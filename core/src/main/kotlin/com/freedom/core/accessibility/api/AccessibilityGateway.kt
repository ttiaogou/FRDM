package com.freedom.core.accessibility.api

/** Platform-neutral identity for one currently visible accessibility window generation. */
data class WindowRef(
    val packageName: String,
    val activityClass: String,
    val generation: Long,
    /** Android window identity. -1 keeps compatibility with snapshots created before window scoping. */
    val windowId: Int = -1
)

/** Screen coordinates are expressed in physical pixels, never Android Rect instances. */
data class DisplayMetricsSnapshot(
    val widthPixels: Int,
    val heightPixels: Int,
    val density: Float,
    /** Android display identity used by the coordinate snapshot; the current adapter targets the default display. */
    val displayId: Int = 0,
    /** Display rotation in clockwise degrees: 0, 90, 180 or 270. */
    val rotationDegrees: Int = 0
)

data class ScreenRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width == 0 || height == 0
}

/** Child indices from the root. This is a short-lived locator, not a persistent node handle. */
data class NodePath(val childIndices: List<Int>)

data class NodeTarget(
    val window: WindowRef,
    val path: NodePath,
    val expectedViewId: String? = null,
    val expectedClassName: String? = null,
    /** Optional in-memory identity evidence; never stores the original text or description. */
    val identityFingerprint: NodeIdentityFingerprint? = null,
    val relocationPolicy: NodeTargetRelocationPolicy = NodeTargetRelocationPolicy.PATH_ONLY
)

data class AccessibilityActionSnapshot(
    val id: Int,
    val label: String? = null
)

data class AccessibilityCollectionSnapshot(
    val rowCount: Int,
    val columnCount: Int,
    val hierarchical: Boolean
)

data class AccessibilityCollectionItemSnapshot(
    val rowIndex: Int,
    val rowSpan: Int,
    val columnIndex: Int,
    val columnSpan: Int,
    val heading: Boolean,
    val selected: Boolean
)

data class AccessibilityNodeSnapshot(
    val path: NodePath,
    val className: String,
    val viewId: String,
    val text: String?,
    val contentDescription: String?,
    val bounds: ScreenRect,
    val visible: Boolean,
    val enabled: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val selected: Boolean,
    val focused: Boolean,
    val childCount: Int,
    val packageName: String = "",
    val windowId: Int = -1,
    val indexInParent: Int = -1,
    val focusable: Boolean = false,
    val contextClickable: Boolean = false,
    val drawingOrder: Int = 0,
    val password: Boolean = false,
    val hintText: String? = null,
    val stateDescription: String? = null,
    val tooltipText: String? = null,
    val collection: AccessibilityCollectionSnapshot? = null,
    val collectionItem: AccessibilityCollectionItemSnapshot? = null,
    val actions: List<AccessibilityActionSnapshot> = emptyList(),
    val dismissable: Boolean = false,
    val accessibilityFocused: Boolean = false,
    val multiLine: Boolean = false,
    val contentInvalid: Boolean = false,
    val importantForAccessibility: Boolean = false,
    val screenReaderFocusable: Boolean = false,
    val heading: Boolean = false,
    val showingHintText: Boolean = false,
    val captureEvidence: AccessibilityNodeCaptureEvidence = AccessibilityNodeCaptureEvidence(
        // Older/manual snapshot producers predate these fields; do not present their default
        // false values as observations. The Android gateway explicitly supplies real evidence.
        notCapturedFields = setOf(
            AccessibilityNodeField.DISMISSABLE,
            AccessibilityNodeField.ACCESSIBILITY_FOCUSED,
            AccessibilityNodeField.MULTI_LINE,
            AccessibilityNodeField.CONTENT_INVALID,
            AccessibilityNodeField.IMPORTANT_FOR_ACCESSIBILITY,
            AccessibilityNodeField.SCREEN_READER_FOCUSABLE,
            AccessibilityNodeField.HEADING,
            AccessibilityNodeField.SHOWING_HINT_TEXT
        )
    )
) {
    /**
     * Returns the observed state of one field. `EMPTY` means the getter was read successfully but
     * returned no value; it is deliberately different from `NOT_REQUESTED` and `READ_FAILED`.
     */
    fun captureState(
        field: AccessibilityNodeField,
        textCapture: NodeTextCapture
    ): AccessibilityFieldCaptureState {
        if (field in captureEvidence.failedFields) return AccessibilityFieldCaptureState.READ_FAILED
        if (field in captureEvidence.unsupportedFields) return AccessibilityFieldCaptureState.UNSUPPORTED
        if (field in captureEvidence.notCapturedFields) return AccessibilityFieldCaptureState.NOT_REQUESTED
        if (field.requiresTextCapture && textCapture == NodeTextCapture.NONE) {
            return AccessibilityFieldCaptureState.NOT_REQUESTED
        }
        val hasValue = when (field) {
            AccessibilityNodeField.CLASS_NAME -> className.isNotBlank()
            AccessibilityNodeField.VIEW_ID -> viewId.isNotBlank()
            AccessibilityNodeField.PACKAGE_NAME -> packageName.isNotBlank()
            AccessibilityNodeField.WINDOW_ID -> windowId >= 0
            AccessibilityNodeField.BOUNDS -> !bounds.isEmpty
            AccessibilityNodeField.TEXT -> !text.isNullOrBlank()
            AccessibilityNodeField.CONTENT_DESCRIPTION -> !contentDescription.isNullOrBlank()
            AccessibilityNodeField.HINT_TEXT -> !hintText.isNullOrBlank()
            AccessibilityNodeField.STATE_DESCRIPTION -> !stateDescription.isNullOrBlank()
            AccessibilityNodeField.TOOLTIP_TEXT -> !tooltipText.isNullOrBlank()
            AccessibilityNodeField.ACTION_LABELS -> {
                if (AccessibilityNodeField.ACTIONS in captureEvidence.failedFields) {
                    return AccessibilityFieldCaptureState.READ_FAILED
                }
                actions.any { !it.label.isNullOrBlank() }
            }
            AccessibilityNodeField.CHILD_COUNT -> true
            AccessibilityNodeField.CHILD_NODES -> childCount > 0
            AccessibilityNodeField.INDEX_IN_PARENT -> indexInParent >= 0
            AccessibilityNodeField.DRAWING_ORDER -> true
            AccessibilityNodeField.COLLECTION -> collection != null
            AccessibilityNodeField.COLLECTION_ITEM -> collectionItem != null
            AccessibilityNodeField.ACTIONS -> actions.isNotEmpty()
            else -> true
        }
        return if (hasValue) AccessibilityFieldCaptureState.VALUE_PRESENT else AccessibilityFieldCaptureState.EMPTY
    }

    fun target(window: WindowRef): NodeTarget = NodeTarget(
        window = window,
        path = path,
        expectedViewId = viewId.takeIf(String::isNotBlank),
        expectedClassName = className.takeIf(String::isNotBlank)
    )
}

data class AccessibilityTreeSnapshot(
    val window: WindowRef,
    val nodes: List<AccessibilityNodeSnapshot>,
    val visitedNodeCount: Int,
    /** Backward-compatible summary: true when any node, depth or time budget omitted part of the tree. */
    val truncated: Boolean,
    val requestedMaxNodes: Int = visitedNodeCount,
    val requestedMaxDepth: Int = 48,
    val walkTruncated: Boolean = truncated,
    val depthTruncated: Boolean = false,
    val capturedAtEpochMs: Long = System.currentTimeMillis(),
    val windowId: Int = nodes.firstOrNull()?.windowId ?: -1,
    val stopReason: String? = when {
        walkTruncated -> "NODE_BUDGET"
        depthTruncated -> "DEPTH_BUDGET"
        else -> null
    },
    /** Records whether potentially sensitive text properties were requested for this snapshot. */
    val textCapture: NodeTextCapture = NodeTextCapture.NONE,
    val timeBudgetExceeded: Boolean = false,
    val requestedMaxDurationMs: Long = 750L
)

enum class NodeTextCapture {
    /** Collect class, view-id, bounds and interaction flags only. */
    NONE,
    /** Text is transient in-memory evidence; callers must not persist or log it by default. */
    EPHEMERAL
}

/** A field that the accessibility snapshot can expose to inspection and locator analysis. */
enum class AccessibilityNodeField(val requiresTextCapture: Boolean = false) {
    CLASS_NAME,
    VIEW_ID,
    PACKAGE_NAME,
    WINDOW_ID,
    BOUNDS,
    TEXT(true),
    CONTENT_DESCRIPTION(true),
    HINT_TEXT(true),
    STATE_DESCRIPTION(true),
    TOOLTIP_TEXT(true),
    ACTION_LABELS(true),
    VISIBLE,
    ENABLED,
    CHECKABLE,
    CHECKED,
    CLICKABLE,
    LONG_CLICKABLE,
    SCROLLABLE,
    EDITABLE,
    SELECTED,
    FOCUSED,
    FOCUSABLE,
    CONTEXT_CLICKABLE,
    DISMISSABLE,
    ACCESSIBILITY_FOCUSED,
    MULTI_LINE,
    CONTENT_INVALID,
    IMPORTANT_FOR_ACCESSIBILITY,
    SCREEN_READER_FOCUSABLE,
    HEADING,
    SHOWING_HINT_TEXT,
    CHILD_COUNT,
    CHILD_NODES,
    INDEX_IN_PARENT,
    DRAWING_ORDER,
    PASSWORD,
    COLLECTION,
    COLLECTION_ITEM,
    ACTIONS
}

/** Describes why an attribute has (or does not have) a value in a captured node. */
enum class AccessibilityFieldCaptureState {
    VALUE_PRESENT,
    EMPTY,
    NOT_REQUESTED,
    UNSUPPORTED,
    READ_FAILED
}

/**
 * Read failures and platform-unsupported fields are kept separately from their fallback values.
 * This prevents an exception-safe default such as `clickable = false` from being presented as a
 * successfully observed false value.
 */
data class AccessibilityNodeCaptureEvidence(
    val failedFields: Set<AccessibilityNodeField> = emptySet(),
    val unsupportedFields: Set<AccessibilityNodeField> = emptySet(),
    val notCapturedFields: Set<AccessibilityNodeField> = emptySet()
)

data class TreeInspectionRequest(
    val maxNodes: Int,
    val textCapture: NodeTextCapture = NodeTextCapture.NONE,
    /** Reproduces the monitor's legacy scan behavior: search any visible service window if needed. */
    val allowVisibleWindowFallback: Boolean = false,
    /** Maximum node depth from the root. The default bounds pathological/deep accessibility trees. */
    val maxDepth: Int = 48,
    /** One monotonic time budget for root acquisition's follow-up traversal, including all windows. */
    val maxDurationMs: Long = 750L
) {
    init {
        require(maxNodes > 0) { "maxNodes must be positive" }
        require(maxDepth in 0..128) { "maxDepth must be in 0..128" }
        require(maxDurationMs in 1L..5_000L) { "maxDurationMs must be in 1..5000" }
    }
}

/**
 * Non-touch semantic node actions only. Screen taps, long presses and scrolling are deliberately
 * excluded and must be represented as GestureIntentRequest values submitted through GestureApi.
 */
enum class NodeAction {
    FOCUS,
    SET_TEXT,
    DISMISS
}

data class NodeActionRequest(
    val target: NodeTarget,
    val action: NodeAction,
    val textArgument: String? = null
)

data class ActionResult(
    val accepted: Boolean,
    val performed: Boolean,
    val reason: String = ""
)

enum class GlobalAccessibilityAction {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    /** Turns the display off and locks the device on Android API 28+. */
    LOCK_SCREEN
}

data class GesturePoint(val x: Float, val y: Float)

/** Commands retain the original path shape without exposing android.graphics.Path. */
sealed interface GesturePathCommand {
    data class MoveTo(val point: GesturePoint) : GesturePathCommand
    data class LineTo(val point: GesturePoint) : GesturePathCommand
    data class CubicTo(
        val control1: GesturePoint,
        val control2: GesturePoint,
        val end: GesturePoint
    ) : GesturePathCommand
}

data class GestureStrokeSpec(
    val commands: List<GesturePathCommand>,
    val durationMs: Long,
    /** Absolute start time from the overall gesture start. */
    val startDelayMs: Long = 0L,
    /** Continue the immediately preceding stroke without lifting the same pointer. */
    val continuationOfPrevious: Boolean = false,
    /** Keep this pointer down for one more continuation segment. */
    val willContinue: Boolean = false
) {
    init {
        require(commands.isNotEmpty()) { "Gesture path cannot be empty" }
        require(durationMs > 0L) { "Gesture duration must be positive" }
        require(startDelayMs >= 0L) { "Gesture start delay cannot be negative" }
    }
}

/** Narrows which asynchronous Accessibility feedback may be causally linked to a dispatch. */
enum class GestureFeedbackIntent {
    /** Default: feedback must match the gesture's own geometry and shape. */
    STANDARD,
    /** A bottom-navigation tap; page changes are verified by snapshots, never scroll ownership. */
    NAVIGATION_TAB,
    /** A tap explicitly intended to open a different Activity/window. */
    NAVIGATION_ENTRY
}

enum class GestureDispatchPurpose {
    /** Autonomous interaction; requires a fresh READY target-application check. */
    AUTONOMOUS,
    /** Explicit user-requested Freedom-internal calibration, not a target-app action. */
    INTERNAL_CALIBRATION
}

data class GestureSpec(
    val commands: List<GesturePathCommand>,
    val durationMs: Long,
    val startDelayMs: Long = 0L,
    /** The primary stroke remains down and must be continued by its next segment. */
    val willContinue: Boolean = false,
    /** Additional independent or continued strokes, used for compound/timed gestures. */
    val additionalStrokes: List<GestureStrokeSpec> = emptyList(),
    /** Explicitly opts into narrowly bounded page-refresh or navigation feedback correlation. */
    val feedbackIntent: GestureFeedbackIntent = GestureFeedbackIntent.STANDARD,
    /** Marks the only supported non-autonomous dispatch path: internal user-requested calibration. */
    val dispatchPurpose: GestureDispatchPurpose = GestureDispatchPurpose.AUTONOMOUS
) {
    init {
        require(commands.isNotEmpty()) { "Gesture path cannot be empty" }
        require(durationMs > 0L) { "Gesture duration must be positive" }
        require(startDelayMs >= 0L) { "Gesture start delay cannot be negative" }
    }
}

enum class GestureCompletion {
    COMPLETED,
    CANCELLED
}

fun interface GestureResultListener {
    fun onResult(result: GestureCompletion)
}

enum class GestureSubmission {
    /** Accepted by the Runtime; a single active command or a bounded FIFO waiting slot is reserved. */
    ACCEPTED,
    /**
     * Autonomous action was not sent because the target page/window is temporarily not ready;
     * the caller must reacquire fresh evidence and retry without consuming an attempt.
     */
    DEFERRED_NOT_READY,
    REJECTED
}

/**
 * The only feature-facing boundary to Android accessibility operations.
 * Implementations reacquire the current root for every operation and validate NodeTarget.window
 * and its path before performing an action. No AccessibilityNodeInfo escapes this boundary.
 * Touch-like actions are not part of this port; submit GestureIntentRequest through GestureApi.
 */
interface ActiveWindowInspectionPort {
    fun displayMetrics(): DisplayMetricsSnapshot
    fun inspectActiveWindow(request: TreeInspectionRequest): AccessibilityTreeSnapshot?

    /**
     * Reacquires the active window and captures only the subtree addressed by a previously
     * confirmed node target. Implementations must validate the target window, path and expected
     * node identity before traversing descendants. A null result means the target could not be
     * safely reacquired; callers must obtain a fresh full-window snapshot before retrying.
     */
    fun inspectNodeSubtree(
        target: NodeTarget,
        request: TreeInspectionRequest
    ): AccessibilityTreeSnapshot? = null

    /**
     * Captures every currently exposed window belonging to the selected target package.
     * Implementations must exclude other applications and system-owned windows. The default
     * preserves compatibility for test doubles and adapters that only expose an active root.
     */
    fun inspectTargetWindows(request: TreeInspectionRequest): List<AccessibilityTreeSnapshot> =
        inspectActiveWindow(request)?.let(::listOf).orEmpty()

    fun isWindowCurrent(window: WindowRef): Boolean
}

interface AccessibilityActionPort {
    fun performNodeAction(request: NodeActionRequest): ActionResult
    fun performGlobalAction(action: GlobalAccessibilityAction): Boolean
}

/** Combined platform gateway for the Service composition root. Features may depend on narrower ports. */
interface AccessibilityGateway :
    ActiveWindowInspectionPort,
    AccessibilityActionPort {

    /**
     * Captures the active window identity for a gesture coordinate snapshot. Unlike
     * isWindowCurrent(), this intentionally supports Freedom's own calibration window too.
     */
    fun currentGestureWindow(): WindowRef? =
        inspectActiveWindow(TreeInspectionRequest(maxNodes = 1, maxDepth = 0))?.window

    /** Validates a gesture window against the currently active platform window. */
    fun isGestureWindowCurrent(window: WindowRef): Boolean {
        val current = currentGestureWindow() ?: return false
        return current.packageName == window.packageName &&
            current.generation == window.generation &&
            (current.windowId < 0 || window.windowId < 0 || current.windowId == window.windowId) &&
            (current.activityClass.isBlank() || window.activityClass.isBlank() ||
                current.activityClass == window.activityClass)
    }

    /**
     * Performs one BACK navigation only for a caller-validated, exact target window. Unlike the
     * normal global-action path, this does not require the page to be a chat page; the adapter must
     * first complete its dedicated overlay-safety inspection and pass the fresh WindowRef plus
     * the expected page Activity. Only a caller-verified identity page in that exact window may
     * supply sourceIdentityVerified=true, based on fresh unique platform identity evidence,
     * not an Activity guess or historical ID. Implementations always revalidate package,
     * window ID, generation, run mode and authorization; otherwise Activity is also required.
     * The expected destination Activity is provenance only; null claims no page transition.
     */
    fun performVerifiedNavigationBack(
        expectedWindow: WindowRef,
        expectedActivityClass: String,
        targetActivityClass: String?,
        sourceIdentityVerified: Boolean = false
    ): Boolean = false
}
