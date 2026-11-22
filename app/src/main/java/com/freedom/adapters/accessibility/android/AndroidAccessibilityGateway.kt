package com.freedom.adapters.accessibility.android

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.core.accessibility.api.AccessibilityGateway
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityNodeCaptureEvidence
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.AccessibilityActionSnapshot
import com.freedom.core.accessibility.api.AccessibilityCollectionItemSnapshot
import com.freedom.core.accessibility.api.AccessibilityCollectionSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.ActionResult
import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.GestureCompletion
import com.freedom.core.accessibility.api.GestureResultListener
import com.freedom.core.accessibility.api.GestureSpec
import com.freedom.core.accessibility.api.GestureExecutionPort
import com.freedom.core.accessibility.api.GestureDispatchPurpose
import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.api.GestureStrokeSpec
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.GlobalAccessibilityAction
import com.freedom.core.accessibility.api.NodeAction
import com.freedom.core.accessibility.api.NodeActionRequest
import com.freedom.core.accessibility.api.NodeIdentityFingerprint
import com.freedom.core.accessibility.api.NodeTargetRelocationPolicy
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.TreeInspectionRequest
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.events.InputOriginClassifier
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.UiWriteAuthorization
import com.freedom.core.runtime.UiWriteScope
import com.freedom.core.runtime.TargetAppPageReadiness
import com.freedom.core.runtime.TargetAppReadinessController
import com.freedom.core.runtime.TargetAppReadinessEvidence
import com.freedom.core.runtime.TargetAppReadinessStatus
import com.freedom.core.runtime.TargetAppReadinessSnapshot
import com.freedom.core.accessibility.query.NodeTargetResolver
import java.util.ArrayDeque

private const val MAX_TARGET_WINDOWS_PER_CAPTURE = 8

/**
 * Android implementation of the feature-facing accessibility gateway.
 * AccessibilityNodeInfo is acquired per call, never returned, and always recycled here.
 */
internal class AndroidAccessibilityGateway(
    private val service: AccessibilityService,
    private val targetPackage: () -> String,
    private val activityClass: () -> String,
    private val inputOriginClassifier: InputOriginClassifier,
    private val activeRunMode: () -> RunMode?,
    private val inspectPageReadiness: (AccessibilityNodeInfo, String, RunMode) -> TargetAppPageReadiness,
    private val targetAppReadinessController: TargetAppReadinessController,
    private val uiWriteAuthorization: UiWriteAuthorization,
    private val onReadinessDiagnostic: (String) -> Unit = {},
    private val recoveredActivityBindingCurrent: (Int, Long) -> Boolean = { _, _ -> true },
    private val onRecoveredActivityBindingMismatch: () -> Unit = {}
) : AccessibilityGateway, GestureExecutionPort, AndroidActiveWindowRootAccess, AndroidScreenshotCapture {

    @Volatile
    private var windowGeneration: Long = 0L
    private val displayContextLock = Any()
    private val rootDiagnosticLock = Any()
    private val displayContextTracker by lazy(LazyThreadSafetyMode.NONE) {
        AndroidDisplayContextTracker(service) {
            synchronized(displayContextLock) { windowGeneration += 1L }
        }
    }
    @Volatile
    private var lastRootDiagnosticSignature: String = ""
    @Volatile
    private var lastRootDiagnosticAtMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureDispatcher by lazy(LazyThreadSafetyMode.NONE) {
        AndroidGestureDispatcher(
            service = service,
            targetPackage = targetPackage,
            activityClass = activityClass,
            inputOriginClassifier = inputOriginClassifier,
            targetAppReadinessController = targetAppReadinessController,
            onReadinessDiagnostic = onReadinessDiagnostic,
            currentWindowGeneration = { windowGeneration },
            activeWindowId = ::activeWindowId,
            currentActiveWindowPackage = ::currentActiveWindowPackage,
            inspectAutonomousDispatchReadiness = ::inspectAutonomousDispatchReadiness
        )
    }
    private val screenshotCapture by lazy(LazyThreadSafetyMode.NONE) {
        AndroidScreenshotCaptureCoordinator(
            service = service,
            targetPackage = targetPackage,
            activityClass = activityClass,
            mainHandler = mainHandler
        )
    }
    private val nodeSnapshotReader by lazy(LazyThreadSafetyMode.NONE) {
        AndroidNodeSnapshotReader(
            createWindowRef = ::createWindowRef,
            recycleNode = ::recycle
        )
    }

    /** Called by the Service host before routing a new window transition event. */
    fun noteWindowTransition() {
        synchronized(displayContextLock) {
            windowGeneration += 1L
        }
    }

    /** Current page/window generation used to prevent stale action-feedback matches. */
    fun currentWindowGeneration(): Long = windowGeneration

    /** Records the package in the actual active window; event package alone is not foreground proof. */
    fun observeForegroundWindowForReadiness(nowUptimeMs: Long = SystemClock.uptimeMillis()): String {
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        val foregroundPackage = if (root == null) "" else try {
            safeString { root.packageName?.toString().orEmpty() }
        } finally {
            recycle(root)
        }
        targetAppReadinessController.observeForegroundPackage(
            targetPackage = targetPackage(),
            foregroundPackage = foregroundPackage,
            nowUptimeMs = nowUptimeMs
        )
        return foregroundPackage
    }

    override fun captureActiveDisplay(
        expectedPackage: String,
        expectedActivity: String,
        callback: (AndroidScreenshotFrame?) -> Unit
    ) = screenshotCapture.captureActiveDisplay(expectedPackage, expectedActivity, callback)

    override fun displayMetrics(): DisplayMetricsSnapshot = displayContextTracker.snapshot()

    /** Captures the active window without restricting it to the currently selected social app. */
    override fun currentGestureWindow(): WindowRef? {
        // Refresh first so a rotation/resolution change also advances the captured generation.
        displayContextTracker.snapshot()
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        return try {
            val packageName = safeString { root.packageName?.toString().orEmpty() }
            if (packageName.isBlank()) return null
            val currentWindowId = safeInt { root.windowId }.takeIf { it >= 0 } ?: -1
            WindowRef(
                packageName = packageName,
                activityClass = activityForWindow(currentWindowId),
                generation = windowGeneration,
                windowId = currentWindowId
            )
        } finally {
            recycle(root)
        }
    }

    /** Rechecks package, Activity, window ID and page generation immediately before dispatch. */
    override fun isGestureWindowCurrent(window: WindowRef): Boolean {
        if (window.generation != windowGeneration) return false
        val current = currentGestureWindow() ?: return false
        return current.packageName == window.packageName &&
            current.generation == window.generation &&
            (current.windowId < 0 || window.windowId < 0 || current.windowId == window.windowId) &&
            (current.activityClass.isBlank() || window.activityClass.isBlank() ||
                current.activityClass == window.activityClass)
    }

    override fun inspectActiveWindow(request: TreeInspectionRequest): AccessibilityTreeSnapshot? {
        val deadlineNanos = inspectionDeadline(request.maxDurationMs)
        return nodeSnapshotReader.inspectRoot(
            acquireTargetRoot(request.allowVisibleWindowFallback),
            request,
            deadlineNanos = deadlineNanos
        )
    }

    /**
     * Captures a previously confirmed list subtree without walking unrelated toolbar/window
     * branches. The path is never trusted by itself: the active window, package, window ID and
     * expected resource identity are revalidated before the subtree root is traversed.
     */
    override fun inspectNodeSubtree(
        target: NodeTarget,
        request: TreeInspectionRequest
    ): AccessibilityTreeSnapshot? {
        if (target.window.packageName != targetPackage().trim() ||
            !isWindowCurrent(target.window)
        ) return null

        val deadlineNanos = inspectionDeadline(request.maxDurationMs)
        var current: AccessibilityNodeInfo? = acquireTargetRoot(request.allowVisibleWindowFallback)
            ?: return null
        try {
            val rootPackage = safeString { current?.packageName?.toString().orEmpty() }
            val rootWindowId = safeInt { current?.windowId ?: -1 }
            if (rootPackage != target.window.packageName ||
                (target.window.windowId >= 0 && rootWindowId >= 0 && rootWindowId != target.window.windowId)
            ) return null

            for (childIndex in target.path.childIndices) {
                if (System.nanoTime() >= deadlineNanos) return null
                val parent = current ?: return null
                val child = runCatching { parent.getChild(childIndex) }.getOrNull()
                recycle(parent)
                current = null
                current = child ?: return null
            }

            val subtreeRoot = current ?: return null
            val actualPackage = safeString { subtreeRoot.packageName?.toString().orEmpty() }
            val actualWindowId = safeInt { subtreeRoot.windowId }
            val actualViewId = safeString { subtreeRoot.viewIdResourceName.orEmpty() }
            val actualClassName = safeString { subtreeRoot.className?.toString().orEmpty() }
            if (actualPackage != target.window.packageName ||
                (target.window.windowId >= 0 && actualWindowId >= 0 && actualWindowId != target.window.windowId) ||
                (!target.expectedViewId.isNullOrBlank() && actualViewId != target.expectedViewId) ||
                (!target.expectedClassName.isNullOrBlank() && actualClassName != target.expectedClassName) ||
                !isWindowCurrent(target.window)
            ) return null

            // inspectRoot takes ownership and always recycles the supplied node. Preserve the
            // original absolute path so NodeTreeIndex parent/child relationships still work.
            current = null
            val snapshot = nodeSnapshotReader.inspectRoot(
                subtreeRoot,
                request,
                target.window.windowId,
                deadlineNanos,
                initialPath = target.path.childIndices
            ) ?: return null
            if (!isWindowCurrent(target.window)) return null
            val resolvedWindowId = target.window.windowId.takeIf { it >= 0 } ?: snapshot.windowId
            return snapshot.copy(
                window = target.window.copy(windowId = resolvedWindowId),
                windowId = resolvedWindowId
            )
        } finally {
            current?.let(::recycle)
        }
    }

    /** Captures all accessible windows whose root package matches the selected target app. */
    override fun inspectTargetWindows(request: TreeInspectionRequest): List<AccessibilityTreeSnapshot> {
        val expectedPackage = targetPackage().trim()
        if (expectedPackage.isBlank()) return emptyList()

        data class WindowRootCandidate(
            val windowId: Int,
            val priority: Int,
            val root: AccessibilityNodeInfo
        )

        val deadlineNanos = inspectionDeadline(request.maxDurationMs)
        val candidates = ArrayList<WindowRootCandidate>()
        val seenWindowIds = HashSet<Int>()
        var windowEnumerationTruncated = false
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        // The framework list is read once, then each window/root acquisition shares one deadline.
        var nextWindowIndex = 0
        for (index in windows.indices) {
            if (System.nanoTime() >= deadlineNanos) {
                nextWindowIndex = index
                windowEnumerationTruncated = true
                break
            }
            val window = windows[index]
            nextWindowIndex = index + 1
            try {
                val root = runCatching { window.root }.getOrNull() ?: continue
                val rootPackage = safeString { root.packageName?.toString().orEmpty() }
                if (rootPackage != expectedPackage) {
                    recycle(root)
                    continue
                }
                val frameworkWindowId = safeInt { window.id }.takeIf { it >= 0 } ?: -1
                val rootWindowId = safeInt { root.windowId }.takeIf { it >= 0 } ?: frameworkWindowId
                if (rootWindowId >= 0 && !seenWindowIds.add(rootWindowId)) {
                    recycle(root)
                    continue
                }
                val isActiveOrFocused = runCatching { window.isActive || window.isFocused }.getOrDefault(false)
                candidates += WindowRootCandidate(
                    windowId = rootWindowId,
                    priority = if (isActiveOrFocused) -1 else index,
                    root = root
                )
            } finally {
                runCatching { window.recycle() }
            }
        }

        // Windows not visited because the time budget elapsed are also released here.
        if (windowEnumerationTruncated) {
            for (index in nextWindowIndex until windows.size) {
                runCatching { windows[index].recycle() }
            }
        }

        // Some devices expose rootInActiveWindow slightly before the matching entry appears in
        // service.windows. It is acquired only while the same aggregate deadline remains open.
        if (System.nanoTime() < deadlineNanos) {
            val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
            if (activeRoot != null) {
                val activePackage = safeString { activeRoot.packageName?.toString().orEmpty() }
                val activeWindowId = safeInt { activeRoot.windowId }.takeIf { it >= 0 } ?: -1
                val isDistinct = activeWindowId >= 0 && seenWindowIds.add(activeWindowId)
                if (activePackage == expectedPackage && (isDistinct || candidates.isEmpty())) {
                    candidates += WindowRootCandidate(activeWindowId, -2, activeRoot)
                } else {
                    recycle(activeRoot)
                }
            }
        } else {
            windowEnumerationTruncated = true
        }

        if (candidates.isEmpty()) return emptyList()
        val prioritized = candidates.sortedBy { it.priority }
        val windowBudgetTruncated = prioritized.size > MAX_TARGET_WINDOWS_PER_CAPTURE
        val selectedRoots = prioritized.take(MAX_TARGET_WINDOWS_PER_CAPTURE)
        prioritized.drop(MAX_TARGET_WINDOWS_PER_CAPTURE).forEach { recycle(it.root) }
        // Node and time limits are aggregate across all same-package windows, not per-window copies.
        val perWindowRequest = request.copy(maxNodes = (request.maxNodes / selectedRoots.size).coerceAtLeast(1))
        val snapshots = ArrayList<AccessibilityTreeSnapshot>(selectedRoots.size)
        selectedRoots.forEach { candidate ->
            val snapshot = nodeSnapshotReader.inspectRoot(
                candidate.root,
                perWindowRequest,
                candidate.windowId,
                deadlineNanos
            ) ?: return@forEach
            snapshots += snapshot.copy(
                truncated = snapshot.truncated || windowBudgetTruncated || windowEnumerationTruncated,
                timeBudgetExceeded = snapshot.timeBudgetExceeded || windowEnumerationTruncated,
                requestedMaxDurationMs = request.maxDurationMs,
                stopReason = snapshot.stopReason ?: when {
                    windowBudgetTruncated -> "WINDOW_BUDGET"
                    windowEnumerationTruncated -> "WINDOW_ENUMERATION_TIME_BUDGET"
                    else -> null
                }
            )
        }
        return snapshots
    }

    private fun inspectionDeadline(maxDurationMs: Long): Long {
        val durationNanos = maxDurationMs.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
        val now = System.nanoTime()
        return if (Long.MAX_VALUE - now < durationNanos) Long.MAX_VALUE else now + durationNanos
    }

    override fun isWindowCurrent(window: WindowRef): Boolean {
        if (window.generation != windowGeneration) return false
        if (window.packageName != targetPackage()) return false
        val currentActivity = activityClass()
        if (window.activityClass.isNotBlank() && currentActivity.isNotBlank() &&
            window.activityClass != currentActivity
        ) return false
        if (window.windowId < 0) {
            return withActiveTargetRoot { root, _ ->
                safeString { root.packageName?.toString().orEmpty() } == window.packageName
            } ?: false
        }
        return runCatching {
            var matched = false
            service.windows.orEmpty().forEach { candidate ->
                try {
                    if (!matched && safeInt { candidate.id } == window.windowId) {
                        val root = runCatching { candidate.root }.getOrNull()
                        if (root != null) {
                            try {
                                matched = safeString { root.packageName?.toString().orEmpty() } == window.packageName
                            } finally {
                                recycle(root)
                            }
                        }
                    }
                } finally {
                    runCatching { candidate.recycle() }
                }
            }
            if (matched) true else {
                withActiveTargetRoot { root, activeWindow ->
                    safeString { root.packageName?.toString().orEmpty() } == window.packageName &&
                        activeWindow.windowId == window.windowId
                } ?: false
            }
        }.getOrDefault(false)
    }

    override fun performNodeAction(request: NodeActionRequest): ActionResult {
        if (!uiWriteAuthorization.permits(UiWriteScope.BROWSE_NODE_ACTION)) {
            return ActionResult(accepted = false, performed = false, reason = "ui_operation_lease_missing")
        }
        val readinessFailure = inspectAutonomousDispatchReadiness()
        if (readinessFailure != null) {
            return ActionResult(accepted = false, performed = false, reason = "app_not_ready:$readinessFailure")
        }
        if (!isWindowCurrent(request.target.window)) {
            return ActionResult(accepted = false, performed = false, reason = "window_stale")
        }

        val relocationEnabled = request.target.identityFingerprint != null &&
            request.target.relocationPolicy == NodeTargetRelocationPolicy.UNIQUE_MATCH_IN_SAME_WINDOW
        val resolutionSnapshot = if (relocationEnabled) {
            // Text is captured only ephemerally so a moved target can be distinguished without
            // persisting it. Legacy path-only targets keep their previous single-path behavior.
            inspectActiveWindow(
                TreeInspectionRequest(
                    maxNodes = NODE_TARGET_RESOLUTION_MAX_NODES,
                    textCapture = NodeTextCapture.EPHEMERAL
                )
            ) ?: return ActionResult(false, false, "target_snapshot_unavailable")
        } else null
        val resolvedPath = if (resolutionSnapshot != null) {
            val resolution = NodeTargetResolver.resolve(request.target, resolutionSnapshot)
            if (!resolution.isResolved) {
                return ActionResult(
                    accepted = false,
                    performed = false,
                    reason = "target_${resolution.status.name.lowercase()}"
                )
            }
            resolution.resolvedPath
                ?: return ActionResult(false, false, "target_resolution_missing_path")
        } else {
            request.target.path
        }

        // Reacquire the live root and verify the same window generation and fingerprint again.
        // This closes the gap between snapshot resolution and the actual semantic action.
        val performed = withActiveTargetRoot { root, window ->
            if (!sameWindowIdentity(window, request.target.window) ||
                (resolutionSnapshot != null && !sameWindowIdentity(window, resolutionSnapshot.window))
            ) return@withActiveTargetRoot false
            val target = resolvePath(root, resolvedPath) ?: return@withActiveTargetRoot false
            try {
                val actualViewId = safeString { target.viewIdResourceName.orEmpty() }
                val actualClass = safeString { target.className?.toString().orEmpty() }
                if (request.target.expectedViewId != null && actualViewId != request.target.expectedViewId) {
                    return@withActiveTargetRoot false
                }
                if (request.target.expectedClassName != null && actualClass != request.target.expectedClassName) {
                    return@withActiveTargetRoot false
                }
                request.target.identityFingerprint?.let { expected ->
                    if (!expected.matches(identityFingerprintFor(target))) {
                        return@withActiveTargetRoot false
                    }
                }
                // Re-evaluate from the live foreground root as close as possible to performAction.
                // The previously resolved node is never treated as a reusable readiness token.
                if (inspectAutonomousDispatchReadiness() != null) {
                    return@withActiveTargetRoot false
                }
                uiWriteAuthorization.withAuthorizedWrite(UiWriteScope.BROWSE_NODE_ACTION) {
                    runCatching { performSemanticNodeAction(target, request.action, request.textArgument) }
                        .getOrDefault(false)
                } ?: false
            } finally {
                recycle(target)
            }
        } ?: false
        return ActionResult(
            accepted = performed,
            performed = performed,
            reason = if (performed) "performed" else "target_unavailable_or_rejected"
        )
    }

    override fun performVerifiedNavigationBack(
        expectedWindow: WindowRef,
        expectedActivityClass: String
    ): Boolean {
        if (!uiWriteAuthorization.permits(UiWriteScope.VERIFIED_AI_BACK)) return false
        val expectedPackage = targetPackage().trim()
        if (expectedActivityClass.isBlank() || expectedPackage.isBlank() ||
            expectedWindow.packageName != expectedPackage ||
            expectedWindow.activityClass != expectedActivityClass ||
            activityClass() != expectedActivityClass ||
            expectedWindow.windowId < 0 || expectedWindow.generation != windowGeneration ||
            runCatching { activeRunMode() }.getOrNull() != RunMode.AI_REPLY
        ) return false

        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return false
        return try {
            val actualPackage = safeString { root.packageName?.toString().orEmpty() }
            val actualWindowId = safeInt { root.windowId }
            val actualActivity = activityClass()
            val stillCurrent = actualPackage == expectedPackage &&
                actualWindowId == expectedWindow.windowId &&
                windowGeneration == expectedWindow.generation &&
                (expectedWindow.activityClass.isBlank() || actualActivity == expectedWindow.activityClass)
            if (!stillCurrent) {
                onReadinessDiagnostic(
                    "经验证导航返回拒绝[STALE_WINDOW]：expectedWindow=${expectedWindow.windowId}@${expectedWindow.generation}；" +
                        "actualWindow=$actualWindowId@$windowGeneration；activity=$actualActivity"
                )
                false
            } else {
                val accepted = uiWriteAuthorization.withAuthorizedWrite(UiWriteScope.VERIFIED_AI_BACK) {
                    runCatching {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }.getOrDefault(false)
                } ?: false
                onReadinessDiagnostic(
                    "经验证导航返回派发：windowId=$actualWindowId；generation=$windowGeneration；" +
                        "platformAccepted=$accepted；路径=VERIFIED_NAVIGATION_BACK"
                )
                accepted
            }
        } finally {
            recycle(root)
        }
    }

    override fun performGlobalAction(action: GlobalAccessibilityAction): Boolean {
        // Lock-screen requests are a safety response to manual-review holds. Every other global
        // action changes the target UI and must pass the same current-page readiness gate.
        if (action != GlobalAccessibilityAction.LOCK_SCREEN) {
            if (!uiWriteAuthorization.permits(UiWriteScope.GLOBAL_NAVIGATION)) return false
            val readinessFailure = inspectAutonomousDispatchReadiness()
            if (readinessFailure != null) return false
        }
        if (action == GlobalAccessibilityAction.LOCK_SCREEN && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        val submit = {
            runCatching {
                service.performGlobalAction(
                    when (action) {
                        GlobalAccessibilityAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                        GlobalAccessibilityAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                        GlobalAccessibilityAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                        GlobalAccessibilityAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        GlobalAccessibilityAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                        GlobalAccessibilityAction.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    }
                )
            }.getOrDefault(false)
        }
        // LOCK_SCREEN is a manual-review safety response, not an autonomous task action.
        return if (action == GlobalAccessibilityAction.LOCK_SCREEN) submit()
        else uiWriteAuthorization.withAuthorizedWrite(UiWriteScope.GLOBAL_NAVIGATION, submit) ?: false
    }

    private fun authorizedGestureDispatch(spec: GestureSpec, submit: () -> GestureSubmission): GestureSubmission {
        if (spec.dispatchPurpose == GestureDispatchPurpose.INTERNAL_CALIBRATION) {
            // Explicit user calibration is allowed only on Freedom's own active window.
            return if (currentActiveWindowPackage() == service.packageName) submit()
            else GestureSubmission.REJECTED
        }
        return uiWriteAuthorization.withAuthorizedWrite(UiWriteScope.AUTONOMOUS_GESTURE, submit)
            ?: GestureSubmission.REJECTED
    }

    override fun dispatchGesture(spec: GestureSpec, listener: GestureResultListener): GestureSubmission =
        authorizedGestureDispatch(spec) { gestureDispatcher.dispatchGesture(spec, listener) }

    override fun dispatchGesture(
        spec: GestureSpec,
        listener: GestureResultListener,
        onPlatformDispatch: () -> Unit
    ): GestureSubmission =
        authorizedGestureDispatch(spec) {
            gestureDispatcher.dispatchGesture(spec, listener, onPlatformDispatch)
        }

    /**
     * Forces the same read-only Android safety evaluation used before dispatch.
     * This does not dispatch a gesture or grant UI ownership. The cached current()
     * diagnostic alone is insufficient after a service restart.
     */
    fun inspectRecoveryReadiness(): TargetAppReadinessSnapshot {
        inspectAutonomousDispatchReadiness()
        return targetAppReadinessController.current()
    }

    private fun inspectAutonomousDispatchReadiness(): String? {
        val expectedPackage = targetPackage()
        val mode = runCatching { activeRunMode() }.getOrNull()
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        if (root == null) {
            val snapshot = targetAppReadinessController.evaluate(
                TargetAppReadinessEvidence(
                    targetPackage = expectedPackage,
                    foregroundPackage = "",
                    activityClass = activityClass(),
                    runMode = mode,
                    windowId = -1,
                    pageGeneration = windowGeneration,
                    activeWindowAvailable = false
                ),
                SystemClock.uptimeMillis()
            )
            emitReadinessRejection(snapshot.status, snapshot.reason, mode, -1)
            return snapshot.reason
        }

        try {
            val foregroundPackage = safeString { root.packageName?.toString().orEmpty() }
            val windowId = safeInt { root.windowId }.takeIf { it >= 0 } ?: -1
            val currentActivity = activityForWindow(windowId)
            if (currentActivity.isBlank() && activityClass().isNotBlank()) {
                // The recovered identity was tied to a different window or generation.
                // Revoke that provisional clue and re-enter read-only evidence collection.
                onRecoveredActivityBindingMismatch()
            }
            val pageReadiness = if (mode != null && foregroundPackage == expectedPackage) {
                runCatching { inspectPageReadiness(root, currentActivity, mode) }
                    .getOrElse {
                        TargetAppPageReadiness(
                            ready = false,
                            reason = "页面准入检查异常：${it.javaClass.simpleName}"
                        )
                    }
            } else {
                TargetAppPageReadiness(
                    ready = false,
                    reason = "未进入目标应用页面检查；前台包名或运行模式不匹配"
                )
            }
            val snapshot = targetAppReadinessController.evaluate(
                TargetAppReadinessEvidence(
                    targetPackage = expectedPackage,
                    foregroundPackage = foregroundPackage,
                    activityClass = currentActivity,
                    runMode = mode,
                    windowId = windowId,
                    pageGeneration = windowGeneration,
                    activeWindowAvailable = foregroundPackage.isNotBlank(),
                    // An incomplete safety scan is transient: let the dispatcher defer the
                    // still-pending task and reacquire a fresh root. The per-window retry count
                    // is diagnostic only; exhaustion never authorizes an action or ends the task.
                    blockingReason = pageReadiness.blockingReason.takeUnless { pageReadiness.retryable },
                    businessPageReady = pageReadiness.ready,
                    businessPageReason = pageReadiness.reason
                ),
                SystemClock.uptimeMillis()
            )
            if (snapshot.status != TargetAppReadinessStatus.READY) {
                emitReadinessRejection(snapshot.status, snapshot.reason, mode, windowId)
                return snapshot.reason
            }
            return null
        } finally {
            recycle(root)
        }
    }

    private fun currentActiveWindowPackage(): String {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return ""
        return try {
            safeString { root.packageName?.toString().orEmpty() }
        } finally {
            recycle(root)
        }
    }

    private fun emitReadinessRejection(
        status: TargetAppReadinessStatus,
        reason: String,
        mode: RunMode?,
        windowId: Int
    ) {
        runCatching {
            onReadinessDiagnostic(
                "自主手势派发拒绝[APP_NOT_READY]：status=$status；mode=${mode?.name ?: "NONE"}；" +
                    "windowId=$windowId；reason=$reason"
            )
        }
    }

    override fun <T> withTargetWindowRoots(
        block: (List<AndroidTargetWindowRoot>) -> T
    ): T? {
        val expectedPackage = targetPackage().trim()
        if (expectedPackage.isBlank()) return null
        val roots = ArrayList<AndroidTargetWindowRoot>()
        val seenIds = HashSet<Int>()
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        try {
            for (window in windows) {
                if (roots.size >= MAX_TARGET_WINDOWS_PER_CAPTURE) break
                val root = runCatching { window.root }.getOrNull() ?: continue
                val packageName = safeString { root.packageName?.toString().orEmpty() }
                if (packageName != expectedPackage) {
                    recycle(root)
                    continue
                }
                val fallbackId = safeInt { window.id }.takeIf { it >= 0 } ?: -1
                val windowId = safeInt { root.windowId }.takeIf { it >= 0 } ?: fallbackId
                if (windowId >= 0 && !seenIds.add(windowId)) {
                    recycle(root)
                    continue
                }
                val windowRef = createWindowRef(root, fallbackId)
                if (windowRef == null) {
                    recycle(root)
                    continue
                }
                roots += AndroidTargetWindowRoot(
                    root = root,
                    window = windowRef,
                    active = runCatching { window.isActive }.getOrDefault(false),
                    focused = runCatching { window.isFocused }.getOrDefault(false)
                )
            }
            val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
            if (activeRoot != null) {
                val packageName = safeString { activeRoot.packageName?.toString().orEmpty() }
                val windowId = safeInt { activeRoot.windowId }
                if (packageName == expectedPackage && (windowId < 0 || seenIds.add(windowId))) {
                    val windowRef = createWindowRef(activeRoot)
                    if (windowRef == null) {
                        recycle(activeRoot)
                    } else {
                        val duplicateRoots = roots.filter { it.window.windowId == windowRef.windowId }
                        if (duplicateRoots.isNotEmpty()) {
                            roots.removeAll { it.window.windowId == windowRef.windowId }
                            duplicateRoots.forEach { recycle(it.root) }
                        }
                        roots += AndroidTargetWindowRoot(
                            root = activeRoot,
                            window = windowRef,
                            active = true,
                            focused = true
                        )
                    }
                } else {
                    recycle(activeRoot)
                }
            }
            val ordered = roots.sortedWith(compareByDescending<AndroidTargetWindowRoot> { it.active }
                .thenByDescending { it.focused })
            return block(ordered)
        } finally {
            roots.forEach { recycle(it.root) }
            windows.forEach { runCatching { it.recycle() } }
        }
    }

    override fun isTargetWindowPresent(windowId: Int): Boolean? {
        if (windowId < 0) return null
        val windows = runCatching { service.windows }.getOrNull() ?: return null
        return try {
            windows.any { window -> runCatching { window.id == windowId }.getOrDefault(false) }
        } finally {
            windows.forEach { runCatching { it.recycle() } }
        }
    }

    override fun <T> withActiveWindowRoot(block: (AccessibilityNodeInfo) -> T): T? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        return try {
            block(root)
        } finally {
            recycle(root)
        }
    }

    /**
     * 执行当前输入框的非触摸语义动作。调用方须在本次活动根节点回调中
     * 取得正确输入框并同步调用；不保存节点，不重放旧路径或旧窗口代次。
     */
    override fun performCurrentInputAction(
        node: AccessibilityNodeInfo,
        action: NodeAction,
        text: String?
    ): Boolean = uiWriteAuthorization.withAuthorizedWrite(UiWriteScope.AI_COMPOSER) {
        when (action) {
            NodeAction.FOCUS -> node.isFocused || performSemanticNodeAction(node, action, null)
            NodeAction.SET_TEXT -> text != null && performSemanticNodeAction(node, action, text)
            NodeAction.DISMISS -> false
        }
    } ?: false

    /** 所有非触摸节点动作共用此唯一平台调用点，原有目标校验仍由各自入口负责。 */
    private fun performSemanticNodeAction(node: AccessibilityNodeInfo, action: NodeAction, text: String?): Boolean {
        val arguments = if (action == NodeAction.SET_TEXT && text != null) {
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        } else null
        val platformAction = when (action) {
            NodeAction.FOCUS -> AccessibilityNodeInfo.ACTION_FOCUS
            NodeAction.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
            NodeAction.DISMISS -> AccessibilityNodeInfo.ACTION_DISMISS
        }
        return node.performAction(platformAction, arguments)
    }

    override fun <T> withActiveTargetRoot(block: (AccessibilityNodeInfo, WindowRef) -> T): T? {
        val root = acquireTargetRoot(allowVisibleWindowFallback = false) ?: return null
        return try {
            val window = createWindowRef(root) ?: return null
            block(root, window)
        } finally {
            recycle(root)
        }
    }

    override fun targetFor(
        root: AccessibilityNodeInfo,
        target: AccessibilityNodeInfo,
        window: WindowRef
    ): NodeTarget? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, List<Int>>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to emptyList())
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < 3_000) {
                val (node, path) = queue.removeFirst()
                visited += 1
                try {
                    if (runCatching { node == target }.getOrDefault(false)) {
                        return NodeTarget(
                            window = window,
                            path = NodePath(path),
                            expectedViewId = safeString { target.viewIdResourceName.orEmpty() }
                                .takeIf(String::isNotBlank),
                            expectedClassName = safeString { target.className?.toString().orEmpty() }
                                .takeIf(String::isNotBlank),
                            identityFingerprint = identityFingerprintFor(target),
                            relocationPolicy = NodeTargetRelocationPolicy.UNIQUE_MATCH_IN_SAME_WINDOW
                        )
                    }
                    val childCount = safeInt { node.childCount }
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= 3_000) break
                        safeNode { node.getChild(index) }?.let { child ->
                            queue.add(child to (path + index))
                        }
                    }
                } finally {
                    recycle(node)
                }
            }
        } finally {
            while (queue.isNotEmpty()) recycle(queue.removeFirst().first)
        }
        return null
    }

    private fun acquireTargetRoot(allowVisibleWindowFallback: Boolean): AccessibilityNodeInfo? {
        val expectedPackage = targetPackage().trim()
        if (expectedPackage.isBlank()) {
            logRootAcquisitionFailure(
                "TARGET_PACKAGE_EMPTY",
                "fallback=$allowVisibleWindowFallback; activeRoot=not_requested; windows=not_requested"
            )
            return null
        }

        val activeResult = runCatching { service.rootInActiveWindow }
        val active = activeResult.getOrNull()
        val activeFailure = activeResult.exceptionOrNull()?.javaClass?.simpleName
        var activePackage = ""
        if (active != null) {
            activePackage = safeString { active.packageName?.toString().orEmpty() }
            if (activePackage == expectedPackage) return active
            recycle(active)
        }

        val windowsResult = runCatching { service.windows.toList() }
        val windows = windowsResult.getOrNull().orEmpty()
        val windowsFailure = windowsResult.exceptionOrNull()?.javaClass?.simpleName
        if (windowsFailure != null) {
            logRootAcquisitionFailure(
                "WINDOW_LIST_ACCESS_EXCEPTION",
                "fallback=$allowVisibleWindowFallback; activeRoot=${activeRootState(active == null, activeFailure, activePackage)}; " +
                    "exception=$windowsFailure"
            )
            return null
        }
        if (windows.isEmpty()) {
            logRootAcquisitionFailure(
                "WINDOW_LIST_EMPTY",
                "fallback=$allowVisibleWindowFallback; activeRoot=${activeRootState(active == null, activeFailure, activePackage)}; windowCount=0"
            )
            return null
        }

        var inactiveWindowsFiltered = 0
        var focusStateExceptions = 0
        var rootsAttempted = 0
        var nullRoots = 0
        var rootExceptions = 0
        var foreignPackageRoots = 0
        for (window in windows) {
            try {
                if (!allowVisibleWindowFallback) {
                    val activeOrFocusedResult = runCatching { window.isActive || window.isFocused }
                    if (activeOrFocusedResult.isFailure) focusStateExceptions++
                    if (!activeOrFocusedResult.getOrDefault(false)) {
                        inactiveWindowsFiltered++
                        continue
                    }
                }
                rootsAttempted++
                val rootResult = runCatching { window.root }
                val candidate = rootResult.getOrNull()
                if (candidate == null) {
                    if (rootResult.isFailure) rootExceptions++ else nullRoots++
                    continue
                }
                if (safeString { candidate.packageName?.toString().orEmpty() } == expectedPackage) {
                    return candidate
                }
                foreignPackageRoots++
                recycle(candidate)
            } finally {
                runCatching { window.recycle() }
            }
        }

        val reason = when {
            rootsAttempted == 0 && focusStateExceptions > 0 -> "WINDOW_FOCUS_STATE_ACCESS_EXCEPTION"
            rootsAttempted == 0 && inactiveWindowsFiltered > 0 -> "NO_ACTIVE_OR_FOCUSED_TARGET_WINDOW"
            rootExceptions > 0 && nullRoots == 0 && foreignPackageRoots == 0 -> "WINDOW_ROOT_ACCESS_EXCEPTION"
            nullRoots > 0 && rootExceptions == 0 && foreignPackageRoots == 0 -> "WINDOW_ROOT_NULL"
            foreignPackageRoots > 0 && nullRoots == 0 && rootExceptions == 0 -> "TARGET_PACKAGE_WINDOW_NOT_FOUND"
            else -> "NO_MATCHING_TARGET_WINDOW_ROOT"
        }
        logRootAcquisitionFailure(
            reason,
            "fallback=$allowVisibleWindowFallback; activeRoot=${activeRootState(active == null, activeFailure, activePackage)}; " +
                "windowCount=${windows.size}; rootsAttempted=$rootsAttempted; " +
                "inactiveFiltered=$inactiveWindowsFiltered; focusStateExceptions=$focusStateExceptions; " +
                "nullRoots=$nullRoots; " +
                "rootExceptions=$rootExceptions; foreignPackageRoots=$foreignPackageRoots"
        )
        return null
    }

    private fun activeRootState(wasNull: Boolean, failure: String?, packageName: String): String = when {
        failure != null -> "exception:$failure"
        wasNull -> "null"
        packageName.isBlank() -> "package_unreadable"
        else -> "other_package:$packageName"
    }

    /** Rate-limits identical Root failures while retaining the actual failed acquisition stage. */
    private fun logRootAcquisitionFailure(reason: String, details: String) {
        val signature = "$reason|$details"
        val now = SystemClock.elapsedRealtime()
        val shouldLog = synchronized(rootDiagnosticLock) {
            if (signature == lastRootDiagnosticSignature &&
                now - lastRootDiagnosticAtMs < ROOT_ACQUISITION_DIAGNOSTIC_INTERVAL_MS
            ) {
                false
            } else {
                lastRootDiagnosticSignature = signature
                lastRootDiagnosticAtMs = now
                true
            }
        }
        if (shouldLog) {
            onReadinessDiagnostic("无障碍 Root 获取诊断：reason=$reason；$details")
        }
    }

    private fun activityForWindow(windowId: Int): String =
        if (recoveredActivityBindingCurrent(windowId, windowGeneration)) activityClass() else ""

    private fun createWindowRef(root: AccessibilityNodeInfo, windowIdFallback: Int = -1): WindowRef? {
        displayContextTracker.snapshot()
        val packageName = safeString { root.packageName?.toString().orEmpty() }
        if (packageName.isBlank() || packageName != targetPackage()) return null
        val windowId = safeInt { root.windowId }.takeIf { it >= 0 } ?: windowIdFallback
        return WindowRef(
            packageName = packageName,
            activityClass = activityForWindow(windowId),
            generation = windowGeneration,
            windowId = windowId
        )
    }

    private fun identityFingerprintFor(node: AccessibilityNodeInfo): NodeIdentityFingerprint {
        val parent = safeNode { node.parent }
        return try {
            NodeIdentityFingerprint.create(
                className = safeString { node.className?.toString().orEmpty() },
                viewId = safeString { node.viewIdResourceName.orEmpty() },
                text = safeString { node.text?.toString().orEmpty() },
                contentDescription = safeString { node.contentDescription?.toString().orEmpty() },
                childCount = safeInt { node.childCount },
                parentClassName = parent?.let { safeString { it.className?.toString().orEmpty() } },
                parentViewId = parent?.let { safeString { it.viewIdResourceName.orEmpty() } },
                windowId = safeInt { node.windowId }.takeIf { it >= 0 } ?: -1
            )
        } finally {
            recycle(parent)
        }
    }

    private fun sameWindowIdentity(first: WindowRef, second: WindowRef): Boolean =
        first.packageName == second.packageName &&
            first.generation == second.generation &&
            (first.windowId < 0 || second.windowId < 0 || first.windowId == second.windowId) &&
            (first.activityClass.isBlank() || second.activityClass.isBlank() ||
                first.activityClass == second.activityClass)

    private fun resolvePath(root: AccessibilityNodeInfo, path: NodePath): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(root)
        for (index in path.childIndices) {
            val parent = current ?: return null
            val next = safeNode { parent.getChild(index) }
            recycle(parent)
            current = next ?: return null
        }
        return current
    }

    private fun activeWindowId(): Int {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return -1
        return try {
            val rootPackage = safeString { root.packageName?.toString().orEmpty() }
            if (rootPackage != targetPackage()) -1
            else runCatching { root.windowId }.getOrDefault(-1).takeIf { it >= 0 } ?: -1
        } finally {
            recycle(root)
        }
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        if (node != null) runCatching { node.recycle() }
    }

    private inline fun safeString(block: () -> String): String = runCatching(block).getOrDefault("")
    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)
    private fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()
}

/** Android-only bridge used inside social adapters; never imported by a Feature module. */
internal data class AndroidTargetWindowRoot(
    val root: AccessibilityNodeInfo,
    val window: WindowRef,
    val active: Boolean,
    val focused: Boolean
)

internal interface AndroidActiveWindowRootAccess {
    /** 仅同步操作当前回调中的输入框；允许 FOCUS / SET_TEXT，不提供触摸动作。 */
    fun performCurrentInputAction(node: AccessibilityNodeInfo, action: NodeAction, text: String? = null): Boolean
    fun <T> withActiveTargetRoot(block: (AccessibilityNodeInfo, WindowRef) -> T): T?
    fun <T> withActiveWindowRoot(block: (AccessibilityNodeInfo) -> T): T?
    fun <T> withTargetWindowRoots(block: (List<AndroidTargetWindowRoot>) -> T): T?
    /** null means the windows collection could not be queried; false means the ID is absent. */
    fun isTargetWindowPresent(windowId: Int): Boolean?
    fun targetFor(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo, window: WindowRef): NodeTarget?
}


/** Identifies whether Android captured the target application window or the whole display. */
private const val NODE_TARGET_RESOLUTION_MAX_NODES = 3_000
private const val ROOT_ACQUISITION_DIAGNOSTIC_INTERVAL_MS = 5_000L
