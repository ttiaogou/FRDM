package com.freedom.adapters.social.momo

import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Owns bounded session-list sample serialization and its run-local sample index. */
internal class MomoChatSampleSessionListStore(
    private val isRunAcceptingRecords: () -> Boolean,
    private val runDirectory: () -> File?,
    private val blockedIdentityKeys: () -> Set<String>,
    private val appendJsonLine: (String, JSONObject) -> Unit
) {
    private var nextSessionListIndex = 1
    val sampleCount: Int get() = nextSessionListIndex - 1

    fun resetForNewRun() {
        nextSessionListIndex = 1
    }

    fun persist(snapshot: ChatPageSnapshot): Int {
        if (!isRunAcceptingRecords()) return 0
        val directory = runDirectory() ?: return 0
        val rows = JSONArray()
        val snapshotAmbiguousIdentityKeys = ChatSessionManagementPolicy.ambiguousSessionIdentityKeys(
            snapshot.chatListCandidates
        )
        // A key blocked earlier in this run stays blocked even if only one matching row is
        // currently visible. Snapshot-local collision counts remain separately observable.
        val runBlockedIdentityKeys = blockedIdentityKeys().toSet()
        val effectiveBlockedIdentityKeys = snapshotAmbiguousIdentityKeys + runBlockedIdentityKeys
        snapshot.chatListCandidates.forEach { candidate ->
            val identityKey = ChatSessionManagementPolicy.stableSessionKey(candidate)
            val identityAmbiguous = identityKey in effectiveBlockedIdentityKeys &&
                candidate.selectionClass == "PRIVATE_CHAT"
            rows.put(JSONObject()
                .put("rowIndex", candidate.rowIndex)
                .put("displayNameIdentityAmbiguous", identityAmbiguous)
                .put("name", candidate.nameText.orEmpty())
                .put("nameSource", candidate.nameSource.name)
                .put("preview", candidate.previewText.orEmpty())
                .put("latestMessagePreview", candidate.previewText.orEmpty())
                .put("previewSource", candidate.previewSource.name)
                .put("timestamp", candidate.timestampText.orEmpty())
                .put("latestMessageTime", candidate.timestampText.orEmpty())
                .put("unreadStatus", candidate.unreadStatus.name)
                .put("unreadCount", candidate.unreadCount)
                .put("unreadEvidenceSource", candidate.unreadEvidenceSource.name)
                // Absence is recorded explicitly but never converted into READ.
                .put("statusNewNodePresent", candidate.statusNewNodePresent)
                .put("statusNewNodeObservationCount", candidate.statusNewNodeObservationCount)
                // Image status-point metadata is recorded separately; only the exact, verified
                // subscription-entry contract can contribute a system-entry UNREAD state.
                .put("statusPointNodeObservationCount", candidate.statusPointNodeObservationCount)
                .put("statusPointVisibleNodeCount", candidate.statusPointVisibleNodeCount)
                .put("statusPointValidBoundsNodeCount", candidate.statusPointValidBoundsNodeCount)
                .put("statusPointContainedInRowNodeCount", candidate.statusPointContainedInRowNodeCount)
                .put("statusNewObservation", when {
                    candidate.statusNewNodeObservationCount == 0 -> "NODE_ABSENT_UNKNOWN"
                    candidate.unreadNodeEvidence.any { it.statusNewDisposition == "READ" } -> "EXPLICIT_ZERO_READ_EVIDENCE"
                    candidate.unreadNodeEvidence.any { it.statusNewDisposition == "UNREAD" } -> "EXPLICIT_UNREAD_EVIDENCE"
                    else -> "NODE_PRESENT_BUT_UNRESOLVED_UNKNOWN"
                })
                // Per-node evidence is redacted: no raw badge text or contentDescription is stored.
                .put("unreadNodeEvidence", JSONArray().apply {
                    candidate.unreadNodeEvidence.forEach { evidence ->
                        put(JSONObject()
                            .put("viewIdResourceName", evidence.viewIdResourceName)
                            .put("status", evidence.status.name)
                            .put("count", evidence.count)
                            .put("source", evidence.source.name)
                            .put("bounds", MomoChatSampleDataMapper.rectJson(evidence.bounds))
                            .put("boundsValid", evidence.boundsValid)
                            .put("intersectsRow", evidence.intersectsRow)
                            .put("containedInRow", evidence.containedInRow)
                            .put("visibleToUser", evidence.visibleToUser)
                            .put("statusNewDisposition", evidence.statusNewDisposition))
                    }
                })
                .put("selectionClass", candidate.selectionClass)
                .put("selectionReason", candidate.selectionReason)
                .put("selectableForPrivateChat", candidate.selectionClass == "PRIVATE_CHAT" && !identityAmbiguous)
                .put("selectionBlockedReason", when {
                    identityKey in runBlockedIdentityKeys && candidate.selectionClass == "PRIVATE_CHAT" ->
                        "RUN_SCOPED_AMBIGUOUS_DISPLAY_NAME_IDENTITY"
                    identityKey in snapshotAmbiguousIdentityKeys && candidate.selectionClass == "PRIVATE_CHAT" ->
                        "AMBIGUOUS_DISPLAY_NAME_IDENTITY"
                    else -> ""
                })
                .put("rowBounds", MomoChatSampleDataMapper.rectJson(candidate.rowBounds))
                // Avatar node evidence is diagnostic only. Accessibility exposes its node path
                // and geometry, not the underlying image identity; never promote it to a contact ID.
                .put("hasAvatarNode", candidate.hasAvatar)
                .put("avatarNodePath", candidate.avatarPath?.childIndices?.joinToString("/"))
                .put("avatarBoundsNonEmpty", candidate.avatarBounds?.isEmpty == false)
                .put("rowVisibleToUser", candidate.rowVisibleToUser)
                .put("intersectsViewport", candidate.intersectsViewport)
                .put("fullyInsideViewport", candidate.fullyInsideViewport)
                .put("rowBoundsValid", candidate.rowBoundsValid)
                .put("nameNodePath", candidate.nameNodePath?.childIndices?.joinToString("/"))
                .put("nameNodeVisibleToUser", candidate.nameNodeVisibleToUser)
                .put("nameBounds", candidate.nameBounds?.let(MomoChatSampleDataMapper::rectJson))
                .put("nameBoundsValid", candidate.nameBoundsValid)
                .put("nameBoundsIntersectsViewport", candidate.nameBoundsIntersectViewport)
                .put("nameBoundsFullyInsideViewport", candidate.nameBoundsFullyInsideViewport)
                .put("nameTextLength", candidate.nameText.orEmpty().length)
                .put("previewNodePath", candidate.previewNodePath?.childIndices?.joinToString("/"))
                .put("previewNodeVisibleToUser", candidate.previewNodeVisibleToUser)
                .put("previewBounds", candidate.previewBounds?.let(MomoChatSampleDataMapper::rectJson))
                .put("previewBoundsValid", candidate.previewBoundsValid)
                .put("previewBoundsIntersectsViewport", candidate.previewBoundsIntersectViewport)
                .put("previewBoundsFullyInsideViewport", candidate.previewBoundsFullyInsideViewport)
                .put("previewTextLength", candidate.previewText.orEmpty().length)
                .put("timestampNodePath", candidate.timestampNodePath?.childIndices?.joinToString("/"))
                .put("timestampNodeVisibleToUser", candidate.timestampNodeVisibleToUser)
                .put("timestampBounds", candidate.timestampBounds?.let(MomoChatSampleDataMapper::rectJson))
                .put("avatarBounds", candidate.avatarBounds?.let(MomoChatSampleDataMapper::rectJson)))
        }
        val index = nextSessionListIndex++
        val record = JSONObject()
            .put("sampleType", "session_list")
            .put("unreadEvidenceSchema", "per_node_redacted_v2_row_presence")
            .put("sampleIndex", index)
            .put("capturedAt", System.currentTimeMillis())
            .put("activity", snapshot.activityClass)
            .put("viewport", snapshot.chatListViewport?.let(MomoChatSampleDataMapper::rectJson))
            .put("viewportFingerprintAlgorithm", "sha256_session_viewport_v1")
            .put("viewportFingerprint", MomoChatSampleDataMapper.viewportFingerprint(snapshot))
            .put("viewportComplete", !snapshot.chatListExtractionTruncated)
            .put("candidateCount", snapshot.chatListCandidates.size)
            .put("privateChatCandidateCount", snapshot.chatListCandidates.count { it.selectionClass == "PRIVATE_CHAT" })
            .put("systemEntryCount", snapshot.chatListCandidates.count { it.selectionClass == "SYSTEM_ENTRY" })
            .put("unclassifiedRowCount", snapshot.chatListCandidates.count { it.selectionClass == "UNCLASSIFIED" })
            .put("unreadSummary", JSONObject()
                .put("unread", snapshot.chatListCandidates.count { it.unreadStatus.name == "UNREAD" })
                .put("read", snapshot.chatListCandidates.count { it.unreadStatus.name == "READ" })
                .put("unknown", snapshot.chatListCandidates.count { it.unreadStatus.name == "UNKNOWN" }))
            .put("unreadNodeEvidenceSummary", JSONObject().apply {
                val evidence = snapshot.chatListCandidates.flatMap { it.unreadNodeEvidence }
                put("nodeCount", evidence.size)
                put("readNodeCount", evidence.count { it.status.name == "READ" })
                put("unreadNodeCount", evidence.count { it.status.name == "UNREAD" })
                put("unknownNodeCount", evidence.count { it.status.name == "UNKNOWN" })
                put("outsideRowNodeCount", evidence.count { !it.containedInRow })
                put("rowsWithStatusNewNode", snapshot.chatListCandidates.count { it.statusNewNodePresent })
                put("rowsWithoutStatusNewNode", snapshot.chatListCandidates.count { !it.statusNewNodePresent })
                put("rowsWithExplicitZeroReadEvidence", snapshot.chatListCandidates.count { candidate ->
                    candidate.unreadNodeEvidence.any { it.statusNewDisposition == "READ" }
                })
                put("statusPointNodeCount", snapshot.chatListCandidates.sumOf { it.statusPointNodeObservationCount })
                put("rowsWithStatusPointNode", snapshot.chatListCandidates.count { it.statusPointNodeObservationCount > 0 })
                put("visibleStatusPointNodeCount", snapshot.chatListCandidates.sumOf { it.statusPointVisibleNodeCount })
                put("validBoundsStatusPointNodeCount", snapshot.chatListCandidates.sumOf { it.statusPointValidBoundsNodeCount })
                put("containedStatusPointNodeCount", snapshot.chatListCandidates.sumOf { it.statusPointContainedInRowNodeCount })
                put("statusPointConfirmedSystemUnreadCount", snapshot.chatListUnreadDiagnostics.statusPointConfirmedSystemUnreadCount)
                put("statusNewNotVisibleCount", snapshot.chatListUnreadDiagnostics.statusNewNotVisibleCount)
            })
            .put("identityCollisionPolicy", "run_scoped_normalized_display_name_collision_blocks_until_run_end")
            .put("ambiguousDisplayNameIdentityKeyCount", snapshotAmbiguousIdentityKeys.size)
            .put("ambiguousDisplayNameIdentityRowCount", snapshot.chatListCandidates.count {
                ChatSessionManagementPolicy.stableSessionKey(it) in snapshotAmbiguousIdentityKeys &&
                    ChatSessionManagementPolicy.isEligible(it)
            })
            // Preserve row-level corroborating evidence for later inspection without treating
            // avatar geometry, row position, preview, or timestamp as a stable user identity.
            .put("ambiguousDisplayNameGroups", JSONArray().apply {
                snapshotAmbiguousIdentityKeys.sorted().forEach { key ->
                    val groupRows = snapshot.chatListCandidates.filter {
                        ChatSessionManagementPolicy.isEligible(it) &&
                            ChatSessionManagementPolicy.stableSessionKey(it) == key
                    }
                    put(JSONObject()
                        .put("identityKey", key)
                        .put("identityBasis", "NORMALIZED_DISPLAY_NAME_ONLY")
                        .put("verifiedContactIdAvailable", false)
                        .put("rows", JSONArray().apply {
                            groupRows.forEach { candidate ->
                                put(JSONObject()
                                    .put("rowIndex", candidate.rowIndex)
                                    .put("avatarNodeAvailable", candidate.hasAvatar)
                                    .put("avatarNodePath", candidate.avatarPath?.childIndices?.joinToString("/"))
                                    .put("avatarBounds", candidate.avatarBounds?.let(MomoChatSampleDataMapper::rectJson))
                                    .put("nameSource", candidate.nameSource.name)
                                    .put("unreadStatus", candidate.unreadStatus.name)
                                    .put("rowBounds", MomoChatSampleDataMapper.rectJson(candidate.rowBounds)))
                            }
                        }))
                }
            })
            .put("runBlockedDisplayNameIdentityKeyCount", runBlockedIdentityKeys.size)
            .put("runBlockedDisplayNameIdentityRowCount", snapshot.chatListCandidates.count {
                ChatSessionManagementPolicy.stableSessionKey(it) in runBlockedIdentityKeys &&
                    it.selectionClass == "PRIVATE_CHAT"
            })
            .put("visibilitySummary", JSONObject()
                .put("rowVisibleToUser", snapshot.chatListCandidates.count { it.rowVisibleToUser })
                .put("nameNodeVisibleToUser", snapshot.chatListCandidates.count { it.nameNodeVisibleToUser })
                .put("previewNodeVisibleToUser", snapshot.chatListCandidates.count { it.previewNodeVisibleToUser })
                .put("nameBoundsIntersectViewport", snapshot.chatListCandidates.count { it.nameBoundsIntersectViewport })
                .put("previewBoundsIntersectViewport", snapshot.chatListCandidates.count { it.previewBoundsIntersectViewport })
                .put("nameBoundsValid", snapshot.chatListCandidates.count { it.nameBoundsValid })
                .put("previewBoundsValid", snapshot.chatListCandidates.count { it.previewBoundsValid }))
            .put("truncated", snapshot.chatListExtractionTruncated)
            .put("invalidRowBoundsCount", snapshot.chatListInvalidRowBoundsCount)
            .put("rows", rows)
        appendJsonLine("session_lists.jsonl", record)
        return if (directory.isDirectory) index else 0
    }


}
