package com.freedom.features.aireply.ai

/**
 * P0 contract only: applications prepare context and constraints before model evaluation.
 * Implementations must not observe pages, own task leases, send messages or execute gestures.
 * Existing AiReplyDecisionEngine/AiModelProvider/AiModelTransport remain the sole model path.
 */
interface AiDecisionGateway {
    fun decide(request: ModelDecisionRequest): ModelDecisionOutcome
}

/** History selection belongs to the calling business, never to the model transport. */
enum class ModelContextScope {
    CURRENT_WINDOW_ONLY,
    VERIFIED_HISTORY_WITH_CURRENT_WINDOW
}

/**
 * Provider response-mode preference is separate from the required local JSON schema.
 * PROMPT_ONLY_JSON may change the provider request but must not relax local parsing.
 */
enum class ModelResponseMode { STRICT_JSON, PROMPT_ONLY_JSON }

data class ModelDecisionConstraints(
    val contextScope: ModelContextScope,
    val contactIdentityVerified: Boolean,
    val cooldownActive: Boolean,
    val personalization: AiReplyPersonalization = AiReplyPersonalization()
)

/** An explicit local JSON contract, even when provider JSON enforcement is unavailable. */
data class ModelOutputContract(
    val name: String,
    val jsonSchema: String,
    val strict: Boolean = true
) {
    init {
        require(name.isNotBlank())
        require(jsonSchema.isNotBlank())
        require(strict) { "Model decisions require strict local schema validation" }
    }

    override fun toString(): String =
        "ModelOutputContract(name=$name, jsonSchema=[REDACTED], strict=$strict)"
}

/**
 * No raw OCR frame, Android node, page snapshot, API key or UI capability is accepted here.
 * Task ownership/window/generation are checked by the existing coordinators, not by this DTO.
 */
data class ModelDecisionRequest(
    val taskId: String,
    val requestId: String,
    val context: ConversationContext,
    val constraints: ModelDecisionConstraints,
    val outputContract: ModelOutputContract,
    val responseMode: ModelResponseMode = ModelResponseMode.STRICT_JSON
) {
    init {
        require(taskId.isNotBlank())
        require(requestId.isNotBlank())
    }

    // Prevent accidental logging of conversation text or caller-provided request identifiers.
    override fun toString(): String =
        "ModelDecisionRequest(taskId=[REDACTED], requestId=[REDACTED], " +
            "context=[REDACTED], constraints=[REDACTED], outputContract=[REDACTED], " +
            "responseMode=$responseMode)"
}

/**
 * Reuse the existing disjoint model-decision/program-execution outcomes and diagnostics.
 * A transport failure is never a model WAIT decision.
 */
typealias ModelDecisionOutcome = AiReplyEvaluation
