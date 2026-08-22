package com.openminis.app.debug

import android.content.Context
import com.openminis.app.tools.JobRegistry
import com.openminis.app.tools.SubagentLimits
import org.json.JSONArray
import org.json.JSONObject

/**
 * `agent.settings.*` RPC handlers for the Web Remote frontend.
 *
 * Mirrors the DeepSeek Harness agent knobs that are worth exposing remotely:
 * the main agent is the default (Primary) model group, lightweight tasks /
 * delegated children use the Sub group when configured (otherwise they
 * inherit Primary), and the `subagent` tool has a depth cap and a per-run
 * timeout. Model group selection lives in `provider.groups.*`; this family
 * only covers the subagent limits.
 */
internal object AgentRpcMethods {

    fun settingsGet(context: Context): JSONObject = JSONObject().apply {
        put("maxDepth", SubagentLimits.maxDepth(context))
        put("timeoutMinutes", SubagentLimits.timeoutMs(context) / 60_000L)
    }

    /**
     * Per-session Agent execution permission (DSH /permission state). This is
     * the Agent-runtime layer — deliberately distinct from RemotePermissionPolicy
     * which gates what a *browser* may invoke.
     */
    fun sessionPermissionGet(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            com.openminis.app.tools.SessionPermissionStore.preset(context, sessionId)
                ?.let { put("preset", it) }
                ?: put("preset", JSONObject.NULL)
        }
    }

    fun sessionPermissionSet(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val preset = params.optString("preset", "").ifEmpty { null }
        if (preset != null && !com.openminis.app.tools.SessionPermissionStore.isKnownPreset(preset)) {
            throw RPCException(-32602, "preset must be workspace-write | danger-full-access | null")
        }
        com.openminis.app.tools.SessionPermissionStore.setPreset(context, sessionId, preset)
        // Same three events the /permission command emits, so the DSH
        // permissions projection (App and Web) sees one state.
        val effective = preset ?: com.openminis.app.tools.SessionPermissionStore.WORKSPACE_WRITE
        val sandboxMode = if (effective == com.openminis.app.tools.SessionPermissionStore.DANGER_FULL_ACCESS)
            "danger-full-access" else "workspace-write"
        val approvalPolicy = if (effective == com.openminis.app.tools.SessionPermissionStore.DANGER_FULL_ACCESS)
            "never" else "ask"
        com.openminis.app.ui.chat.SessionEventHub.append(sessionId, "permission/preset", JSONObject().put("preset", effective))
        com.openminis.app.ui.chat.SessionEventHub.append(sessionId, "sandbox/mode", JSONObject().put("mode", sandboxMode))
        com.openminis.app.ui.chat.SessionEventHub.append(sessionId, "approval/policy", JSONObject().put("policy", approvalPolicy))
        return JSONObject().apply {
            put("sessionId", sessionId)
            if (preset != null) put("preset", preset) else put("preset", JSONObject.NULL)
        }
    }

    fun approvalsList(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty { null }
        val arr = JSONArray()
        for (r in com.openminis.app.tools.ApprovalSeam.pendingFor(sessionId)) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("sessionId", r.sessionId)
                put("tool", r.toolName)
                put("summary", r.summary)
                put("createdAt", r.createdAt)
            })
        }
        return JSONObject().put("approvals", arr)
    }

    fun approvalsAnswer(context: Context, params: JSONObject): JSONObject {
        val id = params.optString("approvalId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'approvalId' param")
        }
        if (!params.has("allowed")) throw RPCException(-32602, "Missing 'allowed' param")
        val ok = com.openminis.app.tools.ApprovalSeam.answer(id, params.optBoolean("allowed", false))
        if (!ok) throw RPCException(-32001, "No pending approval with id $id")
        return JSONObject().put("ok", true)
    }

    fun settingsSet(context: Context, params: JSONObject): JSONObject {
        val maxDepth = params.optInt("maxDepth", SubagentLimits.DEFAULT_MAX_DEPTH)
        val timeoutMinutes = params.optLong("timeoutMinutes", SubagentLimits.DEFAULT_TIMEOUT_MINUTES)
        if (maxDepth !in SubagentLimits.MAX_DEPTH_RANGE) {
            throw RPCException(-32602, "maxDepth must be in ${SubagentLimits.MAX_DEPTH_RANGE}")
        }
        if (timeoutMinutes !in SubagentLimits.TIMEOUT_MINUTES_RANGE) {
            throw RPCException(-32602, "timeoutMinutes must be in ${SubagentLimits.TIMEOUT_MINUTES_RANGE}")
        }
        SubagentLimits.save(context, maxDepth, timeoutMinutes)
        return JSONObject().apply {
            put("ok", true)
            put("maxDepth", maxDepth)
            put("timeoutMinutes", timeoutMinutes)
        }
    }

    // ── Jobs (DeepSeek Harness dsh-tool-jobs, minimal port) ────────────────

    /** agent.jobs.list: all tracked jobs, newest first, as JSON. */
    fun jobsList(context: Context, params: JSONObject): JSONObject {
        val arr = JSONArray()
        for (job in JobRegistry.list()) {
            arr.put(JSONObject().apply {
                put("id", job.id)
                put("kind", job.kind)
                put("label", job.label)
                put("status", job.status.name)
                put("detail", job.detail)
                put("startedAt", job.startedAt)
                put("finishedAt", job.finishedAt ?: JSONObject.NULL)
                put("output", JobRegistry.output(job.id) ?: "")
            })
        }
        return JSONObject().apply {
            put("jobs", arr)
            put("count", arr.length())
        }
    }

    /** agent.jobs.cancel: request cancellation of a running job by id. */
    fun jobsCancel(context: Context, params: JSONObject): JSONObject {
        val id = params.optString("id", "").ifEmpty {
            throw RPCException(-32602, "Missing 'id' param")
        }
        val job = JobRegistry.get(id)
            ?: throw RPCException(-32602, "Job not found: $id")
        val canceled = JobRegistry.kill(id, params.optString("reason", ""))
        return JSONObject().apply {
            put("id", id)
            put("canceled", canceled)
            put("status", (JobRegistry.get(id) ?: job).status.name)
        }
    }
}
