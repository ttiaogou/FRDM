package com.freedom.core.accessibility.api

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.SelfNavigationTransition

/**
 * Platform-neutral notifications crossing the single business-facing accessibility entry.
 * A physical Android callback can yield multiple ordered semantic notifications:
 * window/navigation attribution precedes normal target delivery, while visible-log
 * de-duplication must not drop AccountBrowse's preflight target events.
 */
sealed interface UnifiedAccessibilityEvent {
    data class TargetApplication(val event: AccessibilityEventEnvelope) : UnifiedAccessibilityEvent
    data class GlobalTouch(val event: AccessibilityEventEnvelope) : UnifiedAccessibilityEvent
    data class ActivityTransition(val activityClass: String) : UnifiedAccessibilityEvent
    data class NavigationObservation(
        val activityClass: String,
        val attachedSelfNavigation: SelfNavigationTransition?
    ) : UnifiedAccessibilityEvent
}

/**
 * Single platform-neutral gateway for business-facing accessibility notifications.
 * Business consumers never receive raw Android AccessibilityEvent or live nodes.
 */
fun interface UnifiedAccessibilityEntry {
    fun accept(event: UnifiedAccessibilityEvent)
}
