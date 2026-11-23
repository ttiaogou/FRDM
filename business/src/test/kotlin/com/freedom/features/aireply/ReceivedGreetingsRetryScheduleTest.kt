package com.freedom.features.aireply

import com.freedom.features.recovery.RecoveryScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

/** Existing greetings-cadence cases now exercise the shared runtime recovery scheduler. */
class ReceivedGreetingsRetryScheduleTest {
    @Test
    fun sharedRecoveryRetriesIncreaseAndThenRemainAtTheLowFrequencyCeiling() {
        assertEquals(500L, RecoveryScheduler.readOnlyRetryDelayMs(1))
        assertEquals(1_000L, RecoveryScheduler.readOnlyRetryDelayMs(2))
        assertEquals(2_000L, RecoveryScheduler.readOnlyRetryDelayMs(3))
        assertEquals(5_000L, RecoveryScheduler.readOnlyRetryDelayMs(4))
        assertEquals(10_000L, RecoveryScheduler.readOnlyRetryDelayMs(5))
        assertEquals(30_000L, RecoveryScheduler.readOnlyRetryDelayMs(6))
        assertEquals(30_000L, RecoveryScheduler.readOnlyRetryDelayMs(7))
        assertEquals(30_000L, RecoveryScheduler.readOnlyRetryDelayMs(100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroRetryNumber() {
        RecoveryScheduler.readOnlyRetryDelayMs(0)
    }
}
