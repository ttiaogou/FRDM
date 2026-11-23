package com.freedom.adapters.social.momo

import com.freedom.features.aireply.ports.MomoSayHiDeferredRowCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiTraversalStateTest {
    private class InMemoryRepository : MomoSayHiTraversalCheckpointRepository {
        var value: MomoSayHiTraversalCheckpointRecord? = null
        override fun load(nowEpochMs: Long): MomoSayHiTraversalCheckpointRecord? = value
        override fun save(checkpoint: MomoSayHiTraversalCheckpointRecord) {
            value = checkpoint
        }
        override fun clear() {
            value = null
        }
    }

    @Test
    fun deferralUsesTheExistingBoundedBackoffAndPersistsRetryMetadata() {
        val repository = InMemoryRepository()
        val state = MomoSayHiTraversalState(
            MomoSayHiTraversalCheckpointStore(repository),
            safeTargetFailureReasonPrefix = "SAFE_TARGET"
        )

        val first = state.deferRow("row-a", "TEMPORARY", nowEpochMs = 10_000L)
        val second = state.deferRow("row-a", "TEMPORARY", nowEpochMs = 20_000L)
        val third = state.deferRow("row-a", "TEMPORARY", nowEpochMs = 30_000L)
        val fourth = state.deferRow("row-a", "TEMPORARY", nowEpochMs = 40_000L)

        assertEquals(1_500L, first.delayMs)
        assertEquals(3_000L, second.delayMs)
        assertEquals(5_000L, third.delayMs)
        assertEquals(5_000L, fourth.delayMs)
        assertEquals(4, fourth.backoffStage)
        assertTrue(state.isDeferredRow("row-a"))
        assertTrue(state.isDeferredRetryDue("row-a", 45_000L))
        assertEquals(4, repository.value?.deferredRows?.get("row-a")?.attempts)
    }

    @Test
    fun safeTargetFailuresKeepTheirSeparateFiniteRetryBudget() {
        val state = MomoSayHiTraversalState(
            MomoSayHiTraversalCheckpointStore(InMemoryRepository()),
            safeTargetFailureReasonPrefix = "SAFE_TARGET"
        )

        val first = state.deferRow("row-a", "SAFE_TARGET:BOUNDS_CHANGED", 1_000L)
        val second = state.deferRow("row-a", "SAFE_TARGET:BOUNDS_CHANGED", 2_000L)
        val third = state.deferRow("row-a", "SAFE_TARGET:BOUNDS_CHANGED", 3_000L)

        assertEquals(1, first.safeTargetFailures)
        assertEquals(2, second.safeTargetFailures)
        assertEquals(3, third.safeTargetFailures)
        assertFalse(first.safeTargetRetryExhausted)
        assertFalse(second.safeTargetRetryExhausted)
        assertTrue(third.safeTargetRetryExhausted)
        assertFalse(state.shouldRetryDeferredRow("row-a"))
    }

    @Test
    fun restoreRebuildsOnlyEligibleDeferredRowsAndStartsAFreshForwardPass() {
        val repository = InMemoryRepository().apply {
            value = MomoSayHiTraversalCheckpointRecord(
                processedRowKeys = setOf("done"),
                processedContactIdHashes = setOf("contact-hash"),
                deferredRows = linkedMapOf(
                    "done" to MomoSayHiDeferredRowCheckpointRecord(1, 2_000L),
                    "pending" to MomoSayHiDeferredRowCheckpointRecord(2, 5_000L, 1)
                ),
                deferredReturnToTopActive = true,
                deferredReplayStarted = true
            )
        }
        val state = MomoSayHiTraversalState(MomoSayHiTraversalCheckpointStore(repository))

        val restored = state.restore()

        assertEquals(1, restored?.processedRows)
        assertEquals(1, restored?.deferredRows)
        assertEquals(1, restored?.processedContacts)
        assertTrue(state.isProcessedRow("done"))
        assertFalse(state.isDeferredRow("done"))
        assertTrue(state.isDeferredRow("pending"))
        assertFalse(state.deferredReturnToTopActive)
        assertFalse(state.deferredReplayStarted)
        assertFalse(state.isDeferredRetryDue("pending", 4_999L))
        assertTrue(state.isDeferredRetryDue("pending", 5_000L))
        assertTrue(state.hasRestorableCheckpoint)
    }

    @Test
    fun completedIdentityCanBeRemovedFromDeferredStateAndExplicitExitClearsCheckpoint() {
        val repository = InMemoryRepository()
        val state = MomoSayHiTraversalState(MomoSayHiTraversalCheckpointStore(repository))

        state.deferRow("row-a", "TEMPORARY", 1_000L)
        state.markProcessedRow("row-a")
        state.markProcessedContactHash("contact-hash")
        state.clearDeferral("row-a", 2_000L)

        assertTrue(state.isProcessedRow("row-a"))
        assertFalse(state.isDeferredRow("row-a"))
        assertTrue(state.isProcessedContactHash("contact-hash"))
        assertEquals(0, state.deferredRowCount)

        state.clear()
        assertEquals(0, state.processedRowCount)
        assertEquals(0, state.processedContactCount)
        assertNull(repository.value)
        assertFalse(state.hasRestorableCheckpoint)
    }

    @Test
    fun everyCheckpointMutationIsPersistedImmediately() {
        val repository = InMemoryRepository()
        val state = MomoSayHiTraversalState(MomoSayHiTraversalCheckpointStore(repository))

        state.markProcessedRow("row-a")
        assertTrue(repository.value?.processedRowKeys?.contains("row-a") == true)

        state.markProcessedContactHash("contact-a")
        assertTrue(repository.value?.processedContactIdHashes?.contains("contact-a") == true)

        state.setDeferredReturnToTopActive(true)
        state.setDeferredReplayStarted(true)
        assertTrue(repository.value?.deferredReturnToTopActive == true)
        assertTrue(repository.value?.deferredReplayStarted == true)
    }
    @Test
    fun exhaustedUnsafeRowNeverRetriesUntilChangedSafeEvidenceIsStableTwice() {
        val repository = InMemoryRepository()
        val state = MomoSayHiTraversalState(
            MomoSayHiTraversalCheckpointStore(repository),
            safeTargetFailureReasonPrefix = "SAFE_TARGET"
        )
        val unsafe = "a".repeat(64)
        val recovered = "b".repeat(64)
        repeat(3) { attempt ->
            state.deferRow("row-unsafe", "SAFE_TARGET:BOUNDS_UNVERIFIED",
                nowEpochMs = 1_000L + attempt * 1_000L, failureEvidenceHash = unsafe)
        }
        state.deferRow("row-other", "ITEM_NOT_READY", 4_000L)
        val stored = requireNotNull(repository.value?.deferredRows?.get("row-unsafe"))
        assertEquals(3, stored.safeTargetValidationFailures)
        assertEquals("SAFE_TARGET:BOUNDS_UNVERIFIED", stored.lastDeferralReason)
        assertEquals(unsafe, stored.failedSafeTargetEvidenceHash)
        assertEquals(33_000L, stored.retryAfterEpochMs)
        assertTrue(state.hasExhaustedSafeTargetRows)
        assertFalse(state.shouldRetryDeferredRow("row-unsafe"))
        assertFalse(state.isDeferredRetryDue("row-unsafe", 500_000L))
        assertTrue(state.isDeferredRetryDue("row-other", 6_000L))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.NOT_READY,
            state.observeExhaustedSafeTargetEvidence(
                "row-unsafe", recovered, "window:1", 32_999L, 32_999L))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.NOT_READY,
            state.observeExhaustedSafeTargetEvidence(
                "row-unsafe", unsafe, "window:1", 33_000L, 33_000L))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.WAIT_SECOND_SNAPSHOT,
            state.observeExhaustedSafeTargetEvidence(
                "row-unsafe", recovered, "window:1", 33_001L, 33_001L))
        assertFalse(state.shouldRetryDeferredRow("row-unsafe"))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.WAIT_SECOND_SNAPSHOT,
            state.observeExhaustedSafeTargetEvidence(
                "row-unsafe", recovered, "window:changed", 33_002L, 33_002L))
        assertFalse(state.shouldRetryDeferredRow("row-unsafe"))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.REARMED,
            state.observeExhaustedSafeTargetEvidence(
                "row-unsafe", recovered, "window:changed", 33_003L, 33_003L))
        assertTrue(state.shouldRetryDeferredRow("row-unsafe"))
        assertFalse(state.isDeferredRetryDue("row-unsafe", 34_502L))
        assertTrue(state.isDeferredRetryDue("row-unsafe", 34_503L))
        assertTrue(state.isDeferredRow("row-unsafe"))
        assertFalse(state.isProcessedRow("row-unsafe"))
        assertEquals(0, repository.value?.deferredRows?.get("row-unsafe")?.safeTargetValidationFailures)
    }

    @Test
    fun rearmIsAtMostOneFiniteBudgetPerChangedSceneAndSurvivesRestart() {
        val repository = InMemoryRepository()
        val store = MomoSayHiTraversalCheckpointStore(repository)
        val original = MomoSayHiTraversalState(store, safeTargetFailureReasonPrefix = "SAFE_TARGET")
        repeat(3) { attempt ->
            original.deferRow("row-1", "SAFE_TARGET:UNSAFE", 1_000L + attempt * 1_000L,
                failureEvidenceHash = "a".repeat(64))
        }
        val restarted = MomoSayHiTraversalState(store, safeTargetFailureReasonPrefix = "SAFE_TARGET")
        assertEquals(1, restarted.restore()?.deferredRows)
        assertFalse(restarted.shouldRetryDeferredRow("row-1"))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.WAIT_SECOND_SNAPSHOT,
            restarted.observeExhaustedSafeTargetEvidence(
                "row-1", "b".repeat(64), "window:1", 34_000L, 34_000L))
        // A delayed callback from an older/equal capture never completes proof.
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.WAIT_SECOND_SNAPSHOT,
            restarted.observeExhaustedSafeTargetEvidence(
                "row-1", "b".repeat(64), "window:1", 34_000L, 34_001L))
        assertFalse(restarted.shouldRetryDeferredRow("row-1"))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.REARMED,
            restarted.observeExhaustedSafeTargetEvidence(
                "row-1", "b".repeat(64), "window:1", 34_002L, 34_002L))
        repeat(3) { attempt ->
            restarted.deferRow("row-1", "SAFE_TARGET:UNSAFE", 40_000L + attempt * 1_000L,
                failureEvidenceHash = "b".repeat(64))
        }
        assertFalse(restarted.shouldRetryDeferredRow("row-1"))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.NOT_READY,
            restarted.observeExhaustedSafeTargetEvidence(
                "row-1", "b".repeat(64), "window:1", 72_001L, 72_001L))
        val again = MomoSayHiTraversalState(store, safeTargetFailureReasonPrefix = "SAFE_TARGET")
        again.restore()
        assertFalse(again.shouldRetryDeferredRow("row-1"))
        assertEquals("b".repeat(64),
            repository.value?.deferredRows?.get("row-1")?.failedSafeTargetEvidenceHash)
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.WAIT_SECOND_SNAPSHOT,
            again.observeExhaustedSafeTargetEvidence(
                "row-1", "c".repeat(64), "window:2", 73_000L, 73_000L))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.REARMED,
            again.observeExhaustedSafeTargetEvidence(
                "row-1", "c".repeat(64), "window:2", 73_002L, 73_002L))
    }

    @Test
    fun legacyExhaustedRowRequiresNewFailureBaselineAndFurtherChangedEvidence() {
        val repository = InMemoryRepository().apply {
            value = MomoSayHiTraversalCheckpointRecord(
                processedRowKeys = emptySet(),
                processedContactIdHashes = emptySet(),
                deferredRows = mapOf(
                    "legacy-row" to MomoSayHiDeferredRowCheckpointRecord(
                        attempts = 3,
                        retryAfterEpochMs = 10_000L,
                        safeTargetValidationFailures = 3
                    )
                ),
                deferredReturnToTopActive = false,
                deferredReplayStarted = false
            )
        }
        val state = MomoSayHiTraversalState(
            MomoSayHiTraversalCheckpointStore(repository),
            safeTargetFailureReasonPrefix = "SAFE_TARGET"
        )
        state.restore()
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.BASELINE_RECORDED,
            state.observeExhaustedSafeTargetEvidence(
                "legacy-row", "a".repeat(64), "window:1", 12_000L, 12_000L))
        assertFalse(state.shouldRetryDeferredRow("legacy-row"))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.NOT_READY,
            state.observeExhaustedSafeTargetEvidence(
                "legacy-row", "a".repeat(64), "window:1", 12_001L, 12_001L))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.WAIT_SECOND_SNAPSHOT,
            state.observeExhaustedSafeTargetEvidence(
                "legacy-row", "b".repeat(64), "window:1", 12_002L, 12_002L))
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.REARMED,
            state.observeExhaustedSafeTargetEvidence(
                "legacy-row", "b".repeat(64), "window:1", 12_003L, 12_003L))
        assertFalse(state.isProcessedRow("legacy-row"))
    }

    @Test
    fun invalidOrUnchangedEvidenceNeverRearmsAndProcessedRowsStayClosed() {
        val state = MomoSayHiTraversalState(
            MomoSayHiTraversalCheckpointStore(InMemoryRepository()),
            safeTargetFailureReasonPrefix = "SAFE_TARGET"
        )
        repeat(3) { attempt ->
            state.deferRow("row-a", "SAFE_TARGET:INVALID", attempt * 1_000L + 1_000L,
                failureEvidenceHash = "a".repeat(64))
        }
        for (candidate in listOf("", "unhashed", "a".repeat(64))) {
            assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.NOT_READY,
                state.observeExhaustedSafeTargetEvidence(
                    "row-a", candidate, "window:1", 50_000L, 50_000L))
        }
        assertFalse(state.shouldRetryDeferredRow("row-a"))
        state.markProcessedRow("row-a")
        assertEquals(MomoSayHiTraversalState.SafeEvidenceRecovery.NOT_READY,
            state.observeExhaustedSafeTargetEvidence(
                "row-a", "b".repeat(64), "window:1", 50_001L, 50_001L))
    }

}
