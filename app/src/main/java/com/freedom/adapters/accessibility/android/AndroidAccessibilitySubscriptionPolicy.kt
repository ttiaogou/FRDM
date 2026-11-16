package com.freedom.adapters.accessibility.android

import android.view.accessibility.AccessibilityEvent
import com.freedom.core.runtime.RunMode

/**
 * Explicit subscription contract for FreedomAccessibilityService.
 *
 * Android filters events by event type and package name before invoking onAccessibilityEvent.
 * The system-generated TOUCH_INTERACTION_START/END events may have a null package name, so
 * Android drops them when packageNames contains only the selected target. AccountBrowse keeps
 * the documented compatibility exception (packageNames=null) and applies a strict target-package
 * guard in the Service callback before reading event.source or normalizing the event.
 * AI Reply also subscribes to the two global touch-boundary events. Since Android may deliver
 * those with a null package, AI Reply uses packageNames=null and the Service callback applies a
 * strict package/type guard before reading event.source or normalizing ordinary target events.
 * The idle service continues to use the selected-package allowlist.
 */
internal data class AndroidAccessibilitySubscription(
    val packageNames: List<String>?,
    val eventTypes: Int
)

internal object AndroidAccessibilitySubscriptionPolicy {
    val targetApplicationEventTypes: Int =
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_SELECTED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED

    val globalTouchEventTypes: Int =
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END

    fun forRunMode(
        targetPackage: String,
        fallbackPackage: String,
        runMode: RunMode?
    ): AndroidAccessibilitySubscription {
        val selectedPackage = targetPackage.takeIf(String::isNotBlank) ?: fallbackPackage
        return if (runMode == RunMode.ACCOUNT_BROWSE) {
            AndroidAccessibilitySubscription(
                packageNames = if (targetPackage.isNotBlank()) null else listOf(selectedPackage),
                eventTypes = targetApplicationEventTypes or globalTouchEventTypes
            )
        } else if (runMode == RunMode.AI_REPLY) {
            AndroidAccessibilitySubscription(
                packageNames = if (targetPackage.isNotBlank()) null else listOf(selectedPackage),
                eventTypes = targetApplicationEventTypes or globalTouchEventTypes
            )
        } else {
            AndroidAccessibilitySubscription(
                packageNames = listOf(selectedPackage),
                eventTypes = targetApplicationEventTypes
            )
        }
    }

    /**
     * Callback-level guard used before event.source is accessed. This is mandatory while
     * AI Reply and AccountBrowse use packageNames=null to receive the two system touch-interaction events.
     */
    fun acceptsCallbackEvent(
        eventPackage: String?,
        eventType: Int,
        targetPackage: String,
        runMode: RunMode?
    ): Boolean {
        val isGlobalTouch = eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
        return if (isGlobalTouch) {
            targetPackage.isNotBlank() &&
                (runMode == RunMode.ACCOUNT_BROWSE || runMode == RunMode.AI_REPLY)
        } else {
            targetPackage.isNotBlank() && eventPackage == targetPackage
        }
    }
}
