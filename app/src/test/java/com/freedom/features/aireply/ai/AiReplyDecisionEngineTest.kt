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
        assertEquals(AiReplyAction.HUMAN_REVIEW, result.action)
        assertEquals(0, provider.callCount)
    }

    @Test fun cloudProcessingIsDeniedUntilUserConsentIsGranted() {
        val provider = FakeProvider(replyJson("你好"))
        val engine = AiReplyDecisionEngine(provider)
        val result = engine.decide(AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r2"))
        assertEquals(AiReplyAction.HUMAN_REVIEW, result.action)
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
        assertEquals(AiReplyAction.HUMAN_REVIEW, result.action)
        assertEquals("MODEL_OUTPUT_SCHEMA_INVALID", result.reason)
    }

    @Test fun unknownOrDuplicateJsonPropertiesAreRejected() {
        val unknown = """{"action":"REPLY","reason":"OK","draft":"hi","extra":true}"""
        val duplicate = """{"action":"REPLY","action":"WAIT","reason":"OK","draft":null}"""
        listOf(unknown, duplicate).forEachIndexed { index, output ->
            val result = AiReplyDecisionEngine(FakeProvider(output), grantedConsent).decide(
                AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r6-$index")
            )
            assertEquals(AiReplyAction.HUMAN_REVIEW, result.action)
            assertEquals("MODEL_OUTPUT_SCHEMA_INVALID", result.reason)
        }
    }

    @Test fun nonReplyActionMustHaveNullDraft() {
        val output = """{"action":"WAIT","reason":"NOT_READY","draft":"please wait"}"""
        val result = AiReplyDecisionEngine(FakeProvider(output), grantedConsent).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r7")
        )
        assertEquals(AiReplyAction.HUMAN_REVIEW, result.action)
    }

    @Test fun replyDraftOverConfiguredLimitIsRejectedRatherThanTruncated() {
        val provider = FakeProvider(replyJson("12345"))
        val result = AiReplyDecisionEngine(provider, grantedConsent, maxDraftChars = 4).decide(
            AiReplyDecisionInput(context(), contactIdentityVerified = true, requestId = "r8")
        )
        assertEquals(AiReplyAction.HUMAN_REVIEW, result.action)
        assertNull(result.draft)
    }

    @Test fun manualReviewOverridesModel() {
        val provider = FakeProvider(replyJson("hi"))
        val result = AiReplyDecisionEngine(provider, grantedConsent).decide(
            AiReplyDecisionInput(context(), true, manualReviewRequired = true, requestId = "r9")
        )
        assertEquals(AiReplyAction.HUMAN_REVIEW, result.action)
        assertNotNull(result.reason)
        assertEquals(0, provider.callCount)
    }

    private fun replyJson(draft: String) =
        """{"action":"REPLY","reason":"GREETING","draft":"${draft.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
}
