package com.classic.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import androidx.core.app.ActivityCompat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 一个镜头的预览 + RAW 拍照 + DNG 保存 的生命周期封装。
 *
 * - open(lens, previewSurface)：打开相机，建双输出 session（预览 + RAW），开始重复预览。
 * - capture(callback)：发一次静态拍照，等 Image + CaptureResult 齐备后用 DngCreator 写 .dng。
 * - close()：释放相机/会话/ImageReader。
 *
 * 多摄：预览和 RAW 两个 OutputConfiguration 都 setPhysicalCameraId 锁定到目标物理镜头。
 */
class CameraController(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val bgHandler: Handler
) {
    private val LOG_TAG = "ClassicCamera"

    private var currentLens: LensInfo? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null

    // RAW 预览每帧回调（把 Bayer 数据 + 动态 gain/黑电平/白电平 喂给 RawPipeline）
    // blR/blG/blB/wl = null 表示动态数据尚未到达，由接收方保持已有值（如 applyLensParams 设置的静态值）
    var onRawFrame: ((bayer: ShortArray, w: Int, h: Int, wbR: Float, wbG: Float, wbB: Float,
                      blR: Float?, blG: Float?, blB: Float?, wl: Float?) -> Unit)? = null

    // GPU JPEG 处理器：通过 queueEvent 在 GL 线程渲染，CountDownLatch 同步等待结果
    // 返回 null 则跳过 JPEG 保存
    var gpuJpegProcessor: ((
        rawShorts: ShortArray, w: Int, h: Int,
        blackLevelR: Float, blackLevelG: Float, blackLevelB: Float,
        whiteLevel: Float,
        wbR: Float, wbG: Float, wbB: Float,
        ccm: FloatArray,
        cfaType: Int
    ) -> Bitmap?)? = null

    // 拍照同步：等 Image 和 CaptureResult 都到达
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var captureLatch: CountDownLatch? = null
    private var captureCallback: ((dngName: String, jpgName: String, dynamic: Map<String, String>) -> Unit)? = null
    private var captureCharacteristics: CameraCharacteristics? = null

    /** 设置开关：拍照时是否保存 DNG（由设置界面控制）。 */
    var saveDng: Boolean = true

    // ---- 手动曝光控制 ----
    /** 手动曝光控制器（由 MainActivity 注入）。 */
    var manualController: ManualController? = null

    /** 当前 session 的 repeating capture 回调引用，供 updateCaptureParams 重建 request 时复用。 */
    private var captureCb: CameraCaptureSession.CaptureCallback? = null

    /** 打开镜头并开始 RAW repeating 预览（RAW 当 preview target）。 */
    fun open(lens: LensInfo) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(LOG_TAG, "no camera permission")
            return
        }
        // 必须先同步释放上一轮（关 session/device/reader），再开新相机，
        // 否则旧设备未释放完就 configureStreams 会 CAMERA_DISCONNECTED；
        // 同时递增 seq 作废任何在途的旧 onOpened 回调。
        openSeq++
        teardownInternal()
        val mySeq = openSeq
        currentLens = lens

        try {
            cameraManager.openCamera(lens.logicalCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (mySeq != openSeq) { camera.close(); return } // 被新一轮取代
                    cameraDevice = camera
                    startSession(camera, lens)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); if (cameraDevice === camera) cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(LOG_TAG, "openCamera error=$error")
                    camera.close(); if (cameraDevice === camera) cameraDevice = null
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "openCamera err", e)
        }
    }
    @Volatile private var openSeq = 0

    /** 关掉当前轮所占的 session/device/reader，但不递增序列号（close 时另递增）。 */
    private fun teardownInternal() {
        try {
            session?.close(); session = null
            cameraDevice?.close(); cameraDevice = null
            rawReader?.close(); rawReader = null
            pendingImage?.close(); pendingImage = null
            pendingResult = null
            captureLatch = null
            captureCallback = null
        } catch (e: Exception) {
            Log.e(LOG_TAG, "teardown err", e)
        }
    }

    private fun startSession(camera: CameraDevice, lens: LensInfo) {
        // 物理镜头的 RAW 尺寸用物理镜头 characteristics 取
        val charIdForRaw = lens.physicalCameraId ?: lens.logicalCameraId
        val characteristics = cameraManager.getCameraCharacteristics(charIdForRaw)
        captureCharacteristics = characteristics
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = configMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
        if (rawSizes.isNullOrEmpty()) {
            Log.e(LOG_TAG, "no RAW sizes for lens $charIdForRaw")
            return
        }
        val maxSize = rawSizes.maxByOrNull { it.width * it.height }!!
        // repeating 模式下需要更多缓冲
        rawReader = ImageReader.newInstance(maxSize.width, maxSize.height, ImageFormat.RAW_SENSOR, 5)
        rawReader?.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage()
            if (img == null) return@setOnImageAvailableListener
            // 拍照模式（captureLatch 有值）：攒图存 DNG
            if (captureLatch != null) {
                pendingImage?.close()
                pendingImage = img
                tryWriteDng()
                return@setOnImageAvailableListener
            }
            // 预览模式：取 Bayer 数据 + 动态 gain 回调
            try {
                processRawPreviewFrame(img)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "preview frame err", e)
            } finally {
                img.close()
            }
        }, bgHandler)

        val rawSurface = rawReader!!.surface
        val stateCb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                startRawRepeating(s)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(LOG_TAG, "session config failed")
            }
        }

        // 配置 stream 前再次确认序列仍有效，且吞掉竞态抛的 CAMERA_DISCONNECTED，
        // 避免在 onOpened 回调里向上抛导致 FATAL。
        if (lens != currentLens) { camera.close(); return }
        try {
            if (Build.VERSION.SDK_INT >= 28 && lens.physicalCameraId != null) {
                val rawConfig = OutputConfiguration(rawSurface)
                rawConfig.setPhysicalCameraId(lens.physicalCameraId)
                val executor = Executor { r -> bgHandler.post(r) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(rawConfig),
                    executor,
                    stateCb
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                camera.createCaptureSession(listOf(rawSurface), stateCb, bgHandler)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "createCaptureSession err (likely teardown race): ${e.message}")
            try { camera.close() } catch (_: Exception) {}
        }
    }

    /** 把 RAW Image 的 Bayer 数据 + 白平衡 gain 取出喂给 pipeline。 */
    private fun processRawPreviewFrame(img: Image) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = img.width
        val h = img.height
        val bayer = ShortArray(w * h)
        // RAW_SENSOR 的 plane 是连续的 16-bit，通常 rowStride = w*2, pixelStride=2
        if (rowStride == w * 2 && pixelStride == 2) {
            buf.asShortBuffer().get(bayer)
        } else {
            // 兜底逐像素
            val sb = buf.asShortBuffer()
            var idx = 0
            for (row in 0 until h) {
                for (col in 0 until w) {
                    bayer[idx++] = sb.get(row * (rowStride / 2) + col * (pixelStride / 2))
                }
            }
        }
        // 白平衡 gain：从总结果拿最优；预览帧没 TotalCaptureResult，先用静态默认 1,1,1
        // (动态 gain 随 CaptureCallback 更新到 lastWBGain)
        val wb = lastWBGain
        // 诊断：每秒打印一次 RAW 取值与回调是否触发（黑屏排查用）
        previewFrameCount++
        val nowNs = System.nanoTime()
        if (nowNs - lastPreviewLog > 1_000_000_000L) {
            // 采样中心像素与统计
            val center = (h/2)*w + (w/2)
            val minVals = IntArray(4) { Int.MAX_VALUE }; val maxVals = IntArray(4)
            var step = 0
            var i = 0
            while (i < bayer.size && step < 20000) {
                // 粗采样看范围
                val v = bayer[i].toInt() and 0xFFFF
                val idx = (i % 4)
                if (v < minVals[idx]) minVals[idx] = v
                if (v > maxVals[idx]) maxVals[idx] = v
                i += 3; step++
            }
            Log.d(LOG_TAG, "previewFrame OK w=$w h=$h center=${bayer[center].toInt() and 0xFFFF} " +
                "range0=[${minVals.minOrNull()}:${maxVals.maxOrNull()}] onRawFrameSet=${onRawFrame != null} " +
                "rowStride=$rowStride pixelStride=$pixelStride buf.remaining=${buf.remaining()} " +
                "bayer[0..5]=${bayer.take(6).map { it.toInt() and 0xFFFF }} " +
                "dynBl=${lastDynamicBlackLevel?.let { "[%.0f,%.0f,%.0f]".format(it[0],it[1],it[2]) } ?: "null"} " +
                "dynWl=${lastDynamicWhiteLevel ?: "null"}")
            previewFrameCount = 0
            lastPreviewLog = nowNs
        }
        // 传递动态黑/白电平（null 表示尚未收到 → 由接收方保持已有静态值）
        val bl = lastDynamicBlackLevel
        val blR = bl?.elementAtOrNull(0)
        val blG = bl?.elementAtOrNull(1)
        val blB = bl?.elementAtOrNull(2)
        val wl = lastDynamicWhiteLevel
        onRawFrame?.invoke(bayer, w, h, wb[0], wb[1], wb[2], blR, blG, blB, wl)

        // 半自动 AE 反馈：若某一参数手动、另一参数 Auto，则根据画面亮度自动调节
        updateSemiAutoExposure(bayer, w, h)
    }
    private var previewFrameCount = 0
    private var lastPreviewLog = 0L

    @Volatile private var lastWBGain = floatArrayOf(1f, 1f, 1f)

    // ---- 半自动 AE 反馈环状态 ----
    private var autoAeFrameCount = 0
    /** 诊断计数器，每 60 帧打一次日志。 */
    private var diagFrameCount = 0

    /**
     * 半自动 AE 反馈环（快门优先 / ISO 优先）。
     *
     * 当 isManualExposure=true 但只有一轴手动时（例如快门固定、ISO=Auto），
     * 逐帧分析 RAW 均值亮度，以 18% 灰（whiteLevel × 0.18）为目标，
     * 自动调节 Auto 轴参数，使画面曝光趋近标准测光值。
     *
     * 约每 10 帧调一次，避免震荡。
     */
    private fun updateSemiAutoExposure(bayer: ShortArray, w: Int, h: Int) {
        val mc = manualController ?: return
        if (!mc.isManualExposure) {
            diagFrameCount = 0
            return
        }
        val adjustIso = !mc.isIsoManual
        val adjustShutter = !mc.isShutterManual
        if (!adjustIso && !adjustShutter) return

        // 1. 中心矩形区域采样 ~1000 像素（50%×50% 中心区域，中心加权）
        val rectLeft = w / 4
        val rectTop = h / 4
        val rectRight = w * 3 / 4
        val rectBottom = h * 3 / 4
        val rectW = rectRight - rectLeft
        val rectH = rectBottom - rectTop
        val rectTotal = rectW * rectH
        val step = maxOf(1, rectTotal / 1000)
        val cx = w * 0.5
        val cy = h * 0.5
        val invHalfW = 1.0 / (rectW * 0.5)
        val invHalfH = 1.0 / (rectH * 0.5)
        var weightedSum = 0.0
        var weightSum = 0.0
        var idx = 0
        while (idx < rectTotal) {
            val lx = idx % rectW
            val ly = idx / rectW
            val px = rectLeft + lx
            val py = rectTop + ly
            val dx = (px - cx) * invHalfW
            val dy = (py - cy) * invHalfH
            val distSq = (dx * dx + dy * dy).coerceAtMost(2.0)
            val distNorm = distSq / 2.0
            val wt = 1.0 - distNorm * 0.8  // 矩形中心=1.0, 矩形边缘=0.2
            weightedSum += (bayer[py * w + px].toInt() and 0xFFFF) * wt
            weightSum += wt
            idx += step
        }
        val mean = (weightedSum / weightSum).toFloat()

        // 2. 归一化到 0~1（减黑电平，除以白电平）
        val wl = lastDynamicWhiteLevel
            ?: captureCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat()
            ?: 1023f
        val bl = lastDynamicBlackLevel
        val blAvg = bl?.let { (it[0] + it[1] + it[2]) / 3f } ?: 64f
        val normalizedMean = ((mean - blAvg) / (wl - blAvg)).coerceIn(0.001f, 1f)

        // 3. 目标：18% 灰
        val target = 0.18f
        val ratio = target / normalizedMean

        // 诊断：每 60 帧打一次当前 ISO/快门/亮度
        diagFrameCount++
        if (diagFrameCount >= 60 && mc.isManualExposure) {
            diagFrameCount = 0
            Log.d(LOG_TAG, "diag: iso=${mc.iso} exp=${mc.exposureTimeNs}ns " +
                "mean=${"%.0f".format(mean)} normalizedMean=${"%.3f".format(normalizedMean)} " +
                "target=${"%.3f".format(target)} ratio=${"%.2f".format(ratio)} " +
                "shutterManual=${mc.isShutterManual} isoManual=${mc.isIsoManual}")
        }

        // 4. 每 10 帧调一次（避免震荡）
        autoAeFrameCount++
        if (autoAeFrameCount < 10) return
        autoAeFrameCount = 0

        // 5. 偏差在 ±7% 内视为稳定，不调
        if (ratio in 0.93f..1.07f) return

        // 6. 调节 Auto 轴
        if (adjustIso) {
            val newIso = (mc.iso * ratio).toInt().coerceIn(mc.sensitivityMin, mc.sensitivityMax)
            if (newIso != mc.iso) {
                mc.iso = newIso
                Log.d(LOG_TAG, "semiAE: iso mean=${"%.0f".format(mean)} normalizedMean=${"%.3f".format(normalizedMean)} ratio=${"%.2f".format(ratio)} iso=%d".format(mc.iso))
                bgHandler.post { updateCaptureParams() }
            }
        } else if (adjustShutter) {
            val newExp = (mc.exposureTimeNs * ratio).toLong().coerceIn(mc.exposureMinNs, mc.exposureMaxNs)
            if (newExp != mc.exposureTimeNs) {
                mc.exposureTimeNs = newExp
                Log.d(LOG_TAG, "semiAE: shutter mean=${"%.0f".format(mean)} normalizedMean=${"%.3f".format(normalizedMean)} ratio=${"%.2f".format(ratio)} exp=%d".format(newExp))
                bgHandler.post { updateCaptureParams() }
            }
        }
    }

    // 动态黑/白电平（帧级别），由 startRawRepeating 的 onCaptureCompleted 更新
    // null = 尚未收到第一帧 TotalCaptureResult，由接收方保持静态值
    @Volatile private var lastDynamicBlackLevel: FloatArray? = null  // [R, G_avg, B]
    @Volatile private var lastDynamicWhiteLevel: Float? = null

    // 预览重复请求最近一帧的 TotalCaptureResult，供 DNG 元数据使用
    @Volatile private var lastRepeatingResult: TotalCaptureResult? = null

    // 拍照时刻快照的参数（保证 JPEG 色彩与预览完全一致）
    private var captureSnapshotBlackLevel: FloatArray? = null
    private var captureSnapshotWhiteLevel: Float? = null
    private var captureSnapshotWB: FloatArray? = null

    private fun startRawRepeating(session: CameraCaptureSession) {
        try {
            val device = cameraDevice
            val reader = rawReader
            if (device == null || reader == null) {
                Log.e(LOG_TAG, "startRawRepeating: device/reader null")
                return
            }
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(reader.surface)
            val cb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(s, request, result)
                    // 更新白平衡 gain（动态）
                    var wbUpdated = false
                    val rggb = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                    if (rggb != null) {
                        val r = rggb.red
                        val gAvg = (rggb.greenEven + rggb.greenOdd) * 0.5f
                        val b = rggb.blue
                        // 某些镜头（如前置）虽然提供 gains 但恒为 [1,1,1]（无实际校正）
                        if (kotlin.math.abs(r - 1f) > 0.01f || kotlin.math.abs(gAvg - 1f) > 0.01f || kotlin.math.abs(b - 1f) > 0.01f) {
                            lastWBGain = floatArrayOf(r, gAvg, b)
                            wbUpdated = true
                        }
                    }
                    if (!wbUpdated) {
                        // fallback: 从 neutralColorPoint 反算白平衡
                        val neutralKey = result.keys.firstOrNull {
                            it.name == CaptureResult.SENSOR_NEUTRAL_COLOR_POINT.name
                        }
                        if (neutralKey != null) {
                            val raw = result.get(neutralKey)
                            if (raw is Array<*> && raw.size >= 3) {
                                val r = (raw[0] as? android.util.Rational)?.toFloat() ?: 0f
                                val g = (raw[1] as? android.util.Rational)?.toFloat() ?: 0f
                                val b = (raw[2] as? android.util.Rational)?.toFloat() ?: 0f
                                if (r > 0f && b > 0f) {
                                    lastWBGain = floatArrayOf(g / r, 1.0f, g / b)
                                    wbUpdated = true
                                }
                            }
                            if (!wbUpdated && raw is DoubleArray && raw.size >= 3) {
                                if (raw[0] > 0.0 && raw[2] > 0.0) {
                                    lastWBGain = floatArrayOf((raw[1] / raw[0]).toFloat(), 1.0f, (raw[1] / raw[2]).toFloat())
                                    wbUpdated = true
                                }
                            }
                        }
                    }
                    // 更新动态黑电平（帧级别，优于静态 SensorCharacteristics 值）
                    val dynBl = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                    if (dynBl != null && dynBl.size >= 4) {
                        lastDynamicBlackLevel = floatArrayOf(
                            dynBl[0].toFloat() + 2f,
                            ((dynBl[1] + dynBl[2]) * 0.5).toFloat() + 2f,
                            dynBl[3].toFloat() + 2f
                        )
                    }
                    // 更新动态白电平（帧级别，优于静态 SensorCharacteristics 值）
                    val dynWl = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                    if (dynWl != null && dynWl > 0) {
                        lastDynamicWhiteLevel = dynWl.toFloat()
                    }
                    lastRepeatingResult = result
                    rawFrameCount++
                    val now = System.nanoTime()
                    if (now - lastFpsTime > 1_000_000_000L) {
                        val fps = rawFrameCount.toFloat() * 1_000_000_000f / (now - lastFpsTime)
                        Log.d(LOG_TAG, "RAW repeating fps=%.1f wb=[${
                            "%.2f,%.2f,%.2f".format(lastWBGain[0], lastWBGain[1], lastWBGain[2])
                        }]".format(fps))
                        rawFrameCount = 0
                        lastFpsTime = now
                    }
                }
            }
            captureCb = cb
            // 应用手动控制参数（如果有）
            manualController?.applyTo(builder)
            session.setRepeatingRequest(builder.build(), cb, bgHandler)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "startRawRepeating err", e)
        }
    }
    private var rawFrameCount = 0
    private var lastFpsTime = System.nanoTime()

    /** 拍照：拦截下一帧预览 Image 做 DNG，用快照参数保证 JPEG 色彩与预览一致。 */
    fun capture(callback: (dngName: String, jpgName: String, dynamic: Map<String, String>) -> Unit) {
        if (session == null) { Log.e(LOG_TAG, "no session"); return }
        val lens = currentLens ?: return
        captureCallback = callback
        pendingImage = null
        pendingResult = lastRepeatingResult
        // 快照当前动态参数（与预览使用的参数一致，保证 JPEG 色彩完全相同）
        captureSnapshotBlackLevel = lastDynamicBlackLevel?.copyOf()
            ?: lens.blackLevelPattern?.let { parseBlackLevel(it) }
            ?: floatArrayOf(0f, 0f, 0f)
        captureSnapshotWhiteLevel = lastDynamicWhiteLevel
            ?: (lens.whiteLevel?.toFloat() ?: 1023f)
        captureSnapshotWB = lastWBGain.copyOf()
        // 拦截下一帧预览 Image（不再发新的 STILL_CAPTURE 请求，避免 AE 状态切换）
        captureLatch = CountDownLatch(1)
    }

    /** Image 到齐后写 DNG/JPG（只等 Image，不再等待独立的 CaptureResult）。 */
    private fun tryWriteDng() {
        val img = pendingImage ?: return
        val characteristics = captureCharacteristics ?: return
        val cb = captureCallback ?: return

        val result = pendingResult
        val dynamic = if (result != null) collectDynamicFields(result) else emptyMap()

        val dngName = if (saveDng && result != null) {
            writeDng(characteristics, result, img, lens = currentLens)
        } else {
            if (!saveDng) Log.d(LOG_TAG, "DNG saving disabled by user setting")
            else Log.w(LOG_TAG, "no TotalCaptureResult for DNG, skipping DNG")
            ""
        }
        val jpgName = writeJpeg(img, currentLens, result)
        img.close()
        pendingImage = null
        pendingResult = null
        captureLatch = null
        captureCallback = null

        cb(dngName, jpgName, dynamic)
    }

    /**
     * 通过 MediaStore 写入共享目录 Pictures/gufa/，可在文件管理器/相册直接看到。
     * 返回文件显示名（供 Toast）。
     */
    private fun writeDng(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        lens: LensInfo?
    ): String {
        val displayName = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())}_" +
            (lens?.focalLength?.let { "${it}mm" } ?: "") + ".dng"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            // Android 10+ 用 RELATIVE_PATH 指定子目录（相对共享根 Pictures/）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/gufa")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run { Log.e(LOG_TAG, "MediaStore insert failed"); return displayName }

        var bytes = 0L
        try {
            resolver.openOutputStream(uri)?.use { out: OutputStream ->
                DngCreator(characteristics, result).apply {
                    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    setOrientation(degToExifOrientation(sensorOrientation))
                }.use { dng ->
                    dng.writeImage(out, image)
                }
                // openOutputStream 后只能近似得到大小，这里以写入完成标记
            }
            bytes = try { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L } catch (e: Exception) { 0L }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.d(LOG_TAG, "DNG saved: $displayName ($bytes bytes) -> Pictures/gufa/")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "writeDng err", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
        }
        return displayName
    }

    /**
     * 用预览帧的快照参数将 RAW 渲染为全分辨率 JPEG，并写入 EXIF。
     * 所有色彩参数来自 capture 时刻的快照（与预览一致），不从 TotalCaptureResult 反算。
     */
    private fun writeJpeg(
        image: Image,
        lens: LensInfo?,
        captureResult: TotalCaptureResult?
    ): String {
        val displayName = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())}_" +
            (lens?.focalLength?.let { "${it}mm" } ?: "") + ".jpg"

        val processor = gpuJpegProcessor
        if (processor == null) {
            Log.w(LOG_TAG, "gpuJpegProcessor not set, skipping JPEG")
            return displayName
        }

        val rawShorts = extractRawShorts(image)

        // ★ 使用快照参数（与预览使用的完全一致）
        val blackLevel = captureSnapshotBlackLevel ?: run {
            lens?.blackLevelPattern?.let { parseBlackLevel(it) } ?: floatArrayOf(0f, 0f, 0f)
        }
        val whiteLevel = captureSnapshotWhiteLevel
            ?: (lens?.whiteLevel?.toFloat() ?: 1023f)
        val wbGain = captureSnapshotWB ?: floatArrayOf(1f, 1f, 1f)

        // CCM 与 CFA 是镜头静态值（和预览完全一致），加全零检测防止 JPEG 全黑
        val ccm = lens?.forwardMatrix1?.let {
            val parsed = parseColorMatrix(it)
            if (parsed.all { v -> v == 0f }) null  // 全零 → fallback to identity
            else mergeForwardMatrixToSRGB(parsed)
        } ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val cfaType = lens?.colorFilterArrangement ?: 2

        Log.d(LOG_TAG, "writeJpeg: previewSnapshot bl=[${blackLevel[0]},${blackLevel[1]},${blackLevel[2]}] wl=$whiteLevel wb=[${wbGain[0]},${wbGain[1]},${wbGain[2]}]")

        val bitmap = processor(
            rawShorts, image.width, image.height,
            blackLevel[0], blackLevel[1], blackLevel[2],
            whiteLevel,
            wbGain[0], wbGain[1], wbGain[2],
            ccm, cfaType
        )
        if (bitmap == null) {
            Log.w(LOG_TAG, "GPU JPEG returned null, skipping JPEG")
            return displayName
        }

        Log.d(LOG_TAG, "JPEG: GPU rendered ${image.width}x${image.height}")

        // 提取 EXIF 信息
        val iso = captureResult?.get(CaptureResult.SENSOR_SENSITIVITY)
        val exposureTimeNs = captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val aperture = lens?.aperture
        val actualFl = lens?.focalLength
        // 35mm 等效焦距（按钮上显示的那个值）
        val equivFl = if (lens != null && lens.sensorWidthMm > 0f && lens.sensorHeightMm > 0f) {
            val diag = sqrt((lens.sensorWidthMm * lens.sensorWidthMm + lens.sensorHeightMm * lens.sensorHeightMm).toDouble())
            val cropFactor = 43.27 / diag
            (lens.focalLength * cropFactor).roundToInt()
        } else null

        return saveBitmapAsJpeg(context, bitmap, displayName, iso, exposureTimeNs, aperture, actualFl, equivFl)
    }

    /** 复用 DYNAMIC_KEYS：取本次拍照的动态字段，供回调/log。 */
    private fun collectDynamicFields(result: TotalCaptureResult): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (keyName in DYNAMIC_KEYS) {
            val key = result.keys.firstOrNull { it.name == keyName } ?: continue
            try {
                map[keyName] = formatValue(result.get(key))
            } catch (e: Exception) {
                map[keyName] = "<读取错误>"
            }
        }
        // 关键曝光三要素单独 log
        Log.d(LOG_TAG, "dynamic: exposure=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} " +
            "iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)} " +
            "wb=${result.get(CaptureResult.COLOR_CORRECTION_GAINS)}")
        return map
    }

    /** 释放（生命周期用）。作废当前轮，并回收资源。 */
    fun close() {
        openSeq++ // 作废回调
        teardownInternal()
    }

    /**
     * 动态更新 repeating capture 参数（如手动曝光/ISO）。
     * 可在 session 存活期间反复调用，无需重建 session，下一帧即生效。
     * 由 MainActivity 的 SeekBar 回调触发。
     */
    fun updateCaptureParams() {
        val s = session ?: return
        val dev = cameraDevice ?: return
        val reader = rawReader ?: return
        val cb = captureCb ?: return
        try {
            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(reader.surface)
            manualController?.applyTo(builder)
            s.setRepeatingRequest(builder.build(), cb, bgHandler)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "updateCaptureParams err", e)
        }
    }

    /** SENSOR_ORIENTATION（角度）→ DngCreator.setOrientation 所需的 EXIF 方向值。 */
    private fun degToExifOrientation(deg: Int): Int = when (deg) {
        90 -> 6   // rotate 90 CW
        180 -> 3  // rotate 180
        270 -> 8  // rotate 270 CW (90 CCW)
        else -> 1 // 0° = normal
    }
}
