package com.lostpacker.app.dev

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.lostpacker.app.R
import com.lostpacker.app.capture.ScreenCapturer
import com.lostpacker.app.data.SnapshotHolder
import com.lostpacker.app.prefs.Prefs
import com.lostpacker.app.shizuku.ShizukuSupport

class DevToolsActivity : AppCompatActivity() {

    private lateinit var templateRepo: TemplateRepository
    private lateinit var tvTemplates: TextView
    private lateinit var tvLog: TextView

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) importTemplate(uri) else log("取消选择")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dev_tools)
        Prefs.init(this)
        templateRepo = TemplateRepository(this)

        tvTemplates = findViewById(R.id.tvTemplates)
        tvLog = findViewById(R.id.tvDevLog)

        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            pickImage.launch("image/*")
        }
        findViewById<Button>(R.id.btnCaptureCrop).setOnClickListener {
            captureAndCrop()
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            exportWithName()
        }
        findViewById<Button>(R.id.btnSaveGrid).setOnClickListener {
            val cols = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCols).text.toString().toIntOrNull()
            val rows = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRows).text.toString().toIntOrNull()
            if (cols != null && rows != null && cols in 1..50 && rows in 1..50) {
                Prefs.setCols(cols); Prefs.setRows(rows)
                log("网格设置为 ${cols}x$rows")
            } else log("请输入有效的行列数")
        }
        findViewById<Button>(R.id.btnTestGithub).setOnClickListener {
            saveCreds()
            val res = GitHubUploader.testConnection(Prefs.githubToken())
            log(res.message)
        }
        findViewById<Button>(R.id.btnGenAndUpload).setOnClickListener {
            Thread { uploadAll() }.start()
        }

        fillPreload()
        refreshTemplates()
    }

    private fun fillPreload() {
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etToken)
            .setText(Prefs.githubToken())
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRepo)
            .setText(Prefs.githubRepo())
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCols)
            .setText(Prefs.cols().toString())
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRows)
            .setText(Prefs.rows().toString())
    }

    private fun saveCreds() {
        Prefs.setGithubToken(findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etToken).text.toString())
        Prefs.setGithubRepo(findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRepo).text.toString())
    }

    private fun importTemplate(uri: Uri) {
        val label = queryName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "template_${System.currentTimeMillis()}"
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
        if (bmp == null) { log("读取图片失败"); return }
        val f = templateRepo.save(label, bmp)
        bmp.recycle()
        log("已保存模板：${f.name}")
        refreshTemplates()
    }

    private fun queryName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cur ->
            val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cur.moveToFirst()) cur.getString(idx) else null
        }
    }

    private fun refreshTemplates() {
        val all = templateRepo.list()
        tvTemplates.text = if (all.isEmpty()) "已存模板：无"
        else "已存模板（${all.size}）：\n" + all.joinToString("\n") { "· " + it.label }
    }

    private fun uploadAll() {
        saveCreds()
        val token = Prefs.githubToken()
        val repo = Prefs.githubRepo()
        if (token.isBlank() || repo.isBlank()) { log("请先填写 Token 与仓库名"); return }

        log("生成仓库说明 & 上传模板…")
        val readme = buildReadme(templateRepo.list().map { it.label })
        push(repo, token, "README.md", readme.toByteArray(), "同步自动化说明")
        push(repo, token, "templates/templates_export.zip",
            templateRepo.exportTemplatesZip().readBytes(), "同步识别模板")
        push(repo, token, "templates/README.md",
            "本目录保存上传的图标模板，用于游戏图标识别。".toByteArray(), "模板说明")
        log("上传完成（未配置仓库时请先创建）")
    }

    private fun push(repo: String, token: String, path: String, bytes: ByteArray, msg: String) {
        val res = GitHubUploader.createOrUpdateFile(repo, token, path, bytes, msg)
        log("${path} : ${if (res.ok) "成功" else "失败(${res.message})"}")
    }

    private fun buildReadme(labels: List<String>): String {
        return """
            # 失空进化自动理包器 识别模板同步

            通过悬浮窗框选背包区域，经 Shizuku 触控 + 截图识别，自动拖动合并相同物品。

            ## 当前已上传的图标模板
            ${labels.joinToString("\n") { "- $it" }}

            ## 使用说明
            1. 安装并启动 Shizuku，授予权限。
            2. 主界面开启悬浮窗。
            3. 悬浮窗 -> 框选区域，框出背包格子并按实际行/列设置网格。
            4. 点击“开始整理”自动整理。
        """.trimIndent()
    }

    /** Shizuku 截图 → 存入缓存 → 交给框选页裁出图标 */
    private fun captureAndCrop() {
        if (!ShizukuSupport.isAvailable()) { log("Shizuku 未运行，请先启动"); return }
        if (!ShizukuSupport.isGranted()) { log("请先授予 Shizuku 权限"); return }
        Thread {
            log("正在截图…")
            val bmp = ScreenCapturer.capture()
            if (bmp == null) { log("截图失败，请检查 Shizuku 权限"); return@Thread }
            templateRepo.saveScreenshot(bmp)   // 截图到缓存
            SnapshotHolder.shot = bmp
            runOnUiThread { startActivity(Intent(this, IconCropActivity::class.java)) }
        }.start()
    }

    /** 导出模板：弹命名弹窗，方便区分不同游戏的图标包 */
    private fun exportWithName() {
        val templates = templateRepo.list()
        if (templates.isEmpty()) {
            log("暂无模板，请先“截图→框选图标”或上传图片")
            return
        }
        val input = EditText(this)
        input.hint = "例如：失空进化 或 shadow-of-war"
        AlertDialog.Builder(this)
            .setTitle("导出模板")
            .setMessage("给这组图标起个游戏名（用于区分不同游戏的图标）。\n导出后把压缩包上传给我，我会预制进 App 供所有人开箱即用。")
            .setView(input)
            .setPositiveButton("导出并分享") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { log("未填写游戏名，已取消导出"); return@setPositiveButton }
                exportZip(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportZip(name: String) {
        Thread {
            val f = templateRepo.exportGameTemplates(name)
            log("已生成包：${f.name}（${f.length() / 1024} KB）")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread {
                try {
                    startActivity(Intent.createChooser(intent, "导出 $name 图标包（请上传给我）"))
                } catch (e: Exception) { log("未找到用于保存/分享的应用") }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        templateRepo.clearScreenshotCache()   // 退出清理截图缓存，避免占用空间
    }

    private fun log(msg: String) { runOnUiThread { tvLog.append("\n$msg") } }
}