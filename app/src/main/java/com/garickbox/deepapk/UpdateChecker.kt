package com.garickbox.deepapk

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(val latestVersion: String, val apkUrl: String, val releasePage: String)

object UpdateChecker {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    fun check(): UpdateInfo? {
        return try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/Garickbox/DeepApk/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body!!.string())
            val tag = json.optString("tag_name", "")
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apkUrl = a.optString("browser_download_url"); break }
            }
            if (apkUrl.isBlank()) return null
            UpdateInfo(
                latestVersion = tag.removePrefix("build-").ifBlank { tag },
                apkUrl = apkUrl,
                releasePage = json.optString("html_url", "")
            )
        } catch (_: Exception) { null }
    }
}
