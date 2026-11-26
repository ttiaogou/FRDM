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
    fun wireDiagnosticsCaptureActualFlattenedMessagesWithoutHeadersOrSystemPrompt() {
        val connection = FakeConnection(URL("https://api.deepseek.com/chat/completions"), 200,
            """{"choices":[{"message":{"content":"ok"}}]}""")
        val transport = DeepSeekAiModelTransport(configuration()) { connection }
        val records = mutableListOf<String>()
        val logs = mutableListOf<String>()
        transport.wireDiagnostic = { records.add(it) }
        transport.diagnosticLog = { logs.add(it) }
        transport.wireDiagnosticLog = { logs.add(it) }
        val input = request().copy(attemptIndex = 1, conversation = listOf(
            AiModelMessage(AiModelMessage.Role.ASSISTANT, "还没睡"),
            AiModelMessage(AiModelMessage.Role.USER, "出来吃烧烤吗"),
            AiModelMessage(AiModelMessage.Role.USER, "我请你"),
            AiModelMessage(AiModelMessage.Role.USER, "怎么说")))
        transport.execute(input).getOrThrow()
        val decoded = records.map { MiniJson.parse(it) as Map<*, *> }
        assertEquals(listOf("REQUEST_PREPARED", "REQUEST_WRITTEN", "HTTP_RESPONSE"), decoded.map { it["stage"] })
        val prepared = decoded.first()
        val actual = wireMessages(requestPayload(connection)).drop(1)
        val captured = prepared["messages"] as List<*>
        assertEquals(actual, captured.map { entry ->
            val item = entry as Map<*, *>
            mapOf("role" to item["role"], "content" to item["content"])
        })
        assertEquals(4, (prepared["logicalConversationMessages"] as Number).toInt())
        assertEquals(1, (prepared["wireConversationMessages"] as Number).toInt())
        assertEquals(200, (decoded.last()["httpStatus"] as Number).toInt())
        assertTrue(decoded.all { it["requestId"] == input.requestId && (it["attemptIndex"] as Number).toInt() == 1 })
        assertTrue(records.none { it.contains("deepseek-secret-key") || it.contains(input.systemInstruction) })
        assertTrue(logs.none { it.contains("我请你") || it.contains("出来吃烧烤吗") })
        // A diagnostic exception cannot prevent the actual request from being sent.
        transport.wireDiagnostic = { error("diagnostic unavailable") }
        assertEquals("ok", transport.execute(input).getOrThrow().text)
    }

    @Test
    fun mixedRoleJsonRequestsPreserveAllTextAndOrderInOneUserMessage() {
        val role = AiModelMessage.Role.USER
        val assistant = AiModelMessage.Role.ASSISTANT
        val samples = listOf(
            listOf(AiModelMessage(role, "今天忙吗"), AiModelMessage(assistant, "刚休息"), AiModelMessage(role, "那就好")),
            listOf(AiModelMessage(assistant, "还在看书"), AiModelMessage(role, "天气冷"),
                AiModelMessage(role, "记得加衣"), AiModelMessage(role, "在吗"),
                AiModelMessage(assistant, "谢谢提醒\n多穿点实在"), AiModelMessage(role, "忙吗"),
                AiModelMessage(role, "睡了吗"))
        )
        val formats = listOf(
            AiModelResponseFormat.JsonSchema("reply", """{"type":"object","properties":{"action":{"type":"string"},"reason":{"type":"string"},"draft":{"type":"string"}}}"""),
            AiModelResponseFormat.JsonSchema("reply", """{"type":"object","properties":{"action":{"type":"string"},"draft":{"type":"string"}}}""")
        )
        for (sample in samples) {
            for ((attempt, format) in formats.withIndex()) {
                val connection = FakeConnection(URL("https://api.deepseek.com/chat/completions"),
                    200, """{"choices":[{"message":{"content":"{\"action\":\"REPLY\",\"draft\":\"收到\"}"},"finish_reason":"stop"}]}""")
                val transport = DeepSeekAiModelTransport(configuration()) { connection }
                val logs = mutableListOf<String>()
                transport.diagnosticLog = { logs.add(it) }
                val input = request(format).copy(conversation = sample, attemptIndex = attempt)
                transport.execute(input).getOrThrow()
                val payload = requestPayload(connection)
                val wire = wireMessages(payload)
                assertEquals(2, wire.size)
                assertEquals("system", wire[0]["role"])
                assertEquals(input.systemInstruction + "\n\n" +
                    "请严格只返回一个有效的 JSON 对象，不要使用 Markdown 代码块，也不要添加任何说明文字。" +
                    "请严格匹配以下 JSON Schema：\n" + format.schema, wire[0]["content"])
                assertEquals("user", wire[1]["role"])
                val expected = if (sample.size == 3) {
                    "对方(USER)：今天忙吗\n己方(ASSISTANT)：刚休息\n对方(USER)：那就好"
                } else {
                    "己方(ASSISTANT)：还在看书\n对方(USER)：天气冷\n对方(USER)：记得加衣\n对方(USER)：在吗\n" +
                        "己方(ASSISTANT)：谢谢提醒\n多穿点实在\n对方(USER)：忙吗\n对方(USER)：睡了吗"
                }
                assertEquals(expected, wire[1]["content"])
                assertEquals(sample, input.conversation)
                assertEquals(mapOf("type" to "json_object"), payload["response_format"])
                assertEquals(256, (payload["max_tokens"] as Number).toInt())
                assertEquals(0.4, (payload["temperature"] as Number).toDouble(), 0.0)
                val requestLog = logs.single { it.contains("DeepSeek REQUEST") }
                assertTrue(requestLog.contains("conversationMessages=1 "))
                assertTrue(requestLog.contains("originalConversationMessages=${sample.size} "))
                assertTrue(requestLog.contains("contextEncoding=ROLE_LABELED_SINGLE_USER"))
                assertTrue(requestLog.contains("schemaCopies=1"))
                assertTrue(logs.none { it.contains("刚休息") || it.contains("谢谢提醒") || it.contains("收到") })
            }
        }
    }

    @Test
    fun allUserJsonAndMixedRolePlainTextKeepOriginalRoleArrays() {
        val allUser = listOf(AiModelMessage(AiModelMessage.Role.USER, "你好"),
            AiModelMessage(AiModelMessage.Role.USER, "你好"),
            AiModelMessage(AiModelMessage.Role.USER, "在吗"))
        val mixed = listOf(AiModelMessage(AiModelMessage.Role.USER, "你好"),
            AiModelMessage(AiModelMessage.Role.ASSISTANT, "刚休息"),
            AiModelMessage(AiModelMessage.Role.USER, "在吗"))
        for (input in listOf(request().copy(conversation = allUser),
            request(AiModelResponseFormat.PlainText).copy(conversation = mixed, attemptIndex = 2))) {
            val connection = FakeConnection(URL("https://api.deepseek.com/chat/completions"),
                200, """{"choices":[{"message":{"content":"ok"}}]}""")
            val transport = DeepSeekAiModelTransport(configuration()) { connection }
            val logs = mutableListOf<String>()
            transport.diagnosticLog = { logs.add(it) }
            transport.execute(input).getOrThrow()
            val payload = requestPayload(connection)
            val wire = wireMessages(payload)
            assertEquals(4, wire.size)
            assertEquals(input.conversation.map { mapOf(
                "role" to if (it.role == AiModelMessage.Role.USER) "user" else "assistant",
                "content" to it.content
            ) }, wire.drop(1))
            assertEquals(input.responseFormat is AiModelResponseFormat.JsonSchema, payload.containsKey("response_format"))
            if (input.responseFormat == AiModelResponseFormat.PlainText) {
                assertEquals(input.systemInstruction, wire.first()["content"])
            }
            assertTrue(logs.single { it.contains("DeepSeek REQUEST") }.contains("contextEncoding=ROLE_ARRAY"))
        }
    }

    @Test
    fun blankMixedRoleJsonResponseStillFailsWithExistingRetryClassification() {
        val connection = FakeConnection(URL("https://api.deepseek.com/chat/completions"),
            200, """{"choices":[{"message":{"content":"   "},"finish_reason":"stop"}]}""")
        val transport = DeepSeekAiModelTransport(configuration()) { connection }
        val input = request().copy(conversation = listOf(
            AiModelMessage(AiModelMessage.Role.ASSISTANT, "刚休息"),
            AiModelMessage(AiModelMessage.Role.USER, "在吗")
        ))
        val failure = transport.execute(input).exceptionOrNull() as AiModelHttpException
        assertEquals("EMPTY_MODEL_OUTPUT", failure.safeCode)
        assertEquals("BLANK", failure.responseContentState)
        assertTrue(failure.retryable)
        assertEquals(2, wireMessages(requestPayload(connection)).size)
    }

    private fun requestPayload(connection: FakeConnection): Map<*, *> =
        MiniJson.parse(connection.requestBody.toString(StandardCharsets.UTF_8.name())) as Map<*, *>

    private fun wireMessages(payload: Map<*, *>): List<Map<*, *>> =
        (payload["messages"] as List<*>).map { it as Map<*, *> }

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
