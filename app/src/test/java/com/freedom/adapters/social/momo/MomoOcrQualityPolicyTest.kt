package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.MessageContentQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class MomoOcrQualityPolicyTest {
    @Test
    fun singleEngineChineseTextIsUnverified() {
        assertEquals(MessageContentQuality.UNVERIFIED, MomoOcrQualityPolicy.classify("睡不着呀"))
    }

    @Test
    fun ordinarySingleEngineTextNeverBecomesVerifiedFromConfidenceAlone() {
        // The classifier has no confidence-score input by design: a score from the same engine
        // is not independent evidence of transcription correctness.
        assertEquals(MessageContentQuality.UNVERIFIED, MomoOcrQualityPolicy.classify("你好，晚上好"))
    }

    @Test
    fun unknownEmojiPlaceholderMakesMessageAmbiguous() {
        assertEquals(
            MessageContentQuality.AMBIGUOUS,
            MomoOcrQualityPolicy.classify("在吗${MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL}")
        )
    }

    @Test
    fun uncertainPunctuationPlaceholderMakesMessageAmbiguous() {
        assertEquals(
            MessageContentQuality.AMBIGUOUS,
            MomoOcrQualityPolicy.classify("你好${MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL}")
        )
    }

    @Test
    fun symbolOnlyBubbleIsAmbiguousEvenWhenNoColorComponentWasDetected() {
        assertEquals(MessageContentQuality.AMBIGUOUS, MomoOcrQualityPolicy.classify(")("))
    }

    @Test
    fun emptyResultIsFailed() {
        assertEquals(MessageContentQuality.FAILED, MomoOcrQualityPolicy.classify(" \n\t"))
    }

    @Test
    fun lineWithoutCharacterGeometryIsMarkedAmbiguous() {
        assertEquals(
            MessageContentQuality.AMBIGUOUS,
            MomoOcrQualityPolicy.classify("${MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY} 在吗a")
        )
    }
}
