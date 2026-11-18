package com.garickbox.deepapk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(SettingsStore.load(ctx)) }
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(ChatStore.load(ctx)) }
    var streaming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var searchInfo by remember { mutableStateOf("") }
    var reasoningExpanded by remember { mutableStateOf<Set<Int>>(emptySet()) }

    LaunchedEffect(Unit) { settings = SettingsStore.load(ctx) }

    if (settings.apiKey.isBlank()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text("Введите DeepSeek API Key (platform.deepseek.com):", fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().background(Color.LightGray).padding(8.dp),
                textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                if (input.isNotBlank()) {
                    val s = settings.copy(apiKey = input.trim())
                    SettingsStore.save(ctx, s); settings = s; input = ""
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFEEEEEE)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DeepApk v${BuildConfig.VERSION_NAME}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "build ${BuildConfig.VERSION_CODE} · ${settings.model}" +
                        (if (settings.thinkingEnabled) " · 🧠" else "") +
                        (if (settings.webSearchEnabled) " · 🌐" else ""),
                    fontSize = 10.sp, color = Color.Gray
                )
            }
            if (searchInfo.isNotBlank()) Text("🌐 $searchInfo", fontSize = 10.sp, color = Color(0xFF0066CC))
            Text("⚙", fontSize = 22.sp, modifier = Modifier.clickable { onOpenSettings() }.padding(4.dp))
        }
        if (error.isNotBlank()) {
            Text("❌ $error", fontSize = 11.sp, color = Color.Red, modifier = Modifier.padding(8.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(8.dp), reverseLayout = true) {
            items(messages.size) { revIdx ->
                val idx = messages.size - 1 - revIdx
                val m = messages[idx]
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(if (m.role == "user") "Вы" else "DeepSeek", fontSize = 11.sp, color = Color.Gray)
                    if (m.reasoning.isNotBlank()) {
                        val expanded = idx in reasoningExpanded
                        Text(
                            (if (expanded) "▼" else "▶") + " 💭 Reasoning (${m.reasoning.length} симв.)",
                            fontSize = 11.sp, color = Color(0xFF666666),
                            modifier = Modifier.clickable {
                                reasoningExpanded = if (expanded) reasoningExpanded - idx else reasoningExpanded + idx
                            }.padding(vertical = 4.dp)
                        )
                        if (expanded) {
                            Text(m.reasoning, fontSize = 11.sp, color = Color(0xFF888888), fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(m.content, fontSize = 14.sp)
                    if (m.role == "assistant" && m.content.contains("```")) {
                        TextButton(onClick = { copyForTermux(ctx, m.content) }) { Text("📋 Копировать для Termux", fontSize = 11.sp) }
                    }
                    HorizontalDivider()
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f).background(Color(0xFFF0F0F0)).padding(10.dp).heightIn(min = 40.dp),
                textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    if (input.isBlank() || streaming) return@Button
                    val userText = input
                    input = ""; error = ""; searchInfo = ""
                    messages.add(Message("user", userText))
                    messages.add(Message("assistant", ""))
                    val newIdx = messages.size - 1
                    streaming = true
                    scope.launch {
                        try {
                            DeepSeekApi.chatStream(
                                apiKey = settings.apiKey,
                                model = settings.model,
                                thinkingEnabled = settings.thinkingEnabled,
                                webSearchEnabled = settings.webSearchEnabled,
                                temperature = settings.temperature,
                                maxTokens = settings.maxTokens,
                                systemPrompt = settings.systemPrompt,
                                history = messages.dropLast(1)
                            ).collect { ev ->
                                val cur = messages[newIdx]
                                when (ev) {
                                    is StreamEvent.Reasoning -> messages[newIdx] = cur.copy(reasoning = cur.reasoning + ev.delta)
                                    is StreamEvent.Content -> messages[newIdx] = cur.copy(content = cur.content + ev.delta)
                                    is StreamEvent.SearchInfo -> searchInfo = "поиск ×${ev.queries}"
                                    is StreamEvent.Error -> error = ev.msg
                                    StreamEvent.Done -> {}
                                }
                            }
                        } catch (e: Exception) {
                            error = e.message ?: "unknown"
                        }
                        ChatStore.save(ctx, messages)
                        streaming = false
                    }
                },
                enabled = !streaming
            ) { Text(if (streaming) "…" else "➤", fontSize = 16.sp) }
        }
    }
}

private fun copyForTermux(ctx: Context, text: String) {
    val escaped = text.replace(Regex("```(\\w*)\\n([\\s\\S]*?)```")) { m ->
        val ext = m.groupValues[1].ifBlank { "txt" }
        "cat > file.$ext << 'EOF'\n${m.groupValues[2]}EOF\n"
    }
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("termux", escaped))
    Toast.makeText(ctx, "Скопировано для Termux", Toast.LENGTH_SHORT).show()
}
