package com.garickbox.deepapk

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    isDark: Boolean,
    onThemeChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf(SettingsStore.load(ctx)) }
    var termuxPermGranted by remember { mutableStateOf(TermuxBridge.hasRunPermission(ctx)) }
    var balance by remember { mutableStateOf("…") }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadPct by remember { mutableStateOf(0) }
    var statusMsg by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    var showProviderDialog by remember { mutableStateOf(false) }
    var showDeepSeekKeyDialog by remember { mutableStateOf(false) }
    var showGeminiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showGeminiModelDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showProxyDialog by remember { mutableStateOf(false) }
    var keyLinkMeta by remember { mutableStateOf<ProviderMeta?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        termuxPermGranted = granted || TermuxBridge.hasRunPermission(ctx)
        statusMsg = if (termuxPermGranted) "Разрешение Termux выдано" else "Разрешение не выдано"
    }

    LaunchedEffect(draft.apiKey) {
        balance = if (draft.apiKey.isBlank()) "Нет ключа"
        else withContext(Dispatchers.IO) { DeepSeekApi.fetchBalance(draft.apiKey)?.text ?: "Ошибка" }
    }

    val pageBg = if (isDark) Color(0xFF111215) else Color(0xFFF5F6F8)
    val cardBg = if (isDark) Color(0xFF1A1C22) else Color.White
    val dividerCol = if (isDark) Color(0xFF282C35) else Color(0xFFF0F1F4)

    fun saveNow(updated: AppSettings) {
        draft = updated
        SettingsStore.save(ctx, updated)
    }

    val themeLabel = when (draft.themeMode) {
        "dark" -> "Тёмная"
        "light" -> "Светлая"
        else -> "Система"
    }
    val fontLabel = when {
        draft.fontSizeSp <= 13 -> "Мелкий (${draft.fontSizeSp})"
        draft.fontSizeSp >= 17 -> "Крупный (${draft.fontSizeSp})"
        else -> "Обычный (${draft.fontSizeSp})"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pageBg)
    ) {
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
                textAlign = TextAlign.Center
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            val pMeta = ModelCatalog.findProvider(draft.provider)
            val mMeta = ModelCatalog.findModel(draft.provider, draft.activeModelLabel())
            val activeK = draft.activeKey()
            SectionLabel("Выбор ИИ")
            SettingsCard(cardBg) {
                SettingsRow("⚡", "Активный ИИ", draft.providerLabel()) { showProviderDialog = true }
                HorizontalDivider(color = dividerCol)
                SettingsRow("💎", "Модель (нажми)", draft.activeModelLabel()) { showModelDialog = true }
                if (mMeta != null) Text("${mMeta.tag} · ${mMeta.fitsFor}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 44.dp, end = 14.dp, bottom = 10.dp))
            }
            Spacer(Modifier.height(14.dp))
            SectionLabel("Ключ: ${pMeta.title}")
            SettingsCard(cardBg) {
                if (draft.provider == "deepseek") {
                    SettingsRow("💰", "Баланс DeepSeek", balance) {
                        if (draft.apiKey.isNotBlank()) { balance = "Обновление…"; scope.launch { balance = withContext(Dispatchers.IO) { DeepSeekApi.fetchBalance(draft.apiKey)?.text ?: "Ошибка" } } }
                    }
                    HorizontalDivider(color = dividerCol)
                }
                if (draft.provider == "custom") {
                    SettingsRow("🔗", "Base URL", draft.customBaseUrl.removePrefix("https://")) { showProxyDialog = true }
                    HorizontalDivider(color = dividerCol)
                }
                SettingsRow("🔑", "API-ключ", if (activeK.isBlank()) "Не задан" else "${activeK.take(4)}•••${activeK.takeLast(4)}") { showDeepSeekKeyDialog = true }
                HorizontalDivider(color = dividerCol)
                SettingsRow("🌐", "Получить ключ", pMeta.keyUrl.removePrefix("https://")) { keyLinkMeta = pMeta }
            }
            Spacer(Modifier.height(14.dp))
            SectionLabel("Приложение")
            SettingsCard(cardBg) {
                SettingsRow(
                    icon = "☀",
                    title = "Внешний вид",
                    value = themeLabel,
                    onClick = { showThemeDialog = true }
                )
                HorizontalDivider(color = dividerCol)
                SettingsRow(
                    icon = "AA",
                    title = "Размер шрифта",
                    value = fontLabel,
                    onClick = { showFontDialog = true }
                )
                HorizontalDivider(color = dividerCol)
                SettingsRow(
                    icon = "🎛",
                    title = "Параметры (код / текст)",
                    value = "T=${"%.1f".format(draft.temperature)} (${if (draft.temperature <= 0.35f) "Код" else if (draft.temperature <= 0.85f) "Баланс" else "Текст"}) · ${draft.maxTokens / 1024}K",
                    onClick = { showParamsDialog = true }
                )
                HorizontalDivider(color = dividerCol)
                SettingsRow(
                    icon = "✎",
                    title = "Персонализация",
                    value = draft.systemPrompt.take(16) + "…",
                    onClick = { showPromptDialog = true }
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("Termux")
            SettingsCard(cardBg) {
                SettingsRow(
                    icon = "▶",
                    title = "Прямой запуск команд",
                    value = if (termuxPermGranted) "Разрешено" else "Запросить",
                    onClick = { permLauncher.launch(TermuxBridge.PERM_RUN_COMMAND) }
                )
            }

            Spacer(Modifier.height(14.dp))
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
                            "Доступна версия ${u.latestVersion} (${u.latestBuild})",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (u.notes.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(u.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (downloading) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { downloadPct / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Скачивание: $downloadPct%", fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !downloading,
                                onClick = {
                                    downloading = true
                                    downloadPct = 0
                                    scope.launch {
                                        val file = withContext(Dispatchers.IO) {
                                            UpdateChecker.downloadApk(ctx, u) { pct ->
                                                downloadPct = pct
                                            }
                                        }
                                        downloading = false
                                        if (file != null) {
                                            Toast.makeText(
                                                ctx,
                                                "Сохранено в Download/${u.fileName}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            UpdateChecker.installApk(ctx, file)
                                        } else {
                                            Toast.makeText(ctx, "Ошибка скачивания APK", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            ) { Text(if (downloading) "Загрузка…" else "Установить") }
                            OutlinedButton(onClick = {
                                TermuxBridge.copyToClipboard(ctx, u.apkUrl)
                                Toast.makeText(ctx, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                            }) { Text("Копировать ссылку") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showProviderDialog) {
        ProviderGuideDialog(draft.provider, { saveNow(draft.copy(provider = it)); showProviderDialog = false }, { keyLinkMeta = it }, { showProviderDialog = false })
    }
    if (showDeepSeekKeyDialog) {
        val pm = ModelCatalog.findProvider(draft.provider)
        KeyInputDialog("Ключ: ${pm.title}", draft.activeKey(), pm.keyHint, { saveNow(draft.withActiveKey(it)); showDeepSeekKeyDialog = false }, { showDeepSeekKeyDialog = false })
    }
    if (showModelDialog) {
        RichModelDialog(draft.provider, draft.activeModelLabel(), { saveNow(draft.withActiveModel(it)); showModelDialog = false }, { keyLinkMeta = ModelCatalog.findProvider(draft.provider) }, { showModelDialog = false })
    }
    if (showProxyDialog) {
        CustomProxyDialog(draft.customBaseUrl, { saveNow(draft.copy(customBaseUrl = it)); showProxyDialog = false }, { showProxyDialog = false })
    }
    keyLinkMeta?.let { KeyLinkDialog(it) { keyLinkMeta = null } }
    if (showParamsDialog) {
        ParamsCodeDialog(draft.temperature, draft.maxTokens, { tp, mt -> saveNow(draft.copy(temperature = tp, maxTokens = mt)); showParamsDialog = false }, { showParamsDialog = false })
    }
    if (showPromptDialog) {
        var prompt by remember { mutableStateOf(draft.systemPrompt) }
        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            title = { Text("Персонализация (System Prompt)", fontSize = 17.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        minLines = 5,
                        maxLines = 9,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { prompt = AppSettings.DEFAULT_SYSTEM_PROMPT }) {
                        Text("Сбросить на Termux-промпт")
                    }
                }
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
    if (showThemeDialog) {
        ChoiceDialog(
            title = "Внешний вид",
            items = listOf(
                "system" to "Система",
                "dark" to "Тёмная",
                "light" to "Светлая"
            ),
            selected = draft.themeMode,
            onSelect = { mode ->
                saveNow(draft.copy(themeMode = mode))
                onThemeChange(mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }
    if (showFontDialog) {
        ChoiceDialog(
            title = "Размер шрифта",
            items = listOf(
                "12" to "Очень мелкий (12)",
                "13" to "Мелкий (13)",
                "15" to "Обычный (15)",
                "17" to "Крупный (17)",
                "19" to "Очень крупный (19)",
                "21" to "Гигантский (21)"
            ),
            selected = draft.fontSizeSp.toString(),
            onSelect = { szStr ->
                val sz = szStr.toIntOrNull()
                if (sz != null) {
                    saveNow(draft.copy(fontSizeSp = sz))
                    showFontDialog = false
                }
            },
            onDismiss = { showFontDialog = false }
        )
    }
}

@Composable
private fun KeyInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var keyVal by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp) },
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
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(keyVal.trim()) }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    items: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp) },
        text = {
            Column {
                items.forEach { (id, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(id) }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = selected == id, onClick = { onSelect(id) })
                        Text(label, fontSize = 13.5.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
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
        Text(icon, fontSize = 16.sp, modifier = Modifier.width(30.dp))
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
                modifier = Modifier.widthIn(max = 140.dp).padding(end = 6.dp)
            )
        }
        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
