package com.garickbox.deepapk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val marker: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>, val rawMd: String) : MdBlock()
    object Divider : MdBlock()
}

private val TABLE_SEP_REGEX = Regex("^\\s*\\|?(?:\\s*:?-{2,}:?\\s*\\|)+\\s*:?-{2,}:?\\s*\\|?\\s*$")

private fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.drop(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split("|").map { it.trim() }
}

private val mdBlockCache = LruCache<String, List<MdBlock>>(96)

fun parseMarkdownBlocksCached(input: String): List<MdBlock> {
    if (input.isEmpty()) return emptyList()
    val key = "${input.length}:${input.hashCode()}"
    mdBlockCache.get(key)?.let { return it }
    val parsed = parseMarkdownBlocks(input)
    mdBlockCache.put(key, parsed)
    return parsed
}

fun parseMarkdownBlocks(input: String): List<MdBlock> {
    if (input.isEmpty()) return emptyList()
    val lines = input.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    val para = mutableListOf<String>()
    val quote = mutableListOf<String>()

    var inFence = false
    var fenceChar = '`'
    var fenceLen = 0
    var fenceLang = ""
    val codeLines = mutableListOf<String>()

    fun flushPara() {
        if (para.isNotEmpty()) {
            out.add(MdBlock.Paragraph(para.joinToString("\n")))
            para.clear()
        }
    }

    fun flushQuote() {
        if (quote.isNotEmpty()) {
            out.add(MdBlock.Quote(quote.joinToString("\n")))
            quote.clear()
        }
    }

    fun countLeadingSpaces(s: String): Int {
        var c = 0
        while (c < s.length && s[c] == ' ') c++
        return c
    }

    fun detectFenceOpen(line: String): Triple<Char, Int, String>? {
        val spaces = countLeadingSpaces(line)
        if (spaces > 3 || spaces >= line.length) return null
        val ch = line[spaces]
        if (ch != '`' && ch != '~') return null
        var idx = spaces
        while (idx < line.length && line[idx] == ch) idx++
        val len = idx - spaces
        if (len < 3) return null
        val info = line.substring(idx).trim()
        if (ch == '`' && info.contains('`')) return null
        return Triple(ch, len, info.split(Regex("\\s+")).firstOrNull().orEmpty())
    }

    fun isFenceClose(line: String, ch: Char, minLen: Int): Boolean {
        val spaces = countLeadingSpaces(line)
        if (spaces > 3 || spaces >= line.length) return false
        if (line[spaces] != ch) return false
        var idx = spaces
        while (idx < line.length && line[idx] == ch) idx++
        if (idx - spaces < minLen) return false
        return line.substring(idx).isBlank()
    }

    var li = 0
    while (li < lines.size) {
        val line = lines[li]
        if (inFence) {
            if (isFenceClose(line, fenceChar, fenceLen)) {
                out.add(MdBlock.Code(fenceLang, codeLines.joinToString("\n")))
                codeLines.clear()
                inFence = false
            } else {
                codeLines.add(line)
            }
            li++
            continue
        }

        val open = detectFenceOpen(line)
        if (open != null) {
            flushPara()
            flushQuote()
            inFence = true
            fenceChar = open.first
            fenceLen = open.second
            fenceLang = open.third
            li++
            continue
        }

        if (line.contains("|") && li + 1 < lines.size && TABLE_SEP_REGEX.matches(lines[li + 1])) {
            flushPara()
            flushQuote()
            val rawLines = mutableListOf(line, lines[li + 1])
            val headers = splitTableRow(line)
            val rows = mutableListOf<List<String>>()
            var j = li + 2
            while (j < lines.size && lines[j].contains("|") && lines[j].isNotBlank()) {
                if (detectFenceOpen(lines[j]) != null) break
                rawLines.add(lines[j])
                rows.add(splitTableRow(lines[j]))
                j++
            }
            out.add(MdBlock.Table(headers, rows, rawLines.joinToString("\n")))
            li = j
            continue
        }

        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            flushPara()
            flushQuote()
            li++
            continue
        }

        if (trimmed.matches(Regex("^([-*_])\\1{2,}$"))) {
            flushPara()
            flushQuote()
            out.add(MdBlock.Divider)
            li++
            continue
        }

        val hMatch = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
        if (hMatch != null) {
            flushPara()
            flushQuote()
            out.add(MdBlock.Heading(hMatch.groupValues[1].length, hMatch.groupValues[2].trim()))
            li++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushPara()
            quote.add(trimmed.removePrefix(">").trimStart())
            li++
            continue
        } else {
            flushQuote()
        }

        val ulMatch = Regex("^\\s*[-*+]\\s+(.+)$").find(line)
        if (ulMatch != null) {
            flushPara()
            out.add(MdBlock.Bullet("•", ulMatch.groupValues[1]))
            li++
            continue
        }

        val olMatch = Regex("^\\s*(\\d+\\.)\\s+(.+)$").find(line)
        if (olMatch != null) {
            flushPara()
            out.add(MdBlock.Bullet(olMatch.groupValues[1], olMatch.groupValues[2]))
            li++
            continue
        }

        para.add(line)
        li++
    }

    if (inFence) {
        out.add(MdBlock.Code(fenceLang, codeLines.joinToString("\n")))
    }
    flushPara()
    flushQuote()
    return out
}

private val HTML_BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val HTML_BLOCK_BREAK_REGEX = Regex("</(?:p|div|li|tr|h[1-6])>", RegexOption.IGNORE_CASE)
private val HTML_TAG_SKIP_REGEX = Regex(
    "</?(?:p|div|span|strong|b|em|i|u|s|ul|ol|li|table|thead|tbody|tr|td|th|h[1-6]|hr|section|article)\\b[^>]*>",
    RegexOption.IGNORE_CASE
)
private val HTML_ANCHOR_REGEX = Regex(
    "<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>",
    RegexOption.IGNORE_CASE
)
private val BARE_URL_REGEX = Regex("https?://[^\\s<>\"']+")

private fun decodeEntities(s: String): String = s
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")

private fun cleanHtmlOutsideCode(s: String): String {
    var r = HTML_BR_REGEX.replace(s, "\n")
    r = HTML_BLOCK_BREAK_REGEX.replace(r, "\n")
    r = HTML_TAG_SKIP_REGEX.replace(r, "")
    return r
}

private fun findBalancedParenEnd(text: String, openParenIdx: Int): Int {
    var depth = 0
    var i = openParenIdx
    while (i < text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

fun buildCleanInlineString(
    raw: String,
    linkColor: Color,
    inlineCodeBg: Color
): AnnotatedString = buildAnnotatedString {
    val text = cleanHtmlOutsideCode(raw)
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline
        )
    )
    var i = 0
    while (i < text.length) {
        val ch = text[i]

        if (ch == '\\' && i + 1 < text.length && text[i + 1] in "*_`[]()~>#+-!\\") {
            append(text[i + 1])
            i += 2
            continue
        }

        if (ch == '`') {
            var tickLen = 0
            while (i + tickLen < text.length && text[i + tickLen] == '`') tickLen++
            val needle = "`".repeat(tickLen)
            val close = text.indexOf(needle, i + tickLen)
            if (close > i + tickLen) {
                val inner = text.substring(i + tickLen, close)
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = inlineCodeBg
                    )
                ) {
                    append(inner)
                }
                i = close + tickLen
                continue
            }
        }

        if (ch == '<') {
            val anchor = HTML_ANCHOR_REGEX.matchAt(text, i)
            if (anchor != null) {
                val url = decodeEntities(anchor.groupValues[1].trim())
                val label = decodeEntities(anchor.groupValues[2].replace(Regex("<[^>]+>"), "").trim())
                    .ifBlank { url }
                withLink(LinkAnnotation.Url(url = url, styles = linkStyles)) {
                    append(label)
                }
                i = anchor.range.last + 1
                continue
            }
        }

        if (ch == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i + 1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                val closeParen = findBalancedParenEnd(text, closeBracket + 1)
                if (closeParen > closeBracket + 2) {
                    val urlRaw = text.substring(closeBracket + 2, closeParen).trim()
                        .removeSurrounding("<", ">")
                        .split(Regex("\\s+")).firstOrNull().orEmpty()
                    if (urlRaw.startsWith("http://") || urlRaw.startsWith("https://")) {
                        val label = decodeEntities(text.substring(i + 1, closeBracket))
                        withLink(LinkAnnotation.Url(url = urlRaw, styles = linkStyles)) {
                            append(label.ifBlank { urlRaw })
                        }
                        i = closeParen + 1
                        continue
                    }
                }
            }
        }

        if (ch == 'h' && (text.startsWith("https://", i) || text.startsWith("http://", i))) {
            val m = BARE_URL_REGEX.matchAt(text, i)
            if (m != null) {
                var url = m.value
                while (url.isNotEmpty() && url.last() in ".,;:!?\"'") {
                    url = url.dropLast(1)
                }
                while (url.endsWith(")") && url.count { it == '(' } < url.count { it == ')' }) {
                    url = url.dropLast(1)
                }
                if (url.length > 8) {
                    withLink(LinkAnnotation.Url(url = url, styles = linkStyles)) {
                        append(url)
                    }
                    i += url.length
                    continue
                }
            }
        }

        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val delim = text.substring(i, i + 2)
            val close = text.indexOf(delim, i + 2)
            if (close > i + 2) {
                val inner = decodeEntities(text.substring(i + 2, close))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(inner)
                }
                i = close + 2
                continue
            }
        }

        if (text.startsWith("~~", i)) {
            val close = text.indexOf("~~", i + 2)
            if (close > i + 2) {
                val inner = decodeEntities(text.substring(i + 2, close))
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(inner)
                }
                i = close + 2
                continue
            }
        }

        if (ch == '*' && i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '*') {
            val close = text.indexOf('*', i + 1)
            if (close > i + 1 && text[close - 1] != ' ') {
                val inner = decodeEntities(text.substring(i + 1, close))
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(inner)
                }
                i = close + 1
                continue
            }
        }

        if (ch == '&') {
            val semi = text.indexOf(';', i + 1)
            if (semi in (i + 2)..(i + 8)) {
                val ent = text.substring(i, semi + 1)
                val dec = decodeEntities(ent)
                if (dec != ent) {
                    append(dec)
                    i = semi + 1
                    continue
                }
            }
        }

        append(ch)
        i++
    }
}

private val CODE_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
    "function", "return", "local", "export", "echo", "cd", "grep", "cat", "sed", "awk",
    "find", "tee", "git", "python", "python3", "bash", "sh", "true", "false",
    "fun", "val", "var", "object", "class", "data", "sealed", "import", "package",
    "when", "null", "try", "catch", "finally", "def", "from", "as", "with", "in"
)

private fun highlightCode(code: String, isDark: Boolean): AnnotatedString = buildAnnotatedString {
    val baseColor = if (isDark) Color(0xFFE5E7EB) else Color(0xFF1F2328)
    val commentColor = if (isDark) Color(0xFF7D8590) else Color(0xFF6E7781)
    val stringColor = if (isDark) Color(0xFF7EE787) else Color(0xFF22863A)
    val kwColor = if (isDark) Color(0xFF79C0FF) else Color(0xFF005CC5)
    val varColor = if (isDark) Color(0xFFFFA657) else Color(0xFFB35900)

    var i = 0
    while (i < code.length) {
        val ch = code[i]

        if (ch == '#' || (ch == '/' && i + 1 < code.length && code[i + 1] == '/')) {
            val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
            withStyle(SpanStyle(color = commentColor)) {
                append(code.substring(i, end))
            }
            i = end
            continue
        }

        if (ch == '"' || ch == '\'') {
            var j = i + 1
            while (j < code.length) {
                if (code[j] == '\\' && ch == '"' && j + 1 < code.length) {
                    j += 2
                    continue
                }
                if (code[j] == ch || code[j] == '\n') {
                    if (code[j] == ch) j++
                    break
                }
                j++
            }
            withStyle(SpanStyle(color = stringColor)) {
                append(code.substring(i, j))
            }
            i = j
            continue
        }

        if (ch == '$' && i + 1 < code.length && (code[i + 1].isLetterOrDigit() || code[i + 1] == '_' || code[i + 1] == '{')) {
            var j = i + 1
            if (code[j] == '{') {
                val close = code.indexOf('}', j + 1)
                j = if (close != -1) close + 1 else j + 1
            } else {
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
            }
            withStyle(SpanStyle(color = varColor)) {
                append(code.substring(i, j))
            }
            i = j
            continue
        }

        if (ch.isLetter() || ch == '_') {
            var j = i + 1
            while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
            val word = code.substring(i, j)
            if (word in CODE_KEYWORDS) {
                withStyle(SpanStyle(color = kwColor, fontWeight = FontWeight.Medium)) {
                    append(word)
                }
            } else {
                withStyle(SpanStyle(color = baseColor)) {
                    append(word)
                }
            }
            i = j
            continue
        }

        if (ch == '|') {
            withStyle(SpanStyle(color = varColor, fontWeight = FontWeight.Bold)) {
                append(ch)
            }
            i++
            continue
        }

        withStyle(SpanStyle(color = baseColor)) {
            append(ch)
        }
        i++
    }
}

@Composable
fun MarkdownMessageContent(
    content: String,
    textColor: Color,
    isDark: Boolean,
    fontSizeSp: Int = 15,
    onCopyCode: (lang: String, code: String) -> Unit,
    onCopyTermux: (lang: String, code: String) -> Unit,
    onCopyTable: (text: String, toast: String) -> Unit = { _, _ -> },
    onOpenHtml: (code: String) -> Unit = {}
) {
    val blocks = remember(content) { parseMarkdownBlocksCached(content) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            RenderMdBlock(block, textColor, isDark, fontSizeSp, onCopyCode, onCopyTermux, onCopyTable, onOpenHtml)
        }
    }
}

@Composable
fun RenderMdBlock(
    block: MdBlock,
    textColor: Color,
    isDark: Boolean,
    fontSizeSp: Int = 15,
    onCopyCode: (lang: String, code: String) -> Unit,
    onCopyTermux: (lang: String, code: String) -> Unit,
    onCopyTable: (text: String, toast: String) -> Unit = { _, _ -> },
    onOpenHtml: (code: String) -> Unit = {}
) {
    val linkColor = if (isDark) Color(0xFF7FA2FF) else DeepSeekBlue
    val inlineCodeBg = if (isDark) Color(0xFF282C34) else Color(0xFFECEFF4)
    val quoteBarColor = if (isDark) Color(0xFF3E4554) else Color(0xFFD0D5DD)
    val codeBg = if (isDark) Color(0xFF1A1C22) else Color(0xFFF7F8FA)
    val codeBorder = if (isDark) Color(0xFF2D3139) else Color(0xFFE6E8EE)
    val codeHeaderDivider = if (isDark) Color(0xFF2A2E37) else Color(0xFFEAECF0)
    val codeHeaderText = if (isDark) Color(0xFF9AA0A6) else Color(0xFF6B7280)
            when (block) {
                is MdBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = textColor.copy(alpha = 0.12f)
                    )
                }

                is MdBlock.Heading -> {
                    val delta = when (block.level) {
                        1 -> 4
                        2 -> 3
                        3 -> 2
                        else -> 1
                    }
                    val size = (fontSizeSp + delta).sp
                    val styled = remember(block.text, isDark) {
                        buildCleanInlineString(block.text, linkColor, inlineCodeBg)
                    }
                    Text(
                        text = styled,
                        fontSize = size,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        lineHeight = (size.value + 5).sp
                    )
                }

                is MdBlock.Quote -> {
                    val styled = remember(block.text, isDark) {
                        buildCleanInlineString(block.text, linkColor, inlineCodeBg)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                drawLine(
                                    color = quoteBarColor,
                                    start = Offset(0f, 0f),
                                    end = Offset(0f, size.height),
                                    strokeWidth = 2.5.dp.toPx()
                                )
                            }
                            .padding(start = 10.dp, top = 2.dp, bottom = 2.dp)
                    ) {
                        Text(
                            text = styled,
                            fontSize = (fontSizeSp - 1).sp,
                            color = textColor.copy(alpha = 0.8f),
                            lineHeight = (fontSizeSp + 5).sp
                        )
                    }
                }

                is MdBlock.Bullet -> {
                    val styled = remember(block.text, isDark) {
                        buildCleanInlineString(block.text, linkColor, inlineCodeBg)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = block.marker,
                            fontSize = fontSizeSp.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 4.dp, end = 8.dp)
                        )
                        Text(
                            text = styled,
                            fontSize = fontSizeSp.sp,
                            color = textColor,
                            lineHeight = (fontSizeSp + 7).sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MdBlock.Paragraph -> {
                    val styled = remember(block.text, isDark) {
                        buildCleanInlineString(block.text, linkColor, inlineCodeBg)
                    }
                    Text(
                        text = styled,
                        fontSize = fontSizeSp.sp,
                        color = textColor,
                        lineHeight = (fontSizeSp + 7).sp
                    )
                }

                is MdBlock.Table -> {
                    val cols = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, codeBorder, RoundedCornerShape(12.dp))
                            .background(codeBg)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Таблица", fontSize = 11.5.sp, color = codeHeaderText, modifier = Modifier.weight(1f))
                            Text(
                                "❐ Excel (TSV)",
                                fontSize = 11.5.sp,
                                color = DeepSeekBlue,
                                modifier = Modifier
                                    .clickable {
                                        val tsv = (listOf(block.headers) + block.rows).joinToString("\n") { it.joinToString("\t") }
                                        onCopyTable(tsv, "Таблица скопирована (TSV)")
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                            Text(
                                "❐ MD",
                                fontSize = 11.5.sp,
                                color = codeHeaderText,
                                modifier = Modifier
                                    .clickable { onCopyTable(block.rawMd, "Таблица скопирована (MD)") }
                                    .padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                            )
                        }
                        HorizontalDivider(color = codeHeaderDivider)
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            Row(Modifier.background(if (isDark) Color(0xFF23262E) else Color(0xFFECEEF3))) {
                                for (c in 0 until cols) {
                                    Text(
                                        text = buildCleanInlineString(block.headers.getOrElse(c) { "" }, linkColor, inlineCodeBg),
                                        fontSize = (fontSizeSp - 2).coerceAtLeast(11).sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textColor,
                                        modifier = Modifier.width(140.dp).padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }
                            }
                            block.rows.forEachIndexed { rIdx, row ->
                                HorizontalDivider(color = codeHeaderDivider)
                                Row(
                                    Modifier.background(
                                        if (rIdx % 2 == 1) (if (isDark) Color(0xFF1F2229) else Color(0xFFF3F4F7))
                                        else Color.Transparent
                                    )
                                ) {
                                    for (c in 0 until cols) {
                                        Text(
                                            text = buildCleanInlineString(row.getOrElse(c) { "" }, linkColor, inlineCodeBg),
                                            fontSize = (fontSizeSp - 2).coerceAtLeast(11).sp,
                                            color = textColor,
                                            modifier = Modifier.width(140.dp).padding(horizontal = 10.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is MdBlock.Code -> {
                    val totalLines = remember(block.code) { block.code.count { it == '\n' } + 1 }
                    val isHuge = totalLines > 28 || block.code.length > 3500
                    var codeExpanded by remember(block.code.hashCode(), block.code.length) { mutableStateOf(false) }
                    val shownCode = remember(block.code, isHuge, codeExpanded) {
                        if (!isHuge || codeExpanded) block.code
                        else block.code.lineSequence().take(24).joinToString("\n")
                    }
                    val highlighted = remember(shownCode, isDark) {
                        if (shownCode.length > 12000) AnnotatedString(shownCode) else highlightCode(shownCode, isDark)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, codeBorder, RoundedCornerShape(14.dp))
                            .background(codeBg)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = block.lang.ifBlank { "code" },
                                fontSize = 12.sp,
                                color = codeHeaderText,
                                modifier = Modifier.weight(1f)
                            )
                            val langLow = block.lang.trim().lowercase()
                            if (langLow in setOf("html", "htm", "svg")) {
                                Text(
                                    text = "🌐 Открыть",
                                    fontSize = 12.sp,
                                    color = DeepSeekBlue,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clickable { onOpenHtml(block.code) }
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            } else if (TermuxBridge.isRunnableCodeBlock(block.lang, block.code)) {
                                Text(
                                    text = "▶ Termux",
                                    fontSize = 12.sp,
                                    color = DeepSeekBlue,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clickable { onCopyTermux(block.lang, block.code) }
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "❐ Копировать",
                                fontSize = 12.sp,
                                color = codeHeaderText,
                                modifier = Modifier
                                    .clickable { onCopyCode(block.lang, block.code) }
                                    .padding(start = 6.dp, top = 2.dp, bottom = 2.dp)
                            )
                        }
                        HorizontalDivider(color = codeHeaderDivider, thickness = 1.dp)
                        Text(
                            text = highlighted,
                            fontSize = (fontSizeSp - 2.5f).coerceAtLeast(11f).sp,
                            lineHeight = (fontSizeSp + 3.5f).sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                        if (isHuge) {
                            HorizontalDivider(color = codeHeaderDivider)
                            Text(
                                text = if (codeExpanded) "▲ Свернуть ($totalLines стр.)"
                                else "▼ Показать весь код (ещё ${(totalLines - 24).coerceAtLeast(1)} стр.)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = DeepSeekBlue,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { codeExpanded = !codeExpanded }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
    }
}
