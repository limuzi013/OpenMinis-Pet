package com.openminis.app.tools.android

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.openminis.app.BuildConfig
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.data.ContextOffload
import com.openminis.app.tools.internal.ToolResultPruner
import org.json.JSONArray
import org.json.JSONObject

/** Structured process, memory, crash, ANR, and stack diagnosis. */
object AndroidDiagnoseController {
    suspend fun diagnose(
        context: Context,
        sessionId: String,
        packageNameRaw: String,
        action: String,
        cursor: String?,
        toolId: String,
    ): JSONObject {
        val packageName = AndroidPackageController.requirePackageName(packageNameRaw)
        val requested = when (action) {
            "all" -> listOf("process", "memory", "crash", "anr")
            "process", "memory", "crash", "anr", "stack" -> listOf(action)
            else -> throw IllegalArgumentException("unknown android_diagnose action: $action")
        }
        val available = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        val pids = AndroidPackageController.pids(context, sessionId, packageName)
        val foregroundPackage = MinisAccessibilityService.getInstance()?.foregroundPackage()?.first
        val root = JSONObject()
            .put("package", packageName)
            .put("pids", JSONArray(pids))
            .put("foreground", foregroundPackage == packageName)
            .put("status", if (pids.isEmpty()) "not_running" else "running")

        if ("process" in requested) {
            root.put("process", processSection(context, sessionId, packageName, pids))
            available += "process"
        }
        if ("memory" in requested) {
            val memory = memorySection(context, sessionId, packageName, pids)
            if (memory == null) unavailable += "memory" else {
                root.put("memory", memory)
                available += "memory"
            }
        }
        if ("crash" in requested) {
            val crash = crashSection(context, sessionId, packageName, cursor)
            if (crash == null) unavailable += "crash" else {
                root.put("crash", crash)
                root.put("status", "crashed")
                available += "crash"
            }
        }
        if ("anr" in requested) {
            val anr = anrSection(context, sessionId, packageName)
            if (anr == null) {
                root.put("anr", false)
                unavailable += "anr"
            } else {
                root.put("anr", anr)
                available += "anr"
            }
        }
        if ("stack" in requested) {
            val stack = stackSection(context, sessionId, pids.firstOrNull(), toolId)
            if (stack == null) unavailable += "stack" else {
                root.put("stack", stack)
                available += "stack"
            }
        }
        root.put("availableSections", JSONArray(available))
        root.put("unavailableSections", JSONArray(unavailable))
        root.put("capability", AndroidCapabilityResolver.resolve(context).getJSONObject("debug"))
        AndroidDebugSessionStore.update(sessionId) { it.copy(targetPackage = packageName, lastPidSet = pids.toSet()) }
        return root
    }

    private suspend fun processSection(
        context: Context,
        sessionId: String,
        packageName: String,
        knownPids: List<Int>,
    ): JSONObject {
        val result = PrivilegedCommandRunner.run(
            context, sessionId, listOf("ps", "-A", "-o", "PID,NAME"), "读取进程表", CommandRisk.READ_ONLY, 15_000L,
        )
        val rows = if (result.success) AndroidDebugParsers.parseProcesses(result.stdout)
            .filter { it.name == packageName || it.name.startsWith("$packageName:") }
        else knownPids.map { AndroidProcessRow(it, packageName) }
        return JSONObject()
            .put("status", if (result.success) CapabilityStatus.AVAILABLE.name else CapabilityStatus.PARTIAL.name)
            .put("backend", if (result.success) result.backend.name.lowercase() else "android-sdk")
            .put("processes", JSONArray(rows.map { JSONObject().put("pid", it.pid).put("name", it.name) }))
    }

    private suspend fun memorySection(
        context: Context,
        sessionId: String,
        packageName: String,
        pids: List<Int>,
    ): JSONObject? {
        val result = PrivilegedCommandRunner.run(
            context, sessionId, listOf("dumpsys", "meminfo", packageName), "读取 App 内存", CommandRisk.READ_ONLY, 30_000L,
        )
        if (result.success) {
            return AndroidDebugParsers.parseMeminfo(result.stdout)?.put("status", CapabilityStatus.AVAILABLE.name)
                ?.put("backend", result.backend.name.lowercase())
        }
        if (packageName != BuildConfig.APPLICATION_ID || pids.isEmpty()) return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val infos = manager.getProcessMemoryInfo(pids.toIntArray())
        val pss = infos.sumOf { it.totalPss }
        return JSONObject()
            .put("status", CapabilityStatus.PARTIAL.name)
            .put("backend", "android-sdk")
            .put("totalPssKb", pss)
            .put("detail", "ordinary SDK memory info is limited to OpenMinis-owned processes")
    }

    private suspend fun crashSection(
        context: Context,
        sessionId: String,
        packageName: String,
        cursor: String?,
    ): JSONObject? {
        if (!cursor.isNullOrBlank()) {
            val since = AndroidLogManager.readSince(context, sessionId, cursor)
            val important = since.optJSONArray("importantLines") ?: JSONArray()
            val raw = (0 until important.length()).joinToString("\n") { important.optString(it) }
            AndroidDebugParsers.parseCrash(raw, packageName)?.let {
                return it.put("sourceChannel", "logcat-cursor").put("cursor", cursor)
            }
        }
        val args = listOf("logcat", "-b", "crash", "-d", "-v", "epoch", "-t", "500")
        val privileged = PrivilegedCommandRunner.run(
            context, sessionId, args, "读取 crash log buffer", CommandRisk.READ_ONLY, 30_000L,
        )
        val result = if (privileged.success) privileged else {
            RootCommandRunner.runProcess(args, 30_000L, PrivilegedBackend.NONE)
        }
        val related = result.stdout.lineSequence().filter { line ->
            line.contains(packageName) || line.contains("AndroidRuntime") || line.contains("FATAL EXCEPTION") ||
                line.trimStart().startsWith("at ") || line.contains("Caused by:") || line.contains("Fatal signal")
        }.joinToString("\n")
        return AndroidDebugParsers.parseCrash(related, packageName)?.put("sourceChannel", "logcat-crash")
            ?.put("capabilityStatus", if (privileged.success) CapabilityStatus.AVAILABLE.name else CapabilityStatus.PARTIAL.name)
    }

    private suspend fun anrSection(context: Context, sessionId: String, packageName: String): JSONObject? {
        val result = PrivilegedCommandRunner.run(
            context, sessionId, listOf("dumpsys", "activity", "lastanr"), "读取最后一次 ANR", CommandRisk.READ_ONLY, 20_000L,
        )
        if (!result.success || !result.stdout.contains(packageName)) return null
        val preview = ToolResultPruner.prune(result.stdout) ?: result.stdout
        return JSONObject()
            .put("detected", true)
            .put("status", CapabilityStatus.PARTIAL.name)
            .put("backend", result.backend.name.lowercase())
            .put("source", "dumpsys activity lastanr")
            .put("content", preview)
            .put("detail", "ANR traces under /data/anr may remain unavailable under SELinux/OEM policy")
    }

    private suspend fun stackSection(
        context: Context,
        sessionId: String,
        pid: Int?,
        toolId: String,
    ): JSONObject? {
        if (pid == null) return null
        val result = PrivilegedCommandRunner.run(
            context = context,
            sessionId = sessionId,
            argv = listOf("debuggerd", "-b", pid.toString()),
            operation = "读取进程 backtrace",
            risk = CommandRisk.READ_ONLY,
            timeoutMs = 45_000L,
            rootOnly = true,
        )
        if (!result.success) return null
        val full = result.stdout.ifBlank { result.stderr }
        val pruned = ToolResultPruner.prune(full)
        val spill = if (pruned != null) ContextOffload.offloadContent(
            context, sessionId, full, toolId, "android_stack", "log",
        ) else null
        return JSONObject()
            .put("status", CapabilityStatus.PARTIAL.name)
            .put("backend", "root")
            .put("pid", pid)
            .put("content", pruned ?: full)
            .put("spillFile", spill ?: JSONObject.NULL)
            .put("detail", "debuggerd output availability still depends on SELinux and target process policy")
    }
}
