package com.lostpacker.app.touch

import com.lostpacker.app.shizuku.ShizukuSupport

/**
 * 通过 Shizuku 调用 `input` 命令注入触控事件。
 * 坐标均为屏幕绝对像素，与截屏/悬浮窗框选坐标一致。
 */
object TouchInjector {

    fun tap(x: Int, y: Int): Boolean {
        val r = ShizukuSupport.exec("input", "tap", x.toString(), y.toString())
        return r.exitCode == 0
    }

    /** 长按（某些游戏需要先长按再拖动） */
    fun longPress(x: Int, y: Int, ms: Int = 500): Boolean {
        return drag(x, y, x, y, ms)
    }

    /** 拖拽：模拟 按下 -> 移动 -> 抬起，即游戏中“抓起物品拖到目标格”。 */
    fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 500): Boolean {
        val r = ShizukuSupport.exec(
            "input", "swipe",
            x1.toString(), y1.toString(),
            x2.toString(), y2.toString(),
            durationMs.toString()
        )
        return r.exitCode == 0
    }
}