package com.openminis.app.tools.android

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.data.ContextOffload
import com.openminis.app.offload.OffloadPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Direct model-tool projection over the existing AccessibilityService. */
object AndroidUiController {
    data class UiToolResult(
        val json: JSONObject,
        val success: Boolean,
        val imageData: ByteArray? = null,
        val imageLinuxPath: String? = null,
        val imageHostPath: String? = null,
    )

    suspend fun execute(
        context: Context,
        sessionId: String,
        args: JSONObject,
        toolId: String,
    ): UiToolResult {
        val action = args.optString("action", "observe")
        val allowed = OffloadPermissionManager.checkPermission(
            "a11y_cli",
            "android_ui $action",
            sessionId.ifBlank { OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID },
        )
        if (!allowed) return error(
            "PERMISSION_DENIED",
            "android-a11y-cli integration is disabled; enable it under Settings → Permissions → Integrations",
        )
        val service = MinisAccessibilityService.getInstance()
            ?: return error("ACCESSIBILITY_NOT_CONNECTED", "enable Minis under Android Settings → Accessibility")
        return withContext(Dispatchers.Default) {
            when (action) {
                "observe" -> observe(service, args, sessionId)
                "screenshot" -> screenshot(context, service, sessionId, args, toolId)
                "click" -> click(service, args, longPress = false)
                "long_press" -> click(service, args, longPress = true)
                "set_text" -> setText(context, service, args)
                "scroll" -> scroll(service, args)
                "back" -> global(service, AccessibilityService.GLOBAL_ACTION_BACK, "back")
                "home" -> global(service, AccessibilityService.GLOBAL_ACTION_HOME, "home")
                "wait" -> waitFor(service, args)
                else -> error("INVALID_ACTION", "unknown android_ui action: $action")
            }
        }
    }

    private fun observe(service: MinisAccessibilityService, args: JSONObject, sessionId: String): UiToolResult {
        val result = AndroidUiObservationRegistry.observe(
            service,
            UiObserveOptions(
                interactiveOnly = args.optBoolean("interactiveOnly", true),
                maxDepth = args.optInt("maxDepth", 12).coerceIn(0, 30),
                maxNodes = args.optInt("maxNodes", 120).coerceIn(1, 500),
                textFilter = args.optString("textFilter", "").takeIf(String::isNotBlank),
                resourceIdFilter = args.optString("resourceIdFilter", "").takeIf(String::isNotBlank),
                packageFilter = args.optString("packageFilter", "").takeIf(String::isNotBlank),
            ),
        )
        AndroidDebugSessionStore.update(sessionId) {
            it.copy(lastUiGeneration = result.optLong("generation"), targetPackage = result.optString("package").ifBlank { it.targetPackage })
        }
        return UiToolResult(result.put("status", CapabilityStatus.AVAILABLE.name), true)
    }

    private fun screenshot(
        context: Context,
        service: MinisAccessibilityService,
        sessionId: String,
        args: JSONObject,
        toolId: String,
    ): UiToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return error("SCREENSHOT_UNSUPPORTED_API", "Accessibility screenshots require Android 11 (API 30+)")
        }
        val displayId = args.optInt("displayId", Display.DEFAULT_DISPLAY)
        val scale = args.optDouble("scale", 0.5).toFloat().coerceIn(0.1f, 1f)
        val shot = service.captureScreenshot(displayId)
        val raw = shot.bitmap ?: return error(
            shot.errorCode ?: "SCREENSHOT_FAILED",
            shot.errorMessage ?: "Accessibility takeScreenshot failed (FLAG_SECURE, OEM throttling, or disconnected service)",
        )
        val originalWidth = raw.width
        val originalHeight = raw.height
        val bitmap = if (scale == 1f) raw else {
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== raw) raw.recycle()
            scaled
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        val bytes = output.toByteArray()
        val linuxPath = ContextOffload.offloadImage(context, sessionId, bytes, toolId, "image/png")
        val host = linuxPath.takeIf(String::isNotEmpty)?.let {
            com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(sessionId, it, context)?.absolutePath
        }
        val json = JSONObject()
            .put("status", CapabilityStatus.AVAILABLE.name)
            .put("displayId", displayId)
            .put("originalWidth", originalWidth)
            .put("originalHeight", originalHeight)
            .put("width", width)
            .put("height", height)
            .put("scale", scale.toDouble())
            .put("sizeBytes", bytes.size)
            .put("path", linuxPath.ifEmpty { JSONObject.NULL })
            .put("note", "Screenshot may still be absent for FLAG_SECURE windows or OEM throttling")
        return UiToolResult(json, true, bytes, linuxPath.takeIf(String::isNotEmpty), host)
    }

    private suspend fun click(service: MinisAccessibilityService, args: JSONObject, longPress: Boolean): UiToolResult {
        val generation = args.optLong("generation", -1L)
        val ref = args.optString("ref", "")
        if (generation >= 0L && ref.isNotBlank()) {
            return when (val resolved = AndroidUiObservationRegistry.resolve(generation, ref)) {
                is UiRefResolution.Error -> error(resolved.code, resolved.message)
                is UiRefResolution.Found -> {
                    if (!resolved.node.isEnabled || !resolved.node.isVisibleToUser) {
                        error("UI_NODE_NOT_ACTIONABLE", "ref $ref is disabled or no longer visible")
                    } else {
                        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                        var fallback = false
                        var ok = resolved.node.performAction(action)
                        if (!ok) {
                            val bounds = Rect().also(resolved.node::getBoundsInScreen)
                            val point = Path().apply {
                                moveTo(bounds.exactCenterX(), bounds.exactCenterY())
                                lineTo(bounds.exactCenterX() + 0.1f, bounds.exactCenterY() + 0.1f)
                            }
                            ok = service.dispatchSimpleGesture(point, 0L, if (longPress) 800L else 50L)
                            fallback = true
                        }
                        delay(250L)
                        val (pkg, window) = service.foregroundPackage()
                        UiToolResult(JSONObject()
                            .put("action", if (longPress) "long_press" else "click")
                            .put("generation", generation)
                            .put("ref", ref)
                            .put("success", ok)
                            .put("coordinateFallback", fallback)
                            .put("package", pkg ?: "")
                            .put("window", window ?: "")
                            .put("verification", if (AndroidUiObservationRegistry.resolve(generation, ref) is UiRefResolution.Error) "UI_CHANGED" else "UI_STABLE"), ok)
                    }
                }
            }
        }
        if (!args.has("x") || !args.has("y")) {
            return error("INVALID_ARGS", "click requires generation+ref from observe, or explicit x+y coordinates")
        }
        val x = args.optDouble("x", Double.NaN)
        val y = args.optDouble("y", Double.NaN)
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0) return error("INVALID_ARGS", "x/y must be finite non-negative pixels")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat() + 0.1f, y.toFloat() + 0.1f) }
        val ok = service.dispatchSimpleGesture(path, 0L, if (longPress) 800L else 50L)
        return UiToolResult(JSONObject()
            .put("action", if (longPress) "long_press" else "click")
            .put("x", x).put("y", y).put("success", ok).put("coordinateFallback", true), ok)
    }

    private fun setText(context: Context, service: MinisAccessibilityService, args: JSONObject): UiToolResult {
        val resolved = resolveRef(args)
        if (resolved is UiRefResolution.Error) return error(resolved.code, resolved.message)
        resolved as UiRefResolution.Found
        if (!resolved.node.isEditable || !resolved.node.isEnabled) {
            return error("UI_NODE_NOT_EDITABLE", "the observed ref is not an enabled editable node")
        }
        val text = args.optString("text", "")
        var method = "ACTION_SET_TEXT"
        var ok = service.setNodeText(resolved.node, text)
        if (!ok) {
            // Safe Unicode fallback: focus + ACTION_PASTE, restoring the user's
            // previous clipboard without exposing its content to the model.
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val previous = runCatching { clipboard.primaryClip }.getOrNull()
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Minis Android input", text))
                    resolved.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    ok = resolved.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    method = "CLIPBOARD_ACTION_PASTE"
                } finally {
                    if (previous != null) clipboard.setPrimaryClip(previous)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
        return UiToolResult(JSONObject().put("action", "set_text")
            .put("generation", resolved.locator.generation).put("ref", resolved.locator.ref)
            .put("success", ok).put("inputMethod", method)
            .put("unicode", "ACTION_SET_TEXT/clipboard supports Unicode; shell input is not used"), ok)
    }

    private fun scroll(service: MinisAccessibilityService, args: JSONObject): UiToolResult {
        if (args.optString("ref", "").isNotBlank()) {
            val resolved = resolveRef(args)
            if (resolved is UiRefResolution.Error) return error(resolved.code, resolved.message)
            resolved as UiRefResolution.Found
            val direction = args.optString("direction", "forward")
            val action = if (direction in setOf("backward", "up", "left")) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val ok = resolved.node.performAction(action)
            return UiToolResult(JSONObject().put("action", "scroll").put("ref", resolved.locator.ref)
                .put("generation", resolved.locator.generation).put("direction", direction).put("success", ok), ok)
        }
        val x = args.optDouble("x", Double.NaN)
        val y = args.optDouble("y", Double.NaN)
        val deltaX = args.optDouble("deltaX", 0.0)
        val deltaY = args.optDouble("deltaY", 0.0)
        if (listOf(x, y, deltaX, deltaY).any { !it.isFinite() } || deltaX == 0.0 && deltaY == 0.0) {
            return error("INVALID_ARGS", "coordinate scroll requires finite x/y and a non-zero deltaX or deltaY")
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo((x + deltaX).toFloat(), (y + deltaY).toFloat()) }
        val ok = service.dispatchSimpleGesture(path, 0L, args.optLong("durationMs", 300L).coerceIn(50L, 5_000L))
        return UiToolResult(JSONObject().put("action", "scroll").put("success", ok)
            .put("coordinateFallback", true).put("x", x).put("y", y)
            .put("deltaX", deltaX).put("deltaY", deltaY), ok)
    }

    private fun global(service: MinisAccessibilityService, action: Int, name: String): UiToolResult {
        val ok = service.performGlobalAction(action)
        return UiToolResult(JSONObject().put("action", name).put("success", ok), ok)
    }

    private suspend fun waitFor(service: MinisAccessibilityService, args: JSONObject): UiToolResult {
        val timeout = args.optLong("timeoutMs", 5_000L).coerceIn(0L, 60_000L)
        val text = args.optString("textFilter", "").trim()
        val disappear = args.optString("mode", "appear") == "disappear"
        val started = System.currentTimeMillis()
        if (text.isEmpty()) {
            delay(timeout.coerceAtMost(10_000L))
            return UiToolResult(JSONObject().put("action", "wait").put("waitedMs", System.currentTimeMillis() - started).put("matched", true), true)
        }
        do {
            val present = service.rootNodes().any { containsText(it, text, 0, 30) }
            if (present != disappear) {
                return UiToolResult(JSONObject().put("action", "wait").put("textFilter", text)
                    .put("mode", if (disappear) "disappear" else "appear")
                    .put("matched", true).put("waitedMs", System.currentTimeMillis() - started), true)
            }
            delay(200L)
        } while (System.currentTimeMillis() - started < timeout)
        return UiToolResult(JSONObject().put("action", "wait").put("textFilter", text)
            .put("mode", if (disappear) "disappear" else "appear")
            .put("matched", false).put("timedOut", true).put("waitedMs", System.currentTimeMillis() - started), true)
    }

    private fun containsText(node: AccessibilityNodeInfo?, needle: String, depth: Int, maxDepth: Int): Boolean {
        if (node == null || depth > maxDepth) return false
        if (node.text?.toString()?.contains(needle, true) == true ||
            node.contentDescription?.toString()?.contains(needle, true) == true) return true
        for (index in 0 until node.childCount) if (containsText(node.getChild(index), needle, depth + 1, maxDepth)) return true
        return false
    }

    private fun resolveRef(args: JSONObject): UiRefResolution {
        val generation = args.optLong("generation", -1L)
        val ref = args.optString("ref", "")
        if (generation < 0L || ref.isBlank()) return UiRefResolution.Error(
            "INVALID_ARGS", "generation and ref from the latest observe are required",
        )
        return AndroidUiObservationRegistry.resolve(generation, ref)
    }

    private fun error(code: String, message: String): UiToolResult = UiToolResult(
        JSONObject().put("status", CapabilityStatus.UNAVAILABLE.name)
            .put("success", false).put("error", JSONObject().put("code", code).put("message", message)),
        false,
    )
}
