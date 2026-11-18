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
import java.net.URLEncoder
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
        .readTimeout(0, TimeUnit.SECONDS)
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
            .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
            .toMutableList()

        if (continuation) {
            prepared.add(
                Message(
                    role = "user",
                    content = "Продолжи ровно с места, где оборвался предыдущий ответ. Не повторяй уже написанное и не добавляй вступлений."
                )
            )
        }

        if (settings.provider == "gemini") {
            callRef = streamGemini(settings, prepared)
        } else {
            callRef = streamDeepSeek(settings, prepared)
        }

        close()
        awaitClose { callRef?.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun ProducerScope<StreamEvent>.streamDeepSeek(
        settings: AppSettings,
        history: List<Message>
    ): Call {
        val arr = JSONArray()
        if (settings.systemPrompt.isNotBlank()) {
            arr.put(JSONObject().put("role", "system").put("content", settings.systemPrompt))
        }
        history.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }

        val effectiveModel = if (settings.webSearchEnabled) "deepseek-flash" else settings.model
        val root = JSONObject()
            .put("model", effectiveModel)
            .put("messages", arr)
            .put("stream", true)
            .put("temperature", settings.temperature.toDouble())
            .put("max_tokens", settings.maxTokens)
            .put("stream_options", JSONObject().put("include_usage", true))

        if (settings.thinkingEnabled) {
            root.put("thinking", JSONObject().put("type", "enabled"))
        } else {
            root.put("thinking", JSONObject().put("type", "disabled"))
        }

        if (settings.webSearchEnabled) {
            val tools = JSONArray()
            tools.put(JSONObject().put("type", "deepseek_web_search"))
            tools.put(JSONObject().put("type", "deepseek_read_url"))
            root.put("tools", tools)
        }

        val body = root.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val call = client.newCall(req)
        var lastUsage = ""
        var truncated = false
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(800) ?: ""
                    trySend(StreamEvent.Error("DeepSeek API ${resp.code}: $err"))
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
                                    trySend(StreamEvent.Done(lastUsage, truncated))
                                    break
                                }
                                try {
                                    val obj = JSONObject(json)
                                    val errObj = obj.optJSONObject("error")
                                    if (errObj != null) {
                                        trySend(StreamEvent.Error(errObj.optString("message", "API error")))
                                        break
                                    }
                                    val usage = obj.optJSONObject("usage")
                                    if (usage != null) {
                                        val st = usage.optJSONObject("server_tool_use")
                                        val searches = st?.optInt("tool_search", 0) ?: 0
                                        if (searches > 0) trySend(StreamEvent.SearchInfo(searches))
                                        val pTok = usage.optInt("prompt_tokens", 0)
                                        val cTok = usage.optInt("completion_tokens", 0)
                                        if (pTok > 0 || cTok > 0) {
                                            lastUsage = "↑$pTok ↓$cTok"
                                        }
                                    }
                                    val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                                    val finishReason = choice.optString("finish_reason", "")
                                    if (finishReason == "length") truncated = true
                                    val delta = choice.optJSONObject("delta") ?: continue
                                    val reasoning = delta.optString("reasoning_content", "")
                                    if (reasoning.isNotEmpty() && reasoning != "null") {
                                        trySend(StreamEvent.Reasoning(reasoning))
                                    }
                                    val content = delta.optString("content", "")
                                    if (content.isNotEmpty() && content != "null") {
                                        trySend(StreamEvent.Content(content))
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isActive) {
                trySend(StreamEvent.Error(e.message ?: "unknown"))
            }
        }
        return call
    }

    private fun ProducerScope<StreamEvent>.streamGemini(
        settings: AppSettings,
        history: List<Message>
    ): Call? {
        val key = settings.geminiApiKey.trim()
        if (key.isBlank()) {
            trySend(StreamEvent.Error("Не задан Gemini API Key"))
            return null
        }

        val contents = JSONArray()
        history.forEach { msg ->
            val gRole = if (msg.role == "assistant") "model" else "user"
            val lastIdx = contents.length() - 1
            if (lastIdx >= 0 && contents.getJSONObject(lastIdx).optString("role") == gRole) {
                val parts = contents.getJSONObject(lastIdx).getJSONArray("parts")
                parts.put(JSONObject().put("text", msg.content))
            } else {
                contents.put(
                    JSONObject()
                        .put("role", gRole)
                        .put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
                )
            }
        }

        val genCfg = JSONObject()
            .put("temperature", settings.temperature.toDouble())
            .put("maxOutputTokens", settings.maxTokens.coerceAtMost(65536))

        if (settings.thinkingEnabled &&
            (settings.geminiModel.contains("2.5") || settings.geminiModel.contains("3"))
        ) {
            genCfg.put("thinkingConfig", JSONObject().put("includeThoughts", true))
        }

        val root = JSONObject()
            .put("contents", contents)
            .put("generationConfig", genCfg)

        if (settings.systemPrompt.isNotBlank()) {
            root.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", settings.systemPrompt)))
            )
        }

        if (settings.webSearchEnabled) {
            root.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }

        val encodedKey = URLEncoder.encode(key, "UTF-8")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${settings.geminiModel}:streamGenerateContent?alt=sse&key=$encodedKey"
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(root.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(req)
        var lastUsage = ""
        var truncated = false
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(800) ?: ""
                    trySend(StreamEvent.Error("Gemini API ${resp.code}: $err"))
                } else {
                    val stream = resp.body?.byteStream()
                    if (stream == null) {
                        trySend(StreamEvent.Error("Пустой ответ Gemini"))
                    } else {
                        BufferedReader(InputStreamReader(stream)).use { reader ->
                            while (isActive) {
                                val rawLine = reader.readLine() ?: break
                                val l = rawLine.trim()
                                if (!l.startsWith("data: ")) continue
                                val json = l.removePrefix("data: ").trim()
                                if (json == "[DONE]") break
                                try {
                                    val obj = JSONObject(json)
                                    val errObj = obj.optJSONObject("error")
                                    if (errObj != null) {
                                        trySend(StreamEvent.Error(errObj.optString("message", "Gemini error")))
                                        break
                                    }
                                    val um = obj.optJSONObject("usageMetadata")
                                    if (um != null) {
                                        val pTok = um.optInt("promptTokenCount", 0)
                                        val cTok = um.optInt("candidatesTokenCount", 0)
                                        if (pTok > 0 || cTok > 0) {
                                            lastUsage = "↑$pTok ↓$cTok"
                                        }
                                    }
                                    val cand = obj.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                                    val fr = cand.optString("finishReason", "")
                                    if (fr == "MAX_TOKENS") truncated = true
                                    val gm = cand.optJSONObject("groundingMetadata")
                                    val qCount = gm?.optJSONArray("webSearchQueries")?.length() ?: 0
                                    if (qCount > 0) trySend(StreamEvent.SearchInfo(qCount))

                                    val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: continue
                                    for (i in 0 until parts.length()) {
                                        val part = parts.getJSONObject(i)
                                        val txt = part.optString("text", "")
                                        if (txt.isEmpty()) continue
                                        if (part.optBoolean("thought", false)) {
                                            trySend(StreamEvent.Reasoning(txt))
                                        } else {
                                            trySend(StreamEvent.Content(txt))
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                        if (isActive) {
                            trySend(StreamEvent.Done(lastUsage, truncated))
                        }
                    }
                }
                Unit
            }
        } catch (e: Exception) {
            if (isActive) {
                trySend(StreamEvent.Error(e.message ?: "unknown"))
            }
        }
        return call
    }
}
