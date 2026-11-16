package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Test

class MomoOcrTextSelectorTest {
    @Test
    fun chineseResultIsNeverPollutedByUnalignedLatinTokens() {
        assertEquals("在吗", MomoOcrTextSelector.selectPrimary("在吗", "在吗a"))
        assertEquals("怎么了？", MomoOcrTextSelector.selectPrimary("怎么了？", "ELT"))
    }

    @Test
    fun latinIsUsedOnlyWhenChineseRecognizerReturnsNoText() {
        assertEquals("hello 123", MomoOcrTextSelector.selectPrimary("", "hello 123"))
        assertEquals("在吗", MomoOcrTextSelector.selectPrimary("在吗", ""))
    }
}
