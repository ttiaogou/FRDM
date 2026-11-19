package com.freedom.features.aireply

import com.freedom.gesture.api.GestureApi
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.event.AccessibilityEventPolicy
import com.freedom.core.accessibility.events.AccessibilityUiSignal
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.PageObservationCoordinator
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import com.freedom.features.aireply.domain.contact.TemporaryContactMessageLedger
import com.freedom.features.aireply.domain.contact.ContactSendCountUpdate
import com.freedom.features.aireply.ports.ChatMessagePort
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ChatSessionManagementPort
import com.freedom.features.aireply.ports.ContactIdentityCaptureRequestResult
import com.freedom.features.aireply.ports.ContactIdentityCaptureOutcome
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpointRepository
import com.freedom.features.recovery.RecoveryTransactionEvent
import com.freedom.features.recovery.UnifiedRecoveryTransactionStateMachine
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashSet

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
    /** Called after verified session history has been persisted, with the exact chat snapshot. */
    private val onVerifiedConversationObserved: (ChatMessageBatch, ContactRowIdentity?, ChatPageSnapshot) -> Unit = { _, _, _ -> },
    /** Inclusive random bounds are injectable so task scheduling can be tested deterministically. */
    private val chooseRandomInt: (Int, Int) -> Int = { minimum, maximum ->
        java.util.concurrent.ThreadLocalRandom.current().nextInt(minimum, maximum + 1)
    },
    /** Shared task/UI ownership; nullable only for isolated legacy unit fixtures. */
    private val taskCoordinator: TaskCoordinator? = null,
    private val currentRunGeneration: () -> Long = { 0L },
    private val sharedPageObservationCoordinator: PageObservationCoordinator? = null,
    /** Prevents leaving the chat while a bounded model request/draft verification is in flight. */
    private val isConversationDraftPending: () -> Boolean = { false },
    /** Current-window draft callback; true reserves the page for generation/review. */
    private val resolveConversationIdentity: (ContactRowIdentity?, String?) -> ContactRowIdentity? = { identity, _ -> identity },
    /** Loads verified draft history after identity restoration and before OCR starts. */
    private val prepareDraftHistory: (ContactRowIdentity?, ChatPageSnapshot) -> Unit = { _, _ -> },
    private val onDraftConversationObserved: (ChatMessageBatch, ChatPageSnapshot, ContactRowIdentity?, String) -> Boolean = { _, _, _, _ -> false },
    private val draftStartupFailure: () -> String? = { null },
    /** A verified draft stays on screen so the user can review it and press Send manually. */
    private val isDraftAwaitingUserSend: () -> Boolean = { false },
    /** Invalidates late model callbacks when this runtime is reset. */
    private val onDraftRuntimeReset: () -> Unit = {},
    private val privateMessagesCheckpointRepository: PrivateMessagesTraversalCheckpointRepository? = null
) {
    companion object {
        private const val MAX_VISIBLE_MESSAGES = 20
        private const val MAX_LOGGED_STRUCTURE_EVIDENCE = 256
        private const val MAX_STRUCTURE_LOG_LINE_CHARS = 3_500
        private const val MESSAGE_LIST_RETRY_DELAY_MS = 120L
        private const val MAX_MESSAGE_LIST_RETRIES = 2
        private val TRANSIENT_EMPTY_SNAPSHOT_RETRY_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 5_000L, 10_000L)
        /** Long waits are polling intervals, not a recovery timeout. */
        private const val SNAPSHOT_RECOVERY_LOW_FREQUENCY_DELAY_MS = 30_000L
        private const val RECEIVED_GREETINGS_USER_QUIET_MIN_MS = 2_000
        private const val RECEIVED_GREETINGS_USER_QUIET_MAX_MS = 5_000
        private const val RECEIVED_GREETINGS_USER_ACTIVE_RECHECK_DELAY_MS = 10_000L
        private const val MAX_OCR_DEBUG_TEXT_CHARS = 4_200
        private const val MAX_OCR_DEBUG_BATCHES_PER_WINDOW = 24
        private const val SESSION_OPEN_VERIFY_DELAY_MS = 2_200L
        private const val SESSION_RETURN_DELAY_MS = 1_500L
        private const val SESSION_RETURN_VERIFY_DELAY_MS = 900L
        private const val DRAFT_PENDING_POLL_DELAY_MS = 500L
        private const val MAX_DRAFT_PENDING_WAIT_MS = 185_000L
        private const val IDENTITY_CAPTURE_TIMEOUT_MS = 15_000L
        private const val MAX_IDENTITY_CAPTURE_RETRIES = 30
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
        private val MESSAGE_LIST_RECOVERY_RETRY_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
        /** Re-check transient mf_view overlap without pausing the session-list traversal. */
        private const val SESSION_SAFE_VIEWPORT_RETRY_DELAY_MS = 650L
        private const val SESSION_SAFE_VIEWPORT_RETRY_BACKOFF_MS = 5_000L
        private const val SESSION_SAFE_VIEWPORT_FAST_RETRIES = 8
        private const val LOW_FREQUENCY_IDLE_MIN_SECONDS = 60
        private const val LOW_FREQUENCY_IDLE_MAX_SECONDS = 120
        private const val PRIORITY_RETURN_INSPECTION_DELAY_MS = 500L
        private const val RECEIVED_GREETINGS_RECHECK_DELAY_MS = 1_300L
        private const val RECEIVED_GREETINGS_NAVIGATION_RECHECK_DELAY_MS = 750L
        private const val MAX_EVENT_BURST_INSPECTION_WAIT_MS = 1_200L
        private const val SESSION_TAP_DURATION_MS = 90L
        private const val SESSION_SCROLL_DURATION_MS = 480L
        private const val PRIVATE_MESSAGES_TASK_ID = PrivateMessagesTaskController.TASK_ID
        private const val RECEIVED_GREETINGS_TASK_ID = ReceivedGreetingsTaskController.TASK_ID
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
    private val receivedGreetingsTaskFlow = ReceivedGreetingsTaskFlowController(
        ownership = taskOwnership,
        pagePort = chatPagePort
    )
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
        get() = receivedGreetingsTaskFlow.isActive
    private val receivedGreetingsTaskCompletedAwaitingUserExit: Boolean
        get() = receivedGreetingsTaskFlow.completedAwaitingUserExit
    private val awaitingNormalInboxAfterGreetingsReturn: Boolean
        get() = receivedGreetingsTaskFlow.awaitingNormalInboxAfterReturn

    private var pageInspectionTask: ScheduledTask? = null
    private var regionRefreshTask: ScheduledTask? = null
    private val inboxPageRecognizer = MomoInboxPageRecognizer()
    private val receivedGreetingsPageRecognizer by lazy(LazyThreadSafetyMode.NONE) {
        MomoReceivedGreetingsPageRecognizer {
            if (!sessionRunStarted || !receivedGreetingsTaskFlow.supportsWorkflow()
            ) {
                ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION
            } else {
                receivedGreetingsTaskFlow.observeManuallyOpenedPage()
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
            },
            sharedCoordinator = sharedPageObservationCoordinator
        )
    }
    private var messageReadRetryTask: ScheduledTask? = null
    private var messageReadRetryCount = 0
    private var readinessDeferredRetryTask: ScheduledTask? = null
    private var readinessDeferredRetryCount = 0
    private var readinessDeferredRetryLimitLogged = false
    private var readinessDeferredRetryGeneration = 0L
    /** Startup navigation is page-structure verified and remains bounded. */
    private var lastPageInspection: ChatPageSnapshot? = null
    /** Last positively confirmed inbox anchor; survives only same-window transient UNKNOWN samples. */
    private var lastConfirmedChatListInspection: ChatPageSnapshot? = null
    private var lastRegionFingerprint = ""
    private var lastMessageFingerprint = ""
    private val loggedOcrDebugFingerprints = LinkedHashSet<String>()
    private val temporaryContactMessageLedger = TemporaryContactMessageLedger(threshold = 10)
    /** Session keys awaiting an externally verified intercept-toggle result, in threshold order. */
    private val pendingInterceptionContactKeys = LinkedHashSet<String>()
    private var ocrDebugBatchLimitLogged = false

    /** Owns asynchronous OCR/message callback lifetime; Runtime handles only validated results. */
    private val messageReadCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        ChatMessageReadCoordinator(
            scheduler = scheduler,
            messagePort = chatMessagePort,
            messageLimit = MAX_VISIBLE_MESSAGES,
            isReadRequestAllowed = {
                isAiReplyActive() && chatPagePort.supportsTargetPackage(targetPackage())
            },
            inspectActivePage = { chatPagePort.inspectChatPage() },
            isResultCurrent = ::isMessageReadResultCurrent,
            shouldDrainPendingSnapshot = { !isReadOnlyGreetingMessageRead() },
            onValidatedResult = { requestedSnapshot, batch, activeSnapshot ->
                lastPageInspection = activeSnapshot
                handleMessageReadBatch(batch, requestedSnapshot)
            },
            onTimeout = { timeoutMs ->
                onLog(
                    "AI 模式消息读取超时：异步适配器未在 ${timeoutMs}ms 内返回；" +
                        "已丢弃该读取代次并安排新鲜页面复核"
                )
                scheduleMessageListRetry()
            },
            onAdapterFailure = {
                onLog("AI 模式消息读取失败：读取适配器异常；未保留消息正文")
                cancelMessageReadRetry()
            }
        )
    }

    private val startupReadinessCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        AiReplyStartupReadinessCoordinator(
            scheduler = scheduler,
            chatPagePort = chatPagePort,
            targetPackage = targetPackage,
            isAiReplyActive = isAiReplyActive,
            isSessionRunStarted = { sessionRunStarted },
            inspectFreshPageSnapshot = ::inspectFreshPageSnapshot,
            isMessageViewportPositionedAgainstTab = ::isMessageViewportPositionedAgainstTab,
            hasRelevantMessageStructureDelta = ::hasRelevantMessageStructureDelta,
            onConfirmed = { snapshot ->
                sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP
                onLog(
                    "AI 启动导航[CONFIRMED]：消息工作区域、底部导航锚点和会话列表视口已连续" +
                        "3 次新快照确认；开始执行启动顶部定位"
                )
                handleInspectionSnapshot(snapshot)
            },
            onTimedOut = { sessionManagerState = SessionManagerState.PAUSED },
            onLog = onLog
        )
    }




    /** One coordinator-owned transaction shared by message-list and received-greetings recovery. */
    private val recoveryTransaction = UnifiedRecoveryTransactionStateMachine()
    private val privateMessagesTraversalTaskExecutor = PrivateMessagesTraversalTaskExecutor()
    init {
        privateMessagesCheckpointRepository?.load()?.let { checkpoint ->
            privateMessagesTraversalTaskExecutor.restore(checkpoint)
            privateMessagesCheckpointRecoveryPending = true
            recoveryTransaction.dispatch(
                RecoveryTransactionEvent.CheckpointLoaded(RecoveryTransactionEvent.Source.PROCESS_RESTART)
            )
            onLog("消息列表进程恢复：已读取持久化检查点；等待页面结构重新确认后继续")
        }
    }
    private var sessionManagerState: SessionManagerState
        get() = privateMessagesTraversalTaskExecutor.executionState
        set(value) { privateMessagesTraversalTaskExecutor.executionState = value }
    /** Archive lifecycle is independent from the optional automatic session traversal state. */
    private var sampleCaptureActive = false
    private var sessionRunStarted = false
    /** Startup stays provisional until the first autonomous gesture is accepted by Android. */
    // Compatibility accessors keep this large coordinator readable while the executor owns the
    // actual traversal/session state. All writes still cross the same single owner.
    private var currentSessionKey: String?
        get() = privateMessagesTraversalTaskExecutor.currentSessionKey
        set(value) { privateMessagesTraversalTaskExecutor.currentSessionKey = value }
    private var currentSessionName: String?
        get() = privateMessagesTraversalTaskExecutor.currentSessionName
        set(value) { privateMessagesTraversalTaskExecutor.currentSessionName = value }
    private var currentSessionCandidate: ChatListCandidateSnapshot?
        get() = privateMessagesTraversalTaskExecutor.currentSessionCandidate
        set(value) { privateMessagesTraversalTaskExecutor.currentSessionCandidate = value }
    private var currentSessionReadCaptured: Boolean
        get() = privateMessagesTraversalTaskExecutor.currentSessionReadCaptured
        set(value) { privateMessagesTraversalTaskExecutor.currentSessionReadCaptured = value }
    private var currentSessionWindowKey: String?
        get() = privateMessagesTraversalTaskExecutor.currentSessionWindowKey
        set(value) { privateMessagesTraversalTaskExecutor.currentSessionWindowKey = value }
    /** Prevents duplicate mismatch handling; the per-attempt guard belongs to the traversal executor. */
    private val headerMismatchGuard: Any
        get() = privateMessagesTraversalTaskExecutor.headerMismatchGuard
    private var headerMismatchRecordedForCurrentAttempt: Boolean
        get() = privateMessagesTraversalTaskExecutor.headerMismatchRecordedForCurrentAttempt
        set(value) { privateMessagesTraversalTaskExecutor.headerMismatchRecordedForCurrentAttempt = value }
    private var sessionTransitionTask: ScheduledTask? = null
    /** Retains the scroll direction while the live mf_view exclusion leaves no safe rectangle. */
    private var sessionSafeViewportRetryTask: ScheduledTask? = null
    private var sessionSafeViewportRetryCount = 0
    private var sessionSafeViewportRetryGeneration = 0L
    /** Set by relevant target-app page-change events; probe admission never consults InputOrigin. */
    private var receivedGreetingsPageProbePending = false
    /** Short-lived empty accessibility roots are retried without replacing the last confirmed page. */
    private val transientEmptySnapshotRecovery = AiReplySessionTraversalCoordinator(
        TRANSIENT_EMPTY_SNAPSHOT_RETRY_DELAYS_MS
    )
    private var snapshotRecoveryStartedAtMs = 0L
    private var receivedGreetingsUserActive = false
    private var receivedGreetingsQuietTask: ScheduledTask? = null
    private var receivedGreetingsQuietGeneration = 0L
    private val pageRecoveryVerificationGate = PageRecoveryVerificationGate()
    private var receivedGreetingsRecoveryVerificationPending = false
    private val receivedGreetingsRecoveryVerificationGate = PageRecoveryVerificationGate()
    private var receivedGreetingsRecoveryVerifiedForNextSnapshot = false
    private var privateMessagesCheckpointRecoveryPending = false
    private val privateMessagesCheckpointVerificationGate = PageRecoveryVerificationGate()
    private var messageRecoveryRetryAttempt = 0
    private var messageListRecoveryVerificationPending = false
    /** Generation invalidates callbacks submitted for a page/task context that is no longer current. */
    private var sessionActionGeneration = 0L
    private var activeSessionGestureRequestId: String? = null
    private data class CurrentSessionBinding(
        val sourceWindowKey: String,
        val sourceRowPath: String,
        val sourceRowBounds: ScreenRect,
        val sourceAvatarBounds: ScreenRect?,
        val chatWindowKey: String,
        val chatSurfaceKey: String
    ) {
        fun matches(snapshot: ChatPageSnapshot, currentChatWindowKey: String?): Boolean =
            currentChatWindowKey == chatWindowKey &&
                windowSessionSurfaceKey(snapshot) == chatSurfaceKey

        /**
         * Profile navigation may recreate the chat window and rebuild both child paths.
         * Once a platform ID is verified, use the restored chat surface plus the caller's
         * auxiliary header check; do not require the old window generation or node paths.
         */
        fun matchesRestoredChatSurface(snapshot: ChatPageSnapshot): Boolean =
            snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
                snapshot.packageName.isNotBlank() &&
                snapshot.inputFieldTarget != null

        private fun windowSessionSurfaceKey(snapshot: ChatPageSnapshot): String? {
            val messageTarget = snapshot.messageListTarget ?: return null
            val inputTarget = snapshot.inputFieldTarget ?: return null
            val window = messageTarget.window
            val inputWindow = inputTarget.window
            if (window.packageName != inputWindow.packageName ||
                window.windowId != inputWindow.windowId ||
                window.generation != inputWindow.generation
            ) return null
            return listOf(
                window.packageName,
                window.windowId,
                window.generation,
                messageTarget.path.childIndices.joinToString("."),
                inputTarget.path.childIndices.joinToString(".")
            ).joinToString("|")
        }
    }
    private var currentSessionBinding: CurrentSessionBinding? = null
    private var identityCaptureSessionKey: String? = null
    private var identityCaptureStartedAtMs = 0L
    private var identityCaptureRetryCount = 0
    private var identityCaptureTerminalSessionKey: String? = null


    /**
     * Low-frequency watcher callback. It requests one read-only page inspection and never
     * navigates or changes task ownership without a page-structure result.
     */
    private fun requestReceivedGreetingsFallbackCheck() {
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive ||
            receivedGreetingsTaskCompletedAwaitingUserExit ||
            !receivedGreetingsTaskFlow.supportsWorkflow()
        ) return
        receivedGreetingsPageProbePending = true
        onLog("收到的招呼页面观察[LOW_FREQUENCY_CHECK]：执行一次只读页面结构检查")
        pageObservationCoordinator.signalFullInspection()
    }

    private fun runFullInspection() {
        pageInspectionTask = null
        if (!isAiReplyActive() || !chatPagePort.supportsTargetPackage(targetPackage())) return
        if (sessionRunStarted && receivedGreetingsTaskActive && receivedGreetingsUserActive) {
            onLog("收到的招呼恢复[USER_ACTIVE]：用户仍在操作；暂停页面指纹复核，10s后重新检查")
            scheduleFullInspectionAfter(RECEIVED_GREETINGS_USER_ACTIVE_RECHECK_DELAY_MS)
            return
        }
        if (sessionRunStarted && receivedGreetingsTaskActive &&
            !ownsTask(RECEIVED_GREETINGS_TASK_ID)
        ) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("收到的招呼任务观察被阻止：当前任务已不持有 UI 操作权；等待安全恢复")
            return
        }
        val inspection = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
        if (transientEmptySnapshotRecovery.isTransient(inspection)) {
            if (snapshotRecoveryStartedAtMs == 0L) {
                snapshotRecoveryStartedAtMs = System.currentTimeMillis()
                recoveryTransaction.dispatch(RecoveryTransactionEvent.UnknownPage("EMPTY_SNAPSHOT"))
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
                onLog("AI 页面观察[SNAPSHOT_RECOVERY_HOLD]：快照暂时丢失；保留当前任务租约和会话状态")
            }
            val elapsedMs = System.currentTimeMillis() - snapshotRecoveryStartedAtMs
            val nextDelayMs = transientEmptySnapshotRecovery.nextDelayMs(
                SNAPSHOT_RECOVERY_LOW_FREQUENCY_DELAY_MS
            )
            val phase = if (transientEmptySnapshotRecovery.retryCount <= TRANSIENT_EMPTY_SNAPSHOT_RETRY_DELAYS_MS.size) {
                "FAST_BACKOFF"
            } else {
                "LOW_FREQUENCY_WAIT"
            }
            onLog(
                "AI 页面观察[SNAPSHOT_RECOVERY]：页面快照为空或暂不可用；" +
                    "retry=${transientEmptySnapshotRecovery.retryCount};phase=$phase;" +
                    "已等待=${elapsedMs.coerceAtLeast(0L)}ms；${nextDelayMs}ms 后重新获取；" +
                    "保留最后一次已确认页面，不释放任务租约、不更新页面身份；" +
                    "恢复不会因等待时长自动终止"
            )
            scheduleFullInspectionAfter(nextDelayMs)
            return
        }
        transientEmptySnapshotRecovery.reset()
        val recovered = inspection!!
        if (snapshotRecoveryStartedAtMs != 0L) {
            if (!pageRecoveryVerificationGate.observe(recoveryStructuralKey(recovered))) {
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
                onLog("AI 页面恢复[RECOVERY_VERIFYING]：第${pageRecoveryVerificationGate.stableSamples}/2次独立新鲜快照尚未稳定；350ms后复核")
                scheduleFullInspectionAfter(350L)
                return
            }
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            // The page gate has already admitted two equal fresh observations.
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeRequested)
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeCompleted)
            onLog("AI 页面恢复[RECOVERY_VERIFIED]：连续两次新鲜结构快照一致；恢复消息列表任务")
            pageRecoveryVerificationGate.reset()
            snapshotRecoveryStartedAtMs = 0L
        }
        if (sessionRunStarted && receivedGreetingsTaskActive &&
            receivedGreetingsRecoveryVerificationPending
        ) {
            val fingerprint = runCatching {
                chatPagePort.receivedGreetingsRecoveryFingerprint()
            }.getOrNull()
            if (fingerprint.isNullOrBlank()) {
                onLog("收到的招呼恢复[RECOVERY_VERIFYING]：当前快照未通过收到招呼列表指纹；继续暂停任务")
                scheduleReceivedGreetingsRecoveryRetry("收到招呼列表指纹尚未确认；不执行滚动、点击或导航")
                return
            }
            if (!receivedGreetingsRecoveryVerificationGate.observe(fingerprint)) {
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
                onLog("收到的招呼恢复[RECOVERY_VERIFYING]：第${receivedGreetingsRecoveryVerificationGate.stableSamples}/2次独立指纹快照尚未稳定；350ms后复核")
                scheduleFullInspectionAfter(350L)
                return
            }
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            // The branch-specific fingerprint gate has already admitted two equal observations.
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeRequested)
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeCompleted)
            receivedGreetingsRecoveryVerificationPending = false
            receivedGreetingsRecoveryVerifiedForNextSnapshot = true
            receivedGreetingsRecoveryVerificationGate.reset()
            onLog("收到的招呼恢复[RECOVERY_VERIFIED]：连续两次新鲜列表指纹一致；恢复收到招呼任务协调器")
        }
        handleInspectionSnapshot(recovered)
    }

    private fun recoveryStructuralKey(snapshot: ChatPageSnapshot): String = listOf(
        snapshot.packageName, snapshot.pageKind.name, snapshot.scannedNodeCount,
        snapshot.messageListCount, snapshot.editableInputCount, snapshot.sendActionCount,
        snapshot.messageRelatedViewIdCount, snapshot.hasChatTab,
        snapshot.messageListTarget?.expectedViewId.orEmpty(),
        snapshot.messageListTarget?.expectedClassName.orEmpty(),
        snapshot.chatListViewport?.toString().orEmpty(), snapshot.chatListCandidates.size
    ).joinToString("|")

    /** System touch boundaries pause received-greetings recovery without becoming page evidence. */
    fun onGlobalTouchEvent(event: AccessibilityEventEnvelope) {
        if (!sessionRunStarted || !receivedGreetingsTaskActive) return
        receivedGreetingsUserActive = true
        recoveryTransaction.dispatch(RecoveryTransactionEvent.UserInteractionStarted)
        receivedGreetingsQuietGeneration++
        receivedGreetingsQuietTask?.cancel()
        receivedGreetingsQuietTask = null
        onLog("收到的招呼恢复[USER_ACTIVE]：检测到用户触摸；暂停恢复复核和业务动作；event=${event.type}")
        if (event.type == AccessibilityEventType.TOUCH_INTERACTION_END) {
            val expectedGeneration = receivedGreetingsQuietGeneration
            val quietMs = chooseRandomInt(
                RECEIVED_GREETINGS_USER_QUIET_MIN_MS,
                RECEIVED_GREETINGS_USER_QUIET_MAX_MS
            ).toLong()
            receivedGreetingsQuietTask = scheduler.schedule(quietMs) {
                if (expectedGeneration != receivedGreetingsQuietGeneration) return@schedule
                receivedGreetingsQuietTask = null
                receivedGreetingsUserActive = false
                recoveryTransaction.dispatch(RecoveryTransactionEvent.UserInteractionEnded)
                onLog("收到的招呼恢复[USER_QUIET]：用户已静默${quietMs}ms；开始双快照页面指纹复核")
                scheduleFullInspectionAfter(0L)
            }
        }
    }

    /** Applies a newly acquired page snapshot after ordinary debounce or verified user stability. */
    private fun handleInspectionSnapshot(incomingInspection: ChatPageSnapshot) {
        val previous = lastPageInspection
        val inspection = stabilizeIncomingPageSnapshot(incomingInspection)

        // 模型请求、分段填入及人工审核期间，不让遍历任务抢走页面。
        if (isConversationDraftPending() || isDraftAwaitingUserSend()) {
            lastPageInspection = inspection
            return
        }

        if (sessionRunStarted) {
            when {
                receivedGreetingsTaskActive && !ownsTask(RECEIVED_GREETINGS_TASK_ID) -> {
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("页面结构调度[OWNERSHIP_REJECTED]：收到的招呼任务未持有UI操作租约；仅保留只读观察")
                    return
                }
                receivedGreetingsTaskCompletedAwaitingUserExit -> {
                    val returnedToInbox = inspection.pageKind == ChatPageKind.CHAT_LIST &&
                        inspection.messageTabPositionKey() != null &&
                        inspection.messageTabAnchorPositionKey() != null
                    if (!returnedToInbox) {
                        sessionManagerState = SessionManagerState.PAUSED
                        lastPageInspection = inspection
                        return
                    }
                    if (!claimTaskOwnership(PRIVATE_MESSAGES_TASK_ID, "页面结构确认已离开收到的招呼列表并返回普通消息列表")) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("私人消息任务恢复[OWNERSHIP_REJECTED]：普通消息列表已确认，但未取得UI操作租约")
                        return
                    }
                    receivedGreetingsTaskFlow.acknowledgeCompletedTaskExit()
                    sessionActionGeneration += 1L
                    privateMessagesTraversalTaskExecutor.initialInboxPositionCheckCompleted = false
                    privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted = false
                    privateMessagesTraversalTaskExecutor.initialUnreadGateSwipeCompleted = false
                    privateMessagesTraversalTaskExecutor.lockedUnreadSessionKeys.clear()
                    privateMessagesTraversalTaskExecutor.lockedUnreadSetFinalized = false
                    privateMessagesTraversalTaskExecutor.normalInboxHadUnread = false
                    privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown = false
                    privateMessagesTraversalTaskExecutor.noProgressScrollCount = 0
                    privateMessagesTraversalTaskExecutor.sessionListStabilityRetryCount = 0
                    privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
                    privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
                    privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys = null
                    privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationToTop = false
                    privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount = 0
                    privateMessagesTraversalTaskExecutor.lastStableSessionListVisibleKeys = null
                    privateMessagesTraversalTaskExecutor.cancelledScrollRecoveryCount = 0
                    currentSessionKey = null
                    currentSessionName = null
                    currentSessionCandidate = null
                    currentSessionReadCaptured = false
                    currentSessionWindowKey = null
                    sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                    onLog("收到的招呼任务[PAGE_RETURN_CONFIRMED]：普通消息列表结构已重新确认；重新取得私人消息任务租约并执行原有未读准入流程")
                }
                !receivedGreetingsTaskActive && inspection.pageKind == ChatPageKind.CHAT_LIST &&
                    !ownsTask(PRIVATE_MESSAGES_TASK_ID) -> {
                    if (!claimTaskOwnership(PRIVATE_MESSAGES_TASK_ID, "页面结构确认普通消息列表符合私人消息任务条件")) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("私人消息任务[OWNERSHIP_REJECTED]：页面结构符合普通消息列表，但暂未取得UI操作租约")
                        return
                    }
                }
            }
        }
        if (previous != null && inboxPageRecognizer.isChatDetail(previous) &&
            (!inboxPageRecognizer.isChatDetail(inspection) ||
                !hasSameWindowSession(previous, inspection))
        ) {
            invalidatePendingMessageRead()
        }
        val previousKey = previous?.stableKey()
        val nextKey = inspection.stableKey()
        lastPageInspection = inspection

        if (!receivedGreetingsTaskActive &&
            inspection.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            chatSessionManagementPort.consumeIdentityReturnReinspectionRequest()
        ) {
            val restoredSessionKey = windowSessionKey(inspection) ?: run {
                onLog("AI 身份事务回接失败：返回聊天页未生成新的 windowSessionKey；暂不读取消息")
                return
            }
            currentSessionWindowKey = restoredSessionKey
            onLog(
                "AI 身份事务回接：资料页返回已确认原聊天；已写回返回页新的 windowSessionKey；" +
                    "主动重新观察聊天页并读取消息，启动 OCR"
            )
            readVisibleMessages(inspection)
            return
        }

        // Probe after any relevant target-app page-change event. Event provenance never gates
        // the probe or grants task ownership. Event bursts are coalesced by EventAssistedDebouncer;
        // the listener owns one stability retry and one low-frequency fallback timer. Only
        // adapter-confirmed page structure can transfer ownership to the Received Greetings task.
        if (receivedGreetingsPageProbePending && sessionRunStarted &&
            !receivedGreetingsTaskActive && !receivedGreetingsTaskCompletedAwaitingUserExit &&
            receivedGreetingsTaskFlow.supportsWorkflow()
        ) {
            receivedGreetingsPageProbePending = false
            val arrivalStatus = pageObservationCoordinator.onPageSnapshotAvailable()
            when (arrivalStatus) {
                ReceivedGreetingsPageArrivalStatus.NOT_TARGET,
                ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION,
                ReceivedGreetingsPageArrivalStatus.UNKNOWN -> Unit
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED -> return
                ReceivedGreetingsPageArrivalStatus.INTERMEDIATE_PAGE,
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING -> {
                    pauseForReceivedGreetingsPageTransition(arrivalStatus)
                    return
                }
            }
        }

        if (sessionRunStarted && receivedGreetingsTaskActive) {
            val decision = receivedGreetingsTaskFlow.evaluatePageSnapshot(inspection.pageKind)
            when (decision.kind) {
                ReceivedGreetingsTaskFlowController.DecisionKind.ENTRY_NOT_FOUND -> {
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("收到的招呼入口未发现：普通消息列表已完成双向搜索并复核两端边界；这是已确认的入口缺失结果，不是单次页面核验失败")
                    return
                }
                ReceivedGreetingsTaskFlowController.DecisionKind.COMPLETE -> {
                    if (completeReceivedGreetingsTaskAtCurrentPage()) return
                    val returned = runCatching {
                        receivedGreetingsTaskFlow.returnForPriorityCheck()
                    }.getOrDefault(false)
                    if (returned) {
                        receivedGreetingsTaskFlow.markAwaitingNormalInboxAfterReturn()
                        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                        if (decision.origin == ReceivedGreetingsTaskFlowController.DecisionOrigin.PAGE_PROGRESS) {
                            onLog("收到的招呼遍历已通过适配器完整性检查；已请求返回普通消息页，等待页面身份复核")
                        } else {
                            onLog("收到的招呼导航：适配器报告列表边界候选；仍需完成安全返回与普通列表页面复核")
                        }
                        scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
                    } else {
                        scheduleReceivedGreetingsRecoveryRetry(
                            if (decision.origin == ReceivedGreetingsTaskFlowController.DecisionOrigin.PAGE_PROGRESS)
                                "遍历已确认到达边界，但本轮未通过安全返回检查；保留完成快照和任务现场"
                            else "列表完整性刚刚确认，但安全返回暂未通过"
                        )
                    }
                    return
                }
                ReceivedGreetingsTaskFlowController.DecisionKind.WAIT_FOR_DETAIL_PAGE -> {
                    scheduleReceivedGreetingsRecoveryRetry(
                        "适配器报告详情候选但当前页面快照尚非聊天详情；等待同窗口新鲜快照，不进入普通消息列表流程"
                    )
                    return
                }
                ReceivedGreetingsTaskFlowController.DecisionKind.DETAIL_READY -> Unit
                ReceivedGreetingsTaskFlowController.DecisionKind.RETRY_REQUIRED -> {
                    scheduleReceivedGreetingsRecoveryRetry(
                        "页面身份/安全导航步骤暂未确认；保留导航现场，不盲目点击或返回"
                    )
                    return
                }
                ReceivedGreetingsTaskFlowController.DecisionKind.LIST_CONTINUES -> {
                    onLog("收到的招呼只读测试：已验证列表页面；优先采集未读行头像身份和聊天详情 OCR，完成后再滚动；不回复")
                    scheduleFullInspectionAfter(RECEIVED_GREETINGS_RECHECK_DELAY_MS)
                }
                ReceivedGreetingsTaskFlowController.DecisionKind.PROFILE_CARD_PENDING -> {
                    onLog("收到的招呼导航：已识别资料卡；等待 NewSayHiSessionListActivity；继续复核顶部‘列表’控件；不触碰通过/拒绝按钮")
                    scheduleFullInspectionAfter(RECEIVED_GREETINGS_RECHECK_DELAY_MS)
                }
                ReceivedGreetingsTaskFlowController.DecisionKind.ENTRY_NOT_FOUND_RECHECK -> {
                    onLog("收到的招呼导航：页面状态已变化为 ENTRY_NOT_FOUND；等待下一次页面级复核")
                    scheduleFullInspectionAfter(RECEIVED_GREETINGS_RECHECK_DELAY_MS)
                }
                ReceivedGreetingsTaskFlowController.DecisionKind.PAGE_TRANSITION_PENDING -> {
                    onLog("收到的招呼导航：页面状态已变化为 ${decision.status.name}；保留当前阶段并等待新快照")
                    scheduleFullInspectionAfter(RECEIVED_GREETINGS_NAVIGATION_RECHECK_DELAY_MS)
                }
            }
            if (decision.kind != ReceivedGreetingsTaskFlowController.DecisionKind.DETAIL_READY) return
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
                SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP,
                SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM,
                SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
            ) && previous?.pageKind == ChatPageKind.CHAT_LIST
        ) {
            recoveryTransaction.dispatch(RecoveryTransactionEvent.UnknownPage("MESSAGE_LIST_UNKNOWN"))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
            messageListRecoveryVerificationPending = true
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
                    sessionRunStarted && awaitingNormalInboxAfterGreetingsReturn -> {
                        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID) &&
                            !claimTaskOwnership(PRIVATE_MESSAGES_TASK_ID, "收到的招呼流程返回普通消息列表后恢复私人消息任务")
                        ) {
                            sessionManagerState = SessionManagerState.PAUSED
                            onLog("私人消息任务恢复[OWNERSHIP_REJECTED]：返回消息列表后未取得UI操作租约")
                            return
                        }
                        receivedGreetingsTaskFlow.markTraversalReturnedToInbox()
                        privateMessagesTraversalTaskExecutor.sessionActionCount = 0
                        privateMessagesTaskController.resetQuota()
                        // The greeting branch returned to the already-established inbox context.
                        // Preserve the existing traversal memory; only the open conversation and
                        // stale viewport baseline are invalid after returning from another page.
                        privateMessagesTraversalTaskExecutor.resumeAfterReturnedInbox()
                        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                        val eligibleUnread = inspection.chatListCandidates.count {
                            it.unreadStatus == ChatUnreadStatus.UNREAD && ChatSessionManagementPolicy.isEligible(it)
                        }
                        val eligibleUnknown = inspection.chatListCandidates.count {
                            it.unreadStatus == ChatUnreadStatus.UNKNOWN && ChatSessionManagementPolicy.isEligible(it)
                        }
                        onLog("AI Reply 测试遍历：收到的招呼列表已返回普通消息页；当前位置明确未读=$eligibleUnread；未知=$eligibleUnknown")
                        when {
                            eligibleUnknown > 0 -> {
                                privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown = true
                                sessionManagerState = SessionManagerState.PAUSED
                                onLog("AI Reply 测试遍历暂停：返回普通消息页存在 UNKNOWN 未读状态；不将未知判定为无消息")
                            }
                            eligibleUnread > 0 -> {
                                privateMessagesTraversalTaskExecutor.normalInboxHadUnread = true
                                privateMessagesTraversalTaskExecutor.phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
                                privateMessagesTraversalTaskExecutor.atTop = false
                                sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                                handleSessionListPage(inspection)
                            }
                            else -> enterUnreadWait("从收到的招呼返回普通消息页，当前位置没有符合条件的新消息")
                        }
                    }
                    sessionRunStarted -> handleSessionListPage(inspection)
                }
            }
            ChatPageKind.CHAT_DETAIL_CANDIDATE -> {
                if (sessionRunStarted && receivedGreetingsTaskActive) {
                    val detailStatus = runCatching { receivedGreetingsTaskFlow.traversalStatus() }
                        .getOrDefault(ReceivedGreetingsTraversalStatus.UNKNOWN)
                    if (detailStatus == ReceivedGreetingsTraversalStatus.DETAIL_READY) {
                        // 读取结果进入独立的人工审核草稿入口，同时保留本地样本归档。
                        readVisibleMessages(inspection)
                    } else {
                        scheduleReceivedGreetingsRecoveryRetry(
                            "收到招呼聊天页三节点指纹尚未确认；继续获取新鲜快照，不启动 OCR"
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
                        val selected = currentSessionCandidate
                        val chatWindowKey = currentSessionWindowKey
                        if (selected != null && chatWindowKey != null) {
                            currentSessionBinding = CurrentSessionBinding(
                                sourceWindowKey = listOf(
                                    selected.rowTarget.window.packageName,
                                    selected.rowTarget.window.windowId,
                                    selected.rowTarget.window.generation
                                ).joinToString("|"),
                                sourceRowPath = selected.rowTarget.path.childIndices.joinToString("."),
                                sourceRowBounds = selected.rowBounds,
                                sourceAvatarBounds = selected.avatarBounds,
                                chatWindowKey = chatWindowKey,
                                chatSurfaceKey = chatSurfaceKey(inspection).orEmpty()
                            )
                            onLog("AI 会话绑定：已建立来源行/聊天窗口/聊天表面绑定；昵称仅作辅助校验")
                        }
                        onLog(
                            "AI 会话管理：聊天页顶部名称与目标会话匹配；匹配方式=${headerMatchMode.name}；" +
                                "开始只读采集，不执行输入或发送"
                        )
                    } else {
                        rejectChatHeaderMismatch(inspection, "页面事件检查")
                    }
                }
                if ((!sessionRunStarted ||
                        sessionManagerState == SessionManagerState.READING_SESSION) &&
                    inspection.messageListTarget != null
                ) {
                    readVisibleMessages(inspection)
                }
            }
            ChatPageKind.UNKNOWN, ChatPageKind.NON_CHAT -> {
                if (sessionRunStarted && receivedGreetingsTaskActive) {
                    recoveryTransaction.dispatch(RecoveryTransactionEvent.UnknownPage())
                    recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
                    // A page classification failure is a recovery request, never a business step.
                    // The received-greetings coordinator retains the traversal checkpoint and
                    // only resumes after the adapter confirms the list/detail fingerprint again.
                    onLog(
                        "PageRecoveryRequested：收到招呼页面 UNKNOWN；" +
                            "暂停滚动/点击/导航，保留任务检查点，等待新鲜页面指纹复核"
                    )
                    if (!receivedGreetingsRecoveryVerifiedForNextSnapshot) {
                        receivedGreetingsRecoveryVerificationPending = true
                        receivedGreetingsRecoveryVerificationGate.reset()
                    }
                    val decision = if (receivedGreetingsRecoveryVerifiedForNextSnapshot) {
                        receivedGreetingsRecoveryVerifiedForNextSnapshot = false
                        receivedGreetingsTaskFlow.evaluatePageSnapshot(ChatPageKind.CHAT_LIST)
                    } else {
                        receivedGreetingsTaskFlow.evaluateUnclassifiedPageSnapshot()
                    }
                    when (decision.kind) {
                        ReceivedGreetingsTaskFlowController.DecisionKind.RETRY_REQUIRED -> {
                            scheduleReceivedGreetingsRecoveryRetry(
                                if (decision.status == ReceivedGreetingsTraversalStatus.SCANNING)
                                    "已识别列表，但本轮快照/安全滚动检查暂未完成"
                                else "无法验证当前页面或提交安全滚动；不执行用户行点击"
                            )
                        }
                        ReceivedGreetingsTaskFlowController.DecisionKind.LIST_CONTINUES -> {
                            onLog("收到的招呼只读测试：已验证列表页面；优先采集未读行头像身份和聊天详情 OCR，完成后再滚动；不回复")
                            scheduleFullInspectionAfter(RECEIVED_GREETINGS_RECHECK_DELAY_MS)
                        }
                        ReceivedGreetingsTaskFlowController.DecisionKind.COMPLETE -> {
                            if (completeReceivedGreetingsTaskAtCurrentPage()) return
                            val returned = runCatching {
                                receivedGreetingsTaskFlow.returnForPriorityCheck()
                            }.getOrDefault(false)
                            if (returned) {
                                receivedGreetingsTaskFlow.markAwaitingNormalInboxAfterReturn()
                                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                                onLog("收到的招呼测试遍历：列表扫描到达边界；已请求返回普通消息页，等待页面身份复核")
                                scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
                            } else {
                                scheduleReceivedGreetingsRecoveryRetry("列表边界已确认，但返回普通消息页暂未通过安全检查")
                            }
                        }
                        ReceivedGreetingsTaskFlowController.DecisionKind.PROFILE_CARD_PENDING -> {
                            scheduleReceivedGreetingsRecoveryRetry(
                                "资料卡顶部‘列表’控件尚未切换到已验证的招呼列表页面；不触碰通过/拒绝按钮"
                            )
                        }
                        ReceivedGreetingsTaskFlowController.DecisionKind.ENTRY_NOT_FOUND_RECHECK -> {
                            scheduleReceivedGreetingsRecoveryRetry(
                                "适配器报告入口未发现，但本次步骤已被接受；等待新鲜页面复核"
                            )
                        }
                        ReceivedGreetingsTaskFlowController.DecisionKind.PAGE_TRANSITION_PENDING -> {
                            scheduleReceivedGreetingsRecoveryRetry(
                                "入口/页面仍在切换或当前状态暂时未知；不执行盲目滑动或返回"
                            )
                        }
                        ReceivedGreetingsTaskFlowController.DecisionKind.ENTRY_NOT_FOUND -> {
                            scheduleReceivedGreetingsRecoveryRetry(
                                "收到的招呼入口状态暂未稳定；等待新鲜页面复核"
                            )
                        }
                        ReceivedGreetingsTaskFlowController.DecisionKind.WAIT_FOR_DETAIL_PAGE -> {
                            scheduleReceivedGreetingsRecoveryRetry(
                                "收到的招呼详情页面尚未稳定；等待同窗口新鲜快照"
                            )
                        }
                        ReceivedGreetingsTaskFlowController.DecisionKind.DETAIL_READY -> {
                            scheduleReceivedGreetingsRecoveryRetry(
                                "详情状态需要目标页面快照复核；不在未知页面推进详情操作"
                            )
                        }
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
     * Observes system touch boundaries. UNKNOWN boundaries are correlated with adjacent target
     * feedback and counted as one behaviour; only the third independent behaviour yields.
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
     * A page transition no longer enters a human-takeover state machine. It only invalidates
     * work tied to the old page and releases the private-task lease until structure confirms
     * which task is eligible to own the UI again.
     */
    private fun pauseForReceivedGreetingsPageTransition(status: ReceivedGreetingsPageArrivalStatus) {
        sessionActionGeneration += 1L
        val interruptedGestureRequestId = activeSessionGestureRequestId
        activeSessionGestureRequestId = null
        if (interruptedGestureRequestId != null) {
            gesturePolicy.cancelActive(interruptedGestureRequestId, "received_greetings_page_transition")
        }
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        cancelSessionSafeViewportRetry(resetCount = true)
        invalidatePendingMessageRead()
        if (ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            releaseTaskOwnership("页面结构进入收到招呼过渡态；暂停私人消息任务并让出UI操作租约")
        }
        // The current page kind itself prevents private-message execution while off the inbox.
        // Keep the traversal state resumable so a later CHAT_LIST snapshot can reacquire the lease
        // and continue through the existing page-driven scheduler without a manual-resume phase.
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
        onLog("收到的招呼页面观察[WAITING]：status=${status.name}；私人消息任务已释放UI操作租约；等待页面结构确认可执行任务")
    }

    private fun runRegionRefresh() {
        regionRefreshTask = null
        if (!isAiReplyActive() || !chatPagePort.supportsTargetPackage(targetPackage())) return
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
        runCatching { onDraftRuntimeReset() }
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
        recoveryTransaction.reset()
        messageListRecoveryVerificationPending = false
        messageRecoveryRetryAttempt = 0
        transientEmptySnapshotRecovery.reset()
        receivedGreetingsRecoveryVerificationPending = false
        receivedGreetingsRecoveryVerifiedForNextSnapshot = false
        receivedGreetingsRecoveryVerificationGate.reset()
        lastRegionFingerprint = ""
        lastMessageFingerprint = ""
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
        sessionRunStarted = false
        startupReadinessCoordinator.resetState(sessionStarted = false)
        sessionManagerState = SessionManagerState.STOPPED
        taskOwnership.reset("运行时重置：$reason")
        currentSessionKey = null
        currentSessionName = null
        currentSessionCandidate = null
        currentSessionReadCaptured = false
        currentSessionWindowKey = null
        currentSessionBinding = null
        identityCaptureSessionKey = null
        identityCaptureStartedAtMs = 0L
        identityCaptureRetryCount = 0
        identityCaptureTerminalSessionKey = null
        snapshotRecoveryStartedAtMs = 0L
        receivedGreetingsQuietTask?.cancel()
        receivedGreetingsQuietTask = null
        receivedGreetingsQuietGeneration++
        receivedGreetingsUserActive = false
        privateMessagesTraversalTaskExecutor.reset()
        lastMessageFingerprint = ""
        temporaryContactMessageLedger.clearAll()
        pendingInterceptionContactKeys.clear()
        privateMessagesTaskController.resetQuota()
        receivedGreetingsTaskFlow.reset()
        receivedGreetingsPageProbePending = false
    }

    /**
     * Route ownership requests through the controller that owns the requested business task.
     * Read-only page observations remain outside the UI-operation lease.
     */
    private fun claimTaskOwnership(taskId: String, reason: String): Boolean = when (taskId) {
        PrivateMessagesTaskController.TASK_ID -> privateMessagesTaskController.claimOwnership(reason)
        ReceivedGreetingsTaskController.TASK_ID -> receivedGreetingsTaskFlow.claimOwnership(reason)
        else -> false
    }

    private fun ownsTask(taskId: String): Boolean = when (taskId) {
        PrivateMessagesTaskController.TASK_ID -> privateMessagesTaskController.ownsTask()
        ReceivedGreetingsTaskController.TASK_ID -> receivedGreetingsTaskFlow.ownsTask()
        else -> false
    }

    private fun releaseTaskOwnership(reason: String) {
        when (taskOwnership.activeTaskId()) {
            PrivateMessagesTaskController.TASK_ID -> privateMessagesTaskController.releaseOwnership(reason)
            ReceivedGreetingsTaskController.TASK_ID -> receivedGreetingsTaskFlow.releaseOwnership(reason)
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

    fun scheduleInitialObservation() {
        val failure = runCatching { draftStartupFailure() }.getOrDefault("MODEL_CONFIGURATION_LOAD_FAILED")
        if (failure != null) {
            sessionRunStarted = false
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 回复启动被阻止：$failure；请修正配置后重新启动；未遍历列表")
            return
        }
        cancelAiReadinessRetry(resetCount = true)
        cancelSessionSafeViewportRetry(resetCount = true)
        startupReadinessCoordinator.cancelRecovery()
        val directory = runCatching {
            chatSessionManagementPort.beginSessionManagement()
        }.getOrNull()
        sampleCaptureActive = !directory.isNullOrBlank()
        sessionRunStarted = sampleCaptureActive
        if (sessionRunStarted && chatPagePort.hasRestorableReceivedGreetingsCheckpoint()) {
            recoveryTransaction.dispatch(
                RecoveryTransactionEvent.CheckpointLoaded(RecoveryTransactionEvent.Source.SERVICE_RECONNECT)
            )
            onLog(
                "收到招呼进程恢复：已保留持久化检查点；等待收到招呼列表页面指纹确认后再恢复任务，" +
                    "不因断点存在而执行盲目导航"
            )
        }
        privateMessagesTaskController.beginRun(currentRunGeneration())
        if (sessionRunStarted && !claimTaskOwnership(PRIVATE_MESSAGES_TASK_ID, "启动私人消息任务")) {
            sessionRunStarted = false
            onLog("AI Reply 启动暂停：私人消息任务未取得唯一 UI 操作权；不会执行自动滚动或打开会话")
        }
        startupReadinessCoordinator.resetState(sessionStarted = sessionRunStarted)
        privateMessagesTaskController.resetQuota()
        receivedGreetingsTaskFlow.reset()
        privateMessagesTraversalTaskExecutor.initialInboxPositionCheckCompleted = false
        privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted = false
        privateMessagesTraversalTaskExecutor.initialUnreadGateSwipeCompleted = false
        privateMessagesTraversalTaskExecutor.lockedUnreadSessionKeys.clear()
        privateMessagesTraversalTaskExecutor.lockedUnreadSetFinalized = false
        privateMessagesTraversalTaskExecutor.normalInboxHadUnread = false
        privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown = false
        receivedGreetingsPageProbePending = false
        pageObservationCoordinator.resetReceivedGreetingsWatch()
        privateMessagesTraversalTaskExecutor.sessionActionCount = 0
        privateMessagesTraversalTaskExecutor.phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
        privateMessagesTraversalTaskExecutor.atTop = false
        privateMessagesTraversalTaskExecutor.noProgressScrollCount = 0
        privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
        privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
        privateMessagesTraversalTaskExecutor.lastStableSessionListVisibleKeys = null
        privateMessagesTraversalTaskExecutor.capturedSessionListFingerprints.clear()
        lastMessageFingerprint = ""
        privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys = null
        privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationToTop = false
        privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount = 0
        privateMessagesCheckpointRecoveryPending = false
        messageListRecoveryVerificationPending = false
        messageRecoveryRetryAttempt = 0
        privateMessagesCheckpointVerificationGate.reset()
        privateMessagesCheckpointRepository?.load()?.let { checkpoint ->
            privateMessagesTraversalTaskExecutor.restore(checkpoint)
            privateMessagesCheckpointRecoveryPending = true
            recoveryTransaction.dispatch(
                RecoveryTransactionEvent.CheckpointLoaded(RecoveryTransactionEvent.Source.SERVICE_RECONNECT)
            )
            onLog("消息列表任务恢复：已重新装载检查点；等待连续两次消息列表结构快照确认")
        }
        sessionManagerState = if (sessionRunStarted) {
            SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP
        } else {
            SessionManagerState.PAUSED
        }
        when {
            sessionRunStarted -> {
                onLog(
                    "AI Reply 完整模型链路已启动：普通聊天按底部至顶部扫描；" +
                        "进入会话读取消息、调用模型并写入审核草稿；仅人工发送；样本目录=$directory"
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

    /** Re-reads the durable received-greetings checkpoint before page observations resume. */
    fun reloadReceivedGreetingsCheckpointAfterServiceReconnect(): Boolean = runCatching {
        chatPagePort.reloadReceivedGreetingsCheckpointAfterServiceReconnect()
    }.getOrDefault(false)

    /** Reloads the message-list checkpoint and accepts it only with a fresh list-page check. */
    fun reloadPrivateMessagesCheckpointAfterServiceReconnect(): Boolean {
        val checkpoint = privateMessagesCheckpointRepository?.load() ?: return false
        privateMessagesTraversalTaskExecutor.restore(checkpoint)
        val page = runCatching { chatPagePort.inspectChatPage() }.getOrNull() ?: return false
        val verified = page.pageKind == ChatPageKind.CHAT_LIST &&
            page.packageName == targetPackage() &&
            page.messageTabPositionKey() != null &&
            page.messageTabAnchorPositionKey() != null
        onLog(
            "消息列表服务重连：已重新读取检查点；当前页面结构=${if (verified) "已确认" else "待重新确认"}"
        )
        return verified
    }

    /** Schedules bounded page or known-region inspection from a normalized, redacted event. */
    fun schedule(event: AccessibilityEventEnvelope) {
        if (!chatPagePort.supportsTargetPackage(targetPackage())) return
        // Navigation-hit verification is an ephemeral adapter concern and must remain active
        // even when durable sample capture is off. The port implementation only persists events
        // while a collection run is open.
        runCatching { chatSessionManagementPort.recordAccessibilityEvent(event) }
        // Page probing is source-agnostic by design. UNKNOWN, PHYSICAL_USER and SELF_INJECTED
        // events can all indicate that the current page may have changed; none of them grants
        // operation ownership. The debouncer merges bursts before the read-only adapter probe.
        val receivedGreetingsPageChangeSignal = event.type in PAGE_CHANGE_RECHECK_EVENT_TYPES
        if (sessionRunStarted &&
            !receivedGreetingsTaskActive && !receivedGreetingsTaskCompletedAwaitingUserExit &&
            receivedGreetingsTaskFlow.supportsWorkflow() && receivedGreetingsPageChangeSignal
        ) {
            pageObservationCoordinator.onPageChangeEvent()
            receivedGreetingsPageProbePending = true
        }
        val knownInspection = lastPageInspection
        val knownChatDetail = knownInspection?.let {
            inboxPageRecognizer.isChatDetail(it)
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
        if (requiresFullInspection || receivedGreetingsPageProbePending) {
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
            // A failed known-region refresh already queues a fresh full inspection. Keep that
            // stronger recovery request; a subsequent scroll event must not replace it with a
            // weaker region refresh that could reuse the invalidated locators.
            if (pageInspectionTask != null) return
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
        messageReadCoordinator.request(snapshot)
    }

    /**
     * Revalidates the active page/window/session before any message batch reaches business
     * callbacks or sample persistence. The received-greetings detail path intentionally has a
     * narrower read-only contract because it does not own a private-message session key.
     */
    private fun isMessageReadResultCurrent(
        snapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        active: ChatPageSnapshot
    ): Boolean {
        val expectedWindowSessionKey = windowSessionKey(snapshot)
        if (isReadOnlyGreetingMessageRead()) {
            return active.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
                active.packageName == snapshot.packageName &&
                active.hasSameWindowIdentity(snapshot) &&
                active.windowGeneration == snapshot.windowGeneration &&
                (expectedWindowSessionKey == null || batch.windowSessionKey == expectedWindowSessionKey)
        }
        return active.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            active.packageName == snapshot.packageName &&
            active.hasSameWindowIdentity(snapshot) &&
            hasSameWindowSession(snapshot, active) &&
            expectedWindowSessionKey != null &&
            (currentSessionWindowKey == null || currentSessionWindowKey == expectedWindowSessionKey) &&
            batch.windowSessionKey == expectedWindowSessionKey
    }

    private fun isReadOnlyGreetingMessageRead(): Boolean =
        sessionRunStarted && receivedGreetingsTaskActive &&
            receivedGreetingsTaskFlow.traversalStatus() == ReceivedGreetingsTraversalStatus.DETAIL_READY

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
        val greetingDetail = isReadOnlyGreetingMessageRead()
        val privateDetail = sessionRunStarted && currentSessionCandidate != null &&
            currentSessionWindowKey == windowSessionKey(snapshot) &&
            ChatSessionManagementPolicy.matchChatHeader(currentSessionName, snapshot.chatTitleText) != null
        val draftTargetConfirmed = greetingDetail || privateDetail
        if (batch.ocrAttempted) {
            val mergedOcrCount = batch.ocrTargets.count { target ->
                batch.messages.any { message -> message.bounds == target.rowBounds }
            }
            val latestText = batch.messagesBottomToTop.firstOrNull()?.content
                ?.takeIf(String::isNotBlank)
                ?.let(::escapeOcrDebugText)
                ?: "[无可用最新消息文本]"
            onLog(
                "AI OCR 消息流水线：OCR目标数=${batch.ocrTargets.size}；" +
                    "OCR识别成功数=${batch.ocrRecognizedTargetCount}；" +
                    "OCR合并成功数=$mergedOcrCount；最终消息数=${batch.messages.size}；" +
                    "最新消息文本=\"$latestText\"；状态=${batch.status.name}"
            )
        }
        var identityJustVerified = false
        if (privateDetail) {
            val identitySessionKey = currentSessionWindowKey
            if (identityCaptureSessionKey != identitySessionKey) {
                identityCaptureSessionKey = identitySessionKey
                identityCaptureStartedAtMs = System.currentTimeMillis()
                identityCaptureRetryCount = 0
                identityCaptureTerminalSessionKey = null
                onLog("AI 联系人身份采集事务：已为当前聊天窗口建立新事务；等待身份终态")
            }
            val currentIdentity = currentSessionCandidate?.contactIdentity
            val identityVerified = currentIdentity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                !currentIdentity.contactId.isNullOrBlank()
            if (!identityVerified && identityCaptureTerminalSessionKey != identitySessionKey) {
                identityCaptureRetryCount++
                val identityElapsedMs = System.currentTimeMillis() - identityCaptureStartedAtMs
                if (identityElapsedMs >= IDENTITY_CAPTURE_TIMEOUT_MS ||
                    identityCaptureRetryCount > MAX_IDENTITY_CAPTURE_RETRIES
                ) {
                    identityCaptureTerminalSessionKey = identitySessionKey
                    onLog(
                        "AI 联系人身份采集终态：IDENTITY_CAPTURE_TIMEOUT；" +
                            "已停止继续 RETRY_LATER；重试=${identityCaptureRetryCount - 1}；" +
                            "耗时=${identityElapsedMs.coerceAtLeast(0L)}ms；仅当前窗口上下文"
                    )
                } else {
                val identityOutcome = runCatching {
                    chatSessionManagementPort.observeContactIdentityCapture(currentSessionCandidate!!)
                }.getOrElse { error ->
                    onLog(
                        "AI 联系人身份采集失败：IDENTITY_CAPTURE_INTERFACE_EXCEPTION；" +
                            "exception=${error.javaClass.simpleName}；message=${error.message ?: "NONE"}；" +
                            "身份未终态，继续等待，不启动模型"
                    )
                    // An adapter exception is not proof of terminal identity failure.
                    // Keep the conversation on the bounded identity transaction and let
                    // the existing timeout path decide when current-window fallback is allowed.
                    ContactIdentityCaptureOutcome.waiting(
                        ContactIdentityCaptureRequestResult.RETRY_LATER
                    )
                }
                if (identityOutcome != null) {
                    if (identityOutcome.verifiedIdentity != null) {
                        val verifiedIdentity = identityOutcome.verifiedIdentity
                        val candidate = currentSessionCandidate
                        val restoredSurfaceMatches = currentSessionBinding
                            ?.matchesRestoredChatSurface(snapshot) == true
                        val restoredPageKind = snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE
                        val restoredPackagePresent = snapshot.packageName.isNotBlank()
                        val restoredInputPresent = snapshot.inputFieldTarget != null
                        val restoredMessageListPresent = snapshot.messageListTarget != null
                        onLog(
                            "AI 会话恢复校验：" +
                                "pageKind=${snapshot.pageKind};" +
                                "packageNamePresent=$restoredPackagePresent;" +
                                "inputFieldPresent=$restoredInputPresent;" +
                                "messageListPresent=$restoredMessageListPresent;" +
                                "windowId=${snapshot.messageListTarget?.window?.windowId ?: snapshot.inputFieldTarget?.window?.windowId ?: "NONE"};" +
                                "windowGeneration=${snapshot.messageListTarget?.window?.generation ?: snapshot.inputFieldTarget?.window?.generation ?: "NONE"};" +
                                "restoredPageKind=$restoredPageKind"
                        )
                        val auxiliaryHeaderMatches = ChatSessionManagementPolicy.matchChatHeader(
                            currentSessionName,
                            snapshot.chatTitleText
                        ) != null
                        val sessionStillMatches = candidate != null &&
                            restoredSurfaceMatches &&
                            auxiliaryHeaderMatches &&
                            verifiedIdentity.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                            !verifiedIdentity.contactId.isNullOrBlank()
                        if (!sessionStillMatches) {
                            onLog(
                                "AI 联系人身份绑定暂缓：CURRENT_SESSION_BINDING_NOT_READY；" +
                                    "platformContactId已保留；" +
                                    "restoredChatSurface=$restoredSurfaceMatches；" +
                                    "辅助标题匹配=$auxiliaryHeaderMatches；等待原聊天页恢复后重试"
                            )
                            return
                        }
                        candidate!!.contactIdentity = verifiedIdentity
                        identityJustVerified = true
                        onLog(
                            "AI 联系人身份绑定完成：已写回当前私人会话候选；" +
                                "confidence=${verifiedIdentity.confidence.name}；" +
                                "身份已确认；继续加载对应联系人历史并提交模型"
                        )
                    } else if (identityOutcome.awaiting) {
                        val elapsed = System.currentTimeMillis() - identityCaptureStartedAtMs
                        if (elapsed < IDENTITY_CAPTURE_TIMEOUT_MS) {
                            onLog(
                                "AI 联系人身份采集中：等待资料页/身份回传；" +
                                    "stage=${identityOutcome.request.name}；" +
                                    "elapsedMs=${elapsed.coerceAtLeast(0L)}；继续等待，不加载历史"
                            )
                            return
                        }
                        identityCaptureTerminalSessionKey = identitySessionKey
                        onLog(
                            "AI 联系人身份采集终态：IDENTITY_CAPTURE_TIMEOUT；" +
                                "request=${identityOutcome.request.name}；" +
                                "降级当前窗口消息；不因身份失败阻断回复生成"
                        )
                    } else if (identityOutcome.failureReason != null) {
                        identityCaptureTerminalSessionKey = identitySessionKey
                        onLog("AI 联系人身份采集终态：${identityOutcome.failureReason}；" +
                            "降级当前窗口消息；不因身份失败阻断回复生成")
                    }
                }
                }
            }
        }
        if (privateDetail && identityJustVerified) {
            onLog("AI OCR 消息读取：身份已确认并返回原聊天页；丢弃旧批次，重新触发当前会话 OCR")
            readVisibleMessages(snapshot)
            return
        }
        // The coordinator now delivers a structural batch first. OCR is deliberately deferred
        // until identity is terminal and the adapter has confirmed the chat detail page is
        // restored. The OCR callback re-enters this method with an OCR-complete batch.
        if ((privateDetail || greetingDetail) && !batch.ocrAttempted && batch.ocrTargets.isNotEmpty()) {
            if (privateDetail) {
                val preOcrIdentity = runCatching {
                    resolveConversationIdentity(currentSessionCandidate?.contactIdentity, snapshot.chatTitleText)
                }.getOrNull()
                if (preOcrIdentity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                    !preOcrIdentity.contactId.isNullOrBlank()
                ) {
                    runCatching { prepareDraftHistory(preOcrIdentity, snapshot) }.onFailure {
                        onLog("AI 草稿历史预加载失败：CONTACT_HISTORY_PRELOAD_FAILED；" +
                            "exception=${it.javaClass.simpleName}；继续使用当前窗口消息")
                    }
                    onLog("AI 草稿上下文：身份已确认，历史加载完成后进入 OCR")
                } else {
                    onLog("AI 草稿上下文：身份未确认；跳过历史加载，进入仅当前窗口 OCR")
                }
            }
            val ocrSnapshot = snapshot
            val ocrVisit = draftVisitToken
            chatMessagePort.readRecentMessagesAsync(ocrSnapshot, limit = 10) { ocrBatch ->
                if (ocrVisit == draftVisitToken && isAiReplyActive()) {
                    handleMessageReadBatch(ocrBatch, ocrSnapshot)
                } else {
                    onLog("AI OCR 结果已丢弃：窗口任务已变化")
                }
            }
            onLog("AI OCR 启动：身份采集已终态且聊天页已恢复；开始处理结构批次中的 OCR 区域")
            return
        }
        val draftAdmission = com.freedom.features.aireply.ai.VisibleDraftContext.assess(batch)
        if (draftTargetConfirmed && draftAdmission.retryable) {
            // 使用新快照重新 OCR；不把重试成功等同于内容已验证。
            if (draftRetryTask != null) return
            if (draftReadRetries < 2) {
                draftReadRetries++
                val visit = draftVisitToken
                val run = sessionActionGeneration
                onLog("AI 消息复读：${draftAdmission.reason}；attempt=$draftReadRetries/2；保持当前页面")
                draftRetryTask = scheduler.schedule(700L * draftReadRetries) {
                    draftRetryTask = null
                    if (visit != draftVisitToken || run != sessionActionGeneration || !isAiReplyActive()) return@schedule
                    val fresh = runCatching { chatPagePort.inspectChatPage() }.getOrNull()
                    if (fresh == null || !hasSameWindowSession(snapshot, fresh)) {
                        onLog("AI 消息复读停止：窗口或会话已变化")
                        return@schedule
                    }
                    readVisibleMessages(fresh)
                }
                return
            }
            onLog("AI 消息复读停止：${draftAdmission.reason}；已达两次重试上限，不猜测内容")
        }

        val rowIdentity = if (greetingDetail) receivedGreetingsTaskFlow.currentContactIdentity()
            else currentSessionCandidate?.contactIdentity
        val identity = runCatching { resolveConversationIdentity(rowIdentity, snapshot.chatTitleText) }
            .getOrElse {
                onLog("AI 历史联系人解析失败：CONTACT_HISTORY_LOOKUP_FAILED；exception=${it.javaClass.simpleName}")
                rowIdentity
            }
        val draftHoldsPage = if (draftTargetConfirmed && draftAdmission.accepted) {
            runCatching { onDraftConversationObserved(batch, snapshot, identity, draftVisitToken) }.getOrElse {
                onLog("AI 草稿入口异常：[DRAFT_CALLBACK_FAILED]；未执行发送")
                false
            }
        } else {
            onLog("AI 草稿未准入：${if (!draftTargetConfirmed) "CHAT_ENTRY_NOT_BOUND" else draftAdmission.reason}")
            false
        } || isConversationDraftPending() || isDraftAwaitingUserSend()
        // Received-greeting details also support human-reviewed drafts. Do not expose their
        // Verified-history observers remain separate from this current-window draft path.
        if (sessionRunStarted && receivedGreetingsTaskActive &&
            receivedGreetingsTaskFlow.traversalStatus() == ReceivedGreetingsTraversalStatus.DETAIL_READY
        ) {
            logTemporaryOcrResults(batch)
            val sampleIndex = runCatching {
                chatSessionManagementPort.persistChatSample(
                    snapshot,
                    batch,
                    sessionName = receivedGreetingsTaskFlow.currentDisplayName(),
                    contactIdentity = identity
                )
            }.getOrDefault(0)
            onLog(
                "收到招呼只读 OCR 采集：样本=${if (sampleIndex > 0) sampleIndex else "未保存"}；" +
                    "读取状态=${batch.status.name}；消息行=${batch.messages.size}；" +
                    "OCR目标=${batch.ocrTargets.size}；已识别=${batch.ocrRecognizedTargetCount}；" +
                    "失败=${batch.ocrFailedTargetCount}；截断=${batch.truncated}；" +
                    "唯一身份=${if (receivedGreetingsTaskFlow.currentContactIdentity() != null) "已验证" else "未验证"}；" +
                    "草稿由独立入口处理；不自动发送"
            )
            if (draftHoldsPage) {
                onLog("收到招呼：保持聊天页面等待草稿审核及人工发送；不自动返回")
                return
            }
            val returned = runCatching {
                receivedGreetingsTaskFlow.completeDetailInspection(sampleIndex > 0)
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
            val contactIdentity = identity
            val sampleIndex = runCatching {
                chatSessionManagementPort.persistChatSample(
                    snapshot, batch, currentSessionName, contactIdentity
                )
            }.getOrDefault(0)
            // 当前行的绑定缺失时，已先通过昵称找回先前采集的平台联系人ID。
            // 用同一已解析身份保存消息并加载历史，不因本次行快照缺少ID而丢失上下文。
            val verifiedContactIdentity = contactIdentity?.takeIf {
                it.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                    !it.contactId.isNullOrBlank()
            }
            if (observerBatch.messages.isNotEmpty() && verifiedContactIdentity != null) {
                runCatching { onVerifiedConversationObserved(observerBatch, verifiedContactIdentity, snapshot) }
                    .onFailure {
                        onLog("AI 会话上下文回调异常；异常诊断不包含消息正文")
                    }
            } else if (observerBatch.messages.isNotEmpty()) {
                onLog("AI 会话历史未命中：当前ID及昵称均未能唯一匹配已采集的平台联系人；草稿仍使用本次可见消息")
            }
            privateMessagesTraversalTaskExecutor.setSessionReadCaptured(sampleIndex > 0)
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
        if (messageListRecoveryVerificationPending && sessionRunStarted && !receivedGreetingsTaskActive) {
            if (!pageRecoveryVerificationGate.observe(recoveryStructuralKey(snapshot))) {
                sessionManagerState = SessionManagerState.PAUSED
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
                onLog("消息列表恢复[RECOVERY_VERIFYING]：等待第二次独立稳定页面快照；保持暂停")
                scheduleSessionListStabilityRetry("消息列表页面指纹尚未连续稳定")
                return
            }
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeRequested)
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeCompleted)
            messageListRecoveryVerificationPending = false
            messageRecoveryRetryAttempt = 0
            pageRecoveryVerificationGate.reset()
            onLog("消息列表恢复[RECOVERY_VERIFIED]：连续两次独立页面快照一致；恢复原有遍历算法")
        }
        if (privateMessagesCheckpointRecoveryPending && sessionRunStarted && !receivedGreetingsTaskActive) {
            if (!privateMessagesCheckpointVerificationGate.observe(recoveryStructuralKey(snapshot))) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("消息列表进程恢复[RECOVERY_VERIFYING]：第${privateMessagesCheckpointVerificationGate.stableSamples}/2次结构快照尚未稳定；350ms后复核")
                scheduleFullInspectionAfter(350L)
                return
            }
            privateMessagesCheckpointRecoveryPending = false
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeRequested)
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeCompleted)
            messageRecoveryRetryAttempt = 0
            privateMessagesCheckpointVerificationGate.reset()
            sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP
            onLog("消息列表进程恢复[RECOVERY_VERIFIED]：连续两次结构快照一致；继续持久化检查点对应任务")
        }
        if (privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys != null) {
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
                SessionManagerState.COMPLETED,
                SessionManagerState.PAUSED
            )
        ) return
        if (!receivedGreetingsTaskActive && !ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            onLog("私人消息列表处理被阻止：当前任务未持有 UI 操作权")
            return
        }

        if (privateMessagesTraversalTaskExecutor.identityReturnPending) {
            val oldKey = privateMessagesTraversalTaskExecutor.identityReturnOldKey
            if (oldKey == null || chatSessionManagementPort.isSessionIdentityBlocked(oldKey)) {
                privateMessagesTraversalTaskExecutor.clearIdentityReturn()
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话身份复核暂停：原身份已被隔离；不打开会话")
                return
            }
            val result = privateMessagesTraversalTaskExecutor.reconcileIdentityReturn(snapshot, sessionActionGeneration)
            when (result.status) {
                PrivateMessagesTraversalTaskExecutor.IdentityReturnStatus.WAITING -> {
                    privateMessagesTraversalTaskExecutor.identityReturnRetries++
                    if (privateMessagesTraversalTaskExecutor.identityReturnRetries <= 3) {
                        onLog("AI 会话身份复核：等待原行平台身份确认；暂不滑动或点击其他行")
                        scheduleFullInspectionAfter(500L)
                    } else {
                        privateMessagesTraversalTaskExecutor.clearIdentityReturn()
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("AI 会话身份复核暂停：未能确认原行平台身份；保留现场")
                    }
                }
                PrivateMessagesTraversalTaskExecutor.IdentityReturnStatus.REJECTED -> {
                    privateMessagesTraversalTaskExecutor.clearIdentityReturn()
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("AI 会话身份复核暂停：返回页面、原行、未读或列表位置不一致；保留现场")
                }
                PrivateMessagesTraversalTaskExecutor.IdentityReturnStatus.RESTORED -> {
                    val candidate = result.candidate ?: return
                    if (chatSessionManagementPort.isSessionIdentityBlocked(ChatSessionManagementPolicy.stableSessionKey(candidate))) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("AI 会话身份复核暂停：已验证身份被隔离；不打开会话")
                        return
                    }
                    sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                    onLog("AI 会话身份复核：原行和未读身份已确认；使用新快照继续打开原会话")
                    attemptOpenSession(candidate)
                }
            }
            return
        }

        // The first list snapshot is a read-only current-position check. It must happen before
        // the positioning phase is allowed to submit any swipe gesture.
        if (!privateMessagesTraversalTaskExecutor.initialInboxPositionCheckCompleted && !receivedGreetingsTaskActive) {
            privateMessagesTraversalTaskExecutor.initialInboxPositionCheckCompleted = true
            // The initial snapshot only establishes the Message work area. It is not used for
            // unread admission: first confirm the list top, then complete one upward admission swipe.
            privateMessagesTraversalTaskExecutor.normalInboxHadUnread = false
            privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown = false
            sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP
            onLog(
                "AI Reply 启动检查：已确认消息工作区域；当前视口行=${snapshot.chatListCandidates.size}；" +
                    "先向下定位并确认列表顶部，再执行一次成功的向上准入滑动；未读判断只使用该向上手势后的新快照"
            )
        }
        // The pre-positioning and top-confirmation snapshots are not admission evidence.
        // Start accumulating unread state only after the successful first upward gate swipe.
        if (privateMessagesTraversalTaskExecutor.initialUnreadGateSwipeCompleted || receivedGreetingsTaskActive) {
            privateMessagesTraversalTaskExecutor.recordViewportUnreadEvidence(
                snapshot,
                receivedGreetingsActive = receivedGreetingsTaskActive
            )
        }

        var allowExpectedSessionReturnViewportChange = false
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
            // The current session may move or change unread state when it is opened/read.
            // That is an expected return mutation, not an unsolicited viewport movement.
            allowExpectedSessionReturnViewportChange = true
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

        val wasWaitingForUnread = sessionManagerState == SessionManagerState.WAITING_FOR_UNREAD
        val listEvaluation = privateMessagesTraversalTaskExecutor.evaluateListSnapshot(
            snapshot = snapshot,
            wasWaitingForUnread = wasWaitingForUnread,
            maxNoProgressScrolls = MAX_NO_PROGRESS_SCROLLS,
            allowExpectedSessionReturnViewportChange = allowExpectedSessionReturnViewportChange
        )
        privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
        val snapshotAmbiguousKeys = listEvaluation.snapshotAmbiguousKeys
        val newlyAmbiguousKeys = listEvaluation.newlyAmbiguousKeys
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

        // The traversal executor owns the snapshot-to-decision sequence. This runtime performs
        // only the side effects selected by that decision packet.
        if (listEvaluation.unreadAuditChanged) {
            onLog(
                "AI 会话管理：未读证据审计；" + formatUnreadAudit(snapshot, snapshotAmbiguousKeys) +
                    "；复查状态=${sessionManagerState.name}"
            )
        }

        val visibleKeys = listEvaluation.visibleKeys
        when (listEvaluation.scrollDecision) {
            PrivateMessagesScrollObservationDecision.NO_PENDING_SCROLL -> Unit
            PrivateMessagesScrollObservationDecision.TOP_VIEWPORT_CHANGED -> {
                // The swipe exposed a new viewport. Inspect this exact snapshot before issuing
                // another top-directed swipe, otherwise intermediate unread rows could be skipped.
                onLog("AI 会话管理：顶部方向滑动后可见会话行发生变化；先审视当前新区域")
            }
            PrivateMessagesScrollObservationDecision.TOP_NO_PROGRESS_RETRY -> {
                onLog("AI 会话管理：顶部定位后新快照未见位置变化；稳定确认计数=${privateMessagesTraversalTaskExecutor.noProgressScrollCount}")
                scrollSessionList(snapshot, toTop = true)
                return
            }
            PrivateMessagesScrollObservationDecision.TOP_BOUNDARY_CANDIDATE -> {
                beginBoundaryStabilityConfirmation(visibleKeys, toTop = true, snapshot = snapshot)
                return
            }
            PrivateMessagesScrollObservationDecision.BOTTOM_NO_PROGRESS -> {
                onLog("AI 会话管理：滚动后可见行顺序与身份集合未变化；底部定位无进展计数=${privateMessagesTraversalTaskExecutor.noProgressScrollCount}")
            }
            PrivateMessagesScrollObservationDecision.BOTTOM_BOUNDARY_CANDIDATE -> {
                onLog("AI 会话管理：滚动后可见行顺序与身份集合未变化；底部定位无进展计数=${privateMessagesTraversalTaskExecutor.noProgressScrollCount}")
                beginBoundaryStabilityConfirmation(visibleKeys, toTop = false, snapshot = snapshot)
                return
            }
            PrivateMessagesScrollObservationDecision.BOTTOM_VIEWPORT_CHANGED -> Unit
        }

        if (listEvaluation.viewportPositionInvalidated) {
            sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
            onLog(
                "AI 会话管理：未发出滚动手势时可见会话身份序列发生变化；当前列表位置不可信；" +
                    "以最新视口重新核对未读状态；保留本轮已处理身份，重新定位底部后继续审视"
            )
            // A snapshot taken after an external list movement must never be used to
            // select or open a row. Re-establish the bottom anchor first, then inspect
            // the fresh viewport under the normal admission rules.
            scrollSessionList(snapshot, toTop = false)
            return
        }

        if (listEvaluation.candidatesEmpty) {
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

        if (!privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted &&
            !receivedGreetingsTaskActive
        ) {
            sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP
            onLog("AI Reply 启动顶部定位：当前新快照尚未确认顶部边界；继续在消息列表内向下滑动")
            scrollSessionList(snapshot, toTop = true)
            return
        }

        listEvaluation.unreadAdmissionDecision?.let { admission ->
            when (admission) {
                PrivateMessagesUnreadAdmissionDecision.FIRST_SWIPE_REQUIRED -> {
                    // First successful upward swipe is a mandatory admission probe.
                    sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                    onLog("AI Reply 启动准入：执行进入消息工作区后的首次向上滑动；完成后才检查未读")
                    scrollSessionList(snapshot, toTop = false)
                    return
                }
                PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED -> {
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("AI Reply 启动准入[UNREAD_STATE_UNKNOWN]：首次成功滑动后的视口存在 UNKNOWN；暂停，不把未知当作无未读")
                    return
                }
                PrivateMessagesUnreadAdmissionDecision.NO_UNREAD -> {
                    onLog("AI Reply 启动准入[NO_UNREAD_AFTER_FIRST_SWIPE]：首次成功向上滑动后的视口没有明确未读；不再滑动到底部，直接切换收到的招呼任务")
                    startReceivedGreetingsTask("进入消息工作区后首次成功向上滑动，确认当前视口没有明确未读私人消息")
                    return
                }
                PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING -> {
                    sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                    onLog("AI Reply 启动准入[UNREAD_LOCKING]：已锁定明确未读身份=${privateMessagesTraversalTaskExecutor.lockedUnreadSessionKeys.size}；继续向上滑动收集其余未读行，定位列表底部")
                    scrollSessionList(snapshot, toTop = false)
                    return
                }
                PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_UNREAD_BOUNDARY_CONFIRMATION -> {
                    sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                    onLog("AI Reply 启动准入[UNREAD_BOUNDARY_CANDIDATE]：当前视口已与未读区分离；暂不继续向上滑动，等待稳定快照确认")
                    scheduleSessionListStabilityRetry("未读区域下方已读边界候选")
                    return
                }
                PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED -> {
                    sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                    onLog("AI Reply 启动准入[UNREAD_BOUNDARY_CONFIRMED]：已确认未读区下方连续已读区域；停止继续到底部，开始从最下面未读会话向上处理")
                    // Do not return: the current stable snapshot is already the first
                    // bottom-to-top processing snapshot. The next candidate is selected below.
                }
            }
        }

        val next = listEvaluation.nextCandidate
        val structuralFingerprint = listEvaluation.structuralFingerprint
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
            if (privateMessagesTraversalTaskExecutor.capturedSessionListFingerprints.add(structuralFingerprint)) {
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
                                "累计不同视口=${privateMessagesTraversalTaskExecutor.capturedSessionListFingerprints.size}；" +
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
            privateMessagesTraversalTaskExecutor.phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
            privateMessagesTraversalTaskExecutor.atTop = false
            privateMessagesTraversalTaskExecutor.noProgressScrollCount = 0
            privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
            privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
            privateMessagesTraversalTaskExecutor.lastStableSessionListVisibleKeys = null
            sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
            onLog("AI Reply 低频待机结束：当前页发现明确未读；先确认当前位置，再重新定位普通消息列表底部并由下往上处理")
            scrollSessionList(snapshot, toTop = false)
            return
        } else {
            sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
        }

        if (privateMessagesTraversalTaskExecutor.capturedSessionListFingerprints.add(structuralFingerprint)) {
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
                            "累计不同视口=${privateMessagesTraversalTaskExecutor.capturedSessionListFingerprints.size}；" +
                            "截图=${if (screenshotPath.isNullOrBlank()) "不可用" else "已保存"}"
                    )
                    attemptOpenSession(next)
                }
            }
        } else {
            attemptOpenSession(next)
        }
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
        privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys = visibleKeys
        privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationToTop = toTop
        privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount = 0
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
            privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys = null
            privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount = 0
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
            if (!isAiReplyActive() || !sessionRunStarted || privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys == null) {
                return@schedule
            }
            val fresh = inspectFreshPageSnapshot()
            if (fresh != null && fresh.pageKind == ChatPageKind.UNKNOWN &&
                fresh.packageName == targetPackage() &&
                fresh.windowIdentityKey() == expectedWindowIdentity
            ) {
                privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys = null
                privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount = 0
                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                onLog("AI 会话管理：延迟边界复核的页面树不完整；不判为非聊天页面，重新采集主列表与导航锚点")
                scheduleSessionListStabilityRetry("延迟边界复核页面树不完整")
                return@schedule
            }
            if (fresh == null || fresh.pageKind != ChatPageKind.CHAT_LIST ||
                fresh.packageName != targetPackage() ||
                fresh.windowIdentityKey() != expectedWindowIdentity
            ) {
                privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys = null
                privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount = 0
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话管理已暂停：延迟边界复核未能确认同一会话列表页面；不判定遍历完成")
                return@schedule
            }
            val currentKeys = privateMessagesTraversalTaskExecutor.visibleSessionKeys(fresh)
            if (currentKeys.isEmpty() || fresh.chatListExtractionTruncated) {
                privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys = null
                privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount = 0
                onLog("AI 会话管理：延迟边界复核快照不完整；不确认边界，转入列表稳定性重试")
                scheduleSessionListStabilityRetry("延迟边界复核快照不完整")
                return@schedule
            }
            if (fresh.nodeTreeProbeTruncated) {
                onLog("AI 会话管理：延迟复核的辅助节点树探针仍不完整；主列表提取和页面锚点一致，继续有限稳定确认")
            }
            val expectedKeys = privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys
            if (currentKeys != expectedKeys) {
                val wasTopCheck = privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationToTop
                privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys = null
                privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount = 0
                privateMessagesTraversalTaskExecutor.noProgressScrollCount = 0
                privateMessagesTraversalTaskExecutor.atTop = false
                sessionManagerState = if (wasTopCheck) {
                    SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                } else {
                    SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                }
                onLog("AI 会话管理：延迟边界复核发现列表发生迟到变化；撤销边界候选，使用新快照继续定位")
                handleSessionListPage(fresh)
                return@schedule
            }
            privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount++
            if (privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount < MAX_BOUNDARY_STABILITY_CONFIRMATIONS) {
                onLog(
                    "AI 会话管理：边界候选延迟快照一致；继续独立稳定复核=" +
                        "$privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount/$MAX_BOUNDARY_STABILITY_CONFIRMATIONS"
                )
                scheduleBoundaryStabilityConfirmation(expectedWindowIdentity)
                return@schedule
            }

            val confirmedTop = privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationToTop
            privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys = null
            privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount = 0
            privateMessagesTraversalTaskExecutor.noProgressScrollCount = 0
            if (confirmedTop) {
                privateMessagesTraversalTaskExecutor.atTop = true
                if (!privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted) {
                    privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted = true
                    sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                    onLog("AI Reply 启动顶部定位[CONFIRMED]：列表顶部已通过连续无位移及延迟独立快照复核；下一步执行首次向上准入滑动")
                } else {
                    sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                    onLog("AI 会话管理：顶部边界经延迟独立快照复核通过；检查顶部当前可见会话行")
                }
            } else {
                privateMessagesTraversalTaskExecutor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
                privateMessagesTraversalTaskExecutor.atTop = false
                privateMessagesTraversalTaskExecutor.lockedUnreadSetFinalized = true
                sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                onLog("AI 会话管理：底部边界经延迟独立快照复核通过；未读身份集合已冻结；进入从底部向顶部的处理阶段")
            }
            handleSessionListPage(fresh)
        }
    }

    private fun continueSessionListWithoutCandidate(snapshot: ChatPageSnapshot) {
        if (privateMessagesTraversalTaskExecutor.phase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM) {
            if (privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown) {
                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                onLog("AI Reply 启动准入[POSITIONING_BLOCKED_BY_UNKNOWN]：未读状态无法确认；不执行底部定位滑动，等待新页面证据")
            } else {
                scrollSessionList(snapshot, toTop = false)
            }
            return
        }
        if (privateMessagesTraversalTaskExecutor.atTop) {
            privateMessagesTraversalTaskExecutor.noProgressScrollCount = 0
            privateMessagesTraversalTaskExecutor.sessionListStabilityRetryCount = 0
            when {
                privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown -> {
                    sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                    onLog("AI Reply 测试遍历暂停：完整扫描中存在未读状态 UNKNOWN 的会话行，不能将未知当作无未读")
                }
                else -> startReceivedGreetingsTask(
                    if (privateMessagesTraversalTaskExecutor.normalInboxHadUnread) "普通聊天列表已完成由下至上扫描，当前没有剩余符合条件的未读会话"
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
                privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown = true
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI Reply 低频待机复查暂停：当前列表存在 UNKNOWN 未读状态，不能按无消息处理")
            } else if (unread > 0) {
                privateMessagesTraversalTaskExecutor.normalInboxHadUnread = true
                privateMessagesTraversalTaskExecutor.attemptedSessionKeys.clear()
                privateMessagesTraversalTaskExecutor.noProgressScrollCount = 0
                privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
                privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
                privateMessagesTraversalTaskExecutor.lastStableSessionListVisibleKeys = null
                privateMessagesTraversalTaskExecutor.sessionListStabilityRetryCount = 0
                privateMessagesTraversalTaskExecutor.phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM
                privateMessagesTraversalTaskExecutor.atTop = false
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
        val noUnreadFastPath = privateMessagesTraversalTaskExecutor.initialUnreadGateSwipeCompleted && privateMessagesTraversalTaskExecutor.lockedUnreadSessionKeys.isEmpty() &&
            !privateMessagesTraversalTaskExecutor.normalInboxHadUnread && !privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown
        val quotaReached = privateMessagesTaskController.quotaReached()
        if ((!noUnreadFastPath && !quotaReached &&
                (privateMessagesTraversalTaskExecutor.phase != SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP || !privateMessagesTraversalTaskExecutor.atTop)) ||
            privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown
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
        val ordinarySessionClickCount = privateMessagesTraversalTaskExecutor.sessionActionCount
        val workflowSupported = receivedGreetingsTaskFlow.beginNavigationTask()
        currentSessionKey = null
        currentSessionWindowKey = null
        currentSessionName = null
        currentSessionCandidate = null
        messageReadCoordinator.invalidatePending()
        privateMessagesTraversalTaskExecutor.sessionActionCount = 0
        onLog(
            "AI Reply：切换收到的招呼只读 OCR 任务；原因=$reason；" +
                "私人聊天成功归档数=$normalCompletedObservationCount/$normalInboxTaskTargetCount；" +
                "普通聊天会话点击次数=$ordinarySessionClickCount；头像身份与详情 OCR 不触发回复"
        )
        if (!workflowSupported) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI Reply 收到的招呼支线暂停：当前社交适配器未声明支持该入口/页面识别能力")
            return
        }
        val accepted = receivedGreetingsTaskFlow.performInitialIdleStep()
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
        if (!claimTaskOwnership(RECEIVED_GREETINGS_TASK_ID, "接管人工打开的收到招呼列表")) {
            onLog("收到的招呼人工任务启动[OWNERSHIP_REJECTED]：未取得唯一 UI 操作权；不接管当前页面")
            return
        }
        sessionActionGeneration += 1L
        receivedGreetingsPageProbePending = false

        val accepted = receivedGreetingsTaskFlow.beginFromConfirmedList()
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
        messageReadCoordinator.invalidatePending()

        receivedGreetingsTaskFlow.markStarted()
        privateMessagesTraversalTaskExecutor.sessionActionCount = 0

        startupReadinessCoordinator.markReceivedGreetingsWorkAreaConfirmed()

        onLog("收到的招呼任务[STARTED]：页面结构已确认当前列表；任务租约已取得；不重复导航进入列表")
        val firstStepAccepted = receivedGreetingsTaskFlow.performInitialIdleStep()
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
        receivedGreetingsTaskFlow.markCompletedAwaitingUserExit()
        sessionActionGeneration += 1L
        releaseTaskOwnership("收到的招呼任务完成；保持当前页面等待用户离开")
        sessionManagerState = SessionManagerState.PAUSED
        onLog("收到的招呼任务[COMPLETE_STAY_ON_PAGE]：列表遍历已完成；保持当前页面；待页面结构重新确认普通消息列表后再申请私人消息任务租约")
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
                ChatSessionManagementPolicy.stableSessionKey(it) in privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys
        }
        val attemptedUnread = eligibleUnread.count {
            ChatSessionManagementPolicy.stableSessionKey(it) in privateMessagesTraversalTaskExecutor.attemptedSessionKeys
        }
        val actionableUnread = eligibleUnread.count {
            val key = ChatSessionManagementPolicy.stableSessionKey(it)
            key !in snapshotAmbiguousKeys && key !in privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys &&
                key !in privateMessagesTraversalTaskExecutor.attemptedSessionKeys
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
        messageRecoveryRetryAttempt += 1
        val attempt = messageRecoveryRetryAttempt
        val delayMs = MESSAGE_LIST_RECOVERY_RETRY_DELAYS_MS[
            (attempt - 1).coerceAtMost(MESSAGE_LIST_RECOVERY_RETRY_DELAYS_MS.lastIndex)
        ]
        recoveryTransaction.dispatch(RecoveryTransactionEvent.RetryScheduled(delayMs))
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
        onLog(
            "AI 会话管理：$reason；第 $attempt 次恢复复核，${delayMs}ms 后再次检查；" +
                "达到最长间隔后继续低频检查，不因次数终止，不执行空坐标操作"
        )
        sessionTransitionTask = scheduler.schedule(delayMs) {
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
        if (!isAiReplyActive() || !sessionRunStarted || !ownsTask(PRIVATE_MESSAGES_TASK_ID)) return

        if (candidate.contactIdentity?.confidence != ContactIdentityConfidence.PLATFORM_ID_VERIFIED) {
            onLog("AI 会话身份：未确认平台 ID；使用本次访问临时上下文，不前置跳转资料采集；仍复核同名歧义、目标行及聊天标题")
        }

        val actionGeneration = sessionActionGeneration
        val attempt = privateMessagesTaskExecutor.openSession(
            candidate = candidate,
            blockedIdentityKeys = privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys
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
                                privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys += attempt.selectedKey
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
                        privateMessagesTraversalTaskExecutor.attemptedSessionKeys.remove(attempt.selectedKey)
                        currentSessionKey = null
                        currentSessionName = null
                        currentSessionCandidate = null
                        currentSessionReadCaptured = false
                        currentSessionWindowKey = null
                        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                        onLog("AI 会话管理[DEFERRED_NOT_READY]：启动保护期间不打开会话、不消耗尝试次数；保留目标意图并等待全新列表快照")
                        scheduleAiReadinessRetry("会话行点击等待目标页面就绪") {
                            if (actionGeneration == sessionActionGeneration && sessionRunStarted) {
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
                        // Store the exact just-revalidated row as one executor-owned session context.
                        privateMessagesTraversalTaskExecutor.beginSession(
                            candidate = attempt.candidate,
                            sessionKey = attempt.selectedKey,
                            windowKey = null
                        )
                        sessionManagerState = SessionManagerState.OPENING_SESSION
                        privateMessagesTraversalTaskExecutor.attemptedSessionKeys += attempt.selectedKey
                        privateMessagesTraversalTaskExecutor.sessionActionCount += 1
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
            privateMessagesTraversalTaskExecutor.attemptedSessionKeys.remove(selectedKey)
            privateMessagesTraversalTaskExecutor.sessionActionCount = (privateMessagesTraversalTaskExecutor.sessionActionCount - 1).coerceAtLeast(0)
            currentSessionKey = null
            currentSessionName = null
            currentSessionCandidate = null
            currentSessionReadCaptured = false
            currentSessionWindowKey = null
            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
            onLog("AI 会话管理[DEFERRED]：会话点击在延迟派发阶段遇到启动保护；不消耗尝试次数，使用新列表快照重新定位目标")
            scheduleAiReadinessRetry("会话行点击等待目标页面就绪") { latest ->
                if (actionGeneration == sessionActionGeneration && sessionRunStarted) {
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
            current.packageName == targetPackage()
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

    private fun scheduleReturnToSessionList(
        snapshot: ChatPageSnapshot,
        draftPendingWaitedMs: Long = 0L
    ) {
        if (sessionManagerState != SessionManagerState.READING_SESSION) return
        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话返回被阻止：私人消息任务未持有 UI 操作权")
            return
        }
        val awaitingUserSend = runCatching { isDraftAwaitingUserSend() }.getOrDefault(true)
        if (awaitingUserSend) {
            sessionTransitionTask?.cancel()
            sessionTransitionTask = null
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 回复草稿已填入并校验：保留当前聊天页面，等待用户检查内容并手动点击发送；未提交发送动作")
            return
        }
        val draftPending = runCatching { isConversationDraftPending() }.getOrDefault(true)
        if (draftPending) {
            if (draftPendingWaitedMs >= MAX_DRAFT_PENDING_WAIT_MS) {
                sessionTransitionTask?.cancel()
                sessionTransitionTask = null
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话管理已暂停：模型请求/草稿校验超过等待上限；保持当前聊天页面，不执行返回动作")
                return
            }
            sessionTransitionTask?.cancel()
            sessionTransitionTask = scheduler.schedule(DRAFT_PENDING_POLL_DELAY_MS) {
                sessionTransitionTask = null
                if (!isAiReplyActive() || !sessionRunStarted ||
                    sessionManagerState != SessionManagerState.READING_SESSION
                ) return@schedule
                scheduleReturnToSessionList(
                    snapshot,
                    draftPendingWaitedMs + DRAFT_PENDING_POLL_DELAY_MS
                )
            }
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
        if (!isAiReplyActive() || !sessionRunStarted || !ownsTask(PRIVATE_MESSAGES_TASK_ID)) return
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
            privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
            privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
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
            privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
            privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
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
            description = when {
                toTop && !privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted ->
                    "AI Reply 启动顶部定位：在消息列表视口内向下滑动并确认顶部边界"
                toTop ->
                    "AI 会话管理：从底部向上审视时，在列表视口内向下滑动到上一个区域"
                !privateMessagesTraversalTaskExecutor.initialUnreadGateSwipeCompleted ->
                    "AI Reply 启动准入：首次向上滑动检查新区域；启动定位底部"
                else ->
                    "AI 会话管理：启动定位底部时，在列表视口内向上滑动到下一个区域"
            },
            executionContext = executionContext
        )
        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 会话滚动[OWNERSHIP_REJECTED]：构造手势后任务租约已变化；拒绝派发旧滑动")
            return
        }
        val freshAmbiguousIdentityKeys = privateMessagesTraversalTaskExecutor.ambiguousIdentityKeys(
            fresh.chatListCandidates
        )
        val newlyAmbiguousFreshKeys = freshAmbiguousIdentityKeys - privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys
        if (newlyAmbiguousFreshKeys.isNotEmpty()) {
            privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys += newlyAmbiguousFreshKeys
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
        privateMessagesTraversalTaskExecutor.pendingScrollBaseline = privateMessagesTraversalTaskExecutor.visibleSessionKeys(fresh)
        privateMessagesTraversalTaskExecutor.pendingScrollToTop = toTop
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
                            val wasInitialAdmissionSwipe = !toTop && !privateMessagesTraversalTaskExecutor.initialUnreadGateSwipeCompleted
                            if (wasInitialAdmissionSwipe) {
                                privateMessagesTraversalTaskExecutor.initialUnreadGateSwipeCompleted = true
                                // This first gesture is an admission probe, not bottom-boundary
                                // positioning. Its post-gesture snapshot must be inspected even
                                // when the visible rows did not move, so discard boundary tracking.
                                privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
                                privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
                                privateMessagesTraversalTaskExecutor.noProgressScrollCount = 0
                                privateMessagesTraversalTaskExecutor.atTop = false
                                onLog("AI Reply 启动准入：顶部定位完成后的首次向上滑动已由 Android 确认成功；将用滑动后的新快照判定未读")
                            }
                            privateMessagesTraversalTaskExecutor.cancelledScrollRecoveryCount = 0
                            readinessDeferredRetryCount = 0
                            readinessDeferredRetryLimitLogged = false
                            startupReadinessCoordinator.markInitializationComplete("首个自主列表滑动已由 Android 完成")
                            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                            onLog(
                                when {
                                    wasInitialAdmissionSwipe -> "AI 会话管理：首次向上准入滑动已完成；等待滑动后的新快照判定未读"
                                    toTop && !privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted -> "AI Reply 启动顶部定位：已完成一次向下滑动；等待新快照确认顶部位置"
                                    toTop -> "AI 会话管理：已完成一次向上审视方向的列表滑动；等待新快照位置复核"
                                    else -> "AI 会话管理：已完成一次底部定位方向的列表滑动；等待新快照确认位置"
                                }
                            )
                            scheduleFullInspection()
                        }
                        GestureExecutionStatus.CANCELLED -> {
                            // A cancelled gesture is not proof of reaching either list boundary.
                            // Discard the old baseline and reacquire the page before any retry.
                            privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
                            privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
                            privateMessagesTraversalTaskExecutor.cancelledScrollRecoveryCount += 1
                            onLog(
                                "AI 会话管理：列表滚动被系统取消；第 $privateMessagesTraversalTaskExecutor.cancelledScrollRecoveryCount/" +
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
                                } else if (privateMessagesTraversalTaskExecutor.cancelledScrollRecoveryCount <= MAX_CANCELLED_SCROLL_RECOVERIES) {
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
                            privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
                            privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
                            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                            onLog("AI 会话管理[DEFERRED]：列表滑动在实际派发前遇到启动保护；结束旧轨迹，等待新快照后重算")
                            scheduleAiReadinessRetry("列表滚动等待目标页面就绪") { latest ->
                                if (actionGeneration == sessionActionGeneration && sessionRunStarted) {
                                    scrollSessionList(latest, toTop)
                                }
                            }
                        }
                        GestureExecutionStatus.REJECTED,
                        GestureExecutionStatus.FAILED -> {
                            privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
                            privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
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
                privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
                privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                onLog("AI 会话管理[DEFERRED_NOT_READY]：列表滚动未派发；保留滚动方向，等待静默结束并用新视口重算轨迹")
                scheduleAiReadinessRetry("列表滚动等待目标页面就绪") { latest ->
                    if (actionGeneration == sessionActionGeneration && sessionRunStarted) {
                        scrollSessionList(latest, toTop)
                    }
                }
            }
            GestureSubmission.REJECTED -> {
                if (activeSessionGestureRequestId == request.id) activeSessionGestureRequestId = null
                privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
                privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
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
        if (!isAiReplyActive() || !sessionRunStarted) return
        if (sessionSafeViewportRetryTask != null) return

        privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
        privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
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
                !isAiReplyActive() || !sessionRunStarted ||
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
     * The delay grows to a low-frequency ceiling and remains there while the task is active; the
     * recovery transaction has no retry-count termination.
     */
    private fun scheduleReceivedGreetingsRecoveryRetry(reason: String) {
        if (!sessionRunStarted || !receivedGreetingsTaskActive) return
        val retryPlan = receivedGreetingsTaskFlow.nextRecoveryRetryPlan()
        val retryCount = retryPlan.retryCount
        val delayMs = retryPlan.delayMs
        onLog(
            "收到的招呼自动恢复[RETRY]：$reason；第${retryCount}次新鲜快照复核；" +
                "delay=${delayMs}ms；任务和当前用户进度保持不变；" +
                "不因重试次数终止恢复"
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

    private var draftVisitToken = java.util.UUID.randomUUID().toString()
    private var draftReadRetries = 0
    private var draftRetryTask: ScheduledTask? = null

    private fun invalidatePendingMessageRead() {
        draftVisitToken = java.util.UUID.randomUUID().toString()
        draftReadRetries = 0
        draftRetryTask?.cancel()
        draftRetryTask = null
        messageReadCoordinator.invalidatePending()
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

    /** Stable chat-surface binding key; deliberately excludes the display name/title. */
    private fun chatSurfaceKey(snapshot: ChatPageSnapshot): String? {
        val messageTarget = snapshot.messageListTarget ?: return null
        val inputTarget = snapshot.inputFieldTarget ?: return null
        val window = messageTarget.window
        val inputWindow = inputTarget.window
        if (window.packageName != inputWindow.packageName ||
            window.windowId != inputWindow.windowId ||
            window.generation != inputWindow.generation
        ) return null
        return listOf(
            window.packageName,
            window.windowId,
            window.generation,
            messageTarget.path.childIndices.joinToString("."),
            inputTarget.path.childIndices.joinToString(".")
        ).joinToString("|")
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
