package com.openminis.app.debug

import android.content.Context
import com.openminis.app.scheduled.ScheduledAgentRunner
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * `scheduled.*` RPC handlers shared by the Android UI and Minis Web.
 *
 * Every mutation goes through [ScheduledTaskManager], so alarms, persisted rows,
 * run history and the native Scheduled Tasks screens all observe one source of
 * truth. Browser callers may create and edit tasks, but Android still owns the
 * actual AlarmManager scheduling and execution lifecycle.
 */
internal object ScheduledTaskRpcMethods {

    /** Fire-and-forget scope for run-now invocations (mirrors ScheduledAgentRunner.bgScope). */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun manager(context: Context): ScheduledTaskManager = ScheduledTaskManager(context)

    fun list(context: Context): JSONObject {
        val tasks = manager(context).list()
        return JSONObject().apply {
            put("tasks", JSONArray().apply { tasks.forEach { put(taskJson(it)) } })
            put("count", tasks.size)
        }
    }

    fun get(context: Context, params: JSONObject): JSONObject {
        val task = requireTask(manager(context), requiredTaskId(params))
        return JSONObject().put("task", taskJson(task))
    }

    fun create(context: Context, params: JSONObject): JSONObject {
        val task = parseTask(params, current = null)
        val created = manager(context).create(task)
        return JSONObject().put("task", taskJson(created)).put("created", true)
    }

    fun update(context: Context, params: JSONObject): JSONObject {
        val mgr = manager(context)
        val current = requireTask(mgr, requiredTaskId(params))
        val updated = mgr.update(parseTask(params, current))
        return JSONObject().put("task", taskJson(updated)).put("updated", true)
    }

    fun toggle(context: Context, params: JSONObject): JSONObject {
        val taskId = requiredTaskId(params)
        if (!params.has("enabled")) throw RPCException(-32602, "Missing 'enabled' param")
        val mgr = manager(context)
        requireTask(mgr, taskId)
        val enabled = params.optBoolean("enabled", true)
        mgr.setEnabled(taskId, enabled)
        val updated = requireTask(mgr, taskId)
        return JSONObject().put("ok", true).put("task", taskJson(updated))
    }

    fun delete(context: Context, params: JSONObject): JSONObject {
        val taskId = requiredTaskId(params)
        val mgr = manager(context)
        requireTask(mgr, taskId)
        mgr.delete(taskId)
        return JSONObject().put("ok", true).put("taskId", taskId)
    }

    fun runs(context: Context, params: JSONObject): JSONObject {
        val task = requireTask(manager(context), requiredTaskId(params))
        val runs = JSONArray().apply { task.runHistory.forEach { put(it.toJson()) } }
        return JSONObject().put("taskId", task.id).put("runs", runs).put("count", runs.length())
    }

    /** Trigger the same background runner used by the native "Run now" action. */
    fun run(context: Context, params: JSONObject): JSONObject {
        val taskId = requiredTaskId(params)
        val task = requireTask(manager(context), taskId)
        bgScope.launch { ScheduledAgentRunner.run(context, task, waitForCompletion = false) }
        return JSONObject().put("ok", true).put("taskId", taskId).put("accepted", true)
    }

    private fun requiredTaskId(params: JSONObject): String =
        params.optString("taskId", params.optString("id", "")).trim().ifEmpty {
            throw RPCException(-32602, "Missing 'taskId' param")
        }

    private fun requireTask(manager: ScheduledTaskManager, id: String): ScheduledTask =
        manager.get(id) ?: throw RPCException(-32602, "Scheduled task not found: $id")

    private fun parseTask(params: JSONObject, current: ScheduledTask?): ScheduledTask {
        val label = stringPatch(params, "label", current?.label ?: "").trim()
        if (label.isEmpty()) throw RPCException(-32602, "Scheduled task label cannot be empty")
        if (label.length > 120) throw RPCException(-32602, "Scheduled task label is too long (max 120)")

        val hour = intPatch(params, listOf("hour", "timeOfDayHour"), current?.timeOfDayHour ?: 9)
        val minute = intPatch(params, listOf("minute", "timeOfDayMinute"), current?.timeOfDayMinute ?: 0)
        if (hour !in 0..23) throw RPCException(-32602, "hour must be in 0..23")
        if (minute !in 0..59) throw RPCException(-32602, "minute must be in 0..59")

        val repeatRaw = stringPatch(params, "repeatMode", current?.repeatMode?.name ?: "ONCE").uppercase()
        val repeatMode = runCatching { ScheduledRepeatMode.valueOf(repeatRaw) }.getOrElse {
            throw RPCException(-32602, "repeatMode must be ONCE, DAILY, WEEKDAYS or CUSTOM")
        }
        val customDays = if (params.has("customDays")) parseCustomDays(params.opt("customDays"))
            else current?.customDays.orEmpty()
        if (customDays.any { it !in Calendar.SUNDAY..Calendar.SATURDAY }) {
            throw RPCException(-32602, "customDays values must use Calendar.SUNDAY..SATURDAY (1..7)")
        }
        if (repeatMode == ScheduledRepeatMode.CUSTOM && customDays.isEmpty()) {
            throw RPCException(-32602, "CUSTOM repeat mode requires at least one custom day")
        }

        val prompt = stringPatch(params, "prompt", current?.prompt ?: "")
        if (prompt.length > 100_000) throw RPCException(-32602, "prompt is too long (max 100000 chars)")

        val targetMode = if (params.has("targetMode") && !params.isNull("targetMode")) {
            val encoded = params.optString("targetMode", "NEW_SESSION")
            val parsed = ScheduledTargetMode.decode(encoded)
            if (encoded != "NEW_SESSION" && parsed is ScheduledTargetMode.NewSession) {
                throw RPCException(-32602, "Invalid targetMode; use NEW_SESSION, APPEND_TO:<sessionId>, or RERUN:<sessionId>:<messageId>")
            }
            when (parsed) {
                is ScheduledTargetMode.AppendToSession -> if (parsed.sessionId.isBlank()) {
                    throw RPCException(-32602, "APPEND_TO target requires a session id")
                }
                is ScheduledTargetMode.RerunMessage -> if (parsed.sessionId.isBlank() || parsed.messageId.isBlank()) {
                    throw RPCException(-32602, "RERUN target requires session and message ids")
                }
                ScheduledTargetMode.NewSession -> Unit
            }
            parsed
        } else current?.targetMode ?: ScheduledTargetMode.NewSession

        val startDate = nullableLongPatch(params, "startDateMs", current?.startDateMs)
        val endDate = nullableLongPatch(params, "endDateMs", current?.endDateMs)
        if (startDate != null && endDate != null && startDate > endDate) {
            throw RPCException(-32602, "startDateMs must not be later than endDateMs")
        }

        val modelId = nullableStringPatch(params, "modelId", current?.modelId)
        val modelBinding = nullableStringPatch(params, "modelBinding", current?.modelBinding)
        validateModelBinding(modelBinding)

        return ScheduledTask(
            id = current?.id ?: java.util.UUID.randomUUID().toString(),
            label = label,
            timeOfDayHour = hour,
            timeOfDayMinute = minute,
            repeatMode = repeatMode,
            customDays = customDays,
            prompt = prompt,
            targetMode = targetMode,
            modelId = modelId,
            modelBinding = modelBinding,
            enabled = if (params.has("enabled")) params.optBoolean("enabled", true) else current?.enabled ?: true,
            createdAt = current?.createdAt ?: System.currentTimeMillis(),
            startDateMs = startDate,
            endDateMs = endDate,
            lastFiredAt = current?.lastFiredAt,
            lastResultPreview = current?.lastResultPreview,
            lastResultSessionId = current?.lastResultSessionId,
            runHistory = current?.runHistory.orEmpty(),
        )
    }

    private fun parseCustomDays(raw: Any?): Set<Int> = when (raw) {
        is JSONArray -> buildSet {
            for (i in 0 until raw.length()) {
                val value = integerValue(raw.get(i), "customDays[$i]")
                if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    throw RPCException(-32602, "customDays[$i] is out of integer range")
                }
                add(value.toInt())
            }
        }
        is String -> raw.split(',').filter { it.isNotBlank() }.map {
            it.trim().toIntOrNull()
                ?: throw RPCException(-32602, "customDays must contain integers")
        }.toSet()
        null, JSONObject.NULL -> emptySet()
        else -> throw RPCException(-32602, "customDays must be an integer array or comma-separated string")
    }

    private fun validateModelBinding(value: String?) {
        if (value.isNullOrBlank()) return
        val obj = runCatching { JSONObject(value) }.getOrElse {
            throw RPCException(-32602, "modelBinding must be a JSON object string")
        }
        val type = obj.optString("type")
        val id = when (type) {
            "entry" -> obj.optString("entryId")
            "group" -> obj.optString("groupId")
            else -> throw RPCException(-32602, "modelBinding.type must be 'entry' or 'group'")
        }
        if (id.isBlank()) throw RPCException(-32602, "modelBinding is missing its target id")
    }

    private fun stringPatch(params: JSONObject, key: String, fallback: String): String =
        if (params.has(key) && !params.isNull(key)) params.optString(key, fallback) else fallback

    private fun intPatch(params: JSONObject, keys: List<String>, fallback: Int): Int {
        val key = keys.firstOrNull { params.has(it) && !params.isNull(it) } ?: return fallback
        val value = integerValue(params.get(key), key)
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw RPCException(-32602, "$key is out of integer range")
        }
        return value.toInt()
    }

    private fun nullableLongPatch(params: JSONObject, key: String, fallback: Long?): Long? {
        if (!params.has(key)) return fallback
        if (params.isNull(key)) return null
        return integerValue(params.get(key), key)
    }

    private fun integerValue(raw: Any, name: String): Long = when (raw) {
        is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
        is Float, is Double -> {
            val value = (raw as Number).toDouble()
            if (!value.isFinite() || value % 1.0 != 0.0 ||
                value < Long.MIN_VALUE.toDouble() || value > Long.MAX_VALUE.toDouble()
            ) throw RPCException(-32602, "$name must be an integer")
            value.toLong()
        }
        else -> throw RPCException(-32602, "$name must be an integer")
    }

    private fun nullableStringPatch(params: JSONObject, key: String, fallback: String?): String? {
        if (!params.has(key)) return fallback
        if (params.isNull(key)) return null
        return params.optString(key, "").trim().ifEmpty { null }
    }

    private fun taskJson(task: ScheduledTask): JSONObject = task.toJson().apply {
        put("nextTriggerMs", task.nextTriggerMs() ?: JSONObject.NULL)
        put("runCount", task.runHistory.size)
    }
}
