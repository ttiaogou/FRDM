package com.freedom.adapters.social.momo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoCompletionBackRetryGateTest {
    @Test
    fun acceptedBackRequiresTimeoutAndTwoFreshSpacedSourceSamples() {
        val gate = MomoCompletionBackRetryGate(8_000L)
        gate.onSubmitted(100L)
        assertFalse(gate.mayRetry("list:1", 8_099L))
        assertFalse(gate.mayRetry("list:1", 8_100L))
        assertFalse(gate.mayRetry("list:1", 8_200L))
        assertTrue(gate.mayRetry("list:1", 8_700L))
        gate.onSubmitted(8_700L)
        assertFalse(gate.mayRetry("list:1", 9_200L))
    }

    @Test
    fun changedWindowMissingEvidenceAndUserInteractionInvalidateSamples() {
        val gate = MomoCompletionBackRetryGate(8_000L)
        gate.onSubmitted(0L)
        assertFalse(gate.mayRetry("list:1", 8_000L))
        assertFalse(gate.mayRetry("list:2", 8_500L))
        gate.invalidateSamples()
        assertFalse(gate.mayRetry("list:2", 9_000L))
        assertFalse(gate.mayRetry(null, 9_500L))
        assertFalse(gate.mayRetry("list:2", 10_000L))
        assertTrue(gate.mayRetry("list:2", 10_500L))
        gate.reset()
        assertFalse(gate.mayRetry("list:2", 20_000L))
    }
}
