package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.MountedFoldersStore
import org.json.JSONArray
import org.json.JSONObject

/** Shared and external-folder inventory for the authenticated Web workbench. */
internal object StorageRpcMethods {

    private fun store(context: Context): MountedFoldersStore =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).mountedFoldersStore

    fun sharedList(): JSONObject = JSONObject().apply {
        put("folders", JSONArray().apply {
            put(shared("shared", "Shared", "/var/minis/shared", writable = true))
            put(shared("skills", "Skills", "/var/minis/skills", writable = false))
            put(shared("memory", "Memory", "/var/minis/memory", writable = false))
        })
    }

    fun mountsList(context: Context): JSONObject {
        val entries = store(context).entries.value
        return JSONObject().apply {
            put("mounts", JSONArray().apply { entries.forEach { put(mountJson(it)) } })
            put("count", entries.size)
            put("capacity", MountedFoldersStore.MAX_MOUNTS)
            put("canAddFromWeb", false)
            put("addRequiresNativePicker", true)
            put("settingsDeepLink", "minis://settings/mount-external")
        }
    }

    suspend fun mountsRename(context: Context, params: JSONObject): JSONObject {
        val id = requiredId(params)
        val name = params.optString("name", "").trim().ifEmpty {
            throw RPCException(-32602, "Missing 'name' param")
        }
        val s = store(context)
        if (!s.rename(id, name)) throw RPCException(-32602, "Invalid/duplicate name or mount not found")
        return JSONObject().put("mount", mountJson(s.entries.value.first { it.id == id }))
    }

    suspend fun mountsSetWritable(context: Context, params: JSONObject): JSONObject {
        val id = requiredId(params)
        if (!params.has("allowWrite")) throw RPCException(-32602, "Missing 'allowWrite' param")
        val s = store(context)
        if (!s.setUserAllowWrite(id, params.optBoolean("allowWrite"))) {
            throw RPCException(-32602, "Mount not found or setting unchanged")
        }
        return JSONObject().put("mount", mountJson(s.entries.value.first { it.id == id }))
    }

    suspend fun mountsRemove(context: Context, params: JSONObject): JSONObject {
        val id = requiredId(params)
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to remove a mounted folder")
        }
        if (!store(context).remove(id)) throw RPCException(-32602, "Mount not found: $id")
        return JSONObject().put("ok", true)
    }

    private fun requiredId(params: JSONObject): String = params.optString("id", "").ifEmpty {
        throw RPCException(-32602, "Missing 'id' param")
    }

    private fun shared(id: String, name: String, path: String, writable: Boolean): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("name", name)
            put("path", path)
            put("writable", writable)
        }

    private fun mountJson(entry: MountedFoldersStore.Entry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("name", entry.name)
        put("sourceDisplayName", entry.sourceDisplayName)
        put("path", "/var/minis/mounts/${entry.name}")
        put("hostPath", entry.resolvedHostPath ?: JSONObject.NULL)
        put("isWritable", entry.isWritable)
        put("userAllowWrite", entry.userAllowWrite)
        put("effectiveWritable", entry.effectiveWritable)
        put("createdAt", entry.createdAt)
    }
}
