package com.garickbox.deepapk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

data class SkillPackage(
    val id: String,
    val name: String,
    val emoji: String,
    val prompt: String,
    val learnedSkill: String = "",
    val version: Int = 1,
    val fileName: String = ""
)

private fun queryUriDisplayName(ctx: Context, uri: Uri): String {
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx).orEmpty()
            }
        }
    } catch (_: Exception) {}
    return uri.lastPathSegment?.substringAfterLast('/').orEmpty()
}

internal fun skillFormatMatches(fields: Map<String, String>, isDeepExt: Boolean): Boolean {
    val fmt = fields["format"].orEmpty()
    return fmt == "deepapk-skill-v1" || (isDeepExt && fields.containsKey("prompt"))
}

internal fun parseSkillFromFields(fields: Map<String, String>, fileName: String): SkillPackage? {
    val prompt = fields["prompt"].orEmpty().trim()
    if (prompt.isBlank()) return null
    val rawBase = fileName.substringBeforeLast('.').ifBlank { "skill" }
    val rawId = fields["id"].orEmpty().trim().ifBlank { rawBase }
    val cleanId = rawId.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        .ifBlank { "custom_${System.currentTimeMillis()}" }
    return SkillPackage(
        id = cleanId,
        name = fields["name"].orEmpty().trim().ifBlank { rawBase },
        emoji = fields["emoji"].orEmpty().trim().ifBlank { "🛠" }.take(4),
        prompt = prompt,
        learnedSkill = fields["learnedSkill"].orEmpty().trim(),
        version = fields["version"]?.toIntOrNull() ?: 1,
        fileName = fileName
    )
}

internal fun parseSkillFromDeepapkBody(text: String, fileName: String): SkillPackage? {
    if (!fileName.endsWith(".deepapk", ignoreCase = true)) return null
    val base = fileName.substringBeforeLast('.').ifBlank { "Новый скилл" }
    val cleanId = base.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        .ifBlank { "custom_${System.currentTimeMillis()}" }
    return SkillPackage(
        id = cleanId,
        name = base,
        emoji = "⭐",
        prompt = text.trim(),
        version = 1,
        fileName = fileName
    )
}

fun parseSkillJsonOrText(rawText: String, fileName: String = ""): SkillPackage? {
    val trimmed = rawText.trim()
    if (trimmed.isBlank()) return null
    val isDeepExt = fileName.endsWith(".deepapk", ignoreCase = true)
    if (trimmed.startsWith("{")) {
        try {
            val o = JSONObject(trimmed)
            val fields = mutableMapOf<String, String>()
            for (k in listOf("format", "prompt", "id", "name", "emoji", "learnedSkill", "version")) {
                if (o.has(k)) fields[k] = o.optString(k, "")
            }
            if (skillFormatMatches(fields, isDeepExt)) {
                return parseSkillFromFields(fields, fileName)
            }
        } catch (_: Exception) {}
    }
    return parseSkillFromDeepapkBody(trimmed, fileName)
}

@Suppress("DEPRECATION")
fun extractIncomingSkill(ctx: Context, intent: Intent?): SkillPackage? {
    if (intent == null) return null
    val action = intent.action ?: return null
    val uri: Uri? = when (action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> null
    }
    if (uri == null) return null
    return try {
        val name = queryUriDisplayName(ctx, uri)
        val raw = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return null
        if (action == Intent.ACTION_SEND && !name.endsWith(".deepapk", ignoreCase = true) && !raw.contains("deepapk-skill-v1")) {
            return null
        }
        parseSkillJsonOrText(raw, name)
    } catch (_: Exception) {
        null
    }
}

fun toDeepApkJson(preset: PromptPreset): String {
    return JSONObject()
        .put("format", "deepapk-skill-v1")
        .put("version", 1)
        .put("id", preset.id)
        .put("name", preset.name)
        .put("emoji", preset.emoji)
        .put("prompt", preset.prompt)
        .put("learnedSkill", preset.learnedSkill)
        .toString(2)
}

fun sharePresetAsDeepApk(ctx: Context, preset: PromptPreset) {
    try {
        val dir = File(ctx.cacheDir, "skills").apply { mkdirs() }
        val slug = preset.id.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifBlank { "skill" }
        val outFile = File(dir, "$slug.deepapk")
        outFile.writeText(toDeepApkJson(preset), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", outFile)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${preset.emoji} ${preset.name}.deepapk")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(share, "Поделиться скиллом .deepapk"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
