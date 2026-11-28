package com.freedom.data

import com.freedom.core.logging.RunLogSeveritySource
import com.freedom.core.logging.RunDiagnosticCounters
import com.freedom.core.logging.RunDiagnosticEvent
import com.freedom.core.logging.RunLogCategory
import com.freedom.core.logging.RunLogLevel
import com.freedom.core.logging.RunLogBudgetPolicy
import org.json.JSONObject

/** Keeps the persisted diagnostic event schema in one place. Field names are compatibility data. */
internal object RunDiagnosticEventJsonCodec {
    fun encode(event: RunDiagnosticEvent): String = JSONObject()
        .put("schemaVersion", 2)
        .put("sequence", event.sequence)
        .put("eventId", event.eventId)
        .put("runId", event.runId)
        .put("taskId", event.taskId)
        .put("traceId", event.traceId)
        .put("timestampEpochMs", event.timestampEpochMs)
        .put("elapsedRealtimeMs", event.elapsedRealtimeMs)
        .put("level", event.level.name)
        .put("category", event.category.name)
        .put("component", event.component)
        .put("eventType", event.eventType)
        .put("message", event.message)
        .put("actionId", event.actionId ?: JSONObject.NULL)
        .put("windowId", event.windowId ?: JSONObject.NULL)
        .put("packageName", event.packageName ?: JSONObject.NULL)
        .put("activityClass", event.activityClass ?: JSONObject.NULL)
        .put("pageGeneration", event.pageGeneration ?: JSONObject.NULL)
        .put("inputOrigin", event.inputOrigin ?: JSONObject.NULL)
        .put("inputOriginEvidence", event.inputOriginEvidence ?: JSONObject.NULL)
        .put("viewId", event.viewId ?: JSONObject.NULL)
        .put("previousState", event.previousState ?: JSONObject.NULL)
        .put("currentState", event.currentState ?: JSONObject.NULL)
        .put("result", event.result ?: JSONObject.NULL)
        .put("reason", event.reason ?: JSONObject.NULL)
        .put("errorType", event.errorType ?: JSONObject.NULL)
        .put("severitySource", event.severitySource.name)
        .put("requestGeneration", event.requestGeneration ?: JSONObject.NULL)
        .toString()

    fun decode(json: JSONObject): RunDiagnosticEvent = RunDiagnosticEvent(
        sequence = json.optLong("sequence"),
        eventId = json.optString("eventId"),
        runId = json.optString("runId"),
        taskId = json.optString("taskId"),
        traceId = json.optString("traceId"),
        timestampEpochMs = json.optLong("timestampEpochMs"),
        elapsedRealtimeMs = json.optLong("elapsedRealtimeMs"),
        level = runCatching { RunLogLevel.valueOf(json.optString("level")) }.getOrDefault(RunLogLevel.INFO),
        category = runCatching { RunLogCategory.valueOf(json.optString("category")) }.getOrDefault(RunLogCategory.GENERAL),
        component = json.optString("component"),
        eventType = json.optString("eventType"),
        message = json.optString("message"),
        actionId = json.optString("actionId").takeIf(String::isNotBlank),
        windowId = if (json.has("windowId") && !json.isNull("windowId")) json.optInt("windowId") else null,
        packageName = json.optString("packageName").takeIf(String::isNotBlank),
        activityClass = json.optString("activityClass").takeIf(String::isNotBlank),
        pageGeneration = if (json.has("pageGeneration") && !json.isNull("pageGeneration")) json.optLong("pageGeneration") else null,
        inputOrigin = json.optString("inputOrigin").takeIf(String::isNotBlank),
        inputOriginEvidence = json.optString("inputOriginEvidence").takeIf(String::isNotBlank),
        viewId = json.optString("viewId").takeIf(String::isNotBlank),
        previousState = json.optString("previousState").takeIf(String::isNotBlank),
        currentState = json.optString("currentState").takeIf(String::isNotBlank),
        result = json.optString("result").takeIf(String::isNotBlank),
        reason = json.optString("reason").takeIf(String::isNotBlank),
        errorType = json.optString("errorType").takeIf(String::isNotBlank),
        severitySource = runCatching { RunLogSeveritySource.valueOf(json.optString("severitySource")) }
            .getOrDefault(RunLogSeveritySource.LEGACY_INFERRED),
        requestGeneration = if (json.has("requestGeneration") && !json.isNull("requestGeneration"))
            json.optLong("requestGeneration") else null
    )

    fun errorDetailLine(event: RunDiagnosticEvent, detail: String): String = JSONObject()
        .put("sequence", event.sequence)
        .put("eventId", event.eventId)
        .put("capturedAtEpochMs", event.timestampEpochMs)
        .put("errorType", event.errorType ?: "UNKNOWN")
        .put("detail", detail)
        .toString()

    fun boundEventFields(event: RunDiagnosticEvent): RunDiagnosticEvent {
        var bounded = event.copy(
            runId = event.runId.take(96),
            taskId = event.taskId.take(96),
            traceId = event.traceId.take(128),
            component = event.component.take(80),
            eventType = event.eventType.take(100),
            message = RunLogBudgetPolicy.truncateUtf8(
                event.message,
                RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES - 5 * 1024
            ),
            actionId = event.actionId?.take(128),
            packageName = event.packageName?.take(160),
            activityClass = event.activityClass?.take(180),
            inputOrigin = event.inputOrigin?.take(40),
            inputOriginEvidence = event.inputOriginEvidence?.take(80),
            viewId = event.viewId?.take(240),
            previousState = event.previousState?.take(100),
            currentState = event.currentState?.take(100),
            result = event.result?.take(100),
            reason = event.reason?.let { RunLogBudgetPolicy.truncateUtf8(it, 512) },
            errorType = event.errorType?.take(160)
        )
        var messageBudget = RunLogBudgetPolicy.utf8Size(bounded.message)
        while (RunLogBudgetPolicy.utf8Size(RunDiagnosticEventJsonCodec.encode(bounded)) > RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES &&
            messageBudget > 0
        ) {
            messageBudget = (messageBudget * 3 / 4).coerceAtLeast(0)
            bounded = bounded.copy(message = RunLogBudgetPolicy.truncateUtf8(bounded.message, messageBudget))
        }
        if (RunLogBudgetPolicy.utf8Size(RunDiagnosticEventJsonCodec.encode(bounded)) > RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES) {
            bounded = bounded.copy(
                component = bounded.component.take(32),
                eventType = bounded.eventType.take(40),
                actionId = bounded.actionId?.take(48),
                packageName = bounded.packageName?.take(64),
                activityClass = bounded.activityClass?.take(64),
                inputOriginEvidence = bounded.inputOriginEvidence?.take(32),
                viewId = bounded.viewId?.take(64),
                reason = bounded.reason?.let { RunLogBudgetPolicy.truncateUtf8(it, 96) },
                errorType = bounded.errorType?.take(64)
            )
        }
        return bounded
    }

    fun counters(counters: RunDiagnosticCounters) = JSONObject()
        .put("acceptedEvents", counters.acceptedEvents)
        .put("writtenEvents", counters.writtenEvents)
        .put("errorEvents", counters.errorEvents)
        .put("warningEvents", counters.warningEvents)
        .put("deduplicatedEvents", counters.deduplicatedEvents)
        .put("droppedEvents", counters.droppedEvents)
        .put("droppedBytes", counters.droppedBytes)
        .put("truncatedRecords", counters.truncatedRecords)
        .put("truncatedBytes", counters.truncatedBytes)
        .put("writeFailures", counters.writeFailures)
        .put("eventBytes", counters.eventBytes)
        .put("errorDetailBytes", counters.errorDetailBytes)
        .put("errorDetailDroppedCount", counters.errorDetailDroppedCount)
        .put("errorDetailDroppedBytes", counters.errorDetailDroppedBytes)
        .put("normalEvictedEvents", counters.normalEvictedEvents)
        .put("normalEvictedBytes", counters.normalEvictedBytes)
        .put("retainedEvents", counters.retainedEvents)
        .put("queueDroppedEvents", counters.queueDroppedEvents)
        .put("budgetDroppedEvents", counters.budgetDroppedEvents)
        .put("incidentCaptured", counters.incidentCaptured)
        .put("incidentRejected", counters.incidentRejected)
        .put("incidentWriteFailures", counters.incidentWriteFailures)
        .put("incidentPostLimited", counters.incidentPostLimited)
        .put("incidentMerged", counters.incidentMerged)
        .put("incidentResolved", counters.incidentResolved)
        .put("incidentUnresolved", counters.incidentUnresolved)
        .put("incidentInterrupted", counters.incidentInterrupted)
}
