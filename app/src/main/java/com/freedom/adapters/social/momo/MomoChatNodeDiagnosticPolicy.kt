package com.freedom.adapters.social.momo

/**
 * Conservative locator policy for diagnostic-only Momo tree probes.
 * A match here never promotes a page to CHAT_DETAIL_CANDIDATE and never creates a message locator.
 */
internal object MomoChatNodeDiagnosticPolicy {
    const val ALTERNATE_MESSAGE_LIST_VIEW_ID = "com.immomo.momo:id/listview"
    const val GREETING_MESSAGE_ROW_VIEW_ID = "com.immomo.momo:id/hi_message_msg_container"

    fun isAlternateMessageListRoot(
        activityClass: String,
        className: String,
        viewId: String
    ): Boolean = activityClass == MomoChatPageContract.CHAT_DETAIL_ACTIVITY &&
        className == MomoChatPageContract.MESSAGE_LIST_CLASS_NAME &&
        viewId == ALTERNATE_MESSAGE_LIST_VIEW_ID

    fun isGreetingMessageRowCandidate(viewId: String): Boolean =
        viewId == GREETING_MESSAGE_ROW_VIEW_ID
}
