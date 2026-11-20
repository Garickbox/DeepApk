package com.garickbox.deepapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTest {

    private fun msg(role: String, content: String) = Message(role = role, content = content)

    @Test fun empty() {
        assertTrue(capHistory(emptyList()).isEmpty())
    }

    @Test fun under_limit_all() {
        val msgs = listOf(msg("user", "a"), msg("assistant", "b"))
        assertEquals(msgs, capHistory(msgs, maxChars = 100))
    }

    @Test fun exactly_limit_all() {
        val msgs = listOf(msg("user", "a".repeat(100)), msg("assistant", "b".repeat(100)))
        assertEquals(msgs, capHistory(msgs, maxChars = 200))
    }

    @Test fun two_msgs_over_limit_last_only() {
        val msgs = listOf(msg("user", "a".repeat(60)), msg("assistant", "b".repeat(60)))
        val r = capHistory(msgs, maxChars = 50)
        assertEquals(1, r.size)
        assertEquals("b".repeat(60), r[0].content)
    }

    @Test fun last_always_kept() {
        val msgs = (1..5).map { msg("user", "m$it".repeat(30)) }
        val r = capHistory(msgs, maxChars = 150)
        assertTrue(r.isNotEmpty())
        assertEquals(msgs.last().content, r.last().content)
    }


    @Test fun total_under_max_chars_returns_same() {
        val msgs = (1..3).map { msg("user", "x".repeat(50)) }
        assertEquals(msgs, capHistory(msgs, maxChars = 500))
    }

    @Test fun default_limit_constant() {
        assertEquals(500_000, MAX_HISTORY_CHARS)
    }
}