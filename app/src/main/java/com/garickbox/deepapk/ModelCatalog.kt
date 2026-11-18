package com.garickbox.deepapk

data class ProviderMeta(val id: String, val title: String, val badge: String, val desc: String, val keyUrl: String, val keyHint: String)
data class ModelMeta(val id: String, val tag: String, val fitsFor: String, val limits: String, val recTemp: String)

object ModelCatalog {
    val providers = listOf(
        ProviderMeta("gemini", "Google Gemini", "Фри · 1M окно", "Лучший бесплатный старт: файлы, Kotlin, патчи. В РФ через VPN.", "https://aistudio.google.com/apikey", "AIza... или AQ..."),
        ProviderMeta("openrouter", "OpenRouter (:free)", "Фри · 50–1000/д", "Много бесплатных :free моделей для кода. 50/день (с $10 — 1000/день).", "https://openrouter.ai/settings/keys", "sk-or-v1-..."),
        ProviderMeta("groq", "Groq (Быстрый)", "Фри · ~500 т/с", "Молниеносный для скриптов. Лимит ~8K токенов/мин (не под гигантские чаты).", "https://console.groq.com/keys", "gsk_..."),
        ProviderMeta("deepseek", "DeepSeek API", "Платный · Топ код", "Лучший под длинные чаты с кэшем. Прямая карта РФ не идёт (см. Proxy).", "https://platform.deepseek.com/api_keys", "sk-..."),
        ProviderMeta("custom", "РФ-прокси / Свой", "МИР / СБП", "ProxyAPI, VseGPT, AITunnel в рублях без VPN или свой OpenAI URL.", "https://proxyapi.ru", "sk-...")
    )
    fun modelsFor(p: String): List<ModelMeta> = when (p) {
        "gemini" -> listOf(
            ModelMeta("gemini-3-flash-preview", "Код + Файлы · Реком.", "Kotlin/Compose, патчи DeepApk, thinking, окно до 1M.", "10–15/мин · ~20–250/д", "0.2"),
            ModelMeta("gemini-3.1-flash-lite", "Запас · До 500/д", "Резерв при 429/503: мелкие правки и быстрые вопросы.", "15/мин · до 500/д", "0.2–0.4"),
            ModelMeta("gemini-3.8-flash", "Новейшая Flash", "Свежая Flash с thinking (в пик бывает 503).", "Free (плавающий)", "0.2"),
            ModelMeta("gemini-3.1-pro-preview", "Pro · Биллинг", "Тяжёлая архитектура. Бесплатно обычно 429.", "Платный ($)", "0.2")
        )
        "openrouter" -> listOf(
            ModelMeta("cohere/north-mini-code:free", "Код + Агент · 256K", "Агентский кодинг и точные правки файлов.", "20/мин · 50–1000/д", "0.2"),
            ModelMeta("google/gemma-4-31b-it:free", "Универсал + Код", "Kotlin/Python, тексты, разбор ошибок.", "20/мин · 50–1000/д", "0.2–0.5"),
            ModelMeta("nvidia/nemotron-3-ultra-550b-a55b:free", "Логика · 1M окно", "Глубокий анализ трудных багов (1M окно).", "20/мин · 50–1000/д", "0.2"),
            ModelMeta("openrouter/free", "Авто-роутер", "Сам ищет свободную :free модель.", "20/мин · 50–1000/д", "0.3")
        )
        "groq" -> listOf(
            ModelMeta("openai/gpt-oss-120b", "Флагман · Код", "120B под код и дебаг (131K, ~500 т/с).", "30/мин · ~8K т/мин", "0.2"),
            ModelMeta("llama-3.3-70b-versatile", "Логика + Рефактор", "Рефакторинг, объяснение кода и тексты.", "30/мин · 200K т/д", "0.2–0.5"),
            ModelMeta("qwen/qwen3.8-27b", "Код + Русский", "Отлично знает русский и алгоритмы.", "30/мин · Free", "0.2"),
            ModelMeta("llama-3.1-8b-instant", "Молния · Скрипты", "Мгновенно для bash и синтаксиса.", "Высокий лимит", "0.2–0.5")
        )
        "deepseek" -> listOf(
            ModelMeta("deepseek-flash", "Код + Поиск", "Ежедневный код + поиск, дешёвый кэш.", "Pay-as-you-go", "0.2"),
            ModelMeta("deepseek-v4-pro", "Максимум код", "Сложные многофайловые правки.", "Pay-as-you-go", "0.2"),
            ModelMeta("deepseek-reasoner", "Глубокий R1", "Пошаговое мышление перед ответом.", "Pay-as-you-go", "0.2–0.6"),
            ModelMeta("deepseek-chat", "Стандарт", "Классический режим без веб-поиска.", "Pay-as-you-go", "0.2–0.7")
        )
        else -> listOf(
            ModelMeta("deepseek-chat", "DeepSeek (прокси)", "Стабильный код в ₽ (ProxyAPI/VseGPT).", "По тарифу прокси", "0.2"),
            ModelMeta("deepseek-reasoner", "DeepSeek R1 (прокси)", "Глубокое рассуждение в ₽.", "По тарифу прокси", "0.2–0.5"),
            ModelMeta("gemini-2.5-flash", "Gemini (прокси)", "Большой контекст без VPN в ₽.", "По тарифу прокси", "0.2–0.4"),
            ModelMeta("gpt-4o-mini", "GPT-4o mini", "Быстрая модель на каждый день.", "По тарифу прокси", "0.3–0.7")
        )
    }
    fun findProvider(id: String) = providers.firstOrNull { it.id == id } ?: providers.first { it.id == "deepseek" }
    fun findModel(p: String, m: String) = modelsFor(p).firstOrNull { it.id == m }
}
