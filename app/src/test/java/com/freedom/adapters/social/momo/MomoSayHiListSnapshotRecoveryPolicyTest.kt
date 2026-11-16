package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiListSnapshotRecoveryPolicyTest {
    @Test
    fun snapshotLeaseAndGestureReservationAreMutuallyExclusive() {
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })

        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.Acquired,
            policy.tryBeginSnapshot(nowElapsedMs = 1_000L)
        )
        assertTrue(!policy.tryBeginGesture(1_001L))
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.SnapshotInFlight,
            policy.tryBeginSnapshot(1_002L)
        )

        policy.onSnapshotReadFinished()
        assertTrue(policy.tryBeginGesture(1_003L))
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.GestureInFlight,
            policy.tryBeginSnapshot(1_004L)
        )
    }

    @Test
    fun gestureTerminalStartsQuietWindowBeforeNextSnapshotOrGesture() {
        val policy = MomoSayHiListSnapshotRecoveryPolicy(
            postGestureSettleRangeMs = 250L..420L,
            sampleDelayMs = { it.first }
        )

        assertTrue(policy.tryBeginGesture(10_000L))
        assertEquals(250L, policy.onGestureFinished(nowElapsedMs = 10_100L))
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.Settling(250L),
            policy.tryBeginSnapshot(10_100L)
        )
        assertFalse(policy.tryBeginGesture(10_349L))
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.Acquired,
            policy.tryBeginSnapshot(10_350L)
        )
    }

    @Test
    fun boundedRetryStillStopsAfterThreeIncompleteSnapshots() {
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })

        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(1, 3, 300L),
            policy.onSnapshotIncomplete("window-1")
        )
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(2, 3, 600L),
            policy.onSnapshotIncomplete("window-1")
        )
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(3, 3, 1_000L),
            policy.onSnapshotIncomplete("window-1")
        )
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Exhausted(3),
            policy.onSnapshotIncomplete("window-1")
        )
    }

    @Test
    fun successfulSnapshotResetsRetryBudgetAndNewWindowIsIndependent() {
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })
        policy.onSnapshotIncomplete("window-1")
        policy.onSnapshotIncomplete("window-1")
        policy.onSnapshotComplete("window-1")

        assertEquals(0, policy.retryAttemptCount())
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(1, 3, 300L),
            policy.onSnapshotIncomplete("window-1")
        )
        repeat(3) { policy.onSnapshotIncomplete("window-2") }
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Exhausted(3),
            policy.onSnapshotIncomplete("window-2")
        )
    }

    @Test
    fun resetReleasesSnapshotAndGestureReservations() {
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.Acquired,
            policy.tryBeginSnapshot(0L)
        )
        policy.reset()
        assertTrue(policy.tryBeginGesture(0L))
        policy.reset()
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.Acquired,
            policy.tryBeginSnapshot(0L)
        )
    }

    @Test
    fun deferredGestureReleasesReservationWithoutConsumingSettleOrFailureState() {
        val policy = MomoSayHiListSnapshotRecoveryPolicy(
            postGestureSettleRangeMs = 250L..420L,
            sampleDelayMs = { it.first }
        )
        assertTrue(policy.tryBeginGesture(10_000L))

        policy.onGestureDeferred()

        assertEquals(0L, policy.remainingSettleMs(10_000L))
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.Acquired,
            policy.tryBeginSnapshot(10_000L)
        )
    }
    @Test
    fun exhaustedRecoveryIsRememberedForTheSameWindowAndClearedByACompleteSnapshot() {
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })
        val expectedRetryDelaysMs = listOf(300L, 600L, 1_000L)
        repeat(expectedRetryDelaysMs.size) { attempt ->
            assertEquals(
                MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(
                    attempt + 1,
                    expectedRetryDelaysMs.size,
                    expectedRetryDelaysMs[attempt]
                ),
                policy.onSnapshotIncomplete("window-1")
            )
        }
        assertFalse(policy.isPausedForWindow("window-1"))
        assertFalse(policy.isPausedForWindow("window-2"))
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Exhausted(3),
            policy.onSnapshotIncomplete("window-1")
        )
        assertTrue(policy.isPausedForWindow("window-1"))
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(1, 3, 300L),
            policy.onSnapshotIncomplete("window-2")
        )

        policy.onSnapshotComplete("window-1")
        assertFalse(policy.isPausedForWindow("window-1"))
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(1, 3, 300L),
            policy.onSnapshotIncomplete("window-1")
        )
    }

}
