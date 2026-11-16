package com.freedom.core.runtime

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope

/** Lifecycle/event contract implemented independently by each business runtime. */
interface ModeRuntimePort {
    val mode: RunMode
    fun onRunStarted()
    fun onRunStopped(reason: String)
    fun onAccessibilityServiceConnected()
    fun onAccessibilityServiceDisconnected()
    fun onTargetApplicationEvent(event: AccessibilityEventEnvelope)
    fun onGlobalTouchEvent(event: AccessibilityEventEnvelope)

    /**
     * Pre-deduplication path for target-app click/selection events that may represent human input.
     * Implementations must keep this handler side-effect bounded and must not persist event text.
     */
    fun onPreemptiveTargetInteraction(event: AccessibilityEventEnvelope) = Unit

    /** Optional mode-specific handling for activity transitions after event normalization. */
    fun onActivityTransition(activityClass: String) = Unit
    fun onExternalRefresh() = Unit

    /** Diagnostics and manual-interaction fallback are dispatched to the active runtime only. */
    fun isTrackedInteractionEvent(event: AccessibilityEventEnvelope): Boolean = false
    fun interactionDiagnostics(event: AccessibilityEventEnvelope, nowMillis: Long): List<String> = emptyList()
    fun handleManualInteractionFallback(event: AccessibilityEventEnvelope) = Unit

    /** Service lifecycle hooks; platform cleanup remains inside concrete adapters. */
    fun onAccessibilityServiceInterrupted(reason: String) = Unit
    fun onServiceDestroyed() = Unit
    fun resetRuntime(reason: String) = onRunStopped(reason)
}
