package com.classic.camera

import android.Manifest
import androidx.appcompat.app.AlertDialog
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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import android.net.Uri
import android.provider.MediaStore
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
import android.view.ViewGroup
import android.view.WindowManager
import com.google.android.material.button.MaterialButton
import android.widget.ImageButton
import android.widget.LinearLayout
import com.google.android.material.slider.Slider
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.classic.camera.databinding.DialogSettingsBinding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    // UI 组件
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var btnShutter: MaterialButton
    private lateinit var btnLastPhoto: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var lensButtonBar: LinearLayout

    // 手动曝光控制 UI
    private lateinit var sbShutterSpeed: Slider
    private lateinit var sbIso: Slider
    private lateinit var tvShutterSpeed: TextView
    private lateinit var tvIso: TextView
    // 状态栏
    private lateinit var tvStatusShutter: TextView
    private lateinit var tvStatusIso: TextView
    private lateinit var tvStatusFilter: TextView

    private var pipeline: RawPipeline? = null
    private var gpuAlignMerge: GpuAlignMerge? = null
    private var lastAppliedPreviewAspect: Float = 0f

    /** 切换镜头期间丢弃残留帧：为 true 时 onRawFrame 直接返回，避免旧帧被新参数重绘。 */
    @Volatile private var previewDropFrames = false
    /** 待应用到 pipeline 的新镜头参数（等新会话配置完成后才真正应用）。 */
    @Volatile private var pendingLensParams: LensInfo? = null

    // Camera2 相关
    private lateinit var cameraManager: CameraManager
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraController: CameraController? = null

    // 手动曝光控制器
    private var manualController: ManualController? = null

    // 加速度传感器：检测手机朝向
    private var sensorManager: SensorManager? = null
    private var accelerometerSensor: Sensor? = null
    private var deviceOrientation: Int = 0  // 0/90/180/270

    /** 滑块调节震动节流：上次震动的 progress 值。 */
    private var lastShutterTick = 0
    private var lastIsoTick = 0

    /** 拍照处理中连续震动。 */
    private var captureVibRunning = false
    private val captureVibHandler = Handler(Looper.getMainLooper())

    /** 当前滤镜路径（空串=无滤镜） */
    /** 设置弹窗引用 */
    private var settingsDialog: AlertDialog? = null

    private var colorTempKelvin = 6500
    private var colorTint = 0
    private var colorTempGains = floatArrayOf(1f, 1f, 1f)

    private var currentFilterPath: String = ""
        set(value) {
            field = value
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("current_filter_path", value).apply()
        }

    /** 设备上最新一张照片的 URI（为空串表示无照片） */
    private var lastPhotoUri: String = ""
    private var allPhotoUris: List<String> = emptyList()
    private var currentPhotoIndex: Int = 0

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
        tvStatusFilter.text = if (path.isNotEmpty()) java.io.File(path).nameWithoutExtension else ""
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
    private var pendingMediaPermissionAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val themePref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("theme", "classic")
        when (themePref) {
            "classic" -> setTheme(R.style.Theme_古法相机)
            "macaron" -> setTheme(R.style.Theme_古法相机_马卡龙)
            "berry" -> setTheme(R.style.Theme_古法相机_脏脏莓咖)
            "mint" -> setTheme(R.style.Theme_古法相机_蜜桃薄荷)
            "sakura" -> setTheme(R.style.Theme_古法相机_夜樱)
            "blossom" -> setTheme(R.style.Theme_古法相机_浅樱)
            "graphite" -> setTheme(R.style.Theme_古法相机_石墨)
            "wine" -> setTheme(R.style.Theme_古法相机_醉莓)
            else -> setTheme(R.style.Theme_古法相机)
        }

        setContentView(R.layout.activity_main)

        // 使用中保持屏幕常亮（拍照取景不熄屏）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        btnShutter = findViewById(R.id.btnShutter)
        btnLastPhoto = findViewById(R.id.btnLastPhoto)
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
            pipeline?.highlightReconstructionEnabled = it.getBoolean("highlight_reconstruction", false)
            pipeline?.exposureComp = it.getFloat("exposure_comp", 0f)
            pipeline?.hlComp = it.getFloat("hl_comp", 0f)
            pipeline?.hlThreshold = it.getFloat("hl_threshold", 0f)
            pipeline?.blackPoint = it.getFloat("black_point", 0f)
            pipeline?.shadowComp = it.getFloat("shadow_comp", 50f)
            pipeline?.contrast = it.getFloat("contrast", 0f)
            pipeline?.autoContrast = it.getBoolean("auto_contrast", true)
            colorTempKelvin = it.getInt("color_temp_kelvin", 6500)
            colorTint = it.getInt("color_tint", 0)
        }
        updateColorTempGains()
        // 加载色调曲线
        val savedCurve = ToneCurve.load(prefs)
        val toneCurveNativeArray = savedCurve.toNativeArray()
        pipeline?.toneCurvePoints = toneCurveNativeArray

        // 恢复上次选择的滤镜
        currentFilterPath = prefs.getString("current_filter_path", "") ?: ""
        if (currentFilterPath.isNotEmpty()) {
            val savedFile = java.io.File(currentFilterPath)
            val savedLut = if (savedFile.exists()) LutUtils.loadCubeFile(savedFile) else null
            if (savedLut != null) {
                pipeline?.lutFloatArray = savedLut
                pipeline?.lutSize = savedLut.let { LutUtils.lutSizeOf(it).takeIf { s -> s > 0 } } ?: LutUtils.LUT_SIZE
            } else {
                currentFilterPath = ""
                prefs.edit().remove("current_filter_path").apply()
            }
        }
        tvStatusFilter?.text = if (currentFilterPath.isNotEmpty())
            java.io.File(currentFilterPath).nameWithoutExtension else ""

        // ---- LUT 选择器初始化 ----
        lutRecyclerView = findViewById(R.id.lutRecyclerView)
        lutRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        lutAdapter = LutSelectorAdapter(context = this,
            onItemClick = { entry ->
                val path = entry.file?.absolutePath ?: ""
                currentFilterPath = path
                tvStatusFilter.text = if (path.isNotEmpty())
                    java.io.File(path).nameWithoutExtension else ""
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
                    val itemHalfW = resources.getDimensionPixelSize(R.dimen.lut_thumb_container) / 2
                    lm.scrollToPositionWithOffset(idx, (halfW - itemHalfW).coerceAtLeast(0))
                }
            }
        )
        lutAdapter.selectedPath = currentFilterPath
        lutRecyclerView.adapter = lutAdapter

        findViewById<android.widget.ImageButton>(R.id.btnManageFilters).setOnClickListener {
            val intent = Intent(this, FilterActivity::class.java).apply {
                putExtra("current_filter", currentFilterPath)
            }
            filterLauncher.launch(intent)
        }

        LutUtils.seedPresetFilters(this)
        loadLutList()
        refreshLatestPhoto()

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

        // 加速度传感器：检测手机朝向（不依赖系统自动旋转开关）
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

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

        btnLastPhoto.setOnClickListener {
            val dialog = PhotoPopupDialog.newInstance(allPhotoUris, currentPhotoIndex)
            dialog.onPhotoDeleted = {
                refreshLatestPhoto()
            }
            dialog.show(supportFragmentManager, "photo_popup")
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
                    cameraController?.deviceOrientation = deviceOrientation

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
        var wasShutterManual = false
        var wasIsoManual = false

        sbShutterSpeed.valueFrom = 0f
        sbShutterSpeed.stepSize = 1f
        sbShutterSpeed.setTickVisible(false)
        sbShutterSpeed.setLabelBehavior(2)
        sbIso.valueFrom = 0f
        sbIso.stepSize = 1f
        sbIso.setTickVisible(false)
        sbIso.setLabelBehavior(2)

        sbShutterSpeed.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val progress = value.toInt()
            val isManual = progress > 0
            if (isManual != wasShutterManual) {
                triggerHaptic(HEAVY)
            }
            wasShutterManual = isManual
            if (isManual && kotlin.math.abs(progress - lastShutterTick) >= TICK_THROTTLE) {
                triggerHaptic(TICK)
                lastShutterTick = progress
            }
            updateLabelForShutter(progress)
            pushExposureParams()
        }
        sbShutterSpeed.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                lastShutterTick = slider.value.toInt()
            }
            override fun onStopTrackingTouch(slider: Slider) {
                LensStore.saveSliderProgress(this@MainActivity,
                    slider.value.toInt(), sbIso.value.toInt(),
                    manualController?.iso ?: 100,
                    manualController?.exposureTimeNs ?: ManualController.DEFAULT_EXPOSURE_NS)
            }
        })

        sbIso.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val progress = value.toInt()
            val isManual = progress > 0
            if (isManual != wasIsoManual) {
                triggerHaptic(HEAVY)
            }
            wasIsoManual = isManual
            if (isManual && kotlin.math.abs(progress - lastIsoTick) >= TICK_THROTTLE) {
                triggerHaptic(TICK)
                lastIsoTick = progress
            }
            updateLabelForIso(progress)
            pushExposureParams()
        }
        sbIso.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                lastIsoTick = slider.value.toInt()
            }
            override fun onStopTrackingTouch(slider: Slider) {
                LensStore.saveSliderProgress(this@MainActivity,
                    sbShutterSpeed.value.toInt(), slider.value.toInt(),
                    manualController?.iso ?: 100,
                    manualController?.exposureTimeNs ?: ManualController.DEFAULT_EXPOSURE_NS)
            }
        })
    }

    /** 把当前滑块值应用到 ManualController → 通知 CameraController 更新预览。 */
    private fun pushExposureParams() {
        manualController?.updateFromSliders(sbShutterSpeed.value.toInt(), sbIso.value.toInt())
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

    private fun updateColorTempGains() {
        colorTempGains = kelvinToWbGains(colorTempKelvin, colorTint)
    }

    /** 显示设置弹窗。 */
    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val order = SettingsOrderStore.load(this)
        val items = buildSettingsItems(prefs, order)

        val adapter = SettingsAdapter(items, object : SettingsAdapter.Listener {
            override fun getSelectedTheme(): String =
                prefs.getString("theme", "classic") ?: "classic"

            override fun onSwitchChanged(item: SettingsItem, checked: Boolean) {
                when (item.id) {
                    "save_dng" -> {
                        prefs.edit().putBoolean("save_dng", checked).apply()
                        cameraController?.saveDng = checked
                    }
                    "highlight_reconstruction" -> {
                        prefs.edit().putBoolean("highlight_reconstruction", checked).apply()
                        glSurfaceView.queueEvent { pipeline?.highlightReconstructionEnabled = checked }
                    }
                    "auto_contrast" -> {
                        prefs.edit().putBoolean("auto_contrast", checked).apply()
                        glSurfaceView.queueEvent { pipeline?.autoContrast = checked }
                    }
                    "multi_frame" -> {
                        val n = if (checked) item.value.toInt().coerceIn(2, 8) else 1
                        prefs.edit().putBoolean("multi_frame", checked).putInt("multi_frame_count", n).apply()
                        cameraController?.multiFrameCount = n
                    }
                }
            }

            override fun onSliderLive(item: SettingsItem, value: Float) {
                when (item.id) {
                    "white_level_offset" ->
                        glSurfaceView.queueEvent { pipeline?.whiteLevelOffset = value }
                    "tone_map_d" ->
                        glSurfaceView.queueEvent { pipeline?.toneMapD = value }
                    "exposure_comp" ->
                        glSurfaceView.queueEvent { pipeline?.exposureComp = value }
                    "hl_comp" ->
                        glSurfaceView.queueEvent { pipeline?.hlComp = value }
                    "hl_threshold" ->
                        glSurfaceView.queueEvent { pipeline?.hlThreshold = value }
                    "black_point" ->
                        glSurfaceView.queueEvent { pipeline?.blackPoint = value }
                    "shadow_comp" ->
                        glSurfaceView.queueEvent { pipeline?.shadowComp = value }
                    "contrast" ->
                        glSurfaceView.queueEvent { pipeline?.contrast = value }
                    "lut_intensity" ->
                        glSurfaceView.queueEvent { pipeline?.lutIntensity = value }
                    "color_temp" -> { colorTempKelvin = value.toInt(); updateColorTempGains() }
                    "color_tint" -> { colorTint = value.toInt(); updateColorTempGains() }
                }
            }

            override fun onSliderCommit(item: SettingsItem, value: Float) {
                when (item.id) {
                    "white_level_offset" ->
                        prefs.edit().putInt("white_level_offset", value.toInt()).apply()
                    "tone_map_d" ->
                        prefs.edit().putFloat("tone_map_d", value).apply()
                    "exposure_comp" ->
                        prefs.edit().putFloat("exposure_comp", value).apply()
                    "hl_comp" ->
                        prefs.edit().putFloat("hl_comp", value).apply()
                    "hl_threshold" ->
                        prefs.edit().putFloat("hl_threshold", value).apply()
                    "black_point" ->
                        prefs.edit().putFloat("black_point", value).apply()
                    "shadow_comp" ->
                        prefs.edit().putFloat("shadow_comp", value).apply()
                    "contrast" ->
                        prefs.edit().putFloat("contrast", value).apply()
                    "lut_intensity" ->
                        prefs.edit().putFloat("lut_intensity", value).apply()
                    "color_temp" -> {
                        colorTempKelvin = value.toInt(); updateColorTempGains()
                        prefs.edit().putInt("color_temp_kelvin", value.toInt()).apply()
                    }
                    "color_tint" -> {
                        colorTint = value.toInt(); updateColorTempGains()
                        prefs.edit().putInt("color_tint", value.toInt()).apply()
                    }
                    "multi_frame" ->
                        prefs.edit().putInt("multi_frame_count", value.toInt()).apply()
                }
            }

            override fun onThemeSelected(themeId: String) {
                prefs.edit().putString("theme", themeId).apply()
                recreate()
            }

            override fun onCurveChanged(curve: ToneCurve) {
                ToneCurve.save(prefs, curve)
                val nativeArr = curve.toNativeArray()
                glSurfaceView.queueEvent {
                    pipeline?.setToneCurve(nativeArr)
                    glSurfaceView.requestRender()
                }
            }
        })

        val binding = DialogSettingsBinding.inflate(layoutInflater)
        binding.settingsRecycler.layoutManager = LinearLayoutManager(this)
        binding.settingsRecycler.adapter = adapter
        adapter.attach(binding.settingsRecycler)

        settingsDialog = AlertDialog.Builder(this)
            .setView(binding.root)
            .show()

        adapter.dialog = settingsDialog
        settingsDialog?.window?.setBackgroundDrawableResource(R.drawable.dialog_settings_bg)
        adapter.dialogBg = settingsDialog?.window?.decorView?.background
        settingsDialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.85).toInt()
        )

        // 底部按钮栏：统一 MD 风格，水平均分
        binding.btnDone.setOnClickListener { settingsDialog?.dismiss() }
        binding.btnSort.setOnClickListener {
            adapter.sortMode = !adapter.sortMode
            binding.btnSort.text = if (adapter.sortMode) "完成排序" else "排序"
            if (!adapter.sortMode) {
                SettingsOrderStore.save(this, items.map { it.id })
            }
        }
        binding.btnReset.setOnClickListener { resetAllSettings(items, adapter, prefs) }
    }

    /** 组装设置项列表：按保存顺序展示，并载入当前 prefs 值。 */
    private fun buildSettingsItems(prefs: SharedPreferences, order: List<String>): MutableList<SettingsItem> {
        val all = mutableListOf<SettingsItem>().apply {
            // 主题
            add(SettingsItem("theme", SettingsViewType.THEME, "主题"))
            // 开关
            add(SettingsItem("save_dng", SettingsViewType.SWITCH, "保存 DNG",
                prefsKey = "save_dng", switchDefault = false,
                boolValue = prefs.getBoolean("save_dng", false)))
            add(SettingsItem("multi_frame", SettingsViewType.MULTIFRAME, "多帧降噪",
                boolValue = prefs.getBoolean("multi_frame", false),
                value = prefs.getInt("multi_frame_count", 4).toFloat()))
            add(SettingsItem("highlight_reconstruction", SettingsViewType.SWITCH, "高光重建",
                prefsKey = "highlight_reconstruction", switchDefault = false,
                boolValue = prefs.getBoolean("highlight_reconstruction", false)))
            // 滑块
            add(SettingsItem("white_level_offset", SettingsViewType.SLIDER, "白电平补偿",
                sliderMin = 0f, sliderMax = 512f, sliderStep = 1f,
                valueToSlider = { it + 256f }, sliderToValue = { it - 256f },
                formatValue = { v -> val o = v.toInt(); if (o >= 0) "+$o" else "$o" },
                value = prefs.getInt("white_level_offset", 0).toFloat()))
            add(SettingsItem("tone_map_d", SettingsViewType.SLIDER, "色调 D",
                sliderMin = 0f, sliderMax = 300f, sliderStep = 1f,
                valueToSlider = { (it + 1f) * 100f }, sliderToValue = { it / 100f - 1f },
                formatValue = { "%.2f".format(it) },
                value = prefs.getFloat("tone_map_d", 0.59f)))
            add(SettingsItem("exposure_comp", SettingsViewType.SLIDER, "曝光补偿 EV",
                sliderMin = 0f, sliderMax = 80f, sliderStep = 1f,
                valueToSlider = { (it + 2f) * 20f }, sliderToValue = { it / 20f - 2f },
                formatValue = { "%.2f".format(it) },
                value = prefs.getFloat("exposure_comp", 0f)))
            add(SettingsItem("hl_comp", SettingsViewType.SLIDER, "高光压缩量",
                sliderMax = 100f, sliderStep = 1f,
                formatValue = { it.toInt().toString() },
                value = prefs.getFloat("hl_comp", 0f)))
            add(SettingsItem("hl_threshold", SettingsViewType.SLIDER, "高光压缩阈值",
                sliderMax = 100f, sliderStep = 1f,
                formatValue = { it.toInt().toString() },
                value = prefs.getFloat("hl_threshold", 0f)))
            add(SettingsItem("black_point", SettingsViewType.SLIDER, "黑点",
                sliderMax = 200f, sliderStep = 1f,
                valueToSlider = { it + 100f }, sliderToValue = { it - 100f },
                formatValue = { it.toInt().toString() },
                value = prefs.getFloat("black_point", 0f)))
            add(SettingsItem("shadow_comp", SettingsViewType.SLIDER, "阴影压缩",
                sliderMax = 100f, sliderStep = 1f,
                formatValue = { it.toInt().toString() },
                value = prefs.getFloat("shadow_comp", 50f)))
            add(SettingsItem("contrast", SettingsViewType.SLIDER, "对比度",
                sliderMax = 200f, sliderStep = 1f,
                valueToSlider = { it + 100f }, sliderToValue = { it - 100f },
                formatValue = { it.toInt().toString() },
                value = prefs.getFloat("contrast", 0f)))
            // 自适应对比度开关
            add(SettingsItem("auto_contrast", SettingsViewType.SWITCH, "自适应对比度",
                prefsKey = "auto_contrast", switchDefault = true,
                boolValue = prefs.getBoolean("auto_contrast", true)))
            // 色调曲线
            add(SettingsItem("curve", SettingsViewType.CURVE, "色调曲线",
                curve = ToneCurve.load(prefs)))
            // 滤镜强度
            add(SettingsItem("lut_intensity", SettingsViewType.SLIDER, "滤镜强度",
                sliderMax = 100f, sliderStep = 1f,
                valueToSlider = { it * 100f }, sliderToValue = { it / 100f },
                formatValue = { "${(it * 100f).roundToInt()}%" },
                value = prefs.getFloat("lut_intensity", 1f)))
            // 色温 / 色调
            add(SettingsItem("color_temp", SettingsViewType.SLIDER, "色温",
                sliderMax = 130f, sliderStep = 1f,
                valueToSlider = { (it - 2000f) / 100f }, sliderToValue = { it * 100f + 2000f },
                formatValue = { "${it.toInt()}K" },
                value = prefs.getInt("color_temp_kelvin", 6500).toFloat()))
            add(SettingsItem("color_tint", SettingsViewType.SLIDER, "色调",
                sliderMax = 200f, sliderStep = 1f,
                valueToSlider = { it + 100f }, sliderToValue = { it - 100f },
                formatValue = { v -> val o = v.toInt(); if (o >= 0) "+$o" else "$o" },
                value = prefs.getInt("color_tint", 0).toFloat()))
        }
        val byId = all.associateBy { it.id }
        // 按保存顺序重排，缺省项追加到末尾
        val result = mutableListOf<SettingsItem>()
        for (id in order) byId[id]?.let { result.add(it) }
        for (item in all) if (result.none { it.id == item.id }) result.add(item)
        return result
    }

    /** 把所有设置恢复为出厂默认，就地刷新弹窗（不重开）。 */
    private fun resetAllSettings(
        items: MutableList<SettingsItem>,
        adapter: SettingsAdapter,
        prefs: SharedPreferences
    ) {
        // 更新 item 运行时值
        val defaults = mapOf(
            "save_dng" to SettingsItem("save_dng", SettingsViewType.SWITCH, "", boolValue = false),
            "multi_frame" to SettingsItem("multi_frame", SettingsViewType.MULTIFRAME, "", boolValue = false, value = 4f),
            "highlight_reconstruction" to SettingsItem("highlight_reconstruction", SettingsViewType.SWITCH, "", boolValue = false),
            "white_level_offset" to SettingsItem("white_level_offset", SettingsViewType.SLIDER, "", value = 0f),
            "tone_map_d" to SettingsItem("tone_map_d", SettingsViewType.SLIDER, "", value = 0.59f),
            "exposure_comp" to SettingsItem("exposure_comp", SettingsViewType.SLIDER, "", value = 0f),
            "hl_comp" to SettingsItem("hl_comp", SettingsViewType.SLIDER, "", value = 0f),
            "hl_threshold" to SettingsItem("hl_threshold", SettingsViewType.SLIDER, "", value = 0f),
            "black_point" to SettingsItem("black_point", SettingsViewType.SLIDER, "", value = 0f),
            "shadow_comp" to SettingsItem("shadow_comp", SettingsViewType.SLIDER, "", value = 50f),
            "contrast" to SettingsItem("contrast", SettingsViewType.SLIDER, "", value = 0f),
            "auto_contrast" to SettingsItem("auto_contrast", SettingsViewType.SWITCH, "", boolValue = true),
            "lut_intensity" to SettingsItem("lut_intensity", SettingsViewType.SLIDER, "", value = 1f),
            "color_temp" to SettingsItem("color_temp", SettingsViewType.SLIDER, "", value = 6500f),
            "color_tint" to SettingsItem("color_tint", SettingsViewType.SLIDER, "", value = 0f),
        )
        for (item in items) {
            defaults[item.id]?.let { d ->
                item.boolValue = d.boolValue
                item.value = d.value
            }
            if (item.type == SettingsViewType.CURVE) item.curve = ToneCurve()
        }

        // 写回 prefs
        prefs.edit().putBoolean("save_dng", false)
            .putBoolean("multi_frame", false)
            .putInt("multi_frame_count", 4)
            .putBoolean("highlight_reconstruction", false)
            .putBoolean("auto_contrast", true)
            .putInt("white_level_offset", 0)
            .putFloat("tone_map_d", 0.59f)
            .putFloat("exposure_comp", 0f)
            .putFloat("hl_comp", 0f)
            .putFloat("hl_threshold", 0f)
            .putFloat("black_point", 0f)
            .putFloat("shadow_comp", 50f)
            .putFloat("contrast", 0f)
            .putFloat("lut_intensity", 1f)
            .putInt("color_temp_kelvin", 6500)
            .putInt("color_tint", 0)
            .apply()
        ToneCurve.save(prefs, ToneCurve())

        cameraController?.saveDng = false
        cameraController?.multiFrameCount = 1
        colorTempKelvin = 6500
        colorTint = 0
        updateColorTempGains()

        glSurfaceView.queueEvent {
            pipeline?.whiteLevelOffset = 0f
            pipeline?.toneMapD = 0.59f
            pipeline?.exposureComp = 0f
            pipeline?.hlComp = 0f
            pipeline?.hlThreshold = 0f
            pipeline?.blackPoint = 0f
            pipeline?.shadowComp = 50f
            pipeline?.contrast = 0f
            pipeline?.autoContrast = true
            pipeline?.lutIntensity = 1f
            pipeline?.highlightReconstructionEnabled = false
            pipeline?.setToneCurve(ToneCurve().toNativeArray())
            glSurfaceView.requestRender()
        }
        adapter.notifyDataSetChanged()
    }

    // ================= LUT 列表加载 =================

    private fun loadLutList() {
        if (!LutUtils.isStorageAuthorized(this)) {
            if (LutUtils.shouldRequestStorage(this)) LutUtils.requestStorageAccess(this)
            return
        }
        lutAdapter.loadFromDirectory(LutUtils.filtersDir())
    }

    // ================= 最后照片按钮 =================

    /** 从 MediaStore 查询所有照片并更新按钮。 */
    private fun refreshLatestPhoto() {
        try {
            val baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            val ids = mutableListOf<Long>()
            contentResolver.query(baseUri, arrayOf(MediaStore.Images.Media._ID), null, null, sortOrder)?.use { cur ->
                while (cur.moveToNext()) {
                    ids.add(cur.getLong(0))
                }
            }
            allPhotoUris = ids.map { Uri.withAppendedPath(baseUri, it.toString()).toString() }
            currentPhotoIndex = 0
            lastPhotoUri = allPhotoUris.firstOrNull() ?: ""
        } catch (_: Exception) {
            allPhotoUris = emptyList()
            lastPhotoUri = ""
        }
        refreshLastPhotoButton()
        val hasPhotos = allPhotoUris.isNotEmpty()
        btnLastPhoto.isEnabled = hasPhotos
        btnLastPhoto.alpha = if (hasPhotos) 1f else 0.35f
    }

    /** 根据 lastPhotoUri 更新按钮图标。 */
    private fun refreshLastPhotoButton() {
        if (lastPhotoUri.isEmpty()) {
            btnLastPhoto.setImageResource(R.drawable.ic_photo)
            btnLastPhoto.scaleType = android.widget.ImageView.ScaleType.CENTER
            btnLastPhoto.background = ContextCompat.getDrawable(this, R.drawable.btn_icon_md_bg)
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
                    val rounded = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, bmp)
                    rounded.cornerRadius = (8 * resources.displayMetrics.density + 0.5f)
                    btnLastPhoto.setImageDrawable(rounded)
                    btnLastPhoto.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    btnLastPhoto.background = null
                } else {
                    btnLastPhoto.setImageResource(R.drawable.ic_photo)
                    btnLastPhoto.scaleType = android.widget.ImageView.ScaleType.CENTER
                    btnLastPhoto.background = ContextCompat.getDrawable(this, R.drawable.btn_icon_md_bg)
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to load photo thumbnail", e)
                btnLastPhoto.setImageResource(R.drawable.ic_photo)
                btnLastPhoto.scaleType = android.widget.ImageView.ScaleType.CENTER
                btnLastPhoto.background = ContextCompat.getDrawable(this, R.drawable.btn_icon_md_bg)
            }
        }
    }

    companion object {
        private const val HEAVY = 1           // 边界大震 50ms
        private const val TICK = 2             // 调节小震 6ms
        private const val TICK_THROTTLE = 20   // 每差 20 进度震一次（分辨率10000时共~500次）
        private const val PREVIEW_SCALE = 0.95f  // 预览画面缩放系数
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
        val btnHeight = resources.getDimensionPixelSize(R.dimen.lens_btn_height)
        val btnMarginH = resources.getDimensionPixelSize(R.dimen.spacing_xs)
        val cornerRadius = resources.getDimension(R.dimen.corner_medium)
        for (lens in lensList) {
            val btn = MaterialButton(this).apply {
                text = if (lens.lensFacing == CameraMetadata.LENS_FACING_FRONT) "自拍"
                       else formatFocalLength(lens.focalLength, lens)
                isAllCaps = false
                minimumHeight = 0
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(cornerRadius)
                    .build()
                setTag(lens)
                setOnClickListener { selectLens(lens) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, btnHeight
                ).apply { setMargins(btnMarginH, 0, btnMarginH, 0) }
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
                LensStore.saveSliderProgress(this, sbShutterSpeed.value.toInt(), sbIso.value.toInt(),
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
            sbShutterSpeed.valueTo = ManualController.SHUTTER_RESOLUTION.toFloat()
            sbIso.valueTo = ManualController.ISO_RESOLUTION.toFloat()
            sbShutterSpeed.value = shutterPos.toFloat()
            sbIso.value = isoPos.toFloat()
            // 代码调用 set value（fromUser=false），监听器跳过标签更新，需手动设
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

            // 切换镜头：暂存新镜头参数并丢弃旧镜头残留帧。
            // 参数不立即应用——等到新会话 onConfigured（新镜头首帧前）才真正写到 pipeline，
            // 否则 GL 线程可能用新参数重绘旧帧，导致偏色/颠倒/CFA 错乱。
            pendingLensParams = lens
            previewDropFrames = true
            openSelectedLensPreview()
        }
    }

    /** 遍历镜头按钮，选中项高亮，其余恢复默认。 */
    private fun refreshLensButtonStyles() {
        for (i in 0 until lensButtonBar.childCount) {
            val child = lensButtonBar.getChildAt(i)
            if (child is MaterialButton) {
                val isSelected = child.tag === selectedLens
                if (isSelected) {
                    child.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        getAttrColor(R.attr.accentColor))
                    child.setTextColor(getAttrColor(R.attr.onAccentColor))
                } else {
                    child.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        getAttrColor(R.attr.surfaceLight))
                    child.setTextColor(getAttrColor(R.attr.iconPrimary))
                }
            }
        }
    }

    /** 把镜头静态参数（黑电平/白电平/CCM/CFA/forwardMatrix/orientation）设置到 pipeline。 */
    private fun applyLensParamsToPipeline(lens: LensInfo) {
        val p = pipeline ?: return
        // 新镜头会话就绪、即将接收新帧：作废旧的残留帧（防御），随后新帧会立即填充
        p.rawBuffer = null
        p.rawShorts = null
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

    private fun applyPreviewAspect(imageAspect: Float) {
        val p = pipeline ?: return
        if (p.rawW <= 0 || p.rawH <= 0) return
        val container = glSurfaceView.parent as? View ?: return
        val parentW = (container.parent as? View)?.width ?: container.width
        if (parentW <= 0) return
        val lp = container.layoutParams as ViewGroup.MarginLayoutParams
        val scaledW = (parentW * PREVIEW_SCALE).toInt().coerceAtLeast(1)
        val marginH = (parentW - scaledW) / 2
        val targetHeight = (scaledW / imageAspect).toInt().coerceAtLeast(1)
        if (lp.height != targetHeight || lp.width != scaledW || lp.leftMargin != marginH) {
            lp.width = scaledW
            lp.height = targetHeight
            lp.leftMargin = marginH
            lp.rightMargin = marginH
            container.layoutParams = lp
        }
    }

    /** 用当前选中镜头打开 RAW 预览。 */
    private fun openSelectedLensPreview() {
        val lens = selectedLens ?: return
        startBackgroundThread()
        val controller = cameraController ?: CameraController(this, cameraManager, backgroundHandler!!).also {
            cameraController = it
            it.manualController = manualController  // 注入手动曝光控制器
            // 新镜头会话配置完成（新镜头首帧前）：应用暂存的静态参数并放行渲染
            it.onPreviewReady = {
                val pl = pendingLensParams
                if (pl != null) {
                    applyLensParamsToPipeline(pl)
                    pendingLensParams = null
                }
                previewDropFrames = false
            }
            it.onExposureComplete = { stopCaptureVibration() }
            it.onPhotoSaved = { uri ->
                if (uri != null) {
                    lastPhotoUri = uri.toString()
                    runOnUiThread { refreshLastPhotoButton() }
                }
                runOnUiThread { ensureMediaPermission { refreshLatestPhoto() } }
            }
            it.saveDng = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("save_dng", true)
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            it.multiFrameCount = if (prefs.getBoolean("multi_frame", false)) prefs.getInt("multi_frame_count", 4).coerceIn(2, 8) else 1
            // GPU JPEG 处理器：通过 queueEvent 在 GL 线程渲染，CountDownLatch 同步等待
            // 返回 null 则跳过 JPEG 保存
            it.gpuJpegProcessor = { rawShorts, w, h, blR, blG, blB, wl, wbR, wbG, wbB, ccm, cfa ->
                val latch = CountDownLatch(1)
                var bitmap: Bitmap? = null
                val gains = colorTempGains
                it.pendingCaptureLatch = latch
                glSurfaceView.queueEvent {
                    try {
                        bitmap = pipeline?.renderCaptureToBitmap(
                            rawShorts, w, h, blR, blG, blB, wl,
                            wbR * gains[0], wbG * gains[1], wbB * gains[2], ccm, cfa
                        )
                        if (bitmap == null) {
                            Log.w(LOG_TAG, "GPU JPEG: pipeline returned null")
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "GPU JPEG render failed: ${e.message}", e)
                    }
                    latch.countDown()
                }
                latch.await()
                it.pendingCaptureLatch = null
                // 根据快门时快照的手机朝向旋转 JPEG 像素（不依赖 EXIF Orientation）
                val captureOrientation = cameraController?.deviceOrientation ?: deviceOrientation
                bitmap?.let { bmp -> rotateBitmap(bmp, captureOrientation) } ?: bitmap
            }
            // GPU 多帧融合处理器（在 GL 线程运行，CountDownLatch 同步）
            val aligner = gpuAlignMerge
            if (aligner != null) {
                it.gpuMultiFrameProcessor = { frames, w, h, numTx, numTy, wl ->
                    val latch = CountDownLatch(1)
                    var result: ShortArray? = null
                    var error: Exception? = null
                    it.pendingCaptureLatch = latch
                    glSurfaceView.queueEvent {
                        try {
                            result = aligner.process(frames, w, h, numTx, numTy, wl)
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "GPU multi-frame failed", e)
                            error = e
                        }
                        latch.countDown()
                    }
                    latch.await()
                    it.pendingCaptureLatch = null
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
        controller.onRawFrame = rawFrame@ { buf, w, h, wbR, wbG, wbB, blR, blG, blB, wl ->
            // 切换镜头期间丢弃残留帧：避免旧帧被新参数重绘（偏色/颠倒/CFA 错乱）
            if (previewDropFrames) return@rawFrame
            val p = pipeline
            if (p != null) {
                p.rawBuffer = buf
                p.rawW = w; p.rawH = h
                val rotated = (p.orientation == 90 || p.orientation == 270)
                val imageAspect = if (rotated) h.toFloat() / w.toFloat()
                                  else w.toFloat() / h.toFloat()
                if (imageAspect > 0f && imageAspect != lastAppliedPreviewAspect) {
                    lastAppliedPreviewAspect = imageAspect
                    runOnUiThread { applyPreviewAspect(imageAspect) }
                }
                val gains = colorTempGains
                p.wbR = wbR * gains[0]; p.wbG = wbG * gains[1]; p.wbB = wbB * gains[2]
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
                // 逻辑相机与它的物理子镜头是两条都可能提供 RAW 的路径。
                // 先收集各物理子镜头。
                var childRawCount = 0
                for (physId in physicalIds) {
                    claimedPhysical.add(physId)
                    val physChar = cameraManager.getCameraCharacteristics(physId)
                    if (supportsRaw(physChar)) {
                        childRawCount++
                        lenses.add(buildLensInfo(id, physId, physChar,
                            "Physical[$physId] (under Logical[$id])"))
                    }
                }
                // 若子镜头都不支持 RAW，但逻辑镜头自身支持（部分 OEM/老设备只在
                // 逻辑流上报 CAPABILITY_RAW），补上逻辑镜头本身，避免整支主摄被漏掉。
                if (childRawCount == 0 && supportsRaw(characteristics)) {
                    lenses.add(buildLensInfo(id, null, characteristics, "Logical[$id]"))
                }
            } else {
                if (id !in claimedPhysical && supportsRaw(characteristics)) {
                    lenses.add(buildLensInfo(id, null, characteristics, "Camera[$id]"))
                }
            }
        }

        // 兜底去重：同一传感器可能既以独立镜头身份出现、又被其逻辑父镜头收集，
        // 以 lensId（logical:physical）为键去重，保证与遍历顺序无关。
        val seen = mutableSetOf<String>()
        return lenses.filter { seen.add(LensStore.lensId(it)) }
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

    private fun ensureMediaPermission(action: () -> Unit) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingMediaPermissionAction = action
            ActivityCompat.requestPermissions(this, arrayOf(perm), 102)
        }
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
        } else if (requestCode == 102 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val pending = pendingMediaPermissionAction
            pendingMediaPermissionAction = null
            pending?.invoke()
        } else {
            if (requestCode == 101) {
                pendingPermissionAction = null
                Toast.makeText(this, "相机权限是必需的", Toast.LENGTH_SHORT).show()
            } else if (requestCode == 102) {
                pendingMediaPermissionAction = null
                Toast.makeText(this, "需要存储权限才能访问照片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- 加速度传感器：设备朝向检测 ----
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]  // 重力 X 分量
            val y = event.values[1]  // 重力 Y 分量
            deviceOrientation = when {
                kotlin.math.abs(x) > kotlin.math.abs(y) -> if (x > 0) 270 else 90
                else -> if (y > 0) 0 else 180
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onPause() {
        super.onPause()
        accelerometerSensor?.let { sensorManager?.unregisterListener(sensorListener) }
        cameraController?.close()
        cameraController = null
        glSurfaceView.onPause()
        captureVibHandler.removeCallbacksAndMessages(null)
        captureVibRunning = false
    }

    override fun onResume() {
        super.onResume()
        accelerometerSensor?.let { sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
        glSurfaceView.onResume()
        selectedLens?.let { selectLens(it) }
        loadLutList()
        refreshLatestPhoto()
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
