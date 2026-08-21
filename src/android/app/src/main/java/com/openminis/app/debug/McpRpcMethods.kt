package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.MCPRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * `mcp.*` RPC handlers for the Web Remote frontend. All writes use the native
 * repository so Settings, chat and Web observe one configuration file.
 */
internal object McpRpcMethods {

    private fun repo(context: Context): MCPRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).mcpRepository

    fun list(context: Context): JSONObject {
        val servers = repo(context).servers.value
        val arr = JSONArray()
        for (s in servers) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("note", s.note ?: JSONObject.NULL)
                put("enabled", s.enabled)
                put("url", s.url ?: JSONObject.NULL)
                put("command", s.command ?: JSONObject.NULL)
                put("args", JSONArray(s.args))
                put("env", JSONObject(s.env))
                put("headers", JSONObject(s.headers))
                put("startupTimeoutSeconds", s.startupTimeoutSeconds ?: JSONObject.NULL)
                put("createdAt", s.createdAt)
            })
        }
        return JSONObject().put("servers", arr)
    }

    fun get(context: Context, params: JSONObject): JSONObject {
        val serverId = params.optString("serverId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'serverId' param")
        }
        val server = repo(context).servers.value.find { it.id == serverId }
            ?: throw RPCException(-32602, "MCP server not found: $serverId")
        return JSONObject().put("server", serverToJson(server))
    }

    private val VALID_ID = Regex("^[A-Za-z0-9_\\-]{1,128}$")

    fun create(context: Context, params: JSONObject): JSONObject {
        val r = repo(context)
        val id = params.optString("serverId", params.optString("id", "")).trim().ifEmpty {
            throw RPCException(-32602, "Missing 'serverId' param")
        }
        if (!VALID_ID.matches(id)) {
            throw RPCException(-32602, "Server ID must be 1-128 characters: letters, digits, hyphens, underscores")
        }
        if (r.servers.value.any { it.id == id }) {
            throw RPCException(-32602, "MCP server already exists: $id")
        }
        val server = parseServer(params, id = id, current = null)
        if (!r.add(server)) throw RPCException(-32000, "Failed to create MCP server: $id")
        return JSONObject().put("server", serverToJson(server))
    }

    fun update(context: Context, params: JSONObject): JSONObject {
        val r = repo(context)
        val id = params.optString("serverId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'serverId' param")
        }
        val current = r.servers.value.find { it.id == id }
            ?: throw RPCException(-32602, "MCP server not found: $id")
        val server = parseServer(params, id = id, current = current)
        if (!r.update(server)) throw RPCException(-32000, "Failed to update MCP server: $id")
        return JSONObject().put("server", serverToJson(server))
    }

    fun importJson(context: Context, params: JSONObject): JSONObject {
        val configJson = params.optString("configJson", "").ifEmpty {
            throw RPCException(-32602, "Missing 'configJson' param")
        }
        val imported = repo(context).importJSON(configJson)
        if (imported.isEmpty()) throw RPCException(-32602, "No valid MCP servers found in configJson")
        return JSONObject().apply {
            put("count", imported.size)
            put("servers", JSONArray().apply { imported.forEach { put(serverToJson(it)) } })
        }
    }

    fun toggle(context: Context, params: JSONObject): JSONObject {
        val serverId = params.optString("serverId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'serverId' param")
        }
        if (!params.has("enabled")) {
            throw RPCException(-32602, "Missing 'enabled' param")
        }
        val enabled = params.optBoolean("enabled", true)
        val r = repo(context)
        if (r.servers.value.none { it.id == serverId }) {
            throw RPCException(-32602, "MCP server not found: $serverId")
        }
        r.setEnabled(serverId, enabled)
        return JSONObject().put("ok", true)
    }

    fun delete(context: Context, params: JSONObject): JSONObject {
        val serverId = params.optString("serverId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'serverId' param")
        }
        val r = repo(context)
        if (r.servers.value.none { it.id == serverId }) {
            throw RPCException(-32602, "MCP server not found: $serverId")
        }
        r.delete(serverId)
        return JSONObject().put("ok", true)
    }

    private fun parseServer(
        params: JSONObject,
        id: String,
        current: MCPRepository.MCPServerConfig?,
    ): MCPRepository.MCPServerConfig {
        fun optionalString(name: String, fallback: String?): String? =
            if (!params.has(name)) fallback
            else if (params.isNull(name)) null
            else params.optString(name, "").trim().ifEmpty { null }

        val url = optionalString("url", current?.url)
        val command = optionalString("command", current?.command)
        if ((url == null) == (command == null)) {
            throw RPCException(-32602, "Exactly one transport is required: 'url' or 'command'")
        }
        val args = if (params.has("args")) stringList(params.optJSONArray("args")) else current?.args.orEmpty()
        val headers = if (params.has("headers")) stringMap(params.optJSONObject("headers")) else current?.headers.orEmpty()
        val env = if (params.has("env")) stringMap(params.optJSONObject("env")) else current?.env.orEmpty()
        val timeout = if (!params.has("startupTimeoutSeconds")) current?.startupTimeoutSeconds
            else if (params.isNull("startupTimeoutSeconds")) null
            else params.optInt("startupTimeoutSeconds").takeIf { it > 0 }
                ?: throw RPCException(-32602, "startupTimeoutSeconds must be greater than zero")
        return MCPRepository.MCPServerConfig(
            id = id,
            note = optionalString("note", current?.note),
            enabled = if (params.has("enabled")) params.optBoolean("enabled", true) else current?.enabled ?: true,
            url = url,
            headers = headers,
            command = command,
            args = args,
            env = env,
            startupTimeoutSeconds = timeout,
            oauth = current?.oauth,
            createdAt = current?.createdAt ?: System.currentTimeMillis(),
        )
    }

    private fun stringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (key in obj.keys()) out[key] = obj.optString(key, "")
        return out
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    private fun serverToJson(s: MCPRepository.MCPServerConfig): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("note", s.note ?: JSONObject.NULL)
        put("enabled", s.enabled)
        put("url", s.url ?: JSONObject.NULL)
        put("command", s.command ?: JSONObject.NULL)
        put("args", JSONArray(s.args))
        put("env", JSONObject(s.env))
        put("headers", JSONObject(s.headers))
        put("startupTimeoutSeconds", s.startupTimeoutSeconds ?: JSONObject.NULL)
        put("createdAt", s.createdAt)
    }
}
