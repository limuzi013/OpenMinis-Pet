package com.openminis.app.pet

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Small API used by OpenMinis runtime code without depending on overlay internals. */
object PetBridge {
    private const val TAG = "PetBridge"

    /** Throttle identical state broadcasts so a fast agent progress burst
     *  (tool status flapping every few hundred ms) cannot spam the overlay
     *  with startForegroundService calls. */
    @Volatile private var lastState: PetState? = null
    @Volatile private var lastStateAt = 0L

    fun setState(context: Context, state: PetState) {
        if (!PetPreferences.isEnabled(context)) return
        val now = System.currentTimeMillis()
        if (state == lastState && now - lastStateAt < 500L) return
        lastState = state
        lastStateAt = now
        val intent = Intent(context, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_SET_STATE
            putExtra(PetOverlayService.EXTRA_STATE, state.name)
        }
        startSafely(context, intent)
    }

    fun updateAgentStatus(context: Context, sessionCount: Int, toolStatus: String?) {
        if (!PetPreferences.isEnabled(context)) return
        setState(context, PetAgentStateResolver.resolve(sessionCount, toolStatus))
    }

    fun startIfEnabled(context: Context) {
        if (!PetPreferences.isEnabled(context)) return
        startSafely(context, Intent(context, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_START
        })
    }

    fun reload(context: Context) {
        if (!PetPreferences.isEnabled(context)) return
        startSafely(context, Intent(context, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_RELOAD
        })
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, PetOverlayService::class.java))
    }

    private fun startSafely(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: Exception) {
            // Never let the optional pet runtime crash an agent turn on OEM ROMs
            // that reject an FGS start from a background transition.
            Log.w(TAG, "Unable to start/update pet overlay: ${e.message}")
        }
    }
}
