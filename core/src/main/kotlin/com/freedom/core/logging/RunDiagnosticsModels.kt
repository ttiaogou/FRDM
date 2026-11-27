package com.freedom.core.logging

/** Severity levels are stable machine-readable values used by reports and UI filters. */
enum class RunLogLevel { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }

/** Persisted provenance: a producer's declared level must not be inferred again. */
enum class RunLogSeveritySource { LEGACY_INFERRED, PRODUCER_DECLARED }

/** A producer-owned observation, without storage identities or any business/UI authority.
 *  Text is for display; typed fields, not its wording, govern classification.
 */
data class RunDiagnosticInput(
    val eventType: String,
    val level: RunLogLevel,
    val category: RunLogCategory,
    val component: String,
    val message: String,
    val taskId: String? = null,
    val traceId: String? = null,
    val result: String? = null,
    val reason: String? = null,
    val requestGeneration: Long? = null,
    val actionId: String? = null,
    val windowId: Int? = null,
    val packageName: String? = null,
    val activityClass: String? = null,
    val pageGeneration: Long? = null,
    val inputOrigin: String? = null,
    val inputOriginEvidence: String? = null,
    val viewId: String? = null,
    val previousState: String? = null,
    val currentState: String? = null,
    val errorType: String? = null,
    val errorDetail: String? = null
) {
    fun toEvent(
        runId: String, sequence: Long, timestampEpochMs: Long, elapsedRealtimeMs: Long
    ): RunDiagnosticEvent = RunDiagnosticEvent(
        sequence = sequence,
        eventId = java.util.UUID.randomUUID().toString(),
        runId = runId,
        taskId = taskId ?: "UNKNOWN",
        traceId = traceId ?: actionId ?: runId,
        timestampEpochMs = timestampEpochMs,
        elapsedRealtimeMs = elapsedRealtimeMs,
        level = level,
        category = category,
        component = component,
        eventType = eventType,
        message = RunLogBudgetPolicy.redactSensitivePayload(message),
        result = result,
        reason = reason?.let(RunLogBudgetPolicy::redactSensitivePayload),
        severitySource = RunLogSeveritySource.PRODUCER_DECLARED,
        requestGeneration = requestGeneration,
        actionId = actionId,
        windowId = windowId,
        packageName = packageName,
        activityClass = activityClass,
        pageGeneration = pageGeneration,
        inputOrigin = inputOrigin,
        inputOriginEvidence = inputOriginEvidence,
        viewId = viewId,
        previousState = previousState,
        currentState = currentState,
        errorType = errorType,
        errorDetail = errorDetail
    )
}

enum class RunLogCategory {
    LIFECYCLE,
    TASK,
    NAVIGATION,
    INPUT,
    GESTURE,
    COLLECTION,
    DATA_QUALITY,
    ARCHIVE,
    ERROR,
    GENERAL
}

data class RunDiagnosticEvent(
    val sequence: Long,
    val eventId: String,
    val runId: String,
    val taskId: String,
    val traceId: String,
    val timestampEpochMs: Long,
    val elapsedRealtimeMs: Long,
    val level: RunLogLevel,
    val category: RunLogCategory,
    val component: String,
    val eventType: String,
    val message: String,
    val actionId: String? = null,
    val windowId: Int? = null,
    val packageName: String? = null,
    val activityClass: String? = null,
    val pageGeneration: Long? = null,
    val inputOrigin: String? = null,
    val inputOriginEvidence: String? = null,
    val viewId: String? = null,
    val previousState: String? = null,
    val currentState: String? = null,
    val result: String? = null,
    val reason: String? = null,
    val errorType: String? = null,
    /** Optional stack/exception detail; persisted separately under its own byte budget. */
    val errorDetail: String? = null,
    val severitySource: RunLogSeveritySource = RunLogSeveritySource.LEGACY_INFERRED,
    val requestGeneration: Long? = null
)

data class RunDiagnosticCounters(
    val acceptedEvents: Long = 0,
    val writtenEvents: Long = 0,
    val errorEvents: Long = 0,
    val warningEvents: Long = 0,
    val deduplicatedEvents: Long = 0,
    val droppedEvents: Long = 0,
    val droppedBytes: Long = 0,
    val truncatedRecords: Long = 0,
    val truncatedBytes: Long = 0,
    val writeFailures: Long = 0,
    val eventBytes: Long = 0,
    val errorDetailBytes: Long = 0,
    val errorDetailDroppedCount: Long = 0,
    val errorDetailDroppedBytes: Long = 0,
    /** Intentional rotation, not a capture or write failure. */
    val normalEvictedEvents: Long = 0,
    val normalEvictedBytes: Long = 0,
    val retainedEvents: Long = 0,
    val queueDroppedEvents: Long = 0,
    val budgetDroppedEvents: Long = 0,
    val incidentCaptured: Long = 0,
    val incidentRejected: Long = 0,
    val incidentWriteFailures: Long = 0,
    val incidentPostLimited: Long = 0
)

data class RunDiagnosticIssue(
    val code: String,
    val level: RunLogLevel,
    val title: String,
    val description: String,
    val evidenceSequences: List<Long> = emptyList(),
    val suggestedAction: String
)

data class RunDiagnosticHistoryPattern(
    val code: String,
    val occurrenceCount: Int,
    val analyzedRunCount: Int,
    val presentInCurrentRun: Boolean
)

data class RunDiagnosticReport(
    val runId: String,
    val taskId: String,
    val result: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val durationMs: Long,
    val counters: RunDiagnosticCounters,
    val issues: List<RunDiagnosticIssue>,
    val conclusion: String,
    val historicalPatterns: List<RunDiagnosticHistoryPattern> = emptyList()
)
