package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProbeParserTest {
    @Test
    fun `parses uid gid groups capabilities and SELinux`() {
        val output = """
            __ID__
            uid=0(root) gid=0(root) groups=0(root),1000(system),2000(shell)
            __CAPS__
            CapEff:	0000000000240000
            __CONTEXT__
            u:r:su:s0
            __MODE__
            Enforcing
        """.trimIndent()
        val parsed = RootProbeParser.parse(output, 0)
        assertTrue(parsed.authorized)
        assertEquals(0, parsed.effectiveUid)
        assertEquals(0, parsed.effectiveGid)
        assertEquals(listOf("0(root)", "1000(system)", "2000(shell)"), parsed.groups)
        assertEquals("0000000000240000", parsed.effectiveCapabilitiesHex)
        assertEquals("u:r:su:s0", parsed.selinuxContext)
        assertEquals("Enforcing", parsed.selinuxMode)
        assertTrue(parsed.hasCapability(18))
        assertTrue(parsed.hasCapability(21))
        assertFalse(parsed.hasCapability(19))
    }

    @Test
    fun `uid zero is not inferred from a successful exit`() {
        val parsed = RootProbeParser.parse("uid=2000(shell) gid=2000(shell)", 0)
        assertFalse(parsed.authorized)
        assertTrue(parsed.error!!.contains("not uid 0"))
    }

    @Test
    fun `denied su preserves the real stderr`() {
        val parsed = RootProbeParser.parse("", 1, "permission denied")
        assertFalse(parsed.authorized)
        assertEquals("permission denied", parsed.error)
    }

    @Test
    fun `capability parser rejects missing and out of range bits`() {
        assertFalse(LinuxCapabilityParser.hasBit(null, 0))
        assertFalse(LinuxCapabilityParser.hasBit("not-hex", 1))
        assertFalse(LinuxCapabilityParser.hasBit("ffffffffffffffff", 64))
    }
}
