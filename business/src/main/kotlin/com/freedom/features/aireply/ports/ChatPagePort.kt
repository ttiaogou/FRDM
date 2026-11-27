package com.freedom.features.aireply.ports

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.conversation.domain.contact.ContactRowIdentity

/** Generic chat-page classification; provider-specific names stay inside social adapters. */
enum class ChatPageKind {
    UNKNOWN,
    NON_CHAT,
    CHAT_LIST,
    CHAT_DETAIL_CANDIDATE
}


/** Source of a short-lived text value obtained from a conversation-list accessibility node. */
enum class ChatNodeTextSource {
    NODE_TEXT,
    CONTENT_DESCRIPTION,
    NONE
}

/** Unread state is tri-valued; provider-specific, container-scoped contracts may confirm READ on absence. */
enum class ChatUnreadStatus {
    UNREAD,
    READ,
    UNKNOWN
}

/** Independent Momo fire-feature state; fire presence alone is not unread evidence. */
enum class ChatFireStatus {
    NONE,
    FIRE_PRESENT,
    FIRE_UNREAD,
    FIRE_UNKNOWN
}

/** Redacted source category for the unread state; never stores the raw accessibility label. */
enum class ChatUnreadEvidenceSource {
    ACCESSIBILITY_LABEL,
    SEMANTIC_VIEW_ID,
    COMBINED,
    CONFLICTING,
    STATUS_BADGE_ABSENT,
    STATUS_BADGE_EMPTY,
    SYSTEM_ENTRY_STATUS_POINT,
    NONE
}

/** Redacted counts for the Momo status_new node family; never contains badge text. */
data class ChatListUnreadDiagnostics(
    val statusNewNodeCount: Int = 0,
    val statusNewUnreadCount: Int = 0,
    val statusNewReadCount: Int = 0,
    val statusNewUnknownCount: Int = 0,
    val statusNewInvalidBoundsCount: Int = 0,
    val statusNewOutsideRowCount: Int = 0,
    val statusNewEmptyValueCount: Int = 0,
    val statusNewNonNumericValueCount: Int = 0,
    val statusNewNotVisibleCount: Int = 0,
    val statusNewAbsentReadCount: Int = 0,
    val statusPointConfirmedSystemUnreadCount: Int = 0
) {
    operator fun plus(other: ChatListUnreadDiagnostics) = ChatListUnreadDiagnostics(
        statusNewNodeCount = statusNewNodeCount + other.statusNewNodeCount,
        statusNewUnreadCount = statusNewUnreadCount + other.statusNewUnreadCount,
        statusNewReadCount = statusNewReadCount + other.statusNewReadCount,
        statusNewUnknownCount = statusNewUnknownCount + other.statusNewUnknownCount,
        statusNewInvalidBoundsCount = statusNewInvalidBoundsCount + other.statusNewInvalidBoundsCount,
        statusNewOutsideRowCount = statusNewOutsideRowCount + other.statusNewOutsideRowCount,
        statusNewEmptyValueCount = statusNewEmptyValueCount + other.statusNewEmptyValueCount,
        statusNewNonNumericValueCount = statusNewNonNumericValueCount + other.statusNewNonNumericValueCount,
        statusNewNotVisibleCount = statusNewNotVisibleCount + other.statusNewNotVisibleCount,
        statusNewAbsentReadCount = statusNewAbsentReadCount + other.statusNewAbsentReadCount,
        statusPointConfirmedSystemUnreadCount = statusPointConfirmedSystemUnreadCount +
            other.statusPointConfirmedSystemUnreadCount
    )
}

/**
 * Redacted per-node evidence behind a row's READ/UNREAD/UNKNOWN state.
 * Raw node text/contentDescription are deliberately excluded; geometry and resource ID
 * are retained so device samples can be audited without persisting message text.
 */
data class ChatListUnreadNodeEvidence(
    val viewIdResourceName: String,
    val status: ChatUnreadStatus,
    val count: Int?,
    val source: ChatUnreadEvidenceSource,
    val bounds: ScreenRect,
    val boundsValid: Boolean,
    val intersectsRow: Boolean,
    val containedInRow: Boolean,
    val visibleToUser: Boolean,
    val statusNewDisposition: String = "NONE"
)

/**
 * One in-memory conversation-row candidate. Plaintext is intentionally excluded from toString()
 * and from the page observation key; the row fingerprint contains structural evidence plus
 * redacted text-source, length, and hash metadata. Callers must not write the in-memory values
 * to ordinary logs.
 * Node paths are scoped to rowTarget.window and must be reacquired/revalidated before interaction.
 */
class ChatListCandidateSnapshot(
    val rowIndex: Int,
    val rowTarget: NodeTarget,
    val rowBounds: ScreenRect,
    val rowVisibleToUser: Boolean,
    val intersectsViewport: Boolean,
    val fullyInsideViewport: Boolean,
    val rowBoundsValid: Boolean,
    val avatarBounds: ScreenRect?,
    val avatarPath: NodePath?,
    val nameText: String?,
    val nameSource: ChatNodeTextSource,
    val nameNodePath: NodePath?,
    val nameBounds: ScreenRect?,
    val nameNodeVisibleToUser: Boolean,
    val nameBoundsIntersectViewport: Boolean,
    val nameBoundsFullyInsideViewport: Boolean,
    val nameBoundsValid: Boolean,
    val previewText: String?,
    val previewSource: ChatNodeTextSource,
    val previewNodePath: NodePath?,
    val previewBounds: ScreenRect?,
    val previewNodeVisibleToUser: Boolean,
    val previewBoundsIntersectViewport: Boolean,
    val previewBoundsFullyInsideViewport: Boolean,
    val previewBoundsValid: Boolean,
    val timestampText: String?,
    val timestampSource: ChatNodeTextSource,
    val timestampNodePath: NodePath?,
    val timestampBounds: ScreenRect?,
    val timestampNodeVisibleToUser: Boolean,
    /** Provider adapter classification; only PRIVATE_CHAT rows may be selected for navigation. */
    val selectionClass: String = "PRIVATE_CHAT",
    /** Redacted reason code explaining why the row is or is not selectable. */
    val selectionReason: String = "LEGACY_DEFAULT",
    /** Unknown is retained when the app exposes no reliable unread marker in accessibility. */
    val unreadStatus: ChatUnreadStatus = ChatUnreadStatus.UNKNOWN,
    /** Exact count when the accessibility badge exposes one; null for dots, labels or 99+. */
    val unreadCount: Int? = null,
    /** Redacted source category supporting unreadStatus. */
    val unreadEvidenceSource: ChatUnreadEvidenceSource = ChatUnreadEvidenceSource.NONE,
    /** Fire-feature state is tracked independently from ordinary chat unread state. */
    val fireStatus: ChatFireStatus = ChatFireStatus.NONE,
    /** Count from a reliable unread badge on the same row as the fire feature, when present. */
    val fireUnreadCount: Int? = null,
    /** Short-lived fire-feature node locator/bounds bound to this exact row snapshot. */
    val fireNodePath: NodePath? = null,
    val fireBounds: ScreenRect? = null,
    /** Redacted badge-node observations supporting the tri-state unread result. */
    val unreadNodeEvidence: List<ChatListUnreadNodeEvidence> = emptyList(),
    /** Whether this row contained the exact Momo status_new node, even if it was empty/hidden. */
    val statusNewNodePresent: Boolean = false,
    /** Number of exact status_new nodes observed in this row; duplicates remain diagnostic. */
    val statusNewNodeObservationCount: Int = 0,
    /** Momo image status-point observations are diagnostic unless a row-specific contract confirms a system-entry dot. */
    val statusPointNodeObservationCount: Int = 0,
    val statusPointVisibleNodeCount: Int = 0,
    val statusPointValidBoundsNodeCount: Int = 0,
    val statusPointContainedInRowNodeCount: Int = 0,
    /** Read-only evidence that Android clipped the row to the vertical viewport edge.
     * Not a page fingerprint, contact identity or coordinate execution input. */
    val rowClippedAtViewportEdge: Boolean = false
) {
    /** Assigned by the provider adapter for this exact page snapshot; never reused across refreshes. */
    var contactIdentity: ContactRowIdentity? = null

    val hasReadableName: Boolean get() = !nameText.isNullOrBlank()
    val hasReadablePreview: Boolean get() = !previewText.isNullOrBlank()
    val hasAvatar: Boolean get() = avatarPath != null

    fun structuralKey(): String = listOf(
        rowIndex, rowTarget.path.childIndices.joinToString("/"),
        "${rowTarget.window.packageName}:${rowTarget.window.activityClass}:${rowTarget.window.generation}",
        rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom,
        rowVisibleToUser, intersectsViewport, fullyInsideViewport, rowBoundsValid,
        hasAvatar, hasReadableName, nameSource.name, nameNodeVisibleToUser,
        nameBoundsIntersectViewport, nameBoundsFullyInsideViewport, nameBoundsValid,
        nameBounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty(),
        nameText?.length ?: 0, nameText?.hashCode() ?: 0,
        hasReadablePreview, previewSource.name, previewNodeVisibleToUser,
        previewBoundsIntersectViewport, previewBoundsFullyInsideViewport, previewBoundsValid,
        previewBounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty(),
        previewText?.length ?: 0, previewText?.hashCode() ?: 0,
        timestampText.isNullOrBlank().not(), timestampSource.name,
        timestampText?.length ?: 0, timestampText?.hashCode() ?: 0,
        selectionClass, selectionReason,
        unreadStatus.name, unreadCount, unreadEvidenceSource.name,
        fireStatus.name, fireUnreadCount,
        fireNodePath?.childIndices?.joinToString("/").orEmpty(),
        fireBounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty(),
        statusNewNodePresent, statusNewNodeObservationCount,
        statusPointNodeObservationCount, statusPointVisibleNodeCount,
        statusPointValidBoundsNodeCount, statusPointContainedInRowNodeCount,
        unreadNodeEvidence.joinToString(",") { evidence ->
            listOf(
                evidence.viewIdResourceName, evidence.status.name, evidence.count,
                evidence.source.name, evidence.bounds.left, evidence.bounds.top,
                evidence.bounds.right, evidence.bounds.bottom, evidence.boundsValid,
                evidence.intersectsRow, evidence.containedInRow, evidence.visibleToUser,
                evidence.statusNewDisposition
            ).joinToString("/")
        }
    ).joinToString(":")

    override fun toString(): String =
        "ChatListCandidateSnapshot(rowIndex=$rowIndex, hasReadableName=$hasReadableName, " +
            "hasReadablePreview=$hasReadablePreview, hasAvatar=$hasAvatar, " +
            "rowVisibleToUser=$rowVisibleToUser, intersectsViewport=$intersectsViewport, " +
            "fullyInsideViewport=$fullyInsideViewport, rowBoundsValid=$rowBoundsValid, " +
            "selectionClass=$selectionClass, selectionReason=$selectionReason, " +
            "unreadStatus=$unreadStatus, unreadCount=$unreadCount, " +
            "unreadEvidenceSource=$unreadEvidenceSource, fireStatus=$fireStatus, " +
            "fireUnreadCount=$fireUnreadCount)"
}

/** Redacted structural evidence from the active chat-page tree; never stores node text. */
data class ChatPageNodeEvidence(
    val path: String,
    val className: String,
    val viewId: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val childCount: Int,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isClickable: Boolean,
    val hasText: Boolean,
    val insideListCandidate: Boolean,
    val depth: Int = 0,
    val parentPath: String = "",
    val isVisibleToUser: Boolean = false,
    val isListProbeRoot: Boolean = false,
    val isAlternateListProbe: Boolean = false,
    val isMessageRowCandidate: Boolean = false,
    val textLength: Int = 0,
    val contentDescriptionLength: Int = 0,
    val boundsValid: Boolean = false,
    val isSelected: Boolean = false
)

data class ChatHeaderNodeDiagnostic(
    val nodePath: String = "",
    val found: Boolean = false,
    val textLength: Int = 0,
    val contentDescriptionLength: Int = 0,
    val isVisibleToUser: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val boundsValid: Boolean = false,
    val parentFound: Boolean = false,
    val parentClassName: String = "",
    val parentViewId: String = "",
    val parentIsVisibleToUser: Boolean = false,
    val parentBoundsValid: Boolean = false,
    val readOutcome: String = "NOT_FOUND"
)

data class SessionListNavigationTarget(
    val nodeTarget: NodeTarget,
    val bounds: ScreenRect
)

data class ChatPageSnapshot(
    val packageName: String,
    val activityClass: String,
    val pageKind: ChatPageKind,
    val scannedNodeCount: Int,
    val editableInputCount: Int,
    val messageListCount: Int,
    val sendActionCount: Int,
    val messageRelatedViewIdCount: Int,
    val hasChatTab: Boolean,
    val messageListTarget: NodeTarget?,
    val inputFieldTarget: NodeTarget?,
    val structureEvidence: List<ChatPageNodeEvidence> = emptyList(),
    val nodeTreeProbeVisitedNodeCount: Int = 0,
    val nodeTreeProbeTruncated: Boolean = false,
    val chatListViewport: ScreenRect? = null,
    /** Fresh physical display bounds used to validate tap targets outside the chat list. */
    val screenBounds: ScreenRect? = null,
    val chatListCandidates: List<ChatListCandidateSnapshot> = emptyList(),
    val chatListExtractionTruncated: Boolean = false,
    val chatListInvalidRowBoundsCount: Int = 0,
    val chatListUnreadDiagnostics: ChatListUnreadDiagnostics = ChatListUnreadDiagnostics(),
    /** Short-lived title read from the known chat header; never written to ordinary logs. */
    val chatTitleText: String? = null,
    val chatHeaderDiagnostic: ChatHeaderNodeDiagnostic? = null,
    /** Adapter-derived semantic locator for navigating to the session list from its main tab host. */
    val sessionListNavigationTarget: SessionListNavigationTarget? = null,
    /** Identity generation of the active Android window; prevents reusing anchors across windows. */
    val windowGeneration: Long = 0L,
    /** Collection positions from the same inbox observation, not child indexes or contact IDs. */
    val chatListCollectionRowCount: Int? = null,
    val chatListVisibleCollectionRows: List<Int> = emptyList(),
    val chatListCanScrollForward: Boolean? = null,
    val chatListCanScrollBackward: Boolean? = null
) {
    /** Stable physical anchor for the bottom message tab, even while another main tab is active. */
    private fun identityWindowRef() = inputFieldTarget?.window
        ?: messageListTarget?.window
        ?: sessionListNavigationTarget?.nodeTarget?.window
        // A freshly extracted inbox row is also a window-scoped accessibility target.
        // Use it only as the final identity fallback when the adapter has no page-level
        // target; windowIdentityKey() still validates package, windowId and generation.
        ?: chatListCandidates.firstOrNull()?.rowTarget?.window

    /** Window identity is package + Android window ID + generation; Activity is diagnostic only. */
    fun windowIdentityKey(): String? {
        val window = identityWindowRef() ?: return null
        if (packageName.isBlank() || window.packageName != packageName || window.windowId < 0 ||
            (windowGeneration > 0L && window.generation != windowGeneration)
        ) return null
        return "$packageName|windowId=${window.windowId}|generation=${window.generation}"
    }

    fun hasSameWindowIdentity(other: ChatPageSnapshot): Boolean {
        val first = windowIdentityKey() ?: return false
        val second = other.windowIdentityKey() ?: return false
        return first == second
    }

    fun messageTabAnchorPositionKey(): String? {
        if (!hasChatTab) return null
        val target = sessionListNavigationTarget ?: return null
        val tabBounds = target.bounds
        val display = screenBounds ?: return null
        val window = target.nodeTarget.window
        if (tabBounds.isEmpty || display.isEmpty || window.packageName != packageName ||
            window.windowId < 0
        ) return null
        return listOf(
            packageName,
            "windowId=${window.windowId}",
            "generation=${window.generation}",
            "${tabBounds.left},${tabBounds.top},${tabBounds.right},${tabBounds.bottom}",
            "${display.left},${display.top},${display.right},${display.bottom}"
        ).joinToString("|")
    }

    /**
     * Stable navigation-host fingerprint captured on either side of the bottom Message-tab tap.
     * It deliberately excludes page kind and list viewport: navigating from another bottom tab to
     * Message is expected to change both. Package, window ID/generation, tab anchor and display
     * must remain; Activity is diagnostic only.
     */
    fun messageTabContextPositionKey(): String? {
        val anchor = messageTabAnchorPositionKey() ?: return null
        val display = screenBounds ?: return null
        if (display.isEmpty) return null
        return listOf(
            packageName,
            anchor,
            "${display.left},${display.top},${display.right},${display.bottom}"
        ).joinToString("|")
    }

    /**
     * Position-only fingerprint for a confirmed message list. Volatile row content, previews,
     * timestamps and unread badges are deliberately excluded; the stable viewport remains part
     * of this key so a list that scrolls or resizes cannot pass the post-tap position check.
     */
    fun messageTabPositionKey(): String? {
        if (pageKind != ChatPageKind.CHAT_LIST || chatListViewport?.isEmpty != false) return null
        val context = messageTabContextPositionKey() ?: return null
        val viewport = chatListViewport
        return listOf(
            context,
            "${viewport.left},${viewport.top},${viewport.right},${viewport.bottom}"
        ).joinToString("|")
    }

    /**
     * Stable navigation-shell key. Activity names, row counts, child counts and message payload
     * are excluded; the window generation and fixed ancestor/component relationships remain.
     */
    fun navigationStructureKey(): String = buildString {
        append(packageName).append('|').append(windowIdentityKey().orEmpty()).append('|').append(pageKind.name)
        append('|').append(hasChatTab)
        append('|').append(screenBounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty())
        append('|').append(chatListViewport?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty())
        append('|').append(sessionListNavigationTarget?.bounds?.let {
            "${it.left},${it.top},${it.right},${it.bottom}"
        }.orEmpty())
        val stableNodes = structureEvidence.filter {
            !it.insideListCandidate || it.isListProbeRoot || it.isAlternateListProbe
        }
        stableNodes.forEach { node ->
            append("|N:").append(node.path).append(':').append(node.parentPath)
                .append(':').append(node.className).append(':').append(node.viewId)
                .append(':').append(node.left).append(',').append(node.top)
                .append(',').append(node.right).append(',').append(node.bottom)
                .append(':').append(node.isEditable).append(':').append(node.isScrollable)
                .append(':').append(node.isClickable).append(':').append(node.isVisibleToUser)
                .append(':').append(node.boundsValid).append(':').append(node.isSelected)
                .append(':').append(node.isListProbeRoot).append(':').append(node.isAlternateListProbe)
        }
    }

    /** Paths whose stable structure/geometry appeared, disappeared, or changed between snapshots. */
    fun changedNavigationNodePaths(other: ChatPageSnapshot): List<String> {
        fun stableNodes(snapshot: ChatPageSnapshot): Map<String, String> =
            snapshot.structureEvidence
                .filter { !it.insideListCandidate || it.isListProbeRoot || it.isAlternateListProbe }
                .associate { node ->
                    val signature = listOf(
                        node.parentPath, node.className, node.viewId,
                        node.left, node.top, node.right, node.bottom,
                        node.isEditable, node.isScrollable, node.isClickable,
                        node.isVisibleToUser, node.boundsValid, node.isSelected, node.isListProbeRoot,
                        node.isAlternateListProbe
                    ).joinToString(":")
                    node.path to signature
                }
        val beforeNodes = stableNodes(this)
        val afterNodes = stableNodes(other)
        return (beforeNodes.keys + afterNodes.keys).distinct().filter { path ->
            beforeNodes[path] != afterNodes[path]
        }.sorted()
    }

    /** Stable page-type identity; it deliberately excludes the current conversation's title. */
    fun structuralPageKey(): String {
        val windowKey = windowIdentityKey().orEmpty()
        return when (pageKind) {
            ChatPageKind.CHAT_DETAIL_CANDIDATE -> listOf(
                packageName,
                windowKey,
                pageKind.name,
                "chat_user_name",
                "message_iv_userphoto",
                "message_ed_msgeditor",
                "toolbar_back_imagebutton"
            ).joinToString("|")

            ChatPageKind.CHAT_LIST -> listOf(
                packageName,
                windowKey,
                pageKind.name,
                messageTabAnchorPositionKey().orEmpty(),
                chatListViewport?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty(),
                screenBounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty()
            ).joinToString("|")

            ChatPageKind.UNKNOWN, ChatPageKind.NON_CHAT -> listOf(
                packageName,
                windowKey.ifBlank { "window-unverified" },
                pageKind.name,
                // Retain diagnostic-only evidence for unclassified pages so a real transition
                // wakes the observer; this branch never authorizes a navigation action.
                activityClass,
                nodeTreeProbeTruncated,
                screenBounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty(),
                structureEvidence.filter { !it.insideListCandidate }.joinToString(";") { evidence ->
                    listOf(evidence.path, evidence.parentPath, evidence.className, evidence.viewId,
                        evidence.left, evidence.top, evidence.right, evidence.bottom,
                        evidence.isVisibleToUser, evidence.isClickable, evidence.isScrollable,
                        evidence.isSelected).joinToString(":")
                }
            ).joinToString("|")
        }
    }

    /** Redacted conversation identity used only to separate sessions inside one Activity/window. */
    fun conversationIdentityKey(): String? = chatTitleText
        ?.trim()
        ?.takeIf { it.startsWith("与") && it.endsWith("对话") }
        ?.hashCode()
        ?.toString()

    /**
     * Observation identity combines the structural page key with provider-neutral evidence.
     * Chat-list rows contribute their redacted structural fingerprints so row refreshes within
     * an unchanged viewport are observable without embedding names or previews in the key.
     * The navigationStructureKey remains row-agnostic for position validation.
     */
    fun stableKey(): String = when (pageKind) {
        ChatPageKind.CHAT_DETAIL_CANDIDATE -> listOf(
            structuralPageKey(), "conversation=${conversationIdentityKey().orEmpty()}"
        ).joinToString("|")

        ChatPageKind.CHAT_LIST -> listOf(
            structuralPageKey(),
            "rows=${chatListCandidates.joinToString(";") { it.structuralKey() }}"
        ).joinToString("|")

        ChatPageKind.UNKNOWN, ChatPageKind.NON_CHAT -> structuralPageKey()
    }

}

data class ChatRegionSnapshot(
    val packageMatches: Boolean,
    val messageRegionNodeCount: Int,
    val messageRegionTextNodeCount: Int,
    val inputRegionNodeCount: Int,
    val inputRegionEditableNodeCount: Int,
    val pathsResolved: Boolean
)

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

    /** Requests a safe BACK after confirmed sending; list return must be independently verified. */
    fun completeReceivedGreetingDetailInspection(success: Boolean): Boolean = false

    /**
     * Defers a verified, unsent greeting after a model-only failure. Must revalidate
     * the current detail and use the existing safe BACK transaction. This never
     * records a send or marks the source row processed.
     */
    fun deferReceivedGreetingAfterModelFailure(): Boolean = false

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
