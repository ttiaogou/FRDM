package com.freedom.features.aireply.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.test.assertFailsWith

class AiDecisionGatewayContractTest {
    private val context = ConversationContext(
        contactId = "verified-contact",
        conversationId = "conversation",
        messages = listOf(AiModelMessage(AiModelMessage.Role.USER, "private test message")),
        insertedCount = 0,
        duplicateCount = 0,
        rejectedCount = 0,
        hasVerifiedIncoming = true,
        currentWindowDraft = true
    )

    private fun request(
        taskId: String = "AI_REPLY_RECEIVED_GREETINGS",
        requestId: String = "request-1"
    ) = ModelDecisionRequest(
        taskId = taskId,
        requestId = requestId,
        context = context,
        constraints = ModelDecisionConstraints(
            contextScope = ModelContextScope.CURRENT_WINDOW_ONLY,
            contactIdentityVerified = true,
            cooldownActive = false
        ),
        outputContract = ModelOutputContract(
            name = "ai_reply_decision",
            jsonSchema = """{"type":"object"}"""
        )
    )

    @Test fun rejectsMissingTaskAndRequestIdentity() {
        assertFailsWith<IllegalArgumentException> { request(taskId = "") }
        assertFailsWith<IllegalArgumentException> { request(requestId = "  ".trim()) }
    }

    @Test fun keepsProviderResponseModeSeparateFromStrictLocalSchema() {
        val recovered = request().copy(responseMode = ModelResponseMode.PROMPT_ONLY_JSON)
        assertEquals(ModelResponseMode.PROMPT_ONLY_JSON, recovered.responseMode)
        assertEquals("ai_reply_decision", recovered.outputContract.name)
        assertEquals(true, recovered.outputContract.strict)
        assertFailsWith<IllegalArgumentException> {
            ModelOutputContract("ai_reply_decision", """{"type":"object"}""", strict = false)
        }
    }

    @Test fun doesNotExposeChatTextOrIdentifiersViaDebugFormatting() {
        val formatted = request().toString()
        assertFalse(formatted.contains("private test message"))
        assertFalse(formatted.contains("request-1"))
        assertFalse(formatted.contains("verified-contact"))
        assertFalse(formatted.contains("AI_REPLY_RECEIVED_GREETINGS"))
    }

    @Test fun reusesExistingSeparatedDecisionAndExecutionOutcomes() {
        val reply = AiReplyEvaluation.Model(AiReplyDecision(AiReplyAction.REPLY, "SAFE", "你好"))
        val technicalFailure = AiReplyEvaluation.Execution(AiReplyExecutionStatus.MODEL_CALL_FAILED)
        val gateway = object : AiDecisionGateway {
            override fun decide(request: ModelDecisionRequest): ModelDecisionOutcome =
                if (request.responseMode == ModelResponseMode.STRICT_JSON) reply else technicalFailure
        }
        assertSame(reply, gateway.decide(request()))
        assertSame(
            technicalFailure,
            gateway.decide(request().copy(responseMode = ModelResponseMode.PROMPT_ONLY_JSON))
        )
    }
}
