package com.freedom.application.runtime

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
    /** Authorize unified page observation, without selecting or persisting a business runtime. */
    @Synchronized
    fun requestStart(): Boolean {
        if (state.isRunRequested()) return false
        state.setRunRequested(true)
        return true
    }

    /** Clear persisted run intent; the service coordinator stops the active runtime on refresh. */
    @Synchronized
    fun requestStop() {
        val wasRequested = state.isRunRequested()
        onDiagnostic(
            "运行请求停止[REQUESTED]：source=RunSessionCommandUseCase.requestStop；" +
                "previousRunRequested=$wasRequested"
        )
        state.setRunRequested(false)
    }
}
