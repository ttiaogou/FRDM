package com.freedom.adapters.accessibility.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationVisibilityLeaseVisibilityTest {
    @Test
    fun leaseIsPubliclyUsableAndCloseIsIdempotent() {
        var releases = 0
        val lease = ObservationVisibilityLease { releases++ }

        assertFalse(lease.isClosed)
        lease.close()
        lease.close()

        assertTrue(lease.isClosed)
        assertEquals(1, releases)
    }
}
