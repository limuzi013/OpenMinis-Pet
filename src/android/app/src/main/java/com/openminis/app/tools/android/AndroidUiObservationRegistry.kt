package com.openminis.app.tools.android

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.ArrayDeque

/** Compact observation filters exposed by `android_ui observe`. */
data class UiObserveOptions(
    val interactiveOnly: Boolean = true,
    val maxDepth: Int = 12,
    val maxNodes: Int = 120,
    val textFilter: String? = null,
    val resourceIdFilter: String? = null,
    val packageFilter: String? = null,
)

data class UiLocator(
    val generation: Long,
    val ref: String,
    val rootIndex: Int,
    val childPath: List<Int>,
    val packageName: String,
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: Rect,
)

sealed class UiRefResolution {
    data class Found(val service: MinisAccessibilityService, val node: AccessibilityNodeInfo, val locator: UiLocator) : UiRefResolution()
    data class Error(val code: String, val message: String) : UiRefResolution()
}

/**
 * Short-lived semantic refs bound to a complete UI fingerprint. No
 * AccessibilityNodeInfo survives an observation call.
 */
object AndroidUiObservationRegistry {
    private const val MAX_OBSERVATIONS = 4
    private const val OBSERVATION_TTL_MS = 30_000L
    private const val FINGERPRINT_NODE_LIMIT = 2_000
    private val generationFence = UiGenerationFence(
        maxEntries = MAX_OBSERVATIONS,
        ttlMs = OBSERVATION_TTL_MS,
    )

    private data class Observation(
        val generation: Long,
        val createdAt: Long,
        val fingerprint: String,
        val locators: Map<String, UiLocator>,
    )

    private val observations = LinkedHashMap<Long, Observation>()

    @Synchronized
    fun observe(service: MinisAccessibilityService, options: UiObserveOptions): JSONObject {
        val roots = service.rootNodes()
        val generation = generationFence.nextGeneration()
        val fingerprint = fingerprint(roots)
        val nodes = JSONArray()
        val locators = LinkedHashMap<String, UiLocator>()
        var refCounter = 0
        var truncated = false

        fun walk(node: AccessibilityNodeInfo?, rootIndex: Int, path: List<Int>, depth: Int, parentRef: String?) {
            if (node == null || depth > options.maxDepth || nodes.length() >= options.maxNodes) {
                if (node != null && (depth > options.maxDepth || nodes.length() >= options.maxNodes)) truncated = true
                return
            }
            val packageName = node.packageName?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val resourceId = node.viewIdResourceName.orEmpty()
            val className = node.className?.toString().orEmpty()
            val interactive = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable ||
                node.isCheckable || node.isFocusable || node.actionList.isNotEmpty()
            val textNeedle = options.textFilter?.trim()?.lowercase().orEmpty()
            val idNeedle = options.resourceIdFilter?.trim()?.lowercase().orEmpty()
            val packageNeedle = options.packageFilter?.trim()?.lowercase().orEmpty()
            val matches = (!options.interactiveOnly || interactive) &&
                (textNeedle.isEmpty() || text.lowercase().contains(textNeedle) || description.lowercase().contains(textNeedle)) &&
                (idNeedle.isEmpty() || resourceId.lowercase().contains(idNeedle)) &&
                (packageNeedle.isEmpty() || packageName.lowercase().contains(packageNeedle))
            var emittedRef: String? = null
            if (matches && node.isVisibleToUser && nodes.length() < options.maxNodes) {
                refCounter += 1
                val ref = "u$refCounter"
                emittedRef = ref
                val bounds = Rect().also(node::getBoundsInScreen)
                val locator = UiLocator(
                    generation = generation,
                    ref = ref,
                    rootIndex = rootIndex,
                    childPath = path,
                    packageName = packageName,
                    className = className,
                    text = text,
                    contentDescription = description,
                    resourceId = resourceId,
                    bounds = Rect(bounds),
                )
                locators[ref] = locator
                nodes.put(nodeJson(node, locator, depth, parentRef))
            }
            val nextParent = emittedRef ?: parentRef
            for (index in 0 until node.childCount) {
                if (nodes.length() >= options.maxNodes) {
                    truncated = true
                    break
                }
                walk(node.getChild(index), rootIndex, path + index, depth + 1, nextParent)
            }
        }

        roots.forEachIndexed { index, root -> walk(root, index, emptyList(), 0, null) }
        observations[generation] = Observation(
            generation = generation,
            createdAt = System.currentTimeMillis(),
            fingerprint = fingerprint,
            locators = locators,
        )
        generationFence.install(generation, fingerprint, locators.keys)
        trimLocked()
        val (pkg, window) = service.foregroundPackage()
        return JSONObject().apply {
            put("generation", generation)
            put("package", pkg ?: "")
            put("activity", window ?: "")
            put("window", window ?: "")
            put("nodeCount", nodes.length())
            put("truncated", truncated)
            put("nodes", nodes)
            put("expiresInMs", OBSERVATION_TTL_MS)
        }
    }

    @Synchronized
    fun resolve(generation: Long, ref: String): UiRefResolution {
        val observation = observations[generation]
            ?: return UiRefResolution.Error("STALE_UI_REF", "generation $generation is no longer retained; run android_ui observe again")
        val locator = observation.locators[ref]
            ?: return UiRefResolution.Error("UI_REF_NOT_FOUND", "ref $ref does not belong to generation $generation")
        val service = MinisAccessibilityService.getInstance()
            ?: return UiRefResolution.Error("ACCESSIBILITY_NOT_CONNECTED", "MinisAccessibilityService is not connected")
        val roots = service.rootNodes()
        when (generationFence.validate(generation, ref, fingerprint(roots))) {
            UiGenerationFence.Verdict.STALE -> return UiRefResolution.Error(
                "STALE_UI_REF", "the window changed or generation $generation expired; run android_ui observe again",
            )
            UiGenerationFence.Verdict.REF_NOT_FOUND -> return UiRefResolution.Error(
                "UI_REF_NOT_FOUND", "ref $ref does not belong to generation $generation",
            )
            UiGenerationFence.Verdict.VALID -> Unit
        }
        var node: AccessibilityNodeInfo? = roots.getOrNull(locator.rootIndex)
        for (index in locator.childPath) node = node?.getChild(index)
        if (node == null || !matches(locator, node)) {
            return UiRefResolution.Error("STALE_UI_REF", "ref $ref no longer resolves to the observed semantic node")
        }
        return UiRefResolution.Found(service, node, locator)
    }

    @Synchronized
    internal fun clearForTests() {
        observations.clear()
        generationFence.clear()
    }

    private fun matches(locator: UiLocator, node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        return locator.packageName == node.packageName?.toString().orEmpty() &&
            locator.className == node.className?.toString().orEmpty() &&
            locator.text == node.text?.toString().orEmpty() &&
            locator.contentDescription == node.contentDescription?.toString().orEmpty() &&
            locator.resourceId == node.viewIdResourceName.orEmpty() &&
            locator.bounds == bounds
    }

    private fun nodeJson(node: AccessibilityNodeInfo, locator: UiLocator, depth: Int, parentRef: String?): JSONObject {
        val bounds = locator.bounds
        return JSONObject().apply {
            put("ref", locator.ref)
            parentRef?.let { put("parentRef", it) }
            put("depth", depth)
            if (locator.text.isNotEmpty()) put("text", locator.text)
            if (locator.contentDescription.isNotEmpty()) put("contentDescription", locator.contentDescription)
            if (locator.resourceId.isNotEmpty()) put("viewIdResourceName", locator.resourceId)
            if (locator.className.isNotEmpty()) put("className", locator.className)
            put("bounds", JSONObject()
                .put("left", bounds.left).put("top", bounds.top)
                .put("right", bounds.right).put("bottom", bounds.bottom))
            put("clickable", node.isClickable)
            put("longClickable", node.isLongClickable)
            put("editable", node.isEditable)
            put("scrollable", node.isScrollable)
            put("enabled", node.isEnabled)
            put("selected", node.isSelected)
            put("checked", node.isChecked)
            put("focused", node.isFocused)
            put("supportedActions", JSONArray(actionNames(node)))
        }
    }

    private fun actionNames(node: AccessibilityNodeInfo): List<String> = node.actionList.map { action ->
        when (action.id) {
            AccessibilityNodeInfo.ACTION_CLICK -> "click"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
            AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear_focus"
            else -> action.label?.toString()?.takeIf(String::isNotBlank) ?: "action_${action.id}"
        }
    }.distinct()

    private fun fingerprint(roots: List<AccessibilityNodeInfo>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach(queue::addLast)
        while (queue.isNotEmpty() && count < FINGERPRINT_NODE_LIMIT) {
            val node = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            val line = buildString {
                append(node.windowId).append('|')
                append(node.packageName).append('|').append(node.className).append('|')
                append(node.viewIdResourceName).append('|').append(node.text).append('|')
                append(node.contentDescription).append('|').append(bounds.flattenToString()).append('|')
                append(node.childCount).append(';')
            }
            digest.update(line.toByteArray(Charsets.UTF_8))
            count += 1
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        digest.update("count=$count,roots=${roots.size}".toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun trimLocked() {
        val now = System.currentTimeMillis()
        observations.entries.removeAll { now - it.value.createdAt > OBSERVATION_TTL_MS }
        while (observations.size > MAX_OBSERVATIONS) observations.remove(observations.keys.first())
    }
}
