package com.lostpacker.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lostpacker.app.shizuku.ShizukuSupport
import java.io.File

/**
 * 通过 Shizuku 执行 `screencap -p <文件>` 截图。
 * 相比从 stdout 读二进制，落地到 /data/local/tmp 更稳定（部分机型/厂商二进制的
 * stdout 兼容性差），shell 创建的文件对 App 可读，再读回解码。
 */
object ScreenCapturer {

    /** @return 全屏截图，失败返回 null。 */
    fun capture(): Bitmap? {
        val file = "/data/local/tmp/lostpacker_shot_${System.currentTimeMillis()}.png"
        val r = ShizukuSupport.exec("screencap", "-p", file)
        if (r.exitCode != 0) return null
        val f = File(file)
        if (!f.exists() || f.length() <= 0) return null
        return try {
            BitmapFactory.decodeFile(file).also { f.delete() }
        } catch (e: Exception) {
            null
        }
    }
}