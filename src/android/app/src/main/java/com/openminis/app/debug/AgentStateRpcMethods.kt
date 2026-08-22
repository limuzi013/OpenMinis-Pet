package com.openminis.app.debug

import android.content.Context
import com.openminis.app.tools.AgentStateStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * `agent.goal.*` / `agent.todo.*` / `agent.plan.*` / `agent.deliverables.*`
 * RPC handlers for the Web Remote bars (DeepSeek Harness style).
 */
internal object AgentStateRpcMethods {

    private fun requireSession(params: JSONObject): String =
        params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }

    // ── Goal ────────────────────────────────────────────────────────────────
    fun goalGet(context: Context, params: JSONObject): JSONObject {
        val g = AgentStateStore.goalGet(requireSession(params))
        return goalJson(g)
    }

    fun goalSet(context: Context, params: JSONObject): JSONObject {
        val sid = requireSession(params)
        val g = AgentStateStore.goalSet(sid, params.optString("text", ""))
        return goalJson(g)
    }

    fun goalSetActive(context: Context, params: JSONObject): JSONObject {
        val sid = requireSession(params)
        val g = AgentStateStore.goalSetActive(sid, params.optBoolean("active", true))
        return goalJson(g)
    }

    private fun goalJson(g: AgentStateStore.Goal): JSONObject = JSONObject().apply {
        put("id", g.id)
        put("revision", g.revision)
        put("text", g.text)
        put("active", g.active)
        put("phase", g.phase)
        put("maxGoalRounds", g.maxGoalRounds)
        put("createdAt", g.createdAt)
        put("updatedAt", g.updatedAt)
    }

    // ── Todo ────────────────────────────────────────────────────────────────
    fun todoGet(context: Context, params: JSONObject): JSONObject {
        val t = AgentStateStore.todoGet(requireSession(params))
        return todoJson(t)
    }

    fun todoReplace(context: Context, params: JSONObject): JSONObject {
        val sid = requireSession(params)
        val items = mutableListOf<AgentStateStore.TodoItem>()
        params.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items.add(
                    AgentStateStore.TodoItem(
                        id = o.optString("id", "").ifEmpty { java.util.UUID.randomUUID().toString() },
                        title = o.optString("title", ""),
                        status = o.optString("status", "pending"),
                    )
                )
            }
        }
        return todoJson(AgentStateStore.todoReplace(sid, items))
    }

    private fun todoJson(t: AgentStateStore.TodoList): JSONObject = JSONObject().apply {
        val arr = JSONArray()
        for (item in t.items) {
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("status", item.status)
            })
        }
        put("items", arr)
        put("updatedAt", t.updatedAt)
    }

    // ── Plan mode (soft) ────────────────────────────────────────────────────
    fun planGet(context: Context, params: JSONObject): JSONObject {
        val p = AgentStateStore.planGet(requireSession(params))
        return planJson(p)
    }

    fun planSet(context: Context, params: JSONObject): JSONObject {
        val sid = requireSession(params)
        val p = AgentStateStore.planSet(sid, params.optString("mode", "off"), params.optString("plan", ""))
        return planJson(p)
    }

    private fun planJson(p: AgentStateStore.Plan): JSONObject = JSONObject().apply {
        put("mode", p.mode)
        put("plan", p.plan)
        put("updatedAt", p.updatedAt)
    }

    // ── Deliverables ────────────────────────────────────────────────────────
    fun deliverablesList(context: Context, params: JSONObject): JSONObject {
        val sid = requireSession(params)
        val arr = JSONArray()
        for (d in AgentStateStore.deliverablesGet(sid)) {
            arr.put(JSONObject().apply {
                put("path", d.path)
                put("action", d.action)
                put("at", d.at)
            })
        }
        return JSONObject().apply {
            put("files", arr)
            put("count", arr.length())
        }
    }

    fun deliverablesClear(context: Context, params: JSONObject): JSONObject {
        AgentStateStore.deliverablesClear(requireSession(params))
        return JSONObject().put("ok", true)
    }
}
