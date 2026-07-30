package com.classic.camera

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CurveEditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CURVE = "tone_curve"

        fun createIntent(context: Context, curve: ToneCurve): Intent {
            val sb = StringBuilder()
            for (p in curve.points) {
                if (sb.isNotEmpty()) sb.append(",")
                sb.append(p.x).append(",").append(p.y)
            }
            return Intent(context, CurveEditorActivity::class.java).apply {
                putExtra(EXTRA_CURVE, sb.toString())
            }
        }

        fun parseResultCurve(data: Intent?): ToneCurve? {
            val s = data?.getStringExtra(EXTRA_CURVE) ?: return null
            val curve = ToneCurve()
            curve.points.clear()
            val parts = s.split(",")
            for (i in parts.indices step 2) {
                if (i + 1 >= parts.size) break
                val x = parts[i].toFloatOrNull() ?: continue
                val y = parts[i + 1].toFloatOrNull() ?: continue
                curve.points.add(ControlPoint(x, y))
            }
            if (curve.points.size < 2) return null
            return curve
        }
    }

    private lateinit var curveView: CurveEditorView
    private lateinit var titleBar: LinearLayout
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySelectedTheme()
        window.navigationBarColor = getAttrColor(R.attr.surfaceDarkest)

        val curve = parseIntentCurve()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getAttrColor(R.attr.surfaceDarkest))
        }

        titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(getAttrColor(R.attr.surfaceDark))
        }

        val btnBack = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(getAttrColor(R.attr.textPrimary))
            layoutParams = LinearLayout.LayoutParams(56, 56)
            setOnClickListener { finish() }
        }

        val tvTitle = TextView(this).apply {
            text = "色调曲线"
            textSize = 18f
            setTextColor(getAttrColor(R.attr.textPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                marginStart = 16
            }
        }

        val btnReset = TextView(this).apply {
            text = "重置"
            textSize = 14f
            setTextColor(getAttrColor(R.attr.errorColor))
            setPadding(24, 12, 24, 12)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener {
                curveView.resetCurve()
                Toast.makeText(this@CurveEditorActivity, "已重置为 y=x", Toast.LENGTH_SHORT).show()
            }
        }

        val btnApply = TextView(this).apply {
            text = "应用"
            textSize = 14f
            setTextColor(getAttrColor(R.attr.accentColor))
            setPadding(24, 12, 24, 12)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { applyAndFinish() }
        }

        titleBar.addView(btnBack)
        titleBar.addView(tvTitle)
        titleBar.addView(btnReset)
        titleBar.addView(btnApply)

        curveView = CurveEditorView(this).apply {
            setCurve(curve)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            onDragStart = {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0f)
                window.decorView.background = ColorDrawable(Color.TRANSPARENT)
                root.setBackgroundColor(Color.TRANSPARENT)
                curveView.setBackgroundColor(Color.TRANSPARENT)
                titleBar.visibility = android.view.View.GONE
                tvHint.visibility = android.view.View.GONE
            }
            onDragEnd = {
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0.6f)
                window.decorView.background = null
                titleBar.visibility = android.view.View.VISIBLE
                tvHint.visibility = android.view.View.VISIBLE
                root.setBackgroundColor(getAttrColor(R.attr.surfaceDarkest))
                curveView.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        root.addView(titleBar)
        root.addView(curveView)

        // 底部说明栏
        tvHint = TextView(this).apply {
            text = "点击曲线添加控制点，拖动控制点调整形状"
            textSize = 12f
            setTextColor(getAttrColor(R.attr.textSecondary))
            gravity = android.view.Gravity.CENTER
            setPadding(16, 16, 16, 16)
            setBackgroundColor(getAttrColor(R.attr.surfaceDark))
        }
        root.addView(tvHint)

        setContentView(root)
    }

    private fun parseIntentCurve(): ToneCurve {
        val s = intent.getStringExtra(EXTRA_CURVE) ?: return ToneCurve()
        if (s.isEmpty()) return ToneCurve()
        val curve = ToneCurve()
        curve.points.clear()
        val parts = s.split(",")
        for (i in parts.indices step 2) {
            if (i + 1 >= parts.size) break
            val x = parts[i].toFloatOrNull() ?: continue
            val y = parts[i + 1].toFloatOrNull() ?: continue
            curve.points.add(ControlPoint(x, y))
        }
        if (curve.points.size < 2) {
            curve.reset()
        }
        return curve
    }

    private fun applyAndFinish() {
        val curve = curveView.getCurve()
        val sb = StringBuilder()
        for (p in curve.points) {
            if (sb.isNotEmpty()) sb.append(",")
            sb.append(p.x).append(",").append(p.y)
        }
        val intent = Intent().apply {
            putExtra(EXTRA_CURVE, sb.toString())
        }
        setResult(RESULT_OK, intent)
        finish()
    }
}
