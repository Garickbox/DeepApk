package com.garickbox.deepapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxBridgeTest {

    @Test fun isShellLang_all() {
        for (s in listOf("sh", "bash", "zsh", "shell", " BASH ")) assertTrue(s, TermuxBridge.isShellLang(s))
        assertFalse(TermuxBridge.isShellLang("kotlin"))
        assertFalse(TermuxBridge.isShellLang("python"))
        assertFalse(TermuxBridge.isShellLang(""))
    }

    @Test fun isPythonLang_all() {
        for (s in listOf("py", "python", "python3", " Python3 ")) assertTrue(s, TermuxBridge.isPythonLang(s))
        assertFalse(TermuxBridge.isPythonLang("kotlin"))
    }

    @Test fun isRunnableCodeBlock_basic() {
        assertTrue(TermuxBridge.isRunnableCodeBlock("bash", "echo hi"))
        assertTrue(TermuxBridge.isRunnableCodeBlock("py", "print(1)"))
        assertTrue(TermuxBridge.isRunnableCodeBlock("", "git status"))
        assertTrue(TermuxBridge.isRunnableCodeBlock("", "adb devices"))
        assertTrue(TermuxBridge.isRunnableCodeBlock("", "pkg install curl"))
        assertTrue(TermuxBridge.isRunnableCodeBlock("", "#!/bin/bash\necho hi"))
        assertTrue(TermuxBridge.isRunnableCodeBlock("", "{\n echo hi | tee log"))
        assertFalse(TermuxBridge.isRunnableCodeBlock("", "привет мир"))
        assertFalse(TermuxBridge.isRunnableCodeBlock("", ""))
        assertFalse(TermuxBridge.isRunnableCodeBlock("", "   \n  \n"))
        assertFalse(TermuxBridge.isRunnableCodeBlock("", "kotlin code"))
    }

    @Test fun detectInterpreter_all() {
        assertEquals("python3", TermuxBridge.detectInterpreter("py", ""))
        assertEquals("python3", TermuxBridge.detectInterpreter("python", "print(1)"))
        assertEquals("bash", TermuxBridge.detectInterpreter("sh", "ls"))
        assertEquals("bash", TermuxBridge.detectInterpreter("bash", ""))
        assertEquals("python3", TermuxBridge.detectInterpreter("", "#!/usr/bin/env python3\nx=1"))
        assertEquals("bash", TermuxBridge.detectInterpreter("", "#!/bin/bash\nls"))
        assertEquals("python3", TermuxBridge.detectInterpreter("", "def f():\n    pass"))
        assertEquals("bash", TermuxBridge.detectInterpreter("", "import sys\n| tee log"))
        assertEquals("bash", TermuxBridge.detectInterpreter("", "echo hi"))
    }

    @Test fun extractRunnable_onlyShellAndPython() {
        val text = "Вот скрипт:\n```bash\necho hi\n```\nещё kotlin:\n```kotlin\nval x = 1\n```"
        val blocks = TermuxBridge.extractRunnableCodeBlocks(text)
        assertEquals(1, blocks.size)
        assertEquals("bash", blocks[0].lang)
        assertEquals("echo hi", blocks[0].code)
    }

    @Test fun extractRunnable_bareCommand() {
        val blocks = TermuxBridge.extractRunnableCodeBlocks("```\ngit status\n```")
        assertEquals(1, blocks.size)
    }

    @Test fun extractRunnable_empty() {
        assertTrue(TermuxBridge.extractRunnableCodeBlocks("").isEmpty())
    }

    @Test fun escapeSingle_plain() {
        assertEquals("'abc'", TermuxBridge.escapeSingle("abc"))
        assertEquals("''", TermuxBridge.escapeSingle(""))
    }

    @Test fun escapeSingle_quotes() {
        assertEquals("'it'\\''s'", TermuxBridge.escapeSingle("it's"))
        assertEquals("'a'\\''b'\\''c'", TermuxBridge.escapeSingle("a'b'c"))
    }

    @Test fun escapeSingle_dollar() {
        assertEquals("'\$HOME'", TermuxBridge.escapeSingle("\$HOME"))
    }
}