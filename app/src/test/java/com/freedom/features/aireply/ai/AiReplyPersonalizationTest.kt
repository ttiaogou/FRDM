package com.freedom.features.aireply.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReplyPersonalizationTest {
    @Test fun dailyChatDefaultsUseTheRequestedCombination() {
        val preferences = AiReplyPersonalization()

        assertEquals(AnswerStyle.EFFICIENT_PRACTICAL, preferences.answerStyle)
        assertEquals(ResponseFlexibility.NATURAL_BALANCED, preferences.responseFlexibility)
        assertEquals(ReplyLength.MODERATE, preferences.replyLength)
        assertEquals(Friendliness.NATURAL_FRIENDLY, preferences.friendliness)
        assertEquals(Enthusiasm.MODERATE, preferences.enthusiasm)
        assertEquals(EmojiUsage.OCCASIONAL, preferences.emojiUsage)
    }

    @Test fun availableLabelsMatchTheRequestedPersonalizationChoices() {
        assertEquals(
            listOf("高效务实", "自然随和", "严谨专业", "轻松幽默"),
            enumValues<AnswerStyle>().map { it.displayName }
        )
        assertEquals(
            listOf("严谨稳定", "自然均衡", "灵活多样", "创意丰富"),
            enumValues<ResponseFlexibility>().map { it.displayName }
        )
        assertEquals(
            listOf("简短精炼", "适中", "详细充分"),
            enumValues<ReplyLength>().map { it.displayName }
        )
        assertEquals(
            listOf("正式客观", "自然友好", "亲切随和", "非常亲切"),
            enumValues<Friendliness>().map { it.displayName }
        )
        assertEquals(
            listOf("沉稳冷静", "适度热情", "积极热情", "充满活力"),
            enumValues<Enthusiasm>().map { it.displayName }
        )
        assertEquals(
            listOf("不使用", "偶尔使用", "适量使用", "丰富使用"),
            enumValues<EmojiUsage>().map { it.displayName }
        )
    }

    @Test fun responseFlexibilityMapsToTemperaturePresets() {
        assertEquals(0.2, ResponseFlexibility.STRICT_STABLE.temperature, 0.0)
        assertEquals(0.4, ResponseFlexibility.NATURAL_BALANCED.temperature, 0.0)
        assertEquals(0.7, ResponseFlexibility.FLEXIBLE_DIVERSE.temperature, 0.0)
        assertEquals(1.0, ResponseFlexibility.CREATIVE_RICH.temperature, 0.0)
    }

    @Test fun replyLengthMapsToOutputTokenPresets() {
        assertEquals(256, ReplyLength.CONCISE.maxOutputTokens)
        assertEquals(512, ReplyLength.MODERATE.maxOutputTokens)
        assertEquals(1_024, ReplyLength.DETAILED.maxOutputTokens)
    }

    @Test fun tonePreferencesAreExpressedAsSystemPromptInstructions() {
        val prompt = AiReplyPersonalization(
            answerStyle = AnswerStyle.LIGHT_HUMOR,
            friendliness = Friendliness.VERY_FRIENDLY,
            enthusiasm = Enthusiasm.ENERGETIC,
            emojiUsage = EmojiUsage.RICH
        ).systemPromptInstructions()

        assertTrue(prompt.contains("light, appropriate humor"))
        assertTrue(prompt.contains("distinctly warm"))
        assertTrue(prompt.contains("lively, upbeat"))
        assertTrue(prompt.contains("wider range of relevant emoji"))
    }
}
