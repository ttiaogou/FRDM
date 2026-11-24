package com.freedom.features.aireply.ai

/**
 * Provider-neutral bridge to the existing, unchanged decision engine.
 * Does not own UI operations, OCR, task leases, retries, history or send confirmation.
 */
class EngineBackedAiDecisionGateway(
    modelProvider: AiModelProvider,
    cloudConsent: AiModelCloudConsent = AiModelCloudConsent.DENY_ALL
) : AiDecisionGateway {
    private val engine = AiReplyDecisionEngine(modelProvider = modelProvider, cloudConsent = cloudConsent)

    override fun decide(request: ModelDecisionRequest): ModelDecisionOutcome {
        // The current engine generates exactly this schema and always parses locally.
        // Fail closed rather than silently ignoring a different caller-specified protocol.
        require(request.outputContract == AiReplyModelContracts.currentOutput()) {
            "Unsupported model decision output contract"
        }
        return engine.decide(
            AiReplyDecisionInput(
                context = request.context,
                contactIdentityVerified = request.constraints.contactIdentityVerified,
                cooldownActive = request.constraints.cooldownActive,
                requestId = request.requestId,
                personalization = request.constraints.personalization,
                promptOnlyJsonSchema = request.responseMode == ModelResponseMode.PROMPT_ONLY_JSON,
                responseMode = request.responseMode
            )
        )
    }
}
