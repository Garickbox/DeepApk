package com.garickbox.deepapk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

sealed class StreamEvent {
    data class Reasoning(val delta: String) : StreamEvent()
    data class Content(val delta: String) : StreamEvent()
    data class SearchInfo(val queries: Int) : StreamEvent()
    data class Error(val msg: String) : StreamEvent()
    data class Done(val usage: String = "") : StreamEvent()
}

object DeepSeekApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun chatStream(
        apiKey: String,
        model: String,
        thinkingEnabled: Boolean,
        webSearchEnabled: Boolean,
        temperature: Float,
        maxTokens: Int,
        systemPrompt: String,
        history: List<Message>
    ): Flow<StreamEvent> = callbackFlow {
        val arr = JSONArray()
        if (systemPrompt.isNotBlank()) {
            arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        history
            .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
            .forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }

        val effectiveModel = if (webSearchEnabled) "deepseek-flash" else model
        val root = JSONObject()
            .put("model", effectiveModel)
            .put("messages", arr)
            .put("stream", true)
            .put("temperature", temperature.toDouble())
            .put("max_tokens", maxTokens)
            .put("stream_options", JSONObject().put("include_usage", true))

        if (thinkingEnabled) {
            root.put("thinking", JSONObject().put("type", "enabled"))
        } else {
            root.put("thinking", JSONObject().put("type", "disabled"))
        }

        if (webSearchEnabled) {
            val tools = JSONArray()
            tools.put(JSONObject().put("type", "deepseek_web_search"))
            tools.put(JSONObject().put("type", "deepseek_read_url"))
            root.put("tools", tools)
        }

        val body = root.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val call = client.newCall(req)
        var lastUsage = ""
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(800) ?: ""
                    trySend(StreamEvent.Error("API ${resp.code}: $err"))
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
                                    trySend(StreamEvent.Done(lastUsage))
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
        close()
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)
}
