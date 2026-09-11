package com.lostpacker.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 大图找小图结果预览：
 *  - 双指捏合缩放 + 双指/单指拖动平移画面
 *  - 单指移动幅度极小(人不可能完全不动)后松手 = 轻点，判定为收起图片
 */
class FindPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 轻点收起时回调 */
    var onDismiss: (() -> Unit)? = null

    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    private var fitScale = 1f
    private var scale = 1f
    private var translateX = 0f
    private var translateY = 0f

    // 手势状态
    private var mode = 0            // 0=无 1=单指 2=双指
    private var downX = 0f
    private var downY = 0f
    private var totalMove = 0f
    private var pinchStartDist = 1f
    private var pinchStartScale = 1f
    private var pinchMidX = 0f
    private var pinchMidY = 0f
    private var pinchStartTX = 0f
    private var pinchStartTY = 0f

    private val tapThresholdPx: Float get() = 18f * resources.displayMetrics.density

    fun setImage(b: Bitmap) {
        bmp = b
        resetToFit()
        invalidate()
    }

    private fun resetToFit() {
        val b = bmp ?: return
        if (width <= 0 || height <= 0) return
        fitScale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        scale = fitScale
        translateX = (width - b.width * scale) / 2f
        translateY = (height - b.height * scale) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (bmp != null) resetToFit()
    }

    private fun rebuildMatrix() {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(translateX, translateY)
    }

    private fun clampTranslate() {
        val b = bmp ?: return
        val w = b.width * scale
        val h = b.height * scale
        val m = 40f * resources.displayMetrics.density
        val minX = min(0f, width - w) - m
        val maxX = max(0f, width - w) + m
        val minY = min(0f, height - h) - m
        val maxY = max(0f, height - h) + m
        translateX = translateX.coerceIn(minX, maxX)
        translateY = translateY.coerceIn(minY, maxY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp ?: return
        rebuildMatrix()
        canvas.drawBitmap(b, matrix, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 1
                downX = event.x; downY = event.y
                totalMove = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode != 2) {
                    mode = 2
                    pinchStartDist = pointerDist(event)
                    pinchStartScale = scale
                    pinchMidX = midX(event); pinchMidY = midY(event)
                    pinchStartTX = translateX; pinchStartTY = translateY
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    1 -> {
                        val dx = event.x - downX; val dy = event.y - downY
                        totalMove += kotlin.math.abs(dx) + kotlin.math.abs(dy)
                        // 双指以外，单指在放大后可平移画面
                        translateX += dx; translateY += dy
                        clampTranslate()
                        downX = event.x; downY = event.y
                        invalidate()
                    }
                    2 -> {
                        val d = pointerDist(event)
                        if (d > 0.001f) {
                            val newScale = (pinchStartScale * d / pinchStartDist)
                                .coerceIn(fitScale, 8f)
                            // 以两指中点缩放，保持该点内容不动
                            val ratio = newScale / pinchStartScale
                            val mx = midX(event); val my = midY(event)
                            translateX = mx - (mx - pinchStartTX) * ratio
                            translateY = my - (my - pinchStartTY) * ratio
                            scale = newScale
                            pinchMidX = mx; pinchMidY = my
                            clampTranslate()
                            invalidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 只剩一指时回到单指模式，并重置累计位移，避免误判成轻点
                mode = 1
                totalMove = 0f
                downX = event.getX(0); downY = event.getY(0)
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 1 && totalMove < tapThresholdPx) {
                    onDismiss?.invoke()
                }
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> { mode = 0 }
        }
        return true
    }

    private fun pointerDist(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 1f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f
}
