package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidOperationRiskPolicyTest {
    @Test
    fun `read only diagnostics do not request mutation approval`() {
        val risk = AndroidOperationRiskPolicy.classify(AndroidAgentTools.DIAGNOSE, "memory")
        assertEquals(AndroidOperationRisk.READ_ONLY, risk)
        assertFalse(AndroidOperationRiskPolicy.requiresOneTimeApproval(risk))
    }

    @Test
    fun `install uninstall clear and root setup are approval classified`() {
        val cases = listOf(
            AndroidAgentTools.DEPLOY to "install_and_launch",
            AndroidAgentTools.APP to "uninstall",
            AndroidAgentTools.LOGS to "clear",
            AndroidAgentTools.CAPABILITIES to "active_root_probe",
            AndroidAgentTools.CAPABILITIES to "probe_native_chroot",
        )
        for ((tool, action) in cases) {
            assertTrue("$tool/$action", AndroidOperationRiskPolicy.requiresOneTimeApproval(
                AndroidOperationRiskPolicy.classify(tool, action),
            ))
        }
    }

    @Test
    fun `ui mutation is visible side effect but not destructive`() {
        assertEquals(
            AndroidOperationRisk.USER_VISIBLE_SIDE_EFFECT,
            AndroidOperationRiskPolicy.classify(AndroidAgentTools.UI, "click"),
        )
    }
}
