package com.freedom.adapters.social.momo

/**
 * Momo-only allowlist for text evidence that may represent a chat message.
 *
 * A RecyclerView row can also contain greeting prompts, embedded WebViews, games and card
 * controls. Those controls must never become message candidates merely because they expose text.
 * Keep this policy based on resource-id structure, not translated button labels.
 */
internal object MomoChatRowCandidatePolicy {
    private val messageRowAnchorIds = setOf(
        "message_root",
        "message_content_layout",
        "chat_message_item"
    )

    private val explicitMessageBodyIds = setOf(
        "message_tv_layouttextview",
        "message_text",
        "msg_text",
        "chat_content",
        "bubble_text"
    )

    private val messageContentContainerIds = setOf(
        "message_content_layout",
        "message_layout_leftcontainer",
        "message_layout_rightcontainer",
        "message_bubble"
    )

    private val ignoredUiTextIds = setOf(
        "message_tv_timestamp",
        "message_tv_noticemessage",
        "tv_show",
        "rd_tv_show",
        "tv_tips",
        "tv_desc",
        "tv_loading_tips"
    )

    fun hasMessageRowAnchor(viewIds: Collection<String>): Boolean = viewIds.any { rawId ->
        val id = normalizedId(rawId)
        id in messageRowAnchorIds || id in explicitMessageBodyIds
    }

    fun isMessageBodyText(viewId: String, ancestorViewIds: Collection<String>): Boolean {
        val id = normalizedId(viewId)
        if (id in ignoredUiTextIds) return false
        if (id in explicitMessageBodyIds) return true
        return ancestorViewIds.any { normalizedId(it) in messageContentContainerIds }
    }

    fun isIgnoredUiTextViewId(viewId: String): Boolean = normalizedId(viewId) in ignoredUiTextIds

    private fun normalizedId(rawId: String): String =
        rawId.substringAfterLast('/').substringAfterLast(':').lowercase()
}
