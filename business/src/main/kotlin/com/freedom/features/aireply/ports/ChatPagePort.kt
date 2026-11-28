package com.freedom.features.aireply.ports

import com.freedom.features.conversation.domain.contact.ContactRowIdentity

/**
 * Implemented by the active social-app adapter; AIReply never imports a Momo class and never
 * receives the raw accessibility tree. Message-body access belongs to the separate, bounded
 * ChatMessagePort and is delivered only as an ephemeral in-memory batch.
 */
enum class ReceivedGreetingsTraversalStatus {
    /** The adapter is waiting for an asynchronous Activity transition or a stable list page. */
    NAVIGATING,
    /** The adapter is currently scanning the confirmed received-greetings list. */
    SCANNING,
    /** The adapter confirmed that the received-greetings list has reached its end. */
    COMPLETE,
    /** The current page is a greeting profile card; row-level navigation is not enabled. */
    PROFILE_CARD,
    /** A specific unread greeting opened its verified private-chat detail for read-only OCR. */
    DETAIL_READY,
    /** A complete bidirectional pass of the ordinary Message list found no received-greetings label. */
    ENTRY_NOT_FOUND,
    /** The adapter cannot prove the current received-greetings page or traversal state. */
    UNKNOWN
}

/** One completed-list BACK step; acceptance is never evidence of inbox arrival. */
enum class ReceivedGreetingsBackResult {
    BACK_SUBMITTED,
    GESTURE_IN_FLIGHT,
    WAITING_FOR_PAGE_CONFIRMATION,
    SOURCE_UNVERIFIED,
    REJECTED,
    OWNERSHIP_REJECTED
}

/** Result of observing a page the user may be opening manually as the Received Greetings list. */
enum class ReceivedGreetingsPageArrivalStatus {
    /** Momo is open, but the user has not reached the Received Greetings surface yet. */
    WAITING_FOR_USER_NAVIGATION,
    /** An intermediate Activity in the Received Greetings navigation path is visible. */
    INTERMEDIATE_PAGE,
    /** The existing Received Greetings fingerprint matched; stable confirmation is pending. */
    LIST_STABILIZING,
    /** Complete list evidence was confirmed by spaced snapshots from the same window. */
    LIST_CONFIRMED,
    /** The active page or its structural evidence could not be safely classified. */
    UNKNOWN,
    /** The active page is unrelated to manual Received Greetings navigation. */
    NOT_TARGET
}

/** Gesture callback evidence is not page proof or a UI lease. */
enum class MessageWorkAreaNavigationOutcome {
    COMPLETED,
    DEFERRED_NOT_DISPATCHED,
    UNCONFIRMED
}

interface ChatPagePort {
    /** Semantic page-boundary check; concrete Activity identifiers stay in the adapter. */
    fun isReceivedGreetingsListActivity(activityClass: String): Boolean = false
    /** Returns whether the active social adapter owns the supplied selected package. */
    fun supportsTargetPackage(packageName: String): Boolean

    fun inspectChatPage(): ChatPageSnapshot

    /**
     * Verifies and enters the selected social app's message work area before AI scheduling.
     * Implementations must use a freshly verified, app-specific navigation target; a default
     * no-op keeps platform-neutral test adapters and unsupported social adapters compatible.
     */
    fun ensureMessageWorkArea(): Boolean = true

    /**
     * Optional completion signal for a submitted Message-tab gesture.
     * The overload keeps existing adapters compatible; only a concrete adapter can report
     * an actual dispatch outcome. A missing callback must never be assumed completed.
     */
    fun ensureMessageWorkArea(onGestureOutcome: (MessageWorkAreaNavigationOutcome) -> Unit): Boolean =
        ensureMessageWorkArea()

    /**
     * True when the adapter exposes a stable Message-tab anchor and the runtime can verify the
     * resulting inbox viewport against that anchor using fresh page snapshots. Adapters without
     * this evidence retain the legacy entry contract and must not claim positional verification.
     */
    fun supportsVerifiedMessageWorkAreaNavigation(): Boolean = false

    /** True only for an explicitly recognized Momo page from which one global Back is safe. */
    fun canSafelyNavigateBackToSessionList(snapshot: ChatPageSnapshot): Boolean =
        snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE

    fun refreshKnownChatRegions(previous: ChatPageSnapshot): ChatRegionSnapshot

    /**
     * Indicates that the active social adapter recognizes the received-greetings entry/page.
     * The scheduler invokes this only after the ordinary-chat phase explicitly yields to that branch.
     */
    fun supportsReceivedGreetingsIdleWorkflow(): Boolean = false

    /** Capability checks used before the received-greetings UI-operation lease is claimed. */
    fun supportsReceivedGreetingsEntry(): Boolean = supportsReceivedGreetingsIdleWorkflow()
    fun supportsReceivedGreetingsPageRecognition(): Boolean = supportsReceivedGreetingsIdleWorkflow()
    fun supportsReceivedGreetingsTraversal(): Boolean = supportsReceivedGreetingsIdleWorkflow()

    /** Resets adapter-owned entry-search state before a new received-greetings navigation task. */
    fun beginReceivedGreetingsNavigationTask() = Unit

    /** Performs one bounded, page-verified received-greetings entry/inspection step. */
    fun performReceivedGreetingsIdleStep(): Boolean = false

    /**
     * Observes a user-opened received-greetings page without navigation, clicking or scrolling.
     * Implementations must verify the exact page identity before extracting any text.
     */
    fun observeReceivedGreetingsPage(): Boolean = false

    /** Fresh page-specific fingerprint; null keeps received-greetings recovery paused. */
    fun receivedGreetingsRecoveryFingerprint(): String? = null

    /** Reloads the durable received-greetings checkpoint after an AccessibilityService reconnect. */
    fun reloadReceivedGreetingsCheckpointAfterServiceReconnect(): Boolean = false

    /** Persists the current received-greetings traversal before a cross-business page switch. */
    fun persistReceivedGreetingsCheckpoint() = Unit

    /** True when a process-recreated adapter has a non-expired received-greetings checkpoint. */
    fun hasRestorableReceivedGreetingsCheckpoint(): Boolean = false

    /** Observes user navigation toward the greetings list without clicking or scrolling. */
    fun observeManuallyOpenedReceivedGreetingsPage(): ReceivedGreetingsPageArrivalStatus =
        ReceivedGreetingsPageArrivalStatus.NOT_TARGET

    /** Starts read-only traversal from an already confirmed, manually opened greetings list. */
    fun beginReceivedGreetingsTaskFromCurrentList(): Boolean = false

    /**
     * Synchronizes adapter-owned list confirmation after the shared page-recovery route has
     * already verified the destination with two stable snapshots.
     */
    fun synchronizeReceivedGreetingsRecoveryConfirmation(): Boolean = false

    /** Current read-only traversal status for the received-greetings branch. */
    fun receivedGreetingsTraversalStatus(): ReceivedGreetingsTraversalStatus =
        ReceivedGreetingsTraversalStatus.UNKNOWN

    /** True only when a verified list boundary is waiting for changed safe-click evidence. */
    fun isReceivedGreetingsWaitingForSafeTargetEvidence(): Boolean = false

    /**
     * Read-only proof that the *current* page is the identity profile belonging to an
     * in-progress received-greetings avatar transaction. False for overlays, unrelated
     * pages, stale windows and adapters without this exact structural evidence.
     *
     * This is an intermediate business-page check, NOT a recovery-route fingerprint or
     * permission to navigate/back; the adapter's existing gesture safety gate still applies.
     */
    fun isReceivedGreetingsIdentityProfileNavigationActive(snapshot: ChatPageSnapshot): Boolean = false

    /**
     * Read-only projection of an existing greeting navigation stage and its original deadline.
     * True means only that an unclassified target-app observation may be retried; it does not
     * identify the destination, renew the deadline, grant a lease, or authorize any UI action.
     */
    fun isReceivedGreetingsNavigationAwaitingPageEvidence(snapshot: ChatPageSnapshot): Boolean = false

    /**
     * Read-only evidence from the existing greeting-detail BACK transaction.
     * The source must match the accepted BACK window. This does not confirm the destination
     * or advance the cursor; the original list fingerprint and double-snapshot gate do that.
     */
    fun isReceivedGreetingsDetailReturnNavigationActive(source: ChatPageSnapshot): Boolean = false

    /** Display name captured from the exact unread greeting row currently being inspected. */
    fun receivedGreetingsCurrentDisplayName(): String? = null

    /** Verified platform identity captured from the avatar profile; raw platform IDs are never exposed. */
    fun receivedGreetingsCurrentContactIdentity(): ContactRowIdentity? = null

    /** Hashed fingerprint of the exact greeting row currently being inspected. */
    fun receivedGreetingsCurrentRowKeyHash(): String? = null

    /** Identity parsing result for the currently selected greeting; contains no raw platform ID. */
    fun receivedGreetingsCurrentIdentityStatus(): String? = null

    /** Durably records the final confirmed greeting send before a safe BACK is permitted. */
    fun persistReceivedGreetingConfirmedSend(confirmationId: String): Boolean = false

    /** Current-run confirmed sends whose list return was independently verified and persisted.
     * The adapter holds subsequent row actions until this acknowledgement is consumed.
     */
    fun consumeReceivedGreetingsVerifiedReturnReceipts(): List<String> = emptyList()

    /** Requests a safe BACK after confirmed sending; list return must be independently verified. */
    fun completeReceivedGreetingDetailInspection(success: Boolean): Boolean = false

    /**
     * Defers a verified, unsent greeting after a model-only failure. Must revalidate
     * the current detail and use the existing safe BACK transaction. This never
     * records a send or marks the source row processed.
     */
    fun deferReceivedGreetingAfterModelFailure(): Boolean = false

    /** Stable unreadable visual content; retains the original row without a send receipt. */
    fun deferReceivedGreetingAfterUnreadableContent(): Boolean = false

    /** Records a manually returned, still-unconfirmed greeting row and releases detail state. */
    fun deferReceivedGreetingAfterManualReturn(): Boolean = false

    /** One lease-gated safe list BACK step from independently verified source evidence. */
    fun returnFromReceivedGreetingsForPriorityCheck(): ReceivedGreetingsBackResult =
        ReceivedGreetingsBackResult.REJECTED

    /** Read-only proof of the intermediate greeting card reached by a submitted list BACK. */
    fun isReceivedGreetingsCompletionReturnIntermediate(): Boolean = false

    /** Current verified list/card source for recovery of the task-handoff BACK transaction. */
    fun receivedGreetingsCompletionReturnSourceFingerprint(): String? = null

    /** Breaks stale retry samples on a missing window or user interaction; retains submission state. */
    fun invalidateReceivedGreetingsCompletionReturnEvidence() {}

    /** Retires transient return state only after inbox confirmation; a partial scan keeps its checkpoint. */
    fun confirmReceivedGreetingsCompletionReturn(preserveTraversal: Boolean = false): Boolean = !preserveTraversal
}
