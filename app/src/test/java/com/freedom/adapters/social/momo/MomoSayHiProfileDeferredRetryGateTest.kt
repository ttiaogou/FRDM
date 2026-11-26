package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiProfileDeferredRetryGateTest {
    private val original = WindowRef("com.immomo.momo", "NewSayHiSessionListActivity", 5L, 12)

    @Test fun deferredDoesNotImmediatelyResubmitSwipeOnSameWindow() {
        val gate = MomoSayHiProfileDeferredRetryGate(sameWindowProbeDelayMs = 5_000L)
        assertTrue(gate.mayAttempt(original, 1_000L))
        gate.onDeferred("request-1", original, 1_000L)
        assertFalse(gate.mayAttempt(original, 1_001L))
        assertFalse(gate.mayAttempt(original, 5_999L))
        assertTrue(gate.mayAttempt(original, 6_000L))
    }

    @Test fun secondDeferralSuspendsRepeatedRequestsUntilWindowEvidenceChanges() {
        val gate = MomoSayHiProfileDeferredRetryGate()
        gate.onDeferred("request-1", original, 1_000L)
        gate.onDeferred("request-2", original, 6_000L)
        assertFalse(gate.mayAttempt(original, 120_000L))
        assertTrue(gate.mayAttempt(original.copy(generation = 6L), 120_001L))
    }

    @Test fun newlyConfirmedActivityBindingReleasesOldDeferral() {
        val gate = MomoSayHiProfileDeferredRetryGate()
        gate.onDeferred("request-1", original, 1_000L)
        assertTrue(gate.mayAttempt(original.copy(activityClass = "PersonalProfileActivityK"), 1_001L))
    }

    @Test fun duplicateDeferredCallbackDoesNotConsumeSameWindowProbe() {
        val gate = MomoSayHiProfileDeferredRetryGate()
        gate.onDeferred("request-1", original, 1_000L)
        gate.onDeferred("request-1", original, 1_100L)
        assertTrue(gate.mayAttempt(original, 6_000L))
    }

    @Test fun newContactGetsIndependentOpportunity() {
        val gate = MomoSayHiProfileDeferredRetryGate()
        gate.onDeferred("request-1", original, 1_000L)
        gate.onDeferred("request-2", original, 6_000L)
        gate.reset()
        assertTrue(gate.mayAttempt(original, 6_001L))
    }
}
