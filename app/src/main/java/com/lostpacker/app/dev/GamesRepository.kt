package com.lostpacker.app.dev

import android.content.Context
import com.lostpacker.app.prefs.Prefs
import java.io.File

/**
 * 游戏档案管理：每款游戏有独立模板目录 + 独立区域/分类/勾选设置。
 * 默认游戏“失控进化”不可删除。
 */
class GamesRepository(private val context: Context) {

    fun defaultGame(): String = Prefs.DEFAULT_GAME

    /** 游戏模板根目录 */
    fun gameDir(game: String): File =
        File(context.getExternalFilesDir(null), "games/$game").apply { if (!exists()) mkdirs() }

    /** 当前游戏列表（默认游戏永远排最前） */
    fun list(): List<String> {
        val s = Prefs.games()
        if (Prefs.DEFAULT_GAME !in s) { s.add(Prefs.DEFAULT_GAME); Prefs.setGames(s) }
        return s.sortedWith(compareBy({ it != Prefs.DEFAULT_GAME }, { it }))
    }

    /** 确保当前游戏存在目录，防止空文件夹缺失 */
    fun ensure(name: String) {
        gameDir(name)
        val s = Prefs.games()
        if (name !in s) { s.add(name); Prefs.setGames(s) }
    }

    /** 添加新游戏，返回实际使用的名字；空名/重名返回 null */
    fun add(name: String): String? {
        val n = name.trim()
        if (n.isEmpty() || n in Prefs.games()) return null
        gameDir(n)
        val s = Prefs.games(); s.add(n); Prefs.setGames(s)
        return n
    }

    /** 删除游戏（默认游戏不可删） */
    fun remove(name: String): Boolean {
        if (name == Prefs.DEFAULT_GAME) return false
        val s = Prefs.games()
        if (name !in s) return false
        s.remove(name); Prefs.setGames(s)
        deleteRecursively(gameDir(name))
        if (Prefs.currentGame() == name) Prefs.setCurrentGame(Prefs.DEFAULT_GAME)
        removePrefs(name)
        return true
    }

    /** 复制游戏，返回新名字 */
    fun copy(from: String, to: String): String? {
        val n = to.trim()
        if (n.isEmpty() || n == from || n in Prefs.games()) return null
        copyRecursively(File(gameDir(from), "templates"), File(gameDir(n), "templates"))
        mapPrefs(from, n)
        val s = Prefs.games(); s.add(n); Prefs.setGames(s)
        return n
    }

    /** 重命名游戏（保留模板与设置） */
    fun rename(old: String, new: String): Boolean {
        val n = new.trim()
        if (n.isEmpty() || n == old || n in Prefs.games()) return false
        if (old == Prefs.DEFAULT_GAME) return false // 默认游戏不允许改名，避免破坏默认入口
        val from = gameDir(old)
        val to = File(from.parentFile, n)
        if (from.exists()) { if (!from.renameTo(to)) { to.mkdirs(); copyRecursively(File(from, "templates"), File(to, "templates")); deleteRecursively(from) } }
        mapPrefs(old, n)
        val s = Prefs.games(); s.remove(old); s.add(n); Prefs.setGames(s)
        if (Prefs.currentGame() == old) Prefs.setCurrentGame(n)
        return true
    }

    /** 把 pref 键里的 <suffix>:<old> 迁移为 <suffix>:<new> */
    private fun mapPrefs(old: String, new: String) {
        val sp = context.getSharedPreferences("lostpacker", Context.MODE_PRIVATE)
        val ed = sp.edit()
        for (k in sp.all.keys) {
            if (k.endsWith(":$old")) {
                ed.putString(k.removeSuffix(":$old") + ":$new", sp.getString(k, "").orEmpty())
                ed.remove(k)
            }
        }
        ed.apply()
    }

    private fun removePrefs(game: String) {
        val sp = context.getSharedPreferences("lostpacker", Context.MODE_PRIVATE)
        val ed = sp.edit()
        for (k in sp.all.keys) if (k.endsWith(":$game")) ed.remove(k)
        ed.apply()
    }

    private fun deleteRecursively(f: File) {
        if (!f.exists()) return
        f.walkBottomUp().forEach { it.delete() }
    }

    private fun copyRecursively(src: File, dst: File) {
        if (!src.exists()) return
        dst.mkdirs()
        src.copyRecursively(dst, overwrite = true)
    }
}