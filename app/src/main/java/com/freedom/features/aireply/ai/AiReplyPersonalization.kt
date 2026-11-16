package com.freedom.features.aireply.ai

/** User-facing reply preferences. API-specific values stay behind these friendly choices. */
data class AiReplyPersonalization(
    val answerStyle: AnswerStyle = AnswerStyle.EFFICIENT_PRACTICAL,
    val responseFlexibility: ResponseFlexibility = ResponseFlexibility.NATURAL_BALANCED,
    val replyLength: ReplyLength = ReplyLength.MODERATE,
    val friendliness: Friendliness = Friendliness.NATURAL_FRIENDLY,
    val enthusiasm: Enthusiasm = Enthusiasm.MODERATE,
    val emojiUsage: EmojiUsage = EmojiUsage.OCCASIONAL
) {
    /** Instructions for tone and expression; these do not alter numeric model parameters. */
    fun systemPromptInstructions(): String = listOf(
        answerStyle.systemInstruction,
        friendliness.systemInstruction,
        enthusiasm.systemInstruction,
        emojiUsage.systemInstruction
    ).joinToString(separator = "\n") { "- $it" }
}

enum class AnswerStyle(
    val displayName: String,
    val systemInstruction: String
) {
    EFFICIENT_PRACTICAL(
        "高效务实",
        "Be efficient and pragmatic. Lead with the main point and focus on useful, actionable content."
    ),
    NATURAL_CASUAL(
        "自然随和",
        "Use natural, relaxed, conversational language without sounding scripted."
    ),
    PROFESSIONAL(
        "严谨专业",
        "Use precise, well-structured, professional language; distinguish facts from uncertainty."
    ),
    LIGHT_HUMOR(
        "轻松幽默",
        "Use light, appropriate humor naturally; avoid forced jokes or undermining the other person."
    )
}

enum class ResponseFlexibility(
    val displayName: String,
    val temperature: Double
) {
    STRICT_STABLE("严谨稳定", 0.2),
    NATURAL_BALANCED("自然均衡", 0.4),
    FLEXIBLE_DIVERSE("灵活多样", 0.7),
    CREATIVE_RICH("创意丰富", 1.0)
}

enum class ReplyLength(
    val displayName: String,
    val maxOutputTokens: Int
) {
    CONCISE("简短精炼", 256),
    MODERATE("适中", 512),
    DETAILED("详细充分", 1_024)
}

enum class Friendliness(
    val displayName: String,
    val systemInstruction: String
) {
    FORMAL_OBJECTIVE(
        "正式客观",
        "Keep the tone formal and objective; avoid unnecessary familiarity."
    ),
    NATURAL_FRIENDLY(
        "自然友好",
        "Be naturally friendly, considerate, and approachable."
    ),
    WARM_CASUAL(
        "亲切随和",
        "Be warm, relaxed, and familiar while respecting the other person's boundaries."
    ),
    VERY_FRIENDLY(
        "非常亲切",
        "Use a distinctly warm, caring, and supportive tone without excessive flattery."
    )
}

enum class Enthusiasm(
    val displayName: String,
    val systemInstruction: String
) {
    CALM(
        "沉稳冷静",
        "Keep the tone calm and measured; do not overstate excitement."
    ),
    MODERATE(
        "适度热情",
        "Show moderate positive interest while keeping the conversation natural."
    ),
    POSITIVE(
        "积极热情",
        "Be actively positive and encouraging when it fits the conversation."
    ),
    ENERGETIC(
        "充满活力",
        "Use a lively, upbeat tone when appropriate; avoid artificial hype."
    )
}

enum class EmojiUsage(
    val displayName: String,
    val systemInstruction: String
) {
    NONE(
        "不使用",
        "Do not use emoji or text emoticons."
    ),
    OCCASIONAL(
        "偶尔使用",
        "Use emoji occasionally, only where they naturally add warmth or meaning."
    ),
    MODERATE(
        "适量使用",
        "Use emoji moderately in suitable moments; avoid visual clutter."
    ),
    RICH(
        "丰富使用",
        "Use a wider range of relevant emoji regularly, without repetition or clutter."
    )
}


/** Persistence boundary for the user's AI reply style preferences. */
interface AiReplyPersonalizationRepository {
    fun load(): AiReplyPersonalization
    fun save(personalization: AiReplyPersonalization)
}
