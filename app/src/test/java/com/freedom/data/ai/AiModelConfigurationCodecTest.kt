package com.freedom.data.ai

import com.freedom.features.aireply.ai.AiModelConfiguration
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
        val invalidVersion = encoded.copyOf().apply { this[7] = 2 }
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


}
