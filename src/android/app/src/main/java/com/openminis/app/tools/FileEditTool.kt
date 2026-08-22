package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.tools.internal.FileEditEngine
import com.openminis.app.tools.internal.FileMutationQueue
import org.json.JSONArray
import org.json.JSONObject

object FileEditTool {
    const val NAME = "file_edit"
    private const val MAX_DIFF_CHARS = 12_000

    fun definition(): AgentToolDefinition {
        val editItem = AgentToolParam(
            type = "object",
            description = "One targeted replacement. Every old_text is matched against the original file, not incrementally.",
            properties = mapOf(
                "old_text" to AgentToolParam("string", "Exact text to replace. It must identify one unique, non-overlapping region of the original file."),
                "new_text" to AgentToolParam("string", "Replacement text. Use an empty string to delete the matched block."),
            ),
            requiredProperties = listOf("old_text", "new_text"),
        )
        return AgentToolDefinition(
            name = NAME,
            description = "Edit one existing text file with one or more atomic targeted replacements. ALWAYS file_read the relevant region first. All edits are matched against the same original snapshot; overlapping edits are rejected. Matching is exact first, then a conservative fuzzy fallback for Unicode punctuation/special spaces/trailing whitespace. Original BOM and CRLF/LF style are preserved. Prefer this over file_write for modifying existing files.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
                "path" to AgentToolParam("string", "Absolute Linux path to the file to edit (e.g. /var/minis/workspace/app/src/Main.kt)"),
                "edits" to AgentToolParam("array", "One or more non-overlapping targeted replacements. If nearby changes touch the same logical block, merge them into one edit.", items = editItem),
                // Legacy fields remain accepted at runtime so old tool traces / clients do not break.
                "old_string" to AgentToolParam("string", "Legacy single-edit field. Prefer edits[].old_text."),
                "new_string" to AgentToolParam("string", "Legacy single-edit field. Prefer edits[].new_text."),
                "replace_all" to AgentToolParam("boolean", "Legacy compatibility only. When true with old_string/new_string, replaces all exact occurrences."),
            ),
            required = listOf("tool_title", "path", "edits"),
            propertyOrdering = listOf("tool_title", "path", "edits", "old_string", "new_string", "replace_all"),
            timeoutMs = 60_000L,
        )
    }

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // Per-session permission preset (DSH /permission) gate, mirroring FileWriteTool.
            if (!SessionPermissionStore.allowsFileWrite(context, sessionId, path)) {
                return ToolExecutionResult(
                    "Error: session permission preset `workspace-write` only allows writing under " +
                        "/var/minis/workspace (and per-session /var/minis/* dirs); refusing to edit $path.",
                    false, toolTitle = toolTitle,
                )
            }

            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }

            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)
            if (!file.exists()) return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            if (!file.isFile) return ToolExecutionResult("Error: Path is not a regular file: $path", false, toolTitle = toolTitle)

            FileMutationQueue.withFile(file) {
                val content = file.readText()
                val edits = parseEdits(args)
                if (edits.isEmpty()) {
                    return@withFile ToolExecutionResult(
                        "Error: provide a non-empty 'edits' array (or legacy old_string/new_string)",
                        false, toolTitle = toolTitle,
                    )
                }

                // Preserve legacy replace_all semantics separately: Pi-style multi-edit
                // requires unique matches, while old traces may intentionally replace all.
                if (!args.has("edits") && args.optBoolean("replace_all", false)) {
                    val old = args.optString("old_string", "")
                    val new = args.optString("new_string", "")
                    if (old.isEmpty()) return@withFile ToolExecutionResult("Error: old_string cannot be empty", false, toolTitle = toolTitle)
                    // CRLF files: match on the LF-normalized text so old_string
                    // written with \n still finds lines in a \r\n file, then
                    // restore the original line-ending style afterwards.
                    val normalized = FileEditEngine.normalizeLf(content)
                    val normalizedOld = FileEditEngine.normalizeLf(old)
                    val count = Regex.escape(normalizedOld).toRegex().findAll(normalized).count()
                    if (count == 0) return@withFile ToolExecutionResult("Error: old_string not found in $path", false, toolTitle = toolTitle)
                    val updated = normalized.replace(normalizedOld, FileEditEngine.normalizeLf(new))
                    val restored = FileEditEngine.restoreLineEnding(updated, FileEditEngine.detectLineEnding(content))
                    file.writeText(restored)
                    return@withFile ToolExecutionResult("Edited $path ($count replacements, ${file.length()} bytes)", true, toolTitle = toolTitle)
                }

                val result = FileEditEngine.apply(content, edits, path)
                file.writeText(result.newContent)
                val fuzzyNote = if (result.fuzzyMatchCount > 0) ", ${result.fuzzyMatchCount} fuzzy match(es)" else ""
                val lineNote = result.firstChangedLine?.let { ", first changed line $it" }.orEmpty()
                val diff = takeCodePoints(result.diff, MAX_DIFF_CHARS)
                val diffNote = if (result.diff.length > MAX_DIFF_CHARS) "\n[diff truncated to $MAX_DIFF_CHARS chars]" else ""
                ToolExecutionResult(
                    "Edited $path (${result.replacementCount} block(s)$fuzzyNote$lineNote, ${file.length()} bytes)" +
                        if (diff.isNotBlank()) "\n\n$diff$diffNote" else "",
                    true,
                    toolTitle = toolTitle,
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error editing file: ${e.message}", false)
        }
    }

    /**
     * Take at most [max] UTF-16 code units of [text] without splitting a
     * surrogate pair (a raw take() can leave a dangling high surrogate, which
     * then decodes to U+FFFD in the tool result).
     */
    private fun takeCodePoints(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max
        if (Character.isHighSurrogate(text[end - 1]) && end < text.length &&
            Character.isLowSurrogate(text[end])
        ) {
            end -= 1
        }
        return text.substring(0, end)
    }

    private fun parseEdits(args: JSONObject): List<FileEditEngine.Edit> {
        val out = mutableListOf<FileEditEngine.Edit>()
        val array: JSONArray? = when (val raw = args.opt("edits")) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull()
            is JSONObject -> JSONArray().put(raw)
            else -> null
        }
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val oldText = when {
                    item.has("old_text") -> item.optString("old_text", "")
                    else -> item.optString("oldText", "")
                }
                val newText = when {
                    item.has("new_text") -> item.optString("new_text", "")
                    else -> item.optString("newText", "")
                }
                out += FileEditEngine.Edit(oldText, newText)
            }
        }
        if (out.isEmpty() && args.has("old_string") && args.has("new_string")) {
            out += FileEditEngine.Edit(args.optString("old_string", ""), args.optString("new_string", ""))
        }
        return out
    }
}
