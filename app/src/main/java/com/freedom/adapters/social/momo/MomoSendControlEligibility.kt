package com.freedom.adapters.social.momo

/** Exact Momo text-send evidence, separate from legacy label-based send controls. */
internal enum class MomoComposerSendState {
    EMPTY_CONFIRMED, TEXT_PRESENT_SEND_READY, TEXT_PRESENT_BUTTON_PENDING, INPUT_CHANGED, INPUT_STATE_UNKNOWN
}

internal object MomoSendControlEligibility {
    /** Observation only: absence of the button never proves that an already verified draft is empty. */
    fun observeComposer(
        actualText: String,
        expectedText: String,
        showingHint: Boolean,
        eligibleButtonCount: Int
    ): MomoComposerSendState = when {
        showingHint || actualText.isBlank() -> MomoComposerSendState.EMPTY_CONFIRMED
        actualText != expectedText -> MomoComposerSendState.INPUT_CHANGED
        eligibleButtonCount == 1 -> MomoComposerSendState.TEXT_PRESENT_SEND_READY
        eligibleButtonCount == 0 -> MomoComposerSendState.TEXT_PRESENT_BUTTON_PENDING
        else -> MomoComposerSendState.INPUT_STATE_UNKNOWN
    }

    const val TEXT_SEND_VIEW_ID = "com.immomo.momo:id/message_btn_sendtext"
    const val EDITOR_LAYOUT_VIEW_ID = "com.immomo.momo:id/editor_layout"

    fun accepts(
        viewId: String?,
        className: String?,
        label: String,
        visible: Boolean,
        enabled: Boolean,
        clickable: Boolean,
        sharesEditorParent: Boolean
    ): Boolean {
        if (!visible || !enabled || !clickable) return false
        val normalizedLabel = label.lowercase()
        val sendsByLabel = normalizedLabel.contains("发送") || normalizedLabel.contains("send")
        // Existing supported controls continue to require an explicit send label.
        if (viewId != TEXT_SEND_VIEW_ID) return sendsByLabel
        // The exact text-send ViewId can be temporarily unlabeled during layout changes.
        // Require its verified Button type AND the exact current composer parent.
        return className == "android.widget.Button" && sharesEditorParent &&
            (label.isBlank() || sendsByLabel)
    }
}
