package com.lostpacker.app.dev

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lostpacker.app.data.ItemTemplate
import com.lostpacker.app.prefs.Prefs
import com.lostpacker.app.vision.ImageMatcher
import java.io.File

/** 管理上传的图标模板：保存到应用缓存目录，按 label 命名 */
class TemplateRepository(private val context: Context) {

    private val dir: File get() = Prefs.saveImageDir(context)

    fun list(): List<ItemTemplate> {
        val d = dir
        if (!d.exists()) return emptyList()
        return d.listFiles { f -> f.extension.equals("png", true) || f.extension.equals("jpg", true) }
            ?.sortedBy { it.name }
            ?.map { f ->
                val label = f.name.substringBeforeLast('.')
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                ItemTemplate(
                    id = label,
                    label = label,
                    file = f,
                    fingerprint = bmp?.let { ImageMatcher.fingerprint(it) } ?: FloatArray(0)
                )
            } ?: emptyList()
    }

    /** 保存一张上传的图标作为模板 */
    fun save(label: String, bitmap: Bitmap): File {
        val f = File(dir, sanitize(label) + ".png")
        val out = f.outputStream()
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            out.close()
        }
        return f
    }

    /** 导出当前所有模板到一个目录的压缩包（便于上传 GitHub） */
    fun exportTemplatesZip(): File {
        val zip = File(dir.parentFile ?: dir, "templates_export.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            for (f in listOf( *list().map { it.file }.toTypedArray() )) {
                if (!f.exists()) continue
                zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            return@use
        }
        return zip
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^\\w\\u4e00-\\u9fa5\\-]"), "_")
}