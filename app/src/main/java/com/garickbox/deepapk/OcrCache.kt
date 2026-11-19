package com.garickbox.deepapk

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Офлайн-база OCR.
 * Ключ = SHA-256(размер_файла + первые 256 KB). Одинаковые имена не конфликтуют.
 * Хранилище: filesDir/ocr_base/<hash>/page_NNNNN.md + _name.txt
 * Не теряется при очистке кэша Android (filesDir ≠ cacheDir).
 */
internal object OcrCache {
    private const val DIR = "ocr_base"
    private const val HASH_BYTES = 256 * 1024

    data class Stats(val pages: Int, val files: Int, val bytes: Long)
    data class Entry(val hash: String, val name: String, val pages: Int, val bytes: Long)

    private fun root(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

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
        val f = File(File(root(ctx), hash), pageName(page))
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    fun put(ctx: Context, hash: String, page: Int, text: String, origName: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val dir = File(root(ctx), hash).apply { mkdirs() }
            File(dir, pageName(page)).writeText(text)
            val nameFile = File(dir, "_name.txt")
            if (!nameFile.isFile) nameFile.writeText(origName)
            true
        } catch (e: Throwable) {
            Log.e("OCR", "put: " + e.message, e)
            false
        }
    }

    private fun pageName(page: Int): String = "page_%05d.md".format(page)

    fun stats(ctx: Context): Stats {
        val r = root(ctx)
        var pages = 0; var files = 0; var bytes = 0L
        r.listFiles()?.forEach { d ->
            if (d.isDirectory) {
                files++
                d.listFiles()?.forEach { f ->
                    if (f.name.startsWith("page_")) {
                        pages++
                        bytes += f.length()
                    }
                }
            }
        }
        return Stats(pages, files, bytes)
    }

    fun list(ctx: Context): List<Entry> {
        val r = root(ctx)
        return r.listFiles()?.filter { it.isDirectory }?.map { d ->
            val files = d.listFiles()
            val pages = files?.count { it.name.startsWith("page_") } ?: 0
            val bytes = files?.sumOf { it.length() } ?: 0L
            val name = File(d, "_name.txt").takeIf { it.isFile }
                ?.let { runCatching { it.readText() }.getOrNull() }
                ?.trim()?.takeIf { it.isNotBlank() }
                ?: d.name.take(12)
            Entry(d.name, name, pages, bytes)
        }?.sortedByDescending { it.pages } ?: emptyList()
    }

    fun deleteOne(ctx: Context, hash: String): Boolean =
        try { File(root(ctx), hash).deleteRecursively() } catch (_: Throwable) { false }

    fun clear(ctx: Context): Boolean = try {
        root(ctx).deleteRecursively()
        root(ctx)
        true
    } catch (_: Throwable) { false }

    fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> "%.1f MB".format(b / (1024.0 * 1024.0))
    }
}
