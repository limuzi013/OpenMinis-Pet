package com.openminis.app.debug

import android.content.Context
import com.openminis.app.remote.RemoteCapabilityCatalog
import com.openminis.app.remote.RemotePermissionPolicy
import org.json.JSONObject

/**
 * `settings.permissionPreset.*` / `settings.capabilities.*` / `settings.sandbox.get`
 * — Web Remote permission surface. Both the Android settings screen and the
 * Minis Web console read/write through these same SharedPreferences-backed
 * helpers, so any change is immediately visible on the other side.
 */
internal object SettingsRpcMethods {

    fun permissionPresetGet(context: Context, params: JSONObject): JSONObject {
        val preset = RemotePermissionPolicy.preset(context)
        return JSONObject().apply {
            put("preset", preset)
            put("label", labelOf(preset))
            put("danger", preset == RemotePermissionPolicy.PRESET_DANGER_FULL)
        }
    }

    /**
     * Legacy preset API. Applying a preset is a coarse batch (all capabilities
     * reset to the preset's state). Gated by the permission-management
     * capability itself so a Web that turned it off cannot re-enable it.
     */
    fun permissionPresetSet(context: Context, params: JSONObject): JSONObject {
        requirePermissionManage(context, "preset changes")
        val preset = params.optString("preset", "").ifEmpty {
            throw RPCException(-32602, "Missing 'preset' param")
        }
        if (!RemotePermissionPolicy.setPreset(context, preset)) {
            throw RPCException(-32602, "preset must be workspace-write or danger-full-access")
        }
        return JSONObject().apply {
            put("ok", true)
            put("preset", preset)
        }
    }

    /** Full per-capability catalog with current enabled state. */
    fun capabilitiesGet(context: Context, params: JSONObject): JSONObject {
        requirePermissionManage(context, "capability reads")
        val state = RemotePermissionPolicy.capabilityState(context)
        return JSONObject().apply {
            put("capabilities", RemoteCapabilityCatalog.capabilitiesJson(state))
            put("preset", RemotePermissionPolicy.preset(context))
        }
    }

    /**
     * Flip exactly one capability. Only the touched capability changes.
     * When `permission.manage` is off, ALL capability writes are refused —
     * including attempts to turn `permission.manage` back on from the Web.
     */
    fun capabilitiesSet(context: Context, params: JSONObject): JSONObject {
        requirePermissionManage(context, "capability changes")
        val id = params.optString("capability", "").ifEmpty {
            throw RPCException(-32602, "Missing 'capability' param")
        }
        if (!params.has("enabled")) {
            throw RPCException(-32602, "Missing 'enabled' param")
        }
        val enabled = params.optBoolean("enabled", false)
        if (!RemotePermissionPolicy.setCapability(context, id, enabled)) {
            throw RPCException(-32602, "Unknown capability: $id")
        }
        return JSONObject().apply {
            put("ok", true)
            put("capability", id)
            put("enabled", RemotePermissionPolicy.allowsCapability(context, id))
        }
    }

    fun sandboxGet(context: Context, params: JSONObject): JSONObject {
        val info = RemotePermissionPolicy.sandboxInfo(context)
        val state = RemotePermissionPolicy.capabilityState(context)
        return JSONObject().apply {
            for ((k, v) in info) put(k, v)
            put("capabilities", RemoteCapabilityCatalog.capabilitiesJson(state))
        }
    }

    private fun requirePermissionManage(context: Context, what: String) {
        if (!RemotePermissionPolicy.allowsCapability(context, RemoteCapabilityCatalog.PERMISSION_MANAGE)) {
            throw RPCException(-40300, "权限管理已关闭：$what 只能在 Android 手机上操作")
        }
    }

    private fun labelOf(preset: String): String = when (preset) {
        RemotePermissionPolicy.PRESET_WORKSPACE_WRITE -> "Workspace Write"
        RemotePermissionPolicy.PRESET_DANGER_FULL -> "Danger Full Access"
        else -> preset
    }
}
