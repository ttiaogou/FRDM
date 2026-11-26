package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.MessageContentQuality
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.aireply.domain.message.OcrContentKind
import com.freedom.features.aireply.domain.message.OcrContentRect
import com.freedom.features.aireply.domain.message.OcrTokenSource
import com.freedom.features.aireply.domain.message.OcrTokenStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoOcrTokenStructureTest {
    private val bounds = OcrVisualRect(0, 0, 120, 28)

    @Test
    fun plainTextBecomesOneRecognizedTokenWithoutInventedCharacterBounds() {
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(OcrVisualLine("你好呀", bounds, emptyList())), emptyList()
        )
        assertEquals("你好呀", result.text)
        assertEquals(listOf(OcrContentKind.TEXT), result.tokens.map { it.kind })
        val token = result.tokens.single()
        assertEquals(OcrTokenStatus.RECOGNIZED, token.status)
        assertEquals(OcrTokenSource.OCR_LINE, token.source)
        assertEquals(OcrContentRect(0, 0, 120, 28), token.lineBounds)
        assertNull(token.symbolBounds)
    }

    @Test
    fun unicodeEmojiInTextBecomesIndependentUnverifiedTokenWithoutChangingP0Text() {
        val line = OcrVisualLine("你😊还没睡", bounds, emptyList())
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(line), emptyList())
        assertEquals("你😊还没睡", result.text)
        assertEquals(listOf("你", "😊", "还没睡"), result.tokens.map { it.text })
        assertEquals(
            listOf(OcrContentKind.TEXT, OcrContentKind.EMOJI, OcrContentKind.TEXT),
            result.tokens.map { it.kind }
        )
        assertEquals(OcrTokenSource.UNICODE_EMOJI, result.tokens[1].source)
        assertEquals(OcrTokenStatus.UNVERIFIED, result.tokens[1].status)
        assertNull(result.tokens[1].symbolBounds)
        assertEquals(MessageContentQuality.VERIFIED, MomoOcrQualityPolicy.classify(result.text))
    }

    @Test
    fun keycapAndJoinedEmojiAreNotSplitIntoStrayTextPieces() {
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(OcrVisualLine("1️⃣👩‍💻", bounds, emptyList())), emptyList()
        )
        assertEquals("1️⃣👩‍💻", result.text)
        assertEquals(listOf(OcrContentKind.EMOJI, OcrContentKind.EMOJI), result.tokens.map { it.kind })
        assertEquals("1️⃣", result.tokens[0].text)
        assertEquals("👩‍💻", result.tokens[1].text)
    }

    @Test
    fun lineLevelVisualEvidenceMarksTextAmbiguousAndKeepsTrueRegionBounds() {
        val region = OcrVisualRect(25, 2, 44, 25)
        val line = OcrVisualLine("在吗a", bounds, emptyList())
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(line), listOf(region))
        assertEquals("${MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY} 在吗a", result.text)
        assertEquals(result.text, MomoOcrVisualSymbolGuard.reconcile(listOf(line), listOf(region)))
        assertEquals(listOf(OcrContentKind.PLACEHOLDER, OcrContentKind.TEXT), result.tokens.map { it.kind })
        assertEquals(OcrTokenSource.VISUAL_GUARD, result.tokens[0].source)
        assertEquals(OcrContentRect(25, 2, 44, 25), result.tokens[0].symbolBounds)
        assertEquals(OcrTokenStatus.AMBIGUOUS, result.tokens[1].status)
        assertNull(result.tokens[1].symbolBounds)
        assertEquals(MessageContentQuality.AMBIGUOUS, MomoOcrQualityPolicy.classify(result.text))
    }

    @Test
    fun visualReplacementKeepsPlaceholderProvenanceInsteadOfClaimingOcrRecognizedIt() {
        val line = OcrVisualLine(
            "在吗e", bounds,
            listOf(OcrVisualSymbol("e", OcrVisualRect(43, 3, 57, 23), 0.9f))
        )
        val color = OcrVisualRect(42, 3, 58, 24)
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(line), listOf(color))
        assertEquals("在吗${MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL}", result.text)
        assertEquals(listOf(OcrContentKind.TEXT, OcrContentKind.PLACEHOLDER), result.tokens.map { it.kind })
        assertEquals(OcrTokenSource.VISUAL_GUARD, result.tokens[1].source)
        assertEquals(OcrContentRect(42, 3, 58, 24), result.tokens[1].symbolBounds)
        assertEquals(OcrTokenStatus.AMBIGUOUS, result.tokens[0].status)
    }

    @Test
    fun literalOcrTextMatchingMarkerIsNotFalselyAttributedToVisualGuard() {
        val literal = MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(OcrVisualLine(literal, bounds, emptyList())), emptyList()
        )
        assertEquals(literal, result.text)
        assertEquals(OcrContentKind.TEXT, result.tokens.single().kind)
        assertEquals(OcrTokenSource.OCR_LINE, result.tokens.single().source)
        assertEquals(MessageContentQuality.AMBIGUOUS, MomoOcrQualityPolicy.classify(result.text))
    }

    @Test
    fun lowConfidencePunctuationCreatesSourceTaggedPlaceholderWithRealSymbolBounds() {
        val punct = OcrVisualRect(21, 2, 34, 24)
        val line = OcrVisualLine(
            "你好？", bounds, listOf(OcrVisualSymbol("？", punct, 0.21f))
        )
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(line), emptyList())
        assertEquals("你好${MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL}", result.text)
        assertEquals(listOf(OcrContentKind.TEXT, OcrContentKind.PLACEHOLDER), result.tokens.map { it.kind })
        assertEquals(OcrTokenSource.OCR_SYMBOL, result.tokens.last().source)
        assertEquals(OcrContentRect(21, 2, 34, 24), result.tokens.last().symbolBounds)
        assertEquals(OcrTokenStatus.AMBIGUOUS, result.tokens.first().status)
    }

    @Test
    fun onlyVisualEvidenceCreatesPlaceholderWithoutInventingAnOcrTextLine() {
        val region = OcrVisualRect(4, 5, 30, 32)
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(emptyList(), listOf(region))
        assertEquals(MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL, result.text)
        assertEquals(OcrContentKind.PLACEHOLDER, result.tokens.single().kind)
        assertNull(result.tokens.single().lineBounds)
        assertEquals(OcrContentRect(4, 5, 30, 32), result.tokens.single().symbolBounds)
    }

    @Test
    fun multiLineTokensKeepOrderingAndDoNotChangeJoinedText() {
        val lines = listOf(
            OcrVisualLine("😊", OcrVisualRect(0, 40, 60, 65), emptyList()),
            OcrVisualLine("睡了吗", OcrVisualRect(0, 0, 60, 25), emptyList())
        )
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(lines, emptyList())
        assertEquals("睡了吗\n😊", result.text)
        assertEquals(listOf(0, 1), result.tokens.map { it.lineIndex })
        assertEquals(listOf(OcrContentKind.TEXT, OcrContentKind.EMOJI), result.tokens.map { it.kind })
    }

    @Test
    fun tokensTravelWithOcrResultAndBatchWithoutLeakingTextInDiagnostics() {
        val secret = "PRIVATE_OCR_TOKEN_SENTINEL"
        val token = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(OcrVisualLine(secret, bounds, emptyList())), emptyList()
        ).tokens.single()
        val result = MomoChatOcrResult(
            textByRowIndex = mapOf(3 to secret),
            attemptedTargetCount = 1,
            failedTargetCount = 0,
            tokensByRowIndex = mapOf(3 to listOf(token))
        )
        val batch = ChatMessageBatch(
            status = MessageReadStatus.SUCCESS,
            windowSessionKey = "window",
            messages = emptyList(),
            scannedRowCount = 1,
            candidateRowCount = 1,
            truncated = false,
            capturedAtMillis = 1L,
            ocrTokensByRowIndex = result.tokensByRowIndex
        )
        assertEquals(secret, batch.ocrTokensByRowIndex[3]!!.single().text)
        assertFalse(result.toString().contains(secret))
        assertFalse(batch.toString().contains(secret))
        assertFalse(token.toString().contains(secret))
        assertTrue(token.toString().contains("[REDACTED]"))
    }
}
