package com.freedom.features.aireply

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceivedGreetingsRetryScheduleTest {
    @Test
    fun retriesIncreaseAndThenRemainAtTheLowFrequencyCeiling() {
        assertEquals(500L, ReceivedGreetingsRetrySchedule.delayForRetry(1))
        assertEquals(1_000L, ReceivedGreetingsRetrySchedule.delayForRetry(2))
        assertEquals(2_000L, ReceivedGreetingsRetrySchedule.delayForRetry(3))
        assertEquals(5_000L, ReceivedGreetingsRetrySchedule.delayForRetry(4))
        assertEquals(10_000L, ReceivedGreetingsRetrySchedule.delayForRetry(5))
        assertEquals(30_000L, ReceivedGreetingsRetrySchedule.delayForRetry(6))
        assertEquals(30_000L, ReceivedGreetingsRetrySchedule.delayForRetry(7))
        assertEquals(30_000L, ReceivedGreetingsRetrySchedule.delayForRetry(100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroRetryNumber() {
        ReceivedGreetingsRetrySchedule.delayForRetry(0)
    }
}
