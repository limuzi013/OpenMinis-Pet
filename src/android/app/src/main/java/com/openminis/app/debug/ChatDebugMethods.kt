package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.remote.ChatTitleNormalizer
import com.openminis.app.service.SessionActivityTracker
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only `chat.*` RPC handlers — Phase 1.
 *
 * Mirrors the `chat.sessions.*` / `chat.messages.list` / `chat.models.list`
 * shape from `docs/debug-server-api.md`. Mutation handlers (`chat.prompt`,
 * `chat.retry`, `chat.session.cancel`, `chat.session.delete`) land in Phase 2
 * once the headless ChatViewModel runner is wired.
 */
internal object ChatDebugMethods {

    private fun chat(context: Context): ChatRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).chatRepository

    private fun provider(context: Context): ProviderRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).providerRepository

    suspend fun sessionsList(context: Context, params: JSONObject): JSONObject {
        val limit = params.optInt("limit", 50).coerceIn(1, 500)
        val includeEmpty = params.optBoolean("includeEmpty", false)
        val repo = chat(context)

        val all = repo.dao.listSessions()
        val arr = JSONArray()
        var emitted = 0
        val provRepo = provider(context)

        for (session in all) {
            if (emitted >= limit) break
            // Cheap "is empty" probe — load the most recent message preview to
            // gate the includeEmpty filter without joining all messages.
            val lastParts = repo.dao.lastMessageParts(session.id)
            if (!includeEmpty && lastParts == null) continue

            val modelDisplay = resolveModelDisplay(provRepo, session.modelId)
            arr.put(JSONObject().apply {
                put("id", session.id)
                put("title", ChatTitleNormalizer.normalize(session.title))
                put("modelId", session.modelId)
                put("modelName", modelDisplay)
                put("source", session.source ?: JSONObject.NULL)
                put("isRunning", SessionActivityTracker.isActive(session.id))
                put("memoryEnabled", session.memoryEnabled == 1)
                put("category", session.category ?: JSONObject.NULL)
                put("pinnedAt", session.pinnedAt ?: JSONObject.NULL)
                put("lastMessagePreview", session.lastMessage ?: JSONObject.NULL)
                put("createdAt", session.createdAt)
                put("updatedAt", session.updatedAt)
            })
            emitted++
        }
        return JSONObject().apply {
            put("count", emitted)
            put("sessions", arr)
        }
    }

    suspend fun sessionsGet(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val repo = chat(context)
        val session = repo.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val provRepo = provider(context)
        val msgs = repo.dao.loadMessages(sessionId)
        return JSONObject().apply {
            put("id", session.id)
            put("title", ChatTitleNormalizer.normalize(session.title))
            put("modelId", session.modelId)
            put("modelName", resolveModelDisplay(provRepo, session.modelId))
            put("source", session.source ?: JSONObject.NULL)
            put("isRunning", SessionActivityTracker.isActive(session.id))
            put("memoryEnabled", session.memoryEnabled == 1)
            put("category", session.category ?: JSONObject.NULL)
            put("messageCount", msgs.size)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
        }
    }

    suspend fun messagesList(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val limit = params.optInt("limit", 200).coerceIn(1, 1000)
        val offset = params.optInt("offset", 0).coerceAtLeast(0)
        val rolesArr = params.optJSONArray("roles")
        val rolesFilter: Set<String>? = if (rolesArr != null) {
            (0 until rolesArr.length()).map { rolesArr.optString(it) }
                .filter { it.isNotEmpty() }
                .toSet()
                .takeIf { it.isNotEmpty() }
        } else null
        val includeTools = params.optBoolean("includeTools", true)
        val includeReasoning = params.optBoolean("includeReasoning", false)

        val repo = chat(context)
        repo.dao.getSession(sessionId) ?: throw RPCException(-32602, "Session not found")
        val all = repo.dao.loadMessages(sessionId)
        val filtered = if (rolesFilter == null) all else all.filter { it.role in rolesFilter }
        val sliced = filtered.drop(offset).take(limit)

        val arr = JSONArray()
        for (m in sliced) arr.put(messageToJson(m, includeTools, includeReasoning))
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("totalCount", filtered.size)
            put("count", arr.length())
            put("messages", arr)
        }
    }

    private fun messageToJson(m: MessageEntity, includeTools: Boolean, includeReasoning: Boolean): JSONObject {
        val obj = JSONObject().apply {
            put("id", m.id)
            put("role", m.role)
            put("createdAt", m.createdAt)
        }
        // partsJson is a JSON array of {type, value/...} blocks; surface it
        // as-is so callers see what's stored. Pull a flat `content` view for
        // text-only consumption.
        val parts = try { org.json.JSONArray(m.partsJson) } catch (_: Exception) { null }
        if (parts != null) {
            obj.put("parts", parts)
            val sb = StringBuilder()
            val attachments = JSONArray()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                when (p.optString("type")) {
                    "text" -> sb.append(p.optString("value", ""))
                    "image", "file", "attachment" -> attachments.put(p)
                }
            }
            if (sb.isNotEmpty()) obj.put("content", sb.toString())
            if (includeTools) {
                val trajectory = ChatToolTrajectoryNormalizer.collect(parts)
                if (trajectory.toolCalls.length() > 0) obj.put("toolCalls", trajectory.toolCalls)
                if (trajectory.toolResults.length() > 0) obj.put("toolResults", trajectory.toolResults)
            }
            if (attachments.length() > 0) obj.put("attachments", attachments)
        } else {
            // Legacy/unstructured payload — return raw.
            obj.put("content", m.partsJson)
        }
        m.tokenUsage?.let {
            obj.put("tokenUsage", try { JSONObject(it) } catch (_: Exception) { it })
        }
        if (includeReasoning) {
            obj.put("reasoningContent", m.reasoningContent ?: JSONObject.NULL)
        }
        return obj
    }

    suspend fun sessionsUsage(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val perTurn = params.optBoolean("perTurn", false)
        val repo = chat(context)
        repo.dao.getSession(sessionId) ?: throw RPCException(-32602, "Session not found")
        val msgs = repo.dao.loadMessages(sessionId)

        var totalIn = 0L
        var totalOut = 0L
        var totalCacheCreate = 0L
        var totalCacheRead = 0L
        var totalReasoning = 0L
        var turnCount = 0
        val turnsArr = JSONArray()

        for (m in msgs) {
            if (m.role != "assistant") continue
            val raw = m.tokenUsage ?: continue
            val usage = try { JSONObject(raw) } catch (_: Exception) { continue }
            val ti = usage.optLong("inputTokens", usage.optLong("input_tokens", 0))
            val to = usage.optLong("outputTokens", usage.optLong("output_tokens", 0))
            val tcc = usage.optLong("cacheCreationTokens", usage.optLong("cache_creation_tokens", 0))
            val tcr = usage.optLong("cacheReadTokens", usage.optLong("cache_read_tokens",
                usage.optLong("prompt_cache_hit_tokens", 0)))
            val tr = usage.optLong("reasoningTokens", usage.optLong("reasoning_tokens", 0))
            totalIn += ti; totalOut += to; totalCacheCreate += tcc; totalCacheRead += tcr; totalReasoning += tr
            turnCount++
            if (perTurn) {
                turnsArr.put(JSONObject().apply {
                    put("messageId", m.id)
                    put("createdAt", m.createdAt)
                    put("inputTokens", ti)
                    put("outputTokens", to)
                    put("cacheCreationTokens", tcc)
                    put("cacheReadTokens", tcr)
                    put("reasoningTokens", tr)
                })
            }
        }
        val totals = JSONObject().apply {
            put("inputTokens", totalIn)
            put("outputTokens", totalOut)
            put("cacheCreationTokens", totalCacheCreate)
            put("cacheReadTokens", totalCacheRead)
            put("reasoningTokens", totalReasoning)
            put("turnCount", turnCount)
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("totals", totals)
            if (perTurn) put("turns", turnsArr)
        }
    }

    fun modelsList(context: Context, params: JSONObject): JSONObject {
        val includeHidden = params.optBoolean("includeHidden", false)
        val includeDisabled = params.optBoolean("includeDisabled", false)
        val cfg = provider(context).config.value
        val enabledIds = cfg.instances.filter { it.isEnabled || includeDisabled }.map { it.id }.toSet()

        val entriesArr = JSONArray()
        for (entry in cfg.modelEntries) {
            if (entry.providerInstanceId !in enabledIds) continue
            if (!includeHidden && entry.isHidden) continue
            val inst = cfg.instances.find { it.id == entry.providerInstanceId } ?: continue
            val effective = entry.model
            val inputs = effective.inputModalities ?: emptyList()
            entriesArr.put(JSONObject().apply {
                put("id", entry.id)
                put("modelId", effective.id)
                put("modelName", effective.displayName)
                put("providerInstanceId", inst.id)
                put("providerInstanceName", inst.label)
                put("providerType", inst.providerType.name)
                put("supportsReasoning", effective.supportsReasoning ?: JSONObject.NULL)
                put("supportsImages", "image" in inputs)
                put("supportsVideo", "video" in inputs)
                put("supportsAudio", "audio" in inputs)
                put("supportsPDF", "pdf" in inputs)
                put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
                put("maxOutputTokens", effective.maxOutputTokens ?: JSONObject.NULL)
                put("hidden", entry.isHidden)
            })
        }

        val groupsArr = JSONArray()
        for (group in cfg.modelGroups) {
            val ids = JSONArray()
            for (memberId in group.memberEntryIds) ids.put(memberId)
            groupsArr.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("strategy", group.strategy.name)
                put("isDefault", group.id == cfg.defaultPrimaryGroupId)
                put("memberEntryIds", ids)
            })
        }

        return JSONObject().apply {
            put("defaultGroupId", cfg.defaultPrimaryGroupId ?: JSONObject.NULL)
            put("groupCount", groupsArr.length())
            put("entryCount", entriesArr.length())
            put("groups", groupsArr)
            put("entries", entriesArr)
        }
    }

    private fun resolveModelDisplay(repo: ProviderRepository, modelId: String): String {
        val cfg = repo.config.value
        val entry = cfg.modelEntries.firstOrNull { it.baseModel.id == modelId || it.id == modelId }
            ?: return modelId
        return entry.model.displayName
    }
}

/**
 * Gives remote clients one stable tool-event shape while retaining the raw
 * persisted block. Android stores [toolUse]/[toolResult] with their fields
 * nested under `value`; older imported sessions use flat OpenAI-style
 * `tool_call`/`tool_use`/`tool_result` blocks.
 */
internal object ChatToolTrajectoryNormalizer {

    data class Trajectory(
        val toolCalls: JSONArray,
        val toolResults: JSONArray,
    )

    fun collect(parts: JSONArray): Trajectory {
        val toolCalls = JSONArray()
        val toolResults = JSONArray()
        for (i in 0 until parts.length()) {
            val block = parts.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "toolUse", "tool_call", "tool_use" -> toolCalls.put(normalizeCall(block))
                "toolResult", "tool_result" -> toolResults.put(normalizeResult(block))
            }
        }
        return Trajectory(toolCalls = toolCalls, toolResults = toolResults)
    }

    private fun normalizeCall(block: JSONObject): JSONObject {
        val payload = payloadOf(block)
        return normalizedBase(block, kind = "call").apply {
            putAlias(this, "id", payload, block, "id", "toolUseId", "tool_use_id", "toolCallId", "tool_call_id", "callId", "call_id")
            putAlias(this, "toolUseId", payload, block, "toolUseId", "tool_use_id", "toolCallId", "tool_call_id", "callId", "call_id", "id")
            putToolName(this, payload, block)
            putAlias(this, "input", payload, block, "input", "arguments", "args", "parameters")
            putAlias(this, "description", payload, block, "description", "title", "toolTitle", "tool_title")
            putAlias(this, "pageURL", payload, block, "pageURL", "pageUrl", "page_url")
            putAlias(this, "imageFilePath", payload, block, "imageFilePath", "image_file_path")
            putAlias(this, "thoughtSignature", payload, block, "thoughtSignature", "thought_signature")
        }
    }

    private fun normalizeResult(block: JSONObject): JSONObject {
        val payload = payloadOf(block)
        return normalizedBase(block, kind = "result").apply {
            putAlias(this, "id", payload, block, "id", "toolUseId", "tool_use_id", "toolCallId", "tool_call_id", "callId", "call_id")
            putAlias(this, "toolUseId", payload, block, "toolUseId", "tool_use_id", "toolCallId", "tool_call_id", "callId", "call_id", "id")
            putToolName(this, payload, block)
            putAlias(this, "output", payload, block, "output", "content", "result")
            putAlias(this, "snapshot", payload, block, "snapshot")
            putAlias(this, "error", payload, block, "error")

            val isError = booleanValue(firstValue(payload, block, "isError", "is_error"))
            if (isError != null) put("isError", isError)
            val success = booleanValue(firstValue(payload, block, "success"))
            when {
                success != null -> put("success", success)
                isError != null -> put("success", !isError)
            }
        }
    }

    private fun normalizedBase(block: JSONObject, kind: String): JSONObject = JSONObject().apply {
        put("type", block.optString("type"))
        put("kind", kind)
        // Keep both representations available for existing API consumers and
        // for diagnostics of malformed historical records.
        if (block.has("value")) put("value", block.get("value"))
        put("raw", block)
    }

    private fun payloadOf(block: JSONObject): JSONObject = block.optJSONObject("value") ?: block

    private fun putToolName(target: JSONObject, primary: JSONObject, fallback: JSONObject) {
        val name = firstValue(primary, fallback, "name", "toolName", "tool_name") ?: return
        target.put("name", name)
        target.put("toolName", name)
    }

    private fun putAlias(
        target: JSONObject,
        canonicalName: String,
        primary: JSONObject,
        fallback: JSONObject,
        vararg aliases: String,
    ) {
        firstValue(primary, fallback, *aliases)?.let { target.put(canonicalName, it) }
    }

    private fun firstValue(primary: JSONObject, fallback: JSONObject, vararg names: String): Any? {
        for (source in arrayOf(primary, fallback)) {
            for (name in names) {
                if (source.has(name) && !source.isNull(name)) return source.get(name)
            }
        }
        return null
    }

    private fun booleanValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when {
            value.equals("true", ignoreCase = true) || value == "1" -> true
            value.equals("false", ignoreCase = true) || value == "0" -> false
            else -> null
        }
        else -> null
    }
}
