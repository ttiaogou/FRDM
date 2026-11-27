package com.freedom.core.logging

/** Evidence association only: never authorizes a business operation or establishes causality. */
object RunIncidentEvidencePolicy {
    enum class Relation { SAME_TRACE, SAME_ACTION, SAME_TASK_PAGE, NEARBY_UNCORRELATED }

    fun triggerLevel(event: RunDiagnosticEvent): RunLogLevel? {
        if (RunDiagnosticAnalyzer.isPreviousRunRecoveryEvent(event) ||
            event.result == "DEDUPLICATED" || event.eventType == "event_dedup_summary" ||
            event.eventType in setOf("run_started", "run_finished")
        ) return null
        return when (event.level) {
            RunLogLevel.FATAL -> RunLogLevel.FATAL
            RunLogLevel.ERROR -> when {
                RunDiagnosticAnalyzer.isRuntimeFailureEvent(event) -> RunLogLevel.ERROR
                RunLogBudgetPolicy.isNonFatalQualityWarningMessage(event.message) -> RunLogLevel.WARN
                else -> null
            }
            RunLogLevel.WARN -> RunLogLevel.WARN
            else -> null
        }
    }

    private fun known(value: String?, runId: String): String? =
        value?.takeUnless { it.isBlank() || it == runId ||
            it.equals("UNKNOWN", true) || it.equals("NONE", true) || it.equals("null", true) }

    fun relation(trigger: RunDiagnosticEvent, candidate: RunDiagnosticEvent): Relation? {
        if (trigger.runId != candidate.runId || trigger.eventId == candidate.eventId) return null
        val task = known(trigger.taskId, trigger.runId)
        val otherTask = known(candidate.taskId, candidate.runId)
        if (task != null && otherTask != null && task != otherTask) return null
        val trace = known(trigger.traceId, trigger.runId)
        val otherTrace = known(candidate.traceId, candidate.runId)
        val action = known(trigger.actionId, trigger.runId)
        val otherAction = known(candidate.actionId, candidate.runId)
        val generationConflict = trigger.requestGeneration != null && candidate.requestGeneration != null &&
            trigger.requestGeneration != candidate.requestGeneration
        if (generationConflict) return null
        // Explicitly different request traces cannot be silently associated by page proximity.
        if (trace != null && otherTrace != null && trace != otherTrace) return null
        if (trace != null && trace == otherTrace) return Relation.SAME_TRACE
        if (action != null && otherAction != null && action != otherAction) return null
        if (action != null && action == otherAction) return Relation.SAME_ACTION
        if (task != null && task == otherTask && trigger.windowId != null &&
            trigger.windowId == candidate.windowId && trigger.pageGeneration != null &&
            trigger.pageGeneration == candidate.pageGeneration
        ) return Relation.SAME_TASK_PAGE
        return Relation.NEARBY_UNCORRELATED
    }
}
