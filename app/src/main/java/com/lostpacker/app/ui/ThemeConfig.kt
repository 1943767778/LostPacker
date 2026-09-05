package com.lostpacker.app.ui

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.lostpacker.app.prefs.Prefs

/**
 * 悬浮窗双主题配色（浅/深）与圆角绘制工具。
 * 强调色统一用天蓝 #4FC3F7；圆角体系：面板20 / 内容14 / 按钮与输入框8 / 导航选中6。
 */
object ThemeConfig {

    data class Palette(
        val bgPanel: Int,
        val bgCard: Int,
        val textMain: Int,
        val textSub: Int,
        val navSel: Int,
        val navText: Int,
        val accent: Int,
        val onAccent: Int,
        val closeBg: Int,
        val divider: Int
    )

    val ACCENT = Color.parseColor("#4FC3F7")

    private val LIGHT = Palette(
        bgPanel = 0xFFFFFFFF.toInt(),
        bgCard = 0xFFF5F5F5.toInt(),
        textMain = 0xFF000000.toInt(),
        textSub = 0xFF666666.toInt(),
        navSel = 0xFFE3F2FD.toInt(),
        navText = 0xFF444444.toInt(),
        accent = ACCENT,
        onAccent = 0xFFFFFFFF.toInt(),
        closeBg = 0xFFF44336.toInt(),
        divider = 0xFFEDEDED.toInt()
    )

    private val DARK = Palette(
        bgPanel = 0xFF0D1B2A.toInt(),
        bgCard = 0xFF1B2838.toInt(),
        textMain = 0xFFFFFFFF.toInt(),
        textSub = 0xFF8A9BB0.toInt(),
        navSel = 0xFF13202C.toInt(),
        navText = 0xFFB0BEC5.toInt(),
        accent = ACCENT,
        onAccent = 0xFFFFFFFF.toInt(),
        closeBg = 0xFFF44336.toInt(),
        divider = 0xFF22384A.toInt()
    )

    fun pal(): Palette = if (Prefs.darkTheme()) DARK else LIGHT

    fun dp(v: Float): Float = v * Resources.getSystem().displayMetrics.density

    fun dp(v: Int): Float = v * Resources.getSystem().displayMetrics.density

    fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp.toFloat())
            setColor(color)
        }

    fun roundedCorner(color: Int, topLeft: Int, topRight: Int, bottomRight: Int, bottomLeft: Int): GradientDrawable =
        GradientDrawable().apply {
            val f = { d: Int -> dp(d.toFloat()) }
            cornerRadii = floatArrayOf(
                f(topLeft), f(topLeft),
                f(topRight), f(topRight),
                f(bottomRight), f(bottomRight),
                f(bottomLeft), f(bottomLeft)
            )
            setColor(color)
        }

    /** 描边卡片：透明底 + 次要文字色描边 + 圆角 */
    fun stroked(strokeColor: Int, radiusDp: Int, widthDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp.toFloat())
            setStroke(dp(widthDp).toInt(), strokeColor)
            setColor(0x00000000)
        }
}