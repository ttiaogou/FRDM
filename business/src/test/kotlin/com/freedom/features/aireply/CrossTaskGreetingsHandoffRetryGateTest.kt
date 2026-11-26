package com.freedom.features.aireply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossTaskGreetingsHandoffRetryGateTest {
    @Test fun identicalEvidenceWaitsForReadOnlyRetryAndUsesSharedBackoff() {
        val gate = CrossTaskGreetingsHandoffRetryGate()
        val first = gate.onAdmissionBlocked("run7|checkpoint|window12")
        assertEquals(1, first.attempt)
        assertEquals(500L, first.delayMs)
        assertTrue(gate.isWaitingOn("run7|checkpoint|window12"))
        assertFalse(gate.isWaitingOn("run7|checkpoint|window13"))
        assertTrue(gate.onReadOnlyRetryDue("run7|checkpoint|window12"))
        assertFalse(gate.isWaitingOn("run7|checkpoint|window12"))
        assertEquals(1_000L, gate.onAdmissionBlocked("run7|checkpoint|window12").delayMs)
    }

    @Test fun retryCapRemainsLowFrequencyAndNeverStopsByAttemptCount() {
        val gate = CrossTaskGreetingsHandoffRetryGate()
        repeat(40) {
            val retry = gate.onAdmissionBlocked("run7|checkpoint|same-window")
            assertEquals(if (it >= 5) 30_000L else listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L)[it], retry.delayMs)
            assertTrue(gate.onReadOnlyRetryDue("run7|checkpoint|same-window"))
        }
        assertEquals(41, gate.onAdmissionBlocked("run7|checkpoint|same-window").attempt)
    }

    @Test fun newFingerprintOrRunRequiresFreshProofAndResetsBackoff() {
        val gate = CrossTaskGreetingsHandoffRetryGate()
        gate.onAdmissionBlocked("run7|checkpoint|window12")
        assertTrue(gate.evidenceChanged("run7|checkpoint|window13"))
        gate.reset()
        assertFalse(gate.isWaitingOn("run7|checkpoint|window13"))
        assertEquals(500L, gate.onAdmissionBlocked("run7|checkpoint|window13").delayMs)
        assertTrue(gate.evidenceChanged("run8|checkpoint|window13"))
        gate.reset()
        assertEquals(500L, gate.onAdmissionBlocked("run8|checkpoint|window13").delayMs)
    }

    @Test fun staleCallbackNeverUnblocksChangedEvidence() {
        val gate = CrossTaskGreetingsHandoffRetryGate()
        gate.onAdmissionBlocked("old")
        gate.reset()
        gate.onAdmissionBlocked("new")
        assertFalse(gate.onReadOnlyRetryDue("old"))
        assertTrue(gate.isWaitingOn("new"))
    }
}
