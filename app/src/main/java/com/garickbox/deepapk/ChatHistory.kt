package com.garickbox.deepapk

internal const val MAX_HISTORY_CHARS = 150_000

internal fun capHistory(msgs: List<Message>, maxChars: Int = MAX_HISTORY_CHARS): List<Message> {
    if (msgs.isEmpty()) return msgs
    val sizes = msgs.map { it.content.length }
    val total = sizes.sum()
    if (total <= maxChars) return msgs
    val kept = mutableListOf<Int>()
    var acc = 0
    for (i in msgs.indices.reversed()) {
        val sz = sizes[i]
        if (kept.isNotEmpty() && acc + sz > maxChars) break
        kept.add(0, i)
        acc += sz
    }
    if (kept.isNotEmpty() && kept.first() > 0) {
        val firstSz = sizes[0]
        if (acc + firstSz <= maxChars) {
            kept.add(0, 0)
            acc += firstSz
        }
    }
    android.util.Log.w("DeepApk", "history capped: ${msgs.size} -> ${kept.size} msgs, chars $total -> $acc")
    return kept.map { msgs[it] }
}
