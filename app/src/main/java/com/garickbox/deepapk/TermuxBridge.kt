package com.garickbox.deepapk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

object TermuxBridge {
    const val PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    fun hasCodeBlocks(text: String): Boolean =
        parseMarkdownBlocks(text).any { it is MdBlock.Code }

    fun extractBestCodeBlock(text: String): MdBlock.Code? {
        val codes = parseMarkdownBlocks(text).filterIsInstance<MdBlock.Code>()
        if (codes.isEmpty()) return null
        return codes.lastOrNull {
            it.lang.lowercase() in listOf("sh", "bash", "zsh", "shell", "")
        } ?: codes.last()
    }

    fun hasRunPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    fun sendCodeToTermux(ctx: Context, lang: String, code: String) {
        val cleanLang = lang.lowercase().trim()
        val isShell = cleanLang in listOf("", "sh", "bash", "zsh", "shell")
        val isPython = cleanLang in listOf("py", "python", "python3")

        val desiredName = when {
            isShell -> "deep_cmd.sh"
            isPython -> "deep_cmd.py"
            else -> "deep_code.${cleanLang.take(8).ifBlank { "txt" }}"
        }

        val savedPath = writeToDownloads(ctx, desiredName, code)
        if (savedPath == null) {
            copyToClipboard(ctx, code)
            Toast.makeText(ctx, "Не удалось записать файл — код скопирован", Toast.LENGTH_SHORT).show()
            return
        }

        val termuxCmd = when {
            isShell -> "bash $savedPath"
            isPython -> "python $savedPath"
            else -> "cat $savedPath"
        }

        copyToClipboard(ctx, termuxCmd)

        if (isShell || isPython) {
            if (tryRunCommandIntent(ctx, isPython, savedPath)) {
                Toast.makeText(ctx, "Передано в Termux: $savedPath", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val launch = ctx.packageManager.getLaunchIntentForPackage("com.termux")
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(launch) }
            Toast.makeText(
                ctx,
                "Файл $savedPath готов · команда в буфере",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                ctx,
                "Сохранено: $savedPath (команда в буфере)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun tryRunCommandIntent(ctx: Context, isPython: Boolean, filePath: String): Boolean {
        if (!hasRunPermission(ctx)) return false
        return try {
            val bin = if (isPython) {
                "/data/data/com.termux/files/usr/bin/python"
            } else {
                "/data/data/com.termux/files/usr/bin/bash"
            }
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", bin)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(filePath))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            }
            ctx.startService(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeToDownloads(ctx: Context, fileName: String, content: String): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = ctx.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                var existingUri: android.net.Uri? = null
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(fileName),
                    null
                )?.use { cur ->
                    if (cur.moveToFirst()) {
                        val id = cur.getLong(0)
                        existingUri = android.content.ContentUris.withAppendedId(collection, id)
                    }
                }

                val targetUri = existingUri?.let { uri ->
                    runCatching {
                        resolver.openOutputStream(uri, "wt")?.use { os ->
                            os.write(content.toByteArray(Charsets.UTF_8))
                        }
                        uri
                    }.getOrNull()
                } ?: run {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val newUri = resolver.insert(collection, values) ?: return null
                    resolver.openOutputStream(newUri, "wt")?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                    }
                    newUri
                }

                var actualName = fileName
                resolver.query(
                    targetUri,
                    arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cur ->
                    if (cur.moveToFirst()) {
                        actualName = cur.getString(0) ?: fileName
                    }
                }
                "/sdcard/Download/$actualName"
            } catch (_: Exception) {
                null
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                f.writeText(content, Charsets.UTF_8)
                f.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("termux", text))
    }
}
