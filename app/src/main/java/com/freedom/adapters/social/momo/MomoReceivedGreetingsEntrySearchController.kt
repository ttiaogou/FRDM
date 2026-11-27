package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import com.freedom.features.recovery.RecoveryScheduler

/**
 * Controls a bounded, two-direction sweep of the ordinary Message list when the received-
 * greetings entry is not present in the current viewport. The run-local inbox observation
 * selects the initial direction only; a missing or stale hint defaults to later rows.
 * Completed gestures provide movement evidence only. A physical edge requires independent,
 * stable live collection/action evidence; failed or stationary swipes alone never prove an edge.
 * Failure recovery uses the existing read-only retry cadence and spaced fresh READY evidence.
 */
internal class MomoReceivedGreetingsEntrySearchController(
    private val unchangedObservationsToConfirmBoundary: Int = 2
) {
    enum class Observation {
        CONTINUE,
        WAIT_FOR_OBSERVATION,
        REVERSED,
        COMPLETE
    }

    private var active = false
    private var direction = VerticalSwipeDirection.UP
    private var reversed = false
    private var unchangedCount = 0
    private var pendingBaseline: String? = null
    private val edgeConfirmation = MomoSayHiListReturnConfirmationPolicy(
        requiredStableSamples = unchangedObservationsToConfirmBoundary
    )
    private val retryConfirmation = MomoSayHiListReturnConfirmationPolicy()
    private var retryAttempt = 0
    private var retryNotBeforeMs = 0L
    private var retryAdmitted = false
    private var complete = false
    private var searchGeneration = 0L
    private data class InFlightGesture(val requestId: String, val generation: Long)
    private var inFlightGesture: InFlightGesture? = null

    init {
        require(unchangedObservationsToConfirmBoundary >= 2)
    }

    @Synchronized
    fun start(initialPosition: MomoReceivedGreetingsEntryPosition = MomoReceivedGreetingsEntryPosition.UNKNOWN): Boolean {
        if (initialPosition == MomoReceivedGreetingsEntryPosition.VISIBLE) {
            // A visible entry must go through the independent fresh-target tap policy.
            reset()
            return false
        }
        searchGeneration++
        active = true
        // UP/DOWN describe finger movement, not list-content direction.
        direction = if (initialPosition == MomoReceivedGreetingsEntryPosition.ABOVE) {
            VerticalSwipeDirection.DOWN
        } else {
            VerticalSwipeDirection.UP
        }
        reversed = false
        unchangedCount = 0
        pendingBaseline = null
        complete = false
        edgeConfirmation.reset()
        clearRetry()
        return true
    }

    @Synchronized
    fun direction(): VerticalSwipeDirection = direction

    @Synchronized
    fun captureSearchGeneration(): Long = searchGeneration

    @Synchronized
    fun isGestureInFlight(): Boolean = inFlightGesture != null

    /** Bind an actual dispatch to the current search; never overlap two search gestures. */
    @Synchronized
    fun tryBeginGesture(expectedGeneration: Long, requestId: String): Boolean {
        if (!active || complete || expectedGeneration != searchGeneration ||
            inFlightGesture != null || requestId.isBlank() ||
            (retryAttempt > 0 && !retryAdmitted)
        ) return false
        retryAdmitted = false
        retryConfirmation.reset()
        edgeConfirmation.reset()
        inFlightGesture = InFlightGesture(requestId, searchGeneration)
        return true
    }

    /**
     * A matching terminal result alone may release this request's busy marker. A reset
     * invalidates its viewport evidence but retains physical in-flight ownership until
     * the terminal result. Unrelated/duplicate results cannot release a newer request.
     * A cancelled/deferred gesture never counts as boundary evidence.
     */
    @Synchronized
    fun finishGesture(requestId: String, completedBaseline: String?, nowMs: Long): Boolean {
        val pending = inFlightGesture ?: return false
        if (pending.requestId != requestId) return false
        inFlightGesture = null
        if (pending.generation != searchGeneration || !active || complete) return false
        pendingBaseline = completedBaseline?.takeIf { it.isNotBlank() }
        if (pendingBaseline == null) {
            unchangedCount = 0
            edgeConfirmation.reset()
            holdForObservation(nowMs)
        }
        return true
    }

    /** A missing or unready observation breaks stability, not the sweep direction. */
    @Synchronized
    fun invalidateObservation() {
        pendingBaseline = null
        unchangedCount = 0
        edgeConfirmation.reset()
        retryConfirmation.reset()
        retryAdmitted = false
    }

    /** READY evidence is freshly evaluated by the existing runtime owner; never a write grant. */
    @Synchronized
    fun mayDispatch(readyViewportKey: String?, nowMs: Long): Boolean {
        if (!active || complete || inFlightGesture != null || readyViewportKey.isNullOrBlank()) {
            retryConfirmation.reset()
            retryAdmitted = false
            return false
        }
        if (retryAttempt == 0) return true
        if (nowMs < retryNotBeforeMs) {
            retryConfirmation.reset()
            retryAdmitted = false
            return false
        }
        retryAdmitted = retryConfirmation.observe(readyViewportKey, nowMs)
        return retryAdmitted
    }

    @Synchronized
    fun retryAttempt(): Int = retryAttempt

    @Synchronized
    fun observeAfterGesture(
        currentFingerprint: String,
        boundaryEvidence: Boolean?,
        nowMs: Long
    ): Observation {
        if (!active || complete) return Observation.CONTINUE
        if (inFlightGesture != null) return Observation.WAIT_FOR_OBSERVATION
        if (currentFingerprint.isBlank()) {
            invalidateObservation()
            return Observation.WAIT_FOR_OBSERVATION
        }
        val baseline = pendingBaseline
        pendingBaseline = null
        if (baseline != null) {
            if (currentFingerprint != baseline) {
                unchangedCount = 0
                clearRetry()
            } else if (boundaryEvidence != true) {
                unchangedCount++
                if (unchangedCount >= unchangedObservationsToConfirmBoundary) {
                    unchangedCount = 0
                    holdForObservation(nowMs)
                    edgeConfirmation.reset()
                    return Observation.WAIT_FOR_OBSERVATION
                }
            }
        }
        if (boundaryEvidence != true) {
            edgeConfirmation.reset()
            return Observation.CONTINUE
        }
        // An actual physical edge can be confirmed without attempting to swipe past it.
        // No failed dispatch or unchanged fingerprint is counted as independent edge evidence.
        if (!edgeConfirmation.observe(currentFingerprint, nowMs)) return Observation.WAIT_FOR_OBSERVATION
        edgeConfirmation.reset()
        unchangedCount = 0
        clearRetry()
        if (!reversed) {
            reversed = true
            direction = if (direction == VerticalSwipeDirection.UP) {
                VerticalSwipeDirection.DOWN
            } else {
                VerticalSwipeDirection.UP
            }
            return Observation.REVERSED
        }
        complete = true
        active = false
        return Observation.COMPLETE
    }

    private fun holdForObservation(nowMs: Long) {
        retryAttempt = (retryAttempt + 1).coerceAtMost(RecoveryScheduler.fastRetryCount + 1)
        retryNotBeforeMs = nowMs + RecoveryScheduler.readOnlyRetryDelayMs(retryAttempt)
        retryConfirmation.reset()
        retryAdmitted = false
    }

    private fun clearRetry() {
        retryAttempt = 0
        retryNotBeforeMs = 0L
        retryConfirmation.reset()
        retryAdmitted = false
    }

    @Synchronized
    fun isActive(): Boolean = active

    @Synchronized
    fun isComplete(): Boolean = complete

    /** Retire search evidence without pretending an accepted gesture has ended. */
    @Synchronized
    fun reset() {
        searchGeneration++
        active = false
        direction = VerticalSwipeDirection.UP
        reversed = false
        unchangedCount = 0
        pendingBaseline = null
        complete = false
        edgeConfirmation.reset()
        clearRetry()
    }

    /** The run guard has invalidated previous callbacks before a run-level reset. */
    @Synchronized
    fun resetForRun() {
        reset()
        inFlightGesture = null
    }
}
