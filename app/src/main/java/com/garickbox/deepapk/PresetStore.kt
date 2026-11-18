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
            prompt = "Ты — генератор официальных учебных и технических отчётов, рефератов и ответов на билеты строго по ГОСТ (А4, поля верх/низ 20мм, право 15мм, лево 30мм, Times New Roman 14pt, интервал 1.5, красная строка 1.25см).\nВсегда выдавай ОДИН цельный самодостаточный HTML-файл (<!DOCTYPE html><html lang=\"ru\">...</html>) без сокращений и заглушек.\nТребования к HTML:\n1. Sticky .toolbar с кнопками: 📄 .docx (id=btnDocx, onclick=downloadDocx()), 📄 .rtf (downloadRtf()), 📄 .txt (downloadTxt()), 📄 .html (downloadHtml()), 🖨 PDF (window.print()).\n2. Блок .hint: что внутри и что заменить (жёлтый <span class=\"fill\">[...]</span>).\n3. Внутри <div class=\"sheet\"><div id=\"doc\">: титул, .pagebreak, <h1>/<h2>/<h3>, <p>, <p class=\"noind\">, <p class=\"formula\">, <table> (11pt, border 1px solid #000, <td class=\"c\">), нумерация, подписи, <svg> схемы/графики.\n4. Встроенный <script> БЕЗ внешних CDN: CRC32 + makeZip + OOXML (buildDocxBlob с word/document.xml, word/styles.xml, word/footer1.xml с PAGE, word/_rels/document.xml.rels, [Content_Types].xml, _rels/.rels; поля w:top=1134 w:right=850 w:bottom=1134 w:left=1701, таблицы с границами, w:highlight yellow для .fill, downloadDocx/Rtf/Txt/Html).",
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
