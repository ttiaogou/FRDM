package com.freedom.features.aireply.ai

import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.ports.ChatDraftInputPort
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
 * Calls the configured remote model and fills only a verified, empty Momo chat input field.
 * Sending is intentionally outside this coordinator and remains a manual user action.
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
    private val chooseRandomInt: (Int, Int) -> Int = { min, max ->
        java.util.concurrent.ThreadLocalRandom.current().nextInt(min, max + 1)
    }
) {
    private val pending = AtomicBoolean(false)
    private val awaitingUserSend = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private val processedMessageKeys = LinkedHashSet<String>()

    val isPending: Boolean get() = pending.get()
    val isAwaitingUserSend: Boolean get() = awaitingUserSend.get()

    /** Invalidates late callbacks when the AI Reply runtime is stopped or rebuilt. */
    fun resetForRuntimeReset() {
        generation.incrementAndGet()
        awaitingUserSend.set(false)
        synchronized(lock) { processedMessageKeys.clear() }
    }

    /** Called only after platform identity and the current chat header have been verified. */
    fun generateAndFill(
        batch: ChatMessageBatch,
        identity: ContactRowIdentity,
        snapshot: ChatPageSnapshot,
        context: ConversationContext
    ): Boolean {
        val contactId = identity.contactId?.takeIf(String::isNotBlank) ?: return reject("CONTACT_ID_MISSING")
        if (identity.confidence != ContactIdentityConfidence.PLATFORM_ID_VERIFIED) {
            return reject("CONTACT_IDENTITY_UNVERIFIED")
        }
        if (context.contactId != contactId || context.identityConversationMismatch()) {
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
        val latestIncoming = batch.messages.firstOrNull {
            it.isAiInputEligible && it.direction == ChatMessageDirection.INCOMING
        } ?: return reject("NO_VERIFIED_INCOMING_MESSAGE")
        if (context.messages.isEmpty() || context.messages.last().role != AiModelMessage.Role.USER ||
            context.messages.last().content != latestIncoming.content
        ) return reject("LATEST_CONTEXT_MESSAGE_NOT_INCOMING")
        if (!context.hasVerifiedIncoming) return reject("NO_VERIFIED_INCOMING_CONTEXT")
        if (!isAiReplyActive()) return reject("AI_REPLY_NOT_ACTIVE")
        if (isManualReviewRequired()) return reject("MANUAL_REVIEW_REQUIRED")
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
        val dedupeKey = fingerprint("$contactId|$expectedWindowSessionKey|${latestIncoming.id}")
        synchronized(lock) {
            if (pending.get()) return reject("DRAFT_REQUEST_ALREADY_PENDING")
            if (dedupeKey in processedMessageKeys) return reject("DUPLICATE_INCOMING_MESSAGE")
            processedMessageKeys += dedupeKey
            while (processedMessageKeys.size > MAX_PROCESSED_KEYS) {
                val oldest = processedMessageKeys.firstOrNull() ?: break
                processedMessageKeys.remove(oldest)
            }
            pending.set(true)
        }

        val requestGeneration = generation.get()
        val accepted = runCatching {
            backgroundExecutor.execute {
                try {
                    generateOnBackground(configuration, context, target, requestGeneration)
                } catch (_: Throwable) {
                    onLog("AI 回复草稿生成失败：[DRAFT_GENERATION_FAILED]")
                    pending.set(false)
                }
            }
        }.isSuccess
        if (!accepted) {
            pending.set(false)
            return reject("BACKGROUND_EXECUTOR_REJECTED")
        }
        onLog("AI 回复草稿：已提交后台模型请求；不会自动发送")
        return true
    }

    private fun generateOnBackground(
        configuration: AiModelConfiguration,
        context: ConversationContext,
        target: ChatDraftInputTarget,
        requestGeneration: Long
    ) {
        if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive() || isManualReviewRequired()) {
            finish("AI_REPLY_STATE_CHANGED_BEFORE_REQUEST")
            return
        }
        val provider = createProvider(configuration)
        val decision = AiReplyDecisionEngine(
            modelProvider = provider,
            cloudConsent = cloudConsent
        ).decide(
            AiReplyDecisionInput(
                context = context,
                contactIdentityVerified = true,
                manualReviewRequired = isManualReviewRequired(),
                cooldownActive = false,
                requestId = UUID.randomUUID().toString(),
                personalization = personalizationRepository.load()
            )
        )
        if (decision.action != AiReplyAction.REPLY || decision.draft.isNullOrBlank()) {
            finish("MODEL_DECISION_${decision.reason}")
            return
        }
        if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive() || isManualReviewRequired()) {
            finish("AI_REPLY_STATE_CHANGED_AFTER_REQUEST")
            return
        }
        scheduler.schedule(0L) {
            if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive() || isManualReviewRequired()) {
                finish("AI_REPLY_STATE_CHANGED_BEFORE_INPUT")
                return@schedule
            }
            val result = runCatching { inputPort.writeDraft(target, decision.draft) }
                .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.ACTION_REJECTED) }
            if (!result.succeeded) {
                finish("DRAFT_INPUT_${result.reasonCode}")
                return@schedule
            }
            if (result.status == ChatDraftInputStatus.ALREADY_PRESENT) {
                scheduler.schedule(VERIFY_DWELL_DELAY_MS) {
                    verifyAndFinish(target, decision.draft, requestGeneration)
                }
            } else {
                val dwell = chooseRandomInt(MIN_VERIFY_DWELL_DELAY_MS, MAX_VERIFY_DWELL_DELAY_MS)
                scheduler.schedule(dwell.toLong()) {
                    verifyAndFinish(target, decision.draft, requestGeneration)
                }
            }
        }
    }

    private fun verifyAndFinish(target: ChatDraftInputTarget, draft: String, requestGeneration: Long) {
        if (!isCurrentGeneration(requestGeneration) || !isAiReplyActive() || isManualReviewRequired()) {
            finish("AI_REPLY_STATE_CHANGED_BEFORE_VERIFY")
            return
        }
        val result = runCatching { inputPort.verifyDraft(target, draft) }
            .getOrElse { ChatDraftInputResult(ChatDraftInputStatus.VERIFICATION_FAILED) }
        if (result.status == ChatDraftInputStatus.VERIFIED) {
            awaitingUserSend.set(true)
            finish("DRAFT_ENTERED_AND_VERIFIED_USER_SEND_REQUIRED")
        } else {
            finish("DRAFT_VERIFY_${result.reasonCode}")
        }
    }

    private fun isCurrentGeneration(requestGeneration: Long): Boolean =
        generation.get() == requestGeneration

    private fun finish(reasonCode: String) {
        onLog("AI 回复草稿处理结束：$reasonCode；未执行发送动作")
        pending.set(false)
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
        private const val MIN_VERIFY_DWELL_DELAY_MS = 650
        private const val MAX_VERIFY_DWELL_DELAY_MS = 950
        private const val VERIFY_DWELL_DELAY_MS = 800L

        private fun newDraftExecutor() = Executors.newSingleThreadExecutor(ThreadFactory { runnable ->
            Thread(runnable, "frdm-ai-model-request").apply { isDaemon = true }
        })
    }
}
