package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoOcrVisualSymbolGuardTest {
    @Test
    fun coloredEmojiMisreadAsAsciiLetterIsReplacedByExplicitUnknownMarker() {
        val line = OcrVisualLine(
            text = "嗨打个招呼e你这么晚也没睡",
            bounds = OcrVisualRect(0, 0, 160, 24),
            symbols = listOf(
                OcrVisualSymbol("e", OcrVisualRect(58, 3, 70, 19), 0.91f)
            )
        )

        val result = MomoOcrVisualSymbolGuard.reconcile(
            lines = listOf(line),
            colorRegions = listOf(OcrVisualRect(57, 2, 71, 20))
        )

        assertEquals("嗨打个招呼${MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL}你这么晚也没睡", result)
        assertTrue(result.contains(MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL))
        assertTrue(!result.contains("招呼e你"))
    }

    @Test
    fun everyOcrGlyphInsideEmojiRegionIsRemovedBeforePlaceholderInsertion() {
        val line = OcrVisualLine(
            text = ")e",
            bounds = OcrVisualRect(0, 0, 40, 24),
            symbols = listOf(
                OcrVisualSymbol(")", OcrVisualRect(8, 2, 17, 21), 0.95f),
                OcrVisualSymbol("e", OcrVisualRect(17, 2, 29, 21), 0.93f)
            )
        )

        assertEquals(
            MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL,
            MomoOcrVisualSymbolGuard.reconcile(
                listOf(line),
                listOf(OcrVisualRect(7, 1, 30, 22))
            )
        )
    }

    @Test
    fun multipleFalseHanSymbolsOverlappingOneEmojiRegionAreAllRemoved() {
        val line = OcrVisualLine(
            text = "在吗印丑a",
            bounds = OcrVisualRect(0, 0, 110, 24),
            symbols = listOf(
                OcrVisualSymbol("印", OcrVisualRect(38, 2, 50, 21), 0.89f),
                OcrVisualSymbol("丑", OcrVisualRect(50, 2, 62, 21), 0.91f),
                OcrVisualSymbol("a", OcrVisualRect(62, 2, 73, 21), 0.94f)
            )
        )
        assertEquals(
            "在吗${MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL}",
            MomoOcrVisualSymbolGuard.reconcile(
                listOf(line),
                listOf(OcrVisualRect(37, 1, 74, 22))
            )
        )
    }

    @Test
    fun recognizedUnicodeEmojiIsKeptWhenNoFalseTokensOverlap() {
        val line = OcrVisualLine(
            text = "你好😊",
            bounds = OcrVisualRect(0, 0, 60, 24),
            symbols = listOf(OcrVisualSymbol("😊", OcrVisualRect(34, 2, 56, 23), 0.8f))
        )
        assertEquals(
            "你好😊",
            MomoOcrVisualSymbolGuard.reconcile(listOf(line), listOf(OcrVisualRect(35, 3, 55, 22)))
        )
    }

    @Test
    fun ordinaryLatinLetterIsPreservedWithoutVisualColorEvidence() {
        val line = OcrVisualLine(
            text = "love you",
            bounds = OcrVisualRect(0, 0, 100, 24),
            symbols = listOf(OcrVisualSymbol("e", OcrVisualRect(25, 3, 34, 19), 0.95f))
        )

        assertEquals("love you", MomoOcrVisualSymbolGuard.reconcile(listOf(line), emptyList()))
    }

    @Test
    fun alreadyRecognizedUnicodeEmojiIsPreserved() {
        val line = OcrVisualLine(
            text = "嗨😊",
            bounds = OcrVisualRect(0, 0, 48, 24),
            symbols = listOf(OcrVisualSymbol("😊", OcrVisualRect(20, 2, 42, 23), 0.88f))
        )

        assertEquals(
            "嗨😊",
            MomoOcrVisualSymbolGuard.reconcile(
                listOf(line),
                listOf(OcrVisualRect(21, 3, 41, 22))
            )
        )
    }

    @Test
    fun coloredEmojiOmittedByOcrGetsAnExplicitMarkerAtItsApproximatePosition() {
        val line = OcrVisualLine(
            text = "你好世界",
            bounds = OcrVisualRect(0, 0, 100, 24),
            symbols = listOf(
                OcrVisualSymbol("你", OcrVisualRect(0, 2, 16, 22), 0.9f),
                OcrVisualSymbol("好", OcrVisualRect(18, 2, 34, 22), 0.9f),
                OcrVisualSymbol("世", OcrVisualRect(58, 2, 74, 22), 0.9f),
                OcrVisualSymbol("界", OcrVisualRect(76, 2, 92, 22), 0.9f)
            )
        )

        val result = MomoOcrVisualSymbolGuard.reconcile(
            listOf(line),
            listOf(OcrVisualRect(38, 3, 55, 21))
        )

        assertEquals("你好${MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL}世界", result)
    }

    @Test
    fun lowConfidenceUnicodeEmojiIsNotMistakenForUncertainPunctuation() {
        val line = OcrVisualLine(
            text = "你好😊",
            bounds = OcrVisualRect(0, 0, 60, 24),
            symbols = listOf(OcrVisualSymbol("😊", OcrVisualRect(34, 2, 56, 23), 0.21f))
        )
        assertEquals("你好😊", MomoOcrVisualSymbolGuard.reconcile(listOf(line), emptyList()))
    }

    @Test
    fun punctuationIsPreservedWhenThereIsNoColoredGlyphAtItsLocation() {
        val line = OcrVisualLine(
            text = "你好？真的！",
            bounds = OcrVisualRect(0, 0, 100, 24),
            symbols = listOf(OcrVisualSymbol("？", OcrVisualRect(32, 2, 44, 22), 0.9f))
        )
        assertEquals("你好？真的！", MomoOcrVisualSymbolGuard.reconcile(listOf(line), emptyList()))
    }

    @Test
    fun lowConfidencePunctuationIsMarkedForReviewInsteadOfPassedAsCertain() {
        val line = OcrVisualLine(
            text = "你好？真的！",
            bounds = OcrVisualRect(0, 0, 100, 24),
            symbols = listOf(OcrVisualSymbol("？", OcrVisualRect(32, 2, 44, 22), 0.21f))
        )
        assertEquals(
            "你好${MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL}真的！",
            MomoOcrVisualSymbolGuard.reconcile(listOf(line), emptyList())
        )
    }

    @Test
    fun highConfidencePunctuationAndUnavailableConfidenceAreNotChanged() {
        val highConfidence = OcrVisualLine(
            text = "你好？",
            bounds = OcrVisualRect(0, 0, 60, 24),
            symbols = listOf(OcrVisualSymbol("？", OcrVisualRect(32, 2, 44, 22), 0.93f))
        )
        val unavailableConfidence = highConfidence.copy(
            symbols = listOf(OcrVisualSymbol("？", OcrVisualRect(32, 2, 44, 22), 0f))
        )
        assertEquals("你好？", MomoOcrVisualSymbolGuard.reconcile(listOf(highConfidence), emptyList()))
        assertEquals("你好？", MomoOcrVisualSymbolGuard.reconcile(listOf(unavailableConfidence), emptyList()))
    }

    @Test
    fun colorScannerFindsCompactSaturatedGlyphButIgnoresMonochromeTextPixels() {
        val width = 40
        val height = 30
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        for (y in 8 until 20) {
            for (x in 12 until 24) pixels[y * width + x] = 0xFFFFD92F.toInt()
        }

        val detected = MomoOcrVisualSymbolGuard.detectColorRegions(width, height) { x, y ->
            pixels[y * width + x]
        }
        assertEquals(1, detected.size)
        assertEquals(OcrVisualRect(12, 8, 24, 20), detected.single())

        val monochrome = MomoOcrVisualSymbolGuard.detectColorRegions(12, 12) { x, y ->
            if (x in 4..7 && y in 3..8) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()
        }
        assertTrue(monochrome.isEmpty())
    }


    @Test
    fun visualSymbolWithoutCharacterGeometryMakesWholeLineUncertain() {
        val line = OcrVisualLine(
            text = "在吗a",
            bounds = OcrVisualRect(0, 0, 80, 24),
            symbols = emptyList()
        )
        assertEquals(
            "${MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY} 在吗a",
            MomoOcrVisualSymbolGuard.reconcile(
                listOf(line),
                listOf(OcrVisualRect(30, 2, 50, 22))
            )
        )
    }

    @Test
    fun emojiOnlyBubbleProducesMarkerInsteadOfBeingReportedAsEmptyText() {
        assertEquals(
            MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL,
            MomoOcrVisualSymbolGuard.reconcile(
                lines = emptyList(),
                colorRegions = listOf(OcrVisualRect(4, 4, 28, 28))
            )
        )
    }
}
