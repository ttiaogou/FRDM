package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.features.aireply.domain.message.*
import org.junit.Assert.*
import org.junit.Test

/** P4 contract tests from raw OCR line evidence to parsed message plus P2 projection. */
class MomoOcrP4PipelineRegressionTest {
    private val listBounds = ScreenRect(0, 0, 720, 900)

    private fun target(row: Int = 3) = ChatMessageOcrTarget(
        rowIndex = row,
        rowBounds = ScreenRect(0, 200, 720, 335),
        bodyBounds = ScreenRect(140, 215, 450, 300),
        bubbleBounds = ScreenRect(125, 202, 470, 318),
        avatarBounds = ScreenRect(12, 205, 85, 280)
    )

    private fun line(text: String, top: Int = 0, symbols: List<OcrVisualSymbol> = emptyList()) =
        OcrVisualLine(text, OcrVisualRect(0, top, 200, top + 28), symbols)

    private fun map(text: String, target: ChatMessageOcrTarget = target()) =
        MomoChatOcrCandidateMapper.parse(
            listOf(target), mapOf(target.rowIndex to text), listBounds, ChatMessageParser(), 10
        ).single()

    @Test fun pureTextKeepsOriginalContentTypeDirectionAndCompleteProjection() {
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("今晚有空吗")), emptyList()
        )
        val projection = MomoOcrTextProjector.extract(result.text, result.tokens)!!
        val message = map(result.text)
        assertEquals("今晚有空吗", result.text)
        assertEquals(OcrContentKind.TEXT, result.tokens.single().kind)
        assertEquals(OcrTextProjectionStatus.COMPLETE, projection.status)
        assertEquals(result.text, projection.text)
        assertEquals(ChatMessageDirection.INCOMING, message.direction)
        assertEquals(MessageContentQuality.VERIFIED, message.contentQuality)
        assertTrue(message.isAiInputEligible)
    }

    @Test fun unicodeEmojiIsIndependentTokenAndProjectionDoesNotRewriteMessageIdentity() {
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("你好😊还没睡吗")), emptyList()
        )
        val projection = MomoOcrTextProjector.extract(result.text, result.tokens)!!
        val message = map(result.text)
        val originalRevision = message.effectiveContentRevision
        val originalId = message.stableLogicalMessageId
        val batch = ChatMessageBatch(
            MessageReadStatus.SUCCESS, "session", listOf(message), 1, 1, false, 1L,
            ocrTokensByRowIndex = mapOf(3 to result.tokens),
            ocrTextProjectionsByRowIndex = mapOf(3 to projection)
        )
        assertEquals(listOf(OcrContentKind.TEXT, OcrContentKind.EMOJI, OcrContentKind.TEXT),
            result.tokens.map { it.kind })
        assertEquals("你好还没睡吗", projection.text)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
        assertEquals("你好😊还没睡吗", message.content)
        assertEquals(originalId, batch.messages.single().stableLogicalMessageId)
        assertEquals(originalRevision, batch.messages.single().effectiveContentRevision)
        assertFalse(message.toString().contains("你好😊"))
    }

    @Test fun unknownVisualLineIsExcludedWhileIndependentLinesStayOrdered() {
        val region = OcrVisualRect(20, 52, 45, 72)
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("今晚有空吗"), line("在吗a", 48), line("明天也可以", 100)),
            listOf(region)
        )
        val projection = MomoOcrTextProjector.extract(result.text, result.tokens)!!
        val message = map(result.text)
        assertEquals("今晚有空吗\n明天也可以", projection.text)
        assertEquals(listOf(0, 2), projection.includedLineIndices)
        assertEquals(listOf(1), projection.excludedLineIndices)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
        assertTrue(result.tokens.any { it.kind == OcrContentKind.PLACEHOLDER })
        assertEquals(MessageContentQuality.AMBIGUOUS, message.contentQuality)
        assertFalse(message.isAiInputEligible)
        assertEquals(result.text, message.content)
    }

    @Test fun unknownVisualInsideOneLineNeverPromotesItsNeighborText() {
        val region = OcrVisualRect(20, 4, 45, 23)
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("你还没睡吗a")), listOf(region)
        )
        assertTrue(result.text.contains(MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY))
        assertNull(MomoOcrTextProjector.extract(result.text, result.tokens))
        assertFalse(map(result.text).isAiInputEligible)
    }

    @Test fun pureEmojiAndNoisySymbolsCannotCreateModelTextProjection() {
        listOf("😊", "1️⃣", "👩‍💻", ")(", "?!").forEach { raw ->
            val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
                listOf(line(raw)), emptyList()
            )
            assertNull(raw, MomoOcrTextProjector.extract(result.text, result.tokens))
            assertFalse(raw, map(result.text).isAiInputEligible)
        }
        val visualOnly = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            emptyList(), listOf(OcrVisualRect(4, 5, 35, 34))
        )
        assertNull(MomoOcrTextProjector.extract(visualOnly.text, visualOnly.tokens))
        assertFalse(map(visualOnly.text).isAiInputEligible)
    }

    @Test fun lowConfidencePunctuationCannotBecomeVerifiedNearbyText() {
        val glyph = OcrVisualSymbol("？", OcrVisualRect(25, 2, 36, 24), 0.2f)
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("你好？", symbols = listOf(glyph))), emptyList()
        )
        assertTrue(result.text.contains(MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL))
        assertTrue(result.tokens.any { it.source == OcrTokenSource.OCR_SYMBOL })
        assertNull(MomoOcrTextProjector.extract(result.text, result.tokens))
        assertEquals(MessageContentQuality.AMBIGUOUS, map(result.text).contentQuality)
    }

    @Test fun mismatchedOrMissingOcrEvidenceCannotBeUsedForPartialAdmission() {
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("你好😊还没睡")), emptyList()
        )
        assertNull(MomoOcrTextProjector.extract(result.text + "篡改", result.tokens))
        assertNull(MomoOcrTextProjector.extract(result.text, result.tokens.dropLast(1)))
        assertNull(MomoOcrTextProjector.extract(result.text, result.tokens.reversed()))
        val onlyClassification = target().copy(classificationOnly = true)
        assertTrue(MomoChatOcrCandidateMapper.parse(
            listOf(onlyClassification), mapOf(3 to "个人资料"), listBounds,
            ChatMessageParser(), 10
        ).isEmpty())
    }

    @Test fun fullOcrPayloadAndTokenDataAreNotPrintedByBatchDiagnostics() {
        val secret = "P4_SECRET_RAW_OCR_SENTINEL"
        val result = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line(secret)), emptyList()
        )
        val projection = MomoOcrTextProjector.extract(result.text, result.tokens)!!
        val message = map(result.text)
        val batch = ChatMessageBatch(
            MessageReadStatus.SUCCESS, "window", listOf(message), 1, 1, false, 1L,
            ocrTokensByRowIndex = mapOf(3 to result.tokens),
            ocrTextProjectionsByRowIndex = mapOf(3 to projection)
        )
        assertFalse(batch.toString().contains(secret))
        assertFalse(batch.summary().contains(secret))
        assertFalse(result.tokens.single().toString().contains(secret))
        assertFalse(projection.toString().contains(secret))
    }
}
