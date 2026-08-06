package com.pikowalker.app.update

import android.content.Context
import com.pikowalker.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ApkDownloader {

    suspend fun download(
        context: Context,
        assetApiUrl: String,
        assetName: String,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        // GitHub 私有 repo 的 asset 下載會先回 302 轉址到 S3 的預簽名網址 —
        // 第二段請求不能帶 Authorization header，否則 S3 會回 400。
        var conn = openAssetConnection(assetApiUrl, withAuth = true)
        var responseCode = conn.responseCode

        if (responseCode in 300..399) {
            val redirectUrl = conn.getHeaderField("Location")
                ?: throw IllegalStateException("下載連結轉址失敗")
            conn.disconnect()
            conn = openAssetConnection(redirectUrl, withAuth = false)
            responseCode = conn.responseCode
        }

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("下載失敗 (HTTP $responseCode)")
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val destFile = File(updatesDir, assetName)

        val totalSize = conn.contentLength
        var bytesRead = 0L

        conn.inputStream.use { input ->
            destFile.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesRead += read
                    if (totalSize > 0) onProgress(bytesRead.toFloat() / totalSize)
                }
            }
        }

        destFile
    }

    private fun openAssetConnection(urlString: String, withAuth: Boolean): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/octet-stream")
        if (withAuth) {
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.GITHUB_UPDATE_TOKEN}")
        }
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return conn
    }
}
