package com.openminis.app.remote

import com.openminis.app.ui.chat.SessionEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * sessionStats projection semantics (追加任务 #10/#12).
 *
 * The DSH StatsLine consumes the projection's raw integers and formats them;
 * this layer must never format ("0.7s") or invent numbers. The fold mirrors
 * the DSH fixture's sessionStatsOf using the boundary events the native
 * runtime actually emits.
 */
class DshSessionStatsTest {

    private fun event(type: String, time: Long, data: JSONObject = JSONObject()): SessionEvent =
        SessionEvent("s1", time, time, type, data.toString())

    @Test
    fun `empty journal yields zeroed stats`() {
        val stats = DshApiAdapter.computeSessionStats(emptyList())
        assertEquals(0, stats.getInt("turns"))
        assertEquals(0, stats.getInt("steps"))
        assertEquals(0L, stats.getLong("llmMs"))
        assertEquals(0L, stats.getLong("toolMs"))
        assertEquals(0L, stats.getLong("ttftMs"))
        assertEquals(0, stats.getInt("ttftSteps"))
        assertEquals(0L, stats.getLong("decodeMs"))
        assertEquals(0L, stats.getLong("decodeTokens"))
    }

    @Test
    fun `one step with ttft and decode`() {
        val events = listOf(
            event("assistant/placeholder", 1_000),
            event("assistant/chunk", 1_500, JSONObject().put("chunk", JSONObject()
                .put("type", "text-delta").put("text", "a"))),
            event("assistant/message", 4_000, JSONObject().put("usage", JSONObject()
                .put("outputTokens", 120))),
            event("turn/end", 4_100),
        )
        val stats = DshApiAdapter.computeSessionStats(events)
        assertEquals(1, stats.getInt("turns"))
        assertEquals(1, stats.getInt("steps"))
        assertEquals(3_000L, stats.getLong("llmMs"))      // 4000 - 1000
        assertEquals(500L, stats.getLong("ttftMs"))       // 1500 - 1000
        assertEquals(1, stats.getInt("ttftSteps"))
        assertEquals(2_500L, stats.getLong("decodeMs"))   // 4000 - 1500
        assertEquals(120L, stats.getLong("decodeTokens"))
    }

    @Test
    fun `reasoning first token counts as ttft`() {
        val events = listOf(
            event("assistant/placeholder", 2_000),
            event("assistant/chunk", 2_300, JSONObject().put("chunk", JSONObject()
                .put("type", "reasoning-delta").put("text", "想"))),
            event("assistant/message", 5_000),
            event("turn/end", 5_100),
        )
        val stats = DshApiAdapter.computeSessionStats(events)
        assertEquals(300L, stats.getLong("ttftMs"))
        assertEquals(1, stats.getInt("ttftSteps"))
        assertEquals(2_700L, stats.getLong("decodeMs"))
    }

    @Test
    fun `tool timing accumulates from call to result`() {
        val events = listOf(
            event("tool/call", 10_000, JSONObject().put("call", JSONObject().put("callId", "c1"))),
            event("tool/result", 12_500, JSONObject().put("call", JSONObject().put("callId", "c1"))),
            event("tool/call", 13_000, JSONObject().put("call", JSONObject().put("callId", "c2"))),
            event("turn/end", 14_000),
        )
        val stats = DshApiAdapter.computeSessionStats(events)
        assertEquals(2_500L, stats.getLong("toolMs"))
    }

    @Test
    fun `turn end clears pending tools so trailing calls do not leak`() {
        val events = listOf(
            event("tool/call", 10_000, JSONObject().put("call", JSONObject().put("callId", "c1"))),
            event("turn/end", 11_000),
            event("tool/result", 30_000, JSONObject().put("call", JSONObject().put("callId", "c1"))),
        )
        val stats = DshApiAdapter.computeSessionStats(events)
        assertEquals(0L, stats.getLong("toolMs"))
    }

    @Test
    fun `steps count settled assistant messages with no placeholder`() {
        // A settled message without a preceding placeholder still counts a step
        // (history backfill emits assistant/message directly).
        val events = listOf(
            event("assistant/message", 1_000, JSONObject()
                .put("message", JSONObject().put("id", "a").put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "hi"))))),
            event("turn/end", 1_200),
        )
        val stats = DshApiAdapter.computeSessionStats(events)
        assertEquals(1, stats.getInt("steps"))
        assertEquals(1, stats.getInt("turns"))
        assertEquals(0L, stats.getLong("llmMs")) // no start boundary — nothing to fake
    }
}
