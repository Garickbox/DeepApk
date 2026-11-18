package com.garickbox.deepapk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed class MsgSegment {
    data class Text(val text: String) : MsgSegment()
    data class Code(val lang: String, val code: String) : MsgSegment()
}

private fun parseSegments(input: String): List<MsgSegment> {
    val result = mutableListOf<MsgSegment>()
    val regex = Regex("```([^\\n`]*)\\r?\\n([\\s\\S]*?)```")
    var last = 0
    for (m in regex.findAll(input)) {
        if (m.range.first > last) {
            val plain = input.substring(last, m.range.first).trim('\n')
            if (plain.isNotEmpty()) result.add(MsgSegment.Text(plain))
        }
        val lang = m.groupValues[1].trim()
        val code = m.groupValues[2].trimEnd('\r', '\n')
        result.add(MsgSegment.Code(lang, code))
        last = m.range.last + 1
    }
    if (last < input.length) {
        val tail = input.substring(last).trimStart('\n')
        if (tail.isNotEmpty()) result.add(MsgSegment.Text(tail))
    }
    if (result.isEmpty() && input.isNotEmpty()) {
        result.add(MsgSegment.Text(input))
    }
    return result
}

@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(SettingsStore.load(ctx)) }
    var input by remember { mutableStateOf("") }
    val sessions = remember {
        mutableStateListOf<ChatSession>().apply {
            val loaded = ChatStore.loadSessions(ctx)
            if (loaded.isEmpty()) {
                add(
                    ChatSession(
                        id = System.currentTimeMillis().toString(),
                        title = "Новый чат",
                        updatedAt = System.currentTimeMillis(),
                        messages = emptyList()
                    )
                )
            } else {
                addAll(loaded)
            }
        }
    }
    var activeSessionId by remember {
        mutableStateOf(
            ChatStore.getActiveSessionId(ctx)
                ?.takeIf { id -> sessions.any { it.id == id } }
                ?: sessions.first().id
        )
    }
    val messages = remember {
        mutableStateListOf<Message>().apply {
            sessions.firstOrNull { it.id == activeSessionId }?.messages?.let { addAll(it) }
        }
    }

    var streaming by remember { mutableStateOf(false) }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf("") }
    var searchInfo by remember { mutableStateOf("") }
    var reasoningExpanded by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showSessionsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { settings = SettingsStore.load(ctx) }

    fun persistCurrentSession(customTitle: String? = null) {
        val idx = sessions.indexOfFirst { it.id == activeSessionId }
        val now = System.currentTimeMillis()
        if (idx >= 0) {
            val cur = sessions[idx]
            val title = customTitle ?: cur.title
            sessions[idx] = cur.copy(title = title, updatedAt = now, messages = messages.toList())
        }
        ChatStore.setActiveSessionId(ctx, activeSessionId)
        ChatStore.saveSessions(ctx, sessions.toList())
    }

    fun switchSession(session: ChatSession) {
        if (streaming) return
        activeSessionId = session.id
        messages.clear()
        messages.addAll(session.messages)
        reasoningExpanded = emptySet()
        error = ""
        searchInfo = ""
        ChatStore.setActiveSessionId(ctx, session.id)
    }

    fun createNewSession() {
        if (streaming) return
        val s = ChatSession(
            id = System.currentTimeMillis().toString(),
            title = "Новый чат",
            updatedAt = System.currentTimeMillis(),
            messages = emptyList()
        )
        sessions.add(0, s)
        switchSession(s)
        ChatStore.saveSessions(ctx, sessions.toList())
    }

    fun deleteSession(id: String) {
        if (streaming) return
        sessions.removeAll { it.id == id }
        if (sessions.isEmpty()) {
            sessions.add(
                ChatSession(
                    id = System.currentTimeMillis().toString(),
                    title = "Новый чат",
                    updatedAt = System.currentTimeMillis(),
                    messages = emptyList()
                )
            )
        }
        if (activeSessionId == id || sessions.none { it.id == activeSessionId }) {
            switchSession(sessions.first())
        }
        ChatStore.saveSessions(ctx, sessions.toList())
    }

    fun startStream() {
        if (streaming) return
        error = ""
        searchInfo = ""
        messages.add(Message(role = "assistant", content = ""))
        val newIdx = messages.size - 1
        streaming = true
        streamJob = scope.launch {
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
                    val cur = messages.getOrNull(newIdx) ?: return@collect
                    when (ev) {
                        is StreamEvent.Reasoning -> messages[newIdx] = cur.copy(reasoning = cur.reasoning + ev.delta)
                        is StreamEvent.Content -> messages[newIdx] = cur.copy(content = cur.content + ev.delta)
                        is StreamEvent.SearchInfo -> searchInfo = "поиск ×${ev.queries}"
                        is StreamEvent.Error -> error = ev.msg
                        is StreamEvent.Done -> {
                            if (ev.usage.isNotBlank()) {
                                messages[newIdx] = cur.copy(usage = ev.usage)
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                error = e.message ?: "unknown"
            } finally {
                val last = messages.getOrNull(newIdx)
                if (last != null && last.content.isBlank() && last.reasoning.isBlank()) {
                    messages.removeAt(newIdx)
                }
                persistCurrentSession()
                streaming = false
                streamJob = null
            }
        }
    }

    if (settings.apiKey.isBlank()) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("DeepApk v${BuildConfig.VERSION_NAME}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Введите DeepSeek API Key (platform.deepseek.com):", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("sk-...") }
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        val s = settings.copy(apiKey = input.trim())
                        SettingsStore.save(ctx, s)
                        settings = s
                        input = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }
        }
        return
    }

    val activeTitle = sessions.firstOrNull { it.id == activeSessionId }?.title ?: "DeepApk"

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).clickable { if (!streaming) showSessionsDialog = true }) {
                    Text(
                        activeTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val shownModel = if (settings.webSearchEnabled) "deepseek-flash" else settings.model
                    Text(
                        "b${BuildConfig.VERSION_CODE} · $shownModel" +
                            (if (settings.thinkingEnabled) " · 🧠" else "") +
                            (if (settings.webSearchEnabled) " · 🌐" else "") +
                            (if (searchInfo.isNotBlank()) " ($searchInfo)" else ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "💬",
                    fontSize = 19.sp,
                    modifier = Modifier.clickable { if (!streaming) showSessionsDialog = true }.padding(6.dp)
                )
                Text(
                    "➕",
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { createNewSession() }.padding(6.dp)
                )
                Text(
                    "🗑",
                    fontSize = 19.sp,
                    modifier = Modifier.clickable {
                        if (!streaming) {
                            messages.clear()
                            reasoningExpanded = emptySet()
                            error = ""
                            searchInfo = ""
                            persistCurrentSession("Новый чат")
                        }
                    }.padding(6.dp)
                )
                Text(
                    "⚙",
                    fontSize = 20.sp,
                    modifier = Modifier.clickable { onOpenSettings() }.padding(6.dp)
                )
            }
        }

        if (error.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "❌ $error",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "✕",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.clickable { error = "" }.padding(4.dp)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                count = messages.size,
                key = { revIdx -> messages[messages.size - 1 - revIdx].id }
            ) { revIdx ->
                val idx = messages.size - 1 - revIdx
                val m = messages[idx]
                val isUser = m.role == "user"
                val cardColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
                val textColor = if (isUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cardColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isUser) "Вы" else "DeepSeek",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor.copy(alpha = 0.75f),
                                modifier = Modifier.weight(1f)
                            )
                            if (m.usage.isNotBlank()) {
                                Text(
                                    m.usage,
                                    fontSize = 10.sp,
                                    color = textColor.copy(alpha = 0.65f)
                                )
                            }
                        }

                        if (m.reasoning.isNotBlank()) {
                            val expanded = m.id in reasoningExpanded
                            Text(
                                (if (expanded) "▼" else "▶") + " 💭 Рассуждения (${m.reasoning.length} симв.)",
                                fontSize = 11.sp,
                                color = textColor.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .clickable {
                                        reasoningExpanded = if (expanded) {
                                            reasoningExpanded - m.id
                                        } else {
                                            reasoningExpanded + m.id
                                        }
                                    }
                                    .padding(vertical = 4.dp)
                            )
                            if (expanded) {
                                SelectionContainer {
                                    Text(
                                        m.reasoning,
                                        fontSize = 11.sp,
                                        color = textColor.copy(alpha = 0.8f),
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                }
                            }
                        }

                        val segments = remember(m.content) { parseSegments(m.content) }
                        if (segments.isEmpty() && streaming && idx == messages.size - 1) {
                            Text("Думаю…", fontSize = 13.sp, color = textColor.copy(alpha = 0.6f))
                        } else {
                            segments.forEach { seg ->
                                when (seg) {
                                    is MsgSegment.Text -> {
                                        SelectionContainer {
                                            Text(
                                                seg.text,
                                                fontSize = 14.sp,
                                                color = textColor,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                            )
                                        }
                                    }
                                    is MsgSegment.Code -> {
                                        Spacer(Modifier.height(4.dp))
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF1A1B1E))
                                        ) {
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF282A2F))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    seg.lang.ifBlank { "code" },
                                                    fontSize = 11.sp,
                                                    color = Color(0xFFAAAAAA),
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    "📋 Код",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF8AB4F8),
                                                    modifier = Modifier
                                                        .clickable { copyPlain(ctx, seg.code) }
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                                Text(
                                                    "▶ Termux",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF81C995),
                                                    modifier = Modifier
                                                        .clickable { copyBlockForTermux(ctx, seg.lang, seg.code) }
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            SelectionContainer {
                                                Text(
                                                    seg.code,
                                                    fontSize = 12.sp,
                                                    color = Color(0xFFE8EAED),
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier
                                                        .horizontalScroll(rememberScrollState())
                                                        .padding(8.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                        }

                        if (!isUser && m.content.isNotBlank()) {
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TextButton(
                                    onClick = { copyPlain(ctx, m.content) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("📋 Всё", fontSize = 11.sp) }
                                if (m.content.contains("```")) {
                                    TextButton(
                                        onClick = { copyAllForTermux(ctx, m.content) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) { Text("▶ Все блоки", fontSize = 11.sp) }
                                }
                                if (idx == messages.size - 1 && !streaming) {
                                    TextButton(
                                        onClick = {
                                            messages.removeAt(idx)
                                            startStream()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) { Text("🔄 Повтор", fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                    placeholder = { Text("Сообщение…", fontSize = 14.sp) }
                )
                Spacer(Modifier.width(6.dp))
                if (streaming) {
                    FilledTonalButton(
                        onClick = { streamJob?.cancel() },
                        modifier = Modifier.height(52.dp)
                    ) { Text("⏹", fontSize = 16.sp) }
                } else {
                    Button(
                        onClick = {
                            if (input.isBlank()) return@Button
                            val userText = input.trim()
                            input = ""
                            val curTitle = sessions.firstOrNull { it.id == activeSessionId }?.title
                            if (messages.isEmpty() || curTitle == "Новый чат") {
                                persistCurrentSession(userText.replace("\n", " ").take(36))
                            }
                            messages.add(Message(role = "user", content = userText))
                            startStream()
                        },
                        modifier = Modifier.height(52.dp)
                    ) { Text("➤", fontSize = 16.sp) }
                }
            }
        }
    }

    if (showSessionsDialog) {
        AlertDialog(
            onDismissRequest = { showSessionsDialog = false },
            title = { Text("Чаты (${sessions.size})", fontSize = 17.sp) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            createNewSession()
                            showSessionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("➕ Новый чат") }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(sessions.size, key = { sessions[it].id }) { i ->
                            val s = sessions[i]
                            val selected = s.id == activeSessionId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        switchSession(s)
                                        showSessionsDialog = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        s.title,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${s.messages.size} сообщ.",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "🗑",
                                    fontSize = 16.sp,
                                    modifier = Modifier
                                        .clickable { deleteSession(s.id) }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSessionsDialog = false }) { Text("Закрыть") }
            }
        )
    }
}

private fun copyPlain(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("deepapk", text))
    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
}

private fun safeMarker(code: String): String {
    var marker = "DEEPAPK_EOF"
    var n = 1
    while (code.contains(marker)) {
        marker = "DEEPAPK_EOF_$n"
        n++
    }
    return marker
}

private fun copyBlockForTermux(ctx: Context, lang: String, code: String) {
    val l = lang.lowercase()
    val payload = if (l in listOf("", "sh", "bash", "zsh", "shell")) {
        code
    } else {
        val m = safeMarker(code)
        "cat > file.$l << '$m'\n$code\n$m\n"
    }
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("termux", payload))
    Toast.makeText(ctx, "Скопировано для Termux", Toast.LENGTH_SHORT).show()
}

private fun copyAllForTermux(ctx: Context, text: String) {
    val blocks = parseSegments(text).filterIsInstance<MsgSegment.Code>()
    val payload = blocks.joinToString("\n") { b ->
        val l = b.lang.lowercase()
        if (l in listOf("sh", "bash", "zsh", "shell")) {
            b.code
        } else {
            val ext = l.ifBlank { "txt" }
            val m = safeMarker(b.code)
            "cat > file.$ext << '$m'\n${b.code}\n$m"
        }
    }
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("termux", payload))
    Toast.makeText(ctx, "Скопировано для Termux (${blocks.size})", Toast.LENGTH_SHORT).show()
}
