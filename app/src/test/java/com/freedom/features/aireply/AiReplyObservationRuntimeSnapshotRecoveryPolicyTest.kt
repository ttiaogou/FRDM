package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReplyObservationRuntimeSnapshotRecoveryPolicyTest {
    private fun snapshot(
        kind: ChatPageKind = ChatPageKind.UNKNOWN,
        scanned: Int = 0,
        messageLists: Int = 0,
        editableInputs: Int = 0,
        sendActions: Int = 0,
        messageIds: Int = 0
    ) = ChatPageSnapshot(
        packageName = "com.immomo.momo",
        activityClass = "com.immomo.momo.maintab.MaintabActivity",
        pageKind = kind,
        scannedNodeCount = scanned,
        editableInputCount = editableInputs,
        messageListCount = messageLists,
        sendActionCount = sendActions,
        messageRelatedViewIdCount = messageIds,
        hasChatTab = false,
        messageListTarget = null,
        inputFieldTarget = null
    )

    @Test fun nullOrCompletelyEmptyUnknownSnapshotIsTransient() {
        val policy = AiReplySessionTraversalCoordinator(longArrayOf(100L, 200L))
        assertTrue(policy.isTransient(null))
        assertTrue(policy.isTransient(snapshot()))
    }

    @Test fun nonEmptyOrClassifiedSnapshotIsNotTreatedAsTransient() {
        val policy = AiReplySessionTraversalCoordinator(longArrayOf(100L, 200L))
        assertFalse(policy.isTransient(snapshot(scanned = 1)))
        assertFalse(policy.isTransient(snapshot(kind = ChatPageKind.CHAT_LIST)))
        assertFalse(policy.isTransient(snapshot(messageLists = 1)))
    }

    @Test fun recoveryUsesFastBackoffThenContinuesLowFrequencyWithoutHardStop() {
        val policy = AiReplySessionTraversalCoordinator(longArrayOf(500L, 1_000L, 2_000L, 5_000L, 10_000L))
        assertEquals(500L, policy.nextDelayMs(30_000L))
        assertEquals(1_000L, policy.nextDelayMs(30_000L))
        assertEquals(2_000L, policy.nextDelayMs(30_000L))
        assertEquals(5_000L, policy.nextDelayMs(30_000L))
        assertEquals(10_000L, policy.nextDelayMs(30_000L))
        assertEquals(30_000L, policy.nextDelayMs(30_000L))
        assertEquals(30_000L, policy.nextDelayMs(30_000L))
        assertTrue(policy.retryCount > 6)
        policy.reset()
        assertEquals(0, policy.retryCount)
        assertEquals(500L, policy.nextDelayMs(30_000L))
    }

    @Test fun recoveryGateRequiresTwoEqualFreshStructuralSamples() {
        val gate = PageRecoveryVerificationGate()
        assertFalse(gate.observe("chat-list|rows=3"))
        assertEquals(1, gate.stableSamples)
        assertFalse(gate.observe("chat-list|rows=4"))
        assertTrue(gate.observe("chat-list|rows=4"))
        gate.reset()
        assertEquals(0, gate.stableSamples)
    }
}
