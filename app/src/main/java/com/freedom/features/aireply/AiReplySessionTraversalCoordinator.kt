package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot

/** Bounded recovery for temporarily empty accessibility roots; it never invents page identity. */
internal class AiReplySessionTraversalCoordinator(retryDelaysMs: LongArray) {
    private val retryDelays = retryDelaysMs.toList()
    var retryCount: Int = 0
        private set

    fun isTransient(snapshot: ChatPageSnapshot?): Boolean = snapshot == null ||
        (snapshot.pageKind == ChatPageKind.UNKNOWN && snapshot.scannedNodeCount == 0 &&
            snapshot.messageListCount == 0 && snapshot.editableInputCount == 0 &&
            snapshot.sendActionCount == 0 && snapshot.messageRelatedViewIdCount == 0)

    /** Returns the next bounded delay, then returns null once the episode is exhausted. */
    fun nextDelayMs(): Long? {
        if (retryCount >= retryDelays.size) {
            reset()
            return null
        }
        return retryDelays[retryCount++]
    }

    fun reset() {
        retryCount = 0
    }
}
