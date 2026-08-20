package com.openminis.app.voice

import android.service.voice.VoiceInteractionService

/**
 * Lets OpenMinis Pet be registered as the system default digital assistant
 * (RoleManager.ROLE_ASSISTANT). The platform binds this lightweight service
 * while it is the selected assistant; each actual invocation is handled by
 * [AssistSessionService] and [AssistSession].
 * The manifest metadata is intentionally part of this contract: Android only
 * considers a VoiceInteractionService assistant-qualified when it names both
 * a session and recognition service.
 */
class AssistService : VoiceInteractionService()
