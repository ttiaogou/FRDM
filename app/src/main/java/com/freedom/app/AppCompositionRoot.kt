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
import com.freedom.application.runtime.RunSessionCoordinator
import com.freedom.core.accessibility.diagnostics.NodeCaptureSource
import com.freedom.core.accessibility.events.InputOriginClassifier
import com.freedom.core.accessibility.events.TouchInteractionMonitor
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext
import com.freedom.core.logging.RunLogCategory
import com.freedom.core.logging.RunLogLevel
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import com.freedom.core.runtime.TargetAppReadinessController
import com.freedom.data.MonitorStoreRepository
import com.freedom.data.MonitorStoreSnapshotRepository
import com.freedom.data.OcrReviewStore
import com.freedom.data.RunDiagnosticsManager
import com.freedom.data.SharedPreferencesAiModelCloudConsent
import com.freedom.data.SharedPreferencesGestureSettingsRepository
import com.freedom.data.TaskLaunchParametersStore
import com.freedom.data.ai.EncryptedAiModelConfigurationRepository
import com.freedom.data.database.LocalConversationRepository
import com.freedom.data.social.momo.MomoSayHiTraversalCheckpointDataStore
import com.freedom.data.samples.MomoChatSampleArchiveDataStore
import com.freedom.features.aireply.AiReplyObservationRuntime
import com.freedom.features.aireply.ai.AiModelConfigurationRepository
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository
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
    val aiModelCloudConsent: AiModelCloudConsentSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SharedPreferencesAiModelCloudConsent(appContext)
    }
    val aiModelConfigurationRepository: AiModelConfigurationRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EncryptedAiModelConfigurationRepository(appContext)
    }
    val ocrReviewRepository: OcrReviewRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OcrReviewStore(appContext)
    }
    val conversationRepository: ConversationRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalConversationRepository(File(appContext.filesDir, "conversation_store"))
    }
    val sayHiTraversalCheckpointRepository: MomoSayHiTraversalCheckpointRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MomoSayHiTraversalCheckpointDataStore(appContext)
    }
    val sampleArchivePort: SessionSampleArchivePort by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MomoChatSampleArchiveDataStore()
    }
    /** Shared across the mutually exclusive business modes and AI Reply sub-tasks. */
    val uiOperationOwnershipCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UiOperationOwnershipCoordinator()
    }
    val taskCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TaskCoordinator(uiOperationOwnershipCoordinator)
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
        persistManualReviewHold: (String) -> Unit,
        requestScreenOff: () -> Boolean,
        refreshViews: () -> Unit
    ): AccessibilityServiceDependencies = AccessibilityServiceDependencies(
        root = this,
        service = service,
        mainHandler = mainHandler,
        addLog = addLog,
        dp = dp,
        persistManualReviewHold = persistManualReviewHold,
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
    private val persistManualReviewHold: (String) -> Unit,
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
    val accessibilityGateway: AndroidAccessibilityGateway by lazy(LazyThreadSafetyMode.NONE) {
        AndroidAccessibilityGateway(
            service = service,
            targetPackage = { monitorRepository.targetPackage() },
            activityClass = windowContextTracker::currentActivityClass,
            inputOriginClassifier = inputOriginClassifier,
            activeRunMode = {
                if (monitorRepository.isRunRequested()) runSessionCoordinator.activeMode() else null
            },
            inspectPageReadiness = { root, activity, mode ->
                momoSocialAdapter.inspectGesturePageReadiness(root, activity, mode)
            },
            targetAppReadinessController = targetAppReadinessController,
            onReadinessDiagnostic = addLog
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
            ocrReviewRepository = root.ocrReviewRepository,
            sampleArchivePort = root.sampleArchivePort
        )
    }
    val momoSocialAdapter: MomoSocialAdapter by momoSocialAdapterDelegate
    val aiReplyObservationRuntime by lazy(LazyThreadSafetyMode.NONE) {
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
            onLog = addLog,
            fullInspectionDebounceMs = FreedomAccessibilityService.AI_CHAT_INSPECT_DEBOUNCE_MS,
            regionRefreshDebounceMs = FreedomAccessibilityService.AI_CHAT_REGION_DEBOUNCE_MS,
            isInputManualReviewRequired = {
                monitorRepository.isInputManualReviewRequired() || inputOriginClassifier.isManualReviewRequired()
            },
            onManualReviewRequired = persistManualReviewHold,
            onRequestScreenOff = requestScreenOff,
            taskCoordinator = root.taskCoordinator,
            currentRunGeneration = { runSessionCoordinator.currentRunGeneration() }
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
            isManualReviewRequired = {
                monitorRepository.isInputManualReviewRequired() || inputOriginClassifier.isManualReviewRequired()
            },
            onManualReviewRequired = persistManualReviewHold,
            onRequestScreenOff = requestScreenOff
        )
    }
    val accountBrowseRuntimeAdapter by lazy(LazyThreadSafetyMode.NONE) {
        AccountBrowseModeRuntimeAdapter(
            workflow = { accountBrowseWorkflow },
            acknowledgeManualReview = inputOriginClassifier::acknowledgeManualReview,
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
                    acknowledgeManualReview = inputOriginClassifier::acknowledgeManualReview,
                    isPersistedManualReviewRequired = monitorRepository::isInputManualReviewRequired,
                    onLog = addLog,
                    onReadinessStarting = { mode, reason ->
                        targetAppReadinessController.markStarting(mode, reason, SystemClock.uptimeMillis())
                    },
                    onReadinessInvalidated = { reason ->
                        targetAppReadinessController.invalidate(reason, SystemClock.uptimeMillis())
                    }
                )
            ),
            log = monitorRepository
        )
    }
    val accessibilityEventPipelineDelegate = lazy(LazyThreadSafetyMode.NONE) {
        AndroidAccessibilityEventPipeline(
            targetPackage = { monitorRepository.targetPackage() },
            freedomPackage = service.packageName,
            resolveAppLabel = appLabelResolver::resolve,
            dispatchTargetApplicationEvent = runSessionCoordinator::dispatchTargetApplicationEvent,
            dispatchGlobalTouchEvent = runSessionCoordinator::dispatchGlobalTouchEvent,
            dispatchActivityTransition = runSessionCoordinator::dispatchActivityTransition,
            dispatchPreemptiveTargetInteraction = runSessionCoordinator::dispatchPreemptiveTargetInteraction,
            interactionDiagnostics = runSessionCoordinator::interactionDiagnostics,
            handleManualInteractionFallback = runSessionCoordinator::handleManualInteractionFallback,
            updateAppPage = { appLabel, pageLabel -> monitorRepository.updateAppPage(appLabel, pageLabel) },
            appendLog = addLog,
            currentActivityClass = windowContextTracker::currentActivityClass,
            updateCurrentActivityClass = windowContextTracker::updateActivityClass,
            onBrowseEventLogged = { monitorPreviewScheduler.scheduleBrowseScan() },
            inputOriginClassifier = inputOriginClassifier,
            currentWindowGeneration = { accessibilityGateway.currentWindowGeneration() },
            observeWindowStateEvent = windowContextTracker::observeWindowStateEvent,
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
