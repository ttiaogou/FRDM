package com.freedom.features.aireply.ai

import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.domain.message.MessageContentQuality
import com.freedom.features.aireply.ports.ChatDraftInputPort
import com.freedom.features.aireply.ports.ChatDraftSendPort
import com.freedom.features.aireply.ports.ChatDraftInputResult
import com.freedom.features.aireply.ports.ChatDraftInputStatus
import com.freedom.features.aireply.ports.ChatDraftInputTarget
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.policy.AiReplyPolicyRegistry
import com.freedom.features.aireply.policy.PolicyRegistry
import com.freedom.features.aireply.policy.TaskPolicy
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.TaskLease
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared, safe model/send event contract for both registered AI reply businesses.
 * Diagnostics only: no conversation text, raw transport responses, page fingerprints or UI capabilities.
 * Fixed fields allow model and send events to be joined without changing persistence contracts.
 */
internal object AiReplyTraceContract {
    fun format(
        component: String,
        requestId: String,
        taskId: String?,
        generation: Long,
        eventType: String,
        attemptIndex: Int? = null,
        retryState: String? = null,
        reasonCode: String? = null,
        delayMs: Long? = null,
        responseMode: String? = null,
        outcomeCategory: String? = null,
        segmentIndex: Int? = null,
        segmentCount: Int? = null,
        sendAttemptId: String? = null,
        sendConfirmationId: String? = null,
        sendStatus: String? = null,
        finalState: String? = null,
        sendAttemptOrigin: String? = null
    ): String {
        // Never serialize arbitrary provider, adapter or persisted identifier text.
        fun safe(value: String?): String = value?.takeIf {
            it.length in 1..128 && it.all { char ->
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                    char == '_' || char == '-' || char == '.' || char == ':'
            }
        } ?: "UNKNOWN"
        val safeTask = taskId?.takeIf {
            it == AiDecisionTaskIds.PRIVATE_MESSAGES ||
                it == AiDecisionTaskIds.RECEIVED_GREETINGS
        } ?: "UNKNOWN"
        val prefix = if (component == "MODEL") "AI 模型生命周期：" else "AI 消息发送链路："
        return prefix +
            "component=${safe(component)}；requestId=${safe(requestId)}；taskId=$safeTask；" +
            "generation=$generation；eventType=${safe(eventType)}；" +
            "attemptIndex=${attemptIndex ?: "NONE"}；retryState=${safe(retryState)}；" +
            "reasonCode=${safe(reasonCode)}；delayMs=${delayMs ?: "NONE"}；" +
            "responseMode=${safe(responseMode)}；outcomeCategory=${safe(outcomeCategory)}；" +
            "segmentIndex=${segmentIndex ?: "NONE"}；segmentCount=${segmentCount ?: "NONE"}；" +
            "sendAttemptId=${safe(sendAttemptId)}；sendConfirmationId=${safe(sendConfirmationId)}；" +
            "sendStatus=${safe(sendStatus)}；sendAttemptOrigin=${safe(sendAttemptOrigin)}；" +
            "finalState=${safe(finalState)}"
    }
}

enum class SendConfirmationResult {
    NOT_PENDING,
    WAITING,
    CONFIRMED,
    UNKNOWN
}

/**
 * Single business handoff for a multi-message send transaction. WAITING covers both a
 * segment awaiting OCR confirmation and an already confirmed segment awaiting a freshly
 * verified empty composer. Only the final fully confirmed message queue supplies a receipt.
 * The existing per-segment matcher, delays and safety gates are deliberately unchanged.
 */
/** Technical model failure before any input/send. This is not a model WAIT or a sent result. */
data class AiReplyPreSendModelFailure(
    val requestId: String,
    val requestKey: String,
    val conversationKey: String,
    val windowSessionKey: String,
    val visitToken: String,
    val taskLease: TaskLease?,
    val reasonCode: String,
    val attempts: Int,
    val retryDelayMs: Long,
    val readyToDefer: Boolean
)

data class ReplySendTransactionOutcome(
    val status: SendConfirmationResult,
    val finalSendConfirmationId: String? = null
) {
    init {
        require(status == SendConfirmationResult.CONFIRMED || finalSendConfirmationId == null) {
            "A partial or unknown send must not carry a final confirmation ID"
        }
    }
}

/**
 * 调用已配置的远端模型并填写已验证的输入框。发送操作在本阶段之外，
 * 后续阶段可继续处理已持久化的 PENDING_SEND 状态。
 */
class AiReplyDraftCoordinator(
    private val configurationRepository: AiModelConfigurationRepository,
    private val cloudConsent: AiModelCloudConsent,
    private val personalizationRepository: AiReplyPersonalizationRepository,
    private val inputPort: ChatDraftInputPort,
    private val scheduler: TaskScheduler,
    private val isAiReplyActive: () -> Boolean,
    private val onLog: (String) -> Unit,
    private val createGateway: (AiModelConfiguration) -> AiDecisionGateway,
    private val backgroundExecutor: Executor = newDraftExecutor(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val chooseRandomInt: (Int, Int) -> Int = { min, max ->
        java.util.concurrent.ThreadLocalRandom.current().nextInt(min, max + 1)
    },
    private val replyTaskRepository: ReplyTaskRepository? = null,
    private val sendPort: ChatDraftSendPort? = null,
    private val replySegmentTaskRepository: ReplySegmentTaskRepository? = null,
    private val captureTaskLease: () -> TaskLease? = { null },
    /** Delivered after a durable, no-send technical failure to the task-specific runtime. */
    private val onPreSendModelFailure: (AiReplyPreSendModelFailure) -> Unit = {},
    /** Same immutable registry used by the composition root; never grants UI ownership. */
    private val policyRegistry: PolicyRegistry = AiReplyPolicyRegistry,
    /** Requests the existing read-only page observer after a correlated platform completion. */
    private val onSendConfirmationObservationRequested: () -> Unit = {},
    /** Greeting flow can provide the last verified message observation immediately before send. */
    private val captureGreetingPreSendOutgoingKeys: (ChatDraftInputTarget) -> Set<String>? = { null }
) {
    private val pending = AtomicBoolean(false)
    private val awaitingUserSend = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private val processedMessageKeys = LinkedHashSet<String>()
    private val failedRequests = linkedMapOf<String, Pair<Int, Long>>()
    /** Only a first-chunk focus rejection with a freshly re-read empty composer can be retried. */
    private val verifiedEmptyInputRetries = linkedMapOf<String, VerifiedEmptyInputRetry>()

    /** Diagnostic-only trace. Never consulted by model decisions, send gates or retry scheduling. */
    private data class ModelLifecycleTrace(
        val requestId: String,
        val taskId: String,
        val requestGeneration: Long,
        var attemptIndex: Int = -1,
        var scheduledRetries: Int = 0,
        var retryPending: Boolean = false,
        var lastOutcomeCategory: String = "UNKNOWN",
        var terminalLogged: Boolean = false
    )

    private var modelLifecycleTrace: ModelLifecycleTrace? = null
    /** Diagnostic-only de-duplication, never used to advance a send or release a gate. */
    private val emittedSendTraceKeys = LinkedHashSet<String>()

    private fun lifecycleRecord(
        trace: ModelLifecycleTrace,
        eventType: String,
        retryState: String = "NONE",
        reasonCode: String = "NONE",
        delayMs: Long? = null,
        responseMode: String = "UNKNOWN",
        finalState: String? = null
    ): String = AiReplyTraceContract.format(
        component = "MODEL", requestId = trace.requestId, taskId = trace.taskId,
        generation = trace.requestGeneration, eventType = eventType,
        attemptIndex = trace.attemptIndex, retryState = retryState, reasonCode = reasonCode,
        delayMs = delayMs, responseMode = responseMode,
        outcomeCategory = trace.lastOutcomeCategory, finalState = finalState
    )

    /** Emits fixed diagnostic codes only; model output and exception messages never enter this log. */
    private fun recordModelLifecycle(
        requestId: String,
        requestGeneration: Long,
        eventType: String,
        attemptIndex: Int,
        retryState: String = "NONE",
        reasonCode: String = "NONE",
        delayMs: Long? = null,
        responseMode: String = "UNKNOWN",
        outcomeCategory: String = "UNKNOWN"
    ) {
        val log = synchronized(lock) {
            val trace = modelLifecycleTrace?.takeIf {
                it.requestId == requestId && it.requestGeneration == requestGeneration && !it.terminalLogged
            } ?: return@synchronized null
            when (eventType) {
                "MODEL_ATTEMPT_STARTED" -> trace.attemptIndex = attemptIndex
                "MODEL_ATTEMPT_FINISHED" -> trace.lastOutcomeCategory = outcomeCategory
                "MODEL_RETRY_SCHEDULED" -> {
                    trace.retryPending = true
                    trace.scheduledRetries++
                }
                "MODEL_RETRY_EXECUTING", "MODEL_RETRY_CANCELLED" -> trace.retryPending = false
            }
            lifecycleRecord(trace, eventType, retryState, reasonCode, delayMs, responseMode)
        }
        // Diagnostics must never cause a model decision or UI action to fail.
        log?.let { runCatching { onLog(it) } }
    }

    private fun terminalLifecycleRecord(
        trace: ModelLifecycleTrace,
        finalState: String,
        reasonCode: String,
        retryState: String = when {
            finalState == "SUCCEEDED" -> "SUCCEEDED"
            finalState == "CANCELLED" -> "CANCELLED_GENERATION_CHANGED"
            trace.lastOutcomeCategory == "EMPTY_MODEL_OUTPUT" &&
                trace.scheduledRetries == MAX_EMPTY_MODEL_RETRIES -> "EXHAUSTED"
            else -> "NONE"
        }
    ): String = lifecycleRecord(
        trace, "MODEL_REQUEST_FINISHED", retryState, reasonCode, finalState = finalState
    )

    /** Exactly one model-generation terminal event, separate from later send confirmation. */
    private fun completeModelLifecycle(
        requestId: String,
        requestGeneration: Long,
        finalState: String,
        reasonCode: String,
        retryState: String? = null
    ) {
        val log = synchronized(lock) {
            val trace = modelLifecycleTrace?.takeIf {
                it.requestId == requestId && it.requestGeneration == requestGeneration && !it.terminalLogged
            } ?: return@synchronized null
            trace.terminalLogged = true
            if (retryState == null) terminalLifecycleRecord(trace, finalState, reasonCode)
            else terminalLifecycleRecord(trace, finalState, reasonCode, retryState)
        }
        log?.let { runCatching { onLog(it) } }
    }
    private var activeInputObservationAtMillis = 0L
    private var activeMessageKey: String? = null
    private var activeRequestId: String? = null
    private var activeDraftTarget: ChatDraftInputTarget? = null
    private var activeDraftVisitToken: String? = null
    private var activeExistingOutgoingKeys: Set<String> = emptySet()
    private var activeTaskId: String? = null
    private var pendingSendConfirmation: PendingSendConfirmation? = null
    /** A confirmed segment must obtain a fresh chat/composer observation before the next one starts. */
    private var pendingNextMessageSegment: PendingNextMessageSegment? = null
    // 回复尝试应保留当前聊天页，直到新发出的消息得到确认。
    // 清除模型或输入的 pending 标志不代表消息发送成功。
    private var replyAwaitingConfirmedSend = false
    private var sendConfirmationChecks = 0
    private var lastConfirmedSendId: String? = null
    private var activeMessageSegments: List<String> = emptyList()
    /** 观察到发送按钮缺失时，说明未提交手势；必须原样保留当前消息段。 */
    private var pendingSendControl: PendingSendControl? = null
    private var sendControlRetryScheduled = false
    private var sendControlRetryTicket = 0L

    private data class PendingSendControl(
        val messageKey: String,
        val requestId: String,
        val segmentIndex: Int,
        val message: String,
        val target: ChatDraftInputTarget,
        val requestGeneration: Long,
        val observations: Int = 0
    )

    private data class PendingNextMessageSegment(
        val requestId: String,
        val segmentIndex: Int,
        val requestGeneration: Long,
        val confirmedObservationAtMillis: Long,
        val previousTarget: ChatDraftInputTarget
    )

    private data class PendingSendConfirmation(
        val sendAttemptId: String,
        val requestId: String,
        val conversationKey: String,
        val segmentIndex: Int,
        val segmentCount: Int,
        val segmentContentHash: String,
        val submittedAt: Long,
        val confirmationDeadline: Long,
        val requestGeneration: Long,
        val target: ChatDraftInputTarget,
        val draft: String,
        val existingOutgoingKeys: Set<String>,
        /** Hashed identities of outgoing bubbles visible before the original send. */
        val previousOutgoingKeyHashes: Set<String> = emptySet(),
        val recoveredFromLedger: Boolean = false
    )

    /**
     * One event contract across private messages and received greetings.
     * An unpersisted or recovered business origin is UNKNOWN, never guessed.
     */
    private fun recordSendTrace(
        eventType: String,
        requestId: String,
        requestGeneration: Long,
        segmentIndex: Int?,
        segmentCount: Int?,
        sendAttemptId: String? = null,
        sendConfirmationId: String? = null,
        sendStatus: String = "UNKNOWN",
        reasonCode: String = "NONE",
        recoveredFromLedger: Boolean = false
    ) {
        val log = synchronized(lock) {
            val terminal = eventType in setOf(
                "MESSAGE_SEND_CONFIRMED", "MESSAGE_SEND_UNCERTAIN", "MESSAGE_SEND_COMPLETED"
            )
            val key = "$requestId|$requestGeneration|$segmentIndex|${sendAttemptId.orEmpty()}|$eventType"
            if (terminal && !emittedSendTraceKeys.add(key)) return@synchronized null
            while (emittedSendTraceKeys.size > MAX_PROCESSED_KEYS) {
                emittedSendTraceKeys.remove(emittedSendTraceKeys.first())
            }
            val taskId = modelLifecycleTrace?.takeIf {
                it.requestId == requestId && it.requestGeneration == requestGeneration
            }?.taskId
            AiReplyTraceContract.format(
                component = "SEND", requestId = requestId, taskId = taskId,
                generation = requestGeneration, eventType = eventType,
                segmentIndex = segmentIndex, segmentCount = segmentCount,
                sendAttemptId = sendAttemptId, sendConfirmationId = sendConfirmationId,
                sendStatus = sendStatus, reasonCode = reasonCode,
                sendAttemptOrigin = if (recoveredFromLedger) "RECOVERED_SYNTHETIC" else "ORIGINAL"
            )
        }
        // Logging is observational only: callback failure cannot affect send state.
        log?.let { runCatching { onLog(it) } }
    }

    private fun recordSendTrace(
        eventType: String,
        pending: PendingSendConfirmation,
        sendStatus: String,
        reasonCode: String = "NONE",
        sendConfirmationId: String? = null
    ) = recordSendTrace(
        eventType, pending.requestId, pending.requestGeneration,
        pending.segmentIndex, pending.segmentCount,
        pending.sendAttemptId, sendConfirmationId,
        sendStatus, reasonCode, pending.recoveredFromLedger
    )

    private data class ReadOnlyRecoveredMatch(
        val requestId: String,
        val segmentIndex: Int,
        val messageKey: String,
        val observedAtMillis: Long
    )

    private var readOnlyRecoveredMatch: ReadOnlyRecoveredMatch? = null

    private data class VerifiedEmptyInputRetry(
        val windowSessionKey: String,
        val outgoingMessageKeys: Set<String>,
        val observationAtMillis: Long
    )

    val isPending: Boolean get() = pending.get()
    val isAwaitingUserSend: Boolean get() = awaitingUserSend.get()
    /** 对现有页面返回门禁而言，等待尚未提交的发送按钮不等于任务完成。 */
    val isSendControlRecoveryPending: Boolean get() = synchronized(lock) { pendingSendControl != null }
    /** A submitted send requires fresh read-only confirmation; draft generation alone does not. */
    val isSendConfirmationPending: Boolean get() = synchronized(lock) {
        pendingSendConfirmation != null || pendingNextMessageSegment != null
    }
    val isConversationReturnBlocked: Boolean get() = synchronized(lock) {
        pending.get() || awaitingUserSend.get() ||
            pendingSendConfirmation != null || replyAwaitingConfirmedSend
    }

    /** AI 回复运行环境停止或重建时，使迟到的回调失效。 */
    fun resetForRuntimeReset() {
        var cancelledModelLogs: List<String> = emptyList()
        synchronized(lock) {
            modelLifecycleTrace?.takeIf { !it.terminalLogged }?.let { trace ->
                trace.terminalLogged = true
                cancelledModelLogs = (if (trace.retryPending) listOf(
                    lifecycleRecord(trace, "MODEL_RETRY_CANCELLED", "CANCELLED_GENERATION_CHANGED", "RUNTIME_RESET")
                ) else emptyList()) + terminalLifecycleRecord(
                    trace, "CANCELLED", "RUNTIME_RESET", "CANCELLED_GENERATION_CHANGED"
                )
            }
            modelLifecycleTrace = null
            emittedSendTraceKeys.clear()
            pendingSendConfirmation?.let { pendingConfirmation ->
                val requestId = activeRequestId
                val repository = replySegmentTaskRepository
                if (requestId != null && repository != null) {
                    repository.find("$requestId:segment:${pendingConfirmation.segmentIndex}")?.let { record ->
                        repository.update(record.copy(status = ReplySegmentTaskStatus.UNKNOWN))
                    }
                }
            }
            generation.incrementAndGet()
            pending.set(false)
            awaitingUserSend.set(false)
            processedMessageKeys.clear()
            failedRequests.clear()
            verifiedEmptyInputRetries.clear()
            activeInputObservationAtMillis = 0L
            activeMessageKey = null
            activeRequestId = null
            activeDraftTarget = null
            activeDraftVisitToken = null
            activeExistingOutgoingKeys = emptySet()
            activeTaskId = null
            pendingSendConfirmation = null
            pendingNextMessageSegment = null
            readOnlyRecoveredMatch = null
            replyAwaitingConfirmedSend = false
            sendConfirmationChecks = 0
            lastConfirmedSendId = null
            activeMessageSegments = emptyList()
            pendingSendControl = null
            sendControlRetryScheduled = false
            sendControlRetryTicket++
        }
        cancelledModelLogs.forEach { runCatching { onLog(it) } }
    }

    fun startupFailure(): String? {
        if (!cloudConsent.isGranted()) return "CLOUD_PROCESSING_CONSENT_REQUIRED"
        val configuration = runCatching { configurationRepository.load().normalized() }.getOrNull()
            ?: return "MODEL_CONFIGURATION_LOAD_FAILED"
        return if (configuration.isReadyForCloudProvider) null else "MODEL_CONFIGURATION_NOT_READY"
    }

    /** 无生成前确认；合并已解析联系人历史与当前窗口消息。 */
    fun generateVisibleDraft(batch: ChatMessageBatch, snapshot: ChatPageSnapshot,
                             visitToken: String = batch.windowSessionKey.orEmpty(),
                             history: VerifiedDraftHistory? = null,
                             taskId: String = AiDecisionTaskIds.PRIVATE_MESSAGES): Boolean {
        val policy = policyRegistry.resolve(taskId)?.takeIf { it.taskId == taskId }
            ?: return reject("AI_TASK_POLICY_NOT_REGISTERED")
        val visible = VisibleDraftContext.build(batch, visitToken) ?: return reject("VISIBLE_DRAFT_CONTEXT_UNAVAILABLE")
        val context = DraftHistoryMerger.merge(visible, batch, policy.contextPolicy().selectHistory(history))
        val contextScope = if (context.messages.size > visible.messages.size) {
            ModelContextScope.VERIFIED_HISTORY_WITH_CURRENT_WINDOW
        } else {
            ModelContextScope.CURRENT_WINDOW_ONLY
        }
        return generateAndFill(batch, null, snapshot, context, visitToken, taskId, contextScope)
    }

    /** 仅在平台身份和当前聊天标题均已验证后调用。 */
    fun generateAndFill(
        batch: ChatMessageBatch,
        identity: ContactRowIdentity?,
        snapshot: ChatPageSnapshot,
        context: ConversationContext,
        visitToken: String = batch.windowSessionKey.orEmpty(),
        taskId: String = AiDecisionTaskIds.PRIVATE_MESSAGES,
        contextScope: ModelContextScope = if (context.currentWindowDraft) {
            ModelContextScope.CURRENT_WINDOW_ONLY
        } else {
            ModelContextScope.VERIFIED_HISTORY_WITH_CURRENT_WINDOW
        }
    ): Boolean {
        val policy = policyRegistry.resolve(taskId)?.takeIf { it.taskId == taskId }
            ?: return reject("AI_TASK_POLICY_NOT_REGISTERED")
        // A greeting must remain current-window-only even if a caller supplies history
        // directly through generateAndFill instead of generateVisibleDraft.
        if (policy.contextPolicy().scope == ModelContextScope.CURRENT_WINDOW_ONLY) {
            if (contextScope != ModelContextScope.CURRENT_WINDOW_ONLY || !context.currentWindowDraft ||
                context.messages != VisibleDraftContext.build(batch, visitToken)?.messages
            ) return reject("AI_TASK_POLICY_CONTEXT_MISMATCH")
        }
        val localDraft = context.currentWindowDraft
        val contactId = if (localDraft) VisibleDraftContext.build(batch, visitToken)?.contactId ?: return reject("CHAT_WINDOW_UNAVAILABLE")
            else identity?.contactId?.takeIf(String::isNotBlank) ?: return reject("CONTACT_ID_MISSING")
        if (!localDraft && identity?.confidence != ContactIdentityConfidence.PLATFORM_ID_VERIFIED) {
            return reject("CONTACT_IDENTITY_UNVERIFIED")
        }
        if (context.contactId != contactId || (!localDraft && context.identityConversationMismatch())) {
            return reject("CONVERSATION_CONTEXT_MISMATCH")
        }
        if (snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
            snapshot.packageName.isBlank() || snapshot.conversationIdentityKey() == null
        ) return reject("CHAT_PAGE_NOT_CONFIRMED")
        val windowIdentity = snapshot.windowIdentityKey() ?: return reject("CHAT_WINDOW_UNAVAILABLE")
        val conversationIdentity = snapshot.conversationIdentityKey() ?: return reject("CHAT_IDENTITY_UNAVAILABLE")
        val inputTarget = snapshot.inputFieldTarget ?: return reject("CHAT_INPUT_NOT_FOUND")
        val window = inputTarget.window
        val expectedWindowSessionKey =
            "${window.packageName}|${window.windowId}|${window.generation}|conversation=$conversationIdentity"
        if (batch.windowSessionKey != expectedWindowSessionKey) {
            return reject("MESSAGE_WINDOW_MISMATCH")
        }
        val visible = if (localDraft) VisibleDraftContext.build(batch, visitToken) else null
        if (localDraft && (visible == null || context.conversationId != visible.conversationId ||
                context.messages.takeLast(visible.messages.size) != visible.messages)) return reject("VISIBLE_CONTEXT_MISMATCH")
        val latestIncoming = batch.messagesBottomToTop.firstOrNull {
            (if (localDraft) VisibleDraftContext.eligible(batch, it)
                else it.isAiInputEligible && AiSafeConversationText.legacy(it.content) != null) &&
                it.direction == ChatMessageDirection.INCOMING
        } ?: return reject("NO_VERIFIED_INCOMING_MESSAGE")
        val latestSafeText = if (localDraft) {
            AiSafeConversationText.visible(batch, latestIncoming)
        } else {
            AiSafeConversationText.legacy(latestIncoming.content)
        } ?: return reject("LATEST_CONTEXT_MESSAGE_NOT_INCOMING")
        if (context.messages.isEmpty() || context.messages.last().role != AiModelMessage.Role.USER ||
            context.messages.last().content != latestSafeText.take(2_000)
        ) return reject("LATEST_CONTEXT_MESSAGE_NOT_INCOMING")
        if (!localDraft && !context.hasVerifiedIncoming) return reject("NO_VERIFIED_INCOMING_CONTEXT")
        if (!isAiReplyActive()) return reject("AI_REPLY_NOT_ACTIVE")
        if (!cloudConsent.isGranted()) return reject("CLOUD_PROCESSING_CONSENT_REQUIRED")

        val configuration = runCatching { configurationRepository.load().normalized() }.getOrElse {
            return reject("MODEL_CONFIGURATION_LOAD_FAILED")
        }
        if (!configuration.isReadyForCloudProvider) return reject("MODEL_CONFIGURATION_NOT_READY")
        val target = ChatDraftInputTarget(
            packageName = snapshot.packageName,
            activityClass = snapshot.activityClass,
            windowIdentityKey = windowIdentity,
            conversationIdentityKey = conversationIdentity,
            windowSessionKey = expectedWindowSessionKey,
            nodeTarget = inputTarget,
            expectedChatTitle = snapshot.chatTitleText,
            taskLease = captureTaskLease()
        )
        // 窗口代次用于防止旧回调继续执行，不代表新的模型任务身份。
        val dedupeConversationKey = conversationIdentity
        val dedupeKey = fingerprint("$contactId|$dedupeConversationKey|${latestIncoming.stableLogicalMessageId}|${latestIncoming.effectiveContentRevision}")
        // 新的已验证聊天观察可以将尚未提交的待发送动作重新绑定到新的
        // 窗口代次；不得重新生成模型回复，也不得重写输入框内容。
        val awaitingControl = synchronized(lock) { pendingSendControl }
        if (awaitingControl != null) {
            if (awaitingControl.messageKey != dedupeKey) return reject("SEND_CONTROL_PENDING_OTHER_CONVERSATION")
            val verified = runCatching { inputPort.verifyDraft(target, awaitingControl.message) }
                .getOrNull()?.status == ChatDraftInputStatus.VERIFIED
            if (verified) {
                synchronized(lock) {
                    if (pendingSendControl?.requestId == awaitingControl.requestId) {
                        pendingSendControl = pendingSendControl?.copy(target = target)
                    }
                }
                onLog("AI 待发送节点观察：原草稿仍在当前会话；更新窗口证据，等待安全重定位发送按钮")
                schedulePendingSendControlRetry()
            } else {
                onLog("AI 待发送节点观察：当前草稿未确认；保留原消息段，不覆盖输入、不执行发送")
            }
            return true
        }
        // 服务或进程恢复后，只有 PENDING_SEND 表示上一次手势
        // 尚未派发；此处绝不重试 SENDING、UNKNOWN 或 SENT_UNCONFIRMED。
        val savedPending = replySegmentTaskRepository?.findByConversation(dedupeConversationKey)
            ?.groupBy { it.requestId }
            ?.values
            ?.mapNotNull { records ->
                val sorted = records.sortedBy { it.segmentIndex }
                val pendingRecords = sorted.filter { it.status == ReplySegmentTaskStatus.PENDING_SEND }
                val pendingRecord = pendingRecords.singleOrNull() ?: return@mapNotNull null
                if (sorted.size != pendingRecord.segmentCount ||
                    sorted.indices.any { sorted[it].segmentIndex != it } ||
                    sorted.take(pendingRecord.segmentIndex).any { it.status != ReplySegmentTaskStatus.SENT } ||
                    sorted.any { it.status in setOf(ReplySegmentTaskStatus.SENDING, ReplySegmentTaskStatus.UNKNOWN, ReplySegmentTaskStatus.SENT_UNCONFIRMED) } ||
                    sorted.any { it.segmentContent.isBlank() || fingerprint(it.segmentContent) != it.segmentContentHash }
                ) return@mapNotNull null
                val owner = replyTaskRepository?.find(dedupeKey)
                if (owner?.requestId != pendingRecord.requestId) return@mapNotNull null
                if (pendingRecord.segmentIndex == 0 && owner?.status != ReplyTaskStatus.PENDING_SEND) return@mapNotNull null
                if (batch.messages.any {
                        it.direction == ChatMessageDirection.OUTGOING &&
                            it.contentQuality == MessageContentQuality.VERIFIED &&
                            fingerprint(it.content.orEmpty()) == pendingRecord.segmentContentHash
                    }
                ) return@mapNotNull null
                sorted
            }?.maxByOrNull { it.first().createdAt }
        if (savedPending != null) {
            val current = savedPending.single { it.status == ReplySegmentTaskStatus.PENDING_SEND }
            val composerVerified = runCatching { inputPort.verifyDraft(target, current.segmentContent) }
                .getOrNull()?.status == ChatDraftInputStatus.VERIFIED
            if (!composerVerified) return reject("PENDING_SEND_RESTORE_REQUIRES_VERIFIED_COMPOSER")
            synchronized(lock) {
                if (pendingSendConfirmation != null || pending.get()) return reject("SEND_TRANSACTION_ALREADY_ACTIVE")
                activeMessageKey = dedupeKey
                activeRequestId = current.requestId
                activeExistingOutgoingKeys = batch.messages.filter { it.direction == ChatMessageDirection.OUTGOING }
                    .mapTo(LinkedHashSet()) { messageKey(it.stableLogicalMessageId, it.effectiveContentRevision) }
                activeMessageSegments = savedPending.map { it.segmentContent }
                replyAwaitingConfirmedSend = true
                pending.set(true)
                pendingSendControl = PendingSendControl(
                    dedupeKey, current.requestId, current.segmentIndex, current.segmentContent,
                    target, generation.get()
                )
            }
            onLog("AI 待发送事务恢复[PENDING_SEND]：requestId=${current.requestId.take(12)}；" +
                "segmentIndex=${current.segmentIndex}；草稿已复核；不生成新草稿、不重发已确认段")
            schedulePendingSendControlRetry()
            return true
        }
        // UNKNOWN and SENT_UNCONFIRMED may already have reached the platform. A missing
        // outgoing bubble is not proof the send gesture was never submitted.
        // Resume only by read-only send verification; never recreate draft chunks here.
        val uncertainSend = replySegmentTaskRepository?.findByConversation(dedupeConversationKey)
            ?.firstOrNull { it.status in setOf(ReplySegmentTaskStatus.UNKNOWN, ReplySegmentTaskStatus.SENT_UNCONFIRMED) }
        if (uncertainSend != null) {
            onLog("AI 段任务恢复[READ_ONLY_REQUIRED]：requestId=${uncertainSend.requestId.take(12)}；" +
                "segmentIndex=${uncertainSend.segmentIndex}；status=${uncertainSend.status.name}；禁止重新输入或发送")
            return reject("RECOVERY_UNCERTAIN_SEND_REQUIRES_READ_ONLY_CONFIRMATION")
        }
        // A failed first focus action never submitted a send gesture. Even so, do not retry
        // until a later message observation confirms the same chat, unchanged outgoing
        // messages and an empty, freshly located composer.
        val verifiedRetry = synchronized(lock) { verifiedEmptyInputRetries[dedupeKey] }
        if (verifiedRetry != null) {
            val outgoingNow = batch.messages.asSequence()
                .filter { it.direction == ChatMessageDirection.OUTGOING }
                .map { messageKey(it.stableLogicalMessageId, it.effectiveContentRevision) }
                .toSet()
            if (verifiedRetry.windowSessionKey != batch.windowSessionKey ||
                batch.capturedAtMillis <= verifiedRetry.observationAtMillis ||
                outgoingNow != verifiedRetry.outgoingMessageKeys ||
                runCatching { inputPort.verifyDraft(target, "") }.getOrNull()?.status != ChatDraftInputStatus.VERIFIED
            ) return reject("DRAFT_INPUT_RETRY_REQUIRES_FRESH_EMPTY_UNCHANGED_CHAT")
        }
        val requestId = UUID.randomUUID().toString()
        val taskRecord = ReplyTaskRecord(
            requestKey = dedupeKey,
            conversationKey = dedupeConversationKey,
            logicalMessageId = latestIncoming.stableLogicalMessageId,
            contentRevision = latestIncoming.effectiveContentRevision,
            status = ReplyTaskStatus.REQUESTED,
            requestId = requestId,
            updatedAtMillis = nowMillis(),
            userId = contactId
        )
        val requestGeneration = synchronized(lock) {
            if (awaitingUserSend.get()) return reject("DRAFT_AWAITING_USER_SEND")
            if (pending.get()) return reject("DRAFT_REQUEST_ALREADY_PENDING")
            if (dedupeKey in processedMessageKeys) return reject("DUPLICATE_INCOMING_MESSAGE")
            val failure = failedRequests[dedupeKey]
            if (failure != null && (failure.first >= 2 || nowMillis() < failure.second)) {
                return reject("DRAFT_FAILURE_COOLDOWN_OR_RETRY_LIMIT")
            }
            if (replyTaskRepository != null && !replyTaskRepository.tryClaim(
                    taskRecord, REPLY_TASK_REQUEST_TIMEOUT_MILLIS
                )) {
                val status = replyTaskRepository.find(dedupeKey)?.status?.name ?: "CLAIM_FAILED"
                return reject("PERSISTED_REPLY_TASK_$status")
            }
            activeMessageKey = dedupeKey
            replyAwaitingConfirmedSend = true
            activeRequestId = requestId
            activeDraftTarget = target
            activeDraftVisitToken = visitToken
            activeTaskId = taskId
            // Old successful requests may have retained their in-memory segment list.
            // Starting a new model request must not inherit that prior queue.
            activeMessageSegments = emptyList()
            activeInputObservationAtMillis = batch.capturedAtMillis
            verifiedEmptyInputRetries.remove(dedupeKey)
            activeExistingOutgoingKeys = batch.messages
                .filter { it.direction == ChatMessageDirection.OUTGOING }
                .mapTo(LinkedHashSet()) { messageKey(it.stableLogicalMessageId, it.effectiveContentRevision) }
            processedMessageKeys += dedupeKey
            while (processedMessageKeys.size > MAX_PROCESSED_KEYS) {
                val oldest = processedMessageKeys.firstOrNull() ?: break
                processedMessageKeys.remove(oldest)
            }
            pending.set(true)
            generation.get().also { requestGeneration ->
                modelLifecycleTrace = ModelLifecycleTrace(requestId, policy.taskId, requestGeneration)
            }
        }
        val accepted = runCatching {
            backgroundExecutor.execute {
                try {
                    generateOnBackground(configuration, context, target, requestGeneration, requestId, policy, contextScope)
                } catch (error: Throwable) {
                    onLog("AI 回复草稿生成失败：[DRAFT_GENERATION_FAILED]；requestId=$requestId；" +
                        "taskId=${policy.taskId}；exceptionType=${error.javaClass.simpleName}")
                    finish("DRAFT_GENERATION_FAILED", requestGeneration)
                }
            }
        }.isSuccess
        if (!accepted) {
            finish("BACKGROUND_EXECUTOR_REJECTED", requestGeneration)
            return reject("BACKGROUND_EXECUTOR_REJECTED")
        }
        onLog("AI 回复草稿：已提交后台模型请求；不会自动发送")
        return true
    }

    private fun generateOnBackground(
        configuration: AiModelConfiguration,
        context: ConversationContext,
        target: ChatDraftInputTarget,
        requestGeneration: Long,
        requestId: String,
        policy: TaskPolicy,
        contextScope: ModelContextScope,
        emptyRetryAttempt: Int = 0
    ) {
        if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive()) {
            finish("AI_REPLY_STATE_CHANGED_BEFORE_REQUEST", requestGeneration)
            return
        }
        val callStartedAt = nowMillis()
        // Only the final DeepSeek empty-output retry changes the transport response mode.
        val promptOnlyJsonSchema = configuration.provider == AiModelServiceProvider.DEEPSEEK &&
            emptyRetryAttempt == MAX_EMPTY_MODEL_RETRIES
        val responseMode = if (promptOnlyJsonSchema) "PROMPT_ONLY_JSON" else "STRICT_JSON"
        recordModelLifecycle(
            requestId, requestGeneration, "MODEL_ATTEMPT_STARTED", emptyRetryAttempt,
            responseMode = responseMode
        )
        onLog(
            "AI 模型调用开始：requestId=$requestId；" +
                "provider=${configuration.provider.name}；" +
                "modelHash=${diagnosticHash(configuration.modelName)}；" +
                "contextMessages=${context.messages.size}；" +
                "emptyRetryAttempt=$emptyRetryAttempt；responseMode=$responseMode"
        )
        val evaluation = try {
            createGateway(configuration).decide(
                ModelDecisionRequest(
                    taskId = policy.taskId,
                    requestId = requestId,
                    context = context,
                    constraints = ModelDecisionConstraints(
                        contextScope = contextScope,
                        contactIdentityVerified = !context.currentWindowDraft,
                        cooldownActive = false,
                        personalization = personalizationRepository.load()
                    ),
                    outputContract = policy.modelContract(),
                    responseMode = if (promptOnlyJsonSchema) {
                        ModelResponseMode.PROMPT_ONLY_JSON
                    } else {
                        ModelResponseMode.STRICT_JSON
                    }
                )
            )
        } catch (error: Throwable) {
            recordModelLifecycle(
                requestId, requestGeneration, "MODEL_ATTEMPT_FINISHED", emptyRetryAttempt,
                reasonCode = "DECIDE_EXCEPTION", responseMode = responseMode,
                outcomeCategory = "MODEL_CALL_FAILED"
            )
            onLog(
                "AI 模型调用异常：requestId=$requestId；" +
                    "stage=DECIDE；" +
                    "exception=${error.javaClass.simpleName}；" +
                    "elapsedMs=${(nowMillis() - callStartedAt).coerceAtLeast(0L)}"
            )
            throw error
        }
        fun safeLog(value: String?): String = AiModelDataRedactor.redact(value.orEmpty())
            .replace(Regex("[\\r\\n\\t\\p{Cntrl}]"), " ").take(1_000)
        if (evaluation is AiReplyEvaluation.Execution) {
            val outcomeCategory = when {
                evaluation.status == AiReplyExecutionStatus.MODEL_EMPTY_OUTPUT ||
                    evaluation.callDiagnostics?.safeCode == "EMPTY_MODEL_OUTPUT" ->
                    AiModelOutputCategory.EMPTY_MODEL_OUTPUT
                evaluation.status == AiReplyExecutionStatus.MODEL_OUTPUT_INVALID ->
                    AiModelOutputCategory.MODEL_OUTPUT_INVALID
                evaluation.status == AiReplyExecutionStatus.MODEL_CALL_FAILED ->
                    AiModelOutputCategory.MODEL_CALL_FAILED
                else -> AiModelOutputCategory.LOCAL_PRECHECK_BLOCKED
            }
            val safeCode = evaluation.outputInvalidReason?.name
                ?: evaluation.callDiagnostics?.safeCode ?: evaluation.status.reasonCode
            val contentState = evaluation.outputContentState
                ?: evaluation.callDiagnostics?.responseContentState ?: "UNKNOWN"
            recordModelLifecycle(
                requestId, requestGeneration, "MODEL_ATTEMPT_FINISHED", emptyRetryAttempt,
                reasonCode = outcomeCategory.name, responseMode = responseMode,
                outcomeCategory = outcomeCategory.name
            )
            onLog("AI 模型结果分类：requestId=$requestId；taskId=${policy.taskId}；" +
                "generation=$requestGeneration；outcomeCategory=${outcomeCategory.name}；" +
                "safeCode=${safeLog(safeCode)}；contentState=${safeLog(contentState)}；" +
                "executionState=${evaluation.status.name}；" +
                "provider=${safeLog(evaluation.provider ?: configuration.provider.name)}；" +
                "modelHash=${diagnosticHash(evaluation.model ?: configuration.modelName)}；" +
                "elapsedMs=${(nowMillis() - callStartedAt).coerceAtLeast(0L)}；draftPresent=false")
            // A local gate, transport failure or invalid protocol is never a model WAIT.
            onLog(
                "AI 程序执行状态：requestId=$requestId；executionState=${evaluation.status.name}；" +
                    "reason=${evaluation.status.reasonCode}；draftPresent=false"
            )
            evaluation.callDiagnostics?.let { diagnostics ->
                onLog("AI 模型调用失败：requestId=${safeLog(requestId)}；" +
                    "provider=${safeLog(configuration.provider.name)}；modelHash=${diagnosticHash(configuration.modelName)}；" +
                    "elapsedMs=${(nowMillis() - callStartedAt).coerceAtLeast(0L)}；${diagnostics.logFields()}")
            }
            if (evaluation.status == AiReplyExecutionStatus.MODEL_CALL_FAILED) {
                val diagnostics = evaluation.callDiagnostics
                if (diagnostics?.safeCode == "EMPTY_MODEL_OUTPUT" && diagnostics.retryable == true &&
                    emptyRetryAttempt < MAX_EMPTY_MODEL_RETRIES &&
                    isCurrentGeneration(requestGeneration) && isAiReplyActive()
                ) {
                    val retryDelayMs = if (emptyRetryAttempt == 0) FIRST_EMPTY_MODEL_RETRY_DELAY_MS
                        else SECOND_EMPTY_MODEL_RETRY_DELAY_MS
                    val nextMode = if (configuration.provider == AiModelServiceProvider.DEEPSEEK &&
                        emptyRetryAttempt + 1 == MAX_EMPTY_MODEL_RETRIES
                    ) "PROMPT_ONLY_JSON" else "STRICT_JSON"
                    onLog("AI 模型空输出有限恢复：requestId=$requestId；" +
                        "attempt=${emptyRetryAttempt + 1}/$MAX_EMPTY_MODEL_RETRIES；" +
                        "delayMs=$retryDelayMs；responseMode=$nextMode；不写草稿、不执行发送")
                    val scheduled = runCatching {
                        scheduler.schedule(retryDelayMs) {
                            if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive()) {
                                val retryState = if (!isCurrentGeneration(requestGeneration)) {
                                    "CANCELLED_GENERATION_CHANGED"
                                } else "CANCELLED_RUNTIME_INACTIVE"
                                recordModelLifecycle(
                                    requestId, requestGeneration, "MODEL_RETRY_CANCELLED",
                                    emptyRetryAttempt + 1, retryState, "RETRY_GATE_CHANGED",
                                    responseMode = nextMode
                                )
                                completeModelLifecycle(
                                    requestId, requestGeneration, "CANCELLED", "RETRY_GATE_CHANGED", retryState
                                )
                                return@schedule
                            }
                            // 等待期间用户可能切换了会话或自行输入文字。
                            // 再次发送模型请求前，必须重新验证当前真实输入框。
                            val composerReady = runCatching { inputPort.verifyDraft(target, "") }
                                .getOrNull()?.status == ChatDraftInputStatus.VERIFIED
                            if (!composerReady) {
                                recordModelLifecycle(
                                    requestId, requestGeneration, "MODEL_RETRY_CANCELLED",
                                    emptyRetryAttempt + 1, "CANCELLED_CONTEXT_CHANGED",
                                    "COMPOSER_NOT_VERIFIED", responseMode = nextMode
                                )
                                onLog("AI 模型空输出恢复取消：原会话或空输入框已变化；不重试、不发送")
                                finish("MODEL_EMPTY_RETRY_CONTEXT_CHANGED", requestGeneration)
                                return@schedule
                            }
                            recordModelLifecycle(
                                requestId, requestGeneration, "MODEL_RETRY_EXECUTING",
                                emptyRetryAttempt + 1, "EXECUTING", "COMPOSER_VERIFIED",
                                responseMode = nextMode
                            )
                            val accepted = runCatching {
                                backgroundExecutor.execute {
                                    try {
                                        generateOnBackground(
                                            configuration, context, target, requestGeneration, requestId,
                                            policy, contextScope, emptyRetryAttempt + 1
                                        )
                                    } catch (_: Throwable) {
                                        finish("DRAFT_GENERATION_FAILED", requestGeneration)
                                    }
                                }
                            }.isSuccess
                            if (!accepted) finish("BACKGROUND_EXECUTOR_REJECTED", requestGeneration)
                        }
                    }.isSuccess
                    if (scheduled) {
                        recordModelLifecycle(
                            requestId, requestGeneration, "MODEL_RETRY_SCHEDULED",
                            emptyRetryAttempt + 1, "SCHEDULED", "EMPTY_MODEL_OUTPUT",
                            retryDelayMs, nextMode
                        )
                        return
                    }
                    recordModelLifecycle(
                        requestId, requestGeneration, "MODEL_RETRY_SCHEDULE_FAILED",
                        emptyRetryAttempt + 1, reasonCode = "SCHEDULER_UNAVAILABLE",
                        responseMode = nextMode
                    )
                }
                finish("MODEL_DECISION_MODEL_CALL_FAILED", requestGeneration)
                return
            }
            val finishCode = when (evaluation.status) {
                AiReplyExecutionStatus.MODEL_EMPTY_OUTPUT,
                AiReplyExecutionStatus.MODEL_OUTPUT_INVALID ->
                    "MODEL_DECISION_${evaluation.status.reasonCode}"
                else -> "EXECUTION_${evaluation.status.reasonCode}"
            }
            // No valid decision or draft exists: do not persist MODEL_COMPLETED.
            // finish() records a retryable, pre-send terminal failure instead.
            finish(finishCode, requestGeneration)
            return
        }
        val decision = (evaluation as AiReplyEvaluation.Model).decision
        recordModelLifecycle(
            requestId, requestGeneration, "MODEL_ATTEMPT_FINISHED", emptyRetryAttempt,
            reasonCode = "VALID_DECISION", responseMode = responseMode,
            outcomeCategory = "VALID_DECISION"
        )
        val draftPresent = !decision.draft.isNullOrBlank()
        val draftLength = decision.draft?.codePointCount(0, decision.draft.length) ?: 0
        val messageCount = if (draftPresent) ReplyMessageSegments.parse(decision.draft!!).messages.size else 0
        onLog("AI 模型结果分类：requestId=$requestId；taskId=${policy.taskId}；" +
            "generation=$requestGeneration；outcomeCategory=VALID_DECISION；" +
            "safeCode=MODEL_DECISION_${decision.action.name}；contentState=PRESENT；" +
            "action=${decision.action.name}；reasonHash=${diagnosticHash(decision.reason)}；" +
            "provider=${safeLog(decision.provider)}；modelHash=${diagnosticHash(decision.model)}；" +
            "finishReason=${AiModelCallDiagnostics.safeFinishReason(decision.finishReason)}；" +
            "elapsedMs=${(nowMillis() - callStartedAt).coerceAtLeast(0L)}；" +
            "inputTokens=${decision.usage?.inputTokens ?: "UNKNOWN"}；" +
            "outputTokens=${decision.usage?.outputTokens ?: "UNKNOWN"}；" +
            "draftPresent=$draftPresent；draftLength=$draftLength；messageCount=$messageCount；" +
            "reasonDetailPresent=${!decision.reasonDetail.isNullOrBlank()}；" +
            "reasonDetailLength=${decision.reasonDetail?.length ?: 0}")
        onLog(
            "AI 模型调用完成：requestId=$requestId；" +
                "elapsedMs=${(nowMillis() - callStartedAt).coerceAtLeast(0L)}；" +
                "action=${decision.action.name}；" +
                "reasonHash=${diagnosticHash(decision.reason)}；" +
                "draftPresent=${!decision.draft.isNullOrBlank()}"
        )
        onLog("AI 模型决策详情：requestId=$requestId；action=${decision.action.name}；" +
            "reasonHash=${diagnosticHash(decision.reason)}；" +
            "reasonDetailPresent=${!decision.reasonDetail.isNullOrBlank()}；" +
            "reasonDetailLength=${decision.reasonDetail?.length ?: 0}；" +
            "provider=${safeLog(decision.provider)}；modelHash=${diagnosticHash(decision.model)}；" +
            "finishReason=${AiModelCallDiagnostics.safeFinishReason(decision.finishReason)}；" +
            "contextMessages=${context.messages.size}；" +
            "inputTokens=${decision.usage?.inputTokens ?: "UNKNOWN"}；" +
            "outputTokens=${decision.usage?.outputTokens ?: "UNKNOWN"}；" +
            "draftPresent=$draftPresent；draftLength=$draftLength；messageCount=$messageCount")
        completeModelLifecycle(
            requestId, requestGeneration, "SUCCEEDED", "MODEL_DECISION_${decision.action.name}"
        )
        updateTaskStatus(ReplyTaskStatus.MODEL_COMPLETED, requestGeneration)
        if (decision.action != AiReplyAction.REPLY || decision.draft.isNullOrBlank()) {
            onLog(
                "AI 草稿状态：MODEL_DID_NOT_RETURN_DRAFT；模型未返回可用草稿；" +
                    "reasonHash=${diagnosticHash(decision.reason)}；reasonDetailPresent=${!decision.reasonDetail.isNullOrBlank()}；" +
                    "action=${decision.action.name}；draftPresent=${!decision.draft.isNullOrBlank()}"
            )
            // Never interpret an untrusted model reason as a technical retry code.
            finish("MODEL_BUSINESS_${decision.action.name}", requestGeneration)
            return
        }
        if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive()) {
            finish("AI_REPLY_STATE_CHANGED_AFTER_REQUEST", requestGeneration)
            return
        }
        scheduler.schedule(0L) {
            if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive()) {
                finish("AI_REPLY_STATE_CHANGED_BEFORE_INPUT", requestGeneration)
                return@schedule
            }
            // 消息边界由模型决定；现有输入节奏仍负责将每条消息按
            // 2～3 个字素簇逐段写入。第一阶段在完整草稿验证后停止，
            // 不得在该阶段提交发送手势。
            val parsedMessages = ReplyMessageSegments.parse(decision.draft)
            onLog(
                "AI 回复解析诊断：requestId=$requestId；" +
                    "lineBreakCount=${parsedMessages.lineBreakCount}；" +
                    "messageCount=${parsedMessages.messages.size}；" +
                    "messageLengths=${parsedMessages.messages.joinToString(",", prefix="[", postfix="]") { it.codePointCount(0, it.length).toString() }}；" +
                    "fallbackToSingleMessage=${parsedMessages.fallbackToSingleMessage}"
            )
            persistMessageSegmentTasks(
                requestId = requestId,
                conversationKey = target.conversationIdentityKey,
                messages = parsedMessages.messages
            )
            activeMessageSegments = parsedMessages.messages.map(String::trim).filter(String::isNotEmpty)
            writeMessageSegment(target, 0, requestGeneration)
        }
    }

    private fun persistMessageSegmentTasks(
        requestId: String,
        conversationKey: String,
        messages: List<String>
    ) {
        val repository = replySegmentTaskRepository ?: return
        val records = ReplySegmentTaskFactory.create(requestId, conversationKey, messages, nowMillis())
        records.forEach { record ->
            runCatching {
                if (repository.find(record.taskId) == null) repository.save(record)
            }.onFailure { error ->
                onLog("AI 消息段任务持久化失败：requestId=$requestId；segmentIndex=${record.segmentIndex}；error=${error.javaClass.simpleName}")
            }
        }
        onLog("AI 消息段任务已建立：requestId=$requestId；conversationKeyHash=${diagnosticHash(conversationKey)}；segmentCount=${records.size}；输入片段仍由 DraftSegments 独立处理")
    }

    private fun writeMessageSegment(target: ChatDraftInputTarget, segmentIndex: Int, requestGeneration: Long) {
        val message = activeMessageSegments.getOrNull(segmentIndex).orEmpty()
        if (message.isBlank()) { finish("DRAFT_SEGMENT_EMPTY", requestGeneration); return }
        writeMessageChunk(target, segmentIndex, DraftSegments.split(message), 0, "", requestGeneration)
    }

    private fun writeMessageChunk(target: ChatDraftInputTarget, segmentIndex: Int, chunks: List<String>, chunkIndex: Int, confirmed: String, requestGeneration: Long) {
        if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive()) { finish("AI_REPLY_STATE_CHANGED_BEFORE_INPUT", requestGeneration); return }
        val expected = confirmed + chunks[chunkIndex]
        val result = runCatching { inputPort.writeDraft(target, confirmed, expected) }
            .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.ACTION_REJECTED) }
        if (!result.succeeded) {
            // First chunk, first message: a rejected FOCUS never performed SET_TEXT
            // and no send path has run. A blank composer re-read is the final proof.
            val safeBeforeAnyWrite = segmentIndex == 0 && chunkIndex == 0 &&
                confirmed.isEmpty() && result.status == ChatDraftInputStatus.ACTION_REJECTED &&
                result.reasonCode == "INPUT_FOCUS_REJECTED" &&
                runCatching { inputPort.verifyDraft(target, "") }.getOrNull()?.status == ChatDraftInputStatus.VERIFIED &&
                synchronized(lock) { pendingSendConfirmation == null && !awaitingUserSend.get() &&
                    activeMessageKey != null && activeRequestId != null } &&
                updateTaskStatus(ReplyTaskStatus.FAILED_RETRYABLE, requestGeneration)
            if (safeBeforeAnyWrite) {
                synchronized(lock) {
                    if (!isCurrentGeneration(requestGeneration)) return
                    val key = activeMessageKey ?: return
                    verifiedEmptyInputRetries[key] = VerifiedEmptyInputRetry(
                        target.windowSessionKey, activeExistingOutgoingKeys.toSet(), activeInputObservationAtMillis
                    )
                    while (verifiedEmptyInputRetries.size > MAX_PROCESSED_KEYS) {
                        verifiedEmptyInputRetries.remove(verifiedEmptyInputRetries.keys.first())
                    }
                    replyAwaitingConfirmedSend = false
                }
                onLog("AI 草稿首次输入恢复：FOCUS_REJECTED；已确认原会话空输入框、未写入且未开始发送；" +
                    "等待新鲜消息观察复核，有限重试，不复用旧节点")
                finish("DRAFT_INPUT_CONFIRMED_EMPTY_RETRYABLE", requestGeneration)
                return
            }
            finish("DRAFT_INPUT_${result.reasonCode}", requestGeneration)
            return
        }
        scheduler.schedule(chooseRandomInt(MIN_VERIFY_DWELL_DELAY_MS, MAX_VERIFY_DWELL_DELAY_MS).toLong()) {
            val checked = runCatching { inputPort.verifyDraft(target, expected) }
                .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.VERIFICATION_FAILED) }
            if (checked.status != ChatDraftInputStatus.VERIFIED) { finish("DRAFT_VERIFY_${checked.reasonCode}", requestGeneration); return@schedule }
            if (chunkIndex + 1 < chunks.size) writeMessageChunk(target, segmentIndex, chunks, chunkIndex + 1, expected, requestGeneration)
            else {
                val message = activeMessageSegments[segmentIndex]
                val finalCheck = runCatching { inputPort.verifyDraft(target, message) }
                    .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.VERIFICATION_FAILED) }
                if (finalCheck.status != ChatDraftInputStatus.VERIFIED) { finish("DRAFT_VERIFY_${finalCheck.reasonCode}", requestGeneration); return@schedule }
                updateSegmentStatus(requestGeneration, segmentIndex, ReplySegmentTaskStatus.DRAFT_APPLIED)
                updateTaskStatus(ReplyTaskStatus.DRAFT_APPLIED, requestGeneration)
                sendMessageSegment(target, message, segmentIndex, requestGeneration)
            }
        }
    }

    private fun sendMessageSegment(target: ChatDraftInputTarget, message: String, segmentIndex: Int, requestGeneration: Long) {
        if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive() || !hasActiveTaskOwner()) return
        if (synchronized(lock) { pendingSendConfirmation != null }) return
        // For received greetings, refresh the outgoing baseline immediately before the
        // local gesture. The model-request observation may be stale while the remote call
        // was in flight; only messages appearing after this baseline can prove this send.
        if (synchronized(lock) { activeTaskId == AiDecisionTaskIds.RECEIVED_GREETINGS }) {
            captureGreetingPreSendOutgoingKeys(target)?.let { keys ->
                synchronized(lock) { activeExistingOutgoingKeys = keys.toSet() }
                onLog("收到招呼发送前快照[BASELINE_CAPTURED]：己方消息=${keys.size}；发送前重新建立差分基线")
            }
        }
        updateSegmentStatus(requestGeneration, segmentIndex, ReplySegmentTaskStatus.PENDING_SEND)
        if (segmentIndex == 0) updateTaskStatus(ReplyTaskStatus.PENDING_SEND, requestGeneration)
        // 保留手势提交前持久化的 SENDING 安全标记；只有明确返回
        // SEND_CONTROL_NOT_READY 且确认未派发手势时，才允许回退此状态。
        // Persist SENDING and its pre-send evidence in the SAME durable update.
        // A crash must not leave a bare SENDING record without its recovery proof.
        // Persist the pre-send snapshot BEFORE invoking the gesture port. The absence of
        // an outgoing bubble after a restart must not permit replay or text-only proof.
        val sendEvidenceSaved = replySegmentTaskRepository?.let { repository ->
            runCatching {
                val requestId = synchronized(lock) { activeRequestId }.orEmpty()
                val requestKey = synchronized(lock) { activeMessageKey }.orEmpty()
                val id = "$requestId:segment:$segmentIndex"
                val record = repository.find(id)
                val evidence = record?.copy(
                    status = ReplySegmentTaskStatus.SENDING,
                    sendAttemptStartedAt = nowMillis(),
                    sendWindowSessionKey = target.windowSessionKey,
                    sendChatTitleHash = fingerprint(normalizeTitle(target.expectedChatTitle)),
                    preSendOutgoingKeyHashes = synchronized(lock) { activeExistingOutgoingKeys.mapTo(LinkedHashSet()) { fingerprint(it) } },
                    sendRequestKey = requestKey
                )
                requestId.isNotBlank() && requestKey.isNotBlank() &&
                    record?.status == ReplySegmentTaskStatus.PENDING_SEND &&
                    evidence != null && repository.update(evidence) &&
                    repository.find(id) == evidence
            }.getOrDefault(false)
        } ?: true
        if (!sendEvidenceSaved) {
            onLog("AI 发送前检查点[NOT_DURABLE]：无法确认证据已持久化；禁止提交发送")
            finish("SEND_PRECHECKPOINT_NOT_DURABLE", requestGeneration)
            return
        }
        if (segmentIndex == 0) updateTaskStatus(ReplyTaskStatus.SENDING, requestGeneration)
        val sendAttemptId = UUID.randomUUID().toString()
        val completion = sendCompletionObservation(sendAttemptId, requestGeneration)
        val sent = runCatching { sendPort?.sendDraft(target, message, completion) ?: ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "SEND_PORT_UNAVAILABLE") }
            .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "SEND_PORT_EXCEPTION") }
        if (sent.status == ChatDraftInputStatus.SEND_CONTROL_NOT_FOUND &&
            sent.reasonCode == "SEND_CONTROL_NOT_READY"
        ) {
            // 适配器已经验证输入框内容完全匹配，但未发现符合条件的发送控件，
            // 因而没有提交手势；不得因此终止任务。
            updateSegmentStatus(requestGeneration, segmentIndex, ReplySegmentTaskStatus.PENDING_SEND)
            if (segmentIndex == 0) updateTaskStatus(ReplyTaskStatus.PENDING_SEND, requestGeneration)
            synchronized(lock) {
                val old = pendingSendControl?.takeIf {
                    it.requestId == activeRequestId && it.segmentIndex == segmentIndex
                }
                pendingSendControl = PendingSendControl(
                    activeMessageKey.orEmpty(), activeRequestId.orEmpty(), segmentIndex, message,
                    target, requestGeneration, (old?.observations ?: 0) + 1
                )
                pending.set(true)
            }
            recordSendTrace(
                "MESSAGE_SEND_DEFERRED", synchronized(lock) { activeRequestId }.orEmpty(),
                requestGeneration, segmentIndex, activeMessageSegments.size,
                sendStatus = "NOT_SUBMITTED", reasonCode = "SEND_CONTROL_NOT_READY"
            )
            onLog("AI 待发送事务[BUTTON_PENDING]：segmentIndex=$segmentIndex；" +
                "草稿已验证、手势未提交；保留 PENDING_SEND 并等待新鲜节点")
            schedulePendingSendControlRetry()
            return
        }
        if (sent.status != ChatDraftInputStatus.SEND_SUBMITTED) {
            recordSendTrace(
                "MESSAGE_SEND_REJECTED", synchronized(lock) { activeRequestId }.orEmpty(),
                requestGeneration, segmentIndex, activeMessageSegments.size,
                sendStatus = "NOT_SUBMITTED", reasonCode = sent.status.name
            )
            updateSegmentStatus(requestGeneration, segmentIndex, ReplySegmentTaskStatus.FAILED_RETRYABLE)
            updateTaskStatus(ReplyTaskStatus.FAILED_RETRYABLE, requestGeneration)
            finish("DRAFT_SEND_${sent.reasonCode}", requestGeneration)
            return
        }
        synchronized(lock) { pendingSendControl = null }
        val submittedAt = nowMillis()
        synchronized(lock) {
            pendingSendConfirmation = PendingSendConfirmation(sendAttemptId, activeRequestId.orEmpty(), target.conversationIdentityKey, segmentIndex, activeMessageSegments.size, fingerprint(message), submittedAt, submittedAt + SEND_CONFIRMATION_TIMEOUT_MILLIS, requestGeneration, target, message, activeExistingOutgoingKeys)
            sendConfirmationChecks = 0
        }
        updateSegmentStatus(requestGeneration, segmentIndex, ReplySegmentTaskStatus.SENT_UNCONFIRMED)
        if (segmentIndex == 0) updateTaskStatus(ReplyTaskStatus.SENT_UNCONFIRMED, requestGeneration)
        pending.set(false)
        synchronized(lock) { pendingSendConfirmation }?.let {
            recordSendTrace("MESSAGE_SEND_SUBMITTED", it, "SENT_UNCONFIRMED")
        }
    }

    /**
     * The adapter may call back before sendDraft returns. Queue the notification on
     * the existing runtime scheduler, where SEND_SUBMITTED has installed its pending
     * record. A duplicate/old completion cannot wake a different send attempt.
     * No confirmation, count, retry-send or UI permission is granted here.
     */
    private fun sendCompletionObservation(sendAttemptId: String, requestGeneration: Long): () -> Unit {
        val delivered = AtomicBoolean(false)
        return {
            if (delivered.compareAndSet(false, true)) {
                runCatching {
                    scheduler.schedule(0L) {
                        if (!isAiReplyActive() || !isCurrentGeneration(requestGeneration)) return@schedule
                        val matches = synchronized(lock) {
                            pendingSendConfirmation?.let {
                                it.sendAttemptId == sendAttemptId && it.requestGeneration == requestGeneration
                            } == true
                        }
                        if (!matches) return@schedule
                        onLog("AI 发送确认[GESTURE_COMPLETED_RECHECK]：sendAttemptId=${sendAttemptId.take(12)}；主动请求新鲜只读观察；不标记发送成功")
                        onSendConfirmationObservationRequested()
                    }
                }.onFailure {
                    onLog("AI 发送确认[COMPLETION_OBSERVATION_UNAVAILABLE]：保留待确认发送；不重发")
                }
            }
        }
    }

    private fun schedulePendingSendControlRetry() {
        val scheduled = synchronized(lock) {
            if (sendControlRetryScheduled || pendingSendControl == null) null
            else {
                sendControlRetryScheduled = true
                pendingSendControl!! to ++sendControlRetryTicket
            }
        } ?: return
        val (current, ticket) = scheduled
        // 仅限制检查频率，不设置终止性重试次数上限；界面就绪可能延迟。
        val delayMs = when {
            current.observations <= 2 -> 1_000L
            current.observations <= 4 -> 2_000L
            current.observations <= 8 -> 5_000L
            else -> 30_000L
        }
        val accepted = runCatching {
            scheduler.schedule(delayMs) {
                val pendingControl = synchronized(lock) {
                    if (ticket != sendControlRetryTicket) null
                    else {
                        sendControlRetryScheduled = false
                        pendingSendControl
                    }
                } ?: return@schedule
                if (!isCurrentGeneration(pendingControl.requestGeneration) || !isAiReplyActive()) return@schedule
                if (synchronized(lock) {
                        pendingSendConfirmation != null || activeRequestId != pendingControl.requestId
                    }
                ) return@schedule
                val verified = runCatching { inputPort.verifyDraft(pendingControl.target, pendingControl.message) }
                    .getOrNull()?.status == ChatDraftInputStatus.VERIFIED
                if (!verified) {
                    synchronized(lock) {
                        if (pendingSendControl?.requestId == pendingControl.requestId) {
                            pendingSendControl = pendingControl.copy(observations = pendingControl.observations + 1)
                        }
                    }
                    onLog("AI 待发送事务[COMPOSER_NOT_READY]：草稿或窗口尚未再次确认；保留当前段，不覆盖、不发送")
                    schedulePendingSendControlRetry()
                    return@schedule
                }
                sendMessageSegment(
                    pendingControl.target, pendingControl.message, pendingControl.segmentIndex,
                    pendingControl.requestGeneration
                )
            }
        }.isSuccess
        if (!accepted) {
            synchronized(lock) {
                if (ticket == sendControlRetryTicket) sendControlRetryScheduled = false
            }
            onLog("AI 待发送事务[SCHEDULER_UNAVAILABLE]：保留 PENDING_SEND，等待下一次聊天观察")
        }
    }

    private fun updateSegmentStatus(requestGeneration: Long, segmentIndex: Int, status: ReplySegmentTaskStatus, sentAt: Long? = null, confirmationId: String? = null) {
        val requestId = synchronized(lock) { activeRequestId } ?: return
        val repo = replySegmentTaskRepository ?: return
        val record = repo.find("$requestId:segment:$segmentIndex") ?: return
        if (record.status != status) repo.update(record.copy(status = status, sentAt = sentAt ?: record.sentAt, confirmationId = confirmationId ?: record.confirmationId))
    }

    internal fun writeNext(target: ChatDraftInputTarget, draft: String, segments: List<String>,
                          index: Int, confirmed: String, requestGeneration: Long) {
        if (!isCurrentGeneration(requestGeneration)) return
        if (!isAiReplyActive()) {
            finish("AI_REPLY_STATE_CHANGED_BEFORE_INPUT", requestGeneration)
            return
        }
        val expected = confirmed + segments[index]
        onLog(
            "AI 草稿输入段：segmentIndex=$index；segmentCount=${segments.size}；" +
                "当前段字符数=${segments[index].codePointCount(0, segments[index].length)}；" +
                "requestGeneration=$requestGeneration"
        )
        val result = synchronized(lock) {
            if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive()) return
            runCatching { inputPort.writeDraft(target, confirmed, expected) }
                .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.ACTION_REJECTED) }
        }
        if (!result.succeeded) {
            val inputNotReady = result.status in setOf(
                ChatDraftInputStatus.STALE_CHAT,
                ChatDraftInputStatus.INPUT_NOT_FOUND,
                ChatDraftInputStatus.INPUT_NOT_EDITABLE,
                ChatDraftInputStatus.ACTION_REJECTED
            )
            onLog(
                if (inputNotReady) {
                    "AI 草稿状态：INPUT_BOX_NOT_READY；输入框未就绪；中文原因=${result.reasonCode}"
                } else {
                    "AI 草稿状态：LOCAL_DRAFT_VALIDATION_REJECTED；本地草稿校验未通过；中文原因=${result.reasonCode}"
                }
            )
            finish("DRAFT_INPUT_${result.reasonCode}", requestGeneration)
            return
        }
        val dwell = chooseRandomInt(MIN_VERIFY_DWELL_DELAY_MS, MAX_VERIFY_DWELL_DELAY_MS)
            .coerceIn(MIN_VERIFY_DWELL_DELAY_MS, MAX_VERIFY_DWELL_DELAY_MS)
        scheduler.schedule(dwell.toLong()) {
            if (!isCurrentGeneration(requestGeneration)) return@schedule
            if (!isAiReplyActive()) {
                finish("AI_REPLY_STATE_CHANGED_BEFORE_VERIFY", requestGeneration)
                return@schedule
            }
            verifySegment(
                target = target,
                draft = draft,
                segments = segments,
                index = index,
                expected = expected,
                requestGeneration = requestGeneration,
                attempt = 0
            )
        }
    }

    private fun verifySegment(
        target: ChatDraftInputTarget,
        draft: String,
        segments: List<String>,
        index: Int,
        expected: String,
        requestGeneration: Long,
        attempt: Int
    ) {
        if (!isCurrentGeneration(requestGeneration)) return
        if (!isAiReplyActive()) {
            finish("AI_REPLY_STATE_CHANGED_BEFORE_VERIFY", requestGeneration)
            return
        }
        val checked = runCatching { inputPort.verifyDraft(target, expected) }
            .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.VERIFICATION_FAILED) }
        if (checked.status == ChatDraftInputStatus.VERIFIED) {
            if (index + 1 < segments.size) {
                writeNext(target, draft, segments, index + 1, expected, requestGeneration)
            } else {
                verifyAndFinish(target, draft, segments, index, requestGeneration)
            }
            return
        }

        if (attempt < MAX_RECOVERY_VERIFY_RETRIES && isTransientVerificationFailure(checked.status)) {
            val nextAttempt = attempt + 1
            onLog(
                "AI 草稿复核暂未完成：${checked.reasonCode}；" +
                    "将在 ${RECOVERY_VERIFY_RETRY_DELAY_MS}ms 后进行第 ${nextAttempt + 1} 次复核；" +
                    "当前段不推进"
            )
            scheduler.schedule(RECOVERY_VERIFY_RETRY_DELAY_MS) {
                verifySegment(
                    target, draft, segments, index, expected, requestGeneration, nextAttempt
                )
            }
            return
        }

        onLog(
            "AI 草稿状态：LOCAL_DRAFT_VALIDATION_REJECTED；本地回读校验未通过；" +
                "中文原因=${checked.reasonCode}；复核次数=${attempt + 1}；安全停止"
        )
        finish("DRAFT_VERIFY_${checked.reasonCode}", requestGeneration)
    }

    private fun isTransientVerificationFailure(status: ChatDraftInputStatus): Boolean =
        status in setOf(
            // 输入节点缺失或发生变化时，必须重新获得工作流准入许可；
            // 直接重试旧草稿回调可能误操作已过期的会话。
            ChatDraftInputStatus.VERIFICATION_FAILED
        )

    private fun verifyAndFinish(
        target: ChatDraftInputTarget,
        draft: String,
        segments: List<String>,
        segmentIndex: Int,
        requestGeneration: Long
    ) {
        if (!isCurrentGeneration(requestGeneration)) return
        if (!isAiReplyActive()) {
            finish("AI_REPLY_STATE_CHANGED_BEFORE_VERIFY", requestGeneration)
            return
        }
        val result = runCatching { inputPort.verifyDraft(target, draft) }
            .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.VERIFICATION_FAILED) }
        if (result.status == ChatDraftInputStatus.VERIFIED) {
            // 第二阶段处理 PENDING_SEND；只有适配器重新确认当前聊天页及发送控件后，
            // 才能调用已有的发送端口。
            synchronized(lock) {
                if (!isCurrentGeneration(requestGeneration)) return
                awaitingUserSend.set(true)
            }
            updateTaskStatus(ReplyTaskStatus.DRAFT_APPLIED, requestGeneration)
            updateTaskStatus(ReplyTaskStatus.PENDING_SEND, requestGeneration)
            val configuredSendPort = sendPort
            if (configuredSendPort == null) {
                awaitingUserSend.set(true)
                onLog("AI 草稿状态：PENDING_SEND；发送端口不可用；保留当前草稿等待人工处理")
                finish("DRAFT_ENTERED_USER_CONTENT_REVIEW_AND_SEND_REQUIRED", requestGeneration)
                return
            }
            updateTaskStatus(ReplyTaskStatus.SENDING, requestGeneration)
            awaitingUserSend.set(true)
            if (!isCurrentGeneration(requestGeneration) || !hasActiveTaskOwner()) {
                updateTaskStatus(ReplyTaskStatus.FAILED_FINAL, requestGeneration)
                onLog("AI 回复状态：发送前任务校验失败；status=FAILED_FINAL；reason=REQUEST_GENERATION_INVALID")
                finish("DRAFT_SEND_REQUEST_GENERATION_INVALID", requestGeneration)
                return
            }
            val sendAttemptId = UUID.randomUUID().toString()
            val completion = sendCompletionObservation(sendAttemptId, requestGeneration)
            val sent = runCatching { configuredSendPort.sendDraft(target, draft, completion) }
                .getOrElse {
                    ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "SEND_PORT_EXCEPTION")
                }
            if (sent.status == ChatDraftInputStatus.SEND_SUBMITTED) {
                val submittedAt = nowMillis()
                synchronized(lock) {
                    pendingSendConfirmation = PendingSendConfirmation(
                        sendAttemptId = sendAttemptId,
                        requestId = activeRequestId.orEmpty(),
                        conversationKey = target.conversationIdentityKey,
                        segmentIndex = segmentIndex,
                        segmentCount = segments.size,
                        segmentContentHash = fingerprint(draft),
                        submittedAt = submittedAt,
                        confirmationDeadline = submittedAt + SEND_CONFIRMATION_TIMEOUT_MILLIS,
                        requestGeneration = requestGeneration,
                        target = target,
                        draft = draft,
                        existingOutgoingKeys = activeExistingOutgoingKeys
                    )
                    sendConfirmationChecks = 0
                }
                synchronized(lock) { pendingSendConfirmation }?.let {
                    recordSendTrace("MESSAGE_SEND_SUBMITTED", it, "SENT_UNCONFIRMED")
                }
                onLog("AI 回复状态：SENT_UNCONFIRMED；发送按钮手势已被接受；" +
                    "不代表 Momo 已产生己方消息；等待后续结果核验；" +
                    "sendAttemptId=${sendAttemptId.take(12)}；requestId=${activeRequestId?.take(12)}")
                pending.set(false)
            } else {
                val finalFailure = sent.status in setOf(
                    ChatDraftInputStatus.SEND_CONTROL_NOT_FOUND,
                    ChatDraftInputStatus.SEND_CONTROL_AMBIGUOUS,
                    ChatDraftInputStatus.SEND_PRECONDITION_FAILED,
                    ChatDraftInputStatus.SEND_GESTURE_REJECTED
                ) && sent.reasonCode in setOf(
                    "CHAT_IDENTITY_CONFLICT",
                    "TASK_OWNERSHIP_LOST",
                    "REQUEST_GENERATION_INVALID",
                    "DRAFT_CHANGED_BEFORE_SEND",
                    "SEND_CONTROL_AMBIGUOUS",
                    "SEND_CONTROL_BOUNDS_INVALID"
                )
                val failureStatus = if (finalFailure) ReplyTaskStatus.FAILED_FINAL else ReplyTaskStatus.FAILED_RETRYABLE
                updateTaskStatus(failureStatus, requestGeneration)
                recordSendTrace(
                    "MESSAGE_SEND_REJECTED", synchronized(lock) { activeRequestId }.orEmpty(),
                    requestGeneration, segmentIndex, segments.size,
                    sendStatus = "NOT_SUBMITTED", reasonCode = sent.status.name
                )
                onLog("AI 回复状态：发送手势未提交；status=${failureStatus.name}；reason=${sent.reasonCode}")
                finish("DRAFT_SEND_${sent.reasonCode}", requestGeneration)
            }
        } else {
            onLog(
                "AI 草稿状态：LOCAL_DRAFT_VALIDATION_REJECTED；最终回读校验未通过；" +
                    "中文原因=${result.reasonCode}"
            )
            finish("DRAFT_VERIFY_${result.reasonCode}", requestGeneration)
        }
    }

    private fun isCurrentGeneration(requestGeneration: Long): Boolean =
        generation.get() == requestGeneration

    private fun hasActiveTaskOwner(): Boolean = synchronized(lock) {
        activeMessageKey != null && activeRequestId != null
    }

    /**
     * Rehydrate only an uncertain, already-attempted send. Never recreate input chunks
     * or invoke the send port during recovery. Legacy records without a pre-send
     * baseline remain unresolved rather than accepting a same-text historical bubble.
     */
    private fun restoreUncertainSendConfirmation(
        batch: ChatMessageBatch, snapshot: ChatPageSnapshot
    ): SendConfirmationResult {
        val conversationKey = snapshot.conversationIdentityKey() ?: return SendConfirmationResult.NOT_PENDING
        val repository = replySegmentTaskRepository ?: return SendConfirmationResult.NOT_PENDING
        val all = runCatching { repository.findByConversation(conversationKey) }.getOrNull()
            ?: return SendConfirmationResult.UNKNOWN
        val uncertain = all.filter {
            it.status in setOf(
                ReplySegmentTaskStatus.SENDING, ReplySegmentTaskStatus.SENT_UNCONFIRMED,
                ReplySegmentTaskStatus.UNKNOWN
            )
        }
        if (uncertain.isEmpty()) return SendConfirmationResult.NOT_PENDING
        synchronized(lock) { replyAwaitingConfirmedSend = true }
        val candidate = uncertain.singleOrNull()
        val segments = candidate?.let { current ->
            all.filter { it.requestId == current.requestId }.sortedBy { it.segmentIndex }
        }
        val startedAt = candidate?.sendAttemptStartedAt
        val requestKey = candidate?.sendRequestKey
        val targetNode = snapshot.inputFieldTarget
        val windowIdentity = snapshot.windowIdentityKey()
        val session = targetNode?.window
        val currentWindowKey = if (session == null) null
            else "${session.packageName}|${session.windowId}|${session.generation}|conversation=$conversationKey"
        val valid = candidate != null && segments != null && startedAt != null &&
            !requestKey.isNullOrBlank() && candidate.sendChatTitleHash != null &&
            candidate.sendWindowSessionKey?.endsWith("|conversation=$conversationKey") == true &&
            snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            snapshot.packageName.isNotBlank() && targetNode != null &&
            windowIdentity != null && batch.windowSessionKey == currentWindowKey &&
            batch.capturedAtMillis > startedAt &&
            fingerprint(normalizeTitle(snapshot.chatTitleText)) == candidate.sendChatTitleHash &&
            segments.size == candidate.segmentCount &&
            segments.indices.all { index ->
                val record = segments[index]
                record.segmentIndex == index && record.segmentCount == candidate.segmentCount &&
                    record.segmentContent.isNotBlank() && fingerprint(record.segmentContent) == record.segmentContentHash &&
                    (when {
                        index < candidate.segmentIndex -> record.status == ReplySegmentTaskStatus.SENT
                        index == candidate.segmentIndex -> record.status == candidate.status
                        else -> record.status == ReplySegmentTaskStatus.CREATED
                    })
            } &&
            replyTaskRepository?.find(requestKey)?.let {
                it.requestId == candidate.requestId && it.conversationKey == conversationKey &&
                    it.status in setOf(
                        ReplyTaskStatus.PENDING_SEND, ReplyTaskStatus.SENDING,
                        ReplyTaskStatus.SENT_UNCONFIRMED, ReplyTaskStatus.UNKNOWN
                    )
            } == true &&
            isAiReplyActive()
        if (!valid || candidate == null || segments == null || targetNode == null ||
            windowIdentity == null || currentWindowKey == null || requestKey == null || startedAt == null
        ) {
            candidate?.let {
                recordSendTrace(
                    "MESSAGE_SEND_UNCERTAIN", it.requestId, generation.get(),
                    it.segmentIndex, it.segmentCount, sendStatus = "UNKNOWN",
                    reasonCode = "RECOVERY_EVIDENCE_INSUFFICIENT", recoveredFromLedger = true
                )
            }
            onLog("AI 发送只读恢复[UNRESOLVED_EVIDENCE]：原发送基线、身份、持久化队列或新鲜页面证据不足；禁止重发")
            return SendConfirmationResult.UNKNOWN
        }
        val target = ChatDraftInputTarget(
            packageName = snapshot.packageName,
            activityClass = snapshot.activityClass,
            windowIdentityKey = windowIdentity,
            conversationIdentityKey = conversationKey,
            windowSessionKey = currentWindowKey,
            nodeTarget = targetNode,
            expectedChatTitle = snapshot.chatTitleText,
            taskLease = captureTaskLease()
        )
        synchronized(lock) {
            if (pendingSendConfirmation != null || pendingNextMessageSegment != null ||
                pending.get() || awaitingUserSend.get()
            ) return SendConfirmationResult.WAITING
            activeRequestId = candidate.requestId
            activeMessageKey = requestKey
            activeMessageSegments = segments.map { it.segmentContent }
            activeExistingOutgoingKeys = emptySet()
            replyAwaitingConfirmedSend = true
            readOnlyRecoveredMatch = null
            pendingSendConfirmation = PendingSendConfirmation(
                sendAttemptId = fingerprint("${candidate.requestId}|${candidate.segmentIndex}|READ_ONLY"),
                requestId = candidate.requestId,
                conversationKey = conversationKey,
                segmentIndex = candidate.segmentIndex,
                segmentCount = candidate.segmentCount,
                segmentContentHash = candidate.segmentContentHash,
                submittedAt = startedAt,
                confirmationDeadline = nowMillis() + SEND_CONFIRMATION_TIMEOUT_MILLIS,
                requestGeneration = generation.get(),
                target = target,
                draft = candidate.segmentContent,
                existingOutgoingKeys = emptySet(),
                previousOutgoingKeyHashes = candidate.preSendOutgoingKeyHashes,
                recoveredFromLedger = true
            )
            sendConfirmationChecks = 0
        }
        synchronized(lock) { pendingSendConfirmation }?.let {
            recordSendTrace(
                "MESSAGE_SEND_REHYDRATED", it, "WAITING",
                reasonCode = "READ_ONLY_RECOVERY"
            )
        }
        onLog("AI 发送只读恢复[REHYDRATED]：requestId=${candidate.requestId.take(12)}；" +
            "segmentIndex=${candidate.segmentIndex}；仅核验新鲜己方气泡，不重新输入或发送")
        return SendConfirmationResult.WAITING
    }

    fun confirmSendResult(batch: ChatMessageBatch, snapshot: ChatPageSnapshot): SendConfirmationResult {
        val pending = synchronized(lock) { pendingSendConfirmation }
        if (pending == null) {
            val next = synchronized(lock) { pendingNextMessageSegment }
            if (next != null) return resumeConfirmedNextMessageSegment(batch, snapshot, next)
            return restoreUncertainSendConfirmation(batch, snapshot)
        }
        if (generation.get() != pending.requestGeneration) {
            recordSendTrace(
                "MESSAGE_SEND_UNCERTAIN", pending, "UNKNOWN", "GENERATION_CHANGED"
            )
            onLog("AI 发送结果核验：UNKNOWN；运行代次已失效；禁止重发")
            updateTaskStatus(ReplyTaskStatus.UNKNOWN, pending.requestGeneration)
            updateSegmentStatus(pending.requestGeneration, pending.segmentIndex, ReplySegmentTaskStatus.UNKNOWN)
            return SendConfirmationResult.UNKNOWN
        }
        val observationMatches = batch.windowSessionKey == pending.target.windowSessionKey &&
            snapshot.packageName == pending.target.packageName &&
            snapshot.conversationIdentityKey() == pending.target.conversationIdentityKey &&
            normalizeTitle(snapshot.chatTitleText) == normalizeTitle(pending.target.expectedChatTitle) &&
            batch.capturedAtMillis >= pending.submittedAt
        if (!observationMatches) {
            // A reconstructed send requires consecutive, independently fresh proof.
            // A wrong page/window observation invalidates the first proof candidate.
            if (pending.recoveredFromLedger) synchronized(lock) { readOnlyRecoveredMatch = null }
            val checks = synchronized(lock) { ++sendConfirmationChecks }
            if (checks >= SEND_CONFIRMATION_MAX_CHECKS) {
                recordSendTrace(
                    "MESSAGE_SEND_UNCERTAIN", pending, "UNKNOWN", "CHAT_OBSERVATION_MISMATCH"
                )
                onLog("AI 发送结果核验：UNKNOWN；窗口、会话标题或时间条件连续不匹配；禁止重发")
                updateTaskStatus(ReplyTaskStatus.UNKNOWN, pending.requestGeneration)
                updateSegmentStatus(pending.requestGeneration, pending.segmentIndex, ReplySegmentTaskStatus.UNKNOWN)
                return SendConfirmationResult.UNKNOWN
            }
            onLog("AI 发送结果核验：WAITING；当前批次不属于发送后的稳定聊天观察；检查次数=$checks")
            return SendConfirmationResult.WAITING
        }
        val greetingTransaction = synchronized(lock) {
            activeTaskId == AiDecisionTaskIds.RECEIVED_GREETINGS
        }
        val eligibleMatches = batch.messages.filter { message ->
            message.direction == ChatMessageDirection.OUTGOING &&
                messageKey(message.stableLogicalMessageId, message.effectiveContentRevision) !in pending.existingOutgoingKeys &&
                fingerprint(messageKey(message.stableLogicalMessageId, message.effectiveContentRevision)) !in pending.previousOutgoingKeyHashes
                && (greetingTransaction || (
                    message.contentQuality == MessageContentQuality.VERIFIED &&
                    fingerprint(message.content.orEmpty()) == pending.segmentContentHash
                ))
        }
        // A reconstructed transaction needs a single, stable bubble on two fresh reads;
        // a matching string on one OCR pass is never sufficient proof.
        val matched = if (pending.recoveredFromLedger) eligibleMatches.singleOrNull() else eligibleMatches.firstOrNull()
        if (matched != null) {
            if (pending.recoveredFromLedger) {
                val matchKey = messageKey(matched.stableLogicalMessageId, matched.effectiveContentRevision)
                val prior = synchronized(lock) { readOnlyRecoveredMatch }
                if (prior == null || prior.requestId != pending.requestId ||
                    prior.segmentIndex != pending.segmentIndex ||
                    prior.messageKey != matchKey || batch.capturedAtMillis <= prior.observedAtMillis
                ) {
                    synchronized(lock) {
                        readOnlyRecoveredMatch = ReadOnlyRecoveredMatch(
                            pending.requestId, pending.segmentIndex, matchKey, batch.capturedAtMillis
                        )
                    }
                    onLog("AI 发送只读恢复[CANDIDATE_SEEN]：等待第二次新鲜同一消息确认；禁止重发")
                    return SendConfirmationResult.WAITING
                }
            }
            val confirmationId = fingerprint("${pending.requestId}|${pending.segmentIndex}|${pending.segmentContentHash}|${matched.stableLogicalMessageId}|${matched.effectiveContentRevision}")
            if (updateSegmentStatusAndContinue(pending, confirmationId)) {
                recordSendTrace(
                    "MESSAGE_SEND_CONFIRMED", pending, "CONFIRMED",
                    sendConfirmationId = confirmationId
                )
                onLog("AI 发送结果核验：SENT；sendConfirmationId=${confirmationId.take(12)}；" +
                    "sendAttemptId=${pending.sendAttemptId.take(12)}")
                val nextIndex = pending.segmentIndex + 1
                synchronized(lock) {
                    pendingSendConfirmation = null
                    awaitingUserSend.set(false)
                    // A later segment with identical text must never match this earlier bubble.
                    activeExistingOutgoingKeys = if (pending.recoveredFromLedger) {
                        batch.messages.filter { it.direction == ChatMessageDirection.OUTGOING }
                            .mapTo(LinkedHashSet()) { messageKey(it.stableLogicalMessageId, it.effectiveContentRevision) }
                    } else activeExistingOutgoingKeys +
                        messageKey(matched.stableLogicalMessageId, matched.effectiveContentRevision)
                    readOnlyRecoveredMatch = null
                    if (nextIndex < activeMessageSegments.size) {
                        pendingNextMessageSegment = PendingNextMessageSegment(
                            pending.requestId, nextIndex, pending.requestGeneration,
                            batch.capturedAtMillis, pending.target
                        )
                    }
                }
                if (nextIndex < activeMessageSegments.size) {
                    onLog("AI 消息段顺序推进[AWAITING_FRESH_CHAT]：segmentIndex=${pending.segmentIndex} 已确认；" +
                        "等待新鲜聊天与空输入框复核后启动 segmentIndex=$nextIndex")
                    return SendConfirmationResult.WAITING
                }
                val allSegmentsSent = replySegmentTaskRepository?.findByRequest(pending.requestId)
                    ?.let { records ->
                        records.size == pending.segmentCount && records.all { it.status == ReplySegmentTaskStatus.SENT }
                    } ?: true
                if (!allSegmentsSent) {
                    onLog("AI 发送结果核验：整体任务完成门禁未通过；仍存在未确认消息段；禁止返回列表")
                    return SendConfirmationResult.WAITING
                }
                lastConfirmedSendId = confirmationId
                updateTaskStatus(ReplyTaskStatus.SENT, pending.requestGeneration)
                recordSendTrace(
                    "MESSAGE_SEND_COMPLETED", pending, "CONFIRMED",
                    reasonCode = "ALL_SEGMENTS_CONFIRMED", sendConfirmationId = confirmationId
                )
                synchronized(lock) {
                    activeMessageKey = null
                    activeRequestId = null
                    activeExistingOutgoingKeys = emptySet()
                    replyAwaitingConfirmedSend = false
                }
                return SendConfirmationResult.CONFIRMED
            }
            recordSendTrace(
                "MESSAGE_SEND_UNCERTAIN", pending, "UNKNOWN", "SEGMENT_PERSIST_REJECTED"
            )
            onLog("AI 发送结果核验[SEGMENT_PERSIST_REJECTED]：消息段状态持久化未确认；禁止推进下一条")
            return SendConfirmationResult.UNKNOWN
        }
        // An OCR miss, ambiguous match or changed bubble breaks the consecutive-read
        // chain. It is not a failed send and must never reopen the send port.
        if (pending.recoveredFromLedger) synchronized(lock) { readOnlyRecoveredMatch = null }
        val checks = synchronized(lock) { ++sendConfirmationChecks }
        if (checks >= SEND_CONFIRMATION_MAX_CHECKS || nowMillis() >= pending.confirmationDeadline) {
            updateTaskStatus(ReplyTaskStatus.UNKNOWN, pending.requestGeneration)
            updateSegmentStatus(pending.requestGeneration, pending.segmentIndex, ReplySegmentTaskStatus.UNKNOWN)
            recordSendTrace(
                "MESSAGE_SEND_UNCERTAIN", pending, "UNKNOWN", "CONFIRMATION_EVIDENCE_UNAVAILABLE"
            )
            onLog("AI 发送结果核验：UNKNOWN；检查次数=$checks；未发现新的己方消息；禁止立即重发")
            return SendConfirmationResult.UNKNOWN
        }
        onLog("AI 发送结果核验：WAITING；检查次数=$checks；等待下一次当前聊天消息观察")
        return SendConfirmationResult.WAITING
    }

    /**
     * A previous SENT confirmation authorizes only a read-only look for the next composer.
     * No old input node or scheduled gesture is reused across independently sent messages.
     */
    private fun resumeConfirmedNextMessageSegment(
        batch: ChatMessageBatch,
        snapshot: ChatPageSnapshot,
        next: PendingNextMessageSegment
    ): SendConfirmationResult {
        if (!isCurrentGeneration(next.requestGeneration) || !isAiReplyActive() ||
            !hasActiveTaskOwner() || synchronized(lock) { activeRequestId != next.requestId }
        ) {
            onLog("AI 消息段推进[STALE_REQUEST]：发送任务或运行代次已变化；不提交后续消息")
            return SendConfirmationResult.UNKNOWN
        }
        val targetBefore = next.previousTarget
        val currentInput = snapshot.inputFieldTarget
        val currentWindowIdentity = snapshot.windowIdentityKey()
        val sameChat = snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            snapshot.packageName == targetBefore.packageName &&
            snapshot.activityClass == targetBefore.activityClass &&
            snapshot.conversationIdentityKey() == targetBefore.conversationIdentityKey &&
            normalizeTitle(snapshot.chatTitleText) == normalizeTitle(targetBefore.expectedChatTitle) &&
            currentWindowIdentity == targetBefore.windowIdentityKey &&
            batch.windowSessionKey == targetBefore.windowSessionKey &&
            batch.capturedAtMillis > next.confirmedObservationAtMillis &&
            currentInput != null &&
            currentInput.window == targetBefore.nodeTarget.window
        if (!sameChat || currentInput == null || currentWindowIdentity == null) {
            onLog("AI 消息段推进[CHAT_REVALIDATION_WAIT]：尚无发送确认后的同窗口新鲜聊天与输入框证据；不发送")
            return SendConfirmationResult.WAITING
        }
        val target = targetBefore.copy(windowIdentityKey = currentWindowIdentity, nodeTarget = currentInput)
        val repo = replySegmentTaskRepository
        if (repo != null) {
            val records = repo.findByRequest(next.requestId).sortedBy { it.segmentIndex }
            val segment = records.getOrNull(next.segmentIndex)
            val valid = records.size == activeMessageSegments.size &&
                records.indices.all { records[it].segmentIndex == it && records[it].requestId == next.requestId } &&
                records.take(next.segmentIndex).all { it.status == ReplySegmentTaskStatus.SENT } &&
                segment?.status == ReplySegmentTaskStatus.CREATED &&
                segment?.segmentContentHash == activeMessageSegments.getOrNull(next.segmentIndex)?.let(::fingerprint)
            if (!valid) {
                onLog("AI 消息段推进[CHECKPOINT_CONFLICT]：持久化队列与待发送段不一致；禁止下一条发送")
                return SendConfirmationResult.UNKNOWN
            }
        }
        if (runCatching { inputPort.verifyDraft(target, "") }.getOrNull()?.status != ChatDraftInputStatus.VERIFIED) {
            onLog("AI 消息段推进[COMPOSER_NOT_READY]：新输入框不为空或无法确认；不覆盖、不发送")
            return SendConfirmationResult.WAITING
        }
        val reserved = synchronized(lock) {
            if (pendingNextMessageSegment != next || pendingSendConfirmation != null ||
                activeRequestId != next.requestId || !isCurrentGeneration(next.requestGeneration)) false
            else {
                pendingNextMessageSegment = null
                pending.set(true)
                true
            }
        }
        if (!reserved) return SendConfirmationResult.WAITING
        val scheduled = runCatching {
            scheduler.schedule(0L) {
                if (!isCurrentGeneration(next.requestGeneration) || !isAiReplyActive() || !hasActiveTaskOwner()) return@schedule
                writeMessageSegment(target, next.segmentIndex, next.requestGeneration)
            }
        }.isSuccess
        if (!scheduled) {
            synchronized(lock) {
                if (activeRequestId == next.requestId && isCurrentGeneration(next.requestGeneration)) {
                    pendingNextMessageSegment = next
                    pending.set(false)
                }
            }
            onLog("AI 消息段推进[SCHEDULER_UNAVAILABLE]：保留未发送段，等待后续聊天观察")
            return SendConfirmationResult.WAITING
        }
        onLog("AI 消息段推进[NEXT_SEGMENT_STARTED]：segmentIndex=${next.segmentIndex}；" +
            "新鲜页面和空输入框已验证，按既有 DraftSegments 节奏输入")
        return SendConfirmationResult.WAITING
    }

    /** A repository rejection is not a confirmed send and must never advance the queue. */
    private fun updateSegmentStatusAndContinue(pending: PendingSendConfirmation, confirmationId: String): Boolean {
        if (!isCurrentGeneration(pending.requestGeneration)) return false
        val repo = replySegmentTaskRepository ?: return true
        val record = repo.find("${pending.requestId}:segment:${pending.segmentIndex}") ?: return false
        if (record.requestId != pending.requestId || record.segmentIndex != pending.segmentIndex ||
            record.segmentContentHash != pending.segmentContentHash
        ) return false
        if (record.status == ReplySegmentTaskStatus.SENT) return record.confirmationId == confirmationId
        if (record.status !in setOf(
                ReplySegmentTaskStatus.SENT_UNCONFIRMED, ReplySegmentTaskStatus.UNKNOWN,
                ReplySegmentTaskStatus.SENDING
            )) return false
        if (!repo.update(record.copy(status = ReplySegmentTaskStatus.SENT,
                sentAt = nowMillis(), confirmationId = confirmationId))) return false
        val saved = repo.find(record.taskId)
        return saved?.status == ReplySegmentTaskStatus.SENT && saved.confirmationId == confirmationId
    }

    /**
     * The only outward-facing business send result: internal queue progress remains WAITING,
     * while the final confirmed segment yields one consumable transaction receipt. The
     * receipt is delivered alongside the final outcome, never as a separate later callback.
     */
    fun confirmSendTransaction(batch: ChatMessageBatch, snapshot: ChatPageSnapshot): ReplySendTransactionOutcome {
        val result = confirmSendResult(batch, snapshot)
        return ReplySendTransactionOutcome(
            status = result,
            finalSendConfirmationId = if (result == SendConfirmationResult.CONFIRMED) {
                consumeConfirmedSendId()
            } else null
        )
    }

    fun consumeConfirmedSendId(): String? = synchronized(lock) {
        lastConfirmedSendId.also { lastConfirmedSendId = null }
    }

    private fun finish(reasonCode: String, requestGeneration: Long) {
        var terminalLog: String? = null
        val technicalPreSendFailure = reasonCode in setOf(
            "DRAFT_GENERATION_FAILED", "BACKGROUND_EXECUTOR_REJECTED",
            "MODEL_DECISION_MODEL_CALL_FAILED", "MODEL_DECISION_EMPTY_MODEL_OUTPUT",
            "MODEL_DECISION_MODEL_OUTPUT_SCHEMA_INVALID"
        )
        val reclaimableFailure = technicalPreSendFailure ||
            reasonCode == "MODEL_EMPTY_RETRY_CONTEXT_CHANGED"
        val durableFailureRecorded = if (reclaimableFailure) {
            // An uncertain send is never reclaimed by a model-only failure.
            updateTaskStatus(ReplyTaskStatus.FAILED_RETRYABLE, requestGeneration)
        } else false
        val event = synchronized(lock) {
            if (!isCurrentGeneration(requestGeneration)) return
            val preSend = activeMessageSegments.isEmpty() &&
                pendingSendConfirmation == null && pendingNextMessageSegment == null &&
                pendingSendControl == null && !awaitingUserSend.get()
            if (preSend) replyAwaitingConfirmedSend = false
            pending.set(false)
            pendingSendControl = null
            sendControlRetryScheduled = false
            sendControlRetryTicket++
            val key = activeMessageKey
            val id = activeRequestId
            val originalTarget = activeDraftTarget
            val visitToken = activeDraftVisitToken
            activeMessageKey = null
            activeRequestId = null
            activeDraftTarget = null
            activeDraftVisitToken = null
            var attempts = 0
            if (key != null && (technicalPreSendFailure ||
                    reasonCode == "DRAFT_INPUT_CONFIRMED_EMPTY_RETRYABLE")) {
                attempts = (failedRequests[key]?.first ?: 0) + 1
                failedRequests[key] = attempts to (nowMillis() + MODEL_FAILURE_RETRY_DELAY_MS)
                while (failedRequests.size > MAX_PROCESSED_KEYS) failedRequests.remove(failedRequests.keys.first())
                processedMessageKeys.remove(key)
            }
            modelLifecycleTrace?.takeIf {
                it.requestGeneration == requestGeneration && !it.terminalLogged
            }?.let { trace ->
                trace.terminalLogged = true
                val cancelled = reasonCode == "MODEL_EMPTY_RETRY_CONTEXT_CHANGED" ||
                    reasonCode.startsWith("AI_REPLY_STATE_CHANGED")
                val retryState = when {
                    reasonCode == "MODEL_EMPTY_RETRY_CONTEXT_CHANGED" -> "CANCELLED_CONTEXT_CHANGED"
                    cancelled -> "CANCELLED_RUNTIME_INACTIVE"
                    trace.lastOutcomeCategory == "EMPTY_MODEL_OUTPUT" &&
                        trace.scheduledRetries == MAX_EMPTY_MODEL_RETRIES -> "EXHAUSTED"
                    else -> "NONE"
                }
                terminalLog = terminalLifecycleRecord(
                    trace, if (cancelled) "CANCELLED" else "FAILED", reasonCode, retryState
                )
            }
            if (technicalPreSendFailure && preSend && durableFailureRecorded &&
                key != null && id != null && originalTarget != null && visitToken != null
            ) AiReplyPreSendModelFailure(
                requestId = id,
                requestKey = key,
                conversationKey = originalTarget.conversationIdentityKey,
                windowSessionKey = originalTarget.windowSessionKey,
                visitToken = visitToken,
                taskLease = originalTarget.taskLease,
                reasonCode = reasonCode,
                attempts = attempts,
                retryDelayMs = MODEL_FAILURE_RETRY_DELAY_MS,
                readyToDefer = attempts >= MODEL_FAILURE_MAX_ATTEMPTS
            ) else null
        }
        terminalLog?.let { runCatching { onLog(it) } }
        onLog("AI 回复草稿处理结束：$reasonCode")
        if (event != null) {
            onLog("AI 模型失败交接[PRE_SEND_NO_GESTURE]：reason=${event.reasonCode}；" +
                "attempt=${event.attempts}/$MODEL_FAILURE_MAX_ATTEMPTS；defer=${event.readyToDefer}")
            runCatching { onPreSendModelFailure(event) }.onFailure {
                onLog("AI 模型失败交接[CALLBACK_REJECTED]：不执行页面操作")
            }
        }
    }

    /**
     * A greeting can be deferred only after the original request is durably retryable,
     * no segment was created, the request/lease still match, and the *fresh* composer is empty.
     * This verification does not read or write a draft or submit a gesture.
     */
    fun verifyGreetingPreSendFailure(
        failure: AiReplyPreSendModelFailure, snapshot: ChatPageSnapshot
    ): Boolean {
        if (!isAiReplyActive() || isConversationReturnBlocked || isSendConfirmationPending ||
            isSendControlRecoveryPending
        ) return false
        if (snapshot.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE ||
            snapshot.conversationIdentityKey() != failure.conversationKey ||
            snapshot.windowIdentityKey() == null ||
            snapshot.inputFieldTarget == null ||
            snapshot.packageName != failure.windowSessionKey.substringBefore('|')
        ) return false
        val window = snapshot.inputFieldTarget.window
        val sessionKey = "${window.packageName}|${window.windowId}|${window.generation}|conversation=${failure.conversationKey}"
        if (sessionKey != failure.windowSessionKey || captureTaskLease() != failure.taskLease) return false
        val owner = replyTaskRepository?.find(failure.requestKey) ?: return false
        if (owner.requestId != failure.requestId || owner.conversationKey != failure.conversationKey ||
            owner.status != ReplyTaskStatus.FAILED_RETRYABLE
        ) return false
        if (replySegmentTaskRepository?.findByRequest(failure.requestId)?.isNotEmpty() == true) return false
        val input = ChatDraftInputTarget(
            packageName = snapshot.packageName, activityClass = snapshot.activityClass,
            windowIdentityKey = snapshot.windowIdentityKey() ?: return false,
            conversationIdentityKey = failure.conversationKey,
            windowSessionKey = sessionKey,
            nodeTarget = snapshot.inputFieldTarget,
            expectedChatTitle = snapshot.chatTitleText,
            taskLease = failure.taskLease
        )
        return runCatching { inputPort.verifyDraft(input, "") }.getOrNull()?.status == ChatDraftInputStatus.VERIFIED
    }

    /**
     * Re-arm only a private technical, no-send failure after the existing cooldown.
     * The same durable FAILED_RETRYABLE owner, original lease, exact chat window and
     * a newly checked empty composer must pass the existing pre-send verifier.
     * This method never writes text, requests a model, or submits a send gesture.
     */
    fun rearmVerifiedPrivatePreSendFailure(
        failure: AiReplyPreSendModelFailure,
        snapshot: ChatPageSnapshot
    ): Boolean {
        if (!verifyGreetingPreSendFailure(failure, snapshot)) return false
        return synchronized(lock) {
            val previous = failedRequests[failure.requestKey] ?: return@synchronized false
            if (nowMillis() < previous.second || activeMessageKey != null ||
                pending.get() || awaitingUserSend.get() ||
                pendingSendConfirmation != null || pendingNextMessageSegment != null ||
                pendingSendControl != null
            ) return@synchronized false
            failedRequests.remove(failure.requestKey)
            processedMessageKeys.remove(failure.requestKey)
            onLog("AI 私聊模型失败[VERIFIED_REARM]：原失败任务与空输入框已复核；允许新鲜 OCR 再次申请模型")
            true
        }
    }

    /** Called only after a lease-gated safe BACK accepts an unprocessed greeting deferral. */
    fun releaseDeferredGreetingModelFailure(failure: AiReplyPreSendModelFailure) {
        synchronized(lock) {
            if (activeMessageKey == failure.requestKey || pending.get() ||
                pendingSendConfirmation != null || pendingNextMessageSegment != null
            ) return
            failedRequests.remove(failure.requestKey)
            processedMessageKeys.remove(failure.requestKey)
        }
    }

    private fun updateTaskStatus(status: ReplyTaskStatus, requestGeneration: Long): Boolean {
        if (!isCurrentGeneration(requestGeneration)) return false
        val owner = synchronized(lock) { activeMessageKey to activeRequestId }
        val key = owner.first ?: return false
        val requestId = owner.second ?: return false
        return replyTaskRepository?.updateStatus(key, requestId, status, nowMillis()) ?: true
    }

    private fun reject(reasonCode: String): Boolean {
        onLog("AI 回复草稿未启动：$reasonCode")
        return false
    }

    /** Logging-only stable correlation label; never expose raw model free text or chat identity. */
    private fun diagnosticHash(value: String?): String =
        value?.takeIf(String::isNotBlank)?.let { fingerprint(it).take(12) } ?: "UNKNOWN"

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun messageKey(id: String, revision: String): String = "$id|$revision"

    private fun normalizeTitle(value: String?): String = value.orEmpty().trim().replace(Regex("\\s+"), " ")

    private fun ConversationContext.identityConversationMismatch(): Boolean =
        contactId.isBlank() || conversationId != "momo-direct-$contactId"

    companion object {
        private const val MAX_PROCESSED_KEYS = 256
        private const val MODEL_FAILURE_MAX_ATTEMPTS = 2
        private const val MODEL_FAILURE_RETRY_DELAY_MS = 30_000L
        private const val MIN_VERIFY_DWELL_DELAY_MS = 500
        private const val MAX_VERIFY_DWELL_DELAY_MS = 900
        private const val RECOVERY_VERIFY_RETRY_DELAY_MS = 5_000L
        private const val MAX_RECOVERY_VERIFY_RETRIES = 2
        private const val REPLY_TASK_REQUEST_TIMEOUT_MILLIS = 120_000L
        private const val SEND_CONFIRMATION_TIMEOUT_MILLIS = 8_000L
        private const val SEND_CONFIRMATION_MAX_CHECKS = 2
        private const val MAX_EMPTY_MODEL_RETRIES = 2
        private const val FIRST_EMPTY_MODEL_RETRY_DELAY_MS = 2_500L
        private const val SECOND_EMPTY_MODEL_RETRY_DELAY_MS = 6_000L

        private fun newDraftExecutor() = Executors.newSingleThreadExecutor(ThreadFactory { runnable ->
            Thread(runnable, "frdm-ai-model-request").apply { isDaemon = true }
        })
    }
}
