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

object DeepSeekApi {
    private val client = OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()

    fun chatStream(apiKey: String, messages: List<Message>): Flow<String> = callbackFlow {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", "deepseek-v4-pro").put("messages", arr).put("stream", true)
            .toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer $apiKey").post(body).build()

        val call = client.newCall(req)
        val resp = call.execute()
        if (!resp.isSuccessful) {
            trySend("❌ API ${resp.code}: ${resp.body?.string()?.take(200)}")
            close(); return@callbackFlow
        }

        val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream()))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line!!.trim()
            if (l.startsWith("data: ")) {
                val json = l.removePrefix("data: ").trim()
                if (json == "[DONE]") break
                try {
                    val delta = JSONObject(json).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content") ?: ""
                    if (delta.isNotEmpty()) trySend(delta)
                } catch (_: Exception) {}
            }
        }
        close()
        awaitClose { call.cancel() }
    }
}
