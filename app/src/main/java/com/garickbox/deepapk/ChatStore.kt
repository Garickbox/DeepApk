package com.garickbox.deepapk

import android.content.Context
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
    private const val PREF = "deepapk_chat_prefs"
    private const val KEY_ACTIVE = "active_session_id"

    private fun sessionsFile(ctx: Context) = File(ctx.filesDir, "sessions.json")
    private fun legacyFile(ctx: Context) = File(ctx.filesDir, "chats.json")

    fun getActiveSessionId(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null)

    fun setActiveSessionId(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun loadSessions(ctx: Context): MutableList<ChatSession> = synchronized(lock) {
        val f = sessionsFile(ctx)
        if (!f.exists()) {
            val legacy = loadLegacy(ctx)
            if (legacy.isNotEmpty()) {
                val firstUser = legacy.firstOrNull { it.role == "user" }?.content
                    ?.trim()?.replace("\n", " ")?.take(36).orEmpty().ifBlank { "Чат" }
                val migrated = mutableListOf(
                    ChatSession(
                        id = System.currentTimeMillis().toString(),
                        title = firstUser,
                        updatedAt = System.currentTimeMillis(),
                        messages = legacy
                    )
                )
                saveSessionsLocked(ctx, migrated)
                return@synchronized migrated
            }
            return@synchronized mutableListOf()
        }
        return@synchronized try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<ChatSession>()
            for (i in 0 until arr.length()) {
                val sObj = arr.getJSONObject(i)
                val mArr = sObj.optJSONArray("messages") ?: JSONArray()
                val msgs = mutableListOf<Message>()
                for (j in 0 until mArr.length()) {
                    val o = mArr.getJSONObject(j)
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
                out.add(
                    ChatSession(
                        id = sObj.optString("id").ifBlank { nextMsgId().toString() },
                        title = sObj.optString("title", "Новый чат").ifBlank { "Новый чат" },
                        updatedAt = sObj.optLong("updatedAt", 0L),
                        messages = msgs,
                        presetId = sObj.optString("presetId", "default").ifBlank { "default" }
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveSessions(ctx: Context, sessions: List<ChatSession>) = synchronized(lock) {
        saveSessionsLocked(ctx, sessions)
    }

    private fun saveSessionsLocked(ctx: Context, sessions: List<ChatSession>) {
        val arr = JSONArray()
        sessions.forEach { s ->
            val mArr = JSONArray()
            s.messages.forEach { m ->
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
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("updatedAt", s.updatedAt)
                    .put("presetId", s.presetId)
                    .put("messages", mArr)
            )
        }
        val target = sessionsFile(ctx)
        val tmp = File(ctx.filesDir, "sessions.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(arr.toString())
            tmp.delete()
        }
    }

    private fun loadLegacy(ctx: Context): List<Message> {
        val f = legacyFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Message(
                    role = o.optString("role"),
                    content = o.optString("content"),
                    reasoning = o.optString("reasoning", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
