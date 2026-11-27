package com.freedom.data

import com.freedom.core.logging.RunDiagnosticInput
import com.freedom.core.logging.RunLogSeveritySource
import android.content.Context
import android.os.SystemClock
import com.freedom.core.logging.RunDiagnosticAnalyzer
import com.freedom.core.logging.RunDiagnosticCounters
import com.freedom.core.logging.RunDiagnosticEvent
import com.freedom.core.logging.RunDiagnosticReport
import com.freedom.core.logging.RunDiagnosticHistoryAnalyzer
import com.freedom.core.logging.RunLogBudgetPolicy
import com.freedom.core.logging.RunLogCategory
import com.freedom.core.logging.RunLogLevel
import com.freedom.application.ports.RunDiagnosticsPort
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

/**
 * Run-scoped observability store. It keeps the existing monitor UI log separate from structured,
 * byte-budgeted traces and creates a deterministic diagnostic report when a run is sealed.
 * User-facing message bodies and contact labels are redacted before entering this store.
 */
class RunDiagnosticsManager private constructor(context: Context) : RunDiagnosticsPort {
    companion object {
    private const val HISTORY_LIMIT = 20
    private const val HISTORY_TOTAL_BYTES = 64L * 1024L * 1024L
    private const val MAX_RECENT_EVENTS = 512
    private const val WRITER_QUEUE_CAPACITY = 1024
    private const val DEDUP_WINDOW_MS = RunLogBudgetPolicy.DEDUPLICATION_WINDOW_MS
    private const val MARKER_FILE = "active_run.json"
    private const val AI_CONTEXT_RECORD_LIMIT = 512 * 1024
    private const val AI_CONTEXT_RUN_LIMIT = 2L * 1024L * 1024L

    @Volatile private var instance: RunDiagnosticsManager? = null

    fun get(context: Context): RunDiagnosticsManager = synchronized(this) {
        instance ?: RunDiagnosticsManager(context.applicationContext).also { instance = it }
    }
    }

    private data class ActiveRun(
        val runId: String,
        val mode: String,
        val startedAt: Long,
        val directory: File,
        var nextSequence: Long = 1L,
        val rollingStore: RunEventRollingStore = RunEventRollingStore(directory),
        var writtenEventBytes: Long = 0L,
        var normalEvictedEvents: Long = 0L,
        var normalEvictedBytes: Long = 0L,
        var queueDroppedEvents: Long = 0L,
        var budgetDroppedEvents: Long = 0L,
        var acceptedEvents: Long = 0L,
        var writtenEvents: Long = 0L,
        var errorEvents: Long = 0L,
        var warningEvents: Long = 0L,
        var deduplicatedEvents: Long = 0L,
        var droppedEvents: Long = 0L,
        var droppedBytes: Long = 0L,
        var truncatedRecords: Long = 0L,
        var truncatedBytes: Long = 0L,
        var writeFailures: Long = 0L,
        var reservedErrorDetailBytes: Long = 0L,
        var writtenErrorDetailBytes: Long = 0L,
        var errorDetailDroppedCount: Long = 0L,
        var errorDetailDroppedBytes: Long = 0L,
        var aiContextReservedBytes: Long = 0L,
        var aiContextWrittenBytes: Long = 0L,
        var aiContextWrittenRecords: Long = 0L,
        var aiContextDroppedRecords: Long = 0L,
        var lastCounterPersistAtMs: Long = 0L,
        var lastSignature: String = "",
        var lastEventAt: Long = 0L,
        var pendingDuplicateCount: Long = 0L,
        var pendingDuplicateType: String = "",
        var finishRequested: Boolean = false,
        var finishResult: String = "STOPPED",
        var finishReason: String? = null,
        var fallbackFinishFuture: ScheduledFuture<*>? = null,
        val finishLatch: CountDownLatch = CountDownLatch(1),
        val recentEvents: ArrayDeque<RunDiagnosticEvent> = ArrayDeque()
    )

    private val appContext = context.applicationContext
    private val rootDirectory = File(appContext.filesDir, "freedom_diagnostics")
    private val runsDirectory = File(rootDirectory, "runs")
    private val markerFile = File(rootDirectory, MARKER_FILE)
    private val finishScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Freedom-RunDiagnosticsFinish").apply { isDaemon = true }
    }
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WRITER_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "Freedom-RunDiagnosticsWriter").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    @Volatile private var activeRun: ActiveRun? = null
    @Volatile private var finalizingRun: ActiveRun? = null
    @Volatile private var finalizationPending: Boolean = false

    init {
        rootDirectory.mkdirs()
        runsDirectory.mkdirs()
        recoverPreviousRunIfNeeded()
    }

    /** Returns the diagnostic run currently accepting events, for pairing with sample archives. */
    @Synchronized
    override fun activeRunId(): String? = activeRun?.takeUnless { it.finishRequested }?.runId

    /**
     * Waits for the exact diagnostic report linked to a completed sample run. This is used only
     * from the background export worker; it never forces an active task to stop.
     */
    override fun awaitFinalizedRun(runId: String, timeoutMillis: Long): Boolean {
        if (!isSafeRunId(runId)) return false
        val report = File(File(runsDirectory, runId), "diagnostic_report.txt")
        if (report.isFile) return true
        val (state, shouldRequestFinalization) = synchronized(this) {
            val active = activeRun?.takeIf { it.runId == runId }
            val finalizing = finalizingRun?.takeIf { it.runId == runId }
            (active ?: finalizing) to (active?.finishRequested == true)
        }
        state ?: return report.isFile
        // A sample archive is only exportable after its task stopped. If the diagnostic manager
        // has already received that stop request but its fallback timer has not fired yet, ask it
        // to run the idempotent finalizer now so the paired report is available in this export.
        if (shouldRequestFinalization) finishRun()
        val completed = runCatching { state.finishLatch.await(timeoutMillis, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!completed) return false
        return report.isFile
    }

    private fun isSafeRunId(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9._-]{1,120}")) && value != "." && value != ".."

    fun beginRun(mode: String, resumedAfterProcessRecovery: Boolean = false): String {
        // Give the service's runtime cleanup a short window to append its final evidence after
        // the persisted run-intent flag is cleared. Do not hold this manager lock while waiting.
        val closingRun = synchronized(this) { activeRun?.takeIf { it.finishRequested } }
        if (closingRun != null) {
            runCatching { closingRun.finishLatch.await(4, TimeUnit.SECONDS) }
        }
        val shouldAwaitFinalizer = synchronized(this) { finalizationPending }
        if (shouldAwaitFinalizer) {
            // Wait without holding the manager lock: queued writer tasks update counters under it.
            runCatching { writer.submit {}.get(10, TimeUnit.SECONDS) }
        }
        return synchronized(this) {
            activeRun?.let {
                check(!it.finishRequested) { "上一次诊断报告仍在收尾，无法复用已关闭的运行会话" }
                return@synchronized it.runId
            }
            check(!finalizationPending) { "上一次诊断报告仍在封存，请勿并发启动新任务" }
            // A stale marker is recovered before a new run can overwrite it.
            recoverPreviousRunIfNeeded()
            val runId = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
            val directory = File(runsDirectory, runId)
            check(directory.mkdirs() || directory.isDirectory) { "无法创建运行诊断目录" }
            val state = ActiveRun(
                runId = runId,
                mode = mode.ifBlank { "UNKNOWN" },
                startedAt = System.currentTimeMillis(),
                directory = directory
            )
            activeRun = state
            writeMarker(
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("active", true)
                    .put("runId", runId)
                    .put("mode", state.mode)
                    .put("startedAtEpochMs", state.startedAt)
                    .put("directory", runId)
            )
            recordInternal(
                state,
                RunDiagnosticEvent(
                    sequence = 0L,
                    eventId = UUID.randomUUID().toString(),
                    runId = runId,
                    taskId = runId,
                    traceId = runId,
                    timestampEpochMs = state.startedAt,
                    elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    level = RunLogLevel.INFO,
                    category = RunLogCategory.TASK,
                    component = "RunCoordinator",
                    eventType = "run_started",
                    message = if (resumedAfterProcessRecovery) "任务运行会话在进程恢复后重新建立；上一进程运行已单独标记为异常结束" else "运行任务开始；mode=${state.mode}",
                    currentState = "RUNNING",
                    result = "STARTED"
                ),
                allowDedup = false
            )
            runId
        }
    }

    /** Records a redacted free-text log as a structured diagnostic event. */
    @Synchronized
    fun recordLog(message: String) {
        val current = activeRun ?: return
        val now = System.currentTimeMillis()
        val safeBeforeLimit = RunLogBudgetPolicy.redactSensitivePayload(message)
        val event = RunLogBudgetPolicy.toEvent(
            message = safeBeforeLimit,
            runId = current.runId,
            sequence = current.nextSequence++,
            timestampEpochMs = now,
            elapsedRealtimeMs = SystemClock.elapsedRealtime()
        )
        val sourceBytes = RunLogBudgetPolicy.utf8Size(safeBeforeLimit)
        val storedBytes = RunLogBudgetPolicy.utf8Size(event.message)
        if (storedBytes < sourceBytes) {
            current.truncatedRecords++
            current.truncatedBytes += (sourceBytes - storedBytes).toLong()
        }
        recordInternal(
            current,
            event,
            allowDedup = event.level != RunLogLevel.ERROR && event.level != RunLogLevel.FATAL
        )
    }

    /** Typed producers share the same sequence, storage and lifecycle as legacy logs. */
    @Synchronized
    fun recordInput(input: RunDiagnosticInput) {
        val current = activeRun ?: return
        val event = input.toEvent(
            current.runId, current.nextSequence++, System.currentTimeMillis(), SystemClock.elapsedRealtime()
        )
        recordInternal(current, event,
            allowDedup = input.level != RunLogLevel.ERROR && input.level != RunLogLevel.FATAL)
    }

    /** The plugin pipe is bound to the run that issued the OCR request, never a later run. */
    @Synchronized
    internal fun recordOcrPluginDiagnostic(runId: String, requestId: String, attempt: Int, level: RunLogLevel, message: String) {
        if (activeRun?.takeIf { !it.finishRequested }?.runId != runId) return
        recordStructured(
            eventType = "ocr_plugin_diagnostic",
            category = RunLogCategory.COLLECTION,
            level = level,
            component = "OcrPlugin",
            message = "requestId=$requestId;attempt=$attempt;$message",
            actionId = "$requestId-attempt-$attempt"
        )
    }

    /** The caller binds the sink to the original run; late callbacks cannot enter another run. */
    @Synchronized
    override fun recordAiContextDiagnostic(runId: String, jsonLine: String) {
        val state = activeRun?.takeIf { it.runId == runId && !it.finishRequested } ?: return
        val bytes = jsonLine.toByteArray(Charsets.UTF_8).size.toLong() + 1L
        if (bytes > AI_CONTEXT_RECORD_LIMIT || state.aiContextReservedBytes + bytes > AI_CONTEXT_RUN_LIMIT ||
            jsonLine.isBlank() || '\n' in jsonLine || '\r' in jsonLine
        ) {
            state.aiContextDroppedRecords++
            return
        }
        if (!hasQueueRoom(RunEventRollingStore.Lane.NORMAL)) {
            state.aiContextDroppedRecords++
            return
        }
        state.aiContextReservedBytes += bytes
        try {
            writer.execute {
                runCatching {
                    File(state.directory, "ai_context_diagnostics.jsonl").appendText(jsonLine + "\n", Charsets.UTF_8)
                }.onSuccess {
                    synchronized(this) {
                        state.aiContextWrittenRecords++
                        state.aiContextWrittenBytes += bytes
                    }
                }.onFailure {
                    synchronized(this) { state.aiContextDroppedRecords++ }
                }
            }
        } catch (_: RejectedExecutionException) {
            state.aiContextReservedBytes -= bytes
            state.aiContextDroppedRecords++
        }
    }

    /** Explicit structured event entry point for modules that already have typed event data. */
    @Synchronized
    override fun recordStructured(
        eventType: String,
        category: RunLogCategory,
        level: RunLogLevel,
        component: String,
        message: String,
        actionId: String?,
        windowId: Int?,
        previousState: String?,
        currentState: String?,
        result: String?,
        reason: String?,
        errorType: String?,
        errorDetail: String?,
        packageName: String?,
        activityClass: String?,
        pageGeneration: Long?,
        inputOrigin: String?,
        inputOriginEvidence: String?,
        viewId: String?
    ) {
        val state = activeRun ?: return
        val event = synchronized(this) {
            if (activeRun !== state) return
            RunDiagnosticInput(
                eventType = eventType, level = level, category = category,
                component = component, message = message, actionId = actionId,
                windowId = windowId, previousState = previousState, currentState = currentState,
                result = result, reason = reason, errorType = errorType, errorDetail = errorDetail,
                packageName = packageName, activityClass = activityClass, pageGeneration = pageGeneration,
                inputOrigin = inputOrigin, inputOriginEvidence = inputOriginEvidence, viewId = viewId
            ).toEvent(state.runId, state.nextSequence++, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        }
        recordInternal(state, event, allowDedup = result == "DEDUPLICATED")
    }

    override fun recordException(
        component: String,
        eventType: String,
        message: String,
        error: Throwable,
        actionId: String?,
        windowId: Int?
    ) {
        recordStructured(
            eventType = eventType,
            category = RunLogCategory.ERROR,
            level = RunLogLevel.ERROR,
            component = component,
            message = message,
            actionId = actionId,
            windowId = windowId,
            errorType = error.javaClass.name,
            errorDetail = error.stackTraceToString()
        )
    }

    @Synchronized
    fun finishRun(result: String = "STOPPED", reason: String? = null) {
        val state = activeRun ?: return
        if (state.finishRequested) {
            if (result != "STOPPED") state.finishResult = result
            if (!reason.isNullOrBlank()) state.finishReason = reason
            // The owning service calls this after runtime cleanup; cancel the fallback timer so
            // the report cannot seal before the runtime/archive cleanup has appended its evidence.
            state.fallbackFinishFuture?.cancel(false)
            state.fallbackFinishFuture = null
            finishScheduler.execute { finalizeRequestedRun(state) }
            return
        }
        state.finishRequested = true
        state.finishResult = result
        state.finishReason = reason
        // Fallback for cases where the AccessibilityService is unavailable to receive refresh.
        state.fallbackFinishFuture = finishScheduler.schedule(
            { finalizeRequestedRun(state) },
            15_000,
            TimeUnit.MILLISECONDS
        )
    }

    private fun finalizeRequestedRun(state: ActiveRun) {
        val result: String
        val reason: String?
        val finishEvent: RunDiagnosticEvent
        synchronized(this) {
            if (activeRun !== state || !state.finishRequested) {
                // Another scheduled completion may run after the owning finalizer has already
                // detached this state. Only the actual finalizer releases the completion latch.
                return
            }
            flushDuplicateSummary(state)
            result = state.finishResult
            reason = state.finishReason
            finishEvent = RunDiagnosticEvent(
                sequence = state.nextSequence++,
                eventId = UUID.randomUUID().toString(),
                runId = state.runId,
                taskId = state.runId,
                traceId = state.runId,
                timestampEpochMs = System.currentTimeMillis(),
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                level = RunLogLevel.INFO,
                category = RunLogCategory.TASK,
                component = "RunCoordinator",
                eventType = "run_finished",
                message = "运行任务结束；result=$result${reason?.let { "；reason=$it" }.orEmpty()}",
                previousState = "RUNNING",
                currentState = result,
                result = result,
                reason = reason
            )
            state.acceptedEvents++
            addRecent(state, finishEvent)
            activeRun = null
            finalizingRun = state
            finalizationPending = true
        }
        val finalize = Runnable {
            try {
                appendEventLine(state, finishEvent)
                sealEvents(state.directory)
                val finishedAt = System.currentTimeMillis()
                val storedEvents = readEvents(state.directory)
                val reportEvents = if (storedEvents.isNotEmpty()) storedEvents else state.recentEvents.toList()
                // Keep the displayed error/warning totals aligned with the event evidence used
                // by the analyzer. Failed writes remain visible in dropped/write-failure counters.
                val reportCounters = counters(state).copy(
                    errorEvents = reportEvents.count(RunDiagnosticAnalyzer::isRuntimeFailureEvent).toLong(),
                    warningEvents = reportEvents.count {
                        it.level == RunLogLevel.WARN && !RunDiagnosticAnalyzer.isPreviousRunRecoveryEvent(it)
                    }.toLong()
                )
                writeCounterSnapshot(state.directory, RunDiagnosticEventJsonCodec.counters(reportCounters).toString())
                val baseReport = RunDiagnosticAnalyzer.analyze(
                    runId = state.runId,
                    result = result,
                    startedAtEpochMs = state.startedAt,
                    finishedAtEpochMs = finishedAt,
                    events = reportEvents,
                    counters = reportCounters
                )
                val report = RunDiagnosticHistoryAnalyzer.enrich(
                    baseReport,
                    loadHistoricalIssueCodes(excludingRunId = state.runId)
                )
                writeReport(state.directory, report)
                if (state.aiContextWrittenRecords > 0L || state.aiContextDroppedRecords > 0L) {
                    runCatching {
                        File(state.directory, "ai_context_diagnostics_summary.json").writeText(JSONObject()
                            .put("schemaVersion", 1)
                            .put("writtenRecords", state.aiContextWrittenRecords)
                            .put("writtenBytes", state.aiContextWrittenBytes)
                            .put("droppedRecords", state.aiContextDroppedRecords)
                            .put("recordLimitBytes", AI_CONTEXT_RECORD_LIMIT)
                            .put("runLimitBytes", AI_CONTEXT_RUN_LIMIT)
                            .toString(), Charsets.UTF_8)
                    }
                }
                writeDiagnosticManifest(state.directory, state.runId)
                writeMarker(
                    JSONObject()
                        .put("schemaVersion", 1)
                        .put("active", false)
                        .put("runId", state.runId)
                        .put("mode", state.mode)
                        .put("startedAtEpochMs", state.startedAt)
                        .put("finishedAtEpochMs", finishedAt)
                        .put("result", result)
                        .put("directory", state.runId)
                )
                runCatching { appendCompletionSummary(report) }
                runCatching { pruneHistory(state.runId) }
            } catch (error: Exception) {
                synchronized(this) { state.writeFailures++ }
                runCatching {
                    val fallback = RunDiagnosticAnalyzer.analyze(
                        runId = state.runId,
                        result = "DIAGNOSTIC_FINALIZATION_FAILED",
                        startedAtEpochMs = state.startedAt,
                        finishedAtEpochMs = System.currentTimeMillis(),
                        events = readEvents(state.directory).ifEmpty { state.recentEvents.toList() },
                        counters = counters(state)
                    ).copy(
                        conclusion = "诊断封存过程发生异常：${error.javaClass.simpleName}。原始运行事件仍保留在本地目录，报告可能不完整。"
                    )
                    writeReport(state.directory, fallback)
                    writeDiagnosticManifest(state.directory, state.runId)
                    writeMarker(
                        JSONObject()
                            .put("schemaVersion", 1)
                            .put("active", false)
                            .put("runId", state.runId)
                            .put("mode", state.mode)
                            .put("startedAtEpochMs", state.startedAt)
                            .put("finishedAtEpochMs", System.currentTimeMillis())
                            .put("result", "DIAGNOSTIC_FINALIZATION_FAILED")
                            .put("finalizationFailed", true)
                            .put("directory", state.runId)
                    )
                }
            } finally {
                synchronized(this) {
                    if (finalizingRun === state) finalizingRun = null
                    finalizationPending = false
                    state.finishLatch.countDown()
                }
            }
        }
        enqueueFinalizer(finalize)
    }

    /** Idempotent recovery: an active marker from a previous process becomes an interrupted run. */
    @Synchronized
    fun recoverPreviousRunIfNeeded(): Boolean {
        if (!markerFile.isFile) return false
        val marker = runCatching { JSONObject(markerFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return false
        if (!marker.optBoolean("active", false)) return false
        val runId = marker.optString("runId").takeIf(String::isNotBlank) ?: return false
        val safeDirectory = File(runsDirectory, runId)
        if (!safeDirectory.canonicalPath.startsWith(runsDirectory.canonicalPath + File.separator)) return false
        safeDirectory.mkdirs()
        val startedAt = marker.optLong("startedAtEpochMs", safeDirectory.lastModified())
        val finishedAt = System.currentTimeMillis()
        // The process may have stopped before the canonical compatibility file was sealed.
        val sealed = runCatching { sealEvents(safeDirectory) }.isSuccess
        // Never create a canonical file containing only a recovery marker if segment sealing
        // failed: that would hide the original spool. Analyze surviving sources instead.
        val recoveryMarkerWritten = sealed && runCatching {
            if (readEvents(safeDirectory).none { it.eventType == "previous_run_recovered_uncleanly" }) {
                appendRecoveryEvent(safeDirectory, runId, finishedAt)
            }
        }.isSuccess
        val recoveredEvents = readEvents(safeDirectory)
        val reconstructed = countersFromEvents(safeDirectory, recoveredEvents).let {
            if (recoveryMarkerWritten) it else it.copy(writeFailures = it.writeFailures + 1)
        }
        val baseReport = RunDiagnosticAnalyzer.analyze(
            runId = runId,
            result = "ABORTED_UNCLEANLY",
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
            events = recoveredEvents,
            counters = reconstructed
        ).copy(
            conclusion = "上一次进程未留下正常结束标记。此报告由下次启动时恢复生成；异步队列及最近计数检查点可能不完整。" +
                if (recoveryMarkerWritten) "" else "分段封存或恢复标记写入失败，保留可读取的原始证据。"
        )
        val report = RunDiagnosticHistoryAnalyzer.enrich(
            baseReport,
            loadHistoricalIssueCodes(excludingRunId = runId)
        )
        writeReport(safeDirectory, report)
        writeDiagnosticManifest(safeDirectory, runId)
        writeMarker(
            JSONObject()
                .put("schemaVersion", 1)
                .put("active", false)
                .put("recoveredAfterProcessExit", true)
                .put("runId", runId)
                .put("startedAtEpochMs", startedAt)
                .put("finishedAtEpochMs", finishedAt)
                .put("result", "ABORTED_UNCLEANLY")
                .put("directory", runId)
        )
        pruneHistory(runId)
        return true
    }

    private fun loadHistoricalIssueCodes(excludingRunId: String): List<Set<String>> =
        runsDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != excludingRunId && File(it, "diagnostic_report.json").isFile }
            .sortedByDescending { File(it, "diagnostic_report.json").lastModified() }
            .take(HISTORY_LIMIT - 1)
            .mapNotNull { directory ->
                runCatching {
                    val report = JSONObject(File(directory, "diagnostic_report.json").readText(Charsets.UTF_8))
                    val issues = report.optJSONArray("issues") ?: JSONArray()
                    buildSet {
                        for (index in 0 until issues.length()) {
                            issues.optJSONObject(index)?.optString("code")
                                ?.takeIf(String::isNotBlank)
                                ?.let(::add)
                        }
                    }
                }.getOrNull()
            }

    override fun latestReportText(): String {
        val latest = runsDirectory.listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, "diagnostic_report.txt").isFile }
            .maxByOrNull { File(it, "diagnostic_report.txt").lastModified() }
            ?: return "暂无诊断报告。完成或停止一次运行任务后，将自动生成诊断报告。"
        val file = File(latest, "diagnostic_report.txt")
        return runCatching {
            RunLogBudgetPolicy.truncateUtf8(file.readText(Charsets.UTF_8), RunLogBudgetPolicy.MAX_DIAGNOSTIC_REPORT_BYTES)
        }.getOrDefault("诊断报告读取失败：${file.name}")
    }

    fun latestReportFile(): File? = runsDirectory.listFiles()
        .orEmpty()
        .filter { it.isDirectory && File(it, "diagnostic_report.txt").isFile }
        .maxByOrNull { File(it, "diagnostic_report.txt").lastModified() }
        ?.let { File(it, "diagnostic_report.txt") }

    private fun recordInternal(state: ActiveRun, event: RunDiagnosticEvent, allowDedup: Boolean) {
        synchronized(this) {
            if (activeRun !== state) return
            if (allowDedup) {
                val signature = "${event.level}|${event.category}|${event.eventType}|${event.taskId}|${event.traceId}|${event.actionId}|${event.windowId}|${event.pageGeneration}|${event.requestGeneration}|${event.result}|${event.reason}|${event.message}"
                val now = event.timestampEpochMs
                if (signature == state.lastSignature && now - state.lastEventAt in 0..DEDUP_WINDOW_MS) {
                    state.deduplicatedEvents++
                    state.pendingDuplicateCount++
                    state.pendingDuplicateType = event.eventType
                    state.lastEventAt = now
                    persistCountersSnapshot(state, force = state.deduplicatedEvents % 50L == 0L)
                    return
                }
                flushDuplicateSummary(state)
                state.lastSignature = signature
                state.lastEventAt = now
            }
            var detailBytesToReserve = 0L
            val eventWithDetail = event.errorDetail?.let { rawDetail ->
                val redactedDetail = RunLogBudgetPolicy.redactSensitivePayload(rawDetail)
                var detailBudget = minOf(
                    RunLogBudgetPolicy.utf8Size(redactedDetail),
                    RunLogBudgetPolicy.MAX_ERROR_DETAIL_BYTES - 1
                )
                var safeDetail = RunLogBudgetPolicy.truncateUtf8(redactedDetail, detailBudget)
                var detailLineBytes = errorDetailLine(event, safeDetail).toByteArray(Charsets.UTF_8).size.toLong() + 1L
                while (detailLineBytes > RunLogBudgetPolicy.MAX_ERROR_DETAIL_BYTES && detailBudget > 0) {
                    detailBudget = (detailBudget * 3 / 4).coerceAtLeast(0)
                    safeDetail = RunLogBudgetPolicy.truncateUtf8(redactedDetail, detailBudget)
                    detailLineBytes = errorDetailLine(event, safeDetail).toByteArray(Charsets.UTF_8).size.toLong() + 1L
                }
                if (state.reservedErrorDetailBytes + detailLineBytes > RunLogBudgetPolicy.MAX_ERROR_DETAILS_TOTAL_BYTES) {
                    state.errorDetailDroppedCount++
                    state.errorDetailDroppedBytes += RunLogBudgetPolicy.utf8Size(rawDetail).toLong()
                    event.copy(errorDetail = null)
                } else {
                    if (RunLogBudgetPolicy.utf8Size(safeDetail) < RunLogBudgetPolicy.utf8Size(rawDetail)) {
                        state.truncatedRecords++
                        val originalDetailBytes = RunLogBudgetPolicy.utf8Size(rawDetail).toLong()
                        val storedDetailBytes = RunLogBudgetPolicy.utf8Size(safeDetail).toLong()
                        state.truncatedBytes += (originalDetailBytes - storedDetailBytes).coerceAtLeast(0L)
                    }
                    detailBytesToReserve = detailLineBytes
                    event.copy(errorDetail = safeDetail)
                }
            } ?: event
            val boundedEvent = boundEventFields(eventWithDetail)
            val jsonLine = RunDiagnosticEventJsonCodec.encode(boundedEvent)
            val originalBytes = RunDiagnosticEventJsonCodec.encode(event).toByteArray(Charsets.UTF_8).size + 1
            val safeBytes = jsonLine.toByteArray(Charsets.UTF_8).size + 1
            state.reservedErrorDetailBytes += detailBytesToReserve
            state.acceptedEvents++
            if (event.level == RunLogLevel.ERROR || event.level == RunLogLevel.FATAL) state.errorEvents++
            if (event.level == RunLogLevel.WARN) state.warningEvents++
            if (safeBytes < originalBytes) {
                state.truncatedRecords++
                state.truncatedBytes += (originalBytes - safeBytes).toLong()
            }
            addRecent(state, boundedEvent)
            enqueueEvent(state, boundedEvent)
            if (state.acceptedEvents % 50L == 0L) persistCountersSnapshot(state, force = true)
        }
    }

    private fun flushDuplicateSummary(state: ActiveRun) {
        if (state.pendingDuplicateCount <= 0) return
        val count = state.pendingDuplicateCount
        val summary = RunDiagnosticEvent(
            sequence = state.nextSequence++,
            eventId = UUID.randomUUID().toString(),
            runId = state.runId,
            taskId = state.runId,
            traceId = state.runId,
            timestampEpochMs = state.lastEventAt,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            level = RunLogLevel.INFO,
            category = RunLogCategory.DATA_QUALITY,
            severitySource = RunLogSeveritySource.PRODUCER_DECLARED,
            component = "RunLogProcessor",
            eventType = "event_dedup_summary",
            message = "重复事件已提纯合并；type=${state.pendingDuplicateType}；repeatCount=$count；windowMs=$DEDUP_WINDOW_MS",
            result = "DEDUPLICATED"
        )
        state.pendingDuplicateCount = 0L
        state.pendingDuplicateType = ""
        state.acceptedEvents++
        addRecent(state, summary)
        enqueueEvent(state, summary)
    }

    private fun lane(event: RunDiagnosticEvent): RunEventRollingStore.Lane = when {
        event.eventType == "run_started" || event.eventType == "run_finished" ->
            RunEventRollingStore.Lane.LIFECYCLE
        event.level == RunLogLevel.ERROR || event.level == RunLogLevel.FATAL ->
            RunEventRollingStore.Lane.CRITICAL
        event.level == RunLogLevel.WARN -> RunEventRollingStore.Lane.WARNING
        else -> RunEventRollingStore.Lane.NORMAL
    }

    /** One bounded FIFO writer; reserve capacity for more severe events and the seal barrier. */
    private fun hasQueueRoom(lane: RunEventRollingStore.Lane): Boolean =
        RunEventRollingStore.canEnqueue(lane, writer.queue.remainingCapacity())

    private fun enqueueEvent(state: ActiveRun, event: RunDiagnosticEvent) {
        val task = Runnable {
            if (appendEventLine(state, event)) {
                event.errorDetail?.let { appendErrorDetailLine(state, event, it) }
            }
        }
        // All producers use the existing manager lock. Neither budget pressure nor a full
        // queue is allowed to run a file write on the accessibility/business caller.
        try {
            if (!hasQueueRoom(lane(event))) throw RejectedExecutionException("diagnostic queue reserve")
            writer.execute(task)
        } catch (_: RejectedExecutionException) {
            synchronized(this) {
                state.queueDroppedEvents++
                recordDroppedEvent(state, event)
            }
        }
    }

    private fun recordDroppedEvent(state: ActiveRun, event: RunDiagnosticEvent) {
        state.droppedEvents++
        state.droppedBytes += RunDiagnosticEventJsonCodec.encode(event).toByteArray(Charsets.UTF_8).size + 1L
        event.errorDetail?.let { detail ->
            state.errorDetailDroppedCount++
            state.errorDetailDroppedBytes += RunLogBudgetPolicy.utf8Size(detail)
            val reserved = errorDetailLine(event, detail).toByteArray(Charsets.UTF_8).size + 1L
            state.reservedErrorDetailBytes = (state.reservedErrorDetailBytes - reserved).coerceAtLeast(0L)
        }
    }

    private fun enqueueFinalizer(task: Runnable) {
        // The active run is already detached; no new run can enter while finalization is
        // pending. The reserved slot normally suffices. Only the finish scheduler may wait
        // for queue space, never execute the finalizer concurrently with the writer.
        try {
            writer.execute(task)
        } catch (_: RejectedExecutionException) {
            var interrupted = false
            while (true) {
                try {
                    writer.queue.put(task)
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun persistCountersSnapshot(state: ActiveRun, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - state.lastCounterPersistAtMs < 2_000L) return
        if (!hasQueueRoom(RunEventRollingStore.Lane.NORMAL)) return
        state.lastCounterPersistAtMs = now
        val snapshot = RunDiagnosticEventJsonCodec.counters(counters(state)).toString()
        try {
            // Never perform counter-file I/O on the accessibility callback/UI thread.
            writer.execute {
                runCatching {
                    writeCounterSnapshot(state.directory, snapshot)
                }.onFailure {
                    synchronized(this) { state.writeFailures++ }
                }
            }
        } catch (_: RejectedExecutionException) {
            // A deferred optional checkpoint is not a disk write failure.
            // The finalizer writes a fresh snapshot after draining the writer queue.
        }
    }

    private fun writeCounterSnapshot(directory: File, snapshot: String) {
        val temporary = File(directory, "diagnostic_counters.json.tmp")
        try {
            temporary.writeText(snapshot, Charsets.UTF_8)
            java.nio.file.Files.move(temporary.toPath(), File(directory, "diagnostic_counters.json").toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun appendEventLine(state: ActiveRun, event: RunDiagnosticEvent): Boolean {
        val line = RunDiagnosticEventJsonCodec.encode(boundEventFields(event))
        val bytes = line.toByteArray(Charsets.UTF_8).size.toLong() + 1L
        val outcome = state.rollingStore.append(lane(event), line)
        synchronized(this) {
            state.normalEvictedEvents += outcome.evictedRecords
            state.normalEvictedBytes += outcome.evictedBytes
            state.writtenEventBytes = (state.writtenEventBytes - outcome.evictedBytes).coerceAtLeast(0L)
            when (outcome.status) {
                RunEventRollingStore.Status.WRITTEN -> {
                    state.writtenEvents++
                    state.writtenEventBytes += bytes
                }
                RunEventRollingStore.Status.BUDGET_REJECTED -> {
                    state.budgetDroppedEvents++
                    recordDroppedEvent(state, event)
                }
                RunEventRollingStore.Status.WRITE_FAILED -> {
                    state.writeFailures++
                    recordDroppedEvent(state, event)
                }
            }
            if (outcome.evictedRecords > 0L || outcome.status != RunEventRollingStore.Status.WRITTEN) {
                // Called by the writer. The optional snapshot remains asynchronous and
                // is suppressed after detachment so it cannot follow the seal barrier.
                if (activeRun === state) persistCountersSnapshot(state)
            }
        }
        return outcome.status == RunEventRollingStore.Status.WRITTEN
    }

    private fun sealEvents(directory: File) {
        RunEventRollingStore.seal(directory) { line ->
            runCatching { JSONObject(line).getLong("sequence") }.getOrDefault(Long.MAX_VALUE)
        }
    }

    private fun errorDetailLine(event: RunDiagnosticEvent, detail: String): String = JSONObject()
        .put("sequence", event.sequence)
        .put("eventId", event.eventId)
        .put("capturedAtEpochMs", event.timestampEpochMs)
        .put("errorType", event.errorType ?: "UNKNOWN")
        .put("detail", detail)
        .toString()

    private fun appendErrorDetailLine(state: ActiveRun, event: RunDiagnosticEvent, detail: String) {
        val line = errorDetailLine(event, detail) + "\n"
        runCatching {
            File(state.directory, "errors.jsonl").appendText(line, Charsets.UTF_8)
            synchronized(this) { state.writtenErrorDetailBytes += line.toByteArray(Charsets.UTF_8).size.toLong() }
        }.onFailure {
            synchronized(this) {
                state.writeFailures++
                state.errorDetailDroppedCount++
                state.errorDetailDroppedBytes += RunLogBudgetPolicy.utf8Size(detail).toLong()
            }
        }
    }

    private fun writeReport(directory: File, report: RunDiagnosticReport) {
        val json = RunDiagnosticReportFormatter.toJson(report).toString(2)
        val boundedJson = if (RunLogBudgetPolicy.utf8Size(json) <= RunLogBudgetPolicy.MAX_DIAGNOSTIC_REPORT_BYTES) {
            json
        } else {
            JSONObject()
                .put("schemaVersion", 1)
                .put("runId", report.runId)
                .put("result", report.result)
                .put("conclusion", "诊断 JSON 达到容量上限；请查看同目录的精简 TXT 报告。")
                .put("issuesOmitted", report.issues.size)
                .toString(2)
        }
        File(directory, "diagnostic_report.json").writeText(boundedJson, Charsets.UTF_8)
        val text = RunDiagnosticReportFormatter.toText(report)
        File(directory, "diagnostic_report.txt").writeText(
            RunLogBudgetPolicy.truncateUtf8(text, RunLogBudgetPolicy.MAX_DIAGNOSTIC_REPORT_BYTES),
            Charsets.UTF_8
        )
        val summary = buildString {
            append("Freedom 任务诊断摘要\n")
            append("Run ID：").append(report.runId).append('\n')
            append("任务结果：").append(report.result).append('\n')
            append("问题数量：").append(report.issues.size).append('\n')
            append("结论：").append(report.conclusion)
        }
        File(directory, "diagnostic_summary.txt").writeText(
            RunLogBudgetPolicy.truncateUtf8(summary, RunLogBudgetPolicy.MAX_DIAGNOSTIC_SUMMARY_BYTES),
            Charsets.UTF_8
        )
    }

    /** File-level integrity manifest; it does not claim that every source event was captured. */
    private fun writeDiagnosticManifest(directory: File, runId: String) {
        val names = listOf(
            "events.jsonl",
            "errors.jsonl",
            "ai_context_diagnostics.jsonl",
            "ai_context_diagnostics_summary.json",
            "diagnostic_counters.json",
            "diagnostic_report.json",
            "diagnostic_report.txt",
            "diagnostic_summary.txt"
        )
        val files = JSONArray()
        (names.map { File(directory, it) } + RunEventRollingStore.sources(directory))
            .filter(File::isFile).distinctBy { it.absolutePath }.forEach { file ->
            files.put(JSONObject()
                .put("path", file.relativeTo(directory).invariantSeparatorsPath)
                .put("sizeBytes", file.length())
                .put("sha256", sha256(file)))
        }
        File(directory, "diagnostic_manifest.json").writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("runId", runId)
                .put("generatedAtEpochMs", System.currentTimeMillis())
                .put("integrityScope", "listed_files_only_not_event_capture_completeness")
                .put("byteBudgets", JSONObject()
                    .put("eventLogMaxBytes", RunLogBudgetPolicy.MAX_RUN_EVENT_BYTES + RunLogBudgetPolicy.MAX_CRITICAL_EVENT_EXTRA_BYTES)
                    .put("normalRollingMaxBytes", RunLogBudgetPolicy.MAX_NORMAL_EVENT_BYTES)
                    .put("normalSegmentMaxBytes", RunLogBudgetPolicy.NORMAL_EVENT_SEGMENT_BYTES)
                    .put("warningMaxBytes", RunLogBudgetPolicy.MAX_WARNING_EVENT_BYTES)
                    .put("criticalMaxBytes", RunLogBudgetPolicy.MAX_CRITICAL_EVENT_EXTRA_BYTES)
                    .put("lifecycleMaxBytes", RunLogBudgetPolicy.MAX_LIFECYCLE_EVENT_BYTES)
                    .put("eventRecordMaxBytes", RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES)
                    .put("errorDetailMaxBytesPerEntry", RunLogBudgetPolicy.MAX_ERROR_DETAIL_BYTES)
                    .put("errorDetailMaxBytesTotal", RunLogBudgetPolicy.MAX_ERROR_DETAILS_TOTAL_BYTES)
                    .put("diagnosticReportMaxBytes", RunLogBudgetPolicy.MAX_DIAGNOSTIC_REPORT_BYTES)
                    .put("diagnosticSummaryMaxBytes", RunLogBudgetPolicy.MAX_DIAGNOSTIC_SUMMARY_BYTES))
                .put("files", files)
                .toString(2),
            Charsets.UTF_8
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun counters(state: ActiveRun) = RunDiagnosticCounters(
        acceptedEvents = state.acceptedEvents,
        writtenEvents = state.writtenEvents,
        errorEvents = state.errorEvents,
        warningEvents = state.warningEvents,
        deduplicatedEvents = state.deduplicatedEvents,
        droppedEvents = state.droppedEvents,
        droppedBytes = state.droppedBytes,
        truncatedRecords = state.truncatedRecords,
        truncatedBytes = state.truncatedBytes,
        writeFailures = state.writeFailures,
        eventBytes = state.writtenEventBytes,
        errorDetailBytes = state.writtenErrorDetailBytes,
        errorDetailDroppedCount = state.errorDetailDroppedCount,
        errorDetailDroppedBytes = state.errorDetailDroppedBytes,
        normalEvictedEvents = state.normalEvictedEvents,
        normalEvictedBytes = state.normalEvictedBytes,
        retainedEvents = (state.writtenEvents - state.normalEvictedEvents).coerceAtLeast(0L),
        queueDroppedEvents = state.queueDroppedEvents,
        budgetDroppedEvents = state.budgetDroppedEvents
    )

    private fun countersFromEvents(directory: File, events: List<RunDiagnosticEvent>): RunDiagnosticCounters {
        val markerCounts = runCatching { JSONObject(File(directory, "diagnostic_counters.json").readText()) }.getOrNull()
        return RunDiagnosticCounters(
            acceptedEvents = maxOf(events.size.toLong(), markerCounts?.optLong("acceptedEvents") ?: 0L),
            writtenEvents = maxOf(events.size.toLong() + (markerCounts?.optLong("normalEvictedEvents") ?: 0L),
                markerCounts?.optLong("writtenEvents") ?: 0L),
            errorEvents = events.count(RunDiagnosticAnalyzer::isRuntimeFailureEvent).toLong(),
            warningEvents = events.count {
                it.level == RunLogLevel.WARN && !RunDiagnosticAnalyzer.isPreviousRunRecoveryEvent(it)
            }.toLong(),
            deduplicatedEvents = markerCounts?.optLong("deduplicatedEvents") ?: events.filter { it.eventType == "event_dedup_summary" }.sumOf { event ->
                Regex("repeatCount=(\\d+)").find(event.message)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            },
            droppedEvents = markerCounts?.optLong("droppedEvents") ?: 0L,
            droppedBytes = markerCounts?.optLong("droppedBytes") ?: 0L,
            truncatedRecords = markerCounts?.optLong("truncatedRecords") ?: 0L,
            truncatedBytes = markerCounts?.optLong("truncatedBytes") ?: 0L,
            writeFailures = markerCounts?.optLong("writeFailures") ?: 0L,
            eventBytes = RunEventRollingStore.sources(directory).sumOf(File::length),
            errorDetailBytes = File(directory, "errors.jsonl").length(),
            errorDetailDroppedCount = markerCounts?.optLong("errorDetailDroppedCount") ?: 0L,
            errorDetailDroppedBytes = markerCounts?.optLong("errorDetailDroppedBytes") ?: 0L,
            normalEvictedEvents = markerCounts?.optLong("normalEvictedEvents") ?: 0L,
            normalEvictedBytes = markerCounts?.optLong("normalEvictedBytes") ?: 0L,
            retainedEvents = events.size.toLong(),
            queueDroppedEvents = markerCounts?.optLong("queueDroppedEvents") ?: 0L,
            budgetDroppedEvents = markerCounts?.optLong("budgetDroppedEvents") ?: 0L
        )
    }

    private fun readEvents(directory: File): List<RunDiagnosticEvent> {
        val result = ArrayList<RunDiagnosticEvent>()
        RunEventRollingStore.sources(directory).forEach { file ->
            runCatching {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            runCatching { RunDiagnosticEventJsonCodec.decode(JSONObject(line)) }.getOrNull()?.let(result::add)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun boundEventFields(event: RunDiagnosticEvent): RunDiagnosticEvent {
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

    private fun appendRecoveryEvent(directory: File, runId: String, finishedAt: Long) {
        val event = RunDiagnosticEvent(
            sequence = (readEvents(directory).maxOfOrNull { it.sequence } ?: -1L) + 1L,
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            taskId = runId,
            traceId = runId,
            timestampEpochMs = finishedAt,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            level = RunLogLevel.WARN,
            category = RunLogCategory.LIFECYCLE,
            component = "ProcessRecovery",
            eventType = "previous_run_recovered_uncleanly",
            message = "本次启动识别并恢复上一次未正常收尾的运行记录；上一进程终止前尚在异步队列中的少量记录可能未落盘",
            result = "ABORTED_UNCLEANLY"
        )
        File(directory, "events.jsonl").appendText(RunDiagnosticEventJsonCodec.encode(event) + "\n", Charsets.UTF_8)
    }

    private fun appendCompletionSummary(report: RunDiagnosticReport) {
        // Add a compact summary to the existing monitor history without re-entering an active run.
        val highSeverity = report.issues.count { it.level == RunLogLevel.ERROR || it.level == RunLogLevel.FATAL }
        MonitorStore.addLog(
            appContext,
            "任务自诊断完成：runId=${report.runId}；result=${report.result}；" +
                "issues=${report.issues.size}；highSeverity=$highSeverity；" +
                "recurringPatterns=${report.historicalPatterns.size}；" +
                "deduplicated=${report.counters.deduplicatedEvents}；" +
                "dropped=${report.counters.droppedEvents}；report=diagnostic_report.txt"
        )
    }

    private fun writeMarker(json: JSONObject) {
        runCatching {
            val temporary = File(rootDirectory, "$MARKER_FILE.tmp")
            temporary.writeText(json.toString(), Charsets.UTF_8)
            if (!temporary.renameTo(markerFile)) {
                markerFile.writeText(json.toString(), Charsets.UTF_8)
                temporary.delete()
            }
        }
    }

    private fun pruneHistory(protectedRunId: String) {
        val directories = runsDirectory.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { File(it, "diagnostic_report.json").lastModified().takeIf { time -> time > 0L } ?: it.lastModified() }
        var remaining = directories.size
        var totalBytes = directories.sumOf { dir -> dir.walkTopDown().filter(File::isFile).sumOf(File::length) }
        for (directory in directories) {
            if (remaining <= HISTORY_LIMIT && totalBytes <= HISTORY_TOTAL_BYTES) break
            if (directory.name == protectedRunId || directory.name == activeRun?.runId ||
                directory.name == finalizingRun?.runId || !File(directory, "diagnostic_report.json").isFile) continue
            val size = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
            if (directory.deleteRecursively()) {
                remaining--
                totalBytes = (totalBytes - size).coerceAtLeast(0L)
            }
        }
    }

    private fun addRecent(state: ActiveRun, event: RunDiagnosticEvent) {
        state.recentEvents.addLast(event)
        while (state.recentEvents.size > MAX_RECENT_EVENTS) state.recentEvents.removeFirst()
    }
}
