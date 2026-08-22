package com.openminis.app.tools.android

/** Auditable risk class for Android debug operations. */
enum class AndroidOperationRisk { READ_ONLY, USER_VISIBLE_SIDE_EFFECT, DESTRUCTIVE, ROOT_AUTHORIZATION, ROOT_SETUP }

object AndroidOperationRiskPolicy {
    fun classify(tool: String, action: String): AndroidOperationRisk = when (tool to action) {
        AndroidAgentTools.CAPABILITIES to "active_root_probe" -> AndroidOperationRisk.ROOT_AUTHORIZATION
        AndroidAgentTools.CAPABILITIES to "probe_native_chroot" -> AndroidOperationRisk.ROOT_SETUP
        AndroidAgentTools.APP to "install",
        AndroidAgentTools.APP to "uninstall",
        AndroidAgentTools.DEPLOY to "install",
        AndroidAgentTools.DEPLOY to "install_and_launch",
        AndroidAgentTools.LOGS to "clear" -> AndroidOperationRisk.DESTRUCTIVE
        AndroidAgentTools.APP to "launch",
        AndroidAgentTools.APP to "stop",
        AndroidAgentTools.APP to "restart",
        AndroidAgentTools.DEPLOY to "launch",
        AndroidAgentTools.UI to "click",
        AndroidAgentTools.UI to "long_press",
        AndroidAgentTools.UI to "set_text",
        AndroidAgentTools.UI to "scroll",
        AndroidAgentTools.UI to "back",
        AndroidAgentTools.UI to "home" -> AndroidOperationRisk.USER_VISIBLE_SIDE_EFFECT
        else -> AndroidOperationRisk.READ_ONLY
    }

    fun requiresOneTimeApproval(risk: AndroidOperationRisk): Boolean = risk in setOf(
        AndroidOperationRisk.DESTRUCTIVE,
        AndroidOperationRisk.ROOT_AUTHORIZATION,
        AndroidOperationRisk.ROOT_SETUP,
    )
}
