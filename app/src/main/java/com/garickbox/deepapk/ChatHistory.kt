package com.garickbox.deepapk

internal const val MAX_HISTORY_CHARS = 500_000

internal fun capHistory(msgs: List<Message>, maxChars: Int = MAX_HISTORY_CHARS): List<Message> {
    if (msgs.isEmpty()) return msgs
    val sizes = msgs.map { it.content.length }
    val total = sizes.sum()
    if (total <= maxChars) return msgs
    if (msgs.size <= 2) return listOf(msgs.last())
    val minDrop = (maxChars / 4).coerceAtLeast(1)
    var cutIdx = 1
    var dropped = 0
    while (cutIdx < msgs.lastIndex && (total - dropped > maxChars || dropped < minDrop)) {
        dropped += sizes[cutIdx]
        cutIdx++
    }
    val out = mutableListOf<Message>()
    if (sizes[0] + (total - sizes[0] - dropped) <= maxChars) out.add(msgs[0])
    val skipped = cutIdx - if (out.isEmpty()) 0 else 1
    if (skipped > 0 && cutIdx < msgs.size) {
        out.add(Message(role = "user", content = "[...пропущено $skipped сообщений середины диалога...]"))
    }
    for (i in cutIdx until msgs.size) out.add(msgs[i])
    return out
}
