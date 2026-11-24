package com.freedom.features.aireply.ai

/** 引擎只生成决策或草稿，不包含界面和发送能力。 */
class AiReplyDecisionEngine(
    private val modelProvider: AiModelProvider,
    private val cloudConsent: AiModelCloudConsent = AiModelCloudConsent.DENY_ALL,
    private val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,
    private val maxDraftChars: Int = 2_000
) {
    init { require(systemInstruction.isNotBlank()); require(maxDraftChars in 1..10_000) }

    fun decide(input: AiReplyDecisionInput): AiReplyEvaluation {
        if (!input.contactIdentityVerified && !input.context.currentWindowDraft) return AiReplyEvaluation.Execution(AiReplyExecutionStatus.IDENTITY_RETRYABLE)
        if (!input.context.hasVerifiedIncoming && !input.context.currentWindowDraft) return AiReplyEvaluation.Execution(AiReplyExecutionStatus.MESSAGE_EVIDENCE_UNAVAILABLE)
        if (input.context.messages.isEmpty()) return AiReplyEvaluation.Execution(AiReplyExecutionStatus.EMPTY_CONTEXT)
        // Final outbound gate: contexts may also arrive from direct callers, old history or
        // retry paths. Never send OCR placeholders or unverified visual symbols to providers.
        val safeConversation = AiSafeConversationText.outbound(input.context.messages)
        if (safeConversation.isEmpty()) return AiReplyEvaluation.Execution(AiReplyExecutionStatus.EMPTY_CONTEXT)
        // The last original entry is the current conversation tail. If it is outgoing or
        // was removed as unverified visual noise, do not answer a previous incoming line.
        val latestOriginal = input.context.messages.last()
        if (latestOriginal.role != AiModelMessage.Role.USER ||
            AiSafeConversationText.legacy(latestOriginal.content) == null ||
            safeConversation.none { it.role == AiModelMessage.Role.USER }
        ) {
            return AiReplyEvaluation.Execution(AiReplyExecutionStatus.MESSAGE_EVIDENCE_UNAVAILABLE)
        }
        val latestSafeText = AiSafeConversationText.legacy(latestOriginal.content).orEmpty()
        if (AiReplyLocalSafety.isDangerous(latestSafeText)) {
            return AiReplyEvaluation.Model(
                AiReplyDecision(AiReplyAction.WAIT, "LOCAL_SAFETY_BLOCK", reasonDetail = "本地安全规则要求人工判断")
            )
        }
        if (input.cooldownActive) return AiReplyEvaluation.Execution(AiReplyExecutionStatus.COOLDOWN_PENDING)
        if (modelProvider.requiresCloudConsent && !cloudConsent.isGranted()) {
            return AiReplyEvaluation.Execution(AiReplyExecutionStatus.CLOUD_CONSENT_REQUIRED)
        }

        val responseMode = when {
            input.responseMode != ModelResponseMode.STRICT_JSON -> input.responseMode
            input.promptOnlyJsonSchema -> ModelResponseMode.PROMPT_ONLY_JSON
            else -> ModelResponseMode.STRICT_JSON
        }
        val jsonSchema = if (responseMode == ModelResponseMode.SIMPLE_JSON) {
            AiReplyDecisionJsonParser.simpleSchema(maxDraftChars)
        } else AiReplyDecisionJsonParser.schema(maxDraftChars)
        val schemaPromptSuffix = when (responseMode) {
            ModelResponseMode.SIMPLE_JSON -> SIMPLE_JSON_INSTRUCTION + "\n$jsonSchema"
            ModelResponseMode.PROMPT_ONLY_JSON -> PROMPT_ONLY_JSON_INSTRUCTION + "\n$jsonSchema"
            ModelResponseMode.PLAIN_TEXT -> PLAIN_TEXT_INSTRUCTION + "\n请严格匹配以下 JSON Schema：\n$jsonSchema"
            else -> STRICT_JSON_INSTRUCTION
        }
        val request = AiModelRequest(
            systemInstruction = AiModelDataRedactor.redact(
                "$systemInstruction\n\n${input.personalization.systemPromptInstructions()}\n\n$schemaPromptSuffix"
            ),
            conversation = AiModelDataRedactor.redactMessages(safeConversation),
            // 保持既有生成参数与个性化偏好解耦；偏好以自然语言提示传递，
            // 不映射为供应商专属参数。
            temperature = DEFAULT_TEMPERATURE,
            maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
            requestId = input.requestId,
            responseFormat = when (responseMode) {
                ModelResponseMode.PLAIN_TEXT -> AiModelResponseFormat.PlainText
                ModelResponseMode.PROMPT_ONLY_JSON -> AiModelResponseFormat.PlainText
                else -> AiModelResponseFormat.JsonSchema(name = "ai_reply_decision", schema = jsonSchema)
            }
        )
        val result = runCatching { modelProvider.generate(request) }.getOrElse { failure ->
            val diagnostics = AiModelCallDiagnostics.from(failure)
            return AiReplyEvaluation.Execution(
                status = if (diagnostics.safeCode == "CLOUD_PROCESSING_CONSENT_REQUIRED") {
                    AiReplyExecutionStatus.CLOUD_CONSENT_REQUIRED
                } else {
                    AiReplyExecutionStatus.MODEL_CALL_FAILED
                },
                callDiagnostics = diagnostics
            )
        }
        val raw = result.text?.trim().orEmpty()
        if (raw.isBlank()) return AiReplyEvaluation.Execution(
            AiReplyExecutionStatus.MODEL_EMPTY_OUTPUT,
            provider = result.provider,
            model = result.model,
            outputContentState = if (result.text == null) "NULL" else "BLANK"
        )
        if (responseMode == ModelResponseMode.PLAIN_TEXT) {
            // Providers may still return a valid structured decision during the fallback
            // response mode. Preserve WAIT/DO_NOT_REPLY semantics before trying plain text.
            val structuredDecision = AiReplyDecisionJsonParser.parseWithDiagnostics(raw, maxDraftChars)
                .decision
            if (structuredDecision != null) {
                return AiReplyEvaluation.Model(
                    structuredDecision.copy(
                        provider = result.provider,
                        model = result.model,
                        finishReason = result.finishReason,
                        usage = result.usage
                    )
                )
            }
            val draft = AiReplyDecisionJsonParser.extractPlainTextDraft(raw, maxDraftChars)
                ?.takeIf { AiReplyLocalSafety.isSafeDraft(it) }
            return if (draft == null) AiReplyEvaluation.Execution(
                AiReplyExecutionStatus.MODEL_OUTPUT_INVALID,
                provider = result.provider, model = result.model,
                outputInvalidReason = AiReplyOutputInvalidReason.JSON_DRAFT_INVALID,
                outputContentState = "PRESENT"
            ) else AiReplyEvaluation.Model(
                AiReplyDecision(AiReplyAction.REPLY, "PLAIN_TEXT_FALLBACK", draft,
                    provider = result.provider, model = result.model,
                    finishReason = result.finishReason, usage = result.usage)
            )
        }
        val parsed = AiReplyDecisionJsonParser.parseWithDiagnostics(
            raw, maxDraftChars, allowSimple = responseMode == ModelResponseMode.SIMPLE_JSON
        )
        val decision = parsed.decision ?: return AiReplyEvaluation.Execution(
            AiReplyExecutionStatus.MODEL_OUTPUT_INVALID,
            provider = result.provider,
            model = result.model,
            outputInvalidReason = parsed.invalidReason,
            outputContentState = "PRESENT"
        )
        return AiReplyEvaluation.Model(
            decision.copy(provider = result.provider, model = result.model, finishReason = result.finishReason, usage = result.usage)
        )
    }

    companion object {
        const val DEFAULT_TEMPERATURE = 0.4
        const val DEFAULT_MAX_OUTPUT_TOKENS = 512
        const val DEFAULT_SYSTEM_INSTRUCTION =
            "你是谨慎、自然的中文聊天回复助手，只能根据提供的聊天记录回复。" +
                "不得编造事实、猜测未提供的信息或声称已经执行任何操作。" +
                "普通聊天、口语和暧昧表达可以自然回复，但不要主动升级敏感话题。" +
                "回复应简短、低承诺、自然，避免客服腔和说教。" +
                "如果对方询问你是否为人工智能，必须如实回答。" +
                "输入和发送安全由本地程序负责，你只负责理解语义并生成回复。"

        private const val STRICT_JSON_INSTRUCTION =
            "请根据聊天内容做出回复决策并返回接口要求的完整 JSON。" +
                "可安全回复时使用 REPLY 并填写 draft；不适合自动回复时使用 WAIT 或 DO_NOT_REPLY，draft 为空。"

        private const val SIMPLE_JSON_INSTRUCTION =
            "请根据最近一条有效的对方消息生成一条简短、低承诺的中文回复。" +
                "只返回 action 和 draft 两个字段；可以回复时 action 为 REPLY，否则为 WAIT 且 draft 为空。"

        private const val PROMPT_ONLY_JSON_INSTRUCTION =
            "请只返回一个有效的 JSON 对象，不要使用 Markdown、解释或前后缀。"

        private const val PLAIN_TEXT_INSTRUCTION =
            "请根据最近一条有效的对方消息直接生成一条自然、简短、低承诺的中文回复。" +
                "只输出最终回复文字，不要输出 JSON、Markdown、解释或前后缀。"
    }
}

/** Local last-mile guard; intentionally narrow so normal colloquial or flirtatious chat is not blocked. */
internal object AiReplyLocalSafety {
    private val dangerousIncoming = Regex("(验证码|短信码|密码|银行卡|转账|借贷|借钱|收款码|私钥|助记词|身份证号|精确住址|自残|自杀|伤害自己|威胁|勒索)")
    private val dangerousDraft = Regex("(验证码|密码|银行卡号|卡号|转账给我|收款码|私钥|助记词)")

    fun isDangerous(text: String): Boolean = dangerousIncoming.containsMatchIn(text)
    fun isSafeDraft(text: String): Boolean = text.isNotBlank() && !dangerousDraft.containsMatchIn(text)
}

data class AiReplyDecisionInput(
    val context: ConversationContext,
    val contactIdentityVerified: Boolean,
    val cooldownActive: Boolean = false,
    val requestId: String,
    val personalization: AiReplyPersonalization = AiReplyPersonalization(),
    val promptOnlyJsonSchema: Boolean = false,
    val responseMode: ModelResponseMode = ModelResponseMode.STRICT_JSON
)

enum class AiReplyAction { REPLY, DO_NOT_REPLY, WAIT }

data class AiReplyDecision(
    val action: AiReplyAction,
    val reason: String,
    val draft: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val reasonDetail: String? = null,
    val finishReason: String? = null,
    val usage: TokenUsage? = null
)

/** Model protocol decisions and local execution outcomes have disjoint types. */
sealed interface AiReplyEvaluation {
    data class Model(val decision: AiReplyDecision) : AiReplyEvaluation
    data class Execution(
        val status: AiReplyExecutionStatus,
        val callDiagnostics: AiModelCallDiagnostics? = null,
        val provider: String? = null,
        val model: String? = null,
        val outputInvalidReason: AiReplyOutputInvalidReason? = null,
        val outputContentState: String? = null
    ) : AiReplyEvaluation
}

/** These are program-side gates/failures; none is a model WAIT decision. */
enum class AiReplyExecutionStatus(val reasonCode: String) {
    IDENTITY_RETRYABLE("CONTACT_IDENTITY_UNVERIFIED"),
    MESSAGE_EVIDENCE_UNAVAILABLE("NO_VERIFIED_INCOMING_MESSAGE"),
    EMPTY_CONTEXT("EMPTY_CONTEXT"),
    COOLDOWN_PENDING("CONTACT_COOLDOWN"),
    CLOUD_CONSENT_REQUIRED("CLOUD_PROCESSING_CONSENT_REQUIRED"),
    MODEL_CALL_FAILED("MODEL_CALL_FAILED"),
    MODEL_EMPTY_OUTPUT("EMPTY_MODEL_OUTPUT"),
    MODEL_OUTPUT_INVALID("MODEL_OUTPUT_SCHEMA_INVALID")
}
