package com.openminis.app.remote

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.MinisApp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real-device smoke test of the Web Remote server's capability gates:
 * direct /api-prefixed routes, /api/rpc (JSON-RPC) and DSH unary methods.
 *
 * The test starts its OWN server on port 8877 with a temporary login
 * password and stops it afterwards. It changes the app's stored username,
 * password, port and capability state — the host-side shared_prefs
 * backup/restore (run-as tar) returns everything afterwards. Do not run
 * against a device whose Web Remote is in active use without that backup.
 */
@RunWith(AndroidJUnit4::class)
class RemoteAccessServerSmokeTest {

    private val context get() = ApplicationProvider.getApplicationContext<MinisApp>()
    private var server: RemoteAccessServer? = null
    private var cookie: String = ""
    private val base = "http://127.0.0.1:8877"
    private lateinit var savedPreset: String
    private lateinit var savedCapabilities: Map<String, Boolean>

    @Before
    fun setUp() {
        // Snapshot the user's permission state and pin deterministically.
        savedPreset = RemotePermissionPolicy.preset(context)
        savedCapabilities = RemotePermissionPolicy.capabilityState(context)
        RemotePermissionPolicy.setPreset(context, RemotePermissionPolicy.PRESET_WORKSPACE_WRITE)
        // Fixture state; host-side prefs restore returns everything later.
        RemoteAccessPrefs.setUsername(context, "smokeuser")
        RemoteAccessPrefs.setPort(context, 8877)
        RemoteAccessPrefs.setPassword(context, "SmokePass-123456".toCharArray())
        RemoteAccessPrefs.setLanAccessEnabled(context, false)
        RemoteAccessPrefs.setCloudflareTunnelEnabled(context, false)
        // The app's own boot path may have auto-started the RemoteAccessService
        // on the stored port; stop it so the fixture server owns 8877.
        RemoteAccessService.stop(context)
        Thread.sleep(400)
        val token = RemoteAccessPrefs.token(context)
        server = RemoteAccessServer(context, 8877, token, "127.0.0.1")
        // A previous instrumented run in the same long-lived process may still
        // be releasing the port; retry binds before giving up.
        var started = server!!.start()
        for (attempt in 0 until 20) {
            if (started) break
            Thread.sleep(500)
            server?.stop()
            server = RemoteAccessServer(context, 8877, token, "127.0.0.1")
            started = server!!.start()
        }
        assertTrue("server must bind", started)
        Thread.sleep(600)
    }

    @After
    fun tearDown() {
        server?.stop()
        // A previous run may have created the smoke session before the empty-
        // payload guard existed; remove it so no test data remains.
        runCatching {
            kotlinx.coroutines.runBlocking {
                val sid = "definitely-not-a-real-session"
                com.openminis.app.debug.HeadlessChatRunner.cancel(context, sid)
                (context.applicationContext as? MinisApp)?.chatRepository?.deleteSession(sid)
            }
        }
        // Restore the user's exact permission state (preset + per-cap rows).
        if (::savedPreset.isInitialized) {
            RemotePermissionPolicy.setPreset(context, savedPreset)
            for ((id, enabled) in savedCapabilities) {
                RemotePermissionPolicy.setCapability(context, id, enabled)
            }
        }
    }

    private fun request(
        path: String,
        method: String = "GET",
        body: String? = null,
        useCookie: Boolean = true,
    ): Pair<Int, JSONObject> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 4000
        conn.readTimeout = 8000
        if (useCookie && cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val header = conn.getHeaderField("Set-Cookie") ?: ""
        conn.disconnect()
        val parsed = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
        if (cookie.isEmpty() && header.contains("minis_session=")) {
            cookie = "minis_session=" + header.substringAfter("minis_session=", "").substringBefore(";")
        }
        return code to parsed
    }

    private fun rpcCode(method: String, params: JSONObject = JSONObject()): Int = request(
        "/api/rpc", "POST",
        JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", method).put("params", params).toString(),
    ).first

    private fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject =
        request(
            "/api/rpc", "POST",
            JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", method).put("params", params).toString(),
        ).second

    @Test
    fun capabilityGatesApplyOnServerForRpcHttpAndDsh() {
        // 0) Unauthenticated is rejected.
        assertEquals(401, request("/api/status", useCookie = false).first)

        // 1) Login issues the HttpOnly session cookie.
        val (loginCode, loginBody) = request(
            "/api/auth/login", "POST",
            JSONObject().put("username", "smokeuser").put("password", "SmokePass-123456").toString(),
            useCookie = false,
        )
        assertEquals(200, loginCode)
        assertTrue(loginBody.optBoolean("ok", false))
        assertTrue(cookie.isNotBlank())

        // 2) Default-on capabilities work.
        val (statusCode, _) = request("/api/status")
        assertEquals(200, statusCode)
        assertFalse(rpc("debug.appInfo").has("error"))
        assertFalse(rpc("provider.types").has("error"))
        assertFalse(rpc("chat.sessions.list", JSONObject().put("limit", 1)).has("error"))

        // 3) Default-off capabilities are denied with 403 server-side.
        assertEquals(403, rpcCode("debug.tap", JSONObject().put("x", 10).put("y", 10)))
        assertEquals(403, rpcCode("provider.export"))
        assertEquals(403, rpcCode("debug.logs.read", JSONObject().put("name", "x.log")))
        assertEquals(403, rpcCode("debug.upgrade.future", JSONObject())) // unknown → deny
        // Shell is deliberately default-ON (same sandboxed execution the
        // /api/shell route already exposed) — but it is its OWN capability:
        // disabling it kills both entries.
        assertEquals(200, rpcCode("debug.shellExecute", JSONObject().put("command", "echo ok")))

        // 4) rpc.discover lists only mapped+enabled methods, annotated.
        val discover = rpc("rpc.discover")
        assertFalse(discover.has("error"))
        val methods = discover.getJSONObject("result").getJSONArray("methods")
        var sawTap = false
        var sawUnannotated = false
        for (i in 0 until methods.length()) {
            val m = methods.getJSONObject(i)
            if (m.getString("name") == "debug.tap") sawTap = true
            if (!m.has("capability")) sawUnannotated = true
        }
        assertFalse("debug.tap must not be advertised", sawTap)
        assertFalse("every advertised method carries capability", sawUnannotated)
        assertTrue(discover.getJSONObject("result").has("capabilities"))

        // 5) /api/permissions GET + per-item PATCH (only the touched row).
        val (permsCode, perms) = request("/api/permissions")
        assertEquals(200, permsCode)
        assertTrue(perms.getJSONArray("capabilities").length() >= 20)
        val (patchCode, patched) = request(
            "/api/permissions", "PATCH",
            JSONObject().put("capability", "device.view").put("enabled", true).toString(),
        )
        assertEquals(200, patchCode)
        assertTrue(patched.optBoolean("ok", false))

        // 6) Screenshot now passes the gate (HTTP 200 JSON-RPC, not 403);
        //    the handler answers with the no-Activity error because the smoke
        //    test has no foreground Activity (or with base64 if the app is
        //    somehow on screen).
        val shotCode = rpcCode("debug.screenshot", JSONObject().put("scale", 0.3))
        assertEquals(200, shotCode)
        val shot = rpc("debug.screenshot", JSONObject().put("scale", 0.3))
        val shotOk = shot.optJSONObject("result")?.has("base64") == true
        val shotError = shot.optJSONObject("error")?.optString("message", "") ?: ""
        assertTrue("gate passed (base64 or handler error)", shotOk || shotError.contains("No active Activity"))

        // 7) DSH unary: mapped method passes the gate (chat default ON) and
        //    reaches the adapter; unmapped method is denied. Use an empty
        //    content payload so the adapter rejects it inside (bad-request)
        //    without sending anything or creating sessions.
        val (dshCode, dshBody) = request(
            "/api/session.prompt", "POST",
            JSONObject().put("type", "client-request").put("rpcId", "smoke")
                .put("method", "session.prompt")
                .put("payload", JSONObject().put("sessionId", "definitely-not-a-real-session")
                    .put("content", JSONArray()))
                .toString(),
        )
        assertEquals(200, dshCode)
        assertTrue("adapter-level bad-request, not a gate", !dshBody.getJSONObject("result").optBoolean("ok", true))
        assertEquals(
            403,
            request(
                "/api/session.prompts", "POST",
                JSONObject().put("type", "client-request").put("method", "session.prompts").put("payload", JSONObject()).toString(),
            ).first,
        )

        // 8) Lock-out: permission.manage OFF ⇒ re-enabling from the Web fails.
        assertEquals(200, request(
            "/api/permissions", "PATCH",
            JSONObject().put("capability", "permission.manage").put("enabled", false).toString(),
        ).first)
        assertEquals(
            403,
            request(
                "/api/permissions", "PATCH",
                JSONObject().put("capability", "permission.manage").put("enabled", true).toString(),
            ).first,
        )
        assertEquals(403, rpcCode("settings.capabilities.get"))

        // 9) Restore defaults so the postconditions stay default. While
        //    permission.manage is OFF the Web cannot patch ANY permission row
        //    (that IS the lock-out rule) — restore both rows directly here,
        //    exactly like the phone settings screen would.
        assertTrue(RemotePermissionPolicy.setCapability(context, RemoteCapabilityCatalog.DEVICE_VIEW, false))
        assertTrue(RemotePermissionPolicy.setCapability(context, RemoteCapabilityCatalog.PERMISSION_MANAGE, true))
        assertEquals(200, request("/api/permissions").first)
    }
}
