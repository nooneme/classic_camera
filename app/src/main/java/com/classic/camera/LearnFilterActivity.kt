package com.classic.camera

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.google.android.material.button.MaterialButton
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
    private lateinit var btnAddImages: MaterialButton
    private lateinit var btnStartLearn: MaterialButton
    private lateinit var btnFinishLearn: MaterialButton
    private lateinit var tvCoverage: TextView

    /** 最近一次生成的 LUT 数据 */
    private var lastLut: FloatArray? = null

    private var originalUri: Uri? = null
    private var filterUri: Uri? = null

    /** 累积的多组像素数据 */
    private val allOrigPixels = mutableListOf<Int>()
    private val allFiltPixels = mutableListOf<Int>()
    /** 已见过的原图颜色 -> 在全局 allFiltPixels 中的索引，用于去重与滤镜颜色平均 */
    private val seenOrigColors = mutableMapOf<Int, Int>()

    /** 是否正在学习中 */
    private var isLearning = false
    private var isAdding = false

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
        applySelectedTheme()
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
        btnStartLearn.isEnabled = allOrigPixels.isNotEmpty() && !isLearning
    }

    private fun updatePixelCount() {
        tvCoverage.text = "已累积 ${allOrigPixels.size} 像素"
        tvCoverage.setTextColor(getAttrColor(R.attr.textTertiary))
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

                val (smoothOrig, smoothFilt) = LutStrideSampler.preprocess(origBmp, filtBmp)
                origBmp.recycle()
                filtBmp.recycle()

                val (origSamples, filtSamples, sampleCount) = LutStrideSampler.sample(smoothOrig, smoothFilt, seenOrigColors, allFiltPixels)
                smoothOrig.recycle()
                smoothFilt.recycle()

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
                val stats = FloatArray(9)
                LutEngine.fitMlsLut(
                    allOrigPixels.toIntArray(), allFiltPixels.toIntArray(),
                    allOrigPixels.size, outLut, stats
                )
                showFitStats(stats, outLut)
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

    /** MLS 统计展示 */
    private fun showFitStats(stats: FloatArray, lut: FloatArray) {
        val avg = stats[0]; val max = stats[2]
        val worstR = stats[4].toInt(); val worstG = stats[5].toInt(); val worstB = stats[6].toInt()
        val quadCount = stats[7].toInt()
        val linCount = stats[8].toInt()
        val fitTotal = quadCount + linCount
        val quadPct = if (fitTotal > 0) quadCount * 100f / fitTotal else 0f
        val linPct = if (fitTotal > 0) linCount * 100f / fitTotal else 0f
        val avg255 = (avg * 255).toInt()
        val max255 = (max * 255).toInt()

        runOnUiThread {
            lastLut = lut
            tvCoverage.text = "MLS 拟合完成\n平均误差 ${"%.4f".format(avg)}（≈${avg255}/255）\n最大误差 ${"%.4f".format(max)}（≈${max255}/255）\n最差输入颜色 (${worstR},${worstG},${worstB})\n二次拟合 ${"%.1f".format(quadPct)}% · 线性拟合 ${"%.1f".format(linPct)}%"
            tvCoverage.setTextColor(android.graphics.Color.rgb(worstR, worstG, worstB))
            btnFinishLearn.isEnabled = true
            Toast.makeText(this, "MLS 拟合完成！平均误差=$avg, 最大误差=$max", Toast.LENGTH_LONG).show()
            isLearning = false
            btnStartLearn.text = "开始学习"
            updateButtons()
        }
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
        if (!LutUtils.isStorageAuthorized(this)) {
            if (LutUtils.shouldRequestStorage(this)) LutUtils.requestStorageAccess(this)
            Toast.makeText(this, "需要存储权限才能保存滤镜", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val lut = lastLut ?: return
        val dir = LutUtils.filtersDir()
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
