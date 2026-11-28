package com.freedom.adapters.social.momo

import com.freedom.gesture.api.GestureApi
import com.freedom.adapters.social.momo.MomoChatPageContract.unknownChatPageSnapshot
import com.freedom.adapters.social.momo.MomoChatPageContract.receivedGreetingsListChatPagePlaceholder
import com.freedom.adapters.social.momo.MomoSayHiIdentityFingerprintPolicy.sha256IdentityAnchor
import com.freedom.adapters.social.momo.MomoSayHiIdentityFingerprintPolicy.extractChatPeerName
import com.freedom.adapters.social.momo.MomoSayHiIdentityFingerprintPolicy.normalizeIdentityDisplayName
import com.freedom.adapters.social.momo.MomoWindowPageFingerprint.sayHiWindowStabilityKey
import com.freedom.adapters.social.momo.MomoWindowPageFingerprint.sameMomoWindowGeneration
import com.freedom.adapters.social.momo.MomoChatListSessionPolicy.receivedGreetingsEntrySearchViewportFingerprint
import com.freedom.features.conversation.domain.contact.ContactIdentityManager
import com.freedom.features.conversation.domain.contact.ContactIdentityObservation
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import com.freedom.features.conversation.domain.message.ConversationMessageInput
import android.content.Context
import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.BuildConfig
import com.freedom.adapters.accessibility.android.AndroidActiveWindowRootAccess
import com.freedom.adapters.accessibility.android.AndroidScreenshotCapture
import com.freedom.adapters.accessibility.android.AndroidDialogWindowObservation
import com.freedom.adapters.accessibility.android.AndroidTargetWindowRoot
import com.freedom.adapters.accessibility.android.MonitorOverlay
import com.freedom.core.accessibility.api.AccessibilityGateway
import com.freedom.core.accessibility.api.NodeActionRequest
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.TreeInspectionRequest
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.AccessibilityTreeLimits
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext
import com.freedom.core.accessibility.api.GlobalAccessibilityAction
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.query.NodeTreeIndex
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.PageFingerprint
import com.freedom.core.runtime.TargetAppPageReadiness
import com.freedom.core.runtime.TargetAppReadinessSnapshot
import com.freedom.core.runtime.TargetAppReadinessStatus
import com.freedom.features.accountbrowse.ports.*
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsBackResult
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ChatPageNodeEvidence
import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.MessageWorkAreaNavigationOutcome
import com.freedom.features.aireply.ports.ChatMessagePort
import com.freedom.features.aireply.ports.ChatSessionManagementPort
import com.freedom.features.aireply.ports.PrivateChatFollowUpPort
import com.freedom.features.aireply.ports.PrivateChatFollowUpProgress
import com.freedom.features.aireply.ports.ChatDraftInputPort
import com.freedom.features.aireply.ports.ChatDraftInputTarget
import com.freedom.features.aireply.ports.ChatDraftInputResult
import com.freedom.features.aireply.ports.ChatDraftSendPort
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.aireply.ports.ContactIdentityCaptureRequestResult
import com.freedom.features.aireply.ports.ContactIdentityCaptureOutcome
import com.freedom.features.aireply.ports.ObservedPrivateChatIdentityTarget
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import kotlin.math.abs
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.application.ports.GestureSettingsRepository
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatRegionSnapshot
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.SessionListNavigationTarget
import com.freedom.application.ports.RunDiagnosticsPort
import com.freedom.application.ports.OcrReviewRepository
import com.freedom.application.ports.SessionSampleArchivePort
import com.freedom.features.conversation.ports.ConversationRepository
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository
import com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnJournal
import com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnPhase
import com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnRepository
import com.freedom.features.aireply.policy.ConversationFilterDecision
import com.freedom.features.aireply.policy.ConversationFilterEvidence
import com.freedom.features.aireply.policy.ConversationFilterPolicy
import com.freedom.features.aireply.policy.ConversationFilterResult
import com.freedom.features.aireply.policy.ConversationFilterSettings

/**
 * Android-side Momo adapter. It owns app-specific inspectors and controllers and exposes
 * platform-neutral evidence and short-lived locators through SocialBrowsePort / ChatPagePort.
 * Raw Android nodes stay inside this adapter and are never exposed to the browse workflow.
 */
internal class MomoSocialAdapter(
    private val context: Context,
    private val rootAccess: AndroidActiveWindowRootAccess,
    private val accessibilityGateway: AccessibilityGateway,
    private val targetPackage: String,
    private val activityClass: () -> String,
    private val dp: (Int) -> Int,
    private val onLog: (String) -> Unit,
    gestureSettingsRepository: GestureSettingsRepository,
    private val gesturePolicy: GestureApi,
    private val screenshotCapture: AndroidScreenshotCapture? = null,
    private val latestDialogWindowObservation: () -> AndroidDialogWindowObservation? = { null },
    private val conversationRepository: ConversationRepository,
    private val diagnostics: RunDiagnosticsPort,
    private val sayHiTraversalCheckpointRepository: MomoSayHiTraversalCheckpointRepository,
    private val receivedGreetingsSendReturnRepository: ReceivedGreetingsSendReturnRepository,
    private val ocrReviewRepository: OcrReviewRepository,
    private val sampleArchivePort: SessionSampleArchivePort,
    /** Fresh observation from the existing Android READY owner; not a UI write grant. */
    private val inspectReceivedGreetingsReadiness: () -> TargetAppReadinessSnapshot,
    private val conversationFilterSettings: () -> ConversationFilterSettings,
    /** The AI task lease must still be current when a send gesture is admitted. */
    private val isAiReplyUiOperationOwned: () -> Boolean = { true },
    /** Exact lease captured at model admission; task handoff revokes old async callbacks. */
    private val isBoundAiReplyUiOperationOwned: (com.freedom.core.runtime.TaskLease?) -> Boolean = { true }
) : SocialBrowsePort, ChatPagePort, ChatMessagePort, ChatSessionManagementPort, ChatDraftInputPort,
    ChatDraftSendPort, PrivateChatFollowUpPort {

    override fun isReceivedGreetingsListActivity(activityClass: String): Boolean =
        activityClass == MomoSayHiPageContract.LIST_ACTIVITY

    private data class Config(
        val page: MomoPageInspector.Config,
        val navigation: MomoNavigationController.Config,
        val popupMaxScanNodes: Int,
        val feedGesture: MomoFeedGestureController.Config,
        val chatMaxPageInspectionNodes: Int = MomoChatPageInspector.MAX_PAGE_INSPECTION_NODES,
        val chatMaxRegionInspectionNodes: Int = MomoChatPageInspector.MAX_REGION_INSPECTION_NODES
    )

    // Keep the adapter's established Momo-specific defaults as the single config owner.
    private val config: Config = createDefaultConfig()

    private sealed interface ReceivedGreetingsTapInspection {
        data class Ready(
            val tree: AccessibilityTreeSnapshot,
            val metrics: DisplayMetricsSnapshot,
            val target: MomoReceivedGreetingsEntryTapDecision.Ready
        ) : ReceivedGreetingsTapInspection

        data class Rejected(val reason: String) : ReceivedGreetingsTapInspection
    }

    private enum class SayHiRowTapFeedbackState {
        NOT_DISPATCHED,
        WAITING,
        TIMED_OUT,
        VERIFIED,
        REJECTED
    }

    private enum class SayHiReadOnlyStage {
        IDLE,
        AVATAR_TAP_PENDING,
        WAITING_PROFILE,
        PROFILE_SCROLL_PENDING,
        PROFILE_BACK_PENDING,
        ROW_TAP_PENDING,
        WAITING_DETAIL,
        DETAIL_BACK_PENDING
    }

    private data class SayHiSelectedGreeting(
        val rowKey: String,
        val displayName: String,
        val greetingText: String?,
        val timestampText: String?,
        val sourceWindow: WindowRef,
        val sourceStructureFingerprint: String,
        val listFilterEvidence: ConversationFilterEvidence = ConversationFilterEvidence(),
        val profileFilterEvidence: ConversationFilterEvidence? = null
    )

    private sealed interface SayHiCardListTapInspection {
        data class Ready(
            val tree: AccessibilityTreeSnapshot,
            val metrics: DisplayMetricsSnapshot,
            val target: MomoSayHiCardListTapDecision.Ready
        ) : SayHiCardListTapInspection

        data class Rejected(val reason: String) : SayHiCardListTapInspection
    }

    // Momo-specific behavior is composed by bounded Browse/Chat/Message/Session collaborators.
    private val browseComponents = MomoBrowseAdapterComponents(
        context = context,
        dp = dp,
        accessibilityGateway = accessibilityGateway,
        onLog = onLog,
        gestureSettingsRepository = gestureSettingsRepository,
        pageConfig = config.page,
        navigationConfig = config.navigation,
        popupMaxScanNodes = config.popupMaxScanNodes,
        popupSafetyMaxIncompleteAttempts = MAX_POPUP_SAFETY_INCOMPLETE_ATTEMPTS,
        feedGestureConfig = config.feedGesture
    )
    private val chatComponents = MomoChatAdapterComponents(
        chatMaxPageInspectionNodes = config.chatMaxPageInspectionNodes,
        chatMaxRegionInspectionNodes = config.chatMaxRegionInspectionNodes
    )
    private val sayHiAccessibilityInspector = MomoSayHiAccessibilityInspector()
    private val sessionComponents = MomoSessionAdapterComponents(
        context = context,
        diagnostics = diagnostics,
        conversationRepository = conversationRepository,
        sayHiTraversalCheckpointRepository = sayHiTraversalCheckpointRepository,
        sampleArchivePort = sampleArchivePort
    )
    private val sayHiTraversalState = MomoSayHiTraversalState(
        checkpointStore = sessionComponents.sayHiTraversalCheckpointStore,
        maxRememberedRows = MAX_SAYHI_PROCESSED_ROWS,
        maxRetryBackoffStage = MAX_SAYHI_ROW_RETRY_BACKOFF_STAGE,
        safeTargetFailureReasonPrefix = SAYHI_SAFE_TARGET_VALIDATION_FAILURE_REASON_PREFIX
    )
    private val messageComponents = MomoMessageAdapterComponents(
        context = context,
        rootAccess = rootAccess,
        targetPackage = targetPackage,
        screenshotCapture = screenshotCapture,
        sampleStore = sessionComponents.sampleStore,
        ocrReviewRepository = ocrReviewRepository,
        onLog = onLog
    )
    private val pageInspectionComponents = MomoPageInspectionComponents(
        browse = browseComponents,
        chat = chatComponents,
        sayHiAccessibilityInspector = sayHiAccessibilityInspector,
        rootAccess = rootAccess,
        targetPackage = targetPackage,
        dp = dp,
        onLog = onLog,
        latestDialogWindowObservation = latestDialogWindowObservation,
        isSayHiProfileScrollPending = { windowId ->
            (sayHiReadOnlyStage == SayHiReadOnlyStage.PROFILE_SCROLL_PENDING &&
                sayHiSelectedGreeting != null &&
                sayHiProfileScrollInFlight && sayHiProfileScrollWindowId == windowId) ||
                sessionComponents.inboxIdentity.pendingInboxIdentityCapture?.let {
                    it.stage == InboxIdentityCaptureStage.WAITING_PROFILE &&
                        it.profileScrollInFlight && it.profileScrollWindowId == windowId
                } == true
        },
        sayHiProfileScrollWindowId = {
            sessionComponents.inboxIdentity.pendingInboxIdentityCapture?.takeIf { it.profileScrollInFlight }?.profileScrollWindowId
                ?: sayHiProfileScrollWindowId
        }
    )

    private val sayHiNavigationEvidenceInspector = MomoSayHiNavigationEvidenceInspector()
    private val sayHiAutonomousBrowseController = MomoSayHiAutonomousBrowseController()
    private val sayHiNavigationFlowController = MomoSayHiNavigationFlowController()
    private val sayHiListSnapshotRecoveryPolicy = MomoSayHiListSnapshotRecoveryPolicy()
    private val sayHiSnapshotCoordinator = MomoSayHiListSnapshotCoordinator(
        recoveryPolicy = sayHiListSnapshotRecoveryPolicy,
        isNavigationRelevant = {
            completionBackStage == CompletionBackStage.NONE &&
                (sayHiNavigationFlowController.isReceivedListConfirmed() ||
                    sayHiNavigationFlowController.isWaitingForReceivedList()) &&
                (!isSayHiChatNavigationInProgress() || isReceivedGreetingsListStructureVisible())
        },
        isGestureBusy = { sayHiGestureInFlight },
        beforeFreshInspection = { lastSayHiInspectionAtMs = 0L },
        inspectFreshSnapshot = {
            inspectSayHiPageAndAutonomousBrowse(
                allowAutonomousScroll = false,
                minimumIntervalMs = 0L,
                forceFresh = true
            )
        },
        onLog = onLog
    )
    @Volatile private var sayHiConfirmedListTarget: NodeTarget? = null
    @Volatile private var lastSayHiSnapshotGateDiagnostic: String = ""
    @Volatile private var lastSayHiSnapshotGateDiagnosticAtMs: Long = 0L
    @Volatile private var sayHiGenericChatInspectionBypassActive: Boolean = false
    @Volatile private var lastSayHiInspectionSignature: String = ""
    @Volatile private var lastSayHiInspectionAtMs: Long = 0L
    @Volatile private var lastSayHiNavigationEvidenceSignature: String = ""
    @Volatile private var lastMomoNavigationEvidenceAtMs: Long = 0L
    @Volatile private var sayHiNonSearchGestureInFlight: Boolean = false
    private val sayHiGestureInFlight: Boolean
        get() = sayHiNonSearchGestureInFlight || receivedGreetingsEntrySearchGestureInFlight
    private enum class CompletionBackStage { NONE, LIST_SUBMITTED, CARD_SUBMITTED }
    @Volatile private var completionBackStage = CompletionBackStage.NONE
    private val completionBackRetryGate = MomoCompletionBackRetryGate(SAYHI_DETAIL_BACK_TIMEOUT_MS)
    private val receivedGreetingsEntrySearchController = MomoReceivedGreetingsEntrySearchController()
    private val receivedGreetingsEntryPositionMemory = MomoReceivedGreetingsEntryPositionMemory()
    private val receivedGreetingsEntrySearchGestureInFlight: Boolean
        get() = receivedGreetingsEntrySearchController.isGestureInFlight()
    @Volatile private var sayHiReadOnlyStage: SayHiReadOnlyStage = SayHiReadOnlyStage.IDLE
    @Volatile private var sayHiSelectedGreeting: SayHiSelectedGreeting? = null
    @Volatile private var sayHiSelectedContactIdentity: ContactRowIdentity? = null
    @Volatile private var sayHiSelectedIdentityStatus: MomoSayHiPlatformIdentityStatus = MomoSayHiPlatformIdentityStatus.MISSING
    @Volatile private var sayHiReadOnlyStageStartedAtMs: Long = 0L
    @Volatile private var sayHiDetailBackRecoveryWindowStartedAtUptimeMs: Long = 0L
    @Volatile private var sayHiDetailBackRecoveryAttempted: Boolean = false
    @Volatile private var sayHiDetailBackSourceWindow: WindowRef? = null
    private val sayHiDetailBackReturnConfirmation = MomoSayHiListReturnConfirmationPolicy()
    private val sayHiRestoredReturnConfirmation = MomoSayHiListReturnConfirmationPolicy()
    private val sayHiSendReturnJournal = ReceivedGreetingsSendReturnJournal(receivedGreetingsSendReturnRepository)

    @Synchronized
    override fun consumeReceivedGreetingsVerifiedReturnReceipts(): List<String> {
        if (!isReceivedGreetingsListConfirmed() || sayHiReadOnlyStage != SayHiReadOnlyStage.IDLE ||
            sayHiGestureInFlight || !isAiReplyUiOperationOwned()
        ) return emptyList()
        return sayHiTraversalState.consumeVerifiedReturnReceipts()
    }

    @Synchronized
    private fun recordSayHiRunSendReceipt(receipt: String) {
        sayHiTraversalState.recordRunSendReceipt(receipt)
    }

    @Synchronized
    private fun recordSayHiVerifiedReturnReceipt(receipt: String) {
        sayHiTraversalState.recordVerifiedReturnReceipt(receipt)
    }

    @Synchronized
    private fun hasSayHiVerifiedReturnReceipts(): Boolean = sayHiTraversalState.hasVerifiedReturnReceipts()
    @Volatile private var sayHiProfileReadAttempts: Int = 0
    @Volatile private var sayHiProfileScrollAttempted: Boolean = false
    private val sayHiProfileDeferredRetryGate = MomoSayHiProfileDeferredRetryGate()
    @Volatile private var sayHiProfileScrollFailed: Boolean = false
    @Volatile private var sayHiProfileScrollInFlight: Boolean = false
    @Volatile private var sayHiProfileScrollWindowId: Int = -1
    @Volatile private var sayHiProfileScrollRequestId: String? = null
    @Volatile private var sayHiProfileScrollSettledAtMs: Long = 0L
    @Volatile private var sayHiProfileBackDialogPending: Boolean = false
    @Volatile private var sayHiProfileBackDialogWindowId: Int = -1
    @Volatile private var sayHiProfileBackWindowId: Int = -1
    @Volatile private var sayHiProfileBackAttempts: Int = 0
    @Volatile private var sayHiStableCandidateKey: String = ""
    @Volatile private var sayHiStableCandidateTarget: MomoSayHiTapTarget? = null
    @Volatile private var sayHiStableCandidateWindow: WindowRef? = null
    @Volatile private var sayHiProfileRowRecoveryAttempts: Int = 0
    @Volatile private var pendingSayHiRowTapTarget: MomoSayHiTapTarget? = null
    @Volatile private var pendingSayHiRowTapSubmittedAtUptimeMs: Long = 0L
    @Volatile private var pendingSayHiRowTapGestureCommandId: String? = null
    @Volatile private var pendingSayHiRowTapWindowId: Int = -1
    @Volatile private var sayHiRowTapFeedbackState: SayHiRowTapFeedbackState =
        SayHiRowTapFeedbackState.NOT_DISPATCHED
    @Volatile private var sayHiRowTapHitReason: String = ""
    @Volatile private var sayHiUnverifiedBackAttempted: Boolean = false
    @Volatile private var sayHiUnverifiedBackAttemptActivity: String = ""
    /** True only when we safely backed out of a fingerprint-confirmed detail whose tap event never arrived. */
    @Volatile private var sayHiUnverifiedDetailReturnPending: Boolean = false
    init {
        sayHiTraversalState.restore()?.let { restored ->
            onLog(
                "收到招呼断点恢复：已恢复成功记录=${restored.processedRows}；待恢复用户=${restored.deferredRows}；" +
                    "已验证联系人去重=${restored.processedContacts}；反向回访=false；复核遍历=false；仅恢复行/联系人哈希和状态"
            )
        }
    }

    // ---- SocialBrowsePort / ChatPagePort: app-specific evidence and short-lived locators ----

    override val detailActivityClass: String = MomoAppDescriptor.DYNAMIC_DETAIL_ACTIVITY_CLASS

    override fun isMainActivity(activityClass: String): Boolean =
        activityClass == MomoAppDescriptor.MAIN_ACTIVITY_CLASS

    /**
     * Fresh, read-only page inspection used immediately before every autonomous dispatch.
     * The caller owns the supplied root and keeps it alive only for this synchronous call.
     */
    fun inspectGesturePageReadiness(
        root: AccessibilityNodeInfo,
        currentActivity: String,
        mode: RunMode,
        commandId: String? = null
    ): TargetAppPageReadiness = pageInspectionComponents.gestureReadinessInspector.inspect(
        root, currentActivity, mode,
        activeAlbumSelectorPermit.get()?.takeIf { it.matchesCommand(commandId) }
    )

    // A single short-lived dispatch capability, not a second media workflow or task owner.
    private val activeAlbumSelectorPermit = AtomicReference<MomoAlbumGesturePermit?>(null)

    override fun isDetailActivity(activityClass: String): Boolean =
        activityClass == detailActivityClass

    override fun isImageBrowserActivity(activityClass: String): Boolean =
        activityClass == MomoAppDescriptor.FEED_IMAGE_BROWSER_ACTIVITY_CLASS

    override fun supportsTargetPackage(packageName: String): Boolean = packageName == targetPackage

    override fun canSafelyNavigateBackToSessionList(snapshot: ChatPageSnapshot): Boolean {
        if (snapshot.packageName != targetPackage) return false
        return snapshot.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE ||
            isReceivedGreetingsListStructureVisible() ||
            inspectPage().pageKind == BrowsePageKind.DETAIL ||
            (snapshot.activityClass == MomoSayHiPageContract.CARD_ACTIVITY &&
                snapshot.structureEvidence.any { it.viewId.substringAfterLast('/') in setOf(
                    MomoSayHiPageContract.CARD_CONTENT_VIEW_ID.substringAfterLast('/'),
                    MomoSayHiPageContract.CARD_CLOSE_VIEW_ID.substringAfterLast('/')
                ) })
    }

    private fun observedDialogWindowIdFor(window: WindowRef): Int? {
        val observation = latestDialogWindowObservation() ?: return null
        if (observation.windowId < 0 || observation.windowId == window.windowId) return null
        return if (rootAccess.isTargetWindowPresent(observation.windowId) == true) {
            observation.windowId
        } else null
    }

    /**
     * Entry observation reuses the existing inspectors on one live target root. No identity
     * transaction, navigation, business checkpoint, gesture or task lease is touched here.
     */
    internal fun inspectUnifiedEntryPage(currentTaskId: String?): Pair<PageFingerprint, Boolean>? =
        rootAccess.withActiveTargetRoot { root, window ->
            if (window.packageName != targetPackage || window.windowId < 0 ||
                !accessibilityGateway.isWindowCurrent(window)
            ) return@withActiveTargetRoot null
            val greetings = sayHiAccessibilityInspector.hasReceivedGreetingsListFingerprint(root, targetPackage)
            val dynamic = browseComponents.pageInspector.inspectMomoFeedReadiness(root).fingerprint
            // Preserve the dedicated greetings-list inspection boundary.
            val chat = if (greetings) null else chatComponents.pageInspector.inspectRoot(
                root = AccessibilityNodeInfo.obtain(root),
                targetPackage = targetPackage,
                activityClass = window.activityClass
            )
            if (!accessibilityGateway.isWindowCurrent(window)) return@withActiveTargetRoot null
            val privateList = chat?.pageType == MomoChatPageType.CHAT_LIST
            val matches = listOf(greetings, dynamic != null, privateList).count { it }
            val fingerprint = when {
                matches != 1 -> PageFingerprint(targetPackage, window.activityClass, "UNKNOWN", null)
                greetings -> PageFingerprint(
                    targetPackage, window.activityClass, ChatPageKind.NON_CHAT.name,
                    MomoWindowPageFingerprint.windowIdentity(window, targetPackage, "RECEIVED_GREETINGS_LIST")
                )
                dynamic != null -> PageFingerprint(
                    targetPackage, window.activityClass, BrowsePageKind.DYNAMIC_FEED.name,
                    MomoWindowPageFingerprint.create(window, targetPackage, BrowsePageKind.DYNAMIC_FEED.name, dynamic.signature)
                )
                else -> PageFingerprint(targetPackage, window.activityClass, ChatPageKind.CHAT_LIST.name, null)
            }
            // A conflicting or recognized top-level page cannot inherit a previous task's
            // authorization. Only a non-top-level page can retain the existing task,
            // using the same fresh root and existing structural evidence.
            val ownedNestedPage = if (matches == 0 && currentTaskId != null && chat != null) {
                val verified = when (currentTaskId) {
                    "ACCOUNT_BROWSE" ->
                        chat.pageType != MomoChatPageType.CHAT_DETAIL_CANDIDATE &&
                            inspectGesturePageReadiness(root, window.activityClass, RunMode.ACCOUNT_BROWSE).ready
                    "AI_REPLY_PRIVATE_MESSAGES", "AI_REPLY_RECEIVED_GREETINGS" -> {
                        val profileTransaction = if (currentTaskId == "AI_REPLY_PRIVATE_MESSAGES") {
                            sessionComponents.inboxIdentity.pendingInboxIdentityCapture != null
                        } else {
                            sayHiSelectedGreeting != null && sayHiReadOnlyStage in setOf(
                                SayHiReadOnlyStage.AVATAR_TAP_PENDING, SayHiReadOnlyStage.WAITING_PROFILE,
                                SayHiReadOnlyStage.PROFILE_SCROLL_PENDING, SayHiReadOnlyStage.PROFILE_BACK_PENDING
                            )
                        }
                        // Page structure is not conversation identity. Existing business header,
                        // identity, window and per-operation safety checks are still mandatory.
                        val chatConfirmed = chat.pageType == MomoChatPageType.CHAT_DETAIL_CANDIDATE &&
                            chat.hasStableChatDetailFingerprint && !chat.chatTitleText.isNullOrBlank()
                        val cardConfirmed = currentTaskId == "AI_REPLY_RECEIVED_GREETINGS" &&
                            window.activityClass == MomoSayHiPageContract.CARD_ACTIVITY &&
                            pageInspectionComponents.gestureReadinessInspector.hasVisibleViewId(
                                root, setOf(MomoSayHiPageContract.CARD_CONTENT_VIEW_ID, MomoSayHiPageContract.CARD_CLOSE_VIEW_ID)
                            )
                        val privateFollowUp = privateFollowUpController.current()?.takeIf {
                            currentTaskId == "AI_REPLY_PRIVATE_MESSAGES"
                        }
                        if (privateFollowUp != null) {
                            // During the child, another chat is not a continuation of this transaction.
                            // A child popup can coexist with the underlying chat structure.
                            // Reuse its exact active-transaction evidence before classifying a chat.
                            inspectPrivateFollowUpOwnedPage(root, window) ||
                                (chat.pageType == MomoChatPageType.CHAT_DETAIL_CANDIDATE &&
                                    originalPrivateFollowUpChat(privateFollowUp) &&
                                    chat.chatTitleText == privateFollowUp.target.expectedChatTitle)
                        } else chatConfirmed || cardConfirmed || (profileTransaction && isSayHiProfileSurfacePresent())
                    }
                    else -> false
                }
                verified
            } else false
            if (!accessibilityGateway.isWindowCurrent(window)) return@withActiveTargetRoot null
            fingerprint to ownedNestedPage
        }

    /** One read-only capture path for the unified observer and explicit child failures.
     * No ordinary event sampling, no raw node retention, and no additional scheduler. */
    internal fun captureAnomalyPage(taskId: String, runGeneration: Long, explicitReason: String? = null) {
        runCatching {
            val runId = diagnostics.activeRunId() ?: return
            val window = accessibilityGateway.currentGestureWindow() ?: return
            if (window.packageName != targetPackage || window.windowId < 0) return
            val reason = explicitReason ?: diagnostics.pendingPageAnomaly(window, taskId) ?: return
            if (!diagnostics.claimPageAnomaly(runId)) return
            val snapshot = accessibilityGateway.inspectActiveWindow(
                TreeInspectionRequest(maxNodes = 480, maxDepth = 48, maxDurationMs = 300L,
                    textCapture = NodeTextCapture.NONE)
            ) ?: return
            if (snapshot.window != window || snapshot.windowId != window.windowId ||
                !accessibilityGateway.isWindowCurrent(window) || diagnostics.activeRunId() != runId) return
            diagnostics.recordPageAnomaly(runId, taskId, runGeneration, reason, snapshot)
        }
    }

    /** Reuse the existing image-browser readiness check and canonical window identity.
     * No image-content anchors, coordinates or new structural fingerprint are required.
     */
    private fun confirmedImageBrowserIdentity(root: AccessibilityNodeInfo, window: WindowRef): String? {
        if (!isImageBrowserActivity(window.activityClass) ||
            window.packageName != targetPackage || window.windowId < 0 ||
            !accessibilityGateway.isWindowCurrent(window) ||
            observedDialogWindowIdFor(window) != null ||
            browseComponents.pageInspector.hasMomoHomePageNavigationFingerprint(root)
        ) return null
        if (!inspectGesturePageReadiness(root, window.activityClass, RunMode.ACCOUNT_BROWSE).ready ||
            !accessibilityGateway.isWindowCurrent(window)
        ) return null
        return MomoWindowPageFingerprint.windowIdentity(window, targetPackage, BrowsePageKind.IMAGE_VIEWER.name)
    }

    override fun inspectPage(): BrowsePageEvidence = rootAccess.withActiveTargetRoot { root, window ->
        val packageName = safeString { root.packageName?.toString().orEmpty() }
        val activity = window.activityClass
        if (packageName != targetPackage || window.packageName != targetPackage ||
            window.windowId < 0 || !accessibilityGateway.isWindowCurrent(window)
        ) {
            return@withActiveTargetRoot BrowsePageEvidence(
                packageName = packageName,
                activityClass = activity,
                pageKind = BrowsePageKind.UNKNOWN,
                fingerprint = "",
                confidence = 0,
                reason = if (packageName.isBlank()) "当前前台窗口没有可识别的包名"
                    else "当前活动窗口不是已验证的陌陌窗口"
            )
        }

        val readiness = browseComponents.pageInspector.inspectMomoFeedReadiness(root)
        val detail = if (readiness.fingerprint == null) {
            browseComponents.pageInspector.inspectCurrentDynamicDetailIdentity(
                root = root,
                currentActivity = activity
            )
        } else null
        val homeFingerprint = if (readiness.fingerprint == null && detail?.identityConfirmed != true) {
            browseComponents.pageInspector.hasMomoHomePageNavigationFingerprint(root)
        } else false
        val imageBrowserIdentity = if (readiness.fingerprint == null &&
            detail?.identityConfirmed != true && !homeFingerprint
        ) confirmedImageBrowserIdentity(root, window) else null
        val fingerprint = when {
            readiness.fingerprint != null -> readiness.fingerprint.signature
            detail?.identityConfirmed == true -> detail.fingerprint.signature
            homeFingerprint -> "momo-home-navigation-v1"
            else -> browseComponents.pageInspector.pageFingerprint(root).signature
        }
        val kind = when {
            readiness.fingerprint != null -> BrowsePageKind.DYNAMIC_FEED
            detail?.identityConfirmed == true -> BrowsePageKind.DETAIL
            homeFingerprint -> BrowsePageKind.HOME
            imageBrowserIdentity != null -> BrowsePageKind.IMAGE_VIEWER
            else -> BrowsePageKind.OTHER
        }
        val windowScopedFingerprint = imageBrowserIdentity ?: MomoWindowPageFingerprint.create(
            window = window,
            targetPackage = targetPackage,
            pageKind = kind.name,
            structuralSignature = fingerprint
        ).orEmpty()
        if (kind in setOf(BrowsePageKind.HOME, BrowsePageKind.DYNAMIC_FEED, BrowsePageKind.DETAIL) &&
            windowScopedFingerprint.isNotBlank()
        ) {
            browseComponents.pageIdentityMemory.remember(
                window = window,
                pageKind = kind.name,
                fingerprint = windowScopedFingerprint,
                nowUptimeMs = SystemClock.uptimeMillis()
            )
        } else if (kind == BrowsePageKind.OTHER) {
            val dialogWindowId = observedDialogWindowIdFor(window)
            val popup = if (dialogWindowId == null) browseComponents.popupInspector.inspect(root) else null
            val retained = try {
                when {
                    dialogWindowId != null -> browseComponents.pageIdentityMemory.retainForDialog(
                        window, dialogWindowId, SystemClock.uptimeMillis()
                    )
                    popup != null -> browseComponents.pageIdentityMemory.retainForObstruction(window, SystemClock.uptimeMillis())
                    else -> null
                }
            } finally {
                popup?.let(browseComponents.popupInspector::recycle)
            }
            val retainedKind = when (retained?.pageKind) {
                BrowsePageKind.HOME.name -> BrowsePageKind.HOME
                BrowsePageKind.DYNAMIC_FEED.name -> BrowsePageKind.DYNAMIC_FEED
                BrowsePageKind.DETAIL.name -> BrowsePageKind.DETAIL
                else -> null
            }
            if (retained != null && retainedKind != null) {
                return@withActiveTargetRoot BrowsePageEvidence(
                    packageName = packageName,
                    activityClass = activity,
                    pageKind = retainedKind,
                    fingerprint = retained.fingerprint,
                    confidence = 35,
                    reason = "页面遮挡（dialogWindowId=${dialogWindowId ?: "same-window-popup"}）；沿用同一窗口代次内最近确认的${retainedKind.name}身份；" +
                        "仅保留页面上下文，不授权点击/滚动/OCR/BACK，等待遮挡消失后重新识别",
                    nodeCount = 0
                )
            }
        }
        if (windowScopedFingerprint.isBlank()) {
            return@withActiveTargetRoot BrowsePageEvidence(
                packageName = packageName,
                activityClass = activity,
                pageKind = BrowsePageKind.UNKNOWN,
                fingerprint = "",
                confidence = 0,
                reason = "无法建立当前活动窗口的页面身份键"
            )
        }
        BrowsePageEvidence(
            packageName = packageName,
            activityClass = activity,
            pageKind = kind,
            fingerprint = windowScopedFingerprint,
            confidence = when (kind) {
                BrowsePageKind.UNKNOWN -> 0
                BrowsePageKind.OTHER -> 20
                BrowsePageKind.HOME -> 85
                BrowsePageKind.DYNAMIC_FEED, BrowsePageKind.DETAIL -> 100
                BrowsePageKind.IMAGE_VIEWER -> 100
            },
            reason = when (kind) {
                BrowsePageKind.UNKNOWN -> "当前窗口不是目标陌陌窗口"
                BrowsePageKind.DYNAMIC_FEED -> readiness.reason.ifBlank { "已确认动态列表结构指纹" }
                BrowsePageKind.DETAIL -> detail?.reason.orEmpty()
                BrowsePageKind.HOME -> "首页导航结构指纹确认"
                BrowsePageKind.OTHER -> "当前陌陌窗口未匹配已验证的页面结构指纹"
                BrowsePageKind.IMAGE_VIEWER -> "图片动态内容已通过原有图片浏览器安全检查与当前窗口身份确认"
            },
            nodeCount = when {
                readiness.fingerprint != null -> readiness.fingerprint.nodeCount
                detail?.identityConfirmed == true -> detail.fingerprint.nodeCount
                else -> 0
            }
        )
    } ?: BrowsePageEvidence(
        packageName = "",
        activityClass = activityClass(),
        pageKind = BrowsePageKind.UNKNOWN,
        fingerprint = "",
        confidence = 0,
        reason = "当前没有可用的陌陌前台活动窗口",
        windowAvailable = false
    )

    override fun inspectDynamicTab(): DynamicTabEvidence = rootAccess.withActiveTargetRoot { root, window ->
        val tabInspection = browseComponents.pageInspector.inspectDynamicTabRow(root)
        val row = tabInspection.evidence
        val home = browseComponents.pageInspector.inspectHomeBottomNav(root)
        val metrics = context.resources.displayMetrics
        val dynamicTarget = row?.let { evidence ->
            browseComponents.navigationController.findClickableNodeForLabel(
                root = root,
                label = config.page.dynamicTabLabel,
                expectedBounds = evidence.dynamicBounds,
                minCenterY = 0,
                maxCenterY = (metrics.heightPixels * config.page.dynamicTabUpperBandRatio).toInt()
            )
        }
        val homeTarget = home?.let { evidence ->
            browseComponents.navigationController.findClickableNodeForLabel(
                root = root,
                label = config.page.homeTabLabel,
                expectedBounds = evidence.homeBounds,
                minCenterY = (metrics.heightPixels * config.page.homeNavLowerBandRatio).toInt(),
                maxCenterY = metrics.heightPixels
            ) ?: browseComponents.navigationController.findClickableHomeTabContainer(
                root = root,
                expectedLabelBounds = evidence.homeBounds,
                minCenterY = (metrics.heightPixels * config.page.homeNavLowerBandRatio).toInt(),
                maxCenterY = metrics.heightPixels
            )
        }
        try {
            DynamicTabEvidence(
                bounds = row?.dynamicBounds?.toScreenRect(),
                targetBounds = dynamicTarget?.let {
                    val targetBounds = Rect()
                    runCatching { it.getBoundsInScreen(targetBounds) }
                    targetBounds.toScreenRect()
                },
                selected = row?.dynamicSelected ?: false,
                selectionKnown = row?.selectionKnown ?: false,
                homeTabBounds = home?.homeBounds?.toScreenRect(),
                homeTabTargetBounds = homeTarget?.let {
                    val targetBounds = Rect()
                    runCatching { it.getBoundsInScreen(targetBounds) }
                    targetBounds.toScreenRect()
                },
                homeSelected = home?.homeSelected ?: false,
                homeSelectionKnown = home?.selectionKnown ?: false,
                contentTop = row?.contentTop,
                reason = row?.diagnostic ?: "${tabInspection.reason}; ${home?.diagnostic.orEmpty()}",
                targetWindow = window
            )
        } finally {
            runCatching { dynamicTarget?.recycle() }
            runCatching { homeTarget?.recycle() }
        }
    } ?: DynamicTabEvidence(
        bounds = null,
        targetBounds = null,
        selected = false,
        selectionKnown = false,
        homeTabBounds = null,
        homeTabTargetBounds = null,
        homeSelected = false,
        homeSelectionKnown = false,
        contentTop = null,
        reason = "当前没有可用的陌陌活动窗口",
        targetWindow = null
    )

    override fun inspectPopup(): PopupEvidence? = rootAccess.withActiveTargetRoot { root, window ->
        val popup = browseComponents.popupInspector.inspect(root) ?: return@withActiveTargetRoot null
        try {
            PopupEvidence(
                signature = popup.signature,
                dismissable = popup.dismissable,
                critical = popup.critical,
                dismissTarget = popup.container?.let { rootAccess.targetFor(root, it, window) },
                closeBounds = popup.closeTapBounds?.toScreenRect(),
                closeTargetBounds = popup.closeTarget?.let {
                    val targetBounds = Rect()
                    runCatching { it.getBoundsInScreen(targetBounds) }
                    targetBounds.toScreenRect()
                },
                description = popup.description,
                targetWindow = window
            )
        } finally {
            popup?.let(browseComponents.popupInspector::recycle)
        }
    }

    override fun inspectExternalPopup(): PopupEvidence? = rootAccess.withActiveWindowRoot { root ->
        val packageName = safeString { root.packageName?.toString().orEmpty() }
        if (packageName == targetPackage) return@withActiveWindowRoot null
        val popup = browseComponents.popupInspector.inspect(root) ?: return@withActiveWindowRoot null
        try {
            PopupEvidence(
                signature = popup.signature,
                dismissable = false,
                critical = popup.critical,
                dismissTarget = null,
                closeBounds = null,
                closeTargetBounds = null,
                description = popup.description
            )
        } finally {
            popup?.let(browseComponents.popupInspector::recycle)
        }
    }

    override fun inspectFeed(): FeedEvidence = rootAccess.withActiveTargetRoot { root, window ->
        if (!accessibilityGateway.isWindowCurrent(window)) {
            return@withActiveTargetRoot FeedEvidence(false, "", 0, null, "动态列表快照窗口已过期")
        }
        val blockingDialogWindowId = observedDialogWindowIdFor(window)
        if (blockingDialogWindowId != null) {
            return@withActiveTargetRoot FeedEvidence(
                ready = false,
                fingerprint = "",
                visibleCardCount = 0,
                scrollBounds = null,
                reason = "独立弹窗 windowId=$blockingDialogWindowId 仍存在；保留页面身份但不允许滚动/选卡，等待新快照"
            )
        }
        val readiness = browseComponents.pageInspector.inspectMomoFeedReadiness(root)
        val tab = browseComponents.pageInspector.inspectDynamicTabRow(root).evidence
        val visibleContainer = tab?.let { browseComponents.pageInspector.findVisibleFeedContainer(root, it.contentTop) }
        val scrollContainer = browseComponents.feedGestureController.findScrollableFeedContainer(root)
        try {
            val structuralFingerprint = readiness.fingerprint?.signature.orEmpty()
            val windowScopedFingerprint = if (structuralFingerprint.isNotBlank()) {
                MomoWindowPageFingerprint.create(
                    window = window,
                    targetPackage = targetPackage,
                    pageKind = "DYNAMIC_FEED",
                    structuralSignature = structuralFingerprint
                ).orEmpty()
            } else ""
            FeedEvidence(
                ready = windowScopedFingerprint.isNotBlank() &&
                    accessibilityGateway.isWindowCurrent(window),
                fingerprint = windowScopedFingerprint,
                visibleCardCount = visibleContainer?.let(browseComponents.pageInspector::countVisibleDirectChildren) ?: 0,
                scrollBounds = scrollContainer?.let {
                    val bounds = Rect()
                    runCatching { it.getBoundsInScreen(bounds) }
                    bounds.toScreenRect()
                },
                reason = readiness.reason
            )
        } finally {
            runCatching { visibleContainer?.recycle() }
            runCatching { scrollContainer?.recycle() }
        }
    } ?: FeedEvidence(false, "", 0, null, "当前没有可用的陌陌活动窗口")

    override fun loadFeedGestureProfile(): FeedGestureProfile =
        browseComponents.feedGestureController.loadScrollGestureProfile().let { profile ->
            FeedGestureProfile(durationMillis = profile.durationMs)
        }

    override fun planFeedGesture(profile: FeedGestureProfile): FeedGesturePlan? =
        rootAccess.withActiveTargetRoot { root, window ->
            val target = browseComponents.feedGestureController.resolveFeedGestureTarget(root)
                ?: return@withActiveTargetRoot null
            FeedGesturePlan(
                bounds = target.bounds.toScreenRect(),
                className = target.className,
                profile = profile,
                targetWindow = window
            )
        }

    override fun captureVisibleFeedSnapshot(listOnly: Boolean): String =
        rootAccess.withActiveTargetRoot { root, _ ->
            if (!listOnly) {
                browseComponents.feedGestureController.captureVisibleFeedNodeSnapshot(root)
            } else {
                val list = browseComponents.feedGestureController.findScrollableFeedContainer(root)
                    ?: return@withActiveTargetRoot ""
                try {
                    browseComponents.feedGestureController.captureVisibleFeedNodeSnapshot(list)
                } finally {
                    runCatching { list.recycle() }
                }
            }
        }.orEmpty()

    override fun selectVisibleFeedCard(
        minimumWidthPx: Int,
        minimumHeightPx: Int
    ): FeedSelectionResult = rootAccess.withActiveTargetRoot { root, window ->
        val list = browseComponents.feedGestureController.findScrollableFeedContainer(root)
            ?: return@withActiveTargetRoot FeedSelectionResult(
                null, 0, 0, 0, 0, "未找到可安全滚动的动态列表容器"
            )
        val sourceFingerprint = browseComponents.pageInspector.pageFingerprint(root).signature
        val sourceSnapshot = try {
            browseComponents.feedGestureController.captureVisibleFeedNodeSnapshot(list)
        } catch (_: Exception) {
            ""
        }
        val selection = try {
            MomoDynamicCardSelector.select(list, minimumWidthPx, minimumHeightPx)
        } finally {
            runCatching { list.recycle() }
        }
        val selectedNode = selection.node
        val candidate = selectedNode?.let { node ->
            try {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) null else FeedCardCandidate(
                    bounds = bounds.toScreenRect(),
                    cardType = FeedCardType.NORMAL_SOCIAL,
                    fingerprint = "${safeString { node.className?.toString().orEmpty() }}@${bounds.toShortString()}",
                    targetWindow = window
                )
            } finally {
                runCatching { node.recycle() }
            }
        }
        FeedSelectionResult(
            candidate = candidate,
            inspectedCount = selection.inspectedCardCount,
            skippedAdvertisements = selection.skippedAdvertisementCount,
            skippedLiveStreams = selection.skippedLiveStreamCount,
            skippedUnknown = selection.skippedUnknownCount,
            reason = selection.reason,
            sourceFingerprint = sourceFingerprint,
            sourceSnapshot = sourceSnapshot
        )
    } ?: FeedSelectionResult(null, 0, 0, 0, 0, "当前没有可用的陌陌活动窗口")

    /** Read only the image counter; never use it to identify or authorize a page. */
    override fun readImageIndex(expectedFingerprint: String): ImageIndexReadResult =
        runCatching<ImageIndexReadResult> {
            rootAccess.withActiveTargetRoot { root, window ->
                val fingerprint = confirmedImageBrowserIdentity(root, window)
                if (fingerprint == null || fingerprint != expectedFingerprint ||
                    root.packageName?.toString() != targetPackage ||
                    root.windowId != window.windowId
                ) return@withActiveTargetRoot ImageIndexReadResult.Unavailable(
                    ImageIndexUnavailableReason.WINDOW_NOT_CONFIRMED
                )

                val viewId = "$targetPackage:id/include_feed_tv_index"
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
                try {
                    val visible = nodes.filter { it.isVisibleToUser }
                    if (visible.any {
                            it.viewIdResourceName != viewId ||
                                it.packageName?.toString() != targetPackage ||
                                it.windowId != window.windowId
                        } || !accessibilityGateway.isWindowCurrent(window)
                    ) return@withActiveTargetRoot ImageIndexReadResult.Unavailable(
                        ImageIndexUnavailableReason.WINDOW_NOT_CONFIRMED
                    )
                    val labels = visible.map { it.text?.toString().orEmpty() }
                    if (!accessibilityGateway.isWindowCurrent(window)) {
                        ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.WINDOW_NOT_CONFIRMED)
                    } else {
                        val bounds = Rect()
                        root.getBoundsInScreen(bounds)
                        val result = ImageIndexReadResult.fromVisibleLabels(labels, fingerprint, window)
                        if (!accessibilityGateway.isWindowCurrent(window)) {
                            ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.WINDOW_NOT_CONFIRMED)
                        } else if (result is ImageIndexReadResult.Confirmed && !bounds.isEmpty) {
                            result.copy(viewport = bounds.toScreenRect())
                        } else result
                    }
                } finally {
                    nodes.forEach { node -> runCatching { node.recycle() } }
                }
            } ?: ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.WINDOW_NOT_CONFIRMED)
        }.getOrElse { ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.NODE_READ_FAILED) }

    override fun inspectDetail(expectedFingerprint: String): DetailEvidence =
        rootAccess.withActiveTargetRoot { root, window ->
            if (window.packageName != targetPackage || window.windowId < 0 ||
                !accessibilityGateway.isWindowCurrent(window)
            ) {
                return@withActiveTargetRoot DetailEvidence(
                    false, false, "", "动态详情检查时活动窗口身份已变化"
                )
            }
            val blockingDialogWindowId = observedDialogWindowIdFor(window)
            if (blockingDialogWindowId != null) {
                return@withActiveTargetRoot DetailEvidence(
                    isFeedPage = false,
                    identityConfirmed = false,
                    fingerprint = "",
                    reason = "动态详情仍有独立弹窗 windowId=$blockingDialogWindowId；等待遮挡消失后重新确认"
                )
            }
            val result = browseComponents.pageInspector.inspectCurrentDynamicDetailIdentity(
                root = root,
                currentActivity = window.activityClass
            )
            if (!result.isFeedPage && !result.identityConfirmed) {
                val imageIdentity = confirmedImageBrowserIdentity(root, window)
                if (imageIdentity != null) {
                    return@withActiveTargetRoot DetailEvidence(
                        isFeedPage = false,
                        // Like ordinary detail, fresh evidence can rebind after a window
                        // generation change. The workflow compares two fresh identities
                        // before BACK rather than authorizing from the old fingerprint.
                        identityConfirmed = true,
                        fingerprint = imageIdentity,
                        reason = "图片动态内容；复用现有页面安全检查与当前窗口代次"
                    )
                }
            }
            val scopedFingerprint = MomoWindowPageFingerprint.create(
                window = window,
                targetPackage = targetPackage,
                pageKind = "DETAIL",
                structuralSignature = result.fingerprint.signature
            ).orEmpty()
            DetailEvidence(
                isFeedPage = result.isFeedPage,
                identityConfirmed = result.identityConfirmed && accessibilityGateway.isWindowCurrent(window),
                fingerprint = scopedFingerprint,
                reason = result.reason,
                nodeCount = result.fingerprint.nodeCount
            )
        } ?: DetailEvidence(false, false, "", "当前没有可用的陌陌活动窗口")

    override fun inspectFeedReturn(expectedFingerprint: String): FeedReturnEvidence =
        rootAccess.withActiveTargetRoot { root, window ->
            if (window.packageName != targetPackage || window.windowId < 0 ||
                !accessibilityGateway.isWindowCurrent(window)
            ) return@withActiveTargetRoot FeedReturnEvidence(
                false, false, "", "动态列表返回检查时活动窗口身份已变化"
            )
            val readiness = browseComponents.pageInspector.inspectMomoFeedReadiness(root)
            val structuralFingerprint = readiness.fingerprint?.signature.orEmpty()
            val fingerprint = if (structuralFingerprint.isNotBlank()) {
                MomoWindowPageFingerprint.create(
                    window = window,
                    targetPackage = targetPackage,
                    pageKind = "DYNAMIC_FEED",
                    structuralSignature = structuralFingerprint
                ).orEmpty()
            } else ""
            val restored = fingerprint.isNotBlank()
            FeedReturnEvidence(
                restored = restored,
                stable = restored && expectedFingerprint.isNotBlank() && fingerprint == expectedFingerprint &&
                    accessibilityGateway.isWindowCurrent(window),
                fingerprint = fingerprint,
                reason = if (restored) readiness.reason else "动态 Feed 尚未重新出现"
            )
        } ?: FeedReturnEvidence(false, false, "", "当前没有可用的陌陌活动窗口")

    // ---- ChatPagePort: AIReply sees only generic structure and NodeTarget locators ----

    /**
     * Captures redacted navigation evidence on non-SayHi Momo pages, including the message-home
     * entry and any interception settings page. Candidate controls are never clicked here.
     */
    private fun inspectMomoNavigationEvidenceIfNeeded() {
        val activityHint = activityClass()
        if (isReceivedGreetingsListStructureVisible() ||
            activityHint == MomoSayHiPageContract.CARD_ACTIVITY
        ) return
        val now = System.currentTimeMillis()
        if (now - lastMomoNavigationEvidenceAtMs < NAVIGATION_EVIDENCE_INTERVAL_MS) return
        lastMomoNavigationEvidenceAtMs = now
        val tree = accessibilityGateway.inspectActiveWindow(
            TreeInspectionRequest(
                maxNodes = NAVIGATION_EVIDENCE_MAX_NODES,
                textCapture = NodeTextCapture.EPHEMERAL
            )
        ) ?: return
        if (tree.window.packageName != targetPackage) return
        recordMomoNavigationEvidence(tree)
    }

    /** Stores only structural metadata; raw node text remains ephemeral and is never logged. */
    private fun recordMomoNavigationEvidence(
        tree: com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
    ) {
        val metrics = runCatching { accessibilityGateway.displayMetrics() }.getOrNull()
        val evidence = sayHiNavigationEvidenceInspector.inspect(
            tree = tree,
            screenWidth = metrics?.widthPixels ?: 0,
            screenHeight = metrics?.heightPixels ?: 0
        )
        val signature = evidence.structuralKey()
        if (signature == lastSayHiNavigationEvidenceSignature) return
        lastSayHiNavigationEvidenceSignature = signature
        onLog(
            "陌陌导航结构取证：Activity=${evidence.activityClass};" +
                "扫描节点=${evidence.visitedNodeCount};截断=${evidence.truncated};" +
                "收到招呼入口候选=${evidence.receivedGreetingsEntryTargets.size};" +
                "入口节点=[${sayHiNavigationEvidenceInspector.describeNavigationCandidates(evidence.receivedGreetingsEntryTargets, MAX_NAVIGATION_EVIDENCE_LOG_ITEMS)}];" +
                "右上角可点击候选=${evidence.topRightActionTargets.size};" +
                "右上角节点=[${sayHiNavigationEvidenceInspector.describeNavigationCandidates(evidence.topRightActionTargets, MAX_NAVIGATION_EVIDENCE_LOG_ITEMS)}];" +
                "拦截相关文本节点=${evidence.interceptLabelNodeCount};" +
                "开关候选=${evidence.toggleTargets.size};" +
                "开关节点=[${sayHiNavigationEvidenceInspector.describeNavigationCandidates(evidence.toggleTargets, MAX_NAVIGATION_EVIDENCE_LOG_ITEMS)}]"
        )
    }

    /**
     * Entry point for the explicit received-greetings branch. The runtime periodically invokes
     * this bounded step while CHAT_LIST remains visible so entry discovery survives viewport misses.
     */
    override fun supportsReceivedGreetingsIdleWorkflow(): Boolean = true

    override fun observeReceivedGreetingsPage(): Boolean =
        inspectSayHiPageAndAutonomousBrowse(allowAutonomousScroll = false, minimumIntervalMs = 300L)

    /**
     * Page-arrival watcher used after a real user-navigation event. It reuses the existing
     * Received Greetings fingerprint and complete list-container evidence, but does not require
     * the automated profile-card/list-button navigation checkpoint.
     */
    override fun observeManuallyOpenedReceivedGreetingsPage(): ReceivedGreetingsPageArrivalStatus {
        val currentActivity = activityClass()
        if (currentActivity.isBlank()) return ReceivedGreetingsPageArrivalStatus.UNKNOWN

        sayHiNavigationFlowController.manualReceivedGreetingsArrivalProbeActive = true
        val inspected = try {
            inspectSayHiPageAndAutonomousBrowse(
                allowAutonomousScroll = false,
                minimumIntervalMs = 0L,
                forceFresh = true
            )
        } finally {
            sayHiNavigationFlowController.manualReceivedGreetingsArrivalProbeActive = false
        }

        val listFingerprintVisible = isReceivedGreetingsListStructureVisible()
        return when {
            sayHiNavigationFlowController.isReceivedListConfirmed() && listFingerprintVisible ->
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
            sayHiNavigationFlowController.isManuallyStabilizingReceivedList() || listFingerprintVisible ->
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING
            currentActivity == MomoSayHiPageContract.CARD_ACTIVITY ||
                currentActivity == MomoSayHiPageContract.LIST_ACTIVITY ||
                currentActivity.contains(".message.sayhi.", ignoreCase = true) ->
                ReceivedGreetingsPageArrivalStatus.INTERMEDIATE_PAGE
            currentActivity == MomoAppDescriptor.MAIN_ACTIVITY_CLASS ->
                ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION
            inspected -> ReceivedGreetingsPageArrivalStatus.UNKNOWN
            else -> ReceivedGreetingsPageArrivalStatus.NOT_TARGET
        }
    }

    /**
     * Uses exactly the final start gates in the shared recovery preflight. No gesture is
     * admitted here; each caller takes a fresh snapshot and fails closed on missing evidence.
     */
    private fun sampleReceivedGreetingsListAdmission(): MomoReceivedGreetingsAdmissionDiagnostic {
        val confirmedTarget = sayHiConfirmedListTarget
        return MomoReceivedGreetingsAdmissionDiagnostic(
            listConfirmed = sayHiNavigationFlowController.isReceivedListConfirmed(),
            listStructureVisible = runCatching { isReceivedGreetingsListStructureVisible() }.getOrDefault(false),
            windowTargetPresent = confirmedTarget != null,
            windowCurrent = runCatching {
                confirmedTarget?.let { accessibilityGateway.isWindowCurrent(it.window) } == true
            }.getOrDefault(false),
            sayHiGestureInFlight = sayHiGestureInFlight,
            readOnlyStage = sayHiReadOnlyStage.name,
            entrySearchGestureInFlight = receivedGreetingsEntrySearchGestureInFlight
        )
    }

    /** Starts the existing read-only browser on the already confirmed current list. */
    override fun beginReceivedGreetingsTaskFromCurrentList(): Boolean {
        val admission = sampleReceivedGreetingsListAdmission()
        if (!admission.isReadyForCurrentList()) {
            onLog("收到的招呼人工入口接管[REJECTED]：" +
                admission.toLogFields() + "；保持页面不动")
            return false
        }

        completionBackStage = CompletionBackStage.NONE
        completionBackRetryGate.reset()
        resetReceivedGreetingsEntrySearch()
        sayHiAutonomousBrowseController.reset()
        sayHiTraversalState.prepareForListReentry()
        clearSayHiSelectedGreeting()
        resetSayHiRowTapTransaction()
        sayHiNavigationFlowController.receivedGreetingsEntryTapPending = false
        sayHiNavigationFlowController.sayHiCardListTapPending = false
        // The same logical scan may be resumed through a manually confirmed list.
        // Its direction and outstanding rows are not reset by admission.
        sayHiStableCandidateKey = ""
        sayHiStableCandidateTarget = null
        sayHiStableCandidateWindow = null
        onLog("收到的招呼人工入口接管[ACCEPTED]：复核当前活动窗口和已确认 listview；从当前列表启动会话遍历，不执行入口导航")
        return true
    }

    /**
     * Bridges the shared route confirmation into the adapter's window-scoped list state. The
     * existing read-only manual-list observer remains the authority for locating the current
     * list container and setting sayHiConfirmedListTarget; no navigation or gesture is issued.
     */
    override fun synchronizeReceivedGreetingsRecoveryConfirmation(): Boolean {
        var status = observeManuallyOpenedReceivedGreetingsPage()
        if (status != ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED) {
            status = observeManuallyOpenedReceivedGreetingsPage()
        }
        val admission = sampleReceivedGreetingsListAdmission()
        val confirmed = status == ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED &&
            admission.isReadyForCurrentList()
        onLog(
            "收到的招呼统一恢复确认同步：结果=${if (confirmed) "已同步" else "未同步"}；" +
                "adapterList=${admission.listConfirmed}；" +
                "windowTarget=${admission.windowTargetPresent}；" +
                "admissionReady=${admission.isReadyForCurrentList()}；" +
                "failedGates=${admission.rejectedGates().joinToString(",").ifEmpty { "NONE" }}；" +
                "readOnlyStage=${admission.readOnlyStage}"
        )
        return confirmed
    }

    override fun beginReceivedGreetingsNavigationTask() {
        completionBackStage = CompletionBackStage.NONE
        completionBackRetryGate.reset()
        // The run-local inbox position memory survives this task handoff; only the
        // previous navigation sweep is retired. The next live inbox read revalidates it.
        resetReceivedGreetingsEntrySearch()
        sayHiTraversalState.prepareForListReentry()
    }

    override fun performReceivedGreetingsIdleStep(): Boolean {
        // Do not inspect/tap a candidate while the bounded discovery swipe is still running.
        // The next fresh page snapshot owns the next decision; this also prevents a recycled
        // row or transiently visible label from racing the gesture callback.
        if (receivedGreetingsEntrySearchGestureInFlight || hasSayHiVerifiedReturnReceipts()) return true

        val currentActivity = activityClass()
        when (sayHiReadOnlyStage) {
            SayHiReadOnlyStage.AVATAR_TAP_PENDING,
            SayHiReadOnlyStage.ROW_TAP_PENDING -> {
                if (System.currentTimeMillis() - sayHiReadOnlyStageStartedAtMs > SAYHI_GESTURE_TERMINAL_TIMEOUT_MS) {
                    if (sayHiGestureInFlight) {
                        onLog("收到招呼手势等待超时：协调器仍报告手势在途；继续等待平台终态，不重复派发")
                        return true
                    }
                    when {
                        inspectCurrentChatDetailFingerprint() != null -> {
                            sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_DETAIL
                            onLog("收到招呼手势回调超时：新鲜结构已确认聊天详情候选；保留事务，继续等待点击来源核验，不启动 OCR")
                            return true
                        }
                        isSayHiProfileSurfacePresent() -> {
                            sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_PROFILE
                            sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                            onLog("收到招呼手势回调超时：当前为资料页；转入资料页独立识别与安全返回流程")
                            return inspectPendingSayHiAvatarProfile(currentActivity)
                        }
                        isReceivedGreetingsListStructureVisible() -> {
                            sayHiSelectedGreeting?.let { deferSayHiRow(it.rowKey, "GESTURE_TERMINAL_CALLBACK_TIMEOUT") }
                            clearSayHiSelectedGreeting()
                            resetSayHiRowTapTransaction()
                            sayHiReadOnlyStage = SayHiReadOnlyStage.IDLE
                            onLog("收到招呼手势回调超时：仍在已识别的招呼列表；隔离当前用户并继续其他用户，不重复派发")
                            return inspectSayHiPageAndAutonomousBrowse(
                                allowAutonomousScroll = true, minimumIntervalMs = 0L, forceFresh = true
                            )
                        }
                        else -> {
                            onLog("收到招呼手势回调超时：当前页面仍无法确认；保留用户和事务，等待新鲜页面快照，不盲目点击或返回")
                            return true
                        }
                    }
                }
                return true
            }
            SayHiReadOnlyStage.WAITING_PROFILE -> return inspectPendingSayHiAvatarProfile(currentActivity)
            SayHiReadOnlyStage.PROFILE_SCROLL_PENDING -> {
                if (System.currentTimeMillis() - sayHiReadOnlyStageStartedAtMs > SAYHI_PROFILE_SCROLL_TIMEOUT_MS) {
                    sayHiProfileScrollRequestId = null
                    sayHiProfileScrollInFlight = false
                    sayHiNonSearchGestureInFlight = false
                    sayHiProfileScrollFailed = true
                    sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_PROFILE
                    sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                    onLog("收到招呼身份采集：资料页单次向上滑动等待超时；不重复派发，转入安全返回")
                }
                return true
            }
            SayHiReadOnlyStage.PROFILE_BACK_PENDING -> {
                if (sayHiProfileBackDialogPending) {
                    when (rootAccess.isTargetWindowPresent(sayHiProfileBackDialogWindowId)) {
                        true -> return true
                        null -> {
                            onLog("收到招呼资料页返回：无法确认 dialog.h 是否仍存在；保持暂停，不继续执行第二次 BACK")
                            return true
                        }
                        false -> {
                            sayHiProfileBackDialogPending = false
                            sayHiProfileBackDialogWindowId = -1
                            val activePage = rootAccess.withActiveTargetRoot { _, window -> window }
                            if (activePage == null) return true
                            if (isReceivedGreetingsListConfirmed()) {
                                sayHiProfileBackWindowId = -1
                                onLog("收到招呼资料页返回：dialog.h 已消失；当前窗口与列表结构指纹已确认；Activity 和操作准入由现有 READY 复核")
                                return inspectSayHiPageAndAutonomousBrowse(
                                    allowAutonomousScroll = true, minimumIntervalMs = 0L, forceFresh = true
                                )
                            }
                            if (isReceivedGreetingsListStructureVisible()) {
                                onLog("收到招呼资料页返回：dialog.h 已消失且收到招呼列表结构指纹已出现；等待稳定快照确认")
                                return true
                            }
                            if (currentActivity == MomoSayHiPageContract.PROFILE_ACTIVITY &&
                                activePage.activityClass == MomoSayHiPageContract.PROFILE_ACTIVITY
                            ) {
                                onLog("收到招呼资料页返回：已确认 dialog.h 消失且资料页重新活动；重新执行独立安全检查后再返回列表")
                                return requestSayHiProfileBack()
                            }
                            if (isReceivedGreetingsListStructureVisible()) {
                                onLog("收到招呼资料页返回：Activity 已变化但列表容器尚未稳定；等待新鲜快照")
                                return true
                            }
                        }
                    }
                }
                if (isReceivedGreetingsListConfirmed()) {
                    sayHiProfileBackWindowId = -1
                    onLog("收到招呼资料页返回确认：当前窗口与列表结构指纹已重新验证；Activity 和操作准入由现有 READY 复核")
                    return inspectSayHiPageAndAutonomousBrowse(
                        allowAutonomousScroll = true, minimumIntervalMs = 0L, forceFresh = true
                    )
                }
                if (System.currentTimeMillis() - sayHiReadOnlyStageStartedAtMs > SAYHI_PROFILE_BACK_TIMEOUT_MS) {
                    if (currentActivity == MomoSayHiPageContract.PROFILE_ACTIVITY &&
                        !sayHiProfileBackDialogPending && sayHiProfileBackAttempts < MAX_SAYHI_PROFILE_BACK_ATTEMPTS
                    ) {
                        onLog("收到招呼资料页返回超时：当前资料页仍通过 Activity 识别且无待关闭弹层；重新执行一次独立窗口/弹层安全检查")
                        return requestSayHiProfileBack()
                    }
                    onLog("收到招呼资料页返回等待仍未确认：保留当前页面与用户断点，继续低频获取新鲜窗口快照；不盲目重复 BACK")
                }
                return true
            }
            SayHiReadOnlyStage.WAITING_DETAIL -> {
                if (sayHiRowTapFeedbackState == SayHiRowTapFeedbackState.WAITING &&
                    pendingSayHiRowTapSubmittedAtUptimeMs > 0L &&
                    SystemClock.uptimeMillis() - pendingSayHiRowTapSubmittedAtUptimeMs >=
                    SAYHI_CLICK_EVENT_SETTLE_MS
                ) {
                    // A missing event at the deadline is inconclusive, not proof that the tap
                    // hit the wrong control. Keep the transaction alive for late event delivery.
                    sayHiRowTapFeedbackState = SayHiRowTapFeedbackState.TIMED_OUT
                    onLog("收到招呼点击事件核验[TIMEOUT_PENDING]：真实派发后的反馈窗口已到期；不判定 REJECTED，保留点击事务并继续页面复核")
                }
                if (sayHiRowTapFeedbackState == SayHiRowTapFeedbackState.REJECTED) {
                    if (isReceivedGreetingsListStructureVisible()) {
                        sayHiSelectedGreeting?.let { deferSayHiRow(it.rowKey, "DETAIL_NAVIGATION_UNVERIFIED") }
                        val reason = sayHiRowTapHitReason
                        clearSayHiSelectedGreeting()
                        onLog("收到招呼点击失败：实际命中非行容器控件（$reason）；仍在原列表，不执行 BACK、不执行 OCR；重新识别列表")
                        return inspectSayHiPageAndAutonomousBrowse(
                            allowAutonomousScroll = true, minimumIntervalMs = 0L, forceFresh = true
                        )
                    }
                    return returnFromUnverifiedSayHiDetailNavigation(
                        currentActivity,
                        "CLICKED_UNEXPECTED_CONTROL:$sayHiRowTapHitReason"
                    )
                }
                val chatDetailFingerprintConfirmed = inspectCurrentChatDetailFingerprint() != null
                if (chatDetailFingerprintConfirmed) {
                    val fingerprintConfirmed = sayHiRowTapFeedbackState == SayHiRowTapFeedbackState.VERIFIED
                    if (fingerprintConfirmed) return true
                    val detailWaitElapsedMs =
                        (System.currentTimeMillis() - sayHiReadOnlyStageStartedAtMs).coerceAtLeast(0L)
                    if (detailWaitElapsedMs < SAYHI_ROW_DETAIL_VERIFY_TIMEOUT_MS) return true
                    if (sayHiRowTapFeedbackState == SayHiRowTapFeedbackState.TIMED_OUT &&
                        !sayHiUnverifiedBackAttempted
                    ) {
                        // The destination page is independently fingerprinted, but the source tap
                        // event never arrived. Do not read the conversation. Use only the
                        // existing exact-window + overlay-checked ChatActivity BACK, then defer
                        // this row after the received-greetings list is independently confirmed.
                        sayHiUnverifiedBackAttempted = true
                        val sourceWindow = inspectCurrentChatDetailFingerprint()?.first
                        var submittedWindow: WindowRef? = null
                        val accepted = sourceWindow?.let { expectedWindow ->
                            requestChatActivityBack(
                                reason = "收到招呼点击反馈超时后的安全单用户隔离回退；不执行 OCR",
                                expectedWindow = expectedWindow,
                                onSubmitted = { submittedWindow = it }
                            )
                        } ?: false
                        if (accepted) {
                            sayHiUnverifiedDetailReturnPending = true
                            sayHiReadOnlyStage = SayHiReadOnlyStage.DETAIL_BACK_PENDING
                            sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                            resetSayHiDetailBackReturnTracking()
                            sayHiDetailBackSourceWindow = submittedWindow
                            onLog(
                                "收到招呼点击反馈超时恢复：聊天详情指纹已独立确认，但来源事件缺失；" +
                                    "已通过精确窗口/弹层检查安全返回；本用户记为待恢复，不读取消息、不计入成功；等待列表确认"
                            )
                            return true
                        }
                    }
                    onLog(
                        "收到招呼详情页指纹确认暂未满足：Activity 虽为 ChatActivity，但点击来源或页面指纹尚未全部确认；" +
                            "不执行 OCR；安全回退未获准时保留当前事务并请求新快照复核"
                    )
                    return true
                }
                if (currentActivity == MomoSayHiPageContract.PROFILE_ACTIVITY) {
                    return returnFromUnverifiedSayHiDetailNavigation(
                        currentActivity,
                        "UNEXPECTED_PROFILE_ACTIVITY:$currentActivity"
                    )
                }
                if (System.currentTimeMillis() - sayHiReadOnlyStageStartedAtMs < SAYHI_ROW_DETAIL_VERIFY_TIMEOUT_MS) {
                    return true
                }
                if (isReceivedGreetingsListStructureVisible()) {
                    // The row tap produced no page transition. Never issue BACK from the list,
                    // which could leave the received-greetings workflow entirely.
                    sayHiSelectedGreeting?.let { deferSayHiRow(it.rowKey, "ROW_TAP_NO_PAGE_TRANSITION") }
                    clearSayHiSelectedGreeting()
                    resetSayHiRowTapTransaction()
                    sayHiReadOnlyStage = SayHiReadOnlyStage.IDLE
                    onLog("收到招呼只读详情导航：行区域点击后仍停留在原列表；本行隔离并安排后续复核，不执行返回或立即重复点击")
                    return inspectSayHiPageAndAutonomousBrowse(
                        allowAutonomousScroll = true, minimumIntervalMs = 0L, forceFresh = true
                    )
                }
                onLog("收到招呼只读详情导航：未在限定时间内确认陌陌聊天 Activity；不执行输入或发送，安全返回")
                return returnFromUnverifiedSayHiDetailNavigation(currentActivity)
            }
            SayHiReadOnlyStage.DETAIL_BACK_PENDING -> {
                val nowUptimeMs = SystemClock.uptimeMillis()
                val listWindowIdentity = receivedGreetingsListWindowIdentity()
                if (sayHiDetailBackReturnConfirmation.observe(listWindowIdentity, nowUptimeMs)) {
                    val pendingRow = sayHiTraversalState.sayHiPendingVerifiedReturnRow
                    if (pendingRow != null) {
                        val proof = runCatching { sayHiSendReturnJournal.findByRow(pendingRow) }.getOrNull()
                        if (proof == null || !runCatching {
                                sayHiSendReturnJournal.confirmVerifiedReturn(proof.rowKeyHash, proof.contactIdHash)
                            }.getOrDefault(false)
                        ) {
                            onLog("GREETING_RETURN_CONFIRMATION_DEFERRED reason=RETURN_FACT_PERSIST_FAILED")
                            return true
                        }
                        // Cursor progress is a consequence of persisted RETURN_VERIFIED, never
                        // of a BACK gesture callback. The verified source row need not remain unread.
                        sayHiTraversalState.markProcessedRow(proof.rowKeyHash)
                        sayHiTraversalState.markProcessedContactHash(proof.contactIdHash)
                        clearSayHiRowDeferral(proof.rowKeyHash)
                        recordSayHiVerifiedReturnReceipt(proof.sendConfirmationHash)
                        sayHiTraversalState.sayHiPendingVerifiedReturnRow = null
                        onLog("GREETING_RETURN_VERIFIED：已通过原列表双快照，独立持久化返回事实并推进游标")
                    }
                    if (sayHiUnverifiedDetailReturnPending) {
                        sayHiSelectedGreeting?.let {
                            deferSayHiRow(it.rowKey, "CLICK_FEEDBACK_UNVERIFIED_AFTER_DETAIL")
                        }
                        sayHiUnverifiedDetailReturnPending = false
                        onLog("收到招呼单用户隔离：点击来源未核验，仅延期原行；不标记已发送")
                    }
                    onLog(
                        "收到招呼详情页返回确认：当前窗口和列表结构指纹" +
                            "已由连续 ${sayHiDetailBackReturnConfirmation.stableSampleCount} 份新鲜快照确认；" +
                            "Activity 和操作准入由现有 READY 独立复核；window=$listWindowIdentity"
                    )
                    clearSayHiSelectedGreeting()
                    // Yield the verified session boundary before selecting another row.
                    // The next unified observation decides whether to continue or hand off.
                    return true
                }
                if (listWindowIdentity != null && sayHiDetailBackReturnConfirmation.stableSampleCount == 1) {
                    onLog(
                        "收到招呼详情页返回候选：已识别当前窗口和列表结构指纹；尚非操作准入；" +
                            "等待至少 500ms 后的第二份新鲜快照；window=$listWindowIdentity"
                    )
                }

                val nowWallClockMs = System.currentTimeMillis()
                val elapsedMs = (nowWallClockMs - sayHiReadOnlyStageStartedAtMs).coerceAtLeast(0L)
                if (elapsedMs >= SAYHI_DETAIL_BACK_TIMEOUT_MS &&
                    sayHiDetailBackRecoveryWindowStartedAtUptimeMs == 0L
                ) {
                    sayHiDetailBackRecoveryWindowStartedAtUptimeMs = nowUptimeMs
                    onLog(
                        "收到招呼详情页返回等待超时：已等待 ${elapsedMs}ms，未完成连续列表确认；" +
                            "进入额外 ${SAYHI_DETAIL_BACK_RECOVERY_TIMEOUT_MS}ms 安全恢复窗口，" +
                            "仅当当前仍是 ChatActivity 且窗口/弹层复核通过时允许一次补救 BACK"
                    )
                }

                if (sayHiDetailBackRecoveryWindowStartedAtUptimeMs > 0L &&
                    !sayHiDetailBackRecoveryAttempted &&
                    inspectCurrentChatDetailFingerprint() != null
                ) {
                    // Mark before dispatch. A rejected/uncertain action must never become a loop.
                    sayHiDetailBackRecoveryAttempted = true
                    val sourceWindow = sayHiDetailBackSourceWindow
                    val accepted = sourceWindow?.let { expectedWindow ->
                        requestChatActivityBack(
                            reason = "收到招呼详情页首次返回超时后的单次安全补救",
                            expectedWindow = expectedWindow
                        )
                    } ?: false
                    onLog(
                        "收到招呼详情页返回补救：仍处于 ChatActivity；" +
                            "原始窗口引用可用=${sourceWindow != null}；" +
                            "已按原始 windowId/generation 复核当前页面并执行独立弹层安全检查；" +
                            "platformAccepted=$accepted；仅提交本次补救，不把平台接受视作返回成功；" +
                            "等待新的页面快照确认"
                    )
                }

                val recoveryWindowElapsedMs = if (sayHiDetailBackRecoveryWindowStartedAtUptimeMs > 0L) {
                    (nowUptimeMs - sayHiDetailBackRecoveryWindowStartedAtUptimeMs).coerceAtLeast(0L)
                } else {
                    0L
                }
                if (recoveryWindowElapsedMs >= SAYHI_DETAIL_BACK_RECOVERY_TIMEOUT_MS) {
                    onLog(
                        "收到招呼详情页返回仍未确认：初次等待与额外恢复窗口均已结束；" +
                            "补救 BACK 已尝试=${sayHiDetailBackRecoveryAttempted}；" +
                            "当前 Activity=$currentActivity；列表稳定快照数=" +
                            "${sayHiDetailBackReturnConfirmation.stableSampleCount}；" +
                            "保留页面和采集数据，停止重复 BACK，继续等待页面事件或新鲜快照"
                    )
                    return true
                }
                return true
            }
            SayHiReadOnlyStage.IDLE -> Unit
        }
        // Do not validate the main chat-list identity or enter the intermediate list host while
        // the just-submitted entry tap is navigating. Momo may publish one or more intermediate
        // Activity values before NewSayHiCardActivity arrives. Every transition is bounded by the
        // existing entry verification timeout and checked against a fresh Activity on each pass.
        if (sayHiNavigationFlowController.shouldAwaitProfileCardActivity(
                currentActivityClass = currentActivity,
                profileCardActivityClass = MomoSayHiPageContract.CARD_ACTIVITY,
                entryTapPending = sayHiNavigationFlowController.receivedGreetingsEntryTapPending
            )
        ) {
            val now = System.currentTimeMillis()
            val elapsedMs = (now - sayHiNavigationFlowController.receivedGreetingsEntryTapAtMs).coerceAtLeast(0L)
            if (elapsedMs < RECEIVED_GREETINGS_ENTRY_VERIFY_TIMEOUT_MS) {
                if (sayHiNavigationFlowController.lastReceivedGreetingsNavigationWaitActivityClass != currentActivity) {
                    sayHiNavigationFlowController.lastReceivedGreetingsNavigationWaitActivityClass = currentActivity
                    onLog(
                        "收到的招呼导航：入口点击已提交，等待异步进入 NewSayHiCardActivity；" +
                            "当前过渡 Activity=${currentActivity.ifBlank { "UNKNOWN" }}；" +
                            "elapsed=${elapsedMs}ms；导航等待中，不执行主聊天列表扫描"
                    )
                }
                return true
            }
            sayHiNavigationFlowController.receivedGreetingsEntryTapPending = false
            sayHiNavigationFlowController.lastReceivedGreetingsNavigationWaitActivityClass = ""
            sayHiNavigationFlowController.reset()
            logReceivedGreetingsEntrySkip("PAGE_SWITCH_NOT_CONFIRMED_WITHIN_TIMEOUT")
            onLog(
                "收到的招呼入口点击后未能在限定时间内验证资料卡页面切换；" +
                    "本轮不自动重复点击；elapsed=${elapsedMs}ms"
            )
            return false
        }
        if (currentActivity == MomoSayHiPageContract.CARD_ACTIVITY) {
            if (sayHiNavigationFlowController.receivedGreetingsEntryTapPending) {
                sayHiNavigationFlowController.receivedGreetingsEntryTapPending = false
                sayHiNavigationFlowController.lastReceivedGreetingsNavigationWaitActivityClass = ""
                sayHiNavigationFlowController.lastReceivedGreetingsEntryDiagnostic = ""
                onLog("收到的招呼入口点击后已进入资料卡 Activity；等待卡片与顶部‘列表’控件稳定")
            }
            if (sayHiNavigationFlowController.sayHiCardListTapPending) {
                if (System.currentTimeMillis() - sayHiNavigationFlowController.sayHiCardListTapAtMs < SAYHI_CARD_LIST_VERIFY_TIMEOUT_MS) {
                    return true
                }
                sayHiNavigationFlowController.sayHiCardListTapPending = false
                sayHiNavigationFlowController.markCardListTapFailed()
                onLog("收到的招呼资料卡：点击‘列表’后未在限定时间内确认列表 Activity；重新复核资料卡")
            }
            if (!sayHiNavigationFlowController.isWaitingForCard()) {
                onLog("收到的招呼资料卡：当前不处于等待资料卡阶段；不执行额外点击")
                return true
            }

            // Keep a read-only profile sample, but gate the actual click on multiple spaced
            // snapshots of the exact close_container + child label. The profile card's accept /
            // reject controls are never considered by this navigation policy.
            inspectSayHiPageAndAutonomousBrowse(allowAutonomousScroll = false, minimumIntervalMs = 300L)
            when (val inspection = inspectSayHiCardListTapTarget()) {
                is SayHiCardListTapInspection.Ready -> {
                    val windowKey = sayHiWindowStabilityKey(inspection.tree)
                    val signature = listOf(
                        inspection.target.controlPath,
                        inspection.target.labelPath,
                        inspection.target.controlBounds,
                        inspection.target.tapBounds
                    ).joinToString("|")
                    val stable = sayHiNavigationFlowController.observeStableCardTarget(
                        windowKey = windowKey,
                        targetSignature = signature,
                        capturedAtMs = inspection.tree.capturedAtEpochMs
                    )
                    if (stable) {
                        onLog("收到的招呼资料卡：连续新快照确认 close_container 与‘列表’位置稳定；准备点击")
                        dispatchSayHiCardListTap()
                    } else {
                        onLog("收到的招呼资料卡：已识别可点击 close_container 和子节点‘列表’；等待下一份稳定快照")
                    }
                }
                is SayHiCardListTapInspection.Rejected -> {
                    sayHiNavigationFlowController.resetCardStability()
                    logSayHiCardListTapSkip(inspection.reason)
                }
            }
            return true
        }
        if (isReceivedGreetingsListStructureVisible()) {
            if (sayHiNavigationFlowController.isWaitingForCard()) {
                // Momo first exposes NewSayHiSessionListActivity as the card host, then opens
                // NewSayHiCardActivity on top. This first Activity is not the message list.
                onLog("收到的招呼导航：检测到资料卡宿主过渡 Activity；等待 NewSayHiCardActivity，不开始列表扫描")
                return true
            }
            if (sayHiNavigationFlowController.isWaitingForReceivedList()) {
                val inspected = inspectSayHiPageAndAutonomousBrowse(
                    allowAutonomousScroll = false,
                    minimumIntervalMs = 300L
                )
                if (!inspected) sayHiNavigationFlowController.resetListStability()
                if (!sayHiNavigationFlowController.isReceivedListConfirmed()) {
                    return true
                }
                sayHiNavigationFlowController.sayHiCardListTapPending = false
                sayHiAutonomousBrowseController.reset()
                onLog("收到的招呼资料卡‘列表’导航已验证：窗口与外层可滚动 listview 结构连续稳定；Activity 和操作准入由现有 READY 复核")
                return true
            }
            if (!sayHiNavigationFlowController.isReceivedListConfirmed()) {
                onLog("收到的招呼导航：当前列表 Activity 尚无资料卡‘列表’点击凭证；拒绝把过渡页面当作消息列表")
                return true
            }
            return inspectSayHiPageAndAutonomousBrowse(
                allowAutonomousScroll = true,
                minimumIntervalMs = 300L
            )
        }
        val currentMainPageType = rootAccess.withActiveTargetRoot { root, window ->
            chatComponents.pageInspector.inspectRoot(
                root = AccessibilityNodeInfo.obtain(root),
                targetPackage = targetPackage,
                activityClass = window.activityClass.ifBlank { currentActivity }
            ).pageType
        }
        onLog(
            "收到招呼入口前页面类型：activity=$currentActivity；" +
                "pageType=$currentMainPageType；" +
                "entryPending=${sayHiNavigationFlowController.receivedGreetingsEntryTapPending}"
        )
        // The received-greetings row is itself the navigation surface. Do not make the
        // generic page classifier a prerequisite: a fresh, unique label plus its clickable
        // item_layout is stronger evidence for this entry than a transient CHAT_LIST result.

        val now = System.currentTimeMillis()
        if (now - sayHiNavigationFlowController.lastReceivedGreetingsEntryTapAtMs < RECEIVED_GREETINGS_ENTRY_RETRY_COOLDOWN_MS) {
            logReceivedGreetingsEntrySkip("ENTRY_RETRY_COOLDOWN_ACTIVE")
            return true
        }

        val firstInspection = inspectReceivedGreetingsTapTarget()
        if (firstInspection !is ReceivedGreetingsTapInspection.Ready) {
            val reason = (firstInspection as ReceivedGreetingsTapInspection.Rejected).reason
            logReceivedGreetingsEntrySkip(reason)
            return if (reason.contains("ENTRY_LABEL_COUNT=0")) {
                performReceivedGreetingsEntrySearchStep()
            } else {
                // A visible-but-ambiguous or unsafe label is not permission to scroll past it.
                // It also breaks consecutive edge/retry observation evidence.
                receivedGreetingsEntrySearchController.invalidateObservation()
                true
            }
        }
        resetReceivedGreetingsEntrySearch()

        // This second tree is acquired immediately before dispatch. It must still identify the
        // same window and exactly the same label/clickable-parent geometry; otherwise the list
        // moved, recycled a row, or navigated while the earlier candidate was being evaluated.
        val latestInspection = inspectReceivedGreetingsTapTarget(
            expectedWindow = firstInspection.tree.window,
            expectedWindowId = firstInspection.tree.windowId
        )
        if (latestInspection !is ReceivedGreetingsTapInspection.Ready) {
            val reason = (latestInspection as ReceivedGreetingsTapInspection.Rejected).reason
            logReceivedGreetingsEntrySkip(reason)
            return true
        }
        if (!MomoReceivedGreetingsEntryTapPolicy.hasStableTargetPosition(
                firstTree = firstInspection.tree,
                first = firstInspection.target,
                latestTree = latestInspection.tree,
                latest = latestInspection.target
            )
        ) {
            logReceivedGreetingsEntrySkip("TARGET_POSITION_CHANGED_BETWEEN_FRESH_SNAPSHOTS")
            onLog(
                "收到的招呼入口：点击前两次独立快照的标签/可点击父控件位置不一致；" +
                    "丢弃旧坐标，本轮不点击，等待列表稳定后的新快照"
            )
            return true
        }

        val tree = latestInspection.tree
        val metrics = latestInspection.metrics
        val tapTarget = latestInspection.target
        if (!accessibilityGateway.isWindowCurrent(tree.window)) {
            logReceivedGreetingsEntrySkip("LATEST_SNAPSHOT_BECAME_STALE_BEFORE_GESTURE")
            return true
        }
        onLog(
            "收到的招呼入口[PRE_TAP_REVALIDATED]：windowGeneration=${tree.window.generation}；" +
                "windowId=${tree.windowId}；snapshotAt=${tree.capturedAtEpochMs}；" +
                "display=${metrics.widthPixels}x${metrics.heightPixels}；" +
                "labelBounds=${sayHiNavigationEvidenceInspector.formatBounds(tapTarget.tapBounds)}；" +
                "clickableParentBounds=${sayHiNavigationEvidenceInspector.formatBounds(tapTarget.clickableAncestorBounds)}；" +
                "点击使用第二次独立快照的位置"
        )

        val tapSubmittedAt = System.currentTimeMillis()
        val snapshotAgeMs = (tapSubmittedAt - tree.capturedAtEpochMs).coerceAtLeast(0L)
        if (snapshotAgeMs > RECEIVED_GREETINGS_PRE_TAP_MAX_SNAPSHOT_AGE_MS) {
            logReceivedGreetingsEntrySkip("LATEST_SNAPSHOT_TOO_OLD:${snapshotAgeMs}ms")
            return true
        }
        if (!accessibilityGateway.isWindowCurrent(tree.window)) {
            logReceivedGreetingsEntrySkip("WINDOW_CHANGED_DURING_FINAL_GESTURE_PREPARATION")
            return true
        }
        val executionContext = gesturePolicy.captureContext(
            tree.window,
            metrics.toGestureCoordinateContext()
        ) ?: run {
            logReceivedGreetingsEntrySkip("WINDOW_OR_DISPLAY_CONTEXT_CHANGED_AT_GESTURE_BIND")
            return true
        }
        resetSayHiListSnapshotState()
        resetReceivedGreetingsEntrySearch()
        sayHiNavigationFlowController.beginEntryNavigation()
        sayHiNavigationFlowController.receivedGreetingsEntryTapPending = true
        sayHiNavigationFlowController.lastReceivedGreetingsNavigationWaitActivityClass = ""
        sayHiNonSearchGestureInFlight = true
        sayHiNavigationFlowController.receivedGreetingsEntryTapAtMs = tapSubmittedAt
        sayHiNavigationFlowController.lastReceivedGreetingsEntryTapAtMs = tapSubmittedAt
        sayHiNavigationFlowController.lastReceivedGreetingsEntryDiagnostic = ""
        val tapRequest = GestureIntentRequest(
            id = "momo-sayhi-entry-${tapSubmittedAt}-${System.nanoTime()}",
            intent = GestureIntent.Tap(
                // Tap the session container's safe area, not the non-clickable TextView label.
                preferredBounds = tapTarget.clickableAncestorBounds,
                targetBounds = tapTarget.clickableAncestorBounds,
                durationMs = RECEIVED_GREETINGS_ENTRY_TAP_DURATION_MS,
                feedbackIntent = com.freedom.core.accessibility.api.GestureFeedbackIntent.NAVIGATION_ENTRY
            ),
            description = "陌陌收到的招呼入口安全点击",
            executionContext = executionContext
        )
        val callbackGeneration = sayHiRunGenerationGuard.capture()
        val submission = gesturePolicy.execute(tapRequest, GestureExecutionListener { result ->
            if (!sayHiRunGenerationGuard.isCurrent(callbackGeneration)) return@GestureExecutionListener
            sayHiNonSearchGestureInFlight = false
            val completed = result.status == GestureExecutionStatus.COMPLETED
            onLog(
                if (completed) {
                    "收到的招呼入口：统一手势策略已完成标签安全区域点击；" +
                        "标签路径=${tapTarget.labelPath.joinToString("/")}；" +
                        "点击祖先=${tapTarget.clickableAncestorPath.joinToString("/")}；等待新页面身份验证"
                } else {
                    sayHiNavigationFlowController.receivedGreetingsEntryTapPending = false
                    sayHiNavigationFlowController.reset()
                    "收到的招呼入口：协调器手势未完成；status=${result.status}；reason=${result.reason}；不尝试其他坐标"
                }
            )
        })
        if (submission == GestureSubmission.ACCEPTED) {
            onLog(
                "收到的招呼入口：主聊天列表已唯一识别入口；已在 item_layout 安全区域提交一次点击；" +
                    "不再只点击文字节点，未触碰资料卡拒绝/通过操作"
            )
        } else if (submission == GestureSubmission.DEFERRED_NOT_READY) {
            // No tap was dispatched. Restore the entry phase so the next idle inspection can
            // reacquire the same list entry after the overlay/startup guard clears.
            sayHiNonSearchGestureInFlight = false
            sayHiNavigationFlowController.receivedGreetingsEntryTapPending = false
            sayHiNavigationFlowController.reset()
            onLog("收到的招呼入口[DEFERRED_NOT_READY]：页面暂不可操作，未派发点击、未消耗入口重试；保留导航意图并等待新快照")
        } else {
            sayHiNonSearchGestureInFlight = false
            sayHiNavigationFlowController.receivedGreetingsEntryTapPending = false
            sayHiNavigationFlowController.reset()
            logReceivedGreetingsEntrySkip("GESTURE_SUBMISSION_REJECTED")
            onLog("收到的招呼入口：统一手势协调器未接受精确入口点击；保持待机，不执行坐标兜底")
        }
        return true
    }

    /**
     * Double-snapshot validation and Gesture API dispatch for the card header's exact "列表"
     * control. The actual list page is accepted only after the Activity and list container change.
     */
    private fun dispatchSayHiCardListTap() {
        if (sayHiGestureInFlight || sayHiNavigationFlowController.sayHiCardListTapPending) return
        val now = System.currentTimeMillis()
        if (now - sayHiNavigationFlowController.lastSayHiCardListTapAtMs < SAYHI_CARD_LIST_TAP_COOLDOWN_MS) return

        val first = inspectSayHiCardListTapTarget()
        if (first !is SayHiCardListTapInspection.Ready) {
            val reason = (first as SayHiCardListTapInspection.Rejected).reason
            logSayHiCardListTapSkip(reason)
            return
        }
        val latest = inspectSayHiCardListTapTarget(
            expectedWindow = first.tree.window,
            expectedWindowId = first.tree.windowId
        )
        if (latest !is SayHiCardListTapInspection.Ready) {
            val reason = (latest as SayHiCardListTapInspection.Rejected).reason
            logSayHiCardListTapSkip(reason)
            return
        }
        if (!MomoSayHiCardListTapPolicy.hasStableTargetPosition(
                firstTree = first.tree,
                first = first.target,
                latestTree = latest.tree,
                latest = latest.target
            )
        ) {
            logSayHiCardListTapSkip("TARGET_POSITION_CHANGED_BETWEEN_FRESH_SNAPSHOTS")
            return
        }

        val tree = latest.tree
        val metrics = latest.metrics
        val target = latest.target
        if (!accessibilityGateway.isWindowCurrent(tree.window)) {
            logSayHiCardListTapSkip("WINDOW_STALE_BEFORE_GESTURE")
            return
        }
        val snapshotAgeMs = (System.currentTimeMillis() - tree.capturedAtEpochMs).coerceAtLeast(0L)
        if (snapshotAgeMs > SAYHI_CARD_LIST_PRE_TAP_MAX_SNAPSHOT_AGE_MS) {
            logSayHiCardListTapSkip("SNAPSHOT_TOO_OLD:${snapshotAgeMs}ms")
            return
        }
        val executionContext = gesturePolicy.captureContext(
            tree.window,
            metrics.toGestureCoordinateContext()
        ) ?: run {
            logSayHiCardListTapSkip("WINDOW_OR_DISPLAY_CONTEXT_CHANGED_AT_GESTURE_BIND")
            return
        }

        if (!sayHiNavigationFlowController.markCardListTapSubmitted()) {
            logSayHiCardListTapSkip("CARD_PAGE_NOT_STABLE_FOR_NAVIGATION")
            return
        }
        resetSayHiListSnapshotState()
        val submittedAt = System.currentTimeMillis()
        sayHiNonSearchGestureInFlight = true
        sayHiNavigationFlowController.sayHiCardListTapPending = true
        sayHiNavigationFlowController.sayHiCardListTapAtMs = submittedAt
        val request = GestureIntentRequest(
            id = "momo-sayhi-card-list-${submittedAt}-${System.nanoTime()}",
            intent = GestureIntent.Tap(
                preferredBounds = target.tapBounds,
                targetBounds = target.controlBounds,
                durationMs = RECEIVED_GREETINGS_ENTRY_TAP_DURATION_MS,
                feedbackIntent = com.freedom.core.accessibility.api.GestureFeedbackIntent.NAVIGATION_ENTRY
            ),
            description = "陌陌收到招呼资料卡点击列表入口",
            executionContext = executionContext
        )
        val callbackGeneration = sayHiRunGenerationGuard.capture()
        val submission = gesturePolicy.execute(request, GestureExecutionListener { result ->
            if (!sayHiRunGenerationGuard.isCurrent(callbackGeneration)) return@GestureExecutionListener
            sayHiNonSearchGestureInFlight = false
            if (result.status == GestureExecutionStatus.COMPLETED) {
                sayHiNavigationFlowController.sayHiCardListTapPending = true
                sayHiNavigationFlowController.sayHiCardListTapAtMs = submittedAt
                sayHiNavigationFlowController.lastSayHiCardListTapAtMs = submittedAt
                onLog("收到的招呼资料卡：已通过统一手势执行精确‘列表’控件点击；等待 NewSayHiSessionListActivity 验证")
            } else {
                sayHiNavigationFlowController.sayHiCardListTapPending = false
                sayHiNavigationFlowController.markCardListTapFailed()
                onLog("收到的招呼资料卡：‘列表’控件手势未完成；status=${result.status}；reason=${result.reason}")
            }
        })
        if (submission == GestureSubmission.DEFERRED_NOT_READY) {
            sayHiNonSearchGestureInFlight = false
            sayHiNavigationFlowController.sayHiCardListTapPending = false
            sayHiNavigationFlowController.markCardListTapFailed()
            onLog("收到的招呼资料卡列表入口[DEFERRED_NOT_READY]：没有派发点击；清除旧稳定样本，重新采集资料卡快照后再尝试")
        } else if (submission != GestureSubmission.ACCEPTED) {
            sayHiNonSearchGestureInFlight = false
            sayHiNavigationFlowController.sayHiCardListTapPending = false
            sayHiNavigationFlowController.markCardListTapFailed()
            logSayHiCardListTapSkip("GESTURE_SUBMISSION_REJECTED:$submission")
        } else {
            // The pending flag was set before dispatch, preventing duplicate dispatches while
            // the Gesture API is still executing. The callback closes it on cancellation.
            onLog(
                "收到的招呼资料卡[PRE_TAP_REVALIDATED]：已唯一确认 close_container 与子节点‘列表’；" +
                    "labelBounds=${sayHiNavigationEvidenceInspector.formatBounds(target.tapBounds)}；controlBounds=${sayHiNavigationEvidenceInspector.formatBounds(target.controlBounds)}"
            )
        }
    }

    private fun inspectSayHiCardListTapTarget(
        expectedWindow: WindowRef? = null,
        expectedWindowId: Int? = null
    ): SayHiCardListTapInspection {
        val tree = accessibilityGateway.inspectActiveWindow(
            TreeInspectionRequest(
                maxNodes = NAVIGATION_EVIDENCE_MAX_NODES,
                textCapture = NodeTextCapture.EPHEMERAL
            )
        ) ?: return SayHiCardListTapInspection.Rejected("ACTIVE_WINDOW_TREE_UNAVAILABLE")
        if (tree.truncated) return SayHiCardListTapInspection.Rejected("ACCESSIBILITY_TREE_TRUNCATED")
        if (tree.window.packageName != targetPackage ||
            tree.window.activityClass != MomoSayHiPageContract.CARD_ACTIVITY
        ) return SayHiCardListTapInspection.Rejected("PACKAGE_OR_ACTIVITY_MISMATCH")
        if (expectedWindow != null && tree.window != expectedWindow) {
            return SayHiCardListTapInspection.Rejected("WINDOW_CHANGED_BETWEEN_SNAPSHOTS")
        }
        if (expectedWindowId != null && tree.windowId != expectedWindowId) {
            return SayHiCardListTapInspection.Rejected("WINDOW_ID_CHANGED_BETWEEN_SNAPSHOTS")
        }
        if (!accessibilityGateway.isWindowCurrent(tree.window)) {
            return SayHiCardListTapInspection.Rejected("WINDOW_NOT_CURRENT")
        }
        val metrics = runCatching { accessibilityGateway.displayMetrics() }.getOrNull()
            ?: return SayHiCardListTapInspection.Rejected("DISPLAY_METRICS_UNAVAILABLE")
        val decision = MomoSayHiCardListTapPolicy.evaluate(
            tree = tree,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )
        return when (decision) {
            is MomoSayHiCardListTapDecision.Ready -> SayHiCardListTapInspection.Ready(tree, metrics, decision)
            is MomoSayHiCardListTapDecision.Rejected -> SayHiCardListTapInspection.Rejected(decision.reason)
        }
    }

    private fun logSayHiCardListTapSkip(reason: String) {
        onLog("收到的招呼资料卡列表入口：未点击；原因=$reason")
    }

    /**
     * Searches the complete ordinary Message-list surface for the received-greetings entry.
     * A miss in the current viewport is not terminal: swipe toward later rows, confirm that
     * direction's boundary with two successful no-motion observations, then sweep back once.
     * Every gesture is tied to a newly inspected CHAT_LIST snapshot and a freshly resolved safe
     * viewport that excludes the mf_view overlay.
     */
    private fun performReceivedGreetingsEntrySearchStep(): Boolean {
        if (receivedGreetingsEntrySearchController.isComplete()) return false
        if (sayHiGestureInFlight) return true

        val fresh = runCatching { inspectChatPage() }.getOrNull() ?: run {
            receivedGreetingsEntrySearchController.invalidateObservation()
            onLog("收到的招呼入口搜索暂停：无法重新读取普通消息列表；不使用旧视口坐标")
            return false
        }
        if (fresh.packageName != targetPackage) {
            receivedGreetingsEntrySearchController.invalidateObservation()
            onLog(
                "收到的招呼入口搜索暂停：当前前台包已离开目标应用；" +
                    "packageMatch=false；不跨应用派发滑动"
            )
            return false
        }
        if (fresh.pageKind != ChatPageKind.CHAT_LIST) {
            // Accessibility snapshots can briefly classify a transitioning list as UNKNOWN.
            // Keep the task alive and let the next page event retry; never reuse old coordinates.
            if (fresh.pageKind == ChatPageKind.UNKNOWN) {
                receivedGreetingsEntrySearchController.invalidateObservation()
                onLog("收到的招呼入口搜索暂缓：列表快照暂时为 UNKNOWN；等待新快照后重新确认，不终止导航")
                return false
            }
            resetReceivedGreetingsEntrySearch()
            onLog(
                "收到的招呼入口搜索暂停当前滑动：页面已切换为 ${fresh.pageKind.name}；" +
                    "不在非消息列表页面执行搜索手势，保留任务等待页面级导航"
            )
            return true
        }
        val expectedWindow = fresh.messageListTarget?.window
            ?: fresh.chatListCandidates.firstOrNull()?.rowTarget?.window
            ?: fresh.sessionListNavigationTarget?.nodeTarget?.window
            ?: run {
                receivedGreetingsEntrySearchController.invalidateObservation()
                onLog("收到的招呼入口搜索暂缓：新鲜列表快照尚未提供可绑定的窗口身份；等待下一次快照，不终止导航")
                return false
            }
        if (!accessibilityGateway.isWindowCurrent(expectedWindow) ||
            expectedWindow.packageName != targetPackage || expectedWindow.windowId < 0 ||
            fresh.windowGeneration > 0L && expectedWindow.generation != fresh.windowGeneration
        ) {
            receivedGreetingsEntrySearchController.invalidateObservation()
            onLog("收到的招呼入口搜索暂缓：当前窗口/代次与最新消息列表快照暂不一致；不派发旧坐标滑动，等待重取")
            return false
        }
        val windowIdentity = "${expectedWindow.packageName}|${expectedWindow.windowId}|${expectedWindow.generation}"
        // inspectChatPage() already revalidated the shared run-local memory using this fresh
        // ordinary-inbox observation. A task handoff resets only search progress, not the hint.
        // Never infer an action from the handoff's previous snapshot or saved tap coordinates.
        val entryPosition = receivedGreetingsEntryPositionMemory.position
        if (entryPosition == MomoReceivedGreetingsEntryPosition.VISIBLE ||
            !receivedGreetingsEntrySearchController.isActive() || receivedGreetingsEntrySearchController.receivedGreetingsEntrySearchWindowIdentity != windowIdentity
        ) {
            if (!receivedGreetingsEntrySearchController.start(entryPosition)) {
                receivedGreetingsEntrySearchController.receivedGreetingsEntrySearchWindowIdentity = ""
                onLog("收到的招呼入口搜索[VISIBLE_RECHECK]：最新列表观察已见入口；不滑过入口，等待现有双快照安全点击复核")
                return true
            }
            receivedGreetingsEntrySearchController.receivedGreetingsEntrySearchWindowIdentity = windowIdentity
            val initialDirection = receivedGreetingsEntrySearchController.direction()
            onLog(
                "收到的招呼入口搜索开始：已确认普通消息列表窗口=$windowIdentity；" +
                    "position=${entryPosition.name}；initialSwipe=${initialDirection.name}；" +
                    "initialListDirection=${if (initialDirection == VerticalSwipeDirection.DOWN) "TOWARD_START" else "TOWARD_END"}；" +
                    "当前方向到达边界仍未发现时，再反向复核全列表"
            )
        }

        val searchGeneration = receivedGreetingsEntrySearchController.captureSearchGeneration()
        val viewportFingerprint = receivedGreetingsEntrySearchViewportFingerprint(fresh)
        if (viewportFingerprint.isBlank()) {
            receivedGreetingsEntrySearchController.invalidateObservation()
            onLog("收到的招呼入口搜索[OBSERVATION_HOLD]：列表证据不完整；不派发滑动、不确认边界")
            return false
        }
        // Same live READY owner as the dedicated greetings list. Lease admission remains
        // in the existing task executor and unified gesture pipeline; this is read-only.
        val readiness = runCatching(inspectReceivedGreetingsReadiness).getOrNull()
        if (readiness?.isReadyForWindow(expectedWindow, RunMode.AI_REPLY) != true ||
            !accessibilityGateway.isWindowCurrent(expectedWindow)
        ) {
            receivedGreetingsEntrySearchController.invalidateObservation()
            onLog("收到的招呼入口搜索[OBSERVATION_HOLD]：当前普通消息列表窗口尚未 READY；保留方向，仅重新观察")
            return false
        }
        val boundaryEvidence = ChatSessionManagementPolicy.greetingSearchBoundaryEvidence(
            fresh, toTop = receivedGreetingsEntrySearchController.direction() == VerticalSwipeDirection.DOWN
        )
        val nowMs = SystemClock.elapsedRealtime()
        when (receivedGreetingsEntrySearchController.observeAfterGesture(viewportFingerprint, boundaryEvidence, nowMs)) {
            MomoReceivedGreetingsEntrySearchController.Observation.CONTINUE -> Unit
            MomoReceivedGreetingsEntrySearchController.Observation.WAIT_FOR_OBSERVATION -> {
                onLog("收到的招呼入口搜索[OBSERVATION_HOLD]：等待独立边界证据或无位移后的只读恢复；不派发滑动")
                return false
            }
            MomoReceivedGreetingsEntrySearchController.Observation.REVERSED -> {
                onLog("收到的招呼入口搜索[PHYSICAL_EDGE_CONFIRMED]：连续新鲜快照确认实际列表边界；反转方向，复核剩余列表区域")
            }
            MomoReceivedGreetingsEntrySearchController.Observation.COMPLETE -> {
                onLog(
                    "收到的招呼入口搜索完成：已从当前起点双向扫描普通消息列表并复核两端边界；" +
                        "仍未发现唯一‘收到的招呼’标签；不盲点其它行，也不把未命中伪装成进入成功"
                )
                return false
            }
        }

        val viewport = resolveSessionListSwipeViewport(fresh)
        if (viewport == null || viewport.isEmpty || viewport.width < MIN_SAFE_SESSION_SWIPE_WIDTH_PX ||
            viewport.height < MIN_SAFE_SESSION_SWIPE_HEIGHT_PX
        ) {
            onLog("收到的招呼入口搜索暂缓：当前快照/悬浮控件状态下没有足够大的安全列表滑动区域；等待重取")
            return true
        }
        val metrics = runCatching { accessibilityGateway.displayMetrics() }.getOrNull() ?: run {
            onLog("收到的招呼入口搜索暂缓：无法读取当前显示坐标上下文；等待下一次安全复核")
            return true
        }
        val executionContext = gesturePolicy.captureContext(
            expectedWindow,
            metrics.toGestureCoordinateContext()
        ) ?: run {
            onLog("收到的招呼入口搜索暂缓：窗口或显示坐标上下文已变化；未派发滑动")
            return true
        }
        if (!receivedGreetingsEntrySearchController.mayDispatch(
                "$windowIdentity|$viewportFingerprint|$viewport", SystemClock.elapsedRealtime()
            )
        ) {
            onLog("收到的招呼入口搜索[RETRY_SUPPRESSED]：attempt=${receivedGreetingsEntrySearchController.retryAttempt()}；等待退避及两次新鲜 READY 视口复核")
            return false
        }
        val direction = receivedGreetingsEntrySearchController.direction()
        val submittedAt = System.currentTimeMillis()
        val request = GestureIntentRequest(
            id = "momo-sayhi-entry-search-${submittedAt}-${System.nanoTime()}",
            intent = GestureIntent.VerticalSwipe(
                viewport = viewport,
                direction = direction,
                durationMs = RECEIVED_GREETINGS_ENTRY_SEARCH_SCROLL_DURATION_MS
            ),
            description = if (direction == VerticalSwipeDirection.UP) {
                "收到的招呼入口搜索：沿普通消息列表向后续区域安全滚动"
            } else {
                "收到的招呼入口搜索：沿普通消息列表向前序区域安全滚动"
            },
            executionContext = executionContext
        )
        val callbackGeneration = sayHiRunGenerationGuard.capture()
        if (sayHiGestureInFlight ||
            !receivedGreetingsEntrySearchController.tryBeginGesture(searchGeneration, request.id)
        ) return true
        val submission = runCatching {
            gesturePolicy.execute(request, GestureExecutionListener { result ->
                if (!sayHiRunGenerationGuard.isCurrent(callbackGeneration)) return@GestureExecutionListener
                val completed = result.status == GestureExecutionStatus.COMPLETED
                if (!receivedGreetingsEntrySearchController.finishGesture(
                        request.id, if (completed) viewportFingerprint else null, SystemClock.elapsedRealtime()
                    )
                ) return@GestureExecutionListener
                if (completed) {
                    onLog(
                        "收到的招呼入口搜索滑动完成：方向=${direction.name}；" +
                            "等待新的会话行身份/位置快照后继续搜索"
                    )
                } else {
                    onLog(
                        "收到的招呼入口搜索滑动未完成：status=${result.status}；reason=${result.reason}；" +
                            "保留方向状态；attempt=${receivedGreetingsEntrySearchController.retryAttempt()}；进入只读退避，重新确认 READY 后才允许单次探测"
                    )
                }
            })
        }.getOrElse {
            receivedGreetingsEntrySearchController.finishGesture(request.id, null, SystemClock.elapsedRealtime())
            onLog("收到的招呼入口搜索暂停：手势协调器异常=${it.javaClass.simpleName}；未使用坐标兜底")
            return false
        }
        return when (submission) {
            GestureSubmission.ACCEPTED -> true
            GestureSubmission.DEFERRED_NOT_READY -> {
                receivedGreetingsEntrySearchController.finishGesture(request.id, null, SystemClock.elapsedRealtime())
                onLog("收到的招呼入口搜索暂缓：手势协调器尚未就绪；保留搜索方向，进入现有只读恢复")
                false
            }
            else -> {
                receivedGreetingsEntrySearchController.finishGesture(request.id, null, SystemClock.elapsedRealtime())
                onLog("收到的招呼入口搜索暂停：手势协调器拒绝安全滑动；submission=$submission")
                false
            }
        }
    }

    private fun resetReceivedGreetingsEntrySearch(resetRun: Boolean = false) {
        if (resetRun) receivedGreetingsEntrySearchController.resetForRun()
        else receivedGreetingsEntrySearchController.reset()
        receivedGreetingsEntrySearchController.receivedGreetingsEntrySearchWindowIdentity = ""
    }

    private fun inspectReceivedGreetingsTapTarget(
        expectedWindow: WindowRef? = null,
        expectedWindowId: Int? = null
    ): ReceivedGreetingsTapInspection {
        val tree = accessibilityGateway.inspectActiveWindow(
            TreeInspectionRequest(
                maxNodes = NAVIGATION_EVIDENCE_MAX_NODES,
                textCapture = NodeTextCapture.EPHEMERAL
            )
        ) ?: return ReceivedGreetingsTapInspection.Rejected("PRE_TAP_ACTIVE_WINDOW_TREE_UNAVAILABLE")
        if (tree.window.packageName != targetPackage) {
            return ReceivedGreetingsTapInspection.Rejected("PRE_TAP_PACKAGE_MISMATCH")
        }
        if (tree.window.windowId < 0 || tree.windowId != tree.window.windowId) {
            return ReceivedGreetingsTapInspection.Rejected("PRE_TAP_WINDOW_ID_UNAVAILABLE_OR_MISMATCHED")
        }
        if (tree.truncated) {
            return ReceivedGreetingsTapInspection.Rejected("PRE_TAP_TREE_TRUNCATED")
        }
        if (expectedWindow != null && tree.window != expectedWindow) {
            return ReceivedGreetingsTapInspection.Rejected("PRE_TAP_WINDOW_GENERATION_CHANGED")
        }
        if (expectedWindowId != null && tree.windowId != expectedWindowId) {
            return ReceivedGreetingsTapInspection.Rejected("PRE_TAP_WINDOW_ID_CHANGED")
        }
        if (!accessibilityGateway.isWindowCurrent(tree.window)) {
            return ReceivedGreetingsTapInspection.Rejected("PRE_TAP_WINDOW_NOT_CURRENT")
        }
        if (!MomoMessageTabAnchorResolver.isMessageWorkArea(tree.nodes, targetPackage)) {
            return ReceivedGreetingsTapInspection.Rejected("PRE_TAP_MESSAGE_WORK_AREA_FINGERPRINT_MISMATCH")
        }
        val metrics = runCatching { accessibilityGateway.displayMetrics() }.getOrNull()
            ?: return ReceivedGreetingsTapInspection.Rejected("PRE_TAP_DISPLAY_METRICS_UNAVAILABLE")
        val evidence = sayHiNavigationEvidenceInspector.inspect(
            tree = tree,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )
        recordMomoNavigationEvidence(tree)
        val decision = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            tree = tree,
            evidence = evidence,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )
        if (decision is MomoReceivedGreetingsEntryTapDecision.Rejected) {
            return ReceivedGreetingsTapInspection.Rejected(
                "PRE_TAP_TARGET_REJECTED:${decision.reason};入口候选=${evidence.receivedGreetingsEntryTargets.size}"
            )
        }
        return ReceivedGreetingsTapInspection.Ready(
            tree = tree,
            metrics = metrics,
            target = decision as MomoReceivedGreetingsEntryTapDecision.Ready
        )
    }

    private fun resetSayHiListSnapshotState() {
        sayHiSnapshotCoordinator.reset()
        sayHiConfirmedListTarget = null
        lastSayHiSnapshotGateDiagnostic = ""
        lastSayHiSnapshotGateDiagnosticAtMs = 0L
        sayHiGenericChatInspectionBypassActive = false
    }

    private fun scheduleSayHiListSnapshotInspection(
        delayMs: Long,
        reason: String,
        retry: Boolean
    ) = sayHiSnapshotCoordinator.scheduleInspection(delayMs, reason, retry)

    private fun handleIncompleteSayHiListSnapshot(
        windowKey: String,
        stopReason: String,
        visitedNodes: Int,
        targetedSubtree: Boolean
    ) = sayHiSnapshotCoordinator.handleIncompleteSnapshot(
        windowKey,
        stopReason,
        visitedNodes,
        targetedSubtree
    )

    private fun logReceivedGreetingsEntrySkip(reason: String) {
        val now = System.currentTimeMillis()
        if (reason != sayHiNavigationFlowController.lastReceivedGreetingsEntryDiagnostic ||
            now - sayHiNavigationFlowController.lastReceivedGreetingsEntryDiagnosticAtMs >= RECEIVED_GREETINGS_ENTRY_DIAGNOSTIC_INTERVAL_MS
        ) {
            sayHiNavigationFlowController.lastReceivedGreetingsEntryDiagnostic = reason
            sayHiNavigationFlowController.lastReceivedGreetingsEntryDiagnosticAtMs = now
            onLog("收到的招呼入口：本轮未点击；原因=$reason")
        }
    }

    /**
     * Reacquires the received-greetings list from any currently exposed Momo window. The first
     * same-package window is not necessarily the list: tooltips and transient overlays can own
     * separate roots. Prefer a complete tree that actually contains the outer listview.
     */
    private fun inspectVisibleReceivedGreetingsWindow(
        request: TreeInspectionRequest
    ): AccessibilityTreeSnapshot? {
        return sayHiAccessibilityInspector.selectVisibleReceivedGreetingsWindow(
            accessibilityGateway.inspectTargetWindows(request), targetPackage
        )
    }

    private fun processReceivedGreetingRows(
        tree: AccessibilityTreeSnapshot,
        inspection: MomoSayHiPageInspection
    ): Boolean {
        if (hasSayHiVerifiedReturnReceipts()) return true
        if (inspection.truncated || inspection.queryBudgetExceeded || tree.truncated ||
            !inspection.containerFound || !inspection.containerMatchCountExact || inspection.containerAmbiguous
        ) return true

        if (sayHiReadOnlyStage == SayHiReadOnlyStage.DETAIL_BACK_PENDING) {
            // A visible list is not yet a verified return: preserve selected source and
            // confirmed-send proof until the original two-snapshot return gate finishes.
            return true
        }

        if (sayHiReadOnlyStage == SayHiReadOnlyStage.PROFILE_BACK_PENDING) {
            val selected = sayHiSelectedGreeting ?: run {
                // Missing selection state is not permission to choose a replacement row.
                onLog("收到招呼身份复核暂停：资料页返回状态缺少原始行身份；不重置流程、不选择其他行")
                return true
            }
            // Page identity must be re-established on the post-BACK snapshot before the
            // retained business identity is rebound to its new window generation.
            if (inspection.kind != MomoSayHiPageKind.RECEIVED_GREETINGS_LIST ||
                !sayHiNavigationFlowController.isReceivedListConfirmed() ||
                tree.window.packageName != targetPackage ||
                !accessibilityGateway.isWindowCurrent(tree.window)
            ) {
                onLog("收到招呼身份复核暂停：返回后的列表页面或当前窗口尚未重新确认；不绑定新代次、不派发点击")
                scheduleSayHiListSnapshotInspection(
                    delayMs = 350L,
                    reason = "PROFILE_RETURN_PAGE_REVALIDATION_REQUIRED",
                    retry = false
                )
                return true
            }
            // The content-derived row key intentionally excludes RecyclerView path/position.
            // If the exact original row is temporarily absent or ambiguous after BACK, keep the
            // selected identity and block list scanning; never fall through to another candidate.
            val matches = inspection.listRows.filter { MomoSayHiIdentityFingerprintPolicy.rowKey(it) == selected.rowKey }
            if (matches.size != 1) {
                sayHiProfileRowRecoveryAttempts++
                if (sayHiProfileRowRecoveryAttempts <= SAYHI_PROFILE_ROW_RECOVERY_MAX_ATTEMPTS) {
                    onLog(
                        "收到招呼身份复核：返回列表未唯一定位原始行；同一行候选=${matches.size}；" +
                            "按稳定身份与行内容重新获取快照=${sayHiProfileRowRecoveryAttempts}/" +
                            "$SAYHI_PROFILE_ROW_RECOVERY_MAX_ATTEMPTS；禁止选择其他行"
                    )
                    scheduleSayHiListSnapshotInspection(
                        delayMs = 350L,
                        reason = "PROFILE_RETURN_ORIGINAL_ROW_REIDENTIFICATION",
                        retry = false
                    )
                } else {
                    onLog(
                        "收到招呼身份复核暂停：多次快照仍无法唯一定位原始行；保留已验证身份和原始行键，" +
                            "不滚动、不点击其他用户，等待页面重新稳定"
                    )
                }
                return true
            }
            sayHiProfileRowRecoveryAttempts = 0
            val row = matches.single()
            val resolvedContactId = sayHiSelectedContactIdentity
                ?.takeIf {
                    sayHiSelectedIdentityStatus == MomoSayHiPlatformIdentityStatus.CONFIRMED &&
                        it.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED
                }
                ?.contactId
                ?.takeIf(String::isNotBlank)
            if (resolvedContactId == null) {
                deferSayHiRow(selected.rowKey, "ITEM_NOT_READY")
                onLog("收到招呼身份复核暂停：原始行已找到，但资料页 ID 未达到平台身份验证标准；不点击行容器或进入会话")
                clearSayHiSelectedGreeting()
                return false
            }
            val contactHash = MomoSayHiIdentityFingerprintPolicy.contactIdHash(resolvedContactId)
            val priorSendRecords = runCatching { receivedGreetingsSendReturnRepository.loadAll() }.getOrElse {
                onLog("GREETING_JOURNAL_UNAVAILABLE：资料页已验证，但去重凭证不可读；禁止进入聊天")
                return true
            }
            val priorSend = priorSendRecords.firstOrNull { it.contactIdHash == contactHash }
            if (sayHiTraversalState.isProcessedContactHash(contactHash) || priorSend != null) {
                if (priorSend == null || priorSend.phase == ReceivedGreetingsSendReturnPhase.RETURN_VERIFIED) {
                    sayHiTraversalState.markProcessedRow(selected.rowKey)
                    clearSayHiRowDeferral(selected.rowKey)
                    persistSayHiTraversalCheckpoint()
                } else {
                    deferSayHiRow(selected.rowKey, "CONFIRMED_SEND_AWAITING_RETURN")
                }
                onLog("收到招呼任务：已验证联系人具有已确认发送记录；跳过重复会话，不重新输入或发送")
                clearSayHiSelectedGreeting()
                return false
            }
            // A new conversation needs final admission only after the existing return,
            // platform identity and durable send/duplicate recovery gates have succeeded.
            if (!sayHiTraversalState.isDeferredRow(selected.rowKey)) {
                val settings = readSayHiFilterSettings() ?: return true
                val evidence = (selected.profileFilterEvidence ?: ConversationFilterEvidence())
                    .supplementedBy(row.filterEvidence)
                val decision = ConversationFilterPolicy.evaluate(settings, evidence)
                if (decision.result != ConversationFilterResult.ALLOW) {
                    if (sayHiTraversalState.markFilterSkipped(selected.rowKey, row.filterEvidence, decision)) {
                        logSayHiFilterDecision(selected.rowKey, decision, settings, evidence, "PROFILE_RETURN")
                    }
                    clearSayHiSelectedGreeting()
                    // Let the normal next idle step select another visible candidate before
                    // the outer list is allowed to scroll. No extra collection is scheduled.
                    return true
                }
                if (settings.hasEnabledConditions) {
                    logSayHiFilterDecision(selected.rowKey, decision, settings, evidence, "PROFILE_RETURN")
                }
            }
            val namePath = row.namePath ?: run {
                deferSayHiRow(selected.rowKey, "ITEM_NOT_READY")
                onLog("收到招呼详情点击跳过：重新定位的原始消息行缺少姓名节点路径；不使用旧节点路径或坐标")
                clearSayHiSelectedGreeting()
                return false
            }
            // Rebuild the clickable row target from this post-BACK snapshot. The inspector's
            // rowPath/namePath are current-snapshot evidence; no pre-profile NodePath or bounds
            // are carried across the navigation boundary.
            val target = MomoSayHiAvatarTargetPolicy.resolveRowTarget(
                index = NodeTreeIndex(tree),
                rowPath = NodePath(row.rowPath),
                namePath = namePath
            ) ?: run {
                deferSayHiRow(selected.rowKey, "ITEM_NOT_READY")
                onLog("收到招呼详情点击跳过：当前快照无法重新构建原始消息行的安全点击目标；不使用旧目标或整行坐标兜底")
                clearSayHiSelectedGreeting()
                return false
            }
            // Only after page, unique row identity, confirmed platform identity and a freshly
            // reconstructed target are all proven do we bind this selection to the current
            // list window generation and structure. The business identity itself is retained.
            val reboundSelection = selected.copy(
                sourceWindow = tree.window,
                sourceStructureFingerprint = MomoSayHiIdentityFingerprintPolicy.treeStructureFingerprint(tree)
            )
            sayHiSelectedGreeting = reboundSelection
            onLog("收到招呼身份复核：返回列表页面、原始行、平台身份和新建安全目标均已确认；绑定当前窗口代次后执行二次校验")
            return dispatchSayHiReadOnlyRowTap(tree, row, target)
        }

        if (sayHiReadOnlyStage != SayHiReadOnlyStage.IDLE) return true

        // Durable sent proof outlives the expiring traversal checkpoint. An unread row
        // with a confirmed send is not eligible for another draft, even after restart.
        val sendRecords = runCatching { receivedGreetingsSendReturnRepository.loadAll() }.getOrElse {
            onLog("GREETING_JOURNAL_UNAVAILABLE：发送去重凭证不可读取；停止招呼遍历以避免重复发送")
            return true
        }
        val pendingReturns = sendRecords.filter { it.phase == ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED }
        if (pendingReturns.isNotEmpty()) {
            if (!isReceivedGreetingsListConfirmed() || !accessibilityGateway.isWindowCurrent(tree.window) ||
                !sayHiRestoredReturnConfirmation.observe(receivedGreetingsListWindowIdentity(), SystemClock.uptimeMillis())
            ) {
                scheduleSayHiListSnapshotInspection(
                    delayMs = 650L, reason = "GREETING_RESTORED_SEND_PENDING_LIST_RETURN", retry = false
                )
                return true
            }
            // Fresh, current, consecutively stable list evidence repairs a RETURN_PENDING
            // checkpoint from an interrupted process without submitting a second BACK.
            for (record in pendingReturns) {
                if (!runCatching {
                        sayHiSendReturnJournal.confirmVerifiedReturn(record.rowKeyHash, record.contactIdHash)
                    }.getOrDefault(false)
                ) {
                    onLog("GREETING_RESTORED_RETURN_BLOCKED reason=RETURN_FACT_PERSIST_FAILED")
                    return true
                }
                sayHiTraversalState.markProcessedRow(record.rowKeyHash)
                sayHiTraversalState.markProcessedContactHash(record.contactIdHash)
                clearSayHiRowDeferral(record.rowKeyHash)
                recordSayHiVerifiedReturnReceipt(record.sendConfirmationHash)
            }
            sayHiRestoredReturnConfirmation.reset()
            onLog("GREETING_RESTORED_RETURN_VERIFIED：已发送联系人经列表双快照完成返回；未重复发送、已补写游标")
            return true
        }
        val confirmedRows = sendRecords.mapTo(HashSet<String>()) { it.rowKeyHash }
        val candidates = inspection.listRows.filter { row ->
            row.unread.status == EvidenceStatus.CONFIRMED && row.unread.value == true
        }
        val settings = readSayHiFilterSettings()
        val unprocessedCandidates = candidates.filter { row ->
            val key = MomoSayHiIdentityFingerprintPolicy.rowKey(row)
            key !in confirmedRows && !sayHiTraversalState.isProcessedRow(key) && !sayHiTraversalState.isDeferredRow(key) &&
                settings != null && (!settings.hasEnabledConditions ||
                    // Keep the existing duplicate-row safety path authoritative. An ambiguous row
                    // key must not be used to cache a filter exclusion for another visible row.
                    (inspection.listRows.count { MomoSayHiIdentityFingerprintPolicy.rowKey(it) == key } != 1 ||
                        passesSayHiListFilter(key, settings, row.filterEvidence)))
        }
        // Read-only recovery probe: the old finite click budget is never reset just
        // because a timer fired. Inspect a NEW unique and independently safe row target.
        // The traversal ledger requires TWO fresh, equal changed-evidence snapshots.
        val exhaustedProbePending = inspectExhaustedSayHiSafeTargetEvidence(
            tree, inspection, candidates, confirmedRows
        )
        val deferredRetryCandidates = candidates.filter { row ->
            val key = MomoSayHiIdentityFingerprintPolicy.rowKey(row)
            key !in confirmedRows && sayHiTraversalState.isDeferredRetryDue(key, System.currentTimeMillis())
        }
        // New users are isolated from one user's transient failures. Deferred users get a
        // bounded retry opportunity only after currently visible fresh candidates are handled.
        // Retain the pending row identity across a pre-dispatch deferral, but never its
        // coordinates. If it is still eligible and visible, prefer it on the fresh snapshot.
        val candidate = (unprocessedCandidates.firstOrNull {
            MomoSayHiIdentityFingerprintPolicy.rowKey(it) == sayHiStableCandidateKey
        } ?: deferredRetryCandidates.firstOrNull {
            MomoSayHiIdentityFingerprintPolicy.rowKey(it) == sayHiStableCandidateKey
        } ?: unprocessedCandidates.firstOrNull() ?: deferredRetryCandidates.firstOrNull()) ?: run {
            sayHiStableCandidateKey = ""
            sayHiStableCandidateTarget = null
            sayHiStableCandidateWindow = null
            // Do not scroll away while the second safe-evidence observation is pending.
            // A configuration read failure is not evidence that the scan is complete.
            return exhaustedProbePending || settings == null
        }
        sayHiTraversalState.sayHiDeferredSafeEvidenceOnlyWait = false
        val key = MomoSayHiIdentityFingerprintPolicy.rowKey(candidate)
        if (inspection.listRows.count { MomoSayHiIdentityFingerprintPolicy.rowKey(it) == key } != 1) {
            deferSayHiRow(key, "ROW_NOT_SAFELY_ACTIONABLE")
            onLog("收到招呼头像点击跳过：当前可见列表存在无法区分的重复消息行；不猜测目标用户")
            return false
        }
        val target = candidate.avatarTapTarget ?: run {
            deferSayHiRow(key, "ROW_NOT_SAFELY_ACTIONABLE")
            onLog("收到招呼头像点击跳过：未找到唯一、可见且尺寸受限的头像节点（userlist_item_iv_face/img_avatar/icon）；不点击整行")
            return false
        }
        val priorTarget = sayHiStableCandidateTarget
        val stable = sayHiStableCandidateKey == key &&
            sayHiStableCandidateWindow == tree.window && priorTarget != null &&
            MomoSayHiAvatarTargetPolicy.sameTarget(priorTarget, target)
        if (!stable) {
            sayHiStableCandidateKey = key
            sayHiStableCandidateTarget = target
            sayHiStableCandidateWindow = tree.window
            onLog("收到招呼未读消息：已定位头像资源节点并记录候选；等待第二份稳定快照后再点击")
            return true
        }
        val selected = SayHiSelectedGreeting(
            rowKey = key,
            displayName = candidate.displayName.value.orEmpty(),
            greetingText = candidate.greetingText.value,
            timestampText = candidate.timestampText.value,
            sourceWindow = tree.window,
            sourceStructureFingerprint = MomoSayHiIdentityFingerprintPolicy.treeStructureFingerprint(tree),
            listFilterEvidence = candidate.filterEvidence
        )
        return dispatchSayHiReadOnlyAvatarTap(tree, selected, target)
    }

    private fun readSayHiFilterSettings(): ConversationFilterSettings? {
        val settings = runCatching { conversationFilterSettings() }.getOrNull()
        if (settings == null) {
            if (!sayHiTraversalState.sayHiFilterSettingsUnavailableLogged) {
                sayHiTraversalState.sayHiFilterSettingsUnavailableLogged = true
                onLog("CONVERSATION_FILTER_SETTINGS_UNAVAILABLE nextAction=WAIT_FOR_SETTINGS")
            }
            return null
        }
        sayHiTraversalState.sayHiFilterSettingsUnavailableLogged = false
        sayHiTraversalState.updateFilterSettings(settings)
        return settings
    }

    private fun passesSayHiListFilter(
        rowKey: String, settings: ConversationFilterSettings, evidence: ConversationFilterEvidence
    ): Boolean {
        if (sayHiTraversalState.isDeferredRow(rowKey) || !settings.hasEnabledConditions) return true
        if (sayHiTraversalState.isFilterSkipped(rowKey, evidence)) return false
        val decision = ConversationFilterPolicy.evaluate(settings, evidence)
        // Missing list evidence is not a rejection: the original profile visit can fill it.
        // Log changed decisions for all candidates, including those not selected before a yield.
        if (sayHiTraversalState.recordListFilterDecision(rowKey, evidence, decision)) {
            logSayHiFilterDecision(rowKey, decision, settings, evidence, "LIST")
        }
        return decision.result != ConversationFilterResult.EXCLUDE
    }

    private fun logSayHiFilterDecision(
        rowKey: String,
        decision: ConversationFilterDecision,
        settings: ConversationFilterSettings,
        evidence: ConversationFilterEvidence,
        stage: String
    ) {
        val nextAction = when {
            stage == "LIST" && decision.result != ConversationFilterResult.EXCLUDE -> "CANDIDATE_PENDING_PROFILE"
            decision.result == ConversationFilterResult.ALLOW -> "CONTINUE_ORIGINAL_WORKFLOW"
            decision.result == ConversationFilterResult.UNKNOWN -> "RECHECK_ON_NEXT_VISIT_OR_EVIDENCE_CHANGE"
            else -> "CONTINUE_TRAVERSAL"
        }
        val event = when (decision.result) {
            ConversationFilterResult.ALLOW -> "CONVERSATION_FILTER_ALLOWED"
            ConversationFilterResult.EXCLUDE -> "CONVERSATION_FILTER_EXCLUDED"
            ConversationFilterResult.UNKNOWN -> "CONVERSATION_FILTER_UNKNOWN"
        }
        // Only normalized threshold/decision information; no raw identifiers or profile text.
        onLog("$event task=RECEIVED_GREETINGS;rowHash=${rowKey.take(12)};stage=$stage reason=${decision.reason ?: "NONE"};" +
            "thresholdMinutes=${settings.maxLastActiveMinutes};" +
            "observedMinutes=${evidence.lastActiveMinutes ?: "UNKNOWN"};" +
            "maxDistanceKm=${settings.maxDistanceKm};observedDistanceKm=${evidence.distanceKm ?: "UNKNOWN"};" +
            "minAge=${settings.minAge};maxAge=${settings.maxAge};observedAge=${evidence.age ?: "UNKNOWN"};" +
            "nextAction=$nextAction")
    }

    private fun dispatchSayHiReadOnlyAvatarTap(
        tree: AccessibilityTreeSnapshot,
        selected: SayHiSelectedGreeting,
        target: MomoSayHiTapTarget,
        allowFreshRevalidation: Boolean = true
    ): Boolean {
        val readiness = sayHiTapReadiness(tree, target)
        when (readiness) {
            MomoSayHiTapReadiness.NEEDS_SCROLL -> {
                // Keep only the stable row identity while the list moves. Every later pass still
                // reacquires a fresh tree and rechecks the current geometry before tapping.
                // Do not relax the tap bounds: let the outer vertical list move it fully onscreen.
                onLog(
                    "收到招呼头像点击暂缓：头像未完整落在显示范围内；" +
                        "目标=${sayHiNavigationEvidenceInspector.formatBounds(target.targetBounds)}；先滚动外层 listview，再重新识别"
                )
                return false
            }
            MomoSayHiTapReadiness.REACQUIRE_SNAPSHOT -> {
                if (!allowFreshRevalidation) {
                    onLog("收到招呼头像点击暂缓：复核快照仍不满足窗口或时效条件；等待新快照")
                    scheduleSayHiListSnapshotInspection(
                        delayMs = 350L,
                        reason = "AVATAR_TAP_REVALIDATION_REQUIRED",
                        retry = false
                    )
                    return true
                }
            }
            MomoSayHiTapReadiness.READY -> Unit
        }
        if (readiness == MomoSayHiTapReadiness.REACQUIRE_SNAPSHOT) {
            val freshRequest = TreeInspectionRequest(
                maxNodes = 1_200,
                maxDepth = 48,
                maxDurationMs = 900L,
                allowVisibleWindowFallback = true,
                textCapture = NodeTextCapture.EPHEMERAL
            )
            val freshTree = inspectVisibleReceivedGreetingsWindow(freshRequest)
                ?: accessibilityGateway.inspectActiveWindow(freshRequest)
            if (freshTree == null || freshTree.truncated ||
                freshTree.window.packageName != targetPackage ||
                !sayHiAccessibilityInspector.hasReceivedGreetingsListFingerprint(freshTree) ||
                !accessibilityGateway.isWindowCurrent(freshTree.window)
            ) {
                onLog("收到招呼头像点击暂缓：旧快照已过期，无法获取当前完整列表快照；不使用旧坐标，安排重新取快照")
                scheduleSayHiListSnapshotInspection(
                    delayMs = 350L,
                    reason = "AVATAR_TAP_FRESH_SNAPSHOT_UNAVAILABLE",
                    retry = false
                )
                return true
            }
            val freshInspection = sayHiAccessibilityInspector.inspect(freshTree)
            if (freshInspection.kind != MomoSayHiPageKind.RECEIVED_GREETINGS_LIST ||
                freshInspection.truncated || freshInspection.queryBudgetExceeded ||
                !freshInspection.containerFound || !freshInspection.containerMatchCountExact ||
                freshInspection.containerAmbiguous
            ) {
                onLog("收到招呼头像点击暂缓：新快照无法完整确认收到招呼列表；保留当前位置，不使用旧坐标")
                return true
            }
            val matchingRows = freshInspection.listRows.filter { row -> MomoSayHiIdentityFingerprintPolicy.rowKey(row) == selected.rowKey }
            val freshTarget = matchingRows.singleOrNull()?.avatarTapTarget
            if (matchingRows.size != 1 || freshTarget == null) {
                onLog("收到招呼头像点击取消：新快照无法唯一找到原始未读用户行；不改点其他用户")
                sayHiStableCandidateKey = ""
                sayHiStableCandidateTarget = null
                sayHiStableCandidateWindow = null
                return true
            }
            if (!MomoSayHiAvatarTargetPolicy.sameTarget(target, freshTarget)) {
                // Position changed while the old snapshot aged. Replace the candidate geometry and
                // require another independent snapshot before any gesture is allowed.
                sayHiStableCandidateKey = selected.rowKey
                sayHiStableCandidateTarget = freshTarget
                sayHiStableCandidateWindow = freshTree.window
                onLog("收到招呼头像点击暂缓：新快照中同一用户头像位置已变化；更新目标并等待下一份稳定快照")
                return true
            }
            onLog("收到招呼头像点击复核：已丢弃旧坐标；新鲜完整快照再次确认同一用户行和头像位置")
            return dispatchSayHiReadOnlyAvatarTap(
                tree = freshTree,
                selected = selected.copy(
                    sourceWindow = freshTree.window,
                    sourceStructureFingerprint = MomoSayHiIdentityFingerprintPolicy.treeStructureFingerprint(freshTree),
                    listFilterEvidence = matchingRows.single().filterEvidence
                ),
                target = freshTarget,
                allowFreshRevalidation = false
            )
        }
        if (!sayHiTraversalState.isDeferredRow(selected.rowKey)) {
            val settings = readSayHiFilterSettings() ?: return true
            if (!passesSayHiListFilter(selected.rowKey, settings, selected.listFilterEvidence)) {
                sayHiStableCandidateKey = ""
                sayHiStableCandidateTarget = null
                sayHiStableCandidateWindow = null
                return true
            }
        }
        val metrics = accessibilityGateway.displayMetrics()
        val executionContext = gesturePolicy.captureContext(tree.window, metrics.toGestureCoordinateContext())
            ?: run {
                onLog("收到招呼头像点击暂缓：窗口或显示坐标上下文发生变化；不使用当前坐标，安排新快照")
                scheduleSayHiListSnapshotInspection(
                    delayMs = 350L,
                    reason = "AVATAR_TAP_COORDINATE_CONTEXT_CHANGED",
                    retry = false
                )
                return true
            }
        sayHiStableCandidateKey = ""
        sayHiStableCandidateTarget = null
        sayHiStableCandidateWindow = null
        sayHiSelectedGreeting = selected
        sayHiSelectedContactIdentity = null
        sayHiSelectedIdentityStatus = MomoSayHiPlatformIdentityStatus.MISSING
        sayHiProfileRowRecoveryAttempts = 0
        clearSayHiRowTapEvidence()
        sayHiProfileReadAttempts = 0
        sayHiProfileScrollAttempted = false
        sayHiProfileDeferredRetryGate.reset()
        sayHiProfileScrollFailed = false
        sayHiProfileScrollInFlight = false
        sayHiProfileScrollWindowId = -1
        sayHiProfileScrollRequestId = null
        sayHiProfileScrollSettledAtMs = 0L
        sayHiProfileBackDialogPending = false
        sayHiProfileBackDialogWindowId = -1
        sayHiProfileBackWindowId = -1
        sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
        sayHiReadOnlyStage = SayHiReadOnlyStage.AVATAR_TAP_PENDING
        sayHiNonSearchGestureInFlight = true
        val request = GestureIntentRequest(
            id = "momo-sayhi-avatar-${System.nanoTime()}",
            intent = GestureIntent.Tap(
                preferredBounds = target.preferredBounds,
                targetBounds = target.targetBounds,
                durationMs = RECEIVED_GREETINGS_ENTRY_TAP_DURATION_MS,
                feedbackIntent = com.freedom.core.accessibility.api.GestureFeedbackIntent.NAVIGATION_ENTRY
            ),
            description = "陌陌收到招呼未读行头像身份读取（只读）",
            executionContext = executionContext
        )
        val callbackGeneration = sayHiRunGenerationGuard.capture()
        val submission = runCatching {
            gesturePolicy.execute(request, GestureExecutionListener { result ->
                if (!sayHiRunGenerationGuard.isCurrent(callbackGeneration)) return@GestureExecutionListener
                sayHiNonSearchGestureInFlight = false
                if (result.status == GestureExecutionStatus.COMPLETED) {
                    sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_PROFILE
                    sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                    onLog("收到招呼头像导航：精确头像手势完成；等待用户资料页与明确身份字段，不点击消息行")
                } else if (result.status == GestureExecutionStatus.DEFERRED) {
                    clearSayHiSelectedGreeting()
                    sayHiStableCandidateKey = selected.rowKey
                    onLog("收到招呼头像导航[DEFERRED]：平台未派发头像点击；不标记跳过，等待新列表快照重新识别")
                    scheduleSayHiListSnapshotInspection(
                        delayMs = 500L,
                        reason = "AVATAR_TAP_CALLBACK_DEFERRED",
                        retry = false
                    )
                } else {
                    deferSayHiRow(selected.rowKey, "ITEM_NOT_READY")
                    clearSayHiSelectedGreeting()
                    onLog("收到招呼头像导航未完成：status=${result.status}；reason=${result.reason}；不改用整行点击")
                }
            })
        }.getOrElse {
            sayHiNonSearchGestureInFlight = false
            deferSayHiRow(selected.rowKey, "ITEM_NOT_READY")
            clearSayHiSelectedGreeting()
            onLog("收到招呼头像导航失败：手势执行器异常；不执行坐标兜底")
            return true
        }
        if (submission == GestureSubmission.DEFERRED_NOT_READY) {
            sayHiNonSearchGestureInFlight = false
            clearSayHiSelectedGreeting()
            sayHiStableCandidateKey = selected.rowKey
            onLog("收到招呼头像导航[DEFERRED_NOT_READY]：弹窗/页面准入暂未放行；未点击、未标记跳过；下一轮使用新列表快照重新识别原用户行")
            scheduleSayHiListSnapshotInspection(
                delayMs = 500L,
                reason = "AVATAR_TAP_SUBMISSION_DEFERRED",
                retry = false
            )
        } else if (submission != GestureSubmission.ACCEPTED) {
            sayHiNonSearchGestureInFlight = false
            deferSayHiRow(selected.rowKey, "ITEM_NOT_READY")
            clearSayHiSelectedGreeting()
            onLog("收到招呼头像导航未提交：统一手势协调器拒绝；submission=$submission")
        } else {
            onLog("收到招呼身份采集：已提交 userlist_item_iv_face 独立头像点击；后续进入已验证聊天事务")
        }
        return true
    }

    private fun dispatchSayHiReadOnlyRowTap(
        tree: AccessibilityTreeSnapshot,
        row: MomoSayHiListRow,
        target: MomoSayHiTapTarget
    ): Boolean {
        when (sayHiTapReadiness(tree, target)) {
            MomoSayHiTapReadiness.NEEDS_SCROLL -> {
                onLog(
                    "收到招呼详情点击暂缓：原消息行未完整落在显示范围内；" +
                        "目标=${sayHiNavigationEvidenceInspector.formatBounds(target.targetBounds)}；先滚动外层 listview"
                )
                return false
            }
            MomoSayHiTapReadiness.REACQUIRE_SNAPSHOT -> {
                onLog("收到招呼详情点击暂缓：快照或窗口上下文已失效；等待新快照，不使用旧坐标")
                scheduleSayHiListSnapshotInspection(
                    delayMs = 350L,
                    reason = "ROW_TAP_REVALIDATION_REQUIRED",
                    retry = false
                )
                return true
            }
            MomoSayHiTapReadiness.READY -> Unit
        }
        val selected = sayHiSelectedGreeting ?: return false
        val revalidationFailure = revalidateSayHiRowTarget(tree, row, target, selected)
        if (revalidationFailure != null) {
            val diagnostic = sayHiNavigationEvidenceInspector.describeSayHiRowTargetRevalidation(tree, row, target, revalidationFailure)
            sayHiNonSearchGestureInFlight = false
            deferSayHiRow(
                selected.rowKey,
                "$SAYHI_SAFE_TARGET_VALIDATION_FAILURE_REASON_PREFIX:$revalidationFailure",
                failureEvidenceHash = MomoSayHiIdentityFingerprintPolicy.sayHiSafeTargetEvidenceHash(tree, row, target)
            )
            onLog(
                "收到招呼整行点击取消：安全区域二次校验失败；reason=$revalidationFailure；" +
                    "$diagnostic；不派发坐标；当前用户已隔离，其他可安全处理用户继续遍历"
            )
            clearSayHiSelectedGreeting()
            sayHiStableCandidateKey = ""
            sayHiStableCandidateTarget = null
            sayHiStableCandidateWindow = null
            scheduleSayHiListSnapshotInspection(
                delayMs = 350L,
                reason = "ROW_SAFE_TAP_TARGET_SECOND_VALIDATION_FAILED",
                retry = false
            )
            return true
        }
        val metrics = accessibilityGateway.displayMetrics()
        val executionContext = gesturePolicy.captureContext(tree.window, metrics.toGestureCoordinateContext())
            ?: run {
                onLog("收到招呼详情点击暂缓：窗口或显示坐标上下文发生变化；安排新快照，不使用旧坐标")
                scheduleSayHiListSnapshotInspection(
                    delayMs = 350L,
                    reason = "ROW_TAP_COORDINATE_CONTEXT_CHANGED",
                    retry = false
                )
                return true
            }
        sayHiReadOnlyStage = SayHiReadOnlyStage.ROW_TAP_PENDING
        sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
        sayHiNonSearchGestureInFlight = true
        pendingSayHiRowTapTarget = target
        sayHiUnverifiedBackAttempted = false
        sayHiUnverifiedBackAttemptActivity = ""
        pendingSayHiRowTapSubmittedAtUptimeMs = 0L
        pendingSayHiRowTapWindowId = tree.window.windowId
        sayHiRowTapFeedbackState = SayHiRowTapFeedbackState.NOT_DISPATCHED
        sayHiRowTapHitReason = ""
        val gestureCommandId = "momo-sayhi-row-detail-${System.nanoTime()}"
        pendingSayHiRowTapGestureCommandId = gestureCommandId
        val request = GestureIntentRequest(
            id = gestureCommandId,
            intent = GestureIntent.Tap(
                preferredBounds = target.preferredBounds,
                targetBounds = target.targetBounds,
                durationMs = RECEIVED_GREETINGS_ENTRY_TAP_DURATION_MS,
                feedbackIntent = com.freedom.core.accessibility.api.GestureFeedbackIntent.NAVIGATION_ENTRY
            ),
            description = "陌陌收到招呼用户行安全区域进入聊天详情",
            executionContext = executionContext
        )
        val callbackGeneration = sayHiRunGenerationGuard.capture()
        val submission = runCatching {
            gesturePolicy.execute(request, object : GestureExecutionListener {
                override fun onDispatched(commandId: String, dispatchedAtUptimeMs: Long) {
                    if (!sayHiRunGenerationGuard.isCurrent(callbackGeneration)) return
                    if (pendingSayHiRowTapGestureCommandId == commandId) {
                        pendingSayHiRowTapSubmittedAtUptimeMs = dispatchedAtUptimeMs
                        sayHiRowTapFeedbackState = SayHiRowTapFeedbackState.WAITING
                    }
                }

                override fun onResult(result: com.freedom.core.accessibility.gesture.GestureExecutionResult) {
                    if (!sayHiRunGenerationGuard.isCurrent(callbackGeneration)) return
                    sayHiNonSearchGestureInFlight = false
                    if (result.status == GestureExecutionStatus.COMPLETED) {
                        sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_DETAIL
                        sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                        if (sayHiRowTapFeedbackState == SayHiRowTapFeedbackState.REJECTED) {
                            onLog("收到招呼点击后核验失败：TYPE_VIEW_CLICKED 未命中会话行安全区域；reason=$sayHiRowTapHitReason；禁止 OCR，转入安全回退判定")
                        } else {
                            onLog("收到招呼详情导航：用户行安全区域手势已完成；等待行级点击事件核验和目标聊天页验证后才允许读取")
                        }
                    } else if (result.status == GestureExecutionStatus.DEFERRED) {
                        resetSayHiRowTapTransaction()
                        sayHiReadOnlyStage = SayHiReadOnlyStage.PROFILE_BACK_PENDING
                        onLog("收到招呼详情点击[DEFERRED]：平台尚未派发用户行点击；保留已验证身份和原始行，等待新鲜列表快照重新识别")
                        scheduleSayHiListSnapshotInspection(
                            delayMs = 500L,
                            reason = "ROW_TAP_CALLBACK_DEFERRED",
                            retry = false
                        )
                    } else {
                        val activityAfterGesture = activityClass()
                        if (inspectCurrentChatDetailFingerprint() != null) {
                            sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_DETAIL
                            sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                            onLog("收到招呼详情导航：手势终态=${result.status}，但当前页面已是目标聊天 Activity；继续等待活动窗口二次确认")
                        } else if (!isReceivedGreetingsListStructureVisible()) {
                            sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_DETAIL
                            sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                            onLog("收到招呼详情点击异常：手势终态=${result.status}，页面=${activityAfterGesture}；不执行 OCR，转入安全 BACK")
                            returnFromUnverifiedSayHiDetailNavigation(
                                activityAfterGesture,
                                "GESTURE_TERMINAL_${result.status}:${result.reason}"
                            )
                        } else {
                            deferSayHiRow(selected.rowKey, "ITEM_NOT_READY")
                            clearSayHiSelectedGreeting()
                            onLog("收到招呼详情导航未完成：status=${result.status}；reason=${result.reason}；仍在列表时跳过原行，不执行备用点击")
                        }
                    }
                }
            })
        }.getOrElse { error ->
            sayHiNonSearchGestureInFlight = false
            val activityAfterFailure = activityClass()
            if (activityAfterFailure.isNotBlank() &&
                !isReceivedGreetingsListStructureVisible() && inspectCurrentChatDetailFingerprint() == null
            ) {
                sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_DETAIL
                sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                onLog("收到招呼详情点击执行异常：页面已离开原列表；不执行 OCR，申请安全 BACK")
                returnFromUnverifiedSayHiDetailNavigation(
                    activityAfterFailure, "GESTURE_EXECUTOR_EXCEPTION:${error.javaClass.simpleName}"
                )
            } else {
                deferSayHiRow(selected.rowKey, "ITEM_NOT_READY")
                clearSayHiSelectedGreeting()
                onLog("收到招呼详情导航失败：手势执行器异常；不执行坐标兜底")
            }
            return true
        }
        if (submission == GestureSubmission.DEFERRED_NOT_READY) {
            sayHiNonSearchGestureInFlight = false
            resetSayHiRowTapTransaction()
            sayHiReadOnlyStage = SayHiReadOnlyStage.PROFILE_BACK_PENDING
            onLog("收到招呼消息行点击[DEFERRED_NOT_READY]：平台手势未派发；保留已验证身份和原始行，不进入详情；重新扫描后再确认")
            scheduleSayHiListSnapshotInspection(
                delayMs = 500L,
                reason = "ROW_TAP_SUBMISSION_DEFERRED",
                retry = false
            )
        } else if (submission != GestureSubmission.ACCEPTED) {
            sayHiNonSearchGestureInFlight = false
            val activityAfterRejection = activityClass()
            if (activityAfterRejection.isNotBlank() &&
                !isReceivedGreetingsListStructureVisible() && inspectCurrentChatDetailFingerprint() == null
            ) {
                sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_DETAIL
                sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                onLog("收到招呼详情点击未提交但页面已变化：不执行 OCR，申请安全 BACK")
                returnFromUnverifiedSayHiDetailNavigation(
                    activityAfterRejection, "GESTURE_SUBMISSION_REJECTED:$submission"
                )
            } else {
                deferSayHiRow(selected.rowKey, "ITEM_NOT_READY")
                clearSayHiSelectedGreeting()
                onLog("收到招呼详情导航未提交：统一手势协调器拒绝；submission=$submission")
            }
        } else {
            onLog("收到招呼会话导航：头像身份步骤已完成；已提交第二阶段用户行安全区域点击；头像区域保留为候选，仅排除 VIP/SVIP、财富等级及独立行内功能控件；若命中头像并进入资料页则复用安全 BACK；进入聊天后由统一授权执行回复")
        }
        return true
    }

    /** Returns a stable failure code instead of hiding the failed proof behind one Boolean. */
    private fun revalidateSayHiRowTarget(
        tree: AccessibilityTreeSnapshot,
        row: MomoSayHiListRow,
        target: MomoSayHiTapTarget,
        selected: SayHiSelectedGreeting
    ): String? {
        if (tree.truncated) return "SNAPSHOT_TRUNCATED"
        if (tree.window.packageName != targetPackage) return "PACKAGE_MISMATCH"
        if (!sameMomoWindowGeneration(tree.window, selected.sourceWindow)) {
            return "WINDOW_GENERATION_CHANGED"
        }
        if (selected.sourceStructureFingerprint != MomoSayHiIdentityFingerprintPolicy.treeStructureFingerprint(tree)) {
            return "SOURCE_STRUCTURE_FINGERPRINT_CHANGED"
        }
        if (!accessibilityGateway.isWindowCurrent(tree.window)) return "WINDOW_NOT_CURRENT"
        val fullPageFingerprint = sayHiAccessibilityInspector.hasReceivedGreetingsListFingerprint(tree)
        // LISTVIEW_SUBTREE snapshots intentionally omit layout_content. They may be used here
        // only while the list controller remains confirmed and the retained exact list target,
        // selected row and current snapshot still belong to the same Android window generation.
        val retainedListWindow = sayHiConfirmedListTarget?.window
        val confirmedSubtreeFingerprint = !fullPageFingerprint &&
            sayHiNavigationFlowController.isReceivedListConfirmed() &&
            retainedListWindow != null &&
            sameMomoWindowGeneration(tree.window, retainedListWindow) &&
            sayHiAccessibilityInspector.hasReceivedGreetingsListSubtreeFingerprint(tree)
        if (!fullPageFingerprint && !confirmedSubtreeFingerprint) {
            return "RECEIVED_GREETINGS_LIST_FINGERPRINT_MISSING"
        }
        if (MomoSayHiIdentityFingerprintPolicy.rowKey(row) != selected.rowKey) return "ROW_IDENTITY_CHANGED"
        if (target.targetBounds != target.preferredBounds) return "TARGET_BOUNDS_DIVERGED"

        val rowPath = NodePath(row.rowPath)
        val namePath = row.namePath ?: return "NAME_NODE_PATH_MISSING"
        val index = NodeTreeIndex(tree)
        val rowNode = index.nodeAt(rowPath) ?: return "ROW_NODE_PATH_NOT_FOUND"
        val targetNode = index.nodeAt(target.path) ?: return "TARGET_NODE_PATH_NOT_FOUND"
        if (!targetNode.visible) return "TARGET_NODE_NOT_VISIBLE"
        if (!targetNode.enabled) return "TARGET_NODE_DISABLED"
        if (!targetNode.clickable) return "TARGET_NODE_NOT_CLICKABLE"
        if (targetNode.bounds.isEmpty) return "TARGET_NODE_BOUNDS_EMPTY"
        if (!containsBounds(targetNode.bounds, target.targetBounds)) {
            return "TARGET_NODE_DOES_NOT_CONTAIN_SAFE_BOUNDS"
        }
        if (!MomoSayHiAvatarTargetPolicy.isWithin(rowPath, target.path)) {
            return "TARGET_NODE_NOT_ROW_ANCESTOR"
        }
        if (!MomoSayHiAvatarTargetPolicy.isWithin(namePath, rowPath)) {
            return "NAME_NODE_NOT_WITHIN_ROW"
        }
        if (!containsBounds(rowNode.bounds, target.targetBounds)) {
            return "ROW_NODE_DOES_NOT_CONTAIN_SAFE_BOUNDS"
        }
        val independentlyResolved = MomoSayHiAvatarTargetPolicy.resolveRowTarget(index, rowPath, namePath)
            ?: return "SAFE_ROW_BOUNDS_RECOMPUTE_FAILED"
        if (target.path != independentlyResolved.path) return "CLICK_ANCESTOR_PATH_CHANGED"
        if (target.targetBounds != independentlyResolved.targetBounds) return "SAFE_ROW_BOUNDS_CHANGED"
        if (target.preferredBounds != independentlyResolved.preferredBounds) {
            return "PREFERRED_ROW_BOUNDS_CHANGED"
        }
        if (target.excludedBounds != independentlyResolved.excludedBounds) {
            return "EXCLUDED_COMPONENT_BOUNDS_CHANGED"
        }
        if (target.targetBounds.width < 24 || target.targetBounds.height < 20) {
            return "SAFE_ROW_BOUNDS_TOO_SMALL"
        }
        return null
    }

    private fun containsBounds(outer: ScreenRect, inner: ScreenRect): Boolean =
        MomoSayHiAvatarTargetPolicy.contains(outer, inner)

    private fun clearSayHiRowTapEvidence() {
        sayHiUnverifiedDetailReturnPending = false
        pendingSayHiRowTapTarget = null
        pendingSayHiRowTapSubmittedAtUptimeMs = 0L
        pendingSayHiRowTapGestureCommandId = null
        pendingSayHiRowTapWindowId = -1
        sayHiRowTapFeedbackState = SayHiRowTapFeedbackState.NOT_DISPATCHED
        sayHiRowTapHitReason = ""
        sayHiUnverifiedBackAttempted = false
        sayHiUnverifiedBackAttemptActivity = ""
    }

    private fun resetSayHiRowTapTransaction() {
        pendingSayHiRowTapTarget = null
        pendingSayHiRowTapSubmittedAtUptimeMs = 0L
        pendingSayHiRowTapGestureCommandId = null
        pendingSayHiRowTapWindowId = -1
        sayHiRowTapFeedbackState = SayHiRowTapFeedbackState.NOT_DISPATCHED
        sayHiRowTapHitReason = ""
    }


    /**
     * Never rearm an exhausted row from a timestamp or a mere changed list position.
     * A current complete list, unique row, safe recomputed target, active window and
     * two strictly fresh snapshots are all required; this method NEVER clicks.
     */
    private fun inspectExhaustedSayHiSafeTargetEvidence(
        tree: AccessibilityTreeSnapshot,
        inspection: MomoSayHiPageInspection,
        candidates: List<MomoSayHiListRow>,
        confirmedRows: Set<String>
    ): Boolean {
        if (!sayHiTraversalState.hasExhaustedSafeTargetRows ||
            inspection.kind != MomoSayHiPageKind.RECEIVED_GREETINGS_LIST ||
            !sayHiNavigationFlowController.isReceivedListConfirmed() ||
            !accessibilityGateway.isWindowCurrent(tree.window) ||
            sayHiGestureInFlight || sayHiReadOnlyStage != SayHiReadOnlyStage.IDLE
        ) return false
        val index = NodeTreeIndex(tree)
        val now = System.currentTimeMillis()
        var verificationScheduled = false
        for (row in candidates) {
            val key = MomoSayHiIdentityFingerprintPolicy.rowKey(row)
            if (key in confirmedRows || !sayHiTraversalState.isDeferredRow(key) ||
                sayHiTraversalState.shouldRetryDeferredRow(key) ||
                candidates.count { MomoSayHiIdentityFingerprintPolicy.rowKey(it) == key } != 1
            ) continue
            val namePath = row.namePath ?: continue
            val target = MomoSayHiAvatarTargetPolicy.resolveRowTarget(
                index, NodePath(row.rowPath), namePath
            ) ?: continue
            if (sayHiTapReadiness(tree, target) != MomoSayHiTapReadiness.READY) continue
            val selected = SayHiSelectedGreeting(
                rowKey = key,
                displayName = row.displayName.value.orEmpty(),
                greetingText = row.greetingText.value,
                timestampText = row.timestampText.value,
                sourceWindow = tree.window,
                sourceStructureFingerprint =
                    MomoSayHiIdentityFingerprintPolicy.treeStructureFingerprint(tree)
            )
            if (revalidateSayHiRowTarget(tree, row, target, selected) != null) continue
            val evidenceHash = MomoSayHiIdentityFingerprintPolicy.sayHiSafeTargetEvidenceHash(tree, row, target)
            val result = sayHiTraversalState.observeExhaustedSafeTargetEvidence(
                rowKey = key,
                evidenceHash = evidenceHash,
                windowKey = sayHiWindowStabilityKey(tree.window),
                capturedAtEpochMs = tree.capturedAtEpochMs,
                nowEpochMs = now
            )
            when (result) {
                MomoSayHiTraversalState.SafeEvidenceRecovery.BASELINE_RECORDED ->
                    onLog("GREETING_DEFERRED_RECOVERY_BASELINE：历史断点缺少失败场景证据；只记录安全基线，不恢复点击额度")
                MomoSayHiTraversalState.SafeEvidenceRecovery.WAIT_SECOND_SNAPSHOT -> {
                    onLog("GREETING_DEFERRED_RECOVERY_VERIFYING：新的安全区域证据待第二份新鲜快照确认；未派发手势")
                    verificationScheduled = true
                }
                MomoSayHiTraversalState.SafeEvidenceRecovery.REARMED -> {
                    sayHiTraversalState.sayHiDeferredSafeEvidenceOnlyWait = false
                    onLog("GREETING_DEFERRED_RECOVERY_REARMED：行身份、窗口及变更后的安全区域已双快照确认；恢复有限点击预算，不计入成功")
                    verificationScheduled = true
                }
                MomoSayHiTraversalState.SafeEvidenceRecovery.NOT_READY -> Unit
            }
        }
        if (verificationScheduled) {
            scheduleSayHiListSnapshotInspection(
                delayMs = 1_500L,
                reason = "DEFERRED_SAFE_TARGET_READ_ONLY_PROOF",
                retry = false
            )
        }
        return verificationScheduled
    }

    private fun deferSayHiRow(
        rowKey: String, reason: String, failureEvidenceHash: String? = null
    ) {
        val result = sayHiTraversalState.deferRow(
            rowKey, reason, System.currentTimeMillis(), failureEvidenceHash
        )
        onLog(
            "收到招呼单用户隔离[DEFERRED]：rowKeyHash=$rowKey；reason=$reason；" +
                "cooldown=${result.delayMs}ms；backoffStage=${result.backoffStage}；" +
                "safeTargetFailures=${result.safeTargetFailures}/${MomoSayHiSafeTargetValidationRetryPolicy.MAX_FAILURES}；" +
                "safeTargetRetryExhausted=${result.safeTargetRetryExhausted}；未计入成功"
        )
    }

    private fun hasRetryableSayHiDeferredRows(): Boolean =
        sayHiTraversalState.hasRetryableDeferredRows()

    private fun clearSayHiRowDeferral(rowKey: String) {
        sayHiTraversalState.clearDeferral(rowKey)
    }

    private fun persistSayHiTraversalCheckpoint() {
        sayHiTraversalState.persist()
    }

    private fun handleSayHiRowTapClickEvidence(event: AccessibilityEventEnvelope) {
        if (event.type != com.freedom.core.accessibility.events.AccessibilityEventType.VIEW_CLICKED ||
            event.packageName != targetPackage ||
            sayHiReadOnlyStage !in setOf(SayHiReadOnlyStage.ROW_TAP_PENDING, SayHiReadOnlyStage.WAITING_DETAIL)
        ) return
        val target = pendingSayHiRowTapTarget ?: return
        // No event can verify this tap until the coordinator reports its actual platform dispatch.
        if (pendingSayHiRowTapSubmittedAtUptimeMs <= 0L ||
            event.eventTimeMillis < pendingSayHiRowTapSubmittedAtUptimeMs
        ) return
        if (pendingSayHiRowTapWindowId >= 0 && event.windowId >= 0 &&
            event.windowId != pendingSayHiRowTapWindowId
        ) return
        val source = event.source
        val hit = MomoSayHiRowClickHitPolicy.evaluate(
            safeTapBounds = target.preferredBounds,
            sourceViewId = source?.viewId,
            sourceContentDescription = source?.contentDescription,
            sourceBounds = source?.bounds,
            sourceClickable = source?.clickable == true
        )
        val sourceId = source?.viewId.orEmpty().substringAfterLast('/').lowercase()
        val reason = (hit as? MomoSayHiRowClickHitResult.Rejected)?.let {
            "${it.reason}:${it.sourceViewId}"
        }
        if (reason != null) {
            sayHiRowTapFeedbackState = SayHiRowTapFeedbackState.REJECTED
            sayHiRowTapHitReason = reason
            onLog("收到招呼点击事件核验[REJECTED]：TYPE_VIEW_CLICKED 未能证明命中用户行安全区域；sourceId=$sourceId；reason=$reason；禁止将其解释为聊天详情导航")
        } else {
            // A late, window/time-matched accepted event supersedes a timeout-only state.
            // It may not override a genuine rejected event from a different source event, but
            // this exact event has just passed the row-hit policy and therefore is authoritative.
            // One enum value owns the whole feedback outcome; a late accepted event atomically
            // supersedes TIMED_OUT/REJECTED for this same command/window transaction.
            sayHiRowTapFeedbackState = SayHiRowTapFeedbackState.VERIFIED
            sayHiRowTapHitReason = ""
            val acceptedHit = hit as? MomoSayHiRowClickHitResult.Accepted
            if (acceptedHit?.avatarHit == true) {
                onLog("收到招呼点击事件核验[AVATAR_HIT]：整行候选区域实际命中头像；等待页面识别，若进入资料页则安全返回，不读取聊天 OCR；sourceId=$sourceId")
            } else {
                onLog("收到招呼点击事件核验[ACCEPTED]：TYPE_VIEW_CLICKED 来源是覆盖整块行点击候选区域的可点击行容器；sourceId=$sourceId")
            }
        }
    }

    private fun sayHiTapReadiness(
        tree: AccessibilityTreeSnapshot,
        target: MomoSayHiTapTarget
    ): MomoSayHiTapReadiness {
        val metrics = accessibilityGateway.displayMetrics()
        val fresh = System.currentTimeMillis() - tree.capturedAtEpochMs <= SAYHI_ROW_PRE_TAP_MAX_SNAPSHOT_AGE_MS
        return MomoSayHiTapReadinessPolicy.evaluate(
            snapshotFresh = fresh,
            packageMatches = tree.window.packageName == targetPackage,
            windowCurrent = accessibilityGateway.isWindowCurrent(tree.window),
            targetBounds = target.targetBounds,
            preferredBounds = target.preferredBounds,
            displayBounds = ScreenRect(0, 0, metrics.widthPixels, metrics.heightPixels)
        )
    }

    private fun inspectPendingSayHiAvatarProfile(currentActivity: String): Boolean {
        if (sayHiReadOnlyStage == SayHiReadOnlyStage.AVATAR_TAP_PENDING || sayHiGestureInFlight) return true
        val selected = sayHiSelectedGreeting ?: run {
            sayHiReadOnlyStage = SayHiReadOnlyStage.IDLE
            return true
        }
        val ageMs = System.currentTimeMillis() - sayHiReadOnlyStageStartedAtMs
        val observedDialog = latestDialogWindowObservation()
        if (observedDialog != null) {
            val dialogStillPresent = rootAccess.isTargetWindowPresent(observedDialog.windowId)
            if (dialogStillPresent == true) {
                onLog(
                    "收到招呼身份采集：检测到仍存在的 dialog.h 窗口；" +
                        "先执行独立弹层安全检查和返回，不在对话框上读取 ID 或滑动"
                )
                return requestSayHiProfileBack()
            }
            if (dialogStillPresent == false) {
                onLog("收到招呼身份采集：dialog.h 事件对应窗口已不在当前窗口集合；忽略过期观察并继续检查资料页")
            } else {
                onLog("收到招呼身份采集：无法确认 dialog.h 窗口当前是否存在；暂停节点采集和滑动，等待下一次窗口快照")
                return true
            }
        }

        if (sayHiProfileScrollFailed) {
            sayHiSelectedIdentityStatus = MomoSayHiPlatformIdentityStatus.MISSING
            sessionComponents.sampleStore.persistSayHiIdentityResult(
                selected.rowKey, selected.displayName, sayHiSelectedIdentityStatus,
                0, "PROFILE_IDENTITY_SCROLL_NOT_COMPLETED", null
            )
            onLog("收到招呼头像身份采集：资料页向上滑动未完成；身份保留为 UNKNOWN")
            return returnFromSayHiIdentityProfile(true)
        }
        if (sayHiProfileScrollSettledAtMs > 0L &&
            System.currentTimeMillis() - sayHiProfileScrollSettledAtMs < SAYHI_PROFILE_SCROLL_SETTLE_MS
        ) return true

        val tree = accessibilityGateway.inspectActiveWindow(
            TreeInspectionRequest(
                maxNodes = 1_200,
                textCapture = NodeTextCapture.EPHEMERAL,
                maxDepth = 48,
                maxDurationMs = 800L
            )
        )
        if (tree == null || tree.window.packageName != targetPackage ||
            tree.window.windowId < 0 || !accessibilityGateway.isWindowCurrent(tree.window)) {
            if (ageMs < SAYHI_PROFILE_PAGE_TIMEOUT_MS) return true
            sayHiSelectedIdentityStatus = MomoSayHiPlatformIdentityStatus.MISSING
            sessionComponents.sampleStore.persistSayHiIdentityResult(
                selected.rowKey, selected.displayName, sayHiSelectedIdentityStatus,
                0, "PROFILE_SNAPSHOT_UNAVAILABLE", null
            )
            onLog("收到招呼头像身份采集暂停：资料页快照持续不可用；不猜测 ID、不点击消息行")
            return returnFromSayHiIdentityProfile(true)
        }

        sayHiProfileReadAttempts++
        if (tree.truncated) {
            if (sayHiProfileReadAttempts < SAYHI_PROFILE_ID_MAX_ATTEMPTS) return true
            sayHiSelectedIdentityStatus = MomoSayHiPlatformIdentityStatus.MISSING
            sessionComponents.sampleStore.persistSayHiIdentityResult(
                selected.rowKey, selected.displayName, sayHiSelectedIdentityStatus,
                0,
                if (sayHiProfileScrollAttempted) "PROFILE_TREE_INCOMPLETE_AFTER_SCROLL" else "PROFILE_TREE_INCOMPLETE",
                null
            )
            onLog("收到招呼头像身份采集：资料页节点树不完整；不把未扫描区域判定为没有 ID")
            return returnFromSayHiIdentityProfile(true)
        }

        // Read ID evidence directly. Only reject a known source page or stale window;
        // optional profile presentation nodes are never identity prerequisites.
        val readout = MomoSayHiPlatformIdentityParser.parse(tree.nodes)
        if (isReceivedGreetingsListConfirmed()) {
            if (ageMs >= SAYHI_PROFILE_PAGE_TIMEOUT_MS) {
                return returnFromSayHiIdentityProfile(false)
            }
            return true
        }
        if (inspectCurrentChatDetailFingerprint() != null ||
            !accessibilityGateway.isWindowCurrent(tree.window)
        ) return true
        // Reuse only the already acquired identity snapshot. Keep the first-screen evidence
        // across the existing one allowed ID swipe; later available fields may fill gaps.
        // None of these optional fields can authorize a profile, ID or navigation action.
        if (!sayHiTraversalState.isDeferredRow(selected.rowKey) &&
            readSayHiFilterSettings()?.hasEnabledConditions == true
        ) {
            val observed = MomoConversationFilterEvidenceParser.fromProfile(tree)
            if (sayHiSelectedGreeting === selected) {
                sayHiSelectedGreeting = selected.copy(
                    profileFilterEvidence = selected.profileFilterEvidence?.supplementedBy(observed) ?: observed
                )
            }
        }
        if (readout.status == MomoSayHiPlatformIdentityStatus.CONFIRMED) {
            val resolved = sessionComponents.contactIdentityManager.resolveSnapshot(
                snapshotId = "sayhi-${System.currentTimeMillis()}-${selected.rowKey.take(12)}",
                observations = listOf(
                    ContactIdentityObservation(
                        displayName = selected.displayName,
                        isPrivateChat = true,
                        platformUserId = readout.value
                    )
                ),
                observedAtMillis = System.currentTimeMillis()
            ).singleOrNull()
            sayHiSelectedContactIdentity = resolved
            val hasVerifiedPlatformId = resolved?.let {
                it.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                    !it.contactId.isNullOrBlank()
            } == true
            sayHiSelectedIdentityStatus = if (hasVerifiedPlatformId) {
                MomoSayHiPlatformIdentityStatus.CONFIRMED
            } else {
                MomoSayHiPlatformIdentityStatus.AMBIGUOUS
            }
            val identityArchived = sessionComponents.sampleStore.persistSayHiIdentityResult(
                selected.rowKey, selected.displayName, sayHiSelectedIdentityStatus,
                readout.candidateCount, readout.source, resolved
            )
            onLog(
                "收到招呼头像身份采集：已从资料页无障碍节点取得唯一 ID 证据；" +
                    "本地身份样本归档=${if (identityArchived) "成功" else "未成功"}；" +
                    "来源=${readout.source}；原始 ID 不写入运行日志"
            )
            return returnFromSayHiIdentityProfile(true)
        }

        if (!sayHiProfileScrollAttempted) {
            // The ID is missing; only the existing safe profile-scroll path may move the UI.
            // A deferred swipe must not be resubmitted on every identical observation.
            if (!sayHiProfileDeferredRetryGate.mayAttempt(tree.window, SystemClock.uptimeMillis())) {
                return true
            }
            onLog(
                "收到招呼头像身份采集[INITIAL_SCAN]：资料页首次完整节点扫描未取得唯一用户 ID；" +
                    "status=${readout.status}；准备执行且仅执行一次纵向向上滑动"
            )
            return dispatchSayHiProfileIdentityScroll(tree)
        }

        sayHiSelectedIdentityStatus = readout.status
        sessionComponents.sampleStore.persistSayHiIdentityResult(
            selected.rowKey, selected.displayName, readout.status,
            readout.candidateCount, readout.source, null
        )
        onLog(
            if (readout.status == MomoSayHiPlatformIdentityStatus.AMBIGUOUS) {
                "收到招呼头像身份采集[POST_SCROLL]：上滑后仍出现多个不同的明确身份候选；标记身份冲突，不关联联系人"
            } else {
                "收到招呼头像身份采集[POST_SCROLL]：完成唯一一次向上滑动和第二次节点扫描；仍未找到明确 ID，保留 UNKNOWN"
            }
        )
        return returnFromSayHiIdentityProfile(true)
    }

    /** Dispatches the one allowed upward profile scroll through the shared gesture policy path. */
    private fun dispatchSayHiProfileIdentityScroll(tree: AccessibilityTreeSnapshot): Boolean {
        if (sayHiProfileScrollAttempted) return true
        val selected = sayHiSelectedGreeting ?: return false
        if (!isIdentityProfileActionWindow(tree)) {
            onLog("收到招呼身份采集：资料页滑动安全条件尚未确认；等待新鲜快照，不消耗滑动机会")
            return true
        }
        val metrics = accessibilityGateway.displayMetrics()
        val decision = MomoSayHiProfileScrollPolicy.resolveIdentityViewport(
            nodes = tree.nodes,
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels
        )
        val viewport = decision.viewport
        if (viewport == null) {
            if (System.currentTimeMillis() - sayHiReadOnlyStageStartedAtMs < SAYHI_PROFILE_PAGE_TIMEOUT_MS) {
                onLog("收到招呼身份采集：安全滑动视口暂不可用；reason=${decision.reason}；等待新鲜快照，不消耗滑动机会")
                return true
            }
            sayHiSelectedIdentityStatus = MomoSayHiPlatformIdentityStatus.MISSING
            sessionComponents.sampleStore.persistSayHiIdentityResult(
                selected.rowKey, selected.displayName, sayHiSelectedIdentityStatus,
                0, "PROFILE_SCROLL_VIEWPORT_UNAVAILABLE:${decision.reason}", null
            )
            onLog(
                "收到招呼身份采集：首次扫描未找到 ID，但无法唯一确认安全滑动视口；" +
                    "candidates=${decision.candidateCount}；reason=${decision.reason}；不猜测坐标"
            )
            return returnFromSayHiIdentityProfile(true)
        }
        if (!isIdentityProfileActionWindow(tree)) {
            sayHiProfileScrollAttempted = false
            onLog("收到招呼身份采集：资料页窗口在滑动准备期间发生变化；丢弃旧坐标")
            return true
        }
        val executionContext = gesturePolicy.captureContext(
            tree.window,
            metrics.toGestureCoordinateContext()
        ) ?: run {
            // No platform gesture was submitted. Preserve the one permitted scroll attempt for
            // a later fresh snapshot instead of consuming it on a stale execution context.
            sayHiProfileScrollAttempted = false
            sayHiProfileScrollFailed = false
            onLog("收到招呼身份采集：资料页滑动坐标上下文已失效；等待新鲜快照，不使用旧视口")
            return true
        }
        val request = GestureIntentRequest(
            id = "momo-sayhi-profile-id-scroll-${System.nanoTime()}",
            intent = GestureIntent.VerticalSwipe(
                viewport = viewport,
                direction = VerticalSwipeDirection.UP,
                durationMs = SAYHI_PROFILE_IDENTITY_SCROLL_DURATION_MS
            ),
            description = "收到招呼用户资料页身份字段只读上滑",
            executionContext = executionContext
        )
        sayHiProfileScrollRequestId = request.id
        sayHiProfileScrollWindowId = tree.window.windowId
        sayHiProfileScrollInFlight = true
        sayHiProfileScrollFailed = false
        sayHiReadOnlyStage = SayHiReadOnlyStage.PROFILE_SCROLL_PENDING
        sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
        sayHiNonSearchGestureInFlight = true
        onLog(
            "收到招呼身份采集[PROFILE_SCROLL]：已唯一确认可滚动资料内容视口；" +
                "candidates=${decision.candidateCount}；fingerprint=${decision.reason}；" +
                "targetPath=${decision.targetPath ?: "none"}；" +
                "bounds=${viewport.left},${viewport.top},${viewport.right},${viewport.bottom}；" +
                "direction=UP；count=1"
        )
        val callbackGeneration = sayHiRunGenerationGuard.capture()
        val submission = runCatching {
            gesturePolicy.execute(request, GestureExecutionListener { result ->
                if (!sayHiRunGenerationGuard.isCurrent(callbackGeneration)) return@GestureExecutionListener
                if (sayHiProfileScrollRequestId != request.id) return@GestureExecutionListener
                sayHiNonSearchGestureInFlight = false
                sayHiProfileScrollInFlight = false
                sayHiProfileScrollRequestId = null
                if (result.status == GestureExecutionStatus.COMPLETED) {
                    sayHiProfileScrollAttempted = true
                    sayHiProfileDeferredRetryGate.reset()
                    sayHiProfileScrollFailed = false
                    sayHiProfileScrollSettledAtMs = System.currentTimeMillis()
                    sayHiProfileReadAttempts = 0
                    sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_PROFILE
                    sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                    onLog("收到招呼身份采集[PROFILE_SCROLL_COMPLETED]：单次向上滑动完成；等待新鲜资料页快照后重新提取 ID")
                } else if (result.status == GestureExecutionStatus.DEFERRED) {
                    sayHiProfileDeferredRetryGate.onDeferred(
                        request.id, tree.window, SystemClock.uptimeMillis()
                    )
                    sayHiProfileScrollFailed = false
                    sayHiProfileScrollAttempted = false
                    sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_PROFILE
                    sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                    onLog("收到招呼身份采集[PROFILE_SCROLL_DEFERRED]：资料页未发生滑动；保留唯一滑动机会，等待新鲜快照")
                } else {
                    sayHiProfileScrollFailed = true
                    sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_PROFILE
                    sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
                    onLog("收到招呼身份采集[PROFILE_SCROLL_FAILED]：status=${result.status}；reason=${result.reason}；不重复滑动")
                }
            })
        }.getOrElse {
            sayHiNonSearchGestureInFlight = false
            sayHiProfileScrollInFlight = false
            sayHiProfileScrollRequestId = null
            sayHiProfileScrollFailed = true
            sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_PROFILE
            sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
            onLog("收到招呼身份采集：资料页上滑协调器异常；type=${it.javaClass.simpleName}；不重复滑动")
            return true
        }
        if (submission == GestureSubmission.DEFERRED_NOT_READY) {
            sayHiProfileDeferredRetryGate.onDeferred(request.id, tree.window, SystemClock.uptimeMillis())
            sayHiNonSearchGestureInFlight = false
            sayHiProfileScrollInFlight = false
            sayHiProfileScrollRequestId = null
            sayHiProfileScrollAttempted = false
            sayHiProfileScrollFailed = false
            sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_PROFILE
            sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
            onLog("收到招呼身份资料页上滑[DEFERRED_NOT_READY]：页面暂不可操作，未派发滑动且不消耗唯一滑动机会；等待新鲜资料页快照")
        } else if (submission != GestureSubmission.ACCEPTED) {
            sayHiNonSearchGestureInFlight = false
            sayHiProfileScrollInFlight = false
            sayHiProfileScrollRequestId = null
            sayHiProfileScrollFailed = true
            sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_PROFILE
            sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
            onLog("收到招呼身份采集：统一手势协调器拒绝资料页上滑；submission=$submission；不重复滑动")
        }
        return true
    }

    /**
     * Read-only identity is established by the existing unique-ID parser. For a UI action
     * before an ID becomes visible, require the existing profile Activity in a fresh
     * current target window. Never issue a profile action on a confirmed source page.
     * This is action safety, not a prerequisite for extracting an ID.
     */
    private fun isIdentityProfileActionWindow(tree: AccessibilityTreeSnapshot): Boolean {
        if (tree.truncated || tree.window.packageName != targetPackage ||
            tree.window.windowId < 0 || !accessibilityGateway.isWindowCurrent(tree.window)
        ) return false
        if (isReceivedGreetingsListConfirmed() || inspectCurrentChatDetailFingerprint() != null) return false
        val evidence = tree.window.activityClass == MomoSayHiPageContract.PROFILE_ACTIVITY ||
            MomoSayHiPlatformIdentityParser.parse(tree.nodes).status == MomoSayHiPlatformIdentityStatus.CONFIRMED
        return evidence && accessibilityGateway.isWindowCurrent(tree.window)
    }

    private fun isSayHiProfileSurfacePresent(): Boolean {
        val tree = runCatching {
            accessibilityGateway.inspectActiveWindow(
                TreeInspectionRequest(maxNodes = 1_200, maxDepth = 48, maxDurationMs = 800L,
                    textCapture = NodeTextCapture.EPHEMERAL)
            )
        }.getOrNull() ?: return false
        return isIdentityProfileActionWindow(tree)
    }

    private fun returnFromSayHiIdentityProfile(pageChanged: Boolean): Boolean {
        val selected = sayHiSelectedGreeting ?: return false
        if (!pageChanged) {
            deferSayHiRow(selected.rowKey, "ITEM_NOT_READY")
            clearSayHiSelectedGreeting()
            return inspectSayHiPageAndAutonomousBrowse(
                allowAutonomousScroll = true, minimumIntervalMs = 0L, forceFresh = true
            )
        }
        val accepted = requestSayHiProfileBack()
        if (!accepted) {
            onLog("收到招呼身份页面：资料页专用 BACK 未通过新鲜窗口/弹层安全检查；保留现场并暂停后续行点击")
        }
        return accepted
    }

    /**
     * A ChatActivity BACK is permitted only after re-resolving the exact active Momo window and
     * completing the bounded overlay scan for both the activity and any currently observed
     * dialog.h window. The caller must still verify the destination page after dispatch.
     */
    private fun requestChatActivityBack(
        reason: String,
        expectedWindow: WindowRef? = null,
        targetActivityClass: String = MomoSayHiPageContract.LIST_ACTIVITY,
        onSubmitted: (WindowRef) -> Unit = {}
    ): Boolean {
        val dialogObservation = latestDialogWindowObservation()
        val inspection = rootAccess.withTargetWindowRoots { windows ->
            if (windows.isEmpty()) return@withTargetWindowRoots null
            val active = windows.firstOrNull { it.active }
                ?: windows.firstOrNull { it.focused }
                ?: run {
                    onLog("ChatActivity BACK 安全检查拒绝：没有可确认的活动/焦点窗口；reason=$reason")
                    return@withTargetWindowRoots null
                }
            if (active.window.packageName != targetPackage || active.window.windowId < 0 ||
                !accessibilityGateway.isWindowCurrent(active.window)
            ) {
                onLog(
                    "ChatActivity BACK 安全检查拒绝：活动窗口身份不匹配；" +
                        "package=${active.window.packageName}；activityHint=${active.window.activityClass}；" +
                        "windowId=${active.window.windowId}；reason=$reason"
                )
                return@withTargetWindowRoots null
            }
            if (sayHiAccessibilityInspector.hasReceivedGreetingsListFingerprint(
                    active.root, targetPackage
                )
            ) {
                onLog(
                    "ChatActivity BACK 安全检查拒绝：当前活动根节点已出现收到招呼 listview；" +
                        "页面身份可能尚未同步，禁止从列表页再次返回；reason=$reason"
                )
                return@withTargetWindowRoots null
            }

            val chatFingerprint = chatComponents.pageInspector.inspectRoot(
                root = AccessibilityNodeInfo.obtain(active.root),
                targetPackage = targetPackage,
                activityClass = active.window.activityClass
            )
            if (chatFingerprint.pageType != MomoChatPageType.CHAT_DETAIL_CANDIDATE ||
                !chatFingerprint.hasStableChatDetailFingerprint
            ) {
                onLog(
                    "ChatActivity BACK 安全检查拒绝：当前活动窗口未通过统一聊天详情页指纹；" +
                        "${chatFingerprint.summary()}；reason=$reason"
                )
                return@withTargetWindowRoots null
            }
            onLog(
                "ChatActivity BACK 页面指纹通过：至少一条完整的同窗口聊天详情识别路径已命中，" +
                    "且当前活动窗口/页面代次已独立复核；windowId=${active.window.windowId}；" +
                    "generation=${active.window.generation}；reason=$reason"
            )

            val dialogRoot = dialogObservation?.let { observed ->
                windows.firstOrNull { it.window.windowId == observed.windowId }
            }
            if (dialogObservation != null && dialogRoot == null) {
                val dialogPresent = rootAccess.isTargetWindowPresent(dialogObservation.windowId)
                if (dialogPresent != false) {
                    onLog(
                        "ChatActivity BACK 安全检查拒绝：观察到 dialog.h，但无法取得其当前窗口根节点" +
                            "或证明该窗口已消失；windowId=${dialogObservation.windowId}；reason=$reason"
                    )
                    return@withTargetWindowRoots null
                }
                onLog(
                    "ChatActivity BACK 安全取证[DIALOG_WINDOW_GONE]：" +
                        "observedWindowId=${dialogObservation.windowId}；" +
                        "activeWindowId=${active.window.windowId}；已确认过期弹层窗口消失"
                )
            }

            val rootsToInspect = buildList {
                add(active)
                if (dialogRoot != null && dialogRoot.window.windowId != active.window.windowId) {
                    add(dialogRoot)
                }
            }
            for (candidate in rootsToInspect) {
                val isObservedDialog = dialogObservation?.windowId == candidate.window.windowId
                val inspectionActivity = if (isObservedDialog) {
                    dialogObservation?.className
                } else {
                    candidate.window.activityClass
                }
                val safety = browseComponents.popupInspector.inspectBlockingOverlay(
                    candidate.root,
                    inspectionActivity
                )
                onLog(
                    "ChatActivity BACK 安全取证[WINDOW]：windowId=${candidate.window.windowId}；" +
                        "active=${candidate.active}；focused=${candidate.focused}；" +
                        "root=${if (isObservedDialog) dialogObservation?.className else candidate.window.activityClass}；" +
                        "visited=${safety.visitedNodes}；elapsedMs=${safety.elapsedMs}；" +
                        "complete=${safety.complete}；stopReason=${safety.stopReason ?: "none"}；" +
                        "popupCandidates=${safety.popupCandidateCount}；" +
                        "blocking=${safety.blockingReason?.take(120) ?: "none"}"
                )
                if (!safety.complete || !safety.blockingReason.isNullOrBlank()) {
                    onLog("ChatActivity BACK 安全检查拒绝：窗口扫描不完整或存在阻断弹层；reason=$reason")
                    return@withTargetWindowRoots null
                }
            }
            ChatActivityBackInspection(active.window)
        } ?: return false

        if (expectedWindow != null && (
                inspection.window.packageName != expectedWindow.packageName ||
                    inspection.window.windowId != expectedWindow.windowId ||
                    inspection.window.generation != expectedWindow.generation
                )
        ) {
            onLog(
                "ChatActivity 安全 BACK 拒绝：当前窗口身份与首次 BACK 的来源窗口不一致；" +
                    "expected=${expectedWindow.windowId}/${expectedWindow.generation}；" +
                    "actual=${inspection.window.windowId}/${inspection.window.generation}；reason=$reason"
            )
            return false
        }
        val stillSameDetail = rootAccess.withActiveTargetRoot { root, window ->
            sameMomoWindowGeneration(window, inspection.window) &&
                chatComponents.pageInspector.inspectRoot(
                    root = AccessibilityNodeInfo.obtain(root),
                    targetPackage = targetPackage,
                    activityClass = window.activityClass
                ).let { page ->
                    page.pageType == MomoChatPageType.CHAT_DETAIL_CANDIDATE &&
                        page.hasStableChatDetailFingerprint
                }
        } ?: false
        if (!stillSameDetail || !accessibilityGateway.isWindowCurrent(inspection.window)) {
            onLog("聊天详情结构安全 BACK 拒绝：弹层检查后页面指纹、窗口或页面代次发生变化；reason=$reason")
            return false
        }
        val accepted = accessibilityGateway.performVerifiedNavigationBack(
            inspection.window,
            inspection.window.activityClass,
            targetActivityClass
        )
        onLog(
            "ChatActivity 安全 BACK 派发：windowId=${inspection.window.windowId}；" +
                "generation=${inspection.window.generation}；platformAccepted=$accepted；" +
                "reason=$reason；path=${if (accepted) "VERIFIED_NAVIGATION_BACK" else "REJECTED"}"
        )
        if (accepted) onSubmitted(inspection.window)
        return accepted
    }

    /** Structural identity confirmation; Activity names and stale list-view events are not enough. */
    private fun isReceivedGreetingsListConfirmed(): Boolean = isReceivedGreetingsListStructureVisible()

    /** Current list identity includes the actual Android window and page generation. */
    private fun receivedGreetingsListWindowIdentity(): String? =
        rootAccess.withActiveTargetRoot { root, window ->
            if (window.packageName != targetPackage || window.windowId < 0 ||
                !accessibilityGateway.isWindowCurrent(window) ||
                !sayHiAccessibilityInspector.hasReceivedGreetingsListFingerprint(root, targetPackage)
            ) return@withActiveTargetRoot null
            if (!accessibilityGateway.isWindowCurrent(window)) return@withActiveTargetRoot null
            MomoWindowPageFingerprint.windowIdentity(
                window = window,
                targetPackage = targetPackage,
                pageKind = "RECEIVED_GREETINGS_LIST"
            )
        }

    private fun isReceivedGreetingsListStructureVisible(): Boolean =
        rootAccess.withActiveTargetRoot { root, window ->
            window.packageName == targetPackage && window.windowId >= 0 &&
                accessibilityGateway.isWindowCurrent(window) &&
                sayHiAccessibilityInspector.hasReceivedGreetingsListFingerprint(root, targetPackage) &&
                accessibilityGateway.isWindowCurrent(window)
        } == true

    override fun receivedGreetingsRecoveryFingerprint(): String? =
        receivedGreetingsListWindowIdentity()

    override fun reloadReceivedGreetingsCheckpointAfterServiceReconnect(): Boolean {
        val restored = sayHiTraversalState.restore() ?: return false
        val pageVerified = isReceivedGreetingsListStructureVisible()
        onLog(
            "收到招呼服务重连：已重新读取断点；成功记录=${restored.processedRows}；" +
                "待恢复用户=${restored.deferredRows}；当前列表指纹=${if (pageVerified) "已确认" else "待重新确认"}"
        )
        return pageVerified
    }

    override fun persistReceivedGreetingsCheckpoint() {
        persistSayHiTraversalCheckpoint()
    }

    override fun hasRestorableReceivedGreetingsCheckpoint(): Boolean =
        sayHiTraversalState.hasRestorableCheckpoint

    private fun resetSayHiDetailBackReturnTracking() {
        sayHiDetailBackRecoveryWindowStartedAtUptimeMs = 0L
        sayHiDetailBackRecoveryAttempted = false
        sayHiDetailBackSourceWindow = null
        sayHiDetailBackReturnConfirmation.reset()
    }

    private data class SayHiProfileBackInspection(
        val window: WindowRef,
        val dialogHPresent: Boolean,
        val dialogHWindowId: Int,
        val profileIdVerified: Boolean
    )

    /**
     * Confirms the shared Momo chat-detail fingerprint from one fresh active-window tree.
     * Received-greetings and private-message detail pages use the same three UI anchors:
     * chat_user_name, message_ed_msgeditor, and message_btn_openemotes. Activity name,
     * message cards, avatars, and recycler contents are not page prerequisites.
     */
    private fun inspectCurrentChatDetailFingerprint(expectedTitle: String? = null): Pair<WindowRef, MomoChatPageInspection>? {
        val observed = rootAccess.withActiveTargetRoot { root, window ->
            if (window.packageName != targetPackage || window.windowId < 0 ||
                !accessibilityGateway.isWindowCurrent(window)
            ) {
                return@withActiveTargetRoot null
            }
            val inspection = chatComponents.pageInspector.inspectRoot(
                root = AccessibilityNodeInfo.obtain(root),
                targetPackage = targetPackage,
                activityClass = window.activityClass
            )
            window to inspection
        }
        if (observed == null) {
            if (expectedTitle != null) {
                onLog("AI 联系人身份返回聊天页检查：rootWindow=缺失；chat_user_name=缺失；chatTitleText=为空；message_ed_msgeditor=缺失；message_btn_openemotes=缺失；titleMatch=false")
            }
            return null
        }

        val (window, inspection) = observed
        if (expectedTitle != null) {
            val title = extractChatPeerName(inspection.chatTitleText)
            val expected = normalizeIdentityDisplayName(expectedTitle)
            onLog(
                "AI 联系人身份返回聊天页检查：" +
                    "rootWindow=${if (accessibilityGateway.isWindowCurrent(window)) "当前" else "非当前"}；" +
                    "chat_user_name=${if (inspection.hasChatTitleAnchor) "存在" else "缺失"}；" +
                    "chatTitleText=${if (title.isNotBlank()) "非空" else "为空"}；" +
                    "message_ed_msgeditor=${if (inspection.hasMessageComposerAnchor) "存在" else "缺失"}；" +
                    "message_btn_openemotes=${if (inspection.hasMessageEmotesAnchor) "存在" else "缺失"}；" +
                    "titleMatch=${title.isNotBlank() && expected.isNotBlank() && title == expected}"
            )
        }
        if (window.windowId < 0 ||
            !accessibilityGateway.isWindowCurrent(window) ||
            inspection.pageType != MomoChatPageType.CHAT_DETAIL_CANDIDATE ||
            !inspection.hasStableChatDetailFingerprint ||
            inspection.chatTitleText.isNullOrBlank()
        ) {
            return null
        }
        return observed
    }

    private data class ChatActivityBackInspection(
        val window: WindowRef
    )

    /**
     * Dedicated back admission for the verified received-greetings profile flow. It performs a
     * bounded popup-safety inspection of the current profile window and any currently-present
     * dialog.h window, then asks the gateway to revalidate the exact active WindowRef immediately
     * before BACK. A stale dialog event is diagnostic only and never blocks a verified profile.
     */
    private fun requestSayHiProfileBack(): Boolean {
        if (sayHiProfileBackAttempts >= MAX_SAYHI_PROFILE_BACK_ATTEMPTS) {
            onLog("资料页专用 BACK 暂缓：本用户已达到有界安全返回尝试次数；保留页面和任务断点，等待新的页面/窗口事件")
            return false
        }
        val inspection = submitVerifiedProfileBackNavigation() ?: return false
        sayHiProfileBackAttempts++
        sayHiReadOnlyStage = SayHiReadOnlyStage.PROFILE_BACK_PENDING
        sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
        sayHiProfileBackDialogPending = inspection.dialogHPresent
        sayHiProfileBackDialogWindowId = inspection.dialogHWindowId
        sayHiProfileBackWindowId = inspection.window.windowId
        onLog(
            if (inspection.dialogHPresent) {
                "收到招呼身份页面：当前 dialog.h 窗口已完成安全检查并提交 BACK；等待确认其从窗口集合消失，再对资料页执行独立安全返回"
            } else {
                "收到招呼身份页面：资料页专用 BACK 已通过弹层安全检查并提交；等待收到招呼列表验证"
            }
        )
        return true
    }

    /**
     * Shared verified profile return used by Received Greetings and inbox identity capture.
     * Keeps the existing active-window, dialog.h, popup-blocker and WindowRef checks in one path.
     */
    private fun submitVerifiedProfileBackNavigation(
        targetActivityClass: String = MomoSayHiPageContract.LIST_ACTIVITY
    ): SayHiProfileBackInspection? {
        val dialogObservation = latestDialogWindowObservation()
        val inspection = rootAccess.withTargetWindowRoots { windows ->
            if (windows.isEmpty()) return@withTargetWindowRoots null
            val active = windows.firstOrNull { it.active }
                ?: windows.firstOrNull { it.focused }
                ?: return@withTargetWindowRoots null
            if (active.window.packageName != targetPackage || active.window.windowId < 0)
                return@withTargetWindowRoots null
            val profileTree = accessibilityGateway.inspectActiveWindow(
                TreeInspectionRequest(maxNodes = 1_800, maxDepth = 48, maxDurationMs = 1_000L,
                    textCapture = NodeTextCapture.EPHEMERAL)
            ) ?: return@withTargetWindowRoots null
            if (profileTree.window != active.window || !isIdentityProfileActionWindow(profileTree)) {
                onLog("资料页 BACK 拒绝[PROFILE_SOURCE_UNVERIFIED]：当前资料页或窗口证据不足")
                return@withTargetWindowRoots null
            }
            // Use the current tree, not a cached ID or an inferred Activity.
            val profileIdVerified = MomoSayHiPlatformIdentityParser.parse(profileTree.nodes)
                .status == MomoSayHiPlatformIdentityStatus.CONFIRMED

            val dialogRoot = dialogObservation?.let { observation ->
                windows.firstOrNull { it.window.windowId == observation.windowId }
            }
            if (dialogObservation != null && dialogRoot == null) {
                val dialogWindowPresent = rootAccess.isTargetWindowPresent(dialogObservation.windowId)
                if (dialogWindowPresent != false) {
                    onLog(
                        "资料页 BACK 安全取证[DIALOG_H_ROOT_UNAVAILABLE]：" +
                            "observedClass=${dialogObservation.className}；" +
                            "observedWindowId=${dialogObservation.windowId}；" +
                            "windowPresent=$dialogWindowPresent；" +
                            "说明=无法取得当前 dialog.h 根节点或证明窗口已消失；拒绝 BACK"
                    )
                    return@withTargetWindowRoots null
                }
                onLog(
                    "资料页 BACK 安全取证[DIALOG_WINDOW_GONE]：" +
                        "observedClass=${dialogObservation.className}；" +
                        "observedWindowId=${dialogObservation.windowId}；" +
                        "observedAtUptimeMs=${dialogObservation.observedAtUptimeMs}；" +
                        "activeWindowId=${active.window.windowId}；" +
                        "说明=窗口集合已确认该 dialog.h 窗口消失；按当前资料页重新检查"
                )
            }

            val dialogPresent = dialogRoot != null
            val rootsToInspect = buildList {
                add(active)
                if (dialogRoot != null && dialogRoot.window.windowId != active.window.windowId) add(dialogRoot)
            }
            for (candidate in rootsToInspect) {
                val isDialogH = dialogObservation?.windowId == candidate.window.windowId
                val safety = browseComponents.popupInspector.inspectBlockingOverlay(
                    candidate.root,
                    if (isDialogH) dialogObservation?.className else candidate.window.activityClass
                )
                onLog(
                    "资料页 BACK 安全取证[WINDOW]：windowId=${candidate.window.windowId}；" +
                        "active=${candidate.active}；focused=${candidate.focused}；" +
                        "eventClass=${if (isDialogH) dialogObservation?.className else candidate.window.activityClass}；" +
                        "visited=${safety.visitedNodes}；elapsedMs=${safety.elapsedMs}；" +
                        "complete=${safety.complete}；stopReason=${safety.stopReason ?: "none"}；" +
                        "popupCandidates=${safety.popupCandidateCount}；" +
                        "blocking=${safety.blockingReason?.take(120) ?: "none"}"
                )
                if (!safety.complete || !safety.blockingReason.isNullOrBlank()) {
                    onLog("资料页 BACK 安全检查拒绝：活动窗口或当前存在的 dialog.h 窗口未能通过完整无阻断检查")
                    return@withTargetWindowRoots null
                }
            }
            SayHiProfileBackInspection(
                window = active.window,
                dialogHPresent = dialogPresent,
                dialogHWindowId = dialogRoot?.window?.windowId ?: -1,
                profileIdVerified = profileIdVerified && !dialogPresent
            )
        } ?: return null

        if (!accessibilityGateway.isWindowCurrent(inspection.window)) {
            onLog("资料页专用 BACK 拒绝：安全检查后活动窗口或页面代次发生变化")
            return null
        }
        val accepted = accessibilityGateway.performVerifiedNavigationBack(
            inspection.window,
            inspection.window.activityClass,
            targetActivityClass.takeUnless { inspection.dialogHPresent },
            sourceIdentityVerified = inspection.profileIdVerified
        )
        onLog("资料页 BACK 提交结果：accepted=$accepted；sourceEvidence=" +
            if (inspection.profileIdVerified) "PROFILE_ID" else "ACTIVITY")
        if (!accepted) return null
        return inspection
    }

    private fun selectedContactId(): String? =
        sayHiSelectedContactIdentity?.contactId?.takeIf(String::isNotBlank)

    private fun clearSayHiSelectedGreeting() {
        sayHiSelectedGreeting = null
        sayHiSelectedContactIdentity = null
        sayHiSelectedIdentityStatus = MomoSayHiPlatformIdentityStatus.MISSING
        sayHiProfileReadAttempts = 0
        sayHiProfileScrollAttempted = false
        sayHiProfileDeferredRetryGate.reset()
        sayHiProfileScrollFailed = false
        sayHiProfileScrollInFlight = false
        sayHiProfileScrollWindowId = -1
        sayHiProfileScrollRequestId = null
        sayHiProfileScrollSettledAtMs = 0L
        sayHiProfileBackDialogPending = false
        sayHiProfileBackDialogWindowId = -1
        sayHiProfileBackWindowId = -1
        sayHiProfileBackAttempts = 0
        sayHiProfileRowRecoveryAttempts = 0
        clearSayHiRowTapEvidence()
        sayHiReadOnlyStage = SayHiReadOnlyStage.IDLE
        sayHiReadOnlyStageStartedAtMs = 0L
        resetSayHiDetailBackReturnTracking()
    }

    private fun returnFromUnverifiedSayHiDetailNavigation(
        currentActivity: String,
        reason: String = "CHAT_ACTIVITY_NOT_CONFIRMED"
    ): Boolean {
        if (currentActivity.isBlank() || isReceivedGreetingsListStructureVisible() ||
            inspectCurrentChatDetailFingerprint() != null
        ) return false
        val selected = sayHiSelectedGreeting
        if (currentActivity == MomoSayHiPageContract.PROFILE_ACTIVITY) {
            selected?.let { if (!sayHiTraversalState.isDeferredRow(it.rowKey)) deferSayHiRow(it.rowKey, "DETAIL_NAVIGATION_UNVERIFIED") }
            sayHiReadOnlyStage = SayHiReadOnlyStage.WAITING_PROFILE
            val accepted = requestSayHiProfileBack()
            onLog(
                if (accepted) {
                    "收到招呼误入资料页识别[PROFILE_OR_AVATAR_PAGE]：第二阶段行区域点击命中头像/资料入口；" +
                        "已复用资料页弹层安全检查并派发 VERIFIED_NAVIGATION_BACK；等待收到招呼列表确认；不读取资料页、不执行 OCR"
                } else {
                    "收到招呼误入资料页安全返回暂未通过：保留资料页和用户断点；等待新的窗口/弹层快照，不继续点击"
                }
            )
            return true
        }
        // An unknown intermediate Activity is not proof that BACK is safe. Keep the captured row
        // and wait for a structural page fingerprint instead of blindly navigating away.
        onLog(
            "收到招呼详情导航处于未知页面：reason=$reason；Activity=${currentActivity.ifBlank { "未知" }}；" +
                "未确认聊天详情指纹/资料页结构，因此不发送全局 BACK、不执行 OCR，保留事务等待新鲜页面复核"
        )
        return true
    }

    override fun receivedGreetingsCurrentDisplayName(): String? =
        sayHiSelectedGreeting?.displayName?.takeIf(String::isNotBlank)

    override fun receivedGreetingsCurrentContactIdentity(): ContactRowIdentity? =
        sayHiSelectedContactIdentity?.takeIf { it.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED }

    override fun receivedGreetingsCurrentRowKeyHash(): String? = sayHiSelectedGreeting?.rowKey

    override fun receivedGreetingsCurrentIdentityStatus(): String? = sayHiSelectedIdentityStatus.name

    override fun persistReceivedGreetingConfirmedSend(confirmationId: String): Boolean {
        if (confirmationId.isBlank() ||
            sayHiReadOnlyStage != SayHiReadOnlyStage.WAITING_DETAIL ||
            sayHiRowTapFeedbackState != SayHiRowTapFeedbackState.VERIFIED
        ) return false
        val selected = sayHiSelectedGreeting ?: return false
        val verifiedId = selectedContactId()?.takeIf {
            sayHiSelectedIdentityStatus == MomoSayHiPlatformIdentityStatus.CONFIRMED &&
                sayHiSelectedContactIdentity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED
        } ?: return false
        val saved = runCatching {
            sayHiSendReturnJournal.recordConfirmedSend(
                selected.rowKey,
                MomoSayHiIdentityFingerprintPolicy.contactIdHash(verifiedId),
                MomoSayHiIdentityFingerprintPolicy.contactIdHash(confirmationId)
            )
        }.getOrDefault(false)
        if (saved) recordSayHiRunSendReceipt(MomoSayHiIdentityFingerprintPolicy.contactIdHash(confirmationId))
        onLog(
            if (saved) "GREETING_SEND_FACT_PERSISTED：发送证明已与原始招呼行及平台身份哈希绑定"
            else "GREETING_SEND_FACT_REJECTED：发送确认未可靠落盘；禁止推进返回"
        )
        return saved
    }

    override fun completeReceivedGreetingDetailInspection(success: Boolean): Boolean =
        completeReceivedGreetingDetailInspectionInternal(success, "DETAIL_SAMPLE_ARCHIVE_FAILED")

    override fun deferReceivedGreetingAfterModelFailure(): Boolean {
        val selected = sayHiSelectedGreeting ?: return false
        // Never allow an unconfirmed model failure to overwrite an existing send fact.
        val priorSend = runCatching { sayHiSendReturnJournal.findByRow(selected.rowKey) }.getOrNull()
        if (priorSend?.phase == ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED) return false
        return completeReceivedGreetingDetailInspectionInternal(false, "DRAFT_MODEL_FAILURE")
    }

    override fun deferReceivedGreetingAfterUnreadableContent(): Boolean {
        val selected = sayHiSelectedGreeting ?: return false
        // Storage failure or any existing confirmed send fact blocks this pre-send path.
        val priorSend = runCatching { sayHiSendReturnJournal.findByRow(selected.rowKey) }
            .getOrElse { return false }
        if (priorSend != null) return false
        return completeReceivedGreetingDetailInspectionInternal(false, "UNREADABLE_VISUAL_CONTENT")
    }

    override fun deferReceivedGreetingAfterManualReturn(): Boolean {
        val selected = sayHiSelectedGreeting ?: return false
        val priorSend = runCatching { sayHiSendReturnJournal.findByRow(selected.rowKey) }.getOrNull()
        if (priorSend?.phase == ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED) return false
        deferSayHiRow(selected.rowKey, "MANUAL_RETURN_BEFORE_SEND_CONFIRMATION")
        sayHiReadOnlyStage = SayHiReadOnlyStage.IDLE
        sayHiRowTapFeedbackState = SayHiRowTapFeedbackState.NOT_DISPATCHED
        pendingSayHiRowTapTarget = null
        sayHiSelectedGreeting = null
        onLog("收到招呼手动返回[ROW_DEFERRED]：未确认发送记录保留；当前招呼行延期，不计入成功")
        return true
    }

    private fun completeReceivedGreetingDetailInspectionInternal(
        success: Boolean, failureReason: String
    ): Boolean {
        if (sayHiReadOnlyStage != SayHiReadOnlyStage.WAITING_DETAIL ||
            sayHiRowTapFeedbackState != SayHiRowTapFeedbackState.VERIFIED
        ) return false
        val selected = sayHiSelectedGreeting ?: return false
        if (success) {
            val contactHash = selectedContactId()
                ?.let(MomoSayHiIdentityFingerprintPolicy::contactIdHash) ?: return false
            val proof = runCatching { sayHiSendReturnJournal.findByRow(selected.rowKey) }.getOrNull()
            if (proof == null || proof.contactIdHash != contactHash ||
                proof.phase != ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED
            ) {
                onLog("GREETING_RETURN_BLOCKED reason=SEND_FACT_NOT_VERIFIED")
                return false
            }
        }
        val confirmedDetail = inspectCurrentChatDetailFingerprint()
        if (confirmedDetail == null || sayHiGestureInFlight) {
            onLog("收到招呼详情采集完成回退准入拒绝：当前活动窗口未通过统一聊天详情页指纹或仍有手势执行")
            return false
        }
        var dispatchedBackWindow: WindowRef? = null
        val accepted = requestChatActivityBack(
            reason = if (success) "收到招呼发送确认后返回列表" else "收到招呼未发送延期后安全返回列表",
            expectedWindow = confirmedDetail.first,
            onSubmitted = { window -> dispatchedBackWindow = window }
        )
        if (accepted) {
            if (success) {
                // A gesture accepted by Android does not prove navigation completed.
                // The source row stays unprocessed until the list double snapshot succeeds.
                sayHiTraversalState.sayHiPendingVerifiedReturnRow = selected.rowKey
            } else {
                // A safe BACK only closes the detail page; it never proves a reply was sent.
                // Retain the existing deferral behavior for incomplete transactions.
                deferSayHiRow(selected.rowKey, failureReason)
            }
            sayHiReadOnlyStage = SayHiReadOnlyStage.DETAIL_BACK_PENDING
            sayHiReadOnlyStageStartedAtMs = System.currentTimeMillis()
            resetSayHiDetailBackReturnTracking()
            sayHiDetailBackSourceWindow = dispatchedBackWindow
            onLog(
                "收到招呼安全 BACK 已提交：本次聊天记录=${if (success) "已保存，待列表验证后记为已处理" else "未发送完成，已延期且不标记成功"}；" +
                    "ChatActivity 已通过窗口/弹层安全检查派发 VERIFIED_NAVIGATION_BACK；" +
                    "等待 NewSayHiSessionListActivity 与 listview 容器双重确认；发送成功已由上层独立核验"
            )
        } else {
            onLog("收到招呼发送后返回暂未通过 ChatActivity 安全 BACK 检查；保持原会话等待复核")
        }
        return accepted
    }

    override fun isReceivedGreetingsIdentityProfileNavigationActive(snapshot: ChatPageSnapshot): Boolean {
        if (snapshot.packageName != targetPackage ||
            snapshot.pageKind != ChatPageKind.NON_CHAT ||
            sayHiSelectedGreeting == null ||
            sayHiReadOnlyStage !in setOf(
                SayHiReadOnlyStage.AVATAR_TAP_PENDING,
                SayHiReadOnlyStage.WAITING_PROFILE,
                SayHiReadOnlyStage.PROFILE_SCROLL_PENDING,
                SayHiReadOnlyStage.PROFILE_BACK_PENDING
            )
        ) return false
        // Fresh target-window evidence; optional profile presentation nodes are not required.
        return isSayHiProfileSurfacePresent()
    }

    override fun isReceivedGreetingsNavigationAwaitingPageEvidence(snapshot: ChatPageSnapshot): Boolean {
        if (snapshot.packageName != targetPackage ||
            sayHiSelectedGreeting == null || sayHiReadOnlyStageStartedAtMs <= 0L
        ) return false
        when (snapshot.pageKind) {
            ChatPageKind.NON_CHAT, ChatPageKind.UNKNOWN -> Unit
            ChatPageKind.CHAT_DETAIL_CANDIDATE -> {
                // Only a real, current row dispatch may account for a premature chat
                // candidate. Reservation/pre-action delay, an avatar or BACK stage,
                // and a rejected tap must not postpone route recovery here.
                if (sayHiReadOnlyStage !in setOf(
                        SayHiReadOnlyStage.ROW_TAP_PENDING, SayHiReadOnlyStage.WAITING_DETAIL
                    ) || pendingSayHiRowTapSubmittedAtUptimeMs <= 0L ||
                    pendingSayHiRowTapGestureCommandId == null || pendingSayHiRowTapTarget == null ||
                    pendingSayHiRowTapWindowId < 0 ||
                    sayHiRowTapFeedbackState == SayHiRowTapFeedbackState.NOT_DISPATCHED ||
                    sayHiRowTapFeedbackState == SayHiRowTapFeedbackState.REJECTED ||
                    sayHiSelectedIdentityStatus != MomoSayHiPlatformIdentityStatus.CONFIRMED ||
                    sayHiSelectedContactIdentity?.confidence != ContactIdentityConfidence.PLATFORM_ID_VERIFIED
                ) return false
            }
            else -> return false
        }
        // Read the adapter's original transaction state and deadline; never extend either.
        val timeoutMs = when (sayHiReadOnlyStage) {
            SayHiReadOnlyStage.AVATAR_TAP_PENDING -> SAYHI_GESTURE_TERMINAL_TIMEOUT_MS
            SayHiReadOnlyStage.ROW_TAP_PENDING -> {
                if (sayHiRowTapFeedbackState == SayHiRowTapFeedbackState.REJECTED) return false
                SAYHI_GESTURE_TERMINAL_TIMEOUT_MS
            }
            SayHiReadOnlyStage.WAITING_PROFILE -> SAYHI_PROFILE_PAGE_TIMEOUT_MS
            SayHiReadOnlyStage.PROFILE_BACK_PENDING -> {
                if (sayHiProfileBackWindowId < 0) return false
                SAYHI_PROFILE_BACK_TIMEOUT_MS
            }
            SayHiReadOnlyStage.WAITING_DETAIL -> {
                if (sayHiRowTapFeedbackState == SayHiRowTapFeedbackState.REJECTED) return false
                SAYHI_ROW_DETAIL_VERIFY_TIMEOUT_MS
            }
            SayHiReadOnlyStage.DETAIL_BACK_PENDING -> {
                if (sayHiDetailBackSourceWindow == null) return false
                SAYHI_DETAIL_BACK_TIMEOUT_MS
            }
            else -> return false
        }
        val elapsedMs = System.currentTimeMillis() - sayHiReadOnlyStageStartedAtMs
        return elapsedMs >= 0L && elapsedMs < timeoutMs
    }

    override fun isReceivedGreetingsDetailReturnNavigationActive(source: ChatPageSnapshot): Boolean {
        if (sayHiReadOnlyStage != SayHiReadOnlyStage.DETAIL_BACK_PENDING ||
            sayHiSelectedGreeting == null || source.packageName != targetPackage ||
            source.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE
        ) return false
        val backSource = sayHiDetailBackSourceWindow ?: return false
        if (backSource.packageName != targetPackage || backSource.windowId < 0 ||
            backSource.generation <= 0L || source.activityClass != backSource.activityClass ||
            (source.windowGeneration > 0L && source.windowGeneration != backSource.generation)
        ) return false

        // Region refresh may retire locators without leaving the source window. A retained
        // generation or current source locator must still match the accepted BACK receipt.
        val sourceWindows = listOfNotNull(source.messageListTarget?.window, source.inputFieldTarget?.window)
        return (source.windowGeneration == backSource.generation || sourceWindows.isNotEmpty()) &&
            sourceWindows.all { sameMomoWindowGeneration(it, backSource) }
    }

    override fun isReceivedGreetingsWaitingForSafeTargetEvidence(): Boolean =
        sayHiTraversalState.sayHiDeferredSafeEvidenceOnlyWait && sayHiTraversalState.hasExhaustedSafeTargetRows &&
            !sayHiTraversalState.hasRetryableDeferredRows() &&
            sayHiReadOnlyStage == SayHiReadOnlyStage.IDLE

    override fun receivedGreetingsTraversalStatus(): ReceivedGreetingsTraversalStatus {
        val currentActivity = activityClass()
        if (receivedGreetingsEntrySearchController.isActive() || receivedGreetingsEntrySearchGestureInFlight) {
            return ReceivedGreetingsTraversalStatus.NAVIGATING
        }
        if (receivedGreetingsEntrySearchController.isComplete()) return ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND
        if (sayHiReadOnlyStage == SayHiReadOnlyStage.WAITING_DETAIL &&
            sayHiRowTapFeedbackState != SayHiRowTapFeedbackState.REJECTED
        ) {
            // A verified row tap identifies the navigation source; the shared structural
            // fingerprint independently identifies the destination before read-only OCR unlocks.
            if (sayHiRowTapFeedbackState == SayHiRowTapFeedbackState.VERIFIED &&
                inspectCurrentChatDetailFingerprint() != null
            ) {
                return ReceivedGreetingsTraversalStatus.DETAIL_READY
            }
        }
        if (sayHiReadOnlyStage != SayHiReadOnlyStage.IDLE) {
            return ReceivedGreetingsTraversalStatus.NAVIGATING
        }
        if (sayHiNavigationFlowController.shouldAwaitProfileCardActivity(
                currentActivityClass = currentActivity,
                profileCardActivityClass = MomoSayHiPageContract.CARD_ACTIVITY,
                entryTapPending = sayHiNavigationFlowController.receivedGreetingsEntryTapPending
            ) || sayHiNavigationFlowController.isWaitingForReceivedList()
        ) {
            return ReceivedGreetingsTraversalStatus.NAVIGATING
        }
        return when {
            isReceivedGreetingsListStructureVisible() -> if (!sayHiNavigationFlowController.isReceivedListConfirmed()) {
                ReceivedGreetingsTraversalStatus.UNKNOWN
            } else if (sayHiAutonomousBrowseController.isFinished() &&
                !sayHiTraversalState.reanchorToTopPending && !sayHiTraversalState.hasDeferredRows
            ) {
                ReceivedGreetingsTraversalStatus.COMPLETE
            } else {
                ReceivedGreetingsTraversalStatus.SCANNING
            }
            currentActivity == MomoSayHiPageContract.CARD_ACTIVITY -> ReceivedGreetingsTraversalStatus.PROFILE_CARD
            else -> ReceivedGreetingsTraversalStatus.UNKNOWN
        }
    }

    /** Source proof is read-only and uses the existing card fingerprint policy. */
    override fun isReceivedGreetingsCompletionReturnIntermediate(): Boolean {
        if (completionBackStage == CompletionBackStage.NONE) return false
        return inspectSayHiCardListTapTarget() is SayHiCardListTapInspection.Ready
    }

    override fun receivedGreetingsCompletionReturnSourceFingerprint(): String? {
        receivedGreetingsListWindowIdentity()?.let { return it }
        if (completionBackStage == CompletionBackStage.NONE) return null
        val card = inspectSayHiCardListTapTarget() as? SayHiCardListTapInspection.Ready ?: return null
        return MomoWindowPageFingerprint.windowIdentity(card.tree.window, targetPackage, "RECEIVED_GREETINGS_CARD")
    }

    override fun invalidateReceivedGreetingsCompletionReturnEvidence() {
        completionBackRetryGate.invalidateSamples()
    }

    @Synchronized
    override fun returnFromReceivedGreetingsForPriorityCheck(): ReceivedGreetingsBackResult {
        if (sayHiGestureInFlight || receivedGreetingsEntrySearchGestureInFlight) {
            completionBackRetryGate.invalidateSamples()
            onLog("收到的招呼任务返回[GESTURE_IN_FLIGHT]：等待已提交手势终态；未提交BACK")
            return ReceivedGreetingsBackResult.GESTURE_IN_FLIGHT
        }
        // Use one current-window structural proof, not a cached activity or old list target.
        val listWindow = rootAccess.withActiveTargetRoot { root, window ->
            if (window.packageName == targetPackage && window.windowId >= 0 &&
                accessibilityGateway.isWindowCurrent(window) &&
                sayHiAccessibilityInspector.hasReceivedGreetingsListFingerprint(root, targetPackage) &&
                accessibilityGateway.isWindowCurrent(window)
            ) window else null
        }
        val nextStage: CompletionBackStage
        val sourceWindow: WindowRef
        if (listWindow != null) {
            // Traversal exhaustion and deferred rows are progress evidence, not
            // prerequisites for a safe return. An unfinished individual transaction is.
            if (!sayHiNavigationFlowController.isReceivedListConfirmed() ||
                sayHiReadOnlyStage != SayHiReadOnlyStage.IDLE ||
                sayHiSelectedGreeting != null || sayHiTraversalState.sayHiPendingVerifiedReturnRow != null ||
                sayHiNavigationFlowController.receivedGreetingsEntryTapPending || sayHiNavigationFlowController.sayHiCardListTapPending ||
                sessionComponents.inboxIdentity.pendingInboxIdentityCapture != null
            ) return ReceivedGreetingsBackResult.SOURCE_UNVERIFIED
            val noPendingSendReturn = runCatching {
                receivedGreetingsSendReturnRepository.loadAll().none {
                    it.phase == ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED
                }
            }.getOrDefault(false)
            if (!noPendingSendReturn) return ReceivedGreetingsBackResult.SOURCE_UNVERIFIED
            // Persist before submitting BACK, including any deferred rows.
            if (!runCatching { persistSayHiTraversalCheckpoint() }.isSuccess) {
                onLog("收到的招呼任务返回[CHECKPOINT_NOT_SAVED]：未保存遍历进度；不提交BACK")
                return ReceivedGreetingsBackResult.REJECTED
            }
            nextStage = CompletionBackStage.LIST_SUBMITTED
            sourceWindow = listWindow
        } else {
            // Only the verified entry-card host reached after a list BACK permits a second step.
            // Never use an arbitrary profile Activity or an Activity hint as authorization.
            if (completionBackStage == CompletionBackStage.NONE) {
                return ReceivedGreetingsBackResult.SOURCE_UNVERIFIED
            }
            val card = inspectSayHiCardListTapTarget() as? SayHiCardListTapInspection.Ready
                ?: return ReceivedGreetingsBackResult.SOURCE_UNVERIFIED
            nextStage = CompletionBackStage.CARD_SUBMITTED
            sourceWindow = card.tree.window
        }
        if (sourceWindow.packageName != targetPackage || sourceWindow.windowId < 0 ||
            !accessibilityGateway.isWindowCurrent(sourceWindow)
        ) {
            return ReceivedGreetingsBackResult.SOURCE_UNVERIFIED
        }
        if (sayHiGestureInFlight || receivedGreetingsEntrySearchGestureInFlight) {
            return ReceivedGreetingsBackResult.GESTURE_IN_FLIGHT
        }
        val sourceKey = MomoWindowPageFingerprint.windowIdentity(
            sourceWindow, targetPackage, nextStage.name
        ) ?: return ReceivedGreetingsBackResult.SOURCE_UNVERIFIED
        // A different verified forward step (list -> card) is not a retry. A replay
        // of the same step or a return to the list requires the original timeout
        // and two fresh, spaced observations of that exact source.
        val forwardCardStep = completionBackStage == CompletionBackStage.LIST_SUBMITTED &&
            nextStage == CompletionBackStage.CARD_SUBMITTED
        if (completionBackStage != CompletionBackStage.NONE && !forwardCardStep &&
            !completionBackRetryGate.mayRetry(sourceKey, SystemClock.uptimeMillis())
        ) return ReceivedGreetingsBackResult.WAITING_FOR_PAGE_CONFIRMATION
        val accepted = accessibilityGateway.performGlobalAction(GlobalAccessibilityAction.BACK)
        if (!accepted) {
            completionBackRetryGate.invalidateSamples()
            onLog("收到的招呼任务返回[REJECTED]：系统未受理BACK；保留遍历检查点")
            return ReceivedGreetingsBackResult.REJECTED
        }
        completionBackStage = nextStage
        // Stop old list-recovery callbacks while this return owns navigation.
        sayHiSnapshotCoordinator.reset()
        completionBackRetryGate.onSubmitted(SystemClock.uptimeMillis())
        onLog("收到的招呼任务返回[BACK_SUBMITTED]：stage=$nextStage；保留遍历检查点，等待独立页面确认")
        return ReceivedGreetingsBackResult.BACK_SUBMITTED
    }

    @Synchronized
    override fun confirmReceivedGreetingsCompletionReturn(preserveTraversal: Boolean): Boolean {
        // The Runtime has independently confirmed the ordinary inbox and retains the greeting
        // lease. Recheck the target window before retiring the completed adapter transaction.
        // Do not invoke the general chat inspection path here: it can advance a separate
        // identity transaction. Use the same existing root-based page classifier read-only.
        if (sayHiGestureInFlight || receivedGreetingsEntrySearchGestureInFlight ||
            sessionComponents.inboxIdentity.pendingInboxIdentityCapture != null
        ) return false
        val inboxConfirmed = rootAccess.withActiveTargetRoot { root, window ->
            if (window.packageName != targetPackage || window.windowId < 0 ||
                !accessibilityGateway.isWindowCurrent(window) ||
                sayHiAccessibilityInspector.hasReceivedGreetingsListFingerprint(root, targetPackage)
            ) return@withActiveTargetRoot false
            val inspection = chatComponents.pageInspector.inspectRoot(
                root = AccessibilityNodeInfo.obtain(root),
                targetPackage = targetPackage,
                activityClass = window.activityClass.ifBlank { activityClass() }
            )
            inspection.pageType == MomoChatPageType.CHAT_LIST &&
                accessibilityGateway.isWindowCurrent(window)
        } == true
        if (!inboxConfirmed || sayHiGestureInFlight || receivedGreetingsEntrySearchGestureInFlight) {
            return false
        }
        // Returning to the other task retires only window-bound state. Even a
        // completed scan retains its business ledger for the next visit.
        if (!runCatching { sayHiTraversalState.suspendTraversal(preserveTraversal) }.isSuccess) return false
        sayHiRunGenerationGuard.invalidatePreviousRun()
        sayHiNavigationFlowController.receivedGreetingsEntryTapPending = false
        sayHiNavigationFlowController.sayHiCardListTapPending = false
        sayHiNavigationFlowController.reset()
        sayHiAutonomousBrowseController.reset()
        resetReceivedGreetingsEntrySearch()
        resetSayHiListSnapshotState()
        clearSayHiSelectedGreeting()
        sayHiTraversalState.sayHiDeferredSafeEvidenceOnlyWait = false
        sayHiStableCandidateKey = ""
        sayHiStableCandidateTarget = null
        sayHiStableCandidateWindow = null
        completionBackStage = CompletionBackStage.NONE
        completionBackRetryGate.reset()
        onLog("收到的招呼任务返回[INBOX_CONFIRMED]：普通消息列表已复核；preserveTraversal=$preserveTraversal")
        return true
    }

    private fun isSayHiChatNavigationInProgress(): Boolean =
        sayHiReadOnlyStage == SayHiReadOnlyStage.ROW_TAP_PENDING ||
            sayHiReadOnlyStage == SayHiReadOnlyStage.WAITING_DETAIL

    /**
     * Inspects confirmed SayHi pages. Test traversal may scroll only the confirmed list;
     */
    private fun inspectSayHiPageAndAutonomousBrowse(
        allowAutonomousScroll: Boolean = true,
        minimumIntervalMs: Long = 500L,
        forceFresh: Boolean = false
    ): Boolean {
        // A completed session must be acknowledged by the business scheduler before
        // a callback or autonomous list pass can select another greeting.
        if (hasSayHiVerifiedReturnReceipts()) return true
        val activityHint = activityClass() // diagnostic/card-host hint only; list identity is structural
        val listSurfaceVisible = isReceivedGreetingsListStructureVisible()
        val listFlowExpected = sayHiNavigationFlowController.isReceivedListConfirmed() ||
            sayHiNavigationFlowController.isWaitingForReceivedList() ||
            sayHiNavigationFlowController.isManuallyStabilizingReceivedList()
        val listPipelineCandidate = listSurfaceVisible || listFlowExpected
        val cardHostCandidate = activityHint == MomoSayHiPageContract.CARD_ACTIVITY
        // A historical confirmed-list phase survives row navigation. It is not proof
        // that a chat root should be scanned as a list or charged a list retry.
        // A genuine return with the existing list fingerprint remains admissible.
        if (isSayHiChatNavigationInProgress() && !listSurfaceVisible && !cardHostCandidate) return false
        if (!listPipelineCandidate && !cardHostCandidate) return false
        val confirmedList = listSurfaceVisible && sayHiNavigationFlowController.isReceivedListConfirmed()
        if (listPipelineCandidate &&
            sayHiSnapshotCoordinator.isPending && !forceFresh
        ) return true
        val now = System.currentTimeMillis()
        if (!forceFresh && now - lastSayHiInspectionAtMs < minimumIntervalMs) return true
        // Read-only list observations and BACK confirmation must remain available while
        // Activity evidence is missing. Only the task-owned action pass consults READY.
        val listActionReadiness = if (allowAutonomousScroll && confirmedList &&
            (sayHiReadOnlyStage == SayHiReadOnlyStage.IDLE ||
                sayHiReadOnlyStage == SayHiReadOnlyStage.PROFILE_BACK_PENDING)
        ) {
            val readiness = runCatching(inspectReceivedGreetingsReadiness).getOrNull()
            if (readiness == null || readiness.status != TargetAppReadinessStatus.READY ||
                readiness.runMode != RunMode.AI_REPLY ||
                readiness.targetPackage != targetPackage ||
                readiness.foregroundPackage != targetPackage ||
                readiness.activityClass.isBlank() || readiness.windowId < 0 || readiness.pageGeneration < 0L
            ) {
                // Keep business row identity, cursor, and send proof. A previously observed
                // target is not a valid locator after Activity/window recovery.
                sayHiStableCandidateTarget = null
                sayHiStableCandidateWindow = null
                sayHiConfirmedListTarget = null
                lastSayHiInspectionAtMs = now
                // false uses the existing task executor RETRY_REQUIRED / read-only backoff.
                // Do not create another polling loop or reserve any gesture.
                return false
            }
            readiness
        } else null
        var listSnapshotLeaseAcquired = false
        if (listPipelineCandidate) {
            when (val admission = sayHiListSnapshotRecoveryPolicy.tryBeginSnapshot(
                SystemClock.elapsedRealtime()
            )) {
                MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.Acquired -> {
                    listSnapshotLeaseAcquired = true
                    lastSayHiSnapshotGateDiagnostic = ""
                }
                MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.GestureInFlight -> {
                    // Gesture terminal callback owns scheduling the next fresh snapshot.
                    return true
                }
                MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.SnapshotInFlight -> {
                    val now = System.currentTimeMillis()
                    if (lastSayHiSnapshotGateDiagnostic != "SNAPSHOT_READ_IN_FLIGHT" ||
                        now - lastSayHiSnapshotGateDiagnosticAtMs >= 1_500L
                    ) {
                        lastSayHiSnapshotGateDiagnostic = "SNAPSHOT_READ_IN_FLIGHT"
                        lastSayHiSnapshotGateDiagnosticAtMs = now
                        onLog("收到招呼列表快照：已有节点树读取正在进行；合并本次事件，稍后重新获取新快照")
                    }
                    scheduleSayHiListSnapshotInspection(
                        delayMs = 200L,
                        reason = "SNAPSHOT_READ_IN_FLIGHT",
                        retry = false
                    )
                    return true
                }
                is MomoSayHiListSnapshotRecoveryPolicy.SnapshotAdmission.Settling -> {
                    scheduleSayHiListSnapshotInspection(
                        delayMs = admission.remainingMs,
                        reason = "POST_GESTURE_SETTLE_WINDOW",
                        retry = false
                    )
                    return true
                }
            }
        }
        lastSayHiInspectionAtMs = now

        val confirmedTarget = if (confirmedList && !sayHiSnapshotCoordinator.forceFullListSnapshot) {
            sayHiConfirmedListTarget?.takeIf {
                accessibilityGateway.isWindowCurrent(it.window) &&
                    (listActionReadiness == null || listActionReadiness.isReadyForWindow(it.window, RunMode.AI_REPLY))
            }
        } else null
        if (confirmedList && confirmedTarget == null) sayHiSnapshotCoordinator.requestFullListFallback()
        val usedListSubtree = confirmedTarget != null
        val useVisibleWindowFallback = sayHiSnapshotCoordinator.forceFullListSnapshot && !usedListSubtree
        val treeRequest = if (usedListSubtree) {
            TreeInspectionRequest(
                maxNodes = 900,
                maxDepth = 36,
                maxDurationMs = 650L,
                textCapture = NodeTextCapture.EPHEMERAL
            )
        } else {
            TreeInspectionRequest(
                maxNodes = 1_200,
                maxDepth = 48,
                maxDurationMs = 900L,
                allowVisibleWindowFallback = useVisibleWindowFallback,
                textCapture = NodeTextCapture.EPHEMERAL
            )
        }
        val snapshotScopeLabel = when {
            usedListSubtree -> "LISTVIEW_SUBTREE"
            useVisibleWindowFallback -> "ACTIVE_WINDOW_FALLBACK"
            else -> "ACTIVE_WINDOW"
        }
        val tree = try {
            if (confirmedTarget != null) {
                accessibilityGateway.inspectNodeSubtree(confirmedTarget, treeRequest)
            } else if (useVisibleWindowFallback) {
                // A fallback root lookup must search all currently exposed windows for the
                // actual received-greetings list, not stop at the first same-package popup.
                inspectVisibleReceivedGreetingsWindow(treeRequest)
                    ?: accessibilityGateway.inspectActiveWindow(treeRequest)
            } else {
                accessibilityGateway.inspectActiveWindow(treeRequest)
            }
        } finally {
            if (listSnapshotLeaseAcquired) {
                sayHiListSnapshotRecoveryPolicy.onSnapshotReadFinished()
                listSnapshotLeaseAcquired = false
            }
        }
        if (tree == null) {
            if (listPipelineCandidate) {
                val key = confirmedTarget?.window?.let(::sayHiWindowStabilityKey)
                    ?: sayHiConfirmedListTarget?.window?.let(::sayHiWindowStabilityKey)
                    ?: "${targetPackage}|$activityHint|WINDOW_UNAVAILABLE"
                handleIncompleteSayHiListSnapshot(
                    windowKey = key,
                    stopReason = if (usedListSubtree) "LIST_SUBTREE_REACQUIRE_FAILED" else "ACTIVE_WINDOW_ROOT_UNAVAILABLE",
                    visitedNodes = 0,
                    targetedSubtree = usedListSubtree
                )
                return confirmedList
            }
            return false
        }
        if (tree.window.packageName != targetPackage || !accessibilityGateway.isWindowCurrent(tree.window)) return false
        if (tree.truncated) {
            val partialRecorded = sessionComponents.sampleStore.recordNodeTree(tree, MomoNodeTreeCapturePriorityPolicy.classify(tree.window.activityClass))
            if (partialRecorded) {
                onLog(
                    "收到招呼列表不完整快照已归档：scope=$snapshotScopeLabel；" +
                        "visited=${tree.visitedNodeCount}；stopReason=${tree.stopReason ?: "UNKNOWN"}；仅保存结构元数据"
                )
            }
            val reason = tree.stopReason ?: when {
                tree.timeBudgetExceeded -> "TIME_BUDGET"
                tree.depthTruncated -> "DEPTH_BUDGET"
                tree.walkTruncated -> "NODE_BUDGET_OR_CHILD_READ_FAILED"
                else -> "TREE_INCOMPLETE"
            }
            if (listPipelineCandidate) {
                handleIncompleteSayHiListSnapshot(
                    windowKey = sayHiWindowStabilityKey(tree.window),
                    stopReason = reason,
                    visitedNodes = tree.visitedNodeCount,
                    targetedSubtree = usedListSubtree
                )
                return confirmedList
            }
            return false
        }
        // This is the same immutable snapshot used by the page inspector. Persist only its
        // structural metadata; NodeDumpFormatter excludes all text and descriptions.
        val nodeTreeRecorded = sessionComponents.sampleStore.recordNodeTree(tree, MomoNodeTreeCapturePriorityPolicy.classify(tree.window.activityClass))
        if (nodeTreeRecorded) {
            onLog(
                "节点树样本已写入本次采集归档：Activity=${tree.window.activityClass}；" +
                    "范围=$snapshotScopeLabel；" +
                    "节点=${tree.visitedNodeCount}；截断=${tree.truncated}；格式=JSON/TXT/XML"
            )
        }
        if (!usedListSubtree) recordMomoNavigationEvidence(tree)
        // Full-window page identity was confirmed before retaining this exact listview target.
        // A targeted subtree omits layout_content by construction, so revalidate its root/list
        // rows with the subtree policy instead of rerunning the full-page ancestor fingerprint.
        // Do not require Activity equality here: on BACK, the accessibility root can already be
        // the restored received-greetings list while the service's event-derived Activity hint
        // remains ChatActivity. Exact NodeTarget/window-generation continuity and the strict
        // listview + greeting-row subtree fingerprint are the authority for this scoped read.
        val confirmedListSubtree = usedListSubtree && confirmedList &&
            confirmedTarget != null && confirmedTarget.window == tree.window
        val result = sayHiAccessibilityInspector.inspect(
            tree,
            receivedGreetingsListSubtreeConfirmed = confirmedListSubtree
        )
        if (result.kind == MomoSayHiPageKind.UNKNOWN) {
            if (listPipelineCandidate) {
                val reason = result.queryStopReason ?: if (result.queryBudgetExceeded || result.truncated) {
                    "SAYHI_QUERY_INCOMPLETE"
                } else {
                    "LIST_PAGE_IDENTITY_NOT_CONFIRMED"
                }
                handleIncompleteSayHiListSnapshot(
                    windowKey = sayHiWindowStabilityKey(tree.window),
                    stopReason = reason,
                    visitedNodes = result.queryVisitedNodeCount,
                    targetedSubtree = usedListSubtree
                )
                onLog(
                    "收到招呼列表识别未完成：queryBudgetExceeded=${result.queryBudgetExceeded}；" +
                        "queryTruncated=${result.truncated}；queryVisited=${result.queryVisitedNodeCount}；" +
                        "queryElapsed=${result.queryElapsedMs}ms；queryStopReason=${result.queryStopReason ?: "NONE"}"
                )
                return confirmedList
            }
            sayHiAutonomousBrowseController.reset()
            if (result.queryBudgetExceeded || result.truncated) {
                onLog(
                    "收到招呼页面识别暂停：查询预算超限=${result.queryBudgetExceeded}；节点树截断=${tree.truncated}；" +
                        "查询访问节点=${result.queryVisitedNodeCount}；耗时=${result.queryElapsedMs}ms；" +
                        "停止原因=${result.queryStopReason ?: "NONE"}"
                )
            }
            return false
        }
        if (listPipelineCandidate &&
            (result.truncated || result.queryBudgetExceeded)
        ) {
            handleIncompleteSayHiListSnapshot(
                windowKey = sayHiWindowStabilityKey(tree.window),
                stopReason = result.queryStopReason ?: "SAYHI_QUERY_INCOMPLETE",
                visitedNodes = result.queryVisitedNodeCount,
                targetedSubtree = usedListSubtree
            )
            return confirmedList
        }
        if (result.kind == MomoSayHiPageKind.GREETING_PROFILE_CARD) {
            // Never interact with the card: its primary actions are Reject / Accept.
            sayHiAutonomousBrowseController.reset()
            sayHiConfirmedListTarget = null
        }
        if (result.kind == MomoSayHiPageKind.RECEIVED_GREETINGS_LIST) {
            val listContainers = tree.nodes.filter {
                it.visible && it.viewId == MomoSayHiPageContract.LIST_VIEW_ID &&
                    it.scrollable && !it.bounds.isEmpty
            }
            val listContainer = listContainers.singleOrNull()
            val completeListEvidence = !tree.truncated && !result.truncated &&
                !result.queryBudgetExceeded && result.containerFound &&
                result.containerMatchCountExact && !result.containerAmbiguous &&
                listContainer != null
            if (completeListEvidence) {
                // Keep a window-scoped locator to the confirmed RecyclerView. Later post-swipe
                // reads can reacquire only this subtree instead of walking the whole Activity.
                sayHiConfirmedListTarget = listContainer!!.target(tree.window)
                sayHiSnapshotCoordinator.markListSnapshotComplete()
                sayHiListSnapshotRecoveryPolicy.onSnapshotComplete(sayHiWindowStabilityKey(tree.window))
            }
            if (sayHiNavigationFlowController.isWaitingForReceivedList()) {
                if (completeListEvidence) {
                    val listSignature = listOf(
                        listContainer!!.path.childIndices,
                        listContainer.bounds,
                        listContainer.childCount,
                        listContainer.scrollable
                    ).joinToString("|")
                    val confirmed = sayHiNavigationFlowController.observeStableReceivedList(
                        windowKey = sayHiWindowStabilityKey(tree),
                        listSignature = listSignature,
                        capturedAtMs = tree.capturedAtEpochMs
                    )
                    if (confirmed) {
                        onLog("收到的招呼列表：收到资料卡‘列表’点击后的连续稳定快照；外层 listview 唯一、可滚动且边界完整")
                    } else {
                        onLog("收到的招呼列表：已确认资料卡‘列表’点击后的列表容器；等待第二份稳定快照")
                    }
                } else {
                    sayHiNavigationFlowController.resetListStability()
                    onLog(
                        "收到的招呼列表：页面仍在加载或列表容器证据不完整；暂不开始遍历；" +
                            "外层 listview 候选=${listContainers.size}；容器完整=${completeListEvidence}"
                    )
                }
            } else if (sayHiNavigationFlowController.manualReceivedGreetingsArrivalProbeActive &&
                (sayHiNavigationFlowController.phase == MomoSayHiNavigationPhase.IDLE ||
                    sayHiNavigationFlowController.isManuallyStabilizingReceivedList())
            ) {
                if (completeListEvidence) {
                    val listSignature = listOf(
                        listContainer!!.path.childIndices,
                        listContainer.bounds,
                        listContainer.childCount,
                        listContainer.scrollable
                    ).joinToString("|")
                    val confirmed = sayHiNavigationFlowController.observeStableManuallyOpenedReceivedList(
                        windowKey = sayHiWindowStabilityKey(tree),
                        listSignature = listSignature,
                        capturedAtMs = tree.capturedAtEpochMs
                    )
                    onLog(
                        if (confirmed) {
                            "收到的招呼人工页面监听[CONFIRMED]：现有页面指纹、完整 listview 和连续稳定快照均已通过"
                        } else {
                            "收到的招呼人工页面监听[STABILIZING]：现有页面指纹和完整 listview 已匹配；等待下一份间隔快照"
                        }
                    )
                } else {
                    sayHiNavigationFlowController.resetManualListStability()
                    onLog(
                        "收到的招呼人工页面监听[WAITING]：列表容器证据尚不完整；不执行点击或滚动；" +
                            "外层 listview 候选=${listContainers.size}；容器完整=${completeListEvidence}"
                    )
                }
            } else if (sayHiNavigationFlowController.isReceivedListConfirmed() && !completeListEvidence) {
                handleIncompleteSayHiListSnapshot(
                    windowKey = sayHiWindowStabilityKey(tree.window),
                    stopReason = "LIST_CONTAINER_EVIDENCE_INCOMPLETE",
                    visitedNodes = tree.visitedNodeCount,
                    targetedSubtree = usedListSubtree
                )
            }
        }
        // Recovery polls are intentionally read-only. The snapshot coordinator calls
        // this path with allowAutonomousScroll=false: it must still examine exhausted
        // rows and arrange the next read; no gesture is ever issued by the probe.
        if (!allowAutonomousScroll &&
            result.kind == MomoSayHiPageKind.RECEIVED_GREETINGS_LIST &&
            sayHiNavigationFlowController.isReceivedListConfirmed() &&
            !result.truncated && !result.queryBudgetExceeded && !tree.truncated &&
            result.containerFound && result.containerMatchCountExact &&
            !result.containerAmbiguous && sayHiTraversalState.hasExhaustedSafeTargetRows &&
            sayHiReadOnlyStage == SayHiReadOnlyStage.IDLE &&
            !sayHiGestureInFlight && accessibilityGateway.isWindowCurrent(tree.window)
        ) {
            val provenSends = runCatching {
                receivedGreetingsSendReturnRepository.loadAll()
            }.getOrNull()
            if (provenSends != null) {
                val pendingProof = inspectExhaustedSayHiSafeTargetEvidence(
                    tree, result,
                    result.listRows.filter { it.unread.status == EvidenceStatus.CONFIRMED && it.unread.value == true },
                    provenSends.mapTo(HashSet()) { it.rowKeyHash }
                )
                if (!pendingProof) {
                    scheduleSayHiListSnapshotInspection(
                        delayMs = 30_000L,
                        reason = "DEFERRED_SAFE_TARGET_READ_ONLY_POLL",
                        retry = false
                    )
                }
            } else {
                onLog("GREETING_DEFERRED_RECOVERY_JOURNAL_UNAVAILABLE：保留安全点击失败断点，不重试点击")
            }
        }
        var greetingRowActionHandled = false
        if (allowAutonomousScroll && result.kind == MomoSayHiPageKind.RECEIVED_GREETINGS_LIST &&
            sayHiNavigationFlowController.isReceivedListConfirmed()
        ) {
            if (sayHiReadOnlyStage == SayHiReadOnlyStage.IDLE ||
                sayHiReadOnlyStage == SayHiReadOnlyStage.PROFILE_BACK_PENDING
            ) {
                // READY must match the freshly acquired tree, not the old list locator.
                // A transition during preparation restarts the existing observation path.
                if (listActionReadiness?.isReadyForWindow(tree.window, RunMode.AI_REPLY) != true ||
                    !accessibilityGateway.isWindowCurrent(tree.window)
                ) {
                    sayHiStableCandidateTarget = null
                    sayHiStableCandidateWindow = null
                    sayHiConfirmedListTarget = null
                    return false
                }
            }
            if (sayHiReadOnlyStage == SayHiReadOnlyStage.IDLE &&
                !tree.truncated && !result.truncated && !result.queryBudgetExceeded &&
                result.containerFound && result.containerMatchCountExact && !result.containerAmbiguous &&
                !sayHiGestureInFlight && accessibilityGateway.isWindowCurrent(tree.window)
            ) {
                val resumed = runCatching {
                    sayHiTraversalState.observeVerifiedViewport(result.listRows.map(MomoSayHiIdentityFingerprintPolicy::rowKey))
                }.getOrElse {
                    onLog("收到招呼检查点恢复[CHECKPOINT_WRITE_FAILED]：等待新鲜快照，不推进遍历")
                    scheduleSayHiListSnapshotInspection(500L, "CHECKPOINT_WRITE_FAILED", retry = false)
                    return false
                }
                if (resumed == MomoSayHiTraversalState.ViewportResume.REANCHOR_TO_TOP) {
                    onLog("收到招呼检查点恢复[REANCHOR]：原视口未证明或仍在重新定位；保留业务记录，使用新鲜列表继续")
                }
            }
            greetingRowActionHandled = processReceivedGreetingRows(tree, result)
            if (!greetingRowActionHandled) {
                // Empty visible rows are not by themselves proof of completion. Route them
                // through the same stable-boundary / scroll-progress checks as populated pages.
                dispatchAutonomousSayHiListScroll(tree, result)
            }
        }
        val archived = sessionComponents.sampleStore.persistSayHiInspection(result)
        if (archived) {
            onLog(
                "收到招呼页面结构化样本已写入本地归档：页面=${result.kind}；" +
                    "顶部推荐头像已排除=${result.excludedRecommendationCardCount}；" +
                    "下方纵向全部招呼行=${result.listRows.size}；" +
                    "资料卡字段=${result.profile?.nodeTextFields?.size ?: 0}"
            )
        }
        val profile = result.profile
        val signature = listOf(
            result.kind.name,
            result.containerFound,
            result.containerMatchCount,
            result.containerMatchCountExact,
            result.containerAmbiguous,
            result.containerResolutionStrategy,
            result.containerNodeCount,
            result.textNodeCount,
            result.excludedRecommendationCardCount,
            result.listRows.map { it.rowPath },
            result.listRows.map { listOf(
                it.displayName.status, it.age.status, it.age.value, it.gender.status, it.gender.value,
                it.distanceText.status, it.distanceKm.value, it.online.status, it.online.value,
                it.greetingText.status, it.timestampText.status,
                it.unread.status, it.unread.value, it.hasAvatarNode,
                it.avatarTapTarget?.path, it.avatarTapTarget?.targetBounds,
                it.rowTapTarget?.path, it.rowTapTarget?.targetBounds
            ) },
            result.truncated,
            profile?.displayName?.status,
            profile?.age?.status,
            profile?.gender?.status,
            profile?.distanceKm?.status,
            profile?.online?.status,
            profile?.biography?.status,
            profile?.displayName?.value,
            profile?.age?.value,
            profile?.gender?.value,
            profile?.distanceKm?.value,
            profile?.online?.value,
            profile?.biography?.value,
            profile?.nodeTextFields?.mapValues { (_, field) ->
                field.evidence.status to field.candidateCount
            }
        ).joinToString("|")
        if (signature == lastSayHiInspectionSignature) return true
        lastSayHiInspectionSignature = signature
        val nameStatus = profile?.displayName?.status?.name ?: "N/A"
        val ageStatus = profile?.age?.status?.name ?: "N/A"
        val genderStatus = profile?.gender?.status?.name ?: "N/A"
        val distanceStatus = profile?.distanceKm?.status?.name ?: "N/A"
        val onlineStatus = profile?.online?.status?.name ?: "N/A"
        val biographyStatus = profile?.biography?.status?.name ?: "N/A"
        onLog(
            "陌陌收到招呼任务观察：页面=${result.kind}；Activity=${result.activityClass}; " +
                "顶部推荐头像已排除=${result.excludedRecommendationCardCount}；下方纵向招呼行=${result.listRows.size}； " +
                "扫描节点=${result.nodeCount}；可见节点=${result.visibleNodeCount}；" +
                "容器命中=${result.containerFound}；容器候选=${result.containerMatchCount}；" +
                "候选数量精确=${result.containerMatchCountExact}；原始候选冲突=${result.containerAmbiguous}；" +
                "容器消歧策略=${result.containerResolutionStrategy}；" +
                "查询预算超限=${result.queryBudgetExceeded}；查询访问节点=${result.queryVisitedNodeCount}；" +
                "查询耗时=${result.queryElapsedMs}ms；查询停止原因=${result.queryStopReason ?: "none"}；" +
                "容器节点=${result.containerNodeCount}；" +
                "文本节点=${result.textNodeCount}；列表候选行=${result.listRows.size}；" +
                "列表姓名可读=${result.listRows.count { it.displayName.status == EvidenceStatus.CONFIRMED }}；" +
                "列表年龄可读=${result.listRows.count { it.age.status == EvidenceStatus.CONFIRMED }}；" +
                "列表性别可读=${result.listRows.count { it.gender.status == EvidenceStatus.CONFIRMED }}；" +
                "列表距离可读=${result.listRows.count { it.distanceKm.status == EvidenceStatus.CONFIRMED }}；" +
                "列表在线状态可读=${result.listRows.count { it.online.status == EvidenceStatus.CONFIRMED }}；" +
                "列表招呼内容可读=${result.listRows.count { it.greetingText.status == EvidenceStatus.CONFIRMED }}；" +
                "列表时间可读=${result.listRows.count { it.timestampText.status == EvidenceStatus.CONFIRMED }}；" +
                "列表未读=${result.listRows.count { it.unread.value == true }}；" +
                "列表无未读标记=${result.listRows.count { it.unread.value == false && it.unread.status == EvidenceStatus.CONFIRMED }}；" +
                "列表未读未知=${result.listRows.count { it.unread.status != EvidenceStatus.CONFIRMED }}；" +
                "截断=${result.truncated}；" +
                "姓名=$nameStatus；年龄=$ageStatus；性别=$genderStatus；距离=$distanceStatus；" +
                "在线=$onlineStatus；简介=$biographyStatus；" +
                "资料卡节点字段状态=${profile?.nodeTextFields.orEmpty().entries.joinToString(",") { (id, field) ->
                    "$id:${field.evidence.status.name}(候选=${field.candidateCount})"
                }}；" +
                "说明=${result.diagnostic}"
        )
        return true
    }

    /**
     * Autonomously browses only the outer vertically scrolling listview proven by the device trace.
     * The nested card_list recommendation carousel is never used as the scroll target.
     */
    private fun completeSayHiTopReanchor(): Boolean = runCatching {
        sayHiTraversalState.completeTopReanchor()
    }.getOrElse {
        onLog("收到招呼检查点恢复[REANCHOR_CHECKPOINT_WRITE_FAILED]：保留重新定位状态，等待保存")
        scheduleSayHiListSnapshotInspection(500L, "REANCHOR_CHECKPOINT_WRITE_FAILED", retry = false)
        false
    }

    private fun dispatchAutonomousSayHiListScroll(
        tree: com.freedom.core.accessibility.api.AccessibilityTreeSnapshot,
        inspection: MomoSayHiPageInspection
    ) {
        if (sayHiGestureInFlight) return
        val visibleList = tree.nodes.firstOrNull {
            it.visible && it.viewId == MomoSayHiPageContract.LIST_VIEW_ID
        } ?: run {
            onLog("收到招呼自主浏览暂停：未找到可见且可滚动的外层 listview 容器")
            return
        }
        if (inspection.truncated || inspection.queryBudgetExceeded || !inspection.containerFound ||
            !inspection.containerMatchCountExact || inspection.containerAmbiguous
        ) {
            onLog("收到招呼自主浏览暂停：列表容器证据不完整或有歧义，不判定遍历完成")
            return
        }
        val list = visibleList
        if (list.bounds.isEmpty) {
            onLog("收到招呼自主浏览暂缓：外层 listview 边界无效；不判定遍历完成")
            scheduleSayHiListSnapshotInspection(500L, "LIST_BOUNDS_INVALID", retry = false)
            return
        }
        val rowSignature = inspection.listRows.joinToString("|") { row ->
            "${MomoSayHiIdentityFingerprintPolicy.rowKey(row)}@${row.bounds.top},${row.bounds.bottom}"
        }
        val signature = if (rowSignature.isNotBlank()) rowSignature else
            "EMPTY:${tree.window.windowId}:${list.bounds.left},${list.bounds.top},${list.bounds.right},${list.bounds.bottom}:${list.childCount}"
        if (!visibleList.scrollable) {
            val boundaryConfirmed = sayHiAutonomousBrowseController.observeNonScrollableBoundary(
                signature = signature,
                capturedAtMs = tree.capturedAtEpochMs
            )
            if (!boundaryConfirmed) {
                onLog("收到招呼列表边界复核：外层 listview 暂不可滚动；等待第二份间隔快照，不报告完成")
                scheduleSayHiListSnapshotInspection(350L, "NON_SCROLLABLE_BOUNDARY_CONFIRMATION", retry = false)
                return
            }
            if (sayHiTraversalState.reanchorToTopPending) {
                if (!completeSayHiTopReanchor()) return
                // A verified non-scrollable list is already at both boundaries.
                sayHiAutonomousBrowseController.reset()
                scheduleSayHiListSnapshotInspection(350L, "REANCHOR_TOP_CONFIRMED", retry = false)
                return
            }
            if (sayHiTraversalState.hasDeferredRows) {
                if (hasRetryableSayHiDeferredRows()) {
                    onLog("收到招呼自主浏览暂未完成：已确认当前 listview 不可滚动，但仍有 ${sayHiTraversalState.deferredRowCount} 个用户待恢复；保留断点并等待回访，不报告完成")
                    scheduleSayHiListSnapshotInspection(2_000L, "DEFERRED_ROWS_WAIT_FOR_FRESH_LIST", retry = false)
                } else {
                    sayHiTraversalState.sayHiDeferredSafeEvidenceOnlyWait = true
                    onLog("收到招呼安全点击已耗尽[READ_ONLY_WAIT]：${sayHiTraversalState.deferredRowCount} 个用户等待新的稳定安全区域证据；不直接重试点击、不报告完成")
                    scheduleSayHiListSnapshotInspection(
                        delayMs = 30_000L,
                        reason = "DEFERRED_SAFE_TARGET_READ_ONLY_POLL",
                        retry = false
                    )
                }
                return
            }
            onLog("收到招呼自主浏览完成：外层 listview 连续两份完整、间隔快照均确认不可滚动，且不存在待恢复用户")
            return
        }
        if (sayHiAutonomousBrowseController.isFinished() && sayHiTraversalState.reanchorToTopPending) {
            if (!completeSayHiTopReanchor()) return
            sayHiAutonomousBrowseController.reset()
            onLog("收到招呼检查点恢复[TOP_REANCHORED]：新鲜边界已确认，继续原业务未处理记录")
        }
        if (sayHiAutonomousBrowseController.isFinished() && sayHiTraversalState.hasDeferredRows) {
            if (!hasRetryableSayHiDeferredRows()) {
                sayHiTraversalState.sayHiDeferredSafeEvidenceOnlyWait = true
                onLog("收到招呼安全点击已耗尽[READ_ONLY_WAIT]：保留延期行和失败证据；30秒后观察页面变化，不直接清空失败次数")
                scheduleSayHiListSnapshotInspection(
                    delayMs = 30_000L,
                    reason = "DEFERRED_SAFE_TARGET_READ_ONLY_POLL",
                    retry = false
                )
                return
            }
            when {
                sayHiTraversalState.deferredReturnToTopActive -> {
                    // The reverse sweep has reached the top candidate boundary. Begin one
                    // forward pass so previously deferred rows can be retried on fresh rows.
                    sayHiTraversalState.setDeferredReturnToTopActive(false)
                    sayHiTraversalState.setDeferredReplayStarted(true)
                    sayHiAutonomousBrowseController.reset()
                    persistSayHiTraversalCheckpoint()
                    onLog("收到招呼待恢复用户：反向回访已到达稳定边界；开始一次正向复核遍历")
                }
                !sayHiTraversalState.deferredReplayStarted -> {
                    sayHiTraversalState.setDeferredReturnToTopActive(true)
                    sayHiAutonomousBrowseController.reset()
                    persistSayHiTraversalCheckpoint()
                    onLog("收到招呼待恢复用户：首次列表边界已确认但仍有待恢复用户；启动一次反向回访，不报告完成")
                }
                else -> {
                    // A completed reverse+forward replay is not permission to abandon deferred
                    // rows. Start another bounded-rate recovery cycle; per-row attempts remain
                    // at the capped backoff stage and each row remains eligible for retry.
                    sayHiTraversalState.setDeferredReturnToTopActive(true)
                    sayHiTraversalState.setDeferredReplayStarted(false)
                    sayHiAutonomousBrowseController.reset()
                    persistSayHiTraversalCheckpoint()
                    onLog("收到招呼自主浏览未完成：正向复核后仍有 ${sayHiTraversalState.deferredRowCount} 个用户待恢复；保留断点并安排下一轮反向/正向回访，不报告 COMPLETE")
                    scheduleSayHiListSnapshotInspection(
                        delayMs = 5_000L,
                        reason = "DEFERRED_ROWS_NEXT_RECOVERY_CYCLE",
                        retry = false
                    )
                    return
                }
            }
        }
        val now = System.currentTimeMillis()
        if (!sayHiAutonomousBrowseController.isFinished() || hasRetryableSayHiDeferredRows()) {
            sayHiTraversalState.sayHiDeferredSafeEvidenceOnlyWait = false
        }
        if (!sayHiAutonomousBrowseController.shouldScroll(signature, now, list.scrollable)) {
            if (sayHiAutonomousBrowseController.isFinished()) {
                if (!sayHiTraversalState.hasDeferredRows) {
                    onLog("收到招呼自主浏览完成：连续页面内容无变化，停止继续滚动")
                } else {
                    onLog("收到招呼自主浏览未完成：列表边界候选已达到，但仍有 ${sayHiTraversalState.deferredRowCount} 个用户处于待恢复状态；不报告 COMPLETE，保留行键和重试记录")
                }
            }
            return
        }
        if (!accessibilityGateway.isWindowCurrent(tree.window)) {
            sayHiAutonomousBrowseController.onGestureCompleted(false)
            onLog("收到招呼自主浏览取消：执行前窗口代次已变化")
            return
        }
        // Keep the original resource-metrics geometry source for the list viewport. The gateway
        // snapshot is captured separately and must describe the same physical coordinate space.
        val metrics = context.resources.displayMetrics
        val coordinateMetrics = accessibilityGateway.displayMetrics()
        if (metrics.widthPixels != coordinateMetrics.widthPixels ||
            metrics.heightPixels != coordinateMetrics.heightPixels ||
            metrics.density != coordinateMetrics.density
        ) {
            sayHiAutonomousBrowseController.onGestureCompleted(false)
            onLog("收到招呼自主浏览暂停：列表几何坐标与无障碍显示快照不一致")
            return
        }
        val rawListBounds = list.bounds
        val visibleListBounds = ScreenRect(
            left = maxOf(rawListBounds.left, 0),
            top = maxOf(rawListBounds.top, 0),
            right = minOf(rawListBounds.right, metrics.widthPixels),
            bottom = minOf(rawListBounds.bottom, metrics.heightPixels)
        )
        val executionContext = gesturePolicy.captureContext(
            tree.window,
            coordinateMetrics.toGestureCoordinateContext()
        ) ?: run {
            sayHiAutonomousBrowseController.onGestureDeferred()
            onLog("收到招呼自主浏览暂缓：列表坐标上下文已变化；未派发滑动，等待重新获取列表快照")
            scheduleSayHiListSnapshotInspection(
                delayMs = 350L,
                reason = "LIST_SCROLL_COORDINATE_CONTEXT_STALE",
                retry = false
            )
            return
        }
        val request = GestureIntentRequest(
            id = "momo-sayhi-list-scroll-${System.nanoTime()}",
            intent = GestureIntent.VerticalSwipe(
                viewport = visibleListBounds,
                direction = if (sayHiTraversalState.isReverseTraversal) VerticalSwipeDirection.DOWN else VerticalSwipeDirection.UP,
                durationMs = 520L
            ),
            description = "陌陌收到招呼列表安全上滑",
            executionContext = executionContext
        )
        if (!sayHiListSnapshotRecoveryPolicy.tryBeginGesture(SystemClock.elapsedRealtime())) {
            // The list gate refused before dispatch; this is a deferral, not a failed platform
            // gesture. Keep the browse failure budget untouched and retry after the gate clears.
            sayHiAutonomousBrowseController.onGestureDeferred()
            val settleRemainingMs = sayHiListSnapshotRecoveryPolicy.remainingSettleMs(
                SystemClock.elapsedRealtime()
            ).coerceAtLeast(200L)
            onLog("收到招呼自主浏览暂缓：节点树读取或手势稳定窗口仍占用；等待=${settleRemainingMs}ms")
            scheduleSayHiListSnapshotInspection(
                delayMs = settleRemainingMs,
                reason = "SNAPSHOT_OR_SETTLE_GATE_BUSY",
                retry = false
            )
            return
        }
        sayHiNonSearchGestureInFlight = true
        val callbackGeneration = sayHiRunGenerationGuard.capture()
        val submission = try {
            gesturePolicy.execute(request, GestureExecutionListener { result ->
                if (!sayHiRunGenerationGuard.isCurrent(callbackGeneration)) return@GestureExecutionListener
                sayHiNonSearchGestureInFlight = false
                if (result.status == GestureExecutionStatus.DEFERRED) {
                    sayHiAutonomousBrowseController.onGestureDeferred()
                    sayHiListSnapshotRecoveryPolicy.onGestureDeferred()
                    onLog("收到招呼自主浏览[DEFERRED]：平台未派发滑动；不记失败、不开启布局静默期，重新获取列表快照")
                    scheduleSayHiListSnapshotInspection(
                        delayMs = 500L,
                        reason = "LIST_SCROLL_CALLBACK_DEFERRED",
                        retry = false
                    )
                } else {
                    val completed = result.status == GestureExecutionStatus.COMPLETED
                    sayHiAutonomousBrowseController.onGestureCompleted(completed)
                    val settleDelayMs = sayHiListSnapshotRecoveryPolicy.onGestureFinished(
                        SystemClock.elapsedRealtime()
                    )
                    onLog(
                        if (completed) "收到招呼自主浏览：外层 listview 上滑手势完成；等待=${settleDelayMs}ms 后采集快照"
                        else "收到招呼自主浏览：协调器手势未完成；status=${result.status}；reason=${result.reason}"
                    )
                    scheduleSayHiListSnapshotInspection(
                        delayMs = settleDelayMs,
                        reason = "GESTURE_TERMINAL_${result.status}",
                        retry = false
                    )
                }
            })
        } catch (failure: Throwable) {
            sayHiNonSearchGestureInFlight = false
            sayHiAutonomousBrowseController.onGestureCompleted(false)
            val settleDelayMs = sayHiListSnapshotRecoveryPolicy.onGestureFinished(
                SystemClock.elapsedRealtime()
            )
            onLog(
                "收到招呼自主浏览：手势协调器抛出异常；type=${failure.javaClass.simpleName}；" +
                    "释放手势状态并等待=${settleDelayMs}ms 后重新采集"
            )
            scheduleSayHiListSnapshotInspection(
                delayMs = settleDelayMs,
                reason = "GESTURE_EXECUTION_EXCEPTION",
                retry = false
            )
            return
        }
        if (submission == GestureSubmission.DEFERRED_NOT_READY) {
            sayHiNonSearchGestureInFlight = false
            sayHiAutonomousBrowseController.onGestureDeferred()
            sayHiListSnapshotRecoveryPolicy.onGestureDeferred()
            scheduleSayHiListSnapshotInspection(
                delayMs = 500L,
                reason = "LIST_SCROLL_DEFERRED_NOT_READY",
                retry = false
            )
            onLog("收到招呼自主浏览[DEFERRED_NOT_READY]：弹窗/窗口准入暂未放行；未派发滑动、不计失败、不消耗滚动机会；等待新快照")
        } else if (submission != GestureSubmission.ACCEPTED) {
            sayHiNonSearchGestureInFlight = false
            sayHiAutonomousBrowseController.onGestureCompleted(false)
            val settleDelayMs = sayHiListSnapshotRecoveryPolicy.onGestureFinished(
                SystemClock.elapsedRealtime()
            )
            scheduleSayHiListSnapshotInspection(
                delayMs = settleDelayMs,
                reason = "GESTURE_SUBMISSION_REJECTED",
                retry = false
            )
            onLog("收到招呼自主浏览：统一手势协调器拒绝外层 listview 上滑手势")
        } else {
            onLog("收到招呼自主浏览：已向外层 listview 提交上滑；不触碰用户行及资料卡按钮")
        }
    }

    /**
     * Enters/refreshes the Momo message work area through the exact navigation resource ID.
     * The target is reacquired twice and dispatched only through the unified gesture executor.
     */
    override fun supportsVerifiedMessageWorkAreaNavigation(): Boolean = true

    override fun ensureMessageWorkArea(): Boolean = ensureMessageWorkArea { }

    override fun ensureMessageWorkArea(
        onGestureOutcome: (MessageWorkAreaNavigationOutcome) -> Unit
    ): Boolean {
        fun captureSnapshot(
            expectedWindow: WindowRef? = null,
            expectedWindowId: Int? = null
        ): AccessibilityTreeSnapshot? {
            val tree = accessibilityGateway.inspectActiveWindow(
                TreeInspectionRequest(maxNodes = NAVIGATION_EVIDENCE_MAX_NODES, textCapture = NodeTextCapture.NONE)
            ) ?: return null
            if (tree.truncated || tree.window.packageName != targetPackage ||
                tree.window.windowId < 0 || tree.windowId != tree.window.windowId ||
                !accessibilityGateway.isWindowCurrent(tree.window)
            ) return null
            if (expectedWindow != null && tree.window != expectedWindow) return null
            if (expectedWindowId != null && tree.windowId != expectedWindowId) return null
            return tree
        }

        // First accept any complete, page-local message-workspace fingerprint. This allows the
        // top Message tab or appbar actions + session list to confirm the workspace when a popup
        // temporarily hides the bottom navigation locator. No tap is authorized by this proof.
        val firstPage = captureSnapshot() ?: run {
            onLog("消息工作区域等待：当前活动窗口快照暂不可用；保留启动导航意图并重新采集")
            return false
        }
        if (MomoMessageTabAnchorResolver.isMessageWorkArea(firstPage.nodes, targetPackage)) {
            val secondPage = captureSnapshot(firstPage.window, firstPage.windowId)
            if (secondPage != null &&
                MomoMessageTabAnchorResolver.isMessageWorkArea(secondPage.nodes, targetPackage) &&
                accessibilityGateway.isWindowCurrent(secondPage.window)
            ) {
                onLog(
                    "消息工作区域[ALTERNATIVE_FINGERPRINT_CONFIRMED]：同一窗口两份新鲜快照中，" +
                        "同页独立指纹路径成立；不要求底部导航定位器同时可见"
                )
                return true
            }
            onLog("消息工作区域等待：页面身份路径在连续快照间暂未稳定；保留导航意图后重试")
            return false
        }

        fun captureTarget(
            expectedWindow: WindowRef? = null,
            expectedWindowId: Int? = null
        ): Pair<AccessibilityTreeSnapshot, MomoMessageTabAnchor>? {
            val tree = captureSnapshot(expectedWindow, expectedWindowId) ?: return null
            val anchor = MomoMessageTabAnchorResolver.resolve(tree.nodes, targetPackage) ?: return null
            return tree to anchor
        }

        val first = captureTarget() ?: run {
            onLog(
                "消息工作区域入口等待：既未确认消息工作区备用指纹，也暂时无法定位底部消息标签；" +
                    "等待遮挡消失后重新扫描，不执行猜测性点击"
            )
            return false
        }
        val latest = captureTarget(first.first.window, first.first.windowId) ?: run {
            onLog("消息工作区域入口等待：点击前第二份快照暂不可用；保留导航意图并重试")
            return false
        }
        val firstAnchor = first.second
        val latestAnchor = latest.second
        if (latestAnchor.clickableTab.path != firstAnchor.clickableTab.path ||
            latestAnchor.clickableTab.bounds != firstAnchor.clickableTab.bounds ||
            latestAnchor.bubble.path != firstAnchor.bubble.path ||
            latestAnchor.bubble.bounds != firstAnchor.bubble.bounds ||
            latestAnchor.isSelected != firstAnchor.isSelected ||
            !accessibilityGateway.isWindowCurrent(latest.first.window)
        ) {
            onLog("消息工作区域入口等待：两次新鲜快照中的消息标签层级、状态或边界发生变化；丢弃旧坐标并重试")
            return false
        }
        if (latestAnchor.isSelected) {
            if (!MomoMessageTabAnchorResolver.isMessageWorkArea(latest.first.nodes, targetPackage)) {
                onLog("消息工作区域入口等待：底部消息导航已选中，但消息工作区备用指纹尚未确认")
                return false
            }
            onLog("消息工作区域入口[STRUCTURE_CONFIRMED]：底部消息标签与消息会话区域结构已在同一快照中确认")
            return true
        }

        val executionContext = gesturePolicy.captureContext(latest.first.window) ?: run {
            onLog("消息工作区域入口等待：窗口或显示坐标上下文已变化；拒绝使用旧坐标并重新扫描")
            return false
        }
        val request = GestureIntentRequest(
            id = "momo-message-work-area-${System.nanoTime()}",
            intent = GestureIntent.Tap(
                preferredBounds = latestAnchor.bubble.bounds,
                targetBounds = latestAnchor.clickableTab.bounds,
                durationMs = RECEIVED_GREETINGS_ENTRY_TAP_DURATION_MS,
                feedbackIntent = com.freedom.core.accessibility.api.GestureFeedbackIntent.NAVIGATION_TAB
            ),
            description = "Tap verified Momo message tab using its bottom-navigation hierarchy",
            executionContext = executionContext
        )
        val submission = gesturePolicy.execute(request, GestureExecutionListener { result ->
            onGestureOutcome(
                when (result.status) {
                    GestureExecutionStatus.COMPLETED -> MessageWorkAreaNavigationOutcome.COMPLETED
                    GestureExecutionStatus.DEFERRED -> MessageWorkAreaNavigationOutcome.DEFERRED_NOT_DISPATCHED
                    else -> MessageWorkAreaNavigationOutcome.UNCONFIRMED
                }
            )
            onLog(
                if (result.status == GestureExecutionStatus.COMPLETED) {
                    "消息工作区域入口：消息标签手势完成；等待新鲜页面指纹确认"
                } else {
                    "消息工作区域入口：手势未完成；status=${result.status}；reason=${result.reason}"
                }
            )
        })
        if (submission == GestureSubmission.DEFERRED_NOT_READY) {
            onLog("消息工作区域入口[DEFERRED_NOT_READY]：页面暂不可操作；未点击、不消耗导航尝试，保留意图并等待下一份新快照")
            return false
        }
        if (submission != GestureSubmission.ACCEPTED) {
            onLog("消息工作区域入口：统一手势协调器未接受点击；submission=$submission；保留导航意图")
            return false
        }
        onLog("消息工作区域入口：已提交经过双快照验证的消息标签导航；等待新鲜页面指纹确认")
        return true
    }

    private val draftTransaction = MomoDraftTransactionController(
        rootAccess = rootAccess,
        targetPackage = targetPackage,
        activityClass = activityClass,
        onLog = onLog,
        gesturePolicy = gesturePolicy,
        isAiReplyUiOperationOwned = isAiReplyUiOperationOwned,
        isBoundAiReplyUiOperationOwned = isBoundAiReplyUiOperationOwned,
        sendTapDurationMs = SEND_TAP_DURATION_MS
    )

    override fun writeDraft(target: ChatDraftInputTarget, expectedText: String, text: String): ChatDraftInputResult =
        draftTransaction.writeDraft(target, expectedText, text)

    override fun verifyDraft(target: ChatDraftInputTarget, text: String): ChatDraftInputResult =
        draftTransaction.verifyDraft(target, text)

    /** The parent chat already holds the lease. Locate only the current image-entry control. */
    internal fun openImageAlbum(
        target: ChatDraftInputTarget,
        transactionId: String,
        ownsTransaction: () -> Boolean,
        onGestureResult: (GestureExecutionStatus) -> Unit
    ): GestureSubmission {
        val window = accessibilityGateway.currentGestureWindow() ?: return GestureSubmission.DEFERRED_NOT_READY
        val original = target.nodeTarget.window
        if (window.packageName != original.packageName || window.windowId != original.windowId ||
            window.generation < original.generation) return GestureSubmission.DEFERRED_NOT_READY
        return submitVerifiedAlbumTap(
            target, window, transactionId, ownsTransaction, "image-entry",
            resolve = { root, _ ->
                chatComponents.pageInspector.inspectImageEntry(AccessibilityNodeInfo.obtain(root), targetPackage)
            },
            boundsOf = { ScreenRect(it.left, it.top, it.right, it.bottom) },
            onGestureResult = onGestureResult
        )
    }

    /** M02.4-B.2: submit one selector tap, then the caller observes directory arrival. */
    internal fun openImageAlbumDirectory(
        target: ChatDraftInputTarget,
        albumWindow: WindowRef,
        transactionId: String,
        ownsTransaction: () -> Boolean,
        onGestureResult: (GestureExecutionStatus) -> Unit
    ): GestureSubmission = submitVerifiedAlbumTap(
        target, albumWindow, transactionId, ownsTransaction, "selector",
        resolve = { root, _ -> MomoAlbumSelectorInspector.inspect(root, targetPackage) },
        boundsOf = { ScreenRect(it.left, it.top, it.right, it.bottom) },
        onGestureResult = onGestureResult
    )

    /** Only the actual foreground window, never a visible-window fallback. */
    private fun freshMediaSnapshot(expectedWindow: WindowRef? = null): AccessibilityTreeSnapshot? {
        val window = accessibilityGateway.currentGestureWindow() ?: return null
        if (window.packageName != targetPackage || window.windowId < 0 ||
            (expectedWindow != null && window != expectedWindow)) return null
        val snapshot = accessibilityGateway.inspectActiveWindow(
            TreeInspectionRequest(
                maxNodes = NAVIGATION_EVIDENCE_MAX_NODES,
                textCapture = NodeTextCapture.EPHEMERAL
            )
        ) ?: return null
        return snapshot.takeIf {
            it.window == window && !it.truncated && !it.timeBudgetExceeded &&
                accessibilityGateway.currentGestureWindow() == window
        }
    }

    /** Read-only dropdown evidence while the page router is resolving the window change. */
    internal fun inspectImageAlbumDirectory(albumWindow: WindowRef): Boolean {
        val snapshot = freshMediaSnapshot() ?: return false
        return isAlbumDirectoryArrival(albumWindow, snapshot) &&
            MomoAlbumDirectoryInspector.resolveFreedomName(snapshot, targetPackage) != null
    }

    private fun isAlbumDirectoryArrival(albumWindow: WindowRef, snapshot: AccessibilityTreeSnapshot): Boolean =
        MomoAlbumDirectoryInspector.isNewDirectoryWindow(albumWindow, snapshot.window, targetPackage)

    /** M02.5: unique directory name, with the name's own safe bounds. */
    internal fun inspectFreedomDirectory(albumWindow: WindowRef): MomoFreedomDirectoryTarget? {
        val snapshot = freshMediaSnapshot() ?: return null
        if (!isAlbumDirectoryArrival(albumWindow, snapshot)) return null
        return MomoAlbumDirectoryInspector.resolveFreedomName(snapshot, targetPackage)
    }

    /** M02.6: click only the name; caller must observe the Freedom title before selecting. */
    internal fun openFreedomAlbum(
        target: ChatDraftInputTarget,
        albumWindow: WindowRef,
        transactionId: String,
        ownsTransaction: () -> Boolean,
        onGestureResult: (GestureExecutionStatus) -> Unit
    ): GestureSubmission {
        val directory = inspectFreedomDirectory(albumWindow) ?: return GestureSubmission.DEFERRED_NOT_READY
        return submitVerifiedAlbumTap(
            target, directory.window, transactionId, ownsTransaction, "freedom-name",
            resolve = { _, window ->
                freshMediaSnapshot(window)?.takeIf { isAlbumDirectoryArrival(albumWindow, it) }
                    ?.let { MomoAlbumDirectoryInspector.resolveFreedomName(it, targetPackage) }
            },
            boundsOf = { it.nameBounds },
            onGestureResult = onGestureResult
        )
    }

    /** The selected Freedom album only needs its current select control. */
    internal fun selectFreedomImage(
        target: ChatDraftInputTarget,
        albumWindow: WindowRef,
        transactionId: String,
        ownsTransaction: () -> Boolean,
        onGestureResult: (GestureExecutionStatus) -> Unit
    ): GestureSubmission = submitVerifiedAlbumTap(
        target, albumWindow, transactionId, ownsTransaction, "select-image",
        resolve = { _, window ->
            freshMediaSnapshot(window)?.let { MomoFreedomAlbumInspector.ownedSelectionTarget(it, targetPackage) }
        },
        boundsOf = { it.second },
        onGestureResult = onGestureResult
    )

    /** The current finish control itself proves one selection. Baseline absence never blocks send. */
    internal fun sendFreedomImage(
        target: ChatDraftInputTarget,
        albumWindow: WindowRef,
        transactionId: String,
        ownsTransaction: () -> Boolean,
        baseline: MomoImageSendBaseline?,
        onDispatched: (MomoImageSendAttempt) -> Unit,
        onGestureResult: (GestureExecutionStatus) -> Unit
    ): GestureSubmission {
        val verifiedBaseline = baseline?.takeIf { it.belongsTo(target, transactionId) }
        return submitVerifiedAlbumTap(
            target, albumWindow, transactionId, ownsTransaction, "send-image",
            resolve = { _, window ->
                freshMediaSnapshot(window)?.let { MomoFreedomAlbumInspector.ownedSendTarget(it, targetPackage) }
            },
            boundsOf = { it.bounds },
            onPlatformDispatched = { commandId, atUptimeMs ->
                onDispatched(MomoImageSendAttempt(verifiedBaseline, commandId, atUptimeMs))
            },
            onGestureResult = onGestureResult
        )
    }

    /**
     * All album actions share the already audited request-scoped permit and GestureApi.
     * Each call is a single action; the caller owns stage progression and result reconciliation.
     */
    private fun <T : Any> submitVerifiedAlbumTap(
        target: ChatDraftInputTarget,
        expectedWindow: WindowRef,
        transactionId: String,
        ownsTransaction: () -> Boolean,
        action: String,
        resolve: (AccessibilityNodeInfo, WindowRef) -> T?,
        boundsOf: (T) -> ScreenRect,
        onPlatformDispatched: (String, Long) -> Unit = { _, _ -> },
        onGestureResult: (GestureExecutionStatus) -> Unit
    ): GestureSubmission {
        val lease = target.taskLease ?: return GestureSubmission.DEFERRED_NOT_READY
        fun ownsWrite(): Boolean = runCatching {
            ownsTransaction() && isAiReplyUiOperationOwned() && isBoundAiReplyUiOperationOwned(lease)
        }.getOrDefault(false)
        // All media steps belong to the admitted parent chat, including child windows without
        // a new Activity. The exact command, live target and parent lease remain mandatory.
        if (transactionId.isBlank() || target.packageName != targetPackage ||
            expectedWindow.packageName != targetPackage || expectedWindow.windowId < 0 ||
            !ownsWrite()
        ) return GestureSubmission.DEFERRED_NOT_READY
        return rootAccess.withActiveTargetRoot { root, window ->
            if (window != expectedWindow || accessibilityGateway.currentGestureWindow() != window) {
                return@withActiveTargetRoot GestureSubmission.DEFERRED_NOT_READY
            }
            val evidence = resolve(root, window) ?: return@withActiveTargetRoot GestureSubmission.DEFERRED_NOT_READY
            val bounds = boundsOf(evidence)
            val executionContext = gesturePolicy.captureContext(window)
                ?: return@withActiveTargetRoot GestureSubmission.DEFERRED_NOT_READY
            if (bounds.isEmpty || executionContext.taskLease != lease ||
                executionContext.targetWindow != window || !ownsWrite()) {
                return@withActiveTargetRoot GestureSubmission.DEFERRED_NOT_READY
            }
            fun targetStillCurrent(): Boolean =
                ownsWrite() && rootAccess.withActiveTargetRoot { freshRoot, freshWindow ->
                    freshWindow == window && accessibilityGateway.currentGestureWindow() == window &&
                        resolve(freshRoot, freshWindow) == evidence &&
                        accessibilityGateway.currentGestureWindow() == window
                } == true
            val commandId = "momo-album-$action-${System.nanoTime()}"
            val permit = MomoAlbumGesturePermit(
                transactionId = transactionId,
                expectedWindow = window,
                currentWindow = accessibilityGateway::currentGestureWindow,
                ownsTransaction = ::ownsWrite,
                commandId = commandId,
                targetStillCurrent = ::targetStillCurrent,
                transactionChildAction = true
            )
            if (!activeAlbumSelectorPermit.compareAndSet(null, permit)) {
                return@withActiveTargetRoot GestureSubmission.DEFERRED_NOT_READY
            }
            val submission = runCatching {
                if (!inspectGesturePageReadiness(root, window.activityClass, RunMode.AI_REPLY, commandId).ready ||
                    !ownsWrite() || accessibilityGateway.currentGestureWindow() != window
                ) GestureSubmission.DEFERRED_NOT_READY
                else gesturePolicy.execute(
                    GestureIntentRequest(
                        id = commandId,
                        intent = GestureIntent.Tap(
                            preferredBounds = bounds, targetBounds = bounds,
                            durationMs = SEND_TAP_DURATION_MS,
                            feedbackIntent = if (action == "select-image" || action == "interception-switch")
                                com.freedom.core.accessibility.api.GestureFeedbackIntent.STANDARD
                            else com.freedom.core.accessibility.api.GestureFeedbackIntent.NAVIGATION_ENTRY
                        ),
                        description = "Verified Momo album $action",
                        executionContext = executionContext
                    ),
                    object : GestureExecutionListener {
                        override fun isDispatchTargetCurrent(commandId: String): Boolean =
                            activeAlbumSelectorPermit.get() === permit && permit.matchesCommand(commandId) &&
                                permit.confirmsCurrentTarget()

                        override fun onDispatched(commandId: String, dispatchedAtUptimeMs: Long) {
                            if (activeAlbumSelectorPermit.get() === permit && permit.matchesCommand(commandId) && ownsWrite()) {
                                onPlatformDispatched(commandId, dispatchedAtUptimeMs)
                            }
                        }

                        override fun onResult(result: com.freedom.core.accessibility.gesture.GestureExecutionResult) {
                            if (!permit.matchesCommand(result.commandId) ||
                                !activeAlbumSelectorPermit.compareAndSet(permit, null)) return
                            if (runCatching(ownsTransaction).getOrDefault(false)) onGestureResult(result.status)
                        }
                    }
                )
            }.getOrDefault(GestureSubmission.REJECTED)
            if (submission != GestureSubmission.ACCEPTED) {
                activeAlbumSelectorPermit.compareAndSet(permit, null)
            }
            onLog("MEDIA_ALBUM_ACTION action=$action;submission=$submission;windowId=${window.windowId}" +
                if (action == "freedom-name")
                    ";generation=${window.generation};bounds=$bounds;display=${executionContext.coordinateContext.widthPixels}x${executionContext.coordinateContext.heightPixels}"
                else "")
            submission
        } ?: GestureSubmission.DEFERRED_NOT_READY
    }

    /** Capture the original chat's tail BEFORE opening the album. No message content is retained. */
    internal fun captureImageSendBaseline(
        target: ChatDraftInputTarget,
        transactionId: String,
        ownsTransaction: () -> Boolean
    ): MomoImageSendBaseline? {
        if (target.taskLease == null || transactionId.isBlank() ||
            !runCatching(ownsTransaction).getOrDefault(false) ||
            !isBoundAiReplyUiOperationOwned(target.taskLease)
        ) return null
        val snapshot = freshMediaSnapshot(target.nodeTarget.window) ?: return null
        return MomoImageSendEvidenceInspector.baseline(snapshot, target, transactionId)
    }

    /**
     * Re-read the exact original conversation and appended outgoing image.
     * Recognized node states are sufficient; legacy non-clickable status color remains
     * a supplemental positive-success proof. No mutation is performed here.
     */
    internal fun inspectImageSendResult(
        attempt: MomoImageSendAttempt,
        target: ChatDraftInputTarget,
        transactionId: String,
        ownsTransaction: () -> Boolean,
        onResult: (MomoImageSendResult) -> Unit
    ) {
        val baseline = attempt.baseline
        if (baseline == null) {
            if (runCatching(ownsTransaction).getOrDefault(false)) onResult(MomoImageSendResult.UNKNOWN)
            return
        }
        fun current(): Boolean = attempt.commandId.startsWith("momo-album-send-image-") &&
            attempt.dispatchedAtUptimeMs >= 0L && baseline.belongsTo(target, transactionId) &&
            runCatching(ownsTransaction).getOrDefault(false)
        if (!current()) return
        val snapshot = freshMediaSnapshot()
        val proof = snapshot?.let { MomoImageSendEvidenceInspector.newImage(it, baseline, target) }
        if (proof == null) {
            if (current()) onResult(MomoImageSendResult.UNKNOWN)
            return
        }
        if (proof.nodeResult != MomoImageSendResult.UNKNOWN) {
            if (current() && accessibilityGateway.currentGestureWindow() == proof.window) {
                onLog("MEDIA_IMAGE_SEND_EVIDENCE result=${proof.nodeResult};source=NODE;windowId=${proof.window.windowId}")
                onResult(proof.nodeResult)
            } else if (current()) onResult(MomoImageSendResult.UNKNOWN)
            return
        }
        val capture = screenshotCapture
        if (capture == null || proof.statusClickable) {
            // Legacy visual evidence may confirm a non-clickable success, but can never authorize resend.
            if (current()) onResult(MomoImageSendResult.UNKNOWN)
            return
        }
        capture.captureActiveDisplay(proof.window.packageName, proof.window.activityClass) { frame ->
            val result = try {
                if (!current() || frame == null || !frame.monitorOverlayHidden ||
                    accessibilityGateway.currentGestureWindow() != proof.window
                ) MomoImageSendResult.UNKNOWN
                else {
                    val after = freshMediaSnapshot(proof.window)
                        ?.let { MomoImageSendEvidenceInspector.newImage(it, baseline, target) }
                    if (after != proof) MomoImageSendResult.UNKNOWN
                    else MomoImageSendEvidenceInspector.classifyStatus(
                        frame.bitmap, proof,
                        frame.scope == com.freedom.adapters.accessibility.android.AndroidScreenshotScope.TARGET_WINDOW
                    ).takeIf { it == MomoImageSendResult.SUCCESS } ?: MomoImageSendResult.UNKNOWN
                }
            } catch (_: Exception) {
                MomoImageSendResult.UNKNOWN
            } finally {
                runCatching { frame?.bitmap?.recycle() }
                frame?.close()
            }
            if (current()) {
                onLog("MEDIA_IMAGE_SEND_EVIDENCE result=$result;windowId=${proof.window.windowId}")
                onResult(result)
            }
        }
    }

    private val privateFollowUpController = MomoPrivateFollowUpController(
        accessibilityGateway = accessibilityGateway,
        targetPackage = targetPackage,
        onLog = onLog,
        isBoundAiReplyUiOperationOwned = isBoundAiReplyUiOperationOwned,
        captureImageSendBaseline = ::captureImageSendBaseline,
        onPageAnomaly = { child, reason ->
            child.target.taskLease?.let { lease ->
                captureAnomalyPage(lease.taskId, lease.runGeneration, "${child.logPrefix}:${child.stage}:$reason")
            }
        }
    )

    private val imageTransaction = MomoImageSendTransactionController(
        accessibilityGateway = accessibilityGateway,
        targetPackage = targetPackage,
        onLog = onLog,
        followUpCurrent = privateFollowUpController::followUpCurrent,
        originalPrivateFollowUpChat = ::originalPrivateFollowUpChat,
        followUpWait = privateFollowUpController::followUpWait,
        followUpTerminal = privateFollowUpController::followUpTerminal,
        submitPrivateFollowUpStep = privateFollowUpController::submitPrivateFollowUpStep,
        openImageAlbum = ::openImageAlbum,
        openImageAlbumDirectory = ::openImageAlbumDirectory,
        inspectFreedomDirectory = ::inspectFreedomDirectory,
        openFreedomAlbum = ::openFreedomAlbum,
        selectFreedomImage = ::selectFreedomImage,
        sendFreedomImage = ::sendFreedomImage,
        freshMediaSnapshot = ::freshMediaSnapshot,
        submitImageRetryOpen = ::submitImageRetryOpen,
        submitImageRetryConfirm = ::submitImageRetryConfirm,
        inspectImageSendResult = ::inspectImageSendResult
    )

    override fun progress(): PrivateChatFollowUpProgress = privateFollowUpController.progress()

    private fun originalPrivateFollowUpChat(child: PrivateFollowUpTransaction): Boolean {
        val live = accessibilityGateway.currentGestureWindow() ?: return false
        val original = child.target.nodeTarget.window
        return live.packageName == original.packageName && live.windowId == original.windowId &&
            live.generation >= original.generation &&
            live.activityClass == original.activityClass && interceptionReturnedToChat(child)
    }

    /** Positive evidence for this parent's child page, not permission for a generic gesture.
     * Reuse the action-specific inspectors; a target-package window alone is not a route. */
    private fun inspectPrivateFollowUpOwnedPage(root: AccessibilityNodeInfo, live: WindowRef): Boolean {
        val child = privateFollowUpController.current() ?: return false
        if (!privateFollowUpController.followUpCurrent(child) || live.packageName != targetPackage || live.windowId < 0 ||
            live.generation < child.target.nodeTarget.window.generation ||
            root.windowId != live.windowId || root.packageName?.toString() != targetPackage ||
            accessibilityGateway.currentGestureWindow() != live) return false
        val verified = if (child.interception) {
            child.stage != PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS &&
                (interceptionTransaction.interceptionSettingsPage(root, child) ||
                    (child.interceptionState.interceptionSettingsObserved &&
                        interceptionNodeBounds(root, "tv_title", "android.widget.TextView", text = "拦截消息") != null &&
                        interceptionNodeBounds(root, "btn_switch", "android.widget.CompoundButton", checkable = true) != null))
        } else when (child.stage) {
            PrivateFollowUpStage.WAIT_ALBUM, PrivateFollowUpStage.WAIT_DIRECTORY,
            PrivateFollowUpStage.WAIT_FREEDOM, PrivateFollowUpStage.WAIT_SELECTION,
            PrivateFollowUpStage.WAIT_SEND_RESULT -> {
                val album = child.image.albumWindow
                val albumPage = MomoAlbumArrivalInspector.isConfirmed(root, targetPackage) ||
                    MomoAlbumSelectorInspector.inspect(root, targetPackage) != null
                albumPage ||
                    (child.stage == PrivateFollowUpStage.WAIT_FREEDOM && freshMediaSnapshot(live)?.let {
                        MomoFreedomAlbumInspector.ownedSelectionTarget(it, targetPackage) != null
                    } == true) ||
                    (child.stage == PrivateFollowUpStage.WAIT_SELECTION && album != null &&
                        live.packageName == album.packageName && live.windowId == album.windowId &&
                        live.generation >= album.generation && freshMediaSnapshot(live)?.let {
                            MomoFreedomAlbumInspector.ownedSendTarget(it, targetPackage) != null
                        } == true) ||
                    (child.stage == PrivateFollowUpStage.WAIT_DIRECTORY && album != null &&
                        freshMediaSnapshot(live)?.let {
                            isAlbumDirectoryArrival(album, it) &&
                                MomoAlbumDirectoryInspector.resolveFreedomName(it, targetPackage) != null
                        } == true) ||
                    (child.stage == PrivateFollowUpStage.WAIT_SEND_RESULT &&
                        child.image.imageRetryDialogRequested && freshMediaSnapshot(live)?.let {
                            MomoImageSendEvidenceInspector.retryConfirmation(it) != null
                        } == true)
            }
            PrivateFollowUpStage.IMAGE_RETRY_OPEN, PrivateFollowUpStage.IMAGE_RETRY_CONFIRM ->
                child.image.imageRetryDialogRequested && freshMediaSnapshot(live)?.let {
                    MomoImageSendEvidenceInspector.retryConfirmation(it) != null
                } == true
            else -> false
        }
        return verified && privateFollowUpController.followUpCurrent(child) && accessibilityGateway.currentGestureWindow() == live
    }

    /** Only the read-only readiness caller may use child-page evidence. Dispatch still requires
     * the existing exact command permit and independent target/overlay/lease checks. */
    internal fun inspectObservedPrivateFollowUpReadiness(
        root: AccessibilityNodeInfo, activity: String, mode: RunMode
    ): TargetAppPageReadiness? {
        if (mode != RunMode.AI_REPLY) return null
        val live = accessibilityGateway.currentGestureWindow() ?: return null
        if (live.activityClass != activity || !inspectPrivateFollowUpOwnedPage(root, live)) return null
        val readiness = pageInspectionComponents.gestureReadinessInspector.inspect(
            root, activity, mode, observedOwnedChildPage = true
        )
        return if (accessibilityGateway.currentGestureWindow() == live) readiness
            else TargetAppPageReadiness(false, "Private follow-up observation window changed", retryable = true)
    }

    override fun beginPrivateFollowUp(
        target: ChatDraftInputTarget,
        transactionId: String,
        interception: Boolean,
        beforeStep: (com.freedom.features.aireply.domain.contact.ContactFollowUpStep) -> Boolean,
        ownsTransaction: () -> Boolean
    ): Boolean = privateFollowUpController.beginPrivateFollowUp(
        target, transactionId, interception, beforeStep, ownsTransaction
    )

    override fun privateFollowUpSucceeded(): Boolean = privateFollowUpController.privateFollowUpSucceeded()

    override fun abort(reason: String) = privateFollowUpController.abort(reason)

    override fun reset(reason: String) = privateFollowUpController.reset(reason)

    private val interceptionTransaction = MomoInterceptionTransactionController(
        rootAccess = rootAccess,
        accessibilityGateway = accessibilityGateway,
        targetPackage = targetPackage,
        onLog = onLog,
        nodeBounds = ::interceptionNodeBounds,
        originalPrivateFollowUpChat = ::originalPrivateFollowUpChat,
        followUpWait = privateFollowUpController::followUpWait,
        followUpTerminal = privateFollowUpController::followUpTerminal,
        submitInterceptionStep = ::submitInterceptionStep,
        interceptionDismissBounds = ::interceptionDismissBounds,
        interceptionReturnedToChat = ::interceptionReturnedToChat,
        interceptionBackBounds = ::interceptionBackBounds
    )

    private fun submitImageRetryOpen(child: PrivateFollowUpTransaction, window: WindowRef) {
        child.stage = PrivateFollowUpStage.IMAGE_RETRY_OPEN
        privateFollowUpController.submitPrivateFollowUpStep(child, PrivateFollowUpStage.WAIT_SEND_RESULT) { callback ->
            submitVerifiedAlbumTap(
                child.target, window, child.id, { privateFollowUpController.followUpCurrent(child) }, "image-retry-open",
                resolve = { _, live ->
                    freshMediaSnapshot(live)?.let { snapshot ->
                        if (MomoImageSendEvidenceInspector.networkDisconnected(snapshot)) null
                        else imageTransaction.imageRetryProof(child, snapshot)
                            ?.takeIf { it.nodeResult == MomoImageSendResult.FAILED }
                    }
                }, boundsOf = { it.statusBounds },
                onPlatformDispatched = { _, _ ->
                    if (privateFollowUpController.followUpCurrent(child)) {
                        child.image.imageRetryDialogRequested = true
                        child.image.imageRetryDialogWindowId = null
                        onLog("PRIVATE_IMAGE_SEND RETRY_OPEN_DISPATCHED")
                    }
                },
                onGestureResult = callback
            )
        }
    }

    private fun submitImageRetryConfirm(child: PrivateFollowUpTransaction, window: WindowRef) {
        child.stage = PrivateFollowUpStage.IMAGE_RETRY_CONFIRM
        privateFollowUpController.submitPrivateFollowUpStep(child, PrivateFollowUpStage.WAIT_SEND_RESULT) { callback ->
            submitVerifiedAlbumTap(
                child.target, window, child.id, { privateFollowUpController.followUpCurrent(child) }, "image-retry-confirm",
                resolve = { _, live ->
                    if (!child.image.imageRetryDialogRequested || child.image.imageRetryDialogWindowId != live.windowId) null
                    else freshMediaSnapshot(live)?.let(MomoImageSendEvidenceInspector::retryConfirmation)
                }, boundsOf = { it },
                onGestureResult = callback
            )
        }
    }

    /**
     * Interception uses the same parent-bound serial tap pipeline as image sending.
     * Resolve only the current action's target; switch verification is kept outside the frozen locator.
     */
    private fun interceptionNodeBounds(
        root: AccessibilityNodeInfo,
        id: String,
        className: String,
        clickable: Boolean = false,
        checkable: Boolean = false,
        text: String? = null
    ): ScreenRect? = runCatching {
        val nodes = root.findAccessibilityNodeInfosByViewId("$targetPackage:id/$id").orEmpty()
        try {
            val node = nodes.singleOrNull {
                it.packageName?.toString() == targetPackage && it.className?.toString() == className &&
                    it.isVisibleToUser && it.isEnabled && (!clickable || it.isClickable) &&
                    (!checkable || it.isCheckable) && (text == null || it.text?.toString()?.trim() == text)
            } ?: return null
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.toScreenRect().takeUnless { it.isEmpty }
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }.getOrNull()

    private fun submitInterceptionStep(
        child: PrivateFollowUpTransaction,
        next: PrivateFollowUpStage,
        action: String,
        resolve: (AccessibilityNodeInfo, WindowRef) -> ScreenRect?
    ) {
        val window = accessibilityGateway.currentGestureWindow() ?: return
        if (window.packageName != targetPackage || window.windowId < 0) return
        privateFollowUpController.submitPrivateFollowUpStep(child, next) { callback ->
            var layoutBoundsLogged = false
            submitVerifiedAlbumTap(
                child.target, window, child.id, { privateFollowUpController.followUpCurrent(child) }, action,
                resolve = { root, liveWindow ->
                    // The toggle must still be OFF at dispatch revalidation.
                    val safe = when (action) {
                        "interception-avatar" -> liveWindow.windowId == child.target.nodeTarget.window.windowId &&
                            liveWindow.activityClass == child.target.nodeTarget.window.activityClass &&
                            interceptionNodeBounds(root, "chat_user_name", "android.widget.TextView",
                                text = child.target.expectedChatTitle) != null
                        "interception-layout" -> interceptionTransaction.interceptionSettingsPage(root, child) &&
                            interceptionTransaction.interceptionSettingsChecked(root, child) != true
                        "interception-switch" -> interceptionTransaction.interceptionChecked(root) == false
                        "interception-dismiss" -> interceptionTransaction.interceptionChecked(root) == true
                        "interception-back" -> interceptionTransaction.interceptionSettingsPage(root, child) &&
                            (interceptionTransaction.interceptionSettingsChecked(root, child)
                                ?: child.interceptionState.switchVerifiedOn)
                        else -> true
                    }
                    (if (safe) resolve(root, liveWindow) else null)?.also { bounds ->
                        if (action == "interception-layout" && !layoutBoundsLogged) {
                            layoutBoundsLogged = true
                            onLog("PRIVATE_INTERCEPTION LAYOUT_TARGET windowId=${liveWindow.windowId};" +
                                "generation=${liveWindow.generation};bounds=$bounds")
                        }
                    }
                }, boundsOf = { it },
                onGestureResult = callback
            )
        }
    }

    private fun interceptionDismissBounds(root: AccessibilityNodeInfo): ScreenRect? {
        val title = interceptionNodeBounds(root, "tv_title", "android.widget.TextView", text = "拦截消息")
            ?: return null
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        // The user-confirmed safe scrim is ABOVE the title, not the entire FrameLayout.
        // Derive it from the live window and title; never reuse screenshot coordinates.
        return ScreenRect(rootBounds.left, rootBounds.top, rootBounds.right, title.top)
            .takeUnless { it.isEmpty || title.top > rootBounds.bottom }
    }

    /** Verify the bound original chat before entry and after the toggle. */
    private fun interceptionReturnedToChat(child: PrivateFollowUpTransaction): Boolean =
        rootAccess.withActiveTargetRoot { root, window ->
            window.packageName == child.target.packageName &&
                window.generation >= child.target.nodeTarget.window.generation &&
                window.windowId == child.target.nodeTarget.window.windowId &&
                window.activityClass == child.target.nodeTarget.window.activityClass &&
                interceptionNodeBounds(root, "chat_toolbar_avatar", "android.widget.ImageView", clickable = true) != null &&
                (child.target.expectedChatTitle == null ||
                    interceptionNodeBounds(root, "chat_user_name", "android.widget.TextView",
                        text = child.target.expectedChatTitle) != null) &&
                accessibilityGateway.currentGestureWindow() == window
        } == true

    private fun interceptionBackBounds(window: WindowRef): ScreenRect? {
        val snapshot = freshMediaSnapshot(window) ?: return null
        val nodes = snapshot.nodes.filter { it.packageName == targetPackage && it.windowId == window.windowId }
        val root = nodes.singleOrNull { it.path.childIndices.isEmpty() }?.bounds ?: return null
        if (root.isEmpty) return null
        // The supplied return ImageButton has no id. Its local top-left header position
        // identifies the safe target; never fall back to a fixed absolute point.
        return nodes.singleOrNull {
            val b = it.bounds
            it.className == "android.widget.ImageButton" && it.clickable && it.enabled && it.visible &&
                !b.isEmpty && b.left >= root.left && b.top >= root.top &&
                b.right <= root.left + root.width / 3 &&
                b.bottom <= root.top + root.height / 5
        }?.bounds
    }

    override fun observe() = privateFollowUpController.observe(
        reconcileInterception = interceptionTransaction::reconcileInterception,
        observeImage = imageTransaction::observe,
        observeInterception = interceptionTransaction::observe
    )

    override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult =
        sendDraft(target, expectedText) {}

    override fun sendDraft(
        target: ChatDraftInputTarget,
        expectedText: String,
        onGestureCompleted: () -> Unit
    ): ChatDraftInputResult = draftTransaction.sendDraft(target, expectedText, onGestureCompleted)


    override fun inspectChatPage(): ChatPageSnapshot {
        if (inspectPendingInboxIdentityCapture()) {
            // Never expose the pre-navigation chat snapshot while the profile transaction is
            // still pending. Otherwise the model can start while the UI is still on the profile
            // page (or while BACK has not yet restored the chat detail page). The pending flow
            // must finish, confirm the chat page, and only then allow the main pipeline to read
            // the current-window fallback or load history.
            return unknownChatPageSnapshot(activityClass())
        }
        val currentActivity = activityClass()
        if (sayHiNavigationFlowController.shouldUseDedicatedReceivedListInspector(
                isReceivedListSurface = isReceivedGreetingsListStructureVisible()
            )
        ) {
            if (!sayHiGenericChatInspectionBypassActive) {
                sayHiGenericChatInspectionBypassActive = true
                onLog(
                    "收到招呼列表快照隔离：跳过通用 CHAT_PAGE 全树扫描；" +
                        "节点采集统一交由受手势/快照闸门保护的外层 listview 与内层 card_list 精确检查"
                )
            }
            return receivedGreetingsListChatPagePlaceholder(targetPackage)
        }
        sayHiGenericChatInspectionBypassActive = false
        inspectMomoNavigationEvidenceIfNeeded()
        val captured = rootAccess.withActiveTargetRoot { root, window ->
            if (window.packageName != targetPackage || window.windowId < 0 ||
                !accessibilityGateway.isWindowCurrent(window)
            ) return@withActiveTargetRoot null
            val inspection = chatComponents.pageInspector.inspectRoot(
                root = AccessibilityNodeInfo.obtain(root),
                targetPackage = targetPackage,
                activityClass = window.activityClass.ifBlank { activityClass() }
            )
            val resolvedActivity = window.activityClass.ifBlank { activityClass() }
            val treeEvidence = chatComponents.nodeTreeDiagnosticInspector.inspectRoot(
                root = AccessibilityNodeInfo.obtain(root),
                targetPackage = targetPackage,
                activityClass = resolvedActivity,
                window = window
            )
            val shouldCaptureInboxViewport = inspection.pageType == MomoChatPageType.CHAT_LIST
            val chatListExtraction = if (shouldCaptureInboxViewport) {
                chatComponents.listSessionExtractor.extractRoot(
                    root = AccessibilityNodeInfo.obtain(root),
                    targetPackage = targetPackage,
                    activityClass = resolvedActivity,
                    window = window
                )
            } else {
                MomoChatListSessionExtraction(null, emptyList(), 0, false)
            }
            Triple(inspection, treeEvidence, window) to chatListExtraction
        } ?: return unknownChatPageSnapshot(activityClass())

        val (pageEvidence, chatListExtraction) = captured
        val (inspection, treeEvidence, window) = pageEvidence
        val awaitingIdentityListReturn = sessionComponents.inboxIdentity.pendingInboxIdentityCapture?.takeIf {
            it.stage == InboxIdentityCaptureStage.WAITING_LIST_RETURN
        }
        if (awaitingIdentityListReturn != null && inspection.pageType != MomoChatPageType.CHAT_LIST) {
            return unknownChatPageSnapshot(activityClass())
        }
        treeEvidence.snapshot?.let { snapshot ->
            sessionComponents.sampleStore.recordNodeTree(
                snapshot,
                MomoNodeTreeCapturePriorityPolicy.classify(snapshot.window.activityClass, inspection.pageType)
            )
        }
        val chatKind = when (inspection.pageType) {
            MomoChatPageType.UNKNOWN -> ChatPageKind.UNKNOWN
            MomoChatPageType.NON_CHAT -> ChatPageKind.NON_CHAT
            MomoChatPageType.CHAT_LIST -> ChatPageKind.CHAT_LIST
            MomoChatPageType.CHAT_DETAIL_CANDIDATE -> ChatPageKind.CHAT_DETAIL_CANDIDATE
        }
        val pendingIdentityCapture = awaitingIdentityListReturn
        val pendingIdentityMatches = pendingIdentityCapture?.let { pending ->
            chatListExtraction.candidates.indices.filter { index ->
                pending.anchor.matches(chatListExtraction.candidates[index])
            }
        }.orEmpty()
        val pendingIdentityMatchIndex = pendingIdentityMatches.singleOrNull()
        val canConsumePendingIdentity = pendingIdentityCapture != null &&
            inspection.pageType == MomoChatPageType.CHAT_LIST && !chatListExtraction.truncated

        if (chatListExtraction.candidates.isNotEmpty()) {
            val observedAt = System.currentTimeMillis()
            val snapshotId = "momo-${observedAt}-${sessionComponents.chatListSnapshotSequence.incrementAndGet()}"
            val cachedPlatformIds = chatListExtraction.candidates.mapIndexed { index, candidate ->
                val justCapturedId = if (index == pendingIdentityMatchIndex) {
                    pendingIdentityCapture?.platformUserId
                } else null
                justCapturedId ?: createInboxIdentityAnchor(candidate)?.opaqueKey()?.let { key ->
                    sessionComponents.inboxIdentity.cachedPlatformId(key)
                }
            }
            val identities = sessionComponents.contactIdentityManager.resolveSnapshot(
                snapshotId = snapshotId,
                observations = chatListExtraction.candidates.mapIndexed { index, candidate ->
                    ContactIdentityObservation(
                        displayName = candidate.nameText,
                        isPrivateChat = candidate.selectionClass == "PRIVATE_CHAT",
                        platformUserId = cachedPlatformIds[index]
                    )
                },
                observedAtMillis = observedAt
            )
            chatListExtraction.candidates.zip(identities).forEachIndexed { index, (candidate, identity) ->
                candidate.contactIdentity = identity
                val verificationResult = classifyMomoIdentityVerification(
                    confidence = identity.confidence,
                    contactId = identity.contactId
                )
                if (index != pendingIdentityMatchIndex && cachedPlatformIds[index] != null &&
                    verificationResult != MomoIdentityVerificationResult.IDENTITY_VERIFIED
                ) {
                    createInboxIdentityAnchor(candidate)?.opaqueKey()?.let { key ->
                        sessionComponents.inboxIdentity.invalidateCachedIdentity(key, verificationResult, onLog)
                    }
                    onLog(
                        "AI 联系人身份验证分类：result=${verificationResult.name}；" +
                            "confidence=${identity.confidence.name}；不把本次暂时无法确认写入永久失败缓存"
                    )
                }
            }
            if (canConsumePendingIdentity) {
                val verified = pendingIdentityMatchIndex?.let { index ->
                    identities.getOrNull(index)?.let { identity ->
                        identity.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                            !identity.contactId.isNullOrBlank()
                    }
                } == true
                sessionComponents.inboxIdentity.completePendingInboxIdentityBinding(pendingIdentityCapture!!, verified, onLog)
            }
        } else if (canConsumePendingIdentity) {
            sessionComponents.inboxIdentity.completePendingInboxIdentityBinding(pendingIdentityCapture!!, false, onLog)
        }
        val result = ChatPageSnapshot(
            packageName = inspection.windowPackage,
            activityClass = inspection.activityClass,
            pageKind = chatKind,
            scannedNodeCount = inspection.scannedNodeCount,
            editableInputCount = inspection.editableInputCount,
            messageListCount = inspection.messageListCount,
            sendActionCount = inspection.sendActionCount,
            messageRelatedViewIdCount = inspection.messageRelatedViewIdCount,
            hasChatTab = inspection.hasChatTab,
            messageListTarget = inspection.messageListPath?.let {
                NodeTarget(
                    window = window,
                    path = NodePath(it),
                    expectedViewId = inspection.messageListViewId?.takeIf(String::isNotBlank),
                    expectedClassName = inspection.messageListClassName?.takeIf(String::isNotBlank)
                )
            },
            // The chat composer may be surfaced as a live editable container rather than the
            // inner EditText. The current chat identity and editable-field state are verified
            // immediately before writing, so do not reject a freshly discovered input solely
            // because its implementation view ID/class changed during the model wait.
            inputFieldTarget = inspection.inputFieldPath?.let { NodeTarget(window, NodePath(it)) },
            structureEvidence = (treeEvidence.evidence + inspection.structureEvidence)
                .distinctBy { evidence -> evidence.path }
                .map { evidence ->
                ChatPageNodeEvidence(
                    path = evidence.path,
                    className = evidence.className,
                    viewId = evidence.viewId,
                    left = evidence.left,
                    top = evidence.top,
                    right = evidence.right,
                    bottom = evidence.bottom,
                    childCount = evidence.childCount,
                    isEditable = evidence.isEditable,
                    isScrollable = evidence.isScrollable,
                    isClickable = evidence.isClickable,
                    hasText = evidence.hasText,
                    insideListCandidate = evidence.insideListCandidate,
                    depth = evidence.depth,
                    parentPath = evidence.parentPath,
                    isVisibleToUser = evidence.isVisibleToUser,
                    isListProbeRoot = evidence.isListProbeRoot,
                    isAlternateListProbe = evidence.isAlternateListProbe,
                    isMessageRowCandidate = evidence.isMessageRowCandidate,
                    textLength = evidence.textLength,
                    contentDescriptionLength = evidence.contentDescriptionLength,
                    boundsValid = evidence.boundsValid,
                    isSelected = evidence.isSelected
                )
            },
            nodeTreeProbeVisitedNodeCount = treeEvidence.visitedNodeCount,
            nodeTreeProbeTruncated = treeEvidence.truncated,
            chatListViewport = chatListExtraction.viewport,
            chatListCollectionRowCount = chatListExtraction.collectionRowCount,
            chatListVisibleCollectionRows = chatListExtraction.visibleCollectionRows,
            chatListCanScrollForward = chatListExtraction.canScrollForward,
            chatListCanScrollBackward = chatListExtraction.canScrollBackward,
            screenBounds = ScreenRect(
                left = 0,
                top = 0,
                right = context.resources.displayMetrics.widthPixels,
                bottom = context.resources.displayMetrics.heightPixels
            ),
            chatListCandidates = chatListExtraction.candidates,
            chatListExtractionTruncated = chatListExtraction.truncated,
            chatListInvalidRowBoundsCount = chatListExtraction.invalidRowBoundsCount,
            chatListUnreadDiagnostics = chatListExtraction.unreadDiagnostics,
            chatTitleText = inspection.chatTitleText,
            chatHeaderDiagnostic = inspection.chatHeaderDiagnostic?.let { diagnostic ->
                com.freedom.features.aireply.ports.ChatHeaderNodeDiagnostic(
                    nodePath = diagnostic.nodePath,
                    found = diagnostic.found,
                    textLength = diagnostic.textLength,
                    contentDescriptionLength = diagnostic.contentDescriptionLength,
                    isVisibleToUser = diagnostic.isVisibleToUser,
                    left = diagnostic.left,
                    top = diagnostic.top,
                    right = diagnostic.right,
                    bottom = diagnostic.bottom,
                    boundsValid = diagnostic.boundsValid,
                    parentFound = diagnostic.parentFound,
                    parentClassName = diagnostic.parentClassName,
                    parentViewId = diagnostic.parentViewId,
                    parentIsVisibleToUser = diagnostic.parentIsVisibleToUser,
                    parentBoundsValid = diagnostic.parentBoundsValid,
                    readOutcome = diagnostic.readOutcome
                )
            },
            sessionListNavigationTarget = inspection.chatTabEvidence?.takeIf { evidence ->
                evidence.boundsValid && evidence.right > evidence.left && evidence.bottom > evidence.top
            }?.let { evidence ->
                val parsedIndices = evidence.path.split("/").map { it.toIntOrNull() }
                val childIndices = parsedIndices.filterNotNull()
                val display = ScreenRect(
                    0, 0,
                    context.resources.displayMetrics.widthPixels,
                    context.resources.displayMetrics.heightPixels
                )
                val clipped = ScreenRect(
                    maxOf(evidence.left, display.left),
                    maxOf(evidence.top, display.top),
                    minOf(evidence.right, display.right),
                    minOf(evidence.bottom, display.bottom)
                )
                if (childIndices.isEmpty() || childIndices.size != parsedIndices.size || clipped.isEmpty) null
                else SessionListNavigationTarget(
                    nodeTarget = NodeTarget(
                        window = window,
                        path = NodePath(childIndices),
                        expectedViewId = evidence.viewId,
                        expectedClassName = evidence.className
                    ),
                    bounds = clipped
                )
            },
            windowGeneration = window.generation
        )
        val previousEntryPosition = receivedGreetingsEntryPositionMemory.position
        val previousEntryPositionReason = receivedGreetingsEntryPositionMemory.reason
        val observedEntryPosition = receivedGreetingsEntryPositionMemory.observe(result)
        val observedEntryPositionReason = receivedGreetingsEntryPositionMemory.reason
        if (previousEntryPosition != observedEntryPosition || previousEntryPositionReason != observedEntryPositionReason) {
            onLog("收到的招呼入口位置记忆[OBSERVED]：" +
                "previous=" + previousEntryPosition.name + ";position=" + observedEntryPosition.name +
                ";reason=" + observedEntryPositionReason.name)
        }
        if (result.pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE &&
            sessionComponents.inboxIdentity.pendingInboxIdentityCapture == null
        ) {
            sessionComponents.inboxIdentity.lastConfirmedChatDetailSnapshot = result
        }
        return result
    }

    override fun hasPendingIdentityReturnReinspectionRequest(): Boolean =
        sessionComponents.inboxIdentity.hasReturnReinspection()

    override fun consumeIdentityReturnReinspectionRequest(): Boolean {
        val consumed = sessionComponents.inboxIdentity.consumeIdentityReturnReinspectionRequest()
        onLog(
            if (consumed) {
                "AI 身份回接[REQUEST_CONSUMED]：result=CONSUMED；reason=REQUEST_AVAILABLE；" +
                    "仅确认信号消费，业务读写仍由运行时门禁控制"
            } else {
                "AI 身份回接[REQUEST_NOT_CONSUMED]：result=NOT_CONSUMED；reason=NO_PENDING_REQUEST"
            }
        )
        return consumed
    }

    // ---- ChatSessionManagementPort: bounded Momo session navigation and local samples ----

    override fun requestContactIdentityCapture(
        candidate: ChatListCandidateSnapshot
    ): ContactIdentityCaptureRequestResult {
        onLog(
            "AI 联系人身份采集[REQUEST]：开始；" +
                "name=${candidate.nameText?.trim().orEmpty()}；" +
                "selection=${candidate.selectionClass}；" +
                "activity=${activityClass()}"
        )
        if (candidate.contactIdentity?.let {
                it.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                    !it.contactId.isNullOrBlank()
            } == true
        ) return ContactIdentityCaptureRequestResult.NOT_SUPPORTED

        val anchor = createInboxIdentityAnchor(candidate)
            ?: run {
                onLog("AI 联系人身份采集失败：CANDIDATE_ANCHOR_INVALID；未进入聊天详情头像采集")
                return ContactIdentityCaptureRequestResult.REJECTED
            }
        return requestContactIdentityCapture(anchor)
    }

    private fun requestContactIdentityCapture(anchor: InboxIdentityAnchor): ContactIdentityCaptureRequestResult {
        onLog("AI 头像点击事务：进入 AVATAR_CLICK_PREPARING；准备基于当前聊天详情重新定位头像")
        val anchorKey = anchor.opaqueKey()
        sessionComponents.inboxIdentity.checkCaptureAdmission(anchorKey, onLog)?.let { return it }

        // The session candidate is created from the main list, but identity capture is
        // intentionally executed after navigation on the confirmed chat detail page.
        if (sayHiGestureInFlight || sayHiNavigationFlowController.receivedGreetingsEntryTapPending ||
            receivedGreetingsEntrySearchGestureInFlight || sayHiNavigationFlowController.sayHiCardListTapPending
        ) {
            onLog(
                "AI 联系人身份采集等待：CHAT_DETAIL_GESTURE_NOT_READY；" +
                    "gestureInFlight=$sayHiGestureInFlight"
            )
            return ContactIdentityCaptureRequestResult.RETRY_LATER
        }

        if (anchor is ObservedDetailIdentityAnchor) {
            val current = inspectCurrentChatDetailFingerprint(anchor.displayName)
            if (current == null || current.first != anchor.sourceWindow ||
                extractChatPeerName(current.second.chatTitleText) != normalizeIdentityDisplayName(anchor.displayName)
            ) return ContactIdentityCaptureRequestResult.RETRY_LATER
        }

        val pending = PendingInboxIdentityCapture(
            anchor = anchor,
            stage = InboxIdentityCaptureStage.AVATAR_TAP_PENDING,
            lastTransitionAtMs = System.currentTimeMillis()
        )
        sessionComponents.inboxIdentity.registerCapture(anchorKey, pending, onLog)?.let { return it }

        sessionComponents.inboxIdentity.transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_PAGE_READING, "开始读取当前聊天详情页", onLog)
        onLog("AI 头像点击事务：进入 AVATAR_PAGE_READING；读取当前聊天详情页")
        val tree = accessibilityGateway.inspectActiveWindow(
            TreeInspectionRequest(
                maxNodes = 1_800,
                maxDepth = 48,
                maxDurationMs = 1_000L,
                allowVisibleWindowFallback = false,
                textCapture = NodeTextCapture.EPHEMERAL
            )
        ) ?: run {
            onLog("AI 联系人身份采集失败：${MomoAvatarClickFailureReason.CHAT_DETAIL_NOT_STABLE.name}；等待下一次窗口快照")
            sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, "聊天详情节点树暂时不可用", onLog)
            return ContactIdentityCaptureRequestResult.RETRY_LATER
        }
        if (tree.truncated || tree.window.packageName != targetPackage ||
            !accessibilityGateway.isWindowCurrent(tree.window) ||
            (anchor is ObservedDetailIdentityAnchor && tree.window != anchor.sourceWindow)
        ) {
            onLog(
                "AI 联系人身份采集失败：CHAT_DETAIL_WINDOW_NOT_READY；" +
                    "truncated=${tree.truncated}；package=${tree.window.packageName}；" +
                    "activity=${tree.window.activityClass}；current=${accessibilityGateway.isWindowCurrent(tree.window)}"
            )
            sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, "聊天详情页面或窗口暂时不稳定", onLog)
            return ContactIdentityCaptureRequestResult.RETRY_LATER
        }

        sessionComponents.inboxIdentity.transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_LOCATING, "当前聊天详情页已读取", onLog)
        onLog("AI 头像点击事务：进入 AVATAR_LOCATING；从当前节点树重新定位目标头像")
        val index = NodeTreeIndex(tree)
        val target = MomoSayHiAvatarTargetPolicy.resolveConversationAvatarTarget(index)
            ?: run {
                onLog("AI 联系人身份采集失败：${MomoAvatarClickFailureReason.AVATAR_NOT_FOUND.name}；不回退私人列表头像")
                sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, "当前快照暂时未找到目标头像", onLog)
                return ContactIdentityCaptureRequestResult.RETRY_LATER
            }
        onLog("AI 联系人身份采集：已定位聊天详情页左侧消息头像；不使用右上角头像")
        val avatarClickNode = index.nodeAt(target.path)
            ?: run {
                onLog("AI 联系人身份采集失败：${MomoAvatarClickFailureReason.AVATAR_NOT_FOUND.name}；当前快照目标已失效")
                sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, "头像节点在同一快照中失效", onLog)
                return ContactIdentityCaptureRequestResult.RETRY_LATER
            }
        sessionComponents.inboxIdentity.transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_IDENTITY_VERIFYING, "当前头像节点已重新定位", onLog)
        onLog("AI 头像点击事务：进入 AVATAR_IDENTITY_VERIFYING；校验当前页面和头像目标")
        if (!avatarClickNode.visible || target.targetBounds.isEmpty) {
            onLog("AI 联系人身份采集失败：LEFT_AVATAR_TARGET_INVALID；不可见或边界为空")
            sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, "头像目标不可见或边界无效", onLog)
            return ContactIdentityCaptureRequestResult.RETRY_LATER
        }

        val metrics = accessibilityGateway.displayMetrics()
        val executionContext = gesturePolicy.captureContext(
            tree.window,
            metrics.toGestureCoordinateContext()
        ) ?: run {
            onLog("AI 联系人身份采集失败：${MomoAvatarClickFailureReason.WINDOW_CONTEXT_CHANGED.name}；未提交头像点击")
            sessionComponents.inboxIdentity.releaseAvatarCaptureForRetry(pending, "手势坐标上下文暂时不可用", onLog)
            return ContactIdentityCaptureRequestResult.RETRY_LATER
        }
        sessionComponents.inboxIdentity.transitionAvatarClickState(pending, MomoAvatarClickTransactionState.AVATAR_CLICKING, "当前头像目标已通过身份与页面校验", onLog)

        val request = GestureIntentRequest(
            id = "momo-inbox-identity-avatar-${System.nanoTime()}",
            intent = GestureIntent.Tap(
                preferredBounds = target.preferredBounds,
                targetBounds = target.targetBounds,
                durationMs = RECEIVED_GREETINGS_ENTRY_TAP_DURATION_MS,
                feedbackIntent = com.freedom.core.accessibility.api.GestureFeedbackIntent.NAVIGATION_ENTRY
            ),
            description = "陌陌聊天详情：点击对方消息左侧头像采集平台身份（只读）",
            executionContext = executionContext
        )
        val submission = runCatching {
            gesturePolicy.execute(request, GestureExecutionListener { result ->
                sessionComponents.inboxIdentity.applyAvatarGestureResult(pending, result.status, onLog)
                if (result.status == GestureExecutionStatus.COMPLETED) {
                    onLog("AI 联系人身份采集：头像手势回调完成；仅进入资料页复核状态，尚未确认资料页，不读取 ID、不点击整行")
                } else {
                    onLog("AI 联系人身份采集：头像手势未完成，状态=${result.status}；本轮不改点消息行或使用坐标兜底")
                }
            })
        }.getOrElse {
            sessionComponents.inboxIdentity.releaseIfCurrent(pending)
            onLog("AI 联系人身份采集：头像手势执行器异常；" +
                "reason=${MomoAvatarClickFailureReason.AVATAR_GESTURE_REJECTED.name}；重新定位，不执行坐标或整行兜底")
            return ContactIdentityCaptureRequestResult.RETRY_LATER
        }

        if (submission != GestureSubmission.ACCEPTED) {
            sessionComponents.inboxIdentity.transitionAvatarClickState(
                pending,
                MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,
                "头像手势未被接受；等待下一次当前页面重新定位",
                onLog
            )
            sessionComponents.inboxIdentity.releaseIfCurrent(pending)
            val reason = if (submission == GestureSubmission.DEFERRED_NOT_READY) {
                MomoAvatarClickFailureReason.AVATAR_GESTURE_DEFERRED
            } else {
                MomoAvatarClickFailureReason.AVATAR_GESTURE_REJECTED
            }
            onLog("AI 联系人身份采集失败：${reason.name}；submission=$submission；下一次观察重新定位头像")
            return ContactIdentityCaptureRequestResult.RETRY_LATER
        }
        return ContactIdentityCaptureRequestResult.STARTED
    }

    override fun observeContactIdentityCapture(
        candidate: ChatListCandidateSnapshot
    ): ContactIdentityCaptureOutcome {
        onLog("AI 联系人身份采集[OBSERVE]：读取身份终态；name=${candidate.nameText?.trim().orEmpty()}")
        val anchor = createInboxIdentityAnchor(candidate)
            ?: return ContactIdentityCaptureOutcome.failed(
                ContactIdentityCaptureRequestResult.REJECTED,
                "IDENTITY_CAPTURE_CANDIDATE_ANCHOR_INVALID"
            )
        // Preserve the established row entry's verified-identity check and diagnostics.
        return observeContactIdentityCapture(anchor) { requestContactIdentityCapture(candidate) }
    }

    override fun supportsObservedPrivateChatIdentityCapture(): Boolean = true

    private fun createObservedDetailIdentityAnchor(target: ObservedPrivateChatIdentityTarget): ObservedDetailIdentityAnchor? {
        val window = target.sourceWindow() ?: return null
        if (window.packageName != targetPackage) return null
        val peer = extractChatPeerName(target.snapshot.chatTitleText)
        if (peer.isBlank()) return null
        return ObservedDetailIdentityAnchor(peer, window, target.runGeneration, target.visitToken)
    }

    override fun observeContactIdentityCapture(target: ObservedPrivateChatIdentityTarget): ContactIdentityCaptureOutcome {
        val anchor = createObservedDetailIdentityAnchor(target) ?: return ContactIdentityCaptureOutcome.failed(
            ContactIdentityCaptureRequestResult.REJECTED, "OBSERVED_DETAIL_ANCHOR_INVALID"
        )
        return observeContactIdentityCapture(anchor)
    }

    override fun hasPendingObservedPrivateChatIdentityCapture(target: ObservedPrivateChatIdentityTarget): Boolean {
        val key = createObservedDetailIdentityAnchor(target)?.opaqueKey() ?: return false
        return sessionComponents.inboxIdentity.hasObservedCapture(key)
    }

    override fun hasObservedPrivateChatIdentityReturn(target: ObservedPrivateChatIdentityTarget): Boolean {
        val key = createObservedDetailIdentityAnchor(target)?.opaqueKey() ?: return false
        return sessionComponents.inboxIdentity.hasObservedReturn(key)
    }

    override fun consumeObservedPrivateChatIdentityReturn(target: ObservedPrivateChatIdentityTarget): Boolean {
        val key = createObservedDetailIdentityAnchor(target)?.opaqueKey() ?: return false
        return sessionComponents.inboxIdentity.consumeObservedReturn(key)
    }

    override fun cancelObservedPrivateChatIdentityCapture(target: ObservedPrivateChatIdentityTarget) {
        val key = createObservedDetailIdentityAnchor(target)?.opaqueKey() ?: return
        sessionComponents.inboxIdentity.cancelObservedCapture(key)
    }

    private fun observeContactIdentityCapture(
        anchor: InboxIdentityAnchor,
        requestCapture: () -> ContactIdentityCaptureRequestResult = { requestContactIdentityCapture(anchor) }
    ): ContactIdentityCaptureOutcome = sessionComponents.inboxIdentity.observeContactIdentityCapture(anchor, requestCapture, onLog)

    private fun createInboxIdentityAnchor(
        candidate: ChatListCandidateSnapshot
    ): InboxIdentityRowAnchor? {
        if (candidate.selectionClass != "PRIVATE_CHAT" ||
            !candidate.hasReadableName || !candidate.rowVisibleToUser ||
            !candidate.rowBoundsValid || candidate.nameNodePath == null ||
            candidate.rowTarget.window.packageName != targetPackage ||
            candidate.rowTarget.window.activityClass != MomoAppDescriptor.MAIN_ACTIVITY_CLASS
        ) return null
        if (candidate.rowBounds.isEmpty) return null
        return InboxIdentityRowAnchor(
            rowIndex = candidate.rowIndex,
            rowPath = candidate.rowTarget.path.childIndices.toList(),
            displayName = candidate.nameText.orEmpty().trim(),
            rowBounds = candidate.rowBounds
        )
    }

    /** Returns true while the profile-navigation transaction owns the current foreground page. */
    private fun inspectPendingInboxIdentityCapture(): Boolean {
        val pending = sessionComponents.inboxIdentity.pendingInboxIdentityCapture ?: return false
        val currentActivity = activityClass()
        val ageMs = System.currentTimeMillis() - pending.lastTransitionAtMs

        when (pending.stage) {
            InboxIdentityCaptureStage.AVATAR_TAP_PENDING -> {
                if (ageMs <= INBOX_IDENTITY_TAP_TIMEOUT_MS) return true
                if (currentActivity == MomoSayHiPageContract.PROFILE_ACTIVITY) {
                    pending.stage = InboxIdentityCaptureStage.WAITING_PROFILE
                    pending.lastTransitionAtMs = System.currentTimeMillis()
                    sessionComponents.inboxIdentity.transitionAvatarClickState(
                        pending,
                        MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING,
                        "超时窗口内观察到资料页",
                        onLog
                    )
                    return true
                }
                if (currentActivity == MomoAppDescriptor.MAIN_ACTIVITY_CLASS) {
                    sessionComponents.inboxIdentity.releaseAvatarCaptureAfterProfileMiss(
                        pending,
                        MomoAvatarClickFailureReason.AVATAR_GESTURE_COMPLETED_BUT_PROFILE_NOT_OPENED,
                        onLog
                    )
                    return false
                }
                return true
            }

            InboxIdentityCaptureStage.WAITING_PROFILE -> {
                if (pending.profileScrollInFlight) return true
                if (pending.profileScrollSettledAtMs > 0L &&
                    System.currentTimeMillis() - pending.profileScrollSettledAtMs < SAYHI_PROFILE_SCROLL_SETTLE_MS
                ) return true

                if (pending.backAttempts >= MAX_SAYHI_PROFILE_BACK_ATTEMPTS) return true

                val dialogObservation = latestDialogWindowObservation()
                if (dialogObservation != null &&
                    rootAccess.isTargetWindowPresent(dialogObservation.windowId) != false
                ) {
                    val back = submitVerifiedProfileBackNavigation(MomoAppDescriptor.CHAT_DETAIL_ACTIVITY_CLASS) ?: return true
                    pending.backAttempts++
                    pending.lastTransitionAtMs = System.currentTimeMillis()
                    // As in Received Greetings, dismiss a verified overlay first; only the next
                    // clean profile observation may be parsed and returned from.
                    if (!back.dialogHPresent) {
                        pending.stage = InboxIdentityCaptureStage.WAITING_LIST_RETURN
                    }
                    return true
                }

                if (!pending.profileReadCompleted) {
                    val tree = accessibilityGateway.inspectActiveWindow(
                        TreeInspectionRequest(
                            maxNodes = 1_800,
                            maxDepth = 48,
                            maxDurationMs = 1_000L,
                            allowVisibleWindowFallback = false,
                            textCapture = NodeTextCapture.EPHEMERAL
                        )
                    )
                    if (tree == null || tree.truncated || tree.window.packageName != targetPackage ||
                        tree.window.windowId < 0 || !accessibilityGateway.isWindowCurrent(tree.window)
                    ) return true

                    val readout = MomoSayHiPlatformIdentityParser.parse(tree.nodes)
                    if (inspectCurrentChatDetailFingerprint() != null || isReceivedGreetingsListConfirmed()) {
                        if (ageMs > SAYHI_PROFILE_PAGE_TIMEOUT_MS) {
                            sessionComponents.inboxIdentity.releaseAvatarCaptureAfterProfileMiss(
                                pending, MomoAvatarClickFailureReason.PROFILE_PAGE_NOT_CONFIRMED,
                                onLog
                            )
                            return false
                        }
                        return true
                    }
                    if (!accessibilityGateway.isWindowCurrent(tree.window)) return true
                    pending.platformUserId = readout.value.takeIf {
                        readout.status == MomoSayHiPlatformIdentityStatus.CONFIRMED
                    }
                    if (pending.platformUserId != null) {
                        pending.profileEntryVerified = true
                        pending.profileReadCompleted = true
                        onLog("AI 联系人身份采集：资料页已读取唯一平台 ID；原始 ID 不写入运行日志")
                    } else if (!pending.profileScrollAttempted) {
                        if (!isIdentityProfileActionWindow(tree)) return true
                        if (!pending.profileDeferredRetryGate.mayAttempt(tree.window, SystemClock.uptimeMillis())) return true
                        if (dispatchInboxProfileIdentityScroll(pending, tree)) return true
                        if (ageMs < SAYHI_PROFILE_PAGE_TIMEOUT_MS) return true
                        pending.profileReadCompleted = true
                    } else {
                        pending.profileReadCompleted = true
                        onLog("AI 联系人身份采集：单次上滑后仍未取得唯一 ID；保留可重试状态，不关联历史")
                    }
                }

                val back = submitVerifiedProfileBackNavigation(MomoAppDescriptor.CHAT_DETAIL_ACTIVITY_CLASS) ?: return true
                pending.backAttempts++
                pending.lastTransitionAtMs = System.currentTimeMillis()
                if (!back.dialogHPresent) {
                    pending.stage = InboxIdentityCaptureStage.WAITING_LIST_RETURN
                    onLog("AI 身份回接[REQUEST_WAITING]：result=NOT_PRODUCED；reason=AWAITING_VERIFIED_CHAT_RETURN")
                }
                return true
            }

            InboxIdentityCaptureStage.WAITING_LIST_RETURN -> {
                // The user may leave the chat detail manually before the profile
                // transaction has confirmed its expected return. Do not let this
                // stale identity transaction hide the message-list fingerprint:
                // the unified recovery router must be able to observe the new page
                // and decide whether the original task can resume.
                if (currentActivity == MomoAppDescriptor.MAIN_ACTIVITY_CLASS &&
                    !isReceivedGreetingsListStructureVisible()
                ) {
                    sessionComponents.inboxIdentity.failPendingInboxIdentityCapture(
                        pending,
                        "MANUAL_RETURN_TO_MESSAGE_LIST",
                        onLog
                    )
                    onLog(
                        "AI 联系人身份采集[MANUAL_RETURN_DETECTED]：" +
                            "已离开聊天详情页；释放过期身份事务，允许统一页面恢复读取消息列表指纹"
                    )
                    return false
                }
                // The chat detail may be hosted by ChatActivity or wrapped by MaintabActivity.
                // Inspect the stable three-anchor chat fingerprint directly; calling
                // inspectChatPage() here would re-enter this pending transaction and keep
                // returning UNKNOWN.
                val restoredEvidence = inspectCurrentChatDetailFingerprint(pending.anchor.displayName)
                val restoredChat = restoredEvidence?.second
                val restoredTitle = extractChatPeerName(restoredChat?.chatTitleText)
                val anchorTitle = normalizeIdentityDisplayName(pending.anchor.displayName)
                val detailAnchor = pending.anchor as? ObservedDetailIdentityAnchor
                // Without list-row evidence, require the actual original chat window ID too.
                val originalDetailWindow = detailAnchor == null ||
                    (restoredEvidence?.first?.packageName == detailAnchor.sourceWindow.packageName &&
                        restoredEvidence?.first?.windowId == detailAnchor.sourceWindow.windowId)
                val restored = restoredChat != null && originalDetailWindow &&
                    restoredTitle.isNotBlank() && anchorTitle.isNotBlank() &&
                    restoredTitle == anchorTitle
                if (restored) {
                    if (!pending.profileEntryVerified) {
                        onLog("AI 身份回接[REQUEST_NOT_PRODUCED]：result=NOT_PRODUCED；reason=PROFILE_ENTRY_NOT_VERIFIED")
                        sessionComponents.inboxIdentity.releaseAvatarCaptureAfterProfileMiss(
                            pending,
                            MomoAvatarClickFailureReason.PROFILE_PAGE_NOT_CONFIRMED,
                            onLog
                        )
                        return false
                    }
                    sessionComponents.inboxIdentity.completePendingInboxIdentityBinding(
                        pending,
                        pending.platformUserId != null,
                        onLog
                    )
                    sessionComponents.inboxIdentity.transitionAvatarClickState(
                        pending,
                        MomoAvatarClickTransactionState.AVATAR_COMPLETED,
                        "资料页身份读取完成并返回原聊天详情",
                        onLog
                    )
                    sessionComponents.inboxIdentity.publishVerifiedReturn(pending)
                    onLog(
                        "AI 身份回接[REQUEST_PRODUCED]：result=PRODUCED；" +
                            "reason=ORIGINAL_CHAT_FINGERPRINT_AND_TITLE_VERIFIED；" +
                            "profileIdRead=${!pending.platformUserId.isNullOrBlank()}；不记录原始用户ID"
                    )
                    onLog("AI 联系人身份采集：已返回并确认原私人聊天详情页；三节点指纹和聊天标题匹配；继续当前窗口回复流程")
                    return false
                }
                if (currentActivity == MomoSayHiPageContract.PROFILE_ACTIVITY &&
                    ageMs > SAYHI_PROFILE_PAGE_TIMEOUT_MS &&
                    pending.backAttempts < MAX_SAYHI_PROFILE_BACK_ATTEMPTS
                ) {
                    // A submitted BACK is not proof that the profile was left. Allow one more
                    // independently revalidated, popup-safe attempt after the bounded wait.
                    val back = submitVerifiedProfileBackNavigation(MomoAppDescriptor.CHAT_DETAIL_ACTIVITY_CLASS) ?: return true
                    pending.backAttempts++
                    pending.lastTransitionAtMs = System.currentTimeMillis()
                    if (back.dialogHPresent) {
                        pending.stage = InboxIdentityCaptureStage.WAITING_PROFILE
                    }
                }
                if (ageMs > SAYHI_PROFILE_PAGE_TIMEOUT_MS) {
                    sessionComponents.inboxIdentity.failPendingInboxIdentityCapture(
                        pending,
                        "RETURNED_WINDOW_NOT_ORIGINAL_CHAT_DETAIL",
                        onLog
                    )
                    onLog("AI 联系人身份采集终态：返回页未通过聊天三节点指纹/标题确认；降级当前窗口消息，不阻断回复生成")
                    return false
                }
                return true
            }
        }
    }

    private fun dispatchInboxProfileIdentityScroll(
        pending: PendingInboxIdentityCapture,
        tree: AccessibilityTreeSnapshot
    ): Boolean {
        val metrics = accessibilityGateway.displayMetrics()
        val decision = MomoSayHiProfileScrollPolicy.resolveIdentityViewport(
            nodes = tree.nodes,
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels
        )
        val viewport = decision.viewport ?: run {
            onLog(
                "AI 联系人身份采集失败：PROFILE_SCROLL_VIEWPORT_UNAVAILABLE；" +
                    "candidates=${decision.candidateCount}；reason=${decision.reason}"
            )
            return false
        }
        if (!isIdentityProfileActionWindow(tree)) {
            onLog("AI 联系人身份采集失败：PROFILE_SCROLL_WINDOW_NOT_CURRENT；不使用旧窗口")
            return false
        }
        val executionContext = gesturePolicy.captureContext(
            tree.window,
            metrics.toGestureCoordinateContext()
        ) ?: run {
            onLog("AI 联系人身份采集失败：PROFILE_SCROLL_CONTEXT_UNAVAILABLE；不使用旧视口")
            return false
        }
        val request = GestureIntentRequest(
            id = "momo-inbox-profile-id-scroll-${System.nanoTime()}",
            intent = GestureIntent.VerticalSwipe(
                viewport = viewport,
                direction = VerticalSwipeDirection.UP,
                durationMs = SAYHI_PROFILE_IDENTITY_SCROLL_DURATION_MS
            ),
            description = "陌陌私人会话资料页身份字段只读上滑",
            executionContext = executionContext
        )
        pending.profileScrollWindowId = tree.window.windowId
        pending.profileScrollInFlight = true
        val submission = runCatching {
            gesturePolicy.execute(request, GestureExecutionListener { result ->
                if (sessionComponents.inboxIdentity.pendingInboxIdentityCapture !== pending) return@GestureExecutionListener
                pending.lastTransitionAtMs = System.currentTimeMillis()
                when (result.status) {
                    GestureExecutionStatus.COMPLETED -> {
                        pending.profileScrollAttempted = true
                        pending.profileDeferredRetryGate.reset()
                        pending.profileScrollSettledAtMs = System.currentTimeMillis()
                        onLog("AI 联系人身份采集[PROFILE_SCROLL_COMPLETED]：上滑完成；等待资料页二次节点扫描")
                    }
                    GestureExecutionStatus.DEFERRED -> {
                        pending.profileDeferredRetryGate.onDeferred(request.id, tree.window, SystemClock.uptimeMillis())
                        onLog("AI 联系人身份采集：上滑暂缓，等待新快照，不消耗滑动机会")
                    }
                    else -> {
                        pending.profileReadCompleted = true
                        onLog("AI 联系人身份采集失败：PROFILE_SCROLL_${result.status.name}；不重复滑动")
                    }
                }
                pending.profileScrollInFlight = false
                pending.profileScrollWindowId = -1
            })
        }.getOrElse {
            pending.profileScrollInFlight = false
            pending.profileScrollWindowId = -1
            pending.profileReadCompleted = true
            onLog("AI 联系人身份采集失败：PROFILE_SCROLL_EXECUTION_EXCEPTION；exception=${it.javaClass.simpleName}")
            return true
        }
        if (submission != GestureSubmission.ACCEPTED) {
            pending.profileScrollInFlight = false
            pending.profileScrollWindowId = -1
            if (submission == GestureSubmission.DEFERRED_NOT_READY) {
                pending.profileDeferredRetryGate.onDeferred(request.id, tree.window, SystemClock.uptimeMillis())
            } else {
                pending.profileReadCompleted = true
            }
            onLog("AI 联系人身份采集失败：PROFILE_SCROLL_SUBMISSION_${submission.name}")
        }
        return true
    }

    /** A stop or new run invalidates every received-greetings gesture callback from its predecessor. */
    private val sayHiRunGenerationGuard = MomoSayHiRunGenerationGuard()

    /**
     * Only volatile, adapter-owned received-greetings state is cleared here. Never erase
     * sayHiTraversalState checkpoints or the durable send/return journal.
     */
    private fun resetReceivedGreetingsRunState(reason: String) {
        receivedGreetingsEntryPositionMemory.endRun()
        val newGeneration = sayHiRunGenerationGuard.invalidatePreviousRun()
        completionBackStage = CompletionBackStage.NONE
        completionBackRetryGate.reset()
        val previousStage = sayHiReadOnlyStage.name
        val previousGestureInFlight = sayHiGestureInFlight
        val previousEntrySearchInFlight = receivedGreetingsEntrySearchGestureInFlight

        resetSayHiListSnapshotState()
        sayHiNavigationFlowController.reset()
        sayHiAutonomousBrowseController.reset()
        resetReceivedGreetingsEntrySearch(resetRun = true)
        clearSayHiSelectedGreeting()
        resetSayHiRowTapTransaction()
        sayHiRestoredReturnConfirmation.reset()
        sayHiTraversalState.resetFilterSkips()
        sayHiTraversalState.sayHiFilterSettingsUnavailableLogged = false

        sayHiNonSearchGestureInFlight = false
        sayHiTraversalState.sayHiDeferredSafeEvidenceOnlyWait = false
        sayHiStableCandidateKey = ""
        sayHiStableCandidateTarget = null
        sayHiStableCandidateWindow = null
        sayHiTraversalState.sayHiPendingVerifiedReturnRow = null
        synchronized(this) {
            sayHiTraversalState.clearRunReturnReceipts()
        }

        sayHiNavigationFlowController.resetEntryNavigationRuntime()
        lastSayHiInspectionSignature = ""
        lastSayHiInspectionAtMs = 0L
        lastSayHiNavigationEvidenceSignature = ""
        lastMomoNavigationEvidenceAtMs = 0L

        onLog(
            "收到招呼运行时状态清理[$reason]：" +
                "generation=$newGeneration;previousStage=$previousStage;" +
                "previousGestureInFlight=$previousGestureInFlight;" +
                "previousEntrySearchInFlight=$previousEntrySearchInFlight;" +
                "checkpointPreserved=true;sendJournalPreserved=true"
        )
    }

    override fun beginSessionManagement(): String? = runCatching {
        resetReceivedGreetingsRunState("BEGIN_RUN")
        sessionComponents.inboxIdentity.resetForRun()
        sessionComponents.sampleStore.beginRun().also { receivedGreetingsEntryPositionMemory.beginRun() }
    }.onFailure { error ->
        diagnostics.recordException(
            component = "MomoSocialAdapter",
            eventType = "sample_archive_begin_failed",
            message = "AI 会话样本归档初始化失败",
            error = error
        )
    }.getOrNull()

    /** Called by the Service log boundary; records only while an AI collection run is open. */
    fun recordRuntimeLog(message: String) {
        sessionComponents.sampleStore.recordRuntimeLog(message)
    }

    override fun recordAccessibilityEvent(event: AccessibilityEventEnvelope) {
        handleSayHiRowTapClickEvidence(event)
        sessionComponents.sampleStore.recordAccessibilityEvent(event)
    }

    override fun finishSessionManagement(): String? {
        resetReceivedGreetingsRunState("FINISH_RUN")
        sessionComponents.inboxIdentity.resetForRun()
        return runCatching { sessionComponents.sampleStore.finishRun() }.getOrNull()
    }

    override fun blockSessionIdentityKey(sessionKey: String) {
        sessionComponents.sampleStore.blockSessionIdentityKey(sessionKey)
    }

    override fun isSessionIdentityBlocked(sessionKey: String): Boolean =
        sessionComponents.sampleStore.isSessionIdentityBlocked(sessionKey)

    override fun persistVerifiedSessionListMetadata(snapshot: ChatPageSnapshot): Boolean {
        // The same sample store and schema are used. Do not skip visual evidence
        // when any selectable row still has a weak or missing platform identity.
        if (snapshot.chatListCandidates.none(ChatSessionManagementPolicy::isEligible) ||
            snapshot.chatListCandidates.any {
                ChatSessionManagementPolicy.isEligible(it) &&
                    (it.contactIdentity?.confidence != ContactIdentityConfidence.PLATFORM_ID_VERIFIED ||
                        it.contactIdentity?.contactId.isNullOrBlank())
            }
        ) return false
        return runCatching {
            sessionComponents.sampleStore.persistSessionList(snapshot) > 0
        }.getOrDefault(false)
    }

    override fun captureSessionListSample(
        snapshot: ChatPageSnapshot,
        onComplete: (String?) -> Unit
    ) {
        val sampleIndex = runCatching {
            sessionComponents.sampleStore.persistSessionList(snapshot)
        }.getOrDefault(0)
        val capture = screenshotCapture
        if (capture == null || sampleIndex <= 0) {
            onComplete(null)
            return
        }
        MonitorOverlay.acquireObservationVisibility(context) { observationLease ->
            runCatching {
                capture.captureActiveDisplay(snapshot.packageName, snapshot.activityClass) { frame ->
                    val path = if (frame == null) null else try {
                        sessionComponents.sampleStore.saveBitmap("session_list_$sampleIndex", frame.bitmap)
                    } finally {
                        runCatching { frame.bitmap.recycle() }
                        frame.close()
                    }
                    try {
                        sessionComponents.sampleStore.persistSessionListScreenshot(sampleIndex, path, snapshot)
                        onComplete(path)
                    } finally {
                        observationLease.close()
                    }
                }
            }.onFailure {
                observationLease.close()
                onComplete(null)
            }
        }
    }

    override fun resolveSessionTapBounds(
        snapshot: ChatPageSnapshot,
        candidate: ChatListCandidateSnapshot
    ): ScreenRect? {
        if (snapshot.pageKind != ChatPageKind.CHAT_LIST ||
            snapshot.packageName != targetPackage ||
            candidate.rowTarget.window.packageName != targetPackage ||
            !candidate.rowBoundsValid || !candidate.intersectsViewport ||
            !candidate.fullyInsideViewport || !candidate.hasReadableName
        ) return null

        return rootAccess.withActiveTargetRoot { root, window ->
            if (!sameMomoWindowGeneration(window, candidate.rowTarget.window) ||
                safeString { root.packageName?.toString().orEmpty() } != targetPackage
            ) return@withActiveTargetRoot null

            var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(root)
            try {
                for (index in candidate.rowTarget.path.childIndices) {
                    val parent = current ?: return@withActiveTargetRoot null
                    val child = safeNode { parent.getChild(index) }
                    runCatching { parent.recycle() }
                    current = child
                    if (child == null) return@withActiveTargetRoot null
                }
                val row = current ?: return@withActiveTargetRoot null
                val actualClass = safeString { row.className?.toString().orEmpty() }
                val actualViewId = safeString { row.viewIdResourceName.orEmpty() }
                val bounds = Rect()
                runCatching { row.getBoundsInScreen(bounds) }
                val currentBounds = bounds.toScreenRect()
                val viewport = snapshot.chatListViewport
                val geometryMatches = viewport != null &&
                    MomoChatListSessionPolicy.hasValidBounds(currentBounds) &&
                    MomoChatListSessionPolicy.fullyInsideViewport(currentBounds, viewport) &&
                    abs(currentBounds.left - candidate.rowBounds.left) <= SESSION_ROW_BOUNDS_TOLERANCE_PX &&
                    abs(currentBounds.top - candidate.rowBounds.top) <= SESSION_ROW_BOUNDS_TOLERANCE_PX &&
                    abs(currentBounds.right - candidate.rowBounds.right) <= SESSION_ROW_BOUNDS_TOLERANCE_PX &&
                    abs(currentBounds.bottom - candidate.rowBounds.bottom) <= SESSION_ROW_BOUNDS_TOLERANCE_PX
                if (!geometryMatches ||
                    (candidate.rowTarget.expectedViewId != null && actualViewId != candidate.rowTarget.expectedViewId) ||
                    (candidate.rowTarget.expectedClassName != null && actualClass != candidate.rowTarget.expectedClassName) ||
                    !safeBoolean { row.isEnabled } || !safeBoolean { row.isClickable }
                ) return@withActiveTargetRoot null

                val namePath = candidate.nameNodePath ?: return@withActiveTargetRoot null
                val nameNode = chatComponents.listSessionExtractor.resolveLocalPath(row, namePath.childIndices.drop(candidate.rowTarget.path.childIndices.size))
                    ?: return@withActiveTargetRoot null
                try {
                    val currentName = safeString { nameNode.text?.toString().orEmpty() }
                        .ifBlank { safeString { nameNode.contentDescription?.toString().orEmpty() } }
                        .trim()
                    if (currentName != candidate.nameText?.trim()) return@withActiveTargetRoot null
                } finally {
                    runCatching { nameNode.recycle() }
                }
                // Reacquire the floating control's current physical bounds immediately before
                // returning a tap region. A row overlapping the control is restricted to the
                // largest remaining rectangle; an incomplete overlay scan fails closed.
                val overlay = chatComponents.listSessionExtractor.inspectAiFinderFloatingOverlay(root, targetPackage)
                if (!overlay.complete) return@withActiveTargetRoot null
                if (!overlay.foundVisible) {
                    currentBounds
                } else {
                    MomoChatListSessionPolicy.largestSafeSubRect(
                        bounds = currentBounds,
                        excludedBounds = overlay.bounds,
                        minimumWidth = MIN_SAFE_SESSION_TAP_WIDTH_PX,
                        minimumHeight = MIN_SAFE_SESSION_TAP_HEIGHT_PX
                    )
                }
            } finally {
                runCatching { current?.recycle() }
            }
        }
    }

    override fun resolveFireTapBounds(
        snapshot: ChatPageSnapshot,
        candidate: ChatListCandidateSnapshot
    ): ScreenRect? {
        val firePath = candidate.fireNodePath ?: return null
        if (snapshot.pageKind != ChatPageKind.CHAT_LIST ||
            snapshot.packageName != targetPackage ||
            !candidate.rowBoundsValid || !candidate.fullyInsideViewport ||
            !candidate.hasReadableName
        ) return null
        return rootAccess.withActiveTargetRoot { root, window ->
            if (!sameMomoWindowGeneration(window, candidate.rowTarget.window) ||
                safeString { root.packageName?.toString().orEmpty() } != targetPackage
            ) return@withActiveTargetRoot null
            var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(root)
            try {
                for (index in firePath.childIndices) {
                    val parent = current ?: return@withActiveTargetRoot null
                    val child = safeNode { parent.getChild(index) }
                    runCatching { parent.recycle() }
                    current = child
                    if (child == null) return@withActiveTargetRoot null
                }
                val fire = current ?: return@withActiveTargetRoot null
                val viewId = safeString { fire.viewIdResourceName.orEmpty() }
                val bounds = Rect()
                runCatching { fire.getBoundsInScreen(bounds) }
                val liveBounds = bounds.toScreenRect()
                if (!MomoChatListSessionPolicy.isFireFeatureViewId(viewId) ||
                    !MomoChatListSessionPolicy.hasValidBounds(liveBounds) ||
                    !MomoChatListSessionPolicy.fullyInsideViewport(liveBounds, snapshot.chatListViewport ?: return@withActiveTargetRoot null) ||
                    candidate.fireBounds?.let { expected ->
                        abs(expected.left - liveBounds.left) > SESSION_ROW_BOUNDS_TOLERANCE_PX ||
                            abs(expected.top - liveBounds.top) > SESSION_ROW_BOUNDS_TOLERANCE_PX ||
                            abs(expected.right - liveBounds.right) > SESSION_ROW_BOUNDS_TOLERANCE_PX ||
                            abs(expected.bottom - liveBounds.bottom) > SESSION_ROW_BOUNDS_TOLERANCE_PX
                    } == true
                ) return@withActiveTargetRoot null
                liveBounds
            } finally {
                runCatching { current?.recycle() }
            }
        }
    }

    override fun resolveSessionListSwipeViewport(snapshot: ChatPageSnapshot): ScreenRect? {
        if (snapshot.pageKind != ChatPageKind.CHAT_LIST ||
            snapshot.packageName != targetPackage
        ) return null
        val viewport = snapshot.chatListViewport ?: return null
        val expectedWindow = snapshot.messageListTarget?.window
            ?: snapshot.chatListCandidates.firstOrNull()?.rowTarget?.window
            ?: snapshot.sessionListNavigationTarget?.nodeTarget?.window
            ?: return null
        return rootAccess.withActiveTargetRoot { root, window ->
            if (!sameMomoWindowGeneration(window, expectedWindow) ||
                safeString { root.packageName?.toString().orEmpty() } != targetPackage
            ) return@withActiveTargetRoot null
            val overlay = chatComponents.listSessionExtractor.inspectAiFinderFloatingOverlay(root, targetPackage)
            if (!overlay.complete) return@withActiveTargetRoot null
            val auxiliaryBands = chatComponents.listSessionExtractor.findVisibleAuxiliaryRecyclerBounds(root, viewport, targetPackage)
                ?: return@withActiveTargetRoot null
            val mainListBand = MomoChatListSessionPolicy.largestVerticalBandExcluding(
                bounds = viewport,
                excludedBounds = auxiliaryBands,
                minimumWidth = MIN_SAFE_SESSION_SWIPE_WIDTH_PX,
                minimumHeight = MIN_SAFE_SESSION_SWIPE_HEIGHT_PX
            ) ?: return@withActiveTargetRoot null
            if (!overlay.foundVisible) {
                mainListBand
            } else {
                MomoChatListSessionPolicy.largestSafeSubRect(
                    bounds = mainListBand,
                    excludedBounds = overlay.bounds,
                    minimumWidth = MIN_SAFE_SESSION_SWIPE_WIDTH_PX,
                    minimumHeight = MIN_SAFE_SESSION_SWIPE_HEIGHT_PX
                )
            }
        }
    }

    override fun persistChatSample(
        snapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        sessionName: String?
    ): Int = persistChatSample(snapshot, batch, sessionName, null)

    override fun persistChatSample(
        snapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        sessionName: String?,
        contactIdentity: ContactRowIdentity?
    ): Int {
        val sampleIndex = runCatching {
            sessionComponents.sampleStore.persistChatBatch(
                snapshot = snapshot,
                batch = batch,
                sessionName = sessionName,
                contactIdentity = contactIdentity,
                greetingRowKeyHash = receivedGreetingsCurrentRowKeyHash(),
                greetingIdentityStatus = receivedGreetingsCurrentIdentityStatus()
            )
        }.getOrDefault(0)
        persistConversationMessagesIfVerified(snapshot, batch, sessionName, contactIdentity)
        // Structural-only or non-text conversations can have no OCR targets. Still preserve a
        // full-screen reference image for those samples so the exported record is reviewable.
        if (sampleIndex > 0 && batch.sampleScreenshotPath.isNullOrBlank()) {
            val capture = screenshotCapture
            if (capture != null) {
                MonitorOverlay.acquireObservationVisibility(context) { observationLease ->
                    runCatching {
                        capture.captureActiveDisplay(snapshot.packageName, snapshot.activityClass) { frame ->
                            val path = if (frame == null) null else try {
                                sessionComponents.sampleStore.saveBitmap("chat_screen_$sampleIndex", frame.bitmap)
                            } finally {
                                runCatching { frame.bitmap.recycle() }
                                frame.close()
                            }
                            try {
                                sessionComponents.sampleStore.persistChatScreenshot(
                                    sampleIndex,
                                    path,
                                    "structural_read_without_ocr_capture"
                                )
                            } finally {
                                observationLease.close()
                            }
                        }
                    }.onFailure { observationLease.close() }
                }
            }
        }
        return sampleIndex
    }

    /** 使用当前行或昵称找回的已采集平台联系人身份保存消息。 */
    private fun persistConversationMessagesIfVerified(
        snapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        sessionName: String?,
        identity: ContactRowIdentity?
    ) {
        val contactId = identity?.contactId?.takeIf(String::isNotBlank) ?: return
        if (identity.confidence != ContactIdentityConfidence.PLATFORM_ID_VERIFIED) return
        if (batch.windowSessionKey.isNullOrBlank() || batch.messages.isEmpty()) return

        val inputs = batch.messages.map { message ->
            ConversationMessageInput(
                messageFingerprint = message.stableLogicalMessageId,
                direction = message.direction.name,
                type = message.type.name,
                content = message.content,
                contentQuality = message.contentQuality.name,
                directionEvidence = message.directionEvidence.name,
                bounds = message.bounds,
                contentRevision = message.effectiveContentRevision,
                observationBatchId = batch.effectiveObservationBatchId,
                observationRowIndex = message.observationRowIndex
            )
        }
        runCatching {
            conversationRepository.persistMessages(
                contactId = contactId,
                conversationId = "momo-direct-$contactId",
                identityConfidence = identity.confidence.name,
                capturedAtMillis = batch.capturedAtMillis,
                messages = inputs
            )
        }.onSuccess { result ->
            if (result.insertedCount > 0 || result.rejectedCount > 0) {
                onLog(
                    "会话消息本地存储：新增=${result.insertedCount}；" +
                        "重复观察=${result.duplicateObservationCount}；拒绝=${result.rejectedCount}；" +
                        "身份可信级别=${identity.confidence.name}"
                )
            }
        }.onFailure {
            onLog("会话消息本地持久化失败；未记录消息正文")
        }
    }

    override fun persistChatHeaderValidationDiagnostic(
        snapshot: ChatPageSnapshot,
        expectedSessionName: String?,
        matched: Boolean,
        expectedCandidate: ChatListCandidateSnapshot?
    ) {
        runCatching {
            sessionComponents.sampleStore.persistChatHeaderValidationDiagnostic(
                snapshot, expectedSessionName, matched, expectedCandidate
            )
        }
    }

    override fun navigateBackToSessionList(): Boolean =
        requestChatActivityBack("普通聊天消息采集完成", targetActivityClass = MomoAppDescriptor.MAIN_ACTIVITY_CLASS)

    override fun refreshKnownChatRegions(previous: ChatPageSnapshot): ChatRegionSnapshot {
        val messageListTarget = previous.messageListTarget
        val inputFieldTarget = previous.inputFieldTarget
        val previousWindow = messageListTarget?.window
            ?: inputFieldTarget?.window
        val bothTargetsShareWindow = messageListTarget == null ||
            inputFieldTarget == null ||
            messageListTarget.window == inputFieldTarget.window
        if (previousWindow == null || !bothTargetsShareWindow) {
            return ChatRegionSnapshot(false, 0, 0, 0, 0, false)
        }

        val previousInspection = MomoChatPageContract.previousRegionInspection(previous, messageListTarget, inputFieldTarget)

        return rootAccess.withActiveTargetRoot { root, currentWindow ->
            // Node paths are meaningful only inside the exact window generation in which
            // they were observed. A page transition requests a full, bounded inspection.
            if (currentWindow != previousWindow) {
                return@withActiveTargetRoot ChatRegionSnapshot(false, 0, 0, 0, 0, false)
            }
            val refreshed = chatComponents.pageInspector.inspectKnownChatRegions(
                root = AccessibilityNodeInfo.obtain(root),
                targetPackage = targetPackage,
                previous = previousInspection
            )
            MomoChatPageContract.refreshedRegionSnapshot(refreshed)
        } ?: ChatRegionSnapshot(false, 0, 0, 0, 0, false)
    }

    override fun invalidateOcrRecognitionReuse() {
        // Do not initialize the lazy OCR processor just to retire recognition evidence.
        if (messageComponents.ocrProcessorDelegate.isInitialized()) {
            messageComponents.ocrProcessor.invalidateRecognitionReuse()
        }
    }

    override fun readRecentMessages(page: ChatPageSnapshot, limit: Int): ChatMessageBatch =
        messageComponents.reader.readRecentMessages(page, limit)

    override fun readRecentMessagesStructureAsync(
        page: ChatPageSnapshot,
        limit: Int,
        onResult: (ChatMessageBatch) -> Unit
    ) {
        // Structural stage only. In particular, do not acquire the screenshot/OCR processor
        // here: identity navigation and chat-page restoration must complete first.
        onResult(runCatching {
            messageComponents.reader.readRecentMessages(page, limit)
        }.getOrElse {
            messageComponents.reader.structuralReadFailure()
        })
    }

    override fun readRecentMessagesAsync(
        page: ChatPageSnapshot,
        limit: Int,
        onResult: (ChatMessageBatch) -> Unit
    ) = readRecentMessagesAsync(page, limit, { true }, onResult)

    override fun readRecentMessagesAsync(
        page: ChatPageSnapshot,
        limit: Int,
        isRequestCurrent: () -> Boolean,
        onResult: (ChatMessageBatch) -> Unit
    ) = readRecentMessagesWithOcr(page, limit, false, isRequestCurrent, null, onResult)

    override fun readRecentMessagesForSendConfirmationAsync(
        page: ChatPageSnapshot,
        limit: Int,
        onResult: (ChatMessageBatch) -> Unit
    ) = readRecentMessagesForSendConfirmationAsync(page, limit, { true }, onResult)

    override fun readRecentMessagesForSendConfirmationAsync(
        page: ChatPageSnapshot,
        limit: Int,
        isRequestCurrent: () -> Boolean,
        onResult: (ChatMessageBatch) -> Unit
    ) = readRecentMessagesForSendConfirmationAsync(page, limit, isRequestCurrent, null, onResult)

    override fun readRecentMessagesForSendConfirmationAsync(
        page: ChatPageSnapshot,
        limit: Int,
        isRequestCurrent: () -> Boolean,
        confirmedBaselineObservationId: String?,
        onResult: (ChatMessageBatch) -> Unit
    ) = readRecentMessagesWithOcr(page, limit, true, isRequestCurrent, confirmedBaselineObservationId, onResult)

    private fun readRecentMessagesWithOcr(
        page: ChatPageSnapshot,
        limit: Int,
        reuseVerifiedIncoming: Boolean,
        isRequestCurrent: () -> Boolean,
        confirmedBaselineObservationId: String?,
        onResult: (ChatMessageBatch) -> Unit
    ) {
        val expectedWindow = page.messageListTarget?.window
        // Read-only live root identity, not a new page recognizer or a full tree traversal.
        // Once an observation is lost, the old screenshot may never become current again.
        val isCurrent = retainOcrReadValidity {
            isRequestCurrent() && expectedWindow != null && expectedWindow.windowId >= 0 &&
                rootAccess.withActiveTargetRoot { _, current ->
                    current.packageName == expectedWindow.packageName &&
                        current.windowId == expectedWindow.windowId &&
                        current.generation == expectedWindow.generation
                } == true && isRequestCurrent()
        }
        fun staleBatch() = ChatMessageBatch(
            status = MessageReadStatus.WINDOW_STALE,
            windowSessionKey = null,
            messages = emptyList(),
            scannedRowCount = 0,
            candidateRowCount = 0,
            truncated = false,
            capturedAtMillis = System.currentTimeMillis(),
            messageListUnavailableReason = "OCR_READ_INVALIDATED"
        )
        // Hide the monitor before the structural node read, not only before the screenshot.
        // The nested screenshot lease in AndroidAccessibilityGateway keeps the same hidden
        // state through capture/OCR; reference counting prevents either layer restoring early.
        MonitorOverlay.acquireObservationVisibility(context) { observationLease ->
            val resultDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
            val deliver: (ChatMessageBatch) -> Unit = { batch ->
                if (resultDelivered.compareAndSet(false, true)) {
                    try {
                        onResult(if (isCurrent()) batch else staleBatch())
                    } catch (_: Exception) {
                        onLog("AI 模式消息读取结果回调异常；监控悬浮窗已安排恢复")
                    } finally {
                        observationLease.close()
                    }
                }
            }

            if (!isCurrent()) {
                onLog("OCR_BATCH_INVALIDATED stage=BEFORE_STRUCTURE;nextRequest=false")
                deliver(staleBatch())
                return@acquireObservationVisibility
            }
            val structuralBatch = try {
                messageComponents.reader.readRecentMessages(page, limit)
            } catch (_: Exception) {
                invalidateOcrRecognitionReuse()
                deliver(
                    ChatMessageBatch(
                        status = com.freedom.features.aireply.domain.message.MessageReadStatus.READ_FAILED,
                        windowSessionKey = null,
                        messages = emptyList(),
                        scannedRowCount = 0,
                        candidateRowCount = 0,
                        truncated = false,
                        capturedAtMillis = System.currentTimeMillis(),
                        messageListUnavailableReason = "消息结构读取异常（异常详情已隐藏）"
                    )
                )
                return@acquireObservationVisibility
            }

            val formalBodyRows = messageComponents.reader.formalBodyRowCount(structuralBatch)
            onLog("AI 正文容器读取：扫描行=${structuralBatch.scannedRowCount}；正式正文行=$formalBodyRows；正文OCR=${structuralBatch.ocrTargets.size}；仅采集正式文本容器，不分类卡片")
            if (structuralBatch.ocrTargets.isEmpty()) {
                invalidateOcrRecognitionReuse()
                deliver(structuralBatch)
                return@acquireObservationVisibility
            }

            try {
                messageComponents.ocrProcessor.recognize(
                    batch = structuralBatch,
                    expectedPackage = page.packageName,
                    expectedActivity = page.activityClass,
                    reuseVerifiedIncoming = reuseVerifiedIncoming,
                    confirmedBaselineObservationId = confirmedBaselineObservationId,
                    isRequestCurrent = isCurrent
                ) { ocrResult ->
                    if (ocrResult.invalidated || !isCurrent()) {
                        deliver(staleBatch())
                        return@recognize
                    }
                    if (ocrResult.admissionDeferred) {
                        // The worker gate belongs to actual worker exit, not caller timeout.
                        // Never merge this as an empty OCR result or charge failure counters.
                        deliver(structuralBatch.copy(ocrAdmissionDeferred = true))
                        return@recognize
                    }
                    val resultBatch = try {
                        val mergedBatch = messageComponents.reader.applyOcrResults(structuralBatch, ocrResult, limit)
                        // Raw recognized text is carried only to the runtime's post-window-validation
                        // diagnostic path. It is never added to the normal message summary/toString.
                        if (BuildConfig.DEBUG && BuildConfig.OCR_RESULT_DEBUG_LOGGING) {
                            mergedBatch.copy(
                                ocrDebugTextByRowIndex = ocrResult.textByRowIndex.toMap(),
                                ocrDebugChineseTextByRowIndex = ocrResult.chineseTextByRowIndex.toMap(),
                                ocrDebugLatinTextByRowIndex = ocrResult.latinTextByRowIndex.toMap()
                            )
                        } else {
                            mergedBatch
                        }
                    } catch (_: Exception) {
                        structuralBatch.copy(
                            status = MomoChatMessageReadStatusPolicy.afterOcrException(),
                            ocrAttempted = true,
                            ocrAttemptedTargetCount = structuralBatch.ocrTargets.size,
                            ocrFailedTargetCount = structuralBatch.ocrTargets.size,
                            ocrFailureReason = "本地 OCR 结果合并失败（异常详情已隐藏）",
                            ocrDebugTextByRowIndex = if (BuildConfig.DEBUG && BuildConfig.OCR_RESULT_DEBUG_LOGGING) emptyMap() else null,
                            ocrDebugChineseTextByRowIndex = if (BuildConfig.DEBUG && BuildConfig.OCR_RESULT_DEBUG_LOGGING) emptyMap() else null,
                            ocrDebugLatinTextByRowIndex = if (BuildConfig.DEBUG && BuildConfig.OCR_RESULT_DEBUG_LOGGING) emptyMap() else null
                        )
                    }
                    deliver(resultBatch)
                }
            } catch (_: Exception) {
                deliver(
                    structuralBatch.copy(
                        status = MomoChatMessageReadStatusPolicy.afterOcrException(),
                        ocrAttempted = true,
                        ocrAttemptedTargetCount = structuralBatch.ocrTargets.size,
                        ocrFailedTargetCount = structuralBatch.ocrTargets.size,
                        ocrFailureReason = "本地 OCR 初始化或执行失败",
                        ocrDebugTextByRowIndex = if (BuildConfig.DEBUG && BuildConfig.OCR_RESULT_DEBUG_LOGGING) emptyMap() else null,
                        ocrDebugChineseTextByRowIndex = if (BuildConfig.DEBUG && BuildConfig.OCR_RESULT_DEBUG_LOGGING) emptyMap() else null,
                        ocrDebugLatinTextByRowIndex = if (BuildConfig.DEBUG && BuildConfig.OCR_RESULT_DEBUG_LOGGING) emptyMap() else null
                    )
                )
            }
        }
    }

    fun close() {
        if (messageComponents.ocrProcessorDelegate.isInitialized()) {
            runCatching { messageComponents.ocrProcessor.close() }
        }
    }

    private fun Rect.toScreenRect() = ScreenRect(left, top, right, bottom)

    private inline fun safeString(block: () -> String): String = runCatching(block).getOrDefault("")
    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
    private fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()

    private companion object {
        private const val NAVIGATION_EVIDENCE_INTERVAL_MS = 2_000L
        private const val RECEIVED_GREETINGS_ENTRY_VERIFY_TIMEOUT_MS = 4_000L
        private const val RECEIVED_GREETINGS_ENTRY_RETRY_COOLDOWN_MS = 12_000L
        private const val SAYHI_CARD_LIST_VERIFY_TIMEOUT_MS = 4_000L
        private const val SAYHI_CARD_LIST_TAP_COOLDOWN_MS = 12_000L
        private const val SAYHI_CARD_LIST_PRE_TAP_MAX_SNAPSHOT_AGE_MS = 750L
        private const val SAYHI_ROW_PRE_TAP_MAX_SNAPSHOT_AGE_MS = 750L
        private const val SAYHI_GESTURE_TERMINAL_TIMEOUT_MS = 12_000L
        private const val SAYHI_ROW_DETAIL_VERIFY_TIMEOUT_MS = 8_000L
        private const val SAYHI_CLICK_EVENT_SETTLE_MS = 700L
        private const val SAYHI_PROFILE_PAGE_TIMEOUT_MS = 6_000L
        private const val INBOX_IDENTITY_TAP_TIMEOUT_MS = 6_000L
        private const val SAYHI_PROFILE_SCROLL_TIMEOUT_MS = 8_000L
        private const val SAYHI_PROFILE_SCROLL_SETTLE_MS = 500L
        private const val SAYHI_PROFILE_IDENTITY_SCROLL_DURATION_MS = 520L
        private const val SAYHI_PROFILE_BACK_TIMEOUT_MS = 5_000L
        private const val MAX_SAYHI_PROFILE_BACK_ATTEMPTS = 2
        private const val SAYHI_DETAIL_BACK_TIMEOUT_MS = 8_000L
        private const val SAYHI_DETAIL_BACK_RECOVERY_TIMEOUT_MS = 8_000L
        private const val SAYHI_PROFILE_ID_MAX_ATTEMPTS = 3
        private const val SAYHI_PROFILE_ROW_RECOVERY_MAX_ATTEMPTS = 5
        private const val MAX_SAYHI_PROCESSED_ROWS = 600
        private const val MAX_SAYHI_ROW_RETRY_BACKOFF_STAGE = 4
        private const val SAYHI_SAFE_TARGET_VALIDATION_FAILURE_REASON_PREFIX =
            "ROW_SAFE_TAP_TARGET_SECOND_VALIDATION_FAILED"
        private const val RECEIVED_GREETINGS_ENTRY_TAP_DURATION_MS = 90L
        private const val SEND_TAP_DURATION_MS = 90L
        private const val RECEIVED_GREETINGS_ENTRY_SEARCH_SCROLL_DURATION_MS = 520L
        private const val RECEIVED_GREETINGS_PRE_TAP_MAX_SNAPSHOT_AGE_MS = 750L
        private const val RECEIVED_GREETINGS_ENTRY_DIAGNOSTIC_INTERVAL_MS = 5_000L
        private const val NAVIGATION_EVIDENCE_MAX_NODES = 1_200
        private const val MAX_NAVIGATION_EVIDENCE_LOG_ITEMS = 8

        private const val MOMO_DYNAMIC_TAB_LABEL = "动态"
        private const val MOMO_NEARBY_TAB_LABEL = "附近"
        private const val MOMO_AI_TAB_LABEL = "AI小陌"
        private const val MOMO_HOME_TAB_LABEL = "首页"
        private val MOMO_HOME_NAV_LABELS = setOf("首页", "附近", "消息", "我的", "直播", "动态")
        private val MOMO_HOME_TAB_CONTAINER_VIEW_ID_SUFFIXES = setOf("maintab_layout_nearby")
        private const val HOME_NAV_LOWER_BAND_RATIO = 0.72f
        private val DYNAMIC_TAB_LABELS = setOf(
            MOMO_DYNAMIC_TAB_LABEL,
            MOMO_NEARBY_TAB_LABEL,
            MOMO_AI_TAB_LABEL
        )
        private const val DYNAMIC_TAB_UPPER_BAND_RATIO = 0.28f
        private const val DYNAMIC_TAB_ROW_TOLERANCE_DP = 48
        private const val DYNAMIC_TAB_CONTENT_GAP_DP = 8
        private const val TAB_TARGET_MATCH_TOLERANCE_DP = 18
        private const val MAX_TAB_CLICK_TARGET_HEIGHT_RATIO = 0.24f
        private const val MAX_TAB_CLICK_ANCESTOR_DEPTH = 5
        private const val SESSION_ROW_BOUNDS_TOLERANCE_PX = 8
        private const val PAGE_FINGERPRINT_MAX_NODES = 500
        private const val MAX_POPUP_SAFETY_INCOMPLETE_ATTEMPTS = 3
        private const val POPUP_SAFETY_SCAN_DIAGNOSTIC_NODE_THRESHOLD = 350
        private const val POPUP_SAFETY_SCAN_DIAGNOSTIC_DURATION_MS = 300L
        private const val FINGERPRINT_SIZE_BUCKET_PX = 24
        private const val MIN_LIST_WIDTH_RATIO = 0.58f
        private const val MIN_LIST_HEIGHT_RATIO = 0.28f
        private const val MIN_SAFE_SESSION_TAP_WIDTH_PX = 80
        private const val MIN_SAFE_SESSION_TAP_HEIGHT_PX = 48
        private const val MIN_SAFE_SESSION_SWIPE_WIDTH_PX = 240
        private const val MIN_SAFE_SESSION_SWIPE_HEIGHT_PX = 240

        private fun createDefaultConfig(): Config = Config(
            page = MomoPageInspector.Config(
                targetPackage = MomoAppDescriptor.PACKAGE_NAME,
                homeNavLabels = MOMO_HOME_NAV_LABELS,
                homeTabLabel = MOMO_HOME_TAB_LABEL,
                homeNavLowerBandRatio = HOME_NAV_LOWER_BAND_RATIO,
                dynamicTabLabels = DYNAMIC_TAB_LABELS,
                dynamicTabLabel = MOMO_DYNAMIC_TAB_LABEL,
                nearbyTabLabel = MOMO_NEARBY_TAB_LABEL,
                aiTabLabel = MOMO_AI_TAB_LABEL,
                dynamicTabUpperBandRatio = DYNAMIC_TAB_UPPER_BAND_RATIO,
                dynamicTabRowToleranceDp = DYNAMIC_TAB_ROW_TOLERANCE_DP,
                dynamicTabContentGapDp = DYNAMIC_TAB_CONTENT_GAP_DP,
                pageFingerprintMaxNodes = PAGE_FINGERPRINT_MAX_NODES,
                fingerprintSizeBucketPx = FINGERPRINT_SIZE_BUCKET_PX,
                minListWidthRatio = MIN_LIST_WIDTH_RATIO,
                minListHeightRatio = MIN_LIST_HEIGHT_RATIO
            ),
            navigation = MomoNavigationController.Config(
                maxScanNodes = PAGE_FINGERPRINT_MAX_NODES,
                tabTargetMatchToleranceDp = TAB_TARGET_MATCH_TOLERANCE_DP,
                maxClickTargetHeightRatio = MAX_TAB_CLICK_TARGET_HEIGHT_RATIO,
                maxAncestorDepth = MAX_TAB_CLICK_ANCESTOR_DEPTH,
                homeTabContainerViewIdSuffixes = MOMO_HOME_TAB_CONTAINER_VIEW_ID_SUFFIXES
            ),
            popupMaxScanNodes = PAGE_FINGERPRINT_MAX_NODES,
            feedGesture = MomoFeedGestureController.Config(
                minListWidthRatio = MIN_LIST_WIDTH_RATIO,
                minListHeightRatio = MIN_LIST_HEIGHT_RATIO,
                maxNodesPerScan = AccessibilityTreeLimits.MAX_NODES_PER_SCAN
            )
        )
    }
}
