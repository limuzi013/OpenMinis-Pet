package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DSH messageFeedback compare-and-set semantics (P0-5).
 *
 * The bundled ui-message-feedback controller sends `ifVersion` from its last
 * observed row and reconciles `version-conflict` with the server's `current`,
 * so versions must be real CAS tokens (not a hard-coded `"1"`), and the DSH
 * item shape must match `messageFeedback_item$schema` exactly.
 */
class MessageFeedbackCasTest {

    private fun feedback(messageId: String, kind: String, version: String, sessionId: String = "s1") =
        MessageFeedbackStore.Feedback(kind = kind, version = version, sessionId = sessionId)

    @Test
    fun `put without ifVersion creates an item and returns a new version`() {
        val all = mutableMapOf<String, MessageFeedbackStore.Feedback>()
        val result = MessageFeedbackStore.casPut(all, "s1", "m1", "positive", "很好", null)
        assertTrue(result is MessageFeedbackStore.DshResult.Ok)
        val item = (result as MessageFeedbackStore.DshResult.Ok).item
        assertEquals("m1", item.getString("messageId"))
        assertEquals("positive", item.getString("rating"))
        assertEquals("很好", item.getString("note"))
        assertTrue(item.has("version"))
        assertTrue(item.getLong("createdAt") > 0)
        assertTrue(item.getLong("updatedAt") > 0)
        // The stored version matches the returned CAS token.
        assertEquals(item.getString("version"), all["m1"]?.version)
    }

    @Test
    fun `put with matching ifVersion succeeds`() {
        val all = mutableMapOf("m1" to feedback("m1", "up", "v1", "s1"))
        val result = MessageFeedbackStore.casPut(all, "s1", "m1", "negative", "不对", "v1")
        assertTrue(result is MessageFeedbackStore.DshResult.Ok)
        val item = (result as MessageFeedbackStore.DshResult.Ok).item
        assertEquals("negative", item.getString("rating"))
        assertFalse(item.getString("version") == "v1") // version rotates on mutation
    }

    @Test
    fun `put with stale ifVersion returns version-conflict with current`() {
        val all = mutableMapOf("m1" to feedback("m1", "up", "v2", "s1"))
        val result = MessageFeedbackStore.casPut(all, "s1", "m1", "negative", null, "v1")
        assertTrue(result is MessageFeedbackStore.DshResult.Err)
        val err = result as MessageFeedbackStore.DshResult.Err
        assertEquals("version-conflict", err.code)
        assertEquals("v2", err.payload.getJSONObject("current").getString("version"))
    }

    @Test
    fun `put with ifVersion and no existing item returns version-conflict with null current`() {
        val all = mutableMapOf<String, MessageFeedbackStore.Feedback>()
        val result = MessageFeedbackStore.casPut(all, "s1", "m1", "positive", null, "v9")
        assertTrue(result is MessageFeedbackStore.DshResult.Err)
        assertEquals("version-conflict", (result as MessageFeedbackStore.DshResult.Err).code)
        assertTrue(result.payload.isNull("current"))
    }

    @Test
    fun `blank note returns note-blank`() {
        val all = mutableMapOf<String, MessageFeedbackStore.Feedback>()
        val result = MessageFeedbackStore.casPut(all, "s1", "m1", "positive", "   ", null)
        assertTrue(result is MessageFeedbackStore.DshResult.Err)
        assertEquals("note-blank", (result as MessageFeedbackStore.DshResult.Err).code)
    }

    @Test
    fun `invalid rating returns bad-request`() {
        val all = mutableMapOf<String, MessageFeedbackStore.Feedback>()
        val result = MessageFeedbackStore.casPut(all, "s1", "m1", "awesome", null, null)
        assertTrue(result is MessageFeedbackStore.DshResult.Err)
        assertEquals("bad-request", (result as MessageFeedbackStore.DshResult.Err).code)
    }

    @Test
    fun `delete with stale ifVersion returns version-conflict`() {
        val all = mutableMapOf("m1" to feedback("m1", "down", "v7", "s1"))
        val result = MessageFeedbackStore.casDelete(all, "m1", "v6")
        assertTrue(result is MessageFeedbackStore.DshResult.Err)
        assertEquals("version-conflict", (result as MessageFeedbackStore.DshResult.Err).code)
        assertTrue(all.containsKey("m1"))
    }

    @Test
    fun `delete with matching version succeeds and reports absent`() {
        val all = mutableMapOf("m1" to feedback("m1", "up", "v7", "s1"))
        val result = MessageFeedbackStore.casDelete(all, "m1", "v7")
        assertTrue(result is MessageFeedbackStore.DshResult.Ok)
        assertEquals(true, (result as MessageFeedbackStore.DshResult.Ok).item.getBoolean("absent"))
        assertFalse(all.containsKey("m1"))
    }

    @Test
    fun `up and down map to positive and negative`() {
        assertEquals("positive", feedback("m", "up", "v").rating)
        assertEquals("negative", feedback("m", "down", "v").rating)
    }

    @Test
    fun `dsh item keeps the exact DSH field set`() {
        val item = MessageFeedbackStore.dshItem(
            "m1",
            feedback("m1", "up", "v3", "s1"),
        )
        // Schema-required keys.
        assertTrue(item.has("messageId"))
        assertTrue(item.has("rating"))
        assertTrue(item.has("version"))
        assertTrue(item.has("createdAt"))
        assertTrue(item.has("updatedAt"))
        // sessionId is NOT part of the DSH item schema.
        assertFalse(item.has("sessionId"))
        // note only exists when non-empty.
        assertFalse(item.has("note"))
    }
}
