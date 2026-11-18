package com.garickbox.deepapk

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private val idGen = AtomicLong(System.currentTimeMillis() * 1000L)

fun nextMsgId(): Long = idGen.incrementAndGet()

data class Message(
    val id: Long = nextMsgId(),
    val role: String,
    val content: String,
    val reasoning: String = "",
    val usage: String = "",
    val truncated: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<Message>,
    val presetId: String = "default"
)

object ChatStore {
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PREF = "deepapk_chat_prefs"
    private const val KEY_ACTIVE = "active_session_id"

    private fun indexFile(ctx: Context) = File(ctx.filesDir, "sessions_index.json")
    private fun chatFile(ctx: Context, id: String) = File(ctx.filesDir, "chat_${id.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.json")
    private fun legacySessionsFile(ctx: Context) = File(ctx.filesDir, "sessions.json")
    private fun legacyChatsFile(ctx: Context) = File(ctx.filesDir, "chats.json")

    fun getActiveSessionId(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null)

    fun setActiveSessionId(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id).apply()
    }

    private fun parseMessages(arr: JSONArray): List<Message> {
        val msgs = mutableListOf<Message>()
        for (j in 0 until arr.length()) {
            val o = arr.getJSONObject(j)
            val savedId = o.optLong("id", 0L)
            msgs.add(
                Message(
                    id = if (savedId > 0L) savedId else nextMsgId(),
                    role = o.optString("role"),
                    content = o.optString("content"),
                    reasoning = o.optString("reasoning", ""),
                    usage = o.optString("usage", ""),
                    truncated = o.optBoolean("truncated", false),
                    timestamp = o.optLong("timestamp", 0L)
                )
            )
        }
        return msgs
    }

    private fun serializeMessages(msgs: List<Message>): JSONArray {
        val mArr = JSONArray()
        msgs.forEach { m ->
            mArr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("role", m.role)
                    .put("content", m.content)
                    .put("reasoning", m.reasoning)
                    .put("usage", m.usage)
                    .put("truncated", m.truncated)
                    .put("timestamp", m.timestamp)
            )
        }
        return mArr
    }

    private fun atomicWrite(dir: File, target: File, content: String) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    fun loadSessionMessages(ctx: Context, id: String): List<Message> = synchronized(lock) {
        val f = chatFile(ctx, id)
        if (!f.exists()) return@synchronized emptyList()
        return@synchronized try {
            parseMessages(JSONArray(f.readText()))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadSessions(ctx: Context): MutableList<ChatSession> = synchronized(lock) {
        val idxF = indexFile(ctx)
        if (!idxF.exists()) migrateIfNeededLocked(ctx)
        val out = mutableListOf<ChatSession>()
        if (idxF.exists()) {
            try {
                val arr = JSONArray(idxF.readText())
                val activeId = getActiveSessionId(ctx)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id").ifBlank { nextMsgId().toString() }
                    val loadMsgs = (id == activeId) || (activeId == null && i == 0)
                    out.add(
                        ChatSession(
                            id = id,
                            title = o.optString("title", "Новый чат").ifBlank { "Новый чат" },
                            updatedAt = o.optLong("updatedAt", 0L),
                            messages = if (loadMsgs) loadSessionMessages(ctx, id) else emptyList(),
                            presetId = o.optString("presetId", "default").ifBlank { "default" }
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        out
    }

    private fun migrateIfNeededLocked(ctx: Context) {
        val leg = legacySessionsFile(ctx)
        if (leg.exists()) {
            try {
                val arr = JSONArray(leg.readText())
                val metas = mutableListOf<ChatSession>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id").ifBlank { nextMsgId().toString() }
                    val msgs = parseMessages(o.optJSONArray("messages") ?: JSONArray())
                    atomicWrite(ctx.filesDir, chatFile(ctx, id), serializeMessages(msgs).toString())
                    metas.add(
                        ChatSession(
                            id = id,
                            title = o.optString("title", "Новый чат").ifBlank { "Новый чат" },
                            updatedAt = o.optLong("updatedAt", 0L),
                            messages = emptyList(),
                            presetId = o.optString("presetId", "default").ifBlank { "default" }
                        )
                    )
                }
                writeIndexLocked(ctx, metas)
                leg.renameTo(File(ctx.filesDir, "sessions.json.bak"))
                return
            } catch (_: Exception) {}
        }
        val old = legacyChatsFile(ctx)
        if (old.exists()) {
            try {
                val msgs = parseMessages(JSONArray(old.readText()))
                if (msgs.isNotEmpty()) {
                    val id = System.currentTimeMillis().toString()
                    val title = msgs.firstOrNull { it.role == "user" }?.content?.trim()?.replace("\n", " ")?.take(36).orEmpty().ifBlank { "Чат" }
                    atomicWrite(ctx.filesDir, chatFile(ctx, id), serializeMessages(msgs).toString())
                    writeIndexLocked(ctx, listOf(ChatSession(id, title, System.currentTimeMillis(), emptyList())))
                }
            } catch (_: Exception) {}
        }
    }

    private fun writeIndexLocked(ctx: Context, sessions: List<ChatSession>) {
        val arr = JSONArray()
        sessions.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("updatedAt", s.updatedAt)
                    .put("presetId", s.presetId)
            )
        }
        atomicWrite(ctx.filesDir, indexFile(ctx), arr.toString())
    }

    fun saveSessionAndIndexAsync(ctx: Context, session: ChatSession, allSessions: List<ChatSession>) {
        val appCtx = ctx.applicationContext
        val snapSession = session.copy(messages = session.messages.toList())
        val snapIndex = allSessions.map { it.copy(messages = emptyList()) }
        ioScope.launch {
            synchronized(lock) {
                atomicWrite(appCtx.filesDir, chatFile(appCtx, snapSession.id), serializeMessages(snapSession.messages).toString())
                writeIndexLocked(appCtx, snapIndex)
            }
        }
    }

    fun saveSessions(ctx: Context, sessions: List<ChatSession>) {
        val appCtx = ctx.applicationContext
        val snapIndex = sessions.map { it.copy(messages = emptyList()) }
        ioScope.launch {
            synchronized(lock) { writeIndexLocked(appCtx, snapIndex) }
        }
    }

    fun deleteSessionFiles(ctx: Context, id: String) {
        val appCtx = ctx.applicationContext
        ioScope.launch {
            synchronized(lock) { chatFile(appCtx, id).delete() }
        }
    }
}
