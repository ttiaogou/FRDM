package com.freedom.accessibility

import com.freedom.application.ports.GestureCalibrationContract

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.freedom.adapters.accessibility.android.AndroidAccessibilityEventForwarder
import com.freedom.adapters.accessibility.android.AndroidAccessibilitySubscriptionPolicy
import com.freedom.adapters.accessibility.diagnostics.LatestAccessibilityNodeDump
import com.freedom.core.accessibility.diagnostics.NodeCaptureSource
import com.freedom.adapters.accessibility.android.NodeHierarchyOverlay
import com.freedom.core.runtime.RunMode
import com.freedom.core.accessibility.api.UnifiedAccessibilityEvent
import com.freedom.core.accessibility.api.GlobalAccessibilityAction
import com.freedom.core.accessibility.api.AccessibilityTreeLimits
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.TreeInspectionRequest
import com.freedom.core.accessibility.query.NodeTargetResolver
import com.freedom.core.accessibility.query.NodeTreeIndex
import com.freedom.core.accessibility.overlay.NodeBoundsHighlightPlanner
import com.freedom.adapters.android.MonitorActions
import com.freedom.core.logging.RunLogCategory
import com.freedom.core.logging.RunLogLevel
import com.freedom.adapters.accessibility.android.MonitorOverlay

class FreedomAccessibilityService : AccessibilityService() {
    private var lastAppliedSubscriptionSignature: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowContextTracker get() = dependencies.windowContextTracker
    private var pendingNavigationSourcePageGeneration: Long? = null
    private var pendingNavigationSourceAtUptimeMs: Long = 0L
    private val dependencies by lazy(LazyThreadSafetyMode.NONE) {
        com.freedom.app.FreedomApplication.root(this).createAccessibilityServiceDependencies(
            service = this,
            mainHandler = mainHandler,
            addLog = ::addLog,
            dp = ::dp,
            requestScreenOff = ::requestScreenOff,
            refreshViews = ::refreshViews
        )
    }
    private val monitorRepository get() = dependencies.monitorRepository
    private val diagnostics get() = dependencies.diagnostics
    private val appLabelResolver get() = dependencies.appLabelResolver
    private val inputOriginClassifier get() = dependencies.inputOriginClassifier
    private val touchInteractionMonitor get() = dependencies.touchInteractionMonitor
    private val monitorLogPresenter get() = dependencies.monitorLogPresenter
    private val targetAppReadinessController get() = dependencies.targetAppReadinessController
    private val runSessionCoordinator get() = dependencies.runSessionCoordinator
    private val accessibilityEventPipelineDelegate get() = dependencies.accessibilityEventPipelineDelegate
    private val accessibilityEventPipeline get() = dependencies.accessibilityEventPipeline
    private val accessibilityGateway get() = dependencies.accessibilityGateway
    private val gestureVisualizerDelegate get() = dependencies.gestureVisualizerDelegate
    private val gestureVisualizer get() = dependencies.gestureVisualizer
    private val gestureSettingsRepository get() = dependencies.gestureSettingsRepository
    private val gestureRuntimeDelegate get() = dependencies.gestureRuntimeDelegate
    private val gestureRuntime get() = dependencies.gestureRuntime
    private val gesturePolicy get() = dependencies.gesturePolicy
    private val taskScheduler get() = dependencies.taskScheduler
    private val overlayPort get() = dependencies.overlayPort
    private val globalNodeLayoutVisualizationUseCase get() = dependencies.globalNodeLayoutVisualizationUseCase
    private val momoSocialAdapterDelegate get() = dependencies.momoSocialAdapterDelegate
    private val momoSocialAdapter get() = dependencies.momoSocialAdapter
    private val unifiedAccessibilityEntry get() = dependencies.unifiedAccessibilityEntry
    private val accountBrowseWorkflow get() = dependencies.accountBrowseWorkflow
    private val accountBrowseRuntimeAdapter get() = dependencies.accountBrowseRuntimeAdapter
    private val monitorSnapshotRepository get() = dependencies.monitorSnapshotRepository
    private val accessibilityTreePreviewUseCase get() = dependencies.accessibilityTreePreviewUseCase
    private val monitorPreviewSchedulerDelegate get() = dependencies.monitorPreviewSchedulerDelegate
    private val monitorPreviewScheduler get() = dependencies.monitorPreviewScheduler
    private val gestureCalibrationHost get() = dependencies.gestureCalibrationHost
    private val gestureCalibrationController get() = dependencies.gestureCalibrationController
    private val serviceReceiverRegistryDelegate get() = dependencies.serviceReceiverRegistryDelegate
    private val serviceReceiverRegistry get() = dependencies.serviceReceiverRegistry
    private val serviceRecoveryCoordinatorDelegate get() = dependencies.serviceRecoveryCoordinatorDelegate
    private val serviceRecoveryCoordinator get() = dependencies.serviceRecoveryCoordinator
    private val activityRecoveryCoordinator get() = dependencies.activityRecoveryCoordinator

    private val accessibilityEventForwarder by lazy(LazyThreadSafetyMode.NONE) {
        AndroidAccessibilityEventForwarder(
            activeMode = runSessionCoordinator::activeMode,
            targetPackage = monitorRepository::targetPackage,
            isRunRequested = monitorRepository::isRunRequested,
            onLog = ::addLog,
            onGlobalTouch = { type, eventTimeMs, callbackUptimeMs, packageName, windowId ->
                touchInteractionMonitor.record(type, eventTimeMs, callbackUptimeMs, packageName, windowId)
                inputOriginClassifier.noteExpectedNavigationTouch(eventTimeMs)
            },
            onWindowTransition = ::handleAccessibilityWindowTransition,
            pipeline = accessibilityEventPipeline,
            currentRunGeneration = runSessionCoordinator::currentRunGeneration,
            currentWindowGeneration = accessibilityGateway::currentWindowGeneration,
            onFilteredWindowObservation = { sourcePackage ->
                // Freedom's own log/overlay refresh cannot wake the entry reader.
                if (sourcePackage != packageName) dependencies.requestIndependentPageObservation()
            }
        )
    }

    private val targetChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runSessionCoordinator.onTargetPackageChanged("监听目标已更新")
            activityRecoveryCoordinator.stop()
            windowContextTracker.clearActivityClass()
            LatestAccessibilityNodeDump.clear()
            overlayPort.clearNodeBoundsHighlight()
            NodeHierarchyOverlay.clear()
            globalNodeLayoutVisualizationUseCase.clear()
            applyTargetPackageFilter()
            activityRecoveryCoordinator.ensureStarted()
            addLog("监听目标已更新：${monitorRepository.targetPackage()}；清除上一目标的节点快照")
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Only an explicit external refresh may reconcile or wake a runtime.
            if (intent?.action != MonitorActions.ACTION_REFRESH) return
            runSessionCoordinator.onExternalRefresh()
            applyTargetPackageFilter()
            if (!monitorRepository.isRunRequested()) {
                activityRecoveryCoordinator.stop()
                windowContextTracker.clearActivityClass()
            } else {
                activityRecoveryCoordinator.ensureStarted()
            }
        }
    }

    private val bootRecoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MonitorActions.ACTION_BOOT_RECOVERY) {
                serviceRecoveryCoordinator.onBootRecoverySignal()
            }
        }
    }

    private val manualNodeCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            addLog("节点采集请求[MANUAL]：已进入串行采集队列；仅采集当前目标 App 的同包名窗口；不会与程序自主采集并发遍历")
            accessibilityTreePreviewUseCase.captureManual()
        }
    }

    private val nodeBoundsHighlightReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MonitorActions.ACTION_HIGHLIGHT_NODE_BOUNDS -> handleNodeBoundsHighlightRequest(intent)
                MonitorActions.ACTION_SHOW_NODE_LAYOUT_BOUNDS -> handleNodeLayoutBoundsRequest(intent)
                MonitorActions.ACTION_SHOW_GLOBAL_NODE_LAYOUT_BOUNDS -> handleGlobalNodeLayoutBoundsRequest()
                MonitorActions.ACTION_SHOW_GLOBAL_NODE_HIERARCHY -> handleGlobalNodeHierarchyRequest()
                MonitorActions.ACTION_CLEAR_GLOBAL_NODE_LAYOUT_BOUNDS -> {
                    globalNodeLayoutVisualizationUseCase.clear()
                    NodeHierarchyOverlay.clear()
                }
                MonitorActions.ACTION_CLEAR_NODE_BOUNDS_HIGHLIGHT -> {
                    overlayPort.clearNodeBoundsHighlight()
                    addLog("节点高亮已清除[USER_REQUEST]")
                }
            }
        }
    }

    /** Re-captures and resolves a short-lived target in the exact selected target-app window. */
    private fun handleNodeBoundsHighlightRequest(intent: Intent) {
        val source = runCatching {
            NodeCaptureSource.valueOf(
                intent.getStringExtra(MonitorActions.EXTRA_NODE_CAPTURE_SOURCE).orEmpty()
            )
        }.getOrNull()
        val pathIndices = intent.getIntegerArrayListExtra(MonitorActions.EXTRA_NODE_PATH)
        val requestedWindowId = intent.getIntExtra(MonitorActions.EXTRA_NODE_WINDOW_ID, -1)
        if (source == null || pathIndices == null || pathIndices.size > 64 || pathIndices.any { it < 0 }) {
            addLog("节点高亮拒绝[INVALID_REQUEST]：采集来源或节点路径无效")
            overlayPort.clearNodeBoundsHighlight()
            return
        }

        val sourceSnapshot = LatestAccessibilityNodeDump.latestAll(source).firstOrNull { snapshot ->
            snapshot.window.packageName == monitorRepository.targetPackage() &&
                (requestedWindowId < 0 || snapshot.windowId == requestedWindowId)
        }
        if (sourceSnapshot == null) {
            addLog("节点高亮拒绝[SOURCE_SNAPSHOT_UNAVAILABLE]：没有与当前目标应用及所选窗口一致的来源快照")
            overlayPort.clearNodeBoundsHighlight()
            return
        }

        val path = NodePath(pathIndices.toList())
        val sourceIndex = NodeTreeIndex(sourceSnapshot)
        if (NodeBoundsHighlightPlanner.create(sourceSnapshot, path) == null) {
            addLog("节点高亮拒绝[BOUNDS_UNAVAILABLE]：所选节点不存在或没有成功采集有效屏幕边界")
            overlayPort.clearNodeBoundsHighlight()
            return
        }
        val target = sourceIndex.targetFor(path) ?: run {
            addLog("节点高亮拒绝[TARGET_UNAVAILABLE]：无法从当前快照创建短期节点目标")
            overlayPort.clearNodeBoundsHighlight()
            return
        }

        val refreshedSnapshot = runCatching {
            accessibilityGateway.inspectTargetWindows(
                TreeInspectionRequest(
                    maxNodes = AccessibilityTreeLimits.MAX_NODES_PER_SCAN,
                    textCapture = NodeTextCapture.NONE,
                    allowVisibleWindowFallback = true
                )
            ).firstOrNull { snapshot ->
                snapshot.window.packageName == sourceSnapshot.window.packageName &&
                    snapshot.windowId == sourceSnapshot.windowId
            }
        }.getOrNull()
        if (refreshedSnapshot == null) {
            addLog("节点高亮拒绝[REFRESH_FAILED]：无法重新读取所选目标应用窗口")
            overlayPort.clearNodeBoundsHighlight()
            return
        }

        val resolution = NodeTargetResolver.resolve(target, refreshedSnapshot)
        if (!resolution.isResolved) {
            addLog(
                "节点高亮拒绝[RESOLUTION_FAILED]：status=${resolution.status}；" +
                    "scanned=${resolution.scannedNodeCount}；elapsed=${resolution.queryElapsedMs}ms；" +
                    "reason=${resolution.stopReason ?: "NONE"}"
            )
            overlayPort.clearNodeBoundsHighlight()
            return
        }

        val resolvedPath = resolution.resolvedPath ?: run {
            addLog("节点高亮拒绝[RESOLVED_PATH_MISSING]：解析结果没有目标路径")
            overlayPort.clearNodeBoundsHighlight()
            return
        }
        val highlightPlan = NodeBoundsHighlightPlanner.create(refreshedSnapshot, resolvedPath)
        if (highlightPlan == null) {
            addLog("节点高亮拒绝[REFRESHED_BOUNDS_UNAVAILABLE]：最新快照没有可绘制的有效边界")
            overlayPort.clearNodeBoundsHighlight()
            return
        }

        val pathLabel = resolvedPath.childIndices.joinToString("/").ifBlank { "root" }
        val shown = overlayPort.showNodeBoundsHighlight(
            selectedBounds = highlightPlan.selectedBounds,
            ancestorBounds = highlightPlan.ancestorBounds,
            label = highlightPlan.label,
            durationMs = 5_000L
        )
        if (shown) {
            addLog(
                "节点高亮已显示[VALIDATED]：package=${refreshedSnapshot.window.packageName}；" +
                    "windowId=${refreshedSnapshot.windowId}；path=$pathLabel；" +
                    "resolution=${resolution.status}；windowGeneration=${refreshedSnapshot.window.generation}；duration=5000ms"
            )
        } else {
            addLog("节点高亮失败[OVERLAY_ATTACH_FAILED]：无法附加无触摸屏幕边界层")
        }
    }

    /** Draws all freshly captured accessible bounds for one same-package target-app window. */
    private fun handleNodeLayoutBoundsRequest(intent: Intent) {
        val source = runCatching {
            NodeCaptureSource.valueOf(
                intent.getStringExtra(MonitorActions.EXTRA_NODE_CAPTURE_SOURCE).orEmpty()
            )
        }.getOrNull()
        val requestedWindowId = intent.getIntExtra(MonitorActions.EXTRA_NODE_WINDOW_ID, -1)
        if (source == null) {
            addLog("全局节点边界预览拒绝[INVALID_REQUEST]：采集来源无效")
            overlayPort.clearNodeBoundsHighlight()
            return
        }

        val expectedPackage = monitorRepository.targetPackage()
        val sourceSnapshot = LatestAccessibilityNodeDump.latestAll(source).firstOrNull { snapshot ->
            snapshot.window.packageName == expectedPackage && snapshot.windowId == requestedWindowId
        }
        if (sourceSnapshot == null) {
            addLog("全局节点边界预览拒绝[SOURCE_SNAPSHOT_UNAVAILABLE]：目标应用或窗口快照已变化")
            overlayPort.clearNodeBoundsHighlight()
            return
        }

        val freshSnapshot = runCatching {
            accessibilityGateway.inspectTargetWindows(
                TreeInspectionRequest(
                    maxNodes = AccessibilityTreeLimits.MAX_NODES_PER_SCAN,
                    textCapture = NodeTextCapture.NONE,
                    allowVisibleWindowFallback = true
                )
            ).firstOrNull { snapshot ->
                snapshot.window.packageName == expectedPackage &&
                    snapshot.windowId == requestedWindowId &&
                    snapshot.window.generation == sourceSnapshot.window.generation
            }
        }.getOrNull()
        if (freshSnapshot == null) {
            addLog("全局节点边界预览拒绝[REFRESH_FAILED]：所选目标窗口已变化或不可访问")
            overlayPort.clearNodeBoundsHighlight()
            return
        }

        val bounds = freshSnapshot.nodes.asSequence()
            .filter { it.visible && !it.bounds.isEmpty }
            .map { it.bounds }
            .distinct()
            .toList()
        if (bounds.isEmpty()) {
            addLog("全局节点边界预览拒绝[NO_VISIBLE_BOUNDS]：所选窗口没有可绘制节点边界")
            overlayPort.clearNodeBoundsHighlight()
            return
        }
        val drawLimit = 600
        val label = "${expectedPackage.substringAfterLast('.')} 布局边界 · ${bounds.size.coerceAtMost(drawLimit)}/${bounds.size}" +
            if (freshSnapshot.truncated) " · 扫描不完整" else ""
        val shown = overlayPort.showNodeLayoutBounds(
            bounds = bounds,
            label = label,
            durationMs = 12_000L
        )
        if (shown) {
            addLog(
                "全局节点边界预览已显示[VALIDATED]：package=$expectedPackage；" +
                    "windowId=${freshSnapshot.windowId}；bounds=${bounds.size.coerceAtMost(drawLimit)}/${bounds.size}；" +
                    "truncated=${freshSnapshot.truncated}；duration=12000ms"
            )
        } else {
            addLog("全局节点边界预览失败[OVERLAY_ATTACH_FAILED]：无法附加无触摸边界层")
        }
    }

    /** Platform receiver entry; the application use case owns the capture-and-draw flow. */
    private fun handleGlobalNodeLayoutBoundsRequest() {
        NodeHierarchyOverlay.clear()
        globalNodeLayoutVisualizationUseCase.show()
    }

    private fun handleGlobalNodeHierarchyRequest() {
        val snapshots = globalNodeLayoutVisualizationUseCase.snapshots()
        if (!NodeHierarchyOverlay.show(this, snapshots)) {
            addLog("节点层级视图未显示[NO_CACHED_SNAPSHOT]：请先从悬浮按钮执行全局节点可视化")
        } else {
            addLog("节点层级浮层已显示[DETACHED_SNAPSHOT]：windows=${snapshots.size}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceReceiverRegistry.register(
            targetChangedReceiver,
            IntentFilter(MonitorActions.ACTION_TARGET_CHANGED)
        )
        serviceReceiverRegistry.register(
            refreshReceiver,
            IntentFilter(MonitorActions.ACTION_REFRESH)
        )
        serviceReceiverRegistry.register(
            bootRecoveryReceiver,
            IntentFilter(MonitorActions.ACTION_BOOT_RECOVERY)
        )
        serviceReceiverRegistry.register(
            manualNodeCaptureReceiver,
            IntentFilter(MonitorActions.ACTION_MANUAL_NODE_CAPTURE)
        )
        val nodeHighlightFilter = IntentFilter().apply {
            addAction(MonitorActions.ACTION_HIGHLIGHT_NODE_BOUNDS)
            addAction(MonitorActions.ACTION_SHOW_NODE_LAYOUT_BOUNDS)
            addAction(MonitorActions.ACTION_SHOW_GLOBAL_NODE_LAYOUT_BOUNDS)
            addAction(MonitorActions.ACTION_SHOW_GLOBAL_NODE_HIERARCHY)
            addAction(MonitorActions.ACTION_CLEAR_GLOBAL_NODE_LAYOUT_BOUNDS)
            addAction(MonitorActions.ACTION_CLEAR_NODE_BOUNDS_HIGHLIGHT)
        }
        serviceReceiverRegistry.register(nodeBoundsHighlightReceiver, nodeHighlightFilter)
        val calibrationFilter = IntentFilter().apply {
            addAction(GestureCalibrationContract.ACTION_START_CALIBRATION)
            addAction(GestureCalibrationContract.ACTION_CANCEL_CALIBRATION)
        }
        serviceReceiverRegistry.register(gestureCalibrationController.receiver, calibrationFilter)
        // Subscribe to target observations before reconciling the run authorization.
        applyTargetPackageFilter()
        serviceRecoveryCoordinator.onServiceConnected()
        applyTargetPackageFilter()
        // Reacquire live Activity evidence even when Momo stays on the same page and
        // Android emits no new TYPE_WINDOW_STATE_CHANGED callback after reconnect.
        activityRecoveryCoordinator.restart()
        diagnostics.recordStructured(
            eventType = "accessibility_service_connected",
            category = RunLogCategory.LIFECYCLE,
            level = RunLogLevel.INFO,
            component = "FreedomAccessibilityService",
            message = "无障碍服务连接回调已完成运行时协调与事件订阅；服务连接不等同于业务任务就绪",
            packageName = packageName,
            activityClass = windowContextTracker.currentActivityClass(),
            currentState = "CONNECTED",
            result = "CONNECTED"
        )
        addLog("无障碍服务已连接；按运行授权应用目标包名和事件类型订阅策略")
    }

    private fun applyTargetPackageFilter() {
        runCatching {
            val info = serviceInfo ?: return
            val targetPackage = monitorRepository.targetPackage()
            val activeMode = runSessionCoordinator.activeMode()
            val subscription = AndroidAccessibilitySubscriptionPolicy.forRunMode(
                targetPackage = targetPackage,
                fallbackPackage = packageName,
                runMode = activeMode,
                runRequested = monitorRepository.isRunRequested()
            )
            val signature = "${subscription.packageNames?.joinToString(",") ?: "<GLOBAL_FOR_BROWSE_TOUCH>"}|${subscription.eventTypes}"
            if (signature == lastAppliedSubscriptionSignature) return
            if (lastAppliedSubscriptionSignature != null) {
                touchInteractionMonitor.appendSummary("无障碍订阅切换")
            }

            // Never let a short-lived dispatch ledger entry cross a target/mode subscription change.
            inputOriginClassifier.clear()
            touchInteractionMonitor.reset("目标包名或运行模式订阅发生变化")
            addLog("输入来源账本[RESET]：目标包名或运行模式订阅发生变化；清理旧动作关联；旧版本持久化复核状态保留至显式新运行清理")

            info.eventTypes = subscription.eventTypes
            info.packageNames = subscription.packageNames?.toTypedArray()
            setServiceInfo(info)
            lastAppliedSubscriptionSignature = signature

            when (activeMode) {
                RunMode.ACCOUNT_BROWSE -> addLog(
                    "无障碍订阅：自动浏览；保留系统触摸开始/结束事件的 Android 兼容例外（packageNames=null）；" +
                        "回调入口先按目标包名过滤普通应用事件，非目标事件不读取 source、不进入归一化"
                )
                RunMode.AI_REPLY -> addLog(
                    "无障碍订阅：AI 回复；普通事件由服务回调严格限制在目标包名；" +
                        "额外接收系统全局触摸开始/结束事件用于用户优先权仲裁；不读取非目标应用节点"
                )
                null -> addLog(
                    if (monitorRepository.isRunRequested())
                        "无障碍订阅：统一页面观察待命；业务尚未分配；普通事件仍严格过滤目标包名"
                    else "无障碍订阅：空闲；仅订阅目标包名事件"
                )
            }
        }.onFailure {
            addLog("更新目标应用过滤器失败：${it.javaClass.simpleName}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        accessibilityEventForwarder.accept(event)
    }

    private fun handleAccessibilityWindowTransition(event: AccessibilityEvent, activeMode: RunMode?) {
        // Preserve the existing transition ordering and attribution semantics. This callback is
        // owned by the forwarder boundary; the Service callback itself remains pure forwarding.
        val sourcePageGeneration = accessibilityGateway.currentWindowGeneration()
        accessibilityGateway.noteWindowTransition()
        windowContextTracker.invalidateRecoveredActivity()
        activityRecoveryCoordinator.onWindowTransition()
        overlayPort.clearNodeBoundsHighlight()
        NodeHierarchyOverlay.clear()
        if (globalNodeLayoutVisualizationUseCase.snapshots().isNotEmpty()) {
            globalNodeLayoutVisualizationUseCase.clear()
        }
        if (activeMode == RunMode.AI_REPLY) {
            val foregroundPackage = accessibilityGateway.observeForegroundWindowForReadiness()
            addLog("AI启动保护[FOREGROUND_OBSERVED]：activeWindowPackage=${foregroundPackage.ifBlank { "UNKNOWN" }}；targetPackage=${monitorRepository.targetPackage()}；静默窗口=5000ms")
        }
        targetAppReadinessController.invalidate("检测到窗口或Activity变化；必须重新确认目标应用和业务页面就绪", android.os.SystemClock.uptimeMillis())
        val isTargetWindowEvent = event.packageName?.toString() == monitorRepository.targetPackage()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && isTargetWindowEvent) {
            if (pendingNavigationSourcePageGeneration == null || event.eventTime - pendingNavigationSourceAtUptimeMs > NAVIGATION_TRANSITION_EVENT_PAIR_MS) {
                pendingNavigationSourcePageGeneration = sourcePageGeneration
                pendingNavigationSourceAtUptimeMs = event.eventTime
            }
        }
        val pairedSource = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isTargetWindowEvent) {
            pendingNavigationSourcePageGeneration?.takeIf {
                event.eventTime >= pendingNavigationSourceAtUptimeMs && event.eventTime - pendingNavigationSourceAtUptimeMs <= NAVIGATION_TRANSITION_EVENT_PAIR_MS
            }
        } else null
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isTargetWindowEvent) {
            inputOriginClassifier.noteActivityTransition(
                packageName = monitorRepository.targetPackage(),
                targetActivityClass = event.className?.toString().orEmpty(),
                sourcePageGeneration = pairedSource ?: sourcePageGeneration,
                targetPageGeneration = accessibilityGateway.currentWindowGeneration(),
                targetWindowId = runCatching { event.windowId }.getOrDefault(-1),
                transitionAtUptimeMs = event.eventTime,
                runGeneration = runSessionCoordinator.currentRunGeneration()
            )
            val attachedSelfNavigation = inputOriginClassifier.takeAttachedSelfNavigation()
            // Business-facing navigation attribution now crosses the same normalized entry.
            unifiedAccessibilityEntry.accept(
                UnifiedAccessibilityEvent.NavigationObservation(
                    activityClass = event.className?.toString().orEmpty(),
                    attachedSelfNavigation = attachedSelfNavigation
                )
            )
            pendingNavigationSourcePageGeneration = null
            pendingNavigationSourceAtUptimeMs = 0L
        }
        val currentPageGeneration = accessibilityGateway.currentWindowGeneration()
        if (gestureRuntimeDelegate.isInitialized()) {
            gestureRuntime.onPageTransition(currentPageGeneration, "accessibility_window_or_activity_transition")
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun requestScreenOff(): Boolean =
        accessibilityGateway.performGlobalAction(GlobalAccessibilityAction.LOCK_SCREEN)

    private fun addLog(message: String) {
        monitorLogPresenter.addLog(message)
        // Keep the AI collection ZIP self-contained: archive the same monitor log content
        // only while its run directory is open. Do not initialize the social adapter merely to log.
        if (momoSocialAdapterDelegate.isInitialized()) {
            runCatching { momoSocialAdapter.recordRuntimeLog(message) }
        }
    }

    private fun refreshViews() {
        monitorLogPresenter.refresh()
    }

    override fun onInterrupt() {
        activityRecoveryCoordinator.stop()
        diagnostics.recordStructured(
            eventType = "accessibility_service_interrupted",
            category = RunLogCategory.LIFECYCLE,
            level = RunLogLevel.WARN,
            component = "FreedomAccessibilityService",
            message = "系统调用 AccessibilityService.onInterrupt；该回调不等同于服务销毁或授权撤销",
            packageName = packageName,
            activityClass = windowContextTracker.currentActivityClass(),
            currentState = "INTERRUPTED",
            reason = "SYSTEM_INTERRUPT"
        )
        touchInteractionMonitor.appendSummary("AccessibilityService.onInterrupt")
        inputOriginClassifier.clear()
        pendingNavigationSourcePageGeneration = null
        pendingNavigationSourceAtUptimeMs = 0L
        targetAppReadinessController.invalidate(
            "无障碍服务中断；应用就绪状态失效",
            android.os.SystemClock.uptimeMillis()
        )
        MonitorOverlay.releaseAllObservationVisibility("AccessibilityService.onInterrupt")
        overlayPort.clearNodeBoundsHighlight()
        NodeHierarchyOverlay.clear()
        globalNodeLayoutVisualizationUseCase.clear()
        runSessionCoordinator.onAccessibilityServiceInterrupted(
            "无障碍服务被系统中断；请检查系统设置后重新启动"
        )
        addLog("无障碍服务被系统中断")
    }

    override fun onDestroy() {
        activityRecoveryCoordinator.stop()
        diagnostics.recordStructured(
            eventType = "accessibility_service_destroyed",
            category = RunLogCategory.LIFECYCLE,
            level = RunLogLevel.WARN,
            component = "FreedomAccessibilityService",
            message = "AccessibilityService.onDestroy 开始执行资源释放和运行时断开清理",
            packageName = packageName,
            activityClass = windowContextTracker.currentActivityClass(),
            currentState = "DESTROYING",
            reason = "SERVICE_DESTROY_CALLBACK"
        )
        touchInteractionMonitor.appendSummary("AccessibilityService.onDestroy")
        inputOriginClassifier.clear()
        pendingNavigationSourcePageGeneration = null
        pendingNavigationSourceAtUptimeMs = 0L
        targetAppReadinessController.invalidate(
            "无障碍服务销毁；应用就绪状态失效",
            android.os.SystemClock.uptimeMillis()
        )
        // Seal the route and release its lease before shutting down adapters and callbacks.
        runSessionCoordinator.onServiceDestroyed()
        MonitorOverlay.releaseAllObservationVisibility("AccessibilityService.onDestroy")
        overlayPort.clearNodeBoundsHighlight()
        NodeHierarchyOverlay.clear()
        if (globalNodeLayoutVisualizationUseCase.snapshots().isNotEmpty()) {
            globalNodeLayoutVisualizationUseCase.clear()
        }
        if (monitorPreviewSchedulerDelegate.isInitialized()) monitorPreviewScheduler.cancel()
        if (momoSocialAdapterDelegate.isInitialized()) momoSocialAdapter.close()
        if (serviceRecoveryCoordinatorDelegate.isInitialized()) {
            serviceRecoveryCoordinator.onServiceDestroyed()
        }
        mainHandler.removeCallbacksAndMessages(null)
        if (accessibilityEventPipelineDelegate.isInitialized()) {
            accessibilityEventPipeline.resetDuplicateWindow()
        }
        if (serviceReceiverRegistryDelegate.isInitialized()) {
            serviceReceiverRegistry.unregisterAll()
        }
        gestureCalibrationController.onServiceDestroyed()
        if (gestureVisualizerDelegate.isInitialized()) gestureVisualizer.clear()
        super.onDestroy()
    }

    companion object {
        private const val NAVIGATION_TRANSITION_EVENT_PAIR_MS = 1_500L
        internal const val AI_CHAT_INSPECT_DEBOUNCE_MS = 300L
        internal const val AI_CHAT_REGION_DEBOUNCE_MS = 500L

    }
}
