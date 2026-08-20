package com.openminis.app.pet

/**
 * Pure placement rules shared by the overlay's first attach and later drags.
 *
 * Keeping this independent of [android.view.WindowManager] makes it possible
 * to regression-test the important case where a saved position was valid for
 * an older (smaller) sprite but is no longer valid after a scale or pack
 * change.
 */
internal object PetOverlayGeometry {

    fun clamp(
        x: Int,
        y: Int,
        contentWidth: Int,
        contentHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Pair<Int, Int> {
        val maxX = (viewportWidth - contentWidth.coerceAtLeast(0)).coerceAtLeast(0)
        val maxY = (viewportHeight - contentHeight.coerceAtLeast(0)).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    fun nearestHorizontalEdge(
        x: Int,
        contentWidth: Int,
        viewportWidth: Int,
    ): Int {
        val maxX = (viewportWidth - contentWidth.coerceAtLeast(0)).coerceAtLeast(0)
        return if (x + contentWidth / 2 <= viewportWidth / 2) 0 else maxX
    }
}

/**
 * The agent foreground service currently exposes a human-readable status
 * string. Keep its interpretation in one pure, testable place so the pet does
 * not accidentally use a personality animation for a real failure or wait.
 */
internal object PetAgentStateResolver {
    fun resolve(sessionCount: Int, toolStatus: String?): PetState {
        val status = toolStatus.orEmpty().lowercase(java.util.Locale.ROOT)
        return when {
            status.contains("fail") || status.contains("error") || status.contains("cancel") ||
                status.contains("失败") || status.contains("出错") || status.contains("取消") -> PetState.FAILED
            status.contains("review") || status.contains("inspect") || status.contains("check") ||
                status.contains("think") || status.contains("审查") || status.contains("检查") ||
                status.contains("思考") -> PetState.REVIEW
            status.contains("wait") || status.contains("queue") || status.contains("pending") ||
                status.contains("等待") || status.contains("排队") -> PetState.WAITING
            sessionCount > 0 -> PetState.RUNNING
            else -> PetState.IDLE
        }
    }
}
