package com.classic.camera

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

class ApplyFilterActivity : AppCompatActivity() {

    private lateinit var imageArea: FrameLayout
    private lateinit var imagePlaceholder: LinearLayout
    private lateinit var previewView: GLSurfaceView
    private lateinit var lutAdapter: LutSelectorAdapter
    private lateinit var lutRecyclerView: RecyclerView
    private lateinit var seekIntensity: SeekBar
    private lateinit var btnSave: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvFilterName: TextView

    private var selectedImageUri: Uri? = null
    private var originalBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null

    private var pipeline: RawPipeline? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentFilterPath: String = ""
    private var currentLut: FloatArray? = null
    private var previewW = 0
    private var previewH = 0

    /** GL 上下文就绪标记（由 renderer 的 onSurfaceCreated 回调置位）。 */
    @Volatile private var glReady = false

    /** 按住显示原图（0.5 秒后置 true；松开恢复滤镜）。 */
    @Volatile private var showOriginal = false
    private var pressed = false
    private val pressHandler = Handler(Looper.getMainLooper())
    private val showOriginalRunnable = Runnable { showOriginal = true; renderPreview() }
    private val clickRunnable = Runnable { imagePicker.launch(Intent(this, GalleryActivity::class.java)) }

    /** 自定义 Renderer：把 LUT 滤镜结果画到 GLSurfaceView（GPU 直出，无读回）。 */
    private inner class LutRenderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            pipeline = RawPipeline()
            pipeline!!.onSurfaceCreated(gl, config)
            glReady = true
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
        }
        override fun onDrawFrame(gl: GL10?) {
            val bmp = originalBitmap ?: return
            // 按住显示原图：跳过 LUT，直接渲染无滤镜原图
            val showOriginalNow = showOriginal
            val lut = if (!showOriginalNow && currentFilterPath.isNotEmpty() && currentLut != null &&
                seekIntensity.progress > 0) currentLut else null
            val intensity = if (lut != null) seekIntensity.progress / 100f else 0f
            // 用当前 GLSurfaceView 尺寸做 aspect-fit 渲染
            pipeline?.renderLutToView(bmp, lut, intensity, previewW, previewH)
        }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                selectedImageUri = uri
                imagePlaceholder.visibility = LinearLayout.GONE
                previewView.visibility = View.VISIBLE
                // 后台解码全尺寸原图（GPU 渲染用）
                Thread {
                    val bmp = decodeFullBitmap(uri)
                    mainHandler.post {
                        originalBitmap = bmp
                        if (bmp == null) {
                            Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.d(TAG, "原图解码完成: ${bmp.width}x${bmp.height}")
                            updateSaveEnabled()
                            // 设置预览尺寸：宽度填满 imageArea（含内边距），高度按图片比例
                            imageArea.post {
                                val availW = imageArea.width - imageArea.paddingLeft - imageArea.paddingRight
                                val w = availW.coerceAtLeast(1)
                                val h = (w.toFloat() * bmp.height / bmp.width).toInt()
                                previewW = w
                                previewH = h
                                val lp = previewView.layoutParams
                                lp.width = w
                                lp.height = h
                                previewView.layoutParams = lp
                                renderPreview()
                            }
                        }
                    }
                }.start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySelectedTheme()
        setContentView(R.layout.activity_apply_filter)

        imageArea = findViewById(R.id.imageArea)
        imagePlaceholder = findViewById(R.id.imagePlaceholder)
        previewView = findViewById(R.id.previewView)
        seekIntensity = findViewById(R.id.seekIntensity)
        btnSave = findViewById(R.id.btnSave)
        progressBar = findViewById(R.id.progressBar)
        tvFilterName = findViewById(R.id.tvFilterName)
        lutRecyclerView = findViewById(R.id.lutRecyclerView)

        // ---- GLSurfaceView：GPU 直出预览（复用 RawPipeline）----
        previewView.setEGLContextFactory(object : GLSurfaceView.EGLContextFactory {
            override fun createContext(egl: EGL10, display: EGLDisplay, eglConfig: EGLConfig): EGLContext {
                val attr = intArrayOf(0x3098, 3, 0x3038) // EGL_CONTEXT_CLIENT_VERSION = 3
                return egl.eglCreateContext(display, eglConfig, EGL10.EGL_NO_CONTEXT, attr)
            }
            override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
                egl.eglDestroyContext(display, context)
            }
        })
        previewView.setRenderer(LutRenderer())
        previewView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        // ---- 按住图片显示原图，松开恢复滤镜 ----
        // 触摸逻辑挂在 imageArea 上（它覆盖整个图片区，含 GLSurfaceView）
        imageArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    pressHandler.removeCallbacks(showOriginalRunnable)
                    pressHandler.removeCallbacks(clickRunnable)
                    pressHandler.postDelayed(showOriginalRunnable, 500)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    val wasPressed = pressed
                    pressed = false
                    pressHandler.removeCallbacks(showOriginalRunnable)
                    if (showOriginal) {
                        showOriginal = false
                        renderPreview()
                    } else if (wasPressed && event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        // 短按（未触发按住）：重新选择图片
                        pressHandler.post(clickRunnable)
                    }
                }
            }
            true
        }

        // ---- 滤镜选择栏（复用主页 LutSelectorAdapter）----
        lutRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        lutAdapter = LutSelectorAdapter(context = this,
            onItemClick = { entry ->
                val path = entry.file?.absolutePath ?: ""
                currentFilterPath = path
                currentLut = entry.lutData
                tvFilterName.text = if (path.isNotEmpty()) entry.name else ""
                updateSaveEnabled()
                renderPreview()
            },
            onPhase1Complete = null
        )
        lutRecyclerView.adapter = lutAdapter

        // ---- 强度滑块：实时重渲染（预览，GPU 直出无读回，流畅）----
        seekIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) renderPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSave.setOnClickListener {
            saveFullRes()
        }

        if (LutUtils.isStorageAuthorized(this)) {
            LutUtils.seedPresetFilters(this)
            loadLutList()
        } else if (LutUtils.shouldRequestStorage(this)) {
            LutUtils.requestStorageAccess(this)
        }
    }

    override fun onResume() {
        super.onResume()
        previewView.onResume()
        if (LutUtils.isStorageAuthorized(this)) {
            LutUtils.seedPresetFilters(this)
            loadLutList()
        }
    }

    override fun onPause() {
        super.onPause()
        previewView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        pipeline = null
    }

    private fun loadLutList() {
        if (!LutUtils.isStorageAuthorized(this)) {
            if (LutUtils.shouldRequestStorage(this)) LutUtils.requestStorageAccess(this)
            return
        }
        lutAdapter.loadFromDirectory(LutUtils.filtersDir())
    }

    /** 当前是否为「无」滤镜（或强度为 0） */
    private fun hasActiveFilter(): Boolean {
        return currentFilterPath.isNotEmpty() && currentLut != null && seekIntensity.progress > 0
    }

    private fun updateSaveEnabled() {
        btnSave.isEnabled = originalBitmap != null && hasActiveFilter()
    }

    /** 预览：请求 GL 线程重绘（GPU 直出，无读回）。 */
    private fun renderPreview() {
        if (originalBitmap == null) return
        previewView.requestRender()
    }

    /** 保存：在 GL 线程用离屏 FBO 渲染全分辨率并读回，写相册。 */
    private fun saveFullRes() {
        val bmp = originalBitmap ?: return
        if (!glReady) return
        val lut = currentLut ?: return
        val intensity = seekIntensity.progress / 100f
        if (intensity <= 0f) return

        btnSave.isEnabled = false
        progressBar.visibility = View.VISIBLE

        previewView.queueEvent {
            val full = pipeline?.applyLutToBitmap(bmp, lut, intensity)
            mainHandler.post {
                progressBar.visibility = View.GONE
                if (full != null) {
                    resultBitmap = full
                    val name = "filter_${System.currentTimeMillis()}.jpg"
                    val (_, savedUri) = saveBitmapAsJpeg(this, full, name)
                    Toast.makeText(this, "已保存到 DCIM/Camera/", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "滤镜渲染失败", Toast.LENGTH_SHORT).show()
                }
                updateSaveEnabled()
            }
        }
        previewView.requestRender()
    }

    /** 解码全尺寸位图（GPU 分块在 RawPipeline 内部处理）。 */
    private fun decodeFullBitmap(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeFullBitmap failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "ApplyFilter"
    }
}
