package com.learn.antilazy

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 通过 GitHub Releases 实现应用内检查更新；仅访问本仓库公开页面。 */
object Updater {

    private const val TAG = "Updater"
    private const val OWNER = "longbow1998"
    private const val REPO = "Muse"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 供浏览器兜底跳转的发布页。 */
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases/latest"

    data class Release(val tagName: String, val notes: String, val apkUrl: String)

    /**
     * 拉取最新 Release。主通道走 API；部分网络环境无法访问 api.github.com，
     * 自动降级到网页 302 重定向通道（按统一产物命名规则拼出 APK 直链）。
     */
    fun fetchLatestRelease(): Release? = fetchViaApi() ?: fetchViaWebRedirect()

    private fun fetchViaApi(): Release? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConnection(API_URL, followRedirects = true)
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseJson(body)
        } catch (e: Exception) {
            Log.w(TAG, "api channel failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** releases/latest 对未带版本号的访问会 302 到 /tag/vX.Y.Z，从 Location 提取 tag。 */
    private fun fetchViaWebRedirect(): Release? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConnection(RELEASES_PAGE, followRedirects = false)
            val code = conn.responseCode
            if (code != 302 && code != 301) return null
            val location = conn.getHeaderField("Location") ?: return null
            val tag = location.substringAfterLast('/')
                .takeIf { it.startsWith("v") && it.any(Char::isDigit) } ?: return null
            Release(
                tagName = tag,
                notes = "",
                apkUrl = "https://github.com/$OWNER/$REPO/releases/download/$tag/AntiLazy-$tag-debug.apk"
            )
        } catch (e: Exception) {
            Log.w(TAG, "web redirect channel failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun openConnection(url: String, followRedirects: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", "Muse-App")
            if (followRedirects) {
                setRequestProperty("Accept", "application/vnd.github+json")
            }
        }

    private fun parseJson(body: String): Release? = runCatching {
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
