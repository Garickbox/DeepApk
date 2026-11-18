package com.garickbox.deepapk

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val latestBuild: Int,
    val latestVersion: String,
    val apkUrl: String,
    val notes: String
)

object UpdateChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun check(): UpdateInfo? {
        return try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/Garickbox/DeepApk/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val tag = json.optString("tag_name", "")
                val latestBuild = tag.removePrefix("build-").toIntOrNull() ?: 0
                val assets = json.optJSONArray("assets") ?: return null
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isBlank()) return null
                UpdateInfo(
                    latestBuild = latestBuild,
                    latestVersion = "1.0.${if (latestBuild > 0) latestBuild else tag}",
                    apkUrl = apkUrl,
                    notes = json.optString("body", "").trim()
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
