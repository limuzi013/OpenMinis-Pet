package com.openminis.app.remote

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs Cloudflare's official Linux/ARM64 cloudflared inside OpenMinis' existing
 * PRoot environment. No inbound port or public IP is required: cloudflared
 * makes the outbound connector and forwards the remotely-managed tunnel to the
 * loopback Web Remote server.
 *
 * The tunnel token is passed through the documented TUNNEL_TOKEN environment
 * variable rather than a process argument, so it does not appear in ps output
 * or in the command line we log.
 */
object CloudflareTunnelManager {
    private const val TAG = "CloudflareTunnel"
    // Supply-chain note (audit 2026-08-21): "latest" means the download is
    // not byte-reproducible and no pinned SHA-256 can be checked. Transport
    // integrity is HTTPS (GitHub), size is capped (MAX_BINARY_BYTES), and the
    // binary is executed only after `cloudflared version` runs inside PRoot -
    // an unexpected/backdoored binary would at worst fail that check or act
    // as the user's own tunnel connector. Pinning a version would require
    // updating this constant on every release; evaluated as proportionate for
    // a sideloaded self-use app. If this ever ships to a broader audience,
    // pin version + digest and verify before renameTo().
    private const val DOWNLOAD_URL =
        "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
    private const val MAX_BINARY_BYTES = 80L * 1024L * 1024L

    data class Status(
        val installed: Boolean = false,
        val running: Boolean = false,
        val phase: String = "idle",
        val detail: String = "",
        val version: String = "",
    )

    private val TALLOC_BLOCK = Regex("""contains\s+\d+ bytes in\s+\d+ blocks""")

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var process: Process? = null
    private val lock = Any()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun refresh(context: Context): Status = withContext(Dispatchers.IO) {
        val file = binaryFile(context)
        val current = _status.value.copy(installed = file.isFile && file.length() > 1_000_000L)
        _status.value = current
        current
    }

    suspend fun installOrUpdate(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            _status.value = _status.value.copy(phase = "installing", detail = "Downloading cloudflared…")
            PRootKernel.boot(context.applicationContext)
            val target = binaryFile(context)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.download")
            tmp.delete()

            val request = Request.Builder().url(DOWNLOAD_URL).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("cloudflared download HTTP ${response.code}")
                val body = response.body ?: error("cloudflared download returned no body")
                val announced = body.contentLength()
                if (announced > MAX_BINARY_BYTES) error("cloudflared binary is unexpectedly large")
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BINARY_BYTES) error("cloudflared download exceeded size limit")
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
            if (tmp.length() < 1_000_000L) error("cloudflared binary download is incomplete")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target.setReadable(true, true)
            target.setExecutable(true, true)

            val versionResult = runOneShot(context, "/opt/bin/cloudflared version", emptyMap(), 15_000L)
            if (versionResult.first != 0) {
                target.delete()
                error("cloudflared cannot run in PRoot: ${versionResult.second.take(300)}")
            }
            val version = versionResult.second.trim().lineSequence().firstOrNull().orEmpty()
            _status.value = Status(
                installed = true,
                running = process?.isAlive == true,
                phase = if (process?.isAlive == true) "connected" else "ready",
                detail = "cloudflared ready",
                version = version,
            )
            version
        }.onFailure {
            Log.w(TAG, "install failed: ${it.message}", it)
            _status.value = _status.value.copy(
                installed = binaryFile(context).isFile,
                running = false,
                phase = "error",
                detail = it.message ?: "cloudflared install failed",
            )
        }
    }

    suspend fun start(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val token = RemoteAccessPrefs.cloudflareTunnelToken(app)
                ?: error("Cloudflare Tunnel Token is not configured")
            if (!RemoteAccessPrefs.cloudflareTunnelEnabled(app)) return@runCatching

            PRootKernel.boot(app)
            if (!binaryFile(app).isFile) {
                installOrUpdate(app).getOrThrow()
            }

            synchronized(lock) {
                if (process?.isAlive == true) return@runCatching
                _status.value = _status.value.copy(
                    installed = true,
                    running = false,
                    phase = "connecting",
                    detail = "Connecting to Cloudflare…",
                )
                // Android carrier/Wi-Fi networks frequently pass cloudflared's
                // short QUIC pre-check but then drop the long-lived UDP/7844
                // control stream. Auto mode waits through several exponential
                // retries before falling back, leaving the UI in "connecting"
                // for about a minute. TCP/7844 HTTP/2 is supported by named
                // tunnels and is substantially more reliable on mobile networks.
                val pb = prootProcessBuilder(
                    app,
                    "/opt/bin/cloudflared tunnel --no-autoupdate --protocol http2 run",
                )
                pb.environment()["TUNNEL_TOKEN"] = token
                val p = pb.start()
                process = p
                Thread({ drainProcess(p) }, "cloudflared-log").apply { isDaemon = true }.start()
            }
        }.onFailure {
            Log.w(TAG, "start failed: ${it.message}", it)
            _status.value = _status.value.copy(running = false, phase = "error", detail = it.message ?: "Tunnel failed")
        }
    }

    fun stop() {
        synchronized(lock) {
            val p = process
            process = null
            if (p != null) {
                runCatching { p.destroy() }
                runCatching {
                    if (p.isAlive) {
                        Thread.sleep(250)
                        if (p.isAlive) p.destroyForcibly()
                    }
                }
            }
        }
        _status.value = _status.value.copy(running = false, phase = "stopped", detail = "Tunnel stopped")
    }

    private fun drainProcess(p: Process) {
        var last = ""
        // Kept separately from `last`: cloudflared prints the reason first and
        // then keeps talking ("See 'cloudflared tunnel run --help'."), so the
        // final line is rarely the useful one.
        var lastError = ""
        try {
            p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val clean = redact(line)
                    Log.i(TAG, clean)
                    if (!isNoise(clean)) {
                        last = clean
                        if (looksLikeError(clean)) lastError = clean
                    }
                    val connected = line.contains("Registered tunnel connection", ignoreCase = true) ||
                        line.contains("Connection registered", ignoreCase = true)
                    if (connected) {
                        _status.value = _status.value.copy(running = true, phase = "connected", detail = "Tunnel connected")
                    } else if (_status.value.phase == "connecting" && last.isNotBlank()) {
                        _status.value = _status.value.copy(detail = last.take(220))
                    }
                }
            }
        } catch (e: Exception) {
            last = e.message ?: "cloudflared output closed"
        } finally {
            if (lastError.isNotBlank()) last = lastError
            val exit = runCatching { p.waitFor() }.getOrNull()
            synchronized(lock) {
                if (process === p) process = null
            }
            val enabled = managerContext?.let { RemoteAccessPrefs.cloudflareTunnelEnabled(it) } == true
            _status.value = _status.value.copy(
                running = false,
                phase = if (enabled) "error" else "stopped",
                detail = buildString {
                    append("cloudflared stopped")
                    if (exit != null) append(" (exit $exit)")
                    if (last.isNotBlank()) append(": ").append(last.take(180))
                },
            )
        }
    }

    // Used only to classify an unexpected connector exit after the service has
    // already handed control to the background log-drain thread.
    @Volatile private var managerContext: Context? = null

    private fun binaryFile(context: Context): File {
        managerContext = context.applicationContext
        return File(RootfsManager.getInstance(context.applicationContext).rootfsDir, "opt/bin/cloudflared")
    }

    /**
     * PRoot's talloc dumps its whole allocation table when the sandboxed
     * process exits. Dozens of lines of "NAME contains N bytes in M blocks",
     * none of which say anything about why the tunnel failed.
     */
    private fun isNoise(line: String): Boolean {
        val t = line.trim()
        return t.isEmpty() ||
            t.startsWith("talloc report on") ||
            t.startsWith("proot info:") ||
            TALLOC_BLOCK.containsMatchIn(t)
    }

    /** Lines worth surfacing over whatever cloudflared printed last. */
    private fun looksLikeError(line: String): Boolean {
        val t = line.lowercase()
        return "not valid" in t || "invalid" in t || "error" in t ||
            "failed" in t || "unauthorized" in t || "cannot" in t ||
            "refused" in t || "no such" in t
    }

    private fun redact(line: String): String {
        // cloudflared should never echo TUNNEL_TOKEN, but keep a defensive
        // redaction for JWT-looking values in case an upstream log changes.
        return line.replace(Regex("eyJ[A-Za-z0-9._=-]{24,}"), "<redacted-token>")
    }

    private fun prootProcessBuilder(context: Context, command: String): ProcessBuilder {
        val pb = ProcessBuilder(PRootKernel.buildProotCommand(command))
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        for ((key, value) in PRootKernel.customEnvironment) env[key] = value
        return pb
    }

    private fun runOneShot(
        context: Context,
        command: String,
        environment: Map<String, String>,
        timeoutMs: Long,
    ): Pair<Int, String> {
        val pb = prootProcessBuilder(context, command)
        pb.environment().putAll(environment)
        val p = pb.start()
        val text = StringBuilder()
        val reader = Thread {
            runCatching { p.inputStream.bufferedReader(Charsets.UTF_8).use { text.append(it.readText()) } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            reader.join(1_000L)
            return -1 to "Timed out"
        }
        reader.join(1_000L)
        return p.exitValue() to text.toString()
    }
}
