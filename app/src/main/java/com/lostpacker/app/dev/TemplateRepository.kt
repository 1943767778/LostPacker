package com.lostpacker.app.dev

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lostpacker.app.data.ItemTemplate
import com.lostpacker.app.vision.ImageMatcher
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 模板管理，按游戏隔离：
 *  - user：用户在“整理模板”页自建/管理、整理时勾选用的模板
 *  - dev ：开发者工具里截图框选收集、导出给开发者的素材模板
 * 两类分别在各自页面有列表；不同游戏有各自独立的模板目录。
 */
class TemplateRepository(private val context: Context, private val game: String) {

    fun dir(kind: String): File =
        File(context.getExternalFilesDir(null), "games/$game/templates/$kind").apply { if (!exists()) mkdirs() }

    /** 截图缓存目录（临时，退出清除） */
    val screenshotCacheDir: File
        get() = File(context.cacheDir, "lostpacker_shots").apply { if (!exists()) mkdirs() }

    fun list(kind: String): List<ItemTemplate> = scan(dir(kind))

    private fun scan(d: File): List<ItemTemplate> {
        if (!d.exists()) return emptyList()
        return d.listFiles { f -> f.extension.equals("png", true) || f.extension.equals("jpg", true) }
            ?.sortedBy { it.name }
            ?.map { f ->
                val label = f.name.substringBeforeLast('.')
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                ItemTemplate(id = label, label = label, file = f,
                    fingerprint = bmp?.let { ImageMatcher.fingerprint(it) } ?: FloatArray(0))
            } ?: emptyList()
    }

    /** 保存一张图标模板到指定分类 */
    fun save(kind: String, label: String, bitmap: Bitmap): File {
        val f = File(dir(kind), sanitize(label).removeSuffix(".png") + ".png")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** 删除某个分类下的模板 */
    fun delete(kind: String, label: String): Boolean {
        val f = File(dir(kind), sanitize(label).removeSuffix(".png") + ".png")
        return f.exists() && f.delete()
    }

    /** 当前游戏的全部模板（用户+开发者），用于整理匹配 */
    fun allTemplates(): List<ItemTemplate> = list("user") + list("dev")

    /** 保存一张 Shizuku 截屏到缓存（临时） */
    fun saveScreenshot(bmp: Bitmap): File {
        val f = File(screenshotCacheDir, "shot_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return f
    }

    /** 清空截图缓存（退出时调用）；模板目录不清理 */
    fun clearScreenshotCache() {
        val d = screenshotCacheDir
        if (d.exists()) d.listFiles()?.forEach { it.delete() }
    }

    /** 导出开发者素材模板 zip（命名区分游戏，供开发者预制） */
    fun exportDevTemplates(gameName: String): File {
        val safe = sanitize(gameName)
        val zip = File(context.cacheDir, "${safe}_dev_templates.zip")
        val files = dir("dev").listFiles { f -> f.extension.equals("png", true) || f.extension.equals("jpg", true) }
            ?: emptyArray()
        ZipOutputStream(zip.outputStream()).use { zos ->
            for (f in files) {
                zos.putNextEntry(ZipEntry("$safe/${f.name}"))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("$safe/README.txt"))
            zos.write(("开发者素材模板(${files.size}个)：\n" + files.joinToString("\n") { it.name }).toByteArray())
            zos.closeEntry()
        }
        return zip
    }

    /** 开发者预制在 assets/presets/<游戏名>/ 的图标（开箱即用） */
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
        } catch (e: Exception) { /* 无预设时忽略 */ }
        return list
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^\\w\\u4e00-\\u9fa5\\-]"), "_")
}