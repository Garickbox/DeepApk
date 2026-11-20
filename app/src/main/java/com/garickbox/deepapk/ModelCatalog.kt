package com.garickbox.deepapk

data class ProviderMeta(val id: String, val title: String, val badge: String, val desc: String, val keyUrl: String, val keyHint: String)
data class ModelMeta(val id: String, val tag: String, val fitsFor: String, val limits: String, val recTemp: String)

object ModelCatalog {
    val providers = listOf(
        ProviderMeta("custom", "ProxyAPI / Свой", "МИР / СБП", "ProxyAPI (deepseek-v4.1-flash-1017) в рублях без VPN или свой URL.", "https://proxyapi.ru", "sk-..."),
        ProviderMeta("deepseek", "DeepSeek API", "Напрямую", "Официальный API с 50x дисковым кэшем и балансом в $.", "https://platform.deepseek.com/api_keys", "sk-...")
    )
    fun modelsFor(p: String): List<ModelMeta> = when (p) {
        "custom" -> listOf(
            ModelMeta("deepseek/deepseek-v4.1-flash-1017", "Осн. · Код + 1M", "Основная под .kt, патчи DeepApk и большие чаты.", "ProxyAPI · 1M", "0.2"),
            ModelMeta("deepseek/deepseek-chat", "Стандарт", "Быстрые ответы без длинного thinking.", "ProxyAPI", "0.2–0.7"),
            ModelMeta("deepseek/deepseek-reasoner", "Глубокий R1", "Сложная архитектура и отладка.", "ProxyAPI", "0.2")
        )
        else -> listOf(
            ModelMeta("deepseek-flash", "V4.1 Flash · Реком.", "Код, файлы, дешёвый дисковый кэш.", "Pay-as-you-go", "0.2"),
            ModelMeta("deepseek-chat", "Стандарт", "Классический режим без раздумий.", "Pay-as-you-go", "0.2–0.7"),
            ModelMeta("deepseek-reasoner", "Глубокий R1", "Пошаговое мышление.", "Pay-as-you-go", "0.2")
        )
    }
    fun findProvider(id: String) = providers.firstOrNull { it.id == id } ?: providers.first()
    fun findModel(p: String, m: String) = modelsFor(p).firstOrNull { it.id == m }
}
