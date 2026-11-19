package com.garickbox.deepapk

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Офлайн-база OCR. Двухуровневая:
 *  1. SAF .ocr/<sha256>/page_NNNNN.md — если папка проекта подключена (AI читает через fs_read)
 *  2. filesDir/ocr_base/<sha256>/  — fallback (не виден AI, но не теряется)
 * Ключ = SHA-256(размер_файла + первые 256 KB). Одинаковые имена не конфликтуют.
 */
internal object OcrCache {
    private const val DIR_LOCAL = "ocr_base"
    private const val DIR_SAF = ".ocr"
    private const val HASH_BYTES = 256 * 1024

    data class Stats(val pages: Int, val files: Int, val bytes: Long, val saf: Boolean)
    data class Entry(val hash: String, val name: String, val pages: Int, val bytes: Long, val saf: Boolean)

    private fun localRoot(ctx: Context): File = File(ctx.filesDir, DIR_LOCAL).apply { mkdirs() }
    private fun safReady(ctx: Context): Boolean = runCatching { ProjectFs.isReady(ctx) }.getOrDefault(false)
    private fun pageName(page: Int): String = "page_%05d.md".format(page)

    suspend fun fileHash(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            ctx.contentResolver.openInputStream(uri)?.use { inp ->
                val buf = ByteArray(HASH_BYTES)
                var total = 0L
                while (total < HASH_BYTES) {
                    val want = (HASH_BYTES - total).toInt().coerceAtMost(buf.size)
                    val read = inp.read(buf, 0, want)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                    total += read
                }
                val size = runCatching {
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (i >= 0) c.getLong(i) else 0L
                        } else 0L
                    } ?: 0L
                }.getOrDefault(0L)
                digest.update("|$size".toByteArray())
            } ?: return@withContext null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            Log.e("OCR", "fileHash: " + e.message, e)
            null
        }
    }

    fun get(ctx: Context, hash: String, page: Int): String? {
        if (safReady(ctx)) {
            val text = runCatching { ProjectFs.readRelFile(ctx, "$DIR_SAF/$hash/${pageName(page)}") }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        val f = File(File(localRoot(ctx), hash), pageName(page))
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    fun put(ctx: Context, hash: String, page: Int, text: String, origName: String): Boolean {
        if (text.isBlank()) return false
        if (safReady(ctx)) {
            val ok = runCatching {
                ProjectFs.writeRelFile(ctx, "$DIR_SAF/$hash/${pageName(page)}", text)
            }.getOrDefault(false)
            if (ok) {
                val namePath = "$DIR_SAF/$hash/_name.txt"
                if (runCatching { ProjectFs.readRelFile(ctx, namePath) }.getOrNull().isNullOrBlank()) {
                    runCatching { ProjectFs.writeRelFile(ctx, namePath, origName) }
                }
                return true
            }
        }
        return try {
            val dir = File(localRoot(ctx), hash).apply { mkdirs() }
            File(dir, pageName(page)).writeText(text)
            val nameFile = File(dir, "_name.txt")
            if (!nameFile.isFile) nameFile.writeText(origName)
            true
        } catch (e: Throwable) {
            Log.e("OCR", "put: " + e.message, e)
            false
        }
    }

    fun stats(ctx: Context): Stats {
        if (safReady(ctx)) {
            var pages = 0; var fn = 0; var bytes = 0L
            val dirs = runCatching { ProjectFs.listRelChildren(ctx, DIR_SAF) }.getOrDefault(emptyList())
            for ((name, isDir, _) in dirs) {
                if (!isDir) continue
                fn++
                val children = runCatching { ProjectFs.listRelChildren(ctx, "$DIR_SAF/$name") }.getOrDefault(emptyList())
                for ((cn, cIsDir, cs) in children) {
                    if (!cIsDir && cn.startsWith("page_")) { pages++; bytes += cs }
                }
            }
            return Stats(pages, fn, bytes, true)
        }
        var pages = 0; var files = 0; var bytes = 0L
        localRoot(ctx).listFiles()?.forEach { d ->
            if (d.isDirectory) {
                files++
                d.listFiles()?.forEach { f ->
                    if (f.name.startsWith("page_")) { pages++; bytes += f.length() }
                }
            }
        }
        return Stats(pages, files, bytes, false)
    }

    fun list(ctx: Context): List<Entry> {
        if (safReady(ctx)) {
            val dirs = runCatching { ProjectFs.listRelChildren(ctx, DIR_SAF) }.getOrDefault(emptyList())
            return dirs.filter { it.second }.map { (key, _, _) ->
                val children = runCatching { ProjectFs.listRelChildren(ctx, "$DIR_SAF/$key") }.getOrDefault(emptyList())
                var pages = 0; var bytes = 0L
                for ((cn, cIsDir, cs) in children) {
                    if (!cIsDir && cn.startsWith("page_")) { pages++; bytes += cs }
                }
                val name = runCatching { ProjectFs.readRelFile(ctx, "$DIR_SAF/$key/_name.txt") }.getOrNull()
                    ?.trim()?.takeIf { it.isNotBlank() } ?: key.take(12)
                Entry(key, name, pages, bytes, true)
            }.sortedByDescending { it.pages }
        }
        return localRoot(ctx).listFiles()?.filter { it.isDirectory }?.map { d ->
            val files = d.listFiles()
            val pages = files?.count { it.name.startsWith("page_") } ?: 0
            val bytes = files?.sumOf { it.length() } ?: 0L
            val name = File(d, "_name.txt").takeIf { it.isFile }
                ?.let { runCatching { it.readText() }.getOrNull() }
                ?.trim()?.takeIf { it.isNotBlank() } ?: d.name.take(12)
            Entry(d.name, name, pages, bytes, false)
        }?.sortedByDescending { it.pages } ?: emptyList()
    }

    fun deleteOne(ctx: Context, key: String): Boolean {
        var ok = false
        if (safReady(ctx)) {
            ok = runCatching { ProjectFs.deleteRelPath(ctx, "$DIR_SAF/$key") }.getOrDefault(false) || ok
        }
        ok = runCatching { File(localRoot(ctx), key).deleteRecursively() }.getOrDefault(false) || ok
        return ok
    }

    fun clear(ctx: Context): Boolean {
        var ok = false
        if (safReady(ctx)) {
            val dirs = runCatching { ProjectFs.listRelChildren(ctx, DIR_SAF) }.getOrDefault(emptyList())
            for ((name, isDir, _) in dirs) {
                if (isDir) runCatching { ProjectFs.deleteRelPath(ctx, "$DIR_SAF/$name") }
            }
            ok = true
        }
        runCatching {
            localRoot(ctx).deleteRecursively()
            localRoot(ctx)
        }
        return ok
    }

    fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> "%.1f MB".format(b / (1024.0 * 1024.0))
    }
}
