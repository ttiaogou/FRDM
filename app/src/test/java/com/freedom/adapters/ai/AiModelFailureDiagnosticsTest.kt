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
        val decision = AiReplyDecisionEngine(provider, consent).decide(AiReplyDecisionInput(context, true, requestId = "request-1"))
        assertEquals("MODEL_CALL_FAILED", decision.reason)
        assertEquals(AiReplyAction.HUMAN_REVIEW, decision.action)
        assertNull(decision.draft)
        return decision.callDiagnostics!!
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
        assertFalse(d.logFields().contains(privateText))
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
            }, isAiReplyActive = { true }, isManualReviewRequired = { false }, onLog = { logs.add(it) },
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
        assertTrue(logs.single { it.startsWith("AI 模型决策详情：") }.contains("requestId=$requestId"))
        assertFalse(logs.joinToString().contains("模型未提供详细解释"))
        assertFalse(logs.joinToString().contains(secret))
        assertFalse(logs.joinToString().contains(privateText))
        assertFalse(coordinator.isPending)
        assertFalse(coordinator.isAwaitingUserSend)
    }

    private class FakeConnection(private val status: Int, private val body: String,
        private val writeFailure: Exception? = null, private val readFailure: Exception? = null
    ) : HttpURLConnection(URL("https://example.com")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): java.io.OutputStream { writeFailure?.let { throw it }; return ByteArrayOutputStream() }
        override fun getResponseCode() = status
        override fun getInputStream(): java.io.InputStream { readFailure?.let { throw it }; return ByteArrayInputStream(body.toByteArray()) }
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
    }
}
