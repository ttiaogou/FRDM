package com.freedom.core.runtime

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope

/** Lifecycle/event contract implemented independently by each business runtime. */
interface ModeRuntimePort {
    val mode: RunMode
    fun onRunStarted()
    fun onRunStopped(reason: String)

    /** The callback must carry the exact page-route token captured at startup. */
    fun onPageRuntimeStarted(session: PageRuntimeSessionState, onPrepared: (PageRuntimeSessionState) -> Unit) {
        onRunStarted()
        onPrepared(session)
    }

    /** A fresh observation of the same route may retry a provisional startup.
     * Implementations with pending asynchronous preparation must not restart it. */
    fun onPageRuntimePreparationRetry(
        session: PageRuntimeSessionState,
        onPrepared: (PageRuntimeSessionState) -> Unit
    ) = Unit

    /** Same-runtime task handoff: request verification without resetting existing transactions. */
    fun onPageTaskChanged(session: PageRuntimeSessionState) = Unit

    /** Persist only existing checkpoint evidence before the coordinator revokes the lease. */
    fun onPageRuntimeWillSuspend(reason: String) = Unit

    /** Page exit is not a user stop. Implementations can preserve durable recovery state. */
    fun onPageRuntimeSuspended(reason: String) = onRunStopped(reason)

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
