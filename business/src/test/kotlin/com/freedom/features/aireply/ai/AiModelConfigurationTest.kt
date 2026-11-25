package com.freedom.features.aireply.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AiModelConfigurationTest {
    @Test fun defaultConfigurationUsesRequestContractDefaultsAndDeepSeekPresets() {
        val configuration = AiModelConfiguration()
        assertEquals(AiModelServiceProvider.DEEPSEEK, configuration.provider)
        assertEquals("https://api.deepseek.com", configuration.apiBaseUrl)
        assertEquals("deepseek-flash", configuration.modelName)
        assertEquals(0.3, configuration.temperature, 0.0)
        assertEquals(256, configuration.maxOutputTokens)
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
    @Test fun configurationToStringNeverRevealsCustomModelNameOrCredentials() {
        val secretModel = "custom-model_internal-customer-credential_12345"
        val apiKey = "secret-api-key-for-configuration-test"
        val endpoint = "https://private-gateway.example.org/v1"
        val configuration = AiModelConfiguration(
            provider = AiModelServiceProvider.CUSTOM_OPENAI_COMPAT,
            apiBaseUrl = endpoint,
            modelName = secretModel,
            apiKey = apiKey
        )

        val log = configuration.toString()
        assertFalse(log.contains(secretModel))
        assertFalse(log.contains(apiKey))
        assertFalse(log.contains(endpoint))
        assertTrue(log.contains("modelName=[CONFIGURED]"))
        assertTrue(log.contains("apiBaseUrl=[CONFIGURED]"))
        assertTrue(log.contains("apiKey=[REDACTED]"))
        assertEquals(secretModel, configuration.modelName)
        assertEquals(apiKey, configuration.apiKey)
        assertTrue(configuration.isReadyForCloudProvider)
        assertEquals(secretModel, configuration.normalized().modelName)
        assertEquals(apiKey, configuration.normalized().apiKey)
    }

    @Test fun emptyModelConfigurationToStringStatesAreAccurate() {
        val configuration = AiModelConfiguration(
            provider = AiModelServiceProvider.CUSTOM_OPENAI_COMPAT,
            apiBaseUrl = "",
            modelName = "",
            apiKey = ""
        )
        val log = configuration.toString()
        assertTrue(log.contains("modelName=[EMPTY]"))
        assertTrue(log.contains("apiBaseUrl=[EMPTY]"))
        assertTrue(log.contains("apiKey=[REDACTED]"))
        assertFalse(configuration.isReadyForCloudProvider)
    }

    @Test fun providerCatalogContainsSelectableOpenAiAndDeepSeekModels() {
        assertTrue(AiModelServiceProvider.OPENAI.modelOptions.any { it.id == "gpt-6.1-sol" })
        assertTrue(AiModelServiceProvider.OPENAI.modelOptions.any { it.id == "gpt-6.1-terra" })
        assertTrue(AiModelServiceProvider.DEEPSEEK.modelOptions.any { it.id == "deepseek-flash" })
        assertTrue(AiModelServiceProvider.DEEPSEEK.modelOptions.any { it.id == "deepseek-v4-pro" })
    }

    @Test fun legacyEndpointDetectionPreservesCustomOpenAiCompatibleProviders() {
        assertEquals(
            AiModelServiceProvider.DEEPSEEK,
            AiModelServiceProvider.fromLegacyBaseUrl("https://api.deepseek.com")
        )
        assertEquals(
            AiModelServiceProvider.CUSTOM_OPENAI_COMPAT,
            AiModelServiceProvider.fromLegacyBaseUrl("https://gateway.example.com/v1")
        )
    }

}
