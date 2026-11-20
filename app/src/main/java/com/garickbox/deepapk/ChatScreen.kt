package com.garickbox.deepapk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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

private data class TermuxRunnerState(
    val script: String,
    val interpreter: String,
    val running: Boolean = false,
    val elapsedSec: Int = 0,
    val statusLine: String = "",
    val liveTail: String = "",
    val exitCode: Int? = null,
    val durationSec: Int = 0,
    val resultBody: String = "",
    val jobHandle: TermuxJobHandle? = null
)

private const val SECTOR_SIZE = 28
private const val SECTOR_STEP = 20

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    isDark: Boolean,
    incomingShare: IncomingShare? = null,
    onConsumeShare: () -> Unit = {},
    incomingSkill: SkillPackage? = null,
    onConsumeSkill: () -> Unit = {},
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
    var safStatus by remember { mutableStateOf("") }
    var safRetryTargetIdx by remember { mutableStateOf(-1) }
    var safRetryOps by remember { mutableStateOf<List<FsReadOp>>(emptyList()) }
    var cancelRequested by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf<Set<Long>>(emptySet()) }

    var contextMenuMsg by remember { mutableStateOf<Message?>(null) }
    var selectTextMsg by remember { mutableStateOf<Message?>(null) }
    var codePickerBlocks by remember { mutableStateOf<List<MdBlock.Code>?>(null) }
    var termuxRunner by remember { mutableStateOf<TermuxRunnerState?>(null) }
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
    var projectLabel by remember { mutableStateOf(ProjectFs.getLabel(ctx)) }
    var projectAutoRead by remember { mutableStateOf(ProjectFs.isEnabled(ctx)) }
    var showProjectFsDialog by remember { mutableStateOf(false) }
    var showOcrBaseDialog by remember { mutableStateOf(false) }
    var ocrBusy by remember { mutableStateOf(false) }
    var ocrCur by remember { mutableStateOf(0) }
    var ocrTotal by remember { mutableStateOf(0) }
    var ocrJob by remember { mutableStateOf<Job?>(null) }
    var ocrRefreshKey by remember { mutableStateOf(0) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                ctx.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            projectLabel = ProjectFs.saveTree(ctx, uri)
            projectAutoRead = true
            Toast.makeText(ctx, "Папка подключена: $projectLabel", Toast.LENGTH_SHORT).show()
        }
    }
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
            val mime = ctx.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/") || mime.startsWith("application/pdf")) {
                scope.launch {
                    val att = ocrImage(ctx, uri)
                    if (att != null) attachments.add(att)
                    else Toast.makeText(ctx, "Не удалось распознать фото", Toast.LENGTH_SHORT).show()
                }
            } else {
                val att = readAttachment(ctx, uri)
                if (att != null) {
                    attachments.add(att)
                } else {
                    Toast.makeText(ctx, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }

    val photoOcrPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val att = ocrImage(ctx, uri)
                if (att != null) attachments.add(att)
                else Toast.makeText(ctx, "Не удалось распознать фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) {
            scope.launch {
                val att = ocrImage(ctx, uri)
                if (att != null) attachments.add(att)
                else Toast.makeText(ctx, "Не удалось распознать фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchPhotoOcr() {
        try {
            photoOcrPicker.launch(arrayOf("image/*", "application/pdf"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Галерея недоступна: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    fun launchCameraOcr() {
        try {
            val f = java.io.File(ctx.cacheDir, "ocr_" + System.currentTimeMillis() + ".jpg")
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            cameraUri = uri
            cameraPicker.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Камера недоступна: " + e.message, Toast.LENGTH_SHORT).show()
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

    fun feedPosForMsg(msgIdx: Int): Int? {
        val s0 = sectorStart.coerceIn(0, messages.size); val s1 = sectorEnd.coerceIn(s0, messages.size)
        if (msgIdx !in s0 until s1) return null
        var off = if (s0 > 0) 1 else 0
        for (k in s0 until msgIdx) {
            val pm = messages[k]
            if (pm.role == "user") off += 1 else {
                if (pm.reasoning.isNotBlank()) off += 1
                val st = stripDocReportForMarkdown(pm.content); val dr = extractDocReport(pm.content)
                if (pm.content.isBlank() && streaming && k == messages.size - 1) off += 1 else {
                    if (st.isNotBlank()) off += parseMarkdownBlocksCached(st).size
                    if (dr != null && !dr.isComplete) off += 1
                }
                if (pm.content.isNotBlank() && !(streaming && k == messages.size - 1)) off += 1
            }
        }
        return off
    }

    fun startStream(continueTargetIdx: Int? = null, scrollAnchorIdx: Int? = null, toolDepth: Int = 0) {
        if (streaming) return
        error = ""; searchInfo = ""; cancelRequested = false
        if (toolDepth == 0) safStatus = ""
        val targetIdx = if (continueTargetIdx != null) {
            continueTargetIdx
        } else {
            messages.add(Message(role = "assistant", content = ""))
            messages.size - 1
        }
        val rawHistory = if (continueTargetIdx != null) {
            messages.subList(0, continueTargetIdx + 1).toList()
        } else {
            messages.dropLast(1)
        }
        val historySlice = capHistory(rawHistory)
        if (continueTargetIdx != null) {
            messages[targetIdx] = messages[targetIdx].copy(truncated = false)
        }
        resetSectorToTail()
        val anchorMsg = scrollAnchorIdx ?: (targetIdx - 1).coerceAtLeast(0)
        scope.launch {
            kotlinx.coroutines.delay(50)
            feedPosForMsg(anchorMsg)?.let { pos ->
                val lastIdx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                listState.animateScrollToItem(if (lastIdx > 0) pos.coerceAtMost(lastIdx) else pos)
            }
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
            val fsAdd = if (projectAutoRead && ProjectFs.isReady(ctx)) ProjectFs.systemPromptAddendum(ctx) else ""
            val combinedExtra = listOf(extraGlobal, fsAdd).filter { it.isNotBlank() }.joinToString("\n\n")
            val effectiveSettings = settings.copy(systemPrompt = activePreset.buildSystemPrompt(combinedExtra))
            var wasCancelled = false
            try {
                DeepSeekApi.chatStream(
                    settings = effectiveSettings,
                    history = historySlice,
                    continuation = continueTargetIdx != null
                ).collect { ev ->
                    when (ev) {
                        is StreamEvent.Reasoning -> {
                            if (safStatus.isNotEmpty()) safStatus = ""
                            pendingReasoning.append(ev.delta)
                            flushDeltas(false)
                        }
                        is StreamEvent.Content -> {
                            if (safStatus.isNotEmpty()) safStatus = ""
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
                wasCancelled = true
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
                var nextAutoReadPayload: String? = null
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
                    val writeOps = ProjectFs.extractWriteOps(last.content)
                    if (ProjectFs.isReady(ctx) && writeOps.isNotEmpty()) {
                        val wRes = try {
                            withContext(Dispatchers.IO) { ProjectFs.applyDirect(ctx, writeOps) }
                        } catch (e: Throwable) {
                            FsApplyResult(false, "Ошибка применения: " + (e.message ?: e.javaClass.simpleName))
                        }
                        if (wRes.ok) {
                            Toast.makeText(ctx, wRes.message, Toast.LENGTH_SHORT).show()
                        } else {
                            error = wRes.message
                        }
                    }
                    if (!wasCancelled && !cancelRequested && continueTargetIdx == null && projectAutoRead && ProjectFs.isReady(ctx) && toolDepth < ChatSafController.SAF_MAX_TOOL_DEPTH) {
                        val readOps = ProjectFs.extractReadOps(last.content)
                        if (readOps.isNotEmpty()) {
                            safStatus = ChatSafController.buildStatusText(readOps.first())
                            val res = ChatSafController.runSafRead(
                                ctx = ctx,
                                ops = readOps,
                                originalContent = last.content,
                                timeoutMsg = "> ⏱ SAF-чтение не ответило за 180 с (файл большой или папка недоступна). Нажми «Отправить» ещё раз, чтобы повторить."
                            )
                            if (!cancelRequested) {
                                when (res) {
                                    is SafReadResult.Success -> {
                                        safRetryTargetIdx = -1
                                        safRetryOps = emptyList()
                                        messages[targetIdx] = last.copy(content = res.strippedContent)
                                        nextAutoReadPayload = res.payload
                                    }
                                    is SafReadResult.Error -> {
                                        messages.add(Message(role = "assistant", content = res.message))
                                        safRetryTargetIdx = targetIdx
                                        safRetryOps = readOps
                                    }
                                    is SafReadResult.Timeout -> {
                                        messages.add(Message(role = "assistant", content = res.message))
                                        safRetryTargetIdx = targetIdx
                                        safRetryOps = readOps
                                    }
                                }
                            }
                        }
                    }
                }
                persistCurrentSession()
                streaming = false
                streamJob = null
                if (nextAutoReadPayload != null && !cancelRequested) {
                    safStatus = "Отправляю файлы ИИ…"
                    messages.add(Message(role = "user", content = nextAutoReadPayload))
                    persistCurrentSession()
                    startStream(scrollAnchorIdx = messages.size - 1, toolDepth = toolDepth + 1)
                } else { safStatus = "" }
            }
        }
    }

    fun retrySafRead() {
        val rOps = safRetryOps
        val rIdx = safRetryTargetIdx
        if (rOps.isEmpty() || rIdx < 0 || streaming) return
        safRetryTargetIdx = -1
        safRetryOps = emptyList()
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.role == "assistant") {
            val c = lastMsg.content
            if (ChatSafController.isSafErrorMessage(c)) {
                messages.removeAt(messages.size - 1)
            }
        }
        safStatus = "Повторяю SAF-чтение…"
        scope.launch {
            val modelMsg = messages.getOrNull(rIdx)
            val res = ChatSafController.runSafRead(
                ctx = ctx,
                ops = rOps,
                originalContent = modelMsg?.content ?: "",
                timeoutMsg = "> ⏱ Повторное SAF-чтение не ответило. Проверь папку и попробуй снова."
            )
            if (!cancelRequested) {
                when (res) {
                    is SafReadResult.Success -> {
                        if (modelMsg != null) {
                            messages[rIdx] = modelMsg.copy(content = res.strippedContent)
                        }
                        safStatus = "Отправляю файлы ИИ…"
                        messages.add(Message(role = "user", content = res.payload))
                        persistCurrentSession()
                        startStream(scrollAnchorIdx = messages.size - 1, toolDepth = 1)
                    }
                    is SafReadResult.Error -> {
                        safStatus = ""
                        messages.add(Message(role = "assistant", content = res.message))
                        safRetryTargetIdx = rIdx
                        safRetryOps = rOps
                    }
                    is SafReadResult.Timeout -> {
                        safStatus = ""
                        messages.add(Message(role = "assistant", content = res.message))
                        safRetryTargetIdx = rIdx
                        safRetryOps = rOps
                    }
                }
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
                val ext = if (f.name.startsWith("\uD83D\uDCBB ")) "text" else f.name.substringAfterLast('.', "").lowercase()
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
        persistCurrentSession()
        startStream(scrollAnchorIdx = messages.size - 1)
    }

    fun startTermuxRunner(overrideState: TermuxRunnerState? = null) {
        val cur = overrideState ?: termuxRunner ?: return
        if (cur.running) return
        val code = cur.script.trim()
        if (code.isBlank()) {
            Toast.makeText(ctx, "Пустой скрипт", Toast.LENGTH_SHORT).show()
            return
        }
        val handle = TermuxJobHandle()
        termuxRunner = cur.copy(
            running = true,
            elapsedSec = 0,
            statusLine = "Запуск ${cur.interpreter}…",
            liveTail = "",
            exitCode = null,
            resultBody = "",
            jobHandle = handle
        )
        scope.launch {
            TermuxBridge.runScriptAndWatch(
                ctx = ctx,
                scriptText = code,
                interpreter = cur.interpreter,
                jobHandle = handle,
                onLogTail = { tailText ->
                    val r = termuxRunner
                    if (r != null && r.jobHandle == handle) {
                        termuxRunner = r.copy(liveTail = tailText)
                    }
                },
                onTick = { elapsed, tail ->
                    val r = termuxRunner
                    if (r != null && r.jobHandle == handle) {
                        termuxRunner = r.copy(
                            elapsedSec = elapsed,
                            statusLine = "⏳ ${elapsed}с · ${tail.take(80)}"
                        )
                    }
                },
                onDone = { exitCode, body, dur ->
                    val r = termuxRunner
                    if (r != null && r.jobHandle == handle) {
                        val txt = body.trim().ifBlank { "(пусто)" }
                        termuxRunner = r.copy(
                            running = false,
                            elapsedSec = dur,
                            exitCode = exitCode,
                            durationSec = dur,
                            resultBody = txt,
                            liveTail = txt,
                            statusLine = if (exitCode == 0) "✅ Готово · ${dur}с" else "⚠️ exit=$exitCode · ${dur}с",
                            jobHandle = null
                        )
                    }
                }
            )
        }
    }

    fun openTermuxRunner(lang: String, code: String, autoStart: Boolean = true) {
        val clean = code.trim()
        if (clean.isBlank()) {
            Toast.makeText(ctx, "Пустой скрипт", Toast.LENGTH_SHORT).show()
            return
        }
        val interp = TermuxBridge.detectInterpreter(lang, clean)
        val initial = TermuxRunnerState(script = clean, interpreter = interp)
        termuxRunner = initial
        if (autoStart && TermuxBridge.hasRunPermission(ctx)) {
            startTermuxRunner(initial)
        }
    }

    fun handleSendMessageToTermux(content: String) {
        val runnable = TermuxBridge.extractRunnableCodeBlocks(content)
        val blocks = runnable.ifEmpty { TermuxBridge.extractAllCodeBlocks(content) }
        when {
            blocks.isEmpty() -> Toast.makeText(ctx, "Нет блоков кода — используй Выбрать текст", Toast.LENGTH_SHORT).show()
            blocks.size == 1 -> openTermuxRunner(blocks[0].lang, blocks[0].code, autoStart = true)
            else -> codePickerBlocks = blocks
        }
    }

    if (settings.activeKey().isBlank()) {
        val pm = ModelCatalog.findProvider(settings.provider)
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(DeepSeekBlue), contentAlignment = Alignment.Center) {
                Text(settings.providerShort().take(2).trim(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Text(pm.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Ключ: ${pm.keyUrl.removePrefix("https://")}", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp), placeholder = { Text(pm.keyHint) })
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (input.isNotBlank()) { val s = settings.withActiveKey(input); SettingsStore.save(ctx, s); settings = s; input = "" } },
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)
            ) { Text("Начать работу") }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onOpenSettings) { Text("Выбрать ИИ / Настройки") }
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
            ChatDrawerSheet(
                drawerSearch = drawerSearch,
                onDrawerSearchChange = { drawerSearch = it },
                filteredSessions = filteredSessions,
                activeSessionId = activeSessionId,
                onSwitchSession = { switchSession(it); scope.launch { drawerState.close() } },
                onDeleteSession = { deleteSession(it) },
                onCloseDrawer = { scope.launch { drawerState.close() } },
                onOpenSettings = onOpenSettings,
                onOpenOcrBase = { showOcrBaseDialog = true },
                settings = settings,
                balanceText = balanceText
            )
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
                            modifier = Modifier.padding(top = 2.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
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
                            val safConnected = projectLabel.isNotBlank() && ProjectFs.isReady(ctx)
                            val safOn = safConnected && projectAutoRead
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (safOn) DeepSeekBlue.copy(alpha = if (isDark) 0.22f else 0.11f)
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable {
                                        if (!streaming) {
                                            if (!safConnected) treePicker.launch(null) else showProjectFsDialog = true
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (safConnected) "📂 ${projectLabel.take(14)}" + (if (safOn) " · ВКЛ" else " · ВЫКЛ") else "📂 Папка",
                                    fontSize = 11.5.sp,
                                    fontWeight = if (safOn) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (safOn) DeepSeekBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    var showProvMenu by remember { mutableStateOf(false) }
                    Box(Modifier.padding(end = 8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { if (!streaming) showProvMenu = true }
                        ) {
                            Text(settings.providerShort(), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = DeepSeekBlue, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                        }
                        DropdownMenu(expanded = showProvMenu, onDismissRequest = { showProvMenu = false }) {
                            AppSettings.ALL_PROVIDERS.forEach { pid ->
                                val mark = if (settings.hasKey(pid)) "✓" else "нет ключа"
                                DropdownMenuItem(
                                    text = { Text("${settings.providerLabel(pid)} ($mark)", fontSize = 13.sp) },
                                    onClick = { showProvMenu = false; val u = settings.copy(provider = pid); settings = u; SettingsStore.save(ctx, u) }
                                )
                            }
                            DropdownMenuItem(text = { Text("⚙ Настройки и ключи", fontSize = 13.sp, color = DeepSeekBlue) }, onClick = { showProvMenu = false; onOpenSettings() })
                        }
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


                if (safStatus.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = DeepSeekBlue)
                            Spacer(Modifier.width(8.dp))
                            Text("📂 $safStatus", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                if (error.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "❌ $error",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
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
                            DeepWhaleLogo(sizeDp = 56, cornerDp = 16)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "Привет! Я ${settings.providerLabel()}",
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
                                        val stripped = stripTermuxTags(stripDocReportForMarkdown(msg.content))
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
                                        if ((msg.content.isNotBlank() || msg.reasoning.isNotBlank()) && !(streaming && i == messages.size - 1)) {
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
                            contentPadding = PaddingValues(top = 10.dp, bottom = 68.dp),
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
                                        val (attFiles, cleanUserText) = remember(item.msg.id, item.msg.content) {
                                            splitUserAttachments(item.msg.content)
                                        }
                                        var userExpanded by remember(item.msg.id) { mutableStateOf(false) }
                                        val isLongUser = cleanUserText.length > 700 || cleanUserText.count { it == '\n' } > 12
                                        val shownUserText = remember(cleanUserText, isLongUser, userExpanded) {
                                            if (!isLongUser || userExpanded) cleanUserText
                                            else cleanUserText.lineSequence().take(10).joinToString("\n").take(600) + "…"
                                        }
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                            horizontalAlignment = Alignment.End
                                        ) {
                                            if (attFiles.isNotEmpty()) {
                                                Column(
                                                    horizontalAlignment = Alignment.End,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                    modifier = Modifier.padding(bottom = if (shownUserText.isNotBlank()) 6.dp else 0.dp)
                                                ) {
                                                    attFiles.forEach { (fName, fBody) ->
                                                        val kb = (fBody.length / 1024).let { if (it > 0) "${it} КБ" else "${fBody.length} симв." }
                                                        Surface(
                                                            shape = RoundedCornerShape(12.dp),
                                                            color = MaterialTheme.colorScheme.surface,
                                                            border = BorderStroke(1.dp, DeepSeekBlue.copy(alpha = 0.35f)),
                                                            modifier = Modifier
                                                                .widthIn(max = 290.dp)
                                                                .clip(RoundedCornerShape(12.dp))
                                                                .combinedClickable(
                                                                    onClick = { selectTextMsg = Message(role = "user", content = "$fName\n\n$fBody") },
                                                                    onLongClick = { contextMenuMsg = item.msg }
                                                                )
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text("📎", fontSize = 13.sp)
                                                                Spacer(Modifier.width(6.dp))
                                                                Text(
                                                                    text = fName,
                                                                    fontSize = 12.5.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f, fill = false)
                                                                )
                                                                Spacer(Modifier.width(6.dp))
                                                                Text(
                                                                    text = kb,
                                                                    fontSize = 11.5.sp,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            if (shownUserText.isNotBlank()) {
                                                Surface(
                                                    shape = RoundedCornerShape(18.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    modifier = Modifier
                                                        .widthIn(max = 310.dp)
                                                        .clip(RoundedCornerShape(18.dp))
                                                        .combinedClickable(onClick = {}, onLongClick = { contextMenuMsg = item.msg })
                                                ) {
                                                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                                        Text(
                                                            text = shownUserText,
                                                            fontSize = settings.fontSizeSp.sp,
                                                            lineHeight = (settings.fontSizeSp + 6).sp,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                        if (isLongUser) {
                                                            Spacer(Modifier.height(4.dp))
                                                            Text(
                                                                text = if (userExpanded) "▲ Свернуть" else "▼ Показать весь текст (${cleanUserText.length} симв.)",
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = DeepSeekBlue,
                                                                modifier = Modifier.clickable { userExpanded = !userExpanded }
                                                            )
                                                        }
                                                    }
                                                }
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
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = DeepSeekBlue)
    Spacer(Modifier.width(8.dp))
    Text(if (safStatus.isNotBlank()) "📂 $safStatus" else if (item.msg.reasoning.isNotBlank()) "Думаю…" else "Отвечаю…", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
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
                                                onCopyTermux = { lang, code ->
                                                    openTermuxRunner(lang, code, autoStart = true)
                                                },
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
                                            if (safRetryOps.isNotEmpty() && safRetryTargetIdx >= 0 && idx == messages.size - 1) {
                                                Text(
                                                    "🔁 Повторить SAF",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = DeepSeekBlue,
                                                    modifier = Modifier.clickable { retrySafRead() }.padding(vertical = 2.dp)
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
                                    border = BorderStroke(1.dp, if (f.name.startsWith("\uD83D\uDCBB ")) DeepSeekBlue.copy(alpha = 0.45f) else cardBorder),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { selectTextMsg = Message(role = "user", content = "${f.name}\n\n${f.content}") }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val chipLabel = if (f.name.startsWith("\uD83D\uDCBB ")) "${f.name} (${f.sizeLabel})" else "📄 ${f.name} (${f.sizeLabel})"
                                        Text(
                                            chipLabel,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 220.dp)
                                        )
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

                    ChipTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp, max = 130.dp),
                        placeholder = "Напишите сообщение…",
                        textColor = MaterialTheme.colorScheme.onBackground,
                        fontSizeSp = 15f,
                        lineHeightSp = 21f,
                        maxLines = 6,
                        cursorColor = DeepSeekBlue
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
                        if (settings.provider in listOf("deepseek", "gemini")) {
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
                        }
                        Spacer(Modifier.weight(1f))

                        Box {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), CircleShape)
                                    .clickable { showAttachMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "+",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            DropdownMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDCF7  Фото / PDF (OCR)") },
                                    onClick = { showAttachMenu = false; launchPhotoOcr() }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDCF8  Камера (OCR)") },
                                    onClick = { showAttachMenu = false; launchCameraOcr() }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDCCE  Прикрепить файл") },
                                    onClick = { showAttachMenu = false; filePicker.launch(arrayOf("*/*")) }
                                )
                            }
                        }

                        Spacer(Modifier.width(10.dp))

                        if (streaming) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(DeepSeekBlue)
                                    .clickable { cancelRequested = true; safStatus = ""; streamJob?.cancel() },
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
        AnchorsDialog(
            anchors = anchors,
            isDark = isDark,
            onDismiss = { showAnchorsDialog = false },
            onSelect = { msgIdx ->
                showAnchorsDialog = false
                focusSectorAround(msgIdx)
                scope.launch {
                    kotlinx.coroutines.yield()
                    kotlinx.coroutines.delay(50)
                    feedPosForMsg(msgIdx)?.let { listState.animateScrollToItem(it) }
                }
            }
        )
    }

    // Long-press Context Menu Dialog
    contextMenuMsg?.let { m ->
        val hasCode = TermuxBridge.hasCodeBlocks(m.content)
        ContextMenuDialog(
            message = m,
            isDark = isDark,
            hasCode = hasCode,
            onDismiss = { contextMenuMsg = null },
            onCopy = {
                copyPlain(ctx, m.content, "Скопировано")
                contextMenuMsg = null
            },
            onSelectText = {
                selectTextMsg = m
                contextMenuMsg = null
            },
            onRetry = {
                val target = m
                contextMenuMsg = null
                if (!streaming) {
                    if (target.role == "assistant") {
                        val pos = messages.indexOfFirst { it.id == target.id }
                        if (pos >= 0) {
                            while (messages.size > pos) messages.removeAt(messages.size - 1)
                            startStream()
                        }
                    } else {
                        messages.add(Message(role = "user", content = target.content))
                        startStream()
                    }
                }
            },
            onContinue = {
                val target = m
                contextMenuMsg = null
                val pos = messages.indexOfFirst { it.id == target.id }
                if (pos >= 0 && !streaming) startStream(continueTargetIdx = pos)
            },
            onAddToSkill = {
                val txt = m.content.trim().take(600)
                contextMenuMsg = null
                addSkillDraft = txt
            },
            onSendToTermux = {
                val content = m.content
                contextMenuMsg = null
                handleSendMessageToTermux(content)
            }
        )
    }

    // Specific Code Block Picker Dialog (when message has 2+ blocks)
    codePickerBlocks?.let { blocks ->
        CodeBlockPickerDialog(
            blocks = blocks,
            isDark = isDark,
            onDismiss = { codePickerBlocks = null },
            onPick = { b ->
                codePickerBlocks = null
                openTermuxRunner(b.lang, b.code, autoStart = true)
            }
        )
    }

    // Selectable Text Dialog ("Выбрать текст") with marker -> Termux action
    selectTextMsg?.let { m ->
        SelectTextDialog(
            message = m,
            isDark = isDark,
            fontSizeSp = settings.fontSizeSp.toFloat(),
            onDismiss = { selectTextMsg = null },
            onCopyAll = {
                copyPlain(ctx, m.content, "Скопировано полностью")
                selectTextMsg = null
            },
            onSendToTermux = { sel ->
                selectTextMsg = null
                openTermuxRunner("", sel, autoStart = false)
            },
            onEmptySelection = {
                Toast.makeText(ctx, "Выделите фрагмент маркером", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Unified Termux Script Runner Dialog
    termuxRunner?.let { tr ->
        val hasPerm = TermuxBridge.hasRunPermission(ctx)
        Dialog(
            onDismissRequest = { if (!tr.running) termuxRunner = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (tr.running) "⏳ Termux (${tr.elapsedSec}с)" else "▶ Termux",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("bash", "python3").forEach { interp ->
                                ModePill(
                                    label = if (interp == "python3") "python" else "bash",
                                    active = tr.interpreter == interp,
                                    isDark = isDark,
                                    onClick = {
                                        if (!tr.running) termuxRunner = tr.copy(interpreter = interp)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "✕",
                            fontSize = 18.sp,
                            modifier = Modifier.clickable {
                                if (tr.running) tr.jobHandle?.requestCancel(ctx)
                                termuxRunner = null
                            }.padding(4.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tr.script,
                        onValueChange = { if (!tr.running) termuxRunner = tr.copy(script = it) },
                        readOnly = tr.running,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = if (tr.running || tr.exitCode != null) 140.dp else 280.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (!hasPerm) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠️ Нет разрешения RUN_COMMAND (Настройки → Termux → Прямой запуск команд).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (tr.statusLine.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(tr.statusLine, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = DeepSeekBlue)
                    }
                    if (tr.running || tr.exitCode != null) {
                        Spacer(Modifier.height(6.dp))
                        val consoleText = if (tr.running) tr.liveTail.ifBlank { "Выполняется…" } else tr.resultBody
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDark) Color(0xFF13151A) else Color(0xFFF3F5F8),
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            Text(
                                text = consoleText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    if (tr.running) {
                        Button(
                            onClick = { tr.jobHandle?.requestCancel(ctx) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("⏹ Остановить") }
                    } else if (tr.exitCode != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { copyPlain(ctx, tr.resultBody, "Вывод скопирован") },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("❐") }
                            OutlinedButton(
                                onClick = { startTermuxRunner(tr) },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("↻") }
                            Button(
                                onClick = {
                                    val icon = if (tr.exitCode == 0) "\u2705" else "\u26A0\uFE0F"
                                    val tag = if (tr.interpreter == "python3") "python" else "bash"
                                    val txt = tr.resultBody.trim().ifBlank { "(пусто)" }
                                    val sizeLbl = if (txt.length >= 1024) "${txt.length / 1024} КБ" else "${txt.length} симв."
                                    attachments.add(
                                        AttachedFile(
                                            name = "\uD83D\uDCBB $tag $icon exit=${tr.exitCode} \u00B7 ${tr.durationSec}с",
                                            sizeLabel = sizeLbl,
                                            content = txt
                                        )
                                    )
                                    termuxRunner = null
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("📎 В чат") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    TermuxBridge.sendCodeToTermux(ctx, tr.interpreter, tr.script)
                                    termuxRunner = null
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("📤 Файлом") }
                            Button(
                                onClick = { startTermuxRunner(tr) },
                                enabled = hasPerm,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("▶ Запустить") }
                        }
                    }
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
                        val parsed = parseSkillJsonOrText(text, uri.lastPathSegment?.substringAfterLast('/').orEmpty())
                        if (parsed != null) {
                            eEmoji = parsed.emoji
                            eName = parsed.name
                            ePrompt = parsed.prompt
                            if (parsed.learnedSkill.isNotBlank()) eSkill = parsed.learnedSkill
                            Toast.makeText(ctx, "Загружен скилл «${parsed.name}»", Toast.LENGTH_SHORT).show()
                        } else {
                            ePrompt = text
                            Toast.makeText(ctx, "Загружено ${text.length} симв.", Toast.LENGTH_SHORT).show()
                        }
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
                        OutlinedButton(
                            onClick = {
                                val cur = ep.copy(
                                    emoji = eEmoji.ifBlank { "⭐" },
                                    name = eName.ifBlank { "Заготовка" },
                                    prompt = ePrompt,
                                    learnedSkill = eSkill
                                )
                                sharePresetAsDeepApk(ctx, cur)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("📤 .deepapk", fontSize = 12.sp) }
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

    if (showProjectFsDialog) {
        Dialog(onDismissRequest = { showProjectFsDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("📂 Проект SAF: $projectLabel", fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Авто-чтение кода для ИИ", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = projectAutoRead,
                            onCheckedChange = {
                                projectAutoRead = it
                                ProjectFs.setEnabled(ctx, it)
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                showProjectFsDialog = false
                                treePicker.launch(null)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("Сменить папку", fontSize = 12.5.sp) }
                        TextButton(onClick = {
                            ProjectFs.clearTree(ctx)
                            projectLabel = ""
                            projectAutoRead = false
                            showProjectFsDialog = false
                        }) { Text("Отключить", color = Color(0xFFE53935), fontSize = 12.5.sp) }
                    }
                }
            }
        }
    }

    // Incoming .deepapk Skill Package Installer Dialog
    incomingSkill?.let { pkg ->
        val existing = presets.firstOrNull { it.id == pkg.id }
        var keepExistingSkill by remember(pkg.id) { mutableStateOf(true) }
        fun installSkill(switchAndNewChat: Boolean) {
            val list = presets.toMutableList()
            val mergedLearned = when {
                existing != null && keepExistingSkill && existing.learnedSkill.isNotBlank() -> existing.learnedSkill
                pkg.learnedSkill.isNotBlank() -> pkg.learnedSkill
                else -> ""
            }
            val updated = PromptPreset(
                id = pkg.id,
                name = pkg.name,
                emoji = pkg.emoji,
                prompt = pkg.prompt,
                learnedSkill = mergedLearned,
                isBuiltIn = existing?.isBuiltIn ?: false
            )
            val idx = list.indexOfFirst { it.id == pkg.id }
            if (idx >= 0) list[idx] = updated else list.add(updated)
            PresetStore.save(ctx, list)
            presets = PresetStore.load(ctx)
            if (switchAndNewChat) {
                val s = ChatSession(
                    id = System.currentTimeMillis().toString(),
                    title = "Новый чат",
                    updatedAt = System.currentTimeMillis(),
                    messages = emptyList(),
                    presetId = updated.id
                )
                sessions.add(0, s)
                switchSession(s)
                setChatPreset(updated.id)
                ChatStore.saveSessions(ctx, sessions.toList())
            } else {
                setChatPreset(updated.id)
            }
            Toast.makeText(ctx, "Скилл «${updated.name}» сохранён", Toast.LENGTH_SHORT).show()
            onConsumeSkill()
        }
        Dialog(onDismissRequest = onConsumeSkill) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (isDark) Color(0xFF1F2229) else Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("${pkg.emoji} ${pkg.name}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (if (existing != null) "Обновить существующий скилл" else "Новый скилл .deepapk") +
                            " · ${pkg.prompt.length} симв.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            pkg.prompt.take(260),
                            fontSize = 12.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    if (existing != null && existing.learnedSkill.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = keepExistingSkill, onCheckedChange = { keepExistingSkill = it })
                            Text("Сохранить накопленные правила (🧠)", fontSize = 12.5.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onConsumeSkill) { Text("Отмена") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { installSkill(false) },
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("Сохранить", fontSize = 12.5.sp) }
                        Button(
                            onClick = { installSkill(true) },
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("В новый чат", fontSize = 12.5.sp) }
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

    val ocrPdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !ocrBusy) {
            ocrJob = scope.launch {
                ocrBusy = true
                ocrCur = 0
                ocrTotal = 0
                try {
                    ocrPdf(
                        ctx = ctx,
                        uri = uri,
                        forChat = false,
                        onProgress = { cur: Int, total: Int ->
                            ocrCur = cur
                            ocrTotal = total
                        }
                    )
                    ocrRefreshKey++
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // отменено — обработанные страницы остаются в кеше
                } catch (e: Throwable) {
                    android.util.Log.e("OCR", "manual upload: " + e.message, e)
                } finally {
                    ocrBusy = false
                }
            }
        }
    }
    if (showOcrBaseDialog) {
        var reloadKey by remember { mutableStateOf(0) }
        var confirmClear by remember { mutableStateOf(false) }
        var ocrStats by remember{mutableStateOf(OcrCache.Stats(0,0,0L,false))};var ocrList by remember{mutableStateOf<List<OcrCache.Entry>>(emptyList())}
        LaunchedEffect(reloadKey,ocrRefreshKey){ocrStats=withContext(Dispatchers.IO){OcrCache.stats(ctx)};ocrList=withContext(Dispatchers.IO){OcrCache.list(ctx)}}
        AlertDialog(
            onDismissRequest = { showOcrBaseDialog = false; confirmClear = false },
            title = { Text("📚 База OCR", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Страниц: ${ocrStats.pages} · Файлов: ${ocrStats.files} · ${OcrCache.formatBytes(ocrStats.bytes)}",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (ocrBusy) {
                        Text(
                            "📖 OCR: $ocrCur / ${if (ocrTotal > 0) ocrTotal else "?"}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        if (ocrTotal > 0) {
                            LinearProgressIndicator(
                                progress = { ocrCur.toFloat() / ocrTotal.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(6.dp)
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Можно отменить — уже распознанные страницы остаются в базе",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    if (ocrList.isEmpty()) {
                        Text(
                            "Пока пусто. Распознай PDF — каждая страница сохранится здесь (по содержимому, не по имени). Повторная обработка того же файла пойдёт из базы — секунды вместо минут.",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        ocrList.forEach { e ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${e.pages} стр. · ${OcrCache.formatBytes(e.bytes)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "🗑",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable {
                                            scope.launch(Dispatchers.IO){OcrCache.deleteOne(ctx,e.hash);withContext(Dispatchers.Main){reloadKey++}}
                                        }
                                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                    if (confirmClear && ocrList.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Нажми «Удалить всё» ещё раз для подтверждения",
                            fontSize = 12.sp,
                            color = Color(0xFFD32F2F)
                        )
                    }
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ocrList.isNotEmpty() && !ocrBusy) {
                        if (confirmClear) {
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO){OcrCache.clear(ctx);withContext(Dispatchers.Main){confirmClear=false;reloadKey++}}
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                            ) { Text("Удалить всё") }
                            Spacer(Modifier.width(6.dp))
                        } else {
                            OutlinedButton(onClick = { confirmClear = true }) { Text("🗑") }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    if (ocrBusy) {
                        Button(
                            onClick = { ocrJob?.cancel() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) { Text("⏹ Отменить") }
                    } else {
                        Button(onClick = { ocrPdfPicker.launch(arrayOf("application/pdf")) }) {
                            Text("📥 Загрузить PDF")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showOcrBaseDialog = false; confirmClear = false }) { Text("Закрыть") }
            }
        )
    }
}

