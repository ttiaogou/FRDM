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
    fun listSnapshotEvaluationResetsAttemptedRowsOnlyForExplicitUnreadWakeup() {
        val executor = PrivateMessagesTraversalTaskExecutor().apply {
            initialInboxTopPositioningCompleted = true
        }
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
        assertEquals(null, executor.checkpointNextCursorKey)
        assertEquals(setOf("run-blocked"), executor.ambiguousSessionIdentityKeys)
        assertEquals("verified-send", executor.checkpointLastSuccessfulTaskId)
        assertEquals(7L, executor.checkpointRunGeneration)
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
