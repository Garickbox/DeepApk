package com.garickbox.deepapk

import android.content.Context

/** Одна строка diff: ' ' = контекст, '-' = было, '+' = станет. */
data class PreviewLine(
    val kind: Char,
    val oldNo: Int?,
    val newNo: Int?,
    val text: String
)

/** Один блок правок с @@-заголовком. */
data class PreviewHunk(
    val header: String,
    val lines: List<PreviewLine>
)

/** Полный preview одной fs_patch/fs_write операции. */
data class PatchPreview(
    val path: String,
    val exists: Boolean,
    val notes: List<String>,
    val hunks: List<PreviewHunk>
) {
    fun render(): String = buildString {
        appendLine("=== $path ===")
        if (!exists) appendLine("(файл будет создан)")
        notes.forEach { appendLine("⚠\uFE0F $it") }
        hunks.forEach { hunk ->
            appendLine(hunk.header)
            hunk.lines.forEach { line ->
                val no = when (line.kind) {
                    '-' -> line.oldNo
                    '+' -> line.newNo
                    else -> line.oldNo
                }?.toString().orEmpty().padStart(4)
                append(no).append(' ').append(line.kind).append(' ').append(line.text).append('\n')
            }
        }
    }

    fun stats(): String {
        var add = 0
        var del = 0
        hunks.forEach { h -> h.lines.forEach { l ->
            when (l.kind) {
                '+' -> add++
                '-' -> del++
            }
        } }
        return "+$add −$del"
    }
}

/**
 * Строит patch preview без применения. Читает файл через ProjectFs.readRawContent
 * (никаких записей), находит блоки `old` в текущем содержимом и рендерит построчный diff.
 * Дизайн: без LCS/Левенштейна — прямое отображение old/new + 2 строки контекста вокруг.
 */
object PatchPreviewBuilder {
    private const val CONTEXT_LINES = 2
    private const val MAX_LINES_PER_HUNK = 60
    private const val MAX_HUNKS = 8

    fun build(ctx: Context, op: FsWriteOp): PatchPreview = when (op) {
        is FsWriteOp.Patch -> buildForPatch(ctx, op)
        is FsWriteOp.Write -> buildForWrite(ctx, op)
    }

    private fun buildForPatch(ctx: Context, op: FsWriteOp.Patch): PatchPreview {
        val current = ProjectFs.readRawContent(ctx, op.path)
            ?: return PatchPreview(
                path = op.path,
                exists = false,
                notes = listOf("Файл не найден — патч не применится"),
                hunks = emptyList()
            )
        val currentLines = current.split('\n')
        val notes = mutableListOf<String>()
        val hunks = mutableListOf<PreviewHunk>()

        for ((i, pair) in op.replacements.withIndex()) {
            val (oldText, newText) = pair
            val found = findBlock(currentLines, oldText)
            if (found == null) {
                notes.add("replacements[$i]: фрагмент old не найден — этот патч пропустится")
                continue
            }
            val (startIdx, count) = found
            val ctxStart = (startIdx - CONTEXT_LINES).coerceAtLeast(0)
            val ctxEnd = (startIdx + count + CONTEXT_LINES).coerceAtMost(currentLines.size)
            val newBlock = newText.split('\n')

            val lines = mutableListOf<PreviewLine>()
            for (j in ctxStart until startIdx) {
                lines.add(PreviewLine(' ', j + 1, j + 1, currentLines[j]))
            }
            oldText.split('\n').forEachIndexed { k, line ->
                lines.add(PreviewLine('-', startIdx + k + 1, null, line))
            }
            newBlock.forEachIndexed { k, line ->
                lines.add(PreviewLine('+', null, startIdx + k + 1, line))
            }
            val shift = newBlock.size - count
            for (j in (startIdx + count) until ctxEnd) {
                lines.add(PreviewLine(' ', j + 1, j + 1 + shift, currentLines[j]))
            }

            val trimmed = if (lines.size > MAX_LINES_PER_HUNK) {
                val head = lines.take(MAX_LINES_PER_HUNK).toMutableList()
                head.add(PreviewLine(' ', null, null, "… (${lines.size - MAX_LINES_PER_HUNK} строк скрыто)"))
                head
            } else lines
            hunks.add(PreviewHunk("@@ replacements[$i] @@", trimmed))
            if (hunks.size >= MAX_HUNKS) {
                notes.add("Показаны первые $MAX_HUNKS правок")
                break
            }
        }
        return PatchPreview(op.path, exists = true, notes = notes, hunks = hunks)
    }

    private fun buildForWrite(ctx: Context, op: FsWriteOp.Write): PatchPreview {
        val current = ProjectFs.readRawContent(ctx, op.path)
        val newLines = op.content.replace("\r\n", "\n").split('\n')
        val notes = mutableListOf<String>()
        val hunks = mutableListOf<PreviewHunk>()

        if (current == null) {
            notes.add("Новый файл — будет создан")
            val lines = newLines.take(MAX_LINES_PER_HUNK).mapIndexed { i, line ->
                PreviewLine('+', null, i + 1, line)
            }.toMutableList()
            if (newLines.size > MAX_LINES_PER_HUNK) {
                lines.add(PreviewLine(' ', null, null, "… (${newLines.size - MAX_LINES_PER_HUNK} строк скрыто)"))
            }
            hunks.add(PreviewHunk("@@ содержимое (${newLines.size} строк) @@", lines))
        } else {
            notes.add("Полная перезапись файла")
            val oldLines = current.split('\n')
            val lines = mutableListOf<PreviewLine>()
            oldLines.take(MAX_LINES_PER_HUNK).forEachIndexed { i, line ->
                lines.add(PreviewLine('-', i + 1, null, line))
            }
            newLines.take(MAX_LINES_PER_HUNK).forEachIndexed { i, line ->
                lines.add(PreviewLine('+', null, i + 1, line))
            }
            if (oldLines.size > MAX_LINES_PER_HUNK || newLines.size > MAX_LINES_PER_HUNK) {
                lines.add(PreviewLine(' ', null, null, "… (полные файлы длиннее показанного)"))
            }
            hunks.add(PreviewHunk("@@ ${oldLines.size} → ${newLines.size} строк @@", lines))
        }
        return PatchPreview(op.path, exists = current != null, notes = notes, hunks = hunks)
    }

    /** Находит первое вхождение oldText в файле. Возвращает (индекс первой строки, число строк). */
    private fun findBlock(fileLines: List<String>, oldText: String): Pair<Int, Int>? {
        val oldLines = oldText.replace("\r\n", "\n").split('\n')
        if (oldLines.isEmpty()) return null
        var i = 0
        val limit = fileLines.size - oldLines.size
        while (i <= limit) {
            var match = true
            for (j in oldLines.indices) {
                if (fileLines[i + j] != oldLines[j]) {
                    match = false
                    break
                }
            }
            if (match) return i to oldLines.size
            i++
        }
        return null
    }
}