package com.freedom.application.runtime

import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.RunStateRepository

/**
 * Application command boundary used by UI surfaces to request a run-state change.
 * Runtime lifecycle and event distribution remain exclusively owned by RunSessionCoordinator
 * inside the active AccessibilityService composition root.
 */
class RunSessionCommandUseCase(
    private val state: RunStateRepository,
    private val onDiagnostic: (String) -> Unit = {}
) {
    /** Persist the optional business mode before run intent; null starts manual-observation standby. */
    @Synchronized
    fun requestStart(mode: RunMode? = null): Boolean {
        if (state.isRunRequested()) return false
        // Only an explicitly selected business mode acknowledges the AI input-origin review hold.
        // Manual-observation standby does not start AI Reply and must not clear that safety hold.
        if (mode != null) state.setInputManualReviewRequired(false)
        state.setActiveMode(mode)
        state.setRunRequested(true)
        return true
    }

    /** Clear persisted run intent; the service coordinator stops the active runtime on refresh. */
    @Synchronized
    fun requestStop() {
        val previousMode = state.activeMode()
        val wasRequested = state.isRunRequested()
        onDiagnostic(
            "运行请求停止[REQUESTED]：source=RunSessionCommandUseCase.requestStop；" +
                "previousMode=${previousMode?.name ?: "NONE"}；previousRunRequested=$wasRequested"
        )
        state.setRunRequested(false)
    }
}
