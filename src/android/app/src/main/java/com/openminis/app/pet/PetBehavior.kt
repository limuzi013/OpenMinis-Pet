package com.openminis.app.pet

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Autonomous movement for the floating pet: idle wandering, edge snapping and
 * tucking itself away when it has been ignored for a while.
 *
 * Position is driven here rather than in the service so that all the timers
 * that can move the pet live in one place — otherwise a wander step and a
 * snap-back can each believe they own `lp.x` and fight over it mid-animation.
 *
 * Every timer is cancelled through [stop]; the service calls that on both
 * hide and destroy, so no callback can outlive the window it moves.
 */
internal class PetBehavior(
    private val position: () -> Pair<Int, Int>,
    private val size: () -> Pair<Int, Int>,
    private val bounds: () -> Rect,
    private val moveTo: (x: Int, y: Int) -> Unit,
    private val setState: (PetState) -> Unit,
    private val restingState: () -> PetState,
    private val isAgentBusy: () -> Boolean,
    private val onTuckChanged: (tucked: Boolean) -> Unit,
) {
    companion object {
        private const val FRAME_MS = 16L

        /** Quiet time before the pet starts strolling on its own. */
        private const val WANDER_DELAY_MIN_MS = 7_000L
        private const val WANDER_DELAY_MAX_MS = 18_000L

        /** Quiet time before it slides off to the screen edge. */
        private const val TUCK_DELAY_MS = 30_000L

        /** Fraction of the pet still visible once tucked. */
        private const val TUCK_VISIBLE_FRACTION = 0.28f

        private const val WANDER_SPEED_DP_PER_SEC = 70f
        private const val RETURN_SPEED_DP_PER_SEC = 900f
        private const val SNAP_SPEED_DP_PER_SEC = 1_400f
    }

    private val main = Handler(Looper.getMainLooper())

    private var density = 3f
    private var animation: Runnable? = null
    private var wanderTimer: Runnable? = null
    private var tuckTimer: Runnable? = null

    var isTucked: Boolean = false
        private set

    var wanderEnabled: Boolean = true
    var edgeSnapEnabled: Boolean = true
    var autoHideEnabled: Boolean = true

    fun setDensity(value: Float) {
        density = value.coerceAtLeast(1f)
    }

    /** Called on attach and after any deliberate interaction. */
    fun onInteraction() {
        cancelAnimation()
        rescheduleIdleTimers()
    }

    /** Drag finished: optionally snap to the nearest side, then resume timers. */
    fun onDragReleased(onSettled: (x: Int, y: Int) -> Unit) {
        cancelAnimation()
        if (!edgeSnapEnabled) {
            val (x, y) = position()
            onSettled(x, y)
            rescheduleIdleTimers()
            return
        }
        val (x, y) = position()
        val (w, _) = size()
        val area = bounds()
        val targetX = PetOverlayGeometry.nearestHorizontalEdge(x, w, area.width())
        animateTo(targetX, y, SNAP_SPEED_DP_PER_SEC) {
            val (fx, fy) = position()
            onSettled(fx, fy)
            rescheduleIdleTimers()
        }
    }

    /** Slide back into view if tucked; returns true when it actually did. */
    fun wakeIfTucked(): Boolean {
        if (!isTucked) return false
        cancelAnimation()
        val (x, y) = position()
        val (w, _) = size()
        val area = bounds()
        val targetX = PetOverlayGeometry.nearestHorizontalEdge(x, w, area.width())
        isTucked = false
        onTuckChanged(false)
        animateTo(targetX, y, RETURN_SPEED_DP_PER_SEC) { rescheduleIdleTimers() }
        return true
    }

    /** Tuck on demand (menu action), regardless of the auto-hide setting. */
    fun tuckNow() {
        if (isTucked) return
        cancelAnimation()
        tuck(force = true)
    }

    fun stop() {
        cancelAnimation()
        wanderTimer?.let(main::removeCallbacks)
        tuckTimer?.let(main::removeCallbacks)
        wanderTimer = null
        tuckTimer = null
    }

    /**
     * Screen-off pause. Same as [stop] but paired with [resumeTimers], which
     * unlike [reset] keeps [isTucked] — a pet hidden at the edge should still
     * be hidden when the screen comes back, not pop out on its own.
     */
    fun pauseTimers() = stop()

    fun resumeTimers() {
        if (!isTucked) rescheduleIdleTimers()
    }

    fun reset() {
        stop()
        isTucked = false
        rescheduleIdleTimers()
    }

    private fun rescheduleIdleTimers() {
        wanderTimer?.let(main::removeCallbacks)
        tuckTimer?.let(main::removeCallbacks)

        if (wanderEnabled) {
            wanderTimer = Runnable { stepWander() }.also {
                main.postDelayed(it, Random.nextLong(WANDER_DELAY_MIN_MS, WANDER_DELAY_MAX_MS))
            }
        }
        if (autoHideEnabled) {
            tuckTimer = Runnable { tuck() }.also { main.postDelayed(it, TUCK_DELAY_MS) }
        }
    }

    /**
     * One stroll. Deliberately skipped while the agent is working so the
     * running/waiting animation the user is watching is not replaced by a walk
     * cycle halfway through a task.
     */
    private fun stepWander() {
        if (isTucked || isAgentBusy()) {
            rescheduleIdleTimers()
            return
        }
        val (x, y) = position()
        val (w, _) = size()
        val area = bounds()
        val maxX = (area.width() - w).coerceAtLeast(0)
        if (maxX <= 0) {
            rescheduleIdleTimers()
            return
        }

        // Walk somewhere meaningfully far away, otherwise the animation reads
        // as a twitch rather than a stroll.
        val span = (maxX * 0.55f).roundToInt().coerceAtLeast(dp(60))
        val target = (x + Random.nextInt(-span, span + 1)).coerceIn(0, maxX)
        if (abs(target - x) < dp(24)) {
            rescheduleIdleTimers()
            return
        }

        setState(if (target > x) PetState.RUNNING_RIGHT else PetState.RUNNING_LEFT)
        animateTo(target, y, WANDER_SPEED_DP_PER_SEC) {
            setState(restingState())
            rescheduleIdleTimers()
        }
    }

    /**
     * Slide most of the way off the nearest edge so content stays readable.
     *
     * @param force skip the busy check — a menu action is an explicit request,
     *   unlike the idle timer which must not hide a working status indicator.
     */
    private fun tuck(force: Boolean = false) {
        if (isTucked || (!force && isAgentBusy())) {
            if (!force) rescheduleIdleTimers()
            return
        }
        val (x, y) = position()
        val (w, _) = size()
        val area = bounds()
        val visible = (w * TUCK_VISIBLE_FRACTION).roundToInt().coerceAtLeast(dp(12))
        val toLeft = x + w / 2 <= area.width() / 2
        val targetX = if (toLeft) -(w - visible) else area.width() - visible

        isTucked = true
        onTuckChanged(true)
        setState(restingState())
        animateTo(targetX, y, SNAP_SPEED_DP_PER_SEC) { }
    }

    private fun animateTo(targetX: Int, targetY: Int, speedDpPerSec: Float, onEnd: () -> Unit) {
        cancelAnimation()
        val (startX, startY) = position()
        val dx = targetX - startX
        val dy = targetY - startY
        if (dx == 0 && dy == 0) {
            onEnd()
            return
        }

        val distance = kotlin.math.hypot(dx.toFloat(), dy.toFloat())
        val durationMs = ((distance / (speedDpPerSec * density)) * 1000f).toLong().coerceIn(120L, 9_000L)
        val startedAt = android.os.SystemClock.uptimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
                val raw = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                // Ease in/out so a stroll starts and stops gently instead of
                // snapping to full speed on the first frame.
                val t = raw * raw * (3f - 2f * raw)
                moveTo((startX + dx * t).roundToInt(), (startY + dy * t).roundToInt())
                if (raw >= 1f) {
                    animation = null
                    onEnd()
                } else {
                    main.postDelayed(this, FRAME_MS)
                }
            }
        }
        animation = runnable
        main.post(runnable)
    }

    private fun cancelAnimation() {
        animation?.let(main::removeCallbacks)
        animation = null
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
