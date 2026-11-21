package com.garickbox.deepapk

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/** P6 фаза 1: результат SAF-чтения (единый тип вместо Pair? + safErr: String?). */
sealed class SafReadResult {
    data class Success(val strippedContent: String, val payload: String) : SafReadResult()
    data class Error(val message: String) : SafReadResult()
    data class Timeout(val message: String) : SafReadResult()
}

/**
 * P6 фаза 1: вынос SAF-цикла (авто-чтение + retry) из ChatScreen.kt.
 * Compose state (safStatus, safRetryTargetIdx, safRetryOps, messages) остаются в ChatScreen.
 * Здесь только чистый исполняющий слой + форматирование ошибок/статусов.
 */
object ChatSafController {
    const val SAF_READ_TIMEOUT_MS = 180_000L
    const val SAF_MAX_TOOL_DEPTH = 10

    private const val MSG_SECURITY =
        "> 🔒 Доступ к папке проекта отозван или изменился. Переподключи папку через чип SAF в шапке и повтори."

    fun buildStatusText(op: FsReadOp): String = when (op) {
        is FsReadOp.Tree -> "Читаю дерево ${op.path.ifBlank { "/" }}…"
        is FsReadOp.Grep -> "Ищу «${op.pattern.take(20)}»…"
        is FsReadOp.Read -> "Читаю ${op.path.substringAfterLast("/")}…"
    }

    fun isSafErrorMessage(content: String): Boolean =
        content.startsWith("> 🔒 Доступ к папке") ||
        content.startsWith("> ⚠️ Ошибка SAF-чтения") ||
        content.startsWith("> ⏱ SAF-чтение") ||
        content.startsWith("> ⏱ Повторное SAF-чтение")

    /**
     * Единая точка SAF-чтения с таймаутом и обработкой ошибок.
     * @param timeoutMsg текст сообщения при истечении 180 с (для авто-чтения и retry отличается)
     * @param originalContent исходный контент ассистента (для вырезания тегов), пустой = не обрезать
     */
    suspend fun runSafRead(
        ctx: Context,
        ops: List<FsReadOp>,
        originalContent: String,
        timeoutMsg: String
    ): SafReadResult {
        return try {
            val res = withTimeoutOrNull(SAF_READ_TIMEOUT_MS) {
                runInterruptible(Dispatchers.IO) {
                    ProjectFs.executeReadOps(ctx, ops)
                }
            }
            if (res != null) {
                SafReadResult.Success(
                    strippedContent = if (originalContent.isBlank()) "" else ProjectFs.stripReadTags(originalContent, res.first),
                    payload = res.second
                )
            } else {
                SafReadResult.Timeout(timeoutMsg)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            android.util.Log.e("DeepApk", "SAF read SecurityException", se)
            SafReadResult.Error(MSG_SECURITY)
        } catch (te: Throwable) {
            android.util.Log.e("DeepApk", "SAF read failed", te)
            SafReadResult.Error("> ⚠️ Ошибка SAF-чтения: ${te.javaClass.simpleName}: ${te.message ?: "unknown"}")
        }
    }

    internal fun isBlockedTermuxCmd(cmd: String): Boolean {
        val c = cmd.lowercase().trim()
        if (Regex("""(^|[;&|\s])rm\s+-[a-z]*r[a-z]*\s+(/|~|\$home)(\s|$)""").containsMatchIn(c)) return true
        if (c.contains("mkfs.") || c.contains(":(){ :|:& };:")) return true
        return false
    }

    suspend fun runTermuxCmd(
        ctx: Context,
        cmd: String,
        handle: TermuxJobHandle,
        onTick: (Int, String) -> Unit
    ): Triple<Int, String, Int> {
        if (isBlockedTermuxCmd(cmd)) {
            return Triple(-5, "[TERMUX BLOCKED] Опасная команда отклонена: $cmd", 0)
        }
        var outCode = -1
        var outBody = ""
        var outDur = 0
        try {
            TermuxBridge.runScriptAndWatch(
                ctx = ctx,
                scriptText = cmd,
                interpreter = "bash",
                stallTimeoutSec = 120,
                totalTimeoutSec = 300,
                jobHandle = handle,
                onTick = onTick,
                onDone = { code, body, dur ->
                    outCode = code
                    outBody = body
                    outDur = dur
                }
            )
        } catch (ce: CancellationException) {
            handle.requestCancel(ctx)
            throw ce
        }
        return Triple(outCode, outBody, outDur)
    }
}