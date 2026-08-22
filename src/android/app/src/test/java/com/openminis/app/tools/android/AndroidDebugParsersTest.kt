package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDebugParsersTest {
    @Test
    fun `parses Android process table`() {
        val rows = AndroidDebugParsers.parseProcesses("""
            PID NAME
            123 com.example.app
            124 com.example.app:worker
            bad row
        """.trimIndent())
        assertEquals(listOf(123, 124), rows.map { it.pid })
        assertEquals("com.example.app:worker", rows[1].name)
    }

    @Test
    fun `parses epoch logcat and bounded severity summary`() {
        val raw = """
            1724300000.125  123  123 I Example: ready
            1724300001.250  123  123 E AndroidRuntime: FATAL EXCEPTION: main
        """.trimIndent()
        val line = AndroidDebugParsers.parseLogLine(raw.lineSequence().first())
        assertEquals(1_724_300_000_125L, line.epochMillis)
        assertEquals("I", line.priority)
        val summary = AndroidDebugParsers.summarizeLogs(raw)
        assertEquals(2, summary.getInt("lineCount"))
        assertEquals(1, summary.getJSONObject("severityCounts").getInt("E"))
        assertEquals(1, summary.getJSONArray("crashCandidates").length())
    }

    @Test
    fun `parses meminfo summary only when total pss exists`() {
        val parsed = AndroidDebugParsers.parseMeminfo("""
            ** MEMINFO in pid 123 [com.example.app] **
            App Summary
              Java Heap: 1024
              Native Heap: 2048
              Code: 300
              Stack: 44
              Graphics: 55
              TOTAL PSS: 4096
        """.trimIndent())
        assertNotNull(parsed)
        assertEquals(4096L, parsed!!.getLong("totalPssKb"))
        assertEquals(1024L, parsed.getLong("javaHeapKb"))
        assertNull(AndroidDebugParsers.parseMeminfo("No process found"))
    }

    @Test
    fun `crash parser reports only source frames actually present`() {
        val crash = AndroidDebugParsers.parseCrash("""
            08-22 10:00:00.000  123  123 E AndroidRuntime: FATAL EXCEPTION: main
            08-22 10:00:00.001  123  123 E AndroidRuntime: Process: com.example.app, PID: 123
            08-22 10:00:00.002  123  123 E AndroidRuntime: java.lang.NullPointerException: missing user
            08-22 10:00:00.003  123  123 E AndroidRuntime:     at com.example.app.LoginViewModel.login(LoginViewModel.kt:183)
        """.trimIndent(), "com.example.app")
        assertNotNull(crash)
        assertEquals("java.lang.NullPointerException", crash!!.getString("exception"))
        assertEquals("main", crash.getString("thread"))
        assertEquals("LoginViewModel.kt", crash.getJSONObject("source").getString("file"))
        assertEquals(183, crash.getJSONObject("source").getInt("line"))

        val noLine = AndroidDebugParsers.parseCrash("FATAL EXCEPTION: main\njava.lang.IllegalStateException: bad")
        assertNotNull(noLine)
        assertFalse(noLine!!.has("source"))
    }

    @Test
    fun `package dump does not conflate invisibility with installation`() {
        assertNull(AndroidDebugParsers.parsePackageDump("Unable to find package", "com.missing"))
        val parsed = AndroidDebugParsers.parsePackageDump("""
            Package [com.example.app] (abc):
              codePath=/data/app/example
              versionCode=42 minSdk=26 targetSdk=35
              versionName=1.2
              flags=[ DEBUGGABLE HAS_CODE ]
        """.trimIndent(), "com.example.app")
        assertTrue(parsed!!.getBoolean("installed"))
        assertTrue(parsed.getBoolean("debuggable"))
        assertEquals(42L, parsed.getLong("versionCode"))
    }
}
