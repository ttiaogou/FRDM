package com.freedom.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDiagnosticAnalyzerTest {
    @Test
    fun successfulOcrSummaryDoesNotCreateRuntimeErrorsPresentIssue() {
        val message = "13:28:49.314 收到招呼只读 OCR 采集：样本=1；读取状态=SUCCESS；" +
            "消息行=1；OCR目标=1；已识别=1；失败=0；截断=false；唯一身份=已验证；" +
            "未调用 AI 消息回调，不输入、不发送"
        val persistedEvent = RunLogBudgetPolicy.toEvent(
            message = message,
            runId = "RUN_OCR_SUCCESS",
            sequence = 1,
            timestampEpochMs = 1,
            elapsedRealtimeMs = 1
        )
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_OCR_SUCCESS",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 2,
            events = listOf(persistedEvent),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1)
        )

        assertEquals(RunLogLevel.INFO, persistedEvent.level)
        assertEquals(0L, report.counters.errorEvents)
        assertTrue(report.issues.none { it.code == "RUNTIME_ERRORS_PRESENT" })
        assertTrue(report.issues.none { it.code == "RUNTIME_WARNINGS_PRESENT" })
    }

    @Test
    fun identifiesPossibleCrossPageActionTraceGapWithoutClaimingConfirmedCause() {
        val events = listOf(
            event(1, 10_000, "tap", "点击动作已提交；actionId=ACTION_1" , actionId = "ACTION_1"),
            event(2, 12_000, "input", "输入来源分类：origin=UNKNOWN；evidence=NO_MATCHING_FREEDOM_ACTION")
        )
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_1",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 15_000,
            events = events,
            counters = RunDiagnosticCounters(acceptedEvents = 2, writtenEvents = 2)
        )

        val issue = report.issues.first { it.code == "POSSIBLE_CROSS_PAGE_ACTION_TRACE_GAP" }
        assertTrue(issue.description.contains("不能仅据此确认因果"))
        assertTrue(issue.evidenceSequences.containsAll(listOf(1L, 2L)))
    }

    @Test
    fun reportsMissingEvidenceWhenEventsWereDroppedOrTruncated() {
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_2",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 1,
            events = emptyList(),
            counters = RunDiagnosticCounters(droppedEvents = 2, droppedBytes = 300, truncatedRecords = 1, truncatedBytes = 80)
        )

        assertTrue(report.issues.any { it.code == "DIAGNOSTIC_DATA_INCOMPLETE" })
    }


    @Test
    fun archiveSummaryWithZeroCountersDoesNotCreateFalseQualityWarning() {
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_ARCHIVE_OK",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 2,
            events = listOf(
                event(
                    1,
                    1,
                    "sample_archive_summary",
                    "runtimeLogDropped=0/0 bytes；eventTimelineDropped=0/0 bytes；runtimeLogTruncated=0/0 bytes；writeFailures=0",
                ).copy(result = "SEALED")
            ),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1)
        )

        assertTrue(report.issues.none { it.code == "SAMPLE_ARCHIVE_DATA_QUALITY_WARNING" })
    }

    @Test
    fun typedInputOriginEvidenceTriggersUnresolvedOriginDiagnosis() {
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_INPUT",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 2,
            events = listOf(
                event(1, 1, "accessibility_event", "页面滚动事件").copy(
                    inputOrigin = "UNKNOWN",
                    inputOriginEvidence = "NO_MATCHING_FREEDOM_ACTION"
                )
            ),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1)
        )

        assertTrue(report.issues.any { it.code == "INPUT_ORIGIN_UNRESOLVED" })
    }

    @Test
    fun nonZeroArchiveDropCountTriggersQualityWarning() {
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_ARCHIVE_WARN",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 2,
            events = listOf(
                event(
                    1,
                    1,
                    "sample_archive_summary",
                    "runtimeLogDropped=0/0 bytes；eventTimelineDropped=2/120 bytes；runtimeLogTruncated=0/0 bytes；writeFailures=0",
                ).copy(result = "SEALED_WITH_WARNINGS", level = RunLogLevel.WARN)
            ),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1)
        )

        assertTrue(report.issues.any { it.code == "SAMPLE_ARCHIVE_DATA_QUALITY_WARNING" })
    }


    @Test
    fun missingErrorDetailsIsReportedAsIncompleteEvidence() {
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_ERROR_DETAIL",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 2,
            events = emptyList(),
            counters = RunDiagnosticCounters(errorDetailDroppedCount = 1, errorDetailDroppedBytes = 64)
        )

        assertTrue(report.issues.any { it.code == "DIAGNOSTIC_DATA_INCOMPLETE" })
    }

    @Test
    fun uncleanProcessExitIsReportedWithoutGuessingCrashCause() {
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_RECOVERY",
            result = "ABORTED_UNCLEANLY",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 2,
            events = emptyList(),
            counters = RunDiagnosticCounters()
        )

        val issue = report.issues.first { it.code == "RUN_INTERRUPTED_WITH_UNVERIFIED_QUEUE" }
        assertTrue(issue.description.contains("无法确认"))
        assertTrue(issue.description.contains("不完整"))
    }


    @Test
    fun callbackDrainTimeoutFalseIsNotTaskStepTimeout() {
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_GESTURE_OK",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 1,
            events = listOf(
                event(
                    1,
                    1,
                    "gesture_terminal",
                    "platformResult=COMPLETED；businessResult=COMPLETED；callbackAfterDrainTimeout=false"
                )
            ),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1)
        )

        assertTrue(report.issues.none { it.code == "TASK_STEP_TIMEOUT" })
    }

    @Test
    fun callbackDrainTimeoutTrueIsNotMisclassifiedAsTaskStepTimeout() {
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_GESTURE_DRAIN",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 1,
            events = listOf(
                event(1, 1, "gesture_terminal", "callbackAfterDrainTimeout=true")
            ),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1)
        )

        assertTrue(report.issues.none { it.code == "TASK_STEP_TIMEOUT" })
    }

    @Test
    fun explicitTaskStepTimeoutStillTriggersDiagnosis() {
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_STEP_TIMEOUT",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 1,
            events = listOf(
                event(1, 1, "task_step_timeout", "任务步骤超时；taskStepTimeout=true")
            ),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1)
        )

        assertTrue(report.issues.any { it.code == "TASK_STEP_TIMEOUT" })
    }

    @Test
    fun legacyProcessRecoveryErrorIsNotReportedAsCurrentRuntimeError() {
        val recovery = event(
            1,
            1,
            "process_exit_without_run_finish",
            "检测到上次运行未正常结束"
        ).copy(
            level = RunLogLevel.ERROR,
            category = RunLogCategory.LIFECYCLE,
            component = "ProcessRecovery",
            result = "ABORTED_UNCLEANLY"
        )
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_RECOVERY_LEGACY",
            result = "ABORTED_UNCLEANLY",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 1,
            events = listOf(recovery),
            counters = RunDiagnosticCounters(errorEvents = 1, acceptedEvents = 1, writtenEvents = 1)
        )

        assertEquals(0L, report.counters.errorEvents)
        assertTrue(report.issues.none { it.code == "RUNTIME_ERRORS_PRESENT" })
        val interruption = report.issues.first { it.code == "RUN_INTERRUPTED_WITH_UNVERIFIED_QUEUE" }
        assertEquals(RunLogLevel.WARN, interruption.level)
    }

    @Test
    fun currentRecoveryMarkerProducesOnlyDedicatedPreviousRunWarning() {
        val recovery = event(
            1,
            1,
            "previous_run_recovered_uncleanly",
            "本次启动识别并恢复上一次未正常收尾的运行记录"
        ).copy(
            level = RunLogLevel.WARN,
            category = RunLogCategory.LIFECYCLE,
            component = "ProcessRecovery",
            result = "ABORTED_UNCLEANLY"
        )
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_RECOVERY_CURRENT_MARKER",
            result = "ABORTED_UNCLEANLY",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 1,
            events = listOf(recovery),
            counters = RunDiagnosticCounters(warningEvents = 1, acceptedEvents = 1, writtenEvents = 1)
        )

        assertTrue(report.issues.any { it.code == "RUN_INTERRUPTED_WITH_UNVERIFIED_QUEUE" })
        assertTrue(report.issues.none { it.code == "RUNTIME_WARNINGS_PRESENT" })
        assertTrue(report.issues.none { it.code == "RUNTIME_ERRORS_PRESENT" })
        assertEquals(0L, report.counters.errorEvents)
        assertEquals(0L, report.counters.warningEvents)
    }

    @Test
    fun errorCounterIsReconciledWithPersistedRuntimeErrorEvidence() {
        val actualError = event(1, 1, "runtime_failure", "节点检索失败").copy(
            level = RunLogLevel.ERROR,
            category = RunLogCategory.ERROR
        )
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_COUNTER_RECONCILE",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 1,
            events = listOf(actualError),
            counters = RunDiagnosticCounters(errorEvents = 0, acceptedEvents = 1, writtenEvents = 1)
        )

        assertEquals(1L, report.counters.errorEvents)
        assertTrue(report.issues.any { it.code == "RUNTIME_ERRORS_PRESENT" })
    }


    @Test
    fun legacyObservationQualityErrorsAreDowngradedToWarnings() {
        val directionUnknown = event(
            1, 1, "chat_read", "AI 模式消息读取：状态=DIRECTION_UNKNOWN；方向不明=7；OCR失败区域=0"
        ).copy(level = RunLogLevel.ERROR, category = RunLogCategory.ERROR, result = "FAILED_OR_EXCEPTION")
        val partialOcr = event(
            2, 2, "chat_read", "AI 模式消息读取：状态=OCR_PARTIAL；方向不明=6；OCR失败区域=1"
        ).copy(level = RunLogLevel.ERROR, category = RunLogCategory.ERROR, result = "FAILED_OR_EXCEPTION")
        val safePause = event(
            3, 3, "navigation_pause", "收到的招呼导航暂停：异步页面切换等待失败或超时；不把中间页面当作消息列表"
        ).copy(level = RunLogLevel.ERROR, category = RunLogCategory.ERROR, result = "FAILED_OR_EXCEPTION")

        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_LEGACY_FALSE_ERRORS",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 4,
            events = listOf(directionUnknown, partialOcr, safePause),
            counters = RunDiagnosticCounters(
                acceptedEvents = 3,
                writtenEvents = 3,
                errorEvents = 3
            )
        )

        assertEquals(0L, report.counters.errorEvents)
        assertEquals(3L, report.counters.warningEvents)
        assertTrue(report.issues.none { it.code == "RUNTIME_ERRORS_PRESENT" })
        assertTrue(report.issues.any { it.code == "MESSAGE_DIRECTION_UNRESOLVED" && it.level == RunLogLevel.WARN })
        assertTrue(report.issues.any { it.code == "OCR_PARTIAL_READ" && it.level == RunLogLevel.WARN })
        assertTrue(report.issues.any { it.code == "NAVIGATION_PAUSED_AFTER_WAIT_TIMEOUT" && it.level == RunLogLevel.WARN })
    }


    @Test
    fun legacyIdentityObserveWaitingErrorIsRemovedFromErrorAndWarningCounts() {
        val waitingText = "AI 联系人身份采集[OBSERVE_WAITING]：事务仍在进行；不返回失败原因"
        val legacy = RunLogBudgetPolicy.toEvent(waitingText, "RUN_LEGACY_WAIT", 1, 1, 1)
            .copy(level = RunLogLevel.ERROR, category = RunLogCategory.ERROR, result = "FAILED_OR_EXCEPTION")
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_LEGACY_WAIT",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 3,
            events = listOf(legacy),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1, errorEvents = 1)
        )
        assertEquals(0L, report.counters.errorEvents)
        assertEquals(0L, report.counters.warningEvents)
        assertTrue(report.issues.none { it.code == "RUNTIME_ERRORS_PRESENT" })
        assertTrue(report.issues.none { it.code == "RUNTIME_WARNINGS_PRESENT" })
    }

    @Test
    fun identityWaitClassificationKeepsActualErrorsAndQualityWarnings() {
        val waitingText = "AI 联系人身份采集[OBSERVE_WAITING]：事务仍在进行；不返回失败原因"
        val legacy = RunLogBudgetPolicy.toEvent(waitingText, "RUN_MIXED", 1, 1, 1)
            .copy(level = RunLogLevel.ERROR, category = RunLogCategory.ERROR, result = "FAILED_OR_EXCEPTION")
        val exception = RunLogBudgetPolicy.toEvent(
            "$waitingText；读取失败：IllegalStateException", "RUN_MIXED", 2, 2, 2
        )
        val warning = RunLogBudgetPolicy.toEvent(
            "AI 模式消息读取：状态=OCR_PARTIAL；OCR失败区域=1", "RUN_MIXED", 3, 3, 3
        )
        val explicit = legacy.copy(sequence = 4, errorType = "IllegalStateException")
        assertTrue(RunDiagnosticAnalyzer.isRuntimeFailureEvent(explicit))
        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_MIXED",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 5,
            events = listOf(legacy, exception, warning),
            counters = RunDiagnosticCounters(acceptedEvents = 3, writtenEvents = 3, errorEvents = 2, warningEvents = 1)
        )
        assertEquals(1L, report.counters.errorEvents)
        assertEquals(1L, report.counters.warningEvents)
        assertEquals(listOf(2L), report.issues.first { it.code == "RUNTIME_ERRORS_PRESENT" }.evidenceSequences)
        assertTrue(report.issues.any { it.code == "OCR_PARTIAL_READ" })
    }

    @Test
    fun t05LegacyRecoveryTransitionDoesNotCountAsErrorButTypedFailureDoes() {
        val legacy = event(1, 1, "private_recovery",
            "AI 私聊异常会话[RELOCATION_ORIGIN_VERIFIED]：已重新找到目标或确认已读边界；保留未解决身份"
        ).copy(level = RunLogLevel.ERROR, result = "FAILED_OR_EXCEPTION")
        val quality = event(2, 2, "private_recovery",
            "AI 私聊异常会话[INCOMPLETE_UNREAD_RETRYABLE]：unresolved=1；cacheWrite=false；保持检查点"
        ).copy(level = RunLogLevel.ERROR, result = "FAILED_OR_EXCEPTION")
        val actual = quality.copy(sequence = 3, errorType = "IllegalStateException")
        val detail = legacy.copy(sequence = 4, errorDetail = "actual failure")
        val report = RunDiagnosticAnalyzer.analyze("RUN_T05", "STOPPED", 0, 5,
            listOf(legacy, quality, actual, detail), RunDiagnosticCounters(errorEvents = 4))
        assertEquals(2L, report.counters.errorEvents)
        assertEquals(1L, report.counters.warningEvents)
        assertEquals(listOf(3L, 4L), report.issues.first { it.code == "RUNTIME_ERRORS_PRESENT" }.evidenceSequences)
    }

    @Test
    fun zeroDirectionUnknownSummaryDoesNotCreateFalseDirectionIssue() {
        val warningSummary = event(
            1, 1, "chat_batch_summary", "AI 模式消息批次摘要：状态=WARN；方向不明=0；OCR失败区域=0"
        ).copy(level = RunLogLevel.WARN, category = RunLogCategory.DATA_QUALITY)

        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_DIRECTION_ZERO",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 2,
            events = listOf(warningSummary),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1, warningEvents = 1)
        )

        assertTrue(report.issues.none { it.code == "MESSAGE_DIRECTION_UNRESOLVED" })
    }

    @Test
    fun nonZeroDirectionUnknownSummaryUsesActualMessageCount() {
        val warningSummary = event(
            1, 1, "chat_batch_summary", "AI 模式消息批次摘要：状态=WARN；方向不明=3；OCR失败区域=0"
        ).copy(level = RunLogLevel.WARN, category = RunLogCategory.DATA_QUALITY)

        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_DIRECTION_THREE",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 2,
            events = listOf(warningSummary),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1, warningEvents = 1)
        )

        val issue = report.issues.first { it.code == "MESSAGE_DIRECTION_UNRESOLVED" }
        assertTrue(issue.description.contains("检测到 3 条消息方向不明"))
        assertEquals(listOf(1L), issue.evidenceSequences)
    }

    @Test
    fun explicitZeroDirectionCountOverridesUnknownStatusToken() {
        val warning = event(
            1, 1, "chat_batch_summary", "AI 模式消息读取：状态=DIRECTION_UNKNOWN；方向不明=0"
        ).copy(level = RunLogLevel.WARN)

        val report = RunDiagnosticAnalyzer.analyze(
            runId = "RUN_DIRECTION_ZERO_OVERRIDE",
            result = "STOPPED",
            startedAtEpochMs = 0,
            finishedAtEpochMs = 2,
            events = listOf(warning),
            counters = RunDiagnosticCounters(acceptedEvents = 1, writtenEvents = 1, warningEvents = 1)
        )

        assertTrue(report.issues.none { it.code == "MESSAGE_DIRECTION_UNRESOLVED" })
    }

    @Test
    fun l11bProducerSeverityIsAuthoritativeAndLegacyUnknownSendOriginIsNotInput() {
        val normal = RunDiagnosticInput(
            "MODEL_REQUEST_FINISHED", RunLogLevel.INFO, RunLogCategory.TASK,
            "AiModel", "origin=UNKNOWN; error=0; sendAttemptOrigin=UNKNOWN",
            taskId = "AI_REPLY_PRIVATE_MESSAGES", traceId = "request-1", result = "SUCCEEDED",
            requestGeneration = 7
        ).toEvent("RUN", 1, 1, 1)
        val declaredFailure = RunDiagnosticInput(
            "explicit_failure", RunLogLevel.ERROR, RunLogCategory.DATA_QUALITY,
            "Test", "AI 模式消息读取：状态=OCR_PARTIAL；OCR失败区域=1"
        ).toEvent("RUN", 2, 2, 2)
        val legacySend = event(3, 3, "MESSAGE_SEND_COMPLETED", "sendAttemptOrigin=UNKNOWN")
        assertEquals(RunLogSeveritySource.PRODUCER_DECLARED, normal.severitySource)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", normal.taskId)
        assertEquals(7L, normal.requestGeneration)
        assertTrue(RunDiagnosticAnalyzer.isRuntimeFailureEvent(declaredFailure))
        val report = RunDiagnosticAnalyzer.analyze("RUN", "STOPPED", 0, 4,
            listOf(normal, declaredFailure, legacySend), RunDiagnosticCounters(errorEvents = 1))
        assertEquals(1L, report.counters.errorEvents)
        assertEquals(0L, report.counters.warningEvents)
        assertTrue(report.issues.none { it.code == "INPUT_ORIGIN_UNRESOLVED" ||
            it.code == "POSSIBLE_CROSS_PAGE_ACTION_TRACE_GAP" })
    }

    @Test
    fun l11bDeclaredOcrRequestIsNotCrossPageGestureEvidence() {
        val ocr = RunDiagnosticInput(
            "ocr_plugin_diagnostic", RunLogLevel.INFO, RunLogCategory.COLLECTION,
            "OcrPlugin", "OCR request", actionId = "OCR-1"
        ).toEvent("RUN", 1, 100, 100)
        val input = RunDiagnosticInput(
            "accessibility_event", RunLogLevel.WARN, RunLogCategory.INPUT,
            "InputClassifier", "source unknown", inputOrigin = "UNKNOWN"
        ).toEvent("RUN", 2, 200, 200)
        val report = RunDiagnosticAnalyzer.analyze("RUN", "STOPPED", 0, 300,
            listOf(ocr, input), RunDiagnosticCounters(warningEvents = 1))
        assertTrue(report.issues.any { it.code == "INPUT_ORIGIN_UNRESOLVED" })
        assertTrue(report.issues.none { it.code == "POSSIBLE_CROSS_PAGE_ACTION_TRACE_GAP" })
    }

    @Test
    fun l11cIntentionalNormalRotationIsNotDiagnosticDataLoss() {
        val report = RunDiagnosticAnalyzer.analyze("RUN", "STOPPED", 0, 10,
            emptyList(), RunDiagnosticCounters(acceptedEvents = 100, writtenEvents = 100,
                normalEvictedEvents = 90, normalEvictedBytes = 9000, retainedEvents = 10))
        assertTrue(report.issues.none { it.code == "DIAGNOSTIC_DATA_INCOMPLETE" })
        assertEquals(90L, report.counters.normalEvictedEvents)
        val lost = RunDiagnosticAnalyzer.analyze("RUN", "STOPPED", 0, 10,
            emptyList(), RunDiagnosticCounters(droppedEvents = 1, queueDroppedEvents = 1))
        assertTrue(lost.issues.any { it.code == "DIAGNOSTIC_DATA_INCOMPLETE" })
    }

    @Test
    fun l11dIncidentEvidenceLimitIsReportedSeparatelyFromNormalRotation() {
        val report = RunDiagnosticAnalyzer.analyze("RUN", "STOPPED", 0, 10,
            emptyList(), RunDiagnosticCounters(normalEvictedEvents = 100,
                incidentCaptured = 16, incidentRejected = 1, incidentPostLimited = 2))
        assertTrue(report.issues.any { it.code == "INCIDENT_EVIDENCE_INCOMPLETE" })
        assertTrue(report.issues.none { it.code == "DIAGNOSTIC_DATA_INCOMPLETE" })
    }

    private fun event(
        sequence: Long,
        timestamp: Long,
        eventType: String,
        message: String,
        actionId: String? = null
    ) = RunDiagnosticEvent(
        sequence = sequence,
        eventId = "EVENT_$sequence",
        runId = "RUN_1",
        taskId = "RUN_1",
        traceId = actionId ?: "RUN_1",
        timestampEpochMs = timestamp,
        elapsedRealtimeMs = timestamp,
        level = RunLogLevel.INFO,
        category = RunLogCategory.INPUT,
        component = "Test",
        eventType = eventType,
        message = message,
        actionId = actionId
    )
}
