package com.freedom.features.aireply.application.message

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.domain.message.ChatMessage
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.domain.message.ChatMessageType
import com.freedom.features.aireply.domain.message.DirectionEvidence
import com.freedom.features.aireply.domain.message.MessageContentQuality
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A platform-neutral candidate extracted from one visible row by a social-app adapter. */
data class ChatMessageCandidate(
    val rowIndex: Int,
    val rowBounds: ScreenRect,
    val bubbleBounds: ScreenRect?,
    val listBounds: ScreenRect,
    val text: String?,
    val typeHint: ChatMessageType = ChatMessageType.UNKNOWN,
    val explicitDirectionHint: ChatMessageDirection? = null,
    val explicitDirectionEvidence: DirectionEvidence = DirectionEvidence.EXPLICIT_LAYOUT_HINT,
    /** Optional ephemeral timestamp label used only to stabilize the in-memory fingerprint. */
    val timeHint: String? = null,
    val isSystemMessage: Boolean = false,
    /** Optional semantic avatar bounds captured from the same visible row. */
    val avatarBounds: ScreenRect? = null,
    /** Text admitted by the adapter's structural message-read path is model-usable by default. */
    val contentQuality: MessageContentQuality = MessageContentQuality.VERIFIED
) {
    override fun toString(): String =
        "ChatMessageCandidate(rowIndex=$rowIndex,typeHint=$typeHint," +
            "directionHint=$explicitDirectionHint,directionEvidence=$explicitDirectionEvidence," +
            "text=[REDACTED],rowBounds=$rowBounds)"
}

/**
 * Pure message interpretation. It deliberately returns UNKNOWN rather than guessing when the
 * bubble does not occupy a confidently left- or right-aligned region.
 */
class ChatMessageParser(
    private val incomingMaxCenterRatio: Float = 0.43f,
    private val outgoingMinCenterRatio: Float = 0.57f,
    private val minBubbleWidthRatio: Float = 0.035f,
    private val maxBubbleWidthRatio: Float = 0.90f
) {
    init {
        require(incomingMaxCenterRatio in 0f..1f)
        require(outgoingMinCenterRatio in 0f..1f)
        require(incomingMaxCenterRatio < outgoingMinCenterRatio)
        require(minBubbleWidthRatio in 0f..1f)
        require(maxBubbleWidthRatio in 0f..1f)
        require(minBubbleWidthRatio < maxBubbleWidthRatio)
    }

    fun parse(candidates: List<ChatMessageCandidate>, limit: Int = 10): List<ChatMessage> {
        require(limit > 0) { "limit must be positive" }
        val ordered = candidates.sortedWith(
            compareBy<ChatMessageCandidate> { it.rowBounds.top }
                .thenBy { it.rowBounds.bottom }
                .thenBy { it.rowIndex }
        )
        val messages = ArrayList<ChatMessage>(minOf(limit, ordered.size))
        val duplicateOccurrences = HashMap<String, Int>()

        ordered.forEach { candidate ->
            val normalizedText = normalizeText(candidate.text)
            val system = candidate.isSystemMessage || isKnownSystemText(normalizedText)
            val filteredText = normalizedText.takeUnless(::isMetadataOnlyText)
            val type = when {
                system -> ChatMessageType.SYSTEM
                candidate.typeHint != ChatMessageType.UNKNOWN -> candidate.typeHint
                !filteredText.isNullOrBlank() -> ChatMessageType.TEXT
                else -> ChatMessageType.UNKNOWN
            }
            if (type == ChatMessageType.UNKNOWN) return@forEach
            if (type == ChatMessageType.TEXT && filteredText.isNullOrBlank()) return@forEach

            val (direction, evidence) = if (system) {
                ChatMessageDirection.UNKNOWN to DirectionEvidence.AMBIGUOUS
            } else {
                resolveDirection(candidate)
            }
            val content = if (type == ChatMessageType.TEXT || type == ChatMessageType.SYSTEM) {
                filteredText
            } else {
                // Non-text payloads are not extracted in Stage 3.
                null
            }
            val baseId = fingerprint(type, direction, content, candidate.timeHint)
            val occurrence = (duplicateOccurrences[baseId] ?: 0) + 1
            duplicateOccurrences[baseId] = occurrence
            val id = if (occurrence == 1) baseId else "$baseId-$occurrence"
            messages += ChatMessage(
                id = id,
                direction = direction,
                type = type,
                content = content,
                bounds = candidate.rowBounds,
                directionEvidence = evidence,
                contentQuality = candidate.contentQuality
            )
        }
        return messages.takeLast(limit)
    }

    private fun resolveDirection(
        candidate: ChatMessageCandidate
    ): Pair<ChatMessageDirection, DirectionEvidence> {
        val explicit = candidate.explicitDirectionHint
        if (explicit != null) {
            return if (explicit == ChatMessageDirection.UNKNOWN) {
                ChatMessageDirection.UNKNOWN to DirectionEvidence.AMBIGUOUS
            } else {
                explicit to candidate.explicitDirectionEvidence
            }
        }

        val list = candidate.listBounds
        val bubble = candidate.bubbleBounds ?: return Pair(
            ChatMessageDirection.UNKNOWN,
            DirectionEvidence.AMBIGUOUS
        )
        if (list.isEmpty || bubble.isEmpty || bubble.width < list.width * minBubbleWidthRatio ||
            bubble.width >= list.width * maxBubbleWidthRatio
        ) {
            return ChatMessageDirection.UNKNOWN to DirectionEvidence.AMBIGUOUS
        }
        val ratio = (bubble.left + bubble.right) / 2f - list.left
        val centerRatio = ratio / list.width.toFloat()
        val bubbleDirection = when {
            centerRatio <= incomingMaxCenterRatio -> ChatMessageDirection.INCOMING
            centerRatio >= outgoingMinCenterRatio -> ChatMessageDirection.OUTGOING
            else -> ChatMessageDirection.UNKNOWN
        }

        // A long message bubble can legitimately extend across the horizontal center even
        // though it is attached to the incoming/outgoing edge. In that center band, a compact
        // avatar discovered inside the same RecyclerView row is the stronger layout anchor.
        // Outside the center band, avatar geometry remains corroborating evidence and a
        // conflict still produces UNKNOWN rather than reversing the bubble-derived direction.
        val avatar = candidate.avatarBounds
        if (avatar == null) {
            return if (bubbleDirection == ChatMessageDirection.UNKNOWN) {
                ChatMessageDirection.UNKNOWN to DirectionEvidence.AMBIGUOUS
            } else {
                bubbleDirection to DirectionEvidence.BUBBLE_GEOMETRY
            }
        }
        if (avatar.isEmpty || avatar.width <= 0 || avatar.height <= 0) {
            return ChatMessageDirection.UNKNOWN to DirectionEvidence.AMBIGUOUS
        }
        val avatarCenterRatio = ((avatar.left + avatar.right) / 2f - list.left) / list.width.toFloat()
        val avatarDirection = when {
            avatarCenterRatio < 0.5f -> ChatMessageDirection.INCOMING
            avatarCenterRatio > 0.5f -> ChatMessageDirection.OUTGOING
            else -> ChatMessageDirection.UNKNOWN
        }
        if (bubbleDirection == ChatMessageDirection.UNKNOWN) {
            return if (avatarDirection != ChatMessageDirection.UNKNOWN) {
                avatarDirection to DirectionEvidence.BUBBLE_AND_AVATAR_GEOMETRY
            } else {
                ChatMessageDirection.UNKNOWN to DirectionEvidence.AMBIGUOUS
            }
        }
        return if (avatarDirection == bubbleDirection) {
            bubbleDirection to DirectionEvidence.BUBBLE_AND_AVATAR_GEOMETRY
        } else {
            ChatMessageDirection.UNKNOWN to DirectionEvidence.AMBIGUOUS
        }
    }

    private fun normalizeText(text: String?): String? = text
        ?.replace('\u00A0', ' ')
        ?.replace(Regex("[\\t ]+"), " ")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(MAX_MESSAGE_TEXT_LENGTH)

    private fun isMetadataOnlyText(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        return TIME_ONLY.matches(text) || DATE_ONLY.matches(text) ||
            COMMON_CHAT_UI_LABELS.contains(text.lowercase())
    }

    private fun isKnownSystemText(text: String?): Boolean =
        !text.isNullOrBlank() && SYSTEM_TEXT_HINTS.any(text::contains)

    private fun fingerprint(
        type: ChatMessageType,
        direction: ChatMessageDirection,
        content: String?,
        timeHint: String?
    ): String {
        val source = listOf(
            type.name,
            direction.name,
            content.orEmpty(),
            timeHint.orEmpty().trim()
        ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
        return "momo-${digest.take(12).joinToString("") { byte -> "%02x".format(byte) }}"
    }

    private companion object {
        const val MAX_MESSAGE_TEXT_LENGTH = 4_000
        val TIME_ONLY = Regex("^(?:[01]?\\d|2[0-3]):[0-5]\\d$")
        val DATE_ONLY = Regex(
            "^(?:今天|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|" +
                "\\d{1,2}月\\d{1,2}日|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})$"
        )
        val COMMON_CHAT_UI_LABELS = setOf(
            "发送", "send", "更多", "more", "语音", "voice", "表情", "emoji",
            "图片", "image", "拍摄", "camera", "加号", "plus"
        )
        val SYSTEM_TEXT_HINTS = listOf(
            "撤回了一条消息", "你们已经成为好友", "对方已拒绝",
            "对方关闭了招呼", "消息已发送失败"
        )
    }
}
