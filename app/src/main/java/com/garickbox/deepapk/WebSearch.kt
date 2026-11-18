package com.garickbox.deepapk

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object WebSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun tavily(apiKey: String, query: String): String {
        return try {
            val body = JSONObject()
                .put("api_key", apiKey)
                .put("query", query)
                .put("search_depth", "basic")
                .put("max_results", 5)
                .put("include_answer", false)
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("https://api.tavily.com/search").post(body).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return ""
            val json = JSONObject(resp.body!!.string())
            val results = json.optJSONArray("results") ?: return ""
            val sb = StringBuilder("[Web search results]\n")
            for (i in 0 until results.length()) {
                val o = results.getJSONObject(i)
                sb.append("${i + 1}. ${o.optString("title")}\n")
                sb.append("${o.optString("url")}\n")
                sb.append("${o.optString("content").take(400)}\n\n")
            }
            sb.toString()
        } catch (_: Exception) { "" }
    }
}
