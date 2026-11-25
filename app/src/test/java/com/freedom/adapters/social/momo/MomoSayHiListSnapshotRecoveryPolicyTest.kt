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
    fun incompleteSnapshotsContinueAtTheLowFrequencyCeiling() {
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })

        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(1, Int.MAX_VALUE, 500L),
            policy.onSnapshotIncomplete("window-1")
        )
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(2, Int.MAX_VALUE, 1_000L),
            policy.onSnapshotIncomplete("window-1")
        )
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(3, Int.MAX_VALUE, 2_000L),
            policy.onSnapshotIncomplete("window-1")
        )
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(4, Int.MAX_VALUE, 5_000L),
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
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(1, Int.MAX_VALUE, 500L),
            policy.onSnapshotIncomplete("window-1")
        )
        repeat(10) { attempt ->
            val expectedDelay = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L)[attempt.coerceAtMost(5)]
            assertEquals(
                MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(attempt + 1, Int.MAX_VALUE, expectedDelay),
                policy.onSnapshotIncomplete("window-2")
            )
        }
        assertEquals(10, policy.retryAttemptCount())
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
    fun recoveryNeverExhaustsByRetryCountAndCompleteSnapshotResetsIt() {
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })
        val expectedRetryDelaysMs = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
        repeat(expectedRetryDelaysMs.size) { attempt ->
            assertEquals(
                MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(
                    attempt + 1,
                    Int.MAX_VALUE,
                    expectedRetryDelaysMs[attempt]
                ),
                policy.onSnapshotIncomplete("window-1")
            )
        }
        assertEquals(6, policy.retryAttemptCount())
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(7, Int.MAX_VALUE, 30_000L),
            policy.onSnapshotIncomplete("window-1")
        )
        assertEquals(7, policy.retryAttemptCount())
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(1, Int.MAX_VALUE, 500L),
            policy.onSnapshotIncomplete("window-2")
        )

        policy.onSnapshotComplete("window-1")
        assertEquals(0, policy.retryAttemptCount())
        assertEquals(
            MomoSayHiListSnapshotRecoveryPolicy.IncompleteSnapshotDecision.Retry(1, Int.MAX_VALUE, 500L),
            policy.onSnapshotIncomplete("window-1")
        )
    }

}
