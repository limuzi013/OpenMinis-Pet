package com.openminis.app.remote

import android.content.Context

/**
 * Real Agent Preset backend (P1-4).
 *
 * Presets are backed by genuinely composable runtime state, not menu stubs.
 * OpenMinis currently exposes one real per-session execution dimension the
 * Agent runtime honors: the [com.openminis.app.tools.SessionPermissionStore]
 * gate (file write permission preset). Built-in presets therefore describe the
 * real effect they apply:
 *
 *  - `default` — legacy unrestricted behaviour (no permission preset set).
 *  - `workspace-sandboxed` — forces the `workspace-write` permission preset
 *    (file writes outside `/var/minis/workspace` and the per-session
 *    `/var/minis/` dirs are refused by FileWriteTool/FileEditTool).
 *
 * `session.create(agentPreset=X)` validates X, persists it for the session and
 * applies it immediately (permission gate). `agentPreset.list/select/copy/
 * remove/read` all go through this one registry; there is deliberately no
 * second preset store.
 *
 * Right now the registry refuses to advertise combinations it cannot apply
 * (e.g. tool filtering or SOUL/prompt variants) instead of faking them.
 */
object AgentPresetRegistry {

    data class Preset(
        val id: String,
        val trust: String, // "system" | "user"
        val isDefault: Boolean,
        val name: String,
        val description: String,
        /** Permission preset this preset forces; null = leave session as-is. */
        val permission: String?,
    )

    private const val PREFS = "minis_agent_presets"
    private fun sessionKey(sessionId: String) = "session_$sessionId"
    private const val DEFAULT_KEY = "default_for_new_sessions"

    val builtins: List<Preset> = listOf(
        Preset(
            id = "default",
            trust = "system",
            isDefault = true,
            name = "Default",
            description = "默认 Agent 行为：不额外限制执行权限（保持原有行为）",
            permission = null,
        ),
        Preset(
            id = "workspace-sandboxed",
            trust = "system",
            isDefault = false,
            name = "Workspace Sandboxed",
            description = "文件写入仅限工作区（/var/minis/workspace 及 /var/minis/ 会话目录），越界写入被工具门禁拒绝",
            permission = com.openminis.app.tools.SessionPermissionStore.WORKSPACE_WRITE,
        ),
    )

    fun get(id: String): Preset? = builtins.firstOrNull { it.id == id }

    fun isKnownPreset(id: String): Boolean = get(id) != null

    fun list(): List<Preset> = builtins

    /**
     * Apply a preset to one session for real: persist the selection and set the
     * permission gate. Returns the applied preset, or null when unknown.
     */
    fun applyToSession(context: Context, sessionId: String, presetId: String): Preset? {
        val preset = get(presetId) ?: return null
        if (sessionId.isNotBlank()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(sessionKey(sessionId), preset.id).apply()
        }
        // permission == null deliberately clears any earlier preset.
        com.openminis.app.tools.SessionPermissionStore.setPreset(context, sessionId, preset.permission)
        return preset
    }

    /** Persisted preset of one session; null when never selected. */
    fun presetForSession(context: Context, sessionId: String): Preset? {
        if (sessionId.isBlank()) return null
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(sessionKey(sessionId), null) ?: return null
        return get(id)
    }

    fun defaultForNewSessions(context: Context): Preset {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DEFAULT_KEY, "default") ?: "default"
        return get(id) ?: get("default")!!
    }

    fun setDefaultForNewSessions(context: Context, presetId: String): Boolean {
        val preset = get(presetId) ?: return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(DEFAULT_KEY, preset.id).apply()
        return true
    }
}
