package com.openminis.app.pet

import org.junit.Assert.assertEquals
import org.junit.Test

class PetOverlayGeometryTest {

    @Test
    fun `saved position is reclamped when a larger sprite replaces the old one`() {
        // 864 was the right edge for a 216 px sprite on this display. At the
        // maximum scale a 864 px sprite must move back on screen immediately,
        // before its view has measured.
        assertEquals(
            216 to 120,
            PetOverlayGeometry.clamp(
                x = 864,
                y = 120,
                contentWidth = 864,
                contentHeight = 300,
                viewportWidth = 1_080,
                viewportHeight = 2_400,
            ),
        )
    }

    @Test
    fun `clamp keeps an oversized sprite reachable at origin`() {
        assertEquals(
            0 to 0,
            PetOverlayGeometry.clamp(
                x = 99,
                y = 80,
                contentWidth = 1_400,
                contentHeight = 2_600,
                viewportWidth = 1_080,
                viewportHeight = 2_400,
            ),
        )
    }

    @Test
    fun `snap chooses nearest horizontal edge from sprite centre`() {
        assertEquals(0, PetOverlayGeometry.nearestHorizontalEdge(120, 240, 1_080))
        assertEquals(840, PetOverlayGeometry.nearestHorizontalEdge(600, 240, 1_080))
    }

    @Test
    fun `agent resolver preserves semantic error wait thinking and work states`() {
        assertEquals(PetState.FAILED, PetAgentStateResolver.resolve(1, "network error"))
        assertEquals(PetState.WAITING, PetAgentStateResolver.resolve(1, "pending approval"))
        assertEquals(PetState.REVIEW, PetAgentStateResolver.resolve(1, "thinking"))
        assertEquals(PetState.RUNNING, PetAgentStateResolver.resolve(1, "Idle"))
        assertEquals(PetState.IDLE, PetAgentStateResolver.resolve(0, "Idle"))
    }
}
