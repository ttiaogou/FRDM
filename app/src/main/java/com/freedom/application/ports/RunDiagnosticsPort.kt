package com.freedom.application.ports

import com.freedom.core.logging.RunDiagnosticInput
import com.freedom.core.logging.RunLogCategory
import com.freedom.core.logging.RunLogLevel

/** Application-facing diagnostics capability; concrete file and retention details stay in Data. */
interface RunDiagnosticsPort {
    fun activeRunId(): String?
    fun awaitFinalizedRun(runId: String, timeoutMillis: Long = 8_000L): Boolean
    fun latestReportText(): String
    /** Export only sealed history; the data owner prevents concurrent retention eviction. */
    fun withSealedHistoryForExport(copy: (Set<String>) -> Unit) = copy(emptySet())
    /** Diagnostic-only input; no monitor-history duplication is required. */
    fun recordInput(input: RunDiagnosticInput) {}

    /** Run-scoped, pre-sanitized wire evidence. Never routed through ordinary runtime logs. */
    fun recordAiContextDiagnostic(runId: String, jsonLine: String) {}

    fun recordStructured(
        eventType: String,
        category: RunLogCategory,
        level: RunLogLevel = RunLogLevel.INFO,
        component: String,
        message: String,
        actionId: String? = null,
        windowId: Int? = null,
        previousState: String? = null,
        currentState: String? = null,
        result: String? = null,
        reason: String? = null,
        errorType: String? = null,
        errorDetail: String? = null,
        packageName: String? = null,
        activityClass: String? = null,
        pageGeneration: Long? = null,
        inputOrigin: String? = null,
        inputOriginEvidence: String? = null,
        viewId: String? = null
    )

    fun recordException(
        component: String,
        eventType: String,
        message: String,
        error: Throwable,
        actionId: String? = null,
        windowId: Int? = null
    )
}
