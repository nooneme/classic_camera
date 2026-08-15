package com.classic.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class CurveEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val curve = ToneCurve()
    private val displayLUT = FloatArray(256)

    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 44, 44, 44)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintDiag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 44, 44, 44)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintCurve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 100, 181, 246)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val paintCurveShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 0, 0)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val paintPoint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 248, 244, 236)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintPointStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 100, 181, 246)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintEndpoints = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 44, 44, 44)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 100, 181, 246)
        style = Paint.Style.FILL
    }

    private var dragIndex = -1
    private val hitRadius = 32f

    private val pad = 0f
    var previewMode = false
        set(value) { field = value; invalidate() }

    var onCurveChanged: ((ToneCurve) -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    fun getCurve(): ToneCurve = curve

    fun setCurve(c: ToneCurve) {
        curve.points.clear()
        curve.points.addAll(c.points.map { ControlPoint(it.x, it.y) })
        rebuildLUT()
        invalidate()
    }

    fun resetCurve() {
        curve.reset()
        rebuildLUT()
        invalidate()
        onCurveChanged?.invoke(curve)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLUT()
    }

    private fun rebuildLUT() {
        ToneCurveEngine.generateLUT(curve.toNativeArray(), displayLUT.size).copyInto(displayLUT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val left = pad
        val top = pad
        val right = w - pad
        val bottom = h - pad
        val graphW = right - left
        val graphH = bottom - top

        if (!previewMode) {
            canvas.drawColor(Color.argb(255, 248, 244, 236))
            drawGrid(canvas, left, top, right, bottom, graphW, graphH)
            canvas.drawLine(left, bottom, right, top, paintDiag)
        }

        val fillPath = Path()
        val curvePath = Path()
        var first = true
        for (i in displayLUT.indices) {
            val x = left + (i.toFloat() / (displayLUT.size - 1)) * graphW
            val y = bottom - displayLUT[i] * graphH
            if (first) {
                curvePath.moveTo(x, y)
                fillPath.moveTo(x, y)
                first = false
            } else {
                curvePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        if (!previewMode) {
            fillPath.lineTo(right, bottom)
            fillPath.lineTo(left, bottom)
            fillPath.close()
            canvas.drawPath(fillPath, paintFill)
        }
        canvas.drawPath(curvePath, paintCurveShadow)
        canvas.drawPath(curvePath, paintCurve)

        val pointR = 14f
        val strokeR = 20f
        for ((i, p) in curve.points.withIndex()) {
            val cx = left + p.x * graphW
            val cy = bottom - p.y * graphH

            if (i == 0 || i == curve.points.size - 1) {
                canvas.drawCircle(cx, cy, pointR, paintEndpoints)
            } else {
                canvas.drawCircle(cx, cy, strokeR, paintPointStroke)
                canvas.drawCircle(cx, cy, pointR, paintPoint)
            }
        }
    }

    private fun drawGrid(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, gw: Float, gh: Float) {
        val divisions = 4
        for (i in 1 until divisions) {
            val fx = left + (i.toFloat() / divisions) * gw
            canvas.drawLine(fx, top, fx, bottom, paintGrid)
        }
        for (i in 1 until divisions) {
            val fy = top + (i.toFloat() / divisions) * gh
            canvas.drawLine(left, fy, right, fy, paintGrid)
        }
        canvas.drawLine(left, top, right, top, paintGrid)
        canvas.drawLine(right, top, right, bottom, paintGrid)
        canvas.drawLine(left, bottom, right, bottom, paintGrid)
        canvas.drawLine(left, top, left, bottom, paintGrid)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val left = pad
        val top = pad
        val right = w - pad
        val bottom = h - pad
        val graphW = right - left
        val graphH = bottom - top

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val tx = (event.x - left) / graphW
                val ty = 1f - (event.y - top) / graphH
                if (tx < 0f || tx > 1f || ty < 0f || ty > 1f) return false

                val normHit = hitRadius / graphW.coerceAtMost(graphH)
                val existing = curve.points.indexOfFirst {
                    hypot(it.x - tx, it.y - ty) < normHit
                }
                when {
                    // 命中端点（固定不可拖）：直接忽略，避免生成抓不到的幽灵点
                    existing == 0 || existing == curve.points.size - 1 -> {
                        dragIndex = -1
                    }
                    // 命中内部控制点：进入拖拽
                    existing > 0 -> {
                        dragIndex = existing
                        previewMode = true
                        onDragStart?.invoke()
                    }
                    // 空白处：新增一个控制点并立即进入拖拽
                    else -> {
                        val nx = tx.coerceIn(0f, 1f)
                        val ny = ty.coerceIn(0f, 1f)
                        // 落在端点 x 上会变成索引 0 的不可拖幽灵点，忽略
                        if (nx <= 0f || nx >= 1f) {
                            dragIndex = -1
                        } else {
                            dragIndex = curve.addPoint(nx, ny)
                            previewMode = true
                            onDragStart?.invoke()
                        }
                    }
                }
                // 进入拖拽时禁止父 RecyclerView 拦截，否则纵向拖动会被抢走变成滚动
                parent?.requestDisallowInterceptTouchEvent(dragIndex >= 0)
                rebuildLUT()
                invalidate()
                onCurveChanged?.invoke(curve)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIndex > 0 && dragIndex < curve.points.size - 1) {
                    val tx = (event.x - left) / graphW
                    val ty = 1f - (event.y - top) / graphH
                    val prev = curve.points[dragIndex - 1].x
                    val next = curve.points[dragIndex + 1].x
                    curve.points[dragIndex] = ControlPoint(
                        tx.coerceIn(prev, next),
                        ty.coerceIn(0f, 1f)
                    )
                    rebuildLUT()
                    invalidate()
                    onCurveChanged?.invoke(curve)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragIndex >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    previewMode = false
                    onDragEnd?.invoke()
                    dragIndex = -1
                    rebuildLUT()
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                previewMode = false
                onDragEnd?.invoke()
                dragIndex = -1
            }
        }
        return true
    }
}
