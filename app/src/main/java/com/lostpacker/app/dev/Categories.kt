package com.lostpacker.app.dev

import com.lostpacker.app.prefs.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分类系统：把一个“目标模板 + 数量”的集合定义为一类（如 木材、食物、药品）。
 * 失控进化等生存游戏：勾选某个分类 → 整理时把该类物品收进箱子、并把箱内非该类物品取回。
 *
 * @param items 模板 label -> 需要在箱子里保留的数量（单位：个/格数）
 */
data class Category(
    val name: String,
    val items: LinkedHashMap<String, Int> = LinkedHashMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("items", JSONObject(items))
    }

    companion object {
        fun fromJson(o: JSONObject): Category {
            val it = LinkedHashMap<String, Int>()
            o.optJSONObject("items")?.let { io ->
                val k = io.keys()
                while (k.hasNext()) { val key = k.next(); it[key] = io.optInt(key, 0) }
            }
            return Category(o.optString("name", ""), it)
        }
    }
}

object CategoriesStore {
    fun load(game: String): MutableList<Category> {
        val raw = Prefs.catsJson(game)
        if (raw.isBlank()) return mutableListOf()
        return try {
            val a = JSONArray(raw); val out = mutableListOf<Category>()
            for (i in 0 until a.length()) out.add(Category.fromJson(a.getJSONObject(i)))
            out
        } catch (e: Exception) { mutableListOf() }
    }

    fun save(game: String, cats: List<Category>) {
        val a = JSONArray()
        cats.forEach { a.put(it.toJson()) }
        Prefs.setCatsJson(game, a.toString())
    }
}