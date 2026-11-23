package com.freedom.core.accessibility.event

import com.freedom.core.accessibility.events.AccessibilityContentChange
import com.freedom.core.accessibility.events.AccessibilityEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityEventPolicyTest {
    @Test
    fun ordinaryTextChangeDoesNotRequestFullStructureScan() {
        assertEquals(
            AccessibilityEventKind.PAGE_METADATA,
            AccessibilityEventPolicy.classify(
                AccessibilityEventType.WINDOW_CONTENT_CHANGED,
                setOf(AccessibilityContentChange.OTHER)
            )
        )
    }

    @Test
    fun subtreeAndWindowChangesRequestStructuralInspection() {
        assertEquals(
            AccessibilityEventKind.PAGE_STRUCTURE,
            AccessibilityEventPolicy.classify(
                AccessibilityEventType.WINDOW_CONTENT_CHANGED,
                setOf(AccessibilityContentChange.SUBTREE)
            )
        )
        assertEquals(
            AccessibilityEventKind.PAGE_STRUCTURE,
            AccessibilityEventPolicy.classify(AccessibilityEventType.WINDOW_STATE_CHANGED, emptySet())
        )
    }

    @Test
    fun clickIsAFullPageInspectionSignalButOrdinaryEventIsNot() {
        assertTrue(AccessibilityEventPolicy.requiresFullChatPageInspection(
            AccessibilityEventKind.ORDINARY, AccessibilityEventType.VIEW_CLICKED,
            isKnownChatDetail = false, hasChatNavigationEvidence = true
        ))
        assertFalse(AccessibilityEventPolicy.requiresFullChatPageInspection(
            AccessibilityEventKind.ORDINARY, AccessibilityEventType.VIEW_SCROLLED,
            isKnownChatDetail = false, hasChatNavigationEvidence = false
        ))
    }

    @Test
    fun ordinaryUnrelatedClickDoesNotTriggerAFullChatInspection() {
        assertFalse(AccessibilityEventPolicy.requiresFullChatPageInspection(
            AccessibilityEventKind.ORDINARY, AccessibilityEventType.VIEW_CLICKED,
            isKnownChatDetail = false, hasChatNavigationEvidence = false
        ))
    }

    @Test
    fun knownChatDetailUsesRegionRefreshInsteadOfFullPageInspection() {
        assertFalse(AccessibilityEventPolicy.requiresFullChatPageInspection(
            AccessibilityEventKind.PAGE_STRUCTURE, AccessibilityEventType.WINDOW_CONTENT_CHANGED,
            isKnownChatDetail = true, hasChatNavigationEvidence = false
        ))
    }

    @Test
    fun routineEventsRefreshKnownChatRegionsOnly() {
        assertTrue(AccessibilityEventPolicy.requiresKnownChatRegionRefresh(
            AccessibilityEventType.VIEW_TEXT_CHANGED
        ))
        assertTrue(AccessibilityEventPolicy.requiresKnownChatRegionRefresh(
            AccessibilityEventType.VIEW_CLICKED
        ))
    }
}
