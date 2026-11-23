package com.freedom.adapters.ai

import com.freedom.core.accessibility.api.*
import com.freedom.core.runtime.*
import com.freedom.features.aireply.ai.*
import com.freedom.features.aireply.domain.message.*
import com.freedom.features.aireply.ports.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executor
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class AiModelFailureDiagnosticsTest {
    private val secret = "sk-secret-never-log"
    private val privateText = "PRIVATE_CHAT_NEVER_LOG"
    private val consent = AiModelCloudConsent { true }
    private fun config(deepSeek: Boolean) = AiModelConfiguration(
        provider = if (deepSeek) AiModelServiceProvider.DEEPSEEK else AiModelServiceProvider.OPENAI,
        apiBaseUrl = "https://example.com/v1", modelName = "configured-model", apiKey = secret)
    private fun transport(deepSeek: Boolean, factory: AiHttpConnectionFactory): AiModelTransport =
        if (deepSeek) DeepSeekAiModelTransport(config(true), factory)
        else OpenAiCompatibleAiModelTransport(config(false), factory)
    private fun request() = AiModelRequest("private system", listOf(AiModelMessage(AiModelMessage.Role.USER, privateText)), requestId = "request-1")
    private fun diagnostics(deepSeek: Boolean, factory: AiHttpConnectionFactory): AiModelCallDiagnostics {
        val provider = TransportBackedAiModelProvider("test", "configured-model", transport(deepSeek, factory), consent)
        val context = ConversationContext("contact", "conversation", request().conversation, 1, 0, 0, true)
        val result = AiReplyDecisionEngine(provider, consent).decide(AiReplyDecisionInput(context, true, requestId = "request-1"))
        assertTrue(result is AiReplyEvaluation.Execution)
        val execution = result as AiReplyEvaluation.Execution
        assertEquals(AiReplyExecutionStatus.MODEL_CALL_FAILED, execution.status)
        assertEquals("MODEL_CALL_FAILED", execution.status.reasonCode)
        return execution.callDiagnostics!!
    }

    @Test fun httpRejectionsRetainExactStatusAndExistingRetryClassificationForBothProviders() {
        for (deepSeek in listOf(false, true)) for (status in listOf(400, 401, 403, 408, 429, 500, 503)) {
            val d = diagnostics(deepSeek) { FakeConnection(status, "$secret $privateText") }
            assertEquals(status, d.httpStatus)
            assertEquals(AiModelCallStage.RESPONSE_STATUS, d.stage)
            assertEquals(status in listOf(408, 429, 500, 503), d.retryable)
            assertFalse(d.logFields().contains(secret))
            assertFalse(d.logFields().contains(privateText))
            assertTrue(d.safeCode.startsWith("HTTP_"))
        }
    }

    @Test fun providerErrorTokensHelpDiagnoseUnsupportedParametersWithoutExposingErrorMessage() {
        for (deepSeek in listOf(false, true)) {
            val body = """{"error":{"code":"unsupported_parameter","type":"invalid_request_error","param":"response_format","message":"$secret $privateText"}}"""
            val d = diagnostics(deepSeek) { FakeConnection(400, body) }
            assertEquals("unsupported_parameter", d.providerErrorCode)
            assertEquals("invalid_request_error", d.providerErrorType)
            assertEquals("response_format", d.providerErrorParam)
            assertTrue(d.logFields().contains("providerErrorParam=response_format"))
            assertFalse(d.logFields().contains(secret))
            assertFalse(d.logFields().contains(privateText))
        }
    }

    @Test fun arbitraryProviderErrorFieldsAreRedactedEvenWhenTheyLookLikeTechnicalTokens() {
        for (deepSeek in listOf(false, true)) {
            val body = """{"error":{"code":"$secret","type":"$privateText","param":"$privateText"}}"""
            val d = diagnostics(deepSeek) { FakeConnection(400, body) }
            assertEquals("REDACTED", d.providerErrorCode)
            assertEquals("REDACTED", d.providerErrorType)
            assertEquals("REDACTED", d.providerErrorParam)
            assertFalse(d.logFields().contains(secret))
            assertFalse(d.logFields().contains(privateText))
        }
    }

    @Test fun timeoutAndTlsFailuresRetainUnderlyingTypesAndFailureStage() {
        for (deepSeek in listOf(false, true)) for (cause in listOf(SocketTimeoutException(secret), SSLHandshakeException(privateText))) {
            val d = diagnostics(deepSeek) { FakeConnection(200, "unused", writeFailure = cause) }
            assertEquals(AiModelCallStage.REQUEST_WRITE, d.stage)
            assertNull(d.httpStatus)
            assertTrue(d.causeTypes.contains(cause.javaClass.name))
            assertEquals(if (cause is SocketTimeoutException) "HTTP_TIMEOUT" else "HTTP_TRANSPORT_FAILED", d.safeCode)
            assertEquals(true, d.retryable)
            assertFalse(d.logFields().contains(secret))
            assertFalse(d.logFields().contains(privateText))
        }
    }

    @Test fun connectionFactoryFailureIsRecordedBeforeAConnectionExists() {
        for (deepSeek in listOf(false, true)) {
            val d = diagnostics(deepSeek) { throw UnknownHostException("$secret $privateText") }
            assertEquals(AiModelCallStage.CONNECTION_OPEN, d.stage)
            assertTrue(d.causeTypes.contains(UnknownHostException::class.java.name))
            assertNull(d.httpStatus)
            assertFalse(d.logFields().contains(secret))
        }
    }

    @Test fun malformedAndMissingChoicesResponsesAreDistinguishedFromHttpRejection() {
        for (deepSeek in listOf(false, true)) for (body in listOf("not json $privateText", "{}")) {
            val d = diagnostics(deepSeek) { FakeConnection(200, body) }
            assertEquals(200, d.httpStatus)
            assertEquals(AiModelCallStage.RESPONSE_PARSE, d.stage)
            assertEquals(if (body == "{}") "HTTP_RESPONSE_MISSING_CHOICES" else "HTTP_TRANSPORT_FAILED", d.safeCode)
            assertFalse(d.logFields().contains(privateText))
        }
    }

    @Test fun readFailureRetainsSuccessfulHttpStatusAndReadStage() {
        for (deepSeek in listOf(false, true)) {
            val d = diagnostics(deepSeek) { FakeConnection(200, "unused", readFailure = SocketTimeoutException(secret)) }
            assertEquals(200, d.httpStatus)
            assertEquals(AiModelCallStage.RESPONSE_READ, d.stage)
            assertEquals("HTTP_TIMEOUT", d.safeCode)
        }
    }

    @Test fun preparationFailureIsRecordedWithoutOpeningConnection() {
        val broken = OpenAiCompatibleAiModelTransport(config(false).copy(apiKey = "")) { error("must not connect") }
        val d = AiModelCallDiagnostics.from(broken.execute(request()).exceptionOrNull()!!)
        assertEquals("AI_MODEL_NOT_CONFIGURED", d.safeCode)
        assertEquals(AiModelCallStage.CONFIGURATION, d.stage)
        assertNull(d.httpStatus)
    }

    @Test fun emptyProviderOutputHasAnExplicitSafeCode() {
        val d = diagnostics(false) { FakeConnection(200, """{"choices":[{"message":{"content":""}}]}""") }
        assertEquals("EMPTY_MODEL_OUTPUT", d.safeCode)
        assertEquals("BLANK", d.responseContentState)
        assertEquals(true, d.retryable)
        assertFalse(d.logFields().contains(privateText))
    }

    @Test fun deepSeekEmptyResponseRetainsStatusShapeFinishReasonAndTokenCounts() {
        val response = """{"choices":[{"message":{"content":""},"finish_reason":"stop"}],"usage":{"prompt_tokens":31,"completion_tokens":0}}"""
        val d = diagnostics(true) { FakeConnection(200, response) }
        assertEquals("EMPTY_MODEL_OUTPUT", d.safeCode)
        assertEquals(AiModelCallStage.RESPONSE_PARSE, d.stage)
        assertEquals(200, d.httpStatus)
        assertEquals("BLANK", d.responseContentState)
        assertEquals("stop", d.responseFinishReason)
        assertEquals(31, d.responseInputTokens)
        assertEquals(0, d.responseOutputTokens)
        assertEquals(true, d.retryable)
        assertTrue(d.logFields().contains("finishReason=stop"))
        assertFalse(d.logFields().contains(privateText))
    }

    @Test fun deepSeekMissingOrNullContentIsDiagnosedButInvalidTypeAndLengthDoNotRetry() {
        for ((content, expected) in listOf(
            "" to "ABSENT",
            "\"content\":null," to "NULL",
            "\"content\":{\"private\":\"$privateText\"}," to "NON_STRING"
        )) {
            val response = """{"choices":[{"message":{$content"role":"assistant"},"finish_reason":"stop"}]}"""
            val d = diagnostics(true) { FakeConnection(200, response) }
            assertEquals("EMPTY_MODEL_OUTPUT", d.safeCode)
            assertEquals(expected, d.responseContentState)
            assertEquals(expected != "NON_STRING", d.retryable)
            assertFalse(d.logFields().contains(privateText))
        }
        val truncated = diagnostics(true) { FakeConnection(200, """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""") }
        assertEquals(false, truncated.retryable)
        assertEquals("length", truncated.responseFinishReason)
    }

    @Test fun unrecognizedProviderFinishReasonIsRedacted() {
        val body = """{"choices":[{"message":{"content":""},"finish_reason":"$secret $privateText"}]}"""
        val d = diagnostics(true) { FakeConnection(200, body) }
        assertEquals("REDACTED", d.responseFinishReason)
        assertFalse(d.logFields().contains(secret))
        assertFalse(d.logFields().contains(privateText))
    }


    @Test fun deepSeekHttp200ContentAndFinishReasonMatrixKeepsSafeDiagnostics() {
        data class EmptyCase(val message: String, val finish: String?, val state: String, val retry: Boolean)
        val cases = listOf(
            EmptyCase("""{"content":""}""", "stop", "BLANK", true),
            EmptyCase("""{"content":"   "}""", "stop", "BLANK", true),
            EmptyCase("""{"content":null}""", "stop", "NULL", true),
            EmptyCase("{}", null, "ABSENT", true),
            EmptyCase("""{"content":{"private":"$privateText"}}""", "stop", "NON_STRING", false),
            EmptyCase("""{"content":""}""", "length", "BLANK", false),
            EmptyCase("""{"content":null}""", "content_filter", "NULL", false),
            EmptyCase("""{"content":""}""", "tool_calls", "BLANK", false)
        )
        for (case in cases) {
            val finishField = case.finish?.let { ",\"finish_reason\":\"$it\"" } ?: ""
            val body = """{"choices":[{"message":${case.message}$finishField}],"usage":{"prompt_tokens":953,"completion_tokens":3}}"""
            val d = diagnostics(true) { FakeConnection(200, body) }
            assertEquals(case.state, d.responseContentState)
            assertEquals(case.retry, d.retryable)
            assertEquals(case.finish, d.responseFinishReason)
            assertEquals("EMPTY_MODEL_OUTPUT", d.safeCode)
            assertEquals(200, d.httpStatus)
            assertEquals(AiModelCallStage.RESPONSE_PARSE, d.stage)
            assertEquals(953, d.responseInputTokens)
            assertEquals(3, d.responseOutputTokens)
            assertFalse(d.logFields().contains(privateText))
            assertFalse(d.logFields().contains(secret))
        }
    }

    @Test fun openAiHttp200BlankAndNullAreNotBusinessWaitsAndPreserveRetryability() {
        for ((content, expectedState) in listOf("\"\"" to "BLANK", "null" to "NULL")) {
            for (finish in listOf("stop", "length")) {
                val body = """{"choices":[{"message":{"content":$content},"finish_reason":"$finish"}]}"""
                val d = diagnostics(false) { FakeConnection(200, body) }
                assertEquals("EMPTY_MODEL_OUTPUT", d.safeCode)
                assertEquals(expectedState, d.responseContentState)
                assertEquals(finish == "stop", d.retryable)
                assertEquals(finish, d.responseFinishReason)
                assertEquals(AiModelCallStage.MODEL_CALL, d.stage)
                assertFalse(d.logFields().contains(privateText))
            }
        }
    }

    @Test fun realHttp200BlankThenNullThenValidJsonUsesFinalDeepSeekCompatibilityOnly() {
        val fixture = EmptyHttpRecoveryFixture(listOf(
            """{"choices":[{"message":{"content":"  "},"finish_reason":"stop"}],"usage":{"prompt_tokens":953,"completion_tokens":3}}""",
            """{"choices":[{"message":{"content":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":953,"completion_tokens":3}}""",
            """{"choices":[{"message":{"content":"{\"action\":\"REPLY\",\"reason\":\"READY\",\"draft\":\"你好\"}"},"finish_reason":"stop"}]}"""
        ))
        assertTrue(fixture.coordinator.generateVisibleDraft(fixture.batch, fixture.snapshot))
        assertEquals(1, fixture.requests.size)
        assertEquals(0, fixture.sendCount)
        assertEquals(listOf(2_500L), fixture.delays)
        fixture.step()
        assertEquals(2, fixture.requests.size)
        assertEquals(0, fixture.sendCount)
        assertEquals(listOf(2_500L, 6_000L), fixture.delays)
        fixture.step()
        assertEquals(3, fixture.requests.size)
        assertEquals(0, fixture.sendCount)
        fixture.drain()
        val bodies = fixture.requests.map { it.requestBody.toString("UTF-8") }
        assertTrue(bodies[0].contains("\"type\":\"json_object\""))
        assertTrue(bodies[1].contains("\"type\":\"json_object\""))
        assertFalse(bodies[2].contains("\"response_format\""))
        assertTrue(bodies[2].contains("请严格匹配以下 JSON Schema"))
        assertEquals(listOf("你好"), fixture.sentTexts)
        assertTrue(fixture.coordinator.isSendConfirmationPending)
        assertTrue(fixture.coordinator.isConversationReturnBlocked)
        assertFalse(fixture.coordinator.generateVisibleDraft(fixture.batch, fixture.snapshot))
        assertEquals(3, fixture.requests.size)
        assertEquals(1, fixture.sendCount)
        assertEquals(2, fixture.logs.count { it.startsWith("AI 模型调用失败：") })
        assertTrue(fixture.logs.any { it.contains("responseContentState=BLANK") })
        assertTrue(fixture.logs.any { it.contains("responseContentState=NULL") })
        assertTrue(fixture.logs.any { it.contains("responseMode=PROMPT_ONLY_JSON") })
        assertFalse(fixture.logs.joinToString("\n").contains(privateText))
        assertFalse(fixture.logs.joinToString("\n").contains(secret))

        val confirmed = ChatMessage("fresh-outgoing", ChatMessageDirection.OUTGOING,
            ChatMessageType.TEXT, "你好", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val after = fixture.batch.copy(messages = listOf(confirmed), capturedAtMillis = 101L)
        assertEquals(SendConfirmationResult.CONFIRMED, fixture.coordinator.confirmSendResult(after, fixture.snapshot))
        assertFalse(fixture.coordinator.isConversationReturnBlocked)
        assertEquals(1, fixture.sendCount)
    }

    @Test fun realHttp200ThreeEmptyResponsesEndWithoutWritingOrSending() {
        val blank = """{"choices":[{"message":{"content":""},"finish_reason":"stop"}]}"""
        val fixture = EmptyHttpRecoveryFixture(listOf(blank, blank, blank))
        assertTrue(fixture.coordinator.generateVisibleDraft(fixture.batch, fixture.snapshot))
        fixture.drain()
        assertEquals(3, fixture.requests.size)
        assertEquals(listOf(2_500L, 6_000L), fixture.delays)
        assertEquals("", fixture.text)
        assertEquals(0, fixture.writeCount)
        assertEquals(0, fixture.sendCount)
        assertFalse(fixture.coordinator.isPending)
        assertFalse(fixture.coordinator.isSendConfirmationPending)
        assertFalse(fixture.coordinator.isConversationReturnBlocked)
        assertEquals(2, fixture.logs.count { it.startsWith("AI 模型空输出有限恢复：") })
        assertTrue(fixture.logs.any { it.contains("MODEL_DECISION_MODEL_CALL_FAILED") })
        assertFalse(fixture.logs.joinToString("\n").contains(privateText))
    }

    @Test fun coordinatorLogsCorrelatedTechnicalFailureAndNeverAttemptsInput() {
        val logs = mutableListOf<String>()
        val window = WindowRef("pkg", "activity", 7, 21)
        val snapshot = ChatPageSnapshot("pkg", "activity", ChatPageKind.CHAT_DETAIL_CANDIDATE,
            10, 1, 1, 1, 1, false, NodeTarget(window, NodePath(listOf(1))),
            NodeTarget(window, NodePath(listOf(2))), chatTitleText = "与测试对话", windowGeneration = 7)
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS,
            "pkg|21|7|conversation=${snapshot.conversationIdentityKey()}",
            listOf(ChatMessage("id", ChatMessageDirection.INCOMING, ChatMessageType.TEXT, privateText,
                ScreenRect(0, 10, 100, 30), DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)),
            1, 1, false, 1L, completeTailRowBounds = ScreenRect(0, 10, 100, 30))
        var now = 100L
        val coordinator = AiReplyDraftCoordinator(
            configurationRepository = object : AiModelConfigurationRepository {
                override fun load() = config(false)
                override fun save(configuration: AiModelConfiguration) = Unit
                override fun clearApiKey() = Unit
            }, cloudConsent = consent,
            personalizationRepository = object : AiReplyPersonalizationRepository {
                override fun load() = AiReplyPersonalization()
                override fun save(personalization: AiReplyPersonalization) = Unit
            }, inputPort = object : ChatDraftInputPort {
                override fun writeDraft(target: ChatDraftInputTarget, expectedText: String, text: String): ChatDraftInputResult = error("must not write")
                override fun verifyDraft(target: ChatDraftInputTarget, text: String): ChatDraftInputResult = error("must not verify")
            }, scheduler = object : TaskScheduler {
                override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask = error("must not schedule input")
            }, isAiReplyActive = { true }, onLog = { logs.add(it) },
            createProvider = { TransportBackedAiModelProvider("test", "configured-model", transport(false) {
                now += 234L
                FakeConnection(401, "$secret $privateText")
            }, consent) }, backgroundExecutor = Executor { it.run() }, nowMillis = { now })
        val accepted = coordinator.generateVisibleDraft(batch, snapshot)
        assertTrue(logs.joinToString("\n"), accepted)
        val log = logs.single { it.startsWith("AI 模型调用失败：") }
        assertTrue(log.contains("provider=OPENAI"))
        assertTrue(log.contains("model=configured-model"))
        assertTrue(log.contains("elapsedMs=234"))
        assertTrue(log.contains("httpStatus=401"))
        assertTrue(log.contains("safeCode=HTTP_401_UNAUTHORIZED"))
        val requestId = log.substringAfter("requestId=").substringBefore("；")
        assertTrue(requestId.isNotBlank())
        val executionLog = logs.single { it.startsWith("AI 程序执行状态：") }
        assertTrue(executionLog.contains("requestId=$requestId"))
        assertTrue(executionLog.contains("executionState=MODEL_CALL_FAILED"))
        assertFalse(logs.any { it.startsWith("AI 模型决策详情：") })
        assertFalse(logs.joinToString().contains("模型未提供详细解释"))
        assertFalse(logs.joinToString().contains(secret))
        assertFalse(logs.joinToString().contains(privateText))
        assertFalse(coordinator.isPending)
    }


    private inner class EmptyHttpRecoveryFixture(responseBodies: List<String>) {
        private val pendingBodies = java.util.ArrayDeque(responseBodies)
        val requests = mutableListOf<FakeConnection>()
        val delays = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val sentTexts = mutableListOf<String>()
        private val tasks = mutableListOf<() -> Unit>()
        var text = ""
        var writeCount = 0
        val sendCount: Int get() = sentTexts.size
        private val window = WindowRef("pkg", "activity", 7, 21)
        val snapshot = ChatPageSnapshot("pkg", "activity", ChatPageKind.CHAT_DETAIL_CANDIDATE,
            10, 1, 1, 1, 1, false, NodeTarget(window, NodePath(listOf(1))),
            NodeTarget(window, NodePath(listOf(2))), chatTitleText = "与测试对话", windowGeneration = 7)
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS,
            "pkg|21|7|conversation=${snapshot.conversationIdentityKey()}",
            listOf(ChatMessage("incoming", ChatMessageDirection.INCOMING, ChatMessageType.TEXT, privateText,
                ScreenRect(0, 10, 100, 30), DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)),
            1, 1, false, 1L, completeTailRowBounds = ScreenRect(0, 10, 100, 30))
        val coordinator = AiReplyDraftCoordinator(
            configurationRepository = object : AiModelConfigurationRepository {
                override fun load() = config(true)
                override fun save(configuration: AiModelConfiguration) = Unit
                override fun clearApiKey() = Unit
            },
            cloudConsent = consent,
            personalizationRepository = object : AiReplyPersonalizationRepository {
                override fun load() = AiReplyPersonalization()
                override fun save(personalization: AiReplyPersonalization) = Unit
            },
            inputPort = object : ChatDraftInputPort {
                override fun writeDraft(target: ChatDraftInputTarget, expectedText: String, text: String): ChatDraftInputResult {
                    if (this@EmptyHttpRecoveryFixture.text != expectedText) return ChatDraftInputResult(ChatDraftInputStatus.INPUT_NOT_EMPTY)
                    this@EmptyHttpRecoveryFixture.text = text
                    writeCount++
                    return ChatDraftInputResult(ChatDraftInputStatus.WRITTEN)
                }
                override fun verifyDraft(target: ChatDraftInputTarget, expectedText: String) =
                    ChatDraftInputResult(if (this@EmptyHttpRecoveryFixture.text == expectedText)
                        ChatDraftInputStatus.VERIFIED else ChatDraftInputStatus.TEXT_MISMATCH)
            },
            scheduler = object : TaskScheduler {
                override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
                    delays += delayMillis
                    tasks += task
                    return ScheduledTask { tasks.remove(task) }
                }
            },
            isAiReplyActive = { true },
            onLog = { logs.add(it) },
            createProvider = {
                TransportBackedAiModelProvider(
                    "deepseek", "configured-model",
                    transport(true) {
                        val connection = FakeConnection(200, pendingBodies.removeFirst())
                        requests += connection
                        connection
                    },
                    consent
                )
            },
            backgroundExecutor = Executor { it.run() }, nowMillis = { 100L },
            chooseRandomInt = { min, _ -> min },
            sendPort = object : ChatDraftSendPort {
                override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                    if (text != expectedText) return ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED)
                    sentTexts += expectedText
                    text = ""
                    return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
                }
            }
        )
        fun step() { assertTrue("Expected a scheduled task", tasks.isNotEmpty()); tasks.removeAt(0).invoke() }
        fun drain() {
            var count = 0
            while (tasks.isNotEmpty()) {
                assertTrue("Unexpected scheduling loop", ++count <= 30)
                step()
            }
        }
    }

    private class FakeConnection(private val status: Int, private val body: String,
        private val writeFailure: Exception? = null, private val readFailure: Exception? = null
    ) : HttpURLConnection(URL("https://example.com")) {
        val requestBody = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): java.io.OutputStream { writeFailure?.let { throw it }; return requestBody }
        override fun getResponseCode() = status
        override fun getInputStream(): java.io.InputStream { readFailure?.let { throw it }; return ByteArrayInputStream(body.toByteArray()) }
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
    }
}
