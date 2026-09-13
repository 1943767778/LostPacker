package com.lostpacker.app.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
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
 * 自动整理背包 + 自动装箱。
 *
 * 通用模式（未框箱子区域）：截图→按背包网格切格→相似度聚组→把多余堆叠拖动合并。
 *
 * 装箱模式（已框箱子区域）：
 *   1) 先整理背包（合并同种堆叠），并把识别到的目标用绿色框标注出来；
 *   2) 扫描箱子确定目标分类：
 *        - 优先看“箱子命名区”OCR 文字，若包含某个分类名 → 用该分类；
 *        - 否则按箱子内不同物品所属分类出现次数最多的那个分类；
 *   3) 把该分类下的所有物品从背包双击移入箱子（自动从上往下/下往上滚动背包以遍历全部）。
 */
class AutoOrganizer(
    private val context: Context,
    private val game: String,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onTargetBoxes: (List<Rect>?) -> Unit,
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
        val hasBox = boxRegion != null
        clearTargetBoxes()

        Thread {
            try {
                if (hasBox) fullFlow(region, boxRegion!!)
                else genericOrganize(region)
            } catch (e: Exception) {
                clearTargetBoxes()
                finish(false, "出错: ${e.message}")
            }
        }.start()
    }

    // ================== 通用整理 ==================
    private fun genericOrganize(region: RegionConfig) {
        val templates = repo.allTemplates().filter { it.label in Prefs.activeTpl(game) }
        val moved = mergeBackpack(region, templates)
        finish(true, "整理完成，共执行 $moved 次拖动")
    }

    // ================== 装箱流程 ==================
    private fun fullFlow(inv: RegionConfig, box: RegionConfig) {
        val activeTemplates = repo.allTemplates().filter { it.label in Prefs.activeTpl(game) }
        val allTemplates = repo.allTemplates()
        status("第 1 步：整理背包（合并堆叠）…")
        val moved = mergeBackpack(inv, activeTemplates)
        status("第 2 步：扫描箱子确定分类…")
        val target = resolveCategory(inv, box, allTemplates)
        if (target == null) { clearTargetBoxes(); finish(true, "背包整理完成（$moved 次拖动），但无法确定箱子分类，未装箱"); return }
        log("箱子分类判定为「$target」")
        moveCategoryToBox(inv, box, allTemplates, target)
        clearTargetBoxes()
        finish(true, "整理+装箱完成：背包 $moved 次合并，箱子分类「$target」")
    }

    /** 整理背包：把识别到的同种堆叠合并，并把命中的格子用绿框标注出来。 */
    private fun mergeBackpack(region: RegionConfig, templates: List<ItemTemplate>): Int {
        if (templates.isNotEmpty())
            log("本次用 ${templates.size} 个模板：${templates.joinToString(","){it.label}}")
        status("正在截图…")
        val screen = ScreenCapturer.capture() ?: run { finish(false,"截图失败，请检查 Shizuku 权限"); return 0 }
        status("识别背包格子…")
        val cells = buildCells(screen, region)
        val groups = group(cells, templates)
        log("识别到 ${cells.count{it.exists}} 个格子，共 ${groups.size} 组可合并")

        // 标注识别出的目标格子
        val targetCells = cells.filter { it.exists && matchLabel(it, templates) != null }
        if (targetCells.isNotEmpty()) showTargetBoxes(targetCells.map { cellRect(it, region) })

        var moved = 0
        val locateBudget = System.currentTimeMillis() + 4000L
        groups.forEach { g ->
            if (stop) { clearTargetBoxes(); finish(false, "已手动停止"); return moved }
            val dst = g[0]
            var dx = dst.centerX; var dy = dst.centerY
            val label = matchLabel(dst, templates)
            val tpl = label?.let { l -> templates.firstOrNull { it.label == l } }
            if (tpl?.file != null && System.currentTimeMillis() < locateBudget) {
                val p = locateInBackpack(screen, tpl, region)
                if (p != null) { dx = p.x; dy = p.y }
            }
            for (i in 1 until g.size) {
                if (stop) { clearTargetBoxes(); finish(false, "已手动停止"); return moved }
                val src = g[i]
                status("合并 ${labelOf(src)} → ${labelOf(dst)}")
                Thread.sleep(Prefs.stepDelayMs())
                TouchInjector.drag(src.centerX, src.centerY, dx, dy, 480); moved++
                log("拖动 (${src.centerX},${src.centerY}) → ($dx,$dy)")
            }
        }
        return moved
    }

    /** 确定箱子对应的目标分类：优先命名区 OCR，其次按箱内不同物品所属分类票数最多。 */
    private fun resolveCategory(inv: RegionConfig, box: RegionConfig, templates: List<ItemTemplate>): String? {
        val cats = CategoriesStore.load(game)
        if (cats.isEmpty()) { log("尚未创建任何分类，无法装箱"); return null }
        val screen = ScreenCapturer.capture() ?: run { finish(false, "截图失败"); return null }

        // 1) 命名区 OCR：文字里含分类名则直接采用
        val naming = Prefs.namingRegion(game)?.let { RegionConfig.parse(it)?.rect }
        if (naming != null) {
            val text = OcrReader.readText(screen, naming).trim()
            if (text.isNotEmpty()) {
                for (c in cats) if (text.contains(c.name)) {
                    log("箱子命名区识别到「$text」，包含分类「${c.name}」")
                    return c.name
                }
                log("命名区文字「$text」未匹配到任何分类名，改用箱内物品票选")
            }
        }

        // 2) 票选：箱内每个被识别的不同物品，给其所属分类 +1（同种物品无论多少组只算一次）
        val boxCells = buildCells(screen, box)
        val votes = LinkedHashMap<String, Int>()
        val seen = HashSet<String>()
        for (c in boxCells) {
            if (stop) return null
            if (!c.exists) continue
            val label = matchLabel(c, templates) ?: continue
            if (label in seen) continue
            seen.add(label)
            for (cat in cats) if (label in cat.items) votes[cat.name] = (votes[cat.name] ?: 0) + 1
        }
        if (votes.isEmpty()) { log("箱子内没有识别出属于已知分类的物品，无法确定分类"); return null }
        log("箱内物品票选分类：$votes")
        return votes.maxByOrNull { it.value }?.key
    }

    /** 把 [targetCat] 分类下的所有物品从背包双击移入箱子，并自动滚动背包遍历全部。 */
    private fun moveCategoryToBox(inv: RegionConfig, box: RegionConfig, templates: List<ItemTemplate>, targetCat: String) {
        val cat = CategoriesStore.load(game).firstOrNull { it.name == targetCat } ?: run { log("分类「$targetCat」不存在"); return }
        val targetLabels = cat.items.keys.toSet()
        val maxPasses = 5
        var totalMoved = 0
        for (pass in 0 until maxPasses) {
            if (stop) return
            status("装箱第 ${pass + 1} 遍，扫描背包…")
            val screen = ScreenCapturer.capture() ?: run { log("装箱重截图失败"); return }
            val invCells = buildCells(screen, inv)
            var found = 0
            for (c in invCells) {
                if (stop) return
                if (!c.exists) continue
                val label = matchLabel(c, templates) ?: continue
                if (label in targetLabels) {
                    log("装箱：背包 $label → 箱子")
                    TouchInjector.doubleTap(c.centerX, c.centerY)
                    Thread.sleep(Prefs.stepDelayMs())
                    found++; totalMoved++
                }
            }
            if (found == 0) { log("当前视野无「$targetCat」分类物品"); break }
            if (pass == maxPasses - 1) break
            scrollBackpack(inv, down = true)
        }
        // 滚回顶部，尽量恢复原状
        scrollBackpack(inv, down = false, times = 2)
        log("装箱完成，共双击 $totalMoved 次")
    }

    /** 滚动背包：down=true 向下翻（看到下面的物品），否则向上翻回。 */
    private fun scrollBackpack(inv: RegionConfig, down: Boolean, times: Int = 1) {
        val r = inv.rect
        val cx = r.centerX(); val cy = r.centerY()
        val off = (r.height() / 3).coerceAtLeast(80)
        repeat(times) {
            if (stop) return
            val fromY = if (down) cy + off else cy - off
            val toY = if (down) cy - off else cy + off
            TouchInjector.drag(cx, fromY, cx, toY, 400)
            Thread.sleep(500)
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

    /** 用区域限定的原分辨率模板匹配，在背包区域 [region] 内定位模板小图中心（<5s）。 */
    private fun locateInBackpack(screen: Bitmap, tpl: ItemTemplate, region: RegionConfig): Point? {
        if (tpl.file == null) return null
        val bmp = try { BitmapFactory.decodeFile(tpl.file.absolutePath) } catch (e: Exception) { null } ?: return null
        val hit = ImageMatcher.locateInRegion(screen, bmp, region.rect, Prefs.mergeThreshold())
        bmp.recycle()
        return if (hit != null) Point(hit.x, hit.y) else null
    }

    private fun showTargetBoxes(rects: List<Rect>) { handler.post { onTargetBoxes(rects) } }
    private fun clearTargetBoxes() { handler.post { onTargetBoxes(null) } }

    private fun status(m: String) { handler.post { onStatus(m) } }
    private fun log(m: String) { handler.post { onLog(m) } }
    private fun finish(ok: Boolean, m: String) { handler.post { onLog(if (ok) "✓ $m" else "✗ $m"); onFinished(ok, m) } }
}

private fun Rect.toShortString() = "($left,$top)-($right,$bottom)"
