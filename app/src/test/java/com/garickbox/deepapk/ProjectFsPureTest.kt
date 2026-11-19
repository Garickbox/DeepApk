package com.garickbox.deepapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFsPureTest {

    // ==================== sanitizeRelPath ====================

    @Test fun sanitize_normal_path_ok() {
        assertEquals("app/src/main/Foo.kt", ProjectFs.sanitizeRelPath("app/src/main/Foo.kt"))
    }

    @Test fun sanitize_leading_slash_stripped() {
        assertEquals("app/src/main/Foo.kt", ProjectFs.sanitizeRelPath("/app/src/main/Foo.kt"))
    }

    @Test fun sanitize_backslash_normalized() {
        assertEquals("app/src/main/Foo.kt", ProjectFs.sanitizeRelPath("app\\src\\main\\Foo.kt"))
    }

    @Test fun sanitize_dot_segments_removed() {
        assertEquals("app/main", ProjectFs.sanitizeRelPath("./app/./main"))
    }

    @Test fun sanitize_double_slash_collapsed() {
        assertEquals("a/b", ProjectFs.sanitizeRelPath("a//b"))
    }

    @Test fun sanitize_trailing_slash_removed() {
        assertEquals("app/main", ProjectFs.sanitizeRelPath("app/main/"))
    }

    @Test fun sanitize_empty_returns_empty() {
        assertEquals("", ProjectFs.sanitizeRelPath(""))
    }

    @Test fun sanitize_whitespace_returns_empty() {
        assertEquals("", ProjectFs.sanitizeRelPath("   "))
    }

    @Test fun sanitize_dot_only_returns_empty() {
        assertEquals("", ProjectFs.sanitizeRelPath("."))
    }

    @Test fun sanitize_parent_traversal_null() {
        assertNull(ProjectFs.sanitizeRelPath("../etc/passwd"))
    }

    @Test fun sanitize_parent_in_middle_null() {
        assertNull(ProjectFs.sanitizeRelPath("app/../../../etc"))
    }

    @Test fun sanitize_nul_byte_null() {
        assertNull(ProjectFs.sanitizeRelPath("app/a\u0000b"))
    }

    @Test fun sanitize_triple_dot_ok() {
        assertEquals("...", ProjectFs.sanitizeRelPath("..."))
    }

    // ==================== parseAttrs ====================

    @Test fun attrs_double_quotes() {
        val m = ProjectFs.parseAttrs("path=\"app/Foo.kt\" start=\"1\" end=\"100\"")
        assertEquals("app/Foo.kt", m["path"])
        assertEquals("1", m["start"])
        assertEquals("100", m["end"])
    }

    @Test fun attrs_single_quotes() {
        val m = ProjectFs.parseAttrs("path='x.kt'")
        assertEquals("x.kt", m["path"])
    }

    @Test fun attrs_empty_value() {
        val m = ProjectFs.parseAttrs("pattern=\"\"")
        assertEquals("", m["pattern"])
    }

    @Test fun attrs_keys_lowercased() {
        val m = ProjectFs.parseAttrs("PATH=\"x.kt\"")
        assertEquals("x.kt", m["path"])
    }

    @Test fun attrs_empty_returns_empty_map() {
        assertTrue(ProjectFs.parseAttrs("").isEmpty())
    }

    // ==================== countOccurrences ====================

    @Test fun count_basic_one() {
        assertEquals(1, ProjectFs.countOccurrences("hello world", "world"))
    }

    @Test fun count_non_overlapping() {
        assertEquals(2, ProjectFs.countOccurrences("aaaa", "aa"))
    }

    @Test fun count_empty_needle_zero() {
        assertEquals(0, ProjectFs.countOccurrences("hello", ""))
    }

    @Test fun count_not_found_zero() {
        assertEquals(0, ProjectFs.countOccurrences("hello", "xxx"))
    }

    @Test fun count_empty_haystack_zero() {
        assertEquals(0, ProjectFs.countOccurrences("", "x"))
    }

    // ==================== isTextFile ====================

    @Test fun is_text_kt_ok() {
        assertTrue(ProjectFs.isTextFile("Foo.kt", 1000L))
    }

    @Test fun is_text_md_ok() {
        assertTrue(ProjectFs.isTextFile("README.md", 5000L))
    }

    @Test fun is_text_too_big_false() {
        assertFalse(ProjectFs.isTextFile("huge.kt", 400_000L))
    }

    @Test fun is_text_just_under_limit_ok() {
        assertTrue(ProjectFs.isTextFile("ok.kt", 349_999L))
    }

    @Test fun is_text_no_ext_treated_as_text() {
        assertTrue(ProjectFs.isTextFile("Makefile", 100L))
    }
}
