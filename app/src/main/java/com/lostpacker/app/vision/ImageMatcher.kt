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
     * 图中找图：在大图 [big] 中找小图 [templ]。
     * 用归一化互相关(NCC)逐窗口扫描（等比缩小加速、步进逐格），
     * 要求两张图为同一比例（同一次游戏截图里框出时效果最好）。
     * 返回大图坐标下模板中心点 + 相似度(0..1)，越高越像。
     */
    fun locateTemplate(big: Bitmap, templ: Bitmap, threshold: Float = 0.6f): Pair<android.graphics.Point, Float>? {
        if (big.width < templ.width || big.height < templ.height) return null
        val s = if (big.width > 960) 960f / big.width else 1f
        val bw = max(1, (big.width * s).toInt()); val bh = max(1, (big.height * s).toInt())
        val tw = max(1, (templ.width * s).toInt()); val th = max(1, (templ.height * s).toInt())
        if (tw < 6 || th < 6 || bw < tw || bh < th) return null
        val bigS = Bitmap.createScaledBitmap(big, bw, bh, true)
        val tplS = Bitmap.createScaledBitmap(templ, tw, th, true)
        val bg = gray(bigS); bigS.recycle()
        val tg = gray(tplS); tplS.recycle()
        val n = tg.size
        val tMean = tg.sum() / n
        var tVar = 0f; for (v in tg) { val d = v - tMean; tVar += d * d }
        val tStd = sqrt(tVar)

        val step = max(2, tw / 4)
        var bestNcc = -1f; var bestX = -1; var bestY = -1
        var by = 0
        while (by + th <= bh) {
            var bx = 0
            while (bx + tw <= bw) {
                var wSum = 0f; var cross = 0f
                for (ty in 0 until th) {
                    val baseR = (by + ty) * bw + bx
                    val bt = ty * tw
                    for (tx in 0 until tw) {
                        val g = bg[baseR + tx]
                        wSum += g; cross += g * tg[bt + tx]
                    }
                }
                val wMean = wSum / n
                val num = cross - n * wMean * tMean
                val ncc = if (tStd > 1e-3f) num / (tStd * estWindowStd(bg, bw, bx, by, tw, th, n)) else 0f
                if (ncc > bestNcc) { bestNcc = ncc; bestX = bx; bestY = by }
                bx += step
            }
            by += step
        }
        if (bestX < 0 || bestNcc < threshold) return null
        val cx = ((bestX + tw / 2) / s).toInt().coerceIn(0, big.width - 1)
        val cy = ((bestY + th / 2) / s).toInt().coerceIn(0, big.height - 1)
        return android.graphics.Point(cx, cy) to bestNcc.coerceIn(0f, 1f)
    }

    /** 窗口标准差：sum((g-wMean)^2)/n 的开方（用 sum(g^2) 计算，需两次扫描窗口） */
    private fun estWindowStd(bg: FloatArray, bw: Int, bx: Int, by: Int, tw: Int, th: Int, n: Int): Float {
        var sum = 0f; var sumsq = 0f
        for (ty in 0 until th) {
            val base = (by + ty) * bw + bx
            for (tx in 0 until tw) { val g = bg[base + tx]; sum += g; sumsq += g * g }
        }
        val mean = sum / n
        val var1 = (sumsq - n * mean * mean).coerceAtLeast(0f)
        return sqrt(var1)
    }

    private fun gray(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val bl = c and 0xFF
            out[i] = (0.299f * r + 0.587f * g + 0.114f * bl) / 255f
        }
        return out
    }
}