package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageOcrTarget
import com.freedom.features.aireply.domain.message.MessageContentQuality
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.aireply.domain.message.OcrContentRect
import com.freedom.features.aireply.domain.message.OcrContentKind
import com.freedom.features.aireply.domain.message.OcrIsolatedTextEvidence
import com.freedom.features.aireply.domain.message.OcrTextProjection
import com.freedom.features.aireply.domain.message.OcrTextProjectionStatus
import com.freedom.features.aireply.domain.message.OcrTokenSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoOcrTextProjectorTest {
    private fun line(text: String, top: Int = 0): OcrVisualLine =
        OcrVisualLine(text, OcrVisualRect(0, top, 150, top + 24), emptyList())

    private fun project(lines: List<OcrVisualLine>, regions: List<OcrVisualRect> = emptyList()) =
        MomoOcrVisualSymbolGuard.reconcileWithTokens(lines, regions).let { result ->
            MomoOcrTextProjector.extract(result.text, result.tokens)
        }

    @Test
    fun independentOrdinaryTextLinesAreCompleteAndKeepLineBreaks() {
        val result = project(listOf(line("晚上好"), line("明天见", 80)))!!
        assertEquals("晚上好\n明天见", result.text)
        assertEquals(OcrTextProjectionStatus.COMPLETE, result.status)
        assertEquals(listOf(0, 1), result.includedLineIndices)
        assertTrue(result.excludedLineIndices.isEmpty())
    }

    @Test
    fun recognizedUnicodeEmojiSeparatesFromSameLineWithoutGuessingCharacterBoxes() {
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("你好😊还没睡吗")), emptyList()
        )
        val projection = MomoOcrTextProjector.extract(rendered.text, rendered.tokens)!!
        assertEquals("你好😊还没睡吗", rendered.text)
        assertEquals("你好还没睡吗", projection.text)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
        assertEquals(listOf(0), projection.includedLineIndices)
        assertEquals(MessageContentQuality.VERIFIED, MomoOcrQualityPolicy.classify(rendered.text))
        assertNull(rendered.tokens.first().symbolBounds)
    }

    @Test
    fun uncertaintyWithinSameLineIsNeverStrippedToAdmitNeighborText() {
        val region = OcrVisualRect(22, 2, 44, 23)
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("在吗a")), listOf(region)
        )
        assertEquals("${MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY} 在吗a", rendered.text)
        assertNull(MomoOcrTextProjector.extract(rendered.text, rendered.tokens))
    }

    @Test
    fun edgeVisualRegionNeedsSeparateMatchingOcrEvidence() {
        val line = OcrVisualLine("哈哈，我也是，牛马生活", OcrVisualRect(0, 0, 392, 59), emptyList())
        val region = OcrVisualRect(355, 14, 391, 48)
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(line), listOf(region))
        assertNull(MomoOcrTextProjector.extract(rendered.text, rendered.tokens))
        val bounds = OcrTextProjection.isolatedCropBounds(OcrContentRect(0, 0, 392, 59),
            OcrContentRect(355, 14, 391, 48), 392, 59)!!
        assertEquals(OcrContentRect(0, 0, 353, 59), bounds)
        val proof = OcrIsolatedTextEvidence(bounds, OcrContentRect(1, 16, 350, 47), line.text, 0.95f)
        val tokens = rendered.tokens.map {
            if (it.kind == OcrContentKind.TEXT) it.copy(isolatedTextEvidence = proof) else it
        }
        val projection = MomoOcrTextProjector.extract(rendered.text, tokens)!!
        assertEquals(line.text, projection.text)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
        assertEquals(MessageContentQuality.AMBIGUOUS, MomoOcrQualityPolicy.classify(rendered.text))
        assertNull(OcrTextProjection.isolatedCropBounds(OcrContentRect(0, 0, 392, 59),
            OcrContentRect(180, 14, 214, 48), 392, 59))
        assertEquals(OcrContentRect(38, 0, 392, 59),
            OcrTextProjection.isolatedCropBounds(OcrContentRect(0, 0, 392, 59),
                OcrContentRect(0, 14, 36, 48), 392, 59))
    }

    @Test
    fun independentlyRecognizedLinesSurviveASeparateUncertainVisualLine() {
        val region = OcrVisualRect(20, 42, 46, 61)
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("今晚有空吗"), line("在吗a", 40), line("明天也可以", 90)),
            listOf(region)
        )
        val projection = MomoOcrTextProjector.extract(rendered.text, rendered.tokens)!!
        assertEquals("今晚有空吗\n明天也可以", projection.text)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
        assertEquals(listOf(0, 2), projection.includedLineIndices)
        assertEquals(listOf(1), projection.excludedLineIndices)
        assertEquals(MessageContentQuality.AMBIGUOUS, MomoOcrQualityPolicy.classify(rendered.text))
    }

    @Test
    fun independentTrailingTextSurvivesLeadingUnknownVisualLine() {
        val region = OcrVisualRect(20, 4, 45, 23)
        val projection = project(
            listOf(line("e"), line("你好呀", 75)),
            listOf(region)
        )!!
        assertEquals("你好呀", projection.text)
        assertEquals(listOf(1), projection.includedLineIndices)
        assertEquals(listOf(0), projection.excludedLineIndices)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
    }

    @Test
    fun pureEmojiOrSymbolOnlyCannotBecomeTextProjection() {
        for (text in listOf("😊", "❤️", "1️⃣", ")(", "?!")) {
            assertNull(MomoOcrTextProjector.extract(
                MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(line(text)), emptyList()).text,
                MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(line(text)), emptyList()).tokens
            ))
        }
    }

    @Test
    fun literalMarkerCannotBeWashedIntoUsableText() {
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("正常正文"), line(MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL, 80)),
            emptyList()
        )
        val projection = MomoOcrTextProjector.extract(rendered.text, rendered.tokens)!!
        assertEquals("正常正文", projection.text)
        assertEquals(listOf(1), projection.excludedLineIndices)
        assertEquals(OcrTextProjectionStatus.PARTIAL, projection.status)
    }

    @Test
    fun mismatchedOrReorderedTokensMustFailClosed() {
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("第一行"), line("第二行", 80)), emptyList()
        )
        assertNull(MomoOcrTextProjector.extract(rendered.text + "尾部", rendered.tokens))
        assertNull(MomoOcrTextProjector.extract(rendered.text, rendered.tokens.reversed()))
        assertNull(MomoOcrTextProjector.extract(rendered.text, rendered.tokens.dropLast(1)))
    }

    @Test
    fun unsupportedSymbolGeometryOrSourceCannotBePromoted() {
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("你好")), emptyList()
        )
        val token = rendered.tokens.single()
        assertNull(MomoOcrTextProjector.extract(
            rendered.text, listOf(token.copy(symbolBounds = OcrContentRect(0, 0, 10, 10)))
        ))
        assertNull(MomoOcrTextProjector.extract(
            rendered.text, listOf(token.copy(source = OcrTokenSource.VISUAL_GUARD))
        ))
        assertNull(MomoOcrTextProjector.extract(
            rendered.text, listOf(token.copy(lineBounds = null))
        ))
    }

    @Test
    fun lowConfidencePunctuationAndNeighborTextRemainInadmissibleTogether() {
        val line = OcrVisualLine("你好？", OcrVisualRect(0, 0, 150, 24),
            listOf(OcrVisualSymbol("？", OcrVisualRect(22, 1, 38, 23), 0.2f))
        )
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(line), emptyList())
        assertEquals("你好${MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL}", rendered.text)
        assertNull(MomoOcrTextProjector.extract(rendered.text, rendered.tokens))
    }

    @Test
    fun P2ProjectionRemainsSeparateFromP0MessageAdmissionAndIdentity() {
        val target = ChatMessageOcrTarget(
            rowIndex = 3,
            rowBounds = ScreenRect(0, 100, 720, 185),
            bodyBounds = ScreenRect(140, 114, 450, 166),
            bubbleBounds = ScreenRect(140, 100, 450, 176),
            avatarBounds = ScreenRect(15, 100, 85, 170)
        )
        val line = line("你好😊还没睡")
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(listOf(line), emptyList())
        val projection = MomoOcrTextProjector.extract(rendered.text, rendered.tokens)!!
        val messages = MomoChatOcrCandidateMapper.parse(
            listOf(target), mapOf(3 to rendered.text),
            ScreenRect(0, 0, 720, 900), ChatMessageParser(), 10
        )
        assertEquals(1, messages.size)
        assertEquals(rendered.text, messages.single().content)
        assertEquals(MessageContentQuality.VERIFIED, messages.single().contentQuality)
        assertEquals("你好还没睡", projection.text)
        val batch = ChatMessageBatch(
            status = MessageReadStatus.SUCCESS,
            windowSessionKey = "current-window",
            messages = messages,
            scannedRowCount = 1,
            candidateRowCount = 1,
            truncated = false,
            capturedAtMillis = 1L,
            ocrTokensByRowIndex = mapOf(3 to rendered.tokens),
            ocrTextProjectionsByRowIndex = mapOf(3 to projection)
        )
        assertEquals(rendered.text, batch.messages.single().content)
        assertEquals(projection, batch.ocrTextProjectionsByRowIndex[3])
        assertTrue(batch.messages.single().isAiInputEligible)
    }

    @Test
    fun P2ProjectionNeverMakesP0AmbiguousMessageAiEligible() {
        val region = OcrVisualRect(20, 42, 46, 61)
        val rendered = MomoOcrVisualSymbolGuard.reconcileWithTokens(
            listOf(line("今晚有空吗"), line("在吗a", 40)), listOf(region)
        )
        val projection = MomoOcrTextProjector.extract(rendered.text, rendered.tokens)!!
        assertEquals("今晚有空吗", projection.text)
        val target = ChatMessageOcrTarget(
            rowIndex = 3,
            rowBounds = ScreenRect(0, 100, 720, 185),
            bodyBounds = ScreenRect(140, 114, 450, 166),
            bubbleBounds = ScreenRect(140, 100, 450, 176),
            avatarBounds = ScreenRect(15, 100, 85, 170)
        )
        val message = MomoChatOcrCandidateMapper.parse(
            listOf(target), mapOf(3 to rendered.text),
            ScreenRect(0, 0, 720, 900), ChatMessageParser(), 10
        ).single()
        assertEquals(MessageContentQuality.AMBIGUOUS, message.contentQuality)
        assertFalse(message.isAiInputEligible)
        assertEquals(rendered.text, message.content)
    }

    @Test
    fun projectedContentIsHiddenInDebugStrings() {
        val secret = "OCR_PROJECTION_PRIVATE_TEXT"
        val projection = project(listOf(line(secret)))!!
        val result = MomoChatOcrResult(
            textByRowIndex = mapOf(3 to secret),
            attemptedTargetCount = 1,
            failedTargetCount = 0,
            textProjectionsByRowIndex = mapOf(3 to projection)
        )
        val batch = ChatMessageBatch(
            status = MessageReadStatus.SUCCESS,
            windowSessionKey = "window",
            messages = emptyList(),
            scannedRowCount = 1,
            candidateRowCount = 0,
            truncated = false,
            capturedAtMillis = 1L,
            ocrTextProjectionsByRowIndex = mapOf(3 to projection)
        )
        assertFalse(projection.toString().contains(secret))
        assertFalse(result.toString().contains(secret))
        assertFalse(batch.toString().contains(secret))
        assertTrue(projection.toString().contains("[REDACTED]"))
    }
}
