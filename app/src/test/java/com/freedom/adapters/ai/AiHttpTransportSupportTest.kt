package com.freedom.adapters.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class AiHttpTransportSupportTest {
    @Test
    fun endpointKeepsProviderPathAndRejectsNonHttps() {
        val path = "/chat/completions"
        assertEquals("https://api.example.com/v1/chat/completions",
            AiHttpTransportSupport.chatCompletionsEndpoint(" https://api.example.com/v1/// ", path))
        assertEquals("HTTPS://api.example.com/CHAT/COMPLETIONS",
            AiHttpTransportSupport.chatCompletionsEndpoint("HTTPS://api.example.com/CHAT/COMPLETIONS/", path))
        assertEquals("https://api.example.com/chat/completions",
            AiHttpTransportSupport.chatCompletionsEndpoint("https://api.example.com", path))
        try {
            AiHttpTransportSupport.chatCompletionsEndpoint("http://api.example.com", path)
            fail("HTTP endpoint must not be accepted")
        } catch (expected: IllegalArgumentException) {
            assertEquals("HTTPS_REQUIRED", expected.message)
        }
    }

    @Test
    fun boundedReadPreservesBytesAndExactLimitAndRejectsOverflow() {
        val payload = ByteArray(8193) { (it % 251).toByte() }
        assertArrayEquals(ByteArray(0),
            AiHttpTransportSupport.readBounded(ByteArrayInputStream(ByteArray(0)), 0))
        assertArrayEquals(payload,
            AiHttpTransportSupport.readBounded(ByteArrayInputStream(payload), payload.size))
        try {
            AiHttpTransportSupport.readBounded(ByteArrayInputStream(payload), payload.size - 1)
            fail("Oversized response must be rejected")
        } catch (expected: AiModelHttpException) {
            assertEquals("HTTP_RESPONSE_TOO_LARGE", expected.safeCode)
            assertFalse(expected.retryable)
        }
    }

    @Test
    fun errorCodesAndRetryFlagsRemainIdentical() {
        val expected = mapOf(
            401 to ("HTTP_401_UNAUTHORIZED" to false),
            403 to ("HTTP_403_FORBIDDEN" to false),
            404 to ("HTTP_404_NOT_FOUND" to false),
            408 to ("HTTP_408_TIMEOUT" to true),
            413 to ("HTTP_413_REQUEST_TOO_LARGE" to false),
            429 to ("HTTP_429_RATE_LIMITED" to true),
            500 to ("HTTP_5XX_SERVER_ERROR" to true),
            599 to ("HTTP_5XX_SERVER_ERROR" to true),
            418 to ("HTTP_418" to false),
            600 to ("HTTP_600" to false)
        )
        for ((status, pair) in expected) {
            val error = AiHttpTransportSupport.httpFailure(status)
            assertEquals(pair.first, error.safeCode)
            assertEquals(pair.second, error.retryable)
        }
        assertTrue(AiHttpTransportSupport.httpFailure(503).retryable)
    }
}
