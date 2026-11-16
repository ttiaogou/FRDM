package com.freedom.stability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveStartGateTest {
    @Test
    fun repeatedRequestsAreCoalescedUntilServiceStops() {
        val gate = KeepAliveStartGate()

        assertTrue(gate.tryAcquireStart())
        assertFalse(gate.tryAcquireStart())
        assertTrue(gate.isStartRequestedOrActive())

        gate.markStopped()

        assertFalse(gate.isStartRequestedOrActive())
        assertTrue(gate.tryAcquireStart())
    }

    @Test
    fun successfulServiceStartKeepsGateClosedForDuplicateRequests() {
        val gate = KeepAliveStartGate()

        assertTrue(gate.tryAcquireStart())
        gate.markStarted()

        assertFalse(gate.tryAcquireStart())
    }
}
