package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.domain.message.ChatMessageType
import com.freedom.features.aireply.domain.message.OcrContentKind
import com.freedom.features.aireply.domain.message.OcrContentToken
import com.freedom.features.aireply.domain.message.OcrTokenSource
import com.freedom.features.aireply.domain.message.OcrTokenStatus

/** Detached text/geometry evidence for one visible Momo message row. */
internal data class MomoChatTextEvidence(
    val value: String,
    val bounds: ScreenRect,
    val depth: Int,
    val childCount: Int,
    val className: String,
    val viewId: String,
    val ancestorBounds: List<ScreenRect>,
    val viewIdTrail: List<String>,
    val path: List<Int>
)

/** Pure Momo text, media-type and bubble-shape interpretation for a single row. */
internal object MomoChatMessageTextEvidencePolicy {
    const val MIN_BUBBLE_WIDTH_RATIO = 0.035f
    const val MAX_BUBBLE_WIDTH_RATIO = 0.90f
    const val MAX_MESSAGE_TEXT_LENGTH = 4_000

    private val MESSAGE_TEXT_ID_HINTS = setOf("message_text", "msg_text", "chat_content", "bubble_text")
    private val IMAGE_HINTS = setOf("msg_image", "message_image", "chat_image", "photo_content", "image_content", "message_gifview")
    private val VOICE_HINTS = setOf("msg_voice", "message_voice", "voice_wave", "audio_message")
    private val VIDEO_HINTS = setOf("msg_video", "message_video", "video_content")
    private val CARD_HINTS = setOf("msg_card", "message_card", "share_card", "link_card", "profile_card")
    private val DYNAMIC_CARD_HINTS = setOf(
        "card_view", "dynamic", "feed", "publish_dynamic"
    )
    private val INTERACTIVE_CARD_HINTS = setOf(
        "cardcontainer", "message_btn_ai_chat", "message_btn_game_together",
        "message_btn_gift_or_loc", "message_btn_gotoaudio", "message_btn_openemotes",
        "message_btn_selectpic", "message_open_emotes_view", "game_content"
    )
    private val IMAGE_DESCRIPTION_HINTS = setOf("图片消息", "照片消息", "image message", "photo message")
    private val VOICE_DESCRIPTION_HINTS = setOf("语音消息", "播放语音", "voice message", "audio message")
    private val VIDEO_DESCRIPTION_HINTS = setOf("视频消息", "video message")
    private val CARD_DESCRIPTION_HINTS = setOf("分享卡片", "链接卡片", "profile card", "shared link")
    private val TIME_LABEL = Regex("^(?:[01]?\\d|2[0-3]):[0-5]\\d$")
    private val DATE_LABEL = Regex("^(?:今天|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|\\d{1,2}月\\d{1,2}日)$")
    private val SYSTEM_TEXT_HINTS = listOf("撤回了一条消息", "你们已经成为好友", "消息发送失败")
    private val NON_MESSAGE_UI_TEXT = setOf(
        "是否关注对方", "关注后可以订阅对方动态", "通过随机匹配向你打招呼",
        "来自在线推荐", "关注", "已关注", "取消关注", "发送", "语音", "图片",
        "更多", "表情", "拍摄", "按住说话"
    )

    fun resolveBubbleBounds(evidence: MomoChatTextEvidence, listBounds: ScreenRect): ScreenRect? {
        val candidates = evidence.ancestorBounds.filter { bounds ->
            !bounds.isEmpty && bounds.width >= listBounds.width * MIN_BUBBLE_WIDTH_RATIO &&
                bounds.width < listBounds.width * MAX_BUBBLE_WIDTH_RATIO &&
                bounds.left >= listBounds.left && bounds.right <= listBounds.right &&
                contains(bounds, evidence.bounds)
        }
        val ancestorBubble = candidates.minByOrNull { it.width }
        if (ancestorBubble != null) return ancestorBubble
        return evidence.bounds.takeIf {
            !it.isEmpty && it.width >= listBounds.width * MIN_BUBBLE_WIDTH_RATIO &&
                it.width < listBounds.width * MAX_BUBBLE_WIDTH_RATIO
        }
    }

    fun inferMessageType(signatures: List<String>, bodyText: String? = null): ChatMessageType {
        val image = containsMessageMediaHint(signatures, IMAGE_HINTS, IMAGE_DESCRIPTION_HINTS) ||
            signatures.any { it.contains("imageview") && it.contains("msg") && !it.contains("avatar") }
        val voice = containsMessageMediaHint(signatures, VOICE_HINTS, VOICE_DESCRIPTION_HINTS)
        val video = containsMessageMediaHint(signatures, VIDEO_HINTS, VIDEO_DESCRIPTION_HINTS)
        val card = containsMessageMediaHint(signatures, CARD_HINTS, CARD_DESCRIPTION_HINTS)
        val interactiveCard = containsAnyHint(signatures, INTERACTIVE_CARD_HINTS)
        val dynamicCard = bodyText.orEmpty().contains("发布了动态") ||
            (containsAnyHint(signatures, DYNAMIC_CARD_HINTS) && !interactiveCard)
        val emoji = isEmojiOnly(bodyText)
        val kinds = listOf(image, voice, video, card, dynamicCard, interactiveCard, emoji).count { it }
        if (kinds != 1) return ChatMessageType.UNKNOWN
        return when {
            image -> ChatMessageType.IMAGE
            emoji -> ChatMessageType.EMOJI
            voice -> ChatMessageType.VOICE
            video -> ChatMessageType.VIDEO
            dynamicCard -> ChatMessageType.DYNAMIC_CARD
            interactiveCard -> ChatMessageType.INTERACTIVE_CARD
            card -> ChatMessageType.CARD
            else -> ChatMessageType.UNKNOWN
        }
    }

    /** Exact body resource names only; do not scan cards or avatar/status decorations as media. */
    fun classifiedMediaBodyIds(): Set<String> = IMAGE_HINTS + VOICE_HINTS + VIDEO_HINTS

    fun classifiedMediaBodyType(viewId: String, trail: List<String>): ChatMessageType? {
        val id = entryName(viewId)
        if (id !in classifiedMediaBodyIds()) return null
        val ids = trail.map(::entryName)
        val root = ids.indexOfLast { it == "message_root" }
        if (root < 0 || ids.lastOrNull() != id) return null
        val scoped = ids.drop(root + 1)
        val content = scoped.indexOf("message_content_layout")
        val container = scoped.indexOf("message_layout_messagecontainer")
        if (content < 0 || container <= content || container >= scoped.lastIndex) return null
        val ancestors = scoped.dropLast(1)
        if (containsAnyHint(ancestors, CARD_HINTS + DYNAMIC_CARD_HINTS + INTERACTIVE_CARD_HINTS)) return null
        return inferMessageType(listOf(id)).takeIf { it != ChatMessageType.UNKNOWN }
    }

    /** Preserve text with independent projection; an unproved graphic is not OCR text.
     * The original OCR string/tokens stay in the local batch for diagnostics and projection.
     * No type inference from a marker literal or an ambiguous glyph alone. */
    fun classifyOcrBody(text: String, tokens: List<OcrContentToken>): ChatMessageType {
        if (tokens.isEmpty()) return ChatMessageType.TEXT // legacy callers without row evidence
        val byLine = tokens.groupBy { it.lineIndex }
        val reconstructed = byLine.toSortedMap().values.joinToString("\n") { line ->
            line.joinToString("") { it.text }
        }
        if (reconstructed.trim() != text.trim()) return ChatMessageType.TEXT
        if (MomoOcrTextProjector.extract(text, tokens) != null) return ChatMessageType.TEXT
        val significant = tokens.filterNot { it.text.isBlank() }
        if (significant.isNotEmpty() && significant.all {
                it.kind == OcrContentKind.EMOJI && it.source == OcrTokenSource.UNICODE_EMOJI &&
                    it.status == OcrTokenStatus.UNVERIFIED
            }) return ChatMessageType.EMOJI
        val visual = significant.any {
            it.kind == OcrContentKind.PLACEHOLDER && it.source == OcrTokenSource.VISUAL_GUARD &&
                it.status == OcrTokenStatus.UNVERIFIED
        }
        // No independently verified text in this row. Keep its ordered UNKNOWN slot rather
        // than fabricate a sticker meaning or let an OCR hallucination masquerade as TEXT.
        return if (visual) ChatMessageType.UNKNOWN else ChatMessageType.TEXT
    }

    private fun entryName(id: String): String = id.substringAfterLast('/').substringAfterLast(':').lowercase()

    private fun containsAnyHint(signatures: List<String>, hints: Set<String>): Boolean =
        signatures.any { signature ->
            if (signature.contains("avatar") || signature.contains("head_icon") ||
                signature.contains("user_icon")) false else hints.any(signature::contains)
        }

    /** A standalone pictogram is a separate message kind; emoji inside prose remains TEXT. */
    private fun isEmojiOnly(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return false
        var sawEmoji = false
        var valid = true
        text.codePoints().toArray().forEach { codePoint ->
            val emoji = codePoint in 0x1F000..0x1FAFF ||
                codePoint in 0x2600..0x27BF || codePoint in 0x2300..0x23FF
            val joiner = codePoint == 0x200D || codePoint == 0xFE0F || codePoint == 0x20E3
            if (!emoji && !joiner) valid = false
            sawEmoji = sawEmoji || emoji
        }
        return valid && sawEmoji
    }

    private fun containsMessageMediaHint(
        signatures: List<String>,
        idHints: Set<String>,
        descriptionHints: Set<String>
    ): Boolean = signatures.any { signature ->
        if (signature.contains("avatar") || signature.contains("head_icon") ||
            signature.contains("user_icon")
        ) false else idHints.any(signature::contains) || descriptionHints.any(signature::contains)
    }

    fun textScore(evidence: MomoChatTextEvidence, listBounds: ScreenRect): Int {
        var score = 0
        if (MESSAGE_TEXT_ID_HINTS.any(evidence.viewId::contains)) score += 1_000
        if (evidence.className.contains("textview")) score += 200
        if (evidence.childCount == 0) score += 40
        if (evidence.depth > 0) score += 10
        if (evidence.bounds.width < listBounds.width * MAX_BUBBLE_WIDTH_RATIO) score += 15
        score += evidence.value.length.coerceAtMost(40)
        if (evidence.viewId.contains("time") || evidence.viewId.contains("stamp")) score -= 2_000
        return score
    }

    fun isLikelyMessageDescription(
        description: String,
        viewId: String,
        className: String,
        clickable: Boolean,
        childCount: Int
    ): Boolean {
        val value = description.trim()
        val lower = value.lowercase()
        if (value.length !in 1..MAX_MESSAGE_TEXT_LENGTH || isNonMessageUiText(value)) return false
        if (lower.startsWith("avatar") || lower.startsWith("头像") ||
            lower.startsWith("profile") || lower.startsWith("用户头像")
        ) return false
        return className.contains("textview") || MESSAGE_TEXT_ID_HINTS.any(viewId::contains) ||
            (!clickable && childCount == 0)
    }

    fun isIgnoredStructuralText(text: String, viewId: String): Boolean {
        if (viewId.contains("time") || viewId.contains("stamp") || viewId.contains("avatar") ||
            MomoChatRowCandidatePolicy.isIgnoredUiTextViewId(viewId)
        ) return true
        val value = text.trim()
        return TIME_LABEL.matches(value) || DATE_LABEL.matches(value) || isNonMessageUiText(value)
    }

    fun isTimeLabel(value: String): Boolean = TIME_LABEL.matches(value.trim()) ||
        DATE_LABEL.matches(value.trim())

    fun isNonMessageUiText(value: String): Boolean {
        val normalized = value.trim().lowercase()
            .replace(Regex("[\\s\\p{Punct}，。！？、：；（）【】《》“”‘’·…]+"), "")
        return NON_MESSAGE_UI_TEXT.any { hint ->
            normalized == hint || normalized.startsWith(hint) || normalized.contains(hint)
        }
    }

    fun isSystemMessage(value: String): Boolean = SYSTEM_TEXT_HINTS.any(value::contains)

    private fun contains(container: ScreenRect, child: ScreenRect): Boolean =
        !container.isEmpty && !child.isEmpty && child.left >= container.left &&
            child.top >= container.top && child.right <= container.right &&
            child.bottom <= container.bottom
}
