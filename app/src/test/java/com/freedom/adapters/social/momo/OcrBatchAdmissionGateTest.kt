package com.freedom.adapters.social.momo

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OcrBatchAdmissionGateTest {
    @Test
    fun rejectsNewBatchUntilTheOriginalWorkerActuallyReleasesItsLease() {
        val gate = OcrBatchAdmissionGate()
        val active = gate.tryAcquire()

        assertNotNull(active)
        assertNull(gate.tryAcquire())

        active!!.close()
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun timeoutDoesNotImplicitlyReleaseTheWorkerLease() {
        val gate = OcrBatchAdmissionGate()
        val lease = gate.tryAcquire()!!

        // The caller may have timed out, but only the worker's finally block releases the lease.
        assertNull(gate.tryAcquire())
        lease.close()
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun concurrentAttemptsAdmitOnlyOneBatch() {
        val gate = OcrBatchAdmissionGate()
        val start = CountDownLatch(1)
        val completed = CountDownLatch(8)
        val admitted = AtomicInteger(0)
        val leases = java.util.Collections.synchronizedList(mutableListOf<OcrBatchAdmissionGate.Lease>())

        repeat(8) {
            Thread {
                start.await()
                gate.tryAcquire()?.let {
                    admitted.incrementAndGet()
                    leases += it
                }
                completed.countDown()
            }.start()
        }
        start.countDown()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        org.junit.Assert.assertEquals(1, admitted.get())
        leases.forEach { it.close() }
    }


    @org.junit.Test
    fun lostReadCannotResumeOldScreenshotEvenWhenPageReturns() {
        var current = true
        var calls = 0
        val admission = retainOcrReadValidity { calls++; current }
        org.junit.Assert.assertTrue(admission())
        current = false
        org.junit.Assert.assertFalse(admission())
        current = true
        org.junit.Assert.assertFalse(admission())
        org.junit.Assert.assertEquals(2, calls)
    }

    @org.junit.Test
    fun failedWindowCheckRetiresReadRatherThanAllowingFurtherRequests() {
        val admission = retainOcrReadValidity { error("window unavailable") }
        org.junit.Assert.assertFalse(admission())
        org.junit.Assert.assertFalse(admission())
    }
}
