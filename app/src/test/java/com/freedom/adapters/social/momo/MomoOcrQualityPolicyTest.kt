package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.MessageContentQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class MomoOcrQualityPolicyTest {
    @Test
    fun nonEmptyOcrTextIsAdmittedForModelInput() {
        assertEquals(MessageContentQuality.VERIFIED, MomoOcrQualityPolicy.classify("睡不着呀"))
    }

    @Test
    fun ordinarySingleEngineTextDoesNotRequireIndependentOcrProof() {
        assertEquals(MessageContentQuality.VERIFIED, MomoOcrQualityPolicy.classify("你好，晚上好"))
    }

    @Test
    fun recognizedTextWithVisualMarkerRemainsModelInput() {
        assertEquals(
            MessageContentQuality.VERIFIED,
            MomoOcrQualityPolicy.classify("在吗${MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL}")
        )
    }

    @Test
    fun recognizedTextWithPunctuationMarkerRemainsModelInput() {
        assertEquals(
            MessageContentQuality.VERIFIED,
            MomoOcrQualityPolicy.classify("你好${MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL}")
        )
    }

    @Test
    fun symbolOnlyRecognizedTextIsNotBlockedByTrustClassification() {
        assertEquals(MessageContentQuality.VERIFIED, MomoOcrQualityPolicy.classify(")("))
    }

    @Test
    fun emptyResultIsFailed() {
        assertEquals(MessageContentQuality.FAILED, MomoOcrQualityPolicy.classify(" \n\t"))
    }

    @Test
    fun recognizedTextWithUncertainLineMarkerRemainsModelInput() {
        assertEquals(
            MessageContentQuality.VERIFIED,
            MomoOcrQualityPolicy.classify("${MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY} 在吗a")
        )
    }
}
