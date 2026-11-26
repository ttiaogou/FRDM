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
    fun recognizedTextWithVisualMarkerIsAmbiguous() {
        assertEquals(
            MessageContentQuality.AMBIGUOUS,
            MomoOcrQualityPolicy.classify("在吗${MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL}")
        )
    }

    @Test
    fun recognizedTextWithPunctuationMarkerIsAmbiguous() {
        assertEquals(
            MessageContentQuality.AMBIGUOUS,
            MomoOcrQualityPolicy.classify("你好${MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL}")
        )
    }

    @Test
    fun symbolOnlyRecognizedTextIsAmbiguous() {
        assertEquals(MessageContentQuality.AMBIGUOUS, MomoOcrQualityPolicy.classify(")("))
    }

    @Test
    fun emptyResultIsFailed() {
        assertEquals(MessageContentQuality.FAILED, MomoOcrQualityPolicy.classify(" \n\t"))
    }

    @Test
    fun recognizedTextWithUncertainLineMarkerIsAmbiguous() {
        assertEquals(
            MessageContentQuality.AMBIGUOUS,
            MomoOcrQualityPolicy.classify("${MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY} 在吗a")
        )
    }

    @Test
    fun visualMarkersInTheMiddleOfReadableTextRemainAmbiguous() {
        val markers = listOf(
            MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL,
            MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL,
            MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY
        )
        markers.forEach { marker ->
            assertEquals(
                MessageContentQuality.AMBIGUOUS,
                MomoOcrQualityPolicy.classify("你好${marker}还没睡吗")
            )
        }
    }

    @Test
    fun standaloneEmojiKeycapsAndPunctuationDoNotBecomeVerifiedText() {
        listOf("😊", "❤️", "1️⃣", "?!", "....").forEach { symbol ->
            assertEquals(MessageContentQuality.AMBIGUOUS, MomoOcrQualityPolicy.classify(symbol))
        }
    }

    @Test
    fun normalTextWithPunctuationAndNumbersStaysVerified() {
        listOf("晚上好😊", "Hi :)", "8点见？", "666").forEach { text ->
            assertEquals(MessageContentQuality.VERIFIED, MomoOcrQualityPolicy.classify(text))
        }
    }
}
