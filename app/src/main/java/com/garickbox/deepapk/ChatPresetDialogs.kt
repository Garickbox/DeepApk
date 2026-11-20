package com.garickbox.deepapk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast

@Composable
internal fun PresetEditDialog(
    preset: PromptPreset,
    presets: List<PromptPreset>,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSave: (PromptPreset) -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    val ep = preset
    var eEmoji by remember(ep.id) { mutableStateOf(ep.emoji) }
    var eName by remember(ep.id) { mutableStateOf(ep.name) }
    var ePrompt by remember(ep.id) { mutableStateOf(ep.prompt) }
    var eSkill by remember(ep.id) { mutableStateOf(ep.learnedSkill) }
    val isExisting = presets.any { it.id == ep.id }
    val skillFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty().trim()
                if (text.isNotBlank()) {
                    val parsed = parseSkillJsonOrText(text, uri.lastPathSegment?.substringAfterLast('/').orEmpty())
                    if (parsed != null) {
                        eEmoji = parsed.emoji
                        eName = parsed.name
                        ePrompt = parsed.prompt
                        if (parsed.learnedSkill.isNotBlank()) eSkill = parsed.learnedSkill
                        Toast.makeText(ctx, "Загружен скилл «${parsed.name}»", Toast.LENGTH_SHORT).show()
                    } else {
                        ePrompt = text
                        Toast.makeText(ctx, "Загружено ${text.length} симв.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(ctx, "Файл пустой", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "Ошибка чтения: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isDark) Color(0xFF1F2229) else Color.White,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp)
        ) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (isExisting) "Настроить заготовку" else "Новая заготовка",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = eEmoji,
                        onValueChange = { eEmoji = it.take(4) },
                        label = { Text("Значок") },
                        singleLine = true,
                        modifier = Modifier.width(86.dp)
                    )
                    OutlinedTextField(
                        value = eName,
                        onValueChange = { eName = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { ePrompt = PresetStore.loadCustomTemplate(ctx) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("📋 Шаблон скилла", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { skillFilePicker.launch(arrayOf("text/*", "*/*")) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("📂 Из файла", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            val cur = ep.copy(
                                emoji = eEmoji.ifBlank { "⭐" },
                                name = eName.ifBlank { "Заготовка" },
                                prompt = ePrompt,
                                learnedSkill = eSkill
                            )
                            sharePresetAsDeepApk(ctx, cur)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("📤 .deepapk", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = ePrompt,
                    onValueChange = { ePrompt = it },
                    label = { Text("Базовый промпт (${ePrompt.length} симв. · ~${ePrompt.length / 1024} КБ)") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = eSkill,
                    onValueChange = { eSkill = it },
                    label = { Text("Накопленный скилл (правила)") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ep.isBuiltIn) {
                        TextButton(onClick = {
                            val def = PresetStore.defaults(ctx).firstOrNull { it.id == ep.id }
                            if (def != null) {
                                eEmoji = def.emoji
                                eName = def.name
                                ePrompt = def.prompt
                                eSkill = ""
                            }
                        }) { Text("Сбросить") }
                    } else if (isExisting) {
                        TextButton(onClick = onDelete) { Text("Удалить", color = Color(0xFFE53935)) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Button(
                        onClick = {
                            onSave(
                                ep.copy(
                                    emoji = eEmoji.ifBlank { "⭐" },
                                    name = eName.ifBlank { "Заготовка" },
                                    prompt = ePrompt,
                                    learnedSkill = eSkill
                                )
                            )
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("Сохранить") }
                }
            }
        }
    }
}

@Composable
internal fun PresetsListDialog(
    presets: List<PromptPreset>,
    activePresetId: String,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreateNew: () -> Unit,
    onEdit: (PromptPreset) -> Unit
) {
    val dividerCol = if (isDark) Color(0xFF2B2F38) else Color(0xFFF0F1F4)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (isDark) Color(0xFF1F2229) else Color.White,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 620.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Заготовки промпта",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCreateNew) {
                        Text("+ Своя", color = DeepSeekBlue, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "✕",
                        fontSize = 18.sp,
                        modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
                    )
                }
                Text(
                    "Действует для этого чата · Дообучается по ходу работы",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    presets.forEachIndexed { idx, p ->
                        val selected = p.id == activePresetId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) DeepSeekBlue.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable { onSelect(p.id) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(p.emoji, fontSize = 20.sp, modifier = Modifier.padding(end = 6.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        p.name,
                                        fontSize = 15.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (selected) DeepSeekBlue else MaterialTheme.colorScheme.onBackground
                                    )
                                    if (p.learnedSkill.isNotBlank()) {
                                        Spacer(Modifier.padding(end = 4.dp))
                                        Text("🧠", fontSize = 12.sp)
                                    }
                                }
                                val preview = p.prompt.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(85) ?: "Чистые нейтральные ответы"
                                Text(
                                    preview,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selected) {
                                Text("✓", color = DeepSeekBlue, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                            }
                            Text(
                                "✏️",
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .clickable { onEdit(p) }
                                    .padding(6.dp)
                            )
                        }
                        if (idx < presets.lastIndex) HorizontalDivider(color = dividerCol)
                    }
                }
            }
        }
    }
}