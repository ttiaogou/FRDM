package com.freedom.core.accessibility.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAccessibilityEventRoutePolicyTest {
    private fun event(
        type: AccessibilityEventType,
        packageName: String = "com.immomo.momo"
    ) = AccessibilityEventEnvelope(
        type = type,
        packageName = packageName,
        className = "",
        contentChanges = emptySet(),
        eventTimeMillis = 1L,
        eventText = emptyList(),
        contentDescription = null,
        source = null
    )

    @Test
    fun globalTouchIsRoutedBeforePackageFiltering() {
        val router = DefaultAccessibilityEventRoutePolicy()
        assertEquals(
            AccessibilityEventRoute.GLOBAL_TOUCH,
            router.route(event(AccessibilityEventType.TOUCH_INTERACTION_START, ""), "com.immomo.momo", "com.freedom")
        )
    }

    @Test
    fun onlyTheSelectedNonFreedomPackageIsRoutedAsTargetApplication() {
        val router = DefaultAccessibilityEventRoutePolicy()
        assertEquals(
            AccessibilityEventRoute.TARGET_APPLICATION,
            router.route(event(AccessibilityEventType.VIEW_CLICKED), "com.immomo.momo", "com.freedom")
        )
        assertEquals(
            AccessibilityEventRoute.IGNORE,
            router.route(event(AccessibilityEventType.VIEW_CLICKED, "com.other.app"), "com.immomo.momo", "com.freedom")
        )
        assertEquals(
            AccessibilityEventRoute.IGNORE,
            router.route(event(AccessibilityEventType.VIEW_CLICKED, "com.freedom"), "com.freedom", "com.freedom")
        )
    }

    @Test
    fun duplicateWindowRetainsTheExisting250MillisecondBoundary() {
        val router = DefaultAccessibilityEventRoutePolicy()
        assertFalse(router.shouldSuppressDuplicate("same", 1_000L))
        assertTrue(router.shouldSuppressDuplicate("same", 1_249L))
        assertFalse(router.shouldSuppressDuplicate("same", 1_250L))
    }

    @Test
    fun duplicateSuppressionIsForAnIdenticalConsecutiveSignatureOnly() {
        val router = DefaultAccessibilityEventRoutePolicy()
        assertFalse(router.shouldSuppressDuplicate("a", 10_000L))
        assertFalse(router.shouldSuppressDuplicate("b", 10_050L))
        assertFalse(router.shouldSuppressDuplicate("a", 10_100L))
        router.resetDuplicateWindow()
        assertFalse(router.shouldSuppressDuplicate("a", 10_110L))
    }
}
