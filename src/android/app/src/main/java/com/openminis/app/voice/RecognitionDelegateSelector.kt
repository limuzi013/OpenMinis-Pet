package com.openminis.app.voice

/**
 * Chooses the recognizer used behind [AssistRecognitionService].
 *
 * Selecting OpenMinis as the assistant can make it the configured speech
 * recognizer on older Android releases.  Routing a [android.speech.SpeechRecognizer]
 * back to our own bridge would recurse indefinitely, so the selection policy is
 * deliberately small and deterministic: preserve the previously configured
 * recognizer when it is still available, otherwise use a system recognizer.  We
 * do not silently send microphone audio to an arbitrary third-party service.
 */
internal object RecognitionDelegateSelector {

    data class Component(
        val packageName: String,
        val className: String,
    )

    data class Candidate(
        val component: Component,
        val isSystemApp: Boolean,
    )

    fun select(
        self: Component,
        configured: Component?,
        candidates: List<Candidate>,
    ): Component? {
        val usable = candidates.filter { it.component != self }
        return configured
            ?.takeIf { wanted -> usable.any { it.component == wanted } }
            ?: usable.firstOrNull { it.isSystemApp }?.component
    }
}
