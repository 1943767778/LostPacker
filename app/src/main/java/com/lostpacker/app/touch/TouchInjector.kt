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

    /**
     * 真人式双击：两下点击间隔约 110ms（不快不慢）。
     * 用于失控进化中“把背包物品移入箱子/双击箱子物品取回”。
     * 只能在后台线程调用（内部有短暂 sleep）。
     */
    fun doubleTap(x: Int, y: Int): Boolean {
        val ok1 = ShizukuSupport.exec("input", "tap", x.toString(), y.toString()).exitCode == 0
        try { Thread.sleep(110) } catch (e: InterruptedException) {}
        val ok2 = ShizukuSupport.exec("input", "tap", x.toString(), y.toString()).exitCode == 0
        try { Thread.sleep(70) } catch (e: InterruptedException) {}
        return ok1 && ok2
    }
}