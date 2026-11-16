package com.freedom.features.aireply.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AiModelConfigurationTest {
    @Test fun defaultConfigurationUsesRequestContractDefaults() {
        val configuration = AiModelConfiguration()
        assertEquals(0.3, configuration.temperature, 0.0)
        assertEquals(512, configuration.maxOutputTokens)
        assertEquals(30_000L, configuration.requestTimeoutMillis)
        assertFalse(configuration.isReadyForCloudProvider)
    }

    @Test fun completeHttpsConfigurationIsReady() {
        val configuration = AiModelConfiguration(
            apiBaseUrl = "https://api.example.com/v1/",
            modelName = "example-model",
            apiKey = "secret-key"
        )
        assertTrue(configuration.isReadyForCloudProvider)
        assertEquals("https://api.example.com/v1", configuration.normalized().apiBaseUrl)
    }

    @Test fun nonHttpsEndpointIsRejected() {
        val configuration = AiModelConfiguration(
            apiBaseUrl = "http://api.example.com/v1",
            modelName = "example-model",
            apiKey = "secret-key"
        )
        assertTrue(configuration.validationError()?.contains("HTTPS") == true)
        assertFalse(configuration.isReadyForCloudProvider)
    }

    @Test fun endpointWithEmbeddedCredentialsOrQueryIsRejected() {
        val userInfo = AiModelConfiguration(apiBaseUrl = "https://user:pass@example.com/v1")
        val query = AiModelConfiguration(apiBaseUrl = "https://example.com/v1?token=secret")
        assertTrue(userInfo.validationError() != null)
        assertTrue(query.validationError() != null)
    }

    @Test fun rejectsOutOfRangeGenerationParameters() {
        assertThrows(IllegalArgumentException::class.java) { AiModelConfiguration(temperature = 2.1) }
        assertThrows(IllegalArgumentException::class.java) { AiModelConfiguration(maxOutputTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { AiModelConfiguration(requestTimeoutMillis = 500L) }
    }

    @Test fun apiKeyIsRedactedFromToString() {
        val configuration = AiModelConfiguration(apiKey = "highly-sensitive-api-key")
        assertFalse(configuration.toString().contains("highly-sensitive-api-key"))
        assertTrue(configuration.toString().contains("[REDACTED]"))
    }
}
