package com.freedom.core.accessibility.event

import com.freedom.core.accessibility.events.AccessibilityContentChange
import com.freedom.core.accessibility.events.AccessibilityEventType

/** Separates lightweight metadata updates from page-structure and chat-region inspection. */
enum class AccessibilityEventKind(
    val updatesPageMetadata: Boolean,
    val requestsNodeScan: Boolean
) {
    ORDINARY(updatesPageMetadata = false, requestsNodeScan = false),
    PAGE_METADATA(updatesPageMetadata = true, requestsNodeScan = false),
    PAGE_STRUCTURE(updatesPageMetadata = true, requestsNodeScan = true)
}

/** Pure event policy; Android event constants are normalized by the platform adapter. */
object AccessibilityEventPolicy {

    fun classify(
        eventType: AccessibilityEventType,
        contentChanges: Set<AccessibilityContentChange>
    ): AccessibilityEventKind = when (eventType) {
        AccessibilityEventType.WINDOW_STATE_CHANGED,
        AccessibilityEventType.WINDOWS_CHANGED -> AccessibilityEventKind.PAGE_STRUCTURE

        AccessibilityEventType.WINDOW_CONTENT_CHANGED -> {
            if (contentChanges.any {
                    it == AccessibilityContentChange.SUBTREE ||
                        it == AccessibilityContentChange.PANE_APPEARED ||
                        it == AccessibilityContentChange.PANE_DISAPPEARED
                }) {
                AccessibilityEventKind.PAGE_STRUCTURE
            } else {
                AccessibilityEventKind.PAGE_METADATA
            }
        }

        else -> AccessibilityEventKind.ORDINARY
    }

    /** Navigation signals justify a bounded page inspection even without an Activity change. */
    fun requiresFullChatPageInspection(
        kind: AccessibilityEventKind,
        eventType: AccessibilityEventType,
        isKnownChatDetail: Boolean,
        hasChatNavigationEvidence: Boolean
    ): Boolean {
        if (eventType == AccessibilityEventType.WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEventType.WINDOWS_CHANGED
        ) return true
        if (isKnownChatDetail) return false
        return kind == AccessibilityEventKind.PAGE_STRUCTURE ||
            (hasChatNavigationEvidence &&
                (eventType == AccessibilityEventType.VIEW_CLICKED ||
                    eventType == AccessibilityEventType.VIEW_SELECTED))
    }

    /** Routine changes refresh only known chat subtrees after a detail candidate was established. */
    fun requiresKnownChatRegionRefresh(eventType: AccessibilityEventType): Boolean = when (eventType) {
        AccessibilityEventType.WINDOW_CONTENT_CHANGED,
        AccessibilityEventType.VIEW_SCROLLED,
        AccessibilityEventType.VIEW_TEXT_CHANGED,
        AccessibilityEventType.VIEW_FOCUSED,
        AccessibilityEventType.VIEW_SELECTED,
        AccessibilityEventType.VIEW_CLICKED -> true
        else -> false
    }
}
