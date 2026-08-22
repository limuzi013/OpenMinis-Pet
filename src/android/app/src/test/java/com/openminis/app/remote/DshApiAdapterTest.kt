package com.openminis.app.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Minis Web compatibility wire that the compiled frontend validates with zod.
 *
 * The browser client parses every downlink frame with `serverRequestSchema` +
 * `muxFrameSchema` and every unary response with `UNARY_VALUE_SCHEMAS[method]`
 * (see `assets/minis/plugins/@deepseek-ai/dsh-client-connection/client.js`). A
 * shape mismatch is not a soft failure: the frame is dropped or the call throws
 * inside the client, which surfaces to the user as "the backend is not
 * connected". These tests pin the translations that are easy to regress.
 */
class DshApiAdapterTest {

    private fun nativeEvent(type: String, seq: Long, data: JSONObject): JSONObject =
        JSONObject()
            .put("type", type)
            .put("seq", seq)
            .put("time", 1_700_000_000_000L)
            .put("data", data)

    /**
     * `deriveEventMessage` (client.js:344) reads a `user/message` payload
     * straight off `data`, but the native journal nests it under `data.message`
     * alongside `turn`.
     */
    @Test
    fun `user message is unwrapped from the native turn envelope`() {
        val frame = DshApiAdapter.nativeEventToMuxFrame(
            "s1",
            nativeEvent("user/message", 4, JSONObject()
                .put("turn", 2)
                .put("message", JSONObject()
                    .put("id", "m1")
                    .put("role", "user")
                    .put("content", "你好")))
        )

        assertEquals("session/event", frame.getString("type"))
        assertEquals("s1", frame.getString("sessionId"))

        val event = frame.getJSONObject("event")
        assertEquals("user/message", event.getString("type"))
        assertEquals(4L, event.getLong("seq"))

        // data IS the message — not { turn, message }.
        val message = event.getJSONObject("data")
        assertEquals("m1", message.getString("id"))
        assertEquals("user", message.getString("role"))
        assertEquals("user", message.getJSONObject("source").getString("kind"))
        assertEquals(2, message.getInt("turn"))

        // A plain string becomes a one-element text block array.
        val content = message.getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("你好", content.getJSONObject(0).getString("text"))
    }

    /**
     * `isSurfaceEvent` (client.js:10230) drops any message-producing event that
     * has no `surfaceOp`, so the event parses fine and then never renders.
     */
    @Test
    fun `surface events carry the surfaceOp marker`() {
        val types = listOf(
            "user/message" to JSONObject().put("message", JSONObject()
                .put("id", "m").put("role", "user").put("content", "hi")),
            "assistant/message" to JSONObject().put("message", JSONObject()
                .put("id", "a").put("role", "assistant")
                .put("content", JSONArray().put(
                    JSONObject().put("type", "text").put("text", "hi")))),
        )
        for ((type, data) in types) {
            val event = DshApiAdapter.nativeEventToMuxFrame("s1", nativeEvent(type, 1, data))
                .getJSONObject("event")
            assertEquals("append", event.getString("surfaceOp"))
        }
    }

    /**
     * `toAssistantBlock` (client.js:6887) only knows `text`, `reasoning`,
     * `image` and `tool-call`; the native journal emits the Anthropic spellings
     * `thinking` and `tool_use`, which would classify as "other" and render as
     * nothing.
     */
    @Test
    fun `assistant blocks are renamed to the DSH vocabulary`() {
        val nativeContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "答案"))
            .put(JSONObject().put("type", "thinking").put("text", "推理中"))
            .put(JSONObject().put("type", "tool_use").put("value", JSONObject()
                .put("toolUseId", "call_7")
                .put("name", "Read")
                .put("toolArgs", """{"path":"a.txt"}""")))

        val frame = DshApiAdapter.nativeEventToMuxFrame(
            "s1",
            nativeEvent("assistant/message", 9, JSONObject().put("message", JSONObject()
                .put("id", "a1")
                .put("role", "assistant")
                .put("content", nativeContent)))
        )

        // assistant/message keeps the message under data.message (client.js:349).
        val message = frame.getJSONObject("event").getJSONObject("data").getJSONObject("message")
        val content = message.getJSONArray("content")
        assertEquals(3, content.length())

        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("reasoning", content.getJSONObject(1).getString("type"))
        assertEquals("推理中", content.getJSONObject(1).getString("text"))

        val call = content.getJSONObject(2)
        assertEquals("tool-call", call.getString("type"))
        assertEquals("call_7", call.getString("id"))
        assertEquals("Read", call.getString("name"))
        // argsRaw is rendered as text, so arguments must be a string.
        assertTrue(call.get("arguments") is String)
    }

    /** `tool/result` also nests its message under data.message. */
    @Test
    fun `tool result becomes a tool-result block keyed by call id`() {
        val frame = DshApiAdapter.nativeEventToMuxFrame(
            "s1",
            nativeEvent("tool/result", 12, JSONObject()
                .put("messageId", "a1")
                .put("call", JSONObject().put("toolUseId", "call_7").put("name", "Read"))
                .put("result", JSONObject().put("output", "file body").put("success", true)))
        )

        val event = frame.getJSONObject("event")
        assertEquals("tool/result", event.getString("type"))
        assertEquals("append", event.getString("surfaceOp"))

        val message = event.getJSONObject("data").getJSONObject("message")
        assertEquals("call_7", message.getJSONObject("source").getString("callId"))
        val block = message.getJSONArray("content").getJSONObject(0)
        assertEquals("tool-result", block.getString("type"))
        assertEquals("call_7", block.getString("toolCallId"))
        assertEquals("file body", block.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(false, block.getBoolean("isError"))
    }

    /**
     * sessionEventSchema (client.js:5229) locks only `{type, seq, time, data}`,
     * so non-message events must survive untouched — turn boundaries and token
     * deltas are what drive the streaming UI. Text blocks live at index 1
     * (reasoning is 0); the projector compacts sparse blocks.
     */
    @Test
    fun `native text deltas gain the numeric block index required by the browser fold`() {
        val chunk = JSONObject()
            .put("messageId", "a1")
            .put("turn", 3)
            .put("step", 1)
            .put("chunk", JSONObject().put("type", "text-delta").put("text", "块"))
        val event = DshApiAdapter.nativeEventToMuxFrame("s1", nativeEvent("assistant/chunk", 3, chunk))
            .getJSONObject("event")

        assertEquals("assistant/chunk", event.getString("type"))
        assertEquals(3L, event.getLong("seq"))
        val data = event.getJSONObject("data")
        val translated = data.getJSONObject("chunk")
        assertEquals("text-delta", translated.getString("type"))
        assertEquals(1, translated.getInt("index"))
        assertEquals("块", translated.getString("text"))
        assertEquals(3, data.getInt("turn"))
        assertEquals(1, data.getInt("step"))
        // A non-surface event must NOT be marked as one.
        assertNull(event.opt("surfaceOp"))
    }

    @Test
    fun `tool call delta without a name preserves the existing streamed name`() {
        val data = JSONObject()
            .put("messageId", "a1")
            .put("chunk", JSONObject()
                .put("type", "tool-call-delta")
                .put("callId", "call_9")
                .put("argumentsDelta", "{\"path\":"))
        val event = DshApiAdapter.nativeEventToMuxFrame("s1", nativeEvent("assistant/chunk", 7, data))
            .getJSONObject("event")
        val chunk = event.getJSONObject("data").getJSONObject("chunk")

        assertEquals("tool-call-delta", chunk.getString("type"))
        assertEquals("call_9", chunk.getString("id"))
        assertFalse(chunk.has("name"))
    }

    @Test
    fun `native tool call envelope is flattened for the correlated tool fold`() {
        val data = JSONObject()
            .put("messageId", "a1")
            .put("turn", 4)
            .put("step", 2)
            .put("call", JSONObject()
                .put("toolUseId", "call_9")
                .put("toolName", "Read")
                .put("toolArgs", "{\"path\":\"a.txt\"}"))
        val event = DshApiAdapter.nativeEventToMuxFrame("s1", nativeEvent("tool/call", 8, data))
            .getJSONObject("event")

        val flat = event.getJSONObject("data")
        assertEquals("call_9", flat.getString("callId"))
        assertEquals("Read", flat.getString("name"))
        assertEquals("{\"path\":\"a.txt\"}", flat.getString("arguments"))
        assertEquals(4, flat.getInt("turn"))
        assertEquals(2, flat.getInt("step"))
    }

    /** A malformed native event must not crash the stream. */
    @Test
    fun `message events without a message body fall back to passthrough`() {
        val event = DshApiAdapter.nativeEventToMuxFrame(
            "s1", nativeEvent("user/message", 1, JSONObject().put("turn", 1))
        ).getJSONObject("event")

        assertEquals("user/message", event.getString("type"))
        assertNotNull(event.opt("data"))
    }
}
