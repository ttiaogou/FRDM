package com.freedom.features.aireply.ports

import com.freedom.core.accessibility.api.ScreenRect
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
 * Read-only session navigation and local sample-storage boundary.
 * Implementations must revalidate every row locator immediately before returning tap bounds.
 */
interface ChatSessionManagementPort {
    fun beginSessionManagement(): String?

    /**
     * Acquires a platform identity for a private-chat row that has no verified ID yet.
     * Implementations must use their validated profile-navigation and identity-parser path;
     * the default keeps adapters without this capability behavior-compatible.
     */
    fun requestContactIdentityCapture(
        candidate: ChatListCandidateSnapshot
    ): ContactIdentityCaptureRequestResult = ContactIdentityCaptureRequestResult.NOT_SUPPORTED

    /** Delivers transient event evidence; persists redacted metadata only while a collection run is active. */
    fun recordAccessibilityEvent(event: AccessibilityEventEnvelope) { }

    /** Finalizes the current run and returns the exported ZIP path when successful. */
    fun finishSessionManagement(): String?

    /** Stores the visible session rows and captures their screen state before navigation. */
    fun captureSessionListSample(snapshot: ChatPageSnapshot, onComplete: (String?) -> Unit)

    /** Returns safe current screen bounds only when the exact row still matches the snapshot. */
    fun resolveSessionTapBounds(
        snapshot: ChatPageSnapshot,
        candidate: ChatListCandidateSnapshot
    ): ScreenRect?

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
