package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoPopupSafetyRetryPolicyTest {
    @Test
    fun incompleteSafetyInspectionRemainsRetryableAfterDiagnosticCounterCap() {
        val policy = MomoPopupSafetyRetryPolicy(maxIncompleteAttemptsPerWindow = 3)

        val first = policy.onIncomplete("MaintabActivity:698", 100L)
        val second = policy.onIncomplete("MaintabActivity:698", 500L)
        val third = policy.onIncomplete("MaintabActivity:698", 900L)

        assertEquals(1, first.number)
        assertTrue(first.retryable)
        assertEquals(2, second.number)
        assertTrue(second.retryable)
        assertEquals(3, third.number)
        assertTrue(third.retryable)
        val later = policy.onIncomplete("MaintabActivity:698", 1_300L)
        assertEquals(3, later.number)
        assertTrue(later.retryable)
    }

    @Test
    fun completeInspectionResetsRetryBudgetForSameWindow() {
        val policy = MomoPopupSafetyRetryPolicy(maxIncompleteAttemptsPerWindow = 3)
        policy.onIncomplete("MaintabActivity:698", 100L)
        policy.onIncomplete("MaintabActivity:698", 500L)

        policy.onComplete("MaintabActivity:698", 600L)
        val next = policy.onIncomplete("MaintabActivity:698", 700L)

        assertEquals(1, next.number)
        assertTrue(next.retryable)
    }

    @Test
    fun retrySequenceExpiresAfterIdleGapSoAnOldRunDoesNotConsumeNewRunBudget() {
        val policy = MomoPopupSafetyRetryPolicy(
            maxIncompleteAttemptsPerWindow = 3,
            retrySequenceWindowMs = 5_000L
        )
        policy.onIncomplete("MaintabActivity:698", 100L)
        policy.onIncomplete("MaintabActivity:698", 500L)

        val afterGap = policy.onIncomplete("MaintabActivity:698", 6_000L)

        assertEquals(1, afterGap.number)
        assertTrue(afterGap.retryable)
    }

    @Test
    fun newActivityOrWindowReceivesIndependentRetryBudget() {
        val policy = MomoPopupSafetyRetryPolicy(maxIncompleteAttemptsPerWindow = 3)
        repeat(3) { index -> policy.onIncomplete("MaintabActivity:698", 100L + index * 100L) }

        val activityChanged = policy.onIncomplete("NewSayHiCardActivity:707", 500L)
        assertEquals(1, activityChanged.number)
        assertTrue(activityChanged.retryable)

        val windowChanged = policy.onIncomplete("NewSayHiCardActivity:708", 600L)
        assertEquals(1, windowChanged.number)
        assertTrue(windowChanged.retryable)
    }
}
