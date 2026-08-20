package com.openminis.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.openminis.app.MainActivity

/**
 * Minimal assistant session: invoking the assistant opens the app's main
 * chat UI and immediately finishes the session. Keeps the surface small —
 * OpenMinis Pet is a text-first assistant, so long-press Home = open the app.
 */
class AssistSession(
    private val appContext: Context,
    service: AssistSessionService,
) : VoiceInteractionSession(service) {

    companion object {
        /** Debounce: repeated assistant invocations within this window are
         *  collapsed into one launch (long-press Home mashing). */
        private const val LAUNCH_DEBOUNCE_MS = 1_500L
        @Volatile private var lastLaunchAt = 0L
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < LAUNCH_DEBOUNCE_MS) {
            finish()
            return
        }
        lastLaunchAt = now
        runCatching {
            val intent = Intent(appContext, MainActivity::class.java)
                .setAction(Intent.ACTION_ASSIST)
                // MainActivity is singleTask. CLEAR_TOP makes every system
                // assistant gesture bring the existing chat task forward
                // rather than layering another task behind the session.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            appContext.startActivity(intent)
        }
        finish()
    }
}
