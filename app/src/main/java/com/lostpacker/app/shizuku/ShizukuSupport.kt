package com.lostpacker.app.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuSupport {

    fun isAvailable(): Boolean =
        Shizuku.pingBinder()

    /**
     * Shizuku 权限是否已授予。
     * 注意：binder 是异步到达的，binder 未就绪时 checkSelfPermission 会抛
     * IllegalStateException("binder haven't been received")，这里做防御性处理，
     * 避免 App 启动阶段闪退；binder 到达后由监听器刷新。
     */
    fun isGranted(): Boolean {
        if (!Shizuku.pingBinder()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** 请求 Shizuku 权限（异步，结果走 requestPermissionResultReceiver） */
    fun requestPermission(code: Int) {
        Shizuku.requestPermission(code)
    }

    /** 执行一条 shell 命令，返回 stdout / stderr */
    fun exec(vararg cmd: String): ShellResult {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            val remote = method.invoke(null, cmd as Any, null, null)
            val cls = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
            val stdout = cls.getMethod("getInputStream").invoke(remote) as java.io.InputStream
            val stderr = cls.getMethod("getErrorStream").invoke(remote) as java.io.InputStream
            val outBytes = stdout.readBytes()
            val errBytes = stderr.readBytes()
            stdout.close(); stderr.close()
            val exit = cls.getMethod("waitFor").invoke(remote) as Int
            ShellResult(
                exitCode = exit,
                stdout = String(outBytes, Charsets.UTF_8),
                stderr = String(errBytes, Charsets.UTF_8)
            )
        } catch (e: Exception) {
            ShellResult(-1, "", "exec failed: ${e.message}")
        }
    }

    /** 执行命令并返回 stdout 原始字节（用于截图） */
    fun execBinary(vararg cmd: String): ByteArray {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            val remote = method.invoke(null, cmd as Any, null, null)
            val cls = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
            val stdout = cls.getMethod("getInputStream").invoke(remote) as java.io.InputStream
            val bytes = stdout.readBytes()
            stdout.close()
            bytes
        } catch (e: Exception) {
            ByteArray(0)
        }
    }
}

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)