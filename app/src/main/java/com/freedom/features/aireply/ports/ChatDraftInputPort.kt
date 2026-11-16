package com.freedom.features.aireply.ports

import com.freedom.core.accessibility.api.NodeTarget

/** Short-lived, window-scoped locator used to validate one chat input field before draft entry. */
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
 * Implementations must revalidate the live window, conversation, and editable target each time.
 */
interface ChatDraftInputPort {
    fun writeDraft(target: ChatDraftInputTarget, text: String): ChatDraftInputResult
    fun verifyDraft(target: ChatDraftInputTarget, text: String): ChatDraftInputResult
}
