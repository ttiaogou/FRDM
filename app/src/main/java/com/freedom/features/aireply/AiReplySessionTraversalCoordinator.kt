package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot

/**
 * Recovery pacing for temporarily empty accessibility roots; it never invents page identity.
 *
 * The fast delays are deliberately finite, but the recovery episode is not. Once the fast
 * schedule is exhausted, callers continue polling at the low-frequency delay until a fresh
 * snapshot is usable or the owning run is explicitly stopped.
 */
internal class AiReplySessionTraversalCoordinator(retryDelaysMs: LongArray) {
    private val retryDelays = retryDelaysMs.filter { it > 0L }.toList()
    var retryCount: Int = 0
        private set

    fun isTransient(snapshot: ChatPageSnapshot?): Boolean = snapshot == null ||
        (snapshot.pageKind == ChatPageKind.UNKNOWN && snapshot.scannedNodeCount == 0 &&
            snapshot.messageListCount == 0 && snapshot.editableInputCount == 0 &&
            snapshot.sendActionCount == 0 && snapshot.messageRelatedViewIdCount == 0)

    /** Returns the next fast delay, then keeps returning the low-frequency delay indefinitely. */
    fun nextDelayMs(lowFrequencyDelayMs: Long = DEFAULT_LOW_FREQUENCY_DELAY_MS): Long {
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

    private companion object {
        const val DEFAULT_LOW_FREQUENCY_DELAY_MS = 30_000L
    }
}
