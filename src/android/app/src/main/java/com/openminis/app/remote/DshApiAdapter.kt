package com.openminis.app.remote

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.MinisApp
import com.openminis.app.data.SessionForkManager
import com.openminis.app.debug.ChatDebugMethods
import com.openminis.app.debug.ChatMutationMethods
import com.openminis.app.debug.DebugRPCHandler
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.tools.MessageFeedbackStore
import com.openminis.app.tools.SessionPermissionStore
import com.openminis.app.ui.chat.SessionEventHub
import com.openminis.app.ui.chat.SessionEventReplay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
 * CRITICAL: the DSH client runs `UNARY_VALUE_SCHEMAS[method].parse(result.value)`
 * (zod) on every response, and `rpcErrorSchema.parse` on every error. A response
 * whose shape does not match the schema throws inside the client and the calling
 * feature dies silently. Every value built here is therefore shaped against the
 * exact schema in
 * `assets/minis/plugins/@deepseek-ai/dsh-client-connection/client.js`; the schema
 * line number is quoted above each builder. Do not "simplify" these shapes.
 *
 * Security: this adapter is reachable from the Web Remote surface, so it must
 * never expose Provider API keys, environment secret values, or the
 * device-control debug RPCs (debug.tap / debug.inputText / debug.screenshot /
 * debug.writeFile). `credentials.describe` deliberately returns only
 * configured/writable metadata — never a value.
 */
object DshApiAdapter {

    /** Absolute path the DSH UI shows as the session working directory. */
    private const val WORKSPACE_PATH = "/var/minis/workspace"
    private const val LEGACY_HISTORY_MESSAGES = 100
    private val hostVersion: String get() = BuildConfig.VERSION_NAME

    /** Small live-only index table required by the browser's array-based chunk fold. */
    private val liveToolBlockIndexes = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    /** Serializes the one-time migration of pre-journal sessions. */
    private val legacyBackfillLocks = ConcurrentHashMap<String, Mutex>()

    fun isDshMethod(path: String): Boolean {
        if (!path.startsWith("/api/")) return false
        val method = path.removePrefix("/api/")
        return method in METHODS
    }

    suspend fun handle(context: Context, method: String, envelope: JSONObject): JSONObject {
        val rpcId = envelope.optString("rpcId", "")
        val payload = envelope.optJSONObject("payload") ?: JSONObject()
        return try {
            wrapOk(rpcId, dispatch(context, method, payload))
        } catch (e: Exception) {
            wrapError(rpcId, e.message ?: "internal error")
        }
    }

    private suspend fun dispatch(context: Context, method: String, payload: JSONObject): Any {
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
            "session.fork" -> sessionFork(context, payload)
            // sessionUpdateQueueValueSchema (client.js:5453) — `{ accepted: true }`.
            "session.updateQueue" -> JSONObject().put("accepted", true)
            // Prompt image parts are accepted directly by session.prompt. This
            // lookup method resolves durable MediaStore refs (MediaRef.id).
            "session.attachment" -> sessionAttachment(context, payload)

            "host.describe" -> hostDescribe(context)
            "host.listDirectory" -> hostListDirectory(context, payload)
            // hostOpenPathValueSchema (client.js:5738) — `{ opened: true }`.
            "host.openPath" -> JSONObject().put("opened", true)
            // hostPickDirectoryValueSchema (client.js:5714) — `{ path: string|null }`.
            // Web cannot drive Android's SAF picker, so this always reports "cancelled".
            "host.pickDirectory" -> JSONObject().put("path", JSONObject.NULL)
            // hostCreateDirectoryValueSchema (client.js:5735) — `{ path: string }`.
            "host.createDirectory" -> hostCreateDirectory(context, payload)

            "workspace.list" -> workspaceList(context)
            "workspace.create" -> workspaceCreate(context, payload)
            "workspace.rename" -> workspaceRename(context, payload)
            "workspace.delete" -> workspaceDelete(context, payload)
            "workspace.insertBefore" -> workspaceInsertBefore(context, payload)
            "workspace.insertSessionBefore" -> workspaceInsertSessionBefore(context, payload)
            "workspace.archiveSession" -> workspaceArchiveSession(context, payload)

            "skill.list" -> skillList(context)

            "agentPreset.list" -> agentPresetList(context)
            // agentPresetSelectValueSchema (client.js:5782) — `{ agentPreset: string }`.
            "agentPreset.select" -> agentPresetSelect(context, payload)
            "agentPreset.read" -> agentPresetRead(context, payload)
            "agentPreset.copy" -> agentPresetCopy(context, payload)
            // agentPresetOpenDocumentValueSchema (client.js:5801) — union; the
            // false branch carries the path the user should open themselves.
            "agentPreset.openDocument" -> JSONObject()
                .put("opened", false)
                .put("path", "$WORKSPACE_PATH/.minis/agents")
            // agentPresetRemoveValueSchema (client.js:5807) — `{}`.
            "agentPreset.remove" -> agentPresetRemove(context, payload)

            "goal.create" -> goalCreate(context, payload)
            "goal.edit" -> goalEdit(context, payload)
            "goal.pause" -> goalSetPhase(payload, "pause")
            "goal.resume" -> goalSetPhase(payload, "resume")
            "goal.complete" -> goalSetPhase(payload, "complete")
            "goal.clear" -> goalClear(context, payload)

            // ---- DSH generic Connection RPC (slash endpoint path) ----
            // Web sends `POST /api/{endpoint}` with a `client-request` envelope
            // whose payload is `{ args: { agentId, line | ref | request | ... } }`
            // (see assets/minis/plugins/@deepseek-ai/dsh-api-remotes/client.js).
            "commands/list" -> commandsList(context, payload)
            "commands/execute" -> commandsExecute(context, payload)

            "messageFeedback/list" -> messageFeedbackList(context, payload)
            "messageFeedback/put" -> messageFeedbackPut(context, payload)
            "messageFeedback/delete" -> messageFeedbackDelete(context, payload)

            "resources/list" -> resourcesList(context, payload)

            "goals/create" -> goalsCreate(context, payload)
            "goals/edit" -> goalsEdit(context, payload)
            "goals/pause" -> goalsSetPhase(payload, "pause")
            "goals/resume" -> goalsSetPhase(payload, "resume")
            "goals/complete" -> goalsSetPhase(payload, "complete")
            "goals/clear" -> goalsClear(context, payload)

            "settings.describe" -> settingsDescribe(context)
            // Android settings do not have a browser-openable backing file.
            "settings.openDocument" -> throw IllegalArgumentException("设置由 Android App 管理，没有可打开的配置文件")
            "settings.update" -> settingsWrite(context, payload, "update")
            "settings.replace" -> settingsWrite(context, payload, "replace")
            "settings.mutate" -> settingsWrite(context, payload, "mutate")

            "credentials.describe" -> credentialsDescribe(context)
            // credentialsSet/UnsetValueSchema (client.js:5947/5950) — `{}`.
            // Writes are refused: the Web surface must not set device secrets.
            "credentials.set", "credentials.unset" ->
                throw IllegalArgumentException("credential changes must be made in the Android app")

            "llm.providers" -> llmProviders(context)
            "llm.models" -> llmModels(context)
            // llmDiscoverModelsValueSchema (client.js:5990) — `{ models: [] }`.
            "llm.discoverModels" -> JSONObject().put("models", JSONArray())

            // subagentListValueSchema (client.js:6024).
            "subagent.list" -> JSONObject()
                .put("entries", JSONArray())
                .put("parentAvailable", false)
            // subagentHistoryValueSchema (client.js:6036).
            "subagent.history" -> JSONObject()
                .put("events", JSONArray())
                .put("hasMore", false)
            "subagent.prompt" -> JSONObject().put("messageId", "sub_${System.currentTimeMillis()}")
            // subagentInterruptValueSchema (client.js:6054) — `{ accepted: true }`.
            "subagent.interrupt" -> JSONObject().put("accepted", true)

            else -> throw IllegalArgumentException("unknown method: $method")
        }
    }

    // ---------------------------------------------------------------- sessions

    /**
     * sessionListValueSchema (client.js:5252) — `{ items: SessionSummary[] }`,
     * where each row is `{ sessionId, updatedAt, running, blank, … }`.
     *
     * `blank` gates the "empty session" placeholder in the sidebar. The backend
     * has no messageCount field; `lastMessagePreview` being absent is the cheap
     * emptiness probe sessionsList already computes.
     */
    private suspend fun sessionList(context: Context): JSONObject {
        val raw = ChatDebugMethods.sessionsList(
            context, JSONObject().put("limit", 200).put("includeEmpty", true)
        )
        val sessions = raw.optJSONArray("sessions") ?: JSONArray()
        val items = JSONArray()
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            items.put(sessionSummary(s))
        }
        return JSONObject().put("items", items)
    }

    private fun sessionSummary(s: JSONObject): JSONObject = JSONObject().apply {
        put("sessionId", s.optString("id", s.optString("sessionId")))
        put("updatedAt", s.optLong("updatedAt", System.currentTimeMillis()))
        put("running", s.optBoolean("isRunning", false))
        put("blank", s.optString("lastMessagePreview", "").isEmpty())
        put("cwd", WORKSPACE_PATH)
        put("projections", projectionsBlock(
            s.optString("title", ""),
            s.optString("modelName", ""),
            s.optString("id", s.optString("sessionId", "")),
        ))
    }

    /**
     * sessionProjectionsBlockSchema (client.js:5350) —
     * `{ asOfSeq: int >= -1, values: Record<string, unknown> }`.
     * `title` is the projection the sidebar reads for a session's label.
     */
    private fun projectionsBlock(
        title: String,
        modelName: String,
        sessionId: String = "",
        stats: JSONObject? = null,
        usage: JSONObject? = null,
    ): JSONObject = JSONObject().apply {
        put("asOfSeq", -1)
        put("values", JSONObject().apply {
            if (title.isNotEmpty()) put("title", title)
            if (modelName.isNotEmpty()) {
                put("modelSelection", JSONObject()
                    .put("provider", "openminis")
                    .put("model", modelName))
            }
            goalProjection(sessionId)?.let { put("goal", it) }
            stats?.let { put("sessionStats", it) }
            usage?.let { put("tokenUsage", it) }
        })
    }

    /** Whole-value goal projection consumed by the stock DSH goal bar. */
    private fun goalProjection(sessionId: String): JSONObject? {
        if (sessionId.isEmpty()) return null
        val goal = com.openminis.app.tools.AgentStateStore.goalGet(sessionId)
        if (goal.text.isBlank() || goal.id.isBlank() || goal.revision <= 0) return null
        return JSONObject().apply {
            put("goal", JSONObject().apply {
                put("id", goal.id)
                put("revision", goal.revision)
                put("objective", goal.text)
                put("phase", goal.phase)
                put("maxGoalRounds", goal.maxGoalRounds)
            })
            put("roundsStarted", 0)
            put("createdAt", goal.createdAt)
            put("updatedAt", goal.updatedAt)
        }
    }

    /** sessionCreateValueSchema (client.js:5269) — `{ sessionId, agentPreset? }`. */
    private suspend fun sessionCreate(context: Context, payload: JSONObject): JSONObject {
        val requestedPreset = payload.optString("agentPreset", "").takeIf { it.isNotEmpty() }
        if (requestedPreset != null && !AgentPresetRegistry.isKnownPreset(requestedPreset)) {
            throw IllegalArgumentException("agent preset not found: $requestedPreset")
        }
        val sid = HeadlessChatRunner.ensureSession(context, null)
        val workspaceId = payload.optString("workspaceId", "")
        if (workspaceId.isNotEmpty()) {
            val app = context.applicationContext as? MinisApp
                ?: throw IllegalStateException("MinisApp is not ready")
            if (app.chatRepository.getFolder(workspaceId) == null) {
                throw IllegalArgumentException("workspace not found")
            }
            app.chatRepository.setFolderForSessions(workspaceId, listOf(sid))
        }
        return JSONObject().apply {
            put("sessionId", sid)
            if (requestedPreset != null) {
                // Validate + persist + apply the preset for real (permission gate).
                AgentPresetRegistry.applyToSession(context, sid, requestedPreset)
                put("agentPreset", requestedPreset)
            } else {
                AgentPresetRegistry.defaultForNewSessions(context).let { preset ->
                    if (preset.id != "default") {
                        AgentPresetRegistry.applyToSession(context, sid, preset.id)
                        put("agentPreset", preset.id)
                    }
                }
            }
        }
    }

    /** sessionForkValueSchema (client.js:5287) — `{ sessionId }`. */
    private suspend fun sessionFork(context: Context, payload: JSONObject): JSONObject {
        val sourceId = payload.optString("sessionId", "").ifEmpty {
            throw IllegalArgumentException("sessionId required")
        }
        val app = context.applicationContext as? MinisApp
            ?: throw IllegalStateException("MinisApp is not ready")
        if (!app.subsystemsReady()) throw IllegalStateException("Minis app data is still starting")
        val newId = SessionForkManager(context, app.chatRepository, app.skillRepository)
            .duplicateSession(sourceId)
            ?: throw IllegalArgumentException("session not found")
        return JSONObject().put("sessionId", newId)
    }

    /**
     * sessionHistoryValueSchema (client.js:5366) —
     * `{ events: HistoryEntry[], hasMore, projections? }` where each entry is
     * `{ event: SessionEvent, view? }`.
     */
    private suspend fun sessionHistory(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        if (sid.isEmpty()) throw IllegalArgumentException("sessionId required")

        // History and the live mux must share the native journal's sequence
        // space. Re-numbering database messages from zero makes the browser
        // treat the first live event as a gap (or as a duplicate) and can wipe
        // an already-rendered turn during its automatic repair fetch.
        val watermark = ensureHistoryJournal(context, sid)
        val retained = if (watermark.oldestAvailableSeq > 0L) {
            HeadlessChatRunner.sessionEvents(context, sid, watermark.oldestAvailableSeq - 1L)
        } else {
            watermark
        }
        val beforeSeq = if (payload.has("beforeSeq") && !payload.isNull("beforeSeq")) {
            payload.optLong("beforeSeq", Long.MAX_VALUE)
        } else {
            Long.MAX_VALUE
        }
        val candidates = retained.events.filter { it.seq < beforeSeq }
        val maxMessages = payload.optInt("maxMessages", 50).coerceIn(1, 500)
        var start = 0
        var messages = 0
        for (index in candidates.indices.reversed()) {
            when (candidates[index].type) {
                "user/message", "system/message", "assistant/message" -> messages++
                "turn/start" -> if (messages >= maxMessages) {
                    start = index
                    break
                }
            }
        }
        val events = JSONArray()
        for (event in candidates.subList(start, candidates.size)) {
            // Pass the host context: imageRefs become durable DSH image
            // blocks only when the translator can reach the MediaStore file
            // (context == null skips them — that path must never be used by
            // history, or refresh loses the images).
            val translated = nativeEventToMuxFrame(sid, event.toEventJson(), context)
                .getJSONObject("event")
            events.put(historyEntry(translated))
        }

        var title = ""
        var modelName = ""
        var running = false
        try {
            val status = ChatMutationMethods.status(context, JSONObject().put("sessionId", sid))
            // 统一 null 归一化：status 不再输出 JSONObject.NULL 标题。
            title = ChatTitleNormalizer.normalize(status.opt("title"))
            modelName = status.optString("modelName", "")
            running = status.optBoolean("isRunning", false)
        } catch (_: Exception) {
        }

        val isTailPage = beforeSeq > watermark.latestSeq
        return JSONObject().apply {
            put("events", events)
            put("hasMore", start > 0)
            if (isTailPage) {
                val usageProjection = tokenUsageProjection(context, sid)
                val statsProjection = sessionStatsProjection(context, sid)
                if (statsProjection != null && usageProjection != null) {
                    // decodeTokens == whole-session output tokens; the journal
                    // usage samples are per-step snapshots, DB rows are the
                    // final authoritative totals.
                    statsProjection.put("decodeTokens", usageProjection.optLong("outputTokens", 0L))
                }
                put("projections", projectionsBlock(
                    title,
                    modelName,
                    sid,
                    stats = statsProjection,
                    usage = usageProjection,
                ).apply {
                    put("asOfSeq", watermark.latestSeq.coerceAtLeast(-1L))
                    optJSONObject("values")?.put("agentRunning", running)
                })
            }
        }
    }

    // ------------------------------------------------------------ projections

    /**
     * DSH `sessionStats` projection — real numbers from the native journal:
     * turns/steps from turn/assistant boundaries, llmMs from placeholder→
     * settled message, TTFT from first token delta, toolMs from tool/call→
     * tool/result, decode from first token → settle. Raw integer values only
     * (the browser formats them; StatsLine computes TTFT avg and tok/s).
     */
    private suspend fun sessionStatsProjection(context: Context, sessionId: String): JSONObject? = try {
        val replay = HeadlessChatRunner.sessionEvents(context, sessionId, null)
        if (replay.events.isEmpty()) null else computeSessionStats(replay.events)
    } catch (_: Exception) {
        null
    }

    /**
     * Pure stats fold over native journal events (JVM-testable). Mirrors the
     * DSH fixture's `sessionStatsOf` semantics using the boundary events the
     * runtime actually emits: assistant/placeholder (LLM start),
     * assistant/chunk first token delta (TTFT), assistant/message (settle),
     * tool/call → tool/result (toolMs), turn/end (turns).
     */
    internal fun computeSessionStats(events: List<com.openminis.app.ui.chat.SessionEvent>): JSONObject {
        var turns = 0
        var steps = 0
        var llmMs = 0L
        var toolMs = 0L
        var ttftMs = 0L
        var ttftSteps = 0
        var decodeMs = 0L
        var decodeTokens = 0L
        var openStepStart: Long? = null
        var firstTokenTime: Long? = null
        val pendingCalls = HashMap<String, Long>()
        for (event in events) {
            val data = event.data()
            when (event.type) {
                "assistant/placeholder" -> {
                    openStepStart = event.time
                    firstTokenTime = null
                }
                "assistant/chunk" -> {
                    if (openStepStart == null || firstTokenTime != null) continue
                    val chunk = data.optJSONObject("chunk") ?: continue
                    val kind = chunk.optString("type")
                    if (kind == "text-delta" || kind == "reasoning-delta") {
                        firstTokenTime = event.time
                    }
                }
                "assistant/message" -> {
                    openStepStart?.let { start ->
                        llmMs += (event.time - start).coerceAtLeast(0L)
                        firstTokenTime?.let { first ->
                            ttftMs += (first - start).coerceAtLeast(0L)
                            ttftSteps++
                            decodeMs += (event.time - first).coerceAtLeast(0L)
                        }
                    }
                    data.optJSONObject("usage")?.optLong("outputTokens", 0L)
                        ?.takeIf { it > 0 }?.let { decodeTokens += it }
                    openStepStart = null
                    firstTokenTime = null
                    steps++
                }
                "tool/call" -> {
                    val call = data.optJSONObject("call") ?: JSONObject()
                    val callId = call.optString("callId", "")
                        .ifEmpty { call.optString("toolUseId", "") }
                        .ifEmpty { call.optString("id", "") }
                    if (callId.isNotEmpty()) pendingCalls[callId] = event.time
                }
                "tool/result" -> {
                    val call = data.optJSONObject("call") ?: JSONObject()
                    val callId = call.optString("callId", "")
                        .ifEmpty { call.optString("toolUseId", "") }
                        .ifEmpty { call.optString("id", "") }
                    pendingCalls.remove(callId)?.let { toolMs += (event.time - it).coerceAtLeast(0L) }
                }
                "turn/end" -> {
                    turns++
                    pendingCalls.clear()
                }
                else -> Unit
            }
        }
        return JSONObject().apply {
            put("turns", turns)
            put("steps", steps)
            put("llmMs", llmMs)
            put("toolMs", toolMs)
            put("ttftMs", ttftMs)
            put("ttftSteps", ttftSteps)
            put("decodeMs", decodeMs)
            put("decodeTokens", decodeTokens)
        }
    }

    /**
     * DSH `tokenUsage` projection — one whole-session aggregate from the
     * authoritative per-message token_usage rows (Room), never from the
     * browser's visible window: `{ uncachedInputTokens, outputTokens,
     * cacheReadTokens, cacheWriteTokens }`.
     */
    private suspend fun tokenUsageProjection(context: Context, sessionId: String): JSONObject? = try {
        val raw = ChatDebugMethods.sessionsUsage(
            context, JSONObject().put("sessionId", sessionId),
        )
        val totals = raw.optJSONObject("totals") ?: return null
        JSONObject().apply {
            put("uncachedInputTokens", totals.optLong("inputTokens", 0L))
            put("outputTokens", totals.optLong("outputTokens", 0L))
            put("cacheReadTokens", totals.optLong("cacheReadTokens", 0L))
            put("cacheWriteTokens", totals.optLong("cacheCreationTokens", 0L))
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Sessions created before the durable event journal existed still have
     * canonical rows in the messages table. Seed a bounded recent tail into
     * the real journal once so old conversations render without inventing a
     * second sequence space. Subsequent live events continue at the journal's
     * next sequence and reconnect paging remains contiguous.
     */
    private suspend fun ensureHistoryJournal(context: Context, sessionId: String): SessionEventReplay {
        val initial = HeadlessChatRunner.sessionEvents(context, sessionId, null)
        if (initial.oldestAvailableSeq > 0L) return initial

        val lock = legacyBackfillLocks.getOrPut(sessionId) { Mutex() }
        return try {
            lock.withLock {
                val current = HeadlessChatRunner.sessionEvents(context, sessionId, null)
                if (current.oldestAvailableSeq > 0L) return@withLock current

                val probe = ChatDebugMethods.messagesList(
                    context,
                    JSONObject()
                        .put("sessionId", sessionId)
                        .put("limit", 1)
                        .put("includeTools", true)
                        .put("includeReasoning", true),
                )
                val total = probe.optInt("totalCount", 0)
                if (total <= 0) return@withLock current
                val raw = if (total == 1) {
                    probe
                } else {
                    ChatDebugMethods.messagesList(
                        context,
                        JSONObject()
                            .put("sessionId", sessionId)
                            .put("offset", (total - LEGACY_HISTORY_MESSAGES).coerceAtLeast(0))
                            .put("limit", LEGACY_HISTORY_MESSAGES)
                            .put("includeTools", true)
                            .put("includeReasoning", true),
                    )
                }
                backfillLegacyMessages(sessionId, raw.optJSONArray("messages") ?: JSONArray())
                HeadlessChatRunner.sessionEvents(context, sessionId, null)
            }
        } finally {
            legacyBackfillLocks.remove(sessionId, lock)
        }
    }

    private fun backfillLegacyMessages(sessionId: String, messages: JSONArray) {
        var turn = 0
        var turnOpen = false

        fun startTurn() {
            if (turnOpen) return
            turn++
            SessionEventHub.append(sessionId, "turn/start", JSONObject().put("turn", turn))
            turnOpen = true
        }

        fun endTurn() {
            if (!turnOpen) return
            SessionEventHub.append(
                sessionId,
                "turn/end",
                JSONObject().put("turn", turn).put("reason", "completed"),
            )
            turnOpen = false
        }

        for (index in 0 until messages.length()) {
            val source = messages.optJSONObject(index) ?: continue
            val role = source.optString("role", "user")
            val messageId = source.optString("id", "legacy_$index")
            val text = source.optString("content", "")

            when (role) {
                "user" -> {
                    endTurn()
                    startTurn()
                    val message = JSONObject()
                        .put("id", messageId)
                        .put("role", "user")
                        .put("content", text)
                    source.optJSONArray("attachments")?.let { message.put("attachments", JSONArray(it.toString())) }
                    // Durable mediaRef parts (persisted image refs) become
                    // DSH imageRefs so legacy backfill also renders images.
                    val refs = JSONArray()
                    source.optJSONArray("parts")?.let { parts ->
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            if (part.optString("type") != "mediaRef") continue
                            val value = part.optJSONObject("value") ?: continue
                            val id = value.optString("id", "")
                            if (id.isEmpty() || !value.optString("mimeType", "").startsWith("image/")) continue
                            refs.put(JSONObject().apply {
                                put("id", id)
                                put("relativePath", value.optString("relativePath", ""))
                                put("mimeType", value.optString("mimeType", ""))
                                value.optString("originalFileName", "").takeIf { it.isNotEmpty() }
                                    ?.let { put("originalFileName", it) }
                            })
                        }
                    }
                    if (refs.length() > 0) message.put("imageRefs", refs)
                    SessionEventHub.append(
                        sessionId,
                        "user/message",
                        JSONObject().put("turn", turn).put("message", message),
                    )
                }
                "system" -> {
                    val message = JSONObject()
                        .put("id", messageId)
                        .put("role", "system")
                        .put("content", text)
                    SessionEventHub.append(sessionId, "system/message", JSONObject().put("message", message))
                }
                "assistant" -> {
                    startTurn()
                    val reasoning = source.optString("reasoningContent", "")
                        .takeUnless { it == "null" }
                        .orEmpty()
                    val calls = source.optJSONArray("toolCalls")?.let { JSONArray(it.toString()) } ?: JSONArray()
                    val results = source.optJSONArray("toolResults")?.let { JSONArray(it.toString()) } ?: JSONArray()
                    val content = legacyAssistantContent(text, reasoning, calls)
                    val data = JSONObject()
                        .put("turn", turn)
                        .put("messageId", messageId)
                        .put("content", text)
                        .put("isStreaming", false)
                        .put("message", JSONObject()
                            .put("id", messageId)
                            .put("role", "assistant")
                            .put("content", content))
                    if (reasoning.isNotEmpty()) data.put("reasoning", reasoning)
                    if (calls.length() > 0) data.put("toolCalls", calls)
                    if (results.length() > 0) data.put("toolResults", results)
                    source.optJSONObject("tokenUsage")?.let { data.put("usage", it) }
                    SessionEventHub.append(sessionId, "assistant/message", data)

                    for (resultIndex in 0 until results.length()) {
                        val result = results.optJSONObject(resultIndex) ?: continue
                        val callId = toolCallId(result, messageId, resultIndex)
                        val name = result.optString("name", result.optString("toolName", "tool"))
                        SessionEventHub.append(
                            sessionId,
                            "tool/result",
                            JSONObject()
                                .put("turn", turn)
                                .put("messageId", messageId)
                                .put("call", JSONObject()
                                    .put("id", callId)
                                    .put("callId", callId)
                                    .put("toolUseId", callId)
                                    .put("name", name))
                                .put("result", JSONObject()
                                    .put("output", result.optString("output", result.optString("content", "")))
                                    .put("success", result.optBoolean("success", !result.optBoolean("isError", false)))
                                    .put("isError", result.optBoolean("isError", !result.optBoolean("success", true)))),
                        )
                    }
                    endTurn()
                }
            }
        }
        endTurn()
    }

    /**
     * Canonical DSH content-block order for a settled assistant message:
     * reasoning first, then text, then tool calls (Think → Answer → tools).
     * Pure so legacy-backfill ordering is JVM-testable.
     */
    internal fun legacyAssistantContent(text: String, reasoning: String, calls: JSONArray): JSONArray =
        JSONArray().apply {
            if (reasoning.isNotEmpty()) put(JSONObject().put("type", "thinking").put("text", reasoning))
            if (text.isNotEmpty()) put(JSONObject().put("type", "text").put("text", text))
            for (callIndex in 0 until calls.length()) {
                val call = calls.optJSONObject(callIndex) ?: continue
                put(JSONObject().put("type", "tool_use").put("value", call))
            }
        }

    /** Tool call/result correlation id, honouring the normalizer's alias set. */
    private fun toolCallId(block: JSONObject, baseId: String, index: Int): String {
        val id = block.optString("toolUseId", "").ifEmpty { block.optString("id", "") }
        return id.ifEmpty { "${baseId}_tc$index" }
    }

    /** Tool arguments as raw text — the normalizer stores them under `input`. */
    private fun rawArguments(call: JSONObject): String {
        if (!call.has("input")) return "{}"
        val value = call.get("input")
        return if (value is String) value else value.toString()
    }

    private fun textBlock(text: String): JSONObject =
        JSONObject().put("type", "text").put("text", text)

    /** Historical Android attachments have no foreign attachment id; keep them visible as notes. */
    private fun appendAttachmentNotes(target: JSONArray, attachments: JSONArray?) {
        if (attachments == null) return
        for (i in 0 until attachments.length()) {
            val attachment = attachments.optJSONObject(i) ?: continue
            val value = attachment.optJSONObject("value") ?: attachment
            val name = value.optString("name", "").ifEmpty {
                value.optString("fileName", "").ifEmpty { "图片 ${i + 1}" }
            }
            target.put(textBlock("[附件：$name]"))
        }
    }

    /** messageSchema (client.js:5568) — role is system|user|assistant only. */
    private fun dshMessage(
        id: String,
        role: String,
        content: JSONArray,
        sourceKind: String,
        callId: String? = null,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role)
        put("content", content)
        put("source", JSONObject().put("kind", sourceKind).apply {
            if (!callId.isNullOrBlank()) put("callId", callId)
        })
    }

    /**
     * sessionEventSchema (client.js:5229). `surfaceOp` is required for a
     * message-producing event to reach the transcript.
     */
    private fun dshEvent(type: String, seq: Long, timeMs: Long, data: JSONObject): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("seq", seq)
            put("time", timeMs)
            put("data", data)
            put("surfaceOp", "append")
        }

    // ------------------------------------------------- live event translation

    /**
     * Translate one native [com.openminis.app.ui.chat.SessionEvent] JSON object
     * into the `session/event` mux-frame payload DSH expects.
     *
     * The native journal already uses DSH's event *names* (`user/message`,
     * `assistant/message`, `tool/result`, the turn boundaries, and the
     * `text-delta` / `reasoning-delta` / `tool-call-delta` chunks, which DSH's
     * `isTokenDelta` recognises verbatim), but the three message-producing
     * events carry a different *payload* shape:
     *
     *  - native `user/message` data is `{ turn, message }`, while
     *    `deriveEventMessage` (client.js:344) reads the message straight off
     *    `data` for that type;
     *  - native message `content` is a plain string for user turns and uses
     *    Anthropic block names (`thinking`, `tool_use`) for assistant turns,
     *    where DSH wants `reasoning` and `tool-call` (client.js:6887);
     *  - none of them carry `source` or `surfaceOp`, and without `surfaceOp`
     *    `isSurfaceEvent` (client.js:10230) drops the event from the transcript.
     *
     * Anything else is passed through unchanged: sessionEventSchema only locks
     * `{ type, seq, time, data }`, so unknown types parse cleanly and DSH
     * ignores the ones it has no fold for.
     */
    fun nativeEventToMuxFrame(sessionId: String, event: JSONObject, context: Context? = null): JSONObject {
        val type = event.optString("type")
        val seq = event.optLong("seq")
        val time = event.optLong("time")
        val data = event.optJSONObject("data") ?: JSONObject()

        val translated: JSONObject? = when (type) {
            "user/message" -> data.optJSONObject("message")?.let { native ->
                val message = nativeMessageToDsh(native, "user", "user", context, sessionId)
                copyTurnStep(data, message)
                dshEvent("user/message", seq, time, message)
            }
            "system/message" -> data.optJSONObject("message")?.let { native ->
                val message = nativeMessageToDsh(native, "system", "system", context, sessionId)
                copyTurnStep(data, message)
                dshEvent("user/message", seq, time, message)
            }
            "assistant/message" -> data.optJSONObject("message")?.let { native ->
                val translatedData = JSONObject().put("message", nativeAssistantMessageToDsh(native))
                copyTurnStep(data, translatedData)
                data.optJSONObject("usage")?.let { translatedData.put("usage", it) }
                liveToolBlockIndexes.remove(liveMessageKey(sessionId, data.optString("messageId", native.optString("id"))))
                dshEvent("assistant/message", seq, time, translatedData)
            }
            "assistant/chunk" -> translateAssistantChunk(sessionId, seq, time, data)
            "tool/call" -> translateToolCall(seq, time, data)
            "tool/result" -> nativeToolResultToDsh(data)?.let { message ->
                val translatedData = JSONObject().put("message", message)
                copyTurnStep(data, translatedData)
                dshEvent("tool/result", seq, time, translatedData)
            }
            "turn/end" -> {
                liveToolBlockIndexes.keys
                    .filter { it.startsWith("$sessionId\u0000") }
                    .forEach(liveToolBlockIndexes::remove)
                val translatedData = JSONObject(data.toString())
                val rawReason = data.opt("reason")
                if (rawReason !is JSONObject) {
                    val kind = rawReason?.toString()?.lowercase()?.let {
                        when {
                            "cancel" in it -> "cancelled"
                            "error" in it || "fail" in it -> "error"
                            else -> "completed"
                        }
                    } ?: "completed"
                    translatedData.put("reason", JSONObject().put("kind", kind))
                }
                plainDshEvent(type, seq, time, translatedData)
            }
            else -> null
        }

        val finalEvent = translated ?: plainDshEvent(type, seq, time, data)
        return JSONObject().apply {
            put("type", "session/event")
            put("sessionId", sessionId)
            put("event", finalEvent)
        }
    }

    private fun plainDshEvent(type: String, seq: Long, time: Long, data: JSONObject): JSONObject =
        JSONObject().put("type", type).put("seq", seq).put("time", time).put("data", data)

    private fun copyTurnStep(source: JSONObject, target: JSONObject) {
        if (source.has("turn")) target.put("turn", source.optInt("turn", 0))
        if (source.has("step")) target.put("step", source.optInt("step", 0))
    }

    private fun translateToolCall(seq: Long, time: Long, data: JSONObject): JSONObject? {
        val call = data.optJSONObject("call") ?: return null
        val callId = call.optString("callId", "")
            .ifEmpty { call.optString("toolUseId", "") }
            .ifEmpty { call.optString("id", "") }
        if (callId.isEmpty()) return null
        val translated = JSONObject()
            .put("callId", callId)
            .put("name", call.optString("name", call.optString("toolName", "tool")))
            .put("arguments", call.optString("toolArgs", "").ifEmpty { rawArguments(call) })
        copyTurnStep(data, translated)
        return plainDshEvent("tool/call", seq, time, translated)
    }

    private fun translateAssistantChunk(
        sessionId: String,
        seq: Long,
        time: Long,
        data: JSONObject,
    ): JSONObject? {
        val chunk = data.optJSONObject("chunk") ?: return null
        val messageId = data.optString("messageId", "")
        val translatedChunk = when (chunk.optString("type")) {
            // DSH canonical chunk order (client.js conversation fold):
            // reasoning block is index 0, text block index 1, tool-call
            // blocks index 2+. The projector compacts sparse blocks, so a
            // text-only message at index 1 needs no fake reasoning fill.
            "text-delta" -> JSONObject()
                .put("type", "text-delta")
                .put("index", 1)
                .put("text", chunk.optString("text", ""))
            "reasoning-delta" -> JSONObject()
                .put("type", "reasoning-delta")
                .put("index", 0)
                .put("text", chunk.optString("text", ""))
            "tool-call-delta" -> {
                val callId = chunk.optString("callId", "")
                    .ifEmpty { chunk.optString("toolUseId", "") }
                if (callId.isEmpty()) return null
                val key = liveMessageKey(sessionId, messageId)
                val byCall = liveToolBlockIndexes.getOrPut(key) { ConcurrentHashMap() }
                val index = byCall.getOrPut(callId) { 2 + byCall.size }
                JSONObject()
                    .put("type", "tool-call-delta")
                    .put("index", index)
                    .put("id", callId)
                    .put("argumentsDelta", chunk.optString("argumentsDelta", chunk.optString("text", "")))
                    .apply {
                        chunk.optString("name", "").takeIf { it.isNotEmpty() }
                            ?.let { put("name", it) }
                    }
            }
            // Streaming tool stdout has its own native terminal result event;
            // the browser assistant-chunk fold has no tool-result-delta arm.
            "tool-result-delta" -> return null
            else -> return null
        }
        val translatedData = JSONObject().put("messageId", messageId).put("chunk", translatedChunk)
        copyTurnStep(data, translatedData)
        return plainDshEvent("assistant/chunk", seq, time, translatedData)
    }

    private fun liveMessageKey(sessionId: String, messageId: String): String =
        "$sessionId\u0000$messageId"

    /** Native user/system message (string content) into a DSH message. */
    private fun nativeMessageToDsh(
        message: JSONObject,
        role: String,
        sourceKind: String,
        context: Context? = null,
        sessionId: String = "",
    ): JSONObject {
        val content = JSONArray()
        when (val raw = if (message.has("content")) message.get("content") else "") {
            is JSONArray -> for (i in 0 until raw.length()) {
                convertContentBlock(raw.optJSONObject(i))?.let { content.put(it) }
            }
            is String -> if (raw.isNotEmpty()) content.put(textBlock(raw))
            else -> {}
        }
        // Canonical MediaRefs first: durable DSH image blocks. Flat-append
        // EVERY block individually — putting the JSONArray itself would nest
        // it as one content element, which DSH's contentParts classifies as
        // an unknown block and renders as the “附加内容块” JSON fallback
        // instead of the image.
        resolveImageRefs(context, sessionId, message)?.let { blocks ->
            appendFlatBlocks(content, blocks)
        }
        // Legacy attachment names only (documents/files) fall back to notes.
        appendAttachmentNotes(content, message.optJSONArray("attachments"))
        return dshMessage(message.optString("id", "msg_$role"), role, content, sourceKind)
    }

    /**
     * Append every block of [blocks] as an INDIVIDUAL content entry.
     * Passing the JSONArray itself would embed it as one nested element:
     * DSH's `contentParts` would classify that array as an unknown block
     * and render the “附加内容块”/Extra content block JSON fallback.
     */
    internal fun appendFlatBlocks(target: JSONArray, blocks: JSONArray) {
        for (i in 0 until blocks.length()) {
            blocks.optJSONObject(i)?.let { target.put(it) }
        }
    }

    /**
     * Build DSH `image` content blocks for a native message's canonical
     * MediaRefs. Each block carries the durable attachment ref
     * `{ attachmentId, mediaType, bytes, width, height, name? }` that
     * `session.attachment` resolves. Context is REQUIRED: callers that omit
     * it silently drop every image block (that was the live/history break).
     */
    internal fun resolveImageRefs(context: Context?, sessionId: String, message: JSONObject): JSONArray? {
        val refs = message.optJSONArray("imageRefs") ?: return null
        if (refs.length() == 0 || context == null) return null
        val blocks = JSONArray()
        for (i in 0 until refs.length()) {
            val ref = refs.optJSONObject(i) ?: continue
            dshImageBlock(context, sessionId, ref)?.let { blocks.put(it) }
        }
        return if (blocks.length() == 0) null else blocks
    }

    /** One DSH image block for a MediaRef; null when the file is unreadable. */
    private fun dshImageBlock(context: Context, sessionId: String, ref: JSONObject): JSONObject? = try {
        val relativePath = ref.optString("relativePath", "")
        if (relativePath.isEmpty()) null
        else {
            val file = File(com.openminis.app.data.storage.MediaStore(context).mediaBaseDir, relativePath)
            if (!file.exists() || !file.isFile) null
            else {
                val attachment = dshImageAttachment(ref.optString("id", ""), file, ref)
                if (attachment == null) null
                else JSONObject().put("type", "image").put("attachment", attachment)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** `imageAttachmentRefSchema` (client.js:5422) — bytes is the file size. */
    private fun dshImageAttachment(attachmentId: String, file: File, ref: JSONObject): JSONObject? {
        if (attachmentId.isEmpty()) return null
        val mediaType = ref.optString("mimeType", "")
            .ifEmpty { guessMimeByName(file.name) ?: "image/png" }
        if (!mediaType.startsWith("image/")) return null
        val bounds = imageBounds(file)
        return imageAttachmentProto(
            attachmentId = attachmentId,
            mediaType = mediaType,
            bytes = file.length(),
            width = bounds?.first ?: 1,
            height = bounds?.second ?: 1,
            name = ref.optString("originalFileName", "").takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Pure `imageAttachmentRefSchema` shape (client.js:5423): every integer
     * is positive, `name` optional. Divorced from Android file/bitmap APIs so
     * JVM tests can pin the wire contract exactly.
     */
    internal fun imageAttachmentProto(
        attachmentId: String,
        mediaType: String,
        bytes: Long,
        width: Int,
        height: Int,
        name: String?,
    ): JSONObject = JSONObject().apply {
        put("attachmentId", attachmentId)
        put("mediaType", mediaType)
        put("bytes", bytes.coerceAtLeast(1L))
        put("width", width.coerceAtLeast(1))
        put("height", height.coerceAtLeast(1))
        if (!name.isNullOrEmpty()) put("name", name)
    }

    private fun guessMimeByName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }

    /** Decode image dimensions without loading the full pixel data. */
    private val imageBoundsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    private fun imageBounds(file: File): Pair<Int, Int>? {
        val key = "${file.absolutePath}:${file.length()}"
        imageBoundsCache[key]?.let { return it }
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                val bounds = opts.outWidth to opts.outHeight
                if (imageBoundsCache.size > 512) imageBoundsCache.clear()
                imageBoundsCache[key] = bounds
                bounds
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Native assistant message: Anthropic block names into DSH block names. */
    private fun nativeAssistantMessageToDsh(message: JSONObject): JSONObject {
        val content = JSONArray()
        when (val raw = if (message.has("content")) message.get("content") else "") {
            is JSONArray -> for (i in 0 until raw.length()) {
                convertContentBlock(raw.optJSONObject(i))?.let { content.put(it) }
            }
            is String -> if (raw.isNotEmpty()) content.put(textBlock(raw))
            else -> {}
        }
        return dshMessage(
            message.optString("id", "assistant"), "assistant", content, "assistant"
        )
    }

    /**
     * Map one native content block onto its DSH counterpart. `thinking` and
     * `tool_use` are the Anthropic spellings the native journal emits;
     * toAssistantBlock (client.js:6887) only knows `reasoning` and `tool-call`.
     */
    private fun convertContentBlock(block: JSONObject?): JSONObject? {
        if (block == null) return null
        return when (block.optString("type")) {
            "text" -> {
                val text = block.optString("text", block.optString("value", ""))
                if (text.isEmpty()) null else textBlock(text)
            }
            "thinking", "reasoning" -> {
                val text = block.optString("text", block.optString("value", ""))
                if (text.isEmpty()) null else {
                    JSONObject().put("type", "reasoning").put("text", text)
                }
            }
            "tool_use", "toolUse", "tool-call" -> {
                val call = block.optJSONObject("value") ?: block
                JSONObject().apply {
                    put("type", "tool-call")
                    put("id", call.optString("toolUseId", "").ifEmpty { call.optString("id", "call") })
                    put("name", call.optString("name", call.optString("toolName", "tool")))
                    put("arguments", call.optString("toolArgs", "").ifEmpty { rawArguments(call) })
                }
            }
            else -> null
        }
    }

    /** Native `tool/result` data `{ messageId, call, result }` into a DSH message. */
    private fun nativeToolResultToDsh(data: JSONObject): JSONObject? {
        val call = data.optJSONObject("call") ?: JSONObject()
        val result = data.optJSONObject("result") ?: JSONObject()
        val callId = call.optString("callId", "")
            .ifEmpty { call.optString("toolUseId", "") }
            .ifEmpty { call.optString("id", "") }
        if (callId.isEmpty()) return null
        val output = result.optString("output", result.optString("content", ""))
        val block = JSONObject().apply {
            put("type", "tool-result")
            put("toolCallId", callId)
            put("content", JSONArray().put(textBlock(output)))
            put("isError", result.optBoolean("isError", !result.optBoolean("success", true)))
        }
        val messageId = data.optString("messageId", "tool").let { "${it}_$callId" }
        return dshMessage(messageId, "user", JSONArray().put(block), "tool", callId)
    }

    /** historyEntrySchema (client.js:5341) — `{ event, view? }`. */
    private fun historyEntry(event: JSONObject): JSONObject =
        JSONObject().put("event", event)

    /**
     * sessionModelsValueSchema (client.js:5373) —
     * `{ current: ModelSelection, routable, groups: ProviderGroup[], failures: [] }`.
     *
     * modelSelectionSchema requires provider and model to be non-empty
     * (`string().min(1)`), so a session with no resolved model still has to
     * report a placeholder rather than "".
     */
    private suspend fun sessionModels(context: Context, payload: JSONObject): JSONObject {
        val raw = ChatDebugMethods.modelsList(context, JSONObject())
        val entries = raw.optJSONArray("entries") ?: JSONArray()

        val byProvider = LinkedHashMap<String, JSONArray>()
        val providerByEntryId = mutableMapOf<String, String>()
        val entryIdByBaseModel = mutableMapOf<String, String>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val provider = e.optString("providerInstanceName", "")
                .ifEmpty { e.optString("providerType", "") }
                .ifEmpty { "OpenMinis" }
            val id = e.optString("id", "").ifEmpty { e.optString("modelId", "") }
            if (id.isEmpty()) continue
            val modelName = e.optString("modelName", "").ifEmpty { id }
            providerByEntryId[id] = provider
            e.optString("modelId", "").takeIf { it.isNotEmpty() }?.let { entryIdByBaseModel[it] = id }
            byProvider.getOrPut(provider) { JSONArray() }.put(modelCatalogEntry(e, id, modelName))
        }

        val groups = JSONArray()
        for ((provider, models) in byProvider) {
            groups.put(JSONObject()
                .put("id", provider)
                .put("name", provider)
                .put("models", models))
        }

        var currentEntryId = ""
        var currentEffort = ""
        val sid = payload.optString("sessionId")
        if (sid.isNotEmpty()) {
            try {
                val session = requireApp(context).chatRepository.getSession(sid)
                val binding = session?.modelBinding?.let { runCatching { JSONObject(it) }.getOrNull() }
                currentEntryId = binding?.takeIf { it.optString("type") == "entry" }
                    ?.optString("entryId", "").orEmpty()
                if (currentEntryId.isEmpty()) {
                    currentEntryId = session?.modelId?.let(entryIdByBaseModel::get).orEmpty()
                }
                // 双向同步：手机端设置的思考强度比“默认 off”更权威。
                // session.thinkingOverride 存的是 enum 名（null = 从未设置 =
                // 实际上处于 off）。
                currentEffort = session?.thinkingOverride?.lowercase().orEmpty()
            } catch (_: Exception) {
            }
        }
        if (currentEntryId.isEmpty()) {
            currentEntryId = byProvider.values.firstOrNull()?.optJSONObject(0)?.optString("id", "").orEmpty()
        }
        return JSONObject().apply {
            put("current", JSONObject()
                .put("provider", providerByEntryId[currentEntryId]
                    ?: byProvider.keys.firstOrNull()
                    ?: "OpenMinis")
                .put("model", currentEntryId.ifEmpty { "unconfigured" })
                .apply {
                    // 只有模型本身支持推理时才回传 effort，避免 schema 校验
                    // 通过但 UI 显示奇怪状态的组合。
                    val reasoning = currentReasoningBlock(entries, currentEntryId)
                    if (reasoning != null && currentEffort.isNotEmpty()) {
                        put("reasoningEffort", currentEffort.takeIf { e ->
                            reasoning.optJSONArray("efforts")?.let { arr ->
                                (0 until arr.length()).any { arr.optJSONObject(it)?.optString("id") == e }
                            } == true
                        }.orEmpty())
                    }
                })
            put("routable", true)
            put("groups", groups)
            put("failures", JSONArray())
        }
    }

    /** One modelCatalogModelSchema row (client.js:5316) with reasoning metadata. */
    private fun modelCatalogEntry(e: JSONObject, id: String, modelName: String): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", modelName)
        put("description", e.optString("modelId", modelName))
        DshReasoningCatalog.reasoningBlock(
            supportsReasoning = e.isNull("supportsReasoning") ?: e.optBoolean("supportsReasoning", false),
            maxCeiling = maxThinkingLevelFor(e),
        )?.let { put("reasoning", it) }
    }

    /** Ceiling is null when unreachable → OFF, so non-reasoning models stay effort-free. */
    private fun maxThinkingLevelFor(e: JSONObject): com.openminis.app.data.model.ThinkingLevel {
        val supports = if (e.isNull("supportsReasoning")) null else e.optBoolean("supportsReasoning", false)
        if (supports == false) return com.openminis.app.data.model.ThinkingLevel.OFF
        // Catalog ceiling is keyed by the BASE model id (family rules match the
        // public model id), and modelsList already exposes that as `modelId`.
        return com.openminis.app.provider.ThinkingLevelCatalog.declaredMaxLevel(
            e.optString("modelId", ""),
        ) ?: com.openminis.app.data.model.ThinkingLevel.XHIGH
    }

    /** Resolve the reasoning block of the entry the session is currently bound to. */
    private fun currentReasoningBlock(entries: JSONArray, entryId: String): JSONObject? {
        if (entryId.isEmpty()) return null
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            if (e.optString("id", "") == entryId) {
                return DshReasoningCatalog.reasoningBlock(
                    supportsReasoning = if (e.isNull("supportsReasoning")) null else e.optBoolean("supportsReasoning", false),
                    maxCeiling = maxThinkingLevelFor(e),
                )
            }
        }
        return null
    }

    /** sessionSelectModelValueSchema (client.js:5386) — `{ selected: ModelSelection }`. */
    private suspend fun sessionSelectModel(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        val model = payload.optString("model")
        val provider = payload.optString("provider", "")
        val effort = payload.optString("reasoningEffort", "")

        val result = ChatMutationMethods.selectModel(
            context, JSONObject().put("sessionId", sid).put("modelEntryId", model)
        )
        if (effort.isNotEmpty()) {
            ChatMutationMethods.selectThinkingLevel(
                context,
                JSONObject().put("sessionId", sid).put("thinkingLevel", effort.lowercase()),
            )
        }
        return JSONObject().put("selected", JSONObject().apply {
            put("provider", provider.ifEmpty { "OpenMinis" })
            put("model", result.optString("modelEntryId", model).ifEmpty { model })
            if (effort.isNotEmpty()) put("reasoningEffort", effort)
        })
    }

    /** sessionRenameValueSchema (client.js:5278) — `{ title: min 1, seq: int >= 0 }`. */
    private suspend fun sessionRename(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        val title = payload.optString("title", "").trim().take(120)
        if (sid.isEmpty() || title.isEmpty()) {
            throw IllegalArgumentException("sessionId and title required")
        }
        val app = context.applicationContext as com.openminis.app.MinisApp
        val repo = app.chatRepositoryOrNull
            ?: throw IllegalStateException("chat subsystem is not ready")
        repo.updateSessionTitle(sid, title)
        return JSONObject().put("title", title).put("seq", 0)
    }

    /** sessionPromptValueSchema (client.js:5413) — `{ accepted: true, command? }`. */
    private suspend fun sessionPrompt(context: Context, payload: JSONObject): JSONObject {
        val sid = payload.optString("sessionId")
        val parts = payload.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        val attachments = JSONArray()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text" -> {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(part.optString("text", ""))
                }
                "image" -> {
                    val data = part.optString("data", "")
                    val mime = part.optString("mediaType", "")
                    if (data.isNotEmpty() && mime.startsWith("image/")) {
                        val extension = when (mime) {
                            "image/jpeg" -> "jpg"
                            "image/webp" -> "webp"
                            "image/gif" -> "gif"
                            else -> "png"
                        }
                        attachments.put(JSONObject()
                            .put("name", part.optString("name", "image-${i + 1}.$extension"))
                            .put("mime", mime)
                            .put("data", data))
                    }
                }
            }
        }
        if (text.isEmpty() && attachments.length() == 0) {
            throw IllegalArgumentException("prompt content required")
        }
        val expanded = expandResourceReferences(context, sid, text.toString())
        val promptText = expanded.ifEmpty { "[图片附件]" }
        val request = JSONObject()
            .put("prompt", promptText)
            .put("sessionId", sid)
            .put("wait", false)
        if (attachments.length() > 0) request.put("attachments", attachments)
        ChatMutationMethods.prompt(context, request)
        return JSONObject().put("accepted", true)
    }

    /**
     * Expand `@` resource references serialized by the OpenMinis
     * minis-input-resources plugin into real context the Agent can read:
     *
     *   `<<minis-file:/var/minis/workspace/a.txt>>`   → file body (≤ 12 KiB)
     *   `<<minis-session:<sessionId>>>`               → recent session tail
     *
     * Anything unreadable keeps its marker (never silently drops a request).
     */
    private suspend fun expandResourceReferences(context: Context, sessionId: String, text: String): String {
        if (!text.contains("<<minis-")) return text
        val fileMarker = "<<minis-file:"
        val sessionMarker = "<<minis-session:"
        var out = text
        while (true) {
            val start = out.indexOf(fileMarker)
            if (start < 0) break
            val end = out.indexOf(">>", start + fileMarker.length)
            if (end < 0) break
            val path = out.substring(start + fileMarker.length, end).trim()
            val body = resolveFileReference(context, sessionId, path)
            out = out.substring(0, start) + body + out.substring(end + 2)
        }
        while (true) {
            val start = out.indexOf(sessionMarker)
            if (start < 0) break
            val end = out.indexOf(">>", start + sessionMarker.length)
            if (end < 0) break
            val sid = out.substring(start + sessionMarker.length, end).trim()
            out = out.substring(0, start) + resolveSessionReference(context, sid) + out.substring(end + 2)
        }
        return out
    }

    private fun resolveFileReference(context: Context, sessionId: String, path: String): String {
        if (path.isBlank()) return "[无法解析的资源引用]"
        return try {
            val resolved = resolveSessionPath(context, sessionId, path)
            if (!resolved.exists() || resolved.isDirectory) "[$path (不可读)]"
            else {
                val body = resolved.readText().take(12_000)
                "[引用文件 $path]\n$body"
            }
        } catch (e: Exception) {
            "[$path (解析失败: ${e.message})]"
        }
    }

    private suspend fun resolveSessionReference(context: Context, sid: String): String {
        if (sid.isBlank()) return "[无法解析的会话引用]"
        return try {
            val replay = HeadlessChatRunner.sessionEvents(context, sid, null)
            val events = replay.events.filter { it.type == "user/message" || it.type == "assistant/message" }.takeLast(8)
            if (events.isEmpty()) return "[引用会话 $sid (无消息)]"
            val builder = StringBuilder("[引用历史会话 $sid]\n")
            for (event in events) {
                val data = event.toEventJson().optJSONObject("data") ?: continue
                val message = data.optJSONObject("message")
                val role = message?.optString("role", "") ?: ""
                val content = message?.opt("content")
                val text = when (content) {
                    is String -> content.take(600)
                    null -> ""
                    else -> runCatching {
                        val arr = content as? org.json.JSONArray ?: return@runCatching ""
                        val parts = (0 until arr.length()).mapNotNull { i ->
                            val block = arr.optJSONObject(i)
                            when (block?.optString("type")) {
                                "text", "thinking" -> block.optString("text", "")
                                else -> null
                            }
                        }
                        parts.joinToString("\n").take(600)
                    }.getOrDefault("")
                }
                if (text.isNotEmpty()) builder.append("- ").append(role).append(": ").append(text).append("\n")
            }
            builder.toString().trim()
        } catch (e: Exception) {
            "[引用会话 $sid (读取失败: ${e.message})]"
        }
    }

    /** sessionCancelValueSchema (client.js:5456) — `{ accepted: true }`. */
    private suspend fun sessionCancel(context: Context, payload: JSONObject): JSONObject {
        ChatMutationMethods.cancel(
            context, JSONObject().put("sessionId", payload.optString("sessionId"))
        )
        return JSONObject().put("accepted", true)
    }

    /**
     * sessionSearchValueSchema (client.js:5255) — snippet is capped at 240
     * Unicode code points and the list at 20 rows by the schema itself.
     */
    private suspend fun sessionSearch(context: Context, payload: JSONObject): JSONObject {
        val query = payload.optString("query", "").trim()
        if (query.isEmpty()) return JSONObject().put("items", JSONArray()).put("hasMore", false)

        val raw = ChatDebugMethods.sessionsList(
            context, JSONObject().put("limit", 200).put("includeEmpty", false)
        )
        val sessions = raw.optJSONArray("sessions") ?: JSONArray()
        val needle = query.lowercase()
        val items = JSONArray()
        var matched = 0
        for (i in 0 until sessions.length()) {
            val s = sessions.optJSONObject(i) ?: continue
            val title = s.optString("title", "")
            val preview = s.optString("lastMessagePreview", "")
            val hit = title.lowercase().contains(needle) || preview.lowercase().contains(needle)
            if (!hit) continue
            matched++
            if (items.length() >= 20) continue
            val snippet = if (title.lowercase().contains(needle)) title else preview
            items.put(JSONObject()
                .put("sessionId", s.optString("id"))
                .put("snippet", takeCodePoints(snippet, 240)))
        }
        return JSONObject().put("items", items).put("hasMore", matched > items.length())
    }

    /** Truncate without splitting a UTF-16 surrogate pair. */
    private fun takeCodePoints(value: String, limit: Int): String {
        if (value.codePointCount(0, value.length) <= limit) return value
        return value.substring(0, value.offsetByCodePoints(0, limit))
    }

    // ------------------------------------------------------------------- host

    /**
     * hostDescribeValueSchema (client.js:5704) —
     * `{ version, cwd, provider?, model?, attachedSessions: int >= 0, canOpenPath }`.
     *
     * This is the first call the DSH shell makes (client.js:99); a shape mismatch
     * here leaves the whole UI on a blank frame, so keep every required key.
     */
    private suspend fun hostDescribe(context: Context): JSONObject {
        var attached = 0
        try {
            val raw = ChatDebugMethods.sessionsList(
                context, JSONObject().put("limit", 500).put("includeEmpty", true)
            )
            attached = raw.optInt("count", raw.optJSONArray("sessions")?.length() ?: 0)
        } catch (_: Exception) {
        }
        return JSONObject().apply {
            put("version", hostVersion)
            put("cwd", WORKSPACE_PATH)
            put("attachedSessions", attached)
            // Opening a path is an Android-side action the Web surface cannot drive.
            put("canOpenPath", false)
        }
    }

    /**
     * hostListDirectoryValueSchema (client.js:5723) —
     * `{ path, home, crumbs: DirectoryEntry[], entries: DirectoryEntry[], truncated }`
     * where DirectoryEntry is `{ name, path, hidden }` (client.js:5716).
     */
    private suspend fun hostListDirectory(context: Context, payload: JSONObject): JSONObject {
        val path = normalizedLinuxPath(payload.optString("path", "").ifEmpty { WORKSPACE_PATH })
        if (path != WORKSPACE_PATH && !path.startsWith("$WORKSPACE_PATH/")) {
            throw IllegalArgumentException("Minis Web 目录选择器仅开放 $WORKSPACE_PATH")
        }
        val entries = JSONArray()
        var truncated = false
        val sessionId = latestSessionId(context)
        if (sessionId != null) {
            val dir = resolveSessionPath(context, sessionId, path)
            if (!dir.exists() || !dir.isDirectory) {
                throw IllegalArgumentException("not a directory: $path")
            }
            val children = dir.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            truncated = children.size > 500
            children.take(500).forEach { child ->
                entries.put(JSONObject()
                    .put("name", child.name)
                    .put("path", "${path.trimEnd('/')}/${child.name}")
                    .put("hidden", child.name.startsWith(".")))
            }
        }
        return JSONObject().apply {
            put("path", path)
            put("home", WORKSPACE_PATH)
            put("crumbs", buildCrumbs(path))
            put("entries", entries)
            put("truncated", truncated)
        }
    }

    private fun buildCrumbs(path: String): JSONArray {
        val crumbs = JSONArray()
        val rootSegments = WORKSPACE_PATH.trim('/').split('/')
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        var acc = WORKSPACE_PATH
        crumbs.put(JSONObject()
            .put("name", rootSegments.last())
            .put("path", WORKSPACE_PATH)
            .put("hidden", false))
        for (segment in segments.drop(rootSegments.size)) {
            acc = "$acc/$segment"
            crumbs.put(JSONObject()
                .put("name", segment)
                .put("path", acc)
                .put("hidden", segment.startsWith(".")))
        }
        return crumbs
    }

    private suspend fun hostCreateDirectory(context: Context, payload: JSONObject): JSONObject {
        val parent = normalizedLinuxPath(payload.optString("path", WORKSPACE_PATH))
        val name = payload.optString("name", "").trim()
        if (name.isEmpty()) throw IllegalArgumentException("directory name required")
        if (name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            throw IllegalArgumentException("invalid directory name")
        }
        val targetPath = "${parent.trimEnd('/')}/$name"
        if (!isWorkspacePath(targetPath) &&
            !RemotePermissionPolicy.allowsCapability(context, RemoteCapabilityCatalog.SANDBOX_FS)
        ) {
            throw IllegalArgumentException("该目录不在工作区内；开启「沙箱任意路径文件访问」能力后才能创建")
        }
        val sessionId = latestSessionId(context)
            ?: throw IllegalArgumentException("create a session before creating a workspace directory")
        val target = resolveSessionPath(context, sessionId, targetPath)
        if (target.exists()) {
            if (!target.isDirectory) throw IllegalArgumentException("a file already exists at $targetPath")
        } else if (!target.mkdirs()) {
            throw IllegalStateException("could not create directory")
        }
        return JSONObject().put("path", targetPath)
    }

    private suspend fun latestSessionId(context: Context): String? {
        val result = ChatDebugMethods.sessionsList(
            context, JSONObject().put("limit", 1).put("includeEmpty", true),
        )
        return result.optJSONArray("sessions")?.optJSONObject(0)?.optString("id", "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizedLinuxPath(raw: String): String {
        if (raw.contains('\\') || raw.contains('\u0000')) throw IllegalArgumentException("invalid path")
        val path = if (raw.startsWith('/')) raw else "/$raw"
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) throw IllegalArgumentException("'..' is not allowed")
        return "/" + parts.filterNot { it == "." }.joinToString("/")
    }

    private fun isWorkspacePath(path: String): Boolean =
        path == WORKSPACE_PATH || path.startsWith("$WORKSPACE_PATH/")

    /** Resolve through the same PRoot mapping while enforcing the real host root. */
    private fun resolveSessionPath(context: Context, sessionId: String, linuxPath: String): File {
        val resolved = PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            ?: throw IllegalArgumentException("cannot resolve path")
        val expectedRoot = if (linuxPath.startsWith("/var/minis/")) {
            val subdir = linuxPath.removePrefix("/var/minis/").substringBefore('/')
            PRootKernel.resolveSessionHostPath(sessionId, "/var/minis/$subdir", context)
                ?: throw IllegalArgumentException("cannot resolve path root")
        } else {
            com.openminis.app.sandbox.RootfsManager.getInstance(context).rootfsDir
        }.canonicalFile
        val canonical = resolved.canonicalFile
        val prefix = expectedRoot.path.trimEnd(File.separatorChar) + File.separator
        if (canonical.path != expectedRoot.path && !canonical.path.startsWith(prefix)) {
            throw IllegalArgumentException("path escapes the Minis workspace")
        }
        return canonical
    }

    // -------------------------------------------------------------- workspaces

    /**
     * DSH workspaces map directly to the App's native conversation groups
     * ([FolderEntity]). Ungrouped sessions remain in DSH's own ungrouped lane,
     * so creating, renaming, dissolving or moving a session is immediately
     * visible in both the Android session list and the browser sidebar.
     */
    private suspend fun workspaceList(context: Context): JSONObject {
        val app = requireApp(context)
        val folders = orderedFolders(context, app.chatRepository.listFolders())
        val sessions = app.chatRepository.dao.listSessions()
        val items = JSONArray()
        folders.forEach { folder -> items.put(workspaceView(folder, sessions)) }
        return JSONObject()
            .put("items", items)
            .put("archivedSessionIds", JSONArray(archivedSessionIds(context).toList()))
    }

    private suspend fun workspaceCreate(context: Context, payload: JSONObject): JSONObject {
        val path = normalizedLinuxPath(payload.optString("path", ""))
        if (path == "/" || path == WORKSPACE_PATH) {
            throw IllegalArgumentException("请选择一个命名的工作区")
        }
        val encodedName = path.substringAfterLast('/').trim()
        val title = runCatching {
            java.net.URLDecoder.decode(encodedName, Charsets.UTF_8.name())
        }.getOrDefault(encodedName).trim().take(120)
        if (title.isEmpty()) throw IllegalArgumentException("工作区名称不能为空")

        val app = requireApp(context)
        val existing = app.chatRepository.findFolderByName(title)
        val folder = existing ?: app.chatRepository.createFolder(title)
        rememberWorkspace(context, folder.id)
        val sessions = app.chatRepository.dao.listSessions()
        return JSONObject()
            .put("workspace", workspaceView(folder, sessions))
            .put("created", existing == null)
    }

    private suspend fun workspaceRename(context: Context, payload: JSONObject): JSONObject {
        val id = requiredWorkspaceId(payload)
        val title = payload.optString("title", "").trim().take(120)
        if (title.isEmpty()) throw IllegalArgumentException("工作区名称不能为空")
        val app = requireApp(context)
        val folder = app.chatRepository.getFolder(id)
            ?: throw IllegalArgumentException("workspace not found")
        val conflict = app.chatRepository.findFolderByName(title)
        if (conflict != null && conflict.id != id) {
            throw IllegalArgumentException("已有同名工作区")
        }
        app.chatRepository.renameFolder(id, title, folder.description)
        val updated = app.chatRepository.getFolder(id)
            ?: throw IllegalStateException("workspace disappeared")
        return JSONObject().put("workspace", workspaceView(updated, app.chatRepository.dao.listSessions()))
    }

    private suspend fun workspaceDelete(context: Context, payload: JSONObject): JSONObject {
        val id = requiredWorkspaceId(payload)
        val app = requireApp(context)
        if (app.chatRepository.getFolder(id) == null) {
            throw IllegalArgumentException("workspace not found")
        }
        app.chatRepository.dissolveFolder(id)
        forgetWorkspace(context, id)
        return JSONObject().put("deleted", true)
    }

    private suspend fun workspaceInsertBefore(context: Context, payload: JSONObject): JSONObject {
        val id = requiredWorkspaceId(payload)
        val app = requireApp(context)
        val folders = app.chatRepository.listFolders()
        if (folders.none { it.id == id }) throw IllegalArgumentException("workspace not found")
        val order = reconciledWorkspaceOrder(context, folders.map { it.id }).toMutableList()
        order.remove(id)
        val before = payload.optString("beforeWorkspaceId", "")
        val index = if (before.isEmpty()) order.size else order.indexOf(before).takeIf { it >= 0 }
            ?: throw IllegalArgumentException("anchor workspace not found")
        order.add(index, id)
        saveWorkspaceOrder(context, order)
        return JSONObject().put("workspaceIds", JSONArray(order))
    }

    private suspend fun workspaceInsertSessionBefore(context: Context, payload: JSONObject): JSONObject {
        val workspaceId = requiredWorkspaceId(payload)
        val sessionId = payload.optString("sessionId", "").ifEmpty {
            throw IllegalArgumentException("sessionId required")
        }
        val app = requireApp(context)
        val folder = app.chatRepository.getFolder(workspaceId)
            ?: throw IllegalArgumentException("workspace not found")
        if (app.chatRepository.getSession(sessionId) == null) {
            throw IllegalArgumentException("session not found")
        }
        app.chatRepository.setFolderForSessions(workspaceId, listOf(sessionId))
        // Android sorts group members by normal session order; moving the
        // membership is durable even though it has no separate manual order.
        return JSONObject().put("workspace", workspaceView(folder, app.chatRepository.dao.listSessions()))
    }

    private suspend fun workspaceArchiveSession(context: Context, payload: JSONObject): JSONObject {
        val sessionId = payload.optString("sessionId", "").ifEmpty {
            throw IllegalArgumentException("sessionId required")
        }
        val app = requireApp(context)
        if (app.chatRepository.getSession(sessionId) == null) {
            throw IllegalArgumentException("session not found")
        }
        val ids = archivedSessionIds(context).toMutableSet().apply { add(sessionId) }
        context.getSharedPreferences("minis_dsh_workspace", Context.MODE_PRIVATE)
            .edit().putStringSet("archived_sessions", ids).apply()
        return JSONObject().put("archivedSessionIds", JSONArray(ids.toList()))
    }

    private fun workspaceView(
        folder: com.openminis.app.data.db.FolderEntity,
        sessions: List<com.openminis.app.data.db.ChatSessionEntity>,
    ): JSONObject = JSONObject().apply {
        put("workspaceId", folder.id)
        put("path", "$WORKSPACE_PATH/groups/${folder.id}")
        put("title", folder.name)
        put("sessionIds", JSONArray(sessions.filter { it.folderId == folder.id }.map { it.id }))
        put("createdAt", iso8601(folder.createdAt))
        put("updatedAt", iso8601(folder.updatedAt))
    }

    private fun requireApp(context: Context): MinisApp =
        context.applicationContext as? MinisApp
            ?: throw IllegalStateException("MinisApp is not ready")

    private fun requiredWorkspaceId(payload: JSONObject): String =
        payload.optString("workspaceId", "").ifEmpty {
            throw IllegalArgumentException("workspaceId required")
        }

    private fun archivedSessionIds(context: Context): Set<String> =
        context.getSharedPreferences("minis_dsh_workspace", Context.MODE_PRIVATE)
            .getStringSet("archived_sessions", emptySet())?.toSet().orEmpty()

    private fun orderedFolders(
        context: Context,
        folders: List<com.openminis.app.data.db.FolderEntity>,
    ): List<com.openminis.app.data.db.FolderEntity> {
        val byId = folders.associateBy { it.id }
        val order = reconciledWorkspaceOrder(context, folders.map { it.id })
        return order.mapNotNull(byId::get)
    }

    private fun reconciledWorkspaceOrder(context: Context, current: List<String>): List<String> {
        val saved = context.getSharedPreferences("minis_dsh_workspace", Context.MODE_PRIVATE)
            .getString("workspace_order", null)
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            .orEmpty()
        val known = current.toSet()
        return saved.filter { it in known }.distinct() + current.filter { it !in saved }
    }

    private fun saveWorkspaceOrder(context: Context, order: List<String>) {
        context.getSharedPreferences("minis_dsh_workspace", Context.MODE_PRIVATE)
            .edit().putString("workspace_order", JSONArray(order).toString()).apply()
    }

    private suspend fun rememberWorkspace(context: Context, id: String) {
        val folders = requireApp(context).chatRepository.listFolders()
        val order = reconciledWorkspaceOrder(context, folders.map { it.id }).toMutableList()
        if (id !in order) order.add(id)
        saveWorkspaceOrder(context, order)
    }

    private fun forgetWorkspace(context: Context, id: String) {
        val prefs = context.getSharedPreferences("minis_dsh_workspace", Context.MODE_PRIVATE)
        val order = prefs.getString("workspace_order", null)
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            .orEmpty().filterNot { it == id }
        prefs.edit().putString("workspace_order", JSONArray(order).toString()).apply()
    }

    private fun iso8601(epochMs: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(epochMs))
    }

    // ------------------------------------------------------------------ skills

    /**
     * skillListValueSchema (client.js:5754) — `{ skills: SkillEntry[] }` where
     * SkillEntry is `{ name: min 1, description, whenToUse?, modelInvocable }`
     * (client.js:5746). Note the key is `skills`, not `items`.
     */
    private suspend fun skillList(context: Context): JSONObject {
        val skills = JSONArray()
        try {
            val rpc = DebugRPCHandler(context)
            val req = JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", 1)
                put("method", "skills.list"); put("params", JSONObject())
            }
            val result = JSONObject(rpc.handle(req.toString())).optJSONObject("result") ?: JSONObject()
            val raw = result.optJSONArray("skills") ?: JSONArray()
            for (i in 0 until raw.length()) {
                val s = raw.optJSONObject(i) ?: continue
                val name = s.optString("name", "").ifEmpty { s.optString("id", "") }
                if (name.isEmpty()) continue
                skills.put(JSONObject().apply {
                    put("name", name)
                    put("description", s.optString("description", ""))
                    put("modelInvocable", s.optBoolean("enabled", true))
                })
            }
        } catch (_: Exception) {
        }
        return JSONObject().put("skills", skills)
    }

    // ----------------------------------------------------------- agent presets

    /**
     * agentPresetListValueSchema (client.js:5772) —
     * `{ presets: AgentPresetEntry[], authorable, hasDocument }` where an entry
     * is `{ id: min 1, trust: "system"|"user", isDefault, name?, description? }`.
     */
    private fun agentPresetList(context: Context): JSONObject = JSONObject().apply {
        val presets = JSONArray()
        val defaultId = try { AgentPresetRegistry.defaultForNewSessions(context).id } catch (_: Exception) { "default" }
        for (preset in AgentPresetRegistry.list()) {
            presets.put(JSONObject().apply {
                put("id", preset.id)
                put("trust", preset.trust)
                put("isDefault", preset.id == defaultId)
                put("name", preset.name)
                put("description", preset.description)
            })
        }
        put("presets", presets)
        put("authorable", false)
        put("hasDocument", false)
    }

    /** agentPresetReadValueSchema (client.js:5785). */
    private fun agentPresetRead(context: Context, payload: JSONObject): JSONObject {
        val id = payload.optString("agentPreset", "default")
        val preset = AgentPresetRegistry.get(id)
        if (preset == null) throw IllegalArgumentException("agent preset not found: $id")
        return JSONObject().apply {
            put("agentPreset", preset.id)
            put("trust", preset.trust)
            put("content", "")
            put("name", preset.name)
        }
    }

    /** agentPresetSelectValueSchema (client.js:5782) — `{ agentPreset: string }`. */
    private fun agentPresetSelect(context: Context, payload: JSONObject): JSONObject {
        val id = payload.optString("agentPreset", "")
        if (id.isEmpty()) throw IllegalArgumentException("agentPreset required")
        val sessionId = payload.optString("sessionId", "")
        if (sessionId.isNotEmpty()) {
            val preset = AgentPresetRegistry.applyToSession(context, sessionId, id)
                ?: throw IllegalArgumentException("agent preset not found: $id")
            return JSONObject().put("agentPreset", preset.id)
        }
        if (!AgentPresetRegistry.setDefaultForNewSessions(context, id)) {
            throw IllegalArgumentException("agent preset not found: $id")
        }
        return JSONObject().put("agentPreset", id)
    }

    /** agentPresetCopyValueSchema (client.js:5796) — `{ agentPreset: string }`. */
    private fun agentPresetCopy(context: Context, payload: JSONObject): JSONObject {
        val id = payload.optString("agentPreset", "")
        if (id.isEmpty()) throw IllegalArgumentException("agentPreset required")
        if (AgentPresetRegistry.get(id) == null) throw IllegalArgumentException("agent preset not found: $id")
        // OpenMinis currently ships system presets only; "copy" would need a
        // user-writable profile store. Refuse honestly instead of faking one.
        throw IllegalArgumentException("自定义预设当前不可用：请在 Android App 内等待后续版本")
    }

    // agentPresetOpenDocumentValueSchema (client.js:5801) — union; the
    // false branch carries the path the user should open themselves.
    // agentPresetRemoveValueSchema (client.js:5807) — `{}`.
    private fun agentPresetRemove(context: Context, payload: JSONObject): JSONObject {
        val id = payload.optString("agentPreset", "")
        if (id.isEmpty()) throw IllegalArgumentException("agentPreset required")
        throw IllegalArgumentException("系统预设不可删除")
    }

    // ------------------------------------------------------------------- goals

    /** DSH goal APIs backed by the same AgentStateStore used by native UI/RPC. */
    private fun goalCreate(context: Context, payload: JSONObject): JSONObject {
        val sessionId = requiredGoalSession(payload)
        val objective = payload.optString("objective", "").trim()
        if (objective.isEmpty()) throw IllegalArgumentException("goal objective required")
        val rounds = payload.optInt("maxGoalRounds", 8).coerceIn(1, 100)
        val goal = com.openminis.app.tools.AgentStateStore.goalSet(sessionId, objective, rounds)
        appendGoalChange(sessionId, "create", goal)
        return goalRef(goal)
    }

    private fun goalEdit(context: Context, payload: JSONObject): JSONObject {
        val sessionId = requiredGoalSession(payload)
        val current = requireCurrentGoal(sessionId, payload)
        val objective = if (payload.has("objective")) {
            payload.optString("objective", "").trim().ifEmpty {
                throw IllegalArgumentException("goal objective cannot be empty")
            }
        } else current.text
        val rounds = if (payload.has("maxGoalRounds")) {
            payload.optInt("maxGoalRounds", current.maxGoalRounds).coerceIn(1, 100)
        } else current.maxGoalRounds
        val goal = com.openminis.app.tools.AgentStateStore.goalSet(sessionId, objective, rounds)
        appendGoalChange(sessionId, "edit", goal)
        return goalRef(goal)
    }

    private fun goalSetPhase(payload: JSONObject, operation: String): JSONObject {
        val sessionId = requiredGoalSession(payload)
        requireCurrentGoal(sessionId, payload)
        val goal = when (operation) {
            "pause" -> com.openminis.app.tools.AgentStateStore.goalSetActive(sessionId, false)
            "resume" -> com.openminis.app.tools.AgentStateStore.goalSetActive(sessionId, true)
            "complete" -> com.openminis.app.tools.AgentStateStore.goalComplete(sessionId)
            else -> throw IllegalArgumentException("unknown goal operation")
        }
        appendGoalChange(sessionId, operation, goal)
        return goalRef(goal)
    }

    /** goalClearValueSchema (client.js:5860) — `{ cleared: true }`. */
    private fun goalClear(context: Context, payload: JSONObject): JSONObject {
        val sessionId = requiredGoalSession(payload)
        val current = requireCurrentGoal(sessionId, payload)
        com.openminis.app.tools.AgentStateStore.goalClear(sessionId)
        SessionEventHub.append(sessionId, "goal/change", JSONObject().apply {
            put("kind", "goal/change")
            put("version", 1)
            put("operation", "clear")
            put("cleared", JSONObject().put("id", current.id).put("revision", current.revision + 1))
            put("clearedAt", System.currentTimeMillis())
        })
        return JSONObject().put("cleared", true)
    }

    private fun requiredGoalSession(payload: JSONObject): String =
        payload.optString("sessionId", "").ifEmpty {
            throw IllegalArgumentException("sessionId required")
        }

    private fun requireCurrentGoal(
        sessionId: String,
        payload: JSONObject,
    ): com.openminis.app.tools.AgentStateStore.Goal {
        val goal = com.openminis.app.tools.AgentStateStore.goalGet(sessionId)
        if (goal.text.isBlank() || goal.id.isBlank()) throw IllegalArgumentException("goal not found")
        payload.optJSONObject("ref")?.let { ref ->
            if (ref.optString("id", "") != goal.id || ref.optInt("revision", 0) != goal.revision) {
                throw IllegalArgumentException("goal was changed; refresh and retry")
            }
        }
        return goal
    }

    private fun goalRef(goal: com.openminis.app.tools.AgentStateStore.Goal): JSONObject =
        JSONObject().put("ref", JSONObject()
            .put("id", goal.id)
            .put("revision", goal.revision.coerceAtLeast(1)))

    private fun appendGoalChange(
        sessionId: String,
        operation: String,
        goal: com.openminis.app.tools.AgentStateStore.Goal,
    ) {
        SessionEventHub.append(sessionId, "goal/change", JSONObject().apply {
            put("kind", "goal/change")
            put("version", 1)
            put("operation", operation)
            put("goal", JSONObject().apply {
                put("id", goal.id)
                put("revision", goal.revision)
                put("objective", goal.text)
                put("phase", goal.phase)
                put("maxGoalRounds", goal.maxGoalRounds)
            })
            put("roundsStarted", 0)
            put("createdAt", goal.createdAt)
            put("updatedAt", goal.updatedAt)
        })
    }

    // ---------------------------------------------------------------- settings

    /**
     * DSH theme, language and permission namespaces are projections over the
     * App's real SharedPreferences/repositories. Both surfaces therefore edit
     * one source of truth rather than maintaining browser-only duplicates.
     */
    private fun settingsDescribe(context: Context): JSONObject = JSONObject().apply {
        put("writable", true)
        put("hasDocument", false)
        put("namespaces", JSONArray().apply {
            put(settingsNamespace(context, "ui-theme"))
            put(settingsNamespace(context, "locale"))
            put(settingsNamespace(context, "permission"))
            put(settingsNamespace(context, "agent-presets"))
            put(settingsNamespace(context, "general"))
        })
    }

    private fun settingsNamespace(context: Context, ns: String): JSONObject {
        val (schema, value, base) = when (ns) {
            "ui-theme" -> {
                val mode = com.openminis.app.ui.settings.getAppearancePrefs(context)
                    .getInt(com.openminis.app.ui.settings.KEY_THEME_MODE, 0)
                val preference = when (mode) { 1 -> "light"; 2 -> "dark"; else -> "system" }
                Triple(themeSettingsSchema(), JSONObject().put("preference", preference), JSONObject().put("preference", "system"))
            }
            "locale" -> {
                val code = com.openminis.app.ui.settings.getAppearancePrefs(context)
                    .getString(com.openminis.app.ui.settings.KEY_LANGUAGE, "").orEmpty()
                val value = JSONObject().apply { if (code == "zh" || code == "en") put("preference", code) }
                Triple(localeSettingsSchema(), value, JSONObject())
            }
            "permission" -> {
                val preset = RemotePermissionPolicy.preset(context)
                Triple(
                    permissionSettingsSchema(),
                    JSONObject().put("defaultPreset", preset),
                    JSONObject().put("defaultPreset", RemotePermissionPolicy.PRESET_WORKSPACE_WRITE),
                )
            }
            "agent-presets" -> {
                val default = AgentPresetRegistry.defaultForNewSessions(context).id
                Triple(
                    agentPresetSettingsSchema(),
                    JSONObject().put("default", default),
                    JSONObject().put("default", "default"),
                )
            }
            "general" -> Triple(
                generalSettingsSchema(),
                JSONObject().apply {
                    put("host", "OpenMinis Pet (Android)")
                    put("version", hostVersion)
                    put("device", android.os.Build.MODEL)
                    put("workspace", WORKSPACE_PATH)
                },
                JSONObject(),
            )
            else -> throw IllegalArgumentException("unknown settings namespace: $ns")
        }
        return JSONObject().apply {
            put("ns", ns)
            put("schema", schema)
            put("value", value)
            put("base", base)
            put("user", JSONObject(value.toString()))
            put("applies", "live")
            put("secrets", JSONArray())
            put("revision", settingsRevision(value))
        }
    }

    private fun settingsWrite(context: Context, payload: JSONObject, mode: String): JSONObject {
        val ns = payload.optString("ns", "").ifEmpty {
            throw IllegalArgumentException("settings namespace required")
        }
        val currentView = settingsNamespace(context, ns)
        val current = currentView.getJSONObject("value")
        if (payload.has("expectedRevision") &&
            payload.optInt("expectedRevision", -1) != currentView.getInt("revision")
        ) {
            throw IllegalArgumentException("settings changed on Android; refresh and retry")
        }
        val next = JSONObject(current.toString())
        when (mode) {
            "update" -> mergeJsonObject(next, payload.optJSONObject("patch") ?: JSONObject())
            "replace" -> {
                val replacement = payload.optJSONObject("section") ?: JSONObject()
                next.keys().asSequence().toList().forEach(next::remove)
                mergeJsonObject(next, replacement)
            }
            "mutate" -> {
                val ops = payload.optJSONArray("ops") ?: JSONArray()
                for (i in 0 until ops.length()) {
                    val op = ops.optJSONObject(i) ?: continue
                    val path = op.optJSONArray("path") ?: JSONArray()
                    if (path.length() != 1) throw IllegalArgumentException("only top-level setting fields are supported")
                    val field = path.optString(0, "")
                    if (field.isEmpty()) throw IllegalArgumentException("setting field required")
                    when (op.optString("op")) {
                        "set" -> next.put(field, op.opt("value"))
                        "unset" -> next.remove(field)
                        else -> throw IllegalArgumentException("unknown settings mutation")
                    }
                }
            }
        }

        when (ns) {
            "ui-theme" -> {
                val preference = next.optString("preference", "system")
                val modeValue = when (preference) {
                    "system" -> 0; "light" -> 1; "dark" -> 2
                    else -> throw IllegalArgumentException("invalid theme preference")
                }
                com.openminis.app.ui.settings.getAppearancePrefs(context).edit()
                    .putInt(com.openminis.app.ui.settings.KEY_THEME_MODE, modeValue).apply()
            }
            "locale" -> {
                val preference = next.optString("preference", "")
                if (preference.isNotEmpty() && preference != "zh" && preference != "en") {
                    throw IllegalArgumentException("invalid locale preference")
                }
                applyAppLocale(context, preference)
            }
            "permission" -> {
                if (!RemotePermissionPolicy.allowsCapability(context, RemoteCapabilityCatalog.PERMISSION_MANAGE)) {
                    throw IllegalArgumentException("权限管理已在 Web 端关闭：要修改权限，请回到 Android 手机设置页操作")
                }
                val preset = next.optString("defaultPreset", "")
                if (!RemoteCapabilityCatalog.isKnownPreset(preset)) {
                    throw IllegalArgumentException("invalid permission preset")
                }
                RemotePermissionPolicy.setPreset(context, preset)
            }
            "agent-presets" -> {
                if (!AgentPresetRegistry.setDefaultForNewSessions(context, next.optString("default", "default"))) {
                    throw IllegalArgumentException("unknown agent preset")
                }
            }
            "general" -> throw IllegalArgumentException("general host metadata is read-only")
            else -> throw IllegalArgumentException("unknown settings namespace: $ns")
        }
        return settingsNamespace(context, ns)
    }

    private fun mergeJsonObject(target: JSONObject, patch: JSONObject) {
        for (key in patch.keys()) target.put(key, patch.opt(key))
    }

    private fun applyAppLocale(context: Context, preference: String) {
        com.openminis.app.ui.settings.getAppearancePrefs(context).edit()
            .putString(com.openminis.app.ui.settings.KEY_LANGUAGE, preference).apply()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(android.app.LocaleManager::class.java)
            manager?.applicationLocales = if (preference.isEmpty()) {
                android.os.LocaleList.getEmptyLocaleList()
            } else {
                android.os.LocaleList.forLanguageTags(preference)
            }
        }
    }

    private fun settingsRevision(value: JSONObject): Int = value.toString().hashCode() and Int.MAX_VALUE

    /** Schemastery JSON envelopes consumed by dsh-client-schema-form. */
    private fun themeSettingsSchema(): JSONObject = JSONObject().apply {
        put("uid", 5)
        put("refs", JSONObject().apply {
            put("1", JSONObject().put("type", "const").put("value", "light"))
            put("2", JSONObject().put("type", "const").put("value", "dark"))
            put("3", JSONObject().put("type", "const").put("value", "system"))
            put("4", JSONObject().put("type", "union").put("list", JSONArray(listOf(1, 2, 3))))
            put("5", JSONObject().put("type", "object").put("dict", JSONObject().put("preference", 4)))
        })
    }

    private fun localeSettingsSchema(): JSONObject = JSONObject().apply {
        put("uid", 4)
        put("refs", JSONObject().apply {
            put("1", JSONObject().put("type", "const").put("value", "zh"))
            put("2", JSONObject().put("type", "const").put("value", "en"))
            put("3", JSONObject().put("type", "union").put("list", JSONArray(listOf(1, 2))))
            put("4", JSONObject().put("type", "object").put("dict", JSONObject().put("preference", 3)))
        })
    }

    private fun permissionSettingsSchema(): JSONObject = JSONObject().apply {
        put("uid", 4)
        put("refs", JSONObject().apply {
            put("1", JSONObject().put("type", "const")
                .put("meta", JSONObject().put("description", "工作区写入"))
                .put("value", RemotePermissionPolicy.PRESET_WORKSPACE_WRITE))
            put("2", JSONObject().put("type", "const")
                .put("meta", JSONObject().put("description", "完整沙箱访问（高风险）"))
                .put("value", RemotePermissionPolicy.PRESET_DANGER_FULL))
            put("3", JSONObject().put("type", "union").put("list", JSONArray(listOf(1, 2))))
            put("4", JSONObject().put("type", "object").put("dict", JSONObject().put("defaultPreset", 3)))
        })
    }

    private fun agentPresetSettingsSchema(): JSONObject = JSONObject().apply {
        put("uid", 3)
        put("refs", JSONObject().apply {
            put("1", JSONObject().put("type", "const")
                .put("meta", JSONObject().put("description", "默认（不额外限制）"))
                .put("value", "default"))
            put("2", JSONObject().put("type", "const")
                .put("meta", JSONObject().put("description", "工作区沙箱（仅限工作区写文件）"))
                .put("value", "workspace-sandboxed"))
            put("3", JSONObject().put("type", "union").put("list", JSONArray(listOf(1, 2))))
            put("4", JSONObject().put("type", "object").put("dict", JSONObject().put("default", 3)))
        })
    }

    private fun singleStringSchema(field: String): JSONObject = JSONObject().apply {
        put("uid", 2)
        put("refs", JSONObject().apply {
            put("1", JSONObject().put("type", "string"))
            put("2", JSONObject().put("type", "object").put("dict", JSONObject().put(field, 1)))
        })
    }

    private fun generalSettingsSchema(): JSONObject = JSONObject().apply {
        put("uid", 2)
        put("refs", JSONObject().apply {
            put("1", JSONObject().put("type", "string"))
            put("2", JSONObject().put("type", "object").put("dict", JSONObject().apply {
                put("host", 1); put("version", 1); put("device", 1); put("workspace", 1)
            }))
        })
    }

    // ------------------------------------------------------------- credentials

    /**
     * credentialsDescribeValueSchema (client.js:5941) —
     * `{ credentials: Record<string, { configured, source?, writable }> }`.
     * Note this is a RECORD keyed by ref name, not an array.
     *
     * Security: only presence metadata crosses this boundary. Provider API keys
     * are never read here, and `writable` is false so the UI does not offer an
     * edit affordance the Web surface must refuse anyway.
     */
    private fun credentialsDescribe(context: Context): JSONObject {
        val credentials = JSONObject()
        try {
            val app = context.applicationContext as com.openminis.app.MinisApp
            val cfg = app.providerRepositoryOrNull?.config?.value
            for (instance in cfg?.instances.orEmpty()) {
                val ref = credentialRef(instance.providerType.name)
                if (credentials.has(ref)) continue
                credentials.put(ref, JSONObject()
                    .put("configured", true)
                    .put("source", "android-keystore")
                    .put("writable", false))
            }
        } catch (_: Exception) {
        }
        return JSONObject().put("credentials", credentials)
    }

    /** credentialRefNameSchema (client.js:5932) — `^[A-Za-z_][A-Za-z0-9_]*$`. */
    private fun credentialRef(providerType: String): String {
        val sanitized = providerType.uppercase().map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
        val head = if (sanitized.firstOrNull()?.isDigit() != false) "_$sanitized" else sanitized
        return "${head}_API_KEY"
    }

    // --------------------------------------------------------------------- llm

    /**
     * llmProvidersValueSchema (client.js:5968) — `{ providers: [...] }` where a
     * row is `{ provider: min 1, displayName: min 1, settingsNs, settingsPath: [],
     * active, declared? }` (client.js:5958).
     */
    private fun llmProviders(context: Context): JSONObject {
        val providers = JSONArray()
        try {
            val app = context.applicationContext as com.openminis.app.MinisApp
            val cfg = app.providerRepositoryOrNull?.config?.value
            for (instance in cfg?.instances.orEmpty()) {
                providers.put(JSONObject().apply {
                    put("provider", instance.id)
                    put("displayName", instance.label.ifEmpty { instance.providerType.name })
                    put("settingsNs", "general")
                    put("settingsPath", JSONArray())
                    put("active", instance.isEnabled)
                    put("declared", true)
                })
            }
        } catch (_: Exception) {
        }
        if (providers.length() == 0) {
            providers.put(JSONObject()
                .put("provider", "openminis")
                .put("displayName", "OpenMinis")
                .put("settingsNs", "general")
                .put("settingsPath", JSONArray())
                .put("active", true))
        }
        return JSONObject().put("providers", providers)
    }

    /**
     * llmModelsValueSchema (client.js:5971) — `{ groups, failures }`, the same
     * provider-group shape session.models uses.
     */
    private suspend fun llmModels(context: Context): JSONObject {
        val models = sessionModels(context, JSONObject())
        return JSONObject()
            .put("groups", models.optJSONArray("groups") ?: JSONArray())
            .put("failures", JSONArray())
    }

    // -------------------------------------------------- DSH generic Remote

    /** Unwrap the generic Remote payload: everything lives under `args`. */
    private fun remoteArgs(payload: JSONObject): Pair<JSONObject, String> {
        val args = payload.optJSONObject("args") ?: JSONObject()
        val agentId = args.optString("agentId", "").ifEmpty {
            args.optString("sessionId", "")
        }
        return args to agentId
    }

    // ------------------------------------------------------- host commands

    /**
     * commands/list value schema (dsh-api-remotes client.js:4291) — a bare
     * `array[{ name, description, input?: { hint } }]`. This is the single
     * host command directory consumed by dsh-client-ui-commands; every entry
     * must map to a real handler below (nothing is listed as a menu-only
     * stub).
     */
    private suspend fun commandsList(context: Context, payload: JSONObject): JSONArray {
        val (_, agentId) = remoteArgs(payload)
        val list = JSONArray()
        for (desc in hostCommandDirectory(context, agentId)) {
            val entry = JSONObject()
                .put("name", desc.name)
                .put("description", desc.description)
            desc.hint?.let { entry.put("input", JSONObject().put("hint", it)) }
            list.put(entry)
        }
        return list
    }

    private data class CommandDescriptor(val name: String, val description: String, val hint: String? = null)

    private fun hostCommandDirectory(context: Context, agentId: String): List<CommandDescriptor> {
        val commands = mutableListOf(
            CommandDescriptor("model", "查看或切换当前会话的模型与思考强度", "可选: 模型 id，或 `off`/`low`/`medium`/`high` 设置思考强度"),
            CommandDescriptor("permission", "切换当前会话的 Agent 执行权限预设", "workspace-write | danger-full-access"),
            CommandDescriptor("goal", "查看或设置当前会话的 Goal", "可选: 目标描述文本"),
            CommandDescriptor("feedback", "查看当前会话的消息反馈状态"),
            CommandDescriptor("export", "导出当前会话的 JSON 日志"),
        )
        return commands
    }

    /**
     * commands/execute value schema (dsh-api-remotes client.js:4279) —
     * `undefined | { commandId, result: { kind: "success"|"error", text? } }`.
     * `undefined` (value omitted) means "not admitted"; the composer echoes the
     * line as an error. The host also appends durable `command/run` +
     * `command/done` mux events so the outcome renders as a flow node.
     */
    private suspend fun commandsExecute(context: Context, payload: JSONObject): Any {
        val (args, agentId) = remoteArgs(payload)
        val line = args.optString("line", "").trim()
        if (agentId.isBlank() || line.isEmpty()) {
            throw IllegalArgumentException("sessionId and line are required")
        }
        if (!line.startsWith("/")) {
            throw IllegalArgumentException("command must start with '/'")
        }
        val parsed = parseCommandLine(line)
        val descriptor = hostCommandDirectory(context, agentId).firstOrNull { it.name == parsed.name }
            ?: return JSONObject.NULL // unknown command → value omitted → client echoes error

        val commandId = "cmd_${System.currentTimeMillis()}_${parsed.name}"
        val runEvent = SessionEventHub.append(agentId, "command/run", JSONObject().apply {
            put("commandId", commandId)
            put("name", parsed.name)
            if (parsed.arg.isNotEmpty()) put("args", parsed.arg)
        })

        val outcome = executeHostCommand(context, agentId, parsed.name, parsed.arg)
        val doneEvent = SessionEventHub.append(agentId, "command/done", JSONObject().apply {
            put("commandId", commandId)
            put("kind", if (outcome.ok) "success" else "error")
            if (outcome.text.isNotEmpty()) put("text", outcome.text)
        })

        return JSONObject().apply {
            put("commandId", commandId)
            put("result", JSONObject().apply {
                put("kind", if (outcome.ok) "success" else "error")
                if (outcome.text.isNotEmpty()) put("text", outcome.text)
                if (outcome.ok) {
                    val seq = doneEvent?.seq?.takeIf { it >= 0 }
                    if (seq != null) put("sourceEventSeq", seq)
                }
            })
        }
    }

    private data class ParsedCommand(val name: String, val arg: String)

    private fun parseCommandLine(line: String): ParsedCommand {
        val body = line.removePrefix("/").trim()
        val space = body.indexOfFirst { it == ' ' || it == '\t' }
        return if (space < 0) {
            ParsedCommand(body.lowercase(), "")
        } else {
            ParsedCommand(body.substring(0, space).lowercase(), body.substring(space + 1).trim())
        }
    }

    private data class CommandOutcome(val ok: Boolean, val text: String = "")

    private suspend fun executeHostCommand(
        context: Context,
        sessionId: String,
        name: String,
        arg: String,
    ): CommandOutcome = try {
        when (name) {
            "model" -> commandModel(context, sessionId, arg)
            "permission" -> commandPermission(context, sessionId, arg)
            "goal" -> commandGoal(context, sessionId, arg)
            "feedback" -> CommandOutcome(true, commandFeedbackText(context, sessionId))
            "export" -> CommandOutcome(true, commandExport(context, sessionId))
            else -> CommandOutcome(false, "unknown command: /$name")
        }
    } catch (e: Exception) {
        CommandOutcome(false, e.message ?: "command failed")
    }

    /** `/model` — read the session's current model, or apply model/effort. */
    private suspend fun commandModel(context: Context, sessionId: String, arg: String): CommandOutcome {
        if (arg.isEmpty()) {
            val status = runCatching {
                ChatMutationMethods.status(context, JSONObject().put("sessionId", sessionId))
            }.getOrNull()
            val model = status?.optString("modelName", "").orEmpty()
            val level = status?.optString("thinkingLevel", "").orEmpty()
            return CommandOutcome(true, "当前模型: ${model.ifEmpty { "(未设置)" }}${if (level.isNotEmpty()) " · 思考强度: $level" else ""}")
        }
        val effort = when (arg.lowercase()) {
            "off", "low", "medium", "high", "xhigh" -> arg.lowercase()
            else -> null
        }
        if (effort != null) {
            ChatMutationMethods.selectThinkingLevel(
                context,
                JSONObject().put("sessionId", sessionId).put("thinkingLevel", effort),
            )
            return CommandOutcome(true, "思考强度已切换为 $effort")
        }
        val result = ChatMutationMethods.selectModel(
            context,
            JSONObject().put("sessionId", sessionId).put("modelEntryId", arg),
        )
        val chosen = result.optString("modelEntryId", arg).ifEmpty { arg }
        return CommandOutcome(true, "模型已切换: $chosen")
    }

    /** `/permission <preset>` — real per-session Agent permission switch. */
    private fun commandPermission(context: Context, sessionId: String, arg: String): CommandOutcome {
        val preset = arg.lowercase()
        if (preset.isNotEmpty() && !SessionPermissionStore.isKnownPreset(preset)) {
            return CommandOutcome(false, "未知权限预设: $preset (可用: workspace-write | danger-full-access)")
        }
        val effective = preset.ifEmpty { SessionPermissionStore.preset(context, sessionId) ?: "workspace-write" }
        SessionPermissionStore.setPreset(context, sessionId, preset.ifEmpty { null })
        // DSH permission projection is derived from these three events
        // (see permissionSelectOf in dsh-client-connection/client.js).
        val sandboxMode = if (effective == SessionPermissionStore.DANGER_FULL_ACCESS) "danger-full-access" else "workspace-write"
        val approvalPolicy = if (effective == SessionPermissionStore.DANGER_FULL_ACCESS) "never" else "ask"
        SessionEventHub.append(sessionId, "permission/preset", JSONObject().put("preset", effective))
        SessionEventHub.append(sessionId, "sandbox/mode", JSONObject().put("mode", sandboxMode))
        SessionEventHub.append(sessionId, "approval/policy", JSONObject().put("policy", approvalPolicy))
        return if (preset.isEmpty()) {
            CommandOutcome(true, "当前会话权限: $effective (未预设,按默认工作区写入)")
        } else {
            CommandOutcome(true, "当前会话权限已切换为 $effective；文件写入门禁已生效")
        }
    }

    /** `/goal [text]` — show or set the session's Goal. */
    private fun commandGoal(context: Context, sessionId: String, arg: String): CommandOutcome {
        val current = com.openminis.app.tools.AgentStateStore.goalGet(sessionId)
        if (arg.isEmpty()) {
            if (current.text.isBlank()) return CommandOutcome(true, "当前会话未设置 Goal")
            return CommandOutcome(true, "Goal (${current.phase}): ${current.text} (rounds ${current.maxGoalRounds})")
        }
        val goal = com.openminis.app.tools.AgentStateStore.goalSet(sessionId, arg)
        appendGoalChange(sessionId, "create", goal)
        return CommandOutcome(true, "Goal 已设置: ${goal.text}")
    }

    /** `/feedback` — summary of the session's feedback rows. */
    private fun commandFeedbackText(context: Context, sessionId: String): String {
        val items = MessageFeedbackStore.listForSession(context, sessionId)
        if (items.isEmpty()) return "当前会话暂无消息反馈"
        val positive = items.count { it.second.rating == "positive" }
        val negative = items.count { it.second.rating == "negative" }
        return "当前会话反馈: $positive 个赞同 / $negative 个反对 (共 ${items.size} 条)"
    }

    /** `/export` — surface the session's JSON export path (debugger endpoint). */
    private suspend fun commandExport(context: Context, sessionId: String): String {
        val target = File(context.cacheDir, "session_export_$sessionId.json").apply { parentFile?.mkdirs() }
        val replay = runCatching { HeadlessChatRunner.sessionEvents(context, sessionId, null) }.getOrNull()
        val array = JSONArray()
        replay?.events?.forEach { array.put(it.toEventJson()) }
        target.writeText(JSONObject()
            .put("sessionId", sessionId)
            .put("exportedAt", System.currentTimeMillis())
            .put("events", array).toString(2))
        return "会话已导出: ${target.absolutePath}"
    }

    // -------------------------------------------------- messageFeedback Remote

    /**
     * DSH business result wrapper — `{ ok, value|error }`.
     * Matches `messageFeedback_*_result$schema` (dsh-api-remotes client.js:5730).
     */
    private fun dshOk(value: JSONObject): JSONObject = JSONObject().put("ok", true).put("value", value)

    private fun dshErr(code: String, message: String, extra: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("ok", false).put("error", JSONObject()
            .put("code", code)
            .put("message", message)
            .apply {
                for (key in extra.keys()) put(key, extra.opt(key))
            })

    private suspend fun messageFeedbackList(context: Context, payload: JSONObject): JSONObject {
        val (_, agentId) = remoteArgs(payload)
        if (agentId.isBlank()) return dshErr("session-not-found", "sessionId required")
        if (!sessionExists(context, agentId)) return dshErr("session-not-found", "session not found")
        val items = JSONArray()
        for ((messageId, fb) in MessageFeedbackStore.listForSession(context, agentId)) {
            items.put(MessageFeedbackStore.dshItem(messageId, fb))
        }
        return dshOk(JSONObject().put("items", items))
    }

    private suspend fun messageFeedbackPut(context: Context, payload: JSONObject): JSONObject {
        val (args, agentId) = remoteArgs(payload)
        val messageId = args.optString("messageId", "")
        val rating = args.optString("rating", "")
        val note = if (args.has("note") && !args.isNull("note")) args.optString("note", "") else null
        val ifVersion = if (args.has("ifVersion") && !args.isNull("ifVersion")) args.optString("ifVersion", "") else null
        if (agentId.isBlank()) return dshErr("session-not-found", "sessionId required")
        if (!sessionExists(context, agentId)) return dshErr("session-not-found", "session not found")
        if (messageId.isBlank()) return dshErr("target-not-found", "messageId required", JSONObject().put("sessionId", agentId))
        return when (val result = MessageFeedbackStore.putDsh(context, agentId, messageId, rating, note, ifVersion)) {
            is MessageFeedbackStore.DshResult.Ok -> dshOk(result.item)
            is MessageFeedbackStore.DshResult.Err -> {
                when (result.code) {
                    "version-conflict" -> dshOk(JSONObject().put("ok", false).put("error", result.payload))
                    else -> dshOk(JSONObject().put("ok", false).put("error", result.payload))
                }
            }
        }
    }

    private suspend fun messageFeedbackDelete(context: Context, payload: JSONObject): JSONObject {
        val (args, agentId) = remoteArgs(payload)
        val messageId = args.optString("messageId", "")
        val ifVersion = if (args.has("ifVersion") && !args.isNull("ifVersion")) args.optString("ifVersion", "") else null
        if (agentId.isBlank()) return dshErr("session-not-found", "sessionId required")
        if (!sessionExists(context, agentId)) return dshErr("session-not-found", "session not found")
        if (messageId.isBlank()) return dshOk(JSONObject().put("absent", true))
        return when (val result = MessageFeedbackStore.deleteDsh(context, agentId, messageId, ifVersion)) {
            is MessageFeedbackStore.DshResult.Ok -> dshOk(result.item)
            is MessageFeedbackStore.DshResult.Err -> dshOk(JSONObject().put("ok", false).put("error", result.payload))
        }
    }

    private suspend fun sessionExists(context: Context, sessionId: String): Boolean = try {
        requireApp(context).chatRepositoryOrNull?.getSession(sessionId) != null
    } catch (e: Exception) {
        false
    }

    // -------------------------------------------------- goals/* (generic RPC)

    private fun goalsArgs(payload: JSONObject): Triple<String, JSONObject, JSONObject> {
        val (args, agentId) = remoteArgs(payload)
        val ref = args.optJSONObject("ref") ?: JSONObject()
        val request = args.optJSONObject("request") ?: JSONObject()
        return Triple(agentId, ref, request)
    }

    private fun goalsCreate(context: Context, payload: JSONObject): JSONObject {
        val (agentId, _, request) = goalsArgs(payload)
        val objective = request.optString("objective", "").trim()
        if (objective.isEmpty()) throw IllegalArgumentException("goal objective required")
        val goal = com.openminis.app.tools.AgentStateStore.goalSet(
            agentId,
            objective,
            if (request.has("maxGoalRounds")) request.optInt("maxGoalRounds", 8) else null,
        )
        appendGoalChange(agentId, "create", goal)
        return goalRef(goal)
    }

    private fun goalsEdit(context: Context, payload: JSONObject): JSONObject {
        val (agentId, ref, request) = goalsArgs(payload)
        val sessionId = requiredGoalSession(JSONObject().put("sessionId", agentId))
        val current = requireCurrentGoal(sessionId, JSONObject().put("ref", ref))
        val objective = if (request.has("objective")) {
            request.optString("objective", "").trim().ifEmpty {
                throw IllegalArgumentException("goal objective cannot be empty")
            }
        } else current.text
        val rounds = if (request.has("maxGoalRounds")) {
            request.optInt("maxGoalRounds", current.maxGoalRounds).coerceIn(1, 100)
        } else current.maxGoalRounds
        val goal = com.openminis.app.tools.AgentStateStore.goalSet(sessionId, objective, rounds)
        appendGoalChange(sessionId, "edit", goal)
        return dshGoalValue(goal)
    }

    private fun goalsSetPhase(payload: JSONObject, operation: String): JSONObject {
        val (agentId, ref, _) = goalsArgs(payload)
        val sessionId = requiredGoalSession(JSONObject().put("sessionId", agentId))
        requireCurrentGoal(sessionId, JSONObject().put("ref", ref))
        val goal = when (operation) {
            "pause" -> com.openminis.app.tools.AgentStateStore.goalSetActive(sessionId, false)
            "resume" -> com.openminis.app.tools.AgentStateStore.goalSetActive(sessionId, true)
            "complete" -> com.openminis.app.tools.AgentStateStore.goalComplete(sessionId)
            else -> throw IllegalArgumentException("unknown goal operation")
        }
        appendGoalChange(sessionId, operation, goal)
        return dshGoalValue(goal)
    }

    /** goals/clear result — `{ id, revision }` (dsh-api-remotes client.js:4376). */
    private fun goalsClear(context: Context, payload: JSONObject): JSONObject {
        val (agentId, ref, _) = goalsArgs(payload)
        val sessionId = requiredGoalSession(JSONObject().put("sessionId", agentId))
        val current = requireCurrentGoal(sessionId, JSONObject().put("ref", ref))
        com.openminis.app.tools.AgentStateStore.goalClear(sessionId)
        SessionEventHub.append(sessionId, "goal/change", JSONObject().apply {
            put("kind", "goal/change")
            put("version", 1)
            put("operation", "clear")
            put("cleared", JSONObject().put("id", current.id).put("revision", current.revision + 1))
            put("clearedAt", System.currentTimeMillis())
        })
        return JSONObject().put("id", current.id).put("revision", current.revision + 1)
    }

    /** Full DSH goal object (goals/edit|pause|resume|complete result schema). */
    private fun dshGoalValue(goal: com.openminis.app.tools.AgentStateStore.Goal): JSONObject = JSONObject().apply {
        val phase = when (goal.phase) {
            "complete" -> "complete"
            "paused" -> "paused"
            else -> "active"
        }
        put("roundsStarted", 0)
        put("createdAt", goal.createdAt)
        put("updatedAt", goal.updatedAt)
        put("activation", if (goal.active) "armed" else "disarmed")
        put("objective", goal.text)
        put("phase", phase)
        put("maxGoalRounds", goal.maxGoalRounds)
        put("id", goal.id)
        put("revision", goal.revision)
    }

    // ----------------------------------------------------------- attachments

    /**
     * sessionAttachmentValueSchema (client.js:5438) —
     * `{ attachment: imageAttachmentRefSchema, data: string }` where `data` is
     * a standard base64 STRING: the DSH client validates it with `string()`
     * and the runtime decodes it with `atob()` (client.js:7271) before the
     * conversation UI wraps it in a Blob. A byte array would fail both.
     *
     * Security: the attachment must be referenced by a message of the
     * requested session — knowing an attachmentId must never leak an image
     * from another session.
     */
    private suspend fun sessionAttachment(context: Context, payload: JSONObject): JSONObject {
        val sessionId = payload.optString("sessionId", "")
        val attachmentId = payload.optString("attachmentId", "")
        if (sessionId.isEmpty()) throw IllegalArgumentException("sessionId required")
        if (attachmentId.isEmpty()) throw IllegalArgumentException("attachmentId required")

        val ref = resolveMediaRefForSession(context, sessionId, attachmentId)
            ?: throw IllegalArgumentException("attachment not found in this session")
        val mediaStore = com.openminis.app.data.storage.MediaStore(context)
        val file = File(mediaStore.mediaBaseDir, ref.optString("relativePath", ""))
        if (!file.exists() || !file.isFile || file.length() > 32L * 1024 * 1024) {
            throw IllegalArgumentException("attachment file is missing or too large")
        }
        val metadata = dshImageAttachment(attachmentId, file, ref)
            ?: throw IllegalArgumentException("attachment is not a supported image")
        val bytes = file.readBytes()
        val data = encodeAttachmentData(bytes)
        return JSONObject()
            .put("attachment", metadata)
            .put("data", data)
    }

    /**
     * DSH `session.attachment` `data` wire format: standard base64.
     * `dsh-client-runtime` decodes it with `atob()` and the conversation UI
     * then builds a Blob from the resulting bytes. Pure JVM so the protocol
     * contract is unit-testable (minSdk 26; java.util.Base64 is the
     * no-wrap, atob-compatible encoder).
     */
    internal fun encodeAttachmentData(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    /**
     * Find the MediaRef for [attachmentId] among messages of [sessionId]
     * (live journal imageRefs + durable parts_json mediaRef entries).
     */
    private suspend fun resolveMediaRefForSession(context: Context, sessionId: String, attachmentId: String): JSONObject? {
        // 1) Live journal may already know the ref.
        runCatching {
            val replay = HeadlessChatRunner.sessionEvents(context, sessionId, null)
            for (event in replay.events.reversed()) {
                if (event.type != "user/message") continue
                val message = event.toEventJson().optJSONObject("data")?.optJSONObject("message") ?: continue
                val refs = message.optJSONArray("imageRefs") ?: continue
                for (i in 0 until refs.length()) {
                    val ref = refs.optJSONObject(i) ?: continue
                    if (ref.optString("id", "") == attachmentId) return ref
                }
            }
        }
        // 2) Durable parts_json (DB) — survives restart & legacy messages.
        runCatching {
            val app = requireApp(context)
            val messages = app.chatRepository.dao.loadMessages(sessionId)
            for (message in messages.asReversed()) {
                val parts = runCatching { org.json.JSONArray(message.partsJson) }.getOrNull() ?: continue
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optString("type") != "mediaRef") continue
                    val value = part.optJSONObject("value") ?: continue
                    val id = value.optString("id", "")
                    if (id == attachmentId) {
                        return JSONObject()
                            .put("id", id)
                            .put("relativePath", value.optString("relativePath", ""))
                            .put("mimeType", value.optString("mimeType", ""))
                            .apply {
                                value.optString("originalFileName", "").takeIf { it.isNotEmpty() }
                                    ?.let { put("originalFileName", it) }
                            }
                    }
                }
            }
        }
        return null
    }

    // --------------------------------------------------- resources/@ mention

    /**
     * `resources/list` — OpenMinis resource-catalog used by the `@` mention
     * source in the composer. Value is `{ files: [{name,path,kind}], sessions:
     * [{sessionId,title}] }`; both come from real backend state (workspace
     * directory walk + session list) — no second index.
     */
    private suspend fun resourcesList(context: Context, payload: JSONObject): JSONObject {
        val (args, agentId) = remoteArgs(payload)
        val query = args.optString("query", "").trim().lowercase()
        val dirsOnly = args.optBoolean("dirsOnly", false)

        val files = JSONArray()
        val sessionId = agentId.ifEmpty { latestSessionId(context) }
        if (sessionId != null) {
            walkWorkspaceForResources(context, sessionId, query, dirsOnly, files)
        }

        val sessions = JSONArray()
        try {
            val raw = ChatDebugMethods.sessionsList(
                context, JSONObject().put("limit", 200).put("includeEmpty", true),
            )
            val rows = raw.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val s = rows.optJSONObject(i) ?: continue
                val sid = s.optString("id", s.optString("sessionId", ""))
                if (sid.isEmpty()) continue
                val title = s.optString("title", "").ifEmpty {
                    s.optString("lastMessagePreview", "").take(40)
                }.ifEmpty { "会话" }
                if (query.isNotEmpty() && !title.lowercase().contains(query)) continue
                sessions.put(JSONObject()
                    .put("sessionId", sid)
                    .put("title", title))
            }
        } catch (_: Exception) {
        }
        return JSONObject()
            .put("files", files)
            .put("sessions", sessions)
    }

    private suspend fun walkWorkspaceForResources(
        context: Context,
        sessionId: String,
        query: String,
        dirsOnly: Boolean,
        out: JSONArray,
    ) {
        val root = runCatching { resolveSessionPath(context, sessionId, WORKSPACE_PATH) }.getOrNull() ?: return
        if (!root.isDirectory) return
        var budget = 300
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && budget > 0) {
            val (dir, depth) = queue.removeFirst()
            if (depth > 3) continue
            val children = runCatching { dir.listFiles() }.getOrNull()?.sortedBy { it.name.lowercase() }.orEmpty()
            for (child in children) {
                if (budget <= 0) return
                val isDir = child.isDirectory
                val rel = child.relativeTo(root).invariantSeparatorsPath
                val linux = "$WORKSPACE_PATH/$rel"
                if (query.isNotEmpty() && !child.name.lowercase().contains(query)) {
                    if (isDir && depth < 3) queue.add(child to depth + 1)
                    continue
                }
                if (!isDir && dirsOnly) continue
                out.put(JSONObject()
                    .put("name", child.name)
                    .put("path", linux)
                    .put("kind", if (isDir) "dir" else "file"))
                budget--
            }
        }
    }

    // ---------------------------------------------------------------- envelope

    private fun wrapOk(rpcId: String, value: Any?): JSONObject = JSONObject().apply {
        put("type", "server-response")
        put("rpcId", rpcId)
        put("result", JSONObject().put("ok", true).apply {
            // JSONObject.NULL marks "no value": commands/execute uses it to
            // mean "not admitted", which maps to `undefined` in the client
            // schema (`union([undefined, …])`).
            if (value != null && value !== JSONObject.NULL) put("value", value)
        })
    }

    /**
     * rpcErrorSchema (client.js:4897) is a discriminated union over `code`, and
     * there is no generic "internal" member — an unknown code fails the client's
     * own parse, turning a handled backend error into an unhandled client throw.
     * `bad-request` is the one branch whose details (`{ issues: [] }`) accept an
     * arbitrary payload, so it is the safe carrier for any host-side failure.
     */
    private fun wrapError(rpcId: String, message: String): JSONObject = JSONObject().apply {
        put("type", "server-response")
        put("rpcId", rpcId)
        put("result", JSONObject().apply {
            put("ok", false)
            put("error", JSONObject().apply {
                put("code", "bad-request")
                put("message", message)
                put("details", JSONObject().put("issues", JSONArray()))
            })
        })
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
        "commands/list", "commands/execute",
        "messageFeedback/list", "messageFeedback/put", "messageFeedback/delete",
        "resources/list",
        "goals/create", "goals/edit", "goals/pause", "goals/resume", "goals/complete", "goals/clear",
        "settings.describe", "settings.openDocument", "settings.update",
        "settings.replace", "settings.mutate",
        "credentials.describe", "credentials.set", "credentials.unset",
        "llm.providers", "llm.models", "llm.discoverModels",
    )
}
