package com.garickbox.deepapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test fun empty() {
        assertTrue(parseMarkdownBlocks("").isEmpty())
    }

    @Test fun paragraph() {
        val r = parseMarkdownBlocks("hello world")
        assertEquals(1, r.size)
        assertTrue(r[0] is MdBlock.Paragraph)
        assertEquals("hello world", (r[0] as MdBlock.Paragraph).text)
    }

    @Test fun heading_levels() {
        val r = parseMarkdownBlocks("# H1\n\n## H2\n\n### H3")
        assertEquals(3, r.size)
        assertEquals(1, (r[0] as MdBlock.Heading).level)
        assertEquals("H1", (r[0] as MdBlock.Heading).text)
        assertEquals(2, (r[1] as MdBlock.Heading).level)
        assertEquals(3, (r[2] as MdBlock.Heading).level)
    }

    @Test fun quote() {
        val r = parseMarkdownBlocks("> line1\n> line2")
        assertEquals(1, r.size)
        assertTrue(r[0] is MdBlock.Quote)
    }

    @Test fun bullet_dash() {
        val r = parseMarkdownBlocks("- item1\n- item2")
        assertEquals(2, r.size)
        assertTrue(r[0] is MdBlock.Bullet)
        assertEquals("item1", (r[0] as MdBlock.Bullet).text)
    }

    @Test fun ordered_list() {
        val r = parseMarkdownBlocks("1. first\n2. second")
        assertEquals(2, r.size)
        assertEquals("1.", (r[0] as MdBlock.Bullet).marker)
        assertEquals("first", (r[0] as MdBlock.Bullet).text)
    }

    @Test fun divider() {
        val r = parseMarkdownBlocks("---")
        assertEquals(1, r.size)
        assertTrue(r[0] is MdBlock.Divider)
    }

    @Test fun code_block_with_lang() {
        val r = parseMarkdownBlocks("```bash\necho hi\n```")
        assertEquals(1, r.size)
        assertTrue(r[0] is MdBlock.Code)
        assertEquals("bash", (r[0] as MdBlock.Code).lang)
        assertEquals("echo hi", (r[0] as MdBlock.Code).code)
    }

    @Test fun code_block_no_lang() {
        val r = parseMarkdownBlocks("```\nplain\n```")
        assertEquals(1, r.size)
        assertEquals("", (r[0] as MdBlock.Code).lang)
        assertEquals("plain", (r[0] as MdBlock.Code).code)
    }

    @Test fun unclosed_fence_at_end() {
        val r = parseMarkdownBlocks("```\nnever closed")
        assertEquals(1, r.size)
        assertTrue(r[0] is MdBlock.Code)
        assertEquals("never closed", (r[0] as MdBlock.Code).code)
    }

    @Test fun four_backticks_inside_three() {
        val r = parseMarkdownBlocks("````\nhas triple\n````")
        assertEquals(1, r.size)
        assertTrue(r[0] is MdBlock.Code)
    }

    @Test fun tilde_fence() {
        val r = parseMarkdownBlocks("~~~\ncode here\n~~~")
        assertEquals(1, r.size)
        assertTrue(r[0] is MdBlock.Code)
        assertEquals("code here", (r[0] as MdBlock.Code).code)
    }

    @Test fun table_simple() {
        val r = parseMarkdownBlocks("| A | B |\n|---|---|\n| 1 | 2 |")
        assertEquals(1, r.size)
        val t = r[0] as MdBlock.Table
        assertEquals(listOf("A", "B"), t.headers)
        assertEquals(1, t.rows.size)
        assertEquals(listOf("1", "2"), t.rows[0])
    }

    @Test fun mixed_doc() {
        val r = parseMarkdownBlocks("# Заголовок\n\nТекст\n\n- пункт")
        assertTrue(r.size >= 3)
        assertTrue(r[0] is MdBlock.Heading)
        assertTrue(r[1] is MdBlock.Paragraph)
        assertTrue(r[2] is MdBlock.Bullet)
    }
}