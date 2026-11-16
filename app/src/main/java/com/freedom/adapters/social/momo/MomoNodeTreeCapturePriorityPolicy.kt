package com.freedom.adapters.social.momo

import com.freedom.core.logging.NodeTreeCapturePriority

/** Assigns archive priority only after the current Momo page has been structurally identified. */
internal object MomoNodeTreeCapturePriorityPolicy {
    fun classify(
        activityClass: String,
        pageType: MomoChatPageType? = null
    ): NodeTreeCapturePriority {
        val keyActivity = activityClass == MomoSayHiPageContract.LIST_ACTIVITY ||
            activityClass == MomoSayHiPageContract.CARD_ACTIVITY ||
            activityClass == MomoChatPageContract.CHAT_DETAIL_ACTIVITY ||
            activityClass == MomoSayHiPageContract.PROFILE_ACTIVITY
        return if (keyActivity || pageType == MomoChatPageType.CHAT_DETAIL_CANDIDATE) {
            NodeTreeCapturePriority.KEY_PAGE
        } else {
            NodeTreeCapturePriority.STANDARD
        }
    }
}
