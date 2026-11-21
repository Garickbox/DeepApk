package com.garickbox.deepapk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    attachments: List<AttachedFile>,
    onRemoveAttachment: (Int) -> Unit,
    onInspectAttachment: (AttachedFile) -> Unit,
    settings: AppSettings,
    streaming: Boolean,
    isDark: Boolean,
    showAttachMenu: Boolean,
    onShowAttachMenuChange: (Boolean) -> Unit,
    onToggleThinking: () -> Unit,
    onToggleSearch: () -> Unit,
    onPhotoOcr: () -> Unit,
    onCameraOcr: () -> Unit,
    onPickFile: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit
) {
    val cardBg = if (isDark) Color(0xFF1A1C22) else Color(0xFFFFFFFF)
    val cardBorder = if (isDark) Color(0xFF2D3139) else Color(0xFFE6E8EE)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .shadow(2.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, cardBorder, RoundedCornerShape(24.dp))
            .background(cardBg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEachIndexed { idx, f ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (f.name.startsWith("\uD83D\uDCBB ")) DeepSeekBlue.copy(alpha = 0.45f) else cardBorder),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onInspectAttachment(f) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val chipLabel = if (f.name.startsWith("\uD83D\uDCBB ")) "${f.name} (${f.sizeLabel})" else "📄 ${f.name} (${f.sizeLabel})"
                            Text(
                                chipLabel,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 220.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "✕",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { onRemoveAttachment(idx) }
                            )
                        }
                    }
                }
            }
        }

        ChipTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp, max = 130.dp),
            placeholder = "Напишите сообщение…",
            textColor = MaterialTheme.colorScheme.onBackground,
            fontSizeSp = 15f,
            lineHeightSp = 21f,
            maxLines = 6,
            cursorColor = DeepSeekBlue
        )

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModePill(
                label = "⚛ Рассуждение",
                active = settings.thinkingEnabled,
                isDark = isDark,
                onClick = onToggleThinking
            )
            if (settings.provider in listOf("deepseek", "gemini")) {
                Spacer(Modifier.width(8.dp))
                ModePill(
                    label = "🌐 Поиск",
                    active = settings.webSearchEnabled,
                    isDark = isDark,
                    onClick = onToggleSearch
                )
            }
            Spacer(Modifier.weight(1f))

            Box {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), CircleShape)
                        .clickable { onShowAttachMenuChange(true) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "+",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded = showAttachMenu,
                    onDismissRequest = { onShowAttachMenuChange(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text("\uD83D\uDCF7  Фото / PDF (OCR)") },
                        onClick = { onShowAttachMenuChange(false); onPhotoOcr() }
                    )
                    DropdownMenuItem(
                        text = { Text("\uD83D\uDCF8  Камера (OCR)") },
                        onClick = { onShowAttachMenuChange(false); onCameraOcr() }
                    )
                    DropdownMenuItem(
                        text = { Text("\uD83D\uDCCE  Прикрепить файл") },
                        onClick = { onShowAttachMenuChange(false); onPickFile() }
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            if (streaming) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(DeepSeekBlue)
                        .clickable { onStop() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("■", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                val canSend = input.isNotBlank() || attachments.isNotEmpty()
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) DeepSeekBlue
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                        )
                        .clickable(enabled = canSend) { onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("↑", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
