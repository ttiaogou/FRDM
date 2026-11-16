package com.freedom.features.aireply

import com.freedom.gesture.api.GestureApi
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.event.AccessibilityEventPolicy
import com.freedom.core.accessibility.events.AccessibilityUiSignal
import com.freedom.core.accessibility.events.InputOrigin
import com.freedom.core.accessibility.events.InputOriginDecision
import com.freedom.core.accessibility.events.InputOriginEvidence
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UnknownInteractionArbiter
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.contact.TemporaryContactMessageLedger
import com.freedom.features.aireply.domain.contact.ContactSendCountUpdate
import com.freedom.features.aireply.ports.ChatMessagePort
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ChatSessionManagementPort
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.features.aireply.ports.ChatPageSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns AI-mode page observation, bounded chat-region refresh and bounded message-read scheduling.
 * It has no Android or social-app dependency. Message bodies are passed synchronously to an
 * ephemeral callback. Raw bodies are never logged by default; the explicit debug-only OCR
 * validation payload is logged only after active-window revalidation.
 */
class AiReplyObservationRuntime(
    private val scheduler: TaskScheduler,
    private val chatPagePort: ChatPagePort,
    private val chatMessagePort: ChatMessagePort,
    private val chatSessionManagementPort: ChatSessionManagementPort,
    private val gesturePolicy: GestureApi,
    private val targetPackage: () -> String,
    private val isAiReplyActive: () -> Boolean,
    private val onLog: (String) -> Unit,
    private val fullInspectionDebounceMs: Long,
    private val regionRefreshDebounceMs: Long,
    private val onMessagesObserved: (ChatMessageBatch) -> Unit = {},
    private val isInputManualReviewRequired: () -> Boolean = { false },
    private val onManualReviewRequired: (String) -> Unit = {},
    private val onRequestScreenOff: () -> Boolean = { false },
    /** Monotonic clock for diagnosing delayed main-thread recovery-watch execution. */
    private val uptimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Inclusive random bounds are injectable so task scheduling can be tested deterministically. */
    private val chooseRandomInt: (Int, Int) -> Int = { minimum, maximum ->
        java.util.concurrent.ThreadLocalRandom.current().nextInt(minimum, maximum + 1)
    },
    /** Shared task/UI ownership; nullable only for isolated legacy unit fixtures. */
    private val taskCoordinator: TaskCoordinator? = null,
    private val currentRunGeneration: () -> Long = { 0L }
) {
    companion object {
        private const val MAX_LOGGED_STRUCTURE_EVIDENCE = 256
        private const val MAX_STRUCTURE_LOG_LINE_CHARS = 3_500
        private const val MAX_VISIBLE_MESSAGES = 20
        private const val MESSAGE_LIST_RETRY_DELAY_MS = 120L
        private const val MAX_MESSAGE_LIST_RETRIES = 2
        /** Slightly exceeds the adapter's 60s OCR batch timeout as a final callback safeguard. */
        private const val MESSAGE_READ_CALLBACK_TIMEOUT_MS = 65_000L
        private val TRANSIENT_EMPTY_SNAPSHOT_RETRY_DELAYS_MS = longArrayOf(350L, 700L, 1_200L)
        private const val MAX_OCR_DEBUG_TEXT_CHARS = 4_200
        private const val MAX_OCR_DEBUG_BATCHES_PER_WINDOW = 24
        private const val SESSION_OPEN_VERIFY_DELAY_MS = 2_200L
        private const val SESSION_RETURN_DELAY_MS = 1_500L
        private const val SESSION_RETURN_VERIFY_DELAY_MS = 900L
        private const val MAX_NO_PROGRESS_SCROLLS = 2
        private const val SESSION_BOUNDARY_SETTLE_DELAY_MS = 650L
        private const val MAX_BOUNDARY_STABILITY_CONFIRMATIONS = 2
        private const val MAX_CANCELLED_SCROLL_RECOVERIES = 2
        private const val SCROLL_CANCEL_RECOVERY_DELAY_MS = 180L
        private const val AI_READINESS_RETRY_DELAY_MS = 400L
        private const val AI_READINESS_RETRY_BACKOFF_MS = 5_000L
        private const val MAX_AI_READINESS_DEFERRED_RETRIES = 75
        private const val STARTUP_MESSAGE_TAB_RETRY_DELAY_MS = 400L
        private const val MAX_STARTUP_MESSAGE_TAB_RETRIES = 75
        private const val MAX_STARTUP_MESSAGE_TAB_TAPS = 1
        private const val SESSION_LIST_STABILITY_RETRY_DELAY_MS = 350L
        /** Re-check transient mf_view overlap without pausing the session-list traversal. */
        private const val SESSION_SAFE_VIEWPORT_RETRY_DELAY_MS = 650L
        private const val SESSION_SAFE_VIEWPORT_RETRY_BACKOFF_MS = 5_000L
        private const val SESSION_SAFE_VIEWPORT_FAST_RETRIES = 8
        private const val LOW_FREQUENCY_IDLE_MIN_SECONDS = 60
        private const val LOW_FREQUENCY_IDLE_MAX_SECONDS = 120
        private const val PRIORITY_RETURN_INSPECTION_DELAY_MS = 500L
        private const val RECEIVED_GREETINGS_RECHECK_DELAY_MS = 1_300L
        private const val RECEIVED_GREETINGS_NAVIGATION_RECHECK_DELAY_MS = 750L
        private const val MAX_SESSION_LIST_STABILITY_RETRIES = 3
        private const val MAX_EVENT_BURST_INSPECTION_WAIT_MS = 1_200L
        private const val USER_CONTROL_STABILITY_CONFIRM_DELAY_MS = 300L
        private const val REQUIRED_USER_CONTROL_STABLE_SAMPLES = 3
        private const val MAX_USER_CONTROL_STABILITY_RETRIES = 10
        private const val USER_CONTROL_NAVIGATION_VERIFY_DELAY_MS = 900L
        private const val MESSAGE_TAB_GUARD_DEBOUNCE_MS = 180L
        private const val USER_HANDOFF_MESSAGE_TAB_WATCH_INTERVAL_MS = 30_000L
        private const val MAX_USER_CONTROL_NAVIGATION_BACK_ATTEMPTS = 4
        private const val MAX_USER_CONTROL_CHAT_TAB_TAPS = 1
        private const val MAX_ARCHIVED_MANUAL_MESSAGE_FINGERPRINTS = 500
        private const val SESSION_TAP_DURATION_MS = 90L
        private const val SESSION_SCROLL_DURATION_MS = 480L
        private const val PRIVATE_MESSAGES_TASK_ID = PrivateMessagesTaskController.TASK_ID
        private const val RECEIVED_GREETINGS_TASK_ID = ReceivedGreetingsTaskController.TASK_ID
        private val PREEMPTIVE_INTERACTION_EVENT_TYPES = setOf(
            AccessibilityEventType.VIEW_CLICKED,
            AccessibilityEventType.VIEW_LONG_CLICKED,
            AccessibilityEventType.VIEW_CONTEXT_CLICKED,
            AccessibilityEventType.VIEW_SELECTED,
            AccessibilityEventType.VIEW_SCROLLED
        )
        private val PAGE_CHANGE_RECHECK_EVENT_TYPES = setOf(
            AccessibilityEventType.WINDOW_STATE_CHANGED,
            AccessibilityEventType.WINDOWS_CHANGED,
            AccessibilityEventType.WINDOW_CONTENT_CHANGED,
            AccessibilityEventType.VIEW_CLICKED,
            AccessibilityEventType.VIEW_LONG_CLICKED,
            AccessibilityEventType.VIEW_CONTEXT_CLICKED,
            AccessibilityEventType.VIEW_SELECTED,
            AccessibilityEventType.VIEW_SCROLLED,
            AccessibilityEventType.VIEW_TEXT_CHANGED
        )
    }

    private val taskOwnership = AiReplyTaskOwnership(
        coordinator = taskCoordinator,
        currentRunGeneration = currentRunGeneration,
        onLog = onLog
    )
    private val privateMessagesTaskController = PrivateMessagesTaskController(
        ownership = taskOwnership,
        chooseRandomInt = chooseRandomInt
    )
    private val receivedGreetingsTaskController = ReceivedGreetingsTaskController(taskOwnership)
    private val receivedGreetingsTaskExecutor = ReceivedGreetingsTaskExecutor(chatPagePort) {
        ownsTask(RECEIVED_GREETINGS_TASK_ID)
    }
    private val privateMessagesTaskExecutor by lazy(LazyThreadSafetyMode.NONE) {
        PrivateMessagesTaskExecutor(
            sessionPort = chatSessionManagementPort,
            gesturePolicy = gesturePolicy,
            inspectFreshPageSnapshot = ::inspectFreshPageSnapshot,
            targetPackage = targetPackage,
            ownsTask = { ownsTask(PRIVATE_MESSAGES_TASK_ID) }
        )
    }
    private val normalCompletedObservationCount: Int
        get() = privateMessagesTaskController.completedObservationCount
    private val normalInboxTaskTargetCount: Int
        get() = privateMessagesTaskController.targetObservationCount
    private val receivedGreetingsTaskActive: Boolean
        get() = receivedGreetingsTaskController.isActive
    private val receivedGreetingsTaskCompletedAwaitingUserExit: Boolean
        get() = receivedGreetingsTaskController.completedAwaitingUserExit
    private val awaitingNormalInboxAfterGreetingsReturn: Boolean
        get() = receivedGreetingsTaskController.awaitingNormalInboxAfterReturn

    private var pageInspectionTask: ScheduledTask? = null
    private var regionRefreshTask: ScheduledTask? = null
    private val inboxPageRecognizer = MomoInboxPageRecognizer()
    private val receivedGreetingsPageRecognizer by lazy(LazyThreadSafetyMode.NONE) {
        MomoReceivedGreetingsPageRecognizer {
            if (userControlPhase == UserControlPhase.TOUCHING ||
                !sessionRunStarted || !receivedGreetingsTaskExecutor.supportsWorkflow()
            ) {
                ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION
            } else {
                receivedGreetingsTaskExecutor.observeManuallyOpenedPage()
            }
        }
    }
    private val pageObservationCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        MomoPageObservationCoordinator(
            scheduler = scheduler,
            fullInspectionDebounceMs = fullInspectionDebounceMs,
            regionRefreshDebounceMs = regionRefreshDebounceMs,
            maxEventBurstInspectionWaitMs = MAX_EVENT_BURST_INSPECTION_WAIT_MS,
            receivedGreetingsPageRecognizer = receivedGreetingsPageRecognizer,
            runFullInspection = ::runFullInspection,
            runRegionRefresh = ::runRegionRefresh,
            onReceivedGreetingsConfirmed = ::startReceivedGreetingsTaskFromConfirmedPage,
            onFallbackCheckRequested = ::requestReceivedGreetingsFallbackCheck,
            fallbackDelayMs = {
                chooseRandomInt(LOW_FREQUENCY_IDLE_MIN_SECONDS, LOW_FREQUENCY_IDLE_MAX_SECONDS)
                    .coerceIn(LOW_FREQUENCY_IDLE_MIN_SECONDS, LOW_FREQUENCY_IDLE_MAX_SECONDS) * 1_000L
            }
        )
    }
    private var messageReadRetryTask: ScheduledTask? = null
    private var messageReadRetryCount = 0
    private var readinessDeferredRetryTask: ScheduledTask? = null
    private var readinessDeferredRetryCount = 0
    private var readinessDeferredRetryLimitLogged = false
    private var readinessDeferredRetryGeneration = 0L
    /** Startup navigation is independent from user-handoff retries and remains bounded. */
    private var lastPageInspection: ChatPageSnapshot? = null
    /** Last positively confirmed inbox anchor; survives only same-window transient UNKNOWN samples. */
    private var lastConfirmedChatListInspection: ChatPageSnapshot? = null
    private var lastRegionFingerprint = ""
    private var lastMessageFingerprint = ""
    private val archivedManualMessageFingerprints = LinkedHashSet<String>()
    private var userControlPhase = UserControlPhase.IDLE
    /** Owns only the tiered user-quiet delay; page sampling/navigation use separate tasks. */
    private var handoffMessageTabWatchScheduledAtUptimeMs: Long? = null
    private var handoffMessageTabWatchDueAtUptimeMs: Long? = null
    private var messageTabProbeInFlight = false
    private var userControlCycle = 0L
    private var userControlInteractionRevision = 0L
    private var messageTabPositionConfirmedForCycle = false
    private var messageTabNavigationAnchorBeforeTap: String? = null
    private var pendingMessageTabGuardReason = ""
    private var pendingMessageTabGuardRestartsQuiet = false
    private var pendingMessageTabGuardFallsBackToQuiet = false
    private val manualTakeoverCoordinator = AiReplyManualTakeoverCoordinator(scheduler)
    private var pendingUserPageInspection = false
    private var userControlNavigationBackAttempts = 0
    private var userControlChatTabTapAttempts = 0
    private var userControlNavigationInFlight = false
    /** True after a user hand-off until a stable, eligible target page re-arms automation. */
    private var automationYieldedToUser = false
    /** Compatibility gate for an older persisted review latch; new unknown-source inputs never set it. */
    private var manualReviewRequired = false
    private var messageReadInFlight = false
    private var messageReadTimeoutTask: ScheduledTask? = null
    private var pendingMessageReadSnapshot: ChatPageSnapshot? = null
    private var messageReadGeneration = 0L
    private val loggedOcrDebugFingerprints = LinkedHashSet<String>()
    private val temporaryContactMessageLedger = TemporaryContactMessageLedger(threshold = 10)
    /** Session keys awaiting an externally verified intercept-toggle result, in threshold order. */
    private val pendingInterceptionContactKeys = LinkedHashSet<String>()
    private var ocrDebugBatchLimitLogged = false

    private val startupReadinessCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        AiReplyStartupReadinessCoordinator(
            scheduler = scheduler,
            chatPagePort = chatPagePort,
            targetPackage = targetPackage,
            isAiReplyActive = isAiReplyActive,
            isSessionRunStarted = { sessionRunStarted },
            isAutomationYieldedToUser = { automationYieldedToUser },
            isUserControlIdle = { userControlPhase == UserControlPhase.IDLE },
            isManualReviewRequired = ::manualReviewIsRequired,
            inspectFreshPageSnapshot = ::inspectFreshPageSnapshot,
            isMessageViewportPositionedAgainstTab = ::isMessageViewportPositionedAgainstTab,
            hasRelevantMessageStructureDelta = ::hasRelevantMessageStructureDelta,
            onConfirmed = { snapshot ->
                sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                onLog(
                    "AI 启动导航[CONFIRMED]：消息工作区域、底部导航锚点和会话列表视口已连续" +
                        "3 次新快照确认；重新执行未读准入审计"
                )
                handleInspectionSnapshot(snapshot)
            },
            onTimedOut = { sessionManagerState = SessionManagerState.PAUSED },
            onLog = onLog
        )
    }

    private enum class UserControlPhase {
        IDLE,
        TOUCHING,
        SETTLING,
        VERIFYING_STABILITY
    }

    /** Accessibility update categories are diagnostic only; none is itself a touch signal. */
    private enum class UiChangeKind {
        MESSAGE_DATA_UPDATE,
        PAGE_NAVIGATION_UPDATE,
        PAGE_STRUCTURE_UPDATE,
        AD_CONTENT_UPDATE,
        OTHER_UPDATE
    }

    private enum class SessionManagerState {
        STOPPED,
        POSITIONING_SESSION_LIST_AT_BOTTOM,
        SCANNING_SESSION_LIST_BOTTOM_TO_TOP,
        WAITING_FOR_SESSION_LIST,
        CAPTURING_SESSION_LIST,
        OPENING_SESSION,
        READING_SESSION,
        RETURNING_TO_SESSION_LIST,
        SCROLLING_SESSION_LIST,
        WAITING_FOR_UNREAD,
        BROWSING_RECEIVED_GREETINGS,
        COMPLETED,
        PAUSED
    }

    private enum class SessionListTraversalPhase {
        POSITIONING_AT_BOTTOM,
        SCANNING_BOTTOM_TO_TOP
    }

    private var sessionManagerState = SessionManagerState.STOPPED
    private var sessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
    private var sessionListAtTop = false
    /** Archive lifecycle is independent from the optional automatic session traversal state. */
    private var sampleCaptureActive = false
    private var passiveManualObservation = false
    private var sessionRunStarted = false
    /** Startup stays provisional until the first autonomous gesture is accepted by Android. */
    /** Captured before a hand-off so passive-only capture can never become autonomous browsing. */
    private var userHandoffCanResumeAutonomous = false
    private var currentSessionKey: String? = null
    private var currentSessionName: String? = null
    private var currentSessionCandidate: ChatListCandidateSnapshot? = null
    private var currentSessionReadCaptured = false
    private var currentSessionWindowKey: String? = null
    /** Prevents an event-driven inspection and delayed fallback from recording the same mismatch twice. */
    private val headerMismatchGuard = Any()
    private var headerMismatchRecordedForCurrentAttempt = false
    private var sessionTransitionTask: ScheduledTask? = null
    /** Retains the scroll direction while the live mf_view exclusion leaves no safe rectangle. */
    private var sessionSafeViewportRetryTask: ScheduledTask? = null
    private var sessionSafeViewportRetryCount = 0
    private var sessionSafeViewportRetryGeneration = 0L
    private val unknownInteractionArbiter = UnknownInteractionArbiter()
    private var unknownCounterResetTask: ScheduledTask? = null
    private var unknownCounterResetRevision = 0L
    private var lastUnknownBehaviourAtUptimeMs = Long.MIN_VALUE
    private val attemptedSessionKeys = LinkedHashSet<String>()
    /** Names colliding within a visible list snapshot stay blocked for the entire run. */
    private val ambiguousSessionIdentityKeys = LinkedHashSet<String>()
    private var sessionActionCount = 0
    /** Private-message quota state is owned by PrivateMessagesTaskController. */
    private var initialInboxPositionCheckCompleted = false
    /** True only after Android reports completion of the mandatory first upward list swipe. */
    private var initialUnreadGateSwipeCompleted = false
    /** Stable identities of explicitly unread private rows observed while positioning at the bottom. */
    private val lockedUnreadSessionKeys = LinkedHashSet<String>()
    /** Becomes true once the bottom boundary is verified; later new unread rows are next-cycle work. */
    private var lockedUnreadSetFinalized = false
    private var normalInboxHadUnread = false
    private var normalInboxUnreadStateUnknown = false
    /** Set by relevant target-app page-change events; probe admission never consults InputOrigin. */
    private var receivedGreetingsPageProbePending = false
    private var sessionListStabilityRetryCount = 0
    /** Short-lived empty accessibility roots are retried without replacing the last confirmed page. */
    private val transientEmptySnapshotRecovery = AiReplySessionTraversalCoordinator(
        TRANSIENT_EMPTY_SNAPSHOT_RETRY_DELAYS_MS
    )
    /** Generation invalidates callbacks from gestures submitted before a human hand-off. */
    private var sessionActionGeneration = 0L
    private var activeSessionGestureRequestId: String? = null
    private var lastUnreadAuditFingerprint = ""
    private var noProgressScrollCount = 0
    private var pendingScrollBaseline: List<String>? = null
    private var pendingScrollToTop = false
    private var lastStableSessionListVisibleKeys: List<String>? = null
    private var cancelledScrollRecoveryCount = 0
    /** Viewports are archived independently from whether their rows are eligible for navigation. */
    private val capturedSessionListFingerprints = LinkedHashSet<String>()
    /** A no-motion boundary is provisional until delayed, independent fresh snapshots agree. */
    private var pendingBoundaryConfirmationKeys: List<String>? = null
    private var pendingBoundaryConfirmationToTop = false
    private var boundaryStabilityConfirmationCount = 0


    /**
     * Low-frequency watcher callback. It requests a read-only page inspection or, during an
     * existing hand-off, lets the established quiet-period guard perform the same watcher-first
     * check. It never navigates by itself and never bypasses a manual-review latch.
     */
    private fun requestReceivedGreetingsFallbackCheck() {
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive ||
            receivedGreetingsTaskCompletedAwaitingUserExit ||
            !receivedGreetingsTaskExecutor.supportsWorkflow()
        ) return
        if (manualReviewIsRequired()) {
            pageObservationCoordinator.resetReceivedGreetingsWatch()
            onLog("收到的招呼页面监听[FALLBACK_HELD]：当前存在待人工复核锁定；不自动接管任务")
            return
        }

        receivedGreetingsPageProbePending = true
        onLog("收到的招呼页面监听[LOW_FREQUENCY_FALLBACK]：事件静默期间执行一次只读页面复核；不依据输入来源分类")
        when {
            userControlPhase != UserControlPhase.IDLE -> {
                pendingUserPageInspection = true
            }
            automationYieldedToUser -> {
                requestMessageTabGuard(
                    reason = "收到的招呼页面低频兜底复核",
                    restartQuietWhenUnconfirmed = false,
                    fallBackToQuietWhenUnconfirmed = false
                )
            }
            else -> pageObservationCoordinator.signalFullInspection()
        }
    }

    private fun runFullInspection() {
        pageInspectionTask = null
        if (!isAiReplyActive() || !chatPagePort.supportsTargetPackage(targetPackage())) return
        if (sessionRunStarted && receivedGreetingsTaskActive &&
            !ownsTask(RECEIVED_GREETINGS_TASK_ID)
        ) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("收到的招呼任务观察被阻止：当前任务已不持有 UI 操作权；等待安全恢复")
            return
        }
        if (userControlPhase != UserControlPhase.IDLE) {
            pendingUserPageInspection = true
            return
        }
        val inspection = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
        if (transientEmptySnapshotRecovery.isTransient(inspection)) {
            val delayMs = transientEmptySnapshotRecovery.nextDelayMs()
            if (delayMs != null) {
                onLog(
                    "AI 被动观察[SNAPSHOT_RECOVERY]：页面快照为空或暂不可用；" +
                        "retry=${transientEmptySnapshotRecovery.retryCount}/${TRANSIENT_EMPTY_SNAPSHOT_RETRY_DELAYS_MS.size}；" +
                        "${delayMs}ms 后重新获取；保留最后一次已确认页面，不更新页面身份"
                )
                scheduleFullInspectionAfter(delayMs)
            } else {
                onLog(
                    "AI 被动观察[SNAPSHOT_RECOVERY_EXHAUSTED]：连续 ${TRANSIENT_EMPTY_SNAPSHOT_RETRY_DELAYS_MS.size} 次快照恢复仍为空；" +
                        "保留最后一次已确认页面，等待下一次界面事件后重新检查"
                )
            }
            return
        }
        transientEmptySnapshotRecovery.reset()
        handleInspectionSnapshot(inspection!!)
    }

    /** Applies a newly acquired page snapshot after ordinary debounce or verified user stability. */
    private fun handleInspectionSnapshot(incomingInspection: ChatPageSnapshot) {
        if (sessionRunStarted && !receivedGreetingsTaskCompletedAwaitingUserExit) {
            val requiredTask = if (receivedGreetingsTaskActive) {
                RECEIVED_GREETINGS_TASK_ID
            } else {
                PRIVATE_MESSAGES_TASK_ID
            }
            if (!ownsTask(requiredTask)) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("页面状态机处理被阻止：当前任务未持有 UI 操作权；task=$requiredTask；仅保留只读页面观察")
                return
            }
        }
        val previous = lastPageInspection
        val inspection = stabilizeIncomingPageSnapshot(incomingInspection)
        if (previous != null && inboxPageRecognizer.isChatDetail(previous) &&
            (!inboxPageRecognizer.isChatDetail(inspection) ||
                !hasSameWindowSession(previous, inspection))
        ) {
            invalidatePendingMessageRead()
        }
        val previousKey = previous?.stableKey()
        val nextKey = inspection.stableKey()
        lastPageInspection = inspection

        // Probe after any relevant target-app page-change event, independently of InputOrigin.
        // Event bursts are coalesced by EventAssistedDebouncer; the listener owns one stability
        // retry and one low-frequency fallback timer. Only adapter-confirmed page structure can
        // transfer ownership to the Received Greetings task.
        if (receivedGreetingsPageProbePending && sessionRunStarted &&
            !receivedGreetingsTaskActive && !receivedGreetingsTaskCompletedAwaitingUserExit &&
            userControlPhase == UserControlPhase.IDLE && !automationYieldedToUser &&
            receivedGreetingsTaskExecutor.supportsWorkflow()
        ) {
            receivedGreetingsPageProbePending = false
            val arrivalStatus = pageObservationCoordinator.onPageSnapshotAvailable()
            when (arrivalStatus) {
                ReceivedGreetingsPageArrivalStatus.NOT_TARGET,
                ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION -> Unit
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED -> return
                ReceivedGreetingsPageArrivalStatus.INTERMEDIATE_PAGE,
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
                ReceivedGreetingsPageArrivalStatus.UNKNOWN -> {
                    pauseForReceivedGreetingsPageTransition(arrivalStatus)
                    return
                }
            }
        }

        if (sessionRunStarted && receivedGreetingsTaskActive &&
            sessionManagerState == SessionManagerState.BROWSING_RECEIVED_GREETINGS
        ) {
            val traversalBefore = runCatching { receivedGreetingsTaskExecutor.traversalStatus() }
                .getOrDefault(ReceivedGreetingsTraversalStatus.UNKNOWN)
            if (traversalBefore == ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("收到的招呼入口未发现：普通消息列表已完成双向搜索并复核两端边界；这是已确认的入口缺失结果，不是单次页面核验失败")
                return
            }
            if (traversalBefore == ReceivedGreetingsTraversalStatus.COMPLETE) {
                if (completeReceivedGreetingsTaskAtCurrentPage()) return
                val returned = runCatching {
                    receivedGreetingsTaskExecutor.returnForPriorityCheck()
                }.getOrDefault(false)
                if (returned) {
                    receivedGreetingsTaskController.markAwaitingNormalInboxAfterReturn()
                    sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                    onLog("收到的招呼遍历已通过适配器完整性检查；已请求返回普通消息页，等待页面身份复核")
                    scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
                } else {
                    scheduleReceivedGreetingsRecoveryRetry("遍历已确认到达边界，但本轮未通过安全返回检查；保留完成快照和任务现场")
                }
                return
            }
            if (traversalBefore == ReceivedGreetingsTraversalStatus.DETAIL_READY &&
                inspection.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE
            ) {
                scheduleReceivedGreetingsRecoveryRetry(
                    "适配器报告详情候选但当前页面快照尚非聊天详情；等待同窗口新鲜快照，不进入普通消息列表流程"
                )
                return
            }
            if (traversalBefore != ReceivedGreetingsTraversalStatus.DETAIL_READY) {
                // Keep invoking the adapter's bounded idle step even when the current page is
                // still the ordinary CHAT_LIST. Entry discovery can require scrolling through
                // the Message list; one failed viewport lookup must not silently strand the task.
                val observed = runCatching { receivedGreetingsTaskExecutor.performIdleStep() }
                    .getOrDefault(false)
                val nextStatus = runCatching { receivedGreetingsTaskExecutor.traversalStatus() }
                    .getOrDefault(ReceivedGreetingsTraversalStatus.UNKNOWN)
                if (!observed) {
                    if (nextStatus == ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("收到的招呼导航暂停：已完成普通消息列表双向搜索，入口未出现")
                    } else {
                        scheduleReceivedGreetingsRecoveryRetry(
                            "页面身份/安全导航步骤暂未确认；保留导航现场，不盲目点击或返回"
                        )
                    }
                } else {
                    receivedGreetingsTaskController.resetRecoveryRetryCount()
                    when (nextStatus) {
                        ReceivedGreetingsTraversalStatus.COMPLETE -> {
                            if (completeReceivedGreetingsTaskAtCurrentPage()) return
                            onLog("收到的招呼导航：适配器报告列表边界候选；仍需完成安全返回与普通列表页面复核")
                            val returned = runCatching {
                                receivedGreetingsTaskExecutor.returnForPriorityCheck()
                            }.getOrDefault(false)
                            if (returned) {
                                receivedGreetingsTaskController.markAwaitingNormalInboxAfterReturn()
                                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                                scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
                            } else {
                                scheduleReceivedGreetingsRecoveryRetry("列表完整性刚刚确认，但安全返回暂未通过")
                            }
                            return
                        }
                        ReceivedGreetingsTraversalStatus.SCANNING -> {
                            // The generic page inspector can still call the transition screen
                            // NON_CHAT while the adapter has independently confirmed the
                            // received-greetings list. Keep this adapter-owned workflow alive;
                            // do not strand the list merely because the generic classifier lags.
                            onLog("收到的招呼只读测试：已验证列表页面；优先采集未读行头像身份和聊天详情 OCR，完成后再滚动；不回复")
                            scheduleFullInspectionAfter(RECEIVED_GREETINGS_RECHECK_DELAY_MS)
                        }
                        ReceivedGreetingsTraversalStatus.PROFILE_CARD -> {
                            onLog("收到的招呼导航：已识别资料卡；等待 NewSayHiSessionListActivity；继续复核顶部‘列表’控件；不触碰通过/拒绝按钮")
                            scheduleFullInspectionAfter(RECEIVED_GREETINGS_RECHECK_DELAY_MS)
                        }
                        ReceivedGreetingsTraversalStatus.NAVIGATING -> {
                            onLog("收到的招呼导航：入口定位/页面切换仍在进行；保留导航阶段并等待新快照")
                            scheduleFullInspectionAfter(RECEIVED_GREETINGS_NAVIGATION_RECHECK_DELAY_MS)
                        }
                        ReceivedGreetingsTraversalStatus.UNKNOWN -> {
                            // UNKNOWN is an explicit adapter status change, not proof that the
                            // ordinary inbox is safe to inspect. Keep the greetings workflow
                            // active, report the transition, and retry with the bounded
                            // navigation cadence without sampling stale inbox rows.
                            onLog("收到的招呼导航：页面状态已变化为 UNKNOWN；保留导航阶段并等待新快照")
                            scheduleFullInspectionAfter(RECEIVED_GREETINGS_NAVIGATION_RECHECK_DELAY_MS)
                        }
                        ReceivedGreetingsTraversalStatus.DETAIL_READY,
                        ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND -> {
                            onLog("收到的招呼导航：页面状态已变化为 ${nextStatus.name}；等待下一次页面级复核")
                            scheduleFullInspectionAfter(RECEIVED_GREETINGS_RECHECK_DELAY_MS)
                        }
                    }
                }
                return
            }
        }
        if (sessionRunStarted && startupReadinessCoordinator.initializationPending &&
            chatPagePort.supportsVerifiedMessageWorkAreaNavigation() && !startupReadinessCoordinator.workAreaConfirmed
        ) {
            // Startup page events are useful readiness signals, but a raw CHAT_LIST classification
            // must not bypass the Message-tab/viewport proof owned by the startup gate.
            return
        }
        if (sessionRunStarted && inspection.pageKind == ChatPageKind.UNKNOWN &&
            sessionManagerState in setOf(
                SessionManagerState.WAITING_FOR_SESSION_LIST,
                SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM,
                SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
            ) && (previous?.pageKind == ChatPageKind.CHAT_LIST || sessionListStabilityRetryCount > 0)
        ) {
            scheduleSessionListStabilityRetry("页面结构暂时不可用")
            return
        }
        if (inspection.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE) {
            lastRegionFingerprint = ""
        }
        if (previousKey != nextKey || inspection.pageKind == ChatPageKind.UNKNOWN) {
            onLog("AI 模式页面结构识别：${summarize(inspection)}")
            logStructureEvidence(inspection)
        }
        when (inspection.pageKind) {
            ChatPageKind.CHAT_LIST -> {
                when {
                    sessionRunStarted && !automationYieldedToUser && awaitingNormalInboxAfterGreetingsReturn -> {
                        receivedGreetingsTaskController.markTraversalReturnedToInbox()
                        sessionActionCount = 0
                        privateMessagesTaskController.resetQuota()
                        // The greeting branch returned to the already-established inbox context.
                        // Start a fresh quota/identity set without replaying the startup admission swipe.
                        lockedUnreadSessionKeys.clear()
                        lockedUnreadSetFinalized = false
                        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                        attemptedSessionKeys.clear()
                        initialInboxPositionCheckCompleted = true
                        sessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
                        sessionListAtTop = false
                        noProgressScrollCount = 0
                        pendingScrollBaseline = null
                        pendingScrollToTop = false
                        lastStableSessionListVisibleKeys = null
                        normalInboxHadUnread = false
                        normalInboxUnreadStateUnknown = false
                        val eligibleUnread = inspection.chatListCandidates.count {
                            it.unreadStatus == ChatUnreadStatus.UNREAD && ChatSessionManagementPolicy.isEligible(it)
                        }
                        val eligibleUnknown = inspection.chatListCandidates.count {
                            it.unreadStatus == ChatUnreadStatus.UNKNOWN && ChatSessionManagementPolicy.isEligible(it)
                        }
                        onLog("AI Reply 测试遍历：收到的招呼列表已返回普通消息页；当前位置明确未读=$eligibleUnread；未知=$eligibleUnknown")
                        when {
                            eligibleUnknown > 0 -> {
                                normalInboxUnreadStateUnknown = true
                                sessionManagerState = SessionManagerState.PAUSED
                                onLog("AI Reply 测试遍历暂停：返回普通消息页存在 UNKNOWN 未读状态；不将未知判定为无消息")
                            }
                            eligibleUnread > 0 -> {
                                normalInboxHadUnread = true
                                sessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
                                sessionListAtTop = false
                                sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                                handleSessionListPage(inspection)
                            }
                            else -> enterUnreadWait("从收到的招呼返回普通消息页，当前位置没有符合条件的新消息")
                        }
                    }
                    sessionRunStarted && !automationYieldedToUser -> handleSessionListPage(inspection)
                    isManualObservationEnabled() -> capturePassiveSessionList(inspection)
                }
            }
            ChatPageKind.CHAT_DETAIL_CANDIDATE -> {
                if (sessionRunStarted && receivedGreetingsTaskActive &&
                    sessionManagerState == SessionManagerState.BROWSING_RECEIVED_GREETINGS
                ) {
                    val detailStatus = runCatching { receivedGreetingsTaskExecutor.traversalStatus() }
                        .getOrDefault(ReceivedGreetingsTraversalStatus.UNKNOWN)
                    if (detailStatus == ReceivedGreetingsTraversalStatus.DETAIL_READY) {
                        // This is the intentionally read-only OCR validation path. It never enters
                        // the ordinary inbox message observer/reply pipeline.
                        readVisibleMessages(inspection)
                    } else {
                        scheduleReceivedGreetingsRecoveryRetry(
                            "ChatActivity 候选已出现，但点击来源/详情指纹尚未全部确认；继续获取新鲜快照，不启动 OCR"
                        )
                    }
                    return
                }
                if (currentSessionKey != null && sessionManagerState == SessionManagerState.OPENING_SESSION) {
                    sessionTransitionTask?.cancel()
                    sessionTransitionTask = null
                    val headerMatchMode = currentSessionHeaderMatchMode(inspection)
                    if (headerMatchMode != null) {
                        sessionManagerState = SessionManagerState.READING_SESSION
                        currentSessionWindowKey = windowSessionKey(inspection)
                        onLog(
                            "AI 会话管理：聊天页顶部名称与目标会话匹配；匹配方式=${headerMatchMode.name}；" +
                                "开始只读采集，不执行输入或发送"
                        )
                    } else {
                        rejectChatHeaderMismatch(inspection, "页面事件检查")
                    }
                }
                if ((isManualObservationEnabled() || !sessionRunStarted ||
                        sessionManagerState == SessionManagerState.READING_SESSION) &&
                    inspection.messageListTarget != null
                ) {
                    readVisibleMessages(inspection)
                } else if (sampleCaptureActive && inspection.messageListTarget == null) {
                    onLog("AI 被动观察：已识别聊天页面，但消息列表定位暂不可用；仅暂停当前页面消息采集，观察仍保持运行")
                }
            }
            ChatPageKind.UNKNOWN, ChatPageKind.NON_CHAT -> {
                if (sessionRunStarted && receivedGreetingsTaskActive &&
                    sessionManagerState == SessionManagerState.BROWSING_RECEIVED_GREETINGS
                ) {
                    val observed = runCatching { receivedGreetingsTaskExecutor.performIdleStep() }
                        .getOrDefault(false)
                    val traversalStatus = runCatching { receivedGreetingsTaskExecutor.traversalStatus() }
                        .getOrDefault(ReceivedGreetingsTraversalStatus.UNKNOWN)
                    when {
                        !observed -> {
                            scheduleReceivedGreetingsRecoveryRetry(
                                if (traversalStatus == ReceivedGreetingsTraversalStatus.SCANNING)
                                    "已识别列表，但本轮快照/安全滚动检查暂未完成"
                                else "无法验证当前页面或提交安全滚动；不执行用户行点击"
                            )
                        }
                        traversalStatus == ReceivedGreetingsTraversalStatus.SCANNING -> {
                            receivedGreetingsTaskController.resetRecoveryRetryCount()
                            onLog("收到的招呼只读测试：已验证列表页面；优先采集未读行头像身份和聊天详情 OCR，完成后再滚动；不回复")
                            scheduleFullInspectionAfter(RECEIVED_GREETINGS_RECHECK_DELAY_MS)
                        }
                        traversalStatus == ReceivedGreetingsTraversalStatus.COMPLETE -> {
                            if (completeReceivedGreetingsTaskAtCurrentPage()) return
                            val returned = runCatching {
                                receivedGreetingsTaskExecutor.returnForPriorityCheck()
                            }.getOrDefault(false)
                            if (returned) {
                                receivedGreetingsTaskController.markAwaitingNormalInboxAfterReturn()
                                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                                onLog("收到的招呼测试遍历：列表扫描到达边界；已请求返回普通消息页，等待页面身份复核")
                                scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
                            } else {
                                scheduleReceivedGreetingsRecoveryRetry("列表边界已确认，但返回普通消息页暂未通过安全检查")
                            }
                        }
                        traversalStatus == ReceivedGreetingsTraversalStatus.PROFILE_CARD -> {
                            scheduleReceivedGreetingsRecoveryRetry(
                                "资料卡顶部‘列表’控件尚未切换到已验证的招呼列表页面；不触碰通过/拒绝按钮"
                            )
                        }
                        else -> {
                            scheduleReceivedGreetingsRecoveryRetry(
                                "入口/页面仍在切换或当前状态暂时未知；不执行盲目滑动或返回"
                            )
                        }
                    }
                }
                if (isManualObservationEnabled()) {
                    val observed = runCatching { receivedGreetingsTaskExecutor.observePageReadOnly() }.getOrDefault(false)
                    if (observed) {
                        onLog("AI 被动观察：已识别并采集人工打开的收到招呼页面；未执行点击或滚动")
                    } else {
                        onLog("AI 被动观察：页面身份暂时无法确认；仅暂停当前页面业务采集，事件观察与归档生命周期保持运行")
                    }
                }
                if (sessionRunStarted && currentSessionKey != null &&
                    sessionManagerState == SessionManagerState.OPENING_SESSION
                ) {
                    // Unexpected Activity after an attempted session click is a hard pause.
                    sessionTransitionTask?.cancel()
                    sessionTransitionTask = null
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("AI 会话管理已暂停：点击后进入非预期页面；不继续点击或盲目返回")
                }
            }
        }
    }

    /**
     * Archives the current inbox viewport after a human navigation event. This path is strictly
     * observational: it never scrolls, resolves tap bounds, selects unread rows, or opens chats.
     */
    private fun capturePassiveSessionList(snapshot: ChatPageSnapshot) {
        val fingerprint = snapshot.stableKey()
        if (!capturedSessionListFingerprints.add(fingerprint)) return
        chatSessionManagementPort.captureSessionListSample(snapshot) { screenshotPath ->
            scheduler.schedule(0L) {
                if (!isAiReplyActive() || !isManualObservationEnabled()) {
                    return@schedule
                }
                onLog(
                    "AI 被动观察：人工当前会话列表视口已归档；识别行=${snapshot.chatListCandidates.size}；" +
                        "明确未读=${snapshot.chatListCandidates.count { it.unreadStatus == ChatUnreadStatus.UNREAD }}；" +
                        "已读=${snapshot.chatListCandidates.count { it.unreadStatus == ChatUnreadStatus.READ }}；" +
                        "未知=${snapshot.chatListCandidates.count { it.unreadStatus == ChatUnreadStatus.UNKNOWN }}；" +
                        "累计不同视口=${capturedSessionListFingerprints.size}；" +
                        "截图=${if (screenshotPath.isNullOrBlank()) "不可用" else "已保存"}；不执行列表手势或点击"
                )
            }
        }
    }

    /**
     * Observes system touch boundaries. UNKNOWN boundaries are correlated with adjacent target
     * feedback and counted as one behaviour; only the third independent behaviour yields.
     */
    fun onGlobalTouchEvent(event: AccessibilityEventEnvelope) {
        val decision = event.inputOriginDecision ?: unknownInputDecision("全局触摸事件未携带来源分类")
        onLog(
            "全局触摸链路[RUNTIME_RECEIVED]：事件=${event.type}；eventTime=${event.eventTimeMillis}；" +
                "origin=${decision.origin}；phase=${userControlPhase.name}；" +
                "automationYielded=$automationYieldedToUser；manualReviewRequired=${manualReviewIsRequired()}"
        )
        if (!isAiReplyActive() || !chatPagePort.supportsTargetPackage(targetPackage())) {
            onLog("全局触摸链路[RUNTIME_DROPPED]：AI运行未激活或目标包不匹配；事件=${event.type}")
            return
        }

        val observation = recordUnknownInteractionBehaviour(event, decision)
        val protectionTriggered = observation.disposition == UnknownInteractionArbiter.Disposition.HUMAN_CONFIRMED ||
            observation.thresholdReached
        if (!protectionTriggered) {
            if (event.type == AccessibilityEventType.TOUCH_INTERACTION_END &&
                automationYieldedToUser && userControlPhase != UserControlPhase.IDLE
            ) {
                // A target click/scroll may be the event that reaches the third UNKNOWN threshold
                // and moves the phase to SETTLING before Android sends the global touch END. The
                // END still owns the true quiet-period start, regardless of the current phase.
                manualTakeoverCoordinator.quietPeriod.restart()
                manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_SETTLE)
                pendingUserPageInspection = true
                finishUserControl("全局触摸结束；三次UNKNOWN阈值保护周期从实际触摸结束后进入页面静默复核")
                onLog(
                    "全局触摸链路[PROTECTION_SETTLING]：保护周期收到全局触摸结束；" +
                        "静默计时从触摸结束重新起算；进入既有5秒/15秒/60秒静默验证；行为=${observation.behaviourId}"
                )
                return
            }
            onLog(
                "全局触摸链路[UNKNOWN_CONTINUES]：事件=${event.type}；" +
                    "未知独立行为=${observation.count}/${UnknownInteractionArbiter.DEFAULT_THRESHOLD}；" +
                    "行为=${observation.behaviourId}；任务继续；${observation.reason}"
            )
            return
        }

        val reason = "全局触摸事件=${event.type}；origin=${decision.origin}；仲裁=${observation.disposition}；" +
            "独立未知行为=${observation.count}/${UnknownInteractionArbiter.DEFAULT_THRESHOLD}；${observation.reason}"
        when (event.type) {
            AccessibilityEventType.TOUCH_INTERACTION_START -> {
                beginUserControl(reason)
                userControlPhase = UserControlPhase.TOUCHING
                pendingUserPageInspection = true
                onLog("全局触摸链路[PROTECTION_TRIGGERED]：$reason；触摸期间暂停自主派发，等待结束后启动分级静默验证")
            }
            AccessibilityEventType.TOUCH_INTERACTION_END -> {
                if (userControlPhase == UserControlPhase.IDLE && !automationYieldedToUser) {
                    beginUserControl(reason)
                }
                pendingUserPageInspection = true
                finishUserControl(reason)
                onLog("全局触摸链路[PROTECTION_SETTLING]：$reason；进入既有5秒/15秒/60秒静默验证")
            }
            else -> onLog("全局触摸链路[RUNTIME_IGNORED]：不是触摸边界事件；事件=${event.type}")
        }
    }

    /**
     * Receives only click/selection/scroll feedback that survives application-control filtering.
     * Target-app control callbacks are discarded before this method; remaining UNKNOWN evidence
     * is grouped into independent behaviours and yields only at the existing third threshold.
     */
    fun onPreemptiveTargetInteraction(event: AccessibilityEventEnvelope) {
        if (!isAiReplyActive() || !chatPagePort.supportsTargetPackage(targetPackage())) return
        if (event.type !in PREEMPTIVE_INTERACTION_EVENT_TYPES) return

        val decision = event.inputOriginDecision ?: unknownInputDecision("目标交互事件未携带来源分类")
        if (!decision.shouldYieldAutomation) {
            val filterTag = if (decision.origin == InputOrigin.SELF_INJECTED) {
                "SELF_ACTION_FEEDBACK_CONSUMED"
            } else {
                "APPLICATION_CONTROL_FILTERED"
            }
            onLog(
                "未知交互仲裁[$filterTag]：事件=${event.type}；" +
                    "origin=${decision.origin}；actionId=${decision.actionId ?: "NONE"}；" +
                    "已归属自主动作反馈/目标应用控件变化；不计数、不让权"
            )
            return
        }

        val observation = recordUnknownInteractionBehaviour(event, decision)
        if (observation.disposition != UnknownInteractionArbiter.Disposition.HUMAN_CONFIRMED &&
            !observation.thresholdReached
        ) {
            onLog(
                "未知交互仲裁[CONTINUE_AUTONOMOUS]：事件=${event.type}；" +
                    "package=${event.packageName}；Activity=${event.className}；" +
                    "windowId=${event.windowId}；pageGeneration=${event.pageGeneration}；" +
                    "class=${event.source?.className.orEmpty()}；ViewId=${event.source?.viewId.orEmpty()}；" +
                    "bounds=${event.source?.bounds?.let(::formatBounds) ?: "UNKNOWN"}；" +
                    "origin=${decision.origin}；evidence=${decision.evidence}；" +
                    "独立未知行为=${observation.count}/${UnknownInteractionArbiter.DEFAULT_THRESHOLD}；" +
                    "count=${observation.count}/${UnknownInteractionArbiter.DEFAULT_THRESHOLD}；" +
                    "行为=${observation.behaviourId}；${observation.reason}；任务继续"
            )
            return
        }

        val reason = "目标交互事件=${event.type}；origin=${decision.origin}；" +
            "独立未知行为=${observation.count}/${UnknownInteractionArbiter.DEFAULT_THRESHOLD}；${observation.reason}"
        if (userControlPhase == UserControlPhase.TOUCHING) {
            pendingUserPageInspection = true
            onLog("未知交互仲裁[PROTECTION_ALREADY_TOUCHING]：$reason；保留当前保护周期")
            return
        }
        beginUserControl(reason)
        userControlPhase = UserControlPhase.SETTLING
        pendingUserPageInspection = true
        scheduleUserPageStabilityCheck(reason)
        onLog(
            "未知交互仲裁[PROTECTION_TRIGGERED]：${event.type}；独立未知行为=${observation.count}/3；" +
                "暂停自主派发并废弃旧任务；进入既有5秒/15秒/60秒页面复核流程"
        )
    }

    private fun recordUnknownInteractionBehaviour(
        event: AccessibilityEventEnvelope,
        decision: InputOriginDecision
    ): UnknownInteractionArbiter.Observation {
        val source = event.source
        val family = when (event.type) {
            AccessibilityEventType.TOUCH_INTERACTION_START,
            AccessibilityEventType.TOUCH_INTERACTION_END -> UnknownInteractionArbiter.GLOBAL_TOUCH_FAMILY
            AccessibilityEventType.VIEW_SCROLLED -> "SCROLL"
            AccessibilityEventType.VIEW_CLICKED,
            AccessibilityEventType.VIEW_LONG_CLICKED,
            AccessibilityEventType.VIEW_CONTEXT_CLICKED,
            AccessibilityEventType.VIEW_SELECTED -> "TARGET_INTERACTION"
            else -> event.type.name
        }
        val sourceClass = source?.className.orEmpty()
        val sourceViewId = source?.viewId.orEmpty()
        val sourceBounds = source?.bounds?.let(::formatBounds).orEmpty()
        // Prefer the stable resource locator over geometry: a RecyclerView can move or resize
        // during one refresh/layout burst without becoming a new interaction source. Geometry is
        // used only when the source has no ViewId, so anonymous controls remain distinguishable.
        val sourceKey = when {
            sourceViewId.isNotBlank() -> "$sourceClass|$sourceViewId"
            sourceBounds.isNotBlank() -> "$sourceClass|$sourceBounds"
            else -> sourceClass
        }
        val now = uptimeMillis()
        val observation = unknownInteractionArbiter.record(
            origin = decision.origin,
            windowId = event.windowId,
            pageGeneration = event.pageGeneration,
            family = family,
            sourceKey = sourceKey,
            nowMillis = now
        )
        if (decision.shouldYieldAutomation) {
            lastUnknownBehaviourAtUptimeMs = now
            scheduleUnknownCounterReset()
        }
        onLog(
            "未知交互仲裁[BEHAVIOUR]：disposition=${observation.disposition}；" +
                "event=${event.type}；package=${event.packageName.ifBlank { "SYSTEM/NULL" }}；" +
                "Activity=${event.className.ifBlank { "UNKNOWN" }}；windowId=${event.windowId}；" +
                "pageGeneration=${event.pageGeneration}；class=${source?.className.orEmpty()}；" +
                "ViewId=${source?.viewId.orEmpty()}；bounds=${source?.bounds?.let(::formatBounds) ?: "UNKNOWN"}；" +
                "origin=${decision.origin}；evidence=${decision.evidence}；" +
                "行为=${observation.behaviourId}；count=${observation.count}/3；reason=${observation.reason}"
        )
        return observation
    }

    private fun retryUnknownCounterResetOnPageEvent(event: AccessibilityEventEnvelope) {
        if (unknownInteractionArbiter.currentCount == 0 ||
            unknownInteractionArbiter.isThresholdLatched ||
            unknownCounterResetTask != null ||
            event.type !in PAGE_CHANGE_RECHECK_EVENT_TYPES
        ) return
        val lastUnknownAt = lastUnknownBehaviourAtUptimeMs
        if (lastUnknownAt == Long.MIN_VALUE ||
            uptimeMillis() - lastUnknownAt < UnknownInteractionArbiter.DEFAULT_RESET_QUIET_PERIOD_MS
        ) return
        val revision = unknownCounterResetRevision
        unknownCounterResetTask = scheduler.schedule(0L) {
            unknownCounterResetTask = null
            verifyUnknownCounterReset(revision, previousStructureKey = null, stableSamples = 0)
        }
    }

    private fun scheduleUnknownCounterReset() {
        if (unknownInteractionArbiter.isThresholdLatched) return
        unknownCounterResetRevision += 1L
        val revision = unknownCounterResetRevision
        unknownCounterResetTask?.cancel()
        unknownCounterResetTask = scheduler.schedule(UnknownInteractionArbiter.DEFAULT_RESET_QUIET_PERIOD_MS) {
            unknownCounterResetTask = null
            verifyUnknownCounterReset(revision, previousStructureKey = null, stableSamples = 0)
        }
    }

    /** A 10-second quiet interval alone is insufficient: require three fresh matching page snapshots. */
    private fun verifyUnknownCounterReset(
        revision: Long,
        previousStructureKey: String?,
        stableSamples: Int
    ) {
        if (revision != unknownCounterResetRevision || !isAiReplyActive() ||
            unknownInteractionArbiter.isThresholdLatched || unknownInteractionArbiter.currentCount == 0
        ) return
        val now = uptimeMillis()
        if (lastUnknownBehaviourAtUptimeMs == Long.MIN_VALUE ||
            now - lastUnknownBehaviourAtUptimeMs < UnknownInteractionArbiter.DEFAULT_RESET_QUIET_PERIOD_MS
        ) {
            scheduleUnknownCounterReset()
            return
        }
        val fresh = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
        if (fresh == null || fresh.pageKind == ChatPageKind.UNKNOWN ||
            !chatPagePort.supportsTargetPackage(fresh.packageName)
        ) {
            onLog(
                "未知交互仲裁[COUNTER_RESET_DEFERRED]：已连续10秒无新UNKNOWN，但当前页面身份/结构尚不可验证；" +
                    "保留计数=${unknownInteractionArbiter.currentCount}；停止定时全树轮询，等待下一次页面事件再复核"
            )
            return
        }
        val key = fresh.navigationStructureKey() + "|generation=${fresh.windowGeneration}"
        val matches = previousStructureKey == key
        val samples = if (matches) stableSamples + 1 else 1
        if (samples >= REQUIRED_USER_CONTROL_STABLE_SAMPLES) {
            val cleared = unknownInteractionArbiter.clearAfterStableQuiet(now)
            if (cleared) {
                lastUnknownBehaviourAtUptimeMs = Long.MIN_VALUE
                onLog(
                    "未知交互仲裁[COUNTER_RESET]：页面连续${REQUIRED_USER_CONTROL_STABLE_SAMPLES}次结构稳定；" +
                        "连续10秒无新UNKNOWN；旧计数已清零；后续未知行为从1重新计数；当前页面=${fresh.pageKind}"
                )
            }
            return
        }
        unknownCounterResetTask = scheduler.schedule(USER_CONTROL_STABILITY_CONFIRM_DELAY_MS) {
            unknownCounterResetTask = null
            verifyUnknownCounterReset(revision, previousStructureKey = key, stableSamples = samples)
        }
    }

    private fun formatBounds(bounds: ScreenRect): String =
        "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"


    private fun beginUserControl(reason: String) {
        if (userControlPhase == UserControlPhase.TOUCHING) return
        if (startupReadinessCoordinator.initializationPending && !startupReadinessCoordinator.workAreaConfirmed) {
            // A real hand-off owns the screen while startup navigation is pending. The normal
            // Message-tab guard will revalidate the page before startup can resume.
            startupReadinessCoordinator.cancelRecovery()
        }
        manualTakeoverCoordinator.quietPeriod.restart()
        val startsNewCycle = !automationYieldedToUser || userControlPhase == UserControlPhase.IDLE
        if (startsNewCycle) {
            userControlCycle += 1L
            userHandoffCanResumeAutonomous = sessionRunStarted || startupReadinessCoordinator.initializationPending
            userControlNavigationBackAttempts = 0
            userControlChatTabTapAttempts = 0
            userControlNavigationInFlight = false
            messageTabPositionConfirmedForCycle = false
            messageTabNavigationAnchorBeforeTap = null
        }
        userControlInteractionRevision += 1L
        automationYieldedToUser = true
        userControlPhase = UserControlPhase.TOUCHING
        pendingUserPageInspection = true
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_SETTLE)
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_STABILITY)
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_NAVIGATION)
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.MESSAGE_TAB_GUARD)
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.MESSAGE_TAB_PROBE)
        messageTabProbeInFlight = false
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.HANDOFF_MESSAGE_TAB_WATCH)
        handoffMessageTabWatchScheduledAtUptimeMs = null
        handoffMessageTabWatchDueAtUptimeMs = null
        pendingMessageTabGuardReason = ""
        pendingMessageTabGuardRestartsQuiet = false
        pendingMessageTabGuardFallsBackToQuiet = false

        // Revoke future automation work without stopping the archive or event listener.
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        cancelSessionSafeViewportRetry(resetCount = true)
        if (sessionRunStarted) sessionManagerState = SessionManagerState.PAUSED
        sessionActionGeneration += 1L
        val interruptedGestureRequestId = activeSessionGestureRequestId
        activeSessionGestureRequestId = null
        if (interruptedGestureRequestId != null) {
            val interrupted = gesturePolicy.cancelActive(interruptedGestureRequestId, reason)
            onLog(
                "人工接管[ACTIVE_GESTURE_INVALIDATED]：commandId=$interruptedGestureRequestId；" +
                    "逻辑取消=$interrupted；任务代次=$sessionActionGeneration；" +
                    "平台回调负责确认旧手势结束"
            )
        } else {
            onLog("人工接管[ACTIVE_GESTURE_INVALIDATED]：没有活动自主手势；任务代次=$sessionActionGeneration")
        }
        pendingScrollBaseline = null
        pendingScrollToTop = false
        pendingBoundaryConfirmationKeys = null
        pendingBoundaryConfirmationToTop = false
        boundaryStabilityConfirmationCount = 0
        pageInspectionTask?.cancel()
        pageInspectionTask = null
        regionRefreshTask?.cancel()
        regionRefreshTask = null
        pageObservationCoordinator.cancelFullInspection()
        pageObservationCoordinator.cancelRegionRefresh()
        invalidatePendingMessageRead()
        onLog(
            "AI 用户优先权：检测到$reason；已让出界面控制权并撤销待执行自动遍历任务；" +
                "样本归档和无障碍事件观察保持运行；等待用户操作结束"
        )
    }

    private fun finishUserControl(reason: String) {
        if (userControlPhase == UserControlPhase.IDLE && !pendingUserPageInspection) return
        if (userControlPhase == UserControlPhase.TOUCHING || userControlPhase == UserControlPhase.IDLE) {
            pendingUserPageInspection = true
            onLog("AI 用户优先权：$reason；等待页面静止后复核")
        }
        scheduleUserPageStabilityCheck(reason, restartQuietWhenUnconfirmed = true)
    }

    /**
     * Runs the message-tab guard before any manual quiet timer is scheduled. A confirmed message
     * tab is checked by tapping its current locator and comparing a fresh positional snapshot.
     */
    private fun scheduleUserPageStabilityCheck(
        reason: String = "页面状态复核",
        restartQuietWhenUnconfirmed: Boolean = true,
        fallBackToQuietWhenUnconfirmed: Boolean = true
    ) {
        if (!isAiReplyActive() || manualReviewIsRequired()) {
            onLog("AI 用户优先权：存在先前持久化复核状态；本轮不启动自动恢复")
            return
        }
        userControlPhase = UserControlPhase.SETTLING
        requestMessageTabGuard(
            reason = reason,
            restartQuietWhenUnconfirmed = restartQuietWhenUnconfirmed,
            fallBackToQuietWhenUnconfirmed = fallBackToQuietWhenUnconfirmed
        )
    }

    /** The original tiered quiet-period timer, now reached only after the message-tab guard fails. */
    private fun scheduleQuietPeriodStabilityCheck(restartQuietPeriod: Boolean) {
        if (!isAiReplyActive() || manualReviewIsRequired()) return
        if (!restartQuietPeriod && manualTakeoverCoordinator.isScheduled(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_SETTLE)) return
        userControlPhase = UserControlPhase.SETTLING
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_SETTLE)
        val delayMs = manualTakeoverCoordinator.quietPeriod.currentDelayMs
        val stage = manualTakeoverCoordinator.quietPeriod.stageNumber
        manualTakeoverCoordinator.schedule(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_SETTLE, delayMs) {
            if (!isAiReplyActive() || !chatPagePort.supportsTargetPackage(targetPackage())) return@schedule
            userControlPhase = UserControlPhase.VERIFYING_STABILITY
            pendingUserPageInspection = true
            onLog("AI 用户优先权：第${stage}轮静默 ${delayMs / 1_000} 秒结束；开始连续 ${REQUIRED_USER_CONTROL_STABLE_SAMPLES} 次页面快照验证")
            verifyUserPageStability(previous = null, retry = 0, stableSamples = 0)
        }
        scheduleHandoffMessageTabWatch()
    }

    /**
     * Cross-checks the freshly captured inbox viewport against the bottom Message-tab anchor.
     * This is geometry evidence, not a refresh-state or scroll-counter dependency.
     */
    private fun isMessageViewportPositionedAgainstTab(
        snapshot: ChatPageSnapshot,
        tabBounds: ScreenRect,
        displayBounds: ScreenRect
    ): Boolean {
        val viewport = snapshot.chatListViewport ?: return false
        if (viewport.isEmpty || tabBounds.isEmpty || displayBounds.isEmpty) return false
        if (snapshot.sessionListNavigationTarget?.bounds != tabBounds) return false
        if (viewport.left < displayBounds.left || viewport.top < displayBounds.top ||
            viewport.right > displayBounds.right || viewport.bottom > displayBounds.bottom ||
            tabBounds.left < displayBounds.left || tabBounds.top < displayBounds.top ||
            tabBounds.right > displayBounds.right || tabBounds.bottom > displayBounds.bottom
        ) return false
        val tabCenterX = (tabBounds.left + tabBounds.right) / 2
        if (tabCenterX !in viewport.left..viewport.right || viewport.top >= tabBounds.bottom) return false
        val verticalGap = tabBounds.top - viewport.bottom
        // The list viewport should end at or just above the bottom navigation region. A small
        // overlap accommodates OEM layouts where accessibility bounds include separator pixels.
        return verticalGap in (-displayBounds.height * 8 / 100)..(displayBounds.height * 12 / 100)
    }

    /**
     * A NON_CHAT -> CHAT_LIST transition needs a structural delta located in the newly exposed
     * inbox viewport. It must also be consistent with the exact Message-tab tap and stable tab
     * anchor; a distant unrelated layout change is not accepted as navigation evidence.
     */
    private fun hasRelevantMessageStructureDelta(
        before: ChatPageSnapshot,
        after: ChatPageSnapshot,
        tabBounds: ScreenRect,
        displayBounds: ScreenRect
    ): Boolean {
        if (before.pageKind == after.pageKind || after.pageKind != ChatPageKind.CHAT_LIST) return false
        val viewport = after.chatListViewport ?: return false
        val changedPaths = before.changedNavigationNodePaths(after).toSet()
        val changedNodes = after.structureEvidence.filter { it.path in changedPaths && it.boundsValid }
        val hasChangedNodeInInbox = changedNodes.any { node ->
            node.right > viewport.left && node.left < viewport.right &&
                node.bottom > viewport.top && node.top < viewport.bottom
        }
        val hasExplicitListTargetInInbox =
            (after.messageListTarget != null && after.messageListCount > 0) ||
                after.chatListCandidates.isNotEmpty()
        val sameNavigationAnchor = before.messageTabAnchorPositionKey() == after.messageTabAnchorPositionKey()
        val viewportProof = isMessageViewportPositionedAgainstTab(after, tabBounds, displayBounds)
        // The completed tap was compiled from this exact tabBounds by GesturePolicyPipeline;
        // business code intentionally does not receive or inspect the final sampled touch point.
        return sameNavigationAnchor && viewportProof &&
            (hasChangedNodeInInbox || hasExplicitListTargetInInbox)
    }

    /**
     * Continue page verification after an observation-only update without inventing a new quiet
     * period. An already pending quiet timer is left untouched; after it expires, stable sampling
     * resumes on its own bounded task handle.
     */
    private fun continuePageVerificationWithoutRestartingQuiet() {
        if (!isAiReplyActive() || manualReviewIsRequired()) return
        if (manualTakeoverCoordinator.isScheduled(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_SETTLE) || manualTakeoverCoordinator.isScheduled(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_STABILITY)) return
        userControlPhase = UserControlPhase.VERIFYING_STABILITY
        manualTakeoverCoordinator.schedule(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_STABILITY, USER_CONTROL_STABILITY_CONFIRM_DELAY_MS) {
            if (!isAiReplyActive() || !automationYieldedToUser ||
                userControlPhase == UserControlPhase.TOUCHING
            ) return@schedule
            verifyUserPageStability(previous = null, retry = 0, stableSamples = 0)
        }
    }

    /** A known message-list page never enters manual quiet solely because position proof is pending. */
    private fun keepMessagePageAwaitingPositionEvidence(snapshot: ChatPageSnapshot?, reason: String) {
        if (!automationYieldedToUser || !userHandoffCanResumeAutonomous || manualReviewIsRequired()) return
        userControlPhase = UserControlPhase.SETTLING
        pendingUserPageInspection = true
        if (snapshot != null) lastPageInspection = snapshot
        onLog(
            "消息标签保护[MESSAGE_PAGE_AWAITING_POSITION]：$reason；" +
                "快照仍识别为消息页面；不安排人工静默；等待30秒位置巡检或新的页面证据"
        )
        scheduleHandoffMessageTabWatch()
    }

    /**
     * Coalesces navigation/content bursts into one bounded page probe. A page/content update does
     * not restart the quiet period; only a new input signal does that at its call site.
     */
    private fun requestMessageTabGuard(
        reason: String,
        restartQuietWhenUnconfirmed: Boolean,
        fallBackToQuietWhenUnconfirmed: Boolean = true
    ) {
        if (!isAiReplyActive() || manualReviewIsRequired()) return
        if (userControlPhase == UserControlPhase.TOUCHING) {
            pendingUserPageInspection = true
            onLog("消息标签保护[DEFERRED]：仍处于触摸期；不读取或点击导航标签；等待触摸结束")
            return
        }

        pendingMessageTabGuardReason = reason
        pendingMessageTabGuardRestartsQuiet =
            pendingMessageTabGuardRestartsQuiet || restartQuietWhenUnconfirmed
        pendingMessageTabGuardFallsBackToQuiet =
            pendingMessageTabGuardFallsBackToQuiet || fallBackToQuietWhenUnconfirmed
        // A fresh page probe supersedes a queued stability sample, but it must not cancel or
        // restart the independent user-quiet timer. This prevents stale samples racing the
        // Message-tab positional check after content/navigation notifications.
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_STABILITY)
        if (messageTabProbeInFlight || manualTakeoverCoordinator.isScheduled(AiReplyManualTakeoverCoordinator.TaskKey.MESSAGE_TAB_GUARD)) return

        val cycle = userControlCycle
        manualTakeoverCoordinator.schedule(AiReplyManualTakeoverCoordinator.TaskKey.MESSAGE_TAB_GUARD, MESSAGE_TAB_GUARD_DEBOUNCE_MS) {
            if (cycle != userControlCycle || !automationYieldedToUser ||
                userControlPhase == UserControlPhase.TOUCHING
            ) return@schedule
            val guardReason = pendingMessageTabGuardReason.ifBlank { "页面状态复核" }
            val restartQuiet = pendingMessageTabGuardRestartsQuiet
            val fallBackToQuiet = pendingMessageTabGuardFallsBackToQuiet
            pendingMessageTabGuardReason = ""
            pendingMessageTabGuardRestartsQuiet = false
            pendingMessageTabGuardFallsBackToQuiet = false
            runMessageTabGuard(cycle, guardReason, restartQuiet, fallBackToQuiet)
        }
    }

    /**
     * Momo does not expose a reliable selected-state flag on every build. The verified fallback
     * is therefore: tap the visible bottom message tab, reacquire a fresh page snapshot, and
     * accept it only when the tab bounds and inbox viewport bounds remain unchanged.
     */
    private fun runMessageTabGuard(
        cycle: Long,
        reason: String,
        restartQuietWhenUnconfirmed: Boolean,
        fallBackToQuietWhenUnconfirmed: Boolean
    ) {
        if (cycle != userControlCycle || userControlPhase == UserControlPhase.TOUCHING) return

        // Momo's manual path to Received Greetings passes through the ordinary Message tab.
        // Give the page-arrival watcher first refusal before the legacy recovery guard can tap
        // Message or navigate away from an intermediate greetings page.
        if (sessionRunStarted && userHandoffCanResumeAutonomous &&
            receivedGreetingsTaskExecutor.supportsWorkflow()
        ) {
            val arrivalStatus = pageObservationCoordinator.onPageSnapshotAvailable()
            receivedGreetingsPageProbePending = false
            if (receivedGreetingsTaskCompletedAwaitingUserExit) {
                val current = inspectFreshPageSnapshot()
                if (current?.pageKind == ChatPageKind.CHAT_LIST &&
                    current.messageTabPositionKey() != null &&
                    current.messageTabAnchorPositionKey() != null
                ) {
                    if (!claimTaskOwnership(PRIVATE_MESSAGES_TASK_ID, "用户返回消息列表后恢复私人消息任务")) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("私人消息任务恢复暂停[OWNERSHIP_REJECTED]：用户已返回消息列表，但未取得 UI 操作权")
                        return
                    }
                    receivedGreetingsTaskController.acknowledgeCompletedTaskExit()
                    sessionActionGeneration += 1L
                    onLog("收到的招呼人工任务[USER_RETURNED_TO_INBOX]：已确认用户返回消息列表；重新取得 UI 操作权并交还现有私人消息恢复流程")
                } else {
                    holdForReceivedGreetingsPageTransition(arrivalStatus)
                    return
                }
            } else if (arrivalStatus == ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED &&
                receivedGreetingsTaskActive
            ) {
                return
            } else if (arrivalStatus != ReceivedGreetingsPageArrivalStatus.NOT_TARGET) {
                holdForReceivedGreetingsPageTransition(arrivalStatus)
                return
            }
        }

        val before = inspectFreshPageSnapshot()
        val beforePositionKey = before?.messageTabPositionKey()
        val beforeContextPositionKey = before?.messageTabContextPositionKey()
        val beforeAnchorPositionKey = before?.messageTabAnchorPositionKey()
        val tabTarget = before?.sessionListNavigationTarget
        val displayBounds = before?.screenBounds

        // Passive-only observation may inspect the current surface, but must never gain a
        // navigation gesture merely because the recovery guard was added.
        if (!userHandoffCanResumeAutonomous) {
            onLog(
                "消息标签保护[PASSIVE_ONLY]：当前运行仅允许被动归档；" +
                    "不执行导航点击或自主恢复；页面=${before?.let(::classifyUiSnapshot) ?: "UNAVAILABLE"}"
            )
            if (before?.pageKind == ChatPageKind.CHAT_LIST) {
                messageTabProbeInFlight = false
                keepPassiveObservationAfterHandoff(
                    before,
                    "被动模式只读观察到消息页面；不执行消息标签点击或自主恢复"
                )
            } else if (fallBackToQuietWhenUnconfirmed) {
                scheduleQuietPeriodStabilityCheck(restartQuietWhenUnconfirmed)
            }
            return
        }
        if (before == null || beforeAnchorPositionKey == null || tabTarget == null ||
            displayBounds == null || displayBounds.isEmpty
        ) {
            val kind = before?.let(::classifyUiSnapshot).orEmpty()
            onLog(
                "消息标签保护[NOT_CONFIRMED]：原因=$reason；页面=${before?.pageKind?.name ?: "UNAVAILABLE"}；" +
                    "Activity=${before?.activityClass?.substringAfterLast('.') ?: "未知"}；" +
                    "页面类型=$kind；当前没有可验证的消息标签锚点位置证据"
            )
            if (before?.pageKind == ChatPageKind.CHAT_LIST) {
                keepMessagePageAwaitingPositionEvidence(before, "消息标签位置锚点暂不可用")
            } else if (fallBackToQuietWhenUnconfirmed) {
                scheduleQuietPeriodStabilityCheck(restartQuietWhenUnconfirmed)
            } else {
                continuePageVerificationWithoutRestartingQuiet()
            }
            return
        }

        if (userControlChatTabTapAttempts >= MAX_USER_CONTROL_CHAT_TAB_TAPS) {
            onLog("消息标签保护[PROBE_LIMIT]：本轮已执行过消息标签验证点击；保持现有页面验证流程")
            if (before?.pageKind == ChatPageKind.CHAT_LIST) {
                keepMessagePageAwaitingPositionEvidence(before, "消息标签验证点击次数达到本轮上限")
            } else if (fallBackToQuietWhenUnconfirmed) {
                scheduleQuietPeriodStabilityCheck(restartQuietWhenUnconfirmed)
            } else {
                continuePageVerificationWithoutRestartingQuiet()
            }
            return
        }

        if (sessionRunStarted && !ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("消息标签保护[OWNERSHIP_REJECTED]：私人消息任务未持有 UI 操作权；不提交导航点击")
            return
        }
        val tapBounds = tabTarget.bounds
        val executionContext = gesturePolicy.captureContext(tabTarget.nodeTarget.window) ?: run {
            onLog("消息标签保护[CONTEXT_STALE]：生成点击坐标时的窗口或显示坐标上下文已变化；拒绝派发")
            if (before.pageKind == ChatPageKind.CHAT_LIST) {
                keepMessagePageAwaitingPositionEvidence(before, "消息标签点击上下文已失效")
            } else if (fallBackToQuietWhenUnconfirmed) {
                scheduleQuietPeriodStabilityCheck(restartQuietWhenUnconfirmed)
            }
            return
        }
        val request = GestureIntentRequest(
            id = "ai-message-tab-guard-${System.currentTimeMillis()}-${userControlChatTabTapAttempts + 1}",
            intent = GestureIntent.Tap(
                preferredBounds = tapBounds,
                targetBounds = tapBounds,
                durationMs = SESSION_TAP_DURATION_MS,
                displayBounds = displayBounds,
                feedbackIntent = GestureFeedbackIntent.NAVIGATION_TAB
            ),
            description = "AI会话恢复验证：点击底部消息标签并核对页面位置",
            executionContext = executionContext
        )
        messageTabProbeInFlight = true
        activeSessionGestureRequestId = request.id
        val actionGeneration = sessionActionGeneration
        val interactionRevision = userControlInteractionRevision
        if (sessionRunStarted && !ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            messageTabProbeInFlight = false
            activeSessionGestureRequestId = null
            sessionManagerState = SessionManagerState.PAUSED
            onLog("消息标签保护[OWNERSHIP_REJECTED]：派发前任务租约已变化；取消导航点击")
            return
        }
        val submission = runCatching {
            gesturePolicy.execute(request) { result ->
                scheduler.schedule(0L) gestureCallback@{
                    if (activeSessionGestureRequestId == request.id) activeSessionGestureRequestId = null
                    if (actionGeneration != sessionActionGeneration || cycle != userControlCycle) return@gestureCallback
                    if (sessionRunStarted && !ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
                        messageTabProbeInFlight = false
                        onLog("消息标签保护[STALE_CALLBACK_IGNORED]：私人消息任务租约已失效；不继续页面导航验证")
                        return@gestureCallback
                    }
                    if (userControlPhase == UserControlPhase.TOUCHING) {
                        messageTabProbeInFlight = false
                        return@gestureCallback
                    }
                    if (result.status != GestureExecutionStatus.COMPLETED) {
                        messageTabProbeInFlight = false
                        onLog("消息标签保护[PLATFORM_GESTURE_FAILED]：派发已被系统接受，但平台手势终态=${result.status}；尚未进入点击后的页面验证，不认定消息标签有效")
                        if (interactionRevision != userControlInteractionRevision) {
                            requestMessageTabGuard("验证点击期间出现新的输入事件", true)
                        } else if (before.pageKind == ChatPageKind.CHAT_LIST) {
                            keepMessagePageAwaitingPositionEvidence(before, "消息标签验证手势未完成")
                        } else if (fallBackToQuietWhenUnconfirmed) {
                            scheduleQuietPeriodStabilityCheck(restartQuietWhenUnconfirmed)
                        } else {
                            continuePageVerificationWithoutRestartingQuiet()
                        }
                        return@gestureCallback
                    }

                    onLog(
                        "消息标签保护[TAP_COMPLETED]：已点击底部消息标签；" +
                            "点击前锚点指纹=${beforeAnchorPositionKey.hashCode()}；" +
                            "点击前页面位置指纹=${beforeContextPositionKey?.hashCode() ?: "NONE"}；" +
                            "点击前列表位置指纹=${beforePositionKey?.hashCode() ?: "NONE"}；等待新快照复核"
                    )
                    manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.MESSAGE_TAB_PROBE)
                    manualTakeoverCoordinator.schedule(AiReplyManualTakeoverCoordinator.TaskKey.MESSAGE_TAB_PROBE, USER_CONTROL_NAVIGATION_VERIFY_DELAY_MS) postTapVerification@{
                        if (actionGeneration != sessionActionGeneration || cycle != userControlCycle ||
                            !automationYieldedToUser || userControlPhase == UserControlPhase.TOUCHING
                        ) {
                            messageTabProbeInFlight = false
                            return@postTapVerification
                        }
                        if (interactionRevision != userControlInteractionRevision) {
                            messageTabProbeInFlight = false
                            requestMessageTabGuard("消息标签验证期间出现新的输入事件", true)
                            return@postTapVerification
                        }

                        val after = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
                        val afterPositionKey = after?.messageTabPositionKey()
                        val afterContextPositionKey = after?.messageTabContextPositionKey()
                        val afterAnchorPositionKey = after?.messageTabAnchorPositionKey()
                        val sameExistingMessagePagePosition =
                            before.pageKind == ChatPageKind.CHAT_LIST &&
                                beforePositionKey != null &&
                                afterPositionKey == beforePositionKey
                        // A NON_CHAT snapshot is only a valid starting point for a tab-switch
                        // proof when it contains no pre-existing chat-list viewport/evidence.
                        // Otherwise the post-tap CHAT_LIST classification may merely reflect a
                        // reclassification of the same surface, not navigation into the inbox.
                        val verifiedNonChatBeforeTap =
                            before.pageKind == ChatPageKind.NON_CHAT &&
                                before.chatListViewport?.isEmpty != false &&
                                before.messageListCount == 0 &&
                                before.messageListTarget == null &&
                                before.chatListCandidates.isEmpty()
                        val sameNavigationStructure = after != null &&
                            before.navigationStructureKey() == after.navigationStructureKey()
                        val positionCrossValidated = after != null &&
                            isMessageViewportPositionedAgainstTab(after, tapBounds, displayBounds)
                        val positionUnchanged = after != null &&
                            after.pageKind == ChatPageKind.CHAT_LIST &&
                            afterAnchorPositionKey == beforeAnchorPositionKey &&
                            afterPositionKey != null &&
                            beforeContextPositionKey != null &&
                            afterContextPositionKey == beforeContextPositionKey &&
                            positionCrossValidated &&
                            (sameExistingMessagePagePosition ||
                                verifiedNonChatBeforeTap &&
                                hasRelevantMessageStructureDelta(before, after, tapBounds, displayBounds))
                        messageTabProbeInFlight = false
                        if (after != null && positionUnchanged) {
                            lastPageInspection = after
                            messageTabPositionConfirmedForCycle = true
                            messageTabNavigationAnchorBeforeTap = null
                            onLog(
                                "消息标签保护[CONFIRMED]：点击底部消息后页面上下文和位置保持不变；" +
                                    "Activity=${after.activityClass.substringAfterLast('.')}；" +
                                    "消息列表视口与导航标签边界一致；不安排人工静默"
                            )
                            cancelUserControlRecoveryTasks(cancelMessageTabWatch = true)
                            if (userHandoffCanResumeAutonomous) {
                                resumeAutonomousSessionTraversal(after)
                            } else {
                                keepPassiveObservationAfterHandoff(
                                    after,
                                    "消息标签位置验证通过；当前运行仅允许被动样本归档，不启动自主点击或滑动"
                                )
                            }
                        } else if (after != null && sameNavigationStructure) {
                            lastPageInspection = after
                            onLog(
                                "消息标签保护[NO_STRUCTURE_CHANGE]：点击前后页面结构指纹一致；" +
                                    "判定未发生页面结构转换，不记为UNKNOWN，并立即结束该导航点击的滚动反馈匹配；" +
                                    "page=${after.pageKind.name}；structure=${after.navigationStructureKey().hashCode()}"
                            )
                            if (after.pageKind == ChatPageKind.CHAT_LIST && positionCrossValidated) {
                                // Same message page and same stable structure is a valid no-transition
                                // result; do not require a refresh counter or manufacture UNKNOWN.
                                lastPageInspection = after
                                messageTabPositionConfirmedForCycle = true
                                messageTabNavigationAnchorBeforeTap = null
                                onLog("消息标签保护[CONFIRMED_NO_TRANSITION]：消息页结构及列表/导航位置稳定；保持当前消息页面")
                                cancelUserControlRecoveryTasks(cancelMessageTabWatch = true)
                                resumeAutonomousSessionTraversal(after)
                            } else if (fallBackToQuietWhenUnconfirmed) {
                                scheduleQuietPeriodStabilityCheck(restartQuietWhenUnconfirmed)
                            } else {
                                continuePageVerificationWithoutRestartingQuiet()
                            }
                        } else {
                            lastPageInspection = after ?: lastPageInspection
                            onLog(
                                "消息标签保护[PAGE_VERIFICATION_FAILED]：手势已被系统接受并完成，但点击后的页面结构及空间位置交叉验证未通过；" +
                                    "点击前锚点=${beforeAnchorPositionKey.hashCode()}；" +
                                    "点击后锚点=${afterAnchorPositionKey?.hashCode() ?: "NONE"}；" +
                                    "结构变化路径=${after?.let { before.changedNavigationNodePaths(it).take(12) } ?: emptyList<String>()}；" +
                                    "点击前列表=${beforePositionKey?.hashCode() ?: "NONE"}；" +
                                    "点击后列表=${afterPositionKey?.hashCode() ?: "NONE"}；" +
                                    "页面=${after?.pageKind?.name ?: "UNAVAILABLE"}；不认定消息标签有效"
                            )
                            if (after?.pageKind == ChatPageKind.CHAT_LIST) {
                                keepMessagePageAwaitingPositionEvidence(after, "点击后仍在消息页面，但位置证据尚未稳定")
                            } else if (fallBackToQuietWhenUnconfirmed) {
                                scheduleQuietPeriodStabilityCheck(restartQuietWhenUnconfirmed)
                            } else {
                                continuePageVerificationWithoutRestartingQuiet()
                            }
                        }
                    }
                }
            }
        }.getOrDefault(GestureSubmission.REJECTED)

        when (submission) {
            GestureSubmission.DEFERRED_NOT_READY -> {
                if (activeSessionGestureRequestId == request.id) activeSessionGestureRequestId = null
                messageTabProbeInFlight = false
                onLog("消息标签保护[DEFERRED_NOT_READY]：5秒启动保护或页面准入仍在进行；保留验证任务，静默结束后重新采集快照再决定点击")
                scheduleAiReadinessRetry("消息标签保护等待目标页面就绪") {
                    if (cycle == userControlCycle && automationYieldedToUser &&
                        userControlPhase != UserControlPhase.TOUCHING
                    ) runMessageTabGuard(cycle, reason, restartQuietWhenUnconfirmed, fallBackToQuietWhenUnconfirmed)
                }
            }
            GestureSubmission.REJECTED -> {
                if (activeSessionGestureRequestId == request.id) activeSessionGestureRequestId = null
                messageTabProbeInFlight = false
                onLog("消息标签保护[DISPATCH_NOT_ACCEPTED]：系统没有接受验证手势；这是派发拒绝，不是点击后的页面验证失败")
                if (before.pageKind == ChatPageKind.CHAT_LIST) {
                    keepMessagePageAwaitingPositionEvidence(before, "系统未接受消息标签验证手势")
                } else if (fallBackToQuietWhenUnconfirmed) {
                    scheduleQuietPeriodStabilityCheck(restartQuietWhenUnconfirmed)
                } else {
                    continuePageVerificationWithoutRestartingQuiet()
                }
            }
            GestureSubmission.ACCEPTED -> {
                userControlChatTabTapAttempts++
                readinessDeferredRetryCount = 0
                readinessDeferredRetryLimitLogged = false
                onLog("消息标签保护[TAP_SUBMITTED]：已提交底部消息标签验证点击；触摸落点由统一手势策略在安全区域内生成")
            }
        }
    }

    /**
     * A structurally recognized greetings navigation state requires the private task to yield.
     * Event origin is intentionally not consulted; ownership changes only after the adapter's
     * existing page evidence and fresh-snapshot confirmation have been evaluated.
     */
    private fun pauseForReceivedGreetingsPageTransition(status: ReceivedGreetingsPageArrivalStatus) {
        if (!automationYieldedToUser) {
            beginUserControl("收到的招呼页面结构进入过渡/稳定确认阶段")
            userControlPhase = UserControlPhase.IDLE
            pendingUserPageInspection = false
            manualTakeoverCoordinator.quietPeriod.reset()
        }
        holdForReceivedGreetingsPageTransition(status)
    }

    private fun holdForReceivedGreetingsPageTransition(status: ReceivedGreetingsPageArrivalStatus) {
        sessionManagerState = SessionManagerState.PAUSED
        automationYieldedToUser = true
        onLog(
            "收到的招呼页面监听[WAITING]：status=${status.name}；私人消息任务保持暂停；" +
                "不点击消息标签、不执行返回导航；等待用户继续导航或进入已确认的收到招呼列表"
        )
    }

    private fun scheduleHandoffMessageTabWatch() {
        if (manualTakeoverCoordinator.isScheduled(AiReplyManualTakeoverCoordinator.TaskKey.HANDOFF_MESSAGE_TAB_WATCH)) return
        if (!automationYieldedToUser || !userHandoffCanResumeAutonomous || manualReviewIsRequired()) return
        val cycle = userControlCycle
        val scheduledAt = uptimeMillis()
        val dueAt = scheduledAt + USER_HANDOFF_MESSAGE_TAB_WATCH_INTERVAL_MS
        handoffMessageTabWatchScheduledAtUptimeMs = scheduledAt
        handoffMessageTabWatchDueAtUptimeMs = dueAt
        onLog(
            "AI 用户优先权[30S_MESSAGE_TAB_WATCH_SCHEDULED]：" +
                "cycle=$cycle；scheduledAtUptimeMs=$scheduledAt；dueAtUptimeMs=$dueAt；" +
                "delayMs=$USER_HANDOFF_MESSAGE_TAB_WATCH_INTERVAL_MS"
        )
        manualTakeoverCoordinator.schedule(AiReplyManualTakeoverCoordinator.TaskKey.HANDOFF_MESSAGE_TAB_WATCH, USER_HANDOFF_MESSAGE_TAB_WATCH_INTERVAL_MS) {
            val firedAt = uptimeMillis()
            val expectedAt = handoffMessageTabWatchDueAtUptimeMs ?: dueAt
            val actualDelay = (firedAt - scheduledAt).coerceAtLeast(0L)
            val lateBy = (firedAt - expectedAt).coerceAtLeast(0L)
            handoffMessageTabWatchScheduledAtUptimeMs = null
            handoffMessageTabWatchDueAtUptimeMs = null
            onLog(
                "AI 用户优先权[30S_MESSAGE_TAB_WATCH_FIRED]：" +
                    "cycle=$cycle；scheduledAtUptimeMs=$scheduledAt；dueAtUptimeMs=$expectedAt；" +
                    "firedAtUptimeMs=$firedAt；actualDelayMs=$actualDelay；lateByMs=$lateBy"
            )
            if (cycle != userControlCycle || !automationYieldedToUser ||
                !userHandoffCanResumeAutonomous || manualReviewIsRequired()
            ) return@schedule
            if (userControlPhase != UserControlPhase.TOUCHING &&
                !messageTabProbeInFlight && !manualTakeoverCoordinator.isScheduled(AiReplyManualTakeoverCoordinator.TaskKey.MESSAGE_TAB_GUARD)
            ) {
                // One bounded, safe re-tap per 30-second watch interval. The gesture is only
                // against the freshly inspected semantic Message-tab target; no list row is tapped.
                userControlChatTabTapAttempts = 0
                onLog("AI 用户优先权[30S_MESSAGE_TAB_WATCH]：执行新快照检查；必要时重新点击消息标签并比较位置")
                requestMessageTabGuard(
                    reason = "30秒消息标签状态巡检",
                    restartQuietWhenUnconfirmed = false,
                    fallBackToQuietWhenUnconfirmed = false
                )
            } else {
                onLog("AI 用户优先权[30S_MESSAGE_TAB_WATCH]：当前触摸或已有验证任务；本轮仅保持让权，不叠加任务")
            }
            scheduleHandoffMessageTabWatch()
        }
    }

    private fun cancelUserControlRecoveryTasks(cancelMessageTabWatch: Boolean = false) {
        cancelAiReadinessRetry(resetCount = true)
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_SETTLE)
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_STABILITY)
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_NAVIGATION)
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.MESSAGE_TAB_GUARD)
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.MESSAGE_TAB_PROBE)
        messageTabProbeInFlight = false
        pendingMessageTabGuardReason = ""
        pendingMessageTabGuardRestartsQuiet = false
        pendingMessageTabGuardFallsBackToQuiet = false
        if (cancelMessageTabWatch) {
            manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.HANDOFF_MESSAGE_TAB_WATCH)
            handoffMessageTabWatchScheduledAtUptimeMs = null
            handoffMessageTabWatchDueAtUptimeMs = null
        }
    }

    /** Only input-like events restart quiet; page/data/advertisement updates are observations. */
    private fun isManualInputEvent(event: AccessibilityEventEnvelope): Boolean =
        event.type in PREEMPTIVE_INTERACTION_EVENT_TYPES ||
            event.type == AccessibilityEventType.VIEW_SCROLLED ||
            event.type == AccessibilityEventType.TOUCH_INTERACTION_START ||
            event.type == AccessibilityEventType.TOUCH_INTERACTION_END

    private fun classifyUiSnapshot(snapshot: ChatPageSnapshot): String = when {
        snapshot.pageKind == ChatPageKind.CHAT_LIST -> "MESSAGE_PAGE"
        snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE -> "CHAT_DETAIL"
        snapshot.pageKind == ChatPageKind.NON_CHAT -> "NON_CHAT_PAGE"
        else -> "UNKNOWN_PAGE"
    }

    private fun classifyUiChange(event: AccessibilityEventEnvelope): UiChangeKind {
        if (event.type == AccessibilityEventType.WINDOW_STATE_CHANGED ||
            event.type == AccessibilityEventType.WINDOWS_CHANGED ||
            event.contentChanges.any {
                it == com.freedom.core.accessibility.events.AccessibilityContentChange.PANE_APPEARED ||
                    it == com.freedom.core.accessibility.events.AccessibilityContentChange.PANE_DISAPPEARED
            }
        ) return UiChangeKind.PAGE_NAVIGATION_UPDATE

        val viewId = event.source?.viewId.orEmpty().lowercase(Locale.ROOT)
        val className = event.source?.className.orEmpty().lowercase(Locale.ROOT)
        val adTokens = listOf("advert", "sponsor", "native_ad", "ad_view", "_ad", "ad_")
        if (adTokens.any(viewId::contains) || adTokens.any(className::contains)) {
            return UiChangeKind.AD_CONTENT_UPDATE
        }

        val messageTokens = listOf("chatlist_item_", "message", "msg_", "conversation", "chat_content")
        if (messageTokens.any(viewId::contains) ||
            event.signals.contains(AccessibilityUiSignal.MESSAGE_TAB)
        ) return UiChangeKind.MESSAGE_DATA_UPDATE

        return when (event.type) {
            AccessibilityEventType.WINDOW_CONTENT_CHANGED -> UiChangeKind.PAGE_STRUCTURE_UPDATE
            AccessibilityEventType.VIEW_TEXT_CHANGED -> UiChangeKind.MESSAGE_DATA_UPDATE
            else -> UiChangeKind.OTHER_UPDATE
        }
    }

    /**
     * Mirrors AccountBrowse's post-handoff recovery: quiet period, repeated stable page samples,
     * safe navigation to the required root, then a fresh autonomous traversal from a new baseline.
     * Only a confirmed Momo session-list surface re-arms the manager.
     */
    private fun verifyUserPageStability(
        previous: ChatPageSnapshot?,
        retry: Int,
        stableSamples: Int
    ) {
        if (userControlPhase != UserControlPhase.VERIFYING_STABILITY ||
            !automationYieldedToUser || !isAiReplyActive()
        ) return

        val rawFresh = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
        if (rawFresh == null) {
            retryUserPageStability(previous, retry + 1, 0)
            return
        }
        val fresh = stabilizeIncomingPageSnapshot(rawFresh)

        val previousMessagePosition = previous?.messageTabPositionKey()
        val freshMessagePosition = fresh.messageTabPositionKey()
        val sameSnapshot = previous != null && if (
            previousMessagePosition != null && freshMessagePosition != null
        ) {
            // Ignore live row/message changes while the message tab and inbox viewport stay put.
            previousMessagePosition == freshMessagePosition
        } else {
            previous.stableKey() == fresh.stableKey() &&
                previous.packageName == fresh.packageName &&
                previous.hasSameWindowIdentity(fresh)
        }
        val nextStableSamples = if (sameSnapshot) stableSamples + 1 else 1
        if (nextStableSamples < REQUIRED_USER_CONTROL_STABLE_SAMPLES) {
            retryUserPageStability(fresh, retry + 1, nextStableSamples)
            return
        }

        lastPageInspection = fresh
        if (fresh.packageName != targetPackage()) {
            finishUserPageVerification(
                "稳定页面不属于目标应用；保持人工让权",
                fresh
            )
            return
        }

        when (fresh.pageKind) {
            ChatPageKind.CHAT_LIST -> {
                val freshPositionKey = fresh.messageTabPositionKey()
                val navigationAnchor = messageTabNavigationAnchorBeforeTap
                if (!messageTabPositionConfirmedForCycle &&
                    freshPositionKey != null && navigationAnchor != null &&
                    fresh.messageTabAnchorPositionKey() == navigationAnchor
                ) {
                    messageTabPositionConfirmedForCycle = true
                    messageTabNavigationAnchorBeforeTap = null
                    onLog("消息标签保护[CONFIRMED_AFTER_NAVIGATION]：已点击底部消息标签；导航锚点未移动；连续稳定快照确认会话列表视口；允许恢复")
                }
                if (!messageTabPositionConfirmedForCycle) {
                    if (freshPositionKey != null) {
                        requestMessageTabGuard(
                            reason = "三次稳定快照后执行消息标签最终点击验证",
                            restartQuietWhenUnconfirmed = false,
                            fallBackToQuietWhenUnconfirmed = false
                        )
                    } else {
                        keepMessagePageAwaitingPositionEvidence(
                            fresh,
                            "虽然页面识别为CHAT_LIST，但缺少底部消息标签/列表视口的可比位置证据；不恢复自主操控"
                        )
                    }
                    return
                }
                resumeAutonomousSessionTraversal(fresh)
            }
            ChatPageKind.CHAT_DETAIL_CANDIDATE,
            ChatPageKind.NON_CHAT -> {
                // Passive-only sample capture must never gain autonomous navigation privileges.
                handleInspectionSnapshot(fresh)
                if (userHandoffCanResumeAutonomous) {
                    navigateTowardSessionList(fresh)
                } else {
                    keepPassiveObservationAfterHandoff(fresh, "当前运行仅允许被动样本归档；不执行自动返回或标签点击")
                }
            }
            ChatPageKind.UNKNOWN -> {
                if (userHandoffCanResumeAutonomous) {
                    finishUserPageVerification(
                        "连续快照仍无法确认页面身份；保持自主控制暂停，等待下一次页面事件",
                        fresh
                    )
                } else {
                    keepPassiveObservationAfterHandoff(fresh, "被动归档模式无法确认页面身份；不启动自主导航")
                }
            }
        }
    }

    private fun keepPassiveObservationAfterHandoff(snapshot: ChatPageSnapshot, reason: String) {
        cancelSessionSafeViewportRetry(resetCount = true)
        cancelUserControlRecoveryTasks(cancelMessageTabWatch = true)
        unknownCounterResetTask?.cancel()
        unknownCounterResetTask = null
        unknownCounterResetRevision += 1L
        unknownInteractionArbiter.reset()
        lastUnknownBehaviourAtUptimeMs = Long.MIN_VALUE
        manualTakeoverCoordinator.quietPeriod.reset()
        userControlPhase = UserControlPhase.IDLE
        pendingUserPageInspection = false
        userControlNavigationInFlight = false
        automationYieldedToUser = true
        sessionManagerState = SessionManagerState.PAUSED
        onLog(
            "AI 用户优先权：$reason；页面=${snapshot.pageKind.name}；" +
                "保持只读观察；该模式不具备自主恢复条件，不启动分级复核锁"
        )
    }

    private fun resumeAutonomousSessionTraversal(fresh: ChatPageSnapshot) {
        cancelSessionSafeViewportRetry(resetCount = true)
        cancelUserControlRecoveryTasks(cancelMessageTabWatch = true)
        unknownCounterResetTask?.cancel()
        unknownCounterResetTask = null
        unknownCounterResetRevision += 1L
        unknownInteractionArbiter.reset()
        lastUnknownBehaviourAtUptimeMs = Long.MIN_VALUE
        manualTakeoverCoordinator.quietPeriod.reset()
        userControlPhase = UserControlPhase.IDLE
        pendingUserPageInspection = false
        userControlNavigationBackAttempts = 0
        userControlChatTabTapAttempts = 0
        userControlNavigationInFlight = false

        if (!userHandoffCanResumeAutonomous) {
            automationYieldedToUser = false
            sessionManagerState = SessionManagerState.PAUSED
            onLog(
                "AI 用户优先权：消息会话列表已连续稳定确认；当前运行是被动样本归档，" +
                    "恢复页面观察但不启动自主点击或滑动"
            )
            handleInspectionSnapshot(fresh)
            return
        }

        val resumingStartupInitialization = startupReadinessCoordinator.initializationPending
        sessionRunStarted = true
        automationYieldedToUser = false
        userHandoffCanResumeAutonomous = false
        if (resumingStartupInitialization) {
            startupReadinessCoordinator.markWorkAreaConfirmed()
        }

        // A fresh page is now authoritative. Re-run the unread gate before any positioning swipe.
        initialInboxPositionCheckCompleted = false
        initialUnreadGateSwipeCompleted = false
        lockedUnreadSessionKeys.clear()
        lockedUnreadSetFinalized = false
        normalInboxHadUnread = false
        normalInboxUnreadStateUnknown = false
        // Human navigation invalidates the old viewport position. The first fresh snapshot must
        // pass the unread gate before any bottom-positioning swipe can be submitted.
        sessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        sessionListAtTop = false
        noProgressScrollCount = 0
        sessionListStabilityRetryCount = 0
        pendingScrollBaseline = null
        pendingScrollToTop = false
        pendingBoundaryConfirmationKeys = null
        pendingBoundaryConfirmationToTop = false
        boundaryStabilityConfirmationCount = 0
        lastStableSessionListVisibleKeys = null
        cancelledScrollRecoveryCount = 0
        currentSessionKey = null
        currentSessionName = null
        currentSessionCandidate = null
        currentSessionReadCaptured = false
        currentSessionWindowKey = null
        sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM

        if (resumingStartupInitialization) {
            onLog(
                "启动仲裁[INITIALIZATION_RESUMED]：消息会话列表已连续稳定确认；" +
                    "未设置持久化复核锁；重新定位列表底部并启动自主遍历"
            )
        } else {
            onLog(
                "AI 用户优先权：页面连续稳定 ${REQUIRED_USER_CONTROL_STABLE_SAMPLES} 次并确认是消息会话列表；" +
                    "人工操作结束；自动接管已恢复；清除旧列表基准并重新定位底部"
            )
        }
        handleInspectionSnapshot(fresh)
    }

    private fun navigateTowardSessionList(snapshot: ChatPageSnapshot) {
        if (userControlNavigationInFlight) return
        if (sessionRunStarted && !ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话恢复导航[OWNERSHIP_REJECTED]：私人消息任务未持有 UI 操作权；保持当前页面")
            return
        }

        // The social adapter exposes a semantic, freshly verified session-tab target.
        // Provider package names, Activity names and resource IDs never enter this feature.
        val chatTabNavigation = snapshot.sessionListNavigationTarget
        if (chatTabNavigation != null) {
            if (userControlChatTabTapAttempts >= MAX_USER_CONTROL_CHAT_TAB_TAPS) {
                finishUserPageVerification("已达到消息Tab导航次数上限；保持人工让权", snapshot)
                return
            }
            val fresh = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
            val freshTarget = fresh?.sessionListNavigationTarget
            if (fresh == null || fresh.pageKind != ChatPageKind.NON_CHAT ||
                fresh.packageName != targetPackage() || !fresh.hasSameWindowIdentity(snapshot) ||
                freshTarget == null
            ) {
                finishUserPageVerification("消息Tab导航前页面或控件复核失败；拒绝点击", fresh ?: snapshot)
                return
            }
            val tapBounds = freshTarget.bounds
            val displayBounds = fresh.screenBounds
            if (displayBounds == null || displayBounds.isEmpty) {
                finishUserPageVerification("消息Tab点击前缺少有效屏幕边界；拒绝使用控件边界代替屏幕边界", fresh)
                return
            }
            val executionContext = gesturePolicy.captureContext(freshTarget.nodeTarget.window) ?: run {
                finishUserPageVerification("消息Tab点击上下文已变化；拒绝派发旧窗口坐标", fresh)
                return
            }
            val request = GestureIntentRequest(
                id = "ai-session-recovery-tab-${System.currentTimeMillis()}-${userControlChatTabTapAttempts + 1}",
                intent = GestureIntent.Tap(
                    preferredBounds = tapBounds,
                    targetBounds = tapBounds,
                    durationMs = SESSION_TAP_DURATION_MS,
                    displayBounds = displayBounds,
                    feedbackIntent = GestureFeedbackIntent.NAVIGATION_TAB
                ),
                description = "AI会话管理恢复导航：定位并点击消息Tab",
                executionContext = executionContext
            )
            userControlNavigationInFlight = true
            activeSessionGestureRequestId = request.id
            val generation = sessionActionGeneration
            if (sessionRunStarted && !ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
                userControlNavigationInFlight = false
                activeSessionGestureRequestId = null
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话恢复导航[OWNERSHIP_REJECTED]：派发前任务租约已变化；取消消息Tab点击")
                return
            }
            val submission = runCatching {
                gesturePolicy.execute(request) { result ->
                    scheduler.schedule(0L) {
                        if (activeSessionGestureRequestId == request.id) activeSessionGestureRequestId = null
                        userControlNavigationInFlight = false
                        if (generation != sessionActionGeneration || !automationYieldedToUser) return@schedule
                        if (sessionRunStarted && !ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
                            userControlNavigationInFlight = false
                            onLog("AI 会话恢复导航[STALE_CALLBACK_IGNORED]：私人消息任务租约已失效；停止后续导航")
                            return@schedule
                        }
                        if (result.status == GestureExecutionStatus.COMPLETED) {
                            onLog("AI会话管理恢复导航：消息Tab点击完成；等待页面跳转后重新验证会话列表")
                            scheduleNavigationReverification()
                        } else {
                            finishUserPageVerification("消息Tab导航手势未完成（${result.status}）；保持人工让权", fresh)
                        }
                    }
                }
            }.getOrDefault(GestureSubmission.REJECTED)
            when (submission) {
                GestureSubmission.DEFERRED_NOT_READY -> {
                    if (activeSessionGestureRequestId == request.id) activeSessionGestureRequestId = null
                    userControlNavigationInFlight = false
                    onLog("AI会话管理恢复导航[DEFERRED_NOT_READY]：保留消息Tab导航意图；不计入点击次数，也不让权为业务失败")
                    scheduleAiReadinessRetry("消息Tab导航等待静默窗口结束") { latest ->
                        if (generation == sessionActionGeneration && automationYieldedToUser) {
                            navigateTowardSessionList(latest)
                        }
                    }
                }
                GestureSubmission.REJECTED -> {
                    if (activeSessionGestureRequestId == request.id) activeSessionGestureRequestId = null
                    userControlNavigationInFlight = false
                    onLog("AI会话管理恢复导航[DISPATCH_NOT_ACCEPTED]：系统未接受手势；与页面跳转验证失败分开记录")
                    finishUserPageVerification("系统未接受消息Tab导航手势；保持人工让权", fresh)
                }
                GestureSubmission.ACCEPTED -> {
                    userControlChatTabTapAttempts++
                    readinessDeferredRetryCount = 0
                    readinessDeferredRetryLimitLogged = false
                    onLog(
                        "AI会话管理恢复导航：已通过统一手势策略提交消息Tab点击；" +
                            "触摸落点由统一手势策略在安全区域内生成"
                    )
                }
            }
            return
        }

        if (chatPagePort.canSafelyNavigateBackToSessionList(snapshot) &&
            userControlNavigationBackAttempts < MAX_USER_CONTROL_NAVIGATION_BACK_ATTEMPTS &&
            (!sessionRunStarted || ownsTask(PRIVATE_MESSAGES_TASK_ID))
        ) {
            val accepted = runCatching { chatSessionManagementPort.navigateBackToSessionList() }
                .getOrDefault(false)
            if (accepted) {
                userControlNavigationBackAttempts++
                userControlNavigationInFlight = true
                onLog(
                    "AI会话管理恢复导航：当前页面=${snapshot.activityClass.substringAfterLast('.')}；" +
                        "提交安全返回 ${userControlNavigationBackAttempts}/$MAX_USER_CONTROL_NAVIGATION_BACK_ATTEMPTS；等待页面重新识别"
                )
                scheduleNavigationReverification()
            } else {
                finishUserPageVerification("系统未接受安全返回动作；保持人工让权", snapshot)
            }
            return
        }

        val reason = when {
            userControlNavigationBackAttempts >= MAX_USER_CONTROL_NAVIGATION_BACK_ATTEMPTS ->
                "已达到安全返回次数上限"
            else -> "当前页面没有已验证的消息Tab或安全返回路径"
        }
        finishUserPageVerification("$reason；等待用户返回消息会话列表", snapshot)
    }

    private fun scheduleNavigationReverification() {
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_NAVIGATION)
        manualTakeoverCoordinator.schedule(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_NAVIGATION, USER_CONTROL_NAVIGATION_VERIFY_DELAY_MS) {
            userControlNavigationInFlight = false
            if (!isAiReplyActive() || !automationYieldedToUser) return@schedule
            userControlPhase = UserControlPhase.VERIFYING_STABILITY
            verifyUserPageStability(previous = null, retry = 0, stableSamples = 0)
        }
    }

    private fun finishUserPageVerification(reason: String, snapshot: ChatPageSnapshot) {
        userControlNavigationInFlight = false
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_NAVIGATION)
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_STABILITY)
        automationYieldedToUser = true
        pendingUserPageInspection = true
        sessionManagerState = SessionManagerState.PAUSED
        val nextDelayMs = manualTakeoverCoordinator.quietPeriod.advanceAfterUnmetCondition()
        onLog(
            "AI 用户优先权：${reason}；页面=${snapshot.pageKind.name}；" +
                "Activity=${snapshot.activityClass.substringAfterLast('.')}; 本轮未满足自主恢复条件"
        )
        if (nextDelayMs != null) {
            userControlPhase = UserControlPhase.SETTLING
            onLog(
                "AI 用户优先权：第${manualTakeoverCoordinator.quietPeriod.stageNumber - 1}轮复核未通过；" +
                    "开始第${manualTakeoverCoordinator.quietPeriod.stageNumber}轮静默 ${nextDelayMs / 1_000} 秒，结束后重新检查页面、导航能力和稳定性"
            )
            scheduleUserPageStabilityCheck()
        } else {
            latchManualReviewAndTurnScreenOff(
                "第三轮静默 60 秒结束后仍不满足会话列表自主恢复条件；最后页面=${snapshot.pageKind.name}；$reason"
            )
        }
    }

    private fun latchManualReviewAndTurnScreenOff(reason: String) {
        cancelUserControlRecoveryTasks(cancelMessageTabWatch = true)
        userControlPhase = UserControlPhase.IDLE
        pendingUserPageInspection = false
        userControlNavigationInFlight = false
        automationYieldedToUser = true
        manualReviewRequired = true
        sessionManagerState = SessionManagerState.PAUSED
        runCatching { onManualReviewRequired(reason) }
        val screenOffAccepted = runCatching { onRequestScreenOff() }.getOrDefault(false)
        onLog(
            "AI 用户优先权[MANUAL_REVIEW_LATCHED]：$reason；已写入持久化人工复核锁；" +
                "已请求息屏=${screenOffAccepted}；不会自动恢复，必须显式停止并重新启动运行"
        )
    }

    private fun retryUserPageStability(
        previous: ChatPageSnapshot?,
        retry: Int,
        stableSamples: Int
    ) {
        if (retry >= MAX_USER_CONTROL_STABILITY_RETRIES) {
            finishUserPageVerification(
                "页面在 ${MAX_USER_CONTROL_STABILITY_RETRIES + 1} 次采样内未形成连续稳定快照",
                previous ?: lastPageInspection ?: ChatPageSnapshot(
                    packageName = targetPackage(),
                    activityClass = "",
                    pageKind = ChatPageKind.UNKNOWN,
                    scannedNodeCount = 0,
                    editableInputCount = 0,
                    messageListCount = 0,
                    sendActionCount = 0,
                    messageRelatedViewIdCount = 0,
                    hasChatTab = false,
                    messageListTarget = null,
                    inputFieldTarget = null
                )
            )
            return
        }
        manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_STABILITY)
        manualTakeoverCoordinator.schedule(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_STABILITY, USER_CONTROL_STABILITY_CONFIRM_DELAY_MS) {
            verifyUserPageStability(
                previous = previous,
                retry = retry + 1,
                stableSamples = stableSamples
            )
        }
    }

    private fun unknownInputDecision(reason: String): InputOriginDecision = InputOriginDecision(
        origin = InputOrigin.UNKNOWN,
        evidence = InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
        reason = reason
    )

    private fun manualReviewIsRequired(): Boolean =
        manualReviewRequired || isInputManualReviewRequired()

    private fun isManualObservationEnabled(): Boolean =
        sampleCaptureActive && (passiveManualObservation || automationYieldedToUser)

    private fun runRegionRefresh() {
        regionRefreshTask = null
        if (!isAiReplyActive() || !chatPagePort.supportsTargetPackage(targetPackage())) return
        if (userControlPhase != UserControlPhase.IDLE) {
            pendingUserPageInspection = true
            return
        }
        val previous = lastPageInspection
            ?.takeIf { it.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE }
            ?: return
        val refresh = chatPagePort.refreshKnownChatRegions(previous)
        if (!refresh.pathsResolved) {
            onLog("AI 模式聊天区域定位已失效；等待下一次页面结构/导航事件重新识别")
            lastPageInspection = previous.copy(
                messageListTarget = null,
                inputFieldTarget = null
            )
            lastRegionFingerprint = ""
            scheduleFullInspection()
        } else {
            val fingerprint = listOf(
                refresh.messageRegionNodeCount,
                refresh.messageRegionTextNodeCount,
                refresh.inputRegionNodeCount,
                refresh.inputRegionEditableNodeCount
            ).joinToString("|")
            if (fingerprint != lastRegionFingerprint) {
                lastRegionFingerprint = fingerprint
                onLog(
                    "AI 模式已定向检查聊天区域：消息区域节点=${refresh.messageRegionNodeCount}；" +
                        "含文本节点=${refresh.messageRegionTextNodeCount}；" +
                        "输入区域节点=${refresh.inputRegionNodeCount}；" +
                        "可编辑节点=${refresh.inputRegionEditableNodeCount}（不记录消息文本）"
                )
            }
            readVisibleMessages(previous)
        }
    }

    /** Cancel all pending observations when the target changes, run stops, or Service reconnects. */
    fun reset(reason: String = "未指定") {
        sessionActionGeneration += 1L
        startupReadinessCoordinator.cancelRecovery()
        val interruptedGestureRequestId = activeSessionGestureRequestId
        activeSessionGestureRequestId = null
        if (interruptedGestureRequestId != null) {
            gesturePolicy.cancelActive(interruptedGestureRequestId, "runtime_reset:$reason")
        }
        onLog(
            "AI 样本归档：运行时重置；原因=$reason；当前状态=${sessionManagerState.name}；" +
                "样本归档已启动=$sampleCaptureActive；自动会话遍历已启动=$sessionRunStarted"
        )
        pageInspectionTask?.cancel()
        regionRefreshTask?.cancel()
        cancelAiReadinessRetry(resetCount = true)
        cancelSessionSafeViewportRetry(resetCount = true)
        pageObservationCoordinator.reset()
        sessionTransitionTask?.cancel()
        cancelMessageReadRetry()
        pageInspectionTask = null
        regionRefreshTask = null
        sessionTransitionTask = null
        lastPageInspection = null
        lastConfirmedChatListInspection = null
        transientEmptySnapshotRecovery.reset()
        lastRegionFingerprint = ""
        lastMessageFingerprint = ""
        archivedManualMessageFingerprints.clear()
        cancelUserControlRecoveryTasks(cancelMessageTabWatch = true)
        unknownCounterResetTask?.cancel()
        unknownCounterResetTask = null
        unknownCounterResetRevision += 1L
        unknownInteractionArbiter.reset()
        lastUnknownBehaviourAtUptimeMs = Long.MIN_VALUE
        userControlPhase = UserControlPhase.IDLE
        manualTakeoverCoordinator.quietPeriod.reset()
        pendingUserPageInspection = false
        userControlNavigationBackAttempts = 0
        userControlChatTabTapAttempts = 0
        userControlNavigationInFlight = false
        messageTabPositionConfirmedForCycle = false
        messageTabNavigationAnchorBeforeTap = null
        automationYieldedToUser = false
        manualReviewRequired = false
        loggedOcrDebugFingerprints.clear()
        ocrDebugBatchLimitLogged = false
        invalidatePendingMessageRead()
        if (sampleCaptureActive) {
            val archivePath = runCatching {
                chatSessionManagementPort.finishSessionManagement()
            }.getOrNull()
            if (!archivePath.isNullOrBlank()) {
                onLog("AI 会话管理：本次样本包已封存；ZIP=$archivePath")
            } else {
                onLog("AI 会话管理：本次样本包已停止写入；ZIP 封装失败或无可封存样本")
            }
        }
        sampleCaptureActive = false
        passiveManualObservation = false
        sessionRunStarted = false
        startupReadinessCoordinator.resetState(sessionStarted = false)
        userHandoffCanResumeAutonomous = false
        sessionManagerState = SessionManagerState.STOPPED
        taskOwnership.reset("运行时重置：$reason")
        sessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        sessionListAtTop = false
        currentSessionKey = null
        currentSessionName = null
        currentSessionCandidate = null
        currentSessionReadCaptured = false
        currentSessionWindowKey = null
        capturedSessionListFingerprints.clear()
        lastMessageFingerprint = ""
        pendingBoundaryConfirmationKeys = null
        pendingBoundaryConfirmationToTop = false
        boundaryStabilityConfirmationCount = 0
        attemptedSessionKeys.clear()
        ambiguousSessionIdentityKeys.clear()
        temporaryContactMessageLedger.clearAll()
        pendingInterceptionContactKeys.clear()
        sessionActionCount = 0
        privateMessagesTaskController.resetQuota()
        receivedGreetingsTaskController.reset()
        initialInboxPositionCheckCompleted = false
        initialUnreadGateSwipeCompleted = false
        lockedUnreadSessionKeys.clear()
        lockedUnreadSetFinalized = false
        normalInboxHadUnread = false
        normalInboxUnreadStateUnknown = false
        receivedGreetingsPageProbePending = false
        sessionListStabilityRetryCount = 0
        lastUnreadAuditFingerprint = ""
        noProgressScrollCount = 0
        pendingScrollBaseline = null
        pendingScrollToTop = false
        lastStableSessionListVisibleKeys = null
        cancelledScrollRecoveryCount = 0
    }

    /**
     * Route ownership requests through the controller that owns the requested business task.
     * Read-only page observations remain outside the UI-operation lease.
     */
    private fun claimTaskOwnership(taskId: String, reason: String): Boolean = when (taskId) {
        PrivateMessagesTaskController.TASK_ID -> privateMessagesTaskController.claimOwnership(reason)
        ReceivedGreetingsTaskController.TASK_ID -> receivedGreetingsTaskController.claimOwnership(reason)
        else -> false
    }

    private fun ownsTask(taskId: String): Boolean = when (taskId) {
        PrivateMessagesTaskController.TASK_ID -> privateMessagesTaskController.ownsTask()
        ReceivedGreetingsTaskController.TASK_ID -> receivedGreetingsTaskController.ownsTask()
        else -> false
    }

    private fun releaseTaskOwnership(reason: String) {
        when (taskOwnership.activeTaskId()) {
            PrivateMessagesTaskController.TASK_ID -> privateMessagesTaskController.releaseOwnership(reason)
            ReceivedGreetingsTaskController.TASK_ID -> receivedGreetingsTaskController.releaseOwnership(reason)
            else -> taskOwnership.releaseCurrent(reason)
        }
    }

    /**
     * Records only a confirmed successful send, never a message merely observed in history.
     * The send executor must call this after the target app confirms the message was accepted.
     */
    fun recordSuccessfulSend(sendConfirmationId: String): ContactSendCountUpdate {
        val contactKey = currentSessionKey.takeIf {
            sessionManagerState == SessionManagerState.READING_SESSION
        }
        val update = temporaryContactMessageLedger.recordSuccessfulSend(contactKey, sendConfirmationId)
        if (update.thresholdReachedNow) {
            contactKey?.let(pendingInterceptionContactKeys::add)
            onLog(
                "AI 联系人临时账本：当前会话达到发送上限=${update.threshold}；" +
                    "已暂停该联系人后续发送并等待消息拦截开关确认"
            )
        }
        return update
    }

    /**
     * Clears the oldest threshold-reached contact only after an external controller has
     * independently verified that the intended intercept switch is ON. This survives the
     * normal return-to-inbox transition, which clears currentSessionKey.
     */
    fun confirmMessageInterceptionEnabled(): Boolean {
        val contactKey = pendingInterceptionContactKeys.firstOrNull() ?: return false
        val cleared = temporaryContactMessageLedger.confirmInterceptionEnabled(contactKey)
        if (cleared) {
            pendingInterceptionContactKeys.remove(contactKey)
            onLog("AI 联系人临时账本：消息拦截状态已确认开启；该联系人临时数据已清除")
        }
        return cleared
    }

    fun scheduleInitialObservation(passiveOnly: Boolean = false) {
        cancelAiReadinessRetry(resetCount = true)
        cancelSessionSafeViewportRetry(resetCount = true)
        startupReadinessCoordinator.cancelRecovery()
        val directory = runCatching {
            chatSessionManagementPort.beginSessionManagement()
        }.getOrNull()
        sampleCaptureActive = !directory.isNullOrBlank()
        passiveManualObservation = passiveOnly
        // In passive-only mode, archive creation must not implicitly start inbox scrolling or
        // opening. Keep legacy traversal available for explicit/internal callers only.
        sessionRunStarted = sampleCaptureActive && !passiveOnly
        privateMessagesTaskController.beginRun(currentRunGeneration())
        if (sessionRunStarted && !claimTaskOwnership(PRIVATE_MESSAGES_TASK_ID, "启动私人消息任务")) {
            sessionRunStarted = false
            onLog("AI Reply 启动暂停：私人消息任务未取得唯一 UI 操作权；不会执行自动滚动或打开会话")
        }
        startupReadinessCoordinator.resetState(sessionStarted = sessionRunStarted)
        privateMessagesTaskController.resetQuota()
        receivedGreetingsTaskController.reset()
        initialInboxPositionCheckCompleted = false
        initialUnreadGateSwipeCompleted = false
        lockedUnreadSessionKeys.clear()
        lockedUnreadSetFinalized = false
        normalInboxHadUnread = false
        normalInboxUnreadStateUnknown = false
        receivedGreetingsPageProbePending = false
        pageObservationCoordinator.resetReceivedGreetingsWatch()
        sessionActionCount = 0
        userHandoffCanResumeAutonomous = false
        sessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        sessionListAtTop = false
        noProgressScrollCount = 0
        pendingScrollBaseline = null
        pendingScrollToTop = false
        lastStableSessionListVisibleKeys = null
        capturedSessionListFingerprints.clear()
        lastMessageFingerprint = ""
        archivedManualMessageFingerprints.clear()
        cancelUserControlRecoveryTasks(cancelMessageTabWatch = true)
        userControlPhase = UserControlPhase.IDLE
        manualTakeoverCoordinator.quietPeriod.reset()
        pendingUserPageInspection = false
        userControlNavigationBackAttempts = 0
        userControlChatTabTapAttempts = 0
        userControlNavigationInFlight = false
        messageTabPositionConfirmedForCycle = false
        messageTabNavigationAnchorBeforeTap = null
        automationYieldedToUser = false
        pendingBoundaryConfirmationKeys = null
        pendingBoundaryConfirmationToTop = false
        boundaryStabilityConfirmationCount = 0
        sessionManagerState = if (sessionRunStarted) {
            SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
        } else {
            SessionManagerState.PAUSED
        }
        when {
            passiveOnly && sampleCaptureActive -> {
                onLog(
                    "AI 被动观察已启动：样本归档已开启；等待人工操作采集会话列表和聊天详情；" +
                        "自动滚动、自动打开会话、输入和发送均禁用；样本目录=$directory"
                )
                // Passive mode still needs an initial page snapshot so later manual navigation
                // and scroll events can be observed and archived without autonomous gestures.
                scheduleFullInspection()
            }
            sessionRunStarted -> {
                onLog(
                    "AI Reply 测试遍历已启动：普通聊天按底部至顶部扫描；" +
                        "只读进入会话并执行 OCR，不生成回复、不发送消息；样本目录=$directory"
                )
                if (chatPagePort.supportsVerifiedMessageWorkAreaNavigation()) {
                    startupReadinessCoordinator.beginVerification()
                } else {
                    // Compatibility path for adapters that do not expose a stable navigation
                    // anchor. The Momo adapter opts into the stricter, snapshot-verified flow.
                    val entered = runCatching { chatPagePort.ensureMessageWorkArea() }.getOrDefault(false)
                    if (!entered) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("AI Reply 启动暂停：适配器未能通过消息工作区入口；不执行后续滑动或会话点击")
                        return
                    }
                    startupReadinessCoordinator.markWorkAreaConfirmed()
                    scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
                }
            }
            else -> {
                onLog("AI 会话样本归档未启动：本地样本目录创建失败；继续被动页面观察，不执行自主点击")
                scheduleFullInspection()
            }
        }
    }

    /** Schedules bounded page or known-region inspection from a normalized, redacted event. */
    fun schedule(event: AccessibilityEventEnvelope) {
        if (!chatPagePort.supportsTargetPackage(targetPackage())) return
        // Navigation-hit verification is an ephemeral adapter concern and must remain active
        // even when durable sample capture is off. The port implementation only persists events
        // while a collection run is open.
        runCatching { chatSessionManagementPort.recordAccessibilityEvent(event) }
        if (manualReviewIsRequired()) {
            manualReviewRequired = true
            automationYieldedToUser = true
            userControlPhase = UserControlPhase.IDLE
            if (event.type in PAGE_CHANGE_RECHECK_EVENT_TYPES) {
                pendingUserPageInspection = true
                if (pageInspectionTask == null && !pageObservationCoordinator.isFullInspectionPending()) {
                    pageObservationCoordinator.signalFullInspection()
                }
                onLog("输入仲裁[READ_ONLY_OBSERVATION]：来源复核锁定期间只采集当前页面；不运行自主点击/滚动")
            }
            return
        }
        retryUnknownCounterResetOnPageEvent(event)
        val changeKind = classifyUiChange(event)
        val isManualInput = isManualInputEvent(event)
        // Page probing is source-agnostic by design. UNKNOWN, PHYSICAL_USER and SELF_INJECTED
        // events can all indicate that the current page may have changed; none of them grants
        // operation ownership. The debouncer merges bursts before the read-only adapter probe.
        val receivedGreetingsPageChangeSignal = event.type in PAGE_CHANGE_RECHECK_EVENT_TYPES
        if (sessionRunStarted &&
            !receivedGreetingsTaskActive && !receivedGreetingsTaskCompletedAwaitingUserExit &&
            receivedGreetingsTaskExecutor.supportsWorkflow() && receivedGreetingsPageChangeSignal
        ) {
            pageObservationCoordinator.onPageChangeEvent()
            receivedGreetingsPageProbePending = true
        }
        if (userControlPhase == UserControlPhase.TOUCHING) {
            pendingUserPageInspection = true
            return
        }
        if (userControlPhase == UserControlPhase.SETTLING ||
            userControlPhase == UserControlPhase.VERIFYING_STABILITY
        ) {
            val restartQuiet = isManualInput && event.inputOriginDecision?.shouldYieldAutomation != false
            if (restartQuiet) {
                userControlInteractionRevision += 1L
                manualTakeoverCoordinator.quietPeriod.restart()
                manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_SETTLE)
            }
            pendingUserPageInspection = true
            scheduleUserPageStabilityCheck(
                reason = "让权期间事件=${event.type}；更新类型=${changeKind.name}",
                restartQuietWhenUnconfirmed = restartQuiet,
                fallBackToQuietWhenUnconfirmed = restartQuiet
            )
            onLog(
                "AI 用户优先权[HANDOFF_EVENT]：事件=${event.type}；更新类型=${changeKind.name}；" +
                    "人工输入=$restartQuiet；页面/内容/广告更新不重置静默；先检查消息标签"
            )
            return
        }
        // A verified NON_CHAT page does not end the user's hand-off window. Page, message and
        // advertisement events trigger a fresh message-tab check but never restart quiet.
        if (automationYieldedToUser && event.type in PAGE_CHANGE_RECHECK_EVENT_TYPES) {
            val restartQuiet = isManualInput && event.inputOriginDecision?.shouldYieldAutomation != false
            if (restartQuiet) {
                userControlInteractionRevision += 1L
                manualTakeoverCoordinator.quietPeriod.restart()
                manualTakeoverCoordinator.cancel(AiReplyManualTakeoverCoordinator.TaskKey.USER_CONTROL_SETTLE)
            }
            pendingUserPageInspection = true
            scheduleUserPageStabilityCheck(
                reason = "让权页面复核事件=${event.type}；更新类型=${changeKind.name}",
                restartQuietWhenUnconfirmed = restartQuiet,
                fallBackToQuietWhenUnconfirmed = restartQuiet
            )
            onLog(
                "AI 用户优先权[HANDOFF_EVENT]：事件=${event.type}；更新类型=${changeKind.name}；" +
                    "人工输入=$restartQuiet；页面/内容/广告更新不重置静默；先检查消息标签"
            )
            return
        }
        val knownInspection = lastPageInspection
        val knownChatDetail = knownInspection?.let {
            inboxPageRecognizer.isChatDetail(it) && it.messageListTarget != null
        } == true
        // Preserve the 2.1 rule: any click/selection from a known chat list can
        // navigate to a detail page even when the clicked row has no chat-related ViewId/text.
        val chatListNavigation = knownInspection?.pageKind == ChatPageKind.CHAT_LIST &&
            (event.type == AccessibilityEventType.VIEW_CLICKED ||
                event.type == AccessibilityEventType.VIEW_SELECTED)
        val windowTransition = event.type == AccessibilityEventType.WINDOW_STATE_CHANGED ||
            event.type == AccessibilityEventType.WINDOWS_CHANGED
        if (windowTransition) {
            // The click event normally precedes the Activity transition. Once the new window is
            // reported, the short-lived self-TAP correlation window is no longer relevant.
            }
        if (windowTransition || chatListNavigation) {
            // Invalidate before scheduling a debounced inspection. An OCR callback that returns
            // during the debounce window must not be delivered to a different chat/session.
            invalidatePendingMessageRead()
            regionRefreshTask?.cancel()
            regionRefreshTask = null
            pageObservationCoordinator.cancelRegionRefresh()
            lastRegionFingerprint = ""
        }
        val hasChatNavigationEvidence = chatListNavigation || event.signals.any {
            it == AccessibilityUiSignal.CHAT_NAVIGATION || it == AccessibilityUiSignal.MESSAGE_TAB
        }
        val eventKind = AccessibilityEventPolicy.classify(event.type, event.contentChanges)
        val requiresFullInspection = AccessibilityEventPolicy.requiresFullChatPageInspection(
            kind = eventKind,
            eventType = event.type,
            isKnownChatDetail = knownChatDetail,
            hasChatNavigationEvidence = hasChatNavigationEvidence
        )
        // In passive mode the user may manually enter SayHi list/card Activities. Those pages are
        // intentionally NON_CHAT to the ordinary chat classifier, so inspect their exact page
        // identity after human navigation/content/scroll events. Debouncing bounds the read-only
        // scans; no gesture or click is issued by this branch.
        val passiveNonChatObservation = isManualObservationEnabled() &&
            !knownChatDetail && event.type in setOf(
                AccessibilityEventType.WINDOW_STATE_CHANGED,
                AccessibilityEventType.WINDOWS_CHANGED,
                AccessibilityEventType.WINDOW_CONTENT_CHANGED,
                AccessibilityEventType.VIEW_CLICKED,
                AccessibilityEventType.VIEW_SELECTED,
                AccessibilityEventType.VIEW_SCROLLED,
                AccessibilityEventType.VIEW_TEXT_CHANGED
            )

        if (requiresFullInspection || passiveNonChatObservation || receivedGreetingsPageProbePending) {
            regionRefreshTask?.cancel()
            regionRefreshTask = null
            pageObservationCoordinator.cancelRegionRefresh()
            pageInspectionTask?.cancel()
            pageInspectionTask = null
            pageObservationCoordinator.signalFullInspection()
            return
        }

        if (knownChatDetail && AccessibilityEventPolicy.requiresKnownChatRegionRefresh(event.type)) {
            cancelMessageReadRetry()
            regionRefreshTask?.cancel()
            pageObservationCoordinator.cancelFullInspection()
            pageObservationCoordinator.signalRegionRefresh()
        }
    }

    /**
     * Reads only the currently visible bounded message set. Screenshot/OCR-capable adapters may
     * complete asynchronously; message bodies remain ephemeral and are never logged here.
     */
    private fun readVisibleMessages(snapshot: ChatPageSnapshot) {
        if (!isAiReplyActive() || !chatPagePort.supportsTargetPackage(targetPackage())) return
        if (sessionRunStarted) {
            val requiredTask = if (receivedGreetingsTaskActive) {
                RECEIVED_GREETINGS_TASK_ID
            } else {
                PRIVATE_MESSAGES_TASK_ID
            }
            if (!ownsTask(requiredTask)) {
                onLog("消息详情读取被阻止：当前任务未持有 UI 操作权；task=$requiredTask")
                return
            }
        }
        if (messageReadInFlight) {
            pendingMessageReadSnapshot = snapshot
            return
        }
        messageReadInFlight = true
        val requestGeneration = messageReadGeneration
        messageReadTimeoutTask?.cancel()

        // The OCR adapter may invoke its callback on a worker thread. Arbitrate callback and
        // watchdog completion atomically, and keep a local handle so the callback can cancel the
        // watchdog as soon as the result arrives (before its main-thread delivery is queued).
        // Otherwise the 65s watchdog remains ahead of the queued zero-delay delivery and can
        // invalidate a perfectly valid result while the scheduler is waiting to process it.
        val callbackGate = Any()
        var callbackReceived = false
        var requestClosedWithoutCallback = false
        val timeoutTaskRef = AtomicReference<ScheduledTask?>()
        val timeoutTask = scheduler.schedule(MESSAGE_READ_CALLBACK_TIMEOUT_MS) {
            val shouldHandleTimeout = synchronized(callbackGate) {
                if (callbackReceived || requestClosedWithoutCallback) {
                    false
                } else {
                    requestClosedWithoutCallback = true
                    true
                }
            }
            if (!shouldHandleTimeout || requestGeneration != messageReadGeneration || !messageReadInFlight) {
                return@schedule
            }
            messageReadTimeoutTask = null
            // Invalidate the callback before freeing the in-flight gate. A late OCR result must
            // not complete this read or clear the timeout for a newer request.
            messageReadGeneration += 1L
            messageReadInFlight = false
            pendingMessageReadSnapshot = null
            onLog("AI 模式消息读取超时：异步适配器未在 ${MESSAGE_READ_CALLBACK_TIMEOUT_MS}ms 内返回；已丢弃该读取代次并安排新鲜页面复核")
            scheduleMessageListRetry()
        }
        messageReadTimeoutTask = timeoutTask
        timeoutTaskRef.set(timeoutTask)

        val callThread = Thread.currentThread()
        val callReturned = AtomicBoolean(false)
        try {
            chatMessagePort.readRecentMessagesAsync(snapshot, limit = MAX_VISIBLE_MESSAGES) { batch ->
                val shouldDeliver = synchronized(callbackGate) {
                    if (callbackReceived) {
                        false
                    } else {
                        callbackReceived = true
                        // Cancel on callback receipt, not when the queued delivery eventually runs.
                        // This local handle may be cancelled from the adapter callback thread.
                        if (!requestClosedWithoutCallback) timeoutTaskRef.get()?.cancel()
                        true
                    }
                }
                if (shouldDeliver) {
                    val deliver = {
                        completeMessageRead(
                            snapshot = snapshot,
                            batch = batch,
                            requestGeneration = requestGeneration,
                            revalidateActivePage = callReturned.get() || Thread.currentThread() != callThread
                        )
                    }
                    if (!callReturned.get() && Thread.currentThread() == callThread) {
                        // Keep legacy synchronous adapters synchronous; only screenshot/OCR callbacks
                        // cross the scheduler boundary and require a fresh page validation.
                        deliver()
                    } else {
                        scheduler.schedule(0L, deliver)
                    }
                }
            }
        } catch (_: Exception) {
            // An adapter exception closes this attempt only when no callback has already won.
            val shouldHandleException = synchronized(callbackGate) {
                if (callbackReceived || requestClosedWithoutCallback) {
                    false
                } else {
                    requestClosedWithoutCallback = true
                    true
                }
            }
            if (shouldHandleException) {
                if (requestGeneration == messageReadGeneration) messageReadGeneration += 1L
                timeoutTaskRef.get()?.cancel()
                messageReadTimeoutTask = null
                messageReadInFlight = false
                onLog("AI 模式消息读取失败：读取适配器异常；未保留消息正文")
                cancelMessageReadRetry()
            }
        } finally {
            callReturned.set(true)
        }
    }

    private fun completeMessageRead(
        snapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        requestGeneration: Long,
        revalidateActivePage: Boolean
    ) {
        if (requestGeneration != messageReadGeneration) return
        messageReadTimeoutTask?.cancel()
        messageReadTimeoutTask = null
        messageReadInFlight = false
        val readOnlyGreetingRequest = sessionRunStarted && receivedGreetingsTaskActive &&
            receivedGreetingsTaskExecutor.traversalStatus() == ReceivedGreetingsTraversalStatus.DETAIL_READY
        if (isAiReplyActive() && chatPagePort.supportsTargetPackage(targetPackage())) {
            val current = if (revalidateActivePage) {
                runCatching { chatPagePort.inspectChatPage() }.getOrNull()
            } else {
                snapshot
            }
            val expectedWindowSessionKey = windowSessionKey(snapshot)
            val readOnlyGreetingDetail = sessionRunStarted && receivedGreetingsTaskActive &&
                receivedGreetingsTaskExecutor.traversalStatus() == ReceivedGreetingsTraversalStatus.DETAIL_READY
            val stillSameChat = current?.let { active ->
                if (readOnlyGreetingDetail) {
                    active.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
                        active.packageName == snapshot.packageName &&
                        active.hasSameWindowIdentity(snapshot) &&
                        active.windowGeneration == snapshot.windowGeneration &&
                        (expectedWindowSessionKey == null ||
                            batch.windowSessionKey == expectedWindowSessionKey)
                } else {
                    active.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
                        active.packageName == snapshot.packageName &&
                        active.hasSameWindowIdentity(snapshot) &&
                        hasSameWindowSession(snapshot, active) &&
                        expectedWindowSessionKey != null &&
                        (currentSessionWindowKey == null || currentSessionWindowKey == expectedWindowSessionKey) &&
                        batch.windowSessionKey == expectedWindowSessionKey
                }
            } == true
            if (stillSameChat) {
                lastPageInspection = current
                handleMessageReadBatch(batch, snapshot)
            }
        }
        val pending = pendingMessageReadSnapshot
        pendingMessageReadSnapshot = null
        if (!readOnlyGreetingRequest && pending != null && pending.stableKey() != snapshot.stableKey()) {
            readVisibleMessages(pending)
        }
    }

    private fun handleMessageReadBatch(batch: ChatMessageBatch, snapshot: ChatPageSnapshot) {
        if (sessionRunStarted) {
            val requiredTask = if (receivedGreetingsTaskActive) {
                RECEIVED_GREETINGS_TASK_ID
            } else {
                PRIVATE_MESSAGES_TASK_ID
            }
            if (!ownsTask(requiredTask)) {
                onLog("消息读取回调[STALE_TASK_IGNORED]：任务租约已失效；不保存样本、不执行后续页面操作；task=$requiredTask")
                return
            }
        }
        // Received-greeting details are an OCR calibration task only. Do not expose their
        // contents to the future AI observer callback and do not enter any reply/send state.
        if (sessionRunStarted && receivedGreetingsTaskActive &&
            receivedGreetingsTaskExecutor.traversalStatus() == ReceivedGreetingsTraversalStatus.DETAIL_READY
        ) {
            logTemporaryOcrResults(batch)
            val sampleIndex = runCatching {
                chatSessionManagementPort.persistChatSample(
                    snapshot,
                    batch,
                    sessionName = receivedGreetingsTaskExecutor.currentDisplayName(),
                    contactIdentity = receivedGreetingsTaskExecutor.currentContactIdentity()
                )
            }.getOrDefault(0)
            onLog(
                "收到招呼只读 OCR 采集：样本=${if (sampleIndex > 0) sampleIndex else "未保存"}；" +
                    "读取状态=${batch.status.name}；消息行=${batch.messages.size}；" +
                    "OCR目标=${batch.ocrTargets.size}；已识别=${batch.ocrRecognizedTargetCount}；" +
                    "失败=${batch.ocrFailedTargetCount}；截断=${batch.truncated}；" +
                    "唯一身份=${if (receivedGreetingsTaskExecutor.currentContactIdentity() != null) "已验证" else "未验证"}；" +
                    "未调用 AI 消息回调，不输入、不发送"
            )
            val returned = runCatching {
                receivedGreetingsTaskExecutor.completeDetailInspection(sampleIndex > 0)
            }.getOrDefault(false)
            if (returned) {
                scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
            } else {
                scheduleReceivedGreetingsRecoveryRetry(
                    "详情读取完成但本轮未能通过资料页/聊天页安全返回检查；保留详情页和用户断点，不重复执行 BACK"
                )
            }
            return
        }
        // This method is reached only after the asynchronous callback has passed the active
        // package/activity/window-generation check. Never log OCR output before that point.
        logTemporaryOcrResults(batch)
        // Observers are a future AI-input boundary: expose only verified text with a known
        // direction, ordered bottom-to-top. Own outgoing text remains available as AI context;
        // reply schedulers must use ChatMessageBatch.incomingMessagesBottomToTop. Unverified OCR
        // remains in this runtime's diagnostics, never in the callback.
        val observerBatch = batch.copy(
            messages = batch.aiInputMessagesBottomToTop,
            ocrDebugTextByRowIndex = null,
            ocrDebugChineseTextByRowIndex = null,
            ocrDebugLatinTextByRowIndex = null,
            sampleScreenshotPath = null,
            sampleCropPathsByRowIndex = emptyMap()
        )
        runCatching { onMessagesObserved(observerBatch) }.onFailure {
            onLog("AI 模式消息观察回调异常；异常诊断不包含消息正文")
        }
        if (sessionRunStarted && currentSessionKey != null &&
            sessionManagerState == SessionManagerState.READING_SESSION && !currentSessionReadCaptured
        ) {
            val sampleIndex = runCatching {
                chatSessionManagementPort.persistChatSample(
                    snapshot, batch, currentSessionName, currentSessionCandidate?.contactIdentity
                )
            }.getOrDefault(0)
            currentSessionReadCaptured = sampleIndex > 0
            if (sampleIndex > 0) {
                onLog("AI 会话管理：聊天样本已保存；样本序号=$sampleIndex；消息=${batch.messages.size}；OCR区域=${batch.ocrTargets.size}；方向不明=${batch.messages.count { it.direction == com.freedom.features.aireply.domain.message.ChatMessageDirection.UNKNOWN }}")
            } else {
                onLog("AI 会话管理：聊天样本保存失败或当前存储会话不可用；仍保持只读模式")
            }
            scheduleReturnToSessionList(snapshot)
        }
        val fingerprint = listOf(
            snapshot.stableKey(),
            batch.status.name,
            batch.windowSessionKey.orEmpty(),
            batch.messages.joinToString(",") { it.id },
            batch.messageStructureEvidence.joinToString(","),
            batch.messageListUnavailableReason.orEmpty(),
            batch.ocrAttempted,
            batch.ocrRecognizedTargetCount,
            batch.ocrFailureReason.orEmpty()
        ).joinToString("|")
        if (isManualObservationEnabled() && sampleCaptureActive &&
            fingerprint !in archivedManualMessageFingerprints
        ) {
            // A manually opened chat has no independently verified inbox-row identity. Archive
            // the batch without assigning a contact name or promoting UNKNOWN direction. Retry
            // failed persistence on the next event, but do not duplicate an identical sample.
            val sampleIndex = runCatching {
                chatSessionManagementPort.persistChatSample(snapshot, batch, sessionName = null)
            }.getOrDefault(0)
            if (sampleIndex > 0) {
                archivedManualMessageFingerprints += fingerprint
                while (archivedManualMessageFingerprints.size > MAX_ARCHIVED_MANUAL_MESSAGE_FINGERPRINTS) {
                    archivedManualMessageFingerprints.remove(archivedManualMessageFingerprints.first())
                }
            }
            onLog(
                if (sampleIndex > 0) {
                    "AI 被动观察：人工打开的聊天详情已归档；样本序号=$sampleIndex；" +
                        "可见消息=${batch.messages.size}；AI 可用消息=${observerBatch.messages.size}；" +
                        "方向不明=${batch.messages.count { it.direction == com.freedom.features.aireply.domain.message.ChatMessageDirection.UNKNOWN }}；" +
                        "联系人身份未绑定；不执行输入或发送"
                } else {
                    "AI 被动观察：人工聊天详情读取完成，但样本归档失败；未绑定联系人身份；不执行输入或发送"
                }
            )
        }
        if (fingerprint != lastMessageFingerprint) {
            lastMessageFingerprint = fingerprint
            val bodyLogStatus = if (batch.ocrDebugTextByRowIndex != null ||
                batch.ocrDebugChineseTextByRowIndex != null || batch.ocrDebugLatinTextByRowIndex != null
            ) {
                "调试版 OCR 结果已按临时人工校验策略单独记录"
            } else {
                "消息正文未写入日志"
            }
            onLog(
                "AI 模式消息读取：${batch.summary()}；" +
                    "最新对方文字消息=${if (batch.latestIncomingTextMessage != null) "已识别" else "无/不确定"}；" +
                    bodyLogStatus
            )
            batch.messageStructureEvidence.take(8).forEach { evidence ->
                onLog("AI 模式消息行结构证据：$evidence")
            }
        }
        if (batch.status == com.freedom.features.aireply.domain.message.MessageReadStatus.MESSAGE_LIST_UNAVAILABLE) {
            scheduleMessageListRetry()
        } else {
            cancelMessageReadRetry()
        }
    }

    /**
     * Temporary, local-only human validation output. The adapter supplies a non-null payload
     * only in the explicitly enabled debug build. Every OCR target is logged, including empty
     * results, and repeated batches are deduplicated with a bounded per-window fingerprint set.
     */
    private fun logTemporaryOcrResults(batch: ChatMessageBatch) {
        val debugRows = batch.ocrDebugTextByRowIndex ?: return
        if (!batch.ocrAttempted || batch.ocrTargets.isEmpty()) return

        val chineseRows = batch.ocrDebugChineseTextByRowIndex.orEmpty()
        val latinRows = batch.ocrDebugLatinTextByRowIndex.orEmpty()
        val targets = batch.ocrTargets.sortedBy { it.rowIndex }
        val fingerprint = createOcrDebugFingerprint(
            windowSessionKey = batch.windowSessionKey,
            targets = targets,
            finalRows = debugRows,
            chineseRows = chineseRows,
            latinRows = latinRows,
            failureReason = batch.ocrFailureReason
        )
        if (fingerprint in loggedOcrDebugFingerprints) return
        if (loggedOcrDebugFingerprints.size >= MAX_OCR_DEBUG_BATCHES_PER_WINDOW) {
            if (!ocrDebugBatchLimitLogged) {
                ocrDebugBatchLimitLogged = true
                onLog(
                    "AI 模式 OCR 临时日志已达到单会话 ${MAX_OCR_DEBUG_BATCHES_PER_WINDOW} 组上限；" +
                        "为保护本地日志空间，后续相同会话结果不再记录，切换会话后重新计数"
                )
            }
            return
        }
        loggedOcrDebugFingerprints += fingerprint

        targets.forEach { target ->
            val rawText = debugRows[target.rowIndex]?.takeIf(String::isNotBlank)
            val chineseText = chineseRows[target.rowIndex]?.takeIf(String::isNotBlank)
            val latinText = latinRows[target.rowIndex]?.takeIf(String::isNotBlank)
            val parsedMessage = batch.messages.firstOrNull { it.bounds == target.rowBounds }
            val direction = when (parsedMessage?.direction) {
                com.freedom.features.aireply.domain.message.ChatMessageDirection.INCOMING -> "对方"
                com.freedom.features.aireply.domain.message.ChatMessageDirection.OUTGOING -> "自己"
                com.freedom.features.aireply.domain.message.ChatMessageDirection.UNKNOWN, null -> "不明/未形成消息对象"
            }
            val evidence = parsedMessage?.directionEvidence?.name ?: "无"
            val parseState = if (parsedMessage == null) "未合并" else "已合并"
            val resultState = if (rawText == null) "空结果" else "已识别"
            val displayText = rawText?.let(::escapeOcrDebugText) ?: "[未识别到文字]"
            val chineseDisplay = chineseText?.let(::escapeOcrDebugText) ?: "[中文识别无结果]"
            val latinDisplay = latinText?.let(::escapeOcrDebugText) ?: "[Latin识别无结果]"
            val quality = parsedMessage?.contentQuality?.name ?: "未形成消息"
            onLog(
                "AI 模式 OCR 识别结果[临时人工校验/仅本地]：row=${target.rowIndex}；" +
                    "状态=$resultState；解析=$parseState；内容可信度=$quality；方向=$direction；方向证据=$evidence；" +
                    "正文区域=${target.bodyBounds}；气泡区域=${target.bubbleBounds}；" +
                    "最终文本=\"$displayText\"；中文候选=\"$chineseDisplay\"；Latin候选=\"$latinDisplay\""
            )
        }
    }

    private fun createOcrDebugFingerprint(
        windowSessionKey: String?,
        targets: List<com.freedom.features.aireply.domain.message.ChatMessageOcrTarget>,
        finalRows: Map<Int, String>,
        chineseRows: Map<Int, String>,
        latinRows: Map<Int, String>,
        failureReason: String?
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(windowSessionKey.orEmpty().toByteArray(StandardCharsets.UTF_8))
        digest.update(failureReason.orEmpty().toByteArray(StandardCharsets.UTF_8))
        targets.forEach { target ->
            digest.update(target.rowIndex.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(finalRows[target.rowIndex].orEmpty().toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(chineseRows[target.rowIndex].orEmpty().toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(latinRows[target.rowIndex].orEmpty().toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun escapeOcrDebugText(value: String): String {
        val escaped = buildString(value.length) {
            value.forEach { character ->
                append(
                    when (character) {
                        '\\' -> "\\\\"
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        '\"' -> "\\\""
                        else -> character.toString()
                    }
                )
            }
        }
        return if (escaped.length <= MAX_OCR_DEBUG_TEXT_CHARS) escaped
        else escaped.take(MAX_OCR_DEBUG_TEXT_CHARS) + "…[已截断]"
    }

    private fun handleSessionListPage(snapshot: ChatPageSnapshot) {
        if (pendingBoundaryConfirmationKeys != null) {
            // The delayed boundary verifier owns this interval. Ignore ordinary page events so
            // an early accessibility callback cannot turn a provisional no-motion result into
            // a completed traversal.
            return
        }
        if (!sessionRunStarted || sessionManagerState in setOf(
                SessionManagerState.CAPTURING_SESSION_LIST,
                SessionManagerState.OPENING_SESSION,
                SessionManagerState.READING_SESSION,
                SessionManagerState.SCROLLING_SESSION_LIST,
                SessionManagerState.BROWSING_RECEIVED_GREETINGS,
                SessionManagerState.COMPLETED,
                SessionManagerState.PAUSED
            )
        ) return
        if (!receivedGreetingsTaskActive && !ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            onLog("私人消息列表处理被阻止：当前任务未持有 UI 操作权")
            return
        }

        // The first list snapshot is a read-only current-position check. It must happen before
        // the positioning phase is allowed to submit any swipe gesture.
        if (!initialInboxPositionCheckCompleted && !receivedGreetingsTaskActive) {
            initialInboxPositionCheckCompleted = true
            // This snapshot only establishes the current Message work area. The required
            // first upward swipe must complete before unread/no-unread is decided.
            normalInboxHadUnread = false
            normalInboxUnreadStateUnknown = false
            onLog(
                "AI Reply 启动检查：已确认消息工作区域；当前视口行=${snapshot.chatListCandidates.size}；" +
                    "先执行一次向上滑动，手势成功后再判定未读，不使用滑动前快照作结论"
            )
        }
        if (!receivedGreetingsTaskActive) {
            val visibleUnread = snapshot.chatListCandidates.any {
                it.unreadStatus == ChatUnreadStatus.UNREAD && ChatSessionManagementPolicy.isEligible(it)
            }
            val visibleUnknown = snapshot.chatListCandidates.any {
                it.unreadStatus == ChatUnreadStatus.UNKNOWN && ChatSessionManagementPolicy.isEligible(it)
            }
            if (sessionListTraversalPhase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM) {
                // Before the first bottom boundary, each newly exposed viewport contributes to
                // the locked set. After finalization, repositioning must not admit new messages.
                if (!lockedUnreadSetFinalized) {
                    normalInboxHadUnread = visibleUnread
                    normalInboxUnreadStateUnknown = visibleUnknown
                } else {
                    if (visibleUnknown) normalInboxUnreadStateUnknown = true
                }
            } else {
                // Once traversal starts, retain whether any visited viewport had unknown status.
                if (visibleUnread) normalInboxHadUnread = true
                if (visibleUnknown) normalInboxUnreadStateUnknown = true
            }
        }

        if (sessionManagerState == SessionManagerState.RETURNING_TO_SESSION_LIST) {
            sessionTransitionTask?.cancel()
            sessionTransitionTask = null
            val observationCompleted = currentSessionReadCaptured
            onLog(
                if (observationCompleted) "AI Reply 测试遍历：已验证返回普通消息列表；本次会话只读观察完成"
                else "AI Reply 测试遍历：已返回普通消息列表，但聊天内容读取未完成；不计入已完成观察数"
            )
            currentSessionKey = null
            currentSessionName = null
            currentSessionCandidate = null
            currentSessionReadCaptured = false
            currentSessionWindowKey = null
            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
            if (observationCompleted) {
                val completedObservations = privateMessagesTaskController.recordSuccessfulObservation()
                onLog("AI Reply 模拟回复计数：已成功读取并保存的不同私人聊天=$completedObservations/$normalInboxTaskTargetCount；仅模拟计数，未发送消息")
                if (privateMessagesTaskController.quotaReached()) {
                    onLog("AI Reply 私人消息任务达到本轮随机目标；暂停私聊扫描，切换收到的招呼任务")
                    startReceivedGreetingsTask("私人消息成功归档计数达到随机目标 $normalInboxTaskTargetCount")
                    return
                }
            }
        }

        val snapshotAmbiguousKeys = ChatSessionManagementPolicy.ambiguousSessionIdentityKeys(
            snapshot.chatListCandidates
        )
        val newlyAmbiguousKeys = snapshotAmbiguousKeys - ambiguousSessionIdentityKeys
        if (snapshotAmbiguousKeys.isNotEmpty()) {
            ambiguousSessionIdentityKeys += snapshotAmbiguousKeys
        }
        if (newlyAmbiguousKeys.isNotEmpty()) {
            newlyAmbiguousKeys.forEach(chatSessionManagementPort::blockSessionIdentityKey)
            val affectedRows = snapshot.chatListCandidates.count {
                ChatSessionManagementPolicy.stableSessionKey(it) in newlyAmbiguousKeys &&
                    ChatSessionManagementPolicy.isEligible(it)
            }
            onLog(
                "AI 会话管理：发现规范化昵称身份冲突；冲突键数=${newlyAmbiguousKeys.size}；" +
                    "关联可见行数=$affectedRows；本次运行将跳过这些身份，不执行点击或消息归并；" +
                    "当前列表节点未提供可验证联系人 ID"
            )
        }

        // Diagnostics are audit metadata, not page structure. Keep them out of stableKey()
        // so changing counters cannot trigger a new list sample or invalidate pending actions.
        val unreadAuditFingerprint =
            "${sessionManagerState.name}:${snapshot.stableKey()}:" +
                "${snapshot.chatListUnreadDiagnostics}:${snapshotAmbiguousKeys.sorted().joinToString(",")}"
        if (unreadAuditFingerprint != lastUnreadAuditFingerprint) {
            lastUnreadAuditFingerprint = unreadAuditFingerprint
            onLog(
                "AI 会话管理：未读证据审计；" + formatUnreadAudit(snapshot, snapshotAmbiguousKeys) +
                    "；复查状态=${sessionManagerState.name}"
            )
        }

        // Track every geometrically valid visible row for scroll-position comparison, including
        // blocked identities. This is observation-only: blocked keys remain excluded by
        // selectNext() and attemptOpenSession(), and can never become tap candidates here.
        val wasWaitingForUnread = sessionManagerState == SessionManagerState.WAITING_FOR_UNREAD
        val hadPendingScrollBaseline = pendingScrollBaseline != null
        val visibleKeys = visibleSessionKeys(snapshot)
        pendingScrollBaseline?.let { baseline ->
            val positionChanged = visibleKeys != baseline
            val wasScrollingToTop = pendingScrollToTop
            pendingScrollBaseline = null
            pendingScrollToTop = false
            if (wasScrollingToTop) {
                if (positionChanged) {
                    // The swipe exposed a new viewport. Inspect this exact snapshot before
                    // issuing another top-directed swipe, otherwise intermediate unread rows
                    // would be skipped while the runtime rapidly scrolls to the top.
                    noProgressScrollCount = 0
                    sessionListAtTop = false
                    onLog("AI 会话管理：顶部方向滑动后可见会话行发生变化；先审视当前新区域")
                } else {
                    // Require two consecutive unchanged fresh snapshots at the top boundary. A
                    // completed gesture alone, or one temporarily blocked swipe, is not proof.
                    noProgressScrollCount += 1
                    if (noProgressScrollCount < MAX_NO_PROGRESS_SCROLLS) {
                        sessionListAtTop = false
                        onLog("AI 会话管理：顶部定位后新快照未见位置变化；稳定确认计数=$noProgressScrollCount")
                        scrollSessionList(snapshot, toTop = true)
                        return
                    }
                    beginBoundaryStabilityConfirmation(visibleKeys, toTop = true, snapshot = snapshot)
                    return
                }
            } else if (!positionChanged) {
                noProgressScrollCount += 1
                onLog("AI 会话管理：滚动后可见行顺序与身份集合未变化；底部定位无进展计数=$noProgressScrollCount")
                if (sessionListTraversalPhase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM &&
                    noProgressScrollCount >= MAX_NO_PROGRESS_SCROLLS
                ) {
                    beginBoundaryStabilityConfirmation(visibleKeys, toTop = false, snapshot = snapshot)
                    return
                }
            } else {
                noProgressScrollCount = 0
                sessionListAtTop = false
            }
        }

        // A viewport identity change without one of our pending scroll gestures means the
        // current scan position is no longer trustworthy (for example, a user swipe during
        // a detail-page visit). Re-establish the bottom before continuing; do not tap rows
        // from a position whose place in the traversal is unknown. Parked events remain
        // current-page probes and deliberately do not trigger a full reposition.
        if (!hadPendingScrollBaseline && !wasWaitingForUnread && visibleKeys.isNotEmpty() &&
            sessionListTraversalPhase == SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        ) {
            val previousVisibleKeys = lastStableSessionListVisibleKeys
            if (previousVisibleKeys != null && previousVisibleKeys != visibleKeys) {
                sessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
                sessionListAtTop = false
                noProgressScrollCount = 0
                sessionListStabilityRetryCount = 0
                // Repositioning is based on this fresh viewport, not stale unread evidence from
                // the page visited before opening a session. Any currently visible UNREAD row
                // is still handled first by the admission gate below; UNKNOWN still blocks the swipe.
                normalInboxHadUnread = snapshot.chatListCandidates.any {
                    it.unreadStatus == ChatUnreadStatus.UNREAD && ChatSessionManagementPolicy.isEligible(it)
                }
                normalInboxUnreadStateUnknown = snapshot.chatListCandidates.any {
                    it.unreadStatus == ChatUnreadStatus.UNKNOWN && ChatSessionManagementPolicy.isEligible(it)
                }
                sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                onLog(
                    "AI 会话管理：未发出滚动手势时可见会话身份序列发生变化；当前列表位置不可信；" +
                        "以最新视口重新核对未读状态；保留本轮已处理身份，重新定位底部后继续审视"
                )
            }
        }
        if (visibleKeys.isNotEmpty()) lastStableSessionListVisibleKeys = visibleKeys

        if (snapshot.chatListCandidates.isEmpty()) {
            if (sessionManagerState == SessionManagerState.WAITING_FOR_UNREAD) {
                // An incomplete accessibility tree during idle is not evidence that the list
                // is empty or that a new traversal should begin. Keep the existing low-frequency
                // timer and avoid both scrolling and cancelling the parked state.
                onLog(
                    "AI 会话管理：待机期间收到页面变化，但当前无可用会话行；" +
                        "保持待机，不执行滑动，等待后续页面事件或低频全列表复查"
                )
                return
            }
            scheduleSessionListStabilityRetry("当前列表暂未提取到会话行")
            return
        }
        sessionListStabilityRetryCount = 0

        if (sessionListTraversalPhase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM &&
            !wasWaitingForUnread
        ) {
            if (!initialUnreadGateSwipeCompleted) {
                // First successful upward swipe is a mandatory admission probe.
                sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                onLog("AI Reply 启动准入：执行进入消息工作区后的首次向上滑动；完成后才检查未读")
                scrollSessionList(snapshot, toTop = false)
                return
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
            when {
                normalInboxUnreadStateUnknown -> {
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("AI Reply 启动准入[UNREAD_STATE_UNKNOWN]：首次成功滑动后的视口存在 UNKNOWN；暂停，不把未知当作无未读")
                    return
                }
                lockedUnreadSessionKeys.isEmpty() -> {
                    onLog("AI Reply 启动准入[NO_UNREAD_AFTER_FIRST_SWIPE]：首次成功向上滑动后的视口没有明确未读；不再滑动到底部，直接切换收到的招呼任务")
                    startReceivedGreetingsTask("进入消息工作区后首次成功向上滑动，确认当前视口没有明确未读私人消息")
                    return
                }
                else -> {
                    sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                    onLog("AI Reply 启动准入[UNREAD_LOCKING]：已锁定明确未读身份=${lockedUnreadSessionKeys.size}；继续向上滑动收集其余未读行，定位列表底部")
                    scrollSessionList(snapshot, toTop = false)
                    return
                }
            }
        }

        if (wasWaitingForUnread && snapshot.chatListCandidates.any {
                it.unreadStatus == ChatUnreadStatus.UNREAD && ChatSessionManagementPolicy.isEligible(it)
            }
        ) {
            attemptedSessionKeys.clear()
        }
        val eligibleForThisTask = if (lockedUnreadSessionKeys.isNotEmpty()) {
            snapshot.chatListCandidates.filter {
                ChatSessionManagementPolicy.stableSessionKey(it) in lockedUnreadSessionKeys
            }
        } else snapshot.chatListCandidates
        val next = ChatSessionManagementPolicy.selectNext(
            eligibleForThisTask,
            attemptedSessionKeys,
            ambiguousSessionIdentityKeys
        )

        // ChatPageSnapshot.stableKey() captures page/window/viewport structure and redacted
        // per-row fingerprints. For list archiving, also include the resolved visible-session
        // identities and current row geometry so a swipe to a new set of rows in the same viewport
        // bounds is archived as a distinct sample.
        val structuralFingerprint = buildString {
            append(snapshot.stableKey())
            append("|visibleRows=")
            append(visibleKeys.joinToString(","))
        }
        if (next == null) {
            // While parked, page-change events are wake-up probes only. A visible page with no
            // explicit UNREAD candidate must not restart scrolling; the low-frequency timer owns
            // the next full-list traversal.
            if (wasWaitingForUnread) {
                onLog(
                    "AI 会话管理：待机期间收到列表变化并完成当前页复查；" +
                        "未发现明确未读候选，保持顶部待机，不执行滑动"
                )
                return
            }
            // Archive every distinct viewport even when all visible rows are blocked, read,
            // unknown, or otherwise ineligible. Sampling is observation-only and never grants
            // permission to click a row.
            if (capturedSessionListFingerprints.add(structuralFingerprint)) {
                sessionManagerState = SessionManagerState.CAPTURING_SESSION_LIST
                chatSessionManagementPort.captureSessionListSample(snapshot) { screenshotPath ->
                    scheduler.schedule(0L) {
                        if (!isAiReplyActive() || !sessionRunStarted ||
                            sessionManagerState != SessionManagerState.CAPTURING_SESSION_LIST
                        ) return@schedule
                        onLog(
                            "AI 会话管理：列表视口样本已累计；识别行=${snapshot.chatListCandidates.size}；" +
                                "私人聊天=${snapshot.chatListCandidates.count { it.selectionClass == "PRIVATE_CHAT" }}；" +
                                "系统入口=${snapshot.chatListCandidates.count { it.selectionClass == "SYSTEM_ENTRY" }}；" +
                                "未分类=${snapshot.chatListCandidates.count { it.selectionClass == "UNCLASSIFIED" }}；" +
                                "未读=${snapshot.chatListCandidates.count { it.unreadStatus.name == "UNREAD" }}；" +
                                "未知=${snapshot.chatListCandidates.count { it.unreadStatus.name == "UNKNOWN" }}；" +
                                "身份冲突键数=${snapshotAmbiguousKeys.size}；" +
                                "累计不同视口=${capturedSessionListFingerprints.size}；" +
                                "截图=${if (screenshotPath.isNullOrBlank()) "不可用" else "已保存"}"
                        )
                        continueSessionListWithoutCandidate(snapshot)
                    }
                }
            } else {
                continueSessionListWithoutCandidate(snapshot)
            }
            return
        }

        if (wasWaitingForUnread) {
            sessionTransitionTask?.cancel()
            sessionTransitionTask = null
                sessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
            sessionListAtTop = false
            noProgressScrollCount = 0
            pendingScrollBaseline = null
            pendingScrollToTop = false
            lastStableSessionListVisibleKeys = null
            sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
            onLog("AI Reply 低频待机结束：当前页发现明确未读；先确认当前位置，再重新定位普通消息列表底部并由下往上处理")
            scrollSessionList(snapshot, toTop = false)
            return
        } else {
            sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
        }

        if (capturedSessionListFingerprints.add(structuralFingerprint)) {
            sessionManagerState = SessionManagerState.CAPTURING_SESSION_LIST
            chatSessionManagementPort.captureSessionListSample(snapshot) { screenshotPath ->
                scheduler.schedule(0L) {
                    if (!isAiReplyActive() || !sessionRunStarted ||
                        sessionManagerState != SessionManagerState.CAPTURING_SESSION_LIST
                    ) return@schedule
                    onLog(
                        "AI 会话管理：列表视口样本已累计；识别行=${snapshot.chatListCandidates.size}；" +
                            "私人聊天=${snapshot.chatListCandidates.count { it.selectionClass == "PRIVATE_CHAT" }}；" +
                            "系统入口=${snapshot.chatListCandidates.count { it.selectionClass == "SYSTEM_ENTRY" }}；" +
                            "未分类=${snapshot.chatListCandidates.count { it.selectionClass == "UNCLASSIFIED" }}；" +
                            "未读=${snapshot.chatListCandidates.count { it.unreadStatus.name == "UNREAD" }}；" +
                            "未知=${snapshot.chatListCandidates.count { it.unreadStatus.name == "UNKNOWN" }}；" +
                            "身份冲突键数=${snapshotAmbiguousKeys.size}；" +
                            "累计不同视口=${capturedSessionListFingerprints.size}；" +
                            "截图=${if (screenshotPath.isNullOrBlank()) "不可用" else "已保存"}"
                    )
                    attemptOpenSession(next)
                }
            }
        } else {
            attemptOpenSession(next)
        }
    }

    private fun visibleSessionKeys(snapshot: ChatPageSnapshot): List<String> =
        ChatSessionManagementPolicy.orderBottomToTop(
            snapshot.chatListCandidates.filter {
                ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)
            }
        ).mapNotNull { candidate ->
            val identityKey = ChatSessionManagementPolicy.stableSessionKey(candidate)
            if (identityKey.isBlank()) return@mapNotNull null
            // Stable identity plus physical row geometry measures real viewport movement.
            // Row indexes are deliberately omitted because inserting/removing dynamic cards above
            // the inbox changes indexes without changing the visible conversation set.
            "$identityKey@${candidate.rowBounds.left},${candidate.rowBounds.top}," +
                "${candidate.rowBounds.right},${candidate.rowBounds.bottom}"
        }

    private fun beginBoundaryStabilityConfirmation(
        visibleKeys: List<String>,
        toTop: Boolean,
        snapshot: ChatPageSnapshot
    ) {
        if (visibleKeys.isEmpty() || snapshot.chatListExtractionTruncated || snapshot.windowGeneration <= 0L) {
            onLog("AI 会话管理：边界候选缺少完整可见行证据或稳定窗口身份；不确认边界，重新获取稳定快照")
            scheduleSessionListStabilityRetry("边界候选快照不完整或缺少稳定窗口身份")
            return
        }
        if (snapshot.nodeTreeProbeTruncated) {
            onLog("AI 会话管理：辅助节点树探针已截断；主会话列表提取器仍完整，使用已确认主列表进行有限边界复核")
        }
        pendingBoundaryConfirmationKeys = visibleKeys
        pendingBoundaryConfirmationToTop = toTop
        boundaryStabilityConfirmationCount = 0
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
        onLog(
            if (toTop) {
                "AI 会话管理：顶部连续无位移仅作为候选边界；等待延迟新快照复核"
            } else {
                "AI 会话管理：底部连续无位移仅作为候选边界；等待延迟新快照复核"
            }
        )
        val expectedWindowIdentity = snapshot.windowIdentityKey()
        if (expectedWindowIdentity.isNullOrBlank()) {
            pendingBoundaryConfirmationKeys = null
            boundaryStabilityConfirmationCount = 0
            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
            onLog("AI 会话管理：边界候选缺少有效 windowId/generation；不确认边界，重新获取当前页面快照")
            scheduleSessionListStabilityRetry("边界候选缺少当前窗口身份")
            return
        }
        scheduleBoundaryStabilityConfirmation(expectedWindowIdentity)
    }

    private fun scheduleBoundaryStabilityConfirmation(expectedWindowIdentity: String) {
        sessionTransitionTask?.cancel()
        sessionTransitionTask = scheduler.schedule(SESSION_BOUNDARY_SETTLE_DELAY_MS) {
            sessionTransitionTask = null
            if (!isAiReplyActive() || !sessionRunStarted || pendingBoundaryConfirmationKeys == null) {
                return@schedule
            }
            val fresh = inspectFreshPageSnapshot()
            if (fresh != null && fresh.pageKind == ChatPageKind.UNKNOWN &&
                fresh.packageName == targetPackage() &&
                fresh.windowIdentityKey() == expectedWindowIdentity
            ) {
                pendingBoundaryConfirmationKeys = null
                boundaryStabilityConfirmationCount = 0
                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                onLog("AI 会话管理：延迟边界复核的页面树不完整；不判为非聊天页面，重新采集主列表与导航锚点")
                scheduleSessionListStabilityRetry("延迟边界复核页面树不完整")
                return@schedule
            }
            if (fresh == null || fresh.pageKind != ChatPageKind.CHAT_LIST ||
                fresh.packageName != targetPackage() ||
                fresh.windowIdentityKey() != expectedWindowIdentity
            ) {
                pendingBoundaryConfirmationKeys = null
                boundaryStabilityConfirmationCount = 0
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话管理已暂停：延迟边界复核未能确认同一会话列表页面；不判定遍历完成")
                return@schedule
            }
            val currentKeys = visibleSessionKeys(fresh)
            if (currentKeys.isEmpty() || fresh.chatListExtractionTruncated) {
                pendingBoundaryConfirmationKeys = null
                boundaryStabilityConfirmationCount = 0
                onLog("AI 会话管理：延迟边界复核快照不完整；不确认边界，转入列表稳定性重试")
                scheduleSessionListStabilityRetry("延迟边界复核快照不完整")
                return@schedule
            }
            if (fresh.nodeTreeProbeTruncated) {
                onLog("AI 会话管理：延迟复核的辅助节点树探针仍不完整；主列表提取和页面锚点一致，继续有限稳定确认")
            }
            val expectedKeys = pendingBoundaryConfirmationKeys
            if (currentKeys != expectedKeys) {
                val wasTopCheck = pendingBoundaryConfirmationToTop
                pendingBoundaryConfirmationKeys = null
                boundaryStabilityConfirmationCount = 0
                noProgressScrollCount = 0
                sessionListAtTop = false
                sessionManagerState = if (wasTopCheck) {
                    SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                } else {
                    SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                }
                onLog("AI 会话管理：延迟边界复核发现列表发生迟到变化；撤销边界候选，使用新快照继续定位")
                handleSessionListPage(fresh)
                return@schedule
            }
            boundaryStabilityConfirmationCount++
            if (boundaryStabilityConfirmationCount < MAX_BOUNDARY_STABILITY_CONFIRMATIONS) {
                onLog(
                    "AI 会话管理：边界候选延迟快照一致；继续独立稳定复核=" +
                        "$boundaryStabilityConfirmationCount/$MAX_BOUNDARY_STABILITY_CONFIRMATIONS"
                )
                scheduleBoundaryStabilityConfirmation(expectedWindowIdentity)
                return@schedule
            }

            val confirmedTop = pendingBoundaryConfirmationToTop
            pendingBoundaryConfirmationKeys = null
            boundaryStabilityConfirmationCount = 0
            noProgressScrollCount = 0
            if (confirmedTop) {
                sessionListAtTop = true
                sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                onLog("AI 会话管理：顶部边界经延迟独立快照复核通过；检查顶部当前可见会话行")
            } else {
                sessionListTraversalPhase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
                sessionListAtTop = false
                lockedUnreadSetFinalized = true
                sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                onLog("AI 会话管理：底部边界经延迟独立快照复核通过；未读身份集合已冻结；进入从底部向顶部的处理阶段")
            }
            handleSessionListPage(fresh)
        }
    }

    private fun continueSessionListWithoutCandidate(snapshot: ChatPageSnapshot) {
        if (sessionListTraversalPhase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM) {
            if (normalInboxUnreadStateUnknown) {
                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                onLog("AI Reply 启动准入[POSITIONING_BLOCKED_BY_UNKNOWN]：未读状态无法确认；不执行底部定位滑动，等待新页面证据")
            } else {
                scrollSessionList(snapshot, toTop = false)
            }
            return
        }
        if (sessionListAtTop) {
            noProgressScrollCount = 0
            sessionListStabilityRetryCount = 0
            when {
                normalInboxUnreadStateUnknown -> {
                    sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                    onLog("AI Reply 测试遍历暂停：完整扫描中存在未读状态 UNKNOWN 的会话行，不能将未知当作无未读")
                }
                else -> startReceivedGreetingsTask(
                    if (normalInboxHadUnread) "普通聊天列表已完成由下至上扫描，当前没有剩余符合条件的未读会话"
                    else "普通聊天列表已完成筛选，没有符合条件的新消息"
                )
            }
            return
        }
        scrollSessionList(snapshot, toTop = true)
    }

    /**
     * Test mode waits only after both list branches have been checked. A single randomized
     * 60–120 second timer performs a fresh, read-only current-page check.
     */
    private fun enterUnreadWait(message: String) {
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        sessionManagerState = SessionManagerState.WAITING_FOR_UNREAD
        onLog(message)
        val seconds = chooseRandomInt(LOW_FREQUENCY_IDLE_MIN_SECONDS, LOW_FREQUENCY_IDLE_MAX_SECONDS)
            .coerceIn(LOW_FREQUENCY_IDLE_MIN_SECONDS, LOW_FREQUENCY_IDLE_MAX_SECONDS)
        onLog("AI Reply 低频待机：$seconds 秒后重新检查普通消息当前位置")
        sessionTransitionTask = scheduler.schedule(seconds * 1_000L) {
            sessionTransitionTask = null
            if (!isAiReplyActive() || !sessionRunStarted ||
                sessionManagerState != SessionManagerState.WAITING_FOR_UNREAD
            ) return@schedule
            val fresh = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
            if (fresh == null || fresh.pageKind != ChatPageKind.CHAT_LIST || fresh.packageName != targetPackage()) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI Reply 低频待机复查暂停：未能确认普通消息列表页面；不把未知页面当作无未读，也不执行点击或滑动")
                return@schedule
            }
            val unread = fresh.chatListCandidates.count {
                it.unreadStatus == ChatUnreadStatus.UNREAD && ChatSessionManagementPolicy.isEligible(it)
            }
            val unknown = fresh.chatListCandidates.count {
                it.unreadStatus == ChatUnreadStatus.UNKNOWN && ChatSessionManagementPolicy.isEligible(it)
            }
            onLog("AI Reply 低频待机复查：当前位置明确未读=$unread；未读状态未知=$unknown；页面行数=${fresh.chatListCandidates.size}")
            if (unknown > 0) {
                normalInboxUnreadStateUnknown = true
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI Reply 低频待机复查暂停：当前列表存在 UNKNOWN 未读状态，不能按无消息处理")
            } else if (unread > 0) {
                normalInboxHadUnread = true
                attemptedSessionKeys.clear()
                noProgressScrollCount = 0
                pendingScrollBaseline = null
                pendingScrollToTop = false
                lastStableSessionListVisibleKeys = null
                sessionListStabilityRetryCount = 0
                sessionListTraversalPhase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
                sessionListAtTop = false
                sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                handleSessionListPage(fresh)
            } else {
                enterUnreadWait("待机复查后当前位置仍无明确未读；继续 60–120 秒静默待机")
            }
        }
    }

    /**
     * Hand off from the private-message task either after its randomized archive quota is met,
     * after the first successful upward swipe confirms no unread rows, or after a full unread scan.
     * The adapter owns Momo-specific navigation and the bounded read-only traversal.
     */
    private fun startReceivedGreetingsTask(reason: String) {
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive) return
        val noUnreadFastPath = initialUnreadGateSwipeCompleted && lockedUnreadSessionKeys.isEmpty() &&
            !normalInboxHadUnread && !normalInboxUnreadStateUnknown
        val quotaReached = privateMessagesTaskController.quotaReached()
        if ((!noUnreadFastPath && !quotaReached &&
                (sessionListTraversalPhase != SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP || !sessionListAtTop)) ||
            normalInboxUnreadStateUnknown
        ) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog(
                "AI Reply 收到的招呼支线暂停：普通会话列表尚未完成底部至顶部的完整复核，" +
                    "或仍存在 UNKNOWN 未读；不提前切换到收到招呼入口"
            )
            return
        }
        if (!claimTaskOwnership(RECEIVED_GREETINGS_TASK_ID, "切换收到的招呼任务")) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("收到的招呼任务切换暂停：无法原子取得 UI 操作权；不执行入口导航")
            return
        }
        sessionActionGeneration += 1L
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        val ordinarySessionClickCount = sessionActionCount
        runCatching { receivedGreetingsTaskExecutor.beginNavigationTask() }
        receivedGreetingsTaskController.markNavigationStarted()
        currentSessionKey = null
        currentSessionWindowKey = null
        currentSessionName = null
        currentSessionCandidate = null
        messageReadInFlight = false
        messageReadTimeoutTask?.cancel()
        messageReadTimeoutTask = null
        pendingMessageReadSnapshot = null
        messageReadGeneration += 1L
        sessionActionCount = 0
        sessionManagerState = SessionManagerState.BROWSING_RECEIVED_GREETINGS
        onLog(
            "AI Reply：切换收到的招呼只读 OCR 任务；原因=$reason；" +
                "私人聊天成功归档数=$normalCompletedObservationCount/$normalInboxTaskTargetCount；" +
                "普通聊天会话点击次数=$ordinarySessionClickCount；头像身份与详情 OCR 不触发回复"
        )
        if (!receivedGreetingsTaskExecutor.supportsWorkflow()) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI Reply 收到的招呼支线暂停：当前社交适配器未声明支持该入口/页面识别能力")
            return
        }
        val accepted = runCatching { receivedGreetingsTaskExecutor.performIdleStep() }.getOrDefault(false)
        if (!accepted) {
            scheduleReceivedGreetingsRecoveryRetry(
                "首次入口导航步骤暂未完成；保留任务和导航状态，不尝试备用坐标"
            )
            return
        }
        scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
    }

    /** Starts the greetings task from a list the current page structure and adapter have confirmed. */
    private fun startReceivedGreetingsTaskFromConfirmedPage() {
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive) return
        if (receivedGreetingsTaskCompletedAwaitingUserExit) {
            onLog("收到的招呼页面监听：当前列表本轮已经处理完成；保持页面不动，等待用户离开后再次进入")
            return
        }
        if (userControlPhase == UserControlPhase.TOUCHING) return
        if (!claimTaskOwnership(RECEIVED_GREETINGS_TASK_ID, "接管人工打开的收到招呼列表")) {
            onLog("收到的招呼人工任务启动[OWNERSHIP_REJECTED]：未取得唯一 UI 操作权；不接管当前页面")
            return
        }
        sessionActionGeneration += 1L
        if (!automationYieldedToUser) {
            beginUserControl("收到的招呼人工列表已连续稳定确认")
            userControlPhase = UserControlPhase.IDLE
            pendingUserPageInspection = false
            manualTakeoverCoordinator.quietPeriod.reset()
        }
        receivedGreetingsPageProbePending = false

        val accepted = runCatching {
            receivedGreetingsTaskExecutor.beginFromConfirmedList()
        }.getOrDefault(false)
        if (!accepted) {
            onLog("收到的招呼人工任务启动[REJECTED]：确认后的当前列表未通过第二次窗口/阶段复核；不执行任何导航动作")
            return
        }

        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        currentSessionKey = null
        currentSessionWindowKey = null
        currentSessionName = null
        currentSessionCandidate = null
        currentSessionReadCaptured = false
        messageReadInFlight = false
        messageReadTimeoutTask?.cancel()
        messageReadTimeoutTask = null
        pendingMessageReadSnapshot = null
        messageReadGeneration += 1L

        receivedGreetingsTaskController.markStarted()
        sessionActionCount = 0

        cancelUserControlRecoveryTasks(cancelMessageTabWatch = true)
        unknownCounterResetTask?.cancel()
        unknownCounterResetTask = null
        unknownCounterResetRevision += 1L
        unknownInteractionArbiter.reset()
        lastUnknownBehaviourAtUptimeMs = Long.MIN_VALUE
        manualTakeoverCoordinator.quietPeriod.reset()
        userControlPhase = UserControlPhase.IDLE
        pendingUserPageInspection = false
        userControlNavigationInFlight = false
        automationYieldedToUser = false
        userHandoffCanResumeAutonomous = false
        startupReadinessCoordinator.markReceivedGreetingsWorkAreaConfirmed()
        sessionManagerState = SessionManagerState.BROWSING_RECEIVED_GREETINGS

        onLog("收到的招呼人工任务[STARTED]：已确认用户手动打开的列表；暂停私人消息任务并接管当前页面；不点击入口、不返回消息列表")
        val firstStepAccepted = runCatching {
            receivedGreetingsTaskExecutor.performIdleStep()
        }.getOrDefault(false)
        if (!firstStepAccepted) {
            scheduleReceivedGreetingsRecoveryRetry("人工打开的列表已接管，但首次只读遍历检查暂未完成")
            return
        }
        scheduleFullInspectionAfter(RECEIVED_GREETINGS_RECHECK_DELAY_MS)
    }

    /**
     * Option A: a greetings task ends in place. It releases automation
     * ownership and waits for the user to leave the list; it never issues BACK or tab navigation.
     */
    private fun completeReceivedGreetingsTaskAtCurrentPage(): Boolean {
        if (!receivedGreetingsTaskActive) return false
        if (!ownsTask(RECEIVED_GREETINGS_TASK_ID)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("收到的招呼任务完成检查被阻止：租约已失效；按安全原则保持当前页面，不执行返回导航")
            return true
        }
        receivedGreetingsTaskController.markCompletedAwaitingUserExit()
        sessionActionGeneration += 1L
        releaseTaskOwnership("收到的招呼任务完成；保持当前页面等待用户离开")
        cancelUserControlRecoveryTasks(cancelMessageTabWatch = true)
        userControlPhase = UserControlPhase.IDLE
        pendingUserPageInspection = false
        userControlNavigationInFlight = false
        manualTakeoverCoordinator.quietPeriod.reset()
        automationYieldedToUser = true
        userHandoffCanResumeAutonomous = sessionRunStarted
        sessionManagerState = SessionManagerState.PAUSED
        onLog("收到的招呼任务[COMPLETE_STAY_ON_PAGE]：列表遍历已完成；保持当前收到的招呼页面；等待用户离开后再恢复私人消息任务")
        return true
    }

    private fun formatUnreadAudit(
        snapshot: ChatPageSnapshot,
        snapshotAmbiguousKeys: Set<String>
    ): String {
        val candidates = snapshot.chatListCandidates
        val privateRows = candidates.filter { it.selectionClass == "PRIVATE_CHAT" }
        val systemRows = candidates.filter { it.selectionClass == "SYSTEM_ENTRY" }
        val unread = candidates.filter { it.unreadStatus == ChatUnreadStatus.UNREAD }
        val unreadPrivate = unread.filter { it.selectionClass == "PRIVATE_CHAT" }
        val unknownPrivate = privateRows.filter { it.unreadStatus == ChatUnreadStatus.UNKNOWN }
        val eligibleUnknownPrivate = unknownPrivate.count(ChatSessionManagementPolicy::isEligible)
        val unreadSystem = systemRows.count { it.unreadStatus == ChatUnreadStatus.UNREAD }
        val unknownSystem = systemRows.count { it.unreadStatus == ChatUnreadStatus.UNKNOWN }
        val eligibleUnread = unreadPrivate.filter(ChatSessionManagementPolicy::isEligible)
        val blockedUnread = eligibleUnread.count {
            ChatSessionManagementPolicy.stableSessionKey(it) in snapshotAmbiguousKeys ||
                ChatSessionManagementPolicy.stableSessionKey(it) in ambiguousSessionIdentityKeys
        }
        val attemptedUnread = eligibleUnread.count {
            ChatSessionManagementPolicy.stableSessionKey(it) in attemptedSessionKeys
        }
        val actionableUnread = eligibleUnread.count {
            val key = ChatSessionManagementPolicy.stableSessionKey(it)
            key !in snapshotAmbiguousKeys && key !in ambiguousSessionIdentityKeys &&
                key !in attemptedSessionKeys
        }
        val diagnostics = snapshot.chatListUnreadDiagnostics
        return "行=${candidates.size}" +
            "；私人聊天=${candidates.count { it.selectionClass == "PRIVATE_CHAT" }}" +
            "；系统入口=${candidates.count { it.selectionClass == "SYSTEM_ENTRY" }}" +
            "；未分类=${candidates.count { it.selectionClass == "UNCLASSIFIED" }}" +
            "；全体行UNREAD=${unread.size}" +
            "；全体行READ=${candidates.count { it.unreadStatus == ChatUnreadStatus.READ }}" +
            "；全体行UNKNOWN=${candidates.count { it.unreadStatus == ChatUnreadStatus.UNKNOWN }}" +
            "；私人聊天未读=${unreadPrivate.size}" +
            "；私人聊天UNKNOWN=${unknownPrivate.size}" +
            "；可操作私人聊天UNKNOWN=${eligibleUnknownPrivate}" +
            "；系统入口未读=${unreadSystem}（不计入私人聊天未读）" +
            "；系统入口UNKNOWN=${unknownSystem}（不阻断私人聊天准入）" +
            "；几何准入未读=${eligibleUnread.size}" +
            "；身份冲突阻止=${blockedUnread}" +
            "；本轮已处理=${attemptedUnread}" +
            "；可操作未读=${actionableUnread.coerceAtLeast(0)}" +
            "；证据源ViewId=${unread.count { it.unreadEvidenceSource.name == "SEMANTIC_VIEW_ID" }}" +
            "；证据源标签=${unread.count { it.unreadEvidenceSource.name == "ACCESSIBILITY_LABEL" }}" +
            "；status_new节点=${diagnostics.statusNewNodeCount}" +
            "；status_new未读=${diagnostics.statusNewUnreadCount}" +
            "；status_new已读=${diagnostics.statusNewReadCount}" +
            "；status_new未知=${diagnostics.statusNewUnknownCount}" +
            "；status_new无效边界=${diagnostics.statusNewInvalidBoundsCount}" +
            "；status_new跨行=${diagnostics.statusNewOutsideRowCount}" +
            "；status_new空值=${diagnostics.statusNewEmptyValueCount}" +
            "；status_new非数字=${diagnostics.statusNewNonNumericValueCount}" +
            "；status_new控件缺失判已读=${diagnostics.statusNewAbsentReadCount}"
    }

    private fun scheduleSessionListStabilityRetry(reason: String) {
        if (sessionManagerState == SessionManagerState.PAUSED ||
            sessionManagerState == SessionManagerState.STOPPED
        ) return
        sessionTransitionTask?.cancel()
        if (sessionListStabilityRetryCount >= MAX_SESSION_LIST_STABILITY_RETRIES) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog(
                "AI 会话管理已暂停：会话列表连续 $MAX_SESSION_LIST_STABILITY_RETRIES 次未稳定（$reason）；" +
                    "不执行点击或滚动，等待新的页面事件或用户重新启动"
            )
            return
        }
        sessionListStabilityRetryCount += 1
        val attempt = sessionListStabilityRetryCount
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
        onLog(
            "AI 会话管理：$reason；进行第 $attempt/$MAX_SESSION_LIST_STABILITY_RETRIES 次短间隔复核，" +
                "不执行空坐标操作"
        )
        sessionTransitionTask = scheduler.schedule(SESSION_LIST_STABILITY_RETRY_DELAY_MS) {
            sessionTransitionTask = null
            if (!isAiReplyActive() || !sessionRunStarted) {
                onLog(
                    "AI 会话管理：列表稳定性复核任务未执行；原因=运行已不活跃或样本会话已结束；" +
                        "当前状态=${sessionManagerState.name}"
                )
                return@schedule
            }
            if (sessionManagerState != SessionManagerState.WAITING_FOR_SESSION_LIST) {
                onLog(
                    "AI 会话管理：列表稳定性复核任务取消执行；原因=状态已变化；" +
                        "当前状态=${sessionManagerState.name}"
                )
                return@schedule
            }
            scheduleFullInspection()
        }
    }

    private fun attemptOpenSession(candidate: ChatListCandidateSnapshot) {
        if (!isAiReplyActive() || !sessionRunStarted || !ownsTask(PRIVATE_MESSAGES_TASK_ID) ||
            automationYieldedToUser || userControlPhase != UserControlPhase.IDLE
        ) return

        val actionGeneration = sessionActionGeneration
        val attempt = privateMessagesTaskExecutor.openSession(
            candidate = candidate,
            blockedIdentityKeys = ambiguousSessionIdentityKeys
        ) { requestId, snapshot, selectedKey, result ->
            scheduler.schedule(0L) {
                if (activeSessionGestureRequestId == requestId) activeSessionGestureRequestId = null
                if (actionGeneration != sessionActionGeneration) {
                    onLog(
                        "已忽略过期自主点击回调：commandId=$requestId；" +
                            "提交代次=$actionGeneration；当前代次=$sessionActionGeneration；结果=${result.status}"
                    )
                    return@schedule
                }
                handleSessionTapResult(result, snapshot, selectedKey, actionGeneration)
            }
        }

        when (attempt) {
            is PrivateMessagesTaskExecutor.Attempt.Rejected -> {
                when (attempt.reason) {
                    PrivateMessagesTaskExecutor.RejectionReason.OWNERSHIP_LOST -> {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("AI 会话点击[OWNERSHIP_REJECTED]：构造手势前或过程中任务租约已变化；拒绝派发旧点击")
                    }
                    else -> {
                        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                        val message = when (attempt.reason) {
                            PrivateMessagesTaskExecutor.RejectionReason.PAGE_CHANGED ->
                                "AI 会话管理：点击前复核发现页面已变化；取消本次会话操作"
                            PrivateMessagesTaskExecutor.RejectionReason.AMBIGUOUS_SESSION_IDENTITY -> {
                                ambiguousSessionIdentityKeys += attempt.selectedKey
                                "AI 会话管理：点击前复核发现目标昵称已被本轮阻止或当前快照存在重复行；拒绝点击"
                            }
                            PrivateMessagesTaskExecutor.RejectionReason.SESSION_ROW_NOT_FOUND ->
                                "AI 会话管理：点击前未能重新匹配目标会话行；拒绝使用旧节点路径"
                            PrivateMessagesTaskExecutor.RejectionReason.UNREAD_ADMISSION_CHANGED ->
                                "AI 会话管理：点击前准入复核失败；仅允许明确未读且会话行、昵称、预览均完整可见的私人会话；" +
                                    "当前未读状态=${attempt.unreadStatus?.name ?: ChatUnreadStatus.UNKNOWN.name}；拒绝点击"
                            PrivateMessagesTaskExecutor.RejectionReason.UNSAFE_TAP_BOUNDS ->
                                "AI 会话管理：目标会话行复核失败或边界不安全；点击未派发、不消耗会话尝试次数，目标序号=${candidate.rowIndex}"
                            PrivateMessagesTaskExecutor.RejectionReason.MISSING_LIST_VIEWPORT ->
                                "AI 会话管理：点击前缺少当前列表视口边界；点击未派发、不消耗会话尝试次数，拒绝使用行中心或旧坐标"
                            PrivateMessagesTaskExecutor.RejectionReason.WINDOW_CONTEXT_CHANGED ->
                                "AI 会话管理：目标会话行所属窗口或显示坐标上下文已变化；拒绝点击"
                            PrivateMessagesTaskExecutor.RejectionReason.OWNERSHIP_LOST ->
                                "AI 会话点击[OWNERSHIP_REJECTED]：任务租约已变化；拒绝派发旧点击"
                        }
                        onLog(message)
                        scheduleFullInspection()
                    }
                }
                return
            }
            is PrivateMessagesTaskExecutor.Attempt.Submitted -> {
                activeSessionGestureRequestId = attempt.requestId
                when (attempt.submission) {
                    GestureSubmission.DEFERRED_NOT_READY -> {
                        if (activeSessionGestureRequestId == attempt.requestId) activeSessionGestureRequestId = null
                        attemptedSessionKeys.remove(attempt.selectedKey)
                        currentSessionKey = null
                        currentSessionName = null
                        currentSessionCandidate = null
                        currentSessionReadCaptured = false
                        currentSessionWindowKey = null
                        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                        onLog("AI 会话管理[DEFERRED_NOT_READY]：启动保护期间不打开会话、不消耗尝试次数；保留目标意图并等待全新列表快照")
                        scheduleAiReadinessRetry("会话行点击等待目标页面就绪") {
                            if (actionGeneration == sessionActionGeneration && sessionRunStarted && !automationYieldedToUser) {
                                attemptOpenSession(candidate)
                            }
                        }
                        return
                    }
                    GestureSubmission.REJECTED -> {
                        if (activeSessionGestureRequestId == attempt.requestId) activeSessionGestureRequestId = null
                        sessionManagerState = SessionManagerState.PAUSED
                        currentSessionKey = null
                        currentSessionName = null
                        currentSessionCandidate = null
                        onLog("AI 会话管理已暂停：系统明确拒绝会话点击派发；与页面跳转验证失败分开记录")
                        return
                    }
                    GestureSubmission.ACCEPTED -> {
                        currentSessionKey = attempt.selectedKey
                        synchronized(headerMismatchGuard) {
                            headerMismatchRecordedForCurrentAttempt = false
                        }
                        // Use the name from the just-revalidated row, not the older pre-screenshot snapshot.
                        currentSessionName = attempt.candidate.nameText
                        currentSessionCandidate = attempt.candidate
                        currentSessionReadCaptured = false
                        currentSessionWindowKey = null
                        sessionManagerState = SessionManagerState.OPENING_SESSION
                        attemptedSessionKeys += attempt.selectedKey
                        sessionActionCount += 1
                        readinessDeferredRetryCount = 0
                        readinessDeferredRetryLimitLogged = false
                    }
                }
            }
        }

        onLog(
            "AI 会话管理：已提交会话行点击；序号=${candidate.rowIndex + 1}；" +
                "触摸落点由统一手势策略在安全区域内生成；等待验证页面跳转"
        )
        sessionTransitionTask?.cancel()
        sessionTransitionTask = scheduler.schedule(SESSION_OPEN_VERIFY_DELAY_MS) {
            verifyOpenedSession()
        }
    }

    private fun handleSessionTapResult(
        result: GestureExecutionResult,
        snapshot: ChatPageSnapshot,
        selectedKey: String,
        actionGeneration: Long
    ) {
        if (sessionManagerState != SessionManagerState.OPENING_SESSION) return
        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话点击回调[STALE_CALLBACK_IGNORED]：私人消息任务租约已失效；不推进旧会话状态")
            return
        }
        if (result.status == GestureExecutionStatus.DEFERRED) {
            sessionTransitionTask?.cancel()
            sessionTransitionTask = null
            attemptedSessionKeys.remove(selectedKey)
            sessionActionCount = (sessionActionCount - 1).coerceAtLeast(0)
            currentSessionKey = null
            currentSessionName = null
            currentSessionCandidate = null
            currentSessionReadCaptured = false
            currentSessionWindowKey = null
            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
            onLog("AI 会话管理[DEFERRED]：会话点击在延迟派发阶段遇到启动保护；不消耗尝试次数，使用新列表快照重新定位目标")
            scheduleAiReadinessRetry("会话行点击等待目标页面就绪") { latest ->
                if (actionGeneration == sessionActionGeneration && sessionRunStarted && !automationYieldedToUser) {
                    val latestCandidate = latest.chatListCandidates.firstOrNull {
                        ChatSessionManagementPolicy.stableSessionKey(it) == selectedKey
                    }
                    if (latestCandidate != null) attemptOpenSession(latestCandidate)
                    else {
                        onLog("AI 会话管理[DEFERRED_REVERIFY]：新快照中未找到原目标会话；重新执行完整列表检查")
                        scheduleFullInspection()
                    }
                }
            }
            return
        }
        if (result.status != GestureExecutionStatus.COMPLETED) {
            sessionTransitionTask?.cancel()
            sessionTransitionTask = null
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话管理已暂停：会话点击手势未完成（${result.status}）；不假定已打开目标聊天")
            return
        }
        // The accessibility window event may arrive before or after this callback. The delayed
        // verification is intentionally independent and always reacquires the active page.
        if (!hasSameTargetPackage(snapshot)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话管理已暂停：点击完成后目标窗口身份不一致")
        } else {
            startupReadinessCoordinator.markInitializationComplete("首个自主会话点击已由 Android 完成")
        }
    }

    private fun verifyOpenedSession() {
        sessionTransitionTask = null
        if (!isAiReplyActive() || !sessionRunStarted ||
            sessionManagerState != SessionManagerState.OPENING_SESSION
        ) return
        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话打开复核被阻止：私人消息任务租约已失效；不处理旧页面状态")
            return
        }
        val current = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
        if (current?.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            current.packageName == targetPackage() &&
            current.messageListTarget != null
        ) {
            val headerMatchMode = currentSessionHeaderMatchMode(current)
            if (headerMatchMode == null) {
                rejectChatHeaderMismatch(current, "延迟页面复核")
                return
            }
            sessionManagerState = SessionManagerState.READING_SESSION
            currentSessionWindowKey = windowSessionKey(current)
            onLog(
                "AI 会话管理：页面跳转及聊天页顶部名称验证通过；匹配方式=${headerMatchMode.name}；" +
                    "开始只读采集"
            )
            readVisibleMessages(current)
            return
        }
        if (current?.pageKind == ChatPageKind.CHAT_LIST) {
            onLog("AI 会话管理：点击后仍停留在会话列表；记录本次未命中，不重复点击同一会话")
            currentSessionKey = null
            currentSessionName = null
            currentSessionCandidate = null
            currentSessionReadCaptured = false
            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
            handleSessionListPage(current)
        } else {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话管理已暂停：点击后的页面无法确认是目标聊天；不会继续操作或自动返回")
        }
    }

    /**
     * Both the event-driven inspection and the delayed fallback must persist the same redacted
     * header evidence. The event path normally wins the race and cancels the delayed task, so
     * diagnostics must not exist only in verifyOpenedSession(). Identity matching stays strict.
     */
    private fun rejectChatHeaderMismatch(snapshot: ChatPageSnapshot, source: String) {
        val shouldRecord = synchronized(headerMismatchGuard) {
            if (headerMismatchRecordedForCurrentAttempt) {
                false
            } else {
                headerMismatchRecordedForCurrentAttempt = true
                true
            }
        }
        if (!shouldRecord) {
            onLog(
                "AI 会话管理：忽略同一会话点击的重复名称不匹配回调；" +
                    "会话键=${currentSessionKey?.take(12) ?: "UNKNOWN"}；诊断与安全返回已由首个回调处理"
            )
            return
        }
        val expectedTitle = normalizeSessionTitle(currentSessionName)
        val actualTitle = normalizeSessionTitle(snapshot.chatTitleText)
        val titleComparison = ChatSessionManagementPolicy.compareSessionTitles(
            currentSessionName, snapshot.chatTitleText
        )
        val selectedCandidate = currentSessionCandidate
        runCatching {
            chatSessionManagementPort.persistChatHeaderValidationDiagnostic(
                snapshot,
                currentSessionName,
                matched = false,
                expectedCandidate = currentSessionCandidate
            )
        }
        val header = snapshot.chatHeaderDiagnostic
        onLog(
            "AI 会话管理：聊天页顶部名称校验未通过；诊断入口=$source；" +
                "目标标题可读=${expectedTitle.isNotBlank()}；页面标题可读=${actualTitle.isNotBlank()}；" +
                "规范化长度=${expectedTitle.length}/${actualTitle.length}；" +
                "目标列表行=${selectedCandidate?.rowIndex?.plus(1) ?: 0}；" +
                "目标文本来源=${selectedCandidate?.nameSource?.name ?: "UNKNOWN"}；" +
                "共同前缀字符数=${titleComparison.commonPrefixCodePointCount}；" +
                "共同后缀字符数=${titleComparison.commonSuffixCodePointCount}；" +
                "目标未匹配字符数=${titleComparison.expectedUnmatchedCodePointCount}；" +
                "页面未匹配字符数=${titleComparison.actualUnmatchedCodePointCount}；" +
                "标题节点找到=${header?.found == true}；标题节点系统可见=${header?.isVisibleToUser == true}；" +
                "标题文本长度=${header?.textLength ?: 0}；描述长度=${header?.contentDescriptionLength ?: 0}；" +
                "标题边界有效=${header?.boundsValid == true}；父节点找到=${header?.parentFound == true}；" +
                "父节点系统可见=${header?.parentIsVisibleToUser == true}；读取结果=${header?.readOutcome ?: "NO_DIAGNOSTIC"}；" +
                "已拒绝当前会话读取，不记录标题原文；准备安全返回并跳过该候选"
        )
        scheduleRejectedSessionReturn(snapshot)
    }

    /**
     * A mismatched title is a rejected session, not a reason to stop the entire read-only run.
     * Return only while the exact same target ChatActivity window is still active. Once the
     * session list is re-observed, the attempted key keeps this candidate from being tapped again.
     */
    private fun scheduleRejectedSessionReturn(snapshot: ChatPageSnapshot) {
        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话安全返回被阻止：私人消息任务未持有 UI 操作权")
            return
        }
        val rejectedWindowKey = windowSessionKey(snapshot)
        if (snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
            snapshot.packageName != targetPackage() || rejectedWindowKey == null || currentSessionKey == null
        ) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话管理已暂停：身份不匹配后无法确认当前聊天窗口；不执行返回或继续点击")
            return
        }

        currentSessionWindowKey = rejectedWindowKey
        sessionManagerState = SessionManagerState.RETURNING_TO_SESSION_LIST
        sessionTransitionTask?.cancel()
        sessionTransitionTask = scheduler.schedule(SESSION_RETURN_DELAY_MS) rejectedReturn@{
            sessionTransitionTask = null
            if (!isAiReplyActive() || !sessionRunStarted ||
                sessionManagerState != SessionManagerState.RETURNING_TO_SESSION_LIST
            ) return@rejectedReturn
            if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话安全返回[STALE_TASK_IGNORED]：私人消息任务租约已失效；不提交返回动作")
                return@rejectedReturn
            }

            val current = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
            if (current == null || current.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
                current.packageName != targetPackage() || windowSessionKey(current) != rejectedWindowKey
            ) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话管理已暂停：身份不匹配后的返回前复核失败；不执行全局返回")
                return@rejectedReturn
            }

            val accepted = runCatching {
                chatSessionManagementPort.navigateBackToSessionList()
            }.getOrDefault(false)
            if (!accepted) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话管理已暂停：身份不匹配后系统未接受返回动作；不尝试其它会话")
                return@rejectedReturn
            }

            onLog("AI 会话管理：已拒绝当前会话并提交安全返回；等待会话列表验证后尝试下一候选")
            sessionTransitionTask = scheduler.schedule(SESSION_RETURN_VERIFY_DELAY_MS) verifyRejectedReturn@{
                sessionTransitionTask = null
                if (!isAiReplyActive() || !sessionRunStarted ||
                    sessionManagerState != SessionManagerState.RETURNING_TO_SESSION_LIST
                ) return@verifyRejectedReturn
                val returned = inspectFreshPageSnapshot()
                if (returned?.pageKind == ChatPageKind.CHAT_LIST && returned.packageName == targetPackage()) {
                    lastPageInspection = returned
                    handleSessionListPage(returned)
                } else {
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("AI 会话管理已暂停：身份不匹配后的返回未能确认会话列表；不继续点击")
                }
            }
        }
    }

    private fun currentSessionHeaderMatchMode(snapshot: ChatPageSnapshot) =
        ChatSessionManagementPolicy.matchChatHeader(currentSessionName, snapshot.chatTitleText)

    private fun normalizeSessionTitle(value: String?): String =
        ChatSessionManagementPolicy.normalizeSessionTitle(value)

    private fun scheduleReturnToSessionList(snapshot: ChatPageSnapshot) {
        if (sessionManagerState != SessionManagerState.READING_SESSION) return
        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话返回被阻止：私人消息任务未持有 UI 操作权")
            return
        }
        sessionTransitionTask?.cancel()
        sessionTransitionTask = scheduler.schedule(SESSION_RETURN_DELAY_MS) {
            sessionTransitionTask = null
            if (!isAiReplyActive() || !sessionRunStarted ||
                sessionManagerState != SessionManagerState.READING_SESSION
            ) return@schedule
            if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话返回[STALE_TASK_IGNORED]：私人消息任务租约已失效；不提交返回动作")
                return@schedule
            }
            val current = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
            if (current == null || current.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
                current.packageName != snapshot.packageName || !hasSameWindowSession(snapshot, current)
            ) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话管理已暂停：返回前聊天窗口身份已变化；不执行全局返回")
                return@schedule
            }
            sessionManagerState = SessionManagerState.RETURNING_TO_SESSION_LIST
            val accepted = runCatching {
                chatSessionManagementPort.navigateBackToSessionList()
            }.getOrDefault(false)
            if (!accepted) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话管理已暂停：系统未接受返回会话列表动作")
                return@schedule
            }
            onLog("AI 会话管理：消息采集完成，已提交系统返回动作；等待会话列表页面验证")
            sessionTransitionTask = scheduler.schedule(SESSION_RETURN_VERIFY_DELAY_MS) {
                sessionTransitionTask = null
                val returned = inspectFreshPageSnapshot()
                if (returned?.pageKind == ChatPageKind.CHAT_LIST && returned.packageName == targetPackage()) {
                    handleSessionListPage(returned)
                } else {
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("AI 会话管理已暂停：返回动作后未确认会话列表；不执行下一次点击")
                }
            }
        }
    }

    private fun scrollSessionList(
        snapshot: ChatPageSnapshot,
        toTop: Boolean = false
    ) {
        if (!isAiReplyActive() || !sessionRunStarted || !ownsTask(PRIVATE_MESSAGES_TASK_ID) ||
            automationYieldedToUser ||
            userControlPhase != UserControlPhase.IDLE
        ) return
        // Reacquire the active page immediately before scrolling. A previous list snapshot can
        // become stale after RecyclerView relayout, window changes, or user interaction.
        val inspectionResult = runCatching { chatPagePort.inspectChatPage() }
        val rawFresh = inspectionResult.getOrNull()
        val fresh = rawFresh?.let(::stabilizeIncomingPageSnapshot) ?: run {
            sessionManagerState = SessionManagerState.PAUSED
            val error = inspectionResult.exceptionOrNull()
            val reason = if (error == null) "INSPECTION_RETURNED_NO_SNAPSHOT"
            else "INSPECTION_EXCEPTION:${error.javaClass.simpleName}"
            onLog(
                "AI 会话管理已暂停：滚动前页面身份复核失败；原因=$reason；" +
                    "不会使用旧视口坐标"
            )
            return
        }
        val identityFailureReason = when {
            fresh.pageKind != ChatPageKind.CHAT_LIST ->
                "PAGE_KIND_MISMATCH:${fresh.pageKind.name}"
            fresh.packageName != targetPackage() ->
                "PACKAGE_MISMATCH"
            !fresh.hasSameWindowIdentity(snapshot) ->
                "WINDOW_IDENTITY_MISMATCH"
            else -> null
        }
        val sameWindowTransientUnknown = fresh.pageKind == ChatPageKind.UNKNOWN &&
            fresh.packageName == snapshot.packageName &&
            fresh.packageName == targetPackage() &&
            fresh.hasSameWindowIdentity(snapshot) &&
            snapshot.windowGeneration > 0L &&
            fresh.windowGeneration == snapshot.windowGeneration
        if (sameWindowTransientUnknown) {
            // The swipe has not been submitted. Keep the traversal phase and direction intent;
            // the next stability inspection will rebuild the viewport from a new node tree.
            pendingScrollBaseline = null
            pendingScrollToTop = false
            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
            onLog(
                "AI 会话管理：滚动前页面暂时UNKNOWN，但应用、windowId 和窗口代次未变化；" +
                    "本次不派发手势、不使用旧视口坐标，等待新快照复核"
            )
            scheduleSessionListStabilityRetry("滚动前页面结构暂时UNKNOWN")
            return
        }
        if (identityFailureReason != null) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog(
                "AI 会话管理已暂停：滚动前页面身份复核失败；原因=$identityFailureReason；" +
                    "目标包名匹配=${fresh.packageName == targetPackage()}；" +
                    "页面类型=${fresh.pageKind.name}；" +
                    "窗口身份匹配=${fresh.hasSameWindowIdentity(snapshot)}；" +
                    "节点扫描数=${fresh.scannedNodeCount}；" +
                    "列表候选数=${fresh.chatListCandidates.size}；" +
                    "不会使用旧视口坐标"
            )
            return
        }
        val listViewport = fresh.chatListViewport
        if (listViewport == null || listViewport.isEmpty ||
            listViewport.width < 240 || listViewport.height < 240
        ) {
            scheduleSessionSafeViewportRetry(
                toTop = toTop,
                expectedWindowIdentity = fresh.windowIdentityKey().orEmpty(),
                reason = "当前会话列表视口暂时不可用或尺寸不足"
            )
            return
        }
        // Keep the original list viewport for row/boundary fingerprints. Only the physical
        // gesture region is reduced, using a freshly reacquired mf_view bound from the adapter.
        val viewport = chatSessionManagementPort.resolveSessionListSwipeViewport(fresh)
        if (viewport == null || viewport.isEmpty || viewport.width < 240 || viewport.height < 240) {
            scheduleSessionSafeViewportRetry(
                toTop = toTop,
                expectedWindowIdentity = fresh.windowIdentityKey().orEmpty(),
                reason = "排除 mf_view 后暂时没有足够安全的列表滑动区域"
            )
            return
        }
        // A usable region has been freshly recomputed. Cancel the pending retry and reset its
        // backoff before constructing a new GestureIntentRequest from this snapshot only.
        cancelSessionSafeViewportRetry(resetCount = true)
        if (viewport != listViewport) {
            onLog(
                "AI 会话管理：已识别 mf_view 悬浮控件；本次滑动使用排除后的安全区域=" +
                    "${viewport.left},${viewport.top},${viewport.right},${viewport.bottom}；" +
                    "列表原视口仍用于位置与边界复核"
            )
        }
        val sourceWindow = fresh.messageListTarget?.window
            ?: fresh.chatListCandidates.firstOrNull()?.rowTarget?.window
        val executionContext = sourceWindow?.let { gesturePolicy.captureContext(it) } ?: run {
            pendingScrollBaseline = null
            pendingScrollToTop = false
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话管理已暂停：列表滚动坐标缺少匹配的窗口/显示上下文；不使用旧坐标")
            return
        }
        val request = GestureIntentRequest(
            id = "momo-session-scroll-${System.currentTimeMillis()}",
            intent = GestureIntent.VerticalSwipe(
                viewport = viewport,
                direction = if (toTop) VerticalSwipeDirection.DOWN else VerticalSwipeDirection.UP,
                durationMs = SESSION_SCROLL_DURATION_MS
            ),
            description = if (toTop) {
                "AI 会话管理：从底部向上审视时，在列表视口内向下滑动到上一个区域"
            } else {
                "AI 会话管理：启动定位底部时，在列表视口内向上滑动到下一个区域"
            },
            executionContext = executionContext
        )
        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话滚动[OWNERSHIP_REJECTED]：构造手势后任务租约已变化；拒绝派发旧滑动")
            return
        }
        val freshAmbiguousIdentityKeys = ChatSessionManagementPolicy.ambiguousSessionIdentityKeys(
            fresh.chatListCandidates
        )
        val newlyAmbiguousFreshKeys = freshAmbiguousIdentityKeys - ambiguousSessionIdentityKeys
        if (newlyAmbiguousFreshKeys.isNotEmpty()) {
            ambiguousSessionIdentityKeys += newlyAmbiguousFreshKeys
            // Keep the message index in sync with the runtime guard. A collision discovered
            // during pre-scroll inspection must revoke AI eligibility from already stored samples.
            newlyAmbiguousFreshKeys.forEach(chatSessionManagementPort::blockSessionIdentityKey)
            val affectedRows = fresh.chatListCandidates.count {
                ChatSessionManagementPolicy.stableSessionKey(it) in newlyAmbiguousFreshKeys &&
                    ChatSessionManagementPolicy.isEligible(it)
            }
            onLog(
                "AI 会话管理：滚动前复核发现规范化昵称身份冲突；冲突键数=${newlyAmbiguousFreshKeys.size}；" +
                    "关联可见行数=$affectedRows；已加入本轮身份阻止列表"
            )
        }
        // The baseline measures viewport movement, not click eligibility. Keep blocked rows in
        // this set so a viewport containing only blocked identities is still measurable.
        // Use the exact same viewport fingerprint on both sides of the comparison.
        // handleSessionListPage() compares complete visible-row identity and screen bounds so a
        // RecyclerView that is still moving cannot look stationary. Row indexes are intentionally
        // excluded because auxiliary dynamic cards can change list child indexes independently.
        // An identity-only baseline here would never equal that geometry-aware snapshot,
        // causing every completed swipe to be misclassified as movement and preventing
        // the no-progress boundary confirmation from ever starting.
        pendingScrollBaseline = visibleSessionKeys(fresh)
        pendingScrollToTop = toTop
        sessionManagerState = SessionManagerState.SCROLLING_SESSION_LIST
        val actionGeneration = sessionActionGeneration
        activeSessionGestureRequestId = request.id
        val submission = runCatching {
            gesturePolicy.execute(request) { result ->
                scheduler.schedule(0L) {
                    if (activeSessionGestureRequestId == request.id) activeSessionGestureRequestId = null
                    if (actionGeneration != sessionActionGeneration) {
                        onLog(
                            "已忽略过期自主滑动回调：commandId=${request.id}；" +
                                "提交代次=$actionGeneration；当前代次=$sessionActionGeneration；结果=${result.status}"
                        )
                        return@schedule
                    }
                    if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("AI 会话滚动回调[STALE_TASK_IGNORED]：私人消息任务租约已失效；不推进列表状态")
                        return@schedule
                    }
                    if (sessionManagerState != SessionManagerState.SCROLLING_SESSION_LIST) return@schedule
                    when (result.status) {
                        GestureExecutionStatus.COMPLETED -> {
                            val wasInitialAdmissionSwipe = !toTop && !initialUnreadGateSwipeCompleted
                            if (wasInitialAdmissionSwipe) {
                                initialUnreadGateSwipeCompleted = true
                                // This first gesture is an admission probe, not bottom-boundary
                                // positioning. Its post-gesture snapshot must be inspected even
                                // when the visible rows did not move, so discard boundary tracking.
                                pendingScrollBaseline = null
                                pendingScrollToTop = false
                                noProgressScrollCount = 0
                                onLog("AI Reply 启动准入：首次向上滑动已由 Android 确认成功；将用滑动后的新快照判定未读")
                            }
                            cancelledScrollRecoveryCount = 0
                            readinessDeferredRetryCount = 0
                            readinessDeferredRetryLimitLogged = false
                            startupReadinessCoordinator.markInitializationComplete("首个自主列表滑动已由 Android 完成")
                            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                            onLog(
                                when {
                                    wasInitialAdmissionSwipe -> "AI 会话管理：首次向上准入滑动已完成；等待滑动后的新快照判定未读"
                                    toTop -> "AI 会话管理：已完成一次向上审视方向的列表滑动；等待新快照位置复核"
                                    else -> "AI 会话管理：已完成一次底部定位方向的列表滑动；等待新快照确认位置"
                                }
                            )
                            scheduleFullInspection()
                        }
                        GestureExecutionStatus.CANCELLED -> {
                            // A cancelled gesture is not proof of reaching either list boundary.
                            // Discard the old baseline and reacquire the page before any retry.
                            pendingScrollBaseline = null
                            pendingScrollToTop = false
                            cancelledScrollRecoveryCount += 1
                            onLog(
                                "AI 会话管理：列表滚动被系统取消；第 $cancelledScrollRecoveryCount/" +
                                    "$MAX_CANCELLED_SCROLL_RECOVERIES 次恢复检查；重新读取页面与列表位置"
                            )
                            sessionTransitionTask?.cancel()
                            sessionTransitionTask = scheduler.schedule(SCROLL_CANCEL_RECOVERY_DELAY_MS) recovery@{
                                sessionTransitionTask = null
                                if (!isAiReplyActive() || !sessionRunStarted ||
                                    sessionManagerState != SessionManagerState.SCROLLING_SESSION_LIST
                                ) return@recovery
                                val refreshed = inspectFreshPageSnapshot()
                                if (refreshed == null ||
                                    refreshed.pageKind != ChatPageKind.CHAT_LIST ||
                                    refreshed.packageName != targetPackage() ||
                                    !refreshed.hasSameWindowIdentity(snapshot)
                                ) {
                                    sessionManagerState = SessionManagerState.PAUSED
                                    onLog(
                                        "AI 会话管理已暂停：滚动取消后页面复核失败；可能发生页面切换或服务中断；" +
                                            "不判定遍历完成，也不继续使用旧列表坐标"
                                    )
                                } else if (cancelledScrollRecoveryCount <= MAX_CANCELLED_SCROLL_RECOVERIES) {
                                    sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                                    onLog("AI 会话管理：取消后确认仍处于会话列表；使用新快照进行有限重试")
                                    scrollSessionList(refreshed, toTop)
                                } else {
                                    sessionManagerState = SessionManagerState.PAUSED
                                    onLog("AI 会话管理已暂停：列表滚动连续取消且达到恢复上限；等待用户检查")
                                }
                            }
                        }
                        GestureExecutionStatus.DEFERRED -> {
                            pendingScrollBaseline = null
                            pendingScrollToTop = false
                            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                            onLog("AI 会话管理[DEFERRED]：列表滑动在实际派发前遇到启动保护；结束旧轨迹，等待新快照后重算")
                            scheduleAiReadinessRetry("列表滚动等待目标页面就绪") { latest ->
                                if (actionGeneration == sessionActionGeneration && sessionRunStarted && !automationYieldedToUser) {
                                    scrollSessionList(latest, toTop)
                                }
                            }
                        }
                        GestureExecutionStatus.REJECTED,
                        GestureExecutionStatus.FAILED -> {
                            pendingScrollBaseline = null
                            pendingScrollToTop = false
                            sessionManagerState = SessionManagerState.PAUSED
                            onLog("AI 会话管理已暂停：列表滚动执行失败（${result.status}）；不判定遍历完成")
                        }
                    }
                }
            }
        }.getOrDefault(GestureSubmission.REJECTED)
        when (submission) {
            GestureSubmission.DEFERRED_NOT_READY -> {
                if (activeSessionGestureRequestId == request.id) activeSessionGestureRequestId = null
                pendingScrollBaseline = null
                pendingScrollToTop = false
                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                onLog("AI 会话管理[DEFERRED_NOT_READY]：列表滚动未派发；保留滚动方向，等待静默结束并用新视口重算轨迹")
                scheduleAiReadinessRetry("列表滚动等待目标页面就绪") { latest ->
                    if (actionGeneration == sessionActionGeneration && sessionRunStarted && !automationYieldedToUser) {
                        scrollSessionList(latest, toTop)
                    }
                }
            }
            GestureSubmission.REJECTED -> {
                if (activeSessionGestureRequestId == request.id) activeSessionGestureRequestId = null
                pendingScrollBaseline = null
                pendingScrollToTop = false
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话管理已暂停：系统明确拒绝列表滚动派发；尚未执行页面验证，不判定为页面验证失败")
            }
            GestureSubmission.ACCEPTED -> {
                // ACCEPTED means the coordinator admitted/scheduled the command. Only the
                // asynchronous COMPLETED callback proves Android actually performed the swipe.
            }
        }
    }

    /**
     * A transient mf_view overlay can cover the remaining gesture rectangle for an unknown
     * amount of time. Keep the traversal intent and asynchronously reacquire the page instead
     * of pausing the whole session scan. Every retry rebuilds the viewport and gesture from a
     * new node tree; no old bounds or gesture command is replayed.
     */
    private fun scheduleSessionSafeViewportRetry(
        toTop: Boolean,
        expectedWindowIdentity: String,
        reason: String
    ) {
        if (!isAiReplyActive() || !sessionRunStarted || automationYieldedToUser ||
            userControlPhase != UserControlPhase.IDLE
        ) return
        if (sessionSafeViewportRetryTask != null) return

        pendingScrollBaseline = null
        pendingScrollToTop = false
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST

        val delayMs = if (sessionSafeViewportRetryCount < SESSION_SAFE_VIEWPORT_FAST_RETRIES) {
            SESSION_SAFE_VIEWPORT_RETRY_DELAY_MS
        } else {
            SESSION_SAFE_VIEWPORT_RETRY_BACKOFF_MS
        }
        sessionSafeViewportRetryCount += 1
        val attempt = sessionSafeViewportRetryCount
        val retryGeneration = sessionSafeViewportRetryGeneration
        val actionGeneration = sessionActionGeneration
        onLog(
            "AI 会话管理[SAFE_VIEWPORT_RETRY]：$reason；保留滚动方向=${if (toTop) "向上审视" else "定位底部"}；" +
                "第 $attempt 次重新采集；${delayMs}ms 后重新计算，不使用旧坐标、不暂停遍历"
        )
        sessionSafeViewportRetryTask = scheduler.schedule(delayMs) retry@{
            sessionSafeViewportRetryTask = null
            if (retryGeneration != sessionSafeViewportRetryGeneration ||
                actionGeneration != sessionActionGeneration ||
                !isAiReplyActive() || !sessionRunStarted || automationYieldedToUser ||
                userControlPhase != UserControlPhase.IDLE ||
                sessionManagerState != SessionManagerState.WAITING_FOR_SESSION_LIST
            ) return@retry

            val refreshed = inspectFreshPageSnapshot()
            if (refreshed == null) {
                onLog("AI 会话管理[SAFE_VIEWPORT_RETRY]：本轮未取得新页面快照；保留滚动意图并继续重新采集")
                scheduleSessionSafeViewportRetry(toTop, expectedWindowIdentity, "新页面快照暂不可用")
                return@retry
            }
            val transientUnknownOnSameWindow = refreshed.pageKind == ChatPageKind.UNKNOWN &&
                refreshed.packageName == targetPackage() &&
                refreshed.windowIdentityKey() == expectedWindowIdentity &&
                lastConfirmedChatListInspection?.hasSameWindowIdentity(refreshed) == true
            if (transientUnknownOnSameWindow) {
                onLog("AI 会话管理[SAFE_VIEWPORT_RETRY]：同一窗口页面树仍暂时UNKNOWN；保留滚动意图，等待新快照")
                scheduleSessionSafeViewportRetry(toTop, expectedWindowIdentity, "同一窗口页面树暂时UNKNOWN")
                return@retry
            }
            if (refreshed.pageKind != ChatPageKind.CHAT_LIST ||
                refreshed.packageName != targetPackage() ||
                refreshed.windowIdentityKey() != expectedWindowIdentity ||
                lastConfirmedChatListInspection?.hasSameWindowIdentity(refreshed) != true
            ) {
                cancelSessionSafeViewportRetry(resetCount = true)
                onLog(
                    "AI 会话管理[SAFE_VIEWPORT_RETRY_END]：新快照显示页面身份已变化；" +
                        "不派发旧列表手势，交由常规页面检查处理"
                )
                scheduleFullInspection()
                return@retry
            }

            onLog(
                "AI 会话管理[SAFE_VIEWPORT_RECALCULATE]：已取得新的会话列表快照；" +
                    "重新读取 mf_view Bounds、计算安全矩形并构建新手势请求"
            )
            scrollSessionList(refreshed, toTop)
        }
    }

    private fun cancelSessionSafeViewportRetry(resetCount: Boolean) {
        sessionSafeViewportRetryTask?.cancel()
        sessionSafeViewportRetryTask = null
        sessionSafeViewportRetryGeneration += 1L
        if (resetCount) sessionSafeViewportRetryCount = 0
    }

    /** Acquires the newest page tree and validates it against the last confirmed inbox identity. */
    private fun inspectFreshPageSnapshot(): ChatPageSnapshot? {
        val raw = runCatching { chatPagePort.inspectChatPage() }.getOrNull() ?: return null
        return stabilizeIncomingPageSnapshot(raw)
    }

    /**
     * Keeps the last positively confirmed inbox identity as comparison-only evidence across
     * transient UNKNOWN samples. It never supplies coordinates or node paths to an action.
     */
    private fun stabilizeIncomingPageSnapshot(incoming: ChatPageSnapshot): ChatPageSnapshot {
        val confirmed = lastConfirmedChatListInspection
            ?: lastPageInspection?.takeIf { it.pageKind == ChatPageKind.CHAT_LIST }
        val stable = stabilizeConfirmedChatList(confirmed, incoming)
        when {
            stable.pageKind == ChatPageKind.CHAT_LIST -> lastConfirmedChatListInspection = stable
            stable.pageKind == ChatPageKind.UNKNOWN && confirmed != null &&
                confirmed.packageName == stable.packageName &&
                confirmed.hasSameWindowIdentity(stable) ->
                lastConfirmedChatListInspection = confirmed
            else -> lastConfirmedChatListInspection = null
        }
        lastPageInspection = stable
        if (stable !== incoming) {
            onLog("AI 页面稳定性[TRANSIENT_UNKNOWN_NORMALIZED]：新快照的窗口代次、消息Tab锚点和可见主列表视口一致；使用新快照数据，不复用旧节点路径或坐标")
        }
        return stable
    }

    /**
     * Retains an AI Reply intent when the foreground quiet gate defers dispatch. Every retry
     * reacquires a new page snapshot; callers recompute all bounds and gesture geometry from it.
     */
    private fun scheduleAiReadinessRetry(
        reason: String,
        retry: (ChatPageSnapshot) -> Unit
    ) {
        if (!isAiReplyActive()) return
        if (readinessDeferredRetryTask != null) return
        val retryDelay = if (readinessDeferredRetryCount < MAX_AI_READINESS_DEFERRED_RETRIES) {
            readinessDeferredRetryCount++
            AI_READINESS_RETRY_DELAY_MS
        } else {
            if (!readinessDeferredRetryLimitLogged) {
                readinessDeferredRetryLimitLogged = true
                onLog("AI 启动保护[RETRY_BACKOFF]：$reason；等待时间较长，改为每 5 秒重新验证一次；待执行意图继续保留")
            }
            AI_READINESS_RETRY_BACKOFF_MS
        }
        val generation = readinessDeferredRetryGeneration
        readinessDeferredRetryTask = scheduler.schedule(retryDelay) {
            readinessDeferredRetryTask = null
            if (generation != readinessDeferredRetryGeneration || !isAiReplyActive()) return@schedule
            val fresh = inspectFreshPageSnapshot()
            if (fresh == null) {
                onLog("AI 启动保护[REVERIFY_UNAVAILABLE]：$reason；本轮没有取得新页面快照，保留意图继续等待")
                scheduleAiReadinessRetry(reason, retry)
                return@schedule
            }
            onLog(
                "AI 启动保护[REVERIFIED]：$reason；retry=${readinessDeferredRetryCount}/$MAX_AI_READINESS_DEFERRED_RETRIES；" +
                    "page=${fresh.pageKind.name}；Activity=${fresh.activityClass.substringAfterLast('.')}; " +
                    "windowGeneration=${fresh.windowGeneration}；使用本轮新快照重新计算操作"
            )
            lastPageInspection = fresh
            retry(fresh)
        }
    }

    /**
     * A transient received-greetings observation failure schedules another fresh full snapshot.
     * The delay grows to a bounded ceiling and then remains at that ceiling while the task is
     * active; this prevents a retry budget from silently converting a recoverable page into PAUSED.
     */
    private fun scheduleReceivedGreetingsRecoveryRetry(reason: String) {
        if (!sessionRunStarted || !receivedGreetingsTaskActive ||
            sessionManagerState != SessionManagerState.BROWSING_RECEIVED_GREETINGS
        ) return
        val retryCount = receivedGreetingsTaskController.nextRecoveryRetryCount()
        val delayMs = ReceivedGreetingsRetrySchedule.delayForRetry(retryCount)
        onLog(
            "收到的招呼自动恢复[RETRY]：$reason；第${retryCount}次新鲜快照复核；" +
                "delay=${delayMs}ms；任务和当前用户进度保持不变"
        )
        scheduleFullInspectionAfter(delayMs)
    }

    private fun cancelAiReadinessRetry(resetCount: Boolean) {
        readinessDeferredRetryTask?.cancel()
        readinessDeferredRetryTask = null
        readinessDeferredRetryGeneration++
        if (resetCount) {
            readinessDeferredRetryCount = 0
            readinessDeferredRetryLimitLogged = false
        }
    }

    private fun hasSameTargetPackage(snapshot: ChatPageSnapshot): Boolean =
        snapshot.packageName == targetPackage()

    private fun scheduleFullInspection() {
        scheduleFullInspectionAfter(fullInspectionDebounceMs)
    }

    private fun scheduleFullInspectionAfter(delayMs: Long) {
        if (userControlPhase != UserControlPhase.IDLE) {
            pendingUserPageInspection = true
            return
        }
        cancelMessageReadRetry()
        pageObservationCoordinator.cancelFullInspection()
        pageObservationCoordinator.cancelRegionRefresh()
        pageInspectionTask?.cancel()
        pageInspectionTask = scheduler.schedule(delayMs, ::runFullInspection)
    }

    /**
     * A missing list locator can be a short transition/recycling window. Retry asynchronously
     * by taking a fresh page snapshot; never reuse old platform node handles or block
     * the accessibility callback with sleep().
     */
    private fun scheduleMessageListRetry() {
        // Keep one retry budget for the entire unavailable-list episode. A transiently changing
        // accessibility tree must not reset the budget and turn retries into an unbounded loop.
        if (messageReadRetryTask != null) return
        if (messageReadRetryCount >= MAX_MESSAGE_LIST_RETRIES) {
            onLog("AI 模式消息列表连续不可用；已完成 $MAX_MESSAGE_LIST_RETRIES 次延迟重试，等待下一次界面事件")
            cancelMessageReadRetry()
            return
        }

        messageReadRetryCount += 1
        messageReadRetryTask = scheduler.schedule(MESSAGE_LIST_RETRY_DELAY_MS) {
            messageReadRetryTask = null
            if (!isAiReplyActive() || !chatPagePort.supportsTargetPackage(targetPackage())) {
                cancelMessageReadRetry()
                return@schedule
            }

            // The snapshot and node paths are deliberately refreshed after the delay. If the
            // user navigated away, the current page contract will reject the read without using
            // the previous chat's locator.
            val refreshed = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
            if (refreshed == null || refreshed.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
                refreshed.packageName != targetPackage() ||
                refreshed.messageListTarget == null || refreshed.inputFieldTarget == null
            ) {
                lastPageInspection = refreshed
                lastRegionFingerprint = ""
                cancelMessageReadRetry()
                return@schedule
            }
            lastPageInspection = refreshed
            readVisibleMessages(refreshed)
        }
    }

    private fun invalidatePendingMessageRead() {
        messageReadGeneration += 1L
        messageReadInFlight = false
        messageReadTimeoutTask?.cancel()
        messageReadTimeoutTask = null
        pendingMessageReadSnapshot = null
        lastMessageFingerprint = ""
        loggedOcrDebugFingerprints.clear()
        ocrDebugBatchLimitLogged = false
        cancelMessageReadRetry()
    }

    private fun hasSameWindowSession(
        first: ChatPageSnapshot,
        second: ChatPageSnapshot
    ): Boolean {
        val firstMessageWindow = first.messageListTarget?.window ?: return false
        val firstInputWindow = first.inputFieldTarget?.window ?: return false
        val secondMessageWindow = second.messageListTarget?.window ?: return false
        val secondInputWindow = second.inputFieldTarget?.window ?: return false
        fun sameWindowGeneration(left: com.freedom.core.accessibility.api.WindowRef,
                                 right: com.freedom.core.accessibility.api.WindowRef): Boolean =
            left.packageName == right.packageName && left.windowId >= 0 && right.windowId >= 0 &&
                left.windowId == right.windowId && left.generation == right.generation
        return sameWindowGeneration(firstMessageWindow, firstInputWindow) &&
            sameWindowGeneration(secondMessageWindow, secondInputWindow) &&
            sameWindowGeneration(firstMessageWindow, secondMessageWindow) &&
            first.conversationIdentityKey() != null &&
            first.conversationIdentityKey() == second.conversationIdentityKey()
    }

    private fun windowSessionKey(snapshot: ChatPageSnapshot): String? {
        val messageWindow = snapshot.messageListTarget?.window ?: return null
        val inputWindow = snapshot.inputFieldTarget?.window ?: return null
        if (messageWindow.packageName != inputWindow.packageName ||
            messageWindow.windowId < 0 || inputWindow.windowId < 0 ||
            messageWindow.windowId != inputWindow.windowId ||
            messageWindow.generation != inputWindow.generation
        ) return null
        val conversationIdentity = snapshot.conversationIdentityKey() ?: return null
        return "${messageWindow.packageName}|${messageWindow.windowId}|${messageWindow.generation}|conversation=$conversationIdentity"
    }

    private fun cancelMessageReadRetry() {
        messageReadRetryTask?.cancel()
        messageReadRetryTask = null
        messageReadRetryCount = 0
    }

    private fun summarize(snapshot: ChatPageSnapshot): String = when (snapshot.pageKind) {
        ChatPageKind.UNKNOWN -> "无法确认当前目标应用窗口"
        ChatPageKind.NON_CHAT -> "当前不是已识别的聊天页面"
        ChatPageKind.CHAT_LIST -> "识别到目标应用消息入口/会话列表候选"
        ChatPageKind.CHAT_DETAIL_CANDIDATE -> "识别到聊天详情页面候选（尚未确认一对一）"
    } + "；Activity=${snapshot.activityClass.ifBlank { "未知" }}；" +
        "输入控件=${snapshot.editableInputCount}；列表候选=${snapshot.messageListCount}；" +
        "发送控件=${snapshot.sendActionCount}；消息相关 ViewId=${snapshot.messageRelatedViewIdCount}；" +
        "扫描节点=${snapshot.scannedNodeCount}；结构证据节点=${snapshot.structureEvidence.size}" +
        formatChatListSessionSummary(snapshot) +
        formatNodeTreeProbeSummary(snapshot)

    /** Logs only counts and geometry-derived state; never emits session names or previews. */
    private fun formatChatListSessionSummary(snapshot: ChatPageSnapshot): String {
        if (snapshot.pageKind != ChatPageKind.CHAT_LIST) return ""
        val candidates = snapshot.chatListCandidates
        return "；会话行候选=${candidates.size}" +
            "；名称节点可读=${candidates.count { it.hasReadableName }}" +
            "；预览节点可读=${candidates.count { it.hasReadablePreview }}" +
            "；头像节点=${candidates.count { it.hasAvatar }}" +
            "；会话行系统可见=${candidates.count { it.rowVisibleToUser }}" +
            "；会话行与视口相交=${candidates.count { it.intersectsViewport }}" +
            "；会话行完整位于视口=${candidates.count { it.fullyInsideViewport }}" +
            "；会话行边界有效=${candidates.count { it.rowBoundsValid }}" +
            "；名称节点系统可见=${candidates.count { it.nameNodeVisibleToUser }}" +
            "；预览节点系统可见=${candidates.count { it.previewNodeVisibleToUser }}" +
            "；名称节点几何相交=${candidates.count { it.nameBoundsIntersectViewport }}" +
            "；预览节点几何相交=${candidates.count { it.previewBoundsIntersectViewport }}" +
            "；名称节点完整位于视口=${candidates.count { it.nameBoundsFullyInsideViewport }}" +
            "；预览节点完整位于视口=${candidates.count { it.previewBoundsFullyInsideViewport }}" +
            "；名称节点边界无效=${candidates.count { !it.nameBoundsValid }}" +
            "；预览节点边界无效=${candidates.count { !it.previewBoundsValid }}" +
            "；已丢弃无效会话行边界=${snapshot.chatListInvalidRowBoundsCount}" +
            "；名称文本来源NODE_TEXT=${candidates.count { it.nameSource.name == "NODE_TEXT" }}" +
            "；预览文本来源NODE_TEXT=${candidates.count { it.previewSource.name == "NODE_TEXT" }}" +
            "；status_new节点=${snapshot.chatListUnreadDiagnostics.statusNewNodeCount}" +
            "；status_new未读=${snapshot.chatListUnreadDiagnostics.statusNewUnreadCount}" +
            "；status_new已读=${snapshot.chatListUnreadDiagnostics.statusNewReadCount}" +
            "；status_new未知=${snapshot.chatListUnreadDiagnostics.statusNewUnknownCount}" +
            "；status_new控件缺失判已读=${snapshot.chatListUnreadDiagnostics.statusNewAbsentReadCount}" +
            "；节点提取截断=${snapshot.chatListExtractionTruncated}（不输出会话文本）"
    }

    private fun formatNodeTreeProbeSummary(snapshot: ChatPageSnapshot): String {
        if (snapshot.nodeTreeProbeVisitedNodeCount == 0 && !snapshot.nodeTreeProbeTruncated) return ""
        return "；节点树探针=只读（不参与消息读取）" +
            "；节点树探针扫描=${snapshot.nodeTreeProbeVisitedNodeCount}" +
            "；节点树探针截断=${snapshot.nodeTreeProbeTruncated}" +
            "；替代列表探针=${snapshot.structureEvidence.count { it.isAlternateListProbe }}" +
            "；招呼消息行候选=${snapshot.structureEvidence.count { it.isMessageRowCandidate }}"
    }

    /**
     * Emits bounded, redacted node evidence in numbered records. MonitorStore persists up to
     * 5,000 characters per record; each evidence payload stays below 4,500 characters to leave
     * room for the timestamp/category prefix and avoid silent truncation.
     */
    private fun logStructureEvidence(snapshot: ChatPageSnapshot) {
        if (snapshot.structureEvidence.isEmpty()) return

        val entries = snapshot.structureEvidence
            .take(MAX_LOGGED_STRUCTURE_EVIDENCE)
            .map(::formatNodeEvidence)
        val chunks = ArrayList<String>()
        var current = StringBuilder()
        for (entry in entries) {
            val separator = if (current.isEmpty()) "" else " || "
            if (current.isNotEmpty() &&
                current.length + separator.length + entry.length > MAX_STRUCTURE_LOG_LINE_CHARS
            ) {
                chunks += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(" || ")
            current.append(entry)
        }
        if (current.isNotEmpty()) chunks += current.toString()

        chunks.forEachIndexed { index, chunk ->
            onLog("AI 模式节点树明细[${index + 1}/${chunks.size}]：$chunk")
        }

        val omitted = snapshot.structureEvidence.size - entries.size
        if (omitted > 0) {
            onLog("AI 模式节点树明细：受证据数量上限限制，省略节点=$omitted")
        }
    }

    /** Structural fields only: never serialize node text, content descriptions or contact data. */
    private fun formatNodeEvidence(
        item: com.freedom.features.aireply.ports.ChatPageNodeEvidence
    ): String {
        val flags = buildList {
            if (item.isEditable) add("editable")
            if (item.isScrollable) add("scrollable")
            if (item.isClickable) add("clickable")
            if (item.insideListCandidate) add("insideListProbe")
            if (item.isListProbeRoot) add("probeRoot")
            if (item.isAlternateListProbe) add("alternateListProbe")
            if (item.isMessageRowCandidate) add("messageRowCandidate")
            if (item.hasText) add("hasText")
            if (item.className.contains("image", ignoreCase = true) ||
                item.className.contains("drawee", ignoreCase = true) ||
                item.className.contains("avatar", ignoreCase = true) ||
                item.viewId.contains("avatar", ignoreCase = true)
            ) add("imageLike")
            add("visible=${item.isVisibleToUser}")
        }.joinToString(",")
        return "path=${item.path},depth=${item.depth},parent=${item.parentPath.ifBlank { "-" }}," +
            "class=${item.className.substringAfterLast('.')}," +
            "id=${item.viewId.ifBlank { "-" }}," +
            "bounds=[${item.left},${item.top}][${item.right},${item.bottom}]," +
            "children=${item.childCount},flags=${flags.ifBlank { "-" }}"
    }

}
