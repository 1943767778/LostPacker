package com.lostpacker.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.lostpacker.app.PickerActivity
import com.lostpacker.app.R
import com.lostpacker.app.auto.AutoOrganizer
import com.lostpacker.app.capture.ScreenCapturer
import com.lostpacker.app.data.RegionConfig
import com.lostpacker.app.data.SnapshotHolder
import com.lostpacker.app.dev.CategoriesStore
import com.lostpacker.app.dev.Category
import com.lostpacker.app.dev.GamesRepository
import com.lostpacker.app.dev.TemplateRepository
import com.lostpacker.app.prefs.Prefs
import com.lostpacker.app.shizuku.ShizukuSupport
import com.lostpacker.app.ui.ThemeConfig

class FloatWindowService : Service() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelView: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var selector: View? = null
    private var cropRoot: View? = null
    private var dialog: View? = null
    private var organizer: AutoOrganizer? = null

    private var gamesRepo: GamesRepository? = null
    private var currentTab = 0
    private var organizing = false

    private var statusTv: TextView? = null
    private var permTv: TextView? = null
    private var logTv: TextView? = null
    private var tabContent: LinearLayout? = null
    private var organizeBtn: TextView? = null

    // 持久化：日志/状态跨 Tab 重建不丢
    private val logBuffer = StringBuilder()
    private var lastStatus = "就绪"
    private var gameDropdownOpen = false

    // 框选/截图模式
    private enum class CropMode { SAVE_TEMPLATE, SET_BACKPACK, SET_BOX, SET_SPLIT, PICK_IMG }
    private var cropMode = CropMode.SAVE_TEMPLATE
    private var cropSlot = ""          // PICK_IMG 目标：usr/dev/A/B/BIG/TPL
    private var pickPoller: Runnable? = null

    // 工具页：相似度对比 A/B、图中找图 big/tpl
    private var imgA: Bitmap? = null
    private var imgB: Bitmap? = null
    private var imgBig: Bitmap? = null
    private var imgTpl: Bitmap? = null

    private var dragDX = 0f
    private var dragDY = 0f
    private var dialogInput: EditText? = null
    private var ticker: Runnable? = null

    private val currentGame get() = Prefs.currentGame()
    private fun repo() = TemplateRepository(this, currentGame)

    private val TAB_LABELS = arrayOf("主页", "整理", "模板", "工具", "设置")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        gamesRepo = GamesRepository(this)
        gamesRepo?.ensure(Prefs.DEFAULT_GAME)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        seedUsageLog()
        startForegroundCompat()
        showBubble()
        startTicker()
    }

    override fun onDestroy() {
        organizer?.stop()
        ticker?.let { handler.removeCallbacks(it) }
        pickPoller?.let { handler.removeCallbacks(it) }
        removeView(bubble); bubbleParams = null
        removeView(panelView); panelParams = null
        removeView(selector); selector = null
        removeView(cropRoot); cropRoot = null
        removeView(dialog); dialog = null
        SnapshotHolder.release()
        repo().clearScreenshotCache()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val channelId = "lostpacker_float"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channelId, "自动理包器悬浮窗", NotificationManager.IMPORTANCE_LOW))
            startForeground(1, Notification.Builder(this, channelId)
                .setContentTitle("自动理包器运行中")
                .setSmallIcon(R.drawable.ic_launcher).build())
        }
    }

    private val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

    // ============ 圆点气泡 ============
    private fun showBubble() {
        val p = ThemeConfig.pal()
        val dot = TextView(this).apply {
            text = "理"; setTextColor(p.onAccent); textSize = 16f; gravity = Gravity.CENTER
            background = ThemeConfig.rounded(p.accent, 22)
            setOnClickListener { expandPanel() }
            setOnTouchListener(::moveBubble)
        }
        val lp = WindowManager.LayoutParams(
            ThemeConfig.dp(44).toInt(), ThemeConfig.dp(44).toInt(), overlayType,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = bubbleX(); y = bubbleY() }
        wm.addView(dot, lp)
        bubble = dot; bubbleParams = lp
    }

    // ============ 主面板 ============
    private fun expandPanel() { removeView(bubble); bubbleParams = null; buildPanel(); wm.addView(panelView, panelParams) }
    private fun collapsePanel() { removeView(panelView); panelParams = null; showBubble() }

    private fun buildPanel() {
        val p = ThemeConfig.pal()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeConfig.rounded(p.bgPanel, 20)
            setPadding(ThemeConfig.dp(6).toInt(), 0, ThemeConfig.dp(6).toInt(), ThemeConfig.dp(6).toInt())
        }
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ThemeConfig.dp(12).toInt(), ThemeConfig.dp(10).toInt(), ThemeConfig.dp(8).toInt(), ThemeConfig.dp(10).toInt())
            setOnTouchListener(::movePanel)
        }
        titleBar.addView(TextView(this).apply {
            text = "自动理包器 · $currentGame"; setTextColor(p.textMain); textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(TextView(this).apply {
            text = "—"; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ThemeConfig.dp(30).toInt(), ThemeConfig.dp(30).toInt())
            background = ThemeConfig.rounded(p.closeBg, 15)
            setOnClickListener { collapsePanel() }
        })
        root.addView(titleBar)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ThemeConfig.dp(2).toInt(), 0, 0) }
        body.addView(buildNav())
        val content = ScrollView(this).apply {
            setBackground(ThemeConfig.rounded(p.bgCard, 14))
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(ThemeConfig.dp(6).toInt(), ThemeConfig.dp(4).toInt(), ThemeConfig.dp(6).toInt(), ThemeConfig.dp(8).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val contentLv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(contentLv)
        body.addView(content)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabContent = contentLv
        panelView = root
        selectTab(currentTab)

        panelParams = WindowManager.LayoutParams(
            ThemeConfig.dp(312).toInt(), ThemeConfig.dp(360).toInt(), overlayType,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = prefPanelX(); y = prefPanelY() }
    }

    // ============ 导航 ============
    private fun buildNav(): LinearLayout {
        val p = ThemeConfig.pal()
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ThemeConfig.dp(4).toInt(), 0, ThemeConfig.dp(4).toInt())
            layoutParams = LinearLayout.LayoutParams(ThemeConfig.dp(54).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        TAB_LABELS.forEachIndexed { i, label -> nav.addView(makeNavTab(i, label, i == currentTab, p)) }
        return nav
    }
    private fun makeNavTab(index: Int, label: String, selected: Boolean, p: ThemeConfig.Palette): TextView =
        TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = 11f
            typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else null
            setTextColor(if (selected) p.accent else p.navText)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeConfig.dp(40).toInt())
            setOnClickListener { selectTab(index) }
            if (selected) background = ThemeConfig.rounded(p.navSel, 6)
        }

    private fun selectTab(index: Int) {
        currentTab = index
        rebuildNav()
        tabContent?.run {
            removeAllViews()
            when (index) {
                0 -> tabHome(this)
                1 -> tabOrganize(this)
                2 -> tabTemplates(this)
                3 -> tabTools(this)
                4 -> tabSettings(this)
            }
        }
    }

    private fun rebuildNav() {
        val root = panelView ?: return
        val body = (root.getChildAt(1) as? LinearLayout) ?: return
        if (body.childCount > 0) { body.removeViewAt(0); body.addView(buildNav(), 0) }
    }

    // ============ 通用小组件 ============
    private fun pillTitle(l: LinearLayout, t: String) { l.addView(TextView(this).apply { text = t; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(ThemeConfig.pal().textMain); setPadding(0, ThemeConfig.dp(6).toInt(), 0, ThemeConfig.dp(2).toInt()) }) }
    private fun pillSub(l: LinearLayout, t: String) { l.addView(TextView(this).apply { text = t; textSize = 12f; setTextColor(ThemeConfig.pal().textSub); setPadding(0, 0, 0, ThemeConfig.dp(4).toInt()) }) }

    private fun makeBtn(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val p = ThemeConfig.pal()
        return TextView(this).apply {
            this.text = text; textSize = 12f; gravity = Gravity.CENTER; isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeConfig.dp(38).toInt())
            (layoutParams as ViewGroup.MarginLayoutParams).topMargin = ThemeConfig.dp(6).toInt()
            setOnClickListener { onClick() }
            if (primary) { setTextColor(p.onAccent); background = ThemeConfig.rounded(p.accent, 8) }
            else { setTextColor(p.textSub); background = ThemeConfig.stroked(p.textSub, 8, 1) }
        }
    }

    private fun makeMiniBtn(text: String, onClick: () -> Unit): TextView {
        val p = ThemeConfig.pal()
        return TextView(this).apply {
            this.text = text; textSize = 11f; gravity = Gravity.CENTER; isClickable = true
            layoutParams = LinearLayout.LayoutParams(ThemeConfig.dp(60).toInt(), ThemeConfig.dp(28).toInt())
            setTextColor(p.accent); background = ThemeConfig.stroked(p.accent, 8, 1)
            setOnClickListener { onClick() }
        }
    }

    private fun makeInput(def: String, hint: String, numeric: Boolean = false): EditText {
        val p = ThemeConfig.pal()
        return EditText(this).apply {
            setText(def); this.hint = hint
            setTextColor(p.textMain); setHintTextColor(p.textSub)
            setBackground(ThemeConfig.rounded(p.bgCard, 8))
            setPadding(ThemeConfig.dp(10).toInt(), ThemeConfig.dp(4).toInt(), ThemeConfig.dp(10).toInt(), ThemeConfig.dp(4).toInt())
            textSize = 13f
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setOnFocusChangeListener { _, focused -> setPanelFocusable(focused) }
        }
    }

    private fun setPanelFocusable(focusable: Boolean) {
        val p = panelParams ?: return
        p.flags = if (focusable) (p.flags and FLAG_NOT_FOCUSABLE.inv())
        else (p.flags or FLAG_NOT_FOCUSABLE)
        try { wm.updateViewLayout(panelView, p) } catch (e: Exception) {}
    }

    private fun makeRow(vararg texts: String): TextView = TextView(this).apply {
        text = texts.joinToString(" "); textSize = 13f; setTextColor(ThemeConfig.pal().textMain)
    }

    /** 内层可独立滚动容器（保证拖动时滚动日志区域而非整页） */
    private fun makeLogScroll(child: View?): ScrollView {
        val p = ThemeConfig.pal()
        val sv = ScrollView(this).apply {
            setBackground(ThemeConfig.rounded(p.bgCard, 8))
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeConfig.dp(128).toInt())
            // 阻断父容器拦截，让内层滚动优先
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_MOVE) parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        if (child != null) sv.addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sv
    }

    private fun makeTextRow(text: String, size: Float = 13f, color: Int = ThemeConfig.pal().textMain, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else null
        }

    // ============ 主页：游戏管理 + 信息 + 日志 ============
    private fun tabHome(l: LinearLayout) {
        val gr = gamesRepo ?: GamesRepository(this)
        val games = gr.list()
        val game = currentGame

        pillTitle(l, "🎮 选择游戏")
        // 下拉框
        val dropdown = makeBtn("当前游戏：$game  ▾", true) { gameDropdownOpen = !gameDropdownOpen; selectTab(0) }
        l.addView(dropdown)
        if (gameDropdownOpen) {
            games.forEach { name ->
                val sel = name == game
                val row = TextView(this).apply {
                    text = (if (sel) "● " else "○ ") + name
                    textSize = 12f; gravity = Gravity.CENTER_VERTICAL
                    setPadding(ThemeConfig.dp(8).toInt(), ThemeConfig.dp(6).toInt(), ThemeConfig.dp(8).toInt(), ThemeConfig.dp(6).toInt())
                    setTextColor(if (sel) ThemeConfig.pal().accent else ThemeConfig.pal().textMain)
                    background = ThemeConfig.rounded(if (sel) ThemeConfig.pal().navSel else ThemeConfig.pal().bgCard, 8)
                    setOnClickListener {
                        if (name != game) {
                            Prefs.setCurrentGame(name); gr.ensure(name)
                            appendLog("切换到游戏：$name")
                            gameDropdownOpen = false
                            rebuildPanelKeep(); selectTab(0)
                        }
                    }
                }
                row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ThemeConfig.dp(3).toInt() }
                l.addView(row)
            }
        }
        // 管理按钮
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(makeMiniBtn("添加", { gameNameDialog("添加游戏", null) }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(makeMiniBtn("重命名", { gameNameDialog("重命名游戏", game) }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(makeMiniBtn("复制", { gameCopyDialog(game) }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(makeMiniBtn("删除", { gameDeleteConfirm(game) }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        l.addView(row)

        pillTitle(l, "ℹ 运行信息")
        statusTv = makeTextRow(lastStatus).also { l.addView(it) }
        permTv = TextView(this).apply { textSize = 12f; setTextColor(ThemeConfig.pal().textSub) }.also { l.addView(it) }
        updatePermLine()

        pillTitle(l, "📋 运行日志")
        logTv = TextView(this).apply {
            textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ThemeConfig.pal().textSub)
            text = logBuffer.toString()
        }
        l.addView(makeLogScroll(logTv))
    }

    private fun updatePermLine() {
        permTv?.text = "Shizuku: ${if (ShizukuSupport.isAvailable()) "已运行" else "未运行"} · 权限: ${if (ShizukuSupport.isGranted()) "已授予" else "未授予"}"
    }

    private fun gameNameDialog(title: String, initial: String?) {
        showInputDialog(title, "游戏名称（不能与现有重复）") { name ->
            val n = name.trim()
            if (n.isEmpty()) { setStatus("名称不能为空"); return@showInputDialog }
            when (title) {
                "添加游戏" -> gamesRepo?.add(n)?.let { setStatus("已添加：$it") } ?: setStatus("添加失败（可能重名）")
                "重命名游戏" -> if (gamesRepo?.rename(initial ?: currentGame, n) == true) setStatus("已重命名为：$n") else setStatus("重命名失败（默认游戏不可改）")
            }
            rebuildPanelKeep(); selectTab(0)
        }
    }

    private fun gameCopyDialog(from: String) {
        showInputDialog("复制游戏", "新游戏名称") { name ->
            val n = name.trim()
            val ok = if (from == currentGame) gamesRepo?.copy(from, n) else gamesRepo?.copy(from, n)
            if (ok != null) { setStatus("已复制为：$ok"); Prefs.setCurrentGame(ok); rebuildPanelKeep(); selectTab(0) }
            else setStatus("复制失败（可能重名）")
        }
    }

    private fun gameDeleteConfirm(game: String) {
        if (game == Prefs.DEFAULT_GAME) { setStatus("默认游戏「失控进化」不可删除"); return }
        showConfirmDialog("删除游戏", "确定删除「$game」？（其模板与设置将一并删除）") {
            if (gamesRepo?.remove(game) == true) { setStatus("已删除：$game"); rebuildPanelKeep(); selectTab(0) }
            else setStatus("删除失败")
        }
    }

    private fun seedUsageLog() {
        if (logBuffer.isNotEmpty()) return
        logBuffer.append("【使用说明】\n")
        logBuffer.append("1. 主页：选/添加/重命名/复制/删除游戏；每个游戏模板、分类、区域互相独立。\n")
        logBuffer.append("2. 整理：勾选目标模板 → 框选背包区域 → 开始整理；失控进化另需框箱子/拆分区域并选分类。\n")
        logBuffer.append("3. 模板：截图或上传图片 → 框选图标保存为用户模板；可建分类并分配目标数量。\n")
        logBuffer.append("4. 工具：相似度对比、图中找图、开发者素材导出。\n\n")
    }

    // ============ 整理页 ============
    private fun tabOrganize(l: LinearLayout) {
        val game = currentGame
        pillTitle(l, "🧹 自动整理")

        // 分类下拉（失控进化等有箱子的游戏，用分类确定箱内保留目标）
        val cats = CategoriesStore.load(game)
        if (cats.isNotEmpty()) {
            val selCat = Prefs.selectedCat(game)
            l.addView(makeBtn("箱子分类：${if (selCat.isEmpty()) "未选择" else selCat}  ▾", false) {
                val next = cats.firstOrNull { it.name != selCat }?.name ?: ""
                Prefs.setSelectedCat(game, next)
                setStatus(if (next.isEmpty()) "未选择分类（仅按模板勾选项整理）" else "箱子按分类「$next」归置")
                selectTab(1)
            })
        } else {
            pillSub(l, "（暂无分类，可在「模板」页创建）")
        }

        // 目标模板勾选（全部模板）
        pillTitle(l, "🎯 勾选要整理的目标模板")
        val allTpls = repo().allTemplates()
        if (allTpls.isEmpty()) {
            pillSub(l, "（暂无模板，先去「模板」页加图标）")
        } else {
            allTpls.forEach { t ->
                val checked = t.label in Prefs.activeTpl(game)
                val p = ThemeConfig.pal()
                val row = TextView(this).apply {
                    text = (if (checked) "☑  " else "☐  ") + t.label
                    textSize = 12f; gravity = Gravity.CENTER_VERTICAL
                    setPadding(ThemeConfig.dp(8).toInt(), ThemeConfig.dp(7).toInt(), ThemeConfig.dp(8).toInt(), ThemeConfig.dp(7).toInt())
                    setTextColor(if (checked) p.accent else p.textMain)
                    typeface = if (checked) android.graphics.Typeface.DEFAULT_BOLD else null
                    background = ThemeConfig.rounded(if (checked) p.navSel else p.bgCard, 8)
                    setOnClickListener {
                        val cur = Prefs.activeTpl(game)
                        if (t.label in cur) cur.remove(t.label) else cur.add(t.label)
                        Prefs.setActiveTpl(game, cur)
                        selectTab(1)
                    }
                }
                row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ThemeConfig.dp(3).toInt() }
                l.addView(row)
            }
        }

        // 开始/停止
        organizeBtn = makeBtn(if (organizing) "⏹ 停止整理" else "🚀 开始整理", true) { toggleOrganize() }.also { l.addView(it) }

        // 区域框选
        pillTitle(l, "🖼 区域框选")
        val region = RegionConfig.parse(Prefs.region(game))
        pillSub(l, "背包区域：${region?.let { "${it.rect.left},${it.rect.top} ${it.columns}x${it.rows}格" } ?: "未框选"} · 网格 ${Prefs.cols(game)}x${Prefs.rows(game)}")
        l.addView(makeBtn("✍️ 框选背包区域", primary = true) { beginCrop(CropMode.SET_BACKPACK) })

        val box = RegionConfig.parse(Prefs.boxRegion(game))
        pillSub(l, "${if (box != null) "已框" else "未框"}选箱子区域${if (box != null) " ${box.rect.left},${box.rect.top}" else ""}")
        l.addView(makeBtn("📦 框选箱子区域（失控进化）", primary = true) { beginCrop(CropMode.SET_BOX) })

        val split = RegionConfig.parse(Prefs.splitRegion(game))
        pillSub(l, "${if (split != null) "已框" else "未框"}选拆分进度条区域")
        l.addView(makeBtn("🪓 框选拆分进度条区域（失控进化）", primary = true) { beginCrop(CropMode.SET_SPLIT) })

        val cols = makeInput(Prefs.cols(game).toString(), "列数", true)
        val rows = makeInput(Prefs.rows(game).toString(), "行数", true)
        val saveBtn = makeBtn("保存行列数", false) {
            val c = cols.text.toString().toIntOrNull(); val ro = rows.text.toString().toIntOrNull()
            if (c != null && ro != null && c in 1..50 && ro in 1..50) { Prefs.setCols(game, c); Prefs.setRows(game, ro); setStatus("网格 ${c}x$ro 已保存") }
            else setStatus("行列数无效")
        }
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(cols, rows).forEach { v -> v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); hRow.addView(v) }
        l.addView(hRow)
        l.addView(saveBtn)

        pillTitle(l, "⚙ 相似度阈值")
        val bar = SeekBar(this).apply { max = 40; progress = ((Prefs.mergeThreshold() - 0.6f) * 100).toInt() }
        l.addView(bar)
        val tl = makeTextRow("合并阈值：${"%.2f".format(Prefs.mergeThreshold())}", 12f, ThemeConfig.pal().textSub)
        l.addView(tl)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, pv: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = 0.6f + (sb?.progress ?: 0) / 100f; Prefs.setMergeThreshold(v)
                tl.text = "合并阈值：${"%.2f".format(v)}"
            }
        })
    }

    private fun toggleOrganize() {
        if (organizing) { organizer?.stop(); setStatus("已停止"); organizeBtn?.text = "🚀 开始整理"; organizing = false }
        else {
            organizer = AutoOrganizer(this, currentGame, ::setStatus, ::appendLog) { ok, msg ->
                setStatus(msg); organizeBtn?.text = "🚀 开始整理"; organizing = false
            }
            organizer?.start()
            organizing = true
            organizeBtn?.text = "⏹ 停止整理"
        }
    }

    // ============ 模板页：用户模板 + 分类 ============
    private fun tabTemplates(l: LinearLayout) {
        val game = currentGame
        pillTitle(l, "🗂 用户模板")
        pillSub(l, "把图标存成用户模板；可截图或上传图片后框选。")
        l.addView(makeBtn("📸/🖼 加用户模板（截图或上传→框选）", true) { cropMode = CropMode.SAVE_TEMPLATE; cropSlot = "user"; chooseImageSource() })
        val items = repo().list("user")
        if (items.isEmpty()) pillSub(l, "（暂无用户模板）") else items.forEach { t ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ThemeConfig.dp(4).toInt(), 0, 4.toFloat().toInt()) }
            r.addView(makeTextRow(t.label, 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(makeMiniBtn("删除") { repo().delete("user", t.label); setStatus("已删除 ${t.label}"); selectTab(2) })
            l.addView(r)
        }

        pillTitle(l, "🗂 分类系统")
        pillSub(l, "把若干模板+目标数量定义成一类（如 木材/食物）；失控进化用分类决定箱内保留物。")
        l.addView(makeBtn("➕ 新建分类", primary = true) { showInputDialog("新建分类", "分类名（如：木材）") { name ->
            val n = name.trim(); if (n.isEmpty()) { setStatus("名称为空"); return@showInputDialog }
            val cats = CategoriesStore.load(game)
            if (cats.any { it.name == n }) { setStatus("分类已存在"); return@showInputDialog }
            cats.add(Category(n)); CategoriesStore.save(game, cats); setStatus("已创建分类：$n"); selectTab(2)
        } })
        val cats = CategoriesStore.load(game)
        if (cats.isEmpty()) pillSub(l, "（暂无分类）") else cats.forEach { c ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ThemeConfig.dp(5).toInt(), 0, 0) }
            r.addView(makeTextRow("${c.name}（${c.items.size}项）", 13f, ThemeConfig.pal().textMain, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(makeMiniBtn("配置", { categoryEditor(c) }))
            r.addView(makeMiniBtn("删除", { showConfirmDialog("删除分类", "删除分类「${c.name}」？") {
                val lst = CategoriesStore.load(game); lst.removeAll { it.name == c.name }; CategoriesStore.save(game, lst)
                if (Prefs.selectedCat(game) == c.name) Prefs.setSelectedCat(game, "")
                setStatus("已删除分类：${c.name}"); selectTab(2)
            } }))
            l.addView(r)
        }
    }

    /** 分类配置：给分类勾选模板并填目标数量 */
    private fun categoryEditor(cat: Category) {
        showInputDialog("分类「${cat.name}」模板+数量", "输入模板名:数量，用空格分隔，如 木 40  石 20") { raw ->
            val map = LinkedHashMap<String, Int>()
            raw.trim().split(Regex("\\s+")).forEach { tok ->
                val parts = tok.split(":")
                if (parts.size == 2) {
                    val n = parts[1].trim().toIntOrNull(); val nName = parts[0].trim()
                    if (n != null && nName.isNotEmpty()) map[nName] = n
                }
            }
            if (map.isEmpty()) { setStatus("格式示例：木:40 石:20"); return@showInputDialog }
            cat.items.clear(); cat.items.putAll(map)
            val cats = CategoriesStore.load(currentGame)
            val idx = cats.indexOfFirst { it.name == cat.name }
            if (idx >= 0) { cats[idx] = cat; CategoriesStore.save(currentGame, cats) }
            setStatus("已配置分类「${cat.name}」：${cat.items}")
            selectTab(2)
        }
    }

    // ============ 工具页：相似度对比 / 图中找图 / 开发者素材 ============
    private fun tabTools(l: LinearLayout) {
        pillTitle(l, "🛠 相似度对比")
        pillSub(l, "分别选取两张图片（上传或截图→框选），点对比看相似度。")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(makeImgSlot(imgA, "A", { cropMode = CropMode.PICK_IMG; cropSlot = "A"; chooseImageSource() }, { confirmClearImg("A") { imgA = null; selectTab(3) } }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(makeImgSlot(imgB, "B", { cropMode = CropMode.PICK_IMG; cropSlot = "B"; chooseImageSource() }, { confirmClearImg("B") { imgB = null; selectTab(3) } }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        l.addView(row)
        l.addView(makeBtn("🔍 对比 A/B 相似度", primary = true) {
            if (imgA == null || imgB == null) { setStatus("请先选取 A 和 B 两张图"); return@makeBtn }
            val s = com.lostpacker.app.vision.ImageMatcher.similarity(
                com.lostpacker.app.vision.ImageMatcher.fingerprint(imgA!!),
                com.lostpacker.app.vision.ImageMatcher.fingerprint(imgB!!))
            setStatus("相似度：${"%.2f".format(s)}（>0.9 基本同一物品）"); appendLog("对比 A/B 相似度 = ${"%.2f".format(s)}")
        })

        pillTitle(l, "🔎 图中找图")
        pillSub(l, "选取大图+小图标，找小图在大图中的位置。")
        val findRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        findRow.addView(makeImgSlot(imgBig, "大图", { cropMode = CropMode.PICK_IMG; cropSlot = "BIG"; chooseImageSource() }, { confirmClearImg("大图") { imgBig = null; selectTab(3) } }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        findRow.addView(makeImgSlot(imgTpl, "小图", { cropMode = CropMode.PICK_IMG; cropSlot = "TPL"; chooseImageSource() }, { confirmClearImg("小图") { imgTpl = null; selectTab(3) } }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        l.addView(findRow)
        l.addView(makeBtn("📍 在大图中找小图", primary = true) {
            if (imgBig == null || imgTpl == null) { setStatus("请先选大图和小图"); return@makeBtn }
            Thread {
                setStatus("正在搜索图中目标…")
                val hit = com.lostpacker.app.vision.ImageMatcher.locateTemplate(imgBig!!, imgTpl!!)
                handler.post {
                    if (hit != null) setStatus("找到！位于 (${hit.first.x},${hit.first.y})，相似度 ${"%.2f".format(hit.second)}")
                    else setStatus("未找到匹配目标")
                    appendLog(if (hit != null) "图中找图命中 (${hit.first.x},${hit.first.y})" else "图中找图未命中")
                }
            }.start()
        })

        // 开发者素材
        pillTitle(l, "🧰 开发者素材")
        l.addView(makeBtn("📸/🖼 收集开发者素材（截图或上传→框选）", true) { cropMode = CropMode.SAVE_TEMPLATE; cropSlot = "dev"; chooseImageSource() })
        val dev = repo().list("dev")
        pillSub(l, if (dev.isEmpty()) "（暂无开发者素材）" else "已有 ${dev.size} 项素材")
        l.addView(makeBtn("📦 导出开发者素材（命名后分享）", true) { exportDevDialog() })
    }

    private fun confirmClearImg(label: String, onOk: () -> Unit) {
        showConfirmDialog("删除图片", "删除 $label 图片？") { onOk() }
    }

    /** 图片槽：默认圆角方块+加号；已有图为略缩图+右上角小叉 */
    private fun makeImgSlot(bmp: Bitmap?, label: String, onPick: () -> Unit, onClear: () -> Unit): FrameLayout {
        val p = ThemeConfig.pal()
        val slot = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ThemeConfig.dp(72).toInt(), ThemeConfig.dp(72).toInt()) }
        val iv = ImageView(this).apply {
            setBackground(ThemeConfig.rounded(p.bgCard, 10))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setOnClickListener { onPick() }
        }
        if (bmp != null) iv.setImageBitmap(bmp)
        slot.addView(iv, FrameLayout.LayoutParams(ThemeConfig.dp(72).toInt(), ThemeConfig.dp(72).toInt()))
        if (bmp == null) {
            slot.addView(TextView(this).apply {
                text = "+"; textSize = 34f; setTextColor(p.textSub); gravity = Gravity.CENTER
                background = ThemeConfig.rounded(p.bgCard, 10)
                setOnClickListener { onPick() }
            }, FrameLayout.LayoutParams(ThemeConfig.dp(72).toInt(), ThemeConfig.dp(72).toInt()))
        }
        slot.addView(TextView(this).apply {
            text = label; textSize = 10f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(ThemeConfig.dp(4).toInt(), 0, ThemeConfig.dp(4).toInt(), 0)
            background = ThemeConfig.rounded(0x88000000.toInt(), 6)
        }, FrameLayout.LayoutParams(ThemeConfig.dp(24).toInt(), ThemeConfig.dp(16).toInt(), Gravity.BOTTOM or Gravity.START))
        if (bmp != null) {
            slot.addView(TextView(this).apply {
                text = "✕"; textSize = 11f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
                background = ThemeConfig.rounded(0xFFE04040.toInt(), 9)
                setOnClickListener { onClear() }
            }, FrameLayout.LayoutParams(ThemeConfig.dp(18).toInt(), ThemeConfig.dp(18).toInt(), Gravity.TOP or Gravity.END))
        }
        return slot
    }

    private fun exportDevDialog() {
        val dev = repo().list("dev")
        if (dev.isEmpty()) { setStatus("开发者素材为空，请先收集"); return }
        showInputDialog("导出开发者素材", "游戏名（用于区分，如：失控进化）") { name ->
            val f = repo().exportDevTemplates(name)
            removeView(dialog); dialog = null; dialogInput = null
            setStatus("已生成 ${f.name}（${f.length() / 1024}KB）")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply { type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            try { startActivity(Intent.createChooser(intent, "导出 $name 图标包").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { setStatus("未找到可分享的应用") }
        }
    }

    private fun tabSettings(l: LinearLayout) {
        pillTitle(l, "⚙ 设置")
        val dark = Prefs.darkTheme()
        l.addView(makeBtn("深色模式：" + if (dark) "开" else "关", true) { Prefs.setDarkTheme(!Prefs.darkTheme()); rebuildPanelKeep() })
        pillSub(l, "自动理包器 v2.0.0")
        pillSub(l, "需 Shizuku 授权；截图即框选：双指缩放、单指拖动、开始框选后画框。")
    }

    // ============ 面板重建 / 拖动 / 坐标 ============
    private fun rebuildPanelKeep() {
        panelParams?.let { Prefs.setPanelPos(it.x, it.y) }
        removeView(panelView); panelView = null
        buildPanel()
        if (panelView != null) wm.addView(panelView, panelParams)
    }

    private fun movePanel(v: View, ev: MotionEvent): Boolean {
        val p = panelParams ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragDX = ev.rawX - p.x; dragDY = ev.rawY - p.y }
            MotionEvent.ACTION_MOVE -> { p.x = (ev.rawX - dragDX).toInt(); p.y = (ev.rawY - dragDY).toInt(); wm.updateViewLayout(panelView!!, p) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Prefs.setPanelPos(p.x, p.y)
        }
        return true
    }
    private fun moveBubble(v: View, ev: MotionEvent): Boolean {
        val p = bubbleParams ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragDX = ev.rawX - p.x; dragDY = ev.rawY - p.y }
            MotionEvent.ACTION_MOVE -> { p.x = (ev.rawX - dragDX).toInt(); p.y = (ev.rawY - dragDY).toInt(); wm.updateViewLayout(bubble!!, p) }
        }
        return false
    }
    private fun bubbleX(): Int { val px = Prefs.panelX(); return if (px in 0..screenWidth()) px else screenWidth() - ThemeConfig.dp(64).toInt() }
    private fun bubbleY(): Int { val py = Prefs.panelY(); return if (py in 0..screenHeight()) py else ThemeConfig.dp(200).toInt() }
    private fun prefPanelX(): Int { val px = Prefs.panelX(); return if (px in 0..screenWidth()) px else (screenWidth() - ThemeConfig.dp(312).toInt()) / 2 }
    private fun prefPanelY(): Int { val py = Prefs.panelY(); return if (py in 0..screenHeight()) py else ThemeConfig.dp(80).toInt() }

    // ============ 框选：区域直接出画布，截图相关只用于存模板/工具图 ============
    private fun beginCrop(mode: CropMode) {
        when (mode) {
            CropMode.SET_BACKPACK -> startRegionSelect("backpack")
            CropMode.SET_BOX -> startRegionSelect("box")
            CropMode.SET_SPLIT -> startRegionSelect("split")
            else -> { cropMode = mode; chooseImageSource() }
        }
    }

    /** 实时画布框选区域（不需要截屏）：直接在当前游戏画面上拖矩形。 */
    private fun startRegionSelect(kind: String) {
        if (selector != null) return
        hidePanelForOverlay()
        val game = currentGame
        val cv = RegionSelectorView(this).apply { gridCols = Prefs.cols(game); gridRows = Prefs.rows(game) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = ThemeConfig.rounded(0xCC000000.toInt(), 14)
            setPadding(ThemeConfig.dp(10).toInt(), ThemeConfig.dp(8).toInt(), ThemeConfig.dp(10).toInt(), ThemeConfig.dp(8).toInt())
        }
        val cancel = makeCropBtn("取消")
        val all = makeCropBtn("框全图")
        val ok = makeCropBtn(if (kind == "backpack") "确定(背包)" else if (kind == "box") "确定(箱子)" else "确定(拆分)")
        cancel.setBackground(ThemeConfig.stroked(0xFF888888.toInt(), 8, 1))
        all.background = ThemeConfig.rounded(0xFF8BC34A.toInt(), 8)
        ok.background = ThemeConfig.rounded(0xFF4FC3F7.toInt(), 8)
        cancel.setOnClickListener { teardownRegion() }
        all.setOnClickListener {
            cv.selectAll()
            val r = cv.lastRect()
            if (r != null) { applyRegion(r, kind); teardownRegion(); rebuildPanel(); selectTab(1) }
        }
        ok.setOnClickListener {
            val r = cv.lastRect()
            if (r == null) { setStatus("请先在画布上拖一个矩形"); return@setOnClickListener }
            applyRegion(r, kind)
            teardownRegion(); rebuildPanel(); selectTab(1)
        }
        bar.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(all, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ok, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val root = FrameLayout(this)
        root.addView(cv)
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val lp = WindowManager.LayoutParams(MATCH_PARENT, MATCH_PARENT, overlayType, FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        wm.addView(root, lp)
        selector = root
        setStatus("拖拽框出区域，点确定保存；点取消重来")
    }

    private fun teardownRegion() {
        removeView(selector); selector = null
        rebuildPanel()
    }

    private fun applyRegion(rect: Rect, kind: String) {
        val game = currentGame
        when (kind) {
            "backpack" -> Prefs.setRegion(game, RegionConfig(rect, Prefs.cols(game), Prefs.rows(game)).serialize())
            "box" -> Prefs.setBoxRegion(game, RegionConfig(rect, Prefs.cols(game), Prefs.rows(game)).serialize())
            "split" -> Prefs.setSplitRegion(game, RegionConfig(rect, Prefs.cols(game), Prefs.rows(game)).serialize())
        }
        setStatus(if (kind == "backpack") "已设背包区域" else if (kind == "box") "已设箱子区域" else "已设拆分区域")
    }

    /** 弹窗：上传图片并框区 / 截图并框区（选择后自动销毁该弹窗） */
    private fun chooseImageSource() {
        if (dialog != null) return
        val p = ThemeConfig.pal()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeConfig.rounded(p.bgPanel, 16)
            setPadding(ThemeConfig.dp(16).toInt(), ThemeConfig.dp(16).toInt(), ThemeConfig.dp(16).toInt(), ThemeConfig.dp(14).toInt())
        }
        card.addView(makeTextRow("选择图片来源", 16f, p.textMain, true))
        val up = makeBtn("🖼 上传图片并框区", primary = true) {
            removeView(dialog); dialog = null
            startImagePick()
        }
        val shot = makeBtn("📸 截图并框区", primary = true) {
            removeView(dialog); dialog = null
            captureThenCrop()
        }
        card.addView(up)
        card.addView(shot)
        val lp = WindowManager.LayoutParams(
            ThemeConfig.dp(280).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, overlayType,
            FLAG_NOT_TOUCH_MODAL or FLAG_ALT_FOCUSABLE_IM, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; dimAmount = 0.4f }
        wm.addView(card, lp)
        dialog = card
    }

    /** 上传图片：打开系统文件选择（PickerActivity 中转） */
    private fun startImagePick() {
        hidePanelForOverlay()
        SnapshotHolder.release()
        try {
            startActivity(Intent(this, PickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { setStatus("无法打开文件选择器"); rebuildPanel(); return }
        val poller = object : Runnable {
            override fun run() {
                if (SnapshotHolder.isPicked()) {
                    val bmp = SnapshotHolder.takePicked()
                    handler.post { if (bmp != null) showCropOverlay(bmp) else rebuildPanel() }
                } else {
                    handler.postDelayed(this, 250)
                }
            }
        }
        pickPoller?.let { handler.removeCallbacks(it) }
        pickPoller = poller
        handler.postDelayed(poller, 250)
    }

    private fun captureThenCrop() {
        if (!ShizukuSupport.isAvailable()) { setStatus("Shizuku 未运行"); appendLog("✗ 无法截图：Shizuku 未运行"); rebuildPanel(); return }
        if (!ShizukuSupport.isGranted()) { setStatus("请先授予 Shizuku 权限"); appendLog("✗ 无法截图：未授予 Shizuku 权限"); rebuildPanel(); return }
        hidePanelForOverlay()
        Thread {
            appendLog("开始截图…")
            val bmp = ScreenCapturer.capture()
            if (bmp == null) { appendLog("✗ 截图失败，请检查 Shizuku 授权"); handler.post { rebuildPanel() }; return@Thread }
            handler.post { showCropOverlay(bmp) }
        }.start()
    }

    private fun hidePanelForOverlay() { removeView(panelView); panelParams = null }

    private fun rebuildPanel() {
        removeView(panelView); panelView = null
        buildPanel()
        if (panelView != null) wm.addView(panelView, panelParams)
    }

    // ============ 全屏框选界面 ============
    private fun showCropOverlay(bmp: Bitmap) {
        if (cropRoot != null) { bmp.recycle(); return }
        val cv = CropView(this).apply { setSource(bmp) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = ThemeConfig.rounded(0xCC000000.toInt(), 14)
            setPadding(ThemeConfig.dp(10).toInt(), ThemeConfig.dp(8).toInt(), ThemeConfig.dp(10).toInt(), ThemeConfig.dp(8).toInt())
        }
        val allBtn = makeCropBtn("框全图")
        val okBtn = makeCropBtn(confirmLabel())
        val cancelBtn = makeCropBtn("取消")
        allBtn.background = ThemeConfig.rounded(0xFF8BC34A.toInt(), 8)
        okBtn.background = ThemeConfig.rounded(0xFF4FC3F7.toInt(), 8)
        cancelBtn.setBackground(ThemeConfig.stroked(0xFF888888.toInt(), 8, 1))
        allBtn.setOnClickListener { cv.selectAll() }
        okBtn.setOnClickListener { onCropConfirm(cv) }
        cancelBtn.setOnClickListener { teardownCrop(bmp) }
        bar.addView(allBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(okBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val root = FrameLayout(this)
        root.addView(cv)
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val lp = WindowManager.LayoutParams(MATCH_PARENT, MATCH_PARENT, overlayType, FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        wm.addView(root, lp)
        cropRoot = root
        setStatus("单指拖动框选 · 双指捏合缩放 / 双指拖动移动")
    }

    private fun makeCropBtn(label: String): TextView = TextView(this).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
        setPadding(ThemeConfig.dp(12).toInt(), ThemeConfig.dp(6).toInt(), ThemeConfig.dp(12).toInt(), ThemeConfig.dp(6).toInt())
    }

    private fun confirmLabel(): String = when (cropMode) {
        CropMode.SAVE_TEMPLATE -> "存为模板"
        CropMode.SET_BACKPACK -> "设为背包区域"
        CropMode.SET_BOX -> "设为箱子区域"
        CropMode.SET_SPLIT -> "设为拆分区域"
        CropMode.PICK_IMG -> "选用此图"
    }

    private fun onCropConfirm(cv: CropView) {
        when (cropMode) {
            CropMode.SAVE_TEMPLATE -> saveTemplate(cv)
            CropMode.SET_BACKPACK -> saveRegion(cv, "backpack")
            CropMode.SET_BOX -> saveRegion(cv, "box")
            CropMode.SET_SPLIT -> saveRegion(cv, "split")
            CropMode.PICK_IMG -> pickImage(cv)
        }
    }

    private fun saveRegion(cv: CropView, kind: String) {
        val rect = cv.selectedRect() ?: run { setStatus("请先框选区域"); return }
        val game = currentGame
        when (kind) {
            "backpack" -> Prefs.setRegion(game, RegionConfig(rect, Prefs.cols(game), Prefs.rows(game)).serialize())
            "box" -> Prefs.setBoxRegion(game, RegionConfig(rect, Prefs.cols(game), Prefs.rows(game)).serialize())
            "split" -> Prefs.setSplitRegion(game, RegionConfig(rect, Prefs.cols(game), Prefs.rows(game)).serialize())
        }
        setStatus(if (kind == "backpack") "已设背包区域" else if (kind == "box") "已设箱子区域" else "已设拆分区域")
        teardownCrop(SnapshotHolder.takePicked())
        rebuildPanel(); selectTab(1)
    }

    private fun pickImage(cv: CropView) {
        val bmp = cv.selectedBitmap() ?: run { setStatus("请先框选要用的图"); return }
        when (cropSlot) {
            "A" -> imgA = bmp
            "B" -> imgB = bmp
            "BIG" -> imgBig = bmp
            "TPL" -> imgTpl = bmp
        }
        setStatus("已选用 $cropSlot 图片")
        teardownCrop(SnapshotHolder.takePicked())
        rebuildPanel(); selectTab(3)
    }

    private fun saveTemplate(cv: CropView) {
        val icon = cv.selectedBitmap() ?: run { setStatus("请先框选图标"); return }
        val game = currentGame
        val slot = cropSlot
        showInputDialog("保存${if (slot == "user") "用户" else "开发者"}模板", "图标名称（如：木剑 / potion）") { name ->
            val label = if (name.isBlank()) "icon_${System.currentTimeMillis()}" else name
            repo().save(slot, label, icon)
            setStatus("已保存模板：$label")
            teardownCrop(SnapshotHolder.takePicked())
            rebuildPanel(); selectTab(if (slot == "user") 2 else 3)
        }
    }

    private fun teardownCrop(bmp: Bitmap?) {
        removeView(cropRoot); cropRoot = null
        bmp?.recycle()
    }

    // ============ 弹窗 ============
    private fun showInputDialog(title: String, hint: String, onOk: (String) -> Unit) {
        removeView(dialog); dialog = null
        val p = ThemeConfig.pal()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeConfig.rounded(p.bgPanel, 16)
            setPadding(ThemeConfig.dp(16).toInt(), ThemeConfig.dp(16).toInt(), ThemeConfig.dp(16).toInt(), ThemeConfig.dp(14).toInt())
        }
        card.addView(makeTextRow(title, 16f, p.textMain, true))
        val input = EditText(this).apply {
            this.hint = hint; setTextColor(p.textMain); setHintTextColor(p.textSub); textSize = 14f
            setBackground(ThemeConfig.rounded(p.bgCard, 8)); setPadding(ThemeConfig.dp(10).toInt(), ThemeConfig.dp(6).toInt(), ThemeConfig.dp(10).toInt(), ThemeConfig.dp(6).toInt())
        }
        card.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ThemeConfig.dp(10).toInt() })
        dialogInput = input
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancel = makeMiniBtn("取消") { removeView(dialog); dialog = null; dialogInput = null }
        val ok = makeMiniBtn("确定") { val s = input.text.toString().trim(); removeView(dialog); dialog = null; dialogInput = null; onOk(s) }
        btns.addView(cancel); btns.addView(ok)
        card.addView(btns, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ThemeConfig.dp(12).toInt() })

        // 可聚焦 + 允许输入法：去掉 NOT_FOCUSABLE
        val lp = WindowManager.LayoutParams(
            ThemeConfig.dp(290).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, overlayType,
            FLAG_NOT_TOUCH_MODAL or FLAG_ALT_FOCUSABLE_IM, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; dimAmount = 0.4f }
        wm.addView(card, lp)
        dialog = card
        handler.postDelayed({
            try {
                input.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) {}
        }, 140)
    }

    private fun showConfirmDialog(title: String, msg: String, onOk: () -> Unit) {
        removeView(dialog); dialog = null
        val p = ThemeConfig.pal()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeConfig.rounded(p.bgPanel, 16)
            setPadding(ThemeConfig.dp(16).toInt(), ThemeConfig.dp(16).toInt(), ThemeConfig.dp(16).toInt(), ThemeConfig.dp(14).toInt())
        }
        card.addView(makeTextRow(title, 16f, p.textMain, true))
        card.addView(makeTextRow(msg, 13f, p.textSub).also { it.setPadding(0, ThemeConfig.dp(6).toInt(), 0, 0) })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btns.addView(makeMiniBtn("取消") { removeView(dialog); dialog = null })
        btns.addView(makeMiniBtn("确定") { removeView(dialog); dialog = null; onOk() })
        card.addView(btns, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ThemeConfig.dp(12).toInt() })
        val lp = WindowManager.LayoutParams(
            ThemeConfig.dp(290).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, overlayType,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; dimAmount = 0.4f }
        wm.addView(card, lp)
        dialog = card
    }

    // ============ 状态 / 时钟 / 工具 ============
    private fun startTicker() {
        val r = object : Runnable {
            override fun run() {
                if (currentTab == 0) updatePermLine()
                handler.postDelayed(this, 1500)
            }
        }
        ticker = r
        handler.postDelayed(r, 1500)
    }

    private fun setStatus(msg: String) { lastStatus = msg; handler.post { statusTv?.text = msg } }
    private fun appendLog(msg: String) {
        if (logBuffer.length > 9000) logBuffer.delete(0, logBuffer.length - 6000)
        logBuffer.append(msg).append('\n')
        handler.post { logTv?.text = logBuffer.toString() }
    }
    private fun removeView(v: View?) { if (v != null) try { wm.removeView(v) } catch (e: Exception) {} }

    private fun screenWidth(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.width()
        else resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.height()
        else resources.displayMetrics.heightPixels

    companion object {
        private const val FLAG_NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        private const val FLAG_NOT_TOUCH_MODAL = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        private const val FLAG_LAYOUT_NO_LIMITS = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        private const val FLAG_ALT_FOCUSABLE_IM = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        private const val MATCH_PARENT = WindowManager.LayoutParams.MATCH_PARENT
    }
}