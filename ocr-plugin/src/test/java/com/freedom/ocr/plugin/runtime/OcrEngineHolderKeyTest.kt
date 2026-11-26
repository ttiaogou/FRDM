package com.freedom.ocr.plugin.runtime

import com.paddle.ocr.PaddleOCRConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OcrEngineHolderKeyTest {
    private val defaultConfig = PaddleOCRConfig()

    private fun key(
        config: PaddleOCRConfig = defaultConfig,
        model: String = "ppocrv6_small",
        version: String = "1",
        threads: Int = 2,
    ) = OcrEngineHolder.EngineKey.from(model, version, threads, config)

    @Test
    fun differentImageSizesReuseTheSameEngineKey() {
        val dimensions = listOf(429, 296, 425, 169, 265, 296, 425)
        val keys = dimensions.map { longSide ->
            key(defaultConfig.copy(detMaxSideLimit = resolveDetectionMaxSideLimit(0, longSide)))
        }
        assertEquals(1, keys.distinct().size)
    }

    @Test
    fun requestLimitRetainsOriginalPrecedence() {
        assertEquals(429, resolveDetectionMaxSideLimit(0, 429))
        assertEquals(169, resolveDetectionMaxSideLimit(0, 169))
        assertEquals(512, resolveDetectionMaxSideLimit(512, 429))
        assertEquals(296, resolveDetectionMaxSideLimit(-1, 296))
        assertEquals(4000, resolveDetectionMaxSideLimit(0, 0))
        assertEquals(4000, resolveDetectionMaxSideLimit(-1, -1))
    }

    @Test
    fun modelVersionThreadsAndOtherConfigStillInvalidateEngineKey() {
        val original = key()
        assertNotEquals(original, key(model = "other"))
        assertNotEquals(original, key(version = "2"))
        assertNotEquals(original, key(threads = 4))
        listOf(
            defaultConfig.copy(detLimitSideLen = 96),
            defaultConfig.copy(detLimitType = "max"),
            defaultConfig.copy(detThresh = 0.4f),
            defaultConfig.copy(detBoxThresh = 0.7f),
            defaultConfig.copy(detUnclipRatio = 1.8f),
            defaultConfig.copy(recScoreThresh = 0.5f),
            defaultConfig.copy(recBatchSize = 2),
        ).forEach { assertNotEquals(original, key(it)) }
    }
}
