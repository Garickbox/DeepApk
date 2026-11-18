package com.garickbox.deepapk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

data class AttachedFile(
    val name: String,
    val sizeLabel: String,
    val content: String
)

data class IncomingShare(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val files: List<AttachedFile>
)

fun readAttachment(ctx: Context, uri: Uri): AttachedFile? {
    return try {
        var name = "file.txt"
        var sizeBytes = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nIdx >= 0) name = c.getString(nIdx) ?: name
                if (sIdx >= 0) sizeBytes = c.getLong(sIdx)
            }
        }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        val isDocx = name.endsWith(".docx", ignoreCase = true) ||
            mime.contains("wordprocessingml", ignoreCase = true)
        val text = if (isDocx) {
            extractDocxText(raw) ?: "[DOCX не удалось разобрать]"
        } else {
            val maxBytes = 300_000
            val cut = raw.size > maxBytes
            val slice = if (cut) raw.copyOf(maxBytes) else raw
            String(slice, Charsets.UTF_8) + if (cut) "\n\n... [обрезано до 300 KB]" else ""
        }
        val kb = (if (sizeBytes > 0) sizeBytes else raw.size.toLong()) / 1024
        val label = if (kb > 0) "${kb}KB" else "${raw.size}B"
        AttachedFile(name = name, sizeLabel = label, content = text)
    } catch (_: Exception) {
        null
    }
}

private fun extractDocxText(raw: ByteArray): String? {
    return try {
        var docXml: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(raw)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    docXml = zis.readBytes()
                    break
                }
            }
        }
        val bytes = docXml ?: return null
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val sb = StringBuilder()
        var inText = false
        var rowHasCell = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val local = p.name?.substringAfter(':').orEmpty()
            when (ev) {
                XmlPullParser.START_TAG -> when (local) {
                    "t" -> inText = true
                    "br" -> sb.append('\n')
                    "tab" -> sb.append('\t')
                    "tr" -> rowHasCell = false
                    "tc" -> {
                        if (rowHasCell) sb.append(" | ")
                        rowHasCell = true
                    }
                }
                XmlPullParser.TEXT -> if (inText) sb.append(p.text.orEmpty())
                XmlPullParser.END_TAG -> when (local) {
                    "t" -> inText = false
                    "p", "tr" -> sb.append('\n')
                }
            }
            ev = p.next()
        }
        val clean = sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        if (clean.length > 240_000) clean.take(240_000) + "\n... [обрезано]" else clean
    } catch (_: Exception) {
        null
    }
}

@Suppress("DEPRECATION")
fun extractIncomingShare(ctx: Context, intent: Intent?): IncomingShare? {
    if (intent == null) return null
    val action = intent.action ?: return null
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val parts = mutableListOf<String>()
    intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    val extraTexts = if (action == Intent.ACTION_SEND_MULTIPLE) {
        intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
    } else null
    if (!extraTexts.isNullOrEmpty()) {
        extraTexts.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }.forEach { parts.add(it) }
    } else {
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    }
    val uris = mutableListOf<Uri>()
    if (action == Intent.ACTION_SEND) {
        val u: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (u != null) uris.add(u)
    } else {
        val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        if (list != null) uris.addAll(list)
    }
    val files = uris.mapNotNull { readAttachment(ctx, it) }
    val text = parts.joinToString("\n\n")
    if (text.isBlank() && files.isEmpty()) return null
    return IncomingShare(text = text, files = files)
}
