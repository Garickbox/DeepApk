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
        if (id != "default") {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("Если пользователь просит запомнить новое правило или поправляет твой формат на будущее, добавь в самом конце ответа тег <skill_save>краткое правило в 1-2 строки</skill_save>.")
        }
        return sb.toString()
    }
}

object PresetStore {
    private val lock = Any()
    private const val PREF_NAME = "deepapk_presets_meta"
    private const val CURRENT_VER = 3
    private val CORE_IDS = setOf("default", "termux", "docx_report")
    private val REMOVED_BUILTIN_IDS = setOf("patch_dev", "writer", "study", "translator", "concise")

    private fun file(ctx: Context) = File(ctx.filesDir, "presets.json")

    private fun readSkillAsset(ctx: Context, name: String, fallback: String): String {
        return try {
            ctx.assets.open("skills/$name").bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
                .ifBlank { fallback }
        } catch (_: Exception) {
            fallback
        }
    }

    fun loadCustomTemplate(ctx: Context): String {
        return readSkillAsset(ctx, "custom_template.md", "# РОЛЬ\nОпиши задачу и правила ассистента.")
    }

    fun defaults(ctx: Context): List<PromptPreset> = listOf(
        PromptPreset(
            id = "default",
            name = "Обычный",
            emoji = "💬",
            prompt = readSkillAsset(ctx, "default.md", "Ты — честный, точный и полезный универсальный помощник. Отвечай ясно, по делу и без выдуманных фактов."),
            isBuiltIn = true
        ),
        PromptPreset(
            id = "termux",
            name = "Termux / Bash",
            emoji = "💻",
            prompt = readSkillAsset(ctx, "termux.md", "Ты — опытный Android/Linux-разработчик для Termux. Давай цельный bash-блок с tee ~/log.txt."),
            isBuiltIn = true
        ),
        PromptPreset(
            id = "docx_report",
            name = "docx отчет",
            emoji = "📄",
            prompt = readSkillAsset(ctx, "docx_report.md", "Генерируй ГОСТ-документы внутри <doc_report title=\"Отчёт\">...</doc_report>."),
            isBuiltIn = true
        )
    )

    fun load(ctx: Context): MutableList<PromptPreset> = synchronized(lock) {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedVer = prefs.getInt("ver", 1)
        val f = file(ctx)
        val freshDefaults = defaults(ctx)

        if (!f.exists()) {
            val list = freshDefaults.toMutableList()
            saveLocked(ctx, list)
            prefs.edit().putInt("ver", CURRENT_VER).apply()
            return@synchronized list
        }

        return@synchronized try {
            val arr = JSONArray(f.readText())
            val parsed = mutableListOf<PromptPreset>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id").trim()
                if (id.isEmpty()) continue
                parsed.add(
                    PromptPreset(
                        id = id,
                        name = o.optString("name", id),
                        emoji = o.optString("emoji", "⭐"),
                        prompt = o.optString("prompt", ""),
                        learnedSkill = o.optString("learnedSkill", ""),
                        isBuiltIn = o.optBoolean("isBuiltIn", id in CORE_IDS)
                    )
                )
            }

            if (savedVer < CURRENT_VER) {
                val skillsById = parsed.associate { it.id to it.learnedSkill }
                val out = mutableListOf<PromptPreset>()
                freshDefaults.forEach { d ->
                    out.add(d.copy(learnedSkill = skillsById[d.id].orEmpty()))
                }
                parsed.forEach { p ->
                    val isOldRemoved = p.id in REMOVED_BUILTIN_IDS || p.isBuiltIn
                    if (p.id !in CORE_IDS && !isOldRemoved) {
                        out.add(p.copy(isBuiltIn = false))
                    }
                }
                saveLocked(ctx, out)
                prefs.edit().putInt("ver", CURRENT_VER).apply()
                return@synchronized out
            }

            val seen = parsed.map { it.id }.toMutableSet()
            freshDefaults.forEach { d ->
                if (d.id !in seen) parsed.add(d)
            }
            parsed
        } catch (_: Exception) {
            val list = freshDefaults.toMutableList()
            saveLocked(ctx, list)
            prefs.edit().putInt("ver", CURRENT_VER).apply()
            list
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
