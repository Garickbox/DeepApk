package com.garickbox.deepapk

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProviderGuideDialog(selected: String, onSelect: (String) -> Unit, onOpenKeySite: (ProviderMeta) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбор ИИ-провайдера", fontSize = 17.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelCatalog.providers.forEach { p ->
                    val isSel = p.id == selected
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(if (isSel) 1.5.dp else 1.dp, if (isSel) DeepSeekBlue else MaterialTheme.colorScheme.outlineVariant),
                        color = if (isSel) DeepSeekBlue.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(p.id) }
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(p.badge, fontSize = 11.sp, color = DeepSeekBlue, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(p.desc, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("🌐 Где ключ", fontSize = 11.5.sp, color = DeepSeekBlue, modifier = Modifier.clickable { onOpenKeySite(p) })
                                Text(if (isSel) "✓ Активен" else "Выбрать ›", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DeepSeekBlue)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
fun RichModelDialog(provider: String, selected: String, onSelect: (String) -> Unit, onOpenKeySite: () -> Unit, onDismiss: () -> Unit) {
    val models = remember(provider) { ModelCatalog.modelsFor(provider) }
    var focusedId by remember { mutableStateOf(selected) }
    var customId by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Модели (${ModelCatalog.findProvider(provider).title})", fontSize = 16.sp) },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Нажмите на модель, чтобы прочитать для чего она и лимит:", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                models.forEach { m ->
                    val isSel = m.id == selected; val isExp = m.id == focusedId
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(if (isSel || isExp) 1.5.dp else 1.dp, if (isSel) DeepSeekBlue else MaterialTheme.colorScheme.outlineVariant),
                        color = if (isSel) DeepSeekBlue.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().clickable { focusedId = m.id }
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.id, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (isSel) Text("✓", color = DeepSeekBlue, fontWeight = FontWeight.Bold)
                            }
                            Text(m.tag, fontSize = 11.sp, color = DeepSeekBlue)
                            if (isExp) {
                                Spacer(Modifier.height(4.dp))
                                Text("Подходит: ${m.fitsFor}", fontSize = 11.5.sp, lineHeight = 15.sp)
                                Text("Лимит: ${m.limits} · Реком. T=${m.recTemp}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Button(onClick = { onSelect(m.id) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                                    Text(if (isSel) "Оставить" else "Выбрать модель", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                if (provider == "custom" || provider == "openrouter") {
                    OutlinedTextField(value = customId, onValueChange = { customId = it }, singleLine = true, label = { Text("Свой ID модели", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { if (customId.isNotBlank()) onSelect(customId.trim()) }) { Text("Использовать свой ID") }
                }
                Text("🌐 Где взять API-ключ →", fontSize = 12.sp, color = DeepSeekBlue, modifier = Modifier.clickable { onOpenKeySite() }.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
fun KeyLinkDialog(meta: ProviderMeta, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ключ: ${meta.title}", fontSize = 16.5.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(meta.desc, fontSize = 12.5.sp, lineHeight = 17.sp)
                Text("Сайт: ${meta.keyUrl}", fontSize = 12.5.sp, color = DeepSeekBlue, fontWeight = FontWeight.Medium)
                Text("Формат ключа: ${meta.keyHint}\nСоздайте ключ в кабинете и вставьте в «API-ключ».", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(meta.keyUrl))) }
                catch (_: Exception) { Toast.makeText(ctx, "Скопируйте ссылку", Toast.LENGTH_SHORT).show() }
            }) { Text("Открыть сайт") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { TermuxBridge.copyToClipboard(ctx, meta.keyUrl); Toast.makeText(ctx, "Ссылка скопирована", Toast.LENGTH_SHORT).show() }) { Text("Копировать") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        }
    )
}

@Composable
fun CustomProxyDialog(initialUrl: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("РФ-прокси / Base URL", fontSize = 16.5.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Быстрые пресеты (оплата МИР/СБП):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ProxyAPI" to "https://api.proxyapi.ru/openai/v1", "VseGPT" to "https://api.vsegpt.ru/v1", "AITunnel" to "https://api.aitunnel.ru/v1").forEach { (lbl, u) ->
                        OutlinedButton(onClick = { url = u }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(30.dp)) { Text(lbl, fontSize = 11.sp) }
                    }
                }
                OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, label = { Text("Base URL (/v1)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (url.isNotBlank()) onSave(url.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun ParamsCodeDialog(initTemp: Float, initMax: Int, onSave: (Float, Int) -> Unit, onDismiss: () -> Unit) {
    var temp by remember { mutableStateOf(initTemp) }
    var maxT by remember { mutableStateOf(initMax) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки под код и ответы", fontSize = 16.5.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Быстрый пресет:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { temp = 0.2f; maxT = 16384 }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(30.dp)) { Text("💻 Код 0.2", fontSize = 11.sp) }
                    OutlinedButton(onClick = { temp = 0.7f; maxT = 16384 }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(30.dp)) { Text("⚖ Баланс 0.7", fontSize = 11.sp) }
                    OutlinedButton(onClick = { temp = 1.0f; maxT = 16384 }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(30.dp)) { Text("🎨 Текст 1.0", fontSize = 11.sp) }
                }
                Text("Temperature: ${"%.2f".format(temp)}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..2f)
                Text("0.1–0.3 — точный код и патчи без фантазий; 0.7 — диалог; >1.0 — креатив.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("Max output tokens: $maxT (${maxT / 1024}K)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Slider(value = maxT.toFloat(), onValueChange = { maxT = it.toInt() }, valueRange = 1024f..131072f, steps = 127)
                Text("Макс. длина ответа. Для длинных файлов и патчей: 16K–32K.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(temp, maxT) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
