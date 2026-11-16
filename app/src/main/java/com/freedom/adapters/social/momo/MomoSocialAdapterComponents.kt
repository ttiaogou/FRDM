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
