package com.freedom.features.aireply

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatNodeTextSource
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun staleUnknownAdmissionClearsOnlyAfterSameViewportBlockedRowsBecomeExplicit() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
            initialUnreadGateSwipeCompleted = true
        }
        val known = candidate(0, "Known", ChatUnreadStatus.UNREAD)
        val unresolved = candidate(1, "Unknown", ChatUnreadStatus.UNKNOWN)
        val blocked = executor.evaluateListSnapshot(snapshot(known, unresolved), false, 2)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED, blocked.unreadAdmissionDecision)
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
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
            executor.evaluateListSnapshot(snapshot(known, unknown), false, 2).unreadAdmissionDecision)

        val different = snapshot(candidate(0, "Other", ChatUnreadStatus.UNREAD))
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
            executor.evaluateListSnapshot(different, false, 2).unreadAdmissionDecision)
        assertTrue(executor.normalInboxUnreadStateUnknown)

        val stillUnknown = snapshot(known, unknown)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
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
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
            executor.evaluateListSnapshot(first, false, 2).unreadAdmissionDecision)

        // The row still has an unknown status, but is now ineligible due to missing geometry.
        // It is unsafe to declare the old evidence resolved just because eligibleUnknown=0.
        val incomplete = snapshot(known, candidate(1, "Uncertain", ChatUnreadStatus.UNKNOWN, previewBoundsValid = false))
        executor.recordViewportUnreadEvidence(incomplete, receivedGreetingsActive = false)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
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
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        assertEquals(
            PrivateMessagesUnreadAdmissionDecision.NO_UNREAD,
            executor.evaluateUnreadAdmission(snapshot(candidate(2, "Read", ChatUnreadStatus.READ)))
        )

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
            PrivateMessagesUnreadAdmissionDecision.FIRST_SWIPE_REQUIRED,
            afterTopConfirmation.unreadAdmissionDecision
        )
        assertEquals(null, afterTopConfirmation.nextCandidate)
        assertTrue(executor.lockedUnreadSessionKeys.isEmpty())
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
        assertEquals(SessionListTraversalPhase.POSITIONING_AT_BOTTOM, executor.phase)
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
            snapshot(next),
            wasWaitingForUnread = false,
            maxNoProgressScrolls = 2,
            allowExpectedSessionReturnViewportChange = true
        )

        assertFalse(evaluation.viewportPositionInvalidated)
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
        assertEquals(next, evaluation.nextCandidate)
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

        executor.resetForReturnedInbox()

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

        executor.beginNextPollingRound()

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

        // A verified return from greetings is still the SAME round.
        executor.resumeAfterReturnedInbox()
        assertEquals(setOf(key(a), key(b)), executor.attemptedSessionKeys)
        assertEquals(setOf(key(a), key(b)), executor.lockedUnreadSessionKeys)
        assertEquals(setOf("old-snapshot-capture"), executor.capturedSessionListFingerprints)
        assertEquals(key(b), executor.checkpointNextCursorKey)
        assertEquals(null, executor.selectNextCandidate(
            snapshot(a, b, c).chatListCandidates, executor.lockedUnreadSessionKeys
        ))

        // Only a separately verified next-round entry retires frozen membership.
        executor.beginNextPollingRound()
        assertTrue(executor.attemptedSessionKeys.isEmpty())
        assertTrue(executor.lockedUnreadSessionKeys.isEmpty())
        assertTrue(executor.capturedSessionListFingerprints.isEmpty())
        assertFalse(executor.lockedUnreadSetFinalized)
        assertEquals("confirmed-a-or-b-receipt", executor.checkpointLastSuccessfulTaskId)
        assertEquals(42L, executor.checkpointRunGeneration)
        assertEquals(null, executor.checkpointNextCursorKey)

        // A/B are read; C arrives unread. Existing first-swipe admission remains mandatory.
        executor.initialInboxTopPositioningCompleted = true
        assertEquals(PrivateMessagesUnreadAdmissionDecision.FIRST_SWIPE_REQUIRED,
            executor.evaluateUnreadAdmission(snapshot(a, b, c)))
        executor.initialUnreadGateSwipeCompleted = true
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

        assertTrue(runCatching { executor.beginNextPollingRound() }.isFailure)
        assertEquals(setOf("previously-attempted"), executor.attemptedSessionKeys)
        assertEquals(setOf("previously-frozen"), executor.lockedUnreadSessionKeys)
        assertEquals(setOf("previous-viewport"), executor.capturedSessionListFingerprints)
        assertEquals(setOf("unresolved"), executor.unresolvedUnknownUnreadKeys)
        assertEquals("previous-confirmed-send", executor.checkpointLastSuccessfulTaskId)
    }

    @Test
    fun timedFullPollingRoundDiscoversUnreadBelowInitiallyReadViewport() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        val visibleRead = candidate(0, "Visible read A", ChatUnreadStatus.READ)
        val furtherRead = candidate(1, "Visible read B", ChatUnreadStatus.READ)
        val offscreenUnread = candidate(2, "Offscreen unread C", ChatUnreadStatus.UNREAD)

        // Preserve the original first-run fast-path; P6 applies to VERIFIED timed rounds.
        executor.initialUnreadGateSwipeCompleted = true
        assertEquals(PrivateMessagesUnreadAdmissionDecision.NO_UNREAD,
            executor.evaluateUnreadAdmission(snapshot(visibleRead)))
        executor.beginNextPollingRound()
        assertTrue(executor.fullListPollingRoundRequired)
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true

        // The same apparently empty viewport is NOT proof that the whole list is read.
        executor.recordViewportUnreadEvidence(snapshot(visibleRead), false)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(visibleRead)))
        assertEquals(SessionListTraversalPhase.POSITIONING_AT_BOTTOM, executor.phase)
        assertFalse(executor.lockedUnreadSetFinalized)

        // New evidence from a lower viewport locks a formerly offscreen unread row.
        executor.recordViewportUnreadEvidence(snapshot(offscreenUnread), false)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(offscreenUnread)))
        assertEquals(setOf(key(offscreenUnread)), executor.lockedUnreadSessionKeys)
        assertTrue(executor.normalInboxHadUnread)

        // A later READ viewport must not erase the C evidence or end the round early.
        executor.recordViewportUnreadEvidence(snapshot(furtherRead), false)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(furtherRead)))
        assertTrue(executor.normalInboxHadUnread)
        assertFalse(executor.lockedUnreadSetFinalized)

        // The existing separately verified bottom-boundary transition owns scan admission.
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.lockedUnreadSetFinalized = true
        val evaluation = executor.evaluateListSnapshot(snapshot(offscreenUnread), false, 2)
        assertEquals(offscreenUnread, evaluation.nextCandidate)
        assertTrue(executor.fullListPollingRoundRequired)
    }

    @Test
    fun timedFullPollingCheckpointRestoresScanModeAndUnknownBlocksCompletion() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.checkpointLastSuccessfulTaskId = "confirmed-send-prior-round"
        executor.beginNextPollingRound()
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        val read = candidate(0, "Read only here", ChatUnreadStatus.READ)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            executor.evaluateUnreadAdmission(snapshot(read)))

        val saved = executor.checkpoint()
        assertTrue(saved.fullListPollingRoundRequired)
        val resumed = PrivateMessagesTraversalTaskExecutor().apply { restore(saved) }
        assertTrue(resumed.fullListPollingRoundRequired)
        assertEquals("confirmed-send-prior-round", resumed.checkpointLastSuccessfulTaskId)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            resumed.evaluateUnreadAdmission(snapshot(read)))

        val unknown = candidate(1, "Unread evidence unknown", ChatUnreadStatus.UNKNOWN)
        assertEquals(PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED,
            resumed.evaluateUnreadAdmission(snapshot(unknown)))
        assertTrue(resumed.normalInboxUnreadStateUnknown)
        assertEquals(setOf(key(unknown)), resumed.unresolvedUnknownUnreadKeys)
        assertTrue(runCatching { resumed.beginNextPollingRound() }.isFailure)
        assertTrue(resumed.fullListPollingRoundRequired)
        assertEquals("confirmed-send-prior-round", resumed.checkpointLastSuccessfulTaskId)

        // Older serialized checkpoints omit this optional field and retain original behavior.
        assertFalse(saved.copy(fullListPollingRoundRequired = false).fullListPollingRoundRequired)
        executor.reset()
        assertFalse(executor.fullListPollingRoundRequired)
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
        assertTrue(runCatching { recovered.beginNextPollingRound() }.isFailure)
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
        assertTrue(runCatching { restored.beginNextPollingRound() }.isFailure)

        val repaired = candidate(0, "Incomplete A", ChatUnreadStatus.UNREAD)
        assertFalse(restored.observeIncompleteUnreadEvidence(snapshot(repaired, healthy)))
        assertTrue(key(repaired) in restored.lockedUnreadSessionKeys)
        assertEquals(repaired, restored.selectNextCandidate(listOf(repaired), restored.lockedUnreadSessionKeys))
    }

    @Test
    fun p1RelocationPreservesRoundAndPersistsFullScanRequirement() {
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
        assertEquals(PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING,
            restored.evaluateUnreadAdmission(repairedPage))
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
        recovered.beginNextPollingRound()
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

        executor.beginNextPollingRound()
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
        assertTrue(restored.fullListPollingRoundRequired)
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
        assertTrue(runCatching { restored.beginNextPollingRound() }.isFailure)
        assertEquals(setOf(key(processedA)), restored.attemptedSessionKeys)

        // The original UNKNOWN viewport and incomplete row each need new explicit proof.
        assertFalse(restored.observeUnreadBlockers(snapshot(
            candidate(1, "Unknown B", ChatUnreadStatus.READ)
        )))
        assertTrue(restored.observeIncompleteUnreadEvidence(snapshot(
            candidate(5, "Different read row", ChatUnreadStatus.READ)
        )))
        assertTrue(runCatching { restored.beginNextPollingRound() }.isFailure)
        assertFalse(restored.observeIncompleteUnreadEvidence(snapshot(
            candidate(2, "Incomplete C", ChatUnreadStatus.UNREAD, previewBoundsValid = true)
        )))

        // Only now may a new round retire old A's selection cursor, not its send receipt
        // or the run-scoped identity conflict.
        restored.beginNextPollingRound()
        assertTrue(restored.attemptedSessionKeys.isEmpty())
        assertTrue(restored.lockedUnreadSessionKeys.isEmpty())
        assertTrue(restored.fullListPollingRoundRequired)
        assertTrue(restored.ambiguousSessionIdentityKeys.contains(blockedIdentity))
        assertEquals("confirmed-prior-send", restored.checkpointLastSuccessfulTaskId)
        assertEquals(17L, restored.checkpointRunGeneration)
        assertEquals(null, restored.checkpointNextCursorKey)
        restored.initialInboxTopPositioningCompleted = true
        assertEquals(PrivateMessagesUnreadAdmissionDecision.FIRST_SWIPE_REQUIRED,
            restored.evaluateUnreadAdmission(snapshot(newD)))
        restored.initialUnreadGateSwipeCompleted = true
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
        val saved = candidate(0, "Alice", ChatUnreadStatus.UNREAD, top = 100)
        executor.lastStableSessionListVisibleKeys = executor.visibleSessionKeys(snapshot(saved))
        executor.rememberRecoveryAnchor(saved)

        val moved = candidate(4, "Alice", ChatUnreadStatus.UNREAD, top = 900)
        val current = snapshot(candidate(1, "Other", ChatUnreadStatus.READ), moved)
        assertFalse(executor.isRestoredViewportCompatible(current))

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
    fun restoredViewportMismatchWithoutAnchorContinuesWithCurrentViewport() {
        val executor = PrivateMessagesTraversalTaskExecutor()
        executor.lastStableSessionListVisibleKeys = listOf("old@0,100,720,240")

        val current = snapshot(candidate(2, "New row", ChatUnreadStatus.UNREAD, top = 500))
        val result = executor.reanchorRestoredCheckpoint(current)

        assertEquals(
            PrivateMessagesTraversalTaskExecutor.RestoreReanchorStatus.NOT_FOUND,
            result.status
        )
        assertEquals(executor.visibleSessionKeys(current), executor.lastStableSessionListVisibleKeys)
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
        top: Int = 100 + rowIndex * 150,
        previewBoundsValid: Boolean = true
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
            previewBoundsValid = previewBoundsValid,
            timestampText = "10:20",
            timestampSource = ChatNodeTextSource.NODE_TEXT,
            timestampNodePath = null,
            timestampBounds = null,
            timestampNodeVisibleToUser = false,
            unreadStatus = unread
        )
    }
}
