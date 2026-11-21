package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.recovery.RecoveryScheduler

/**
 * Recovery pacing for temporarily empty accessibility roots; it never invents page identity.
 *
 * The fast delays are deliberately finite, but the recovery episode is not. Once the fast
 * schedule is exhausted, callers continue polling at the low-frequency delay until a fresh
 * snapshot is usable or the owning run is explicitly stopped.
 */
internal class AiReplySessionTraversalCoordinator(retryDelaysMs: LongArray) {
    private val pageObservationSemantics = AiReplyPageObservationSemantics()
    private val retryDelays = retryDelaysMs.filter { it > 0L }.toList()
    var retryCount: Int = 0
        private set

    fun isTransient(snapshot: ChatPageSnapshot?): Boolean =
        pageObservationSemantics.classify(snapshot, previous = null, targetPackage = "").status ==
            ObservationStatus.TEMPORARILY_UNAVAILABLE

    /** Returns the next fast delay, then keeps returning the low-frequency delay indefinitely. */
    fun nextDelayMs(lowFrequencyDelayMs: Long = RecoveryScheduler.longWaitDelayMs): Long {
        require(lowFrequencyDelayMs > 0L) { "lowFrequencyDelayMs must be positive" }
        return if (retryCount < retryDelays.size) {
            retryDelays[retryCount++]
        } else {
            retryCount++
            lowFrequencyDelayMs
        }
    }

    fun reset() {
        retryCount = 0
    }

}
