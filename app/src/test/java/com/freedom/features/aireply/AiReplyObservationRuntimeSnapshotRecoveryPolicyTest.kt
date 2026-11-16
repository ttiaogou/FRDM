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

    @Test fun recoveryRetriesAreBoundedAndResetForTheNextEpisode() {
        val policy = AiReplySessionTraversalCoordinator(longArrayOf(350L, 700L, 1_200L))
        assertEquals(350L, policy.nextDelayMs())
        assertEquals(700L, policy.nextDelayMs())
        assertEquals(1_200L, policy.nextDelayMs())
        assertEquals(null, policy.nextDelayMs())
        assertEquals(0, policy.retryCount)
        assertEquals(350L, policy.nextDelayMs())
    }
}
