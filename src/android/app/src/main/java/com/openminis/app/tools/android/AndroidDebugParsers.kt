package com.openminis.app.tools.android

import org.json.JSONArray
import org.json.JSONObject

/** One parsed process table row. */
data class AndroidProcessRow(val pid: Int, val name: String)

/** Pure parser helpers shared by app, log, and diagnose tools. */
object AndroidDebugParsers {
    private val processLine = Regex("""^\s*(\d+)\s+(.+?)\s*$""")
    private val epochLog = Regex("""^\s*(\d{9,}(?:\.\d{1,9})?)\s+(\d+)\s+(\d+)\s+([VDIWEFAS])\s+([^:]+):\s?(.*)$""")
    private val threadLog = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFAS])\s+([^:]+):\s?(.*)$""")
    private val sourceFrame = Regex("""\bat\s+[^\s(]+\(([^():]+):(\d+)\)""")
    private val pssLine = Regex("""(?m)^\s*TOTAL(?:\s+PSS)?\s*:\s*(\d+)""")
    private val tableTotal = Regex("""(?m)^\s*TOTAL\s+(\d+)\s+(\d+).*""")

    fun parseProcesses(output: String): List<AndroidProcessRow> = output.lineSequence()
        .map(String::trimEnd)
        .filter { it.isNotBlank() && !it.trimStart().startsWith("PID") }
        .mapNotNull { line ->
            processLine.matchEntire(line)?.let { match ->
                match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
                    ?.let { AndroidProcessRow(it, match.groupValues[2].trim()) }
            }
        }.toList()

    data class LogLine(
        val raw: String,
        val timestamp: String?,
        val epochMillis: Long?,
        val pid: Int?,
        val tid: Int?,
        val priority: String?,
        val tag: String?,
        val message: String,
    )

    fun parseLogLine(raw: String): LogLine {
        epochLog.matchEntire(raw)?.let { match ->
            val seconds = match.groupValues[1].toDoubleOrNull()
            return LogLine(
                raw = raw,
                timestamp = match.groupValues[1],
                epochMillis = seconds?.times(1000.0)?.toLong(),
                pid = match.groupValues[2].toIntOrNull(),
                tid = match.groupValues[3].toIntOrNull(),
                priority = match.groupValues[4],
                tag = match.groupValues[5].trim(),
                message = match.groupValues[6],
            )
        }
        threadLog.matchEntire(raw)?.let { match ->
            return LogLine(
                raw = raw,
                timestamp = match.groupValues[1],
                epochMillis = null,
                pid = match.groupValues[2].toIntOrNull(),
                tid = match.groupValues[3].toIntOrNull(),
                priority = match.groupValues[4],
                tag = match.groupValues[5].trim(),
                message = match.groupValues[6],
            )
        }
        return LogLine(raw, null, null, null, null, null, null, raw)
    }

    /** Token-efficient summary; important tail remains bounded. */
    fun summarizeLogs(raw: String, maxImportantLines: Int = 120): JSONObject {
        val parsed = raw.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("--------- beginning of") }
            .map(::parseLogLine).toList()
        val severity = linkedMapOf("V" to 0, "D" to 0, "I" to 0, "W" to 0, "E" to 0, "F" to 0)
        parsed.forEach { line -> line.priority?.let { if (it in severity) severity[it] = severity.getValue(it) + 1 } }
        val important = parsed.filter { line ->
            line.priority in setOf("W", "E", "F", "A") ||
                Regex("FATAL EXCEPTION|AndroidRuntime|ANR in |Fatal signal|tombstone|Exception|Caused by:", RegexOption.IGNORE_CASE)
                    .containsMatchIn(line.raw)
        }.takeLast(maxImportantLines)
        val first = parsed.firstOrNull()
        val last = parsed.lastOrNull()
        return JSONObject().apply {
            put("lineCount", parsed.size)
            put("timeRange", JSONObject()
                .put("start", first?.timestamp ?: JSONObject.NULL)
                .put("end", last?.timestamp ?: JSONObject.NULL))
            put("severityCounts", JSONObject().apply { severity.forEach(::put) })
            put("importantLines", JSONArray(important.map { it.raw }))
            put("crashCandidates", JSONArray(important.filter {
                Regex("FATAL EXCEPTION|Fatal signal|ANR in |AndroidRuntime", RegexOption.IGNORE_CASE).containsMatchIn(it.raw)
            }.takeLast(20).map { it.raw }))
        }
    }

    /** Parse the stable portions of `dumpsys meminfo`. */
    fun parseMeminfo(output: String): JSONObject? {
        val totalPss = pssLine.find(output)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: tableTotal.find(output)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return null
        fun summary(label: String): Long? = Regex("(?m)^\\s*${Regex.escape(label)}:\\s*(\\d+)")
            .find(output)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val pid = Regex("(?m)^\\s*MEMINFO in pid (\\d+)").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return JSONObject().apply {
            put("pid", pid ?: JSONObject.NULL)
            put("totalPssKb", totalPss)
            summary("Java Heap")?.let { put("javaHeapKb", it) }
            summary("Native Heap")?.let { put("nativeHeapKb", it) }
            summary("Code")?.let { put("codeKb", it) }
            summary("Stack")?.let { put("stackKb", it) }
            summary("Graphics")?.let { put("graphicsKb", it) }
            summary("TOTAL RSS")?.let { put("totalRssKb", it) }
            summary("TOTAL SWAP PSS")?.let { put("totalSwapPssKb", it) }
        }
    }

    /** Parse package facts from `dumpsys package`; absent package returns null. */
    fun parsePackageDump(output: String, packageName: String): JSONObject? {
        if (output.isBlank() || output.contains("Unable to find package", true) ||
            !output.contains("Package [$packageName]") && !output.contains("$packageName/")) return null
        fun value(name: String): String? = Regex("(?m)^\\s*${Regex.escape(name)}=([^\\s]+)")
            .find(output)?.groupValues?.getOrNull(1)
        return JSONObject().apply {
            put("packageName", packageName)
            put("installed", true)
            value("versionName")?.let { put("versionName", it) }
            value("versionCode")?.substringBefore(' ')?.toLongOrNull()?.let { put("versionCode", it) }
            value("minSdk")?.toIntOrNull()?.let { put("minSdk", it) }
            value("targetSdk")?.toIntOrNull()?.let { put("targetSdk", it) }
            value("codePath")?.let { put("apkPath", it) }
            value("dataDir")?.let { put("dataDir", it) }
            put("debuggable", Regex("\\bDEBUGGABLE\\b").containsMatchIn(output))
        }
    }

    /** Parse the newest Java/native crash block without inventing source lines. */
    fun parseCrash(raw: String, packageName: String? = null): JSONObject? {
        val lines = raw.lines()
        val fatalIndexes = lines.indices.filter { index ->
            val line = lines[index]
            line.contains("FATAL EXCEPTION:") || line.contains("Fatal signal ") || line.contains("ANR in ")
        }
        val start = fatalIndexes.lastOrNull() ?: return null
        val block = lines.subList(start, minOf(lines.size, start + 180))
        val processLine = block.firstOrNull { it.contains("Process:") }
        val process = processLine?.let { Regex("""Process:\s*([^,]+)""").find(it)?.groupValues?.getOrNull(1)?.trim() }
        val thread = block.firstOrNull { it.contains("FATAL EXCEPTION:") }
            ?.substringAfter("FATAL EXCEPTION:")?.trim()
        val exceptionLine = block.firstOrNull { line ->
            Regex("""(?:^|:\s)(?:[a-zA-Z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?::|$)""").containsMatchIn(line) &&
                !line.contains("FATAL EXCEPTION") && !line.trimStart().startsWith("at ")
        }?.substringAfter(": ", missingDelimiterValue = "")?.ifBlank { null }
        val source = block.firstNotNullOfOrNull { line ->
            val frame = sourceFrame.find(line) ?: return@firstNotNullOfOrNull null
            val file = frame.groupValues[1]
            val lineNumber = frame.groupValues[2].toIntOrNull() ?: return@firstNotNullOfOrNull null
            if (packageName != null && !line.contains(packageName) && !line.contains("$file:")) return@firstNotNullOfOrNull null
            JSONObject().put("file", file).put("line", lineNumber).put("frame", line.trim())
        }
        val exceptionType = block.firstNotNullOfOrNull { line ->
            Regex("""((?:[a-zA-Z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error|Throwable))(?::\s*(.*))?""")
                .find(line)?.let { it.groupValues[1] to it.groupValues.getOrElse(2) { "" } }
        }
        return JSONObject().apply {
            put("kind", when {
                block.first().contains("ANR in ") -> "anr"
                block.first().contains("Fatal signal ") -> "native"
                else -> "java"
            })
            process?.let { put("process", it) }
            thread?.let { put("thread", it) }
            exceptionType?.first?.let { put("exception", it) }
            exceptionType?.second?.takeIf(String::isNotBlank)?.let { put("message", it) }
            exceptionLine?.let { if (!has("message")) put("message", it) }
            source?.let { put("source", it) }
            put("stack", JSONArray(block.filter { it.trimStart().startsWith("at ") || it.contains("Caused by:") }.take(80)))
        }
    }
}
