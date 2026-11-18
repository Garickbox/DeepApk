package com.garickbox.deepapk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PromptPreset(
    val id: String,
    val name: String,
    val emoji: String,
    val prompt: String,
    val learnedSkill: String = "",
    val isBuiltIn: Boolean = false
) {
    fun buildSystemPrompt(globalExtra: String = ""): String {
        if (id == "default") return globalExtra
        val sb = StringBuilder()
        if (prompt.isNotBlank()) sb.append(prompt.trim())
        if (learnedSkill.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("Выученные правила пользователя:\n").append(learnedSkill.trim())
        }
        if (globalExtra.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("Дополнительно:\n").append(globalExtra.trim())
        }
        sb.append("\n\nЕсли пользователь просит запомнить новое правило или поправляет твой формат на будущее, добавь в самом конце ответа тег <skill_save>краткое правило в 1-2 строки</skill_save>.")
        return sb.toString()
    }
}

object PresetStore {
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "presets.json")

    fun defaults(): List<PromptPreset> = listOf(
        PromptPreset(
            id = "default",
            name = "Обычный",
            emoji = "💬",
            prompt = "",
            isBuiltIn = true
        ),
        PromptPreset(
            id = "termux",
            name = "Termux / Bash",
            emoji = "💻",
            prompt = "Ты — опытный Android/Linux-разработчик. Пользователь работает в Termux (Android) с проектами на GitHub.\n" +
                "Правила:\n" +
                "1. Отвечай на русском, чётко и по делу.\n" +
                "2. ЗАПРЕЩЕНО предлагать nano/vim/редакторы и дробить ответ на отдельные chmod/cat блоки. Собирай всё в ОДИН цельный копируемый bash-блок (через cat <<'EOF' > файл && chmod +x файл, или Python-патчер cat > ~/patch.py <<'PYEOF').\n" +
                "3. Все команды и патчи оборачивай в:\n{\n...\n} 2>&1 | tee ~/log.txt\ntermux-clipboard-set < ~/log.txt || true\n" +
                "4. Если сначала нужно увидеть код/ошибку — дай короткий диагностический grep/sed блок и не гадай.",
            isBuiltIn = true
        ),
        PromptPreset(
            id = "docx_report",
            name = "docx отчет",
            emoji = "📄",
            prompt = "Ты — генератор официальных ГОСТ-отчётов, рефератов, лабораторных и ответов на билеты.\nПриложение DeepApk само собирает идеальный .docx-файл по ГОСТу (А4, поля 20/15/20/30 мм, Times New Roman 14pt, 1.5 интервал, нумерация страниц, таблицы и картинки). ТЕБЕ НЕ НУЖНО писать <!DOCTYPE>, <style>, CRC32, ZIP или JavaScript.\nВсегда выдавай контент строго внутри тега:\n<doc_report title=\"Короткое_название_файла\">\n...HTML-разметка документа...\n</doc_report>\n\nПравила разметки внутри <doc_report>:\n1. Титул (если нужен): <div class=\"title-page\"> с <p class=\"center noind\"> и <p class=\"right noind\">, затем <div class=\"pagebreak\"></div>.\n2. Заголовки: <h1>, <h2>, <h3>.\n3. Абзацы: <p> (с красной строкой), <p class=\"noind\">, <p class=\"center\">, <p class=\"right\">, формулы <p class=\"formula\">, выделение <b>, <i>, <sub>, <sup>, жёлтые заглушки <span class=\"fill\">[...]</span>.\n4. Списки: <ul>/<ol> с <li>.\n5. Таблицы: <table><tr><th>...</th></tr><tr><td class=\"c\">...</td></tr></table>.\n6. Рисунки, схемы и графики: вставляй векторный <svg width=\"540\" height=\"240\" viewBox=\"0 0 540 240\">...</svg> (DeepApk сам отрендерит его внутрь Word!) + подпись <p class=\"noind center\">Рис. 1. Название</p>.\n7. Если приложена методичка/вопросы или задан объём (например 10 страниц) — выполняй полностью и глубоко, не сокращай разделы.",
            isBuiltIn = true
        ),
        PromptPreset(
            id = "patch_dev",
            name = "Патч-инженер",
            emoji = "🛠",
            prompt = "Ты — senior-разработчик. Работай хирургически:\n" +
                "1. Сначала проверь точные якоря/строки, не угадывай.\n" +
                "2. Делай компактный Python-патчер с проверкой вхождения якорей перед записью, а не переписывай длинные файлы целиком.\n" +
                "3. Если работа в Termux — давай один цельный блок с tee ~/log.txt.",
            isBuiltIn = true
        ),
        PromptPreset(
            id = "writer",
            name = "Редактор / Текст",
            emoji = "📚",
            prompt = "Ты — сильный редактор и автор текстов на русском языке. Пиши грамотно, структурированно и живо, без воды и ИИ-штампов.",
            isBuiltIn = true
        ),
        PromptPreset(
            id = "study",
            name = "Учёба / Экзамен",
            emoji = "🎓",
            prompt = "Ты — сильный преподаватель. Задачи оформляй чётко: Дано -> Формулы -> Подстановка и расчёт -> Ответ с единицами измерения. На экзаменационные вопросы давай структурированные готовые ответы.",
            isBuiltIn = true
        ),
        PromptPreset(
            id = "translator",
            name = "Переводчик",
            emoji = "🇬🇧",
            prompt = "Ты — профессиональный переводчик. Переводи точно и естественно (RU <-> EN или любой язык), при необходимости кратко поясняя термины и нюансы.",
            isBuiltIn = true
        ),
        PromptPreset(
            id = "concise",
            name = "Кратко",
            emoji = "⚡",
            prompt = "Отвечай максимально сжато: сразу готовый ответ, решение или список из 3-5 пунктов без вступлений и лишних пояснений.",
            isBuiltIn = true
        )
    )

    fun load(ctx: Context): MutableList<PromptPreset> = synchronized(lock) {
        val f = file(ctx)
        val base = defaults().associateBy { it.id }.toMutableMap()
        if (!f.exists()) {
            val list = defaults().toMutableList()
            saveLocked(ctx, list)
            return@synchronized list
        }
        return@synchronized try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<PromptPreset>()
            val seen = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id").trim()
                if (id.isEmpty()) continue
                seen.add(id)
                out.add(
                    PromptPreset(
                        id = id,
                        name = o.optString("name", id),
                        emoji = o.optString("emoji", "✨"),
                        prompt = o.optString("prompt", ""),
                        learnedSkill = o.optString("learnedSkill", ""),
                        isBuiltIn = o.optBoolean("isBuiltIn", base.containsKey(id))
                    )
                )
            }
            defaults().forEach { d -> if (d.id !in seen) out.add(d) }
            out
        } catch (_: Exception) {
            defaults().toMutableList()
        }
    }

    fun save(ctx: Context, list: List<PromptPreset>) = synchronized(lock) {
        saveLocked(ctx, list)
    }

    fun appendSkill(ctx: Context, presetId: String, rule: String) = synchronized(lock) {
        val clean = rule.trim()
        if (clean.isBlank()) return@synchronized
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == presetId }
        if (idx >= 0) {
            val cur = list[idx]
            val combined = if (cur.learnedSkill.isBlank()) "• $clean" else "${cur.learnedSkill.trim()}\n• $clean"
            list[idx] = cur.copy(learnedSkill = combined)
            saveLocked(ctx, list)
        }
    }

    private fun saveLocked(ctx: Context, list: List<PromptPreset>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("emoji", p.emoji)
                    .put("prompt", p.prompt)
                    .put("learnedSkill", p.learnedSkill)
                    .put("isBuiltIn", p.isBuiltIn)
            )
        }
        val target = file(ctx)
        val tmp = File(ctx.filesDir, "presets.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(arr.toString())
            tmp.delete()
        }
    }
}
