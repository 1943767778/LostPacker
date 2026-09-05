package com.lostpacker.app.auto

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.lostpacker.app.capture.ScreenCapturer
import com.lostpacker.app.data.RegionConfig
import com.lostpacker.app.data.ItemTemplate
import com.lostpacker.app.prefs.Prefs
import com.lostpacker.app.touch.TouchInjector
import com.lostpacker.app.vision.ImageMatcher

/**
 * 自动整理背包：
 * 1. 截图
 * 2. 在框选区域内按网格切分格子
 * 3. 判断每个格子是否有物品并计算指纹
 * 4. 用相似度把相同物品聚成一组（也可配合上传的模板命名）
 * 5. 依次把同组内多余的物品拖到该组第一个格子上合并
 */
class AutoOrganizer(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onFinished: (Boolean, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var stop = false

    fun stop() { stop = true }

    fun start() {
        val region = RegionConfig.parse(Prefs.region())
        if (region == null) {
            finish(false, "尚未框选识别区域")
            return
        }
        val repo = com.lostpacker.app.dev.TemplateRepository(context)
        val selected = Prefs.activeTemplateIds()
        val templates = (repo.list("user") + repo.list("dev")).filter { it.label in selected }
        log(if (templates.isEmpty())
            "当前未勾选任何模板，将仅按相似度归类相同物品"
        else
            "本次使用 ${templates.size} 个模板：${templates.joinToString(",") { it.label }}")

        Thread {
            try {
                status("正在截图…")
                val screen = ScreenCapturer.capture()
                if (screen == null) { finish(false, "截图失败：请检查 Shizuku 权限"); return@Thread }

                status("正在识别格子…")
                val cells = buildCells(screen, region)
                val groups = group(cells, templates)
                log("识别到 ${cells.count { it.exists }} 个物品，共 ${groups.size} 组可合并")

                var moved = 0
                groups.forEach { g ->
                    if (stop) { finish(false, "已手动停止"); return@Thread }
                    // 从第2个开始拖到第一个
                    for (i in 1 until g.size) {
                        if (stop) { finish(false, "已手动停止"); return@Thread }
                        val src = g[i]
                        val dst = g[0]
                        status("移动 ${indexToLabel(src)} → ${indexToLabel(dst)}")
                        Thread.sleep(Prefs.stepDelayMs())
                        TouchInjector.drag(src.centerX, src.centerY, dst.centerX, dst.centerY, 480)
                        moved++
                        log("拖动: (${src.centerX},${src.centerY}) -> (${dst.centerX},${dst.centerY})")
                    }
                }
                finish(true, "整理完成，共执行 ${moved} 次拖动")
            } catch (e: Exception) {
                finish(false, "出错: ${e.message}")
            }
        }.start()
    }

    /** 把截图分割成网格格子 */
    private fun buildCells(screen: Bitmap, region: RegionConfig): List<com.lostpacker.app.data.Cell> {
        val r = region.rect
        val cellW = (r.width()) / region.columns
        val cellH = (r.height()) / region.rows
        val cells = ArrayList<com.lostpacker.app.data.Cell>(region.columns * region.rows)
        var idx = 0
        for (row in 0 until region.rows) {
            for (col in 0 until region.columns) {
                val left = r.left + col * cellW
                val top = r.top + row * cellH
                val bmp = Bitmap.createBitmap(screen, left, top, cellW, cellH)
                val coverage = ImageMatcher.coverage(bmp)
                val fp = ImageMatcher.fingerprint(bmp)
                bmp.recycle()
                val exists = coverage >= 0.10f
                cells.add(
                    com.lostpacker.app.data.Cell(
                        index = idx,
                        row = row,
                        col = col,
                        centerX = left + cellW / 2,
                        centerY = top + cellH / 2,
                        exists = exists,
                        fingerprint = fp
                    )
                )
                idx++
            }
        }
        return cells
    }

    /** 按模板 + 相似度把相同物品分组（先按选中模板命名，未命中的再按相似度聚类） */
    private fun group(cells: List<com.lostpacker.app.data.Cell>, templates: List<ItemTemplate>): List<MutableList<com.lostpacker.app.data.Cell>> {
        val threshold = Prefs.mergeThreshold()
        val occupied = cells.filter { it.exists }

        // 1) 命中已知模板：以模板 id 作为分组键
        val named = LinkedHashMap<String, MutableList<com.lostpacker.app.data.Cell>>()
        val unnamed = ArrayList<com.lostpacker.app.data.Cell>()
        for (cell in occupied) {
            var best: Pair<ItemTemplate, Float>? = null
            for (t in templates) {
                if (t.fingerprint.size != ImageMatcher.FP_SIZE * ImageMatcher.FP_SIZE) continue
                val s = ImageMatcher.similarity(cell.fingerprint, t.fingerprint)
                if (s >= threshold && (best == null || s > best!!.second)) best = t to s
            }
            if (best != null) named.getOrPut(best.first.id) { mutableListOf() }.add(cell)
            else unnamed.add(cell)
        }
        val groups = ArrayList<MutableList<com.lostpacker.app.data.Cell>>()
        named.forEach { g ->
            if (g.value.size > 1) groups.add(g.value)
        }

        // 2) 未命中模板的格，按相似度聚类
        val used = BooleanArray(unnamed.size)
        for (i in unnamed.indices) {
            if (used[i]) continue
            val cluster = mutableListOf(unnamed[i])
            used[i] = true
            for (j in i + 1 until unnamed.size) {
                if (used[j]) continue
                if (cluster.any { ImageMatcher.similarity(it.fingerprint, unnamed[j].fingerprint) >= threshold }) {
                    cluster.add(unnamed[j]); used[j] = true
                }
            }
            if (cluster.size > 1) groups.add(cluster)
        }
        return groups
    }

    private fun indexToLabel(cell: com.lostpacker.app.data.Cell): String = "格${cell.index + 1}"

    private fun status(m: String) { handler.post { onStatus(m) } }
    private fun log(m: String) { handler.post { onLog(m) } }
    private fun finish(ok: Boolean, m: String) { handler.post { onLog(if (ok) "✓ $m" else "✗ $m"); onFinished(ok, m) } }
}