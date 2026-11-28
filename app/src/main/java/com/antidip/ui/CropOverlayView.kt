package com.antidip.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * V2.4 拍照录题裁剪框（用户要求 2026-11-28）：在题目照片上拖动一个可移动/
 * 可缩放的矩形，框住要录入的题目（框选越准，AI 识别越精确）。
 *
 * 视图自身只管理"视图坐标系"里的裁剪矩形（[rect]）；Activity 在确认裁剪时，
 * 通过 ImageView 的 imageMatrix 逆映射把该矩形换算到源位图坐标后裁剪。
 *
 * 交互：拖四角圆点 = 缩放该角；拖框内部 = 整体移动；点框外 = 忽略（防误触）。
 */
class CropOverlayView(context: Context) : View(context) {

    /** 视图坐标系下的裁剪矩形（onSizeChanged 后可用，默认居中 84%）。 */
    val rect = RectF()

    private val dimPaint = Paint().apply {
        color = 0x8A000000.toInt(); style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = 0xFF9BEA00.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val handlePaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL
    }
    private val handleStroke = Paint().apply {
        color = 0xFF3E9B1E.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }

    private var mode = 0 // 0=无 1=移动 2=左上 3=右上 4=左下 5=右下
    private var lastX = 0f
    private var lastY = 0f
    private val handleR = dp(10f)
    private val touchSlop = dp(28f)
    private val minSize = dp(60f)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** 重置为默认裁剪框（居中，边距 8%）。 */
    fun resetRect() {
        if (width > 0 && height > 0) {
            rect.set(width * 0.08f, height * 0.08f, width * 0.92f, height * 0.92f)
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (rect.isEmpty) resetRect()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rect.isEmpty) return
        val w = width.toFloat(); val h = height.toFloat()
        // 四周压暗，框内保持原亮度（分四块画，避免 PorterDuff 依赖）。
        canvas.drawRect(0f, 0f, w, rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, w, h, dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, dimPaint)
        canvas.drawRect(rect, borderPaint)
        for (p in corners()) {
            canvas.drawCircle(p[0], p[1], handleR, handlePaint)
            canvas.drawCircle(p[0], p[1], handleR, handleStroke)
        }
    }

    private fun corners(): List<FloatArray> = listOf(
        floatArrayOf(rect.left, rect.top),
        floatArrayOf(rect.right, rect.top),
        floatArrayOf(rect.left, rect.bottom),
        floatArrayOf(rect.right, rect.bottom)
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                mode = hitMode(event.x, event.y)
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 0) return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x; lastY = event.y
                applyDrag(dx, dy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitMode(x: Float, y: Float): Int {
        for ((i, p) in corners().withIndex()) {
            if (abs(x - p[0]) <= touchSlop && abs(y - p[1]) <= touchSlop) {
                return i + 2 // 2..5
            }
        }
        return if (rect.contains(x, y)) 1 else 0
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val minX = handleR; val maxX = width - handleR
        val minY = handleR; val maxY = height - handleR
        when (mode) {
            1 -> {
                val nx = (rect.left + dx).coerceIn(0f, width - rect.width())
                val ny = (rect.top + dy).coerceIn(0f, height - rect.height())
                rect.offsetTo(nx, ny)
            }
            2 -> { // 左上
                rect.left = (rect.left + dx).coerceIn(minX, rect.right - minSize)
                rect.top = (rect.top + dy).coerceIn(minY, rect.bottom - minSize)
            }
            3 -> { // 右上
                rect.right = (rect.right + dx).coerceIn(rect.left + minSize, maxX)
                rect.top = (rect.top + dy).coerceIn(minY, rect.bottom - minSize)
            }
            4 -> { // 左下
                rect.left = (rect.left + dx).coerceIn(minX, rect.right - minSize)
                rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, maxY)
            }
            5 -> { // 右下
                rect.right = (rect.right + dx).coerceIn(rect.left + minSize, maxX)
                rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, maxY)
            }
        }
    }
}
