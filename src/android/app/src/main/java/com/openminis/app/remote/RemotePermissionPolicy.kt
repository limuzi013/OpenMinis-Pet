package com.openminis.app.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * Source of truth for the Web Remote's per-capability switches.
 *
 * Design:
 *  - [RemoteCapabilityCatalog] is the definition of every capability
 *    (stable id, 中文 label/说明, risk, default state).
 *  - SharedPreferences (`remote_permission_policy`) stores ONE boolean per
 *    capability (`cap.<id>`). The default (no stored key) is the catalog
 *    default, so an upgrade never silently loses previous behavior.
 *  - The legacy single-preset value remains stored and readable
 *    ([preset]/[setPreset]) for API compatibility; calling [setPreset]
 *    applies the preset's full state as a coarse batch (workspace-write →
 *    catalog defaults, danger-full-access → all on).
 *  - One-time migration: an install that previously selected
 *    `danger-full-access` but has never written capability keys gets every
 *    capability materialized as enabled, so the old choice is honored.
 *  - Unknown capability ids: reads default to false (deny), writes are
 *    rejected — a future catalog change can never open a switch that this
 *    build does not know.
 */
object RemotePermissionPolicy {

    const val PRESET_WORKSPACE_WRITE = "workspace-write"
    const val PRESET_DANGER_FULL = "danger-full-access"

    private const val PREFS = "remote_permission_policy"
    private const val KEY_PRESET = "preset"
    private const val KEY_CAP_PREFIX = "cap."
    private const val KEY_CAPS_MIGRATED = "caps_migrated_v2"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Materialize legacy preset state into per-capability keys on first
     * access after an upgrade.
     */
    @Synchronized
    private fun ensureMigrated(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_CAPS_MIGRATED, false)) return
        val storedPreset = p.getString(KEY_PRESET, PRESET_WORKSPACE_WRITE)
            ?.takeIf { RemoteCapabilityCatalog.isKnownPreset(it) }
            ?: PRESET_WORKSPACE_WRITE
        val state = RemoteCapabilityCatalog.valuesForPreset(storedPreset)
        val edit = p.edit()
        for ((id, enabled) in state) edit.putBoolean(KEY_CAP_PREFIX + id, enabled)
        edit.putBoolean(KEY_CAPS_MIGRATED, true).apply()
    }

    fun preset(context: Context): String {
        ensureMigrated(context)
        return prefs(context).getString(KEY_PRESET, PRESET_WORKSPACE_WRITE)
            ?.takeIf { RemoteCapabilityCatalog.isKnownPreset(it) }
            ?: PRESET_WORKSPACE_WRITE
    }

    /**
     * Legacy preset API compatibility: applying a preset replaces every
     * capability with the preset's coarse state. The preset value itself is
     * also stored so old readers keep working.
     */
    @Synchronized
    fun setPreset(context: Context, preset: String): Boolean {
        if (!RemoteCapabilityCatalog.isKnownPreset(preset)) return false
        val state = RemoteCapabilityCatalog.valuesForPreset(preset)
        val edit = prefs(context).edit()
        edit.putString(KEY_PRESET, preset)
        edit.putBoolean(KEY_CAPS_MIGRATED, true)
        for ((id, enabled) in state) edit.putBoolean(KEY_CAP_PREFIX + id, enabled)
        edit.apply()
        return true
    }

    /** Whether one capability is enabled. Unknown id → false (deny). */
    fun allowsCapability(context: Context, capabilityId: String): Boolean {
        val cap = RemoteCapabilityCatalog.byId(capabilityId) ?: return false
        ensureMigrated(context)
        return prefs(context).getBoolean(KEY_CAP_PREFIX + capabilityId, cap.defaultEnabled)
    }

    /** Current state of every known capability (id → enabled). */
    fun capabilityState(context: Context): Map<String, Boolean> {
        ensureMigrated(context)
        val p = prefs(context)
        return RemoteCapabilityCatalog.ALL.associate { cap ->
            cap.id to p.getBoolean(KEY_CAP_PREFIX + cap.id, cap.defaultEnabled)
        }
    }

    /**
     * Flip exactly one capability. Returns false when the id is unknown or
     * the value cannot be stored. Only the touched capability changes.
     */
    @Synchronized
    fun setCapability(context: Context, capabilityId: String, enabled: Boolean): Boolean {
        val cap = RemoteCapabilityCatalog.byId(capabilityId) ?: return false
        ensureMigrated(context)
        prefs(context).edit().putBoolean(KEY_CAP_PREFIX + capabilityId, enabled).apply()
        return true
    }

    /**
     * Legacy coarse gate used by older call sites and by the current
     * migrations. It remains as a narrow compatibility shim:
     *  - "shell" → [SHELL], "fileWrite" → [FILES_WRITE],
     *    "rpc" → the entire catalog default (per-capability checks are the
     *    real gate now), "admin" → [ADMIN].
     * New code must use [allowsCapability].
     */
    fun allows(context: Context, op: String): Boolean = when (op) {
        "shell" -> allowsCapability(context, RemoteCapabilityCatalog.SHELL)
        "fileWrite" -> allowsCapability(context, RemoteCapabilityCatalog.FILES_WRITE)
        "rpc" -> true // legacy coarse answer; per-method gates are authoritative now
        "admin" -> allowsCapability(context, RemoteCapabilityCatalog.ADMIN)
        else -> allowsCapability(context, op) // already a capability id? let catalog answer
    }

    fun sandboxInfo(context: Context): Map<String, String> = mapOf(
        "owner" to "RemotePermissionPolicy",
        "mode" to preset(context),
        "workspaceRoot" to "/var/minis/workspace",
    )
}
