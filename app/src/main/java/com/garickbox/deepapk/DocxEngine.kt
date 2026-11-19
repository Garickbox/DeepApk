package com.garickbox.deepapk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

data class ParsedDocReport(
    val title: String,
    val bodyHtml: String,
    val rawSource: String,
    val isComplete: Boolean,
    val tablesCount: Int,
    val svgCount: Int
)

private val DOC_REPORT_FULL = Regex("<doc_report\\b([^>]*)>([\\s\\S]*?)</doc_report>", RegexOption.IGNORE_CASE)
private val DOC_REPORT_OPEN = Regex("<doc_report\\b([^>]*)>([\\s\\S]*)$", RegexOption.IGNORE_CASE)
private val HTML_FENCE_DOC = Regex("```html\\s*([\\s\\S]*?<div\\s+id=[\"']doc[\"'][\\s\\S]*?)```", RegexOption.IGNORE_CASE)

fun extractDocReport(content: String): ParsedDocReport? {
    val mFull = DOC_REPORT_FULL.find(content)
    if (mFull != null) {
        val attrs = mFull.groupValues[1]
        val title = Regex("title=[\"']([^\"']+)[\"']").find(attrs)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "Отчёт" }
        val body = mFull.groupValues[2].trim()
        return ParsedDocReport(
            title = title,
            bodyHtml = body,
            rawSource = mFull.value,
            isComplete = true,
            tablesCount = Regex("<table\\b", RegexOption.IGNORE_CASE).findAll(body).count(),
            svgCount = Regex("<svg\\b", RegexOption.IGNORE_CASE).findAll(body).count()
        )
    }
    val mOpen = DOC_REPORT_OPEN.find(content)
    if (mOpen != null) {
        val attrs = mOpen.groupValues[1]
        val title = Regex("title=[\"']([^\"']+)[\"']").find(attrs)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "Отчёт" }
        val body = mOpen.groupValues[2]
        return ParsedDocReport(title, body, mOpen.value, false, 0, 0)
    }
    val mHtml = HTML_FENCE_DOC.find(content)
    if (mHtml != null) {
        val fullHtml = mHtml.groupValues[1]
        val title = Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(fullHtml)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "Документ" }
        val docStart = Regex("<div\\s+id=[\"']doc[\"'][^>]*>", RegexOption.IGNORE_CASE).find(fullHtml)?.range?.last
        val body = if (docStart != null) {
            val after = fullHtml.substring(docStart + 1)
            val scriptIdx = after.indexOf("<script", ignoreCase = true).let { if (it >= 0) it else after.length }
            after.substring(0, scriptIdx).replace(Regex("</div>\\s*</div>\\s*$"), "").trim()
        } else fullHtml
        return ParsedDocReport(
            title = title,
            bodyHtml = body,
            rawSource = fullHtml,
            isComplete = true,
            tablesCount = Regex("<table\\b", RegexOption.IGNORE_CASE).findAll(body).count(),
            svgCount = Regex("<svg\\b", RegexOption.IGNORE_CASE).findAll(body).count()
        )
    }
    return null
}

fun stripDocReportForMarkdown(content: String): String {
    var out = DOC_REPORT_FULL.replace(content, "").trim()
    out = DOC_REPORT_OPEN.replace(out, "").trim()
    return out
}

object DocxEngine {
    private const val CH_ID = "deepapk_docs"

    private fun xmlEsc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun unescapeHtml(s: String): String {
        var r = s
            .replace("&nbsp;", "\u00A0")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&times;", "×")
            .replace("&minus;", "−")
            .replace("&deg;", "°")
            .replace("&plusmn;", "±")
            .replace("&le;", "≤")
            .replace("&ge;", "≥")
            .replace("&approx;", "≈")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        r = Regex("&#(\\d+);").replace(r) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
        r = Regex("&#x([0-9a-fA-F]+);").replace(r) { m -> m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value }
        return r
    }

    private sealed class HToken {
        data class Open(val tag: String, val attrs: String) : HToken()
        data class Close(val tag: String) : HToken()
        data class Text(val text: String) : HToken()
        data class Svg(val markup: String, val caption: String) : HToken()
    }

    private fun tokenizeHtml(raw: String): List<HToken> {
        val clean = raw.replace(Regex("<!--[\\s\\S]*?-->"), "")
        val out = mutableListOf<HToken>()
        var i = 0
        while (i < clean.length) {
            if (clean[i] == '<') {
                if (clean.startsWith("<svg", i, ignoreCase = true)) {
                    val end = clean.indexOf("</svg>", i, ignoreCase = true)
                    if (end > i) {
                        val svgMarkup = clean.substring(i, end + 6)
                        val title = Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(svgMarkup)?.groupValues?.get(1)?.trim().orEmpty()
                        out.add(HToken.Svg(svgMarkup, title))
                        i = end + 6
                        continue
                    }
                }
                val gt = clean.indexOf('>', i)
                if (gt < 0) {
                    out.add(HToken.Text(clean.substring(i)))
                    break
                }
                val inner = clean.substring(i + 1, gt).trim()
                if (inner.startsWith("/")) {
                    out.add(HToken.Close(inner.drop(1).trim().lowercase().substringBefore(' ')))
                } else {
                    val selfClose = inner.endsWith("/")
                    val body = if (selfClose) inner.dropLast(1).trim() else inner
                    val sp = body.indexOfFirst { it.isWhitespace() }
                    val tag = (if (sp < 0) body else body.substring(0, sp)).lowercase()
                    val attrs = if (sp < 0) "" else body.substring(sp + 1)
                    out.add(HToken.Open(tag, attrs))
                    if (selfClose || tag == "br" || tag == "hr") out.add(HToken.Close(tag))
                }
                i = gt + 1
            } else {
                val lt = clean.indexOf('<', i)
                val seg = if (lt < 0) clean.substring(i) else clean.substring(i, lt)
                if (seg.isNotEmpty()) out.add(HToken.Text(seg))
                i = if (lt < 0) clean.length else lt
            }
        }
        return out
    }

    private fun inlineRunsFromTokens(tokens: List<HToken>, baseSzHalfPt: String? = null, forceBold: Boolean = false, forceCaps: Boolean = false): String {
        val sb = StringBuilder()
        var b = if (forceBold) 1 else 0
        var itl = 0
        var u = 0
        var sub = 0
        var sup = 0
        var fill = 0
        var mono = 0
        val stack = mutableListOf<String>()

        fun rPrXml(): String {
            val p = StringBuilder()
            if (b > 0) p.append("<w:b/><w:bCs/>")
            if (itl > 0) p.append("<w:i/><w:iCs/>")
            if (u > 0) p.append("<w:u w:val=\"single\"/>")
            if (sub > 0) p.append("<w:vertAlign w:val=\"subscript\"/>")
            else if (sup > 0) p.append("<w:vertAlign w:val=\"superscript\"/>")
            if (forceCaps) p.append("<w:caps/>")
            if (fill > 0) p.append("<w:highlight w:val=\"yellow\"/>")
            if (mono > 0) p.append("<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F4F4F4\"/>")
            if (baseSzHalfPt != null) p.append("<w:sz w:val=\"$baseSzHalfPt\"/><w:szCs w:val=\"$baseSzHalfPt\"/>")
            return if (p.isEmpty()) "" else "<w:rPr>$p</w:rPr>"
        }

        fun pushTag(t: String, attrs: String) {
            stack.add(t)
            when (t) {
                "b", "strong" -> b++
                "i", "em" -> itl++
                "u" -> u++
                "sub" -> sub++
                "sup" -> sup++
                "code", "kbd", "samp", "tt", "pre" -> mono++
                "span" -> if (Regex("\\b(fill|ans)\\b").containsMatchIn(attrs)) {
                    if (attrs.contains("fill")) fill++
                    if (attrs.contains("ans")) b++
                }
            }
        }

        fun popTag(t: String) {
            val idx = stack.lastIndexOf(t)
            if (idx < 0) return
            stack.removeAt(idx)
            when (t) {
                "b", "strong" -> if (b > 0) b--
                "i", "em" -> if (itl > 0) itl--
                "u" -> if (u > 0) u--
                "sub" -> if (sub > 0) sub--
                "sup" -> if (sup > 0) sup--
                "code", "kbd", "samp", "tt", "pre" -> if (mono > 0) mono--
                "span" -> {
                    if (fill > 0) fill--
                }
            }
        }

        for (tk in tokens) {
            when (tk) {
                is HToken.Open -> {
                    if (tk.tag == "br") sb.append("<w:r>${rPrXml()}<w:br/></w:r>")
                    else pushTag(tk.tag, tk.attrs)
                }
                is HToken.Close -> popTag(tk.tag)
                is HToken.Text -> {
                    val txt = unescapeHtml(tk.text).replace(Regex("[\\r\\n\\t]+"), " ")
                    if (txt.isNotEmpty()) sb.append("<w:r>${rPrXml()}<w:t xml:space=\"preserve\">${xmlEsc(txt)}</w:t></w:r>")
                }
                is HToken.Svg -> {}
            }
        }
        return sb.toString()
    }

    private suspend fun renderSvgToPng(ctx: Context, svg: String): ByteArray? {
        val wAttr = Regex("\\bwidth=[\"'](\\d+)").find(svg)?.groupValues?.get(1)?.toIntOrNull() ?: 560
        val hAttr = Regex("\\bheight=[\"'](\\d+)").find(svg)?.groupValues?.get(1)?.toIntOrNull() ?: 260
        val w = (wAttr * 2).coerceIn(320, 1200)
        val h = (hAttr * 2).coerceIn(180, 900)
        return withTimeoutOrNull(3500L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<ByteArray?> { cont ->
                    try {
                        val wv = WebView(ctx)
                        wv.settings.javaScriptEnabled = false
                        wv.setBackgroundColor(android.graphics.Color.WHITE)
                        wv.measure(
                            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
                        )
                        wv.layout(0, 0, w, h)
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!cont.isActive) return@postDelayed
                                    try {
                                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                        val c = Canvas(bmp)
                                        c.drawColor(android.graphics.Color.WHITE)
                                        wv.draw(c)
                                        val baos = ByteArrayOutputStream()
                                        bmp.compress(Bitmap.CompressFormat.PNG, 95, baos)
                                        bmp.recycle()
                                        wv.destroy()
                                        cont.resume(baos.toByteArray())
                                    } catch (_: Exception) {
                                        wv.destroy()
                                        cont.resume(null)
                                    }
                                }, 140L)
                            }
                        }
                        val html = "<!DOCTYPE html><html><body style=\"margin:0;padding:8px;background:#fff;display:flex;justify-content:center;align-items:center;\">$svg</body></html>"
                        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    suspend fun buildDocxBytes(ctx: Context, report: ParsedDocReport): ByteArray {
        val tokens = tokenizeHtml(report.bodyHtml)
        val images = mutableListOf<Pair<String, ByteArray>>()
        val bodyXml = StringBuilder()

        var i = 0
        var listOrdered = false

        fun attrClass(attrs: String): String =
            Regex("class=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1).orEmpty()

        fun collectUntil(closeTag: String, startIdx: Int): Pair<List<HToken>, Int> {
            val buf = mutableListOf<HToken>()
            var depth = 1
            var k = startIdx
            while (k < tokens.size && depth > 0) {
                val tk = tokens[k]
                if (tk is HToken.Open && tk.tag == closeTag) depth++
                else if (tk is HToken.Close && tk.tag == closeTag) {
                    depth--
                    if (depth == 0) return buf to (k + 1)
                }
                buf.add(tk)
                k++
            }
            return buf to k
        }

        while (i < tokens.size) {
            val tk = tokens[i]
            when (tk) {
                is HToken.Svg -> {
                    val png = renderSvgToPng(ctx, tk.markup)
                    if (png != null) {
                        val imgNum = images.size + 1
                        val rId = "rIdImg$imgNum"
                        images.add("word/media/image$imgNum.png" to png)
                        val cx = 5400000L // ~15cm EMU
                        val cy = 2600000L
                        bodyXml.append(
                            "<w:p><w:pPr><w:jc w:val=\"center\"/><w:ind w:firstLine=\"0\"/></w:pPr>" +
                                "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                                "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$imgNum\" name=\"Img$imgNum\"/>" +
                                "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                                "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                                "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                                "<pic:nvPicPr><pic:cNvPr id=\"$imgNum\" name=\"Img$imgNum\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                                "<pic:blipFill><a:blip r:embed=\"$rId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
                                "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
                        )
                    } else {
                        bodyXml.append("<w:p><w:pPr><w:jc w:val=\"center\"/><w:ind w:firstLine=\"0\"/></w:pPr><w:r><w:t>[Рисунок: ${xmlEsc(tk.caption.ifBlank { "схема" })}]</w:t></w:r></w:p>")
                    }
                    i++
                }
                is HToken.Open -> {
                    val cls = attrClass(tk.attrs)
                    when (tk.tag) {
                        "div" -> {
                            if (Regex("\\bpagebreak\\b").containsMatchIn(cls)) {
                                bodyXml.append("<w:p><w:pPr><w:rPr><w:sz w:val=\"2\"/><w:szCs w:val=\"2\"/></w:rPr></w:pPr><w:r><w:rPr><w:sz w:val=\"2\"/><w:szCs w:val=\"2\"/></w:rPr><w:br w:type=\"page\"/></w:r></w:p>")
                                val (_, next) = collectUntil("div", i + 1)
                                i = next
                            } else {
                                i++
                            }
                        }
                        "h1", "h2", "h3" -> {
                            val (inner, next) = collectUntil(tk.tag, i + 1)
                            val jc = if (tk.tag == "h3" && !cls.contains("center")) "left" else "center"
                            val runs = inlineRunsFromTokens(inner, "28", forceBold = true, forceCaps = (tk.tag == "h1"))
                            bodyXml.append("<w:p><w:pPr><w:keepNext/><w:jc w:val=\"$jc\"/><w:spacing w:before=\"240\" w:after=\"120\"/><w:ind w:firstLine=\"0\"/></w:pPr>$runs</w:p>")
                            i = next
                        }
                        "p" -> {
                            val (inner, next) = collectUntil("p", i + 1)
                            val hasSvg = inner.filterIsInstance<HToken.Svg>().isNotEmpty()
                            if (hasSvg) {
                                inner.filterIsInstance<HToken.Svg>().forEach { s ->
                                    val png = renderSvgToPng(ctx, s.markup)
                                    if (png != null) {
                                        val imgNum = images.size + 1
                                        val rId = "rIdImg$imgNum"
                                        images.add("word/media/image$imgNum.png" to png)
                                        val cx = 5400000L
                                        val cy = 2600000L
                                        bodyXml.append(
                                            "<w:p><w:pPr><w:jc w:val=\"center\"/><w:ind w:firstLine=\"0\"/></w:pPr>" +
                                                "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                                                "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$imgNum\" name=\"Img$imgNum\"/>" +
                                                "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                                                "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                                                "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                                                "<pic:nvPicPr><pic:cNvPr id=\"$imgNum\" name=\"Img$imgNum\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                                                "<pic:blipFill><a:blip r:embed=\"$rId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                                                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                                                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
                                                "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
                                        )
                                    }
                                }
                            } else {
                                val isNoInd = cls.contains("noind") || cls.contains("center") || cls.contains("right") || cls.contains("formula")
                                val jc = when {
                                    cls.contains("center") || cls.contains("formula") -> "center"
                                    cls.contains("right") -> "right"
                                    else -> "left"
                                }
                                val ind = if (isNoInd) "0" else "709"
                                val runs = inlineRunsFromTokens(inner)
                                bodyXml.append("<w:p><w:pPr><w:ind w:firstLine=\"$ind\"/><w:jc w:val=\"$jc\"/></w:pPr>$runs</w:p>")
                            }
                            i = next
                        }
                        "ul", "ol" -> {
                            listOrdered = tk.tag == "ol"
                            i++
                        }
                        "li" -> {
                            val (inner, next) = collectUntil("li", i + 1)
                            val numId = if (listOrdered) "1" else "2"
                            val runs = inlineRunsFromTokens(inner)
                            bodyXml.append("<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$numId\"/></w:numPr><w:ind w:left=\"1080\" w:hanging=\"360\"/><w:jc w:val=\"left\"/></w:pPr>$runs</w:p>")
                            i = next
                        }
                        "table" -> {
                            val (inner, next) = collectUntil("table", i + 1)
                            bodyXml.append(buildTableXml(inner))
                            i = next
                        }
                        "pre" -> {
                            val (inner, next) = collectUntil("pre", i + 1)
                            val sb = StringBuilder()
                            inner.forEach { it2 ->
                                when (it2) {
                                    is HToken.Text -> sb.append(unescapeHtml(it2.text))
                                    is HToken.Open -> if (it2.tag == "br") sb.append("\n")
                                    is HToken.Close -> {}
                                    is HToken.Svg -> {}
                                }
                            }
                            val lines = sb.toString()
                                .replace("\r\n", "\n")
                                .replace('\r', '\n')
                                .split('\n')
                            lines.forEach { line ->
                                val t = line.replace("\t", "    ")
                                val runText = if (t.isEmpty()) " " else t
                                bodyXml.append(
                                    "<w:p><w:pPr><w:ind w:firstLine=\"0\" w:left=\"360\"/><w:jc w:val=\"left\"/>" +
                                        "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F5F5F5\"/></w:pPr>" +
                                        "<w:r><w:rPr><w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/></w:rPr>" +
                                        "<w:t xml:space=\"preserve\">${xmlEsc(runText)}</w:t></w:r></w:p>"
                                )
                            }
                            i = next
                        }
                        else -> i++
                    }
                }
                else -> i++
            }
        }

        val docXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
<w:body>$bodyXml<w:sectPr><w:footerReference w:type="default" r:id="rIdFooter"/><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="850" w:bottom="1134" w:left="1701" w:header="708" w:footer="708" w:gutter="0"/><w:docGrid w:linePitch="360"/></w:sectPr></w:body></w:document>"""

        val imgRels = images.indices.joinToString("") { idx ->
            val n = idx + 1
            "<Relationship Id=\"rIdImg$n\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image$n.png\"/>"
        }
        val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rIdNumbering" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
<Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
$imgRels</Relationships>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="png" ContentType="image/png"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
</Types>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:cs="Times New Roman"/><w:sz w:val="28"/><w:szCs w:val="28"/><w:lang w:val="ru-RU"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:line="360" w:lineRule="auto" w:before="0" w:after="0"/><w:jc w:val="left"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
</w:styles>"""

        val numberingXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:lvlJc w:val="left"/></w:lvl></w:abstractNum>
<w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="–"/><w:lvlJc w:val="left"/></w:lvl></w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
<w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
</w:numbering>"""

        val footerXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:p><w:pPr><w:jc w:val="center"/><w:ind w:firstLine="0"/></w:pPr>
<w:r><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman"/><w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr><w:fldChar w:fldCharType="begin"/></w:r>
<w:r><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman"/><w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>
<w:r><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman"/><w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr><w:fldChar w:fldCharType="end"/></w:r>
</w:p></w:ftr>"""

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun put(name: String, data: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
            put("[Content_Types].xml", contentTypes.toByteArray(Charsets.UTF_8))
            put("_rels/.rels", rootRels.toByteArray(Charsets.UTF_8))
            put("word/document.xml", docXml.toByteArray(Charsets.UTF_8))
            put("word/_rels/document.xml.rels", docRels.toByteArray(Charsets.UTF_8))
            put("word/styles.xml", stylesXml.toByteArray(Charsets.UTF_8))
            put("word/numbering.xml", numberingXml.toByteArray(Charsets.UTF_8))
            put("word/footer1.xml", footerXml.toByteArray(Charsets.UTF_8))
            images.forEach { (name, bytes) -> put(name, bytes) }
        }
        return baos.toByteArray()
    }

    private fun buildTableXml(tokens: List<HToken>): String {
        val sb = StringBuilder()
        sb.append(
            "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>" +
                "<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>" +
                "<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>" +
                "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>" +
                "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>" +
                "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>" +
                "<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>" +
                "</w:tblBorders><w:tblLayout w:type=\"autofit\"/><w:tblCellMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"70\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:right w:w=\"70\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr>"
        )
        var i = 0
        while (i < tokens.size) {
            val tk = tokens[i]
            if (tk is HToken.Open && tk.tag == "tr") {
                sb.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
                i++
                while (i < tokens.size) {
                    val ct = tokens[i]
                    if (ct is HToken.Close && ct.tag == "tr") { i++; break }
                    if (ct is HToken.Open && (ct.tag == "td" || ct.tag == "th")) {
                        val isTh = ct.tag == "th"
                        val isCenter = isTh || ct.attrs.contains("c")
                        val cellBuf = mutableListOf<HToken>()
                        i++
                        while (i < tokens.size) {
                            val sub = tokens[i]
                            if (sub is HToken.Close && sub.tag == ct.tag) { i++; break }
                            cellBuf.add(sub)
                            i++
                        }
                        val runs = inlineRunsFromTokens(cellBuf, "24", forceBold = isTh)
                        val jc = if (isCenter) "center" else "left"
                        sb.append("<w:tc><w:tcPr><w:vAlign w:val=\"top\"/></w:tcPr><w:p><w:pPr><w:spacing w:line=\"240\" w:lineRule=\"auto\" w:before=\"0\" w:after=\"0\"/><w:ind w:firstLine=\"0\"/><w:jc w:val=\"$jc\"/><w:rPr><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/></w:rPr></w:pPr>$runs</w:p></w:tc>")
                    } else i++
                }
                sb.append("</w:tr>")
            } else i++
        }
        sb.append("</w:tbl><w:p><w:pPr><w:ind w:firstLine=\"0\"/><w:rPr><w:sz w:val=\"2\"/><w:szCs w:val=\"2\"/></w:rPr></w:pPr></w:p>")
        return sb.toString()
    }

    fun extractStandaloneHtmlPage(content: String): Pair<String, String>? {
        val rx = Regex("```(?:html|htm)\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        for (m in rx.findAll(content)) {
            val code = m.groupValues[1].trim()
            if (code.contains("<!doctype", ignoreCase = true) || code.contains("<html", ignoreCase = true) || code.contains("<body", ignoreCase = true)) {
                val t = Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(code)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "Работа" }
                return t to code
            }
        }
        return null
    }

    fun saveHtmlSilently(ctx: Context, title: String, html: String): File? {
        return try {
            val base = title.replace(Regex("[^\\wа-яА-ЯёЁ._-]"), "_").trim('_').take(50).ifBlank { "document" }
            val hash = (html.hashCode() and 0xFFFF).toString(16)
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            File(dir, "${base}_$hash.html").apply { writeText(html, Charsets.UTF_8) }
        } catch (_: Exception) { null }
    }

    fun openHtmlInBrowser(ctx: Context, title: String, html: String) {
        val f = saveHtmlSilently(ctx, title, html) ?: return
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(i, "Открыть в браузере"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Нет браузера для открытия HTML: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun buildPreviewHtml(report: ParsedDocReport): String {
        if (report.rawSource.contains("<!doctype", ignoreCase = true) || report.rawSource.contains("<html", ignoreCase = true)) {
            return report.rawSource
        }
        val safeTitle = report.title.replace("<", "&lt;").replace(">", "&gt;")
        return """<!DOCTYPE html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>$safeTitle</title>
<style>:root{--page-w:210mm;}html,body{margin:0;padding:0;background:#e8eaed;font-family:'Times New Roman',Times,serif;}
.toolbar{position:sticky;top:0;z-index:100;background:#1f3a5f;color:#fff;padding:10px 14px;display:flex;flex-wrap:wrap;gap:8px;align-items:center;font-family:Arial,sans-serif;}
.toolbar .ttl{font-size:13px;font-weight:600;margin-right:auto;}
.toolbar button{font-size:12px;font-weight:600;padding:8px 12px;border:none;border-radius:6px;background:#4a90d9;color:#fff;cursor:pointer;}
.sheet{max-width:var(--page-w);margin:14px auto 50px;background:#fff;box-shadow:0 4px 24px rgba(0,0,0,.18);padding:20mm 15mm 20mm 30mm;box-sizing:border-box;}
#doc{font-family:'Times New Roman',Times,serif;font-size:14pt;line-height:1.5;color:#000;}
#doc p{margin:0;text-indent:1.25cm;text-align:left;line-height:1.5;}#doc p.noind{text-indent:0;}#doc p.center{text-indent:0;text-align:center;}#doc p.right{text-indent:0;text-align:right;}
#doc p.formula{text-indent:0;text-align:center;font-style:italic;margin:6pt 0;}
#doc h1,#doc h2{font-size:14pt;font-weight:bold;text-align:center;text-indent:0;margin:12pt 0 8pt;text-transform:uppercase;}
#doc h3{font-size:14pt;font-weight:bold;text-align:left;text-indent:0;margin:10pt 0 6pt;}
#doc ul,#doc ol{margin:0;padding-left:2.2cm;}#doc table{border-collapse:collapse;width:100%;font-size:11pt;margin:8pt 0;}
#doc th,#doc td{border:1px solid #000;padding:3pt 4pt;vertical-align:top;}#doc th,#doc td.c{text-align:center;}
.pagebreak{page-break-before:always;break-before:page;}.fill{background:#fff3a0;padding:0 2px;}.ans{font-weight:bold;}
@media print{.toolbar{display:none!important;}body{background:#fff;}.sheet{margin:0;box-shadow:none;padding:0;}@page{size:A4;margin:20mm 15mm 20mm 30mm;}}</style></head>
<body><div class="toolbar"><span class="ttl">$safeTitle</span><button onclick="window.print()">🖨 PDF / Печать</button></div>
<div class="sheet"><div id="doc">${report.bodyHtml}</div></div></body></html>"""
    }

    suspend fun saveExportAndNotify(ctx: Context, fileName: String, mime: String, bytes: ByteArray): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                val safeName = fileName.replace(Regex("[^\\wа-яА-ЯёЁ._-]"), "_").take(80)
                var openUri: Uri? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    openUri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (openUri != null) {
                        ctx.contentResolver.openOutputStream(openUri)?.use { it.write(bytes) }
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, safeName).writeBytes(bytes)
                }
                val expDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                val cacheFile = File(expDir, safeName).apply { writeBytes(bytes) }
                val shareUri = openUri ?: FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", cacheFile)

                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Сохранено в Загрузки: $safeName", Toast.LENGTH_LONG).show()
                    postDocNotification(ctx, safeName, shareUri, mime)
                }
                shareUri
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
                }
                null
            }
        }
    }

    private fun postDocNotification(ctx: Context, title: String, uri: Uri, mime: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CH_ID, "Документы DeepApk", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(ctx, title.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CH_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Файл готов: $title")
                .setContentText("Нажмите, чтобы открыть документ")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(title.hashCode(), n)
        } catch (_: SecurityException) {} catch (_: Exception) {}
    }

    fun openUri(ctx: Context, uri: Uri, mime: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(i, "Открыть документ"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Нет приложения для открытия: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
