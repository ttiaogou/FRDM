package com.freedom.adapters.accessibility.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.inspector.AccessibilityHierarchyTree
import com.freedom.core.accessibility.inspector.NodeCodeSnippetGenerator
import com.freedom.core.accessibility.inspector.InspectorNodeAttribute
import com.freedom.core.accessibility.inspector.InspectorSearchCriteria
import com.freedom.core.accessibility.inspector.InspectorSearchEngine
import com.freedom.core.accessibility.inspector.NodeInspectorIdentity
import com.freedom.core.accessibility.inspector.NodeInspectorVisibilitySession

/**
 * A touchable hierarchy browser backed only by the detached target-app snapshots.
 * It is intentionally a bounded panel over the target app, not a Freedom Activity or a new capture
 * entry point. The user can expand/collapse branches, select rows, inspect node attributes and open
 * a row context menu without invoking the target app's controls.
 */
internal object NodeHierarchyOverlay {
    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var capturedSnapshots: List<AccessibilityTreeSnapshot> = emptyList()
    private var activeWindowIndex: Int = 0
    private val selectedPaths = mutableMapOf<Int, List<Int>>()
    private val expandedPaths = mutableMapOf<Int, MutableSet<List<Int>>>()
    private var treeRows: LinearLayout? = null
    private var detailsView: TextView? = null
    private var titleView: TextView? = null
    private var windowButton: TextView? = null
    private var searchBar: InspectorSearchBarView? = null
    private var searchCriteria = InspectorSearchCriteria()
    private var removeVisibilityObserver: (() -> Unit)? = null

    fun show(context: Context, snapshots: List<AccessibilityTreeSnapshot>): Boolean {
        if (snapshots.isEmpty()) {
            Toast.makeText(context, "请先从悬浮按钮运行全局节点可视化", Toast.LENGTH_SHORT).show()
            return false
        }
        clear()
        capturedSnapshots = snapshots.toList()
        activeWindowIndex = 0
        selectedPaths.clear()
        expandedPaths.clear()
        searchCriteria = InspectorSearchCriteria()

        val manager = runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull() ?: return false
        val metrics = context.resources.displayMetrics
        val density = metrics.density.coerceAtLeast(1f)
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(dp(9), dp(7), dp(9), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF8F8F7F0.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFFA5C331.toInt())
            }
            elevation = dp(14).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFF23372B.toInt())
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleView = title
        header.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))
        val window = TextView(context).apply {
            text = "窗口 ▾"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF718E22.toInt())
            setPadding(dp(7), 0, dp(7), 0)
            setOnClickListener { showWindowPicker(context, this) }
        }
        windowButton = window
        header.addView(window, LinearLayout.LayoutParams(dp(82), dp(36)))
        header.addView(TextView(context).apply {
            text = "×"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFF7D887E.toInt())
            setOnClickListener { clear() }
        }, LinearLayout.LayoutParams(dp(32), dp(38)))
        root.addView(header)

        val search = InspectorSearchBarView(context, { query, regex, attributes ->
            searchCriteria = searchCriteria.copy(query = query, regularExpression = regex, attributes = attributes)
            renderTree(context)
        }, {
            // Area selection is created on the bounds surface; the hierarchy surface can clear it
            // after the state hand-off stage connects both presentations.
            searchCriteria = searchCriteria.copy(region = null)
            renderTree(context)
        })
        searchBar = search
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })

        root.addView(TextView(context).apply {
            text = "点击节点选择并查看属性；点击 ▾/▸ 展开或折叠；长按节点打开上下文菜单。"
            textSize = 10.5f
            setTextColor(0xFF7D887E.toInt())
            setPadding(dp(3), dp(2), dp(3), dp(7))
        })

        val treeScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(0xFFF8F7F0.toInt())
        }
        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(treeScroll, FrameLayout.LayoutParams(-1, -1))
        }
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        treeRows = rows
        treeScroll.addView(rows, FrameLayout.LayoutParams(-1, -2))
        root.addView(horizontal, LinearLayout.LayoutParams(-1, 0, 1f))

        val detailsPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(2))
            background = GradientDrawable().apply {
                setColor(0xFFEFEEE7.toInt())
                cornerRadius = dp(8).toFloat()
            }
        }
        detailsPanel.addView(TextView(context).apply {
            text = "节点信息"
            textSize = 11f
            setTextColor(0xFF56665B.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        val details = TextView(context).apply {
            text = "选择一个节点查看完整属性。"
            textSize = 10f
            setTextColor(0xFF56665B.toInt())
            maxLines = 8
            setTextIsSelectable(true)
            setPadding(0, dp(3), 0, dp(3))
        }
        detailsView = details
        detailsPanel.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addView(details, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, dp(112)))
        root.addView(detailsPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })

        val maxHeight = (metrics.heightPixels - dp(112)).coerceAtLeast(dp(300))
        val panelHeight = (metrics.heightPixels * 0.72f).toInt().coerceAtMost(maxHeight)
            .coerceAtLeast(minOf(dp(360), maxHeight))
        val params = WindowManager.LayoutParams(
            (metrics.widthPixels - dp(24)).coerceAtLeast(dp(260)),
            panelHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(72)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        return runCatching {
            manager.addView(root, params)
            windowManager = manager
            rootView = root
            initializeWindowState()
            removeVisibilityObserver = NodeInspectorVisibilitySession.observe {
                root.post { if (rootView === root) renderTree(context) }
            }
            renderTree(context)
            true
        }.getOrElse {
            clear()
            false
        }
    }

    private fun initializeWindowState() {
        capturedSnapshots.forEachIndexed { index, snapshot ->
            val tree = AccessibilityHierarchyTree(snapshot.nodes)
            expandedPaths[index] = tree.expandablePaths.toMutableSet()
            tree.rootNodes.firstOrNull()?.let { selectedPaths[index] = tree.pathOf(it) }
        }
    }

    private fun showWindowPicker(context: Context, anchor: View) {
        if (capturedSnapshots.size <= 1) return
        PopupMenu(context, anchor).apply {
            capturedSnapshots.forEachIndexed { index, snapshot ->
                val title = "${index + 1}. ${snapshot.window.activityClass.substringAfterLast('.')} · ID ${snapshot.windowId}"
                menu.add(title).setOnMenuItemClickListener {
                    activeWindowIndex = index
                    renderTree(context)
                    true
                }
            }
            show()
        }
    }

    private fun currentSnapshot(): AccessibilityTreeSnapshot? =
        capturedSnapshots.getOrNull(activeWindowIndex)

    private fun currentTree(): AccessibilityHierarchyTree? =
        currentSnapshot()?.let { AccessibilityHierarchyTree(it.nodes) }

    private fun renderTree(context: Context) {
        val snapshot = currentSnapshot() ?: return
        val tree = currentTree() ?: return
        val rows = treeRows ?: return
        val title = titleView
        val window = windowButton
        val displayIndex = activeWindowIndex + 1
        title?.text = "节点层级 · ${tree.rootNodes.size} 个根节点 · ${snapshot.nodes.size} 个采集节点"
        window?.text = if (capturedSnapshots.size > 1) "窗口 $displayIndex/${capturedSnapshots.size} ▾" else "窗口 $displayIndex"
        rows.removeAllViews()
        val hiddenPaths = NodeInspectorVisibilitySession.hiddenPaths(snapshot.window)
        val hiddenSelection = selectedPaths[activeWindowIndex]?.takeIf { it in hiddenPaths }
        if (hiddenSelection != null) selectedPaths.remove(activeWindowIndex)
        val searchResult = InspectorSearchEngine.evaluate(snapshot, searchCriteria, hiddenPaths)
        val filterActive = searchCriteria.query.isNotBlank() || searchCriteria.attributes.isNotEmpty() || searchCriteria.region != null
        val availableCount = snapshot.nodes.size - hiddenPaths.size
        searchBar?.updateCount(searchResult.count, availableCount.coerceAtLeast(0), searchResult.error)

        val expanded = expandedPaths.getOrPut(activeWindowIndex) { tree.expandablePaths.toMutableSet() }
        val selectedPath = selectedPaths[activeWindowIndex]
        val renderExpanded = if (filterActive && searchResult.error == null) {
            expanded.toMutableSet().apply {
                searchResult.matchingPaths.forEach { path ->
                    for (length in 0 until path.size) add(path.take(length))
                }
            }
        } else expanded
        tree.visibleRows(
            renderExpanded,
            if (filterActive && searchResult.error == null) searchResult.retainedPaths else null,
            hiddenPaths
        ).forEach { row ->
            val node = row.node
            val path = tree.pathOf(node)
            val selected = path == selectedPath
            val isMatch = path in searchResult.matchingPaths
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 6 + row.depth.coerceAtMost(18) * 15), dp(context, 2), dp(context, 5), dp(context, 2))
                setBackgroundColor(if (selected) 0xFFFBEAE4.toInt() else 0xFFF8F7F0.toInt())
                isClickable = true
                isLongClickable = true
                setOnClickListener { selectNode(context, tree, node) }
                setOnLongClickListener {
                    selectNode(context, tree, node)
                    showNodeContextMenu(context, it, snapshot, tree, node)
                    true
                }
            }
            val disclosure = TextView(context).apply {
                text = when {
                    row.hasChildren && row.expanded -> "▾"
                    row.hasChildren -> "▸"
                    else -> "·"
                }
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(if (row.hasChildren) 0xFF56665B.toInt() else 0xFFA3ACA1.toInt())
                setOnClickListener {
                    if (row.hasChildren) {
                        if (row.expanded) expanded.remove(path) else expanded.add(path)
                        renderTree(context)
                    } else {
                        selectNode(context, tree, node)
                    }
                }
            }
            item.addView(disclosure, LinearLayout.LayoutParams(dp(context, 26), dp(context, 34)))
            val label = TextView(context).apply {
                text = highlightedRowLabel(node, snapshot, searchCriteria)
                textSize = 10.5f
                setTextColor(
                    when {
                        selected -> 0xFFB42318.toInt()
                        filterActive && !isMatch -> 0xFFA3ACA1.toInt()
                        !node.visible -> 0xFFA3ACA1.toInt()
                        else -> 0xFF56665B.toInt()
                    }
                )
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(context, 3), dp(context, 5), dp(context, 3), dp(context, 5))
            }
            item.addView(label, LinearLayout.LayoutParams(dp(context, 285), -2))
            rows.addView(item, LinearLayout.LayoutParams(-1, -2))
            rows.addView(View(context).apply { setBackgroundColor(0xFFDDE2D8.toInt()) },
                LinearLayout.LayoutParams(-1, dp(context, 1)))
        }

        if (filterActive && searchResult.error == null && searchResult.count == 0) {
            rows.addView(TextView(context).apply {
                text = "没有匹配节点。可清空搜索词或属性筛选后恢复完整树。"
                textSize = 11f
                setTextColor(0xFF7D887E.toInt())
                setPadding(dp(context, 12), dp(context, 16), dp(context, 12), dp(context, 16))
            })
        }
        if (searchResult.error != null) {
            rows.addView(TextView(context).apply {
                text = "搜索表达式错误：${searchResult.error}"
                textSize = 10f
                setTextColor(0xFF9B5145.toInt())
                setPadding(dp(context, 8), dp(context, 7), dp(context, 8), dp(context, 7))
            })
        }

        if (snapshot.truncated || snapshot.walkTruncated || snapshot.depthTruncated) {
            rows.addView(TextView(context).apply {
                text = "采集快照不完整：已显示本窗口全部 ${snapshot.nodes.size} 个已采集节点；原因：${snapshot.stopReason ?: "预算限制"}。"
                textSize = 10f
                setTextColor(0xFF93652E.toInt())
                setPadding(dp(context, 7), dp(context, 8), dp(context, 7), dp(context, 8))
            })
        }
        selectedPath?.let(tree::find)?.let { selected ->
            detailsView?.text = formatNodeDetails(selected, snapshot)
        } ?: run {
            detailsView?.text = when {
                hiddenSelection != null -> "当前选中节点已隐藏，已从可选列表中移除。"
                snapshot.nodes.none { it.path.childIndices !in hiddenPaths } -> "当前窗口没有可选节点。"
                else -> "选择一个节点查看完整属性。"
            }
        }
    }

    private fun selectNode(
        context: Context,
        tree: AccessibilityHierarchyTree,
        node: AccessibilityNodeSnapshot
    ) {
        val path = tree.pathOf(node)
        if (NodeInspectorVisibilitySession.isHidden(NodeInspectorIdentity(currentSnapshot()?.window ?: return, node.path))) {
            Toast.makeText(context, "该节点已隐藏，不能再次选中", Toast.LENGTH_SHORT).show()
            return
        }
        selectedPaths[activeWindowIndex] = path
        val expanded = expandedPaths.getOrPut(activeWindowIndex) { tree.expandablePaths.toMutableSet() }
        tree.pathParent(path)?.let { expanded.addAllAncestors(it) }
        detailsView?.text = currentSnapshot()?.let { formatNodeDetails(node, it) }.orEmpty()
        renderTree(context)
    }

    private fun showNodeContextMenu(
        context: Context,
        anchor: View,
        snapshot: AccessibilityTreeSnapshot,
        tree: AccessibilityHierarchyTree,
        node: AccessibilityNodeSnapshot
    ) {
        val path = tree.pathOf(node)
        val expanded = expandedPaths.getOrPut(activeWindowIndex) { tree.expandablePaths.toMutableSet() }
        val menu = PopupMenu(context, anchor)
        fun add(title: String, action: () -> Unit) {
            menu.menu.add(title).setOnMenuItemClickListener {
                action()
                true
            }
        }
        add("显示节点信息") {
            selectedPaths[activeWindowIndex] = path
            detailsView?.text = formatNodeDetails(node, snapshot)
            renderTree(context)
        }
        add("选择父节点") {
            tree.parentOf(path)?.let { parent -> selectNode(context, tree, parent) }
                ?: Toast.makeText(context, "根节点没有父节点", Toast.LENGTH_SHORT).show()
        }
        if (tree.childrenOf(path).isNotEmpty()) {
            add("展开此节点及全部子节点") {
                expanded.addAll(tree.descendantExpandablePaths(path))
                renderTree(context)
            }
            add("折叠此节点的子节点") {
                expanded.removeAll(tree.descendantPaths(path))
                expanded.remove(path)
                renderTree(context)
            }
        }
        add("复制节点路径") {
            copy(context, "节点路径", path.joinToString("/").ifBlank { "root" })
        }
        add("复制资源 ID") { copy(context, "节点资源 ID", node.viewId) }
        if (snapshot.textCapture == NodeTextCapture.EPHEMERAL) {
            add("复制文本") { copy(context, "节点文本", node.text.orEmpty()) }
            add("复制内容描述") { copy(context, "节点内容描述", node.contentDescription.orEmpty()) }
        }
        add("复制边界坐标") {
            copy(context, "节点边界", "${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}")
        }
        add("生成定位代码并复制") {
            copy(context, "节点定位代码", NodeCodeSnippetGenerator.generate(node, snapshot))
        }
        menu.show()
    }

    private fun highlightedRowLabel(
        node: AccessibilityNodeSnapshot,
        snapshot: AccessibilityTreeSnapshot,
        criteria: InspectorSearchCriteria
    ): CharSequence {
        val label = formatRowLabel(node, snapshot)
        val query = criteria.query.trim()
        if (query.isBlank()) return label
        val range = if (criteria.regularExpression) {
            runCatching {
                java.util.regex.Pattern.compile(query, java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE)
                    .matcher(label).let { matcher -> if (matcher.find()) matcher.start() until matcher.end() else null }
            }.getOrNull()
        } else {
            val start = label.indexOf(query, ignoreCase = true)
            if (start >= 0) start until start + query.length else null
        } ?: return label
        if (range.first < 0 || range.last >= label.length) return label
        return SpannableString(label).apply {
            setSpan(BackgroundColorSpan(0xFFEAF2CF.toInt()), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(0xFF23372B.toInt()), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun formatRowLabel(node: AccessibilityNodeSnapshot, snapshot: AccessibilityTreeSnapshot): String = buildString {
        append(node.className.substringAfterLast('.').ifBlank { "Unknown" })
        if (node.viewId.isNotBlank()) append("  ·  ").append(node.viewId.substringAfterLast('/'))
        if (snapshot.textCapture == NodeTextCapture.EPHEMERAL) {
            listOf(node.text, node.contentDescription, node.hintText, node.stateDescription)
                .filterNot { it.isNullOrBlank() }
                .forEach { value -> append("  ·  ").append(value!!.replace('\n', ' ').take(32)) }
        }
        if (node.childCount > 0) append("  ·  ").append(node.childCount).append(" 子节点")
        if (!node.visible) append("  ·  不可见")
    }

    private fun formatNodeDetails(node: AccessibilityNodeSnapshot, snapshot: AccessibilityTreeSnapshot): String = buildString {
        append("类型：").append(node.className.ifBlank { "未知" })
        append("\n资源 ID：").append(node.viewId.ifBlank { "无" })
        append("\n路径：").append(node.path.childIndices.joinToString("/").ifBlank { "root" })
        append("\n窗口：").append(snapshot.window.activityClass).append("（ID ").append(snapshot.windowId).append('）')
        append("\n包名：").append(node.packageName.ifBlank { snapshot.window.packageName })
        append("\n边界：[").append(node.bounds.left).append(", ").append(node.bounds.top)
            .append(", ").append(node.bounds.right).append(", ").append(node.bounds.bottom).append(']')
        append("\n父级索引：").append(node.indexInParent)
        append("\n子节点数：").append(node.childCount)
        append("\n可见：").append(node.visible).append(" · 启用：").append(node.enabled)
        append("\n可点击：").append(node.clickable).append(" · 长按：").append(node.longClickable)
        append("\n可滚动：").append(node.scrollable).append(" · 可编辑：").append(node.editable)
        append("\n可勾选：").append(node.checkable).append(" · 已勾选：").append(node.checked)
        append("\n已选中：").append(node.selected).append(" · 焦点：").append(node.focused)
        append("\n文本采集：").append(snapshot.textCapture)
        if (snapshot.textCapture == NodeTextCapture.EPHEMERAL) {
            append("\n文本：").append(node.text.orEmpty().ifBlank { "（空）" })
            append("\n内容描述：").append(node.contentDescription.orEmpty().ifBlank { "（空）" })
            append("\n提示文本：").append(node.hintText.orEmpty().ifBlank { "（空）" })
        } else {
            append("\n文本/描述：本次未请求采集")
        }
        if (node.actions.isNotEmpty()) {
            append("\n可用操作：").append(node.actions.joinToString("、") { it.label?.takeIf(String::isNotBlank) ?: it.id.toString() })
        }
    }

    private fun copy(context: Context, label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(context, "无法访问剪贴板", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun MutableSet<List<Int>>.addAllAncestors(path: List<Int>) {
        for (length in 0..path.size) add(path.take(length))
    }

    fun clear() {
        searchBar?.hideKeyboard()
        removeVisibilityObserver?.invoke()
        removeVisibilityObserver = null
        val current = rootView
        rootView = null
        treeRows = null
        detailsView = null
        titleView = null
        windowButton = null
        val manager = windowManager
        windowManager = null
        if (current != null && manager != null) runCatching { manager.removeViewImmediate(current) }
        capturedSnapshots = emptyList()
        activeWindowIndex = 0
        selectedPaths.clear()
        expandedPaths.clear()
        searchCriteria = InspectorSearchCriteria()
    }
}
