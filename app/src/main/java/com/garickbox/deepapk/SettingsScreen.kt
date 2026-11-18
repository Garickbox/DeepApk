package com.garickbox.deepapk

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
    var msg by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Настройки", fontSize = 20.sp)
        Text("DeepApk v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.height(12.dp))

        Field("DeepSeek API Key (platform.deepseek.com)", draft.apiKey) { draft = draft.copy(apiKey = it) }


        Spacer(Modifier.height(8.dp))
        Text("Модель:", fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = draft.model == "deepseek-flash", onClick = { draft = draft.copy(model = "deepseek-flash") })
            Text("deepseek-flash (V4.1, vision, web search)", fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = draft.model == "deepseek-v4-pro", onClick = { draft = draft.copy(model = "deepseek-v4-pro") })
            Text("deepseek-v4-pro (V4, мощнее, без vision)", fontSize = 12.sp)
        }


        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.thinkingEnabled, onCheckedChange = { draft = draft.copy(thinkingEnabled = it) })
            Text("Thinking mode (рассуждения)", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.webSearchEnabled, onCheckedChange = { draft = draft.copy(webSearchEnabled = it) })
            Text("Веб-поиск DeepSeek (встроенный, deepseek-flash)", fontSize = 13.sp)
        }


        Spacer(Modifier.height(8.dp))
        Text("Temperature: ${"%.2f".format(draft.temperature)}", fontSize = 13.sp)
        Slider(value = draft.temperature, onValueChange = { draft = draft.copy(temperature = it) }, valueRange = 0f..2f)


        Spacer(Modifier.height(8.dp))
        Text("Max output tokens: ${draft.maxTokens}", fontSize = 13.sp)
        Slider(
            value = draft.maxTokens.toFloat(),
            onValueChange = { draft = draft.copy(maxTokens = it.toInt()) },
            valueRange = 1024f..131072f,
            steps = 127
        )
        Text("Максимум у DeepSeek — 384K, но 128K хватит для reasoning + ответа.", fontSize = 10.sp, color = Color.Gray)

        Spacer(Modifier.height(12.dp))
        Field("System Prompt", draft.systemPrompt, multiline = true) { draft = draft.copy(systemPrompt = it) }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { SettingsStore.save(ctx, draft); msg = "Сохранено" }) { Text("Сохранить") }
            OutlinedButton(onClick = onBack) { Text("Назад") }
            OutlinedButton(
                onClick = {
                    checking = true; msg = ""
            scope.launch {
                val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
                checking = false
                if (info == null) msg = "Не удалось проверить обновления"
                else if (info.latestVersion == BuildConfig.VERSION_NAME) msg = "Уже последняя версия"
                else update = info
            }
                },
                enabled = !checking
            ) { Text(if (checking) "Проверяю…" else "🔄 Обновить") }
        }
        if (msg.isNotBlank()) Text(msg, color = Color.Green, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))


        update?.let { u ->
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Доступна версия ${u.latestVersion}", fontSize = 14.sp)
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

@Composable
private fun Field(label: String, value: String, multiline: Boolean = false, onChange: (String) -> Unit) {
    Text(label, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F0F0)).padding(8.dp)
            .height(if (multiline) 100.dp else 40.dp),
        textStyle = TextStyle(fontSize = 13.sp)
    )
    Spacer(Modifier.height(8.dp))
}
