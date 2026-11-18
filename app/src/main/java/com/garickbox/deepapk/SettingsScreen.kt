package com.garickbox.deepapk

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    var draft by remember { mutableStateOf(SettingsStore.load(ctx)) }
    var termuxPermGranted by remember { mutableStateOf(TermuxBridge.hasRunPermission(ctx)) }
    var checking by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showParamsDialog by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        termuxPermGranted = granted || TermuxBridge.hasRunPermission(ctx)
        statusMsg = if (termuxPermGranted) "Разрешение Termux выдано" else "Разрешение не выдано"
    }

    val pageBg = if (isDark) Color(0xFF111215) else Color(0xFFF5F6F8)
    val cardBg = if (isDark) Color(0xFF1A1C22) else Color.White
    val dividerCol = if (isDark) Color(0xFF282C35) else Color(0xFFF0F1F4)

    fun saveNow(updated: AppSettings) {
        draft = updated
        SettingsStore.save(ctx, updated)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pageBg)
    ) {
        // Top bar matching screenshot 2
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(cardBg)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Text("<", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                "Настройки",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(end = 36.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            SectionLabel("Профиль и API")
            SettingsCard(cardBg) {
                SettingsRow(
                    icon = "🔑",
                    title = "API-ключ DeepSeek",
                    value = if (draft.apiKey.isBlank()) "Не задан" else "sk-•••${draft.apiKey.takeLast(4)}",
                    onClick = { showApiKeyDialog = true }
                )
                HorizontalDivider(color = dividerCol)
                SettingsRow(
                    icon = "🤖",
                    title = "Модель по умолчанию",
                    value = draft.model,
                    onClick = { showModelDialog = true }
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("Генерация и промпт")
            SettingsCard(cardBg) {
                SettingsRow(
                    icon = "🎛",
                    title = "Temperature / Max tokens",
                    value = "${"%.1f".format(draft.temperature)} · ${draft.maxTokens / 1024}K",
                    onClick = { showParamsDialog = true }
                )
                HorizontalDivider(color = dividerCol)
                SettingsRow(
                    icon = "📝",
                    title = "Персонализация (System Prompt)",
                    value = draft.systemPrompt.take(18) + "…",
                    onClick = { showPromptDialog = true }
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("Интеграция с Termux")
            SettingsCard(cardBg) {
                SettingsRow(
                    icon = "⚡",
                    title = "Прямой запуск в Termux",
                    value = if (termuxPermGranted) "Разрешено" else "Запросить",
                    onClick = { permLauncher.launch(TermuxBridge.PERM_RUN_COMMAND) }
                )
            }
            Text(
                "Код всегда сохраняется в Download/deep_cmd.sh. Для мгновенного запуска без вставки добавьте в ~/.termux/termux.properties строку: allow-external-apps = true",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )

            Spacer(Modifier.height(8.dp))
            SectionLabel("О программе")
            SettingsCard(cardBg) {
                SettingsRow(
                    icon = "ⓘ",
                    title = if (checking) "Проверяю обновления…" else "Проверить обновления",
                    value = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                    onClick = {
                        if (!checking) {
                            checking = true
                            statusMsg = ""
                            update = null
                            scope.launch {
                                val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
                                checking = false
                                if (info == null) {
                                    statusMsg = "Не удалось проверить обновления"
                                } else if (info.latestBuild <= BuildConfig.VERSION_CODE) {
                                    statusMsg = "Установлена актуальная версия (${BuildConfig.VERSION_CODE})"
                                } else {
                                    update = info
                                }
                            }
                        }
                    }
                )
            }

            if (statusMsg.isNotBlank()) {
                Text(
                    statusMsg,
                    color = DeepSeekBlue,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                )
            }

            update?.let { u ->
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Доступна версия ${u.latestVersion} (build ${u.latestBuild})",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (u.notes.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(u.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val i = Intent(Intent.ACTION_VIEW, Uri.parse(u.apkUrl))
                                runCatching { ctx.startActivity(i) }
                                Toast.makeText(ctx, "Скачивание APK…", Toast.LENGTH_SHORT).show()
                            }) { Text("Скачать APK") }
                            OutlinedButton(onClick = { update = null }) { Text("Позже") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showApiKeyDialog) {
        var keyVal by remember { mutableStateOf(draft.apiKey) }
        var visible by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("API-ключ DeepSeek", fontSize = 17.sp) },
            text = {
                OutlinedTextField(
                    value = keyVal,
                    onValueChange = { keyVal = it },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(
                            if (visible) "🙈" else "👁",
                            modifier = Modifier.clickable { visible = !visible }.padding(8.dp)
                        )
                    },
                    placeholder = { Text("sk-...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    saveNow(draft.copy(apiKey = keyVal.trim()))
                    showApiKeyDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showModelDialog) {
        val models = listOf(
            "deepseek-flash" to "deepseek-flash (быстрая + поиск)",
            "deepseek-v4-pro" to "deepseek-v4-pro (мощнее)",
            "deepseek-chat" to "deepseek-chat (стандарт)",
            "deepseek-reasoner" to "deepseek-reasoner (R1)"
        )
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Выберите модель", fontSize = 17.sp) },
            text = {
                Column {
                    models.forEach { (id, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    saveNow(draft.copy(model = id))
                                    showModelDialog = false
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = draft.model == id, onClick = {
                                saveNow(draft.copy(model = id))
                                showModelDialog = false
                            })
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) { Text("Закрыть") }
            }
        )
    }

    if (showParamsDialog) {
        var temp by remember { mutableStateOf(draft.temperature) }
        var maxT by remember { mutableStateOf(draft.maxTokens) }
        AlertDialog(
            onDismissRequest = { showParamsDialog = false },
            title = { Text("Параметры генерации", fontSize = 17.sp) },
            text = {
                Column {
                    Text("Temperature: ${"%.2f".format(temp)}", fontSize = 13.sp)
                    Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..2f)
                    Spacer(Modifier.height(8.dp))
                    Text("Max output tokens: $maxT", fontSize = 13.sp)
                    Slider(
                        value = maxT.toFloat(),
                        onValueChange = { maxT = it.toInt() },
                        valueRange = 1024f..131072f,
                        steps = 127
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saveNow(draft.copy(temperature = temp, maxTokens = maxT))
                    showParamsDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showParamsDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showPromptDialog) {
        var prompt by remember { mutableStateOf(draft.systemPrompt) }
        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            title = { Text("System Prompt", fontSize = 17.sp) },
            text = {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    saveNow(draft.copy(systemPrompt = prompt))
                    showPromptDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showPromptDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsCard(bg: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(
    icon: String,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 17.sp, modifier = Modifier.width(30.dp))
        Text(
            title,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (value.isNotBlank()) {
            Text(
                value,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 135.dp).padding(end = 6.dp)
            )
        }
        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
