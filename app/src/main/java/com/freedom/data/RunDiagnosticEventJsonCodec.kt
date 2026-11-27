package com.freedom.data

import com.freedom.core.logging.RunLogSeveritySource
import com.freedom.core.logging.RunDiagnosticCounters
import com.freedom.core.logging.RunDiagnosticEvent
import com.freedom.core.logging.RunLogCategory
import com.freedom.core.logging.RunLogLevel
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
}
