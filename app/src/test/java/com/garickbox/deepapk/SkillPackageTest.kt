package com.garickbox.deepapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPackageTest {

    @Test fun fmt_v1_no_ext() = assertTrue(skillFormatMatches(mapOf("format" to "deepapk-skill-v1"), false)).let { }
    @Test fun fmt_v1_with_ext() = assertTrue(skillFormatMatches(mapOf("format" to "deepapk-skill-v1"), true)).let { }
    @Test fun fmt_ext_with_prompt() = assertTrue(skillFormatMatches(mapOf("prompt" to "x"), true)).let { }
    @Test fun fmt_ext_no_prompt() = assertFalse(skillFormatMatches(mapOf("foo" to "x"), true)).let { }
    @Test fun fmt_not_ext_with_prompt() = assertFalse(skillFormatMatches(mapOf("prompt" to "x"), false)).let { }
    @Test fun fmt_empty() = assertFalse(skillFormatMatches(emptyMap(), false)).let { }
    @Test fun fmt_v1_wins_even_without_prompt() = assertTrue(skillFormatMatches(mapOf("format" to "deepapk-skill-v1"), true)).let { }

    @Test fun fields_full() {
        val p = parseSkillFromFields(
            mapOf(
                "prompt" to "  hello world  ",
                "id" to "my-id_1",
                "name" to "My Name",
                "emoji" to "🎯",
                "learnedSkill" to "  rule  ",
                "version" to "3"
            ),
            "s.deepapk"
        )!!
        assertEquals("my_id_1", p.id)
        assertEquals("My Name", p.name)
        assertEquals("🎯", p.emoji)
        assertEquals("hello world", p.prompt)
        assertEquals("rule", p.learnedSkill)
        assertEquals(3, p.version)
    }

    @Test fun fields_no_prompt() = assertNull(parseSkillFromFields(emptyMap(), "s.deepapk")).let { }
    @Test fun fields_blank_prompt() = assertNull(parseSkillFromFields(mapOf("prompt" to "   "), "s.deepapk")).let { }

    @Test fun fields_id_from_filename() {
        val p = parseSkillFromFields(mapOf("prompt" to "x"), "happy.deepapk")!!
        assertEquals("happy", p.id)
        assertEquals("happy", p.name)
    }

    @Test fun fields_default_emoji_and_version() {
        val p = parseSkillFromFields(mapOf("prompt" to "x"), "s.deepapk")!!
        assertEquals("🛠", p.emoji)
        assertEquals(1, p.version)
    }

    @Test fun fields_id_sanitize() {
        val p = parseSkillFromFields(mapOf("prompt" to "x", "id" to "Hello World!!!"), "s.deepapk")!!
        assertEquals("hello_world", p.id)
    }

    @Test fun fields_emoji_take_4() {
        val p = parseSkillFromFields(mapOf("prompt" to "x", "emoji" to "abcdefgh"), "s.deepapk")!!
        assertEquals("abcd", p.emoji)
    }

    @Test fun deepapk_body_ok() {
        val p = parseSkillFromDeepapkBody("hello", "my.deepapk")!!
        assertEquals("my", p.id)
        assertEquals("my", p.name)
        assertEquals("⭐", p.emoji)
        assertEquals("hello", p.prompt)
        assertEquals(1, p.version)
    }

    @Test fun deepapk_body_wrong_ext() = assertNull(parseSkillFromDeepapkBody("hello", "my.txt")).let { }

    @Test fun deepapk_body_no_filename() {
        val p = parseSkillFromDeepapkBody("x", ".deepapk")!!
        assertTrue(p.id.startsWith("custom_"))
        assertEquals("Новый скилл", p.name)
    }

    @Test fun deepapk_body_trims() {
        val p = parseSkillFromDeepapkBody("  content  ", "s.deepapk")!!
        assertEquals("content", p.prompt)
    }
}