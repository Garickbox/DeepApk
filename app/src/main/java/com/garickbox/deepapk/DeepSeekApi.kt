package com.garickbox.deepapk

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

sealed class StreamEvent {
    data class Reasoning(val delta: String) : StreamEvent()
    data class Content(val delta: String) : StreamEvent()
    data class Error(val msg: String) : StreamEvent()
    object Done : StreamEvent()
}

object DeepSeekApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun chatStream(
        apiKey: String,
        model: String,
        temperature: Float,
        maxTokens: Int,
        systemPrompt: String,
        history: List<Message>,
        webContext: String = ""
    ): Flow<StreamEvent> = callbackFlow {
        val arr = JSONArray()
        if (systemPrompt.isNotBlank()) {
            val sp = if (webContext.isNotBlank()) "$systemPrompt\n\n$webContext" else systemPrompt
            arr.put(JSONObject().put("role", "system").put("content", sp))
        }
        history.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }

        val body = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("stream", true)
            .put("temperature", temperature.toDouble())
            .put("max_tokens", maxTokens)
            .toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body).build()

        val call = client.newCall(req)
        try {
            val resp = call.execute()
            if (!resp.isSuccessful) {
                val err = resp.body?.string()?.take(500) ?: ""
                trySend(StreamEvent.Error("API ${resp.code}: $err"))
                close(); return@callbackFlow
            }
            val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream()))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                if (!l.startsWith("data: ")) continue
                val json = l.removePrefix("data: ").trim()
                if (json == "[DONE]") { trySend(StreamEvent.Done); break }
                try {
                    val choice = JSONObject(json).optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta") ?: continue
                    val reasoning = delta.optString("reasoning_content", "")
                    if (reasoning.isNotEmpty()) trySend(StreamEvent.Reasoning(reasoning))
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty() && content != "null") trySend(StreamEvent.Content(content))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            trySend(StreamEvent.Error(e.message ?: "unknown"))
        }
        close()
        awaitClose { call.cancel() }
    }
}
