package com.freedom.adapters.social.momo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoChatUiTextFilterTest {

    @Test
    fun filtersRandomMatchGreetingOriginNoticeWithPunctuationVariants() {
        assertTrue(MomoChatUiTextFilter.isNonMessageUiText("通过随机匹配向你打招呼。"))
        assertTrue(MomoChatUiTextFilter.isNonMessageUiText("通过随机匹配向你打招呼"))
        assertTrue(MomoChatUiTextFilter.isNonMessageUiText(" 通过随机匹配向你打招呼！ "))
    }

    @Test
    fun filtersScreenRecordedGreetingOriginNotice() {
        assertTrue(MomoChatUiTextFilter.isNonMessageUiText("通过敲敲向你打招呼"))
        assertTrue(MomoChatUiTextFilter.isNonMessageUiText("通过敲敲向你打招呼。"))
    }

    @Test
    fun filtersKnownMomoPromptsAndStandaloneToolbarLabels() {
        assertTrue(MomoChatUiTextFilter.isNonMessageUiText("是否关注对方？"))
        assertTrue(MomoChatUiTextFilter.isNonMessageUiText("关注后可以订阅对方动态"))
        assertTrue(MomoChatUiTextFilter.isNonMessageUiText("来自在线推荐"))
        assertTrue(MomoChatUiTextFilter.isNonMessageUiText("发送"))
        assertTrue(MomoChatUiTextFilter.isNonMessageUiText("图片"))
    }

    @Test
    fun preservesOrdinaryMessagesContainingUiWords() {
        assertFalse(MomoChatUiTextFilter.isNonMessageUiText("我平时不太关注别人动态，也很少发送图片。"))
        assertFalse(MomoChatUiTextFilter.isNonMessageUiText("如果你喜欢，可以给我发一张图片。"))
        assertFalse(MomoChatUiTextFilter.isNonMessageUiText("hi，喜欢吃辣的嘛"))
    }
}
