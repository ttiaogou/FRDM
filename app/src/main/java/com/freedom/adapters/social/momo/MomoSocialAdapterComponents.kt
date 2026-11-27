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
}
