package com.openminis.app.tools.android

import android.content.Context
import android.os.SystemClock
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.tools.ApprovalSeam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Backend selected for one privileged Android command. */
enum class PrivilegedBackend { ROOT, SHIZUKU, NONE }

/** Risk of one privileged command, used to keep mutation approval explicit. */
enum class CommandRisk { READ_ONLY, USER_VISIBLE, MUTATING, ROOT_SETUP }

/** Result of one argv-based Android command. */
data class AndroidCommandResult(
    val backend: PrivilegedBackend,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val unavailableReason: String? = null,
) {
    val success: Boolean get() = exitCode == 0 && unavailableReason == null
}

/** Actual identity and kernel-policy facts returned by an active `su` probe. */
data class RootProbeResult(
    val authorized: Boolean,
    val effectiveUid: Int? = null,
    val effectiveGid: Int? = null,
    val groups: List<String> = emptyList(),
    val effectiveCapabilitiesHex: String? = null,
    val selinuxContext: String? = null,
    val selinuxMode: String? = null,
    val error: String? = null,
) {
    fun hasCapability(bit: Int): Boolean = LinuxCapabilityParser.hasBit(effectiveCapabilitiesHex, bit)
}

/** Pure parsers for `id`, `/proc/self/status`, and SELinux probe output. */
object RootProbeParser {
    private val uidRegex = Regex("""uid=(\d+)(?:\(([^)]*)\))?""")
    private val gidRegex = Regex("""gid=(\d+)(?:\(([^)]*)\))?""")
    private val groupsRegex = Regex("""groups=([^\n]+)""")
    private val capRegex = Regex("""(?m)^CapEff:\s*([0-9a-fA-F]+)\s*$""")
    private val contextRegex = Regex("""(?m)^__CONTEXT__\s*\n([^\n]+)""")
    private val modeRegex = Regex("""(?m)^__MODE__\s*\n([^\n]+)""")

    fun parse(stdout: String, exitCode: Int, stderr: String = ""): RootProbeResult {
        val uid = uidRegex.find(stdout)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val gid = gidRegex.find(stdout)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val groups = groupsRegex.find(stdout)?.groupValues?.getOrNull(1)
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val cap = capRegex.find(stdout)?.groupValues?.getOrNull(1)?.lowercase()
        val context = contextRegex.find(stdout)?.groupValues?.getOrNull(1)?.trim()
            ?.takeUnless { it.isBlank() || it.equals("unknown", true) }
        val mode = modeRegex.find(stdout)?.groupValues?.getOrNull(1)?.trim()
            ?.takeUnless { it.isBlank() || it.equals("unknown", true) }
        val authorized = exitCode == 0 && uid == 0
        val error = when {
            authorized -> null
            stderr.isNotBlank() -> stderr.trim().take(500)
            exitCode != 0 -> "su probe exited with code $exitCode"
            uid != 0 -> "su returned effective uid=${uid ?: "unknown"}, not uid 0"
            else -> "root authorization was not established"
        }
        return RootProbeResult(
            authorized = authorized,
            effectiveUid = uid,
            effectiveGid = gid,
            groups = groups,
            effectiveCapabilitiesHex = cap,
            selinuxContext = context,
            selinuxMode = mode,
            error = error,
        )
    }
}

/** Linux effective-capability bit parser; never equates uid 0 with all bits. */
object LinuxCapabilityParser {
    fun hasBit(hex: String?, bit: Int): Boolean {
        if (hex.isNullOrBlank() || bit !in 0..63) return false
        return runCatching {
            val value = java.lang.Long.parseUnsignedLong(hex, 16)
            (value and (1L shl bit)) != 0L
        }.getOrDefault(false)
    }
}

/** Direct `su` backend. Passive detection never starts `su`. */
object RootCommandRunner {
    private const val MAX_CAPTURE_CHARS = 1_048_576
    private val knownPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/data/adb/ksu/bin/su", "/debug_ramdisk/su",
    )

    @Volatile private var lastProbe: RootProbeResult? = null
    @Volatile private var lastProbeElapsed: Long = 0L

    fun passiveSuPath(): String? {
        knownPaths.firstOrNull { File(it).canExecute() }?.let { return it }
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, "su") }.firstOrNull { it.canExecute() }
        return path?.absolutePath
    }

    fun cachedProbe(): RootProbeResult? = lastProbe

    suspend fun activeProbe(): RootProbeResult = withContext(Dispatchers.IO) {
        val command = """
            printf '__ID__\n'; id
            printf '__CAPS__\n'; grep '^CapEff:' /proc/self/status 2>/dev/null || true
            printf '__CONTEXT__\n'; (id -Z 2>/dev/null || cat /proc/self/attr/current 2>/dev/null || echo unknown)
            printf '__MODE__\n'; (getenforce 2>/dev/null || echo unknown)
        """.trimIndent()
        val result = runSu(command, 15_000L)
        RootProbeParser.parse(result.stdout, result.exitCode, result.stderr).also {
            lastProbe = it
            lastProbeElapsed = SystemClock.elapsedRealtime()
        }
    }

    suspend fun run(argv: List<String>, timeoutMs: Long): AndroidCommandResult = withContext(Dispatchers.IO) {
        if (cachedProbe()?.authorized != true) {
            return@withContext AndroidCommandResult(
                backend = PrivilegedBackend.NONE,
                exitCode = 126,
                stdout = "",
                stderr = "",
                unavailableReason = "root authorization has not been actively granted",
            )
        }
        runSu(shellJoin(argv), timeoutMs)
    }

    private fun runSu(command: String, timeoutMs: Long): AndroidCommandResult {
        val su = passiveSuPath() ?: return AndroidCommandResult(
            PrivilegedBackend.NONE, 127, "", "", unavailableReason = "su executable not found",
        )
        return runProcess(listOf(su, "-c", command), timeoutMs, PrivilegedBackend.ROOT)
    }

    internal fun shellJoin(argv: List<String>): String = argv.joinToString(" ") { value ->
        "'" + value.replace("'", "'\\''") + "'"
    }

    internal fun runProcess(
        argv: List<String>,
        timeoutMs: Long,
        backend: PrivilegedBackend,
    ): AndroidCommandResult {
        return try {
            val process = ProcessBuilder(argv).start()
            val stdout = BoundedText(MAX_CAPTURE_CHARS)
            val stderr = BoundedText(MAX_CAPTURE_CHARS)
            val outThread = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { stdout.appendLine(it) } } }
            val errThread = Thread { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { stderr.appendLine(it) } } }
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()
            val exited = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            if (!exited) process.destroyForcibly()
            outThread.join(2_000L)
            errThread.join(2_000L)
            AndroidCommandResult(
                backend = backend,
                exitCode = if (exited) process.exitValue() else 124,
                stdout = stdout.value(),
                stderr = stderr.value(),
                timedOut = !exited,
            )
        } catch (t: Throwable) {
            AndroidCommandResult(backend, -1, "", t.message.orEmpty())
        }
    }

    private class BoundedText(private val maxChars: Int) {
        private val value = StringBuilder()
        @Synchronized fun appendLine(line: String) {
            if (value.length >= maxChars) return
            val remaining = maxChars - value.length
            value.append(line.take((remaining - 1).coerceAtLeast(0)))
            if (remaining > 0) value.append('\n')
        }
        @Synchronized fun value(): String = value.toString().trimEnd()
    }
}

/** Unified seam only for operations that genuinely need shell privilege. */
object PrivilegedCommandRunner {
    suspend fun run(
        context: Context,
        sessionId: String,
        argv: List<String>,
        operation: String,
        risk: CommandRisk = CommandRisk.READ_ONLY,
        timeoutMs: Long = 30_000L,
        rootOnly: Boolean = false,
    ): AndroidCommandResult {
        require(argv.isNotEmpty()) { "privileged command argv must not be empty" }
        if (risk == CommandRisk.MUTATING || risk == CommandRisk.ROOT_SETUP) {
            val decision = ApprovalSeam.request(
                context,
                sessionId,
                "android_privileged",
                "$operation\n\n${argv.joinToString(" ")}",
            )
            if (decision.decision != "allowed-once") {
                return AndroidCommandResult(
                    PrivilegedBackend.NONE, 126, "", "",
                    unavailableReason = "operation was not approved (${decision.decision})",
                )
            }
        }

        if (RootCommandRunner.cachedProbe()?.authorized == true) {
            return RootCommandRunner.run(argv, timeoutMs)
        }
        if (!rootOnly && ShizukuManager.isReady()) {
            val allowed = OffloadPermissionManager.checkPermission(
                "shizuku_cli",
                operation,
                sessionId.ifBlank { OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID },
            )
            if (!allowed) {
                return AndroidCommandResult(
                    PrivilegedBackend.NONE, 126, "", "",
                    unavailableReason = "android-shizuku-cli permission is not allowed",
                )
            }
            return withContext(Dispatchers.IO) {
                val result = ShizukuManager.runProcess(argv.toTypedArray(), timeoutMs = timeoutMs)
                AndroidCommandResult(
                    backend = PrivilegedBackend.SHIZUKU,
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    timedOut = result.exitCode == 124,
                )
            }
        }
        return AndroidCommandResult(
            PrivilegedBackend.NONE,
            126,
            "",
            "",
            unavailableReason = if (rootOnly) {
                "authorized root with the required capability is unavailable"
            } else {
                "neither authorized root nor an authorized Shizuku shell is available"
            },
        )
    }

    /** Explicit root authorization probe. Never called by passive capability reads. */
    suspend fun requestActiveRootProbe(context: Context, sessionId: String): RootProbeResult {
        if (RootCommandRunner.passiveSuPath() == null) {
            return RootProbeResult(false, error = "su executable not found")
        }
        val decision = ApprovalSeam.request(
            context,
            sessionId,
            "android_capabilities",
            "主动请求 Root 授权并读取 uid/gid/capabilities/SELinux 状态",
        )
        if (decision.decision != "allowed-once") {
            return RootProbeResult(false, error = "root probe was not approved (${decision.decision})")
        }
        return RootCommandRunner.activeProbe()
    }
}
