package com.freedom.features.aireply

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatNodeTextSource
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatUnreadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessagesTraversalTaskExecutorTest {
    @Test
    fun mandatoryFirstSwipeAndTriStateUnreadAdmissionRemainDistinct() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val unread = candidate(0, "Unread", ChatUnreadStatus.UNREAD)
        val unknown = candidate(1, "Unknown", ChatUnreadStatus.UNKNOWN)

        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.FIRST_SWIPE_REQUIRED,
            executor.evaluateUnreadAdmission(snapshot(unread))
        )
        executor.initialUnreadGateSwipeCompleted = true
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
            executor.evaluateUnreadAdmission(snapshot(unread, unknown))
        )
        assertEquals(setOf(key(unread)), executor.lockedUnreadSessionKeys)
        assertTrue(executor.normalInboxUnreadStateUnknown)

        executor.reset()
        executor.initialUnreadGateSwipeCompleted = true
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.NO_UNREAD,
            executor.evaluateUnreadAdmission(snapshot(candidate(2, "Read", ChatUnreadStatus.READ)))
        )

        executor.reset()
        executor.initialUnreadGateSwipeCompleted = true
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(unread))
        )
        assertEquals(setOf(key(unread)), executor.lockedUnreadSessionKeys)
    }

    @Test
    fun listSnapshotEvaluationOwnsUnreadAdmissionAndFrozenCandidateSelection() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val top = candidate(0, "Top", ChatUnreadStatus.UNREAD, top = 100)
        val bottom = candidate(1, "Bottom", ChatUnreadStatus.UNREAD, top = 400)

        val first = executor.evaluateListSnapshot(
            snapshot(bottom, top),
            wasWaitingForUnread = false,
            maxNoProgressScrolls = 2
        )
        assertEquals(PrivateMessagesUnreadAdmissionDecision.FIRST_SWIPE_REQUIRED, first.unreadAdmissionDecision)
        assertEquals(null, first.nextCandidate)

        executor.initialUnreadGateSwipeCompleted = true
        val admission = executor.evaluateListSnapshot(
            snapshot(bottom, top),
            wasWaitingForUnread = false,
            maxNoProgressScrolls = 2
        )
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING, admission.unreadAdmissionDecision)
        assertEquals(setOf(key(bottom), key(top)), executor.lockedUnreadSessionKeys)
        assertEquals(null, admission.nextCandidate)

        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.lockedUnreadSetFinalized = true
        val scan = executor.evaluateListSnapshot(
            snapshot(top, bottom),
            wasWaitingForUnread = false,
            maxNoProgressScrolls = 2
        )
        assertEquals(null, scan.unreadAdmissionDecision)
        assertEquals(bottom, scan.nextCandidate)
        assertTrue(scan.unreadAuditChanged)
        assertEquals(scan.structuralFingerprint, executor.evaluateListSnapshot(
            snapshot(top, bottom),
            wasWaitingForUnread = false,
            maxNoProgressScrolls = 2
        ).structuralFingerprint)
    }

    @Test
    fun listSnapshotEvaluationResetsAttemptedRowsOnlyForExplicitUnreadWakeup() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.executionState = SessionManagerState.WAITING_FOR_UNREAD
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.atTop = true
        executor.attemptedSessionKeys += "previously-attempted"
        val unread = candidate(0, "New unread", ChatUnreadStatus.UNREAD)

        val evaluation = executor.evaluateListSnapshot(
            snapshot(unread),
            wasWaitingForUnread = true,
            maxNoProgressScrolls = 2
        )

        assertTrue(evaluation.wasWaitingForUnread)
        assertTrue(evaluation.explicitUnreadWhileWaiting)
        assertTrue(executor.attemptedSessionKeys.isEmpty())
        assertEquals(unread, evaluation.nextCandidate)
    }

    @Test
    fun listSnapshotEvaluationConsumesViewportScrollAndBoundaryEvidenceOnce() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        val previous = candidate(0, "Previous", ChatUnreadStatus.UNREAD, top = 100)
        val current = candidate(0, "Current", ChatUnreadStatus.UNREAD, top = 350)
        executor.pendingScrollBaseline = executor.visibleSessionKeys(snapshot(previous))
        executor.pendingScrollToTop = true

        val evaluation = executor.evaluateListSnapshot(
            snapshot(current),
            wasWaitingForUnread = false,
            maxNoProgressScrolls = 2
        )

        assertEquals(PrivateMessagesScrollObservationDecision.TOP_VIEWPORT_CHANGED, evaluation.scrollDecision)
        assertEquals(null, executor.pendingScrollBaseline)
        assertFalse(executor.atTop)
        assertEquals(executor.visibleSessionKeys(snapshot(current)), evaluation.visibleKeys)
    }

    @Test
    fun unreadEvidenceAccumulatesAccordingToPositioningAndFinalizationPhase() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val unread = candidate(0, "Unread", ChatUnreadStatus.UNREAD)
        val unknown = candidate(1, "Unknown", ChatUnreadStatus.UNKNOWN)

        executor.recordViewportUnreadEvidence(snapshot(unread), receivedGreetingsActive = false)
        assertTrue(executor.normalInboxHadUnread)
        assertFalse(executor.normalInboxUnreadStateUnknown)

        executor.normalInboxHadUnread = false
        executor.recordViewportUnreadEvidence(snapshot(unknown), receivedGreetingsActive = false)
        assertFalse(executor.normalInboxHadUnread)
        assertTrue(executor.normalInboxUnreadStateUnknown)

        executor.normalInboxUnreadStateUnknown = false
        executor.lockedUnreadSetFinalized = true
        executor.recordViewportUnreadEvidence(snapshot(unread), receivedGreetingsActive = false)
        assertFalse(executor.normalInboxHadUnread)
        executor.recordViewportUnreadEvidence(snapshot(unknown), receivedGreetingsActive = false)
        assertTrue(executor.normalInboxUnreadStateUnknown)

        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.normalInboxHadUnread = false
        executor.normalInboxUnreadStateUnknown = false
        executor.recordViewportUnreadEvidence(snapshot(unread, unknown), receivedGreetingsActive = false)
        assertTrue(executor.normalInboxHadUnread)
        assertTrue(executor.normalInboxUnreadStateUnknown)

        executor.normalInboxHadUnread = false
        executor.normalInboxUnreadStateUnknown = false
        executor.recordViewportUnreadEvidence(snapshot(unread, unknown), receivedGreetingsActive = true)
        assertFalse(executor.normalInboxHadUnread)
        assertFalse(executor.normalInboxUnreadStateUnknown)
    }

    @Test
    fun scrollObservationOwnsProgressAndBoundaryTransitions() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val visible = listOf("row-a@0,0,100,80")
        val moved = listOf("row-b@0,80,100,160")

        executor.pendingScrollBaseline = visible
        executor.pendingScrollToTop = true
        assertEquals(
            PrivateMessagesScrollObservationDecision.TOP_VIEWPORT_CHANGED,
            executor.consumePendingScrollObservation(moved, maxNoProgressScrolls = 2)
        )
        assertEquals(0, executor.noProgressScrollCount)
        assertFalse(executor.atTop)
        assertEquals(null, executor.pendingScrollBaseline)

        executor.pendingScrollBaseline = visible
        executor.pendingScrollToTop = true
        assertEquals(
            PrivateMessagesScrollObservationDecision.TOP_NO_PROGRESS_RETRY,
            executor.consumePendingScrollObservation(visible, maxNoProgressScrolls = 2)
        )
        executor.pendingScrollBaseline = visible
        executor.pendingScrollToTop = true
        executor.atTop = true
        assertEquals(
            PrivateMessagesScrollObservationDecision.TOP_BOUNDARY_CANDIDATE,
            executor.consumePendingScrollObservation(visible, maxNoProgressScrolls = 2)
        )
        // Reaching the boundary candidate does not itself alter the pre-existing atTop flag.
        assertTrue(executor.atTop)

        executor.phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        executor.noProgressScrollCount = 0
        executor.pendingScrollBaseline = visible
        executor.pendingScrollToTop = false
        assertEquals(
            PrivateMessagesScrollObservationDecision.BOTTOM_NO_PROGRESS,
            executor.consumePendingScrollObservation(visible, maxNoProgressScrolls = 2)
        )
        executor.pendingScrollBaseline = visible
        assertEquals(
            PrivateMessagesScrollObservationDecision.BOTTOM_BOUNDARY_CANDIDATE,
            executor.consumePendingScrollObservation(visible, maxNoProgressScrolls = 2)
        )

        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.pendingScrollBaseline = visible
        assertEquals(
            PrivateMessagesScrollObservationDecision.BOTTOM_VIEWPORT_CHANGED,
            executor.consumePendingScrollObservation(moved, maxNoProgressScrolls = 2)
        )
        assertEquals(
            PrivateMessagesScrollObservationDecision.NO_PENDING_SCROLL,
            executor.consumePendingScrollObservation(moved, maxNoProgressScrolls = 2)
        )
    }

    @Test
    fun unrequestedViewportChangeInvalidatesScanPositionButKeepsProcessedIdentities() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.lastStableSessionListVisibleKeys = listOf("old-viewport")
        executor.attemptedSessionKeys += "already-processed"
        executor.noProgressScrollCount = 2
        executor.sessionListStabilityRetryCount = 1
        val unread = candidate(0, "New unread", ChatUnreadStatus.UNREAD)

        val changed = executor.reconcileUnrequestedViewportChange(
            hadPendingScrollBaseline = false,
            wasWaitingForUnread = false,
            visibleKeys = listOf("new-viewport"),
            snapshot = snapshot(unread)
        )

        assertTrue(changed)
        assertEquals(SessionListTraversalPhase.POSITIONING_AT_BOTTOM, executor.phase)
        assertFalse(executor.atTop)
        assertEquals(0, executor.noProgressScrollCount)
        assertEquals(0, executor.sessionListStabilityRetryCount)
        assertTrue(executor.normalInboxHadUnread)
        assertEquals(setOf("already-processed"), executor.attemptedSessionKeys)
    }

    @Test
    fun candidateSelectionKeepsBottomToTopOrderAndFrozenUnreadMembership() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val top = candidate(0, "Top", ChatUnreadStatus.UNREAD, top = 100)
        val bottom = candidate(1, "Bottom", ChatUnreadStatus.UNREAD, top = 400)
        val read = candidate(2, "Read", ChatUnreadStatus.READ, top = 700)
        val candidates = listOf(top, bottom, read)

        assertEquals(bottom, executor.selectNextCandidate(candidates))
        executor.attemptedSessionKeys += key(bottom)
        assertEquals(top, executor.selectNextCandidate(candidates))
        assertEquals(null, executor.selectNextCandidate(candidates, lockedIdentityKeys = setOf(key(read))))
        assertEquals(top, executor.selectNextCandidate(candidates, lockedIdentityKeys = setOf(key(top))))
    }

    @Test
    fun executionerOwnsCurrentSessionContextAndClearsItAtResetBoundary() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val selected = candidate(3, "Selected session", ChatUnreadStatus.UNREAD)

        executor.executionState = SessionManagerState.OPENING_SESSION
        executor.beginSession(
            candidate = selected,
            sessionKey = key(selected),
            windowKey = "window-3"
        )
        executor.setSessionReadCaptured(true)

        assertEquals(SessionManagerState.OPENING_SESSION, executor.executionState)
        assertEquals(key(selected), executor.currentSessionKey)
        assertEquals("Selected session", executor.currentSessionName)
        assertEquals(selected, executor.currentSessionCandidate)
        assertTrue(executor.currentSessionReadCaptured)
        assertEquals("window-3", executor.currentSessionWindowKey)

        executor.reset()

        assertEquals(SessionManagerState.STOPPED, executor.executionState)
        assertEquals(null, executor.currentSessionKey)
        assertEquals(null, executor.currentSessionName)
        assertEquals(null, executor.currentSessionCandidate)
        assertFalse(executor.currentSessionReadCaptured)
        assertEquals(null, executor.currentSessionWindowKey)
    }

    @Test
    fun returnedInboxResetClearsOnlyTheNewPassStateAndKeepsRunAuditHistory() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.atTop = true
        executor.initialUnreadGateSwipeCompleted = true
        executor.initialInboxPositionCheckCompleted = false
        executor.attemptedSessionKeys += "attempted"
        executor.lockedUnreadSessionKeys += "locked"
        executor.lockedUnreadSetFinalized = true
        executor.pendingScrollBaseline = listOf("baseline")
        executor.pendingScrollToTop = true
        executor.lastStableSessionListVisibleKeys = listOf("stable")
        executor.normalInboxHadUnread = true
        executor.normalInboxUnreadStateUnknown = true
        executor.pendingBoundaryConfirmationKeys = listOf("boundary")
        executor.boundaryStabilityConfirmationCount = 1
        executor.capturedSessionListFingerprints += "audit"

        executor.resetForReturnedInbox()

        assertEquals(SessionListTraversalPhase.POSITIONING_AT_BOTTOM, executor.phase)
        assertFalse(executor.atTop)
        assertTrue(executor.initialInboxPositionCheckCompleted)
        assertTrue(executor.initialUnreadGateSwipeCompleted)
        assertTrue(executor.attemptedSessionKeys.isEmpty())
        assertTrue(executor.lockedUnreadSessionKeys.isEmpty())
        assertFalse(executor.lockedUnreadSetFinalized)
        assertEquals(null, executor.pendingScrollBaseline)
        assertFalse(executor.pendingScrollToTop)
        assertEquals(null, executor.lastStableSessionListVisibleKeys)
        assertFalse(executor.normalInboxHadUnread)
        assertFalse(executor.normalInboxUnreadStateUnknown)
        assertEquals(listOf("boundary"), executor.pendingBoundaryConfirmationKeys)
        assertEquals(1, executor.boundaryStabilityConfirmationCount)
        assertEquals(setOf("audit"), executor.capturedSessionListFingerprints)
    }

    private fun key(candidate: ChatListCandidateSnapshot): String =
        com.freedom.features.aireply.ports.ChatSessionManagementPolicy.stableSessionKey(candidate)

    private fun snapshot(vararg candidates: ChatListCandidateSnapshot) = ChatPageSnapshot(
        packageName = "com.immomo.momo",
        activityClass = "MaintabActivity",
        pageKind = com.freedom.features.aireply.ports.ChatPageKind.CHAT_LIST,
        scannedNodeCount = candidates.size,
        editableInputCount = 0,
        messageListCount = 0,
        sendActionCount = 0,
        messageRelatedViewIdCount = 0,
        hasChatTab = true,
        messageListTarget = null,
        inputFieldTarget = null,
        chatListViewport = ScreenRect(0, 0, 720, 1600),
        chatListCandidates = candidates.toList()
    )

    private fun candidate(
        rowIndex: Int,
        name: String,
        unread: ChatUnreadStatus,
        top: Int = 100 + rowIndex * 150
    ): ChatListCandidateSnapshot {
        val row = ScreenRect(0, top, 720, top + 140)
        val nameBounds = ScreenRect(150, top + 10, 380, top + 40)
        val previewBounds = ScreenRect(150, top + 50, 620, top + 90)
        return ChatListCandidateSnapshot(
            rowIndex = rowIndex,
            rowTarget = NodeTarget(
                WindowRef("com.immomo.momo", "MaintabActivity", 1L, 1),
                NodePath(listOf(0, rowIndex)),
                expectedClassName = "android.widget.RelativeLayout"
            ),
            rowBounds = row,
            rowVisibleToUser = true,
            intersectsViewport = true,
            fullyInsideViewport = true,
            rowBoundsValid = true,
            avatarBounds = ScreenRect(20, top + 10, 110, top + 100),
            avatarPath = NodePath(listOf(0, rowIndex, 0)),
            nameText = name,
            nameSource = ChatNodeTextSource.NODE_TEXT,
            nameNodePath = NodePath(listOf(0, rowIndex, 1)),
            nameBounds = nameBounds,
            nameNodeVisibleToUser = true,
            nameBoundsIntersectViewport = true,
            nameBoundsFullyInsideViewport = true,
            nameBoundsValid = true,
            previewText = "Preview $rowIndex",
            previewSource = ChatNodeTextSource.NODE_TEXT,
            previewNodePath = NodePath(listOf(0, rowIndex, 2)),
            previewBounds = previewBounds,
            previewNodeVisibleToUser = true,
            previewBoundsIntersectViewport = true,
            previewBoundsFullyInsideViewport = true,
            previewBoundsValid = true,
            timestampText = "10:20",
            timestampSource = ChatNodeTextSource.NODE_TEXT,
            timestampNodePath = null,
            timestampBounds = null,
            timestampNodeVisibleToUser = false,
            unreadStatus = unread
        )
    }
}
