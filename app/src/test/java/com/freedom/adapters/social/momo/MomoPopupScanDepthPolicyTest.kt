package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoPopupScanDepthPolicyTest {
    @Test
    fun defaultDepthExceedsThePreviouslyFailingTwentyFourLevelBoundary() {
        assertEquals(48, MomoPopupScanDepthPolicy.DEFAULT_MAX_SCAN_DEPTH)
        assertFalse(MomoPopupScanDepthPolicy.mustStopAt(24, 1))
        assertFalse(MomoPopupScanDepthPolicy.mustStopAt(47, 1))
        assertTrue(MomoPopupScanDepthPolicy.mustStopAt(48, 1))
        assertFalse(MomoPopupScanDepthPolicy.mustStopAt(48, 0))
    }
}
