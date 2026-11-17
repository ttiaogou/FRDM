package com.freedom.features.aireply.ai

import org.junit.Assert.*
import org.junit.Test

class DraftSegmentsTest {
    @Test fun shortTextIsSplitAndSingleGraphemeIsPreserved() {
        assertEquals(listOf("你", "好"), DraftSegments.split("你好"))
        assertEquals(listOf("👨‍👩‍👧‍👦"), DraftSegments.split("👨‍👩‍👧‍👦"))
    }

    @Test fun emojiAndCombiningMarksAreNotSplit() {
        val text = "你好👨‍👩‍👧‍👦e\u0301🇨🇳再见朋友们"
        val segments = DraftSegments.split(text)
        assertEquals(text, segments.joinToString(""))
        assertEquals(1, segments.count { it.contains("👨‍👩‍👧‍👦") })
        assertEquals(1, segments.count { it.contains("e\u0301") })
        assertEquals(1, segments.count { it.contains("🇨🇳") })
    }

    @Test fun longTextUsesBoundedSegments() {
        val segments = DraftSegments.split("abcdefghijklmnopqrstuvwx")
        assertEquals("abcdefghijklmnopqrstuvwx", segments.joinToString(""))
        assertTrue(segments.all { it.length in 3..8 })
    }
}
