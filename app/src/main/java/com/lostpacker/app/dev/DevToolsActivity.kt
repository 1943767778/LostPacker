package com.lostpacker.app.dev

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lostpacker.app.R
import com.lostpacker.app.prefs.Prefs

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

    private fun log(msg: String) { runOnUiThread { tvLog.append("\n$msg") } }
}