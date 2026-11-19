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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

private suspend fun ensureTessdata(ctx: Context): File = withContext(Dispatchers.IO) {
    val root = File(ctx.filesDir, "tess").apply { mkdirs() }
    val dir = File(root, "tessdata").apply { mkdirs() }
    for (name in listOf("rus.traineddata", "eng.traineddata")) {
        val f = File(dir, name)
        if (!f.exists() || f.length() < 100_000L) {
            try {
                ctx.assets.open("tessdata/" + name).use { inp ->
                    f.outputStream().use { out -> inp.copyTo(out) }
                }
            } catch (e: Exception) {
                Log.e("OCR", "copy " + name + " failed: " + e.message, e)
            }
        }
    }
    root
}

private suspend fun tesseractOcr(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    var tess: TessBaseAPI? = null
    var bmp: android.graphics.Bitmap? = null
    try {
        val root = ensureTessdata(ctx)
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return@withContext null
        tess = TessBaseAPI()
        if (!tess.init(root.absolutePath, "rus+eng", TessBaseAPI.OcrEngineMode.OEM_LSTM_ONLY)) {
            Log.e("OCR", "tess.init failed")
            return@withContext null
        }
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        tess.setVariable("user_defined_dpi", "300")
        tess.setVariable("preserve_interword_spaces", "1")
        tess.setImage(bmp)
        val text = tess.getUTF8Text() ?: ""
        Log.d("OCR", "tess5 dim=${bmp.width}x${bmp.height} len=${text.length} conf=${try { tess.meanConfidence() } catch (_: Throwable) { -1 }}")
        if (text.isBlank()) null else text
    } catch (e: Throwable) {
        Log.e("OCR", "tesseract: " + e.message, e)
        null
    } finally {
        try { tess?.recycle() } catch (_: Throwable) {}
        try { bmp?.recycle() } catch (_: Throwable) {}
    }
}

private suspend fun runOcrOn(recognizer: TextRecognizer, image: InputImage): Text? {
    return try {
        suspendCancellableCoroutine<Text> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { v: Text -> if (cont.isActive) cont.resume(v) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
    } catch (_: Exception) {
        null
    }
}

private suspend fun ocrPdf(ctx: Context, uri: Uri, maxPages: Int = 20): String? =
    withContext(Dispatchers.IO) {
        var renderer: PdfRenderer? = null
        var pfd2: ParcelFileDescriptor? = null
        var tess: TessBaseAPI? = null
        var tmp: File? = null
        try {
            val root = ensureTessdata(ctx)
            tess = TessBaseAPI()
            if (!tess.init(root.absolutePath, "rus+eng", TessBaseAPI.OcrEngineMode.OEM_LSTM_ONLY)) {
                Log.e("OCR", "ocrPdf: tess.init failed")
                return@withContext null
            }
            val tmpFile = File(ctx.cacheDir, "ocr_pdf_" + System.currentTimeMillis() + ".pdf")
            tmp = tmpFile
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    tmpFile.outputStream().use { out -> fis.copyTo(out) }
                }
            } ?: return@withContext null

            val p = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd2 = p
            renderer = PdfRenderer(p)
            val total = renderer.pageCount
            val pages = total.coerceAtMost(maxPages)
            val sb = StringBuilder()
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            tess.setVariable("user_defined_dpi", "300")
            tess.setVariable("preserve_interword_spaces", "1")
            tess.setVariable("textord_tabfind_find_tables", "1")
            val scale = 3.0f
            Log.d("OCR", "ocrPdf: pages=$pages scale=3.0 psm=SINGLE_BLOCK")
            for (i in 0 until pages) {
                val page = renderer.openPage(i)
                try {
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    tess.setImage(bmp)
                    val pageText = tess.getUTF8Text() ?: ""
                    bmp.recycle()
                    sb.append("=== Страница ").append(i + 1).append(" / ").append(total).append(" ===\n")
                    if (pageText.isBlank()) sb.append("[нет текста]\n")
                    else sb.append(pageText.trim()).append('\n')
                    sb.append('\n')
                } finally {
                    try { page.close() } catch (_: Throwable) {}
                }
            }
            if (total > maxPages) {
                sb.append("[... PDF ").append(total).append(" стр., обработано первых ").append(maxPages).append("]")
            }
            val result = sb.toString().trim()
            if (result.isBlank()) null else result
        } catch (e: Throwable) {
            Log.e("OCR", "ocrPdf: " + e.message, e)
            null
        } finally {
            try { tess?.recycle() } catch (_: Throwable) {}
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd2?.close() } catch (_: Throwable) {}
            try { tmp?.delete() } catch (_: Throwable) {}
        }
    }

suspend fun ocrImage(ctx: Context, uri: Uri): AttachedFile? {
    return try {
        var finalText: String? = null
        var label = ""
        var emoji = "\uD83D\uDCF7"

        val mime = ctx.contentResolver.getType(uri).orEmpty()
        val isPdf = mime.startsWith("application/pdf")

        if (isPdf) {
            emoji = "\uD83D\uDCC4"
            val pdfText = ocrPdf(ctx, uri)
            if (!pdfText.isNullOrBlank()) {
                finalText = pdfText.trim()
                label = "PDF OCR"
            }
        } else {
            val tessText = tesseractOcr(ctx, uri)
            if (!tessText.isNullOrBlank() && tessText.length > 3) {
                finalText = tessText.trim()
                label = tessText.count { it == '\n' }.plus(1).toString() + " строк (tess)"
            } else {
                val image = InputImage.fromFilePath(ctx, uri)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    val pick = runOcrOn(recognizer, image)
                    if (pick != null && pick.text.isNotBlank()) {
                        val sb = StringBuilder()
                        for (block in pick.textBlocks) {
                            for (line in block.lines) sb.append(line.text).append('\n')
                            sb.append('\n')
                        }
                        finalText = sb.toString().trim()
                        label = pick.textBlocks.size.toString() + " блоков (mlkit)"
                    }
                } finally {
                    recognizer.close()
                }
            }
        }

        if (finalText.isNullOrBlank()) return null

        var name = if (isPdf) "ocr_doc.pdf" else "ocr_photo.jpg"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nIdx >= 0) name = c.getString(nIdx) ?: name
            }
        }

        val trimmed = if (finalText.length > 50000) finalText.take(50000) + "\n... [обрезано]" else finalText
        AttachedFile(name = emoji + " " + name, sizeLabel = label, content = trimmed)
    } catch (e: Throwable) {
        Log.e("OCR", "ocrImage: " + e.message, e)
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
