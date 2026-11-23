package com.freedom.features.aireply.ai

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
    val responseOutputTokens: Int? = null
) {
    fun logFields(): String = "safeCode=${safeToken(safeCode)}；failureStage=${stage.name}；" +
        "httpStatus=${httpStatus ?: "UNKNOWN"}；retryable=${retryable ?: "UNKNOWN"}；" +
        "exceptionType=${safeToken(exceptionType)}；causeTypes=${causeTypes.joinToString(",") { safeToken(it) }}；" +
        "providerErrorCode=${safeProviderError(providerErrorCode)}；" +
        "providerErrorType=${safeProviderError(providerErrorType)}；providerErrorParam=${safeProviderParam(providerErrorParam)}；" +
        "responseContentState=${safeContentState(responseContentState)}；" +
        "finishReason=${safeFinishReason(responseFinishReason)}；" +
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
            value?.takeIf { it in setOf("ABSENT", "NULL", "BLANK", "NON_STRING") } ?: "UNKNOWN"
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
                responseOutputTokens = details?.responseOutputTokens?.takeIf { it >= 0 }
            )
        }
    }
}
