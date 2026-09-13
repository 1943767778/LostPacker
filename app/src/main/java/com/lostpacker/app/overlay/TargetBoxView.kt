package com.lostpacker.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * 全屏标注视图：在识别出的目标格子上画绿色框，用于让用户直观看到整理器“看到了什么”。
 * 本身不消费触摸事件（返回 false），配合全屏 NOT_TOUCH_MODAL 悬浮窗使用，游戏仍可正常操作。
 */
class TargetBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = 0xFF2ECC71.toInt()
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x223DDC97.toInt()
    }

    /** 需要标注的矩形列表（屏幕绝对坐标） */
    var boxes: List<Rect> = emptyList()
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (r in boxes) {
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, boxPaint)
        }
    }

    // 纯标注，不拦截任何触摸
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false
}
