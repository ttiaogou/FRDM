package com.freedom.adapters.ai

import java.io.ByteArrayOutputStream

/** Stateless HTTP primitives shared by the existing independent model transports. */
internal object AiHttpTransportSupport {
    fun chatCompletionsEndpoint(baseUrl: String, completionsPath: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://", ignoreCase = true)) { "HTTPS_REQUIRED" }
        return if (normalized.endsWith(completionsPath, ignoreCase = true)) {
            normalized
        } else {
            "$normalized$completionsPath"
        }
    }

    fun httpFailure(status: Int): AiModelHttpException = when (status) {
        401 -> AiModelHttpException("HTTP_401_UNAUTHORIZED")
        403 -> AiModelHttpException("HTTP_403_FORBIDDEN")
        404 -> AiModelHttpException("HTTP_404_NOT_FOUND")
        408 -> AiModelHttpException("HTTP_408_TIMEOUT", retryable = true)
        413 -> AiModelHttpException("HTTP_413_REQUEST_TOO_LARGE")
        429 -> AiModelHttpException("HTTP_429_RATE_LIMITED", retryable = true)
        in 500..599 -> AiModelHttpException("HTTP_5XX_SERVER_ERROR", retryable = true)
        else -> AiModelHttpException("HTTP_$status")
    }

    fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
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
}
