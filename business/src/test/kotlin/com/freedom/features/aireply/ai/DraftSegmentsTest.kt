package com.freedom.features.aireply.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun messageBoundariesComeOnlyFromNewlines() {
        assertEquals(listOf("第一条", "第二条"), ReplyMessageSegments.split("第一条\n第二条"))
        assertEquals(listOf("第一条", "第二条"), ReplyMessageSegments.split("第一条\r\n第二条"))
        assertEquals(listOf("第一条", "第二条"), ReplyMessageSegments.split("第一条\r第二条"))
        assertEquals(listOf("第一条", "第二条"), ReplyMessageSegments.split("第一条\n\n第二条"))
        assertEquals(listOf("第一条<MSG>第二条"), ReplyMessageSegments.split("第一条<MSG>第二条"))
        assertEquals(listOf("第一条。第二条"), ReplyMessageSegments.split("第一条。第二条"))
    }

    @Test
    fun exposesSafeParseMetadataWithoutMessage正文() {
        val parsed = ReplyMessageSegments.parse("第一条\n第二条")
        assertEquals(1, parsed.lineBreakCount)
        assertEquals(2, parsed.messages.size)
        assertEquals(listOf(3, 3), parsed.messages.map { it.codePointCount(0, it.length) })
        assertTrue(!parsed.fallbackToSingleMessage)

        val single = ReplyMessageSegments.parse("第一条<MSG>第二条")
        assertEquals(1, single.messages.size)
        assertTrue(single.fallbackToSingleMessage)
    }
}
