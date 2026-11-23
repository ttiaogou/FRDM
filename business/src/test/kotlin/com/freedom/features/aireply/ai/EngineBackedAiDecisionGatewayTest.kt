package com.freedom.features.aireply.ai

import com.freedom.features.aireply.PrivateMessagesTaskController
import com.freedom.features.aireply.ReceivedGreetingsTaskController
import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertFailsWith

class EngineBackedAiDecisionGatewayTest {
    private val context = ConversationContext(
        "contact", "conversation", listOf(AiModelMessage(AiModelMessage.Role.USER, "你好")),
        0, 0, 0, true, currentWindowDraft = true
    )
    private val json = """{"action":"REPLY","reason":"OK","draft":"你好呀"}"""

    private fun request(mode: ModelResponseMode = ModelResponseMode.STRICT_JSON) =
        ModelDecisionRequest(
            AiDecisionTaskIds.RECEIVED_GREETINGS, "request-1", context,
            ModelDecisionConstraints(ModelContextScope.CURRENT_WINDOW_ONLY, false, false),
            AiReplyModelContracts.currentOutput(), mode
        )

    @Test fun usesTheSameSchemaTemperatureTokensAndDecisionAsExistingEngine() {
        val observed = mutableListOf<AiModelRequest>()
        val provider = object : AiModelProvider {
            override fun generate(request: AiModelRequest): AiModelResult {
                observed += request
                return AiModelResult(json, "fake", "fake")
            }
        }
        val consent = AiModelCloudConsent { true }
        val gateway = EngineBackedAiDecisionGateway(provider, consent)
        val strictResult = gateway.decide(request()) as AiReplyEvaluation.Model
        assertEquals(AiReplyAction.REPLY, strictResult.decision.action)
        assertEquals("你好呀", strictResult.decision.draft)
        assertEquals(AiReplyDecisionEngine.DEFAULT_TEMPERATURE, observed.single().temperature, 0.0)
        assertEquals(AiReplyDecisionEngine.DEFAULT_MAX_OUTPUT_TOKENS, observed.single().maxOutputTokens)
        assertEquals(AiReplyModelContracts.currentOutput().jsonSchema,
            (observed.single().responseFormat as AiModelResponseFormat.JsonSchema).schema)
        assertEquals("ai_reply_decision",
            (observed.single().responseFormat as AiModelResponseFormat.JsonSchema).name)

        val relaxedTransportResult = gateway.decide(request(ModelResponseMode.PROMPT_ONLY_JSON))
        assertTrue(relaxedTransportResult is AiReplyEvaluation.Model)
        assertEquals(AiModelResponseFormat.PlainText, observed.last().responseFormat)
        assertTrue(observed.last().systemInstruction.contains(AiReplyModelContracts.currentOutput().jsonSchema))
    }

    @Test fun programFailureRemainsDistinctFromModelDecision() {
        val gateway = EngineBackedAiDecisionGateway(object : AiModelProvider {
            override fun generate(request: AiModelRequest): AiModelResult = error("fake failure")
        }, AiModelCloudConsent { true })
        val failure = gateway.decide(request()) as AiReplyEvaluation.Execution
        assertEquals(AiReplyExecutionStatus.MODEL_CALL_FAILED, failure.status)
        assertNotNull(failure.callDiagnostics)
    }

    @Test fun mismatchedSchemaIsRejectedBeforeModelCall() {
        var calls = 0
        val gateway = EngineBackedAiDecisionGateway(object : AiModelProvider {
            override fun generate(request: AiModelRequest): AiModelResult {
                calls++
                return AiModelResult(json, "fake", "fake")
            }
        }, AiModelCloudConsent { true })
        assertFailsWith<IllegalArgumentException> {
            gateway.decide(request().copy(
                outputContract = ModelOutputContract("other_schema", """{"type":"object"}""")
            ))
        }
        assertEquals(0, calls)
    }

    @Test fun declaredTaskIdsRemainEqualToTheRealBusinessOwnerIds() {
        assertEquals(ReceivedGreetingsTaskController.TASK_ID, AiDecisionTaskIds.RECEIVED_GREETINGS)
        assertEquals(PrivateMessagesTaskController.TASK_ID, AiDecisionTaskIds.PRIVATE_MESSAGES)
    }
}
