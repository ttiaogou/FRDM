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
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import com.freedom.core.runtime.TaskScheduler
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class SendConfirmationResult {
    NOT_PENDING,
    WAITING,
    CONFIRMED,
    UNKNOWN
}

/**
 * Calls the configured remote model and fills a verified composer. Sending is deliberately
 * outside this phase; the next phase may consume the persisted PENDING_SEND state.
 */
class AiReplyDraftCoordinator(
    private val configurationRepository: AiModelConfigurationRepository,
    private val cloudConsent: AiModelCloudConsent,
    private val personalizationRepository: AiReplyPersonalizationRepository,
    private val inputPort: ChatDraftInputPort,
    private val scheduler: TaskScheduler,
    private val isAiReplyActive: () -> Boolean,
    private val isManualReviewRequired: () -> Boolean,
    private val onLog: (String) -> Unit,
    private val createProvider: (AiModelConfiguration) -> AiModelProvider,
    private val backgroundExecutor: Executor = newDraftExecutor(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val chooseRandomInt: (Int, Int) -> Int = { min, max ->
        java.util.concurrent.ThreadLocalRandom.current().nextInt(min, max + 1)
    },
    private val replyTaskRepository: ReplyTaskRepository? = null,
    private val sendPort: ChatDraftSendPort? = null,
    private val replySegmentTaskRepository: ReplySegmentTaskRepository? = null
) {
    private val pending = AtomicBoolean(false)
    private val awaitingUserSend = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private val processedMessageKeys = LinkedHashSet<String>()
    private val failedRequests = linkedMapOf<String, Pair<Int, Long>>()
    private var activeMessageKey: String? = null
    private var activeRequestId: String? = null
    private var activeExistingOutgoingKeys: Set<String> = emptySet()
    private var pendingSendConfirmation: PendingSendConfirmation? = null
    private var sendConfirmationChecks = 0
    private var lastConfirmedSendId: String? = null

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
        val existingOutgoingKeys: Set<String>
    )

    val isPending: Boolean get() = pending.get()
    val isAwaitingUserSend: Boolean get() = awaitingUserSend.get()

    /** Invalidates late callbacks when the AI Reply runtime is stopped or rebuilt. */
    fun resetForRuntimeReset() {
        synchronized(lock) {
            generation.incrementAndGet()
            pending.set(false)
            awaitingUserSend.set(false)
            processedMessageKeys.clear()
            failedRequests.clear()
            activeMessageKey = null
            activeRequestId = null
            activeExistingOutgoingKeys = emptySet()
            pendingSendConfirmation = null
            sendConfirmationChecks = 0
            lastConfirmedSendId = null
        }
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
                             history: VerifiedDraftHistory? = null): Boolean {
        val visible = VisibleDraftContext.build(batch, visitToken) ?: return reject("VISIBLE_DRAFT_CONTEXT_UNAVAILABLE")
        val context = DraftHistoryMerger.merge(visible, batch, history)
        return generateAndFill(batch, null, snapshot, context, visitToken)
    }

    /** Called only after platform identity and the current chat header have been verified. */
    fun generateAndFill(
        batch: ChatMessageBatch,
        identity: ContactRowIdentity?,
        snapshot: ChatPageSnapshot,
        context: ConversationContext,
        visitToken: String = batch.windowSessionKey.orEmpty()
    ): Boolean {
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
            (if (localDraft) VisibleDraftContext.eligible(it) else it.isAiInputEligible) && it.direction == ChatMessageDirection.INCOMING
        } ?: return reject("NO_VERIFIED_INCOMING_MESSAGE")
        if (context.messages.isEmpty() || context.messages.last().role != AiModelMessage.Role.USER ||
            context.messages.last().content != latestIncoming.content?.take(2_000)
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
            expectedChatTitle = snapshot.chatTitleText
        )
        // Window generation is a stale-callback guard, not a new model task identity.
        val dedupeConversationKey = conversationIdentity
        val dedupeKey = fingerprint("$contactId|$dedupeConversationKey|${latestIncoming.stableLogicalMessageId}|${latestIncoming.effectiveContentRevision}")
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
            activeRequestId = requestId
            activeExistingOutgoingKeys = batch.messages
                .filter { it.direction == ChatMessageDirection.OUTGOING }
                .mapTo(LinkedHashSet()) { messageKey(it.stableLogicalMessageId, it.effectiveContentRevision) }
            processedMessageKeys += dedupeKey
            while (processedMessageKeys.size > MAX_PROCESSED_KEYS) {
                val oldest = processedMessageKeys.firstOrNull() ?: break
                processedMessageKeys.remove(oldest)
            }
            pending.set(true)
            generation.get()
        }
        val accepted = runCatching {
            backgroundExecutor.execute {
                try {
                    generateOnBackground(configuration, context, target, requestGeneration, requestId)
                } catch (_: Throwable) {
                    onLog("AI 回复草稿生成失败：[DRAFT_GENERATION_FAILED]")
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
        requestId: String
    ) {
        if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive()) {
            finish("AI_REPLY_STATE_CHANGED_BEFORE_REQUEST", requestGeneration)
            return
        }
        val callStartedAt = nowMillis()
        onLog(
            "AI 模型调用开始：requestId=$requestId；" +
                "provider=${configuration.provider.name}；" +
                "model=${configuration.modelName}；" +
                "contextMessages=${context.messages.size}"
        )
        val decision = try {
            val provider = createProvider(configuration)
            AiReplyDecisionEngine(
                modelProvider = provider,
                cloudConsent = cloudConsent
            ).decide(
                AiReplyDecisionInput(
                    context = context,
                    contactIdentityVerified = !context.currentWindowDraft,
                    cooldownActive = false,
                    requestId = requestId,
                    personalization = personalizationRepository.load()
                )
            )
        } catch (error: Throwable) {
            onLog(
                "AI 模型调用异常：requestId=$requestId；" +
                    "stage=DECIDE；" +
                    "exception=${error.javaClass.simpleName}；" +
                    "elapsedMs=${(nowMillis() - callStartedAt).coerceAtLeast(0L)}"
            )
            throw error
        }
        onLog(
            "AI 模型调用完成：requestId=$requestId；" +
                "elapsedMs=${(nowMillis() - callStartedAt).coerceAtLeast(0L)}；" +
                "action=${decision.action.name}；" +
                "reason=${decision.reason}；" +
                "draftPresent=${!decision.draft.isNullOrBlank()}"
        )
        if (decision.action != AiReplyAction.REPLY || decision.draft.isNullOrBlank()) {
            fun safeLog(value: String?): String = AiModelDataRedactor.redact(value.orEmpty())
                .replace(Regex("[\\r\\n\\t\\p{Cntrl}]"), " ").take(1_000)
            decision.callDiagnostics?.let { diagnostics ->
                onLog("AI 模型调用失败：requestId=${safeLog(requestId)}；" +
                    "provider=${safeLog(configuration.provider.name)}；model=${safeLog(configuration.modelName)}；" +
                    "elapsedMs=${(nowMillis() - callStartedAt).coerceAtLeast(0L)}；${diagnostics.logFields()}")
            }
            val fallbackDetail = if (decision.callDiagnostics != null) {
                "本地捕获到模型调用异常；未取得可用回复；请查看同一 requestId 的调用失败日志"
            } else "模型未提供详细解释；请核查模型响应协议"
            onLog("AI 模型决策详情：requestId=$requestId；action=${decision.action.name}；" +
                "reason=${safeLog(decision.reason)}；reasonDetail=${safeLog(decision.reasonDetail ?: fallbackDetail)}；" +
                "provider=${safeLog(decision.provider)}；model=${safeLog(decision.model)}；" +
                "finishReason=${safeLog(decision.finishReason)}；contextMessages=${context.messages.size}；" +
                "inputTokens=${decision.usage?.inputTokens}；outputTokens=${decision.usage?.outputTokens}；" +
                "draftPresent=${!decision.draft.isNullOrBlank()}")
        }
        if (decision.reason == "MODEL_CALL_FAILED") {
            finish("MODEL_DECISION_MODEL_CALL_FAILED", requestGeneration)
            return
        }
        updateTaskStatus(ReplyTaskStatus.MODEL_COMPLETED, requestGeneration)
        if (decision.action != AiReplyAction.REPLY || decision.draft.isNullOrBlank()) {
            onLog(
                "AI 草稿状态：MODEL_DID_NOT_RETURN_DRAFT；模型未返回可用草稿；" +
                    "中文原因=${decision.reasonDetail ?: decision.reason}；" +
                    "action=${decision.action.name}；draftPresent=${!decision.draft.isNullOrBlank()}"
            )
            finish("MODEL_DECISION_${decision.reason}", requestGeneration)
            return
        }
        if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive()) {
            finish("AI_REPLY_STATE_CHANGED_AFTER_REQUEST", requestGeneration)
            return
        }
        scheduler.schedule(0L) {
            if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive() || isManualReviewRequired()) {
                finish("AI_REPLY_STATE_CHANGED_BEFORE_INPUT", requestGeneration)
                return@schedule
            }
            // The model owns message boundaries; the existing input cadence still owns
            // 2-3-grapheme writes inside each queued message. Stage one stops after the
            // complete draft is verified; it must not submit a send gesture.
            val parsedMessages = ReplyMessageSegments.parse(decision.draft)
            onLog(
                "AI 回复解析诊断：requestId=$requestId；" +
                    "delimiterCount=${parsedMessages.delimiterCount}；" +
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
            val segments = parsedMessages.messages.flatMap(DraftSegments::split)
            writeNext(target, decision.draft, segments, 0, "", requestGeneration)
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
        onLog("AI 消息段任务已建立：requestId=$requestId；conversationKey=$conversationKey；segmentCount=${records.size}；输入片段仍由 DraftSegments 独立处理")
    }

    internal fun writeNext(target: ChatDraftInputTarget, draft: String, segments: List<String>,
                          index: Int, confirmed: String, requestGeneration: Long) {
        if (!isCurrentGeneration(requestGeneration)) return
        if (!isAiReplyActive() || isManualReviewRequired()) {
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
            if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive() || isManualReviewRequired()) return
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
            if (!isAiReplyActive() || isManualReviewRequired()) {
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
        if (!isAiReplyActive() || isManualReviewRequired()) {
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
            // A missing or changed input node requires a fresh workflow admission;
            // retrying the same draft callback could target a stale conversation.
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
        if (!isAiReplyActive() || isManualReviewRequired()) {
            finish("AI_REPLY_STATE_CHANGED_BEFORE_VERIFY", requestGeneration)
            return
        }
        val result = runCatching { inputPort.verifyDraft(target, draft) }
            .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.VERIFICATION_FAILED) }
        if (result.status == ChatDraftInputStatus.VERIFIED) {
            // Stage two consumes PENDING_SEND and invokes the retained send port only after
            // the adapter has revalidated the live chat and send control.
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
            val sent = runCatching { configuredSendPort.sendDraft(target, draft) }
                .getOrElse {
                    ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "SEND_PORT_EXCEPTION")
                }
            if (sent.status == ChatDraftInputStatus.SEND_SUBMITTED) {
                val sendAttemptId = UUID.randomUUID().toString()
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

    fun confirmSendResult(batch: ChatMessageBatch, snapshot: ChatPageSnapshot): SendConfirmationResult {
        val pending = synchronized(lock) { pendingSendConfirmation } ?: return SendConfirmationResult.NOT_PENDING
        if (generation.get() != pending.requestGeneration) {
            onLog("AI 发送结果核验：UNKNOWN；运行代次已失效；禁止重发")
            updateTaskStatus(ReplyTaskStatus.UNKNOWN, pending.requestGeneration)
            return SendConfirmationResult.UNKNOWN
        }
        val observationMatches = batch.windowSessionKey == pending.target.windowSessionKey &&
            snapshot.packageName == pending.target.packageName &&
            snapshot.conversationIdentityKey() == pending.target.conversationIdentityKey &&
            normalizeTitle(snapshot.chatTitleText) == normalizeTitle(pending.target.expectedChatTitle) &&
            batch.capturedAtMillis >= pending.submittedAt
        if (!observationMatches) {
            val checks = synchronized(lock) { ++sendConfirmationChecks }
            if (checks >= SEND_CONFIRMATION_MAX_CHECKS) {
                onLog("AI 发送结果核验：UNKNOWN；窗口、会话标题或时间条件连续不匹配；禁止重发")
                updateTaskStatus(ReplyTaskStatus.UNKNOWN, pending.requestGeneration)
                return SendConfirmationResult.UNKNOWN
            }
            onLog("AI 发送结果核验：WAITING；当前批次不属于发送后的稳定聊天观察；检查次数=$checks")
            return SendConfirmationResult.WAITING
        }
        val matched = batch.messages.firstOrNull { message ->
            message.direction == ChatMessageDirection.OUTGOING &&
                message.contentQuality == MessageContentQuality.VERIFIED &&
                fingerprint(message.content.orEmpty()) == pending.segmentContentHash &&
                messageKey(message.stableLogicalMessageId, message.effectiveContentRevision) !in pending.existingOutgoingKeys
        }
        if (matched != null) {
            val confirmationId = fingerprint(
                "${pending.conversationKey}|${pending.requestId}|${pending.segmentIndex}|${matched.stableLogicalMessageId}"
            )
            if (updateTaskStatus(ReplyTaskStatus.SENT, pending.requestGeneration)) {
                onLog("AI 发送结果核验：SENT；sendConfirmationId=${confirmationId.take(12)}；" +
                    "sendAttemptId=${pending.sendAttemptId.take(12)}")
                synchronized(lock) {
                    lastConfirmedSendId = confirmationId
                    pendingSendConfirmation = null
                    activeMessageKey = null
                    activeRequestId = null
                    activeExistingOutgoingKeys = emptySet()
                    awaitingUserSend.set(false)
                }
                return SendConfirmationResult.CONFIRMED
            }
            return SendConfirmationResult.UNKNOWN
        }
        val checks = synchronized(lock) { ++sendConfirmationChecks }
        if (checks >= SEND_CONFIRMATION_MAX_CHECKS || nowMillis() >= pending.confirmationDeadline) {
            updateTaskStatus(ReplyTaskStatus.UNKNOWN, pending.requestGeneration)
            onLog("AI 发送结果核验：UNKNOWN；检查次数=$checks；未发现新的己方消息；禁止立即重发")
            return SendConfirmationResult.UNKNOWN
        }
        onLog("AI 发送结果核验：WAITING；检查次数=$checks；等待下一次当前聊天消息观察")
        return SendConfirmationResult.WAITING
    }

    fun consumeConfirmedSendId(): String? = synchronized(lock) {
        lastConfirmedSendId.also { lastConfirmedSendId = null }
    }

    private fun finish(reasonCode: String, requestGeneration: Long) {
        if (reasonCode in setOf("DRAFT_GENERATION_FAILED", "BACKGROUND_EXECUTOR_REJECTED", "MODEL_DECISION_MODEL_CALL_FAILED")) {
            updateTaskStatus(if (reasonCode == "MODEL_DECISION_MODEL_CALL_FAILED") ReplyTaskStatus.UNKNOWN else ReplyTaskStatus.FAILED_RETRYABLE, requestGeneration)
        }
        synchronized(lock) {
            if (!isCurrentGeneration(requestGeneration)) return
            pending.set(false)
            val key = activeMessageKey
            activeMessageKey = null
            activeRequestId = null
            // 仅模型/调度失败允许后续观察触发一次重试；输入过的草稿不得盲目重写。
            if (key != null && reasonCode in setOf("DRAFT_GENERATION_FAILED", "BACKGROUND_EXECUTOR_REJECTED",
                    "MODEL_DECISION_MODEL_CALL_FAILED", "MODEL_DECISION_EMPTY_MODEL_OUTPUT",
                    "MODEL_DECISION_MODEL_OUTPUT_SCHEMA_INVALID")) {
                val attempts = (failedRequests[key]?.first ?: 0) + 1
                failedRequests[key] = attempts to (nowMillis() + 30_000L)
                while (failedRequests.size > MAX_PROCESSED_KEYS) failedRequests.remove(failedRequests.keys.first())
                processedMessageKeys.remove(key)
            }
        }
        onLog("AI 回复草稿处理结束：$reasonCode")
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

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun messageKey(id: String, revision: String): String = "$id|$revision"

    private fun normalizeTitle(value: String?): String = value.orEmpty().trim().replace(Regex("\\s+"), " ")

    private fun ConversationContext.identityConversationMismatch(): Boolean =
        contactId.isBlank() || conversationId != "momo-direct-$contactId"

    companion object {
        private const val MAX_PROCESSED_KEYS = 256
        private const val MIN_VERIFY_DWELL_DELAY_MS = 500
        private const val MAX_VERIFY_DWELL_DELAY_MS = 900
        private const val RECOVERY_VERIFY_RETRY_DELAY_MS = 5_000L
        private const val MAX_RECOVERY_VERIFY_RETRIES = 2
        private const val REPLY_TASK_REQUEST_TIMEOUT_MILLIS = 120_000L
        private const val SEND_CONFIRMATION_TIMEOUT_MILLIS = 8_000L
        private const val SEND_CONFIRMATION_MAX_CHECKS = 2

        private fun newDraftExecutor() = Executors.newSingleThreadExecutor(ThreadFactory { runnable ->
            Thread(runnable, "frdm-ai-model-request").apply { isDaemon = true }
        })
    }
}
