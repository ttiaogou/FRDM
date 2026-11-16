package com.freedom.data.ai

import com.freedom.features.aireply.ai.AiModelConfiguration
import com.freedom.features.aireply.ai.AiModelServiceProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiModelConfigurationCodecTest {
    @Test fun roundTripsCompleteConfiguration() {
        val expected = AiModelConfiguration(
            apiBaseUrl = "https://api.example.com/v1",
            modelName = "example-model",
            apiKey = "secret-configuration-key",
            temperature = 0.7,
            maxOutputTokens = 1_024,
            requestTimeoutMillis = 45_000L
        )
        val decoded = AiModelConfigurationCodec.decode(AiModelConfigurationCodec.encode(expected))
        assertEquals(expected, decoded)
    }

    @Test fun roundTripsEmptyInitialConfiguration() {
        val expected = AiModelConfiguration()
        assertEquals(expected, AiModelConfigurationCodec.decode(AiModelConfigurationCodec.encode(expected)))
    }

    @Test fun rejectsUnknownVersionAndCorruptedHeader() {
        val encoded = AiModelConfigurationCodec.encode(AiModelConfiguration())
        val invalidHeader = encoded.copyOf().apply { this[0] = 0 }
        val invalidVersion = encoded.copyOf().apply { this[7] = 3 }
        assertThrows(IllegalArgumentException::class.java) {
            AiModelConfigurationCodec.decode(invalidHeader)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiModelConfigurationCodec.decode(invalidVersion)
        }
    }

    @Test fun rejectsTrailingPayloadBytes() {
        val encoded = AiModelConfigurationCodec.encode(AiModelConfiguration()) + byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) {
            AiModelConfigurationCodec.decode(encoded)
        }
    }

    @Test fun roundTripsProviderSelectionInVersionTwo() {
        val expected = AiModelConfiguration(
            provider = AiModelServiceProvider.DEEPSEEK,
            apiBaseUrl = "https://api.deepseek.com",
            modelName = "deepseek-v4-pro",
            apiKey = "deepseek-secret",
            requestTimeoutMillis = 60_000L
        )
        assertEquals(expected, AiModelConfigurationCodec.decode(AiModelConfigurationCodec.encode(expected)))
    }

    @Test fun migratesLegacyOpenAiConfigurationWithoutLosingKeyOrParameters() {
        val legacy = encodeLegacyV1(
            baseUrl = "https://api.openai.com/v1",
            modelName = "gpt-4o-mini",
            apiKey = "legacy-openai-key"
        )
        val migrated = AiModelConfigurationCodec.decode(legacy)
        assertEquals(AiModelServiceProvider.OPENAI, migrated.provider)
        assertEquals("https://api.openai.com/v1", migrated.apiBaseUrl)
        assertEquals("gpt-4o-mini", migrated.modelName)
        assertEquals("legacy-openai-key", migrated.apiKey)
    }

    @Test fun migratesLegacyDeepSeekConfigurationToDeepSeekProvider() {
        val legacy = encodeLegacyV1(
            baseUrl = "https://api.deepseek.com",
            modelName = "deepseek-chat",
            apiKey = "legacy-deepseek-key"
        )
        val migrated = AiModelConfigurationCodec.decode(legacy)
        assertEquals(AiModelServiceProvider.DEEPSEEK, migrated.provider)
        assertEquals("https://api.deepseek.com", migrated.apiBaseUrl)
        assertEquals("deepseek-chat", migrated.modelName)
        assertEquals("legacy-deepseek-key", migrated.apiKey)
    }

    @Test fun preservesLegacyCustomCompatibleEndpoint() {
        val legacy = encodeLegacyV1(
            baseUrl = "https://gateway.example.com/v1",
            modelName = "company-model",
            apiKey = "legacy-custom-key"
        )
        val migrated = AiModelConfigurationCodec.decode(legacy)
        assertEquals(AiModelServiceProvider.CUSTOM_OPENAI_COMPAT, migrated.provider)
        assertEquals("https://gateway.example.com/v1", migrated.apiBaseUrl)
        assertEquals("company-model", migrated.modelName)
        assertEquals("legacy-custom-key", migrated.apiKey)
    }

    private fun encodeLegacyV1(
        baseUrl: String,
        modelName: String,
        apiKey: String
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(0x46524149)
            data.writeInt(1)
            data.writeUTF(baseUrl)
            data.writeUTF(modelName)
            data.writeUTF(apiKey)
            data.writeDouble(AiModelConfiguration.DEFAULT_TEMPERATURE)
            data.writeInt(AiModelConfiguration.DEFAULT_MAX_OUTPUT_TOKENS)
            data.writeLong(AiModelConfiguration.DEFAULT_REQUEST_TIMEOUT_MILLIS)
        }
        return output.toByteArray()
    }


}
