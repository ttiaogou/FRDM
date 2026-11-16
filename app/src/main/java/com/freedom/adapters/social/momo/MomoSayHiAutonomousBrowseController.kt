package com.freedom.adapters.social.momo

/**
 * Bounded controller for the confirmed received-greetings RecyclerView only.
 * It never targets profile cards, row controls, or the horizontal card carousel.
 */
internal class MomoSayHiAutonomousBrowseController(
    private val minimumIntervalMs: Long = 1_200L,
    private val maximumUnchangedPages: Int = 2
) {
    private var lastSignature: String = ""
    private var lastGestureAtMs: Long = 0L
    private var unchangedPages = 0
    private var gesturePending = false
    private var awaitingPostGestureSnapshot = false
    private var consecutiveFailures = 0
    private var nonScrollableBoundarySignature = ""
    private var nonScrollableBoundarySamples = 0
    private var lastNonScrollableBoundaryAtMs = 0L
    private var finished = false

    @Synchronized
    fun shouldScroll(signature: String, nowMs: Long, listScrollable: Boolean): Boolean {
        if (!listScrollable || signature.isBlank() || finished || gesturePending) return false
        if (lastSignature.isBlank()) {
            lastSignature = signature
            lastGestureAtMs = nowMs
            return false
        }
        if (awaitingPostGestureSnapshot) {
            if (signature == lastSignature) {
                unchangedPages++
            } else {
                unchangedPages = 0
                lastSignature = signature
            }
            awaitingPostGestureSnapshot = false
        } else if (signature != lastSignature) {
            unchangedPages = 0
            lastSignature = signature
        }
        if (unchangedPages >= maximumUnchangedPages) {
            finished = true
            return false
        }
        if (nowMs - lastGestureAtMs < minimumIntervalMs) return false
        gesturePending = true
        lastGestureAtMs = nowMs
        return true
    }

    /**
     * Releases a scheduled swipe when the shared snapshot/gesture gate is temporarily busy.
     * This is not a platform cancellation: no gesture was submitted, so it must not contribute
     * to the bounded consecutive-failure count or end-of-list detection.
     */
    @Synchronized
    fun onGestureDeferred() {
        gesturePending = false
        lastGestureAtMs = 0L
        awaitingPostGestureSnapshot = false
    }

    @Synchronized
    fun onGestureCompleted(completed: Boolean) {
        gesturePending = false
        if (completed) {
            consecutiveFailures = 0
            awaitingPostGestureSnapshot = true
        } else {
            // A failed/cancelled swipe is never evidence of the list boundary. Keep the
            // controller recoverable and rate-limit the next fresh-snapshot attempt.
            consecutiveFailures++
            lastGestureAtMs = System.currentTimeMillis()
            awaitingPostGestureSnapshot = false
        }
    }

    /**
     * Confirms an actually non-scrollable list only after two spaced, identical, complete
     * snapshots. A single transient `scrollable=false` value is not an end-of-list proof.
     */
    @Synchronized
    fun observeNonScrollableBoundary(signature: String, capturedAtMs: Long): Boolean {
        if (finished) return true
        if (signature.isBlank() || capturedAtMs <= 0L) return false
        if (signature != nonScrollableBoundarySignature || capturedAtMs < lastNonScrollableBoundaryAtMs) {
            nonScrollableBoundarySignature = signature
            nonScrollableBoundarySamples = 1
            lastNonScrollableBoundaryAtMs = capturedAtMs
            return false
        }
        if (capturedAtMs - lastNonScrollableBoundaryAtMs >= 250L) {
            nonScrollableBoundarySamples++
            lastNonScrollableBoundaryAtMs = capturedAtMs
        }
        if (nonScrollableBoundarySamples >= 2) {
            gesturePending = false
            awaitingPostGestureSnapshot = false
            finished = true
        }
        return finished
    }

    @Synchronized
    fun markFinished() {
        gesturePending = false
        awaitingPostGestureSnapshot = false
        finished = true
    }

    @Synchronized
    fun reset() {
        lastSignature = ""
        lastGestureAtMs = 0L
        unchangedPages = 0
        gesturePending = false
        awaitingPostGestureSnapshot = false
        consecutiveFailures = 0
        nonScrollableBoundarySignature = ""
        nonScrollableBoundarySamples = 0
        lastNonScrollableBoundaryAtMs = 0L
        finished = false
    }

    @Synchronized
    fun isFinished(): Boolean = finished
}
