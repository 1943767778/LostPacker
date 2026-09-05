package com.lostpacker.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.lostpacker.app.R
import com.lostpacker.app.auto.AutoOrganizer
import com.lostpacker.app.data.RegionConfig
import com.lostpacker.app.dev.DevToolsActivity
import com.lostpacker.app.prefs.Prefs

class FloatWindowService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var panel: View
    private var panelParams: WindowManager.LayoutParams? = null
    private var selector: View? = null
    private var organizer: AutoOrganizer? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        showPanel()
    }

    override fun onDestroy() {
        organizer?.stop()
        removeView(panel)
        panelParams = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val channelId = "lostpacker_float"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channelId, "理包器悬浮窗", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(this, channelId)
                .setContentTitle("失空进化自动理包器正在运行")
                .setSmallIcon(R.drawable.ic_launcher)
                .build()
            startForeground(1, n)
        }
    }

    // ---------- 悬浮窗面板 ----------
    private fun showPanel() {
        panel = LayoutInflater.from(this).inflate(R.layout.float_panel, null)
        tvStatus = panel.findViewById(R.id.tvStatus)
        tvLog = panel.findViewById(R.id.tvLog)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - 340
            y = 200
        }

        panel.findViewById<Button>(R.id.btnSelectRegion).setOnClickListener { startRegionSelect() }
        panel.findViewById<Button>(R.id.btnStart).setOnClickListener { startPacking() }
        panel.findViewById<Button>(R.id.btnStop).setOnClickListener { organizer?.stop() }
        panel.findViewById<Button>(R.id.btnDev).setOnClickListener {
            startActivity(Intent(this, DevToolsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        panel.findViewById<Button>(R.id.btnHide).setOnClickListener { stopSelf() }
        panel.findViewById<View>(R.id.panelTitle).setOnTouchListener(::movePanel)

        wm.addView(panel, panelParams)
    }

    private fun movePanel(v: View, ev: MotionEvent): Boolean {
        val p = panelParams ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragOffsetX = ev.rawX - p.x; dragOffsetY = ev.rawY - p.y }
            MotionEvent.ACTION_MOVE -> {
                p.x = (ev.rawX - dragOffsetX).toInt()
                p.y = (ev.rawY - dragOffsetY).toInt()
                wm.updateViewLayout(panel, p)
            }
        }
        return true
    }

    private fun screenWidth(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.currentWindowMetrics.bounds.width()
        }
        @Suppress("DEPRECATION")
        return getSystemService(Context.DISPLAY_SERVICE)?.let { (it as android.hardware.display.DisplayManager).getDisplay(0).width } ?: 1080
    }

    // ---------- 框选区域 ----------
    private fun startRegionSelect() {
        if (selector != null) return
        removeView(panel)
        // 打开游戏所在界面提示：直接在当前屏幕框选
        val root = LayoutInflater.from(this).inflate(R.layout.region_selector, null)
        val sv = root.findViewById<RegionSelectorView>(R.id.regionSelectorView)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        sv.onRegionSelected = { rect ->
            Prefs.setRegion(RegionConfig(rect, Prefs.cols(), Prefs.rows()).serialize())
            removeView(root)
            selector = null
            showPanel()
            setStatus("已框选区域 (${rect.left},${rect.top})-(${rect.right},${rect.bottom})")
        }
        wm.addView(root, p)
        selector = root
        setStatus("在屏幕中拖拽框出背包格子区域")
    }

    // ---------- 开始整理 ----------
    private fun startPacking() {
        organizer = AutoOrganizer(this, ::setStatus, ::appendLog) { ok, msg -> setStatus(msg) }
        organizer?.start()
    }

    private fun setStatus(msg: String) {
        if (::tvStatus.isInitialized) tvStatus.text = msg
    }

    private fun appendLog(msg: String) {
        if (::tvLog.isInitialized) {
            val t = tvLog.text.toString()
            tvLog.text = if (t == "日志" || t.isBlank()) msg else "$t\n$msg"
        }
    }

    private fun removeView(v: View) {
        try { wm.removeView(v) } catch (e: Exception) {}
    }
}