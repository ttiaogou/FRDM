package com.freedom.adapters.accessibility.android

import com.freedom.gesture.api.GestureApi
import android.view.accessibility.AccessibilityEvent
import com.freedom.core.accessibility.api.AccessibilityGateway
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.core.accessibility.api.GlobalAccessibilityAction
import com.freedom.core.accessibility.api.NodeAction
import com.freedom.core.accessibility.api.NodeActionRequest
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.overlay.OverlayPort
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.PageObservationCoordinator
import com.freedom.core.runtime.PageFingerprint
import com.freedom.features.accountbrowse.DynamicFeedRecoveryRouteAdapter
import com.freedom.core.runtime.PageObservationSubscription
import com.freedom.features.accountbrowse.AccountBrowseInteractionContext
import com.freedom.features.accountbrowse.AccountBrowseInteractionEventClassifier
import com.freedom.features.accountbrowse.AccountBrowseInteractionEvidence
import com.freedom.features.accountbrowse.AccountBrowseInteractionEvidenceClassifier
import com.freedom.features.accountbrowse.AccountBrowsePhase
import com.freedom.features.accountbrowse.AccountBrowseRuntime
import com.freedom.features.accountbrowse.AccountBrowseTask
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpointRepository
import com.freedom.features.accountbrowse.AccountBrowseSessionStatePort
import com.freedom.features.accountbrowse.FeedRefreshVerificationPolicy
import com.freedom.features.accountbrowse.FeedReturnStability
import com.freedom.features.accountbrowse.FeedReturnVerificationPolicy
import com.freedom.features.accountbrowse.ports.*

/**
 * Android execution adapter for AccountBrowse.
 *
 * The workflow owns orchestration and retry/state decisions, while SocialBrowsePort supplies
 * Momo-specific semantic evidence, verified screen bounds, and a short-lived semantic dismiss
 * locator. AccessibilityGateway is used for inspection and non-touch platform actions; all screen
 * taps and movement go through the shared, visualized Gesture Policy Pipeline. Raw Android nodes never
 * cross into this class.
 */
internal class AndroidAccountBrowseWorkflow(
    private val accessibilityGateway: AccessibilityGateway,
    private val gesturePolicy: GestureApi,
    private val taskScheduler: TaskScheduler,
    private val isBrowseModeActive: () -> Boolean,
    private val socialBrowsePort: SocialBrowsePort,
    private val overlayPort: OverlayPort,
    private val isMonitoringRunning: () -> Boolean,
    private val targetPackage: () -> String,
    private val monitorPageContext: () -> String,
    private val onLog: (String) -> Unit,
    private val onRequestScreenOff: () -> Boolean = { false },
    private val pageObservationCoordinator: PageObservationCoordinator? = null,
    private val accountBrowseCheckpointRepository: AccountBrowseCheckpointRepository? = null
) {
    private val displayMetrics get() = accessibilityGateway.displayMetrics()
    private val navigationExecutor = AccountBrowseNavigationExecutor(gesturePolicy) { displayMetrics }
    private val scrollExecutor = AccountBrowseScrollExecutor()
    private val popupExecutor = AccountBrowsePopupExecutor()
    private val recoveryExecutor = AccountBrowseRecoveryExecutor()

    private val observationCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        pageObservationCoordinator ?: PageObservationCoordinator(taskScheduler)
    }
    private val dynamicFeedRecoveryRoute = DynamicFeedRecoveryRouteAdapter(targetPackage) {
        accountBrowseRuntime.saveRecoveryCheckpoint()
    }
    private val pageObservationChannel: PageObservationSubscription by lazy(LazyThreadSafetyMode.NONE) {
        observationCoordinator.registerChannel(
            channelId = ACCOUNT_BROWSE_PAGE_OBSERVATION_CHANNEL,
            quietPeriodMs = PAGE_OBSERVATION_QUIET_PERIOD_MS,
            maxWaitMs = PAGE_OBSERVATION_MAX_WAIT_MS,
            eventTypes = setOf(
                AccessibilityEventType.WINDOW_STATE_CHANGED,
                AccessibilityEventType.WINDOWS_CHANGED,
                AccessibilityEventType.WINDOW_CONTENT_CHANGED,
                AccessibilityEventType.VIEW_CLICKED,
                AccessibilityEventType.VIEW_SELECTED,
                AccessibilityEventType.VIEW_SCROLLED
            ),
            onObservationRequested = ::onCoalescedPageObservationRequested
        )
    }

    init {
        observationCoordinator.registerFingerprintRoute(dynamicFeedRecoveryRoute.asRoute())
    }

    internal var phase: AccountBrowsePhase
        get() = accountBrowseRuntime.phase
        private set(value) {
            if (value == AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB &&
                accountBrowseRuntime.phase != AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            ) {
                session.dynamicTabWaitStartedAt = System.currentTimeMillis()
            }
            accountBrowseRuntime.transitionTo(value)
        }

    private val session: AccountBrowseSessionStatePort
        get() = accountBrowseRuntime.session

    internal fun startIfIdle() {
        accountBrowseTask.start()
        if (accountBrowsePhase == AccountBrowsePhase.IDLE) {
            startAccountBrowseIfNeeded()
        } else if (accountBrowseRuntime.hasPendingCheckpointVerification()) {
            startRestoredCheckpointVerification()
        }
    }

    private fun matchDynamicFeedRoute(page: BrowsePageEvidence): Boolean =
        observationCoordinator.matchFingerprint(
            PageFingerprint(
                packageName = page.packageName,
                activityClass = page.activityClass,
                semanticPage = page.pageKind.name,
                adapterFingerprint = page.fingerprint
            )
        ) == DynamicFeedRecoveryRouteAdapter.ROUTE_ID

    private fun startRestoredCheckpointVerification() {
        if (!isMonitoringRunning() || !isBrowseModeActive()) return
        if (!accountBrowseRuntime.beginCheckpointVerification()) return
        addLog("动态浏览已读取持久化检查点；进入只读页面复核，连续两次稳定指纹确认前不会恢复滑动、点击或导航")
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    internal fun isGestureBusyForCalibration(): Boolean =
        accountBrowseSession.gestureInFlight || accountBrowseSession.overlayPassthroughActive

    internal fun currentActivityForInteractionLog(): String =
        accountBrowseSession.currentActivityClass.ifBlank { "未知" }

    internal fun currentPhaseForInteractionLog(): String = accountBrowsePhase.name

    private var accountBrowsePhase: AccountBrowsePhase
        get() = phase
        set(value) { phase = value }

    private val accountBrowseSession: AccountBrowseSessionStatePort
        get() = session

    private val accountBrowseRuntime by lazy(LazyThreadSafetyMode.NONE) {
        AccountBrowseRuntime(
            scheduler = taskScheduler,
            canScheduleStep = {
                isMonitoringRunning() && isBrowseModeActive() &&
                    !accountBrowseSession.touchActive &&
                    accountBrowsePhase != AccountBrowsePhase.PAUSED &&
                    accountBrowsePhase != AccountBrowsePhase.IDLE
            },
            isSessionEnabled = { isMonitoringRunning() && isBrowseModeActive() },
            isTouchActive = { accountBrowseSession.touchActive },
            targetPackage = targetPackage,
            checkpointRepository = accountBrowseCheckpointRepository,
            onResetSession = ::resetAccountBrowseSession,
            onReadOnlyRecoveryObservation = ::observeAccountBrowseRecoveryReadOnly,
            actions = AccountBrowseRuntime.Actions(
                waitForDynamicTab = ::waitForMomoDynamicTab,
                verifyNavigationAction = ::verifyNavigationAction,
                refreshDynamicFeed = ::refreshDynamicFeed,
                verifyDynamicFeedRefresh = ::verifyDynamicFeedRefresh,
                waitForFeedStable = ::waitForMomoFeedStable,
                runFirstSwipeCountdown = ::runFirstSwipeCountdown,
                performFeedScroll = ::performFeedScroll,
                verifyInitialSwipe = ::verifyInitialSwipe,
                openVisibleFeedCard = ::openVisibleFeedCard,
                checkDetailPageLoaded = ::checkDetailPageLoaded,
                returnFromDetailPage = ::returnFromDetailPage,
                waitForImageBrowserReturn = ::waitForImageBrowserReturn,
                waitForDetailRestabilize = ::waitForDetailRestabilize,
                checkFeedListReturned = ::checkFeedListReturned
            )
        )
    }

    private val accountBrowseTask by lazy(LazyThreadSafetyMode.NONE) {
        AccountBrowseTask(accountBrowseRuntime)
    }

    private val interactionEventClassifier = AccountBrowseInteractionEventClassifier()
    private val interactionEvidenceClassifier = AccountBrowseInteractionEvidenceClassifier(interactionEventClassifier)
    private var gestureDispatchSyncTask: ScheduledTask? = null

    private val manualHandoffCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        AccountBrowseManualHandoffCoordinator(
            scheduler = taskScheduler,
            isOverlayPassthroughActive = { accountBrowseSession.overlayPassthroughActive },
            isWaitingForScrollSettle = { accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE },
            restoreOverlayTouchPassthrough = { restoreMonitorOverlayTouchPassthrough() },
            pauseAfterWatchdog = ::pauseAccountBrowse
        )
    }

    private fun addLog(message: String) = onLog(message)

    private fun scheduleOverlayPassthroughWatchdog(delayMs: Long) =
        manualHandoffCoordinator.scheduleOverlayPassthroughWatchdog(delayMs)

    private fun cancelOverlayPassthroughWatchdog() =
        manualHandoffCoordinator.cancelOverlayPassthroughWatchdog()

    // Legacy call sites may still signal an interrupted gesture. They no longer create a
    // handoff timer: the unified scheduler owns the next read-only observation.
    private fun cancelManualTouchResume() = Unit
    private fun scheduleManualTouchResume(delayMs: Long = 0L) = accountBrowseRuntime.onUserInteractionEnded()

    internal fun cancelScheduledStep() = accountBrowseRuntime.cancelScheduledStep()

    /** Cancels only the shared page-observation request owned by AccountBrowse. */
    internal fun cancelPageObservation() = pageObservationChannel.cancel()
    internal fun restoreOverlayTouchPassthrough(
        actionDescription: String = "首次滑动检查结束"
    ): Boolean = restoreMonitorOverlayTouchPassthrough(actionDescription)
    internal fun cleanup() {
        overlayPort.clearGestureTrail()
        restoreMonitorOverlayTouchPassthrough()
        cancelScheduledStep()
        pageObservationChannel.cancel()
        manualHandoffCoordinator.cleanup()
        gestureDispatchSyncTask?.cancel()
        gestureDispatchSyncTask = null
        hideFirstSwipeCountdown()
    }
    private fun restoreMonitorOverlayTouchPassthrough(
        actionDescription: String = "首次滑动检查结束"
    ): Boolean = restoreMonitorOverlayTouchPassthroughImpl(actionDescription)

    /** Own global touch start/end semantics for manual takeover and quiet-period resume. */
    internal fun handleGlobalTouchEvent(eventType: Int, eventPackage: String) {
        when (eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                accountBrowseSession.lastGlobalTouchEventAt = System.currentTimeMillis()
                accountBrowseSession.lastGlobalTouchEventName = "TYPE_TOUCH_INTERACTION_START"
                accountBrowseSession.lastGlobalTouchEventOrigin = if (accountBrowseSession.gestureInFlight) {
                    "Freedom 自动滑动期间收到；具体来源待确认"
                } else {
                    "全局触摸事件；系统未提供来源应用"
                }
                if (accountBrowseSession.gestureInFlight) {
                    accountBrowseSession.touchStartedDuringGesture = true
                    addLog(
                        "收到系统触摸开始事件；发生在 Freedom 自动滑动执行期间，归类为自动手势期间的触摸/中断候选；" +
                            "Activity=${accountBrowseSession.currentActivityClass.ifBlank { "未知" }}；阶段=${accountBrowsePhase.name}；" +
                            "${currentMonitorPageContext()}；系统未提供该事件的手指坐标"
                    )
                    return
                }
                addLog(
                    "用户在操作屏幕：收到 TYPE_TOUCH_INTERACTION_START；" +
                        "来源应用=${eventPackage.ifBlank { "系统未提供来源应用" }}；" +
                        "Activity=${accountBrowseSession.currentActivityClass.ifBlank { "未知" }}；" +
                        "自动浏览阶段=${accountBrowsePhase.name}；${currentMonitorPageContext()}；系统未提供手指坐标"
                )
                if (!accountBrowseSession.touchActive) {
                    accountBrowseSession.touchActive = true
                    accountBrowseRuntime.cancelScheduledStep()
                    accountBrowseRuntime.onUserInteractionStarted()
                    pauseAccountBrowse("检测到用户触摸；停止危险操作并持续只读观察")
                }
            }

            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                accountBrowseSession.lastGlobalTouchEventAt = System.currentTimeMillis()
                accountBrowseSession.lastGlobalTouchEventName = "TYPE_TOUCH_INTERACTION_END"
                accountBrowseSession.lastGlobalTouchEventOrigin = if (accountBrowseSession.gestureInFlight) {
                    "Freedom 自动滑动期间收到；具体来源待确认"
                } else {
                    "全局触摸事件；系统未提供来源应用"
                }
                if (accountBrowseSession.gestureInFlight) {
                    accountBrowseSession.touchEndedDuringGesture = true
                    addLog(
                        "收到系统触摸结束事件；发生在 Freedom 自动滑动执行期间，归类为自动手势期间的触摸/中断候选；" +
                            "Activity=${accountBrowseSession.currentActivityClass.ifBlank { "未知" }}；阶段=${accountBrowsePhase.name}；" +
                            "${currentMonitorPageContext()}；系统未提供该事件的手指坐标"
                    )
                    return
                }
                addLog(
                    "用户停止操作屏幕：收到 TYPE_TOUCH_INTERACTION_END；" +
                        "来源应用=${eventPackage.ifBlank { "系统未提供来源应用" }}；" +
                        "Activity=${accountBrowseSession.currentActivityClass.ifBlank { "未知" }}；" +
                        "自动浏览阶段=${accountBrowsePhase.name}；${currentMonitorPageContext()}；系统未提供手指坐标"
                )
                if (accountBrowseSession.touchActive) {
                    accountBrowseSession.touchActive = false
                    accountBrowseRuntime.onUserInteractionEnded()
                    addLog("用户已停止操作屏幕；统一恢复调度器已回到快速只读观察，无需人工恢复")
                } else {
                    addLog("收到 TYPE_TOUCH_INTERACTION_END，但当前没有匹配的触摸开始状态；未推断用户是否完成了实际操作")
                }
            }
        }
    }

    /** Apply browse-only event evidence before log de-duplication, as the legacy Service did. */
    internal fun onTargetAppEvent(eventType: Int, now: Long) {
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE
        ) {
            accountBrowseSession.lastScrollEventAt = now
            accountBrowseSession.scrollEventCount++
            if (!accountBrowseSession.scrollEventSeen) {
                accountBrowseSession.scrollEventSeen = true
                addLog("第 ${accountBrowseSession.swipeCount + 1} 轮滑动期间检测到目标应用列表滚动事件")
            }
        }
        if (accountBrowsePhase == AccountBrowsePhase.IDLE) startAccountBrowseIfNeeded()
    }

    internal fun onRefreshVerificationEvent(eventType: Int, className: String, now: Long) {
        if (accountBrowsePhase == AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH &&
            accountBrowseSession.feedRefreshRecoveryActive &&
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            isRecyclerViewClass(className) &&
            now >= accountBrowseSession.feedRefreshActionAt
        ) {
            accountBrowseSession.feedRefreshRecyclerContentChangeEvents++
            if (accountBrowseSession.feedRefreshRecyclerContentChangeEvents <= 3) {
                addLog(
                    "首页双击后的动态列表内容更新事件：" +
                        "RecyclerView，第 ${accountBrowseSession.feedRefreshRecyclerContentChangeEvents} 次"
                )
            }
        }
    }

    /**
     * Shared page observation only wakes the existing read-only verification phases. The
     * AccountBrowseScheduler keeps ownership of phase timing and does not replace an already
     * pending step, so event bursts cannot skip countdown, dwell, or gesture phases.
     */
    /** Scheduler callback: inspect fresh evidence only; never click, navigate or scroll here. */
    private fun observeAccountBrowseRecoveryReadOnly() {
        if (!isMonitoringRunning() || !isBrowseModeActive()) return
        val page = socialBrowsePort.inspectPage()
        accountBrowseRuntime.observeRecoveryPage(page)
        if (page.pageKind == BrowsePageKind.DYNAMIC_FEED && matchDynamicFeedRoute(page)) {
            val inspection = socialBrowsePort.inspectFeed()
            accountBrowseRuntime.observeDynamicFeedFingerprint(
                inspection.fingerprint.takeIf { inspection.ready && it.isNotBlank() }.orEmpty()
            )
        }
        if (accountBrowseRuntime.recoveryState() == com.freedom.features.recovery.RecoveryTransactionState.ACTIVE &&
            !accountBrowseSession.touchActive && accountBrowsePhase != AccountBrowsePhase.IDLE
        ) {
            accountBrowseRuntime.scheduleStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun onCoalescedPageObservationRequested() {
        if (!isMonitoringRunning() || !isBrowseModeActive() || accountBrowseSession.touchActive ||
            accountBrowsePhase !in PAGE_OBSERVATION_PHASES
        ) return
        accountBrowseRuntime.scheduleStep(PAGE_STATE_POLL_MS)
    }

    private fun isRecyclerViewClass(className: String): Boolean =
        className.substringAfterLast('.') == "RecyclerView"

    internal fun startAccountBrowseIfNeeded() {
        if (!isMonitoringRunning() || !isBrowseModeActive()) return
        accountBrowseTask.start()
        if (accountBrowsePhase != AccountBrowsePhase.IDLE) return
        if (!socialBrowsePort.supportsTargetPackage(targetPackage())) {
            pauseAccountBrowse("当前阶段仅支持陌陌动态列表；其他应用暂未接入浏览适配")
            return
        }
        val page = socialBrowsePort.inspectPage()
        accountBrowseRuntime.observeRecoveryPage(page)
        if (page.pageKind == BrowsePageKind.DYNAMIC_FEED && matchDynamicFeedRoute(page)) {
            addLog("动态浏览页面路由已匹配统一页面指纹：进入动态列表恢复/稳定性复核链路")
        }
        if (page.packageName.isBlank()) {
            if (!accountBrowseSession.rootUnavailableLogged) {
                addLog("恢复检查暂时无法读取当前前台页面；等待后续页面事件再次触发检查")
                accountBrowseSession.rootUnavailableLogged = true
            }
            return
        }
        if (!socialBrowsePort.supportsTargetPackage(page.packageName)) {
            if (!accountBrowseSession.notTargetForegroundLogged) {
                addLog("恢复检查发现当前前台不是陌陌；不会执行自动操作，等待进入陌陌后重新识别")
                accountBrowseSession.notTargetForegroundLogged = true
            }
            return
        }
        accountBrowseSession.notTargetForegroundLogged = false
        accountBrowseSession.rootUnavailableLogged = false

        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
        accountBrowseSession.deadlineAt = System.currentTimeMillis() + INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS
        accountBrowseSession.lastDynamicTabWaitReason = ""
        accountBrowseSession.lastStableFingerprint = ""
        accountBrowseSession.stableSamples = 0
        addLog("已检测到陌陌进入前台；开始识别页面层级并定位首页顶部‘动态’标签（最多 ${INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS / 1_000} 秒），进入后再验证列表稳定性")
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    internal fun scheduleAccountBrowseStep(delayMs: Long) {
        accountBrowseRuntime.scheduleStep(delayMs)
    }

    internal fun resetAccountBrowseSession() {
        accountBrowseTask.stop(
            beforeReset = {
                restoreMonitorOverlayTouchPassthrough()
                pageObservationChannel.cancel()
                accountBrowseRuntime.cancelScheduledStep()
            },
            afterReset = ::hideFirstSwipeCountdown
        )
    }

    /** Retains the call boundary while converting every former pause into a recovery transaction. */
    internal fun pauseAccountBrowse(reason: String, allowManualResume: Boolean = false) {
        accountBrowseRuntime.requestRecovery(
            reason = reason,
            beforePause = {
                restoreMonitorOverlayTouchPassthrough()
                pageObservationChannel.cancel()
                accountBrowseRuntime.cancelScheduledStep()
            },
            hideCountdown = ::hideFirstSwipeCountdown,
            onPauseReasonChanged = { changedReason ->
                addLog("动态浏览已进入统一恢复观察：$changedReason")
            }
        )
    }

    internal fun performAccountBrowseStep() {
        accountBrowseTask.performStep()
    }

    /**
     * Inspect an exposed dialog/popup before navigation or browsing. Only an explicit,
     * non-consequential close action is automated. Permission, account, payment, deletion,
     * agreement and confirmation prompts remain for the user to handle.
     */
    private fun handleMomoPopup(): Boolean {
        val popup = socialBrowsePort.inspectPopup()
        val now = System.currentTimeMillis()

        if (accountBrowseSession.popupDismissPending) {
            if (popup == null) {
                accountBrowseSession.popupDismissPending = false
                accountBrowseSession.popupDismissDeadlineAt = 0L
                accountBrowseSession.popupNextAttemptAt = 0L
                accountBrowseSession.popupActionAttempts = 0
                accountBrowseSession.popupExhaustedLogged = false
                accountBrowseSession.lastPopupSignature = ""
                addLog("弹窗关闭已验证：重新扫描后未发现原弹窗；继续当前页面导航/浏览步骤")
                return false
            }
            if (now < accountBrowseSession.popupDismissDeadlineAt) {
                scheduleAccountBrowseStep(POPUP_VERIFY_POLL_MS)
                return true
            }
            accountBrowseSession.popupDismissPending = false
            accountBrowseSession.popupDismissDeadlineAt = 0L
            accountBrowseSession.popupNextAttemptAt = now + POPUP_RETRY_DELAY_MS
            addLog("弹窗关闭动作已提交，但页面仍报告弹窗存在；等待重新分析关闭方式，不执行页面点击或滑动")
            scheduleAccountBrowseStep(POPUP_RETRY_DELAY_MS)
            return true
        }

        if (popup == null) {
            if (accountBrowseSession.lastPopupSignature.isNotBlank()) {
                accountBrowseSession.lastPopupSignature = ""
                accountBrowseSession.popupActionAttempts = 0
                accountBrowseSession.popupExhaustedLogged = false
                accountBrowseSession.popupNextAttemptAt = 0L
            }
            return false
        }

        val isNewPopup = popup.signature != accountBrowseSession.lastPopupSignature
        if (isNewPopup) {
            accountBrowseSession.lastPopupSignature = popup.signature
            accountBrowseSession.popupActionAttempts = 0
            accountBrowseSession.popupExhaustedLogged = false
            accountBrowseSession.popupNextAttemptAt = 0L
        }

        val popupDecision = popupExecutor.decide(
            popup = popup,
            nowEpochMs = now,
            nextAttemptAtEpochMs = accountBrowseSession.popupNextAttemptAt,
            actionAttempts = accountBrowseSession.popupActionAttempts,
            maxAttempts = MAX_POPUP_DISMISS_ATTEMPTS,
            minimumPollMs = POPUP_VERIFY_POLL_MS
        )
        when (popupDecision) {
            AccountBrowsePopupExecutor.Decision.Critical -> {
                if (isNewPopup) {
                    addLog("识别到需要用户决定的弹窗：${popup.description}；检测到权限/账号/支付/删除/协议等重要内容，不自动确认或关闭")
                }
                scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
                return true
            }
            is AccountBrowsePopupExecutor.Decision.Wait -> {
                scheduleAccountBrowseStep(popupDecision.delayMs)
                return true
            }
            AccountBrowsePopupExecutor.Decision.NoSafeAction -> {
                if (!accountBrowseSession.popupExhaustedLogged) {
                    addLog("弹窗已识别，但没有暴露可确认的安全关闭方式；保持等待用户处理，不使用屏幕外部点击或盲目返回")
                    accountBrowseSession.popupExhaustedLogged = true
                }
                scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
                return true
            }
            AccountBrowsePopupExecutor.Decision.AttemptsExhausted -> {
                if (!accountBrowseSession.popupExhaustedLogged) {
                    addLog("已识别弹窗，但两次安全关闭尝试后仍无法确认关闭；交由用户处理，不执行动态导航或滑动")
                    accountBrowseSession.popupExhaustedLogged = true
                }
                scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
                return true
            }
            is AccountBrowsePopupExecutor.Decision.SystemDismiss,
            is AccountBrowsePopupExecutor.Decision.CoordinateTap -> Unit
        }
        val hasSafeDismissMethod = popupDecision is AccountBrowsePopupExecutor.Decision.SystemDismiss
        val closeBounds = (popupDecision as? AccountBrowsePopupExecutor.Decision.CoordinateTap)?.preferredBounds
        val closeTargetBounds = (popupDecision as? AccountBrowsePopupExecutor.Decision.CoordinateTap)?.targetBounds
        var actionDescription = "没有可确认的安全关闭控件"
        var dismissed = false
        if (hasSafeDismissMethod) {
            actionDescription = "系统弹窗 ACTION_DISMISS"
            dismissed = runCatching {
                accessibilityGateway.performNodeAction(
                    NodeActionRequest((popupDecision as AccountBrowsePopupExecutor.Decision.SystemDismiss).target, NodeAction.DISMISS)
                ).performed
            }.getOrDefault(false)
        }

        if (dismissed) {
            accountBrowseSession.popupActionAttempts++
            accountBrowseSession.popupDismissPending = true
            accountBrowseSession.popupDismissDeadlineAt = now + POPUP_DISMISS_VERIFY_TIMEOUT_MS
            accountBrowseSession.programmaticActionLabel = "Freedom ACTION_DISMISS 弹窗关闭"
            accountBrowseSession.ignoreManualEventsUntil = now + POPUP_DISMISS_VERIFY_TIMEOUT_MS
            addLog("识别到可安全关闭的陌陌弹窗：${popup.description}；采用系统语义 ACTION_DISMISS（非屏幕点击）；等待重新扫描验证关闭结果")
            scheduleAccountBrowseStep(POPUP_VERIFY_POLL_MS)
            return true
        }

        if (closeBounds != null && closeTargetBounds != null) {
            actionDescription = "明确关闭控件（${popup.description}）"
            val tap = navigationExecutor.createCoordinateTap(
                preferredBounds = closeBounds,
                clickableBounds = closeTargetBounds,
                description = "陌陌弹窗关闭",
                expectedWindow = popup.targetWindow
            )
            if (tap == null) {
                accountBrowseSession.popupActionAttempts++
                accountBrowseSession.popupNextAttemptAt = now + POPUP_RETRY_DELAY_MS
                addLog("弹窗识别：${popup.description}；关闭控件边界无效或坐标越界，拒绝执行节点级点击")
                scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
                return true
            }

            accountBrowseSession.popupActionAttempts++
            accountBrowseSession.programmaticActionLabel = "Freedom COORDINATE_TAP 弹窗关闭"
            accountBrowseSession.ignoreManualEventsUntil = now + POPUP_DISMISS_VERIFY_TIMEOUT_MS
            accountBrowseSession.gestureInFlight = true
            val submission = runCatching {
                gesturePolicy.execute(tap.request) { result ->
                    accountBrowseSession.gestureInFlight = false
                    if (!isMonitoringRunning()) return@execute
                    if (result.status == GestureExecutionStatus.DEFERRED) {
                        accountBrowseSession.popupNextAttemptAt = System.currentTimeMillis() + POPUP_RETRY_DELAY_MS
                        addLog("弹窗关闭动作在平台派发前延期；不假定弹窗状态已变化，等待新快照后重新识别")
                        scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
                    } else if (result.status == GestureExecutionStatus.COMPLETED) {
                        accountBrowseSession.popupDismissPending = true
                        accountBrowseSession.popupDismissDeadlineAt = System.currentTimeMillis() + POPUP_DISMISS_VERIFY_TIMEOUT_MS
                        addLog("弹窗关闭坐标手势已完成；目标边界=${(tap.request.intent as GestureIntent.Tap).targetBounds.toShortString()}；等待重新扫描验证关闭结果")
                        scheduleAccountBrowseStep(POPUP_VERIFY_POLL_MS)
                    } else {
                        accountBrowseSession.popupNextAttemptAt = System.currentTimeMillis() + POPUP_RETRY_DELAY_MS
                        val interruptedByTouch = accountBrowseSession.touchStartedDuringGesture ||
                            accountBrowseSession.touchEndedDuringGesture || accountBrowseSession.touchActive
                        addLog("弹窗关闭坐标手势未完成（${result.status}）；不假定弹窗已关闭，${if (interruptedByTouch) "等待用户接管" else "稍后重新识别"}")
                        scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
                    }
                }
            }.getOrDefault(GestureSubmission.REJECTED)

            if (submission == GestureSubmission.DEFERRED_NOT_READY) {
                accountBrowseSession.gestureInFlight = false
                accountBrowseSession.popupActionAttempts =
                    (accountBrowseSession.popupActionAttempts - 1).coerceAtLeast(0)
                accountBrowseSession.programmaticActionLabel = ""
                accountBrowseSession.ignoreManualEventsUntil = 0L
                accountBrowseSession.popupNextAttemptAt = now + POPUP_RETRY_DELAY_MS
                addLog("弹窗关闭手势因页面/窗口暂时不可操作而延期；未派发动作、不消耗关闭尝试次数，等待遮挡变化后重新扫描")
                scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
            } else if (submission != GestureSubmission.ACCEPTED) {
                accountBrowseSession.gestureInFlight = false
                accountBrowseSession.popupNextAttemptAt = now + POPUP_RETRY_DELAY_MS
                addLog("弹窗关闭坐标手势未被接受；没有执行不可见的节点级点击")
                scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
            } else {
                addLog("已提交陌陌弹窗关闭坐标点击；最终坐标由统一策略编译并可视化；等待系统完成回调")
            }
            return true
        }

        accountBrowseSession.popupActionAttempts++
        if (isNewPopup || accountBrowseSession.popupActionAttempts == 1) {
            addLog("弹窗识别：${popup.description}；关闭操作未成功（${actionDescription}）；不使用屏幕外部点击或盲目返回")
        }
        accountBrowseSession.popupNextAttemptAt = now + POPUP_RETRY_DELAY_MS
        scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
        return true
    }

    private fun acceptConfirmedDynamicFeed(reason: String, viaFallback: Boolean) {
        accountBrowseSession.imageBrowserActive = false
        accountBrowseSession.imageBrowserReturnActivityClass = ""
        accountBrowseSession.detailActivityClass = ""
        accountBrowseSession.detailFingerprint = ""
        accountBrowseSession.programmaticActionLabel = ""
        accountBrowseSession.detailDwellDueAt = 0L
        accountBrowseSession.detailDwellStartedAt = 0L
        accountBrowseSession.detailDwellRemainingMs = 0L
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_FEED_STABLE
        accountBrowseSession.deadlineAt = System.currentTimeMillis() + INITIAL_FEED_STABILITY_TIMEOUT_MS
        accountBrowseSession.lastDynamicTabWaitReason = ""
        accountBrowseSession.lastStableFingerprint = ""
        accountBrowseSession.stableSamples = 0
        addLog(
            "动态列表页面身份已确认；识别路径=${if (viaFallback) "同页备用指纹" else "动态标签主指纹"}；" +
                "现在独立等待列表稳定（最多 ${INITIAL_FEED_STABILITY_TIMEOUT_MS / 1_000} 秒）。$reason"
        )
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    private fun isRetainedObstructionEvidence(reason: String): Boolean =
        recoveryExecutor.isRetainedObstructionEvidence(reason)

    private fun waitForMomoDynamicTab() {
        val page = socialBrowsePort.inspectPage()
        var reason = if (!page.windowAvailable) "暂时无法读取当前页面" else page.reason
        if (!socialBrowsePort.supportsTargetPackage(page.packageName)) {
            val externalPopup = socialBrowsePort.inspectExternalPopup()
            if (externalPopup != null) {
                reason = "当前前台为外部系统窗口/弹窗：${externalPopup.description}；需要用户处理，不对其他应用执行关闭或点击"
            } else if (!page.windowAvailable) {
                reason = "暂时无法读取当前页面"
            } else {
                reason = "陌陌当前不在前台；保留导航上下文，不对其他应用执行操作"
            }
        } else if (handleMomoPopup()) {
            return
        } else if (isRetainedObstructionEvidence(page.reason)) {
            if (reason != accountBrowseSession.lastDynamicTabWaitReason) {
                accountBrowseSession.lastDynamicTabWaitReason = reason
                addLog("动态页面身份仍在同一窗口代次内有效，但当前被遮挡；不重复点击导航、不终止任务，等待遮挡消失后重新识别")
            }
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        } else {
            val startupWaitReason = if (
                accountBrowseSession.feedRefreshRecoveryActive &&
                !accountBrowseSession.feedRefreshStartupReady
            ) {
                dynamicFeedRecoveryStartupWaitReason()
            } else {
                null
            }
            if (startupWaitReason != null) {
                reason = startupWaitReason
            } else {
                accountBrowseSession.notTargetForegroundLogged = false
                accountBrowseSession.rootUnavailableLogged = false

                val tabEvidence = socialBrowsePort.inspectDynamicTab()
                val structuralFeed = if (page.pageKind == BrowsePageKind.DYNAMIC_FEED &&
                    page.confidence >= 80 && !isRetainedObstructionEvidence(page.reason)
                ) {
                    socialBrowsePort.inspectFeed()
                } else null
                if (structuralFeed?.ready == true) {
                    // The adapter's page-local alternate route (Home appbar publish entry + Home
                    // shell) is sufficient when the Dynamic tab label is currently covered.
                    // Feed/list readiness is still required; no gesture bypass is granted here.
                    if (accountBrowseSession.feedRefreshRecoveryActive) {
                        accountBrowsePhase = AccountBrowsePhase.REFRESH_DYNAMIC_FEED
                        accountBrowseSession.feedRefreshWaitReason = ""
                        addLog("动态列表备用指纹确认成功：顶部标签当前不可见；保留页面身份并进入刷新验证")
                        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                    } else {
                        acceptConfirmedDynamicFeed(structuralFeed.reason, viaFallback = true)
                    }
                    return
                }
                if (tabEvidence.bounds != null) {
                    if (tabEvidence.selectionKnown && !tabEvidence.selected) {
                        val dynamicTargetBounds = tabEvidence.targetBounds
                        if (dynamicTargetBounds != null) {
                            submitNavigationClick(
                                tapBounds = tabEvidence.bounds,
                                targetBounds = dynamicTargetBounds,
                                beforeFingerprint = page.fingerprint,
                                actionKey = NAV_ACTION_DYNAMIC_TAB,
                                actionDescription = "首页顶部‘动态’标签",
                                expectedWindow = tabEvidence.targetWindow
                            )
                            return
                        }
                        reason = "已确认首页顶部动态导航行，但没有找到可安全点击的‘动态’控件"
                    } else {
                        val feed = socialBrowsePort.inspectFeed()
                        val shouldVerifyUnknownSelectionByClick =
                            !tabEvidence.selectionKnown &&
                                !accountBrowseSession.dynamicTabClickConfirmed &&
                                accountBrowseSession.recoveryDynamicClickAttempts < MAX_RECOVERY_TAB_CLICK_ATTEMPTS
                        if (shouldVerifyUnknownSelectionByClick) {
                            val dynamicTargetBounds = tabEvidence.targetBounds
                            if (dynamicTargetBounds != null) {
                                addLog("动态标签选中状态未被系统明确暴露；为确保进入目标页，主动点击已由同排导航结构确认的‘动态’并验证结果")
                                submitNavigationClick(
                                    tapBounds = tabEvidence.bounds,
                                    targetBounds = dynamicTargetBounds,
                                    beforeFingerprint = page.fingerprint,
                                    actionKey = NAV_ACTION_DYNAMIC_TAB,
                                    actionDescription = "首页顶部‘动态’标签",
                                    expectedWindow = tabEvidence.targetWindow
                                )
                                return
                            }
                            reason = "已找到首页顶部动态导航行，但动态标签没有安全可点击的节点；不把当前列表直接认定为目标页"
                        }
                        if (feed.ready &&
                            (tabEvidence.selectionKnown || accountBrowseSession.dynamicTabClickConfirmed)
                        ) {
                            if (accountBrowseSession.feedRefreshRecoveryActive) {
                                accountBrowsePhase = AccountBrowsePhase.REFRESH_DYNAMIC_FEED
                                accountBrowseSession.feedRefreshWaitReason = ""
                                addLog(
                                    "已通过动态标签主指纹确认列表；进入首页连续双击刷新验证，不使用旧列表位置继续滑动"
                                )
                                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                                return
                            }
                            acceptConfirmedDynamicFeed(feed.reason, viaFallback = false)
                            return
                        }
                        if (!shouldVerifyUnknownSelectionByClick || accountBrowseSession.dynamicTabClickConfirmed) {
                            reason = feed.reason
                        }
                    }
                } else if (tabEvidence.homeTabBounds != null) {
                    val homeTargetBounds = tabEvidence.homeTabTargetBounds
                    if (homeTargetBounds != null) {
                        if (tabEvidence.homeSelectionKnown && tabEvidence.homeSelected) {
                            reason = "已确认底部‘首页’处于选中状态，但首页顶部动态导航行尚未出现；等待页面内容加载"
                        } else {
                            submitNavigationClick(
                                tapBounds = tabEvidence.homeTabBounds,
                                targetBounds = homeTargetBounds,
                                beforeFingerprint = page.fingerprint,
                                actionKey = NAV_ACTION_HOME_TAB,
                                actionDescription = "陌陌底部‘首页’标签",
                                expectedWindow = tabEvidence.targetWindow
                            )
                            return
                        }
                    } else {
                        reason = "识别到陌陌底部‘首页’导航，但没有找到可安全点击的控件"
                    }
                } else if (isKnownMomoChildPage()) {
                    if (accountBrowseSession.recoveryBackAttempts >= MAX_RECOVERY_BACK_STEPS) {
                        pauseAccountBrowse("已连续返回 $MAX_RECOVERY_BACK_STEPS 次，仍无法识别动态标签页；停止导航以避免误退出陌陌")
                        return
                    }
                    submitNavigationBack(page.fingerprint)
                    return
                } else {
                    reason = when {
                        socialBrowsePort.isMainActivity(accountBrowseSession.currentActivityClass) ->
                            "当前位于陌陌主 Activity，但无障碍节点暂未暴露可验证的首页/动态导航控件"
                        accountBrowseSession.currentActivityClass.isBlank() ->
                            "尚未取得可靠的陌陌 Activity 信息，也没有识别到动态导航行或底部首页导航"
                        else ->
                            "当前页面层级无法确认；不执行猜测性返回或点击"
                    }
                }
            }
        }

        if (reason.isNotBlank() && reason != accountBrowseSession.lastDynamicTabWaitReason) {
            accountBrowseSession.lastDynamicTabWaitReason = reason
            addLog("动态标签定位等待：$reason")
        }
        if (System.currentTimeMillis() >= accountBrowseSession.deadlineAt) {
            // A failed fingerprint is not proof that navigation occurred. Keep the business
            // phase and wait for a fresh snapshot; do not guess-click, BACK, or terminate.
            accountBrowseSession.deadlineAt =
                System.currentTimeMillis() + INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS
            if (reason != accountBrowseSession.lastDynamicTabWaitReason) {
                addLog("动态页所有识别路径暂时未匹配；这不代表页面已切换。保留当前业务状态，继续重试新鲜快照，不执行猜测性点击/BACK，也不终止任务")
            }
        }
        scheduleAccountBrowseStep(maxOf(PAGE_STATE_POLL_MS, 750L))
    }

    private fun isKnownMomoChildPage(): Boolean {
        val imageBrowserKnown = accountBrowseSession.imageBrowserActive
        if (imageBrowserKnown && socialBrowsePort.isImageBrowserActivity(accountBrowseSession.currentActivityClass)) {
            return true
        }
        // A stored detail fingerprint describes where the workflow was, not where the
        // foreground window is now. Only a fresh structural identity may authorize recovery BACK.
        val currentPage = socialBrowsePort.inspectPage()
        return recoveryExecutor.isConfirmedDetailPage(currentPage)
    }

    private fun submitNavigationClick(
        tapBounds: ScreenRect?,
        targetBounds: ScreenRect?,
        beforeFingerprint: String,
        actionKey: String,
        actionDescription: String,
        expectedWindow: WindowRef?
    ) {
        val tap = navigationExecutor.createCoordinateTap(
            preferredBounds = tapBounds,
            clickableBounds = targetBounds,
            description = actionDescription,
            expectedWindow = expectedWindow
        )
        if (tap == null) {
            accountBrowseSession.navigationActionKey = ""
            accountBrowseSession.navigationBeforeActivity = ""
            accountBrowseSession.navigationBeforeFingerprint = ""
            accountBrowseSession.navigationActionDeadlineAt = 0L
            accountBrowseSession.navigationActionAttempts = 0
            accountBrowseSession.programmaticActionLabel = ""
            accountBrowsePhase = if (accountBrowseSession.feedRefreshRecoveryActive) {
                AccountBrowsePhase.REFRESH_DYNAMIC_FEED
            } else {
                AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            }
            addLog("导航控件‘$actionDescription’的安全坐标/窗口上下文暂时不可用；未执行点击、不消耗导航次数，重新获取快照后再尝试")
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        val beforeActivity = accountBrowseSession.currentActivityClass
        val attempt = when (actionKey) {
            NAV_ACTION_DYNAMIC_TAB -> ++accountBrowseSession.recoveryDynamicClickAttempts
            NAV_ACTION_HOME_TAB -> ++accountBrowseSession.recoveryHomeClickAttempts
            else -> 1
        }
        val maxAttempts = if (actionKey == NAV_ACTION_DYNAMIC_TAB) {
            MAX_RECOVERY_TAB_CLICK_ATTEMPTS
        } else {
            MAX_RECOVERY_HOME_CLICK_ATTEMPTS
        }
        if (attempt > maxAttempts) {
            pauseAccountBrowse("导航控件‘$actionDescription’连续 $maxAttempts 次未能验证进入；已停止重复点击")
            return
        }

        val actionAt = System.currentTimeMillis()
        accountBrowseSession.programmaticActionLabel = "Freedom COORDINATE_TAP $actionDescription"
        accountBrowseSession.ignoreManualEventsUntil = actionAt + NAVIGATION_ACTION_EVENT_WINDOW_MS
        accountBrowseSession.navigationActionKey = actionKey
        accountBrowseSession.navigationBeforeActivity = beforeActivity
        accountBrowseSession.navigationBeforeFingerprint = beforeFingerprint
        accountBrowseSession.navigationActionAttempts = attempt
        accountBrowseSession.navigationActionDeadlineAt = actionAt + NAVIGATION_ACTION_VERIFY_TIMEOUT_MS
        accountBrowseSession.gestureInFlight = true
        accountBrowsePhase = AccountBrowsePhase.VERIFY_NAVIGATION_ACTION

        val submission = runCatching {
            gesturePolicy.execute(tap.request) { result ->
                accountBrowseSession.gestureInFlight = false
                if (accountBrowsePhase != AccountBrowsePhase.VERIFY_NAVIGATION_ACTION || !isMonitoringRunning()) {
                    return@execute
                }
                if (result.status == GestureExecutionStatus.DEFERRED) {
                    accountBrowseSession.navigationActionKey = ""
                    accountBrowseSession.navigationBeforeActivity = ""
                    accountBrowseSession.navigationBeforeFingerprint = ""
                    accountBrowseSession.navigationActionDeadlineAt = 0L
                    accountBrowseSession.programmaticActionLabel = ""
                    accountBrowseSession.ignoreManualEventsUntil = 0L
                    accountBrowsePhase = if (accountBrowseSession.feedRefreshRecoveryActive) {
                        AccountBrowsePhase.REFRESH_DYNAMIC_FEED
                    } else {
                        AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
                    }
                    addLog("‘$actionDescription’平台派发前延期；保留当前业务阶段和重试预算，等待新快照")
                    scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                    return@execute
                }
                if (result.status != GestureExecutionStatus.COMPLETED) {
                    val interruptedByTouch = accountBrowseSession.touchStartedDuringGesture ||
                        accountBrowseSession.touchEndedDuringGesture || accountBrowseSession.touchActive
                    pauseAccountBrowse(
                        "‘$actionDescription’坐标手势被系统或用户操作中断；停止本次自动导航",
                        allowManualResume = interruptedByTouch
                    )
                    return@execute
                }
                accountBrowseSession.touchStartedDuringGesture = false
                accountBrowseSession.touchEndedDuringGesture = false
                addLog("Android 已完成‘$actionDescription’的可视化坐标点击；目标边界=${(tap.request.intent as GestureIntent.Tap).targetBounds.toShortString()}；等待重新扫描页面验证导航结果")
                scheduleAccountBrowseStep(NAVIGATION_ACTION_VERIFY_POLL_MS)
            }
        }.getOrDefault(GestureSubmission.REJECTED)

        if (submission == GestureSubmission.DEFERRED_NOT_READY) {
            accountBrowseSession.gestureInFlight = false
            when (actionKey) {
                NAV_ACTION_DYNAMIC_TAB -> accountBrowseSession.recoveryDynamicClickAttempts =
                    (accountBrowseSession.recoveryDynamicClickAttempts - 1).coerceAtLeast(0)
                NAV_ACTION_HOME_TAB -> accountBrowseSession.recoveryHomeClickAttempts =
                    (accountBrowseSession.recoveryHomeClickAttempts - 1).coerceAtLeast(0)
            }
            accountBrowseSession.navigationActionKey = ""
            accountBrowseSession.navigationBeforeActivity = ""
            accountBrowseSession.navigationBeforeFingerprint = ""
            accountBrowseSession.navigationActionDeadlineAt = 0L
            accountBrowseSession.navigationActionAttempts = 0
            accountBrowseSession.programmaticActionLabel = ""
            accountBrowseSession.ignoreManualEventsUntil = 0L
            accountBrowsePhase = if (accountBrowseSession.feedRefreshRecoveryActive) {
                AccountBrowsePhase.REFRESH_DYNAMIC_FEED
            } else {
                AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            }
            addLog("‘$actionDescription’因页面暂时被遮挡/尚未就绪而延期；没有派发点击、没有消耗重试次数，稍后重新识别同一业务页面")
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        if (submission != GestureSubmission.ACCEPTED) {
            accountBrowseSession.gestureInFlight = false
            pauseAccountBrowse("Android 拒绝了‘$actionDescription’的可视化坐标点击；没有继续尝试其他控件")
            return
        }

        addLog(
            "已提交‘$actionDescription’的坐标点击（第 $attempt/$maxAttempts 次）；" +
                "最终点击坐标由统一策略编译，目标边界=${(tap.request.intent as GestureIntent.Tap).targetBounds.toShortString()}，" +
                "目标可点击范围=${targetBounds?.toShortString() ?: "系统未提供"}；" +
                "手势轨迹已显示，等待系统完成回调和页面导航验证"
        )
    }

    private fun submitNavigationBack(beforeFingerprint: String) {
        val beforeActivity = accountBrowseSession.currentActivityClass
        accountBrowseSession.recoveryBackAttempts++
        accountBrowseSession.programmaticActionLabel = "Freedom GLOBAL_ACTION_BACK 页面层级返回"
        accountBrowseSession.ignoreManualEventsUntil = System.currentTimeMillis() + NAVIGATION_ACTION_EVENT_WINDOW_MS
        val accepted = runCatching {
            accessibilityGateway.performGlobalAction(GlobalAccessibilityAction.BACK)
        }.getOrDefault(false)
        if (!accepted) {
            pauseAccountBrowse("当前已识别为陌陌子页面，但系统未接受返回操作；停止自动导航")
            return
        }

        accountBrowseSession.navigationActionKey = NAV_ACTION_BACK
        accountBrowseSession.navigationBeforeActivity = beforeActivity
        accountBrowseSession.navigationBeforeFingerprint = beforeFingerprint
        accountBrowseSession.navigationActionAttempts = accountBrowseSession.recoveryBackAttempts
        accountBrowseSession.navigationActionDeadlineAt = System.currentTimeMillis() + NAVIGATION_ACTION_VERIFY_TIMEOUT_MS
        accountBrowsePhase = AccountBrowsePhase.VERIFY_NAVIGATION_ACTION
        addLog(
            "当前页面识别为陌陌子页面（Activity=${beforeActivity.ifBlank { "未知" }}）；" +
                "已提交第 ${accountBrowseSession.recoveryBackAttempts}/$MAX_RECOVERY_BACK_STEPS 次返回；" +
                "等待验证返回后的页面层级"
        )
        scheduleAccountBrowseStep(NAVIGATION_ACTION_VERIFY_POLL_MS)
    }

    private fun verifyNavigationAction() {
        val page = socialBrowsePort.inspectPage()
        if (!socialBrowsePort.supportsTargetPackage(page.packageName)) {
            if (System.currentTimeMillis() >= accountBrowseSession.navigationActionDeadlineAt) {
                accountBrowseSession.navigationActionKey = ""
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            }
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        if (handleMomoPopup()) return

        val currentActivity = page.activityClass.ifBlank { accountBrowseSession.currentActivityClass }
        val currentFingerprint = page.fingerprint
        val actionKey = accountBrowseSession.navigationActionKey
        val tabEvidence = if (actionKey == NAV_ACTION_DYNAMIC_TAB) {
            socialBrowsePort.inspectDynamicTab()
        } else null
        val feedConfirmed = if (actionKey == NAV_ACTION_DYNAMIC_TAB || actionKey == NAV_ACTION_HOME_TAB) {
            socialBrowsePort.inspectFeed().ready
        } else false

        val structureChanged = currentFingerprint.isNotBlank() &&
            currentFingerprint != accountBrowseSession.navigationBeforeFingerprint
        val backPageEvidence = if (actionKey == NAV_ACTION_BACK) {
            val tabs = socialBrowsePort.inspectDynamicTab()
            page.confidence >= 80 && !isRetainedObstructionEvidence(page.reason) &&
                (page.pageKind == BrowsePageKind.HOME || page.pageKind == BrowsePageKind.DYNAMIC_FEED ||
                    (tabs.bounds != null && structureChanged))
        } else false
        val confirmed = when (actionKey) {
            NAV_ACTION_DYNAMIC_TAB -> feedConfirmed ||
                (tabEvidence?.let { it.selectionKnown && it.selected } == true) ||
                (tabEvidence?.bounds != null && structureChanged)
            NAV_ACTION_HOME_TAB -> feedConfirmed ||
                (page.pageKind == BrowsePageKind.HOME && page.confidence >= 80 &&
                    !isRetainedObstructionEvidence(page.reason) && tabEvidence?.bounds != null) ||
                (tabEvidence?.bounds != null && structureChanged)
            NAV_ACTION_BACK -> backPageEvidence
            else -> false
        }

        if (confirmed) {
            if (actionKey == NAV_ACTION_DYNAMIC_TAB) {
                accountBrowseSession.dynamicTabClickConfirmed = true
            }
            addLog("导航结果已验证：动作=$actionKey；Activity=${currentActivity.ifBlank { "未知" }}；页面结构已重新识别")
            accountBrowseSession.navigationActionKey = ""
            accountBrowseSession.navigationBeforeActivity = ""
            accountBrowseSession.navigationBeforeFingerprint = ""
            accountBrowseSession.navigationActionDeadlineAt = 0L
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            accountBrowseSession.lastDynamicTabWaitReason = ""
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        if (System.currentTimeMillis() >= accountBrowseSession.navigationActionDeadlineAt) {
            val attempts = accountBrowseSession.navigationActionAttempts
            val maxAttempts = when (actionKey) {
                NAV_ACTION_BACK -> MAX_RECOVERY_BACK_STEPS
                NAV_ACTION_HOME_TAB -> MAX_RECOVERY_HOME_CLICK_ATTEMPTS
                NAV_ACTION_DYNAMIC_TAB -> MAX_RECOVERY_TAB_CLICK_ATTEMPTS
                else -> 1
            }
            addLog("导航结果未得到确认：动作=$actionKey；页面仍未出现可验证变化（$attempts/$maxAttempts 次）；重新分析当前页面后再决定下一步")
            accountBrowseSession.navigationActionKey = ""
            accountBrowseSession.navigationBeforeActivity = ""
            accountBrowseSession.navigationBeforeFingerprint = ""
            accountBrowseSession.navigationActionDeadlineAt = 0L
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            if (attempts >= maxAttempts && actionKey != NAV_ACTION_BACK) {
                pauseAccountBrowse("导航动作 $actionKey 连续 $maxAttempts 次未验证成功；已停止重复点击")
                return
            }
            if (actionKey == NAV_ACTION_BACK && accountBrowseSession.recoveryBackAttempts >= MAX_RECOVERY_BACK_STEPS) {
                pauseAccountBrowse("连续返回 $MAX_RECOVERY_BACK_STEPS 次后仍未能确认页面层级；已停止自动导航")
                return
            }
            scheduleAccountBrowseStep(NAVIGATION_ACTION_RETRY_DELAY_MS)
            return
        }

        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    /** The recovery path waits for the known Momo main Activity and a visible nav row. */
    private fun dynamicFeedRecoveryStartupWaitReason(): String? {
        val page = socialBrowsePort.inspectPage()
        if (!socialBrowsePort.supportsTargetPackage(page.packageName)) {
            return "Debug 恢复启动等待：当前无障碍根节点不属于陌陌（${page.packageName.ifBlank { "未提供" }}）"
        }
        val pageKind = page.pageKind
        if (pageKind != BrowsePageKind.HOME && pageKind != BrowsePageKind.DYNAMIC_FEED) {
            return "Debug 恢复启动等待：尚未通过首页/动态页结构指纹确认；当前 Activity=${page.activityClass.ifBlank { "尚未报告" }}；pageKind=$pageKind"
        }

        if (page.confidence < 80 || isRetainedObstructionEvidence(page.reason)) {
            return "已确认页面身份仅为短期上下文，当前页面仍被遮挡；等待新鲜指纹，不授权刷新或导航"
        }
        val feedReady = pageKind == BrowsePageKind.DYNAMIC_FEED && socialBrowsePort.inspectFeed().ready
        val tabEvidence = socialBrowsePort.inspectDynamicTab()
        if (!feedReady && tabEvidence.bounds == null && tabEvidence.homeTabBounds == null) {
            return "Debug 恢复启动等待：首页/动态页备用指纹尚未确认，且导航节点暂不可见；继续重新扫描"
        }

        accountBrowseSession.feedRefreshStartupReady = true
        addLog(
            "恢复启动检查通过：已确认陌陌首页/动态页结构指纹、当前无障碍根节点和导航结构；" +
                "继续定位顶部‘动态’并验证可见列表，列表未就绪前不会执行首页双击"
        )
        return null
    }

    private fun waitForMomoFeedStable() {
        val page = socialBrowsePort.inspectPage()
        if (!socialBrowsePort.supportsTargetPackage(page.packageName)) {
            accountBrowseSession.lastStableFingerprint = ""
            accountBrowseSession.stableSamples = 0
            scheduleOrTimeoutFeedStability()
            return
        }
        if (handleMomoPopup()) return
        if (isRetainedObstructionEvidence(page.reason)) {
            addLog("动态页已由先前成功指纹确认；当前识别锚点被遮挡，保留任务并等待遮挡消失，不执行滑动")
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        if (page.pageKind == BrowsePageKind.DYNAMIC_FEED && !matchDynamicFeedRoute(page)) {
            accountBrowseRuntime.observeRecoveryPage(
                page.copy(reason = "DYNAMIC_FEED_ROUTE_NOT_MATCHED")
            )
            addLog("动态列表页面指纹未匹配统一路由；保持暂停并等待新页面快照")
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        val inspection = socialBrowsePort.inspectFeed()
        val fingerprint = inspection.fingerprint.takeIf { inspection.ready && it.isNotBlank() }
        if (fingerprint == null) {
            accountBrowseSession.lastStableFingerprint = ""
            accountBrowseSession.stableSamples = 0
            accountBrowseRuntime.observeDynamicFeedFingerprint("")
            addLog("动态列表尚不具备操作条件：${inspection.reason}")
        } else {
            val recovered = accountBrowseRuntime.observeDynamicFeedFingerprint(fingerprint)
            accountBrowseSession.lastStableFingerprint = fingerprint
            accountBrowseSession.stableSamples = accountBrowseRuntime.recoveryStableSamples()
            addLog("动态列表采样：可见项目=${inspection.visibleCardCount}，稳定计数=${accountBrowseSession.stableSamples}/$REQUIRED_STABLE_FEED_SAMPLES；事务状态=${accountBrowseRuntime.recoveryState().name}；${inspection.reason}")
            if (recovered) {
                accountBrowseSession.stableSamples = REQUIRED_STABLE_FEED_SAMPLES
            }
        }

        if (accountBrowseRuntime.recoveryState() == com.freedom.features.recovery.RecoveryTransactionState.ACTIVE &&
            accountBrowseSession.stableSamples >= REQUIRED_STABLE_FEED_SAMPLES
        ) {
            accountBrowseSession.swipeCount = 0
            addLog("动态浏览恢复验证完成：已确认动态列表连续稳定 ${REQUIRED_STABLE_FEED_SAMPLES} 次；恢复自主浏览")
            accountBrowsePhase = AccountBrowsePhase.FIRST_SWIPE_COUNTDOWN
            accountBrowseSession.countdownRemaining = FIRST_SWIPE_COUNTDOWN_SECONDS
            addLog(
                "已连续确认陌陌动态列表稳定 ${REQUIRED_STABLE_FEED_SAMPLES} 次；" +
                    "即将开始持续滑动循环，现在开始首次滑动倒计时"
            )
            if (!showFirstSwipeCountdown(accountBrowseSession.countdownRemaining)) {
                pauseAccountBrowse("无法显示首次滑动倒计时提示；为避免无提示操作，已暂停")
                return
            }
            addLog("首次滑动倒计时：${accountBrowseSession.countdownRemaining} 秒")
            scheduleAccountBrowseStep(FIRST_SWIPE_COUNTDOWN_STEP_MS)
            return
        }
        scheduleOrTimeoutFeedStability()
    }

    private fun scheduleOrTimeoutFeedStability() {
        if (System.currentTimeMillis() >= accountBrowseSession.deadlineAt) {
            accountBrowseSession.deadlineAt =
                System.currentTimeMillis() + INITIAL_FEED_STABILITY_TIMEOUT_MS
            addLog("动态列表尚未取得可操作快照；超时仅用于诊断，不终止任务。继续等待遮挡消失/列表加载并重新识别")
        }
        scheduleAccountBrowseStep(maxOf(PAGE_STATE_POLL_MS, 750L))
    }

    /**
     * A mismatched return snapshot is not treated as a permanent stop. Re-identify
     * Home -> Dynamic, then use Momo's own Home-tab reselect refresh and verify it.
     */
    private fun beginDynamicFeedRefreshRecovery(reason: String) {
        accountBrowseRuntime.cancelScheduledStep()
        overlayPort.clearGestureTrail()
        restoreMonitorOverlayTouchPassthrough("动态页面刷新恢复准备")
        accountBrowseSession.feedRefreshRecoveryActive = true
        accountBrowseSession.feedRefreshStartupReady = false
        accountBrowseSession.feedRefreshAttempts = 0
        accountBrowseSession.feedRefreshBeforeSnapshot = ""
        accountBrowseSession.feedRefreshLastSnapshot = ""
        accountBrowseSession.feedRefreshStableSamples = 0
        accountBrowseSession.feedRefreshRecyclerContentChangeEvents = 0
        accountBrowseSession.feedRefreshActionAt = 0L
        accountBrowseSession.feedRefreshVerificationDeadlineAt = 0L
        accountBrowseSession.feedRefreshWaitReason = ""
        accountBrowseSession.navigationActionKey = ""
        accountBrowseSession.navigationBeforeActivity = ""
        accountBrowseSession.navigationBeforeFingerprint = ""
        accountBrowseSession.navigationActionDeadlineAt = 0L
        accountBrowseSession.navigationActionAttempts = 0
        accountBrowseSession.recoveryBackAttempts = 0
        accountBrowseSession.recoveryHomeClickAttempts = 0
        accountBrowseSession.recoveryDynamicClickAttempts = 0
        accountBrowseSession.dynamicTabClickConfirmed = false
        accountBrowseSession.lastDynamicTabWaitReason = ""
        accountBrowseSession.deadlineAt = System.currentTimeMillis() + INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
        addLog(
            "$reason；启动主动恢复等待：先确认陌陌主 Activity、无障碍根节点和导航结构，" +
                "再验证顶部‘动态’列表可用，最后才执行首页连续双击刷新"
        )
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    /** Submit two actual tap strokes at the verified bottom Home tab. */
    private fun refreshDynamicFeed() {
        if (!accountBrowseSession.feedRefreshRecoveryActive) {
            pauseAccountBrowse("动态刷新恢复状态丢失；没有执行滑动")
            return
        }
        if (accountBrowseSession.feedRefreshAttempts >= MAX_DYNAMIC_FEED_REFRESH_ATTEMPTS) {
            accountBrowseSession.feedRefreshRecoveryActive = false
            pauseAccountBrowse("首页连续双击刷新已达到最大尝试次数；没有确认动态列表重新加载，因此未继续滑动")
            return
        }

        val page = socialBrowsePort.inspectPage()
        if (!socialBrowsePort.supportsTargetPackage(page.packageName)) {
            scheduleDynamicFeedRefreshNavigationWait("当前前台不是陌陌")
            return
        }
        if (handleMomoPopup()) return

        val tabEvidence = socialBrowsePort.inspectDynamicTab()
        val readiness = socialBrowsePort.inspectFeed()
        val dynamicTabConfirmed = (tabEvidence.bounds != null && (
            (tabEvidence.selectionKnown && tabEvidence.selected) ||
                (!tabEvidence.selectionKnown && accountBrowseSession.dynamicTabClickConfirmed)
            )) || (page.pageKind == BrowsePageKind.DYNAMIC_FEED && page.confidence >= 80 &&
            !isRetainedObstructionEvidence(page.reason) && readiness.ready)
        if (!readiness.ready || !dynamicTabConfirmed) {
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        val homeBounds = tabEvidence.homeTabBounds
        val homeTargetBounds = tabEvidence.homeTabTargetBounds
        if (homeBounds == null) {
            scheduleDynamicFeedRefreshNavigationWait("没有识别到底部‘首页’导航控件")
            return
        }
        if (tabEvidence.homeSelectionKnown && !tabEvidence.homeSelected) {
            if (homeTargetBounds == null) {
                scheduleDynamicFeedRefreshNavigationWait("底部‘首页’未选中，但没有找到可安全点击的首页控件范围")
                return
            }
            addLog("已确认底部当前不是‘首页’；先单击底部‘首页’并验证导航，再执行连续双击刷新")
            submitNavigationClick(
                tapBounds = homeBounds,
                targetBounds = homeTargetBounds,
                beforeFingerprint = page.fingerprint,
                actionKey = NAV_ACTION_HOME_TAB,
                actionDescription = "陌陌底部‘首页’标签",
                expectedWindow = tabEvidence.targetWindow
            )
            return
        }
        if (homeTargetBounds == null) {
            scheduleDynamicFeedRefreshNavigationWait("已识别底部‘首页’，但没有找到可点击的首页控件范围")
            return
        }

        val targetBounds = homeTargetBounds
        val metrics = displayMetrics
        val displayBounds = ScreenRect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if (targetBounds == null || targetBounds.isEmpty) {
            scheduleDynamicFeedRefreshNavigationWait("首页文字位置不在可点击控件范围内；拒绝盲目点击")
            return
        }
        if (readiness.scrollBounds == null) {
            scheduleDynamicFeedRefreshNavigationWait("动态列表容器尚未出现")
            return
        }
        val beforeSnapshot = socialBrowsePort.captureVisibleFeedSnapshot(listOnly = true)
        if (beforeSnapshot.isBlank()) {
            scheduleDynamicFeedRefreshNavigationWait("当前动态列表没有可读取的可见内容；暂不执行刷新点击")
            return
        }
        if (!enableMonitorOverlayTouchPassthrough("首页连续双击刷新")) {
            accountBrowseSession.feedRefreshRecoveryActive = false
            pauseAccountBrowse("浮动按钮无法启用触摸穿透；首页双击刷新未执行")
            return
        }


        accountBrowseSession.feedRefreshAttempts++
        accountBrowseSession.feedRefreshBeforeSnapshot = beforeSnapshot
        accountBrowseSession.feedRefreshLastSnapshot = ""
        accountBrowseSession.feedRefreshStableSamples = 0
        accountBrowseSession.feedRefreshRecyclerContentChangeEvents = 0
        accountBrowseSession.feedRefreshActionAt = System.currentTimeMillis()
        accountBrowseSession.feedRefreshVerificationDeadlineAt = accountBrowseSession.feedRefreshActionAt + DYNAMIC_FEED_REFRESH_VERIFY_TIMEOUT_MS
        accountBrowseSession.feedRefreshWaitReason = ""
        accountBrowsePhase = AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH
        accountBrowseSession.programmaticActionLabel = "Freedom COORDINATE_DOUBLE_TAP 首页刷新动态"
        accountBrowseSession.ignoreManualEventsUntil = accountBrowseSession.feedRefreshActionAt + HOME_REFRESH_PROGRAMMATIC_EVENT_WINDOW_MS
        accountBrowseSession.gestureInFlight = true
        accountBrowseSession.touchStartedDuringGesture = false
        accountBrowseSession.touchEndedDuringGesture = false
        addLog(
            "已确认陌陌首页内顶部‘动态’页面；底部导航证据=${tabEvidence.reason}；" +
                "对底部‘首页’执行第 ${accountBrowseSession.feedRefreshAttempts}/$MAX_DYNAMIC_FEED_REFRESH_ATTEMPTS 次连续双击，" +
                "目标控件边界=${targetBounds.toShortString()}，两次点击间隔=${HOME_REFRESH_TAP_INTERVAL_MS - HOME_REFRESH_TAP_DURATION_MS} 毫秒；" +
                "开始等待动态列表重新加载证据"
        )
        val executionContext = tabEvidence.targetWindow?.let { sourceWindow ->
            gesturePolicy.captureContext(
                expectedWindow = sourceWindow,
                expectedCoordinates = metrics.toGestureCoordinateContext()
            )
        } ?: run {
            accountBrowseSession.gestureInFlight = false
            accountBrowseSession.touchStartedDuringGesture = false
            accountBrowseSession.touchEndedDuringGesture = false
            accountBrowseSession.feedRefreshAttempts =
                (accountBrowseSession.feedRefreshAttempts - 1).coerceAtLeast(0)
            accountBrowseSession.feedRefreshBeforeSnapshot = ""
            accountBrowseSession.feedRefreshVerificationDeadlineAt = 0L
            accountBrowseSession.feedRefreshWaitReason = ""
            accountBrowseSession.programmaticActionLabel = ""
            restoreMonitorOverlayTouchPassthrough("首页双击刷新上下文暂时失效")
            accountBrowsePhase = AccountBrowsePhase.REFRESH_DYNAMIC_FEED
            addLog("首页双击刷新窗口/显示上下文在派发前发生变化；未消耗刷新次数、拒绝旧坐标，等待新快照后重新确认")
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        val gestureRequest = GestureIntentRequest(
            id = "accountbrowse-refresh-${accountBrowseSession.feedRefreshAttempts}-${System.nanoTime()}",
            intent = GestureIntent.DoubleTap(
                preferredBounds = homeBounds,
                targetBounds = targetBounds,
                durationMs = HOME_REFRESH_TAP_DURATION_MS,
                intervalMs = HOME_REFRESH_TAP_INTERVAL_MS,
                displayBounds = displayBounds
            ),
            description = "陌陌首页动态刷新",
            executionContext = executionContext
        )
        val submission = runCatching {
            gesturePolicy.execute(gestureRequest) { result ->
                if (result.status == GestureExecutionStatus.DEFERRED) {
                    accountBrowseSession.gestureInFlight = false
                    accountBrowseSession.programmaticActionLabel = ""
                    accountBrowseSession.feedRefreshVerificationDeadlineAt = 0L
                    restoreMonitorOverlayTouchPassthrough("首页刷新在平台派发前延期")
                    accountBrowsePhase = AccountBrowsePhase.REFRESH_DYNAMIC_FEED
                    addLog("首页刷新在平台派发前延期；不判定刷新失败，等待重新识别后重试")
                    scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                } else if (result.status == GestureExecutionStatus.COMPLETED) {
                    accountBrowseSession.gestureInFlight = false
                    accountBrowseSession.touchStartedDuringGesture = false
                    accountBrowseSession.touchEndedDuringGesture = false
                    restoreMonitorOverlayTouchPassthrough("首页连续双击刷新手势完成")
                    if (accountBrowsePhase != AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH ||
                        !isMonitoringRunning()
                    ) return@execute
                    addLog("Android 已完成首页连续双击手势；正在检查动态列表是否确实重新加载")
                    scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                } else {
                    val touchStarted = accountBrowseSession.touchStartedDuringGesture
                    val touchEnded = accountBrowseSession.touchEndedDuringGesture
                    accountBrowseSession.gestureInFlight = false
                    accountBrowseSession.touchStartedDuringGesture = false
                    accountBrowseSession.touchEndedDuringGesture = false
                    restoreMonitorOverlayTouchPassthrough("首页连续双击刷新手势取消")
                    if (accountBrowsePhase != AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH) return@execute
                    val interruptedByTouch = touchStarted || touchEnded
                    if (interruptedByTouch) {
                        pauseAccountBrowse("首页连续双击刷新被屏幕触摸中断；等待用户操作结束后重新识别页面", allowManualResume = true)
                        if (touchEnded) {
                            accountBrowseSession.touchActive = false
                            cancelManualTouchResume()
                            scheduleManualTouchResume()
                        } else {
                            accountBrowseSession.touchActive = true
                        }
                    } else {
                        accountBrowseSession.feedRefreshRecoveryActive = false
                        pauseAccountBrowse("Android 取消了首页连续双击刷新手势；未确认动态列表已重新加载")
                    }
                }
            }
        }.getOrDefault(GestureSubmission.REJECTED)
        if (submission == GestureSubmission.DEFERRED_NOT_READY) {
            accountBrowseSession.gestureInFlight = false
            accountBrowseSession.feedRefreshAttempts =
                (accountBrowseSession.feedRefreshAttempts - 1).coerceAtLeast(0)
            accountBrowseSession.programmaticActionLabel = ""
            accountBrowseSession.feedRefreshVerificationDeadlineAt = 0L
            restoreMonitorOverlayTouchPassthrough("首页刷新因页面遮挡而延期")
            accountBrowsePhase = AccountBrowsePhase.REFRESH_DYNAMIC_FEED
            addLog("首页刷新手势因页面暂时不可操作而延期；没有派发手势或消耗刷新次数，等待遮挡消失后重新识别")
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        if (submission != GestureSubmission.ACCEPTED) {
            accountBrowseSession.gestureInFlight = false
            restoreMonitorOverlayTouchPassthrough("首页连续双击刷新手势提交失败")
            accountBrowseSession.feedRefreshRecoveryActive = false
            pauseAccountBrowse("Android 拒绝首页连续双击刷新手势；没有执行后续滑动")
        }
    }

    /** Verify a real RecyclerView reload signal and a stable, recognizable Dynamic feed. */
    private fun verifyDynamicFeedRefresh() {
        if (!accountBrowseSession.feedRefreshRecoveryActive) {
            pauseAccountBrowse("动态刷新验证状态丢失；没有执行滑动")
            return
        }
        val page = socialBrowsePort.inspectPage()
        if (!socialBrowsePort.supportsTargetPackage(page.packageName)) {
            scheduleOrRetryDynamicFeedRefresh("刷新期间陌陌不在前台")
            return
        }
        if (handleMomoPopup()) return

        val readiness = socialBrowsePort.inspectFeed()
        var readinessReason = readiness.reason
        val tabEvidence = socialBrowsePort.inspectDynamicTab()
        val dynamicTabConfirmed = (tabEvidence.bounds != null && (
            (tabEvidence.selectionKnown && tabEvidence.selected) ||
                (!tabEvidence.selectionKnown && accountBrowseSession.dynamicTabClickConfirmed)
            )) || (page.pageKind == BrowsePageKind.DYNAMIC_FEED && page.confidence >= 80 &&
            !isRetainedObstructionEvidence(page.reason) && readiness.ready)
        val homeConfirmed = (tabEvidence.homeTabBounds != null && (
            (tabEvidence.homeSelectionKnown && tabEvidence.homeSelected) ||
                (!tabEvidence.homeSelectionKnown && dynamicTabConfirmed)
            )) || (page.pageKind == BrowsePageKind.DYNAMIC_FEED && page.confidence >= 80 &&
            !isRetainedObstructionEvidence(page.reason) && readiness.ready)
        val currentSnapshot = if (readiness.ready && dynamicTabConfirmed && homeConfirmed &&
            readiness.scrollBounds != null
        ) socialBrowsePort.captureVisibleFeedSnapshot(listOnly = true) else ""
        val isDynamicFeedReady = currentSnapshot.isNotBlank()
        if (readiness.ready && !dynamicTabConfirmed) {
            readinessReason = "动态页面主/备用指纹尚未形成稳定的可操作列表证据"
        } else if (readiness.ready && !homeConfirmed) {
            readinessReason = "顶部动态列表存在，但底部首页选中状态未能确认"
        }

        if (!isDynamicFeedReady) {
            accountBrowseSession.feedRefreshStableSamples = 0
            accountBrowseSession.feedRefreshLastSnapshot = ""
            if (readinessReason != accountBrowseSession.feedRefreshWaitReason) {
                accountBrowseSession.feedRefreshWaitReason = readinessReason
                addLog("首页双击刷新验证等待：$readinessReason")
            }
        } else {
            accountBrowseSession.feedRefreshWaitReason = ""
            accountBrowseSession.feedRefreshStableSamples = if (currentSnapshot == accountBrowseSession.feedRefreshLastSnapshot) {
                accountBrowseSession.feedRefreshStableSamples + 1
            } else {
                1
            }
            accountBrowseSession.feedRefreshLastSnapshot = currentSnapshot
        }

        val snapshotChanged = accountBrowseSession.feedRefreshBeforeSnapshot.isNotBlank() &&
            currentSnapshot.isNotBlank() && currentSnapshot != accountBrowseSession.feedRefreshBeforeSnapshot
        val reloadEvidence = FeedRefreshVerificationPolicy.hasReloadEvidence(
            snapshotChanged = snapshotChanged,
            recyclerContentChangeEvents = accountBrowseSession.feedRefreshRecyclerContentChangeEvents
        )
        if (isDynamicFeedReady) {
            addLog(
                "刷新验证采样：动态页面=已确认；" +
                    "列表内容变化=${if (snapshotChanged) "是" else "否"}；" +
                    "RecyclerView 内容更新事件=${accountBrowseSession.feedRefreshRecyclerContentChangeEvents}；" +
                    "列表稳定=${accountBrowseSession.feedRefreshStableSamples}/$REQUIRED_DYNAMIC_REFRESH_STABLE_SAMPLES；" +
                    "刷新证据=${if (reloadEvidence) "已取得" else "不足"}"
            )
        }
        if (isDynamicFeedReady && reloadEvidence &&
            accountBrowseSession.feedRefreshStableSamples >= REQUIRED_DYNAMIC_REFRESH_STABLE_SAMPLES
        ) {
            completeDynamicFeedRefreshRecovery(snapshotChanged)
            return
        }
        if (System.currentTimeMillis() >= accountBrowseSession.feedRefreshVerificationDeadlineAt) {
            scheduleOrRetryDynamicFeedRefresh("首页连续双击后未取得足够的动态列表刷新证据")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun scheduleDynamicFeedRefreshNavigationWait(reason: String) {
        if (reason != accountBrowseSession.feedRefreshWaitReason) {
            accountBrowseSession.feedRefreshWaitReason = reason
            addLog("首页双击刷新准备等待：$reason")
        }
        if (System.currentTimeMillis() >= accountBrowseSession.deadlineAt) {
            accountBrowseSession.feedRefreshRecoveryActive = false
            pauseAccountBrowse("无法确认陌陌底部‘首页’和顶部‘动态’导航控件；主动刷新恢复超时")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun scheduleOrRetryDynamicFeedRefresh(reason: String) {
        if (System.currentTimeMillis() < accountBrowseSession.feedRefreshVerificationDeadlineAt) {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        when (
            FeedRefreshVerificationPolicy.timeoutAction(
                completedAttempts = accountBrowseSession.feedRefreshAttempts,
                maxAttempts = MAX_DYNAMIC_FEED_REFRESH_ATTEMPTS
            )
        ) {
            FeedRefreshVerificationPolicy.TimeoutAction.RETRY -> {
                addLog(
                    "$reason；第 ${accountBrowseSession.feedRefreshAttempts} 次首页连续双击未能完成验证；" +
                        "重新识别首页/动态标签后再进行最后一次刷新尝试"
                )
                accountBrowseSession.feedRefreshLastSnapshot = ""
                accountBrowseSession.feedRefreshStableSamples = 0
                accountBrowseSession.feedRefreshRecyclerContentChangeEvents = 0
                accountBrowseSession.feedRefreshWaitReason = ""
                accountBrowseSession.recoveryBackAttempts = 0
                accountBrowseSession.recoveryHomeClickAttempts = 0
                accountBrowseSession.recoveryDynamicClickAttempts = 0
                accountBrowseSession.dynamicTabClickConfirmed = false
                accountBrowseSession.lastDynamicTabWaitReason = ""
                accountBrowseSession.deadlineAt = System.currentTimeMillis() + INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            }

            FeedRefreshVerificationPolicy.TimeoutAction.SAFE_PAUSE -> {
                accountBrowseSession.feedRefreshRecoveryActive = false
                pauseAccountBrowse(
                    "已尝试 $MAX_DYNAMIC_FEED_REFRESH_ATTEMPTS 次首页连续双击，但仍无法确认动态列表重新加载并稳定；" +
                        "为避免在未知页面滑动，已安全暂停"
                )
            }
        }
    }

    private fun completeDynamicFeedRefreshRecovery(snapshotChanged: Boolean) {
        val attempts = accountBrowseSession.feedRefreshAttempts
        val events = accountBrowseSession.feedRefreshRecyclerContentChangeEvents
        accountBrowseSession.feedRefreshRecoveryActive = false
        accountBrowseSession.detailActivityClass = ""
        accountBrowseSession.detailFingerprint = ""
        accountBrowseSession.programmaticActionLabel = ""
        accountBrowseSession.detailDwellDueAt = 0L
        accountBrowseSession.detailDwellStartedAt = 0L
        accountBrowseSession.detailDwellRemainingMs = 0L
        accountBrowseSession.imageBrowserActive = false
        accountBrowseSession.imageBrowserReturnActivityClass = ""
        accountBrowseSession.sourceSnapshot = ""
        accountBrowseSession.lastReturnedSnapshot = ""
        accountBrowseSession.returnedStableSamples = 0
        accountBrowseSession.returnMismatchLogged = false
        accountBrowseSession.swipeCount = 0
        accountBrowseSession.lastStableFingerprint = ""
        accountBrowseSession.stableSamples = 0
        accountBrowseSession.deadlineAt = System.currentTimeMillis() + INITIAL_FEED_STABILITY_TIMEOUT_MS
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_FEED_STABLE
        addLog(
            "首页连续双击刷新已验证成功（尝试次数=$attempts；" +
                "列表内容变化=${if (snapshotChanged) "是" else "否"}；RecyclerView 内容更新事件=$events；" +
                "列表连续稳定 $REQUIRED_DYNAMIC_REFRESH_STABLE_SAMPLES 次）；" +
                "不再沿用旧位置，已建立刷新后的新动态列表基准，准备恢复浏览"
        )
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    /** Keep the user informed for three seconds, and re-check the feed before dispatch. */
    private fun runFirstSwipeCountdown() {
        val page = obtainTargetPageOrPause() ?: return
        if (!socialBrowsePort.supportsTargetPackage(page.packageName)) {
            pauseAccountBrowse("倒计时期间陌陌已不在前台；已取消首次滑动")
            return
        }
        if (!socialBrowsePort.inspectFeed().ready) {
            accountBrowseSession.lastStableFingerprint = ""
            accountBrowseSession.stableSamples = 0
            accountBrowseSession.deadlineAt = System.currentTimeMillis() + INITIAL_FEED_STABILITY_TIMEOUT_MS
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_FEED_STABLE
            addLog("倒计时期间动态列表暂时不可操作；取消本次滑动倒计时，等待重新确认页面和可见列表")
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        if (accountBrowseSession.countdownRemaining > 1) {
            accountBrowseSession.countdownRemaining--
            if (!showFirstSwipeCountdown(accountBrowseSession.countdownRemaining)) {
                pauseAccountBrowse("首次滑动倒计时提示中断；已取消首次滑动")
                return
            }
            addLog("首次滑动倒计时：${accountBrowseSession.countdownRemaining} 秒")
            scheduleAccountBrowseStep(FIRST_SWIPE_COUNTDOWN_STEP_MS)
            return
        }

        accountBrowseSession.countdownRemaining = 0
        hideFirstSwipeCountdown()
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
        performFeedScroll()
    }

    /** Use the dedicated overlay component so countdown WindowManager state is not workflow state. */
    private fun showFirstSwipeCountdown(seconds: Int): Boolean =
        overlayPort.showFirstSwipeCountdown(
            secondsRemaining = seconds,
            message = "首次滑动倒计时\n$seconds\n请观察动态列表是否移动"
        )

    private fun hideFirstSwipeCountdown() = overlayPort.hideFirstSwipeCountdown()

    private fun obtainTargetPageOrPause(pauseWhenUnavailable: Boolean = true): BrowsePageEvidence? {
        val page = socialBrowsePort.inspectPage()
        if (!page.windowAvailable) {
            if (pauseWhenUnavailable) {
                addLog("当前活动窗口/无障碍根节点暂时不可读取；保留业务阶段和待执行目标，稍后重新获取新快照")
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            }
            return null
        }
        if (!socialBrowsePort.supportsTargetPackage(page.packageName)) {
            val externalPopup = socialBrowsePort.inspectExternalPopup()
            if (externalPopup != null) {
                addLog("检测到陌陌以外的系统窗口/弹窗：${externalPopup.description}；不对系统或其他应用执行点击，等待用户处理后重新定位动态")
                beginDynamicNavigationRecovery("检测到陌陌外部系统窗口，需要用户先处理")
                return null
            }
            pauseAccountBrowse("当前没有打开陌陌；自动浏览已暂停")
            return null
        }
        if (handleMomoPopup()) return null
        return page
    }

    /** Temporarily remove the live monitor window from the touch path for the swipe. */
    private fun enableMonitorOverlayTouchPassthrough(
        actionDescription: String = "首次滑动"
    ): Boolean {
        if (!overlayPort.isMonitorAttached()) {
            accountBrowseSession.overlayPassthroughActive = false
            addLog("浮动按钮当前未附加（配置中“浮动按钮”已关闭）；$actionDescription 不经过悬浮窗")
            return true
        }
        val before = overlayPort.touchStateDescription()
        val enabled = overlayPort.setMonitorTouchPassthrough(true, MONITOR_OVERLAY_PASSTHROUGH_WATCHDOG_MS)
        if (!enabled) {
            addLog("无法暂时关闭浮动按钮的触摸拦截；未发送首次滑动。$before")
            return false
        }
        accountBrowseSession.overlayPassthroughActive = true
        scheduleOverlayPassthroughWatchdog(MONITOR_OVERLAY_PASSTHROUGH_WATCHDOG_MS)
        addLog(
            "$actionDescription 前已临时隐藏浮动按钮并启用触摸穿透。" +
                "$before；切换后=${overlayPort.touchStateDescription()}"
        )
        return true
    }

    private fun restoreMonitorOverlayTouchPassthroughImpl(
        actionDescription: String = "首次滑动检查结束"
    ): Boolean {
        if (!accountBrowseSession.overlayPassthroughActive) return true
        val restored = overlayPort.setMonitorTouchPassthrough(false)
        if (restored) {
            accountBrowseSession.overlayPassthroughActive = false
            cancelOverlayPassthroughWatchdog()
            addLog("$actionDescription：浮动按钮已恢复显示和触摸操作")
            return true
        }
        addLog("警告：浮动按钮触摸状态恢复失败；悬浮窗可能仍处于隐藏/穿透状态")
        return false
    }

    /**
     * First-stage verification: dispatch the adapter's existing Momo gesture plan through the
     * generic gateway. The adapter owns target resolution; Gesture Policy Pipeline compiles the physical path.
     */
    private fun performFeedScroll() {
        if (obtainTargetPageOrPause() == null) return
        if (!socialBrowsePort.inspectFeed().ready) {
            accountBrowseSession.lastStableFingerprint = ""
            accountBrowseSession.stableSamples = 0
            accountBrowseSession.deadlineAt = System.currentTimeMillis() + INITIAL_FEED_STABILITY_TIMEOUT_MS
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_FEED_STABLE
            addLog("动态页面身份尚未丢失，但列表暂时不可操作；本轮不发送滑动，等待重新确认可见列表")
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        accountBrowseSession.sourceSnapshot = socialBrowsePort.captureVisibleFeedSnapshot(listOnly = false)
        addLog("第 ${accountBrowseSession.swipeCount + 1} 轮滑动前可见节点快照：${accountBrowseSession.sourceSnapshot.ifBlank { "未提取到可见文字节点" }}")
        val profile = socialBrowsePort.loadFeedGestureProfile()
        val gesturePlan = socialBrowsePort.planFeedGesture(profile) ?: run {
            pauseAccountBrowse("动态列表有效区域不足以生成安全滑动路径；本轮未发送滑动")
            return
        }
        if (!enableMonitorOverlayTouchPassthrough()) {
            pauseAccountBrowse("浮动按钮无法切换为触摸穿透；本轮滑动未执行")
            return
        }

        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE
        accountBrowseSession.scrollEventSeen = false
        accountBrowseSession.scrollEventCount = 0
        accountBrowseSession.programmaticActionLabel = "Freedom dispatchGesture 自动坐标滑动"
        accountBrowseSession.lastProgrammaticScrollAt = System.currentTimeMillis()
        accountBrowseSession.lastProgrammaticScrollLabel = accountBrowseSession.programmaticActionLabel
        accountBrowseSession.ignoreManualEventsUntil = System.currentTimeMillis() + profile.durationMillis + 2_000L
        accountBrowseSession.lastScrollEventAt = 0L
        accountBrowseSession.fallbackScrollAttempted = false
        accountBrowseSession.gestureInFlight = false
        accountBrowseSession.touchStartedDuringGesture = false
        accountBrowseSession.touchEndedDuringGesture = false
        addLog(
            "第 ${accountBrowseSession.swipeCount + 1} 轮列表滑动意图已准备：容器=${gesturePlan.className}，" +
                "边界=${gesturePlan.bounds.toShortString()}，最终轨迹由统一策略编译，持续 ${profile.durationMillis} 毫秒"
        )

        gestureDispatchSyncTask?.cancel()
        gestureDispatchSyncTask = taskScheduler.schedule(SWIPE_WINDOW_SYNC_DELAY_MS) {
            gestureDispatchSyncTask = null
            if (accountBrowsePhase != AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE ||
                accountBrowseSession.touchActive || !isMonitoringRunning()
            ) {
                accountBrowseSession.gestureInFlight = false
                accountBrowseSession.lastProgrammaticScrollAt = 0L
                accountBrowseSession.lastProgrammaticScrollLabel = ""
                restoreMonitorOverlayTouchPassthrough()
                return@schedule
            }
            accountBrowseSession.gestureInFlight = true
            val executionContext = gesturePolicy.captureContext(gesturePlan.targetWindow) ?: run {
                accountBrowseSession.gestureInFlight = false
                accountBrowseSession.lastProgrammaticScrollAt = 0L
                accountBrowseSession.lastProgrammaticScrollLabel = ""
                accountBrowseSession.programmaticActionLabel = ""
                accountBrowseSession.scrollEventSeen = false
                accountBrowseSession.scrollEventCount = 0
                restoreMonitorOverlayTouchPassthrough("列表滑动窗口或显示上下文暂时失效")
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                addLog("列表滑动的窗口/显示上下文在派发前发生变化；拒绝旧轨迹，保留滑动阶段并等待新快照重新规划")
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                return@schedule
            }
            val gestureRequest = GestureIntentRequest(
                id = "accountbrowse-scroll-${accountBrowseSession.swipeCount + 1}-${System.nanoTime()}",
                intent = scrollExecutor.feedSwipeIntent(gesturePlan.bounds, profile),
                description = "陌陌动态列表滑动",
                executionContext = executionContext
            )
            val submission = runCatching {
                gesturePolicy.execute(gestureRequest) { result ->
                    if (result.status == GestureExecutionStatus.DEFERRED) {
                        accountBrowseSession.gestureInFlight = false
                        accountBrowseSession.lastProgrammaticScrollAt = 0L
                        accountBrowseSession.lastProgrammaticScrollLabel = ""
                        accountBrowseSession.programmaticActionLabel = ""
                        accountBrowseSession.scrollEventSeen = false
                        accountBrowseSession.scrollEventCount = 0
                        restoreMonitorOverlayTouchPassthrough("动态列表滑动在平台派发前延期")
                        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                        addLog("动态列表滑动在平台派发前延期；不计入失败次数，重新确认视口后再规划")
                        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                    } else if (result.status == GestureExecutionStatus.COMPLETED) {
                        accountBrowseSession.gestureInFlight = false
                        accountBrowseSession.touchStartedDuringGesture = false
                        accountBrowseSession.touchEndedDuringGesture = false
                        if (accountBrowsePhase != AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE || !isMonitoringRunning()) return@execute
                        addLog("Android 已完成第 ${accountBrowseSession.swipeCount + 1} 轮屏幕滑动手势；正在检查真实列表滚动")
                        scheduleAccountBrowseStep(SCROLL_SETTLE_MS)
                    } else {
                        val touchStarted = accountBrowseSession.touchStartedDuringGesture
                        val touchEnded = accountBrowseSession.touchEndedDuringGesture
                        accountBrowseSession.gestureInFlight = false
                        accountBrowseSession.touchStartedDuringGesture = false
                        accountBrowseSession.touchEndedDuringGesture = false
                        if (accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE) {
                            val interruptedByTouch = touchStarted || touchEnded
                            pauseAccountBrowse(
                                "第 ${accountBrowseSession.swipeCount + 1} 轮屏幕滑动被系统或用户操作中断",
                                allowManualResume = interruptedByTouch
                            )
                            if (interruptedByTouch) {
                                if (touchEnded) {
                                    accountBrowseSession.touchActive = false
                                    addLog("检测到手动触摸中断了自动滑动，且触摸已经结束；开始第一轮静默 5 秒等待")
                                    cancelManualTouchResume()
                                    scheduleManualTouchResume()
                                } else {
                                    accountBrowseSession.touchActive = true
                                    addLog("检测到手动触摸中断了自动滑动；等待用户松开屏幕后再开始静默等待")
                                }
                            }
                        }
                    }
                }
            }.getOrDefault(GestureSubmission.REJECTED)
            addLog("dispatchGesture 返回：accepted=${submission == GestureSubmission.ACCEPTED}")
            if (submission == GestureSubmission.DEFERRED_NOT_READY) {
                accountBrowseSession.gestureInFlight = false
                accountBrowseSession.lastProgrammaticScrollAt = 0L
                accountBrowseSession.lastProgrammaticScrollLabel = ""
                accountBrowseSession.programmaticActionLabel = ""
                accountBrowseSession.scrollEventSeen = false
                accountBrowseSession.scrollEventCount = 0
                restoreMonitorOverlayTouchPassthrough("动态列表滑动因遮挡而延期")
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                addLog("动态列表滑动因当前窗口/操作区暂时不可用而延期；未发送手势、未计入滑动次数，稍后重新确认并尝试")
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                return@schedule
            }
            if (submission != GestureSubmission.ACCEPTED) {
                accountBrowseSession.gestureInFlight = false
                accountBrowseSession.lastProgrammaticScrollAt = 0L
                accountBrowseSession.lastProgrammaticScrollLabel = ""
                pauseAccountBrowse("Android 拒绝第 ${accountBrowseSession.swipeCount + 1} 轮屏幕滑动手势；已停止本轮浏览")
                return@schedule
            }
            addLog("第 ${accountBrowseSession.swipeCount + 1} 轮列表区域滑动手势已提交；统一执行器已在触摸前显示实际坐标轨迹，等待系统完成回调")
        }
    }

    private fun verifyInitialSwipe() {
        if (accountBrowsePhase != AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE) return
        if (accountBrowseSession.gestureInFlight) {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        if (accountBrowseSession.scrollEventSeen) {
            val quietForMs = System.currentTimeMillis() - accountBrowseSession.lastScrollEventAt
            if (quietForMs < SCROLL_SETTLE_MS) {
                scheduleAccountBrowseStep((SCROLL_SETTLE_MS - quietForMs).coerceAtLeast(100L))
                return
            }
        }
        if (!restoreMonitorOverlayTouchPassthrough()) {
            pauseAccountBrowse("第 ${accountBrowseSession.swipeCount + 1} 轮滑动后无法恢复浮动按钮；已停止后续操作")
            return
        }
        if (obtainTargetPageOrPause() == null) return
        val afterFeedReadiness = socialBrowsePort.inspectFeed()
        val afterSnapshot = if (afterFeedReadiness.ready) {
            socialBrowsePort.captureVisibleFeedSnapshot(listOnly = false)
        } else ""
        val afterPage = socialBrowsePort.inspectPage()
        val obstructionRetained = isRetainedObstructionEvidence(afterPage.reason)
        addLog("第 ${accountBrowseSession.swipeCount + 1} 轮滑动后可见节点快照：${afterSnapshot.ifBlank { "动态列表快照不可用" }}")
        // Check for an observed obstruction before treating a missing scroll event as gesture
        // failure. A foreground banner/modal may intercept the swipe while the underlying feed
        // remains the same page; preserve the pending verification and retry after a fresh tree.
        if (obstructionRetained && (afterSnapshot.isBlank() || !accountBrowseSession.scrollEventSeen)) {
            addLog("滑动后动态列表被临时遮挡；保留本轮滚动上下文，不将缺失滚动事件判为任务失败，等待遮挡消失后重新读取快照")
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        if (!accountBrowseSession.scrollEventSeen) {
            // The primary gesture was submitted but no list event arrived. Keep the workflow
            // alive and let openVisibleFeedCard() perform its one safe fallback scroll after
            // revalidating the current page, rather than terminating on a missing event alone.
            addLog("常规滑动未收到目标列表滚动事件；未计入成功轮数，转入重新识别并尝试受控备用滑动")
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_CARD_OPEN
            scheduleAccountBrowseStep(CARD_OPEN_SETTLE_MS)
            return
        }
        if (afterSnapshot.isBlank()) {
            pauseAccountBrowse("第 ${accountBrowseSession.swipeCount + 1} 轮收到滚动事件，但列表快照仍不可用；为避免误选动态，保留安全暂停")
            return
        }
        val snapshotsDiffer = accountBrowseSession.sourceSnapshot.isNotBlank() && accountBrowseSession.sourceSnapshot != afterSnapshot
        accountBrowseSession.swipeCount++
        addLog(
            "第 ${accountBrowseSession.swipeCount} 轮真实滑动已确认；" +
                "TYPE_VIEW_SCROLLED 事件数=${accountBrowseSession.scrollEventCount}；" +
                "前后节点快照${if (snapshotsDiffer) "发生变化" else "未显示明显差异"}"
        )
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_CARD_OPEN
        addLog("动态列表已停止滚动；累计已确认滑动=${accountBrowseSession.swipeCount} 轮，等待列表稳定后识别并打开一条普通动态")
        scheduleAccountBrowseStep(CARD_OPEN_SETTLE_MS)
    }

    private fun openVisibleFeedCard() {
        if (!accountBrowseSession.scrollEventSeen && !accountBrowseSession.fallbackScrollAttempted) {
            if (obtainTargetPageOrPause() == null) return
            val feed = socialBrowsePort.inspectFeed()
            if (!feed.ready) {
                addLog("备用滑动前动态列表暂时不可识别或被遮挡；不消耗备用滑动次数，等待新快照后重新确认")
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                return
            }
            val scrollBounds = feed.scrollBounds
            if (scrollBounds == null || scrollBounds.isEmpty) {
                addLog("备用滑动区域暂时不可用；保留当前动态浏览阶段并重新获取页面指纹，不执行坐标猜测")
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                return
            }
            val profile = socialBrowsePort.loadFeedGestureProfile()
            val gesturePlan = socialBrowsePort.planFeedGesture(profile)
            if (gesturePlan == null) {
                pauseAccountBrowse("常规滑动未触发列表滚动，且无法生成备用坐标滑动路径；已停止，不使用节点级滚动")
                return
            }
            if (!enableMonitorOverlayTouchPassthrough("备用坐标滑动")) {
                pauseAccountBrowse("浮动按钮无法切换为触摸穿透；备用坐标滑动未执行")
                return
            }

            accountBrowseSession.fallbackScrollAttempted = true
            accountBrowseSession.programmaticActionLabel = "Freedom COORDINATE_SWIPE 备用滚动"
            accountBrowseSession.lastProgrammaticScrollAt = System.currentTimeMillis()
            accountBrowseSession.lastProgrammaticScrollLabel = accountBrowseSession.programmaticActionLabel
            accountBrowseSession.ignoreManualEventsUntil =
                System.currentTimeMillis() + profile.durationMillis + 2_000L
            accountBrowseSession.gestureInFlight = false
            addLog(
                "常规滑动未触发列表滚动；准备执行一次备用手势意图：" +
                    "边界=${gesturePlan.bounds.toShortString()}，最终轨迹由统一策略编译，" +
                    "时长=${profile.durationMillis} 毫秒"
            )

            gestureDispatchSyncTask?.cancel()
            gestureDispatchSyncTask = taskScheduler.schedule(SWIPE_WINDOW_SYNC_DELAY_MS) {
                gestureDispatchSyncTask = null
                if (accountBrowseSession.touchActive || !isMonitoringRunning() || !isBrowseModeActive()) {
                    accountBrowseSession.lastProgrammaticScrollAt = 0L
                    accountBrowseSession.lastProgrammaticScrollLabel = ""
                    restoreMonitorOverlayTouchPassthrough("备用坐标滑动未执行")
                    return@schedule
                }
                val executionContext = gesturePolicy.captureContext(gesturePlan.targetWindow) ?: run {
                    accountBrowseSession.gestureInFlight = false
                    accountBrowseSession.lastProgrammaticScrollAt = 0L
                    accountBrowseSession.lastProgrammaticScrollLabel = ""
                    accountBrowseSession.programmaticActionLabel = ""
                    accountBrowseSession.fallbackScrollAttempted = false
                    restoreMonitorOverlayTouchPassthrough("备用滑动窗口或显示上下文暂时失效")
                    accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                    addLog("备用滑动窗口/显示上下文已变化；丢弃旧轨迹和旧坐标，不计入备用次数，等待新快照重新规划")
                    scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                    return@schedule
                }
                val gestureRequest = GestureIntentRequest(
                    id = "accountbrowse-fallback-scroll-${System.nanoTime()}",
                    intent = scrollExecutor.feedSwipeIntent(gesturePlan.bounds, profile),
                    description = "陌陌动态列表备用坐标滑动",
                    executionContext = executionContext
                )
                accountBrowseSession.gestureInFlight = true
                val submission = runCatching {
                    gesturePolicy.execute(gestureRequest) { result ->
                        accountBrowseSession.gestureInFlight = false
                        accountBrowseSession.touchStartedDuringGesture = false
                        accountBrowseSession.touchEndedDuringGesture = false
                        restoreMonitorOverlayTouchPassthrough("备用坐标滑动完成")
                        if (!isMonitoringRunning() || !isBrowseModeActive()) return@execute
                        if (result.status == GestureExecutionStatus.DEFERRED) {
                            accountBrowseSession.gestureInFlight = false
                            accountBrowseSession.lastProgrammaticScrollAt = 0L
                            accountBrowseSession.lastProgrammaticScrollLabel = ""
                            accountBrowseSession.programmaticActionLabel = ""
                            accountBrowseSession.fallbackScrollAttempted = false
                            restoreMonitorOverlayTouchPassthrough("备用坐标滑动在平台派发前延期")
                            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                            addLog("备用滑动在平台派发前延期；不消耗备用滑动次数，等待新鲜视口")
                            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                        } else if (result.status == GestureExecutionStatus.COMPLETED) {
                            addLog("备用坐标滑动手势已完成；等待确认动态列表是否产生真实滚动")
                            scheduleAccountBrowseStep(SCROLL_SETTLE_MS)
                        } else {
                            pauseAccountBrowse(
                                "备用坐标滑动被系统或用户操作中断；不再尝试节点级滚动",
                                allowManualResume = true
                            )
                        }
                    }
                }.getOrDefault(GestureSubmission.REJECTED)

                if (submission == GestureSubmission.DEFERRED_NOT_READY) {
                    accountBrowseSession.gestureInFlight = false
                    accountBrowseSession.lastProgrammaticScrollAt = 0L
                    accountBrowseSession.lastProgrammaticScrollLabel = ""
                    accountBrowseSession.programmaticActionLabel = ""
                    accountBrowseSession.fallbackScrollAttempted = false
                    restoreMonitorOverlayTouchPassthrough("备用坐标滑动因遮挡而延期")
                    accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                    addLog("备用滑动因当前页面暂时不可操作而延期；没有消耗备用滑动次数，等待重新识别")
                    scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                } else if (submission != GestureSubmission.ACCEPTED) {
                    accountBrowseSession.gestureInFlight = false
                    accountBrowseSession.lastProgrammaticScrollAt = 0L
                    accountBrowseSession.lastProgrammaticScrollLabel = ""
                    restoreMonitorOverlayTouchPassthrough("备用坐标滑动未被接受")
                    pauseAccountBrowse("Android 拒绝备用可视化坐标滑动；不使用节点级滚动替代")
                } else {
                    addLog("备用坐标滑动已提交统一 Gesture Policy Pipeline；屏幕已显示实际滑动轨迹，等待系统完成回调")
                }
            }
            return
        }

        if (obtainTargetPageOrPause() == null) return
        val feed = socialBrowsePort.inspectFeed()
        if (!feed.ready) {
            addLog("滑动后动态列表指纹暂时不可见；不认定已离开动态页，等待备用路径或遮挡恢复后重扫")
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_SAFE_CARD
            scheduleAccountBrowseStep(SAFE_CARD_RECHECK_INTERVAL_MS)
            return
        }
        if (feed.scrollBounds == null) {
            pauseAccountBrowse("滑动后未找到动态列表；已暂停")
            return
        }
        val minWidth = (displayMetrics.widthPixels * MIN_FEED_CARD_WIDTH_RATIO).toInt()
        val minHeight = (displayMetrics.heightPixels * MIN_FEED_CARD_HEIGHT_RATIO).toInt()
        val result = socialBrowsePort.selectVisibleFeedCard(minWidth, minHeight)
        if (result.sourceFingerprint.isBlank()) {
            pauseAccountBrowse("滑动后未找到动态列表；已暂停")
            return
        }
        accountBrowseSession.sourceFingerprint = result.sourceFingerprint
        accountBrowseSession.sourceSnapshot = result.sourceSnapshot
        if (result.skippedLiveStreams > 0) addLog("卡片筛选：已跳过 ${result.skippedLiveStreams} 条疑似直播间动态")
        if (result.skippedAdvertisements > 0) addLog("卡片筛选：已跳过 ${result.skippedAdvertisements} 条广告或推广卡片")
        if (result.skippedUnknown > 0) addLog("卡片筛选：已跳过 ${result.skippedUnknown} 条无法确认类型的卡片")
        val candidate = result.candidate
        if (candidate == null) {
            addLog("卡片筛选未通过：${result.reason}；检查候选=${result.inspectedCount}，直播=${result.skippedLiveStreams}，广告=${result.skippedAdvertisements}，未知=${result.skippedUnknown}")
            if (accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SAFE_CARD) {
                accountBrowseSession.safeCardRecheckCount++
                if (accountBrowseSession.safeCardRecheckCount >= MAX_SAFE_CARD_RECHECKS_BEFORE_NEXT_SWIPE) {
                    accountBrowseSession.safeCardRecheckCount = 0
                    accountBrowseSession.scrollEventSeen = false
                    accountBrowseSession.scrollEventCount = 0
                    accountBrowseSession.lastScrollEventAt = 0L
                    accountBrowseSession.fallbackScrollAttempted = false
                    accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                    val nextSwipeDelay = scrollExecutor.randomScrollIntervalMs()
                    addLog("连续 ${MAX_SAFE_CARD_RECHECKS_BEFORE_NEXT_SWIPE} 次复查仍未发现可安全打开的普通动态；广告、直播和未知卡片继续全部跳过，${nextSwipeDelay / 1_000.0} 秒后继续滑动列表搜索")
                    scheduleAccountBrowseStep(nextSwipeDelay)
                } else {
                    addLog("第 ${accountBrowseSession.safeCardRecheckCount} 次定时复查：当前仍没有符合条件的普通动态；保持等待 ${SAFE_CARD_RECHECK_INTERVAL_MS / 1_000.0} 秒，不点击广告、直播或未知卡片")
                    scheduleAccountBrowseStep(SAFE_CARD_RECHECK_INTERVAL_MS)
                }
            } else {
                accountBrowseSession.safeCardRecheckCount = 0
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_SAFE_CARD
                addLog("本轮没有找到可安全打开的普通动态；已跳过广告、直播和未知卡片，进入周期性等待，每 ${SAFE_CARD_RECHECK_INTERVAL_MS / 1_000.0} 秒重新检查；连续复查仍无合适卡片时会继续滑动列表搜索，不会永久停在 PAUSED")
                scheduleAccountBrowseStep(SAFE_CARD_RECHECK_INTERVAL_MS)
            }
            return
        }
        addLog("卡片筛选通过：已确认候选具有可识别的普通社交卡片结构")
        accountBrowseSession.safeCardRecheckCount = 0
        val tap = navigationExecutor.createCoordinateTap(
            preferredBounds = candidate.bounds,
            clickableBounds = candidate.bounds,
            description = "陌陌普通动态卡片",
            expectedWindow = candidate.targetWindow
        )
        if (tap == null) {
            accountBrowseSession.sourceFingerprint = ""
            accountBrowseSession.sourceSnapshot = ""
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_SAFE_CARD
            addLog("普通动态卡片的安全坐标或窗口上下文暂时失效；丢弃该候选及旧坐标，重新扫描，不执行节点级或猜测性点击")
            scheduleAccountBrowseStep(SAFE_CARD_RECHECK_INTERVAL_MS)
            return
        }
        accountBrowseSession.programmaticActionLabel = "Freedom COORDINATE_TAP 动态卡片"
        accountBrowseSession.ignoreManualEventsUntil =
            System.currentTimeMillis() + PROGRAMMATIC_CLICK_EVENT_WINDOW_MS
        accountBrowseSession.gestureInFlight = true
        val submission = runCatching {
            gesturePolicy.execute(tap.request) { result ->
                accountBrowseSession.gestureInFlight = false
                if (accountBrowsePhase != AccountBrowsePhase.WAIT_FOR_CARD_OPEN || !isMonitoringRunning()) {
                    return@execute
                }
                if (result.status == GestureExecutionStatus.DEFERRED) {
                    accountBrowseSession.programmaticActionLabel = ""
                    accountBrowseSession.sourceFingerprint = ""
                    accountBrowseSession.sourceSnapshot = ""
                    accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_SAFE_CARD
                    addLog("动态卡片点击在平台派发前延期；丢弃旧坐标，等待重新扫描，不标记卡片失败")
                    scheduleAccountBrowseStep(SAFE_CARD_RECHECK_INTERVAL_MS)
                    return@execute
                }
                if (result.status != GestureExecutionStatus.COMPLETED) {
                    val interruptedByTouch = accountBrowseSession.touchStartedDuringGesture ||
                        accountBrowseSession.touchEndedDuringGesture || accountBrowseSession.touchActive
                    pauseAccountBrowse(
                        "普通动态卡片坐标点击被系统或用户操作中断；停止本轮自动浏览",
                        allowManualResume = interruptedByTouch
                    )
                    return@execute
                }
                accountBrowseSession.touchStartedDuringGesture = false
                accountBrowseSession.touchEndedDuringGesture = false
                accountBrowseSession.returnedStableSamples = 0
                accountBrowseSession.lastReturnedSnapshot = ""
                accountBrowseSession.returnMismatchLogged = false
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD
                accountBrowseSession.deadlineAt = System.currentTimeMillis() + DETAIL_LOAD_TIMEOUT_MS
                accountBrowseSession.lastStableFingerprint = ""
                accountBrowseSession.stableSamples = 0
                addLog("普通动态卡片坐标点击已完成；最终坐标由统一策略编译；正在确认详情页面是否成功载入")
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            }
        }.getOrDefault(GestureSubmission.REJECTED)
        if (submission == GestureSubmission.DEFERRED_NOT_READY) {
            accountBrowseSession.gestureInFlight = false
            accountBrowseSession.programmaticActionLabel = ""
            accountBrowseSession.sourceFingerprint = ""
            accountBrowseSession.sourceSnapshot = ""
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_SAFE_CARD
            addLog("动态卡片点击因页面暂时被遮挡而延期；丢弃旧坐标，稍后重新扫描可安全打开的卡片")
            scheduleAccountBrowseStep(SAFE_CARD_RECHECK_INTERVAL_MS)
            return
        }
        if (submission != GestureSubmission.ACCEPTED) {
            accountBrowseSession.gestureInFlight = false
            pauseAccountBrowse("Android 拒绝普通动态卡片的可视化坐标点击；已停止本轮操作")
            return
        }
        addLog(
            "已提交一条符合条件的普通动态卡片坐标点击；" +
                "最终坐标由统一策略编译；" +
                "统一可视化已显示手指落点，等待系统完成回调"
        )
    }

    private fun checkDetailPageLoaded() {
        val page = obtainTargetPageOrPause() ?: return
        val isFeedPage = page.pageKind == BrowsePageKind.DYNAMIC_FEED
        val fingerprint = page.fingerprint
        if (!isFeedPage && page.pageKind == BrowsePageKind.DETAIL &&
            page.confidence >= 80 && !isRetainedObstructionEvidence(page.reason) &&
            fingerprint != accountBrowseSession.sourceFingerprint && fingerprint.isNotBlank()
        ) {
            if (fingerprint == accountBrowseSession.lastStableFingerprint) {
                accountBrowseSession.stableSamples++
            } else {
                accountBrowseSession.lastStableFingerprint = fingerprint
                accountBrowseSession.stableSamples = 1
            }
            if (accountBrowseSession.stableSamples >= REQUIRED_STABLE_PAGE_SAMPLES) {
                accountBrowseSession.detailFingerprint = fingerprint
                accountBrowseSession.detailActivityClass = accountBrowseSession.currentActivityClass
                    .ifBlank { socialBrowsePort.detailActivityClass }
                val dwell = scrollExecutor.randomBrowseDelayMs()
                accountBrowsePhase = AccountBrowsePhase.DETAIL_DWELL
                accountBrowseSession.detailDwellRemainingMs = dwell
                accountBrowseSession.detailDwellStartedAt = System.currentTimeMillis()
                accountBrowseSession.detailDwellDueAt = accountBrowseSession.detailDwellStartedAt + dwell
                addLog("动态详情已确认载入；本次阅读停留 ${dwell / 1000.0} 秒")
                scheduleAccountBrowseStep(dwell)
                return
            }
        } else {
            accountBrowseSession.lastStableFingerprint = ""
            accountBrowseSession.stableSamples = 0
        }
        if (System.currentTimeMillis() >= accountBrowseSession.deadlineAt) {
            beginDynamicNavigationRecovery("等待动态详情超时；当前 Activity=${accountBrowseSession.currentActivityClass.ifBlank { "未知" }}，重新分析当前页面层级")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun returnFromDetailPage() {
        val identity = socialBrowsePort.inspectDetail(accountBrowseSession.detailFingerprint)
        if (identity.isFeedPage) {
            addLog("详情停留结束时重新扫描发现已经回到动态列表；不再重复执行返回，直接验证原列表位置")
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_RETURN
            accountBrowseSession.returnedStableSamples = 0
            accountBrowseSession.lastReturnedSnapshot = ""
            accountBrowseSession.returnMismatchLogged = false
            accountBrowseSession.deadlineAt = System.currentTimeMillis() + LIST_RETURN_TIMEOUT_MS
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        if (!identity.identityConfirmed) {
            addLog("详情停留结束时无法确认当前页面仍是原动态详情：${identity.reason}；交由主动导航重新分析页面层级，不执行猜测性返回")
            beginDynamicNavigationRecovery("详情页面身份验证未通过：${identity.reason}")
            return
        }
        if (identity.fingerprint != accountBrowseSession.detailFingerprint) {
            addLog("动态详情内容发生变化（可见锚点=${identity.nodeCount}）；页面指纹仍确认在同一窗口代次的动态详情页，按正常内容更新处理")
            accountBrowseSession.detailFingerprint = identity.fingerprint
        }
        // Reacquire the active page immediately before BACK. The inspector's fingerprint already
        // contains package/windowId/generation, so an intervening dialog or Activity/window switch
        // cannot reuse the earlier positive result.
        val preBackPage = socialBrowsePort.inspectPage()
        if (preBackPage.pageKind != BrowsePageKind.DETAIL ||
            preBackPage.fingerprint != identity.fingerprint || preBackPage.fingerprint.isBlank()
        ) {
            beginDynamicNavigationRecovery(
                "BACK 前页面身份复核失败：当前页类型=${preBackPage.pageKind}；窗口/代次/结构指纹已变化；取消本次 BACK"
            )
            return
        }
        accountBrowseSession.programmaticActionLabel = "Freedom GLOBAL_ACTION_BACK 返回详情"
        accountBrowseSession.ignoreManualEventsUntil = System.currentTimeMillis() + 1_500L
        if (!accessibilityGateway.performGlobalAction(GlobalAccessibilityAction.BACK)) {
            beginDynamicNavigationRecovery("详情页面身份已确认，但系统未接受返回操作")
            return
        }
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_RETURN
        accountBrowseSession.returnedStableSamples = 0
        accountBrowseSession.lastReturnedSnapshot = ""
        accountBrowseSession.returnMismatchLogged = false
        accountBrowseSession.deadlineAt = System.currentTimeMillis() + LIST_RETURN_TIMEOUT_MS
        addLog("动态详情身份已确认；阅读停留结束，正在返回并验证原动态列表位置")
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    /** Machine-detected page uncertainty should enter the same verified navigator, not dead-end PAUSED. */
    private fun beginDynamicNavigationRecovery(reason: String) {
        accountBrowseRuntime.beginNavigationRecovery(
            reason = reason,
            beforeRecovery = {
                restoreMonitorOverlayTouchPassthrough()
                accountBrowseRuntime.cancelScheduledStep()
            },
            initialDynamicTabWaitTimeoutMs = INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS,
            pageStatePollMs = PAGE_STATE_POLL_MS,
            currentTimeMillis = System::currentTimeMillis,
            onStarted = { recoveryReason ->
                addLog("页面状态需要重新定位：$recoveryReason；启动主动导航，重新识别页面层级并验证动态标签")
            },
            scheduleStep = ::scheduleAccountBrowseStep
        )
    }

    /**
     * Track the known Momo photo-browser Activity as a child page of the current dynamic detail.
     * Only the exact Activity that was active before the photo browser is accepted as its return.
     */
    internal fun handleMomoActivityTransition(activityClass: String) {
        if (!isActivityWindowClassName(activityClass)) return

        val previousActivity = accountBrowseSession.currentActivityClass
        accountBrowseSession.currentActivityClass = activityClass
        if (previousActivity.isNotBlank() && previousActivity != activityClass) {
            val transitionEvidence = interactionEvidenceClassifier.describePageTransition(
                now = System.currentTimeMillis(),
                lastGlobalTouchEventAt = accountBrowseSession.lastGlobalTouchEventAt,
                lastGlobalTouchEventName = accountBrowseSession.lastGlobalTouchEventName,
                lastGlobalTouchEventOrigin = accountBrowseSession.lastGlobalTouchEventOrigin,
                lastTargetInteractionAt = accountBrowseSession.lastTargetInteractionAt,
                lastTargetInteractionCategory = accountBrowseSession.lastTargetInteractionCategory,
                lastTargetInteractionDescription = accountBrowseSession.lastTargetInteractionDescription
            )
            addLog(
                "页面切换来源证据：${previousActivity.substringAfterLast('.')} → " +
                    "${activityClass.substringAfterLast('.')}；$transitionEvidence"
            )
        }

        if (socialBrowsePort.isImageBrowserActivity(activityClass)) {
            if (accountBrowsePhase == AccountBrowsePhase.DETAIL_DWELL) {
                accountBrowseSession.imageBrowserReturnActivityClass = previousActivity
                    .takeIf { it.isNotBlank() && !socialBrowsePort.isImageBrowserActivity(it) }
                    ?: socialBrowsePort.detailActivityClass
                accountBrowseSession.imageBrowserActive = true
                accountBrowseSession.detailDwellRemainingMs =
                    (accountBrowseSession.detailDwellDueAt - System.currentTimeMillis()).coerceAtLeast(0L)
                cancelManualTouchResume()
                accountBrowseRuntime.cancelScheduledStep()
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN
                accountBrowseSession.deadlineAt = System.currentTimeMillis() + IMAGE_BROWSER_RETURN_TIMEOUT_MS
                addLog("自动详情停留期间进入陌陌图片浏览页面：保留详情上下文，等待图片页面返回")
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            }
            return
        }

        if (accountBrowseSession.imageBrowserActive) {
            val returnedPage = socialBrowsePort.inspectPage()
            val returnedToDetail = returnedPage.pageKind == BrowsePageKind.DETAIL &&
                returnedPage.confidence >= 80 && !isRetainedObstructionEvidence(returnedPage.reason)
            if (accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB ||
                accountBrowsePhase == AccountBrowsePhase.VERIFY_NAVIGATION_ACTION ||
                accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_FEED_STABLE
            ) {
                if (returnedToDetail) {
                    accountBrowseSession.imageBrowserActive = false
                    accountBrowseSession.imageBrowserReturnActivityClass = ""
                    addLog("恢复导航已退出图片浏览页面并回到详情层级；继续主动寻找动态主页面")
                }
                return
            }
            if (returnedToDetail) {
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE
                accountBrowseSession.deadlineAt = System.currentTimeMillis() + DETAIL_RESTABILIZE_TIMEOUT_MS
                accountBrowseSession.lastStableFingerprint = ""
                accountBrowseSession.stableSamples = 0
                addLog("检测到已退出图片浏览器并通过动态详情结构指纹确认返回；Activity=$activityClass（仅诊断）；正在重新确认详情页面稳定性")
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            } else {
                accountBrowseSession.imageBrowserActive = false
                pauseAccountBrowse("图片浏览器退出后进入了非原动态详情页面：$activityClass；为避免误返回或误点击，已安全暂停")
            }
        }
    }

    private fun waitForImageBrowserReturn() {
        if (!accountBrowseSession.imageBrowserActive) {
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE
            accountBrowseSession.deadlineAt = System.currentTimeMillis() + DETAIL_RESTABILIZE_TIMEOUT_MS
            accountBrowseSession.lastStableFingerprint = ""
            accountBrowseSession.stableSamples = 0
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        if (System.currentTimeMillis() >= accountBrowseSession.deadlineAt) {
            pauseAccountBrowse("等待动态图片浏览页面返回原详情超时；没有执行返回或点击操作")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun waitForDetailRestabilize() {
        if (obtainTargetPageOrPause() == null) return
        val identity = socialBrowsePort.inspectDetail(accountBrowseSession.detailFingerprint)
        val currentDetailPage = socialBrowsePort.inspectPage()
        if (!identity.identityConfirmed || currentDetailPage.pageKind != BrowsePageKind.DETAIL ||
            currentDetailPage.confidence < 80 || isRetainedObstructionEvidence(currentDetailPage.reason)
        ) {
            if (identity.isFeedPage) {
                addLog("退出图片浏览器后已经回到动态列表；直接验证列表状态，不重复返回")
                accountBrowseSession.imageBrowserActive = false
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_RETURN
                accountBrowseSession.deadlineAt = System.currentTimeMillis() + LIST_RETURN_TIMEOUT_MS
                accountBrowseSession.returnedStableSamples = 0
                accountBrowseSession.lastReturnedSnapshot = ""
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                return
            }
            beginDynamicNavigationRecovery("图片浏览器返回后未能确认原动态详情身份：${identity.reason}")
            return
        }
        accountBrowseSession.stableSamples++
        accountBrowseSession.detailFingerprint = identity.fingerprint
        accountBrowseSession.detailActivityClass = accountBrowseSession.currentActivityClass
        if (accountBrowseSession.stableSamples >= REQUIRED_STABLE_PAGE_SAMPLES) {
            accountBrowseSession.imageBrowserActive = false
            accountBrowseSession.imageBrowserReturnActivityClass = ""
            accountBrowseSession.detailDwellStartedAt = System.currentTimeMillis()
            accountBrowseSession.detailDwellDueAt = accountBrowseSession.detailDwellStartedAt + accountBrowseSession.detailDwellRemainingMs
            accountBrowsePhase = AccountBrowsePhase.DETAIL_DWELL
            val remaining = accountBrowseSession.detailDwellRemainingMs
            addLog(
                "图片浏览器已返回原动态详情；页面身份连续 ${REQUIRED_STABLE_PAGE_SAMPLES} 次确认，" +
                    "内容节点变化不影响页面身份；继续剩余阅读时间 ${remaining / 1000.0} 秒"
            )
            scheduleAccountBrowseStep(remaining.coerceAtLeast(300L))
            return
        }
        if (System.currentTimeMillis() >= accountBrowseSession.deadlineAt) {
            beginDynamicNavigationRecovery("图片浏览器已退出，但原动态详情身份未能连续确认")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    /**
     * Some devices do not deliver global touch-interaction events to this service.
     * In that case, infer manual interaction from real target-app click/scroll/input events.
     */
    internal fun currentMonitorPageContext(): String = monitorPageContext()

    private fun interactionContext(): AccountBrowseInteractionContext = AccountBrowseInteractionContext(
        phase = accountBrowsePhase,
        isMainActivity = socialBrowsePort.isMainActivity(accountBrowseSession.currentActivityClass),
        isDetailActivity = socialBrowsePort.isDetailActivity(accountBrowseSession.currentActivityClass),
        touchActive = accountBrowseSession.touchActive,
        lastGlobalTouchEventAt = accountBrowseSession.lastGlobalTouchEventAt,
        lastGlobalTouchEventName = accountBrowseSession.lastGlobalTouchEventName,
        lastGlobalTouchEventOrigin = accountBrowseSession.lastGlobalTouchEventOrigin,
        gestureInFlight = accountBrowseSession.gestureInFlight,
        ignoreManualEventsUntil = accountBrowseSession.ignoreManualEventsUntil,
        programmaticActionLabel = accountBrowseSession.programmaticActionLabel,
        dynamicTabWaitStartedAt = accountBrowseSession.dynamicTabWaitStartedAt,
        feedRefreshRecoveryActive = accountBrowseSession.feedRefreshRecoveryActive,
        detailDwellStartedAt = accountBrowseSession.detailDwellStartedAt,
        feedRefreshActionAt = accountBrowseSession.feedRefreshActionAt
    )

    internal fun isTrackedInteractionEvent(eventType: AccessibilityEventType): Boolean =
        interactionEventClassifier.isTrackedInteractionEvent(eventType)

    internal fun classifyInteractionEventOrigin(
        eventType: AccessibilityEventType,
        now: Long,
        className: String
    ): String = interactionEventClassifier.classifyOrigin(
        eventType, now, isRecyclerViewClass(className), interactionContext()
    )

    /** Diagnostic-only evidence classification; this method does not affect control flow. */
    internal fun classifyInteractionEventEvidence(
        event: AccessibilityEventEnvelope,
        now: Long
    ): AccountBrowseInteractionEvidence = interactionEvidenceClassifier.classifyTargetEvent(
        event = event,
        now = now,
        isRecyclerView = isRecyclerViewClass(event.className),
        context = interactionContext(),
        lastProgrammaticScrollAt = accountBrowseSession.lastProgrammaticScrollAt,
        lastProgrammaticScrollLabel = accountBrowseSession.lastProgrammaticScrollLabel
    )

    /**
     * Some devices do not deliver global touch-interaction events to this service.
     * Infer manual interaction from the normalized, short-lived event evidence only when
     * it does not match an accessibility action that Freedom itself just submitted.
     */
    internal fun handleManualInteractionFallback(event: AccessibilityEventEnvelope) {
        val eventType = event.type
        if (!isTrackedInteractionEvent(eventType)) return
        val now = System.currentTimeMillis()
        val interactionContext = interactionContext()
        if (interactionEventClassifier.isProgrammaticInteractionEvent(eventType, now, interactionContext)) return
        val startupSuppressionLog = interactionEventClassifier.startupScrollSuppressionLog(
            eventType = eventType,
            now = now,
            isRecyclerView = isRecyclerViewClass(event.className),
            context = interactionContext
        )
        if (startupSuppressionLog != null) {
            addLog(startupSuppressionLog)
            return
        }
        if (accountBrowseSession.imageBrowserActive ||
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN ||
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE
        ) return

        val eventName = AccessibilityEventFormatter.rawEventName(
            AndroidAccessibilityEventNormalizer.toAndroidEventType(eventType)
        )
        val content = event.eventText.joinToString(" ").trim().take(300)
        val description = event.contentDescription?.take(180).orEmpty()
        val className = event.className
        val target = sequenceOf(content, description, className).firstOrNull { it.isNotBlank() }
            ?.take(120) ?: "未提供控件文本"
        val sourceDetails = AccessibilityEventFormatter.sourceDetails(event.source)
        val evidence = classifyInteractionEventEvidence(event, now)
        accountBrowseSession.lastTargetInteractionAt = now
        accountBrowseSession.lastTargetInteractionCategory = evidence.category
        accountBrowseSession.lastTargetInteractionDescription = evidence.description
        val reason = if (evidence.hasIndependentTouchEvidence) {
            "检测到与全局触摸证据相关的目标应用交互事件（$eventName；控件：${className.substringAfterLast('.')}；内容：$target）"
        } else {
            "检测到目标应用交互事件，来源未完全确认（$eventName；控件：${className.substringAfterLast('.')}；内容：$target）；按现有安全策略作为人工操作候选"
        }

        addLog(
            "$reason；Activity=${accountBrowseSession.currentActivityClass.ifBlank { "未知" }}；" +
                "自动浏览阶段=${accountBrowsePhase.name}；${currentMonitorPageContext()}；" +
                "事件证据分类=${evidence.category}；${evidence.description}；$sourceDetails"
        )
        pauseAccountBrowse("$reason；停止危险操作并交由统一恢复观察")

        cancelManualTouchResume()
        if (accountBrowseSession.touchActive) {
            addLog("目标控件事件发生时全局触摸仍处于活动状态；暂不启动静默倒计时，等待 TYPE_TOUCH_INTERACTION_END")
        } else {
            addLog("已记录目标控件交互；最后一次操作后等待第一轮静默 5 秒，再重新识别当前页面")
            scheduleManualTouchResume()
        }
    }

    private fun checkFeedListReturned() {
        val page = obtainTargetPageOrPause(pauseWhenUnavailable = false)
        if (page == null) {
            accountBrowseSession.returnedStableSamples = 0
            accountBrowseSession.lastReturnedSnapshot = ""
            addLog("返回后的活动窗口暂时不可读取；保留原动态详情/列表导航上下文，等待新鲜节点树，不发送额外 BACK 或点击")
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        if (isRetainedObstructionEvidence(page.reason)) {
            addLog("动态详情返回后检测到临时遮挡；仅保留上一页面上下文，不认定已经返回列表，等待遮挡消失后重新确认")
            accountBrowseSession.returnedStableSamples = 0
            accountBrowseSession.lastReturnedSnapshot = ""
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        val feed = socialBrowsePort.inspectFeed()
        val isFeedList = feed.ready
        val currentSnapshot = if (isFeedList && feed.scrollBounds != null) {
            socialBrowsePort.captureVisibleFeedSnapshot(listOnly = true)
        } else ""
        val positionMatches = isFeedList && scrollExecutor.isFeedPositionRestored(accountBrowseSession.sourceSnapshot, currentSnapshot)
        val stability = FeedReturnStability.update(
            previousSnapshot = accountBrowseSession.lastReturnedSnapshot,
            previousSamples = accountBrowseSession.returnedStableSamples,
            currentSnapshot = currentSnapshot,
            isFeedPage = isFeedList,
            positionMatches = positionMatches,
            requiredSamples = REQUIRED_STABLE_PAGE_SAMPLES
        )
        accountBrowseSession.lastReturnedSnapshot = stability.lastSnapshot
        accountBrowseSession.returnedStableSamples = stability.stableSamples
        val decision = FeedReturnVerificationPolicy.decide(
            rootAvailable = true,
            isFeedPage = isFeedList,
            positionMatches = positionMatches,
            positionStable = stability.isStable,
            deadlineReached = System.currentTimeMillis() >= accountBrowseSession.deadlineAt
        )
        when (decision) {
            FeedReturnVerificationPolicy.Decision.RESTORED -> {
                accountBrowseSession.detailActivityClass = ""
                accountBrowseSession.detailFingerprint = ""
                accountBrowseSession.programmaticActionLabel = ""
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                accountBrowseSession.returnedStableSamples = 0
                accountBrowseSession.lastReturnedSnapshot = ""
                accountBrowseSession.returnMismatchLogged = false
                val delay = scrollExecutor.randomScrollIntervalMs()
                addLog("已返回原动态列表，位置快照连续 ${REQUIRED_STABLE_PAGE_SAMPLES} 次匹配；下一轮滑动将在随机等待 ${delay / 1000.0} 秒后进行")
                scheduleAccountBrowseStep(delay)
                return
            }
            FeedReturnVerificationPolicy.Decision.PAUSE_ROOT_UNAVAILABLE -> {
                beginDynamicFeedRefreshRecovery("返回后持续无法读取当前页面")
                return
            }
            FeedReturnVerificationPolicy.Decision.PAUSE_POSITION_UNCONFIRMED -> {
                beginDynamicFeedRefreshRecovery("已返回动态列表，但无法确认恢复到打开动态前的位置")
                return
            }
            FeedReturnVerificationPolicy.Decision.PAUSE_NOT_FEED_PAGE -> {
                beginDynamicFeedRefreshRecovery("返回后没有重新识别到动态列表")
                return
            }
            FeedReturnVerificationPolicy.Decision.WAIT -> {
                if (isFeedList && !positionMatches && !accountBrowseSession.returnMismatchLogged) {
                    addLog("已识别动态列表，但当前可见内容与打开动态前的位置快照不匹配；暂不继续滑动，等待列表位置恢复并稳定")
                    accountBrowseSession.returnMismatchLogged = true
                }
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            }
        }
    }

    /** A return is accepted only when several list-specific visible nodes and bounds match. */
    private fun ScreenRect.toShortString(): String = "[$left,$top][$right,$bottom]"

    companion object {

        private const val ACCOUNT_BROWSE_PAGE_OBSERVATION_CHANNEL = "ACCOUNT_BROWSE_PAGE_OBSERVATION"
        private const val PAGE_OBSERVATION_QUIET_PERIOD_MS = 300L
        private const val PAGE_OBSERVATION_MAX_WAIT_MS = 1_200L
        private val PAGE_OBSERVATION_PHASES = setOf(
            AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH,
            AccountBrowsePhase.WAIT_FOR_FEED_STABLE,
            AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE,
            AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD,
            AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN,
            AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE,
            AccountBrowsePhase.WAIT_FOR_LIST_RETURN
        )

        private const val NAV_ACTION_DYNAMIC_TAB = "DYNAMIC_TAB"
        private const val NAV_ACTION_HOME_TAB = "HOME_TAB"
        private const val NAV_ACTION_BACK = "BACK"
        private const val NAVIGATION_ACTION_EVENT_WINDOW_MS = 1_800L
        private const val NAVIGATION_ACTION_VERIFY_TIMEOUT_MS = 2_100L
        private const val NAVIGATION_ACTION_VERIFY_POLL_MS = 350L
        private const val NAVIGATION_ACTION_RETRY_DELAY_MS = 700L
        private const val MAX_RECOVERY_TAB_CLICK_ATTEMPTS = 2
        private const val MAX_RECOVERY_HOME_CLICK_ATTEMPTS = 2
        private const val MAX_RECOVERY_BACK_STEPS = 4
        private const val HOME_REFRESH_TAP_DURATION_MS = 90L
        private const val COORDINATE_TAP_DURATION_MS = 90L
        private const val HOME_REFRESH_TAP_INTERVAL_MS = 380L
        private const val HOME_REFRESH_PROGRAMMATIC_EVENT_WINDOW_MS = 1_800L
        private const val DYNAMIC_FEED_REFRESH_VERIFY_TIMEOUT_MS = 10_000L
        private const val MAX_DYNAMIC_FEED_REFRESH_ATTEMPTS = 2
        private const val REQUIRED_DYNAMIC_REFRESH_STABLE_SAMPLES = 3
        private const val POPUP_VERIFY_POLL_MS = 350L
        private const val POPUP_DISMISS_VERIFY_TIMEOUT_MS = 2_500L
        private const val POPUP_RETRY_DELAY_MS = 900L
        private const val POPUP_BLOCKED_RECHECK_MS = 1_000L
        private const val MAX_POPUP_DISMISS_ATTEMPTS = 2

        private const val MIN_BROWSE_DELAY_MS = 3_000L
        private const val MAX_BROWSE_DELAY_MS = 15_000L
        private const val MIN_SCROLL_INTERVAL_MS = 5_000L
        private const val MAX_SCROLL_INTERVAL_MS = 8_000L
        private const val DETAIL_LOAD_TIMEOUT_MS = 12_000L
        private const val LIST_RETURN_TIMEOUT_MS = 10_000L
        private const val PAGE_STATE_POLL_MS = 300L
        private const val SCROLL_SETTLE_MS = 850L
        private const val CARD_OPEN_SETTLE_MS = 500L
        private const val MONITOR_OVERLAY_PASSTHROUGH_WATCHDOG_MS = 5_000L
        private const val SWIPE_WINDOW_SYNC_DELAY_MS = 120L
        private const val IMAGE_BROWSER_RETURN_TIMEOUT_MS = 60_000L
        private const val DETAIL_RESTABILIZE_TIMEOUT_MS = 10_000L
        private const val SAFE_CARD_RECHECK_INTERVAL_MS = 2_500L
        private const val MAX_SAFE_CARD_RECHECKS_BEFORE_NEXT_SWIPE = 2
        private const val PROGRAMMATIC_CLICK_EVENT_WINDOW_MS = 900L
        private const val INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS = 60_000L
        private const val MANUAL_RECOVERY_NAVIGATION_TIMEOUT_MS = 15_000L
        private const val INITIAL_FEED_STABILITY_TIMEOUT_MS = 10_000L
        private const val REQUIRED_STABLE_FEED_SAMPLES = 2
        private const val FIRST_SWIPE_COUNTDOWN_SECONDS = 3
        private const val FIRST_SWIPE_COUNTDOWN_STEP_MS = 1_000L
        private const val REQUIRED_STABLE_PAGE_SAMPLES = 2
        private const val MIN_RETURN_SNAPSHOT_MATCH_LINES = 3
        private const val RETURN_SNAPSHOT_MATCH_RATIO = 0.70
        private const val MIN_DETAIL_NODE_COUNT = 12

        private const val MIN_FEED_CARD_WIDTH_RATIO = 0.75f
        private const val MIN_FEED_CARD_HEIGHT_RATIO = 0.18f


    }
}
