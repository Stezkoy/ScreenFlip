package com.screenflip.app.swap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the PServer swap command building and result parsing.
 */
class PServerSwapEngineTest {

    @Test
    fun `top task command targets the requested display`() {
        val cmd0 = PServerSwapEngine.topTaskCommand(0)
        assertTrue(cmd0.contains("/Display #0/{d=0}"))
        assertTrue(cmd0.contains("/Display #[1-9]/{d=9}"))
        val cmd4 = PServerSwapEngine.topTaskCommand(4)
        assertTrue(cmd4.contains("/Display #0/{d=9}"))
        assertTrue(cmd4.contains("/Display #4/{d=0}"))
        assertTrue(cmd0.contains("dumpsys activity activities | awk"))
        assertTrue(cmd0.contains("/topResumed/"))
        assertTrue(cmd0.endsWith("print substr(${'$'}0,RSTART+3,RLENGTH-3);exit}'"))
    }

    @Test
    fun `top task command stays below the firmware command length limit`() {
        // The firmware shell truncates commands at ~256 chars (215 worked,
        // 315 failed in the one-shot diagnostics), so the probe must stay
        // well below that even for two-digit display ids.
        assertTrue(PServerSwapEngine.topTaskCommand(0).length < 215)
        for (id in 1..99) {
            val cmd = PServerSwapEngine.topTaskCommand(id)
            assertTrue("display $id is ${cmd.length} chars", cmd.length < 215)
        }
    }

    @Test
    fun `top task command has no command substitution`() {
        // The firmware shell does not support $( ... ); the command must be
        // a plain single-line pipeline.
        val cmd = PServerSwapEngine.topTaskCommand(4)
        assertFalse(cmd.contains("\$("))
        assertFalse(cmd.contains("backtick", ignoreCase = true))
        assertFalse(cmd.contains("\n"))
    }

    @Test
    fun `top task command uses bare dollar-zero for mawk compatibility`() {
        val cmd = PServerSwapEngine.topTaskCommand(4)
        // The firmware mawk rejects the backslash-escaped field reference, so
        // the command must contain a bare "$0" and never a backslash before it.
        assertTrue(cmd.contains("match(${'$'}0,/\\} t[0-9]+/)"))
        assertFalse(cmd.contains("\\${'$'}0"))
    }

    @Test
    fun `parse top task id valid`() {
        assertEquals("291", PServerSwapEngine.parseTopTaskId("291"))
        assertEquals("1234", PServerSwapEngine.parseTopTaskId(" 1234 "))
    }

    @Test
    fun `parse top task id empty and garbage`() {
        assertNull(PServerSwapEngine.parseTopTaskId(""))
        assertNull(PServerSwapEngine.parseTopTaskId("garbage"))
        assertNull(PServerSwapEngine.parseTopTaskId("29a"))
        assertNull(PServerSwapEngine.parseTopTaskId("1 2"))
    }
}
