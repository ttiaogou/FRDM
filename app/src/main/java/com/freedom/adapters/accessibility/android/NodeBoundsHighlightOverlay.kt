package com.freedom.adapters.accessibility.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.freedom.adapters.android.MonitorActions
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.overlay.NodeBoundsHitCandidate
import com.freedom.core.accessibility.overlay.NodeBoundsHitTester
import com.freedom.core.accessibility.inspector.NodeCodeSnippetGenerator
import com.freedom.core.accessibility.inspector.NodeInspectorEntry
import com.freedom.core.accessibility.inspector.NodeInspectorIdentity
import com.freedom.core.accessibility.inspector.NodeInspectorOperations
import com.freedom.core.accessibility.inspector.NodeInspectorVisibilitySession
import com.freedom.core.accessibility.inspector.InspectorSearchCriteria
import com.freedom.core.accessibility.inspector.InspectorSearchEngine
import kotlin.math.abs

/**
 * Owns screen-space node overlays. The normal highlight mode is pass-through; interactive layout
 * inspection deliberately receives touches so a tap selects a node instead of activating the app.
 * Only detached snapshots cross this boundary; live AccessibilityNodeInfo is never retained here.
 */
internal object NodeBoundsHighlightOverlay {
    private const val DEFAULT_DURATION_MS = 5_000L
    private const val MIN_DURATION_MS = 500L
    private const val MAX_DURATION_MS = 15_000L
    private const val MAX_LAYOUT_BOUNDS = 600

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var highlightView: HighlightView? = null
    private var attached = false
    private var attachedInteractive = false
    private val removeRunnable = Runnable { removeOnMainThread() }

    fun show(
        context: Context,
        selectedBounds: ScreenRect,
        ancestorBounds: List<ScreenRect>,
        label: String,
        durationMs: Long = DEFAULT_DURATION_MS
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (selectedBounds.isEmpty) return false
        val view = ensureAttached(context, interactive = false) ?: return false
        mainHandler.removeCallbacks(removeRunnable)
        view.update(selectedBounds, ancestorBounds, label)
        mainHandler.postDelayed(removeRunnable, durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS))
        return true
    }

    /** Displays a temporary, pass-through set of bounds. */
    fun showAll(
        context: Context,
        bounds: List<ScreenRect>,
        label: String,
        durationMs: Long = 12_000L
    ): Boolean = showAllInternal(context, bounds, label, durationMs)

    /** Displays persistent, pass-through bounds for legacy callers. */
    fun showAllPersistent(context: Context, bounds: List<ScreenRect>, label: String): Boolean =
        showAllInternal(context, bounds, label, durationMs = null)

    /**
     * Displays the complete captured node geometry as an interactive inspector. Taps select the
     * deepest/smallest matching node; long presses open its contextual action menu.
     */
    fun showInteractiveAll(
        context: Context,
        snapshots: List<AccessibilityTreeSnapshot>,
        label: String
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val capturedNodes = snapshots.flatMapIndexed { windowOrder, snapshot ->
            snapshot.nodes.asSequence()
                .filter { node -> node.visible && !node.bounds.isEmpty }
                .map { node -> CapturedNode(snapshot, node, windowOrder) }
                .toList()
        }.take(MAX_LAYOUT_BOUNDS)
        if (capturedNodes.isEmpty()) return false

        NodeInspectorVisibilitySession.beginSession()
        val view = ensureAttached(context, interactive = true) ?: return false
        mainHandler.removeCallbacks(removeRunnable)
        view.updateInteractive(capturedNodes, label)
        return true
    }

    private fun showAllInternal(
        context: Context,
        bounds: List<ScreenRect>,
        label: String,
        durationMs: Long?
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val visibleBounds = bounds.asSequence()
            .filterNot(ScreenRect::isEmpty)
            .distinct()
            .take(MAX_LAYOUT_BOUNDS)
            .toList()
        if (visibleBounds.isEmpty()) return false
        val view = ensureAttached(context, interactive = false) ?: return false
        mainHandler.removeCallbacks(removeRunnable)
        view.updateAll(visibleBounds, label)
        durationMs?.let { duration ->
            mainHandler.postDelayed(removeRunnable, duration.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS))
        }
        return true
    }

    fun clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainHandler.removeCallbacks(removeRunnable)
            removeOnMainThread()
            NodeInspectorVisibilitySession.endSession()
        } else {
            mainHandler.post {
                mainHandler.removeCallbacks(removeRunnable)
                removeOnMainThread()
                NodeInspectorVisibilitySession.endSession()
            }
        }
    }

    private fun ensureAttached(context: Context, interactive: Boolean): HighlightView? {
        val current = highlightView
        if (attached && current?.isAttachedToWindow == true && attachedInteractive == interactive) {
            return current
        }
        if (attached || current != null) removeOnMainThread()

        val manager = runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull() ?: return null
        val view = HighlightView(context, interactive)
        val touchModeFlags = if (interactive) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or touchModeFlags
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (interactive) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        return runCatching {
            manager.addView(view, params)
            windowManager = manager
            highlightView = view
            attached = true
            attachedInteractive = interactive
            view
        }.getOrElse {
            windowManager = null
            highlightView = null
            attached = false
            attachedInteractive = false
            null
        }
    }

    private fun removeOnMainThread() {
        val view = highlightView
        val manager = windowManager
        val wasAttached = attached
        attached = false
        attachedInteractive = false
        highlightView = null
        windowManager = null
        view?.dispose()
        if (wasAttached && view != null && manager != null) {
            runCatching { manager.removeViewImmediate(view) }
        }
    }

    private data class CapturedNode(
        val snapshot: AccessibilityTreeSnapshot,
        val node: AccessibilityNodeSnapshot,
        val windowOrder: Int
    ) {
        val identity = NodeInspectorIdentity(snapshot.window, node.path)
        val hitCandidate = NodeBoundsHitCandidate(
            windowId = snapshot.windowId,
            path = node.path,
            bounds = node.bounds,
            windowOrder = windowOrder
        )
    }

    private class HighlightView(
        context: Context,
        private val interactive: Boolean
    ) : FrameLayout(context) {
        private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        private var selectedBounds: ScreenRect? = null
        private var ancestors: List<ScreenRect> = emptyList()
        private var layoutBounds: List<ScreenRect> = emptyList()
        private var capturedNodes: List<CapturedNode> = emptyList()
        private var capturedSnapshots: List<AccessibilityTreeSnapshot> = emptyList()
        private var activeWindow: com.freedom.core.accessibility.api.WindowRef? = null
        private var selectedNode: CapturedNode? = null
        private var label: String = ""
        private var pressedNode: CapturedNode? = null
        private var pressedScreenX = 0f
        private var pressedScreenY = 0f
        private var movedBeyondSlop = false
        private var longPressTriggered = false
        private var contextMenu: LinearLayout? = null
        private var searchBar: InspectorSearchBarView? = null
        private var searchCriteria = InspectorSearchCriteria()
        private var searchResults: Map<com.freedom.core.accessibility.api.WindowRef, com.freedom.core.accessibility.inspector.InspectorSearchResult> = emptyMap()
        private var searchCount = 0
        private var searchError: String? = null
        private var regionStart: Pair<Int, Int>? = null
        private var regionPreview: ScreenRect? = null
        private var regionSelectionMode = false

        private val layoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCA5C331.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.15f * density
        }
        private val ancestorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC314D3A.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
        }
        private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33F04438
            style = Paint.Style.FILL
        }
        private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF04438.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3.2f * density
        }
        private val matchedBoundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF93652E.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.4f * density
        }
        private val regionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33D8EF68
            style = Paint.Style.FILL
        }
        private val regionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA5C331.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEE17281F.toInt()
            style = Paint.Style.FILL
        }
        private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val infoPanel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 8
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xF217281F.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xAAB9DF3B.toInt())
            }
            visibility = View.GONE
            isClickable = true
            setOnClickListener { visibility = View.GONE }
            contentDescription = "当前选中节点信息；点击收起"
        }
        private val exitButton = TextView(context).apply {
            text = "× 退出检查"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE617281F.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xAAB9DF3B.toInt())
            }
            contentDescription = "退出节点检查器，恢复原页面"
            isClickable = true
            setOnClickListener {
                this.context.sendBroadcast(
                    Intent(MonitorActions.ACTION_CLEAR_GLOBAL_NODE_LAYOUT_BOUNDS)
                        .setPackage(this.context.packageName)
                )
                NodeBoundsHighlightOverlay.clear()
            }
        }
        private val longPressRunnable = Runnable {
            val candidate = pressedNode
            if (interactive && candidate != null && !movedBeyondSlop) {
                longPressTriggered = true
                selectNode(candidate)
                showNodeContextMenu(candidate, pressedScreenX, pressedScreenY)
            }
        }

        init {
            setWillNotDraw(false)
            isClickable = interactive
            isFocusable = interactive
            isFocusableInTouchMode = interactive
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            clipChildren = false
            if (interactive) {
                val search = InspectorSearchBarView(context, { query, regex, attributes ->
                    searchCriteria = searchCriteria.copy(query = query, regularExpression = regex, attributes = attributes)
                    applySearch()
                }, {
                    searchCriteria = searchCriteria.copy(region = null)
                    regionPreview = null
                    searchBar?.setRegionActive(false)
                    applySearch()
                }, { enabled ->
                    regionSelectionMode = enabled
                    Toast.makeText(this.context, if (enabled) "请在屏幕任意位置拖动框选区域" else "已退出区域框选模式", Toast.LENGTH_SHORT).show()
                })
                searchBar = search
                addView(search, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply {
                    leftMargin = dp(10)
                    rightMargin = dp(10)
                    topMargin = dp(18)
                })
                addView(
                    exitButton,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    ).apply {
                        topMargin = dp(72)
                        rightMargin = dp(12)
                    }
                )
                addView(
                    infoPanel,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    ).apply {
                        leftMargin = dp(12)
                        rightMargin = dp(12)
                        bottomMargin = dp(18)
                    }
                )
            }
        }

        fun update(bounds: ScreenRect, parents: List<ScreenRect>, description: String) {
            capturedNodes = emptyList()
            capturedSnapshots = emptyList()
            activeWindow = null
            layoutBounds = emptyList()
            selectedNode = null
            selectedBounds = bounds
            ancestors = parents.filterNot(ScreenRect::isEmpty).take(8)
            label = description.take(96)
            infoPanel.visibility = View.GONE
            invalidate()
        }

        fun updateAll(bounds: List<ScreenRect>, description: String) {
            capturedNodes = emptyList()
            capturedSnapshots = emptyList()
            activeWindow = null
            selectedNode = null
            selectedBounds = null
            ancestors = emptyList()
            layoutBounds = bounds
            label = description.take(96)
            infoPanel.visibility = View.GONE
            invalidate()
        }

        fun updateInteractive(nodes: List<CapturedNode>, description: String) {
            capturedNodes = nodes
            capturedSnapshots = nodes.map { it.snapshot }.distinctBy { it.window }
            activeWindow = null
            selectedNode = null
            selectedBounds = null
            ancestors = emptyList()
            label = description.take(96)
            searchCriteria = InspectorSearchCriteria()
            regionStart = null
            regionPreview = null
            regionSelectionMode = false
            searchBar?.setRegionSelectionMode(false)
            searchBar?.setRegionActive(false)
            infoPanel.visibility = View.GONE
            applySearch()
        }

        private fun visibleCapturedNodes(): List<CapturedNode> {
            val byIdentity = capturedNodes.associateBy { it.identity }
            val visible = NodeInspectorOperations.visibleEntries(
                entries = capturedNodes.map { NodeInspectorEntry(it.identity, it.node.bounds) },
                hidden = NodeInspectorVisibilitySession.hiddenIdentities(),
                window = activeWindow
            ).mapNotNull { entry -> byIdentity[entry.identity] }
            return if (searchError != null || !hasActiveSearch()) visible else visible.filter { captured ->
                searchResults[captured.snapshot.window]?.matchingPaths?.contains(captured.node.path.childIndices) == true
            }
        }

        private fun hasActiveSearch(): Boolean = searchCriteria.query.isNotBlank() ||
            searchCriteria.attributes.isNotEmpty() || searchCriteria.region != null

        private fun applySearch() {
            searchResults = capturedSnapshots.associate { snapshot ->
                snapshot.window to InspectorSearchEngine.evaluate(
                    snapshot,
                    searchCriteria,
                    NodeInspectorVisibilitySession.hiddenPaths(snapshot.window)
                )
            }
            searchError = searchResults.values.firstNotNullOfOrNull { it.error }
            searchCount = if (searchError != null) {
                capturedNodes.count { !NodeInspectorVisibilitySession.isHidden(it.identity) }
            } else capturedNodes.count { captured ->
                !NodeInspectorVisibilitySession.isHidden(captured.identity) &&
                    searchResults[captured.snapshot.window]?.matchingPaths?.contains(captured.node.path.childIndices) == true
            }
            val availableCount = capturedNodes.count { !NodeInspectorVisibilitySession.isHidden(it.identity) }
            searchBar?.updateCount(searchCount, availableCount, searchError)
            searchBar?.setRegionActive(searchCriteria.region != null)
            val selected = selectedNode
            if (selected != null && (
                    NodeInspectorVisibilitySession.isHidden(selected.identity) ||
                        (hasActiveSearch() && searchError == null &&
                            searchResults[selected.snapshot.window]?.matchingPaths?.contains(selected.node.path.childIndices) != true)
                    )
            ) {
                selectedNode = null
                selectedBounds = null
                ancestors = emptyList()
                infoPanel.visibility = View.GONE
            }
            refreshVisibleBounds()
        }

        private fun refreshVisibleBounds() {
            layoutBounds = visibleCapturedNodes().map { it.node.bounds }
            invalidate()
        }

        fun dispose() {
            removeCallbacks(longPressRunnable)
            dismissContextMenu()
            searchBar?.hideKeyboard()
            searchBar = null
            regionStart = null
            regionPreview = null
            regionSelectionMode = false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!interactive) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeCallbacks(longPressRunnable)
                    pressedScreenX = event.rawX
                    pressedScreenY = event.rawY
                    pressedNode = findNodeAt(event.rawX.toInt(), event.rawY.toInt())
                    regionStart = if (regionSelectionMode || pressedNode == null) event.rawX.toInt() to event.rawY.toInt() else null
                    if (regionSelectionMode) pressedNode = null
                    regionPreview = null
                    movedBeyondSlop = false
                    longPressTriggered = false
                    if (pressedNode != null) {
                        postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = ViewConfiguration.get(this.context).scaledTouchSlop
                    if (abs(event.rawX - pressedScreenX) > slop || abs(event.rawY - pressedScreenY) > slop) {
                        movedBeyondSlop = true
                        removeCallbacks(longPressRunnable)
                        regionStart?.let { (startX, startY) ->
                            regionPreview = ScreenRect(
                                minOf(startX, event.rawX.toInt()), minOf(startY, event.rawY.toInt()),
                                maxOf(startX, event.rawX.toInt()), maxOf(startY, event.rawY.toInt())
                            )
                            invalidate()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    if (regionStart != null && movedBeyondSlop) {
                        val selectedRegion = regionPreview
                        if (selectedRegion != null && selectedRegion.width >= dp(12) && selectedRegion.height >= dp(12)) {
                            searchCriteria = searchCriteria.copy(region = selectedRegion)
                            regionSelectionMode = false
                            searchBar?.setRegionSelectionMode(false)
                            searchBar?.setRegionActive(true)
                            applySearch()
                            Toast.makeText(this.context, "区域筛选已应用：${searchCount} 个节点", Toast.LENGTH_SHORT).show()
                        }
                        regionPreview = null
                        regionStart = null
                        invalidate()
                    } else if (!movedBeyondSlop && !longPressTriggered) {
                        val candidate = pressedNode
                        if (candidate != null) selectNode(candidate) else clearSelection()
                        performClick()
                    }
                    pressedNode = null
                    regionStart = null
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPressRunnable)
                    pressedNode = null
                    regionStart = null
                    regionPreview = null
                    movedBeyondSlop = false
                    invalidate()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun findNodeAt(screenX: Int, screenY: Int): CapturedNode? {
            val visible = visibleCapturedNodes()
            val hit = NodeBoundsHitTester.find(visible.map { it.hitCandidate }, screenX, screenY) ?: return null
            return visible.firstOrNull { it.hitCandidate == hit }
        }

        private fun selectNode(captured: CapturedNode) {
            if (NodeInspectorVisibilitySession.isHidden(captured.identity)) return
            selectedNode = captured
            selectedBounds = captured.node.bounds
            ancestors = visibleCapturedNodes().asSequence()
                .filter { candidate ->
                    candidate.snapshot.windowId == captured.snapshot.windowId &&
                        candidate.node.path.childIndices.size < captured.node.path.childIndices.size &&
                        captured.node.path.childIndices.take(candidate.node.path.childIndices.size) ==
                        candidate.node.path.childIndices
                }
                .sortedBy { it.node.path.childIndices.size }
                .map { it.node.bounds }
                .filterNot(ScreenRect::isEmpty)
                .toList()
            infoPanel.typeface = Typeface.DEFAULT
            infoPanel.maxLines = 12
            infoPanel.setTextIsSelectable(true)
            infoPanel.contentDescription = "当前选中节点属性"
            infoPanel.text = formatNodeInfo(captured)
            infoPanel.visibility = View.VISIBLE
            invalidate()
        }

        private fun clearSelection() {
            selectedNode = null
            selectedBounds = null
            ancestors = emptyList()
            infoPanel.visibility = View.GONE
            invalidate()
        }

        private fun showNodeContextMenu(captured: CapturedNode, screenX: Float, screenY: Float) {
            val parent = NodeInspectorOperations.parentOf(
                captured.identity,
                capturedNodes.map { NodeInspectorEntry(it.identity, it.node.bounds) }
            )
            val windowSwitchAvailable = capturedSnapshots.size > 1
            fun action(title: String, enabled: Boolean = true, callback: () -> Unit) =
                Triple(title, enabled, callback)
            showActionPanel(
                screenX,
                screenY,
                listOf(
                    action("显示节点信息") { selectNode(captured) },
                    action("复制节点路径") {
                        copy("节点路径", captured.node.path.childIndices.joinToString("/").ifBlank { "根节点" })
                    },
                    action("复制资源 ID") { copy("节点资源 ID", captured.node.viewId) },
                    action("复制边界坐标") {
                        val bounds = captured.node.bounds
                        copy("节点边界", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                    },
                    action("查看布局层级") {
                        if (!NodeHierarchyOverlay.show(this.context, capturedSnapshots)) {
                            Toast.makeText(this.context, "节点层级视图无法打开", Toast.LENGTH_SHORT).show()
                        }
                    },
                    action("生成代码") { generateCode(captured) },
                    action("选择父节点", parent != null) {
                        val parentNode = parent?.let { identity -> capturedNodes.firstOrNull { it.identity == identity } }
                        if (parentNode != null) selectNode(parentNode)
                        else Toast.makeText(this.context, "当前快照中没有父节点", Toast.LENGTH_SHORT).show()
                    },
                    action("隐藏节点") { hideNode(captured) },
                    action("隐藏相同边界节点") { hideSameBounds(captured) },
                    action("切换窗口", windowSwitchAvailable) { showWindowPicker(screenX, screenY) },
                    action("保存布局到文件") { saveLayoutSnapshot() },
                    action("退出") { exitInspector() }
                )
            )
        }

        private fun showActionPanel(
            screenX: Float,
            screenY: Float,
            actions: List<Triple<String, Boolean, () -> Unit>>
        ) {
            dismissContextMenu()
            val menu = LinearLayout(this.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply {
                    setColor(0xF8F8F7F0.toInt())
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), 0xFFA3ACA1.toInt())
                }
                elevation = dp(14).toFloat()
                isClickable = true
            }
            actions.forEach { (title, enabled, action) ->
                val item = TextView(this.context).apply {
                    text = title
                    textSize = 13f
                    setTextColor(if (enabled) 0xFF23372B.toInt() else 0xFFA3ACA1.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    minHeight = dp(40)
                    isEnabled = enabled
                    isClickable = enabled
                    setOnClickListener {
                        dismissContextMenu()
                        action()
                    }
                }
                menu.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val location = IntArray(2)
            getLocationOnScreen(location)
            val menuWidth = dp(216)
            val estimatedHeight = dp((actions.size * 42 + 16).coerceAtMost(500))
            val localX = (screenX.toInt() - location[0]).coerceIn(
                dp(8), (width - menuWidth - dp(8)).coerceAtLeast(dp(8))
            )
            val localY = (screenY.toInt() - location[1]).coerceIn(
                dp(48), (height - estimatedHeight - dp(8)).coerceAtLeast(dp(48))
            )
            val params = FrameLayout.LayoutParams(
                menuWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = localX
                topMargin = localY
            }
            contextMenu = menu
            addView(menu, params)
            menu.bringToFront()
            exitButton.bringToFront()
            infoPanel.bringToFront()
        }

        private fun showWindowPicker(screenX: Float, screenY: Float) {
            val actions = mutableListOf<Triple<String, Boolean, () -> Unit>>()
            actions += Triple("显示全部窗口", true, {
                activeWindow = null
                clearSelection()
                refreshVisibleBounds()
                Toast.makeText(this.context, "已显示全部目标 App 窗口", Toast.LENGTH_SHORT).show()
            })
            capturedSnapshots.forEach { snapshot ->
                val windowLabel = buildString {
                    append("窗口 ").append(snapshot.windowId)
                    val activity = snapshot.window.activityClass.substringAfterLast('.')
                    if (activity.isNotBlank()) append(" · ").append(activity)
                    if (snapshot.truncated) append(" · 不完整")
                }
                actions += Triple(windowLabel, true, {
                    activeWindow = snapshot.window
                    clearSelection()
                    refreshVisibleBounds()
                    Toast.makeText(this.context, "已切换到窗口 ${snapshot.windowId}", Toast.LENGTH_SHORT).show()
                })
            }
            showActionPanel(screenX, screenY, actions)
        }

        private fun hideNode(captured: CapturedNode) {
            NodeInspectorVisibilitySession.hide(captured.identity)
            if (selectedNode?.identity == captured.identity) clearSelection()
            applySearch()
            Toast.makeText(this.context, "已隐藏当前节点；本次检查期间不可再次选中", Toast.LENGTH_SHORT).show()
        }

        private fun hideSameBounds(captured: CapturedNode) {
            val same = NodeInspectorOperations.sameBounds(
                captured.identity,
                captured.node.bounds,
                capturedNodes.map { NodeInspectorEntry(it.identity, it.node.bounds) }
            )
            NodeInspectorVisibilitySession.hideAll(same)
            if (selectedNode?.identity?.let { it in same } == true) clearSelection()
            applySearch()
            Toast.makeText(this.context, "已隐藏 ${same.size} 个相同边界节点；本次检查期间不可再次选中", Toast.LENGTH_SHORT).show()
        }

        private fun generateCode(captured: CapturedNode) {
            val code = NodeCodeSnippetGenerator.generate(captured.node, captured.snapshot)
            infoPanel.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            infoPanel.maxLines = 16
            infoPanel.setTextIsSelectable(true)
            infoPanel.contentDescription = "生成的 AutoJs6 控件定位代码"
            infoPanel.text = code
            infoPanel.visibility = View.VISIBLE
            invalidate()
            copy("节点定位代码", code)
        }

        private fun saveLayoutSnapshot() {
            val selectedSnapshot = selectedNode?.snapshot
                ?: capturedSnapshots.firstOrNull { it.window == activeWindow }
                ?: capturedSnapshots.firstOrNull()
            if (selectedSnapshot == null) {
                Toast.makeText(this.context, "没有可保存的布局快照", Toast.LENGTH_SHORT).show()
                return
            }
            NodeLayoutSnapshotExporter.save(this.context, selectedSnapshot) { result ->
                result.onSuccess { file ->
                    copy("布局文件路径", file.absolutePath)
                    Toast.makeText(this.context, "布局已保存：${file.name}；路径已复制", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(this.context, "布局保存失败：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }

        private fun exitInspector() {
            this.context.sendBroadcast(
                Intent(MonitorActions.ACTION_CLEAR_GLOBAL_NODE_LAYOUT_BOUNDS)
                    .setPackage(this.context.packageName)
            )
            NodeBoundsHighlightOverlay.clear()
        }

        private fun dismissContextMenu() {
            contextMenu?.let { menu ->
                removeView(menu)
                contextMenu = null
            }
        }

        private fun copy(label: String, value: String) {
            val clipboard = this.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                Toast.makeText(this.context, "无法访问剪贴板", Toast.LENGTH_SHORT).show()
                return
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(this.context, "已复制$label", Toast.LENGTH_SHORT).show()
        }

        private fun formatNodeInfo(captured: CapturedNode): String {
            val node = captured.node
            val bounds = node.bounds
            return buildString {
                append("已选中节点 · ").append(node.className.substringAfterLast('.')).append('\n')
                append("窗口：").append(captured.snapshot.window.activityClass.ifBlank { "未知" })
                    .append("（ID ").append(captured.snapshot.windowId).append("）\n")
                append("资源 ID：").append(node.viewId.ifBlank { "无" }).append('\n')
                append("路径：").append(node.path.childIndices.joinToString("/").ifBlank { "根节点" }).append('\n')
                append("边界：").append(bounds.left).append(',').append(bounds.top).append(" – ")
                    .append(bounds.right).append(',').append(bounds.bottom).append('\n')
                append("可见=").append(node.visible).append("  启用=").append(node.enabled)
                    .append("  可点击=").append(node.clickable).append("  长按=").append(node.longClickable).append('\n')
                append("可聚焦=").append(node.focusable).append("  已选中=").append(node.selected)
                    .append("  可滚动=").append(node.scrollable).append("  可编辑=").append(node.editable)
                    .append("  子节点=").append(node.childCount).append('\n')
                append("可检查=").append(node.checkable).append("  已检查=").append(node.checked)
                    .append("  可上下文点击=").append(node.contextClickable).append('\n')
                append("绘制顺序=").append(node.drawingOrder).append("  父节点索引=").append(node.indexInParent).append('\n')
                append("窗口包名：").append(captured.snapshot.window.packageName).append('\n')
                if (captured.snapshot.textCapture == NodeTextCapture.EPHEMERAL) {
                    append("文本：").append(node.text.orEmpty().ifBlank { "（空）" }).append('\n')
                    append("内容描述：").append(node.contentDescription.orEmpty().ifBlank { "（空）" })
                } else {
                    append("文本/描述：本次未请求采集")
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val location = IntArray(2)
            getLocationOnScreen(location)
            canvas.save()
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())

            if (layoutBounds.isNotEmpty()) {
                layoutBounds.forEach { drawBounds(canvas, it, layoutPaint) }
                if (hasActiveSearch() && searchError == null) {
                    visibleCapturedNodes().forEach { drawBounds(canvas, it.node.bounds, matchedBoundsPaint) }
                }
                drawLayoutLabel(canvas)
            }
            (regionPreview ?: searchCriteria.region)?.let { region ->
                val rect = region.toRectF()
                canvas.drawRect(rect, regionFillPaint)
                canvas.drawRect(rect, regionStrokePaint)
            }
            ancestors.forEach { drawBounds(canvas, it, ancestorPaint) }
            val bounds = selectedBounds
            if (bounds != null && !bounds.isEmpty) {
                val rect = bounds.toRectF()
                canvas.drawRoundRect(rect, 4f * density, 4f * density, selectedFillPaint)
                canvas.drawRoundRect(rect, 4f * density, 4f * density, selectedStrokePaint)
                drawLabel(canvas, rect, selectedNode?.node?.className?.substringAfterLast('.') ?: label)
            }
            canvas.restore()
        }

        private fun drawLayoutLabel(canvas: Canvas) {
            if (label.isBlank() || width <= 0 || height <= 0) return
            val paddingX = 10f * density
            val paddingY = 7f * density
            val displayLabel = if (interactive) {
                val count = if (hasActiveSearch() && searchError == null) " · 匹配 $searchCount" else ""
                val regionHint = if (regionSelectionMode) " · 正在框选区域" else " · 点击选择 / 长按菜单 / 框选区域"
                "$label$count$regionHint"
            } else label
            val textWidth = labelTextPaint.measureText(displayLabel).coerceAtMost(width - 2f * paddingX)
            val boxWidth = textWidth + 2f * paddingX
            val boxHeight = labelTextPaint.textSize + 2f * paddingY
            val left = 10f * density
            val location = IntArray(2)
            getLocationOnScreen(location)
            val top = location[1] + 28f * density
            val box = RectF(left + location[0], top, left + location[0] + boxWidth, top + boxHeight)
            canvas.drawRoundRect(box, 5f * density, 5f * density, labelBackgroundPaint)
            canvas.drawText(displayLabel, box.left + paddingX, top + paddingY - labelTextPaint.ascent(), labelTextPaint)
        }

        private fun drawLabel(canvas: Canvas, selectedRect: RectF, text: String) {
            if (text.isBlank() || width <= 0 || height <= 0) return
            val paddingX = 8f * density
            val paddingY = 5f * density
            val textWidth = labelTextPaint.measureText(text).coerceAtMost(width - 2f * paddingX)
            val boxWidth = textWidth + 2f * paddingX
            val boxHeight = labelTextPaint.textSize + 2f * paddingY
            val left = selectedRect.left.coerceIn(0f, (width - boxWidth).coerceAtLeast(0f))
            val preferredTop = selectedRect.top - boxHeight - 4f * density
            val top = if (preferredTop >= 0f) preferredTop
            else (selectedRect.bottom + 4f * density).coerceAtMost((height - boxHeight).coerceAtLeast(0f))
            val box = RectF(left, top, left + boxWidth, top + boxHeight)
            canvas.drawRoundRect(box, 5f * density, 5f * density, labelBackgroundPaint)
            canvas.drawText(text, left + paddingX, top + paddingY - labelTextPaint.ascent(), labelTextPaint)
        }

        private fun drawBounds(canvas: Canvas, bounds: ScreenRect, paint: Paint) {
            if (bounds.isEmpty) return
            canvas.drawRoundRect(bounds.toRectF(), 3f * density, 3f * density, paint)
        }

        private fun ScreenRect.toRectF(): RectF = RectF(
            left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()
        )

        private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }
}
