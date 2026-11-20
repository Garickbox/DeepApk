package com.garickbox.deepapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetStoreTest {

    @Test fun learned_empty() { assertEquals("", PresetStore.sanitizeLearned("")); assertEquals("", PresetStore.sanitizeLearned("   \n  ")) }
    @Test fun learned_short() {
        assertEquals("", PresetStore.sanitizeLearned("короткая"))
        assertEquals("", PresetStore.sanitizeLearned("12345678901234"))
        assertEquals("• 123456789012345", PresetStore.sanitizeLearned("123456789012345"))
    }
    @Test fun learned_headers() { assertEquals("", PresetStore.sanitizeLearned("# Заголовок")); assertEquals("", PresetStore.sanitizeLearned("## Второй")) }
    @Test fun learned_broken() { assertEquals("", PresetStore.sanitizeLearned("- ) обрывок длинный тут")) }
    @Test fun learned_norm() { assertEquals("• это правило длинное тут", PresetStore.sanitizeLearned("это правило длинное тут")) }
    @Test fun learned_norm_kept() {
        assertEquals("• уже правило длинное", PresetStore.sanitizeLearned("• уже правило длинное"))
    }
    @Test fun learned_dedup() {
        val r = PresetStore.sanitizeLearned("• правило длинное здесь\n• Правило длинное здесь")
        assertEquals(1, r.split("\n").size)
    }
    @Test fun learned_junk_screen() { assertEquals("", PresetStore.sanitizeLearned("правило -ChatScreen.kt- хвост")) }
    @Test fun learned_junk_ctx() { assertEquals("", PresetStore.sanitizeLearned("правило -ContextActions.kt- хвост")) }
    @Test fun learned_order() {
        val r = PresetStore.sanitizeLearned("## мусор\n• Правило один длинное тут\n\n• Правило два длинное тут\n")
        val lines = r.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("один"))
        assertTrue(lines[1].contains("два"))
    }
    @Test fun learned_8000() {
        val line = "• " + "x".repeat(200)
        val input = (1..50).joinToString("\n") { "$line$it" }
        val r = PresetStore.sanitizeLearned(input)
        assertTrue("len=${r.length}", r.length <= 8000)
    }
    @Test fun prompt_default_no_save() {
        val p = PromptPreset("default", "", "", "X")
        assertEquals("X", p.buildSystemPrompt())
    }
    @Test fun prompt_termux_full() {
        val p = PromptPreset("termux", "", "", "P", "L")
        val r = p.buildSystemPrompt("G")
        assertTrue(r.contains("P"))
        assertTrue(r.contains("Выученные правила пользователя:\nL"))
        assertTrue(r.contains("Дополнительно:\nG"))
        assertTrue(r.contains("<skill_save>"))
    }
    @Test fun prompt_termux_no_prompt() {
        val p = PromptPreset("termux", "", "", "", "L")
        val r = p.buildSystemPrompt()
        assertTrue(r.startsWith("Выученные"))
        assertTrue(r.contains("<skill_save>"))
    }
    @Test fun prompt_default_full_no_save() {
        val p = PromptPreset("default", "", "", "P", "L")
        val r = p.buildSystemPrompt("G")
        assertTrue(r.contains("P"))
        assertTrue(r.contains("L"))
        assertTrue(r.contains("G"))
        assertFalse(r.contains("<skill_save>"))
    }
    @Test fun prompt_custom_has_save() {
        val p = PromptPreset("custom", "", "", "P")
        assertTrue(p.buildSystemPrompt().contains("<skill_save>"))
    }
    @Test fun prompt_all_empty() {
        val p = PromptPreset("default", "", "", "")
        assertEquals("", p.buildSystemPrompt())
    }
}