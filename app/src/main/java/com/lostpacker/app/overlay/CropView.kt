package com.lostpacker.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 截图/上传图 → 框选图标视图：
 *  - 单指拖动直接框选（无需切换“框选模式”按钮）
 *  - 双指：捏合缩放 + 双指拖动移动画面
 *  - 「框全图」一键框整张图片
 * [selectedBitmap] 返回源图上按框裁剪的图标（独立拷贝，不随源图回收而失效）。
 */
class CropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null)
    : View(context, attrs) {

    private var source: Bitmap? = null

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitScale = 1f

    private val density: Float get() = resources.displayMetrics.density

    private val fill = Paint().apply { style = Paint.Style.FILL; color = 0x223DDC97.toInt() }
    private val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f * density; color = 0xFF3DDC97.toInt() }
    private val tip = Paint().apply { textSize = 13f * density; color = Color.WHITE }
    private val mask = Paint().apply { color = 0x55000000 }

    // 手势
    private var pinchStartDist = -1f; private var pinchStartScale = 1f
    private var lastMidX = 0f; private var lastMidY = 0f
    private var drawing = false

    // 框（源像素坐标，随缩放/平移保持稳定）
    private var boxL = -1f; private var boxT = -1f; private var boxR = -1f; private var boxB = -1f

    fun setSource(bmp: Bitmap?) {
        source = bmp
        resetToFit()
        invalidate()
    }

    private fun resetToFit() {
        val bmp = source ?: return
        if (width <= 0 || height <= 0) return
        fitScale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        scale = fitScale
        tx = (width - bmp.width * scale) / 2f
        ty = (height - bmp.height * scale) / 2f
        boxL = -1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (source != null) resetToFit()
    }

    private fun toViewX(x: Float) = x * scale + tx
    private fun toViewY(y: Float) = y * scale + ty
    private fun toSrcX(vx: Float) = (vx - tx) / scale
    private fun toSrcY(vy: Float) = (vy - ty) / scale

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = source ?: return
        canvas.drawBitmap(bmp, null, RectF(toViewX(0f), toViewY(0f), toViewX(bmp.width.toFloat()), toViewY(bmp.height.toFloat())), null)
        // 外围压暗，突出图像
        canvas.drawRect(0f, 0f, width.toFloat(), max(0f, toViewY(0f)), mask)
        canvas.drawRect(0f, min(height.toFloat(), toViewY(bmp.height.toFloat())), width.toFloat(), height.toFloat(), mask)
        canvas.drawRect(0f, toViewY(0f), toViewX(0f), toViewY(bmp.height.toFloat()), mask)
        canvas.drawRect(toViewX(bmp.width.toFloat()), toViewY(0f), width.toFloat(), toViewY(bmp.height.toFloat()), mask)

        if (boxL >= 0) {
            val l = toViewX(min(boxL, boxR)); val t = toViewY(min(boxT, boxB))
            val r = toViewX(max(boxL, boxR)); val b = toViewY(max(boxT, boxB))
            canvas.drawRect(l, t, r, b, fill)
            canvas.drawRect(l, t, r, b, border)
            canvas.drawText("${(max(boxL,boxR)-min(boxL,boxR)).toInt()}x${(max(boxT,boxB)-min(boxT,boxB)).toInt()}px",
                l + 6, if (t > 22) t - 6 else b + 22, tip)
        } else {
            canvas.drawText("单指拖动即可框选 · 双指捏合缩放 / 双指拖动移动", 12f, 22f, tip)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = source ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawing = true
                val sx = toSrcX(event.x).coerceIn(0f, bmp.width.toFloat())
                val sy = toSrcY(event.y).coerceIn(0f, bmp.height.toFloat())
                boxL = sx; boxT = sy; boxR = sx; boxB = sy
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (drawing) boxL = -1f  // 加到双指时取消正在画的框
                drawing = false
                val d = pointerDist(event)
                if (d > 0) { pinchStartDist = d; pinchStartScale = scale }
                lastMidX = midX(event); lastMidY = midY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && pinchStartDist > 0) {
                    // 双指：先按移动矢量平移，再按距离变化缩放
                    val mx = midX(event); val my = midY(event)
                    tx += mx - lastMidX; ty += my - lastMidY
                    lastMidX = mx; lastMidY = my
                    val d = pointerDist(event)
                    scale = (pinchStartScale * d / pinchStartDist).coerceIn(fitScale, 6f)
                    invalidate()
                } else if (drawing) {
                    val sx = toSrcX(event.x).coerceIn(0f, bmp.width.toFloat())
                    val sy = toSrcY(event.y).coerceIn(0f, bmp.height.toFloat())
                    boxR = sx; boxB = sy
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinchStartDist = -1f
                drawing = false
            }
        }
        return true
    }

    private fun pointerDist(e: MotionEvent): Float {
        if (e.pointerCount < 2) return -1f
        val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f

    /** 返回源图坐标下的框选矩形；未画框或过小返回 null */
    fun selectedRect(): Rect? {
        val bmp = source ?: return null
        if (boxL < 0) return null
        val l = min(boxL, boxR).toInt().coerceIn(0, bmp.width)
        val t = min(boxT, boxB).toInt().coerceIn(0, bmp.height)
        val r = max(boxL, boxR).toInt().coerceIn(0, bmp.width)
        val b = max(boxT, boxB).toInt().coerceIn(0, bmp.height)
        if (r - l <= 1 || b - t <= 1) return null
        return Rect(l, t, r, b)
    }

    /** 返回源图上按框裁剪出的图标（独立拷贝，避免源图被回收后失效）。 */
    fun selectedBitmap(): Bitmap? {
        val r = selectedRect() ?: return null
        val bmp = source ?: return null
        val win = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
        return win.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** 一键框选整张图片 */
    fun selectAll() {
        val bmp = source ?: return
        boxL = 0f; boxT = 0f; boxR = bmp.width.toFloat(); boxB = bmp.height.toFloat()
        drawing = false
        invalidate()
    }

    fun clearBox() { boxL = -1f; invalidate() }
}