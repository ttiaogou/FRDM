package com.freedom.adapters.ai

import com.freedom.features.aireply.ai.AiModelCallStage
import com.freedom.features.aireply.ai.AiModelConfiguration
import com.freedom.features.aireply.ai.AiModelMessage
import com.freedom.features.aireply.ai.AiModelRequest
import com.freedom.features.aireply.ai.AiModelResponseFormat
import com.freedom.features.aireply.ai.AiModelResult
import com.freedom.features.aireply.ai.AiModelTransport
import com.freedom.features.aireply.ai.TokenUsage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/** Safe, non-secret transport failure suitable for diagnostics. Response bodies are never exposed. */
class AiModelHttpException(
    override val safeCode: String,
    override val retryable: Boolean = false,
    cause: Throwable? = null,
    override val httpStatus: Int? = null,
    override val stage: AiModelCallStage = AiModelCallStage.MODEL_CALL,
    override val providerErrorCode: String? = null,
    override val providerErrorType: String? = null,
    override val providerErrorParam: String? = null
) : IOException(safeCode, cause), com.freedom.features.aireply.ai.AiModelCallFailureDetails

/** One trace per invocation: concurrent calls cannot overwrite each other's status or stage. */
internal class AiModelHttpCallTrace {
    var stage = AiModelCallStage.CONFIGURATION
    var httpStatus: Int? = null
    private var providerErrorCode: String? = null
    private var providerErrorType: String? = null
    private var providerErrorParam: String? = null

    // 只提取白名单内的错误码/类型/参数，绝不保留自由文本 message 或响应正文。
    fun captureProviderError(body: String) {
        val root = MiniJson.parse(body) as? Map<*, *> ?: return
        val error = root["error"] as? Map<*, *> ?: return
        providerErrorCode = (error["code"] as? String)?.let {
            com.freedom.features.aireply.ai.AiModelCallDiagnostics.safeProviderError(it)
        }
        providerErrorType = (error["type"] as? String)?.let {
            com.freedom.features.aireply.ai.AiModelCallDiagnostics.safeProviderError(it)
        }
        providerErrorParam = (error["param"] as? String)?.let {
            com.freedom.features.aireply.ai.AiModelCallDiagnostics.safeProviderParam(it)
        }
    }

    fun <T> capture(block: (AiModelHttpCallTrace) -> T): Result<T> = runCatching { block(this) }
        .recoverCatching { failure ->
            val known = failure as? AiModelHttpException
            val safeCode = known?.safeCode ?: when (failure.message) {
                "AI_MODEL_NOT_CONFIGURED", "AI_MODEL_PROVIDER_MISMATCH", "HTTPS_REQUIRED" -> failure.message!!
                else -> "HTTP_PREPARATION_FAILED"
            }
            throw AiModelHttpException(
                safeCode = safeCode,
                retryable = known?.retryable ?: false,
                // Retain the original wrapper/type and cause for technical diagnostics only.
                cause = failure,
                httpStatus = httpStatus,
                stage = stage,
                providerErrorCode = providerErrorCode,
                providerErrorType = providerErrorType,
                providerErrorParam = providerErrorParam
            )
        }
}

/** Opens a connection; injected in tests so the transport can be verified without network access. */
fun interface AiHttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

/**
 * OpenAI-compatible Chat Completions transport. The configured API Base URL is treated as a
 * prefix (normally ending in /v1); /chat/completions is appended unless already present.
 * It never logs request bodies, response bodies, API keys, or conversation text.
 */
class OpenAiCompatibleAiModelTransport(
    private val configuration: AiModelConfiguration,
    private val connectionFactory: AiHttpConnectionFactory = AiHttpConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    }
) : AiModelTransport {

    override fun execute(request: AiModelRequest): Result<AiModelResult> = AiModelHttpCallTrace().capture { trace ->
        val config = configuration.normalized()
        require(config.isReadyForCloudProvider) { "AI_MODEL_NOT_CONFIGURED" }
        trace.stage = AiModelCallStage.ENDPOINT
        val endpoint = chatCompletionsEndpoint(config.apiBaseUrl)
        trace.stage = AiModelCallStage.REQUEST_ENCODING
        val payload = buildRequestPayload(config.modelName, request)
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
            val bodyBytes = payload.toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            trace.stage = AiModelCallStage.REQUEST_WRITE
            connection.outputStream.use { output -> output.write(bodyBytes) }

            trace.stage = AiModelCallStage.RESPONSE_STATUS
            val status = connection.responseCode
            trace.httpStatus = status
            if (status !in 200..299) {
                // Read only a bounded amount to release the stream; do not surface provider text.
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
        messages += mapOf("role" to "system", "content" to request.systemInstruction)
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
            "max_completion_tokens" to request.maxOutputTokens,
            "stream" to false
        )
        when (val format = request.responseFormat) {
            AiModelResponseFormat.PlainText -> Unit
            is AiModelResponseFormat.JsonSchema -> {
                val schemaValue = MiniJson.parse(format.schema)
                payload["response_format"] = mapOf(
                    "type" to "json_schema",
                    "json_schema" to mapOf(
                        "name" to format.name,
                        "strict" to format.strict,
                        "schema" to schemaValue
                    )
                )
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
        val content = when (val value = message["content"]) {
            is String -> value
            is List<*> -> value.mapNotNull { item ->
                (item as? Map<*, *>)?.get("text") as? String
            }.joinToString(separator = "")
            else -> null
        }
        val usageMap = root["usage"] as? Map<*, *>
        val usage = usageMap?.let {
            val input = (it["prompt_tokens"] as? Number)?.toInt()
                ?: (it["input_tokens"] as? Number)?.toInt()
            val output = (it["completion_tokens"] as? Number)?.toInt()
                ?: (it["output_tokens"] as? Number)?.toInt()
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
        const val PROVIDER_NAME = "openai-compatible"
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}

/** 适配器使用的小型严格 JSON 编解码器，避免 JVM 测试依赖 Android JSON 桩。 */
internal object MiniJson {
    fun stringify(value: Any?): String = buildString { appendValue(value) }

    fun parse(source: String): Any? = Parser(source).parse()

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Boolean, is Int, is Long, is Double, is Float -> append(value.toString())
            is Number -> {
                require(value.toDouble().isFinite())
                append(value.toString())
            }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    require(entry.key is String)
                    appendQuoted(entry.key as String)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            else -> error("Unsupported JSON value")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): Any? {
            skipWhitespace()
            val result = readValue()
            skipWhitespace()
            require(index == source.length) { "Trailing JSON data" }
            return result
        }

        private fun readValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> error("Invalid JSON value")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val values = LinkedHashMap<String, Any?>()
            if (peek() == '}') {
                index++
                return values
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                val value = readValue()
                require(!values.containsKey(key)) { "Duplicate JSON key" }
                values[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return values
                    }
                    else -> error("Invalid JSON object separator")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val values = ArrayList<Any?>()
            if (peek() == ']') {
                index++
                return values
            }
            while (true) {
                values += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return values
                    }
                    else -> error("Invalid JSON array separator")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val output = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when {
                    char == '"' -> return output.toString()
                    char == '\\' -> {
                        require(index < source.length) { "Incomplete JSON escape" }
                        when (val escape = source[index++]) {
                            '"' -> output.append('"')
                            '\\' -> output.append('\\')
                            '/' -> output.append('/')
                            'b' -> output.append('\b')
                            'f' -> output.append('\u000C')
                            'n' -> output.append('\n')
                            'r' -> output.append('\r')
                            't' -> output.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length)
                                output.append(source.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("Invalid JSON escape: $escape")
                        }
                    }
                    char.code < 0x20 -> error("Unescaped JSON control character")
                    else -> output.append(char)
                }
            }
            error("Unterminated JSON string")
        }

        private fun readNumber(): Number {
            val start = index
            if (peek() == '-') index++
            if (peek() == '0') index++ else {
                require(peek() in '1'..'9')
                while (peek() in '0'..'9') index++
            }
            if (peek() == '.') {
                index++
                require(peek() in '0'..'9')
                while (peek() in '0'..'9') index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                require(peek() in '0'..'9')
                while (peek() in '0'..'9') index++
            }
            val token = source.substring(start, index)
            return token.toLongOrNull() ?: token.toDouble().also { require(it.isFinite()) }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(source.regionMatches(index, literal, 0, literal.length))
            index += literal.length
            return value
        }

        private fun expect(expected: Char) {
            require(peek() == expected) { "Expected JSON token" }
            index++
        }

        private fun peek(): Char? = source.getOrNull(index)

        private fun skipWhitespace() {
            while (peek() in setOf(' ', '\n', '\r', '\t')) index++
        }
    }
}
