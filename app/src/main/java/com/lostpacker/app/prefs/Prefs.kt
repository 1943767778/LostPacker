package com.lostpacker.app.prefs

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    const val DEFAULT_GAME = "失控进化"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("lostpacker", Context.MODE_PRIVATE)
    }

    fun region(): String? = sp.getString("region", null)
    fun setRegion(v: String) = sp.edit().putString("region", v).apply()

    /// ---------------- 游戏管理（每游戏独立模板/分类/区域） ----------------
    fun currentGame(): String = sp.getString("cur_game", DEFAULT_GAME) ?: DEFAULT_GAME
    fun setCurrentGame(g: String) = sp.edit().putString("cur_game", g).apply()
    fun games(): MutableSet<String> =
        HashSet(sp.getStringSet("games", null) ?: setOf(DEFAULT_GAME))
    fun setGames(s: Set<String>) = sp.edit().putStringSet("games", s.toSet()).apply()

    // 背包识别区域（原来的识别区域，按游戏存）
    fun region(g: String): String? = sp.getString("region:$g", null)
    fun setRegion(g: String, v: String) = sp.edit().putString("region:$g", v).apply()
    // 箱子区域（失控进化等需要把物品收进容器的游戏）
    fun boxRegion(g: String): String? = sp.getString("box:$g", null)
    fun setBoxRegion(g: String, v: String) = sp.edit().putString("box:$g", v).apply()
    // 拆分进度条区域（失控进化超量拆分用）
    fun splitRegion(g: String): String? = sp.getString("split:$g", null)
    fun setSplitRegion(g: String, v: String) = sp.edit().putString("split:$g", v).apply()

    fun cols(g: String): Int = sp.getInt("cols:$g", 8)
    fun setCols(g: String, v: Int) = sp.edit().putInt("cols:$g", v).apply()
    fun rows(g: String): Int = sp.getInt("rows:$g", 8)
    fun setRows(g: String, v: Int) = sp.edit().putInt("rows:$g", v).apply()

    // 手动勾选用于本次整理的模板 label 集合（按游戏存）
    fun activeTpl(g: String): MutableSet<String> =
        HashSet(sp.getStringSet("atpl:$g", null) ?: emptySet())
    fun setActiveTpl(g: String, s: Set<String>) = sp.edit().putStringSet("atpl:$g", s.toSet()).apply()

    // 每游戏分类系统 JSON
    fun catsJson(g: String): String = sp.getString("cats:$g", "") ?: ""
    fun setCatsJson(g: String, v: String) = sp.edit().putString("cats:$g", v).apply()
    // 整理页当前选中的分类名
    fun selectedCat(g: String): String = sp.getString("catsel:$g", "") ?: ""
    fun setSelectedCat(g: String, v: String) = sp.edit().putString("catsel:$g", v).apply()

    fun mergeThreshold(): Float = sp.getFloat("merge_threshold", 0.92f)
    fun setMergeThreshold(v: Float) = sp.edit().putFloat("merge_threshold", v).apply()

    fun cols(): Int = sp.getInt("cols", 8)
    fun setCols(v: Int) = sp.edit().putInt("cols", v).apply()

    fun rows(): Int = sp.getInt("rows", 8)
    fun setRows(v: Int) = sp.edit().putInt("rows", v).apply()

    fun sortOrder(): String = sp.getString("sort_order", "row") ?: "row"
    fun setSortOrder(v: String) = sp.edit().putString("sort_order", v).apply()

    fun stepDelayMs(): Long = sp.getLong("step_delay", 350L)
    fun setStepDelayMs(v: Long) = sp.edit().putLong("step_delay", v).apply()

    // 悬浮窗：深色主题 + 位置记忆
    fun darkTheme(): Boolean = sp.getBoolean("dark_theme", false)
    fun setDarkTheme(v: Boolean) = sp.edit().putBoolean("dark_theme", v).apply()

    fun panelX(): Int = sp.getInt("panel_x", -1)
    fun panelY(): Int = sp.getInt("panel_y", -1)
    fun setPanelPos(x: Int, y: Int) = sp.edit().putInt("panel_x", x).putInt("panel_y", y).apply()

    fun githubToken(): String = sp.getString("github_token", "") ?: ""
    fun setGithubToken(v: String) = sp.edit().putString("github_token", v).apply()

    fun githubRepo(): String = sp.getString("github_repo", "") ?: ""
    fun setGithubRepo(v: String) = sp.edit().putString("github_repo", v).apply()

    fun saveImageDir(context: Context): java.io.File {
        val dir = java.io.File(context.getExternalFilesDir(null), "templates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}