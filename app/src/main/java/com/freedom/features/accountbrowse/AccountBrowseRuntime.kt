package com.freedom.features.accountbrowse

import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.accountbrowse.ports.BrowsePageEvidence
import com.freedom.features.accountbrowse.ports.BrowsePageKind
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpoint
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpointRepository
import com.freedom.features.recovery.RecoveryTransactionEvent
import com.freedom.features.recovery.RecoveryTransactionState
import com.freedom.features.recovery.UnifiedRecoveryTransactionStateMachine
import com.freedom.features.recovery.RecoveryEventDispatcher
import com.freedom.features.recovery.RecoveryEventIdentity

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
    private val onLog: (String) -> Unit = {}
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
    private val recoveryTransaction = RecoveryEventDispatcher(
        UnifiedRecoveryTransactionStateMachine(),
        onLog
    )
    private val recoveryRouteId = "account-browse"
    private val recoveryCheckpointId = "account-browse-checkpoint"

    fun scheduleStep(delayMs: Long) = stepScheduler.schedule(delayMs)

    fun cancelScheduledStep() = stepScheduler.cancel()

    /**
     * AccountBrowse Runtime owns the session snapshot. The exposed port is a command surface whose
     * setters atomically replace the private immutable snapshot; adapters never receive storage.
     */
    private val sessionStateOwner = AccountBrowseSessionStateOwner()
    val session: AccountBrowseSessionStatePort = sessionStateOwner.port
    private var restoredCheckpointPhase: AccountBrowsePhase? = null

    var phase: AccountBrowsePhase = AccountBrowsePhase.IDLE
        private set

    init {
        checkpointRepository?.load()?.let { checkpoint ->
            restoredCheckpointPhase = runCatching { AccountBrowsePhase.valueOf(checkpoint.phase) }
                .getOrDefault(AccountBrowsePhase.PAUSED)
            // A restored checkpoint is never executable immediately. The workflow must first
            // re-observe the page and satisfy the shared two-sample verification gate.
            phase = AccountBrowsePhase.PAUSED
            recoveryTransaction.activate(
                RecoveryEventIdentity(
                    taskId = "ACCOUNT_BROWSE",
                    routeId = recoveryRouteId,
                    runGeneration = currentRunGeneration().coerceAtLeast(0L),
                    checkpointId = recoveryCheckpointId,
                    observationGeneration = 0L,
                    unknownReason = "CHECKPOINT_LOADED"
                )
            )
            recoveryTransaction.dispatch(
                RecoveryTransactionEvent.CheckpointLoaded(
                    if (checkpoint.savedAtEpochMs > 0L) {
                        RecoveryTransactionEvent.Source.PROCESS_RESTART
                    } else {
                        RecoveryTransactionEvent.Source.SERVICE_RECONNECT
                    }
                )
            )
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

    fun hasPendingCheckpointVerification(): Boolean = restoredCheckpointPhase != null

    /** Moves a restored session into a read-only feed verification phase. */
    fun beginCheckpointVerification(): Boolean {
        if (restoredCheckpointPhase == null) return false
        transitionTo(AccountBrowsePhase.WAIT_FOR_FEED_STABLE)
        return true
    }

    /** Normalizes page-observation uncertainty before any AccountBrowse action is authorized. */
    fun observeRecoveryPage(page: BrowsePageEvidence): RecoveryTransactionState {
        val unknown = page.pageKind == BrowsePageKind.UNKNOWN ||
            page.packageName.isBlank() ||
            !isTargetPackage(page.packageName) ||
            page.fingerprint.isBlank() ||
            page.confidence < 80
        if (unknown) {
            recoveryTransaction.activate(
                RecoveryEventIdentity(
                    taskId = "ACCOUNT_BROWSE",
                    routeId = recoveryRouteId,
                    runGeneration = currentRunGeneration().coerceAtLeast(0L),
                    checkpointId = recoveryCheckpointId,
                    observationGeneration = recoveryTransaction.stableVerificationSamples.toLong(),
                    unknownReason = page.reason.ifBlank { "ACCOUNT_BROWSE_PAGE_UNKNOWN" }
                )
            )
            return recoveryTransaction.dispatch(
                RecoveryTransactionEvent.UnknownPage(
                    page.reason.ifBlank { "ACCOUNT_BROWSE_PAGE_UNKNOWN" }
                )
            ).also {
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
            }
        }
        return recoveryTransaction.state
    }

    fun recoveryState(): RecoveryTransactionState = recoveryTransaction.state

    fun recoveryStableSamples(): Int = recoveryTransaction.stableVerificationSamples

    /**
     * Replaces the dynamic-feed-local stableSamples decision with the shared two-sample gate.
     * The caller still owns fingerprint extraction and all feed actions.
     */
    fun observeDynamicFeedFingerprint(fingerprint: String): Boolean {
        val state = recoveryTransaction.observeFingerprint(fingerprint)
        if (state != RecoveryTransactionState.VERIFIED) return false
        recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeRequested)
        recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeCompleted)
        restoredCheckpointPhase = null
        return true
    }

    private fun isTargetPackage(packageName: String): Boolean = packageName == targetPackage()

    /** The AccountBrowse workflow owns all phase transitions. */
    fun transitionTo(nextPhase: AccountBrowsePhase) {
        phase = nextPhase
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
        if (isTouchActive() || phase == AccountBrowsePhase.PAUSED) return
        if (recoveryTransaction.state != RecoveryTransactionState.ACTIVE &&
            phase != AccountBrowsePhase.WAIT_FOR_FEED_STABLE
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
        phase = AccountBrowsePhase.IDLE
        restoredCheckpointPhase = null
        recoveryTransaction.reset()
        session.reset()
        afterReset()
    }

    /** Centralize pause-state ordering; Android-specific cleanup remains callback-owned. */
    fun pauseSession(
        reason: String,
        allowManualResume: Boolean,
        capturePausedContext: (AccountBrowsePhase) -> Unit,
        beforePause: () -> Unit,
        hideCountdown: () -> Unit,
        onPauseReasonChanged: (String) -> Unit
    ) {
        if (allowManualResume && phase != AccountBrowsePhase.PAUSED) {
            capturePausedContext(phase)
        }
        beforePause()
        if (allowManualResume) {
            session.manualResumeAllowed = true
            session.manualRecoveryInProgress = true
            session.manualRecoveryAttemptActive = false
        }
        session.gestureInFlight = false
        session.countdownRemaining = 0
        hideCountdown()
        phase = AccountBrowsePhase.PAUSED
        session.deadlineAt = 0L
        if (session.pauseReason != reason) {
            session.pauseReason = reason
            onPauseReasonChanged(reason)
        }
        persistCheckpoint()
    }

    /** Record manual takeover while already paused, without replacing the first snapshot. */
    fun recordManualTakeoverWhilePaused(
        activityClass: String,
        sourceFingerprint: String,
        detailFingerprint: String,
        imageBrowserActive: Boolean,
        lastProgrammaticAction: String,
        currentTimeMillis: () -> Long
    ) {
        session.manualResumeAllowed = true
        session.manualRecoveryInProgress = true
        session.manualRecoveryAttemptActive = false
        if (session.pausedContext == null) {
            session.pausedContext = ManualResumeContext(
                phaseName = "PAUSED",
                activityClass = activityClass,
                sourceFingerprint = sourceFingerprint,
                detailFingerprint = detailFingerprint,
                imageBrowserActive = imageBrowserActive,
                lastProgrammaticAction = lastProgrammaticAction,
                capturedAt = currentTimeMillis()
            )
        }
    }

    /**
     * Apply the existing post-touch quiet-period checks and state reset before navigation.
     * Predicates are lambdas so they are evaluated lazily in their original branch order.
     */
    fun resumeAfterManualTouch(
        isMonitoringRunning: () -> Boolean,
        isBrowseModeEnabled: () -> Boolean,
        quietPeriodMs: Long,
        quietPeriodStage: Int,
        initialDynamicTabWaitTimeoutMs: Long,
        pageStatePollMs: Long,
        currentTimeMillis: () -> Long,
        onLog: (String) -> Unit,
        scheduleStep: (Long) -> Unit
    ) {
        when {
            !session.manualResumeAllowed -> Unit
            session.touchActive -> onLog("手动恢复检查未执行：仍检测到屏幕触摸，继续等待用户操作结束")
            !isMonitoringRunning() -> onLog("手动恢复检查未执行：监听已停止")
            !isBrowseModeEnabled() -> onLog("手动恢复检查未执行：自动浏览模式已关闭")
            else -> {
                session.manualRecoveryInProgress = true
                session.manualRecoveryAttemptActive = true
                val context = session.pausedContext
                onLog(
                    "用户最后一次屏幕操作后已静默 ${quietPeriodMs / 1000.0} 秒（第${quietPeriodStage}轮）；" +
                        "开始基于暂停上下文重新分析当前页面；暂停前阶段=${context?.phaseName ?: "未知"}，" +
                        "暂停前 Activity=${context?.activityClass?.ifBlank { "未知" } ?: "未知"}"
                )
                session.pauseReason = ""
                session.sourceFingerprint = ""
                session.detailFingerprint = ""
                session.lastStableFingerprint = ""
                session.stableSamples = 0
                session.navigationActionKey = ""
                session.navigationBeforeActivity = ""
                session.navigationBeforeFingerprint = ""
                session.navigationActionDeadlineAt = 0L
                session.navigationActionAttempts = 0
                session.recoveryBackAttempts = 0
                session.recoveryHomeClickAttempts = 0
                session.recoveryDynamicClickAttempts = 0
                session.dynamicTabClickConfirmed = false
                phase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
                session.deadlineAt = currentTimeMillis() + initialDynamicTabWaitTimeoutMs
                session.lastDynamicTabWaitReason = ""
                onLog("手动恢复已进入主动导航：先识别页面层级，再返回必要的上级页面，主动定位并验证‘动态’标签")
                scheduleStep(pageStatePollMs)
            }
        }
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
        if (session.pausedContext == null) {
            session.pausedContext = ManualResumeContext(
                phaseName = phase.name,
                activityClass = session.currentActivityClass,
                sourceFingerprint = session.sourceFingerprint,
                detailFingerprint = session.detailFingerprint,
                imageBrowserActive = session.imageBrowserActive,
                lastProgrammaticAction = session.programmaticActionLabel,
                capturedAt = currentTimeMillis()
            )
        }
        beforeRecovery()
        session.pauseReason = reason
        session.manualResumeAllowed = false
        session.manualRecoveryInProgress = false
        session.manualRecoveryAttemptActive = false
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
        phase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
        session.deadlineAt = currentTimeMillis() + initialDynamicTabWaitTimeoutMs
        onStarted(reason)
        scheduleStep(pageStatePollMs)
    }

}
