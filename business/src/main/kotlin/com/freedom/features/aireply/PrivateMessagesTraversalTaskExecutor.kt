package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpoint

/**
 * Owns the private-message list traversal state and pure list decisions.
 *
 * Page snapshots and operation results are supplied by AiReplyObservationRuntime; this component
 * owns traversal progress, admitted unread identities, boundary evidence and candidate selection.
 * Android access, scheduling and gesture dispatch remain outside this class.
 */
internal enum class SessionListTraversalPhase {
    POSITIONING_AT_BOTTOM,
    /** Search for the stable read-only suffix below the frozen unread rows. */
    SEEKING_UNREAD_BOUNDARY,
    SCANNING_BOTTOM_TO_TOP
}

/** 高层私聊会话流程状态。执行器持有状态，运行时仅负责调度外部副作用。 */
internal enum class SessionManagerState {
    STOPPED,
    POSITIONING_SESSION_LIST_AT_TOP,
    POSITIONING_SESSION_LIST_AT_BOTTOM,
    SCANNING_SESSION_LIST_BOTTOM_TO_TOP,
    WAITING_FOR_SESSION_LIST,
    CAPTURING_SESSION_LIST,
    OPENING_SESSION,
    READING_SESSION,
    RETURNING_TO_SESSION_LIST,
    SCROLLING_SESSION_LIST,
    WAITING_FOR_UNREAD,
    COMPLETED,
    PAUSED
}

internal data class PrivateMessagesUnreadEvidence(
    val hasUnread: Boolean,
    val hasUnknown: Boolean
)

internal enum class PrivateMessagesUnreadAdmissionDecision {
    WAIT_FOR_TOP_CONFIRMATION,
    UNKNOWN_BLOCKED,
    NO_UNREAD,
    CONTINUE_BOTTOM_POSITIONING,
    WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION,
    UNREAD_BOUNDARY_CONFIRMED
}

internal enum class PrivateMessagesScrollObservationDecision {
    NO_PENDING_SCROLL,
    TOP_VIEWPORT_CHANGED,
    TOP_NO_PROGRESS_RETRY,
    TOP_BOUNDARY_CANDIDATE,
    BOTTOM_NO_PROGRESS,
    BOTTOM_BOUNDARY_CANDIDATE,
    BOTTOM_VIEWPORT_CHANGED,
    POSITION_UNVERIFIED
}

/**
 * Page identity and the original business blocker are independent evidence.
 * LOCAL_REMEDIATION permits the existing P0/P1 safe traversal, not resolution of the row.
 */
internal enum class PrivateListRecoveryFaultEvidence(val allowsResume: Boolean) {
    NOT_APPLICABLE(true),
    CLEARED(true),
    LOCAL_REMEDIATION(true),
    ORIGINAL_UNKNOWN_STILL_BLOCKED(false),
    ORIGINAL_INCOMPLETE_STILL_BLOCKED(false),
    ORIGINAL_EVIDENCE_MISSING(false),
    LIST_STRUCTURE_INCOMPLETE(false)
}

/** Reuse the traversal's exact blocker rules on a disposable checkpoint copy. */
internal fun assessPrivateListRecoveryFault(
    checkpoint: PrivateMessagesTraversalCheckpoint,
    recoveryReason: String?,
    snapshot: ChatPageSnapshot
): PrivateListRecoveryFaultEvidence {
    val unknownReason = recoveryReason == "INITIAL_UNREAD_STATE_UNKNOWN" ||
        recoveryReason == "PRIVATE_POLL_FULL_SCAN_UNKNOWN"
    val incompleteReason = recoveryReason == "PRIVATE_INCOMPLETE_UNREAD_ROW"
    val savedUnknown = checkpoint.normalInboxUnreadStateUnknown ||
        checkpoint.unresolvedUnknownUnreadKeys.isNotEmpty()
    val savedIncomplete = checkpoint.unresolvedIncompleteUnreadKeys.isNotEmpty()
    if (!unknownReason && !incompleteReason && !savedUnknown && !savedIncomplete) {
        return PrivateListRecoveryFaultEvidence.NOT_APPLICABLE
    }
    // A missing original checkpoint cannot be replaced by proof that some other row is clean.
    if ((unknownReason && !savedUnknown) || (incompleteReason && !savedIncomplete)) {
        return PrivateListRecoveryFaultEvidence.ORIGINAL_EVIDENCE_MISSING
    }
    if (snapshot.pageKind != ChatPageKind.CHAT_LIST || snapshot.chatListExtractionTruncated ||
        snapshot.chatListViewport == null || snapshot.windowIdentityKey() == null ||
        snapshot.chatListCandidates.isEmpty()
    ) return PrivateListRecoveryFaultEvidence.LIST_STRUCTURE_INCOMPLETE

    val probe = PrivateMessagesTraversalTaskExecutor().apply { restore(checkpoint) }
    // Preserve the original checkpoint through every unsuccessful observation; neither
    // a first sample nor a different viewport can retire pending identities.
    if (probe.observeUnreadBlockers(snapshot) || probe.unresolvedUnknownUnreadKeys.isNotEmpty()) {
        // Resume independent work, not the unresolved row or completion. Legacy and
        // anonymous evidence still require their original recovery proof.
        return if (probe.lockedUnreadSetFinalized && probe.canContinueWithUnknownUnread(snapshot))
            PrivateListRecoveryFaultEvidence.LOCAL_REMEDIATION
        else PrivateListRecoveryFaultEvidence.ORIGINAL_UNKNOWN_STILL_BLOCKED
    }
    if (probe.observeIncompleteUnreadEvidence(snapshot)) {
        return if (probe.canContinueWithIncompleteUnread(snapshot)) {
            PrivateListRecoveryFaultEvidence.LOCAL_REMEDIATION
        } else PrivateListRecoveryFaultEvidence.ORIGINAL_INCOMPLETE_STILL_BLOCKED
    }
    return PrivateListRecoveryFaultEvidence.CLEARED
}

private const val UNREAD_BOUNDARY_STABILITY_CONFIRMATIONS = 2
private const val COLLECTION_POSITION_PREFIX = "collection-position:"

/** A complete, read-only decision packet for one fresh inbox snapshot. */
internal data class PrivateMessagesListSnapshotEvaluation(
    val snapshotAmbiguousKeys: Set<String>,
    val newlyAmbiguousKeys: Set<String>,
    /** Durable, retryable evidence from fully visible but structurally incomplete unread rows. */
    val incompleteUnreadEvidenceBlocked: Boolean,
    /** An isolated named row must not revoke independently complete list evidence. */
    val incompleteUnreadAllowsIndependentTraversal: Boolean,
    val unreadAuditChanged: Boolean,
    val wasWaitingForUnread: Boolean,
    val visibleKeys: List<String>,
    val scrollDecision: PrivateMessagesScrollObservationDecision,
    val viewportPositionInvalidated: Boolean,
    val candidatesEmpty: Boolean,
    val unreadAdmissionDecision: PrivateMessagesUnreadAdmissionDecision?,
    val explicitUnreadWhileWaiting: Boolean,
    val nextCandidate: ChatListCandidateSnapshot?,
    val structuralFingerprint: String
)

internal class PrivateMessagesTraversalTaskExecutor {
    var checkpointRunGeneration: Long = 0L
    var checkpointLastSuccessfulTaskId: String? = null
    var checkpointNextCursorKey: String? = null
    var checkpointTraversalDirection: String = "BOTTOM_TO_TOP"
    fun checkpoint(): PrivateMessagesTraversalCheckpoint = PrivateMessagesTraversalCheckpoint(
        // A submitted but not yet verified navigation is not processed work.
        // A restart may reselect it only from a fresh explicit UNREAD observation.
        attemptedSessionKeys = attemptedSessionKeys.filterNot {
            executionState == SessionManagerState.OPENING_SESSION && it == currentSessionKey
        }.toSet(),
        lockedUnreadSessionKeys = lockedUnreadSessionKeys.toSet(),
        ambiguousSessionIdentityKeys = ambiguousSessionIdentityKeys.toSet(),
        lastStableVisibleKeys = lastStableSessionListVisibleKeys.orEmpty().toList(),
        phase = phase.name,
        atTop = atTop,
        initialInboxPositionCheckCompleted = initialInboxPositionCheckCompleted,
        initialInboxTopPositioningCompleted = initialInboxTopPositioningCompleted,
        initialUnreadGateSwipeCompleted = initialUnreadGateSwipeCompleted,
        lockedUnreadSetFinalized = lockedUnreadSetFinalized,
        normalInboxHadUnread = normalInboxHadUnread,
        normalInboxUnreadStateUnknown = normalInboxUnreadStateUnknown,
        resumeSessionKey = resumeSessionKey,
        resumeSessionName = resumeSessionName,
        runGeneration = checkpointRunGeneration,
        lastSuccessfulTaskId = checkpointLastSuccessfulTaskId,
        nextCursorKey = checkpointNextCursorKey,
        traversalDirection = checkpointTraversalDirection,
        unresolvedUnknownUnreadKeys = unresolvedUnknownUnreadKeys.toSet(),
        unknownUnreadViewportKeys = unknownUnreadViewportKeys.toList(),
        fullListPollingRoundRequired = fullListPollingRoundRequired,
        unresolvedIncompleteUnreadKeys = unresolvedIncompleteUnreadKeys.toSet()
    )

    fun restore(checkpoint: PrivateMessagesTraversalCheckpoint) {
        // A checkpoint replaces the in-memory traversal cursor. It must not inherit a
        // previous run's pending session, scroll baseline, or boundary evidence.
        clearIdentityReturn()
        clearCurrentSession()
        headerMismatchRecordedForCurrentAttempt = false
        sessionListStabilityRetryCount = 0
        sessionActionCount = 0
        noProgressScrollCount = 0
        pendingScrollBaseline = null
        pendingScrollToTop = false
        cancelledScrollRecoveryCount = 0
        unreadBoundarySignature = ""
        unreadBoundaryStabilityCount = 0
        capturedSessionListFingerprints.clear()
        pendingBoundaryConfirmationKeys = null
        pendingBoundaryConfirmationToTop = false
        boundaryStabilityConfirmationCount = 0
        lastUnreadAuditFingerprint = ""
        attemptedSessionKeys.clear()
        lockedUnreadSessionKeys.clear()
        ambiguousSessionIdentityKeys.clear()
        attemptedSessionKeys += checkpoint.attemptedSessionKeys
        lockedUnreadSessionKeys += checkpoint.lockedUnreadSessionKeys
        ambiguousSessionIdentityKeys += checkpoint.ambiguousSessionIdentityKeys
        lastStableSessionListVisibleKeys = checkpoint.lastStableVisibleKeys.toList()
        phase = runCatching { SessionListTraversalPhase.valueOf(checkpoint.phase) }
            .getOrDefault(SessionListTraversalPhase.POSITIONING_AT_BOTTOM)
        atTop = checkpoint.atTop
        initialInboxPositionCheckCompleted = checkpoint.initialInboxPositionCheckCompleted
        initialInboxTopPositioningCompleted = checkpoint.initialInboxTopPositioningCompleted
        initialUnreadGateSwipeCompleted = checkpoint.initialUnreadGateSwipeCompleted
        lockedUnreadSetFinalized = checkpoint.lockedUnreadSetFinalized
        normalInboxHadUnread = checkpoint.normalInboxHadUnread
        normalInboxUnreadStateUnknown = checkpoint.normalInboxUnreadStateUnknown
        unresolvedUnknownUnreadKeys.clear()
        unresolvedUnknownUnreadKeys += checkpoint.unresolvedUnknownUnreadKeys
        unknownUnreadViewportKeys.clear()
        unknownUnreadViewportKeys += checkpoint.unknownUnreadViewportKeys
        // v1 checkpoints have only a boolean blocker. Keep the pre-existing visible-row
        // anchor so fresh evidence from another viewport cannot silently clear that state.
        legacyUnknownUnreadViewportKeys =
            if (normalInboxUnreadStateUnknown && unresolvedUnknownUnreadKeys.isEmpty()) {
                checkpoint.lastStableVisibleKeys.takeIf { it.isNotEmpty() }?.toList()
            } else null
        resumeSessionKey = checkpoint.resumeSessionKey
        resumeSessionName = checkpoint.resumeSessionName
        checkpointRunGeneration = checkpoint.runGeneration
        checkpointLastSuccessfulTaskId = checkpoint.lastSuccessfulTaskId
        checkpointNextCursorKey = checkpoint.nextCursorKey
        checkpointTraversalDirection = checkpoint.traversalDirection
        // Old ordinary polling checkpoints must not resurrect forced bottom positioning.
        // A same-round anomaly relocation always starts with finalized locked membership.
        fullListPollingRoundRequired = checkpoint.fullListPollingRoundRequired && checkpoint.lockedUnreadSetFinalized
        unresolvedIncompleteUnreadKeys.clear()
        unresolvedIncompleteUnreadKeys += checkpoint.unresolvedIncompleteUnreadKeys
    }

    internal enum class RestoreReanchorStatus { REANCHORED, NOT_FOUND, AMBIGUOUS }
    internal data class RestoreReanchorResult(
        val status: RestoreReanchorStatus,
        val matchedCandidate: ChatListCandidateSnapshot? = null
    )

    /**
     * Re-anchors a restored traversal to the newest list snapshot. Coordinates and row indexes
     * from the checkpoint are never reused. The last session identity is only a search hint;
     * clicking still goes through the normal fresh-row validation path.
     */
    fun reanchorRestoredCheckpoint(snapshot: ChatPageSnapshot): RestoreReanchorResult {
        recordVerifiedViewport(snapshot)

        val candidates = snapshot.chatListCandidates.filter { candidate ->
            candidate.selectionClass == "PRIVATE_CHAT" && ChatSessionManagementPolicy.isEligible(candidate)
        }
        val keyMatches = resumeSessionKey?.let { key ->
            candidates.filter { ChatSessionManagementPolicy.stableSessionKey(it) == key }
        }.orEmpty()
        val titleKey = ChatSessionManagementPolicy.stableSessionKeyForTitle(resumeSessionName)
        val titleMatches = if (titleKey.isBlank()) emptyList() else candidates.filter {
            ChatSessionManagementPolicy.stableSessionKeyForTitle(it.nameText) == titleKey
        }
        val matches = when {
            keyMatches.size == 1 -> keyMatches
            titleMatches.size == 1 -> titleMatches
            keyMatches.size > 1 || titleMatches.size > 1 -> return RestoreReanchorResult(RestoreReanchorStatus.AMBIGUOUS)
            else -> return RestoreReanchorResult(RestoreReanchorStatus.NOT_FOUND)
        }
        val matched = matches.single()
        val newKey = ChatSessionManagementPolicy.stableSessionKey(matched)
        val oldKey = resumeSessionKey
        if (oldKey != null && oldKey != newKey) {
            if (attemptedSessionKeys.remove(oldKey)) attemptedSessionKeys += newKey
            if (lockedUnreadSessionKeys.remove(oldKey)) lockedUnreadSessionKeys += newKey
            if (ambiguousSessionIdentityKeys.remove(oldKey)) ambiguousSessionIdentityKeys += newKey
        }
        resumeSessionKey = newKey.takeIf(String::isNotBlank)
        resumeSessionName = matched.nameText
        return RestoreReanchorResult(RestoreReanchorStatus.REANCHORED, matched)
    }

    /** Keeps the most recent business identity as a recovery search hint. */
    fun rememberRecoveryAnchor(candidate: ChatListCandidateSnapshot) =
        rememberRecoveryAnchor(ChatSessionManagementPolicy.stableSessionKey(candidate), candidate.nameText)

    fun rememberRecoveryAnchor(identityKey: String, displayName: String?) {
        resumeSessionKey = identityKey.takeIf(String::isNotBlank)
        resumeSessionName = displayName?.takeIf(String::isNotBlank)
    }

    /**
     * Legacy compatibility predicate retained for callers/tests; recovery no longer treats a
     * viewport mismatch as a fatal condition.
     */
    fun isRestoredViewportCompatible(snapshot: ChatPageSnapshot): Boolean {
        val expected = lastStableSessionListVisibleKeys.orEmpty()
        if (expected.isEmpty()) return true
        val actual = visibleSessionKeys(snapshot)
        if (actual.isEmpty()) return false
        // Compatibility is deliberately stricter than re-anchoring: a row identity that is
        // still visible at a different geometry must enter the re-anchor path instead of
        // allowing the caller to reuse the old viewport/checkpoint coordinates.
        return actual == expected
    }
    private data class IdentityReturn(
        val original: ChatListCandidateSnapshot,
        val oldKey: String,
        val baseline: List<String>,
        val generation: Long
    )
    internal enum class IdentityReturnStatus { WAITING, RESTORED, REJECTED }
    internal data class IdentityReturnResult(val status: IdentityReturnStatus, val candidate: ChatListCandidateSnapshot? = null)
    private var identityReturn: IdentityReturn? = null
    var identityReturnRetries: Int = 0
    val identityReturnPending: Boolean get() = identityReturn != null
    val identityReturnOldKey: String? get() = identityReturn?.oldKey

    /** 仅保留普通快照数据；不跨页面保留 Android 节点。 */
    fun beginIdentityReturn(candidate: ChatListCandidateSnapshot, generation: Long): Boolean {
        val baseline = lastStableSessionListVisibleKeys ?: return false
        val key = ChatSessionManagementPolicy.stableSessionKey(candidate)
        if (identityReturn != null || key.isBlank() || baseline.none { it.startsWith("$key@") }) return false
        identityReturn = IdentityReturn(candidate, key, baseline.toList(), generation)
        identityReturnRetries = 0
        return true
    }

    fun reconcileIdentityReturn(snapshot: ChatPageSnapshot, generation: Long): IdentityReturnResult {
        val pending = identityReturn ?: return IdentityReturnResult(IdentityReturnStatus.REJECTED)
        val original = pending.original
        if (pending.generation != generation || snapshot.pageKind != ChatPageKind.CHAT_LIST ||
            snapshot.chatListExtractionTruncated || original.rowTarget.window.windowId < 0 ||
            snapshot.packageName != original.rowTarget.window.packageName ||
            snapshot.activityClass != original.rowTarget.window.activityClass ||
            snapshot.windowGeneration <= 0L
        ) return IdentityReturnResult(IdentityReturnStatus.REJECTED)
        val titleKey = ChatSessionManagementPolicy.stableSessionKeyForTitle(original.nameText)
        val matches = snapshot.chatListCandidates.filter {
            ChatSessionManagementPolicy.stableSessionKeyForTitle(it.nameText) == titleKey &&
                it.selectionClass == "PRIVATE_CHAT"
        }
        if (titleKey.isBlank() || matches.size != 1) return IdentityReturnResult(IdentityReturnStatus.REJECTED)
        val row = matches.single()
        if (row.contactIdentity?.confidence != ContactIdentityConfidence.PLATFORM_ID_VERIFIED ||
            row.contactIdentity?.contactId.isNullOrBlank()
        ) return IdentityReturnResult(IdentityReturnStatus.WAITING)
        val newKey = ChatSessionManagementPolicy.stableSessionKey(row)
        if (row.rowIndex != original.rowIndex || row.rowTarget.path != original.rowTarget.path ||
            row.rowBounds != original.rowBounds || row.avatarBounds != original.avatarBounds ||
            row.avatarPath != original.avatarPath || row.nameNodePath != original.nameNodePath ||
            row.rowTarget.window.windowId != original.rowTarget.window.windowId ||
            row.rowTarget.window.packageName != snapshot.packageName ||
            row.rowTarget.window.activityClass != snapshot.activityClass ||
            row.rowTarget.window.generation != snapshot.windowGeneration ||
            row.unreadStatus != ChatUnreadStatus.UNREAD || !ChatSessionManagementPolicy.isEligible(row) ||
            newKey.isBlank() || newKey == pending.oldKey ||
            snapshot.chatListCandidates.count { ChatSessionManagementPolicy.isEligible(it) && ChatSessionManagementPolicy.stableSessionKey(it) == newKey } != 1 ||
            pending.oldKey !in lockedUnreadSessionKeys || newKey in lockedUnreadSessionKeys ||
            pending.oldKey in attemptedSessionKeys || newKey in attemptedSessionKeys ||
            pending.oldKey in ambiguousSessionIdentityKeys || newKey in ambiguousSessionIdentityKeys
        ) return IdentityReturnResult(IdentityReturnStatus.REJECTED)
        val expected = pending.baseline.map { entry ->
            if (entry.startsWith("${pending.oldKey}@")) newKey + entry.substring(pending.oldKey.length) else entry
        }
        if (expected != visibleSessionKeys(snapshot)) return IdentityReturnResult(IdentityReturnStatus.REJECTED)
        lockedUnreadSessionKeys.remove(pending.oldKey)
        lockedUnreadSessionKeys.add(newKey)
        lastStableSessionListVisibleKeys = expected
        identityReturn = null
        return IdentityReturnResult(IdentityReturnStatus.RESTORED, row)
    }

    fun clearIdentityReturn() { identityReturn = null; identityReturnRetries = 0 }
    /** 私聊流程和当前会话上下文由此执行器独占持有。 */
    var executionState: SessionManagerState = SessionManagerState.STOPPED
    var currentSessionKey: String? = null
    var currentSessionName: String? = null
    var currentSessionCandidate: ChatListCandidateSnapshot? = null
    var currentSessionReadCaptured: Boolean = false
    var currentSessionWindowKey: String? = null
    val headerMismatchGuard = Any()
    var headerMismatchRecordedForCurrentAttempt: Boolean = false

    var phase: SessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
    var atTop: Boolean = false

    var initialInboxPositionCheckCompleted: Boolean = false
    /** True only after the startup list-top boundary has been independently confirmed. */
    var initialInboxTopPositioningCompleted: Boolean = false
    // Legacy checkpoint field name: true after unread admission observes the confirmed top.
    // No gesture completion is required or implied by this flag.
    var initialUnreadGateSwipeCompleted: Boolean = false

    val attemptedSessionKeys: MutableSet<String> = LinkedHashSet()
    val ambiguousSessionIdentityKeys: MutableSet<String> = LinkedHashSet()
    // Retain the existing checkpoint names: finalization describes the boundary,
    // not immutable membership. Newly verified unread rows join this same ledger.
    val lockedUnreadSessionKeys: MutableSet<String> = LinkedHashSet()
    var lockedUnreadSetFinalized: Boolean = false
    /** Legacy checkpoint field: an exceptional same-round search for pending identities.
     * Stop as soon as an actionable target is rediscovered; physical end is a fallback. */
    var fullListPollingRoundRequired: Boolean = false
        private set
    private var unreadBoundarySignature: String = ""
    private var unreadBoundaryStabilityCount: Int = 0

    var normalInboxHadUnread: Boolean = false
    var normalInboxUnreadStateUnknown: Boolean = false
    /** Keys are already SHA-256 opaque traversal hints, not user IDs or message text. */
    val unresolvedUnknownUnreadKeys: MutableSet<String> = LinkedHashSet()
    val unknownUnreadViewportKeys: MutableList<String> = ArrayList()
    /**
     * Retryable list-row evidence. Opaque title keys (or geometry-only hints for unnamed
     * rows) survive checkpoints, but never enter the permanent identity-conflict cache.
     */
    val unresolvedIncompleteUnreadKeys: MutableSet<String> = LinkedHashSet()
    private var legacyUnknownUnreadViewportKeys: List<String>? = null
    var resumeSessionKey: String? = null
    var resumeSessionName: String? = null
    var sessionListStabilityRetryCount: Int = 0
    var sessionActionCount: Int = 0

    var noProgressScrollCount: Int = 0
    var pendingScrollBaseline: List<String>? = null
    var pendingScrollToTop: Boolean = false
    // Transient position evidence from the same observation as the existing stable keys.
    // Never serialize collection positions or reuse them after checkpoint restore.
    private var lastObservedViewportPosition: Pair<String, IntRange?>? = null
    var lastStableSessionListVisibleKeys: List<String>? = null
        set(value) {
            field = value
            lastObservedViewportPosition = null
        }
    var cancelledScrollRecoveryCount: Int = 0

    val capturedSessionListFingerprints: MutableSet<String> = LinkedHashSet()
    var pendingBoundaryConfirmationKeys: List<String>? = null
    var pendingBoundaryConfirmationToTop: Boolean = false
    var boundaryStabilityConfirmationCount: Int = 0
    var lastUnreadAuditFingerprint: String = ""

    /** Derives visible, complete row identities in the established bottom-to-top order. */
    fun visibleSessionKeys(snapshot: ChatPageSnapshot): List<String> =
        ChatSessionManagementPolicy.orderBottomToTop(
            snapshot.chatListCandidates.filter {
                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)
            }
        ).mapNotNull { candidate ->
            val identityKey = ChatSessionManagementPolicy.stableSessionKey(candidate)
            if (identityKey.isBlank()) return@mapNotNull null
            // Keep exact geometry for boundary, identity-return and checkpoint proof.
            // Position continuity is evaluated separately; row indexes are not identities.
            "$identityKey@${candidate.rowBounds.left},${candidate.rowBounds.top}," +
                "${candidate.rowBounds.right},${candidate.rowBounds.bottom}"
        }

    // A collection range is only position evidence. Keep the old identity/geometry keys
    // unchanged for checkpoint restoration, identity return and incomplete-row safety gates.
    private fun collectionRows(snapshot: ChatPageSnapshot): List<Int>? {
        if (snapshot.pageKind != ChatPageKind.CHAT_LIST || snapshot.chatListExtractionTruncated ||
            snapshot.chatListViewport?.isEmpty != false || snapshot.windowGeneration <= 0L ||
            snapshot.windowIdentityKey() == null
        ) return null
        val count = snapshot.chatListCollectionRowCount ?: return null
        val rows = snapshot.chatListVisibleCollectionRows
        return rows.takeIf {
            count > 0 && it.isNotEmpty() && it.all { row -> row in 0 until count } &&
                it.zipWithNext().all { (a, b) -> b == a + 1 }
        }
    }

    private fun collectionPositionKey(snapshot: ChatPageSnapshot): String? =
        collectionRows(snapshot)?.let { rows ->
            "$COLLECTION_POSITION_PREFIX${snapshot.windowIdentityKey()}:${rows.first()}:${rows.last()}"
        }

    /** Record only an independently observed list, never a stale coordinate or row target. */
    fun recordVerifiedViewport(snapshot: ChatPageSnapshot) {
        val keys = visibleSessionKeys(snapshot)
        if (keys.isEmpty() || snapshot.chatListExtractionTruncated) return
        lastStableSessionListVisibleKeys = keys
        val window = snapshot.windowIdentityKey() ?: return
        lastObservedViewportPosition = window to collectionRows(snapshot)?.let { it.first()..it.last() }
    }

    private fun collectionPositionFromKey(key: String?): Pair<String, IntRange>? {
        if (key == null || !key.startsWith(COLLECTION_POSITION_PREFIX)) return null
        val last = key.substringAfterLast(':').toIntOrNull() ?: return null
        val prefix = key.substringBeforeLast(':')
        val first = prefix.substringAfterLast(':').toIntOrNull() ?: return null
        if (first < 0 || last < first) return null
        return prefix.substringBeforeLast(':') to (first..last)
    }

    private fun orderedIdentityKeys(keys: List<String>): List<String> =
        keys.filterNot { it.startsWith(COLLECTION_POSITION_PREFIX) }
            .map { it.substringBefore('@') }.filter(String::isNotBlank)

    /** Null means no independent boundary evidence; false prevents a false no-motion boundary. */
    fun collectionBoundaryEvidence(snapshot: ChatPageSnapshot, toTop: Boolean): Boolean? {
        val rows = collectionRows(snapshot) ?: return null
        val count = snapshot.chatListCollectionRowCount ?: return null
        val atEdge = if (toTop) rows.first() == 0 else rows.last() == count - 1
        val canScroll = if (toTop) snapshot.chatListCanScrollBackward else snapshot.chatListCanScrollForward
        if (!atEdge || canScroll == true) return false
        if (canScroll == null) return null
        // A missing pair of scroll actions on a multi-viewport list is not proof of an edge.
        val opposite = if (toTop) snapshot.chatListCanScrollForward else snapshot.chatListCanScrollBackward
        if (rows.size < count && opposite != true) return null
        return true
    }

    /** Transient scroll baseline: compare row range first; retain old evidence as fallback. */
    fun scrollPositionKeys(snapshot: ChatPageSnapshot): List<String> {
        val legacy = visibleSessionKeys(snapshot)
        if (legacy.isEmpty()) return legacy
        return collectionPositionKey(snapshot)?.let { listOf(it) + legacy } ?: legacy
    }

    /** Keep the independent, complete-row boundary stability check; never downgrade a
     * collection-proven boundary to an identity-only observation when evidence disappears. */
    fun boundaryConfirmationKeys(snapshot: ChatPageSnapshot): List<String> {
        val legacy = visibleSessionKeys(snapshot)
        if (legacy.isEmpty()) return legacy
        val position = collectionPositionKey(snapshot) ?: return legacy
        return listOf("$position:${snapshot.chatListCollectionRowCount}:" +
            "${snapshot.chatListCanScrollForward}:${snapshot.chatListCanScrollBackward}") + legacy
    }

    fun ambiguousIdentityKeys(candidates: List<ChatListCandidateSnapshot>): Set<String> {
        val fullyVisible = candidates.filter {
            it.selectionClass == "PRIVATE_CHAT" && it.rowVisibleToUser &&
                it.intersectsViewport && it.fullyInsideViewport && it.hasReadableName
        }
        // An incomplete second row is still evidence of a title collision: it must not
        // disappear from collision detection simply because its preview/bounds are invalid.
        // Keep the existing fully-verified/eligible policy otherwise unchanged.
        val incompleteTitleCollisions = fullyVisible
            .groupBy { ChatSessionManagementPolicy.stableSessionKeyForTitle(it.nameText) }
            .filter { (key, rows) ->
                key.isNotBlank() && rows.map { it.rowIndex }.distinct().size > 1 &&
                    rows.any { !ChatSessionManagementPolicy.isEligible(it) }
            }
            .values.flatMap { rows ->
                rows.map(ChatSessionManagementPolicy::stableSessionKey).filter(String::isNotBlank)
            }.toSet()
        return ChatSessionManagementPolicy.ambiguousSessionIdentityKeys(candidates) +
            incompleteTitleCollisions
    }

    /**
     * Identify incomplete *fully-visible* private rows with unread/unknown evidence.
     * Clipped edge rows are excluded: scrolling, not a failure cache, handles them.
     *
     * Resolve a saved anomaly only when the same opaque identity (or exact anonymous
     * geometry hint) is uniquely present and now eligible with explicit READ/UNREAD.
     * Missing rows in other viewports must NOT release a past anomaly.
     */
    fun observeIncompleteUnreadEvidence(snapshot: ChatPageSnapshot): Boolean {
        val rows = snapshot.chatListCandidates.filter {
            it.selectionClass == "PRIVATE_CHAT" && it.rowVisibleToUser &&
                it.intersectsViewport && it.fullyInsideViewport &&
                !isNonContributingClippedEdge(snapshot, it)
        }
        val incomplete = rows.filter {
            it.unreadStatus != ChatUnreadStatus.READ &&
                (!ChatSessionManagementPolicy.isEligible(it) || it.rowClippedAtViewportEdge)
        }
        // A named clipped row with positive/uncertain badge evidence is not READ,
        // even when it is not geometrically eligible. Never use its geometry as identity.
        val namedClipped = snapshot.chatListCandidates.filter {
            it.selectionClass == "PRIVATE_CHAT" && it.rowVisibleToUser && it.intersectsViewport &&
                (!it.fullyInsideViewport || it.rowClippedAtViewportEdge) && it.hasReadableName &&
                it.unreadStatus != ChatUnreadStatus.READ && !isNonContributingClippedEdge(snapshot, it)
        }
        val activeIncompleteHints = (incomplete + namedClipped).map(::incompleteUnreadHint).toSet()
        unresolvedIncompleteUnreadKeys += activeIncompleteHints
        if (!snapshot.chatListExtractionTruncated && unresolvedIncompleteUnreadKeys.isNotEmpty()) {
            unresolvedIncompleteUnreadKeys.removeAll { saved ->
                if (saved in activeIncompleteHints) return@removeAll false
                val matches = rows.filter { row ->
                    if (saved.startsWith("unnamed:")) anonymousUnreadHint(row) == saved
                    else incompleteUnreadHint(row) == saved
                }
                val allExplicit = matches.isNotEmpty() && matches.all {
                    ChatSessionManagementPolicy.isEligible(it) && !it.rowClippedAtViewportEdge &&
                        ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport) &&
                        it.unreadStatus != ChatUnreadStatus.UNKNOWN
                }
                val identityConflictStillBlocked = matches.size > 1 && matches.all {
                    ChatSessionManagementPolicy.stableSessionKey(it) in ambiguousSessionIdentityKeys
                }
                val resolved = allExplicit && (matches.size == 1 || identityConflictStillBlocked)
                // A named anomaly was never admitted as unread while its row was incomplete.
                // If it repairs after the ordinary unread set froze, retain that original
                // pending work in the same round; never admit anonymous geometry as identity.
                if (resolved && saved.startsWith("identity:") && matches.size == 1 &&
                    initialUnreadGateSwipeCompleted && lockedUnreadSetFinalized
                ) {
                    val repaired = matches.single()
                    val repairedKey = ChatSessionManagementPolicy.stableSessionKey(repaired)
                    if (repaired.unreadStatus == ChatUnreadStatus.UNREAD &&
                        repairedKey.isNotBlank() && repairedKey !in ambiguousSessionIdentityKeys
                    ) {
                        lockedUnreadSessionKeys += repairedKey
                        normalInboxHadUnread = true
                    }
                }
                resolved
            }
        }
        return unresolvedIncompleteUnreadKeys.isNotEmpty()
    }

    private fun incompleteUnreadHint(row: ChatListCandidateSnapshot): String {
        val key = ChatSessionManagementPolicy.stableSessionKey(row)
        return if (key.isNotBlank()) "identity:$key" else anonymousUnreadHint(row)
    }

    private fun anonymousUnreadHint(row: ChatListCandidateSnapshot): String {
        // An unnamed row may acquire a readable title later. Match its original
        // geometry only to lift the read-only block, never to select or click it.
        val bounds = row.rowBounds
        return "unnamed:${row.rowIndex}@${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
    }

    /**
     * A named pending row is not a page failure when the same fresh list contains
     * independently complete private-row evidence. An anonymous geometry hint cannot
     * safely survive a viewport move as an identity; retain the existing fail-closed path.
     * This decision grants no UI lease and never clears the durable pending set.
     */
    fun canContinueWithIncompleteUnread(snapshot: ChatPageSnapshot): Boolean =
        unresolvedIncompleteUnreadKeys.isNotEmpty() &&
            unresolvedIncompleteUnreadKeys.all { it.startsWith("identity:") } &&
            hasIndependentExplicitRow(snapshot)

    fun canContinueWithUnknownUnread(snapshot: ChatPageSnapshot): Boolean =
        unresolvedUnknownUnreadKeys.isNotEmpty() && legacyUnknownUnreadViewportKeys == null &&
            unresolvedIncompleteUnreadKeys.none { !it.startsWith("identity:") } &&
            hasIndependentExplicitRow(snapshot)

    // Apply the existing weak-title uniqueness requirement to read-only retirement
    // as well: a different same-title row cannot settle an outstanding identity.
    private fun isUniqueCompletePrivateRow(
        snapshot: ChatPageSnapshot,
        row: ChatListCandidateSnapshot,
        conflicts: Set<String>
    ): Boolean {
        val key = ChatSessionManagementPolicy.stableSessionKey(row)
        val titleKey = ChatSessionManagementPolicy.stableSessionKeyForTitle(row.nameText)
        val window = row.rowTarget.window
        val rowWindowIdentity = "${window.packageName}|windowId=${window.windowId}|generation=${window.generation}"
        return key.isNotBlank() && key !in conflicts && window.generation > 0L &&
            rowWindowIdentity == snapshot.windowIdentityKey() &&
            ChatSessionManagementPolicy.isEligible(row) && !row.rowClippedAtViewportEdge &&
            ChatSessionManagementPolicy.isCompleteBoundaryEvidence(row, snapshot.chatListViewport) &&
            snapshot.chatListCandidates.count {
                ChatSessionManagementPolicy.stableSessionKey(it) == key
            } == 1 &&
            (key != titleKey || snapshot.chatListCandidates.count {
                it.selectionClass != "SYSTEM_ENTRY" &&
                    ChatSessionManagementPolicy.stableSessionKeyForTitle(it.nameText) == titleKey
            } == 1)
    }

    private fun hasIndependentExplicitRow(snapshot: ChatPageSnapshot): Boolean {
        if (snapshot.pageKind != ChatPageKind.CHAT_LIST || snapshot.chatListExtractionTruncated ||
            snapshot.windowIdentityKey() == null || snapshot.chatListViewport == null
        ) return false
        val conflicts = ambiguousIdentityKeys(snapshot.chatListCandidates) + ambiguousSessionIdentityKeys
        return snapshot.chatListCandidates.any { row ->
            val key = ChatSessionManagementPolicy.stableSessionKey(row)
            isUniqueCompletePrivateRow(snapshot, row, conflicts) &&
                row.unreadStatus != ChatUnreadStatus.UNKNOWN &&
                key !in unresolvedUnknownUnreadKeys && !isPendingIncompleteRow(row)
        }
    }

    fun pendingKnownUnreadKeys(): Set<String> =
        lockedUnreadSessionKeys - attemptedSessionKeys - ambiguousSessionIdentityKeys

    // A clipped anonymous/unclassified badge is neither a READ proof nor a durable
    // identity. Reobserve it at completion without admitting a geometry-only target.
    fun hasUnresolvedVisibleEdgeEvidence(snapshot: ChatPageSnapshot): Boolean =
        snapshot.chatListCandidates.any { row ->
            row.selectionClass != "SYSTEM_ENTRY" && row.rowVisibleToUser && row.intersectsViewport &&
                (row.selectionClass == "UNCLASSIFIED" || row.rowClippedAtViewportEdge || !row.fullyInsideViewport) &&
                row.unreadStatus != ChatUnreadStatus.READ &&
                (row.unreadStatus == ChatUnreadStatus.UNREAD || row.statusNewNodePresent ||
                    row.unreadNodeEvidence.isNotEmpty())
        }

    private fun hasPendingRoundWork(): Boolean =
        pendingKnownUnreadKeys().isNotEmpty() || unresolvedIncompleteUnreadKeys.isNotEmpty() ||
            unresolvedUnknownUnreadKeys.isNotEmpty() || normalInboxUnreadStateUnknown

    /** Reconcile only unique, complete, current row evidence. READ removes pending
     * work, never records an attempt. Missing/UNKNOWN/clipped rows remain pending. */
    private fun reconcileAdmittedUnread(snapshot: ChatPageSnapshot) {
        if (snapshot.pageKind != ChatPageKind.CHAT_LIST || snapshot.chatListExtractionTruncated ||
            snapshot.windowIdentityKey() == null
        ) return
        val conflicts = ambiguousIdentityKeys(snapshot.chatListCandidates) + ambiguousSessionIdentityKeys
        val rowsByKey = snapshot.chatListCandidates.groupBy(ChatSessionManagementPolicy::stableSessionKey)
        for ((key, rows) in rowsByKey) {
            if (key.isBlank() || key in conflicts || key in attemptedSessionKeys || rows.size != 1) continue
            val row = rows.single()
            if (!isUniqueCompletePrivateRow(snapshot, row, conflicts) ||
                key in unresolvedUnknownUnreadKeys || isPendingIncompleteRow(row)
            ) continue
            when (row.unreadStatus) {
                ChatUnreadStatus.UNREAD -> {
                    lockedUnreadSessionKeys += key
                    normalInboxHadUnread = true
                }
                ChatUnreadStatus.READ -> lockedUnreadSessionKeys.remove(key)
                ChatUnreadStatus.UNKNOWN -> Unit
            }
        }
    }

    /**
     * A completed physical scan with unresolved named evidence may revisit the list
     * in the SAME round. Never treat a saved hint as an actionable row or a coordinate.
     */
    fun canBeginIncompleteUnreadRelocation(snapshot: ChatPageSnapshot): Boolean =
        initialInboxPositionCheckCompleted && initialInboxTopPositioningCompleted &&
            initialUnreadGateSwipeCompleted && lockedUnreadSetFinalized &&
            phase == SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP && atTop &&
            pendingScrollBaseline == null && pendingBoundaryConfirmationKeys == null &&
            currentSessionKey == null && !identityReturnPending &&
            hasPendingRoundWork() && legacyUnknownUnreadViewportKeys == null &&
            unresolvedIncompleteUnreadKeys.none { !it.startsWith("identity:") } &&
            snapshot.windowIdentityKey() != null && hasIndependentExplicitRow(snapshot)

    fun beginIncompleteUnreadRelocation(snapshot: ChatPageSnapshot): Boolean {
        if (!canBeginIncompleteUnreadRelocation(snapshot)) return false
        // Reuse the original traversal, stopping at a rediscovered pending target.
        // Do not begin a new polling round or clear any attempt or send receipt.
        fullListPollingRoundRequired = true
        phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        atTop = false
        noProgressScrollCount = 0
        resetUnreadBoundaryObservation()
        executionState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
        return true
    }

    fun confirmPhysicalEndForReturnTraversal() {
        phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        atTop = false
        lockedUnreadSetFinalized = true
        fullListPollingRoundRequired = false
    }

    private fun isPendingIncompleteRow(row: ChatListCandidateSnapshot): Boolean =
        incompleteUnreadHint(row) in unresolvedIncompleteUnreadKeys ||
            anonymousUnreadHint(row) in unresolvedIncompleteUnreadKeys

    fun selectNextCandidate(
        candidates: List<ChatListCandidateSnapshot>,
        lockedIdentityKeys: Set<String> = emptySet()
    ): ChatListCandidateSnapshot? {
        val eligibleCandidates = if (lockedIdentityKeys.isNotEmpty()) {
            candidates.filter { ChatSessionManagementPolicy.stableSessionKey(it) in lockedIdentityKeys }
        } else {
            candidates
        }
        return ChatSessionManagementPolicy.selectNext(
            eligibleCandidates.filterNot {
                isPendingIncompleteRow(it) || it.rowClippedAtViewportEdge ||
                    ChatSessionManagementPolicy.stableSessionKey(it) in unresolvedUnknownUnreadKeys
            },
            attemptedSessionKeys,
            ambiguousSessionIdentityKeys
        )
    }

    private fun resetUnreadBoundaryObservation() {
        unreadBoundarySignature = ""
        unreadBoundaryStabilityCount = 0
    }

    private fun buildUnreadBoundarySignature(snapshot: ChatPageSnapshot): String {
        val rows = snapshot.chatListCandidates
            .filter { ChatSessionManagementPolicy.isEligible(it) }
            .filter {
                !it.rowClippedAtViewportEdge &&
                    ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)
            }
            .sortedWith(
                compareBy<ChatListCandidateSnapshot> { it.rowBounds.top }
                    .thenBy { it.rowBounds.bottom }
            )
        val window = snapshot.windowIdentityKey() ?: return ""
        val keys = rows.mapNotNull { row ->
            ChatSessionManagementPolicy.stableSessionKey(row)
                .takeIf(String::isNotBlank)
                ?.let { key ->
                    "$key@${row.rowBounds.left},${row.rowBounds.top}," +
                        "${row.rowBounds.right},${row.rowBounds.bottom}:${row.unreadStatus.name}"
                }
        }
        return if (rows.isNotEmpty() && keys.size == rows.size) "$window|${keys.joinToString("|")}" else ""
    }

    fun unreadEvidence(snapshot: ChatPageSnapshot): PrivateMessagesUnreadEvidence {
        val eligible = snapshot.chatListCandidates.filter {
            ChatSessionManagementPolicy.isEligible(it) && !isNonContributingClippedEdge(snapshot, it)
        }
        return PrivateMessagesUnreadEvidence(
            hasUnread = eligible.any { it.unreadStatus == ChatUnreadStatus.UNREAD },
            hasUnknown = eligible.any { it.unreadStatus == ChatUnreadStatus.UNKNOWN }
        )
    }

    /**
     * Owns the policy sequence from a fresh inbox snapshot to the next traversal decision.
     * The returned packet contains only decisions; screenshots, diagnostics, timers, navigation
     * and gestures remain effects performed by AiReplyObservationRuntime.
     */
    fun evaluateListSnapshot(
        snapshot: ChatPageSnapshot,
        wasWaitingForUnread: Boolean,
        maxNoProgressScrolls: Int,
        allowExpectedSessionReturnViewportChange: Boolean = false,
        allowCollectionTopConfirmation: Boolean = true
    ): PrivateMessagesListSnapshotEvaluation {
        val snapshotAmbiguousKeys = ambiguousIdentityKeys(snapshot.chatListCandidates)
        val incompleteUnreadEvidenceBlocked = observeIncompleteUnreadEvidence(snapshot)
        val newlyAmbiguousKeys = snapshotAmbiguousKeys - ambiguousSessionIdentityKeys
        if (snapshotAmbiguousKeys.isNotEmpty()) {
            ambiguousSessionIdentityKeys += snapshotAmbiguousKeys
        }

        val incompleteUnreadAllowsIndependentTraversal =
            incompleteUnreadEvidenceBlocked && canContinueWithIncompleteUnread(snapshot)

        // Diagnostic counters are deliberately not part of stableKey(), so audit updates cannot
        // invalidate a pending row or gesture operation.
        val unreadAuditFingerprint =
            "${executionState.name}:${snapshot.stableKey()}:" +
                "${snapshot.chatListUnreadDiagnostics}:${snapshotAmbiguousKeys.sorted().joinToString(",")}"
        val unreadAuditChanged = unreadAuditFingerprint != lastUnreadAuditFingerprint
        if (unreadAuditChanged) lastUnreadAuditFingerprint = unreadAuditFingerprint

        val hadPendingScrollBaseline = pendingScrollBaseline != null
        val visibleKeys = visibleSessionKeys(snapshot)
        val wasScrollingToTop = pendingScrollToTop
        val observedScrollDecision = consumePendingScrollObservation(
            visibleKeys = scrollPositionKeys(snapshot),
            maxNoProgressScrolls = maxNoProgressScrolls,
            boundaryEvidence = collectionBoundaryEvidence(snapshot, toTop = wasScrollingToTop)
        )
        val viewportPositionInvalidated = if (
            observedScrollDecision == PrivateMessagesScrollObservationDecision.POSITION_UNVERIFIED
        ) {
            invalidateTraversalPosition(snapshot)
            true
        } else if (allowExpectedSessionReturnViewportChange) {
            // A verified return may reorder the processed row. Preserve the phase,
            // but do not carry a stale top assertion into the changed viewport.
            if (atTop && (lastStableSessionListVisibleKeys != visibleKeys ||
                    collectionBoundaryEvidence(snapshot, toTop = true) == false)
            ) atTop = false
            false
        } else {
            reconcileUnrequestedViewportChange(
                hadPendingScrollBaseline = hadPendingScrollBaseline,
                wasWaitingForUnread = wasWaitingForUnread,
                visibleKeys = visibleKeys,
                snapshot = snapshot
            )
        }
        // Do not supersede an existing viewport recovery decision. Reuse the independent
        // boundary verifier only within the current valid traversal.
        val scrollDecision = if (allowCollectionTopConfirmation && !viewportPositionInvalidated &&
            !wasWaitingForUnread && !atTop && visibleKeys.isNotEmpty() &&
            (!hadPendingScrollBaseline || wasScrollingToTop) &&
            (!initialInboxTopPositioningCompleted || phase == SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP) &&
            collectionBoundaryEvidence(snapshot, toTop = true) == true
        ) PrivateMessagesScrollObservationDecision.TOP_BOUNDARY_CANDIDATE
        else observedScrollDecision
        if (!viewportPositionInvalidated) {
            recordVerifiedViewport(snapshot)
            if (initialInboxTopPositioningCompleted && !wasWaitingForUnread &&
                phase == SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
            ) {
                observeUnreadBlockers(snapshot)
                reconcileAdmittedUnread(snapshot)
            }
        }

        val candidatesEmpty = snapshot.chatListCandidates.isEmpty()
        if (!candidatesEmpty) sessionListStabilityRetryCount = 0

        val unreadAdmissionDecision = if (!viewportPositionInvalidated && initialInboxTopPositioningCompleted && !candidatesEmpty &&
            (phase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM ||
                phase == SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY) && !wasWaitingForUnread
        ) {
            evaluateUnreadAdmission(snapshot)
        } else {
            null
        }

        val explicitUnreadWhileWaiting = wasWaitingForUnread && snapshot.chatListCandidates.any {
            it.unreadStatus == ChatUnreadStatus.UNREAD && ChatSessionManagementPolicy.isEligible(it)
        }
        // A viewport observation is not a round transition. Keep the previous round's
        // attempted identities intact until the runtime has independently confirmed
        // a fresh inbox and explicitly calls beginNextPollingRound().
        val nextCandidate = if (!viewportPositionInvalidated && initialInboxTopPositioningCompleted &&
            !wasWaitingForUnread && !candidatesEmpty && !snapshot.chatListExtractionTruncated &&
            (!incompleteUnreadEvidenceBlocked || incompleteUnreadAllowsIndependentTraversal) &&
            (!normalInboxUnreadStateUnknown || canContinueWithUnknownUnread(snapshot)) &&
            (unreadAdmissionDecision == null ||
                unreadAdmissionDecision == PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED)
        ) {
            // Candidates come from the admitted ledger, not a fallback to every row
            // when that ledger happens to be empty.
            val selectable = snapshot.chatListCandidates.filter {
                ChatSessionManagementPolicy.stableSessionKey(it) in lockedUnreadSessionKeys
            }.filter {
                isUniqueCompletePrivateRow(snapshot, it, ambiguousSessionIdentityKeys)
            }
            selectNextCandidate(selectable, lockedUnreadSessionKeys)
        } else {
            null
        }

        val structuralFingerprint = buildString {
            append(snapshot.stableKey())
            append("|visibleRows=")
            append(visibleKeys.joinToString(","))
        }

        return PrivateMessagesListSnapshotEvaluation(
            snapshotAmbiguousKeys = snapshotAmbiguousKeys,
            newlyAmbiguousKeys = newlyAmbiguousKeys,
            incompleteUnreadEvidenceBlocked = incompleteUnreadEvidenceBlocked,
            incompleteUnreadAllowsIndependentTraversal = incompleteUnreadAllowsIndependentTraversal,
            unreadAuditChanged = unreadAuditChanged,
            wasWaitingForUnread = wasWaitingForUnread,
            visibleKeys = visibleKeys,
            scrollDecision = scrollDecision,
            viewportPositionInvalidated = viewportPositionInvalidated,
            candidatesEmpty = candidatesEmpty,
            unreadAdmissionDecision = unreadAdmissionDecision,
            explicitUnreadWhileWaiting = explicitUnreadWhileWaiting,
            nextCandidate = nextCandidate,
            structuralFingerprint = structuralFingerprint
        )
    }

    /** Accumulates the same tri-state evidence as the original runtime list state machine. */
    fun recordViewportUnreadEvidence(snapshot: ChatPageSnapshot, receivedGreetingsActive: Boolean) {
        if (receivedGreetingsActive) return
        val evidence = unreadEvidence(snapshot)
        if (phase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM) {
            if (!lockedUnreadSetFinalized) {
                // Every ordinary round preserves earlier unread evidence across viewports.
                normalInboxHadUnread = normalInboxHadUnread || evidence.hasUnread
                // A clean *different* viewport must not erase an unresolved historic UNKNOWN.
                // The admission gate below owns revalidation of saved blocked identities.
                if (evidence.hasUnknown) normalInboxUnreadStateUnknown = true
                else if (unresolvedUnknownUnreadKeys.isEmpty() &&
                    legacyUnknownUnreadViewportKeys == null
                ) normalInboxUnreadStateUnknown = false
            } else if (evidence.hasUnknown) {
                normalInboxUnreadStateUnknown = true
            }
        } else {
            if (evidence.hasUnread) normalInboxHadUnread = true
            if (evidence.hasUnknown) normalInboxUnreadStateUnknown = true
        }
    }

    /**
     * An actually clipped edge holder is not a complete row. It contributes no READ
     * evidence; explicit or ambiguous badge evidence still prevents an all-read viewport.
     */
    private fun isNonContributingClippedEdge(snapshot: ChatPageSnapshot, row: ChatListCandidateSnapshot): Boolean {
        val viewport = snapshot.chatListViewport ?: return false
        return row.rowBoundsValid && row.intersectsViewport && row.rowVisibleToUser &&
            (!row.fullyInsideViewport || row.rowClippedAtViewportEdge) &&
            (row.rowBounds.top <= viewport.top || row.rowBounds.bottom >= viewport.bottom) &&
            row.unreadStatus != ChatUnreadStatus.UNREAD &&
            (row.unreadStatus == ChatUnreadStatus.READ ||
                (!row.statusNewNodePresent && row.statusNewNodeObservationCount == 0 &&
                    row.unreadEvidenceSource == com.freedom.features.aireply.ports.ChatUnreadEvidenceSource.NONE &&
                    row.unreadNodeEvidence.isEmpty()))
    }

    /** Admit the current confirmed-top snapshot itself; no compulsory swipe. */
    fun evaluateUnreadAdmission(snapshot: ChatPageSnapshot): PrivateMessagesUnreadAdmissionDecision {
        if (!initialInboxTopPositioningCompleted) {
            return PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_TOP_CONFIRMATION
        }
        initialUnreadGateSwipeCompleted = true
        val unknownBlocked = observeUnreadBlockers(snapshot)
        reconcileAdmittedUnread(snapshot)
        val viewportUnread = snapshot.chatListCandidates.filter {
            it.unreadStatus == ChatUnreadStatus.UNREAD && ChatSessionManagementPolicy.isEligible(it)
        }
        if (unknownBlocked && !canContinueWithUnknownUnread(snapshot)) {
            resetUnreadBoundaryObservation()
            return PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED
        }
        // An exceptional same-round search may stop at the first newly actionable
        // pending identity instead of forcing a full physical-bottom scan.
        if (fullListPollingRoundRequired && lockedUnreadSetFinalized) {
            val pendingKeys = pendingKnownUnreadKeys()
            val targets = snapshot.chatListCandidates.filter {
                ChatSessionManagementPolicy.stableSessionKey(it) in pendingKeys &&
                    isUniqueCompletePrivateRow(snapshot, it, ambiguousSessionIdentityKeys)
            }
            if (!hasPendingRoundWork() || selectNextCandidate(targets, pendingKeys) != null) {
                fullListPollingRoundRequired = false
                phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
                resetUnreadBoundaryObservation()
                return PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED
            }
            phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
            resetUnreadBoundaryObservation()
            return PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING
        }
        val privateRows = snapshot.chatListCandidates.filter { it.selectionClass == "PRIVATE_CHAT" }
        val completePrivateRows = privateRows.filter {
            !it.rowClippedAtViewportEdge &&
                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)
        }
        // Clipped holders may lose the timestamp/name and be UNCLASSIFIED. An observed
        // unread marker on such a row is not evidence of a clean read viewport.
        val unresolvedUnclassifiedBadge = snapshot.chatListCandidates.any {
            it.selectionClass == "UNCLASSIFIED" && it.unreadStatus != ChatUnreadStatus.READ &&
                (it.unreadStatus == ChatUnreadStatus.UNREAD || it.statusNewNodePresent ||
                    it.unreadNodeEvidence.isNotEmpty())
        }
        val cleanReadViewport = completePrivateRows.isNotEmpty() && !snapshot.chatListExtractionTruncated &&
            snapshot.chatListInvalidRowBoundsCount == 0 && !unresolvedUnclassifiedBadge &&
            privateRows.all {
                (it.unreadStatus == ChatUnreadStatus.READ && it in completePrivateRows) ||
                    isNonContributingClippedEdge(snapshot, it)
            }
        if (canContinueWithIncompleteUnread(snapshot) && !cleanReadViewport) {
            // Pending named evidence is not an empty inbox or a proven read suffix.
            // Continue only the existing list positioning path; the completion gate
            // retains the unresolved row until it is independently re-observed.
            phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
            resetUnreadBoundaryObservation()
            return PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING
        }
        if (viewportUnread.isNotEmpty()) {
            phase = SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY
            resetUnreadBoundaryObservation()
            return PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING
        }

        if (phase != SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY) {
            phase = SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY
            resetUnreadBoundaryObservation()
        }

        if (!cleanReadViewport) {
            resetUnreadBoundaryObservation()
            phase = SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY
            return PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING
        }

        val signature = buildUnreadBoundarySignature(snapshot)
        if (signature.isBlank()) {
            resetUnreadBoundaryObservation()
            return PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION
        }

        if (signature == unreadBoundarySignature) {
            unreadBoundaryStabilityCount++
        } else {
            unreadBoundarySignature = signature
            unreadBoundaryStabilityCount = 1
        }
        if (unreadBoundaryStabilityCount < UNREAD_BOUNDARY_STABILITY_CONFIRMATIONS) {
            return PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION
        }

        phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        lockedUnreadSetFinalized = true
        resetUnreadBoundaryObservation()
        // A no-work handoff is possible only at the verified top, after the same
        // full-read viewport proof used by a non-empty unread round.
        if (atTop && lockedUnreadSessionKeys.isEmpty() && !normalInboxHadUnread &&
            !hasPendingRoundWork()
        ) return PrivateMessagesUnreadAdmissionDecision.NO_UNREAD
        return PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED
    }

    /** Shared existing UNKNOWN blocker policy, also used by the round wakeup gate. */
    fun observeUnreadBlockers(snapshot: ChatPageSnapshot): Boolean {
        val currentUnknown = snapshot.chatListCandidates.filter {
            ChatSessionManagementPolicy.isEligible(it) && it.unreadStatus == ChatUnreadStatus.UNKNOWN &&
                !isNonContributingClippedEdge(snapshot, it)
        }
        // Keep identifiable badge rows with unusable unread evidence in the same
        // durable row ledger as structurally incomplete rows. A fresh independent
        // unread target permits scanning but never authorizes clicking the unknown.
        if (unresolvedUnknownUnreadKeys.isEmpty() && legacyUnknownUnreadViewportKeys == null &&
            currentUnknown.isNotEmpty() &&
            !snapshot.chatListExtractionTruncated && snapshot.chatListViewport != null &&
            currentUnknown.all { it.statusNewNodePresent && it.hasReadableName &&
                ChatSessionManagementPolicy.stableSessionKey(it) !in ambiguousSessionIdentityKeys } &&
            (currentUnknown.all(::isPendingIncompleteRow) || snapshot.chatListCandidates.any { it.unreadStatus == ChatUnreadStatus.UNREAD &&
                ChatSessionManagementPolicy.isEligible(it) &&
                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport) &&
                ChatSessionManagementPolicy.stableSessionKey(it) !in ambiguousSessionIdentityKeys })
        ) {
            normalInboxUnreadStateUnknown = false
            unresolvedIncompleteUnreadKeys += currentUnknown.map(::incompleteUnreadHint)
            return false
        }
        val hadNamedBlockers = unresolvedUnknownUnreadKeys.isNotEmpty()
        if (currentUnknown.isNotEmpty() && legacyUnknownUnreadViewportKeys == null) {
            if (unresolvedUnknownUnreadKeys.isEmpty()) {
                unknownUnreadViewportKeys.clear()
                unknownUnreadViewportKeys += visibleSessionKeys(snapshot)
            }
            unresolvedUnknownUnreadKeys += currentUnknown.map(ChatSessionManagementPolicy::stableSessionKey)
            normalInboxUnreadStateUnknown = true
        }
        // Legacy checkpoints without named blockers keep their exact anchor until
        // it is verified. Do not overwrite it with an unrelated named blocker.
        if (normalInboxUnreadStateUnknown && !snapshot.chatListExtractionTruncated &&
            snapshot.chatListViewport != null && snapshot.windowIdentityKey() != null
        ) {
            val conflicts = ambiguousIdentityKeys(snapshot.chatListCandidates) + ambiguousSessionIdentityKeys
            val rowsByKey = snapshot.chatListCandidates.groupBy(ChatSessionManagementPolicy::stableSessionKey)
            // Resolve each named blocker independently, not by old coordinates or
            // by another row becoming READ. A collision/partial row proves nothing.
            unresolvedUnknownUnreadKeys.removeAll { key ->
                val matches = rowsByKey[key].orEmpty()
                matches.size == 1 && matches.single().let { row ->
                    isUniqueCompletePrivateRow(snapshot, row, conflicts) &&
                        row.unreadStatus != ChatUnreadStatus.UNKNOWN
                }
            }
            val legacyAnchor = legacyUnknownUnreadViewportKeys
            val legacyRevalidated = legacyAnchor != null && legacyAnchor.isNotEmpty() &&
                legacyAnchor == visibleSessionKeys(snapshot) &&
                snapshot.chatListCandidates.none {
                    it.selectionClass == "PRIVATE_CHAT" && it.unreadStatus == ChatUnreadStatus.UNKNOWN
                }
            if (legacyRevalidated) legacyUnknownUnreadViewportKeys = null
            if (unresolvedUnknownUnreadKeys.isEmpty() && legacyUnknownUnreadViewportKeys == null &&
                currentUnknown.isEmpty() && (hadNamedBlockers || legacyRevalidated)
            ) {
                normalInboxUnreadStateUnknown = false
                unknownUnreadViewportKeys.clear()
            }
        }

        return normalInboxUnreadStateUnknown
    }

    /**
     * Consumes one completed scroll against its fresh geometry-aware viewport baseline and returns
     * the exact next boundary decision. Side effects (new gestures or delayed confirmation) stay
     * in the runtime, while the traversal transition itself is owned here.
     */
    fun consumePendingScrollObservation(
        visibleKeys: List<String>,
        maxNoProgressScrolls: Int,
        boundaryEvidence: Boolean? = null
    ): PrivateMessagesScrollObservationDecision {
        val baseline = pendingScrollBaseline
            ?: return PrivateMessagesScrollObservationDecision.NO_PENDING_SCROLL
        val previousPosition = collectionPositionFromKey(baseline.firstOrNull())
        val currentPosition = collectionPositionFromKey(visibleKeys.firstOrNull())
        val wasScrollingToTop = pendingScrollToTop
        pendingScrollBaseline = null
        pendingScrollToTop = false

        val positionChanged = if (previousPosition != null && currentPosition != null) {
            if (previousPosition.first != currentPosition.first) {
                return PrivateMessagesScrollObservationDecision.POSITION_UNVERIFIED
            }
            val before = previousPosition.second
            val after = currentPosition.second
            val beforeIdentities = orderedIdentityKeys(baseline)
            val afterIdentities = orderedIdentityKeys(visibleKeys)
            val sameIdentitySequence = beforeIdentities.isNotEmpty() && beforeIdentities == afterIdentities
            // Collection indexes can shift when a card is inserted even though the same
            // complete private rows remain visible. This alone is not scroll progress.
            val towardTop = after.first <= before.first && after.last <= before.last && after != before
            val towardBottom = after.first >= before.first && after.last >= before.last && after != before
            if (!sameIdentitySequence && (if (wasScrollingToTop) towardBottom else towardTop)) {
                // The observed movement contradicts the submitted direction. Do not
                // infer a completed scroll or boundary from the changed range.
                return PrivateMessagesScrollObservationDecision.POSITION_UNVERIFIED
            }
            !sameIdentitySequence && (if (wasScrollingToTop) towardTop else towardBottom)
        } else {
            // Preserve the existing conservative legacy evidence when collection
            // metadata is unavailable on either side.
            baseline.filterNot { it.startsWith(COLLECTION_POSITION_PREFIX) } !=
                visibleKeys.filterNot { it.startsWith(COLLECTION_POSITION_PREFIX) }
        }

        if (wasScrollingToTop) {
            if (positionChanged) {
                noProgressScrollCount = 0
                atTop = false
                return PrivateMessagesScrollObservationDecision.TOP_VIEWPORT_CHANGED
            }
            noProgressScrollCount += 1
            return if (noProgressScrollCount < maxNoProgressScrolls || boundaryEvidence == false) {
                atTop = false
                PrivateMessagesScrollObservationDecision.TOP_NO_PROGRESS_RETRY
            } else {
                PrivateMessagesScrollObservationDecision.TOP_BOUNDARY_CANDIDATE
            }
        }

        if (!positionChanged) {
            noProgressScrollCount += 1
            return if (
                (phase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM ||
                    phase == SessionListTraversalPhase.SEEKING_UNREAD_BOUNDARY) &&
                noProgressScrollCount >= maxNoProgressScrolls && boundaryEvidence != false
            ) {
                PrivateMessagesScrollObservationDecision.BOTTOM_BOUNDARY_CANDIDATE
            } else {
                PrivateMessagesScrollObservationDecision.BOTTOM_NO_PROGRESS
            }
        }

        noProgressScrollCount = 0
        atTop = false
        return PrivateMessagesScrollObservationDecision.BOTTOM_VIEWPORT_CHANGED
    }

    /** Returns true when an unsolicited viewport change invalidated a bottom-to-top scan. */
    fun reconcileUnrequestedViewportChange(
        hadPendingScrollBaseline: Boolean,
        wasWaitingForUnread: Boolean,
        visibleKeys: List<String>,
        snapshot: ChatPageSnapshot
    ): Boolean {
        if (hadPendingScrollBaseline || wasWaitingForUnread || visibleKeys.isEmpty() ||
            phase != SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        ) return false
        val previousVisibleKeys = lastStableSessionListVisibleKeys ?: return false
        val previousPosition = lastObservedViewportPosition
        val currentWindow = snapshot.windowIdentityKey()
        val currentRows = collectionRows(snapshot)?.let { it.first()..it.last() }
        val sameWindow = previousPosition == null ||
            (currentWindow != null && currentWindow == previousPosition.first)
        if (sameWindow && previousVisibleKeys == visibleKeys) {
            if (atTop && collectionBoundaryEvidence(snapshot, toTop = true) == false) atTop = false
            return false
        }

        val previousIdentities = orderedIdentityKeys(previousVisibleKeys)
        val currentIdentities = orderedIdentityKeys(visibleKeys)
        val common = previousIdentities.toSet().intersect(currentIdentities.toSet()).filter { key ->
            key !in ambiguousSessionIdentityKeys &&
                previousIdentities.count { it == key } == 1 &&
                currentIdentities.count { it == key } == 1
        }
        val priorRows = previousPosition?.second
        val overlappingRange = priorRows == null || currentRows == null ||
            (priorRows.first <= currentRows.last && currentRows.first <= priorRows.last)
        val orderedAnchors = previousIdentities.filter { it in common } ==
            currentIdentities.filter { it in common }
        if (sameWindow && !snapshot.chatListExtractionTruncated &&
            common.isNotEmpty() && overlappingRange &&
            (orderedAnchors || (priorRows != null && currentRows == priorRows))
        ) {
            // Local reflow: keep the confirmed unread boundary and direction. Rebuild
            // candidate geometry from this snapshot; do not restart the search below.
            atTop = false
            noProgressScrollCount = 0
            return false
        }

        invalidateTraversalPosition(snapshot)
        return true
    }

    private fun invalidateTraversalPosition(snapshot: ChatPageSnapshot) {
        // Retain the round, frozen unread boundary and old cursor as checkpoint evidence.
        // A new position requires the existing independent recovery verification.
        atTop = false
        noProgressScrollCount = 0
        sessionListStabilityRetryCount = 0
        val evidence = unreadEvidence(snapshot)
        normalInboxHadUnread = normalInboxHadUnread || evidence.hasUnread
        normalInboxUnreadStateUnknown = normalInboxUnreadStateUnknown || evidence.hasUnknown
    }

    /** Clears all traversal state at the AI Reply runtime reset boundary. */
    fun reset() {
        clearIdentityReturn()
        executionState = SessionManagerState.STOPPED
        clearCurrentSession()
        headerMismatchRecordedForCurrentAttempt = false
        phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        atTop = false
        initialInboxPositionCheckCompleted = false
        initialInboxTopPositioningCompleted = false
        initialUnreadGateSwipeCompleted = false
        attemptedSessionKeys.clear()
        ambiguousSessionIdentityKeys.clear()
        lockedUnreadSessionKeys.clear()
        lockedUnreadSetFinalized = false
        fullListPollingRoundRequired = false
        resetUnreadBoundaryObservation()
        normalInboxHadUnread = false
        normalInboxUnreadStateUnknown = false
        unresolvedUnknownUnreadKeys.clear()
        unknownUnreadViewportKeys.clear()
        unresolvedIncompleteUnreadKeys.clear()
        legacyUnknownUnreadViewportKeys = null
        sessionListStabilityRetryCount = 0
        sessionActionCount = 0
        noProgressScrollCount = 0
        pendingScrollBaseline = null
        pendingScrollToTop = false
        lastStableSessionListVisibleKeys = null
        resumeSessionKey = null
        resumeSessionName = null
        cancelledScrollRecoveryCount = 0
        capturedSessionListFingerprints.clear()
        pendingBoundaryConfirmationKeys = null
        pendingBoundaryConfirmationToTop = false
        boundaryStabilityConfirmationCount = 0
        lastUnreadAuditFingerprint = ""
    }

    /**
     * Begins a new ordinary-inbox pass after the greetings branch returns to the inbox.
     * The inbox context was already established; no startup probe gesture is required.
     */
    fun resetForReturnedInbox() {
        clearIdentityReturn()
        executionState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
        clearCurrentSession()
        lockedUnreadSessionKeys.clear()
        lockedUnreadSetFinalized = false
        fullListPollingRoundRequired = false
        resetUnreadBoundaryObservation()
        attemptedSessionKeys.clear()
        initialInboxPositionCheckCompleted = true
        phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        atTop = false
        noProgressScrollCount = 0
        pendingScrollBaseline = null
        pendingScrollToTop = false
        lastStableSessionListVisibleKeys = null
        resumeSessionKey = null
        resumeSessionName = null
        normalInboxHadUnread = false
        normalInboxUnreadStateUnknown = false
        unresolvedUnknownUnreadKeys.clear()
        unknownUnreadViewportKeys.clear()
        unresolvedIncompleteUnreadKeys.clear()
        legacyUnknownUnreadViewportKeys = null
    }

    /**
     * Start only after a completed pair of tasks and new inbox proof. Re-run the
     * established admission sequence, preserving run-scoped identity blocks and send audit.
     */
    fun beginNextPollingRound() {
        check(!normalInboxUnreadStateUnknown && unresolvedUnknownUnreadKeys.isEmpty() &&
            unresolvedIncompleteUnreadKeys.isEmpty())
        // Only an admitted NEW round retires old viewport capture deduplication.
        // A resumeAfterReturnedInbox() or restored same-round checkpoint must keep it.
        // Without this clear, repeated A/B viewport fingerprints are silently treated
        // as already captured in the next complete scan.
        resetForReturnedInbox()
        capturedSessionListFingerprints.clear()
        initialInboxPositionCheckCompleted = false
        initialInboxTopPositioningCompleted = false
        initialUnreadGateSwipeCompleted = false
        pendingBoundaryConfirmationKeys = null
        pendingBoundaryConfirmationToTop = false
        boundaryStabilityConfirmationCount = 0
        sessionListStabilityRetryCount = 0
        cancelledScrollRecoveryCount = 0
        sessionActionCount = 0
        headerMismatchRecordedForCurrentAttempt = false
        checkpointNextCursorKey = null
        lastUnreadAuditFingerprint = ""
        executionState = SessionManagerState.WAITING_FOR_SESSION_LIST
    }

    /**
     * Resumes the existing inbox traversal after the greetings branch returns.
     * The viewport may have changed, but the admission decision, attempted identities and
     * boundary memory remain valid; only the currently open conversation is no longer valid.
     */
    fun resumeAfterReturnedInbox() {
        clearCurrentSession()
        executionState = SessionManagerState.WAITING_FOR_SESSION_LIST
        pendingScrollBaseline = null
        pendingScrollToTop = false
        lastStableSessionListVisibleKeys = null
    }

    fun clearCurrentSession() {
        currentSessionKey = null
        currentSessionName = null
        currentSessionCandidate = null
        currentSessionReadCaptured = false
        currentSessionWindowKey = null
    }

    fun beginSession(
        candidate: ChatListCandidateSnapshot,
        sessionKey: String,
        windowKey: String?
    ) {
        rememberRecoveryAnchor(candidate)
        currentSessionKey = sessionKey
        currentSessionName = candidate.nameText
        currentSessionCandidate = candidate
        currentSessionReadCaptured = false
        currentSessionWindowKey = windowKey
        synchronized(headerMismatchGuard) {
            headerMismatchRecordedForCurrentAttempt = false
        }
    }

    fun setSessionReadCaptured(captured: Boolean) {
        currentSessionReadCaptured = captured
    }

}
