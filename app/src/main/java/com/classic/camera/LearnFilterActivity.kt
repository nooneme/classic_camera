package com.classic.camera

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class LearnFilterActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "LearnFilter"
    }

    private lateinit var originalImageArea: FrameLayout
    private lateinit var filterImageArea: FrameLayout
    private lateinit var originalPlaceholder: LinearLayout
    private lateinit var filterPlaceholder: LinearLayout
    private lateinit var originalPreview: ImageView
    private lateinit var filterPreview: ImageView
    private lateinit var btnAddImages: Button
    private lateinit var btnStartLearn: Button
    private lateinit var btnFinishLearn: Button
    private lateinit var tvCoverage: TextView

    /** 最近一次生成的 LUT 数据 */
    private var lastLut: FloatArray? = null

    private var originalUri: Uri? = null
    private var filterUri: Uri? = null

    /** 累积的多组像素数据 */
    private val allOrigPixels = mutableListOf<Int>()
    private val allFiltPixels = mutableListOf<Int>()

    /** 是否正在学习中 */
    private var isLearning = false
    private var isAdding = false

    private val originalPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        Log.d(TAG, "originalPicker result: $uri")
        if (uri != null) {
            originalUri = uri
            originalPreview.setImageURI(uri)
            originalPreview.visibility = ImageView.VISIBLE
            originalPlaceholder.visibility = LinearLayout.GONE
            updateButtons()
        }
    }

    private val filterPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        Log.d(TAG, "filterPicker result: $uri")
        if (uri != null) {
            filterUri = uri
            filterPreview.setImageURI(uri)
            filterPreview.visibility = ImageView.VISIBLE
            filterPlaceholder.visibility = LinearLayout.GONE
            updateButtons()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learn_filter)

        originalImageArea = findViewById(R.id.originalImageArea)
        filterImageArea = findViewById(R.id.filterImageArea)
        originalPlaceholder = findViewById(R.id.originalPlaceholder)
        filterPlaceholder = findViewById(R.id.filterPlaceholder)
        originalPreview = findViewById(R.id.originalPreview)
        filterPreview = findViewById(R.id.filterPreview)
        btnAddImages = findViewById(R.id.btnAddImages)
        btnStartLearn = findViewById(R.id.btnStartLearn)
        btnFinishLearn = findViewById(R.id.btnFinishLearn)
        tvCoverage = findViewById(R.id.tvCoverage)

        originalImageArea.setOnClickListener {
            originalPicker.launch("image/*")
        }

        filterImageArea.setOnClickListener {
            filterPicker.launch("image/*")
        }

        btnAddImages.setOnClickListener {
            addImages()
        }

        btnStartLearn.setOnClickListener {
            startLearning()
        }

        btnFinishLearn.setOnClickListener {
            showSaveDialog()
        }
    }

    private fun updateButtons() {
        val hasImages = originalUri != null && filterUri != null
        btnAddImages.isEnabled = hasImages && !isAdding
        btnStartLearn.isEnabled = allOrigPixels.isNotEmpty() && !isLearning
    }

    private fun updatePixelCount() {
        tvCoverage.text = "已累积 ${allOrigPixels.size} 像素"
        tvCoverage.setTextColor(0xFF888888.toInt())
    }

    private fun addImages() {
        val origUri = originalUri ?: return
        val filtUri = filterUri ?: return

        isAdding = true
        updateButtons()
        btnAddImages.text = "添加中…"

        Thread {
            try {
                val origBmp = loadBitmap(origUri)
                val filtBmp = loadBitmap(filtUri)

                if (origBmp == null || filtBmp == null) {
                    runOnUiThread {
                        Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
                        isAdding = false
                        btnAddImages.text = "添加图片"
                        updateButtons()
                    }
                    return@Thread
                }

                val (origSamples, filtSamples, sampleCount) = LutStrideSampler.sample(origBmp, filtBmp)
                origBmp.recycle()
                filtBmp.recycle()

                allOrigPixels.addAll(origSamples.toList())
                allFiltPixels.addAll(filtSamples.toList())

                runOnUiThread {
                    updatePixelCount()
                    Toast.makeText(this, "已添加 $sampleCount 像素", Toast.LENGTH_SHORT).show()
                    isAdding = false
                    btnAddImages.text = "添加图片"
                    updateButtons()
                }
            } catch (e: Exception) {
                Log.e(TAG, "add images failed", e)
                runOnUiThread {
                    Toast.makeText(this, "添加失败: ${e.message}", Toast.LENGTH_LONG).show()
                    isAdding = false
                    btnAddImages.text = "添加图片"
                    updateButtons()
                }
            }
        }.start()
    }

    private fun startLearning() {
        if (allOrigPixels.isEmpty()) return

        isLearning = true
        updateButtons()
        btnStartLearn.text = "学习中…"

        Thread {
            try {
                val totalNodes = LutEngine.LUT_SIZE * LutEngine.LUT_SIZE * LutEngine.LUT_SIZE
                val outLut = FloatArray(totalNodes * 3)

                val coverage = LutEngine.generateLutAndCheckCoverage(
                    allOrigPixels.toIntArray(), allFiltPixels.toIntArray(),
                    allOrigPixels.size, outLut
                )

                runOnUiThread {
                    lastLut = outLut
                    val pct = (coverage * 100).toInt()
                    tvCoverage.text = "色彩覆盖率: ${pct}% (已累积 ${allOrigPixels.size} 像素)"
                    tvCoverage.setTextColor(if (coverage >= 0.3f) 0xFF4CAF50.toInt() else 0xFFFFA726.toInt())
                    btnFinishLearn.isEnabled = true
                    Toast.makeText(this, "学习完成！色彩覆盖率 $pct%", Toast.LENGTH_LONG).show()
                    isLearning = false
                    btnStartLearn.text = "开始学习"
                    updateButtons()
                }
            } catch (e: Exception) {
                Log.e(TAG, "learning failed", e)
                runOnUiThread {
                    Toast.makeText(this, "学习失败: ${e.message}", Toast.LENGTH_LONG).show()
                    isLearning = false
                    btnStartLearn.text = "开始学习"
                    updateButtons()
                }
            }
        }.start()
    }

    private fun showSaveDialog() {
        val input = EditText(this).apply {
            hint = "输入滤镜名称"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("保存滤镜")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveCubeFile(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveCubeFile(name: String) {
        val lut = lastLut ?: return
        val dir = getExternalFilesDir("filters") ?: filesDir
        dir.mkdirs()
        val file = File(dir, "${name}.cube")

        if (file.exists()) {
            AlertDialog.Builder(this)
                .setTitle("覆盖滤镜")
                .setMessage("「$name」已存在，是否覆盖？")
                .setPositiveButton("覆盖") { _, _ -> writeCubeFile(file, name, lut) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            writeCubeFile(file, name, lut)
        }
    }

    private fun writeCubeFile(file: File, name: String, lut: FloatArray) {
        val ls = System.lineSeparator()
        val sb = StringBuilder()
        sb.append("TITLE \"$name\"").append(ls)
        sb.append("LUT_3D_SIZE ${LutEngine.LUT_SIZE}").append(ls)
        sb.append("DOMAIN_MIN 0.0 0.0 0.0").append(ls)
        sb.append("DOMAIN_MAX 1.0 1.0 1.0").append(ls)

        for (b in 0 until LutEngine.LUT_SIZE) {
            for (g in 0 until LutEngine.LUT_SIZE) {
                for (r in 0 until LutEngine.LUT_SIZE) {
                    val idx = (b * LutEngine.LUT_SIZE * LutEngine.LUT_SIZE + g * LutEngine.LUT_SIZE + r) * 3
                    sb.append("%.6f %.6f %.6f".format(lut[idx], lut[idx + 1], lut[idx + 2])).append(ls)
                }
            }
        }

        try {
            file.writeText(sb.toString())
            Toast.makeText(this, "已保存到 ${file.absolutePath}", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            null
        }
    }
}
