package com.freedom.core.runtime

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope

/** Lifecycle/event contract implemented independently by each business runtime. */
interface ModeRuntimePort {
    val mode: RunMode
    /** Start the selected task, or retry its existing preparation without resetting it.
     * Return true only when preparation completed. The task lease remains independently verified. */
    fun onRunStarted(taskId: String, retry: Boolean): Boolean
    fun onRunStopped(reason: String)

    /** Same-runtime task handoff: request verification without resetting existing transactions. */
    fun onPageTaskChanged(session: PageRuntimeSessionState) = Unit

    /** Save existing business progress, without activating recovery or granting UI authority. */
    fun saveProgressBeforeExit(reason: String, serviceBoundary: Boolean) = Unit

    /** Normal page exit: cancel transient work, preserve durable progress, do not simulate service loss. */
    fun onRunPaused(reason: String) = onRunStopped(reason)

    fun onAccessibilityServiceConnected()
    fun onAccessibilityServiceDisconnected()
    fun onTargetApplicationEvent(event: AccessibilityEventEnvelope)
    fun onGlobalTouchEvent(event: AccessibilityEventEnvelope) = Unit

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
