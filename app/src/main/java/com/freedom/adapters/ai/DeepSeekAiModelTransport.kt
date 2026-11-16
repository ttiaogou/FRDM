package com.freedom.adapters.ai

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

/** DeepSeek-specific Chat Completions adapter. It does not expose provider response bodies. */
class DeepSeekAiModelTransport(
    private val configuration: AiModelConfiguration,
    private val connectionFactory: AiHttpConnectionFactory = AiHttpConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    }
) : AiModelTransport {

    override fun execute(request: AiModelRequest): Result<AiModelResult> = runCatching {
        val config = configuration.normalized()
        require(config.provider == AiModelServiceProvider.DEEPSEEK) { "AI_MODEL_PROVIDER_MISMATCH" }
        require(config.isReadyForCloudProvider) { "AI_MODEL_NOT_CONFIGURED" }
        val connection = connectionFactory.open(URL(chatCompletionsEndpoint(config.apiBaseUrl)))
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = config.requestTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            connection.readTimeout = config.requestTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val bodyBytes = buildRequestPayload(config.modelName, request)
                .toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { output -> output.write(bodyBytes) }

            val status = connection.responseCode
            if (status !in 200..299) {
                runCatching { connection.errorStream?.use { readBounded(it, MAX_RESPONSE_BYTES) } }
                throw httpFailure(status)
            }
            val responseText = connection.inputStream.use {
                String(readBounded(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8)
            }
            parseResponse(responseText, config.modelName)
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
        val messages = ArrayList<Map<String, Any?>>(request.conversation.size + 1)
        val systemInstruction = when (val format = request.responseFormat) {
            AiModelResponseFormat.PlainText -> request.systemInstruction
            is AiModelResponseFormat.JsonSchema -> buildString {
                append(request.systemInstruction)
                append("\n\n")
                append("Return exactly one valid json object. The response must be JSON only, ")
                append("with no Markdown fences or surrounding prose. Match this JSON Schema:\n")
                append(format.schema)
            }
        }
        messages += mapOf("role" to "system", "content" to systemInstruction)
        request.conversation.forEach { message ->
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
            // DeepSeek defaults to thinking mode; explicitly disable it for this response-only
            // workflow so temperature retains its documented behavior and reasoning stays off.
            "thinking" to mapOf("type" to "disabled"),
            "stream" to false
        )
        when (request.responseFormat) {
            AiModelResponseFormat.PlainText -> Unit
            is AiModelResponseFormat.JsonSchema -> {
                // DeepSeek Chat Completions supports JSON Output, not OpenAI's json_schema format.
                // The complete schema is supplied in the system instruction and validated locally.
                payload["response_format"] = mapOf("type" to "json_object")
            }
        }
        return MiniJson.stringify(payload)
    }

    private fun parseResponse(body: String, configuredModel: String): AiModelResult {
        val root = MiniJson.parse(body) as? Map<*, *>
            ?: throw AiModelHttpException("HTTP_RESPONSE_INVALID")
        val choices = root["choices"] as? List<*>
            ?: throw AiModelHttpException("HTTP_RESPONSE_MISSING_CHOICES")
        val firstChoice = choices.firstOrNull() as? Map<*, *>
            ?: throw AiModelHttpException("HTTP_RESPONSE_EMPTY_CHOICES")
        val message = firstChoice["message"] as? Map<*, *>
            ?: throw AiModelHttpException("HTTP_RESPONSE_MISSING_MESSAGE")
        val content = message["content"] as? String
        val usageMap = root["usage"] as? Map<*, *>
        val usage = usageMap?.let {
            val input = (it["prompt_tokens"] as? Number)?.toInt()
            val output = (it["completion_tokens"] as? Number)?.toInt()
            if (input != null && output != null && input >= 0 && output >= 0) {
                TokenUsage(input, output)
            } else null
        }
        return AiModelResult(
            text = content,
            provider = PROVIDER_NAME,
            model = (root["model"] as? String)?.takeIf(String::isNotBlank) ?: configuredModel,
            usage = usage,
            finishReason = firstChoice["finish_reason"] as? String
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
