package com.freedom.adapters.accessibility.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationOverlayVisibilityStateTest {
    @Test
    fun firstObservationHoldHidesOverlayAndFinalReleaseRestoresEligibility() {
        val state = ObservationOverlayVisibilityState()

        assertTrue(state.shouldBeVisible(floatingLogEnabled = true, touchPassthrough = false))
        assertTrue(state.acquire())
        assertTrue(state.isObservationHidden())
        assertFalse(state.shouldBeVisible(floatingLogEnabled = true, touchPassthrough = false))

        assertTrue(state.release())
        assertEquals(0, state.activeHolds)
        assertTrue(state.shouldBeVisible(floatingLogEnabled = true, touchPassthrough = false))
    }

    @Test
    fun nestedObservationHoldsDoNotRestoreOverlayEarly() {
        val state = ObservationOverlayVisibilityState()

        assertTrue(state.acquire())
        assertFalse(state.acquire())
        assertEquals(2, state.activeHolds)

        assertFalse(state.release())
        assertTrue(state.isObservationHidden())
        assertFalse(state.shouldBeVisible(floatingLogEnabled = true, touchPassthrough = false))

        assertTrue(state.release())
        assertFalse(state.isObservationHidden())
        assertTrue(state.shouldBeVisible(floatingLogEnabled = true, touchPassthrough = false))
    }

    @Test
    fun observationReleaseDoesNotOverrideActiveGestureTouchPassthrough() {
        val state = ObservationOverlayVisibilityState()
        state.acquire()

        assertTrue(state.release())
        assertFalse(state.shouldBeVisible(floatingLogEnabled = true, touchPassthrough = true))
        assertTrue(state.shouldBeVisible(floatingLogEnabled = true, touchPassthrough = false))
    }

    @Test
    fun disabledFloatingLogNeverBecomesVisibleAfterObservation() {
        val state = ObservationOverlayVisibilityState()
        state.acquire()
        state.release()

        assertFalse(state.shouldBeVisible(floatingLogEnabled = false, touchPassthrough = false))
    }

    @Test
    fun duplicateReleaseCannotUnderflowHoldCount() {
        val state = ObservationOverlayVisibilityState()

        assertFalse(state.release())
        assertEquals(0, state.activeHolds)
        state.acquire()
        assertTrue(state.release())
        assertFalse(state.release())
        assertEquals(0, state.activeHolds)
    }
    @Test
    fun leaseReleaseIsIdempotentAndCannotUnderflowObservationState() {
        val state = ObservationOverlayVisibilityState()
        state.acquire()
        var releaseCalls = 0
        val lease = ObservationVisibilityLease {
            releaseCalls += 1
            state.release()
        }

        lease.close()
        lease.close()

        assertEquals(1, releaseCalls)
        assertEquals(0, state.activeHolds)
        assertTrue(lease.isClosed)
        assertTrue(state.shouldBeVisible(floatingLogEnabled = true, touchPassthrough = false))
    }

}
