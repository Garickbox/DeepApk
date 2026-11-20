package com.garickbox.deepapk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

sealed class StreamEvent {
    data class Reasoning(val delta: String) : StreamEvent()
    data class Content(val delta: String) : StreamEvent()
    data class SearchInfo(val queries: Int) : StreamEvent()
    data class Error(val msg: String) : StreamEvent()
    data class Done(val usage: String = "", val truncated: Boolean = false) : StreamEvent()
}

data class BalanceInfo(val isAvailable: Boolean, val text: String)

object DeepSeekApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchBalance(apiKey: String): BalanceInfo? {
        val key = apiKey.trim()
        if (key.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url("https://api.deepseek.com/user/balance")
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val obj = JSONObject(body)
                val avail = obj.optBoolean("is_available", false)
                val infos = obj.optJSONArray("balance_infos") ?: return BalanceInfo(avail, "0.00")
                val parts = mutableListOf<String>()
                for (i in 0 until infos.length()) {
                    val b = infos.getJSONObject(i)
                    val total = b.optString("total_balance", "0")
                    val curr = b.optString("currency", "")
                    if (total.isNotBlank()) parts.add("$total $curr".trim())
                }
                BalanceInfo(avail, parts.joinToString(" · ").ifBlank { "0.00" })
            }
        } catch (_: Exception) {
            null
        }
    }

    fun chatStream(
        settings: AppSettings,
        history: List<Message>,
        continuation: Boolean = false
    ): Flow<StreamEvent> = callbackFlow {
        var callRef: Call? = null
        val prepared = history
            .filter { (it.role == "user" || it.role == "assistant") && (it.content.isNotBlank() || (it.role == "assistant" && it.reasoning.isNotBlank())) }
            .map { if (it.role == "assistant" && it.content.isBlank()) it.copy(content = "[обрыв]") else it }.toMutableList()
        if (continuation) prepared.add(Message(role = "user", content = if (prepared.lastOrNull()?.content == "[обрыв]") "Рассуждения оборвались. Сразу выдай ответ." else "Продолжи с места обрыва."))
        callRef = when (settings.provider) {
            "custom" -> { val b = settings.customBaseUrl.trim().trimEnd('/'); streamOpenAiCompat(settings, prepared, if (b.endsWith("/chat/completions")) b else "$b/chat/completions", settings.customApiKey, settings.customModel, false) }
            else -> streamOpenAiCompat(settings, prepared, "https://api.deepseek.com/chat/completions", settings.apiKey, settings.model, true)
        }
        close()
        awaitClose { callRef?.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun ProducerScope<StreamEvent>.streamOpenAiCompat(
        settings: AppSettings, history: List<Message>, endpointUrl: String,
        rawKey: String, modelName: String, isDirect: Boolean
    ): Call? {
        val key = rawKey.trim()
        if (key.isBlank()) { trySend(StreamEvent.Error("Не задан API-ключ (${settings.providerLabel()})")); return null }
        if (!endpointUrl.startsWith("http")) { trySend(StreamEvent.Error("Неверный Base URL")); return null }
        val arr = JSONArray()
        if (settings.systemPrompt.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", settings.systemPrompt))
        history.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val root = JSONObject().put("model", modelName.ifBlank { "deepseek/deepseek-v4.1-flash-1017" }).put("messages", arr).put("stream", true)
            .put("max_tokens", settings.maxTokens).put("stream_options", JSONObject().put("include_usage", true))
            .put("thinking", JSONObject().put("type", if (settings.thinkingEnabled) "enabled" else "disabled"))
        if (!settings.thinkingEnabled) root.put("temperature", settings.temperature.toDouble())
        if (isDirect && settings.webSearchEnabled) root.put("tools", JSONArray().put(JSONObject().put("type", "deepseek_web_search")).put(JSONObject().put("type", "deepseek_read_url")))
        val req = Request.Builder().url(endpointUrl).header("Authorization", "Bearer $key").header("Content-Type", "application/json").post(root.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(req)
        var lastUsage = ""
        var truncated = false; var gotC = false; var gotR = false; var done = false
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(800) ?: ""
                    trySend(StreamEvent.Error("${settings.providerLabel()} ${resp.code}: $err"))
                } else {
                    val stream = resp.body?.byteStream()
                    if (stream == null) {
                        trySend(StreamEvent.Error("Пустой ответ сервера"))
                    } else {
                        BufferedReader(InputStreamReader(stream)).use { reader ->
                            while (isActive) {
                                val rawLine = reader.readLine() ?: break
                                val l = rawLine.trim()
                                if (!l.startsWith("data: ")) continue
                                val json = l.removePrefix("data: ").trim()
                                if (json == "[DONE]") {
                                    done = true
                                    if (!gotC && gotR) { trySend(StreamEvent.Done(lastUsage, true)); trySend(StreamEvent.Error("Рассуждения оборвались — нажми «⏭ Продолжить»")) } else if (!gotC) trySend(StreamEvent.Error("Соединение закрыто без ответа")) else trySend(StreamEvent.Done(lastUsage, truncated))
                                    break
                                }
                                try {
                                    val obj = JSONObject(json)
                                    val errObj = obj.optJSONObject("error")
                                    if (errObj != null) {
                                        done = true; trySend(StreamEvent.Error(errObj.optString("message", "API error"))); break
                                    }
                                    val usage = obj.optJSONObject("usage")
                                    if (usage != null) {
                                        val st = usage.optJSONObject("server_tool_use")
                                        val searches = st?.optInt("tool_search", 0) ?: 0
                                        if (searches > 0) trySend(StreamEvent.SearchInfo(searches))
                                        val pTok = usage.optInt("prompt_tokens", 0)
                                        val cTok = usage.optInt("completion_tokens", 0)
                                        val hit = usage.optInt("prompt_cache_hit_tokens", -1).let { if (it >= 0) it else usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0) ?: 0 }
                                        if (pTok > 0 || cTok > 0) lastUsage = "↑$pTok${if (pTok > 0 && hit > 0) " ⚡${hit * 100 / pTok}%" else ""} ↓$cTok"
                                    }
                                    val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                                    val finishReason = choice.optString("finish_reason", "")
                                    if (finishReason == "length") truncated = true
                                    val delta = choice.optJSONObject("delta") ?: continue
                                    val reasoning = delta.optString("reasoning_content", "").ifBlank { delta.optString("reasoning", "") }
                                    if (reasoning.isNotEmpty() && reasoning != "null") {
                                        gotR = true; trySend(StreamEvent.Reasoning(reasoning))
                                    }
                                    val content = delta.optString("content", "")
                                    if (content.isNotEmpty() && content != "null") {
                                        gotC = true; trySend(StreamEvent.Content(content))
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                        if (isActive && !done) { if (!gotC && gotR) { trySend(StreamEvent.Done(lastUsage, true)); trySend(StreamEvent.Error("Рассуждения оборвались — нажми «⏭ Продолжить»")) } else if (!gotC) trySend(StreamEvent.Error("Соединение закрыто без ответа")) else trySend(StreamEvent.Done(lastUsage, truncated)) }
                    }
                }
                Unit
            }
        } catch (_: SocketTimeoutException) {
            if (isActive) trySend(StreamEvent.Error("Таймаут ответа сервера (120с)"))
        } catch (e: Exception) {
            if (isActive) {
                trySend(StreamEvent.Error(e.message ?: "unknown"))
            }
        }
        return call
    }


}
