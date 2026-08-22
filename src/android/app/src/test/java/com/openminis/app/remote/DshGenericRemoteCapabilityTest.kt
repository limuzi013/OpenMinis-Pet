package com.openminis.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DSH generic-Connection RPC endpoint → capability mapping (P0-2/P0-3).
 *
 * The bundled `dsh-api-remotes` calls slash endpoints over the generic Remote
 * (`POST /api/commands/list`, `/api/messageFeedback/put`, `/api/goals` operations).
 * RemoteAccessServer denies every DSH method whose mapping is missing, so this
 * table must stay explicit and complete — no `else -> CHAT` fallback allowed.
 */
class DshGenericRemoteCapabilityTest {

    @Test
    fun `commands endpoints map to CHAT`() {
        assertEquals(
            RemoteCapabilityCatalog.CHAT,
            RemoteCapabilityCatalog.capabilityForDshRequest("commands/list", null),
        )
        assertEquals(
            RemoteCapabilityCatalog.CHAT,
            RemoteCapabilityCatalog.capabilityForDshRequest("commands/execute", null),
        )
    }

    @Test
    fun `messageFeedback endpoints map to CHAT`() {
        for (m in listOf("messageFeedback/list", "messageFeedback/put", "messageFeedback/delete")) {
            assertEquals(RemoteCapabilityCatalog.CHAT, RemoteCapabilityCatalog.capabilityForDshRequest(m, null))
        }
    }

    @Test
    fun `goals endpoints map to AGENT_MANAGE`() {
        for (m in listOf(
            "goals/create", "goals/edit", "goals/pause",
            "goals/resume", "goals/complete", "goals/clear",
        )) {
            assertEquals(RemoteCapabilityCatalog.AGENT_MANAGE, RemoteCapabilityCatalog.capabilityForDshRequest(m, null))
        }
    }

    @Test
    fun `unknown slash endpoints stay denied`() {
        assertNull(RemoteCapabilityCatalog.capabilityForDshRequest("commands/export", null))
        assertNull(RemoteCapabilityCatalog.capabilityForDshRequest("messageFeedback/fetch", null))
        assertNull(RemoteCapabilityCatalog.capabilityForDshRequest("host/execute", null))
        assertNull(RemoteCapabilityCatalog.capabilityForDshRequest("goals/archive", null))
    }

    @Test
    fun `dot methods keep their existing mapping`() {
        assertEquals(
            RemoteCapabilityCatalog.CHAT,
            RemoteCapabilityCatalog.capabilityForDshRequest("session.prompt", null),
        )
        assertEquals(
            RemoteCapabilityCatalog.AGENT_MANAGE,
            RemoteCapabilityCatalog.capabilityForDshRequest("goal.create", null),
        )
    }
}
