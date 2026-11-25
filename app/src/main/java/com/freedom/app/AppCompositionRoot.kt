package com.freedom.app

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.content.Intent
import android.os.Build
import com.freedom.accessibility.FreedomAccessibilityService
import com.freedom.adapters.accessibility.android.AndroidAccessibilityEventPipeline
import com.freedom.adapters.ai.DeepSeekAiModelTransport
import com.freedom.adapters.ai.OpenAiCompatibleAiModelTransport
import com.freedom.adapters.accessibility.android.AndroidAccessibilityGateway
import com.freedom.adapters.accessibility.android.AndroidAccessibilityServiceRecoveryCoordinator
import com.freedom.adapters.accessibility.android.AndroidGestureVisualizer
import com.freedom.adapters.accessibility.android.AndroidMonitorLogPresenter
import com.freedom.adapters.accessibility.android.AndroidMonitorPreviewScheduler
import com.freedom.adapters.accessibility.android.AndroidOverlayPort
import com.freedom.adapters.accessibility.android.AndroidServiceReceiverRegistry
import com.freedom.adapters.accessibility.android.AndroidWindowContextTracker
import com.freedom.adapters.accessibility.android.MonitorOverlay
import com.freedom.adapters.accessibility.diagnostics.LatestAccessibilityNodeDump
import com.freedom.adapters.android.AndroidAppLabelResolver
import com.freedom.adapters.android.MonitorActions
import com.freedom.adapters.android.gestures.MomoGesturePolicySettingsProvider
import com.freedom.adapters.android.gestures.AndroidGestureCalibrationPresentationAdapter
import com.freedom.adapters.android.gestures.AndroidGestureCalibrationHost
import com.freedom.adapters.runtime.android.AccountBrowseModeRuntimeAdapter
import com.freedom.adapters.runtime.android.AiReplyModeRuntimeAdapter
import com.freedom.adapters.runtime.android.HandlerTaskScheduler
import com.freedom.adapters.social.momo.MomoAppDescriptor
import com.freedom.features.aireply.ai.AiReplyDraftCoordinator
import com.freedom.features.aireply.ai.AiDecisionTaskIds
import com.freedom.features.aireply.ai.EngineBackedAiDecisionGateway
import com.freedom.features.aireply.policy.AiReplyPolicyRegistry
import com.freedom.features.aireply.policy.PolicyRegistry
import com.freedom.features.aireply.ai.TransportBackedAiModelProvider
import com.freedom.adapters.social.momo.MomoSocialAdapter
import com.freedom.application.ports.AiModelCloudConsentSettings
import com.freedom.application.ports.GestureCalibrationPresentationPort
import com.freedom.application.ports.GestureSettingsRepository
import com.freedom.application.ports.MonitorControlRepository
import com.freedom.application.ports.MonitorSnapshotRepository
import com.freedom.application.ports.OcrReviewRepository
import com.freedom.application.ports.RunDiagnosticsPort
import com.freedom.application.ports.SessionSampleArchivePort
import com.freedom.application.ports.TaskLaunchParametersRepository
import com.freedom.application.runtime.RunSessionCommandUseCase
import com.freedom.application.runtime.RunGenerationClock
import com.freedom.application.runtime.RunSessionCoordinator
import com.freedom.core.accessibility.api.UnifiedAccessibilityEntry
import com.freedom.core.accessibility.api.UnifiedAccessibilityEvent
import com.freedom.core.accessibility.diagnostics.NodeCaptureSource
import com.freedom.core.accessibility.events.InputOriginClassifier
import com.freedom.core.accessibility.events.TouchInteractionMonitor
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext
import com.freedom.core.logging.RunLogCategory
import com.freedom.core.logging.RunLogLevel
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.PageObservationCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import com.freedom.core.runtime.UiWriteAuthorization
import com.freedom.core.runtime.UiWriteScope
import com.freedom.core.runtime.UnifiedPageObservationBus
import com.freedom.core.runtime.TargetAppReadinessController
import com.freedom.data.MonitorStoreRepository
import com.freedom.data.MonitorStoreSnapshotRepository
import com.freedom.data.OcrReviewStore
import com.freedom.data.AccountBrowseCheckpointDataStore
import com.freedom.data.RecoveryScheduleDataStore
import com.freedom.data.RunDiagnosticsManager
import com.freedom.data.SharedPreferencesAiModelCloudConsent
import com.freedom.data.SharedPreferencesGestureSettingsRepository
import com.freedom.data.TaskLaunchParametersStore
import com.freedom.data.ContactMessageLedgerDataStore
import com.freedom.data.ai.EncryptedAiModelConfigurationRepository
import com.freedom.data.ai.AiReplyPersonalizationStore
import com.freedom.data.database.LocalConversationRepository
import com.freedom.data.database.LocalReplyTaskRepository
import com.freedom.data.database.LocalReplySegmentTaskRepository
import com.freedom.features.aireply.ai.ReplyTaskRepository
import com.freedom.features.aireply.ai.ReplySegmentTaskRepository
import com.freedom.data.social.momo.MomoSayHiTraversalCheckpointDataStore
import com.freedom.data.social.momo.ReceivedGreetingsSendReturnDataStore
import com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnRepository
import com.freedom.data.PrivateMessagesTraversalCheckpointDataStore
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpointRepository
import com.freedom.features.aireply.domain.contact.ContactMessageLedgerRepository
import com.freedom.data.samples.MomoChatSampleArchiveDataStore
import com.freedom.features.aireply.AiReplyObservationRuntime
import com.freedom.features.aireply.ai.AiModelServiceProvider
import com.freedom.features.aireply.ai.ConversationContextManager
import com.freedom.features.aireply.ai.AiModelConfigurationRepository
import com.freedom.features.aireply.ai.AiReplyPersonalizationRepository
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository
import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpointRepository
import com.freedom.features.recovery.RecoveryScheduleRepository
import com.freedom.features.recovery.UnifiedRecoveryTransactionManager
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.ports.ConversationRepository
import com.freedom.gesture.api.GestureApi as PublicGestureApi
import com.freedom.application.gesture.GestureRuntimeFactory
import com.freedom.application.gesture.GestureRuntimeHandle
import com.freedom.application.monitor.AccessibilityTreePreviewUseCase
import com.freedom.application.monitor.GlobalNodeLayoutVisualizationUseCase
import com.freedom.accessibility.gesture.GestureCalibrationController
import com.freedom.stability.MonitorKeepAliveService
import java.io.File

/**
 * The single application composition root. Data implementations are constructed here and exposed
 * only through application-facing ports. Activity and AccessibilityService graphs are scoped to
 * their platform lifetimes and receive the shared application repositories.
 */
class AppCompositionRoot(context: Context) {
    val appContext: Context = context.applicationContext

    val monitorRepository: MonitorControlRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MonitorStoreRepository(appContext)
    }
    val diagnostics: RunDiagnosticsPort by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RunDiagnosticsManager.get(appContext)
    }
    val gestureSettingsRepository: GestureSettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SharedPreferencesGestureSettingsRepository(appContext)
    }
    val taskLaunchParametersRepository: TaskLaunchParametersRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TaskLaunchParametersStore(appContext)
    }
    val contactMessageLedgerRepository: ContactMessageLedgerRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ContactMessageLedgerDataStore(appContext)
    }
    val aiModelCloudConsent: AiModelCloudConsentSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SharedPreferencesAiModelCloudConsent(appContext)
    }
    val aiModelConfigurationRepository: AiModelConfigurationRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EncryptedAiModelConfigurationRepository(appContext)
    }
    val aiReplyPersonalizationRepository: AiReplyPersonalizationRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AiReplyPersonalizationStore(appContext)
    }
    /** Application-owned immutable policy registry, shared with the service-scoped draft flow. */
    val aiReplyPolicyRegistry: PolicyRegistry = AiReplyPolicyRegistry
    val ocrReviewRepository: OcrReviewRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OcrReviewStore(appContext)
    }
    val conversationRepository: ConversationRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalConversationRepository(File(appContext.filesDir, "conversation_store"))
    }

    val replyTaskRepository: ReplyTaskRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalReplyTaskRepository(File(appContext.filesDir, "reply_task_store.tsv"))
    }
    val replySegmentTaskRepository: ReplySegmentTaskRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalReplySegmentTaskRepository(File(appContext.filesDir, "reply_segment_task_store.tsv"))
    }
    val conversationContextManager: ConversationContextManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ConversationContextManager(conversationRepository)
    }
    val sayHiTraversalCheckpointRepository: MomoSayHiTraversalCheckpointRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MomoSayHiTraversalCheckpointDataStore(appContext)
    }
    val receivedGreetingsSendReturnRepository: ReceivedGreetingsSendReturnRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReceivedGreetingsSendReturnDataStore(appContext)
    }
    val privateMessagesCheckpointRepository: PrivateMessagesTraversalCheckpointRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PrivateMessagesTraversalCheckpointDataStore(appContext)
    }
    val accountBrowseCheckpointRepository: AccountBrowseCheckpointRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AccountBrowseCheckpointDataStore(appContext)
    }
    val recoveryScheduleRepository: RecoveryScheduleRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RecoveryScheduleDataStore(appContext)
    }
    // AI list and greetings checkpoint plans must never overwrite Browse or each other.
    val privateMessagesRecoveryScheduleRepository: RecoveryScheduleRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RecoveryScheduleDataStore(appContext, "recovery_schedule_ai_private_messages")
    }
    val receivedGreetingsRecoveryScheduleRepository: RecoveryScheduleRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RecoveryScheduleDataStore(appContext, "recovery_schedule_ai_received_greetings")
    }
    val sampleArchivePort: SessionSampleArchivePort by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MomoChatSampleArchiveDataStore()
    }
    /** Process-scoped alongside the UI leases; a recreated Service must not restart generation at zero. */
    val runGenerationClock by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RunGenerationClock()
    }
    /** Shared across the mutually exclusive business modes and AI Reply sub-tasks. */
    val uiOperationOwnershipCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UiOperationOwnershipCoordinator()
    }
    val taskCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TaskCoordinator(uiOperationOwnershipCoordinator)
    }
    /** P6: one process recovery admission manager, shared by both automation modes. */
    val recoveryTransactionManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UnifiedRecoveryTransactionManager(
            currentRunGeneration = runGenerationClock::current,
            onLog = monitorRepository::addLog
        )
    }
    val monitorSnapshotRepository: MonitorSnapshotRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MonitorStoreSnapshotRepository(appContext)
    }

    init {
        // The overlay is a process-owned Android adapter. Its repository is supplied once by the
        // composition root; it must never instantiate a Data implementation as a fallback.
        MonitorOverlay.installRepository(monitorRepository)
    }

    fun createActivityDependencies(activity: Activity): ActivityDependencies = ActivityDependencies(
        monitorRepository = monitorRepository,
        taskLaunchParametersRepository = taskLaunchParametersRepository,
        aiModelCloudConsent = aiModelCloudConsent,
        aiModelConfigurationRepository = aiModelConfigurationRepository,
        aiReplyPersonalizationRepository = aiReplyPersonalizationRepository,
        gestureCalibration = AndroidGestureCalibrationPresentationAdapter(activity, gestureSettingsRepository),
        runSessionCommands = RunSessionCommandUseCase(
            state = monitorRepository,
            onDiagnostic = monitorRepository::addLog
        ),
        ocrReviewRepository = ocrReviewRepository,
        diagnostics = diagnostics,
        sampleArchivePort = sampleArchivePort
    )

    internal fun createAccessibilityServiceDependencies(
        service: FreedomAccessibilityService,
        mainHandler: Handler,
        addLog: (String) -> Unit,
        dp: (Int) -> Int,
        requestScreenOff: () -> Boolean,
        refreshViews: () -> Unit
    ): AccessibilityServiceDependencies = AccessibilityServiceDependencies(
        root = this,
        service = service,
        mainHandler = mainHandler,
        addLog = addLog,
        dp = dp,
        requestScreenOff = requestScreenOff,
        refreshViews = refreshViews
    )
}

/** Dependencies bound to one Activity instance; none of the members are Data implementations. */
class ActivityDependencies internal constructor(
    val monitorRepository: MonitorControlRepository,
    val taskLaunchParametersRepository: TaskLaunchParametersRepository,
    val aiModelCloudConsent: AiModelCloudConsentSettings,
    val aiModelConfigurationRepository: AiModelConfigurationRepository,
    val aiReplyPersonalizationRepository: AiReplyPersonalizationRepository,
    val gestureCalibration: GestureCalibrationPresentationPort,
    val runSessionCommands: RunSessionCommandUseCase,
    val ocrReviewRepository: OcrReviewRepository,
    val diagnostics: RunDiagnosticsPort,
    val sampleArchivePort: SessionSampleArchivePort
)

/** Platform-specific graph created by AppCompositionRoot for one AccessibilityService lifetime. */
internal class AccessibilityServiceDependencies internal constructor(
    root: AppCompositionRoot,
    private val service: FreedomAccessibilityService,
    private val mainHandler: Handler,
    private val addLog: (String) -> Unit,
    private val dp: (Int) -> Int,
    private val requestScreenOff: () -> Boolean,
    private val refreshViews: () -> Unit
) {
    val monitorRepository: MonitorControlRepository = root.monitorRepository
    val gestureSettingsRepository: GestureSettingsRepository = root.gestureSettingsRepository
    val windowContextTracker = AndroidWindowContextTracker()
    val diagnostics: RunDiagnosticsPort = root.diagnostics
    val appLabelResolver by lazy(LazyThreadSafetyMode.NONE) { AndroidAppLabelResolver(service) }
    val inputOriginClassifier by lazy(LazyThreadSafetyMode.NONE) {
        InputOriginClassifier(onDiagnostic = addLog)
    }
    val touchInteractionMonitor by lazy(LazyThreadSafetyMode.NONE) {
        TouchInteractionMonitor(onDiagnostic = addLog)
    }
    val monitorLogPresenter by lazy(LazyThreadSafetyMode.NONE) {
        AndroidMonitorLogPresenter(
            persistLog = monitorRepository::addLog,
            refreshPresentation = {
                service.sendBroadcast(Intent(MonitorActions.ACTION_REFRESH).setPackage(service.packageName))
                MonitorOverlay.update(service)
            }
        )
    }
    val targetAppReadinessController by lazy(LazyThreadSafetyMode.NONE) {
        TargetAppReadinessController { previous, current ->
            addLog(
                "目标应用就绪状态[TRANSITION]：${previous.status}->${current.status}；" +
                    "mode=${current.runMode?.name ?: "NONE"}；package=${current.foregroundPackage.ifBlank { "UNKNOWN" }}；" +
                    "activity=${current.activityClass.ifBlank { "UNKNOWN" }}；windowId=${current.windowId}；" +
                    "pageGeneration=${current.pageGeneration}；reason=${current.reason}"
            )
        }
    }
    val taskScheduler: HandlerTaskScheduler by lazy(LazyThreadSafetyMode.NONE) {
        HandlerTaskScheduler(mainHandler)
    }
    /** One service-scoped page-observation coordinator shared by AI Reply and AccountBrowse. */
    val pageObservationCoordinator: PageObservationCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        PageObservationCoordinator(taskScheduler)
    }
    val unifiedPageObservationBus: UnifiedPageObservationBus by lazy(LazyThreadSafetyMode.NONE) {
        UnifiedPageObservationBus().also { bus ->
            bus.registerClassifier(aiReplyModeRuntimeAdapter)
            bus.registerClassifier(accountBrowseRuntimeAdapter)
        }
    }
    /** Service-scoped Activity recovery; no gesture or page-action capability is injected. */
    val activityEvidenceReader by lazy(LazyThreadSafetyMode.NONE) {
        com.freedom.adapters.accessibility.android.AndroidUsageActivityEvidenceReader(
            service = service,
            targetPackage = monitorRepository::targetPackage,
            currentWindowGeneration = { accessibilityGateway.currentWindowGeneration() }
        )
    }
    val activityRecoveryCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        com.freedom.adapters.accessibility.android.AndroidForegroundActivityRecoveryCoordinator(
            scheduler = taskScheduler,
            isRunRequested = {
                monitorRepository.isRunRequested() && runSessionCoordinator.activeMode() != null
            },
            currentActivityClass = windowContextTracker::currentActivityClass,
            readEvidence = activityEvidenceReader::probe,
            acceptEvidence = { evidence ->
                accessibilityGateway.currentWindowGeneration() == evidence.windowGeneration &&
                    windowContextTracker.restoreActivityClass(
                        evidence.activityClass, evidence.windowId, evidence.windowGeneration
                    )
            },
            onLog = addLog
        )
    }
    val uiWriteAuthorization: UiWriteAuthorization by lazy(LazyThreadSafetyMode.NONE) {
        UiWriteAuthorization(
            taskCoordinator = root.taskCoordinator,
            currentRunGeneration = { runSessionCoordinator.currentRunGeneration() },
            activeRunMode = {
                if (monitorRepository.isRunRequested()) runSessionCoordinator.activeMode() else null
            }
        )
    }
    val accessibilityGateway: AndroidAccessibilityGateway by lazy(LazyThreadSafetyMode.NONE) {
        AndroidAccessibilityGateway(
            service = service,
            targetPackage = { monitorRepository.targetPackage() },
            activityClass = windowContextTracker::currentActivityClass,
            activeRunMode = {
                if (monitorRepository.isRunRequested()) runSessionCoordinator.activeMode() else null
            },
            inspectPageReadiness = { root, activity, mode ->
                momoSocialAdapter.inspectGesturePageReadiness(root, activity, mode)
            },
            targetAppReadinessController = targetAppReadinessController,
            inputOriginClassifier = inputOriginClassifier,
            uiWriteAuthorization = uiWriteAuthorization,
            onReadinessDiagnostic = addLog,
            recoveredActivityBindingCurrent = windowContextTracker::recoveredActivityMatches,
            onRecoveredActivityBindingMismatch = {
                windowContextTracker.invalidateRecoveredActivity()
                activityRecoveryCoordinator.ensureStarted()
            }
        )
    }
    val gestureVisualizerDelegate = lazy(LazyThreadSafetyMode.NONE) { AndroidGestureVisualizer(service) }
    val gestureVisualizer by gestureVisualizerDelegate
    val gestureRuntimeDelegate = lazy(LazyThreadSafetyMode.NONE) {
        GestureRuntimeFactory.create(
            dispatcher = accessibilityGateway,
            visualizer = gestureVisualizer,
            displayMetrics = accessibilityGateway::displayMetrics,
            onDiagnostic = addLog,
            scheduler = taskScheduler,
            currentPageGeneration = accessibilityGateway::currentWindowGeneration,
            taskCoordinator = root.taskCoordinator,
            currentRunGeneration = runSessionCoordinator::currentRunGeneration,
            isTargetWindowCurrent = accessibilityGateway::isGestureWindowCurrent,
            currentGestureWindow = accessibilityGateway::currentGestureWindow,
            isGestureWindowCurrent = accessibilityGateway::isGestureWindowCurrent,
            currentCoordinateContext = { accessibilityGateway.displayMetrics().toGestureCoordinateContext() },
            policySettingsProvider = MomoGesturePolicySettingsProvider(
                context = service,
                repository = root.gestureSettingsRepository
            ),
            uptimeClockMillis = { SystemClock.uptimeMillis() }
        )
    }
    val gestureRuntime: GestureRuntimeHandle get() = gestureRuntimeDelegate.value
    val gesturePolicy: PublicGestureApi get() = gestureRuntime
    val overlayPort by lazy(LazyThreadSafetyMode.NONE) { AndroidOverlayPort(service) }
    val globalNodeLayoutVisualizationUseCase by lazy(LazyThreadSafetyMode.NONE) {
        GlobalNodeLayoutVisualizationUseCase(
            gateway = accessibilityGateway,
            targetPackage = monitorRepository::targetPackage,
            overlay = overlayPort,
            onLog = addLog
        )
    }
    val momoSocialAdapterDelegate: Lazy<MomoSocialAdapter> = lazy(LazyThreadSafetyMode.NONE) {
        MomoSocialAdapter(
            context = service,
            rootAccess = accessibilityGateway,
            accessibilityGateway = accessibilityGateway,
            targetPackage = MomoAppDescriptor.PACKAGE_NAME,
            activityClass = windowContextTracker::currentActivityClass,
            dp = dp,
            onLog = addLog,
            gestureSettingsRepository = root.gestureSettingsRepository,
            screenshotCapture = accessibilityGateway,
            gesturePolicy = gesturePolicy,
            onNodeTreeSnapshot = { snapshot ->
                LatestAccessibilityNodeDump.update(snapshot, NodeCaptureSource.AUTOMATIC)
            },
            latestDialogWindowObservation = windowContextTracker::currentDialogWindowObservation,
            conversationRepository = root.conversationRepository,
            diagnostics = diagnostics,
            sayHiTraversalCheckpointRepository = root.sayHiTraversalCheckpointRepository,
            receivedGreetingsSendReturnRepository = root.receivedGreetingsSendReturnRepository,
            ocrReviewRepository = root.ocrReviewRepository,
            sampleArchivePort = root.sampleArchivePort,
            registerSelfNavigation = { targetActivityClass ->
                inputOriginClassifier.registerExpectedSelfNavigation(
                    packageName = monitorRepository.targetPackage(),
                    targetActivityClass = targetActivityClass
                )
            },
            isAiReplyUiOperationOwned = {
                uiWriteAuthorization.permits(UiWriteScope.AI_COMPOSER) ||
                    uiWriteAuthorization.permits(UiWriteScope.VERIFIED_GREETING_COMPOSER)
            },
            isBoundAiReplyUiOperationOwned = { lease ->
                uiWriteAuthorization.permitsBound(UiWriteScope.AI_COMPOSER, lease) ||
                    uiWriteAuthorization.permitsBound(UiWriteScope.VERIFIED_GREETING_COMPOSER, lease)
            }
        )
    }
    val momoSocialAdapter: MomoSocialAdapter by momoSocialAdapterDelegate
    private val aiReplyDraftCoordinatorDelegate: Lazy<AiReplyDraftCoordinator> = lazy(LazyThreadSafetyMode.NONE) {
        AiReplyDraftCoordinator(
            configurationRepository = root.aiModelConfigurationRepository,
            cloudConsent = root.aiModelCloudConsent,
            personalizationRepository = root.aiReplyPersonalizationRepository,
            inputPort = momoSocialAdapter,
            sendPort = momoSocialAdapter,
            scheduler = taskScheduler,
            isAiReplyActive = {
                monitorRepository.isRunRequested() && runSessionCoordinator.activeMode() == RunMode.AI_REPLY
            },
            onLog = addLog,
            createGateway = { configuration ->
                val transport = when (configuration.provider) {
                    AiModelServiceProvider.DEEPSEEK -> DeepSeekAiModelTransport(configuration).also { it.diagnosticLog = addLog }
                    AiModelServiceProvider.OPENAI,
                    AiModelServiceProvider.CUSTOM_OPENAI_COMPAT -> OpenAiCompatibleAiModelTransport(configuration)
                }
                val providerName = when (configuration.provider) {
                    AiModelServiceProvider.DEEPSEEK -> DeepSeekAiModelTransport.PROVIDER_NAME
                    AiModelServiceProvider.OPENAI -> "openai"
                    AiModelServiceProvider.CUSTOM_OPENAI_COMPAT -> OpenAiCompatibleAiModelTransport.PROVIDER_NAME
                }
                EngineBackedAiDecisionGateway(
                    modelProvider = TransportBackedAiModelProvider(
                        providerName = providerName,
                        modelName = configuration.modelName,
                        transport = transport,
                        cloudConsent = root.aiModelCloudConsent
                    ),
                    cloudConsent = root.aiModelCloudConsent
                )
            },
            replyTaskRepository = root.replyTaskRepository,
            replySegmentTaskRepository = root.replySegmentTaskRepository,
            policyRegistry = root.aiReplyPolicyRegistry,
            captureTaskLease = { root.taskCoordinator.currentLease() },
            onPreSendModelFailure = { failure ->
                // This callback is dispatched asynchronously onto the runtime scheduler.
                // It cannot grant a new task lease or authorize a stale greeting BACK.
                aiReplyObservationRuntime.onPreSendModelFailure(failure)
            },
            onSendConfirmationObservationRequested = {
                aiReplyObservationRuntime.onSendConfirmationObservationRequested()
            },
            captureGreetingPreSendOutgoingKeys = { target ->
                aiReplyObservationRuntime.captureGreetingPreSendOutgoingKeys(target.windowSessionKey)
            },
            capturePrivatePreSendOutgoingKeys = { target ->
                aiReplyObservationRuntime.capturePrivatePreSendOutgoingKeys(target.windowSessionKey)
            }
        )
    }
    val aiReplyDraftCoordinator: AiReplyDraftCoordinator by aiReplyDraftCoordinatorDelegate
    val aiReplyObservationRuntime: AiReplyObservationRuntime by lazy(LazyThreadSafetyMode.NONE) {
        // The history preloaded before OCR is consumed by the later draft callback. Keeping this
        // cache at the single runtime composition boundary avoids reading the same repository
        // rows again after OCR completes.
        var preparedDraftHistory: com.freedom.features.aireply.ai.VerifiedDraftHistory? = null
        AiReplyObservationRuntime(
            scheduler = taskScheduler,
            chatPagePort = momoSocialAdapter,
            chatMessagePort = momoSocialAdapter,
            chatSessionManagementPort = momoSocialAdapter,
            gesturePolicy = gesturePolicy,
            targetPackage = { monitorRepository.targetPackage() },
            isAiReplyActive = {
                monitorRepository.isRunRequested() && runSessionCoordinator.activeMode() == RunMode.AI_REPLY
            },
            inspectAndroidRecoveryReadiness = { _ -> accessibilityGateway.inspectRecoveryReadiness() },
            onLog = addLog,
            pageObservationSnapshotSource = { snapshot ->
                unifiedPageObservationBus.observe(snapshot)
            },
            fullInspectionDebounceMs = FreedomAccessibilityService.AI_CHAT_INSPECT_DEBOUNCE_MS,
            regionRefreshDebounceMs = FreedomAccessibilityService.AI_CHAT_REGION_DEBOUNCE_MS,
            draftStartupFailure = { aiReplyDraftCoordinator.startupFailure() },
            resolveConversationIdentity = { identity, title ->
                com.freedom.features.conversation.domain.contact.ContactHistoryResolver(root.conversationRepository)
                    .resolve(identity, title)
            },
            prepareDraftHistory = { identity, snapshot ->
                preparedDraftHistory = null
                val privatePolicy = requireNotNull(root.aiReplyPolicyRegistry.resolve(AiDecisionTaskIds.PRIVATE_MESSAGES)) {
                    "Private messages policy is not registered"
                }
                val verifiedIdentity = privatePolicy.contextPolicy().verifiedHistoryIdentity(identity)
                if (verifiedIdentity != null) {
                    val history = com.freedom.features.conversation.domain.contact.ContactHistoryResolver(
                        root.conversationRepository
                    ).loadDraftHistory(verifiedIdentity, snapshot.chatTitleText)
                    preparedDraftHistory = history
                    addLog(
                        if (history != null) {
                            "AI 草稿历史预加载：已验证联系人历史=${history.messages.size}条；OCR 尚未启动"
                        } else {
                            "AI 草稿历史预加载：未找到可用历史；OCR 尚未启动"
                        }
                    )
                }
            },
            onDraftConversationObserved = { batch, snapshot, identity, visitToken ->
                val privatePolicy = requireNotNull(root.aiReplyPolicyRegistry.resolve(AiDecisionTaskIds.PRIVATE_MESSAGES)) {
                    "Private messages policy is not registered"
                }
                val verifiedIdentity = privatePolicy.contextPolicy().verifiedHistoryIdentity(identity)
                val history = if (verifiedIdentity != null) {
                    preparedDraftHistory?.also {
                        preparedDraftHistory = null
                        addLog("AI 草稿历史读取：复用 OCR 前已完成的历史预加载；不重复查询")
                    } ?: runCatching {
                        com.freedom.features.conversation.domain.contact.ContactHistoryResolver(root.conversationRepository)
                            .loadDraftHistory(verifiedIdentity, snapshot.chatTitleText)
                    }.onFailure {
                        addLog(
                            "AI 草稿历史读取失败：CONTACT_HISTORY_READ_FAILED；" +
                                "contactIdVerified=true；exception=${it.javaClass.simpleName}；" +
                                "降级为仅当前窗口上下文"
                        )
                    }.getOrNull()
                } else {
                    preparedDraftHistory = null
                    addLog("AI 草稿上下文：身份未达到 PLATFORM_ID_VERIFIED；不加载联系人历史；仅当前窗口上下文")
                    null
                }
                addLog(
                    if (history != null) {
                        "AI 草稿上下文合并顺序：已验证联系人历史=${history.messages.size}条；" +
                            "再合并当前窗口消息=${batch.messages.size}条；source=${history.lookupSource}"
                    } else {
                        "AI 草稿上下文合并顺序：无可用联系人历史；仅使用当前窗口消息=${batch.messages.size}条"
                    }
                )
                aiReplyDraftCoordinator.generateVisibleDraft(
                    batch,
                    snapshot,
                    visitToken = visitToken,
                    history = privatePolicy.contextPolicy().selectHistory(history),
                    taskId = privatePolicy.taskId
                )
            },
            onGreetingDraftConversationObserved = { batch, snapshot, _, visitToken ->
                // Greeting contacts are new conversations: use the verified current-window OCR
                // only. Never reuse a private-chat preload or query stored contact history.
                preparedDraftHistory = null
                val greetingPolicy = requireNotNull(root.aiReplyPolicyRegistry.resolve(AiDecisionTaskIds.RECEIVED_GREETINGS)) {
                    "Received greetings policy is not registered"
                }
                addLog(
                    "收到招呼草稿上下文[CURRENT_WINDOW_ONLY]：仅使用本次聊天消息=${batch.messages.size}条；" +
                        "未读取历史记录；本次聊天采集仍由收到招呼任务保存"
                )
                aiReplyDraftCoordinator.generateVisibleDraft(
                    batch,
                    snapshot,
                    visitToken = visitToken,
                    history = greetingPolicy.contextPolicy().selectHistory(null),
                    taskId = greetingPolicy.taskId
                )
            },
            onSendTransaction = { batch, snapshot ->
                aiReplyDraftCoordinator.confirmSendTransaction(batch, snapshot)
            },
            verifyGreetingPreSendModelFailure = { failure, snapshot ->
                aiReplyDraftCoordinator.verifyGreetingPreSendFailure(failure, snapshot)
            },
            verifyPrivatePreSendModelFailure = { failure, snapshot ->
                aiReplyDraftCoordinator.rearmVerifiedPrivatePreSendFailure(failure, snapshot)
            },
            onGreetingModelFailureDeferred = { failure ->
                aiReplyDraftCoordinator.releaseDeferredGreetingModelFailure(failure)
            },
            onVerifiedConversationObserved = { batch, identity, snapshot ->
                val verifiedIdentity = identity?.takeIf {
                    it.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED
                }
                val contactId = verifiedIdentity?.contactId?.takeIf(String::isNotBlank)
                if (verifiedIdentity != null && contactId != null) {
                    val conversationId = "momo-direct-$contactId"
                    val context = root.conversationContextManager.recordAndLoad(
                        contactId = contactId,
                        conversationId = conversationId,
                        identityConfidence = verifiedIdentity.confidence.name,
                        batch = batch
                    )
                    val incomingCount = context.messages.count { it.role.name == "USER" }
                    val outgoingCount = context.messages.size - incomingCount
                    addLog(
                        "AI 会话上下文已整理：可用历史=${context.messages.size}；" +
                            "对方消息=$incomingCount；自己消息=$outgoingCount；" +
                            "当前观察批次=${batch.messages.size}；联系人身份=平台已验证；" +
                            "未输出消息正文"
                    )
                    // 草稿由当前窗口入口统一生成；此回调仅维护已验证历史。
                } else {
                    addLog("AI 会话上下文未加载：联系人身份尚未达到平台 ID 验证级别")
                }
            },
            taskCoordinator = root.taskCoordinator,
            currentRunGeneration = { runSessionCoordinator.currentRunGeneration() },
            sharedPageObservationCoordinator = pageObservationCoordinator,
            isConversationDraftPending = { aiReplyDraftCoordinator.isConversationReturnBlocked },
            isSendConfirmationPending = { aiReplyDraftCoordinator.isSendConfirmationPending },
            deferGreetingSendForManualReturn = {
                aiReplyDraftCoordinator.deferUnconfirmedSendForManualReturn()
            },
            deferPrivateSendForManualReturn = {
                aiReplyDraftCoordinator.deferPrivateUnconfirmedSendForManualReturn()
            },
            isSendControlRecoveryPending = { aiReplyDraftCoordinator.isSendControlRecoveryPending },
            onDraftRuntimeReset = {
                if (aiReplyDraftCoordinatorDelegate.isInitialized()) {
                    aiReplyDraftCoordinator.resetForRuntimeReset()
                }
            },
            messageInterceptionThreshold = {
                root.taskLaunchParametersRepository.read().messageInterceptionThreshold
            },
            imageSendThreshold = {
                root.taskLaunchParametersRepository.read().imageSendThreshold
            },
            contactMessageLedgerRepository = root.contactMessageLedgerRepository,
            privateMessagesCheckpointRepository = root.privateMessagesCheckpointRepository,
            receivedGreetingsCheckpointRepository = root.sayHiTraversalCheckpointRepository,
            privateMessagesRecoveryScheduleRepository = root.privateMessagesRecoveryScheduleRepository,
            receivedGreetingsRecoveryScheduleRepository = root.receivedGreetingsRecoveryScheduleRepository,
            recoveryTransactionManager = root.recoveryTransactionManager
        )
    }
    val accountBrowseWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        com.freedom.adapters.accessibility.android.AndroidAccountBrowseWorkflow(
            accessibilityGateway = accessibilityGateway,
            gesturePolicy = gesturePolicy,
            taskScheduler = taskScheduler,
            isBrowseModeActive = { runSessionCoordinator.activeMode() == RunMode.ACCOUNT_BROWSE },
            socialBrowsePort = momoSocialAdapter,
            overlayPort = overlayPort,
            isMonitoringRunning = { monitorRepository.isRunRequested() },
            targetPackage = { monitorRepository.targetPackage() },
            monitorPageContext = {
                val snapshot = monitorRepository.presentationSnapshot()
                "当前识别应用=${snapshot.appLabel}；当前识别页面=${snapshot.pageLabel}"
            },
            onLog = addLog,
            onRequestScreenOff = requestScreenOff,
            pageObservationCoordinator = pageObservationCoordinator,
            accountBrowseCheckpointRepository = root.accountBrowseCheckpointRepository,
            recoveryScheduleRepository = root.recoveryScheduleRepository,
            recoveryTransactionManager = root.recoveryTransactionManager,
            currentRunGeneration = { runSessionCoordinator.currentRunGeneration() }
        )
    }
    val accountBrowseRuntimeAdapter by lazy(LazyThreadSafetyMode.NONE) {
        AccountBrowseModeRuntimeAdapter(
            workflow = { accountBrowseWorkflow },
            onReadinessStarting = { mode, reason ->
                targetAppReadinessController.markStarting(mode, reason, SystemClock.uptimeMillis())
            },
            onReadinessInvalidated = { reason ->
                targetAppReadinessController.invalidate(reason, SystemClock.uptimeMillis())
            },
            taskCoordinator = root.taskCoordinator,
            currentRunGeneration = { runSessionCoordinator.currentRunGeneration() },
            onLog = addLog
        )
    }
    val runSessionCoordinator: RunSessionCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        RunSessionCoordinator(
            state = monitorRepository,
            runtimes = mapOf(
                RunMode.ACCOUNT_BROWSE to accountBrowseRuntimeAdapter,
                RunMode.AI_REPLY to AiReplyModeRuntimeAdapter(
                    runtime = { aiReplyObservationRuntime },
                    onLog = addLog,
                    onReadinessStarting = { mode, reason ->
                        targetAppReadinessController.markStarting(mode, reason, SystemClock.uptimeMillis())
                    },
                    onReadinessInvalidated = { reason ->
                        targetAppReadinessController.invalidate(reason, SystemClock.uptimeMillis())
                    }
                )
            ),
            log = monitorRepository,
            generationClock = root.runGenerationClock,
            taskCoordinator = root.taskCoordinator
        )
    }
    // Service-scoped platform-neutral routing gateway. Existing runtime dispatch order
    // and AI self-navigation attribution are preserved, but never called from the Service.
    val unifiedAccessibilityEntry: UnifiedAccessibilityEntry by lazy(LazyThreadSafetyMode.NONE) {
        UnifiedAccessibilityEntry { notification ->
            when (notification) {
                is UnifiedAccessibilityEvent.TargetApplication ->
                    runSessionCoordinator.dispatchTargetApplicationEvent(notification.event)
                is UnifiedAccessibilityEvent.GlobalTouch ->
                    runSessionCoordinator.dispatchGlobalTouchEvent(notification.event)
                is UnifiedAccessibilityEvent.ActivityTransition ->
                    runSessionCoordinator.dispatchActivityTransition(notification.activityClass)
                is UnifiedAccessibilityEvent.NavigationObservation -> {
                    val attached = notification.attachedSelfNavigation
                    if (attached != null) aiReplyObservationRuntime.onSelfNavigationAttached(attached)
                    else aiReplyObservationRuntime.onUnmatchedNavigationObserved(notification.activityClass)
                }
            }
        }
    }
    val accessibilityEventPipelineDelegate = lazy(LazyThreadSafetyMode.NONE) {
        AndroidAccessibilityEventPipeline(
            targetPackage = { monitorRepository.targetPackage() },
            freedomPackage = service.packageName,
            resolveAppLabel = appLabelResolver::resolve,
            interactionDiagnostics = runSessionCoordinator::interactionDiagnostics,
            handleManualInteractionFallback = runSessionCoordinator::handleManualInteractionFallback,
            updateAppPage = { appLabel, pageLabel -> monitorRepository.updateAppPage(appLabel, pageLabel) },
            appendLog = addLog,
            currentActivityClass = windowContextTracker::currentActivityClass,
            updateCurrentActivityClass = windowContextTracker::updateActivityClass,
            onBrowseEventLogged = { monitorPreviewScheduler.scheduleBrowseScan() },
            unifiedAccessibilityEntry = unifiedAccessibilityEntry,
            currentWindowGeneration = { accessibilityGateway.currentWindowGeneration() },
            inputOriginClassifier = inputOriginClassifier,
            observeWindowStateEvent = windowContextTracker::observeWindowStateEvent,
            pageObservationCoordinator = pageObservationCoordinator,
            onActivityWindowEventDiagnostic = { className, windowId, generation, tracked ->
                addLog(
                    "ACTIVITY_EVENT_DIAGNOSTIC stage=PIPELINE; " +
                        "activityEvidenceSource=ACCESSIBILITY_WINDOW_STATE; " +
                        "eventClass=${com.freedom.adapters.accessibility.android.safeActivityEventClassName(className)}; " +
                        "windowId=$windowId; pageGeneration=$generation; " +
                        "classIsActivity=${com.freedom.adapters.accessibility.android.isActivityWindowClassName(className)}; " +
                        "tracked=$tracked; result=${if (tracked) "ACTIVITY_CLUE_RECORDED" else "CLASS_NOT_TRACKED"}; " +
                        "readinessNotGrantedByDiagnostic=true"
                )
            },
            onDuplicateSuppressed = { event ->
                val decision = event.inputOriginDecision
                diagnostics.recordStructured(
                    eventType = "accessibility_duplicate_suppressed",
                    category = RunLogCategory.DATA_QUALITY,
                    level = RunLogLevel.WARN,
                    component = "AndroidAccessibilityEventPipeline",
                    message = "重复无障碍事件合并；type=${event.type}；package=${event.packageName}; class=${event.className.substringAfterLast('.')}; windowId=${event.windowId}",
                    actionId = decision?.actionId,
                    windowId = event.windowId,
                    packageName = event.packageName,
                    activityClass = event.className,
                    pageGeneration = accessibilityGateway.currentWindowGeneration(),
                    inputOrigin = decision?.origin?.name,
                    inputOriginEvidence = decision?.evidence?.name,
                    viewId = event.source?.viewId,
                    result = "DEDUPLICATED"
                )
            }
        )
    }
    val accessibilityEventPipeline by accessibilityEventPipelineDelegate
    val monitorSnapshotRepository: MonitorSnapshotRepository = root.monitorSnapshotRepository
    val accessibilityTreePreviewUseCase by lazy(LazyThreadSafetyMode.NONE) {
        AccessibilityTreePreviewUseCase(
            appLabelResolver = appLabelResolver,
            gateway = accessibilityGateway,
            isRunning = { monitorRepository.isRunRequested() },
            snapshotRepository = monitorSnapshotRepository,
            onLog = addLog,
            onRefresh = refreshViews,
            onSnapshotCaptured = { snapshot, source -> LatestAccessibilityNodeDump.update(snapshot, source) },
            onSnapshotsCaptured = { snapshots, source ->
                if (source == NodeCaptureSource.MANUAL) LatestAccessibilityNodeDump.updateAll(snapshots, source)
            }
        )
    }
    val monitorPreviewSchedulerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        AndroidMonitorPreviewScheduler(
            scheduler = taskScheduler,
            isBrowseRunActive = {
                monitorRepository.isRunRequested() && runSessionCoordinator.activeMode() == RunMode.ACCOUNT_BROWSE
            },
            scanActiveWindow = { accessibilityTreePreviewUseCase.scanActiveWindow() }
        )
    }
    val monitorPreviewScheduler by monitorPreviewSchedulerDelegate
    val gestureCalibrationHost by lazy(LazyThreadSafetyMode.NONE) {
        AndroidGestureCalibrationHost(service = service, gesturePolicy = gesturePolicy, handler = mainHandler)
    }
    val gestureCalibrationController by lazy(LazyThreadSafetyMode.NONE) {
        com.freedom.accessibility.gesture.GestureCalibrationController(
            host = gestureCalibrationHost,
            isBrowseGestureBusy = { accountBrowseRuntimeAdapter.isGestureBusyForCalibration() },
            onLog = addLog
        )
    }
    val serviceReceiverRegistryDelegate = lazy(LazyThreadSafetyMode.NONE) {
        AndroidServiceReceiverRegistry(
            registerReceiver = { receiver, filter ->
                if (Build.VERSION.SDK_INT >= 33) {
                    service.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    service.registerReceiver(receiver, filter)
                }
            },
            unregisterReceiver = { receiver -> service.unregisterReceiver(receiver) }
        )
    }
    val serviceReceiverRegistry by serviceReceiverRegistryDelegate
    val serviceRecoveryCoordinatorDelegate = lazy(LazyThreadSafetyMode.NONE) {
        AndroidAccessibilityServiceRecoveryCoordinator(
            host = object : AndroidAccessibilityServiceRecoveryCoordinator.Host {
                override fun isRunRequested(): Boolean = monitorRepository.isRunRequested()
                override fun resolveActiveModeForRestore(): RunMode? = runSessionCoordinator.resolveActiveModeForRestore()
                override fun isBootRecoveryPending(): Boolean = monitorRepository.isBootRecoveryPending()
                override fun clearBootRecoveryPending() = monitorRepository.setBootRecoveryPending(false)
                override fun stopRun(reason: String) = runSessionCoordinator.stop(reason)
                override fun stopKeepAlive() = MonitorKeepAliveService.stop(service)
                override fun appendLog(message: String) = monitorRepository.addLog(message)
                override fun resetRuntime(mode: RunMode, reason: String) = runSessionCoordinator.resetRuntime(mode, reason)
                override fun clearActivityContext() = windowContextTracker.clearActivityClass()
                override fun connectRuntime() = runSessionCoordinator.onAccessibilityServiceConnected()
                override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(service)
                override fun isOverlayEnabled(): Boolean = monitorRepository.isOverlayEnabled()
                override fun isFloatingLogEnabled(): Boolean = monitorRepository.isFloatingLogEnabled()
                override fun showOverlay() { MonitorOverlay.show(service) }
                override fun isKeepAliveEnabled(): Boolean = monitorRepository.isKeepAliveEnabled()
                override fun startKeepAlive() { MonitorKeepAliveService.start(service) }
                override fun refresh() = refreshViews()
            },
            scheduler = taskScheduler
        )
    }
    val serviceRecoveryCoordinator by serviceRecoveryCoordinatorDelegate
}
