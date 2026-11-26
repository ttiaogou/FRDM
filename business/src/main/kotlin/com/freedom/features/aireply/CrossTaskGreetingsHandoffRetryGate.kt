package com.freedom.features.aireply

import com.freedom.features.recovery.RecoveryScheduler

/**
 * Read-only handoff attempt throttle, not a second recovery state machine.
 *
 * The private checkpoint and unified recovery transaction continue to own recovery.
 * This guard only prevents repeating the same failed UI lease transfer on unchanged
 * destination evidence. Every retry is backed by the existing RecoveryScheduler cadence.
 */
internal class CrossTaskGreetingsHandoffRetryGate {
    data class Retry(val attempt: Int, val delayMs: Long)

    private var evidenceKey: String? = null
    private var consecutiveFailures = 0
    private var waitingForReadOnlyRetry = false

    fun evidenceChanged(nextKey: String): Boolean =
        evidenceKey != null && evidenceKey != nextKey

    fun isWaitingOn(nextKey: String): Boolean =
        evidenceKey == nextKey && waitingForReadOnlyRetry

    fun onAdmissionBlocked(key: String): Retry {
        require(key.isNotBlank()) { "handoff evidence key must not be blank" }
        if (evidenceKey != key) {
            evidenceKey = key
            consecutiveFailures = 0
        }
        consecutiveFailures++
        waitingForReadOnlyRetry = true
        return Retry(
            attempt = consecutiveFailures,
            delayMs = RecoveryScheduler.readOnlyRetryDelayMs(consecutiveFailures)
        )
    }

    /** A timer grants only another read-only page observation, never task ownership. */
    fun onReadOnlyRetryDue(key: String): Boolean {
        if (evidenceKey != key || !waitingForReadOnlyRetry) return false
        waitingForReadOnlyRetry = false
        return true
    }

    fun reset() {
        evidenceKey = null
        consecutiveFailures = 0
        waitingForReadOnlyRetry = false
    }
}
