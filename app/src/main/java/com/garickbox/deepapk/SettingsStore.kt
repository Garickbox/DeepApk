package com.garickbox.deepapk

import android.content.Context

data class AppSettings(
    val provider: String = "deepseek",
    val apiKey: String = "",
    val geminiApiKey: String = "",
    val model: String = "deepseek-flash",
    val geminiModel: String = "gemini-2.5-flash",
    val thinkingEnabled: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val temperature: Float = 1.0f,
    val maxTokens: Int = 16384,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val themeMode: String = "system",
    val fontSizeSp: Int = 15
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай развёрнуто, с примерами кода где уместно."
    }

    fun activeKey(): String = if (provider == "gemini") geminiApiKey else apiKey
    fun activeModelLabel(): String =
        if (provider == "gemini") geminiModel
        else if (webSearchEnabled) "deepseek-flash" else model
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
            provider = p.getString("provider", "deepseek") ?: "deepseek",
            apiKey = p.getString("api_key", "") ?: "",
            geminiApiKey = p.getString("gemini_api_key", "") ?: "",
            model = p.getString("model", "deepseek-flash") ?: "deepseek-flash",
            geminiModel = p.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash",
            thinkingEnabled = p.getBoolean("thinking_enabled", true),
            webSearchEnabled = webSearch,
            temperature = p.getFloat("temperature", 1.0f),
            maxTokens = p.getInt("max_tokens", 16384),
            systemPrompt = p.getString("system_prompt", AppSettings.DEFAULT_SYSTEM_PROMPT)
                ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
            themeMode = p.getString("theme_mode", "system") ?: "system",
            fontSizeSp = p.getInt("font_size_sp", 15).coerceIn(12, 20)
        )
    }

    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("provider", s.provider)
            .putString("api_key", s.apiKey.trim())
            .putString("gemini_api_key", s.geminiApiKey.trim())
            .putString("model", s.model)
            .putString("gemini_model", s.geminiModel)
            .putBoolean("thinking_enabled", s.thinkingEnabled)
            .putBoolean("web_search_enabled", s.webSearchEnabled)
            .remove("webSearchEnabled")
            .putFloat("temperature", s.temperature)
            .putInt("max_tokens", s.maxTokens)
            .putString("system_prompt", s.systemPrompt)
            .putString("theme_mode", s.themeMode)
            .putInt("font_size_sp", s.fontSizeSp)
            .apply()
    }
}
