package com.freedom.adapters.ai

import com.freedom.features.aireply.ai.AiModelConfiguration
import com.freedom.features.aireply.ai.AiModelMessage
import com.freedom.features.aireply.ai.AiModelRequest
import com.freedom.features.aireply.ai.AiModelResponseFormat
import com.freedom.features.aireply.ai.AiModelServiceProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekAiModelTransportTest {
    @Test
    fun postsDeepSeekSpecificTokenAndJsonModeParameters() {
        val connection = FakeConnection(
            URL("https://api.deepseek.com/chat/completions"),
            200,
            """{"model":"deepseek-flash","choices":[{"message":{"content":"{\"action\":\"REPLY\",\"reason\":\"READY\",\"draft\":\"你好\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":18,"completion_tokens":9}}"""
        )
        val transport = DeepSeekAiModelTransport(configuration()) { connection }
        val logs = mutableListOf<String>()
        transport.diagnosticLog = { logs.add(it) }
        val result = transport.execute(request().copy(attemptIndex = 1)).getOrThrow()
        assertTrue(logs.single { it.contains("DeepSeek REQUEST") }.contains("schemaCopies=1"))
        assertTrue(logs.single { it.contains("DeepSeek RESPONSE") }.contains("contentState=PRESENT"))
        assertTrue(logs.all { it.contains("attemptIndex=1") && !it.contains("你好") })

        assertEquals("{\"action\":\"REPLY\",\"reason\":\"READY\",\"draft\":\"你好\"}", result.text)
        assertEquals("deepseek-flash", result.model)
        assertEquals(18, result.usage?.inputTokens)
        assertEquals(9, result.usage?.outputTokens)
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer deepseek-secret-key", connection.requestProperties["Authorization"])
        val body = connection.requestBody.toString(StandardCharsets.UTF_8.name())
        assertTrue(body.contains("\"model\":\"deepseek-flash\""))
        assertTrue(body.contains("\"max_tokens\":256"))
        assertFalse(body.contains("max_completion_tokens"))
        assertTrue(body.contains("\"type\":\"json_object\""))
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.contains("请严格只返回一个有效的 JSON 对象"))
        assertTrue(body.contains("请严格匹配以下 JSON Schema"))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertFalse(body.contains("deepseek-secret-key"))
    }

    @Test
    fun promptOnlyJsonRecoveryOmitsForcedJsonMode() {
        val connection = FakeConnection(
            URL("https://api.deepseek.com/chat/completions"),
            200,
            """{"choices":[{"message":{"content":"{\"action\":\"REPLY\",\"reason\":\"READY\",\"draft\":\"你好\"}"}}]}"""
        )
        val transport = DeepSeekAiModelTransport(configuration()) { connection }
        transport.execute(request(AiModelResponseFormat.PlainText)).getOrThrow()
        val body = connection.requestBody.toString(StandardCharsets.UTF_8.name())
        assertFalse(body.contains("\"response_format\""))
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.contains("\"stream\":false"))
    }

    @Test
    fun appendsDeepSeekChatCompletionsPathWithoutAddingV1() {
        var openedUrl: URL? = null
        val transport = DeepSeekAiModelTransport(configuration()) { url ->
            openedUrl = url
            FakeConnection(url, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        transport.execute(request(AiModelResponseFormat.PlainText)).getOrThrow()
        assertEquals("https://api.deepseek.com/chat/completions", openedUrl?.toString())
    }

    @Test
    fun rejectsConfigurationForAnotherProvider() {
        val config = configuration().copy(provider = AiModelServiceProvider.OPENAI)
        val failure = DeepSeekAiModelTransport(config)
            .execute(request(AiModelResponseFormat.PlainText)).exceptionOrNull()
        assertEquals("AI_MODEL_PROVIDER_MISMATCH", failure?.message)
    }

    @Test
    fun unauthorizedResponseIsSafeAndDoesNotExposeProviderBody() {
        val connection = FakeConnection(
            URL("https://api.deepseek.com/chat/completions"),
            401,
            "provider secret diagnostic"
        )
        val failure = DeepSeekAiModelTransport(configuration()) { connection }
            .execute(request(AiModelResponseFormat.PlainText)).exceptionOrNull()
        assertTrue(failure is AiModelHttpException)
        assertEquals("HTTP_401_UNAUTHORIZED", (failure as AiModelHttpException).safeCode)
        assertFalse(failure.message.orEmpty().contains("provider secret diagnostic"))
    }

    private fun configuration() = AiModelConfiguration(
        provider = AiModelServiceProvider.DEEPSEEK,
        apiBaseUrl = "https://api.deepseek.com",
        modelName = "deepseek-flash",
        apiKey = "deepseek-secret-key",
        requestTimeoutMillis = 5_000L
    )

    private fun request(format: AiModelResponseFormat = AiModelResponseFormat.JsonSchema(
        name = "reply",
        schema = """{"type":"object","properties":{"draft":{"type":"string"}},"required":["draft"]}"""
    )) = AiModelRequest(
        systemInstruction = "Return one JSON object only.",
        conversation = listOf(AiModelMessage(AiModelMessage.Role.USER, "你好")),
        temperature = 0.4,
        maxOutputTokens = 256,
        requestId = "deepseek-request-1",
        responseFormat = format
    )

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val responseBody: String
    ) : HttpURLConnection(url) {
        val requestBody = ByteArrayOutputStream()
        val requestProperties = linkedMapOf<String, String>()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getOutputStream() = requestBody
        override fun getInputStream() = ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8))
        override fun getErrorStream() = ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8))
        override fun getResponseCode(): Int = status
        override fun setRequestProperty(key: String, value: String) {
            requestProperties[key] = value
        }
    }
}
