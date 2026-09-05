package com.lostpacker.app.dev

import com.lostpacker.app.prefs.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 通过 GitHub REST API 把文件（源码/模板/发行包）上传到仓库。
 * 采用 contents API，逐文件创建/更新，base64 编码。
 */
object GitHubUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val REPO_URL = "https://api.github.com/repos/"

    data class Result(val ok: Boolean, val message: String)

    fun testConnection(token: String): Result {
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 200) {
                    val user = JSONObject(resp.body?.string() ?: "{}").optString("login", "?")
                    Result(true, "已连接，用户：$user")
                } else {
                    Result(false, resp.code.toString() + " " + (resp.body?.string() ?: ""))
                }
            }
        } catch (e: Exception) {
            Result(false, e.message ?: "网络错误")
        }
    }

    fun createOrUpdateFile(repo: String, token: String, path: String, contentBytes: ByteArray, msg: String): Result {
        val url = REPO_URL + repo + "/contents/" + path
        val b64 = Base64.getEncoder().encodeToString(contentBytes)
        val payload = JSONObject().apply {
            put("message", msg)
            put("content", b64)
        }
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        // 先尝试获取已有文件 sha
        val sha = getSha(repo, token, path)
        if (sha != null) payload.put("sha", sha)
        val req = Request.Builder()
            .url(url)
            .put(body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                Result(resp.code in 200..299, resp.code.toString())
            }
        } catch (e: Exception) {
            Result(false, e.message ?: "上传失败")
        }
    }

    private fun getSha(repo: String, token: String, path: String): String? {
        val url = REPO_URL + repo + "/contents/" + path
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 200) {
                    val sha = JSONObject(resp.body?.string() ?: "{}").optString("sha")
                    if (sha.isBlank()) null else sha
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}