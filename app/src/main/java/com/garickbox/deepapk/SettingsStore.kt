package com.garickbox.deepapk
import android.content.Context
data class AppSettings(
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val thinkingEnabled: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val temperature: Float = 1.0f,
    val maxTokens: Int = 16384,
    val systemPrompt: String = "Ты — полезный ассистент. Отвечай развёрнуто, с примерами кода где уместно."
)
object SettingsStore {
    private const val PREF = "deepapk_settings"
    fun load(ctx: Context): AppSettings {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return AppSettings(
            apiKey = p.getString("api_key", "") ?: "",
            model = p.getString("model", "deepseek-flash") ?: "deepseek-flash",
            thinkingEnabled = p.getBoolean("thinking_enabled", true),
            webSearchEnabled = p.getBoolean("web_search_enabled", true),
            temperature = p.getFloat("temperature", 1.0f),
            maxTokens = p.getInt("max_tokens", 16384),
            systemPrompt = p.getString("system_prompt", AppSettings().systemPrompt) ?: ""
        )
    }
    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("api_key", s.apiKey)
            .putString("model", s.model)
            .putBoolean("thinking_enabled", s.thinkingEnabled)
            .putBoolean("webSearchEnabled", s.webSearchEnabled)
            .putFloat("temperature", s.temperature)
            .putInt("max_tokens", s.maxTokens)
            .putString("system_prompt", s.systemPrompt)
            .apply()
    }
}
