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

    /** 图中找图命中结果（原图坐标）：中心点 + 命中窗口尺寸 + 相似度 */
    data class Match(val x: Int, val y: Int, val w: Int, val h: Int, val score: Float)

    /**
     * 图中找图：在大图 [big] 中找小图 [templ]。
     * 多尺度 + 归一化互相关(NCC) 逐窗口扫描：先等比把大图缩到宽<=960 提速，
     * 再对模板尝试多个缩放比例，把命中率提到最高。
     * 匹配用“梯度(边缘)图”而非原始灰度，纯色/按钮面不产生梯度、不会把大片同色背景
     * 误判成同类，带边框的小按钮能精确锁定在其真实位置。
     * 返回原图坐标下的 [Match]（中心点/尺寸/相似度），未命中返回 null。
     */
    fun locateTemplate(big: Bitmap, templ: Bitmap, threshold: Float = 0.6f): Match? {
        // 防御：输入位图可能已被回收，或与其它已回收位图共享内存（例如大图小图用了同一张全图）。
        // 先各自取一块独立拷贝并持有，避免后续 createScaledBitmap 抛 “cannot use a recycled source”。
        var bCopied: Bitmap? = null
        var tCopied: Bitmap? = null
        try {
            if (big.isRecycled || templ.isRecycled) return null
            bCopied = big.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            tCopied = templ.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } catch (e: Exception) {
            return null
        }
        val b = bCopied ?: return null
        val t = tCopied ?: return null

        val s = if (b.width > 960) 960f / b.width else 1f
        val bw = max(1, (b.width * s).toInt()); val bh = max(1, (b.height * s).toInt())
        val bigS = Bitmap.createScaledBitmap(b, bw, bh, true)
        val bg = grad(bigS)
        if (bigS !== b) bigS.recycle()

        val scales = floatArrayOf(0.55f, 0.7f, 0.85f, 1.0f, 1.2f, 1.45f, 1.75f)
        var best: Match? = null
        for (f in scales) {
            val tw = max(6, (t.width * s * f).toInt())
            val th = max(6, (t.height * s * f).toInt())
            if (tw > bw || th > bh) continue
            val tplS = Bitmap.createScaledBitmap(t, tw, th, true)
            val tg = grad(tplS)
            // 关键：当目标尺寸与源相同，createScaledBitmap 会原样返回源(==t)，绝不能在这里回收，
            // 否则下一次迭代再需要 t 时就变成 "recycled source"。
            if (tplS !== t) tplS.recycle()
            val res = scanBest(bg, bw, bh, tg, tw, th) ?: continue
            if (res[2] < threshold) continue
            val cx = ((res[0] + tw / 2) / s).toInt().coerceIn(0, b.width - 1)
            val cy = ((res[1] + th / 2) / s).toInt().coerceIn(0, b.height - 1)
            val w = (tw / s).toInt().coerceIn(1, b.width)
            val h = (th / s).toInt().coerceIn(1, b.height)
            val cand = Match(cx, cy, w, h, res[2].coerceIn(0f, 1f))
            if (best == null || cand.score > best!!.score) best = cand
        }

        // 原分辨率复核：多尺度阶段已把大图降到 <=960，文本细笔画容易因降采样的
        // 1px 缩放/取整错位而掉分。这里拿“原始小图 t + 原始大图 b”在粗定位附近
        // 的小邻域内再算一次真实 NCC，文本能精确命中。
        if (best != null && t.width + 1 < b.width && t.height + 1 < b.height) {
            val cxo = (best.x - t.width / 2).coerceIn(0, b.width - t.width)
            val cyo = (best.y - t.height / 2).coerceIn(0, b.height - t.height)
            val tgO = grad(t)
            val n = t.width * t.height
            val tMean = tgO.sum() / n
            var tVar = 0f; for (v in tgO) { val d = v - tMean; tVar += d * d }
            val tStd = sqrt(tVar)
            if (tStd >= 1e-3f) {
                val bgO = grad(b)
                val rad = 8
                val x0 = max(0, cxo - rad); val x1 = min(b.width - t.width, cxo + rad)
                val y0 = max(0, cyo - rad); val y1 = min(b.height - t.height, cyo + rad)
                var ob = -1f; var ox = cxo; var oy = cyo
                var yy = y0
                while (yy <= y1) {
                    var xx = x0
                    while (xx <= x1) {
                        val v = nccAt(bgO, b.width, xx, yy, tgO, t.width, t.height, n, tMean, tStd)
                        if (v > ob) { ob = v; ox = xx; oy = yy }
                        xx++
                    }
                    yy++
                }
                if (ob >= threshold) {
                    best = Match(ox + t.width / 2, oy + t.height / 2, t.width, t.height, ob)
                } else {
                    best = null
                }
            }
        }

        b.recycle()
        t.recycle()
        return best
    }

    /** 单尺度 NCC。先粗扫定位候选，再在候选附近逐像素细扫，返回 {bestX, bestY, bestNcc}，找不到返回 null */
    private fun scanBest(bg: FloatArray, bw: Int, bh: Int, tg: FloatArray, tw: Int, th: Int): FloatArray? {
        val n = tg.size
        val tMean = tg.sum() / n
        var tVar = 0f; for (v in tg) { val d = v - tMean; tVar += d * d }
        val tStd = sqrt(tVar)
        if (tStd < 1e-3f) return null   // 模板本身没有明暗变化，无法可靠匹配

        // 粗扫：用较大步长快速定位
        val step = max(2, tw / 8)
        var bestNcc = -1f; var bestX = -1; var bestY = -1
        var by = 0
        while (by + th <= bh) {
            var bx = 0
            while (bx + tw <= bw) {
                val ncc = nccAt(bg, bw, bx, by, tg, tw, th, n, tMean, tStd)
                if (ncc > bestNcc) { bestNcc = ncc; bestX = bx; bestY = by }
                bx += step
            }
            by += step
        }
        if (bestX < 0) return null

        // 细扫：候选位置附近 ±rad 内逐像素精确定位（框选的小图峰值很尖锐，粗扫可能错过）
        val rad = min(step, 12)
        val x0 = max(0, bestX - rad); val x1 = min(bw - tw, bestX + rad)
        val y0 = max(0, bestY - rad); val y1 = min(bh - th, bestY + rad)
        var yy = y0
        while (yy <= y1) {
            var xx = x0
            while (xx <= x1) {
                val ncc = nccAt(bg, bw, xx, yy, tg, tw, th, n, tMean, tStd)
                if (ncc > bestNcc) { bestNcc = ncc; bestX = xx; bestY = yy }
                xx++
            }
            yy++
        }
        return floatArrayOf(bestX.toFloat(), bestY.toFloat(), bestNcc)
    }

    /** 计算窗口 [bx,by] 处模板 [tg] 的归一化互相关（单次扫描同时算窗口均值和方差） */
    private fun nccAt(bg: FloatArray, bw: Int, bx: Int, by: Int, tg: FloatArray, tw: Int, th: Int,
                      n: Int, tMean: Float, tStd: Float): Float {
        var wSum = 0f; var wSumSq = 0f; var cross = 0f
        for (ty in 0 until th) {
            val baseR = (by + ty) * bw + bx
            val bt = ty * tw
            for (tx in 0 until tw) {
                val g = bg[baseR + tx]
                wSum += g; wSumSq += g * g; cross += g * tg[bt + tx]
            }
        }
        val wMean = wSum / n
        val wVar = (wSumSq - n * wMean * wMean).coerceAtLeast(0f)
        val wStd = sqrt(wVar)
        if (wStd < 1e-3f) return 0f
        val num = cross - n * wMean * tMean
        return num / (tStd * wStd)
    }

    /** 梯度(边缘)强度图：中央差分取梯度模长。纯色区域≈0，边框/纹理处才有值，NCC 时更抗漂移 */
    private fun grad(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val bl = c and 0xFF
            lum[i] = (0.299f * r + 0.587f * g + 0.114f * bl) / 255f
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val ym = if (y > 0) y - 1 else y
            val yp = if (y < h - 1) y + 1 else y
            val baseL = y * w
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else x
                val xp = if (x < w - 1) x + 1 else x
                val dx = lum[baseL + xp] - lum[baseL + xm]
                val dy = lum[yp * w + x] - lum[ym * w + x]
                out[baseL + x] = sqrt(dx * dx + dy * dy)
            }
        }
        return out
    }
}