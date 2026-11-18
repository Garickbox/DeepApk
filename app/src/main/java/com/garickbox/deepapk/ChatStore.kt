package com.garickbox.deepapk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Message(val role: String, val content: String)

object ChatStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "chats.json")
    fun load(ctx: Context): MutableList<Message> {
        val f = file(ctx); if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Message(arr.getJSONObject(it).getString("role"), arr.getJSONObject(it).getString("content")) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }
    fun save(ctx: Context, msgs: List<Message>) {
        val arr = JSONArray()
        msgs.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        file(ctx).writeText(arr.toString())
    }
}
