package com.openminis.app.tools.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** App information and lifecycle operations over SDK APIs plus the privileged seam. */
object AndroidPackageController {
    private val packagePattern = Regex("""^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$""")

    fun requirePackageName(value: String): String {
        val packageName = value.trim()
        require(packagePattern.matches(packageName)) { "invalid Android package name: $value" }
        return packageName
    }

    suspend fun info(context: Context, sessionId: String, rawPackage: String): JSONObject {
        val packageName = requirePackageName(rawPackage)
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS
        val packageInfo = runCatching {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, flags)
        }.getOrNull()
        if (packageInfo != null) {
            val appInfo = packageInfo.applicationInfo
            val launcher = context.packageManager.getLaunchIntentForPackage(packageName)
            val pids = visiblePids(context, packageName)
            return JSONObject().apply {
                put("packageName", packageName)
                put("installed", true)
                put("visibility", "VISIBLE")
                put("versionName", packageInfo.versionName ?: JSONObject.NULL)
                put("versionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else {
                    @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
                })
                put("launcherActivity", launcher?.component?.className ?: JSONObject.NULL)
                put("running", pids.isNotEmpty())
                put("pids", JSONArray(pids))
                put("apkPath", appInfo?.sourceDir ?: JSONObject.NULL)
                put("debuggable", ((appInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                put("system", ((appInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0)
                put("permissions", JSONArray(packageInfo.requestedPermissions.orEmpty().toList()))
                put("userId", android.os.Process.myUserHandle().hashCode())
            }
        }

        val fallback = PrivilegedCommandRunner.run(
            context = context,
            sessionId = sessionId,
            argv = listOf("dumpsys", "package", packageName),
            operation = "读取 Android package 信息",
            risk = CommandRisk.READ_ONLY,
            timeoutMs = 15_000L,
        )
        if (fallback.success) {
            val parsed = AndroidDebugParsers.parsePackageDump(fallback.stdout, packageName)
            if (parsed != null) {
                val pids = privilegedPids(context, sessionId, packageName)
                parsed.put("visibility", "PRIVILEGED_FALLBACK")
                parsed.put("backend", fallback.backend.name.lowercase())
                parsed.put("running", pids.isNotEmpty())
                parsed.put("pids", JSONArray(pids))
                val launcher = resolveLauncherPrivileged(context, sessionId, packageName)
                parsed.put("launcherActivity", launcher ?: JSONObject.NULL)
                return parsed
            }
        }
        return JSONObject()
            .put("packageName", packageName)
            .put("installed", false)
            .put("visibility", if (fallback.unavailableReason != null) "NOT_VISIBLE_OR_NOT_FOUND" else "NOT_FOUND")
            .put("status", CapabilityStatus.PARTIAL.name)
            .put("detail", fallback.unavailableReason
                ?: "PackageManager and privileged package query did not find the package")
    }

    suspend fun launch(context: Context, sessionId: String, rawPackage: String, activity: String? = null): JSONObject {
        val packageName = requirePackageName(rawPackage)
        val explicit = activity?.trim()?.takeIf(String::isNotEmpty)
        val intent = if (explicit == null) context.packageManager.getLaunchIntentForPackage(packageName) else {
            Intent().setClassName(packageName, explicit)
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                AndroidDebugSessionStore.update(sessionId) {
                    it.copy(targetPackage = packageName, launchActivity = intent.component?.className)
                }
                JSONObject().put("packageName", packageName).put("launched", true)
                    .put("activity", intent.component?.className ?: JSONObject.NULL)
                    .put("backend", "android-sdk")
            } catch (_: Throwable) {
                launchPrivileged(context, sessionId, packageName, explicit)
            }
        }
        return launchPrivileged(context, sessionId, packageName, explicit)
    }

    suspend fun stop(context: Context, sessionId: String, rawPackage: String, userId: Int? = null): JSONObject {
        val packageName = requirePackageName(rawPackage)
        val argv = mutableListOf("am", "force-stop")
        userId?.let { argv += listOf("--user", it.toString()) }
        argv += packageName
        val result = PrivilegedCommandRunner.run(
            context, sessionId, argv, "停止 Android App $packageName", CommandRisk.USER_VISIBLE, 30_000L,
        )
        return commandJson(result).put("packageName", packageName).put("stopped", result.success)
    }

    suspend fun restart(
        context: Context,
        sessionId: String,
        rawPackage: String,
        activity: String? = null,
        userId: Int? = null,
    ): JSONObject {
        val stopped = stop(context, sessionId, rawPackage, userId)
        if (!stopped.optBoolean("stopped")) return stopped.put("restarted", false)
        val launched = launch(context, sessionId, rawPackage, activity)
        return JSONObject()
            .put("packageName", requirePackageName(rawPackage))
            .put("stopped", true)
            .put("launched", launched.optBoolean("launched"))
            .put("restarted", launched.optBoolean("launched"))
            .put("launch", launched)
    }

    suspend fun install(
        context: Context,
        sessionId: String,
        artifact: ApkArtifact,
        allowDowngrade: Boolean,
        grantRuntimePermissions: Boolean,
        userId: Int? = null,
    ): JSONObject {
        val staged = AndroidApkInspector.stageForInstaller(context, artifact)
        val argv = mutableListOf("pm", "install", "-r")
        if (allowDowngrade) argv += "-d"
        if (grantRuntimePermissions) argv += "-g"
        userId?.let { argv += listOf("--user", it.toString()) }
        argv += staged.absolutePath
        val result = PrivilegedCommandRunner.run(
            context,
            sessionId,
            argv,
            "安装 APK ${artifact.packageName}",
            CommandRisk.MUTATING,
            timeoutMs = 180_000L,
        )
        if (result.success) {
            AndroidDebugSessionStore.update(sessionId) {
                it.copy(targetPackage = artifact.packageName, artifactPath = artifact.linuxPath ?: artifact.hostPath)
            }
        }
        return commandJson(result)
            .put("installed", result.success && result.stdout.contains("Success", ignoreCase = true))
            .put("packageName", artifact.packageName)
            .put("artifact", artifact.toJson())
            .put("stagedPath", staged.absolutePath)
    }

    suspend fun uninstall(
        context: Context,
        sessionId: String,
        rawPackage: String,
        keepData: Boolean,
        userId: Int? = null,
    ): JSONObject {
        val packageName = requirePackageName(rawPackage)
        val argv = mutableListOf("pm", "uninstall")
        if (keepData) argv += "-k"
        userId?.let { argv += listOf("--user", it.toString()) }
        argv += packageName
        val result = PrivilegedCommandRunner.run(
            context, sessionId, argv, "卸载 Android App $packageName", CommandRisk.MUTATING, 90_000L,
        )
        return commandJson(result)
            .put("packageName", packageName)
            .put("uninstalled", result.success && result.stdout.contains("Success", ignoreCase = true))
    }

    suspend fun pids(context: Context, sessionId: String, packageName: String): List<Int> {
        val normal = visiblePids(context, packageName)
        return if (normal.isNotEmpty()) normal else privilegedPids(context, sessionId, packageName)
    }

    private fun visiblePids(context: Context, packageName: String): List<Int> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        return manager.runningAppProcesses.orEmpty()
            .filter { it.processName == packageName || it.processName.startsWith("$packageName:") }
            .map { it.pid }.distinct()
    }

    private suspend fun privilegedPids(context: Context, sessionId: String, packageName: String): List<Int> {
        val result = PrivilegedCommandRunner.run(
            context, sessionId, listOf("pidof", packageName), "读取 App PID", CommandRisk.READ_ONLY, 10_000L,
        )
        if (!result.success) return emptyList()
        return result.stdout.trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull).filter { it > 0 }
    }

    private suspend fun resolveLauncherPrivileged(context: Context, sessionId: String, packageName: String): String? {
        val result = PrivilegedCommandRunner.run(
            context, sessionId,
            listOf("cmd", "package", "resolve-activity", "--brief", "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, packageName),
            "解析 Launcher Activity", CommandRisk.READ_ONLY, 10_000L,
        )
        return result.stdout.lineSequence().map(String::trim)
            .firstOrNull { it.contains('/') && !it.startsWith("No activity") }
    }

    private suspend fun launchPrivileged(
        context: Context,
        sessionId: String,
        packageName: String,
        activity: String?,
    ): JSONObject {
        val argv = if (activity != null) {
            listOf("am", "start", "-W", "-n", "$packageName/$activity")
        } else {
            listOf("monkey", "-p", packageName, "-c", Intent.CATEGORY_LAUNCHER, "1")
        }
        val result = PrivilegedCommandRunner.run(
            context, sessionId, argv, "启动 Android App $packageName", CommandRisk.USER_VISIBLE, 30_000L,
        )
        if (result.success) {
            AndroidDebugSessionStore.update(sessionId) {
                it.copy(targetPackage = packageName, launchActivity = activity)
            }
        }
        return commandJson(result).put("packageName", packageName).put("activity", activity ?: JSONObject.NULL)
            .put("launched", result.success)
    }

    fun commandJson(result: AndroidCommandResult): JSONObject = JSONObject().apply {
        put("backend", result.backend.name.lowercase())
        put("exitCode", result.exitCode)
        put("stdout", result.stdout.take(32_000))
        put("stderr", result.stderr.take(16_000))
        put("timedOut", result.timedOut)
        put("success", result.success)
        result.unavailableReason?.let {
            put("status", CapabilityStatus.REQUIRES_USER_GRANT.name)
            put("unavailableReason", it)
        }
    }
}
