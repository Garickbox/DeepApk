package com.garickbox.deepapk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ChatScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("deepapk", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(ChatStore.load(ctx)) }
    var streaming by remember { mutableStateOf(false) }

    if (apiKey.isBlank()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text("Введите DeepSeek API Key:", fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().background(Color.LightGray).padding(8.dp),
                textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { if (input.isNotBlank()) { apiKey = input; prefs.edit().putString("api_key", apiKey).apply(); input = "" } }, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить")
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        LazyColumn(Modifier.weight(1f), reverseLayout = true) {
            items(messages.reversed()) { m ->
                Column(Modifier.fillMaxWidth().padding(4.dp)) {
                    Text(if (m.role == "user") "Вы" else "DeepSeek", fontSize = 11.sp, color = Color.Gray)
                    Text(m.content, fontSize = 14.sp)
                    if (m.role == "assistant" && m.content.contains("```")) {
                        TextButton(onClick = { copyForTermux(ctx, m.content) }) { Text("📋 Копировать в Termux", fontSize = 11.sp) }
                    }
                    HorizontalDivider()
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f).background(Color(0xFFF0F0F0)).padding(8.dp),
                textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = {
                    if (input.isBlank() || streaming) return@Button
                    messages.add(Message("user", input)); input = ""
                    streaming = true
                    messages.add(Message("assistant", ""))
                    scope.launch {
                        try {
                            DeepSeekApi.chatStream(apiKey, messages.dropLast(1)).collect { delta ->
                                val last = messages.last()
                                messages[messages.lastIndex] = last.copy(content = last.content + delta)
                            }
                        } catch (e: Exception) {
                            val last = messages.last()
                            messages[messages.lastIndex] = last.copy(content = "❌ ${e.message}")
                        }
                        ChatStore.save(ctx, messages)
                        streaming = false
                    }
                },
                enabled = !streaming
            ) { Text(if (streaming) "…" else "➤") }
        }
    }
}

private fun copyForTermux(ctx: Context, text: String) {
    val escaped = text.replace(Regex("```(\\w*)\\n([\\s\\S]*?)```")) { m ->
        "cat > file.${m.groupValues[1].ifBlank { "sh" }} << 'EOF'\n${m.groupValues[2]}EOF\n"
    }
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("termux", escaped))
    Toast.makeText(ctx, "Скопировано для Termux", Toast.LENGTH_SHORT).show()
}
