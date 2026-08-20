package com.openminis.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionDelegateSelectorTest {

    private val bridge = RecognitionDelegateSelector.Component(
        "dev.openminispet.android",
        "com.openminis.app.voice.AssistRecognitionService",
    )
    private val deviceRecognizer = RecognitionDelegateSelector.Component(
        "com.android.speech",
        "com.android.speech.RecognizerService",
    )
    private val thirdPartyRecognizer = RecognitionDelegateSelector.Component(
        "example.recognizer",
        "example.recognizer.Service",
    )

    @Test
    fun `keeps the configured recognizer when it is available and not the bridge`() {
        val selected = RecognitionDelegateSelector.select(
            self = bridge,
            configured = thirdPartyRecognizer,
            candidates = listOf(
                RecognitionDelegateSelector.Candidate(deviceRecognizer, isSystemApp = true),
                RecognitionDelegateSelector.Candidate(thirdPartyRecognizer, isSystemApp = false),
            ),
        )

        assertEquals(thirdPartyRecognizer, selected)
    }

    @Test
    fun `never selects itself even if an older platform configured it`() {
        val selected = RecognitionDelegateSelector.select(
            self = bridge,
            configured = bridge,
            candidates = listOf(
                RecognitionDelegateSelector.Candidate(bridge, isSystemApp = false),
                RecognitionDelegateSelector.Candidate(deviceRecognizer, isSystemApp = true),
            ),
        )

        assertEquals(deviceRecognizer, selected)
    }

    @Test
    fun `does not send audio to an arbitrary unconfigured third party`() {
        val selected = RecognitionDelegateSelector.select(
            self = bridge,
            configured = null,
            candidates = listOf(
                RecognitionDelegateSelector.Candidate(bridge, isSystemApp = false),
                RecognitionDelegateSelector.Candidate(thirdPartyRecognizer, isSystemApp = false),
            ),
        )

        assertNull(selected)
    }
}
