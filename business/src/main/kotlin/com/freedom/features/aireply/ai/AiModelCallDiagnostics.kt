package com.freedom.features.aireply.ai

import com.freedom.core.logging.RunDiagnosticInput
import com.freedom.core.logging.RunLogCategory
import com.freedom.core.logging.RunLogLevel
import java.util.LinkedHashSet

/** 仅记录技术故障元数据，禁止保留异常消息、URL、响应正文或凭据。 */
/** Classification is diagnostic only and must never control retry or sending. */
enum class AiModelOutputCategory {
    VALID_DECISION, EMPTY_MODEL_OUTPUT, MODEL_OUTPUT_INVALID, MODEL_CALL_FAILED, LOCAL_PRECHECK_BLOCKED
}

enum class AiModelCallStage {
    MODEL_CALL, CONFIGURATION, ENDPOINT, REQUEST_ENCODING, CONNECTION_OPEN,
    CONNECTION_SETUP, REQUEST_WRITE, RESPONSE_STATUS, RESPONSE_READ, RESPONSE_PARSE
}

/** 由网络适配器实现，业务层不直接依赖具体适配器。 */
interface AiModelCallFailureDetails {
    val safeCode: String
    val retryable: Boolean
    val httpStatus: Int?
    val stage: AiModelCallStage
    val providerErrorCode: String? get() = null
    val providerErrorType: String? get() = null
    val providerErrorParam: String? get() = null
    /** 仅保留固定格式的技术元数据，不保存供应商响应正文。 */
    val responseContentState: String? get() = null
    val responseFinishReason: String? get() = null
    val responseInputTokens: Int? get() = null
    val responseOutputTokens: Int? get() = null
    val responseReasoningContentState: String? get() = null
}

data class AiModelCallDiagnostics(
    val safeCode: String,
    val stage: AiModelCallStage,
    val httpStatus: Int?,
    val retryable: Boolean?,
    val exceptionType: String,
    val causeTypes: List<String>,
    val providerErrorCode: String? = null,
    val providerErrorType: String? = null,
    val providerErrorParam: String? = null,
    val responseContentState: String? = null,
    val responseFinishReason: String? = null,
    val responseInputTokens: Int? = null,
    val responseOutputTokens: Int? = null,
    val responseReasoningContentState: String? = null
) {
    fun logFields(): String = "safeCode=${safeToken(safeCode)}；failureStage=${stage.name}；" +
        "httpStatus=${httpStatus ?: "UNKNOWN"}；retryable=${retryable ?: "UNKNOWN"}；" +
        "exceptionType=${safeToken(exceptionType)}；causeTypes=${causeTypes.joinToString(",") { safeToken(it) }}；" +
        "providerErrorCode=${safeProviderError(providerErrorCode)}；" +
        "providerErrorType=${safeProviderError(providerErrorType)}；providerErrorParam=${safeProviderParam(providerErrorParam)}；" +
        "responseContentState=${safeContentState(responseContentState)}；" +
        "finishReason=${safeFinishReason(responseFinishReason)}；" +
        "reasoningContentState=${safeContentState(responseReasoningContentState)}；" +
        "inputTokens=${responseInputTokens?.takeIf { it >= 0 } ?: "UNKNOWN"}；" +
        "outputTokens=${responseOutputTokens?.takeIf { it >= 0 } ?: "UNKNOWN"}"

    companion object {
        private fun safeToken(value: String): String = value.takeIf {
            it.length in 1..200 && it.matches(Regex("[A-Za-z0-9_.$-]+"))
        } ?: "UNKNOWN"

        // 仅保留固定技术词表；未知值不输出，避免服务端字段夹带密钥或聊天原文。
        private val providerErrors = setOf(
            "invalid_api_key", "authentication_error", "permission_denied", "permission_error",
            "invalid_request_error", "invalid_request", "invalid_parameter", "unsupported_parameter",
            "unsupported_value", "model_not_found", "context_length_exceeded", "insufficient_quota",
            "rate_limit_exceeded", "rate_limit_error", "server_error", "internal_error", "api_error",
            "service_unavailable", "invalid_response_format", "invalid_json_schema", "billing_hard_limit_reached"
        )
        private val providerParams = setOf(
            "model", "temperature", "max_tokens", "max_completion_tokens", "response_format",
            "response_format.type", "response_format.json_schema", "messages", "stream", "thinking"
        )
        fun safeProviderError(value: String?): String = when {
            value == null -> "UNKNOWN"
            value in providerErrors -> value
            else -> "REDACTED"
        }
        fun safeProviderParam(value: String?): String = when {
            value == null -> "UNKNOWN"
            value in providerParams -> value
            else -> "REDACTED"
        }
        private fun safeContentState(value: String?): String =
            value?.takeIf { it in setOf("PRESENT", "ABSENT", "NULL", "BLANK", "NON_STRING") } ?: "UNKNOWN"
        fun safeFinishReason(value: String?): String = when (value) {
            null -> "UNKNOWN"
            "stop", "length", "content_filter", "tool_calls", "function_call" -> value
            else -> "REDACTED"
        }

        fun from(failure: Throwable): AiModelCallDiagnostics {
            val details = failure as? AiModelCallFailureDetails
            // 只有这些本地固定的校验消息可以映射为错误码，禁止记录任意异常消息。
            val localCode = when (failure.message) {
                "Model provider returned empty output" -> "EMPTY_MODEL_OUTPUT"
                "Cloud model processing consent has not been granted" -> "CLOUD_PROCESSING_CONSENT_REQUIRED"
                else -> "MODEL_CALL_EXCEPTION"
            }
            val causes = ArrayList<String>()
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            seen.add(failure)
            var cause = failure.cause
            while (cause != null && causes.size < 8 && seen.add(cause)) {
                causes += safeToken(cause.javaClass.name)
                cause = cause.cause
            }
            return AiModelCallDiagnostics(
                safeCode = safeToken(details?.safeCode ?: localCode),
                stage = details?.stage ?: AiModelCallStage.MODEL_CALL,
                httpStatus = details?.httpStatus?.takeIf { it in 100..599 },
                retryable = details?.retryable,
                exceptionType = safeToken(failure.javaClass.name),
                causeTypes = causes,
                providerErrorCode = details?.providerErrorCode?.let(::safeProviderError),
                providerErrorType = details?.providerErrorType?.let(::safeProviderError),
                providerErrorParam = details?.providerErrorParam?.let(::safeProviderParam),
                responseContentState = details?.responseContentState?.let(::safeContentState),
                responseFinishReason = details?.responseFinishReason?.let(::safeFinishReason),
                responseInputTokens = details?.responseInputTokens?.takeIf { it >= 0 },
                responseOutputTokens = details?.responseOutputTokens?.takeIf { it >= 0 },
                responseReasoningContentState = details?.responseReasoningContentState?.let(::safeContentState)
            )
        }
    }
}

/**
 * Shared, safe model/send event contract for both registered AI reply businesses.
 * Diagnostics only: no conversation text, raw transport responses, page fingerprints or UI capabilities.
 * Fixed fields allow model and send events to be joined without changing persistence contracts.
 */
internal object AiReplyTraceContract {
    fun format(
        component: String,
        requestId: String,
        taskId: String?,
        generation: Long,
        eventType: String,
        attemptIndex: Int? = null,
        retryState: String? = null,
        reasonCode: String? = null,
        delayMs: Long? = null,
        responseMode: String? = null,
        outcomeCategory: String? = null,
        segmentIndex: Int? = null,
        segmentCount: Int? = null,
        sendAttemptId: String? = null,
        sendConfirmationId: String? = null,
        sendStatus: String? = null,
        finalState: String? = null,
        sendAttemptOrigin: String? = null
    ): RunDiagnosticInput {
        // Never serialize arbitrary provider, adapter or persisted identifier text.
        fun safe(value: String?): String = value?.takeIf {
            it.length in 1..128 && it.all { char ->
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                    char == '_' || char == '-' || char == '.' || char == ':'
            }
        } ?: "UNKNOWN"
        val safeTask = taskId?.takeIf {
            it == AiDecisionTaskIds.PRIVATE_MESSAGES ||
                it == AiDecisionTaskIds.RECEIVED_GREETINGS
        } ?: "UNKNOWN"
        val prefix = if (component == "MODEL") "AI 模型生命周期：" else "AI 消息发送链路："
        val message = prefix +
            "component=${safe(component)}；requestId=${safe(requestId)}；taskId=$safeTask；" +
            "generation=$generation；eventType=${safe(eventType)}；" +
            "attemptIndex=${attemptIndex ?: "NONE"}；retryState=${safe(retryState)}；" +
            "reasonCode=${safe(reasonCode)}；delayMs=${delayMs ?: "NONE"}；" +
            "responseMode=${safe(responseMode)}；outcomeCategory=${safe(outcomeCategory)}；" +
            "segmentIndex=${segmentIndex ?: "NONE"}；segmentCount=${segmentCount ?: "NONE"}；" +
            "sendAttemptId=${safe(sendAttemptId)}；sendConfirmationId=${safe(sendConfirmationId)}；" +
            "sendStatus=${safe(sendStatus)}；sendAttemptOrigin=${safe(sendAttemptOrigin)}；" +
            "finalState=${safe(finalState)}"
        val level = when (eventType) {
            "MODEL_REQUEST_FINISHED" -> when (finalState) {
                "SUCCEEDED", "CANCELLED" -> RunLogLevel.INFO
                else -> RunLogLevel.ERROR
            }
            "MODEL_ATTEMPT_FINISHED" -> when (outcomeCategory) {
                "VALID_DECISION", "LOCAL_PRECHECK_BLOCKED" -> RunLogLevel.INFO
                else -> RunLogLevel.WARN
            }
            "MODEL_RETRY_SCHEDULE_FAILED" -> RunLogLevel.ERROR
            "MESSAGE_SEND_UNCERTAIN", "MESSAGE_SEND_REJECTED", "MESSAGE_SEND_DEFERRED" -> RunLogLevel.WARN
            "MODEL_ATTEMPT_STARTED", "MODEL_RETRY_SCHEDULED", "MODEL_RETRY_EXECUTING",
            "MODEL_RETRY_CANCELLED", "MESSAGE_SEND_SUBMITTED", "MESSAGE_SEND_REHYDRATED",
            "MESSAGE_SEND_CONFIRMED", "MESSAGE_SEND_COMPLETED" -> RunLogLevel.INFO
            else -> RunLogLevel.WARN
        }
        return RunDiagnosticInput(
            eventType = safe(eventType),
            level = level,
            category = RunLogCategory.TASK,
            component = if (component == "MODEL") "AiModel" else "AiSend",
            message = message,
            taskId = safeTask.takeUnless { it == "UNKNOWN" },
            traceId = safe(requestId).takeUnless { it == "UNKNOWN" },
            result = safe(finalState ?: sendStatus ?: outcomeCategory ?: retryState),
            reason = safe(reasonCode),
            requestGeneration = generation
        )
    }
}

/**
 * Instance-local diagnostic lifecycle of an existing draft transaction. No model, UI,
 * scheduling, lease or persistence authority. The draft coordinator supplies its original lock.
 * Locked methods are called only inside that transaction's existing synchronized block;
 * event emission stays at the original call sites, outside the inner diagnostic lock scope.
 */
internal class AiReplyRequestTrace(
    private val lock: Any,
    private val onDiagnosticLog: (RunDiagnosticInput) -> Unit,
    private val maxProcessedKeys: Int,
    private val maxEmptyModelRetries: Int
) {
    /** Diagnostic-only trace. Never consulted by model decisions, send gates or retry scheduling. */
    private data class ModelLifecycleTrace(
        val requestId: String,
        val taskId: String,
        val requestGeneration: Long,
        var attemptIndex: Int = -1,
        var scheduledRetries: Int = 0,
        var retryPending: Boolean = false,
        var lastOutcomeCategory: String = "UNKNOWN",
        var terminalLogged: Boolean = false
    )

    private var modelLifecycleTrace: ModelLifecycleTrace? = null
    /** Diagnostic-only de-duplication, never used to advance a send or release a gate. */
    private val emittedSendTraceKeys = LinkedHashSet<String>()

    private fun lifecycleRecord(
        trace: ModelLifecycleTrace,
        eventType: String,
        retryState: String = "NONE",
        reasonCode: String = "NONE",
        delayMs: Long? = null,
        responseMode: String = "UNKNOWN",
        finalState: String? = null
    ): RunDiagnosticInput = AiReplyTraceContract.format(
        component = "MODEL", requestId = trace.requestId, taskId = trace.taskId,
        generation = trace.requestGeneration, eventType = eventType,
        attemptIndex = trace.attemptIndex, retryState = retryState, reasonCode = reasonCode,
        delayMs = delayMs, responseMode = responseMode,
        outcomeCategory = trace.lastOutcomeCategory, finalState = finalState
    )

    fun recordModelEvidence(
        type: String, level: RunLogLevel, taskId: String, requestId: String,
        requestGeneration: Long, result: String, message: String
    ) {
        runCatching {
            onDiagnosticLog(RunDiagnosticInput(
                eventType = type, level = level, category = RunLogCategory.TASK,
                component = "AiModel", message = message, taskId = taskId,
                traceId = requestId, requestGeneration = requestGeneration, result = result
            ))
        }
    }

    /** Emits fixed diagnostic codes only; model output and exception messages never enter this log. */
    fun recordModelLifecycle(
        requestId: String,
        requestGeneration: Long,
        eventType: String,
        attemptIndex: Int,
        retryState: String = "NONE",
        reasonCode: String = "NONE",
        delayMs: Long? = null,
        responseMode: String = "UNKNOWN",
        outcomeCategory: String = "UNKNOWN"
    ) {
        val log = synchronized(lock) {
            val trace = modelLifecycleTrace?.takeIf {
                it.requestId == requestId && it.requestGeneration == requestGeneration && !it.terminalLogged
            } ?: return@synchronized null
            when (eventType) {
                "MODEL_ATTEMPT_STARTED" -> trace.attemptIndex = attemptIndex
                "MODEL_ATTEMPT_FINISHED" -> trace.lastOutcomeCategory = outcomeCategory
                "MODEL_RETRY_SCHEDULED" -> {
                    trace.retryPending = true
                    trace.scheduledRetries++
                }
                "MODEL_RETRY_EXECUTING", "MODEL_RETRY_CANCELLED" -> trace.retryPending = false
            }
            lifecycleRecord(trace, eventType, retryState, reasonCode, delayMs, responseMode)
        }
        // Diagnostics must never cause a model decision or UI action to fail.
        log?.let { runCatching { onDiagnosticLog(it) } }
    }

    private fun terminalLifecycleRecord(
        trace: ModelLifecycleTrace,
        finalState: String,
        reasonCode: String,
        retryState: String = when {
            finalState == "SUCCEEDED" -> "SUCCEEDED"
            finalState == "CANCELLED" -> "CANCELLED_GENERATION_CHANGED"
            trace.lastOutcomeCategory == "EMPTY_MODEL_OUTPUT" &&
                trace.scheduledRetries == maxEmptyModelRetries -> "EXHAUSTED"
            else -> "NONE"
        }
    ): RunDiagnosticInput = lifecycleRecord(
        trace, "MODEL_REQUEST_FINISHED", retryState, reasonCode, finalState = finalState
    )

    /** Exactly one model-generation terminal event, separate from later send confirmation. */
    fun completeModelLifecycle(
        requestId: String,
        requestGeneration: Long,
        finalState: String,
        reasonCode: String,
        retryState: String? = null
    ) {
        val log = synchronized(lock) {
            val trace = modelLifecycleTrace?.takeIf {
                it.requestId == requestId && it.requestGeneration == requestGeneration && !it.terminalLogged
            } ?: return@synchronized null
            trace.terminalLogged = true
            if (retryState == null) terminalLifecycleRecord(trace, finalState, reasonCode)
            else terminalLifecycleRecord(trace, finalState, reasonCode, retryState)
        }
        log?.let { runCatching { onDiagnosticLog(it) } }
    }

    fun beginLocked(requestId: String, taskId: String, requestGeneration: Long) {
        modelLifecycleTrace = ModelLifecycleTrace(requestId, taskId, requestGeneration)
    }

    fun resetLocked(): List<RunDiagnosticInput> {
        var cancelledModelLogs: List<RunDiagnosticInput> = emptyList()
        modelLifecycleTrace?.takeIf { !it.terminalLogged }?.let { trace ->
            trace.terminalLogged = true
            cancelledModelLogs = (if (trace.retryPending) listOf(
                lifecycleRecord(trace, "MODEL_RETRY_CANCELLED", "CANCELLED_GENERATION_CHANGED", "RUNTIME_RESET")
            ) else emptyList()) + terminalLifecycleRecord(
                trace, "CANCELLED", "RUNTIME_RESET", "CANCELLED_GENERATION_CHANGED"
            )
        }
        modelLifecycleTrace = null
        emittedSendTraceKeys.clear()
        return cancelledModelLogs
    }

    fun finishLocked(requestGeneration: Long, reasonCode: String): RunDiagnosticInput? {
        var terminalLog: RunDiagnosticInput? = null
        modelLifecycleTrace?.takeIf {
            it.requestGeneration == requestGeneration && !it.terminalLogged
        }?.let { trace ->
            trace.terminalLogged = true
            val cancelled = reasonCode == "MODEL_EMPTY_RETRY_CONTEXT_CHANGED" ||
                reasonCode.startsWith("AI_REPLY_STATE_CHANGED")
            val retryState = when {
                reasonCode == "MODEL_EMPTY_RETRY_CONTEXT_CHANGED" -> "CANCELLED_CONTEXT_CHANGED"
                cancelled -> "CANCELLED_RUNTIME_INACTIVE"
                trace.lastOutcomeCategory == "EMPTY_MODEL_OUTPUT" &&
                    trace.scheduledRetries == maxEmptyModelRetries -> "EXHAUSTED"
                else -> "NONE"
            }
            terminalLog = terminalLifecycleRecord(
                trace, if (cancelled) "CANCELLED" else "FAILED", reasonCode, retryState
            )
        }
        return terminalLog
    }

    fun recordSendTrace(
        eventType: String,
        requestId: String,
        requestGeneration: Long,
        segmentIndex: Int?,
        segmentCount: Int?,
        sendAttemptId: String? = null,
        sendConfirmationId: String? = null,
        sendStatus: String = "UNKNOWN",
        reasonCode: String = "NONE",
        recoveredFromLedger: Boolean = false
    ) {
        val log = synchronized(lock) {
            val terminal = eventType in setOf(
                "MESSAGE_SEND_CONFIRMED", "MESSAGE_SEND_UNCERTAIN", "MESSAGE_SEND_COMPLETED"
            )
            val key = "$requestId|$requestGeneration|$segmentIndex|${sendAttemptId.orEmpty()}|$eventType"
            if (terminal && !emittedSendTraceKeys.add(key)) return@synchronized null
            while (emittedSendTraceKeys.size > maxProcessedKeys) {
                emittedSendTraceKeys.remove(emittedSendTraceKeys.first())
            }
            val taskId = modelLifecycleTrace?.takeIf {
                it.requestId == requestId && it.requestGeneration == requestGeneration
            }?.taskId
            AiReplyTraceContract.format(
                component = "SEND", requestId = requestId, taskId = taskId,
                generation = requestGeneration, eventType = eventType,
                segmentIndex = segmentIndex, segmentCount = segmentCount,
                sendAttemptId = sendAttemptId, sendConfirmationId = sendConfirmationId,
                sendStatus = sendStatus, reasonCode = reasonCode,
                sendAttemptOrigin = if (recoveredFromLedger) "RECOVERED_SYNTHETIC" else "ORIGINAL"
            )
        }
        // Logging is observational only: callback failure cannot affect send state.
        log?.let { runCatching { onDiagnosticLog(it) } }
    }
}
