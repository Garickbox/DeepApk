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
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class TermuxJobHandle {
    @Volatile var cancelled: Boolean = false
    @Volatile internal var pidFile: File? = null

    fun requestCancel(ctx: Context) {
        cancelled = true
        val pf = pidFile ?: return
        val pid = runCatching { if (pf.exists()) pf.readText().trim().toIntOrNull() else null }.getOrNull()
        if (pid != null && pid > 0) TermuxBridge.killPid(ctx, pid)
    }
}

object TermuxBridge {
    const val PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    private val SHELL_LANGS = setOf("sh", "bash", "zsh", "shell")
    private val PYTHON_LANGS = setOf("py", "python", "python3")

    fun isShellLang(lang: String): Boolean = lang.trim().lowercase() in SHELL_LANGS
    fun isPythonLang(lang: String): Boolean = lang.trim().lowercase() in PYTHON_LANGS

    fun isRunnableCodeBlock(lang: String, code: String): Boolean {
        val l = lang.trim().lowercase()
        if (l in SHELL_LANGS || l in PYTHON_LANGS) return true
        if (l.isNotEmpty()) return false
        val first = code.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return false
        if (first.startsWith("#!/")) return true
        if (first.startsWith("{") && code.contains("| tee")) return true
        val cmdPrefixes = listOf(
            "adb ", "git ", "bash ", "sh ", "python", "cd ", "ls ", "cat ", "grep ",
            "sed ", "awk ", "find ", "curl ", "wget ", "mkdir ", "cp ", "mv ", "rm ",
            "chmod ", "pip ", "pkg ", "apt ", "echo ", "export ", "set ", "nl ", "wc "
        )
        return cmdPrefixes.any { first.startsWith(it) }
    }

    fun detectInterpreter(lang: String, code: String): String {
        if (isPythonLang(lang)) return "python3"
        if (isShellLang(lang)) return "bash"
        val trimmed = code.trimStart()
        val firstLine = trimmed.lineSequence().firstOrNull().orEmpty()
        if (firstLine.startsWith("#!/") && firstLine.contains("python")) return "python3"
        if ((trimmed.contains("import sys") || trimmed.contains("def ")) && !trimmed.contains("| tee")) return "python3"
        return "bash"
    }

    fun hasCodeBlocks(text: String): Boolean =
        parseMarkdownBlocks(text).any { it is MdBlock.Code }

    fun extractAllCodeBlocks(text: String): List<MdBlock.Code> =
        parseMarkdownBlocks(text).filterIsInstance<MdBlock.Code>()

    fun extractRunnableCodeBlocks(text: String): List<MdBlock.Code> =
        parseMarkdownBlocks(text).filterIsInstance<MdBlock.Code>().filter { isRunnableCodeBlock(it.lang, it.code) }

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
        runScriptAndWatch(
            ctx = ctx,
            scriptText = cmd,
            interpreter = "bash",
            stallTimeoutSec = stallTimeoutSec,
            totalTimeoutSec = totalTimeoutSec,
            onTick = onTick,
            onDone = onDone
        )
    }

    fun killPid(ctx: Context, pid: Int) {
        if (!hasRunPermission(ctx) || pid <= 0) return
        runCatching {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "kill -TERM -$pid 2>/dev/null || kill -TERM $pid 2>/dev/null || true"))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            ctx.startService(intent)
        }
    }

    private fun killPidFile(ctx: Context, pidFile: File) {
        val pid = runCatching { if (pidFile.exists()) pidFile.readText().trim().toIntOrNull() else null }.getOrNull()
        if (pid != null && pid > 0) killPid(ctx, pid)
    }

    private fun cleanupOldJobs(jobsDir: File) {
        runCatching {
            val files = jobsDir.listFiles() ?: return@runCatching
            val cutoff = System.currentTimeMillis() - 86_400_000L
            files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
            val rest = jobsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@runCatching
            if (rest.size > 60) rest.drop(60).forEach { it.delete() }
        }
    }

    private fun readTailUtf8(file: File, maxBytes: Int = 16384): String {
        if (!file.exists()) return ""
        return runCatching {
            val len = file.length()
            if (len <= 0L) return@runCatching ""
            RandomAccessFile(file, "r").use { raf ->
                val start = (len - maxBytes.toLong()).coerceAtLeast(0L)
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                val raw = String(buf, Charsets.UTF_8)
                if (start > 0L) raw.substringAfter('\n', raw) else raw
            }
        }.getOrDefault("")
    }

    private fun readLastLinesSummary(file: File): String =
        readTailUtf8(file, 4096)
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(2)
            .joinToString(" | ")
            .take(200)

    private fun readLogSmart(file: File, maxChars: Int = 8000): String {
        if (!file.exists()) return ""
        return runCatching {
            val len = file.length()
            if (len <= 0L) return@runCatching ""
            if (len <= 24576L) {
                val full = file.readText(Charsets.UTF_8)
                if (full.length <= maxChars) return@runCatching full
                val head = full.take(1400)
                val tail = full.takeLast(maxChars - 1600)
                val skipped = full.length - head.length - tail.length
                return@runCatching "$head\n… (пропущено $skipped симв.) …\n$tail"
            }
            val head = RandomAccessFile(file, "r").use { raf ->
                val n = minOf(len, 4096L).toInt()
                val b = ByteArray(n)
                raf.readFully(b)
                String(b, Charsets.UTF_8).take(1400)
            }
            val tail = readTailUtf8(file, 24576).takeLast(maxChars - 1600)
            "$head\n… (лог ${len / 1024} КБ, показаны начало и хвост) …\n$tail"
        }.getOrDefault("")
    }

    suspend fun runScriptAndWatch(
        ctx: Context,
        scriptText: String,
        interpreter: String,
        stallTimeoutSec: Int = 40,
        totalTimeoutSec: Int = 180,
        jobHandle: TermuxJobHandle? = null,
        onLogTail: ((String) -> Unit)? = null,
        onTick: (Int, String) -> Unit,
        onDone: (Int, String, Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            if (!hasRunPermission(ctx)) {
                withContext(Dispatchers.Main) {
                    onDone(-1, "Нет разрешения RUN_COMMAND. Настройки → Termux → Прямой запуск команд → Разрешить", 0)
                }
                return@withContext
            }
            val clean = scriptText.replace("\r\n", "\n").replace("\r", "\n")
            if (clean.isBlank()) {
                withContext(Dispatchers.Main) { onDone(-1, "Пустой скрипт", 0) }
                return@withContext
            }
            val mediaRoot = File(Environment.getExternalStorageDirectory(), "Android/media/com.garickbox.deepapk")
            val jobsDir = File(mediaRoot, "jobs")
            if (!jobsDir.exists() && !jobsDir.mkdirs()) {
                withContext(Dispatchers.Main) { onDone(-1, "Не удалось создать " + jobsDir.absolutePath, 0) }
                return@withContext
            }
            cleanupOldJobs(jobsDir)
            val jobId = "job_" + System.currentTimeMillis() + "_" + (1000..9999).random()
            val isPy = interpreter == "python3"
            val ext = if (isPy) "py" else "sh"
            val scriptFile = File(jobsDir, "$jobId.$ext")
            val logFile = File(jobsDir, "$jobId.log")
            val exitFile = File(jobsDir, "$jobId.exit")
            val pidFile = File(jobsDir, "$jobId.pid")
            jobHandle?.pidFile = pidFile
            scriptFile.writeText(clean)
            val interpBin = if (isPy) "python3" else "bash"
            val wrapper = "$interpBin '${scriptFile.absolutePath}' > '${logFile.absolutePath}' 2>&1 & CPID=\$!; echo \$CPID > '${pidFile.absolutePath}'; wait \$CPID; echo \$? > '${exitFile.absolutePath}'"
            try {
                val bin = "/data/data/com.termux/files/usr/bin/bash"
                val intent = Intent("com.termux.RUN_COMMAND").apply {
                    component = ComponentName("com.termux", "com.termux.app.RunCommandService")
                    putExtra("com.termux.RUN_COMMAND_PATH", bin)
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapper))
                    putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                }
                ctx.startService(intent)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onDone(-1, "Не удалось запустить Termux (${e.javaClass.simpleName}: ${e.message ?: ""}) — открой Termux или проверь автозапуск", 0)
                }
                return@withContext
            }
            val start = System.currentTimeMillis()
            var lastLen = 0L
            var lastChange = start
            while (true) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - start) / 1000).toInt()
                if (jobHandle?.cancelled == true) {
                    killPidFile(ctx, pidFile)
                    val body = readLogSmart(logFile)
                    withContext(Dispatchers.Main) { onDone(-4, (body + "\n[остановлено пользователем]").trim(), elapsed) }
                    return@withContext
                }
                val exitCode = if (exitFile.exists()) runCatching { exitFile.readText().trim().toIntOrNull() }.getOrNull() else null
                if (exitCode != null) {
                    val body = readLogSmart(logFile)
                    val tailText = readTailUtf8(logFile, 12000).takeLast(4000)
                    withContext(Dispatchers.Main) {
                        onLogTail?.invoke(tailText)
                        onDone(exitCode, body, elapsed)
                    }
                    return@withContext
                }
                val curLen = if (logFile.exists()) logFile.length() else 0L
                if (curLen > lastLen) { lastLen = curLen; lastChange = System.currentTimeMillis() }
                val lastLine = readLastLinesSummary(logFile)
                val liveTail = if (onLogTail != null) readTailUtf8(logFile, 12000).takeLast(4000) else ""
                withContext(Dispatchers.Main) {
                    onTick(elapsed, lastLine)
                    onLogTail?.invoke(liveTail)
                }
                val stall = (System.currentTimeMillis() - lastChange) / 1000
                if (stall > stallTimeoutSec) {
                    killPidFile(ctx, pidFile)
                    val body = readLogSmart(logFile)
                    withContext(Dispatchers.Main) { onDone(-2, body + "\n[stall: нет вывода ${stall}с]", elapsed) }
                    return@withContext
                }
                if (elapsed > totalTimeoutSec) {
                    killPidFile(ctx, pidFile)
                    val body = readLogSmart(logFile)
                    withContext(Dispatchers.Main) { onDone(-3, body + "\n[total: ${elapsed}с]", elapsed) }
                    return@withContext
                }
            }
        } catch (e: Throwable) {
            val msg = (e.message ?: e.javaClass.simpleName).take(400)
            withContext(Dispatchers.Main) { onDone(-1, "exception: " + msg, 0) }
        }
    }

    fun escapeSingle(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("deepapk", text))
    }
}
