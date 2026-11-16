package com.freedom.data.ai

import android.content.Context
import android.content.SharedPreferences
import com.freedom.features.aireply.ai.AiReplyPersonalization
import com.freedom.features.aireply.ai.AiReplyPersonalizationRepository
import com.freedom.features.aireply.ai.AnswerStyle
import com.freedom.features.aireply.ai.ResponseFlexibility
import com.freedom.features.aireply.ai.ReplyLength
import com.freedom.features.aireply.ai.Friendliness
import com.freedom.features.aireply.ai.Enthusiasm
import com.freedom.features.aireply.ai.EmojiUsage

/** Stores only non-sensitive AI reply preferences, independently from task launch filters. */
internal class AiReplyPersonalizationStore(context: Context) : AiReplyPersonalizationRepository {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun load(): AiReplyPersonalization = AiReplyPersonalization(
        answerStyle = readEnum(KEY_ANSWER_STYLE, AnswerStyle.EFFICIENT_PRACTICAL),
        responseFlexibility = readEnum(KEY_RESPONSE_FLEXIBILITY, ResponseFlexibility.NATURAL_BALANCED),
        replyLength = readEnum(KEY_REPLY_LENGTH, ReplyLength.MODERATE),
        friendliness = readEnum(KEY_FRIENDLINESS, Friendliness.NATURAL_FRIENDLY),
        enthusiasm = readEnum(KEY_ENTHUSIASM, Enthusiasm.MODERATE),
        emojiUsage = readEnum(KEY_EMOJI_USAGE, EmojiUsage.OCCASIONAL)
    )

    override fun save(personalization: AiReplyPersonalization) {
        val committed = preferences.edit()
            .putString(KEY_ANSWER_STYLE, personalization.answerStyle.name)
            .putString(KEY_RESPONSE_FLEXIBILITY, personalization.responseFlexibility.name)
            .putString(KEY_REPLY_LENGTH, personalization.replyLength.name)
            .putString(KEY_FRIENDLINESS, personalization.friendliness.name)
            .putString(KEY_ENTHUSIASM, personalization.enthusiasm.name)
            .putString(KEY_EMOJI_USAGE, personalization.emojiUsage.name)
            .commit()
        check(committed) { "无法保存 AI 个性化偏好" }
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T =
        preferences.getString(key, null)
            ?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } }
            ?: fallback

    private companion object {
        const val PREFERENCES_NAME = "freedom_ai_reply_personalization"
        const val KEY_ANSWER_STYLE = "answer_style"
        const val KEY_RESPONSE_FLEXIBILITY = "response_flexibility"
        const val KEY_REPLY_LENGTH = "reply_length"
        const val KEY_FRIENDLINESS = "friendliness"
        const val KEY_ENTHUSIASM = "enthusiasm"
        const val KEY_EMOJI_USAGE = "emoji_usage"
    }
}
