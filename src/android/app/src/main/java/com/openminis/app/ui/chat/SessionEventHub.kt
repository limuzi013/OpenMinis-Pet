package com.openminis.app.ui.chat

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.SessionEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * A bounded, append-only event journal for a single chat session.
 *
 * This is deliberately separate from the messages table. Messages remain the
 * durable source for an initial conversation snapshot; this journal records
 * the exact state transitions that occur while a model is generating. It
 * mirrors the important Harness invariant:
 *
 *   snapshot (once) -> monotonic session events -> incremental projections
 *
 * The in-memory journal is the hot fan-out cache. Once a real session exists,
 * the same events are appended asynchronously to Room after hot fan-out; the
 * DB table has the same bounded retention window. This
 * keeps the event log useful across reconnects and process restarts without
 * allowing a token stream to become an unbounded second copy of history.
 */
data class SessionEvent(
    val sessionId: String,
    val seq: Long,
    val time: Long,
    val type: String,
    /** A compact JSON object string. Stored immutable so callers cannot mutate the journal. */
    val dataJson: String,
) {
    fun data(): JSONObject = JSONObject(dataJson)

    /** DSH-shaped event object, without the outer `session/event` frame. */
    fun toEventJson(): JSONObject = JSONObject().apply {
        put("seq", seq)
        put("time", time)
        put("type", type)
        put("data", data())
    }

    /** Wire frame consumed by the authenticated WebSocket adapter. */
    fun toWireEnvelope(): JSONObject = JSONObject().apply {
        put("type", "session/event")
        put("sessionId", sessionId)
        put("event", toEventJson())
    }
}

/** Result of replaying a session's bounded journal after [afterSeq]. */
data class SessionEventReplay(
    val events: List<SessionEvent>,
    val latestSeq: Long,
    val oldestAvailableSeq: Long,
    /** True means the requested cursor predates the retained journal. */
    val resetRequired: Boolean,
)

/**
 * A projection captured while the event journal is fenced.  The snapshot and
 * [lastSeq] describe exactly the same point in the native session: a client
 * can hydrate [value] and safely resume from `lastSeq + 1` without replaying
 * a non-idempotent token that is already represented in the snapshot.
 */
data class SessionEventCapture<T>(
    val value: T,
    val lastSeq: Long,
)

/** Immutable event-sourced tail used to hydrate a remote snapshot. */
data class SessionEventTail(
    val messages: Map<String, SessionEventTailMessage>,
    val isRunning: Boolean?,
    val modelName: String?,
    val thinkingLevel: String?,
    val turn: Int?,
)

data class SessionEventTailMessage(
    val id: String,
    val role: String,
    /** False only when a process joined after the message's reset event aged out. */
    val contentAuthoritative: Boolean,
    val content: String,
    val isStreaming: Boolean,
    val isAwaitingModelResponse: Boolean,
    val attachments: List<String>,
    val reasoningByIndex: Map<String, String>,
    val tools: Map<String, SessionEventTailTool>,
)

data class SessionEventTailTool(
    val id: String,
    val name: String,
    val title: String,
    val args: String,
    val output: String,
    val status: String?,
    val startTimeMs: Long,
    val durationMs: Long,
)

/**
 * Process-wide collection of per-session journals.  All mutating operations
 * are synchronized because ChatViewModel can emit from its main scope while a
 * remote transport reads from an IO worker.
 */
object SessionEventHub {
    /** Enough room for a long raw-token tool turn without making Room a log archive. */
    internal const val MAX_EVENTS_PER_SESSION = 2_048
    /** Bound dormant-session hot caches as well as each individual replay window. */
    internal const val MAX_SESSION_JOURNALS = 48
    private const val PERSIST_BATCH_MAX = 96

    private data class Journal(
        var nextSeq: Long,
        val events: ArrayDeque<SessionEvent> = ArrayDeque(),
        val tail: MaterializedTail = MaterializedTail(),
    )

    /** An event waiting for a real session id / durable sequence watermark. */
    private data class PendingEvent(
        val time: Long,
        val type: String,
        val dataJson: String,
    )

    private sealed interface DurableTask {
        data class Append(val event: SessionEvent) : DurableTask
        data class Delete(val sessionId: String) : DurableTask
    }

    /* accessOrder gives eviction an LRU meaning without touching event order. */
    private val journals = LinkedHashMap<String, Journal>(16, 0.75f, true)
    private val preActivation = mutableMapOf<String, ArrayDeque<PendingEvent>>()
    private val activatedSessions = mutableSetOf<String>()
    private val activationGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var durableDao: ChatDao? = null

    private val _events = MutableSharedFlow<SessionEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Live notification only; reconnect replay must always use [eventsSince]. */
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val persistenceQueue = Channel<DurableTask>(Channel.UNLIMITED)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        persistenceScope.launch { persistLoop() }
    }

    /** Install the app database once it is ready. Safe to call more than once. */
    fun installDurableStore(dao: ChatDao) {
        synchronized(this) { durableDao = dao }
    }

    /**
     * Read Room's per-session high water mark before publishing any event for
     * [sessionId]. Concurrent callers await the same gate. Events produced
     * while activation is pending are held without a sequence/frame, then
     * assigned and published in source order under the hub lock. This avoids
     * both cross-process sequence collisions and snapshot-watermark races.
     */
    suspend fun activateSession(dao: ChatDao, sessionId: String) {
        if (sessionId.isBlank()) return
        var owner = false
        val gate = synchronized(this) {
            durableDao = dao
            if (sessionId in activatedSessions) return@synchronized null
            activationGates.getOrPut(sessionId) {
                owner = true
                CompletableDeferred()
            }
        } ?: return
        if (!owner) {
            gate.await()
            return
        }
        try {
            val durableLatest = dao.latestSessionEventSeq(sessionId)
            synchronized(this) {
                val journal = journals.getOrPut(sessionId) { Journal(durableLatest + 1L) }
                journal.nextSeq = maxOf(journal.nextSeq, durableLatest + 1L)
                activatedSessions.add(sessionId)
                val held = preActivation.remove(sessionId)?.toList().orEmpty()
                held.forEach { pending ->
                    val event = appendHotLocked(sessionId, pending.time, pending.type, pending.dataJson)
                    // SharedFlow emission is non-blocking (DROP_OLDEST). Keep it
                    // inside the same lock that allocates seq so live consumers
                    // cannot observe N+1 before N during activation.
                    _events.tryEmit(event)
                    persistenceQueue.trySend(DurableTask.Append(event))
                }
            }
            gate.complete(Unit)
        } catch (t: Throwable) {
            gate.completeExceptionally(t)
            throw t
        } finally {
            synchronized(this) { activationGates.remove(sessionId) }
        }
    }

    /**
     * Append and publish a new event synchronously under the hub lock. Room
     * persistence happens later on a serial IO worker; live correctness never
     * waits for storage, and snapshot capture can fence on this same lock.
     */
    fun append(sessionId: String, type: String, data: JSONObject = JSONObject()): SessionEvent? {
        if (sessionId.isBlank() || type.isBlank()) return null
        val pending = PendingEvent(
            time = System.currentTimeMillis(),
            type = type,
            dataJson = JSONObject(data.toString()).toString(),
        )
        return synchronized(this) {
            var direct: SessionEvent? = null
            if (durableDao == null) {
                // Pure JVM tests and a very early boot path intentionally use
                // an in-memory sequence beginning at one.
                direct = appendHotLocked(sessionId, pending.time, pending.type, pending.dataJson)
            } else if (sessionId in activatedSessions) {
                direct = appendHotLocked(sessionId, pending.time, pending.type, pending.dataJson)
            } else {
                val held = preActivation.getOrPut(sessionId) { ArrayDeque() }
                held.addLast(pending)
                while (held.size > MAX_EVENTS_PER_SESSION) held.removeFirst()
            }
            // Allocate, install in the hot replay journal, and publish under
            // one lock. Room is merely an asynchronous durability mirror; it
            // must never become the source of live sequence ordering.
            direct?.let { event ->
                _events.tryEmit(event)
                if (durableDao != null) persistenceQueue.trySend(DurableTask.Append(event))
            }
            direct
        }
    }

    /** Current hot cursor; use [replayDurable] for a cross-restart cursor. */
    fun latestSeq(sessionId: String): Long = synchronized(this) {
        journals[sessionId]?.let { it.nextSeq - 1L } ?: 0L
    }

    /**
     * Execute [projection] while append/activation is fenced and return its
     * exact high-water mark. The callback must stay non-blocking: it normally
     * reads the already-materialized ViewModel state and builds a JSON object.
     */
    fun <T> captureWithWatermark(
        sessionId: String,
        projection: (hotEvents: List<SessionEvent>, lastSeq: Long) -> T,
    ): SessionEventCapture<T> = synchronized(this) {
        val journal = journals[sessionId]
        val hotEvents = journal?.events?.toList().orEmpty()
        val lastSeq = journal?.nextSeq?.minus(1L) ?: 0L
        SessionEventCapture(projection(hotEvents, lastSeq), lastSeq)
    }

    /** Same fence as the two-argument variant, additionally exposing the
     * materialized tail that survives hot-journal eviction. */
    fun <T> captureWithWatermark(
        sessionId: String,
        projection: (hotEvents: List<SessionEvent>, tail: SessionEventTail, lastSeq: Long) -> T,
    ): SessionEventCapture<T> = synchronized(this) {
        val journal = journals[sessionId]
        val hotEvents = journal?.events?.toList().orEmpty()
        val tail = journal?.tail?.snapshot() ?: emptyTail()
        val lastSeq = journal?.nextSeq?.minus(1L) ?: 0L
        SessionEventCapture(projection(hotEvents, tail, lastSeq), lastSeq)
    }

    /** Hot replay used by an already-running transport. */
    fun eventsSince(sessionId: String, afterSeq: Long?): SessionEventReplay = synchronized(this) {
        replayFromEvents(journals[sessionId]?.events?.toList().orEmpty(), afterSeq)
    }

    /**
     * Merge Room's retained event window with the hot cache. This is the
     * reconnect/process-restart read path; duplicate sequence numbers prefer
     * the hot instance because it is the exact object most recently published.
     */
    suspend fun replayDurable(dao: ChatDao, sessionId: String, afterSeq: Long?): SessionEventReplay {
        val durableOldest = dao.oldestSessionEventSeq(sessionId)
        val durableLatest = dao.latestSessionEventSeq(sessionId)
        val cursorForRead = afterSeq ?: durableLatest
        val durable = if (afterSeq == null) emptyList() else dao.sessionEventsAfter(
            sessionId = sessionId,
            afterSeq = afterSeq,
            limit = MAX_EVENTS_PER_SESSION + PERSIST_BATCH_MAX,
        ).map { row ->
            SessionEvent(
                sessionId = row.sessionId,
                seq = row.seq,
                time = row.createdAt,
                type = row.type,
                dataJson = row.payloadJson,
            )
        }
        val hot = synchronized(this) { journals[sessionId]?.events?.toList().orEmpty() }
        val combined = LinkedHashMap<Long, SessionEvent>()
        durable.forEach { combined[it.seq] = it }
        hot.forEach { combined[it.seq] = it }
        val all = combined.values.sortedBy { it.seq }
        val oldest = listOfNotNull(
            durableOldest.takeIf { it > 0L },
            hot.firstOrNull()?.seq,
        ).minOrNull() ?: 0L
        val latest = maxOf(durableLatest, hot.lastOrNull()?.seq ?: 0L)
        val cursor = afterSeq ?: latest
        val reset = oldest > 0L && cursor < oldest - 1L
        return SessionEventReplay(
            events = if (reset) emptyList() else all.filter { it.seq > cursorForRead },
            latestSeq = latest,
            oldestAvailableSeq = oldest,
            resetRequired = reset,
        )
    }

    /**
     * Promote held draft intents when ChatViewModel promotes its draft session.
     * Activation of the target assigns the actual durable sequence afterwards.
     */
    fun rename(fromSessionId: String, toSessionId: String) {
        if (fromSessionId.isBlank() || toSessionId.isBlank() || fromSessionId == toSessionId) return
        synchronized(this) {
            preActivation.remove(fromSessionId)?.let { from ->
                val target = preActivation.getOrPut(toSessionId) { ArrayDeque() }
                target.addAll(from)
                while (target.size > MAX_EVENTS_PER_SESSION) target.removeFirst()
            }
            activatedSessions.remove(fromSessionId)
            val fromHot = journals.remove(fromSessionId)
            if (fromHot != null) {
                val target = journals.getOrPut(toSessionId) { Journal(1L) }
                fromHot.events.forEach { old ->
                    val moved = old.copy(sessionId = toSessionId)
                    target.events.addLast(moved)
                    target.tail.apply(moved)
                }
                while (target.events.size > MAX_EVENTS_PER_SESSION) target.events.removeFirst()
                target.nextSeq = maxOf(target.nextSeq, (target.events.lastOrNull()?.seq ?: 0L) + 1L)
            }
            trimJournalsLocked()
        }
    }

    /** Drop a deleted session's transient cache and durable replay rows. */
    fun remove(sessionId: String) {
        synchronized(this) {
            journals.remove(sessionId)
            preActivation.remove(sessionId)
            activatedSessions.remove(sessionId)
        }
        persistenceQueue.trySend(DurableTask.Delete(sessionId))
    }

    /** Clear a live conversation's replay history while preserving the session row itself. */
    fun clear(sessionId: String) {
        synchronized(this) {
            // Keep the sequence watermark while the queued SQL delete drains.
            // A new turn queued immediately after Clear must not reuse an old
            // primary key that the writer has not deleted yet.
            val next = journals[sessionId]?.nextSeq ?: 1L
            journals[sessionId] = Journal(next)
            preActivation.remove(sessionId)
        }
        persistenceQueue.trySend(DurableTask.Delete(sessionId))
    }

    /** Test-only reset; intentionally internal so production callers cannot erase a live journal. */
    internal fun clearForTests() {
        synchronized(this) {
            journals.clear()
            preActivation.clear()
            activatedSessions.clear()
            activationGates.clear()
            durableDao = null
        }
    }

    private fun appendHotLocked(
        sessionId: String,
        time: Long,
        type: String,
        dataJson: String,
    ): SessionEvent {
        val journal = journals.getOrPut(sessionId) { Journal(1L) }
        trimJournalsLocked()
        val event = SessionEvent(
            sessionId = sessionId,
            seq = journal.nextSeq++,
            time = time,
            type = type,
            dataJson = dataJson,
        )
        // Materialize before publishing so a capture under this same lock can
        // never fence past a raw delta that its snapshot does not contain.
        journal.tail.apply(event)
        journal.events.addLast(event)
        while (journal.events.size > MAX_EVENTS_PER_SESSION) journal.events.removeFirst()
        return event
    }

    /**
     * Small event reducer for the remote snapshot tail. It is deliberately
     * independent of Compose: raw provider chunks arrive before the UI's
     * throttle, and this reducer advances under the very lock that assigns the
     * event sequence. The message table remains the long-term base; this tail
     * only overlays recent/live work and is bounded separately.
     */
    private class MaterializedTail {
        private class MutableTool(val id: String) {
            var name = ""
            var title = ""
            var args = ""
            var output = ""
            var status: String? = null
            var startTimeMs = 0L
            var durationMs = 0L
        }

        private class MutableMessage(val id: String, var role: String) {
            var content = ""
            var contentAuthoritative = false
            var isStreaming = false
            var isAwaitingModelResponse = false
            var attachments: List<String> = emptyList()
            val reasoningByIndex = LinkedHashMap<String, String>()
            val tools = LinkedHashMap<String, MutableTool>()
        }

        private val messages = LinkedHashMap<String, MutableMessage>()
        private var isRunning: Boolean? = null
        private var modelName: String? = null
        private var thinkingLevel: String? = null
        private var turn: Int? = null

        fun apply(event: SessionEvent) {
            val data = runCatching { event.data() }.getOrNull() ?: return
            val embedded = data.optJSONObject("message")
            val messageId = data.optString("messageId", embedded?.optString("id", "") ?: "")
            when (event.type) {
                "turn/start", "turn/status", "turn/end" -> {
                    if (data.has("isRunning")) isRunning = data.optBoolean("isRunning")
                    else if (event.type == "turn/start") isRunning = true
                    else if (event.type == "turn/end") isRunning = false
                    data.optString("modelName", "").takeIf { it.isNotBlank() }?.let { modelName = it }
                    data.optString("thinkingLevel", "").takeIf { it.isNotBlank() }?.let { thinkingLevel = it }
                    if (data.has("turn")) turn = data.optInt("turn")
                }
                "user/message", "system/message" -> {
                    val message = embedded ?: return
                    val id = message.optString("id", "")
                    if (id.isBlank()) return
                    val target = messageFor(id, message.optString("role", "user")) ?: return
                    copyMessage(target, message, authoritative = true)
                }
                "assistant/placeholder" -> {
                    val message = embedded ?: return
                    val id = message.optString("id", messageId)
                    if (id.isBlank()) return
                    val target = messageFor(id, "assistant") ?: return
                    copyMessage(target, message, authoritative = true)
                    target.isStreaming = true
                }
                "assistant/chunk" -> {
                    val target = messageFor(messageId, "assistant") ?: return
                    val chunk = data.optJSONObject("chunk") ?: return
                    when (chunk.optString("type")) {
                        "text-delta" -> target.content += chunk.optString("text", "")
                        "reasoning-delta" -> {
                            val index = chunk.optString("index", "thinking")
                            target.reasoningByIndex[index] = target.reasoningByIndex[index].orEmpty() +
                                chunk.optString("text", "")
                        }
                        "tool-call-delta" -> {
                            val tool = toolFor(target, chunk.optString("callId", chunk.optString("toolUseId", "")))
                            if (tool != null) tool.args += chunk.optString("text", "")
                        }
                        "tool-result-delta" -> {
                            val tool = toolFor(target, chunk.optString("callId", chunk.optString("toolUseId", "")))
                            if (tool != null) tool.output += chunk.optString("text", "")
                        }
                    }
                }
                "assistant/replace" -> {
                    val target = messageFor(messageId, "assistant") ?: return
                    if (data.has("content")) {
                        target.content = data.optString("content", "")
                        target.contentAuthoritative = true
                    }
                    if (data.has("reasoning")) {
                        target.reasoningByIndex[data.optString("blockId", "thinking")] =
                            data.optString("reasoning", "")
                    }
                    if (data.has("isStreaming")) target.isStreaming = data.optBoolean("isStreaming")
                }
                "assistant/message" -> {
                    val target = messageFor(messageId, "assistant") ?: return
                    if (data.has("content")) target.content = data.optString("content", "")
                    target.contentAuthoritative = true
                    target.isStreaming = false
                    target.isAwaitingModelResponse = data.optBoolean("isAwaitingModelResponse", false)
                    if (data.has("reasoning")) target.reasoningByIndex["thinking"] = data.optString("reasoning", "")
                    applyCalls(target, data.optJSONArray("toolCalls"))
                    applyResults(target, data.optJSONArray("toolResults"))
                }
                "tool/call", "tool/status", "tool/result" -> {
                    val target = messageFor(messageId, "assistant") ?: return
                    val call = data.optJSONObject("call")
                    val toolId = if (call != null) {
                        call.optString("id", call.optString("toolUseId", ""))
                    } else {
                        data.optString("toolUseId", "")
                    }
                    val tool = toolFor(target, toolId) ?: return
                    applyCall(tool, call)
                    data.optJSONObject("result")?.let { applyResult(tool, it) }
                }
            }
            trimMessages()
        }

        fun snapshot(): SessionEventTail = SessionEventTail(
            messages = messages.mapValues { (_, message) ->
                SessionEventTailMessage(
                    id = message.id,
                    role = message.role,
                    contentAuthoritative = message.contentAuthoritative,
                    content = message.content,
                    isStreaming = message.isStreaming,
                    isAwaitingModelResponse = message.isAwaitingModelResponse,
                    attachments = message.attachments.toList(),
                    reasoningByIndex = message.reasoningByIndex.toMap(),
                    tools = message.tools.mapValues { (_, tool) ->
                        SessionEventTailTool(
                            id = tool.id,
                            name = tool.name,
                            title = tool.title,
                            args = tool.args,
                            output = tool.output,
                            status = tool.status,
                            startTimeMs = tool.startTimeMs,
                            durationMs = tool.durationMs,
                        )
                    },
                )
            },
            isRunning = isRunning,
            modelName = modelName,
            thinkingLevel = thinkingLevel,
            turn = turn,
        )

        private fun messageFor(id: String, role: String): MutableMessage? {
            if (id.isBlank()) return null
            return messages.getOrPut(id) { MutableMessage(id, role) }.also { if (role.isNotBlank()) it.role = role }
        }

        private fun toolFor(message: MutableMessage, id: String): MutableTool? =
            id.takeIf { it.isNotBlank() }?.let { message.tools.getOrPut(it) { MutableTool(it) } }

        private fun copyMessage(target: MutableMessage, source: JSONObject, authoritative: Boolean) {
            source.optString("role", "").takeIf { it.isNotBlank() }?.let { target.role = it }
            if (source.has("content")) target.content = source.optString("content", "")
            target.contentAuthoritative = authoritative
            if (source.has("isStreaming")) target.isStreaming = source.optBoolean("isStreaming")
            if (source.has("isAwaitingModelResponse")) {
                target.isAwaitingModelResponse = source.optBoolean("isAwaitingModelResponse")
            }
            source.optJSONArray("attachments")?.let { array ->
                target.attachments = List(array.length()) { index -> array.optString(index) }
            }
        }

        private fun applyCalls(target: MutableMessage, calls: JSONArray?) {
            for (index in 0 until (calls?.length() ?: 0)) {
                val call = calls?.optJSONObject(index) ?: continue
                toolFor(target, call.optString("id", call.optString("toolUseId", "")))?.let { applyCall(it, call) }
            }
        }

        private fun applyResults(target: MutableMessage, results: JSONArray?) {
            for (index in 0 until (results?.length() ?: 0)) {
                val result = results?.optJSONObject(index) ?: continue
                toolFor(target, result.optString("id", result.optString("toolUseId", "")))?.let { applyResult(it, result) }
            }
        }

        private fun applyCall(tool: MutableTool, call: JSONObject?) {
            if (call == null) return
            tool.name = call.optString("name", call.optString("toolName", tool.name))
            tool.title = call.optString("title", tool.title)
            if (call.has("toolArgs")) tool.args = call.optString("toolArgs", "")
            call.optString("toolStatus", "").takeIf { it.isNotBlank() && it != "null" }?.let { tool.status = it }
            if (call.has("startTimeMs")) tool.startTimeMs = call.optLong("startTimeMs")
            if (call.has("durationMs")) tool.durationMs = call.optLong("durationMs")
        }

        private fun applyResult(tool: MutableTool, result: JSONObject) {
            tool.name = result.optString("name", result.optString("toolName", tool.name))
            if (result.has("output")) tool.output = result.optString("output", "")
            result.optString("toolStatus", "").takeIf { it.isNotBlank() && it != "null" }?.let { tool.status = it }
            if (result.has("durationMs")) tool.durationMs = result.optLong("durationMs")
        }

        private fun trimMessages() {
            // The durable messages table is the base. Retain enough event-tail
            // rows to bridge an async DB write without retaining every turn.
            while (messages.size > 96) {
                val iterator = messages.entries.iterator()
                val oldest = iterator.next()
                if (oldest.value.isStreaming) break
                iterator.remove()
            }
        }
    }

    private fun emptyTail(): SessionEventTail = SessionEventTail(
        messages = emptyMap(),
        isRunning = null,
        modelName = null,
        thinkingLevel = null,
        turn = null,
    )

    private suspend fun persistLoop() {
        var deferred: DurableTask? = null
        while (true) {
            val first = deferred ?: persistenceQueue.receive()
            deferred = null
            when (first) {
                is DurableTask.Delete -> {
                    val dao = synchronized(this) { durableDao }
                    runCatching { dao?.deleteSessionEvents(first.sessionId) }
                }
                is DurableTask.Append -> {
                    // Give raw provider chunks a tiny collection window, then
                    // insert one ordered batch. This never blocks the model or
                    // Compose, and event fan-out remains in original order.
                    delay(12L)
                    val batch = ArrayList<DurableTask.Append>(PERSIST_BATCH_MAX)
                    batch.add(first)
                    while (batch.size < PERSIST_BATCH_MAX) {
                        val next = persistenceQueue.tryReceive().getOrNull() ?: break
                        if (next is DurableTask.Append) batch.add(next)
                        else {
                            deferred = next
                            break
                        }
                    }
                    val dao = synchronized(this) { durableDao }
                    if (dao == null) continue
                    try {
                        val assigned = batch.map { it.event }
                        dao.insertSessionEvents(assigned.map { event ->
                            SessionEventEntity(
                                sessionId = event.sessionId,
                                seq = event.seq,
                                type = event.type,
                                payloadJson = event.dataJson,
                                createdAt = event.time,
                            )
                        })
                        assigned.map { it.sessionId }.distinct().forEach { sid ->
                            // Batched pruning bounds the table to the replay
                            // window plus at most one small write batch.
                            if (assigned.any { it.sessionId == sid && it.seq % 64L == 0L }) {
                                dao.trimSessionEvents(sid, MAX_EVENTS_PER_SESSION)
                            }
                        }
                    } catch (_: Throwable) {
                    // Live events have already been sequenced and published.
                    // If a durability write fails, a future cold reconnect
                    // falls back to a fresh message snapshot rather than a
                    // retry storm on a full/vacant database.
                    }
                }
            }
        }
    }

    private fun replayFromEvents(events: List<SessionEvent>, afterSeq: Long?): SessionEventReplay {
        val oldest = events.firstOrNull()?.seq ?: 0L
        val latest = events.lastOrNull()?.seq ?: 0L
        val cursor = afterSeq ?: latest
        val reset = oldest > 0L && cursor < oldest - 1L
        return SessionEventReplay(
            events = if (reset) emptyList() else events.filter { it.seq > cursor },
            latestSeq = latest,
            oldestAvailableSeq = oldest,
            resetRequired = reset,
        )
    }

    private fun trimJournalsLocked() {
        while (journals.size > MAX_SESSION_JOURNALS) {
            val eldest = journals.entries.iterator().next()
            journals.remove(eldest.key)
        }
    }

}

/**
 * Semantic adapter kept beside the native ChatViewModel rather than the web
 * server.  It turns the ViewModel's canonical transitions into a small stable
 * protocol while preserving the UI's existing high-frequency throttles.
 */
internal class ChatSessionEventEmitter(
    private val sessionId: () -> String,
) {
    private data class ToolState(
        val toolArgs: String,
        val content: String,
        val status: ToolBlockStatus?,
        val title: String,
        val durationMs: Long,
    )

    private val textByMessage = mutableMapOf<String, String>()
    private val reasoningByBlock = mutableMapOf<String, String>()
    private val toolsByMessage = mutableMapOf<String, MutableMap<String, ToolState>>()
    private var runActive = false
    private var lastAwaitingModelResponse: Boolean? = null
    /** DSH numbers turns and steps so a reconnect can correlate a tool trail. */
    private var lastClosedTurn = 0
    private var activeTurn = 0
    private var nextStepInTurn = 0
    private val stepByMessage = mutableMapOf<String, Int>()

    @Synchronized
    fun runStatus(
        isRunning: Boolean,
        modelName: String,
        thinkingLevel: String,
        reason: String? = null,
    ) {
        val data = JSONObject().apply {
            put("isRunning", isRunning)
            if (modelName.isNotBlank()) put("modelName", modelName)
            if (thinkingLevel.isNotBlank()) put("thinkingLevel", thinkingLevel.lowercase())
            if (!reason.isNullOrBlank()) put("reason", reason)
        }
        when {
            isRunning && !runActive -> {
                runActive = true
                activeTurn = lastClosedTurn + 1
                nextStepInTurn = 0
                stepByMessage.clear()
                data.put("turn", activeTurn)
                emit("turn/start", data)
            }
            !isRunning && runActive -> {
                val endingTurn = activeTurn.takeIf { it > 0 } ?: (lastClosedTurn + 1)
                runActive = false
                data.put("turn", endingTurn)
                if (!data.has("reason")) data.put("reason", reason ?: "completed")
                emit("turn/end", data)
                lastClosedTurn = endingTurn
                activeTurn = 0
            }
            else -> {
                data.put("turn", turnForEvent())
                emit("turn/status", data)
            }
        }
    }

    @Synchronized
    fun messageCreated(message: ChatMessage) {
        val messageJson = JSONObject().apply {
            put("id", message.id)
            put("role", message.role)
            put("content", message.content)
            put("isStreaming", message.isStreaming)
            put("isAwaitingModelResponse", message.isAwaitingModelResponse)
            if (message.attachmentNames.isNotEmpty()) put("attachments", JSONArray(message.attachmentNames))
            if (message.error != null) put("error", message.error)
        }
        when (message.role) {
            "user" -> emit("user/message", JSONObject().apply {
                put("turn", turnForEvent())
                put("message", messageJson)
            })
            "assistant" -> {
                // A placeholder is an Android UI concern; DSH's canonical
                // `assistant/message` is emitted when the turn settles.
                emit("assistant/placeholder", JSONObject().put("messageId", message.id).put("message", messageJson))
                textByMessage[message.id] = message.content
                trackBlocks(message.id, message.toolBlocks)
            }
            else -> emit("system/message", JSONObject().put("message", messageJson))
        }
    }

    /** Raw provider usage hook — feeds the DSH tokenUsage projection. */
    @Synchronized
    fun rawUsageChunk(messageId: String, usage: org.json.JSONObject, turn: Int, step: Int = 0) {
        if (!usage.keys().hasNext()) return
        emit("assistant/chunk", JSONObject().apply {
            put("messageId", messageId)
            put("turn", turn)
            put("step", step)
            put("chunk", JSONObject().put("type", "usage").put("usage", usage))
        })
    }

    /** Raw provider text hook. This deliberately sits ahead of Compose's
     * throttled projection: every LLM delta becomes an append-only
     * `assistant/chunk` event. Sequencing and live fan-out happen immediately
     * under the hub lock; Room persistence stays on its serial IO worker.
     */
    @Synchronized
    fun rawTextChunk(messageId: String, text: String) {
        if (text.isEmpty()) return
        textByMessage[messageId] = (textByMessage[messageId] ?: "") + text
        emit("assistant/chunk", JSONObject().apply {
            put("messageId", messageId)
            put("chunk", JSONObject().put("type", "text-delta").put("text", text))
        })
    }

    /** Raw provider thinking chunk, parallel to [rawTextChunk]. */
    @Synchronized
    fun rawReasoningChunk(messageId: String, blockId: String, text: String) {
        if (text.isEmpty()) return
        val key = reasoningKey(messageId, blockId)
        reasoningByBlock[key] = (reasoningByBlock[key] ?: "") + text
        emit("assistant/chunk", JSONObject().apply {
            put("messageId", messageId)
            put("chunk", JSONObject().apply {
                put("type", "reasoning-delta")
                put("text", text)
                put("index", blockId)
            })
        })
    }

    /** Raw accumulated tool-argument snapshot reduced to an append delta. */
    @Synchronized
    fun rawToolInput(messageId: String, toolUseId: String, accumulated: String) {
        val byTool = toolsByMessage.getOrPut(messageId) { mutableMapOf() }
        val previous = byTool[toolUseId]
        val before = previous?.toolArgs.orEmpty()
        if (accumulated == before) return
        if (accumulated.startsWith(before)) {
            val delta = accumulated.substring(before.length)
            if (delta.isNotEmpty()) {
                emit("assistant/chunk", JSONObject().apply {
                    put("messageId", messageId)
                    put("chunk", JSONObject().apply {
                        // DSH-compatible name: this is a delta of the
                        // assistant's tool-call input block.
                        put("type", "tool-call-delta")
                        put("callId", toolUseId)
                        put("toolUseId", toolUseId)
                        put("text", delta)
                    })
                })
            }
        } else {
            // Tool argument providers occasionally restart a partial JSON
            // object. This is not a text append, so surface an explicit
            // Android status replacement rather than fabricating a delta.
            emit("tool/status", JSONObject().apply {
                put("messageId", messageId)
                put("call", JSONObject().apply {
                    put("id", toolUseId)
                    put("toolUseId", toolUseId)
                    put("toolArgs", accumulated)
                })
            })
        }
        byTool[toolUseId] = ToolState(
            toolArgs = accumulated,
            content = previous?.content.orEmpty(),
            status = previous?.status,
            title = previous?.title.orEmpty(),
            durationMs = previous?.durationMs ?: 0L,
        )
    }

    /** Raw tool-call boundary emitted before the UI's structural projection. */
    @Synchronized
    fun toolCall(messageId: String, toolUseId: String, toolName: String, startedAtMs: Long) {
        val byTool = toolsByMessage.getOrPut(messageId) { mutableMapOf() }
        if (byTool.containsKey(toolUseId)) return
        byTool[toolUseId] = ToolState("", "", ToolBlockStatus.STREAMING, toolName, 0L)
        emit("tool/call", JSONObject().apply {
            put("messageId", messageId)
            put("call", JSONObject().apply {
                put("id", toolUseId)
                put("callId", toolUseId)
                put("toolUseId", toolUseId)
                put("name", toolName)
                put("toolName", toolName)
                put("toolStatus", ToolBlockStatus.STREAMING.name)
                put("startTimeMs", startedAtMs)
            })
        })
    }

    /** Actual terminal tool result, emitted at execution completion. */
    @Synchronized
    fun toolResult(messageId: String, block: AssistantBlock) {
        val byTool = toolsByMessage.getOrPut(messageId) { mutableMapOf() }
        byTool[block.id] = ToolState(block.toolArgs, block.content, block.toolStatus, block.toolTitle, block.durationMs)
        emit("tool/result", JSONObject().apply {
            put("messageId", messageId)
            put("call", toolCallJson(block))
            put("result", toolResultJson(block))
        })
    }

    /** Streaming tool stdout/stderr line from the native shell callback. */
    @Synchronized
    fun rawToolOutput(messageId: String, toolUseId: String, text: String) {
        if (text.isEmpty()) return
        val byTool = toolsByMessage.getOrPut(messageId) { mutableMapOf() }
        val previous = byTool[toolUseId]
        byTool[toolUseId] = ToolState(
            toolArgs = previous?.toolArgs.orEmpty(),
            content = previous?.content.orEmpty() + text,
            status = previous?.status ?: ToolBlockStatus.RUNNING,
            title = previous?.title.orEmpty(),
            durationMs = previous?.durationMs ?: 0L,
        )
        emit("assistant/chunk", JSONObject().apply {
            put("messageId", messageId)
            put("chunk", JSONObject().apply {
                // A tool result may stream before its terminal tool/result
                // event (shell stdout). Keep the block identity explicit.
                put("type", "tool-result-delta")
                put("callId", toolUseId)
                put("toolUseId", toolUseId)
                put("text", text)
            })
        })
    }

    /**
     * Called where Android publishes its throttled UI projection. This is a
     * repair/status source only; normal text and reasoning were already logged
     * by the raw provider chunk hooks above.
     */
    @Synchronized
    fun streamingUpdate(
        messageId: String,
        content: String,
        blocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean,
    ) {
        val previous = textByMessage[messageId] ?: ""
        if (content != previous) {
            when {
                // Compose is behind the raw provider stream; do not replace a
                // newer event-log projection with an older UI snapshot.
                previous.startsWith(content) -> Unit
                content.startsWith(previous) -> {
                val delta = content.substring(previous.length)
                if (delta.isNotEmpty()) {
                    emit("assistant/chunk", JSONObject().apply {
                        put("messageId", messageId)
                        put("chunk", JSONObject().put("type", "text-delta").put("text", delta))
                    })
                }
                textByMessage[messageId] = content
                }
                else -> {
                emit("assistant.replace", JSONObject().apply {
                    put("messageId", messageId)
                    put("content", content)
                    put("isStreaming", true)
                })
                textByMessage[messageId] = content
                }
            }
        }
        emitReasoningDeltas(messageId, blocks, isStreaming = true)
        emitToolTransitions(messageId, blocks)
        // The stream projection itself is intentionally throttled, but a run
        // status is only meaningful at a state boundary. Do not turn every
        // text flush into a redundant status frame.
        if (lastAwaitingModelResponse != isAwaitingModelResponse) {
            lastAwaitingModelResponse = isAwaitingModelResponse
            emit("turn/status", JSONObject().apply {
                put("isRunning", true)
                put("isAwaitingModelResponse", isAwaitingModelResponse)
            })
        }
    }

    @Synchronized
    fun messageSettled(
        messageId: String,
        content: String,
        blocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean,
    ) {
        val reasoning = blocks.filter { it.kind == "thinking" }.joinToString("") { it.content }
        val calls = JSONArray()
        val results = JSONArray()
        blocks.filter { it.kind == "tool_use" }.forEach { block ->
            val call = toolCallJson(block)
            calls.put(call)
            if (isTerminal(block.toolStatus)) {
                results.put(JSONObject().apply {
                    put("toolUseId", block.id)
                    put("id", block.id)
                    put("name", block.toolName)
                    put("toolName", block.toolName)
                    put("output", block.content)
                    put("success", block.toolStatus == ToolBlockStatus.SUCCESS)
                    put("toolStatus", block.toolStatus?.name ?: JSONObject.NULL)
                })
            }
        }
        val knownText = textByMessage[messageId]
        if (knownText != content) {
            emit("assistant.replace", JSONObject().apply {
                put("messageId", messageId)
                put("content", content)
                if (reasoning.isNotEmpty()) put("reasoning", reasoning)
                put("isStreaming", false)
            })
        }
        emit("assistant/message", JSONObject().apply {
            put("messageId", messageId)
            put("content", content)
            put("isStreaming", false)
            put("isAwaitingModelResponse", isAwaitingModelResponse)
            if (reasoning.isNotEmpty()) put("reasoning", reasoning)
            if (calls.length() > 0) put("toolCalls", calls)
            if (results.length() > 0) put("toolResults", results)
            put("message", JSONObject().apply {
                put("id", messageId)
                put("role", "assistant")
                put("content", JSONArray().apply {
                    if (content.isNotEmpty()) put(JSONObject().put("type", "text").put("text", content))
                    if (reasoning.isNotEmpty()) put(JSONObject().put("type", "thinking").put("text", reasoning))
                    for (i in 0 until calls.length()) {
                        put(JSONObject().put("type", "tool_use").put("value", calls.getJSONObject(i)))
                    }
                })
            })
        })
        textByMessage.remove(messageId)
        blocks.filter { it.kind == "thinking" }.forEach { reasoningByBlock.remove(reasoningKey(messageId, it.id)) }
        toolsByMessage.remove(messageId)
        stepByMessage.remove(messageId)
        lastAwaitingModelResponse = null
    }

    @Synchronized
    fun clearMessage(messageId: String) {
        textByMessage.remove(messageId)
        toolsByMessage.remove(messageId)
        reasoningByBlock.keys.filter { it.startsWith("$messageId\u0000") }.forEach(reasoningByBlock::remove)
        stepByMessage.remove(messageId)
    }

    private fun emitReasoningDeltas(messageId: String, blocks: List<AssistantBlock>, isStreaming: Boolean) {
        blocks.filter { it.kind == "thinking" }.forEach { block ->
            val key = reasoningKey(messageId, block.id)
            val previous = reasoningByBlock[key] ?: ""
            if (block.content == previous) return@forEach
            if (block.content.startsWith(previous)) {
                val delta = block.content.substring(previous.length)
                if (delta.isNotEmpty()) {
                    emit("assistant/chunk", JSONObject().apply {
                        put("messageId", messageId)
                        put("chunk", JSONObject().apply {
                            put("type", "reasoning-delta")
                            put("text", delta)
                            put("index", block.id)
                        })
                    })
                }
            } else {
                emit("assistant.replace", JSONObject().apply {
                    put("messageId", messageId)
                    put("content", textByMessage[messageId] ?: "")
                    put("reasoning", block.content)
                    put("blockId", block.id)
                    put("isStreaming", isStreaming)
                })
            }
            reasoningByBlock[key] = block.content
        }
    }

    private fun emitToolTransitions(messageId: String, blocks: List<AssistantBlock>) {
        val previous = toolsByMessage.getOrPut(messageId) { mutableMapOf() }
        blocks.filter { it.kind == "tool_use" }.forEach { block ->
            val before = previous[block.id]
            val after = ToolState(
                toolArgs = block.toolArgs,
                content = block.content,
                status = block.toolStatus,
                title = block.toolTitle,
                durationMs = block.durationMs,
            )
            val call = toolCallJson(block)
            when {
                before == null -> emit("tool/call", JSONObject().apply {
                    put("messageId", messageId)
                    put("call", call)
                })
                isTerminal(after.status) && before.status != after.status -> emit("tool/result", JSONObject().apply {
                    put("messageId", messageId)
                    put("call", call)
                    put("result", toolResultJson(block))
                })
                before != after -> emit("tool/status", JSONObject().apply {
                    put("messageId", messageId)
                    put("call", call)
                    if (after.content != before.content && after.content.isNotEmpty()) {
                        put("result", toolResultJson(block))
                    }
                })
            }
            previous[block.id] = after
        }
    }

    private fun trackBlocks(messageId: String, blocks: List<AssistantBlock>) {
        if (blocks.isEmpty()) return
        val target = toolsByMessage.getOrPut(messageId) { mutableMapOf() }
        blocks.filter { it.kind == "tool_use" }.forEach { block ->
            target[block.id] = ToolState(block.toolArgs, block.content, block.toolStatus, block.toolTitle, block.durationMs)
        }
        blocks.filter { it.kind == "thinking" }.forEach { block ->
            reasoningByBlock[reasoningKey(messageId, block.id)] = block.content
        }
    }

    private fun toolCallJson(block: AssistantBlock): JSONObject = JSONObject().apply {
        put("id", block.id)
        put("toolUseId", block.id)
        put("name", block.toolName)
        put("toolName", block.toolName)
        put("title", block.toolTitle)
        put("toolStatus", block.toolStatus?.name ?: JSONObject.NULL)
        put("toolArgs", block.toolArgs)
        val parsedInput = runCatching { JSONObject(block.toolArgs) }.getOrNull()
        if (parsedInput != null) put("input", parsedInput)
        if (block.startTimeMs > 0L) put("startTimeMs", block.startTimeMs)
        if (block.durationMs > 0L) put("durationMs", block.durationMs)
    }

    private fun toolResultJson(block: AssistantBlock): JSONObject = JSONObject().apply {
        put("toolUseId", block.id)
        put("id", block.id)
        put("name", block.toolName)
        put("toolName", block.toolName)
        put("output", block.content)
        put("success", block.toolStatus == ToolBlockStatus.SUCCESS)
        put("toolStatus", block.toolStatus?.name ?: JSONObject.NULL)
        if (block.durationMs > 0L) put("durationMs", block.durationMs)
    }

    private fun isTerminal(status: ToolBlockStatus?): Boolean = status == ToolBlockStatus.SUCCESS ||
        status == ToolBlockStatus.FAILED ||
        status == ToolBlockStatus.CANCELLED ||
        status == ToolBlockStatus.TIMEOUT

    private fun reasoningKey(messageId: String, blockId: String): String = "$messageId\u0000$blockId"

    private fun emit(type: String, data: JSONObject) {
        // SessionEvent is intentionally wire-native: every provider-level
        // chunk and tool transition carries the DSH turn/step context rather
        // than forcing the browser to infer it from mutable UI state.
        if (!data.has("turn")) data.put("turn", turnForEvent())
        val messageId = data.optString("messageId", "")
        if (messageId.isNotBlank() && !data.has("step")) {
            data.put("step", stepByMessage.getOrPut(messageId) { ++nextStepInTurn })
        }
        SessionEventHub.append(sessionId(), type, data)
    }

    private fun turnForEvent(): Int = activeTurn.takeIf { it > 0 } ?: (lastClosedTurn + 1)
}
