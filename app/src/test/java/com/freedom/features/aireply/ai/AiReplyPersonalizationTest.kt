package com.freedom.features.aireply.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReplyPersonalizationTest {
    @Test
    fun dailyChatDefaultsUseTheNewProviderNeutralPreferences() {
        val preferences = AiReplyPersonalization()

        assertEquals(PersonalityStyle.FLIRTATIOUS_PROBING, preferences.personalityStyle)
        assertEquals(LanguageStyle.CASUAL, preferences.languageStyle)
        assertEquals(ReplyLength.MINIMAL, preferences.replyLength)
        assertEquals(EmotionExpression.NATURAL_MODERATE, preferences.emotionExpression)
        assertEquals(HumorLevel.OCCASIONAL, preferences.humorLevel)
        assertEquals(InteractionLevel.MODERATE, preferences.interactionLevel)
        assertEquals("", preferences.customInstructions)
    }

    @Test
    fun availableLabelsMatchAllRequestedChoices() {
        assertEquals(
            listOf("暧昧试探", "亲切友好", "温柔治愈", "活泼可爱", "幽默风趣", "细腻敏感"),
            enumValues<PersonalityStyle>().map { it.displayName }
        )
        assertEquals(
            listOf("日常口语", "正式得体", "简洁直接", "知性优雅", "网络流行语"),
            enumValues<LanguageStyle>().map { it.displayName }
        )
        assertEquals(
            listOf("极简", "简短", "适中", "详细"),
            enumValues<ReplyLength>().map { it.displayName }
        )
        assertEquals(
            listOf("克制冷静", "自然舒适", "情绪丰富", "热情积极", "高度共情"),
            enumValues<EmotionExpression>().map { it.displayName }
        )
        assertEquals(
            listOf("严肃认真", "偶尔幽默", "适当玩梗", "幽默风趣", "经常调侃"),
            enumValues<HumorLevel>().map { it.displayName }
        )
        assertEquals(
            listOf("只回答，不主动延伸", "适当互动", "主动提出问题", "主动延伸话题、提供建议"),
            enumValues<InteractionLevel>().map { it.displayName }
        )
    }

    @Test
    fun allPreferencesBecomePlainLanguageInstructionsForAnyProviderAdapter() {
        val prompt = AiReplyPersonalization(
            personalityStyle = PersonalityStyle.FLIRTATIOUS_PROBING,
            languageStyle = LanguageStyle.INTELLECTUAL,
            replyLength = ReplyLength.DETAILED,
            emotionExpression = EmotionExpression.HIGHLY_EMPATHETIC,
            humorLevel = HumorLevel.LIGHT_WORDPLAY,
            interactionLevel = InteractionLevel.EXTEND_AND_SUGGEST,
            customInstructions = "不要频繁使用表情符号。\n不要每次回答都反问我。"
        ).systemPromptInstructions()

        assertTrue(prompt.contains("暧昧和试探感"))
        assertTrue(prompt.contains("知性优雅"))
        assertTrue(prompt.contains("充分解释"))
        assertTrue(prompt.contains("有同理心"))
        assertTrue(prompt.contains("文字趣味"))
        assertTrue(prompt.contains("可行建议"))
        assertTrue(prompt.contains("不要频繁使用表情符号。"))
        assertTrue(prompt.contains("不得覆盖任务要求、事实准确性或安全规则"))
        assertFalse(prompt.contains("temperature"))
        assertFalse(prompt.contains("maxOutputTokens"))
    }

    @Test
    fun customInstructionsAreNormalizedAndLengthValidated() {
        val normalized = AiReplyPersonalization(
            customInstructions = "  不要刻意讨好我。\u0000\n可以偶尔开玩笑。  "
        ).normalized()
        assertEquals("不要刻意讨好我。\n可以偶尔开玩笑。", normalized.customInstructions)
        assertNull(normalized.validationError())

        val tooLong = AiReplyPersonalization(
            customInstructions = "x".repeat(AiReplyPersonalization.MAX_CUSTOM_INSTRUCTION_LENGTH + 1)
        )
        assertNotNull(tooLong.validationError())
    }
}
