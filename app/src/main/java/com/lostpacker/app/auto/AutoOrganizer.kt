package com.lostpacker.app.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.lostpacker.app.capture.ScreenCapturer
import com.lostpacker.app.data.RegionConfig
import com.lostpacker.app.data.ItemTemplate
import com.lostpacker.app.data.Cell
import com.lostpacker.app.dev.CategoriesStore
import com.lostpacker.app.dev.Category
import com.lostpacker.app.dev.TemplateRepository
import com.lostpacker.app.prefs.Prefs
import com.lostpacker.app.touch.TouchInjector
import com.lostpacker.app.vision.ImageMatcher
import com.lostpacker.app.vision.OcrReader

/**
 * 自动整理背包。
 *
 * 通用模式（任何游戏，未配置箱子/未选分类）：
 *   截图→按框选背包区域网格切格→相似度把相同物品聚组→把多余堆叠拖动合并。
 *
 * 箱子分类模式（失控进化等，已框箱子区域 + 选中分类）：
 *   双击把背包里的目标物品移入箱子；双击把箱内非目标物品取回；
 *   通过 OCR 读堆叠数量做“达标/超量”判断，超量时先合并、再按“拆分”+进度条比例拆分、多余取回。
 */
class AutoOrganizer(
    private val context: Context,
    private val game: String,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onFinished: (Boolean, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var stop = false
    private val repo: TemplateRepository = TemplateRepository(context, game)

    fun stop() { stop = true }

    fun start() {
        val region = RegionConfig.parse(Prefs.region(game))
        if (region == null) { finish(false, "尚未框选背包区域（整理页先框选）"); return }

        val boxRegion = RegionConfig.parse(Prefs.boxRegion(game))
        val catName = Prefs.selectedCat(game)
        val cat: Category? = CategoriesStore.load(game).firstOrNull { it.name == catName }
        val lossMode = boxRegion != null && cat != null

        Thread {
            try {
                if (lossMode) {
                    classifyBox(boxRegion!!, cat!!, region)
                } else {
                    genericOrganize(region)
                }
            } catch (e: Exception) {
                finish(false, "出错: ${e.message}")
            }
        }.start()
    }

    // ================== 通用整理 ==================
    private fun genericOrganize(region: RegionConfig) {
        val templates = repo.allTemplates().filter { it.label in Prefs.activeTpl(game) }
        if (templates.isNotEmpty())
            log("本次用 ${templates.size} 个模板：${templates.joinToString(","){it.label}}")
        status("正在截图…")
        val screen = ScreenCapturer.capture() ?: run { finish(false,"截图失败，请检查 Shizuku 权限"); return }
        status("识别背包格子…")
        val cells = buildCells(screen, region)
        val groups = group(cells, templates)
        log("识别到 ${cells.count{it.exists}} 个格子，共 ${groups.size} 组可合并")
        var moved = 0
        groups.forEach { g ->
            if (stop) { finish(false, "已手动停止"); return }
            for (i in 1 until g.size) {
                if (stop) { finish(false, "已手动停止"); return }
                val src = g[i]; val dst = g[0]
                status("合并 ${labelOf(src)} → ${labelOf(dst)}")
                Thread.sleep(Prefs.stepDelayMs())
                TouchInjector.drag(src.centerX, src.centerY, dst.centerX, dst.centerY, 480); moved++
                log("拖动 (${src.centerX},${src.centerY}) → (${dst.centerX},${dst.centerY})")
            }
        }
        finish(true, "整理完成，共执行 ${moved} 次拖动")
    }

    // ================== 失控进化：箱子分类 ==================
    private fun classifyBox(box: RegionConfig, cat: Category, inv: RegionConfig) {
        val templates = repo.allTemplates()
        val keep = cat.items
        log("箱子分类：${box.rect.toShortString()}，分类「${cat.name}」，目标：${keep}")
        val maxRounds = 6

        for (round in 1..maxRounds) {
            if (stop) { finish(false, "已手动停止"); return }
            status("第 $round 轮扫描箱子…")
            val screen = ScreenCapturer.capture() ?: run { finish(false, "截图失败"); return }
            val boxCells = buildCells(screen, box)
            val invCells = buildCells(screen, inv)
            var acted = false

            // 1) 箱内有非目标物品 → 双击取回
            for (c in boxCells) {
                if (stop) { finish(false, "已手动停止"); return }
                if (!c.exists) continue
                val label = matchLabel(c, templates)
                if (label == null || label !in keep) {
                    log("取回：箱内 ${labelOf(c)}（${label ?: "未知"}）非目标 → 背包")
                    TouchInjector.doubleTap(c.centerX, c.centerY); acted = true
                }
            }

            // 2) 每个目标物品：按数量“达标/超量”处理
            for ((label, target) in keep) {
                if (stop) { finish(false, "已手动停止"); return }
                val stacks = boxCells.filter { it.exists && matchLabel(it, templates) == label }
                val total = stacks.sumOf { countOf(it, screen, box) ?: 1 }
                if (total < target) {
                    // 达标：从背包双击移入，直到总数达标
                    var got = total
                    val invStacks = invCells.filter { it.exists && matchLabel(it, templates) == label }
                    for (s in invStacks) {
                        if (got >= target) break
                        if (stop) { finish(false, "已手动停止"); return }
                        val c = countOf(s, screen, inv) ?: 1
                        log("移入：背包 ${labelOf(s)}($c) 双击 → 箱子")
                        TouchInjector.doubleTap(s.centerX, s.centerY); got += c; acted = true
                    }
                    if (got < target) log("⚠ $label 仍不足目标（$got<$target）")
                } else if (total > target) {
                    reduceBoxExcess(label, target, stacks, screen, box); acted = true
                }
            }

            if (!acted) { log("第 $round 轮无操作，分类完成"); break }
            if (round == maxRounds) log("已达最大轮次，请人工检查")
        }
        finish(true, "箱子分类流程结束")
    }

    /** 超量：先合并同种堆栈，再按“拆分”进度条比例拆分到目标数，多余取回。 */
    private fun reduceBoxExcess(label: String, target: Int, stacks: List<Cell>, screen: Bitmap, box: RegionConfig) {
        if (stacks.isEmpty()) return
        // 尝试把同种堆栈拖到第一个上合并
        val first = stacks.first()
        for (i in 1 until stacks.size) {
            if (stop) return
            log("合并：箱子 ${labelOf(first)} + ${labelOf(stacks[i])}")
            TouchInjector.drag(stacks[i].centerX, stacks[i].centerY, first.centerX, first.centerY, 480)
            Thread.sleep(Prefs.stepDelayMs())
        }
        Thread.sleep(Prefs.stepDelayMs())
        val single = countOf(first, screen, box) ?: 1
        val split = RegionConfig.parse(Prefs.splitRegion(game))
        if (split != null) {
            // 点选该堆栈 → 全屏找“拆分”按钮 → 点击 → 在进度条比例位置点一下
            Touchtap(first.centerX, first.centerY)
            Thread.sleep(350)
            val fresh = ScreenCapturer.capture() ?: run { log("⚠ 拆分前重截图失败"); return }
            val p = OcrReader.findText(fresh, "拆分")
            if (p != null) {
                log("点击“拆分”（${p.x},${p.y}）")
                TouchInjector.tap(p.x, p.y)
                Thread.sleep(450)
                val frac = (target.toFloat() / single).coerceIn(0.02f, 0.98f)
                val x = split.rect.left + (split.rect.width() * frac).toInt()
                val y = split.rect.centerY()
                log("拆分 ${label}：$target/$single，进度条比例 ${"%.0f".format(frac * 100)}%")
                TouchInjector.tap(x.coerceIn(split.rect.left, split.rect.right), y)
                Thread.sleep(400)
                // 拆分后的多余部分会另成堆栈，交给下一轮重新扫描取回
            } else {
                log("⚠ 未找到“拆分”按钮，跳过拆分")
            }
        } else {
            log("⚠ 未框选拆分进度条区域，无法拆分；请框选后重试")
        }
    }

    // ================== 网格 / 匹配 ==================
    private fun buildCells(screen: Bitmap, region: RegionConfig): List<Cell> {
        val r = region.rect
        val cellW = r.width() / region.columns
        val cellH = r.height() / region.rows
        val out = ArrayList<Cell>(region.columns * region.rows)
        var idx = 0
        for (row in 0 until region.rows) for (col in 0 until region.columns) {
            val left = r.left + col * cellW; val top = r.top + row * cellH
            val bmp = Bitmap.createBitmap(screen, left, top, cellW, cellH)
            val exists = ImageMatcher.coverage(bmp) >= 0.10f
            val fp = ImageMatcher.fingerprint(bmp)
            bmp.recycle()
            out.add(Cell(idx++, row, col, left + cellW/2, top + cellH/2, exists, fp))
        }
        return out
    }

    private fun cellRect(c: Cell, region: RegionConfig): Rect {
        val r = region.rect
        val cellW = r.width() / region.columns
        val cellH = r.height() / region.rows
        return Rect(r.left + c.col*cellW, r.top + c.row*cellH, r.left + (c.col+1)*cellW, r.top + (c.row+1)*cellH)
    }

    /** 读取格子右下角堆叠数量（图标右下区域 OCR）。 */
    private fun countOf(c: Cell, screen: Bitmap, region: RegionConfig): Int? {
        val r = cellRect(c, region)
        // 数量通常压在图标右下方，取右下半区域，避让图标本体
        val w = r.width(); val h = r.height()
        val rect = Rect(r.left + (w * 0.42).toInt(), r.top + (h * 0.42).toInt(), r.right, r.bottom)
        val n = OcrReader.readCount(screen, rect)
        return if (n != null && n in 1..9999) n else null
    }

    private fun matchLabel(c: Cell, templates: List<ItemTemplate>): String? {
        var best: Pair<ItemTemplate, Float>? = null
        for (t in templates) {
            if (t.fingerprint.size != ImageMatcher.FP_SIZE * ImageMatcher.FP_SIZE) continue
            val s = ImageMatcher.similarity(c.fingerprint, t.fingerprint)
            if (s >= Prefs.mergeThreshold() && (best == null || s > best!!.second)) best = t to s
        }
        return best?.first?.label
    }

    /** 通用合并：先按模板命名分组，未命中的按相似度聚类。 */
    private fun group(cells: List<Cell>, templates: List<ItemTemplate>): List<MutableList<Cell>> {
        val threshold = Prefs.mergeThreshold()
        val occupied = cells.filter { it.exists }
        val named = LinkedHashMap<String, MutableList<Cell>>()
        val unnamed = ArrayList<Cell>()
        for (c in occupied) {
            var best: Pair<ItemTemplate, Float>? = null
            for (t in templates) {
                if (t.fingerprint.size != ImageMatcher.FP_SIZE * ImageMatcher.FP_SIZE) continue
                val s = ImageMatcher.similarity(c.fingerprint, t.fingerprint)
                if (s >= threshold && (best == null || s > best!!.second)) best = t to s
            }
            if (best != null) named.getOrPut(best.first.id) { mutableListOf() }.add(c)
            else unnamed.add(c)
        }
        val groups = ArrayList<MutableList<Cell>>()
        named.forEach { if (it.value.size > 1) groups.add(it.value) }
        val used = BooleanArray(unnamed.size)
        for (i in unnamed.indices) {
            if (used[i]) continue
            val cluster = mutableListOf(unnamed[i]); used[i] = true
            for (j in i+1 until unnamed.size) if (!used[j] && cluster.any { ImageMatcher.similarity(it.fingerprint, unnamed[j].fingerprint) >= threshold }) {
                cluster.add(unnamed[j]); used[j] = true
            }
            if (cluster.size > 1) groups.add(cluster)
        }
        return groups
    }

    private fun labelOf(c: Cell) = "格${c.index + 1}"
    private fun Touchtap(x: Int, y: Int) { TouchInjector.tap(x, y) }

    private fun status(m: String) { handler.post { onStatus(m) } }
    private fun log(m: String) { handler.post { onLog(m) } }
    private fun finish(ok: Boolean, m: String) { handler.post { onLog(if (ok) "✓ $m" else "✗ $m"); onFinished(ok, m) } }
}

private fun Rect.toShortString() = "($left,$top)-($right,$bottom)"