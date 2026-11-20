package com.freedom.features.accountbrowse

/**
 * Mutable command port for AccountBrowse session updates.
 *
 * The port is not the state container: every setter submits an update to the Runtime-owned
 * [AccountBrowseSessionStateOwner], which replaces an immutable snapshot atomically. Adapters
 * can request state changes but cannot retain or mutate the owned snapshot itself.
 */
interface AccountBrowseSessionStatePort {
    var pauseReason: String
    var lastDynamicTabWaitReason: String
    var dynamicTabWaitStartedAt: Long
    var deadlineAt: Long
    var sourceFingerprint: String
    var detailFingerprint: String
    var detailActivityClass: String
    var lastStableFingerprint: String
    var stableSamples: Int
    var touchActive: Boolean
    var manualResumeAllowed: Boolean
    var manualRecoveryInProgress: Boolean
    var manualRecoveryAttemptActive: Boolean
    var touchStartedDuringGesture: Boolean
    var touchEndedDuringGesture: Boolean
    var gestureInFlight: Boolean
    var scrollEventSeen: Boolean
    var scrollEventCount: Int
    var lastScrollEventAt: Long
    var swipeCount: Int
    var fallbackScrollAttempted: Boolean
    var sourceSnapshot: String
    var returnedStableSamples: Int
    var lastReturnedSnapshot: String
    var returnMismatchLogged: Boolean
    var countdownRemaining: Int
    var overlayPassthroughActive: Boolean
    var currentActivityClass: String
    var imageBrowserReturnActivityClass: String
    var imageBrowserActive: Boolean
    var detailDwellDueAt: Long
    var detailDwellStartedAt: Long
    var detailDwellRemainingMs: Long
    var ignoreManualEventsUntil: Long
    var programmaticActionLabel: String
    var safeCardRecheckCount: Int
    var lastGlobalTouchEventAt: Long
    var lastGlobalTouchEventName: String
    var lastGlobalTouchEventOrigin: String
    var lastTargetInteractionAt: Long
    var lastTargetInteractionCategory: String
    var lastTargetInteractionDescription: String
    var lastProgrammaticScrollAt: Long
    var lastProgrammaticScrollLabel: String
    var pausedContext: ManualResumeContext?
    var navigationActionKey: String
    var navigationBeforeActivity: String
    var navigationBeforeFingerprint: String
    var navigationActionDeadlineAt: Long
    var navigationActionAttempts: Int
    var recoveryBackAttempts: Int
    var recoveryHomeClickAttempts: Int
    var recoveryDynamicClickAttempts: Int
    var dynamicTabClickConfirmed: Boolean
    var feedRefreshRecoveryActive: Boolean
    var feedRefreshStartupReady: Boolean
    var feedRefreshAttempts: Int
    var feedRefreshBeforeSnapshot: String
    var feedRefreshLastSnapshot: String
    var feedRefreshStableSamples: Int
    var feedRefreshRecyclerContentChangeEvents: Int
    var feedRefreshActionAt: Long
    var feedRefreshVerificationDeadlineAt: Long
    var feedRefreshWaitReason: String
    var notTargetForegroundLogged: Boolean
    var rootUnavailableLogged: Boolean
    var lastPopupSignature: String
    var popupDismissPending: Boolean
    var popupDismissDeadlineAt: Long
    var popupNextAttemptAt: Long
    var popupActionAttempts: Int
    var popupExhaustedLogged: Boolean
    fun reset()
}

/** Immutable value snapshot for one AccountBrowse session. */
data class AccountBrowseSessionSnapshot(
    val pauseReason: String = "",
    val lastDynamicTabWaitReason: String = "",
    val dynamicTabWaitStartedAt: Long = 0L,
    val deadlineAt: Long = 0L,
    val sourceFingerprint: String = "",
    val detailFingerprint: String = "",
    val detailActivityClass: String = "",
    val lastStableFingerprint: String = "",
    val stableSamples: Int = 0,
    val touchActive: Boolean = false,
    val manualResumeAllowed: Boolean = false,
    val manualRecoveryInProgress: Boolean = false,
    val manualRecoveryAttemptActive: Boolean = false,
    val touchStartedDuringGesture: Boolean = false,
    val touchEndedDuringGesture: Boolean = false,
    val gestureInFlight: Boolean = false,
    val scrollEventSeen: Boolean = false,
    val scrollEventCount: Int = 0,
    val lastScrollEventAt: Long = 0L,
    val swipeCount: Int = 0,
    val fallbackScrollAttempted: Boolean = false,
    val sourceSnapshot: String = "",
    val returnedStableSamples: Int = 0,
    val lastReturnedSnapshot: String = "",
    val returnMismatchLogged: Boolean = false,
    val countdownRemaining: Int = 0,
    val overlayPassthroughActive: Boolean = false,
    val currentActivityClass: String = "",
    val imageBrowserReturnActivityClass: String = "",
    val imageBrowserActive: Boolean = false,
    val detailDwellDueAt: Long = 0L,
    val detailDwellStartedAt: Long = 0L,
    val detailDwellRemainingMs: Long = 0L,
    val ignoreManualEventsUntil: Long = 0L,
    val programmaticActionLabel: String = "",
    val safeCardRecheckCount: Int = 0,
    val lastGlobalTouchEventAt: Long = 0L,
    val lastGlobalTouchEventName: String = "",
    val lastGlobalTouchEventOrigin: String = "",
    val lastTargetInteractionAt: Long = 0L,
    val lastTargetInteractionCategory: String = "",
    val lastTargetInteractionDescription: String = "",
    val lastProgrammaticScrollAt: Long = 0L,
    val lastProgrammaticScrollLabel: String = "",
    val pausedContext: ManualResumeContext? = null,
    val navigationActionKey: String = "",
    val navigationBeforeActivity: String = "",
    val navigationBeforeFingerprint: String = "",
    val navigationActionDeadlineAt: Long = 0L,
    val navigationActionAttempts: Int = 0,
    val recoveryBackAttempts: Int = 0,
    val recoveryHomeClickAttempts: Int = 0,
    val recoveryDynamicClickAttempts: Int = 0,
    val dynamicTabClickConfirmed: Boolean = false,
    val feedRefreshRecoveryActive: Boolean = false,
    val feedRefreshStartupReady: Boolean = false,
    val feedRefreshAttempts: Int = 0,
    val feedRefreshBeforeSnapshot: String = "",
    val feedRefreshLastSnapshot: String = "",
    val feedRefreshStableSamples: Int = 0,
    val feedRefreshRecyclerContentChangeEvents: Int = 0,
    val feedRefreshActionAt: Long = 0L,
    val feedRefreshVerificationDeadlineAt: Long = 0L,
    val feedRefreshWaitReason: String = "",
    val notTargetForegroundLogged: Boolean = false,
    val rootUnavailableLogged: Boolean = false,
    val lastPopupSignature: String = "",
    val popupDismissPending: Boolean = false,
    val popupDismissDeadlineAt: Long = 0L,
    val popupNextAttemptAt: Long = 0L,
    val popupActionAttempts: Int = 0,
    val popupExhaustedLogged: Boolean = false
)

/**
 * Runtime-internal storage for AccountBrowse session state.
 *
 * All updates are serialized and published as immutable snapshot replacements. The adapter-facing
 * port submits individual updates; the current snapshot is never exposed mutably.
 */
internal class AccountBrowseSessionStateOwner {
    @Volatile
    private var current: AccountBrowseSessionSnapshot = AccountBrowseSessionSnapshot()

    val port: AccountBrowseSessionStatePort = object : AccountBrowseSessionStatePort {
        override var pauseReason: String
            get() = snapshot().pauseReason
            set(value) = update { it.copy(pauseReason = value) }
        override var lastDynamicTabWaitReason: String
            get() = snapshot().lastDynamicTabWaitReason
            set(value) = update { it.copy(lastDynamicTabWaitReason = value) }
        override var dynamicTabWaitStartedAt: Long
            get() = snapshot().dynamicTabWaitStartedAt
            set(value) = update { it.copy(dynamicTabWaitStartedAt = value) }
        override var deadlineAt: Long
            get() = snapshot().deadlineAt
            set(value) = update { it.copy(deadlineAt = value) }
        override var sourceFingerprint: String
            get() = snapshot().sourceFingerprint
            set(value) = update { it.copy(sourceFingerprint = value) }
        override var detailFingerprint: String
            get() = snapshot().detailFingerprint
            set(value) = update { it.copy(detailFingerprint = value) }
        override var detailActivityClass: String
            get() = snapshot().detailActivityClass
            set(value) = update { it.copy(detailActivityClass = value) }
        override var lastStableFingerprint: String
            get() = snapshot().lastStableFingerprint
            set(value) = update { it.copy(lastStableFingerprint = value) }
        override var stableSamples: Int
            get() = snapshot().stableSamples
            set(value) = update { it.copy(stableSamples = value) }
        override var touchActive: Boolean
            get() = snapshot().touchActive
            set(value) = update { it.copy(touchActive = value) }
        override var manualResumeAllowed: Boolean
            get() = snapshot().manualResumeAllowed
            set(value) = update { it.copy(manualResumeAllowed = value) }
        override var manualRecoveryInProgress: Boolean
            get() = snapshot().manualRecoveryInProgress
            set(value) = update { it.copy(manualRecoveryInProgress = value) }
        override var manualRecoveryAttemptActive: Boolean
            get() = snapshot().manualRecoveryAttemptActive
            set(value) = update { it.copy(manualRecoveryAttemptActive = value) }
        override var touchStartedDuringGesture: Boolean
            get() = snapshot().touchStartedDuringGesture
            set(value) = update { it.copy(touchStartedDuringGesture = value) }
        override var touchEndedDuringGesture: Boolean
            get() = snapshot().touchEndedDuringGesture
            set(value) = update { it.copy(touchEndedDuringGesture = value) }
        override var gestureInFlight: Boolean
            get() = snapshot().gestureInFlight
            set(value) = update { it.copy(gestureInFlight = value) }
        override var scrollEventSeen: Boolean
            get() = snapshot().scrollEventSeen
            set(value) = update { it.copy(scrollEventSeen = value) }
        override var scrollEventCount: Int
            get() = snapshot().scrollEventCount
            set(value) = update { it.copy(scrollEventCount = value) }
        override var lastScrollEventAt: Long
            get() = snapshot().lastScrollEventAt
            set(value) = update { it.copy(lastScrollEventAt = value) }
        override var swipeCount: Int
            get() = snapshot().swipeCount
            set(value) = update { it.copy(swipeCount = value) }
        override var fallbackScrollAttempted: Boolean
            get() = snapshot().fallbackScrollAttempted
            set(value) = update { it.copy(fallbackScrollAttempted = value) }
        override var sourceSnapshot: String
            get() = snapshot().sourceSnapshot
            set(value) = update { it.copy(sourceSnapshot = value) }
        override var returnedStableSamples: Int
            get() = snapshot().returnedStableSamples
            set(value) = update { it.copy(returnedStableSamples = value) }
        override var lastReturnedSnapshot: String
            get() = snapshot().lastReturnedSnapshot
            set(value) = update { it.copy(lastReturnedSnapshot = value) }
        override var returnMismatchLogged: Boolean
            get() = snapshot().returnMismatchLogged
            set(value) = update { it.copy(returnMismatchLogged = value) }
        override var countdownRemaining: Int
            get() = snapshot().countdownRemaining
            set(value) = update { it.copy(countdownRemaining = value) }
        override var overlayPassthroughActive: Boolean
            get() = snapshot().overlayPassthroughActive
            set(value) = update { it.copy(overlayPassthroughActive = value) }
        override var currentActivityClass: String
            get() = snapshot().currentActivityClass
            set(value) = update { it.copy(currentActivityClass = value) }
        override var imageBrowserReturnActivityClass: String
            get() = snapshot().imageBrowserReturnActivityClass
            set(value) = update { it.copy(imageBrowserReturnActivityClass = value) }
        override var imageBrowserActive: Boolean
            get() = snapshot().imageBrowserActive
            set(value) = update { it.copy(imageBrowserActive = value) }
        override var detailDwellDueAt: Long
            get() = snapshot().detailDwellDueAt
            set(value) = update { it.copy(detailDwellDueAt = value) }
        override var detailDwellStartedAt: Long
            get() = snapshot().detailDwellStartedAt
            set(value) = update { it.copy(detailDwellStartedAt = value) }
        override var detailDwellRemainingMs: Long
            get() = snapshot().detailDwellRemainingMs
            set(value) = update { it.copy(detailDwellRemainingMs = value) }
        override var ignoreManualEventsUntil: Long
            get() = snapshot().ignoreManualEventsUntil
            set(value) = update { it.copy(ignoreManualEventsUntil = value) }
        override var programmaticActionLabel: String
            get() = snapshot().programmaticActionLabel
            set(value) = update { it.copy(programmaticActionLabel = value) }
        override var safeCardRecheckCount: Int
            get() = snapshot().safeCardRecheckCount
            set(value) = update { it.copy(safeCardRecheckCount = value) }
        override var lastGlobalTouchEventAt: Long
            get() = snapshot().lastGlobalTouchEventAt
            set(value) = update { it.copy(lastGlobalTouchEventAt = value) }
        override var lastGlobalTouchEventName: String
            get() = snapshot().lastGlobalTouchEventName
            set(value) = update { it.copy(lastGlobalTouchEventName = value) }
        override var lastGlobalTouchEventOrigin: String
            get() = snapshot().lastGlobalTouchEventOrigin
            set(value) = update { it.copy(lastGlobalTouchEventOrigin = value) }
        override var lastTargetInteractionAt: Long
            get() = snapshot().lastTargetInteractionAt
            set(value) = update { it.copy(lastTargetInteractionAt = value) }
        override var lastTargetInteractionCategory: String
            get() = snapshot().lastTargetInteractionCategory
            set(value) = update { it.copy(lastTargetInteractionCategory = value) }
        override var lastTargetInteractionDescription: String
            get() = snapshot().lastTargetInteractionDescription
            set(value) = update { it.copy(lastTargetInteractionDescription = value) }
        override var lastProgrammaticScrollAt: Long
            get() = snapshot().lastProgrammaticScrollAt
            set(value) = update { it.copy(lastProgrammaticScrollAt = value) }
        override var lastProgrammaticScrollLabel: String
            get() = snapshot().lastProgrammaticScrollLabel
            set(value) = update { it.copy(lastProgrammaticScrollLabel = value) }
        override var pausedContext: ManualResumeContext?
            get() = snapshot().pausedContext
            set(value) = update { it.copy(pausedContext = value) }
        override var navigationActionKey: String
            get() = snapshot().navigationActionKey
            set(value) = update { it.copy(navigationActionKey = value) }
        override var navigationBeforeActivity: String
            get() = snapshot().navigationBeforeActivity
            set(value) = update { it.copy(navigationBeforeActivity = value) }
        override var navigationBeforeFingerprint: String
            get() = snapshot().navigationBeforeFingerprint
            set(value) = update { it.copy(navigationBeforeFingerprint = value) }
        override var navigationActionDeadlineAt: Long
            get() = snapshot().navigationActionDeadlineAt
            set(value) = update { it.copy(navigationActionDeadlineAt = value) }
        override var navigationActionAttempts: Int
            get() = snapshot().navigationActionAttempts
            set(value) = update { it.copy(navigationActionAttempts = value) }
        override var recoveryBackAttempts: Int
            get() = snapshot().recoveryBackAttempts
            set(value) = update { it.copy(recoveryBackAttempts = value) }
        override var recoveryHomeClickAttempts: Int
            get() = snapshot().recoveryHomeClickAttempts
            set(value) = update { it.copy(recoveryHomeClickAttempts = value) }
        override var recoveryDynamicClickAttempts: Int
            get() = snapshot().recoveryDynamicClickAttempts
            set(value) = update { it.copy(recoveryDynamicClickAttempts = value) }
        override var dynamicTabClickConfirmed: Boolean
            get() = snapshot().dynamicTabClickConfirmed
            set(value) = update { it.copy(dynamicTabClickConfirmed = value) }
        override var feedRefreshRecoveryActive: Boolean
            get() = snapshot().feedRefreshRecoveryActive
            set(value) = update { it.copy(feedRefreshRecoveryActive = value) }
        override var feedRefreshStartupReady: Boolean
            get() = snapshot().feedRefreshStartupReady
            set(value) = update { it.copy(feedRefreshStartupReady = value) }
        override var feedRefreshAttempts: Int
            get() = snapshot().feedRefreshAttempts
            set(value) = update { it.copy(feedRefreshAttempts = value) }
        override var feedRefreshBeforeSnapshot: String
            get() = snapshot().feedRefreshBeforeSnapshot
            set(value) = update { it.copy(feedRefreshBeforeSnapshot = value) }
        override var feedRefreshLastSnapshot: String
            get() = snapshot().feedRefreshLastSnapshot
            set(value) = update { it.copy(feedRefreshLastSnapshot = value) }
        override var feedRefreshStableSamples: Int
            get() = snapshot().feedRefreshStableSamples
            set(value) = update { it.copy(feedRefreshStableSamples = value) }
        override var feedRefreshRecyclerContentChangeEvents: Int
            get() = snapshot().feedRefreshRecyclerContentChangeEvents
            set(value) = update { it.copy(feedRefreshRecyclerContentChangeEvents = value) }
        override var feedRefreshActionAt: Long
            get() = snapshot().feedRefreshActionAt
            set(value) = update { it.copy(feedRefreshActionAt = value) }
        override var feedRefreshVerificationDeadlineAt: Long
            get() = snapshot().feedRefreshVerificationDeadlineAt
            set(value) = update { it.copy(feedRefreshVerificationDeadlineAt = value) }
        override var feedRefreshWaitReason: String
            get() = snapshot().feedRefreshWaitReason
            set(value) = update { it.copy(feedRefreshWaitReason = value) }
        override var notTargetForegroundLogged: Boolean
            get() = snapshot().notTargetForegroundLogged
            set(value) = update { it.copy(notTargetForegroundLogged = value) }
        override var rootUnavailableLogged: Boolean
            get() = snapshot().rootUnavailableLogged
            set(value) = update { it.copy(rootUnavailableLogged = value) }
        override var lastPopupSignature: String
            get() = snapshot().lastPopupSignature
            set(value) = update { it.copy(lastPopupSignature = value) }
        override var popupDismissPending: Boolean
            get() = snapshot().popupDismissPending
            set(value) = update { it.copy(popupDismissPending = value) }
        override var popupDismissDeadlineAt: Long
            get() = snapshot().popupDismissDeadlineAt
            set(value) = update { it.copy(popupDismissDeadlineAt = value) }
        override var popupNextAttemptAt: Long
            get() = snapshot().popupNextAttemptAt
            set(value) = update { it.copy(popupNextAttemptAt = value) }
        override var popupActionAttempts: Int
            get() = snapshot().popupActionAttempts
            set(value) = update { it.copy(popupActionAttempts = value) }
        override var popupExhaustedLogged: Boolean
            get() = snapshot().popupExhaustedLogged
            set(value) = update { it.copy(popupExhaustedLogged = value) }

        override fun reset() = this@AccountBrowseSessionStateOwner.reset()
    }

    fun snapshot(): AccountBrowseSessionSnapshot = current

    @Synchronized
    fun update(transform: (AccountBrowseSessionSnapshot) -> AccountBrowseSessionSnapshot) {
        current = transform(current)
    }

    @Synchronized
    fun reset() {
        // Preserve the original reset contract: overlayPassthroughActive is intentionally retained
        // until the WindowManager cleanup callback changes it.
        current = current.copy(
            pauseReason = "",
            lastDynamicTabWaitReason = "",
            dynamicTabWaitStartedAt = 0L,
            deadlineAt = 0L,
            sourceFingerprint = "",
            sourceSnapshot = "",
            returnedStableSamples = 0,
            lastReturnedSnapshot = "",
            returnMismatchLogged = false,
            detailFingerprint = "",
            detailActivityClass = "",
            lastStableFingerprint = "",
            stableSamples = 0,
            lastPopupSignature = "",
            popupDismissPending = false,
            popupDismissDeadlineAt = 0L,
            popupNextAttemptAt = 0L,
            popupActionAttempts = 0,
            popupExhaustedLogged = false,
            touchActive = false,
            manualResumeAllowed = false,
            manualRecoveryInProgress = false,
            manualRecoveryAttemptActive = false,
            touchStartedDuringGesture = false,
            touchEndedDuringGesture = false,
            gestureInFlight = false,
            scrollEventSeen = false,
            scrollEventCount = 0,
            lastScrollEventAt = 0L,
            swipeCount = 0,
            fallbackScrollAttempted = false,
            countdownRemaining = 0,
            currentActivityClass = "",
            imageBrowserReturnActivityClass = "",
            imageBrowserActive = false,
            detailDwellDueAt = 0L,
            detailDwellStartedAt = 0L,
            detailDwellRemainingMs = 0L,
            ignoreManualEventsUntil = 0L,
            programmaticActionLabel = "",
            safeCardRecheckCount = 0,
            lastGlobalTouchEventAt = 0L,
            lastGlobalTouchEventName = "",
            lastGlobalTouchEventOrigin = "",
            lastTargetInteractionAt = 0L,
            lastTargetInteractionCategory = "",
            lastTargetInteractionDescription = "",
            lastProgrammaticScrollAt = 0L,
            lastProgrammaticScrollLabel = "",
            pausedContext = null,
            navigationActionKey = "",
            navigationBeforeActivity = "",
            navigationBeforeFingerprint = "",
            navigationActionDeadlineAt = 0L,
            navigationActionAttempts = 0,
            recoveryBackAttempts = 0,
            recoveryHomeClickAttempts = 0,
            recoveryDynamicClickAttempts = 0,
            dynamicTabClickConfirmed = false,
            feedRefreshRecoveryActive = false,
            feedRefreshStartupReady = false,
            feedRefreshAttempts = 0,
            feedRefreshBeforeSnapshot = "",
            feedRefreshLastSnapshot = "",
            feedRefreshStableSamples = 0,
            feedRefreshRecyclerContentChangeEvents = 0,
            feedRefreshActionAt = 0L,
            feedRefreshVerificationDeadlineAt = 0L,
            feedRefreshWaitReason = "",
            notTargetForegroundLogged = false,
            rootUnavailableLogged = false
        )
    }
}
