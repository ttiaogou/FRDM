package com.freedom.core.logging

/** Deterministic, evidence-linked first-pass diagnosis; it does not invent unverified root causes. */
object RunDiagnosticAnalyzer {
    fun analyze(
        runId: String,
        result: String,
        startedAtEpochMs: Long,
        finishedAtEpochMs: Long,
        events: List<RunDiagnosticEvent>,
        counters: RunDiagnosticCounters
    ): RunDiagnosticReport {
        val issues = mutableListOf<RunDiagnosticIssue>()
        val sorted = events.sortedBy(RunDiagnosticEvent::sequence)
        val recoveryEvents = sorted.filter(::isPreviousRunRecoveryEvent)
        val recoveryErrorCount = recoveryEvents.count {
            it.level == RunLogLevel.ERROR || it.level == RunLogLevel.FATAL
        }.toLong()
        val recoveryWarningCount = recoveryEvents.count { it.level == RunLogLevel.WARN }.toLong()
        val persistedErrorLevelEvents = sorted.filter {
            !isPreviousRunRecoveryEvent(it) &&
                (it.level == RunLogLevel.ERROR || it.level == RunLogLevel.FATAL)
        }
        // Older releases promoted observation-quality states to ERROR using broad keyword
        // matching. Reclassify those known non-fatal states from their message evidence so an
        // old run is not permanently reported as a runtime failure.
        val errorEvents = persistedErrorLevelEvents.filter(::isRuntimeFailureEvent)
        val downgradedQualityEvents = persistedErrorLevelEvents.filterNot(::isRuntimeFailureEvent)
            .filterNot(::isLegacyBenignObservation)
        val persistedWarningEvents = sorted.filter {
            it.level == RunLogLevel.WARN && !isPreviousRunRecoveryEvent(it)
        }
        val warningEvents = persistedWarningEvents + downgradedQualityEvents
        // Counters can contain events not present in the persisted event list. Only the residual
        // count is retained as an unclassified failure/warning; represented events are counted
        // from their effective severity to avoid counting legacy false-positive ERROR records.
        val unrepresentedErrorCount = (
            counters.errorEvents - recoveryErrorCount - persistedErrorLevelEvents.size.toLong()
            ).coerceAtLeast(0L)
        val observedErrorCount = errorEvents.size.toLong() + unrepresentedErrorCount
        val unrepresentedWarningCount = (
            counters.warningEvents - recoveryWarningCount - persistedWarningEvents.size.toLong()
            ).coerceAtLeast(0L)
        val observedWarningCount = warningEvents.size.toLong() + unrepresentedWarningCount
        val reportCounters = counters.copy(
            errorEvents = observedErrorCount,
            warningEvents = observedWarningCount
        )

        if (observedErrorCount > 0L) {
            issues += RunDiagnosticIssue(
                code = "RUNTIME_ERRORS_PRESENT",
                level = if (errorEvents.any { it.level == RunLogLevel.FATAL }) RunLogLevel.FATAL else RunLogLevel.ERROR,
                title = "运行期间出现异常或失败",
                description = "该运行记录确认 $observedErrorCount 条错误级记录，其中 ${errorEvents.size} 条具有可引用事件证据；计数已排除上一次运行的 ProcessRecovery 恢复标记。具体根因需结合错误详情证据确认。",
                evidenceSequences = errorEvents.take(20).map { it.sequence },
                suggestedAction = "优先检查错误记录对应的模块、失败步骤和异常类型；确认原因后再决定是否重试。"
            )
        }

        val directionUnknownEvents = warningEvents.filter(::isMessageDirectionUnresolvedEvent)
        val directionUnknownMessageCount = directionUnknownEvents.sumOf(::messageDirectionUnknownCount)
        if (directionUnknownMessageCount > 0L) {
            issues += RunDiagnosticIssue(
                code = "MESSAGE_DIRECTION_UNRESOLVED",
                level = RunLogLevel.WARN,
                title = "部分消息发送方向尚未确认",
                description = "检测到 $directionUnknownMessageCount 条消息方向不明，来自 ${directionUnknownEvents.size} 条诊断记录。该情况属于消息数据质量/证据不足，不等同于程序运行异常；对应消息不得进入 AI 输入。",
                evidenceSequences = directionUnknownEvents.take(20).map { it.sequence },
                suggestedAction = "检查同一 message_root 行内的左右布局容器、头像证据和 OCR 回退方向继承；只有方向证据明确后才允许进入 AI 上下文。"
            )
        }

        val partialOcrEvents = warningEvents.filter(::isPartialOcrEvent)
        if (partialOcrEvents.isNotEmpty()) {
            issues += RunDiagnosticIssue(
                code = "OCR_PARTIAL_READ",
                level = RunLogLevel.WARN,
                title = "部分 OCR 目标未成功识别",
                description = "检测到 ${partialOcrEvents.size} 条 OCR 部分识别记录。已识别的文字仍属于单引擎未验证内容；未识别区域不应被视为已读取。",
                evidenceSequences = partialOcrEvents.take(20).map { it.sequence },
                suggestedAction = "检查失败目标的截图裁剪区域、OCR 返回文本框和坐标映射；重新采集或人工复核，不要把部分识别标记成完整读取。"
            )
        }

        val safeNavigationPauseEvents = warningEvents.filter(::isSafeNavigationPauseEvent)
        if (safeNavigationPauseEvents.isNotEmpty()) {
            issues += RunDiagnosticIssue(
                code = "NAVIGATION_PAUSED_AFTER_WAIT_TIMEOUT",
                level = RunLogLevel.WARN,
                title = "导航等待超时后安全暂停",
                description = "检测到 ${safeNavigationPauseEvents.size} 条导航等待超时并暂停的记录。暂停本身不是未捕获程序异常，但当前业务步骤尚未完成。",
                evidenceSequences = safeNavigationPauseEvents.take(20).map { it.sequence },
                suggestedAction = "确认当前 Activity 和页面状态，页面稳定后再恢复导航；不要将中间页面当作目标列表继续操作。"
            )
        }

        if (counters.deduplicatedEvents > 0) {
            issues += RunDiagnosticIssue(
                code = "EVENT_BURST_DEDUPLICATED",
                level = RunLogLevel.WARN,
                title = "检测到重复事件密集发生",
                description = "运行期间合并了 ${counters.deduplicatedEvents} 次重复事件。提纯减少了重复输出，但重复量仍可用于判断页面事件风暴。",
                evidenceSequences = sorted.filter { it.eventType == "event_dedup_summary" }.map { it.sequence }.take(20),
                suggestedAction = "检查对应页面的内容变化频率、事件去重窗口和页面稳定判定是否反复重置。"
            )
        }

        if (counters.droppedEvents > 0 || counters.truncatedRecords > 0 || counters.writeFailures > 0 ||
            counters.errorDetailDroppedCount > 0
        ) {
            issues += RunDiagnosticIssue(
                code = "DIAGNOSTIC_DATA_INCOMPLETE",
                level = RunLogLevel.ERROR,
                title = "诊断记录存在缺失或截断",
                description = "丢弃 ${counters.droppedEvents} 条事件（${counters.droppedBytes} 字节），截断 ${counters.truncatedRecords} 条（${counters.truncatedBytes} 字节），写入失败 ${counters.writeFailures} 次；另有 ${counters.errorDetailDroppedCount} 条异常详情未保存（${counters.errorDetailDroppedBytes} 字节）。报告不能宣称记录完整。",
                evidenceSequences = emptyList(),
                suggestedAction = "检查容量预算、队列压力和文件写入状态；必要时降低普通事件采样率并优先保留状态变化与异常证据。"
            )
        }

        val unknownOriginEvents = sorted.filter {
            val text = it.message.uppercase()
            it.inputOrigin.equals("UNKNOWN", ignoreCase = true) ||
                it.inputOriginEvidence.equals("NO_MATCHING_FREEDOM_ACTION", ignoreCase = true) ||
                (it.severitySource == RunLogSeveritySource.LEGACY_INFERRED &&
                    !it.eventType.startsWith("MODEL_") && !it.eventType.startsWith("MESSAGE_SEND_") &&
                    (Regex("(?:^|[;；\\s])(?:INPUTORIGIN|ORIGIN)=UNKNOWN(?:$|[;；\\s])").containsMatchIn(text) ||
                        text.contains("NO_MATCHING_FREEDOM_ACTION")))
        }
        if (unknownOriginEvents.isNotEmpty()) {
            val relatedActionEvents = sorted.filter {
                it.actionId != null &&
                    (it.severitySource == RunLogSeveritySource.LEGACY_INFERRED ||
                        it.category in setOf(RunLogCategory.INPUT, RunLogCategory.GESTURE, RunLogCategory.NAVIGATION))
            }
            val evidence = (unknownOriginEvents.take(10) + relatedActionEvents.takeLast(10))
                .map { it.sequence }.distinct().sorted()
            val crossWindowHint = unknownOriginEvents.any { unknown ->
                relatedActionEvents.any { action ->
                    action.sequence < unknown.sequence &&
                        unknown.timestampEpochMs - action.timestampEpochMs in 0..15_000
                }
            }
            issues += RunDiagnosticIssue(
                code = if (crossWindowHint) "POSSIBLE_CROSS_PAGE_ACTION_TRACE_GAP" else "INPUT_ORIGIN_UNRESOLVED",
                level = RunLogLevel.WARN,
                title = if (crossWindowHint) "疑似存在跨页面动作关联缺口" else "存在无法确认来源的交互事件",
                description = if (crossWindowHint) {
                    "UNKNOWN 交互前 15 秒内存在带 actionId 的动作记录，时间上相关但不能仅据此确认因果；可能需要补充跨窗口/Activity 动作上下文。"
                } else {
                    "检测到 ${unknownOriginEvents.size} 条来源不确定记录，当前证据不足以判定为人工操作或 Freedom 自主操作。"
                },
                evidenceSequences = evidence,
                suggestedAction = "核对前序手势/点击动作、windowId、Activity 变化、actionId 和页面代次；证据不足时继续保持保守仲裁。"
            )
        }

        val sampleArchiveWarnings = sorted.filter { event ->
            if (event.eventType != "sample_archive_summary") return@filter false
            val hasDroppedRecords = Regex("(?:runtimeLogDropped|eventTimelineDropped)=[1-9]\\d*")
                .containsMatchIn(event.message)
            val hasTruncatedRecords = Regex("runtimeLogTruncated=[1-9]\\d*")
                .containsMatchIn(event.message)
            val hasWriteFailures = Regex("writeFailures=[1-9]\\d*")
                .containsMatchIn(event.message)
            hasDroppedRecords || hasTruncatedRecords || hasWriteFailures || event.result == "SEALED_WITH_WARNINGS"
        }
        if (sampleArchiveWarnings.isNotEmpty()) {
            issues += RunDiagnosticIssue(
                code = "SAMPLE_ARCHIVE_DATA_QUALITY_WARNING",
                level = if (sampleArchiveWarnings.any { it.level == RunLogLevel.ERROR }) RunLogLevel.ERROR else RunLogLevel.WARN,
                title = "采集归档存在数据质量告警",
                description = sampleArchiveWarnings.take(3).joinToString("；") { it.message.take(700) },
                evidenceSequences = sampleArchiveWarnings.take(10).map { it.sequence },
                suggestedAction = "检查采集归档清单中的 captureWarnings、丢弃数量和写入失败数量；不要仅凭 ZIP 校验成功判断采集内容完整。"
            )
        }

        if (warningEvents.isNotEmpty() && issues.none { it.level == RunLogLevel.WARN || it.level == RunLogLevel.ERROR || it.level == RunLogLevel.FATAL }) {
            issues += RunDiagnosticIssue(
                code = "RUNTIME_WARNINGS_PRESENT",
                level = RunLogLevel.WARN,
                title = "运行期间存在需要复核的警告",
                description = "检测到 ${maxOf(warningEvents.size.toLong(), counters.warningEvents)} 条警告级记录，但没有匹配到更具体的诊断规则。",
                evidenceSequences = warningEvents.take(20).map { it.sequence },
                suggestedAction = "查看对应事件上下文，判断是否影响任务步骤或采集数据质量。"
            )
        }

        val timeouts = sorted.filter(::isConfirmedTaskStepTimeout)
        if (timeouts.isNotEmpty()) {
            issues += RunDiagnosticIssue(
                code = "TASK_STEP_TIMEOUT",
                level = RunLogLevel.WARN,
                title = "任务步骤出现超时迹象",
                description = "检测到 ${timeouts.size} 条超时相关记录；需结合对应步骤的开始记录和页面状态确认影响。",
                evidenceSequences = timeouts.take(20).map { it.sequence },
                suggestedAction = "检查该步骤的等待条件、页面是否稳定以及节点检索是否达到重试上限；不要盲目延长超时。"
            )
        }

        if (result == "ABORTED_UNCLEANLY") {
            issues += RunDiagnosticIssue(
                code = "RUN_INTERRUPTED_WITH_UNVERIFIED_QUEUE",
                level = RunLogLevel.WARN,
                title = "上一次运行未正常收尾（由本次启动恢复识别）",
                description = "检测到进程退出时运行标记仍处于活动状态。异步写入队列中尚未落盘的记录数量无法确认，因此本次诊断证据可能不完整。",
                evidenceSequences = sorted.takeLast(8).map { it.sequence },
                suggestedAction = "检查系统回收、进程崩溃和服务重建记录；恢复任务前重新读取页面状态，不要直接重放最后一个动作。"
            )
        }

        if (result != "COMPLETED" && result != "STOPPED" && result != "COMPLETED_WITH_WARNINGS" &&
            result != "ABORTED_UNCLEANLY"
        ) {
            issues += RunDiagnosticIssue(
                code = "RUN_DID_NOT_FINISH_NORMALLY",
                level = RunLogLevel.ERROR,
                title = "任务未以正常完成状态结束",
                description = "本次运行结果为 $result。若没有 run_finished 事件，可能是用户停止、服务中断或进程未正常收尾；无法仅凭状态区分具体原因。",
                evidenceSequences = sorted.takeLast(12).map { it.sequence },
                suggestedAction = "检查最后一条成功状态、服务生命周期记录和上次运行检查点；恢复前重新确认页面与任务检查点。"
            )
        }

        if (issues.isEmpty() && warningEvents.isEmpty() && result != "ABORTED_UNCLEANLY") {
            issues += RunDiagnosticIssue(
                code = "NO_RULE_BASED_ISSUE_FOUND",
                level = RunLogLevel.INFO,
                title = "未发现已配置规则能够确认的问题",
                description = "本次诊断未触发已配置异常规则。这不等于业务结果绝对正确，也不代表未采集到的数据不存在。",
                evidenceSequences = sorted.takeLast(8).map { it.sequence },
                suggestedAction = "结合采集数量、业务验收条件和原始证据复核结果。"
            )
        }

        val conclusion = when {
            issues.any { it.level == RunLogLevel.FATAL || it.level == RunLogLevel.ERROR } ->
                "本次运行存在错误或诊断数据不完整，需要检查后再决定是否恢复。"
            issues.any { it.level == RunLogLevel.WARN } ->
                "本次运行存在需要关注的不确定项或质量告警，建议复核证据。"
            else -> "当前规则未发现明显异常，仍需结合业务数据完整性进行验收。"
        }
        return RunDiagnosticReport(
            runId = runId,
            taskId = runId,
            result = result,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            durationMs = (finishedAtEpochMs - startedAtEpochMs).coerceAtLeast(0),
            counters = reportCounters,
            issues = issues,
            conclusion = conclusion
        )
    }

    /** Recovery markers describe the previous process/run, never a failure in the new run. */
    fun isPreviousRunRecoveryEvent(event: RunDiagnosticEvent): Boolean =
        event.component.equals("ProcessRecovery", ignoreCase = true) ||
            event.eventType.equals("process_exit_without_run_finish", ignoreCase = true) ||
            event.eventType.equals("previous_run_recovered_uncleanly", ignoreCase = true)

    /** Legacy free-text ERROR events from a normal identity wait are informational, not warnings. */
    private fun isLegacyBenignIdentityWait(event: RunDiagnosticEvent): Boolean =
        event.level == RunLogLevel.ERROR &&
            event.result == "FAILED_OR_EXCEPTION" &&
            event.errorType.isNullOrBlank() && event.errorDetail.isNullOrBlank() &&
            RunLogBudgetPolicy.isBenignIdentityObservationWait(event.message)

    private fun isLegacyBenignObservation(event: RunDiagnosticEvent): Boolean =
        isLegacyBenignIdentityWait(event) ||
            (event.level == RunLogLevel.ERROR && event.result == "FAILED_OR_EXCEPTION" &&
                event.errorType.isNullOrBlank() && event.errorDetail.isNullOrBlank() &&
                RunLogBudgetPolicy.isBenignPrivateRowRecoveryTransition(event.message))

    /** True only for a confirmed error/fatal event belonging to the run being diagnosed. */
    fun isRuntimeFailureEvent(event: RunDiagnosticEvent): Boolean =
        !isPreviousRunRecoveryEvent(event) && (
            event.level == RunLogLevel.FATAL ||
                (event.level == RunLogLevel.ERROR &&
                    (event.severitySource == RunLogSeveritySource.PRODUCER_DECLARED ||
                        (!isLegacyBenignObservation(event) &&
                            (!event.errorType.isNullOrBlank() || !event.errorDetail.isNullOrBlank() ||
                                !RunLogBudgetPolicy.isNonFatalQualityWarningMessage(event.message)))))
            )

    private fun isMessageDirectionUnresolvedEvent(event: RunDiagnosticEvent): Boolean =
        messageDirectionUnknownCount(event) > 0L

    /**
     * A numeric direction summary is authoritative, including an explicit zero. Older per-row
     * records may not carry a count; those retain the status-token fallback and count as one.
     */
    private fun messageDirectionUnknownCount(event: RunDiagnosticEvent): Long {
        val message = event.message
        val explicitCount = Regex("方向不明\\s*[=:：]\\s*(\\d+)")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        if (explicitCount != null) return explicitCount
        val normalized = message.uppercase()
        return if (
            normalized.contains("状态=DIRECTION_UNKNOWN") ||
            normalized.contains("READSTATUS=DIRECTION_UNKNOWN")
        ) 1L else 0L
    }

    private fun isPartialOcrEvent(event: RunDiagnosticEvent): Boolean {
        val message = event.message.uppercase()
        return message.contains("状态=OCR_PARTIAL") ||
            Regex("OCR(?:读取)?状态\\s*[=:：]\\s*PARTIAL", RegexOption.IGNORE_CASE)
                .containsMatchIn(event.message)
    }

    private fun isSafeNavigationPauseEvent(event: RunDiagnosticEvent): Boolean =
        RunLogBudgetPolicy.isNonFatalQualityWarningMessage(event.message) &&
            (event.message.contains("收到的招呼导航暂停") ||
                event.message.contains("收到招呼详情页返回暂停") ||
                event.message.contains("收到招呼身份页返回暂停"))

    /**
     * Do not use substring matching for TIMEOUT: callbackAfterDrainTimeout is a callback-drain
     * diagnostic, and the common value false must never be promoted to a task-step timeout.
     */
    fun isConfirmedTaskStepTimeout(event: RunDiagnosticEvent): Boolean {
        val type = event.eventType.lowercase()
        if (type in setOf("task_step_timeout", "step_timeout", "task_timeout")) return true
        val result = event.result.orEmpty().uppercase()
        if (result in setOf("TASK_STEP_TIMEOUT", "STEP_TIMEOUT", "TIMED_OUT")) return true
        val message = event.message
        val explicitFlag = Regex(
            "(?:taskStepTimeout|stepTimeout|taskTimeout|timedOut)\\s*[=:：]\\s*(?:true|1|yes)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(message)
        if (explicitFlag) return true
        return Regex("(?:任务步骤|步骤执行|任务等待)(?:[^；;，,。]{0,24})超时", RegexOption.IGNORE_CASE)
            .containsMatchIn(message) ||
            Regex("\\b(?:TASK_STEP_TIMEOUT|STEP_TIMEOUT)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(message)
    }
}
