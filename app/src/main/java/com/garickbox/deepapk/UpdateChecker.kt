package com.garickbox.deepapk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val latestBuild: Int,
    val latestVersion: String,
    val apkUrl: String,
    val fileName: String,
    val notes: String
)

object UpdateChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
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
                var fileName = "DeepApk-build-$latestBuild.apk"
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        fileName = name
                        break
                    }
                }
                if (apkUrl.isBlank()) return null
                UpdateInfo(
                    latestBuild = latestBuild,
                    latestVersion = "1.0.${if (latestBuild > 0) latestBuild else tag}",
                    apkUrl = apkUrl,
                    fileName = fileName,
                    notes = json.optString("body", "").trim()
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun downloadApk(
        ctx: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File? {
        return try {
            val req = Request.Builder().url(info.apkUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val total = body.contentLength()
                val outFile = File(ctx.cacheDir, info.fileName)
                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var copied = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            copied += n
                            if (total > 0) {
                                val pct = ((copied * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                runCatching {
                    TermuxBridge.writeBytesToDownloads(
                        ctx,
                        info.fileName,
                        "application/vnd.android.package-archive",
                        outFile.readBytes()
                    )
                }
                outFile
            }
        } catch (_: Exception) {
            null
        }
    }

    fun installApk(ctx: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                ctx,
                "Разрешите установку для DeepApk и нажмите «Установить» ещё раз",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
            return
        }

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(installIntent) }
            .onFailure {
                Toast.makeText(ctx, "Ошибка запуска установщика", Toast.LENGTH_SHORT).show()
            }
    }
}
