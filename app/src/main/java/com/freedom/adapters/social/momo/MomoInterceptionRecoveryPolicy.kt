package com.freedom.adapters.social.momo

/** Pure state reconciliation for the existing private interception child. */
internal object MomoInterceptionRecoveryPolicy {
    enum class Page {
        ORIGINAL_CHAT, SETTINGS_OFF, SETTINGS_ON, POPUP_OFF, POPUP_ON,
        SETTINGS_IDENTITY_UNCONFIRMED, SETTINGS_STATE_UNKNOWN,
        POPUP_SOURCE_UNCONFIRMED, POPUP_STATE_UNKNOWN, UNKNOWN
    }
    enum class Action { OPEN_SETTINGS, OPEN_CONTROL, ENABLE, DISMISS, BACK, COMPLETE, WAIT }
    data class Decision(val action: Action, val verifiedOn: Boolean)

    /** The caller must first prove that the unique, visible status node exists. */
    fun settingsChecked(text: String): Boolean? = when (text.trim()) {
        "已开启" -> true
        "" -> false
        else -> null
    }

    fun reconcile(page: Page, verifiedOn: Boolean): Decision = when (page) {
        Page.ORIGINAL_CHAT -> Decision(if (verifiedOn) Action.COMPLETE else Action.OPEN_SETTINGS, verifiedOn)
        Page.SETTINGS_OFF -> Decision(Action.OPEN_CONTROL, false)
        Page.SETTINGS_ON -> Decision(Action.BACK, true)
        Page.POPUP_OFF -> Decision(Action.ENABLE, false)
        Page.POPUP_ON -> Decision(Action.DISMISS, true)
        Page.SETTINGS_IDENTITY_UNCONFIRMED, Page.SETTINGS_STATE_UNKNOWN,
        Page.POPUP_SOURCE_UNCONFIRMED, Page.POPUP_STATE_UNKNOWN,
        Page.UNKNOWN -> Decision(Action.WAIT, verifiedOn)
    }
}
