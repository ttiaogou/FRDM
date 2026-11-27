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

    /** Only explicit producer identities can merge or settle an incident. */
    data class TransactionKey(
        val runId: String, val taskId: String, val traceId: String,
        val generation: Long?, val family: String, val reason: String?
    )
    enum class Outcome { OPEN, RECOVERING, RESOLVED, UNRESOLVED, INTERRUPTED }

    fun transactionKey(event: RunDiagnosticEvent): TransactionKey? {
        if (event.severitySource != RunLogSeveritySource.PRODUCER_DECLARED) return null
        val task = known(event.taskId, event.runId) ?: return null
        val trace = known(event.traceId, event.runId) ?: known(event.actionId, event.runId) ?: return null
        val family = when {
            event.component == "UnifiedRecovery" && event.eventType.startsWith("RECOVERY_TRANSACTION_") -> "RECOVERY"
            event.component == "AiModel" && event.eventType.startsWith("MODEL_") &&
                event.requestGeneration != null -> "MODEL"
            event.component == "AiSend" && event.eventType.startsWith("MESSAGE_SEND_") &&
                event.requestGeneration != null -> "SEND"
            else -> "EVENT:${event.component}:${event.eventType}"
        }
        return TransactionKey(event.runId, task, trace, event.requestGeneration, family,
            event.reason.takeIf { family.startsWith("EVENT:") })
    }

    /** A matching key is necessary but not sufficient: require an authoritative terminal event. */
    fun outcome(event: RunDiagnosticEvent, key: TransactionKey): Outcome? {
        if (transactionKey(event) != key) return null
        return when (key.family) {
            "RECOVERY" -> when {
                event.eventType == "RECOVERY_TRANSACTION_RESOLVED" && event.result == "ACTIVE" -> Outcome.RESOLVED
                event.eventType == "RECOVERY_TRANSACTION_INTERRUPTED" && event.result == "STOPPED_BY_USER" -> Outcome.INTERRUPTED
                event.eventType == "RECOVERY_TRANSACTION_PROGRESS" &&
                    event.result in setOf("RECOVERING", "VERIFYING", "RESUMING") -> Outcome.RECOVERING
                else -> null
            }
            "MODEL" -> when {
                event.eventType == "MODEL_REQUEST_FINISHED" && event.result == "SUCCEEDED" -> Outcome.RESOLVED
                event.eventType == "MODEL_REQUEST_FINISHED" && event.result == "CANCELLED" -> Outcome.INTERRUPTED
                event.eventType == "MODEL_REQUEST_FINISHED" && event.result == "FAILED" -> Outcome.UNRESOLVED
                event.eventType in setOf("MODEL_RETRY_SCHEDULED", "MODEL_RETRY_EXECUTING", "MODEL_ATTEMPT_STARTED") -> Outcome.RECOVERING
                else -> null
            }
            "SEND" -> when {
                event.eventType == "MESSAGE_SEND_COMPLETED" && event.result == "CONFIRMED" -> Outcome.RESOLVED
                event.eventType in setOf("MESSAGE_SEND_SUBMITTED", "MESSAGE_SEND_REHYDRATED", "MESSAGE_SEND_CONFIRMED") -> Outcome.RECOVERING
                else -> null
            }
            else -> null
        }
    }

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
