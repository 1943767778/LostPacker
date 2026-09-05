package com.lostpacker.app.prefs

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("lostpacker", Context.MODE_PRIVATE)
    }

    fun region(): String? = sp.getString("region", null)
    fun setRegion(v: String) = sp.edit().putString("region", v).apply()

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