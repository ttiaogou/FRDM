package com.freedom.adapters.accessibility.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationVisibilityLeaseRegistryTest {
    @Test
    fun releaseAllClosesEveryOutstandingLeaseExactlyOnce() {
        val registry = ObservationVisibilityLeaseRegistry()
        var firstReleaseCount = 0
        var secondReleaseCount = 0
        val first = ObservationVisibilityLease { firstReleaseCount++ }
        val second = ObservationVisibilityLease { secondReleaseCount++ }

        registry.register(first)
        registry.register(second)

        assertEquals(2, registry.activeLeaseCount())
        assertEquals(2, registry.releaseAll())
        assertTrue(first.isClosed)
        assertTrue(second.isClosed)
        assertEquals(1, firstReleaseCount)
        assertEquals(1, secondReleaseCount)
        assertEquals(0, registry.activeLeaseCount())
    }

    @Test
    fun lateCallbackCloseAfterReleaseAllIsSafe() {
        val registry = ObservationVisibilityLeaseRegistry()
        var releases = 0
        val lease = ObservationVisibilityLease { releases++ }
        registry.register(lease)

        registry.releaseAll()
        lease.close()

        assertTrue(lease.isClosed)
        assertEquals(1, releases)
        assertEquals(0, registry.activeLeaseCount())
    }

    @Test
    fun releaseAllContinuesWhenOneReleaseActionThrows() {
        val registry = ObservationVisibilityLeaseRegistry()
        var healthyReleaseCount = 0
        val broken = ObservationVisibilityLease { error("simulated release failure") }
        val healthy = ObservationVisibilityLease { healthyReleaseCount++ }
        registry.register(broken)
        registry.register(healthy)

        assertEquals(2, registry.releaseAll())
        assertTrue(broken.isClosed)
        assertTrue(healthy.isClosed)
        assertEquals(1, healthyReleaseCount)
        assertEquals(0, registry.activeLeaseCount())
    }

    @Test
    fun repeatedReleaseAllIsIdempotent() {
        val registry = ObservationVisibilityLeaseRegistry()
        var releases = 0
        registry.register(ObservationVisibilityLease { releases++ })

        assertEquals(1, registry.releaseAll())
        assertEquals(0, registry.releaseAll())
        assertEquals(1, releases)
        assertEquals(0, registry.activeLeaseCount())
    }

    @Test
    fun releasingEmptyRegistryIsNoOp() {
        val registry = ObservationVisibilityLeaseRegistry()

        assertEquals(0, registry.releaseAll())
        assertEquals(0, registry.activeLeaseCount())
    }
}
