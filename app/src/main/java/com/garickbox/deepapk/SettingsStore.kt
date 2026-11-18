package com.garickbox.deepapk

import android.content.Context

data class AppSettings(
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val thinkingEnabled: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val temperature: Float = 1.0f,
    val maxTokens: Int = 16384,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай развёрнуто, с примерами кода где уместно."
    }
}

object SettingsStore {
    private const val PREF = "deepapk_settings"

    fun load(ctx: Context): AppSettings {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val webSearch = when {
            p.contains("web_search_enabled") -> p.getBoolean("web_search_enabled", true)
            p.contains("webSearchEnabled") -> p.getBoolean("webSearchEnabled", true)
            else -> true
        }
        return AppSettings(
            apiKey = p.getString("api_key", "") ?: "",
            model = p.getString("model", "deepseek-flash") ?: "deepseek-flash",
            thinkingEnabled = p.getBoolean("thinking_enabled", true),
            webSearchEnabled = webSearch,
            temperature = p.getFloat("temperature", 1.0f),
            maxTokens = p.getInt("max_tokens", 16384),
            systemPrompt = p.getString("system_prompt", AppSettings.DEFAULT_SYSTEM_PROMPT)
                ?: AppSettings.DEFAULT_SYSTEM_PROMPT
        )
    }

    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("api_key", s.apiKey.trim())
            .putString("model", s.model)
            .putBoolean("thinking_enabled", s.thinkingEnabled)
            .putBoolean("web_search_enabled", s.webSearchEnabled)
            .remove("webSearchEnabled")
            .putFloat("temperature", s.temperature)
            .putInt("max_tokens", s.maxTokens)
            .putString("system_prompt", s.systemPrompt)
            .apply()
    }
}
