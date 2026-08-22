package com.openminis.app.tools.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.openminis.app.BuildConfig
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque

/** Metadata read from a real APK artifact, never inferred from a fixed filename. */
data class ApkArtifact(
    val hostPath: String,
    val linuxPath: String?,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val debuggable: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val signingSha256: List<String>,
    val candidateActivities: List<String>,
    val source: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("artifactPath", linuxPath ?: hostPath)
        put("hostPath", hostPath)
        put("package", packageName)
        put("versionName", versionName ?: JSONObject.NULL)
        put("versionCode", versionCode)
        put("debuggable", debuggable)
        put("sizeBytes", sizeBytes)
        put("modifiedAt", modifiedAt)
        put("signingSha256", JSONArray(signingSha256))
        put("candidateActivities", JSONArray(candidateActivities))
        put("metadataSource", source)
    }
}

/** APK path resolution, Gradle-output discovery, and archive metadata parsing. */
object AndroidApkInspector {
    private const val MAX_DISCOVERY_DIRECTORIES = 4_000
    private const val MAX_DISCOVERY_DEPTH = 10

    fun inspect(
        context: Context,
        sessionId: String,
        artifactPath: String? = null,
        searchRoot: String? = null,
    ): ApkArtifact {
        val explicit = artifactPath?.trim().orEmpty()
        val file = if (explicit.isNotEmpty()) {
            resolvePath(context, sessionId, explicit)
                ?: throw IllegalArgumentException("APK path is not visible from this session: $explicit")
        } else {
            val root = searchRoot?.trim().orEmpty().takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("artifactPath or searchRoot is required; APK paths are never guessed")
            val hostRoot = resolvePath(context, sessionId, root)
                ?: throw IllegalArgumentException("searchRoot is not visible from this session: $root")
            discover(hostRoot).firstOrNull()
                ?: throw IllegalArgumentException("no APK under real Gradle build/outputs/apk directories below $root")
        }
        if (!file.isFile || !file.name.endsWith(".apk", true)) {
            throw IllegalArgumentException("artifact is not a readable APK file: ${file.absolutePath}")
        }
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalArgumentException("PackageManager could not parse APK metadata: ${file.absolutePath}")
        archive.applicationInfo?.sourceDir = file.absolutePath
        archive.applicationInfo?.publicSourceDir = file.absolutePath
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION") archive.signatures?.toList().orEmpty()
        }
        val signing = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        val linuxPath = linuxPathFor(context, sessionId, file)
        return ApkArtifact(
            hostPath = file.canonicalPath,
            linuxPath = linuxPath,
            packageName = archive.packageName,
            versionName = archive.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else {
                @Suppress("DEPRECATION") archive.versionCode.toLong()
            },
            debuggable = ((archive.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
            signingSha256 = signing,
            candidateActivities = archive.activities.orEmpty().filter { it.enabled }.map { it.name }.distinct(),
            source = metadataSource(file),
        )
    }

    /** Newest artifacts first; only real Gradle output directories are considered. */
    fun discover(projectRoot: File): List<File> {
        if (!projectRoot.isDirectory) return emptyList()
        val outputRoots = mutableListOf<File>()
        data class Pending(val file: File, val depth: Int)
        val queue = ArrayDeque<Pending>()
        queue.add(Pending(projectRoot.canonicalFile, 0))
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_DISCOVERY_DIRECTORIES) {
            val (dir, depth) = queue.removeFirst()
            visited += 1
            if (dir.path.replace('\\', '/').endsWith("/build/outputs/apk")) {
                outputRoots += dir
                continue
            }
            if (depth >= MAX_DISCOVERY_DEPTH) continue
            dir.listFiles()?.filter(File::isDirectory)?.forEach { child ->
                if (child.name !in setOf(".git", ".gradle", "node_modules", "build") || child.name == "build") {
                    // A build directory is useful only for its outputs/apk branch.
                    if (child.name == "build") {
                        val apk = File(child, "outputs/apk")
                        if (apk.isDirectory) outputRoots += apk
                    } else queue.add(Pending(child, depth + 1))
                }
            }
        }
        return outputRoots.distinctBy { it.canonicalPath }
            .flatMap { root -> root.walkTopDown().maxDepth(5).filter { it.isFile && it.extension.equals("apk", true) }.toList() }
            .distinctBy { it.canonicalPath }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.absolutePath })
    }

    fun resolvePath(context: Context, sessionId: String, path: String): File? {
        if (!path.startsWith('/')) return null
        val candidate = when {
            path.startsWith("/var/minis/") -> PRootKernel.resolveSessionHostPath(sessionId, path, context)
            else -> PRootKernel.resolveHostPath(path)?.takeIf(File::exists) ?: File(path)
        } ?: return null
        return runCatching { candidate.canonicalFile }.getOrNull()
    }

    fun stageForInstaller(context: Context, artifact: ApkArtifact): File {
        if (artifact.packageName == BuildConfig.APPLICATION_ID) {
            throw UnsupportedOperationException(
                "UNSUPPORTED: installing OpenMinis over itself kills the current Agent process; use a future Debug Companion",
            )
        }
        val source = File(artifact.hostPath)
        val dir = File(context.externalCacheDir ?: context.cacheDir, "android-deploy").apply { mkdirs() }
        val target = File(dir, "${artifact.packageName}-${artifact.versionCode}-${source.lastModified()}.apk")
        if (!target.isFile || target.length() != source.length()) source.copyTo(target, overwrite = true)
        target.setReadable(true, false)
        return target
    }

    private fun metadataSource(file: File): String {
        val metadata = File(file.parentFile, "output-metadata.json")
        if (!metadata.isFile) return "apk-archive"
        return runCatching {
            val root = JSONObject(metadata.readText())
            val elements = root.optJSONArray("elements") ?: JSONArray()
            val listed = (0 until elements.length()).any { index ->
                elements.optJSONObject(index)?.optString("outputFile") == file.name
            }
            if (listed) "gradle-output-metadata" else "apk-archive"
        }.getOrDefault("apk-archive")
    }

    private fun linuxPathFor(context: Context, sessionId: String, file: File): String? {
        val bases = listOf("workspace", "attachments", "offloads", "browser")
        for (base in bases) {
            val host = File(context.filesDir, "minis-sessions/$sessionId/$base")
            val relative = runCatching { file.canonicalFile.relativeTo(host.canonicalFile).path }.getOrNull() ?: continue
            if (!relative.startsWith("..")) return "/var/minis/$base/${relative.replace(File.separatorChar, '/')}"
        }
        return null
    }
}
