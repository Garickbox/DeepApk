package com.garickbox.deepapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for SAF tag parsers in [ProjectFs].
 * No Context / no Robolectric — runs in CI and locally.
 *
 * Ampl: all SAF literals written with "\u003C" escape instead of a
 * bare "<" sign — the Kotlin compiler expands them to real tags.
 */
class ProjectFsParserTest {

    // ================================================================
    //  extractWriteOps
    // ================================================================

    @Test
    fun fs_patch_single_pair() {
        val t = "\u003Cfs_patch path=\"a.kt\">\u003Cold>foo\u003C/old>\u003Cnew>bar\u003C/new>\u003C/fs_patch>"
        val ops = ProjectFs.extractWriteOps(t)
        assertEquals(1, ops.size)
        val p = ops[0] as FsWriteOp.Patch
        assertEquals("a.kt", p.path)
        assertEquals(1, p.replacements.size)
        assertEquals("foo" to "bar", p.replacements[0])
    }

    @Test
    fun fs_patch_two_pairs() {
        val t = "\u003Cfs_patch path=\"b.kt\">\u003Cold>aaa\u003C/old>\u003Cnew>AAA\u003C/new>\u003Cold>bbb\u003C/old>\u003Cnew>BBB\u003C/new>\u003C/fs_patch>"
        val ops = ProjectFs.extractWriteOps(t)
        assertEquals(1, ops.size)
        val p = ops[0] as FsWriteOp.Patch
        assertEquals(2, p.replacements.size)
        assertEquals("aaa" to "AAA", p.replacements[0])
        assertEquals("bbb" to "BBB", p.replacements[1])
    }

    @Test
    fun fs_patch_multiline_trimmed_edges() {
        val t = "\u003Cfs_patch path=\"m.kt\">\u003Cold>\nline1\nline2\n\u003C/old>\u003Cnew>\nx\n\u003C/new>\u003C/fs_patch>"
        val p = ProjectFs.extractWriteOps(t)[0] as FsWriteOp.Patch
        assertEquals("line1\nline2", p.replacements[0].first)
        assertEquals("x", p.replacements[0].second)
    }

    @Test
    fun two_fs_patch_sorted_by_position() {
        val t = "\u003Cfs_patch path=\"a\">\u003Cold>x\u003C/old>\u003Cnew>y\u003C/new>\u003C/fs_patch> \u003Cfs_patch path=\"b\">\u003Cold>u\u003C/old>\u003Cnew>v\u003C/new>\u003C/fs_patch>"
        val ops = ProjectFs.extractWriteOps(t)
        assertEquals(2, ops.size)
        assertEquals("a", (ops[0] as FsWriteOp.Patch).path)
        assertEquals("b", (ops[1] as FsWriteOp.Patch).path)
    }

    @Test
    fun patch_and_write_ordered() {
        val t = "\u003Cfs_patch path=\"a\">\u003Cold>x\u003C/old>\u003Cnew>y\u003C/new>\u003C/fs_patch> \u003Cfs_write path=\"b\">hi\u003C/fs_write>"
        val ops = ProjectFs.extractWriteOps(t)
        assertEquals(2, ops.size)
        assertTrue(ops[0] is FsWriteOp.Patch)
        assertTrue(ops[1] is FsWriteOp.Write)
    }

    @Test
    fun uppercase_outer_tag_matched_ignorecase() {
        val t = "\u003CFS_PATCH path=\"a\">\u003Cold>x\u003C/old>\u003Cnew>y\u003C/new>\u003C/FS_PATCH>"
        val ops = ProjectFs.extractWriteOps(t)
        assertEquals(1, ops.size)
        assertTrue(ops[0] is FsWriteOp.Patch)
    }

    @Test
    fun empty_path_skipped() {
        val t = "\u003Cfs_patch path=\"\">\u003Cold>x\u003C/old>\u003Cnew>y\u003C/new>\u003C/fs_patch>"
        assertEquals(0, ProjectFs.extractWriteOps(t).size)
    }

    @Test
    fun patch_without_new_skipped() {
        val t = "\u003Cfs_patch path=\"a\">\u003Cold>x\u003C/old>\u003C/fs_patch>"
        assertEquals(0, ProjectFs.extractWriteOps(t).size)
    }

    @Test
    fun fs_write_empty_body() {
        val t = "\u003Cfs_write path=\"x.kt\">\u003C/fs_write>"
        val ops = ProjectFs.extractWriteOps(t)
        assertEquals(1, ops.size)
        val w = ops[0] as FsWriteOp.Write
        assertEquals("x.kt", w.path)
        assertEquals("", w.content)
    }

    @Test
    fun fs_write_internal_newlines_preserved() {
        val t = "\u003Cfs_write path=\"x.kt\">line1\\nline2\u003C/fs_write>"
        val w = ProjectFs.extractWriteOps(t)[0] as FsWriteOp.Write
        assertEquals("line1\\nline2", w.content)
    }

    @Test
    fun fs_write_inner_spaces_kept() {
        val t = "\u003Cfs_write path=\"x.kt\">\n\n  content  \n\n\u003C/fs_write>"
        val w = ProjectFs.extractWriteOps(t)[0] as FsWriteOp.Write
        assertEquals("  content  ", w.content)
    }

    @Test
    fun attr_order_irrelevant() {
        val t = "\u003Cfs_write id=\"1\" path=\"x.kt\">hi\u003C/fs_write>"
        val ops = ProjectFs.extractWriteOps(t)
        assertEquals("x.kt", (ops[0] as FsWriteOp.Write).path)
    }

    // ================================================================
    //  extractReadOps
    // ================================================================

    @Test
    fun tree_self_closing_default_path() {
        val ops = ProjectFs.extractReadOps("\u003Cfs_tree/>")
        assertEquals(1, ops.size)
        assertEquals("", (ops[0] as FsReadOp.Tree).path)
    }

    @Test
    fun tree_with_path() {
        val ops = ProjectFs.extractReadOps("\u003Cfs_tree path=\"app/src/main\"/>")
        assertEquals("app/src/main", (ops[0] as FsReadOp.Tree).path)
    }

    @Test
    fun grep_pattern_and_path() {
        val ops = ProjectFs.extractReadOps("\u003Cfs_grep pattern=\"CURRENT_VER\" path=\"app/src/main\"/>")
        assertEquals(1, ops.size)
        val g = ops[0] as FsReadOp.Grep
        assertEquals("CURRENT_VER", g.pattern)
        assertEquals("app/src/main", g.path)
    }

    @Test
    fun grep_without_pattern_skipped() {
        val ops = ProjectFs.extractReadOps("\u003Cfs_grep path=\"app/src/main\"/>")
        assertEquals(0, ops.size)
    }

    @Test
    fun read_explicit_start_end() {
        val ops = ProjectFs.extractReadOps("\u003Cfs_read path=\"x.kt\" start=\"10\" end=\"20\"/>")
        val r = ops[0] as FsReadOp.Read
        assertEquals("x.kt", r.path)
        assertEquals(10, r.start)
        assertEquals(20, r.end)
    }

    @Test
    fun read_defaults_1_to_150() {
        val ops = ProjectFs.extractReadOps("\u003Cfs_read path=\"x.kt\"/>")
        val r = ops[0] as FsReadOp.Read
        assertEquals(1, r.start)
        assertEquals(150, r.end)
    }

    @Test
    fun cap_at_4_read_ops() {
        val t = "\u003Cfs_read path=\"a\"/>\u003Cfs_read path=\"b\"/>\u003Cfs_read path=\"c\"/>\u003Cfs_read path=\"d\"/>\u003Cfs_read path=\"e\"/>"
        val ops = ProjectFs.extractReadOps(t)
        assertEquals(4, ops.size)
        assertEquals(listOf("a", "b", "c", "d"), ops.map { (it as FsReadOp.Read).path })
    }

    // ================================================================
    //  stripReadTags
    // ================================================================

    @Test
    fun strip_removes_all_tags_keeps_text_and_summary() {
        val t = "Hello \u003Cfs_tree/> world \u003Cfs_grep pattern=\"x\" path=\"y\"/> \u003Cfs_read path=\"z.kt\"/>"
        val out = ProjectFs.stripReadTags(t, "test summary")
        assertTrue("Hello", out.contains("Hello"))
        assertTrue("world", out.contains("world"))
        assertFalse(out.contains("\u003Cfs_"))
        assertFalse(out.contains("fs_tree"))
        assertTrue(out.endsWith("> \uD83D\uDD0D Запрос к проекту: test summary"))
    }

    @Test
    fun strip_only_tags_returns_summary_alone() {
        val t = "\u003Cfs_tree/>\u003Cfs_tree path=\"a\"/>\u003Cfs_grep pattern=\"x\" path=\"y\"/>"
        val out = ProjectFs.stripReadTags(t, "tree")
        assertEquals("> \uD83D\uDD0D Запрос к проекту: tree", out)
    }
}
