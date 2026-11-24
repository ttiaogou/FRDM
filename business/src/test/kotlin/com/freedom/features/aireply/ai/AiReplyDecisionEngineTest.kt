package com.freedom.features.aireply.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReplyDecisionEngineTest {
    private class FakeProvider(private val output: String) : AiModelProvider {
        var callCount = 0
        var lastRequest: AiModelRequest? = null

        override fun generate(request: AiModelRequest): AiModelResult {
            callCount++
            lastRequest = request
            return AiModelResult(output, "fake", "test")
        }
    }

    private val grantedConsent = AiModelCloudConsent { true }

    private fun context(content: String = "你好") = ConversationContext(
        contactId = "c1", conversationId = "v1",
        messages = listOf(AiModelMessage(AiModelMessage.Role.USER, content)),
        insertedCount = 1, duplicateCount = 0, rejectedCount = 0, hasVerifiedIncoming = true
    )

    @Test fun unverifiedIdentityRequiresHumanReview() {
        val provider = FakeProvider(replyJson("你好"))
        val engine = AiReplyDecisionEngine(provider, grantedConsent)
        val result = engine.decide(AiReplyDecisionInput(context(), contactIdentityVerified = false, requestId = "r1"))
        assertEquals(AiReplyExecutionStatus.IDENTITY_RETRYABLE, execution(result).status)
        assertEquals(0, provider.callCount)
    }

    @Test fun cloudProcessingIsDeniedUntilUserConsentIsGranted() {
        val provider = FakeProvider(replyJson("你好"))
        val engine = AiReplyDecisionEngine(provider)
        val result = engine.decide(AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r2"))
        assertEquals(AiReplyExecutionStatus.CLOUD_CONSENT_REQUIRED, execution(result).status)
        assertEquals("CLOUD_PROCESSING_CONSENT_REQUIRED", execution(result).status.reasonCode)
        assertEquals(0, provider.callCount)
    }

    @Test fun strictJsonReplyProducesDraftAndRequestsJsonSchema() {
        val provider = FakeProvider(replyJson("你好，很高兴认识你"))
        val engine = AiReplyDecisionEngine(provider, grantedConsent)
        val result = engine.decide(AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r3"))
        assertEquals(AiReplyAction.REPLY, model(result).action)
        assertEquals("你好，很高兴认识你", model(result).draft)
        val responseFormat = provider.lastRequest?.responseFormat as AiModelResponseFormat.JsonSchema
        assertTrue(responseFormat.strict)
        assertTrue(responseFormat.schema.contains("additionalProperties"))
        assertTrue(responseFormat.schema.contains("REPLY"))
    }

    @Test fun promptOnlyJsonRecoveryPreservesSchemaAndStrictLocalParsing() {
        val provider = FakeProvider(replyJson("你好"))
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "prompt-only",
                promptOnlyJsonSchema = true)
        )
        assertEquals(AiReplyAction.REPLY, model(result).action)
        val request = provider.lastRequest!!
        assertEquals(AiModelResponseFormat.PlainText, request.responseFormat)
        assertTrue(request.systemInstruction.contains("请只返回一个有效的 JSON 对象"))
        assertTrue(request.systemInstruction.contains("\"reasonDetail\""))
        assertTrue(request.systemInstruction.contains("additionalProperties"))
        assertTrue(request.systemInstruction.contains("如果对方询问你是否为人工智能，必须如实回答"))
    }

    @Test fun promptOnlyJsonRecoveryRejectsNonJsonAndDoesNotTurnItIntoDraft() {
        val provider = FakeProvider("你好")
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "prompt-only-bad",
                promptOnlyJsonSchema = true)
        )
        assertEquals(AiReplyExecutionStatus.MODEL_OUTPUT_INVALID, execution(result).status)
        assertEquals(AiModelResponseFormat.PlainText, provider.lastRequest?.responseFormat)
    }

    @Test fun plainTextFallbackAcceptsFinalTextAndStripsFiniteWrapper() {
        val provider = FakeProvider("回复：你好呀")
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "plain-text", responseMode = ModelResponseMode.PLAIN_TEXT)
        )
        assertEquals(AiReplyAction.REPLY, model(result).action)
        assertEquals("你好呀", model(result).draft)
    }

    @Test fun localSafetyBlocksSensitiveDraftWithoutBlockingOrdinaryDigits() {
        val safe = AiReplyDecisionEngine(FakeProvider(replyJson("123456")), grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "safe-digits")
        )
        assertEquals(AiReplyAction.REPLY, model(safe).action)

        val blocked = AiReplyDecisionEngine(FakeProvider(replyJson("请把验证码发给我")), grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "unsafe-draft")
        )
        assertEquals(AiReplyAction.WAIT, model(blocked).action)
        assertEquals("LOCAL_SAFETY_BLOCK", model(blocked).reason)
    }

    @Test fun defaultPersonalizationUsesDailyChatGenerationPresets() {
        val provider = FakeProvider(replyJson("收到"))
        AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r-default-personalization")
        )

        val request = provider.lastRequest!!
        assertEquals(0.4, request.temperature, 0.0)
        assertEquals(512, request.maxOutputTokens)
        assertTrue(request.systemInstruction.contains("轻微、克制的暧昧和试探感"))
        assertTrue(request.systemInstruction.contains("尽量用最少的文字直接回答"))
        assertTrue(request.systemInstruction.contains("根据语境自然表达关心或积极态度"))
        assertTrue(request.systemInstruction.contains("偶尔加入轻松幽默"))
    }

    @Test fun personalizationChangesProviderNeutralInstructionsWithoutChangingGenerationParameters() {
        val provider = FakeProvider(replyJson("收到"))
        val personalization = AiReplyPersonalization(
            personalityStyle = PersonalityStyle.GENTLE_HEALING,
            languageStyle = LanguageStyle.CONCISE,
            replyLength = ReplyLength.DETAILED,
            emotionExpression = EmotionExpression.HIGHLY_EMPATHETIC,
            humorLevel = HumorLevel.SERIOUS,
            interactionLevel = InteractionLevel.ASK_QUESTIONS,
            customInstructions = "不要刻意讨好我。"
        )

        AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(
                context = context(),
                contactIdentityVerified = true,
                requestId = "r-personalization",
                personalization = personalization
            )
        )

        val request = provider.lastRequest!!
        assertEquals(AiReplyDecisionEngine.DEFAULT_TEMPERATURE, request.temperature, 0.0)
        assertEquals(AiReplyDecisionEngine.DEFAULT_MAX_OUTPUT_TOKENS, request.maxOutputTokens)
        assertTrue(request.systemInstruction.contains("表达温和、耐心、有安抚感"))
        assertTrue(request.systemInstruction.contains("减少铺垫和重复"))
        assertTrue(request.systemInstruction.contains("充分解释"))
        assertTrue(request.systemInstruction.contains("以有同理心的方式回应"))
        assertTrue(request.systemInstruction.contains("保持严肃、认真"))
        assertTrue(request.systemInstruction.contains("提出简短、相关的问题"))
        assertTrue(request.systemInstruction.contains("不要刻意讨好我。"))
    }

    @Test fun baseInstructionDoesNotCarryStructuredOutputRules() {
        val instruction = AiReplyDecisionEngine.DEFAULT_SYSTEM_INSTRUCTION
        assertFalse(instruction.contains("reasonDetail"))
        assertFalse(instruction.contains("JSON Schema"))
        assertFalse(instruction.contains("\\n"))
    }

    @Test fun jsonEscapedNewlineParsesIntoSeparateMessages() {
        val provider = FakeProvider("""{"action":"REPLY","reason":"GREETING","draft":"第一条\n第二条"}""")
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r-newline")
        )
        assertEquals(AiReplyAction.REPLY, model(result).action)
        assertEquals("第一条\n第二条", model(result).draft)
        assertEquals(listOf("第一条", "第二条"), ReplyMessageSegments.split(model(result).draft!!))
    }

    @Test fun systemInstructionDoesNotRequireFalseClaimsAboutAiIdentity() {
        val instruction = AiReplyDecisionEngine.DEFAULT_SYSTEM_INSTRUCTION
        assertTrue(instruction.contains("如果对方询问你是否为人工智能，必须如实回答"))
    }

    @Test fun personalIdentifiersAreRedactedBeforeProviderCall() {
        val provider = FakeProvider(replyJson("收到"))
        val engine = AiReplyDecisionEngine(provider, grantedConsent)
        engine.decide(
            AiReplyDecisionInput(
                context("电话 13800138000，邮箱 test@example.com，证件 11010519491231002X"),
                contactIdentityVerified = true,
                requestId = "r4"
            )
        )
        val sentContent = provider.lastRequest!!.conversation.single().content
        assertFalse(sentContent.contains("13800138000"))
        assertFalse(sentContent.contains("test@example.com"))
        assertFalse(sentContent.contains("11010519491231002X"))
        assertTrue(sentContent.contains("[手机号已脱敏]"))
        assertTrue(sentContent.contains("[邮箱已脱敏]"))
        assertTrue(sentContent.contains("[身份证号已脱敏]"))
    }

    @Test fun invalidModelProtocolRequiresHumanReview() {
        val provider = FakeProvider("Sure, I can help.")
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r5")
        )
        assertEquals(AiReplyExecutionStatus.MODEL_OUTPUT_INVALID, execution(result).status)
        assertEquals("MODEL_OUTPUT_SCHEMA_INVALID", execution(result).status.reasonCode)
    }

    @Test fun unknownOrDuplicateJsonPropertiesAreRejected() {
        val unknown = """{"action":"REPLY","reason":"OK","draft":"hi","extra":true}"""
        val duplicate = """{"action":"REPLY","action":"WAIT","reason":"OK","draft":null}"""
        listOf(unknown, duplicate).forEachIndexed { index, output ->
            val result = AiReplyDecisionEngine(FakeProvider(output), grantedConsent).decide(
                AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r6-$index")
            )
            assertEquals(AiReplyExecutionStatus.MODEL_OUTPUT_INVALID, execution(result).status)
            assertEquals("MODEL_OUTPUT_SCHEMA_INVALID", execution(result).status.reasonCode)
        }
    }

    @Test fun nonReplyActionMustHaveNullDraft() {
        val output = """{"action":"WAIT","reason":"NOT_READY","draft":"please wait"}"""
        val result = AiReplyDecisionEngine(FakeProvider(output), grantedConsent).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r7")
        )
        assertEquals(AiReplyExecutionStatus.MODEL_OUTPUT_INVALID, execution(result).status)
    }

    @Test fun replyDraftOverConfiguredLimitIsRejectedRatherThanTruncated() {
        val provider = FakeProvider(replyJson("12345"))
        val result = AiReplyDecisionEngine(provider, grantedConsent, maxDraftChars = 4).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r8")
        )
        assertEquals(AiReplyExecutionStatus.MODEL_OUTPUT_INVALID, execution(result).status)
        assertTrue(result is AiReplyEvaluation.Execution)
    }

    @Test fun removedReviewGateDoesNotOverrideModel() {
        val provider = FakeProvider(replyJson("hi"))
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "r9")
        )
        assertEquals(AiReplyAction.REPLY, model(result).action)
        assertEquals("hi", model(result).draft)
        assertEquals(1, provider.callCount)
    }

    @Test fun highConsequenceWaitPreservesDetailedModelExplanationAndMetadata() {
        val provider = object : AiModelProvider {
            override fun generate(request: AiModelRequest) = AiModelResult(
                """{"action":"WAIT","reason":"FINANCIAL_TRANSACTION_REVIEW","reasonDetail":"涉及转账或借贷等高后果事项，停止自动发送。","draft":null}""",
                "fake", "test", TokenUsage(40, 30), "stop")
        }
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "detailed"))
        assertEquals(AiReplyAction.WAIT, model(result).action)
        assertTrue(model(result).reasonDetail!!.contains("涉及转账或借贷等高后果事项"))
        assertEquals("stop", model(result).finishReason)
        assertEquals(30, model(result).usage?.outputTokens)
        assertNull(model(result).draft)
    }

    @Test fun explanationIsRequiredBySchemaAndMalformedExplanationIsRejected() {
        val provider = FakeProvider(replyJson("你好"))
        AiReplyDecisionEngine(provider, grantedConsent).decide(AiReplyDecisionInput(context(), true, requestId = "schema-detail"))
        val schema = (provider.lastRequest!!.responseFormat as AiModelResponseFormat.JsonSchema).schema
        assertTrue(schema.contains("\"required\": [\"action\", \"reason\", \"reasonDetail\", \"draft\"]"))
        for (detail in listOf("null", "\"\"", "\"${"x".repeat(1001)}\"")) {
            val output = """{"action":"WAIT","reason":"AMBIGUOUS","reasonDetail":$detail,"draft":null}"""
            val result = AiReplyDecisionEngine(FakeProvider(output), grantedConsent).decide(
                AiReplyDecisionInput(context(), true, requestId = "bad-detail"))
            assertEquals("MODEL_OUTPUT_SCHEMA_INVALID", execution(result).status.reasonCode)
        }
    }


    @Test fun safeAmbiguousInputCanYieldLowCommitmentClarification() {
        val provider = FakeProvider(replyJson("你说的是哪件事呀？"))
        val outcome = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context("这个"), true, requestId = "safe-ambiguous")
        )
        assertEquals(AiReplyAction.REPLY, model(outcome).action)
        assertEquals("你说的是哪件事呀？", model(outcome).draft)
        assertTrue(provider.lastRequest!!.systemInstruction.contains("低承诺"))
    }

    @Test fun parserDiagnosticReasonsDoNotChangeValidationOrDraft() {
        val cases = listOf(
            "not-json" to AiReplyOutputInvalidReason.JSON_SYNTAX_INVALID,
            """{"action":"REPLY","reason":"OK"}""" to AiReplyOutputInvalidReason.JSON_REQUIRED_FIELD_MISSING,
            """{"action":"REPLY","reason":"OK","draft":"hi","extra":"value"}""" to AiReplyOutputInvalidReason.JSON_SCHEMA_MISMATCH,
            """{"action":"UNKNOWN","reason":"OK","draft":"hi"}""" to AiReplyOutputInvalidReason.JSON_ACTION_INVALID,
            """{"action":"REPLY","reason":"invalid reason","draft":"hi"}""" to AiReplyOutputInvalidReason.JSON_REASON_INVALID,
            """{"action":"REPLY","reason":"OK","draft":null}""" to AiReplyOutputInvalidReason.JSON_DRAFT_EMPTY,
            """{"action":"REPLY","reason":"OK","draft":" hello "}""" to AiReplyOutputInvalidReason.JSON_DRAFT_INVALID,
            """{"action":"WAIT","reason":"OK","reasonDetail":"","draft":null}""" to AiReplyOutputInvalidReason.JSON_REASON_DETAIL_INVALID
        )
        for ((output, expected) in cases) {
            val rejected = execution(AiReplyDecisionEngine(FakeProvider(output), grantedConsent).decide(
                AiReplyDecisionInput(context(), true, requestId = "invalid-output")))
            assertEquals(AiReplyExecutionStatus.MODEL_OUTPUT_INVALID, rejected.status)
            assertEquals(expected, rejected.outputInvalidReason)
            assertEquals("PRESENT", rejected.outputContentState)
        }
        val tooLong = AiReplyDecisionEngine(FakeProvider(replyJson("12345")), grantedConsent, maxDraftChars = 4)
            .decide(AiReplyDecisionInput(context(), true, requestId = "too-long"))
        assertEquals(AiReplyOutputInvalidReason.JSON_DRAFT_TOO_LONG, execution(tooLong).outputInvalidReason)

        // Preserve existing compatibility: reasonDetail may be absent in locally accepted responses.
        val accepted = AiReplyDecisionEngine(FakeProvider(replyJson("你好")), grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "legacy-detail"))
        assertEquals(AiReplyAction.REPLY, model(accepted).action)
        assertNull(model(accepted).reasonDetail)
    }

    @Test fun directProviderBlankOutputRetainsEmptyStatusAndAddsContentState() {
        val rejected = execution(AiReplyDecisionEngine(FakeProvider("  "), grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "direct-empty")))
        assertEquals(AiReplyExecutionStatus.MODEL_EMPTY_OUTPUT, rejected.status)
        assertEquals("BLANK", rejected.outputContentState)
    }

    private fun model(result: AiReplyEvaluation): AiReplyDecision =
        (result as AiReplyEvaluation.Model).decision

    private fun execution(result: AiReplyEvaluation): AiReplyEvaluation.Execution =
        result as AiReplyEvaluation.Execution

    @Test fun programFailureNeverMasqueradesAsModelWait() {
        val provider = object : AiModelProvider {
            override fun generate(request: AiModelRequest): AiModelResult =
                error("temporary transport failure")
        }
        val outcome = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "technical")
        )
        val technical = execution(outcome)
        assertEquals(AiReplyExecutionStatus.MODEL_CALL_FAILED, technical.status)
        assertNotNull(technical.callDiagnostics)
    }

    @Test fun modelWaitReasonCannotSelectProgramFailureStatus() {
        val provider = FakeProvider(
            """{"action":"WAIT","reason":"MODEL_CALL_FAILED","reasonDetail":"涉及资金转账，需要人工确认。","draft":null}"""
        )
        val outcome = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "model-wait")
        )
        assertEquals(AiReplyAction.WAIT, model(outcome).action)
        assertEquals("MODEL_CALL_FAILED", model(outcome).reason)
        assertEquals(1, provider.callCount)
    }

    @Test fun consentRevokedDuringProviderCallIsProgramConsentStatus() {
        val provider = object : AiModelProvider {
            override fun generate(request: AiModelRequest): AiModelResult =
                error("Cloud model processing consent has not been granted")
        }
        val outcome = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "revoked")
        )
        assertEquals(AiReplyExecutionStatus.CLOUD_CONSENT_REQUIRED, execution(outcome).status)
    }

    private fun replyJson(draft: String) =
        """{"action":"REPLY","reason":"GREETING","draft":"${draft.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""

    @Test fun finalOutboundGateSanitizesDirectMixedContextsBeforeModelCall() {
        val provider = FakeProvider(replyJson("你好"))
        val context = context().copy(messages = listOf(
            AiModelMessage(AiModelMessage.Role.USER, "早上好😊"),
            AiModelMessage(AiModelMessage.Role.ASSISTANT, "昨天见"),
            AiModelMessage(AiModelMessage.Role.USER, "在吗[表情/图形未识别]"),
            AiModelMessage(AiModelMessage.Role.USER, "今晚有空吗")
        ))
        AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context, true, requestId = "p3-final-safe")
        )
        assertEquals(1, provider.callCount)
        assertEquals(listOf("早上好", "昨天见", "今晚有空吗"),
            provider.lastRequest!!.conversation.map { it.content })
    }

    @Test fun finalOutboundGateRefusesContextWithOnlyVisualPlaceholders() {
        val provider = FakeProvider(replyJson("你好"))
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context("[图形/符号内容待确认] 在吗a"),
                true, requestId = "p3-no-unverified")
        )
        assertEquals(AiReplyExecutionStatus.EMPTY_CONTEXT, execution(result).status)
        assertEquals(0, provider.callCount)
    }

    @Test fun finalOutboundGateRejectsOnlyOutgoingHistoryAfterFiltering() {
        val provider = FakeProvider(replyJson("你好"))
        val context = context().copy(messages = listOf(
            AiModelMessage(AiModelMessage.Role.USER, "😊"),
            AiModelMessage(AiModelMessage.Role.ASSISTANT, "你好呀")
        ))
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context, true, requestId = "p3-no-user")
        )
        assertEquals(AiReplyExecutionStatus.MESSAGE_EVIDENCE_UNAVAILABLE, execution(result).status)
        assertEquals(0, provider.callCount)
    }


}
