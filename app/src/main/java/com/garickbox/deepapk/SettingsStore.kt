package com.garickbox.deepapk

import android.content.Context

data class AppSettings(
    val provider: String = "deepseek",
    val apiKey: String = "",
    val geminiApiKey: String = "",
    val openrouterApiKey: String = "",
    val groqApiKey: String = "",
    val customApiKey: String = "",
    val model: String = "deepseek-flash",
    val geminiModel: String = "gemini-3-flash-preview",
    val openrouterModel: String = "cohere/north-mini-code:free",
    val groqModel: String = "openai/gpt-oss-120b",
    val customBaseUrl: String = "https://api.proxyapi.ru/openai/v1",
    val customModel: String = "deepseek-chat",
    val thinkingEnabled: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val temperature: Float = 0.2f,
    val maxTokens: Int = 16384,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val themeMode: String = "system",
    val fontSizeSp: Int = 15
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай развёрнуто, с примерами кода где уместно."
        val ALL_PROVIDERS = listOf("gemini", "openrouter", "groq", "deepseek", "custom")
    }
    fun hasKey(p: String = provider) = when (p) {
        "gemini" -> geminiApiKey.isNotBlank()
        "openrouter" -> openrouterApiKey.isNotBlank()
        "groq" -> groqApiKey.isNotBlank()
        "custom" -> customApiKey.isNotBlank() && customBaseUrl.isNotBlank()
        else -> apiKey.isNotBlank()
    }
    fun activeKey() = when (provider) {
        "gemini" -> geminiApiKey
        "openrouter" -> openrouterApiKey
        "groq" -> groqApiKey
        "custom" -> customApiKey
        else -> apiKey
    }
    fun withActiveKey(k: String) = when (provider) {
        "gemini" -> copy(geminiApiKey = k.trim())
        "openrouter" -> copy(openrouterApiKey = k.trim())
        "groq" -> copy(groqApiKey = k.trim())
        "custom" -> copy(customApiKey = k.trim())
        else -> copy(apiKey = k.trim())
    }
    fun activeModelLabel() = when (provider) {
        "gemini" -> geminiModel
        "openrouter" -> openrouterModel
        "groq" -> groqModel
        "custom" -> customModel
        else -> if (webSearchEnabled) "deepseek-flash" else model
    }
    fun withActiveModel(m: String) = when (provider) {
        "gemini" -> copy(geminiModel = m.trim())
        "openrouter" -> copy(openrouterModel = m.trim())
        "groq" -> copy(groqModel = m.trim())
        "custom" -> copy(customModel = m.trim())
        else -> copy(model = m.trim())
    }
    fun providerLabel(p: String = provider) = ModelCatalog.findProvider(p).title
    fun providerKeyUrl(p: String = provider) = ModelCatalog.findProvider(p).keyUrl
    fun providerShort(p: String = provider) = when (p) {
        "gemini" -> "✦ Gemini"; "openrouter" -> "OR Free"; "groq" -> "⚡ Groq"; "custom" -> "🔗 Proxy"; else -> "DS"
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
        val gemM = (p.getString("gemini_model", "gemini-3-flash-preview") ?: "gemini-3-flash-preview").let {
            when {
                it in listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro") -> "gemini-3-flash-preview"
                it == "gemini-3.8-flash" && !p.getBoolean("mig38", false) -> {
                    p.edit().putBoolean("mig38", true).putString("gemini_model", "gemini-3-flash-preview").apply()
                    "gemini-3-flash-preview"
                }
                else -> it
            }
        }
        return AppSettings(
            provider = p.getString("provider", "deepseek") ?: "deepseek",
            apiKey = p.getString("api_key", "") ?: "",
            geminiApiKey = p.getString("gemini_api_key", "") ?: "",
            openrouterApiKey = p.getString("or_api_key", "") ?: "",
            groqApiKey = p.getString("groq_api_key", "") ?: "",
            customApiKey = p.getString("custom_api_key", "") ?: "",
            model = p.getString("model", "deepseek-flash") ?: "deepseek-flash",
            geminiModel = gemM,
            openrouterModel = p.getString("or_model", "cohere/north-mini-code:free") ?: "cohere/north-mini-code:free",
            groqModel = p.getString("groq_model", "openai/gpt-oss-120b") ?: "openai/gpt-oss-120b",
            customBaseUrl = p.getString("custom_base_url", "https://api.proxyapi.ru/openai/v1") ?: "https://api.proxyapi.ru/openai/v1",
            customModel = p.getString("custom_model", "deepseek-chat") ?: "deepseek-chat",
            thinkingEnabled = p.getBoolean("thinking_enabled", true),
            webSearchEnabled = webSearch,
            temperature = p.getFloat("temperature", 0.2f),
            maxTokens = p.getInt("max_tokens", 16384),
            systemPrompt = p.getString("system_prompt", AppSettings.DEFAULT_SYSTEM_PROMPT) ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
            themeMode = p.getString("theme_mode", "system") ?: "system",
            fontSizeSp = p.getInt("font_size_sp", 15).coerceIn(12, 20)
        )
    }

    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("provider", s.provider)
            .putString("api_key", s.apiKey.trim())
            .putString("gemini_api_key", s.geminiApiKey.trim())
            .putString("or_api_key", s.openrouterApiKey.trim())
            .putString("groq_api_key", s.groqApiKey.trim())
            .putString("custom_api_key", s.customApiKey.trim())
            .putString("model", s.model)
            .putString("gemini_model", s.geminiModel)
            .putString("or_model", s.openrouterModel)
            .putString("groq_model", s.groqModel)
            .putString("custom_base_url", s.customBaseUrl.trim())
            .putString("custom_model", s.customModel.trim())
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
