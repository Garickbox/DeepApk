package com.garickbox.deepapk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private sealed class ChatFeedItem(val key: String) {
    data class UserBubble(val msgIdx: Int, val msg: Message) : ChatFeedItem("u_${msg.id}")
    data class AssistantReasoning(val msgIdx: Int, val msg: Message) : ChatFeedItem("r_${msg.id}")
    data class AssistantPending(val msgIdx: Int, val msg: Message) : ChatFeedItem("p_${msg.id}")
    data class AssistantBlock(val msgIdx: Int, val msg: Message, val blockIdx: Int, val block: MdBlock) :
        ChatFeedItem("b_${msg.id}_$blockIdx")
    data class AssistantFooter(val msgIdx: Int, val msg: Message) : ChatFeedItem("f_${msg.id}")
}

private const val SECTOR_SIZE = 28
private const val SECTOR_STEP = 20

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    isDark: Boolean,
    incomingShare: IncomingShare? = null,
    onConsumeShare: () -> Unit = {},
    onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val showScrollBottom by remember { derivedStateOf { listState.canScrollForward } }

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
    var showAnchorsDialog by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(PresetStore.load(ctx)) }
    var activePresetId by remember {
        mutableStateOf(
            sessions.firstOrNull { it.id == activeSessionId }?.presetId ?: "default"
        )
    }
    var showPresetsDialog by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<PromptPreset?>(null) }
    var addSkillDraft by remember { mutableStateOf<String?>(null) }
    var sectorEnd by remember { mutableStateOf(messages.size) }
    var sectorStart by remember { mutableStateOf((messages.size - SECTOR_SIZE).coerceAtLeast(0)) }

    fun resetSectorToTail() {
        sectorEnd = messages.size
        sectorStart = (messages.size - SECTOR_SIZE).coerceAtLeast(0)
    }

    fun focusSectorAround(msgIdx: Int) {
        sectorStart = (msgIdx - 10).coerceAtLeast(0)
        sectorEnd = (msgIdx + 20).coerceAtMost(messages.size)
    }

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
        if (messages.isNotEmpty()) {
            resetSectorToTail()
            listState.scrollToItem(9999)
        }
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
            val updated = cur.copy(title = title, updatedAt = now, messages = messages.toList())
            sessions[idx] = updated
            ChatStore.saveSessionAndIndexAsync(ctx, updated, sessions.toList())
        } else {
            ChatStore.saveSessions(ctx, sessions.toList())
        }
        ChatStore.setActiveSessionId(ctx, activeSessionId)
    }

    fun setChatPreset(pid: String) {
        activePresetId = pid
        val idx = sessions.indexOfFirst { it.id == activeSessionId }
        if (idx >= 0) {
            sessions[idx] = sessions[idx].copy(presetId = pid)
            ChatStore.saveSessions(ctx, sessions.toList())
        }
    }

    fun switchSession(session: ChatSession) {
        if (streaming) return
        activeSessionId = session.id
        activePresetId = session.presetId
        val loadedMsgs = if (session.messages.isNotEmpty()) session.messages
        else ChatStore.loadSessionMessages(ctx, session.id)
        val sIdx = sessions.indexOfFirst { it.id == session.id }
        if (sIdx >= 0 && sessions[sIdx].messages.isEmpty() && loadedMsgs.isNotEmpty()) {
            sessions[sIdx] = sessions[sIdx].copy(messages = loadedMsgs)
        }
        messages.clear()
        messages.addAll(loadedMsgs)
        resetSectorToTail()
        reasoningExpanded = emptySet()
        error = ""
        searchInfo = ""
        ChatStore.setActiveSessionId(ctx, session.id)
        if (messages.isNotEmpty()) {
            scope.launch { listState.scrollToItem(9999) }
        }
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
        ChatStore.deleteSessionFiles(ctx, id)
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

    fun startStream(continueTargetIdx: Int? = null, scrollAnchorIdx: Int? = null) {
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
        resetSectorToTail()
        val anchorMsg = scrollAnchorIdx ?: (targetIdx - 1).coerceAtLeast(0)
        scope.launch {
            kotlinx.coroutines.yield()
            val pos = messages.subList(sectorStart, sectorEnd.coerceAtMost(messages.size))
                .indexOfFirst { it.id == messages.getOrNull(anchorMsg)?.id }
            if (pos >= 0) listState.animateScrollToItem(pos + if (sectorStart > 0) 1 else 0)
        }
        streaming = true
        streamJob = scope.launch {
            val pendingContent = StringBuilder()
            val pendingReasoning = StringBuilder()
            var lastFlush = 0L

            fun flushDeltas(force: Boolean) {
                if (pendingContent.isEmpty() && pendingReasoning.isEmpty()) return
                val now = System.currentTimeMillis()
                if (!force && now - lastFlush < 75L) return
                val cur = messages.getOrNull(targetIdx) ?: return
                messages[targetIdx] = cur.copy(
                    reasoning = cur.reasoning + pendingReasoning.toString(),
                    content = cur.content + pendingContent.toString()
                )
                pendingReasoning.clear()
                pendingContent.clear()
                lastFlush = now
            }

            val activePreset = presets.firstOrNull { it.id == activePresetId } ?: presets.first()
            val extraGlobal = if (settings.systemPrompt.trim() == AppSettings.DEFAULT_SYSTEM_PROMPT.trim()) "" else settings.systemPrompt.trim()
            val effectiveSettings = settings.copy(systemPrompt = activePreset.buildSystemPrompt(extraGlobal))
            try {
                DeepSeekApi.chatStream(
                    settings = effectiveSettings,
                    history = historySlice,
                    continuation = continueTargetIdx != null
                ).collect { ev ->
                    when (ev) {
                        is StreamEvent.Reasoning -> {
                            pendingReasoning.append(ev.delta)
                            flushDeltas(false)
                        }
                        is StreamEvent.Content -> {
                            pendingContent.append(ev.delta)
                            flushDeltas(false)
                        }
                        is StreamEvent.SearchInfo -> searchInfo = "поиск ×${ev.queries}"
                        is StreamEvent.Error -> {
                            flushDeltas(true)
                            error = ev.msg
                        }
                        is StreamEvent.Done -> {
                            flushDeltas(true)
                            val cur = messages.getOrNull(targetIdx)
                            if (cur != null) {
                                messages[targetIdx] = cur.copy(
                                    usage = if (ev.usage.isNotBlank()) ev.usage else cur.usage,
                                    truncated = ev.truncated
                                )
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                flushDeltas(true)
            } catch (e: Exception) {
                flushDeltas(true)
                error = e.message ?: "unknown"
            } finally {
                flushDeltas(true)
                val doneMsg = messages.getOrNull(targetIdx)
                if (doneMsg != null && doneMsg.content.contains("<skill_save>")) {
                    val rx = Regex("<skill_save>([\\s\\S]*?)</skill_save>")
                    val rules = rx.findAll(doneMsg.content).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
                    if (rules.isNotEmpty()) {
                        val targetPid = if (activePresetId == "default") "termux" else activePresetId
                        rules.forEach { PresetStore.appendSkill(ctx, targetPid, it) }
                        presets = PresetStore.load(ctx)
                        val tName = presets.firstOrNull { it.id == targetPid }?.name ?: targetPid
                        val clean = rx.replace(doneMsg.content, "").trimEnd() +
                            "\n\n> 🧠 Правило сохранено в заготовку «$tName»"
                        messages[targetIdx] = doneMsg.copy(content = clean)
                    }
                }
                val last = messages.getOrNull(targetIdx)
                if (continueTargetIdx == null && last != null && last.content.isBlank() && last.reasoning.isBlank()) {
                    messages.removeAt(targetIdx)
                } else if (last != null && last.content.isNotBlank()) {
                    val docRep = extractDocReport(last.content)
                    if (docRep != null && docRep.isComplete) {
                        withContext(Dispatchers.IO) { DocxEngine.saveHtmlSilently(ctx, docRep.title, DocxEngine.buildPreviewHtml(docRep)) }
                    } else {
                        DocxEngine.extractStandaloneHtmlPage(last.content)?.let { (t, h) ->
                            withContext(Dispatchers.IO) { DocxEngine.saveHtmlSilently(ctx, t, h) }
                        }
                    }
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
        keyboardController?.hide()
        focusManager.clearFocus()

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
        startStream(scrollAnchorIdx = messages.size - 1)
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
                        DeepWhaleLogo(sizeDp = 34, isGemini = settings.provider == "gemini")
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
                    val topPreset = presets.firstOrNull { it.id == activePresetId } ?: presets.first()
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeTitle,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (topPreset.id != "default") DeepSeekBlue.copy(alpha = if (isDark) 0.22f else 0.11f)
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable { if (!streaming) showPresetsDialog = true }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${topPreset.emoji} ${topPreset.name} ▾",
                                fontSize = 11.5.sp,
                                fontWeight = if (topPreset.id != "default") FontWeight.SemiBold else FontWeight.Medium,
                                color = if (topPreset.id != "default") DeepSeekBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

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
                            DeepWhaleLogo(sizeDp = 56, isGemini = settings.provider == "gemini", cornerDp = 16)
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
                        val safeStart = sectorStart.coerceIn(0, messages.size)
                        val safeEnd = sectorEnd.coerceIn(safeStart, messages.size)
                        val feedItems = remember(messages.toList(), safeStart, safeEnd, streaming) {
                            buildList<ChatFeedItem> {
                                for (i in safeStart until safeEnd) {
                                    val msg = messages[i]
                                    if (msg.role == "user") {
                                        add(ChatFeedItem.UserBubble(i, msg))
                                    } else {
                                        if (msg.reasoning.isNotBlank()) add(ChatFeedItem.AssistantReasoning(i, msg))
                                        val stripped = stripDocReportForMarkdown(msg.content)
                                        val docRep = extractDocReport(msg.content)
                                        if (msg.content.isBlank() && streaming && i == messages.size - 1) {
                                            add(ChatFeedItem.AssistantPending(i, msg))
                                        } else {
                                            if (stripped.isNotBlank()) {
                                                val blocks = parseMarkdownBlocksCached(stripped)
                                                blocks.forEachIndexed { bIdx, b ->
                                                    add(ChatFeedItem.AssistantBlock(i, msg, bIdx, b))
                                                }
                                            }
                                            if (docRep != null && !docRep.isComplete) {
                                                add(ChatFeedItem.AssistantBlock(i, msg, 9000, MdBlock.Paragraph("📄 Генерирую документ «${docRep.title}» (${msg.content.length} симв.)…")))
                                            }
                                        }
                                        if (msg.content.isNotBlank() && !(streaming && i == messages.size - 1)) {
                                            add(ChatFeedItem.AssistantFooter(i, msg))
                                        }
                                    }
                                }
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            reverseLayout = false,
                            contentPadding = PaddingValues(top = 10.dp, bottom = 52.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (safeStart > 0) {
                                item(key = "load_older_sector") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .clickable {
                                                val ns = (safeStart - SECTOR_STEP).coerceAtLeast(0)
                                                sectorStart = ns
                                                if (safeEnd - ns > 55) sectorEnd = ns + 55
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "▲ Показать предыдущие (скрыто $safeStart)",
                                            fontSize = 12.5.sp,
                                            color = DeepSeekBlue,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            items(count = feedItems.size, key = { feedItems[it].key }) { fIdx ->
                                when (val item = feedItems[fIdx]) {
                                    is ChatFeedItem.UserBubble -> {
                                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                                            Surface(
                                                shape = RoundedCornerShape(18.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                modifier = Modifier
                                                    .widthIn(max = 310.dp)
                                                    .clip(RoundedCornerShape(18.dp))
                                                    .combinedClickable(onClick = {}, onLongClick = { contextMenuMsg = item.msg })
                                            ) {
                                                Text(
                                                    text = item.msg.content,
                                                    fontSize = settings.fontSizeSp.sp,
                                                    lineHeight = (settings.fontSizeSp + 6).sp,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                                )
                                            }
                                        }
                                    }
                                    is ChatFeedItem.AssistantReasoning -> {
                                        val m = item.msg
                                        val thoughtBarColor = if (isDark) Color(0xFF3E4554) else Color(0xFFD0D5DD)
                                        val thoughtBg = if (isDark) Color(0xFF181A1F) else Color(0xFFF6F7F9)
                                        val expanded = m.id in reasoningExpanded
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(thoughtBg)
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = (if (expanded) "▼ " else "▶ ") + "Рассуждение (${m.reasoning.length} симв.)",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.fillMaxWidth().clickable {
                                                    reasoningExpanded = if (expanded) reasoningExpanded - m.id else reasoningExpanded + m.id
                                                }
                                            )
                                            if (expanded) {
                                                Spacer(Modifier.height(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .drawBehind {
                                                            drawLine(thoughtBarColor, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx())
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
                                    }
                                    is ChatFeedItem.AssistantPending -> {
                                        Text("Печатаю…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    is ChatFeedItem.AssistantBlock -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(onClick = {}, onLongClick = { contextMenuMsg = item.msg })
                                        ) {
                                            RenderMdBlock(
                                                block = item.block,
                                                textColor = MaterialTheme.colorScheme.onBackground,
                                                isDark = isDark,
                                                fontSizeSp = settings.fontSizeSp,
                                                onCopyCode = { _, code -> copyPlain(ctx, code, "Код скопирован") },
                                                onCopyTermux = { lang, code -> TermuxBridge.sendCodeToTermux(ctx, lang, code) },
                                                onCopyTable = { txt, toast -> copyPlain(ctx, txt, toast) },
                                                onOpenHtml = { raw ->
                                                    val page = if (raw.contains("<html", true) || raw.contains("<!doctype", true)) raw
                                                    else "<!DOCTYPE html><html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><body style=\"margin:14px;background:#fff;\">$raw</body></html>"
                                                    DocxEngine.openHtmlInBrowser(ctx, "work", page)
                                                }
                                            )
                                        }
                                    }
                                    is ChatFeedItem.AssistantFooter -> {
                                        val m = item.msg
                                        val idx = item.msgIdx
                                        val docRep = remember(m.content) { extractDocReport(m.content) }
                                        val htmlWork = remember(m.content, docRep) {
                                            if (docRep == null) DocxEngine.extractStandaloneHtmlPage(m.content) else null
                                        }
                                        var buildingDocx by remember(m.id) { mutableStateOf(false) }
                                        if (docRep != null && docRep.isComplete) {
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(1.dp, DeepSeekBlue.copy(alpha = 0.35f)),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                            ) {
                                                Column(Modifier.padding(12.dp)) {
                                                    Text("📄 ${docRep.title}.docx", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                                                    Text(
                                                        "ГОСТ А4 · Таблиц: ${docRep.tablesCount} · Рисунков: ${docRep.svgCount}",
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(Modifier.height(10.dp))
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        OutlinedButton(
                                                            onClick = { DocxEngine.openHtmlInBrowser(ctx, docRep.title, DocxEngine.buildPreviewHtml(docRep)) },
                                                            shape = RoundedCornerShape(10.dp),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                            modifier = Modifier.weight(1f)
                                                        ) { Text("🌐 Посмотреть", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                                        Button(
                                                            enabled = !buildingDocx,
                                                            onClick = {
                                                                buildingDocx = true
                                                                scope.launch {
                                                                    val bytes = withContext(Dispatchers.Default) { DocxEngine.buildDocxBytes(ctx, docRep) }
                                                                    val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                                                    val uri = DocxEngine.saveExportAndNotify(ctx, "${docRep.title}.docx", mime, bytes)
                                                                    buildingDocx = false
                                                                    if (uri != null) DocxEngine.openUri(ctx, uri, mime)
                                                                }
                                                            },
                                                            shape = RoundedCornerShape(10.dp),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                            modifier = Modifier.weight(1f)
                                                        ) { Text(if (buildingDocx) "⏳ Сборка…" else "📥 Скачать .docx", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                                    }
                                                }
                                            }
                                        } else if (htmlWork != null) {
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(1.dp, DeepSeekBlue.copy(alpha = 0.35f)),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                            ) {
                                                Column(Modifier.padding(12.dp)) {
                                                    Text("🌐 Готовая работа: ${htmlWork.first}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                                    Text("HTML-файл сохранён — откройте в браузере", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Spacer(Modifier.height(8.dp))
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Button(
                                                            onClick = { DocxEngine.openHtmlInBrowser(ctx, htmlWork.first, htmlWork.second) },
                                                            shape = RoundedCornerShape(10.dp),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                            modifier = Modifier.weight(1f)
                                                        ) { Text("🌐 Посмотреть работу", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                                        OutlinedButton(
                                                            onClick = {
                                                                scope.launch {
                                                                    DocxEngine.saveExportAndNotify(ctx, "${htmlWork.first}.html", "text/html", htmlWork.second.toByteArray(Charsets.UTF_8))
                                                                }
                                                            },
                                                            shape = RoundedCornerShape(10.dp),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                                        ) { Text("📥 HTML", fontSize = 12.sp) }
                                                    }
                                                }
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            modifier = Modifier.padding(bottom = 6.dp)
                                        ) {
                                            Text(
                                                "❐",
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.clickable { copyPlain(ctx, m.content, "Ответ скопирован") }.padding(vertical = 2.dp)
                                            )
                                            if (idx == messages.size - 1 && !streaming) {
                                                Text(
                                                    "↻",
                                                    fontSize = 16.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.clickable {
                                                        messages.removeAt(idx)
                                                        startStream()
                                                    }.padding(vertical = 2.dp)
                                                )
                                                Text(
                                                    if (m.truncated) "⏭ Продолжить (обрыв)" else "⏭ Продолжить",
                                                    fontSize = 12.sp,
                                                    fontWeight = if (m.truncated) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (m.truncated) DeepSeekBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.clickable { startStream(continueTargetIdx = idx) }.padding(vertical = 2.dp)
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

                            if (safeEnd < messages.size) {
                                item(key = "load_newer_sector") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .clickable {
                                                val ne = (safeEnd + SECTOR_STEP).coerceAtMost(messages.size)
                                                sectorEnd = ne
                                                if (ne - safeStart > 55) sectorStart = ne - 55
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "▼ Показать новее (ещё ${messages.size - safeEnd})",
                                            fontSize = 12.5.sp,
                                            color = DeepSeekBlue,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            item(key = "bottom_anchor") { Spacer(Modifier.height(4.dp)) }
                        }

                        val userAnchors = remember(messages.toList()) {
                            messages.mapIndexedNotNull { idx, msg ->
                                if (msg.role == "user") idx to msg else null
                            }
                        }
                        if (userAnchors.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                shadowElevation = 3.dp,
                                color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isDark) Color(0xFF2D3139) else Color(0xFFE5E7EB)
                                ),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = 8.dp, top = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { showAnchorsDialog = true }
                            ) {
                                Text(
                                    text = "📑 ${userAnchors.size}",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
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
                                    scope.launch {
                                        val jumped = sectorEnd < messages.size
                                        sectorEnd = messages.size
                                        sectorStart = (messages.size - SECTOR_SIZE).coerceAtLeast(0)
                                        if (jumped) delay(60)
                                        val lastIdx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                        listState.animateScrollToItem(lastIdx)
                                    }
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

    if (showAnchorsDialog) {
        val anchors = messages.mapIndexedNotNull { idx, msg ->
            if (msg.role == "user") idx to msg else null
        }
        val dividerCol = if (isDark) Color(0xFF2B2F38) else Color(0xFFF0F1F4)
        Dialog(onDismissRequest = { showAnchorsDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Якоря сообщений (${anchors.size})", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        anchors.forEachIndexed { num, (msgIdx, msg) ->
                            val preview = msg.content.trim().replace("\n", " ").take(90)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showAnchorsDialog = false
                                        focusSectorAround(msgIdx)
                                        scope.launch {
                                            kotlinx.coroutines.yield()
                                            var offset = if (sectorStart > 0) 1 else 0
                                            for (i in sectorStart until msgIdx.coerceAtMost(sectorEnd)) {
                                                val pm = messages[i]
                                                offset += if (pm.role == "user") 1 else {
                                                    (if (pm.reasoning.isNotBlank()) 1 else 0) +
                                                        parseMarkdownBlocksCached(pm.content).size.coerceAtLeast(1) + 1
                                                }
                                            }
                                            listState.animateScrollToItem(offset)
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    "#${num + 1}",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepSeekBlue,
                                    modifier = Modifier.width(34.dp)
                                )
                                Text(
                                    preview,
                                    fontSize = 13.5.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (num < anchors.lastIndex) HorizontalDivider(color = dividerCol)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showAnchorsDialog = false }, modifier = Modifier.align(Alignment.End)) {
                        Text("Закрыть")
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
                    HorizontalDivider(color = dividerCol)
                    ContextMenuItem("📌", "В правила заготовки") {
                        val txt = m.content.trim().take(600)
                        contextMenuMsg = null
                        addSkillDraft = txt
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

    // Selectable Text Dialog ("Выбрать текст") with native TextView auto-scroll
    selectTextMsg?.let { m ->
        val textColArgb = MaterialTheme.colorScheme.onBackground.toArgb()
        Dialog(
            onDismissRequest = { selectTextMsg = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.84f)
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
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { c ->
                            ScrollView(c).apply {
                                isFillViewport = true
                                val tv = TextView(c).apply {
                                    setTextIsSelectable(true)
                                    setLineSpacing(0f, 1.35f)
                                    setPadding(6, 6, 6, 32)
                                }
                                addView(
                                    tv,
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                )
                            }
                        },
                        update = { sv ->
                            val tv = sv.getChildAt(0) as TextView
                            if (tv.text.toString() != m.content) tv.text = m.content
                            tv.setTextColor(textColArgb)
                            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp.toFloat())
                        }
                    )
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

    // Presets picker dialog
    if (showPresetsDialog) {
        val dividerCol = if (isDark) Color(0xFF2B2F38) else Color(0xFFF0F1F4)
        Dialog(
            onDismissRequest = { showPresetsDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 620.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Заготовки промпта",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            showPresetsDialog = false
                            editingPreset = PromptPreset(
                                id = "custom_${System.currentTimeMillis()}",
                                name = "Моя заготовка",
                                emoji = "⭐",
                                prompt = "",
                                isBuiltIn = false
                            )
                        }) { Text("+ Своя", color = DeepSeekBlue, fontWeight = FontWeight.SemiBold) }
                        Text(
                            "✕",
                            fontSize = 18.sp,
                            modifier = Modifier.clickable { showPresetsDialog = false }.padding(4.dp)
                        )
                    }
                    Text(
                        "Действует для этого чата · Дообучается по ходу работы",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        presets.forEachIndexed { idx, p ->
                            val selected = p.id == activePresetId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) DeepSeekBlue.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable {
                                        setChatPreset(p.id)
                                        showPresetsDialog = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(p.emoji, fontSize = 20.sp, modifier = Modifier.width(30.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            p.name,
                                            fontSize = 15.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (selected) DeepSeekBlue else MaterialTheme.colorScheme.onBackground
                                        )
                                        if (p.learnedSkill.isNotBlank()) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("🧠", fontSize = 12.sp)
                                        }
                                    }
                                    val preview = p.prompt.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(85) ?: "Чистые нейтральные ответы"
                                    Text(
                                        preview,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (selected) {
                                    Text("✓", color = DeepSeekBlue, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                                }
                                Text(
                                    "✏️",
                                    fontSize = 15.sp,
                                    modifier = Modifier
                                        .clickable {
                                            showPresetsDialog = false
                                            editingPreset = p
                                        }
                                        .padding(6.dp)
                                )
                            }
                            if (idx < presets.lastIndex) HorizontalDivider(color = dividerCol)
                        }
                    }
                }
            }
        }
    }

    // Create / Edit Preset Dialog
    editingPreset?.let { ep ->
        var eEmoji by remember(ep.id) { mutableStateOf(ep.emoji) }
        var eName by remember(ep.id) { mutableStateOf(ep.name) }
        var ePrompt by remember(ep.id) { mutableStateOf(ep.prompt) }
        var eSkill by remember(ep.id) { mutableStateOf(ep.learnedSkill) }
        val isExisting = presets.any { it.id == ep.id }
        val skillFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty().trim()
                    if (text.isNotBlank()) {
                        ePrompt = text
                        Toast.makeText(ctx, "Загружено ${text.length} симв.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "Файл пустой", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Ошибка чтения: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        Dialog(
            onDismissRequest = { editingPreset = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp)
            ) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        if (isExisting) "Настроить заготовку" else "Новая заготовка",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = eEmoji,
                            onValueChange = { eEmoji = it.take(4) },
                            label = { Text("Значок") },
                            singleLine = true,
                            modifier = Modifier.width(86.dp)
                        )
                        OutlinedTextField(
                            value = eName,
                            onValueChange = { eName = it },
                            label = { Text("Название") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { ePrompt = PresetStore.loadCustomTemplate(ctx) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("📋 Шаблон скилла", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { skillFilePicker.launch(arrayOf("text/*", "*/*")) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("📂 Из файла", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ePrompt,
                        onValueChange = { ePrompt = it },
                        label = { Text("Базовый промпт (${ePrompt.length} симв. · ~${ePrompt.length / 1024} КБ)") },
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = eSkill,
                        onValueChange = { eSkill = it },
                        label = { Text("Накопленный скилл (правила)") },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ep.isBuiltIn) {
                            TextButton(onClick = {
                                val def = PresetStore.defaults(ctx).firstOrNull { it.id == ep.id }
                                if (def != null) {
                                    eEmoji = def.emoji
                                    eName = def.name
                                    ePrompt = def.prompt
                                    eSkill = ""
                                }
                            }) { Text("Сбросить") }
                        } else if (isExisting) {
                            TextButton(onClick = {
                                val list = presets.filterNot { it.id == ep.id }
                                PresetStore.save(ctx, list)
                                presets = PresetStore.load(ctx)
                                if (activePresetId == ep.id) setChatPreset("default")
                                editingPreset = null
                            }) { Text("Удалить", color = Color(0xFFE53935)) }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { editingPreset = null }) { Text("Отмена") }
                        Button(
                            onClick = {
                                val list = presets.toMutableList()
                                val updated = ep.copy(
                                    emoji = eEmoji.ifBlank { "⭐" },
                                    name = eName.ifBlank { "Заготовка" },
                                    prompt = ePrompt,
                                    learnedSkill = eSkill
                                )
                                val i = list.indexOfFirst { it.id == ep.id }
                                if (i >= 0) list[i] = updated else list.add(updated)
                                PresetStore.save(ctx, list)
                                presets = PresetStore.load(ctx)
                                setChatPreset(updated.id)
                                editingPreset = null
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("Сохранить") }
                    }
                }
            }
        }
    }

    // Incoming Share Picker Dialog
    incomingShare?.let { share ->
        val hasDocx = share.files.any { it.name.endsWith(".docx", ignoreCase = true) }
        var sharePresetId by remember(share.id) { mutableStateOf(if (hasDocx) "docx_report" else "default") }
        val recentFive = remember(sessions.toList()) {
            sessions.sortedByDescending { it.updatedAt }.take(5)
        }
        val dividerCol = if (isDark) Color(0xFF2B2F38) else Color(0xFFF0F1F4)

        fun applyShareToCurrent() {
            if (share.text.isNotBlank()) {
                input = if (input.isBlank()) share.text else "${input.trimEnd()}\n\n${share.text}"
            }
            share.files.forEach { attachments.add(it) }
            onConsumeShare()
        }

        Dialog(
            onDismissRequest = onConsumeShare,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp)
            ) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📥 Принято из мессенджера", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("✕", fontSize = 18.sp, modifier = Modifier.clickable { onConsumeShare() }.padding(4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    if (share.files.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            share.files.forEach { f ->
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
                                    Text("📎 ${f.name} (${f.sizeLabel})", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (share.text.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                share.text.take(220),
                                fontSize = 12.5.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    Text("➕ Новый чат с ролью:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        presets.forEach { p ->
                            ModePill(
                                label = "${p.emoji} ${p.name}",
                                active = sharePresetId == p.id,
                                isDark = isDark,
                                onClick = { sharePresetId = p.id }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val newSess = ChatSession(
                                id = System.currentTimeMillis().toString(),
                                title = "Новый чат",
                                updatedAt = System.currentTimeMillis(),
                                messages = emptyList(),
                                presetId = sharePresetId
                            )
                            sessions.add(0, newSess)
                            switchSession(newSess)
                            setChatPreset(sharePresetId)
                            applyShareToCurrent()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        val pObj = presets.firstOrNull { it.id == sharePresetId }
                        Text("Открыть в новом чате (${pObj?.emoji ?: ""} ${pObj?.name ?: ""})")
                    }

                    if (recentFive.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Или добавить в последние чаты:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        recentFive.forEachIndexed { idx, s ->
                            val rp = presets.firstOrNull { it.id == s.presetId }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        switchSession(s)
                                        applyShareToCurrent()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(rp?.emoji ?: "💬", fontSize = 16.sp, modifier = Modifier.width(26.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(rp?.name ?: "Обычный", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("→", color = DeepSeekBlue, fontWeight = FontWeight.Bold)
                            }
                            if (idx < recentFive.lastIndex) HorizontalDivider(color = dividerCol)
                        }
                    }
                }
            }
        }
    }

    // Add selected/copied message rule to preset
    addSkillDraft?.let { initial ->
        var ruleText by remember(initial) { mutableStateOf(initial) }
        var targetId by remember(initial) {
            mutableStateOf(if (activePresetId == "default") "termux" else activePresetId)
        }
        Dialog(onDismissRequest = { addSkillDraft = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Добавить правило в заготовку", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        presets.filter { it.id != "default" }.forEach { p ->
                            ModePill(
                                label = "${p.emoji} ${p.name}",
                                active = targetId == p.id,
                                isDark = isDark,
                                onClick = { targetId = p.id }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ruleText,
                        onValueChange = { ruleText = it },
                        label = { Text("Текст правила") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { addSkillDraft = null }) { Text("Отмена") }
                        Button(onClick = {
                            PresetStore.appendSkill(ctx, targetId, ruleText)
                            presets = PresetStore.load(ctx)
                            Toast.makeText(ctx, "Добавлено в заготовку", Toast.LENGTH_SHORT).show()
                            addSkillDraft = null
                        }) { Text("Сохранить") }
                    }
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

@Composable
private fun DeepWhaleLogo(sizeDp: Int, isGemini: Boolean, cornerDp: Int = sizeDp / 2) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(cornerDp.dp))
            .background(DeepSeekBlue),
        contentAlignment = Alignment.Center
    ) {
        if (isGemini) {
            Text("✦", color = Color.White, fontSize = (sizeDp * 0.42f).sp, fontWeight = FontWeight.Bold)
        } else {
            Canvas(Modifier.size((sizeDp * 0.72f).dp)) {
                val w = size.width
                val h = size.height
                val whale = Path().apply {
                    moveTo(w * 0.08f, h * 0.54f)
                    cubicTo(w * 0.08f, h * 0.28f, w * 0.28f, h * 0.16f, w * 0.50f, h * 0.19f)
                    cubicTo(w * 0.66f, h * 0.21f, w * 0.75f, h * 0.34f, w * 0.79f, h * 0.46f)
                    lineTo(w * 0.96f, h * 0.30f)
                    lineTo(w * 0.91f, h * 0.52f)
                    lineTo(w * 0.96f, h * 0.72f)
                    lineTo(w * 0.77f, h * 0.62f)
                    cubicTo(w * 0.70f, h * 0.78f, w * 0.56f, h * 0.84f, w * 0.38f, h * 0.84f)
                    cubicTo(w * 0.18f, h * 0.84f, w * 0.08f, h * 0.72f, w * 0.08f, h * 0.54f)
                    close()
                }
                drawPath(whale, Color.White)
                val prompt = Path().apply {
                    moveTo(w * 0.31f, h * 0.40f)
                    lineTo(w * 0.44f, h * 0.52f)
                    lineTo(w * 0.31f, h * 0.64f)
                }
                drawPath(
                    prompt,
                    DeepSeekBlue,
                    style = Stroke(width = w * 0.075f, cap = StrokeCap.Round)
                )
                drawLine(
                    color = DeepSeekBlue,
                    start = Offset(w * 0.49f, h * 0.64f),
                    end = Offset(w * 0.63f, h * 0.64f),
                    strokeWidth = w * 0.075f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun copyPlain(ctx: Context, text: String, toastMsg: String = "Скопировано") {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("deepapk", text))
    Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
}
