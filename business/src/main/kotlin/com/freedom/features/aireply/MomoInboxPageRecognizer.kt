package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot

/**
 * Recognizes the ordinary Momo inbox/chat surfaces from an already acquired page snapshot.
 * This component is deliberately read-only and never requests a snapshot or performs an action.
 */
internal class MomoInboxPageRecognizer {
    enum class Page {
        MESSAGE_LIST,
        CHAT_DETAIL,
        NON_CHAT,
        UNKNOWN
    }

    fun recognize(snapshot: ChatPageSnapshot): Page = when (snapshot.pageKind) {
        ChatPageKind.CHAT_LIST -> Page.MESSAGE_LIST
        ChatPageKind.CHAT_DETAIL_CANDIDATE -> Page.CHAT_DETAIL
        ChatPageKind.NON_CHAT -> Page.NON_CHAT
        ChatPageKind.UNKNOWN -> Page.UNKNOWN
    }

    fun isMessageList(snapshot: ChatPageSnapshot): Boolean =
        recognize(snapshot) == Page.MESSAGE_LIST

    fun isChatDetail(snapshot: ChatPageSnapshot): Boolean =
        recognize(snapshot) == Page.CHAT_DETAIL
}
