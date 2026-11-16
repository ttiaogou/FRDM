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

    init {
        require(unchangedObservationsToConfirmBoundary >= 2)
    }

    fun start() {
        active = true
        direction = VerticalSwipeDirection.UP
        reversed = false
        unchangedCount = 0
        pendingBaseline = null
        complete = false
    }

    fun direction(): VerticalSwipeDirection = direction

    fun onGestureCompleted(baselineFingerprint: String) {
        if (!active || complete || baselineFingerprint.isBlank()) return
        pendingBaseline = baselineFingerprint
    }

    /** A cancelled/deferred gesture is not evidence that a list boundary was reached. */
    fun onGestureFailed() {
        pendingBaseline = null
    }

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

    fun isActive(): Boolean = active

    fun isComplete(): Boolean = complete

    fun reset() {
        active = false
        direction = VerticalSwipeDirection.UP
        reversed = false
        unchangedCount = 0
        pendingBaseline = null
        complete = false
    }
}
