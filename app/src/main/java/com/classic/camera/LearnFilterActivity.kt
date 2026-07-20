package com.classic.camera

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
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
    private lateinit var spFitMethod: Spinner

    /** 最近一次生成的 LUT 数据 */
    private var lastLut: FloatArray? = null

    private var originalUri: Uri? = null
    private var filterUri: Uri? = null

    /** 累积的多组像素数据 */
    private val allOrigPixels = mutableListOf<Int>()
    private val allFiltPixels = mutableListOf<Int>()
    /** 已见过的原图颜色，用于去重 */
    private val seenOrigColors = mutableSetOf<Int>()

    /** 是否正在学习中 */
    private var isLearning = false
    private var isAdding = false

    /** 是否使用多项式拟合模式 */
    private var isPolyFitMode = false

    private var pendingTarget: String? = null

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                Log.d(TAG, "gallery result: $uri for $pendingTarget")
                when (pendingTarget) {
                    "original" -> {
                        originalUri = uri
                        originalPreview.setImageURI(uri)
                        originalPreview.visibility = ImageView.VISIBLE
                        originalPlaceholder.visibility = LinearLayout.GONE
                    }
                    "filter" -> {
                        filterUri = uri
                        filterPreview.setImageURI(uri)
                        filterPreview.visibility = ImageView.VISIBLE
                        filterPlaceholder.visibility = LinearLayout.GONE
                    }
                }
                updateButtons()
            }
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
        spFitMethod = findViewById(R.id.spFitMethod)

        val fitMethods = arrayOf("标准拟合（需大量样本）", "多项式拟合（少量样本）")
        val spinnerAdapter = object : ArrayAdapter<String>(this, R.layout.spinner_dropdown_item, fitMethods) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent) as android.widget.TextView
                v.setTextColor(0xFFDDDDDD.toInt())
                return v
            }
        }
        spFitMethod.adapter = spinnerAdapter
        spFitMethod.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                isPolyFitMode = pos == 1
                tvCoverage.text = if (isPolyFitMode) "多项式拟合模式：少量样本即可覆盖全色域"
                                  else "已累积 ${allOrigPixels.size} 像素"
                tvCoverage.setTextColor(0xFF888888.toInt())
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        originalImageArea.setOnClickListener {
            pendingTarget = "original"
            galleryLauncher.launch(Intent(this, GalleryActivity::class.java))
        }

        filterImageArea.setOnClickListener {
            pendingTarget = "filter"
            galleryLauncher.launch(Intent(this, GalleryActivity::class.java))
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
        btnAddImages.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (btnAddImages.isEnabled) 0xFF4A90D9.toInt() else 0xFF555555.toInt())
        btnStartLearn.isEnabled = allOrigPixels.isNotEmpty() && !isLearning
        btnStartLearn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (btnStartLearn.isEnabled) 0xFF4A90D9.toInt() else 0xFF555555.toInt())
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

                val (origSamples, filtSamples, sampleCount) = LutStrideSampler.sample(origBmp, filtBmp, seenOrigColors)
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

                if (isPolyFitMode) {
                    val stats = FloatArray(7)
                    LutEngine.fitPolynomialLut(
                        allOrigPixels.toIntArray(), allFiltPixels.toIntArray(),
                        allOrigPixels.size, outLut, stats
                    )
                    val trainAvg = stats[0]; val validAvg = stats[1]
                    val trainMax = stats[2]; val validMax = stats[3]
                    val worstR = stats[4].toInt(); val worstG = stats[5].toInt(); val worstB = stats[6].toInt()
                    val trainAvg255 = (trainAvg * 255).toInt()
                    val validAvg255 = (validAvg * 255).toInt()
                    val trainMax255 = (trainMax * 255).toInt()
                    val validMax255 = (validMax * 255).toInt()
                    val ratio = if (validAvg > 0f) trainAvg / validAvg else 1f
                    val ratioStr = "%.2f".format(ratio)
                    val capacityText = when {
                        ratio < 0.6f -> "可能过拟合(训练<<验证)，试试减少样本"
                        ratio > 0.9f -> "容量充足(训练≈验证)"
                        else -> "容量适中"
                    }

                    runOnUiThread {
                        lastLut = outLut
                        tvCoverage.text = "训练平均误差 ${"%.4f".format(trainAvg)}（≈${trainAvg255}/255）\n验证平均误差 ${"%.4f".format(validAvg)}（≈${validAvg255}/255）\n训练最大误差 ${"%.4f".format(trainMax)}（≈${trainMax255}/255）\n验证最大误差 ${"%.4f".format(validMax)}（≈${validMax255}/255）\n最差输入颜色 (${worstR},${worstG},${worstB})  ${capacityText}"
                        tvCoverage.setTextColor(android.graphics.Color.rgb(worstR, worstG, worstB))
                        btnFinishLearn.isEnabled = true
                        Toast.makeText(this, "拟合完成！训练均差=${trainAvg}, 验证均差=$validAvg, 验证最大=$validMax", Toast.LENGTH_LONG).show()
                        isLearning = false
                        btnStartLearn.text = "开始学习"
                        updateButtons()
                    }
                } else {
                    val outCovered = BooleanArray(totalNodes)
                    val coverage = LutEngine.generateLutAndCheckCoverage(
                        allOrigPixels.toIntArray(), allFiltPixels.toIntArray(),
                        allOrigPixels.size, outLut, outCovered
                    )

                    runOnUiThread {
                        lastLut = outLut
                        val pct = (coverage * 100).toInt()
                        tvCoverage.text = "色彩覆盖率: ${pct}% (已累积 ${allOrigPixels.size} 像素)"
                        tvCoverage.setTextColor(if (coverage >= 0.3f) 0xFF4CAF50.toInt() else 0xFFFFA726.toInt())
                        btnFinishLearn.isEnabled = true
                        CoverageVisualizer.pendingData = outCovered
                        startActivity(android.content.Intent(this@LearnFilterActivity, CoverageVisualizerActivity::class.java))
                        Toast.makeText(this, "学习完成！色彩覆盖率 $pct%", Toast.LENGTH_LONG).show()
                        isLearning = false
                        btnStartLearn.text = "开始学习"
                        updateButtons()
                    }
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
