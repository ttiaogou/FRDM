package com.freedom.data.ai

import android.content.Context
import android.content.SharedPreferences
import com.freedom.features.aireply.ai.AiReplyPersonalization
import com.freedom.features.aireply.ai.AiReplyPersonalizationRepository
import com.freedom.features.aireply.ai.EmotionExpression
import com.freedom.features.aireply.ai.HumorLevel
import com.freedom.features.aireply.ai.InteractionLevel
import com.freedom.features.aireply.ai.LanguageStyle
import com.freedom.features.aireply.ai.PersonalityStyle
import com.freedom.features.aireply.ai.ReplyLength

/** Stores non-sensitive, provider-neutral AI reply preferences separately from task filters. */
internal class AiReplyPersonalizationStore(context: Context) : AiReplyPersonalizationRepository {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun load(): AiReplyPersonalization = AiReplyPersonalization(
        personalityStyle = readEnum(KEY_PERSONALITY_STYLE, PersonalityStyle.FLIRTATIOUS_PROBING),
        languageStyle = readEnum(KEY_LANGUAGE_STYLE, LanguageStyle.CASUAL),
        replyLength = readReplyLength(),
        emotionExpression = readEnum(KEY_EMOTION_EXPRESSION, EmotionExpression.NATURAL_MODERATE),
        humorLevel = readEnum(KEY_HUMOR_LEVEL, HumorLevel.OCCASIONAL),
        interactionLevel = readEnum(KEY_INTERACTION_LEVEL, InteractionLevel.MODERATE),
        customInstructions = preferences.getString(KEY_CUSTOM_INSTRUCTIONS, "")
            .orEmpty()
            .take(AiReplyPersonalization.MAX_CUSTOM_INSTRUCTION_LENGTH)
    ).normalized()

    override fun save(personalization: AiReplyPersonalization) {
        val normalized = personalization.normalized()
        require(normalized.validationError() == null) {
            normalized.validationError() ?: "AI 个性化偏好无效"
        }
        val committed = preferences.edit()
            .putString(KEY_PERSONALITY_STYLE, normalized.personalityStyle.name)
            .putString(KEY_LANGUAGE_STYLE, normalized.languageStyle.name)
            .putString(KEY_REPLY_LENGTH, normalized.replyLength.name)
            .putString(KEY_EMOTION_EXPRESSION, normalized.emotionExpression.name)
            .putString(KEY_HUMOR_LEVEL, normalized.humorLevel.name)
            .putString(KEY_INTERACTION_LEVEL, normalized.interactionLevel.name)
            .putString(KEY_CUSTOM_INSTRUCTIONS, normalized.customInstructions)
            .commit()
        check(committed) { "无法保存 AI 个性化偏好" }
    }

    private fun readReplyLength(): ReplyLength = when (preferences.getString(KEY_REPLY_LENGTH, null)) {
        // Preserve the closest equivalent for users upgrading from the previous settings screen.
        "CONCISE" -> ReplyLength.SHORT
        else -> readEnum(KEY_REPLY_LENGTH, ReplyLength.MINIMAL)
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T =
        preferences.getString(key, null)
            ?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } }
            ?: fallback

    private companion object {
        const val PREFERENCES_NAME = "freedom_ai_reply_personalization"
        const val KEY_PERSONALITY_STYLE = "personality_style"
        const val KEY_LANGUAGE_STYLE = "language_style"
        const val KEY_REPLY_LENGTH = "reply_length"
        const val KEY_EMOTION_EXPRESSION = "emotion_expression"
        const val KEY_HUMOR_LEVEL = "humor_level"
        const val KEY_INTERACTION_LEVEL = "interaction_level"
        const val KEY_CUSTOM_INSTRUCTIONS = "custom_instructions"
    }
}
