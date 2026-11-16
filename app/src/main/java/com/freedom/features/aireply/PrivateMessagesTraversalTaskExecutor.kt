package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.aireply.ports.ChatUnreadStatus

/**
 * Owns the private-message list traversal state and pure list decisions.
 *
 * Page snapshots and operation results are supplied by AiReplyObservationRuntime; this component
 * owns traversal progress, frozen unread identities, boundary evidence and candidate selection.
 * Android access, scheduling and gesture dispatch remain outside this class.
 */
internal enum class SessionListTraversalPhase {
    POSITIONING_AT_BOTTOM,
    SCANNING_BOTTOM_TO_TOP
}

internal data class PrivateMessagesUnreadEvidence(
    val hasUnread: Boolean,
    val hasUnknown: Boolean
)

internal enum class PrivateMessagesUnreadAdmissionDecision {
    FIRST_SWIPE_REQUIRED,
    UNKNOWN_BLOCKED,
    NO_UNREAD,
    CONTINUE_BOTTOM_POSITIONING
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

internal class PrivateMessagesTraversalTaskExecutor {
    var phase: SessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
    var atTop: Boolean = false

    var initialInboxPositionCheckCompleted: Boolean = false
    var initialUnreadGateSwipeCompleted: Boolean = false

    val attemptedSessionKeys: MutableSet<String> = LinkedHashSet()
    val ambiguousSessionIdentityKeys: MutableSet<String> = LinkedHashSet()
    val lockedUnreadSessionKeys: MutableSet<String> = LinkedHashSet()
    var lockedUnreadSetFinalized: Boolean = false

    var normalInboxHadUnread: Boolean = false
    var normalInboxUnreadStateUnknown: Boolean = false
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

    fun ambiguousIdentityKeys(candidates: List<ChatListCandidateSnapshot>): Set<String> =
        ChatSessionManagementPolicy.ambiguousSessionIdentityKeys(candidates)

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
            eligibleCandidates,
            attemptedSessionKeys,
            ambiguousSessionIdentityKeys
        )
    }

    fun unreadEvidence(snapshot: ChatPageSnapshot): PrivateMessagesUnreadEvidence {
        val eligible = snapshot.chatListCandidates.filter(ChatSessionManagementPolicy::isEligible)
        return PrivateMessagesUnreadEvidence(
            hasUnread = eligible.any { it.unreadStatus == ChatUnreadStatus.UNREAD },
            hasUnknown = eligible.any { it.unreadStatus == ChatUnreadStatus.UNKNOWN }
        )
    }

    /** Accumulates the same tri-state evidence as the original runtime list state machine. */
    fun recordViewportUnreadEvidence(snapshot: ChatPageSnapshot, receivedGreetingsActive: Boolean) {
        if (receivedGreetingsActive) return
        val evidence = unreadEvidence(snapshot)
        if (phase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM) {
            if (!lockedUnreadSetFinalized) {
                normalInboxHadUnread = evidence.hasUnread
                normalInboxUnreadStateUnknown = evidence.hasUnknown
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
        if (snapshot.chatListCandidates.any {
                ChatSessionManagementPolicy.isEligible(it) && it.unreadStatus == ChatUnreadStatus.UNKNOWN
            }
        ) normalInboxUnreadStateUnknown = true

        return when {
            normalInboxUnreadStateUnknown -> PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED
            lockedUnreadSessionKeys.isEmpty() -> PrivateMessagesUnreadAdmissionDecision.NO_UNREAD
            else -> PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING
        }
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
            return if (phase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM &&
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
        phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        atTop = false
        initialInboxPositionCheckCompleted = false
        initialUnreadGateSwipeCompleted = false
        attemptedSessionKeys.clear()
        ambiguousSessionIdentityKeys.clear()
        lockedUnreadSessionKeys.clear()
        lockedUnreadSetFinalized = false
        normalInboxHadUnread = false
        normalInboxUnreadStateUnknown = false
        sessionListStabilityRetryCount = 0
        sessionActionCount = 0
        noProgressScrollCount = 0
        pendingScrollBaseline = null
        pendingScrollToTop = false
        lastStableSessionListVisibleKeys = null
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
        lockedUnreadSessionKeys.clear()
        lockedUnreadSetFinalized = false
        attemptedSessionKeys.clear()
        initialInboxPositionCheckCompleted = true
        phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        atTop = false
        noProgressScrollCount = 0
        pendingScrollBaseline = null
        pendingScrollToTop = false
        lastStableSessionListVisibleKeys = null
        normalInboxHadUnread = false
        normalInboxUnreadStateUnknown = false
    }
}
