package com.learn.antilazy

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 通过 GitHub Releases 实现应用内检查更新；仅访问本仓库公开 API。 */
object Updater {

    private const val LATEST_API = "https://api.github.com/repos/longbow1998/AntiLazy/releases/latest"

    data class Release(val tagName: String, val notes: String, val apkUrl: String)

    /** 拉取最新 Release；网络异常、无 APK 资产或接口异常时返回 null。 */
    fun fetchLatestRelease(): Release? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(LATEST_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "AntiLazy-App")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(body: String): Release? = runCatching {
        val obj = JSONObject(body)
        val assets = obj.getJSONArray("assets")
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                apkUrl = asset.getString("browser_download_url")
                break
            }
        }
        Release(
            tagName = obj.getString("tag_name"),
            notes = obj.optString("body", ""),
            apkUrl = apkUrl ?: return null
        )
    }.getOrNull()

    /** 比较语义化版本号：remoteTag 是否比 currentVersionName 更新。 */
    fun isNewer(remoteTag: String, currentVersionName: String): Boolean {
        fun nums(v: String) = v.removePrefix("v").removePrefix("V").trim()
            .split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val remote = nums(remoteTag)
        val current = nums(currentVersionName)
        for (i in 0 until maxOf(remote.size, current.size)) {
            val r = remote.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }
}
