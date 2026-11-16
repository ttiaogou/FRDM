package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TieredManualHandoffQuietPeriodTest {
    @Test
    fun advancesThroughFiveFifteenAndSixtySecondsThenExhausts() {
        val policy = TieredManualHandoffQuietPeriod()

        assertEquals(1, policy.stageNumber)
        assertEquals(5_000L, policy.currentDelayMs)
        assertEquals(15_000L, policy.advanceAfterUnmetCondition())
        assertEquals(2, policy.stageNumber)
        assertEquals(60_000L, policy.advanceAfterUnmetCondition())
        assertEquals(3, policy.stageNumber)
        assertNull(policy.advanceAfterUnmetCondition())
    }

    @Test
    fun aNewManualInteractionRestartsTheFirstStage() {
        val policy = TieredManualHandoffQuietPeriod()

        policy.advanceAfterUnmetCondition()
        policy.advanceAfterUnmetCondition()

        assertEquals(5_000L, policy.restart())
        assertEquals(1, policy.stageNumber)
    }

    @Test
    fun resetRestoresFirstStageWithoutProducingAnotherRetry() {
        val policy = TieredManualHandoffQuietPeriod()
        policy.advanceAfterUnmetCondition()
        policy.advanceAfterUnmetCondition()
        policy.reset()

        assertEquals(5_000L, policy.currentDelayMs)
        assertEquals(1, policy.stageNumber)
    }
}
