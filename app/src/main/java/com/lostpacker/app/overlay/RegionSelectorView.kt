package com.lostpacker.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 全屏框选视图：手指拖拽画矩形，松开后回调所选区域。
 * 同时按 Prefs 的行列数绘制网格预览。
 */
class RegionSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onRegionSelected: ((Rect) -> Unit)? = null

    /** 网格行列数（当前游戏的背包格子），用于预览 */
    var gridCols = 8
    var gridRows = 8

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * resources.displayMetrics.density
        color = 0xFF3DDC97.toInt()
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x2222E8A0.toInt()
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.WHITE
        alpha = 150
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 13 * resources.displayMetrics.density
    }

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    var selecting = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = minOf(startX, endX)
        val t = minOf(startY, endY)
        val r = maxOf(startX, endX)
        val b = maxOf(startY, endY)
        if (selecting && r - l > 5 && b - t > 5) {
            canvas.drawRect(l, t, r, b, fillPaint)
            canvas.drawRect(l, t, r, b, borderPaint)
            drawGrid(canvas, l, t, r, b)
            canvas.drawText(
                "(${l.toInt()},${t.toInt()}) ${(r - l).toInt()}x${(b - t).toInt()}  | ${gridCols}x${gridRows} 格",
                l + 6, if (t > 30) t - 8 else b + 26, textPaint
            )
        }
    }

    private fun drawGrid(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val cols = gridCols
        val rows = gridRows
        if (cols <= 0 || rows <= 0) return
        val cw = (r - l) / cols
        val ch = (b - t) / rows
        for (c in 1 until cols) canvas.drawLine(l + cw * c, t, l + cw * c, b, gridPaint)
        for (rw in 1 until rows) canvas.drawLine(l, t + ch * rw, r, t + ch * rw, gridPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selecting = true
                startX = event.x; startY = event.y
                endX = event.x; endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x; endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x; endY = event.y
                selecting = false
                val l = minOf(startX, endX).toInt()
                val t = minOf(startY, endY).toInt()
                val r = maxOf(startX, endX).toInt()
                val b = maxOf(startY, endY).toInt()
                if (r - l > 20 && b - t > 20) {
                    onRegionSelected?.invoke(Rect(l, t, r, b))
                }
                invalidate()
            }
        }
        return true
    }

    /** 一键框选整个画面 */
    fun selectAll() {
        startX = 0f; startY = 0f
        endX = width.toFloat(); endY = height.toFloat()
        selecting = false
        invalidate()
    }

    /** 读取最近一次框选出来的区域（屏坐标）；未框或过小返回 null */
    fun lastRect(): Rect? {
        val l = minOf(startX, endX).toInt()
        val t = minOf(startY, endY).toInt()
        val r = maxOf(startX, endX).toInt()
        val b = maxOf(startY, endY).toInt()
        if (r - l > 20 && b - t > 20) return Rect(l, t, r, b)
        return null
    }
}