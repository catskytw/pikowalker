package com.pikowalker.app.update

import com.pikowalker.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer ${BuildConfig.GITHUB_UPDATE_TOKEN}")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext UpdateCheckResult.Error("GitHub API 回應錯誤 ($code)")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.getString("tag_name").removePrefix("v")
            val notes = json.optString("body", "")

            val assets = json.getJSONArray("assets")
            var assetApiUrl: String? = null
            var assetName: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    assetApiUrl = asset.getString("url")
                    assetName = name
                    break
                }
            }

            if (assetApiUrl == null || assetName == null) {
                return@withContext UpdateCheckResult.Error("最新版本沒有附上 APK 檔案")
            }

            if (isNewer(tag, BuildConfig.VERSION_NAME)) {
                UpdateCheckResult.Available(tag, notes, assetApiUrl, assetName)
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "檢查更新失敗")
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }
}
