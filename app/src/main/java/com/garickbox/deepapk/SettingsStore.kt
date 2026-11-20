package com.garickbox.deepapk

import android.content.Context

data class AppSettings(
    val provider: String = "custom",
    val apiKey: String = "",
    val customApiKey: String = "",
    val model: String = "deepseek-flash",
    val customBaseUrl: String = "https://api.proxyapi.ru/v1/chat/completions",
    val customModel: String = "deepseek/deepseek-v4.1-flash-1017",
    val thinkingEnabled: Boolean = true,
    val webSearchEnabled: Boolean = false,
    val temperature: Float = 0.2f,
    val maxTokens: Int = 32768,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val themeMode: String = "system",
    val fontSizeSp: Int = 15
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай развёрнуто, с примерами кода где уместно."
        val ALL_PROVIDERS = listOf("custom", "deepseek")
    }
    fun hasKey(p: String = provider) = when (p) {
        "custom" -> customApiKey.isNotBlank() && customBaseUrl.isNotBlank()
        else -> apiKey.isNotBlank()
    }
    fun activeKey() = when (provider) {
        "custom" -> customApiKey
        else -> apiKey
    }
    fun withActiveKey(k: String) = when (provider) {
        "custom" -> copy(customApiKey = k.trim())
        else -> copy(apiKey = k.trim())
    }
    fun activeModelLabel() = when (provider) {
        "custom" -> customModel
        else -> model
    }
    fun withActiveModel(m: String) = when (provider) {
        "custom" -> copy(customModel = m.trim())
        else -> copy(model = m.trim())
    }
    fun providerLabel(p: String = provider) = ModelCatalog.findProvider(p).title
    fun providerKeyUrl(p: String = provider) = ModelCatalog.findProvider(p).keyUrl
    fun providerShort(p: String = provider) = if (p == "custom") "🔗 Proxy" else "DS"
}

object SettingsStore {
    private const val PREF = "deepapk_settings"

    fun load(ctx: Context): AppSettings {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val prov = (p.getString("provider", "custom") ?: "custom").let { if (it in AppSettings.ALL_PROVIDERS) it else "custom" }
        val url = (p.getString("custom_base_url", "https://api.proxyapi.ru/v1/chat/completions") ?: "https://api.proxyapi.ru/v1/chat/completions").let { if (it.isBlank() || it.trimEnd('/') == "https://api.proxyapi.ru/openai/v1") "https://api.proxyapi.ru/v1/chat/completions" else it }
        var cm = p.getString("custom_model", "deepseek/deepseek-v4.1-flash-1017") ?: "deepseek/deepseek-v4.1-flash-1017"; var ws = p.getBoolean("web_search_enabled", false); var mt = p.getInt("max_tokens", 32768)
        if (!p.getBoolean("mig_v41", false)) { if (cm == "deepseek-chat") cm = "deepseek/deepseek-v4.1-flash-1017"; if (mt == 16384) mt = 32768; ws = false; p.edit().putBoolean("mig_v41", true).putString("provider", prov).putString("custom_base_url", url).putString("custom_model", cm).putInt("max_tokens", mt).putBoolean("web_search_enabled", false).apply() }
        return AppSettings(
            provider = prov,
            apiKey = p.getString("api_key", "") ?: "",
            customApiKey = p.getString("custom_api_key", "") ?: "",
            model = p.getString("model", "deepseek-flash") ?: "deepseek-flash",
            customBaseUrl = url,
            customModel = cm,
            thinkingEnabled = p.getBoolean("thinking_enabled", true),
            webSearchEnabled = ws,
            temperature = p.getFloat("temperature", 0.2f),
            maxTokens = mt,
            systemPrompt = p.getString("system_prompt", AppSettings.DEFAULT_SYSTEM_PROMPT) ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
            themeMode = p.getString("theme_mode", "system") ?: "system",
            fontSizeSp = p.getInt("font_size_sp", 15).coerceIn(12, 21)
        )
    }

    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("provider", s.provider)
            .putString("api_key", s.apiKey.trim())
            .putString("custom_api_key", s.customApiKey.trim())
            .putString("model", s.model)
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
