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

internal data class TermuxRunnerState(
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
    var projectAutoTermux by remember { mutableStateOf(ProjectFs.isAutoTermux(ctx)) }
    var autoTermuxHandle by remember { mutableStateOf<TermuxJobHandle?>(null) }
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
            val canAutoTermux = projectAutoTermux && TermuxBridge.hasRunPermission(ctx)
            val fsAdd = when {
                projectAutoRead && ProjectFs.isReady(ctx) -> ProjectFs.systemPromptAddendum(ctx, autoTermux = canAutoTermux)
                canAutoTermux -> ProjectFs.termuxAddendum()
                else -> ""
            }
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
                val feedbackParts = mutableListOf<String>()
                var writeFailed = false
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
                    val canContinueTool = !wasCancelled && !cancelRequested && continueTargetIdx == null && toolDepth < ChatSafController.SAF_MAX_TOOL_DEPTH
                    if (writeOps.isNotEmpty()) {
                        if (ProjectFs.isReady(ctx)) {
                            val wRes = try {
                                withContext(Dispatchers.IO) { ProjectFs.applyDirect(ctx, writeOps) }
                            } catch (e: Throwable) {
                                FsApplyResult(false, "Ошибка применения: " + (e.message ?: e.javaClass.simpleName))
                            }
                            if (wRes.ok) {
                                Toast.makeText(ctx, wRes.message, Toast.LENGTH_SHORT).show()
                                if (canContinueTool) {
                                    val filesLbl = wRes.modifiedFiles.joinToString(", ")
                                    feedbackParts.add("[SAF WRITE OK] ${wRes.message}" + if (filesLbl.isNotBlank()) " ($filesLbl)" else "")
                                }
                            } else {
                                writeFailed = true
                                error = wRes.message
                                if (canContinueTool) {
                                    feedbackParts.add("[SAF WRITE ERROR] ${wRes.message}")
                                }
                            }
                        } else if (canContinueTool) {
                            writeFailed = true
                            feedbackParts.add("[SAF WRITE ERROR] Папка SAF не подключена — <fs_patch>/<fs_write> не применён.")
                        }
                    }
                    if (canContinueTool && projectAutoRead && ProjectFs.isReady(ctx)) {
                        val readOps = ProjectFs.extractReadOps(last.content)
                        if (readOps.isNotEmpty()) {
                            safStatus = ChatSafController.buildStatusText(readOps.first())
                            val res = ChatSafController.runSafRead(
                                ctx = ctx,
                                ops = readOps,
                                originalContent = messages.getOrNull(targetIdx)?.content ?: last.content,
                                timeoutMsg = "> ⏱ SAF-чтение не ответило за 180 с (файл большой или папка недоступна). Нажми «Отправить» ещё раз, чтобы повторить."
                            )
                            if (!cancelRequested) {
                                when (res) {
                                    is SafReadResult.Success -> {
                                        safRetryTargetIdx = -1
                                        safRetryOps = emptyList()
                                        messages.getOrNull(targetIdx)?.let { curM ->
                                            messages[targetIdx] = curM.copy(content = res.strippedContent)
                                        }
                                        feedbackParts.add(res.payload)
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
                    val termuxCmds = if (canContinueTool) extractTermuxCmds(last.content).take(2) else emptyList()
                    if (termuxCmds.isNotEmpty()) {
                        if (writeFailed) {
                            feedbackParts.add("[TERMUX SKIPPED] Патч SAF не применился — команда не запущена. Сначала исправь патч.")
                        } else if (projectAutoTermux && TermuxBridge.hasRunPermission(ctx)) {
                            for (tc in termuxCmds) {
                                if (cancelRequested) break
                                val handle = TermuxJobHandle()
                                autoTermuxHandle = handle
                                safStatus = "Termux: ${tc.cmd.take(36)}…"
                                val (rc, body, dur) = ChatSafController.runTermuxCmd(ctx, tc.cmd, handle) { el, tail ->
                                    safStatus = "⏳ Termux ${el}с" + if (tail.isNotBlank()) " · ${tail.take(40)}" else ""
                                }
                                autoTermuxHandle = null
                                if (cancelRequested) break
                                val icon = if (rc == 0) "\u2705" else "\u26A0\uFE0F"
                                val cleanBody = body.trim().ifBlank { "(пусто)" }
                                val fence = if (cleanBody.contains("```")) "````" else "```"
                                feedbackParts.add("[Файл: \uD83D\uDCBB termux $icon exit=$rc \u00B7 ${dur}с]\n${fence}text\n$cleanBody\n$fence")
                                if (rc != 0) break
                            }
                            if (!cancelRequested) {
                                messages.getOrNull(targetIdx)?.let { curM ->
                                    val strippedT = stripTermuxTags(curM.content).replace(Regex("""\n{3,}"""), "\n\n").trim()
                                    val finalT = if (strippedT.isBlank()) "— выполнено в Termux: ${termuxCmds.first().cmd.take(60)}" else strippedT
                                    messages[targetIdx] = curM.copy(content = finalT)
                                }
                            }
                        } else if (projectAutoTermux) {
                            error = "Нет разрешения Termux RUN_COMMAND для авто-запуска"
                        }
                    }
                }
                persistCurrentSession()
                streaming = false
                streamJob = null
                if (feedbackParts.isNotEmpty() && !cancelRequested) {
                    safStatus = "Отправляю результат ИИ…"
                    messages.add(Message(role = "user", content = feedbackParts.joinToString("\n\n")))
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
                val topPreset = presets.firstOrNull { it.id == activePresetId } ?: presets.first()
                val safConnected = projectLabel.isNotBlank() && ProjectFs.isReady(ctx)
                ChatTopBar(
                    activeTitle = activeTitle,
                    topPreset = topPreset,
                    safConnected = safConnected,
                    safOn = safConnected && projectAutoRead,
                    projectLabel = projectLabel,
                    settings = settings,
                    streaming = streaming,
                    isDark = isDark,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenPresets = { showPresetsDialog = true },
                    onSafClick = { if (!safConnected) treePicker.launch(null) else showProjectFsDialog = true },
                    onSelectProvider = { pid -> val u = settings.copy(provider = pid); settings = u; SettingsStore.save(ctx, u) },
                    onOpenSettings = onOpenSettings,
                    onNewChat = { createNewSession() }
                )
                ChatStatusBanners(safStatus = safStatus, error = error, onClearError = { error = "" })

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        ChatEmptyWelcome(
                            providerLabel = settings.providerLabel(),
                            modelLabel = settings.activeModelLabel()
                        )
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
                                    is ChatFeedItem.UserBubble -> UserBubbleCard(
                                        msg = item.msg,
                                        fontSizeSp = settings.fontSizeSp,
                                        onInspectAttachment = { fName, fBody ->
                                            selectTextMsg = Message(role = "user", content = "$fName\n\n$fBody")
                                        },
                                        onLongClick = { contextMenuMsg = item.msg }
                                    )
                                    is ChatFeedItem.AssistantReasoning -> {
                                        val m = item.msg
                                        val expanded = m.id in reasoningExpanded
                                        AssistantReasoningCard(
                                            msg = m,
                                            expanded = expanded,
                                            isDark = isDark,
                                            onToggle = {
                                                reasoningExpanded = if (expanded) reasoningExpanded - m.id else reasoningExpanded + m.id
                                            }
                                        )
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
                                        AssistantFooterCard(
                                            msg = m,
                                            isLast = idx == messages.size - 1,
                                            streaming = streaming,
                                            hasSafRetry = safRetryOps.isNotEmpty() && safRetryTargetIdx >= 0 && idx == messages.size - 1,
                                            onCopy = { copyPlain(ctx, m.content, "Ответ скопирован") },
                                            onRetry = {
                                                messages.removeAt(idx)
                                                startStream()
                                            },
                                            onContinue = { startStream(continueTargetIdx = idx) },
                                            onRetrySaf = { retrySafRead() }
                                        )
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

                ChatInputBar(
                    input = input,
                    onInputChange = { input = it },
                    attachments = attachments,
                    onRemoveAttachment = { idx -> attachments.removeAt(idx) },
                    onInspectAttachment = { f -> selectTextMsg = Message(role = "user", content = "${f.name}\n\n${f.content}") },
                    settings = settings,
                    streaming = streaming,
                    isDark = isDark,
                    showAttachMenu = showAttachMenu,
                    onShowAttachMenuChange = { showAttachMenu = it },
                    onToggleThinking = {
                        val updated = settings.copy(thinkingEnabled = !settings.thinkingEnabled)
                        settings = updated
                        SettingsStore.save(ctx, updated)
                    },
                    onToggleSearch = {
                        val updated = settings.copy(webSearchEnabled = !settings.webSearchEnabled)
                        settings = updated
                        SettingsStore.save(ctx, updated)
                    },
                    onPhotoOcr = { launchPhotoOcr() },
                    onCameraOcr = { launchCameraOcr() },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onStop = {
                        cancelRequested = true
                        safStatus = ""
                        autoTermuxHandle?.requestCancel(ctx)
                        autoTermuxHandle = null
                        streamJob?.cancel()
                    },
                    onSend = { sendCurrentInput() }
                )
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
        TermuxRunnerDialog(
            tr = tr,
            isDark = isDark,
            hasPerm = TermuxBridge.hasRunPermission(ctx),
            onUpdate = { st -> termuxRunner = st },
            onStart = { st -> startTermuxRunner(st) },
            onAddAttachment = { att -> attachments.add(att) },
            onDismiss = { termuxRunner = null }
        )
    }

    // Presets picker dialog
    if (showPresetsDialog) {
        PresetsListDialog(
            presets = presets,
            activePresetId = activePresetId,
            isDark = isDark,
            onDismiss = { showPresetsDialog = false },
            onSelect = { id -> setChatPreset(id); showPresetsDialog = false },
            onCreateNew = {
                showPresetsDialog = false
                editingPreset = PromptPreset(
                    id = "custom_${System.currentTimeMillis()}",
                    name = "Моя заготовка",
                    emoji = "⭐",
                    prompt = "",
                    isBuiltIn = false
                )
            },
            onEdit = { p -> showPresetsDialog = false; editingPreset = p }
        )
    }

    // Create / Edit Preset Dialog
    editingPreset?.let { ep ->
        PresetEditDialog(
            preset = ep,
            presets = presets,
            isDark = isDark,
            onDismiss = { editingPreset = null },
            onSave = { up ->
                val list = presets.toMutableList()
                val i = list.indexOfFirst { it.id == up.id }
                if (i >= 0) list[i] = up else list.add(up)
                PresetStore.save(ctx, list)
                presets = PresetStore.load(ctx)
                setChatPreset(up.id)
                editingPreset = null
            },
            onDelete = {
                val list = presets.filterNot { it.id == ep.id }
                PresetStore.save(ctx, list)
                presets = PresetStore.load(ctx)
                if (activePresetId == ep.id) setChatPreset("default")
                editingPreset = null
            }
        )
    }

    if (showProjectFsDialog) {
        ProjectFsDialog(
            projectLabel = projectLabel,
            projectAutoRead = projectAutoRead,
            projectAutoTermux = projectAutoTermux,
            isDark = isDark,
            onDismiss = { showProjectFsDialog = false },
            onToggleAutoRead = { v ->
                projectAutoRead = v
                ProjectFs.setEnabled(ctx, v)
            },
            onToggleAutoTermux = { v ->
                projectAutoTermux = v
                ProjectFs.setAutoTermux(ctx, v)
            },
            onChangeFolder = {
                showProjectFsDialog = false
                treePicker.launch(null)
            },
            onClear = {
                ProjectFs.clearTree(ctx)
                projectLabel = ""
                projectAutoRead = false
                showProjectFsDialog = false
            }
        )
    }

    incomingSkill?.let { pkg ->
        val existing = presets.firstOrNull { it.id == pkg.id }
        IncomingSkillDialog(
            pkg = pkg,
            existing = existing,
            isDark = isDark,
            onDismiss = onConsumeSkill,
            onInstall = { keepExistingSkill, switchAndNewChat ->
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
        )
    }

    incomingShare?.let { share ->
        val recentFive = remember(sessions.toList()) {
            sessions.sortedByDescending { it.updatedAt }.take(5)
        }
        fun applyShareToCurrent() {
            if (share.text.isNotBlank()) {
                input = if (input.isBlank()) share.text else "${input.trimEnd()}\n\n${share.text}"
            }
            share.files.forEach { attachments.add(it) }
            onConsumeShare()
        }
        IncomingShareDialog(
            share = share,
            presets = presets,
            recentFive = recentFive,
            isDark = isDark,
            onDismiss = onConsumeShare,
            onOpenNewChat = { sharePresetId ->
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
            onPickExisting = { s ->
                switchSession(s)
                applyShareToCurrent()
            }
        )
    }

    // Add selected/copied message rule to preset
    addSkillDraft?.let { initial ->
        SkillRuleDialog(
            initial = initial,
            isDark = isDark,
            presets = presets,
            activePresetId = activePresetId,
            onDismiss = { addSkillDraft = null },
            onSave = { targetId, text ->
                PresetStore.appendSkill(ctx, targetId, text)
                presets = PresetStore.load(ctx)
                Toast.makeText(ctx, "Добавлено в заготовку", Toast.LENGTH_SHORT).show()
                addSkillDraft = null
            }
        )
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
        OcrBaseDialog(
            ocrBusy = ocrBusy,
            ocrCur = ocrCur,
            ocrTotal = ocrTotal,
            ocrRefreshKey = ocrRefreshKey,
            onDismiss = { showOcrBaseDialog = false },
            onCancelOcr = { ocrJob?.cancel() },
            onPickPdf = { ocrPdfPicker.launch(arrayOf("application/pdf")) }
        )
    }
}

