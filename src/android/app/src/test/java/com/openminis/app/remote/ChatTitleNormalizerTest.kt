package com.openminis.app.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the title normalization that stops "null" leaking onto the wire. */
class ChatTitleNormalizerTest {

    @Test
    fun `null and JSONObject NULL normalize to empty`() {
        assertEquals("", ChatTitleNormalizer.normalize(null))
        assertEquals("", ChatTitleNormalizer.normalize(JSONObject.NULL))
    }

    @Test
    fun `strings are trimmed`() {
        assertEquals("会话 A", ChatTitleNormalizer.normalize("  会话 A  "))
        assertEquals("", ChatTitleNormalizer.normalize("   "))
    }

    @Test
    fun `non-string values become their toString`() {
        assertEquals("42", ChatTitleNormalizer.normalize(42))
    }

    @Test
    fun `hasContent only true for non-blank titles`() {
        assertTrue(ChatTitleNormalizer.hasContent("x"))
        assertFalse(ChatTitleNormalizer.hasContent(null))
        assertFalse(ChatTitleNormalizer.hasContent(JSONObject.NULL))
        assertFalse(ChatTitleNormalizer.hasContent(""))
    }
}
