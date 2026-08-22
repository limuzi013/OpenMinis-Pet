package com.openminis.app.tools.android

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.data.ContextOffload
import com.openminis.app.tools.JobRegistry
import com.openminis.app.tools.internal.SpillPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Cursor-based logcat capture and JobRegistry-backed bounded watches. */
object AndroidLogManager {
    private const val MAX_LINES = 500
    private const val MAX_WATCH_BYTES = 256 * 1024
    private const val WATCH_POLL_MS = 1_500L

    data class LogCursor(
        val id: String,
        val sessionId: String,
        val packageName: String?,
        val markedAtMillis: Long,
        val bootId: String?,
        val pids: Set<Int>,
    )

    private val cursors = ConcurrentHashMap<String, LogCursor>()
    private val watches = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun markCursor(context: Context, sessionId: String, packageName: String?): JSONObject {
        val target = packageName?.trim()?.takeIf(String::isNotEmpty)
        val pids = target?.let { AndroidPackageController.pids(context, sessionId, it).toSet() }.orEmpty()
        val cursor = LogCursor(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            packageName = target,
            markedAtMillis = System.currentTimeMillis(),
            bootId = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull(),
            pids = pids,
        )
        cursors[cursor.id] = cursor
        AndroidDebugSessionStore.update(sessionId) {
            it.copy(targetPackage = target ?: it.targetPackage, logCursor = cursor.id, lastPidSet = pids)
        }
        return cursorJson(cursor)
    }

    suspend fun snapshot(
        context: Context,
        sessionId: String,
        packageName: String?,
        buffer: String,
        priority: String,
        maxLines: Int,
    ): JSONObject {
        val args = mutableListOf("logcat", "-d", "-v", "epoch", "-t", maxLines.coerceIn(1, MAX_LINES).toString())
        addFilters(args, buffer, priority)
        val captured = capture(context, sessionId, args, packageName, emptySet())
        return resultJson(context, sessionId, captured, packageName, null)
    }

    suspend fun readSince(context: Context, sessionId: String, cursorId: String): JSONObject {
        val cursor = cursors[cursorId]
            ?: return JSONObject().put("status", "CURSOR_NOT_FOUND").put("cursor", cursorId)
        if (cursor.sessionId != sessionId) {
            return JSONObject().put("status", "CURSOR_SESSION_MISMATCH").put("cursor", cursorId)
        }
        val currentBoot = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
        if (cursor.bootId != null && currentBoot != null && cursor.bootId != currentBoot) {
            return JSONObject().put("status", "CURSOR_BOOT_CHANGED")
                .put("detail", "device rebooted after the cursor was marked")
                .put("cursor", cursorId)
        }
        val pids = cursor.packageName?.let { AndroidPackageController.pids(context, sessionId, it).toSet() }.orEmpty()
        val args = mutableListOf(
            "logcat", "-d", "-v", "epoch", "-T", epoch(cursor.markedAtMillis),
        )
        val captured = capture(context, sessionId, args, cursor.packageName, cursor.pids + pids)
        return resultJson(context, sessionId, captured, cursor.packageName, cursor)
            .put("pidChanged", cursor.pids.isNotEmpty() && pids.isNotEmpty() && cursor.pids != pids)
            .put("currentPids", JSONArray(pids.toList()))
    }

    suspend fun clear(context: Context, sessionId: String): JSONObject {
        val result = PrivilegedCommandRunner.run(
            context, sessionId, listOf("logcat", "-c"), "清空 logcat buffer", CommandRisk.MUTATING, 20_000L,
        )
        return AndroidPackageController.commandJson(result).put("cleared", result.success)
    }

    suspend fun watch(
        context: Context,
        sessionId: String,
        packageName: String?,
        durationSeconds: Int,
    ): JSONObject {
        val cursorJson = markCursor(context, sessionId, packageName)
        val cursorId = cursorJson.getString("cursor")
        val jobId = JobRegistry.start("android-logcat", "logcat ${packageName ?: "device"}")
        val durationMs = durationSeconds.coerceIn(1, 300) * 1_000L
        val job = scope.launch {
            val started = System.currentTimeMillis()
            var since = started
            var retainedBytes = 0
            try {
                while (System.currentTimeMillis() - started < durationMs) {
                    val args = mutableListOf("logcat", "-d", "-v", "epoch", "-T", epoch(since))
                    val captured = capture(context, sessionId, args, packageName, emptySet())
                    val fresh = captured.raw.lineSequence().filter { line ->
                        AndroidDebugParsers.parseLogLine(line).epochMillis?.let { it >= since } ?: true
                    }.joinToString("\n")
                    if (fresh.isNotBlank() && retainedBytes < MAX_WATCH_BYTES) {
                        val bytes = fresh.toByteArray().size
                        val allowed = (MAX_WATCH_BYTES - retainedBytes).coerceAtLeast(0)
                        val text = if (bytes <= allowed) fresh else fresh.take(allowed)
                        JobRegistry.appendOutput(jobId, text + "\n")
                        retainedBytes += text.toByteArray().size
                        if (bytes > allowed) JobRegistry.appendOutput(jobId, "[android_logs watch output capped at $MAX_WATCH_BYTES bytes]\n")
                    }
                    since = System.currentTimeMillis() - 100L
                    delay(WATCH_POLL_MS)
                }
                JobRegistry.finish(jobId, JobRegistry.JobStatus.COMPLETED, "watch window completed")
            } catch (_: CancellationException) {
                JobRegistry.finish(jobId, JobRegistry.JobStatus.KILLED, "watch stopped")
            } catch (t: Throwable) {
                JobRegistry.appendOutput(jobId, "[watch failed: ${t.message}]\n")
                JobRegistry.finish(jobId, JobRegistry.JobStatus.FAILED, t.message.orEmpty())
            } finally {
                watches.remove(jobId)
            }
        }
        watches[jobId] = job
        return JSONObject()
            .put("jobId", jobId)
            .put("cursor", cursorId)
            .put("status", "RUNNING")
            .put("durationSeconds", durationMs / 1_000L)
    }

    fun readJob(context: Context, sessionId: String, jobId: String): JSONObject {
        val job = JobRegistry.get(jobId)
            ?: return JSONObject().put("status", "JOB_NOT_FOUND").put("jobId", jobId)
        val raw = JobRegistry.output(jobId).orEmpty()
        return resultJson(
            context,
            sessionId,
            CapturedLogs(raw, "job", CapabilityStatus.PARTIAL, "JobRegistry watch output"),
            null,
            null,
        ).put("jobId", jobId).put("jobStatus", job.status.name)
    }

    fun stop(jobId: String): JSONObject {
        watches.remove(jobId)?.cancel()
        val killed = JobRegistry.kill(jobId, "android_logs stop")
        return JSONObject().put("jobId", jobId).put("stopped", killed)
    }

    internal fun clearForTests() {
        cursors.clear()
        watches.values.forEach(Job::cancel)
        watches.clear()
    }

    private data class CapturedLogs(
        val raw: String,
        val backend: String,
        val status: CapabilityStatus,
        val detail: String,
    )

    private suspend fun capture(
        context: Context,
        sessionId: String,
        args: List<String>,
        packageName: String?,
        knownPids: Set<Int>,
    ): CapturedLogs {
        val privileged = if (RootCommandRunner.cachedProbe()?.authorized == true || ShizukuManagerReady.value()) {
            PrivilegedCommandRunner.run(
                context, sessionId, args, "读取 logcat", CommandRisk.READ_ONLY, 45_000L,
            )
        } else null
        val rawResult = if (privileged?.success == true) privileged else {
            RootCommandRunner.runProcess(args, 45_000L, PrivilegedBackend.NONE)
        }
        val pids = if (packageName == null) knownPids else knownPids +
            AndroidPackageController.pids(context, sessionId, packageName).toSet()
        val filtered = if (packageName == null) {
            rawResult.stdout
        } else {
            filterForPackage(rawResult.stdout, packageName, pids)
        }
        val status = when {
            privileged?.success == true -> CapabilityStatus.AVAILABLE
            rawResult.success -> CapabilityStatus.PARTIAL
            else -> CapabilityStatus.UNAVAILABLE
        }
        return CapturedLogs(
            raw = filtered,
            backend = when {
                privileged?.success == true -> privileged.backend.name.lowercase()
                else -> "app"
            },
            status = status,
            detail = when (status) {
                CapabilityStatus.AVAILABLE -> "full-device logcat through privileged shell"
                CapabilityStatus.PARTIAL -> "ordinary app logcat; Android restricts this to OpenMinis-owned logs"
                CapabilityStatus.REQUIRES_USER_GRANT -> "grant required"
                CapabilityStatus.UNAVAILABLE -> rawResult.stderr.ifBlank { rawResult.unavailableReason ?: "logcat unavailable" }
            },
        )
    }

    private fun filterForPackage(raw: String, packageName: String, pids: Set<Int>): String {
        var crashBlock = false
        return raw.lineSequence().filter { line ->
            val parsed = AndroidDebugParsers.parseLogLine(line)
            val startsCrash = line.contains("FATAL EXCEPTION") || line.contains("Process: $packageName") ||
                line.contains("ANR in $packageName") || line.contains("Fatal signal") && line.contains(packageName)
            if (startsCrash) crashBlock = true
            val matches = (parsed.pid?.let { it in pids } == true) || line.contains(packageName) || startsCrash ||
                crashBlock && (line.trimStart().startsWith("at ") || line.contains("Caused by:"))
            if (crashBlock && line.isBlank()) crashBlock = false
            matches
        }.joinToString("\n")
    }

    private fun resultJson(
        context: Context,
        sessionId: String,
        captured: CapturedLogs,
        packageName: String?,
        cursor: LogCursor?,
    ): JSONObject {
        val summary = AndroidDebugParsers.summarizeLogs(captured.raw)
        val bytes = captured.raw.toByteArray().size
        val spill = runCatching {
            SpillPolicy.spillIfOversized(
                text = captured.raw,
                spillDir = ContextOffload.toolsDir(context, sessionId),
                baseName = "android_logs",
            )
        }.getOrNull()
        return summary.apply {
            put("status", captured.status.name)
            put("backend", captured.backend)
            put("detail", captured.detail)
            put("package", packageName ?: JSONObject.NULL)
            put("bytes", bytes)
            cursor?.let { put("cursor", cursorJson(it)) }
            if (spill?.spilled == true && spill.fullPath != null) {
                put("spillFile", "${ContextOffload.LINUX_OFFLOADS_DIR}/tools/${File(spill.fullPath).name}")
            }
            put("bufferWrap", "UNKNOWN")
        }
    }

    private fun cursorJson(cursor: LogCursor): JSONObject = JSONObject()
        .put("cursor", cursor.id)
        .put("markedAt", cursor.markedAtMillis)
        .put("package", cursor.packageName ?: JSONObject.NULL)
        .put("pids", JSONArray(cursor.pids.toList()))
        .put("bootId", cursor.bootId ?: JSONObject.NULL)

    private fun addFilters(args: MutableList<String>, buffer: String, priority: String) {
        if (buffer in setOf("main", "system", "crash", "events", "radio", "all")) args += listOf("-b", buffer)
        if (priority in setOf("V", "D", "I", "W", "E", "F")) args += "*:$priority"
    }

    private fun epoch(millis: Long): String = String.format(Locale.US, "%.3f", millis / 1000.0)

    /** Avoid a hard dependency on Shizuku details in tests. */
    private object ShizukuManagerReady {
        fun value(): Boolean = runCatching { com.openminis.app.offload.ShizukuManager.isReady() }.getOrDefault(false)
    }
}
