package com.freedom.features.aireply.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftSegmentsTest {
    @Test
    fun keepsEveryNonSingletonSegmentBetweenTwoAndThreeGraphemes() {
        for (length in listOf(1, 2, 3, 4, 8)) {
            val parts = DraftSegments.split("字".repeat(length))
            assertEquals(length, parts.sumOf { it.codePointCount(0, it.length) })
            assertTrue(parts.all { it.codePointCount(0, it.length) == 1 || it.codePointCount(0, it.length) in 2..3 })
        }
    }

    @Test
    fun preservesEmojiAndCombiningGraphemes() {
        val text = "A👩‍🚀e\u0301。B"
        val parts = DraftSegments.split(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.none { it == "👩" || it == "‍" || it == "🚀" || it == "e" })
    }

    @Test
    fun messageBoundariesComeFromLinesOrExplicitDelimiter() {
        assertEquals(listOf("第一条", "第二条"), ReplyMessageSegments.split("第一条\n第二条"))
        assertEquals(listOf("第一条", "第二条"), ReplyMessageSegments.split("第一条<MSG>第二条"))
        assertEquals(listOf("第一条。第二条"), ReplyMessageSegments.split("第一条。第二条"))
    }
}
