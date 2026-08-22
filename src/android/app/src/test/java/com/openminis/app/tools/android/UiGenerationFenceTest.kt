package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Test

class UiGenerationFenceTest {
    @Test
    fun `ref is valid only for its generation and fingerprint`() {
        var now = 1_000L
        val fence = UiGenerationFence(maxEntries = 2, ttlMs = 500L) { now }
        val generation = fence.nextGeneration()
        fence.install(generation, "screen-a", setOf("u1", "u2"))
        assertEquals(UiGenerationFence.Verdict.VALID, fence.validate(generation, "u1", "screen-a"))
        assertEquals(UiGenerationFence.Verdict.REF_NOT_FOUND, fence.validate(generation, "u3", "screen-a"))
        assertEquals(UiGenerationFence.Verdict.STALE, fence.validate(generation, "u1", "screen-b"))
    }

    @Test
    fun `expired and evicted generations are stale`() {
        var now = 0L
        val fence = UiGenerationFence(maxEntries = 1, ttlMs = 100L) { now }
        val first = fence.nextGeneration()
        fence.install(first, "a", setOf("u1"))
        now = 101L
        assertEquals(UiGenerationFence.Verdict.STALE, fence.validate(first, "u1", "a"))

        val second = fence.nextGeneration()
        fence.install(second, "b", setOf("u1"))
        val third = fence.nextGeneration()
        fence.install(third, "c", setOf("u1"))
        assertEquals(UiGenerationFence.Verdict.STALE, fence.validate(second, "u1", "b"))
        assertEquals(UiGenerationFence.Verdict.VALID, fence.validate(third, "u1", "c"))
    }
}
