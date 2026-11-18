package com.garickbox.deepapk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AttachedFile(
    val name: String,
    val sizeLabel: String,
    val content: String
)

private fun readAttachment(ctx: Context, uri: Uri): AttachedFile? {
    return try {
        var name = "file.txt"
        var sizeBytes = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                if (sizeIdx >= 0) sizeBytes = c.getLong(sizeIdx)
            }
        }
        val rawBytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val maxBytes = 300_000
        val truncated = rawBytes.size > maxBytes
        val slice = if (truncated) rawBytes.copyOf(maxBytes) else rawBytes
        val text = String(slice, Charsets.UTF_8) + if (truncated) "\n\n... [обрезано до 300 KB]" else ""
        val kb = (if (sizeBytes > 0) sizeBytes else rawBytes.size.toLong()) / 1024
        val label = if (kb > 0) "${kb}KB" else "${rawBytes.size}B"
        AttachedFile(name = name, sizeLabel = label, content = text)
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    isDark: Boolean,
    onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val listState = rememberLazyListState()
    val showScrollBottom by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    var settings by remember { mutableStateOf(SettingsStore.load(ctx)) }
    var input by remember { mutableStateOf("") }
    var drawerSearch by remember { mutableStateOf("") }
    var balanceText by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<AttachedFile>() }

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

    var contextMenuMsg by remember { mutableStateOf<Message?>(null) }
    var selectTextMsg by remember { mutableStateOf<Message?>(null) }
    var codePickerBlocks by remember { mutableStateOf<List<MdBlock.Code>?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val att = readAttachment(ctx, uri)
            if (att != null) {
                attachments.add(att)
            } else {
                Toast.makeText(ctx, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        settings = SettingsStore.load(ctx)
        if (settings.apiKey.isNotBlank()) {
            val b = withContext(Dispatchers.IO) { DeepSeekApi.fetchBalance(settings.apiKey) }
            if (b != null) balanceText = b.text
        }
    }

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

    fun startStream(continueTargetIdx: Int? = null) {
        if (streaming) return
        error = ""
        searchInfo = ""
        val targetIdx = if (continueTargetIdx != null) {
            continueTargetIdx
        } else {
            messages.add(Message(role = "assistant", content = ""))
            messages.size - 1
        }
        val historySlice = if (continueTargetIdx != null) {
            messages.subList(0, continueTargetIdx + 1).toList()
        } else {
            messages.dropLast(1)
        }
        if (continueTargetIdx != null) {
            messages[targetIdx] = messages[targetIdx].copy(truncated = false)
        }
        streaming = true
        streamJob = scope.launch {
            try {
                DeepSeekApi.chatStream(
                    settings = settings,
                    history = historySlice,
                    continuation = continueTargetIdx != null
                ).collect { ev ->
                    val cur = messages.getOrNull(targetIdx) ?: return@collect
                    when (ev) {
                        is StreamEvent.Reasoning -> messages[targetIdx] = cur.copy(reasoning = cur.reasoning + ev.delta)
                        is StreamEvent.Content -> messages[targetIdx] = cur.copy(content = cur.content + ev.delta)
                        is StreamEvent.SearchInfo -> searchInfo = "поиск ×${ev.queries}"
                        is StreamEvent.Error -> error = ev.msg
                        is StreamEvent.Done -> {
                            messages[targetIdx] = cur.copy(
                                usage = if (ev.usage.isNotBlank()) ev.usage else cur.usage,
                                truncated = ev.truncated
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                error = e.message ?: "unknown"
            } finally {
                val last = messages.getOrNull(targetIdx)
                if (continueTargetIdx == null && last != null && last.content.isBlank() && last.reasoning.isBlank()) {
                    messages.removeAt(targetIdx)
                }
                persistCurrentSession()
                streaming = false
                streamJob = null
            }
        }
    }

    fun sendCurrentInput() {
        if (streaming) return
        val typed = input.trim()
        if (typed.isEmpty() && attachments.isEmpty()) return

        val payload = buildString {
            if (typed.isNotEmpty()) append(typed)
            attachments.forEach { f ->
                if (isNotEmpty()) append("\n\n")
                val ext = f.name.substringAfterLast('.', "").lowercase()
                val fence = if (f.content.contains("```")) "````" else "```"
                append("[Файл: ${f.name}]\n$fence$ext\n${f.content}\n$fence")
            }
        }

        val titlePreview = typed.ifBlank { attachments.firstOrNull()?.name ?: "Чат" }
            .replace("\n", " ").take(36)

        input = ""
        attachments.clear()

        val curTitle = sessions.firstOrNull { it.id == activeSessionId }?.title
        if (messages.isEmpty() || curTitle == "Новый чат") {
            persistCurrentSession(titlePreview)
        }
        messages.add(Message(role = "user", content = payload))
        startStream()
    }

    fun handleSendMessageToTermux(content: String) {
        val blocks = TermuxBridge.extractAllCodeBlocks(content)
        when {
            blocks.isEmpty() -> Toast.makeText(ctx, "Нет блоков кода", Toast.LENGTH_SHORT).show()
            blocks.size == 1 -> TermuxBridge.sendCodeToTermux(ctx, blocks[0].lang, blocks[0].code)
            else -> codePickerBlocks = blocks
        }
    }

    if (settings.activeKey().isBlank()) {
        val isGem = settings.provider == "gemini"
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
                Text(if (isGem) "G" else "DS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Text(if (isGem) "Google Gemini" else "DeepSeek", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (isGem) "Введите API-ключ из Google AI Studio" else "Введите API-ключ с platform.deepseek.com",
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
                placeholder = { Text(if (isGem) "AIza..." else "sk-...") }
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        val s = if (isGem) settings.copy(geminiApiKey = input.trim())
                        else settings.copy(apiKey = input.trim())
                        SettingsStore.save(ctx, s)
                        settings = s
                        input = ""
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Начать работу") }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onOpenSettings) { Text("Открыть настройки") }
        }
        return
    }

    val activeTitle = sessions.firstOrNull { it.id == activeSessionId }?.title ?: "Новый чат"
    val filteredSessions = remember(sessions.toList(), drawerSearch) {
        val q = drawerSearch.trim().lowercase()
        if (q.isEmpty()) sessions.toList()
        else sessions.filter { s ->
            s.title.lowercase().contains(q) ||
                s.messages.any { it.content.lowercase().contains(q) }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !streaming,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.background
            ) {
                Column(Modifier.fillMaxHeight().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔍", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = drawerSearch,
                            onValueChange = { drawerSearch = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            ),
                            cursorBrush = SolidColor(DeepSeekBlue),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box {
                                    if (drawerSearch.isEmpty()) {
                                        Text(
                                            "Поиск в содержимом чата…",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        if (drawerSearch.isNotEmpty()) {
                            Text(
                                "✕",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { drawerSearch = "" }.padding(2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    LazyColumn(Modifier.weight(1f)) {
                        items(filteredSessions.size, key = { filteredSessions[it].id }) { i ->
                            val s = filteredSessions[i]
                            val selected = s.id == activeSessionId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.surface
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        switchSession(s)
                                        scope.launch { drawerState.close() }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = s.title,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "🗑",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable { deleteSession(s.id) }
                                        .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                scope.launch { drawerState.close() }
                                onOpenSettings()
                            }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(DeepSeekBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (settings.provider == "gemini") "G" else "DS",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("DeepApk · ${BuildConfig.VERSION_NAME}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (balanceText.isNotBlank()) "Баланс: $balanceText"
                                else settings.activeModelLabel(),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("⋯", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(Modifier.fillMaxSize()) {
                // Top bar
                val barIconColor = MaterialTheme.colorScheme.onBackground
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { if (!streaming) scope.launch { drawerState.open() } },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(18.dp)) {
                            val stroke = 2.dp.toPx()
                            drawLine(
                                color = barIconColor,
                                start = Offset(0f, size.height * 0.30f),
                                end = Offset(size.width * 0.95f, size.height * 0.30f),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = barIconColor,
                                start = Offset(0f, size.height * 0.72f),
                                end = Offset(size.width * 0.58f, size.height * 0.72f),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = activeTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Quick 1-tap provider switch (DeepSeek / Gemini)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                if (!streaming) {
                                    val next = if (settings.provider == "gemini") "deepseek" else "gemini"
                                    val updated = settings.copy(provider = next)
                                    settings = updated
                                    SettingsStore.save(ctx, updated)
                                }
                            }
                    ) {
                        Text(
                            text = if (settings.provider == "gemini") "✦ Gemini" else "DS",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DeepSeekBlue,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, barIconColor.copy(alpha = 0.75f), CircleShape)
                            .clickable { createNewSession() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = barIconColor)
                    }
                }

                if (error.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
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

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(DeepSeekBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (settings.provider == "gemini") "✦" else "DS",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                if (settings.provider == "gemini") "Привет! Я Gemini" else "Привет! Я DeepSeek",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${settings.activeModelLabel()} · Чем помочь сегодня?",
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            reverseLayout = true,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(
                                count = messages.size,
                                key = { revIdx -> messages[messages.size - 1 - revIdx].id }
                            ) { revIdx ->
                                val idx = messages.size - 1 - revIdx
                                val m = messages[idx]
                                val isUser = m.role == "user"

                                if (isUser) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(18.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier
                                                .widthIn(max = 310.dp)
                                                .clip(RoundedCornerShape(18.dp))
                                                .combinedClickable(
                                                    onClick = {},
                                                    onLongClick = { contextMenuMsg = m }
                                                )
                                        ) {
                                            Text(
                                                text = m.content,
                                                fontSize = settings.fontSizeSp.sp,
                                                lineHeight = (settings.fontSizeSp + 6).sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                            )
                                        }
                                    }
                                } else {
                                    val textColor = MaterialTheme.colorScheme.onBackground
                                    val thoughtBarColor = if (isDark) Color(0xFF3E4554) else Color(0xFFD0D5DD)
                                    val thoughtBg = if (isDark) Color(0xFF181A1F) else Color(0xFFF6F7F9)

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {},
                                                onLongClick = { contextMenuMsg = m }
                                            )
                                    ) {
                                        if (m.reasoning.isNotBlank()) {
                                            val expanded = m.id in reasoningExpanded
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(thoughtBg)
                                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                            ) {
                                                Text(
                                                    text = (if (expanded) "▼ " else "▶ ") +
                                                        "Рассуждение (${m.reasoning.length} симв.)",
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
                                                fontSizeSp = settings.fontSizeSp,
                                                onCopyCode = { _, code -> copyPlain(ctx, code, "Код скопирован") },
                                                onCopyTermux = { lang, code -> TermuxBridge.sendCodeToTermux(ctx, lang, code) }
                                            )
                                        }

                                        if (m.content.isNotBlank() && !(streaming && idx == messages.size - 1)) {
                                            Spacer(Modifier.height(6.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                                            ) {
                                                Text(
                                                    "❐",
                                                    fontSize = 15.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier
                                                        .clickable { copyPlain(ctx, m.content, "Ответ скопирован") }
                                                        .padding(vertical = 2.dp)
                                                )
                                                if (idx == messages.size - 1 && !streaming) {
                                                    Text(
                                                        "↻",
                                                        fontSize = 16.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier
                                                            .clickable {
                                                                messages.removeAt(idx)
                                                                startStream()
                                                            }
                                                            .padding(vertical = 2.dp)
                                                    )
                                                    Text(
                                                        if (m.truncated) "⏭ Продолжить (обрыв)" else "⏭ Продолжить",
                                                        fontSize = 12.sp,
                                                        fontWeight = if (m.truncated) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = if (m.truncated) DeepSeekBlue
                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier
                                                            .clickable { startStream(continueTargetIdx = idx) }
                                                            .padding(vertical = 2.dp)
                                                    )
                                                }
                                                Spacer(Modifier.weight(1f))
                                                if (m.usage.isNotBlank()) {
                                                    Text(
                                                        m.usage,
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (showScrollBottom) {
                        Surface(
                            shape = CircleShape,
                            shadowElevation = 4.dp,
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(
                                1.dp,
                                if (isDark) Color(0xFF2D3139) else Color(0xFFE5E7EB)
                            ),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 14.dp, bottom = 10.dp)
                                .size(38.dp)
                                .clip(CircleShape)
                                .clickable {
                                    scope.launch { listState.animateScrollToItem(0) }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("⌄", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                    }
                }

                // Bottom floating composer card
                val cardBg = if (isDark) Color(0xFF1A1C22) else Color(0xFFFFFFFF)
                val cardBorder = if (isDark) Color(0xFF2D3139) else Color(0xFFE6E8EE)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .shadow(2.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, cardBorder, RoundedCornerShape(24.dp))
                        .background(cardBg)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (attachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            attachments.forEachIndexed { idx, f ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, cardBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("📄 ${f.name} (${f.sizeLabel})", fontSize = 12.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "✕",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.clickable { attachments.removeAt(idx) }
                                        )
                                    }
                                }
                            }
                        }
                    }

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
                                        "Напишите сообщение…",
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModePill(
                            label = "⚛ Рассуждение",
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

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), CircleShape)
                                .clickable { filePicker.launch(arrayOf("*/*")) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "+",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        if (streaming) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(DeepSeekBlue)
                                    .clickable { streamJob?.cancel() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("■", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            val canSend = input.isNotBlank() || attachments.isNotEmpty()
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (canSend) DeepSeekBlue
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                                    )
                                    .clickable(enabled = canSend) { sendCurrentInput() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("↑", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Long-press Context Menu Dialog
    contextMenuMsg?.let { m ->
        val hasCode = TermuxBridge.hasCodeBlocks(m.content)
        val dividerCol = if (isDark) Color(0xFF2B2F38) else Color(0xFFF0F1F4)

        Dialog(onDismissRequest = { contextMenuMsg = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.widthIn(min = 240.dp, max = 290.dp)
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    ContextMenuItem("❐", "Копировать") {
                        copyPlain(ctx, m.content, "Скопировано")
                        contextMenuMsg = null
                    }
                    HorizontalDivider(color = dividerCol)
                    ContextMenuItem("⬚", "Выбрать текст") {
                        selectTextMsg = m
                        contextMenuMsg = null
                    }
                    HorizontalDivider(color = dividerCol)
                    ContextMenuItem("↻", "Повторить") {
                        val target = m
                        contextMenuMsg = null
                        if (!streaming) {
                            if (target.role == "assistant") {
                                val pos = messages.indexOfFirst { it.id == target.id }
                                if (pos >= 0) {
                                    while (messages.size > pos) {
                                        messages.removeAt(messages.size - 1)
                                    }
                                    startStream()
                                }
                            } else {
                                messages.add(Message(role = "user", content = target.content))
                                startStream()
                            }
                        }
                    }
                    if (m.role == "assistant") {
                        HorizontalDivider(color = dividerCol)
                        ContextMenuItem("⏭", "Продолжить") {
                            val target = m
                            contextMenuMsg = null
                            val pos = messages.indexOfFirst { it.id == target.id }
                            if (pos >= 0 && !streaming) {
                                startStream(continueTargetIdx = pos)
                            }
                        }
                    }
                    if (hasCode) {
                        HorizontalDivider(color = dividerCol)
                        ContextMenuItem("▶", "Передать в Termux", accent = DeepSeekBlue) {
                            val content = m.content
                            contextMenuMsg = null
                            handleSendMessageToTermux(content)
                        }
                    }
                }
            }
        }
    }

    // Specific Code Block Picker Dialog (when message has 2+ blocks)
    codePickerBlocks?.let { blocks ->
        val dividerCol = if (isDark) Color(0xFF2B2F38) else Color(0xFFF0F1F4)
        Dialog(onDismissRequest = { codePickerBlocks = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Выберите блок для Termux",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        blocks.forEachIndexed { idx, b ->
                            val firstLine = b.code.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        codePickerBlocks = null
                                        TermuxBridge.sendCodeToTermux(ctx, b.lang, b.code)
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    "Блок #${idx + 1} · ${b.lang.ifBlank { "code" }} (${b.code.lines().size} стр.)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = DeepSeekBlue
                                )
                                if (firstLine.isNotBlank()) {
                                    Text(
                                        firstLine,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (idx < blocks.lastIndex) HorizontalDivider(color = dividerCol)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { codePickerBlocks = null },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Отмена") }
                }
            }
        }
    }

    // Selectable Text Dialog ("Выбрать текст")
    selectTextMsg?.let { m ->
        Dialog(onDismissRequest = { selectTextMsg = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Выбрать текст",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "✕",
                            fontSize = 18.sp,
                            modifier = Modifier.clickable { selectTextMsg = null }.padding(4.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SelectionContainer(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = m.content,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            copyPlain(ctx, m.content, "Скопировано полностью")
                            selectTextMsg = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Копировать всё") }
                }
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    icon: String,
    label: String,
    accent: Color? = null,
    onClick: () -> Unit
) {
    val col = accent ?: MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 17.sp, color = col, modifier = Modifier.width(28.dp))
        Text(label, fontSize = 15.5.sp, color = col, fontWeight = FontWeight.Normal)
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
        active && isDark -> Color(0xFF1F2A48)
        active -> Color(0xFFE8F0FE)
        isDark -> Color(0xFF23262E)
        else -> Color(0xFFFFFFFF)
    }
    val border = if (active) DeepSeekBlue.copy(alpha = 0.6f)
    else (if (isDark) Color(0xFF353944) else Color(0xFFE0E3EA))
    val textCol = if (active) (if (isDark) Color(0xFF8AA4FF) else DeepSeekBlue)
    else MaterialTheme.colorScheme.onBackground

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
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = textCol,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
        )
    }
}

private fun copyPlain(ctx: Context, text: String, toastMsg: String = "Скопировано") {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("deepapk", text))
    Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
}
