package com.garickbox.deepapk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun IncomingSkillDialog(
    pkg: SkillPackage,
    existing: PromptPreset?,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onInstall: (keepExistingSkill: Boolean, switchAndNewChat: Boolean) -> Unit
) {
    var keepExistingSkill by remember(pkg.id) { mutableStateOf(true) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (isDark) Color(0xFF1F2229) else Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("${pkg.emoji} ${pkg.name}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (existing != null) "Обновить существующий скилл" else "Новый скилл .deepapk") +
                        " · ${pkg.prompt.length} симв.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        pkg.prompt.take(260),
                        fontSize = 12.sp,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                if (existing != null && existing.learnedSkill.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepExistingSkill, onCheckedChange = { keepExistingSkill = it })
                        Text("Сохранить накопленные правила (🧠)", fontSize = 12.5.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { onInstall(keepExistingSkill, false) },
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("Сохранить", fontSize = 12.5.sp) }
                    Button(
                        onClick = { onInstall(keepExistingSkill, true) },
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("В новый чат", fontSize = 12.5.sp) }
                }
            }
        }
    }
}

@Composable
internal fun IncomingShareDialog(
    share: IncomingShare,
    presets: List<PromptPreset>,
    recentFive: List<ChatSession>,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onOpenNewChat: (presetId: String) -> Unit,
    onPickExisting: (ChatSession) -> Unit
) {
    val hasDocx = share.files.any { it.name.endsWith(".docx", ignoreCase = true) }
    var sharePresetId by remember(share.id) { mutableStateOf(if (hasDocx) "docx_report" else "default") }
    val dividerCol = if (isDark) Color(0xFF2B2F38) else Color(0xFFF0F1F4)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (isDark) Color(0xFF1F2229) else Color.White,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp)
        ) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📥 Принято из мессенджера", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("✕", fontSize = 18.sp, modifier = Modifier.clickable { onDismiss() }.padding(4.dp))
                }
                Spacer(Modifier.height(8.dp))
                if (share.files.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        share.files.forEach { f ->
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
                                Text("📎 ${f.name} (${f.sizeLabel})", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (share.text.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            share.text.take(220),
                            fontSize = 12.5.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Text("➕ Новый чат с ролью:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { p ->
                        ModePill(
                            label = "${p.emoji} ${p.name}",
                            active = sharePresetId == p.id,
                            isDark = isDark,
                            onClick = { sharePresetId = p.id }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onOpenNewChat(sharePresetId) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    val pObj = presets.firstOrNull { it.id == sharePresetId }
                    Text("Открыть в новом чате (${pObj?.emoji ?: ""} ${pObj?.name ?: ""})")
                }

                if (recentFive.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Или добавить в последние чаты:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    recentFive.forEachIndexed { idx, s ->
                        val rp = presets.firstOrNull { it.id == s.presetId }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPickExisting(s) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(rp?.emoji ?: "💬", fontSize = 16.sp, modifier = Modifier.width(26.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(rp?.name ?: "Обычный", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("→", color = DeepSeekBlue, fontWeight = FontWeight.Bold)
                        }
                        if (idx < recentFive.lastIndex) HorizontalDivider(color = dividerCol)
                    }
                }
            }
        }
    }
}
