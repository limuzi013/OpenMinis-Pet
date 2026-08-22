package com.openminis.app.remote

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.MinisApp
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Device-level contract checks for the DSH adapter.
 *
 * These deliberately call [DshApiAdapter.handle], the same boundary used by
 * Web Remote, rather than private helpers. The Workspace mutation uses a unique
 * native conversation group and removes it in `finally`, so the test proves the
 * browser and Android really share one repository without leaving test data.
 */
@RunWith(AndroidJUnit4::class)
class DshApiAdapterInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<MinisApp>()

    private suspend fun call(method: String, payload: JSONObject = JSONObject()): JSONObject {
        val response = DshApiAdapter.handle(
            context,
            method,
            JSONObject()
                .put("type", "client-request")
                .put("rpcId", "instrumentation-$method")
                .put("method", method)
                .put("payload", payload),
        )
        val result = response.getJSONObject("result")
        assertTrue("$method failed: ${result.optJSONObject("error")}", result.getBoolean("ok"))
        return result.getJSONObject("value")
    }

    private suspend fun callError(method: String, payload: JSONObject): JSONObject {
        val response = DshApiAdapter.handle(
            context,
            method,
            JSONObject()
                .put("type", "client-request")
                .put("rpcId", "instrumentation-error-$method")
                .put("method", method)
                .put("payload", payload),
        )
        val result = response.getJSONObject("result")
        assertFalse("$method unexpectedly succeeded", result.getBoolean("ok"))
        return result.getJSONObject("error")
    }

    @Test
    fun readonlyCoreResponsesExposeStrictDshContracts() = runBlocking {
        val host = call("host.describe")
        assertTrue(host.getString("version").isNotBlank())
        assertEquals("/var/minis/workspace", host.getString("cwd"))
        assertTrue(host.getInt("attachedSessions") >= 0)
        assertFalse(host.getBoolean("canOpenPath"))

        val settings = call("settings.describe")
        assertTrue(settings.getBoolean("writable"))
        assertFalse(settings.getBoolean("hasDocument"))
        val namespaces = settings.getJSONArray("namespaces")
        val byName = (0 until namespaces.length())
            .map { namespaces.getJSONObject(it) }
            .associateBy { it.getString("ns") }
        assertEquals(
            setOf("ui-theme", "locale", "permission", "agent-presets", "general"),
            byName.keys,
        )
        byName.values.forEach { view ->
            assertNotNull(view.getJSONObject("schema"))
            assertEquals("live", view.getString("applies"))
            assertTrue(view.getInt("revision") >= 0)
        }

        // Optimistic-concurrency failures must not overwrite a native edit.
        val theme = byName.getValue("ui-theme")
        val wrongRevision = if (theme.getInt("revision") == Int.MAX_VALUE) 0 else theme.getInt("revision") + 1
        val error = callError(
            "settings.update",
            JSONObject()
                .put("ns", "ui-theme")
                .put("patch", JSONObject(theme.getJSONObject("value").toString()))
                .put("expectedRevision", wrongRevision),
        )
        assertTrue(error.getString("message").contains("refresh", ignoreCase = true))

        val workspace = call("workspace.list")
        assertNotNull(workspace.getJSONArray("items"))
        assertNotNull(workspace.getJSONArray("archivedSessionIds"))
        assertNotNull(call("skill.list").getJSONArray("skills"))
        assertNotNull(call("llm.providers").getJSONArray("providers"))
        assertNotNull(call("llm.models").getJSONArray("groups"))

        val sessions = call("session.list").getJSONArray("items")
        if (sessions.length() > 0) {
            val sessionId = sessions.getJSONObject(0).getString("sessionId")
            val models = call("session.models", JSONObject().put("sessionId", sessionId))
            val ids = buildSet {
                val groups = models.getJSONArray("groups")
                for (i in 0 until groups.length()) {
                    val rows = groups.getJSONObject(i).getJSONArray("models")
                    for (j in 0 until rows.length()) add(rows.getJSONObject(j).getString("id"))
                }
            }
            val selected = models.getJSONObject("current").getString("model")
            assertTrue("current model must be a catalog entry id", selected == "unconfigured" || selected in ids)
        }
    }

    @Test
    fun workspaceRoundTripUsesNativeConversationGroups() = runBlocking {
        val token = UUID.randomUUID().toString().take(8)
        val originalName = "pet15-e2e-$token"
        val renamed = "$originalName-renamed"
        var workspaceId: String? = null
        try {
            val created = call(
                "workspace.create",
                JSONObject().put("path", "/var/minis/workspace/${java.net.URLEncoder.encode(originalName, "UTF-8")}"),
            )
            assertTrue(created.getBoolean("created"))
            workspaceId = created.getJSONObject("workspace").getString("workspaceId")
            assertEquals(originalName, context.chatRepository.getFolder(workspaceId)?.name)

            val changed = call(
                "workspace.rename",
                JSONObject().put("workspaceId", workspaceId).put("title", renamed),
            )
            assertEquals(renamed, changed.getJSONObject("workspace").getString("title"))
            assertEquals(renamed, context.chatRepository.getFolder(workspaceId)?.name)

            val listed = call("workspace.list").getJSONArray("items")
            assertTrue((0 until listed.length()).any {
                listed.getJSONObject(it).getString("workspaceId") == workspaceId
            })

            assertTrue(call("workspace.delete", JSONObject().put("workspaceId", workspaceId)).getBoolean("deleted"))
            assertEquals(null, context.chatRepository.getFolder(workspaceId))
            workspaceId = null
        } finally {
            workspaceId?.let { id ->
                if (context.chatRepository.getFolder(id) != null) context.chatRepository.dissolveFolder(id)
            }
        }
    }
}
