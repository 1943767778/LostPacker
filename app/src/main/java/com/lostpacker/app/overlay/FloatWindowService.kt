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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.lostpacker.app.R
import com.lostpacker.app.auto.AutoOrganizer
import com.lostpacker.app.data.RegionConfig
import com.lostpacker.app.data.SnapshotHolder
import com.lostpacker.app.dev.DevToolsActivity
import com.lostpacker.app.dev.IconCropActivity
import com.lostpacker.app.dev.TemplateRepository
import com.lostpacker.app.capture.ScreenCapturer
import com.lostpacker.app.dev.GitHubUploader
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
    private var organizer: AutoOrganizer? = null

    private lateinit var templateRepo: TemplateRepository
    private var currentTab = 0

    private var statusTv: TextView? = null
    private var logTv: TextView? = null
    private var tabContent: LinearLayout? = null

    private var dragDX = 0f
    private var dragDY = 0f

    private val TAB_LABELS = arrayOf("信息", "框选", "整理", "识别", "工具", "设置")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        templateRepo = TemplateRepository(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        showBubble()
    }

    override fun onDestroy() {
        organizer?.stop()
        removeView(bubble); bubbleParams = null
        removeView(panelView); panelParams = null
        removeView(selector); selector = null
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

    // ================= 圆点气泡 =================
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
        dot.layoutParams = LinearLayout.LayoutParams(ThemeConfig.dp(44).toInt(), ThemeConfig.dp(44).toInt())
        val lp = WindowManager.LayoutParams(
            ThemeConfig.dp(44).toInt(), ThemeConfig.dp(44).toInt(),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = bubbleX(); y = bubbleY() }
        wm.addView(dot, lp)
        bubble = dot; bubbleParams = lp
    }

    private fun bubbleX(): Int {
        val px = Prefs.panelX()
        return if (px in 0..screenWidth()) px else screenWidth() - ThemeConfig.dp(64).toInt()
    }
    private fun bubbleY(): Int {
        val py = Prefs.panelY()
        return if (py in 0..screenHeight()) py else ThemeConfig.dp(200).toInt()
    }

    private fun moveBubble(v: View, ev: MotionEvent): Boolean {
        val p = bubbleParams ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragDX = ev.rawX - p.x; dragDY = ev.rawY - p.y }
            MotionEvent.ACTION_MOVE -> {
                p.x = (ev.rawX - dragDX).toInt(); p.y = (ev.rawY - dragDY).toInt()
                wm.updateViewLayout(bubble!!, p)
            }
        }
        return false
    }

    // ================= 主面板（展开/收起） =================
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

        // ---- 顶部标题栏 ----
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeConfig.roundedCorner(p.bgPanel, 20, 20, 0, 0)
            setPadding(ThemeConfig.dp(12).toInt(), ThemeConfig.dp(10).toInt(),
                ThemeConfig.dp(8).toInt(), ThemeConfig.dp(10).toInt())
            setOnTouchListener(::movePanel)
        }
        val titleTv = TextView(this).apply {
            text = "自动理包器"
            setTextColor(p.textMain)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val closeBtn = TextView(this).apply {
            text = "—"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ThemeConfig.dp(30).toInt(), ThemeConfig.dp(30).toInt())
            setOnClickListener { collapsePanel() }
            background = ThemeConfig.rounded(p.closeBg, 15)
        }
        titleBar.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(closeBtn)
        root.addView(titleBar)

        // ---- 主体：左导航 + 右内容 ----
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ThemeConfig.dp(4).toInt(), 0, 0) }
        val nav = buildNav().also { body.addView(it) }
        val content = ScrollView(this).apply {
            setBackgroundColor(0x00000000)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setPadding(ThemeConfig.dp(4).toInt(), 0, ThemeConfig.dp(2).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val contentLv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ThemeConfig.dp(4).toInt(), 0, ThemeConfig.dp(4).toInt(), ThemeConfig.dp(8).toInt()) }
        content.addView(contentLv)
        body.addView(content)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabContent = contentLv
        panelView = root
        selectTab(0)
        content.setBackground(ThemeConfig.rounded(p.bgCard, 14))
        nav.requestLayout()

        panelParams = WindowManager.LayoutParams(
            ThemeConfig.dp(320).toInt(), ThemeConfig.dp(500).toInt(),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefPanelX(); y = prefPanelY()
        }
    }

    private fun prefPanelX(): Int {
        val px = Prefs.panelX()
        return if (px in 0..screenWidth()) px else (screenWidth() - ThemeConfig.dp(320).toInt()) / 2
    }
    private fun prefPanelY(): Int {
        val py = Prefs.panelY()
        return if (py in 0..screenHeight()) py else ThemeConfig.dp(120).toInt()
    }

    // ---- 左导航 ----
    private fun buildNav(): LinearLayout {
        val p = ThemeConfig.pal()
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ThemeConfig.dp(4).toInt(), 0, ThemeConfig.dp(4).toInt())
            layoutParams = LinearLayout.LayoutParams(ThemeConfig.dp(56).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        TAB_LABELS.forEachIndexed { i, label ->
            nav.addView(makeNavTab(i, label, i == currentTab, p))
        }
        return nav
    }

    private fun makeNavTab(index: Int, label: String, selected: Boolean, p: ThemeConfig.Palette): TextView {
        val tv = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 11f
            typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else null
            setTextColor(if (selected) p.accent else p.navText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ThemeConfig.dp(40).toInt()
            )
            setOnClickListener { selectTab(index) }
        }
        if (selected) tv.background = ThemeConfig.rounded(p.navSel, 6)
        return tv
    }

    private fun selectTab(index: Int) {
        currentTab = index
        rebuildNav()
        tabContent?.removeAllViews()
        tabContent?.let { buildTab(it, index) }
    }

    private fun rebuildNav() {
        val root = panelView ?: return
        // nav 是 root 里 body 的第一个子 View
        val body = (root.getChildAt(1) as? LinearLayout) ?: return
        if (body.childCount > 0) {
            body.removeViewAt(0)
            body.addView(buildNav(), 0)
        }
    }

    // ================= 内容区 =================
    private fun buildTab(container: LinearLayout, index: Int) {
        when (index) {
            0 -> tabInfo(container)
            1 -> tabRegion(container)
            2 -> tabOrganize(container)
            3 -> tabRecognize(container)
            4 -> tabTools(container)
            5 -> tabSettings(container)
        }
    }

    private fun pillTitle(l: LinearLayout, text: String) {
        l.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ThemeConfig.pal().textMain)
            setPadding(0, ThemeConfig.dp(6).toInt(), 0, ThemeConfig.dp(2).toInt())
        })
    }

    private fun pillSub(l: LinearLayout, text: String) {
        l.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ThemeConfig.pal().textSub)
            setPadding(0, 0, 0, ThemeConfig.dp(4).toInt())
        })
    }

    private fun makeBtn(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val p = ThemeConfig.pal()
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ThemeConfig.dp(38).toInt())
            val ml = (layoutParams as ViewGroup.MarginLayoutParams)
            ml.topMargin = ThemeConfig.dp(6).toInt()
            setOnClickListener { onClick() }
            if (primary) { setTextColor(p.onAccent); background = ThemeConfig.rounded(p.accent, 8) }
            else {
                setTextColor(p.textSub)
                background = ThemeConfig.stroked(p.textSub, 8, 1)
            }
        }
    }

    private fun makeInput(def: String, hint: String, numeric: Boolean = false): EditText {
        val p = ThemeConfig.pal()
        return EditText(this).apply {
            setText(def)
            this.hint = hint
            setTextColor(p.textMain)
            setHintTextColor(p.textSub)
            setBackground(ThemeConfig.rounded(p.bgCard, 8))
            setPadding(ThemeConfig.dp(10).toInt(), ThemeConfig.dp(6).toInt(), ThemeConfig.dp(10).toInt(), ThemeConfig.dp(6).toInt())
            textSize = 13f
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
    }

    private fun row(l: LinearLayout, vararg views: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { v ->
            v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(v)
        }
        l.addView(row)
    }

    private fun tabInfo(l: LinearLayout) {
        pillTitle(l, "ℹ 信息")
        statusTv = TextView(this).apply {
            text = "就绪"; textSize = 13f
            setTextColor(ThemeConfig.pal().textMain)
        }.also { l.addView(it) }
        pillSub(l, "Shizuku: ${if (ShizukuSupport.isAvailable()) "已运行" else "未运行"} · 权限: ${if (ShizukuSupport.isGranted()) "已授" else "未授"} · 悬浮窗已开启")
        l.addView(makeBtn("查看使用说明", false) { openDev() })
        pillTitle(l, "📋 运行日志")
        logTv = TextView(this).apply {
            text = ""
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ThemeConfig.pal().textSub)
        }.also { l.addView(it) }
    }

    private fun tabRegion(l: LinearLayout) {
        pillTitle(l, "🖼 背包区域设置")
        pillSub(l, "先设置包含背包格子的行列数，再框选屏幕上的背包区域")
        val cols = makeInput(Prefs.cols().toString(), "列数", true)
        val rows = makeInput(Prefs.rows().toString(), "行数", true)
        row(l, cols)
        row(l, rows)
        l.addView(makeBtn("保存行列数", false) {
            val c = cols.text.toString().toIntOrNull(); val ro = rows.text.toString().toIntOrNull()
            if (c != null && ro != null && c in 1..50 && ro in 1..50) { Prefs.setCols(c); Prefs.setRows(ro); setStatus("网格 ${c}x$ro 已保存") }
            else setStatus("行列数无效")
        })
        val cur = RegionConfig.parse(Prefs.region())
        pillSub(l, "当前区域：${if (cur != null) "(${cur.rect.left},${cur.rect.top})-(${cur.rect.right},${cur.rect.bottom})" else "未框选"}")
        l.addView(makeBtn("✍️ 框选区域", true) { startRegionSelect() })
    }

    private fun tabOrganize(l: LinearLayout) {
        pillTitle(l, "🧹 自动整理")
        pillSub(l, "截图识别相同物品后，Shizuku 触控拖动合并。开始前请切到游戏界面。")
        l.addView(makeBtn("🚀 开始整理", true) { startPacking() })
        l.addView(makeBtn("⏹ 停止", false) { organizer?.stop(); setStatus("已发送停止") })
        pillTitle(l, "⚙ 相似度阈值")
        val bar = SeekBar(this).apply {
            max = 40
            progress = ((Prefs.mergeThreshold() - 0.6f) * 100).toInt()
        }
        l.addView(bar)
        pillSub(l, "合并阈值：${"%.2f".format(Prefs.mergeThreshold())}（越高要求图标越像）")
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, pv: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = 0.6f + sb!!.progress / 100f
                Prefs.setMergeThreshold(v)
                (l.getChildAt(l.childCount - 1) as? TextView)?.text = "合并阈值：${"%.2f".format(v)}"
            }
        })
    }

    private fun tabRecognize(l: LinearLayout) {
        pillTitle(l, "👁 图标识别")
        pillSub(l, "用 Shizuku 截屏，框出某个物品图标存为模板；模板越多识别越准。")
        l.addView(makeBtn("📸 截图 → 框选图标", true) { captureAndCrop() })
        val user = templateRepo.list().size
        val preset = templateRepo.presetTemplates().size
        pillSub(l, "已存模板：$user 个；开箱即用预制：$preset 个")
    }

    private fun tabTools(l: LinearLayout) {
        pillTitle(l, "🧰 开发者工具")
        pillSub(l, "把图标导出成压缩包（命名区分不同游戏），上传后我会预制进 App 供所有人开箱即用。")
        l.addView(makeBtn("📦 导出模板（命名弹窗）", true) { exportWithName() })
        pillTitle(l, "🌐 GitHub 同步")
        val token = makeInput(Prefs.githubToken(), "GitHub Token(ghp_…)")
        val repo = makeInput(Prefs.githubRepo(), "仓库 owner/repo")
        l.addView(token); l.addView(repo)
        l.addView(makeBtn("测试连接", false) {
            Prefs.setGithubToken(token.text.toString()); Prefs.setGithubRepo(repo.text.toString())
            Thread {
                val r = GitHubUploader.testConnection(Prefs.githubToken())
                handler.post { setStatus(r.message) }
            }.start()
        })
        l.addView(makeBtn("上传模板说明", false) { syncGithub(repo.text.toString(), token.text.toString()) })
    }

    private fun tabSettings(l: LinearLayout) {
        pillTitle(l, "⚙ 设置")
        val dark = Prefs.darkTheme()
        l.addView(makeBtn("深色模式：" + if (dark) "开" else "关", true) {
            Prefs.setDarkTheme(!Prefs.darkTheme()); rebuildAll()
        })
        l.addView(makeBtn("重置悬浮窗位置", false) {
            Prefs.setPanelPos(-1, -1)
            rebuildAll()
        })
        l.addView(makeBtn("打开独立开发者页", false) { openDev() })
        pillSub(l, "自动理包器 v1.2.0")
        pillSub(l, "悬浮窗：顶部标题栏 + 左导航 + 右内容，圆角卡片风，浅/深双主题。")
    }

    private fun openDev() {
        startActivity(Intent(this, DevToolsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun rebuildAll() {
        val keepPos = panelParams?.let { Prefs.setPanelPos(it.x, it.y) }
        removeView(panelView); panelView = null
        buildPanel()
        if (panelView != null) wm.addView(panelView, panelParams)
    }

    // ================= 框选区域（全屏覆盖层） =================
    private fun startRegionSelect() {
        if (selector != null) return
        removeView(panelView); panelParams = null
        val root = LayoutInflaterView(com.lostpacker.app.R.layout.region_selector)
        val sv = root.findViewById<RegionSelectorView>(R.id.regionSelectorView) ?: return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        sv.onRegionSelected = { rect ->
            Prefs.setRegion(RegionConfig(rect, Prefs.cols(), Prefs.rows()).serialize())
            removeView(root); selector = null
            rebuildAll()
            setStatus("已框选区域")
        }
        wm.addView(root, p)
        selector = root
        setStatus("在屏幕中拖拽框出背包格子区域")
    }

    private fun LayoutInflaterView(layoutRes: Int): View =
        android.view.LayoutInflater.from(this).inflate(layoutRes, null)

    // ================= 开始整理 =================
    private fun startPacking() {
        organizer = AutoOrganizer(this, ::setStatus, ::appendLog) { ok, msg -> setStatus(msg) }
        organizer?.start()
    }

    // ================= 截图框选开发功能 =================
    private fun captureAndCrop() {
        if (!ShizukuSupport.isAvailable()) { setStatus("Shizuku 未运行"); return }
        if (!ShizukuSupport.isGranted()) { setStatus("请先授予 Shizuku 权限"); return }
        Thread {
            setStatus("正在截图…")
            val bmp = ScreenCapturer.capture()
            if (bmp == null) { setStatus("截图失败，请检查 Shizuku 权限"); return@Thread }
            templateRepo.saveScreenshot(bmp)
            SnapshotHolder.shot = bmp
            handler.post {
                startActivity(Intent(this, IconCropActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.start()
    }

    private fun exportWithName() {
        if (templateRepo.list().isEmpty()) { setStatus("暂无模板，请先截图→框选图标"); return }
        val input = EditText(this)
        input.hint = "游戏名，例如：失空进化"
        val dialog = AlertDialog.Builder(this)
            .setTitle("导出模板")
            .setMessage("给这组图标命名以区分不同游戏。导出后上传，我会预制进 App 供所有人开箱即用。")
            .setView(input)
            .setPositiveButton("导出并分享") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { setStatus("未命名，已取消"); return@setPositiveButton }
                Thread { exportZip(name) }.start()
            }
            .setNegativeButton("取消", null)
            .create()
        // 悬浮窗（Service 上下文）弹窗需要叠加窗类型，否则 BadTokenException
        try { dialog.window?.setType(overlayType) } catch (e: Exception) {}
        dialog.show()
    }

    private fun exportZip(name: String) {
        val f = templateRepo.exportGameTemplates(name)
        handler.post {
            setStatus("已生成 ${f.name}（${f.length() / 1024}KB）")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivity(Intent.createChooser(intent, "导出 $name 图标包（请上传）").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { setStatus("未找到可分享应用") }
        }
    }

    private fun syncGithub(repo: String, token: String) {
        Prefs.setGithubToken(token); Prefs.setGithubRepo(repo)
        Thread {
            setStatus("同步中…")
            if (token.isBlank() || repo.isBlank()) { setStatus("请填写 Token 与仓库"); return@Thread }
            val readme = "自动导出模板同步。请到仓库查看 templates/ 目录。".toByteArray()
            var ok = GitHubUploader.createOrUpdateFile(repo, token, "templates_export.zip",
                templateRepo.exportTemplatesZip().readBytes(), "同步导出模板").ok
            ok = GitHubUploader.createOrUpdateFile(repo, token, "README_templates.md", readme, "模板说明").ok
            setStatus(if (ok) "同步完成" else "同步失败（请检查仓库是否存在/Token 权限）")
        }.start()
    }

    // ================= 拖动 =================
    private fun movePanel(v: View, ev: MotionEvent): Boolean {
        val p = panelParams ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragDX = ev.rawX - p.x; dragDY = ev.rawY - p.y }
            MotionEvent.ACTION_MOVE -> {
                p.x = (ev.rawX - dragDX).toInt(); p.y = (ev.rawY - dragDY).toInt()
                wm.updateViewLayout(panelView!!, p)
            }
            MotionEvent.ACTION_UP -> Prefs.setPanelPos(p.x, p.y)
            MotionEvent.ACTION_CANCEL -> Prefs.setPanelPos(p.x, p.y)
        }
        return true
    }

    private fun screenWidth(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return wm.currentWindowMetrics.bounds.width()
        @Suppress("DEPRECATION")
        return (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(0).width
    }
    private fun screenHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return wm.currentWindowMetrics.bounds.height()
        @Suppress("DEPRECATION")
        return (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(0).height
    }

    private fun setStatus(msg: String) {
        handler.post { statusTv?.text = msg }
    }
    private fun appendLog(msg: String) {
        handler.post { logTv?.let { it.text = if (it.text.isNullOrEmpty()) msg else it.text.toString() + "\n" + msg } }
    }
    private fun removeView(v: View?) {
        if (v != null) try { wm.removeView(v) } catch (e: Exception) {}
    }
}