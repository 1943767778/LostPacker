package com.lostpacker.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.lostpacker.app.R
import com.lostpacker.app.auto.AutoOrganizer
import com.lostpacker.app.capture.ScreenCapturer
import com.lostpacker.app.data.RegionConfig
import com.lostpacker.app.data.SnapshotHolder
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
    private var selector: View? = null          // 悬浮窗区域框选覆盖层
    private var cropRoot: View? = null          // 截图→框选覆盖层
    private var dialog: View? = null            // 通用输入弹窗
    private var organizer: AutoOrganizer? = null

    private lateinit var templateRepo: TemplateRepository
    private var currentTab = 0
    private var cropKind = "dev"

    private var statusTv: TextView? = null
    private var permTv: TextView? = null
    private var logTv: TextView? = null
    private var tabContent: LinearLayout? = null
    private var organizeBtn: TextView? = null
    private var organizing = false

    // 持久化：日志/状态跨 Tab 重建不丢
    private val logBuffer = StringBuilder()
    private var lastStatus = "就绪"

    private var dragDX = 0f
    private var dragDY = 0f
    private var dialogInput: EditText? = null

    private var ticker: Runnable? = null

    private val TAB_LABELS = arrayOf("信息", "整理", "模板", "工具", "设置")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        templateRepo = TemplateRepository(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        seedUsageLog()
        startForegroundCompat()
        showBubble()
        startTicker()
    }

    override fun onDestroy() {
        organizer?.stop()
        ticker?.let { handler.removeCallbacks(it) }
        removeView(bubble); bubbleParams = null
        removeView(panelView); panelParams = null
        removeView(selector); selector = null
        removeView(cropRoot); cropRoot = null
        removeView(dialog); dialog = null
        SnapshotHolder.release()
        templateRepo.clearScreenshotCache()
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
            text = "理"
            setTextColor(p.onAccent)
            textSize = 16f
            gravity = Gravity.CENTER
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
    private fun expandPanel() {
        removeView(bubble); bubbleParams = null
        buildPanel()
        wm.addView(panelView, panelParams)
    }
    private fun collapsePanel() {
        removeView(panelView); panelParams = null
        showBubble()
    }

    private fun buildPanel() {
        val p = ThemeConfig.pal()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeConfig.rounded(p.bgPanel, 20)
            setPadding(ThemeConfig.dp(6).toInt(), 0, ThemeConfig.dp(6).toInt(), ThemeConfig.dp(6).toInt())
        }
        // 顶部标题栏
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ThemeConfig.dp(12).toInt(), ThemeConfig.dp(10).toInt(), ThemeConfig.dp(8).toInt(), ThemeConfig.dp(10).toInt())
            setOnTouchListener(::movePanel)
        }
        titleBar.addView(TextView(this).apply {
            text = "自动理包器"
            setTextColor(p.textMain); textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(TextView(this).apply {
            text = "—"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ThemeConfig.dp(30).toInt(), ThemeConfig.dp(30).toInt())
            background = ThemeConfig.rounded(p.closeBg, 15)
            setOnClickListener { collapsePanel() }
        })
        root.addView(titleBar)

        // 主体：左导航 + 右内容
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
            ThemeConfig.dp(306).toInt(), ThemeConfig.dp(320).toInt(), overlayType,
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
            text = label
            gravity = Gravity.CENTER; textSize = 11f
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
                0 -> tabInfo(this)
                1 -> tabOrganize(this)
                2 -> tabTemplates(this, "user")
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

    private fun makeInput(def: String, hint: String, numeric: Boolean = false): EditText {
        val p = ThemeConfig.pal()
        return EditText(this).apply {
            setText(def); this.hint = hint
            setTextColor(p.textMain); setHintTextColor(p.textSub)
            setBackground(ThemeConfig.rounded(p.bgCard, 8))
            setPadding(ThemeConfig.dp(10).toInt(), ThemeConfig.dp(4).toInt(), ThemeConfig.dp(10).toInt(), ThemeConfig.dp(4).toInt())
            textSize = 13f
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            // 悬浮窗内唤起输入法：聚焦时临时让面板可聚焦
            setOnFocusChangeListener { _, focused -> setPanelFocusable(focused) }
        }
    }

    private fun setPanelFocusable(focusable: Boolean) {
        val p = panelParams ?: return
        p.flags = if (focusable) (p.flags and FLAG_NOT_FOCUSABLE.inv())
        else (p.flags or FLAG_NOT_FOCUSABLE)
        try { wm.updateViewLayout(panelView, p) } catch (e: Exception) {}
    }

    private fun rowOf(vararg texts: String): TextView = TextView(this).apply {
        text = texts.joinToString(" "); textSize = 13f; setTextColor(ThemeConfig.pal().textMain)
    }

    // ============ 各 Tab ============
    private fun tabInfo(l: LinearLayout) {
        pillTitle(l, "ℹ 信息")
        statusTv = TextView(this).apply { text = lastStatus; textSize = 13f; setTextColor(ThemeConfig.pal().textMain) }.also { l.addView(it) }
        permTv = TextView(this).apply { textSize = 12f; setTextColor(ThemeConfig.pal().textSub) }.also { l.addView(it) }
        updatePermLine()
        pillTitle(l, "📋 运行日志")
        logTv = TextView(this).apply { text = logBuffer.toString(); textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; setTextColor(ThemeConfig.pal().textSub) }.also { l.addView(it) }
    }

    /** 首次运行时把使用说明写进日志（日志持久化，切换 Tab/整理时不清空） */
    private fun seedUsageLog() {
        if (logBuffer.isNotEmpty()) return
        logBuffer.append("【使用说明】\n")
        logBuffer.append("1. 整理页：勾选要整理的目标图标（可多选，来自全部模板）→ 框选识别区域 → 开始整理。\n")
        logBuffer.append("2. 模板页：截图→框选保存为用户模板（整理时勾选可用）。\n")
        logBuffer.append("3. 工具页：截图→框选收集为开发者素材，可命名导出。\n")
        logBuffer.append("4. 拖动标题栏可移动悬浮窗位置。\n\n")
    }

    private fun updatePermLine() {
        permTv?.text = "Shizuku: ${if (ShizukuSupport.isAvailable()) "已运行" else "未运行"} · 权限: ${if (ShizukuSupport.isGranted()) "已授予" else "未授予"}"
    }

    private fun tabOrganize(l: LinearLayout) {
        pillTitle(l, "🧹 自动整理")

        // 手动从【全部模板】勾选本次要整理的目标图标（用户+开发者模板一起列出）
        pillTitle(l, "🎯 勾选要整理的目标模板")
        pillSub(l, "直接点选即可勾/取消（多选）；不勾任何模板则只按相似度归类。")
        val allTpls = templateRepo.list("user") + templateRepo.list("dev")
        if (allTpls.isEmpty()) {
            pillSub(l, "（暂无模板——请先到「模板/工具」页截图加入后,再回来勾选）")
        } else {
            allTpls.forEach { t ->
                val checked = t.label in Prefs.activeTemplateIds()
                val p = ThemeConfig.pal()
                val row = TextView(this).apply {
                    text = (if (checked) "☑  " else "☐  ") + t.label
                    textSize = 12f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ThemeConfig.dp(8).toInt(), ThemeConfig.dp(7).toInt(), ThemeConfig.dp(8).toInt(), ThemeConfig.dp(7).toInt())
                    setTextColor(if (checked) p.accent else p.textMain)
                    typeface = if (checked) android.graphics.Typeface.DEFAULT_BOLD else null
                    background = ThemeConfig.rounded(if (checked) p.navSel else p.bgCard, 8)
                    setOnClickListener {
                        val cur = Prefs.activeTemplateIds()
                        if (t.label in cur) cur.remove(t.label) else cur.add(t.label)
                        Prefs.setActiveTemplateIds(cur)
                        selectTab(currentTab) // 重建以刷新勾选状态
                    }
                }
                row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                    .apply { topMargin = ThemeConfig.dp(3).toInt() }
                l.addView(row)
            }
        }

        // 开始/停止 二态按钮
        organizeBtn = makeBtn(if (organizing) "⏹ 停止整理" else "🚀 开始整理", true) { toggleOrganize() }.also { l.addView(it) }

        pillTitle(l, "🖼 帧选识别区域")
        val cur = RegionConfig.parse(Prefs.region())
        pillSub(l, "区域：${if (cur != null) "(${cur.rect.left},${cur.rect.top})-(${cur.rect.right},${cur.rect.bottom})" else "未框选"} · 网格 ${Prefs.cols()}x${Prefs.rows()}")
        l.addView(makeBtn("✍️ 框选识别区域", primary = true) { startRegionSelect() })

        val cols = makeInput(Prefs.cols().toString(), "列数", true)
        val rows = makeInput(Prefs.rows().toString(), "行数", true)
        val saveBtn = makeBtn("保存行列数", false) {
            val c = cols.text.toString().toIntOrNull(); val ro = rows.text.toString().toIntOrNull()
            if (c != null && ro != null && c in 1..50 && ro in 1..50) { Prefs.setCols(c); Prefs.setRows(ro); setStatus("网格 ${c}x$ro 已保存") }
            else setStatus("行列数无效")
        }
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(cols, rows).forEach { v -> v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); hRow.addView(v) }
        l.addView(hRow)
        l.addView(saveBtn)

        pillTitle(l, "⚙ 相似度阈值")
        val bar = SeekBar(this).apply { max = 40; progress = ((Prefs.mergeThreshold() - 0.6f) * 100).toInt() }
        l.addView(bar)
        val tl = TextView(this).apply { text = "合并阈值：${"%.2f".format(Prefs.mergeThreshold())}"; textSize = 12f; setTextColor(ThemeConfig.pal().textSub) }
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
            organizer = AutoOrganizer(this, ::setStatus, ::appendLog) { ok, msg ->
                setStatus(msg); organizeBtn?.text = "🚀 开始整理"; organizing = false
            }
            organizer?.start()
            organizing = true
            organizeBtn?.text = "⏹ 停止整理"
        }
    }

    /** 模板列表页（用户/开发者共用一个构建器） */
    private fun tabTemplates(l: LinearLayout, kind: String) {
        val isUser = kind == "user"
        pillTitle(l, if (isUser) "🗂 整理模板（用户）" else "🧰 开发者模板")
        pillSub(l, if (isUser) "管理你整理时要用的模板；也可截图加新图标。" else "开发者素材收集，导出后我会预制到 App。")
        l.addView(makeBtn("📸 截图 → 框选图标（存${if (isUser) "用户" else "开发者"}模板）", true) {
            cropKind = kind; captureThenCrop()
        })
        pillTitle(l, "已保存模板")
        val items = templateRepo.list(kind)
        if (items.isEmpty()) pillSub(l, "（暂无模板）") else items.forEach { it ->
            val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            rowL.setPadding(0, ThemeConfig.dp(4).toInt(), 0, ThemeConfig.dp(4).toInt())
            rowL.addView(TextView(this).apply { text = it.label; textSize = 13f; setTextColor(ThemeConfig.pal().textMain) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (isUser) {
                rowL.addView(makeMiniBtn("删除") {
                    templateRepo.delete(kind, it.label); setStatus("已删除 ${it.label}")
                    selectTab(currentTab)
                })
            }
            l.addView(rowL)
        }
    }

    private fun makeMiniBtn(text: String, onClick: () -> Unit): TextView {
        val p = ThemeConfig.pal()
        return TextView(this).apply {
            this.text = text; textSize = 11f; gravity = Gravity.CENTER; isClickable = true
            layoutParams = LinearLayout.LayoutParams(ThemeConfig.dp(56).toInt(), ThemeConfig.dp(28).toInt())
            setTextColor(p.accent); background = ThemeConfig.stroked(p.accent, 8, 1)
            setOnClickListener { onClick() }
        }
    }

    private fun tabTools(l: LinearLayout) {
        // 开发者素材收集 + 导出（与用户模板页区分）
        tabTemplates(l, "dev")
        l.addView(makeBtn("📦 导出开发者模板（命名后分享）", true) { exportDevDialog() })
    }

    private fun tabSettings(l: LinearLayout) {
        pillTitle(l, "⚙ 设置")
        val dark = Prefs.darkTheme()
        l.addView(makeBtn("深色模式：" + if (dark) "开" else "关", true) { Prefs.setDarkTheme(!Prefs.darkTheme()); rebuildPanelKeep() })
        pillSub(l, "自动理包器 v1.4.0")
        pillSub(l, "仅悬浮窗操作；截图即框选：双指缩放、单指拖动、开始框选后画框。")
    }

    // ============ 面板重建 / 拖动 / 坐标 ============
    private fun rebuildPanelKeep() {
        val keep = panelParams?.let { Prefs.setPanelPos(it.x, it.y) }
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
    private fun prefPanelX(): Int { val px = Prefs.panelX(); return if (px in 0..screenWidth()) px else (screenWidth() - ThemeConfig.dp(316).toInt()) / 2 }
    private fun prefPanelY(): Int { val py = Prefs.panelY(); return if (py in 0..screenHeight()) py else ThemeConfig.dp(100).toInt() }

    // ============ 框选识别区域（全屏覆盖） ============
    private fun startRegionSelect() {
        if (selector != null) return
        hidePanelForOverlay()
        val root = LayoutInflaterCompat(R.layout.region_selector)
        val sv = root.findViewById<RegionSelectorView>(R.id.regionSelectorView) ?: return
        val p = WindowManager.LayoutParams(MATCH_PARENT, MATCH_PARENT, overlayType,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        sv.onRegionSelected = { rect ->
            Prefs.setRegion(RegionConfig(rect, Prefs.cols(), Prefs.rows()).serialize())
            removeView(root); selector = null
            setStatus("已框选识别区域")
            rebuildPanelKeep()
            selectTab(1)
        }
        wm.addView(root, p)
        selector = root
        setStatus("拖拽框出背包格子区域")
    }

    private fun hidePanelForOverlay() {
        removeView(panelView); panelParams = null
    }

    // ============ 截图 → 全屏框选 → 存为模板 ============
    private fun captureThenCrop() {
        if (!ShizukuSupport.isAvailable()) { setStatus("Shizuku 未运行"); appendLog("✗ 无法截图：Shizuku 未运行，请先启动 Shizuku"); return }
        if (!ShizukuSupport.isGranted()) { setStatus("请先授予 Shizuku 权限"); appendLog("✗ 无法截图：未授予 Shizuku 权限"); return }
        Thread {
            setStatus("正在截图…")
            appendLog("开始截图…")
            val bmp = ScreenCapturer.capture()
            if (bmp == null) {
                setStatus("截图失败，请到「信息」页查看日志")
                appendLog("✗ 截图失败：请确认 Shizuku 授权开启（uiautomator/shell），或点右上角气泡重新展开再试")
                return@Thread
            }
            templateRepo.saveScreenshot(bmp)
            SnapshotHolder.shot = bmp
            appendLog("✓ 截图成功，进入框选界面")
            handler.post { showCropOverlay(bmp) }
        }.start()
    }

    private fun showCropOverlay(bmp: android.graphics.Bitmap) {
        if (cropRoot != null) return
        hidePanelForOverlay()
        val cv = CropView(this).apply { setSource(bmp) }
        // 底部控制条
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ThemeConfig.rounded(0xCC000000.toInt(), 14)
            setPadding(ThemeConfig.dp(10).toInt(), ThemeConfig.dp(8).toInt(), ThemeConfig.dp(10).toInt(), ThemeConfig.dp(8).toInt())
        }
        val boxBtn = TextView(this).apply { text = "开始框选"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
            setPadding(ThemeConfig.dp(12).toInt(), ThemeConfig.dp(6).toInt(), ThemeConfig.dp(12).toInt(), ThemeConfig.dp(6).toInt()) }
        val saveBtn = TextView(this).apply { text = "存为模板"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
            setPadding(ThemeConfig.dp(12).toInt(), ThemeConfig.dp(6).toInt(), ThemeConfig.dp(12).toInt(), ThemeConfig.dp(6).toInt()) }
        val cancelBtn = TextView(this).apply { text = "取消"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
            setPadding(ThemeConfig.dp(12).toInt(), ThemeConfig.dp(6).toInt(), ThemeConfig.dp(12).toInt(), ThemeConfig.dp(6).toInt()) }
        boxBtn.background = ThemeConfig.stroked(0xFF4FC3F7.toInt(), 8, 1)
        saveBtn.background = ThemeConfig.rounded(0xFF4FC3F7.toInt(), 8)
        cancelBtn.setBackground(ThemeConfig.stroked(0xFF888888.toInt(), 8, 1))
        boxBtn.setOnClickListener { cv.boxMode = !cv.boxMode; boxBtn.text = if (cv.boxMode) "取消框选" else "开始框选"; setStatus(if (cv.boxMode) "双指缩放/单指拖动，正在画框" else "双指缩放·单指拖动，点「开始框选」后画框") }
        saveBtn.setOnClickListener { saveCropped(cv) }
        cancelBtn.setOnClickListener { teardownCrop(bmp) }
        bar.addView(boxBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val root = FrameLayout(this)
        root.addView(cv)
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val p = WindowManager.LayoutParams(MATCH_PARENT, MATCH_PARENT, overlayType,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        wm.addView(root, p)
        cropRoot = root
        setStatus("双指缩放 · 单指拖动，点「开始框选」后画框")
    }

    private fun saveCropped(cv: CropView) {
        val icon = cv.selectedBitmap() ?: run { setStatus("请先框选要保存的图标"); return }
        val kind = cropKind
        showInputDialog("保存${if (kind == "user") "用户" else "开发者"}模板", "图标名称（如：木剑 / potion）") { name ->
            val label = if (name.isBlank()) "icon_${System.currentTimeMillis()}" else name
            templateRepo.save(kind, label, icon)
            if (kind == "user") { teardownCrop(SnapshotHolder.shot); SnapshotHolder.release(); rebuildPanelKeep(); selectTab(2) }
            else { teardownCrop(SnapshotHolder.shot); SnapshotHolder.release(); rebuildPanelKeep(); selectTab(3) }
            setStatus("已保存模板：$label")
        }
    }

    private fun teardownCrop(bmp: android.graphics.Bitmap?) {
        removeView(cropRoot); cropRoot = null
        bmp?.recycle()
    }

    // ============ 开发者模板导出（命名 + 分享） ============
    private fun exportDevDialog() {
        if (templateRepo.list("dev").isEmpty()) { setStatus("开发者模板为空，请先截图→框选图标"); return }
        showInputDialog("导出开发者模板", "游戏名（用于区分，如：失空进化）") { name ->
            val f = templateRepo.exportDevTemplates(name)
            removeView(dialog); dialog = null; dialogInput = null
            setStatus("已生成 ${f.name}（${f.length() / 1024}KB）")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply { type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            try { startActivity(Intent.createChooser(intent, "导出 $name 图标包").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { setStatus("未找到可分享的应用") }
        }
    }

    // ============ 通用输入弹窗（自绘，避免 Service 弹 AlertDialog BadToken / 闪退） ============
    private fun showInputDialog(title: String, hint: String, onOk: (String) -> Unit) {
        if (dialog != null) return
        val p = ThemeConfig.pal()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeConfig.rounded(p.bgPanel, 16)
            setPadding(ThemeConfig.dp(16).toInt(), ThemeConfig.dp(16).toInt(), ThemeConfig.dp(16).toInt(), ThemeConfig.dp(14).toInt())
        }
        card.addView(TextView(this).apply { text = title; textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(p.textMain) })
        val input = EditText(this).apply { this.hint = hint; setTextColor(p.textMain); setHintTextColor(p.textSub); textSize = 14f
            setBackground(ThemeConfig.rounded(p.bgCard, 8)); setPadding(ThemeConfig.dp(10).toInt(), ThemeConfig.dp(6).toInt(), ThemeConfig.dp(10).toInt(), ThemeConfig.dp(6).toInt()) }
        card.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ThemeConfig.dp(10).toInt() })
        dialogInput = input
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancel = makeMiniBtn("取消") { removeView(dialog); dialog = null; dialogInput = null }
        val ok = makeMiniBtn("确定") { onOk(input.text.toString().trim()) }
        btns.addView(cancel); btns.addView(ok)
        card.addView(btns, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ThemeConfig.dp(12).toInt() })

        val lp = WindowManager.LayoutParams(
            ThemeConfig.dp(280).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, overlayType,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_ALT_FOCUSABLE_IM, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; dimAmount = 0.4f }
        wm.addView(card, lp)
        dialog = card
        handler.postDelayed({ try { input.requestFocus() } catch (e: Exception) {} }, 120)
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

    private fun setStatus(msg: String) {
        lastStatus = msg
        handler.post { statusTv?.text = msg }
    }
    private fun appendLog(msg: String) {
        if (logBuffer.length > 8000) logBuffer.delete(0, logBuffer.length - 6000)
        logBuffer.append(msg).append('\n')
        handler.post { logTv?.text = logBuffer.toString() }
    }
    private fun removeView(v: View?) { if (v != null) try { wm.removeView(v) } catch (e: Exception) {} }

    private fun LayoutInflaterCompat(res: Int): View = android.view.LayoutInflater.from(this).inflate(res, null)

    private fun screenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.width()
        else { @Suppress("DEPRECATION") (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(0).width }
    }
    private fun screenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.height()
        else { @Suppress("DEPRECATION") (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(0).height }
    }

    companion object {
        private const val FLAG_NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        private const val FLAG_NOT_TOUCH_MODAL = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        private const val FLAG_LAYOUT_NO_LIMITS = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        private const val FLAG_ALT_FOCUSABLE_IM = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        private const val MATCH_PARENT = WindowManager.LayoutParams.MATCH_PARENT
    }
}