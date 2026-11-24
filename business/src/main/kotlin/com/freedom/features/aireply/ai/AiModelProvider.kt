package com.freedom.features.aireply.ai

/** 不依赖具体服务商的请求与响应契约；实现不得记录提示词或密钥。 */
interface AiModelProvider {
    /** 云端服务商必须取得用户明确授权；本地服务商可覆盖为无需授权。 */
    val requiresCloudConsent: Boolean get() = true

    fun generate(request: AiModelRequest): AiModelResult
}

data class AiModelRequest(
    val systemInstruction: String,
    val conversation: List<AiModelMessage>,
    val temperature: Double = 0.3,
    val maxOutputTokens: Int = 512,
    val requestId: String,
    val responseFormat: AiModelResponseFormat = AiModelResponseFormat.PlainText
) {
    init {
        require(systemInstruction.isNotBlank())
        require(conversation.isNotEmpty())
        require(temperature in 0.0..2.0)
        require(maxOutputTokens in 1..8192)
        require(requestId.isNotBlank())
    }
}

sealed interface AiModelResponseFormat {
    data object PlainText : AiModelResponseFormat

    /** 服务商应请求严格的结构化响应，而不是自由格式文本。 */
    data class JsonSchema(
        val name: String,
        val schema: String,
        val strict: Boolean = true
    ) : AiModelResponseFormat {
        init {
            require(name.isNotBlank())
            require(schema.isNotBlank())
            require(strict)
        }
    }
}

data class AiModelMessage(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT }
    init { require(content.isNotBlank()) }
}

data class AiModelResult(
    val text: String?,
    val provider: String,
    val model: String,
    val usage: TokenUsage? = null,
    val finishReason: String? = null
) {
    val isUsable: Boolean get() = !text.isNullOrBlank()
}

data class TokenUsage(val inputTokens: Int, val outputTokens: Int) {
    init { require(inputTokens >= 0 && outputTokens >= 0) }
}

/** 传输响应有效但没有可用文本时，仅保留受限的技术字段。 */
class AiModelEmptyOutputException(
    override val responseContentState: String,
    override val responseFinishReason: String?,
    override val responseInputTokens: Int? = null,
    override val responseOutputTokens: Int? = null
    override val responseReasoningContentState: String? = null
) : IllegalArgumentException("Model provider returned empty output"), AiModelCallFailureDetails {
    override val safeCode = "EMPTY_MODEL_OUTPUT"
    override val retryable = responseFinishReason == null || responseFinishReason == "stop"
    override val httpStatus: Int? = null
    override val stage = AiModelCallStage.MODEL_CALL
}

sealed interface AiModelFailure {
    data object NotConfigured : AiModelFailure
    data class Transport(val retryable: Boolean, val safeCode: String) : AiModelFailure
    data class Rejected(val safeCode: String) : AiModelFailure
    data object TimedOut : AiModelFailure
}

/** HTTP 与具体服务商实现使用的适配器接口边界。 */
interface AiModelTransport {
    fun execute(request: AiModelRequest): Result<AiModelResult>
}

/**
 * 使用传输层访问模型必须获得用户授权；在发出请求的边界再次检查授权，
 * 防止调用方通过直接构造模型请求绕过配置检查。
 */
class TransportBackedAiModelProvider(
    private val providerName: String,
    private val modelName: String,
    private val transport: AiModelTransport,
    private val cloudConsent: AiModelCloudConsent
) : AiModelProvider {
    init { require(providerName.isNotBlank() && modelName.isNotBlank()) }

    override fun generate(request: AiModelRequest): AiModelResult {
        check(cloudConsent.isGranted()) { "Cloud model processing consent has not been granted" }
        val outboundRequest = request.copy(
            systemInstruction = AiModelDataRedactor.redact(request.systemInstruction),
            conversation = AiModelDataRedactor.redactMessages(request.conversation)
        )
        val result = transport.execute(outboundRequest).getOrThrow()
        if (!result.isUsable) {
            throw AiModelEmptyOutputException(
                responseContentState = if (result.text == null) "NULL" else "BLANK",
                responseFinishReason = result.finishReason,
                responseInputTokens = result.usage?.inputTokens,
                responseOutputTokens = result.usage?.outputTokens
            )
        }
        return result.copy(provider = providerName, model = modelName)
    }
}
