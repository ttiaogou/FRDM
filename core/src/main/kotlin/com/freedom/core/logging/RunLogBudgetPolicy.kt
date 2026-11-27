package com.freedom.core.logging

import java.util.Locale
import java.util.UUID

/** Byte-based limits shared by monitor history, run tracing and diagnostic reports. */
object RunLogBudgetPolicy {
    const val MAX_MONITOR_ENTRY_BYTES = 8 * 1024
    const val MAX_MONITOR_TOTAL_BYTES = 2 * 1024 * 1024
    const val MAX_RUN_EVENT_BYTES = 8 * 1024 * 1024
    const val MAX_RUN_EVENT_RECORD_BYTES = 20 * 1024
    const val MAX_CRITICAL_EVENT_EXTRA_BYTES = 2 * 1024 * 1024
    // Reallocate the existing total event budget; do not increase it.
    const val MAX_WARNING_EVENT_BYTES = 2 * 1024 * 1024
    const val MAX_LIFECYCLE_EVENT_BYTES = 64 * 1024
    const val MAX_NORMAL_EVENT_BYTES = MAX_RUN_EVENT_BYTES - MAX_WARNING_EVENT_BYTES - MAX_LIFECYCLE_EVENT_BYTES
    const val NORMAL_EVENT_SEGMENT_BYTES = 256 * 1024
    // Separate evidence budget: 32 bounded captures, at most 144 KiB of payload each
    // including one replaceable latest transaction proof, never an unbounded retry log.
    const val MAX_INCIDENT_CAPTURES = 32
    const val MAX_INCIDENT_TRIGGER_BYTES = 24 * 1024
    const val MAX_INCIDENT_BEFORE_BYTES = 32 * 1024
    const val MAX_INCIDENT_AFTER_BYTES = 32 * 1024
    const val MAX_ERROR_DETAIL_BYTES = 32 * 1024
    const val MAX_ERROR_DETAILS_TOTAL_BYTES = 2 * 1024 * 1024
    const val MAX_DIAGNOSTIC_REPORT_BYTES = 256 * 1024
    const val MAX_DIAGNOSTIC_SUMMARY_BYTES = 16 * 1024
    const val MAX_SAMPLE_RUNTIME_LOG_BYTES = 8 * 1024 * 1024
    const val MAX_SAMPLE_RUNTIME_LOG_RECORD_BYTES = 32 * 1024
    const val MAX_SAMPLE_RUNTIME_LOG_ENTRIES = 2_500
    const val MAX_SAMPLE_EVENT_TIMELINE_BYTES = 8 * 1024 * 1024
    const val MAX_SAMPLE_RECORD_BYTES = 8 * 1024
    const val MAX_SAMPLE_NODE_TREE_SAMPLES = 64
    /** Standard snapshots are deliberately capped so key workflow pages retain headroom. */
    const val MAX_SAMPLE_STANDARD_NODE_TREE_SAMPLES = 16
    const val DEDUPLICATION_WINDOW_MS = 250L

    fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    /** Truncates at a Unicode code-point boundary and never exceeds maxBytes in UTF-8. */
    fun truncateUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0 || value.isEmpty()) return ""
        if (utf8Size(value) <= maxBytes) return value
        val output = StringBuilder(minOf(value.length, maxBytes))
        var index = 0
        var used = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val bytes = when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            if (used + bytes > maxBytes) break
            output.appendCodePoint(codePoint)
            used += bytes
            index += charCount
        }
        return output.toString()
    }

    /** Redacts likely user-generated payload fields while retaining the surrounding evidence. */
    fun redactSensitivePayload(message: String): String {
        val normalized = message.replace('\r', ' ').replace('\n', ' ')
        val fields = listOf(
            "消息正文", "消息文本", "聊天内容", "消息预览", "事件文本", "内容描述",
            "OCR识别文本", "ocrText", "eventText", "contentDescription", "联系人昵称",
            "contactName", "displayName", "previewText", "latestMessagePreview"
        )
        var safe = normalized
        fields.forEach { field ->
            val pattern = Regex("(?i)(" + Regex.escape(field) + "\\s*[=:：]\\s*)([^;；|]+)")
            safe = pattern.replace(safe) { match -> match.groupValues[1] + "<REDACTED>" }
        }
        return safe
    }

    /**
     * Read correlation metadata only from the existing, fixed P2 AI log contract.
     * General/legacy text remains on the original inference path, and the business
     * module is deliberately not a dependency of core.
     */
    private data class AiReplyTraceFields(
        val taskId: String?,
        val requestId: String?,
        val eventType: String?
    )

    private fun aiReplyTraceFields(message: String): AiReplyTraceFields? {
        val component = when {
            message.startsWith("AI 模型生命周期：") -> "MODEL"
            message.startsWith("AI 消息发送链路：") -> "SEND"
            else -> return null
        }
        val payload = message.substringAfter('：')
        fun field(key: String): String? {
            val matches = payload.split('；').mapNotNull { part ->
                if (part.startsWith("$key=")) part.substring(key.length + 1) else null
            }
            val value = matches.singleOrNull() ?: return null
            return value.takeIf {
                it.length in 1..128 && it != "UNKNOWN" && it.all { char ->
                    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                        char == '_' || char == '-' || char == '.' || char == ':'
                }
            }
        }
        if (field("component") != component) return null

        val taskId = field("taskId")?.takeIf {
            it == "AI_REPLY_PRIVATE_MESSAGES" || it == "AI_REPLY_RECEIVED_GREETINGS"
        }
        val eventType = field("eventType")?.takeIf {
            it.length <= 100 &&
                (if (component == "MODEL") it.startsWith("MODEL_")
                else it.startsWith("MESSAGE_SEND_")) &&
                it.all { char -> char in 'A'..'Z' || char in '0'..'9' || char == '_' }
        }
        return AiReplyTraceFields(taskId, field("requestId"), eventType)
    }

    fun toEvent(
        message: String,
        runId: String,
        sequence: Long,
        timestampEpochMs: Long,
        elapsedRealtimeMs: Long
    ): RunDiagnosticEvent {
        // Keep 5 KiB for structured metadata, leaving room for long diagnostic evidence lines.
        val safeMessage = truncateUtf8(
            redactSensitivePayload(message),
            MAX_RUN_EVENT_RECORD_BYTES - 5 * 1024
        )
        val level = inferLevel(safeMessage)
        val category = inferCategory(safeMessage, level)
        val aiTrace = aiReplyTraceFields(safeMessage)
        val actionId = Regex("(?:actionId|action_id)\\s*[=:：]\\s*([A-Za-z0-9_.:-]+)", RegexOption.IGNORE_CASE)
            .find(safeMessage)?.groupValues?.getOrNull(1)
        val windowId = Regex("windowId\\s*[=:：]\\s*(-?\\d+)", RegexOption.IGNORE_CASE)
            .find(safeMessage)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val eventType = aiTrace?.eventType
            ?: Regex("(?:事件|event|type)\\s*[=:：]\\s*([A-Za-z0-9_.-]+)", RegexOption.IGNORE_CASE)
                .find(safeMessage)?.groupValues?.getOrNull(1)
            ?: safeMessage.substringBefore('[').substringBefore('：').trim().take(72).ifBlank { "runtime_log" }
        val component = safeMessage.substringBefore('[').substringBefore('：').substringBefore(':')
            .trim().take(80).ifBlank { "runtime" }
        val reason = Regex("(?:原因|reason)\\s*[=:：]\\s*([^;；]+)", RegexOption.IGNORE_CASE)
            .find(safeMessage)?.groupValues?.getOrNull(1)?.take(240)
        return RunDiagnosticEvent(
            sequence = sequence,
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            taskId = aiTrace?.taskId ?: runId,
            traceId = aiTrace?.requestId ?: actionId ?: runId,
            timestampEpochMs = timestampEpochMs,
            elapsedRealtimeMs = elapsedRealtimeMs,
            level = level,
            category = category,
            component = component,
            eventType = eventType,
            message = safeMessage,
            actionId = actionId,
            windowId = windowId,
            result = when (level) {
                RunLogLevel.FATAL, RunLogLevel.ERROR -> "FAILED_OR_EXCEPTION"
                RunLogLevel.WARN -> "WARNING_OR_UNCERTAIN"
                else -> null
            },
            reason = reason,
            errorType = if (level == RunLogLevel.ERROR || level == RunLogLevel.FATAL) {
                Regex("([A-Za-z_$][A-Za-z0-9_$.]*Exception|[A-Za-z_$][A-Za-z0-9_$.]*Error)")
                    .find(safeMessage)?.value
            } else null
        )
    }

    /**
     * These are non-fatal observation/quality states, even when older log wording contains
     * generic words such as "失败". A concrete exception, crash, fatal marker or write failure
     * still keeps its error severity.
     */
    fun isNonFatalQualityWarningMessage(message: String): Boolean {
        val value = message.uppercase(Locale.ROOT)
        val explicitFailure = listOf(
            "FATAL", "致命", "CRASH", "崩溃", "UNCAUGHT", "WRITE_FAILURE", "写入失败"
        ).any(value::contains) ||
            Regex("(?:^|[\\s\\[{,;])(?:LEVEL|SEVERITY|日志级别)\\s*[=:：]\\s*(?:ERROR|FATAL)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(message) ||
            Regex("(?:^|[\\s\\[])ERROR(?:\\]|:|\\s|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(message) ||
            Regex("(?:^|[\\s\\[])EXCEPTION(?:\\]|:|\\s|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(message) ||
            Regex("(?:ERRORS?|EXCEPTIONS?)\\s*[=:：]\\s*[1-9]\\d*", RegexOption.IGNORE_CASE)
                .containsMatchIn(message) ||
            Regex("\\b[A-Za-z_$][A-Za-z0-9_$.]*(?:EXCEPTION|ERROR)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(message)
        if (explicitFailure) return false

        val directionUnknown = value.contains("状态=DIRECTION_UNKNOWN") ||
            value.contains("READSTATUS=DIRECTION_UNKNOWN") ||
            Regex("方向不明\\s*[=:：]").containsMatchIn(message)
        val partialOcr = value.contains("状态=OCR_PARTIAL") ||
            Regex("OCR(?:读取)?状态\\s*[=:：]\\s*PARTIAL", RegexOption.IGNORE_CASE)
                .containsMatchIn(message)
        val safeNavigationPause = value.contains("收到的招呼导航暂停") ||
            value.contains("收到招呼详情页返回暂停") ||
            value.contains("收到招呼身份页返回暂停")
        // These exact business observation states describe retained, retryable
        // row evidence. The component name itself contains "异常", not a crash.
        val privateRowState = Regex(
            "^AI 私聊异常会话\\[(INCOMPLETE_UNREAD_RETRYABLE|ROW_SCOPED_BLOCK|LIST_EVIDENCE_INSUFFICIENT|HANDOFF_BLOCKED|PRE_SCROLL_EVIDENCE_LOST|RELOCATION_WAIT|RELOCATION_STARTED)\\]：",
            RegexOption.IGNORE_CASE
        ).find(message)
        val privateRowWarning = privateRowState != null && run {
            val body = message.substring(privateRowState.range.last + 1).uppercase(Locale.ROOT)
            // Only this exact waiting phrase describes retained evidence, not a new failure.
            val severityBody = if (privateRowState.groupValues[1] == "RELOCATION_WAIT") {
                body.replace("原异常未解决", "")
            } else body
            listOf("失败", "异常", "错误", "TIMEOUT", "超时", "ERROR", "EXCEPTION")
                .none { severityBody.contains(it) }
        }
        return directionUnknown || partialOcr || safeNavigationPause || privateRowWarning
    }

    /**
     * A historical identity observation used a negated phrase containing "失败".
     * Only that exact phrase on that exact waiting event is informational. This
     * method never hides a separate error, exception or warning on the same line.
     */
    fun isBenignIdentityObservationWait(message: String): Boolean =
        message.uppercase(Locale.ROOT).contains("AI 联系人身份采集[OBSERVE_WAITING]") &&
            message.contains("不返回失败原因") &&
            inferLevel(message) == RunLogLevel.INFO

    /** Successful/started recovery transitions, not new observation failures.
     * Exact matching prevents an appended timeout, exception or write failure
     * from inheriting the informational level of the transition.
     */
    fun isBenignPrivateRowRecoveryTransition(message: String): Boolean =
        message.trim() in setOf(
            "AI 私聊异常会话[RELOCATION_ORIGIN_VERIFIED]：已重新找到目标或确认已读边界；保留未解决身份",
            "AI 私聊异常会话[RELOCATION_STARTED]：保留原轮次、已处理记录与未决身份；重新寻找待处理目标，找到后立即反向处理",
            "AI 私聊异常会话[DEFERRED_WORK_HANDOFF]：局部待复核身份不计为完成；保留检查点并安全交接"
        )

    fun inferLevel(message: String): RunLogLevel {
        if (isBenignPrivateRowRecoveryTransition(message)) return RunLogLevel.INFO
        val value = message.uppercase(Locale.ROOT)
        val observationValue = if (value.contains("AI 联系人身份采集[OBSERVE_WAITING]")) {
            value.replace("不返回失败原因", "")
        } else value
        // Older navigation-wait wording included a negated phrase ending in “失败判定”.
        // Remove only that known negation before generic severity matching; a real failure,
        // timeout or exception appended to the same event must still keep its severity.
        val severityValue = observationValue
            .replace("不执行主聊天列表身份失败判定", "")
            .replace(Regex("CALLBACKAFTERDRAINTIMEOUT\\s*[=:：]\\s*FALSE\\b"), "")
            .replace(Regex("(?:截断|TRUNCATED)\\s*[=:：]\\s*FALSE\\b"), "")
            .replace("未截断", "")
            // Summary records often say `失败=0` / `writeFailures=0`. These are zero-valued
            // counters, not failure statements. Remove only explicit zero counters before
            // keyword severity matching; non-zero counters and natural-language failures remain.
            .replace(
                Regex("(?:失败(?:次数|区域|目标)?|错误(?:次数)?|异常(?:次数)?|[A-Z_]*EXCEPTION(?:S|COUNT)?|[A-Z_]*ERROR(?:S|EVENTS)?|FATAL(?:S|EVENTS)?|WRITEFAILURES|FAILURECOUNT|FAILEDCOUNT)\\s*[=:：]\\s*0(?:\\.0+)?(?=$|[\\s;；,，|])"),
                ""
            )
        if (severityValue.contains("入口点击已提交") && severityValue.contains("等待异步进入") &&
            listOf("ERROR", "FATAL", "EXCEPTION", "异常", "失败", "TIMEOUT", "超时", "CRASH", "崩溃")
                .none(severityValue::contains)
        ) {
            return RunLogLevel.INFO
        }
        if (isNonFatalQualityWarningMessage(severityValue)) return RunLogLevel.WARN
        // A routine STARTING observation is not a failed action. Only the exact
        // established transition and normal wait reasons are informational; any
        // extra failure or timeout evidence stays on the general severity path.
        val normalReadinessWait = Regex(
            "^目标应用就绪状态\\[TRANSITION\\]：[A-Z]+->STARTING；MODE=[A-Z_]+；PACKAGE=[^；]+；ACTIVITY=[^；]+；WINDOWID=-?\\d+；PAGEGENERATION=-?\\d+；REASON=(?:当前 ACTIVITY 身份尚未确认|当前活动窗口身份尚未确认|尚未取得当前活动窗口；等待目标应用完成前台启动|目标应用已进入前台；启动保护静默观察中；剩余=\\d+MS；禁止自主手势)$"
        ).matches(severityValue)
        if (normalReadinessWait) return RunLogLevel.INFO
        return when {
            listOf("FATAL", "致命", "CRASH", "崩溃").any(severityValue::contains) -> RunLogLevel.FATAL
            listOf("ERROR", "异常", "失败", "WRITE_FAILURE", "写入失败").any(severityValue::contains) -> RunLogLevel.ERROR
            listOf(
                "UNKNOWN", "未确认", "未找到", "超时", "TIMEOUT", "取消", "丢弃", "截断", "DEDUP",
                "SNAPSHOT_RECOVERY_EXHAUSTED", "快照恢复耗尽"
            ).any(severityValue::contains) -> RunLogLevel.WARN
            else -> RunLogLevel.INFO
        }
    }

    fun inferCategory(message: String, level: RunLogLevel = inferLevel(message)): RunLogCategory {
        if (level == RunLogLevel.ERROR || level == RunLogLevel.FATAL) return RunLogCategory.ERROR
        val value = message.uppercase(Locale.ROOT)
        if (isNonFatalQualityWarningMessage(message)) {
            return if (value.contains("导航暂停") || value.contains("页面切换")) {
                RunLogCategory.NAVIGATION
            } else {
                RunLogCategory.DATA_QUALITY
            }
        }
        return when {
            listOf("SERVICE", "服务", "PROCESS", "进程", "LIFECYCLE").any(value::contains) -> RunLogCategory.LIFECYCLE
            listOf("TASK", "任务", "RUN_STARTED", "RUN_FINISHED").any(value::contains) -> RunLogCategory.TASK
            listOf("NAVIGATION", "页面", "ACTIVITY", "跳转", "返回").any(value::contains) -> RunLogCategory.NAVIGATION
            listOf("INPUT", "触摸", "人工", "ACTIONID", "输入来源").any(value::contains) -> RunLogCategory.INPUT
            listOf("GESTURE", "手势", "滑动", "点击").any(value::contains) -> RunLogCategory.GESTURE
            listOf("采集", "消息", "资料卡", "会话", "OCR").any(value::contains) -> RunLogCategory.COLLECTION
            listOf("完整性", "质量", "UNKNOWN", "未确认", "缺失").any(value::contains) -> RunLogCategory.DATA_QUALITY
            listOf("归档", "ZIP", "封存", "MANIFEST").any(value::contains) -> RunLogCategory.ARCHIVE
            else -> RunLogCategory.GENERAL
        }
    }
}
