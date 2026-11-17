package com.freedom.adapters.social.momo

import com.freedom.features.aireply.ports.ChatDraftInputPort
import com.freedom.features.aireply.ports.ChatDraftInputTarget
import com.freedom.features.aireply.ports.ChatDraftInputResult
import com.freedom.features.aireply.ports.ChatDraftInputStatus

/** 每次仅操作当前正确输入框；窗口代次、标题、旧路径不参与输入准入。 */
internal class MomoDraftInputController(
    private val withCurrentInput: ((Field) -> ChatDraftInputResult) -> ChatDraftInputResult
) : ChatDraftInputPort {
    /** 仅在 withCurrentInput 回调内有效，不能持有跨步骤的 Android 节点。 */
    interface Field {
        fun readText(): String
        fun focus(): Boolean
        fun setText(text: String): Boolean
    }

    override fun writeDraft(target: ChatDraftInputTarget, expectedText: String, text: String): ChatDraftInputResult {
        if (text.isBlank() || text.length > 2_000 || !text.startsWith(expectedText)) {
            return ChatDraftInputResult(ChatDraftInputStatus.ACTION_REJECTED, "DRAFT_TEXT_INVALID")
        }
        val focused = withCurrentInput { input ->
            if (input.readText() != expectedText) {
                ChatDraftInputResult(ChatDraftInputStatus.INPUT_NOT_EMPTY, "USER_INPUT_PRESERVED")
            } else if (input.focus()) {
                ChatDraftInputResult(ChatDraftInputStatus.VERIFIED)
            } else {
                ChatDraftInputResult(ChatDraftInputStatus.ACTION_REJECTED, "INPUT_FOCUS_REJECTED")
            }
        }
        if (!focused.succeeded) return focused
        // 聚焦可触发布局重建，因此再次定位当前输入框。
        return withCurrentInput { input ->
            if (input.readText() != expectedText) {
                ChatDraftInputResult(ChatDraftInputStatus.INPUT_NOT_EMPTY, "INPUT_CHANGED_BEFORE_WRITE")
            } else if (input.setText(text)) {
                ChatDraftInputResult(ChatDraftInputStatus.WRITTEN)
            } else {
                ChatDraftInputResult(ChatDraftInputStatus.ACTION_REJECTED, "INPUT_SET_TEXT_REJECTED")
            }
        }
    }

    override fun verifyDraft(target: ChatDraftInputTarget, text: String): ChatDraftInputResult =
        withCurrentInput { input ->
            if (input.readText() == text) ChatDraftInputResult(ChatDraftInputStatus.VERIFIED)
            else ChatDraftInputResult(ChatDraftInputStatus.TEXT_MISMATCH, "INPUT_TEXT_MISMATCH")
        }
}
