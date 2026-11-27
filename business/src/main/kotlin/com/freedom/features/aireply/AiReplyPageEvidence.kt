package com.freedom.features.aireply

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.events.SelfNavigationTransition
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot

/** Pure page evidence only. No fresh reads, cache updates, task leases or recovery scheduling. */
internal fun recoveryStructuralKey(snapshot: ChatPageSnapshot): String = listOf(
    snapshot.packageName, snapshot.pageKind.name, snapshot.scannedNodeCount,
    snapshot.messageListCount, snapshot.editableInputCount, snapshot.sendActionCount,
    snapshot.messageRelatedViewIdCount, snapshot.hasChatTab,
    snapshot.messageListTarget?.expectedViewId.orEmpty(),
    snapshot.messageListTarget?.expectedClassName.orEmpty(),
    snapshot.chatListViewport?.toString().orEmpty(), snapshot.chatListCandidates.size
).joinToString("|")

internal fun isMessageViewportPositionedAgainstTab(
    snapshot: ChatPageSnapshot,
    tabBounds: ScreenRect,
    displayBounds: ScreenRect
): Boolean {
    val viewport = snapshot.chatListViewport ?: return false
    if (viewport.isEmpty || tabBounds.isEmpty || displayBounds.isEmpty) return false
    if (snapshot.sessionListNavigationTarget?.bounds != tabBounds) return false
    if (viewport.left < displayBounds.left || viewport.top < displayBounds.top ||
        viewport.right > displayBounds.right || viewport.bottom > displayBounds.bottom ||
        tabBounds.left < displayBounds.left || tabBounds.top < displayBounds.top ||
        tabBounds.right > displayBounds.right || tabBounds.bottom > displayBounds.bottom
    ) return false
    val tabCenterX = (tabBounds.left + tabBounds.right) / 2
    if (tabCenterX !in viewport.left..viewport.right || viewport.top >= tabBounds.bottom) return false
    val verticalGap = tabBounds.top - viewport.bottom
    // The list viewport should end at or just above the bottom navigation region. A small
    // overlap accommodates OEM layouts where accessibility bounds include separator pixels.
    return verticalGap in (-displayBounds.height * 8 / 100)..(displayBounds.height * 12 / 100)
}

/**
 * A NON_CHAT -> CHAT_LIST transition needs a structural delta located in the newly exposed
 * inbox viewport. It must also be consistent with the exact Message-tab tap and stable tab
 * anchor; a distant unrelated layout change is not accepted as navigation evidence.
 */
internal fun hasRelevantMessageStructureDelta(
    before: ChatPageSnapshot,
    after: ChatPageSnapshot,
    tabBounds: ScreenRect,
    displayBounds: ScreenRect
): Boolean {
    if (before.pageKind == after.pageKind || after.pageKind != ChatPageKind.CHAT_LIST) return false
    val viewport = after.chatListViewport ?: return false
    val changedPaths = before.changedNavigationNodePaths(after).toSet()
    val changedNodes = after.structureEvidence.filter { it.path in changedPaths && it.boundsValid }
    val hasChangedNodeInInbox = changedNodes.any { node ->
        node.right > viewport.left && node.left < viewport.right &&
            node.bottom > viewport.top && node.top < viewport.bottom
    }
    val hasExplicitListTargetInInbox =
        (after.messageListTarget != null && after.messageListCount > 0) ||
            after.chatListCandidates.isNotEmpty()
    val sameNavigationAnchor = before.messageTabAnchorPositionKey() == after.messageTabAnchorPositionKey()
    val viewportProof = isMessageViewportPositionedAgainstTab(after, tabBounds, displayBounds)
    // The completed tap was compiled from this exact tabBounds by GesturePolicyPipeline;
    // business code intentionally does not receive or inspect the final sampled touch point.
    return sameNavigationAnchor && viewportProof &&
        (hasChangedNodeInInbox || hasExplicitListTargetInInbox)
}

internal fun pageIdentityChanged(previous: ChatPageSnapshot, current: ChatPageSnapshot): Boolean =
    previous.packageName != current.packageName ||
        previous.activityClass != current.activityClass ||
        previous.windowIdentityKey() != current.windowIdentityKey() ||
        previous.pageKind != current.pageKind

internal fun hasSameWindowSession(
    first: ChatPageSnapshot,
    second: ChatPageSnapshot
): Boolean {
    val firstMessageWindow = first.messageListTarget?.window ?: return false
    val firstInputWindow = first.inputFieldTarget?.window ?: return false
    val secondMessageWindow = second.messageListTarget?.window ?: return false
    val secondInputWindow = second.inputFieldTarget?.window ?: return false
    fun sameWindowGeneration(left: com.freedom.core.accessibility.api.WindowRef,
                             right: com.freedom.core.accessibility.api.WindowRef): Boolean =
        left.packageName == right.packageName && left.windowId >= 0 && right.windowId >= 0 &&
            left.windowId == right.windowId && left.generation == right.generation
    return sameWindowGeneration(firstMessageWindow, firstInputWindow) &&
        sameWindowGeneration(secondMessageWindow, secondInputWindow) &&
        sameWindowGeneration(firstMessageWindow, secondMessageWindow) &&
        first.conversationIdentityKey() != null &&
        first.conversationIdentityKey() == second.conversationIdentityKey()
}

internal fun windowSessionKey(snapshot: ChatPageSnapshot): String? {
    val messageWindow = snapshot.messageListTarget?.window ?: return null
    val inputWindow = snapshot.inputFieldTarget?.window ?: return null
    if (messageWindow.packageName != inputWindow.packageName ||
        messageWindow.windowId < 0 || inputWindow.windowId < 0 ||
        messageWindow.windowId != inputWindow.windowId ||
        messageWindow.generation != inputWindow.generation
    ) return null
    val conversationIdentity = snapshot.conversationIdentityKey() ?: return null
    return "${messageWindow.packageName}|${messageWindow.windowId}|${messageWindow.generation}|conversation=$conversationIdentity"
}

/** Matches a generic navigation result to the current page without using business state. */
internal fun selfNavigationMatchesInspection(
    transition: SelfNavigationTransition,
    inspection: ChatPageSnapshot
): Boolean {
    if (transition.targetActivityClass != inspection.activityClass) return false
    if (transition.targetPageGeneration == inspection.windowGeneration) return true
    val observedWindowIds = listOfNotNull(
        inspection.messageListTarget?.window?.windowId,
        inspection.inputFieldTarget?.window?.windowId,
        inspection.sessionListNavigationTarget?.nodeTarget?.window?.windowId
    ).toSet()
    return transition.targetWindowId >= 0 && transition.targetWindowId in observedWindowIds
}

/** Stable chat-surface binding key; deliberately excludes the display name/title. */
internal fun chatSurfaceKey(snapshot: ChatPageSnapshot): String? {
    val messageTarget = snapshot.messageListTarget ?: return null
    val inputTarget = snapshot.inputFieldTarget ?: return null
    val window = messageTarget.window
    val inputWindow = inputTarget.window
    if (window.packageName != inputWindow.packageName ||
        window.windowId != inputWindow.windowId ||
        window.generation != inputWindow.generation
    ) return null
    return listOf(
        window.packageName,
        window.windowId,
        window.generation,
        messageTarget.path.childIndices.joinToString("."),
        inputTarget.path.childIndices.joinToString(".")
    ).joinToString("|")
}
