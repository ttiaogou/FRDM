package com.freedom.adapters.ai

import com.freedom.features.aireply.ai.AiModelCallStage
import com.freedom.features.aireply.ai.AiModelConfiguration
import com.freedom.features.aireply.ai.AiModelMessage
import com.freedom.features.aireply.ai.AiModelRequest
import com.freedom.features.aireply.ai.AiModelResponseFormat
import com.freedom.features.aireply.ai.AiModelResult
import com.freedom.features.aireply.ai.AiModelServiceProvider
import com.freedom.features.aireply.ai.AiModelTransport
import com.freedom.features.aireply.ai.TokenUsage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/** DeepSeek 专用的 Chat Completions 适配器，不暴露服务商响应正文。 */
class DeepSeekAiModelTransport(
    private val configuration: AiModelConfiguration,
    private val connectionFactory: AiHttpConnectionFactory = AiHttpConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    }
) : AiModelTransport {

    internal var diagnosticLog: (String) -> Unit = {}

    override fun execute(request: AiModelRequest): Result<AiModelResult> = AiModelHttpCallTrace().capture { trace ->
        val config = configuration.normalized()
        require(config.provider == AiModelServiceProvider.DEEPSEEK) { "AI_MODEL_PROVIDER_MISMATCH" }
        require(config.isReadyForCloudProvider) { "AI_MODEL_NOT_CONFIGURED" }
        trace.stage = AiModelCallStage.ENDPOINT
        val endpoint = chatCompletionsEndpoint(config.apiBaseUrl)
        trace.stage = AiModelCallStage.CONNECTION_OPEN
        val connection = connectionFactory.open(URL(endpoint))
        try {
            trace.stage = AiModelCallStage.CONNECTION_SETUP
            connection.requestMethod = "POST"
            connection.connectTimeout = config.requestTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            connection.readTimeout = config.requestTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            trace.stage = AiModelCallStage.REQUEST_ENCODING
            val bodyBytes = buildRequestPayload(config.modelName, request)
                .toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            trace.stage = AiModelCallStage.REQUEST_WRITE
            connection.outputStream.use { output -> output.write(bodyBytes) }

            trace.stage = AiModelCallStage.RESPONSE_STATUS
            val status = connection.responseCode
            trace.httpStatus = status
            if (status !in 200..299) {
                runCatching { connection.errorStream?.use {
                    trace.captureProviderError(String(readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8))
                } }
                throw httpFailure(status)
            }
            trace.stage = AiModelCallStage.RESPONSE_READ
            val responseText = connection.inputStream.use {
                String(readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8)
            }
            trace.stage = AiModelCallStage.RESPONSE_PARSE
            parseResponse(responseText, config.modelName, request, connection.getHeaderField("x-request-id"))
        } catch (timeout: SocketTimeoutException) {
            throw AiModelHttpException("HTTP_TIMEOUT", retryable = true, cause = timeout)
        } catch (failure: AiModelHttpException) {
            throw failure
        } catch (failure: Exception) {
            throw AiModelHttpException("HTTP_TRANSPORT_FAILED", retryable = true, cause = failure)
        } finally {
            connection.disconnect()
        }
    }

    private fun chatCompletionsEndpoint(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://", ignoreCase = true)) { "HTTPS_REQUIRED" }
        return if (normalized.endsWith(CHAT_COMPLETIONS_PATH, ignoreCase = true)) {
            normalized
        } else {
            "$normalized$CHAT_COMPLETIONS_PATH"
        }
    }

    private fun buildRequestPayload(modelName: String, request: AiModelRequest): String {
        val systemInstruction = when (val format = request.responseFormat) {
            AiModelResponseFormat.PlainText -> request.systemInstruction
            is AiModelResponseFormat.JsonSchema -> buildString {
                append(request.systemInstruction)
                append("\n\n")
                append("请严格只返回一个有效的 JSON 对象，不要使用 Markdown 代码块，也不要添加任何说明文字。")
                append("请严格匹配以下 JSON Schema：\n")
                append(format.schema)
            }
        }
        val schema = (request.responseFormat as? AiModelResponseFormat.JsonSchema)?.schema
        // DeepSeek JSON mode returned only whitespace with mixed USER/ASSISTANT arrays
        // in two controlled samples (6/6 failures). Role-labelled USER transcripts
        // retained all dialogue and returned valid JSON (6/6 successes).
        // Keep all-USER and plain-text requests unchanged; this is wire formatting only.
        val flatten = schema != null &&
            request.conversation.any { it.role == AiModelMessage.Role.USER } &&
            request.conversation.any { it.role == AiModelMessage.Role.ASSISTANT }
        val conversation = if (flatten) {
            listOf(AiModelMessage(
                AiModelMessage.Role.USER,
                request.conversation.joinToString("\n") { message ->
                    val label = if (message.role == AiModelMessage.Role.USER) "对方(USER)" else "己方(ASSISTANT)"
                    "$label：${message.content}"
                }
            ))
        } else request.conversation
        val messages = ArrayList<Map<String, Any?>>(conversation.size + 1)
        val copies = schema?.let { systemInstruction.windowedSequence(it.length).count { part -> part == it } } ?: 0
        diagnosticLog("DeepSeek REQUEST requestId=${request.requestId} attemptIndex=${request.attemptIndex} " +
            "responseFormat=${if (schema == null) "absent" else "json_object"} thinking=disabled " +
            "maxTokens=${request.maxOutputTokens} systemChars=${systemInstruction.length} schemaCopies=$copies " +
            "conversationMessages=${conversation.size} templateVersion=context-select-v1 " +
            "originalConversationMessages=${request.conversation.size} contextEncoding=${if (flatten) "ROLE_LABELED_SINGLE_USER" else "ROLE_ARRAY"}")
        messages += mapOf("role" to "system", "content" to systemInstruction)
        conversation.forEach { message ->
            messages += mapOf(
                "role" to when (message.role) {
                    AiModelMessage.Role.USER -> "user"
                    AiModelMessage.Role.ASSISTANT -> "assistant"
                },
                "content" to message.content
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "model" to modelName,
            "messages" to messages,
            "temperature" to request.temperature,
            "max_tokens" to request.maxOutputTokens,
            // DeepSeek 默认使用思考模式；本流程只需要生成回复，因此明确关闭思考模式，
            // 使 temperature 参数保持文档规定的行为，同时不返回推理过程。
            "thinking" to mapOf("type" to "disabled"),
            "stream" to false
        )
        when (request.responseFormat) {
            AiModelResponseFormat.PlainText -> Unit
            is AiModelResponseFormat.JsonSchema -> {
                // DeepSeek 对话接口使用 JSON 输出；完整 Schema 已放入中文系统提示词，并由本地再次校验。
                payload["response_format"] = mapOf("type" to "json_object")
            }
        }
        return MiniJson.stringify(payload)
    }

    private fun parseResponse(body: String, configuredModel: String, request: AiModelRequest, providerId: String?): AiModelResult {
        val root = MiniJson.parse(body) as? Map<*, *>
            ?: throw AiModelHttpException("HTTP_RESPONSE_INVALID")
        val choices = root["choices"] as? List<*>
            ?: throw AiModelHttpException("HTTP_RESPONSE_MISSING_CHOICES")
        val firstChoice = choices.firstOrNull() as? Map<*, *>
            ?: throw AiModelHttpException("HTTP_RESPONSE_EMPTY_CHOICES")
        val message = firstChoice["message"] as? Map<*, *>
            ?: throw AiModelHttpException("HTTP_RESPONSE_MISSING_MESSAGE")
        val contentValue = message["content"]
        val content = contentValue as? String
        fun state(key: String): String = when (val value = message[key]) {
            null -> if (key in message) "NULL" else "ABSENT"
            is String -> if (value.isBlank()) "BLANK" else "PRESENT"
            else -> "NON_STRING"
        }
        val reasoningState = state("reasoning_content")
        val usageMap = root["usage"] as? Map<*, *>
        val usage = usageMap?.let {
            val input = (it["prompt_tokens"] as? Number)?.toInt()
            val output = (it["completion_tokens"] as? Number)?.toInt()
            if (input != null && output != null && input >= 0 && output >= 0) {
                TokenUsage(input, output)
            } else null
        }
        val finishReason = firstChoice["finish_reason"] as? String
        val safeProviderId = providerId?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,100}")) }
        diagnosticLog("DeepSeek RESPONSE requestId=${request.requestId} attemptIndex=${request.attemptIndex} " +
            "providerRequestId=${safeProviderId ?: "UNKNOWN"} contentState=${state("content")} " +
            "contentChars=${content?.length} spaces=${content?.count { it == ' ' }} " +
            "lineFeeds=${content?.count { it == '\n' }} reasoningContentState=$reasoningState " +
            "finishReason=${com.freedom.features.aireply.ai.AiModelCallDiagnostics.safeFinishReason(finishReason)} " +
            "inputTokens=${usage?.inputTokens} outputTokens=${usage?.outputTokens}")
        if (content.isNullOrBlank()) {
            val state = when {
                !message.containsKey("content") -> "ABSENT"
                contentValue == null -> "NULL"
                contentValue !is String -> "NON_STRING"
                else -> "BLANK"
            }
            throw AiModelHttpException(
                "EMPTY_MODEL_OUTPUT",
                retryable = state != "NON_STRING" && (finishReason == null || finishReason == "stop"),
                responseContentState = state,
                responseFinishReason = finishReason,
                responseInputTokens = usage?.inputTokens,
                responseOutputTokens = usage?.outputTokens,
                responseReasoningContentState = reasoningState
            )
        }
        return AiModelResult(
            text = content,
            provider = PROVIDER_NAME,
            model = (root["model"] as? String)?.takeIf(String::isNotBlank) ?: configuredModel,
            usage = usage,
            finishReason = finishReason
        )
    }

    private fun httpFailure(status: Int): AiModelHttpException = when (status) {
        401 -> AiModelHttpException("HTTP_401_UNAUTHORIZED")
        403 -> AiModelHttpException("HTTP_403_FORBIDDEN")
        404 -> AiModelHttpException("HTTP_404_NOT_FOUND")
        408 -> AiModelHttpException("HTTP_408_TIMEOUT", retryable = true)
        413 -> AiModelHttpException("HTTP_413_REQUEST_TOO_LARGE")
        429 -> AiModelHttpException("HTTP_429_RATE_LIMITED", retryable = true)
        in 500..599 -> AiModelHttpException("HTTP_5XX_SERVER_ERROR", retryable = true)
        else -> AiModelHttpException("HTTP_$status")
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8_192))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw AiModelHttpException("HTTP_RESPONSE_TOO_LARGE")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val PROVIDER_NAME = "deepseek"
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}
