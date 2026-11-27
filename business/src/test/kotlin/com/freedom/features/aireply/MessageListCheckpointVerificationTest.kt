package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpoint
import com.freedom.features.recovery.RecoveryCheckpointRestoreSource
import com.freedom.features.recovery.RecoveryCheckpointVerificationResult
import com.freedom.features.recovery.RecoveryScheduleRepository
import com.freedom.features.recovery.RecoveryScheduleSnapshot
import com.freedom.features.recovery.RecoveryEventDispatcher
import com.freedom.features.recovery.RecoveryEventIdentity
import com.freedom.features.recovery.RecoveryTransactionEvent
import com.freedom.features.recovery.RecoveryTransactionState
import com.freedom.features.recovery.UnifiedRecoveryTransactionManager
import com.freedom.features.recovery.UnifiedRecoveryTransactionStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListCheckpointVerificationTest {
    private val original = PrivateMessagesTraversalCheckpoint(
        attemptedSessionKeys = setOf("attempted-hash"),
        lockedUnreadSessionKeys = setOf("locked-hash"),
        ambiguousSessionIdentityKeys = emptySet(),
        lastStableVisibleKeys = listOf("old-viewport"),
        phase = "SCANNING_BOTTOM_TO_TOP",
        atTop = false,
        initialInboxPositionCheckCompleted = true,
        initialUnreadGateSwipeCompleted = true,
        lockedUnreadSetFinalized = true,
        normalInboxHadUnread = false,
        normalInboxUnreadStateUnknown = false,
        savedAtEpochMs = 123L,
        runGeneration = 1L,
        nextCursorKey = "cursor-hash"
    )

    private fun page(
        kind: ChatPageKind = ChatPageKind.CHAT_LIST,
        pkg: String = "com.immomo.momo"
    ): ChatPageSnapshot = ChatPageSnapshot(
        packageName = pkg,
        activityClass = "MainActivity",
        pageKind = kind,
        scannedNodeCount = 15,
        editableInputCount = 0,
        messageListCount = 0,
        sendActionCount = 0,
        messageRelatedViewIdCount = 0,
        hasChatTab = true,
        messageListTarget = null,
        inputFieldTarget = null
    )

    private fun fixture(
        scheduleRepository: RecoveryScheduleRepository? = null,
        recoveryTransactionManager: UnifiedRecoveryTransactionManager? = null
    ): Pair<MessageListCheckpointVerification, FakeScheduler> {
        val scheduler = FakeScheduler()
        return MessageListCheckpointVerification(
            scheduler = scheduler,
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 9L },
            onReadOnlyObservation = { scheduler.observations++ },
            scheduleRepository = scheduleRepository,
            recoveryTransactionManager = recoveryTransactionManager
        ) to scheduler
    }

    private fun MessageListCheckpointVerification.sample(
        gen: Long,
        fingerprint: String = "fresh-structural-page",
        source: RecoveryCheckpointRestoreSource = RecoveryCheckpointRestoreSource.PROCESS_RESTART,
        routeVerified: Boolean = true,
        currentPage: ChatPageSnapshot = page()
    ): RecoveryCheckpointVerificationResult = observe(
        checkpoint = original,
        checkpointId = "AI_REPLY_PRIVATE_MESSAGES:PROCESS_RESTART_CHECKPOINT",
        recoveryReason = "PROCESS_RESTART_CHECKPOINT",
        restoreSource = source,
        snapshot = currentPage,
        pageFingerprint = fingerprint,
        routeVerified = routeVerified,
        observationGeneration = gen
    )


    @Test
    fun p2StablePageDoesNotCompleteWhileOriginalBusinessFaultIsBlocked() {
        val (verifier, scheduler) = fixture()
        fun sample(gen: Long, evidence: PrivateListRecoveryFaultEvidence) = verifier.observe(
            checkpoint = original,
            checkpointId = "P2_ORIGINAL_FAULT",
            recoveryReason = "PRIVATE_INCOMPLETE_UNREAD_ROW",
            restoreSource = RecoveryCheckpointRestoreSource.PAGE_OBSERVATION,
            snapshot = page(),
            pageFingerprint = "same-stable-structure",
            routeVerified = true,
            observationGeneration = gen,
            originalFaultEvidence = evidence
        )
        val blocked = PrivateListRecoveryFaultEvidence.ORIGINAL_INCOMPLETE_STILL_BLOCKED
        val cleared = PrivateListRecoveryFaultEvidence.CLEARED
        assertTrue(sample(1L, blocked) is RecoveryCheckpointVerificationResult.Recovering)
        assertTrue(sample(2L, blocked) is RecoveryCheckpointVerificationResult.Recovering)
        assertFalse(verifier.completeResume())
        assertEquals(listOf(500L), scheduler.pendingDelays())
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), sample(3L, cleared))
        assertTrue(sample(4L, blocked) is RecoveryCheckpointVerificationResult.Recovering)
        assertFalse(verifier.completeResume())
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), sample(5L, cleared))
        assertEquals(RecoveryCheckpointVerificationResult.StaleEvidence, sample(5L, cleared))
        assertTrue(sample(6L, cleared) is RecoveryCheckpointVerificationResult.ResumeReady)
        assertTrue(verifier.completeResume())
        assertTrue(scheduler.pendingDelays().isEmpty())
    }

    @Test
    fun p3BlockedSamplesAndChangedScopePreserveSameBackoffAcrossRebinds() {
        val (verifier, scheduler) = fixture()
        fun sample(gen: Long, fingerprint: String, evidence: PrivateListRecoveryFaultEvidence) =
            verifier.observe(
                checkpoint = original,
                checkpointId = "P3_SAME_CHECKPOINT",
                recoveryReason = "PRIVATE_INCOMPLETE_UNREAD_ROW",
                restoreSource = RecoveryCheckpointRestoreSource.PAGE_OBSERVATION,
                snapshot = page(),
                pageFingerprint = fingerprint,
                routeVerified = true,
                observationGeneration = gen,
                originalFaultEvidence = evidence
            )
        val blocked = PrivateListRecoveryFaultEvidence.ORIGINAL_INCOMPLETE_STILL_BLOCKED
        val cleared = PrivateListRecoveryFaultEvidence.CLEARED
        assertTrue(sample(1L, "A", blocked) is RecoveryCheckpointVerificationResult.Recovering)
        scheduler.runNext(500L)
        assertEquals(listOf(1_000L), scheduler.pendingDelays())
        assertTrue(sample(2L, "B", blocked) is RecoveryCheckpointVerificationResult.Recovering)
        assertEquals(listOf(1_000L), scheduler.pendingDelays())
        scheduler.runNext(1_000L)
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), sample(3L, "B", cleared))
        assertEquals(listOf(2_000L), scheduler.pendingDelays())
        verifier.deferOwnership()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), sample(4L, "B", cleared))
        assertEquals(listOf(2_000L), scheduler.pendingDelays())
        assertFalse(verifier.completeResume())
        assertTrue(sample(5L, "B", cleared) is RecoveryCheckpointVerificationResult.ResumeReady)
        assertTrue(verifier.completeResume())
        assertTrue(scheduler.pendingDelays().isEmpty())
    }

    @Test
    fun p2ClearedAndLocalRemediationProofsCannotShareStableSamples() {
        val (verifier, _) = fixture()
        fun sample(gen: Long, evidence: PrivateListRecoveryFaultEvidence) = verifier.observe(
            checkpoint = original,
            checkpointId = "P2_SCOPE",
            recoveryReason = "PRIVATE_INCOMPLETE_UNREAD_ROW",
            restoreSource = RecoveryCheckpointRestoreSource.PAGE_OBSERVATION,
            snapshot = page(),
            pageFingerprint = "same-structure",
            routeVerified = true,
            observationGeneration = gen,
            originalFaultEvidence = evidence
        )
        val local = PrivateListRecoveryFaultEvidence.LOCAL_REMEDIATION
        val cleared = PrivateListRecoveryFaultEvidence.CLEARED
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), sample(1L, local))
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), sample(2L, cleared))
        assertFalse(verifier.completeResume())
        assertTrue(sample(3L, cleared) is RecoveryCheckpointVerificationResult.ResumeReady)
        assertTrue(verifier.completeResume())
    }

    @Test
    fun temporarilyUnavailableSnapshotRejectsPriorSampleAndRetainsCheckpoint() {
        val (verifier, _) = fixture()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L))
        assertTrue(verifier.reject(2L) is RecoveryCheckpointVerificationResult.Recovering)
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(3L))
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("SCANNING_BOTTOM_TO_TOP"),
            verifier.sample(4L)
        )
        assertEquals(setOf("attempted-hash"), original.attemptedSessionKeys)
    }


    @Test
    fun restoredMessageListScheduleKeepsBackoffButRequiresTwoFreshObservations() {
        var saved: RecoveryScheduleSnapshot? = RecoveryScheduleSnapshot(
            taskId = "AI_REPLY_PRIVATE_MESSAGES", routeId = "AI_REPLY_PRIVATE_MESSAGES",
            runGeneration = 2L, recoveryReason = "CHECKPOINT_LOADED",
            checkpointId = "AI_REPLY_PRIVATE_MESSAGES:PROCESS_RESTART_CHECKPOINT",
            observationCount = 5L, nextObservationAtEpochMs = Long.MAX_VALUE,
            backoffStage = 3, touchActive = false
        )
        val repository = object : RecoveryScheduleRepository {
            override fun load(): RecoveryScheduleSnapshot? = saved
            override fun save(snapshot: RecoveryScheduleSnapshot) { saved = snapshot }
            override fun clear() { saved = null }
        }
        val (verifier, scheduler) = fixture(repository)

        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L))
        assertEquals(5L, saved?.observationCount)
        assertEquals(9L, saved?.runGeneration)
        assertEquals(listOf(5_000L), scheduler.pendingDelays())
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("SCANNING_BOTTOM_TO_TOP"),
            verifier.sample(2L)
        )
        assertTrue(verifier.completeResume())
        assertTrue(scheduler.pendingDelays().isEmpty())
        assertEquals(null, repository.load())
    }


    @Test
    fun unknownCheckpointPlanRestoresAcrossReconnectAndProcessRestartButNotNewCheckpoint() {
        val task = "AI_REPLY_PRIVATE_MESSAGES"
        var stored: RecoveryScheduleSnapshot? = RecoveryScheduleSnapshot(
            taskId = task, routeId = task, runGeneration = 2L, recoveryReason = "PAGE_ROUTE_CHANGED",
            checkpointId = AiReplyObservationRuntime.durableRecoveryCheckpointId(
                task, original.savedAtEpochMs, "PAGE_ROUTE_CHANGED"
            ),
            observationCount = 11L, nextObservationAtEpochMs = Long.MAX_VALUE,
            backoffStage = 4, touchActive = false
        )
        val repository = object : RecoveryScheduleRepository {
            override fun load(): RecoveryScheduleSnapshot? = stored
            override fun save(snapshot: RecoveryScheduleSnapshot) { stored = snapshot }
            override fun clear() { stored = null }
        }
        for (source in listOf(
            RecoveryCheckpointRestoreSource.SERVICE_RECONNECT,
            RecoveryCheckpointRestoreSource.PROCESS_RESTART
        )) {
            val (verifier, scheduler) = fixture(repository)
            assertEquals(
                RecoveryCheckpointVerificationResult.Verifying(1),
                verifier.observe(
                    checkpoint = original,
                    checkpointId = AiReplyObservationRuntime.durableRecoveryCheckpointId(
                        task, original.savedAtEpochMs, source.name
                    ),
                    recoveryReason = source.name, restoreSource = source,
                    snapshot = page(), pageFingerprint = "fresh-page",
                    routeVerified = true, observationGeneration = 1L
                )
            )
            assertEquals(11L, stored?.observationCount)
            assertEquals(4, stored?.backoffStage)
            assertEquals(9L, stored?.runGeneration)
            // Rebinding preserves the stage but its remaining delay decreases with wall time.
            assertTrue(scheduler.pendingDelays().single() in 0L..10_000L)
            verifier.reset()
        }
        val changedCheckpoint = original.copy(savedAtEpochMs = original.savedAtEpochMs + 1L)
        val (verifier, scheduler) = fixture(repository)
        assertEquals(
            RecoveryCheckpointVerificationResult.Verifying(1),
            verifier.observe(
                checkpoint = changedCheckpoint,
                checkpointId = AiReplyObservationRuntime.durableRecoveryCheckpointId(
                    task, changedCheckpoint.savedAtEpochMs, "PROCESS_RESTART_CHECKPOINT"
                ),
                recoveryReason = "PROCESS_RESTART_CHECKPOINT",
                restoreSource = RecoveryCheckpointRestoreSource.PROCESS_RESTART,
                snapshot = page(), pageFingerprint = "fresh-page",
                routeVerified = true, observationGeneration = 1L
            )
        )
        assertEquals(0L, stored?.observationCount)
        assertEquals(0, stored?.backoffStage)
        assertEquals(listOf(500L), scheduler.pendingDelays())
    }

    @Test
    fun twoFreshStructuralSamplesAreRequiredAndTimerCompletesAfterResume() {
        val (verifier, scheduler) = fixture()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L))
        assertEquals(listOf(500L), scheduler.pendingDelays())
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("SCANNING_BOTTOM_TO_TOP"),
            verifier.sample(2L)
        )
        assertTrue(verifier.completeResume())
        assertFalse(verifier.hasActiveVerification())
        assertTrue(scheduler.pendingDelays().isEmpty())
        // The shared verification never mutates business traversal state.
        assertEquals(setOf("attempted-hash"), original.attemptedSessionKeys)
        assertEquals("cursor-hash", original.nextCursorKey)
    }

    @Test
    fun mismatchedAndMissingRouteResetsSamplesInsteadOfReplayingCheckpoint() {
        val (verifier, _) = fixture()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L))
        assertTrue(verifier.reject(2L) is RecoveryCheckpointVerificationResult.Recovering)
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(3L))
        assertFalse(verifier.sample(3L) is RecoveryCheckpointVerificationResult.ResumeReady)
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("SCANNING_BOTTOM_TO_TOP"),
            verifier.sample(4L)
        )
    }


    @Test
    fun sharedManagerRebindsChangedFingerprintWithoutSupersedingPrimaryRecovery() {
        val manager = UnifiedRecoveryTransactionManager({ 9L })
        val (verifier, _) = fixture(recoveryTransactionManager = manager)
        val task = PrivateMessagesTaskController.TASK_ID
        val checkpointId = "AI_REPLY_PRIVATE_MESSAGES:PROCESS_RESTART_CHECKPOINT"
        val primary = RecoveryEventDispatcher(
            UnifiedRecoveryTransactionStateMachine(), sharedManager = manager
        )
        primary.activate(RecoveryEventIdentity(task, task, 9L, checkpointId, 0L))
        primary.dispatch(RecoveryTransactionEvent.UnknownPage("PAGE_ROUTE_CHANGED"))

        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L, "page-A"))
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(2L, "page-B"))
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot(task)?.state)
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("SCANNING_BOTTOM_TO_TOP"),
            verifier.sample(3L, "page-B")
        )
        assertTrue(verifier.completeResume())
        assertEquals(RecoveryTransactionState.RECOVERING, manager.snapshot(task)?.state)
        assertEquals(null, manager.snapshot(task, "AI_REPLY_PRIVATE_MESSAGES_CHECKPOINT"))
    }

    @Test
    fun sharedManagerDeniedOwnershipAndResetRequireFreshTwoSampleProof() {
        val manager = UnifiedRecoveryTransactionManager({ 9L })
        val (verifier, _) = fixture(recoveryTransactionManager = manager)
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L))
        assertTrue(verifier.sample(2L) is RecoveryCheckpointVerificationResult.ResumeReady)
        verifier.deferOwnership()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(3L))
        assertTrue(verifier.sample(4L) is RecoveryCheckpointVerificationResult.ResumeReady)
        verifier.reset()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(5L))
        assertTrue(verifier.sample(6L) is RecoveryCheckpointVerificationResult.ResumeReady)
    }

    @Test
    fun aChangedFreshFingerprintStartsAnIndependentTwoSampleCheck() {
        val (verifier, _) = fixture()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L, "page-A"))
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(2L, "page-B"))
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("SCANNING_BOTTOM_TO_TOP"),
            verifier.sample(3L, "page-B")
        )
    }

    @Test
    fun unconfirmedRouteWrongPackageAndStaleGenerationsNeverAuthorizeResume() {
        val (verifier, _) = fixture()
        assertEquals(
            RecoveryCheckpointVerificationResult.NoActiveCheckpoint,
            verifier.sample(1L, routeVerified = false)
        )
        assertEquals(
            RecoveryCheckpointVerificationResult.NoActiveCheckpoint,
            verifier.sample(2L, currentPage = page(pkg = "other.package"))
        )
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(3L))
        assertEquals(RecoveryCheckpointVerificationResult.StaleEvidence, verifier.sample(3L))
        assertEquals(RecoveryCheckpointVerificationResult.StaleEvidence, verifier.reject(2L))
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("SCANNING_BOTTOM_TO_TOP"),
            verifier.sample(4L)
        )
    }

    @Test
    fun deniedOwnershipRequiresTwoNewObservationsAndNoImmediateResume() {
        val (verifier, _) = fixture()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L))
        assertTrue(verifier.sample(2L) is RecoveryCheckpointVerificationResult.ResumeReady)
        verifier.deferOwnership()
        assertFalse(verifier.hasActiveVerification())
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(3L))
        assertTrue(verifier.sample(4L) is RecoveryCheckpointVerificationResult.ResumeReady)
    }

    @Test
    fun resetCancelsOldReadOnlyCallbackAndRestoresOnlyFromFreshData() {
        val (verifier, scheduler) = fixture()
        verifier.sample(1L, source = RecoveryCheckpointRestoreSource.SERVICE_RECONNECT)
        assertEquals(listOf(500L), scheduler.pendingDelays())
        verifier.reset()
        assertTrue(scheduler.pendingDelays().isEmpty())
        scheduler.runCancelled(500L)
        assertEquals(0, scheduler.observations)
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(2L))
        assertEquals(listOf(500L), scheduler.pendingDelays())
    }

    @Test
    fun independentlyWiredCheckpointVerifiersDoNotSharePageProofOrTimers() {
        val (first, firstScheduler) = fixture()
        val (second, secondScheduler) = fixture()

        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), first.sample(1L))
        assertFalse(second.hasActiveVerification())
        assertEquals(listOf(500L), firstScheduler.pendingDelays())
        assertTrue(secondScheduler.pendingDelays().isEmpty())

        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), second.sample(1L))
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("SCANNING_BOTTOM_TO_TOP"),
            first.sample(2L)
        )
        // The second verifier must still require its own second fresh observation.
        assertTrue(second.hasActiveVerification())
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("SCANNING_BOTTOM_TO_TOP"),
            second.sample(2L)
        )
        assertTrue(first.completeResume())
        assertFalse(first.hasActiveVerification())
        assertTrue(second.completeResume())
    }

    @Test
    fun unknownPageCheckpointUsesTheSharedPageObservationRestoreSource() {
        val (verifier, _) = fixture()
        assertEquals(
            RecoveryCheckpointVerificationResult.Verifying(1),
            verifier.sample(1L, source = RecoveryCheckpointRestoreSource.PAGE_OBSERVATION)
        )
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("SCANNING_BOTTOM_TO_TOP"),
            verifier.sample(2L, source = RecoveryCheckpointRestoreSource.PAGE_OBSERVATION)
        )
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(val delay: Long, val callback: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()
        var observations = 0

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun pendingDelays(): List<Long> = entries.filterNot { it.cancelled }.map { it.delay }

        fun runCancelled(delay: Long) {
            val entry = entries.first { it.cancelled && it.delay == delay }
            entry.callback()
        }

        fun runNext(delay: Long) {
            val entry = entries.first { !it.cancelled && it.delay == delay }
            entry.cancelled = true
            entry.callback()
        }
    }
}
