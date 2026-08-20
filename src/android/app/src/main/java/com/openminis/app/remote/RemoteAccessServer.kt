package com.openminis.app.remote

import android.content.Context
import android.util.Log
import com.openminis.app.data.ContextOffload
import com.openminis.app.debug.ChatDebugMethods
import com.openminis.app.debug.DebugRPCHandler
import com.openminis.app.debug.ChatMutationMethods
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.internal.ShellOutputTruncator
import com.openminis.app.tools.internal.FileRevision
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Production remote-control HTTP bridge. It intentionally exposes only a small
 * allow-listed surface and reuses the same ChatViewModel/Session machinery as
 * the native UI. No API-key/provider secrets are ever returned.
 *
 * Security model:
 *  - disabled by default and refuses to start until a login password exists;
 *  - browser login uses PBKDF2-verified credentials + HttpOnly SameSite cookie;
 *  - a per-install bearer token remains available only for CLI/automation;
 *  - credentials are never accepted in query strings or returned by settings;
 *  - cookie-authenticated mutations are same-origin checked; no permissive CORS;
 *  - session filesystem paths resolve through PRootKernel guards.
 * TLS terminates at Cloudflare Tunnel / another reverse proxy for Internet use.
 */
class RemoteAccessServer(
    context: Context,
    private val port: Int,
    private val token: String,
    private val bindHost: String = "127.0.0.1",
) {
    companion object {
        private const val TAG = "RemoteAccessServer"
        private const val MAX_BODY = 4 * 1024 * 1024
        private const val MAX_EDIT_FILE_BYTES = 2L * 1024 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val SESSION_TTL_MS = 12L * 60L * 60L * 1000L
        private const val LOGIN_LOCK_MS = 60_000L
        private const val SESSION_COOKIE = "minis_session"
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val WEBSOCKET_SUBPROTOCOL = "minis.session.v1"
        private const val MAX_WEBSOCKET_FRAME_BYTES = 256 * 1024
        private const val WEBSOCKET_MAX_LIFETIME_MS = 10L * 60L * 1000L
        private const val WEBSOCKET_PING_INTERVAL_MS = 20_000L
        private const val WEBSOCKET_PONG_GRACE_MS = 50_000L

        fun constantTimeTokenEquals(expected: String, provided: String?): Boolean {
            if (expected.isEmpty() || provided == null || expected.length != provided.length) return false
            var diff = 0
            for (i in expected.indices) diff = diff or (expected[i].code xor provided[i].code)
            return diff == 0
        }
    }

    private val appContext = context.applicationContext
    private var scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t ->
                Log.e(TAG, "uncaught server coroutine: ${t.message}", t)
            },
    )
    @Volatile private var stopped = false
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val connectionSlots = Semaphore(32)
    private val sessions = ConcurrentHashMap<String, Long>()
    private val secureRandom = SecureRandom()
    // Per-source-IP login failure counters: one rogue peer can no longer
    // lock the whole server, and one attacker can no longer hide failures
    // behind a non-atomic increment from concurrent connections.
    private val failedLoginsByClient = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val loginLockedUntilByClient = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun start(): Boolean {
        if (acceptJob != null) return true
        // stop() cancels the old scope; a reused instance must get a fresh
        // one or every launch after stop would silently no-op.
        if (!scope.isActive) {
            scope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO +
                    CoroutineExceptionHandler { _, t ->
                        Log.e(TAG, "uncaught server coroutine: ${t.message}", t)
                    },
            )
        }
        stopped = false
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(bindHost), port), 32)
            }
        } catch (e: Exception) {
            Log.e(TAG, "server failed to bind $bindHost:$port: ${e.message}", e)
            return false
        }
        serverSocket = ss
        Log.i(TAG, "Web remote listening on $bindHost:$port")
        acceptJob = scope.launch {
            while (!stopped) {
                try {
                    val socket = ss.accept()
                    if (!connectionSlots.tryAcquire()) {
                        runCatching { socket.close() }
                        continue
                    }
                    launch {
                        try {
                            handle(socket)
                        } catch (t: Throwable) {
                            // One broken connection (client RST mid-write etc.)
                            // must never take the whole process down.
                            Log.w(TAG, "connection handler crashed: ${t.message}")
                        } finally {
                            connectionSlots.release()
                        }
                    }
                } catch (e: Exception) {
                    if (!stopped) Log.w(TAG, "accept failed: ${e.message}")
                }
            }
        }
        return true
    }

    fun stop() {
        stopped = true
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        sessions.clear()
        scope.cancel()
    }

    private data class Request(
        val method: String,
        val rawPath: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
        val remoteAddress: String? = null,
    )

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            try {
                val req = readRequest(input, s.inetAddress?.hostAddress) ?: return
                if (req.method == "OPTIONS") {
                    respond(output, 204, "text/plain; charset=utf-8", "")
                    return
                }

                if (req.path.startsWith("/api/auth/")) {
                    routeAuth(req, output)
                    return
                }

                if (req.path.startsWith("/api/")) {
                    val auth = authenticate(req)
                    if (auth == AuthKind.NONE) {
                        respondJson(output, 401, JSONObject().put("error", "unauthorized"))
                        return
                    }
                    // WebSocket upgrades are GETs, so the normal mutating-
                    // request origin guard would not cover them.  A browser
                    // carrying an HttpOnly session cookie must prove it came
                    // from this exact Remote origin before it can subscribe to
                    // the live session event log (CSWSH protection).
                    if (req.path == "/api/events/session" && isWebSocketUpgrade(req)) {
                        if (auth == AuthKind.COOKIE && !sameWebSocketOrigin(req)) {
                            respondJson(output, 403, JSONObject().put("error", "cross-origin websocket rejected"))
                            return
                        }
                        // The WebSocket reader must not time out halfway
                        // through a masked frame.  Its own ping/pong deadline
                        // below detects dead peers, and socket.use{} closes a
                        // blocked read when the bounded event stream ends.
                        s.soTimeout = 0
                        routeWebSocket(req, input, output)
                        return
                    }
                    if (auth == AuthKind.COOKIE && isMutating(req.method) && !sameOrigin(req)) {
                        respondJson(output, 403, JSONObject().put("error", "cross-origin request rejected"))
                        return
                    }
                    routeApi(req, output)
                } else {
                    routeStatic(req, output)
                }
            } catch (e: BodyTooLargeException) {
                respondJson(output, 413, JSONObject().put("error", "request body too large"))
            } catch (e: IllegalArgumentException) {
                respondJson(output, 400, JSONObject().put("error", e.message ?: "bad request"))
            } catch (e: org.json.JSONException) {
                respondJson(output, 400, JSONObject().put("error", e.message ?: "invalid JSON"))
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.message}")
                // Never echo internal exception details (SQL/file paths) to
                // remote clients — log them server-side only.
                respondJson(output, 500, JSONObject().put("error", "internal error"))
            }
        }
    }

    private enum class AuthKind { NONE, COOKIE, BEARER }

    private fun routeAuth(req: Request, out: BufferedOutputStream) {
        when (req.path) {
            "/api/auth/status" -> {
                requireMethod(req, "GET")
                val authenticated = authenticate(req) != AuthKind.NONE
                respondJson(out, 200, JSONObject().apply {
                    put("authenticated", authenticated)
                    if (authenticated) put("username", RemoteAccessPrefs.username(appContext))
                })
            }
            "/api/auth/login" -> {
                requireMethod(req, "POST")
                if (!RemoteAccessPrefs.hasPassword(appContext)) {
                    respondJson(out, 503, JSONObject().put("error", "Remote login password is not configured on the phone"))
                    return
                }
                val now = System.currentTimeMillis()
                val clientKey = loginClientKey(req)
                val lockedUntil = loginLockedUntilByClient[clientKey]
                if (lockedUntil != null && now < lockedUntil) {
                    respondJson(out, 429, JSONObject().put("error", "too many login attempts").put("retryAfterMs", lockedUntil - now))
                    return
                }
                // Reset an expired bucket so a single typo an hour later does
                // not immediately re-lock this client.
                if (lockedUntil != null) {
                    loginLockedUntilByClient.remove(clientKey, lockedUntil)
                    failedLoginsByClient.remove(clientKey)
                }
                val body = JSONObject(req.body)
                val username = body.optString("username")
                val password = body.optString("password").toCharArray()
                val ok = RemoteAccessPrefs.verifyLogin(appContext, username, password)
                if (!ok) {
                    val counter = failedLoginsByClient.computeIfAbsent(clientKey) { java.util.concurrent.atomic.AtomicInteger() }
                    if (counter.incrementAndGet() >= 5) loginLockedUntilByClient[clientKey] = now + LOGIN_LOCK_MS
                    respondJson(out, 401, JSONObject().put("error", "invalid username or password"))
                    return
                }
                failedLoginsByClient.remove(clientKey)
                loginLockedUntilByClient.remove(clientKey)
                // Opportunistic sweep: drop expired sessions so the map
                // cannot grow without bound on repeated logins.
                val expired = sessions.entries.filter { it.value <= now }.map { it.key }
                if (expired.isNotEmpty()) expired.forEach(sessions::remove)
                val id = newSessionId()
                sessions[id] = now + SESSION_TTL_MS
                val cookie = buildString {
                    append(SESSION_COOKIE).append('=').append(id)
                    append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=").append(SESSION_TTL_MS / 1000L)
                    if (isHttps(req)) append("; Secure")
                }
                respondJson(out, 200, JSONObject().put("ok", true).put("username", RemoteAccessPrefs.username(appContext)), mapOf("Set-Cookie" to cookie))
            }
            "/api/auth/logout" -> {
                requireMethod(req, "POST")
                cookieValue(req, SESSION_COOKIE)?.let { sessions.remove(it) }
                val cookie = buildString {
                    append(SESSION_COOKIE).append("=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
                    if (isHttps(req)) append("; Secure")
                }
                respondJson(out, 200, JSONObject().put("ok", true), mapOf("Set-Cookie" to cookie))
            }
            else -> respondJson(out, 404, JSONObject().put("error", "not found"))
        }
    }

    private fun authenticate(req: Request): AuthKind {
        val bearer = req.headers["authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
        val headerToken = req.headers["x-minis-token"]?.trim()
        if (constantTimeTokenEquals(token, bearer ?: headerToken)) return AuthKind.BEARER

        val sid = cookieValue(req, SESSION_COOKIE) ?: return AuthKind.NONE
        val expiry = sessions[sid] ?: return AuthKind.NONE
        val now = System.currentTimeMillis()
        if (expiry <= now) {
            sessions.remove(sid)
            return AuthKind.NONE
        }
        sessions[sid] = now + SESSION_TTL_MS
        return AuthKind.COOKIE
    }

    /**
     * Direct LAN clients must be rate-limited by their socket address, not an
     * easily forged HTTP header. Cloudflare Tunnel terminates locally, so only
     * a loopback peer may contribute its trusted CF client-address header.
     */
    private fun loginClientKey(req: Request): String {
        val peer = req.remoteAddress ?: "unknown"
        val isLoopback = peer == "127.0.0.1" || peer == "::1"
        return if (isLoopback) {
            req.headers["cf-connecting-ip"]?.trim()?.takeIf { it.isNotEmpty() } ?: peer
        } else {
            peer
        }
    }

    private fun cookieValue(req: Request, name: String): String? =
        req.headers["cookie"]
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    private fun newSessionId(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun isHttps(req: Request): Boolean =
        req.headers["x-forwarded-proto"]?.equals("https", ignoreCase = true) == true ||
            req.headers["cf-visitor"]?.contains("\"scheme\":\"https\"") == true

    private fun isMutating(method: String): Boolean = method in setOf("POST", "PUT", "PATCH", "DELETE")

    private fun sameOrigin(req: Request): Boolean {
        val origin = req.headers["origin"] ?: return true
        val host = req.headers["host"] ?: return false
        val scheme = if (isHttps(req)) "https" else "http"
        return origin.equals("$scheme://$host", ignoreCase = true)
    }

    /** A strict check for cookie-authenticated WebSocket upgrades. */
    private fun sameWebSocketOrigin(req: Request): Boolean =
        !req.headers["origin"].isNullOrBlank() && sameOrigin(req)

    private fun isWebSocketUpgrade(req: Request): Boolean =
        req.headers["upgrade"]?.equals("websocket", ignoreCase = true) == true &&
            req.headers["connection"]
                ?.split(',')
                ?.any { it.trim().equals("upgrade", ignoreCase = true) } == true

    private fun routeStatic(req: Request, out: BufferedOutputStream) {
        if (req.method != "GET") {
            respond(out, 405, "text/plain; charset=utf-8", "Method Not Allowed")
            return
        }
        val asset = when (req.path) {
            "/", "/index.html" -> "remote/index.html"
            "/app.css" -> "remote/app.css"
            "/app.js" -> "remote/app.js"
            "/md.js" -> "remote/md.js"
            "/marked.js" -> "remote/marked.js"
            "/purify.js" -> "remote/purify.js"
            "/ds-tokens.css" -> "remote/ds-tokens.css"
            "/ds-scrollbar.css" -> "remote/ds-scrollbar.css"
            else -> null
        }
        if (asset == null) {
            respond(out, 404, "text/plain; charset=utf-8", "Not Found")
            return
        }
        val mime = when {
            asset.endsWith(".html") -> "text/html; charset=utf-8"
            asset.endsWith(".css") -> "text/css; charset=utf-8"
            asset.endsWith(".js") -> "application/javascript; charset=utf-8"
            else -> "application/octet-stream"
        }
        val bytes = appContext.assets.open(asset).use { it.readBytes() }
        respondBytes(out, 200, mime, bytes)
    }

    private fun routeApi(req: Request, out: BufferedOutputStream) = runBlocking {
        when (req.path) {
            "/api/status" -> respondJson(out, 200, JSONObject().apply {
                put("ok", true); put("platform", "android"); put("port", port); put("bindHost", bindHost)
                put("cloudflareTunnel", RemoteAccessPrefs.cloudflareTunnelEnabled(appContext))
                put("publicHostname", RemoteAccessPrefs.cloudflareHostname(appContext))
                put("tunnel", tunnelStatusJson())
            })
            "/api/settings" -> routeSettings(req, out)
            "/api/settings/restart" -> {
                requireMethod(req, "POST")
                respondJson(out, 200, JSONObject().put("ok", true).put("message", "Remote service restarting"))
                Thread({
                    runCatching { Thread.sleep(350L) }
                    runCatching { RemoteAccessService.restart(appContext) }
                }, "remote-restart").apply { isDaemon = true }.start()
            }
            "/api/sessions" -> {
                val limit = (req.query["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
                respondJson(out, 200, ChatDebugMethods.sessionsList(appContext, JSONObject().put("limit", limit).put("includeEmpty", true)))
            }
            "/api/messages" -> {
                val sid = requireQuery(req, "sessionId")
                val limit = (req.query["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 2000)
                val includeReasoning = req.query["includeReasoning"].equals("true", ignoreCase = true)
                respondJson(out, 200, ChatDebugMethods.messagesList(appContext, JSONObject()
                    .put("sessionId", sid)
                    .put("limit", limit)
                    .put("includeTools", true)
                    .put("includeReasoning", includeReasoning)))
            }
            // The browser event transport is an authenticated WebSocket
            // upgrade at this same path.  Keep an explicit HTTP response for
            // accidental fetches so clients discover that a persistent event
            // channel, rather than another polling route, is required.
            "/api/events/session" -> {
                requireMethod(req, "GET")
                respondJson(
                    out,
                    426,
                    JSONObject().put("error", "websocket upgrade required").put("endpoint", "/api/events/session"),
                    mapOf("Upgrade" to "websocket"),
                )
            }
            "/api/session/status" -> {
                val sid = requireQuery(req, "sessionId")
                respondJson(out, 200, ChatMutationMethods.status(appContext, JSONObject().put("sessionId", sid)))
            }
            // ---- 手机端已有能力的 Web 侧入口 ----
            // 全部转调既有的 Debug RPC 方法，避免第二套会话逻辑。
            "/api/rpc" -> {
                // 转发到 App 内部的 JSON-RPC 分发器，但只放行白名单方法。
                val rpcBody = JSONObject(req.body)
                val method = rpcBody.optString("method")
                if (!isRpcMethodAllowed(method)) {
                    respondJson(
                        out, 403,
                        JSONObject()
                            .put("error", "method not allowed over web remote: $method")
                            .put("allowed", JSONArray(RPC_ALLOWED_PREFIXES.toList())),
                    )
                } else {
                    if (!rpcBody.has("jsonrpc")) rpcBody.put("jsonrpc", "2.0")
                    if (!rpcBody.has("id")) rpcBody.put("id", 1)
                    val raw = DebugRPCHandler(appContext).handle(rpcBody.toString())
                    respond(out, 200, "application/json; charset=utf-8", raw)
                }
            }
            "/api/models" -> {
                respondJson(out, 200, ChatDebugMethods.modelsList(appContext, JSONObject()))
            }
            "/api/usage" -> {
                val sid = requireQuery(req, "sessionId")
                val perTurn = req.query["perTurn"] == "true"
                respondJson(
                    out, 200,
                    ChatDebugMethods.sessionsUsage(
                        appContext,
                        JSONObject().put("sessionId", sid).put("perTurn", perTurn),
                    ),
                )
            }
            "/api/session/model" -> {
                requireMethod(req, "POST")
                val body = JSONObject(req.body)
                respondJson(out, 200, ChatMutationMethods.selectModel(appContext, body))
            }
            "/api/session/thinking" -> {
                requireMethod(req, "POST")
                respondJson(out, 200, ChatMutationMethods.selectThinkingLevel(appContext, JSONObject(req.body)))
            }
            "/api/session/delete" -> {
                requireMethod(req, "POST")
                // confirm 由服务端强制补上：删除是不可逆的，但确认已经发生在
                // 网页的对话框里，不该让前端漏传就变成静默失败。
                val body = JSONObject(req.body).put("confirm", true)
                respondJson(out, 200, ChatMutationMethods.delete(appContext, body))
            }
            "/api/session/title" -> {
                requireMethod(req, "POST")
                val body = JSONObject(req.body)
                val sid = body.optString("sessionId", "")
                val title = body.optString("title", "").trim()
                if (sid.isEmpty() || title.isEmpty()) {
                    respondJson(out, 400, JSONObject().put("error", "sessionId and title are required"))
                } else {
                    kotlinx.coroutines.runBlocking {
                        appContext.chatRepositoryOrThrow().updateSessionTitle(sid, title.take(120))
                    }
                    respondJson(out, 200, JSONObject().put("ok", true).put("title", title.take(120)))
                }
            }
            "/api/session/new" -> {
                requireMethod(req, "POST")
                val sid = kotlinx.coroutines.runBlocking {
                    com.openminis.app.debug.HeadlessChatRunner.ensureSession(appContext, null)
                }
                respondJson(out, 200, JSONObject().put("sessionId", sid))
            }
            "/api/compact" -> {
                requireMethod(req, "POST")
                respondJson(out, 200, ChatMutationMethods.compactBefore(appContext, JSONObject(req.body)))
            }
            "/api/prompt" -> {
                requireMethod(req, "POST")
                val body = JSONObject(req.body)
                // Remote UI is asynchronous by default and polls the shared DB.
                if (!body.has("wait")) body.put("wait", false)
                respondJson(out, 200, ChatMutationMethods.prompt(appContext, body))
            }
            "/api/cancel" -> {
                requireMethod(req, "POST")
                respondJson(out, 200, ChatMutationMethods.cancel(appContext, JSONObject(req.body)))
            }
            "/api/files" -> {
                val sid = requireQuery(req, "sessionId")
                requireExistingSession(sid)
                val path = req.query["path"] ?: "/var/minis/workspace"
                respondJson(out, 200, listFiles(sid, path))
            }
            "/api/file" -> when (req.method) {
                "GET" -> {
                    val sid = requireQuery(req, "sessionId")
                    requireExistingSession(sid)
                    val path = requireQuery(req, "path")
                    val file = resolveSessionFile(sid, path)
                    if (!file.exists() || !file.isFile) throw IllegalArgumentException("not a file: $path")
                    if (file.length() > MAX_EDIT_FILE_BYTES) {
                        throw IllegalArgumentException("file is too large for the Web editor (${file.length()} bytes; max $MAX_EDIT_FILE_BYTES)")
                    }
                    val bytes = file.readBytes()
                    if (bytes.any { it == 0.toByte() }) throw IllegalArgumentException("binary files cannot be opened in the text editor")
                    val text = try {
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString()
                    } catch (_: Exception) {
                        throw IllegalArgumentException("file is not valid UTF-8 text")
                    }
                    respondJson(out, 200, JSONObject().apply {
                        put("success", true); put("path", path); put("content", text); put("size", bytes.size)
                        put("sha256", FileRevision.sha256(bytes))
                    })
                }
                "POST", "PUT" -> {
                    val b = JSONObject(req.body)
                    val sid = b.optString("sessionId")
                    if (sid.isBlank()) throw IllegalArgumentException("sessionId is required")
                    requireExistingSession(sid)
                    // Same containment guard as GET: the tool layer resolves
                    // the path again, but we must reject escapes BEFORE any
                    // write happens.
                    resolveSessionFile(sid, b.optString("path"))
                    // Permission preset: the default workspace-write mode only
                    // allows writing inside /var/minis/workspace. Full Access
                    // lifts that. This makes the settings preset a real gate
                    // instead of a decorative switch.
                    if (RemotePermissionPolicy.preset(appContext) == RemotePermissionPolicy.PRESET_WORKSPACE_WRITE &&
                        !b.optString("path").startsWith("/var/minis/workspace")
                    ) {
                        throw IllegalArgumentException("workspace-write preset: writes are limited to /var/minis/workspace")
                    }
                    val args = JSONObject().put("tool_title", "Write remote file")
                        .put("path", b.optString("path"))
                        .put("content", b.optString("content"))
                        .put("append", b.optBoolean("append", false))
                        .put("create_dirs", true)
                    b.optString("expectedSha256", "").takeIf { it.isNotBlank() }?.let { args.put("expected_sha256", it) }
                    val result = FileWriteTool.execute(args.toString(), sid, appContext)
                    val response = JSONObject().put("success", result.success).put("output", result.output)
                    if (result.success) {
                        // For overwrite mode this is the exact revision of the
                        // bytes the editor just wrote, even if an external shell
                        // mutates the file immediately after the write returns.
                        if (!b.optBoolean("append", false)) {
                            response.put("sha256", FileRevision.sha256(b.optString("content").toByteArray(StandardCharsets.UTF_8)))
                        } else {
                            val written = resolveSessionFile(sid, b.optString("path"))
                            if (written.exists() && written.isFile) response.put("sha256", FileRevision.sha256(written))
                        }
                    }
                    respondJson(out, if (result.success) 200 else 400, response)
                }
                else -> respondJson(out, 405, JSONObject().put("error", "method not allowed"))
            }
            "/api/edit" -> {
                requireMethod(req, "POST")
                val b = JSONObject(req.body)
                val sid = b.optString("sessionId")
                if (sid.isBlank()) throw IllegalArgumentException("sessionId is required")
                requireExistingSession(sid)
                // Containment guard before the edit tool touches the file.
                val target = resolveSessionFile(sid, b.optString("path"))
                // Size guard: FileEditTool reads the whole file into memory
                // (readText + diff), so an unbounded file would OOM the app.
                // The GET route already caps at MAX_EDIT_FILE_BYTES.
                if (target.isFile && target.length() > MAX_EDIT_FILE_BYTES) {
                    throw IllegalArgumentException("file is too large for the Web editor (${target.length()} bytes; max $MAX_EDIT_FILE_BYTES)")
                }
                // Permission preset gate, same policy as /api/file writes.
                if (RemotePermissionPolicy.preset(appContext) == RemotePermissionPolicy.PRESET_WORKSPACE_WRITE &&
                    !b.optString("path").startsWith("/var/minis/workspace")
                ) {
                    throw IllegalArgumentException("workspace-write preset: edits are limited to /var/minis/workspace")
                }
                val args = JSONObject().put("tool_title", "Edit remote file").put("path", b.optString("path"))
                if (b.has("edits")) args.put("edits", b.get("edits"))
                if (b.has("old_string")) args.put("old_string", b.get("old_string"))
                if (b.has("new_string")) args.put("new_string", b.get("new_string"))
                if (b.has("replace_all")) args.put("replace_all", b.get("replace_all"))
                val result = FileEditTool.execute(args.toString(), sid, appContext)
                respondJson(out, if (result.success) 200 else 400, JSONObject().put("success", result.success).put("output", result.output))
            }
            "/api/shell" -> {
                requireMethod(req, "POST")
                val b = JSONObject(req.body)
                val sid = b.optString("sessionId")
                val command = b.optString("command")
                if (sid.isBlank() || command.isBlank()) throw IllegalArgumentException("sessionId and command are required")
                requireExistingSession(sid)
                val timeoutMs = (b.optLong("timeoutMs", 900_000L)).coerceIn(1_000L, 3_600_000L)
                val result = ExecutionCoordinator.execute(sid, command, timeoutMs)
                val full = result.fullOutput ?: result.output
                val trunc = ShellOutputTruncator.truncateTail(full)
                val path = if (trunc.truncated) ContextOffload.offloadContent(appContext, sid, full, "web_${System.currentTimeMillis()}", "shell_execute", "log") else ""
                respondJson(out, 200, JSONObject().apply {
                    put("exitCode", result.exitCode); put("durationMs", result.durationMs); put("output", trunc.output)
                    if (path.isNotEmpty()) put("fullOutputPath", path)
                })
            }
            else -> respondJson(out, 404, JSONObject().put("error", "not found"))
        }
    }

    private suspend fun routeSettings(req: Request, out: BufferedOutputStream) {
        when (req.method) {
            "GET" -> {
                respondJson(out, 200, JSONObject().apply {
                    put("username", RemoteAccessPrefs.username(appContext))
                    put("passwordConfigured", RemoteAccessPrefs.hasPassword(appContext))
                    put("port", RemoteAccessPrefs.port(appContext))
                    put("lanAccess", RemoteAccessPrefs.lanAccessEnabled(appContext))
                    put("bindHost", RemoteAccessPrefs.bindHost(appContext))
                    put("cloudflareTunnelEnabled", RemoteAccessPrefs.cloudflareTunnelEnabled(appContext))
                    put("cloudflareTunnelTokenConfigured", RemoteAccessPrefs.hasCloudflareTunnelToken(appContext))
                    put("cloudflareHostname", RemoteAccessPrefs.cloudflareHostname(appContext))
                    put("tunnel", tunnelStatusJson())
                })
            }
            "PATCH", "PUT" -> {
                val body = JSONObject(req.body)
                val oldPort = RemoteAccessPrefs.port(appContext)
                val oldLan = RemoteAccessPrefs.lanAccessEnabled(appContext)
                val oldUser = RemoteAccessPrefs.username(appContext)

                val requestedUser = body.optString("username", oldUser).trim()
                val newPassword = body.optString("newPassword", "")
                val changesIdentity = requestedUser != oldUser || newPassword.isNotEmpty()
                if (changesIdentity) {
                    val current = body.optString("currentPassword", "").toCharArray()
                    if (!RemoteAccessPrefs.verifyLogin(appContext, oldUser, current)) {
                        respondJson(out, 403, JSONObject().put("error", "current password is incorrect"))
                        return
                    }
                    if (requestedUser != oldUser) RemoteAccessPrefs.setUsername(appContext, requestedUser)
                    if (newPassword.isNotEmpty()) RemoteAccessPrefs.setPassword(appContext, newPassword.toCharArray())
                }

                if (body.has("port")) {
                    val requestedPort = body.optInt("port", oldPort)
                    if (requestedPort !in 1024..65535) throw IllegalArgumentException("port must be 1024-65535")
                    RemoteAccessPrefs.setPort(appContext, requestedPort)
                }
                if (body.has("lanAccess")) {
                    RemoteAccessPrefs.setLanAccessEnabled(appContext, body.getBoolean("lanAccess"))
                }
                if (body.has("cloudflareHostname")) {
                    RemoteAccessPrefs.setCloudflareHostname(appContext, body.optString("cloudflareHostname"))
                }
                var tunnelTokenChanged = false
                if (body.has("cloudflareTunnelToken")) {
                    val supplied = body.optString("cloudflareTunnelToken").trim()
                    if (supplied.isNotEmpty()) {
                        RemoteAccessPrefs.setCloudflareTunnelToken(appContext, supplied)
                        tunnelTokenChanged = true
                    }
                }
                if (body.has("cloudflareTunnelEnabled")) {
                    val enableTunnel = body.getBoolean("cloudflareTunnelEnabled")
                    if (enableTunnel && !RemoteAccessPrefs.hasCloudflareTunnelToken(appContext)) {
                        throw IllegalArgumentException("Cloudflare Tunnel Token is required before enabling the tunnel")
                    }
                    RemoteAccessPrefs.setCloudflareTunnelEnabled(appContext, enableTunnel)
                    if (enableTunnel) {
                        if (tunnelTokenChanged) CloudflareTunnelManager.stop()
                        CloudflareTunnelManager.start(appContext)
                    } else {
                        CloudflareTunnelManager.stop()
                    }
                }

                val restartRequired = oldPort != RemoteAccessPrefs.port(appContext) ||
                    oldLan != RemoteAccessPrefs.lanAccessEnabled(appContext)
                respondJson(out, 200, JSONObject().apply {
                    put("ok", true)
                    put("restartRequired", restartRequired)
                    put("reauthRequired", changesIdentity)
                    put("port", RemoteAccessPrefs.port(appContext))
                    put("lanAccess", RemoteAccessPrefs.lanAccessEnabled(appContext))
                    put("username", RemoteAccessPrefs.username(appContext))
                    put("cloudflareTunnelEnabled", RemoteAccessPrefs.cloudflareTunnelEnabled(appContext))
                    put("cloudflareTunnelTokenConfigured", RemoteAccessPrefs.hasCloudflareTunnelToken(appContext))
                    put("cloudflareHostname", RemoteAccessPrefs.cloudflareHostname(appContext))
                    put("tunnel", tunnelStatusJson())
                })
                if (changesIdentity) sessions.clear()
            }
            else -> respondJson(out, 405, JSONObject().put("error", "method not allowed"))
        }
    }

    private fun tunnelStatusJson(): JSONObject {
        val status = CloudflareTunnelManager.status.value
        return JSONObject().apply {
            put("installed", status.installed)
            put("running", status.running)
            put("phase", status.phase)
            put("detail", status.detail)
            put("version", status.version)
        }
    }

    private suspend fun requireExistingSession(sessionId: String) {
        try {
            ChatDebugMethods.sessionsGet(appContext, JSONObject().put("sessionId", sessionId))
        } catch (_: Exception) {
            throw IllegalArgumentException("session not found: $sessionId")
        }
    }

    /**
     * Resolve a session-scoped Linux path to a host File, with containment
     * enforced on the REAL path. PRootKernel.resolveSessionHostPath does raw
     * File(parent, tail) concatenation, so a path without ".." can still
     * escape the session directory through a symlink planted inside the
     * sandbox (the agent has a shell). canonicalFile resolves those links;
     * anything that lands outside the expected host root is rejected.
     */
    private fun resolveSessionFile(sessionId: String, linuxPath: String): File {
        if (linuxPath.contains("..")) throw IllegalArgumentException("'..' is not allowed")
        val resolved = PRootKernel.resolveSessionHostPath(sessionId, linuxPath, appContext)
            ?: throw IllegalArgumentException("cannot resolve path")
        val expectedRoot = sessionHostRoot(sessionId, linuxPath) ?: return resolved
        val canonical = try {
            resolved.canonicalFile
        } catch (_: Exception) {
            return resolved
        }
        val rootPrefix = expectedRoot.trimEnd(File.separatorChar) + File.separator
        if (canonical.path != expectedRoot && !canonical.path.startsWith(rootPrefix)) {
            throw IllegalArgumentException("path escapes the session workspace")
        }
        // Continue with the canonical object: a second symlink resolution in a
        // downstream File operation would otherwise reopen the escaped path.
        return canonical
    }

    /**
     * Host-side root the session path is expected to live under:
     *  - /var/minis/<subdir>/... → filesDir/minis-sessions/<sid>/<subdir>
     *  - everything else → the PRoot rootfs directory
     * Returns null when the expected root cannot be determined (the path
     * then passes without containment — this covers exotic pre-boot cases).
     */
    private fun sessionHostRoot(sessionId: String, linuxPath: String): String? {
        if (linuxPath.startsWith("/var/minis/")) {
            val subdir = linuxPath.removePrefix("/var/minis/").substringBefore('/')
            if (subdir.isNotEmpty()) {
                return try {
                    File(appContext.filesDir, "minis-sessions/$sessionId/$subdir").canonicalPath
                } catch (_: Exception) {
                    null
                }
            }
        }
        return try {
            com.openminis.app.sandbox.RootfsManager.getInstance(appContext).rootfsDir.canonicalPath
        } catch (_: Exception) {
            null
        }
    }

    private fun listFiles(sessionId: String, linuxPath: String): JSONObject {
        if (linuxPath.contains("..")) throw IllegalArgumentException("'..' is not allowed")
        val dir = resolveSessionFile(sessionId, linuxPath)
        if (!dir.exists() || !dir.isDirectory) throw IllegalArgumentException("not a directory: $linuxPath")
        val items = JSONArray()
        dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.take(1000)?.forEach { f ->
            items.put(JSONObject().apply {
                put("name", f.name); put("directory", f.isDirectory); put("size", f.length()); put("modified", f.lastModified())
                put("path", linuxPath.trimEnd('/') + "/" + f.name)
            })
        }
        return JSONObject().put("path", linuxPath).put("items", items)
    }

    private fun requireMethod(req: Request, method: String) {
        if (req.method != method) throw IllegalArgumentException("$method required")
    }
    private fun requireQuery(req: Request, key: String): String = req.query[key]?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("missing query parameter: $key")

    /**
     * Browser-facing transport for the append-only session event log.
     *
     * The WebSocket carries native [SessionEvent] wire envelopes (`session/event`)
     * in sequence order, with [afterSeq] supplying replay after a reconnect.
     * A subscription without a cursor gets one atomically waterlined
     * `session/snapshot`; an unavailable replay cursor gets
     * `session/subscribed { reset: true }` so the client can rehydrate.
     */
    private fun routeWebSocket(
        req: Request,
        input: BufferedInputStream,
        out: BufferedOutputStream,
    ) = runBlocking {
        when (req.path) {
            "/api/events/session" -> streamSessionEventsWebSocket(req, input, out)
            else -> respondJson(out, 404, JSONObject().put("error", "websocket endpoint not found"))
        }
    }

    private suspend fun streamSessionEventsWebSocket(
        req: Request,
        input: BufferedInputStream,
        out: BufferedOutputStream,
    ) {
        requireMethod(req, "GET")
        val sessionId = requireQuery(req, "sessionId")
        requireExistingSession(sessionId)
        val afterSeq = parseWebSocketAfterSeq(req)
        val handshake = webSocketHandshake(req)
        beginWebSocket(out, handshake.acceptKey, handshake.subprotocol)

        val peer = WebSocketPeer(out)
        val includeReasoning = req.query["includeReasoning"].equals("true", ignoreCase = true)
        // From here onward HTTP has already switched protocols. Any failure
        // must be expressed as a WebSocket close frame rather than letting the
        // outer HTTP error handler append a second response after the 101.
        // The connection is server-push only, but a browser automatically
        // replies to pings and can initiate a normal close.  Keep that reader
        // separate from the outbound event loop so an idle client can never
        // stall token/tool delivery.  socket.use{} closes the stream when this
        // method returns, unblocking this daemon reader if it is waiting.
        Thread(
            { receiveWebSocketControlFrames(input, peer) },
            "remote-ws-control",
        ).apply {
            isDaemon = true
            start()
        }

        // An absent cursor (or explicit snapshot=1) is a subscription, not a
        // request for `eventsSince(null)`: that call intentionally means
        // "start at latest" in the journal and would lose the tiny interval
        // between a REST hydrate and this socket opening. The snapshot builder
        // captures a sequence fence and its materialized event-tail overlay in
        // the same event-log critical section, then this socket replays only
        // events strictly after that fence.
        val needsSnapshot = afterSeq == null || req.query["snapshot"] == "1"
        var cursor = afterSeq ?: 0L
        if (needsSnapshot) {
            // The materialized event-tail overlay and this watermark are
            // captured under the session-event lock. It includes raw provider
            // chunks and tool transitions before Compose's intentionally
            // throttled state receives them, so replaying only > fence can
            // neither duplicate nor drop a token at the snapshot boundary.
            val captured = try {
                com.openminis.app.debug.HeadlessChatRunner
                    .sessionSnapshotWithWatermark(appContext, sessionId, includeReasoning)
            } catch (t: Throwable) {
                Log.w(TAG, "websocket snapshot failed: ${t.message}")
                peer.close(1011, "snapshot failed")
                return
            }
            val fence = captured.lastSeq
            val snapshot = captured.value
            peer.sendText(JSONObject().apply {
                put("type", "session/snapshot")
                put("sessionId", sessionId)
                put("lastSeq", fence)
                put("snapshot", snapshot.put("lastSeq", fence))
            }.toString())
            cursor = fence
        }

        var nextPingAt = System.currentTimeMillis() + WEBSOCKET_PING_INTERVAL_MS
        val expiresAt = System.currentTimeMillis() + WEBSOCKET_MAX_LIFETIME_MS
        try {
        coroutineScope {
            while (peer.isOpen() && System.currentTimeMillis() < expiresAt) {
                // Subscribe before reading the bounded journal.  If an event
                // lands during the replay read it is either in that replay or
                // already queued in [wake], so no edge is lost between the
                // event-log snapshot and the live notification flow.
                val awaitAfter = cursor
                val wake = async(start = CoroutineStart.UNDISPATCHED) {
                    com.openminis.app.ui.chat.SessionEventHub.events
                        .filter { it.sessionId == sessionId && it.seq > awaitAfter }
                        .first()
                }
                val replay = com.openminis.app.debug.HeadlessChatRunner.sessionEvents(appContext, sessionId, cursor)
                if (replay.resetRequired) {
                    peer.sendText(JSONObject().apply {
                        put("type", "session/subscribed")
                        put("sessionId", sessionId)
                        put("lastSeq", replay.latestSeq)
                        put("oldestAvailableSeq", replay.oldestAvailableSeq)
                        put("reset", true)
                    }.toString())
                    // The browser must rehydrate a durable snapshot. Keep
                    // this socket alive and forward everything after its new
                    // waterline while that fetch is in flight.
                    cursor = replay.latestSeq
                } else {
                    if (!needsSnapshot && cursor == afterSeq) {
                        peer.sendText(JSONObject().apply {
                            put("type", "session/subscribed")
                            put("sessionId", sessionId)
                            put("lastSeq", replay.latestSeq)
                            put("oldestAvailableSeq", replay.oldestAvailableSeq)
                            put("reset", false)
                        }.toString())
                    }
                    replay.events.forEach { event ->
                        if (peer.isOpen()) peer.sendText(event.toWireEnvelope().toString())
                    }
                    cursor = replay.latestSeq
                }

                val now = System.currentTimeMillis()
                if (now >= nextPingAt) {
                    if (now - peer.lastPongAt() > WEBSOCKET_PONG_GRACE_MS) {
                        peer.close(1001, "pong timeout")
                        break
                    }
                    peer.sendPing()
                    nextPingAt = now + WEBSOCKET_PING_INTERVAL_MS
                }
                // Wait for a journal append, or only until the next ping
                // deadline. This is a true push path: the browser receives
                // typed native events without a timer polling messages or
                // rebuilding a conversation snapshot.
                val waitMs = (nextPingAt - System.currentTimeMillis()).coerceAtLeast(1L)
                withTimeoutOrNull(waitMs) { wake.await() }
                wake.cancel()
            }
        }
        } catch (t: Throwable) {
            if (peer.isOpen()) {
                Log.w(TAG, "websocket event stream failed: ${t.message}")
                peer.close(1011, "event stream failed")
            }
        } finally {
            if (peer.isOpen()) peer.close(1001, "reconnect")
        }
    }

    private data class WebSocketHandshake(val acceptKey: String, val subprotocol: String?)

    private fun parseWebSocketAfterSeq(req: Request): Long? {
        val raw = req.query["afterSeq"] ?: return null
        return raw.toLongOrNull()?.takeIf { it >= 0L }
            ?: throw IllegalArgumentException("afterSeq must be a non-negative integer")
    }

    private fun webSocketHandshake(req: Request): WebSocketHandshake {
        if (!isWebSocketUpgrade(req)) throw IllegalArgumentException("websocket upgrade required")
        if (req.headers["sec-websocket-version"] != "13") {
            throw IllegalArgumentException("unsupported websocket version")
        }
        val key = req.headers["sec-websocket-key"]?.trim().orEmpty()
        val decoded = runCatching { Base64.getDecoder().decode(key) }.getOrNull()
        if (decoded == null || decoded.size != 16) {
            throw IllegalArgumentException("invalid Sec-WebSocket-Key")
        }
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + WEBSOCKET_GUID).toByteArray(StandardCharsets.US_ASCII)),
        )
        val requestedProtocols = req.headers["sec-websocket-protocol"]
            ?.split(',')
            ?.map { it.trim() }
            .orEmpty()
        return WebSocketHandshake(
            acceptKey = accept,
            subprotocol = WEBSOCKET_SUBPROTOCOL.takeIf { it in requestedProtocols },
        )
    }

    private fun beginWebSocket(out: BufferedOutputStream, acceptKey: String, subprotocol: String?) {
        val headers = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n")
            subprotocol?.let { append("Sec-WebSocket-Protocol: ").append(it).append("\r\n") }
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        out.write(headers)
        out.flush()
    }

    private data class WebSocketFrame(
        val fin: Boolean,
        val opcode: Int,
        val payload: ByteArray,
    )

    private class WebSocketProtocolException(message: String) : IllegalArgumentException(message)

    /** A synchronized writer because the reader may send a pong while the event loop writes text. */
    private class WebSocketPeer(private val out: BufferedOutputStream) {
        private val writeLock = Any()
        private val open = AtomicBoolean(true)
        private val sentClose = AtomicBoolean(false)
        private val lastPong = AtomicLong(System.currentTimeMillis())

        fun isOpen(): Boolean = open.get()
        fun lastPongAt(): Long = lastPong.get()
        fun recordPong() = lastPong.set(System.currentTimeMillis())

        /**
         * Browser WebSocket implementations reassemble fragmented text
         * messages before firing `message`, so a 2,000-message initial
         * snapshot need not be artificially truncated to the frame cap. Hold
         * the writer lock across the fragments; control frames may legally
         * interleave but another data message may not.
         */
        fun sendText(payload: String) {
            if (!open.get()) return
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            try {
                synchronized(writeLock) {
                    var offset = 0
                    var first = true
                    do {
                        val length = minOf(MAX_WEBSOCKET_FRAME_BYTES, bytes.size - offset)
                        val final = offset + length >= bytes.size
                        writeFrameLocked(
                            opcode = if (first) 0x1 else 0x0,
                            payload = bytes,
                            offset = offset,
                            length = length,
                            fin = final,
                        )
                        offset += length
                        first = false
                    } while (offset < bytes.size)
                }
            } catch (_: Exception) {
                open.set(false)
            }
        }

        fun sendPing() = sendFrame(0x9, ByteArray(0))
        fun sendPong(payload: ByteArray) = sendFrame(0xA, payload)

        fun close(code: Int, reason: String) {
            val source = reason.toByteArray(StandardCharsets.UTF_8)
            // A close control frame is capped at 125 bytes and uses two for
            // its code. All server-authored reasons are ASCII, so bounded
            // copying cannot split a multi-byte sequence here.
            val reasonBytes = source.copyOf(minOf(source.size, 123))
            val payload = ByteArray(2 + reasonBytes.size)
            payload[0] = (code ushr 8).toByte()
            payload[1] = code.toByte()
            reasonBytes.copyInto(payload, destinationOffset = 2)
            sendClose(payload)
        }

        fun acknowledgeClose(payload: ByteArray) = sendClose(payload)

        private fun sendClose(payload: ByteArray) {
            if (!sentClose.compareAndSet(false, true)) {
                open.set(false)
                return
            }
            try {
                writeFrame(0x8, payload)
            } finally {
                open.set(false)
            }
        }

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            if (!open.get()) return
            try {
                writeFrame(opcode, payload)
            } catch (_: Exception) {
                open.set(false)
            }
        }

        private fun writeFrame(opcode: Int, payload: ByteArray) = synchronized(writeLock) {
            writeFrameLocked(opcode, payload, 0, payload.size, fin = true)
        }

        private fun writeFrameLocked(
            opcode: Int,
            payload: ByteArray,
            offset: Int,
            length: Int,
            fin: Boolean,
        ) {
            require(length in 0..MAX_WEBSOCKET_FRAME_BYTES) { "websocket frame too large" }
            require(offset >= 0 && offset + length <= payload.size) { "invalid websocket payload range" }
            if ((opcode and 0x08) != 0) require(fin && length <= 125) { "invalid websocket control frame" }
            out.write((if (fin) 0x80 else 0x00) or (opcode and 0x0F))
            when {
                length <= 125 -> out.write(length)
                length <= 0xFFFF -> {
                    out.write(126)
                    out.write(length ushr 8)
                    out.write(length)
                }
                else -> {
                    out.write(127)
                    val size = length.toLong()
                    for (shift in 56 downTo 0 step 8) out.write((size ushr shift).toInt() and 0xFF)
                }
            }
            out.write(payload, offset, length)
            out.flush()
        }
    }

    private fun receiveWebSocketControlFrames(input: BufferedInputStream, peer: WebSocketPeer) {
        try {
            while (peer.isOpen()) {
                val frame = try {
                    readWebSocketFrame(input)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (frame == null) {
                    peer.close(1001, "client disconnected")
                    return
                }
                when (frame.opcode) {
                    0x8 -> {
                        validateClosePayload(frame.payload)
                        peer.acknowledgeClose(frame.payload)
                        return
                    }
                    0x9 -> peer.sendPong(frame.payload)
                    0xA -> peer.recordPong()
                    // The event endpoint subscribes through its URL.  It never
                    // accepts application data on the socket, which keeps the
                    // WebSocket surface read-only even for bearer clients.
                    0x0, 0x1, 0x2 -> {
                        peer.close(1003, "server push only")
                        return
                    }
                    else -> throw WebSocketProtocolException("unsupported websocket opcode")
                }
            }
        } catch (e: WebSocketProtocolException) {
            peer.close(1002, "protocol error")
        } catch (_: Exception) {
            if (peer.isOpen()) peer.close(1011, "websocket read failed")
        }
    }

    private fun readWebSocketFrame(input: BufferedInputStream): WebSocketFrame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) throw WebSocketProtocolException("truncated websocket header")
        if ((first and 0x70) != 0) throw WebSocketProtocolException("websocket extensions are not supported")
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        if (!masked) throw WebSocketProtocolException("client websocket frames must be masked")

        var length = (second and 0x7F).toLong()
        when (length) {
            126L -> {
                length = ((readWebSocketByte(input).toLong() shl 8) or readWebSocketByte(input).toLong())
            }
            127L -> {
                var parsed = 0L
                repeat(8) { index ->
                    val b = readWebSocketByte(input)
                    if (index == 0 && (b and 0x80) != 0) {
                        throw WebSocketProtocolException("invalid websocket length")
                    }
                    parsed = (parsed shl 8) or b.toLong()
                }
                length = parsed
            }
        }
        if (length > MAX_WEBSOCKET_FRAME_BYTES.toLong()) {
            throw WebSocketProtocolException("websocket frame too large")
        }
        val isControl = opcode and 0x08 != 0
        if (isControl && (!fin || length > 125L)) {
            throw WebSocketProtocolException("invalid control frame")
        }
        val mask = ByteArray(4)
        readWebSocketFully(input, mask)
        val payload = ByteArray(length.toInt())
        readWebSocketFully(input, payload)
        for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        return WebSocketFrame(fin = fin, opcode = opcode, payload = payload)
    }

    private fun readWebSocketByte(input: BufferedInputStream): Int {
        val value = input.read()
        if (value < 0) throw WebSocketProtocolException("truncated websocket frame")
        return value and 0xFF
    }

    private fun readWebSocketFully(input: BufferedInputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw WebSocketProtocolException("truncated websocket frame")
            offset += read
        }
    }

    private fun validateClosePayload(payload: ByteArray) {
        if (payload.size == 1) throw WebSocketProtocolException("invalid close frame")
        if (payload.size < 2) return
        val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val validCode = (code in 1000..1014 && code !in setOf(1004, 1005, 1006)) || code in 3000..4999
        if (!validCode) throw WebSocketProtocolException("invalid close code")
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload, 2, payload.size - 2))
        } catch (_: Exception) {
            throw WebSocketProtocolException("invalid close reason")
        }
    }

    private class BodyTooLargeException : RuntimeException()

    private fun readRequest(input: BufferedInputStream, remoteAddress: String? = null): Request? {
        val requestLine = readLine(input) ?: return null
        val first = requestLine.split(' ', limit = 3)
        if (first.size < 2) throw IllegalArgumentException("invalid request line")
        val method = first[0].uppercase()
        val rawPath = first[1]
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (length > MAX_BODY) throw BodyTooLargeException()
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n < 0) break
            offset += n
        }
        val pathPart = rawPath.substringBefore('?')
        val query = parseQuery(rawPath.substringAfter('?', ""))
        return Request(method, rawPath, decode(pathPart), query, headers, bytes.copyOf(offset).toString(StandardCharsets.UTF_8), remoteAddress)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (prev == '\r'.code && b == '\n'.code) {
                val data = out.toByteArray()
                return String(data, 0, (data.size - 1).coerceAtLeast(0), StandardCharsets.UTF_8)
            }
            out.write(b)
            prev = b
            if (out.size() > 16 * 1024) throw IllegalArgumentException("header line too long")
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            val k = decode(if (idx < 0) part else part.substring(0, idx))
            if (k.isBlank()) null else k to decode(if (idx < 0) "" else part.substring(idx + 1))
        }.toMap()
    }
    private fun decode(v: String): String = URLDecoder.decode(v, StandardCharsets.UTF_8.name())

    /** ChatRepository once the app has finished booting; used by the title route. */
    private fun android.content.Context.chatRepositoryOrThrow() =
        (applicationContext as com.openminis.app.MinisApp).chatRepository


    /**
     * Method families reachable from the browser.
     *
     * The `debug.` family is only allowed for the read-only diagnostic subset:
     * logs, crash reports, and app metadata. Everything that amounts to remote
     * control of the phone — tap, inputText, screenshot, ls, readFile,
     * writeFile, shellExecute and the browser/UI-inspection methods — stays off
     * this surface. Web Remote can be published to the open internet through a
     * tunnel, so the local debug server on 127.0.0.1:5321 remains the only way
     * to reach those methods.
     *
     * The writable families below (skills/memory/soul/mcp/scheduled) can change
     * or delete on-device data, so the Web Remote login password is the
     * boundary protecting them when a tunnel is enabled.
     */
    private val RPC_ALLOWED_PREFIXES = arrayOf(
        "provider.", "chat.", "rpc.discover",
        "skills.", "memory.", "soul.",
        "mcp.", "scheduled.", "environments.", "storage.",
        "agent.",
        "settings.",
        "debug.logs.", "debug.crash.", "debug.appInfo"
    )

    /**
     * Methods that fall under an allowed prefix but must still be blocked
     * over the Web Remote:
     *  - provider.export / provider.import carry the stored API keys /
     *    OAuth credentials (base64-wrapped) and arbitrary provider config;
     *    the Web Remote can be published through a public tunnel, so those
     *    must never be reachable from the browser.
     *  - debug.logs.setEnabled is a state mutation, not read-only
     *    diagnostics.
     *
     * Keep this list in sync whenever a new sensitive method is added to
     * DebugRPCHandler: a prefix allowlist protects the *shape* of the RPC
     * surface, the deny list protects its *credentials*.
     */
    private val RPC_DENIED_METHODS = setOf(
        "provider.export", "provider.import",
        "debug.logs.setEnabled",
    )

    private fun isRpcMethodAllowed(method: String): Boolean =
        method.isNotEmpty() &&
            method !in RPC_DENIED_METHODS &&
            RPC_ALLOWED_PREFIXES.any {
                if (it.endsWith(".")) method.startsWith(it) else method == it
            }

    private fun respondJson(
        out: BufferedOutputStream,
        code: Int,
        obj: JSONObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = respond(out, code, "application/json; charset=utf-8", obj.toString(), extraHeaders)

    private fun respond(
        out: BufferedOutputStream,
        code: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = respondBytes(out, code, contentType, body.toByteArray(StandardCharsets.UTF_8), extraHeaders)

    private fun respondBytes(
        out: BufferedOutputStream,
        code: Int,
        contentType: String,
        bytes: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val reason = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized";
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed";
            413 -> "Payload Too Large"; 426 -> "Upgrade Required"; 429 -> "Too Many Requests"; 503 -> "Service Unavailable";
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n")
            append("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' ws: wss:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n")
            for ((name, value) in extraHeaders) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        out.write(headers); out.write(bytes); out.flush()
    }
}
