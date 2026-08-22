package com.openminis.app.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * Minis Web permission presets: a single selector
 * binding a sandbox mode and an approval posture. Default keeps the current
 * remote surface (sandboxed shell + workspace file edits + RPC).
 */
object RemotePermissionPolicy {

    const val PRESET_WORKSPACE_WRITE = "workspace-write"
    const val PRESET_DANGER_FULL = "danger-full-access"

    private const val PREFS = "remote_permission_policy"
    private const val KEY_PRESET = "preset"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun preset(context: Context): String =
        prefs(context).getString(KEY_PRESET, PRESET_WORKSPACE_WRITE)
            ?.takeIf { it == PRESET_WORKSPACE_WRITE || it == PRESET_DANGER_FULL }
            ?: PRESET_WORKSPACE_WRITE

    fun setPreset(context: Context, preset: String): Boolean {
        if (preset != PRESET_WORKSPACE_WRITE && preset != PRESET_DANGER_FULL) return false
        prefs(context).edit().putString(KEY_PRESET, preset).apply()
        return true
    }

    /**
     * Current surface gates. workspace-write keeps shell / fileWrite / rpc
     * enabled (same as today); danger-full-access additionally allows admin
     * ops — none exist yet, but every future admin endpoint must ask here.
     */
    fun allows(context: Context, op: String): Boolean {
        val p = preset(context)
        return when (op) {
            "shell", "fileWrite", "rpc" -> true
            "admin" -> p == PRESET_DANGER_FULL
            else -> false
        }
    }

    fun sandboxInfo(context: Context): Map<String, String> = mapOf(
        "owner" to "RemotePermissionPolicy",
        "mode" to preset(context),
        "workspaceRoot" to "/var/minis/workspace",
    )
}
