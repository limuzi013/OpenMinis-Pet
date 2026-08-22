package com.openminis.app.tools

import android.content.Context

/**
 * Per-session agent execution permission preset (DSH `/permission`).
 *
 * This is deliberately separate from [com.openminis.app.remote.RemotePermissionPolicy],
 * which gates the *Web Remote surface* (which HTTP/RPC capability a browser may
 * invoke). This store gates what the *Agent runtime* may do inside one chat
 * session and is consumed directly by the tool execution gate
 * (see [FileWriteTool]/[FileEditTool]).
 *
 * Presets (DSH enum the bundled web client can express):
 *  - `workspace-write`: file writes allowed only under `/var/minis/workspace`
 *    (and the per-session virtual `/var/minis/` dirs that map into app storage);
 *    everything else is refused with an explicit hint.
 *  - `danger-full-access`: no extra restriction from this store; existing T219
 *    read-only mount guards and the OS sandbox still apply.
 *
 * `null` (never set) keeps the legacy unrestricted behaviour and is reported as
 * `custom` on the DSH projection until the user switches a preset.
 *
 * The DSH web client (rc.8) hard-codes exactly two preset enum values
 * (`workspace-write`, `danger-full-access`) in its permissions projection, so a
 * third "read-only" mode cannot be expressed through the stock UI; it is
 * therefore not advertised here rather than faking a menu entry.
 */
object SessionPermissionStore {

    const val WORKSPACE_WRITE = "workspace-write"
    const val DANGER_FULL_ACCESS = "danger-full-access"

    private const val PREFS = "minis_session_permissions"
    private fun key(sessionId: String) = "preset_$sessionId"

    fun preset(context: Context, sessionId: String): String? {
        if (sessionId.isBlank()) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(sessionId), null)
            ?.takeIf { it == WORKSPACE_WRITE || it == DANGER_FULL_ACCESS }
    }

    fun setPreset(context: Context, sessionId: String, preset: String?) {
        if (sessionId.isBlank()) return
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (preset == null) editor.remove(key(sessionId)) else editor.putString(key(sessionId), preset)
        editor.apply()
    }

    fun isKnownPreset(preset: String): Boolean =
        preset == WORKSPACE_WRITE || preset == DANGER_FULL_ACCESS

    /**
     * Whether a file write to [linuxPath] is permitted under the session's
     * current preset. `null` preset (never set) behaves like the legacy
     * unrestricted runtime.
     */
    fun allowsFileWrite(context: Context, sessionId: String, linuxPath: String): Boolean {
        val preset = preset(context, sessionId) ?: return true
        if (preset == DANGER_FULL_ACCESS) return true
        // workspace-write: only the per-session virtual /var/minis tree.
        return linuxPath.startsWith("/var/minis/workspace/") ||
            linuxPath == "/var/minis/workspace" ||
            linuxPath.startsWith("/var/minis/attachments/") ||
            linuxPath.startsWith("/var/minis/offloads/") ||
            linuxPath.startsWith("/var/minis/browser/") ||
            linuxPath.startsWith("/var/minis/shared/") ||
            linuxPath.startsWith("/var/minis/skills/") ||
            linuxPath.startsWith("/var/minis/memory/") ||
            linuxPath.startsWith("/var/minis/mounts/")
    }
}
