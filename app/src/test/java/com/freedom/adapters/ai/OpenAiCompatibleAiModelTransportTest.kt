package com.freedom.adapters.ai

import com.freedom.features.aireply.ai.AiModelConfiguration
import com.freedom.features.aireply.ai.AiModelMessage
import com.freedom.features.aireply.ai.AiModelRequest
import com.freedom.features.aireply.ai.AiModelResponseFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleAiModelTransportTest {
    @Test
    fun postsChatCompletionsWithConfiguredModelAndPersonalizationParameters() {
        val connection = FakeConnection(
            URL("https://api.example.com/v1/chat/completions"),
            200,
            """{"model":"test-model","choices":[{"message":{"content":"{\"action\":\"REPLY\",\"reason\":\"READY\",\"draft\":\"你好\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":18,"completion_tokens":9}}"""
        )
        val transport = OpenAiCompatibleAiModelTransport(configuration()) { connection }
        val result = transport.execute(request()).getOrThrow()

        assertEquals("你好".let { "{\"action\":\"REPLY\",\"reason\":\"READY\",\"draft\":\"$it\"}" }, result.text)
        assertEquals("test-model", result.model)
        assertEquals(18, result.usage?.inputTokens)
        assertEquals(9, result.usage?.outputTokens)
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer test-secret-key", connection.requestProperties["Authorization"])
        assertEquals("application/json; charset=utf-8", connection.requestProperties["Content-Type"])
        val body = connection.requestBody.toString(StandardCharsets.UTF_8.name())
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"temperature\":0.7"))
        assertTrue(body.contains("\"max_completion_tokens\":256"))
        assertTrue(body.contains("\"type\":\"json_schema\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertFalse(body.contains("test-secret-key"))
    }

    @Test
    fun appendsChatCompletionsPathToBaseUrl() {
        var openedUrl: URL? = null
        val transport = OpenAiCompatibleAiModelTransport(configuration()) { url ->
            openedUrl = url
            FakeConnection(url, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        transport.execute(request(format = AiModelResponseFormat.PlainText)).getOrThrow()
        assertEquals("https://api.example.com/v1/chat/completions", openedUrl?.toString())
    }

    @Test
    fun doesNotAppendDuplicateChatCompletionsPath() {
        var openedUrl: URL? = null
        val config = configuration().copy(apiBaseUrl = "https://api.example.com/v1/chat/completions")
        val transport = OpenAiCompatibleAiModelTransport(config) { url ->
            openedUrl = url
            FakeConnection(url, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        transport.execute(request(format = AiModelResponseFormat.PlainText)).getOrThrow()
        assertEquals("https://api.example.com/v1/chat/completions", openedUrl?.toString())
    }

    @Test
    fun convertsUnauthorizedResponseToSafeFailureWithoutProviderBody() {
        val connection = FakeConnection(URL("https://api.example.com/v1/chat/completions"), 401, "secret provider diagnostics")
        val failure = OpenAiCompatibleAiModelTransport(configuration()) { connection }
            .execute(request(format = AiModelResponseFormat.PlainText)).exceptionOrNull()

        assertTrue(failure is AiModelHttpException)
        assertEquals("HTTP_401_UNAUTHORIZED", (failure as AiModelHttpException).safeCode)
        assertFalse(failure.message.orEmpty().contains("secret provider diagnostics"))
    }

    @Test
    fun rateLimitIsMarkedRetryable() {
        val connection = FakeConnection(URL("https://api.example.com/v1/chat/completions"), 429, "ignored")
        val failure = OpenAiCompatibleAiModelTransport(configuration()) { connection }
            .execute(request(format = AiModelResponseFormat.PlainText)).exceptionOrNull()
        assertTrue(failure is AiModelHttpException)
        assertTrue((failure as AiModelHttpException).retryable)
    }

    private fun configuration() = AiModelConfiguration(
        apiBaseUrl = "https://api.example.com/v1",
        modelName = "test-model",
        apiKey = "test-secret-key",
        requestTimeoutMillis = 5_000L
    )

    private fun request(format: AiModelResponseFormat = AiModelResponseFormat.JsonSchema(
        name = "reply",
        schema = """{"type":"object","properties":{"draft":{"type":"string"}},"required":["draft"]}"""
    )) = AiModelRequest(
        systemInstruction = "Be helpful.",
        conversation = listOf(
            AiModelMessage(AiModelMessage.Role.USER, "你好"),
            AiModelMessage(AiModelMessage.Role.ASSISTANT, "你好，有什么可以帮你？"),
            AiModelMessage(AiModelMessage.Role.USER, "今天天气怎么样？")
        ),
        temperature = 0.7,
        maxOutputTokens = 256,
        requestId = "request-1",
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
