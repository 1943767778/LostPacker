package com.lostpacker.app.dev

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lostpacker.app.data.ItemTemplate
import com.lostpacker.app.prefs.Prefs
import com.lostpacker.app.vision.ImageMatcher
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 管理图标模板：
 *  - 模板保存到应用缓存目录，按 label 命名
 *  - 截图存到独立的缓存子目录（退出时清理，防占用空间）
 *  - 按游戏名导出 zip，供用户上传 / 开发者预制到 App
 */
class TemplateRepository(private val context: Context) {

    private val dir: File get() = Prefs.saveImageDir(context)

    /** 截图缓存目录（临时，退出清除） */
    val screenshotCacheDir: File
        get() = File(context.cacheDir, "lostpacker_shots").apply { if (!exists()) mkdirs() }

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

    /** 保存一张（裁切得到的）图标作为模板 */
    fun save(label: String, bitmap: Bitmap): File {
        val f = File(dir, sanitize(label).removeSuffix(".png") + ".png")
        val out = f.outputStream()
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            out.close()
        }
        return f
    }

    /** 保存一张 Shizuku 截屏到缓存（临时） */
    fun saveScreenshot(bmp: Bitmap): File {
        val f = File(screenshotCacheDir, "shot_${System.currentTimeMillis()}.png")
        val out = f.outputStream()
        try { bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) } finally { out.close() }
        return f
    }

    /** 清空截图缓存（退出时调用，防占用空间）；模板目录不清理 */
    fun clearScreenshotCache() {
        val d = screenshotCacheDir
        if (d.exists()) d.listFiles()?.forEach { it.delete() }
    }

    /** 按游戏名导出模板 zip（便于用户区分不同游戏的图标并上传） */
    fun exportGameTemplates(gameName: String): File {
        val safe = sanitize(gameName)
        val zip = File(context.cacheDir, "${safe}_templates.zip")
        val files = dir.listFiles { f -> f.extension.equals("png", true) || f.extension.equals("jpg", true) }
            ?: emptyArray()
        ZipOutputStream(zip.outputStream()).use { zos ->
            for (f in files) {
                zos.putNextEntry(ZipEntry("$safe/${f.name}"))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            // 附一份清单
            zos.putNextEntry(ZipEntry("$safe/README.txt"))
            zos.write(("当前模板(${files.size}个)：\n" + files.joinToString("\n") { it.name }).toByteArray())
            zos.closeEntry()
        }
        return zip
    }

    /** 兼容旧命名：生成通用导出包 */
    fun exportTemplatesZip(): File = exportGameTemplates("templates")

    /**
     * 开发者预制在 assets/presets/<游戏名>/ 里的图标模板（开箱即用）。
     * 你上传的导出包会被我处理后放到该目录，随 APK 一起发布，所有用户无需自己再框选。
     */
    fun presetTemplates(): List<ItemTemplate> {
        val list = ArrayList<ItemTemplate>()
        try {
            context.assets.list("presets")?.forEach { gameDir ->
                context.assets.list("presets/$gameDir")?.forEach { f ->
                    if (f.endsWith(".png", true) || f.endsWith(".jpg", true)) {
                        val label = "$gameDir/${f.substringBeforeLast('.')}"
                        context.assets.open("presets/$gameDir/$f").use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins)
                            if (bmp != null) {
                                list.add(ItemTemplate(id = label, label = label, file = null,
                                    fingerprint = ImageMatcher.fingerprint(bmp)))
                                bmp.recycle()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // assets 目录不存在时忽略
        }
        return list
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^\\w\\u4e00-\\u9fa5\\-]"), "_")
}