package com.garickbox.deepapk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TextField с поддержкой чипсов [Файл: ...] + fence.
 * В поле ввода [Файл: 💻 bash ✅ exit=0 · 1с] + ```...``` сворачивается
 * в компактный чипс. Полный текст остаётся в value (уходит в API).
 *
 * Реализация: BasicTextField + overlay Box с чипсами.
 * inlineContent в BasicTextField не поддерживается (только в Text).
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
    var tfv by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }

    LaunchedEffect(value) {
        if (tfv.text != value) {
            tfv = TextFieldValue(value, selection = TextRange(value.length))
        }
    }

    Box(modifier = modifier) {
        BasicTextField(
            value = tfv,
            onValueChange = { new: TextFieldValue ->
                tfv = new
                onValueChange(new.text)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp, max = 130.dp),
            maxLines = maxLines,
            textStyle = TextStyle(color = textColor, fontSize = fontSizeSp.sp, lineHeight = lineHeightSp.sp),
            cursorBrush = SolidColor(cursorColor),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (value.isEmpty() && placeholder.isNotBlank()) {
                        Text(placeholder, fontSize = fontSizeSp.sp, color = textColor.copy(alpha = 0.5f))
                    }
                    inner()
                }
            }
        )
        // Overlay: чипсы поверх текста
        val chips = remember(value) { extractChips(value) }
        if (chips.isNotEmpty()) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                chips.forEach { chip ->
                    Box(
                        modifier = Modifier
                            .padding(vertical = 1.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "\uD83D\uDCCE " + chip,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private val CHIP_REGEX = Regex("""\[Файл: ([^\]]+)\]\n```[^\n]*\n([\s\S]*?)\n```""")

private fun extractChips(text: String): List<String> {
    return CHIP_REGEX.findAll(text).map { it.groupValues[1] }.toList()
}
