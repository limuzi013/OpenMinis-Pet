package com.openminis.app.tools.android

import java.util.concurrent.ConcurrentHashMap

/** Lightweight Android-debug state; not a Conversation or Workspace. */
object AndroidDebugSessionStore {
    data class DebugSession(
        val sessionId: String,
        val targetPackage: String? = null,
        val launchActivity: String? = null,
        val artifactPath: String? = null,
        val logCursor: String? = null,
        val lastPidSet: Set<Int> = emptySet(),
        val lastUiGeneration: Long? = null,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private val sessions = ConcurrentHashMap<String, DebugSession>()

    fun get(sessionId: String): DebugSession = sessions.getOrPut(sessionId) { DebugSession(sessionId) }

    fun update(sessionId: String, transform: (DebugSession) -> DebugSession): DebugSession =
        sessions.compute(sessionId) { _, current ->
            transform(current ?: DebugSession(sessionId)).copy(updatedAt = System.currentTimeMillis())
        }!!

    fun clear(sessionId: String) { sessions.remove(sessionId) }

    internal fun clearForTests() { sessions.clear() }
}
