package com.openminis.app.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped agent state shared by the model tools, the Web Remote RPC
 * handlers and the chat UI: goal, todo list, plan mode and turn deliverables.
 *
 * Process-scoped on purpose (mirrors DeepSeek Harness's in-memory session
 * state): these are live-turn affordances, not durable records. Deliverables
 * are tracked from the tool layer so they survive model forgetfulness.
 */
object AgentStateStore {

    data class Goal(
        val text: String = "",
        val active: Boolean = true,
        /** DSH-compatible durable-looking identity for the process lifetime. */
        val id: String = "",
        val revision: Int = 0,
        val phase: String = "active", // active | paused | complete
        val maxGoalRounds: Int = 8,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class TodoItem(val id: String, val title: String, val status: String = "pending")
    data class TodoList(
        val items: List<TodoItem> = emptyList(),
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class Plan(
        val mode: String = "off", // "off" | "plan"
        val plan: String = "",
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class Deliverable(
        val path: String,
        val action: String, // "write" | "edit"
        val at: Long,
    )

    private val goals = ConcurrentHashMap<String, Goal>()
    private val todos = ConcurrentHashMap<String, TodoList>()
    private val plans = ConcurrentHashMap<String, Plan>()
    private val deliverables = ConcurrentHashMap<String, LinkedHashMap<String, Deliverable>>()

    // ── Goal ────────────────────────────────────────────────────────────────
    fun goalGet(sessionId: String): Goal = goals[sessionId] ?: Goal()

    fun goalSet(sessionId: String, text: String, maxGoalRounds: Int? = null): Goal {
        val clean = text.trim()
        if (clean.isEmpty()) {
            goals.remove(sessionId)
            return Goal()
        }
        val now = System.currentTimeMillis()
        val current = goals[sessionId]
        val g = if (current == null) {
            Goal(
                text = clean,
                active = true,
                id = UUID.randomUUID().toString(),
                revision = 1,
                phase = "active",
                maxGoalRounds = maxGoalRounds?.coerceIn(1, 100) ?: 8,
                createdAt = now,
                updatedAt = now,
            )
        } else {
            current.copy(
                text = clean,
                active = current.phase == "active",
                revision = current.revision + 1,
                maxGoalRounds = maxGoalRounds?.coerceIn(1, 100) ?: current.maxGoalRounds,
                updatedAt = now,
            )
        }
        goals[sessionId] = g
        return g
    }

    fun goalSetActive(sessionId: String, active: Boolean): Goal {
        val current = goalGet(sessionId)
        if (current.text.isBlank()) return current
        val g = current.copy(
            active = active,
            phase = if (active) "active" else "paused",
            revision = current.revision + 1,
            updatedAt = System.currentTimeMillis(),
        )
        goals[sessionId] = g
        return g
    }

    fun goalComplete(sessionId: String): Goal {
        val current = goalGet(sessionId)
        if (current.text.isBlank()) return current
        val g = current.copy(
            active = false,
            phase = "complete",
            revision = current.revision + 1,
            updatedAt = System.currentTimeMillis(),
        )
        goals[sessionId] = g
        return g
    }

    fun goalClear(sessionId: String): Goal? = goals.remove(sessionId)

    // ── Todo ────────────────────────────────────────────────────────────────
    fun todoGet(sessionId: String): TodoList = todos[sessionId] ?: TodoList()

    fun todoReplace(sessionId: String, items: List<TodoItem>): TodoList {
        val t = TodoList(items = items.distinctBy { it.id }, updatedAt = System.currentTimeMillis())
        if (t.items.isEmpty()) todos.remove(sessionId) else todos[sessionId] = t
        return todos[sessionId] ?: TodoList()
    }

    // ── Plan mode (soft) ────────────────────────────────────────────────────
    fun planGet(sessionId: String): Plan = plans[sessionId] ?: Plan()

    fun planSet(sessionId: String, mode: String, plan: String = ""): Plan {
        val m = if (mode == "plan") "plan" else "off"
        val p = Plan(mode = m, plan = if (m == "plan") plan.trim() else "", updatedAt = System.currentTimeMillis())
        if (m == "off") plans.remove(sessionId) else plans[sessionId] = p
        return plans[sessionId] ?: Plan()
    }

    // ── Deliverables ────────────────────────────────────────────────────────
    fun recordDeliverable(sessionId: String, path: String, action: String) {
        if (path.isBlank()) return
        val map = deliverables.getOrPut(sessionId) { LinkedHashMap() }
        map[path] = Deliverable(path = path, action = action, at = System.currentTimeMillis())
    }

    fun deliverablesGet(sessionId: String): List<Deliverable> =
        deliverables[sessionId]?.values?.toList()?.reversed() ?: emptyList()

    fun deliverablesClear(sessionId: String) {
        deliverables.remove(sessionId)
    }
}
