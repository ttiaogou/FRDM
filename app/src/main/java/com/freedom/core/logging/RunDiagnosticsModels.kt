package com.freedom.core.logging

/** Severity levels are stable machine-readable values used by reports and UI filters. */
enum class RunLogLevel { DEBUG, INFO, WARN, ERROR, FATAL }

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
    val errorDetail: String? = null
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
    val errorDetailDroppedBytes: Long = 0
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
