package com.openminis.app.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the central capability catalog: stable ids, defaults, per-item
 * semantics, legacy preset mapping and every method→capability mapping.
 */
class RemoteCapabilityCatalogTest {

    @Test
    fun `all ids are unique and labelled`() {
        val ids = RemoteCapabilityCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (cap in RemoteCapabilityCatalog.ALL) {
            assertTrue(cap.id.isNotBlank())
            assertTrue(cap.label.isNotBlank())
            assertTrue(cap.description.isNotBlank())
            assertNotNull(RemoteCapabilityCatalog.byId(cap.id))
        }
        assertEquals(RemoteCapabilityCatalog.ALL.size, RemoteCapabilityCatalog.defaultState().size)
    }

    @Test
    fun `defaults keep management surfaces on and dangerous surfaces off`() {
        val d = RemoteCapabilityCatalog.defaultState()
        // 现有管理/聊天能力保持默认可用，避免升级后网页全部失效。
        for (id in listOf(
            "chat", "files.read", "files.write", "shell", "providers.read",
            "providers.manage", "skills.manage", "memory.manage", "mcp.manage",
            "environments.manage", "storage.manage", "scheduled.manage",
            "agent.manage", "preferences", "service.manage", "permission.manage",
            "diagnostics.list",
        )) assertTrue("$id should default ON", d[id] == true)

        // 危险能力默认关闭。
        for (id in listOf(
            "sandbox.fs", "device.view", "device.control", "ui.inspect",
            "browser.view", "browser.execute", "credentials.export",
            "diagnostics.content", "admin",
        )) assertFalse("$id should default OFF", d[id] == true)
    }

    @Test
    fun `legacy preset maps workspace-write to defaults and danger to all-on`() {
        val defaults = RemoteCapabilityCatalog.defaultState()
        assertEquals(defaults, RemoteCapabilityCatalog.valuesForPreset("workspace-write"))
        val danger = RemoteCapabilityCatalog.valuesForPreset("danger-full-access")
        assertTrue(danger.values.all { it })
        assertTrue(RemoteCapabilityCatalog.isKnownPreset("workspace-write"))
        assertTrue(RemoteCapabilityCatalog.isKnownPreset("danger-full-access"))
        assertFalse(RemoteCapabilityCatalog.isKnownPreset("everything"))
    }

    /** 关闭某一能力只禁止该能力：preset 映射的批量语义同样保持逐项覆盖。 */
    @Test
    fun `flipping one capability in a preset state touches only that row`() {
        val state = RemoteCapabilityCatalog.valuesForPreset("workspace-write").toMutableMap()
        state["device.view"] = true
        // Every other entry still matches the default of its preset row.
        for ((id, enabled) in RemoteCapabilityCatalog.defaultState()) {
            if (id == "device.view") continue
            assertEquals("$id must stay untouched", enabled, state[id])
        }
        assertEquals(true, state["device.view"])
    }

    @Test
    fun `unknown capability ids are rejected by the policy shim`() {
        assertNull(RemoteCapabilityCatalog.byId("provider.whatever-new"))
        assertNull(RemoteCapabilityCatalog.byId("device.readAll"))
        assertNull(RemoteCapabilityCatalog.byId(""))
    }

    // --------------------------------------------------------------- RPC map

    @Test
    fun `rpc methods map to their capability`() {
        assertEquals("chat", RemoteCapabilityCatalog.capabilityForRpcMethod("chat.prompt"))
        assertEquals("chat", RemoteCapabilityCatalog.capabilityForRpcMethod("chat.session.selectThinkingLevel"))
        assertEquals("chat", RemoteCapabilityCatalog.capabilityForRpcMethod("chat.session.delete"))
        assertEquals("device.control", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.tap"))
        assertEquals("device.control", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.inputText"))
        assertEquals("device.view", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.screenshot"))
        assertEquals("device.view", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.screenshot.get"))
        assertEquals("ui.inspect", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.viewTree"))
        assertEquals("shell", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.shellExecute"))
        assertEquals("sandbox.fs", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.readFile"))
        assertEquals("sandbox.fs", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.writeFile"))
        assertEquals("credentials.export", RemoteCapabilityCatalog.capabilityForRpcMethod("provider.export"))
        assertEquals("credentials.export", RemoteCapabilityCatalog.capabilityForRpcMethod("provider.import"))
        assertEquals("providers.read", RemoteCapabilityCatalog.capabilityForRpcMethod("provider.types"))
        assertEquals("providers.manage", RemoteCapabilityCatalog.capabilityForRpcMethod("provider.instances.create"))
        assertEquals("diagnostics.list", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.logs.list"))
        assertEquals("diagnostics.list", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.crash.list"))
        assertEquals("diagnostics.content", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.logs.read"))
        assertEquals("diagnostics.content", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.crash.read"))
        assertEquals("permission.manage", RemoteCapabilityCatalog.capabilityForRpcMethod("settings.capabilities.set"))
        assertEquals("permission.manage", RemoteCapabilityCatalog.capabilityForRpcMethod("settings.permissionPreset.set"))
        assertEquals("admin", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.update.install"))
        assertEquals("admin", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.minisConfig.exec"))
        assertEquals("browser.execute", RemoteCapabilityCatalog.capabilityForRpcMethod("debug.browser.executeJS"))
        assertEquals("agent.manage", RemoteCapabilityCatalog.capabilityForRpcMethod("agent.jobs.cancel"))
        assertEquals("skills.manage", RemoteCapabilityCatalog.capabilityForRpcMethod("skills.create"))
    }

    /** 绝不允许前缀自动放行：未来敏感方法未登记即拒绝。 */
    @Test
    fun `unlisted or future methods are not admitted by prefix`() {
        for (m in listOf(
            "debug.anythingAtAll", "debug.tapNew", "debug.wipeDevice",
            "provider.deleteAll", "provider.exportKey", "chat.deleteEverything",
            "settings.setAnything", "agent.kill", "mcp.readSecret",
        )) {
            assertNull("$m must not be mapped", RemoteCapabilityCatalog.capabilityForRpcMethod(m))
        }
        // rpc.discover 是唯一的无条件元数据方法。
        assertTrue(RemoteCapabilityCatalog.isUnconditionalRpcMethod("rpc.discover"))
        assertFalse(RemoteCapabilityCatalog.isUnconditionalRpcMethod("debug.appInfo"))
    }

    // -------------------------------------------------------------- DSH map

    @Test
    fun `dsh methods map payload-aware`() {
        assertEquals("chat", RemoteCapabilityCatalog.capabilityForDshRequest("session.prompt", null))
        assertEquals("chat", RemoteCapabilityCatalog.capabilityForDshRequest("session.models", JSONObject()))
        assertEquals("files.read", RemoteCapabilityCatalog.capabilityForDshRequest("host.listDirectory", JSONObject()))
        assertEquals("files.write", RemoteCapabilityCatalog.capabilityForDshRequest("host.createDirectory", JSONObject()))
        assertEquals("files.write", RemoteCapabilityCatalog.capabilityForDshRequest("workspace.delete", JSONObject()))
        assertEquals("skills.manage", RemoteCapabilityCatalog.capabilityForDshRequest("skill.list", null))
        assertEquals("agent.manage", RemoteCapabilityCatalog.capabilityForDshRequest("goal.create", JSONObject()))
        assertEquals("preferences", RemoteCapabilityCatalog.capabilityForDshRequest("settings.describe", null))
        assertEquals(
            "permission.manage",
            RemoteCapabilityCatalog.capabilityForDshRequest("settings.update", JSONObject().put("ns", "permission")),
        )
        assertEquals(
            "preferences",
            RemoteCapabilityCatalog.capabilityForDshRequest("settings.update", JSONObject().put("ns", "locale")),
        )
        assertEquals(
            "agent.manage",
            RemoteCapabilityCatalog.capabilityForDshRequest("settings.replace", JSONObject().put("ns", "agent-presets")),
        )
        assertEquals("providers.read", RemoteCapabilityCatalog.capabilityForDshRequest("llm.models", null))
        assertEquals("providers.read", RemoteCapabilityCatalog.capabilityForDshRequest("credentials.describe", null))
        assertEquals("credentials.export", RemoteCapabilityCatalog.capabilityForDshRequest("credentials.set", null))
        assertNull(RemoteCapabilityCatalog.capabilityForDshRequest("session.prompts", null))
        assertNull(RemoteCapabilityCatalog.capabilityForDshRequest("host.rm", null))
    }

    // ------------------------------------------------------------- HTTP map

    @Test
    fun `http routes map per method`() {
        assertEquals("chat", RemoteCapabilityCatalog.capabilityForHttpRoute("GET", "/api/sessions"))
        assertEquals("chat", RemoteCapabilityCatalog.capabilityForHttpRoute("POST", "/api/prompt"))
        assertEquals("chat", RemoteCapabilityCatalog.capabilityForHttpRoute("POST", "/api/cancel"))
        assertEquals("chat", RemoteCapabilityCatalog.capabilityForHttpRoute("POST", "/api/session/delete"))
        assertEquals("files.read", RemoteCapabilityCatalog.capabilityForHttpRoute("GET", "/api/file"))
        assertEquals("files.read", RemoteCapabilityCatalog.capabilityForHttpRoute("GET", "/api/files"))
        assertEquals("files.write", RemoteCapabilityCatalog.capabilityForHttpRoute("POST", "/api/file"))
        assertEquals("files.write", RemoteCapabilityCatalog.capabilityForHttpRoute("POST", "/api/edit"))
        assertEquals("shell", RemoteCapabilityCatalog.capabilityForHttpRoute("POST", "/api/shell"))
        assertNull(RemoteCapabilityCatalog.capabilityForHttpRoute("GET", "/api/status"))
        assertNull(RemoteCapabilityCatalog.capabilityForHttpRoute("GET", "/api/settings"))
        assertEquals("service.manage", RemoteCapabilityCatalog.capabilityForHttpRoute("PATCH", "/api/settings"))
        assertEquals("service.manage", RemoteCapabilityCatalog.capabilityForHttpRoute("POST", "/api/settings/restart"))
        assertEquals("permission.manage", RemoteCapabilityCatalog.capabilityForHttpRoute("PATCH", "/api/permissions"))
        assertEquals("chat", RemoteCapabilityCatalog.capabilityForHttpRoute("GET", "/api/events.mux"))
        assertNull(RemoteCapabilityCatalog.capabilityForHttpRoute("GET", "/api/unknown"))
    }

    @Test
    fun `capability json carries enabled state`() {
        val state = RemoteCapabilityCatalog.defaultState().toMutableMap().apply {
            this["device.view"] = true
        }
        val arr = RemoteCapabilityCatalog.capabilitiesJson(state)
        val deviceView = (0 until arr.length()).map { arr.getJSONObject(it) }
            .first { it.getString("id") == "device.view" }
        assertTrue(deviceView.getBoolean("enabled"))
        assertFalse(deviceView.getBoolean("defaultEnabled"))
        assertEquals("device.view", deviceView.getString("id"))
    }
}
