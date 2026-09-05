package com.lostpacker.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 图标框选视图：显示源截图（按比例适配），拖拽画出要识别/导出的图标矩形。
 * 通过 [selectedBitmap] 返回源图上裁剪出的图标。
 */
class CropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null)
    : View(context, attrs) {

    private var source: Bitmap? = null

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val density: Float get() = resources.displayMetrics.density

    private val fill = Paint().apply { style = Paint.Style.FILL; color = 0x223DDC97.toInt() }
    private val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f * density; color = 0xFF3DDC97.toInt() }
    private val tip = Paint().apply { textSize = 14f * density; color = Color.WHITE }
    private val dim = Paint().apply { color = 0x66000000 }

    private var startX = 0f; private var startY = 0f
    private var endX = 0f; private var endY = 0f
    private var hasRect = false

    fun setSource(bmp: Bitmap?) {
        source = bmp
        updateFit()
        invalidate()
    }

    private fun updateFit() {
        val bmp = source ?: return
        if (width <= 0 || height <= 0) return
        scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        offsetX = (width - bmp.width * scale) / 2f
        offsetY = (height - bmp.height * scale) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { updateFit() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = source ?: return
        val dst = RectF(offsetX, offsetY, offsetX + bmp.width * scale, offsetY + bmp.height * scale)
        canvas.drawBitmap(bmp, null, dst, null)
        // 外区压暗
        canvas.drawRect(0f, 0f, width.toFloat(), offsetY, dim)
        canvas.drawRect(0f, offsetY + bmp.height * scale, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(0f, offsetY, offsetX, offsetY + bmp.height * scale, dim)
        canvas.drawRect(offsetX + bmp.width * scale, offsetY, width.toFloat(), offsetY + bmp.height * scale, dim)

        if (hasRect) {
            val l = min(startX, endX); val t = min(startY, endY)
            val r = max(startX, endX); val b = max(startY, endY)
            canvas.drawRect(l, t, r, b, fill)
            canvas.drawRect(l, t, r, b, border)
            canvas.drawText("框出物品图标 (${toSrc(l).toInt()},${toSrcY(t).toInt()}) ${(toSrc(r) - toSrc(l)).toInt()}x${(toSrcY(b) - toSrcY(t)).toInt()}",
                l + 6, if (t > 26) t - 6 else b + 24, tip)
        }
    }

    private fun toSrc(v: Float) = (v - offsetX) / scale
    private fun toSrcY(v: Float) = (v - offsetY) / scale

    /** 返回源图上裁剪出的图标（矩形容器无效时返回 null） */
    fun selectedBitmap(): Bitmap? {
        val bmp = source ?: return null
        val l = (min(startX, endX) - offsetX) / scale
        val t = (min(startY, endY) - offsetY) / scale
        val r = (max(startX, endX) - offsetX) / scale
        val b = (max(startY, endY) - offsetY) / scale
        val x0 = l.coerceIn(0f, bmp.width.toFloat()).toInt()
        val y0 = t.coerceIn(0f, bmp.height.toFloat()).toInt()
        val w = (r.coerceAtMost(bmp.width.toFloat()) - x0).toInt()
        val h = (b.coerceAtMost(bmp.height.toFloat()) - y0).toInt()
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(bmp, x0, y0, w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                endX = event.x; endY = event.y
                hasRect = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> { endX = event.x; endY = event.y; invalidate() }
            MotionEvent.ACTION_UP -> { endX = event.x; endY = event.y; invalidate() }
        }
        return true
    }
}