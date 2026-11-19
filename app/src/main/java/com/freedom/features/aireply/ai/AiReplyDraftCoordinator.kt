package com.freedom.features.aireply.ai

import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageDirection
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

/**
 * Calls the configured remote model, fills a verified composer, and admits automatic sending
 * only after the adapter revalidates the live conversation and send control.
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
    private val sendPort: ChatDraftSendPort? = null
) {
    private val pending = AtomicBoolean(false)
    private val awaitingUserSend = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private val processedMessageKeys = LinkedHashSet<String>()
    private val failedRequests = linkedMapOf<String, Pair<Int, Long>>()
    private var activeMessageKey: String? = null
    private var activeRequestId: String? = null

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
            nodeTarget = inputTarget
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
                    manualReviewRequired = isManualReviewRequired(),
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
            // 2-3-grapheme writes inside each queued message. Sending remains manual.
            val parsedMessages = ReplyMessageSegments.parse(decision.draft)
            onLog(
                "AI 回复解析诊断：requestId=$requestId；" +
                    "delimiterCount=${parsedMessages.delimiterCount}；" +
                    "lineBreakCount=${parsedMessages.lineBreakCount}；" +
                    "messageCount=${parsedMessages.messages.size}；" +
                    "messageLengths=${parsedMessages.messages.joinToString(",", prefix="[", postfix="]") { it.codePointCount(0, it.length).toString() }}；" +
                    "fallbackToSingleMessage=${parsedMessages.fallbackToSingleMessage}"
            )
            val segments = parsedMessages.messages.flatMap(DraftSegments::split)
            writeNext(target, decision.draft, segments, 0, "", requestGeneration)
        }
    }

    internal fun writeNext(target: ChatDraftInputTarget, draft: String, segments: List<String>,
                          index: Int, confirmed: String, requestGeneration: Long) {
        if (!isCurrentGeneration(requestGeneration)) return
        if (!isAiReplyActive() || isManualReviewRequired()) {
            finish("AI_REPLY_STATE_CHANGED_BEFORE_INPUT", requestGeneration)
            return
        }
        val expected = confirmed + segments[index]
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
                verifyAndFinish(target, draft, requestGeneration)
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

    private fun verifyAndFinish(target: ChatDraftInputTarget, draft: String, requestGeneration: Long) {
        if (!isCurrentGeneration(requestGeneration)) return
        if (!isAiReplyActive() || isManualReviewRequired()) {
            finish("AI_REPLY_STATE_CHANGED_BEFORE_VERIFY", requestGeneration)
            return
        }
        val result = runCatching { inputPort.verifyDraft(target, draft) }
            .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.VERIFICATION_FAILED) }
        if (result.status == ChatDraftInputStatus.VERIFIED) {
            // Capture the nullable dependency once so the compiler and the send decision use
            // the same configuration even though the actual send is executed inside a lambda.
            val configuredSendPort = sendPort
            synchronized(lock) {
                if (!isCurrentGeneration(requestGeneration)) return
                awaitingUserSend.set(configuredSendPort == null)
            }
            updateTaskStatus(ReplyTaskStatus.DRAFT_APPLIED, requestGeneration)
            if (configuredSendPort == null) {
                onLog("AI 草稿状态：DRAFT_ACCEPTED_AND_FILLED；草稿已接受并填入输入框；等待人工检查发送")
                finish("DRAFT_ENTERED_USER_CONTENT_REVIEW_AND_SEND_REQUIRED", requestGeneration)
                return
            }
            updateTaskStatus(ReplyTaskStatus.PENDING_SEND, requestGeneration)
            updateTaskStatus(ReplyTaskStatus.SENDING, requestGeneration)
            val sent = runCatching { configuredSendPort.sendDraft(target, draft) }
                .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED) }
            if (sent.status == ChatDraftInputStatus.SEND_SUBMITTED) {
                updateTaskStatus(ReplyTaskStatus.SENT_UNCONFIRMED, requestGeneration)
                onLog("AI 回复状态：SEND_SUBMITTED；已通过发送前校验并提交发送手势；等待消息结果核验")
                finish("DRAFT_SENT_WAITING_CONFIRMATION", requestGeneration)
            } else {
                updateTaskStatus(ReplyTaskStatus.FAILED_FINAL, requestGeneration)
                onLog("AI 回复状态：SEND_REJECTED；发送前校验或手势提交失败；reason=${sent.reasonCode}")
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
        onLog("AI 回复草稿处理结束：$reasonCode；未执行发送动作")
    }

    private fun updateTaskStatus(status: ReplyTaskStatus, requestGeneration: Long) {
        if (!isCurrentGeneration(requestGeneration)) return
        val owner = synchronized(lock) { activeMessageKey to activeRequestId }
        val key = owner.first ?: return
        val requestId = owner.second ?: return
        replyTaskRepository?.updateStatus(key, requestId, status, nowMillis())
    }

    private fun reject(reasonCode: String): Boolean {
        onLog("AI 回复草稿未启动：$reasonCode")
        return false
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun ConversationContext.identityConversationMismatch(): Boolean =
        contactId.isBlank() || conversationId != "momo-direct-$contactId"

    companion object {
        private const val MAX_PROCESSED_KEYS = 256
        private const val MIN_VERIFY_DWELL_DELAY_MS = 500
        private const val MAX_VERIFY_DWELL_DELAY_MS = 900
        private const val RECOVERY_VERIFY_RETRY_DELAY_MS = 5_000L
        private const val MAX_RECOVERY_VERIFY_RETRIES = 2
        private const val REPLY_TASK_REQUEST_TIMEOUT_MILLIS = 120_000L

        private fun newDraftExecutor() = Executors.newSingleThreadExecutor(ThreadFactory { runnable ->
            Thread(runnable, "frdm-ai-model-request").apply { isDaemon = true }
        })
    }
}
