package com.freedom.features.aireply.ai

/** 引擎只生成决策或草稿，不包含界面和发送能力。 */
class AiReplyDecisionEngine(
    private val modelProvider: AiModelProvider,
    private val cloudConsent: AiModelCloudConsent = AiModelCloudConsent.DENY_ALL,
    private val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,
    private val maxDraftChars: Int = 2_000
) {
    init { require(systemInstruction.isNotBlank()); require(maxDraftChars in 1..10_000) }

    fun decide(input: AiReplyDecisionInput): AiReplyEvaluation = decide(input, false)

    private fun decide(input: AiReplyDecisionInput, transcriptMode: Boolean): AiReplyEvaluation {
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
            // The provider transport owns the schema when structured JSON is enabled.
            // Keeping it out of the business-layer prompt prevents DeepSeek from
            // receiving the same schema twice.
            ModelResponseMode.SIMPLE_JSON -> SIMPLE_JSON_INSTRUCTION
            ModelResponseMode.PROMPT_ONLY_JSON -> PROMPT_ONLY_JSON_INSTRUCTION + "\n$jsonSchema"
            ModelResponseMode.PLAIN_TEXT -> PLAIN_TEXT_INSTRUCTION
            else -> STRICT_JSON_INSTRUCTION
        }
        val redactedConversation = AiModelDataRedactor.redactMessages(safeConversation)
        val outboundConversation = if (transcriptMode) listOf(AiModelMessage(
            AiModelMessage.Role.USER,
            redactedConversation.joinToString("\n") { message ->
                val label = if (message.role == AiModelMessage.Role.USER) "对方(USER)" else "己方(ASSISTANT)"
                "$label：${message.content}"
            }
        )) else redactedConversation
        val request = AiModelRequest(
            systemInstruction = AiModelDataRedactor.redact(
                "$systemInstruction\n\n${input.personalization.systemPromptInstructions()}\n\n$schemaPromptSuffix"
            ),
            conversation = outboundConversation,
            // 保持既有生成参数与个性化偏好解耦；偏好以自然语言提示传递，
            // 不映射为供应商专属参数。
            temperature = DEFAULT_TEMPERATURE,
            maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
            requestId = input.requestId,
            attemptIndex = input.attemptIndex,
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
        val parsed = AiReplyDecisionJsonParser.parseWithDiagnostics(
            raw, maxDraftChars, allowSimple = responseMode == ModelResponseMode.SIMPLE_JSON
        )
        val decision = parsed.decision ?: if (responseMode == ModelResponseMode.PLAIN_TEXT) {
            AiReplyDecisionJsonParser.extractPlainTextDraft(raw, maxDraftChars)?.let {
                AiReplyDecision(AiReplyAction.REPLY, "PLAIN_TEXT_FALLBACK", it)
            }
        } else null
        if (decision == null) return AiReplyEvaluation.Execution(
            AiReplyExecutionStatus.MODEL_OUTPUT_INVALID,
            provider = result.provider,
            model = result.model,
            outputInvalidReason = parsed.invalidReason,
            outputContentState = "PRESENT"
        )
        if (decision.action == AiReplyAction.REPLY &&
            !AiReplyLocalSafety.isSafeDraft(decision.draft.orEmpty())
        ) {
            return AiReplyEvaluation.Model(
                AiReplyDecision(AiReplyAction.WAIT, "LOCAL_SAFETY_BLOCK", reasonDetail = "本地安全规则阻止自动发送")
            )
        }
        return AiReplyEvaluation.Model(
            decision.copy(provider = result.provider, model = result.model, finishReason = result.finishReason, usage = result.usage)
        )
    }

    /**
     * Explicit, read-only replay of one user-entered transcript. Never returns a draft or
     * invokes the task coordinator. The normal decision entry always uses the original array.
     */
    internal fun runContextFormatExperiment(
        transcript: String,
        personalization: AiReplyPersonalization,
        isCancelled: () -> Boolean,
        onLog: (String) -> Unit
    ) {
        val messages = transcript.lineSequence().filter { it.isNotBlank() }.map { line ->
            val match = Regex("^([UA])[:：]\\s*(.+)$").matchEntire(line.trim())
                ?: throw IllegalArgumentException("每条消息须以 U:（对方）或 A:（己方）开头")
            val role = if (match.groupValues[1] == "U") AiModelMessage.Role.USER else AiModelMessage.Role.ASSISTANT
            AiModelMessage(role, match.groupValues[2])
        }.toList()
        require(messages.size in 2..30 && messages.all { it.content.length <= 2_000 }) {
            "请输入 2～30 条消息，每条不超过 2000 字符"
        }
        require(messages.last().role == AiModelMessage.Role.USER && messages.any { it.role == AiModelMessage.Role.ASSISTANT }) {
            "需包含己方消息，且末条必须为对方消息"
        }
        val safe = AiSafeConversationText.outbound(messages)
        require(safe.size == messages.size && !AiReplyLocalSafety.isDangerous(safe.last().content)) {
            "样本未通过现有正文或本地安全校验"
        }
        require(personalization.validationError() == null) { "个性化配置无效" }
        val experimentId = java.util.UUID.randomUUID().toString()
        val context = ConversationContext("diagnostic", "diagnostic", safe, 0, 0, 0, true, currentWindowDraft = true)
        onLog("AI_CONTEXT_EXPERIMENT_START experimentId=$experimentId messages=${safe.size} roles=${safe.joinToString(",") { it.role.name }} repeats=3")
        // Interleave groups to reduce time/order effects; each attempt is independent.
        for (repeat in 1..3) {
            for ((group, mode, flattened) in listOf(
                Triple("A", ModelResponseMode.STRICT_JSON, false),
                Triple("B", ModelResponseMode.STRICT_JSON, true),
                Triple("C", ModelResponseMode.PLAIN_TEXT, false),
                Triple("D", ModelResponseMode.PLAIN_TEXT, true)
            )) {
                if (isCancelled() || !cloudConsent.isGranted()) {
                    onLog("AI_CONTEXT_EXPERIMENT_STOP experimentId=$experimentId reason=CANCELLED_OR_CONSENT_REVOKED")
                    return
                }
                val requestId = "$experimentId-$group-$repeat"
                onLog("AI_CONTEXT_EXPERIMENT_ATTEMPT requestId=$requestId group=$group repeat=$repeat responseMode=${mode.name} flattened=$flattened")
                val result = decide(AiReplyDecisionInput(
                    context, contactIdentityVerified = false, requestId = requestId,
                    personalization = personalization, responseMode = mode, attemptIndex = 0
                ), flattened)
                val summary = when (result) {
                    is AiReplyEvaluation.Model -> if (result.decision.provider == null) {
                        "outcome=LOCAL_SAFETY_BLOCK"
                    } else {
                        "outcome=VALID_DECISION action=${result.decision.action.name}"
                    }
                    is AiReplyEvaluation.Execution ->
                        "outcome=${result.callDiagnostics?.safeCode ?: result.status.reasonCode}"
                }
                onLog("AI_CONTEXT_EXPERIMENT_RESULT requestId=$requestId group=$group repeat=$repeat $summary")
            }
        }
        onLog("AI_CONTEXT_EXPERIMENT_END experimentId=$experimentId attempts=12")
    }

    companion object {
        const val DEFAULT_TEMPERATURE = 0.4
        const val DEFAULT_MAX_OUTPUT_TOKENS = 256
        const val DEFAULT_SYSTEM_INSTRUCTION =
            "你是说话自然的中文聊天助手，只能根据提供的聊天记录回复。" +
                "不得编造事实、猜测未提供的信息或声称已经执行任何操作。" +
                "根据上下文选择最值得回应的内容，无需逐条回应。" +
                "默认回复一条；确需分开发送时，每条独立消息各占一行，同一条消息内不要换行。" +
                "不主动暴露身份，但如果对方询问你是否为人工智能，必须如实回答。" 

        private const val STRICT_JSON_INSTRUCTION =
            "请根据聊天内容做出回复决策并返回接口要求的完整 JSON。" +
                "可安全回复时使用 REPLY 并填写 draft；不适合自动回复时使用 WAIT 或 DO_NOT_REPLY，draft 为空。"

        private const val SIMPLE_JSON_INSTRUCTION =
            "请结合本轮对方消息和上下文，生成简短、低承诺的中文回复。" +
                "只返回 action 和 draft 两个字段；可以回复时 action 为 REPLY，否则为 WAIT 且 draft 为空。"

        private const val PROMPT_ONLY_JSON_INSTRUCTION =
            "请只返回一个有效的 JSON 对象，不要使用 Markdown、解释或前后缀。"

        private const val PLAIN_TEXT_INSTRUCTION =
            "请结合本轮对方消息和上下文，直接生成自然、简短、低承诺的中文回复。" +
                "只输出最终回复文字，不要输出 JSON、Markdown、解释或前后缀。"
    }
}

/** Local last-mile guard; intentionally narrow so normal colloquial or flirtatious chat is not blocked. */
internal object AiReplyLocalSafety {
    private val dangerousIncoming = Regex("(验证码|短信码|密码|银行卡|转账|借贷|借钱|收款码|私钥|助记词|身份证号|精确住址|自残|自杀|伤害自己|威胁|勒索)")
    private val sensitiveDraft = Regex(
        "(验证码|密码|银行卡号|卡号|转账给我|收款码|私钥|助记词|身份证号|精确住址|邮箱|电子邮箱|手机号|" +
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)|" +
            "(?<!\\d)\\d{17}[0-9Xx](?!\\d)|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,})"
    )

    fun isDangerous(text: String): Boolean = dangerousIncoming.containsMatchIn(text)
    fun isSafeDraft(text: String): Boolean = text.isNotBlank() && !sensitiveDraft.containsMatchIn(text)
}

data class AiReplyDecisionInput(
    val context: ConversationContext,
    val contactIdentityVerified: Boolean,
    val cooldownActive: Boolean = false,
    val requestId: String,
    val personalization: AiReplyPersonalization = AiReplyPersonalization(),
    val promptOnlyJsonSchema: Boolean = false,
    val responseMode: ModelResponseMode = ModelResponseMode.STRICT_JSON,
    val attemptIndex: Int = 0
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
