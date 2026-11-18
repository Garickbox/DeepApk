package com.garickbox.deepapk

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(SettingsStore.load(ctx)) }
    var showKey by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    val models = listOf(
        "deepseek-flash" to "deepseek-flash (быстрая, веб-поиск)",
        "deepseek-v4-pro" to "deepseek-v4-pro (мощнее)",
        "deepseek-chat" to "deepseek-chat (стандартная API)",
        "deepseek-reasoner" to "deepseek-reasoner (R1 thinking)"
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Настройки", fontSize = 20.sp)
        Text(
            "DeepApk v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { draft = draft.copy(apiKey = it) },
            label = { Text("DeepSeek API Key (platform.deepseek.com)") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Text(
                    if (showKey) "🙈" else "👁",
                    fontSize = 16.sp,
                    modifier = Modifier.clickable { showKey = !showKey }.padding(8.dp)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        Text("Модель:", fontSize = 13.sp)
        models.forEach { (id, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { draft = draft.copy(model = id) }
            ) {
                RadioButton(
                    selected = draft.model == id,
                    onClick = { draft = draft.copy(model = id) }
                )
                Text(label, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { draft = draft.copy(thinkingEnabled = !draft.thinkingEnabled) }
        ) {
            Checkbox(
                checked = draft.thinkingEnabled,
                onCheckedChange = { draft = draft.copy(thinkingEnabled = it) }
            )
            Text("Thinking mode (рассуждения)", fontSize = 13.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { draft = draft.copy(webSearchEnabled = !draft.webSearchEnabled) }
        ) {
            Checkbox(
                checked = draft.webSearchEnabled,
                onCheckedChange = { draft = draft.copy(webSearchEnabled = it) }
            )
            Text("Веб-поиск DeepSeek (использует deepseek-flash)", fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))
        Text("Temperature: ${"%.2f".format(draft.temperature)}", fontSize = 13.sp)
        Slider(
            value = draft.temperature,
            onValueChange = { draft = draft.copy(temperature = it) },
            valueRange = 0f..2f
        )

        Spacer(Modifier.height(8.dp))
        Text("Max output tokens: ${draft.maxTokens}", fontSize = 13.sp)
        Slider(
            value = draft.maxTokens.toFloat(),
            onValueChange = { draft = draft.copy(maxTokens = it.toInt()) },
            valueRange = 1024f..131072f,
            steps = 127
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = draft.systemPrompt,
            onValueChange = { draft = draft.copy(systemPrompt = it) },
            label = { Text("System Prompt") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                SettingsStore.save(ctx, draft)
                msg = "Сохранено"
            }) { Text("Сохранить") }
            OutlinedButton(onClick = onBack) { Text("Назад") }
            OutlinedButton(
                onClick = {
                    checking = true
                    msg = ""
                    update = null
                    scope.launch {
                        val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
                        checking = false
                        if (info == null) {
                            msg = "Не удалось проверить обновления"
                        } else if (info.latestBuild <= BuildConfig.VERSION_CODE) {
                            msg = "Установлена актуальная версия (build ${BuildConfig.VERSION_CODE})"
                        } else {
                            update = info
                        }
                    }
                },
                enabled = !checking
            ) { Text(if (checking) "Проверяю…" else "🔄 Обновить") }
        }
        if (msg.isNotBlank()) {
            Text(
                msg,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        update?.let { u ->
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Доступна версия ${u.latestVersion} (build ${u.latestBuild})", fontSize = 14.sp)
                    if (u.notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(u.notes, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
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
    }
}
