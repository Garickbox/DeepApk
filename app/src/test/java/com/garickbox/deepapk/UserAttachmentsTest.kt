package com.garickbox.deepapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAttachmentsTest {

    @Test fun no_attachments_returns_raw() {
        val (f, t) = splitUserAttachments("hello world")
        assertTrue(f.isEmpty())
        assertEquals("hello world", t)
    }

    @Test fun single_attachment() {
        val input = "before\n[Файл: foo.kt]\n```\ncode here\n```\nafter"
        val (f, t) = splitUserAttachments(input)
        assertEquals(1, f.size)
        assertEquals("foo.kt", f[0].first)
        assertEquals("code here", f[0].second)
        assertTrue(t.contains("before"))
        assertTrue(t.contains("after"))
        assertFalse(t.contains("code here"))
    }

    @Test fun two_attachments() {
        val input = "[Файл: a.kt]\n```\nAAA\n```\n[Файл: b.kt]\n```\nBBB\n```"
        val (f, _) = splitUserAttachments(input)
        assertEquals(2, f.size)
        assertEquals("a.kt", f[0].first)
        assertEquals("b.kt", f[1].first)
        assertEquals("AAA", f[0].second)
        assertEquals("BBB", f[1].second)
    }

    @Test fun attachment_no_body_falls_back_to_text() {
        val input = "[Файл: orphan]"
        val (f, t) = splitUserAttachments(input)
        assertTrue(f.isEmpty())
        assertTrue(t.contains("[Файл: orphan]"))
    }

    @Test fun attachment_no_closing_fence_consumes_to_eof() {
        val input = "[Файл: x.kt]\n```\nsome body\nmore body"
        val (f, _) = splitUserAttachments(input)
        assertEquals(1, f.size)
        assertEquals("some body\nmore body", f[0].second)
    }

    @Test fun attachment_blank_name_defaults() {
        val input = "[Файл: ]\n```\nX\n```"
        val (f, _) = splitUserAttachments(input)
        assertEquals(1, f.size)
        assertEquals("файл", f[0].first)
    }

    @Test fun excessive_blank_lines_collapsed() {
        val input = "[Файл: a]\n```\nA\n```\n\n\n\ntail"
        val (_, t) = splitUserAttachments(input)
        assertFalse(t.contains("\n\n\n"))
        assertTrue(t.contains("tail"))
    }
}
