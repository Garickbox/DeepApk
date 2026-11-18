package com.garickbox.deepapk

import android.content.Context

data class AppSettings(
    val apiKey: String = DEFAULT_API_KEY,
    val model: String = "deepseek-v4-pro",
    val thinkingEnabled: Boolean = true,
    val webSearchEnabled: Boolean = false,
    val temperature: Float = 1.0f,
    val maxTokens: Int = 32768,
    val systemPrompt: String = "Ты — полезный ассистент. Отвечай развёрнуто, с примерами кода где уместно."
) {
    companion object {
        const val DEFAULT_API_KEY = "sk-3ae012f894a941b782c34ad9d8a561be"
    }
}

object SettingsStore {
    private const val PREF = "deepapk_settings"
    fun load(ctx: Context): AppSettings {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return AppSettings(
            apiKey = p.getString("api_key", AppSettings.DEFAULT_API_KEY) ?: AppSettings.DEFAULT_API_KEY,
            model = p.getString("model", "deepseek-v4-pro") ?: "deepseek-v4-pro",
            thinkingEnabled = p.getBoolean("thinking_enabled", true),
            webSearchEnabled = p.getBoolean("web_search_enabled", false),
            temperature = p.getFloat("temperature", 1.0f),
            maxTokens = p.getInt("max_tokens", 32768),
            systemPrompt = p.getString("system_prompt", AppSettings().systemPrompt) ?: AppSettings().systemPrompt
        )
    }
    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("api_key", s.apiKey)
            .putString("model", s.model)
            .putBoolean("thinking_enabled", s.thinkingEnabled)
            .putBoolean("web_search_enabled", s.webSearchEnabled)
            .putFloat("temperature", s.temperature)
            .putInt("max_tokens", s.maxTokens)
            .putString("system_prompt", s.systemPrompt)
            .apply()
    }
}
