package com.garickbox.deepapk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Диалог «Якоря сообщений». Показывает пронумерованный список user-сообщений
 * и по клику отдаёт msgIdx наружу через onSelect (скролл делает ChatScreen).
 */
@Composable
internal fun AnchorsDialog(
    anchors: List<Pair<Int, Message>>,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSelect: (msgIdx: Int) -> Unit
) {
    val dividerCol = if (isDark) Color(0xFF2B2F38) else Color(0xFFF0F1F4)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isDark) Color(0xFF1F2229) else Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Якоря сообщений (${anchors.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    anchors.forEachIndexed { num, (msgIdx, msg) ->
                        val preview = msg.content.trim().replace("\n", " ").take(90)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(msgIdx) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "#${num + 1}",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepSeekBlue,
                                modifier = Modifier.width(34.dp)
                            )
                            Text(
                                preview,
                                fontSize = 13.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (num < anchors.lastIndex) HorizontalDivider(color = dividerCol)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Закрыть")
                }
            }
        }
    }
}

/**
 * Диалог выбора исполняемого блока кода для Termux.
 * Показывает язык, размер и первую непустую строку каждого блока.
 */
@Composable
internal fun CodeBlockPickerDialog(
    blocks: List<MdBlock.Code>,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onPick: (MdBlock.Code) -> Unit
) {
    val dividerCol = if (isDark) Color(0xFF2B2F38) else Color(0xFFF0F1F4)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isDark) Color(0xFF1F2229) else Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Выберите блок для Termux",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    blocks.forEachIndexed { idx, b ->
                        val firstLine = b.code.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(b) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                "Блок #${idx + 1} · ${b.lang.ifBlank { "code" }} (${b.code.lines().size} стр.)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = DeepSeekBlue
                            )
                            if (firstLine.isNotBlank()) {
                                Text(
                                    firstLine,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (idx < blocks.lastIndex) HorizontalDivider(color = dividerCol)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Отмена")
                }
            }
        }
    }
}