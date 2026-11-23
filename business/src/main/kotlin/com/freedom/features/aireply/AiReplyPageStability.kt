package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot

/**
 * Keeps a previously confirmed inbox classification through a transient UNKNOWN only when the
 * current snapshot independently retains the same app/window/navigation anchor and a live list
 * viewport. Fresh row/viewport data is never copied from the previous snapshot.
 */
internal fun stabilizeConfirmedChatList(
    previous: ChatPageSnapshot?,
    incoming: ChatPageSnapshot
): ChatPageSnapshot {
    if (previous?.pageKind != ChatPageKind.CHAT_LIST || incoming.pageKind != ChatPageKind.UNKNOWN) {
        return incoming
    }
    if (previous.packageName != incoming.packageName ||
        previous.activityClass != incoming.activityClass ||
        previous.windowGeneration <= 0L ||
        incoming.windowGeneration != previous.windowGeneration ||
        !previous.hasChatTab || !incoming.hasChatTab
    ) return incoming

    val previousTarget = previous.sessionListNavigationTarget ?: return incoming
    val currentTarget = incoming.sessionListNavigationTarget ?: return incoming
    if (previous.messageTabAnchorPositionKey() == null ||
        previous.messageTabAnchorPositionKey() != incoming.messageTabAnchorPositionKey() ||
        previousTarget.nodeTarget.path != currentTarget.nodeTarget.path ||
        previousTarget.nodeTarget.expectedViewId != currentTarget.nodeTarget.expectedViewId
    ) return incoming

    val anchorPath = currentTarget.nodeTarget.path.childIndices.joinToString("/")
    val currentAnchorEvidence = incoming.structureEvidence.any { node ->
        node.path == anchorPath && node.viewId.endsWith("maintab_layout_chat") &&
            node.isSelected && node.boundsValid && node.right > node.left && node.bottom > node.top
    }
    val selectedBubbleEvidence = incoming.structureEvidence.any { node ->
        node.viewId.endsWith("fl_bubble_container") && node.isSelected &&
            node.isVisibleToUser && node.boundsValid && node.right > node.left && node.bottom > node.top &&
            node.path.startsWith("$anchorPath/")
    }
    if (!currentAnchorEvidence || !selectedBubbleEvidence) return incoming
    val viewport = incoming.chatListViewport?.takeUnless { it.isEmpty } ?: return incoming
    val currentInboxEvidence = incoming.structureEvidence.any { node ->
        node.className.substringAfterLast('.').equals("RecyclerView", ignoreCase = true) &&
            node.viewId.substringAfterLast('/').equals("recyclerview", ignoreCase = true) &&
            node.isVisibleToUser && node.boundsValid &&
            node.left == viewport.left && node.top == viewport.top &&
            node.right == viewport.right && node.bottom == viewport.bottom
    }
    if (!currentInboxEvidence) return incoming
    return incoming.copy(pageKind = ChatPageKind.CHAT_LIST, hasChatTab = true)
}

