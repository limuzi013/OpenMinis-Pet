package com.openminis.app.voice

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Recognition service advertised by the voice-interaction manifest.
 *
 * Android requires a non-empty `recognitionService` in a
 * `voice-interaction-service` declaration before an app can qualify for the
 * Assistant role.  OpenMinis is not an ASR engine, so this bridge preserves the
 * device's configured recognizer (or a system recognizer) instead of pretending
 * to transcribe audio itself.  In particular, it never delegates to itself.
 */
class AssistRecognitionService : RecognitionService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeSession: DelegateSession? = null

    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        onMain { startListeningOnMain(recognizerIntent, callback) }
    }

    override fun onStopListening(callback: Callback) {
        onMain { activeSession?.stop() }
    }

    override fun onCancel(callback: Callback) {
        onMain {
            activeSession?.cancelAndDestroy()
            activeSession = null
        }
    }

    override fun onDestroy() {
        activeSession?.cancelAndDestroy()
        activeSession = null
        super.onDestroy()
    }

    private fun startListeningOnMain(intent: Intent, callback: Callback) {
        activeSession?.cancelAndDestroy()
        activeSession = null

        val delegate = findDelegate()
        if (delegate == null) {
            deliver(callback) { error(SpeechRecognizer.ERROR_CLIENT) }
            Log.w(TAG, "No non-self system speech recognizer is available")
            return
        }

        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(applicationContext, delegate)
        }.getOrElse { error ->
            Log.w(TAG, "Could not create delegate recognizer $delegate", error)
            deliver(callback) { this.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }

        DelegateSession(callback, recognizer).also { session ->
            activeSession = session
            recognizer.setRecognitionListener(session)
            runCatching { recognizer.startListening(intent) }.onFailure { error ->
                Log.w(TAG, "Could not start delegate recognizer $delegate", error)
                session.failAndDestroy(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    private fun findDelegate(): ComponentName? {
        val self = RecognitionDelegateSelector.Component(
            packageName = packageName,
            className = AssistRecognitionService::class.java.name,
        )
        val configured = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.VOICE_RECOGNITION_SERVICE)
        }.getOrNull()?.let(ComponentName::unflattenFromString)?.let {
            RecognitionDelegateSelector.Component(it.packageName, it.className)
        }
        val candidates = runCatching {
            packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrElse { emptyList() }.mapNotNull { resolveInfo ->
            val info = resolveInfo.serviceInfo ?: return@mapNotNull null
            val flags = info.applicationInfo.flags
            RecognitionDelegateSelector.Candidate(
                component = RecognitionDelegateSelector.Component(info.packageName, info.name),
                isSystemApp = flags and (
                    ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                ) != 0,
            )
        }
        return RecognitionDelegateSelector.select(self, configured, candidates)?.let {
            ComponentName(it.packageName, it.className)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun deliver(callback: Callback, action: Callback.() -> Unit) {
        runCatching { callback.action() }.onFailure {
            Log.w(TAG, "Recognition client disconnected before a result could be delivered", it)
        }
    }

    private inner class DelegateSession(
        private val callback: Callback,
        private val recognizer: SpeechRecognizer,
    ) : RecognitionListener {

        fun stop() {
            runCatching { recognizer.stopListening() }
                .onFailure { failAndDestroy(SpeechRecognizer.ERROR_CLIENT) }
        }

        fun cancelAndDestroy() {
            runCatching { recognizer.cancel() }
            destroy()
        }

        fun failAndDestroy(error: Int) {
            deliver(callback) { this.error(error) }
            finish()
        }

        override fun onReadyForSpeech(params: Bundle?) {
            deliver(callback) { readyForSpeech(params) }
        }

        override fun onBeginningOfSpeech() {
            deliver(callback) { beginningOfSpeech() }
        }

        override fun onRmsChanged(rmsdB: Float) {
            deliver(callback) { rmsChanged(rmsdB) }
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            if (buffer != null) deliver(callback) { bufferReceived(buffer) }
        }

        override fun onEndOfSpeech() {
            deliver(callback) { endOfSpeech() }
        }

        override fun onError(error: Int) {
            deliver(callback) { this.error(error) }
            finish()
        }

        override fun onResults(results: Bundle?) {
            deliver(callback) { this.results(results) }
            finish()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            deliver(callback) { this.partialResults(partialResults) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun finish() {
            if (activeSession === this) activeSession = null
            destroy()
        }

        private fun destroy() {
            runCatching { recognizer.destroy() }
        }
    }

    private companion object {
        const val TAG = "AssistRecognition"
    }
}
