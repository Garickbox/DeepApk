package com.garickbox.deepapk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(SettingsStore.load(ctx)) }
    var draft by remember { mutableStateOf(s) }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Настройки", fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))

        Field("DeepSeek API Key", draft.apiKey) { draft = draft.copy(apiKey = it) }
        Field("Модель (deepseek-chat / deepseek-reasoner / custom)", draft.model) { draft = draft.copy(model = it) }

        Spacer(Modifier.height(8.dp))
        Text("Temperature: ${"%.2f".format(draft.temperature)}", fontSize = 13.sp)
        Slider(value = draft.temperature, onValueChange = { draft = draft.copy(temperature = it) }, valueRange = 0f..2f)

        Spacer(Modifier.height(8.dp))
        Text("Max tokens: ${draft.maxTokens}", fontSize = 13.sp)
        Slider(
            value = draft.maxTokens.toFloat(),
            onValueChange = { draft = draft.copy(maxTokens = it.toInt()) },
            valueRange = 1024f..65536f,
            steps = 31
        )

        Spacer(Modifier.height(12.dp))
        Field("System Prompt", draft.systemPrompt, multiline = true) { draft = draft.copy(systemPrompt = it) }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.webSearchEnabled, onCheckedChange = { draft = draft.copy(webSearchEnabled = it) })
            Text("Включить веб-поиск (Tavily)")
        }
        Field("Tavily API Key (бесплатно 1000/мес: tavily.com)", draft.tavilyKey) { draft = draft.copy(tavilyKey = it) }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { SettingsStore.save(ctx, draft); s = draft; msg = "Сохранено" }) { Text("Сохранить") }
            OutlinedButton(onClick = onBack) { Text("Назад") }
        }
        if (msg.isNotBlank()) Text(msg, color = Color.Green, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
