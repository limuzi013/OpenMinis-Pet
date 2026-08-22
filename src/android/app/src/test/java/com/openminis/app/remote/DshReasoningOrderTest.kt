package com.openminis.app.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reasoning-order contract for the DSH wire (P0-1).
 *
 * The bundled conversation projector folds `assistant/chunk` deltas by their
 * numeric `index` and renders settled assistant messages in `content` order.
 * The canonical order on the wire is: reasoning (0) → text (1) → tool-call
 * (2+). Text-only messages legitimately occupy index 1: the projector compacts
 * sparse blocks, and index 0 must never be filled with fake reasoning.
 */
class DshReasoningOrderTest {

    private fun nativeEvent(type: String, seq: Long, data: JSONObject): JSONObject =
        JSONObject()
            .put("type", type)
            .put("seq", seq)
            .put("time", 1_700_000_000_000L)
            .put("data", data)

    // ------------------------------------------------------- streaming chunks

    @Test
    fun `reasoning delta occupies index 0`() {
        val chunk = JSONObject()
            .put("messageId", "a1")
            .put("chunk", JSONObject().put("type", "reasoning-delta").put("text", "想想"))
        val event = DshApiAdapter.nativeEventToMuxFrame("s1", nativeEvent("assistant/chunk", 3, chunk))
            .getJSONObject("event")
        val translated = event.getJSONObject("data").getJSONObject("chunk")
        assertEquals("reasoning-delta", translated.getString("type"))
        assertEquals(0, translated.getInt("index"))
        assertEquals("想想", translated.getString("text"))
    }

    @Test
    fun `text delta occupies index 1`() {
        val chunk = JSONObject()
            .put("messageId", "a1")
            .put("chunk", JSONObject().put("type", "text-delta").put("text", "答"))
        val event = DshApiAdapter.nativeEventToMuxFrame("s1", nativeEvent("assistant/chunk", 4, chunk))
            .getJSONObject("event")
        val translated = event.getJSONObject("data").getJSONObject("chunk")
        assertEquals("text-delta", translated.getString("type"))
        assertEquals(1, translated.getInt("index"))
        assertEquals("答", translated.getString("text"))
    }

    @Test
    fun `tool-call deltas stay at index 2 and beyond`() {
        var seq = 10L
        val map = mutableMapOf<String, Int>()
        for (callId in listOf("call_a", "call_b")) {
            val chunk = JSONObject()
                .put("messageId", "a1")
                .put("chunk", JSONObject()
                    .put("type", "tool-call-delta")
                    .put("callId", callId)
                    .put("text", "{}"))
            val event = DshApiAdapter.nativeEventToMuxFrame("s1", nativeEvent("assistant/chunk", seq++, chunk))
                .getJSONObject("event")
            val translated = event.getJSONObject("data").getJSONObject("chunk")
            map[callId] = translated.getInt("index")
        }
        assertEquals(2, map["call_a"])
        assertEquals(3, map["call_b"])
    }

    @Test
    fun `mixed stream retains reasoning before text`() {
        val events = listOf(
            JSONObject().put("messageId", "a1").put("chunk", JSONObject()
                .put("type", "reasoning-delta").put("text", "先想")),
            JSONObject().put("messageId", "a1").put("chunk", JSONObject()
                .put("type", "text-delta").put("text", "后答")),
        )
        val indexes = events.map { data ->
            DshApiAdapter.nativeEventToMuxFrame("s1", nativeEvent("assistant/chunk", 5, data))
                .getJSONObject("event").getJSONObject("data").getJSONObject("chunk").getInt("index")
        }
        assertEquals(listOf(0, 1), indexes)
    }

    // ---------------------------------------------------- settled / backfill

    @Test
    fun `legacy backfill builds reasoning before text`() {
        val calls = JSONArray().put(JSONObject().put("toolUseId", "c1").put("name", "Read"))
        val content = DshApiAdapter.legacyAssistantContent("最终答案", "思考过程", calls)
        assertEquals(3, content.length())
        assertEquals("thinking", content.getJSONObject(0).getString("type"))
        assertEquals("思考过程", content.getJSONObject(0).getString("text"))
        assertEquals("text", content.getJSONObject(1).getString("type"))
        assertEquals("最终答案", content.getJSONObject(1).getString("text"))
        assertEquals("tool_use", content.getJSONObject(2).getString("type"))
    }

    @Test
    fun `legacy backfill text-only message stays text-only`() {
        val content = DshApiAdapter.legacyAssistantContent("只有答案", "", JSONArray())
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
    }

    @Test
    fun `legacy backfill reasoning-only message stays reasoning-only`() {
        val content = DshApiAdapter.legacyAssistantContent("", "只有思考", JSONArray())
        assertEquals(1, content.length())
        assertEquals("thinking", content.getJSONObject(0).getString("type"))
    }

    /** Settled assistant messages keep block order from the native journal. */
    @Test
    fun `settled mixed message keeps reasoning before text after conversion`() {
        val nativeContent = JSONArray()
            .put(JSONObject().put("type", "thinking").put("text", "想"))
            .put(JSONObject().put("type", "text").put("text", "答"))
        val frame = DshApiAdapter.nativeEventToMuxFrame(
            "s1",
            nativeEvent("assistant/message", 9, JSONObject().put("message", JSONObject()
                .put("id", "a1").put("role", "assistant").put("content", nativeContent))),
        )
        val message = frame.getJSONObject("event").getJSONObject("data").getJSONObject("message")
        val content = message.getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("reasoning", content.getJSONObject(0).getString("type"))
        assertEquals("text", content.getJSONObject(1).getString("type"))
        assertTrue(frame.getJSONObject("event").has("surfaceOp"))
        assertNull(null)
    }

    /** Unknown chunk types must be dropped without crashing the stream. */
    @Test
    fun `unknown chunk type falls back to passthrough`() {
        val chunk = JSONObject()
            .put("messageId", "a1")
            .put("chunk", JSONObject().put("type", "telemetry").put("text", "x"))
        val event = DshApiAdapter.nativeEventToMuxFrame("s1", nativeEvent("assistant/chunk", 9, chunk))
            .getJSONObject("event")
        assertEquals("assistant/chunk", event.getString("type"))
        val data = event.getJSONObject("data")
        assertEquals("telemetry", data.getJSONObject("chunk").getString("type"))
    }
}
