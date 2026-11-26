package com.freedom.features.aireply.ports

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.conversation.domain.contact.ContactRowIdentity

/** Result of the platform-specific, read-only identity capture step before opening a session. */
enum class ContactIdentityCaptureRequestResult {
    /** The adapter does not implement profile-based identity capture; preserve its existing flow. */
    NOT_SUPPORTED,
    /** An avatar/profile capture gesture was accepted and is now being observed. */
    STARTED,
    /** Another identity capture is already in progress; wait for a fresh page snapshot. */
    IN_PROGRESS,
    /** The current snapshot is stale or temporarily unavailable; reacquire before deciding. */
    RETRY_LATER,
    /** Identity could not be safely captured; do not open this name-only session. */
    REJECTED
}

/**
 * Explicitly observable identity-capture state for the conversation pipeline.
 * A request result alone is not enough to decide whether message/history loading may proceed;
 * callers must wait while [awaiting] is true and may continue only after [completed] is true.
 */
data class ContactIdentityCaptureOutcome(
    val request: ContactIdentityCaptureRequestResult,
    val completed: Boolean = false,
    val awaiting: Boolean = request == ContactIdentityCaptureRequestResult.STARTED ||
        request == ContactIdentityCaptureRequestResult.IN_PROGRESS ||
        request == ContactIdentityCaptureRequestResult.RETRY_LATER,
    val verifiedIdentity: ContactRowIdentity? = null,
    val failureReason: String? = null
) {
    init {
        require(!(completed && awaiting)) { "identity capture cannot be completed and awaiting" }
        require(verifiedIdentity == null || completed) {
            "verified identity requires completed identity capture"
        }
        require(failureReason == null || !awaiting) {
            "failure reason is only valid for a terminal identity capture result"
        }
    }

    companion object {
        fun waiting(request: ContactIdentityCaptureRequestResult, reason: String? = null) =
            ContactIdentityCaptureOutcome(request = request, awaiting = true, failureReason = reason)

        fun completed(identity: ContactRowIdentity) = ContactIdentityCaptureOutcome(
            request = ContactIdentityCaptureRequestResult.STARTED,
            completed = true,
            awaiting = false,
            verifiedIdentity = identity
        )

        fun failed(request: ContactIdentityCaptureRequestResult, reason: String) =
            ContactIdentityCaptureOutcome(
                request = request,
                completed = true,
                awaiting = false,
                failureReason = reason
            )
    }
}

/** Real observed detail evidence for one visit, never a synthetic list row or gesture receipt. */
data class ObservedPrivateChatIdentityTarget(
    val snapshot: ChatPageSnapshot,
    val runGeneration: Long,
    val visitToken: String
) {
    fun sourceWindow(): WindowRef? {
        val message = snapshot.messageListTarget?.window ?: return null
        val input = snapshot.inputFieldTarget?.window ?: return null
        return message.takeIf {
            runGeneration >= 0L && visitToken.isNotBlank() &&
                snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
                snapshot.packageName.isNotBlank() && snapshot.packageName == message.packageName &&
                message == input && snapshot.windowIdentityKey() != null &&
                snapshot.conversationIdentityKey() != null && !snapshot.nodeTreeProbeTruncated
        }
    }

    override fun toString(): String =
        "ObservedPrivateChatIdentityTarget(runGeneration=$runGeneration,sourceValid=${sourceWindow() != null})"
}

/**
 * Read-only session navigation and local sample-storage boundary.
 * Implementations must revalidate every row locator immediately before returning tap bounds.
 */
interface ChatSessionManagementPort {
    fun beginSessionManagement(): String?

    /** Non-consuming diagnostic probe; never authorizes UI or message actions. */
    fun hasPendingIdentityReturnReinspectionRequest(): Boolean = false

    /** Consumes the one-shot signal raised when profile identity returned to the original chat. */
    fun consumeIdentityReturnReinspectionRequest(): Boolean = false

    /**
     * Acquires a platform identity for a private-chat row that has no verified ID yet.
     * Implementations must use their validated profile-navigation and identity-parser path;
     * the default keeps adapters without this capability behavior-compatible.
     */
    fun requestContactIdentityCapture(
        candidate: ChatListCandidateSnapshot
    ): ContactIdentityCaptureRequestResult = ContactIdentityCaptureRequestResult.NOT_SUPPORTED

    /**
     * Returns an explicit terminal/waiting outcome for the identity transaction. This is the
     * hand-off used by the conversation pipeline before it loads history or calls the model.
     * Existing adapters remain compatible until they implement the richer transaction.
     */
    fun observeContactIdentityCapture(
        candidate: ChatListCandidateSnapshot
    ): ContactIdentityCaptureOutcome {
        val request = requestContactIdentityCapture(candidate)
        return when (request) {
            ContactIdentityCaptureRequestResult.STARTED,
            ContactIdentityCaptureRequestResult.IN_PROGRESS,
            ContactIdentityCaptureRequestResult.RETRY_LATER ->
                ContactIdentityCaptureOutcome.waiting(request)
            ContactIdentityCaptureRequestResult.NOT_SUPPORTED ->
                ContactIdentityCaptureOutcome.failed(request, "IDENTITY_CAPTURE_NOT_SUPPORTED")
            ContactIdentityCaptureRequestResult.REJECTED ->
                ContactIdentityCaptureOutcome.failed(request, "IDENTITY_CAPTURE_REJECTED")
        }
    }

    /** Explicit adapter opt-in; a missing capability cannot admit a name-only detail. */
    fun supportsObservedPrivateChatIdentityCapture(): Boolean = false

    /** Same profile-ID transaction, using the actual detail surface as its source. */
    fun observeContactIdentityCapture(target: ObservedPrivateChatIdentityTarget): ContactIdentityCaptureOutcome =
        ContactIdentityCaptureOutcome.failed(
            ContactIdentityCaptureRequestResult.NOT_SUPPORTED, "OBSERVED_DETAIL_IDENTITY_NOT_SUPPORTED"
        )

    fun hasPendingObservedPrivateChatIdentityCapture(target: ObservedPrivateChatIdentityTarget): Boolean = false

    /** Unlike the legacy row receipt, the observed detail receipt is visit-scoped. */
    fun hasObservedPrivateChatIdentityReturn(target: ObservedPrivateChatIdentityTarget): Boolean = false

    fun consumeObservedPrivateChatIdentityReturn(target: ObservedPrivateChatIdentityTarget): Boolean = false

    fun cancelObservedPrivateChatIdentityCapture(target: ObservedPrivateChatIdentityTarget) { }

    /** Delivers transient event evidence; persists redacted metadata only while a collection run is active. */
    fun recordAccessibilityEvent(event: AccessibilityEventEnvelope) { }

    /** Finalizes the current run and returns the exported ZIP path when successful. */
    fun finishSessionManagement(): String?

    /** Stores the visible session rows and captures their screen state before navigation. */
    fun captureSessionListSample(snapshot: ChatPageSnapshot, onComplete: (String?) -> Unit)

    /**
     * Optional metadata-only capture for an entirely verified viewport. It must not
     * request a screenshot/overlay lease or start a deferred UI operation. A false
     * result preserves the existing full capture path for unsupported adapters.
     */
    fun persistVerifiedSessionListMetadata(snapshot: ChatPageSnapshot): Boolean = false

    /** Returns safe current screen bounds only when the exact row still matches the snapshot. */
    fun resolveSessionTapBounds(
        snapshot: ChatPageSnapshot,
        candidate: ChatListCandidateSnapshot
    ): ScreenRect?

    /** Returns a fresh, row-bound fire-feature target when this row exposes one. */
    fun resolveFireTapBounds(
        snapshot: ChatPageSnapshot,
        candidate: ChatListCandidateSnapshot
    ): ScreenRect? = null

    /**
     * Returns a fresh list-swipe region that excludes any known floating touch overlays.
     * The default preserves existing adapters; platform adapters should reacquire live bounds.
     */
    fun resolveSessionListSwipeViewport(snapshot: ChatPageSnapshot): ScreenRect? =
        snapshot.chatListViewport

    /** Persists the validated, read-only message batch and its associated OCR/sample metadata. */
    fun persistChatSample(
        snapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        sessionName: String?
    ): Int

    /** Persists messages under a contact only when the selected inbox row has verified identity. */
    fun persistChatSample(
        snapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        sessionName: String?,
        contactIdentity: ContactRowIdentity?
    ): Int = persistChatSample(snapshot, batch, sessionName)

    /** Persists redacted header-node evidence even when identity validation rejects the page. */
    fun persistChatHeaderValidationDiagnostic(
        snapshot: ChatPageSnapshot,
        expectedSessionName: String?,
        matched: Boolean,
        expectedCandidate: ChatListCandidateSnapshot? = null
    ) { }

    /** Permanently revokes AI-input eligibility for a run-local display-name identity key. */
    fun blockSessionIdentityKey(sessionKey: String) { }

    /** True when this run has quarantined a name-only key after duplicate or visual-conflict evidence. */
    fun isSessionIdentityBlocked(sessionKey: String): Boolean = false

    /** Uses Android's global Back action; it does not tap a coordinate or operate chat controls. */
    fun navigateBackToSessionList(): Boolean
}
