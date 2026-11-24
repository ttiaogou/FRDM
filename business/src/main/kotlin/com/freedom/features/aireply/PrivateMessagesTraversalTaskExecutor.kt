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
 * owns traversal progress, frozen unread identities, boundary evidence and candidate selection.
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
    FIRST_SWIPE_REQUIRED,
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
    BOTTOM_VIEWPORT_CHANGED
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
        return PrivateListRecoveryFaultEvidence.ORIGINAL_UNKNOWN_STILL_BLOCKED
    }
    if (probe.observeIncompleteUnreadEvidence(snapshot)) {
        return if (probe.canContinueWithIncompleteUnread(snapshot)) {
            PrivateListRecoveryFaultEvidence.LOCAL_REMEDIATION
        } else PrivateListRecoveryFaultEvidence.ORIGINAL_INCOMPLETE_STILL_BLOCKED
    }
    return PrivateListRecoveryFaultEvidence.CLEARED
}

private const val UNREAD_BOUNDARY_STABILITY_CONFIRMATIONS = 2

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
        attemptedSessionKeys = attemptedSessionKeys.toSet(),
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
        fullListPollingRoundRequired = checkpoint.fullListPollingRoundRequired
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
        val visible = visibleSessionKeys(snapshot)
        if (visible.isNotEmpty()) lastStableSessionListVisibleKeys = visible

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
    fun rememberRecoveryAnchor(candidate: ChatListCandidateSnapshot) {
        resumeSessionKey = ChatSessionManagementPolicy.stableSessionKey(candidate).takeIf(String::isNotBlank)
        resumeSessionName = candidate.nameText?.takeIf(String::isNotBlank)
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
    var initialUnreadGateSwipeCompleted: Boolean = false

    val attemptedSessionKeys: MutableSet<String> = LinkedHashSet()
    val ambiguousSessionIdentityKeys: MutableSet<String> = LinkedHashSet()
    val lockedUnreadSessionKeys: MutableSet<String> = LinkedHashSet()
    var lockedUnreadSetFinalized: Boolean = false
    /**
     * Set only when a verified timed polling round starts. The original first-run
     * fast admission remains unchanged; a timed scan must reach the list bottom
     * before it can conclude there are no offscreen unread conversations.
     */
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
    var lastStableSessionListVisibleKeys: List<String>? = null
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
            // Stable identity plus physical row geometry measures real viewport movement.
            // Row indexes are deliberately omitted because dynamic cards can shift row indexes.
            "$identityKey@${candidate.rowBounds.left},${candidate.rowBounds.top}," +
                "${candidate.rowBounds.right},${candidate.rowBounds.bottom}"
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
                it.intersectsViewport && it.fullyInsideViewport
        }
        val incomplete = rows.filter {
            it.unreadStatus != ChatUnreadStatus.READ &&
                !ChatSessionManagementPolicy.isEligible(it)
        }
        val activeIncompleteHints = incomplete.map(::incompleteUnreadHint).toSet()
        unresolvedIncompleteUnreadKeys += activeIncompleteHints
        if (!snapshot.chatListExtractionTruncated && unresolvedIncompleteUnreadKeys.isNotEmpty()) {
            unresolvedIncompleteUnreadKeys.removeAll { saved ->
                if (saved in activeIncompleteHints) return@removeAll false
                val matches = rows.filter { row ->
                    if (saved.startsWith("unnamed:")) anonymousUnreadHint(row) == saved
                    else incompleteUnreadHint(row) == saved
                }
                val allExplicit = matches.isNotEmpty() && matches.all {
                    ChatSessionManagementPolicy.isEligible(it) &&
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
    fun canContinueWithIncompleteUnread(snapshot: ChatPageSnapshot): Boolean {
        if (unresolvedIncompleteUnreadKeys.isEmpty() ||
            unresolvedIncompleteUnreadKeys.any { !it.startsWith("identity:") } ||
            snapshot.pageKind != ChatPageKind.CHAT_LIST || snapshot.chatListExtractionTruncated
        ) return false
        val conflicts = ambiguousIdentityKeys(snapshot.chatListCandidates) + ambiguousSessionIdentityKeys
        return snapshot.chatListCandidates.any { row ->
            val key = ChatSessionManagementPolicy.stableSessionKey(row)
            ChatSessionManagementPolicy.isEligible(row) &&
                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(row, snapshot.chatListViewport) &&
                row.unreadStatus != ChatUnreadStatus.UNKNOWN && key.isNotBlank() &&
                key !in conflicts && !isPendingIncompleteRow(row)
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
            !normalInboxUnreadStateUnknown && unresolvedUnknownUnreadKeys.isEmpty() &&
            snapshot.windowIdentityKey() != null && canContinueWithIncompleteUnread(snapshot)

    fun beginIncompleteUnreadRelocation(snapshot: ChatPageSnapshot): Boolean {
        if (!canBeginIncompleteUnreadRelocation(snapshot)) return false
        // Reuse the normal bottom/top traversal and its existing boundary proof. The
        // full-scan requirement survives the checkpoint even if the target repairs
        // before the bottom is reached. Do not call beginNextPollingRound(): all
        // attempted, locked, ambiguous, pending and send receipt keys must survive.
        fullListPollingRoundRequired = true
        phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        atTop = false
        noProgressScrollCount = 0
        resetUnreadBoundaryObservation()
        executionState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
        return true
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
            eligibleCandidates.filterNot(::isPendingIncompleteRow),
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
                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)
            }
            .sortedWith(
                compareBy<ChatListCandidateSnapshot> { it.rowBounds.top }
                    .thenBy { it.rowBounds.bottom }
            )
        val keys = rows.mapNotNull { row ->
            ChatSessionManagementPolicy.stableSessionKey(row)
                .takeIf(String::isNotBlank)
                ?.let { key ->
                    "$key@${row.rowBounds.left},${row.rowBounds.top}," +
                        "${row.rowBounds.right},${row.rowBounds.bottom}:${row.unreadStatus.name}"
                }
        }
        return if (rows.isNotEmpty() && keys.size == rows.size) keys.joinToString("|") else ""
    }

    fun unreadEvidence(snapshot: ChatPageSnapshot): PrivateMessagesUnreadEvidence {
        val eligible = snapshot.chatListCandidates.filter(ChatSessionManagementPolicy::isEligible)
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
        allowExpectedSessionReturnViewportChange: Boolean = false
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
        val scrollDecision = consumePendingScrollObservation(
            visibleKeys = visibleKeys,
            maxNoProgressScrolls = maxNoProgressScrolls
        )

        val viewportPositionInvalidated = if (allowExpectedSessionReturnViewportChange) {
            false
        } else {
            reconcileUnrequestedViewportChange(
                hadPendingScrollBaseline = hadPendingScrollBaseline,
                wasWaitingForUnread = wasWaitingForUnread,
                visibleKeys = visibleKeys,
                snapshot = snapshot
            )
        }
        if (visibleKeys.isNotEmpty()) lastStableSessionListVisibleKeys = visibleKeys

        val candidatesEmpty = snapshot.chatListCandidates.isEmpty()
        if (!candidatesEmpty) sessionListStabilityRetryCount = 0

        val unreadAdmissionDecision = if (initialInboxTopPositioningCompleted && !candidatesEmpty &&
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
        val nextCandidate = if (initialInboxTopPositioningCompleted && !candidatesEmpty &&
            (!incompleteUnreadEvidenceBlocked || incompleteUnreadAllowsIndependentTraversal) &&
            (unreadAdmissionDecision == null ||
                unreadAdmissionDecision == PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED)
        ) {
            val selectable = if (incompleteUnreadEvidenceBlocked) {
                snapshot.chatListCandidates.filter {
                    ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)
                }
            } else snapshot.chatListCandidates
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
                // During a full polling scan, a later read-only viewport must not
                // erase unread evidence collected on an earlier offscreen viewport.
                normalInboxHadUnread = if (fullListPollingRoundRequired) {
                    normalInboxHadUnread || evidence.hasUnread
                } else evidence.hasUnread
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

    /** Applies the unread gate after the mandatory first successful upward swipe. */
    fun evaluateUnreadAdmission(snapshot: ChatPageSnapshot): PrivateMessagesUnreadAdmissionDecision {
        if (!initialUnreadGateSwipeCompleted) {
            return PrivateMessagesUnreadAdmissionDecision.FIRST_SWIPE_REQUIRED
        }
        val viewportUnread = snapshot.chatListCandidates.filter {
            it.unreadStatus == ChatUnreadStatus.UNREAD && ChatSessionManagementPolicy.isEligible(it)
        }
        if (!lockedUnreadSetFinalized) {
            lockedUnreadSessionKeys += viewportUnread.map(ChatSessionManagementPolicy::stableSessionKey)
            normalInboxHadUnread = lockedUnreadSessionKeys.isNotEmpty()
        }
        if (observeUnreadBlockers(snapshot)) {
            resetUnreadBoundaryObservation()
            return PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED
        }
        val privateRows = snapshot.chatListCandidates.filter { it.selectionClass == "PRIVATE_CHAT" }
        val cleanReadViewport = privateRows.isNotEmpty() && !snapshot.chatListExtractionTruncated &&
            privateRows.all { it.unreadStatus == ChatUnreadStatus.READ &&
                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport) }
        if (canContinueWithIncompleteUnread(snapshot) &&
            (lockedUnreadSessionKeys.isEmpty() || !cleanReadViewport)
        ) {
            // Pending named evidence is not an empty inbox or a proven read suffix.
            // Continue only the existing list positioning path; the completion gate
            // retains the unresolved row until it is independently re-observed.
            phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
            resetUnreadBoundaryObservation()
            return PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING
        }
        if (fullListPollingRoundRequired) {
            // A 60-120s wakeup is not a one-viewport poll: scan to the independently
            // confirmed bottom, collecting only explicit unread identities. Existing
            // safe scroll/boundary verification is unchanged.
            phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
            resetUnreadBoundaryObservation()
            return PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING
        }
        if (lockedUnreadSessionKeys.isEmpty()) {
            resetUnreadBoundaryObservation()
            return PrivateMessagesUnreadAdmissionDecision.NO_UNREAD
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

        val eligibleRows = snapshot.chatListCandidates.filter(ChatSessionManagementPolicy::isEligible)
        val visibleReadSuffix = eligibleRows.isNotEmpty() &&
            eligibleRows.all { it.unreadStatus == ChatUnreadStatus.READ }
        if (snapshot.chatListExtractionTruncated || !visibleReadSuffix || !cleanReadViewport) {
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
        return PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED
    }

    /** Shared existing UNKNOWN blocker policy, also used by the round wakeup gate. */
    fun observeUnreadBlockers(snapshot: ChatPageSnapshot): Boolean {
        val currentUnknown = snapshot.chatListCandidates.filter {
            ChatSessionManagementPolicy.isEligible(it) && it.unreadStatus == ChatUnreadStatus.UNKNOWN
        }
        // Keep identifiable badge rows with unusable unread evidence in the same
        // durable row ledger as structurally incomplete rows. A fresh independent
        // unread target permits scanning but never authorizes clicking the unknown.
        if (!normalInboxUnreadStateUnknown && currentUnknown.isNotEmpty() &&
            !snapshot.chatListExtractionTruncated && snapshot.chatListViewport != null &&
            currentUnknown.all { it.statusNewNodePresent && it.hasReadableName &&
                ChatSessionManagementPolicy.stableSessionKey(it) !in ambiguousSessionIdentityKeys } &&
            (currentUnknown.all(::isPendingIncompleteRow) || snapshot.chatListCandidates.any { it.unreadStatus == ChatUnreadStatus.UNREAD &&
                ChatSessionManagementPolicy.isEligible(it) &&
                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport) &&
                ChatSessionManagementPolicy.stableSessionKey(it) !in ambiguousSessionIdentityKeys })
        ) {
            unresolvedIncompleteUnreadKeys += currentUnknown.map(::incompleteUnreadHint)
            return false
        }
        if (currentUnknown.isNotEmpty()) {
            if (unresolvedUnknownUnreadKeys.isEmpty()) {
                unknownUnreadViewportKeys.clear()
                unknownUnreadViewportKeys += visibleSessionKeys(snapshot)
            }
            unresolvedUnknownUnreadKeys += currentUnknown.map(ChatSessionManagementPolicy::stableSessionKey)
            legacyUnknownUnreadViewportKeys = null
            normalInboxUnreadStateUnknown = true
        } else if (normalInboxUnreadStateUnknown && !snapshot.chatListExtractionTruncated &&
            snapshot.chatListViewport != null
        ) {
            // A clean *different* viewport does not resolve an old UNKNOWN. Only an exact
            // re-observation of the blocked identities and their original geometry may release
            // the latch; each formerly UNKNOWN eligible row must now explicitly say READ/UNREAD.
            val visibleKeys = visibleSessionKeys(snapshot)
            val knownAnchor = unknownUnreadViewportKeys.ifEmpty { legacyUnknownUnreadViewportKeys.orEmpty() }
            val sameBlockedViewport = knownAnchor.isNotEmpty() && knownAnchor == visibleKeys
            val eligibleRows = snapshot.chatListCandidates.filter {
                ChatSessionManagementPolicy.isEligible(it) &&
                    ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)
            }
            val blockersNowExplicit = unresolvedUnknownUnreadKeys.isNotEmpty() &&
                unresolvedUnknownUnreadKeys.all { key ->
                    val matches = eligibleRows.filter { ChatSessionManagementPolicy.stableSessionKey(it) == key }
                    matches.size == 1 && matches.single().unreadStatus != ChatUnreadStatus.UNKNOWN
                }
            // Legacy checkpoints did not persist the unknown row keys. They can be reconciled
            // only on the exact old viewport after its current eligible UNKNOWN count reaches 0.
            val legacyRevalidated = unresolvedUnknownUnreadKeys.isEmpty() &&
                legacyUnknownUnreadViewportKeys != null &&
                // With no durable blocker-row keys, every current private row must have an
                // explicit unread state. "Eligible UNKNOWN = 0" alone is not enough.
                snapshot.chatListCandidates.none {
                    it.selectionClass == "PRIVATE_CHAT" && it.unreadStatus == ChatUnreadStatus.UNKNOWN
                }
            if (sameBlockedViewport && (blockersNowExplicit || legacyRevalidated)) {
                normalInboxUnreadStateUnknown = false
                unresolvedUnknownUnreadKeys.clear()
                unknownUnreadViewportKeys.clear()
                legacyUnknownUnreadViewportKeys = null
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
        maxNoProgressScrolls: Int
    ): PrivateMessagesScrollObservationDecision {
        val baseline = pendingScrollBaseline
            ?: return PrivateMessagesScrollObservationDecision.NO_PENDING_SCROLL
        val positionChanged = visibleKeys != baseline
        val wasScrollingToTop = pendingScrollToTop
        pendingScrollBaseline = null
        pendingScrollToTop = false

        if (wasScrollingToTop) {
            if (positionChanged) {
                noProgressScrollCount = 0
                atTop = false
                return PrivateMessagesScrollObservationDecision.TOP_VIEWPORT_CHANGED
            }
            noProgressScrollCount += 1
            return if (noProgressScrollCount < maxNoProgressScrolls) {
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
                noProgressScrollCount >= maxNoProgressScrolls
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
        if (previousVisibleKeys == visibleKeys) return false

        phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        atTop = false
        noProgressScrollCount = 0
        sessionListStabilityRetryCount = 0
        val evidence = unreadEvidence(snapshot)
        normalInboxHadUnread = evidence.hasUnread
        normalInboxUnreadStateUnknown = evidence.hasUnknown
        return true
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
     * The startup admission swipe is not replayed because the inbox context was already established.
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
        fullListPollingRoundRequired = true
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
