package com.freedom.features.aireply.ai

/** Provider-neutral request/response contract. Implementations must not log prompts or secrets. */
interface AiModelProvider {
    /** Cloud-capable providers must require explicit consent; local providers may override to false. */
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

    /** The provider should request a strict structured response rather than free-form prose. */
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

sealed interface AiModelFailure {
    data object NotConfigured : AiModelFailure
    data class Transport(val retryable: Boolean, val safeCode: String) : AiModelFailure
    data class Rejected(val safeCode: String) : AiModelFailure
    data object TimedOut : AiModelFailure
}

/** Explicit adapter boundary for a future HTTP/provider SDK integration. */
interface AiModelTransport {
    fun execute(request: AiModelRequest): Result<AiModelResult>
}

/**
 * Transport-backed model access is opt-in only. Consent is checked here again, at the outbound
 * boundary, so a caller cannot bypass the setting by constructing a model request directly.
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
        require(result.isUsable) { "Model provider returned empty output" }
        return result.copy(provider = providerName, model = modelName)
    }
}
