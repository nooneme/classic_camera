package com.classic.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ApplyFilterActivity : AppCompatActivity() {

    private lateinit var imageArea: FrameLayout
    private lateinit var imagePlaceholder: LinearLayout
    private lateinit var imagePreview: ImageView
    private lateinit var filterSpinner: Spinner
    private lateinit var btnApply: Button
    private lateinit var resultImage: ImageView
    private lateinit var btnSave: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvResultLabel: TextView
    private lateinit var scrollView: ScrollView

    private var selectedImageUri: Uri? = null
    private var selectedFilter: File? = null
    private var resultBitmap: Bitmap? = null

    private var filterFiles = listOf<File>()
    private var filterNames = listOf<String>()

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            imagePreview.setImageURI(uri)
            imagePreview.visibility = ImageView.VISIBLE
            imagePlaceholder.visibility = LinearLayout.GONE
            updateApplyButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apply_filter)

        imageArea = findViewById(R.id.imageArea)
        imagePlaceholder = findViewById(R.id.imagePlaceholder)
        imagePreview = findViewById(R.id.imagePreview)
        filterSpinner = findViewById(R.id.filterSpinner)
        btnApply = findViewById(R.id.btnApply)
        resultImage = findViewById(R.id.resultImage)
        btnSave = findViewById(R.id.btnSave)
        progressBar = findViewById(R.id.progressBar)
        tvResultLabel = findViewById(R.id.tvResultLabel)
        scrollView = findViewById(R.id.scrollView)

        imageArea.setOnClickListener {
            imagePicker.launch("image/*")
        }

        loadFilters()

        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedFilter = if (position == 0) null else filterFiles[position - 1]
                updateApplyButton()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnApply.setOnClickListener {
            applyFilter()
        }

        btnSave.setOnClickListener {
            val bmp = resultBitmap ?: return@setOnClickListener
            val name = "filter_${System.currentTimeMillis()}.jpg"
            saveBitmapAsJpeg(this, bmp, name)
            Toast.makeText(this, "已保存到 Pictures/gufa/", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadFilters() {
        val dir = getExternalFilesDir("filters") ?: filesDir
        filterFiles = if (dir.isDirectory) {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".cube") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        } else emptyList()
        filterNames = filterFiles.map { it.nameWithoutExtension }
        val items = mutableListOf("（选择滤镜）")
        items.addAll(filterNames)
        filterSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun updateApplyButton() {
        btnApply.isEnabled = selectedImageUri != null && selectedFilter != null
    }

    companion object {
        private const val TAG = "ApplyFilter"
        private const val STRIP_HEIGHT = 64
    }

    private fun applyFilter() {
        val uri = selectedImageUri ?: return
        val filterFile = selectedFilter ?: return

        btnApply.isEnabled = false
        progressBar.visibility = View.VISIBLE
        resultImage.visibility = View.GONE
        tvResultLabel.visibility = View.GONE

        Thread {
            try {
                val lut = LutUtils.loadCubeFile(filterFile) ?: run {
                    runOnUiThread { Toast.makeText(this, "滤镜文件加载失败", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                Log.d(TAG, "开始分块处理: $uri")
                val result = decodeAndApplyInStrips(uri, lut)

                if (result == null) {
                    runOnUiThread { Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                Log.d(TAG, "处理完成: ${result.width}x${result.height}")
                runOnUiThread {
                    resultBitmap = result
                    progressBar.visibility = View.GONE
                    resultImage.setImageBitmap(result)
                    resultImage.visibility = View.VISIBLE
                    tvResultLabel.visibility = View.VISIBLE
                    btnSave.visibility = View.VISIBLE
                    btnApply.isEnabled = true
                    scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "处理失败", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
                    btnApply.isEnabled = true
                }
            }
        }.start()
    }

    /** 用 BitmapRegionDecoder 逐条解码+处理，避免同时持有全尺寸输入和输出图 */
    private fun decodeAndApplyInStrips(uri: Uri, lut: FloatArray): Bitmap? {
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
        return try {
            val decoder = BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
            val w = decoder.width
            val h = decoder.height
            Log.d(TAG, "原图尺寸: ${w}x${h}")

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val lutSize = 33
            val lutSizeMinus1 = (lutSize - 1).toFloat()

            for (y in 0 until h step STRIP_HEIGHT) {
                val curH = minOf(STRIP_HEIGHT, h - y)
                val strip = decoder.decodeRegion(Rect(0, y, w, y + curH),
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })

                val pixels = IntArray(w * curH)
                strip.getPixels(pixels, 0, w, 0, 0, w, curH)

                for (row in 0 until curH) {
                    val rowOffset = row * w
                    for (x in 0 until w) {
                        val i = rowOffset + x
                        val argb = pixels[i]
                        val a = (argb ushr 24) and 0xFF
                        val r = (argb ushr 16) and 0xFF
                        val g = (argb ushr 8) and 0xFF
                        val b = argb and 0xFF

                        val fr = r / 255.0f * lutSizeMinus1
                        val fg = g / 255.0f * lutSizeMinus1
                        val fb = b / 255.0f * lutSizeMinus1

                        val r0 = fr.toInt(); val r1 = (r0 + 1).coerceAtMost(lutSize - 1)
                        val g0 = fg.toInt(); val g1 = (g0 + 1).coerceAtMost(lutSize - 1)
                        val b0 = fb.toInt(); val b1 = (b0 + 1).coerceAtMost(lutSize - 1)

                        val dr = fr - r0; val dg = fg - g0; val db = fb - b0

                        fun idx(r: Int, g: Int, b: Int): Int = (b * lutSize * lutSize + g * lutSize + r) * 3

                        val c000 = lut[idx(r0, g0, b0)]; val c100 = lut[idx(r1, g0, b0)]
                        val c010 = lut[idx(r0, g1, b0)]; val c110 = lut[idx(r1, g1, b0)]
                        val c001 = lut[idx(r0, g0, b1)]; val c101 = lut[idx(r1, g0, b1)]
                        val c011 = lut[idx(r0, g1, b1)]; val c111 = lut[idx(r1, g1, b1)]

                        val or = lerp(lerp(lerp(c000, c100, dr), lerp(c010, c110, dr), dg),
                                      lerp(lerp(c001, c101, dr), lerp(c011, c111, dr), dg), db)
                        val og = lerp(lerp(lerp(lut[idx(r0,g0,b0)+1], lut[idx(r1,g0,b0)+1], dr),
                                           lerp(lut[idx(r0,g1,b0)+1], lut[idx(r1,g1,b0)+1], dr), dg),
                                      lerp(lerp(lut[idx(r0,g0,b1)+1], lut[idx(r1,g0,b1)+1], dr),
                                           lerp(lut[idx(r0,g1,b1)+1], lut[idx(r1,g1,b1)+1], dr), dg), db)
                        val ob = lerp(lerp(lerp(lut[idx(r0,g0,b0)+2], lut[idx(r1,g0,b0)+2], dr),
                                           lerp(lut[idx(r0,g1,b0)+2], lut[idx(r1,g1,b0)+2], dr), dg),
                                      lerp(lerp(lut[idx(r0,g0,b1)+2], lut[idx(r1,g0,b1)+2], dr),
                                           lerp(lut[idx(r0,g1,b1)+2], lut[idx(r1,g1,b1)+2], dr), dg), db)

                        val nr = (or * 255f).toInt().coerceIn(0, 255)
                        val ng = (og * 255f).toInt().coerceIn(0, 255)
                        val nb = (ob * 255f).toInt().coerceIn(0, 255)
                        pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
                    }
                }

                out.setPixels(pixels, 0, w, 0, y, w, curH)
                strip.recycle()
            }

            decoder.recycle()
            out
        } finally {
            pfd.close()
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
