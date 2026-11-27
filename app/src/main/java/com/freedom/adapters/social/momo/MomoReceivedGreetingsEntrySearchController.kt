package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.gesture.VerticalSwipeDirection

/**
 * Controls a bounded, two-direction sweep of the ordinary Message list when the received-
 * greetings entry is not present in the current viewport. A successful gesture is compared with
 * the next fresh viewport fingerprint; two successful no-motion observations confirm one edge.
 */
internal class MomoReceivedGreetingsEntrySearchController(
    private val unchangedObservationsToConfirmBoundary: Int = 2
) {
    enum class Observation {
        CONTINUE,
        REVERSED,
        COMPLETE
    }

    private var active = false
    private var direction = VerticalSwipeDirection.UP
    private var reversed = false
    private var unchangedCount = 0
    private var pendingBaseline: String? = null
    private var complete = false
    private var searchGeneration = 0L
    private data class InFlightGesture(val requestId: String, val generation: Long)
    private var inFlightGesture: InFlightGesture? = null

    init {
        require(unchangedObservationsToConfirmBoundary >= 2)
    }

    @Synchronized
    fun start() {
        searchGeneration++
        active = true
        direction = VerticalSwipeDirection.UP
        reversed = false
        unchangedCount = 0
        pendingBaseline = null
        complete = false
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
            inFlightGesture != null || requestId.isBlank()
        ) return false
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
    fun finishGesture(requestId: String, completedBaseline: String?): Boolean {
        val pending = inFlightGesture ?: return false
        if (pending.requestId != requestId) return false
        inFlightGesture = null
        if (pending.generation != searchGeneration || !active || complete) return false
        pendingBaseline = completedBaseline?.takeIf { it.isNotBlank() }
        return true
    }

    @Synchronized
    fun observeAfterGesture(currentFingerprint: String): Observation {
        if (!active || complete || currentFingerprint.isBlank()) return Observation.CONTINUE
        val baseline = pendingBaseline ?: return Observation.CONTINUE
        pendingBaseline = null
        if (currentFingerprint != baseline) {
            unchangedCount = 0
            return Observation.CONTINUE
        }

        unchangedCount++
        if (unchangedCount < unchangedObservationsToConfirmBoundary) return Observation.CONTINUE
        unchangedCount = 0
        if (!reversed) {
            reversed = true
            direction = VerticalSwipeDirection.DOWN
            return Observation.REVERSED
        }
        complete = true
        active = false
        return Observation.COMPLETE
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
    }

    /** The run guard has invalidated previous callbacks before a run-level reset. */
    @Synchronized
    fun resetForRun() {
        reset()
        inFlightGesture = null
    }
}
