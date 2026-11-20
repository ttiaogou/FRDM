package com.freedom.core.accessibility.api

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope

/**
 * Platform-neutral entry contract for all normalized accessibility events.
 *
 * Android adapters are responsible for converting platform callbacks into an
 * AccessibilityEventEnvelope before handing the event to this boundary.
 * Business features must consume events after this entry point instead of
 * receiving Android accessibility callbacks directly.
 */
fun interface UnifiedAccessibilityEntry {
    fun accept(event: AccessibilityEventEnvelope)
}
