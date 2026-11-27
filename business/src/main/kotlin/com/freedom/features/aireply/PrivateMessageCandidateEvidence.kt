package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence

/** Read-only private-list evidence; the traversal executor still owns decisions and state. */
internal fun countEligiblePrivateRows(snapshot: ChatPageSnapshot, unreadStatus: ChatUnreadStatus): Int =
    snapshot.chatListCandidates.count {
        it.unreadStatus == unreadStatus && ChatSessionManagementPolicy.isEligible(it)
    }

internal fun countAffectedEligibleRows(snapshot: ChatPageSnapshot, ambiguousKeys: Set<String>): Int =
    snapshot.chatListCandidates.count {
        ChatSessionManagementPolicy.stableSessionKey(it) in ambiguousKeys &&
            ChatSessionManagementPolicy.isEligible(it)
    }

internal fun hasOnlyVerifiedEligiblePrivateRows(snapshot: ChatPageSnapshot): Boolean {
    val eligibleRows = snapshot.chatListCandidates.filter(ChatSessionManagementPolicy::isEligible)
    return eligibleRows.isNotEmpty() && eligibleRows.all {
        it.contactIdentity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
            !it.contactIdentity?.contactId.isNullOrBlank()
    }
}

/** Existing diagnostic evidence, not click admission. The run-local sets are supplied by their owner. */
internal fun formatUnreadAudit(
    snapshot: ChatPageSnapshot,
    snapshotAmbiguousKeys: Set<String>,
    ambiguousSessionIdentityKeys: Set<String>,
    attemptedSessionKeys: Set<String>
): String {
    val candidates = snapshot.chatListCandidates
    val privateRows = candidates.filter { it.selectionClass == "PRIVATE_CHAT" }
    val systemRows = candidates.filter { it.selectionClass == "SYSTEM_ENTRY" }
    val unread = candidates.filter { it.unreadStatus == ChatUnreadStatus.UNREAD }
    val unreadPrivate = unread.filter { it.selectionClass == "PRIVATE_CHAT" }
    val unknownPrivate = privateRows.filter { it.unreadStatus == ChatUnreadStatus.UNKNOWN }
    val eligibleUnknownPrivate = unknownPrivate.count(ChatSessionManagementPolicy::isEligible)
    val unreadSystem = systemRows.count { it.unreadStatus == ChatUnreadStatus.UNREAD }
    val unknownSystem = systemRows.count { it.unreadStatus == ChatUnreadStatus.UNKNOWN }
    val eligibleUnread = unreadPrivate.filter(ChatSessionManagementPolicy::isEligible)
    val blockedUnread = eligibleUnread.count {
        ChatSessionManagementPolicy.stableSessionKey(it) in snapshotAmbiguousKeys ||
            ChatSessionManagementPolicy.stableSessionKey(it) in ambiguousSessionIdentityKeys
    }
    val attemptedUnread = eligibleUnread.count {
        ChatSessionManagementPolicy.stableSessionKey(it) in attemptedSessionKeys
    }
    val actionableUnread = eligibleUnread.count {
        val key = ChatSessionManagementPolicy.stableSessionKey(it)
        key !in snapshotAmbiguousKeys && key !in ambiguousSessionIdentityKeys &&
            key !in attemptedSessionKeys
    }
    val diagnostics = snapshot.chatListUnreadDiagnostics
    return "行=${candidates.size}" +
        "；私人聊天=${candidates.count { it.selectionClass == "PRIVATE_CHAT" }}" +
        "；系统入口=${candidates.count { it.selectionClass == "SYSTEM_ENTRY" }}" +
        "；未分类=${candidates.count { it.selectionClass == "UNCLASSIFIED" }}" +
        "；全体行UNREAD=${unread.size}" +
        "；全体行READ=${candidates.count { it.unreadStatus == ChatUnreadStatus.READ }}" +
        "；全体行UNKNOWN=${candidates.count { it.unreadStatus == ChatUnreadStatus.UNKNOWN }}" +
        "；私人聊天未读=${unreadPrivate.size}" +
        "；私人聊天UNKNOWN=${unknownPrivate.size}" +
        "；可操作私人聊天UNKNOWN=${eligibleUnknownPrivate}" +
        "；系统入口未读=${unreadSystem}（不计入私人聊天未读）" +
        "；系统入口UNKNOWN=${unknownSystem}（保留原始徽标，不阻断私人聊天准入）" +
        "；系统入口边界READ=${systemRows.count {
            !it.rowClippedAtViewportEdge &&
                ChatSessionManagementPolicy.privateBoundaryReadStatus(it) == ChatUnreadStatus.READ &&
                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)
        }}" +
        "；几何准入未读=${eligibleUnread.size}" +
        "；身份冲突阻止=${blockedUnread}" +
        "；本轮已处理=${attemptedUnread}" +
        "；可操作未读=${actionableUnread.coerceAtLeast(0)}" +
        "；证据源ViewId=${unread.count { it.unreadEvidenceSource.name == "SEMANTIC_VIEW_ID" }}" +
        "；证据源标签=${unread.count { it.unreadEvidenceSource.name == "ACCESSIBILITY_LABEL" }}" +
        "；status_new节点=${diagnostics.statusNewNodeCount}" +
        "；status_new未读=${diagnostics.statusNewUnreadCount}" +
        "；status_new已读=${diagnostics.statusNewReadCount}" +
        "；status_new未知=${diagnostics.statusNewUnknownCount}" +
        "；status_new无效边界=${diagnostics.statusNewInvalidBoundsCount}" +
        "；status_new跨行=${diagnostics.statusNewOutsideRowCount}" +
        "；status_new空值=${diagnostics.statusNewEmptyValueCount}" +
        "；status_new非数字=${diagnostics.statusNewNonNumericValueCount}" +
        "；status_new控件缺失判已读=${diagnostics.statusNewAbsentReadCount}"
}
