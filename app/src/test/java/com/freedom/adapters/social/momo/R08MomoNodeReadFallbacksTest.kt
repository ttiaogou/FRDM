package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R08MomoNodeReadFallbacksTest {
    @Test fun booleanPreservesSuccessfulValueAndFallsBackOnFailure() {
        var reads = 0
        assertTrue(momoReadBooleanOrFalse { reads++; true })
        assertFalse(momoReadBooleanOrFalse { reads++; false })
        assertFalse(momoReadBooleanOrFalse { reads++; error("read failed") })
        assertEquals(3, reads)
    }

    @Test fun integerPreservesZeroNegativeAndPositiveValuesAndFallsBackOnFailure() {
        assertEquals(0, momoReadIntOrZero { 0 })
        assertEquals(-1, momoReadIntOrZero { -1 })
        assertEquals(7, momoReadIntOrZero { 7 })
        assertEquals(0, momoReadIntOrZero { error("read failed") })
    }

    @Test fun nullNodeAndReadFailureStayNullWithoutRetry() {
        var reads = 0
        assertNull(momoReadNodeOrNull { reads++; null })
        assertNull(momoReadNodeOrNull { reads++; error("read failed") })
        assertEquals(2, reads)
    }
}
