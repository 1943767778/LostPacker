package com.lostpacker.app.vision

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.max

/**
 * 轻量级图像识别：
 *  - 指纹：把格子压缩成固定大小灰度图并做归一化
 *  - 相似度：余弦相似度，用于判断“两格物品是否相同”
 *  - 占用检测：通过不透明像素覆盖率判断格子是否有物品
 */
object ImageMatcher {

    const val FP_SIZE = 16

    fun fingerprint(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, FP_SIZE, FP_SIZE, true)
        val px = IntArray(FP_SIZE * FP_SIZE)
        scaled.getPixels(px, 0, FP_SIZE, 0, 0, FP_SIZE, FP_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        val arr = FloatArray(FP_SIZE * FP_SIZE)
        var mean = 0f
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            arr[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            mean += arr[i]
        }
        mean /= arr.size
        var varSum = 0f
        for (v in arr) { val d = v - mean; varSum += d * d }
        val std = sqrt(varSum / arr.size)
        if (std > 1e-4f) for (i in arr.indices) arr[i] = (arr[i] - mean) / std
        return arr
    }

    /** 两个归一化指纹的余弦相似度，取值约 [-1,1]，同物通常 >0.9 */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return (dot / a.size).coerceIn(-1f, 1f)
    }

    /** 不透明像素覆盖率，用于判断格子是否有物品 */
    fun coverage(bitmap: Bitmap): Float {
        if (bitmap.width <= 0 || bitmap.height <= 0) return 0f
        // 采样最多 64x64 避免过大
        val w = min(bitmap.width, 64)
        val h = min(bitmap.height, 64)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        var opaque = 0
        for (c in px) {
            val a = (c ushr 24) and 0xFF
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val bl = c and 0xFF
            val lum = (0.299f * r + 0.587f * g + 0.114f * bl)
            if (a > 200 && lum > 60f) opaque++
        }
        return opaque.toFloat() / px.size
    }

    /**
     * 图中找图：在 [big] 中滑动窗口查找 [templ]，返回最佳左上角坐标与相似度。
     * 步长为模板宽的一半（兼顾速度与命中）。找不到返回 null。
     */
    fun locateTemplate(big: Bitmap, templ: Bitmap, threshold: Float = 0.8f): Pair<android.graphics.Point, Float>? {
        if (big.width < templ.width || big.height < templ.height) return null
        val tfp = fingerprint(templ)
        val step = max(1, templ.width / 2)
        var best: Pair<android.graphics.Point, Float>? = null
        var x = 0
        while (x + templ.width <= big.width) {
            var y = 0
            while (y + templ.height <= big.height) {
                val win = Bitmap.createBitmap(big, x, y, templ.width, templ.height)
                val s = similarity(fingerprint(win), tfp)
                win.recycle()
                if (s > threshold && (best == null || s > best!!.second)) best = android.graphics.Point(x, y) to s
                y += step
            }
            x += step
        }
        return best
    }
}