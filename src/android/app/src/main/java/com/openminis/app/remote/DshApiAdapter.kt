package com.openminis.app.remote

import android.content.Context
import com.openminis.app.debug.ChatDebugMethods
import com.openminis.app.debug.ChatMutationMethods
import com.openminis.app.debug.DebugRPCHandler
import com.openminis.app.debug.HeadlessChatRunner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Translates DSH (DeepSeek Harness) wire-protocol RPC calls into OpenMinis
 * backend calls. The DSH browser sends:
 *
 *   POST /api/{method}
 *   { "type": "client-request", "rpcId": "...", "method": "...", "payload": {...} }
 *
 * and expects back:
 *
 *   { "type": "server-response", "rpcId": "...", "result": { "ok": true, "value": {...} } }
 *
 * This adapter performs the translation without modifying the compiled DSH frontend.
 */
object DshApiAdapter {

    fun isDshMethod(path: String): Boolean {
        if (!path.startsWith("/api/")) return false
        val method = path.removePrefix("/api/")
        return method in METHODS
    }

    suspend fun handle(context: Context, method: String, envelope: JSONObject): JSONObject {
        val rpcId = envelope.optString("rpcId", "")
        val payload = envelope.optJSONObject("payload") ?: JSONObject()
        return try {
            val value = dispatch(context, method, payload)
            wrapOk(rpcId, value)
        } catch (e: Exception) {
            wrapError(rpcId, e.message ?: "internal error")
        }
    }

    private suspend fun dispatch(context: Context, method: String, payload: JSONObject): JSONObject {
        return when (method) {
            "session.list" -> sessionList(context)
            "session.create" -> sessionCreate(context, payload)
            "session.history" -> sessionHistory(context, payload)
            "session.models" -> sessionModels(context, payload)
            "session.selectModel" -> sessionSelectModel(context, payload)
            "session.rename" -> sessionRename(context, payload)
            "session.prompt" -> sessionPrompt(context, payload)
            "session.cancel" -> sessionCancel(context, payload)
            "session.search" -> sessionSearch(context, payload)
            "session.attachment" -> JSONObject().put("id", "att_${System.currentTimeMillis()}")
            "session.updateQueue" -> JSONObject().put("accepted", true)
            "session.fork" -> sessionFork(context, payload)
            "host.describe" -> hostDescribe(context)
            "host.listDirectory" -> hostListDirectory(context, payload)
            "host.openPath" -> JSONObject().put("opened", true)
            "host.pickDirectory" -> JSONObject().put("path", "/var/minis/workspace")
            "host.createDirectory" -> JSONObject().put("created", true)
            "workspace.list" -> workspaceList(context)
            "workspace.create" -> workspaceCreate(context, payload)
            "workspace.rename" -> JSONObject().put("renamed", true)
            "workspace.delete" -> JSONObject().put("deleted", true)
            "workspace.insertBefore" -> JSONObject().put("moved", true)
            "workspace.insertSessionBefore" -> JSONObject().put("moved", true)
            "workspace.archiveSession" -> JSONObject().put("archived", true)
            "skill.list" -> skillList(context)
            "agentPreset.list" -> agentPresetList(context)
            "agentPreset.select" -> JSONObject().put("selected", true)
            "agentPreset.read" -> JSONObject()
            "agentPreset.copy" -> JSONObject().put("id", "preset_copy")
            "agentPreset.openDocument" -> JSONObject().put("opened", true)
            "agentPreset.remove" -> JSONObject().put("removed", true)
            "goal.create" -> goalCreate(context, payload)
            "goal.edit" -> goalEdit(context, payload)
            "goal.pause" -> JSONObject().put("paused", true)
            "goal.resume" -> JSONObject().put("resumed", true)
            "goal.complete" -> JSONObject().put("completed", true)
            "goal.clear" -> goalClear(context, payload)
            "settings.describe" -> settingsDescribe(context)
            "settings.openDocument" -> JSONObject().put("opened", true)
            "settings.update" -> JSONObject().put("updated", true)
            "settings.replace" -> JSONObject().put("replaced", true)
            "settings.mutate" -> JSONObject().put("mutated", true)
            "credentials.describe" -> credentialsDescribe(context)
            "credentials.set" -> JSONObject().put("set", true)
            "credentials.unset" -> JSONObject().put("unset", true)
            "llm.providers" -> llmProviders(context)
            "llm.models" -> llmModels(context)
            "llm.discoverModels" -> JSONObject().put("models", JSONArray())
            "subagent.list" -> JSONObject().put("items", JSONArray())
            "subagent.history" -> JSONObject().put("events", JSONArray()).put("hasMore", false)
            "subagent.prompt" -> JSONObject().put("messageId", "sub_${System.currentTimeMillis()}")
            "subagent.interrupt" -> JSONObject().put("interrupted", true)
            else -> throw IllegalArgumentException("unknown method: $method")
        }
    }

    private suspend fun sessionList(context: Context): JSONObject {
        val raw = ChatDebugMethods.sessionsList(
            context, JSONObject().put("limit", 100).put("includeEmpty", true)
        )
        val sessions = raw.optJSONArray("sessions") ?: JSONArray()
        val items = JSONArray()
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            items.put(JSONObject().apply {
                put("sessionId", s.optString("id", s.optString("sessionId")))
                put("updatedAt", s.optLong("updatedAt", System.currentTimeMillis()))
                put("running", s.optBoolean("isRunning", false))
                put("blank", s.optInt("messageCount", 0) == 0)
                val projections = JSONObject().apply {
                    put("asOfSeq", -1)
                    put("values", JSONObject().apply {
                        put("title", s.optString("title", ""))
                        put("tokenUsage", JSONObject().apply {
                            put("inputTokens", 0)
                            put("outputTokens", 0)
                        })
                    })
                }
                put("projections", projections)
            })
        }
        return JSONObject().put("items", items)
    }

    private suspend fun sessionCreate(context: Context, payload: JSONObject): JSONObject {
        val sid = HeadlessChatRunner.ensureSession(context, null)
        return JSONObject().put("sessionId", sid)
    }

    private suspend fun sessionHistory(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        if (sid.isEmpty()) throw IllegalArgumentException("sessionId required")
        val maxMessages = payload.optInt("maxMessages", 500)
        val raw = ChatDebugMethods.messagesList(
            context, JSONObject()
                .put("sessionId", sid)
                .put("limit", maxMessages)
                .put("includeTools", true)
                .put("includeReasoning", true)
        )
        val messages = raw.optJSONArray("messages") ?: JSONArray()
        val events = JSONArray()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val event = messageToDshEvent(msg, i)
            events.put(JSONObject().apply {
                put("event", event)
            })
        }
        val status = ChatMutationMethods.status(context, JSONObject().put("sessionId", sid))
        val projections = JSONObject().apply {
            put("asOfSeq", events.length())
            put("values", JSONObject().apply {
                put("title", status.optString("title", ""))
                put("modelSelection", JSONObject().apply {
                    put("provider", "openminis")
                    put("model", status.optString("modelName", ""))
                })
                put("tokenUsage", JSONObject().apply {
                    put("inputTokens", 0)
                    put("outputTokens", 0)
                })
                put("agentRunning", status.optBoolean("isRunning", false))
            })
        }
        return JSONObject().apply {
            put("events", events)
            put("hasMore", false)
            put("projections", projections)
        }
    }

    private fun messageToDshEvent(msg: JSONObject, seq: Int): JSONObject {
        val role = msg.optString("role", "user")
        val content = JSONArray()
        val text = msg.optString("content", msg.optString("text", ""))
        if (text.isNotEmpty()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        val toolCalls = msg.optJSONArray("toolCalls")
        if (toolCalls != null) {
            for (j in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(j)
                content.put(JSONObject().apply {
                    put("type", "tool_use")
                    put("id", tc.optString("id", "tc_$j"))
                    put("name", tc.optString("name", "unknown"))
                    put("input", tc.optString("arguments", "{}"))
                })
            }
        }
        val toolResults = msg.optJSONArray("toolResults")
        if (toolResults != null) {
            for (j in 0 until toolResults.length()) {
                val tr = toolResults.getJSONObject(j)
                content.put(JSONObject().apply {
                    put("type", "tool_result")
                    put("tool_use_id", tr.optString("callId", "tc_$j"))
                    put("content", tr.optString("output", ""))
                })
            }
        }
        val thinkingContent = msg.optString("reasoning", "")
        if (thinkingContent.isNotEmpty()) {
            val existing = JSONArray()
            existing.put(JSONObject().put("type", "thinking").put("thinking", thinkingContent))
            for (k in 0 until content.length()) existing.put(content.get(k))
            return JSONObject().apply {
                put("type", "message")
                put("seq", seq)
                put("time", msg.optLong("timestamp", System.currentTimeMillis()) / 1000.0)
                put("data", JSONObject().apply {
                    put("id", msg.optString("id", "msg_$seq"))
                    put("role", role)
                    put("content", existing)
                    put("source", JSONObject().put("kind", "minis"))
                })
            }
        }
        return JSONObject().apply {
            put("type", "message")
            put("seq", seq)
            put("time", msg.optLong("timestamp", System.currentTimeMillis()) / 1000.0)
            put("data", JSONObject().apply {
                put("id", msg.optString("id", "msg_$seq"))
                put("role", role)
                put("content", content)
                put("source", JSONObject().put("kind", "minis"))
            })
        }
    }

    private suspend fun sessionModels(context: Context, payload: JSONObject): JSONObject {
        val raw = ChatDebugMethods.modelsList(context, JSONObject())
        val entries = raw.optJSONArray("entries") ?: raw.optJSONArray("models") ?: JSONArray()
        val groups = JSONArray()
        val providerMap = mutableMapOf<String, JSONArray>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val provider = e.optString("instanceName", e.optString("providerType", "default"))
            val models = providerMap.getOrPut(provider) { JSONArray() }
            models.put(JSONObject().apply {
                put("id", e.optString("modelId", e.optString("entryId", "")))
                put("name", e.optString("modelName", e.optString("modelId", "")))
                val reasoning = e.optJSONObject("reasoning")
                if (reasoning != null) put("reasoning", reasoning)
            })
        }
        for ((provider, models) in providerMap) {
            groups.put(JSONObject().apply {
                put("id", provider)
                put("name", provider)
                put("models", models)
            })
        }
        val sid = payload.optString("sessionId")
        var currentModel = ""
        if (sid.isNotEmpty()) {
            try {
                val status = ChatMutationMethods.status(context, JSONObject().put("sessionId", sid))
                currentModel = status.optString("modelName", "")
            } catch (_: Exception) {}
        }
        return JSONObject().apply {
            put("current", JSONObject().apply {
                put("provider", "openminis")
                put("model", currentModel)
            })
            put("routable", true)
            put("groups", groups)
            put("failures", JSONArray())
        }
    }

    private suspend fun sessionSelectModel(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        val model = payload.optString("model")
        val provider = payload.optString("provider", "")
        val effort = payload.optString("reasoningEffort", "")
        val body = JSONObject().put("sessionId", sid).put("entryId", model)
        val result = ChatMutationMethods.selectModel(context, body)
        if (effort.isNotEmpty()) {
            val level = when (effort.lowercase()) {
                "low" -> "low"
                "medium" -> "medium"
                "high" -> "high"
                else -> effort.lowercase()
            }
            try {
                ChatMutationMethods.selectThinkingLevel(
                    context, JSONObject().put("sessionId", sid).put("level", level)
                )
            } catch (_: Exception) {}
        }
        return JSONObject().put("selected", JSONObject().apply {
            put("provider", provider.ifEmpty { "openminis" })
            put("model", result.optString("modelName", model))
            if (effort.isNotEmpty()) put("reasoningEffort", effort)
        })
    }

    private suspend fun sessionRename(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        val title = payload.optString("title", "").trim().take(120)
        if (sid.isEmpty() || title.isEmpty()) throw IllegalArgumentException("sessionId and title required")
        val app = context.applicationContext as com.openminis.app.MinisApp
        app.chatRepository.updateSessionTitle(sid, title)
        return JSONObject().put("title", title).put("seq", 0)
    }

    private suspend fun sessionPrompt(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        val contentParts = payload.optJSONArray("content") ?: JSONArray()
        val textParts = mutableListOf<String>()
        for (i in 0 until contentParts.length()) {
            val part = contentParts.getJSONObject(i)
            if (part.optString("type") == "text") {
                textParts.add(part.optString("text", ""))
            }
        }
        val text = textParts.joinToString("\n")
        val body = JSONObject().apply {
            put("prompt", text)
            put("sessionId", sid)
            put("wait", false)
        }
        ChatMutationMethods.prompt(context, body)
        return JSONObject().put("accepted", true)
    }

    private suspend fun sessionCancel(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        ChatMutationMethods.cancel(context, JSONObject().put("sessionId", sid))
        return JSONObject().put("cancelled", true)
    }

    private suspend fun sessionSearch(context: Context, payload: JSONObject): JSONObject {
        val query = payload.optString("query", "")
        val raw = ChatDebugMethods.sessionsList(
            context, JSONObject().put("limit", 100).put("includeEmpty", false)
        )
        val sessions = raw.optJSONArray("sessions") ?: JSONArray()
        val items = JSONArray()
        val lower = query.lowercase()
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            val title = s.optString("title", "")
            if (title.lowercase().contains(lower)) {
                items.put(JSONObject().apply {
                    put("sessionId", s.optString("id", s.optString("sessionId")))
                    put("snippet", title.take(240))
                })
            }
        }
        return JSONObject().put("items", items).put("hasMore", false)
    }

    private suspend fun sessionFork(context: Context, payload: JSONObject): JSONObject {
        val newSid = HeadlessChatRunner.ensureSession(context, null)
        return JSONObject().put("sessionId", newSid)
    }

    private fun hostDescribe(context: Context): JSONObject {
        return JSONObject().apply {
            put("platform", "android")
            put("version", "1.12-pet.11")
            put("cwd", "/var/minis/workspace")
            put("homeDir", "/var/minis")
            put("hostname", android.os.Build.MODEL)
            put("features", JSONObject().apply {
                put("pickDirectory", false)
                put("openPath", false)
            })
        }
    }

    private suspend fun hostListDirectory(context: Context, payload: JSONObject): JSONObject {
        val path = payload.optString("path", "/var/minis/workspace")
        val sid = payload.optString("sessionId", "")
        val items = JSONArray()
        try {
            val rpc = DebugRPCHandler(context)
            val req = JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", 1)
                put("method", "storage.files.list")
                put("params", JSONObject().put("path", path).apply {
                    if (sid.isNotEmpty()) put("sessionId", sid)
                })
            }
            val raw = JSONObject(rpc.handle(req.toString()))
            val result = raw.optJSONObject("result") ?: JSONObject()
            val files = result.optJSONArray("items") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                items.put(JSONObject().apply {
                    put("name", f.optString("name"))
                    put("isDirectory", f.optBoolean("isDirectory", false))
                    if (!f.optBoolean("isDirectory", false)) {
                        put("size", f.optLong("size", 0))
                    }
                })
            }
        } catch (_: Exception) {}
        return JSONObject().apply {
            put("path", path)
            put("items", items)
        }
    }

    private suspend fun workspaceList(context: Context): JSONObject {
        val items = JSONArray()
        items.put(JSONObject().apply {
            put("id", "default")
            put("name", "OpenMinis 工作区")
            put("path", "/var/minis/workspace")
            put("sessions", JSONArray())
        })
        return JSONObject().put("items", items)
    }

    private suspend fun workspaceCreate(context: Context, payload: JSONObject): JSONObject {
        val name = payload.optString("name", "新工作区")
        return JSONObject().apply {
            put("id", "ws_${System.currentTimeMillis()}")
            put("name", name)
        }
    }

    private suspend fun skillList(context: Context): JSONObject {
        val rpc = DebugRPCHandler(context)
        val req = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", 1)
            put("method", "skills.list"); put("params", JSONObject())
        }
        val raw = JSONObject(rpc.handle(req.toString()))
        val result = raw.optJSONObject("result") ?: JSONObject()
        val skills = result.optJSONArray("skills") ?: JSONArray()
        val items = JSONArray()
        for (i in 0 until skills.length()) {
            val s = skills.getJSONObject(i)
            items.put(JSONObject().apply {
                put("id", s.optString("id"))
                put("name", s.optString("name"))
                put("description", s.optString("description", ""))
                put("enabled", s.optBoolean("enabled", true))
            })
        }
        return JSONObject().put("items", items)
    }

    private fun agentPresetList(context: Context): JSONObject {
        return JSONObject().put("items", JSONArray().put(JSONObject().apply {
            put("id", "default")
            put("name", "OpenMinis Agent")
            put("description", "默认 Agent 配置")
            put("builtin", true)
        }))
    }

    private suspend fun goalCreate(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        val title = payload.optString("title", "")
        val rpc = DebugRPCHandler(context)
        val req = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", 1)
            put("method", "agent.goal.set")
            put("params", JSONObject().put("sessionId", sid).put("title", title))
        }
        rpc.handle(req.toString())
        return JSONObject().put("created", true)
    }

    private suspend fun goalEdit(context: Context, payload: JSONObject): JSONObject {
        return goalCreate(context, payload)
    }

    private suspend fun goalClear(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        val rpc = DebugRPCHandler(context)
        val req = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", 1)
            put("method", "agent.goal.set")
            put("params", JSONObject().put("sessionId", sid).put("title", ""))
        }
        rpc.handle(req.toString())
        return JSONObject().put("cleared", true)
    }

    private fun settingsDescribe(context: Context): JSONObject {
        return JSONObject().apply {
            put("settings", JSONObject().apply {
                put("theme", "system")
                put("language", "zh-CN")
            })
            put("schema", JSONObject())
        }
    }

    private fun credentialsDescribe(context: Context): JSONObject {
        return JSONObject().apply {
            put("credentials", JSONArray())
        }
    }

    private fun llmProviders(context: Context): JSONObject {
        return JSONObject().put("providers", JSONArray().put(JSONObject().apply {
            put("id", "openminis")
            put("name", "OpenMinis")
            put("configured", true)
        }))
    }

    private suspend fun llmModels(context: Context): JSONObject {
        val raw = ChatDebugMethods.modelsList(context, JSONObject())
        val entries = raw.optJSONArray("entries") ?: raw.optJSONArray("models") ?: JSONArray()
        val models = JSONArray()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            models.put(JSONObject().apply {
                put("id", e.optString("modelId", e.optString("entryId", "")))
                put("name", e.optString("modelName", ""))
                put("provider", e.optString("instanceName", "openminis"))
            })
        }
        return JSONObject().put("models", models)
    }

    private fun wrapOk(rpcId: String, value: JSONObject): JSONObject {
        return JSONObject().apply {
            put("type", "server-response")
            put("rpcId", rpcId)
            put("result", JSONObject().apply {
                put("ok", true)
                put("value", value)
            })
        }
    }

    private fun wrapError(rpcId: String, message: String): JSONObject {
        return JSONObject().apply {
            put("type", "server-response")
            put("rpcId", rpcId)
            put("result", JSONObject().apply {
                put("ok", false)
                put("error", JSONObject().apply {
                    put("code", "internal")
                    put("message", message)
                    put("details", JSONObject())
                })
            })
        }
    }

    private val METHODS = setOf(
        "session.list", "session.create", "session.history", "session.models",
        "session.selectModel", "session.rename", "session.fork", "session.prompt",
        "session.attachment", "session.updateQueue", "session.cancel", "session.search",
        "subagent.list", "subagent.history", "subagent.prompt", "subagent.interrupt",
        "host.describe", "host.pickDirectory", "host.listDirectory",
        "host.createDirectory", "host.openPath",
        "workspace.list", "workspace.create", "workspace.rename", "workspace.delete",
        "workspace.insertBefore", "workspace.insertSessionBefore", "workspace.archiveSession",
        "skill.list",
        "agentPreset.list", "agentPreset.select", "agentPreset.read",
        "agentPreset.copy", "agentPreset.openDocument", "agentPreset.remove",
        "goal.create", "goal.edit", "goal.pause", "goal.resume", "goal.complete", "goal.clear",
        "settings.describe", "settings.openDocument", "settings.update",
        "settings.replace", "settings.mutate",
        "credentials.describe", "credentials.set", "credentials.unset",
        "llm.providers", "llm.models", "llm.discoverModels",
    )
}
