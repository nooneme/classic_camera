package com.classic.camera

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    // UI 组件
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var btnShutter: ImageButton
    private lateinit var btnFilter: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var lensButtonBar: LinearLayout

    // 手动曝光控制 UI
    private lateinit var sbShutterSpeed: SeekBar
    private lateinit var sbIso: SeekBar
    private lateinit var tvShutterSpeed: TextView
    private lateinit var tvIso: TextView
    // 状态栏
    private lateinit var tvStatusShutter: TextView
    private lateinit var tvStatusIso: TextView
    private lateinit var tvStatusFilter: TextView

    private var pipeline: RawPipeline? = null
    private var gpuAlignMerge: GpuAlignMerge? = null

    // Camera2 相关
    private lateinit var cameraManager: CameraManager
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraController: CameraController? = null

    // 手动曝光控制器
    private var manualController: ManualController? = null

    /** 滑块调节震动节流：上次震动的 progress 值。 */
    private var lastShutterTick = 0
    private var lastIsoTick = 0

    /** 拍照处理中连续震动。 */
    private var captureVibRunning = false
    private val captureVibHandler = Handler(Looper.getMainLooper())

    /** 当前滤镜路径（空串=无滤镜） */
    /** 设置弹窗引用（拖拽滑块时透明化用） */
    private var settingsDialog: AlertDialog? = null
    private var settingsDialogOriginalBg: android.graphics.drawable.Drawable? = null
    private val settingsLayoutChildren = mutableListOf<android.view.View>()

    private var currentFilterPath: String = ""
        set(value) {
            field = value
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("current_filter_path", value).apply()
        }

    /** 最后拍摄的照片 URI（为空串表示无照片） */
    private var lastPhotoUri: String = ""
        set(value) {
            field = value
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("last_photo_uri", value).apply()
        }

    // ---- LUT 选择器 ----
    private lateinit var lutAdapter: LutSelectorAdapter
    private lateinit var lutRecyclerView: RecyclerView

    /** 滤镜选择结果接收器 */
    private val filterLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            loadLutList()
            return@registerForActivityResult
        }
        val path = result.data?.getStringExtra("filter_path") ?: ""
        currentFilterPath = path
        lutAdapter.selectedPath = path
        tvStatusFilter.text = if (path.isNotEmpty()) "滤镜: ${java.io.File(path).nameWithoutExtension}" else "滤镜: 无"
        // 先在主线程加载 .cube 文件（避免 GL 线程做 I/O）
        val lut = if (path.isNotEmpty()) LutUtils.loadCubeFile(java.io.File(path)) else null
        glSurfaceView.queueEvent {
            pipeline?.setLut(lut)
            if (lut != null) {
                Log.d(LOG_TAG, "滤镜已应用: $path")
            } else if (path.isNotEmpty()) {
                Log.w(LOG_TAG, "滤镜文件加载失败: $path")
            }
            // 强制刷新画面
            glSurfaceView.requestRender()
        }
    }

    // 已探测的镜头列表与当前选中
    private var lensList: List<LensInfo> = emptyList()
    private var selectedLens: LensInfo? = null

    private val LOG_TAG = "ClassicCamera"

    /** 权限暂存：用户授予权限后自动继续被中断的操作。 */
    private var pendingPermissionAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 使用中保持屏幕常亮（拍照取景不熄屏）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        btnShutter = findViewById(R.id.btnShutter)
        btnFilter = findViewById(R.id.btnFilter)
        btnSettings = findViewById(R.id.btnSettings)
        lensButtonBar = findViewById(R.id.lensButtonBar)

        // 手动控制 UI
        sbShutterSpeed = findViewById(R.id.sbShutterSpeed)
        sbIso = findViewById(R.id.sbIso)
        tvShutterSpeed = findViewById(R.id.tvShutterSpeed)
        tvIso = findViewById(R.id.tvIso)
        // 状态栏
        tvStatusShutter = findViewById(R.id.tvStatusShutter)
        tvStatusIso = findViewById(R.id.tvStatusIso)
        tvStatusFilter = findViewById(R.id.tvStatusFilter)

        // GL 上下文：仅设置 MAJOR=3，EGL 自动返回设备支持的最高 3.x 版本（S23→ES 3.2）
        glSurfaceView.setEGLContextFactory(object : GLSurfaceView.EGLContextFactory {
            override fun createContext(egl: EGL10, display: EGLDisplay, eglConfig: EGLConfig): EGLContext {
                val attr = intArrayOf(0x3098, 3, 0x3038) // EGL_CONTEXT_CLIENT_VERSION = 3
                return egl.eglCreateContext(display, eglConfig, EGL10.EGL_NO_CONTEXT, attr)
            }
            override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
                egl.eglDestroyContext(display, context)
            }
        })
        pipeline = RawPipeline()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.also {
            pipeline?.blackLevelOffset = it.getInt("black_level_offset", 0).toFloat()
            pipeline?.whiteLevelOffset = it.getInt("white_level_offset", 0).toFloat()
            pipeline?.toneMapD = it.getFloat("tone_map_d", 0.59f)
            pipeline?.toneMapE = it.getFloat("tone_map_e", 0.14f)
        }
        // 加载色调曲线
        val savedCurve = ToneCurve.load(prefs)
        val toneCurveNativeArray = savedCurve.toNativeArray()
        pipeline?.toneCurvePoints = toneCurveNativeArray

        // 恢复上次选择的滤镜
        currentFilterPath = prefs.getString("current_filter_path", "") ?: ""
        if (currentFilterPath.isNotEmpty()) {
            val savedLut = LutUtils.loadCubeFile(java.io.File(currentFilterPath))
            if (savedLut != null) {
                pipeline?.lutFloatArray = savedLut
            } else {
                currentFilterPath = ""
            }
        }
        tvStatusFilter?.text = if (currentFilterPath.isNotEmpty())
            "滤镜: ${java.io.File(currentFilterPath).nameWithoutExtension}" else "滤镜: 无"

        // ---- LUT 选择器初始化 ----
        lutRecyclerView = findViewById(R.id.lutRecyclerView)
        lutRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        lutAdapter = LutSelectorAdapter(
            onItemClick = { entry ->
                val path = entry.file?.absolutePath ?: ""
                currentFilterPath = path
                tvStatusFilter.text = if (path.isNotEmpty())
                    "滤镜: ${java.io.File(path).nameWithoutExtension}" else "滤镜: 无"
                val lut = entry.lutData
                glSurfaceView.queueEvent {
                    pipeline?.setLut(lut)
                    glSurfaceView.requestRender()
                }
            },
            onPhase1Complete = {
                val idx = lutAdapter.currentSelectedIndex()
                if (idx >= 0) lutRecyclerView.post {
                    val lm = lutRecyclerView.layoutManager as LinearLayoutManager
                    val halfW = lutRecyclerView.width / 2
                    val itemHalfW = (26 * resources.displayMetrics.density + 0.5f).toInt()
                    lm.scrollToPositionWithOffset(idx, (halfW - itemHalfW).coerceAtLeast(0))
                }
            }
        )
        lutAdapter.selectedPath = currentFilterPath
        lutRecyclerView.adapter = lutAdapter

        findViewById<android.widget.ImageView>(R.id.btnManageFilters).setOnClickListener {
            val intent = Intent(this, FilterActivity::class.java).apply {
                putExtra("current_filter", currentFilterPath)
            }
            filterLauncher.launch(intent)
        }

        LutUtils.seedPresetFilters(this)
        loadLutList()
        restoreLastPhotoUri()

        glSurfaceView.setRenderer(pipeline)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        val focusIndicator = findViewById<View>(R.id.focusIndicator)
        glSurfaceView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val vw = glSurfaceView.width
                val vh = glSurfaceView.height
                viewToSensorNorm(event.x / vw, event.y / vh, vw, vh)?.let { (sx, sy) ->
                    cameraController?.focusOnPoint(sx, sy)
                }

                // 对焦框：缩小弹入后常亮，直到下次点击
                val size = (80 * resources.displayMetrics.density + 0.5f).toInt()
                focusIndicator.apply {
                    translationX = event.x - size / 2f
                    translationY = event.y - size / 2f
                    layoutParams = layoutParams.also {
                        it.width = size
                        it.height = size
                    }
                    alpha = 1f
                    scaleX = 1.3f
                    scaleY = 1.3f
                    visibility = View.VISIBLE
                }
                focusIndicator.animate().cancel()
                focusIndicator.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .start()
            }
            true
        }

        // GPU 多帧融合管线（在 GL context 就绪后的 onSurfaceCreated 中初始化）
        val gpuAligner = GpuAlignMerge()
        gpuAlignMerge = gpuAligner
        pipeline?.onGluReady = {
            gpuAligner.init()
            Log.d(LOG_TAG, "GPU multi-frame pipeline ready = ${gpuAligner.isSupported()}")
            // GL 上下文已就绪，初始化色调曲线纹理
            pipeline?.toneCurvePoints?.let {
                pipeline?.setToneCurve(it)
            }
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // 初始化手动曝光控制器
        manualController = ManualController()
        setupManualControls()

        // 启动流程：有缓存直接进拍照界面，无缓存走探测
        val cached = LensStore.load(this)
        if (cached != null) {
            enterCameraUI(cached)
        } else {
            ensurePermission { startDetection() }
        }

        // 最后照片按钮（原滤镜按钮）
        btnFilter.setOnClickListener {
            if (lastPhotoUri.isEmpty()) {
                Toast.makeText(this, "还没有拍照片", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val uri = Uri.parse(lastPhotoUri)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/jpeg")
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "无法打开照片", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 设置按钮
        btnSettings.setOnClickListener { showSettingsDialog() }

        // 快门（任何阶段无权限即请求）
        btnShutter.setOnClickListener {
            ensurePermission {
                if (cameraController == null || selectedLens == null || pipeline == null) {
                    Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
                } else {
                    startCaptureVibration()
                    val useMulti = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("multi_frame", false)

                    if (useMulti) {
                        cameraController?.startMultiCapture { dngName, jpgName ->
                            runOnUiThread {
                                val msg = if (dngName.isNotEmpty())
                                    "多帧合成: $dngName 和 $jpgName → DCIM/Camera/"
                                else
                                    "多帧合成: $jpgName → DCIM/Camera/"
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        cameraController?.capture { dngName, jpgName, _ ->
                            runOnUiThread {
                                val msg = if (dngName.isNotEmpty())
                                    "$dngName 和 $jpgName → DCIM/Camera/"
                                else
                                    "$jpgName → DCIM/Camera/"
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    // ================= 手动曝光控制 =================

    /** 初始化快门速度 / ISO 滑块，设置段落感交互。 */
    private fun setupManualControls() {
        // 快门速度滑块
        sbShutterSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var wasManual = false
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val isManual = progress > 0
                // 边界大震
                if (isManual != wasManual) {
                    triggerHaptic(HEAVY)
                }
                wasManual = isManual
                // 手动区连续小震（节流，正向/反向都检测）
                if (isManual && kotlin.math.abs(progress - lastShutterTick) >= TICK_THROTTLE) {
                    triggerHaptic(TICK)
                    lastShutterTick = progress
                }
                updateLabelForShutter(progress)
                pushExposureParams()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                lastShutterTick = sbShutterSpeed.progress
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 拖拽结束后保存滑块位置及半自动 AE 收敛值
                LensStore.saveSliderProgress(this@MainActivity,
                    sbShutterSpeed.progress, sbIso.progress,
                    manualController?.iso ?: 100,
                    manualController?.exposureTimeNs ?: ManualController.DEFAULT_EXPOSURE_NS)
            }
        })

        // ISO 滑块
        sbIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var wasManual = false
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val isManual = progress > 0
                if (isManual != wasManual) {
                    triggerHaptic(HEAVY)
                }
                wasManual = isManual
                if (isManual && kotlin.math.abs(progress - lastIsoTick) >= TICK_THROTTLE) {
                    triggerHaptic(TICK)
                    lastIsoTick = progress
                }
                updateLabelForIso(progress)
                pushExposureParams()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                lastIsoTick = sbIso.progress
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                LensStore.saveSliderProgress(this@MainActivity,
                    sbShutterSpeed.progress, sbIso.progress,
                    manualController?.iso ?: 100,
                    manualController?.exposureTimeNs ?: ManualController.DEFAULT_EXPOSURE_NS)
            }
        })
    }

    /** 把当前滑块值应用到 ManualController → 通知 CameraController 更新预览。 */
    private fun pushExposureParams() {
        manualController?.updateFromSliders(sbShutterSpeed.progress, sbIso.progress)
        cameraController?.updateCaptureParams()
    }

    /** 更新快门速度显示标签。 */
    private fun updateLabelForShutter(progress: Int) {
        val label = manualController?.shutterProgressToLabel(progress) ?: "?"
        tvShutterSpeed.text = label
        tvStatusShutter.text = "快门 $label"
    }

    /** 更新 ISO 显示标签。 */
    private fun updateLabelForIso(progress: Int) {
        val label = manualController?.isoProgressToLabel(progress) ?: "?"
        tvIso.text = label
        tvStatusIso.text = "ISO $label"
    }

    /** 跨越段落线时震动反馈。 */
    @Suppress("DEPRECATION")
    private fun triggerHaptic(mode: Int) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return
            val effect = if (mode == HEAVY) {
                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                VibrationEffect.createOneShot(6, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        } catch (_: Exception) { }
    }

    /** 按快门时：大震一次，之后每 150ms 小震直到 JPG 生成。 */
    private fun startCaptureVibration() {
        triggerHaptic(HEAVY)
        captureVibRunning = true
        captureVibHandler.postDelayed({ captureVibTick() }, 200)
    }

    private fun captureVibTick() {
        if (!captureVibRunning) return
        triggerHaptic(TICK)
        captureVibHandler.postDelayed({ captureVibTick() }, 150)
    }

    private fun stopCaptureVibration() {
        captureVibRunning = false
        captureVibHandler.removeCallbacksAndMessages(null)
    }

    // ================= 设置 =================

    /** SharedPreferences 文件名 */
    private val PREFS_NAME = "camera_settings"

    /** 显示设置弹窗。 */
    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun applyDng(enabled: Boolean) {
            prefs.edit().putBoolean("save_dng", enabled).apply()
            cameraController?.saveDng = enabled
        }

        fun applyMultiFrame(enabled: Boolean, count: Int) {
            val n = if (enabled) count.coerceIn(2, 8) else 1
            prefs.edit().putBoolean("multi_frame", enabled).apply()
            prefs.edit().putInt("multi_frame_count", n).apply()
            cameraController?.multiFrameCount = n
        }

        val saveDng = prefs.getBoolean("save_dng", false)
        val multiFrame = prefs.getBoolean("multi_frame", false)
        val multiFrameCount = prefs.getInt("multi_frame_count", 4)
        val blOffset = prefs.getInt("black_level_offset", 0)
        val wlOffset = prefs.getInt("white_level_offset", 0)

        val switchDng = Switch(this).apply {
            isChecked = saveDng
            text = "保存 DNG"
            textSize = 16f
            setPadding(48, 24, 48, 24)
            setOnCheckedChangeListener { _, isChecked -> applyDng(isChecked) }
        }

        val switchMultiFrame = Switch(this).apply {
            isChecked = multiFrame
            text = "多帧降噪"
            textSize = 16f
            setPadding(48, 16, 48, 8)
        }

        val tvFrameCount = TextView(this).apply {
            text = "合成帧数: $multiFrameCount"
            textSize = 15f
            setPadding(48, 8, 48, 4)
            visibility = if (multiFrame) android.view.View.VISIBLE else android.view.View.GONE
        }

        val sbFrameCount = SeekBar(this).apply {
            max = 6
            progress = (multiFrameCount - 2).coerceIn(0, 6)
            setPadding(48, 0, 48, 16)
            visibility = if (multiFrame) android.view.View.VISIBLE else android.view.View.GONE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    tvFrameCount.text = "合成帧数: ${progress + 2}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    applyMultiFrame(true, progress + 2)
                }
            })
        }

        switchMultiFrame.setOnCheckedChangeListener { _, isChecked ->
            val vis = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            sbFrameCount.visibility = vis
            tvFrameCount.visibility = vis
            if (isChecked) {
                tvFrameCount.text = "合成帧数: ${sbFrameCount.progress + 2}"
                applyMultiFrame(true, sbFrameCount.progress + 2)
            } else {
                applyMultiFrame(false, 0)
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        layout.addView(switchDng)
        layout.addView(switchMultiFrame)
        layout.addView(tvFrameCount)
        layout.addView(sbFrameCount)

        // ---- 黑电平补偿 ----
        val tvBlOffset = TextView(this).apply {
            text = "黑电平补偿: ${blOffset}"
            textSize = 15f
            setPadding(48, 16, 48, 4)
        }
        val sbBlOffset = SeekBar(this).apply {
            max = 64
            progress = (blOffset + 32).coerceIn(0, 64)
            setPadding(48, 0, 48, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val offset = progress - 32
                    tvBlOffset.text = "黑电平补偿: ${if (offset >= 0) "+$offset" else "$offset"}"
                    glSurfaceView.queueEvent { pipeline?.blackLevelOffset = offset.toFloat() }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    val offset = seekBar.progress - 32
                    glSurfaceView.queueEvent { pipeline?.blackLevelOffset = offset.toFloat() }
                    showToneMapPreview(tvBlOffset, seekBar)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val offset = seekBar.progress - 32
                    prefs.edit().putInt("black_level_offset", offset).apply()
                    hideToneMapPreview()
                }
            })
        }

        // ---- 白电平补偿 ----
        val tvWlOffset = TextView(this).apply {
            text = "白电平补偿: ${wlOffset}"
            textSize = 15f
            setPadding(48, 8, 48, 4)
        }
        val sbWlOffset = SeekBar(this).apply {
            max = 512
            progress = (wlOffset + 256).coerceIn(0, 512)
            setPadding(48, 0, 48, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val offset = progress - 256
                    tvWlOffset.text = "白电平补偿: ${if (offset >= 0) "+$offset" else "$offset"}"
                    glSurfaceView.queueEvent { pipeline?.whiteLevelOffset = offset.toFloat() }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    val offset = seekBar.progress - 256
                    glSurfaceView.queueEvent { pipeline?.whiteLevelOffset = offset.toFloat() }
                    showToneMapPreview(tvWlOffset, seekBar)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val offset = seekBar.progress - 256
                    prefs.edit().putInt("white_level_offset", offset).apply()
                    hideToneMapPreview()
                }
            })
        }

        layout.addView(tvBlOffset)
        layout.addView(sbBlOffset)
        layout.addView(tvWlOffset)
        layout.addView(sbWlOffset)

        // ---- 色调映射 D（分母一次项系数） ----
        val toneMapD = prefs.getFloat("tone_map_d", 0.59f)
        val toneMapE = prefs.getFloat("tone_map_e", 0.14f)
        val tvToneMapD = TextView(this).apply {
            text = "色调 D（默认0.59）: ${"%.2f".format(toneMapD)}"
            textSize = 15f
            setPadding(48, 16, 48, 4)
        }
        val sbToneMapD = SeekBar(this).apply {
            max = 300
            progress = ((toneMapD + 1f) * 100).roundToInt().coerceIn(0, 300)
            setPadding(48, 0, 48, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val v = (progress / 100f) - 1f
                    tvToneMapD.text = "色调 D（默认0.59）: ${"%.2f".format(v)}"
                    glSurfaceView.queueEvent { pipeline?.toneMapD = v }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    val v = (seekBar.progress / 100f) - 1f
                    glSurfaceView.queueEvent { pipeline?.toneMapD = v }
                    showToneMapPreview(tvToneMapD, seekBar)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val v = (seekBar.progress / 100f) - 1f
                    prefs.edit().putFloat("tone_map_d", v).apply()
                    hideToneMapPreview()
                }
            })
        }

        // ---- 色调映射 E（分母常数项系数） ----
        val tvToneMapE = TextView(this).apply {
            text = "色调 E（默认0.14）: ${"%.2f".format(toneMapE)}"
            textSize = 15f
            setPadding(48, 8, 48, 4)
        }
        val sbToneMapE = SeekBar(this).apply {
            max = 40
            progress = ((toneMapE - 0.1f) * 100).roundToInt().coerceIn(0, 40)
            setPadding(48, 0, 48, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val v = 0.1f + progress / 100f
                    tvToneMapE.text = "色调 E（默认0.14）: ${"%.2f".format(v)}"
                    glSurfaceView.queueEvent { pipeline?.toneMapE = v }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    val v = 0.1f + seekBar.progress / 100f
                    glSurfaceView.queueEvent { pipeline?.toneMapE = v }
                    showToneMapPreview(tvToneMapE, seekBar)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val v = 0.1f + seekBar.progress / 100f
                    prefs.edit().putFloat("tone_map_e", v).apply()
                    hideToneMapPreview()
                }
            })
        }

        layout.addView(tvToneMapD)
        layout.addView(sbToneMapD)
        layout.addView(tvToneMapE)
        layout.addView(sbToneMapE)

        // ---- 色调曲线编辑器（直接嵌入弹窗） ----
        val curveH = (200 * resources.displayMetrics.density).toInt()
        val curveView = CurveEditorView(this).apply {
            val savedCurve = ToneCurve.load(prefs)
            setCurve(savedCurve)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, curveH
            ).apply { setMargins(24, 8, 24, 8) }
            onDragStart = {
                settingsDialog?.window?.let { w ->
                    w.setDimAmount(0f)
                    w.decorView.background = null
                }
                for (i in 0 until layout.childCount) {
                    val child = layout.getChildAt(i)
                    child.alpha = if (child === this@apply) 1f else 0f
                }
                settingsDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.alpha = 0f
            }
            onDragEnd = {
                settingsDialog?.window?.let { w ->
                    w.setDimAmount(0.6f)
                    w.decorView.background = settingsDialogOriginalBg
                }
                for (i in 0 until layout.childCount) layout.getChildAt(i).alpha = 1f
                settingsDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.alpha = 1f
            }
            onCurveChanged = { curve ->
                ToneCurve.save(prefs, curve)
                val nativeArr = curve.toNativeArray()
                glSurfaceView.queueEvent {
                    pipeline?.setToneCurve(nativeArr)
                    glSurfaceView.requestRender()
                }
            }
        }
        layout.addView(curveView)

        // ---- LUT 应用强度 ----
        val lutIntensity = prefs.getFloat("lut_intensity", 1f)
        val tvLutIntensity = TextView(this).apply {
            text = "滤镜强度: ${(lutIntensity * 100).toInt()}%"
            textSize = 15f
            setPadding(48, 16, 48, 4)
        }
        glSurfaceView.queueEvent { pipeline?.lutIntensity = lutIntensity }
        val sbLutIntensity = SeekBar(this).apply {
            max = 100
            progress = (lutIntensity * 100).roundToInt().coerceIn(0, 100)
            setPadding(48, 0, 48, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    tvLutIntensity.text = "滤镜强度: ${progress}%"
                    glSurfaceView.queueEvent { pipeline?.lutIntensity = progress / 100f }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    glSurfaceView.queueEvent { pipeline?.lutIntensity = seekBar.progress / 100f }
                    showToneMapPreview(tvLutIntensity, seekBar)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val v = seekBar.progress / 100f
                    prefs.edit().putFloat("lut_intensity", v).apply()
                    hideToneMapPreview()
                }
            })
        }
        layout.addView(tvLutIntensity)
        layout.addView(sbLutIntensity)

        val btnReset = Button(this).apply {
            text = "恢复默认"
            isAllCaps = false
            setPadding(48, 24, 48, 24)
            setTextColor(0xFFFF6B6B.toInt())
            setBackgroundColor(0x22FF6B6B.toInt())
            setOnClickListener {
                switchDng.isChecked = false
                applyDng(false)
                switchMultiFrame.isChecked = false
                sbFrameCount.visibility = android.view.View.GONE
                tvFrameCount.visibility = android.view.View.GONE
                applyMultiFrame(false, 0)
                sbBlOffset.progress = 32
                tvBlOffset.text = "黑电平补偿: 0"
                glSurfaceView.queueEvent { pipeline?.blackLevelOffset = 0f }
                prefs.edit().putInt("black_level_offset", 0).apply()
                sbWlOffset.progress = 256
                tvWlOffset.text = "白电平补偿: 0"
                glSurfaceView.queueEvent { pipeline?.whiteLevelOffset = 0f }
                prefs.edit().putInt("white_level_offset", 0).apply()
                sbToneMapD.progress = 159
                tvToneMapD.text = "色调 D（默认0.59）: 0.59"
                glSurfaceView.queueEvent { pipeline?.toneMapD = 0.59f }
                prefs.edit().putFloat("tone_map_d", 0.59f).apply()
                sbToneMapE.progress = 4
                tvToneMapE.text = "色调 E（默认0.14）: 0.14"
                glSurfaceView.queueEvent { pipeline?.toneMapE = 0.14f }
                prefs.edit().putFloat("tone_map_e", 0.14f).apply()
                // 重置色调曲线为 y=x
                curveView.resetCurve()
                val defaultCurve = ToneCurve()
                ToneCurve.save(prefs, defaultCurve)
                val nativeArr = defaultCurve.toNativeArray()
                glSurfaceView.queueEvent { pipeline?.setToneCurve(nativeArr) }
                // 重置滤镜强度
                sbLutIntensity.progress = 100
                tvLutIntensity.text = "滤镜强度: 100%"
                glSurfaceView.queueEvent { pipeline?.lutIntensity = 1f }
                prefs.edit().putFloat("lut_intensity", 1f).apply()
            }
        }
        layout.addView(btnReset)

        settingsDialog = AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("完成", null)
            .show()
        settingsDialogOriginalBg = settingsDialog?.window?.decorView?.background
        settingsLayoutChildren.clear()
        for (i in 0 until layout.childCount) settingsLayoutChildren.add(layout.getChildAt(i))
    }

    /** 色调映射滑块拖拽时：弹窗全透明，只保留当前文字。 */
    private var toneMapKeepLabel: android.widget.TextView? = null
    private var toneMapLabelOriginalColor: Int = 0

    private fun showToneMapPreview(keepLabel: android.widget.TextView, keepSlider: android.widget.SeekBar) {
        settingsDialog?.window?.let { w ->
            w.setDimAmount(0f)
            w.decorView.background = null
        }
        toneMapKeepLabel = keepLabel
        toneMapLabelOriginalColor = keepLabel.currentTextColor
        keepLabel.setTextColor(0xFFFFFFFF.toInt())
        for (child in settingsLayoutChildren) {
            child.alpha = if (child === keepLabel || child === keepSlider) 1f else 0f
        }
        settingsDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.alpha = 0f
    }

    /** 松手后恢复弹窗显示。 */
    private fun hideToneMapPreview() {
        settingsDialog?.window?.let { w ->
            w.setDimAmount(0.6f)
            w.decorView.background = settingsDialogOriginalBg
        }
        for (child in settingsLayoutChildren) child.alpha = 1f
        settingsDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.alpha = 1f
        toneMapKeepLabel?.setTextColor(toneMapLabelOriginalColor)
        toneMapKeepLabel = null
    }

    // ================= LUT 列表加载 =================

    private fun loadLutList() {
        val dir = getExternalFilesDir("filters") ?: filesDir
        lutAdapter.loadFromDirectory(dir)
    }

    // ================= 最后照片按钮 =================

    /** 恢复最后照片 URI 并更新按钮图标。 */
    private fun restoreLastPhotoUri() {
        lastPhotoUri = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("last_photo_uri", "") ?: ""
        refreshLastPhotoButton()
    }

    /** 根据 lastPhotoUri 更新按钮图标。 */
    private fun refreshLastPhotoButton() {
        if (lastPhotoUri.isEmpty()) {
            btnFilter.setImageResource(R.drawable.ic_photo)
            btnFilter.scaleType = android.widget.ImageView.ScaleType.CENTER
        } else {
            try {
                val uri = Uri.parse(lastPhotoUri)
                val targetSize = (44 * resources.displayMetrics.density + 0.5f).toInt()
                var sampleSize = 1
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                    while (opts.outWidth / (sampleSize * 2) > targetSize && opts.outHeight / (sampleSize * 2) > targetSize) {
                        sampleSize *= 2
                    }
                }
                var bmp: Bitmap? = null
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    bmp = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                }
                if (bmp != null) {
                    val thumb = Bitmap.createScaledBitmap(bmp, targetSize, targetSize, true)
                    btnFilter.setImageBitmap(thumb)
                    btnFilter.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    if (bmp !== thumb) bmp.recycle()
                } else {
                    btnFilter.setImageResource(R.drawable.ic_photo)
                    btnFilter.scaleType = android.widget.ImageView.ScaleType.CENTER
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to load photo thumbnail", e)
                btnFilter.setImageResource(R.drawable.ic_photo)
                btnFilter.scaleType = android.widget.ImageView.ScaleType.CENTER
            }
        }
    }

    companion object {
        private const val HEAVY = 1           // 边界大震 50ms
        private const val TICK = 2             // 调节小震 6ms
        private const val TICK_THROTTLE = 20   // 每差 20 进度震一次（分辨率10000时共~500次）
    }

    // ================= 启动 / 探测 =================

    /** 首次启动：探测所有支持 RAW 的物理镜头（只静态），存本地后进拍照界面。 */
    private fun startDetection() {
        startBackgroundThread()
        backgroundHandler?.post {
            val lenses = discoverLenses()
            LensStore.save(this@MainActivity, lenses)
            runOnUiThread {
                stopBackgroundThread()
                enterCameraUI(lenses)
            }
        }
    }

    /** 进入拍照界面：按焦距升序生成镜头按钮。 */
    private fun enterCameraUI(lenses: List<LensInfo>) {
        lensList = lenses.sortedWith(compareBy({ it.lensFacing == CameraMetadata.LENS_FACING_FRONT }, { it.focalLength }))
        lensButtonBar.removeAllViews()
        val btnHeight = resources.displayMetrics.density.let { (44 * it + 0.5f).toInt() }
        val btnMargins = resources.displayMetrics.density.let { (4 * it + 0.5f).toInt() }
        for (lens in lensList) {
            val btn = Button(this).apply {
                text = if (lens.lensFacing == CameraMetadata.LENS_FACING_FRONT) "自拍"
                       else formatFocalLength(lens.focalLength, lens)
                isAllCaps = false
                minimumHeight = 0
                setTag(lens)
                setOnClickListener { selectLens(lens) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, btnHeight
                ).apply { setMargins(btnMargins, 0, btnMargins, 0) }
            }
            lensButtonBar.addView(btn)
        }
        // 默认选上次使用的镜头，无记录则选焦距中位数
        if (lensList.isNotEmpty()) {
            val lastId = LensStore.loadLastLensId(this)
            val lastLens = lastId?.let { id -> lensList.find { LensStore.lensId(it) == id } }
            selectLens(lastLens ?: lensList[lensList.size / 2])
        }
    }

    private fun selectLens(lens: LensInfo) {
        ensurePermission {
            // 切换镜头时先保存上一支镜头的位置及半自动 AE 收敛值；首次加载（selectedLens==null）不覆盖已存值
            if (selectedLens != null) {
                LensStore.saveSliderProgress(this, sbShutterSpeed.progress, sbIso.progress,
                    manualController?.iso ?: 100,
                    manualController?.exposureTimeNs ?: ManualController.DEFAULT_EXPOSURE_NS)
            }

            selectedLens = lens
            LensStore.saveLastLensId(this, LensStore.lensId(lens))
            refreshLensButtonStyles()

            // 根据镜头实际能力初始化手动曝光档位
            manualController?.initFromLens(lens)
            // 恢复全局滑块位置（所有镜头共用）
            val saved = LensStore.loadSliderProgress(this)
            val shutterPos = (saved?.shutterProgress ?: 0).coerceIn(0, ManualController.SHUTTER_RESOLUTION)
            val isoPos = (saved?.isoProgress ?: 0).coerceIn(0, ManualController.ISO_RESOLUTION)
            sbShutterSpeed.max = ManualController.SHUTTER_RESOLUTION
            sbIso.max = ManualController.ISO_RESOLUTION
            sbShutterSpeed.progress = shutterPos
            sbIso.progress = isoPos
            // setProgress 是代码调用（fromUser=false），监听器跳过标签更新，需手动设
            updateLabelForShutter(shutterPos)
            updateLabelForIso(isoPos)
            // ★ 同步 ManualController 状态（isManualExposure / 实际值），
            // 否则相机打开后 startRawRepeating 的 applyTo 不会切 AE_MODE_OFF
            manualController?.updateFromSliders(shutterPos, isoPos)

            // 恢复半自动 AE 的自动轴收敛值，防止 app 重启后从过低的默认值起步导致画面偏暗
            if (saved != null) {
                val mc = manualController
                if (mc != null) {
                    if (!mc.isShutterManual && saved.autoExposureNs > 0) {
                        mc.exposureTimeNs = saved.autoExposureNs.coerceIn(mc.exposureMinNs, mc.exposureMaxNs)
                    }
                    if (!mc.isIsoManual && saved.autoIso > 0) {
                        mc.iso = saved.autoIso.coerceIn(mc.sensitivityMin, mc.sensitivityMax)
                    }
                }
            }

            // 切换镜头：刷新 pipeline 静态参数并重开 RAW 预览
            applyLensParamsToPipeline(lens)
            openSelectedLensPreview()
        }
    }

    /** 遍历镜头按钮，选中项高亮，其余恢复默认。 */
    private fun refreshLensButtonStyles() {
        for (i in 0 until lensButtonBar.childCount) {
            val child = lensButtonBar.getChildAt(i)
            if (child is Button) {
                val isSelected = child.tag === selectedLens
                if (isSelected) {
                    child.setBackgroundColor(0xFF4A90D9.toInt()) // 蓝色底色
                    child.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    child.setBackgroundColor(0xFF333333.toInt()) // 深灰底色
                    child.setTextColor(0xFFAAAAAA.toInt())
                }
            }
        }
    }

    /** 把镜头静态参数（黑电平/白电平/CCM/CFA/forwardMatrix/orientation）设置到 pipeline。 */
    private fun applyLensParamsToPipeline(lens: LensInfo) {
        val p = pipeline ?: return
        // 黑电平：LensInfo 存的是 BlackLevelPattern.toString()，如 "[64, 64], [64, 64]"
        val bl = parseBlackLevel(lens.blackLevelPattern)
        p.blackLevelR = bl[0]; p.blackLevelG = bl[1]; p.blackLevelB = bl[2]
        p.whiteLevel = lens.whiteLevel.toFloat().coerceAtLeast(1f)
        // CFA 排列类型：0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
        p.cfaType = lens.colorFilterArrangement
        // CCM：本版用 forwardMatrix1（D65 参考）。LensInfo 存了 toString 文本，解析成 9 个 float
        val mat = parseColorMatrix(lens.forwardMatrix1)
        p.setCCM(mat)
        // 传感器安装角度 + 自拍镜像
        p.orientation = lens.sensorOrientation
        p.mirror = (lens.lensFacing == CameraMetadata.LENS_FACING_FRONT)
        // 切换镜头时清空旧的 LSC 数据，等待新帧的增益图
        p.lscGainMap = null
        p.lscGridCols = 0
        p.lscGridRows = 0
        // 诊断
        val sb = StringBuilder("lensParams bl=[${bl[0]},${bl[1]},${bl[2]}] wl=${lens.whiteLevel} " +
            "cfa=${cfaName(lens.colorFilterArrangement)}(${lens.colorFilterArrangement}) " +
            "orient=${lens.sensorOrientation} mirror=${p.mirror} ccm=")
        for (i in mat.indices) sb.append("%.3f ".format(mat[i]))
        sb.append("\n  fwdMat1=${lens.forwardMatrix1}")
        android.util.Log.d("ClassicCamera", sb.toString())
    }

    /** 用当前选中镜头打开 RAW 预览。 */
    private fun openSelectedLensPreview() {
        val lens = selectedLens ?: return
        startBackgroundThread()
        val controller = cameraController ?: CameraController(this, cameraManager, backgroundHandler!!).also {
            cameraController = it
            it.manualController = manualController  // 注入手动曝光控制器
            it.onExposureComplete = { stopCaptureVibration() }
            it.onPhotoSaved = { uri ->
                if (uri != null) {
                    lastPhotoUri = uri.toString()
                    runOnUiThread { refreshLastPhotoButton() }
                }
            }
            it.saveDng = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("save_dng", true)
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            it.multiFrameCount = if (prefs.getBoolean("multi_frame", false)) prefs.getInt("multi_frame_count", 4).coerceIn(2, 8) else 1
            // GPU JPEG 处理器：通过 queueEvent 在 GL 线程渲染，CountDownLatch 同步等待
            // 返回 null 则跳过 JPEG 保存
            it.gpuJpegProcessor = { rawShorts, w, h, blR, blG, blB, wl, wbR, wbG, wbB, ccm, cfa ->
                val latch = CountDownLatch(1)
                var bitmap: Bitmap? = null
                glSurfaceView.queueEvent {
                    try {
                        bitmap = pipeline?.renderCaptureToBitmap(
                            rawShorts, w, h, blR, blG, blB, wl,
                            wbR, wbG, wbB, ccm, cfa
                        )
                        if (bitmap == null) {
                            Log.w(LOG_TAG, "GPU JPEG: pipeline returned null")
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "GPU JPEG render failed: ${e.message}", e)
                    }
                    latch.countDown()
                }
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    Log.e(LOG_TAG, "GPU JPEG render timed out (5s)")
                }
                bitmap // may be null → writeJpeg 跳过 JPEG 保存
            }
            // GPU 多帧融合处理器（在 GL 线程运行，CountDownLatch 同步）
            val aligner = gpuAlignMerge
            if (aligner != null) {
                it.gpuMultiFrameProcessor = { frames, w, h, numTx, numTy, wl ->
                    val latch = CountDownLatch(1)
                    var result: ShortArray? = null
                    var error: Exception? = null
                    glSurfaceView.queueEvent {
                        try {
                            result = aligner.process(frames, w, h, numTx, numTy, wl)
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "GPU multi-frame failed", e)
                            error = e
                        }
                        latch.countDown()
                    }
                    latch.await(15, TimeUnit.SECONDS)
                    if (error != null) throw RuntimeException("GPU multi-frame failed", error)
                    result!!
                }
            }
        }
        controller.onFocusReset = { runOnUiThread { findViewById<View>(R.id.focusIndicator).visibility = View.GONE } }
        // 实时曝光参数回调：自动模式时从 CaptureResult 读取实际值
        controller.onExposureInfo = { iso, exposureNs ->
            runOnUiThread {
                if (iso != null && exposureNs != null) {
                    tvStatusShutter.text = "快门 ${ManualController.formatExposureNs(exposureNs)}"
                    tvStatusIso.text = "ISO $iso"
                }
            }
        }
        // 每帧 RAW 数据回调：更新 pipeline 参数并请求 GL 重绘
        controller.onRawFrame = { buf, w, h, wbR, wbG, wbB, blR, blG, blB, wl ->
            val p = pipeline
            if (p != null) {
                p.rawBuffer = buf
                p.rawW = w; p.rawH = h
                p.wbR = wbR; p.wbG = wbG; p.wbB = wbB
                // 动态黑/白电平（null = 帧级数据尚未到达，保持 applyLensParams 设置的静态值）
                if (blR != null && blG != null && blB != null) {
                    p.blackLevelR = blR; p.blackLevelG = blG; p.blackLevelB = blB
                }
                if (wl != null) p.whiteLevel = wl
                // 桥接 LSC gain map 数据
                controller.lscGainMap?.let { lsc ->
                    p.lscGainMap = lsc
                    p.lscGridCols = controller.lscGridCols
                    p.lscGridRows = controller.lscGridRows
                }
                glSurfaceView.requestRender()
            }
        }
        controller.open(lens)
        // 确保手动控制器始终注入（不论是新创建还是复用的 CameraController）
        controller.manualController = manualController
    }

    /**
     * 遍历所有支持 RAW_SENSOR 的物理镜头，收集静态数据为 LensInfo。不打开相机。
     */
    private fun discoverLenses(): List<LensInfo> {
        val lenses = mutableListOf<LensInfo>()
        val claimedPhysical = mutableSetOf<String>()

        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val physicalIds: Set<String> = if (Build.VERSION.SDK_INT >= 28) {
                characteristics.physicalCameraIds
            } else emptySet()

            if (physicalIds.isNotEmpty()) {
                for (physId in physicalIds) {
                    claimedPhysical.add(physId)
                    val physChar = cameraManager.getCameraCharacteristics(physId)
                    if (supportsRaw(physChar)) {
                        lenses.add(buildLensInfo(id, physId, physChar,
                            "Physical[$physId] (under Logical[$id])"))
                    }
                }
            } else {
                if (id !in claimedPhysical && supportsRaw(characteristics)) {
                    lenses.add(buildLensInfo(id, null, characteristics, "Camera[$id]"))
                }
            }
        }
        return lenses
    }

    private fun buildLensInfo(
        logicalCameraId: String,
        physicalCameraId: String?,
        characteristics: CameraCharacteristics,
        label: String
    ): LensInfo {
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val ill1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
        val ill2 = if (Build.VERSION.SDK_INT >= 28)
            characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) else null
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawMaxSize = configMap?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width * it.height }
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // 读取动态范围支持的曝光/ISO 边界，供手动模式使用
        val expRangeObj = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val expMinNs = expRangeObj?.lower ?: 100_000L
        val expMaxNs = expRangeObj?.upper ?: 1_000_000_000L
        val sensRangeObj = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val sensMin = sensRangeObj?.lower ?: 100
        val sensMax = sensRangeObj?.upper ?: 12_800

        // 传感器物理尺寸 → 35mm 等效焦距换算
        val physSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorW = physSize?.width ?: 0f
        val sensorH = physSize?.height ?: 0f

        Log.d(LOG_TAG, "buildLensInfo ${label}: exposure=[${expMinNs}ns, ${expMaxNs}ns] (${formatExpRange(expMinNs, expMaxNs)}) sensitivity=[$sensMin, $sensMax] sensor=[${sensorW}x${sensorH}mm]")

        // 打印等效焦距以便验证
        val diag = sqrt((sensorW * sensorW + sensorH * sensorH).toDouble())
        val cropFactor = if (diag > 0) 43.27 / diag else 0.0
        if (cropFactor > 0) {
            val focalLength = focalLengths?.firstOrNull() ?: 0f
            Log.d(LOG_TAG, "  → cropFactor=${"%.1f".format(cropFactor)} actualFocal=${"%.1f".format(focalLength)}mm equiv=${(focalLength * cropFactor).roundToInt()}mm")
        }

        return LensInfo(
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            label = label,
            focalLength = focalLengths?.firstOrNull() ?: 0f,
            aperture = apertures?.firstOrNull() ?: 0f,
            lensFacing = facing ?: -1,
            pixelArraySize = pixelArray?.let { "${it.width}x${it.height}" } ?: "",
            activeArraySize = activeArray?.toString() ?: "",
            colorFilterArrangement = cfa ?: -1,
            rawMaxSize = rawMaxSize?.let { "${it.width}x${it.height}" } ?: "",
            whiteLevel = whiteLevel ?: 0,
            referenceIlluminant1 = (ill1?.toInt() ?: -1),
            referenceIlluminant2 = (ill2?.toInt() ?: -1),
            blackLevelPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.toString() ?: "",
            calibrationTransform1 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.toString() ?: "",
            calibrationTransform2 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.toString() ?: "",
            colorTransform1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toString() ?: "",
            colorTransform2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toString() ?: "",
            forwardMatrix1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.toString() ?: "",
            forwardMatrix2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.toString() ?: "",
            sensorOrientation = sensorOrientation,
            exposureTimeMinNs = expMinNs,
            exposureTimeMaxNs = expMaxNs,
            sensitivityMin = sensMin,
            sensitivityMax = sensMax,
            sensorWidthMm = sensorW,
            sensorHeightMm = sensorH
        )
    }

    private fun supportsRaw(characteristics: CameraCharacteristics): Boolean {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
    }

    // ================= 辅助 =================

    /**
     * 将屏幕触摸坐标（归一化 [0,1]）映射到传感器归一化坐标，供 focusOnPoint 使用。
     * 逆运算 OpenGL 顶点着色器的 transform：aspectScale → 镜像 → 旋转
     */
    private fun viewToSensorNorm(normX: Float, normY: Float, viewW: Int, viewH: Int): Pair<Float, Float>? {
        val lens = selectedLens ?: return null
        val p = pipeline ?: return null
        if (p.rawW <= 0 || p.rawH <= 0) return null

        val orientation = lens.sensorOrientation
        val rotated = orientation == 90 || orientation == 270
        val imageAspect = if (rotated) p.rawH.toFloat() / p.rawW.toFloat()
                          else p.rawW.toFloat() / p.rawH.toFloat()
        val viewAspect = viewW.toFloat() / viewH.toFloat()

        // 逆 letterbox（aspectScale 缩放）
        val ax = if (imageAspect > viewAspect) 1f else imageAspect / viewAspect
        val ay = if (imageAspect > viewAspect) viewAspect / imageAspect else 1f
        var u = (normX - (1f - ax) / 2f) / ax
        var v = (normY - (1f - ay) / 2f) / ay
        u = u.coerceIn(0f, 1f)
        v = v.coerceIn(0f, 1f)

        // 直接应用顶点着色器的 forward 变换：screen → [mirror] → [rotate] → sensor
        // 着色器：uv = aTexCoord; if(mirror) uv.x = 1-uv.x; vUV = f(uv)
        val mx = if (lens.lensFacing == CameraMetadata.LENS_FACING_FRONT) 1f - u else u
        val my = v
        val (sx, sy) = when (orientation) {
            0    -> Pair(mx, 1f - my)
            90   -> Pair(my, 1f - mx)
            180  -> Pair(1f - mx, my)
            270  -> Pair(1f - my, mx)
            else -> Pair(mx, 1f - my)
        }
        return Pair(sx, sy)
    }

    private fun formatFocalLength(f: Float, lens: LensInfo): String {
        val equiv = if (lens.sensorWidthMm > 0f && lens.sensorHeightMm > 0f) {
            val diag = sqrt((lens.sensorWidthMm * lens.sensorWidthMm + lens.sensorHeightMm * lens.sensorHeightMm).toDouble())
            val cropFactor = 43.27 / diag  // 35mm 全画幅对角线 43.27mm
            (f * cropFactor).roundToInt()
        } else null
        return if (equiv != null && equiv > 0) "${equiv}mm" else "${f}mm"
    }

    private fun cfaName(cfa: Int): String = when (cfa) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        else -> "CFA?$cfa"
    }

    /** 曝光时间纳秒 → 可读范围文本，如 "1/8000~30s"。 */
    private fun formatExpRange(minNs: Long, maxNs: Long): String {
        fun fmt(ns: Long): String {
            val s = ns / 1_000_000_000.0
            return if (s >= 1.0) "%.0fs".format(s)
            else "1/%.0f".format(1.0 / s)
        }
        return "${fmt(minNs)}~${fmt(maxNs)}"
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join(); backgroundThread = null; backgroundHandler = null }
        catch (e: Exception) { Log.e(LOG_TAG, "Stop Thread Err", e) }
    }

    // ================= 权限管理 =================

    /** 检查权限：有则立即执行 action，无则请求权限并暂存，授予后自动重试。 */
    private fun ensurePermission(action: () -> Unit) {
        if (checkCameraPermission()) {
            action()
        } else {
            pendingPermissionAction = action
            requestCameraPermission()
        }
    }

    private fun checkCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // 优先执行权限授予前被中断的操作
            val pending = pendingPermissionAction
            pendingPermissionAction = null
            if (pending != null) {
                pending()
            } else if (lensList.isEmpty()) {
                // 无 pending 且无镜头（首次启动），走探测
                startDetection()
            }
        } else {
            pendingPermissionAction = null
            Toast.makeText(this, "相机权限是必需的", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        cameraController?.close()
        captureVibHandler.removeCallbacksAndMessages(null)
        captureVibRunning = false
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        if (selectedLens != null) {
            ensurePermission { openSelectedLensPreview() }
        }
        loadLutList()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController?.close()
        stopBackgroundThread()
        gpuAlignMerge?.let { aligner ->
            glSurfaceView.queueEvent { aligner.release() }
        }
    }

    // ---- ColorSpaceTransform / BlackLevelPattern 文本解析（LensInfo 存的 toString） ----
}
