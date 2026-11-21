package com.freedom.features.accountbrowse

import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.accountbrowse.ports.BrowsePageEvidence
import com.freedom.features.accountbrowse.ports.BrowsePageKind
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpoint
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpointRepository
import com.freedom.features.recovery.RecoveryTransactionState
import com.freedom.features.recovery.RecoveryScheduleRepository
import com.freedom.features.recovery.RecoveryCheckpointAdapterContext

/**
 * Coordinates the automatic-browse phase dispatch independently from Android Service APIs.
 *
 * The controller owns the current phase and the per-session evidence state.
 * Platform operations are supplied by the AccountBrowse Android execution adapter.
 */
class AccountBrowseRuntime(
    scheduler: TaskScheduler,
    private val canScheduleStep: () -> Boolean,
    private val isSessionEnabled: () -> Boolean,
    private val isTouchActive: () -> Boolean,
    private val targetPackage: () -> String,
    private val checkpointRepository: AccountBrowseCheckpointRepository? = null,
    private val onResetSession: () -> Unit,
    private val actions: Actions,
    private val currentRunGeneration: () -> Long = { 0L },
    private val interactionPolicy: AccountBrowseInteractionPolicy = AccountBrowseInteractionPolicy(),
    private val onReadOnlyRecoveryObservation: () -> Unit = {},
    private val onLog: (String) -> Unit = {},
    recoveryScheduleRepository: RecoveryScheduleRepository? = null
) {
    data class Actions(
        val waitForDynamicTab: () -> Unit,
        val verifyNavigationAction: () -> Unit,
        val refreshDynamicFeed: () -> Unit,
        val verifyDynamicFeedRefresh: () -> Unit,
        val waitForFeedStable: () -> Unit,
        val runFirstSwipeCountdown: () -> Unit,
        val performFeedScroll: () -> Unit,
        val verifyInitialSwipe: () -> Unit,
        val openVisibleFeedCard: () -> Unit,
        val checkDetailPageLoaded: () -> Unit,
        val returnFromDetailPage: () -> Unit,
        val waitForImageBrowserReturn: () -> Unit,
        val waitForDetailRestabilize: () -> Unit,
        val checkFeedListReturned: () -> Unit
    )

    private val stepScheduler = AccountBrowseScheduler(scheduler, canScheduleStep) { performStep() }
    private val dynamicPageRecovery = DynamicPageRecovery(
        scheduler = scheduler,
        targetPackage = targetPackage,
        currentRunGeneration = currentRunGeneration,
        onLog = onLog,
        onReadOnlyObservation = onReadOnlyRecoveryObservation,
        scheduleRepository = recoveryScheduleRepository
    )

    fun scheduleStep(delayMs: Long) = stepScheduler.schedule(delayMs)

    fun cancelScheduledStep() = stepScheduler.cancel()

    /**
     * AccountBrowse Runtime owns the session snapshot. The exposed port is a command surface whose
     * setters atomically replace the private immutable snapshot; adapters never receive storage.
     */
    private val sessionStateOwner = AccountBrowseSessionStateOwner()
    val session: AccountBrowseSessionStatePort = sessionStateOwner.port
    private var restoredCheckpointPhase: AccountBrowsePhase? = null
    private var restoredBusinessCheckpoint: AccountBrowseCheckpoint? = null
    private var verifiedFeedAwaitingPhaseResume: String? = null
    private val checkpointAdapter = AccountBrowseRecoveryCheckpointAdapter()

    private val stateMachine = AccountBrowseStateMachine()
    val phase: AccountBrowsePhase
        get() = stateMachine.phase

    init {
        checkpointRepository?.load()?.let { checkpoint ->
            restoredBusinessCheckpoint = checkpoint
            restoredCheckpointPhase = runCatching { AccountBrowsePhase.valueOf(checkpoint.phase) }
                .getOrDefault(AccountBrowsePhase.PAUSED)
            // A restored checkpoint is never executable immediately. The workflow must first
            // re-observe the page and satisfy the shared two-sample verification gate.
            stateMachine.transitionTo(AccountBrowsePhase.PAUSED)
            dynamicPageRecovery.restoreFromCheckpoint(checkpoint.savedAtEpochMs)
            sessionStateOwner.update { current ->
                current.copy(
                    sourceFingerprint = checkpoint.sourceFingerprint,
                    detailFingerprint = checkpoint.detailFingerprint,
                    lastStableFingerprint = checkpoint.lastStableFingerprint,
                    sourceSnapshot = checkpoint.sourceSnapshot,
                    currentActivityClass = checkpoint.currentActivityClass,
                    imageBrowserActive = checkpoint.imageBrowserActive,
                    swipeCount = checkpoint.swipeCount,
                    feedRefreshRecoveryActive = checkpoint.feedRefreshRecoveryActive,
                    feedRefreshLastSnapshot = checkpoint.feedRefreshLastSnapshot
                )
            }
        }
    }

    /** Restore only when the current business run still has a valid checkpoint. */
    fun restoreAfterServiceReconnect(): Boolean {
        val checkpoint = checkpointRepository?.load() ?: return false
        restoredBusinessCheckpoint = checkpoint
        verifiedFeedAwaitingPhaseResume = null
        restoredCheckpointPhase = runCatching { AccountBrowsePhase.valueOf(checkpoint.phase) }
            .getOrDefault(AccountBrowsePhase.PAUSED)
        // Old execution state and gestures must never be resumed before fresh page verification.
        stateMachine.transitionTo(AccountBrowsePhase.PAUSED)
        dynamicPageRecovery.restoreAfterServiceReconnect()
        return true
    }

    fun suspendRecoveryOnServiceDisconnect() = dynamicPageRecovery.suspendForServiceDisconnect()

    fun hasPendingCheckpointVerification(): Boolean = restoredCheckpointPhase != null

    /** Moves a restored session into a read-only feed verification phase. */
    fun beginCheckpointVerification(): Boolean {
        if (restoredCheckpointPhase == null) return false
        transitionTo(AccountBrowsePhase.WAIT_FOR_FEED_STABLE)
        return true
    }

    /** Normalizes page-observation uncertainty before any AccountBrowse action is authorized. */
    fun observeRecoveryPage(page: BrowsePageEvidence): RecoveryTransactionState {
        return dynamicPageRecovery.observePage(page)
    }

    fun recoveryState(): RecoveryTransactionState = dynamicPageRecovery.state()

    /**
     * A reserved task slot is not authorization to dispatch a delayed UI action.
     * Recheck the existing interaction policy at the actual dispatch boundary.
     */
    fun canExecuteAutomaticStep(): Boolean = interactionPolicy.mayPerformAutomaticStep(
        sessionEnabled = isSessionEnabled(),
        touchActive = isTouchActive(),
        phase = phase,
        recoveryState = dynamicPageRecovery.state()
    )

    fun recoveryStableSamples(): Int = dynamicPageRecovery.stableSamples()

    /** Touch only changes observation cadence; it never establishes a manual-resume latch. */
    fun onUserInteractionStarted() = dynamicPageRecovery.onUserInteractionStarted()

    fun onUserInteractionEnded() = dynamicPageRecovery.onUserInteractionEnded()

    /**
     * Replaces the dynamic-feed-local stableSamples decision with the shared two-sample gate.
     * The caller still owns fingerprint extraction and all feed actions.
     */
    fun observeDynamicFeedFingerprint(
        fingerprint: String,
        page: BrowsePageEvidence? = null,
        routeVerified: Boolean = false
    ): Boolean {
        val checkpoint = restoredBusinessCheckpoint
        if (checkpoint != null) {
            // A verified route and newly inspected feed are required; stored business data
            // alone never proves that the page is ready for an action.
            if (!routeVerified || page == null ||
                page.pageKind != BrowsePageKind.DYNAMIC_FEED ||
                page.packageName != targetPackage() ||
                page.confidence < 80 ||
                !page.windowAvailable ||
                page.fingerprint.isBlank() ||
                fingerprint.isBlank()
            ) {
                dynamicPageRecovery.invalidateRestoredCheckpointEvidence()
                return false
            }
            val unified = runCatching {
                checkpointAdapter.adapt(
                    checkpoint,
                    RecoveryCheckpointAdapterContext(
                        checkpointId = "account-browse-checkpoint",
                        runGeneration = currentRunGeneration().coerceAtLeast(0L),
                        targetPackage = targetPackage(),
                        pageType = page.pageKind.name,
                        pageFingerprint = fingerprint,
                        recoveryReason = "CHECKPOINT_LOADED"
                    )
                )
            }.getOrElse {
                // Invalid persisted metadata must not bypass the checkpoint verification gate.
                dynamicPageRecovery.invalidateRestoredCheckpointEvidence()
                onLog("自动浏览统一检查点适配未通过；保留只读恢复观察，不执行自动操作")
                return false
            }
            if (!dynamicPageRecovery.observeRestoredCheckpoint(unified, page)) return false
            restoredBusinessCheckpoint = null
            restoredCheckpointPhase = null
            // Keep the existing WAIT_FOR_FEED_STABLE -> FIRST_SWIPE_COUNTDOWN transition.
            // A later, fresh feed read in that phase must still agree with this fingerprint.
            verifiedFeedAwaitingPhaseResume = fingerprint
            return true
        }

        val pendingVerifiedFingerprint = verifiedFeedAwaitingPhaseResume
        if (pendingVerifiedFingerprint != null) {
            verifiedFeedAwaitingPhaseResume = null
            if (routeVerified && page != null &&
                page.pageKind == BrowsePageKind.DYNAMIC_FEED &&
                page.packageName == targetPackage() &&
                page.confidence >= 80 && page.windowAvailable &&
                page.fingerprint.isNotBlank() &&
                fingerprint == pendingVerifiedFingerprint
            ) {
                return true
            }
            dynamicPageRecovery.rejectVerifiedFeedBeforeResume()
            return false
        }

        if (!dynamicPageRecovery.observeFingerprint(fingerprint)) return false
        restoredCheckpointPhase = null
        return true
    }

    /** The AccountBrowse workflow owns all phase transitions. */
    fun transitionTo(nextPhase: AccountBrowsePhase) {
        stateMachine.transitionTo(nextPhase)
        persistCheckpoint()
    }

    private fun persistCheckpoint() {
        val snapshot = sessionStateOwner.snapshot()
        checkpointRepository?.save(
            AccountBrowseCheckpoint(
                phase = phase.name,
                sourceFingerprint = snapshot.sourceFingerprint,
                detailFingerprint = snapshot.detailFingerprint,
                lastStableFingerprint = snapshot.lastStableFingerprint,
                sourceSnapshot = snapshot.sourceSnapshot,
                currentActivityClass = snapshot.currentActivityClass,
                imageBrowserActive = snapshot.imageBrowserActive,
                swipeCount = snapshot.swipeCount,
                feedRefreshRecoveryActive = snapshot.feedRefreshRecoveryActive,
                feedRefreshLastSnapshot = snapshot.feedRefreshLastSnapshot,
                savedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    /** Allows the shared page router to persist the current browse checkpoint before takeover. */
    fun saveRecoveryCheckpoint() = persistCheckpoint()

    /**
     * Execute the existing phase-to-action mapping.
     *
     * Keep the gate order identical to the original service implementation: reset when
     * monitoring/mode is disabled, then stop for active touch or PAUSED, then dispatch.
     */
    fun performStep() {
        if (!isSessionEnabled()) {
            onResetSession()
            return
        }
        if (!interactionPolicy.mayPerformAutomaticStep(
                sessionEnabled = true,
                touchActive = isTouchActive(),
                phase = phase,
                recoveryState = dynamicPageRecovery.state()
            )
        ) return

        when (phase) {
            AccountBrowsePhase.IDLE,
            AccountBrowsePhase.PAUSED -> return
            AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB -> actions.waitForDynamicTab()
            AccountBrowsePhase.VERIFY_NAVIGATION_ACTION -> actions.verifyNavigationAction()
            AccountBrowsePhase.REFRESH_DYNAMIC_FEED -> actions.refreshDynamicFeed()
            AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH -> actions.verifyDynamicFeedRefresh()
            AccountBrowsePhase.WAIT_FOR_FEED_STABLE -> actions.waitForFeedStable()
            AccountBrowsePhase.FIRST_SWIPE_COUNTDOWN -> actions.runFirstSwipeCountdown()
            AccountBrowsePhase.WAIT_FOR_LIST_SCROLL -> actions.performFeedScroll()
            AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE -> actions.verifyInitialSwipe()
            AccountBrowsePhase.WAIT_FOR_CARD_OPEN,
            AccountBrowsePhase.WAIT_FOR_SAFE_CARD -> actions.openVisibleFeedCard()
            AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD -> actions.checkDetailPageLoaded()
            AccountBrowsePhase.DETAIL_DWELL -> actions.returnFromDetailPage()
            AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN -> actions.waitForImageBrowserReturn()
            AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE -> actions.waitForDetailRestabilize()
            AccountBrowsePhase.WAIT_FOR_LIST_RETURN -> actions.checkFeedListReturned()
        }
    }

    /**
     * Reset session state while preserving the former Service side-effect order.
     * External Android/UI cleanup is supplied by the caller at the original boundaries.
     */
    fun resetSession(
        beforeReset: () -> Unit,
        afterReset: () -> Unit
    ) {
        beforeReset()
        checkpointRepository?.clear()
        stateMachine.reset()
        restoredCheckpointPhase = null
        restoredBusinessCheckpoint = null
        verifiedFeedAwaitingPhaseResume = null
        dynamicPageRecovery.reset()
        session.reset()
        afterReset()
    }

    /**
     * Unknown or unsafe evidence pauses only dangerous operations.  The task remains alive in
     * RECOVERING and the shared scheduler continues fresh, read-only page observations.
     */
    fun requestRecovery(
        reason: String,
        beforePause: () -> Unit,
        hideCountdown: () -> Unit,
        onPauseReasonChanged: (String) -> Unit
    ) {
        beforePause()
        session.gestureInFlight = false
        session.countdownRemaining = 0
        hideCountdown()
        session.deadlineAt = 0L
        if (session.pauseReason != reason) {
            session.pauseReason = reason
            onPauseReasonChanged(reason)
        }
        persistCheckpoint()
        dynamicPageRecovery.observePage(
            BrowsePageEvidence(
                packageName = "",
                activityClass = "",
                pageKind = com.freedom.features.accountbrowse.ports.BrowsePageKind.UNKNOWN,
                fingerprint = "",
                confidence = 0,
                reason = reason
            )
        )
    }

    /** Move only recovery-state preparation; Android overlay and Handler operations stay outside. */
    fun beginNavigationRecovery(
        reason: String,
        beforeRecovery: () -> Unit,
        initialDynamicTabWaitTimeoutMs: Long,
        pageStatePollMs: Long,
        currentTimeMillis: () -> Long,
        onStarted: (String) -> Unit,
        scheduleStep: (Long) -> Unit
    ) {
        beforeRecovery()
        session.pauseReason = reason
        session.navigationActionKey = ""
        session.navigationBeforeActivity = ""
        session.navigationBeforeFingerprint = ""
        session.navigationActionDeadlineAt = 0L
        session.navigationActionAttempts = 0
        session.recoveryBackAttempts = 0
        session.recoveryHomeClickAttempts = 0
        session.recoveryDynamicClickAttempts = 0
        session.dynamicTabClickConfirmed = false
        session.lastDynamicTabWaitReason = ""
        stateMachine.transitionTo(AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB)
        session.deadlineAt = currentTimeMillis() + initialDynamicTabWaitTimeoutMs
        onStarted(reason)
        scheduleStep(pageStatePollMs)
    }

}
