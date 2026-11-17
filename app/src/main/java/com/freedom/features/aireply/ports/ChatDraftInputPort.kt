package com.freedom.features.aireply.ports

import com.freedom.core.accessibility.api.NodeTarget

/** Original request metadata for diagnostics; input uses a freshly located correct composer. */
data class ChatDraftInputTarget(
    val packageName: String,
    val activityClass: String,
    val windowIdentityKey: String,
    val conversationIdentityKey: String,
    val windowSessionKey: String,
    val nodeTarget: NodeTarget
)

enum class ChatDraftInputStatus {
    WRITTEN,
    ALREADY_PRESENT,
    VERIFIED,
    STALE_CHAT,
    INPUT_NOT_FOUND,
    INPUT_NOT_EDITABLE,
    INPUT_NOT_EMPTY,
    ACTION_REJECTED,
    TEXT_MISMATCH,
    VERIFICATION_FAILED
}

data class ChatDraftInputResult(
    val status: ChatDraftInputStatus,
    /** Stable non-sensitive diagnostic code; never contains message text or node contents. */
    val reasonCode: String = status.name
) {
    val succeeded: Boolean
        get() = status == ChatDraftInputStatus.WRITTEN ||
            status == ChatDraftInputStatus.ALREADY_PRESENT ||
            status == ChatDraftInputStatus.VERIFIED
}

/**
 * The only UI capability exposed to AI draft generation. There is intentionally no send method.
 * Implementations must locate the current correct writable composer each time.
 * Stored window/activity/path metadata is diagnostic only and must not block a live composer.
 */
interface ChatDraftInputPort {
    fun writeDraft(target: ChatDraftInputTarget, expectedText: String, text: String): ChatDraftInputResult
    fun verifyDraft(target: ChatDraftInputTarget, text: String): ChatDraftInputResult
}
