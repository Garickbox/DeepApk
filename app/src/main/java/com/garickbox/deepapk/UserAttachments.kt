package com.garickbox.deepapk

/**
 * Парсер [Файл: name] блоков в сообщениях пользователя.
 * Каждый блок — fence с >=3 бэктиками, тело внутри.
 * Возвращает (список name -> body, очищенный текст без блоков).
 */
internal fun splitUserAttachments(raw: String): Pair<List<Pair<String, String>>, String> {
    if (!raw.contains("[Файл: ")) return emptyList<Pair<String, String>>() to raw
    val lines = raw.replace("\r\n", "\n").split("\n")
    val files = mutableListOf<Pair<String, String>>()
    val textOut = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val ln = lines[i]
        if (ln.startsWith("[Файл: ") && ln.endsWith("]") && i + 1 < lines.size) {
            val next = lines[i + 1]
            val ticks = next.takeWhile { it == '`' }.length
            if (ticks >= 3) {
                val fName = ln.removePrefix("[Файл: ").removeSuffix("]").trim().ifBlank { "файл" }
                val closeFence = "`".repeat(ticks)
                val body = mutableListOf<String>()
                var j = i + 2
                while (j < lines.size) {
                    if (lines[j].trim() == closeFence) {
                        j++
                        break
                    }
                    body.add(lines[j])
                    j++
                }
                files.add(fName to body.joinToString("\n").trim())
                i = j
                continue
            }
        }
        textOut.add(ln)
        i++
    }
    val cleaned = textOut.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    return files to cleaned
}
