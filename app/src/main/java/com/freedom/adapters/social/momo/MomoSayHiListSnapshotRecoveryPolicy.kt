package com.freedom.adapters.social.momo

import kotlin.random.Random

/**
 * Serializes received-greetings list snapshots and gestures for the shared recovery transaction.
 *
 * RecyclerView emits content/scroll events while a swipe is running and briefly after Android
 * reports the gesture terminal callback. Those events must not start another accessibility-tree
 * walk during the animation/adapter-layout settle window. This policy is the single gate for
 * both snapshot reads and autonomous list swipes; callers provide monotonic elapsed time.
 */
internal class MomoSayHiListSnapshotRecoveryPolicy(
    private val retryDelayRangesMs: List<LongRange> = listOf(500L..500L, 1_000L..1_000L, 2_000L..2_000L, 5_000L..5_000L, 10_000L..10_000L, 30_000L..30_000L),
    private val postGestureSettleRangeMs: LongRange = 250L..420L,
    private val sampleDelayMs: (LongRange) -> Long = { range ->
        if (range.first == range.last) range.first else Random.nextLong(range.first, range.last + 1L)
    }
) {
    sealed interface IncompleteSnapshotDecision {
        data class Retry(val attempt: Int, val maximumAttempts: Int, val delayMs: Long) : IncompleteSnapshotDecision
        data class Exhausted(val attempts: Int) : IncompleteSnapshotDecision
    }

    sealed interface SnapshotAdmission {
        data object Acquired : SnapshotAdmission
        data object GestureInFlight : SnapshotAdmission
        data object SnapshotInFlight : SnapshotAdmission
        data class Settling(val remainingMs: Long) : SnapshotAdmission
    }

    private var gestureInFlight = false
    private var snapshotReadInFlight = false
    private var quietUntilElapsedMs = 0L
    private var retryWindowKey: String = ""
    private var retryAttempts = 0
    private var exhaustedWindowKey: String? = null

    init {
        require(retryDelayRangesMs.isNotEmpty())
        require(retryDelayRangesMs.all { it.first >= 0L && it.last >= it.first })
        require(postGestureSettleRangeMs.first >= 0L &&
            postGestureSettleRangeMs.last >= postGestureSettleRangeMs.first)
    }

    /** Atomically reserves the one active list snapshot read. */
    @Synchronized
    fun tryBeginSnapshot(nowElapsedMs: Long): SnapshotAdmission {
        if (gestureInFlight) return SnapshotAdmission.GestureInFlight
        if (snapshotReadInFlight) return SnapshotAdmission.SnapshotInFlight
        val remaining = (quietUntilElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        if (remaining > 0L) return SnapshotAdmission.Settling(remaining)
        snapshotReadInFlight = true
        return SnapshotAdmission.Acquired
    }

    @Synchronized
    fun onSnapshotReadFinished() {
        snapshotReadInFlight = false
    }

    /** Reserves a swipe only when no snapshot is being read and the prior swipe has settled. */
    @Synchronized
    fun tryBeginGesture(nowElapsedMs: Long): Boolean {
        if (gestureInFlight || snapshotReadInFlight || nowElapsedMs < quietUntilElapsedMs) return false
        gestureInFlight = true
        return true
    }

    /** Returns a bounded, varied quiet period before the next accessibility snapshot. */
    @Synchronized
    fun onGestureFinished(nowElapsedMs: Long): Long {
        gestureInFlight = false
        val settleDelay = sampleDelayMs(postGestureSettleRangeMs).coerceIn(
            postGestureSettleRangeMs.first,
            postGestureSettleRangeMs.last
        )
        val proposedDeadline = if (Long.MAX_VALUE - nowElapsedMs < settleDelay) {
            Long.MAX_VALUE
        } else {
            nowElapsedMs + settleDelay
        }
        quietUntilElapsedMs = maxOf(quietUntilElapsedMs, proposedDeadline)
        return settleDelay
    }

    /**
     * Releases a gesture reservation when the dispatcher defers before sending any platform
     * gesture (for example, while a popup temporarily blocks the target). No settle window or
     * failed-swipe accounting is added because the viewport has not moved.
     */
    @Synchronized
    fun onGestureDeferred() {
        gestureInFlight = false
    }

    @Synchronized
    fun remainingSettleMs(nowElapsedMs: Long): Long =
        (quietUntilElapsedMs - nowElapsedMs).coerceAtLeast(0L)

    @Synchronized
    fun isGestureInFlight(): Boolean = gestureInFlight

    @Synchronized
    fun isSnapshotReadInFlight(): Boolean = snapshotReadInFlight

    @Synchronized
    fun onSnapshotIncomplete(windowKey: String): IncompleteSnapshotDecision {
        if (windowKey != retryWindowKey) {
            retryWindowKey = windowKey
            retryAttempts = 0
            exhaustedWindowKey = null
        }
        retryAttempts += 1
        val range = retryDelayRangesMs[(retryAttempts - 1).coerceAtMost(retryDelayRangesMs.lastIndex)]
        val delay = sampleDelayMs(range).coerceIn(range.first, range.last)
        return IncompleteSnapshotDecision.Retry(
            attempt = retryAttempts,
            maximumAttempts = Int.MAX_VALUE,
            delayMs = delay
        )
    }

    @Synchronized
    fun onSnapshotComplete(windowKey: String) {
        if (windowKey != retryWindowKey) retryWindowKey = windowKey
        retryAttempts = 0
        exhaustedWindowKey = null
    }

    /** Kept for compatibility; unified recovery no longer exhausts by retry count. */
    @Synchronized
    fun isPausedForWindow(windowKey: String): Boolean = exhaustedWindowKey == windowKey

    @Synchronized
    fun retryAttemptCount(): Int = retryAttempts

    @Synchronized
    fun reset() {
        gestureInFlight = false
        snapshotReadInFlight = false
        quietUntilElapsedMs = 0L
        retryWindowKey = ""
        retryAttempts = 0
        exhaustedWindowKey = null
    }
}
