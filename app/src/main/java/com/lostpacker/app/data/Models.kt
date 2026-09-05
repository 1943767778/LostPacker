package com.lostpacker.app.data

import com.lostpacker.app.prefs.Prefs
import android.graphics.Rect

/** 框选出的识别区域 + 网格配置 */
data class RegionConfig(
    val rect: Rect,
    val columns: Int,
    val rows: Int
) {
    fun serialize(): String =
        "${rect.left},${rect.top},${rect.right},${rect.bottom};$columns;$rows"

    companion object {
        fun parse(s: String?): RegionConfig? {
            if (s.isNullOrBlank()) return null
            return try {
                val seg = s.split(";")
                val rc = seg[0].split(",")
                RegionConfig(
                    Rect(rc[0].toInt(), rc[1].toInt(), rc[2].toInt(), rc[3].toInt()),
                    seg.getOrNull(1)?.toIntOrNull() ?: Prefs.cols(),
                    seg.getOrNull(2)?.toIntOrNull() ?: Prefs.rows()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** 截图识别出的一个格子 */
data class Cell(
    val index: Int,        // 网格序号（按扫描顺序）
    val row: Int,
    val col: Int,
    val centerX: Int,
    val centerY: Int,
    val exists: Boolean,   // 该格是否有物品
    val fingerprint: FloatArray
)

/** 模板别名：把上传的图标图片当成一种物品；assets 预制的模板 file 为 null */
data class ItemTemplate(
    val id: String,
    val label: String,
    val file: java.io.File? = null,
    val fingerprint: FloatArray
)