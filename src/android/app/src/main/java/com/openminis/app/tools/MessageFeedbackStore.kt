package com.openminis.app.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Per-message feedback sidecar (DeepSeek Harness `message-feedback`):
 * thumbs with an optional note, persisted as a small JSON file next
 * to app files. Independent from the immutable message log on purpose.
 *
 * Two wire dialects share this one store:
 *  - OpenMinis native RPC (`chat.feedback.put/delete/listForMessages`):
 *    `kind` = up|down (legacy shape).
 *  - DSH messageFeedback Remote (`messageFeedback` family): `rating` =
 *    positive|negative, plus `version` (CAS token), `createdAt`,
 *    `updatedAt` and `sessionId`. `ifVersion` compares against the stored
 *    version; conflicts return `version-conflict` with the current item.
 *
 * Old rows without a version (legacy numeric `at` only) are upgraded
 * in-place on first load (stable version, timestamps derived from `at`);
 * legacy rows without a sessionId are visible to the native RPC but not to
 * the DSH session listing (their owning session is unknown).
 */
object MessageFeedbackStore {

    data class Feedback(
        val kind: String, // "up" | "down"
        val note: String = "",
        val at: Long = System.currentTimeMillis(),
        /** DSH CAS token; stable per item, regenerated on every mutation. */
        val version: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val sessionId: String = "",
    ) {
        /** DSH rating spelling. */
        val rating: String get() = if (kind == "down") "negative" else "positive"
    }

    /** DSH business-call outcome (mirrors `messageFeedback_*_result$schema`). */
    sealed class DshResult {
        data class Ok(val item: JSONObject) : DshResult()
        data class Err(val code: String, val payload: JSONObject) : DshResult()
    }

    private fun file(context: Context): File =
        File(context.filesDir, "web-message-feedback.json")

    /** Set when [loadAll] hit a parse error: the on-disk file is corrupt and
     *  must be backed up as .corrupt before the next write overwrites it. */
    private var corruptFile = false

    private fun loadAll(context: Context): MutableMap<String, Feedback> {
        val f = file(context)
        if (!f.exists()) return mutableMapOf()
        return try {
            val obj = JSONObject(f.readText())
            val out = mutableMapOf<String, Feedback>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val v = obj.optJSONObject(id) ?: continue
                val legacyAt = v.optLong("at", System.currentTimeMillis())
                out[id] = Feedback(
                    kind = if (v.optString("kind", "up") == "down") "down" else "up",
                    note = v.optString("note", ""),
                    at = legacyAt,
                    version = v.optString("version", "").ifEmpty { UUID.randomUUID().toString() },
                    createdAt = v.optLong("createdAt", legacyAt),
                    updatedAt = v.optLong("updatedAt", legacyAt),
                    sessionId = v.optString("sessionId", ""),
                )
            }
            corruptFile = false
            out
        } catch (e: Exception) {
            // Never silently clear a corrupt sidecar: keep the original file so
            // the damage is not compounded, and let the next save back it up.
            Log.w("MessageFeedbackStore", "failed to parse feedback file: ${e.message}", e)
            corruptFile = true
            mutableMapOf()
        }
    }

    @Synchronized
    private fun saveAll(context: Context, all: Map<String, Feedback>) {
        val obj = JSONObject()
        for ((id, fb) in all) {
            obj.put(id, JSONObject().apply {
                put("kind", fb.kind)
                put("note", fb.note)
                put("at", fb.at)
                put("version", fb.version)
                put("createdAt", fb.createdAt)
                put("updatedAt", fb.updatedAt)
                if (fb.sessionId.isNotEmpty()) put("sessionId", fb.sessionId)
            })
        }
        runCatching {
            val f = file(context)
            if (corruptFile && f.exists()) {
                // Preserve the damaged file before overwriting it.
                val backup = File(f.parentFile, f.name + ".corrupt")
                if (!backup.exists() || backup.delete()) f.renameTo(backup)
            }
            writeAtomic(f, obj.toString())
        }
        corruptFile = false
    }

    /** Write [content] to [f] via a temp file + rename (atomic replace). */
    private fun writeAtomic(f: File, content: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(f)) {
            // renameTo can fail when the target already exists on some
            // filesystems; fall back to a plain write.
            f.writeText(content)
            tmp.delete()
        }
    }

    @Synchronized
    fun put(context: Context, messageId: String, kind: String, note: String = ""): Feedback {
        val all = loadAll(context)
        val now = System.currentTimeMillis()
        val previous = all[messageId]
        val fb = Feedback(
            kind = if (kind == "down") "down" else "up",
            note = note.trim(),
            at = now,
            version = UUID.randomUUID().toString(),
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
            sessionId = previous?.sessionId.orEmpty(),
        )
        all[messageId] = fb
        saveAll(context, all)
        return fb
    }

    @Synchronized
    fun delete(context: Context, messageId: String): Boolean {
        val all = loadAll(context)
        val removed = all.remove(messageId) != null
        if (removed) saveAll(context, all)
        return removed
    }

    @Synchronized
    fun listForMessages(context: Context, messageIds: List<String>): Map<String, Feedback> {
        val all = loadAll(context)
        return all.filterKeys { it in messageIds }
    }

    @Synchronized
    fun all(context: Context): Map<String, Feedback> = loadAll(context)

    fun toJson(map: Map<String, Feedback>): JSONArray {
        val arr = JSONArray()
        for ((id, fb) in map) {
            arr.put(JSONObject().apply {
                put("messageId", id)
                put("kind", fb.kind)
                put("note", fb.note)
                put("at", fb.at)
                put("version", fb.version)
                put("createdAt", fb.createdAt)
                put("updatedAt", fb.updatedAt)
                put("sessionId", fb.sessionId)
            })
        }
        return arr
    }

    // ------------------------------------------------------------- DSH dialect

    /** DSH row shape (`messageFeedback_item$schema`): no `sessionId` key. */
    fun dshItem(messageId: String, fb: Feedback): JSONObject = JSONObject().apply {
        put("messageId", messageId)
        put("rating", fb.rating)
        if (fb.note.isNotEmpty()) put("note", fb.note)
        put("version", fb.version)
        put("createdAt", fb.createdAt)
        put("updatedAt", fb.updatedAt)
    }

    /** `messageFeedback/list` — items for one session, newest first. */
    @Synchronized
    fun listForSession(context: Context, sessionId: String): List<Pair<String, Feedback>> =
        loadAll(context)
            .filter { it.value.sessionId == sessionId }
            .toList()
            .sortedByDescending { it.second.updatedAt }

    /**
     * `messageFeedback/put` with real compare-and-set semantics.
     * [ifVersion] == null allows creating/overwriting; otherwise it must equal
     * the stored version (or the item must not exist when ifVersion is null).
     */
    @Synchronized
    fun putDsh(
        context: Context,
        sessionId: String,
        messageId: String,
        rating: String,
        note: String?,
        ifVersion: String?,
    ): DshResult {
        val all = loadAll(context)
        val result = casPut(all, sessionId, messageId, rating, note, ifVersion)
        if (result is DshResult.Ok) saveAll(context, all)
        return result
    }

    /** Pure compare-and-set core, JVM-testable without a Context. */
    internal fun casPut(
        all: MutableMap<String, Feedback>,
        sessionId: String,
        messageId: String,
        rating: String,
        note: String?,
        ifVersion: String?,
    ): DshResult {
        if (rating != "positive" && rating != "negative") {
            return DshResult.Err("bad-request", JSONObject()
                .put("code", "bad-request")
                .put("message", "rating must be positive or negative")
                .put("details", JSONObject().put("issues", JSONArray())))
        }
        if (note != null && note.isBlank()) {
            // DSH client treats a blank note as "drop the note" via clearNote;
            // a non-null-but-blank value surfaces note-blank per schema.
            return DshResult.Err("note-blank", JSONObject().put("code", "note-blank"))
        }
        val previous = all[messageId]
        if (ifVersion != null) {
            if (previous == null || previous.version != ifVersion) {
                return DshResult.Err("version-conflict", JSONObject()
                    .put("code", "version-conflict")
                    .put("current", if (previous == null) JSONObject.NULL else dshItem(messageId, previous)))
            }
        }
        val now = System.currentTimeMillis()
        val fb = Feedback(
            kind = if (rating == "negative") "down" else "up",
            note = note?.trim().orEmpty(),
            at = now,
            version = UUID.randomUUID().toString(),
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
            sessionId = sessionId,
        )
        all[messageId] = fb
        return DshResult.Ok(dshItem(messageId, fb))
    }


    @Synchronized
    fun deleteDsh(context: Context, sessionId: String, messageId: String, ifVersion: String?): DshResult {
        val all = loadAll(context)
        val result = casDelete(all, messageId, ifVersion)
        if (result is DshResult.Ok) saveAll(context, all)
        return result
    }

    /** Pure compare-and-set delete core, JVM-testable without a Context. */
    internal fun casDelete(all: MutableMap<String, Feedback>, messageId: String, ifVersion: String?): DshResult {
        val previous = all[messageId]
        if (previous != null && ifVersion != null && previous.version != ifVersion) {
            return DshResult.Err("version-conflict", JSONObject()
                .put("code", "version-conflict")
                .put("current", dshItem(messageId, previous)))
        }
        all.remove(messageId)
        return DshResult.Ok(JSONObject().put("absent", true))
    }
}
