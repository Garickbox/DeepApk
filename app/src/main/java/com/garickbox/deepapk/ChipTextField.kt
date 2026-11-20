package com.garickbox.deepapk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TextField с поддержкой чипсов [Файл: ...] + fence.
 * В поле ввода [Файл: 💻 bash ✅ exit=0 · 1с] + ```...``` сворачивается
 * в компактный чипс. Полный текст остаётся в value (уходит в API).
 */
@Composable
fun ChipTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    fontSizeSp: Float = 15f,
    lineHeightSp: Float = 21f,
    maxLines: Int = 6,
    cursorColor: Color = DeepSeekBlue
) {
    val annotated = remember(value) { buildChipAnnotated(value) }
    val inlineContent = remember(value) { buildInlineContent(value) }
    var tfv by remember { mutableStateOf(TextFieldValue(annotated, selection = TextRange(value.length))) }

    LaunchedEffect(value) {
        if (tfv.text != value) {
            tfv = TextFieldValue(annotated, selection = TextRange(value.length))
        }
    }

    BasicTextField(
        value = tfv,
        onValueChange = { new ->
            tfv = new
            onValueChange(new.text)
        },
        modifier = modifier,
        maxLines = maxLines,
        textStyle = TextStyle(color = textColor, fontSize = fontSizeSp.sp, lineHeight = lineHeightSp.sp),
        cursorBrush = SolidColor(cursorColor),
        inlineContent = inlineContent,
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty() && placeholder.isNotBlank()) {
                    Text(placeholder, fontSize = fontSizeSp.sp, color = textColor.copy(alpha = 0.5f))
                }
                inner()
            }
        }
    )
}

private val CHIP_REGEX = Regex("""\[Файл: ([^\]]+)\]\n```[^\n]*\n([\s\S]*?)\n```""")

private fun buildChipAnnotated(text: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    var last = 0
    CHIP_REGEX.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        val name = m.groupValues[1]
        val chipId = "chip_" + m.range.first
        withStyle(SpanStyle(color = Color.Transparent)) {
            appendInlineContent(chipId, "[" + name + "]")
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
private fun buildInlineContent(text: String): Map<String, InlineTextContent> {
    val map = mutableMapOf<String, InlineTextContent>()
    CHIP_REGEX.findAll(text).forEach { m ->
        val name = m.groupValues[1]
        val chipId = "chip_" + m.range.first
        val chipWidth = (name.length * 7 + 40).coerceIn(80, 320)
        map[chipId] = InlineTextContent(
            Placeholder(
                width = chipWidth.dp,
                height = 24.dp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    "\uD83D\uDCCE " + name,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    return map
}
