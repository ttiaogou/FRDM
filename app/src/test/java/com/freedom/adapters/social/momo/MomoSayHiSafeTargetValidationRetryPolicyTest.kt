package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiSafeTargetValidationRetryPolicyTest {
    @Test
    fun permitsInitialAttemptAndTwoRetriesThenStops() {
        var failures = 0

        assertTrue(MomoSayHiSafeTargetValidationRetryPolicy.shouldRetry(failures))
        failures = MomoSayHiSafeTargetValidationRetryPolicy.recordFailure(failures)
        assertTrue(MomoSayHiSafeTargetValidationRetryPolicy.shouldRetry(failures))
        failures = MomoSayHiSafeTargetValidationRetryPolicy.recordFailure(failures)
        assertTrue(MomoSayHiSafeTargetValidationRetryPolicy.shouldRetry(failures))
        failures = MomoSayHiSafeTargetValidationRetryPolicy.recordFailure(failures)

        assertEquals(3, failures)
        assertFalse(MomoSayHiSafeTargetValidationRetryPolicy.shouldRetry(failures))
    }

    @Test
    fun failureCountIsCappedAndCannotReopenRetryBudget() {
        val failures = MomoSayHiSafeTargetValidationRetryPolicy.recordFailure(100)

        assertEquals(MomoSayHiSafeTargetValidationRetryPolicy.MAX_FAILURES, failures)
        assertFalse(MomoSayHiSafeTargetValidationRetryPolicy.shouldRetry(failures))
    }
}
