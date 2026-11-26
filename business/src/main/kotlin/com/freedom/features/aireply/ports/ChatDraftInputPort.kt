package com.freedom.features.aireply.ports

import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.runtime.TaskLease

/** Original request metadata for diagnostics; input uses a freshly located correct composer. */
data class ChatDraftInputTarget(
    val packageName: String,
    val activityClass: String,
    val windowIdentityKey: String,
    val conversationIdentityKey: String,
    val windowSessionKey: String,
    val nodeTarget: NodeTarget,
    /** Title captured after the chat header was validated; used for send-time continuity only. */
    val expectedChatTitle: String? = null,
    /** Exact lease of the request that produced this draft, not the lease of a later task. */
    val taskLease: TaskLease? = null
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
    VERIFICATION_FAILED,
    SEND_CONTROL_NOT_FOUND,
    SEND_CONTROL_AMBIGUOUS,
    SEND_PRECONDITION_FAILED,
    SEND_GESTURE_REJECTED,
    SEND_SUBMITTED
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
 * Implementations must locate the current correct writable composer each time. The captured
 * chat window/title metadata is a safety gate for the current conversation; the node path itself
 * is never reused as the input locator.
 */
interface ChatDraftInputPort {
    fun writeDraft(target: ChatDraftInputTarget, expectedText: String, text: String): ChatDraftInputResult
    fun verifyDraft(target: ChatDraftInputTarget, text: String): ChatDraftInputResult
}

/** Sends only a freshly validated, unchanged draft through the shared screen-gesture channel. */
interface ChatDraftSendPort {
    fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult

    /** Completion is only an observation signal, never evidence that a message was sent. */
    fun sendDraft(
        target: ChatDraftInputTarget,
        expectedText: String,
        onGestureCompleted: () -> Unit
    ): ChatDraftInputResult = sendDraft(target, expectedText)
}
