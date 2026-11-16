package com.freedom.core.accessibility.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInteractionMonitorTest {
    @Test
    fun recordsBalancedStartEndAndDurationWithoutClaimingPhysicalIdentity() {
        val logs = mutableListOf<String>()
        val monitor = TouchInteractionMonitor(logs::add)
        monitor.record(AccessibilityEventType.TOUCH_INTERACTION_START, 100L, 110L, null, -1)
        monitor.record(AccessibilityEventType.TOUCH_INTERACTION_END, 185L, 190L, null, -1)
        val state = monitor.snapshot()
        assertEquals(1L, state.startCount)
        assertEquals(1L, state.endCount)
        assertEquals(0L, state.orphanEndCount)
        assertFalse(state.interactionActive)
        assertEquals(85L, state.lastDurationMs)
        assertTrue(logs.any { it.contains("origin=UNKNOWN") && it.contains("actorProvenance=UNAVAILABLE") })
        monitor.appendSummary("test")
        assertTrue(logs.last().contains("physicalUserConfirmed=0"))
    }

    @Test
    fun recordsOrphanEndAndDuplicateStartInsteadOfSilentlyRepairingTheTrace() {
        val monitor = TouchInteractionMonitor()
        monitor.record(AccessibilityEventType.TOUCH_INTERACTION_END, 10L, 11L, null, -1)
        monitor.record(AccessibilityEventType.TOUCH_INTERACTION_START, 20L, 21L, null, -1)
        monitor.record(AccessibilityEventType.TOUCH_INTERACTION_START, 30L, 31L, null, -1)
        val state = monitor.snapshot()
        assertEquals(1L, state.orphanEndCount)
        assertEquals(1L, state.duplicateStartCount)
        assertTrue(state.interactionActive)
    }

    @Test
    fun resetStartsANewSubscriptionEpoch() {
        val monitor = TouchInteractionMonitor()
        monitor.record(AccessibilityEventType.TOUCH_INTERACTION_START, 10L, 11L, null, -1)
        monitor.reset("test")
        val state = monitor.snapshot()
        assertEquals(0L, state.startCount)
        assertEquals(0L, state.endCount)
        assertFalse(state.interactionActive)
    }
}
