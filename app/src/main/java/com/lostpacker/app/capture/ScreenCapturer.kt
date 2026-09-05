package com.lostpacker.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lostpacker.app.shizuku.ShizukuSupport
import java.io.File

/**
 * 通过 Shizuku 执行 `screencap -p <文件>` 截图。
 * 落地到 /data/local/tmp（shell 可写）再用 App 读回解码，避免从 stdout 读二进制
 * 在部分机型整包读取失败的问题；并对落盘文件 chmod 保证 App 可读，读不到再降级用
 * `cat` 读 stdout 字节流解码。
 */
object ScreenCapturer {

    /** @return 全屏截图，失败返回 null。 */
    fun capture(): Bitmap? {
        val file = "/data/local/tmp/lostpacker_shot_${System.currentTimeMillis()}.png"

        // 1) screencap 写文件
        val r = ShizukuSupport.exec("screencap", "-p", file)
        if (r.exitCode != 0) return readFromStdout()

        // 2) 确保 App 进程可读
        ShizukuSupport.exec("chmod", "0644", file)

        // 3) 优先直接读文件
        val f = File(file)
        if (f.exists() && f.length() > 0) {
            val bmp = try { BitmapFactory.decodeFile(file) } catch (e: Exception) { null }
            if (bmp != null) { ShizukuSupport.exec("rm", file); return bmp }
        }

        // 4) 降级：从 stdout 读回
        return readFromStdout(file)
    }

    private fun readFromStdout(file: String? = null): Bitmap? {
        val bytes = if (file != null) ShizukuSupport.execBinary("cat", file)
                    else ShizukuSupport.execBinary("screencap", "-p")
        if (file != null) ShizukuSupport.exec("rm", file)
        if (bytes.isEmpty()) return null
        return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (e: Exception) { null }
    }
}