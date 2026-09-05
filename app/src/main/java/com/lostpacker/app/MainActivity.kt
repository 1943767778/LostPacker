package com.lostpacker.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lostpacker.app.overlay.FloatWindowService
import com.lostpacker.app.prefs.Prefs
import com.lostpacker.app.shizuku.ShizukuSupport
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private companion object { const val REQ_SHIZUKU = 10086 }

    private val reqNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val reqOverlay = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Prefs.init(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        findViewById<Button>(R.id.btnStartFloat).setOnClickListener {
            startFloatWindow()
        }
        findViewById<Button>(R.id.btnRequestShizuku).setOnClickListener {
            requestShizuku()
        }

        checkBasePermissions()
        refreshStatus()

        // binder 异步到达后刷新一次状态（避免启动时提前调用权限检查闪退）
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
            runOnUiThread { refreshStatus() }
        }
    }

    private fun refreshStatus() {
        val shizuku = if (ShizukuSupport.isAvailable()) "已运行" else "未运行"
        val permission = if (ShizukuSupport.isGranted()) "已授权" else "未授权"
        tvStatus.text = "Shizuku: $shizuku | 权限: $permission | 悬浮窗: ${if (Settings.canDrawOverlays(this)) "已开" else "未开"}"
    }

    private fun checkBasePermissions() {
        reqNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            reqOverlay.launch(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
    }

    private fun requestShizuku() {
        if (ShizukuSupport.isGranted()) { log("已授予 Shizuku 权限"); refreshStatus(); return }
        if (!ShizukuSupport.isAvailable()) { log("Shizuku 未运行，请先在桌面启动"); return }
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_SHIZUKU) {
                log(if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 授权成功" else "Shizuku 授权被拒绝")
                refreshStatus()
            }
        }
        Shizuku.requestPermission(REQ_SHIZUKU)
        refreshStatus()
    }

    private fun startFloatWindow() {
        if (!Settings.canDrawOverlays(this)) {
            log("请先授予悬浮窗权限")
            checkBasePermissions(); return
        }
        if (!ShizukuSupport.isAvailable()) { log("请先启动 Shizuku"); return }
        val intent = Intent(this, FloatWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        log("悬浮窗已开启（可回到游戏界面使用）")
    }

    private fun log(msg: String) {
        tvLog.append("\n$msg")
    }
}