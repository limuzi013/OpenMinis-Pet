package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.EnvVarRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Authenticated environment-variable management for Web Remote.
 *
 * Values are write-only over RPC: list/get return only `hasValue`, matching
 * provider credential handling. This keeps a tunnel-exposed settings page
 * useful without turning it into a secret-export endpoint.
 */
internal object EnvironmentRpcMethods {

    private fun repo(context: Context): EnvVarRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).envVarRepository

    fun list(context: Context): JSONObject {
        val r = repo(context)
        return JSONObject().put("entries", JSONArray().apply {
            r.entries.value.forEach { entry -> put(entryJson(r, entry)) }
        })
    }

    private val VALID_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,254}$")

    fun create(context: Context, params: JSONObject): JSONObject {
        val key = params.optString("key", "").trim().ifEmpty {
            throw RPCException(-32602, "Missing 'key' param")
        }
        if (!VALID_KEY.matches(key)) {
            throw RPCException(-32602, "Key must be a valid shell identifier (letters, digits, underscores)")
        }
        if (!params.has("value")) throw RPCException(-32602, "Missing 'value' param")
        val r = repo(context)
        if (!r.add(key, params.optString("value", ""), params.optString("note", ""))) {
            throw RPCException(-32602, "Invalid or duplicate environment-variable key")
        }
        val created = r.entries.value.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?: throw RPCException(-32000, "Environment variable was not persisted after add")
        return JSONObject().put("entry", entryJson(r, created))
    }

    fun update(context: Context, params: JSONObject): JSONObject {
        val id = params.optString("id", "").ifEmpty {
            throw RPCException(-32602, "Missing 'id' param")
        }
        val r = repo(context)
        val current = r.entries.value.find { it.id == id }
            ?: throw RPCException(-32602, "Environment variable not found: $id")
        val key = if (params.has("key")) params.optString("key", "") else current.key
        val note = if (params.has("note")) params.optString("note", "") else current.note
        // Missing value means preserve the encrypted value. An explicit empty
        // string clears it, which is useful for keyless endpoints and scripts.
        val value = if (params.has("value")) params.optString("value", "")
            else r.getValue(current.key).orEmpty()
        if (!r.update(id, key, value, note)) {
            throw RPCException(-32602, "Invalid/duplicate key or environment variable no longer exists")
        }
        val updated = r.entries.value.find { it.id == id }
            ?: throw RPCException(-32000, "Environment variable update was not persisted")
        return JSONObject().put("entry", entryJson(r, updated))
    }

    fun delete(context: Context, params: JSONObject): JSONObject {
        val id = params.optString("id", "").ifEmpty {
            throw RPCException(-32602, "Missing 'id' param")
        }
        val r = repo(context)
        if (r.entries.value.none { it.id == id }) {
            throw RPCException(-32602, "Environment variable not found: $id")
        }
        r.delete(id)
        return JSONObject().put("ok", true)
    }

    private fun entryJson(r: EnvVarRepository, entry: EnvVarRepository.EnvVarEntry): JSONObject =
        JSONObject().apply {
            put("id", entry.id)
            put("key", entry.key)
            put("note", entry.note)
            put("createdAt", entry.createdAt)
            put("hasValue", r.getValue(entry.key) != null)
        }
}
