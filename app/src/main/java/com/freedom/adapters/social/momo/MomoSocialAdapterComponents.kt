package com.freedom.adapters.social.momo

import android.content.Context
import com.freedom.adapters.accessibility.android.AndroidActiveWindowRootAccess
import com.freedom.adapters.accessibility.android.AndroidScreenshotCapture
import com.freedom.adapters.accessibility.android.AndroidDialogWindowObservation
import com.freedom.application.ports.GestureSettingsRepository
import com.freedom.application.ports.OcrReviewRepository
import com.freedom.application.ports.RunDiagnosticsPort
import com.freedom.application.ports.SessionSampleArchivePort
import com.freedom.core.accessibility.api.AccessibilityGateway
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.features.conversation.domain.contact.ContactIdentityManager
import com.freedom.features.conversation.ports.ConversationRepository
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository
import java.util.concurrent.atomic.AtomicLong
import com.freedom.adapters.social.momo.MomoSayHiIdentityFingerprintPolicy.sha256IdentityAnchor
import com.freedom.adapters.social.momo.MomoSayHiIdentityFingerprintPolicy.normalizeIdentityDisplayName
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ContactIdentityCaptureOutcome
import com.freedom.features.aireply.ports.ContactIdentityCaptureRequestResult
import com.freedom.features.conversation.domain.contact.ContactIdentityObservation

/** Momo browse/navigation/page-inspection implementation group. */
internal class MomoBrowseAdapterComponents(
    context: Context,
    dp: (Int) -> Int,
    accessibilityGateway: AccessibilityGateway,
    onLog: (String) -> Unit,
    gestureSettingsRepository: GestureSettingsRepository,
    pageConfig: MomoPageInspector.Config,
    navigationConfig: MomoNavigationController.Config,
    popupMaxScanNodes: Int,
    popupSafetyMaxIncompleteAttempts: Int,
    feedGestureConfig: MomoFeedGestureController.Config
) {
    val pageInspector = MomoPageInspector(context, dp, pageConfig)
    val pageIdentityMemory = MomoPageIdentityMemory(maxRetentionMs = 6_000L)
    val navigationController = MomoNavigationController(
        context = context,
        dp = dp,
        performGlobalBack = {
            accessibilityGateway.performGlobalAction(
                com.freedom.core.accessibility.api.GlobalAccessibilityAction.BACK
            )
        },
        config = navigationConfig
    )
    val popupInspector = MomoPopupInspector(
        context = context,
        maxScanNodes = popupMaxScanNodes,
        findClickableAncestor = navigationController::findClickableAncestor
    )
    val popupSafetyRetryPolicy = MomoPopupSafetyRetryPolicy(
        maxIncompleteAttemptsPerWindow = popupSafetyMaxIncompleteAttempts
    )
    val feedGestureController = MomoFeedGestureController(
        context = context,
        pageInspector = pageInspector,
        onLog = onLog,
        config = feedGestureConfig,
        gestureSettingsRepository = gestureSettingsRepository
    )
}

/** Chat-page and session-list inspection helpers; no workflow/session state is stored here. */
internal class MomoChatAdapterComponents(
    chatMaxPageInspectionNodes: Int,
    chatMaxRegionInspectionNodes: Int
) {
    val pageInspector = MomoChatPageInspector(
        chatMaxPageInspectionNodes,
        chatMaxRegionInspectionNodes
    )
    val nodeTreeDiagnosticInspector = MomoChatNodeTreeDiagnosticInspector()
    val listSessionExtractor = MomoChatListSessionExtractor()
}

/** Message parsing and OCR execution components. OCR remains lazy until a batch needs it. */
internal class MomoMessageAdapterComponents(
    context: Context,
    rootAccess: AndroidActiveWindowRootAccess,
    targetPackage: String,
    screenshotCapture: AndroidScreenshotCapture?,
    sampleStore: MomoChatSessionSampleStore,
    ocrReviewRepository: OcrReviewRepository,
    onLog: (String) -> Unit
) {
    val reader = MomoChatMessageReader(
        rootAccess = rootAccess,
        targetPackage = targetPackage,
        parser = ChatMessageParser()
    )
    val ocrProcessorDelegate = lazy(LazyThreadSafetyMode.NONE) {
        MomoChatMessageOcrProcessor(
            context,
            screenshotCapture,
            sampleStore,
            ocrReviewRepository,
            onLog
        )
    }
    val ocrProcessor by ocrProcessorDelegate
}

/** Session persistence and identity helpers, composed by the Momo adapter facade. */
internal class MomoSessionAdapterComponents(
    context: Context,
    diagnostics: RunDiagnosticsPort,
    conversationRepository: ConversationRepository,
    sayHiTraversalCheckpointRepository: MomoSayHiTraversalCheckpointRepository,
    sampleArchivePort: SessionSampleArchivePort
) {
    val contactIdentityManager = ContactIdentityManager(conversationRepository)
    val inboxIdentity = MomoInboxIdentityCaptureState(contactIdentityManager)
    val chatListSnapshotSequence = AtomicLong(0L)
    val sampleStore = MomoChatSessionSampleStore(context, diagnostics, sampleArchivePort)
    val sayHiTraversalCheckpointStore = MomoSayHiTraversalCheckpointStore(
        sayHiTraversalCheckpointRepository
    )
}

/** Final page-readiness composition; all mutable SayHi state remains owned by the adapter flow. */
internal class MomoPageInspectionComponents(
    browse: MomoBrowseAdapterComponents,
    chat: MomoChatAdapterComponents,
    sayHiAccessibilityInspector: MomoSayHiAccessibilityInspector,
    rootAccess: AndroidActiveWindowRootAccess,
    targetPackage: String,
    dp: (Int) -> Int,
    onLog: (String) -> Unit,
    latestDialogWindowObservation: () -> AndroidDialogWindowObservation?,
    isSayHiProfileScrollPending: (Int) -> Boolean,
    sayHiProfileScrollWindowId: () -> Int
) {
    val gestureReadinessInspector = MomoGesturePageReadinessInspector(
        rootAccess = rootAccess,
        targetPackage = targetPackage,
        dp = dp,
        onLog = onLog,
        popupInspector = browse.popupInspector,
        popupSafetyRetryPolicy = browse.popupSafetyRetryPolicy,
        latestDialogWindowObservation = latestDialogWindowObservation,
        pageInspector = browse.pageInspector,
        chatPageInspector = chat.pageInspector,
        sayHiAccessibilityInspector = sayHiAccessibilityInspector,
        isSayHiProfileScrollPending = isSayHiProfileScrollPending,
        sayHiProfileScrollWindowId = sayHiProfileScrollWindowId
    )
}

internal enum class InboxIdentityCaptureStage {
    AVATAR_TAP_PENDING,
    WAITING_PROFILE,
    WAITING_LIST_RETURN
}

/**
 * Exact, short-lived row evidence used only to bind a profile ID back to the row that opened
 * it. A nickname by itself is deliberately insufficient to restore the association.
 */
internal sealed interface InboxIdentityAnchor {
    val displayName: String
    fun opaqueKey(): String
    fun matches(candidate: ChatListCandidateSnapshot): Boolean = false
}

internal data class ObservedDetailIdentityAnchor(
    override val displayName: String,
    val sourceWindow: WindowRef,
    val runGeneration: Long,
    val visitToken: String
) : InboxIdentityAnchor {
    override fun opaqueKey(): String = listOf(
        "OBSERVED_DETAIL", sourceWindow.packageName, sourceWindow.windowId,
        sourceWindow.generation, runGeneration, visitToken
    ).joinToString("|").let(::sha256IdentityAnchor)

    override fun toString(): String = "ObservedDetailIdentityAnchor(anchor=[REDACTED])"
}

internal data class InboxIdentityRowAnchor(
    val rowIndex: Int,
    val rowPath: List<Int>,
    override val displayName: String,
    val rowBounds: ScreenRect
) : InboxIdentityAnchor {
    override fun matches(candidate: ChatListCandidateSnapshot): Boolean =
        candidate.selectionClass == "PRIVATE_CHAT" &&
            candidate.rowIndex == rowIndex &&
            candidate.rowTarget.path.childIndices == rowPath &&
            normalizeIdentityDisplayName(candidate.nameText) == normalizeIdentityDisplayName(displayName) &&
            candidate.rowBounds == rowBounds

    override fun opaqueKey(): String = listOf(
        rowIndex,
        rowPath.joinToString("."),
        normalizeIdentityDisplayName(displayName),
        rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom
    ).joinToString("|").let(::sha256IdentityAnchor)
}

internal class PendingInboxIdentityCapture(
    val anchor: InboxIdentityAnchor,
    stage: InboxIdentityCaptureStage,
    lastTransitionAtMs: Long
) {
    @Volatile var stage: InboxIdentityCaptureStage = stage
    @Volatile var lastTransitionAtMs: Long = lastTransitionAtMs
    @Volatile var platformUserId: String? = null
    @Volatile var profileReadCompleted: Boolean = false
    /** Set only after the unique platform ID has been read in the current transaction. */
    @Volatile var profileEntryVerified: Boolean = false
    @Volatile var profileScrollAttempted: Boolean = false
    @Volatile var profileScrollInFlight: Boolean = false
    @Volatile var profileScrollWindowId: Int = -1
    @Volatile var profileScrollSettledAtMs: Long = 0L
    val profileDeferredRetryGate = MomoSayHiProfileDeferredRetryGate()
    @Volatile var backAttempts: Int = 0
    @Volatile var avatarClickState: MomoAvatarClickTransactionState =
        MomoAvatarClickTransactionState.AVATAR_CLICK_PREPARING
}

/** Sole run-local owner of the original private-chat identity transaction and caches.
 * UI orchestration stays in the facade; all compound transitions retain the original monitor.
 * Mutable members remain package-internal for the existing transaction callers during migration.
 */
internal class MomoInboxIdentityCaptureState(
    private val contactIdentityManager: ContactIdentityManager
) {
    val inboxIdentityCaptureLock = Any()
    @Volatile var pendingInboxIdentityCapture: PendingInboxIdentityCapture? = null
    @Volatile var identityReturnReinspectionPending: Boolean = false
    var observedDetailIdentityReturnKey: String? = null
    @Volatile var lastConfirmedChatDetailSnapshot: ChatPageSnapshot? = null
    val failedInboxIdentityCaptureKeys = LinkedHashSet<String>()
    val loggedInboxIdentityFailureKeys = LinkedHashSet<String>()
    /** Run-scoped row-anchor cache; never persists a display-name-to-ID mapping across runs. */
    val verifiedInboxIdentityByAnchor = LinkedHashMap<String, String>()
    val completedInboxIdentityOutcomes = LinkedHashMap<String, ContactIdentityCaptureOutcome>()

    fun resetForRun() {
        synchronized(inboxIdentityCaptureLock) {
            pendingInboxIdentityCapture = null
            identityReturnReinspectionPending = false
            observedDetailIdentityReturnKey = null
            lastConfirmedChatDetailSnapshot = null
            failedInboxIdentityCaptureKeys.clear()
            loggedInboxIdentityFailureKeys.clear()
            verifiedInboxIdentityByAnchor.clear()
            completedInboxIdentityOutcomes.clear()
        }
    }

    fun consumeIdentityReturnReinspectionRequest(): Boolean =
        synchronized(inboxIdentityCaptureLock) {
            if (!identityReturnReinspectionPending) false
            else {
                identityReturnReinspectionPending = false
                true
            }
        }

    /** Retryable failures must not leave a terminal outcome for the next observation. */
    private fun clearRetryableInboxIdentityState(anchorKey: String) {
        completedInboxIdentityOutcomes.remove(anchorKey)
        failedInboxIdentityCaptureKeys.remove(anchorKey)
        verifiedInboxIdentityByAnchor.remove(anchorKey)
    }

    fun onIdentityVerificationLog(onLog: (String) -> Unit, result: MomoIdentityVerificationResult, reason: String, cacheWrite: Boolean, nextAction: String, anchorKey: String) {
        onLog("IDENTITY_VERIFICATION result=${result.name}；reason=$reason；cacheWrite=$cacheWrite；nextAction=$nextAction；anchorHash=${anchorKey.take(12)}")
    }

    fun completePendingInboxIdentityBinding(
        pending: PendingInboxIdentityCapture,
        identityVerified: Boolean,
        onLog: (String) -> Unit
    ) {
        synchronized(inboxIdentityCaptureLock) {
            if (pendingInboxIdentityCapture !== pending) return
            val anchorKey = pending.anchor.opaqueKey()
            if (identityVerified && !pending.platformUserId.isNullOrBlank()) {
                failedInboxIdentityCaptureKeys.remove(anchorKey)
                // An observed detail has no list row to populate in the row cache.
                if (pending.anchor is InboxIdentityRowAnchor) {
                    verifiedInboxIdentityByAnchor[anchorKey] = pending.platformUserId!!
                }
                val identity = contactIdentityManager.resolveSnapshot(
                    snapshotId = "inbox-identity-${System.nanoTime()}",
                    observations = listOf(
                        ContactIdentityObservation(
                            displayName = pending.anchor.displayName,
                            isPrivateChat = true,
                            platformUserId = pending.platformUserId
                        )
                    ),
                    observedAtMillis = System.currentTimeMillis()
                ).singleOrNull()
                val verificationResult = classifyMomoIdentityVerification(
                    confidence = identity?.confidence,
                    contactId = identity?.contactId
                )
                completedInboxIdentityOutcomes[anchorKey] = if (
                    verificationResult == MomoIdentityVerificationResult.IDENTITY_VERIFIED
                ) {
                    ContactIdentityCaptureOutcome.completed(requireNotNull(identity))
                } else if (verificationResult.isTerminalFailure()) {
                    failedInboxIdentityCaptureKeys += anchorKey
                    onIdentityVerificationLog(onLog, verificationResult, "EXPLICIT_IDENTITY_CONFLICT", true, "SKIP_CURRENT_TARGET", anchorKey)
                    ContactIdentityCaptureOutcome.failed(
                        ContactIdentityCaptureRequestResult.REJECTED,
                        verificationResult.name
                    )
                } else {
                    clearRetryableInboxIdentityState(anchorKey)
                    onIdentityVerificationLog(onLog, verificationResult, "PROFILE_ID_NOT_CONFIRMED", false, "RETRY_ON_NEXT_OBSERVATION", anchorKey)
                    ContactIdentityCaptureOutcome.waiting(ContactIdentityCaptureRequestResult.RETRY_LATER)
                }
                while (verifiedInboxIdentityByAnchor.size > MAX_INBOX_VERIFIED_IDENTITY_CACHE_ENTRIES) {
                    val oldest = verifiedInboxIdentityByAnchor.entries.firstOrNull()?.key ?: break
                    verifiedInboxIdentityByAnchor.remove(oldest)
                }
            } else {
                // Missing profile ID is retryable observation failure, not an identity
                // conflict. Do not populate either terminal cache: the next observation must
                // be allowed to reacquire the chat page, relocate the avatar and retry.
                clearRetryableInboxIdentityState(anchorKey)
            }
            pendingInboxIdentityCapture = null
        }
        onLog(
            if (identityVerified) {
                "AI 联系人身份采集完成：资料页 ID 已完成终态回传；等待 AI Reply 绑定当前私人会话"
            } else {
                "AI 联系人身份采集未完成：返回列表后无法将资料页 ID 唯一绑定到原消息行；不关联任何历史记录"
            }
        )
    }

    private companion object {
        private const val MAX_INBOX_VERIFIED_IDENTITY_CACHE_ENTRIES = 256
    }

    fun transitionAvatarClickState(
        pending: PendingInboxIdentityCapture?,
        next: MomoAvatarClickTransactionState,
        reason: String,
        onLog: (String) -> Unit
    ) {
        if (pending == null) return
        val previous = pending.avatarClickState
        if (previous == next) return
        if (!previous.canTransitionTo(next)) {
            onLog(
                "AI 头像点击事务：非法状态转换被拒绝；" +
                    "from=${previous.name}；to=${next.name}；reason=$reason"
            )
            return
        }
        pending.avatarClickState = next
        onLog(
            "AI 头像点击事务状态：from=${previous.name}；to=${next.name}；" +
                "reason=$reason"
        )
    }

    fun releaseAvatarCaptureForRetry(
        pending: PendingInboxIdentityCapture,
        reason: String,
        onLog: (String) -> Unit
    ) {
        transitionAvatarClickState(
            pending,
            MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,
            reason,
            onLog
        )
        synchronized(inboxIdentityCaptureLock) {
            if (pendingInboxIdentityCapture === pending) pendingInboxIdentityCapture = null
        }
    }

    fun releaseAvatarCaptureAfterProfileMiss(
        pending: PendingInboxIdentityCapture,
        reason: MomoAvatarClickFailureReason,
        onLog: (String) -> Unit
    ) {
        transitionAvatarClickState(
            pending,
            MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,
            reason.name,
            onLog
        )
        synchronized(inboxIdentityCaptureLock) {
            if (pendingInboxIdentityCapture === pending) pendingInboxIdentityCapture = null
        }
        onLog(
            "AI 头像点击事务：资料页进入失败；释放旧头像事务，下一次观察重新读取聊天详情并重新定位头像；" +
                "reason=${reason.name}；不暂停任务、不标记身份失败、不使用旧坐标、不点击整行"
        )
    }

    fun failPendingInboxIdentityCapture(
        pending: PendingInboxIdentityCapture,
        reason: String,
        onLog: (String) -> Unit
    ) {
        // Row geometry/path may change while the same profile transaction is unwinding;
        // deduplicate by the redacted display identity and reason, not the volatile anchor.
        val logKey = "${normalizeIdentityDisplayName(pending.anchor.displayName)}|$reason"
        val shouldLog: Boolean
        synchronized(inboxIdentityCaptureLock) {
            if (pendingInboxIdentityCapture === pending) pendingInboxIdentityCapture = null
            shouldLog = loggedInboxIdentityFailureKeys.add(logKey)
        }
        transitionAvatarClickState(
            pending,
            MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY,
            reason,
            onLog
        )
        if (shouldLog) {
            onLog("AI 身份回接[REQUEST_NOT_PRODUCED]：result=NOT_PRODUCED；reason=$reason")
            onLog("AI 联系人身份采集失败：$reason；保留未验证身份，不以昵称关联历史")
        }
    }

    fun observeContactIdentityCapture(
        anchor: InboxIdentityAnchor,
        requestCapture: () -> ContactIdentityCaptureRequestResult,
        onLog: (String) -> Unit
    ): ContactIdentityCaptureOutcome {
        val key = anchor.opaqueKey()
        synchronized(inboxIdentityCaptureLock) {
            completedInboxIdentityOutcomes[key]?.let { return it }
            pendingInboxIdentityCapture?.let { pending ->
                if (pending.anchor.opaqueKey() == key) {
                    // IN_PROGRESS is a non-terminal state. Keep the reason in the
                    // explicit OBSERVE log, never in failureReason.
                    onLog("AI 联系人身份采集[OBSERVE_WAITING]：事务仍在进行；不返回失败原因")
                    return ContactIdentityCaptureOutcome.waiting(
                        ContactIdentityCaptureRequestResult.IN_PROGRESS
                    )
                }
            }
        }
        return when (val request = requestCapture()) {
            ContactIdentityCaptureRequestResult.STARTED,
            ContactIdentityCaptureRequestResult.IN_PROGRESS,
            ContactIdentityCaptureRequestResult.RETRY_LATER ->
                // Waiting is not a terminal failure. The outcome contract rejects a
                // failureReason while awaiting=true; stage details are emitted by the
                // explicit [OBSERVE] and [REQUEST] logs above.
                ContactIdentityCaptureOutcome.waiting(request)
            ContactIdentityCaptureRequestResult.NOT_SUPPORTED ->
                ContactIdentityCaptureOutcome.failed(request, "IDENTITY_CAPTURE_NOT_SUPPORTED")
            ContactIdentityCaptureRequestResult.REJECTED ->
                ContactIdentityCaptureOutcome.failed(request, "IDENTITY_CAPTURE_REQUEST_REJECTED")
        }.also { outcome ->
            onLog(
                "AI 联系人身份采集[OBSERVE_RESULT]：" +
                    "request=${outcome.request.name}；awaiting=${outcome.awaiting}；" +
                    "verified=${outcome.verifiedIdentity != null}；failure=${outcome.failureReason ?: "NONE"}"
            )
        }
    }

    fun cachedPlatformId(key: String): String? =
        synchronized(inboxIdentityCaptureLock) { verifiedInboxIdentityByAnchor[key] }

    fun hasReturnReinspection(): Boolean =
        synchronized(inboxIdentityCaptureLock) { identityReturnReinspectionPending }

    fun hasObservedCapture(key: String): Boolean =
        synchronized(inboxIdentityCaptureLock) {
            pendingInboxIdentityCapture?.anchor?.opaqueKey() == key
        }

    fun hasObservedReturn(key: String): Boolean =
        synchronized(inboxIdentityCaptureLock) { observedDetailIdentityReturnKey == key }

    fun consumeObservedReturn(key: String): Boolean =
        synchronized(inboxIdentityCaptureLock) {
            if (observedDetailIdentityReturnKey != key) false else {
                observedDetailIdentityReturnKey = null
                true
            }
        }

    fun cancelObservedCapture(key: String) {
        synchronized(inboxIdentityCaptureLock) {
            if (pendingInboxIdentityCapture?.anchor?.opaqueKey() == key) pendingInboxIdentityCapture = null
            completedInboxIdentityOutcomes.remove(key)
            if (observedDetailIdentityReturnKey == key) observedDetailIdentityReturnKey = null
            failedInboxIdentityCaptureKeys.remove(key)
        }
    }

    fun releaseIfCurrent(pending: PendingInboxIdentityCapture) {
        synchronized(inboxIdentityCaptureLock) {
            if (pendingInboxIdentityCapture === pending) pendingInboxIdentityCapture = null
        }
    }

    fun publishVerifiedReturn(pending: PendingInboxIdentityCapture) {
        synchronized(inboxIdentityCaptureLock) {
            if (pending.anchor is ObservedDetailIdentityAnchor) {
                val key = pending.anchor.opaqueKey()
                if (completedInboxIdentityOutcomes[key]?.verifiedIdentity != null) {
                    observedDetailIdentityReturnKey = key
                }
            } else {
                identityReturnReinspectionPending = true
            }
        }
    }

    fun applyAvatarGestureResult(pending: PendingInboxIdentityCapture, status: GestureExecutionStatus, onLog: (String) -> Unit) {
        synchronized(inboxIdentityCaptureLock) {
            if (pendingInboxIdentityCapture !== pending) return@synchronized
            if (status == GestureExecutionStatus.COMPLETED) {
                pending.stage = InboxIdentityCaptureStage.WAITING_PROFILE
                pending.lastTransitionAtMs = System.currentTimeMillis()
                transitionAvatarClickState(
                    pending,
                    MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING,
                    "头像手势完成，等待资料页页面确认",
                    onLog
                )
            } else {
                transitionAvatarClickState(
                    pending,
                    MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,
                    "头像手势未完成；不将本次点击直接视为事务终止",
                    onLog
                )
                pendingInboxIdentityCapture = null
            }
        }
    }

    fun invalidateCachedIdentity(key: String, verificationResult: MomoIdentityVerificationResult, onLog: (String) -> Unit) {
        synchronized(inboxIdentityCaptureLock) {
            verifiedInboxIdentityByAnchor.remove(key)
            if (verificationResult.isTerminalFailure()) {
                failedInboxIdentityCaptureKeys += key
                onIdentityVerificationLog(onLog, verificationResult, "EXPLICIT_IDENTITY_CONFLICT", true, "SKIP_CURRENT_TARGET", key)
            } else {
                failedInboxIdentityCaptureKeys.remove(key)
                onIdentityVerificationLog(onLog, verificationResult, "PROFILE_ID_NOT_CONFIRMED", false, "RETRY_ON_NEXT_OBSERVATION", key)
            }
        }
    }

    fun checkCaptureAdmission(anchorKey: String, onLog: (String) -> Unit): ContactIdentityCaptureRequestResult? {
        synchronized(inboxIdentityCaptureLock) {
            if (anchorKey in failedInboxIdentityCaptureKeys) {
                onLog("IDENTITY_CAPTURE_BLOCKED result=IDENTITY_CAPTURE_BLOCKED；reason=EXPLICIT_IDENTITY_CONFLICT；failedCacheHit=true；action=SKIP_CURRENT_TARGET；anchorHash=${anchorKey.take(12)}")
                return ContactIdentityCaptureRequestResult.REJECTED
            }
            onLog("IDENTITY_RETRY_ALLOWED result=IDENTITY_RETRY_ALLOWED；previousFailure=NONE_OR_RETRYABLE；failedCacheHit=false；action=REACQUIRE_AVATAR；anchorHash=${anchorKey.take(12)}")
            val pending = pendingInboxIdentityCapture
            if (pending != null) return ContactIdentityCaptureRequestResult.IN_PROGRESS
        }
        return null
    }

    fun registerCapture(anchorKey: String, pending: PendingInboxIdentityCapture, onLog: (String) -> Unit): ContactIdentityCaptureRequestResult? {
        synchronized(inboxIdentityCaptureLock) {
            if (anchorKey in failedInboxIdentityCaptureKeys) {
                onLog("IDENTITY_CAPTURE_BLOCKED result=IDENTITY_CAPTURE_BLOCKED；reason=EXPLICIT_IDENTITY_CONFLICT；failedCacheHit=true；action=SKIP_CURRENT_TARGET；anchorHash=${anchorKey.take(12)}")
                return ContactIdentityCaptureRequestResult.REJECTED
            }
            if (pendingInboxIdentityCapture != null) {
                return ContactIdentityCaptureRequestResult.IN_PROGRESS
            }
            pendingInboxIdentityCapture = pending
        }
        return null
    }
}
