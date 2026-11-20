package com.garickbox.deepapk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    }
}
