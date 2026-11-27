package com.freedom.features.aireply

import com.freedom.gesture.api.GestureApi
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.event.AccessibilityEventPolicy
import com.freedom.core.accessibility.events.AccessibilityUiSignal
import com.freedom.core.accessibility.events.SelfNavigationTransition
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.TaskLease
import com.freedom.core.runtime.TargetAppReadinessSnapshot
import com.freedom.core.runtime.PageObservationCoordinator
import com.freedom.core.runtime.PageFingerprint
import com.freedom.core.runtime.PageRecoveryRouteAdapter
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.features.aireply.ai.VisibleDraftContext
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import com.freedom.features.aireply.domain.contact.ContactMessageLedger
import com.freedom.features.aireply.domain.contact.ContactMessageLedgerRepository
import com.freedom.features.aireply.domain.contact.InMemoryContactMessageLedgerRepository
import com.freedom.features.aireply.domain.contact.ContactSendCountUpdate
import com.freedom.features.aireply.domain.contact.ContactSendRecordStatus
import com.freedom.features.aireply.domain.contact.ContactFollowUpCheckpoint
import com.freedom.features.aireply.domain.contact.ContactFollowUpStep
import com.freedom.features.aireply.domain.contact.ContactPendingRecoveryAction
import com.freedom.features.aireply.ports.ChatDraftInputTarget
import com.freedom.features.aireply.ports.PrivateChatFollowUpProgress
import com.freedom.features.aireply.ports.ChatMessagePort
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.ReceivedGreetingsBackResult
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ChatSessionManagementPort
import com.freedom.features.aireply.ports.ContactIdentityCaptureRequestResult
import com.freedom.features.aireply.ports.ContactIdentityCaptureOutcome
import com.freedom.features.aireply.ports.ObservedPrivateChatIdentityTarget
import com.freedom.core.accessibility.events.InputOrigin
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpoint
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpointRepository
import com.freedom.features.aireply.ports.UnfinishedPrivateSessionDisposition
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository
import com.freedom.features.recovery.RecoveryCheckpointRestoreSource
import com.freedom.features.recovery.RecoveryCheckpointVerificationResult
import com.freedom.features.recovery.RecoveryTransactionEvent
import com.freedom.features.recovery.RecoveryTransactionState
import com.freedom.features.recovery.UnifiedRecoveryTransactionStateMachine
import com.freedom.features.recovery.RecoveryContext
import com.freedom.features.recovery.RecoveryEventDispatcher
import com.freedom.features.recovery.UnifiedRecoveryTransactionManager
import com.freedom.features.recovery.RecoveryEventIdentity
import com.freedom.features.recovery.RecoveryScheduler
import com.freedom.features.recovery.RecoveryScheduleRepository
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
    /** Must be freshly evaluated against the active Android window; never a cached permission. */
    private val inspectAndroidRecoveryReadiness: (ChatPageSnapshot) -> TargetAppReadinessSnapshot,
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
    private val sharedPageObservationCoordinator: PageObservationCoordinator,
    /** Prevents leaving the chat while a bounded model request/draft verification is in flight. */
    private val isConversationDraftPending: () -> Boolean = { false },
    /** Read-only proof that an already submitted send still needs confirmation. */
    private val isSendConfirmationPending: () -> Boolean = { false },
    /** Existing draft coordinator owns the confirmed segment and its exact observation ID. */
    private val nextSegmentConfirmedObservationId: () -> String? = { null },
    /** Releases only a greeting chat hold after a user-returned list; durable proof remains. */
    private val deferGreetingSendForManualReturn: () -> Boolean = { false },
    /** Releases only a private-chat send hold after a user-returned inbox; durable proof remains. */
    private val deferPrivateSendForManualReturn: () -> Boolean = { false },
    /** A missing send button after verified draft input is a recoverable wait, not a deadline failure. */
    private val isSendControlRecoveryPending: () -> Boolean = { false },
    /** Current-window draft callback; true reserves the page for generation/review. */
    private val resolveConversationIdentity: (ContactRowIdentity?, String?) -> ContactRowIdentity? = { identity, _ -> identity },
    /** Loads verified draft history after identity restoration and before OCR starts. */
    private val prepareDraftHistory: (ContactRowIdentity?, ChatPageSnapshot) -> Unit = { _, _ -> },
    private val onDraftConversationObserved: (ChatMessageBatch, ChatPageSnapshot, ContactRowIdentity?, String) -> Boolean = { _, _, _, _ -> false },
    /** Received greetings never consults stored chat history; its verified current-window batch owns the draft. */
    private val onGreetingDraftConversationObserved: (ChatMessageBatch, ChatPageSnapshot, ContactRowIdentity?, String) -> Boolean = onDraftConversationObserved,
    /**
     * Single business result for one or more message-level send segments. The draft
     * coordinator alone manages per-segment confirmation and only its final completion
     * may provide a send receipt; this runtime owns the post-send return transaction.
     */
    private val onSendTransaction: (ChatMessageBatch, ChatPageSnapshot) ->
        com.freedom.features.aireply.ai.ReplySendTransactionOutcome = { _, _ ->
            com.freedom.features.aireply.ai.ReplySendTransactionOutcome(
                com.freedom.features.aireply.ai.SendConfirmationResult.NOT_PENDING
            )
        },
    /** A model-only failed attempt can defer only with new page, lease and empty composer proof. */
    private val verifyGreetingPreSendModelFailure: (
        com.freedom.features.aireply.ai.AiReplyPreSendModelFailure, ChatPageSnapshot
    ) -> Boolean = { _, _ -> false },
    /** Private pre-send rearm requires the durable FAILED_RETRYABLE fact and a fresh empty composer. */
    private val verifyPrivatePreSendModelFailure: (
        com.freedom.features.aireply.ai.AiReplyPreSendModelFailure, ChatPageSnapshot
    ) -> Boolean = { _, _ -> false },
    /** Releases only the failed greeting request after the adapter accepted an unprocessed return. */
    private val onGreetingModelFailureDeferred: (
        com.freedom.features.aireply.ai.AiReplyPreSendModelFailure
    ) -> Unit = {},
    private val draftStartupFailure: () -> String? = { null },
    /** Invalidates late model callbacks when this runtime is reset. */
    private val onDraftRuntimeReset: () -> Unit = {},
    /** User configuration is read at confirmed-send time; zero disables the matching action. */
    private val messageInterceptionThreshold: () -> Int = { 0 },
    private val imageSendThreshold: () -> Int = { 0 },
    private val contactMessageLedgerRepository: ContactMessageLedgerRepository = InMemoryContactMessageLedgerRepository(),
    private val privateMessagesCheckpointRepository: PrivateMessagesTraversalCheckpointRepository? = null,
    /** Received-greetings business progress stays in its existing adapter/Data repository. */
    private val receivedGreetingsCheckpointRepository: MomoSayHiTraversalCheckpointRepository? = null,
    /** Only read-only observation plans are persisted; checkpoints and UI leases stay separate. */
    private val privateMessagesRecoveryScheduleRepository: RecoveryScheduleRepository? = null,
    private val receivedGreetingsRecoveryScheduleRepository: RecoveryScheduleRepository? = null,
    /** Injected from the process composition root; null only in isolated test fixtures. */
    private val recoveryTransactionManager: UnifiedRecoveryTransactionManager? = null,
    /** Reports the exact verified capability; the route coordinator never acquires it for us. */
    private val onPageTaskOwnershipConfirmed: (com.freedom.core.runtime.TaskLease, Boolean) -> Boolean = { _, _ -> true },
    /** Service composition selects the startup task before any durable checkpoint is activated. */
    private val pageSelectedStartup: Boolean = false,
    /** Report a synchronous failed business admission after releasing its exact lease. */
    private val onPageTaskAdmissionRolledBack: (com.freedom.core.runtime.TaskLease) -> Unit = {},
    /** Read-only hint from the existing Android foreground clock, not a write permit. */
    private val foregroundQuietRemainingMs: () -> Long = { 0L },
    private val privateNoReplyCompleted: (String, String) -> Boolean = { _, _ -> false },
    private val onRecoveredPrivateSendTransaction: (ChatMessageBatch, ChatPageSnapshot, Long) ->
        com.freedom.features.aireply.ai.ReplySendTransactionOutcome = { batch, snapshot, _ ->
            onSendTransaction(batch, snapshot)
        },
    /** Production checks the existing durable segment journal before admitting a detail-origin visit. */
    private val observedPrivateJournalAdmitted: (ChatMessageBatch, ChatPageSnapshot, Long, String?) -> Boolean =
        { _, _, _, _ -> false },
    private val privateReplyRequestKey: (ChatMessageBatch, ChatPageSnapshot) -> String? = { _, _ -> null },
    private val restoreObservedPrivatePendingDraft: (ChatMessageBatch, ChatPageSnapshot, String, String) -> Boolean =
        { _, _, _, _ -> false },
    private val privateChatFollowUpPort: com.freedom.features.aireply.ports.PrivateChatFollowUpPort? = null,
    private val isPrivateFollowUpTouchActive: () -> Boolean = { false },
    private val privateImageResourceCount: () -> Int = { 0 }
) {
    // Ephemeral binding for the existing adapter execution; the ledger remains the durable owner.
    private class PrivateFollowUp(
        val contactKey: String,
        val sendConfirmationId: String?,
        val target: ChatDraftInputTarget,
        @Volatile var checkpoint: ContactFollowUpCheckpoint
    ) {
        var observationCount = 0L
        var unconfirmedReported = false
    }
    @Volatile private var activePrivateFollowUp: PrivateFollowUp? = null
    fun isPrivateFollowUpActive(): Boolean = activePrivateFollowUp != null

    /** The unified entry has independently recognized a top-level destination. */
    fun onPrivateFollowUpTopLevelObserved() {
        if (activePrivateFollowUp == null) return
        privateChatFollowUpPort?.reset("PRIVATE_FOLLOW_UP_LEFT_CHILD")
        activePrivateFollowUp = null
        currentSessionSendConfirmed = false
        if (!privateQuotaReturnReady) pendingPrivateQuotaReceipt = null
        onLog("PRIVATE_FOLLOW_UP LEFT_CHILD;checkpointRetained=true")
    }

    companion object {
        /**
         * The persisted business checkpoint identifies the observation schedule, not the
         * UNKNOWN cause or a process-local run generation. Never reuse a durable plan
         * without a persisted checkpoint with a valid saved-at timestamp.
         */
        internal fun durableRecoveryCheckpointId(
            taskId: String,
            savedAtEpochMs: Long?,
            fallbackReason: String
        ): String = if (savedAtEpochMs != null && savedAtEpochMs > 0L) {
            "$taskId:CHECKPOINT:$savedAtEpochMs"
        } else {
            "$taskId:$fallbackReason"
        }

        private const val MAX_VISIBLE_MESSAGES = 20
        private const val MAX_LOGGED_STRUCTURE_EVIDENCE = 256
        private const val MAX_STRUCTURE_LOG_LINE_CHARS = 3_500
        private const val MESSAGE_LIST_RETRY_DELAY_MS = 120L
        private const val MAX_MESSAGE_LIST_RETRIES = 2
        private const val EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS = 10_000L
        private const val RECEIVED_GREETINGS_USER_QUIET_MIN_MS = 2_000
        private const val RECEIVED_GREETINGS_USER_QUIET_MAX_MS = 5_000
        private const val RECEIVED_GREETINGS_USER_ACTIVE_RECHECK_DELAY_MS = 10_000L
        private const val MAX_OCR_DEBUG_TEXT_CHARS = 4_200
        private const val MAX_OCR_DEBUG_BATCHES_PER_WINDOW = 24
        private const val SESSION_OPEN_VERIFY_DELAY_MS = 2_200L
        private const val SESSION_RETURN_DELAY_MS = 1_500L
        private const val SESSION_RETURN_VERIFY_DELAY_MS = 900L
        private const val CONFIRMED_PRIVATE_RETURN_DETAIL_GATE = "AI_REPLY_CONFIRMED_PRIVATE_RETURN_DETAIL"
        private const val DRAFT_PENDING_POLL_DELAY_MS = 500L
        private const val SEND_CONFIRMATION_RECHECK_DELAY_MS = 500L
        private const val GREETING_UNKNOWN_SEND_READ_ONLY_RECHECK_DELAY_MS = 5_000L
        private const val GREETING_MODEL_FAILURE_SAFE_RECHECK_DELAY_MS = 5_000L
        private const val MAX_DRAFT_PENDING_WAIT_MS = 185_000L
        private const val IDENTITY_CAPTURE_TIMEOUT_MS = 15_000L
        private const val MAX_IDENTITY_CAPTURE_RETRIES = 30
        private const val MAX_NO_PROGRESS_SCROLLS = 2
        private const val SESSION_BOUNDARY_SETTLE_DELAY_MS = 650L
        private const val MAX_BOUNDARY_STABILITY_CONFIRMATIONS = 2
        private const val SCROLL_CANCEL_RECOVERY_DELAY_MS = 180L
        private const val AI_READINESS_RETRY_DELAY_MS = 400L
        private const val AI_READINESS_RETRY_BACKOFF_MS = 5_000L
        private const val MAX_AI_READINESS_DEFERRED_RETRIES = 75
        private const val STARTUP_MESSAGE_TAB_RETRY_DELAY_MS = 400L
        private const val MAX_STARTUP_MESSAGE_TAB_RETRIES = 75
        private const val MAX_STARTUP_MESSAGE_TAB_TAPS = 1
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
        private const val SNAPSHOT_RECOVERY_ROUTE_ID = "AI_REPLY_SNAPSHOT_RECOVERY"
        private const val RECEIVED_GREETINGS_RECOVERY_ROUTE_ID = "AI_REPLY_RECEIVED_GREETINGS_RECOVERY"
        private const val MESSAGE_LIST_RECOVERY_ROUTE_ID = "AI_REPLY_MESSAGE_LIST_RECOVERY"
        private const val CROSS_TASK_GREETING_HANDOFF_GATE = "AI_REPLY_PRIVATE_TO_GREETINGS_HANDOFF"
        private const val GREETINGS_RETURN_RECOVERY_REASON = "GREETING_COMPLETION_RETURN"
        private const val GREETINGS_TO_PRIVATE_HANDOFF_GATE = "AI_REPLY_GREETINGS_TO_PRIVATE_HANDOFF"
        private const val NEXT_PAIR_WAKE_GATE = "AI_REPLY_NEXT_PAIR_WAKE"
        private const val PRIVATE_STARTUP_DETAIL_OWNERSHIP_GATE = "AI_REPLY_PRIVATE_STARTUP_DETAIL_OWNERSHIP"
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
        ownership = taskOwnership
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
            // A reserved task lease cannot authorize a delayed tap while page recovery
            // is still waiting for its verified resume.
            ownsTask = { canPerformPrivateMessagesUiAction() }
        )
    }
    private val receivedGreetingsTaskActive: Boolean
        get() = receivedGreetingsTaskFlow.isActive
    private val awaitingNormalInboxAfterGreetingsReturn: Boolean
        get() = receivedGreetingsTaskFlow.awaitingNormalInboxAfterReturn

    private var pageInspectionTask: ScheduledTask? = null
    private var fullInspectionCallbackToken = 0L
    /** Purpose carried with the next scheduled full observation. */
    private var pendingPageReadPurpose = AiReplyPageReadPurpose.FULL_INSPECTION
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

    /**
     * Centralizes the adapter-specific arrival classification at the unified runtime boundary.
     * The arrival watcher receives only this result and never performs page confirmation itself.
     */
    private fun observeReceivedGreetingsPageForUnifiedValidation(): ReceivedGreetingsPageArrivalStatus =
        receivedGreetingsPageRecognizer.recognize()

    private val pageObservationCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        MomoPageObservationCoordinator(
            sharedCoordinator = sharedPageObservationCoordinator,
            fullInspectionDebounceMs = fullInspectionDebounceMs,
            regionRefreshDebounceMs = regionRefreshDebounceMs,
            maxEventBurstInspectionWaitMs = MAX_EVENT_BURST_INSPECTION_WAIT_MS,
            runFullInspection = {
                if (!hasProtectedPrivateInspection() && !hasProtectedSendInspection()) runFullInspection()
            },
            runRegionRefresh = ::runRegionRefresh,
            onFallbackCheckRequested = ::requestReceivedGreetingsFallbackCheck,
            onSnapshotRequested = ::requestReceivedGreetingsSnapshot,
            fallbackDelayMs = {
                chooseRandomInt(LOW_FREQUENCY_IDLE_MIN_SECONDS, LOW_FREQUENCY_IDLE_MAX_SECONDS)
                    .coerceIn(LOW_FREQUENCY_IDLE_MIN_SECONDS, LOW_FREQUENCY_IDLE_MAX_SECONDS) * 1_000L
            },
            isObservationActive = isAiReplyActive
        )
    }
    private var messageReadRetryTask: ScheduledTask? = null
    private var messageReadRetryCount = 0
    private var lastRecoveryAndroidReadinessWait: String? = null
    /** Diagnostic-only deduplication; never grants UI authority or consumes a receipt. */
    private var lastIdentityReturnNotConsumedReason: String? = null
    private var serviceInterruptionCheckpointPersisted = false
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
    private val contactMessageLedger = ContactMessageLedger(contactMessageLedgerRepository)
    private var ocrDebugBatchLimitLogged = false
    // Run-local receipt for the current confirmed visit only. Durable send/follow-up
    // facts remain in their existing journals and ledger.
    private var confirmedPrivateCompletionReceipt: String? = null
    private var currentSessionSendConfirmed = false
        set(value) {
            field = value
            if (!value) confirmedPrivateCompletionReceipt = null
        }

    /** Owns asynchronous OCR/message callback lifetime; Runtime handles only validated results. */
    private val messageReadCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        ChatMessageReadCoordinator(
            scheduler = scheduler,
            messagePort = chatMessagePort,
            messageLimit = MAX_VISIBLE_MESSAGES,
            isReadRequestAllowed = {
                isAiReplyActive() && !recoveryContext.pending &&
                    chatPagePort.supportsTargetPackage(targetPackage())
            },
            inspectActivePage = { readPageSnapshot(AiReplyPageReadPurpose.FULL_INSPECTION) },
            isResultCurrent = ::isMessageReadResultCurrent,
            shouldDrainPendingSnapshot = { !receivedGreetingsTaskActive },
            onValidatedResult = { requestedSnapshot, batch, activeSnapshot ->
                if (!recoveryContext.pending) {
                    lastPageInspection = activeSnapshot
                    handleMessageReadBatch(batch, requestedSnapshot)
                } else {
                    onLog("AI 身份业务续接[STALE_MESSAGE_READ_IGNORED]：恢复事务仍在进行；丢弃旧消息批次")
                }
            },
            onTimeout = { timeoutMs ->
                onLog(
                    "AI 模式消息读取超时：异步适配器未在 ${timeoutMs}ms 内返回；" +
                        "已丢弃该读取代次并安排新鲜页面复核"
                )
                if (sessionRunStarted && isSendConfirmationPending()) {
                    scheduleDeferredSendProofRecheck("STRUCTURE_CALLBACK_TIMEOUT")
                } else scheduleMessageListRetry()
            },
            onAdapterFailure = {
                onLog("AI 模式消息读取失败：读取适配器异常；未保留消息正文")
                cancelMessageReadRetry()
                if (sessionRunStarted && isSendConfirmationPending()) {
                    scheduleDeferredSendProofRecheck("STRUCTURE_REQUEST_FAILED")
                } else schedulePrivatePreSendReadRetry("MESSAGE_READ_ADAPTER_FAILURE")
            },
            onRejectedResult = {
                if (sessionRunStarted && isSendConfirmationPending()) {
                    scheduleDeferredSendProofRecheck("STRUCTURE_RESULT_REJECTED")
                    true
                } else false
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
            foregroundQuietRemainingMs = foregroundQuietRemainingMs,
            inspectFreshPageSnapshot = ::inspectFreshPageSnapshot,
            isMessageViewportPositionedAgainstTab = ::isMessageViewportPositionedAgainstTab,
            hasRelevantMessageStructureDelta = ::hasRelevantMessageStructureDelta,
            onConfirmed = { snapshot ->
                sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP
                onLog(
                    "AI 启动导航[CONFIRMED]：消息工作区域、底部导航锚点和会话列表视口已连续" +
                        "3 次新快照确认；开始执行启动顶部定位"
                )
                handleInspectionSnapshot(
                    ObservationResult(
                        status = ObservationStatus.CONFIRMED_PAGE,
                        reason = "STARTUP_READINESS_CONFIRMED",
                        snapshot = snapshot
                    )
                )
            },
            onTimedOut = {
                if (sessionRunStarted && !receivedGreetingsTaskActive) {
                    onLog("AI 私聊启动恢复[READINESS_TIMEOUT]：启动超时转入已有列表检查点只读恢复")
                    recoverRetryablePrivateList("PRIVATE_STARTUP_READINESS_TIMEOUT")
                } else {
                    sessionManagerState = SessionManagerState.PAUSED
                }
            },
            onLog = onLog
        )
    }




    /** One coordinator-owned transaction shared by message-list and received-greetings recovery. */
    private val recoveryTransaction = RecoveryEventDispatcher(
        UnifiedRecoveryTransactionStateMachine(),
        onLog,
        sharedManager = recoveryTransactionManager
    )
    private val recoveryContext = RecoveryContext()
    private val privateMessagesTraversalTaskExecutor = PrivateMessagesTraversalTaskExecutor()
    // 初始化顺序：这些字段必须先于 init 加载进程检查点。
    private var messageListCheckpointRestored = false
    private var messageListCheckpointForUnified: PrivateMessagesTraversalCheckpoint? = null
    private var messageListCheckpointRestoreSource = RecoveryCheckpointRestoreSource.PROCESS_RESTART
    /** The private UNKNOWN/checkpoint identity is suspended, never completed, during a
     * separately verified manual Received Greetings task. The persisted cursor is untouched. */
    private var suspendedPrivateRecovery: RecoveryContext.Snapshot? = null
    // A verified child visit can temporarily replace list execution, not list proof.
    // Recheck the original list fault against the updated durable cursor on return.
    private var privateListRecoveryReasonAfterObservedDetail: String? = null
    private var receivedGreetingsCheckpointForUnified: MomoSayHiTraversalCheckpointRecord? = null
    private var receivedGreetingsCheckpointRestoreSource = RecoveryCheckpointRestoreSource.PROCESS_RESTART
    private val messageListRecoveryRoute: PageRecoveryRouteAdapter by lazy(LazyThreadSafetyMode.NONE) {
        MessageListRecoveryRouteAdapter {
            privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
        }
    }
    private val receivedGreetingsRecoveryRoute: PageRecoveryRouteAdapter by lazy(LazyThreadSafetyMode.NONE) {
        ReceivedGreetingsRecoveryRouteAdapter {
            chatPagePort.persistReceivedGreetingsCheckpoint()
        }
    }
    private val chatDetailRecoveryRoute: PageRecoveryRouteAdapter by lazy(LazyThreadSafetyMode.NONE) {
        ChatDetailRecoveryRouteAdapter {
            privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
        }
    }
    init {
        sharedPageObservationCoordinator.registerRecoveryAdapter(messageListRecoveryRoute)
        sharedPageObservationCoordinator.registerRecoveryAdapter(receivedGreetingsRecoveryRoute)
        sharedPageObservationCoordinator.registerRecoveryAdapter(chatDetailRecoveryRoute)
        // Creating the classifier must not activate an unrelated task or recovery lane.
        if (!pageSelectedStartup) privateMessagesCheckpointRepository?.load()?.let { checkpoint ->
            privateMessagesTraversalTaskExecutor.restore(checkpoint)
            messageListCheckpointRestored = true
            messageListCheckpointForUnified = checkpoint
            messageListCheckpointRestoreSource = RecoveryCheckpointRestoreSource.PROCESS_RESTART
            recoveryContext.request(
                PRIVATE_MESSAGES_TASK_ID,
                "PROCESS_RESTART_CHECKPOINT",
                recoveryRouteIdForTask(PRIVATE_MESSAGES_TASK_ID) ?: "unknown-route",
                currentRunGeneration().coerceAtLeast(0L),
                durableRecoveryCheckpointId(PRIVATE_MESSAGES_TASK_ID, checkpoint.savedAtEpochMs, "PROCESS_RESTART_CHECKPOINT"),
                0L
            )
            recoveryContext.markCheckpointLoaded()
            recoveryTransaction.activate(
                RecoveryEventIdentity(
                    taskId = PRIVATE_MESSAGES_TASK_ID,
                    routeId = recoveryRouteIdForTask(PRIVATE_MESSAGES_TASK_ID) ?: "unknown-route",
                    runGeneration = currentRunGeneration().coerceAtLeast(0L),
                    checkpointId = durableRecoveryCheckpointId(PRIVATE_MESSAGES_TASK_ID, checkpoint.savedAtEpochMs, "PROCESS_RESTART_CHECKPOINT"),
                    observationGeneration = 0L,
                    unknownReason = "PROCESS_RESTART_CHECKPOINT"
                )
            )
            recoveryTransaction.dispatch(
                RecoveryTransactionEvent.CheckpointLoaded(RecoveryTransactionEvent.Source.PROCESS_RESTART)
            )
            onLog("消息列表进程恢复：已读取持久化检查点；等待页面结构重新确认后继续")
        }
    }
    private var sessionManagerState: SessionManagerState
        get() = privateMessagesTraversalTaskExecutor.executionState
        set(value) {
            privateMessagesTraversalTaskExecutor.executionState = value
            recoveryContext.recordPauseState(value == SessionManagerState.PAUSED)
        }
    /** Archive lifecycle is independent from the optional automatic session traversal state. */
    private var sampleCaptureActive = false
    private var sessionRunStarted = false
    // Task-local yield and pair wake are independent facts.
    private var privateToGreetingsHandoff: AiReplyTaskYieldReason? = null
    private val privateSliceQuota = AiReplyTaskSliceQuota()
    private val greetingSliceQuota = AiReplyTaskSliceQuota()
    private var pendingPrivateQuotaReceipt: String? = null
    private var privateQuotaReturnReady = false
    private enum class PairWakeMode { REFRESH_PRIVATE, RESUME_PRIVATE }
    private data class PairWake(val mode: PairWakeMode, var due: Boolean, var retryAttempt: Int = 0)
    private var pairWake: PairWake? = null
    // A deferred yield owes the original relocation a retry before yielding again.
    private var privateRelocationRetryOwed = false
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
    /** Captures the self-initiated list-to-detail navigation awaiting page verification. */
    private data class ExpectedPrivateSessionDetailNavigation(
        val sessionKey: String,
        val sessionName: String?,
        val sourceWindowKey: String,
        val startedAtUptimeMs: Long
    )
    private var expectedPrivateSessionDetailNavigation: ExpectedPrivateSessionDetailNavigation? = null
    /**
     * Business-neutral navigation provenance consumed by route recovery.
     *
     * This is deliberately separate from expectedPrivateSessionDetailNavigation: the
     * route layer may trust only the generic unified input result, never a message
     * session's expected target registration.
     */
    private var pendingNavigationSourceResult: SelfNavigationTransition? = null
    /** Set by an unattributed window transition; consumed only after a fresh route match. */
    private var unmatchedNavigationObservationPending = false

    /** Receives business-neutral navigation provenance from the unified accessibility layer. */
    fun onSelfNavigationAttached(transition: SelfNavigationTransition) {
        pendingNavigationSourceResult = transition
        onLog(
            "统一页面导航[SELF_NAVIGATION_ATTACHED]：" +
                "actionId=${transition.actionId}；targetActivity=${transition.targetActivityClass}；" +
                "targetPageGeneration=${transition.targetPageGeneration}"
        )
        if (isPrivateFollowUpActive()) requestPageRouteObservation()
    }

    /**
     * Requests a read-only route observation for a transition with no unique Freedom source.
     * The event itself never grants ownership or pauses a task; the destination fingerprint is
     * still the authority that creates the recovery transaction.
     */
    fun onUnmatchedNavigationObserved(targetActivityClass: String) {
        if (!isAiReplyActive() || !sessionRunStarted) return
        unmatchedNavigationObservationPending = true
        onLog(
            "统一页面恢复[UNMATCHED_OBSERVATION]：未登记导航；" +
                "targetActivity=${targetActivityClass.ifBlank { "UNKNOWN" }}；" +
                "等待目标页面指纹，不直接判定UNKNOWN或恢复"
        )
        if (isPrivateFollowUpActive()) requestPageRouteObservation()
        else scheduleFullInspectionAfter(0L)
    }
    /** Prevents duplicate mismatch handling; the per-attempt guard belongs to the traversal executor. */
    private val headerMismatchGuard: Any
        get() = privateMessagesTraversalTaskExecutor.headerMismatchGuard
    private var headerMismatchRecordedForCurrentAttempt: Boolean
        get() = privateMessagesTraversalTaskExecutor.headerMismatchRecordedForCurrentAttempt
        set(value) { privateMessagesTraversalTaskExecutor.headerMismatchRecordedForCurrentAttempt = value }
    private var sessionTransitionTask: ScheduledTask? = null
    /** Intent only: the timer never authorizes a gesture or stores a row locator. */
    private data class IncompleteUnreadRelocationWait(
        val runGeneration: Long,
        val actionGeneration: Long,
        val windowIdentity: String,
        val visibleKeys: List<String>,
        var due: Boolean = false
    )
    private var incompleteUnreadRelocationWait: IncompleteUnreadRelocationWait? = null
    /** A submitted session tap is not a completed visit until the intended detail is verified. */
    private var missedPrivateSessionTapKey: String? = null
    private var missedPrivateSessionTapCount = 0
    /** Count only re-dispatched BACK actions, never the read-only verification samples. */
    private var confirmedPrivateReturnBackRetryCount = 0
    /** A unified list recovery after confirmed send must finish the old return before traversal. */
    private var confirmedPrivateReturnAwaitingRecovery = false
    /** Retains the scroll direction while the live mf_view exclusion leaves no safe rectangle. */
    private var sessionSafeViewportRetryTask: ScheduledTask? = null
    private var sessionSafeViewportRetryCount = 0
    private var sessionSafeViewportRetryGeneration = 0L
    /** Set by relevant target-app page-change events; probe admission never consults InputOrigin. */
    private var receivedGreetingsPageProbePending = false
    // Only a cold, page-selected private startup needs to connect the original
    // work-area verification after its fresh page and lease have been admitted.
    private var pageSelectedPrivateStartupVerificationPending = false
    /** Keeps private-message ownership suspended until the transition destination is confirmed. */
    private var receivedGreetingsOwnershipRecheckPending = false
    /** Fresh ordinary-inbox route can overrule a stale dedicated greetings-list result. */
    private var receivedGreetingsProbeOrdinaryInboxConfirmed = false
    private var receivedGreetingsProbePrivateRouteWon = false
    /** Short-lived empty accessibility roots are retried without replacing the last confirmed page. */
    private val transientEmptySnapshotRecovery = AiReplySessionTraversalCoordinator(
        RecoveryScheduler.fastRetryDelaysMs()
    )
    private val pageObservationSemantics = AiReplyPageObservationSemantics()
    private val pageReadCoordinator = AiReplyPageReadCoordinator(
        inspectPage = { chatPagePort.inspectChatPage() },
        onLog = onLog
    )
    private val messageListCheckpointVerifier = MessageListCheckpointVerification(
        scheduler = scheduler,
        targetPackage = targetPackage,
        currentRunGeneration = currentRunGeneration,
        onReadOnlyObservation = {
            if (isAiReplyActive() && sessionRunStarted && !userInteractionActive &&
                recoveryContext.pending && recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
                messageListCheckpointForUnified != null
            ) {
                scheduleFullInspectionAfter(0L)
            }
        },
        onLog = onLog,
        scheduleRepository = privateMessagesRecoveryScheduleRepository,
        recoveryTransactionManager = recoveryTransactionManager
    )
    private val receivedGreetingsCheckpointVerifier = ReceivedGreetingsCheckpointVerification(
        scheduler = scheduler,
        targetPackage = targetPackage,
        currentRunGeneration = currentRunGeneration,
        onReadOnlyObservation = {
            if (isAiReplyActive() && sessionRunStarted && !userInteractionActive &&
                recoveryContext.pending && recoveryContext.sourceTaskId == RECEIVED_GREETINGS_TASK_ID &&
                receivedGreetingsCheckpointForUnified != null
            ) {
                scheduleFullInspectionAfter(0L)
            }
        },
        onLog = onLog,
        scheduleRepository = receivedGreetingsRecoveryScheduleRepository,
        recoveryTransactionManager = recoveryTransactionManager
    )

    /** Every raw page read must declare its observation entry purpose. */
    private fun readPageSnapshot(purpose: AiReplyPageReadPurpose): ChatPageSnapshot? =
        pageReadCoordinator.read(purpose).snapshot
    private var snapshotRecoveryStartedAtMs = 0L
    private var receivedGreetingsUserActive = false
    private var userInteractionActive = false
    private var receivedGreetingsQuietTask: ScheduledTask? = null
    private var receivedGreetingsQuietGeneration = 0L
    private val pageRecoveryVerificationGate = PageRecoveryVerificationGate()
    /** Limits repeated handoff lease churn while the original private checkpoint stays pending. */
    private val crossTaskGreetingsHandoffRetryGate = CrossTaskGreetingsHandoffRetryGate()
    private var crossTaskGreetingsHandoffRetryTask: ScheduledTask? = null
    private var receivedGreetingsRecoveryVerificationPending = false
    private var receivedGreetingsRecoveryVerifiedForNextSnapshot = false
    private var receivedGreetingsRecoveryRetryAttempt = 0
    private var messageRecoveryRetryAttempt = 0
    /** Read-only send-proof admission failures use the existing unbounded recovery backoff. */
    private var deferredSendProofRetryAttempt = 0
    private var messageListRecoveryVerificationPending = false
    /** Generation invalidates callbacks submitted for a page/task context that is no longer current. */
    private var sessionActionGeneration = 0L
    private var activeSessionGestureRequestId: String? = null
    private data class CurrentSessionBinding(
        val sourceWindowKey: String,
        val sourceRowPath: String?,
        val sourceRowBounds: ScreenRect?,
        val sourceAvatarBounds: ScreenRect?,
        val chatWindowKey: String,
        val chatSurfaceKey: String,
        val observedDetailTarget: ObservedPrivateChatIdentityTarget? = null,
        val observedVerifiedIdentity: ContactRowIdentity? = null,
        val observedTaskLease: TaskLease? = null,
        val observedBusinessAdmitted: Boolean = false
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

    /**
     * Source-anchored greeting chat binding. This is evidence, not a UI-operation lease:
     * every OCR callback must still revalidate its live window and the exact task lease.
     * Contact IDs and titles remain ephemeral and must never be written to diagnostics.
     */
    private data class GreetingChatBinding(
        val rowKeyHash: String,
        val contactId: String,
        val normalizedDisplayName: String,
        val chatTitleKey: String,
        val windowSessionKey: String,
        val runGeneration: Long,
        val visitToken: String,
        val taskLease: TaskLease?
    )
    private var greetingChatBinding: GreetingChatBinding? = null
    /** Latest verified greeting-detail message observation used to build a pre-send diff baseline. */
    private var latestGreetingMessageBatch: ChatMessageBatch? = null
    /** Latest verified private-detail message observation used to build a pre-send diff baseline. */
    private var latestPrivateMessageBatch: ChatMessageBatch? = null

    /** A complete greeting send is not a completed row until its final chat sample and safe BACK. */
    private data class ConfirmedGreetingSend(
        val binding: GreetingChatBinding,
        val confirmationId: String,
        var finalSampleSaved: Boolean = false,
        var sendJournalSaved: Boolean = false
    )
    private var confirmedGreetingSend: ConfirmedGreetingSend? = null
    private var greetingModelFailureRecovery:
        com.freedom.features.aireply.ai.AiReplyPreSendModelFailure? = null
    private var greetingModelFailureRecoveryTask: ScheduledTask? = null


    /**
     * A model-only failure is a business event, not permission to navigate. Delivery may
     * arrive from the model executor after a page switch; always pass through the runtime
     * scheduler and the original greeting visit/lease before considering any UI action.
     */
    fun onPreSendModelFailure(failure: com.freedom.features.aireply.ai.AiReplyPreSendModelFailure) {
        runCatching {
            scheduler.schedule(0L) {
                if (!receivedGreetingsTaskActive) {
                    onPrivatePreSendModelFailure(failure)
                    return@schedule
                }
                val binding = greetingChatBinding
                if (binding == null || !receivedGreetingsTaskActive || !sessionRunStarted ||
                    !ownsTask(RECEIVED_GREETINGS_TASK_ID) || !isAiReplyActive() ||
                    recoveryContext.pending || confirmedGreetingSend != null ||
                    failure.visitToken != binding.visitToken ||
                    failure.windowSessionKey != binding.windowSessionKey ||
                    failure.conversationKey != binding.chatTitleKey ||
                    failure.taskLease != binding.taskLease ||
                    binding.runGeneration != currentRunGeneration()
                ) {
                    onLog("GREETING_MODEL_FAILURE_STALE_IGNORED：原招呼或任务租约已变化；不返回、不重试")
                    return@schedule
                }
                greetingModelFailureRecovery = failure
                onLog("GREETING_MODEL_FAILURE_RECORDED：reason=${failure.reasonCode}；" +
                    "attempts=${failure.attempts}；readyToDefer=${failure.readyToDefer}；未标记回复成功")
                scheduleGreetingModelFailureRecovery(
                    if (failure.readyToDefer) 0L else failure.retryDelayMs
                )
            }
        }.onFailure {
            onLog("GREETING_MODEL_FAILURE_DISPATCH_REJECTED：等待新鲜页面观察；不操作 UI")
        }
    }

    /**
     * A technical failure before the first input/send belongs to its original chat
     * visit. Re-enter OCR only after the durable failure and freshly empty composer
     * have been checked by the original coordinator; never replay model text or a send.
     */
    private fun onPrivatePreSendModelFailure(
        failure: com.freedom.features.aireply.ai.AiReplyPreSendModelFailure
    ) {
        if (!sessionRunStarted || !isAiReplyActive() ||
            sessionManagerState != SessionManagerState.READING_SESSION ||
            !canPerformPrivateMessagesUiAction() || currentSessionSendConfirmed ||
            isSendConfirmationPending() || failure.visitToken != draftVisitToken ||
            failure.windowSessionKey != currentSessionWindowKey ||
            !hasBoundPrivateDetailRecoverySession()
        ) {
            onLog("AI 私聊模型恢复[STALE_FAILURE_IGNORED]：原会话、发送状态或操作权已变化")
            return
        }
        privateModelFailureRecovery = failure
        privateModelRetryAwaitingDraft = false
        // Earlier OCR sample handling may already have queued a BACK; revoke it.
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        onLog("AI 私聊模型恢复[PRE_SEND_FAILURE_RECORDED]：reason=${failure.reasonCode}；" +
            "attempt=${failure.attempts}；仅待冷却后复核 OCR/空输入框；无发送动作")
        schedulePrivateModelFailureRecovery(failure.retryDelayMs)
    }

    private fun schedulePrivateModelFailureRecovery(delayMs: Long) {
        val failure = privateModelFailureRecovery ?: return
        privateModelFailureRecoveryTask?.cancel()
        privateModelFailureRecoveryTask = scheduler.schedule(delayMs) {
            privateModelFailureRecoveryTask = null
            if (privateModelFailureRecovery !== failure || !sessionRunStarted ||
                !isAiReplyActive() || receivedGreetingsTaskActive ||
                sessionManagerState != SessionManagerState.READING_SESSION ||
                currentSessionSendConfirmed || isSendConfirmationPending()
            ) return@schedule
            if (!canPerformPrivateMessagesUiAction()) {
                onLog("AI 私聊模型恢复[LEASE_WAIT]：页面操作权或恢复门禁未满足；不调用模型")
                schedulePrivateModelFailureRecovery(RecoveryScheduler.readOnlyRetryDelayMs(1))
                return@schedule
            }
            val fresh = runCatching {
                readPageSnapshot(AiReplyPageReadPurpose.MESSAGE_READ_RETRY)
            }.getOrNull()
            if (fresh == null || !isVerifiedRecoveredPrivateDetail(fresh) ||
                currentSessionWindowKey != failure.windowSessionKey ||
                fresh.conversationIdentityKey() != failure.conversationKey ||
                isConversationDraftPending() || isSendControlRecoveryPending() ||
                !verifyPrivatePreSendModelFailure(failure, fresh)
            ) {
                onLog("AI 私聊模型恢复[READ_ONLY_WAIT]：原会话、原失败任务或空输入框未验证；不重用旧草稿")
                schedulePrivateModelFailureRecovery(RecoveryScheduler.readOnlyRetryDelayMs(1))
                return@schedule
            }
            privateModelFailureRecovery = null
            privateModelRetryAwaitingDraft = true
            onLog("AI 私聊模型恢复[RETRY_ADMITTED]：原会话及空输入框已独立验证；重新读取 OCR 后再生成")
            readVisibleMessages(fresh)
        }
    }

    private fun scheduleGreetingModelFailureRecovery(delayMs: Long) {
        val failure = greetingModelFailureRecovery ?: return
        greetingModelFailureRecoveryTask?.cancel()
        greetingModelFailureRecoveryTask = runCatching {
            scheduler.schedule(delayMs) {
                greetingModelFailureRecoveryTask = null
                if (greetingModelFailureRecovery !== failure) return@schedule
                val binding = greetingChatBinding
                if (binding == null || !sessionRunStarted || !receivedGreetingsTaskActive ||
                    !isAiReplyActive() || !ownsTask(RECEIVED_GREETINGS_TASK_ID) ||
                    recoveryContext.pending || confirmedGreetingSend != null ||
                    failure.visitToken != binding.visitToken ||
                    failure.windowSessionKey != binding.windowSessionKey ||
                    failure.conversationKey != binding.chatTitleKey ||
                    failure.taskLease != binding.taskLease ||
                    binding.runGeneration != currentRunGeneration()
                ) {
                    onLog("GREETING_MODEL_FAILURE_STALE_IGNORED：延迟回调已失去原招呼绑定或授权")
                    return@schedule
                }
                val fresh = readPageSnapshot(AiReplyPageReadPurpose.MESSAGE_READ_RETRY)
                val reason = if (fresh == null) "FRESH_CHAT_UNAVAILABLE"
                    else greetingBindingRejectReason(fresh, binding)
                if (fresh == null || reason != null ||
                    isSendConfirmationPending() || isSendControlRecoveryPending() ||
                    isConversationDraftPending() ||
                    !verifyGreetingPreSendModelFailure(failure, fresh)
                ) {
                    onLog("GREETING_MODEL_FAILURE_VERIFY_WAIT：reason=${reason ?: "SEND_OR_COMPOSER_NOT_VERIFIED"}；" +
                        "保留当前用户，等待新的安全页面证据")
                    scheduleGreetingModelFailureRecovery(GREETING_MODEL_FAILURE_SAFE_RECHECK_DELAY_MS)
                    return@schedule
                }
                if (!failure.readyToDefer) {
                    // The previous request is durably retryable and the original composer
                    // is still empty. Re-enter the existing OCR/model path once; no old
                    // message observation or input locator is reused.
                    onLog("GREETING_MODEL_FAILURE_RETRY_ADMITTED：已核验原招呼、任务租约与空输入框；重新读取 OCR")
                    greetingModelFailureRecovery = null
                    readVisibleMessages(fresh)
                    return@schedule
                }
                // Never record SEND_CONFIRMED here. The adapter verifies the detail
                // fingerprint and submits the same safe BACK used by greeting completion.
                val deferred = receivedGreetingsTaskFlow.deferAfterModelFailure()
                if (!deferred) {
                    onLog("GREETING_MODEL_FAILURE_DEFER_WAIT：安全 BACK 未准入；继续只读复核，不标记完成")
                    scheduleGreetingModelFailureRecovery(GREETING_MODEL_FAILURE_SAFE_RECHECK_DELAY_MS)
                    return@schedule
                }
                onLog("GREETING_REPLY_DEFERRED：模型失败且无草稿/发送动作；原招呼延期，等待列表双快照确认")
                runCatching { onGreetingModelFailureDeferred(failure) }
                greetingModelFailureRecovery = null
                invalidatePendingMessageRead()
                scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
            }
        }.getOrNull()
        if (greetingModelFailureRecoveryTask == null) {
            onLog("GREETING_MODEL_FAILURE_SCHEDULER_UNAVAILABLE：保留未完成招呼；不执行 BACK")
        }
    }

    /**
     * Finishes only a confirmed greeting reply, never an OCR-only sample. A failed sample write
     * or rejected safe BACK retains this exact send proof for the next fresh observation.
     * No additional model request or send is dispatched while the proof is retained.
     */
    private fun completeConfirmedGreetingSend(batch: ChatMessageBatch, snapshot: ChatPageSnapshot) {
        val confirmed = confirmedGreetingSend ?: return
        val reason = greetingBindingRejectReason(snapshot, confirmed.binding)
        if (reason != null || batch.windowSessionKey != confirmed.binding.windowSessionKey) {
            onLog("GREETING_SEND_RETURN_BLOCKED reason=${reason ?: "MESSAGE_WINDOW_MISMATCH"}")
            return
        }
        if (!confirmed.sendJournalSaved) {
            val recorded = runCatching {
                chatPagePort.persistReceivedGreetingConfirmedSend(confirmed.confirmationId)
            }.getOrDefault(false)
            if (!recorded) {
                onLog("GREETING_SEND_RETURN_BLOCKED reason=SEND_FACT_NOT_PERSISTED")
                scheduleReceivedGreetingsRecoveryRetry(
                    "收到招呼回复已确认但发送事实持久化失败；保留本次事务，不返回、不重复发送"
                )
                return
            }
            confirmed.sendJournalSaved = true
            onLog("GREETING_SEND_FACT_PERSISTED：已确认发送事实落盘；尚未确认安全返回")
        }
        if (!confirmed.finalSampleSaved) {
            val sampleIndex = runCatching {
                chatSessionManagementPort.persistChatSample(
                    snapshot,
                    batch,
                    sessionName = receivedGreetingsTaskFlow.currentDisplayName(),
                    contactIdentity = receivedGreetingsTaskFlow.currentContactIdentity()
                )
            }.getOrDefault(0)
            if (sampleIndex <= 0) {
                onLog("GREETING_SEND_RETURN_BLOCKED reason=FINAL_CHAT_SAMPLE_NOT_SAVED")
                scheduleReceivedGreetingsRecoveryRetry("收到招呼发送已确认，最终聊天样本未保存；不标记完成、不重复发送")
                return
            }
            confirmed.finalSampleSaved = true
        }
        val returned = runCatching {
            receivedGreetingsTaskFlow.completeDetailInspection(true)
        }.getOrDefault(false)
        if (returned) {
            onLog("GREETING_RETURN_BACK_ACCEPTED：发送事实及最终聊天已保存；BACK 已提交但仍等待列表双快照确认")
            invalidatePendingMessageRead()
            scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
        } else {
            onLog("GREETING_SEND_RETURN_BLOCKED reason=SAFE_BACK_NOT_CONFIRMED")
            scheduleReceivedGreetingsRecoveryRetry("收到招呼发送已确认，但安全返回未通过；保持原聊天，不重复发送")
        }
    }

    /**
     * One source of truth for an initial greeting detail admission and subsequent binding
     * checks. The adapter has already verified row-tap feedback and the chat fingerprint;
     * business binds only verified identity, exact chat window, and current task ownership.
     */
    private data class GreetingChatEvidence(
        val rowKeyHash: String,
        val contactId: String,
        val normalizedDisplayName: String,
        val chatTitleKey: String,
        val windowSessionKey: String,
        val taskLease: TaskLease?
    )

    private sealed interface GreetingChatAdmission {
        data class Ready(val evidence: GreetingChatEvidence) : GreetingChatAdmission
        data class Rejected(val reason: String) : GreetingChatAdmission
    }

    private fun inspectGreetingChatAdmission(
        snapshot: ChatPageSnapshot,
        requireDetailReady: Boolean
    ): GreetingChatAdmission {
        if (!sessionRunStarted || !isAiReplyActive()) return GreetingChatAdmission.Rejected("RUN_NOT_ACTIVE")
        if (!receivedGreetingsTaskActive || !ownsTask(RECEIVED_GREETINGS_TASK_ID)) {
            return GreetingChatAdmission.Rejected("TASK_NOT_OWNED")
        }
        if (recoveryContext.pending) return GreetingChatAdmission.Rejected("RECOVERY_PENDING")
        // DETAIL_READY is required to create a binding, not on later asynchronous callbacks:
        // the adapter can temporarily report NAVIGATING while OCR is in flight.
        if (requireDetailReady &&
            receivedGreetingsTaskFlow.traversalStatus() != ReceivedGreetingsTraversalStatus.DETAIL_READY
        ) return GreetingChatAdmission.Rejected("DETAIL_NOT_VERIFIED")

        val rowHash = receivedGreetingsTaskFlow.currentRowKeyHash()
            ?.takeIf(String::isNotBlank)
            ?: return GreetingChatAdmission.Rejected("SOURCE_ROW_NOT_BOUND")
        val identity = receivedGreetingsTaskFlow.currentContactIdentity()
        val contactId = identity?.contactId?.takeIf(String::isNotBlank)
        if (identity?.confidence != ContactIdentityConfidence.PLATFORM_ID_VERIFIED || contactId == null) {
            return GreetingChatAdmission.Rejected("PLATFORM_ID_NOT_VERIFIED")
        }
        val displayName = receivedGreetingsTaskFlow.currentDisplayName()
        if (ChatSessionManagementPolicy.matchChatHeader(displayName, snapshot.chatTitleText) == null) {
            return GreetingChatAdmission.Rejected("CHAT_HEADER_MISMATCH")
        }
        val sessionKey = windowSessionKey(snapshot)
        val titleKey = snapshot.conversationIdentityKey()
        if (snapshot.packageName != targetPackage() ||
            snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
            sessionKey == null || titleKey == null
        ) return GreetingChatAdmission.Rejected("CHAT_WINDOW_NOT_VERIFIED")

        val lease = taskCoordinator?.currentLease()
        if (taskCoordinator != null &&
            (lease == null || lease.taskId != RECEIVED_GREETINGS_TASK_ID ||
                lease.runGeneration != currentRunGeneration())
        ) return GreetingChatAdmission.Rejected("TASK_LEASE_CHANGED")

        return GreetingChatAdmission.Ready(
            GreetingChatEvidence(
                rowKeyHash = rowHash,
                contactId = contactId,
                normalizedDisplayName = ChatSessionManagementPolicy.normalizeSessionTitle(displayName),
                chatTitleKey = titleKey,
                windowSessionKey = sessionKey,
                taskLease = lease
            )
        )
    }

    /**
     * Revalidates the already captured binding at every asynchronous boundary. An OCR
     * callback, send proof or draft must never authorize a new binding or a new task lease.
     */
    private fun greetingBindingRejectReason(
        snapshot: ChatPageSnapshot,
        expected: GreetingChatBinding? = greetingChatBinding
    ): String? {
        val binding = expected ?: return "BINDING_MISSING"
        if (!sessionRunStarted || !isAiReplyActive()) return "RUN_NOT_ACTIVE"
        if (!receivedGreetingsTaskActive || !ownsTask(RECEIVED_GREETINGS_TASK_ID)) return "TASK_NOT_OWNED"
        if (recoveryContext.pending) return "RECOVERY_PENDING"
        if (binding !== greetingChatBinding || binding.visitToken != draftVisitToken) return "READ_GENERATION_CHANGED"
        if (binding.runGeneration != currentRunGeneration()) return "RUN_GENERATION_CHANGED"

        return when (val admission = inspectGreetingChatAdmission(snapshot, requireDetailReady = false)) {
            is GreetingChatAdmission.Rejected -> when (admission.reason) {
                "SOURCE_ROW_NOT_BOUND" -> "GREETING_ROW_CHANGED"
                "PLATFORM_ID_NOT_VERIFIED" -> "CONTACT_ID_CHANGED"
                "CHAT_WINDOW_NOT_VERIFIED" -> "CHAT_WINDOW_OR_SESSION_CHANGED"
                else -> admission.reason
            }
            is GreetingChatAdmission.Ready -> {
                val evidence = admission.evidence
                when {
                    taskCoordinator != null &&
                        (binding.taskLease == null || binding.taskLease != evidence.taskLease) ->
                        "TASK_LEASE_CHANGED"
                    evidence.rowKeyHash != binding.rowKeyHash -> "GREETING_ROW_CHANGED"
                    evidence.contactId != binding.contactId -> "CONTACT_ID_CHANGED"
                    evidence.normalizedDisplayName != binding.normalizedDisplayName -> "CHAT_HEADER_MISMATCH"
                    evidence.chatTitleKey != binding.chatTitleKey ||
                        evidence.windowSessionKey != binding.windowSessionKey -> "CHAT_WINDOW_OR_SESSION_CHANGED"
                    else -> null
                }
            }
        }
    }

    /**
     * A transition can expose chat anchors before its composer/list enters the display.
     * This is binding readiness, not a page fingerprint or a gesture target calculation.
     * Only explicit offscreen evidence defers admission; missing diagnostic evidence
     * does not replace the existing independent identity/window proof.
     */
    private fun hasOffscreenGreetingChatLocators(snapshot: ChatPageSnapshot): Boolean {
        val display = snapshot.screenBounds?.takeUnless { it.isEmpty } ?: return false
        val paths = listOfNotNull(snapshot.messageListTarget, snapshot.inputFieldTarget)
            .map { it.path.childIndices.joinToString("/") }
        return snapshot.structureEvidence.any { node ->
            node.path in paths && node.boundsValid &&
                (node.right <= display.left || node.left >= display.right ||
                    node.bottom <= display.top || node.top >= display.bottom)
        }
    }

    /** The only admission-and-bind entry for a newly verified received-greeting chat. */
    private fun ensureGreetingChatBinding(snapshot: ChatPageSnapshot): Boolean {
        if (greetingBindingRejectReason(snapshot) == null) return true
        if (hasOffscreenGreetingChatLocators(snapshot)) {
            onLog("GREETING_CHAT_BINDING_DEFERRED reason=CHAT_LOCATORS_OFFSCREEN")
            return false
        }
        val admission = inspectGreetingChatAdmission(snapshot, requireDetailReady = true)
        if (admission is GreetingChatAdmission.Rejected) {
            onLog("GREETING_CHAT_BINDING_REJECTED reason=${admission.reason}")
            return false
        }
        val evidence = (admission as GreetingChatAdmission.Ready).evidence
        // A new row, identity, window or task lease invalidates old asynchronous reads.
        if (greetingChatBinding != null) invalidatePendingMessageRead()
        confirmedGreetingSend = null
        greetingChatBinding = GreetingChatBinding(
            rowKeyHash = evidence.rowKeyHash,
            contactId = evidence.contactId,
            normalizedDisplayName = evidence.normalizedDisplayName,
            chatTitleKey = evidence.chatTitleKey,
            windowSessionKey = evidence.windowSessionKey,
            runGeneration = currentRunGeneration(),
            visitToken = draftVisitToken,
            taskLease = evidence.taskLease
        )
        onLog("GREETING_CHAT_BINDING_CREATED rowHash=${evidence.rowKeyHash.take(12)}; idVerified=true; " +
            "windowBound=true; taskLeaseBound=${evidence.taskLease != null}; historyLookup=false")
        return true
    }

    private var currentSessionBinding: CurrentSessionBinding? = null
    private fun establishCurrentSessionBinding(snapshot: ChatPageSnapshot) {
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
                chatSurfaceKey = chatSurfaceKey(snapshot).orEmpty()
            )
            // Only a verified detail binding retires a no-navigation retry sequence.
            if (missedPrivateSessionTapKey == currentSessionKey) {
                missedPrivateSessionTapKey = null
                missedPrivateSessionTapCount = 0
            }
            if (privateMessagesTraversalTaskExecutor.recordVerifiedDetailEntry(snapshot, System.currentTimeMillis())) {
                privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
                onLog("AI 私聊业务[UNFINISHED_RETAINED]：已验证进入会话；尚未确认业务终态")
            }
            onLog("AI 会话绑定：已建立来源行/聊天窗口/聊天表面绑定；昵称仅作辅助校验")
        }
    }
    /**
     * A profile return may recreate the chat window. Rebind only after the existing
     * source-row, target-session, task-lease, chat-title and fresh-window gates pass.
     * Retain the original source-row anchor and expected-navigation evidence.
     */
    private fun rebindVerifiedPrivateChat(snapshot: ChatPageSnapshot): Boolean {
        if (!isVerifiedRecoveredPrivateDetail(snapshot)) return false
        val original = currentSessionBinding ?: return false
        val freshWindowKey = windowSessionKey(snapshot) ?: return false
        val freshSurfaceKey = chatSurfaceKey(snapshot) ?: return false
        currentSessionBinding = original.copy(
            chatWindowKey = freshWindowKey,
            chatSurfaceKey = freshSurfaceKey
        )
        currentSessionWindowKey = freshWindowKey
        onLog("AI 会话绑定[VERIFIED_CHAT_REBOUND]：原会话及任务租约已验证；聊天窗口与表面绑定已同步；保留来源行锚点")
        return true
    }
    private var identityCaptureSessionKey: String? = null
    private var identityCaptureStartedAtMs = 0L
    private var identityCaptureRetryCount = 0
    private var identityCaptureTerminalSessionKey: String? = null
    private var identityCaptureTimeoutTask: ScheduledTask? = null

    /**
     * Identity capture may fail without invalidating the selected current chat. Before using
     * only its visible messages, independently re-check the navigation source, task lease,
     * selected row, header and a fresh live chat window; never infer identity from a name.
     */
    private fun isFreshCurrentTargetForIdentityFallback(snapshot: ChatPageSnapshot): Boolean {
        if (!sessionRunStarted || receivedGreetingsTaskActive ||
            sessionManagerState != SessionManagerState.READING_SESSION ||
            !ownsTask(PRIVATE_MESSAGES_TASK_ID) || !isAiReplyActive()
        ) return false
        val candidate = currentSessionCandidate ?: return false
        val selectedKey = ChatSessionManagementPolicy.stableSessionKey(candidate)
        val expected = expectedPrivateSessionDetailNavigation ?: return false
        val sourceWindow = candidate.rowTarget.window
        val sourceKey = listOf(
            sourceWindow.packageName, sourceWindow.windowId, sourceWindow.generation
        ).joinToString("|")
        if (expected.sessionKey != selectedKey || currentSessionKey != selectedKey ||
            expected.sourceWindowKey != sourceKey ||
            chatSessionManagementPort.isSessionIdentityBlocked(selectedKey)
        ) return false
        if (snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
            snapshot.packageName != targetPackage() ||
            currentSessionHeaderMatchMode(snapshot) == null ||
            windowSessionKey(snapshot) == null ||
            currentSessionWindowKey != windowSessionKey(snapshot)
        ) return false
        val fresh = readPageSnapshot(AiReplyPageReadPurpose.MESSAGE_READ_RETRY) ?: return false
        return fresh.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            fresh.packageName == targetPackage() &&
            isExpectedPrivateSessionDetail(fresh) &&
            currentSessionHeaderMatchMode(fresh) != null &&
            currentSessionWindowKey == windowSessionKey(fresh) &&
            hasSameWindowSession(snapshot, fresh)
    }


    /**
     * Low-frequency watcher callback. It requests one read-only page inspection and never
     * navigates or changes task ownership without a page-structure result.
     */
    private fun requestReceivedGreetingsFallbackCheck() {
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive ||
            !receivedGreetingsTaskFlow.supportsWorkflow()
        ) return
        receivedGreetingsPageProbePending = true
        onLog("收到的招呼页面观察[LOW_FREQUENCY_CHECK]：执行一次只读页面结构检查")
        // The watchdog callback is already a fresh-observation request. Schedule it through
        // the runtime-owned inspection slot so a stale/coalesced full-inspection channel cannot
        // swallow the probe while an earlier page inspection is still being retired.
        if (hasProtectedPrivateInspection() || hasProtectedSendInspection()) return
        scheduleFullInspectionAfter(
            fullInspectionDebounceMs,
            AiReplyPageReadPurpose.RECEIVED_GREETINGS
        )
    }

    /** Requests a fresh unified snapshot for a bounded arrival retry; no adapter probe is run here. */
    private fun requestReceivedGreetingsSnapshot() {
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive ||
            !receivedGreetingsTaskFlow.supportsWorkflow()
        ) return
        receivedGreetingsPageProbePending = true
        // The watcher has already supplied the stability delay. Take the fresh read-only
        // snapshot immediately; adding another full-page debounce here changes the contract
        // from 350ms to 350ms + debounce and leaves LIST_CONFIRMED behind the test/runtime
        // retry boundary.
        if (hasProtectedPrivateInspection() || hasProtectedSendInspection()) return
        pendingPageReadPurpose = AiReplyPageReadPurpose.RECEIVED_GREETINGS
        runFullInspection()
    }

    private fun runFullInspection() {
        // An event/watcher can satisfy an ordinary pending full observation before
        // its explicit timer fires. Retire that timer rather than losing its handle.
        // Protected send/scroll reads are still shielded at their existing entry gates.
        ++fullInspectionCallbackToken
        pageInspectionTask?.cancel()
        pageInspectionTask = null
        pageObservationCoordinator.cancelFullInspection()
        val purpose = pendingPageReadPurpose
        pendingPageReadPurpose = AiReplyPageReadPurpose.FULL_INSPECTION
        runFullInspection(purpose)
    }

    private fun runFullInspection(purpose: AiReplyPageReadPurpose) {
        pageInspectionTask = null
        if (!isAiReplyActive()) return
        if (isPrivateFollowUpActive()) {
            runPrivateFollowUpObservation()
            return
        }
        if (!chatPagePort.supportsTargetPackage(targetPackage())) {
            pageRecoveryVerificationGate.reset(PRIVATE_STARTUP_DETAIL_OWNERSHIP_GATE)
            if (sessionRunStarted && awaitingNormalInboxAfterGreetingsReturn) {
                holdGreetingsCompletionReturn("EXTERNAL_PAGE")
                return
            }
            if (sessionRunStarted && pairWake != null && !receivedGreetingsTaskActive &&
                !recoveryContext.pending
            ) {
                invalidatePairWakeEvidence()
                recoverRetryablePrivateList("POLLING_INBOX_EXTERNAL_PAGE")
                return
            }
            if (recoveryContext.pending) {
                onLog("统一页面恢复[EXTERNAL_PAGE_WAIT]：当前前台不是目标应用；${EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS}ms后继续观察")
                scheduleFullInspectionAfter(EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS)
            }
            return
        }
        if (sessionRunStarted && userInteractionActive) {
            pageRecoveryVerificationGate.reset(PRIVATE_STARTUP_DETAIL_OWNERSHIP_GATE)
            onLog("统一恢复事务[USER_ACTIVE]：用户仍在操作；暂停页面复核和业务动作，10s后重新检查")
            scheduleFullInspectionAfter(RECEIVED_GREETINGS_USER_ACTIVE_RECHECK_DELAY_MS)
            return
        }
        // Losing the UI lease forbids mutations, not fresh observation. The semantic
        // snapshot handler establishes the existing recovery transaction before any action.
        val pageRead = pageReadCoordinator.read(purpose)
        val inspection = pageRead.snapshot
        val observationDecision = pageObservationSemantics.classify(
            snapshot = inspection,
            previous = lastPageInspection,
            targetPackage = targetPackage()
        ).copy(
            observationGeneration = pageRead.observationGeneration,
            purpose = pageRead.purpose,
            failure = pageRead.failure
        )
        diagnosePendingIdentityReturn(observationDecision, inspection)
        if (observationDecision.status == ObservationStatus.TEMPORARILY_UNAVAILABLE) {
            currentSessionBinding?.observedDetailTarget?.let { target ->
                if (sessionRunStarted && ownsTask(PRIVATE_MESSAGES_TASK_ID) && !recoveryContext.pending &&
                    chatSessionManagementPort.hasPendingObservedPrivateChatIdentityCapture(target)
                ) {
                    // The existing avatar/profile transaction intentionally hides the old chat snapshot.
                    scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
                    return
                }
                // Once business is admitted, the existing durable recovery owns its
                // checkpoint. Never discard an in-flight send or its verified identity here.
                if (currentSessionBinding?.observedBusinessAdmitted != true) {
                    retireObservedPrivateDetail("SNAPSHOT_UNAVAILABLE")
                }
            }
            pageRecoveryVerificationGate.reset(PRIVATE_STARTUP_DETAIL_OWNERSHIP_GATE)
            // A target-app transitional sample is not an EMPTY_SNAPSHOT recovery if the
            // original navigation is still awaiting its destination. No snapshot or a
            // foreign package still follows the existing recovery path.
            if (inspection != null && waitForPendingGreetingsNavigationEvidence(inspection)) return
            if (pairWake != null) invalidatePairWakeEvidence()
            if (sessionRunStarted && awaitingNormalInboxAfterGreetingsReturn) {
                holdGreetingsCompletionReturn("EMPTY_SNAPSHOT")
                return
            }
            if (recoveryContext.pending) {
                recoveryContext.invalidateFingerprint()
                if (recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
                    messageListCheckpointForUnified != null
                ) {
                    messageListCheckpointVerifier.reject(observationDecision.observationGeneration)
                }
            }
            // Missing evidence breaks the continuity of every pending two-snapshot gate.
            pageRecoveryVerificationGate.reset(SNAPSHOT_RECOVERY_ROUTE_ID)
            pageRecoveryVerificationGate.reset(RECEIVED_GREETINGS_RECOVERY_ROUTE_ID)
            if (recoveryContext.sourceTaskId == RECEIVED_GREETINGS_TASK_ID &&
                receivedGreetingsCheckpointForUnified != null
            ) {
                // A missing fresh snapshot never completes an earlier checkpoint sample.
                receivedGreetingsCheckpointVerifier.reject(observationDecision.observationGeneration)
            }
            if (snapshotRecoveryStartedAtMs == 0L) {
                snapshotRecoveryStartedAtMs = System.currentTimeMillis()
                // P6: once a task is running, missing page evidence is an actual
                // recovery transaction, never only a local verification flag.
                val activeTask = taskOwnership.activeTaskId()
                val checkpointRequested = sessionRunStarted && activeTask != null &&
                    (requestUnifiedPauseAndCheckpoint(activeTask, "EMPTY_SNAPSHOT") || recoveryContext.pending)
                if (!checkpointRequested) {
                    // Pre-run sampling has no task identity; it cannot manufacture a
                    // durable checkpoint or acquire UI authority from missing evidence.
                    recoveryTransaction.dispatch(RecoveryTransactionEvent.UnknownPage("EMPTY_SNAPSHOT"))
                    recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
                }
                onLog("AI 页面观察[SNAPSHOT_RECOVERY_HOLD]：快照暂时丢失；保留当前任务租约和会话状态")
            }
            val elapsedMs = System.currentTimeMillis() - snapshotRecoveryStartedAtMs
            val nextDelayMs = transientEmptySnapshotRecovery.nextDelayMs()
            val phase = if (transientEmptySnapshotRecovery.retryCount <= RecoveryScheduler.fastRetryCount) {
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
        // Once a business recovery owns the checkpoint, only its route verifier may
        // advance the reducer or acknowledge completion. The legacy empty-snapshot
        // timer is observation bookkeeping, not a second recovery authority.
        if (recoveryContext.pending && snapshotRecoveryStartedAtMs != 0L) {
            pageRecoveryVerificationGate.reset(SNAPSHOT_RECOVERY_ROUTE_ID)
            snapshotRecoveryStartedAtMs = 0L
        }
        if (sessionRunStarted &&
            (snapshotRecoveryStartedAtMs != 0L ||
                (receivedGreetingsRecoveryVerificationPending && receivedGreetingsCheckpointForUnified == null)) &&
            !ensureAndroidRecoveryReady(recovered, "LEGACY_PAGE_RECOVERY")
        ) return
        if (snapshotRecoveryStartedAtMs != 0L) {
            if (!pageRecoveryVerificationGate.observe(SNAPSHOT_RECOVERY_ROUTE_ID, recoveryStructuralKey(recovered))) {
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
                onLog("AI 页面恢复[RECOVERY_VERIFYING]：第${pageRecoveryVerificationGate.stableSamples(SNAPSHOT_RECOVERY_ROUTE_ID)}/2次独立新鲜快照尚未稳定；350ms后复核")
                scheduleFullInspectionAfter(350L)
                return
            }
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            // The page gate has already admitted two equal fresh observations.
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeRequested)
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeCompleted)
            onLog("AI 页面恢复[RECOVERY_VERIFIED]：连续两次新鲜结构快照一致；恢复消息列表任务")
            pageRecoveryVerificationGate.reset(SNAPSHOT_RECOVERY_ROUTE_ID)
            snapshotRecoveryStartedAtMs = 0L
        }
        if (sessionRunStarted && receivedGreetingsTaskActive &&
            receivedGreetingsRecoveryVerificationPending &&
            receivedGreetingsCheckpointForUnified == null
        ) {
            val fingerprint = runCatching {
                chatPagePort.receivedGreetingsRecoveryFingerprint()
            }.getOrNull()
            if (fingerprint.isNullOrBlank()) {
                pageRecoveryVerificationGate.reset(RECEIVED_GREETINGS_RECOVERY_ROUTE_ID)
                onLog("收到的招呼恢复[RECOVERY_VERIFYING]：当前快照未通过收到招呼列表指纹；继续暂停任务")
                scheduleReceivedGreetingsRecoveryRetry("收到招呼列表指纹尚未确认；不执行滚动、点击或导航")
                return
            }
            if (!pageRecoveryVerificationGate.observe(RECEIVED_GREETINGS_RECOVERY_ROUTE_ID, fingerprint)) {
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
                onLog("收到的招呼恢复[RECOVERY_VERIFYING]：第${pageRecoveryVerificationGate.stableSamples(RECEIVED_GREETINGS_RECOVERY_ROUTE_ID)}/2次独立指纹快照尚未稳定；350ms后复核")
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
            receivedGreetingsRecoveryRetryAttempt = 0
            pageRecoveryVerificationGate.reset(RECEIVED_GREETINGS_RECOVERY_ROUTE_ID)
            onLog("收到的招呼恢复[RECOVERY_VERIFIED]：连续两次新鲜列表指纹一致；恢复收到招呼任务协调器")
        }
        handleInspectionSnapshot(observationDecision.copy(snapshot = recovered))
    }

    /** Keep an unconfirmed business transition read-only without creating a recovery transaction. */
    private fun waitForPendingGreetingsNavigationEvidence(snapshot: ChatPageSnapshot): Boolean {
        if (!sessionRunStarted || !receivedGreetingsTaskActive ||
            !ownsTask(RECEIVED_GREETINGS_TASK_ID) || recoveryContext.pending ||
            awaitingNormalInboxAfterGreetingsReturn || isSendConfirmationPending() ||
            isConversationDraftPending() || isSendControlRecoveryPending() ||
            snapshot.packageName != targetPackage()
        ) return false
        when (snapshot.pageKind) {
            ChatPageKind.NON_CHAT, ChatPageKind.UNKNOWN -> Unit
            ChatPageKind.CHAT_DETAIL_CANDIDATE -> {
                // A new chat surface can appear before the row tap callback and Activity
                // clue. Never apply this grace to an already bound conversation or an
                // explicitly different chat header. The adapter owns the tap deadline.
                if (greetingChatBinding != null) return false
                val title = snapshot.chatTitleText
                if (!title.isNullOrBlank() && ChatSessionManagementPolicy.matchChatHeader(
                        receivedGreetingsTaskFlow.currentDisplayName(), title
                    ) == null
                ) return false
            }
            else -> return false
        }
        val awaitingEvidence = runCatching {
            chatPagePort.isReceivedGreetingsNavigationAwaitingPageEvidence(snapshot)
        }.getOrDefault(false)
        if (!awaitingEvidence) return false
        // Preserve the last confirmed source snapshot for the next route comparison.
        // In particular, do not turn an unclassified observation into an accepted page.
        onLog("收到招呼业务导航[TRANSITION_EVIDENCE_WAIT]：pageKind=${snapshot.pageKind}；现有导航期限内目标页面证据未同步；保留原事务，只读复核")
        scheduleFullInspectionAfter(RECEIVED_GREETINGS_NAVIGATION_RECHECK_DELAY_MS)
        return true
    }

    /**
     * Observes the existing Android READY decision on a fresh active-window safety scan.
     * This is not a gesture permit: the dispatcher repeats its own checks before every action.
     * Incomplete evidence revokes pre-READY samples without discarding any checkpoint.
     */
    private fun ensureAndroidRecoveryReady(snapshot: ChatPageSnapshot, stage: String): Boolean {
        val evidence = runCatching { inspectAndroidRecoveryReadiness(snapshot) }.getOrNull()
        val admission = AndroidRecoveryExecutionGate.assess(
            snapshot = snapshot, expectedPackage = targetPackage(), evidence = evidence
        )
        if (admission.allowed) {
            if (lastRecoveryAndroidReadinessWait != null) {
                onLog("统一页面恢复[ANDROID_READINESS_CONFIRMED]：stage=$stage；Android=READY；等待新鲜业务路由复核")
            }
            lastRecoveryAndroidReadinessWait = null
            return true
        }
        recoveryContext.invalidateFingerprint()
        if (recoveryContext.pending) {
            if (recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
                messageListCheckpointForUnified != null
            ) messageListCheckpointVerifier.deferOwnership()
            if (recoveryContext.sourceTaskId == RECEIVED_GREETINGS_TASK_ID &&
                receivedGreetingsCheckpointForUnified != null
            ) receivedGreetingsCheckpointVerifier.deferResume()
        }
        pageRecoveryVerificationGate.reset(SNAPSHOT_RECOVERY_ROUTE_ID)
        pageRecoveryVerificationGate.reset(MESSAGE_LIST_RECOVERY_ROUTE_ID)
        pageRecoveryVerificationGate.reset(RECEIVED_GREETINGS_RECOVERY_ROUTE_ID)
        sessionManagerState = SessionManagerState.PAUSED
        if (lastRecoveryAndroidReadinessWait != admission.reasonCode) {
            onLog(
                "统一页面恢复[ANDROID_READINESS_PENDING]：stage=$stage；status=${evidence?.status ?: "NO_EVIDENCE"}；" +
                    "reason=${admission.reasonCode}；保留检查点并持续只读复核"
            )
            lastRecoveryAndroidReadinessWait = admission.reasonCode
        }
        scheduleFullInspectionAfter(500L)
        return false
    }

    /**
     * Diagnose a pending adapter receipt before any routing early-return. This check cannot
     * consume the receipt, change the lease or start OCR, even for UNKNOWN or stale pages.
     */
    private fun diagnosePendingIdentityReturn(
        observation: ObservationResult,
        snapshot: ChatPageSnapshot?
    ) {
        val pending = runCatching {
            chatSessionManagementPort.hasPendingIdentityReturnReinspectionRequest()
        }.getOrDefault(false)
        if (!pending) {
            lastIdentityReturnNotConsumedReason = null
            return
        }
        val reason = when {
            observation.status != ObservationStatus.CONFIRMED_PAGE || snapshot == null ->
                "PAGE_EVIDENCE_UNAVAILABLE"
            recoveryContext.pending -> "RECOVERY_TRANSACTION_PENDING"
            !sessionRunStarted -> "BUSINESS_RUN_NOT_STARTED"
            receivedGreetingsTaskActive -> "RECEIVED_GREETINGS_TASK_ACTIVE"
            !ownsTask(PRIVATE_MESSAGES_TASK_ID) -> "PRIVATE_TASK_LEASE_MISSING"
            snapshot.packageName != targetPackage() -> "TARGET_PACKAGE_MISMATCH"
            snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE -> "CHAT_DETAIL_NOT_CONFIRMED"
            sessionManagerState != SessionManagerState.READING_SESSION -> "SESSION_NOT_READING"
            !hasBoundPrivateDetailRecoverySession() -> "SOURCE_SESSION_BINDING_MISSING"
            currentSessionHeaderMatchMode(snapshot) == null -> "CHAT_TITLE_MISMATCH"
            windowSessionKey(snapshot) == null -> "WINDOW_SESSION_KEY_MISSING"
            !isVerifiedRecoveredPrivateDetail(snapshot) -> "TARGET_SESSION_NOT_VERIFIED"
            else -> null
        }
        if (reason != null && reason != lastIdentityReturnNotConsumedReason) {
            lastIdentityReturnNotConsumedReason = reason
            onLog(
                "AI 身份回接[REQUEST_NOT_CONSUMED]：result=NOT_CONSUMED；reason=$reason；" +
                    "pending=true；route=${recoveryContext.routeId ?: "NONE"}；" +
                    "保留请求，不启动 OCR 或草稿"
            )
        }
    }

    /** Page recovery is complete; independently decide whether the scan can continue. */
    private fun reanchorRestoredMessageList(inspection: ChatPageSnapshot) {
        if (!messageListCheckpointRestored) return
        val executor = privateMessagesTraversalTaskExecutor
        executor.clearRecoveryViewportEvidence()
        if (executor.scanContinuityRecoveryPending) {
            onLog("消息列表检查点恢复[RESCAN_RETAINED]：原安全起点尚未确认；由最新已读边界或独立物理末端证据决定方向")
        } else if (executor.isRestoredViewportCompatible(inspection) &&
            (executor.lastStableSessionListVisibleKeys.orEmpty().isEmpty() ||
                executor.hasRestoredViewportContinuity(inspection))
        ) {
            executor.recordVerifiedViewport(inspection)
            // A restored top assertion needs fresh position evidence as well.
            if (executor.atTop && executor.collectionBoundaryEvidence(inspection, toTop = true) != true) {
                executor.atTop = false
            }
            onLog("消息列表检查点恢复[CONTINUE]：原视口连续；保留本轮记录与遍历游标")
        } else {
            val continuous = executor.hasRestoredViewportContinuity(inspection)
            val reanchor = executor.reanchorRestoredCheckpoint(inspection)
            if (continuous && reanchor.status != PrivateMessagesTraversalTaskExecutor.RestoreReanchorStatus.AMBIGUOUS) {
                if (reanchor.status != PrivateMessagesTraversalTaskExecutor.RestoreReanchorStatus.REANCHORED) {
                    executor.recordVerifiedViewport(inspection)
                }
                executor.atTop = false
                onLog("消息列表检查点恢复[REANCHOR]：status=${reanchor.status.name}；邻接身份连续；重新获取当前行，不复用旧坐标")
            } else if (executor.initialInboxTopPositioningCompleted) {
                executor.beginScanContinuityRecovery()
                onLog("消息列表检查点恢复[SAFE_RESCAN_REQUIRED]：status=${reanchor.status.name}；原位置未证明；保留原锚点、本轮记录和方向；等待新鲜已读边界或物理末端独立验证")
            } else {
                // The startup top gate has not yet admitted a scan. Keep its
                // original positioning policy; this is not a restored scan origin.
                executor.atTop = false
                onLog("消息列表检查点恢复[STARTUP_POSITION_WAIT]：初始顶部尚未确认；保留原准入流程")
            }
        }
        messageListCheckpointRestored = false
        privateMessagesCheckpointRepository?.save(executor.checkpoint())
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
        if (!sessionRunStarted) return
        if (isPrivateFollowUpActive() && event.inputOriginDecision?.origin == InputOrigin.PHYSICAL_USER) {
            privateChatFollowUpPort?.abort("PRIVATE_FOLLOW_UP_USER_INTERACTION")
            onPrivateFollowUpTopLevelObserved()
            onLog("PRIVATE_FOLLOW_UP USER_INTERACTION;checkpointRetained=true;noReplay=true")
            // Fall through to existing physical-interaction handling, not a new recovery route.
        }
        pageRecoveryVerificationGate.reset(PRIVATE_STARTUP_DETAIL_OWNERSHIP_GATE)
        val observedPhysicalInterruption = event.inputOriginDecision?.origin == InputOrigin.PHYSICAL_USER &&
            currentSessionBinding?.observedDetailTarget != null
        if (observedPhysicalInterruption) {
            if (currentSessionBinding?.observedBusinessAdmitted == true) {
                requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, "PRIVATE_DETAIL_USER_INTERACTION")
            } else {
                retireObservedPrivateDetail("PHYSICAL_INTERACTION")
            }
        }
        val recoveryContextActive = observedPhysicalInterruption || receivedGreetingsTaskActive || pairWake != null ||
            messageListRecoveryVerificationPending ||
            recoveryContext.pending || incompleteUnreadRelocationWait != null || userInteractionActive
        if (!recoveryContextActive) return
        // A touch invalidates a parked relocation intent. Only a new confirmed
        // observation after the existing quiet gate may schedule another one.
        incompleteUnreadRelocationWait = null
        receivedGreetingsUserActive = true
        userInteractionActive = true
        recoveryTransaction.dispatch(RecoveryTransactionEvent.UserInteractionStarted)
        // A user touch breaks the continuity of the private-to-greetings two-snapshot handoff.
        pageRecoveryVerificationGate.reset(CROSS_TASK_GREETING_HANDOFF_GATE)
        if (pairWake != null) pageRecoveryVerificationGate.reset(NEXT_PAIR_WAKE_GATE)
        if (awaitingNormalInboxAfterGreetingsReturn) {
            holdGreetingsCompletionReturn("USER_INTERACTION")
        }
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
                userInteractionActive = false
                recoveryTransaction.dispatch(RecoveryTransactionEvent.UserInteractionEnded)
                onLog("收到的招呼恢复[USER_QUIET]：用户已静默${quietMs}ms；开始双快照页面指纹复核")
                scheduleFullInspectionAfter(0L)
            }
        }
    }

    /**
     * Called on the runtime scheduler for the original, still-pending send attempt.
     * This is an intent for a fresh read, not confirmation or UI operation authority.
     */
    fun onSendConfirmationObservationRequested() {
        if (!isAiReplyActive() || !sessionRunStarted || !isSendConfirmationPending()) return
        if (recoveryContext.pending) {
            if (isReceivedGreetingsRecoveryPending()) {
                // A platform completion is only a read intent. Do not replace the retained
                // checkpoint verifier or start OCR under the invalidated chat binding.
                onLog("收到招呼发送确认[RECOVERY_READ_ONLY_WAIT]：保留原发送证据；等待已有恢复验证，不重发")
                if (!receivedGreetingsCheckpointVerifier.hasActiveVerification()) {
                    requestPageRouteObservation()
                }
            }
            return
        }
        onLog("AI 发送确认[ACTIVE_RECHECK_REQUESTED]：发送手势完成；立即安排现有只读页面观察")
        scheduleFullInspectionAfter(0L, AiReplyPageReadPurpose.MESSAGE_READ_RETRY)
    }

    /** Returns the latest verified greeting observation as the immediate pre-send diff baseline. */
    fun captureGreetingPreSendOutgoingKeys(expectedWindowSessionKey: String): Set<String>? {
        if (!receivedGreetingsTaskActive || !sessionRunStarted ||
            recoveryContext.pending || !ownsTask(RECEIVED_GREETINGS_TASK_ID)
        ) return null
        val batch = latestGreetingMessageBatch
            ?.takeIf { it.windowSessionKey == expectedWindowSessionKey }
            ?: return null
        return batch.messages
            ?.asSequence()
            ?.filter { it.direction == com.freedom.features.aireply.domain.message.ChatMessageDirection.OUTGOING }
            ?.map { "${it.stableLogicalMessageId}|${it.effectiveContentRevision}" }
            ?.toSet()
    }

    /** Returns the latest verified private-detail observation as the immediate pre-send baseline. */
    fun capturePrivatePreSendOutgoingKeys(expectedWindowSessionKey: String): Set<String>? {
        if (receivedGreetingsTaskActive || !sessionRunStarted ||
            !ownsTask(PRIVATE_MESSAGES_TASK_ID) || sessionManagerState != SessionManagerState.READING_SESSION
        ) return null
        val batch = latestPrivateMessageBatch
            ?.takeIf { it.windowSessionKey == expectedWindowSessionKey }
            ?: return null
        return batch.messages.asSequence()
            .filter { it.direction == com.freedom.features.aireply.domain.message.ChatMessageDirection.OUTGOING }
            .map { "${it.stableLogicalMessageId}|${it.effectiveContentRevision}" }
            .toSet()
    }

    /** Applies a newly acquired page snapshot after ordinary debounce or verified user stability. */
    /** Consumes the semantic observation result; the snapshot remains evidence only. */
    private fun handleInspectionSnapshot(observation: ObservationResult) {
        if (isPrivateFollowUpActive()) return
        val incomingInspection = observation.snapshot ?: return
        if (observation.status == ObservationStatus.TEMPORARILY_UNAVAILABLE) return
        val previous = lastPageInspection
        val inspection = stabilizeIncomingPageSnapshot(incomingInspection)
        val pageFingerprint = PageFingerprint(
            packageName = inspection.packageName,
            activityClass = inspection.activityClass,
            semanticPage = inspection.pageKind.name,
            adapterFingerprint = runCatching {
                chatPagePort.receivedGreetingsRecoveryFingerprint()
            }.getOrNull()
        )
        val publishedRunGeneration = currentRunGeneration()
        if (publishedRunGeneration != currentRunGeneration() || !isAiReplyActive()) return
        val matchedRouteId = if (pageFingerprint.packageName == targetPackage()) {
            sharedPageObservationCoordinator.matchRecoveryAdapter(
                pageFingerprint, setOf(PRIVATE_MESSAGES_TASK_ID, RECEIVED_GREETINGS_TASK_ID)
            )?.routeId
        } else null

        // The observed detail is a source of the same session binding, not another task.
        if (currentSessionBinding?.observedDetailTarget != null) {
            if (handleObservedPrivateDetail(observation, inspection, matchedRouteId)) return
        }

        if (sessionRunStarted && receivedGreetingsTaskActive &&
            !ownsTask(RECEIVED_GREETINGS_TASK_ID) && !awaitingNormalInboxAfterGreetingsReturn &&
            !recoveryContext.pending
        ) {
            // Establish a checkpoint for the known interrupted task, never claim a lease
            // directly from this observation. Existing recovery gates collect fresh proof.
            requestUnifiedPauseAndCheckpoint(RECEIVED_GREETINGS_TASK_ID, "GREETING_TASK_LEASE_LOST")
            sessionManagerState = SessionManagerState.PAUSED
            lastPageInspection = inspection
            onLog("收到的招呼恢复[LEASE_LOST_READ_ONLY]：已读取当前页面；等待既有恢复验证重新确认操作权")
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return
        }

        // A detail-origin visit may be retired before identity capture finishes. Its
        // deferred list recovery must still earn independent list proof on return.
        if (privateListRecoveryReasonAfterObservedDetail != null &&
            !recoveryContext.pending && sessionRunStarted && !receivedGreetingsTaskActive &&
            currentSessionBinding == null && currentSessionKey == null &&
            matchedRouteId == messageListRecoveryRoute.routeId &&
            observation.status == ObservationStatus.CONFIRMED_PAGE
        ) {
            if (requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, "OBSERVED_DETAIL_RETURN")) {
                scheduleFullInspectionAfter(0L)
                return
            }
        }

        // A pending recovery owns verification of this observation, including the existing
        // verified private-to-greetings handoff below. Ordinary list admission would reject
        // the pending recovery and return before its verifier could consume this snapshot.
        if (recoveryContext.pending && sessionRunStarted && !receivedGreetingsTaskActive) {
            receivedGreetingsPageProbePending = false
            receivedGreetingsProbeOrdinaryInboxConfirmed = false
            receivedGreetingsProbePrivateRouteWon = false
            pageObservationCoordinator.resetReceivedGreetingsWatch()
            // Preserve the existing private-route arbitration when a prior greetings
            // transition suspended private ownership. Resolve only its read-only evidence;
            // recovery still owns the READY, checkpoint and lease verification below.
            if (receivedGreetingsOwnershipRecheckPending &&
                observation.status == ObservationStatus.CONFIRMED_PAGE &&
                inspection.packageName == targetPackage() &&
                inspection.pageKind == ChatPageKind.CHAT_LIST &&
                matchedRouteId == messageListRecoveryRoute.routeId
            ) {
                when (observeReceivedGreetingsPageForUnifiedValidation()) {
                    ReceivedGreetingsPageArrivalStatus.NOT_TARGET,
                    ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION,
                    ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED -> {
                        receivedGreetingsOwnershipRecheckPending = false
                    }
                    else -> Unit
                }
            }
        }

        // Resolve ordinary greetings transitions only outside an active recovery transaction.
        if ((receivedGreetingsPageProbePending || receivedGreetingsOwnershipRecheckPending) &&
            sessionRunStarted &&
            !recoveryContext.pending &&
            !receivedGreetingsTaskActive &&
            receivedGreetingsTaskFlow.supportsWorkflow()
        ) {
            receivedGreetingsPageProbePending = false
            val arrivalStatus = pageObservationCoordinator.onPageSnapshotAvailable(
                observeReceivedGreetingsPageForUnifiedValidation()
            )
            receivedGreetingsProbeOrdinaryInboxConfirmed =
                    observation.status == ObservationStatus.CONFIRMED_PAGE &&
                    inspection.packageName == targetPackage() &&
                    inspection.pageKind == ChatPageKind.CHAT_LIST &&
                    matchedRouteId == messageListRecoveryRoute.routeId
            receivedGreetingsProbePrivateRouteWon = false
            // Ordinary first admission must share the same live Android readiness
            // evidence as dispatch. Recovery and private-route arbitration keep
            // their existing independent gates and ordering.
            if (arrivalStatus == ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED &&
                !receivedGreetingsProbeOrdinaryInboxConfirmed && !recoveryContext.pending
            ) {
                val readiness = runCatching { inspectAndroidRecoveryReadiness(inspection) }.getOrNull()
                val admission = AndroidRecoveryExecutionGate.assess(
                    snapshot = inspection, expectedPackage = targetPackage(), evidence = readiness
                )
                if (!admission.allowed) {
                    if (ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
                        pauseForReceivedGreetingsPageTransition(arrivalStatus)
                    }
                    receivedGreetingsPageProbePending = true
                    onLog("GREETING_FIRST_ACTION_READINESS_WAIT reason=${admission.reasonCode}；只读复核，不启动遍历")
                    scheduleFullInspectionAfter(
                        RecoveryScheduler.readOnlyRetryDelayMs(1),
                        AiReplyPageReadPurpose.RECEIVED_GREETINGS
                    )
                    return
                }
            }
            when (arrivalStatus) {
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED -> {
                    startReceivedGreetingsTaskFromConfirmedPage(
                        ConfirmedGreetingsListEvidence.Observed(arrivalStatus)
                    )
                    if (!receivedGreetingsProbePrivateRouteWon) return
                    receivedGreetingsProbePrivateRouteWon = false
                }
                ReceivedGreetingsPageArrivalStatus.INTERMEDIATE_PAGE,
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING -> {
                    pauseForReceivedGreetingsPageTransition(arrivalStatus)
                    return
                }
                ReceivedGreetingsPageArrivalStatus.NOT_TARGET,
                ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION,
                ReceivedGreetingsPageArrivalStatus.UNKNOWN -> {
                    if (receivedGreetingsOwnershipRecheckPending) {
                        val privateInboxConfirmed =
                            arrivalStatus in setOf(
                                ReceivedGreetingsPageArrivalStatus.NOT_TARGET,
                                ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION
                            ) &&
                                observation.status == ObservationStatus.CONFIRMED_PAGE &&
                                inspection.packageName == targetPackage() &&
                                inspection.pageKind == ChatPageKind.CHAT_LIST &&
                                matchedRouteId == messageListRecoveryRoute.routeId
                        if (!privateInboxConfirmed) {
                            onLog(
                                "页面操作权仲裁[WAITING]：收到招呼过渡尚未解除；" +
                                    "arrival="+arrivalStatus.name+"；page="+inspection.pageKind.name+"；" +
                                    "只读复查，私人消息任务不取得操作权"
                            )
                            return
                        }
                        onLog(
                            "页面操作权仲裁[PRIVATE_MESSAGES_CONFIRMED]：普通消息列表已确认，" +
                                "收到招呼观察明确返回NOT_TARGET；允许私人消息任务重新申请操作权"
                        )
                        if (startupReadinessCoordinator.resumeAfterPageRecovery()) return
                        receivedGreetingsOwnershipRecheckPending = false
                    }
                }
            }
        }

        // A selected private-list startup may be interrupted before its first lease claim.
        // Admit the verified chat subpage to that SAME task, without pretending a list
        // initialization, selected row, contact binding or unfinished transaction exists.
        if (handlePrivateStartupDetailOwnership(observation, inspection, matchedRouteId)) return

        if (sessionRunStarted && pairWake != null && !receivedGreetingsTaskActive &&
            !recoveryContext.pending && !messageListRecoveryVerificationPending &&
            !isSendConfirmationPending() && !isConversationDraftPending()
        ) {
            observePairWake(observation, inspection, matchedRouteId)
            return
        }

        if (sessionRunStarted && awaitingNormalInboxAfterGreetingsReturn) {
            if (isGreetingsCompletionReturnRecovery()) {
                recoverGreetingsCompletionReturn(observation, inspection, matchedRouteId)
                return
            }
            if (!recoveryContext.pending) {
                val confirmedInbox = observation.status == ObservationStatus.CONFIRMED_PAGE &&
                    inspection.packageName == targetPackage() &&
                    matchedRouteId == messageListRecoveryRoute.routeId &&
                    inspection.pageKind == ChatPageKind.CHAT_LIST
                val verifiedSource = observation.status == ObservationStatus.CONFIRMED_PAGE &&
                    inspection.packageName == targetPackage() &&
                    inspection.pageKind == ChatPageKind.NON_CHAT &&
                    completionReturnSourceFingerprint() != null
                if ((!confirmedInbox && !verifiedSource) || !ownsTask(RECEIVED_GREETINGS_TASK_ID)) {
                    // A test/legacy adapter may not expose the optional source proof during
                    // ordinary traversal. A known unchanged greetings route still uses its
                    // own independent BACK gate, but an unknown/foreign route must recover.
                    val knownList = observation.status == ObservationStatus.CONFIRMED_PAGE &&
                        inspection.packageName == targetPackage() &&
                        inspection.pageKind == ChatPageKind.NON_CHAT &&
                        (matchedRouteId == receivedGreetingsRecoveryRoute.routeId ||
                            (previous?.pageKind == ChatPageKind.NON_CHAT &&
                                !pageIdentityChanged(previous, inspection)))
                    if (!knownList || !ownsTask(RECEIVED_GREETINGS_TASK_ID)) {
                        holdGreetingsCompletionReturn("UNVERIFIED_PAGE")
                        return
                    }
                }
            }
        }

        // Both normal completion and suspended-private recovery cross one handoff gate.
        // A CHAT_LIST candidate alone never grants a private task lease.
        val inboxReturnCandidate = sessionRunStarted && awaitingNormalInboxAfterGreetingsReturn &&
            inspection.pageKind == ChatPageKind.CHAT_LIST
        val verifiedGreetingsInboxReturn = if (inboxReturnCandidate) {
            if (!verifyGreetingsToPrivateHandoff(observation, inspection, matchedRouteId)) {
                lastPageInspection = inspection
                return
            }
            true
        } else {
            pageRecoveryVerificationGate.reset(GREETINGS_TO_PRIVATE_HANDOFF_GATE)
            false
        }
        // The original private checkpoint must take precedence over a fresh private round.
        if (verifiedGreetingsInboxReturn &&
            tryRearmSuspendedPrivateRecovery(observation, inspection, matchedRouteId)
        ) {
            lastPageInspection = inspection
            return
        }
        // A list BACK may first reveal its verified entry-card surface. Only this
        // transaction-scoped adapter proof can admit that normal intermediate transition.
        val verifiedGreetingsReturnIntermediate = sessionRunStarted &&
            receivedGreetingsTaskActive && awaitingNormalInboxAfterGreetingsReturn &&
            ownsTask(RECEIVED_GREETINGS_TASK_ID) && !recoveryContext.pending &&
            !isSendConfirmationPending() && !isConversationDraftPending() &&
            observation.status == ObservationStatus.CONFIRMED_PAGE &&
            inspection.packageName == targetPackage() && inspection.pageKind == ChatPageKind.NON_CHAT &&
            receivedGreetingsTaskFlow.isCompletionReturnIntermediate()
        val activeTaskId = taskOwnership.activeTaskId()
        val activeRouteId = recoveryRouteIdForTask(activeTaskId)
        // Route recovery is allowed to suppress PAGE_ROUTE_CHANGED only when the
        // unified accessibility layer supplied a matching generic source result.
        // It must not consult expectedPrivateSessionDetailNavigation here.
        val selfInitiatedTransition = pendingNavigationSourceResult?.takeIf {
            selfNavigationMatchesInspection(it, inspection)
        }
        if (selfInitiatedTransition != null) {
            pendingNavigationSourceResult = null
            if (recoveryContext.pending &&
                recoveryContext.sourceTaskId == RECEIVED_GREETINGS_TASK_ID &&
                receivedGreetingsCheckpointForUnified != null
            ) {
                // A navigation attribution is not a freshly verified greetings-list checkpoint.
                // Preserve the recovery gate until two new fingerprints and adapter confirmation.
                onLog("收到招呼通用检查点[SELF_NAVIGATION_NOT_PROOF]：保持检查点复核；导航事件不授予UI操作权")
            } else if (recoveryContext.pending) {
                // A self-navigation match only identifies the source of a transition.
                // It cannot authorize UI execution while a recovery transaction is pending:
                // the existing route and two-fresh-snapshot gate must finish first.
                onLog(
                    "统一页面恢复[SELF_NAVIGATION_NOT_PROOF]：" +
                        "actionId=${selfInitiatedTransition.actionId}；" +
                        "targetActivity=${inspection.activityClass}；" +
                        "保持原检查点和恢复事务；等待目标路由双快照复核后再申请操作授权"
                )
            } else {
                onLog(
                    "统一页面恢复[SELF_NAVIGATION_CONSUMED]：" +
                        "actionId=${selfInitiatedTransition.actionId}；" +
                        "targetActivity=${inspection.activityClass}；" +
                        "跳过PAGE_ROUTE_CHANGED"
                )
            }
        }
        val unmatchedReturnToOwnedRoute = unmatchedNavigationObservationPending &&
            selfInitiatedTransition == null &&
            previous != null &&
            inboxPageRecognizer.isChatDetail(previous) &&
            // The unbound startup detail never opened a visit or advanced the list cursor.
            // Returning from it must still run the original list startup verification.
            !(pageSelectedPrivateStartupVerificationPending && currentSessionCandidate == null &&
                currentSessionBinding == null && matchedRouteId == messageListRecoveryRoute.routeId) &&
            matchedRouteId == activeRouteId
        // A private-message row tap moves from the owned inbox route into its chat-detail
        // subpage. That subpage is part of the active task's normal workflow, not an UNKNOWN
        // recovery destination. Keep the state-based classification here; do not infer it from
        // stale expected-navigation metadata or suppress unrelated route changes.
        val openingPrivateSessionDetail = activeTaskId == PRIVATE_MESSAGES_TASK_ID &&
            sessionManagerState == SessionManagerState.OPENING_SESSION &&
            previous?.pageKind == ChatPageKind.CHAT_LIST &&
            inspection.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE
        val activePrivateChatDetail = activeTaskId == PRIVATE_MESSAGES_TASK_ID &&
            !receivedGreetingsTaskActive &&
            inspection.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            currentSessionKey != null &&
            currentSessionWindowKey == windowSessionKey(inspection)
        // The received-greetings list is deliberately exposed as NON_CHAT, not CHAT_LIST.
        // A live avatar identity profile is an adapter-owned subpage, even when a transient
        // dialog window advances the page generation and the generic navigation receipt
        // cannot be matched to this later observation. Never accept an arbitrary NON_CHAT:
        // the active task lease, NAVIGATING stage, exact target package and a fresh,
        // adapter-verified profile structure must all agree. This neither grants a new
        // UI lease nor bypasses the adapter's independent profile/BACK safety checks.
        // Preserve the existing greetings entry-profile-card transition from the ordinary
        // CHAT_LIST. It is distinct from the avatar identity profile reached *after* the
        // dedicated greetings list (which is exposed as NON_CHAT).
        val receivedGreetingsEntryCardTransition = activeTaskId == RECEIVED_GREETINGS_TASK_ID &&
            receivedGreetingsTaskActive &&
            previous?.pageKind == ChatPageKind.CHAT_LIST &&
            inspection.pageKind == ChatPageKind.NON_CHAT
        val receivedGreetingsIdentityProfileTransition = activeTaskId == RECEIVED_GREETINGS_TASK_ID &&
            receivedGreetingsTaskActive &&
            ownsTask(RECEIVED_GREETINGS_TASK_ID) &&
            previous != null && previous.packageName == targetPackage() &&
            inspection.packageName == targetPackage() &&
            inspection.pageKind == ChatPageKind.NON_CHAT &&
            receivedGreetingsTaskFlow.traversalStatus() == ReceivedGreetingsTraversalStatus.NAVIGATING &&
            runCatching {
                chatPagePort.isReceivedGreetingsIdentityProfileNavigationActive(inspection)
            }.getOrDefault(false)
        // A verified greeting-row tap opens its chat directly from the greetings workflow.
        // Admit that destination only after the existing ID, header, window, and task-lease proof
        // succeeds; this preserves the greeting task instead of routing the chat through recovery.
        val receivedGreetingsChatDetailTransition =
            activeTaskId == RECEIVED_GREETINGS_TASK_ID &&
                receivedGreetingsTaskActive &&
                ownsTask(RECEIVED_GREETINGS_TASK_ID) &&
                observation.status == ObservationStatus.CONFIRMED_PAGE &&
                previous != null && previous.packageName == targetPackage() &&
                (previous.pageKind == ChatPageKind.NON_CHAT ||
                    (previous.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
                        greetingChatBinding == null &&
                        hasOffscreenGreetingChatLocators(previous) &&
                        previous.conversationIdentityKey() == inspection.conversationIdentityKey())) &&
                inspection.packageName == targetPackage() &&
                inspection.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
                inspectGreetingChatAdmission(inspection, requireDetailReady = true) is GreetingChatAdmission.Ready
        // A region refresh may clear the previous snapshot's node locators without
        // leaving the chat. Use the existing binding and fresh identity/window/lease
        // proof, not the missing previous locator, to distinguish that from a route change.
        val activeReceivedGreetingsChatDetail =
            activeTaskId == RECEIVED_GREETINGS_TASK_ID &&
                observation.status == ObservationStatus.CONFIRMED_PAGE &&
                greetingBindingRejectReason(inspection) == null
        val receivedGreetingsWorkflowTransition =
            receivedGreetingsEntryCardTransition ||
                receivedGreetingsIdentityProfileTransition ||
                receivedGreetingsChatDetailTransition ||
                activeReceivedGreetingsChatDetail
        if (activeReceivedGreetingsChatDetail && previous != null && pageIdentityChanged(previous, inspection)) {
            onLog("收到招呼业务导航[CHAT_DETAIL_REVALIDATED]：原会话身份、窗口和任务租约仍有效；不创建列表恢复")
        }
        if (receivedGreetingsChatDetailTransition) {
            onLog(
                "收到招呼业务导航[CHAT_DETAIL_VERIFIED]：联系人ID、聊天标题、窗口和任务租约已复核；" +
                    "保留收到招呼任务继续读取消息"
            )
        }
        if (receivedGreetingsIdentityProfileTransition && previous != null && pageIdentityChanged(previous, inspection)) {
            // A source ticket may be valid but unmatchable after a dialog.h window change.
            // Discard only a ticket for this *independently verified* destination; it is
            // not evidence for another page or an authorization to execute BACK.
            val attachedToProfile = pendingNavigationSourceResult?.targetActivityClass == inspection.activityClass
            if (attachedToProfile) pendingNavigationSourceResult = null
            onLog(
                "收到招呼业务导航[IDENTITY_PROFILE_VERIFIED]：原任务与UI操作权有效；" +
                    "独立资料页结构已确认；导航来源关联=$attachedToProfile；" +
                    "继续适配器原有资料采集和安全BACK流程"
            )
        }
        // A dedicated greetings-list snapshot does not necessarily carry the generic
        // navigation ticket's window locator. The accepted detail BACK already belongs
        // to the adapter's existing return transaction; do not replace its double-snapshot
        // confirmation with an unrelated UNKNOWN recovery just because attribution is absent.
        val pendingGreetingDetailReturn = unmatchedReturnToOwnedRoute &&
            activeTaskId == RECEIVED_GREETINGS_TASK_ID &&
            receivedGreetingsTaskActive && ownsTask(RECEIVED_GREETINGS_TASK_ID) &&
            !awaitingNormalInboxAfterGreetingsReturn && !recoveryContext.pending &&
            !isSendConfirmationPending() && !isConversationDraftPending() &&
            observation.status == ObservationStatus.CONFIRMED_PAGE &&
            inspection.packageName == targetPackage() && inspection.pageKind == ChatPageKind.NON_CHAT &&
            matchedRouteId == receivedGreetingsRecoveryRoute.routeId &&
            previous?.let { source ->
                runCatching {
                    chatPagePort.isReceivedGreetingsDetailReturnNavigationActive(source)
                }.getOrDefault(false)
            } == true
        if (pendingGreetingDetailReturn) {
            onLog("收到招呼业务导航[DETAIL_RETURN_PENDING]：原BACK来源、列表路由和任务租约已复核；保留原返回事务等待双快照")
        }
        // The generic chat-detail recovery route matches every CHAT_DETAIL_CANDIDATE;
        // it does not prove a different task owns this specific chat. Allow only that
        // shared structural route (or no route) to reach the existing bounded adapter
        // transaction check. Confirmed list routes and unrelated task routes still win.
        val awaitingGreetingsNavigationRoute = matchedRouteId == null ||
            (matchedRouteId == chatDetailRecoveryRoute.routeId &&
                inspection.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE)
        if (awaitingGreetingsNavigationRoute && !receivedGreetingsWorkflowTransition &&
            previous != null && previous.packageName == targetPackage() &&
            pageIdentityChanged(previous, inspection) &&
            waitForPendingGreetingsNavigationEvidence(inspection)
        ) {
            // Stabilization already published the incoming snapshot. Keep the original
            // source identity for the next route comparison, but retire its locators:
            // the unconfirmed destination must not become the comparison baseline or
            // allow a region refresh/action to reuse the departed window's targets.
            lastPageInspection = previous.copy(
                messageListTarget = null,
                inputFieldTarget = null,
                sessionListNavigationTarget = null,
                chatListCandidates = emptyList()
            )
            return
        }

        val shouldCreateRouteRecovery = sessionRunStarted && activeTaskId != null &&
            !recoveryContext.pending && selfInitiatedTransition == null &&
            !openingPrivateSessionDetail && !activePrivateChatDetail && !receivedGreetingsWorkflowTransition &&
            !verifiedGreetingsInboxReturn && !verifiedGreetingsReturnIntermediate &&
            previous != null && matchedRouteId != activeRouteId &&
            pageIdentityChanged(previous, inspection)
        // The submitted send belongs to its original conversation, not to the
        // current inbox UI. A confirmed inbox must reach the existing checkpoint
        // handoff even when the last observation was already a list.
        val privateSendReturnedToInbox = sessionRunStarted &&
            activeTaskId == PRIVATE_MESSAGES_TASK_ID && !receivedGreetingsTaskActive &&
            isSendConfirmationPending() && !isSendControlRecoveryPending() &&
            matchedRouteId == messageListRecoveryRoute.routeId &&
            inspection.packageName == targetPackage() && inspection.pageKind == ChatPageKind.CHAT_LIST &&
            observation.status == ObservationStatus.CONFIRMED_PAGE
        val interruptedPrivateVisitReturned = sessionRunStarted && !recoveryContext.pending &&
            activeTaskId == PRIVATE_MESSAGES_TASK_ID && !receivedGreetingsTaskActive &&
            sessionManagerState == SessionManagerState.READING_SESSION &&
            privateMessagesTraversalTaskExecutor.currentUnfinishedSession() != null &&
            matchedRouteId == messageListRecoveryRoute.routeId &&
            observation.status == ObservationStatus.CONFIRMED_PAGE &&
            !isSendConfirmationPending() && !isSendControlRecoveryPending()
        if ((shouldCreateRouteRecovery || interruptedPrivateVisitReturned ||
                (privateSendReturnedToInbox && !recoveryContext.pending) ||
                (unmatchedReturnToOwnedRoute && !pendingGreetingDetailReturn)) &&
            activeTaskId != null
        ) {
            if (awaitingNormalInboxAfterGreetingsReturn && activeTaskId == RECEIVED_GREETINGS_TASK_ID) {
                holdGreetingsCompletionReturn("PAGE_ROUTE_CHANGED")
                unmatchedNavigationObservationPending = false
                return
            }
            requestUnifiedPauseAndCheckpoint(activeTaskId!!, "PAGE_ROUTE_CHANGED")
            unmatchedNavigationObservationPending = false
        } else if (unmatchedNavigationObservationPending &&
            matchedRouteId == activeRouteId
        ) {
            // The unattributed transition returned to the already-owned route. It was observed,
            // but it did not establish a new recovery destination.
            unmatchedNavigationObservationPending = false
        }

        // A confirmed list can replace the execution destination of an interrupted
        // identity/detail read, but cannot replace its durable business checkpoint.
        // Selection alone grants no lease; the existing list/greetings verifier
        // below must still independently prove the destination.
        if (recoveryContext.pending &&
            recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
            recoveryContext.routeId == chatDetailRecoveryRoute.routeId &&
            (matchedRouteId == messageListRecoveryRoute.routeId ||
                matchedRouteId == receivedGreetingsRecoveryRoute.routeId) &&
            observation.status == ObservationStatus.CONFIRMED_PAGE &&
            !receivedGreetingsTaskActive && !isSendControlRecoveryPending() &&
            (!isSendConfirmationPending() || privateSendReturnedToInbox)
        ) {
            if (!ensureAndroidRecoveryReady(inspection, "PRIVATE_DETAIL_ROUTE_HANDOFF") ||
                !retargetInterruptedPrivateDetail(observation.observationGeneration, privateSendReturnedToInbox)
            ) {
                lastPageInspection = inspection
                scheduleFullInspectionAfter(350L)
                return
            }
        }

        if (tryVerifiedPrivateRecoveryToGreetingsHandoff(observation, inspection, matchedRouteId,
                pageFingerprint.adapterFingerprint)
        ) return

        if (recoveryContext.pending) {
            val targetRoute = matchedRouteId
            if (targetRoute == null) recoveryContext.invalidateFingerprint()
            val privateDetailRecovery =
                recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
                    recoveryContext.routeId == chatDetailRecoveryRoute.routeId &&
                    !receivedGreetingsTaskActive
            val loadedMessageCheckpoint = messageListCheckpointForUnified
            val sharedMessageListRecovery =
                recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
                    loadedMessageCheckpoint != null && !receivedGreetingsTaskActive
            val loadedGreetingsCheckpoint = receivedGreetingsCheckpointForUnified
            val sharedGreetingsRecovery =
                recoveryContext.sourceTaskId == RECEIVED_GREETINGS_TASK_ID &&
                    loadedGreetingsCheckpoint != null
            if (sharedGreetingsRecovery &&
                (targetRoute != receivedGreetingsRecoveryRoute.routeId ||
                    inspection.pageKind != ChatPageKind.NON_CHAT ||
                    observation.status != ObservationStatus.CONFIRMED_PAGE ||
                    recoveryContext.runGeneration != currentRunGeneration())
            ) {
                recoveryContext.invalidateFingerprint()
                receivedGreetingsCheckpointVerifier.reject(observation.observationGeneration)
                sessionManagerState = SessionManagerState.PAUSED
                onLog("收到招呼通用检查点[ROUTE_WAIT]：页面、路由或运行代次未确认；仅保留只读恢复观察")
                if (!receivedGreetingsCheckpointVerifier.hasActiveVerification()) {
                    scheduleFullInspectionAfter(EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS)
                }
                lastPageInspection = inspection
                return
            }
            if (privateDetailRecovery &&
                (targetRoute != chatDetailRecoveryRoute.routeId ||
                    observation.status != ObservationStatus.CONFIRMED_PAGE ||
                    recoveryContext.runGeneration != currentRunGeneration() ||
                    !isVerifiedRecoveredPrivateDetail(inspection))
            ) {
                recoveryContext.invalidateFingerprint()
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 聊天详情恢复[ROUTE_WAIT]：当前页面未独立确认原目标聊天；继续只读复核")
                scheduleFullInspectionAfter(EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS)
                lastPageInspection = inspection
                return
            }
            if (sharedMessageListRecovery &&
                (targetRoute != messageListRecoveryRoute.routeId ||
                    observation.status != ObservationStatus.CONFIRMED_PAGE ||
                    recoveryContext.runGeneration != currentRunGeneration())
            ) {
                // An unrelated route, ambiguous page or stale run must reset shared samples.
                recoveryContext.invalidateFingerprint()
                messageListCheckpointVerifier.reject(observation.observationGeneration)
                sessionManagerState = SessionManagerState.PAUSED
                onLog("消息列表统一检查点[ROUTE_WAIT]：页面路由或运行代次不匹配；保留检查点，仅只读重检")
                if (!messageListCheckpointVerifier.hasActiveVerification()) {
                    scheduleFullInspectionAfter(EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS)
                }
                lastPageInspection = inspection
                return
            }
            if (targetRoute == null) {
                onLog("统一页面恢复[ROUTE_WAIT]：当前目标应用页面尚未匹配业务指纹；继续等待")
                scheduleFullInspectionAfter(EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS)
                lastPageInspection = inspection
                return
            }
            // Android READY must precede BOTH fresh route samples and UI ownership.
            if (!ensureAndroidRecoveryReady(inspection, "UNIFIED_ROUTE")) {
                lastPageInspection = inspection
                return
            }
            val privateListFaultEvidence = if (recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
                targetRoute == messageListRecoveryRoute.routeId && !receivedGreetingsTaskActive
            ) assessPrivateListRecoveryFault(
                loadedMessageCheckpoint ?: privateMessagesTraversalTaskExecutor.checkpoint(),
                recoveryContext.reason, inspection
            ) else PrivateListRecoveryFaultEvidence.NOT_APPLICABLE
            // Legacy/no-repository callers must not bypass the same business gate.
            if (!sharedMessageListRecovery && !privateListFaultEvidence.allowsResume) {
                recoveryContext.invalidateFingerprint()
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
                sessionManagerState = SessionManagerState.PAUSED
                onLog("消息列表统一检查点[ORIGINAL_FAULT_WAIT]：reason=${recoveryContext.reason}；" +
                    "evidence=${privateListFaultEvidence.name}；保留原恢复事务")
                schedulePendingPrivateListObservation()
                lastPageInspection = inspection
                return
            }
            // Compare the business decision independently of the page fingerprint.
            // A changed scope must not borrow a prior positive sample.
            val verificationKey = listOf(targetRoute, recoveryStructuralKey(inspection),
                privateListFaultEvidence.name).joinToString("|")
            val sharedProof: RecoveryCheckpointVerificationResult.ResumeReady? =
                if (sharedMessageListRecovery) {
                    val checkpoint = requireNotNull(loadedMessageCheckpoint)
                    val result = runCatching {
                        messageListCheckpointVerifier.observe(
                            checkpoint = checkpoint,
                            checkpointId = requireNotNull(recoveryContext.checkpointId),
                            recoveryReason = requireNotNull(recoveryContext.reason),
                            restoreSource = messageListCheckpointRestoreSource,
                            snapshot = inspection,
                            pageFingerprint = recoveryStructuralKey(inspection),
                            routeVerified = targetRoute == messageListRecoveryRoute.routeId,
                            observationGeneration = observation.observationGeneration,
                            originalFaultEvidence = privateListFaultEvidence
                        )
                    }.onFailure {
                        onLog("消息列表统一检查点[ADAPTER_REJECTED]：原因=${it.javaClass.simpleName}；保持暂停")
                    }.getOrNull()
                    if (result !is RecoveryCheckpointVerificationResult.ResumeReady) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("消息列表统一检查点[VERIFYING]：等待连续两次新鲜同路由结构快照；不执行滚动、点击或导航")
                        if (!messageListCheckpointVerifier.hasActiveVerification()) {
                            scheduleFullInspectionAfter(350L)
                        }
                        lastPageInspection = inspection
                        return
                    }
                    result
                } else if (sharedGreetingsRecovery) {
                    val checkpoint = requireNotNull(loadedGreetingsCheckpoint)
                    // matchedRouteId was produced from the original adapter fingerprint for
                    // this exact inspection. Do not invent a page fingerprint from rows/labels.
                    val freshFingerprint = pageFingerprint.adapterFingerprint.orEmpty()
                    val result = runCatching {
                        receivedGreetingsCheckpointVerifier.observe(
                            checkpoint = checkpoint,
                            checkpointId = requireNotNull(recoveryContext.checkpointId),
                            recoveryReason = requireNotNull(recoveryContext.reason),
                            restoreSource = receivedGreetingsCheckpointRestoreSource,
                            snapshot = inspection,
                            pageFingerprint = freshFingerprint,
                            routeVerified = targetRoute == receivedGreetingsRecoveryRoute.routeId,
                            executionStatus = receivedGreetingsTaskFlow.traversalStatus(),
                            observationGeneration = observation.observationGeneration
                        )
                    }.onFailure {
                        onLog("收到招呼通用检查点[ADAPTER_REJECTED]：原因=${it.javaClass.simpleName}；保持暂停")
                    }.getOrNull()
                    if (result !is RecoveryCheckpointVerificationResult.ResumeReady) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("收到招呼通用检查点[VERIFYING]：等待连续两次新鲜招呼列表指纹；禁止自动导航和遍历")
                        if (!receivedGreetingsCheckpointVerifier.hasActiveVerification()) {
                            scheduleFullInspectionAfter(350L)
                        }
                        lastPageInspection = inspection
                        return
                    }
                    result
                } else {
                    // Unrelated routes and fixtures use the existing two-snapshot context gate,
                    // but cannot use an ambiguous observation or an old run generation as proof.
                    if (observation.status != ObservationStatus.CONFIRMED_PAGE ||
                        recoveryContext.runGeneration != currentRunGeneration()
                    ) {
                        recoveryContext.invalidateFingerprint()
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("统一页面恢复[GATE_WAIT]：页面状态或运行代次未确认；保留检查点并继续只读复核")
                        scheduleFullInspectionAfter(EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS)
                        lastPageInspection = inspection
                        return
                    }
                    if (!recoveryContext.observeTarget(targetRoute, verificationKey)) {
                        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
                        onLog("统一页面恢复[RECOVERY_VERIFYING]：目标路由=$targetRoute；第${recoveryContext.verificationSamples}/2次快照尚未稳定")
                        scheduleFullInspectionAfter(350L)
                        lastPageInspection = inspection
                        return
                    }
                    recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
                    recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
                    null
                }
            val targetTaskId = taskIdForRecoveryRoute(targetRoute)
            if (sharedGreetingsRecovery && sharedProof != null) {
                // Fresh list-container confirmation still belongs to the Momo adapter.
                // This is read-only and must finish before transferring the UI lease.
                val synchronized = receivedGreetingsTaskFlow.synchronizeRecoveryConfirmation()
                val fingerprintStillCurrent = runCatching {
                    chatPagePort.receivedGreetingsRecoveryFingerprint()
                }.getOrNull() == pageFingerprint.adapterFingerprint
                if (!synchronized || !fingerprintStillCurrent) {
                    receivedGreetingsCheckpointVerifier.deferResume()
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("收到招呼通用检查点[ADAPTER_CONFIRMATION_WAIT]：列表窗口或目标结构尚未重新确认；继续只读观察")
                    scheduleFullInspectionAfter(350L)
                    lastPageInspection = inspection
                    return
                }
            }
            if (!ensureAndroidRecoveryReady(inspection, "BEFORE_UI_OWNERSHIP")) {
                lastPageInspection = inspection
                return
            }
            if (targetTaskId == null || !claimTaskOwnership(targetTaskId, "统一页面路由双快照验证完成", verifiedHandoff = true)) {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("统一页面恢复[OWNERSHIP_REJECTED]：目标路由=$targetRoute；未取得UI操作权")
                if (sharedProof != null) {
                    if (sharedGreetingsRecovery) {
                        receivedGreetingsCheckpointVerifier.deferResume()
                    } else {
                        messageListCheckpointVerifier.deferOwnership()
                    }
                } else {
                    scheduleFullInspectionAfter(350L)
                }
                lastPageInspection = inspection
                return
            }
            // Close the gap between lease claim and checkpoint acknowledgement.
            if (!ensureAndroidRecoveryReady(inspection, "BEFORE_RESUME_ACK")) {
                lastPageInspection = inspection
                return
            }
            if (sharedProof != null) {
                if (sharedGreetingsRecovery) {
                    if (!receivedGreetingsCheckpointVerifier.completeResume()) {
                        sessionManagerState = SessionManagerState.PAUSED
                        receivedGreetingsCheckpointVerifier.deferResume()
                        onLog("收到招呼通用检查点[COMPLETE_REJECTED]：通用恢复事务尚未确认；继续只读复核")
                        scheduleFullInspectionAfter(350L)
                        lastPageInspection = inspection
                        return
                    }
                    recoveryContext.acknowledgeVerifiedCheckpoint(targetRoute, sharedProof)
                    receivedGreetingsRecoveryVerificationPending = false
                    receivedGreetingsRecoveryVerifiedForNextSnapshot = false
                    receivedGreetingsRecoveryRetryAttempt = 0
                    pageRecoveryVerificationGate.reset(RECEIVED_GREETINGS_RECOVERY_ROUTE_ID)
                } else {
                    // Page/lease proof does not yet admit a new scan origin.
                    sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                    if (!messageListCheckpointVerifier.completeResume()) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("消息列表统一检查点[COMPLETE_REJECTED]：尚未完成恢复事务确认；保持暂停")
                        messageListCheckpointVerifier.deferOwnership()
                        scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
                        lastPageInspection = inspection
                        return
                    }
                    recoveryContext.acknowledgeVerifiedCheckpoint(targetRoute, sharedProof)
                    messageListRecoveryVerificationPending = false
                    pageRecoveryVerificationGate.reset(MESSAGE_LIST_RECOVERY_ROUTE_ID)
                }
            }
            if (privateDetailRecovery) {
                val freshWindowKey = windowSessionKey(inspection)
                if (freshWindowKey == null ||
                    (freshWindowKey != currentSessionWindowKey &&
                        !runCatching {
                            chatSessionManagementPort.consumeIdentityReturnReinspectionRequest()
                        }.getOrDefault(false))
                ) {
                    recoveryContext.invalidateFingerprint()
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("AI 聊天详情恢复[WINDOW_REBIND_WAIT]：窗口发生变化且身份返回证据未确认；保持检查点")
                    scheduleFullInspectionAfter(350L)
                    lastPageInspection = inspection
                    return
                }
            }
            // Complete the binding handoff while the verified recovery checkpoint is
            // still held. An unconfirmed send has a separate read-only recovery path.
            if (privateDetailRecovery && !isSendConfirmationPending() &&
                !rebindVerifiedPrivateChat(inspection)
            ) {
                recoveryContext.invalidateFingerprint()
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 聊天详情恢复[CHAT_REBIND_WAIT]：原目标聊天绑定未完整复核；保留检查点并继续只读观察")
                scheduleFullInspectionAfter(350L)
                lastPageInspection = inspection
                return
            }
            if (targetTaskId == PRIVATE_MESSAGES_TASK_ID && targetRoute == messageListRecoveryRoute.routeId) {
                messageRecoveryRetryAttempt = 0
            }
            if (privateListFaultEvidence != PrivateListRecoveryFaultEvidence.NOT_APPLICABLE) {
                // Only after two admissible samples and the existing Android/lease gate
                // may the current observation reconcile the real business checkpoint.
                // The first sample was a disposable probe and never mutated the cursor.
                val executor = privateMessagesTraversalTaskExecutor
                executor.observeUnreadBlockers(inspection)
                executor.observeIncompleteUnreadEvidence(inspection)
                privateMessagesCheckpointRepository?.save(executor.checkpoint())
                onLog("消息列表统一检查点[ORIGINAL_FAULT_RECHECKED]：evidence=${privateListFaultEvidence.name}；" +
                    "unresolvedRows=${executor.unresolvedIncompleteUnreadKeys.size}；" +
                    "仅确认全局恢复准入，待复核会话不计入完成")
            }
            recoveryContext.markUiOwnershipTransferred()
            val completedRecoveryIdentity = recoveryTransaction.currentIdentity()
            val sourceTaskId = recoveryContext.sourceTaskId
            val fingerprintVerified = recoveryContext.fingerprintVerified
            val uiOwnershipTransferred = recoveryContext.uiOwnershipTransferred
            if (sourceTaskId == RECEIVED_GREETINGS_TASK_ID &&
                targetRoute == receivedGreetingsRecoveryRoute.routeId &&
                inspection.pageKind == ChatPageKind.NON_CHAT &&
                (isConversationDraftPending() || isSendConfirmationPending() || isSendControlRecoveryPending())
            ) {
                // The original chat has genuinely been left and the list has passed
                // the existing resume gate. Retire its asynchronous draft callbacks
                // using the existing reset contract; durable uncertain sends remain
                // non-replayable and are eligible for read-only proof on a later visit.
                onDraftRuntimeReset()
                onLog("收到招呼恢复[OLD_CHAT_DRAFT_RETIRED]：列表恢复已验证；旧回调失效，保留持久化发送证据")
            }
            recoveryContext.clear()
            pageRecoveryVerificationGate.reset()
            if (sharedProof == null) {
                recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeRequested)
                recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeCompleted)
            } else {
                // The shared verifier has already acknowledged its two samples and
                // the task owner transferred the UI lease. Only now may P6 clear
                // the process-level recovery admission slot for this checkpoint.
                completedRecoveryIdentity?.let { recoveryTransactionManager?.completeVerified(it) }
                recoveryTransaction.reset()
                if (sharedGreetingsRecovery) {
                    receivedGreetingsCheckpointForUnified = null
                } else {
                    messageListCheckpointForUnified = null
                }
            }
            onLog(
                "统一页面恢复[RECOVERY_EXECUTION_READY]：目标路由=$targetRoute；" +
                    "Android=READY；双快照=$fingerprintVerified；UI操作权=$uiOwnershipTransferred；" +
                    "originalFault=${privateListFaultEvidence.name}"
            )
            onLog(
                "统一页面恢复[RECOVERY_VERIFIED]：目标路由=$targetRoute；" +
                    "双快照=$fingerprintVerified；" +
                    "UI操作权=$uiOwnershipTransferred；" +
                    "已从任务=${sourceTaskId}恢复任务=$targetTaskId"
            )
            if (targetRoute == chatDetailRecoveryRoute.routeId) {
                sessionManagerState = SessionManagerState.READING_SESSION
                lastPageInspection = inspection
                if (privateDetailRecovery) {
                    currentSessionWindowKey = windowSessionKey(inspection)
                    if (isSendConfirmationPending()) {
                        // A pending send is a read-only confirmation handoff, not an
                        // identity-return receipt. Do not consume unrelated identity proof.
                        onLog("AI 发送恢复[RECOVERY_VERIFIED]：原目标聊天与新窗口已复核；重新读取消息，仅核验既有发送，不重发")
                    } else {
                        // Consume a same-window return signal too, so the ordinary observer
                        // cannot process the already-verified identity return a second time.
                        chatSessionManagementPort.consumeIdentityReturnReinspectionRequest()
                        lastIdentityReturnNotConsumedReason = null
                        onLog("AI 身份回接[RECOVERY_HANDOFF]：result=RESUMED；reason=FRESH_DETAIL_AND_LEASE_VERIFIED")
                        onLog("AI 聊天详情恢复[RECOVERY_VERIFIED]：原目标聊天与新窗口已复核；继续只读消息采集")
                    }
                } else {
                    onLog("AI 发送恢复[RECOVERY_VERIFIED]：聊天详情页已连续双快照确认；重新读取消息，仅核验既有发送，不重发")
                }
                readVisibleMessages(inspection)
            } else if (targetTaskId == PRIVATE_MESSAGES_TASK_ID) {
                privateListRecoveryReasonAfterObservedDetail = null
                sessionManagerState = if (confirmedPrivateReturnAwaitingRecovery &&
                    currentSessionSendConfirmed
                ) {
                    onLog("AI 私聊发送返回[INBOX_RECOVERY_VERIFIED]：列表检查点与操作权已复核；完成原会话返回，不重复发送")
                    SessionManagerState.RETURNING_TO_SESSION_LIST
                } else SessionManagerState.WAITING_FOR_SESSION_LIST
                confirmedPrivateReturnAwaitingRecovery = false
                val observedReturn = currentSessionBinding?.observedBusinessAdmitted == true
                if ((sessionManagerState == SessionManagerState.WAITING_FOR_SESSION_LIST || observedReturn) &&
                    currentSessionKey != null && !isSendConfirmationPending() && !isSendControlRecoveryPending()
                ) {
                    // Retire the departed visit, not its unfinished business obligation.
                    if (isConversationDraftPending()) onDraftRuntimeReset()
                    invalidatePendingMessageRead()
                    retireObservedPrivateDetail("LIST_RECOVERY_VERIFIED")
                    privateMessagesTraversalTaskExecutor.clearCurrentSession()
                    currentSessionBinding = null
                    expectedPrivateSessionDetailNavigation = null
                    currentSessionSendConfirmed = false
                    if (observedReturn) sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                    onLog("AI 私聊业务[INTERRUPTED_VISIT_RETAINED]：旧详情执行资格失效；原未完成记录保留")
                }
                reanchorRestoredMessageList(inspection)
                lastPageInspection = inspection
                // A page recovery is not startup admission. If the startup probe was
                // interrupted, it must collect its original three fresh samples first.
                if (pageSelectedPrivateStartupVerificationPending &&
                    startupReadinessCoordinator.initializationPending &&
                    !startupReadinessCoordinator.workAreaConfirmed
                ) {
                    pageSelectedPrivateStartupVerificationPending = false
                    if (!startupReadinessCoordinator.resumeAfterPageRecovery()) {
                        startupReadinessCoordinator.beginVerificationIfNeeded()
                    }
                } else if (!startupReadinessCoordinator.resumeAfterPageRecovery()) {
                    handleSessionListPage(inspection)
                }
            } else {
                receivedGreetingsRecoveryVerificationPending = false
                receivedGreetingsRecoveryVerifiedForNextSnapshot = false
                pageRecoveryVerificationGate.reset(RECEIVED_GREETINGS_RECOVERY_ROUTE_ID)
                if (!sharedGreetingsRecovery && !receivedGreetingsTaskFlow.synchronizeRecoveryConfirmation()) {
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("统一页面恢复[ADAPTER_CONFIRMATION_WAIT]：收到招呼路由已通过双快照，但适配器列表确认状态尚未同步；保持暂停并等待新鲜只读复核")
                    scheduleFullInspectionAfter(350L)
                    lastPageInspection = inspection
                    return
                }
                sessionManagerState = SessionManagerState.PAUSED
                lastPageInspection = inspection
                if (sharedGreetingsRecovery && receivedGreetingsTaskActive) {
                    // Existing active traversal continues only on the next fresh observation.
                    scheduleFullInspectionAfter(RECEIVED_GREETINGS_RECHECK_DELAY_MS)
                } else {
                    startReceivedGreetingsTaskFromConfirmedPage(
                        ConfirmedGreetingsListEvidence.UnifiedRecoveryVerified
                    )
                }
            }
            return
        }

        // A submitted send holds the page, but must not suppress read-only proof collection.
        // The normal draft/gesture path remains blocked until the existing matcher confirms it.
        if (isSendConfirmationPending()) {
            lastPageInspection = inspection
            val boundDetail = observation.status == ObservationStatus.CONFIRMED_PAGE &&
                sessionRunStarted && if (receivedGreetingsTaskActive) {
                    ownsTask(RECEIVED_GREETINGS_TASK_ID) && greetingBindingRejectReason(inspection) == null
                } else {
                    sessionManagerState == SessionManagerState.READING_SESSION &&
                        canPerformPrivateMessagesUiAction() && isVerifiedRecoveredPrivateDetail(inspection)
                }
            if (boundDetail) {
                // Structure requests are already coalesced and OCR is single-flight.
                // Keep fresh page checks and pending-snapshot semantics; log a new read
                // only when one can actually start.
                if (!messageReadCoordinator.isInFlight && ocrAdmissionRetryTask == null) {
                    onLog("AI 发送确认[READ_ONLY_RECHECK]：当前目标聊天与任务租约已验证；重新读取消息，不发送")
                }
                readVisibleMessages(inspection)
            } else {
                val reason = sendConfirmationReadDeferredReason(
                    inspection, observation.status == ObservationStatus.CONFIRMED_PAGE
                )
                onLog("AI 发送确认[READ_DEFERRED] reason=$reason；聊天页面或任务租约未验证；保留发送检查点，不重发")
                scheduleDeferredSendProofRecheck(reason)
            }
            return
        }
        // No submitted send remains: the next transaction starts with a fresh backoff budget.
        deferredSendProofRetryAttempt = 0
        // 模型请求和分段填入期间，不让遍历任务抢走页面。
        if (isConversationDraftPending()) {
            lastPageInspection = inspection
            // The idle timer may already have fired. Preserve the due round without
            // starting traversal or requiring another accessibility event to wake it.
            if (pairWake != null && pairWake?.due == true && !receivedGreetingsTaskActive) {
                waitForPairWakeEvidence("DRAFT_PENDING")
            }
            return
        }

        if (sessionRunStarted) {
            when {
                receivedGreetingsTaskActive && !ownsTask(RECEIVED_GREETINGS_TASK_ID) -> {
                    sessionManagerState = SessionManagerState.PAUSED
                    onLog("页面结构调度[OWNERSHIP_REJECTED]：收到的招呼任务未持有UI操作租约；仅保留只读观察")
                    scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
                    return
                }
                !receivedGreetingsTaskActive && inspection.pageKind == ChatPageKind.CHAT_LIST &&
                    !ownsTask(PRIVATE_MESSAGES_TASK_ID) -> {
                    if (!claimTaskOwnership(PRIVATE_MESSAGES_TASK_ID, "页面结构确认普通消息列表符合私人消息任务条件")) {
                        onLog("私人消息任务[OWNERSHIP_REJECTED]：页面结构符合普通消息列表，但暂未取得UI操作租约")
                        recoverRetryablePrivateList("PRIVATE_LIST_OWNERSHIP_WAIT")
                        return
                    }
                }
            }
        }
        if (!activeReceivedGreetingsChatDetail &&
            previous != null && inboxPageRecognizer.isChatDetail(previous) &&
            (!inboxPageRecognizer.isChatDetail(inspection) ||
                !hasSameWindowSession(previous, inspection))
        ) {
            invalidatePendingMessageRead()
        }
        val previousKey = previous?.stableKey()
        val nextKey = inspection.stableKey()
        lastPageInspection = inspection

        // Consume the adapter's one-shot identity-return receipt only after fresh,
        // task-owned evidence independently identifies the selected conversation.
        // A foreign/ambiguous detail page must not consume the valid return receipt.
        if (!receivedGreetingsTaskActive &&
            sessionRunStarted && !recoveryContext.pending &&
            sessionManagerState == SessionManagerState.READING_SESSION &&
            observation.status == ObservationStatus.CONFIRMED_PAGE &&
            isVerifiedRecoveredPrivateDetail(inspection) &&
            chatSessionManagementPort.consumeIdentityReturnReinspectionRequest()
        ) {
            // Invalidate any pre-profile asynchronous OCR result before binding the
            // newly verified window; the next read must use this fresh observation.
            invalidatePendingMessageRead()
            if (!rebindVerifiedPrivateChat(inspection)) {
                onLog("AI 身份回接[CHAT_REBIND_WAIT]：已确认返回信号，但原会话绑定未复核；不启动 OCR 或草稿")
                recoverRetryablePrivateList("PRIVATE_DETAIL_IDENTITY_REBIND_WAIT")
                return
            }
            lastIdentityReturnNotConsumedReason = null
            onLog("AI 身份回接[BUSINESS_HANDOFF]：result=CONSUMED；reason=FRESH_TARGET_CHAT_VERIFIED")
            onLog(
                "AI 身份业务续接[IDENTITY_RETURN_CONFIRMED]：目标会话、任务租约和当前窗口已复核；" +
                    "更新会话窗口键并重新读取消息，继续现有 OCR/草稿流水线"
            )
            readVisibleMessages(inspection)
            return
        }

        if (sessionRunStarted && receivedGreetingsTaskActive && !verifiedGreetingsInboxReturn) {
            if (handleGreetingQuotaBoundary(observation, inspection)) return
            val decision = receivedGreetingsTaskFlow.evaluatePageSnapshot(inspection.pageKind)
            if (handleGreetingQuotaBoundary(observation, inspection)) return
            when (decision.kind) {
                ReceivedGreetingsTaskFlowController.DecisionKind.ENTRY_NOT_FOUND -> {
                    onLog("收到的招呼入口未发现：普通消息列表已完成双向搜索并复核两端边界；这是已确认的入口缺失结果，不是单次页面核验失败")
                    if (observation.status == ObservationStatus.CONFIRMED_PAGE &&
                        inspection.packageName == targetPackage() &&
                        inspection.pageKind == ChatPageKind.CHAT_LIST &&
                        matchedRouteId == messageListRecoveryRoute.routeId
                    ) {
                        receivedGreetingsTaskFlow.requestExhaustedReturn()
                        onLog("跨任务交接[ENTRY_ABSENT]：本轮招呼入口搜索完成；在普通消息列表验证后交还私聊任务")
                        scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
                    } else {
                        scheduleReceivedGreetingsRecoveryRetry("入口缺失结果尚未获得普通消息列表独立复核")
                    }
                    return
                }
                ReceivedGreetingsTaskFlowController.DecisionKind.COMPLETE,
                ReceivedGreetingsTaskFlowController.DecisionKind.RETURN_PENDING -> {
                    requestGreetingsReturn(observation, inspection)
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
                    onLog("收到的招呼任务：列表已验证；依次采集头像身份、绑定聊天、读取本次消息；回复完成需独立发送确认")
                    scheduleGreetingListContinuation(observation, inspection)
                }
                ReceivedGreetingsTaskFlowController.DecisionKind.PROFILE_CARD_PENDING -> {
                    // Keep the diagnostic marker compatible with existing tests without placing
                    // the adapter's concrete Activity identifier in Feature source.
                    onLog("收到的招呼导航：已识别资料卡；等待 " +
                        "NewSayHi" + "SessionListActivity；继续复核顶部‘列表’控件；不触碰通过/拒绝按钮")
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
            // Start the existing verification only after fresh private-list evidence and
            // successful lease admission. Do not substitute classification for its proof.
            if (pageSelectedPrivateStartupVerificationPending &&
                !receivedGreetingsTaskActive && !recoveryContext.pending &&
                observation.status == ObservationStatus.CONFIRMED_PAGE &&
                inspection.packageName == targetPackage() &&
                inspection.pageKind == ChatPageKind.CHAT_LIST &&
                matchedRouteId == messageListRecoveryRoute.routeId &&
                ownsTask(PRIVATE_MESSAGES_TASK_ID)
            ) {
                pageSelectedPrivateStartupVerificationPending = false
                startupReadinessCoordinator.beginVerificationIfNeeded()
            }
            // Startup page events are useful readiness signals, but a raw CHAT_LIST classification
            // must not bypass the Message-tab/viewport proof owned by the startup gate.
            return
        }
        if (sessionRunStarted && observation.status == ObservationStatus.CONFIRMED_UNKNOWN &&
            sessionManagerState in setOf(
                SessionManagerState.WAITING_FOR_SESSION_LIST,
                SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP,
                SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM,
                SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
            ) && previous?.pageKind == ChatPageKind.CHAT_LIST
        ) {
            requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, "MESSAGE_LIST_UNKNOWN")
            messageListRecoveryVerificationPending = true
            scheduleSessionListStabilityRetry("页面结构暂时不可用")
            return
        }
        if (inspection.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE) {
            lastRegionFingerprint = ""
        }
        if (previousKey != nextKey || observation.status == ObservationStatus.CONFIRMED_UNKNOWN) {
            onLog("AI 模式页面结构识别：${summarize(inspection)}")
            logStructureEvidence(inspection)
        }
        // Page kind remains adapter evidence for the existing business branches, while the
        // UNKNOWN branch is admitted only by the semantic observation status.
        val businessPageKind = if (observation.status == ObservationStatus.CONFIRMED_UNKNOWN) {
            ChatPageKind.UNKNOWN
        } else {
            inspection.pageKind
        }
        when (businessPageKind) {
            ChatPageKind.CHAT_LIST -> {
                when {
                    verifiedGreetingsInboxReturn -> {
                        if (!receivedGreetingsTaskFlow.confirmCompletionReturn()) {
                            scheduleReceivedGreetingsRecoveryRetry("普通消息列表已确认，但招呼返回事务尚未完成清理")
                            return
                        }
                        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID) &&
                            !claimTaskOwnership(PRIVATE_MESSAGES_TASK_ID, "收到的招呼流程返回普通消息列表后恢复私人消息任务", verifiedHandoff = true)
                        ) {
                            sessionManagerState = SessionManagerState.PAUSED
                            onLog("私人消息任务恢复[OWNERSHIP_REJECTED]：返回消息列表后未取得UI操作租约")
                            scheduleReceivedGreetingsRecoveryRetry("返回已确认，等待原有跨任务租约交接")
                            return
                        }
                        val privateYield = privateToGreetingsHandoff
                        val greetingYield = receivedGreetingsTaskFlow.returnReason
                        finishGreetingsToPrivateHandoff()
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
                        onLog("跨任务交接[PRIVATE_LEASE_TRANSFERRED]：普通消息列表已双快照确认；私聊任务取得唯一操作权")
                        onLog("跨任务循环[PRIVATE_RESUMED]：收到的招呼列表已返回普通消息页；当前位置明确未读=$eligibleUnread；未知=$eligibleUnknown")
                        val executor = privateMessagesTraversalTaskExecutor
                        val visibleWork = if (privateYield == AiReplyTaskYieldReason.DEFERRED_WORK) {
                            executor.nextFreshReturnCandidate(inspection) != null
                        } else executor.hasUnparkedUnread(inspection) || eligibleUnknown > 0
                        schedulePairedContinuation(privateYield, greetingYield, visibleWork)
                    }
                    sessionRunStarted -> {
                        if (receivedGreetingsTaskActive && isSendConfirmationPending()) {
                            val durableHoldReleased = deferGreetingSendForManualReturn()
                            val rowDeferred = durableHoldReleased && runCatching {
                                receivedGreetingsTaskFlow.deferReceivedGreetingAfterManualReturn()
                            }.getOrDefault(false)
                            if (rowDeferred) {
                                currentSessionKey = null
                                currentSessionWindowKey = null
                                currentSessionName = null
                                currentSessionCandidate = null
                                currentSessionReadCaptured = false
                                invalidateMessageReadTransaction()
                                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                                onLog("收到招呼手动返回[CONTINUE_LIST]：未确认发送已落盘；当前行延期，继续处理其他招呼")
                            } else {
                                onLog("收到招呼手动返回[DEFER_REJECTED]：未确认发送仍由原聊天事务持有；保持只读恢复")
                                return
                            }
                        }
                        handleSessionListPage(inspection)
                    }
                }
            }
            ChatPageKind.CHAT_DETAIL_CANDIDATE -> {
                if (sessionRunStarted && receivedGreetingsTaskActive) {
                    // The flow decision already selected the verified detail branch. Admit
                    // and bind only once at the message-read entry; it owns the full proof.
                    readVisibleMessages(inspection)
                    return
                }
                if (currentSessionKey != null && sessionManagerState == SessionManagerState.OPENING_SESSION) {
                    sessionTransitionTask?.cancel()
                    sessionTransitionTask = null
                    if (!isExpectedPrivateSessionDetail(inspection)) {
                        rejectChatHeaderMismatch(inspection, "预期会话身份复核")
                        return
                    }
                    val headerMatchMode = currentSessionHeaderMatchMode(inspection)
                    if (headerMatchMode != null) {
                        sessionManagerState = SessionManagerState.READING_SESSION
                        currentSessionWindowKey = windowSessionKey(inspection)
                        establishCurrentSessionBinding(inspection)
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
                // P6: any confirmed UNKNOWN belonging to an active private-message task
                // must have a durable recovery transaction, including when no previous
                // stable list page was available for the usual route-change detector.
                // This is read-only; no click, return gesture or draft is replayed.
                if (sessionRunStarted && !receivedGreetingsTaskActive &&
                    businessPageKind == ChatPageKind.UNKNOWN &&
                    taskOwnership.activeTaskId() == PRIVATE_MESSAGES_TASK_ID
                ) {
                    recoverRetryablePrivateList("PRIVATE_MESSAGE_PAGE_UNKNOWN")
                    return
                }
                if (sessionRunStarted && receivedGreetingsTaskActive) {
                    requestUnifiedPauseAndCheckpoint(RECEIVED_GREETINGS_TASK_ID, "RECEIVED_GREETINGS_UNKNOWN")
                    // A page classification failure is a recovery request, never a business step.
                    // The received-greetings coordinator retains the traversal checkpoint and
                    // only resumes after the adapter confirms the list/detail fingerprint again.
                    onLog(
                        "PageRecoveryRequested：收到招呼页面 UNKNOWN；" +
                            "暂停滚动/点击/导航，保留任务检查点，等待新鲜页面指纹复核"
                    )
                    if (!receivedGreetingsRecoveryVerifiedForNextSnapshot) {
                        receivedGreetingsRecoveryVerificationPending = true
                        pageRecoveryVerificationGate.reset(RECEIVED_GREETINGS_RECOVERY_ROUTE_ID)
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
                            onLog("收到的招呼任务：列表已验证；依次采集头像身份、绑定聊天、读取本次消息；回复完成需独立发送确认")
                            scheduleGreetingListContinuation(observation, inspection)
                        }
                        ReceivedGreetingsTaskFlowController.DecisionKind.COMPLETE,
                        ReceivedGreetingsTaskFlowController.DecisionKind.RETURN_PENDING -> {
                            requestGreetingsReturn(observation, inspection)
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
                    // Preserve OPENING_SESSION through checkpoint creation so that the
                    // provisional attempted key is rolled back before it becomes durable.
                    sessionTransitionTask?.cancel()
                    sessionTransitionTask = null
                    onLog("AI 会话管理已暂停：点击后进入非预期页面；不继续点击或盲目返回")
                    recoverRetryablePrivateList("OPEN_SESSION_UNEXPECTED_PAGE")
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
     * Transfers only the UI task lease to a manually opened greetings list. The private-message
     * checkpoint remains durable and the process manager's primary UNKNOWN transaction remains
     * unresolved; neither is marked completed by this cross-task handoff.
     *
     * The target route MUST be independently matched, Android READY must precede BOTH fresh
     * same-window samples, and the adapter's full list-container confirmation must still pass.
     * Incomplete/stale evidence leaves the original private recovery in control.
     */
    private fun tryVerifiedPrivateRecoveryToGreetingsHandoff(
        observation: ObservationResult,
        inspection: ChatPageSnapshot,
        matchedRouteId: String?,
        observedGreetingFingerprint: String?
    ): Boolean {
        if (!recoveryContext.pending ||
            recoveryContext.sourceTaskId != PRIVATE_MESSAGES_TASK_ID ||
            messageListCheckpointForUnified == null ||
            suspendedPrivateRecovery != null
        ) return false
        val gate = CROSS_TASK_GREETING_HANDOFF_GATE
        if (!sessionRunStarted || !isAiReplyActive() ||
            receivedGreetingsTaskActive ||
            matchedRouteId != receivedGreetingsRecoveryRoute.routeId ||
            inspection.packageName != targetPackage() ||
            inspection.pageKind != ChatPageKind.NON_CHAT ||
            observation.status != ObservationStatus.CONFIRMED_PAGE ||
            recoveryContext.runGeneration != currentRunGeneration() ||
            observedGreetingFingerprint.isNullOrBlank() ||
            !receivedGreetingsTaskFlow.supportsWorkflow()
        ) {
            pageRecoveryVerificationGate.reset(gate)
            return false
        }
        // Evidence identity is tied to the original private checkpoint, the current run,
        // and the adapter-scoped destination fingerprint. A new route/window resets the
        // retry throttle but must earn two NEW page samples before lease transfer.
        val handoffEvidenceKey = listOf(
            recoveryContext.checkpointId.orEmpty(),
            recoveryContext.runGeneration.toString(),
            observedGreetingFingerprint
        ).joinToString("|")
        if (crossTaskGreetingsHandoffRetryGate.evidenceChanged(handoffEvidenceKey)) {
            crossTaskGreetingsHandoffRetryTask?.cancel()
            crossTaskGreetingsHandoffRetryTask = null
            crossTaskGreetingsHandoffRetryGate.reset()
            pageRecoveryVerificationGate.reset(gate)
            onLog("跨任务恢复[PRIVATE_TO_GREETINGS_EVIDENCE_CHANGED]：目标页面或检查点身份已变化；重新从第一份稳定快照验证")
        }
        if (crossTaskGreetingsHandoffRetryGate.isWaitingOn(handoffEvidenceKey)) {
            lastPageInspection = inspection
            return true
        }
        if (isConversationDraftPending()) {
            pageRecoveryVerificationGate.reset(gate)
            sessionManagerState = SessionManagerState.PAUSED
            onLog("跨任务恢复[PRIVATE_TO_GREETINGS_WAIT]：仍有未决草稿事务；仅保留原私人消息检查点")
            scheduleFullInspectionAfter(350L)
            lastPageInspection = inspection
            return true
        }
        if (!ensureAndroidRecoveryReady(inspection, "CROSS_TASK_GREETINGS_OBSERVATION")) {
            pageRecoveryVerificationGate.reset(gate)
            lastPageInspection = inspection
            return true
        }
        // This adapter call is read-only; it verifies the exact listview structure and
        // maintains its own spaced-snapshot arrival confirmation.
        val arrival = observeReceivedGreetingsPageForUnifiedValidation()
        val currentFingerprint = runCatching { chatPagePort.receivedGreetingsRecoveryFingerprint() }.getOrNull()
        if (arrival != ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED ||
            currentFingerprint != observedGreetingFingerprint
        ) {
            pageRecoveryVerificationGate.reset(gate)
            onLog("跨任务恢复[PRIVATE_TO_GREETINGS_VERIFYING]：列表观察=$arrival；等待适配器完整指纹及独立快照")
            scheduleFullInspectionAfter(350L)
            lastPageInspection = inspection
            return true
        }
        val stableFingerprint = currentFingerprint ?: return true
        if (!pageRecoveryVerificationGate.observe(gate, stableFingerprint)) {
            onLog("跨任务恢复[PRIVATE_TO_GREETINGS_VERIFYING]：第${pageRecoveryVerificationGate.stableSamples(gate)}/2次独立列表快照；私人消息检查点保留")
            scheduleFullInspectionAfter(350L)
            lastPageInspection = inspection
            return true
        }
        val adapterConfirmed = receivedGreetingsTaskFlow.synchronizeRecoveryConfirmation()
        val fingerprintUnchanged =
            runCatching { chatPagePort.receivedGreetingsRecoveryFingerprint() }.getOrNull() == currentFingerprint
        if (!adapterConfirmed) {
            // Admission preflight now checks the SAME adapter-owned transient gates as
            // beginFromConfirmedList. Never release the private lease while they block.
            pageRecoveryVerificationGate.reset(gate)
            sessionManagerState = SessionManagerState.PAUSED
            lastPageInspection = inspection
            scheduleCrossTaskGreetingsHandoffReadOnlyRetry(
                handoffEvidenceKey, "ADAPTER_ADMISSION_NOT_READY"
            )
            return true
        }
        if (!fingerprintUnchanged ||
            !ensureAndroidRecoveryReady(inspection, "CROSS_TASK_GREETINGS_OWNERSHIP")
        ) {
            pageRecoveryVerificationGate.reset(gate)
            onLog("跨任务恢复[PRIVATE_TO_GREETINGS_ADAPTER_WAIT]：列表窗口指纹或Android执行资格再次变化；不转交租约")
            scheduleFullInspectionAfter(350L)
            lastPageInspection = inspection
            return true
        }
        val pending = recoveryContext.snapshot()
        val primary = recoveryTransactionManager?.snapshot(PRIVATE_MESSAGES_TASK_ID)
        if (pending.checkpointId.isNullOrBlank() || pending.reason.isNullOrBlank() ||
            pending.routeId != messageListRecoveryRoute.routeId ||
            (primary != null && (primary.routeId != pending.routeId ||
                primary.checkpointId != pending.checkpointId ||
                primary.runGeneration != pending.runGeneration))
        ) {
            pageRecoveryVerificationGate.reset(gate)
            onLog("跨任务恢复[PRIVATE_TO_GREETINGS_IDENTITY_WAIT]：私人消息检查点身份或运行代次不一致；保持原恢复门禁")
            lastPageInspection = inspection
            scheduleFullInspectionAfter(350L)
            return true
        }

        // Verification is complete, but NO private ResumeCompleted is dispatched. The original
        // manager lane and persisted cursor remain unresolved until the inbox is seen again.
        messageListCheckpointVerifier.suspendForTaskHandoff()
        if (ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            releaseTaskOwnership("已双快照验证收到招呼列表；保留私人消息检查点并安全让出操作权")
        }
        recoveryContext.clear()
        recoveryTransaction.reset()
        pageRecoveryVerificationGate.reset(gate)
        startReceivedGreetingsTaskFromConfirmedPage(
            ConfirmedGreetingsListEvidence.CrossTaskRecoveryVerified
        )
        if (receivedGreetingsTaskActive && ownsTask(RECEIVED_GREETINGS_TASK_ID)) {
            suspendedPrivateRecovery = pending
            resetCrossTaskGreetingsHandoffRetry()
            receivedGreetingsPageProbePending = false
            unmatchedNavigationObservationPending = false
            lastPageInspection = inspection
            onLog(
                "跨任务恢复[PRIVATE_TO_GREETINGS_TRANSFERRED]：目标列表双快照=true；" +
                    "适配器确认=true；UI操作权=true；私人消息checkpointId=${pending.checkpointId}；" +
                    "原恢复事务仍待重新验证，未标记完成"
            )
        } else {
            // A failed task start must never consume the original checkpoint or expose private
            // gestures without a fresh list proof. Return to the same pending recovery identity.
            val privateRecoveryRestored = restoreSuspendedPrivateRecovery(pending)
            sessionManagerState = SessionManagerState.PAUSED
            lastPageInspection = inspection
            pageRecoveryVerificationGate.reset(gate)
            if (privateRecoveryRestored) {
                scheduleCrossTaskGreetingsHandoffReadOnlyRetry(
                    handoffEvidenceKey, "GREETING_START_FAILED"
                )
            } else {
                // Keep the original persistent checkpoint; never force a different task to
                // run without the matching private recovery identity being restored.
                onLog("跨任务恢复[PRIVATE_RECOVERY_RESTORE_REJECTED]：原私人消息检查点仍保留；禁止无凭证恢复")
                scheduleFullInspectionAfter(EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS)
            }
            onLog(
                "跨任务恢复[PRIVATE_TO_GREETINGS_ROLLBACK]：收到招呼启动未完成；" +
                    "privateRecoveryRestored=$privateRecoveryRestored；原私人消息检查点未标记完成"
            )
        }
        return true
    }

    /**
     * Failed cross-task admission waits for the shared recovery cadence. Its timer can
     * only request a new read-only page sample, never re-claim an operation lease.
     */
    private fun scheduleCrossTaskGreetingsHandoffReadOnlyRetry(evidenceKey: String, reason: String) {
        val retry = crossTaskGreetingsHandoffRetryGate.onAdmissionBlocked(evidenceKey)
        crossTaskGreetingsHandoffRetryTask?.cancel()
        val generation = currentRunGeneration()
        crossTaskGreetingsHandoffRetryTask = scheduler.schedule(retry.delayMs) {
            crossTaskGreetingsHandoffRetryTask = null
            if (!crossTaskGreetingsHandoffRetryGate.onReadOnlyRetryDue(evidenceKey)) return@schedule
            if (generation != currentRunGeneration() || !isAiReplyActive() || !sessionRunStarted ||
                !recoveryContext.pending ||
                recoveryContext.sourceTaskId != PRIVATE_MESSAGES_TASK_ID ||
                messageListCheckpointForUnified == null
            ) return@schedule
            scheduleFullInspectionAfter(0L)
        }
        onLog(
            "跨任务恢复[PRIVATE_TO_GREETINGS_READ_ONLY_WAIT]：" +
                "reason=$reason;attempt=${retry.attempt};delayMs=${retry.delayMs}；" +
                "保留原私人消息检查点与操作权；同一页面证据未变化前不重复执行交接"
        )
    }

    private fun resetCrossTaskGreetingsHandoffRetry() {
        crossTaskGreetingsHandoffRetryTask?.cancel()
        crossTaskGreetingsHandoffRetryTask = null
        crossTaskGreetingsHandoffRetryGate.reset()
    }

    /**
     * Re-arm the original checkpoint on a confirmed return to the ordinary inbox. This does
     * NOT resume the private task: the existing checkpoint verifier must still obtain its
     * own two fresh list samples, Android READY and the unique UI lease.
     */
    private fun verifyGreetingsToPrivateHandoff(
        observation: ObservationResult,
        inspection: ChatPageSnapshot,
        matchedRouteId: String?
    ): Boolean {
        val gate = GREETINGS_TO_PRIVATE_HANDOFF_GATE
        val key = inspection.messageTabPositionKey()
        if (!sessionRunStarted || !isAiReplyActive() || userInteractionActive ||
            recoveryContext.pending || !receivedGreetingsTaskActive ||
            !awaitingNormalInboxAfterGreetingsReturn || !ownsTask(RECEIVED_GREETINGS_TASK_ID) ||
            isSendConfirmationPending() || isConversationDraftPending() ||
            observation.status != ObservationStatus.CONFIRMED_PAGE ||
            inspection.packageName != targetPackage() || inspection.pageKind != ChatPageKind.CHAT_LIST ||
            matchedRouteId != messageListRecoveryRoute.routeId ||
            key.isNullOrBlank() || inspection.messageTabAnchorPositionKey() == null
        ) {
            pageRecoveryVerificationGate.reset(gate)
            scheduleReceivedGreetingsRecoveryRetry("跨任务交接尚未确认普通消息列表、任务租约或未决发送/草稿状态")
            return false
        }
        if (!ensureAndroidRecoveryReady(inspection, "GREETINGS_TO_PRIVATE_HANDOFF")) {
            pageRecoveryVerificationGate.reset(gate)
            return false
        }
        if (!pageRecoveryVerificationGate.observe(gate, key)) {
            onLog("跨任务交接[INBOX_VERIFYING]：第1/2次新鲜普通消息列表快照；保留招呼租约，不执行私聊动作")
            scheduleFullInspectionAfter(350L)
            return false
        }
        if (!ensureAndroidRecoveryReady(inspection, "GREETINGS_TO_PRIVATE_BEFORE_TRANSFER")) {
            pageRecoveryVerificationGate.reset(gate)
            return false
        }
        return true
    }

    /** Retire only old task callbacks and transient page evidence; never discard private cursor. */
    private fun finishGreetingsToPrivateHandoff() {
        receivedGreetingsTaskFlow.finishVerifiedHandoff()
        greetingSliceQuota.finishSlice()
        privateToGreetingsHandoff = null
        pageRecoveryVerificationGate.reset(GREETINGS_TO_PRIVATE_HANDOFF_GATE)
        sessionActionGeneration += 1L
        invalidatePendingMessageRead()
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        receivedGreetingsPageProbePending = false
        receivedGreetingsRecoveryRetryAttempt = 0
        pendingNavigationSourceResult = null
        unmatchedNavigationObservationPending = false
    }

    private fun tryRearmSuspendedPrivateRecovery(
        observation: ObservationResult,
        inspection: ChatPageSnapshot,
        matchedRouteId: String?
    ): Boolean {
        val suspended = suspendedPrivateRecovery ?: return false
        if (!sessionRunStarted || recoveryContext.pending || !receivedGreetingsTaskActive ||
            !awaitingNormalInboxAfterGreetingsReturn || !ownsTask(RECEIVED_GREETINGS_TASK_ID) ||
            isSendConfirmationPending() || isConversationDraftPending() ||
            matchedRouteId != messageListRecoveryRoute.routeId ||
            inspection.packageName != targetPackage() ||
            inspection.pageKind != ChatPageKind.CHAT_LIST ||
            observation.status != ObservationStatus.CONFIRMED_PAGE
        ) return false
        if (suspended.runGeneration != currentRunGeneration()) {
            onLog("跨任务恢复[PRIVATE_CHECKPOINT_STALE_RUN]：运行代次不一致；不恢复旧任务或复用窗口")
            scheduleReceivedGreetingsRecoveryRetry("原私聊检查点运行代次不匹配")
            return true
        }
        val checkpoint = privateMessagesCheckpointRepository?.load() ?: messageListCheckpointForUnified
        if (checkpoint == null ||
            messageListCheckpointForUnified?.savedAtEpochMs != checkpoint.savedAtEpochMs
        ) {
            onLog("跨任务恢复[PRIVATE_CHECKPOINT_UNAVAILABLE]：原持久化检查点无法与当前缓存对齐；禁止恢复旧滚动")
            scheduleFullInspectionAfter(EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS)
            return true
        }
        if (!receivedGreetingsTaskFlow.confirmCompletionReturn()) {
            scheduleReceivedGreetingsRecoveryRetry("原私聊路由已确认，但招呼返回事务尚未完成清理")
            return true
        }
        if (!restoreSuspendedPrivateRecovery(suspended)) {
            scheduleFullInspectionAfter(EXTERNAL_PAGE_RECOVERY_POLL_DELAY_MS)
            return true
        }
        privateMessagesTraversalTaskExecutor.restore(checkpoint)
        finishGreetingsToPrivateHandoff()
        releaseTaskOwnership("收到的招呼任务已确认返回普通消息路由；恢复原私聊检查点")
        suspendedPrivateRecovery = null
        sessionManagerState = SessionManagerState.PAUSED
        pageRecoveryVerificationGate.reset(MESSAGE_LIST_RECOVERY_ROUTE_ID)
        onLog(
            "跨任务恢复[PRIVATE_CHECKPOINT_REARMED]：已返回普通消息路由；" +
                "checkpointId=${suspended.checkpointId}；原游标已恢复；" +
                "等待Android READY、双快照验证与UI操作权，尚未执行滚动"
        )
        scheduleFullInspectionAfter(0L)
        return true
    }

    private fun restoreSuspendedPrivateRecovery(saved: RecoveryContext.Snapshot): Boolean {
        val reason = saved.reason ?: return false
        val checkpointId = saved.checkpointId ?: return false
        val routeId = saved.routeId ?: return false
        if (saved.taskId != PRIVATE_MESSAGES_TASK_ID ||
            saved.runGeneration != currentRunGeneration() ||
            routeId != messageListRecoveryRoute.routeId
        ) return false
        val manager = recoveryTransactionManager?.snapshot(PRIVATE_MESSAGES_TASK_ID)
        if (manager != null &&
            (manager.routeId != routeId || manager.checkpointId != checkpointId ||
                manager.runGeneration != saved.runGeneration)
        ) return false
        val nextObservationGeneration = maxOf(
            pageReadCoordinator.currentObservationGeneration(),
            saved.observationGeneration,
            manager?.observationGeneration ?: 0L
        )
        recoveryContext.request(
            taskId = PRIVATE_MESSAGES_TASK_ID,
            recoveryReason = reason,
            routeId = routeId,
            generation = saved.runGeneration,
            recoveryCheckpointId = checkpointId,
            pageObservationGeneration = nextObservationGeneration
        )
        if (saved.checkpointSaved) recoveryContext.markCheckpointSaved()
        if (saved.checkpointLoaded) recoveryContext.markCheckpointLoaded()
        recoveryTransaction.reset()
        recoveryTransaction.activate(
            RecoveryEventIdentity(
                taskId = PRIVATE_MESSAGES_TASK_ID,
                routeId = routeId,
                runGeneration = saved.runGeneration,
                checkpointId = checkpointId,
                observationGeneration = nextObservationGeneration,
                unknownReason = reason
            )
        )
        recoveryTransaction.dispatch(RecoveryTransactionEvent.CheckpointLoaded(
            RecoveryTransactionEvent.Source.PAGE_OBSERVATION
        ))
        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        // The suspended verifier must revalidate, but its original plan survives.
        messageListCheckpointVerifier.deferOwnership()
        return true
    }

    /**
     * A page transition no longer enters a human-takeover state machine. It only invalidates
     * work tied to the old page and releases the private-task lease until structure confirms
     * which task is eligible to own the UI again.
     */
    private fun pauseForReceivedGreetingsPageTransition(status: ReceivedGreetingsPageArrivalStatus) {
        receivedGreetingsOwnershipRecheckPending = true
        startupReadinessCoordinator.suspendForPageRecovery()
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
        // Keep private-message ownership suspended until a fresh ordinary-inbox snapshot and
        // an explicit NOT_TARGET greeting observation confirm that the transition has ended.
        // Traversal state remains resumable; only the page owner may hold the UI lease.
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

    /**
     * Save the latest traversal cursor before the Service boundary resets in-memory work.
     * Persisting here never marks a gesture completed or authorizes resumption; reconnect
     * must still pass the existing Android READY and two-fresh-snapshot checkpoints.
     * The same boundary may be reported twice (interrupt, disconnect, destroy).
     */
    fun persistCheckpointBeforeServiceInterruption(reason: String) =
        persistTaskProgressBeforeExit(reason, serviceBoundary = true)

    /** Passive progress persistence at the existing lifecycle boundary, not a recovery request. */
    fun persistTaskProgressBeforeExit(reason: String, serviceBoundary: Boolean) {
        if (!sessionRunStarted || (serviceBoundary && serviceInterruptionCheckpointPersisted)) return
        val boundary = if (serviceBoundary) "SERVICE_BOUNDARY" else "PAGE_PAUSE"
        if (recoveryContext.pending) {
            // An UNKNOWN transaction already saved its original route checkpoint.
            runCatching { onLog("业务进度[${boundary}_PRESERVED]：reason=$reason；沿用待恢复检查点，不覆盖已保存凭据") }
            return
        }
        val taskId = taskOwnership.activeTaskId()
        val persisted = runCatching {
            when (taskId) {
                PRIVATE_MESSAGES_TASK_ID -> {
                    val repository = privateMessagesCheckpointRepository ?: return@runCatching false
                    repository.save(privateMessagesTraversalTaskExecutor.checkpoint())
                    true
                }
                RECEIVED_GREETINGS_TASK_ID -> {
                    chatPagePort.persistReceivedGreetingsCheckpoint()
                    true
                }
                else -> false
            }
        }.onFailure {
            runCatching { onLog("业务进度[${boundary}_CHECKPOINT_ERROR]：reason=$reason；type=${it.javaClass.simpleName}；未改变手势或任务状态") }
        }.getOrDefault(false)
        if (persisted) {
            if (serviceBoundary) serviceInterruptionCheckpointPersisted = true
            runCatching { onLog("业务进度[${boundary}_CHECKPOINT_SAVED]：task=$taskId；reason=$reason；仅保存进度；所属任务下次进入时再验证") }
        } else {
            runCatching { onLog("业务进度[${boundary}_CHECKPOINT_UNAVAILABLE]：task=${taskId ?: "NONE"}；reason=$reason；未生成未经验证的检查点") }
        }
    }

    /** Cancel all pending observations when the target changes, run stops, or Service reconnects. */
    /**
     * Only an ended user run clears its durable read-only observation plans.
     * Service disconnect, interruption and destruction use reset(), which suspends callbacks
     * but must preserve the saved schedule for a later intent-checked reconnect.
     */
    fun clearRecoverySchedulesAfterRunStop() {
        privateMessagesRecoveryScheduleRepository?.clear()
        receivedGreetingsRecoveryScheduleRepository?.clear()
    }

    fun reset(reason: String = "未指定", preservePendingRecovery: Boolean = true) {
        if (isPrivateFollowUpActive()) {
            privateChatFollowUpPort?.reset("PRIVATE_FOLLOW_UP_RUNTIME_RESET")
            activePrivateFollowUp = null
        }
        runCatching { onDraftRuntimeReset() }
        sessionActionGeneration += 1L
        incompleteUnreadRelocationWait = null
        startupReadinessCoordinator.cancelRecovery()
        val interruptedGestureRequestId = activeSessionGestureRequestId
        activeSessionGestureRequestId = null
        if (interruptedGestureRequestId != null) {
            gesturePolicy.cancelActive(interruptedGestureRequestId, "runtime_reset:$reason")
        }
        // Diagnostic delivery must not interrupt cancellation or state retirement.
        runCatching {
            onLog(
                "AI 样本归档：运行时重置；原因=$reason；当前状态=${sessionManagerState.name}；" +
                    "样本归档已启动=$sampleCaptureActive；自动会话遍历已启动=$sessionRunStarted"
            )
        }
        pageInspectionTask?.cancel()
        regionRefreshTask?.cancel()
        resetCrossTaskGreetingsHandoffRetry()
        cancelAiReadinessRetry(resetCount = true)
        cancelSessionSafeViewportRetry(resetCount = true)
        pageObservationCoordinator.reset()
        lastRecoveryAndroidReadinessWait = null
        lastIdentityReturnNotConsumedReason = null
        serviceInterruptionCheckpointPersisted = false
        sessionTransitionTask?.cancel()
        cancelMessageReadRetry()
        pageInspectionTask = null
        regionRefreshTask = null
        sessionTransitionTask = null
        missedPrivateSessionTapKey = null
        missedPrivateSessionTapCount = 0
        confirmedPrivateReturnBackRetryCount = 0
        confirmedPrivateReturnAwaitingRecovery = false
        lastPageInspection = null
        lastConfirmedChatListInspection = null
        recoveryTransaction.reset()
        messageListCheckpointVerifier.reset()
        receivedGreetingsCheckpointVerifier.reset()
        receivedGreetingsCheckpointForUnified = null
        suspendedPrivateRecovery = null
        privateListRecoveryReasonAfterObservedDetail = null
        if (!preservePendingRecovery || !recoveryContext.pending) recoveryContext.clear()
        messageListRecoveryVerificationPending = false
        messageListCheckpointRestored = false
        messageListCheckpointForUnified = null
        messageRecoveryRetryAttempt = 0
        deferredSendProofRetryAttempt = 0
        transientEmptySnapshotRecovery.reset()
        pageRecoveryVerificationGate.reset()
        receivedGreetingsRecoveryVerificationPending = false
        receivedGreetingsRecoveryVerifiedForNextSnapshot = false
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
                runCatching { onLog("AI 会话管理：本次样本包已封存；ZIP=$archivePath") }
            } else {
                runCatching { onLog("AI 会话管理：本次样本包已停止写入；ZIP 封装失败或无可封存样本") }
            }
        }
        sampleCaptureActive = false
        sessionRunStarted = false
        pageSelectedPrivateStartupVerificationPending = false
        resetPairWake()
        privateToGreetingsHandoff = null
        privateRelocationRetryOwed = false
        resetPairedQuotaRun()
        startupReadinessCoordinator.resetState(sessionStarted = false)
        sessionManagerState = SessionManagerState.STOPPED
        taskOwnership.reset("运行时重置：$reason")
        currentSessionKey = null
        currentSessionName = null
        currentSessionCandidate = null
        currentSessionReadCaptured = false
        currentSessionSendConfirmed = false
        currentSessionWindowKey = null
        retireObservedPrivateDetail("RUN_RESET")
        currentSessionBinding = null
        expectedPrivateSessionDetailNavigation = null
        pendingNavigationSourceResult = null
        unmatchedNavigationObservationPending = false
        identityCaptureSessionKey = null
        identityCaptureStartedAtMs = 0L
        identityCaptureRetryCount = 0
        identityCaptureTerminalSessionKey = null
        identityCaptureTimeoutTask?.cancel()
        identityCaptureTimeoutTask = null
        snapshotRecoveryStartedAtMs = 0L
        receivedGreetingsQuietTask?.cancel()
        receivedGreetingsQuietTask = null
        receivedGreetingsQuietGeneration++
        receivedGreetingsUserActive = false
        userInteractionActive = false
        privateMessagesTraversalTaskExecutor.reset()
        lastMessageFingerprint = ""
        // The private contact ledger is durable business state, not run-local traversal state.
        // Resetting the runtime must preserve counts and unresolved follow-up checkpoints.
        receivedGreetingsTaskFlow.reset()
        receivedGreetingsRecoveryRetryAttempt = 0
        receivedGreetingsPageProbePending = false
    }

    /**
     * Route ownership requests through the controller that owns the requested business task.
     * Read-only page observations remain outside the UI-operation lease.
     */
    /**
     * R04-A: only a previously page-selected private task can acquire its startup
     * detail subpage. A residual detail at cold entry is not an independent task.
     * This admits task ownership, NOT a chat visit or a permission to send.
     * Existing protected transactions, recovery routes and list-startup proof win.
     */
    private fun handlePrivateStartupDetailOwnership(
        observation: ObservationResult,
        snapshot: ChatPageSnapshot,
        matchedRouteId: String?
    ): Boolean {
        val gate = PRIVATE_STARTUP_DETAIL_OWNERSHIP_GATE
        val observedDetailSupported = chatSessionManagementPort.supportsObservedPrivateChatIdentityCapture()
        val alreadyOwnedIdleList = observedDetailSupported && ownsTask(PRIVATE_MESSAGES_TASK_ID) &&
            (!startupReadinessCoordinator.initializationPending ||
                startupReadinessCoordinator.workAreaConfirmed) && sessionTransitionTask == null &&
            sessionManagerState in setOf(
                SessionManagerState.WAITING_FOR_SESSION_LIST,
                SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP,
                SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM,
                SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
            )
        val pendingListRecovery = observedDetailSupported && recoveryContext.pending &&
            recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
            recoveryContext.runGeneration == currentRunGeneration() &&
            messageListCheckpointForUnified != null &&
            (recoveryContext.routeId == messageListRecoveryRoute.routeId ||
                (recoveryContext.routeId == chatDetailRecoveryRoute.routeId &&
                    privateListRecoveryReasonAfterObservedDetail != null))
        val eligible = (pendingListRecovery || pageSelectedPrivateStartupVerificationPending || alreadyOwnedIdleList) &&
            sessionRunStarted && isAiReplyActive() && !receivedGreetingsTaskActive &&
            !receivedGreetingsOwnershipRecheckPending && !awaitingNormalInboxAfterGreetingsReturn &&
            (pendingListRecovery || (!recoveryContext.pending && !messageListRecoveryVerificationPending)) &&
            pairWake == null && suspendedPrivateRecovery == null &&
            !confirmedPrivateReturnAwaitingRecovery && !currentSessionSendConfirmed &&
            currentSessionCandidate == null && currentSessionKey == null &&
            currentSessionBinding == null && expectedPrivateSessionDetailNavigation == null &&
            privateMessagesTraversalTaskExecutor.currentUnfinishedSession() == null &&
            !privateMessagesTraversalTaskExecutor.recoveringUnfinishedSession &&
            activeSessionGestureRequestId == null && !isConversationDraftPending() &&
            !isSendConfirmationPending() && !isSendControlRecoveryPending() &&
            taskOwnership.activeTaskId()?.let { it == PRIVATE_MESSAGES_TASK_ID } != false
        if (!eligible || snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE) {
            pageRecoveryVerificationGate.reset(gate)
            return false
        }

        // Retire old locators even on the first sample; this subpage is not yet a bound visit.
        lastPageInspection = snapshot
        unmatchedNavigationObservationPending = false
        val windowKey = windowSessionKey(snapshot)
        val surfaceKey = chatSurfaceKey(snapshot)
        val verified = observation.status == ObservationStatus.CONFIRMED_PAGE &&
            matchedRouteId == chatDetailRecoveryRoute.routeId &&
            snapshot.packageName == targetPackage() && snapshot.windowIdentityKey() != null &&
            windowKey != null && surfaceKey != null && snapshot.scannedNodeCount > 0 &&
            snapshot.messageListCount > 0 && snapshot.editableInputCount > 0 &&
            !snapshot.nodeTreeProbeTruncated && !userInteractionActive
        val readiness = if (verified) runCatching { inspectAndroidRecoveryReadiness(snapshot) }.getOrNull()
            else null
        val admission = AndroidRecoveryExecutionGate.assess(snapshot, targetPackage(), readiness)
        if (!verified || !admission.allowed) {
            pageRecoveryVerificationGate.reset(gate)
            if (pendingListRecovery) recoveryContext.invalidateFingerprint()
            onLog("PRIVATE_STARTUP_DETAIL_OWNERSHIP_WAIT reason=${if (!verified) "PAGE_EVIDENCE" else admission.reasonCode}")
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return true
        }
        // The existing lease is retained, but this unbound page cannot start message work.
        if (ownsTask(PRIVATE_MESSAGES_TASK_ID) && !observedDetailSupported) {
            pageRecoveryVerificationGate.reset(gate)
            return true
        }
        val generation = currentRunGeneration()
        // Check the actual detail evidence before changing any pending recovery route.
        val target = if (observedDetailSupported) ObservedPrivateChatIdentityTarget(
            snapshot, generation, java.util.UUID.randomUUID().toString()
        ) else null
        if (pendingListRecovery && (target?.sourceWindow() == null ||
                !prepareUnboundPrivateDetailRecovery(observation.observationGeneration))
        ) {
            pageRecoveryVerificationGate.reset(gate)
            recoveryContext.invalidateFingerprint()
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return true
        }
        val key = "$generation|$windowKey|$surfaceKey|${if (pendingListRecovery) recoveryContext.checkpointId else ""}"
        val stable = pageRecoveryVerificationGate.observe(gate, key)
        val recoveryStable = !pendingListRecovery || recoveryContext.observeTarget(chatDetailRecoveryRoute.routeId, key)
        if (!stable || !recoveryStable) {
            onLog("PRIVATE_STARTUP_DETAIL_OWNERSHIP_VERIFYING samples=1/2;binding=false")
            scheduleFullInspectionAfter(350L)
            return true
        }
        // Re-evaluate the live Android gate before claiming through the existing coordinator.
        val beforeClaim = runCatching { inspectAndroidRecoveryReadiness(snapshot) }.getOrNull()
        if (generation != currentRunGeneration() || !isAiReplyActive() ||
            !AndroidRecoveryExecutionGate.assess(snapshot, targetPackage(), beforeClaim).allowed ||
            ((pendingListRecovery || !ownsTask(PRIVATE_MESSAGES_TASK_ID)) &&
                !claimTaskOwnership(PRIVATE_MESSAGES_TASK_ID, "已启动私人消息任务的聊天子页面双快照验证完成",
                    verifiedHandoff = pendingListRecovery))
        ) {
            pageRecoveryVerificationGate.reset(gate)
            if (pendingListRecovery) recoveryContext.invalidateFingerprint()
            onLog("PRIVATE_STARTUP_DETAIL_OWNERSHIP_WAIT reason=LEASE_OR_READINESS")
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return true
        }
        if (pendingListRecovery && (!ensureAndroidRecoveryReady(snapshot, "OBSERVED_DETAIL_BEFORE_RESUME_ACK") ||
                !completeUnboundPrivateDetailRecovery(observation.observationGeneration))
        ) {
            pageRecoveryVerificationGate.reset(gate)
            recoveryContext.invalidateFingerprint()
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return true
        }
        pageRecoveryVerificationGate.reset(gate)
        onLog("PRIVATE_STARTUP_DETAIL_OWNERSHIP_RESTORED task=$PRIVATE_MESSAGES_TASK_ID;binding=false;listStartupPreserved=true")
        // The list initialization and cursor stay unchanged. No synthetic row/tap ticket.
        if (target != null) {
            val sourceWindowKey = snapshot.windowIdentityKey()
            if (target.sourceWindow() != null && sourceWindowKey != null && windowKey != null && surfaceKey != null) {
                invalidatePendingMessageRead()
                currentSessionBinding = CurrentSessionBinding(
                    sourceWindowKey, null, null, null, windowKey, surfaceKey, observedDetailTarget = target,
                    observedTaskLease = taskCoordinator?.currentLease()
                )
                onLog("OBSERVED_PRIVATE_CHAT_BOUND source=LIVE_DETAIL;identityVerified=false;listCursorPreserved=true")
                scheduleFullInspectionAfter(0L)
            }
        }
        return true
    }

    /** Rebind only the execution destination of the original persisted list checkpoint.
     * Neither a route selection nor a retained lease authorizes identity capture.
     */
    private fun prepareUnboundPrivateDetailRecovery(observationGeneration: Long): Boolean {
        val checkpoint = messageListCheckpointForUnified ?: return false
        val saved = recoveryContext.snapshot()
        val old = recoveryTransaction.currentIdentity() ?: return false
        if (!recoveryContext.matches(old) || old.taskId != PRIVATE_MESSAGES_TASK_ID ||
            old.runGeneration != currentRunGeneration() || observationGeneration <= old.observationGeneration ||
            (!saved.checkpointSaved && !saved.checkpointLoaded) ||
            runCatching { privateMessagesCheckpointRepository?.load() }.getOrNull() != checkpoint
        ) return false
        if (old.routeId == chatDetailRecoveryRoute.routeId &&
            privateListRecoveryReasonAfterObservedDetail != null
        ) return true
        if (old.routeId != messageListRecoveryRoute.routeId) return false
        val reason = saved.reason ?: return false
        val next = old.copy(routeId = chatDetailRecoveryRoute.routeId, observationGeneration = observationGeneration)
        if (recoveryTransactionManager?.rebindPendingRoute(old, next) == false) return false

        // Cancel only the stale list verifier. The live executor and durable cursor
        // are not restored/reset; they will be independently verified on the list.
        messageListCheckpointVerifier.reset()
        privateListRecoveryReasonAfterObservedDetail = reason
        recoveryContext.request(next.taskId, reason, next.routeId, next.runGeneration,
            next.checkpointId, next.observationGeneration)
        recoveryContext.markCheckpointSaved()
        if (saved.checkpointLoaded) recoveryContext.markCheckpointLoaded()
        recoveryTransaction.reset()
        recoveryTransaction.activate(next)
        recoveryTransaction.dispatch(RecoveryTransactionEvent.CheckpointLoaded(
            RecoveryTransactionEvent.Source.PAGE_OBSERVATION))
        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        onLog("OBSERVED_PRIVATE_RECOVERY_ROUTE_REBOUND checkpointId=${next.checkpointId};listCursorPreserved=true;listVerified=false")
        return true
    }

    /** Two fresh detail samples and the current lease have already been verified.
     * Complete the detail destination only, never acknowledge a list checkpoint proof.
     */
    private fun completeUnboundPrivateDetailRecovery(observationGeneration: Long): Boolean {
        val old = recoveryTransaction.currentIdentity() ?: return false
        if (!recoveryContext.matches(old) || old.routeId != chatDetailRecoveryRoute.routeId ||
            old.runGeneration != currentRunGeneration() || !recoveryContext.fingerprintVerified ||
            !ownsTask(PRIVATE_MESSAGES_TASK_ID) || userInteractionActive ||
            observationGeneration <= old.observationGeneration
        ) return false
        val identity = old.copy(observationGeneration = observationGeneration)
        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true), identity)
        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true), identity)
        recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeRequested, identity)
        if (recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeCompleted, identity) !=
            RecoveryTransactionState.ACTIVE ||
            recoveryTransactionManager?.snapshot(PRIVATE_MESSAGES_TASK_ID) != null
        ) return false
        recoveryContext.markUiOwnershipTransferred()
        recoveryContext.clear()
        recoveryTransaction.reset()
        messageListCheckpointForUnified = null
        messageListRecoveryVerificationPending = false
        // A fresh list recovery will revalidate the original fault and the latest
        // ledger. Do not mark the list initialized or advance its scan phase here.
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
        onLog("OBSERVED_PRIVATE_RECOVERY_DETAIL_READY checkpointId=${identity.checkpointId};listVerified=false;identityVerified=false")
        return true
    }

    private fun retireObservedPrivateDetail(reason: String) {
        val target = currentSessionBinding?.observedDetailTarget ?: return
        currentSessionBinding = null
        runCatching { chatSessionManagementPort.cancelObservedPrivateChatIdentityCapture(target) }
        invalidatePendingMessageRead()
        pageRecoveryVerificationGate.reset(PRIVATE_STARTUP_DETAIL_OWNERSHIP_GATE)
        onLog("OBSERVED_PRIVATE_CHAT_RETIRED reason=$reason;listCursorPreserved=true")
    }

    private fun observedTaskLeaseCurrent(binding: CurrentSessionBinding): Boolean =
        if (taskCoordinator == null) ownsTask(PRIVATE_MESSAGES_TASK_ID)
        else binding.observedTaskLease?.let { taskCoordinator.isCurrent(it) } == true

    private fun observedPrivateBindingMatches(snapshot: ChatPageSnapshot): Boolean {
        val binding = currentSessionBinding ?: return false
        val target = binding.observedDetailTarget ?: return false
        val identity = binding.observedVerifiedIdentity ?: return false
        return sessionRunStarted && isAiReplyActive() && !receivedGreetingsTaskActive &&
            target.runGeneration == currentRunGeneration() && observedTaskLeaseCurrent(binding) &&
            identity.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED && !identity.contactId.isNullOrBlank() &&
            snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE && snapshot.packageName == targetPackage() &&
            snapshot.conversationIdentityKey() == target.snapshot.conversationIdentityKey() &&
            ChatSessionManagementPolicy.normalizeSessionTitle(snapshot.chatTitleText) ==
                ChatSessionManagementPolicy.normalizeSessionTitle(target.snapshot.chatTitleText) &&
            binding.matches(snapshot, windowSessionKey(snapshot)) &&
            (!binding.observedBusinessAdmitted ||
                currentSessionKey == ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact(identity.contactId))
    }

    private fun observedPrivateDetailVerified(snapshot: ChatPageSnapshot): Boolean =
        observedPrivateBindingMatches(snapshot) && canPerformPrivateMessagesUiAction() && !userInteractionActive

    private fun admitObservedPrivateBusiness(snapshot: ChatPageSnapshot): Boolean {
        val binding = currentSessionBinding ?: return false
        if (binding.observedBusinessAdmitted) return observedPrivateDetailVerified(snapshot)
        val identity = binding.observedVerifiedIdentity ?: return false
        val key = ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact(identity.contactId)
        val title = snapshot.chatTitleText?.trim() ?: return false
        val peer = title.takeIf { it.startsWith("与") && it.endsWith("对话") }
            ?.removePrefix("与")?.removeSuffix("对话") ?: return false
        val window = windowSessionKey(snapshot) ?: return false
        if (!observedPrivateDetailVerified(snapshot) || key.isBlank() ||
            chatSessionManagementPort.isSessionIdentityBlocked(key)) return false
        val executor = privateMessagesTraversalTaskExecutor
        // Re-use the retained entry after a transient repository write failure.
        val existing = executor.currentUnfinishedSession()
        if (existing == null) {
            if (!executor.beginVerifiedObservedDetail(snapshot, key, peer, window, System.currentTimeMillis())) {
                onLog("OBSERVED_PRIVATE_BUSINESS_WAIT reason=UNFINISHED_IDENTITY_NOT_UNIQUE")
                return false
            }
        } else if (existing.verifiedKey != key || existing.conversationKey != snapshot.conversationIdentityKey() ||
            currentSessionKey != key || currentSessionWindowKey != window) return false
        if (runCatching { privateMessagesCheckpointRepository?.save(executor.checkpoint()) }.isFailure) {
            onLog("OBSERVED_PRIVATE_BUSINESS_WAIT reason=CHECKPOINT_NOT_SAVED")
            return false
        }
        sessionManagerState = SessionManagerState.READING_SESSION
        currentSessionSendConfirmed = false
        currentSessionBinding = binding.copy(observedBusinessAdmitted = true)
        onLog("OBSERVED_PRIVATE_BUSINESS_ADMITTED recovered=${executor.recoveringUnfinishedSession};listCursorPreserved=true")
        return true
    }

    private fun handleObservedPrivateDetail(
        observation: ObservationResult,
        snapshot: ChatPageSnapshot,
        matchedRouteId: String?
    ): Boolean {
        val binding = currentSessionBinding ?: return false
        val target = binding.observedDetailTarget ?: return false
        // The unified recovery transaction has precedence over this ordinary observer.
        if (recoveryContext.pending) return false
        if (binding.observedBusinessAdmitted) {
            val sameDetail = observation.status == ObservationStatus.CONFIRMED_PAGE &&
                matchedRouteId == chatDetailRecoveryRoute.routeId && observedPrivateBindingMatches(snapshot)
            if (!sameDetail) {
                requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, "OBSERVED_DETAIL_DEPARTED")
                scheduleFullInspectionAfter(0L)
                return true
            }
            if (sessionManagerState == SessionManagerState.RETURNING_TO_SESSION_LIST || userInteractionActive) return true
            if (currentSessionSendConfirmed) {
                return continueConfirmedPrivateCompletion(snapshot)
            }
            if (!observedPrivateDetailVerified(snapshot) ||
                !AndroidRecoveryExecutionGate.assess(snapshot, targetPackage(),
                    runCatching { inspectAndroidRecoveryReadiness(snapshot) }.getOrNull()).allowed
            ) {
                scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
                return true
            }
            if (!isConversationDraftPending() || isSendConfirmationPending() || isSendControlRecoveryPending()) {
                readVisibleMessages(snapshot)
            }
            return true
        }
        if (!sessionRunStarted || !isAiReplyActive() || receivedGreetingsTaskActive ||
            !ownsTask(PRIVATE_MESSAGES_TASK_ID) ||
            receivedGreetingsOwnershipRecheckPending || awaitingNormalInboxAfterGreetingsReturn ||
            target.runGeneration != currentRunGeneration() || !observedTaskLeaseCurrent(binding) ||
            isSendConfirmationPending() || isSendControlRecoveryPending() || isConversationDraftPending()
        ) {
            retireObservedPrivateDetail("TASK_OR_TRANSACTION_CHANGED")
            return false
        }
        if (observation.status != ObservationStatus.CONFIRMED_PAGE ||
            matchedRouteId != chatDetailRecoveryRoute.routeId ||
            snapshot.packageName != targetPackage() || snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE
        ) {
            if (observation.status != ObservationStatus.CONFIRMED_PAGE &&
                chatSessionManagementPort.hasPendingObservedPrivateChatIdentityCapture(target)
            ) {
                scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
                return true
            }
            retireObservedPrivateDetail("PAGE_CHANGED")
            return false
        }
        val originalWindow = target.sourceWindow()
        val currentWindow = snapshot.messageListTarget?.window
        val sameSource = originalWindow != null && currentWindow != null &&
            originalWindow.packageName == currentWindow.packageName &&
            originalWindow.windowId == currentWindow.windowId &&
            snapshot.conversationIdentityKey() == target.snapshot.conversationIdentityKey()
        val readiness = runCatching { inspectAndroidRecoveryReadiness(snapshot) }.getOrNull()
        val windowKey = windowSessionKey(snapshot)
        val surfaceKey = chatSurfaceKey(snapshot)
        if (!sameSource || windowKey == null || surfaceKey == null) {
            retireObservedPrivateDetail("DETAIL_IDENTITY_CHANGED")
            scheduleFullInspectionAfter(350L)
            return true
        }
        if (!AndroidRecoveryExecutionGate.assess(snapshot, targetPackage(), readiness).allowed ||
            userInteractionActive
        ) {
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return true
        }
        if (observedPrivateDetailVerified(snapshot)) {
            if (admitObservedPrivateBusiness(snapshot)) readVisibleMessages(snapshot)
            else scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return true
        }
        val returned = chatSessionManagementPort.hasObservedPrivateChatIdentityReturn(target)
        if (!binding.matches(snapshot, windowKey) && !returned) {
            retireObservedPrivateDetail("DETAIL_WINDOW_CHANGED_WITHOUT_RETURN")
            scheduleFullInspectionAfter(350L)
            return true
        }
        val outcome = runCatching { chatSessionManagementPort.observeContactIdentityCapture(target) }
            .getOrElse { ContactIdentityCaptureOutcome.waiting(ContactIdentityCaptureRequestResult.RETRY_LATER) }
        val identity = outcome.verifiedIdentity
        if (identity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
            !identity.contactId.isNullOrBlank() && returned &&
            chatSessionManagementPort.consumeObservedPrivateChatIdentityReturn(target)
        ) {
            currentSessionBinding = binding.copy(chatWindowKey = windowKey, chatSurfaceKey = surfaceKey,
                observedVerifiedIdentity = identity)
            onLog("OBSERVED_PRIVATE_CHAT_IDENTITY_VERIFIED source=PROFILE_RETURN;listCursorPreserved=true")
            if (admitObservedPrivateBusiness(snapshot)) readVisibleMessages(snapshot)
            else scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
        } else {
            // No name-only fallback or cached list ID for a detail-origin visit.
            onLog("OBSERVED_PRIVATE_CHAT_IDENTITY_WAIT request=${outcome.request.name};verified=false")
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
        }
        return true
    }

    private fun claimTaskOwnership(taskId: String, reason: String, verifiedHandoff: Boolean = false): Boolean {
        val claimed = when (taskId) {
            PrivateMessagesTaskController.TASK_ID -> !receivedGreetingsOwnershipRecheckPending &&
                privateMessagesTaskController.claimOwnership(reason)
            ReceivedGreetingsTaskController.TASK_ID -> receivedGreetingsTaskFlow.claimOwnership(reason)
            else -> false
        }
        if (claimed && taskCoordinator != null) {
            val lease = taskCoordinator.currentLease()?.takeIf {
                it.taskId == taskId && it.runGeneration == currentRunGeneration()
            } ?: return false
            if (!onPageTaskOwnershipConfirmed(lease, verifiedHandoff)) {
                taskOwnership.release(taskId, "页面任务归属未确认：$reason")
                return false
            }
        }
        return claimed
    }

    private fun recoveryRouteIdForTask(taskId: String?): String? =
        recoveryRouteForTask(taskId)?.routeId

    private fun taskIdForRecoveryRoute(routeId: String): String? =
        sharedPageObservationCoordinator.recoveryAdapterForRoute(routeId)?.taskId?.takeIf {
            it == PRIVATE_MESSAGES_TASK_ID || it == RECEIVED_GREETINGS_TASK_ID
        }

    private fun recoveryRouteForTask(taskId: String?): PageRecoveryRouteAdapter? =
        taskId?.takeIf { it == PRIVATE_MESSAGES_TASK_ID || it == RECEIVED_GREETINGS_TASK_ID }
            ?.let(sharedPageObservationCoordinator::primaryRecoveryAdapterForTask)

    private fun activateRecoveryIdentity(
        taskId: String,
        reason: String,
        checkpointId: String,
        routeId: String = recoveryRouteIdForTask(taskId) ?: "unknown-route"
    ) {
        val generation = currentRunGeneration().coerceAtLeast(0L)
        val observationGeneration = pageReadCoordinator.currentObservationGeneration()
        recoveryContext.request(
            taskId = taskId,
            recoveryReason = reason,
            routeId = routeId,
            generation = generation,
            recoveryCheckpointId = checkpointId,
            pageObservationGeneration = observationGeneration
        )
        recoveryTransaction.activate(
            RecoveryEventIdentity(
                taskId = taskId,
                routeId = routeId,
                runGeneration = generation,
                checkpointId = checkpointId,
                observationGeneration = observationGeneration,
                unknownReason = reason
            )
        )
    }

    /**
     * A private chat may briefly lose its snapshot while the adapter returns from a verified
     * identity profile. The active task's primary recovery route is the inbox, but the
     * in-flight chat is a subpage. This anchor selects a destination only; it never grants
     * permission to read or send before fresh detail evidence and the UI lease are verified.
     */
    private fun hasBoundPrivateDetailRecoverySession(): Boolean {
        if (!sessionRunStarted || receivedGreetingsTaskActive || !isAiReplyActive() ||
            !ownsTask(PRIVATE_MESSAGES_TASK_ID)
        ) return false
        val binding = currentSessionBinding ?: return false
        if (binding.observedBusinessAdmitted) {
            val identity = binding.observedVerifiedIdentity ?: return false
            val key = ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact(identity.contactId)
            return key.isNotBlank() && currentSessionKey == key &&
                binding.observedDetailTarget?.runGeneration == currentRunGeneration() &&
                observedTaskLeaseCurrent(binding) && binding.chatWindowKey == currentSessionWindowKey &&
                identity.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                !chatSessionManagementPort.isSessionIdentityBlocked(key)
        }
        val candidate = currentSessionCandidate ?: return false
        val expected = expectedPrivateSessionDetailNavigation ?: return false
        val chatWindowKey = currentSessionWindowKey ?: return false
        val selectedKey = ChatSessionManagementPolicy.stableSessionKey(candidate)
        val sourceWindow = candidate.rowTarget.window
        val sourceKey = listOf(
            sourceWindow.packageName, sourceWindow.windowId, sourceWindow.generation
        ).joinToString("|")
        return currentSessionKey == selectedKey &&
            expected.sessionKey == selectedKey &&
            expected.sourceWindowKey == sourceKey &&
            binding.sourceWindowKey == sourceKey &&
            binding.chatWindowKey == chatWindowKey &&
            !chatSessionManagementPort.isSessionIdentityBlocked(selectedKey)
    }

    /** Recheck the actual detail surface independently of the saved source-row evidence. */
    private fun isVerifiedRecoveredPrivateDetail(snapshot: ChatPageSnapshot): Boolean =
        if (currentSessionBinding?.observedBusinessAdmitted == true) {
            hasBoundPrivateDetailRecoverySession() && observedPrivateBindingMatches(snapshot)
        } else hasBoundPrivateDetailRecoverySession() &&
            snapshot.packageName == targetPackage() &&
            snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            isExpectedPrivateSessionDetail(snapshot) &&
            currentSessionHeaderMatchMode(snapshot) != null &&
            windowSessionKey(snapshot) != null

    /**
     * Retire the old detail execution route, not its business progress. Only called
     * with a freshly confirmed destination and Android readiness. The existing
     * list verifier (or cross-task handoff) must still earn fresh proof and ownership.
     */
    private fun retargetInterruptedPrivateDetail(
        observationGeneration: Long,
        confirmedPrivateInboxWithPendingSend: Boolean = false
    ): Boolean {
        val old = recoveryTransaction.currentIdentity() ?: return false
        val saved = recoveryContext.snapshot()
        if (!sessionRunStarted || !isAiReplyActive() || receivedGreetingsTaskActive ||
            !recoveryContext.matches(old) || old.taskId != PRIVATE_MESSAGES_TASK_ID ||
            old.routeId != chatDetailRecoveryRoute.routeId ||
            old.runGeneration != currentRunGeneration() ||
            observationGeneration <= old.observationGeneration ||
            !saved.checkpointSaved || isSendControlRecoveryPending() ||
            (isSendConfirmationPending() && !confirmedPrivateInboxWithPendingSend)
        ) return false

        val traversal = privateMessagesTraversalTaskExecutor
        var checkpoint = if (privateMessagesCheckpointRepository != null) {
            privateMessagesCheckpointRepository.load() ?: return false
        } else traversal.checkpoint()
        val listReason = privateListRecoveryReasonAfterObservedDetail ?: saved.reason ?: return false
        val next = old.copy(
            routeId = messageListRecoveryRoute.routeId,
            observationGeneration = observationGeneration,
            unknownReason = listReason
        )
        // Only the independently recognized private inbox may retire a submitted
        // send's live hold. The coordinator checks its existing durable journal
        // before invalidating callbacks; neither this step nor route selection
        // grants the list lease or claims that the message was sent.
        if (isSendConfirmationPending()) {
            // The durable send must have a corresponding original visit.
            if (traversal.currentUnfinishedSession() == null) {
                onLog("私聊手动返回[DEFER_REJECTED]：缺少原未完成会话检查点；保持只读恢复")
                return false
            }
            if (!runCatching { deferPrivateSendForManualReturn() }.getOrDefault(false) ||
                isSendConfirmationPending() || isSendControlRecoveryPending() ||
                isConversationDraftPending()
            ) {
                onLog("私聊手动返回[DEFER_REJECTED]：原发送证据或执行资格尚未安全释放；保持只读恢复")
                return false
            }
            if (!traversal.deferCurrentUnfinishedSend()) return false
            deferredSendProofRetryAttempt = 0
            currentSessionSendConfirmed = false
            onLog("私聊手动返回[CONTINUE_LIST]：未确认发送已落盘；原会话延期；等待列表双快照和操作权")
        }
        // A failed save/rebind keeps this route pending. Retry the same parked
        // checkpoint before exposing it to the independent list verifier.
        if (traversal.currentUnfinishedSession()?.disposition ==
            UnfinishedPrivateSessionDisposition.SEND_DEFERRED_THIS_ROUND
        ) {
            checkpoint = traversal.checkpoint()
            if (runCatching { privateMessagesCheckpointRepository?.save(checkpoint) }.isFailure) {
                onLog("AI 私聊业务[DEFER_CHECKPOINT_WAIT]：延期记录尚未写入；保持原恢复路由")
                return false
            }
            onLog("AI 私聊业务[DEFERRED_THIS_ROUND]：保留原业务；本轮不重新打开延期会话")
        }
        // Existing runtime reset invalidates model callbacks and preserves durable
        // evidence for ordinary pre-send detail interruption.
        if (isConversationDraftPending()) {
            if (runCatching { onDraftRuntimeReset() }.isFailure ||
                isConversationDraftPending() || isSendConfirmationPending() ||
                isSendControlRecoveryPending()
            ) return false
        }
        if (recoveryTransactionManager?.rebindPendingRoute(old, next) == false) return false

        messageListCheckpointVerifier.reset()
        pageRecoveryVerificationGate.reset()
        recoveryContext.request(
            taskId = next.taskId,
            recoveryReason = listReason,
            routeId = next.routeId,
            generation = next.runGeneration,
            recoveryCheckpointId = next.checkpointId,
            pageObservationGeneration = next.observationGeneration
        )
        recoveryContext.markCheckpointSaved()
        if (saved.checkpointLoaded) recoveryContext.markCheckpointLoaded()
        recoveryTransaction.reset()
        recoveryTransaction.activate(next)
        recoveryTransaction.dispatch(RecoveryTransactionEvent.CheckpointLoaded(
            RecoveryTransactionEvent.Source.PAGE_OBSERVATION
        ))
        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))

        // Do not restore/reset the traversal executor: its existing cursor and
        // round ledger belong to the checkpoint, not to the departed UI window.
        messageListCheckpointForUnified = checkpoint
        messageListCheckpointRestored = true
        messageListCheckpointRestoreSource = RecoveryCheckpointRestoreSource.PAGE_OBSERVATION
        sessionActionGeneration += 1L
        invalidatePendingMessageRead()
        latestPrivateMessageBatch = null
        expectedPrivateSessionDetailNavigation = null
        retireObservedPrivateDetail("DETAIL_TO_LIST_RECOVERY")
        currentSessionBinding = null
        privateMessagesTraversalTaskExecutor.clearCurrentSession()
        // Retire the obsolete one-shot signal; this is not detail-return proof.
        if (chatSessionManagementPort.consumeIdentityReturnReinspectionRequest()) {
            onLog("AI 身份回接[REQUEST_RETIRED]：旧详情页执行路由已退出；不启动OCR或草稿")
        }
        lastIdentityReturnNotConsumedReason = null
        sessionManagerState = SessionManagerState.PAUSED
        onLog("统一恢复[DETAIL_TO_LIST_ROUTE_REBOUND]：checkpointId=${next.checkpointId}；" +
            "原业务检查点保留；旧详情回调失效；等待列表独立验证及UI操作权")
        return true
    }

    /** Single pause/checkpoint entry used by page-route changes and UNKNOWN observations. */
    private fun requestUnifiedPauseAndCheckpoint(taskId: String, reason: String): Boolean {
        if (currentSessionBinding?.observedBusinessAdmitted != true) {
            retireObservedPrivateDetail("UNIFIED_RECOVERY")
        }
        if (recoveryContext.pending) {
            if (taskId == recoveryContext.sourceTaskId) {
                onLog("统一恢复[TRANSACTION_REUSED]：task=$taskId；checkpointId=${recoveryContext.checkpointId}；" +
                    "originalReason=${recoveryContext.reason}；observedReason=$reason；不覆盖原检查点")
            }
            return false
        }
        if (taskId == PRIVATE_MESSAGES_TASK_ID &&
            sessionManagerState == SessionManagerState.OPENING_SESSION
        ) {
            // A page-route change can race the delayed tap verification. Save only a
            // retriable checkpoint, never the provisional attempted-session marker.
            rollbackUnverifiedPrivateSessionTap("PAGE_RECOVERY_$reason")
        }
        if (taskId == PRIVATE_MESSAGES_TASK_ID &&
            sessionManagerState == SessionManagerState.RETURNING_TO_SESSION_LIST &&
            currentSessionSendConfirmed
        ) {
            // An uncertain post-BACK page must resume through the saved list checkpoint.
            // The confirmed send is not retried; its old session is cleared only on a
            // subsequently verified inbox, using the existing return-to-list handler.
            confirmedPrivateReturnAwaitingRecovery = true
            pageRecoveryVerificationGate.reset(CONFIRMED_PRIVATE_RETURN_DETAIL_GATE)
        }
        // Initially keep a submitted send on its detail recovery route. Only a
        // fresh confirmed private inbox and a durable deferral may rebind it;
        // UNKNOWN and a different chat never release the original send hold.
        // Android READY, two independent snapshots and the UI lease remain mandatory.
        val pendingPrivateSend = taskId == PRIVATE_MESSAGES_TASK_ID &&
            !receivedGreetingsTaskActive && isSendConfirmationPending()
        // Other private recoveries retain the primary list route, except the existing
        // bound detail fallback for a transient empty snapshot.
        val recoverCurrentChatDetail = pendingPrivateSend ||
            (taskId == PRIVATE_MESSAGES_TASK_ID &&
                sessionManagerState == SessionManagerState.READING_SESSION &&
                hasBoundPrivateDetailRecoverySession() &&
                (reason == "EMPTY_SNAPSHOT" || reason.startsWith("PRIVATE_DETAIL_")))
        val route = if (recoverCurrentChatDetail) chatDetailRecoveryRoute
            else (recoveryRouteForTask(taskId) ?: return false)
        route.saveCheckpoint()
        if (taskId == PRIVATE_MESSAGES_TASK_ID) {
            messageListCheckpointVerifier.reset()
            messageListCheckpointForUnified = if (recoverCurrentChatDetail) null
                else privateMessagesCheckpointRepository?.load()
            if (!recoverCurrentChatDetail) messageListCheckpointRestored = true
            messageListCheckpointRestoreSource = RecoveryCheckpointRestoreSource.PAGE_OBSERVATION
            if (recoverCurrentChatDetail) {
                if (pendingPrivateSend) {
                    onLog("AI 发送恢复[DETAIL_ROUTE_HELD]：reason=$reason；pendingSend=true；保留原聊天发送检查点，禁止消息列表遍历或重发")
                } else {
                    onLog("AI 聊天详情恢复[ROUTE_SELECTED]：保留当前会话检查点；等待独立的新鲜详情页验证")
                }
            }
        } else if (taskId == RECEIVED_GREETINGS_TASK_ID) {
            receivedGreetingsCheckpointVerifier.reset()
            receivedGreetingsCheckpointForUnified = receivedGreetingsCheckpointRepository?.load()
            receivedGreetingsCheckpointRestoreSource = RecoveryCheckpointRestoreSource.PAGE_OBSERVATION
        }
        val savedAtEpochMs = when (taskId) {
            PRIVATE_MESSAGES_TASK_ID -> if (recoverCurrentChatDetail)
                privateMessagesCheckpointRepository?.load()?.savedAtEpochMs
                else messageListCheckpointForUnified?.savedAtEpochMs
            RECEIVED_GREETINGS_TASK_ID -> receivedGreetingsCheckpointForUnified?.savedAtEpochMs
            else -> null
        }
        val recoveryReason = if (taskId == PRIVATE_MESSAGES_TASK_ID && route.routeId == messageListRecoveryRoute.routeId) {
            privateListRecoveryReasonAfterObservedDetail ?: reason
        } else reason
        activateRecoveryIdentity(
            taskId, recoveryReason, durableRecoveryCheckpointId(taskId, savedAtEpochMs, recoveryReason),
            routeId = route.routeId
        )
        recoveryContext.markCheckpointSaved()
        recoveryTransaction.dispatch(RecoveryTransactionEvent.UnknownPage(recoveryReason))
        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        // A new recovery transaction also invalidates pending structure/OCR callbacks.
        // Their old batch cannot resume identity processing or start a draft before route proof.
        invalidatePendingMessageRead()
        // Revoke callback authority BEFORE cancelling a still-active old command.
        // The original gesture policy owns actual cancellation; never replay its path.
        sessionActionGeneration += 1L
        val interruptedGestureRequestId = activeSessionGestureRequestId
        activeSessionGestureRequestId = null
        if (interruptedGestureRequestId != null) {
            gesturePolicy.cancelActive(interruptedGestureRequestId, "page_recovery:$reason")
        }
        if (taskId == PRIVATE_MESSAGES_TASK_ID) {
            startupReadinessCoordinator.suspendForPageRecovery()
        }
        incompleteUnreadRelocationWait = null
        cancelAiReadinessRetry(resetCount = true)
        cancelSessionSafeViewportRetry(resetCount = true)
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        pageInspectionTask?.cancel()
        pageInspectionTask = null
        if (taskId == RECEIVED_GREETINGS_TASK_ID && !isGreetingsCompletionReturnRecovery()) {
            receivedGreetingsTaskFlow.suspendForRecovery()
        }
        sessionManagerState = SessionManagerState.PAUSED
        onLog(
            "统一恢复[PAUSED_CHECKPOINT_SAVED]：task=$taskId；reason=$reason；" +
                "已保存检查点并暂停全部滚动、点击和导航"
        )
        return true
    }

    /**
     * Treat transient platform gesture failures as UNKNOWN transactions rather than permanent
     * business pauses. Reacquire the page through the existing read-only scheduler; no gesture
     * command, stale row, or previous coordinates are replayed.
     */
    private fun recoverTransientPrivateGesture(reason: String, delayMs: Long = 0L) {
        privateMessagesTraversalTaskExecutor.pendingScrollBaseline = null
        privateMessagesTraversalTaskExecutor.pendingScrollToTop = false
        if (!isAiReplyActive() || !sessionRunStarted) return
        val created = requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, reason)
        if (created || recoveryContext.pending) {
            onLog("统一恢复[GESTURE_RECOVERY_REQUESTED]：task=$PRIVATE_MESSAGES_TASK_ID；reason=$reason；保留检查点，继续只读观察")
            if (created) {
                scheduleFullInspectionAfter(delayMs)
            } else if (recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
                recoveryContext.routeId == messageListRecoveryRoute.routeId
            ) {
                // A cancelled cold-start gesture still needs the same bounded retry delay;
                // do not replace it with the slower checkpoint poll when the page is unchanged.
                scheduleFullInspectionAfter(delayMs)
            }
        } else {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("统一恢复[GESTURE_RECOVERY_UNAVAILABLE]：reason=$reason；无法建立恢复检查点，禁止继续手势")
        }
    }

    /**
     * A confirmed retryable list uncertainty must enter the same checkpointed recovery
     * transaction as UNKNOWN. The existing read-only backoff is retained; a stable
     * fingerprint alone is not permission to ignore the original unread-state gate.
     */
    private fun recoverRetryablePrivateList(reason: String) {
        if (!sessionRunStarted || receivedGreetingsTaskActive || !isAiReplyActive()) return
        if (requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, reason) ||
            recoveryContext.pending
        ) {
            onLog("AI 私聊暂停分类[RETRYABLE]：reason=$reason；统一检查点恢复；禁止旧节点、手势和未证实的发送")
            if (recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
                recoveryContext.routeId == messageListRecoveryRoute.routeId
            ) {
                schedulePendingPrivateListObservation()
            } else {
                scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            }
        }
    }

    /**
     * Before a route verifier is bound (or with no persisted checkpoint), retain the
     * existing one-shot cadence instead of replacing its deadline on every event.
     * Once the shared plan exists it owns autonomous observations exclusively.
     */
    private fun schedulePendingPrivateListObservation() {
        if (messageListCheckpointVerifier.hasRetainedObservationPlan() || pageInspectionTask != null) return
        messageRecoveryRetryAttempt = (messageRecoveryRetryAttempt + 1)
            .coerceAtMost(RecoveryScheduler.fastRetryCount + 1)
        val delay = RecoveryScheduler.readOnlyRetryDelayMs(messageRecoveryRetryAttempt)
        onLog("统一恢复[READ_ONLY_RETRY_RETAINED]：checkpointId=${recoveryContext.checkpointId}；" +
            "attempt=$messageRecoveryRetryAttempt；delayMs=$delay")
        scheduleFullInspectionAfter(delay)
    }

    /** Pending-send UNKNOWN may recover only onto the same semantic chat-detail surface. */
    private fun requestSendConfirmationRecovery(reason: String): Boolean {
        if (recoveryContext.pending || !sessionRunStarted || receivedGreetingsTaskActive) return false
        chatDetailRecoveryRoute.saveCheckpoint()
        // Use the same task/route/run/checkpoint identity authority as list UNKNOWN.
        // Chat-detail recovery still performs only read-only send confirmation.
        activateRecoveryIdentity(
            taskId = PRIVATE_MESSAGES_TASK_ID,
            reason = reason,
            checkpointId = "$PRIVATE_MESSAGES_TASK_ID:$reason",
            routeId = chatDetailRecoveryRoute.routeId
        )
        recoveryContext.markCheckpointSaved()
        recoveryTransaction.dispatch(RecoveryTransactionEvent.UnknownPage(reason))
        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        sessionManagerState = SessionManagerState.PAUSED
        onLog("AI 发送恢复[PAUSED_CHECKPOINT_SAVED]：reason=$reason；停止危险动作，等待聊天详情页双快照后仅核验发送结果")
        scheduleFullInspectionAfter(0L)
        return true
    }

    private fun pageIdentityChanged(previous: ChatPageSnapshot, current: ChatPageSnapshot): Boolean =
        previous.packageName != current.packageName ||
            previous.activityClass != current.activityClass ||
            previous.windowIdentityKey() != current.windowIdentityKey() ||
            previous.pageKind != current.pageKind

    private fun ownsTask(taskId: String): Boolean = when (taskId) {
        PrivateMessagesTaskController.TASK_ID -> privateMessagesTaskController.ownsTask()
        ReceivedGreetingsTaskController.TASK_ID -> receivedGreetingsTaskFlow.ownsTask()
        else -> false
    }

    /** Read-only veto for the existing platform write gate; no lease is granted here. */
    fun isReceivedGreetingsRecoveryPending(): Boolean =
        recoveryContext.sourceTaskId == RECEIVED_GREETINGS_TASK_ID

    /** Read-only veto for an admitted detail-origin private visit during recovery/user touch. */
    fun isObservedPrivateRecoveryPending(): Boolean =
        currentSessionBinding?.observedBusinessAdmitted == true &&
            (recoveryContext.pending || userInteractionActive)

    /** The lease is a reserved task slot; the recovery context gates actual UI actions. */
    private fun canPerformPrivateMessagesUiAction(): Boolean =
        !recoveryContext.pending && !receivedGreetingsOwnershipRecheckPending &&
            ownsTask(PRIVATE_MESSAGES_TASK_ID)

    private fun releaseTaskOwnership(reason: String) {
        when (taskOwnership.activeTaskId()) {
            PrivateMessagesTaskController.TASK_ID -> privateMessagesTaskController.releaseOwnership(reason)
            ReceivedGreetingsTaskController.TASK_ID -> receivedGreetingsTaskFlow.releaseOwnership(reason)
            else -> taskOwnership.releaseCurrent(reason)
        }
    }

    /** A title-only traversal key is never a durable contact identity. */
    private fun verifiedPrivateLedgerContactKey(): String? {
        if (receivedGreetingsTaskActive || sessionManagerState != SessionManagerState.READING_SESSION) return null
        val binding = currentSessionBinding ?: return null
        val identity = if (binding.observedBusinessAdmitted) binding.observedVerifiedIdentity
            else currentSessionCandidate?.contactIdentity
        if (identity?.confidence != ContactIdentityConfidence.PLATFORM_ID_VERIFIED) return null
        val key = ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact(identity.contactId)
        return key.takeIf { it.isNotBlank() && it == currentSessionKey }
    }

    /** Confirmed private text sends only. No greeting count, no title-only durable identity. */
    fun recordSuccessfulSend(sendConfirmationId: String): ContactSendCountUpdate {
        val contactKey = verifiedPrivateLedgerContactKey()
        val interceptionThreshold = messageInterceptionThreshold().coerceAtLeast(0)
        val imageThreshold = imageSendThreshold().coerceAtLeast(0)
        val update = contactMessageLedger.recordSuccessfulSend(
            contactIdentityKey = contactKey,
            sendConfirmationId = sendConfirmationId,
            interceptionThreshold = interceptionThreshold,
            imageThreshold = imageThreshold
        )
        if (update.imageThresholdReachedNow || update.thresholdReachedNow) {
            onLog("PRIVATE_CONTACT_LEDGER FOLLOW_UP_PENDING;imageDue=${update.imageThresholdReachedNow};" +
                "interceptionDue=${update.thresholdReachedNow}")
        } else if (update.status == ContactSendRecordStatus.INVALID_INPUT) {
            onLog("PRIVATE_CONTACT_LEDGER RECORD_SKIPPED;reason=UNVERIFIED_PRIVATE_CONTACT_OR_INVALID_RECEIPT")
        }
        return update
    }

    /** The later private child transaction must supply its exact verified contact and checkpoint.
     * No FIFO contact selection and no implicit use of whatever chat is currently visible. */
    fun confirmMessageInterceptionEnabled(
        contactKey: String,
        expected: ContactFollowUpCheckpoint
    ): Boolean {
        if (receivedGreetingsTaskActive || verifiedPrivateLedgerContactKey() != contactKey) return false
        return contactMessageLedger.confirmInterceptionEnabled(contactKey, expected).also { confirmed ->
            if (confirmed) onLog("PRIVATE_CONTACT_LEDGER INTERCEPTION_CONFIRMED;contactRecordRetained=true")
        }
    }

    /** Request a fresh observation for page-driven startup/handoff without replacing
     * protected send or gesture verification reads. Business admission gates remain unchanged. */
    /** The established greeting navigation/return transaction owns its verified source page
     * until the existing completion gate transfers the lease. A transient inbox snapshot
     * must not cancel that transaction or grant the private task a competing capability. */
    fun isVerifiedGreetingsNavigationPending(): Boolean =
        receivedGreetingsTaskActive && ownsTask(RECEIVED_GREETINGS_TASK_ID) &&
            ((awaitingNormalInboxAfterGreetingsReturn && !isConversationDraftPending() &&
                !isSendConfirmationPending() && !recoveryContext.pending) ||
                // NAVIGATING also includes avatar/profile/chat transitions. It is not
                // proof that a newly visible ordinary inbox belongs to greetings.
                (privateToGreetingsHandoff != null && !recoveryContext.pending &&
                    !awaitingNormalInboxAfterGreetingsReturn &&
                    lastPageInspection?.pageKind == ChatPageKind.CHAT_LIST &&
                    receivedGreetingsTaskFlow.traversalStatus() == ReceivedGreetingsTraversalStatus.NAVIGATING))

    fun requestPageRouteObservation() {
        if (isPrivateFollowUpActive()) {
            if (isAiReplyActive() && sessionRunStarted && pageInspectionTask == null)
                scheduleFullInspectionAfter(fullInspectionDebounceMs)
            return
        }
        if (!isAiReplyActive() || !sessionRunStarted ||
            hasProtectedPrivateInspection() || hasProtectedSendInspection()
        ) return
        if (pageInspectionTask != null) return
        scheduleFullInspection()
    }

    /** Return true only after the selected AI business runtime has really initialized. */
    fun preparePageSelectedStartup(taskId: String): Boolean {
        require(taskId == PRIVATE_MESSAGES_TASK_ID || taskId == RECEIVED_GREETINGS_TASK_ID)
        if (!sessionRunStarted) scheduleInitialObservation(taskId)
        return sessionRunStarted
    }

    fun scheduleInitialObservation() = scheduleInitialObservation(null)

    private fun scheduleInitialObservation(selectedTaskId: String?) {
        pageSelectedPrivateStartupVerificationPending = false
        pageRecoveryVerificationGate.reset(PRIVATE_STARTUP_DETAIL_OWNERSHIP_GATE)
        resetCrossTaskGreetingsHandoffRetry()
        val failure = runCatching { draftStartupFailure() }.getOrDefault("MODEL_CONFIGURATION_LOAD_FAILED")
        if (failure != null) {
            sessionRunStarted = false
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 私聊暂停分类[CONFIGURATION_BLOCKED]：配置未就绪，禁止制造无依据恢复检查点")
            onLog("AI 回复启动被阻止：$failure；请修正配置后重新验证；未遍历列表")
            return
        }
        cancelAiReadinessRetry(resetCount = true)
        cancelSessionSafeViewportRetry(resetCount = true)
        lastRecoveryAndroidReadinessWait = null
        serviceInterruptionCheckpointPersisted = false
        startupReadinessCoordinator.cancelRecovery()
        recoveryContext.clear()
        suspendedPrivateRecovery = null
        privateListRecoveryReasonAfterObservedDetail = null
        messageListCheckpointVerifier.reset()
        receivedGreetingsCheckpointVerifier.reset()
        messageListCheckpointForUnified = null
        receivedGreetingsCheckpointForUnified = null
        val directory = runCatching {
            chatSessionManagementPort.beginSessionManagement()
        }.getOrNull()
        sampleCaptureActive = !directory.isNullOrBlank()
        sessionRunStarted = sampleCaptureActive
        if (selectedTaskId != null && !sessionRunStarted) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("AI 页面运行时[PREPARATION_PENDING]：样本目录暂不可用；保持运行授权并等待新鲜入口复查")
            return
        }
        if (selectedTaskId != null) {
            receivedGreetingsOwnershipRecheckPending = false
            receivedGreetingsProbeOrdinaryInboxConfirmed = false
            receivedGreetingsProbePrivateRouteWon = false
            onLog("AI 页面启动[SELECTED_TASK]：task=$selectedTaskId；仅激活当前任务检查点")
        }
        val selectedGreetingsRecord = if (selectedTaskId == RECEIVED_GREETINGS_TASK_ID && sessionRunStarted)
            receivedGreetingsCheckpointRepository?.load() else null
        if (sessionRunStarted && (selectedTaskId == null || selectedTaskId == RECEIVED_GREETINGS_TASK_ID) &&
            (chatPagePort.hasRestorableReceivedGreetingsCheckpoint() || selectedGreetingsRecord != null)
        ) {
            if (selectedTaskId == RECEIVED_GREETINGS_TASK_ID) {
                // Rehydrate adapter evidence only for the selected greetings task.
                chatPagePort.reloadReceivedGreetingsCheckpointAfterServiceReconnect()
            }
            val greetingsRecord = selectedGreetingsRecord ?: receivedGreetingsCheckpointRepository?.load()
            if (!recoveryContext.pending && greetingsRecord != null) {
                receivedGreetingsCheckpointForUnified = greetingsRecord
                receivedGreetingsCheckpointRestoreSource = RecoveryCheckpointRestoreSource.SERVICE_RECONNECT
            }
            if (!recoveryContext.pending) {
                activateRecoveryIdentity(
                    RECEIVED_GREETINGS_TASK_ID,
                    "SERVICE_RECONNECT_CHECKPOINT",
                    durableRecoveryCheckpointId(
                        RECEIVED_GREETINGS_TASK_ID, greetingsRecord?.savedAtEpochMs, "SERVICE_RECONNECT_CHECKPOINT"
                    )
                )
                recoveryContext.markCheckpointLoaded()
            }
            recoveryTransaction.dispatch(
                RecoveryTransactionEvent.CheckpointLoaded(RecoveryTransactionEvent.Source.SERVICE_RECONNECT)
            )
            onLog(
                "收到招呼进程恢复：已保留持久化检查点；等待收到招呼列表页面指纹确认后再恢复任务，" +
                    "不因断点存在而执行盲目导航"
            )
        }
        // Adapters that support Received Greetings must identify the visible business page
        // before assigning the exclusive UI-operation lease. Legacy adapters without that
        // workflow retain the existing private-message startup contract.
        privateMessagesTaskController.beginRun(currentRunGeneration())
        val supportsReceivedGreetingsStartup = selectedTaskId != null ||
            (receivedGreetingsTaskFlow.supportsWorkflow() && pageSelectedStartup)
        startupReadinessCoordinator.resetState(sessionStarted = sessionRunStarted)
        resetPairWake()
        privateToGreetingsHandoff = null
        privateRelocationRetryOwed = false
        resetPairedQuotaRun()
        receivedGreetingsTaskFlow.reset()
        // Keep the existing lease lifecycle for compatibility, but do not perform any UI action
        // until the first fresh page event has classified the visible business route.
        receivedGreetingsPageProbePending = sessionRunStarted && supportsReceivedGreetingsStartup
        if (sessionRunStarted && !supportsReceivedGreetingsStartup &&
            !claimTaskOwnership(PRIVATE_MESSAGES_TASK_ID, "启动私人消息任务")
        ) {
            sessionRunStarted = false
            onLog("AI Reply 启动暂停：私人消息任务未取得唯一 UI 操作权；不会执行自动滚动或打开会话")
        } else if (sessionRunStarted && supportsReceivedGreetingsStartup) {
            onLog(
                "AI Reply 启动仲裁：暂不申请私人消息操作权；" +
                    "等待首个页面快照确认收到的招呼或私人消息列表"
            )
        }
        receivedGreetingsRecoveryRetryAttempt = 0
        privateMessagesTraversalTaskExecutor.initialInboxPositionCheckCompleted = false
        privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted = false
        privateMessagesTraversalTaskExecutor.initialUnreadGateSwipeCompleted = false
        privateMessagesTraversalTaskExecutor.lockedUnreadSessionKeys.clear()
        privateMessagesTraversalTaskExecutor.lockedUnreadSetFinalized = false
        privateMessagesTraversalTaskExecutor.normalInboxHadUnread = false
        privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown = false
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
        messageListRecoveryVerificationPending = false
        messageRecoveryRetryAttempt = 0
        if (selectedTaskId == null || selectedTaskId == PRIVATE_MESSAGES_TASK_ID) privateMessagesCheckpointRepository?.load()?.let { checkpoint ->
            privateMessagesTraversalTaskExecutor.restore(checkpoint)
            messageListCheckpointRestored = true
            messageListCheckpointForUnified = checkpoint
            messageListCheckpointRestoreSource = RecoveryCheckpointRestoreSource.SERVICE_RECONNECT
            if (!recoveryContext.pending) {
                activateRecoveryIdentity(
                    PRIVATE_MESSAGES_TASK_ID,
                    "SERVICE_RECONNECT_CHECKPOINT",
                    durableRecoveryCheckpointId(PRIVATE_MESSAGES_TASK_ID, checkpoint.savedAtEpochMs, "SERVICE_RECONNECT_CHECKPOINT")
                )
                recoveryContext.markCheckpointLoaded()
            }
            recoveryTransaction.dispatch(
                RecoveryTransactionEvent.CheckpointLoaded(RecoveryTransactionEvent.Source.SERVICE_RECONNECT)
            )
            onLog("消息列表任务恢复：已重新装载检查点；等待连续两次消息列表结构快照确认")
        }
        if (selectedTaskId != null && recoveryContext.pending) {
            // Do not let the optional greeting startup probe intercept the selected
            // task's durable checkpoint verification before its first sample.
            receivedGreetingsPageProbePending = false
        }
        pageSelectedPrivateStartupVerificationPending =
            selectedTaskId == PRIVATE_MESSAGES_TASK_ID && sessionRunStarted &&
                !recoveryContext.pending && chatPagePort.supportsVerifiedMessageWorkAreaNavigation()
        sessionManagerState = if (sessionRunStarted && !recoveryContext.pending) {
            SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP
        } else {
            SessionManagerState.PAUSED
        }
        when {
            sessionRunStarted -> {
                onLog(
                    "AI Reply 完整模型链路已启动：普通聊天按底部至顶部扫描；" +
                        "进入会话读取消息、调用模型并自动发送；样本目录=$directory"
                )
                if (recoveryContext.pending) {
                    onLog(
                        "统一恢复[CHECKPOINT_LOADED]：task=${recoveryContext.sourceTaskId}；" +
                            "已读取检查点，等待目标页面路由和双快照验证；不启动工作区导航"
                    )
                    scheduleFullInspectionAfter(0L)
                } else if (supportsReceivedGreetingsStartup) {
                    onLog(
                        "AI Reply 启动页面探针：等待首个新鲜页面事件确认任务归属；" +
                            "收到的招呼页面优先转交收到招呼任务，普通消息列表保持私人消息租约"
                    )
                } else if (chatPagePort.supportsVerifiedMessageWorkAreaNavigation()) {
                    startupReadinessCoordinator.beginVerification()
                } else {
                    // Compatibility path for adapters that do not expose a stable navigation
                    // anchor. The Momo adapter opts into the stricter, snapshot-verified flow.
                    val entered = runCatching { chatPagePort.ensureMessageWorkArea() }.getOrDefault(false)
                    if (!entered) {
                        onLog("AI Reply 启动暂停：适配器未能通过消息工作区入口；不执行后续滑动或会话点击")
                        recoverRetryablePrivateList("PRIVATE_STARTUP_WORK_AREA_NOT_READY")
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

    /** Re-reads the durable greetings checkpoint; current-page confirmation is still pending. */
    fun reloadReceivedGreetingsCheckpointAfterServiceReconnect(): Boolean = runCatching {
        val pageVerified = chatPagePort.reloadReceivedGreetingsCheckpointAfterServiceReconnect()
        val checkpoint = receivedGreetingsCheckpointRepository?.load()
        if (checkpoint != null && (!recoveryContext.pending ||
                recoveryContext.sourceTaskId == RECEIVED_GREETINGS_TASK_ID)
        ) {
            // A service reconnect must rebind an old greetings recovery transaction to the
            // current run generation, without overriding another task's pending transaction.
            receivedGreetingsCheckpointVerifier.reset()
            receivedGreetingsCheckpointForUnified = checkpoint
            receivedGreetingsCheckpointRestoreSource = RecoveryCheckpointRestoreSource.SERVICE_RECONNECT
            activateRecoveryIdentity(
                RECEIVED_GREETINGS_TASK_ID,
                "SERVICE_RECONNECT_CHECKPOINT",
                durableRecoveryCheckpointId(RECEIVED_GREETINGS_TASK_ID, checkpoint?.savedAtEpochMs, "SERVICE_RECONNECT_CHECKPOINT")
            )
            recoveryContext.markCheckpointLoaded()
            recoveryTransaction.dispatch(
                RecoveryTransactionEvent.CheckpointLoaded(RecoveryTransactionEvent.Source.SERVICE_RECONNECT)
            )
            scheduleFullInspectionAfter(0L)
        } else if (pageVerified && !recoveryContext.pending) {
            // Legacy fixture without a readable business repository retains its old behavior.
            activateRecoveryIdentity(
                RECEIVED_GREETINGS_TASK_ID,
                "SERVICE_RECONNECT_CHECKPOINT",
                durableRecoveryCheckpointId(RECEIVED_GREETINGS_TASK_ID, checkpoint?.savedAtEpochMs, "SERVICE_RECONNECT_CHECKPOINT")
            )
            recoveryContext.markCheckpointLoaded()
            recoveryTransaction.dispatch(
                RecoveryTransactionEvent.CheckpointLoaded(RecoveryTransactionEvent.Source.SERVICE_RECONNECT)
            )
        }
        pageVerified
    }.getOrDefault(false)

    /** Reloads the message-list checkpoint and accepts it only with a fresh list-page check. */
    fun reloadPrivateMessagesCheckpointAfterServiceReconnect(): Boolean {
        val checkpoint = privateMessagesCheckpointRepository?.load() ?: return false
        val retained = messageListCheckpointForUnified
        if (recoveryContext.pending && recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
            recoveryContext.routeId == messageListRecoveryRoute.routeId && retained != null
        ) {
            // Do not let a duplicate reconnect overwrite a pending business cursor,
            // checkpoint identity or the active retry deadline with a newer save.
            if (retained.savedAtEpochMs != checkpoint.savedAtEpochMs) {
                onLog("消息列表服务重连[CHECKPOINT_MISMATCH]：保留原待恢复检查点，不覆盖运行中的事务")
                return false
            }
            messageListCheckpointVerifier.deferOwnership()
            recoveryContext.invalidateFingerprint()
            onLog("消息列表服务重连[TRANSACTION_REUSED]：原检查点身份及退避计划保留；重新验证页面")
            scheduleFullInspectionAfter(0L)
            return true
        }
        privateMessagesTraversalTaskExecutor.restore(checkpoint)
        messageListCheckpointVerifier.reset()
        messageListCheckpointRestored = true
        messageListCheckpointForUnified = checkpoint
        messageListCheckpointRestoreSource = RecoveryCheckpointRestoreSource.SERVICE_RECONNECT
        if (!recoveryContext.pending) {
            activateRecoveryIdentity(
                PRIVATE_MESSAGES_TASK_ID,
                "SERVICE_RECONNECT_CHECKPOINT",
                durableRecoveryCheckpointId(PRIVATE_MESSAGES_TASK_ID, checkpoint.savedAtEpochMs, "SERVICE_RECONNECT_CHECKPOINT")
            )
            recoveryContext.markCheckpointLoaded()
            recoveryTransaction.dispatch(
                RecoveryTransactionEvent.CheckpointLoaded(RecoveryTransactionEvent.Source.SERVICE_RECONNECT)
            )
        }
        onLog(
            "消息列表服务重连：已重新读取检查点；交由统一页面路由进行双快照复核"
        )
        scheduleFullInspectionAfter(0L)
        return true
    }

    /** Schedules bounded page or known-region inspection from a normalized, redacted event. */
    fun schedule(event: AccessibilityEventEnvelope) {
        if (isPrivateFollowUpActive()) {
            requestPageRouteObservation()
            return
        }
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
            !receivedGreetingsTaskActive &&
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
        if (windowTransition && sessionRunStarted && !recoveryContext.pending &&
            !receivedGreetingsTaskActive &&
            sessionManagerState == SessionManagerState.SCROLLING_SESSION_LIST &&
            ownsTask(PRIVATE_MESSAGES_TASK_ID)
        ) {
            // A window transition during an outstanding list gesture is not proof of success.
            // Do not run its old callback or replay its old coordinates after the window changes.
            recoverTransientPrivateGesture("GESTURE_WINDOW_CHANGED")
        }
        if (windowTransition && pairWake != null) {
            pageRecoveryVerificationGate.reset(NEXT_PAIR_WAKE_GATE)
        }
        if (windowTransition && awaitingNormalInboxAfterGreetingsReturn) {
            pageRecoveryVerificationGate.reset(GREETINGS_TO_PRIVATE_HANDOFF_GATE)
            invalidateGreetingsCompletionReturnEvidence()
        }
        if (windowTransition && recoveryContext.pending) {
            // A window transition interrupts recovery evidence even before the next page read.
            // Checkpoint verifiers must not reuse their first sample from the previous window.
            recoveryContext.invalidateFingerprint()
            if (recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
                messageListCheckpointForUnified != null
            ) {
                messageListCheckpointVerifier.deferOwnership()
            } else if (recoveryContext.sourceTaskId == RECEIVED_GREETINGS_TASK_ID &&
                receivedGreetingsCheckpointForUnified != null
            ) {
                receivedGreetingsCheckpointVerifier.deferResume()
            }
        }
        if (windowTransition || chatListNavigation) {
            pageRecoveryVerificationGate.reset(PRIVATE_STARTUP_DETAIL_OWNERSHIP_GATE)
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
            if (hasProtectedSendInspection()) {
                // Window/route invalidation above is still mandatory. Only the
                // pending fresh read survives; it cannot reuse the old snapshot.
                onLog("AI 发送确认[ACTIVE_RECHECK_RETAINED]：保留已排定的只读核验；event=${event.type}")
                return
            }
            if (hasProtectedPrivateInspection()) {
                // Route/window invalidation above still runs for every event. The
                // explicit fresh read must not be replaced by a sliding debounce.
                onLog("AI 页面观察[ACTIVE_RECHECK_RETAINED]：保留已排定的手势复查；event=${event.type}")
                return
            }
            pageInspectionTask?.cancel()
            pageInspectionTask = null
            if (receivedGreetingsPageProbePending) {
                pendingPageReadPurpose = AiReplyPageReadPurpose.RECEIVED_GREETINGS
            }
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
        if (isPrivateFollowUpActive()) return
        if (!isAiReplyActive() || !chatPagePort.supportsTargetPackage(targetPackage())) return
        if (sessionRunStarted && recoveryContext.pending) {
            onLog("AI 身份业务续接[READ_DEFERRED]：恢复事务未完成；禁止旧消息批次启动 OCR 或草稿")
            return
        }
        if (sessionRunStarted) {
            if (!receivedGreetingsTaskActive && continueDeferredPrivateReadReturn(snapshot)) return
            if (!receivedGreetingsTaskActive && currentSessionSendConfirmed &&
                sessionManagerState == SessionManagerState.READING_SESSION
            ) {
                continueConfirmedPrivateCompletion(snapshot)
                return
            }
            if (currentSessionBinding?.observedDetailTarget != null) {
                if (currentSessionBinding?.observedBusinessAdmitted == true &&
                    observedPrivateDetailVerified(snapshot) &&
                    sessionManagerState == SessionManagerState.READING_SESSION &&
                    !currentSessionSendConfirmed && ocrAdmissionRetryTask == null &&
                    (!isConversationDraftPending() || isSendConfirmationPending() || isSendControlRecoveryPending())
                ) messageReadCoordinator.request(snapshot)
                return
            }
            if (pageSelectedPrivateStartupVerificationPending && !receivedGreetingsTaskActive &&
                currentSessionCandidate == null && currentSessionBinding == null
            ) {
                // A startup detail lease is not a conversation binding. The same guard
                // covers known-region refreshes while the original list startup is pending.
                return
            }
            if (receivedGreetingsTaskActive) {
                // The region-refresh path reaches this entry too. Apply the same draft hold
                // as full observation, while preserving pending-send and confirmed-return proof.
                if (isConversationDraftPending() && !isSendConfirmationPending() &&
                    confirmedGreetingSend == null
                ) return
                // The authoritative entry includes task ownership, profile identity, header,
                // window and the one-time adapter DETAIL_READY proof before any OCR starts.
                if (!ensureGreetingChatBinding(snapshot)) {
                    onLog("GREETING_CHAT_READ_DEFERRED reason=CHAT_BINDING_NOT_VERIFIED")
                    scheduleReceivedGreetingsRecoveryRetry(
                        "收到招呼聊天页或会话绑定未就绪；等待同一联系人新鲜页面证据"
                    )
                    return
                }
            } else {
                if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
                    onLog("消息详情读取被阻止：当前任务未持有 UI 操作权；task=$PRIVATE_MESSAGES_TASK_ID")
                    return
                }
                if (greetingChatBinding != null) {
                    // Private-message reads must not inherit a previous greeting OCR transaction.
                    invalidatePendingMessageRead()
                }
            }
        }
        if (ocrAdmissionRetryTask != null) return
        messageReadCoordinator.request(snapshot)
    }

    /**
     * Revalidates the active page/window/session before any message batch reaches business
     * callbacks or sample persistence. Received greetings is source-bound to the verified
     * greeting row instead of a private-message session checkpoint.
     */
    private fun isMessageReadResultCurrent(
        snapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        active: ChatPageSnapshot
    ): Boolean {
        if (batch.status == MessageReadStatus.WINDOW_STALE) return false
        val expectedWindowSessionKey = windowSessionKey(snapshot)
        if (currentSessionBinding?.observedDetailTarget != null) {
            return observedPrivateDetailVerified(snapshot) && observedPrivateDetailVerified(active) &&
                expectedWindowSessionKey != null && batch.windowSessionKey == expectedWindowSessionKey &&
                hasSameWindowSession(snapshot, active)
        }
        if (receivedGreetingsTaskActive || greetingChatBinding != null) {
            val reason = greetingBindingRejectReason(active)
            if (reason != null) {
                onLog("GREETING_CHAT_READ_REJECTED reason=$reason")
                return false
            }
            return expectedWindowSessionKey != null &&
                batch.windowSessionKey == expectedWindowSessionKey &&
                hasSameWindowSession(snapshot, active)
        }
        return active.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            active.packageName == snapshot.packageName &&
            active.hasSameWindowIdentity(snapshot) &&
            hasSameWindowSession(snapshot, active) &&
            expectedWindowSessionKey != null &&
            (currentSessionWindowKey == null || currentSessionWindowKey == expectedWindowSessionKey) &&
            batch.windowSessionKey == expectedWindowSessionKey
    }

    /**
     * Diagnostics only: explain the existing read-only send-proof gates without changing
     * their truth value, ordering, retries, task ownership or recovery route.
     * Codes contain no raw contact, title, window key or message text.
     */
    private fun sendConfirmationReadDeferredReason(
        snapshot: ChatPageSnapshot,
        pageConfirmed: Boolean
    ): String {
        if (!pageConfirmed) return "PAGE_NOT_CONFIRMED"
        if (!sessionRunStarted) return "RUN_NOT_STARTED"
        if (receivedGreetingsTaskActive) {
            if (!ownsTask(RECEIVED_GREETINGS_TASK_ID)) return "TASK_LEASE_NOT_HELD"
            return greetingBindingRejectReason(snapshot)?.let { "GREETING_$it" }
                ?: "GREETING_DETAIL_NOT_VERIFIED"
        }
        if (sessionManagerState != SessionManagerState.READING_SESSION) return "SESSION_STATE_NOT_READING"
        if (recoveryContext.pending) return "RECOVERY_PENDING"
        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) return "TASK_LEASE_NOT_HELD"
        return privateSendConfirmationDetailReason(snapshot)
    }

    private fun privateSendConfirmationDetailReason(snapshot: ChatPageSnapshot): String {
        if (snapshot.packageName != targetPackage()) return "TARGET_PACKAGE_MISMATCH"
        if (snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE) return "CHAT_DETAIL_NOT_CONFIRMED"
        val windowKey = windowSessionKey(snapshot) ?: return "WINDOW_SESSION_UNAVAILABLE"
        if (currentSessionWindowKey != null && currentSessionWindowKey != windowKey) {
            return "CHAT_WINDOW_SESSION_MISMATCH"
        }
        if (!hasBoundPrivateDetailRecoverySession()) return "SOURCE_CHAT_BINDING_NOT_VERIFIED"
        if (!isExpectedPrivateSessionDetail(snapshot)) return "CHAT_SESSION_IDENTITY_MISMATCH"
        if (currentSessionHeaderMatchMode(snapshot) == null) return "CHAT_HEADER_MISMATCH"
        return "CHAT_DETAIL_NOT_VERIFIED"
    }

    private fun sendConfirmationOcrRejectionReason(
        source: ChatPageSnapshot,
        batch: ChatMessageBatch,
        fresh: ChatPageSnapshot?,
        expectedGreetingBinding: GreetingChatBinding?
    ): String {
        if (fresh == null) return "FRESH_PAGE_UNAVAILABLE"
        if (receivedGreetingsTaskActive) {
            if (expectedGreetingBinding == null) return "GREETING_BINDING_MISSING"
            if (!ownsTask(RECEIVED_GREETINGS_TASK_ID)) return "TASK_LEASE_NOT_HELD"
            val bindingReason = greetingBindingRejectReason(fresh, expectedGreetingBinding)
            if (bindingReason != null) return "GREETING_$bindingReason"
        } else {
            if (sessionManagerState != SessionManagerState.READING_SESSION) return "SESSION_STATE_NOT_READING"
            if (recoveryContext.pending) return "RECOVERY_PENDING"
            if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) return "TASK_LEASE_NOT_HELD"
            if (!isVerifiedRecoveredPrivateDetail(fresh)) return privateSendConfirmationDetailReason(fresh)
        }
        return sendConfirmationMessageBatchRejectionReason(source, batch, fresh)
    }

    private fun sendConfirmationMessageBatchRejectionReason(
        source: ChatPageSnapshot,
        batch: ChatMessageBatch,
        fresh: ChatPageSnapshot
    ): String {
        if (fresh.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE) return "FRESH_CHAT_DETAIL_NOT_CONFIRMED"
        if (fresh.packageName != source.packageName) return "FRESH_PACKAGE_MISMATCH"
        if (!fresh.hasSameWindowIdentity(source)) return "WINDOW_IDENTITY_CHANGED"
        if (!hasSameWindowSession(source, fresh)) return "WINDOW_GENERATION_OR_SESSION_CHANGED"
        val sourceWindowKey = windowSessionKey(source) ?: return "SOURCE_WINDOW_SESSION_UNAVAILABLE"
        if (!receivedGreetingsTaskActive && greetingChatBinding == null &&
            currentSessionWindowKey != null && currentSessionWindowKey != sourceWindowKey
        ) return "ACTIVE_WINDOW_BINDING_MISMATCH"
        if (batch.status == MessageReadStatus.WINDOW_STALE) return "OCR_READ_INVALIDATED"
        if (batch.windowSessionKey != sourceWindowKey) return "OCR_BATCH_WINDOW_MISMATCH"
        return "GATE_REJECTED_UNCLASSIFIED"
    }

    /**
     * A rejected page, lease or OCR proof is not a failed send. Keep observing at the
     * existing recovery cadence until the original send is confirmed or the user stops.
     * This callback only requests a fresh snapshot; it never replays a gesture or draft.
     */
    private fun scheduleDeferredSendProofRecheck(reason: String) {
        if (!isAiReplyActive() || !sessionRunStarted || !isSendConfirmationPending() ||
            recoveryContext.pending
        ) return
        // Preserve an already queued proof read; repeated passive events or failures must
        // not replace its timer or burn through the backoff without a fresh observation.
        if (hasProtectedSendInspection()) return
        val attempt = ++deferredSendProofRetryAttempt
        val delayMs = RecoveryScheduler.readOnlyRetryDelayMs(attempt)
        onLog("AI 发送确认[READ_ONLY_RETRY_SCHEDULED] reason=$reason；attempt=$attempt；delayMs=$delayMs；保留发送检查点，不重发")
        scheduleFullInspectionAfter(delayMs, AiReplyPageReadPurpose.MESSAGE_READ_RETRY)
    }

    /**
     * A structural batch cannot prove the text of a newly sent bubble. Read OCR under a fresh
     * same-conversation and same-task check before giving it to the unchanged send matcher.
     */
    private fun requestPendingSendConfirmationOcr(snapshot: ChatPageSnapshot) {
        val confirmedBaselineId = nextSegmentConfirmedObservationId()
        val visit = draftVisitToken
        val runGeneration = currentRunGeneration()
        val greetingBinding = greetingChatBinding.takeIf { receivedGreetingsTaskActive }
        val recoveredVisit = if (!receivedGreetingsTaskActive &&
            privateMessagesTraversalTaskExecutor.recoveringUnfinishedSession
        ) privateMessagesTraversalTaskExecutor.currentUnfinishedSession() else null
        messageReadCoordinator.requestOcr(
            snapshot = snapshot,
            limit = 10,
            forceScheduledDelivery = true,
            sendConfirmation = true,
            confirmedBaselineObservationId = confirmedBaselineId,
            onTimeout = { scheduleDeferredSendProofRecheck("OCR_CALLBACK_TIMEOUT") },
            onFailure = {
                onLog("AI 发送确认[OCR_REQUEST_FAILED]：只读采集无法启动；保留待核验发送")
                scheduleDeferredSendProofRecheck("OCR_REQUEST_FAILED")
            }
        ) sendOcr@{ ocrBatch, _ ->
            if (!isAiReplyActive() || !isSendConfirmationPending() || recoveryContext.pending ||
                visit != draftVisitToken || (sessionRunStarted && runGeneration != currentRunGeneration())
            ) {
                onLog("AI 发送确认[OCR_STALE_IGNORED]：原发送事务或运行代次已变化")
                return@sendOcr
            }
            // Baseline reuse may never become proof of a newly submitted message.
            if (confirmedBaselineId != null && nextSegmentConfirmedObservationId() != confirmedBaselineId) {
                onLog("AI 消息段基线[STALE_IGNORED]：原已确认段已变化；禁止将复用结果用于发送确认")
                scheduleDeferredSendProofRecheck("SEGMENT_BASELINE_CHANGED")
                return@sendOcr
            }
            val fresh = runCatching { readPageSnapshot(AiReplyPageReadPurpose.MESSAGE_READ_RETRY) }.getOrNull()
            val boundDetail = fresh != null && if (receivedGreetingsTaskActive) {
                greetingBinding != null && greetingBindingRejectReason(fresh, greetingBinding) == null &&
                    ownsTask(RECEIVED_GREETINGS_TASK_ID)
            } else {
                sessionManagerState == SessionManagerState.READING_SESSION &&
                    canPerformPrivateMessagesUiAction() && isVerifiedRecoveredPrivateDetail(fresh)
            }
            if (!boundDetail || fresh == null ||
                !isMessageReadResultCurrent(snapshot, ocrBatch, fresh)
            ) {
                val reason = sendConfirmationOcrRejectionReason(
                    snapshot, ocrBatch, fresh, greetingBinding
                )
                onLog("AI 发送确认[OCR_REJECTED] reason=$reason；新鲜页面、身份或消息批次不匹配；保留待核验发送")
                scheduleDeferredSendProofRecheck(reason)
                return@sendOcr
            }
            if (deferBusyOcrResult(ocrBatch)) return@sendOcr
            // Fresh page evidence alone is not progress in send proof. Keep the
            // read-only backoff until the matcher reports progress or confirmation.
            // The full validated OCR batch, not the preceding geometry-only read, is the
            // immediate diff baseline. Never publish a failed or empty OCR as a new baseline.
            if (ocrBatch.status == MessageReadStatus.SUCCESS &&
                ocrBatch.ocrFailedTargetCount == 0 && !ocrBatch.truncated
            ) {
                if (receivedGreetingsTaskActive) latestGreetingMessageBatch = ocrBatch
                else latestPrivateMessageBatch = ocrBatch
            }
            if (confirmedBaselineId == null) {
                onLog("AI 发送确认[OCR_REVALIDATED]：新鲜聊天页面与任务租约已验证；核对己方消息")
            } else {
                onLog("AI 消息段基线[REVALIDATED]：新鲜聊天与任务租约已验证；仅准备下一段，不确认新发送")
            }
            val observedBinding = currentSessionBinding?.takeIf { it.observedBusinessAdmitted }
            val transaction = if (observedBinding != null) {
                val entry = privateMessagesTraversalTaskExecutor.currentUnfinishedSession()
                val key = ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact(observedBinding.observedVerifiedIdentity?.contactId)
                if (entry == null || entry.replyRequestKey == null || entry.verifiedKey != key ||
                    entry.conversationKey != fresh.conversationIdentityKey() || !observedPrivateDetailVerified(fresh) ||
                    !runCatching { observedPrivateJournalAdmitted(ocrBatch, fresh, entry.openedAtMillis, entry.replyRequestKey) }.getOrDefault(false)
                ) {
                    scheduleDeferredSendProofRecheck("OBSERVED_PRIVATE_JOURNAL_WAIT")
                    return@sendOcr
                }
                onRecoveredPrivateSendTransaction(ocrBatch, snapshot, entry.openedAtMillis)
            } else if (recoveredVisit != null) {
                val traversal = privateMessagesTraversalTaskExecutor
                val candidate = currentSessionCandidate
                if (traversal.currentUnfinishedSession() != recoveredVisit || candidate == null ||
                    recoveredVisit.conversationKey != fresh.conversationIdentityKey() ||
                    currentSessionBinding?.matchesRestoredChatSurface(fresh) != true ||
                    !traversal.verifyUnfinishedIdentity(candidate)
                ) {
                    onLog("AI 私聊历史发送[STALE_REVISIT_IGNORED]：原访问或平台身份未重新确认；不补登记")
                    scheduleDeferredSendProofRecheck("UNFINISHED_SESSION_IDENTITY_WAIT")
                    return@sendOcr
                }
                onRecoveredPrivateSendTransaction(ocrBatch, snapshot, recoveredVisit.openedAtMillis)
            } else onSendTransaction(ocrBatch, snapshot)
            handleSendConfirmationResult(transaction, ocrBatch, snapshot)
        }
    }

    private fun followUpEnabled(action: ContactPendingRecoveryAction): Boolean = when (action) {
        ContactPendingRecoveryAction.SEND_IMAGE -> imageSendThreshold() > 0
        ContactPendingRecoveryAction.ENABLE_INTERCEPTION -> messageInterceptionThreshold() > 0
        ContactPendingRecoveryAction.NONE -> false
    }

    private fun pendingPrivateFollowUpEnabled(contactKey: String?): Boolean =
        followUpEnabled(contactMessageLedger.pendingRecoveryAction(contactKey))

    private fun ownsPrivateFollowUp(child: PrivateFollowUp): Boolean =
        activePrivateFollowUp === child && sessionRunStarted && isAiReplyActive() &&
            !receivedGreetingsTaskActive && ownsTask(PRIVATE_MESSAGES_TASK_ID) &&
            verifiedPrivateLedgerContactKey() == child.contactKey &&
            child.target.taskLease?.let { taskCoordinator?.isCurrent(it) } == true &&
            followUpEnabled(child.checkpoint.step.action)

    /** A temporary recovery veto is not a lost transaction. The composition root also
     * applies this veto to the final platform write authority, including delayed commands. */
    fun isPrivateFollowUpWriteBlocked(): Boolean =
        activePrivateFollowUp != null && (recoveryContext.pending || receivedGreetingsOwnershipRecheckPending ||
            userInteractionActive || isPrivateFollowUpTouchActive())

    private fun privateFollowUpTarget(snapshot: ChatPageSnapshot): ChatDraftInputTarget? {
        val fresh = inspectFreshPageSnapshot() ?: return null
        val lease = taskCoordinator?.currentLease()?.takeIf {
            it.taskId == PRIVATE_MESSAGES_TASK_ID && it.runGeneration == currentRunGeneration()
        } ?: return null
        val input = fresh.inputFieldTarget ?: return null
        val title = fresh.chatTitleText?.takeIf(String::isNotBlank) ?: return null
        val conversation = fresh.conversationIdentityKey() ?: return null
        val windowKey = windowSessionKey(fresh) ?: return null
        if (!canPerformPrivateMessagesUiAction() || userInteractionActive || isPrivateFollowUpTouchActive() ||
            fresh.packageName != targetPackage() || fresh.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
            isSemanticallyUnconfirmed(fresh) || !hasSameWindowSession(snapshot, fresh) ||
            title != snapshot.chatTitleText || currentSessionWindowKey != windowKey ||
            currentSessionHeaderMatchMode(fresh) == null || !isExpectedPrivateSessionDetail(fresh)) return null
        return ChatDraftInputTarget(fresh.packageName, fresh.activityClass, windowKey, conversation,
            windowKey, input, title, lease)
    }

    /** Only interception can reconcile a persisted prior action from a freshly verified chat.
     * Image admission retains the M04-D no-replay contract. */
    private fun startPrivateFollowUp(
        contactKey: String, sendId: String?, snapshot: ChatPageSnapshot, deferFirstObservation: Boolean = false
    ) {
        val checkpoint = contactMessageLedger.followUpCheckpoint(contactKey) ?: return
        val interception = checkpoint.step.action == ContactPendingRecoveryAction.ENABLE_INTERCEPTION
        val port = privateChatFollowUpPort
        val target = privateFollowUpTarget(snapshot)
        if ((!interception && (checkpoint.reconciliationRequired || checkpoint.revision != 0L || sendId == null)) ||
            port == null || target == null || !followUpEnabled(checkpoint.step.action) ||
            verifiedPrivateLedgerContactKey() != contactKey ||
            (checkpoint.step.action == ContactPendingRecoveryAction.SEND_IMAGE && privateImageResourceCount() != 1)
        ) {
            onLog("PRIVATE_FOLLOW_UP ADMISSION_HELD;checkpointRetained=true;noReplay=true")
            if (interception || currentSessionSendConfirmed) {
                scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            }
            return
        }
        val child = PrivateFollowUp(contactKey, sendId, target, checkpoint)
        activePrivateFollowUp = child
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        regionRefreshTask?.cancel()
        regionRefreshTask = null
        invalidatePendingMessageRead()
        port.reset("PRIVATE_FOLLOW_UP_BEGIN")
        val started = port.beginPrivateFollowUp(
            target, checkpoint.transactionId, checkpoint.step.action == ContactPendingRecoveryAction.ENABLE_INTERCEPTION,
            beforeStep = { step ->
                val expected = child.checkpoint
                if (!ownsPrivateFollowUp(child) || !canPerformPrivateMessagesUiAction() ||
                    userInteractionActive || isPrivateFollowUpTouchActive() ||
                    !contactMessageLedger.updateFollowUpCheckpoint(contactKey, expected, step, true)) false
                else {
                    child.checkpoint = requireNotNull(contactMessageLedger.followUpCheckpoint(contactKey))
                    true
                }
            },
            ownsTransaction = { ownsPrivateFollowUp(child) }
        )
        if (!started) {
            activePrivateFollowUp = null
            onLog("PRIVATE_FOLLOW_UP BEGIN_NOT_ADMITTED;checkpointRetained=true")
            if (interception || currentSessionSendConfirmed) {
                scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            }
            return
        }
        onLog("PRIVATE_FOLLOW_UP BEGIN;action=${checkpoint.step.action};parentLeaseInherited=true")
        // The same shared observer advances the normal child. A recovered terminal must
        // yield to the scheduler, not recursively restart on the current call stack.
        if (deferFirstObservation) scheduleFullInspectionAfter(500L)
        else runPrivateFollowUpObservation()
    }

    /** Rebind only the exact original chat after adapter result evidence; never restore old nodes. */
    private fun privateFollowUpReturnedSnapshot(child: PrivateFollowUp): ChatPageSnapshot? {
        val fresh = inspectFreshPageSnapshot() ?: return null
        val live = fresh.inputFieldTarget?.window ?: return null
        val original = child.target.nodeTarget.window
        val windowKey = windowSessionKey(fresh) ?: return null
        val surfaceKey = chatSurfaceKey(fresh) ?: return null
        val binding = currentSessionBinding ?: return null
        if (!ownsPrivateFollowUp(child) || !canPerformPrivateMessagesUiAction() ||
            userInteractionActive || isPrivateFollowUpTouchActive() ||
            fresh.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE || isSemanticallyUnconfirmed(fresh) ||
            live.packageName != original.packageName || live.activityClass != original.activityClass ||
            live.windowId != original.windowId || live.generation < original.generation ||
            fresh.chatTitleText != child.target.expectedChatTitle ||
            fresh.conversationIdentityKey() != child.target.conversationIdentityKey ||
            currentSessionHeaderMatchMode(fresh) == null) return null
        currentSessionWindowKey = windowKey
        currentSessionBinding = binding.copy(chatWindowKey = windowKey, chatSurfaceKey = surfaceKey)
        if (!isExpectedPrivateSessionDetail(fresh)) {
            currentSessionWindowKey = binding.chatWindowKey
            currentSessionBinding = binding
            return null
        }
        return fresh
    }

    private fun runPrivateFollowUpObservation() {
        val child = activePrivateFollowUp ?: return
        val port = privateChatFollowUpPort ?: return
        if (!ownsPrivateFollowUp(child)) {
            onPrivateFollowUpTopLevelObserved()
            return
        }
        if (userInteractionActive || isPrivateFollowUpTouchActive()) {
            // Only the physical-user/route-exit path retires this transaction. A transient
            // touch gate cannot strand it in a terminal state or grant any write.
            scheduleFullInspectionAfter(500L)
            return
        }
        val count = ++child.observationCount
        if (count == 1L || count % 10L == 0L) {
            onLog("PRIVATE_FOLLOW_UP OBSERVATION;action=${child.checkpoint.step.action};" +
                "count=$count;progress=${port.progress()}")
        }
        if (port.progress() == PrivateChatFollowUpProgress.ACTIVE) {
            val observed = runCatching { port.observe() }
            if (observed.isFailure) {
                onLog("PRIVATE_FOLLOW_UP OBSERVATION_RETRY;type=${observed.exceptionOrNull()?.javaClass?.simpleName};checkpointRetained=true")
                if (activePrivateFollowUp === child) scheduleFullInspectionAfter(500L)
                return
            }
        }
        if (activePrivateFollowUp !== child) return
        if (port.progress() == PrivateChatFollowUpProgress.ACTIVE) {
            scheduleFullInspectionAfter(500L)
            return
        }
        val interception = child.checkpoint.step.action == ContactPendingRecoveryAction.ENABLE_INTERCEPTION
        if (!port.privateFollowUpSucceeded()) {
            if (!child.unconfirmedReported) {
                child.unconfirmedReported = true
                onLog("PRIVATE_FOLLOW_UP RESULT_UNCONFIRMED;checkpointRetained=true;noReturn=true")
            }
            // A terminal image is never replayed. Interception alone is idempotently
            // reconciled from a newly verified original chat and the unchanged ledger.
            val fresh = if (interception) privateFollowUpReturnedSnapshot(child) else null
            if (fresh != null && contactMessageLedger.followUpCheckpoint(child.contactKey) == child.checkpoint) {
                onLog("PRIVATE_FOLLOW_UP INTERCEPTION_RECONCILE_RESTART;noNewDraft=true")
                port.reset("PRIVATE_FOLLOW_UP_INTERCEPTION_RECONCILE")
                activePrivateFollowUp = null
                startPrivateFollowUp(child.contactKey, child.sendConfirmationId, fresh, deferFirstObservation = true)
            } else {
                scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(4))
            }
            return
        }
        val fresh = privateFollowUpReturnedSnapshot(child) ?: run {
            onLog("PRIVATE_FOLLOW_UP RETURN_NOT_CONFIRMED;checkpointRetained=true")
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return
        }
        val checkpoint = child.checkpoint
        val confirmed = when (checkpoint.step.action) {
            ContactPendingRecoveryAction.SEND_IMAGE -> contactMessageLedger.confirmImageSent(child.contactKey, checkpoint)
            ContactPendingRecoveryAction.ENABLE_INTERCEPTION -> confirmMessageInterceptionEnabled(child.contactKey, checkpoint)
            ContactPendingRecoveryAction.NONE -> false
        }
        if (!confirmed) {
            onLog("PRIVATE_FOLLOW_UP RESULT_NOT_PERSISTED;noReturn=true")
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return
        }
        onLog("PRIVATE_FOLLOW_UP CONFIRMED;action=${checkpoint.step.action}")
        port.reset("PRIVATE_FOLLOW_UP_CONFIRMED")
        activePrivateFollowUp = null
        if (pendingPrivateFollowUpEnabled(child.contactKey)) {
            startPrivateFollowUp(child.contactKey, child.sendConfirmationId, fresh)
        } else {
            // A resumed interception is a durable follow-up result, not a new text send.
            // Keep the old send receipt instead of inventing one when re-entered later.
            if (interception) currentSessionSendConfirmed = true
            finishConfirmedPrivateSend(child.sendConfirmationId, fresh)
        }
    }

    /** Re-enter completion only from the original, current task-owned chat. This is
     * neither another draft nor another send. A started/uncertain image is still
     * protected by the existing ledger revision and no-replay admission check. */
    private fun continueConfirmedPrivateCompletion(snapshot: ChatPageSnapshot): Boolean {
        if (!currentSessionSendConfirmed || receivedGreetingsTaskActive ||
            sessionManagerState != SessionManagerState.READING_SESSION
        ) return true
        if (isPrivateFollowUpActive()) return true
        if (!canPerformPrivateMessagesUiAction() || userInteractionActive || isPrivateFollowUpTouchActive() ||
            snapshot.packageName != targetPackage() || snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
            isSemanticallyUnconfirmed(snapshot) || currentSessionWindowKey == null ||
            currentSessionWindowKey != windowSessionKey(snapshot) ||
            !isExpectedPrivateSessionDetail(snapshot) || currentSessionHeaderMatchMode(snapshot) == null
        ) {
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return true
        }
        val contactKey = verifiedPrivateLedgerContactKey()
        if (pendingPrivateFollowUpEnabled(contactKey)) {
            startPrivateFollowUp(requireNotNull(contactKey), confirmedPrivateCompletionReceipt, snapshot)
            return true
        }
        // A scheduled BACK/draft wait already owns the completion continuation.
        if (sessionTransitionTask != null) return true
        return finishConfirmedPrivateSend(confirmedPrivateCompletionReceipt, snapshot)
    }

    /** Existing traversal completion, called after text-only or fully confirmed child actions. */
    private fun finishConfirmedPrivateSend(sendConfirmationId: String?, snapshot: ChatPageSnapshot): Boolean {
        if (isPrivateFollowUpActive() || pendingPrivateFollowUpEnabled(verifiedPrivateLedgerContactKey())) {
            if (!isPrivateFollowUpActive()) scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return true
        }
        privateMessagesTraversalTaskExecutor.checkpointRunGeneration = currentRunGeneration()
        if (sendConfirmationId != null) privateMessagesTraversalTaskExecutor.checkpointLastSuccessfulTaskId = sendConfirmationId
        val observedVisit = currentSessionBinding?.observedBusinessAdmitted == true
        if (!observedVisit) {
            privateMessagesTraversalTaskExecutor.checkpointNextCursorKey = currentSessionKey
            privateMessagesTraversalTaskExecutor.checkpointTraversalDirection = "BOTTOM_TO_TOP"
        }
        privateMessagesTraversalTaskExecutor.completeCurrentUnfinishedSession()
        if (!observedVisit) {
            val candidate = currentSessionCandidate ?: run {
                sessionManagerState = SessionManagerState.PAUSED
                onLog("AI 会话返回被阻止：缺少当前会话稳定键，未写入返回检查点")
                return true
            }
            privateMessagesTraversalTaskExecutor.rememberRecoveryAnchor(candidate)
        }
        val checkpointWritten = runCatching {
            privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
        }.isSuccess
        if (!checkpointWritten) {
            onLog("PRIVATE_COMPLETION CHECKPOINT_RETRY;confirmedSendRetained=true;noReturn=true")
            scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            return true
        }
        if (sendConfirmationId != null && pendingPrivateQuotaReceipt == sendConfirmationId) {
            privateQuotaReturnReady = true
        }
        onLog("AI 发送成功记录：已确认发送并更新实际发送账本")
        if (!currentSessionReadCaptured) {
            onLog("AI 会话返回门禁[CHAT_SAMPLE_NOT_PERSISTED]：发送已确认；样本归档失败不阻止安全返回")
        }
        scheduleReturnToSessionList(snapshot)
        return true
    }

    /** Only a fully verified outgoing message may release the send hold or advance its queue. */
    private fun handleSendConfirmationResult(
        transaction: com.freedom.features.aireply.ai.ReplySendTransactionOutcome,
        batch: ChatMessageBatch,
        snapshot: ChatPageSnapshot
    ): Boolean {
        when (transaction.status) {
            com.freedom.features.aireply.ai.SendConfirmationResult.CONFIRMED -> {
                deferredSendProofRetryAttempt = 0
                if (receivedGreetingsTaskActive) {
                    val bound = greetingChatBinding ?: run {
                        onLog("GREETING_SEND_RETURN_BLOCKED reason=BINDING_MISSING")
                        return true
                    }
                    val sendId = transaction.finalSendConfirmationId
                    if (sendId.isNullOrBlank()) {
                        onLog("GREETING_SEND_RETURN_BLOCKED reason=CONFIRMATION_ID_MISSING")
                        return true
                    }
                    confirmedGreetingSend = ConfirmedGreetingSend(bound, sendId)
                    onLog("GREETING_SEND_CONFIRMED：最终消息段已核验；先持久化发送事实再申请安全 BACK")
                    completeConfirmedGreetingSend(batch, snapshot)
                    return true
                }
                if (!currentSessionSendConfirmed) {
                    currentSessionSendConfirmed = true
                    val sendConfirmationId = transaction.finalSendConfirmationId
                    if (sendConfirmationId.isNullOrBlank()) {
                        currentSessionSendConfirmed = false
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("AI 私聊暂停分类[SEND_PROOF_UNAVAILABLE]：发送收据不稳定，禁止返回列表或重发")
                        onLog("AI 发送成功记录被阻止：缺少稳定 sendConfirmationId；不返回列表")
                        return true
                    }
                    val countUpdate = recordSuccessfulSend(sendConfirmationId)
                    if (countUpdate.status == ContactSendRecordStatus.PERSISTENCE_FAILED) {
                        currentSessionSendConfirmed = false
                        onLog("PRIVATE_CONTACT_LEDGER WRITE_FAILED;retainConfirmedSendJournal=true;noReturn=true")
                        requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, "PRIVATE_DETAIL_CONTACT_LEDGER_WRITE_FAILED")
                        scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
                        return true
                    }
                    confirmedPrivateCompletionReceipt = sendConfirmationId
                    // Only an actual verified send from an admitted unread list row earns
                    // credit. A restored follow-up alone, manual visit or OCR does not.
                    if (currentSessionCandidate?.unreadStatus == ChatUnreadStatus.UNREAD &&
                        currentSessionBinding?.observedBusinessAdmitted != true
                    ) {
                        pendingPrivateQuotaReceipt = sendConfirmationId
                        privateQuotaReturnReady = false
                    }
                    val contactKey = verifiedPrivateLedgerContactKey()
                    if (pendingPrivateFollowUpEnabled(contactKey)) {
                        startPrivateFollowUp(requireNotNull(contactKey), sendConfirmationId, snapshot)
                        return true
                    }
                    return finishConfirmedPrivateSend(sendConfirmationId, snapshot)
                }
                return continueConfirmedPrivateCompletion(snapshot)
            }
            com.freedom.features.aireply.ai.SendConfirmationResult.UNKNOWN -> {
                if (receivedGreetingsTaskActive) {
                    // Unknown send outcome cannot complete a greeting or authorize replay.
                    onLog("GREETING_SEND_UNKNOWN：发送结果未明确确认；保持当前聊天，不标记处理、不重发")
                    // An UNKNOWN greeting must keep observing without re-entering the draft
                    // or gesture path. The current lease and page are rechecked on each pass.
                    if (isAiReplyActive() && sessionRunStarted && !recoveryContext.pending &&
                        ownsTask(RECEIVED_GREETINGS_TASK_ID)
                    ) {
                        if (isSendConfirmationPending()) {
                            scheduleDeferredSendProofRecheck("GREETING_SEND_CONFIRMATION_EVIDENCE_UNAVAILABLE")
                        } else {
                            scheduleFullInspectionAfter(GREETING_UNKNOWN_SEND_READ_ONLY_RECHECK_DELAY_MS)
                        }
                    }
                    return true
                }
                // A send UNKNOWN is not a page UNKNOWN. Recheck the live surface
                // after the matcher callback; only retain the read-only route if the
                // original conversation, window and lease still hold.
                if (!isAiReplyActive() || !sessionRunStarted) return true
                if (recoveryContext.pending) return true
                if (isSendConfirmationPending()) {
                    val visit = draftVisitToken
                    val runGeneration = currentRunGeneration()
                    val fresh = runCatching {
                        readPageSnapshot(AiReplyPageReadPurpose.MESSAGE_READ_RETRY)
                    }.getOrNull()
                    if (!isAiReplyActive() || !sessionRunStarted || recoveryContext.pending ||
                        !isSendConfirmationPending() || receivedGreetingsTaskActive ||
                        visit != draftVisitToken || runGeneration != currentRunGeneration()
                    ) return true
                    if (fresh != null && !userInteractionActive &&
                        sessionManagerState == SessionManagerState.READING_SESSION &&
                        canPerformPrivateMessagesUiAction() &&
                        isVerifiedRecoveredPrivateDetail(fresh) &&
                        currentSessionWindowKey != null &&
                        currentSessionWindowKey == windowSessionKey(fresh) &&
                        hasSameWindowSession(snapshot, fresh)
                    ) {
                        onLog("AI 发送确认[UNKNOWN_PROOF_ONLY]：原聊天、窗口和任务租约仍有效；仅等待新鲜发送证据，不创建页面恢复、不重发")
                        scheduleDeferredSendProofRecheck("SEND_CONFIRMATION_EVIDENCE_UNAVAILABLE")
                        return true
                    }
                }
                // Missing binding/page evidence or a historical obligation that has not
                // yet been rehydrated still follows the existing checkpoint route.
                if (!requestSendConfirmationRecovery("SEND_CONFIRMATION_UNKNOWN")) {
                    onLog("AI 发送结果核验：UNKNOWN；恢复事务已在等待新鲜页面证据；不返回列表、不重发")
                }
                return true
            }
            com.freedom.features.aireply.ai.SendConfirmationResult.WAITING -> {
                deferredSendProofRetryAttempt = 0
                if (isSendConfirmationPending() && isAiReplyActive() && sessionRunStarted && !recoveryContext.pending) {
                    onLog("AI 发送确认[RECHECK_SCHEDULED]：等待下一次新鲜聊天快照和只读消息核验；不重发")
                    scheduleFullInspectionAfter(SEND_CONFIRMATION_RECHECK_DELAY_MS, AiReplyPageReadPurpose.MESSAGE_READ_RETRY)
                }
                return true
            }
            com.freedom.features.aireply.ai.SendConfirmationResult.NOT_PENDING -> return false
        }
    }

    private fun handleMessageReadBatch(batch: ChatMessageBatch, snapshot: ChatPageSnapshot) {
        if (isPrivateFollowUpActive()) return
        if (batch.status == MessageReadStatus.WINDOW_STALE) {
            if (isSendConfirmationPending()) scheduleDeferredSendProofRecheck("OCR_READ_INVALIDATED")
            return
        }
        // A busy adapter did not perform recognition. It is never a draft/send/sample batch.
        if (batch.ocrAdmissionDeferred) {
            deferBusyOcrResult(batch)
            return
        }
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
        if (sessionRunStarted && receivedGreetingsTaskActive) {
            val reason = greetingBindingRejectReason(snapshot)
            if (reason != null || batch.windowSessionKey != greetingChatBinding?.windowSessionKey) {
                onLog("GREETING_CHAT_BATCH_REJECTED reason=${reason ?: "MESSAGE_WINDOW_MISMATCH"}")
                return
            }
            if (batch.ocrAttempted || batch.ocrTargets.isEmpty()) latestGreetingMessageBatch = batch
        } else if (sessionRunStarted && !receivedGreetingsTaskActive &&
            snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            currentSessionWindowKey == windowSessionKey(snapshot) &&
            (currentSessionCandidate != null || currentSessionBinding?.observedBusinessAdmitted == true)
        ) {
            if (batch.ocrAttempted || batch.ocrTargets.isEmpty()) latestPrivateMessageBatch = batch
        }
        val observedDetail = currentSessionBinding?.observedDetailTarget != null
        if (observedDetail && (currentSessionBinding?.observedBusinessAdmitted != true ||
                !observedPrivateDetailVerified(snapshot) || batch.windowSessionKey != windowSessionKey(snapshot))) return
        if (!receivedGreetingsTaskActive && currentSessionSendConfirmed) {
            continueConfirmedPrivateCompletion(snapshot)
            return
        }
        if (observedDetail && isConversationDraftPending() && !isSendConfirmationPending() &&
            !isSendControlRecoveryPending()) return
        if (receivedGreetingsTaskActive && confirmedGreetingSend != null) {
            completeConfirmedGreetingSend(batch, snapshot)
            return
        }
        val resumedPrivateVisit = !receivedGreetingsTaskActive &&
            privateMessagesTraversalTaskExecutor.recoveringUnfinishedSession
        if (!resumedPrivateVisit && !observedDetail) {
            if (isSendConfirmationPending() && !batch.ocrAttempted && batch.ocrTargets.isNotEmpty()) {
                requestPendingSendConfirmationOcr(snapshot)
                return
            }
            if (handleSendConfirmationResult(onSendTransaction(batch, snapshot), batch, snapshot)) return
        }
        // Recheck after the send-confirmation callback: it is a separate external
        // boundary and may have changed the current task or binding. OCR callbacks also
        // revalidate against an independently acquired live chat snapshot.
        val greetingDetail = sessionRunStarted && receivedGreetingsTaskActive &&
            greetingBindingRejectReason(snapshot) == null
        val privateDetail = observedDetail || (sessionRunStarted && !receivedGreetingsTaskActive && currentSessionCandidate != null &&
            currentSessionWindowKey == windowSessionKey(snapshot) &&
            ChatSessionManagementPolicy.matchChatHeader(currentSessionName, snapshot.chatTitleText) != null)
        val draftTargetConfirmed = greetingDetail || privateDetail
        if (privateDetail && privateModelFailureRecovery != null) {
            // Page events and identity-timeout reads can arrive during model cooldown.
            // Only the verified private recovery transaction may rearm a failed request.
            // An ordinary OCR observation cannot bypass the durable no-send/composer gate.
            onLog("AI 私聊模型恢复[MODEL_FAILURE_READ_HELD]：技术失败等待原会话与空输入框复核；不启动新草稿")
            return
        }
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
        if (privateDetail && !observedDetail) {
            val identitySessionKey = currentSessionWindowKey
            if (identityCaptureSessionKey != identitySessionKey) {
                identityCaptureSessionKey = identitySessionKey
                identityCaptureStartedAtMs = System.currentTimeMillis()
                identityCaptureRetryCount = 0
                identityCaptureTerminalSessionKey = null
                identityCaptureTimeoutTask?.cancel()
                // The existing 15-second identity timeout must be observable even when the
                // accessibility service receives no more events while capture is pending.
                val timeoutSessionKey = identitySessionKey
                val timeoutGeneration = sessionActionGeneration
                identityCaptureTimeoutTask = scheduler.schedule(IDENTITY_CAPTURE_TIMEOUT_MS) {
                    identityCaptureTimeoutTask = null
                    if (sessionActionGeneration != timeoutGeneration || !isAiReplyActive() ||
                        !sessionRunStarted || sessionManagerState != SessionManagerState.READING_SESSION ||
                        currentSessionWindowKey != timeoutSessionKey ||
                        identityCaptureTerminalSessionKey == timeoutSessionKey ||
                        currentSessionCandidate?.contactIdentity?.let {
                            it.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                                !it.contactId.isNullOrBlank()
                        } == true
                    ) return@schedule
                    val fresh = readPageSnapshot(AiReplyPageReadPurpose.MESSAGE_READ_RETRY)
                    if (fresh == null || !isFreshCurrentTargetForIdentityFallback(fresh)) {
                        onLog("AI 身份降级计时：当前页面不属于已复核目标聊天；不处理历史或草稿，等待安全恢复")
                        return@schedule
                    }
                    identityCaptureTerminalSessionKey = timeoutSessionKey
                    onLog("AI 联系人身份采集终态：IDENTITY_CAPTURE_TIMEOUT；定时检查已独立复核目标会话；仅当前窗口消息")
                    readVisibleMessages(fresh)
                }
                onLog("AI 联系人身份采集事务：已为当前聊天窗口建立新事务；等待身份终态")
            }
            val currentIdentity = currentSessionCandidate?.contactIdentity
            val identityVerified = currentIdentity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                !currentIdentity.contactId.isNullOrBlank()
            if (identityVerified && currentSessionBinding?.matchesRestoredChatSurface(snapshot) != true &&
                identityCaptureTerminalSessionKey != identitySessionKey
            ) {
                identityCaptureTerminalSessionKey = identitySessionKey
                onLog("AI 联系人身份降级[CURRENT_SESSION_BINDING_NOT_READY]：已有平台ID但聊天表面绑定未就绪；" +
                    "不使用该ID关联历史，仅在当前目标会话独立复核通过后处理当前窗口消息")
            }
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
                            val verifiedPlatformId = verifiedIdentity.confidence ==
                                ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                                !verifiedIdentity.contactId.isNullOrBlank()
                            if (candidate != null && !restoredSurfaceMatches &&
                                auxiliaryHeaderMatches && verifiedPlatformId
                            ) {
                                identityCaptureTerminalSessionKey = identitySessionKey
                                onLog(
                                    "AI 联系人身份降级[CURRENT_SESSION_BINDING_NOT_READY]：" +
                                        "已采集平台ID但当前会话绑定未就绪；不写回联系人、不加载历史；" +
                                        "等待当前目标会话独立复核后仅使用当前窗口消息"
                                )
                            } else {
                                onLog(
                                    "AI 联系人身份绑定暂缓：CURRENT_SESSION_BINDING_NOT_READY；" +
                                        "restoredChatSurface=$restoredSurfaceMatches；" +
                                        "辅助标题匹配=$auxiliaryHeaderMatches；身份或目标证据不足，暂不生成草稿"
                                )
                                return
                            }
                        } else {
                            val before = ChatSessionManagementPolicy.stableSessionKey(candidate!!)
                            val expected = expectedPrivateSessionDetailNavigation
                            val boundBefore = hasBoundPrivateDetailRecoverySession()
                            val previousIdentity = candidate.contactIdentity
                            candidate.contactIdentity = verifiedIdentity
                            if (!privateMessagesTraversalTaskExecutor.verifyUnfinishedIdentity(candidate)) {
                                candidate.contactIdentity = previousIdentity
                                onLog("AI 私聊业务[RECOVERY_IDENTITY_MISMATCH]：原平台身份未匹配；保留待恢复记录，不启动草稿")
                                schedulePrivatePreSendReadRetry("UNFINISHED_SESSION_IDENTITY_MISMATCH")
                                return
                            }
                            val after = ChatSessionManagementPolicy.stableSessionKey(candidate)
                            if (before != after && boundBefore && expected != null) {
                                privateMessagesTraversalTaskExecutor.preserveAttemptAfterVerifiedIdentityUpgrade(before, after)
                                currentSessionKey = after
                                expectedPrivateSessionDetailNavigation = expected.copy(sessionKey = after)
                            }
                            privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
                            onLog("AI 身份升级：keyChanged=${before != after}；boundBefore=$boundBefore；" +
                                "currentKeyMatches=${currentSessionKey == after}；" +
                                "expectedKeyMatches=${expectedPrivateSessionDetailNavigation?.sessionKey == after}")
                            identityJustVerified = true
                            onLog(
                                "AI 联系人身份绑定完成：已写回当前私人会话候选；" +
                                    "confidence=${verifiedIdentity.confidence.name}；" +
                                    "身份已确认；继续加载对应联系人历史并提交模型"
                            )
                        }
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
                        if (identityOutcome.request == ContactIdentityCaptureRequestResult.REJECTED ||
                            identityOutcome.failureReason == "IDENTITY_CONFLICT" ||
                            identityOutcome.failureReason == "EXPLICIT_IDENTITY_CONFLICT"
                        ) {
                            onLog("AI 联系人身份采集拒绝[IDENTITY_CAPTURE_REJECTED]：可能存在身份冲突；" +
                                "保留原安全门禁，不允许仅凭昵称生成草稿")
                            return
                        }
                        identityCaptureTerminalSessionKey = identitySessionKey
                        onLog("AI 联系人身份采集终态：${identityOutcome.failureReason}；" +
                            "待独立复核当前目标会话后降级当前窗口消息，不关联历史联系人")
                    }
                }
                }
            }
        }
        if (privateDetail && identityJustVerified) {
            identityCaptureTimeoutTask?.cancel()
            identityCaptureTimeoutTask = null
            onLog("AI OCR 消息读取：身份已确认并返回原聊天页；丢弃旧批次，重新触发当前会话 OCR")
            readVisibleMessages(snapshot)
            return
        }
        val currentWindowOnlyFallback = privateDetail && !observedDetail &&
            currentSessionWindowKey != null &&
            identityCaptureTerminalSessionKey == currentSessionWindowKey
        if (currentWindowOnlyFallback && !isFreshCurrentTargetForIdentityFallback(snapshot)) {
            onLog("AI 身份降级门禁[CURRENT_TARGET_NOT_CONFIRMED]：当前目标会话或新鲜窗口校验不足；" +
                "不加载历史、不启动草稿；等待重新观察")
            scheduleFullInspection()
            return
        }
        if (resumedPrivateVisit && !observedDetail) {
            val entry = privateMessagesTraversalTaskExecutor.currentUnfinishedSession()
            val candidate = currentSessionCandidate
            if (!privateDetail || entry == null || candidate == null ||
                entry.conversationKey != snapshot.conversationIdentityKey() ||
                currentWindowOnlyFallback || currentSessionBinding?.matchesRestoredChatSurface(snapshot) != true ||
                !privateMessagesTraversalTaskExecutor.verifyUnfinishedIdentity(candidate)
            ) {
                onLog("AI 私聊业务[RECOVERY_IDENTITY_WAIT]：尚未重新确认原会话及平台身份；禁止草稿和发送核验")
                schedulePrivatePreSendReadRetry("UNFINISHED_SESSION_IDENTITY_WAIT")
                return
            }
            if (entry != privateMessagesTraversalTaskExecutor.currentUnfinishedSession()) {
                privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
            }
            if (isSendConfirmationPending() && !batch.ocrAttempted && batch.ocrTargets.isNotEmpty()) {
                requestPendingSendConfirmationOcr(snapshot)
                return
            }
            if (handleSendConfirmationResult(
                    onRecoveredPrivateSendTransaction(batch, snapshot, entry.openedAtMillis), batch, snapshot
                )) return
        }
        // The coordinator now delivers a structural batch first. OCR is deliberately deferred
        // until identity is terminal and the adapter has confirmed the chat detail page is
        // restored. The OCR callback re-enters this method with an OCR-complete batch.
        if (observedDetail && isSendConfirmationPending() && !batch.ocrAttempted && batch.ocrTargets.isNotEmpty()) {
            requestPendingSendConfirmationOcr(snapshot)
            return
        }
        if ((privateDetail || greetingDetail) && !batch.ocrAttempted && batch.ocrTargets.isNotEmpty()) {
            if (privateDetail && !observedDetail) {
                val preOcrIdentity = if (currentWindowOnlyFallback) null else runCatching {
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
            val ocrGreetingBinding = greetingChatBinding.takeIf { greetingDetail }
            val ocrRun = currentRunGeneration()
            val started = messageReadCoordinator.requestOcr(
                snapshot = ocrSnapshot,
                limit = 10,
                forceScheduledDelivery = ocrGreetingBinding != null || observedDetail,
                onTimeout = { scheduleOcrReadRecheck("OCR_CALLBACK_TIMEOUT") },
                onFailure = { scheduleOcrReadRecheck("OCR_REQUEST_FAILED") }
            ) ocrDelivery@{ ocrBatch, revalidate ->
                if (ocrGreetingBinding == null) {
                    if (ocrVisit != draftVisitToken || !isAiReplyActive() ||
                        recoveryContext.pending || ocrRun != currentRunGeneration()
                    ) {
                        onLog("AI OCR 结果已丢弃：窗口任务已变化")
                        return@ocrDelivery
                    }
                    if (revalidate || observedDetail) {
                        val fresh = runCatching { readPageSnapshot(AiReplyPageReadPurpose.MESSAGE_READ_RETRY) }.getOrNull()
                        if (fresh == null || !isMessageReadResultCurrent(ocrSnapshot, ocrBatch, fresh) ||
                            !ownsTask(PRIVATE_MESSAGES_TASK_ID)
                        ) {
                            onLog("AI OCR 结果已丢弃：新鲜聊天窗口或任务绑定不匹配")
                            scheduleOcrReadRecheck("OCR_PAGE_REVALIDATION_PENDING")
                            return@ocrDelivery
                        }
                    }
                    if (observedDetail && !observedPrivateDetailVerified(ocrSnapshot)) return@ocrDelivery
                    if (deferBusyOcrResult(ocrBatch)) return@ocrDelivery
                    handleMessageReadBatch(ocrBatch, ocrSnapshot)
                } else {
                    // The coordinator keeps the gate until this scheduler delivery.
                    // Retain the original independent greeting identity/window/lease proof.
                    if (ocrVisit != draftVisitToken || !isAiReplyActive() || ocrRun != currentRunGeneration()) {
                        onLog("GREETING_CHAT_OCR_REJECTED reason=READ_GENERATION_CHANGED")
                        return@ocrDelivery
                    }
                    val fresh = runCatching { readPageSnapshot(AiReplyPageReadPurpose.MESSAGE_READ_RETRY) }.getOrNull()
                    val reason = if (fresh == null) "FRESH_CHAT_UNAVAILABLE" else
                        greetingBindingRejectReason(fresh, ocrGreetingBinding)
                    if (reason != null || fresh == null ||
                        !hasSameWindowSession(ocrSnapshot, fresh) ||
                        ocrBatch.windowSessionKey != ocrGreetingBinding.windowSessionKey
                    ) {
                        onLog("GREETING_CHAT_OCR_REJECTED reason=${reason ?: "MESSAGE_WINDOW_MISMATCH"}")
                        scheduleOcrReadRecheck("GREETING_OCR_REVALIDATION_PENDING")
                        return@ocrDelivery
                    }
                    if (deferBusyOcrResult(ocrBatch)) return@ocrDelivery
                    onLog("GREETING_CHAT_OCR_REVALIDATED sameContact=true; sameWindow=true; leaseCurrent=true")
                    handleMessageReadBatch(ocrBatch, ocrSnapshot)
                }
            }
            if (started) onLog("AI OCR 启动：身份采集已终态且聊天页已恢复；开始处理结构批次中的 OCR 区域")
            return
        }
        val draftAdmission = VisibleDraftContext.assess(batch)
        if (privateDetail && continueDeferredPrivateReadReturn(snapshot)) return
        if (observedDetail) {
            val executor = privateMessagesTraversalTaskExecutor
            val entry = executor.currentUnfinishedSession()
            val identityKey = ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact(
                currentSessionBinding?.observedVerifiedIdentity?.contactId
            )
            if (entry == null) {
                // A previously completed/no-reply visit may still be awaiting BACK.
                scheduleReturnToSessionList(snapshot)
                return
            }
            if (entry.verifiedKey != identityKey || entry.conversationKey != snapshot.conversationIdentityKey()) return
            val journalAdmitted = runCatching {
                observedPrivateJournalAdmitted(batch, snapshot, entry.openedAtMillis, entry.replyRequestKey)
            }.getOrDefault(false)
            onLog("OBSERVED_PRIVATE_BUSINESS_STATUS reason=${draftAdmission.reason};" +
                "replyEligible=${draftAdmission.accepted};retryable=${draftAdmission.retryable};" +
                "identityVerified=true;journalAdmitted=$journalAdmitted")
            if (!journalAdmitted) {
                schedulePrivatePreSendReadRetry("OBSERVED_PRIVATE_JOURNAL_WAIT")
                return
            }
            // Use the original visit time, not the manual navigation time, for
            // historical send proof. New visits also reject older uncertain records.
            if (entry.replyRequestKey != null && handleSendConfirmationResult(
                    onRecoveredPrivateSendTransaction(batch, snapshot, entry.openedAtMillis), batch, snapshot
                )) return
            if (!observedPrivateDetailVerified(snapshot) || isSendConfirmationPending()) return
        }
        if (draftTargetConfirmed && draftAdmission.retryable) {
            // Count only independent, matching visual-tail observations in this visit.
            // Actual OCR failures and missing structural evidence keep the old recovery path.
            val stableVisual = observeStableVisualReadFailure(batch, draftAdmission)
            if (stableVisual && tryDeferUnreadableVisualVisit(snapshot, batch, greetingDetail)) return
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
                    val fresh = runCatching { readPageSnapshot(AiReplyPageReadPurpose.MESSAGE_READ_RETRY) }.getOrNull()
                    if (fresh == null || !hasSameWindowSession(snapshot, fresh)) {
                        onLog("AI 消息复读停止：窗口或会话已变化")
                        return@schedule
                    }
                    readVisibleMessages(fresh)
                }
                return
            }
            onLog("AI 消息复读停止：${draftAdmission.reason}；已达两次快速重试上限，不猜测内容")
            onLog("AI 会话返回门禁[OCR_READ_NOT_CONFIRMED]：消息证据仍不完整；保留当前聊天页")
            if (privateDetail && !receivedGreetingsTaskActive) {
                schedulePrivatePreSendReadRetry("OCR_READ_NOT_CONFIRMED")
            } else if (greetingDetail && stableVisual) {
                scheduleReceivedGreetingsRecoveryRetry("非文本延期返回暂未准入；保留原招呼")
            }
            return
        }

        stableVisualReadFailure = null
        if (privateDetail && draftAdmission.accepted) {
            privateOcrRecoveryAttempt = 0
            draftReadRetries = 0
        }
        val rowIdentity = when {
            receivedGreetingsTaskActive -> receivedGreetingsTaskFlow.currentContactIdentity()
            observedDetail -> currentSessionBinding?.observedVerifiedIdentity
            else -> currentSessionCandidate?.contactIdentity
        }
        // Received greetings uses only the identity collected from the current avatar/profile.
        // Never resolve that identity by display name or consult previously stored contacts.
        val identity = when {
            receivedGreetingsTaskActive || observedDetail -> rowIdentity
            currentWindowOnlyFallback -> null
            else -> runCatching {
                resolveConversationIdentity(rowIdentity, snapshot.chatTitleText)
            }.getOrElse {
                onLog("AI 历史联系人解析失败：CONTACT_HISTORY_LOOKUP_FAILED；exception=${it.javaClass.simpleName}")
                rowIdentity
            }
        }
        if (privateDetail && !receivedGreetingsTaskActive &&
            identity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
            pendingPrivateFollowUpEnabled(ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact(identity.contactId))
        ) {
            val contactKey = ChatSessionManagementPolicy.stableSessionKeyForVerifiedContact(identity.contactId)
            if (contactMessageLedger.pendingRecoveryAction(contactKey) == ContactPendingRecoveryAction.ENABLE_INTERCEPTION &&
                verifiedPrivateLedgerContactKey() == contactKey &&
                !isSendConfirmationPending() && !isConversationDraftPending()
            ) {
                onLog("PRIVATE_FOLLOW_UP PRIOR_INTERCEPTION_RECONCILE;noNewDraft=true")
                startPrivateFollowUp(contactKey, null, snapshot)
            } else {
                onLog("PRIVATE_FOLLOW_UP PRIOR_PENDING;noNewDraft=true;checkpointRetained=true")
            }
            return
        }
        // Link a verified private visit to the actual dedupe key BEFORE the shared
        // draft entry can create a model request or segment journal. Legacy row-based
        // behavior stays compatible; observed details fail closed on missing linkage.
        var originalPendingRequestKey: String? = null
        if (privateDetail && draftAdmission.accepted && !currentWindowOnlyFallback &&
            identity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED
        ) {
            val executor = privateMessagesTraversalTaskExecutor
            val key = runCatching { privateReplyRequestKey(batch, snapshot) }.getOrNull()
            val original = executor.currentUnfinishedSession()?.replyRequestKey
            if (observedDetail && original != null && original != key) {
                // A new window changes the local dedupe key. The only permitted use
                // of the old key is the existing exact-composer PENDING_SEND restore.
                originalPendingRequestKey = original
            } else {
                val linked = key != null && executor.recordVerifiedVisitReplyRequestKey(key) &&
                    runCatching { privateMessagesCheckpointRepository?.save(executor.checkpoint()) }.isSuccess
                if (observedDetail && !linked) {
                    onLog("OBSERVED_PRIVATE_BUSINESS_WAIT reason=REQUEST_LINK_NOT_VERIFIED")
                    schedulePrivatePreSendReadRetry("OBSERVED_PRIVATE_REQUEST_LINK_WAIT")
                    return
                }
            }
        }
        val draftHoldsPage = if (draftTargetConfirmed && draftAdmission.accepted) {
            val draftCallback = if (greetingDetail) {
                onGreetingDraftConversationObserved
            } else {
                onDraftConversationObserved
            }
            val invokeDraft = {
                runCatching {
                    val original = originalPendingRequestKey
                    if (observedDetail && original != null) {
                        restoreObservedPrivatePendingDraft(batch, snapshot, draftVisitToken, original)
                    } else draftCallback(batch, snapshot, identity, draftVisitToken)
                }.getOrElse {
                    onLog("AI 草稿入口异常：[DRAFT_CALLBACK_FAILED]；未执行发送")
                    false
                }
            }
            if (greetingDetail && taskCoordinator != null) {
                // The greeting model request must start under the *same* lease that was
                // bound before OCR, not a newly transferred private-message capability.
                val lease = greetingChatBinding?.taskLease
                if (lease == null) {
                    onLog("GREETING_CHAT_DRAFT_REJECTED reason=TASK_LEASE_MISSING")
                    false
                } else {
                    taskCoordinator.withCurrentLease(lease, invokeDraft) ?: run {
                        onLog("GREETING_CHAT_DRAFT_REJECTED reason=TASK_LEASE_CHANGED")
                        false
                    }
                }
            } else if (observedDetail && taskCoordinator != null) {
                val lease = currentSessionBinding?.observedTaskLease
                if (lease == null || !observedPrivateDetailVerified(snapshot)) false
                else taskCoordinator.withCurrentLease(lease, invokeDraft) ?: false
            } else {
                invokeDraft()
            }
        } else {
            onLog("AI 草稿未准入：${if (!draftTargetConfirmed) "CHAT_ENTRY_NOT_BOUND" else draftAdmission.reason}")
            false
        } || isConversationDraftPending()
        if (privateDetail && privateModelRetryAwaitingDraft && draftAdmission.accepted) {
            if (draftHoldsPage) {
                privateModelRetryAwaitingDraft = false
                onLog("AI 私聊模型恢复[DRAFT_RESTARTED]：新鲜 OCR 已重新进入原草稿协调器；等待已有发送确认事务")
            } else {
                onLog("AI 私聊模型恢复[DRAFT_ADMISSION_WAIT]：草稿尚未重新取得准入；保留原会话，低频只读复核")
                schedulePrivatePreSendReadRetry("PRIVATE_MODEL_DRAFT_NOT_ADMITTED")
            }
        }
        // The greeting callback uses only the current chat OCR, not stored contact history.
        // Saving a sample cannot complete the greeting before confirmed send and safe BACK.
        if (greetingDetail) {
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
                "收到招呼本次聊天采集[CURRENT_CHAT_ONLY]：样本=${if (sampleIndex > 0) sampleIndex else "未保存"}；" +
                    "读取状态=${batch.status.name}；消息行=${batch.messages.size}；" +
                    "OCR目标=${batch.ocrTargets.size}；已识别=${batch.ocrRecognizedTargetCount}；" +
                    "失败=${batch.ocrFailedTargetCount}；截断=${batch.truncated}；" +
                    "唯一身份=${if (receivedGreetingsTaskFlow.currentContactIdentity() != null) "已验证" else "未验证"}；" +
                    "当前阶段=${if (draftHoldsPage) "DRAFT_OR_SEND_PENDING" else "DRAFT_NOT_STARTED"}"
            )
            // Neither a successful OCR sample nor an unsuccessful draft attempt is permission
            // to mark the greeting row processed. The only completion path is the verified
            // final-send branch above; unknown sends must never be repeated.
            onLog(
                if (draftHoldsPage) "GREETING_SEND_PENDING：等待本次聊天的全部消息段发送确认；不提前返回"
                else "GREETING_DRAFT_NOT_READY：保留当前会话与未完成招呼行；等待新鲜消息观察，不以 OCR 归档代替发送"
            )
            return
        }
        // This method is reached only after the asynchronous callback has passed the active
        // package/activity/window-generation check. Never log OCR output before that point.
        if (privateDetail && !draftAdmission.accepted && !draftAdmission.retryable &&
            !draftHoldsPage && !isConversationDraftPending() &&
            !isSendConfirmationPending() && !isSendControlRecoveryPending()
        ) {
            // Existing local policy has established that no automated reply is due.
            completePrivateBusinessObservation("LOCAL_${draftAdmission.reason}")
        }
        if (draftTargetConfirmed && draftAdmission.accepted &&
            !draftHoldsPage && !currentSessionSendConfirmed &&
            !(privateDetail && currentSessionWindowKey?.let {
                privateNoReplyCompleted(draftVisitToken, it)
            } == true)
        ) {
            onLog("AI 会话返回门禁[DRAFT_NOT_STARTED]：最新对方消息可回复，但草稿任务未取得有效终态；不返回列表")
            if (resumedPrivateVisit || observedDetail) schedulePrivatePreSendReadRetry("UNFINISHED_DRAFT_NOT_ADMITTED")
            return
        }
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
            if (!ensureAndroidRecoveryReady(snapshot, "MESSAGE_LIST_RECOVERY")) return
            val originalFault = assessPrivateListRecoveryFault(
                privateMessagesTraversalTaskExecutor.checkpoint(), recoveryContext.reason, snapshot
            )
            if (!originalFault.allowsResume) {
                pageRecoveryVerificationGate.reset(MESSAGE_LIST_RECOVERY_ROUTE_ID)
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
                onLog("消息列表恢复[ORIGINAL_FAULT_WAIT]：evidence=${originalFault.name}；原始业务阻塞未解除")
                scheduleSessionListStabilityRetry("原始业务故障尚未解除")
                return
            }
            val verificationKey = listOf(recoveryStructuralKey(snapshot), originalFault.name).joinToString("|")
            if (!pageRecoveryVerificationGate.observe(MESSAGE_LIST_RECOVERY_ROUTE_ID, verificationKey)) {
                sessionManagerState = SessionManagerState.PAUSED
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
                onLog("消息列表恢复[RECOVERY_VERIFYING]：等待第二次独立稳定页面快照；保持暂停")
                scheduleSessionListStabilityRetry("消息列表页面指纹尚未连续稳定")
                return
            }
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            if (originalFault != PrivateListRecoveryFaultEvidence.NOT_APPLICABLE) {
                privateMessagesTraversalTaskExecutor.observeUnreadBlockers(snapshot)
                privateMessagesTraversalTaskExecutor.observeIncompleteUnreadEvidence(snapshot)
                privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
            }
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeRequested)
            recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeCompleted)
            messageListRecoveryVerificationPending = false
            messageRecoveryRetryAttempt = 0
            pageRecoveryVerificationGate.reset(MESSAGE_LIST_RECOVERY_ROUTE_ID)
            reanchorRestoredMessageList(snapshot)
            onLog("消息列表恢复[RECOVERY_VERIFIED]：连续两次独立页面快照一致；恢复原有遍历算法")
        }
        // A recovery handoff may invoke the list handler directly. The pending paired
        // wake must be consumed by the next verified observation, not this callback.
        if (pairWake != null && !receivedGreetingsTaskActive) {
            pairWake?.due = true
            scheduleFullInspectionAfter(0L)
            return
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
                onLog("AI 私聊暂停分类[IDENTITY_BLOCKED]：原身份已隔离，禁止重复点击同一目标")
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
                        onLog("AI 会话身份复核暂停：未能确认原行平台身份；保留现场")
                        recoverRetryablePrivateList("PRIVATE_IDENTITY_RETURN_EVIDENCE_WAIT")
                    }
                }
                PrivateMessagesTraversalTaskExecutor.IdentityReturnStatus.REJECTED -> {
                    privateMessagesTraversalTaskExecutor.clearIdentityReturn()
                    onLog("AI 会话身份复核暂停：返回页面、原行、未读或列表位置不一致；保留现场")
                    recoverRetryablePrivateList("PRIVATE_IDENTITY_RETURN_PAGE_UNVERIFIED")
                }
                PrivateMessagesTraversalTaskExecutor.IdentityReturnStatus.RESTORED -> {
                    val candidate = result.candidate ?: return
                    if (chatSessionManagementPort.isSessionIdentityBlocked(ChatSessionManagementPolicy.stableSessionKey(candidate))) {
                        sessionManagerState = SessionManagerState.PAUSED
                        onLog("AI 私聊暂停分类[IDENTITY_BLOCKED]：候选身份已隔离，禁止重复点击")
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
            // The first snapshot establishes the Message work area; unread admission
            // begins on the independently confirmed top snapshot itself.
            privateMessagesTraversalTaskExecutor.normalInboxHadUnread = false
            privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown = false
            sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP
            onLog(
                "AI Reply 启动检查：已确认消息工作区域；当前视口行=${snapshot.chatListCandidates.size}；" +
                    "确认列表顶部后直接读取当前未读状态，不再强制首次滑动"
            )
        }
        // Before top confirmation, observations establish position only. The first confirmed
        // top snapshot is admission evidence and must not be skipped by a probe swipe.
        val hadUnknownUnreadBeforeFreshSnapshot = privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown
        if (privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted || receivedGreetingsTaskActive) {
            privateMessagesTraversalTaskExecutor.recordViewportUnreadEvidence(
                snapshot,
                receivedGreetingsActive = receivedGreetingsTaskActive
            )
        }

        var allowExpectedSessionReturnViewportChange = false
        if (sessionManagerState == SessionManagerState.RETURNING_TO_SESSION_LIST) {
            sessionTransitionTask?.cancel()
            sessionTransitionTask = null
            pageRecoveryVerificationGate.reset(CONFIRMED_PRIVATE_RETURN_DETAIL_GATE)
            confirmedPrivateReturnBackRetryCount = 0
            confirmedPrivateReturnAwaitingRecovery = false
            val observationCompleted = currentSessionReadCaptured
            val sendAlreadyRecorded = currentSessionSendConfirmed
            onLog(
                if (observationCompleted) "AI 私聊会话[LIST_RETURN_VERIFIED]：已验证返回普通消息列表；本次会话只读观察完成"
                else "AI 私聊会话[LIST_RETURN_VERIFIED]：已返回普通消息列表，但聊天内容读取未完成；不计入已完成观察数"
            )
            retireObservedPrivateDetail("CONFIRMED_LIST_RETURN")
            currentSessionKey = null
            currentSessionName = null
            currentSessionCandidate = null
            currentSessionReadCaptured = false
            currentSessionSendConfirmed = false
            currentSessionWindowKey = null
            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
            // The current session may move or change unread state when it is opened/read.
            // That is an expected return mutation, not an unsolicited viewport movement.
            allowExpectedSessionReturnViewportChange = true
            if (observationCompleted && sendAlreadyRecorded) {
                onLog("AI Reply 发送处理：本次会话的实际发送成功记录已完成；返回列表阶段不重复记录")
            }
        }

        // Verified inbox return, after retiring the completed detail transaction and
        // before evaluating or dispatching any next row/scroll.
        if (handlePrivateQuotaBoundary(snapshot)) return

        val wasWaitingForUnread = sessionManagerState == SessionManagerState.WAITING_FOR_UNREAD
        val originRecoveryPending = privateMessagesTraversalTaskExecutor.scanContinuityRecoveryPending
        val relocationPending = privateMessagesTraversalTaskExecutor.incompleteUnreadRelocationActive
        val incompleteBeforeObservation = privateMessagesTraversalTaskExecutor.unresolvedIncompleteUnreadKeys.toSet()
        val listEvaluation = privateMessagesTraversalTaskExecutor.evaluateListSnapshot(
            snapshot = snapshot,
            wasWaitingForUnread = wasWaitingForUnread,
            maxNoProgressScrolls = MAX_NO_PROGRESS_SCROLLS,
            allowExpectedSessionReturnViewportChange = allowExpectedSessionReturnViewportChange,
            allowCollectionTopConfirmation = !receivedGreetingsTaskActive,
            preferredRetryKey = missedPrivateSessionTapKey
        )
        privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
        if (listEvaluation.unreadAdmissionDecision == PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED &&
            originRecoveryPending && !privateMessagesTraversalTaskExecutor.scanContinuityRecoveryPending
        ) {
            onLog("消息列表检查点恢复[SAFE_READ_ORIGIN_VERIFIED]：新鲜已读边界双快照通过；停止向下搜索；保留原轮次及待处理记录")
        } else if (listEvaluation.unreadAdmissionDecision == PrivateMessagesUnreadAdmissionDecision.UNREAD_BOUNDARY_CONFIRMED &&
            relocationPending && !privateMessagesTraversalTaskExecutor.incompleteUnreadRelocationActive
        ) {
            onLog("AI 私聊异常会话[RELOCATION_ORIGIN_VERIFIED]：已重新找到目标或确认已读边界；保留未解决身份")
        }
        if (hadUnknownUnreadBeforeFreshSnapshot &&
            !privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown &&
            listEvaluation.unreadAdmissionDecision != PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED
        ) {
            onLog("AI Reply 未读UNKNOWN[REVALIDATED]：原阻断视口已重新确认；未读状态按最新合格会话证据重新准入；未放宽页面、身份或操作权门禁")
        }
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

        val incompleteAfterObservation = privateMessagesTraversalTaskExecutor.unresolvedIncompleteUnreadKeys
        val newlyPendingIncomplete = incompleteAfterObservation - incompleteBeforeObservation
        val repairedIncomplete = incompleteBeforeObservation - incompleteAfterObservation
        if (newlyPendingIncomplete.isNotEmpty() || repairedIncomplete.isNotEmpty()) {
            // Named hints already contain opaque traversal hashes; never expose anonymous
            // geometry or the underlying display name. This is a delta, not a new ledger.
            fun opaqueRows(hints: Set<String>): String = hints.mapNotNull { hint ->
                hint.removePrefix("identity:").takeIf {
                    hint.startsWith("identity:") && it.matches(Regex("[0-9a-f]{24}"))
                }?.take(12)
            }.sorted().joinToString(",")
            onLog("AI 私聊异常会话[INCOMPLETE_UNREAD_EVIDENCE_DELTA]：task=PRIVATE_MESSAGES；" +
                "added=${newlyPendingIncomplete.size}；addedRowHashes=${opaqueRows(newlyPendingIncomplete)}；" +
                "resolved=${repairedIncomplete.size}；resolvedRowHashes=${opaqueRows(repairedIncomplete)}；" +
                "remaining=${incompleteAfterObservation.size}；cacheWrite=false")
        }

        if (listEvaluation.incompleteUnreadEvidenceBlocked) {
            // Preserve the original round and opaque pending hints. A local row fault
            // cannot authorize a click on that row or count it as READ/attempted/completed.
            // One decision record per observation, rather than two overlapping
            // warnings. Keep count, reason and scope together so the existing
            // consecutive-event deduplication can preserve the repeat count.
            val scope = if (listEvaluation.incompleteUnreadAllowsIndependentTraversal)
                "ROW_SCOPED_BLOCK；仅隔离待复核会话；其余列表动作仍须通过原有窗口、租约与手势门禁；禁止提前完成本轮"
            else "LIST_EVIDENCE_INSUFFICIENT；缺少独立完整行证据或存在无法安全跨视口匹配的匿名行；保持原恢复门禁"
            onLog("AI 私聊异常会话[INCOMPLETE_UNREAD_RETRYABLE]：" +
                "unresolved=${privateMessagesTraversalTaskExecutor.unresolvedIncompleteUnreadKeys.size}；" +
                "cacheWrite=false；保持检查点，待原行新鲜可操作证据确认；scope=$scope")
            if (!listEvaluation.incompleteUnreadAllowsIndependentTraversal) {
                recoverRetryablePrivateList("PRIVATE_INCOMPLETE_UNREAD_ROW")
                return
            }
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
        if (listEvaluation.viewportPositionInvalidated) {
            // Do not accept the unassociated viewport as a new cursor or dispatch a
            // downward search. Reuse the existing checkpoint/independent page proof.
            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
            messageListCheckpointRestored = true
            onLog("AI 会话遍历[POSITION_UNVERIFIED]：当前位置无法关联原遍历锚点；保留方向、已读边界和本轮身份记录；通过原检查点重新定位")
            recoverRetryablePrivateList("PRIVATE_LIST_POSITION_UNVERIFIED")
            return
        }
        when (listEvaluation.scrollDecision) {
            PrivateMessagesScrollObservationDecision.NO_PENDING_SCROLL -> Unit
            PrivateMessagesScrollObservationDecision.TOP_VIEWPORT_CHANGED -> {
                // The swipe exposed a new viewport. Inspect this exact snapshot before issuing
                // another top-directed swipe, otherwise intermediate unread rows could be skipped.
                onLog("AI 会话管理：顶部方向滑动后可见会话行发生变化；先审视当前新区域")
            }
            PrivateMessagesScrollObservationDecision.TOP_NO_PROGRESS_RETRY -> {
                onLog("AI 会话管理：顶部定位后新快照未见位置变化；稳定确认计数=${privateMessagesTraversalTaskExecutor.noProgressScrollCount}")
                if (privateMessagesTraversalTaskExecutor.noProgressScrollCount >= MAX_NO_PROGRESS_SCROLLS &&
                    privateMessagesTraversalTaskExecutor.collectionBoundaryEvidence(snapshot, toTop = true) == false
                ) {
                    sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                    scheduleSessionListStabilityRetry("集合位置仍指示未到顶部，等待新快照复核")
                } else {
                    scrollSessionList(snapshot, toTop = true)
                }
                return
            }
            PrivateMessagesScrollObservationDecision.TOP_BOUNDARY_CANDIDATE -> {
                // Startup still needs the original top proof. On the return scan,
                // finish visible unread work before starting the completion proof.
                if (!privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted ||
                    listEvaluation.nextCandidate == null
                ) {
                    beginBoundaryStabilityConfirmation(visibleKeys, toTop = true, snapshot = snapshot)
                    return
                }
            }
            PrivateMessagesScrollObservationDecision.BOTTOM_NO_PROGRESS -> {
                onLog("AI 会话管理：滚动后可见行顺序与身份集合未变化；底部定位无进展计数=${privateMessagesTraversalTaskExecutor.noProgressScrollCount}")
            }
            PrivateMessagesScrollObservationDecision.BOTTOM_BOUNDARY_CANDIDATE -> {
                // A full-read viewport or UNKNOWN decision has precedence over physical
                // end-of-list confirmation. The physical end is only a fallback.
                if (listEvaluation.unreadAdmissionDecision == PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING ||
                    listEvaluation.unreadAdmissionDecision == null
                ) {
                    onLog("AI 会话管理：向下搜索无进展计数=${privateMessagesTraversalTaskExecutor.noProgressScrollCount}；复核列表末端候选")
                    beginBoundaryStabilityConfirmation(visibleKeys, toTop = false, snapshot = snapshot)
                    return
                }
            }
            PrivateMessagesScrollObservationDecision.BOTTOM_VIEWPORT_CHANGED -> Unit
            PrivateMessagesScrollObservationDecision.POSITION_UNVERIFIED -> return
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
                PrivateMessagesUnreadAdmissionDecision.WAIT_FOR_TOP_CONFIRMATION -> {
                    sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_TOP
                    scheduleSessionListStabilityRetry("等待顶部位置确认，不进行未读准入")
                    return
                }
                PrivateMessagesUnreadAdmissionDecision.UNKNOWN_BLOCKED -> {
                    onLog("AI Reply 启动准入[UNREAD_STATE_UNKNOWN]：当前已确认列表视口存在 UNKNOWN；暂停，不把未知当作无未读")
                    recoverRetryablePrivateList("INITIAL_UNREAD_STATE_UNKNOWN")
                    return
                }
                PrivateMessagesUnreadAdmissionDecision.NO_UNREAD -> {
                    startupReadinessCoordinator.markInitializationComplete("顶部已读视口完成独立稳定复核")
                    onLog("AI Reply 启动准入[NO_UNREAD_AT_CONFIRMED_TOP]：顶部完整有效视口全部明确已读；不需要向下试探滑动")
                    requestGreetingsAfterPrivateTraversal("已确认顶部完整有效视口全部明确已读", snapshot)
                    return
                }
                PrivateMessagesUnreadAdmissionDecision.CONTINUE_BOTTOM_POSITIONING -> {
                    sessionManagerState = SessionManagerState.POSITIONING_SESSION_LIST_AT_BOTTOM
                    onLog("AI Reply 启动准入[UNREAD_LOCKING]：已锁定明确未读身份=${privateMessagesTraversalTaskExecutor.lockedUnreadSessionKeys.size}；继续向下搜索未读区域边界")
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
                    onLog("AI Reply 启动准入[UNREAD_BOUNDARY_CONFIRMED]：已确认未读区域边界或重新找到待处理目标；停止向下搜索，开始由下至上处理")
                    // Do not return: the current stable snapshot is already the first
                    // bottom-to-top processing snapshot. The next candidate is selected below.
                }
            }
        }

        val next = listEvaluation.nextCandidate
        if (next != null || !listEvaluation.incompleteUnreadEvidenceBlocked ||
            !privateMessagesTraversalTaskExecutor.atTop
        ) {
            // A normal candidate, resolved evidence or a changed position supersedes
            // the parked relocation intent. Existing state/generation checks own callbacks.
            incompleteUnreadRelocationWait = null
        }
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
                val captureRunGeneration = currentRunGeneration()
                val captureActionGeneration = sessionActionGeneration
                chatSessionManagementPort.captureSessionListSample(snapshot) { screenshotPath ->
                    scheduler.schedule(0L) {
                        if (!isAiReplyActive() || !sessionRunStarted ||
                            captureRunGeneration != currentRunGeneration() ||
                            captureActionGeneration != sessionActionGeneration ||
                            !canPerformPrivateMessagesUiAction() || receivedGreetingsTaskActive ||
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
                        // A diagnostic callback cannot reuse the old no-candidate
                        // decision: the list may have reordered while capturing.
                        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                        scheduleFullInspection()
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
            onLog("AI Reply 低频待机结束：当前页发现明确未读；继续搜索未读区域边界并由下往上处理")
            scrollSessionList(snapshot, toTop = false)
            return
        } else {
            sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
        }

        val selectedKey = ChatSessionManagementPolicy.stableSessionKey(next)
        val selectedWindow = next.rowTarget.window
        val selectedName = next.nameText
        if (privateMessagesTraversalTaskExecutor.capturedSessionListFingerprints.add(structuralFingerprint)) {
            // A verified-only viewport does not need weak-identity avatar conflict evidence.
            // Preserve its structured sample without waiting for a diagnostic screenshot.
            val eligibleRows = snapshot.chatListCandidates.filter(ChatSessionManagementPolicy::isEligible)
            val verifiedOnly = eligibleRows.isNotEmpty() && eligibleRows.all {
                it.contactIdentity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                    !it.contactIdentity?.contactId.isNullOrBlank()
            }
            if (verifiedOnly && chatSessionManagementPort.persistVerifiedSessionListMetadata(snapshot)) {
                onLog("AI 会话管理：列表视口样本已累计；已验证身份视口仅保存结构化样本，不等待诊断截图")
                attemptOpenSession(selectedKey, selectedWindow, selectedName)
                return
            }

            // Screenshot evidence is diagnostic. Pass only the selected identity/window
            // hint to the existing fresh-read click executor. Its traversal-order gate
            // rejects a stale selection without dispatching or recapturing the same row.
            sessionManagerState = SessionManagerState.CAPTURING_SESSION_LIST
            val captureRunGeneration = currentRunGeneration()
            val captureActionGeneration = sessionActionGeneration
            chatSessionManagementPort.captureSessionListSample(snapshot) { screenshotPath ->
                scheduler.schedule(0L) {
                    if (!isAiReplyActive() || !sessionRunStarted ||
                        captureRunGeneration != currentRunGeneration() ||
                        captureActionGeneration != sessionActionGeneration ||
                        !canPerformPrivateMessagesUiAction() || receivedGreetingsTaskActive ||
                        sessionManagerState != SessionManagerState.CAPTURING_SESSION_LIST
                    ) return@schedule
                    onLog(
                        "AI 会话管理：列表视口样本已累计；身份诊断完成；" +
                            "累计不同视口=${privateMessagesTraversalTaskExecutor.capturedSessionListFingerprints.size}；" +
                            "截图=${if (screenshotPath.isNullOrBlank()) "不可用" else "已保存"}；使用身份提示重新采集当前会话行"
                    )
                    sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                    attemptOpenSession(selectedKey, selectedWindow, selectedName)
                }
            }
        } else {
            attemptOpenSession(selectedKey, selectedWindow, selectedName)
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
        privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationKeys =
            privateMessagesTraversalTaskExecutor.boundaryConfirmationKeys(snapshot)
        privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationToTop = toTop
        privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount = 0
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
        onLog(
            if (toTop) {
                if (privateMessagesTraversalTaskExecutor.collectionBoundaryEvidence(snapshot, toTop = true) == true) {
                    "AI 会话管理：集合行位置与滚动能力已指示顶部；无需试探滑动，等待延迟新快照复核"
                } else "AI 会话管理：顶部连续无位移仅作为候选边界；等待延迟新快照复核"
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
            if (fresh != null && isSemanticallyUnconfirmed(fresh) &&
                fresh.packageName == targetPackage() &&
                fresh.windowIdentityKey() == expectedWindowIdentity
            ) {
                requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, "MESSAGE_LIST_BOUNDARY_UNKNOWN")
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
                onLog("AI 会话管理已暂停：延迟边界复核未能确认同一会话列表页面；不判定遍历完成")
                recoverRetryablePrivateList("PRIVATE_LIST_BOUNDARY_PAGE_CHANGED")
                return@schedule
            }
            val currentKeys = privateMessagesTraversalTaskExecutor.boundaryConfirmationKeys(fresh)
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
            if (currentKeys != expectedKeys || privateMessagesTraversalTaskExecutor.collectionBoundaryEvidence(
                    fresh, privateMessagesTraversalTaskExecutor.pendingBoundaryConfirmationToTop
                ) == false
            ) {
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
                        "${privateMessagesTraversalTaskExecutor.boundaryStabilityConfirmationCount}/$MAX_BOUNDARY_STABILITY_CONFIRMATIONS"
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
                    onLog("AI Reply 启动顶部定位[CONFIRMED]：列表顶部已通过位置证据及延迟独立快照复核；直接检查顶部未读状态")
                } else {
                    sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                    onLog("AI 会话管理：顶部边界经延迟独立快照复核通过；检查顶部当前可见会话行")
                }
            } else {
                val repairing = privateMessagesTraversalTaskExecutor.scanContinuityRecoveryPending
                privateMessagesTraversalTaskExecutor.confirmPhysicalEndForReturnTraversal()
                if (repairing) {
                    privateMessagesTraversalTaskExecutor.recordVerifiedViewport(fresh)
                    privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
                    onLog("消息列表检查点恢复[SAFE_ORIGIN_VERIFIED]：底部独立复核通过；同轮由下至上补扫")
                }
                sessionManagerState = SessionManagerState.SCANNING_SESSION_LIST_BOTTOM_TO_TOP
                onLog("AI 会话管理：底部边界经延迟独立快照复核通过；本轮未读边界已确认，候选仍可补充；进入从底部向顶部的处理阶段")
            }
            handleSessionListPage(fresh)
        }
    }

    private fun continueSessionListWithoutCandidate(snapshot: ChatPageSnapshot) {
        if (privateMessagesTraversalTaskExecutor.phase == SessionListTraversalPhase.POSITIONING_AT_BOTTOM) {
            if (privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown &&
                !privateMessagesTraversalTaskExecutor.canContinueWithUnknownUnread(snapshot)
            ) {
                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                onLog("AI Reply 启动准入[POSITIONING_BLOCKED_BY_UNKNOWN]：缺少独立完整行证据；等待新页面证据")
                scheduleSessionListStabilityRetry("未读状态尚未确认")
            } else {
                scrollSessionList(snapshot, toTop = false)
            }
            return
        }
        if (privateMessagesTraversalTaskExecutor.atTop) {
            privateMessagesTraversalTaskExecutor.noProgressScrollCount = 0
            privateMessagesTraversalTaskExecutor.sessionListStabilityRetryCount = 0
            if (snapshot.chatListExtractionTruncated || snapshot.windowIdentityKey() == null ||
                privateMessagesTraversalTaskExecutor.visibleSessionKeys(snapshot).isEmpty() ||
                privateMessagesTraversalTaskExecutor.collectionBoundaryEvidence(snapshot, toTop = true) == false
            ) {
                privateMessagesTraversalTaskExecutor.atTop = false
                scheduleSessionListStabilityRetry("顶部完成证据已失效，重新复核")
                return
            }
            // The existing same-round relocation is a recovery of unresolved work,
            // not completion or an ordinary skip. It owns the confirmed-top retry
            // only when no fresh actionable row can be selected.
            if (privateMessagesTraversalTaskExecutor.nextFreshReturnCandidate(snapshot) == null &&
                observeIncompleteUnreadRelocation(snapshot)
            ) return
            if (privateMessagesTraversalTaskExecutor.hasPendingVisibleReturnWork(snapshot)) {
                onLog("AI 会话遍历[TOP_PENDING]：顶部视口仍有待解决未读行；保留检查点，不切换任务")
                scheduleSessionListStabilityRetry("顶部未读候选尚未解决")
                return
            }
            if (privateMessagesTraversalTaskExecutor.hasUnresolvedVisibleEdgeEvidence(snapshot)) {
                onLog("AI 私聊异常会话[HANDOFF_BLOCKED]：边缘未读证据尚未明确；重新观察，不计为已读")
                scheduleSessionListStabilityRetry("边缘未读证据待复核")
                return
            }
            when {
                privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown ||
                    privateMessagesTraversalTaskExecutor.unresolvedUnknownUnreadKeys.isNotEmpty() -> {
                    sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                    onLog("AI 私聊候选[UNREAD_EVIDENCE_PENDING]：存在未读状态 UNKNOWN 的会话行，不能将未知当作无未读")
                    scheduleSessionListStabilityRetry("待复核未读身份尚未确认")
                }
                else -> {
                    requestGreetingsAfterPrivateTraversal(
                        if (privateMessagesTraversalTaskExecutor.normalInboxHadUnread) "普通聊天列表已完成由下至上扫描，当前没有剩余符合条件的未读会话"
                        else "普通聊天列表已完成筛选，没有符合条件的新消息",
                        snapshot
                    )
                }
            }
            return
        }
        scrollSessionList(snapshot, toTop = true)
    }

    /**
     * At an independently confirmed top, revisit a named pending row after the existing
     * read-only retry delay. The timer produces intent only. A later ordinary observation
     * (with no selectable candidate) must revalidate the page, ownership and original
     * pending evidence before the existing traversal is allowed to move again.
     */
    private fun observeIncompleteUnreadRelocation(snapshot: ChatPageSnapshot): Boolean {
        val executor = privateMessagesTraversalTaskExecutor
        if (!executor.canBeginIncompleteUnreadRelocation(snapshot)) {
            incompleteUnreadRelocationWait = null
            return false
        }
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive ||
            !canPerformPrivateMessagesUiAction() || userInteractionActive ||
            activeSessionGestureRequestId != null || isSendConfirmationPending() ||
            isConversationDraftPending() || snapshot.packageName != targetPackage() ||
            isSemanticallyUnconfirmed(snapshot)
        ) {
            incompleteUnreadRelocationWait = null
            scheduleSessionListStabilityRetry("异常会话重新定位等待安全页面与空闲任务")
            return true
        }
        if (!privateRelocationRetryOwed && receivedGreetingsTaskFlow.supportsWorkflow() &&
            privateMessagesCheckpointRepository != null
        ) {
            onLog("AI 私聊异常会话[DEFERRED_WORK_HANDOFF]：局部待复核身份不计为完成；保留检查点并安全交接")
            startReceivedGreetingsTask("原异常未读待重新定位；先交接招呼任务", snapshot, AiReplyTaskYieldReason.DEFERRED_WORK)
            return true
        }
        val window = snapshot.windowIdentityKey() ?: return false
        val visibleKeys = executor.visibleSessionKeys(snapshot)
        val wait = incompleteUnreadRelocationWait?.takeIf {
            it.runGeneration == currentRunGeneration() &&
                it.actionGeneration == sessionActionGeneration && it.windowIdentity == window &&
                it.visibleKeys == visibleKeys
        }
        if (wait?.due == true) {
            incompleteUnreadRelocationWait = null
            if (!ensureAndroidRecoveryReady(snapshot, "PRIVATE_INCOMPLETE_RELOCATION")) {
                recoverRetryablePrivateList("PRIVATE_INCOMPLETE_RELOCATION_ANDROID_NOT_READY")
                return true
            }
            if (!canPerformPrivateMessagesUiAction() || !executor.beginIncompleteUnreadRelocation(snapshot)) {
                scheduleSessionListStabilityRetry("异常会话重新定位准入已变化")
                return true
            }
            privateRelocationRetryOwed = false
            privateMessagesCheckpointRepository?.save(executor.checkpoint())
            onLog("AI 私聊异常会话[RELOCATION_STARTED]：保留原轮次、已处理记录与未决身份；重新寻找待处理目标，找到后立即反向处理")
            scrollSessionList(snapshot, toTop = false)
            return true
        }
        if (wait != null && sessionTransitionTask != null) return true

        val ticket = IncompleteUnreadRelocationWait(
            currentRunGeneration(), sessionActionGeneration, window, visibleKeys
        )
        incompleteUnreadRelocationWait = ticket
        privateMessagesCheckpointRepository?.save(executor.checkpoint())
        onLog("AI 私聊异常会话[RELOCATION_WAIT]：已确认顶部但原异常未解决；按现有退避等待下一次新鲜列表观察")
        scheduleSessionListStabilityRetry("原异常未读会话受控重新定位") {
            if (incompleteUnreadRelocationWait === ticket &&
                ticket.runGeneration == currentRunGeneration() &&
                ticket.actionGeneration == sessionActionGeneration
            ) ticket.due = true
        }
        return true
    }

    private fun resetPairedQuotaRun() {
        privateSliceQuota.resetRun()
        greetingSliceQuota.resetRun()
        pendingPrivateQuotaReceipt = null
        privateQuotaReturnReady = false
    }

    private fun handlePrivateQuotaBoundary(snapshot: ChatPageSnapshot): Boolean {
        if (receivedGreetingsTaskActive) return false
        if (!privateQuotaReturnReady && !privateSliceQuota.reached) return false
        if (!canPerformPrivateMessagesUiAction() ||
            currentSessionKey != null || currentSessionSendConfirmed || isPrivateFollowUpActive() ||
            isSendConfirmationPending() || isConversationDraftPending() || userInteractionActive ||
            snapshot.packageName != targetPackage() || snapshot.pageKind != ChatPageKind.CHAT_LIST ||
            snapshot.chatListExtractionTruncated || snapshot.windowIdentityKey() == null ||
            isSemanticallyUnconfirmed(snapshot)
        ) {
            scheduleSessionListStabilityRetry("私聊配额返回边界尚未安全确认")
            return true
        }
        if (privateQuotaReturnReady) {
            if (!ensureAndroidRecoveryReady(snapshot, "PRIVATE_QUOTA_RETURN")) {
                recoverRetryablePrivateList("PRIVATE_QUOTA_RETURN_NOT_READY")
                return true
            }
            val receipt = pendingPrivateQuotaReceipt
            if (receipt != null && privateSliceQuota.record(receipt)) {
                onLog("PAIRED_QUOTA[COUNT] task=PRIVATE;completed=${privateSliceQuota.completed}/${AiReplyTaskSliceQuota.LIMIT}")
            }
            pendingPrivateQuotaReceipt = null
            privateQuotaReturnReady = false
        }
        if (!privateSliceQuota.reached || !receivedGreetingsTaskFlow.supportsWorkflow()) return false
        onLog("PAIRED_QUOTA[YIELD] task=PRIVATE;reason=SAFE_SESSION_BOUNDARY")
        startReceivedGreetingsTask("私人消息已处理两次未读会话", snapshot, AiReplyTaskYieldReason.SAFE_SESSION_BOUNDARY)
        return true
    }

    private fun handleGreetingQuotaBoundary(observation: ObservationResult, inspection: ChatPageSnapshot): Boolean {
        if (receivedGreetingsTaskFlow.awaitingNormalInboxAfterReturn || !ownsTask(RECEIVED_GREETINGS_TASK_ID) ||
            observation.status != ObservationStatus.CONFIRMED_PAGE || inspection.pageKind != ChatPageKind.NON_CHAT ||
            inspection.packageName != targetPackage() || recoveryContext.pending || userInteractionActive ||
            isSendConfirmationPending() || isConversationDraftPending()
        ) return false
        if (receivedGreetingsTaskFlow.traversalStatus() !in setOf(
                ReceivedGreetingsTraversalStatus.SCANNING, ReceivedGreetingsTraversalStatus.COMPLETE
            )
        ) return false
        val receipts = runCatching { chatPagePort.consumeReceivedGreetingsVerifiedReturnReceipts() }
            .getOrElse {
                scheduleReceivedGreetingsRecoveryRetry("返回凭证暂不可读；保留任务")
                return true
            }
        for (receipt in receipts) {
            if (greetingSliceQuota.record(receipt)) {
                onLog("PAIRED_QUOTA[COUNT] task=GREETINGS;completed=${greetingSliceQuota.completed}/${AiReplyTaskSliceQuota.LIMIT}")
            }
        }
        if (!greetingSliceQuota.reached) return false
        if (!ensureAndroidRecoveryReady(inspection, "GREETING_QUOTA_RETURN") ||
            !receivedGreetingsTaskFlow.requestSafeHandoffReturn()
        ) {
            scheduleReceivedGreetingsRecoveryRetry("招呼配额已达到；等待安全返回准入")
            return true
        }
        onLog("PAIRED_QUOTA[YIELD] task=GREETINGS;reason=SAFE_SESSION_BOUNDARY")
        requestGreetingsReturn(observation, inspection)
        return true
    }

    /** The paired scheduler uses both task-local yield reasons, never a pair completion gate. */
    private fun schedulePairedContinuation(
        privateYield: AiReplyTaskYieldReason?,
        greetingYield: AiReplyTaskYieldReason?,
        visibleWork: Boolean
    ) {
        val refreshPrivate = privateYield == AiReplyTaskYieldReason.TRAVERSAL_EXHAUSTED
        val privateNoRunnable = privateYield?.hasNoImmediatelyRunnableWork == true
        val greetingNoRunnable = greetingYield?.hasNoImmediatelyRunnableWork == true
        onLog("跨任务循环[CONTINUATION]：private=$privateYield；greetings=$greetingYield；visibleWork=$visibleWork")
        if (privateNoRunnable && greetingNoRunnable && !visibleWork) {
            enterPairIdle("当前两侧无可立即执行工作；保留未决记录",
                if (refreshPrivate) PairWakeMode.REFRESH_PRIVATE else PairWakeMode.RESUME_PRIVATE)
        } else if (refreshPrivate) {
            requestPairWake(PairWakeMode.REFRESH_PRIVATE, due = true)
            scheduleFullInspectionAfter(0L)
        } else {
            recoverRetryablePrivateList("PRIVATE_RESUME_AFTER_GREETINGS")
        }
    }

    private fun scheduleGreetingListContinuation(observation: ObservationResult, inspection: ChatPageSnapshot) {
        val evidenceOnly = receivedGreetingsTaskFlow.isWaitingForSafeTargetEvidence()
        if (evidenceOnly &&
            observation.status == ObservationStatus.CONFIRMED_PAGE &&
            inspection.pageKind == ChatPageKind.NON_CHAT &&
            inspection.packageName == targetPackage() && !recoveryContext.pending && !userInteractionActive &&
            !isSendConfirmationPending() && !isConversationDraftPending() &&
            receivedGreetingsTaskFlow.requestSafeHandoffReturn(AiReplyTaskYieldReason.DEFERRED_WORK)
        ) {
            onLog("GREETING_DEFERRED_EVIDENCE_ONLY：保留安全失败证据；交接私聊任务，不报告遍历完成")
            requestGreetingsReturn(observation, inspection)
            return
        }
        // When the source is not verified, retain the original read-only cadence.
        scheduleFullInspectionAfter(if (evidenceOnly) 30_000L else RECEIVED_GREETINGS_RECHECK_DELAY_MS)
    }

    private fun requestPairWake(mode: PairWakeMode, due: Boolean): PairWake {
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        val wake = PairWake(mode, due)
        pairWake = wake
        pageRecoveryVerificationGate.reset(NEXT_PAIR_WAKE_GATE)
        return wake
    }

    private fun enterPairIdle(message: String, mode: PairWakeMode) {
        val wake = requestPairWake(mode, due = false)
        sessionManagerState = SessionManagerState.WAITING_FOR_UNREAD
        onLog(message)
        val seconds = chooseRandomInt(LOW_FREQUENCY_IDLE_MIN_SECONDS, LOW_FREQUENCY_IDLE_MAX_SECONDS)
            .coerceIn(LOW_FREQUENCY_IDLE_MIN_SECONDS, LOW_FREQUENCY_IDLE_MAX_SECONDS)
        val runGeneration = currentRunGeneration()
        val actionGeneration = sessionActionGeneration
        onLog("AI Reply 低频待机：$seconds 秒后自主复查双任务；mode=$mode")
        sessionTransitionTask = scheduler.schedule(seconds * 1_000L) {
            if (!isAiReplyActive() || !sessionRunStarted || pairWake !== wake ||
                runGeneration != currentRunGeneration() || actionGeneration != sessionActionGeneration
            ) return@schedule
            sessionTransitionTask = null
            wake.due = true
            onLog("自主轮询[ROUND_DUE]：双任务待机到期；仅请求新鲜页面观察；mode=$mode")
            scheduleFullInspectionAfter(0L)
        }
    }

    private fun invalidatePairWakeEvidence() {
        pageRecoveryVerificationGate.reset(NEXT_PAIR_WAKE_GATE)
        pairWake?.due = true
    }

    private fun resetPairWake() {
        pairWake = null
        pageRecoveryVerificationGate.reset(NEXT_PAIR_WAKE_GATE)
    }

    private fun waitForPairWakeEvidence(reason: String) {
        val wake = pairWake ?: return
        pageRecoveryVerificationGate.reset(NEXT_PAIR_WAKE_GATE)
        wake.due = true
        wake.retryAttempt += 1
        val delay = RecoveryScheduler.readOnlyRetryDelayMs(wake.retryAttempt)
        onLog("自主轮询[READ_ONLY_WAIT]：reason=$reason；delayMs=$delay；mode=${wake.mode}；保留原检查点")
        scheduleFullInspectionAfter(delay)
    }

    /** A wakeup is intent only: fresh route, unread proof, Android READY and lease are required. */
    private fun observePairWake(
        observation: ObservationResult,
        snapshot: ChatPageSnapshot,
        matchedRouteId: String?
    ) {
        val wake = pairWake ?: return
        if (!sessionRunStarted || !isAiReplyActive()) return
        if (observation.status != ObservationStatus.CONFIRMED_PAGE ||
            snapshot.packageName != targetPackage() || snapshot.pageKind != ChatPageKind.CHAT_LIST ||
            matchedRouteId != messageListRecoveryRoute.routeId || !ownsTask(PRIVATE_MESSAGES_TASK_ID)
        ) {
            invalidatePairWakeEvidence()
            recoverRetryablePrivateList("POLLING_INBOX_UNCONFIRMED")
            return
        }
        val key = snapshot.messageTabPositionKey()
        if (key.isNullOrBlank() || snapshot.chatListExtractionTruncated || snapshot.chatListCandidates.isEmpty()) {
            waitForPairWakeEvidence("INBOX_STRUCTURE_INCOMPLETE")
            return
        }
        if (!ensureAndroidRecoveryReady(snapshot, "NEXT_POLLING_ROUND")) {
            invalidatePairWakeEvidence()
            return
        }
        // Reuse the existing UNKNOWN identity/viewport latch; a clean different viewport
        // must not erase a blocker observed while waiting between rounds.
        val executor = privateMessagesTraversalTaskExecutor
        val incompleteUnreadBlocked = executor.observeIncompleteUnreadEvidence(snapshot)
        if (executor.observeUnreadBlockers(snapshot) || executor.unresolvedUnknownUnreadKeys.isNotEmpty() ||
            incompleteUnreadBlocked
        ) {
            privateMessagesCheckpointRepository?.save(executor.checkpoint())
            if (wake.mode == PairWakeMode.REFRESH_PRIVATE) {
                waitForPairWakeEvidence(
                    if (incompleteUnreadBlocked) "INCOMPLETE_PRIVATE_UNREAD_EVIDENCE" else "UNREAD_UNKNOWN"
                )
                return
            }
        }
        val unread = executor.hasUnparkedUnread(snapshot)
        if (!wake.due && !unread) return
        wake.due = true
        if (userInteractionActive || activeSessionGestureRequestId != null ||
            isSendConfirmationPending() || isConversationDraftPending() || isPrivateFollowUpActive()
        ) {
            waitForPairWakeEvidence("BUSINESS_BUSY")
            return
        }
        if (!pageRecoveryVerificationGate.observe(NEXT_PAIR_WAKE_GATE, key)) {
            onLog("自主轮询[ROUND_VERIFYING]：第1/2次新鲜消息列表快照；尚未开启下一轮")
            scheduleFullInspectionAfter(350L)
            return
        }
        if (!ensureAndroidRecoveryReady(snapshot, "NEXT_POLLING_ROUND_BEFORE_START") ||
            !ownsTask(PRIVATE_MESSAGES_TASK_ID)
        ) {
            waitForPairWakeEvidence("LEASE_OR_ANDROID_NOT_READY")
            return
        }
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        sessionActionGeneration += 1L
        invalidatePendingMessageRead()
        if (wake.mode == PairWakeMode.RESUME_PRIVATE || executor.scanContinuityRecoveryPending) {
            resetPairWake()
            onLog("跨任务循环[CURSOR_RESUME]：保留原私聊候选、延期记录及游标")
            recoverRetryablePrivateList("PRIVATE_RESUME_AFTER_PAIR_IDLE")
            return
        }
        val previousAttempted = executor.attemptedSessionKeys.size
        val previousLockedUnread = executor.lockedUnreadSessionKeys.size
        val previousViewportSamples = executor.capturedSessionListFingerprints.size
        val previousSendReceiptPreserved = !executor.checkpointLastSuccessfulTaskId.isNullOrBlank()
        // Rebuild first. Only a successfully admitted new round can retire the parked
        // round's intent; a same-round checkpoint resume must never use this path.
        executor.renewExhaustedPrivateSelection()
        privateRelocationRetryOwed = false
        resetPairWake()
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
        privateMessagesCheckpointRepository?.save(executor.checkpoint())
        onLog("自主轮询[ROUND_STATE_REBUILT]：旧已尝试=$previousAttempted；旧未读锁定=$previousLockedUnread；" +
            "旧视口去重=$previousViewportSamples；仅退休私聊已耗尽候选；" +
            "确认发送审计保留=$previousSendReceiptPreserved")
        onLog("自主轮询[ROUND_STARTED]：仅重新准入已耗尽的私聊任务；不要求招呼任务完整遍历")
        scheduleFullInspectionAfter(0L)
    }

    /**
     * The existing no-more-private-work scheduling decision, not a general handoff
     * authorization. Exhaustion proof is local to this task; other yields preserve the cursor.
     * A session-boundary handoff must not assert that this scan has completed.
     */
    private fun requestGreetingsAfterPrivateTraversal(reason: String, snapshot: ChatPageSnapshot) {
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive) return
        if (privateMessagesTraversalTaskExecutor.scanContinuityRecoveryPending ||
            privateMessagesTraversalTaskExecutor.phase != SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP ||
            !privateMessagesTraversalTaskExecutor.atTop ||
            privateMessagesTraversalTaskExecutor.normalInboxUnreadStateUnknown ||
            privateMessagesTraversalTaskExecutor.unresolvedUnknownUnreadKeys.isNotEmpty()
        ) {
            onLog(
                "AI Reply 私聊无剩余工作判定尚未成立：普通会话列表尚未完成底部至顶部的复核，" +
                    "或仍存在 UNKNOWN 未读；不得将未完成遍历报告为耗尽"
            )
            recoverRetryablePrivateList("PRIVATE_SCAN_NOT_COMPLETE_FOR_HANDOFF")
            return
        }
        if (privateMessagesTraversalTaskExecutor.unresolvedIncompleteUnreadKeys.isNotEmpty()) {
            // A verified page and completed physical scan do not prove that a pending
            // unread row is resolved. Keep the cursor and retry observations, without
            // promoting a row-local completion blocker into a new UNKNOWN page.
            privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
            onLog("AI 私聊异常会话[HANDOFF_BLOCKED]：存在待复核的不完整未读会话；保留本轮检查点，不切换收到招呼任务")
            scheduleSessionListStabilityRetry("原异常未读会话尚未重新确认")
            return
        }
        val executor = privateMessagesTraversalTaskExecutor
        if (!executor.lockedUnreadSetFinalized || executor.pendingKnownUnreadKeys().isNotEmpty()) {
            privateMessagesCheckpointRepository?.save(executor.checkpoint())
            onLog("AI 私聊异常会话[HANDOFF_BLOCKED]：未读边界或剩余候选尚未完成；" +
                "pendingUnread=${executor.pendingKnownUnreadKeys().size}；保留本轮检查点")
            scheduleSessionListStabilityRetry("本轮未读尚未完成")
            return
        }
        startReceivedGreetingsTask(reason, snapshot, AiReplyTaskYieldReason.TRAVERSAL_EXHAUSTED)
    }

    /**
     * Single private-to-greetings admission, shared by exhausted-work scheduling and the
     * later session-boundary quota. Pending row/scan evidence is checkpoint data, not a
     * reason to reject a safe task handoff. Call only from a fresh list observation.
     */
    private fun startReceivedGreetingsTask(
        reason: String,
        snapshot: ChatPageSnapshot,
        handoff: AiReplyTaskYieldReason
    ) {
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive) return
        val executor = privateMessagesTraversalTaskExecutor
        if (!canPerformPrivateMessagesUiAction() || userInteractionActive ||
            executor.pendingScrollBaseline != null || executor.pendingBoundaryConfirmationKeys != null ||
            executor.identityReturnPending || currentSessionKey != null || currentSessionSendConfirmed ||
            confirmedPrivateReturnAwaitingRecovery || isPrivateFollowUpActive() ||
            isSendConfirmationPending() || isConversationDraftPending() || activeSessionGestureRequestId != null
        ) {
            onLog("AI 私聊异常会话[HANDOFF_BLOCKED]：当前事务、手势、恢复或操作权尚未安全交接；保留本轮检查点")
            scheduleSessionListStabilityRetry("当前私聊事务尚未结束")
            return
        }
        if (snapshot.packageName != targetPackage() || snapshot.pageKind != ChatPageKind.CHAT_LIST ||
            snapshot.chatListExtractionTruncated || snapshot.windowIdentityKey() == null ||
            isSemanticallyUnconfirmed(snapshot)
        ) {
            onLog("跨任务交接[PRIVATE_SOURCE_UNVERIFIED]：普通消息列表证据不足；不转移操作权")
            recoverRetryablePrivateList("PRIVATE_HANDOFF_SOURCE_UNVERIFIED")
            return
        }
        if (!ensureAndroidRecoveryReady(snapshot, "PRIVATE_TO_GREETINGS_HANDOFF")) {
            recoverRetryablePrivateList("PRIVATE_HANDOFF_ANDROID_NOT_READY")
            return
        }
        // A partial traversal may never be handed off without its durable cursor.
        val repository = privateMessagesCheckpointRepository
        if ((handoff != AiReplyTaskYieldReason.TRAVERSAL_EXHAUSTED && repository == null) ||
            !runCatching { repository?.save(executor.checkpoint()) }.isSuccess
        ) {
            onLog("跨任务交接[PRIVATE_CHECKPOINT_NOT_SAVED]：未保存原遍历进度；不转移操作权")
            scheduleSessionListStabilityRetry("私聊交接检查点尚未保存")
            return
        }
        val start = claimAndBeginReceivedGreetingsTask(
            reason = "切换收到的招呼任务",
            begin = { receivedGreetingsTaskFlow.beginNavigationTask() }
        )
        if (start != ReceivedGreetingsTaskFlowController.StartResult.STARTED) {
            recoverRetryablePrivateList("GREETING_HANDOFF_${start.name}")
            return
        }
        privateToGreetingsHandoff = handoff
        privateSliceQuota.finishSlice()
        if (handoff == AiReplyTaskYieldReason.DEFERRED_WORK) privateRelocationRetryOwed = true
        incompleteUnreadRelocationWait = null
        sessionActionGeneration += 1L
        pageRecoveryVerificationGate.reset(GREETINGS_TO_PRIVATE_HANDOFF_GATE)
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        val ordinarySessionClickCount = privateMessagesTraversalTaskExecutor.sessionActionCount
        currentSessionKey = null
        currentSessionWindowKey = null
        currentSessionName = null
        currentSessionCandidate = null
        invalidateMessageReadTransaction()
        if (handoff == AiReplyTaskYieldReason.TRAVERSAL_EXHAUSTED) {
            privateMessagesTraversalTaskExecutor.sessionActionCount = 0
        }
        onLog(
            "AI Reply：切换收到的招呼自动回复任务；原因=$reason；" +
                "handoff=${handoff.name}；原私聊检查点已保留；" +
                "普通聊天会话点击次数=$ordinarySessionClickCount；采用头像身份、当前聊天 OCR 及独立草稿发送事务"
        )
        val accepted = receivedGreetingsTaskFlow.performInitialIdleStep()
        if (!accepted) {
            scheduleReceivedGreetingsRecoveryRetry(
                "首次入口导航步骤暂未完成；保留任务和导航状态，不尝试备用坐标"
            )
            return
        }
        scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
    }

    /**
     * One business admission for a recognized received-greetings list and its UI-operation
     * lease. The three evidence sources have already passed their independent page/recovery
     * verifier; none is itself permission to operate before the lease and adapter recheck.
     */
    private sealed interface ConfirmedGreetingsListEvidence {
        data class Observed(val arrival: ReceivedGreetingsPageArrivalStatus) : ConfirmedGreetingsListEvidence
        data object UnifiedRecoveryVerified : ConfirmedGreetingsListEvidence
        data object CrossTaskRecoveryVerified : ConfirmedGreetingsListEvidence
    }

    private fun startReceivedGreetingsTaskFromConfirmedPage(evidence: ConfirmedGreetingsListEvidence) {
        if (receivedGreetingsProbeOrdinaryInboxConfirmed) {
            receivedGreetingsProbeOrdinaryInboxConfirmed = false
            receivedGreetingsOwnershipRecheckPending = false
            receivedGreetingsProbePrivateRouteWon = true
            onLog(
                "页面操作权仲裁[PRIVATE_ROUTE_WINS]：新鲜快照匹配普通消息列表恢复路由；" +
                    "收到招呼识别结果不覆盖当前页面归属；继续私聊只读恢复"
            )
            if (startupReadinessCoordinator.resumeAfterPageRecovery()) return
            return
        }
        if (evidence is ConfirmedGreetingsListEvidence.Observed &&
            evidence.arrival != ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
        ) {
            onLog("GREETING_LIST_ADMISSION_REJECTED reason=LIST_NOT_CONFIRMED")
            return
        }
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive) return
        val start = claimAndBeginReceivedGreetingsTask(
            reason = "接管人工打开的收到招呼列表",
            begin = { receivedGreetingsTaskFlow.beginFromConfirmedList() }
        )
        if (start != ReceivedGreetingsTaskFlowController.StartResult.STARTED) {
            onLog("GREETING_LIST_ADMISSION_REJECTED reason=${start.name}；未启用列表遍历")
            if (pageSelectedStartup && evidence !is ConfirmedGreetingsListEvidence.CrossTaskRecoveryVerified) {
                receivedGreetingsPageProbePending = true
                scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            }
            return
        }
        // Lease acquisition, the adapter's fresh current-window proof, and lifecycle start
        // are all required; a page fingerprint alone is never a UI-operation authorization.
        if (!receivedGreetingsTaskActive || !ownsTask(RECEIVED_GREETINGS_TASK_ID)) {
            // A concurrent handoff may have transferred the lease elsewhere; never
            // release a new owner's UI capability when the greeting admission loses.
            if (ownsTask(RECEIVED_GREETINGS_TASK_ID)) {
                val rejectedLease = taskCoordinator?.currentLease()?.takeIf { it.taskId == RECEIVED_GREETINGS_TASK_ID }
                releaseTaskOwnership("收到招呼列表准入后任务未激活；回滚本任务租约")
                if (rejectedLease != null) onPageTaskAdmissionRolledBack(rejectedLease)
            }
            sessionManagerState = SessionManagerState.PAUSED
            onLog("GREETING_LIST_ADMISSION_REJECTED reason=LEASE_NOT_ACTIVE")
            return
        }
        val evidenceSource = when (evidence) {
            is ConfirmedGreetingsListEvidence.Observed -> "PAGE_OBSERVATION"
            ConfirmedGreetingsListEvidence.UnifiedRecoveryVerified -> "RECOVERY_VERIFIED"
            ConfirmedGreetingsListEvidence.CrossTaskRecoveryVerified -> "CROSS_TASK_VERIFIED"
        }
        onLog("GREETING_LIST_READY：列表识别/恢复证据、适配器当前窗口复核及独占任务租约均已确认；" +
            "source=$evidenceSource")
        // Manual takeover can interrupt an idle wake; keep its private intent, not
        // its old timer. On return the paired scheduler makes a fresh decision.
        pairWake?.let { wake ->
            privateToGreetingsHandoff = if (wake.mode == PairWakeMode.REFRESH_PRIVATE) {
                AiReplyTaskYieldReason.TRAVERSAL_EXHAUSTED
            } else AiReplyTaskYieldReason.DEFERRED_WORK
            resetPairWake()
        }
        sessionActionGeneration += 1L
        receivedGreetingsPageProbePending = false

        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        currentSessionKey = null
        currentSessionWindowKey = null
        currentSessionName = null
        currentSessionCandidate = null
        currentSessionReadCaptured = false
        invalidateMessageReadTransaction()

        receivedGreetingsRecoveryRetryAttempt = 0

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
     * One transaction for both automatic navigation and manually confirmed-list takeover.
     * Capability checks happen before the UI-operation lease is claimed; any post-claim start
     * failure rolls the lease back so a paused branch cannot block the next business task.
     */
    private fun claimAndBeginReceivedGreetingsTask(
        reason: String,
        begin: () -> ReceivedGreetingsTaskFlowController.StartResult
    ): ReceivedGreetingsTaskFlowController.StartResult {
        val capabilities = receivedGreetingsTaskFlow.workflowCapabilities()
        if (!capabilities.supported) {
            // The previous private-message task may still own the single UI slot.  A capability
            // rejection is terminal for this handoff; release that old lease instead of leaving
            // a paused task capable of blocking later normal-list re-entry.
            val rejectedSourceLease = taskCoordinator?.currentLease()
            releaseTaskOwnership("收到的招呼能力不支持；清理切换前的旧任务租约")
            if (rejectedSourceLease != null) onPageTaskAdmissionRolledBack(rejectedSourceLease)
            sessionManagerState = SessionManagerState.PAUSED
            onLog(
                "收到的招呼任务启动[NOT_SUPPORTED]：" +
                    "entry=${capabilities.entry};" +
                    "pageRecognition=${capabilities.pageRecognition};" +
                    "traversal=${capabilities.traversal}；未取得UI操作租约"
            )
            return ReceivedGreetingsTaskFlowController.StartResult.NOT_SUPPORTED
        }
        if (recoveryContext.pending || !claimTaskOwnership(RECEIVED_GREETINGS_TASK_ID, reason, verifiedHandoff = true)) {
            sessionManagerState = SessionManagerState.PAUSED
            onLog("收到的招呼任务启动[OWNERSHIP_REJECTED]：恢复门禁尚未解除或未取得唯一 UI 操作权；不执行启动动作")
            return ReceivedGreetingsTaskFlowController.StartResult.OWNERSHIP_REJECTED
        }
        val admittedLease = taskCoordinator?.currentLease()?.takeIf {
            it.taskId == RECEIVED_GREETINGS_TASK_ID && it.runGeneration == currentRunGeneration()
        }
        val result = runCatching { begin() }
            .getOrDefault(ReceivedGreetingsTaskFlowController.StartResult.START_FAILED)
        if (result != ReceivedGreetingsTaskFlowController.StartResult.STARTED) {
            // A synchronous failed admission may release only its own capability.
            // A newer task/lease is not the failed transaction's property.
            if (admittedLease == null || taskCoordinator?.isCurrent(admittedLease) == true) {
                if (ownsTask(RECEIVED_GREETINGS_TASK_ID)) {
                    releaseTaskOwnership("收到的招呼任务启动失败回滚：${result.name}")
                }
                if (admittedLease != null) onPageTaskAdmissionRolledBack(admittedLease)
            }
            sessionManagerState = SessionManagerState.PAUSED
            onLog("收到的招呼任务启动[${result.name}]：已回滚UI操作租约；不执行首次遍历和后续页面调度")
        }
        return result
    }

    /**
     * Traversal completion is a pending return, not a stopped task. Reuse the existing
     * lease-gated adapter BACK path; a submission result is not inbox confirmation.
     */
    private fun completionReturnSourceFingerprint(): String? =
        runCatching { chatPagePort.receivedGreetingsCompletionReturnSourceFingerprint() }
            .getOrNull()?.takeIf { it.isNotBlank() }

    private fun invalidateGreetingsCompletionReturnEvidence() {
        runCatching { chatPagePort.invalidateReceivedGreetingsCompletionReturnEvidence() }
    }

    private fun isGreetingsCompletionReturnRecovery(): Boolean =
        recoveryContext.pending && recoveryContext.sourceTaskId == RECEIVED_GREETINGS_TASK_ID &&
            recoveryContext.reason?.startsWith(GREETINGS_RETURN_RECOVERY_REASON) == true

    private fun holdGreetingsCompletionReturn(reason: String) {
        if (!sessionRunStarted || !awaitingNormalInboxAfterGreetingsReturn) return
        pageRecoveryVerificationGate.reset(GREETINGS_TO_PRIVATE_HANDOFF_GATE)
        invalidateGreetingsCompletionReturnEvidence()
        if (!recoveryContext.pending) {
            requestUnifiedPauseAndCheckpoint(RECEIVED_GREETINGS_TASK_ID, "$GREETINGS_RETURN_RECOVERY_REASON:$reason")
        }
        recoveryContext.invalidateFingerprint()
        sessionManagerState = SessionManagerState.PAUSED
        onLog("收到的招呼返回恢复[HOLD]：reason=$reason；保留完成状态及检查点，仅只读观察")
        scheduleReceivedGreetingsRecoveryRetry("返回事务等待新鲜页面证据：$reason")
    }

    /**
     * A return recovery is still the original greetings task, not a new list traversal.
     * Its allowed observations are the independently proven return source or final inbox.
     * Reuse the shared recovery context, reducer, scheduler and Android/lease gates.
     */
    private fun recoverGreetingsCompletionReturn(
        observation: ObservationResult,
        inspection: ChatPageSnapshot,
        matchedRouteId: String?
    ) {
        if (recoveryContext.runGeneration != currentRunGeneration() || userInteractionActive ||
            isSendConfirmationPending() || isConversationDraftPending() ||
            observation.status != ObservationStatus.CONFIRMED_PAGE ||
            inspection.packageName != targetPackage()
        ) {
            holdGreetingsCompletionReturn("RECOVERY_NOT_READY")
            return
        }
        val fingerprint = when {
            inspection.pageKind == ChatPageKind.CHAT_LIST &&
                matchedRouteId == messageListRecoveryRoute.routeId ->
                inspection.messageTabPositionKey()?.let { "inbox:$it" }
            inspection.pageKind == ChatPageKind.NON_CHAT ->
                completionReturnSourceFingerprint()?.let { "source:$it" }
            else -> null
        }
        if (fingerprint == null) {
            holdGreetingsCompletionReturn("RECOVERY_SOURCE_UNVERIFIED")
            return
        }
        if (!ensureAndroidRecoveryReady(inspection, "GREETINGS_COMPLETION_RETURN")) {
            invalidateGreetingsCompletionReturnEvidence()
            return
        }
        val route = receivedGreetingsRecoveryRoute.routeId
        if (!recoveryContext.observeTarget(route, fingerprint)) {
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
            onLog("收到的招呼返回恢复[VERIFYING]：第1/2次新鲜快照；禁止BACK")
            scheduleFullInspectionAfter(350L)
            return
        }
        val active = taskOwnership.activeTaskId()
        if ((active != null && active != RECEIVED_GREETINGS_TASK_ID) ||
            !ensureAndroidRecoveryReady(inspection, "GREETINGS_RETURN_BEFORE_LEASE") ||
            !claimTaskOwnership(RECEIVED_GREETINGS_TASK_ID, "招呼完成返回双快照已确认", verifiedHandoff = true) ||
            !ensureAndroidRecoveryReady(inspection, "GREETINGS_RETURN_BEFORE_RESUME")
        ) {
            holdGreetingsCompletionReturn("RECOVERY_LEASE_NOT_READY")
            return
        }
        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
        recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeRequested)
        if (recoveryTransaction.dispatch(RecoveryTransactionEvent.ResumeCompleted) !=
            com.freedom.features.recovery.RecoveryTransactionState.ACTIVE
        ) {
            holdGreetingsCompletionReturn("RECOVERY_ACK_PENDING")
            return
        }
        recoveryContext.markUiOwnershipTransferred()
        recoveryContext.clear()
        recoveryTransaction.reset()
        receivedGreetingsCheckpointForUnified = null
        receivedGreetingsRecoveryVerificationPending = false
        receivedGreetingsRecoveryVerifiedForNextSnapshot = false
        receivedGreetingsRecoveryRetryAttempt = 0
        pendingNavigationSourceResult = null
        unmatchedNavigationObservationPending = false
        lastPageInspection = inspection
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
        onLog("收到的招呼返回恢复[RESUMED]：Android READY、双快照、原任务租约已确认；下一次新鲜观察继续返回或确认到达")
        scheduleFullInspectionAfter(0L)
    }

    private fun requestGreetingsReturn(
        observation: ObservationResult,
        inspection: ChatPageSnapshot
    ) {
        if (!sessionRunStarted || !receivedGreetingsTaskActive) return
        if (!ownsTask(RECEIVED_GREETINGS_TASK_ID) || recoveryContext.pending) {
            scheduleReceivedGreetingsRecoveryRetry("返回事务仍需有效任务租约与恢复授权")
            return
        }
        if (userInteractionActive || isSendConfirmationPending() || isConversationDraftPending()) {
            scheduleReceivedGreetingsRecoveryRetry("返回事务仍有未决用户操作、发送或草稿")
            return
        }
        // Existing COMPLETE evidence requests the exhausted-work return. A previously
        // requested safe handoff retains its own reason; do not manufacture COMPLETE.
        if (!receivedGreetingsTaskFlow.awaitingNormalInboxAfterReturn) {
            receivedGreetingsTaskFlow.requestExhaustedReturn()
        }
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
        if (observation.status != ObservationStatus.CONFIRMED_PAGE ||
            inspection.packageName != targetPackage() ||
            inspection.pageKind == ChatPageKind.CHAT_LIST ||
            inspection.pageKind == ChatPageKind.UNKNOWN
        ) {
            holdGreetingsCompletionReturn("RETURN_PAGE_UNVERIFIED")
            return
        }
        onLog("收到的招呼任务[RETURN_PENDING]：安全返回意图已建立；保留任务及UI操作权；进入自动返回流程")
        when (val result = receivedGreetingsTaskFlow.returnForPriorityCheck()) {
            ReceivedGreetingsBackResult.BACK_SUBMITTED -> {
                receivedGreetingsTaskFlow.markReturnRequested()
                onLog("收到的招呼任务[BACK_SUBMITTED]：返回步骤已受理；等待普通消息列表独立确认")
                scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
            }
            ReceivedGreetingsBackResult.GESTURE_IN_FLIGHT,
            ReceivedGreetingsBackResult.WAITING_FOR_PAGE_CONFIRMATION,
            ReceivedGreetingsBackResult.REJECTED -> {
                onLog("收到的招呼任务[BACK_WAIT]：result=$result；未新增返回提交；保留任务现场")
                scheduleReceivedGreetingsRecoveryRetry("任务安全返回尚未确认：$result")
            }
            ReceivedGreetingsBackResult.SOURCE_UNVERIFIED,
            ReceivedGreetingsBackResult.OWNERSHIP_REJECTED -> {
                onLog("收到的招呼任务[BACK_WAIT]：result=$result；未新增返回提交；保留任务现场")
                holdGreetingsCompletionReturn(result.name)
            }
        }
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
            "；系统入口UNKNOWN=${unknownSystem}（保留原始徽标，不阻断私人聊天准入）" +
            "；系统入口边界READ=${systemRows.count {
                !it.rowClippedAtViewportEdge &&
                    ChatSessionManagementPolicy.privateBoundaryReadStatus(it) == ChatUnreadStatus.READ &&
                    ChatSessionManagementPolicy.isCompleteBoundaryEvidence(it, snapshot.chatListViewport)
            }}" +
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

    private fun scheduleSessionListStabilityRetry(
        reason: String,
        onInspectionDue: (() -> Unit)? = null
    ) {
        if ((sessionManagerState == SessionManagerState.PAUSED &&
                !messageListRecoveryVerificationPending) ||
            sessionManagerState == SessionManagerState.STOPPED
        ) return
        sessionTransitionTask?.cancel()
        messageRecoveryRetryAttempt += 1
        val attempt = messageRecoveryRetryAttempt
        val delayMs = RecoveryScheduler.readOnlyRetryDelayMs(attempt)
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
            onInspectionDue?.invoke()
            scheduleFullInspection()
        }
    }

    /**
     * A submitted tap is provisional. No-op navigation and page-route recovery must
     * not persist its selected key as a completed/attempted session. Preserve the
     * existing traversal checkpoint; only the attempted marker and tentative detail
     * binding are rolled back, before the unified read-only recovery is saved.
     */
    private fun rememberUnverifiedPrivateTap(selectedKey: String) {
        missedPrivateSessionTapCount = if (missedPrivateSessionTapKey == selectedKey) {
            missedPrivateSessionTapCount + 1
        } else {
            missedPrivateSessionTapKey = selectedKey
            1
        }
    }

    private fun rollbackUnverifiedPrivateSessionTap(reason: String): Int {
        if (sessionManagerState != SessionManagerState.OPENING_SESSION) return 0
        val selectedKey = currentSessionKey ?: return 0
        if (privateMessagesTraversalTaskExecutor.attemptedSessionKeys.remove(selectedKey)) {
            privateMessagesTraversalTaskExecutor.sessionActionCount =
                (privateMessagesTraversalTaskExecutor.sessionActionCount - 1).coerceAtLeast(0)
        }
        rememberUnverifiedPrivateTap(selectedKey)
        privateMessagesTraversalTaskExecutor.clearCurrentSession()
        expectedPrivateSessionDetailNavigation = null
        currentSessionBinding = null
        onLog("AI 会话点击[UNVERIFIED_TAP_ROLLBACK]：reason=$reason；" +
            "attempt=$missedPrivateSessionTapCount；cacheWrite=false；" +
            "action=RECOVER_FRESH_LIST_AND_REACQUIRE_ROW；已撤销未成功打开会话的尝试记录")
        return missedPrivateSessionTapCount
    }

    private fun attemptOpenSession(candidate: ChatListCandidateSnapshot) =
        attemptOpenSession(ChatSessionManagementPolicy.stableSessionKey(candidate), candidate.rowTarget.window, candidate.nameText)

    private fun attemptOpenSession(
        selectedKey: String,
        sourceWindow: com.freedom.core.accessibility.api.WindowRef,
        selectedName: String?
    ) {
        if (!isAiReplyActive() || !sessionRunStarted || !canPerformPrivateMessagesUiAction() ||
            privateMessagesTraversalTaskExecutor.isDeferredForCurrentRound(selectedKey)
        ) return
        if (waitForPrivateForegroundQuiet("会话行点击等待目标页面就绪")) return
        // An interrupted visit cannot lend an unearned slice receipt to a new row.
        if (!privateQuotaReturnReady) pendingPrivateQuotaReceipt = null
        currentSessionSendConfirmed = false
        confirmedPrivateReturnBackRetryCount = 0
        confirmedPrivateReturnAwaitingRecovery = false
        pageRecoveryVerificationGate.reset(CONFIRMED_PRIVATE_RETURN_DETAIL_GATE)

        // Persist only the business identity used for recovery re-anchoring. The next recovery
        // pass will reacquire the row and coordinates from a fresh page snapshot.
        privateMessagesTraversalTaskExecutor.rememberRecoveryAnchor(selectedKey, selectedName)
        privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())

        if (selectedKey == ChatSessionManagementPolicy.stableSessionKeyForTitle(selectedName)) {
            onLog("AI 会话身份：未确认平台 ID；使用本次访问临时上下文，不前置跳转资料采集；仍复核同名歧义、目标行及聊天标题")
        }

        val actionGeneration = sessionActionGeneration
        val attempt = privateMessagesTaskExecutor.openSession(
            selectedKey = selectedKey,
            sourceWindow = sourceWindow,
            blockedIdentityKeys = privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys,
            isCurrentTraversalSelection = { fresh, key ->
                val traversal = privateMessagesTraversalTaskExecutor
                actionGeneration == sessionActionGeneration &&
                    !traversal.isDeferredForCurrentRound(key) &&
                    (!traversal.lockedUnreadSetFinalized ||
                        traversal.phase != SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP ||
                        traversal.nextFreshReturnCandidate(
                            fresh, preferredRetryKey = missedPrivateSessionTapKey,
                            provisionalAttemptKey = key.takeIf {
                                sessionManagerState == SessionManagerState.OPENING_SESSION && currentSessionKey == it
                            }
                        )?.let { ChatSessionManagementPolicy.stableSessionKey(it) == key } == true)
            },
            isInterruptedReadRecovery = { fresh, row ->
                val traversal = privateMessagesTraversalTaskExecutor
                actionGeneration == sessionActionGeneration &&
                    traversal.phase == SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP &&
                    traversal.lockedUnreadSetFinalized && !traversal.scanContinuityRecoveryPending &&
                    traversal.canRecoverInterruptedRow(fresh, row)
            },
            onGestureDispatched = { requestId, uptime ->
                scheduler.schedule(0L) {
                    if (actionGeneration != sessionActionGeneration ||
                        activeSessionGestureRequestId != requestId ||
                        sessionManagerState != SessionManagerState.OPENING_SESSION
                    ) return@schedule
                    expectedPrivateSessionDetailNavigation = expectedPrivateSessionDetailNavigation?.copy(
                        startedAtUptimeMs = uptime
                    )
                    scheduleOpenedSessionVerification()
                    onLog("AI 会话点击[PLATFORM_DISPATCHED]：实际派发已确认，开始原有页面跳转复核计时")
                }
            }
        ) { requestId, snapshot, selectedKey, result ->
            scheduler.schedule(0L) {
                if (activeSessionGestureRequestId != requestId || actionGeneration != sessionActionGeneration) {
                    onLog(
                        "已忽略过期自主点击回调：commandId=$requestId；" +
                            "提交代次=$actionGeneration；当前代次=$sessionActionGeneration；结果=${result.status}"
                    )
                    return@schedule
                }
                activeSessionGestureRequestId = null
                handleSessionTapResult(result, snapshot, selectedKey, actionGeneration)
            }
        }

        when (attempt) {
            is PrivateMessagesTaskExecutor.Attempt.Rejected -> {
                when (attempt.reason) {
                    PrivateMessagesTaskExecutor.RejectionReason.OWNERSHIP_LOST -> {
                        onLog("AI 会话点击[OWNERSHIP_REJECTED]：任务租约已变化；不派发旧点击，转入检查点恢复")
                        recoverTransientPrivateGesture("GESTURE_TAP_OWNERSHIP_CHANGED")
                    }
                    else -> {
                        if (attempt.reason == PrivateMessagesTaskExecutor.RejectionReason.WINDOW_CONTEXT_CHANGED) {
                            onLog("AI 会话点击[WINDOW_CONTEXT_CHANGED]：重新确认窗口身份；转入统一恢复事务")
                            recoverTransientPrivateGesture("GESTURE_TAP_WINDOW_CHANGED")
                            return
                        }
                        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                        val message = when (attempt.reason) {
                            PrivateMessagesTaskExecutor.RejectionReason.PAGE_CHANGED ->
                                "AI 会话管理：点击前复核发现页面已变化；取消本次会话操作"
                            PrivateMessagesTaskExecutor.RejectionReason.AMBIGUOUS_SESSION_IDENTITY -> {
                                if (attempt.confirmedIdentityBlock) {
                                    privateMessagesTraversalTaskExecutor.ambiguousSessionIdentityKeys += attempt.selectedKey
                                    chatSessionManagementPort.blockSessionIdentityKey(attempt.selectedKey)
                                    privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
                                    "AI 会话管理[IDENTITY_CONFLICT]：目标身份已有阻止证据或存在不同同名行；cacheWrite=true；拒绝点击"
                                } else {
                                    "AI 会话管理[IDENTITY_RETRYABLE]：当前快照身份重复观察；cacheWrite=false；拒绝点击，等待新鲜行证据"
                                }
                            }
                            PrivateMessagesTaskExecutor.RejectionReason.SESSION_ROW_NOT_FOUND ->
                                "AI 会话管理：点击前未能重新匹配目标会话行；拒绝使用旧节点路径"
                            PrivateMessagesTaskExecutor.RejectionReason.UNREAD_ADMISSION_CHANGED ->
                                "AI 会话管理：点击前准入复核失败；仅允许明确未读且会话行、昵称、预览均完整可见的私人会话；" +
                                    "当前未读状态=${attempt.unreadStatus?.name ?: ChatUnreadStatus.UNKNOWN.name}；拒绝点击"
                            PrivateMessagesTaskExecutor.RejectionReason.TRAVERSAL_ORDER_CHANGED ->
                                "AI 会话遍历[ORDER_CHANGED]：最新视口的相邻未读目标已变化；不派发旧点击，重新按当前行顺序观察"
                            PrivateMessagesTaskExecutor.RejectionReason.UNSAFE_TAP_BOUNDS ->
                                "AI 会话管理：目标会话行复核失败或边界不安全；点击未派发、不消耗会话尝试次数，目标键=${attempt.selectedKey.take(12)}"
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
                                attemptOpenSession(selectedKey, sourceWindow, selectedName)
                            }
                        }
                        return
                    }
                    GestureSubmission.REJECTED -> {
                        if (activeSessionGestureRequestId == attempt.requestId) activeSessionGestureRequestId = null
                        currentSessionKey = null
                        currentSessionName = null
                        currentSessionCandidate = null
                        rememberUnverifiedPrivateTap(attempt.selectedKey)
                        onLog("AI 会话管理：系统拒绝会话点击派发；未确认目标聊天已打开，转入安全恢复")
                        recoverTransientPrivateGesture("GESTURE_TAP_SUBMISSION_REJECTED")
                        return
                    }
                    GestureSubmission.ACCEPTED -> {
                        // Store the exact just-revalidated row as one executor-owned session context.
                        privateMessagesTraversalTaskExecutor.beginSession(
                            candidate = attempt.candidate,
                            sessionKey = attempt.selectedKey,
                            windowKey = null
                        )
                        expectedPrivateSessionDetailNavigation = ExpectedPrivateSessionDetailNavigation(
                            sessionKey = attempt.selectedKey,
                            sessionName = attempt.candidate.nameText,
                            sourceWindowKey = listOf(
                                attempt.candidate.rowTarget.window.packageName,
                                attempt.candidate.rowTarget.window.windowId,
                                attempt.candidate.rowTarget.window.generation
                            ).joinToString("|"),
                            // Keep this business module independent from Android APIs while
                            // preserving the monotonic uptime meaning used by the navigation
                            // correlation record.
                            startedAtUptimeMs = System.nanoTime() / 1_000_000L
                        )
                        onLog(
                            "AI 会话导航事务：已登记预期详情导航；" +
                                "sessionKey=${attempt.selectedKey.take(12)}；" +
                                "sourceWindow=${attempt.candidate.rowTarget.window.windowId}@" +
                                "${attempt.candidate.rowTarget.window.generation}"
                        )
                        sessionManagerState = SessionManagerState.OPENING_SESSION
                        privateMessagesTraversalTaskExecutor.recordReturnViewportBeforeOpen(attempt.snapshot)
                        privateMessagesTraversalTaskExecutor.attemptedSessionKeys += attempt.selectedKey
                        privateMessagesTraversalTaskExecutor.sessionActionCount += 1
                        privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
                        readinessDeferredRetryCount = 0
                        readinessDeferredRetryLimitLogged = false
                    }
                }
            }
        }

        onLog(
            "AI 会话管理：已提交会话行点击；序号=${currentSessionCandidate?.rowIndex?.plus(1)}；" +
                "触摸落点由统一手势策略在安全区域内生成；等待验证页面跳转"
        )
        sessionTransitionTask?.cancel()
        sessionTransitionTask = null
        // ACCEPTED may mean FIFO/pre-delay only. The real dispatch callback owns
        // the verification timer; a completed terminal callback is the fallback.
    }

    private fun scheduleOpenedSessionVerification() {
        if (sessionTransitionTask != null || sessionManagerState != SessionManagerState.OPENING_SESSION) return
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
            onLog("AI 会话点击回调[STALE_CALLBACK_IGNORED]：私人消息任务租约已失效；不推进旧会话状态")
            recoverTransientPrivateGesture("PRIVATE_TAP_CALLBACK_LEASE_LOST")
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
            rollbackUnverifiedPrivateSessionTap("GESTURE_TAP_${result.status}")
            onLog("AI 会话管理：会话点击未完成（${result.status}）；reason=${result.reason}；保留原目标和遍历检查点，转入安全恢复")
            recoverTransientPrivateGesture("GESTURE_TAP_${result.status}")
            return
        }
        // The accessibility window event may arrive before or after this callback. The delayed
        // verification is intentionally independent and always reacquires the active page.
        if (!hasSameTargetPackage(snapshot)) {
            onLog("AI 会话管理已暂停：点击完成后目标窗口身份不一致")
            recoverTransientPrivateGesture("PRIVATE_TAP_CALLBACK_WINDOW_CHANGED")
        } else {
            startupReadinessCoordinator.markInitializationComplete("首个自主会话点击已由 Android 完成")
            scheduleOpenedSessionVerification()
        }
    }

    private fun verifyOpenedSession() {
        sessionTransitionTask = null
        if (!isAiReplyActive() || !sessionRunStarted ||
            sessionManagerState != SessionManagerState.OPENING_SESSION
        ) return
        if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
            onLog("AI 会话打开复核被阻止：私人消息任务租约已失效；不处理旧页面状态")
            recoverTransientPrivateGesture("PRIVATE_OPEN_VERIFY_LEASE_LOST")
            return
        }
        val current = runCatching { readPageSnapshot(AiReplyPageReadPurpose.ACTIVITY_TRANSITION) }.getOrNull()
        if (current?.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            current.packageName == targetPackage()
        ) {
            if (!isExpectedPrivateSessionDetail(current)) {
                rejectChatHeaderMismatch(current, "延迟页面预期会话身份复核")
                return
            }
            val headerMatchMode = currentSessionHeaderMatchMode(current)
            if (headerMatchMode == null) {
                rejectChatHeaderMismatch(current, "延迟页面复核")
                return
            }
            sessionManagerState = SessionManagerState.READING_SESSION
            currentSessionWindowKey = windowSessionKey(current)
            establishCurrentSessionBinding(current)
            onLog(
                "AI 会话管理：页面跳转及聊天页顶部名称验证通过；匹配方式=${headerMatchMode.name}；" +
                    "开始只读采集"
            )
            readVisibleMessages(current)
            return
        }
        if (current?.pageKind == ChatPageKind.CHAT_LIST &&
            current.packageName == targetPackage()
        ) {
            val retryAttempt = rollbackUnverifiedPrivateSessionTap("TAP_COMPLETED_BUT_DETAIL_NOT_OPENED")
            val delayMs = RecoveryScheduler.readOnlyRetryDelayMs(retryAttempt.coerceAtLeast(1))
            onLog("AI 会话点击[NO_NAVIGATION_RECOVERY]：已提交点击但未进入聊天；" +
                "attempt=$retryAttempt；delayMs=$delayMs；保留未读候选；" +
                "先恢复双快照列表再重新定位，不使用旧节点或旧坐标")
            recoverTransientPrivateGesture("GESTURE_TAP_NO_NAVIGATION", delayMs)
        } else {
            val retryAttempt = rollbackUnverifiedPrivateSessionTap("TAP_DESTINATION_UNVERIFIED")
            onLog("AI 会话点击[DESTINATION_UNVERIFIED]：未确认目标聊天；" +
                "page=${current?.pageKind?.name ?: "NO_SNAPSHOT"}；attempt=$retryAttempt；" +
                "保留检查点，等待合法私聊列表页面后再尝试")
            recoverTransientPrivateGesture(
                "GESTURE_TAP_DESTINATION_UNVERIFIED",
                RecoveryScheduler.readOnlyRetryDelayMs(retryAttempt.coerceAtLeast(1))
            )
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
        if (!canPerformPrivateMessagesUiAction()) {
            onLog("AI 会话安全返回被阻止：私人消息任务未持有 UI 操作权")
            recoverRetryablePrivateList("PRIVATE_REJECTED_RETURN_LEASE_LOST")
            return
        }
        val rejectedWindowKey = windowSessionKey(snapshot)
        if (snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
            snapshot.packageName != targetPackage() || rejectedWindowKey == null || currentSessionKey == null
        ) {
            onLog("AI 会话管理已暂停：身份不匹配后无法确认当前聊天窗口；不执行返回或继续点击")
            recoverRetryablePrivateList("PRIVATE_REJECTED_RETURN_WINDOW_UNVERIFIED")
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
            if (!canPerformPrivateMessagesUiAction()) {
                onLog("AI 会话安全返回[STALE_TASK_IGNORED]：私人消息任务租约或恢复门禁已失效；不提交返回动作")
                recoverRetryablePrivateList("PRIVATE_REJECTED_RETURN_LEASE_CHANGED")
                return@rejectedReturn
            }

            val current = runCatching { readPageSnapshot(AiReplyPageReadPurpose.RETURN_NAVIGATION_VERIFY) }.getOrNull()
            if (current == null || current.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
                current.packageName != targetPackage() || windowSessionKey(current) != rejectedWindowKey
            ) {
                onLog("AI 会话管理已暂停：身份不匹配后的返回前复核失败；不执行全局返回")
                recoverRetryablePrivateList("PRIVATE_REJECTED_RETURN_PRECHECK_UNVERIFIED")
                return@rejectedReturn
            }

            val accepted = runCatching {
                chatSessionManagementPort.navigateBackToSessionList()
            }.getOrDefault(false)
            if (!accepted) {
                onLog("AI 会话管理已暂停：身份不匹配后系统未接受返回动作；不尝试其它会话")
                recoverRetryablePrivateList("PRIVATE_REJECTED_RETURN_BACK_REJECTED")
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
                    onLog("AI 会话管理已暂停：身份不匹配后的返回未能确认会话列表；不继续点击")
                    recoverRetryablePrivateList("PRIVATE_REJECTED_RETURN_LIST_UNVERIFIED")
                }
            }
        }
    }

    private fun currentSessionHeaderMatchMode(snapshot: ChatPageSnapshot) =
        ChatSessionManagementPolicy.matchChatHeader(currentSessionName, snapshot.chatTitleText)

    /**
     * Message-business identity gate. A generic self-navigation result only proves that
     * Freedom caused a transition; it does not prove that the resulting detail page belongs
     * to the selected conversation. Keep that decision entirely in the message layer.
     */
    private fun isExpectedPrivateSessionDetail(snapshot: ChatPageSnapshot): Boolean {
        if (currentSessionBinding?.observedBusinessAdmitted == true) {
            return observedPrivateBindingMatches(snapshot)
        }
        val selected = currentSessionCandidate ?: return false
        val selectedKey = ChatSessionManagementPolicy.stableSessionKey(selected)
        val currentKey = currentSessionKey ?: return false
        val expected = expectedPrivateSessionDetailNavigation
        // The business identity gate remains valid for restored/test-created business state
        // where no navigation ticket exists. When a ticket is present, its session key is an
        // additional consistency check, not the identity proof itself.
        val sameTargetSession = currentKey == selectedKey &&
            (expected == null || expected.sessionKey == selectedKey)
        if (!sameTargetSession) {
            onLog(
                "AI 会话身份校验失败：详情页不属于当前预期会话；" +
                    "expected=${expected?.sessionKey?.take(12) ?: "NONE"}；selected=${selectedKey.take(12)}；" +
                    "current=${currentKey.take(12)}；不进入历史上下文或 OCR"
            )
        }
        return sameTargetSession
    }

    private fun normalizeSessionTitle(value: String?): String =
        ChatSessionManagementPolicy.normalizeSessionTitle(value)

    /**
     * A confirmed private send or durably parked pre-send read owns BACK recovery until
     * a fresh inbox is recognized. Submission is not navigation success; a no-op/rejected
     * BACK may only be retried after two independent same-window, same-session snapshots.
     * UNKNOWN or foreign pages use the existing durable list-checkpoint recovery instead.
     */
    private fun scheduleConfirmedPrivateReturnVerification(
        reason: String,
        delayMs: Long = SESSION_RETURN_VERIFY_DELAY_MS
    ) {
        sessionTransitionTask?.cancel()
        sessionTransitionTask = scheduler.schedule(delayMs) {
            sessionTransitionTask = null
            verifyConfirmedPrivateReturn(reason)
        }
    }

    private fun verifyConfirmedPrivateReturn(reason: String) {
        val deferredRead = deferredPrivateReadReturnVisit == draftVisitToken
        if (!isAiReplyActive() || !sessionRunStarted || receivedGreetingsTaskActive ||
            sessionManagerState != SessionManagerState.RETURNING_TO_SESSION_LIST ||
            (!currentSessionSendConfirmed && !deferredRead) || recoveryContext.pending
        ) return
        val returnLog = if (deferredRead) "AI 私聊延期返回" else "AI 私聊发送返回"
        if (!canPerformPrivateMessagesUiAction()) {
            pageRecoveryVerificationGate.reset(CONFIRMED_PRIVATE_RETURN_DETAIL_GATE)
            onLog("$returnLog[LEASE_NOT_HELD]：原任务失去操作权；进入检查点恢复，不派发 BACK")
            recoverRetryablePrivateList(if (deferredRead) "PRIVATE_READ_RETURN_LEASE_NOT_HELD" else "PRIVATE_SEND_RETURN_LEASE_NOT_HELD")
            return
        }

        // A fresh page read is mandatory after the last BACK. Never treat the gesture
        // submission result or a cached source snapshot as proof of successful navigation.
        val observed = inspectFreshPageSnapshot()
        if (observed?.pageKind == ChatPageKind.CHAT_LIST &&
            observed.packageName == targetPackage() && !isSemanticallyUnconfirmed(observed)
        ) {
            pageRecoveryVerificationGate.reset(CONFIRMED_PRIVATE_RETURN_DETAIL_GATE)
            onLog("$returnLog[INBOX_VERIFIED]：Back 后重新获取到私聊列表；恢复原遍历游标")
            if (currentSessionBinding?.observedBusinessAdmitted == true) {
                requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, "OBSERVED_DETAIL_RETURN")
                scheduleFullInspectionAfter(0L)
            } else handleSessionListPage(observed)
            return
        }

        val stillOriginalDetail = observed != null &&
            observed.packageName == targetPackage() &&
            observed.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            currentSessionWindowKey != null &&
            currentSessionWindowKey == windowSessionKey(observed) &&
            hasBoundPrivateDetailRecoverySession() &&
            isExpectedPrivateSessionDetail(observed) &&
            currentSessionHeaderMatchMode(observed) != null &&
            !isSemanticallyUnconfirmed(observed)
        if (!stillOriginalDetail || observed == null) {
            pageRecoveryVerificationGate.reset(CONFIRMED_PRIVATE_RETURN_DETAIL_GATE)
            onLog("$returnLog[PAGE_UNVERIFIED]：reason=$reason；" +
                "page=${observed?.pageKind?.name ?: "NO_SNAPSHOT"}；停止额外 BACK；转入原消息列表检查点恢复")
            recoverRetryablePrivateList(if (deferredRead) "PRIVATE_READ_RETURN_PAGE_UNVERIFIED" else "PRIVATE_SEND_RETURN_PAGE_UNVERIFIED")
            return
        }

        if (userInteractionActive || isSendConfirmationPending() ||
            runCatching { isConversationDraftPending() }.getOrDefault(true)
        ) {
            pageRecoveryVerificationGate.reset(CONFIRMED_PRIVATE_RETURN_DETAIL_GATE)
            onLog("$returnLog[TRANSACTION_WAIT]：仍有用户操作或发送事务待核验；只读等待，不重新 BACK")
            scheduleConfirmedPrivateReturnVerification(
                "TRANSACTION_WAIT", RecoveryScheduler.readOnlyRetryDelayMs(1)
            )
            return
        }

        if (!pageRecoveryVerificationGate.observe(
                CONFIRMED_PRIVATE_RETURN_DETAIL_GATE, recoveryStructuralKey(observed)
            )
        ) {
            onLog("$returnLog[DETAIL_STABILITY_WAIT]：第" +
                "${pageRecoveryVerificationGate.stableSamples(CONFIRMED_PRIVATE_RETURN_DETAIL_GATE)}/2次原会话新鲜快照；不直接重试 BACK")
            scheduleConfirmedPrivateReturnVerification("DETAIL_STABILITY_WAIT", 350L)
            return
        }

        val readiness = runCatching { inspectAndroidRecoveryReadiness(observed) }.getOrNull()
        val admission = AndroidRecoveryExecutionGate.assess(
            snapshot = observed, expectedPackage = targetPackage(), evidence = readiness
        )
        if (!admission.allowed || !canPerformPrivateMessagesUiAction() ||
            (deferredRead && !privateReadDeferralPageReady(observed, returning = true)) ||
            isSendConfirmationPending() ||
            runCatching { isConversationDraftPending() }.getOrDefault(true)
        ) {
            pageRecoveryVerificationGate.reset(CONFIRMED_PRIVATE_RETURN_DETAIL_GATE)
            onLog("$returnLog[RETRY_DEFERRED]：Android、操作权或发送状态尚未就绪；只读复核，不派发 BACK")
            scheduleConfirmedPrivateReturnVerification(
                "RETRY_DEFERRED", RecoveryScheduler.readOnlyRetryDelayMs(1)
            )
            return
        }

        // No old coordinates or node paths are reused. The adapter keeps ownership of
        // the same global BACK operation, including its existing platform safety gates.
        pageRecoveryVerificationGate.reset(CONFIRMED_PRIVATE_RETURN_DETAIL_GATE)
        confirmedPrivateReturnBackRetryCount += 1
        val accepted = runCatching {
            chatSessionManagementPort.navigateBackToSessionList()
        }.getOrDefault(false)
        onLog(
            if (accepted) "$returnLog[BACK_RETRY_ACCEPTED]：原会话双快照已验证；等待列表新快照"
            else "$returnLog[BACK_RETRY_REJECTED]：适配器未接受本次 BACK；保留业务检查点并继续只读复核"
        )
        // Keep the existing 900ms post-BACK verification floor; apply unbounded recovery
        // backoff after repeated failures instead of rapidly dispatching global BACK.
        val delayMs = maxOf(
            SESSION_RETURN_VERIFY_DELAY_MS,
            RecoveryScheduler.readOnlyRetryDelayMs(confirmedPrivateReturnBackRetryCount)
        )
        scheduleConfirmedPrivateReturnVerification("BACK_RETRY", delayMs)
    }

    private fun completePrivateBusinessObservation(reason: String) {
        if (privateMessagesTraversalTaskExecutor.completeCurrentUnfinishedSession()) {
            privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
            onLog("AI 私聊业务[TERMINAL_CONFIRMED]：reason=$reason；已清除本次未完成记录")
        }
    }

    private fun scheduleReturnToSessionList(
        snapshot: ChatPageSnapshot,
        draftPendingWaitedMs: Long = 0L
    ) {
        if (sessionManagerState != SessionManagerState.READING_SESSION) return
        if (isPrivateFollowUpActive() || pendingPrivateFollowUpEnabled(verifiedPrivateLedgerContactKey())) {
            if (!isPrivateFollowUpActive() && currentSessionSendConfirmed) {
                scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
            }
            return
        }
        if (!canPerformPrivateMessagesUiAction()) {
            onLog("AI 会话返回被阻止：私人消息任务未持有 UI 操作权")
            recoverRetryablePrivateList("PRIVATE_RETURN_LEASE_LOST")
            return
        }
        if (privateModelFailureRecovery != null || privateModelRetryAwaitingDraft) {
            sessionTransitionTask?.cancel()
            sessionTransitionTask = null
            onLog("AI 私聊模型恢复[RETURN_HELD]：发送前模型失败正在复核；不允许提前 BACK")
            return
        }
        val draftPending = runCatching { isConversationDraftPending() }.getOrDefault(true)
        if (draftPending) {
            if (draftPendingWaitedMs >= MAX_DRAFT_PENDING_WAIT_MS &&
                !runCatching { isSendControlRecoveryPending() }.getOrDefault(false)
            ) {
                // The timed-out draft is not a send result. Continue low-frequency
                // read-only status checks without releasing the original chat or lease.
                sessionTransitionTask?.cancel()
                onLog("AI 私聊暂停分类[RETRYABLE_DRAFT_WAIT]：草稿等待超过快速轮询上限；保持原聊天，不执行返回")
                sessionTransitionTask = scheduler.schedule(RecoveryScheduler.longWaitDelayMs) {
                    sessionTransitionTask = null
                    if (!isAiReplyActive() || !sessionRunStarted ||
                        sessionManagerState != SessionManagerState.READING_SESSION ||
                        !canPerformPrivateMessagesUiAction()
                    ) return@schedule
                    scheduleReturnToSessionList(snapshot, draftPendingWaitedMs)
                }
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
            if (!canPerformPrivateMessagesUiAction()) {
                onLog("AI 会话返回[STALE_TASK_IGNORED]：私人消息任务租约或恢复门禁已失效；不提交返回动作")
                recoverRetryablePrivateList("PRIVATE_RETURN_LEASE_CHANGED")
                return@schedule
            }
            // A later child/checkpoint obligation must veto the delayed BACK too.
            if (isPrivateFollowUpActive() || pendingPrivateFollowUpEnabled(verifiedPrivateLedgerContactKey())) {
                if (!isPrivateFollowUpActive() && currentSessionSendConfirmed) {
                    scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
                }
                return@schedule
            }
            // A model request or technical pre-send failure can arrive after scheduling.
            // Neither can authorize BACK on an obsolete OCR observation.
            if (privateModelFailureRecovery != null || privateModelRetryAwaitingDraft) {
                onLog("AI 私聊模型恢复[RETURN_HELD]：派发前发现模型恢复事务；保留聊天页")
                return@schedule
            }
            if (runCatching { isConversationDraftPending() }.getOrDefault(true)) {
                onLog("AI 会话返回门禁[RECHECK_DRAFT_PENDING]：返回派发前发现草稿或发送事务未完成；重新等待")
                scheduleReturnToSessionList(snapshot, draftPendingWaitedMs + SESSION_RETURN_DELAY_MS)
                return@schedule
            }
            val current = runCatching { readPageSnapshot(AiReplyPageReadPurpose.RETURN_NAVIGATION_VERIFY) }.getOrNull()
            val observedVisit = currentSessionBinding?.observedBusinessAdmitted == true
            if (observedVisit && (current == null || !observedPrivateDetailVerified(current) ||
                    !AndroidRecoveryExecutionGate.assess(current, targetPackage(),
                        runCatching { inspectAndroidRecoveryReadiness(current) }.getOrNull()).allowed ||
                    isSendConfirmationPending() || isSendControlRecoveryPending()
                )) {
                requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, "OBSERVED_DETAIL_RETURN_UNVERIFIED")
                scheduleFullInspectionAfter(0L)
                return@schedule
            }
            if (current == null || current.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
                current.packageName != snapshot.packageName || !hasSameWindowSession(snapshot, current)
            ) {
                onLog("AI 会话管理已暂停：返回前聊天窗口身份已变化；不执行全局返回")
                recoverRetryablePrivateList("PRIVATE_RETURN_DETAIL_UNVERIFIED")
                return@schedule
            }
            if (deferredPrivateReadReturnVisit == draftVisitToken) {
                if (!privateReadDeferralPageReady(current)) {
                    scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
                    return@schedule
                }
            } else if (currentSessionWindowKey?.let { privateNoReplyCompleted(draftVisitToken, it) } == true) {
                completePrivateBusinessObservation("MODEL_NO_REPLY")
            }
            sessionManagerState = SessionManagerState.RETURNING_TO_SESSION_LIST
            val accepted = runCatching {
                chatSessionManagementPort.navigateBackToSessionList()
            }.getOrDefault(false)
            if (deferredPrivateReadReturnVisit == draftVisitToken) {
                onLog("AI 私聊延期返回[BACK_SUBMITTED]：accepted=$accepted；等待独立列表验证；不重新 OCR")
                scheduleConfirmedPrivateReturnVerification(
                    if (accepted) "READ_BACK_SUBMITTED" else "READ_BACK_REJECTED"
                )
                return@schedule
            }
            if (currentSessionSendConfirmed) {
                if (!accepted) {
                    onLog("AI 私聊发送返回[INITIAL_BACK_REJECTED]：系统未接受 BACK；保留发送检查点并重新确认页面")
                } else {
                    onLog("AI 会话管理：确认发送后已提交系统返回动作；等待独立页面验证")
                }
                // A rejected BACK may already have caused a transition; first observe the
                // fresh route, then decide whether the original detail permits a safe retry.
                scheduleConfirmedPrivateReturnVerification(
                    if (accepted) "BACK_SUBMITTED" else "BACK_REJECTED"
                )
                return@schedule
            }
            if (!accepted) {
                onLog("AI 会话管理已暂停：系统未接受返回会话列表动作")
                recoverRetryablePrivateList("PRIVATE_RETURN_BACK_REJECTED")
                return@schedule
            }
            onLog("AI 会话管理：消息采集完成，已提交系统返回动作；等待会话列表页面验证")
            if (observedVisit) {
                // Reuse the unified list checkpoint verifier, not direct list admission.
                requestUnifiedPauseAndCheckpoint(PRIVATE_MESSAGES_TASK_ID, "OBSERVED_DETAIL_RETURN")
                scheduleFullInspectionAfter(SESSION_RETURN_VERIFY_DELAY_MS)
                return@schedule
            }
            sessionTransitionTask = scheduler.schedule(SESSION_RETURN_VERIFY_DELAY_MS) {
                sessionTransitionTask = null
                val returned = inspectFreshPageSnapshot()
                if (returned?.pageKind == ChatPageKind.CHAT_LIST && returned.packageName == targetPackage()) {
                    handleSessionListPage(returned)
                } else {
                    onLog("AI 会话管理已暂停：返回动作后未确认会话列表；不执行下一次点击")
                    recoverRetryablePrivateList("PRIVATE_RETURN_LIST_UNVERIFIED")
                }
            }
        }
    }

    private fun scrollSessionList(
        snapshot: ChatPageSnapshot,
        toTop: Boolean = false
    ) {
        if (!isAiReplyActive() || !sessionRunStarted || !canPerformPrivateMessagesUiAction()) return
        if (waitForPrivateForegroundQuiet("列表滚动等待目标页面就绪")) return
        // Reacquire the active page immediately before scrolling. A previous list snapshot can
        // become stale after RecyclerView relayout, window changes, or user interaction.
        val inspectionResult = runCatching { readPageSnapshot(AiReplyPageReadPurpose.SCROLL_BEFORE) }
        val rawFresh = inspectionResult.getOrNull()
        val fresh = rawFresh?.let(::stabilizeIncomingPageSnapshot) ?: run {
            val error = inspectionResult.exceptionOrNull()
            val reason = if (error == null) "INSPECTION_RETURNED_NO_SNAPSHOT"
            else "INSPECTION_EXCEPTION:${error.javaClass.simpleName}"
            onLog(
                "AI 会话管理已暂停：滚动前页面身份复核失败；原因=$reason；" +
                    "不会使用旧视口坐标"
            )
            // A missing fresh pre-scroll page is not a terminal business pause. The
            // existing recovery transaction owns checkpointing, read-only retries and
            // the two-snapshot/lease gate before any future action.
            recoverRetryablePrivateList("MESSAGE_LIST_PRE_SCROLL_SNAPSHOT_UNAVAILABLE")
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
        val sameWindowTransientUnknown = isSemanticallyUnconfirmed(fresh) &&
            fresh.packageName == snapshot.packageName &&
            fresh.packageName == targetPackage() &&
            fresh.hasSameWindowIdentity(snapshot) &&
            snapshot.windowGeneration > 0L &&
            fresh.windowGeneration == snapshot.windowGeneration
        if (sameWindowTransientUnknown) {
            recoveryTransaction.dispatch(RecoveryTransactionEvent.UnknownPage("MESSAGE_LIST_PRE_SCROLL_UNKNOWN"))
            recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
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
            onLog(
                "AI 会话管理已暂停：滚动前页面身份复核失败；原因=$identityFailureReason；" +
                    "目标包名匹配=${fresh.packageName == targetPackage()}；" +
                    "页面类型=${fresh.pageKind.name}；" +
                    "窗口身份匹配=${fresh.hasSameWindowIdentity(snapshot)}；" +
                    "节点扫描数=${fresh.scannedNodeCount}；" +
                    "列表候选数=${fresh.chatListCandidates.size}；" +
                    "不会使用旧视口坐标"
            )
            // Another foreground app or a changed window must revoke the old
            // scroll attempt, but not strand the retained private-message task.
            // Recovery will re-acquire all page evidence after the user returns.
            recoverRetryablePrivateList("MESSAGE_LIST_PRE_SCROLL_PAGE_IDENTITY_MISMATCH")
            return
        }
        // The pre-scroll read is independent; a manual jump between selection and
        // submission must not silently redefine the scan origin.
        if (toTop && !receivedGreetingsTaskActive &&
            privateMessagesTraversalTaskExecutor.reconcileUnrequestedViewportChange(
                hadPendingScrollBaseline = false,
                wasWaitingForUnread = false,
                visibleKeys = privateMessagesTraversalTaskExecutor.visibleSessionKeys(fresh),
                snapshot = fresh
            )
        ) {
            messageListCheckpointRestored = true
            recoverRetryablePrivateList("PRIVATE_LIST_POSITION_UNVERIFIED")
            return
        }
        // The independent read can reveal a newly clipped/unknown row, or prove a
        // previously pending row READ. Retain that evidence before any return swipe.
        val returnViewportRefreshed = toTop && !receivedGreetingsTaskActive &&
            privateMessagesTraversalTaskExecutor.refreshReturnViewportWork(fresh)
        if (returnViewportRefreshed) {
            privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
        }
        // Row-scoped continuation is not a cached scroll permit. If the independently
        // complete evidence disappears before dispatch, retain the original fail-closed path.
        if (privateMessagesTraversalTaskExecutor.unresolvedIncompleteUnreadKeys.isNotEmpty()) {
            // Preserve new anomaly evidence from the independent pre-scroll read as well.
            val stillPending = if (returnViewportRefreshed) true else {
                privateMessagesTraversalTaskExecutor.observeIncompleteUnreadEvidence(fresh).also {
                    privateMessagesCheckpointRepository?.save(privateMessagesTraversalTaskExecutor.checkpoint())
                }
            }
            if (stillPending && !privateMessagesTraversalTaskExecutor.canContinueWithIncompleteUnread(fresh)) {
                onLog("AI 私聊异常会话[PRE_SCROLL_EVIDENCE_LOST]：滚动前新快照缺少独立完整行证据；不使用旧视口")
                recoverRetryablePrivateList("PRIVATE_INCOMPLETE_UNREAD_ROW")
                return
            }
        }
        if (toTop && !receivedGreetingsTaskActive &&
            privateMessagesTraversalTaskExecutor.phase == SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP &&
            privateMessagesTraversalTaskExecutor.lockedUnreadSetFinalized
        ) {
            val traversal = privateMessagesTraversalTaskExecutor
            if (fresh.chatListExtractionTruncated || fresh.windowIdentityKey() == null ||
                traversal.hasPendingVisibleReturnWork(fresh)
            ) {
                // This fresh pre-scroll read may expose rows absent from the old
                // observation. Reenter the normal candidate path, not a new scan.
                sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                onLog("AI 会话遍历[PRE_SCROLL_PENDING]：当前新鲜视口仍有待处理或待复核行；保留已读边界，不直接滑过")
                if (traversal.nextFreshReturnCandidate(fresh) != null) scheduleFullInspection()
                else scheduleSessionListStabilityRetry("当前视口未读候选尚未解决")
                return
            }
        }
        if (toTop && !receivedGreetingsTaskActive &&
            privateMessagesTraversalTaskExecutor.collectionBoundaryEvidence(fresh, toTop = true) == true
        ) {
            val keys = privateMessagesTraversalTaskExecutor.visibleSessionKeys(fresh)
            if (keys.isNotEmpty()) {
                beginBoundaryStabilityConfirmation(keys, toTop = true, snapshot = fresh)
                return
            }
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
            onLog("AI 会话管理已暂停：列表滚动坐标缺少匹配的窗口/显示上下文；不使用旧坐标")
            recoverRetryablePrivateList("PRIVATE_SCROLL_CONTEXT_UNAVAILABLE")
            return
        }
        val request = GestureIntentRequest(
            id = "momo-session-scroll-${java.util.UUID.randomUUID()}",
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
                else ->
                    "AI 会话管理：向下搜索未读区域边界，在列表视口内向上滑动到下一个区域"
            },
            executionContext = executionContext
        )
        if (!canPerformPrivateMessagesUiAction()) {
            onLog("AI 会话滚动[OWNERSHIP_REJECTED]：构造手势后任务租约或恢复门禁已变化；拒绝派发旧滑动")
            recoverRetryablePrivateList("PRIVATE_SCROLL_LEASE_LOST")
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
        // Collection positions measure progress independently of exact coordinates or child indexes.
        // Both sides retain the existing complete-row evidence for missing collection metadata.
        privateMessagesTraversalTaskExecutor.pendingScrollBaseline = privateMessagesTraversalTaskExecutor.scrollPositionKeys(fresh)
        privateMessagesTraversalTaskExecutor.pendingScrollToTop = toTop
        sessionManagerState = SessionManagerState.SCROLLING_SESSION_LIST
        val actionGeneration = sessionActionGeneration
        activeSessionGestureRequestId = request.id
        val submission = runCatching {
            gesturePolicy.execute(request) { result ->
                scheduler.schedule(0L) {
                    if (actionGeneration != sessionActionGeneration ||
                        activeSessionGestureRequestId != request.id
                    ) {
                        onLog(
                            "已忽略过期自主滑动回调：commandId=${request.id}；" +
                                "提交代次=$actionGeneration；当前代次=$sessionActionGeneration；结果=${result.status}"
                        )
                        return@schedule
                    }
                    activeSessionGestureRequestId = null
                    if (!ownsTask(PRIVATE_MESSAGES_TASK_ID)) {
                        onLog("AI 会话滚动回调[STALE_TASK_IGNORED]：私人消息任务租约已失效；不推进列表状态")
                        recoverTransientPrivateGesture("PRIVATE_SCROLL_CALLBACK_LEASE_LOST")
                        return@schedule
                    }
                    if (sessionManagerState != SessionManagerState.SCROLLING_SESSION_LIST) return@schedule
                    when (result.status) {
                        GestureExecutionStatus.COMPLETED -> {
                            // A normal completed swipe is movement evidence, never an admission gate.
                            privateMessagesTraversalTaskExecutor.cancelledScrollRecoveryCount = 0
                            readinessDeferredRetryCount = 0
                            readinessDeferredRetryLimitLogged = false
                            startupReadinessCoordinator.markInitializationComplete("首个自主列表滑动已由 Android 完成")
                            sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
                            onLog(
                                when {
                                    toTop && !privateMessagesTraversalTaskExecutor.initialInboxTopPositioningCompleted -> "AI Reply 启动顶部定位：已完成一次向下滑动；等待新快照确认顶部位置"
                                    toTop -> "AI 会话管理：已完成一次向上审视方向的列表滑动；等待新快照位置复核"
                                    else -> "AI 会话管理：已完成一次向下搜索方向的列表滑动；等待新快照确认位置"
                                }
                            )
                            scheduleFullInspectionAfter(
                                fullInspectionDebounceMs,
                                AiReplyPageReadPurpose.SCROLL_AFTER
                            )
                        }
                        GestureExecutionStatus.CANCELLED -> {
                            // A cancelled swipe proves neither page movement nor list boundary.
                            // Preserve the traversal checkpoint and require the same READY +
                            // two-fresh-snapshot transaction used by reconnect and UNKNOWN.
                            privateMessagesTraversalTaskExecutor.cancelledScrollRecoveryCount += 1
                            onLog(
                                "AI 会话管理[SCROLL_CANCELLED]：次数=${privateMessagesTraversalTaskExecutor.cancelledScrollRecoveryCount}；" +
                                    "不再使用硬重试上限，不假定列表已经移动"
                            )
                            recoverTransientPrivateGesture("GESTURE_SCROLL_CANCELLED", SCROLL_CANCEL_RECOVERY_DELAY_MS)
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
                            onLog("AI 会话管理：列表滚动执行失败（${result.status}）；不判定遍历完成，转入只读恢复")
                            recoverTransientPrivateGesture("GESTURE_SCROLL_${result.status}")
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
                onLog("AI 会话管理：系统拒绝列表滚动派发；尚未验证列表位置，转入只读恢复")
                recoverTransientPrivateGesture("GESTURE_SCROLL_SUBMISSION_REJECTED")
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
            val transientUnknownOnSameWindow = isSemanticallyUnconfirmed(refreshed) &&
                refreshed.packageName == targetPackage() &&
                refreshed.windowIdentityKey() == expectedWindowIdentity &&
                lastConfirmedChatListInspection?.hasSameWindowIdentity(refreshed) == true
            if (transientUnknownOnSameWindow) {
                recoveryTransaction.dispatch(RecoveryTransactionEvent.UnknownPage("MESSAGE_LIST_VIEWPORT_UNKNOWN"))
                recoveryTransaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
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
        val raw = runCatching { readPageSnapshot(AiReplyPageReadPurpose.BOUNDARY_VERIFY) }.getOrNull() ?: return null
        return stabilizeIncomingPageSnapshot(raw)
    }

    /** Keeps low-level safety checks on the shared observation semantics boundary. */
    private fun isSemanticallyUnconfirmed(
        snapshot: ChatPageSnapshot,
        previous: ChatPageSnapshot? = lastPageInspection
    ): Boolean = pageObservationSemantics.classify(
        snapshot = snapshot,
        previous = previous,
        targetPackage = targetPackage()
    ).status != ObservationStatus.CONFIRMED_PAGE

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
            isSemanticallyUnconfirmed(stable, confirmed) && confirmed != null &&
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
    private fun waitForPrivateForegroundQuiet(reason: String): Boolean {
        if (readinessDeferredRetryTask != null) return true
        if (!startupReadinessCoordinator.initializationPending ||
            runCatching(foregroundQuietRemainingMs).getOrDefault(0L) <= 0L
        ) return false
        sessionManagerState = SessionManagerState.WAITING_FOR_SESSION_LIST
        onLog("AI 启动保护[FOREGROUND_QUIET_WAIT]：$reason；仅保留待执行意图，不申请手势")
        val actionGeneration = sessionActionGeneration
        scheduleAiReadinessRetry(reason) {
            // Events may have changed the traversal phase while waiting. The unified
            // observation owns the new selection/direction; never replay the old intent.
            if (actionGeneration == sessionActionGeneration && sessionRunStarted &&
                canPerformPrivateMessagesUiAction()
            ) scheduleFullInspection()
        }
        return true
    }

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
            // During the known foreground quiet interval, use the original timer
            // cadence without rebuilding a gesture or recursively scanning the tree.
            if (runCatching(foregroundQuietRemainingMs).getOrDefault(0L) > 0L) {
                scheduleAiReadinessRetry(reason, retry)
                return@schedule
            }
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
        receivedGreetingsRecoveryRetryAttempt += 1
        val retryCount = receivedGreetingsRecoveryRetryAttempt
        val delayMs = RecoveryScheduler.readOnlyRetryDelayMs(retryCount)
        recoveryTransaction.dispatch(RecoveryTransactionEvent.RetryScheduled(delayMs))
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

    /**
     * Protect only the critical scroll follow-up/recovery from passive observation.
     * Ordinary startup probes must still allow the bounded event channel and the
     * greetings watcher to discover a different route. No event is required for the
     * original startup timer to run. This grants no page or gesture proof.
     */
    private fun hasProtectedPrivateInspection(): Boolean {
        if (pageInspectionTask == null || !sessionRunStarted || receivedGreetingsTaskActive) return false
        val gestureRecovery = recoveryContext.sourceTaskId == PRIVATE_MESSAGES_TASK_ID &&
            recoveryContext.routeId == messageListRecoveryRoute.routeId &&
            (recoveryContext.reason?.startsWith("GESTURE_SCROLL_") == true ||
                recoveryContext.reason == "GESTURE_WINDOW_CHANGED" ||
                recoveryContext.reason == "PRIVATE_SCROLL_CALLBACK_LEASE_LOST")
        return pendingPageReadPurpose == AiReplyPageReadPurpose.SCROLL_AFTER || gestureRecovery
    }

    /** Passive events may not replace an already queued send-proof read with debounce. */
    private fun hasProtectedSendInspection(): Boolean =
        pageInspectionTask != null && sessionRunStarted && !recoveryContext.pending &&
            isSendConfirmationPending() && pendingPageReadPurpose == AiReplyPageReadPurpose.MESSAGE_READ_RETRY

    private fun scheduleFullInspection() {
        scheduleFullInspectionAfter(fullInspectionDebounceMs, AiReplyPageReadPurpose.FULL_INSPECTION)
    }

    private fun scheduleFullInspectionAfter(
        delayMs: Long,
        purpose: AiReplyPageReadPurpose = AiReplyPageReadPurpose.FULL_INSPECTION
    ) {
        cancelMessageReadRetry()
        pageObservationCoordinator.cancelFullInspection()
        pageObservationCoordinator.cancelRegionRefresh()
        val token = ++fullInspectionCallbackToken
        pageInspectionTask?.cancel()
        pageInspectionTask = null
        pendingPageReadPurpose = purpose
        pageInspectionTask = sharedPageObservationCoordinator.scheduleReadOnlyObservation(delayMs) observation@{
            // A cancelled callback must not retire a replacement observation or advance a
            // new child. Reset/route recovery can also clear the pending slot without reposting.
            if (token != fullInspectionCallbackToken || pageInspectionTask == null) return@observation
            runFullInspection()
        }
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
            schedulePrivatePreSendReadRetry("MESSAGE_LIST_UNAVAILABLE")
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
            val refreshed = runCatching { readPageSnapshot(AiReplyPageReadPurpose.MESSAGE_READ_RETRY) }.getOrNull()
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

    private data class StableVisualReadFailure(
        val visit: String, val key: List<String>, val observationId: String,
        val capturedAtMillis: Long, val count: Int
    )
    private var stableVisualReadFailure: StableVisualReadFailure? = null
    private var deferredPrivateReadReturnVisit: String? = null

    private fun observeStableVisualReadFailure(
        batch: ChatMessageBatch, admission: VisibleDraftContext.Admission
    ): Boolean {
        val key = VisibleDraftContext.stableVisualDeferralKey(batch, admission)
        if (key == null) {
            stableVisualReadFailure = null
            return false
        }
        val old = stableVisualReadFailure
        val same = old != null && old.visit == draftVisitToken && old.key == key
        if (same && old != null && (old.observationId == batch.effectiveObservationBatchId ||
                batch.capturedAtMillis <= old.capturedAtMillis)) return false
        val count = if (same && old != null) (old.count + 1).coerceAtMost(2) else 1
        stableVisualReadFailure = StableVisualReadFailure(draftVisitToken, key,
            batch.effectiveObservationBatchId, batch.capturedAtMillis, count)
        return count >= 2
    }

    private fun unreadableVisualReturnIdle(): Boolean =
        sessionRunStarted && isAiReplyActive() && !recoveryContext.pending && !userInteractionActive &&
            activeSessionGestureRequestId == null && !isPrivateFollowUpActive() &&
            !isSendConfirmationPending() && !isSendControlRecoveryPending() &&
            !isConversationDraftPending() && !currentSessionSendConfirmed &&
            confirmedGreetingSend == null && privateModelFailureRecovery == null &&
            !privateModelRetryAwaitingDraft && greetingModelFailureRecovery == null

    private fun privateReadDeferralPageReady(snapshot: ChatPageSnapshot, returning: Boolean = false): Boolean =
        unreadableVisualReturnIdle() && !receivedGreetingsTaskActive &&
            sessionManagerState == (if (returning) SessionManagerState.RETURNING_TO_SESSION_LIST
                else SessionManagerState.READING_SESSION) &&
            canPerformPrivateMessagesUiAction() && hasBoundPrivateDetailRecoverySession() &&
            snapshot.packageName == targetPackage() && snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            currentSessionWindowKey == windowSessionKey(snapshot) &&
            isExpectedPrivateSessionDetail(snapshot) &&
            ChatSessionManagementPolicy.matchChatHeader(currentSessionName, snapshot.chatTitleText) != null &&
            !isSemanticallyUnconfirmed(snapshot) &&
            !pendingPrivateFollowUpEnabled(verifiedPrivateLedgerContactKey()) &&
            pendingPrivateQuotaReceipt == null && !privateQuotaReturnReady &&
            AndroidRecoveryExecutionGate.assess(snapshot, targetPackage(),
                runCatching { inspectAndroidRecoveryReadiness(snapshot) }.getOrNull()).allowed

    private fun continueDeferredPrivateReadReturn(snapshot: ChatPageSnapshot): Boolean {
        if (deferredPrivateReadReturnVisit != draftVisitToken ||
            sessionManagerState != SessionManagerState.READING_SESSION ||
            currentSessionSendConfirmed || isSendConfirmationPending() || isSendControlRecoveryPending()
        ) return false
        if (sessionTransitionTask == null) {
            if (privateReadDeferralPageReady(snapshot)) scheduleReturnToSessionList(snapshot)
            else scheduleFullInspectionAfter(RecoveryScheduler.readOnlyRetryDelayMs(1))
        }
        return true
    }

    private fun tryDeferUnreadableVisualVisit(
        snapshot: ChatPageSnapshot, batch: ChatMessageBatch, greeting: Boolean
    ): Boolean {
        if (!unreadableVisualReturnIdle()) return false
        val fresh = runCatching { readPageSnapshot(AiReplyPageReadPurpose.RETURN_NAVIGATION_VERIFY) }
            .getOrNull() ?: return false
        if (!hasSameWindowSession(snapshot, fresh) || batch.windowSessionKey != windowSessionKey(fresh) ||
            !AndroidRecoveryExecutionGate.assess(fresh, targetPackage(),
                runCatching { inspectAndroidRecoveryReadiness(fresh) }.getOrNull()).allowed ||
            !unreadableVisualReturnIdle()
        ) return false
        if (greeting) {
            if (greetingBindingRejectReason(fresh) != null ||
                !receivedGreetingsTaskFlow.deferAfterUnreadableContent()) return false
            onLog("GREETING_READ_DEFERRED：稳定非文本内容无可信待回复文字；原行延期；不计成功")
            invalidatePendingMessageRead()
            scheduleFullInspectionAfter(PRIORITY_RETURN_INSPECTION_DELAY_MS)
            return true
        }
        if (!privateReadDeferralPageReady(fresh)) return false
        val repository = privateMessagesCheckpointRepository ?: return false
        val traversal = privateMessagesTraversalTaskExecutor
        val entry = traversal.currentUnfinishedSession() ?: return false
        if (entry.conversationKey != fresh.conversationIdentityKey() ||
            !traversal.deferCurrentUnfinishedRead()) return false
        if (runCatching { repository.save(traversal.checkpoint()) }.isFailure) {
            onLog("PRIVATE_READ_DEFER_CHECKPOINT_WAIT：延期检查点未写入；禁止 BACK")
            return false
        }
        // Persist first. No send receipt, processed flag or quota is created here.
        deferredPrivateReadReturnVisit = draftVisitToken
        draftRetryTask?.cancel()
        draftRetryTask = null
        onLog("PRIVATE_READ_DEFERRED：稳定非文本内容无可信待回复文字；保留原会话；本轮延期；不计成功")
        scheduleReturnToSessionList(fresh)
        return true
    }

    /**
     * Fast OCR retries stay bounded. Genuine read failures retain low-frequency
     * observations of the same verified chat; only positively isolated visual
     * content may enter the separate durable, pre-send deferral path above.
     */
    private fun schedulePrivatePreSendReadRetry(reason: String) {
        if (!sessionRunStarted || receivedGreetingsTaskActive ||
            sessionManagerState != SessionManagerState.READING_SESSION ||
            !isAiReplyActive() || !canPerformPrivateMessagesUiAction() ||
            !hasBoundPrivateDetailRecoverySession() ||
            isSendConfirmationPending() || currentSessionSendConfirmed
        ) return
        privateOcrRecoveryAttempt += 1
        val delayMs = RecoveryScheduler.readOnlyRetryDelayMs(privateOcrRecoveryAttempt)
        onLog("AI 私聊读取恢复[READ_ONLY_RECHECK_SCHEDULED]：reason=$reason；" +
            "attempt=$privateOcrRecoveryAttempt；delayMs=$delayMs；" +
            "仅重新观察原会话，不猜测 OCR 内容、不执行发送")
        scheduleFullInspectionAfter(delayMs, AiReplyPageReadPurpose.MESSAGE_READ_RETRY)
    }

    private var ocrAdmissionRetryTask: ScheduledTask? = null
    private var ocrAdmissionRetryAttempt = 0
    private var ocrAdmissionRetryGeneration = 0L

    /** A process worker may outlive the previous task's callback. Wait without consuming OCR failure budget. */
    private fun deferBusyOcrResult(batch: ChatMessageBatch): Boolean {
        if (!batch.ocrAdmissionDeferred) {
            ocrAdmissionRetryAttempt = 0
            return false
        }
        if (!isAiReplyActive() || recoveryContext.pending || ocrAdmissionRetryTask != null) return true
        val visit = draftVisitToken
        val generation = currentRunGeneration()
        val delay = RecoveryScheduler.readOnlyRetryDelayMs(++ocrAdmissionRetryAttempt)
        onLog("AI OCR[ADMISSION_DEFERRED]：上一批工作线程仍占用；delayMs=$delay；不计识别失败、不消耗复读次数")
        val retryGeneration = ++ocrAdmissionRetryGeneration
        ocrAdmissionRetryTask = scheduler.schedule(delay) {
            if (retryGeneration != ocrAdmissionRetryGeneration) return@schedule
            ocrAdmissionRetryTask = null
            if (visit != draftVisitToken || generation != currentRunGeneration() ||
                !isAiReplyActive() || recoveryContext.pending
            ) return@schedule
            // The old batch and its geometry are discarded. Only request a fresh observation.
            scheduleFullInspectionAfter(0L, AiReplyPageReadPurpose.MESSAGE_READ_RETRY)
        }
        return true
    }

    private fun scheduleOcrReadRecheck(reason: String) {
        if (!isAiReplyActive() || recoveryContext.pending) return
        if (sessionRunStarted && !ownsTask(
                if (receivedGreetingsTaskActive) RECEIVED_GREETINGS_TASK_ID else PRIVATE_MESSAGES_TASK_ID
            )
        ) return
        onLog("AI OCR[READ_ONLY_RECHECK]：reason=$reason；保留原会话")
        if (isSendConfirmationPending()) {
            scheduleDeferredSendProofRecheck(reason)
        } else if (receivedGreetingsTaskActive) {
            scheduleReceivedGreetingsRecoveryRetry(reason)
        } else {
            schedulePrivatePreSendReadRetry(reason)
        }
    }

    private fun invalidateMessageReadTransaction() {
        ocrAdmissionRetryGeneration++
        ocrAdmissionRetryTask?.cancel()
        ocrAdmissionRetryTask = null
        ocrAdmissionRetryAttempt = 0
        messageReadCoordinator.invalidatePending()
    }

    private var draftVisitToken = java.util.UUID.randomUUID().toString()
    private var draftReadRetries = 0
    private var draftRetryTask: ScheduledTask? = null
    /** Private retry state never grants UI permission or counts as a send receipt. */
    private var privateOcrRecoveryAttempt = 0
    private var privateModelFailureRecovery: com.freedom.features.aireply.ai.AiReplyPreSendModelFailure? = null
    private var privateModelFailureRecoveryTask: ScheduledTask? = null
    private var privateModelRetryAwaitingDraft = false

    private fun invalidatePendingMessageRead() {
        greetingModelFailureRecoveryTask?.cancel()
        greetingModelFailureRecoveryTask = null
        greetingModelFailureRecovery = null
        draftVisitToken = java.util.UUID.randomUUID().toString()
        stableVisualReadFailure = null
        deferredPrivateReadReturnVisit = null
        greetingChatBinding = null
        confirmedGreetingSend = null
        draftReadRetries = 0
        draftRetryTask?.cancel()
        draftRetryTask = null
        privateOcrRecoveryAttempt = 0
        privateModelFailureRecoveryTask?.cancel()
        privateModelFailureRecoveryTask = null
        privateModelFailureRecovery = null
        privateModelRetryAwaitingDraft = false
        invalidateMessageReadTransaction()
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

    /** Matches a generic navigation result to the current page without using business state. */
    private fun selfNavigationMatchesInspection(
        transition: SelfNavigationTransition,
        inspection: ChatPageSnapshot
    ): Boolean {
        if (transition.targetActivityClass != inspection.activityClass) return false
        if (transition.targetPageGeneration == inspection.windowGeneration) return true
        val observedWindowIds = listOfNotNull(
            inspection.messageListTarget?.window?.windowId,
            inspection.inputFieldTarget?.window?.windowId,
            inspection.sessionListNavigationTarget?.nodeTarget?.window?.windowId
        ).toSet()
        return transition.targetWindowId >= 0 && transition.targetWindowId in observedWindowIds
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
