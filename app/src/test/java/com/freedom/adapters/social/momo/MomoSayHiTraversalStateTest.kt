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
    fun restoreRebuildsOnlyEligibleDeferredRowsAndStartsAFreshBoundarySearch() {
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
    }

    @Test
    fun boundarySearchTransitionsOnlyAfterAStableReadSuffix() {
        val state = MomoSayHiTraversalState(
            MomoSayHiTraversalCheckpointStore(InMemoryRepository())
        )

        assertEquals(
            MomoSayHiTraversalState.TraversalPhase.SEEK_UNREAD_BOUNDARY,
            state.traversalPhase
        )
        assertFalse(state.observeUnreadBoundary(true, false, "unread-only"))
        assertFalse(state.observeUnreadBoundary(true, true, "unread-read"))
        assertTrue(state.observeUnreadBoundary(true, true, "unread-read"))
        assertEquals(
            MomoSayHiTraversalState.TraversalPhase.PROCESS_BOTTOM_TO_TOP,
            state.traversalPhase
        )

        state.beginFreshPass()
        assertEquals(
            MomoSayHiTraversalState.TraversalPhase.SEEK_UNREAD_BOUNDARY,
            state.traversalPhase
        )
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
    }
}
