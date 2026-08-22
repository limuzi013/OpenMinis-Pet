package com.openminis.app.remote

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the pure thinking-effort catalog the DSH model picker consumes. */
class DshReasoningCatalogTest {

    @Test
    fun `effort ids are lowercase enum names`() {
        assertEquals("off", DshReasoningCatalog.effortId(ThinkingLevel.OFF))
        assertEquals("xhigh", DshReasoningCatalog.effortId(ThinkingLevel.XHIGH))
        assertEquals("ultra", DshReasoningCatalog.effortId("ULTRA"))
    }

    @Test
    fun `efforts run off-then-up-to-ceiling`() {
        val efforts = DshReasoningCatalog.effortsFor(ThinkingLevel.HIGH, true)
        assertEquals(
            listOf(
                ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH,
            ),
            efforts,
        )
    }

    @Test
    fun `non-reasoning model gets no efforts`() {
        assertTrue(DshReasoningCatalog.effortsFor(ThinkingLevel.MAX, false).isEmpty())
        assertNull(DshReasoningCatalog.reasoningBlock(false, ThinkingLevel.MAX))
    }

    @Test
    fun `unknown supportsReasoning is treated conservatively as capable`() {
        val efforts = DshReasoningCatalog.effortsFor(ThinkingLevel.XHIGH, null)
        assertEquals(ThinkingLevel.XHIGH, efforts.last())
    }

    @Test
    fun `reasoning block matches the DSH wire shape`() {
        val block = DshReasoningCatalog.reasoningBlock(true, ThinkingLevel.MAX)!!
        assertTrue(block.has("efforts"))
        assertTrue(block.has("defaultEffort"))
        assertEquals("off", block.getString("defaultEffort"))
        val efforts = block.getJSONArray("efforts")
        assertTrue(efforts.length() >= 2)
        assertEquals("off", efforts.getJSONObject(0).getString("id"))
        assertEquals("Off", efforts.getJSONObject(0).getString("name"))
        val last = efforts.getJSONObject(efforts.length() - 1)
        assertEquals("max", last.getString("id"))
    }

    @Test
    fun `clamped efforts exclude tiers above the ceiling`() {
        val block = DshReasoningCatalog.reasoningBlock(true, ThinkingLevel.HIGH)!!
        val ids = (0 until block.getJSONArray("efforts").length())
            .map { block.getJSONArray("efforts").getJSONObject(it).getString("id") }
        assertFalse(ids.contains("max"))
        assertFalse(ids.contains("xhigh"))
        assertTrue(ids.contains("high"))
    }
}
