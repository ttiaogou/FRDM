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
        assertEquals(AiReplyAction.WAIT, result.action)
        assertEquals(0, provider.callCount)
    }

    @Test fun cloudProcessingIsDeniedUntilUserConsentIsGranted() {
        val provider = FakeProvider(replyJson("你好"))
        val engine = AiReplyDecisionEngine(provider)
        val result = engine.decide(AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r2"))
        assertEquals(AiReplyAction.WAIT, result.action)
        assertEquals("CLOUD_PROCESSING_CONSENT_REQUIRED", result.reason)
        assertEquals(0, provider.callCount)
    }

    @Test fun strictJsonReplyProducesDraftAndRequestsJsonSchema() {
        val provider = FakeProvider(replyJson("你好，很高兴认识你"))
        val engine = AiReplyDecisionEngine(provider, grantedConsent)
        val result = engine.decide(AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r3"))
        assertEquals(AiReplyAction.REPLY, result.action)
        assertEquals("你好，很高兴认识你", result.draft)
        val responseFormat = provider.lastRequest?.responseFormat as AiModelResponseFormat.JsonSchema
        assertTrue(responseFormat.strict)
        assertTrue(responseFormat.schema.contains("additionalProperties"))
        assertTrue(responseFormat.schema.contains("REPLY"))
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

    @Test fun multiReplyInstructionUsesOnlySingleNewlines() {
        val instruction = AiReplyDecisionEngine.DEFAULT_SYSTEM_INSTRUCTION
        assertTrue(instruction.contains("多条回复之间必须采用单一换行分隔"))
        assertTrue(instruction.contains("在 JSON 字符串中用 \\n 表示换行"))
        assertFalse(instruction.contains("<MSG>"))
    }

    @Test fun jsonEscapedNewlineParsesIntoSeparateMessages() {
        val provider = FakeProvider("""{"action":"REPLY","reason":"GREETING","draft":"第一条\n第二条"}""")
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r-newline")
        )
        assertEquals(AiReplyAction.REPLY, result.action)
        assertEquals("第一条\n第二条", result.draft)
        assertEquals(listOf("第一条", "第二条"), ReplyMessageSegments.split(result.draft!!))
    }

    @Test fun systemInstructionDoesNotRequireFalseClaimsAboutAiIdentity() {
        val instruction = AiReplyDecisionEngine.DEFAULT_SYSTEM_INSTRUCTION
        assertTrue(instruction.contains("如果对方直接询问你是否为人工智能，必须如实回答"))
        assertTrue(instruction.contains("不得谎称自己是人类"))
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
        assertEquals(AiReplyAction.WAIT, result.action)
        assertEquals("MODEL_OUTPUT_SCHEMA_INVALID", result.reason)
    }

    @Test fun unknownOrDuplicateJsonPropertiesAreRejected() {
        val unknown = """{"action":"REPLY","reason":"OK","draft":"hi","extra":true}"""
        val duplicate = """{"action":"REPLY","action":"WAIT","reason":"OK","draft":null}"""
        listOf(unknown, duplicate).forEachIndexed { index, output ->
            val result = AiReplyDecisionEngine(FakeProvider(output), grantedConsent).decide(
                AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r6-$index")
            )
            assertEquals(AiReplyAction.WAIT, result.action)
            assertEquals("MODEL_OUTPUT_SCHEMA_INVALID", result.reason)
        }
    }

    @Test fun nonReplyActionMustHaveNullDraft() {
        val output = """{"action":"WAIT","reason":"NOT_READY","draft":"please wait"}"""
        val result = AiReplyDecisionEngine(FakeProvider(output), grantedConsent).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r7")
        )
        assertEquals(AiReplyAction.WAIT, result.action)
    }

    @Test fun replyDraftOverConfiguredLimitIsRejectedRatherThanTruncated() {
        val provider = FakeProvider(replyJson("12345"))
        val result = AiReplyDecisionEngine(provider, grantedConsent, maxDraftChars = 4).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r8")
        )
        assertEquals(AiReplyAction.WAIT, result.action)
        assertNull(result.draft)
    }

    @Test fun removedReviewGateDoesNotOverrideModel() {
        val provider = FakeProvider(replyJson("hi"))
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "r9")
        )
        assertEquals(AiReplyAction.REPLY, result.action)
        assertEquals("hi", result.draft)
        assertEquals(1, provider.callCount)
    }

    @Test fun ambiguousDecisionPreservesDetailedModelExplanationAndMetadata() {
        val provider = object : AiModelProvider {
            override fun generate(request: AiModelRequest) = AiModelResult(
                """{"action":"WAIT","reason":"SEXUAL_INNUENDO_AMBIGUOUS","reasonDetail":"表达存在歧义，停止自动发送。","draft":null}""",
                "fake", "test", TokenUsage(40, 30), "stop")
        }
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), true, requestId = "detailed"))
        assertEquals(AiReplyAction.WAIT, result.action)
        assertTrue(result.reasonDetail!!.contains("表达存在歧义，停止自动发送。"))
        assertEquals("stop", result.finishReason)
        assertEquals(30, result.usage?.outputTokens)
        assertNull(result.draft)
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
            assertEquals("MODEL_OUTPUT_SCHEMA_INVALID", result.reason)
        }
    }

    private fun replyJson(draft: String) =
        """{"action":"REPLY","reason":"GREETING","draft":"${draft.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
}
