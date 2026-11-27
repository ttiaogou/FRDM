package com.freedom.features.aireply

import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpoint
import com.freedom.features.aireply.ports.UnfinishedPrivateSession
import com.freedom.features.aireply.ports.UnfinishedPrivateSessionDisposition
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessagesCheckpointRecoveryTest {
    private fun observedDetail(): ChatPageSnapshot {
        val window = WindowRef("com.immomo.momo", "ChatActivity", 2L, 3)
        return ChatPageSnapshot(
            packageName = "com.immomo.momo", activityClass = "ChatActivity",
            pageKind = ChatPageKind.CHAT_DETAIL_CANDIDATE, scannedNodeCount = 40,
            editableInputCount = 1, messageListCount = 1, sendActionCount = 1,
            messageRelatedViewIdCount = 1, hasChatTab = false,
            messageListTarget = NodeTarget(window, NodePath(listOf(1, 2))),
            inputFieldTarget = NodeTarget(window, NodePath(listOf(1, 3))),
            chatTitleText = "与Sample Contact对话"
        )
    }

    @Test
    fun r04cObservedVisitResumesExactPlatformKeyWithoutMovingInboxCursor() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            attemptedSessionKeys += "already-attempted"
            lockedUnreadSessionKeys += "original-unread"
            checkpointNextCursorKey = "original-cursor"
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val page = observedDetail()
        val key = ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact("contact-A")
        val title = ChatSessionManagementPolicy.stableSessionKeyForTitle("Sample Contact")
        val requestKey = "a".repeat(64)
        val entry = UnfinishedPrivateSession(title, title, key, page.conversationIdentityKey()!!,
            100L, UnfinishedPrivateSessionDisposition.SEND_REVISIT_ELIGIBLE, requestKey)
        val before = executor.checkpoint().copy(unfinishedSessions = listOf(entry))
        executor.restore(before)
        assertTrue(executor.beginVerifiedObservedDetail(page, key, "Sample Contact", "new-window", 200L))
        assertTrue(executor.recoveringUnfinishedSession)
        assertEquals(entry, executor.currentUnfinishedSession())
        assertEquals(null, executor.currentSessionCandidate)
        assertFalse(executor.recordVerifiedVisitReplyRequestKey("b".repeat(64)))
        assertTrue(executor.recordVerifiedVisitReplyRequestKey(requestKey))
        assertEquals(before.nextCursorKey, executor.checkpoint().nextCursorKey)
        assertEquals(before.lockedUnreadSessionKeys, executor.checkpoint().lockedUnreadSessionKeys)
        assertTrue(executor.completeCurrentUnfinishedSession())
        assertTrue(executor.checkpoint().unfinishedSessions.isEmpty())
        assertTrue(executor.attemptedSessionKeys.containsAll(before.attemptedSessionKeys + key))
        assertEquals(before.nextCursorKey, executor.checkpoint().nextCursorKey)
        assertEquals(before.phase, executor.checkpoint().phase)
    }

    @Test
    fun r04cObservedVisitCannotInheritWeakOrDifferentSameTitleRecord() {
        val page = observedDetail()
        val key = ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact("contact-A")
        val other = ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact("contact-B")
        val title = ChatSessionManagementPolicy.stableSessionKeyForTitle("Sample Contact")
        val executor = PrivateMessagesTraversalTaskExecutor()
        for (verified in listOf(null, other)) {
            val entry = UnfinishedPrivateSession(title, title, verified, page.conversationIdentityKey()!!, 100L)
            val before = executor.checkpoint().copy(unfinishedSessions = listOf(entry))
            executor.restore(before)
            assertFalse(executor.beginVerifiedObservedDetail(page, key, "Sample Contact", "new-window", 200L))
            assertEquals(listOf(entry), executor.checkpoint().unfinishedSessions)
            assertEquals(null, executor.currentSessionKey)
        }
    }

    @Test
    fun restoreReplacesTransientAndTraversalState() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            attemptedSessionKeys += "old-attempt"
            lockedUnreadSessionKeys += "old-unread"
            currentSessionKey = "old-session"
            pendingScrollToTop = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val checkpoint = PrivateMessagesTraversalCheckpoint(
            attemptedSessionKeys = setOf("restored-attempt"),
            lockedUnreadSessionKeys = setOf("restored-unread"),
            ambiguousSessionIdentityKeys = setOf("restored-ambiguous"),
            lastStableVisibleKeys = listOf("restored-unread@0,0,100,100"),
            phase = SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY.name,
            atTop = false,
            initialInboxPositionCheckCompleted = true,
            initialInboxTopPositioningCompleted = true,
            initialUnreadGateSwipeCompleted = true,
            lockedUnreadSetFinalized = true,
            normalInboxHadUnread = true,
            normalInboxUnreadStateUnknown = false,
            savedAtEpochMs = 100L
        )

        executor.restore(checkpoint)

        assertEquals(setOf("restored-attempt"), executor.attemptedSessionKeys)
        assertEquals(setOf("restored-unread"), executor.lockedUnreadSessionKeys)
        assertEquals("restored-unread@0,0,100,100", executor.lastStableSessionListVisibleKeys?.single())
        assertEquals(SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY, executor.phase)
        assertEquals(null, executor.currentSessionKey)
        assertFalse(executor.pendingScrollToTop)
    }

    @Test
    fun restoreKeepsOpaqueUnknownBlockersWithoutReusingUiHandles() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            normalInboxUnreadStateUnknown = true
            unresolvedUnknownUnreadKeys += "opaque-unknown-row"
            unknownUnreadViewportKeys += "opaque-row@0,100,720,240"
        }
        val snapshot = executor.checkpoint()
        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(snapshot) }

        assertEquals(setOf("opaque-unknown-row"), restored.unresolvedUnknownUnreadKeys)
        assertEquals(listOf("opaque-row@0,100,720,240"), restored.unknownUnreadViewportKeys)
        assertTrue(restored.normalInboxUnreadStateUnknown)
        assertEquals(snapshot.unresolvedUnknownUnreadKeys, restored.checkpoint().unresolvedUnknownUnreadKeys)
        assertEquals(snapshot.unknownUnreadViewportKeys, restored.checkpoint().unknownUnreadViewportKeys)
    }

    @Test
    fun emptyRestoredViewportDoesNotBlockInitialRecovery() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val checkpoint = PrivateMessagesTraversalCheckpoint(
            attemptedSessionKeys = emptySet(),
            lockedUnreadSessionKeys = emptySet(),
            ambiguousSessionIdentityKeys = emptySet(),
            lastStableVisibleKeys = emptyList(),
            phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM.name,
            atTop = false,
            initialInboxPositionCheckCompleted = false,
            initialInboxTopPositioningCompleted = false,
            initialUnreadGateSwipeCompleted = false,
            lockedUnreadSetFinalized = false,
            normalInboxHadUnread = false,
            normalInboxUnreadStateUnknown = false,
            savedAtEpochMs = 100L
        )

        executor.restore(checkpoint)

        assertTrue(executor.lastStableSessionListVisibleKeys.orEmpty().isEmpty())
        assertEquals(SessionListTraversalPhase.POSITIONING_AT_BOTTOM, executor.phase)
    }
}
