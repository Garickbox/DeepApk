package com.garickbox.deepapk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    object Divider : MdBlock()
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

    for (line in lines) {
        if (inFence) {
            if (isFenceClose(line, fenceChar, fenceLen)) {
                out.add(MdBlock.Code(fenceLang, codeLines.joinToString("\n")))
                codeLines.clear()
                inFence = false
            } else {
                codeLines.add(line)
            }
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
            continue
        }

        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            flushPara()
            flushQuote()
            continue
        }

        if (trimmed.matches(Regex("^([-*_])\\1{2,}$"))) {
            flushPara()
            flushQuote()
            out.add(MdBlock.Divider)
            continue
        }

        val hMatch = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
        if (hMatch != null) {
            flushPara()
            flushQuote()
            out.add(MdBlock.Heading(hMatch.groupValues[1].length, hMatch.groupValues[2].trim()))
            continue
        }

        if (trimmed.startsWith(">")) {
            flushPara()
            quote.add(trimmed.removePrefix(">").trimStart())
            continue
        } else {
            flushQuote()
        }

        val ulMatch = Regex("^\\s*[-*+]\\s+(.+)$").find(line)
        if (ulMatch != null) {
            flushPara()
            out.add(MdBlock.Bullet("•", ulMatch.groupValues[1]))
            continue
        }

        val olMatch = Regex("^\\s*(\\d+\\.)\\s+(.+)$").find(line)
        if (olMatch != null) {
            flushPara()
            out.add(MdBlock.Bullet(olMatch.groupValues[1], olMatch.groupValues[2]))
            continue
        }

        para.add(line)
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

        // 1. Escaped markdown char
        if (ch == '\\' && i + 1 < text.length && text[i + 1] in "*_`[]()~>#+-!\\") {
            append(text[i + 1])
            i += 2
            continue
        }

        // 2. Inline code `...` — verbatim, no entity/markdown mangling
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

        // 3. HTML <a href="...">label</a>
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

        // 4. Markdown link [label](url)
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

        // 5. Bare URL
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

        // 6. Bold **...** or __...__
        if ((text.startsWith("**", i) || text.startsWith("__", i))) {
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

        // 7. Strikethrough ~~...~~
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

        // 8. Italic *...*
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

        // 9. HTML entities
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

@Composable
fun MarkdownMessageContent(
    content: String,
    textColor: Color,
    isDark: Boolean,
    onCopyCode: (lang: String, code: String) -> Unit,
    onCopyTermux: (lang: String, code: String) -> Unit
) {
    val blocks = remember(content) { parseMarkdownBlocks(content) }
    val linkColor = if (isDark) Color(0xFF7FA2FF) else DeepSeekBlue
    val inlineCodeBg = if (isDark) Color(0xFF282C34) else Color(0xFFECEFF4)
    val quoteBarColor = if (isDark) Color(0xFF3E4554) else Color(0xFFD0D5DD)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = textColor.copy(alpha = 0.15f)
                    )
                }

                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 19.sp
                        2 -> 17.sp
                        3 -> 16.sp
                        else -> 15.sp
                    }
                    val styled = remember(block.text, isDark) {
                        buildCleanInlineString(block.text, linkColor, inlineCodeBg)
                    }
                    SelectionContainer {
                        Text(
                            text = styled,
                            fontSize = size,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            lineHeight = (size.value + 5).sp
                        )
                    }
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
                        SelectionContainer {
                            Text(
                                text = styled,
                                fontSize = 14.sp,
                                color = textColor.copy(alpha = 0.8f),
                                lineHeight = 20.sp
                            )
                        }
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
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 4.dp, end = 8.dp)
                        )
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(
                                text = styled,
                                fontSize = 14.5.sp,
                                color = textColor,
                                lineHeight = 21.sp
                            )
                        }
                    }
                }

                is MdBlock.Paragraph -> {
                    val styled = remember(block.text, isDark) {
                        buildCleanInlineString(block.text, linkColor, inlineCodeBg)
                    }
                    SelectionContainer {
                        Text(
                            text = styled,
                            fontSize = 14.5.sp,
                            color = textColor,
                            lineHeight = 21.5.sp
                        )
                    }
                }

                is MdBlock.Code -> {
                    val isShell = block.lang.lowercase() in listOf("sh", "bash", "zsh", "shell")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF2D3139), RoundedCornerShape(10.dp))
                            .background(Color(0xFF181A1F))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF23262E))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = block.lang.ifBlank { "code" },
                                fontSize = 11.sp,
                                color = Color(0xFF9AA0A6),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "📋 Копировать",
                                fontSize = 11.5.sp,
                                color = Color(0xFF8AB4F8),
                                modifier = Modifier
                                    .clickable { onCopyCode(block.lang, block.code) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            if (!isShell) {
                                Text(
                                    text = "▶ Termux",
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF81C995),
                                    modifier = Modifier
                                        .clickable { onCopyTermux(block.lang, block.code) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = block.code,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                color = Color(0xFFE8EAED),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
