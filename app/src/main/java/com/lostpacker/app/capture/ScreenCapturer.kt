package com.lostpacker.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lostpacker.app.shizuku.ShizukuSupport

/** 通过 Shizuku 执行 `screencap -p`，从 stdout 读取 PNG 字节并解码为 Bitmap */
object ScreenCapturer {

    /** @return 全屏截图，失败返回 null。 */
    fun capture(): Bitmap? {
        val bytes = ShizukuSupport.execBinary("screencap", "-p")
        if (bytes.isEmpty()) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}