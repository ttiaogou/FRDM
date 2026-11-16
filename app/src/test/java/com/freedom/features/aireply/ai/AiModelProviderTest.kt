package com.freedom.features.aireply.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiModelProviderTest {
    private val grantedConsent = AiModelCloudConsent { true }
    private val deniedConsent = AiModelCloudConsent { false }

    @Test fun transportProviderAnnotatesProviderAndModel() {
        val provider = TransportBackedAiModelProvider("test-provider", "test-model", object : AiModelTransport {
            override fun execute(request: AiModelRequest) = Result.success(
                AiModelResult("{\"action\":\"WAIT\",\"reason\":\"TEST\",\"draft\":null}", "transport", "transport-model")
            )
        }, grantedConsent)
        val result = provider.generate(AiModelRequest("instruction", listOf(AiModelMessage(AiModelMessage.Role.USER, "hello")), requestId = "r"))
        assertEquals("test-provider", result.provider)
        assertEquals("test-model", result.model)
    }

    @Test fun outboundTransportIsBlockedWithoutConsent() {
        var transportCalled = false
        val provider = TransportBackedAiModelProvider("p", "m", object : AiModelTransport {
            override fun execute(request: AiModelRequest): Result<AiModelResult> {
                transportCalled = true
                return Result.success(AiModelResult("{}", "p", "m"))
            }
        }, deniedConsent)
        assertThrows(IllegalStateException::class.java) {
            provider.generate(AiModelRequest("instruction", listOf(AiModelMessage(AiModelMessage.Role.USER, "hello")), requestId = "r"))
        }
        assertEquals(false, transportCalled)
    }

    @Test fun transportReceivesRedactedConversationContent() {
        var outboundText = ""
        val provider = TransportBackedAiModelProvider("p", "m", object : AiModelTransport {
            override fun execute(request: AiModelRequest): Result<AiModelResult> {
                outboundText = request.conversation.single().content
                return Result.success(AiModelResult("{}", "p", "m"))
            }
        }, grantedConsent)
        provider.generate(
            AiModelRequest("instruction", listOf(AiModelMessage(AiModelMessage.Role.USER, "联系电话 13800138000")), requestId = "r")
        )
        assertEquals(false, outboundText.contains("13800138000"))
        assertEquals(true, outboundText.contains("[手机号已脱敏]"))
    }

    @Test fun emptyModelOutputIsRejected() {
        val provider = TransportBackedAiModelProvider("p", "m", object : AiModelTransport {
            override fun execute(request: AiModelRequest) = Result.success(AiModelResult(" ", "p", "m"))
        }, grantedConsent)
        assertThrows(IllegalArgumentException::class.java) {
            provider.generate(AiModelRequest("instruction", listOf(AiModelMessage(AiModelMessage.Role.USER, "hello")), requestId = "r"))
        }
    }
}
