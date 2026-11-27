package com.freedom.features.aireply

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatNodeTextSource
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.features.aireply.ports.UnfinishedPrivateSessionDisposition
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateMessagesTraversalTaskExecutorTest {
    @Test
    fun identityReturnMigratesFrozenUnreadAndPreservesViewport() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val original = candidate(1, "Alice", ChatUnreadStatus.UNREAD)
        val other = candidate(0, "Bob", ChatUnreadStatus.READ)
        val oldKey = key(original)
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.initialInboxTopPositioningCompleted = true
        executor.lockedUnreadSetFinalized = true
        executor.lockedUnreadSessionKeys += oldKey
        executor.lastStableSessionListVisibleKeys = executor.visibleSessionKeys(snapshot(other, original))
        assertTrue(executor.beginIdentityReturn(original, 4))
        val verified = candidate(1, "Alice", ChatUnreadStatus.UNREAD).apply {
            contactIdentity = ContactRowIdentity("s", "u", "contact-1", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
        }
        val returned = snapshot(other, verified).copy(windowGeneration = 1)
        assertEquals(null, executor.selectNextCandidate(returned.chatListCandidates, executor.lockedUnreadSessionKeys))
        val result = executor.reconcileIdentityReturn(returned, 4)
        assertEquals(PrivateMessagesTraversalTaskExecutor.IdentityReturnStatus.RESTORED, result.status)
        assertEquals(setOf(key(verified)), executor.lockedUnreadSessionKeys)
        assertEquals(verified, executor.selectNextCandidate(returned.chatListCandidates, executor.lockedUnreadSessionKeys))
        val evaluation = executor.evaluateListSnapshot(returned, false, 2)
        assertFalse(evaluation.viewportPositionInvalidated)
        assertEquals(verified, evaluation.nextCandidate)
    }

    @Test
    fun identityReturnRejectsChangedPositionAmbiguityUnreadAndStaleGeneration() {
        for (scenario in 0..8) {
            val executor = PrivateMessagesTraversalTaskExecutor()
            val original = candidate(1, "Alice", ChatUnreadStatus.UNREAD)
            val other = candidate(0, "Bob", ChatUnreadStatus.READ)
            val oldKey = key(original)
            executor.lockedUnreadSessionKeys += oldKey
            executor.lastStableSessionListVisibleKeys = executor.visibleSessionKeys(snapshot(other, original))
            assertTrue(executor.beginIdentityReturn(original, 4))
            val unread = when (scenario) {
                2 -> ChatUnreadStatus.READ
                5 -> ChatUnreadStatus.UNKNOWN
                else -> ChatUnreadStatus.UNREAD
            }
            val verified = candidate(1, "Alice", unread).apply {
                contactIdentity = ContactRowIdentity("s", "u", "contact-1", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
            }
            val currentOther = if (scenario == 0) candidate(0, "Bob", ChatUnreadStatus.READ, top = 120) else other
            val returned = when (scenario) {
                1 -> snapshot(currentOther, verified, candidate(2, "Alice", ChatUnreadStatus.UNREAD))
                6 -> snapshot(currentOther)
                7 -> snapshot(currentOther, verified).copy(activityClass = "OtherActivity")
                8 -> snapshot(currentOther, verified).copy(chatListExtractionTruncated = true)
                else -> snapshot(currentOther, verified)
            }
            if (scenario == 4) executor.attemptedSessionKeys += oldKey
            val result = executor.reconcileIdentityReturn(returned.copy(windowGeneration = 1), if (scenario == 3) 5 else 4)
            assertEquals(PrivateMessagesTraversalTaskExecutor.IdentityReturnStatus.REJECTED, result.status)
            assertEquals(setOf(oldKey), executor.lockedUnreadSessionKeys)
        }
    }

    @Test
    fun identityReturnWaitsForVerifiedIdentityAndResetInvalidatesIt() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val original = candidate(0, "Alice", ChatUnreadStatus.UNREAD)
        executor.lockedUnreadSessionKeys += key(original)
        executor.lastStableSessionListVisibleKeys = executor.visibleSessionKeys(snapshot(original))
        assertTrue(executor.beginIdentityReturn(original, 4))
        assertEquals(PrivateMessagesTraversalTaskExecutor.IdentityReturnStatus.WAITING,
            executor.reconcileIdentityReturn(snapshot(original).copy(windowGeneration = 1), 4).status)
        executor.reset()
        assertFalse(executor.identityReturnPending)
        assertEquals(PrivateMessagesTraversalTaskExecutor.IdentityReturnStatus.REJECTED,
            executor.reconcileIdentityReturn(snapshot(original).copy(windowGeneration = 1), 4).status)
    }

    @Test
    fun unknownAdmissionAllowsIndependentRowsAndClearsOnlyOnExplicitIdentityEvidence() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
        }
        val known = candidate(0, "Known", ChatUnreadStatus.UNREAD)
        val unresolved = candidate(1, "Unknown", ChatUnreadStatus.UNKNOWN)
        val blocked = executor.evaluateListSnapshot(snapshot(known, unresolved), false, 2)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING, blocked.unreadAdmissionDecision)
        assertTrue(executor.normalInboxUnreadStateUnknown)
        assertEquals(setOf(key(unresolved)), executor.unresolvedUnknownUnreadKeys)

        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(executor.checkpoint()) }
        val resolved = candidate(1, "Unknown", ChatUnreadStatus.READ)
        val result = restored.evaluateListSnapshot(snapshot(known, resolved), false, 2)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING, result.unreadAdmissionDecision)
        assertFalse(restored.normalInboxUnreadStateUnknown)
        assertTrue(restored.unresolvedUnknownUnreadKeys.isEmpty())
        assertEquals(setOf(key(known)), restored.lockedUnreadSessionKeys)
    }

    @Test
    fun unknownAdmissionCannotClearOnDifferentViewportOrTruncatedOrStillUnknown() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
        }
        val known = candidate(0, "Known", ChatUnreadStatus.UNREAD)
        val unknown = candidate(1, "Unknown", ChatUnreadStatus.UNKNOWN)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateListSnapshot(snapshot(known, unknown), false, 2).unreadAdmissionDecision)

        val different = snapshot(candidate(0, "Other", ChatUnreadStatus.UNREAD))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateListSnapshot(different, false, 2).unreadAdmissionDecision)
        assertTrue(executor.normalInboxUnreadStateUnknown)

        val stillUnknown = snapshot(known, unknown)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateListSnapshot(stillUnknown, false, 2).unreadAdmissionDecision)
        val resolvedButIncomplete = snapshot(known, candidate(1, "Unknown", ChatUnreadStatus.READ))
            .copy(chatListExtractionTruncated = true)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
            executor.evaluateListSnapshot(resolvedButIncomplete, false, 2).unreadAdmissionDecision)
        assertTrue(executor.normalInboxUnreadStateUnknown)
    }

    @Test
    fun legacyCheckpointNeedsOriginalStableViewportBeforeUnknownIsCleared() {
        val legacy = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            phase = SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY
            normalInboxUnreadStateUnknown = true
            lastStableSessionListVisibleKeys = visibleSessionKeys(snapshot(
                candidate(0, "Known", ChatUnreadStatus.UNREAD),
                candidate(1, "Unknown", ChatUnreadStatus.UNKNOWN)
            ))
        }.checkpoint()
        val executor = PrivateMessagesTraversalTaskExecutor().apply { restore(legacy) }
        val changed = snapshot(candidate(4, "Different", ChatUnreadStatus.UNREAD))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
            executor.evaluateUnreadAdmission(changed))
        val same = snapshot(
            candidate(0, "Known", ChatUnreadStatus.UNREAD),
            candidate(1, "Unknown", ChatUnreadStatus.READ)
        )
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(same))
        assertFalse(executor.normalInboxUnreadStateUnknown)
    }

    @Test
    fun zeroEligibleUnknownIsNotEnoughWhenOriginalUnknownRowBecomesIncomplete() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
        }
        val known = candidate(0, "Known", ChatUnreadStatus.UNREAD)
        val unknown = candidate(1, "Uncertain", ChatUnreadStatus.UNKNOWN)
        val first = snapshot(known, unknown)
        executor.recordViewportUnreadEvidence(first, receivedGreetingsActive = false)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateListSnapshot(first, false, 2).unreadAdmissionDecision)

        // The row still has an unknown status, but is now ineligible due to missing geometry.
        // It is unsafe to declare the old evidence resolved just because eligibleUnknown=0.
        val incomplete = snapshot(known, candidate(1, "Uncertain", ChatUnreadStatus.UNKNOWN, previewBoundsValid = false))
        executor.recordViewportUnreadEvidence(incomplete, receivedGreetingsActive = false)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateListSnapshot(incomplete, false, 2).unreadAdmissionDecision)
        assertTrue(executor.normalInboxUnreadStateUnknown)
        assertEquals(setOf(key(unknown)), executor.unresolvedUnknownUnreadKeys)
    }

    @Test
    fun legacyCheckpointDoesNotReleaseIfPrivateUnknownRowIsNowIneligible() {
        val known = candidate(0, "Known", ChatUnreadStatus.UNREAD)
        val unresolved = candidate(1, "Unknown", ChatUnreadStatus.UNKNOWN)
        val old = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            phase = SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY
            normalInboxUnreadStateUnknown = true
            lastStableSessionListVisibleKeys = visibleSessionKeys(snapshot(known, unresolved))
        }.checkpoint()
        val executor = PrivateMessagesTraversalTaskExecutor().apply { restore(old) }
        val stillUnknown = snapshot(known, candidate(1, "Unknown", ChatUnreadStatus.UNKNOWN, previewBoundsValid = false))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
            executor.evaluateUnreadAdmission(stillUnknown))
        assertTrue(executor.normalInboxUnreadStateUnknown)
    }

    @Test
    fun confirmedTopAndTriStateUnreadAdmissionRemainDistinct() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val unread = candidate(0, "Unread", ChatUnreadStatus.UNREAD)
        val unknown = candidate(1, "Unknown", ChatUnreadStatus.UNKNOWN)

        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_TOP_CONFIRMATION,
            executor.evaluateUnreadAdmission(snapshot(unread))
        )
        executor.initialInboxTopPositioningCompleted = true
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(unread, unknown))
        )
        assertEquals(setOf(key(unread)), executor.lockedUnreadSessionKeys)
        assertTrue(executor.normalInboxUnreadStateUnknown)

        executor.reset()
        executor.initialInboxTopPositioningCompleted = true
        executor.atTop = true
        val readTop = snapshot(candidate(2, "Read", ChatUnreadStatus.READ))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            executor.evaluateUnreadAdmission(readTop))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.NO_UNREAD,
            executor.evaluateUnreadAdmission(readTop))

        executor.reset()
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(unread))
        )
        assertEquals(setOf(key(unread)), executor.lockedUnreadSessionKeys)
        assertEquals(SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY, executor.phase)

        val read = candidate(2, "Read", ChatUnreadStatus.READ, top = 700)
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            executor.evaluateUnreadAdmission(snapshot(read))
        )
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED,
            executor.evaluateUnreadAdmission(snapshot(read))
        )
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
        assertTrue(executor.lockedUnreadSetFinalized)
    }

    @Test
    fun listSnapshotEvaluationOwnsUnreadAdmissionAndFrozenCandidateSelection() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
        }
        val top = candidate(0, "Top", ChatUnreadStatus.UNREAD, top = 100)
        val bottom = candidate(1, "Bottom", ChatUnreadStatus.UNREAD, top = 400)

        val first = executor.evaluateListSnapshot(
            snapshot(bottom, top),
            wasWaitingForUnread = false,
            maxNoProgressScrolls = 2
        )
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING, first.unreadAdmissionDecision)
        assertEquals(null, first.nextCandidate)
        assertEquals(setOf(key(bottom), key(top)), executor.lockedUnreadSessionKeys)
        assertTrue(executor.initialUnreadGateSwipeCompleted)

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
    fun startupUnreadAdmissionAndCandidateSelectionWaitForTopPositionConfirmation() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val unread = candidate(0, "Unread", ChatUnreadStatus.UNREAD)

        val beforeTopConfirmation = executor.evaluateListSnapshot(
            snapshot(unread),
            wasWaitingForUnread = false,
            maxNoProgressScrolls = 2
        )

        assertEquals(null, beforeTopConfirmation.unreadAdmissionDecision)
        assertEquals(null, beforeTopConfirmation.nextCandidate)
        assertTrue(executor.lockedUnreadSessionKeys.isEmpty())

        executor.initialInboxTopPositioningCompleted = true
        val afterTopConfirmation = executor.evaluateListSnapshot(
            snapshot(unread),
            wasWaitingForUnread = false,
            maxNoProgressScrolls = 2
        )

        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            afterTopConfirmation.unreadAdmissionDecision
        )
        assertEquals(null, afterTopConfirmation.nextCandidate)
        assertEquals(setOf(key(unread)), executor.lockedUnreadSessionKeys)
        assertTrue(executor.initialUnreadGateSwipeCompleted)
    }

    @Test
    fun listSnapshotEvaluationDoesNotRetireAttemptedRowsBeforeVerifiedNewRound() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
        }
        executor.executionState = SessionManagerState.WAITING_FOR_UNREAD
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.atTop = true
        val alreadyAttempted = candidate(0, "Already attempted", ChatUnreadStatus.UNREAD)
        val newlyVisible = candidate(1, "New C", ChatUnreadStatus.UNREAD)
        executor.attemptedSessionKeys += key(alreadyAttempted)
        executor.lockedUnreadSessionKeys += key(alreadyAttempted)
        executor.lockedUnreadSetFinalized = true

        val evaluation = executor.evaluateListSnapshot(
            snapshot(alreadyAttempted, newlyVisible),
            wasWaitingForUnread = true,
            maxNoProgressScrolls = 2
        )

        assertTrue(evaluation.wasWaitingForUnread)
        assertTrue(evaluation.explicitUnreadWhileWaiting)
        // Merely observing C is not permission to abandon A's original-round receipt.
        assertEquals(setOf(key(alreadyAttempted)), executor.attemptedSessionKeys)
        assertEquals(setOf(key(alreadyAttempted)), executor.lockedUnreadSessionKeys)
        assertEquals(null, evaluation.nextCandidate)
        assertTrue(key(newlyVisible) !in executor.lockedUnreadSessionKeys)
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
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
        assertEquals(listOf("old-viewport"), executor.lastStableSessionListVisibleKeys)
        assertFalse(executor.atTop)
        assertEquals(0, executor.noProgressScrollCount)
        assertEquals(0, executor.sessionListStabilityRetryCount)
        assertTrue(executor.normalInboxHadUnread)
        assertEquals(setOf("already-processed"), executor.attemptedSessionKeys)
    }

    @Test
    fun expectedSessionReturnViewportChangeKeepsBottomToTopScanPosition() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val previous = candidate(0, "Processed", ChatUnreadStatus.READ, top = 100)
        val next = candidate(1, "Next unread", ChatUnreadStatus.UNREAD, top = 400)
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.initialInboxTopPositioningCompleted = true
        executor.lockedUnreadSetFinalized = true
        executor.lastStableSessionListVisibleKeys = executor.visibleSessionKeys(snapshot(previous))
        executor.attemptedSessionKeys += key(previous)

        val evaluation = executor.evaluateListSnapshot(
            snapshot(previous, next),
            wasWaitingForUnread = false,
            maxNoProgressScrolls = 2,
            allowExpectedSessionReturnViewportChange = true
        )

        assertFalse(evaluation.viewportPositionInvalidated)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
        assertEquals(next, evaluation.nextCandidate)
    }

    @Test
    fun localRowReflowAndCardInsertionKeepConfirmedUnreadBoundaryAndCursor() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val done = candidate(0, "Already processed", ChatUnreadStatus.READ, top = 100)
        val pending = candidate(1, "Pending unread", ChatUnreadStatus.UNREAD, top = 300)
        val before = snapshot(done, pending).copy(
            windowGeneration = 1L,
            chatListCollectionRowCount = 67,
            chatListVisibleCollectionRows = (5..14).toList(),
            chatListCanScrollForward = true,
            chatListCanScrollBackward = true
        )
        executor.attemptedSessionKeys += key(done)
        executor.lockedUnreadSessionKeys += setOf(key(done), key(pending))
        executor.recordVerifiedViewport(before)
        val moved = candidate(3, "Pending unread", ChatUnreadStatus.UNREAD, top = 340)
        val refreshed = before.copy(
            chatListCollectionRowCount = 68,
            chatListVisibleCollectionRows = (6..15).toList(),
            chatListCandidates = listOf(candidate(2, "Already processed", ChatUnreadStatus.READ, top = 140), moved)
        )
        val result = executor.evaluateListSnapshot(refreshed, false, 2)
        assertFalse(result.viewportPositionInvalidated)
        assertEquals(moved, result.nextCandidate)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
        assertTrue(executor.lockedUnreadSetFinalized)
        assertEquals(setOf(key(done)), executor.attemptedSessionKeys)
        assertEquals(setOf(key(done), key(pending)), executor.lockedUnreadSessionKeys)

        // Reordering within the same independently observed collection range is
        // a local content mutation, not a reason to restart the downward search.
        val reordered = refreshed.copy(chatListCandidates = listOf(
            candidate(2, "Pending unread", ChatUnreadStatus.UNREAD, top = 140),
            candidate(3, "Already processed", ChatUnreadStatus.READ, top = 340)
        ))
        val afterReorder = executor.evaluateListSnapshot(reordered, false, 2)
        assertFalse(afterReorder.viewportPositionInvalidated)
        assertEquals("Pending unread", afterReorder.nextCandidate?.nameText)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
    }

    @Test
    fun unassociatedCollectionPositionCannotSelectOrReplaceCheckpointBeforeRecovery() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val pending = candidate(1, "Pending", ChatUnreadStatus.UNREAD)
        val old = snapshot(pending).copy(
            windowGeneration = 1L,
            chatListCollectionRowCount = 67,
            chatListVisibleCollectionRows = (13..22).toList(),
            chatListCanScrollForward = true,
            chatListCanScrollBackward = true
        )
        executor.lockedUnreadSessionKeys += key(pending)
        executor.attemptedSessionKeys += "processed"
        executor.recordVerifiedViewport(old)
        val other = candidate(0, "Unassociated", ChatUnreadStatus.UNREAD)
        val fresh = old.copy(
            chatListCandidates = listOf(other),
            chatListVisibleCollectionRows = (0..7).toList(),
            chatListCanScrollBackward = false
        )
        val decision = executor.evaluateListSnapshot(fresh, false, 2)
        assertTrue(decision.viewportPositionInvalidated)
        assertEquals(null, decision.nextCandidate)
        assertEquals(null, decision.unreadAdmissionDecision)
        assertFalse(executor.atTop)
        assertEquals(executor.visibleSessionKeys(old), executor.checkpoint().lastStableVisibleKeys)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
        assertEquals(setOf(key(pending)), executor.lockedUnreadSessionKeys)
        assertEquals(setOf("processed"), executor.attemptedSessionKeys)
        // Page recovery must not accept a disconnected position as the scan origin.
        executor.beginScanContinuityRecovery()
        val positioning = executor.evaluateListSnapshot(fresh, false, 2)
        assertFalse(positioning.viewportPositionInvalidated)
        assertEquals(null, positioning.nextCandidate)
        assertTrue(executor.scanContinuityRecoveryPending)
        assertEquals(executor.visibleSessionKeys(old), executor.checkpoint().lastStableVisibleKeys)
    }

    @Test
    fun collectionIndexShiftWithoutNewIdentityAndReverseMovementAreNotScrollProgress() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val row = candidate(0, "Same", ChatUnreadStatus.UNREAD)
        val before = snapshot(row).copy(
            windowGeneration = 1L,
            chatListCollectionRowCount = 67,
            chatListVisibleCollectionRows = (5..14).toList(),
            chatListCanScrollForward = true,
            chatListCanScrollBackward = true
        )
        val insertedCard = before.copy(
            chatListCollectionRowCount = 68,
            chatListVisibleCollectionRows = (6..15).toList(),
            chatListCandidates = listOf(candidate(1, "Same", ChatUnreadStatus.UNREAD, top = 250))
        )
        executor.pendingScrollBaseline = executor.scrollPositionKeys(before)
        assertEquals(PrivateMessagesScrollObservationDecision.BOTTOM_NO_PROGRESS,
            executor.consumePendingScrollObservation(executor.scrollPositionKeys(insertedCard), 2, false))
        executor.pendingScrollBaseline = executor.scrollPositionKeys(before)
        executor.pendingScrollToTop = true
        val reverse = before.copy(
            chatListVisibleCollectionRows = (13..22).toList(),
            chatListCandidates = listOf(candidate(2, "Other", ChatUnreadStatus.UNREAD))
        )
        assertEquals(PrivateMessagesScrollObservationDecision.POSITION_UNVERIFIED,
            executor.consumePendingScrollObservation(executor.scrollPositionKeys(reverse), 2, false))
        assertEquals(null, executor.pendingScrollBaseline)
    }

    @Test
    fun collectionGapInRequestedDirectionIsNotProofOfScanContinuity() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val before = snapshot(candidate(0, "Original", ChatUnreadStatus.UNREAD)).copy(
            windowGeneration = 1L,
            chatListCollectionRowCount = 67,
            chatListVisibleCollectionRows = (20..29).toList(),
            chatListCanScrollForward = true,
            chatListCanScrollBackward = true
        )
        val gap = before.copy(
            chatListVisibleCollectionRows = (5..14).toList(),
            chatListCandidates = listOf(candidate(1, "After gap", ChatUnreadStatus.UNREAD))
        )
        executor.pendingScrollBaseline = executor.scrollPositionKeys(before)
        executor.pendingScrollToTop = true
        assertEquals(PrivateMessagesScrollObservationDecision.POSITION_UNVERIFIED,
            executor.consumePendingScrollObservation(executor.scrollPositionKeys(gap), 2, false))
        assertEquals(null, executor.pendingScrollBaseline)
    }

    @Test
    fun verifiedChatReturnKeepsDirectionButRevokesOldTopEvidence() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
            atTop = true
        }
        val previous = candidate(0, "Processed", ChatUnreadStatus.READ)
        val next = candidate(1, "Remaining", ChatUnreadStatus.UNREAD)
        executor.recordVerifiedViewport(snapshot(previous))
        executor.attemptedSessionKeys += key(previous)
        executor.lockedUnreadSessionKeys += setOf(key(previous), key(next))
        val fresh = snapshot(previous, next).copy(
            windowGeneration = 1L,
            chatListCollectionRowCount = 67,
            chatListVisibleCollectionRows = (5..14).toList(),
            chatListCanScrollForward = true,
            chatListCanScrollBackward = true
        )
        val result = executor.evaluateListSnapshot(fresh, false, 2,
            allowExpectedSessionReturnViewportChange = true)
        assertFalse(result.viewportPositionInvalidated)
        assertFalse(executor.atTop)
        assertEquals(next, result.nextCandidate)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
        assertEquals(setOf(key(previous)), executor.attemptedSessionKeys)
        assertTrue(executor.lockedUnreadSetFinalized)
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
    fun mixedBadgeRowRemainsPendingAcrossReadBoundaryAndReturnTraversal() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
        }
        val known = candidate(0, "Known", ChatUnreadStatus.UNREAD)
        val edge = candidate(1, "Edge", ChatUnreadStatus.UNKNOWN, badgePresent = true)
        val mixed = executor.evaluateListSnapshot(snapshot(known, edge), false, 2)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING, mixed.unreadAdmissionDecision)
        assertEquals(null, mixed.nextCandidate)
        val suffix = snapshot(candidate(2, "Read", ChatUnreadStatus.READ), candidate(3, "Read2", ChatUnreadStatus.READ))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            executor.evaluateListSnapshot(suffix, false, 2).unreadAdmissionDecision)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED,
            executor.evaluateListSnapshot(suffix, false, 2).unreadAdmissionDecision)
        assertTrue(executor.unresolvedIncompleteUnreadKeys.isNotEmpty())
        executor.pendingScrollBaseline = executor.visibleSessionKeys(suffix)
        executor.pendingScrollToTop = true
        assertEquals(known, executor.evaluateListSnapshot(snapshot(known, edge), false, 2).nextCandidate)
    }

    @Test
    fun boundaryPhaseDoesNotExposeCandidateUntilReadSuffixIsStable() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
        }
        val unread = candidate(0, "Unread", ChatUnreadStatus.UNREAD, top = 100)
        val read = candidate(1, "Read", ChatUnreadStatus.READ, top = 400)

        val first = executor.evaluateListSnapshot(snapshot(unread, read), false, 2)
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            first.unreadAdmissionDecision
        )
        assertEquals(null, first.nextCandidate)

        val boundaryCandidate = executor.evaluateListSnapshot(snapshot(read), false, 2)
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            boundaryCandidate.unreadAdmissionDecision
        )
        assertEquals(null, boundaryCandidate.nextCandidate)

        val confirmed = executor.evaluateListSnapshot(snapshot(read), false, 2)
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED,
            confirmed.unreadAdmissionDecision
        )
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
        assertEquals(null, confirmed.nextCandidate)
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
        assertFalse(executor.initialInboxTopPositioningCompleted)
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
        executor.initialInboxTopPositioningCompleted = true
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

        executor.resetSelectionForNewPass()

        assertEquals(SessionListTraversalPhase.POSITIONING_AT_BOTTOM, executor.phase)
        assertFalse(executor.atTop)
        assertTrue(executor.initialInboxPositionCheckCompleted)
        assertTrue(executor.initialInboxTopPositioningCompleted)
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

    @Test
    fun pollingWakeupUsesExistingUnreadUnknownViewportLatch() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val unknown = candidate(0, "Alice", ChatUnreadStatus.UNKNOWN, top = 100)
        val explicit = candidate(0, "Alice", ChatUnreadStatus.READ, top = 100)
        val different = candidate(1, "Bob", ChatUnreadStatus.READ, top = 100)

        assertTrue(executor.observeUnreadBlockers(snapshot(unknown)))
        assertTrue(executor.observeUnreadBlockers(snapshot(different)))
        assertTrue(executor.unresolvedUnknownUnreadKeys.isNotEmpty())
        assertFalse(executor.observeUnreadBlockers(snapshot(explicit)))
        assertTrue(executor.unresolvedUnknownUnreadKeys.isEmpty())
    }

    @Test
    fun newPollingRoundRetiresOnlyRoundSelectionAndRequiresFreshAdmission() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.initialInboxPositionCheckCompleted = true
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        executor.lockedUnreadSetFinalized = true
        executor.attemptedSessionKeys += "old-attempt"
        executor.lockedUnreadSessionKeys += "old-unread"
        executor.ambiguousSessionIdentityKeys += "run-blocked"
        executor.pendingBoundaryConfirmationKeys = listOf("old-boundary")
        executor.capturedSessionListFingerprints += "prior-round-viewport"
        executor.checkpointLastSuccessfulTaskId = "verified-send"
        executor.checkpointRunGeneration = 7L
        executor.checkpointNextCursorKey = "old-cursor"

        executor.renewExhaustedPrivateSelection()

        assertFalse(executor.initialInboxPositionCheckCompleted)
        assertFalse(executor.initialInboxTopPositioningCompleted)
        assertFalse(executor.initialUnreadGateSwipeCompleted)
        assertFalse(executor.lockedUnreadSetFinalized)
        assertTrue(executor.attemptedSessionKeys.isEmpty())
        assertTrue(executor.lockedUnreadSessionKeys.isEmpty())
        assertEquals(null, executor.pendingBoundaryConfirmationKeys)
        assertTrue("Prior-round captures cannot suppress new-cycle sampling",
            executor.capturedSessionListFingerprints.isEmpty())
        assertEquals(null, executor.checkpointNextCursorKey)
        assertEquals(setOf("run-blocked"), executor.ambiguousSessionIdentityKeys)
        assertEquals("verified-send", executor.checkpointLastSuccessfulTaskId)
        assertEquals(7L, executor.checkpointRunGeneration)
    }

    @Test
    fun resumedPrivateRoundPreservesOldSelectionWhileNewRoundAdmitsNewC() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val a = candidate(0, "Old A", ChatUnreadStatus.READ)
        val b = candidate(1, "Old B", ChatUnreadStatus.READ)
        val c = candidate(2, "New C", ChatUnreadStatus.UNREAD)
        val staleViewport = executor.visibleSessionKeys(snapshot(a, b))
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.lockedUnreadSetFinalized = true
        executor.attemptedSessionKeys += setOf(key(a), key(b))
        executor.lockedUnreadSessionKeys += setOf(key(a), key(b))
        executor.capturedSessionListFingerprints += "old-snapshot-capture"
        executor.lastStableSessionListVisibleKeys = staleViewport
        executor.checkpointRunGeneration = 42L
        executor.checkpointLastSuccessfulTaskId = "confirmed-a-or-b-receipt"
        executor.checkpointNextCursorKey = key(b)

        // A verified return from greetings is still the SAME round; old gesture
        // and boundary samples, unlike business progress, are never reusable.
        executor.pendingScrollBaseline = staleViewport
        executor.pendingScrollToTop = true
        executor.pendingBoundaryConfirmationKeys = staleViewport
        executor.pendingBoundaryConfirmationToTop = true
        executor.noProgressScrollCount = 2
        executor.resumeAfterReturnedInbox()
        assertEquals(null, executor.pendingScrollBaseline)
        assertFalse(executor.pendingScrollToTop)
        assertEquals(null, executor.pendingBoundaryConfirmationKeys)
        assertFalse(executor.pendingBoundaryConfirmationToTop)
        assertEquals(0, executor.noProgressScrollCount)
        assertEquals(staleViewport, executor.lastStableSessionListVisibleKeys)
        assertEquals(setOf(key(a), key(b)), executor.attemptedSessionKeys)
        assertEquals(setOf(key(a), key(b)), executor.lockedUnreadSessionKeys)
        assertEquals(setOf("old-snapshot-capture"), executor.capturedSessionListFingerprints)
        assertEquals(key(b), executor.checkpointNextCursorKey)
        assertEquals(null, executor.selectNextCandidate(
            snapshot(a, b, c).chatListCandidates, executor.lockedUnreadSessionKeys
        ))

        // Only a separately verified next-round entry retires frozen membership.
        executor.renewExhaustedPrivateSelection()
        assertTrue(executor.attemptedSessionKeys.isEmpty())
        assertTrue(executor.lockedUnreadSessionKeys.isEmpty())
        assertTrue(executor.capturedSessionListFingerprints.isEmpty())
        assertFalse(executor.lockedUnreadSetFinalized)
        assertEquals("confirmed-a-or-b-receipt", executor.checkpointLastSuccessfulTaskId)
        assertEquals(42L, executor.checkpointRunGeneration)
        assertEquals(null, executor.checkpointNextCursorKey)

        // A/B are read; C arrives unread. The confirmed-top observation admits C immediately.
        executor.initialInboxTopPositioningCompleted = true
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(a, b, c)))
        assertEquals(setOf(key(c)), executor.lockedUnreadSessionKeys)
        assertTrue(executor.initialUnreadGateSwipeCompleted)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(a, b, c)))
        assertEquals(setOf(key(c)), executor.lockedUnreadSessionKeys)
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.lockedUnreadSetFinalized = true
        val next = executor.evaluateListSnapshot(snapshot(a, b, c), false, 2)
        assertEquals(c, next.nextCandidate)
        assertTrue(executor.capturedSessionListFingerprints.add(next.structuralFingerprint))
        assertFalse("A viewport is captured only once within the NEW round",
            executor.capturedSessionListFingerprints.add(next.structuralFingerprint))
        assertEquals("confirmed-a-or-b-receipt", executor.checkpoint().lastSuccessfulTaskId)
    }

    @Test
    fun unknownUnreadBlocksNewRoundWithoutClearingPreviousCursor() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.attemptedSessionKeys += "previously-attempted"
        executor.lockedUnreadSessionKeys += "previously-frozen"
        executor.capturedSessionListFingerprints += "previous-viewport"
        executor.checkpointLastSuccessfulTaskId = "previous-confirmed-send"
        executor.normalInboxUnreadStateUnknown = true
        executor.unresolvedUnknownUnreadKeys += "unresolved"

        assertTrue(runCatching { executor.renewExhaustedPrivateSelection() }.isFailure)
        assertEquals(setOf("previously-attempted"), executor.attemptedSessionKeys)
        assertEquals(setOf("previously-frozen"), executor.lockedUnreadSessionKeys)
        assertEquals(setOf("previous-viewport"), executor.capturedSessionListFingerprints)
        assertEquals(setOf("unresolved"), executor.unresolvedUnknownUnreadKeys)
        assertEquals("previous-confirmed-send", executor.checkpointLastSuccessfulTaskId)
    }

    @Test
    fun timedPollingRoundCollectsUnreadAndStopsAtConfirmedReadViewport() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val visibleRead = candidate(0, "Visible read A", ChatUnreadStatus.READ)
        val initialUnread = candidate(1, "Visible unread B", ChatUnreadStatus.UNREAD)
        val offscreenUnread = candidate(2, "Offscreen unread C", ChatUnreadStatus.UNREAD)
        val furtherRead = candidate(3, "Further read D", ChatUnreadStatus.READ)

        executor.initialInboxTopPositioningCompleted = true
        executor.atTop = true
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            executor.evaluateUnreadAdmission(snapshot(visibleRead)))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.NO_UNREAD,
            executor.evaluateUnreadAdmission(snapshot(visibleRead)))
        executor.renewExhaustedPrivateSelection()
        assertFalse(executor.incompleteUnreadRelocationActive)
        executor.initialInboxTopPositioningCompleted = true
        executor.atTop = true

        // Collect unread evidence from the very first verified top snapshot.
        executor.recordViewportUnreadEvidence(snapshot(visibleRead, initialUnread), false)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(visibleRead, initialUnread)))
        assertEquals(setOf(key(initialUnread)), executor.lockedUnreadSessionKeys)
        assertFalse(executor.lockedUnreadSetFinalized)

        executor.atTop = false
        executor.recordViewportUnreadEvidence(snapshot(offscreenUnread), false)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(offscreenUnread)))
        assertEquals(setOf(key(initialUnread), key(offscreenUnread)), executor.lockedUnreadSessionKeys)
        assertTrue(executor.normalInboxHadUnread)

        // The read viewport stops the search after independent stable observations.
        executor.recordViewportUnreadEvidence(snapshot(furtherRead), false)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            executor.evaluateUnreadAdmission(snapshot(furtherRead)))
        assertTrue(executor.normalInboxHadUnread)
        assertFalse(executor.lockedUnreadSetFinalized)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED,
            executor.evaluateUnreadAdmission(snapshot(furtherRead)))
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
        assertTrue(executor.lockedUnreadSetFinalized)
        val evaluation = executor.evaluateListSnapshot(snapshot(offscreenUnread), false, 2)
        assertEquals(offscreenUnread, evaluation.nextCandidate)
        assertFalse(executor.incompleteUnreadRelocationActive)
    }

    @Test
    fun timedPollingCheckpointDoesNotRestoreObsoleteFullScanAndUnknownBlocksCompletion() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.checkpointLastSuccessfulTaskId = "confirmed-send-prior-round"
        executor.renewExhaustedPrivateSelection()
        executor.initialInboxTopPositioningCompleted = true
        executor.atTop = true
        val read = candidate(0, "Read only here", ChatUnreadStatus.READ)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            executor.evaluateUnreadAdmission(snapshot(read)))

        val saved = executor.checkpoint()
        assertFalse(saved.fullListPollingRoundRequired)
        val resumed = PrivateMessagesTraversalTaskExecutor().apply {
            restore(saved.copy(fullListPollingRoundRequired = true))
        }
        assertFalse(resumed.incompleteUnreadRelocationActive)
        assertEquals("confirmed-send-prior-round", resumed.checkpointLastSuccessfulTaskId)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            resumed.evaluateUnreadAdmission(snapshot(read)))

        val unknown = candidate(1, "Unread evidence unknown", ChatUnreadStatus.UNKNOWN)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
            resumed.evaluateUnreadAdmission(snapshot(unknown)))
        assertTrue(resumed.normalInboxUnreadStateUnknown)
        assertEquals(setOf(key(unknown)), resumed.unresolvedUnknownUnreadKeys)
        assertTrue(runCatching { resumed.renewExhaustedPrivateSelection() }.isFailure)
        assertFalse(resumed.incompleteUnreadRelocationActive)
        assertEquals("confirmed-send-prior-round", resumed.checkpointLastSuccessfulTaskId)
        executor.reset()
        assertFalse(executor.incompleteUnreadRelocationActive)
    }

    @Test
    fun clampedEdgeWithoutBadgeDoesNotCreateDurableUnreadCandidate() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        fun clampedEdge(unread: ChatUnreadStatus, badgePresent: Boolean = false) = candidate(
            1, "Edge", unread, top = 1540, rowHeight = 60,
            previewBoundsValid = false,
            badgePresent = badgePresent,
            previewBounds = ScreenRect(150, 1620, 620, 1600),
            previewBoundsIntersectViewport = false,
            previewBoundsFullyInsideViewport = false
        )
        val edge = clampedEdge(ChatUnreadStatus.UNKNOWN)
        val healthy = candidate(0, "Healthy", ChatUnreadStatus.UNREAD)
        assertFalse(executor.observeIncompleteUnreadEvidence(snapshot(healthy, edge)))
        assertTrue(executor.unresolvedIncompleteUnreadKeys.isEmpty())
        assertTrue(executor.ambiguousSessionIdentityKeys.isEmpty())
        assertEquals(healthy, executor.selectNextCandidate(listOf(healthy, edge)))
        assertTrue(executor.attemptedSessionKeys.isEmpty())

        // Positive badge evidence must still retain the clipped row for reinspection.
        val unreadEdge = clampedEdge(ChatUnreadStatus.UNREAD, badgePresent = true)
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(healthy, unreadEdge)))
        assertEquals(1, executor.unresolvedIncompleteUnreadKeys.size)
        assertEquals(healthy, executor.selectNextCandidate(listOf(healthy, unreadEdge)))
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(healthy)))
        val repaired = candidate(1, "Edge", ChatUnreadStatus.UNREAD)
        assertFalse(executor.observeIncompleteUnreadEvidence(snapshot(healthy, repaired)))
        assertTrue(executor.unresolvedIncompleteUnreadKeys.isEmpty())
        assertTrue(executor.attemptedSessionKeys.isEmpty())
    }

    @Test
    fun incompleteUnreadPrivateRowIsRetryableAndSurvivesCheckpointUntilOriginalRowRepairs() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val damaged = candidate(0, "Unread but incomplete", ChatUnreadStatus.UNREAD,
            previewBoundsValid = false)
        val damagedResult = executor.evaluateListSnapshot(snapshot(damaged), false, 2)
        assertTrue(damagedResult.incompleteUnreadEvidenceBlocked)
        assertEquals(null, damagedResult.nextCandidate)
        assertTrue("Incomplete unread is not a permanent identity conflict",
            executor.ambiguousSessionIdentityKeys.isEmpty())
        assertTrue("Never count an incomplete row as attempted", executor.attemptedSessionKeys.isEmpty())
        val checkpoint = executor.checkpoint()
        assertEquals(1, checkpoint.unresolvedIncompleteUnreadKeys.size)

        val recovered = PrivateMessagesTraversalTaskExecutor().apply { restore(checkpoint) }
        // Looking at another clean viewport must not erase the unverified row.
        assertTrue(recovered.observeIncompleteUnreadEvidence(snapshot(
            candidate(3, "Other safe row", ChatUnreadStatus.READ)
        )))
        assertTrue(runCatching { recovered.renewExhaustedPrivateSelection() }.isFailure)
        assertEquals(checkpoint.unresolvedIncompleteUnreadKeys, recovered.unresolvedIncompleteUnreadKeys)
        assertTrue(recovered.ambiguousSessionIdentityKeys.isEmpty())

        val repaired = candidate(0, "Unread but incomplete", ChatUnreadStatus.UNREAD,
            previewBoundsValid = true)
        assertFalse("The original row becomes retryable when fresh structure is complete",
            recovered.observeIncompleteUnreadEvidence(snapshot(repaired)))
        assertTrue(recovered.unresolvedIncompleteUnreadKeys.isEmpty())
        assertTrue(recovered.attemptedSessionKeys.isEmpty())
        assertEquals(repaired, recovered.selectNextCandidate(snapshot(repaired).chatListCandidates))
    }

    @Test
    fun t04IncompleteHintCannotBeRetiredByMixedWindowOrNonInboxEvidence() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val damaged = candidate(0, "Pending row", ChatUnreadStatus.UNREAD, previewBoundsValid = false)
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(damaged)))
        val saved = executor.unresolvedIncompleteUnreadKeys.toSet()
        val repaired = candidate(1, "Pending row", ChatUnreadStatus.UNREAD)
        val stale = candidate(1, "Pending row", ChatUnreadStatus.UNREAD, windowGeneration = 2L)
        val healthy = candidate(0, "Other row", ChatUnreadStatus.READ)
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(healthy, stale)))
        assertEquals(saved, executor.unresolvedIncompleteUnreadKeys)
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(repaired).copy(
            pageKind = com.freedom.features.aireply.ports.ChatPageKind.CHAT_DETAIL_CANDIDATE
        )))
        assertEquals(saved, executor.unresolvedIncompleteUnreadKeys)
        assertFalse(executor.observeIncompleteUnreadEvidence(snapshot(healthy, repaired)))
        assertTrue(executor.attemptedSessionKeys.isEmpty())
    }

    @Test
    fun t04ClippedSameTitleCannotReleaseNamedIncompleteHint() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val damaged = candidate(0, "Pending row", ChatUnreadStatus.UNREAD, previewBoundsValid = false)
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(damaged)))
        val saved = executor.unresolvedIncompleteUnreadKeys.toSet()
        val repaired = candidate(0, "Pending row", ChatUnreadStatus.UNREAD)
        val clipped = candidate(1, "Pending row", ChatUnreadStatus.READ, top = 1530,
            rowHeight = 70, clippedAtEdge = true)
        val healthy = candidate(2, "Independent row", ChatUnreadStatus.UNREAD)
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(repaired, clipped, healthy)))
        assertEquals(saved, executor.unresolvedIncompleteUnreadKeys)
        assertTrue(executor.canContinueWithIncompleteUnread(snapshot(repaired, clipped, healthy)))
        assertTrue(executor.attemptedSessionKeys.isEmpty())
        assertFalse(executor.observeIncompleteUnreadEvidence(snapshot(repaired, healthy)))
    }

    @Test
    fun p0NamedIncompleteRowIsLocalAndNeverCompletesTheRound() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val damaged = candidate(0, "Incomplete A", ChatUnreadStatus.UNREAD, previewBoundsValid = false)
        val healthy = candidate(1, "Healthy B", ChatUnreadStatus.UNREAD)
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.lockedUnreadSetFinalized = true
        executor.lockedUnreadSessionKeys += key(healthy)
        val result = executor.evaluateListSnapshot(snapshot(damaged, healthy), false, 2)
        assertTrue(result.incompleteUnreadEvidenceBlocked)
        assertTrue(result.incompleteUnreadAllowsIndependentTraversal)
        assertEquals(healthy, result.nextCandidate)
        assertTrue(executor.attemptedSessionKeys.isEmpty())
        assertTrue(executor.ambiguousSessionIdentityKeys.isEmpty())

        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(executor.checkpoint()) }
        assertTrue(restored.observeIncompleteUnreadEvidence(snapshot(healthy)))
        assertTrue(restored.canContinueWithIncompleteUnread(snapshot(healthy)))
        assertTrue(runCatching { restored.renewExhaustedPrivateSelection() }.isFailure)

        val repaired = candidate(0, "Incomplete A", ChatUnreadStatus.UNREAD)
        assertFalse(restored.observeIncompleteUnreadEvidence(snapshot(repaired, healthy)))
        assertTrue(key(repaired) in restored.lockedUnreadSessionKeys)
        assertEquals(repaired, restored.selectNextCandidate(listOf(repaired), restored.lockedUnreadSessionKeys))
    }

    @Test
    fun p2OriginalIncompleteFaultRequiresItsOwnEvidenceWithoutMutatingCheckpoint() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val damaged = candidate(0, "Incomplete A", ChatUnreadStatus.UNREAD, previewBoundsValid = false)
        val healthy = candidate(1, "Healthy B", ChatUnreadStatus.READ)
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(damaged)))
        val checkpoint = executor.checkpoint()
        fun assess(vararg rows: ChatListCandidateSnapshot) = assessPrivateListRecoveryFault(
            checkpoint, "PRIVATE_INCOMPLETE_UNREAD_ROW", snapshot(*rows)
        )
        assertEquals(PrivateListRecoveryFaultEvidence.ORIGINAL_INCOMPLETE_STILL_BLOCKED, assess(damaged))
        assertEquals(PrivateListRecoveryFaultEvidence.LOCAL_REMEDIATION, assess(damaged, healthy))
        assertEquals(PrivateListRecoveryFaultEvidence.LOCAL_REMEDIATION, assess(healthy))
        assertEquals(PrivateListRecoveryFaultEvidence.CLEARED,
            assess(candidate(0, "Incomplete A", ChatUnreadStatus.UNREAD)))
        assertEquals(checkpoint.unresolvedIncompleteUnreadKeys, executor.unresolvedIncompleteUnreadKeys)
        assertEquals(PrivateListRecoveryFaultEvidence.ORIGINAL_EVIDENCE_MISSING,
            assessPrivateListRecoveryFault(checkpoint.copy(unresolvedIncompleteUnreadKeys = emptySet()),
                "PRIVATE_INCOMPLETE_UNREAD_ROW", snapshot(healthy)))
    }

    @Test
    fun p2OriginalUnknownCannotBeClearedByDifferentCleanViewport() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val unknown = candidate(0, "Unknown A", ChatUnreadStatus.UNKNOWN)
        assertTrue(executor.observeUnreadBlockers(snapshot(unknown)))
        val checkpoint = executor.checkpoint()
        fun assess(vararg rows: ChatListCandidateSnapshot) = assessPrivateListRecoveryFault(
            checkpoint, "INITIAL_UNREAD_STATE_UNKNOWN", snapshot(*rows)
        )
        assertEquals(PrivateListRecoveryFaultEvidence.ORIGINAL_UNKNOWN_STILL_BLOCKED, assess(unknown))
        assertEquals(PrivateListRecoveryFaultEvidence.ORIGINAL_UNKNOWN_STILL_BLOCKED,
            assess(candidate(1, "Different B", ChatUnreadStatus.READ)))
        assertEquals(PrivateListRecoveryFaultEvidence.CLEARED,
            assess(candidate(0, "Unknown A", ChatUnreadStatus.READ)))
        assertEquals(PrivateListRecoveryFaultEvidence.LIST_STRUCTURE_INCOMPLETE,
            assessPrivateListRecoveryFault(checkpoint, "SERVICE_RECONNECT_CHECKPOINT",
                snapshot(candidate(0, "Unknown A", ChatUnreadStatus.READ)).copy(chatListExtractionTruncated = true)))
        assertTrue(executor.normalInboxUnreadStateUnknown)
        assertEquals(checkpoint.unresolvedUnknownUnreadKeys, executor.unresolvedUnknownUnreadKeys)
    }

    @Test
    fun p1RelocationPreservesRoundAndStopsAtRediscoveredPendingTarget() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val damaged = candidate(0, "Incomplete A", ChatUnreadStatus.UNREAD, previewBoundsValid = false)
        val healthy = candidate(1, "Healthy B", ChatUnreadStatus.READ)
        val page = snapshot(damaged, healthy).copy(windowGeneration = 1)
        executor.initialInboxPositionCheckCompleted = true
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.atTop = true
        executor.lockedUnreadSetFinalized = true
        executor.attemptedSessionKeys += key(healthy)
        executor.lockedUnreadSessionKeys += key(healthy)
        executor.ambiguousSessionIdentityKeys += key(candidate(2, "Collision", ChatUnreadStatus.READ))
        executor.checkpointLastSuccessfulTaskId = "confirmed-receipt"
        assertTrue(executor.observeIncompleteUnreadEvidence(page))
        val before = executor.checkpoint()
        assertFalse(executor.canBeginIncompleteUnreadRelocation(page.copy(chatListExtractionTruncated = true)))
        assertTrue(executor.beginIncompleteUnreadRelocation(page))
        val after = executor.checkpoint()
        assertEquals(before.attemptedSessionKeys, after.attemptedSessionKeys)
        assertEquals(before.lockedUnreadSessionKeys, after.lockedUnreadSessionKeys)
        assertEquals(before.ambiguousSessionIdentityKeys, after.ambiguousSessionIdentityKeys)
        assertEquals(before.unresolvedIncompleteUnreadKeys, after.unresolvedIncompleteUnreadKeys)
        assertEquals(before.lastSuccessfulTaskId, after.lastSuccessfulTaskId)
        assertTrue(after.lockedUnreadSetFinalized)
        assertTrue(after.fullListPollingRoundRequired)
        assertFalse(after.atTop)
        assertEquals(SessionListTraversalPhase.POSITIONING_AT_BOTTOM.name, after.phase)
        assertFalse(executor.beginIncompleteUnreadRelocation(page))

        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(after) }
        val repaired = candidate(0, "Incomplete A", ChatUnreadStatus.UNREAD)
        val repairedPage = snapshot(repaired, healthy).copy(windowGeneration = 1)
        assertFalse(restored.observeIncompleteUnreadEvidence(repairedPage))
        assertTrue(key(repaired) in restored.lockedUnreadSessionKeys)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED,
            restored.evaluateUnreadAdmission(repairedPage))
        assertFalse(restored.incompleteUnreadRelocationActive)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, restored.phase)
        assertEquals(repaired, restored.selectNextCandidate(repairedPage.chatListCandidates, restored.lockedUnreadSessionKeys))
        assertEquals(before.attemptedSessionKeys, restored.attemptedSessionKeys)
    }

    @Test
    fun p0IncompleteRowDoesNotAuthorizeUnsafeOrAnonymousViewport() {
        val named = candidate(0, "Incomplete A", ChatUnreadStatus.UNREAD, previewBoundsValid = false)
        val healthy = candidate(1, "Healthy B", ChatUnreadStatus.READ)
        val executor = PrivateMessagesTraversalTaskExecutor()
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(named)))
        assertFalse(executor.canContinueWithIncompleteUnread(snapshot(named)))
        assertFalse(executor.canContinueWithIncompleteUnread(
            snapshot(named, healthy).copy(chatListExtractionTruncated = true)
        ))
        assertFalse(executor.canContinueWithIncompleteUnread(
            snapshot(named, candidate(1, "Healthy B", ChatUnreadStatus.UNKNOWN))
        ))
        val anonymous = candidate(0, "", ChatUnreadStatus.UNREAD)
        val anonymousExecutor = PrivateMessagesTraversalTaskExecutor()
        val result = anonymousExecutor.evaluateListSnapshot(snapshot(anonymous, healthy), false, 2)
        assertTrue(result.incompleteUnreadEvidenceBlocked)
        assertFalse(result.incompleteUnreadAllowsIndependentTraversal)
        assertEquals(null, result.nextCandidate)
    }

    @Test
    fun unnamedIncompleteRowResolvesOnlyWithMatchingFreshGeometryAndReadableEvidence() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val unknownName = candidate(0, "", ChatUnreadStatus.UNKNOWN)
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(unknownName)))
        val savedHints = executor.unresolvedIncompleteUnreadKeys.toSet()
        assertEquals(1, savedHints.size)
        assertTrue(savedHints.single().startsWith("unnamed:"))
        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(executor.checkpoint()) }
        assertTrue(restored.observeIncompleteUnreadEvidence(snapshot(
            candidate(0, "Repaired name", ChatUnreadStatus.UNREAD, top = 500)
        )))
        assertEquals(savedHints, restored.unresolvedIncompleteUnreadKeys)
        assertFalse(restored.observeIncompleteUnreadEvidence(snapshot(
            candidate(0, "Repaired name", ChatUnreadStatus.UNREAD)
        )))
        assertTrue(restored.unresolvedIncompleteUnreadKeys.isEmpty())
    }

    @Test
    fun duplicateTitleWithOneIncompleteRowIsDurablyBlockedButOtherUnreadRemainsEligible() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val complete = candidate(0, "Shared Nickname", ChatUnreadStatus.UNREAD)
        val incomplete = candidate(1, "Shared Nickname", ChatUnreadStatus.UNREAD,
            previewBoundsValid = false)
        val unaffected = candidate(2, "Distinct Nickname", ChatUnreadStatus.UNREAD)
        val conflictingKey = key(complete)
        val result = executor.evaluateListSnapshot(snapshot(complete, incomplete, unaffected),
            wasWaitingForUnread = false, maxNoProgressScrolls = 2)
        assertTrue(result.incompleteUnreadEvidenceBlocked)
        assertTrue(result.newlyAmbiguousKeys.contains(conflictingKey))
        assertTrue(executor.ambiguousSessionIdentityKeys.contains(conflictingKey))
        assertEquals(unaffected, executor.selectNextCandidate(
            listOf(complete, incomplete, unaffected)
        ))

        val recovered = PrivateMessagesTraversalTaskExecutor().apply { restore(executor.checkpoint()) }
        assertTrue(recovered.ambiguousSessionIdentityKeys.contains(conflictingKey))
        assertTrue(recovered.unresolvedIncompleteUnreadKeys.isNotEmpty())
        val repaired = candidate(1, "Shared Nickname", ChatUnreadStatus.UNREAD,
            previewBoundsValid = true)
        assertFalse(recovered.observeIncompleteUnreadEvidence(snapshot(complete, repaired, unaffected)))
        // Identity collision stays isolated even after the transient geometry is repaired.
        assertEquals(unaffected, recovered.selectNextCandidate(
            listOf(complete, repaired, unaffected)
        ))
        recovered.renewExhaustedPrivateSelection()
        assertTrue(recovered.ambiguousSessionIdentityKeys.contains(conflictingKey))
    }

    @Test
    fun p8CombinedRecoveryMustResolveBothBlockersBeforeNextFullPollingRound() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val processedA = candidate(0, "Processed A", ChatUnreadStatus.READ)
        val unknownB = candidate(1, "Unknown B", ChatUnreadStatus.UNKNOWN)
        val incompleteC = candidate(2, "Incomplete C", ChatUnreadStatus.UNREAD,
            previewBoundsValid = false)
        val newD = candidate(3, "New D", ChatUnreadStatus.UNREAD)
        val blockedIdentity = key(candidate(4, "Identity collision", ChatUnreadStatus.UNREAD))

        executor.renewExhaustedPrivateSelection()
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.lockedUnreadSetFinalized = true
        executor.checkpointRunGeneration = 17L
        executor.checkpointLastSuccessfulTaskId = "confirmed-prior-send"
        executor.attemptedSessionKeys += key(processedA)
        executor.lockedUnreadSessionKeys += key(processedA)
        executor.ambiguousSessionIdentityKeys += blockedIdentity
        assertTrue(executor.observeUnreadBlockers(snapshot(unknownB)))
        assertTrue(executor.observeIncompleteUnreadEvidence(snapshot(incompleteC)))

        // Simulate a service/process restart by discarding all in-memory executors.
        val saved = executor.checkpoint()
        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(saved) }
        assertFalse(restored.incompleteUnreadRelocationActive)
        assertEquals(saved.unresolvedUnknownUnreadKeys, restored.unresolvedUnknownUnreadKeys)
        assertEquals(saved.unresolvedIncompleteUnreadKeys, restored.unresolvedIncompleteUnreadKeys)
        assertEquals(saved.attemptedSessionKeys, restored.attemptedSessionKeys)
        assertEquals(saved.lockedUnreadSessionKeys, restored.lockedUnreadSessionKeys)
        assertEquals("confirmed-prior-send", restored.checkpointLastSuccessfulTaskId)
        assertTrue(restored.ambiguousSessionIdentityKeys.contains(blockedIdentity))

        // A different clean viewport cannot release either saved blocker.
        assertTrue(restored.observeUnreadBlockers(snapshot(
            candidate(5, "Different read row", ChatUnreadStatus.READ)
        )))
        assertTrue(restored.observeIncompleteUnreadEvidence(snapshot(
            candidate(5, "Different read row", ChatUnreadStatus.READ)
        )))
        assertTrue(runCatching { restored.renewExhaustedPrivateSelection() }.isFailure)
        assertEquals(setOf(key(processedA)), restored.attemptedSessionKeys)

        // The original UNKNOWN viewport and incomplete row each need new explicit proof.
        assertFalse(restored.observeUnreadBlockers(snapshot(
            candidate(1, "Unknown B", ChatUnreadStatus.READ)
        )))
        assertTrue(restored.observeIncompleteUnreadEvidence(snapshot(
            candidate(5, "Different read row", ChatUnreadStatus.READ)
        )))
        assertTrue(runCatching { restored.renewExhaustedPrivateSelection() }.isFailure)
        assertFalse(restored.observeIncompleteUnreadEvidence(snapshot(
            candidate(2, "Incomplete C", ChatUnreadStatus.UNREAD, previewBoundsValid = true)
        )))

        // Only now may a new round retire old A's selection cursor, not its send receipt
        // or the run-scoped identity conflict.
        restored.renewExhaustedPrivateSelection()
        assertTrue(restored.attemptedSessionKeys.isEmpty())
        assertTrue(restored.lockedUnreadSessionKeys.isEmpty())
        assertFalse(restored.incompleteUnreadRelocationActive)
        assertTrue(restored.ambiguousSessionIdentityKeys.contains(blockedIdentity))
        assertEquals("confirmed-prior-send", restored.checkpointLastSuccessfulTaskId)
        assertEquals(17L, restored.checkpointRunGeneration)
        assertEquals(null, restored.checkpointNextCursorKey)
        restored.initialInboxTopPositioningCompleted = true
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            restored.evaluateUnreadAdmission(snapshot(newD)))
        assertEquals(setOf(key(newD)), restored.lockedUnreadSessionKeys)
        assertTrue(restored.initialUnreadGateSwipeCompleted)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            restored.evaluateUnreadAdmission(snapshot(newD)))
        assertEquals(setOf(key(newD)), restored.lockedUnreadSessionKeys)
        restored.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        restored.lockedUnreadSetFinalized = true
        assertEquals(newD, restored.evaluateListSnapshot(snapshot(newD), false, 2).nextCandidate)
    }

    @Test
    fun p8RestoredReanchorKeepsConfirmedAttemptAndNeverReusesOldSessionNode() {
        val original = candidate(0, "Sent session", ChatUnreadStatus.UNREAD, top = 100)
        val moved = candidate(3, "Sent session", ChatUnreadStatus.UNREAD, top = 900)
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.initialInboxTopPositioningCompleted = true
        executor.lockedUnreadSetFinalized = true
        executor.attemptedSessionKeys += key(original)
        executor.lockedUnreadSessionKeys += key(original)
        executor.checkpointLastSuccessfulTaskId = "confirmed-send-unchanged"
        executor.rememberRecoveryAnchor(original)
        executor.lastStableSessionListVisibleKeys = executor.visibleSessionKeys(snapshot(original))
        executor.beginSession(original, key(original), "obsolete-window")
        executor.pendingScrollBaseline = executor.visibleSessionKeys(snapshot(original))

        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(executor.checkpoint()) }
        assertEquals(null, restored.currentSessionCandidate)
        assertEquals(null, restored.currentSessionKey)
        assertEquals(null, restored.pendingScrollBaseline)
        assertFalse(restored.isRestoredViewportCompatible(snapshot(moved)))
        assertEquals(
            PrivateMessagesTraversalTaskExecutor.RestoreReanchorStatus.REANCHORED,
            restored.reanchorRestoredCheckpoint(snapshot(moved)).status
        )
        assertEquals(setOf(key(original)), restored.attemptedSessionKeys)
        assertEquals(setOf(key(original)), restored.lockedUnreadSessionKeys)
        assertEquals("confirmed-send-unchanged", restored.checkpointLastSuccessfulTaskId)
        assertEquals(null, restored.selectNextCandidate(
            snapshot(moved).chatListCandidates, restored.lockedUnreadSessionKeys
        ))
    }

    @Test
    fun restoredViewportMismatchReanchorsBySessionNameWithoutReusingCoordinates() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val saved = candidate(0, "Alice", ChatUnreadStatus.UNREAD, top = 400)
        val neighbor = candidate(1, "Other", ChatUnreadStatus.READ, top = 100)
        executor.lastStableSessionListVisibleKeys = executor.visibleSessionKeys(snapshot(saved, neighbor))
        executor.rememberRecoveryAnchor(saved)

        val moved = candidate(4, "Alice", ChatUnreadStatus.UNREAD, top = 900)
        val current = snapshot(candidate(1, "Other", ChatUnreadStatus.READ), moved)
        assertFalse(executor.isRestoredViewportCompatible(current))
        assertTrue("Fresh neighboring identity proves continuity", executor.hasRestoredViewportContinuity(current))

        val result = executor.reanchorRestoredCheckpoint(current)

        assertEquals(
            PrivateMessagesTraversalTaskExecutor.RestoreReanchorStatus.REANCHORED,
            result.status
        )
        assertEquals(moved, result.matchedCandidate)
        assertEquals(executor.visibleSessionKeys(current), executor.lastStableSessionListVisibleKeys)
        assertEquals("Alice", executor.resumeSessionName)
    }

    @Test
    fun restoredViewportMismatchWithoutAnchorPreservesOriginalViewport() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.lastStableSessionListVisibleKeys = listOf("old@0,100,720,240")

        val current = snapshot(candidate(2, "New row", ChatUnreadStatus.UNREAD, top = 500))
        val result = executor.reanchorRestoredCheckpoint(current)

        assertEquals(
            PrivateMessagesTraversalTaskExecutor.RestoreReanchorStatus.NOT_FOUND,
            result.status
        )
        assertEquals(listOf("old@0,100,720,240"), executor.lastStableSessionListVisibleKeys)
    }

    @Test
    fun disconnectedReturnCannotSelectOrReplaceOriginalScanAndSafeRescanSurvivesRestore() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxPositionCheckCompleted = true
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val done = candidate(0, "Done", ChatUnreadStatus.READ)
        val pending = candidate(1, "Pending", ChatUnreadStatus.UNREAD)
        val unseen = candidate(2, "Previously unseen", ChatUnreadStatus.UNREAD)
        val original = snapshot(done, pending)
        executor.recordVerifiedViewport(original)
        executor.rememberRecoveryAnchor(done)
        executor.attemptedSessionKeys += key(done)
        executor.lockedUnreadSessionKeys += setOf(key(done), key(pending))
        executor.checkpointLastSuccessfulTaskId = "confirmed-send"
        val oldKeys = executor.lastStableSessionListVisibleKeys
        val jumped = snapshot(unseen)
        val invalid = executor.evaluateListSnapshot(jumped, false, 2, allowExpectedSessionReturnViewportChange = true)
        assertTrue(invalid.viewportPositionInvalidated)
        assertEquals(null, invalid.nextCandidate)
        assertEquals(oldKeys, executor.lastStableSessionListVisibleKeys)
        executor.beginScanContinuityRecovery()
        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(executor.checkpoint()) }
        assertTrue(restored.scanContinuityRecoveryPending)
        assertEquals(oldKeys, restored.lastStableSessionListVisibleKeys)
        assertEquals(setOf(key(done)), restored.attemptedSessionKeys)
        assertEquals("confirmed-send", restored.checkpointLastSuccessfulTaskId)
        val duringPositioning = restored.evaluateListSnapshot(snapshot(pending, unseen), false, 2)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING, duringPositioning.unreadAdmissionDecision)
        assertEquals(null, duringPositioning.nextCandidate)
        assertEquals(oldKeys, restored.lastStableSessionListVisibleKeys)
        assertTrue(runCatching { restored.renewExhaustedPrivateSelection() }.isFailure)
        restored.confirmPhysicalEndForReturnTraversal()
        restored.recordVerifiedViewport(snapshot(pending, unseen))
        assertFalse(restored.scanContinuityRecoveryPending)
        val resumed = restored.evaluateListSnapshot(snapshot(pending, unseen), false, 2)
        assertEquals(unseen, resumed.nextCandidate)
        assertEquals(setOf(key(done)), restored.attemptedSessionKeys)
        assertTrue(restored.pendingKnownUnreadKeys().contains(key(pending)))
    }

    @Test
    fun l07dRestoredRescanStopsAtFreshReadBoundaryWithoutRetiringMissingWork() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxPositionCheckCompleted = true
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val processed = candidate(0, "Processed", ChatUnreadStatus.READ)
        val nowReadBefore = candidate(1, "Now read", ChatUnreadStatus.UNREAD)
        val missing = candidate(2, "Offscreen pending", ChatUnreadStatus.UNREAD)
        val damaged = candidate(3, "Incomplete", ChatUnreadStatus.UNREAD, previewBoundsValid = false)
        executor.attemptedSessionKeys += key(processed)
        executor.lockedUnreadSessionKeys += setOf(key(processed), key(nowReadBefore), key(missing))
        executor.recordVerifiedViewport(snapshot(processed, nowReadBefore))
        executor.checkpointLastSuccessfulTaskId = "receipt"
        executor.checkpointNextCursorKey = key(missing)
        executor.observeIncompleteUnreadEvidence(snapshot(damaged))
        executor.beginScanContinuityRecovery()
        val saved = executor.checkpoint()
        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(saved) }
        val read = snapshot(candidate(1, "Now read", ChatUnreadStatus.READ),
            candidate(4, "System", ChatUnreadStatus.UNREAD, selectionClass = "SYSTEM_ENTRY"))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            restored.evaluateListSnapshot(read, false, 2).unreadAdmissionDecision)
        assertTrue(restored.scanContinuityRecoveryPending)
        assertEquals(saved.lastStableVisibleKeys, restored.lastStableSessionListVisibleKeys)
        // Neither an interrupted observation nor a truncated snapshot can finish recovery.
        restored.clearRecoveryViewportEvidence()
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            restored.evaluateListSnapshot(read, false, 2).unreadAdmissionDecision)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            restored.evaluateListSnapshot(read.copy(chatListExtractionTruncated = true), false, 2).unreadAdmissionDecision)
        assertTrue(restored.scanContinuityRecoveryPending)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            restored.evaluateListSnapshot(read, false, 2).unreadAdmissionDecision)
        val confirmed = restored.evaluateListSnapshot(read, false, 2)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED, confirmed.unreadAdmissionDecision)
        assertFalse(restored.scanContinuityRecoveryPending)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, restored.phase)
        assertEquals(restored.visibleSessionKeys(read), restored.lastStableSessionListVisibleKeys)
        assertEquals(saved.attemptedSessionKeys, restored.attemptedSessionKeys)
        assertEquals(setOf(key(missing)), restored.pendingKnownUnreadKeys())
        assertFalse(key(nowReadBefore) in restored.lockedUnreadSessionKeys)
        assertEquals(saved.unresolvedIncompleteUnreadKeys, restored.unresolvedIncompleteUnreadKeys)
        assertEquals(saved.lastSuccessfulTaskId, restored.checkpointLastSuccessfulTaskId)
        assertEquals(saved.nextCursorKey, restored.checkpointNextCursorKey)
        assertEquals(null, confirmed.nextCandidate)
        val secondRestore = PrivateMessagesTraversalTaskExecutor().apply { restore(restored.checkpoint()) }
        assertFalse(secondRestore.scanContinuityRecoveryPending)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, secondRestore.phase)
    }

    @Test
    fun l07dNamedRelocationUsesReadBoundaryButKeepsUnresolvedWork() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxPositionCheckCompleted = true
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
            atTop = true
        }
        val damaged = candidate(0, "Incomplete", ChatUnreadStatus.UNREAD, previewBoundsValid = false)
        val healthy = candidate(1, "Healthy", ChatUnreadStatus.READ)
        val mixed = snapshot(damaged, healthy)
        assertTrue(executor.observeIncompleteUnreadEvidence(mixed))
        assertTrue(executor.beginIncompleteUnreadRelocation(mixed))
        val saved = executor.checkpoint()
        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(saved) }
        val read = snapshot(healthy)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            restored.evaluateListSnapshot(read, false, 2).unreadAdmissionDecision)
        assertTrue(restored.incompleteUnreadRelocationActive)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED,
            restored.evaluateListSnapshot(read, false, 2).unreadAdmissionDecision)
        assertFalse(restored.incompleteUnreadRelocationActive)
        assertEquals(saved.unresolvedIncompleteUnreadKeys, restored.unresolvedIncompleteUnreadKeys)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, restored.phase)
        assertTrue(runCatching { restored.renewExhaustedPrivateSelection() }.isFailure)
        restored.atTop = true
        assertTrue(restored.canBeginIncompleteUnreadRelocation(read))
    }

    @Test
    fun movedSingleRecoveryContactCannotProveContinuityOfDifferentViewport() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val moved = candidate(0, "Moved contact", ChatUnreadStatus.READ)
        val neighbor = candidate(1, "Original neighbor", ChatUnreadStatus.UNREAD)
        executor.recordVerifiedViewport(snapshot(moved, neighbor))
        executor.rememberRecoveryAnchor(moved)
        val oldKeys = executor.lastStableSessionListVisibleKeys
        val unrelated = candidate(2, "Unrelated", ChatUnreadStatus.READ)
        val fresh = snapshot(moved, unrelated)
        assertEquals(PrivateMessagesTraversalTaskExecutor.RestoreReanchorStatus.REANCHORED,
            executor.reanchorRestoredCheckpoint(fresh).status)
        assertFalse(executor.hasRestoredViewportContinuity(fresh))
        assertEquals(oldKeys, executor.lastStableSessionListVisibleKeys)
    }

    @Test
    fun verifiedInterruptedSessionSurvivesReadBadgeLossAndRestoreWithoutAdmittingOtherReadRows() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxPositionCheckCompleted = true
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val original = candidate(0, "Interrupted", ChatUnreadStatus.UNREAD)
        val read = candidate(0, "Interrupted", ChatUnreadStatus.READ)
        val other = candidate(1, "Other read", ChatUnreadStatus.READ)
        // The previous viewport must be verified before departure. R02 must not
        // treat an empty checkpoint viewport as proof of continuous traversal.
        executor.recordVerifiedViewport(snapshot(original, other))
        executor.beginSession(original, key(original), "original-window")
        executor.attemptedSessionKeys += key(original)
        val detail = snapshot(original).copy(
            pageKind = com.freedom.features.aireply.ports.ChatPageKind.CHAT_DETAIL_CANDIDATE,
            chatTitleText = "与Interrupted对话",
            inputFieldTarget = original.rowTarget,
            messageListTarget = original.rowTarget,
            windowGeneration = 1L
        )
        assertTrue(executor.recordVerifiedDetailEntry(detail, 100L))
        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(executor.checkpoint()) }
        assertEquals(setOf(key(original)), restored.pendingKnownUnreadKeys())
        val list = snapshot(read, other)
        assertTrue(restored.canRecoverInterruptedRow(list, read))
        assertFalse(restored.canRecoverInterruptedRow(list, other))
        assertFalse(restored.canRecoverInterruptedRow(snapshot(read, candidate(1, "Interrupted", ChatUnreadStatus.READ)), read))
        val unknown = candidate(0, "Interrupted", ChatUnreadStatus.UNKNOWN)
        assertFalse(restored.canRecoverInterruptedRow(snapshot(unknown), unknown))
        val decision = restored.evaluateListSnapshot(list, false, 2)
        assertFalse(decision.viewportPositionInvalidated)
        assertEquals(read, decision.nextCandidate)
        assertEquals(setOf(key(original)), restored.pendingKnownUnreadKeys())
        restored.beginSession(read, key(read), "new-window")
        assertTrue(restored.recoveringUnfinishedSession)
        assertFalse(restored.verifyUnfinishedIdentity(read))
        val verified = read.apply {
            contactIdentity = ContactRowIdentity("snapshot", "temp", "original-id", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
        }
        assertTrue(restored.verifyUnfinishedIdentity(verified))
        val wrong = candidate(0, "Interrupted", ChatUnreadStatus.READ).apply {
            contactIdentity = ContactRowIdentity("snapshot", "temp", "different-id", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
        }
        assertFalse(restored.verifyUnfinishedIdentity(wrong))
        assertEquals(key(verified), restored.currentUnfinishedSession()?.verifiedKey)
        assertTrue(restored.completeCurrentUnfinishedSession())
        assertTrue(restored.checkpoint().unfinishedSessions.isEmpty())
        assertTrue(restored.pendingKnownUnreadKeys().isEmpty())
        assertEquals(null, restored.selectNextCandidate(list.chatListCandidates))
    }

    @Test
    fun r03bDeferredSendPreservesOriginalVisitAndLetsSevenOtherUnreadRowsProceed() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxPositionCheckCompleted = true
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val original = candidate(7, "Deferred", ChatUnreadStatus.UNREAD)
        val others = (0..6).map { candidate(it, "Other $it", ChatUnreadStatus.UNREAD) }
        val inbox = snapshot(*(others + original).toTypedArray())
        executor.recordVerifiedViewport(inbox)
        executor.lockedUnreadSessionKeys += (others + original).map(::key)
        executor.beginSession(original, key(original), "original-window")
        executor.attemptedSessionKeys += key(original)
        val detail = snapshot(original).copy(
            pageKind = com.freedom.features.aireply.ports.ChatPageKind.CHAT_DETAIL_CANDIDATE,
            chatTitleText = "与Deferred对话",
            inputFieldTarget = original.rowTarget,
            messageListTarget = original.rowTarget,
            windowGeneration = 1L
        )
        assertTrue(executor.recordVerifiedDetailEntry(detail, 100L))
        val verified = candidate(7, "Deferred", ChatUnreadStatus.UNREAD).apply {
            contactIdentity = ContactRowIdentity("snapshot", "temp", "deferred-id", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
        }
        assertTrue(executor.verifyUnfinishedIdentity(verified))
        val originalVisit = executor.currentUnfinishedSession()!!
        assertTrue(executor.deferCurrentUnfinishedSend())
        assertEquals(originalVisit.copy(disposition = UnfinishedPrivateSessionDisposition.SEND_DEFERRED_THIS_ROUND),
            executor.currentUnfinishedSession())
        val restored = PrivateMessagesTraversalTaskExecutor().apply {
            restore(executor.checkpoint())
            resumeAfterReturnedInbox()
        }
        assertEquals(others.map(::key).toSet(), restored.pendingKnownUnreadKeys())
        assertTrue(restored.isDeferredForCurrentRound(key(verified)))
        assertTrue(restored.isDeferredForCurrentRound(key(original)))
        assertFalse(restored.canRecoverInterruptedRow(inbox, original))
        assertFalse(restored.hasUnparkedUnread(snapshot(original)))
        assertTrue(restored.hasUnparkedUnread(inbox))
        // The preferred retry, ordinary selection and freshly verified return
        // selection all reject the parked row even if it is still UNREAD.
        assertEquals(others.last(), restored.selectNextCandidate(inbox.chatListCandidates, preferredRetryKey = key(original)))
        assertEquals(others.last(), restored.nextFreshReturnCandidate(inbox, preferredRetryKey = key(original)))
        val processed = mutableSetOf<String>()
        repeat(7) {
            val next = restored.selectNextCandidate(inbox.chatListCandidates)!!
            assertTrue(processed.add(key(next)))
            restored.attemptedSessionKeys += key(next)
        }
        assertEquals(others.map(::key).toSet(), processed)
        assertEquals(null, restored.selectNextCandidate(inbox.chatListCandidates))
        assertTrue(restored.pendingKnownUnreadKeys().isEmpty())
        assertFalse(restored.hasPendingVisibleReturnWork(snapshot(original)))
        assertEquals(originalVisit.openedAtMillis, restored.checkpoint().unfinishedSessions.single().openedAtMillis)
        // The original obligation remains, without holding the completed scan.
        restored.renewExhaustedPrivateSelection()
        assertEquals(UnfinishedPrivateSessionDisposition.SEND_REVISIT_ELIGIBLE,
            restored.checkpoint().unfinishedSessions.single().disposition)
        assertFalse(restored.isDeferredForCurrentRound(key(original)))
        assertTrue(restored.pendingKnownUnreadKeys().isEmpty())
        val read = candidate(7, "Deferred", ChatUnreadStatus.READ)
        val unrelated = candidate(6, "Other read", ChatUnreadStatus.READ)
        val readList = snapshot(read, unrelated)
        assertTrue(restored.canRecoverInterruptedRow(readList, read))
        assertFalse(restored.canRecoverInterruptedRow(readList, unrelated))
        assertEquals(read, restored.selectNextCandidate(readList.chatListCandidates))
        assertEquals(null, restored.selectNextCandidate(listOf(unrelated)))
        restored.beginSession(read, key(read), "revisit-window")
        assertTrue(restored.recoveringUnfinishedSession)
        assertTrue(restored.recordVerifiedDetailEntry(detail, 200L))
        assertEquals(originalVisit.openedAtMillis, restored.currentUnfinishedSession()?.openedAtMillis)
        assertEquals(UnfinishedPrivateSessionDisposition.SEND_DEFERRED_THIS_ROUND,
            restored.currentUnfinishedSession()?.disposition)
        assertFalse(restored.verifyUnfinishedIdentity(read))
        assertTrue(restored.verifyUnfinishedIdentity(verified))
        assertTrue(restored.completeCurrentUnfinishedSession())
        assertTrue(restored.checkpoint().unfinishedSessions.isEmpty())
    }

    @Test
    fun l08cUnreadableVisitUsesExistingRoundParkingButCannotParkLinkedReply() {
        val row = candidate(0, "Visual", ChatUnreadStatus.UNREAD)
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.beginSession(row, key(row), "window")
        val detail = snapshot(row).copy(
            pageKind = com.freedom.features.aireply.ports.ChatPageKind.CHAT_DETAIL_CANDIDATE,
            chatTitleText = "与Visual对话",
            inputFieldTarget = row.rowTarget, messageListTarget = row.rowTarget, windowGeneration = 1L)
        assertTrue(executor.recordVerifiedDetailEntry(detail, 100L))
        val verified = candidate(0, "Visual", ChatUnreadStatus.UNREAD).apply {
            contactIdentity = ContactRowIdentity("snapshot", "temp", "visual-id", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
        }
        assertTrue(executor.verifyUnfinishedIdentity(verified))
        assertTrue(executor.deferCurrentUnfinishedRead())
        val parked = executor.checkpoint()
        assertEquals(UnfinishedPrivateSessionDisposition.SEND_DEFERRED_THIS_ROUND,
            parked.unfinishedSessions.single().disposition)
        assertNull(parked.unfinishedSessions.single().replyRequestKey)
        assertNull(parked.lastSuccessfulTaskId)
        val restored = PrivateMessagesTraversalTaskExecutor().apply {
            restore(parked)
            resumeAfterReturnedInbox()
        }
        assertTrue(restored.isDeferredForCurrentRound(key(row)))
        assertFalse(restored.canRecoverInterruptedRow(snapshot(row), row))
        assertTrue(executor.recordVerifiedVisitReplyRequestKey("a".repeat(64)))
        assertFalse(executor.deferCurrentUnfinishedRead())
    }

    @Test
    fun r03bDeferralNeverClearsIndependentUnreadUnknownOrIncompleteEvidence() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxPositionCheckCompleted = true
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val row = candidate(0, "Deferred", ChatUnreadStatus.UNREAD)
        executor.beginSession(row, key(row), "original-window")
        assertTrue(executor.recordVerifiedDetailEntry(snapshot(row).copy(
            pageKind = com.freedom.features.aireply.ports.ChatPageKind.CHAT_DETAIL_CANDIDATE,
            chatTitleText = "与Deferred对话",
            inputFieldTarget = row.rowTarget,
            messageListTarget = row.rowTarget,
            windowGeneration = 1L
        ), 100L))
        assertTrue(executor.deferCurrentUnfinishedSend())
        executor.clearCurrentSession()
        val unknown = candidate(0, "Deferred", ChatUnreadStatus.UNKNOWN, badgePresent = true)
        val fresh = snapshot(unknown)
        assertTrue(executor.hasPendingVisibleReturnWork(fresh))
        assertFalse(executor.canRecoverInterruptedRow(fresh, unknown))
        assertTrue(executor.observeUnreadBlockers(fresh))
        assertTrue(runCatching { executor.renewExhaustedPrivateSelection() }.isFailure)
        assertEquals(UnfinishedPrivateSessionDisposition.SEND_DEFERRED_THIS_ROUND,
            executor.checkpoint().unfinishedSessions.single().disposition)
    }

    private fun key(candidate: ChatListCandidateSnapshot): String =
        com.freedom.features.aireply.ports.ChatSessionManagementPolicy.stableSessionKey(candidate)

    @Test
    fun collectionEvidenceProposesTopWithoutAProbeScroll() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val row = candidate(3, "Unread", ChatUnreadStatus.UNREAD)
        val top = snapshot(row).copy(
            windowGeneration = 1L,
            chatListCollectionRowCount = 67,
            chatListVisibleCollectionRows = (0..7).toList(),
            chatListCanScrollForward = true,
            chatListCanScrollBackward = false
        )
        assertEquals(true, executor.collectionBoundaryEvidence(top, toTop = true))
        assertEquals(
            PrivateMessagesScrollObservationDecision.TOP_BOUNDARY_CANDIDATE,
            executor.evaluateListSnapshot(top, false, 2).scrollDecision
        )
        assertFalse(executor.initialInboxTopPositioningCompleted)
        assertEquals(null, executor.pendingScrollBaseline)
        assertEquals(PrivateMessagesScrollObservationDecision.NO_PENDING_SCROLL,
            executor.evaluateListSnapshot(top, false, 2, allowCollectionTopConfirmation = false).scrollDecision)
        assertEquals(false, executor.collectionBoundaryEvidence(top.copy(chatListCanScrollBackward = true), toTop = true))
        assertEquals(false, executor.collectionBoundaryEvidence(top.copy(
            chatListVisibleCollectionRows = (5..14).toList(),
            chatListCanScrollBackward = true
        ), toTop = true))
        assertEquals(null, executor.collectionBoundaryEvidence(top.copy(
            chatListVisibleCollectionRows = listOf(0, 0, 1)
        ), toTop = true))
        assertEquals(null, executor.collectionBoundaryEvidence(top.copy(
            chatListCanScrollBackward = null
        ), toTop = true))
        assertEquals(null, executor.collectionBoundaryEvidence(top.copy(
            chatListExtractionTruncated = true
        ), toTop = true))
        assertFalse(executor.boundaryConfirmationKeys(top) ==
            executor.boundaryConfirmationKeys(top.copy(chatListVisibleCollectionRows = emptyList())))
    }

    @Test
    fun collectionRangeMeasuresScrollWithoutExactCoordinateEquality() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val first = snapshot(candidate(0, "Same", ChatUnreadStatus.UNREAD, top = 100)).copy(
            windowGeneration = 1L,
            chatListCollectionRowCount = 67,
            chatListVisibleCollectionRows = (5..14).toList(),
            chatListCanScrollForward = true,
            chatListCanScrollBackward = true
        )
        val jitter = first.copy(chatListCandidates = listOf(
            candidate(1, "Same", ChatUnreadStatus.UNREAD, top = 105)
        ))
        assertFalse(executor.visibleSessionKeys(first) == executor.visibleSessionKeys(jitter))
        executor.pendingScrollBaseline = executor.scrollPositionKeys(first)
        assertEquals(
            PrivateMessagesScrollObservationDecision.BOTTOM_NO_PROGRESS,
            executor.consumePendingScrollObservation(executor.scrollPositionKeys(jitter), 2, false)
        )
        executor.pendingScrollBaseline = executor.scrollPositionKeys(jitter)
        val moved = jitter.copy(
            chatListVisibleCollectionRows = (13..22).toList(),
            chatListCandidates = listOf(candidate(2, "Newly exposed", ChatUnreadStatus.UNREAD))
        )
        assertEquals(
            PrivateMessagesScrollObservationDecision.BOTTOM_VIEWPORT_CHANGED,
            executor.consumePendingScrollObservation(executor.scrollPositionKeys(moved), 2, false)
        )
        executor.pendingScrollBaseline = executor.scrollPositionKeys(first)
        executor.pendingScrollToTop = true
        assertEquals(
            PrivateMessagesScrollObservationDecision.TOP_VIEWPORT_CHANGED,
            executor.consumePendingScrollObservation(
                executor.scrollPositionKeys(jitter.copy(chatListVisibleCollectionRows = emptyList())), 2
            )
        )
        executor.pendingScrollBaseline = executor.scrollPositionKeys(first)
        executor.pendingScrollToTop = true
        assertEquals(
            PrivateMessagesScrollObservationDecision.TOP_NO_PROGRESS_RETRY,
            executor.consumePendingScrollObservation(executor.scrollPositionKeys(first), 1, false)
        )
    }

    @Test
    fun readViewportExcludesClippedRowsButDoesNotTreatTheirUnknownAsRead() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
        }
        val read = candidate(0, "Complete read", ChatUnreadStatus.READ)
        // Android clamps this short visible rectangle to the viewport; the adapter
        // reports separate layout-height evidence that the holder is clipped.
        val clipped = candidate(1, "Clipped edge", ChatUnreadStatus.UNKNOWN,
            top = 1530, rowHeight = 70, clippedAtEdge = true)
        val systemUnread = candidate(2, "System entry", ChatUnreadStatus.UNREAD,
            top = 400, selectionClass = "SYSTEM_ENTRY")
        val page = snapshot(read, clipped, systemUnread)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            executor.evaluateListSnapshot(page, false, 2).unreadAdmissionDecision)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED,
            executor.evaluateListSnapshot(page, false, 2).unreadAdmissionDecision)
        assertEquals(ChatUnreadStatus.UNKNOWN, clipped.unreadStatus)
        assertTrue(executor.unresolvedIncompleteUnreadKeys.isEmpty())
        assertTrue(executor.lockedUnreadSessionKeys.isEmpty())
    }

    @Test
    fun l07cSystemBadgesAreReadForBoundaryWithoutChangingRawStateOrEligibility() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
        }
        val read = candidate(0, "Read private", ChatUnreadStatus.READ)
        val systemUnread = candidate(1, "System", ChatUnreadStatus.UNREAD, selectionClass = "SYSTEM_ENTRY")
        val systemUnknown = candidate(1, "System", ChatUnreadStatus.UNKNOWN, selectionClass = "SYSTEM_ENTRY")
        val policy = com.freedom.features.aireply.ports.ChatSessionManagementPolicy
        assertEquals(ChatUnreadStatus.READ, policy.privateBoundaryReadStatus(systemUnread))
        assertEquals(ChatUnreadStatus.READ, policy.privateBoundaryReadStatus(systemUnknown))
        assertFalse(policy.isEligible(systemUnread))
        assertFalse(policy.isEligible(systemUnknown))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            executor.evaluateUnreadAdmission(snapshot(read, systemUnread)))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED,
            executor.evaluateUnreadAdmission(snapshot(read, systemUnknown)))
        assertEquals(ChatUnreadStatus.UNREAD, systemUnread.unreadStatus)
        assertEquals(ChatUnreadStatus.UNKNOWN, systemUnknown.unreadStatus)
        assertTrue(executor.lockedUnreadSessionKeys.isEmpty())
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
    }

    @Test
    fun l07cSystemEntryDoesNotHidePrivateUnreadOrProveAnUnseenSuffix() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
        }
        val system = candidate(0, "System", ChatUnreadStatus.UNREAD, selectionClass = "SYSTEM_ENTRY")
        val unread = candidate(1, "Private unread", ChatUnreadStatus.UNREAD)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(system)))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(system, unread)))
        assertEquals(setOf(key(unread)), executor.lockedUnreadSessionKeys)
        val bottom = snapshot(system).copy(windowGeneration = 1L,
            chatListCollectionRowCount = 2, chatListVisibleCollectionRows = listOf(1),
            chatListCanScrollForward = false, chatListCanScrollBackward = true)
        assertEquals(true, executor.collectionBoundaryEvidence(bottom, toTop = false))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            executor.evaluateUnreadAdmission(bottom))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED,
            executor.evaluateUnreadAdmission(bottom))
        assertEquals(setOf(key(unread)), executor.lockedUnreadSessionKeys)
    }

    @Test
    fun l07cInvisibleRowsDoNotSupplyReadProofOrBlockAnOtherwiseCompleteReadViewport() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
        }
        val hidden = candidate(1, "Hidden stale", ChatUnreadStatus.UNKNOWN,
            top = 1700, rowVisible = false, inViewport = false)
        val read = candidate(0, "Read", ChatUnreadStatus.READ)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(hidden)))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(read).copy(chatListInvalidRowBoundsCount = 1)))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
            executor.evaluateUnreadAdmission(snapshot(read, hidden)))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED,
            executor.evaluateUnreadAdmission(snapshot(read, hidden)))
        assertEquals(ChatUnreadStatus.UNKNOWN, hidden.unreadStatus)
    }

    @Test
    fun readViewportRequiresCompletePrivateEvidenceAndNeverIgnoresEdgeBadge() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
        }
        val read = candidate(0, "Complete read", ChatUnreadStatus.READ)
        val clippedBadge = candidate(1, "Clipped badge", ChatUnreadStatus.UNKNOWN,
            top = 1530, badgePresent = true, rowHeight = 70, clippedAtEdge = true)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateListSnapshot(snapshot(read, clippedBadge), false, 2).unreadAdmissionDecision)
        assertTrue(executor.unresolvedIncompleteUnreadKeys.isNotEmpty())
        assertFalse(executor.lockedUnreadSetFinalized)

        val other = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
        }
        val unclassifiedBadge = candidate(2, "Clipped unclassified", ChatUnreadStatus.UNKNOWN,
            top = 1530, badgePresent = true, rowHeight = 70, clippedAtEdge = true,
            selectionClass = "UNCLASSIFIED")
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            other.evaluateUnreadAdmission(snapshot(read, unclassifiedBadge)))
        assertFalse(other.lockedUnreadSetFinalized)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            other.evaluateUnreadAdmission(snapshot(read).copy(chatListExtractionTruncated = true)))
        assertFalse(other.lockedUnreadSetFinalized)
        val onlyClipped = candidate(3, "Only clipped", ChatUnreadStatus.UNKNOWN,
            top = 1530, rowHeight = 70, clippedAtEdge = true)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            other.evaluateUnreadAdmission(snapshot(onlyClipped)))
        assertFalse(other.lockedUnreadSetFinalized)
    }

    @Test
    fun p6cRetryOriginalOnlyWhenFreshAndProvisionalAttemptDoesNotSuppressIt() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val original = candidate(0, "Original", ChatUnreadStatus.UNREAD)
        val other = candidate(1, "Other", ChatUnreadStatus.UNREAD)
        val done = candidate(2, "Done", ChatUnreadStatus.UNREAD)
        val page = snapshot(original, other, done)
        executor.recordReturnViewportBeforeOpen(page)
        executor.attemptedSessionKeys += key(done)
        assertEquals(original, executor.evaluateListSnapshot(page, false, 2,
            preferredRetryKey = key(original)).nextCandidate)
        executor.attemptedSessionKeys += key(original)
        assertEquals(original, executor.nextFreshReturnCandidate(page,
            preferredRetryKey = key(original), provisionalAttemptKey = key(original)))
        assertEquals(other, executor.nextFreshReturnCandidate(page, preferredRetryKey = key(original)))
        executor.attemptedSessionKeys.remove(key(original))
        assertEquals(other, executor.nextFreshReturnCandidate(snapshot(other, done), preferredRetryKey = key(original)))
        assertTrue(key(original) in executor.pendingKnownUnreadKeys())
        assertEquals(setOf(key(done)), executor.attemptedSessionKeys)
        assertTrue(executor.lockedUnreadSetFinalized)
    }

    @Test
    fun p6bReorderedReturnUsesFreshBottomMostAndPreservesOriginalRound() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val a = candidate(0, "A", ChatUnreadStatus.UNREAD, top = 100)
        val b = candidate(1, "B", ChatUnreadStatus.UNREAD, top = 400)
        val c = candidate(2, "C", ChatUnreadStatus.UNREAD, top = 700)
        val initial = snapshot(a, b, c)
        assertEquals(c, executor.evaluateListSnapshot(initial, false, 2).nextCandidate)
        executor.recordReturnViewportBeforeOpen(initial)
        executor.attemptedSessionKeys += key(c)

        val cMoved = candidate(0, "C", ChatUnreadStatus.UNREAD, top = 100)
        val aMoved = candidate(2, "A", ChatUnreadStatus.UNREAD, top = 700)
        val returned = snapshot(cMoved, b, aMoved)
        assertEquals(aMoved, executor.nextFreshReturnCandidate(returned))
        assertEquals(aMoved, executor.evaluateListSnapshot(returned, false, 2,
            allowExpectedSessionReturnViewportChange = true).nextCandidate)
        assertTrue(executor.hasPendingVisibleReturnWork(returned))
        executor.attemptedSessionKeys += key(a)
        assertEquals(b, executor.nextFreshReturnCandidate(returned))
        executor.attemptedSessionKeys += key(b)
        assertFalse(executor.hasPendingVisibleReturnWork(returned))
        assertTrue(executor.lockedUnreadSetFinalized)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
    }

    @Test
    fun p6bPreScrollRetainsVisibleUnresolvedAndDoesNotRetireMissingCandidate() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val pending = candidate(0, "Pending", ChatUnreadStatus.UNREAD)
        val incomplete = candidate(1, "Incomplete", ChatUnreadStatus.UNREAD, previewBoundsValid = false)
        val other = candidate(2, "Other", ChatUnreadStatus.READ)
        executor.recordReturnViewportBeforeOpen(snapshot(pending, incomplete, other))
        assertTrue(executor.hasPendingVisibleReturnWork(snapshot(incomplete, other)))
        assertEquals(null, executor.nextFreshReturnCandidate(snapshot(incomplete, other)))
        assertTrue(key(pending) in executor.pendingKnownUnreadKeys())
        assertTrue(executor.unresolvedIncompleteUnreadKeys.isNotEmpty())
        assertFalse(executor.hasPendingVisibleReturnWork(snapshot(other)))
        assertTrue(key(pending) in executor.pendingKnownUnreadKeys())
        val repaired = candidate(1, "Incomplete", ChatUnreadStatus.UNREAD)
        val returned = executor.evaluateListSnapshot(snapshot(pending, repaired, other), false, 2,
            allowExpectedSessionReturnViewportChange = true)
        assertEquals(repaired, returned.nextCandidate)
        assertTrue(executor.unresolvedIncompleteUnreadKeys.isEmpty())
    }

    @Test
    fun p6bVerifiedIdentityUpgradeKeepsVisitAttemptedUnderBothKeys() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val weak = candidate(0, "A", ChatUnreadStatus.UNREAD)
        val verified = candidate(0, "A", ChatUnreadStatus.UNREAD).apply {
            contactIdentity = ContactRowIdentity("snapshot", "temp", "verified", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
        }
        executor.attemptedSessionKeys += key(weak)
        executor.lockedUnreadSessionKeys += key(weak)
        executor.preserveAttemptAfterVerifiedIdentityUpgrade(key(weak), key(verified))
        assertEquals(setOf(key(weak), key(verified)), executor.attemptedSessionKeys)
        assertTrue(executor.pendingKnownUnreadKeys().isEmpty())
        assertEquals(null, executor.selectNextCandidate(listOf(verified)))
        assertEquals(null, executor.selectNextCandidate(listOf(weak)))
    }

    @Test
    fun p5ReturningScanAdmitsNewUnreadAndNeverReopensAttemptedOrUnknownRows() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val attempted = candidate(0, "Attempted", ChatUnreadStatus.UNREAD)
        val newUnread = candidate(1, "New unread", ChatUnreadStatus.UNREAD)
        val unknown = candidate(2, "Unknown", ChatUnreadStatus.UNKNOWN)
        executor.attemptedSessionKeys += key(attempted)
        executor.lockedUnreadSessionKeys += key(attempted)
        val first = executor.evaluateListSnapshot(snapshot(attempted, newUnread, unknown), false, 2)
        assertEquals(newUnread, first.nextCandidate)
        assertEquals(setOf(key(attempted), key(newUnread)), executor.lockedUnreadSessionKeys)
        assertEquals(setOf(key(newUnread)), executor.pendingKnownUnreadKeys())
        assertEquals(setOf(key(unknown)), executor.unresolvedUnknownUnreadKeys)
        assertTrue(executor.normalInboxUnreadStateUnknown)
        assertTrue(executor.lockedUnreadSetFinalized)

        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(executor.checkpoint()) }
        restored.attemptedSessionKeys += key(newUnread)
        val movedUnknown = candidate(3, "Unknown", ChatUnreadStatus.UNREAD, top = 550)
        val next = restored.evaluateListSnapshot(snapshot(attempted, newUnread, movedUnknown), false, 2,
            allowExpectedSessionReturnViewportChange = true)
        assertEquals(movedUnknown, next.nextCandidate)
        assertTrue(restored.unresolvedUnknownUnreadKeys.isEmpty())
        assertFalse(restored.normalInboxUnreadStateUnknown)
        assertEquals(setOf(key(movedUnknown)), restored.pendingKnownUnreadKeys())
        assertEquals(setOf(key(attempted), key(newUnread)), restored.attemptedSessionKeys)
    }

    @Test
    fun p5UnknownEvidenceResolvesIndividuallyAndNeverByMissingOrTruncatedRows() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val a = candidate(0, "Unknown A", ChatUnreadStatus.UNKNOWN)
        val b = candidate(1, "Unknown B", ChatUnreadStatus.UNKNOWN)
        assertTrue(executor.observeUnreadBlockers(snapshot(a, b)))
        val movedA = candidate(3, "Unknown A", ChatUnreadStatus.READ, top = 700)
        assertTrue(executor.observeUnreadBlockers(snapshot(movedA).copy(chatListExtractionTruncated = true)))
        assertEquals(setOf(key(a), key(b)), executor.unresolvedUnknownUnreadKeys)
        assertTrue(executor.observeUnreadBlockers(snapshot(movedA)))
        assertEquals(setOf(key(b)), executor.unresolvedUnknownUnreadKeys)
        val movedB = candidate(4, "Unknown B", ChatUnreadStatus.UNREAD, top = 850)
        assertFalse(executor.observeUnreadBlockers(snapshot(movedB)))
        assertTrue(executor.unresolvedUnknownUnreadKeys.isEmpty())
    }

    @Test
    fun p5ClippedUnreadRemainsPendingUntilCompleteAndReadDoesNotCountAsAttempt() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val previouslyUnread = candidate(0, "Previously unread", ChatUnreadStatus.UNREAD)
        val healthy = candidate(1, "Other unread", ChatUnreadStatus.UNREAD)
        val clipped = candidate(2, "Edge unread", ChatUnreadStatus.UNREAD, top = 1530,
            rowHeight = 70, clippedAtEdge = true, badgePresent = true)
        executor.lockedUnreadSessionKeys += key(previouslyUnread)
        val first = executor.evaluateListSnapshot(snapshot(healthy, clipped), false, 2)
        assertEquals(healthy, first.nextCandidate)
        assertTrue(executor.unresolvedIncompleteUnreadKeys.isNotEmpty())
        assertTrue(key(previouslyUnread) in executor.pendingKnownUnreadKeys())
        assertFalse(key(clipped) in executor.attemptedSessionKeys)

        val complete = candidate(2, "Edge unread", ChatUnreadStatus.UNREAD, top = 400)
        val read = candidate(0, "Previously unread", ChatUnreadStatus.READ)
        val next = executor.evaluateListSnapshot(snapshot(read, healthy, complete), false, 2,
            allowExpectedSessionReturnViewportChange = true)
        assertEquals(complete, next.nextCandidate)
        assertTrue(executor.unresolvedIncompleteUnreadKeys.isEmpty())
        assertFalse(key(previouslyUnread) in executor.pendingKnownUnreadKeys())
        assertFalse(key(previouslyUnread) in executor.attemptedSessionKeys)
        assertTrue(key(complete) in executor.pendingKnownUnreadKeys())
    }

    @Test
    fun p5UnverifiedTapDoesNotBecomeProcessedWorkInRestoredCheckpoint() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            lockedUnreadSetFinalized = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        }
        val pending = candidate(0, "Pending navigation", ChatUnreadStatus.UNREAD)
        executor.lockedUnreadSessionKeys += key(pending)
        executor.attemptedSessionKeys += setOf("already-processed", key(pending))
        executor.recordVerifiedViewport(snapshot(pending))
        executor.beginSession(pending, key(pending), null)
        executor.executionState = SessionManagerState.OPENING_SESSION
        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(executor.checkpoint()) }
        assertEquals(setOf("already-processed"), restored.attemptedSessionKeys)
        assertEquals(setOf(key(pending)), restored.pendingKnownUnreadKeys())
        assertEquals(pending, restored.evaluateListSnapshot(snapshot(pending), false, 2).nextCandidate)
    }

    @Test
    fun greetingPhysicalBoundaryDoesNotDependOnUnreadBusinessBoundary() {
        val row = candidate(3, "Read", ChatUnreadStatus.READ)
        val middle = snapshot(row).copy(
            windowGeneration = 1L,
            chatListCollectionRowCount = 20,
            chatListVisibleCollectionRows = listOf(5, 6, 7),
            chatListCanScrollForward = true,
            chatListCanScrollBackward = true
        )
        assertEquals(false, com.freedom.features.aireply.ports.ChatSessionManagementPolicy
            .greetingSearchBoundaryEvidence(middle, toTop = false))
        val bottom = middle.copy(
            chatListVisibleCollectionRows = listOf(17, 18, 19),
            chatListCanScrollForward = false
        )
        assertEquals(true, com.freedom.features.aireply.ports.ChatSessionManagementPolicy
            .greetingSearchBoundaryEvidence(bottom, toTop = false))
        assertEquals(false, com.freedom.features.aireply.ports.ChatSessionManagementPolicy
            .greetingSearchBoundaryEvidence(bottom, toTop = true))
        val top = middle.copy(
            chatListVisibleCollectionRows = listOf(0, 1, 2),
            chatListCanScrollBackward = false
        )
        assertEquals(true, com.freedom.features.aireply.ports.ChatSessionManagementPolicy
            .greetingSearchBoundaryEvidence(top, toTop = true))
        assertEquals(false, com.freedom.features.aireply.ports.ChatSessionManagementPolicy
            .greetingSearchBoundaryEvidence(top, toTop = false))
    }

    @Test
    fun greetingPhysicalBoundaryRejectsContradictoryIncompleteAndMissingProof() {
        val row = candidate(3, "Read", ChatUnreadStatus.READ)
        val bottom = snapshot(row).copy(
            windowGeneration = 1L,
            chatListCollectionRowCount = 20,
            chatListVisibleCollectionRows = listOf(17, 18, 19),
            chatListCanScrollForward = false,
            chatListCanScrollBackward = true
        )
        val policy = com.freedom.features.aireply.ports.ChatSessionManagementPolicy
        assertEquals(false, policy.greetingSearchBoundaryEvidence(
            bottom.copy(chatListCanScrollForward = true), toTop = false))
        assertEquals(null, policy.greetingSearchBoundaryEvidence(
            bottom.copy(chatListExtractionTruncated = true), toTop = false))
        assertEquals(null, policy.greetingSearchBoundaryEvidence(
            bottom.copy(chatListInvalidRowBoundsCount = 1), toTop = false))
        assertEquals(null, policy.greetingSearchBoundaryEvidence(
            bottom.copy(chatListVisibleCollectionRows = listOf(17, 17, 19)), toTop = false))
        assertEquals(null, policy.greetingSearchBoundaryEvidence(
            bottom.copy(chatListVisibleCollectionRows = emptyList()), toTop = false))
        assertEquals(null, policy.greetingSearchBoundaryEvidence(
            bottom.copy(chatListCanScrollBackward = false), toTop = false))
        assertEquals(null, policy.greetingSearchBoundaryEvidence(
            bottom.copy(windowGeneration = 0L), toTop = false))
        val withoutCollection = bottom.copy(
            chatListCollectionRowCount = null,
            chatListVisibleCollectionRows = emptyList()
        )
        assertEquals(true, policy.greetingSearchBoundaryEvidence(withoutCollection, toTop = false))
        assertEquals(null, policy.greetingSearchBoundaryEvidence(
            withoutCollection.copy(chatListCanScrollBackward = false), toTop = false))
        assertEquals(null, policy.greetingSearchBoundaryEvidence(
            withoutCollection.copy(chatListCanScrollForward = null), toTop = false))
    }

    @Test
    fun f02InvisibleChildEvidenceIsTrackedUntilOriginalRowHasCompleteFreshProof() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
            lockedUnreadSetFinalized = true
        }
        val healthy = candidate(0, "Independent", ChatUnreadStatus.UNREAD)
        val damagedName = candidate(1, "Pending name", ChatUnreadStatus.UNREAD, nameNodeVisible = false)
        val damagedPreview = candidate(2, "Pending preview", ChatUnreadStatus.UNREAD, previewNodeVisible = false)
        val first = executor.evaluateListSnapshot(snapshot(healthy, damagedName, damagedPreview), false, 2)
        assertTrue(first.incompleteUnreadEvidenceBlocked)
        assertTrue(first.incompleteUnreadAllowsIndependentTraversal)
        assertEquals(healthy, first.nextCandidate)
        assertEquals(2, executor.unresolvedIncompleteUnreadKeys.size)
        assertTrue(executor.attemptedSessionKeys.isEmpty())
        val restored = PrivateMessagesTraversalTaskExecutor().apply { restore(executor.checkpoint()) }
        assertTrue(restored.observeIncompleteUnreadEvidence(snapshot(healthy)))
        val repairedName = candidate(1, "Pending name", ChatUnreadStatus.UNREAD)
        val repairedPreview = candidate(2, "Pending preview", ChatUnreadStatus.READ)
        assertFalse(restored.observeIncompleteUnreadEvidence(snapshot(healthy, repairedName, repairedPreview)))
        assertTrue(key(repairedName) in restored.pendingKnownUnreadKeys())
        assertFalse(key(repairedPreview) in restored.attemptedSessionKeys)
        assertTrue(restored.unresolvedIncompleteUnreadKeys.isEmpty())
    }

    @Test
    fun f02DecisionPacketIncludesBadgeAnomalyDiscoveredDuringSameObservation() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
            phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
            lockedUnreadSetFinalized = true
        }
        val healthy = candidate(0, "Independent", ChatUnreadStatus.UNREAD)
        val unknown = candidate(1, "Badge pending", ChatUnreadStatus.UNKNOWN, badgePresent = true)
        val page = snapshot(healthy, unknown)
        val first = executor.evaluateListSnapshot(page, false, 2)
        assertEquals(healthy, first.nextCandidate)
        assertTrue(first.incompleteUnreadEvidenceBlocked)
        assertTrue(first.incompleteUnreadAllowsIndependentTraversal)
        assertEquals(1, executor.unresolvedIncompleteUnreadKeys.size)
        assertTrue(executor.unresolvedUnknownUnreadKeys.isEmpty())
        val repaired = candidate(1, "Badge pending", ChatUnreadStatus.UNREAD, badgePresent = true)
        val next = executor.evaluateListSnapshot(snapshot(healthy, repaired), false, 2)
        assertFalse(next.incompleteUnreadEvidenceBlocked)
        assertEquals(repaired, next.nextCandidate)
        assertTrue(key(repaired) in executor.pendingKnownUnreadKeys())
        assertTrue(executor.attemptedSessionKeys.isEmpty())
    }

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
        top: Int = 100 + rowIndex * 150,
        previewBoundsValid: Boolean = true,
        badgePresent: Boolean = false,
        rowHeight: Int = 140,
        clippedAtEdge: Boolean = false,
        selectionClass: String = "PRIVATE_CHAT",
        previewBounds: ScreenRect = ScreenRect(150, top + 50, 620, top + 90),
        previewBoundsIntersectViewport: Boolean = true,
        previewBoundsFullyInsideViewport: Boolean = true,
        rowVisible: Boolean = true,
        inViewport: Boolean = true,
        windowGeneration: Long = 1L,
        nameNodeVisible: Boolean = true,
        previewNodeVisible: Boolean = true
    ): ChatListCandidateSnapshot {
        val row = ScreenRect(0, top, 720, top + rowHeight)
        val nameBounds = ScreenRect(150, top + 10, 380, top + 40)
        return ChatListCandidateSnapshot(
            rowIndex = rowIndex,
            rowTarget = NodeTarget(
                WindowRef("com.immomo.momo", "MaintabActivity", windowGeneration, 1),
                NodePath(listOf(0, rowIndex)),
                expectedClassName = "android.widget.RelativeLayout"
            ),
            rowBounds = row,
            rowVisibleToUser = rowVisible,
            intersectsViewport = inViewport,
            fullyInsideViewport = inViewport,
            rowBoundsValid = true,
            avatarBounds = ScreenRect(20, top + 10, 110, top + 100),
            avatarPath = NodePath(listOf(0, rowIndex, 0)),
            nameText = name,
            nameSource = ChatNodeTextSource.NODE_TEXT,
            nameNodePath = NodePath(listOf(0, rowIndex, 1)),
            nameBounds = nameBounds,
            nameNodeVisibleToUser = nameNodeVisible,
            nameBoundsIntersectViewport = true,
            nameBoundsFullyInsideViewport = true,
            nameBoundsValid = true,
            previewText = "Preview $rowIndex",
            previewSource = ChatNodeTextSource.NODE_TEXT,
            previewNodePath = NodePath(listOf(0, rowIndex, 2)),
            previewBounds = previewBounds,
            previewNodeVisibleToUser = previewNodeVisible,
            previewBoundsIntersectViewport = previewBoundsIntersectViewport,
            previewBoundsFullyInsideViewport = previewBoundsFullyInsideViewport,
            previewBoundsValid = previewBoundsValid,
            timestampText = "10:20",
            timestampSource = ChatNodeTextSource.NODE_TEXT,
            timestampNodePath = null,
            timestampBounds = null,
            timestampNodeVisibleToUser = false,
            statusNewNodePresent = badgePresent,
            unreadStatus = unread,
            selectionClass = selectionClass,
            rowClippedAtViewportEdge = clippedAtEdge
        )
    }
}
