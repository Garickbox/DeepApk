package com.garickbox.deepapk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

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

    fun sendUserText(rawText: String) {
        val userText = rawText.trim()
        if (userText.isEmpty() || streaming) return
        input = ""
        val curTitle = sessions.firstOrNull { it.id == activeSessionId }?.title
        if (messages.isEmpty() || curTitle == "Новый чат") {
            persistCurrentSession(userText.replace("\n", " ").take(36))
        }
        messages.add(Message(role = "user", content = userText))
        startStream()
    }

    if (settings.apiKey.isBlank()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(DeepSeekBlue),
                contentAlignment = Alignment.Center
            ) {
                Text("DS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Text("DeepSeek", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Введите API-ключ с platform.deepseek.com",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("sk-...") }
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        val s = settings.copy(apiKey = input.trim())
                        SettingsStore.save(ctx, s)
                        settings = s
                        input = ""
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Начать работу") }
        }
        return
    }

    val activeTitle = sessions.firstOrNull { it.id == activeSessionId }?.title ?: "Новый чат"

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !streaming,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(290.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxHeight().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(DeepSeekBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("DS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("DeepApk", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            createNewSession()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("✎  Новый чат") }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "История чатов",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )

                    LazyColumn(Modifier.weight(1f)) {
                        items(sessions.size, key = { sessions[it].id }) { i ->
                            val s = sessions[i]
                            val selected = s.id == activeSessionId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        switchSession(s)
                                        scope.launch { drawerState.close() }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        s.title,
                                        fontSize = 13.5.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${s.messages.size} сообщ.",
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "🗑",
                                    fontSize = 15.sp,
                                    modifier = Modifier
                                        .clickable { deleteSession(s.id) }
                                        .padding(6.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch { drawerState.close() }
                                onOpenSettings()
                            }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚙", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("Настройки и обновления", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Top bar (DeepSeek style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "☰",
                    fontSize = 21.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { if (!streaming) scope.launch { drawerState.open() } }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = activeTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val shownModel = if (settings.webSearchEnabled) "deepseek-flash" else settings.model
                    Text(
                        text = shownModel + (if (searchInfo.isNotBlank()) " · 🌐 $searchInfo" else ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "✎",
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { createNewSession() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            if (error.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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

            // Messages area or DeepSeek Welcome Screen
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(DeepSeekBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("DS", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Привет! Я DeepSeek",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Чем я могу помочь сегодня?",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(
                        count = messages.size,
                        key = { revIdx -> messages[messages.size - 1 - revIdx].id }
                    ) { revIdx ->
                        val idx = messages.size - 1 - revIdx
                        val m = messages[idx]
                        val isUser = m.role == "user"

                        if (isUser) {
                            // User bubble aligned right (DeepSeek style)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.widthIn(max = 310.dp)
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = m.content,
                                            fontSize = 15.sp,
                                            lineHeight = 21.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Assistant clean stream on left (DeepSeek style)
                            val textColor = MaterialTheme.colorScheme.onBackground
                            val thoughtBarColor = if (isDark) Color(0xFF3E4554) else Color(0xFFD0D5DD)
                            val thoughtBg = if (isDark) Color(0xFF181A1F) else Color(0xFFF6F7F9)

                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (m.reasoning.isNotBlank()) {
                                    val expanded = m.id in reasoningExpanded
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(thoughtBg)
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = (if (expanded) "▼ " else "▶ ") +
                                                "Глубокое мышление (${m.reasoning.length} симв.)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    reasoningExpanded = if (expanded) {
                                                        reasoningExpanded - m.id
                                                    } else {
                                                        reasoningExpanded + m.id
                                                    }
                                                }
                                        )
                                        if (expanded) {
                                            Spacer(Modifier.height(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .drawBehind {
                                                        drawLine(
                                                            color = thoughtBarColor,
                                                            start = Offset(0f, 0f),
                                                            end = Offset(0f, size.height),
                                                            strokeWidth = 2.dp.toPx()
                                                        )
                                                    }
                                                    .padding(start = 10.dp)
                                            ) {
                                                SelectionContainer {
                                                    Text(
                                                        text = m.reasoning,
                                                        fontSize = 12.sp,
                                                        lineHeight = 17.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }

                                if (m.content.isBlank() && streaming && idx == messages.size - 1) {
                                    Text(
                                        "Печатаю…",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    MarkdownMessageContent(
                                        content = m.content,
                                        textColor = textColor,
                                        isDark = isDark,
                                        onCopyCode = { _, code -> copyPlain(ctx, code, "Код скопирован") },
                                        onCopyTermux = { lang, code -> copyBlockForTermux(ctx, lang, code) }
                                    )
                                }

                                if (m.content.isNotBlank() && !(streaming && idx == messages.size - 1)) {
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            "📋 Копировать",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .clickable { copyPlain(ctx, m.content, "Ответ скопирован") }
                                                .padding(vertical = 4.dp)
                                        )
                                        if (idx == messages.size - 1 && !streaming) {
                                            Text(
                                                "🔄 Повторить",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .clickable {
                                                        messages.removeAt(idx)
                                                        startStream()
                                                    }
                                                    .padding(vertical = 4.dp)
                                            )
                                        }
                                        Spacer(Modifier.weight(1f))
                                        if (m.usage.isNotBlank()) {
                                            Text(
                                                m.usage,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Composer (DeepSeek rounded capsule with Thinking + Search pills)
            val composerBg = if (isDark) Color(0xFF1B1D22) else Color(0xFFF3F4F6)
            val composerBorder = if (isDark) Color(0xFF2D3139) else Color(0xFFE3E5EA)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, composerBorder, RoundedCornerShape(22.dp))
                    .background(composerBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp, max = 130.dp),
                    maxLines = 6,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    ),
                    cursorBrush = SolidColor(DeepSeekBlue),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (input.isEmpty()) {
                                Text(
                                    "Написать DeepSeek…",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModePill(
                        label = "🧠 Мышление",
                        active = settings.thinkingEnabled,
                        isDark = isDark,
                        onClick = {
                            val updated = settings.copy(thinkingEnabled = !settings.thinkingEnabled)
                            settings = updated
                            SettingsStore.save(ctx, updated)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    ModePill(
                        label = "🌐 Поиск",
                        active = settings.webSearchEnabled,
                        isDark = isDark,
                        onClick = {
                            val updated = settings.copy(webSearchEnabled = !settings.webSearchEnabled)
                            settings = updated
                            SettingsStore.save(ctx, updated)
                        }
                    )
                    Spacer(Modifier.weight(1f))

                    if (streaming) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DeepSeekBlue)
                                .clickable { streamJob?.cancel() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("■", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        val canSend = input.isNotBlank()
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) DeepSeekBlue
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                                )
                                .clickable(enabled = canSend) { sendUserText(input) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↑", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    active: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val bg = when {
        active && isDark -> Color(0xFF202B4A)
        active -> Color(0xFFE3EBFF)
        isDark -> Color(0xFF23262E)
        else -> Color(0xFFFFFFFF)
    }
    val border = if (active) DeepSeekBlue else (if (isDark) Color(0xFF353944) else Color(0xFFD5D8DF))
    val textCol = if (active) (if (isDark) Color(0xFF8AA4FF) else DeepSeekBlue)
    else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = textCol,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

private fun copyPlain(ctx: Context, text: String, toastMsg: String = "Скопировано") {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("deepapk", text))
    Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
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
    val ext = lang.lowercase().ifBlank { "txt" }
    val m = safeMarker(code)
    val payload = "cat > file.$ext << '$m'\n$code\n$m\n"
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("termux", payload))
    Toast.makeText(ctx, "Скопировано для Termux", Toast.LENGTH_SHORT).show()
}
