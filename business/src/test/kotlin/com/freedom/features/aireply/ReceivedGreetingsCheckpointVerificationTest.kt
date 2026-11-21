package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.MomoSayHiDeferredRowCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.recovery.RecoveryCheckpointRestoreSource
import com.freedom.features.recovery.RecoveryCheckpointVerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedGreetingsCheckpointVerificationTest {
    private val checkpoint = MomoSayHiTraversalCheckpointRecord(
        processedRowKeys = linkedSetOf("row-hash"),
        processedContactIdHashes = setOf("contact-hash"),
        deferredRows = mapOf(
            "deferred-hash" to MomoSayHiDeferredRowCheckpointRecord(2, 9_000L)
        ),
        deferredReturnToTopActive = true,
        deferredReplayStarted = false,
        savedAtEpochMs = 123L
    )

    private fun page(
        pageKind: ChatPageKind = ChatPageKind.NON_CHAT,
        packageName: String = "com.immomo.momo"
    ) = ChatPageSnapshot(
        packageName = packageName,
        activityClass = "NewSayHiSessionListActivity",
        pageKind = pageKind,
        scannedNodeCount = 38,
        editableInputCount = 0,
        messageListCount = 0,
        sendActionCount = 0,
        messageRelatedViewIdCount = 0,
        hasChatTab = false,
        messageListTarget = null,
        inputFieldTarget = null
    )

    private fun fixture(): Pair<ReceivedGreetingsCheckpointVerification, FakeScheduler> {
        val scheduler = FakeScheduler()
        return ReceivedGreetingsCheckpointVerification(
            scheduler = scheduler,
            targetPackage = { "com.immomo.momo" },
            currentRunGeneration = { 8L },
            onReadOnlyObservation = { scheduler.observations++ }
        ) to scheduler
    }

    private fun ReceivedGreetingsCheckpointVerification.sample(
        generation: Long,
        fingerprint: String = "verified-window-fingerprint",
        source: RecoveryCheckpointRestoreSource = RecoveryCheckpointRestoreSource.SERVICE_RECONNECT,
        kind: ChatPageKind = ChatPageKind.NON_CHAT,
        packageName: String = "com.immomo.momo",
        routeVerified: Boolean = true,
        status: ReceivedGreetingsTraversalStatus = ReceivedGreetingsTraversalStatus.SCANNING
    ): RecoveryCheckpointVerificationResult = observe(
        checkpoint = checkpoint,
        checkpointId = "AI_REPLY_RECEIVED_GREETINGS:SERVICE_RECONNECT_CHECKPOINT",
        recoveryReason = "SERVICE_RECONNECT_CHECKPOINT",
        restoreSource = source,
        snapshot = page(kind, packageName),
        pageFingerprint = fingerprint,
        routeVerified = routeVerified,
        executionStatus = status,
        observationGeneration = generation
    )


    @Test
    fun missingSnapshotBreaksGreetingsProofAndRequiresTwoNewFingerprints() {
        val (verifier, _) = fixture()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L))
        assertTrue(verifier.reject(2L) is RecoveryCheckpointVerificationResult.Recovering)
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(3L))
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady(ReceivedGreetingsTraversalStatus.SCANNING.name),
            verifier.sample(4L)
        )
        assertEquals(setOf("row-hash"), checkpoint.processedRowKeys)
    }

    @Test
    fun twoFreshWindowFingerprintsRequiredBeforeRestoredGreetingsCanResume() {
        val (verifier, scheduler) = fixture()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L))
        assertEquals(listOf(500L), scheduler.pendingDelays())
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady(ReceivedGreetingsTraversalStatus.SCANNING.name),
            verifier.sample(2L)
        )
        assertTrue(verifier.completeResume())
        assertFalse(verifier.hasActiveVerification())
        assertTrue(scheduler.pendingDelays().isEmpty())
        // The generic verification must not mutate the business checkpoint or retry queue.
        assertEquals(setOf("row-hash"), checkpoint.processedRowKeys)
        assertEquals(2, checkpoint.deferredRows.getValue("deferred-hash").attempts)
        assertTrue(checkpoint.deferredReturnToTopActive)
    }

    @Test
    fun missingRouteOrNonGreetingsPageCannotCountAsSecondSample() {
        val (verifier, _) = fixture()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L))
        assertTrue(verifier.sample(2L, routeVerified = false) is RecoveryCheckpointVerificationResult.Recovering)
        assertTrue(verifier.sample(3L, kind = ChatPageKind.CHAT_LIST) is RecoveryCheckpointVerificationResult.Recovering)
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(4L))
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady(ReceivedGreetingsTraversalStatus.SCANNING.name),
            verifier.sample(5L)
        )
    }

    @Test
    fun changedWindowFingerprintOrExecutionStageMustRevalidate() {
        val (verifier, _) = fixture()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L, fingerprint = "A"))
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(2L, fingerprint = "B"))
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(
            3L, fingerprint = "B", status = ReceivedGreetingsTraversalStatus.NAVIGATING
        ))
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady(ReceivedGreetingsTraversalStatus.NAVIGATING.name),
            verifier.sample(4L, fingerprint = "B", status = ReceivedGreetingsTraversalStatus.NAVIGATING)
        )
    }

    @Test
    fun duplicateGenerationWrongPackageAndUnmatchedPageNeverAuthorizeResume() {
        val (verifier, _) = fixture()
        assertEquals(
            RecoveryCheckpointVerificationResult.NoActiveCheckpoint,
            verifier.sample(1L, routeVerified = false)
        )
        assertEquals(
            RecoveryCheckpointVerificationResult.NoActiveCheckpoint,
            verifier.sample(2L, packageName = "unrelated.package")
        )
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(3L))
        assertEquals(RecoveryCheckpointVerificationResult.StaleEvidence, verifier.sample(3L))
        assertEquals(RecoveryCheckpointVerificationResult.StaleEvidence, verifier.reject(2L))
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady(ReceivedGreetingsTraversalStatus.SCANNING.name),
            verifier.sample(4L)
        )
    }

    @Test
    fun adapterConfirmationOrOwnershipRejectionRequiresTwoNewSamples() {
        val (verifier, _) = fixture()
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(1L))
        assertTrue(verifier.sample(2L) is RecoveryCheckpointVerificationResult.ResumeReady)
        verifier.deferResume()
        assertFalse(verifier.hasActiveVerification())
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(3L))
        assertTrue(verifier.sample(4L) is RecoveryCheckpointVerificationResult.ResumeReady)
    }

    @Test
    fun serviceResetCancelsOldObservationAndRequiresFreshProof() {
        val (verifier, scheduler) = fixture()
        verifier.sample(1L)
        assertEquals(listOf(500L), scheduler.pendingDelays())
        verifier.reset()
        assertTrue(scheduler.pendingDelays().isEmpty())
        scheduler.runCancelled(500L)
        assertEquals(0, scheduler.observations)
        assertEquals(RecoveryCheckpointVerificationResult.Verifying(1), verifier.sample(2L))
    }

    @Test
    fun currentUnknownPageCheckpointUsesExistingPageObservationSource() {
        val (verifier, _) = fixture()
        assertEquals(
            RecoveryCheckpointVerificationResult.Verifying(1),
            verifier.sample(1L, source = RecoveryCheckpointRestoreSource.PAGE_OBSERVATION)
        )
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady(ReceivedGreetingsTraversalStatus.SCANNING.name),
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
            entries.first { it.cancelled && it.delay == delay }.callback()
        }
    }
}
