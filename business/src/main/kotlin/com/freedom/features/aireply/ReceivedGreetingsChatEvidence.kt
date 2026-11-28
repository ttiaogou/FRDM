package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy

/** Window-scoped chat evidence observed from one immutable page snapshot. */
internal data class ReceivedGreetingsObservedChatWindowEvidence(
    val chatTitleKey: String?,
    val windowSessionKey: String?
)

/** Non-null proof that the observed snapshot belongs to the expected greeting chat. */
internal data class ReceivedGreetingsVerifiedChatWindowEvidence(
    val chatTitleKey: String,
    val windowSessionKey: String
)

/** Exact header comparison; no lifecycle, identity cache, task lease or recovery reads. */
internal fun matchesReceivedGreetingsChatHeader(
    snapshot: ChatPageSnapshot,
    displayName: String?
): Boolean = ChatSessionManagementPolicy.matchChatHeader(displayName, snapshot.chatTitleText) != null

/** Collects the two existing snapshot-derived keys in their original evaluation order. */
internal fun collectReceivedGreetingsChatWindowEvidence(
    snapshot: ChatPageSnapshot
): ReceivedGreetingsObservedChatWindowEvidence {
    val sessionKey = windowSessionKey(snapshot)
    val titleKey = snapshot.conversationIdentityKey()
    return ReceivedGreetingsObservedChatWindowEvidence(
        chatTitleKey = titleKey,
        windowSessionKey = sessionKey
    )
}

/** Pure page/window gate. A caller must still verify task lifecycle, identity and current lease. */
internal fun verifyReceivedGreetingsChatWindowEvidence(
    snapshot: ChatPageSnapshot,
    expectedPackage: String,
    evidence: ReceivedGreetingsObservedChatWindowEvidence
): ReceivedGreetingsVerifiedChatWindowEvidence? {
    if (snapshot.packageName != expectedPackage ||
        snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
        evidence.windowSessionKey == null || evidence.chatTitleKey == null
    ) return null
    return ReceivedGreetingsVerifiedChatWindowEvidence(
        chatTitleKey = evidence.chatTitleKey,
        windowSessionKey = evidence.windowSessionKey
    )
}

/**
 * A transition can expose chat anchors before its composer/list enters the display.
 * This is binding readiness, not a page fingerprint or a gesture target calculation.
 * Only explicit offscreen evidence defers admission; missing diagnostic evidence
 * does not replace the existing independent identity/window proof.
 */
internal fun hasOffscreenGreetingChatLocators(snapshot: ChatPageSnapshot): Boolean {
    val display = snapshot.screenBounds?.takeUnless { it.isEmpty } ?: return false
    val paths = listOfNotNull(snapshot.messageListTarget, snapshot.inputFieldTarget)
        .map { it.path.childIndices.joinToString("/") }
    return snapshot.structureEvidence.any { node ->
        node.path in paths && node.boundsValid &&
            (node.right <= display.left || node.left >= display.right ||
                node.bottom <= display.top || node.top >= display.bottom)
    }
}
