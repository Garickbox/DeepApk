package com.garickbox.deepapk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object TermuxBridge {
    const val PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    fun hasCodeBlocks(text: String): Boolean =
        parseMarkdownBlocks(text).any { it is MdBlock.Code }

    fun extractAllCodeBlocks(text: String): List<MdBlock.Code> =
        parseMarkdownBlocks(text).filterIsInstance<MdBlock.Code>()

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

        val savedPath = writeBytesToDownloads(
            ctx,
            desiredName,
            "text/plain",
            code.toByteArray(Charsets.UTF_8)
        )
        if (savedPath == null) {
            if ((isShell || isPython) && tryRunCommandInline(ctx, isPython, code)) {
                Toast.makeText(ctx, "Передано inline в Termux", Toast.LENGTH_SHORT).show()
                return
            }
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

    private fun tryRunCommandInline(ctx: Context, isPython: Boolean, code: String): Boolean {
        if (!hasRunPermission(ctx)) return false
        return try {
            val bin = if (isPython) "/data/data/com.termux/files/usr/bin/python"
                      else "/data/data/com.termux/files/usr/bin/bash"
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", bin)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", code))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            }
            ctx.startService(intent)
            true
        } catch (e: Exception) {
            Log.w("DeepApk", "tryRunCommandInline failed: ${e.javaClass.simpleName}: ${e.message}")
            false
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
        } catch (e: Exception) {
            Log.w("DeepApk", "tryRunCommandIntent failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun writeBytesToDownloads(
        ctx: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = ctx.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                var existingUri: Uri? = null
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(fileName),
                    null
                )?.use { cur ->
                    if (cur.moveToFirst()) {
                        existingUri = ContentUris.withAppendedId(collection, cur.getLong(0))
                    }
                }

                val targetUri = existingUri?.let { uri ->
                    runCatching {
                        resolver.openOutputStream(uri, "wt")?.use { os -> os.write(bytes) }
                        uri
                    }.getOrElse { e ->
                        Log.w("DeepApk", "open existing $uri failed: ${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                } ?: run {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val newUri = resolver.insert(collection, values)
                    if (newUri == null) {
                        Log.w("DeepApk", "MediaStore.insert returned null: name=$fileName mime=$mimeType")
                        return null
                    }
                    resolver.openOutputStream(newUri, "wt")?.use { os -> os.write(bytes) }
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
            } catch (e: Exception) {
                Log.w("DeepApk", "writeBytesToDownloads($fileName) failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                f.writeBytes(bytes)
                f.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }


    /**
     * Запускает команду в Termux (BACKGROUND=true), пишет вывод в /sdcard/Download/deepapk_jobs/<id>.log,
     * exitCode в <id>.exit. Kotlin-цикл каждые 1.5с зовёт onTick(elapsed, последняя_строка),
     * при завершении — onDone(exitCode, log_tail_8KB, durationSec).
     * stallTimeoutSec — тишина в логе = выход (-2). totalTimeoutSec — потолок (-3).
     */
    suspend fun runAndWatch(
        ctx: Context,
        cmd: String,
        stallTimeoutSec: Int = 40,
        totalTimeoutSec: Int = 180,
        onTick: (Int, String) -> Unit,
        onDone: (Int, String, Int) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            if (!hasRunPermission(ctx)) {
                withContext(Dispatchers.Main) { onDone(-1, "Нет разрешения RUN_COMMAND", 0) }
                return@withContext
            }
            // Shared media dir Android 11+: DeepApk пишет/читает без разрешений (своя media-папка),
            // Termux с MANAGE_EXTERNAL_STORAGE видит и пишет туда тоже.
            val mediaRoot = File(Environment.getExternalStorageDirectory(),
                "Android/media/com.garickbox.deepapk")
            val jobsDir = File(mediaRoot, "jobs")
            if (!jobsDir.exists() && !jobsDir.mkdirs()) {
                withContext(Dispatchers.Main) {
                    onDone(-1, "Не удалось создать ${jobsDir.absolutePath}", 0)
                }
                return@withContext
            }
            val jobId = "job_" + System.currentTimeMillis() + "_" + (1000..9999).random()
            val logFile = File(jobsDir, "$jobId.log")
            val exitFile = File(jobsDir, "$jobId.exit")

            val wrapper = "({$cmd}) > ${logFile.absolutePath} 2>&1; echo \$? > ${exitFile.absolutePath}"
            val bin = "/data/data/com.termux/files/usr/bin/bash"
            try {
                val intent = Intent("com.termux.RUN_COMMAND").apply {
                    component = ComponentName("com.termux", "com.termux.app.RunCommandService")
                    putExtra("com.termux.RUN_COMMAND_PATH", bin)
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapper))
                    putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                }
                ctx.startService(intent)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone(-1, "startService: ${e.message}", 0) }
                return@withContext
            }

            val start = System.currentTimeMillis()
            var lastLen = 0L
            var lastChange = start

            while (true) {
                delay(1500)
                val elapsed = ((System.currentTimeMillis() - start) / 1000).toInt()

                val exitText = if (exitFile.exists()) runCatching { exitFile.readText().trim() }.getOrNull() else null
                val exitCode = exitText?.toIntOrNull()
                if (exitCode != null) {
                    val body = if (logFile.exists()) runCatching { logFile.readText().take(8000) }.getOrDefault("") else ""
                    withContext(Dispatchers.Main) { onDone(exitCode, body, elapsed) }
                    return@withContext
                }

                val curLen = if (logFile.exists()) logFile.length() else 0L
                if (curLen > lastLen) { lastLen = curLen; lastChange = System.currentTimeMillis() }

                val lastLine = if (logFile.exists()) {
                    try { logFile.readLines().takeLast(2).joinToString(" | ").take(200) }
                    catch (_: Exception) { "" }
                } else ""

                withContext(Dispatchers.Main) { onTick(elapsed, lastLine) }

                val stall = (System.currentTimeMillis() - lastChange) / 1000
                if (stall > stallTimeoutSec) {
                    val body = if (logFile.exists()) runCatching { logFile.readText().take(8000) }.getOrDefault("") else ""
                    withContext(Dispatchers.Main) { onDone(-2, body + "\n[stall timeout: нет вывода ${stall}с]", elapsed) }
                    return@withContext
                }
                if (elapsed > totalTimeoutSec) {
                    val body = if (logFile.exists()) runCatching { logFile.readText().take(8000) }.getOrDefault("") else ""
                    withContext(Dispatchers.Main) { onDone(-3, body + "\n[total timeout: ${elapsed}с]", elapsed) }
                    return@withContext
                }
            }
        }
    }

    fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("deepapk", text))
    }
}
