package com.garickbox.deepapk

import android.content.Context

data class AppSettings(
    val apiKey: String = "",
    val model: String = "deepseek-reasoner",
    val temperature: Float = 1.0f,
    val maxTokens: Int = 32768,
    val systemPrompt: String = "Ты — полезный ассистент. Отвечай развёрнуто, с примерами кода где уместно.",
    val tavilyKey: String = "",
    val webSearchEnabled: Boolean = false
)

object SettingsStore {
    private const val PREF = "deepapk_settings"
    fun load(ctx: Context): AppSettings {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return AppSettings(
            apiKey = p.getString("api_key", "") ?: "",
            model = p.getString("model", "deepseek-reasoner") ?: "deepseek-reasoner",
            temperature = p.getFloat("temperature", 1.0f),
            maxTokens = p.getInt("max_tokens", 32768),
            systemPrompt = p.getString("system_prompt", AppSettings().systemPrompt) ?: "",
            tavilyKey = p.getString("tavily_key", "") ?: "",
            webSearchEnabled = p.getBoolean("web_search_enabled", false)
        )
    }
    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("api_key", s.apiKey)
            .putString("model", s.model)
            .putFloat("temperature", s.temperature)
            .putInt("max_tokens", s.maxTokens)
            .putString("system_prompt", s.systemPrompt)
            .putString("tavily_key", s.tavilyKey)
            .putBoolean("web_search_enabled", s.webSearchEnabled)
            .apply()
    }
}
