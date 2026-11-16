package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiListReturnConfirmationPolicyTest {
    private val policy = MomoSayHiListReturnConfirmationPolicy(
        requiredStableSamples = 2,
        minimumSampleSpacingMs = 500L
    )

    @Test
    fun confirmsOnlyAfterTwoFreshSamplesOfSameWindowGeneration() {
        assertFalse(policy.observe("list:window-7:generation-12", 1_000L))
        assertEquals(1, policy.stableSampleCount)

        assertTrue(policy.observe("list:window-7:generation-12", 1_750L))
        assertEquals(2, policy.stableSampleCount)
    }

    @Test
    fun windowOrGenerationChangeRestartsConfirmation() {
        assertFalse(policy.observe("list:window-7:generation-12", 1_000L))
        assertFalse(policy.observe("list:window-8:generation-12", 1_750L))
        assertEquals(1, policy.stableSampleCount)

        assertFalse(policy.observe("list:window-8:generation-13", 2_500L))
        assertEquals(1, policy.stableSampleCount)
        assertTrue(policy.observe("list:window-8:generation-13", 3_250L))
    }

    @Test
    fun missingEvidenceResetsConfirmation() {
        assertFalse(policy.observe("list:window-7:generation-12", 1_000L))
        assertFalse(policy.observe(null, 1_750L))
        assertEquals(0, policy.stableSampleCount)
        assertFalse(policy.observe("list:window-7:generation-12", 2_500L))
    }

    @Test
    fun observationsTooCloseTogetherCannotConfirmReturn() {
        assertFalse(policy.observe("list:window-7:generation-12", 1_000L))
        assertFalse(policy.observe("list:window-7:generation-12", 1_200L))
        assertEquals(1, policy.stableSampleCount)
        assertTrue(policy.observe("list:window-7:generation-12", 1_800L))
    }
}
