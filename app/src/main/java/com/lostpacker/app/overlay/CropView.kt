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
 * 截图→框选图标视图：
 *  - 双指捏合缩放、单指拖动平移查看整张截图
 *  - 点击「开始框选」进入画框模式，单指画框选中某个物品图标
 *  - [selectedBitmap] 返回源图上按框裁剪的图标
 */
class CropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null)
    : View(context, attrs) {

    private var source: Bitmap? = null

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitScale = 1f

    var boxMode = false

    private val density: Float get() = resources.displayMetrics.density

    private val fill = Paint().apply { style = Paint.Style.FILL; color = 0x223DDC97.toInt() }
    private val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f * density; color = 0xFF3DDC97.toInt() }
    private val tip = Paint().apply { textSize = 13f * density; color = Color.WHITE }
    private val mask = Paint().apply { color = 0x55000000 }

    // 手势
    private var lastX = 0f; private var lastY = 0f
    private var pinchStartDist = -1f; private var pinchStartScale = 1f
    private var pinchMidX = 0f; private var pinchMidY = 0f

    // 框（源像素坐标，随缩放/平移保持稳定）
    private var boxL = -1f; private var boxT = -1f; private var boxR = -1f; private var boxB = -1f
    private var boxStartX = 0f; private var boxStartY = 0f
    private var drawing = false

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
        // 底图
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
        } else if (!boxMode) {
            canvas.drawText("双指缩放 · 单指拖动 | 点「开始框选」画框", 12f, 22f, tip)
        } else {
            canvas.drawText("单指拖动框出物品图标", 12f, 22f, tip)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = source ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (boxMode) {
                    drawing = true
                    val sx = toSrcX(event.x).coerceIn(0f, bmp.width.toFloat())
                    val sy = toSrcY(event.y).coerceIn(0f, bmp.height.toFloat())
                    boxStartX = sx; boxStartY = sy
                    boxL = sx; boxT = sy; boxR = sx; boxB = sy
                    invalidate()
                } else {
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 进入双指缩放
                val p = pointerDist(event)
                if (p > 0) { pinchStartDist = p; pinchStartScale = scale; pinchMidX = event.getX(0) + (event.getX(1)-event.getX(0))/2; pinchMidY = event.getY(0) + (event.getY(1)-event.getY(0))/2 }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && pinchStartDist > 0) {
                    val d = pointerDist(event)
                    var k = if (pinchStartDist > 0) d / pinchStartDist else 1f
                    val newScale = (pinchStartScale * k).coerceIn(fitScale, 6f)
                    k = newScale / scale
                    // 绕中点缩放
                    tx = pinchMidX - (pinchMidX - tx) * k
                    ty = pinchMidY - (pinchMidY - ty) * k
                    scale = newScale
                } else if (boxMode && drawing) {
                    val sx = toSrcX(event.x).coerceIn(0f, bmp.width.toFloat())
                    val sy = toSrcY(event.y).coerceIn(0f, bmp.height.toFloat())
                    boxR = sx; boxB = sy
                } else {
                    tx += event.x - lastX
                    ty += event.y - lastY
                    lastX = event.x; lastY = event.y
                }
                invalidate()
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

    /** 返回源图上按框裁剪出的图标；未画框或尺度过小时返回 null */
    fun selectedBitmap(): Bitmap? {
        val r = selectedRect() ?: return null
        val bmp = source ?: return null
        return Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
    }

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

    fun clearBox() { boxL = -1f; invalidate() }
}