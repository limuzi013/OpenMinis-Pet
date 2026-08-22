package com.openminis.app.remote

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure mapping of Android thinking tiers onto the DSH `modelReasoningSchema`
 * (`assets/minis/plugins/@deepseek-ai/dsh-client-connection/client.js`):
 *
 *   reasoning: { efforts: [{ id, name, description? }].min(1), defaultEffort? }
 *
 * Android semantics: a session's thinking level is a persisted per-session
 * override (enum name, `null` = never set = effectively "off"); the composer
 * offers LOW..ceiling (OFF is reached by toggling the active level off). The
 * DSH effort list therefore always starts with `off` so "reasoning disabled"
 * is an explicit, selectable state that round-trips to
 * `chat.session.selectThinkingLevel`.
 *
 * Kept pure so JVM tests can pin the effort mapping without a device.
 */
object DshReasoningCatalog {

    /** Effort id on the DSH wire (lowercase enum name). */
    fun effortId(level: ThinkingLevel): String = level.name.lowercase()

    fun effortId(levelName: String): String = levelName.lowercase()

    fun effortLabel(level: ThinkingLevel): String = level.displayName

    /**
     * All efforts a model can take: OFF first, then LOW..ceiling.
     * Returns an empty list when the model cannot reason at all
     * (supportsReasoning == false).
     */
    fun effortsFor(ceiling: ThinkingLevel, supportsReasoning: Boolean?): List<ThinkingLevel> {
        if (supportsReasoning == false) return emptyList()
        // supportsReasoning == null ("unknown") is treated as reasoning-capable,
        // mirroring LLMModel.catalogMaxThinkingLevel's conservative default.
        return ThinkingLevel.entries.filter { it.rank <= ceiling.rank }
    }

    /** Wire `description` for one effort. */
    fun effortDescription(level: ThinkingLevel, maxCeiling: ThinkingLevel): String? = when {
        level == ThinkingLevel.OFF -> "关闭推理"
        level == maxCeiling -> "该模型支持的最高强度"
        else -> null
    }

    /**
     * Build the DSH `reasoning` block for one model entry, or null when the
     * model cannot reason.
     */
    fun reasoningBlock(supportsReasoning: Boolean?, maxCeiling: ThinkingLevel): JSONObject? {
        val efforts = effortsFor(maxCeiling, supportsReasoning)
        if (efforts.isEmpty()) return null
        val array = JSONArray()
        for (level in efforts) {
            val item = JSONObject()
                .put("id", effortId(level))
                .put("name", effortLabel(level))
            effortDescription(level, maxCeiling)?.let { item.put("description", it) }
            array.put(item)
        }
        return JSONObject().apply {
            put("efforts", array)
            // Android default: no override means reasoning is off for the session.
            put("defaultEffort", effortId(ThinkingLevel.OFF))
        }
    }
}
