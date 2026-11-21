package com.garickbox.deepapk

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ChatTopBar(
    activeTitle: String,
    topPreset: PromptPreset,
    safConnected: Boolean,
    safOn: Boolean,
    projectLabel: String,
    settings: AppSettings,
    streaming: Boolean,
    isDark: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenPresets: () -> Unit,
    onSafClick: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit
) {
    val barIconColor = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable { if (!streaming) onOpenDrawer() },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(18.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = barIconColor,
                    start = Offset(0f, size.height * 0.30f),
                    end = Offset(size.width * 0.95f, size.height * 0.30f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = barIconColor,
                    start = Offset(0f, size.height * 0.72f),
                    end = Offset(size.width * 0.58f, size.height * 0.72f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }

        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activeTitle,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.padding(top = 2.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (topPreset.id != "default") DeepSeekBlue.copy(alpha = if (isDark) 0.22f else 0.11f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { if (!streaming) onOpenPresets() }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${topPreset.emoji} ${topPreset.name} ▾",
                        fontSize = 11.5.sp,
                        fontWeight = if (topPreset.id != "default") FontWeight.SemiBold else FontWeight.Medium,
                        color = if (topPreset.id != "default") DeepSeekBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (safOn) DeepSeekBlue.copy(alpha = if (isDark) 0.22f else 0.11f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { if (!streaming) onSafClick() }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (safConnected) "📂 ${projectLabel.take(14)}" + (if (safOn) " · ВКЛ" else " · ВЫКЛ") else "📂 Папка",
                        fontSize = 11.5.sp,
                        fontWeight = if (safOn) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (safOn) DeepSeekBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        var showProvMenu by remember { mutableStateOf(false) }
        Box(Modifier.padding(end = 8.dp)) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { if (!streaming) showProvMenu = true }
            ) {
                Text(
                    settings.providerShort(),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DeepSeekBlue,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
            DropdownMenu(expanded = showProvMenu, onDismissRequest = { showProvMenu = false }) {
                AppSettings.ALL_PROVIDERS.forEach { pid ->
                    val mark = if (settings.hasKey(pid)) "✓" else "нет ключа"
                    DropdownMenuItem(
                        text = { Text("${settings.providerLabel(pid)} ($mark)", fontSize = 13.sp) },
                        onClick = { showProvMenu = false; onSelectProvider(pid) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("⚙ Настройки и ключи", fontSize = 13.sp, color = DeepSeekBlue) },
                    onClick = { showProvMenu = false; onOpenSettings() }
                )
            }
        }

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(1.5.dp, barIconColor.copy(alpha = 0.75f), CircleShape)
                .clickable { onNewChat() },
            contentAlignment = Alignment.Center
        ) {
            Text("+", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = barIconColor)
        }
    }
}

@Composable
internal fun ChatStatusBanners(
    safStatus: String,
    error: String,
    onClearError: () -> Unit
) {
    if (safStatus.isNotBlank()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = DeepSeekBlue)
                Spacer(Modifier.width(8.dp))
                Text("📂 $safStatus", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
    if (error.isNotBlank()) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    "❌ $error",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                )
                Text(
                    "✕",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.clickable { onClearError() }.padding(4.dp)
                )
            }
        }
    }
}
