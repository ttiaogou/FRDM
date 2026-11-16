package com.freedom.ui.logs

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.freedom.ui.theme.FreedomUi
import com.freedom.application.ports.NodeCapturePresentationPort
import com.freedom.core.accessibility.diagnostics.NodeCaptureSource
import com.freedom.core.accessibility.diagnostics.NodeInspectorDeviceContext
import com.freedom.core.accessibility.diagnostics.NodeInspectorValidationReportBuilder
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.AccessibilityFieldCaptureState
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.query.AccessibilityNodeInspectionIndex
import com.freedom.core.accessibility.query.NodeLocatorCandidate
import com.freedom.core.accessibility.query.NodeLocatorCandidateBuilder
import com.freedom.core.accessibility.query.NodeLocatorCandidateKind
import com.freedom.core.accessibility.query.NodeLocatorCandidateState
import com.freedom.core.accessibility.query.NodeLocatorEvidenceStability
import com.freedom.core.accessibility.query.NodeSelectorSnippetFormatter
import com.freedom.core.accessibility.query.NodeTreeIndex
import com.freedom.core.accessibility.query.NodeTreeFilterIndex
import kotlin.math.max
import java.util.concurrent.Executors

/**
 * Snapshot-driven node inspector. It renders only attributes actually present in the captured
 * structure; text and descriptions remain omitted by the default structural capture request.
 */
internal object NodeInspectorDialog {
    private const val MAX_TREE_ROWS = 600

    fun show(activity: Activity, dp: (Int) -> Int, capturePort: NodeCapturePresentationPort) {
        var selectedSource = NodeCaptureSource.MANUAL
        var selectedPath: List<Int>? = null
        var selectedWindowId: Int? = null
        val hiddenNodePaths = linkedSetOf<List<Int>>()
        var currentSnapshot: AccessibilityTreeSnapshot? = null
        var currentIndex: AccessibilityNodeInspectionIndex? = null
        var locatorRequestSequence = 0L
        var filterRequestSequence = 0L
        var currentFilterIndex: NodeTreeFilterIndex? = null
        val filterHandler = Handler(Looper.getMainLooper())
        var pendingFilterRunnable: Runnable? = null
        val filterExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "freedom-node-filter").apply { isDaemon = true }
        }
        val locatorExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "freedom-node-locator").apply { isDaemon = true }
        }
        lateinit var inspectorDialog: AlertDialog

        lateinit var status: TextView
        lateinit var validationReportButton: Button
        lateinit var map: NodeInspectorBoundsMap
        lateinit var tree: LinearLayout
        lateinit var details: TextView
        lateinit var relationStatus: TextView
        lateinit var locatorResults: LinearLayout
        lateinit var filterInput: EditText
        lateinit var autoButton: Button
        lateinit var manualButton: Button
        lateinit var windowButton: Button
        lateinit var parentButton: Button
        lateinit var clickableAncestorButton: Button
        lateinit var childrenButton: Button
        lateinit var highlightNodeButton: Button
        lateinit var clearHighlightButton: Button
        lateinit var nodeMenuButton: Button
        lateinit var screenPreviewButton: Button
        lateinit var inspectorScroll: ScrollView

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        content.addView(TextView(activity).apply {
            text = "节点可视化"
            textSize = 20f
            setTextColor(0xFF23372B.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })
        content.addView(TextView(activity).apply {
            text = "边界图、层级树和属性详情使用同一份节点快照。可将选中控件的边界投射到屏幕；服务会刷新并校验目标后才显示。"
            textSize = 12f
            setTextColor(0xFF7D887E.toInt())
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        val sourceRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        autoButton = Button(activity).apply { text = "程序自主采集"; isAllCaps = false }
        manualButton = Button(activity).apply { text = "浮动按钮采集"; isAllCaps = false }
        sourceRow.addView(autoButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        sourceRow.addView(manualButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(sourceRow)
        windowButton = Button(activity).apply {
            text = "当前窗口 · 尚未采集"
            isAllCaps = false
            isEnabled = false
        }
        content.addView(windowButton, LinearLayout.LayoutParams(-1, dp(42)))
        status = TextView(activity).apply {
            textSize = 12f
            setTextColor(0xFF56665B.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        content.addView(status)
        validationReportButton = Button(activity).apply {
            text = "复制真机验收诊断报告"
            isAllCaps = false
            setOnClickListener {
                val device = capturePort.deviceContext() ?: NodeInspectorDeviceContext(
                    targetPackage = currentSnapshot?.window?.packageName.orEmpty(),
                    androidApiLevel = 0,
                    manufacturer = "UNKNOWN",
                    model = "UNKNOWN",
                    appVersionName = null,
                    accessibilityServiceEnabled = false,
                    floatingWindowPermissionGranted = false,
                    displayWidthPixels = 0,
                    displayHeightPixels = 0,
                    density = 0f,
                    orientation = 0
                )
                val report = NodeInspectorValidationReportBuilder.build(
                    source = selectedSource,
                    snapshots = capturePort.latestSnapshots(selectedSource),
                    device = device
                )
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard == null) {
                    Toast.makeText(activity, "无法访问系统剪贴板", Toast.LENGTH_SHORT).show()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Freedom 节点检查验收报告", report))
                    Toast.makeText(activity, "验收报告已复制；报告不包含节点文本和聊天内容", Toast.LENGTH_LONG).show()
                }
            }
        }
        content.addView(validationReportButton, LinearLayout.LayoutParams(-1, dp(44)))

        map = NodeInspectorBoundsMap(activity, dp).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(230))
        }
        content.addView(map)
        content.addView(TextView(activity).apply {
            text = "绿色：有效边界；深绿色：当前节点祖先；红色：当前选中节点。点击空间图或下方树节点可双向定位。"
            textSize = 11f
            setTextColor(0xFF7D887E.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })

        filterInput = EditText(activity).apply {
            hint = "按控件类型或资源 ID 筛选"
            textSize = 13f
            setSingleLine(true)
            setPadding(dp(10), 0, dp(10), 0)
        }
        content.addView(filterInput, LinearLayout.LayoutParams(-1, dp(44)))

        tree = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val treeScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addView(tree)
            isFillViewport = true
        }
        content.addView(treeScroll, LinearLayout.LayoutParams(-1, dp(245)))

        content.addView(TextView(activity).apply {
            text = "选中控件属性"
            textSize = 13f
            setTextColor(0xFF23372B.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(4))
        })
        details = TextView(activity).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFF56665B.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFEFEEE7.toInt())
                cornerRadius = dp(8).toFloat()
            }
        }
        content.addView(details, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(activity).apply {
            text = "控件关系与导航定位"
            textSize = 13f
            setTextColor(0xFF23372B.toInt())
            setPadding(dp(4), dp(10), dp(4), dp(4))
        })
        relationStatus = TextView(activity).apply {
            textSize = 11f
            setTextColor(0xFF56665B.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        content.addView(relationStatus)
        val relationActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        parentButton = Button(activity).apply { text = "父节点"; isAllCaps = false }
        clickableAncestorButton = Button(activity).apply { text = "最近可点击祖先"; isAllCaps = false }
        childrenButton = Button(activity).apply { text = "子节点"; isAllCaps = false }
        relationActions.addView(parentButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        relationActions.addView(clickableAncestorButton, LinearLayout.LayoutParams(0, dp(42), 1.5f))
        relationActions.addView(childrenButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        content.addView(relationActions)

        val highlightActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        highlightNodeButton = Button(activity).apply {
            text = "屏幕高亮 5 秒"
            isAllCaps = false
            isEnabled = false
        }
        clearHighlightButton = Button(activity).apply {
            text = "清除高亮"
            isAllCaps = false
        }
        highlightActions.addView(highlightNodeButton, LinearLayout.LayoutParams(0, dp(42), 1.2f))
        highlightActions.addView(clearHighlightButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        content.addView(highlightActions)
        nodeMenuButton = Button(activity).apply {
            text = "所选节点 · 二级菜单"
            isAllCaps = false
            isEnabled = false
        }
        content.addView(nodeMenuButton, LinearLayout.LayoutParams(-1, dp(42)))
        screenPreviewButton = Button(activity).apply {
            text = "在目标 App 上显示全部节点边界 · 12 秒"
            isAllCaps = false
            isEnabled = false
        }
        content.addView(screenPreviewButton, LinearLayout.LayoutParams(-1, dp(42)))
        content.addView(TextView(activity).apply {
            text = "屏幕高亮只绘制不可触摸边界，不执行点击或滚动。请求会重新采集目标窗口；窗口代次变化、节点歧义或快照不完整时将拒绝显示。"
            textSize = 10f
            setTextColor(0xFF7D887E.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })

        content.addView(TextView(activity).apply {
            text = "定位候选分析"
            textSize = 13f
            setTextColor(0xFF23372B.toInt())
            setPadding(dp(4), dp(10), dp(4), dp(4))
        })
        content.addView(TextView(activity).apply {
            text = "唯一性只对本次完整快照成立；页面变化后必须重新采集并验证。生成的条件只供审核和复用，不会自动执行。"
            textSize = 10f
            setTextColor(0xFF7D887E.toInt())
            setPadding(dp(4), 0, dp(4), dp(4))
        })
        locatorResults = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        content.addView(locatorResults, LinearLayout.LayoutParams(-1, -2))

        lateinit var selectNode: (AccessibilityNodeSnapshot) -> Unit
        lateinit var showNodeContextMenu: (AccessibilityNodeSnapshot) -> Unit

        fun renderDetails(node: AccessibilityNodeSnapshot?) {
            val textCapture = currentSnapshot?.textCapture ?: NodeTextCapture.NONE
            details.text = node?.let { NodeInspectorTextFormatter.formatNodeDetails(it, textCapture) } ?: "未选中控件。"
        }

        fun renderRelations(node: AccessibilityNodeSnapshot?) {
            val index = currentIndex
            val snapshot = currentSnapshot
            if (node == null || index == null || snapshot == null) {
                relationStatus.text = "尚未选择控件。"
                parentButton.isEnabled = false
                clickableAncestorButton.isEnabled = false
                childrenButton.isEnabled = false
                highlightNodeButton.isEnabled = false
                return
            }
            val path = node.path.childIndices
            val parent = index.parentOf(path)
            val children = index.childrenOf(path)
            val clickableAncestor = index.ancestorsOf(path).asReversed().firstOrNull { ancestor ->
                ancestor.captureState(AccessibilityNodeField.CLICKABLE, snapshot.textCapture) ==
                    AccessibilityFieldCaptureState.VALUE_PRESENT && ancestor.clickable
            }
            relationStatus.text = buildString {
                append("路径：").append(path.joinToString("/").ifBlank { "root" })
                append("；直接父节点：").append(parent?.className?.substringAfterLast('.') ?: "无")
                append("；子节点：").append(children.size)
                append("；最近可点击祖先：").append(clickableAncestor?.className?.substringAfterLast('.') ?: "无")
            }
            parentButton.isEnabled = parent != null
            clickableAncestorButton.isEnabled = clickableAncestor != null
            childrenButton.isEnabled = children.isNotEmpty()
            highlightNodeButton.isEnabled = !node.bounds.isEmpty &&
                node.captureState(AccessibilityNodeField.BOUNDS, snapshot.textCapture) ==
                AccessibilityFieldCaptureState.VALUE_PRESENT
        }

        fun candidateKindLabel(kind: NodeLocatorCandidateKind): String = when (kind) {
            NodeLocatorCandidateKind.VIEW_ID_AND_CLASS -> "资源 ID + 控件类型"
            NodeLocatorCandidateKind.VIEW_ID -> "资源 ID"
            NodeLocatorCandidateKind.TEXT_AND_CLASS -> "文本 + 控件类型"
            NodeLocatorCandidateKind.CONTENT_DESCRIPTION_AND_CLASS -> "无障碍描述 + 控件类型"
            NodeLocatorCandidateKind.STRUCTURAL_ATTRIBUTES -> "交互属性 + 控件类型"
        }

        fun candidateStateLabel(state: NodeLocatorCandidateState): String = when (state) {
            NodeLocatorCandidateState.UNIQUE_IN_SNAPSHOT -> "当前快照唯一"
            NodeLocatorCandidateState.AMBIGUOUS -> "多个节点命中"
            NodeLocatorCandidateState.NO_MATCH -> "没有命中"
            NodeLocatorCandidateState.INDETERMINATE -> "无法确认唯一性"
            NodeLocatorCandidateState.TARGET_NOT_MATCHED -> "未命中所选控件"
        }

        fun stabilityLabel(stability: NodeLocatorEvidenceStability): String = when (stability) {
            NodeLocatorEvidenceStability.RESOURCE_IDENTIFIER -> "资源标识；仍需跨页面验证"
            NodeLocatorEvidenceStability.CONTENT_VALUE -> "内容值；可能随页面变化"
            NodeLocatorEvidenceStability.STRUCTURAL_ATTRIBUTES -> "结构属性；可能重复"
        }

        fun copySelector(candidate: NodeLocatorCandidate) {
            val snippet = NodeSelectorSnippetFormatter.format(candidate.selector)
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                Toast.makeText(activity, "无法访问系统剪贴板", Toast.LENGTH_SHORT).show()
                return
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Freedom NodeSelector", snippet))
            Toast.makeText(activity, "已复制定位条件；代码不会自动执行", Toast.LENGTH_SHORT).show()
        }

        fun renderCandidateCard(candidate: NodeLocatorCandidate, index: AccessibilityNodeInspectionIndex) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(7), dp(9), dp(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFFBFAF5.toInt())
                    cornerRadius = dp(7).toFloat()
                    setStroke(dp(1), 0xFFDDE2D8.toInt())
                }
            }
            val countLabel = if (candidate.matchCountExact) candidate.matchCount.toString()
                else "至少 ${candidate.matchCount}（命中数下界）"
            card.addView(TextView(activity).apply {
                text = "${candidateKindLabel(candidate.kind)} · ${candidateStateLabel(candidate.state)}"
                textSize = 12f
                setTextColor(
                    if (candidate.state == NodeLocatorCandidateState.UNIQUE_IN_SNAPSHOT) 0xFF718E22.toInt()
                    else 0xFFB54708.toInt()
                )
            })
            card.addView(TextView(activity).apply {
                text = "命中数：$countLabel\n依据：${candidate.evidenceFields.joinToString()}\n" +
                    "稳定性：${stabilityLabel(candidate.stability)}\n条件：${candidate.selector.describe()}"
                textSize = 10f
                setTextColor(0xFF56665B.toInt())
                setPadding(0, dp(4), 0, dp(4))
            })
            candidate.stopReason?.let { reason ->
                card.addView(TextView(activity).apply {
                    text = "无法确认原因：$reason"
                    textSize = 10f
                    setTextColor(0xFFB54708.toInt())
                })
            }
            val cardActions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            cardActions.addView(Button(activity).apply {
                text = "复制 NodeSelector"
                isAllCaps = false
                setOnClickListener { copySelector(candidate) }
            })
            card.addView(cardActions)
            if (candidate.matchedPaths.isNotEmpty()) {
                candidate.matchedPaths.forEachIndexed { matchIndex, path ->
                    val matchedNode = index.find(path.childIndices) ?: return@forEachIndexed
                    card.addView(TextView(activity).apply {
                        text = "查看命中 ${matchIndex + 1}：${matchedNode.className.substringAfterLast('.')} · ${path.childIndices.joinToString("/")}"
                        textSize = 10f
                        setTextColor(0xFF718E22.toInt())
                        setPadding(0, dp(3), 0, dp(3))
                        isClickable = true
                        setOnClickListener { selectNode(matchedNode) }
                    })
                }
            }
            locatorResults.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            })
        }

        fun renderLocatorAnalysis(node: AccessibilityNodeSnapshot?) {
            locatorRequestSequence += 1
            val requestId = locatorRequestSequence
            locatorResults.removeAllViews()
            val snapshot = currentSnapshot
            if (node == null || snapshot == null) {
                locatorResults.addView(emptyLabel(activity, dp, "选择一个控件后分析可用定位条件。"))
                return
            }
            locatorResults.addView(emptyLabel(activity, dp, "正在分析定位条件；只检查当前快照，不执行任何控件动作。"))
            val requestedPath = node.path
            locatorExecutor.execute {
                val analysis = runCatching {
                    NodeLocatorCandidateBuilder.build(NodeTreeIndex(snapshot), requestedPath)
                }
                activity.runOnUiThread {
                    if (requestId != locatorRequestSequence || !inspectorDialog.isShowing ||
                        activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed) ||
                        requestedPath.childIndices != selectedPath
                    ) return@runOnUiThread
                    locatorResults.removeAllViews()
                    analysis.onSuccess { result ->
                        if (result.candidates.isEmpty()) {
                            locatorResults.addView(emptyLabel(
                                activity, dp,
                                when (result.reason) {
                                    "NO_CAPTURED_IDENTITY_OR_INTERACTION_EVIDENCE" -> "当前采集没有足够的 ID、类型、文本或交互属性证据来生成定位条件。"
                                    "SELECTED_NODE_NOT_IN_SNAPSHOT" -> "所选控件已不在当前快照中，请重新采集。"
                                    else -> "当前快照没有可生成的定位候选。"
                                }
                            ))
                        } else {
                            result.candidates.forEach { candidate -> renderCandidateCard(candidate, currentIndex ?: return@forEach) }
                        }
                        if (!result.snapshotComplete) {
                            locatorResults.addView(emptyLabel(activity, dp,
                                "源快照不完整：任何单个命中都不能证明全树唯一。"))
                        } else if (result.reason != null) {
                            locatorResults.addView(emptyLabel(activity, dp, "分析提示：${result.reason}"))
                        }
                    }.onFailure { error ->
                        locatorResults.addView(emptyLabel(activity, dp,
                            "定位分析失败：${error.javaClass.simpleName}"))
                    }
                }
            }
        }

        fun renderTreeRows() {
            tree.removeAllViews()
            val snapshot = currentSnapshot
            val index = currentIndex
            if (snapshot == null || index == null) {
                tree.addView(emptyLabel(activity, dp, "没有可显示的节点信息。"))
                renderDetails(null)
                return
            }

            val keyword = filterInput.text.toString().trim()
            filterRequestSequence += 1
            val requestId = filterRequestSequence
            pendingFilterRunnable?.let(filterHandler::removeCallbacks)
            tree.addView(emptyLabel(activity, dp, "正在筛选当前快照…"))
            val filterIndex = currentFilterIndex
            val scheduleFilter = Runnable {
                runCatching {
                    filterExecutor.execute {
                        val result = runCatching {
                            filterIndex?.search(keyword, MAX_TREE_ROWS)
                        }
                        activity.runOnUiThread {
                        if (requestId != filterRequestSequence || !inspectorDialog.isShowing ||
                            activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)
                        ) return@runOnUiThread
                        tree.removeAllViews()
                        result.onSuccess { filtered ->
                            if (filtered == null || filtered.matchedCount == 0) {
                                tree.addView(emptyLabel(activity, dp, "没有控件类型或资源 ID 命中关键词。"))
                            } else {
                                val visibleRows = filtered.nodes.filterNot { it.path.childIndices in hiddenNodePaths }
                                if (visibleRows.isEmpty()) {
                                    tree.addView(emptyLabel(activity, dp, "匹配节点已在当前可视化中隐藏。"))
                                }
                                visibleRows.forEach { node ->
                                    val path = node.path.childIndices
                                    val isSelected = path == selectedPath
                                    val isAncestor = selectedPath?.let { selected ->
                                        path.size < selected.size && selected.take(path.size) == path
                                    } ?: false
                                    val row = TextView(activity).apply {
                                        text = NodeInspectorTextFormatter.formatTreeRow(node, snapshot.textCapture)
                                        textSize = 11f
                                        setTextColor(
                                            when {
                                                isSelected -> 0xFFB42318.toInt()
                                                isAncestor -> 0xFF718E22.toInt()
                                                else -> 0xFF56665B.toInt()
                                            }
                                        )
                                        setPadding(dp(8), dp(7), dp(8), dp(7))
                                        setBackgroundColor(
                                            when {
                                                isSelected -> 0xFFFFE4E0.toInt()
                                                isAncestor -> 0xFFEAF2CF.toInt()
                                                else -> if (path.size % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFF8F7F0.toInt()
                                            }
                                        )
                                        isClickable = true
                                        isLongClickable = true
                                        setOnClickListener { selectNode(node) }
                                        setOnLongClickListener {
                                            showNodeContextMenu(node)
                                            true
                                        }
                                    }
                                    tree.addView(row, LinearLayout.LayoutParams(-1, -2))
                                    val divider = View(activity).apply { setBackgroundColor(0xFFDDE2D8.toInt()) }
                                    tree.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
                                }
                            }
                            if (filtered != null && filtered.truncated) {
                                tree.addView(emptyLabel(activity, dp,
                                    "为保证检查器响应速度，仅展示前 ${filtered.nodes.size} 个匹配节点；快照中实际有 ${filtered.matchedCount} 个匹配节点。"))
                            }
                            if (keyword.isNotEmpty()) {
                                tree.addView(emptyLabel(activity, dp, "筛选只匹配 className 和 viewId；未请求的文本不会被用于筛选。"))
                            }
                        }.onFailure { error ->
                            tree.addView(emptyLabel(activity, dp, "筛选失败：${error.javaClass.simpleName}"))
                        }
                        }
                    }
                }
            }
            pendingFilterRunnable = scheduleFilter
            filterHandler.postDelayed(scheduleFilter, 160L)
            val selected = selectedPath?.let(index::find)
            renderDetails(selected)
            map.setSelection(index, selectedPath, hiddenNodePaths)
        }

        selectNode = { node ->
            selectedPath = node.path.childIndices
            val index = currentIndex
            if (index != null) {
                map.setSelection(index, selectedPath, hiddenNodePaths)
                nodeMenuButton.isEnabled = true
                screenPreviewButton.isEnabled = true
                renderDetails(node)
                renderRelations(node)
                renderTreeRows()
                renderLocatorAnalysis(node)
            }
        }

        fun render() {
            val snapshots = capturePort.latestSnapshots(selectedSource)
            val snapshot = snapshots.firstOrNull { it.windowId == selectedWindowId }
                ?: snapshots.firstOrNull()
            selectedWindowId = snapshot?.windowId
            autoButton.alpha = if (selectedSource == NodeCaptureSource.AUTOMATIC) 1f else 0.62f
            manualButton.alpha = if (selectedSource == NodeCaptureSource.MANUAL) 1f else 0.62f
            val selectedWindowIndex = snapshots.indexOfFirst { it.windowId == snapshot?.windowId }
            windowButton.text = if (snapshot == null) {
                "当前窗口 · 尚未采集"
            } else {
                "检查窗口 ${selectedWindowIndex + 1}/${snapshots.size} · ID ${snapshot.windowId} · " +
                    if (snapshot.windowId == -1) "未知窗口标识" else "点击切换"
            }
            windowButton.isEnabled = snapshots.size > 1
            currentSnapshot = snapshot
            currentIndex = snapshot?.let(::AccessibilityNodeInspectionIndex)
            currentFilterIndex = snapshot?.let { NodeTreeFilterIndex(it.nodes) }
            filterRequestSequence += 1
            pendingFilterRunnable?.let(filterHandler::removeCallbacks)
            tree.removeAllViews()
            if (snapshot == null) {
                locatorRequestSequence += 1
                nodeMenuButton.isEnabled = false
                screenPreviewButton.isEnabled = false
                windowButton.isEnabled = false
                status.text = when (selectedSource) {
                    NodeCaptureSource.AUTOMATIC -> "暂无程序自主采集快照。自动采集只在对应业务任务触发时运行。"
                    NodeCaptureSource.MANUAL -> "暂无手动快照。点击上方按钮可采集当前目标 App 所有可访问窗口。"
                }
                map.setSelection(null, null)
                renderDetails(null)
                renderRelations(null)
                renderLocatorAnalysis(null)
                tree.addView(emptyLabel(activity, dp, "没有可显示的节点信息。"))
                return
            }
            status.text = buildString {
                append("来源：").append(selectedSourceLabel(selectedSource))
                append("\n目标应用：").append(snapshot.window.packageName)
                append("\n同包名窗口：").append(snapshots.size).append(" 个；当前 Window ID：").append(snapshot.windowId)
                append("\nActivity：").append(snapshot.window.activityClass.substringAfterLast('.'))
                append("\n窗口代次：").append(snapshot.window.generation)
                append("\n采集时间：").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", snapshot.capturedAtEpochMs))
                append("\n访问节点：").append(snapshot.visitedNodeCount)
                append("；快照节点：").append(snapshot.nodes.size)
                append("；有效可见边界：").append(currentIndex?.visibleBoundedNodes?.size ?: 0)
                append("；扫描上限：").append(snapshot.requestedMaxNodes)
                if (snapshot.truncated) append("；结果不完整，不能据此确认全树唯一性")
                snapshot.stopReason?.let { append("；停止原因：").append(it) }
            }

            val index = currentIndex!!
            val initial = selectedPath?.let(index::find)
                ?: index.visibleBoundedNodes.firstOrNull { it.clickable }
                ?: index.visibleBoundedNodes.firstOrNull()
                ?: index.nodes.firstOrNull()
            selectedPath = initial?.path?.childIndices
            map.setSelection(index, selectedPath, hiddenNodePaths)
            nodeMenuButton.isEnabled = initial != null
            screenPreviewButton.isEnabled = initial != null
            filterInput.setText("")
            renderTreeRows()
            renderDetails(initial)
            renderRelations(initial)
            renderLocatorAnalysis(initial)
        }

        fun showWindowPicker() {
            val snapshots = capturePort.latestSnapshots(selectedSource)
            if (snapshots.size <= 1) return
            val labels = snapshots.mapIndexed { index, snapshot ->
                buildString {
                    append("窗口 ").append(index + 1)
                    append(" · ID ").append(snapshot.windowId)
                    append(" · ").append(snapshot.window.packageName)
                    if (snapshot.visitedNodeCount > 0) append(" · ").append(snapshot.visitedNodeCount).append(" 节点")
                    if (snapshot.truncated) append(" · 不完整")
                }
            }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("切换目标应用窗口")
                .setItems(labels) { _, which ->
                    selectedWindowId = snapshots[which].windowId
                    selectedPath = null
                    hiddenNodePaths.clear()
                    render()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        showNodeContextMenu = { node ->
            selectNode(node)
            val options = arrayOf(
                "显示控件信息",
                "查看布局层级",
                "生成定位代码",
                "选择父节点",
                "隐藏此节点",
                "隐藏相同边界节点",
                "切换窗口",
                "保存布局到文件",
                "退出检查器"
            )
            AlertDialog.Builder(activity)
                .setTitle("节点操作 · ${node.className.substringAfterLast('.')}")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> selectNode(node)
                        1 -> {
                            selectNode(node)
                            inspectorScroll.post { inspectorScroll.smoothScrollTo(0, treeScroll.top) }
                        }
                        2 -> {
                            selectNode(node)
                            renderLocatorAnalysis(node)
                            inspectorScroll.post { inspectorScroll.smoothScrollTo(0, locatorResults.top) }
                        }
                        3 -> {
                            currentIndex?.parentOf(node.path.childIndices)?.let { selectNode(it) }
                                ?: Toast.makeText(activity, "所选节点没有已采集到的父节点", Toast.LENGTH_SHORT).show()
                        }
                        4 -> {
                            hiddenNodePaths += node.path.childIndices.toList()
                            map.setSelection(currentIndex, selectedPath, hiddenNodePaths)
                            renderTreeRows()
                            Toast.makeText(activity, "已在可视化中隐藏所选节点", Toast.LENGTH_SHORT).show()
                        }
                        5 -> {
                            val snapshot = currentSnapshot
                            val bounds = node.bounds
                            if (snapshot != null && !bounds.isEmpty) {
                                val before = hiddenNodePaths.size
                                snapshot.nodes.filter { it.bounds == bounds }.forEach {
                                    hiddenNodePaths += it.path.childIndices.toList()
                                }
                                val hiddenCount = hiddenNodePaths.size - before
                                map.setSelection(currentIndex, selectedPath, hiddenNodePaths)
                                renderTreeRows()
                                Toast.makeText(activity, "已隐藏 $hiddenCount 个相同边界节点", Toast.LENGTH_SHORT).show()
                            }
                        }
                        6 -> showWindowPicker()
                        7 -> currentSnapshot?.let { capturePort.requestLayoutExport(selectedSource, it.windowId) }
                        8 -> inspectorDialog.dismiss()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        map.setOnNodeSelected(selectNode)
        parentButton.setOnClickListener {
            val selected = selectedPath?.let { path -> currentIndex?.find(path) }
            val parent = selected?.let { currentIndex?.parentOf(it.path.childIndices) }
            if (parent != null) selectNode(parent)
            else Toast.makeText(activity, "所选节点没有已采集到的父节点", Toast.LENGTH_SHORT).show()
        }
        clickableAncestorButton.setOnClickListener {
            val selected = selectedPath?.let { path -> currentIndex?.find(path) }
            val snapshot = currentSnapshot
            val ancestor = if (selected != null && snapshot != null) {
                currentIndex?.ancestorsOf(selected.path.childIndices)?.asReversed()?.firstOrNull { node ->
                    node.captureState(AccessibilityNodeField.CLICKABLE, snapshot.textCapture) ==
                        AccessibilityFieldCaptureState.VALUE_PRESENT && node.clickable
                }
            } else null
            if (ancestor != null) selectNode(ancestor)
            else Toast.makeText(activity, "没有采集到可点击的父级控件", Toast.LENGTH_SHORT).show()
        }
        highlightNodeButton.setOnClickListener {
            val path = selectedPath
            val snapshot = currentSnapshot
            val selected = path?.let { currentIndex?.find(it) }
            if (path == null || snapshot == null || selected == null || selected.bounds.isEmpty) {
                Toast.makeText(activity, "当前选中控件没有可用的屏幕边界", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            capturePort.requestNodeBoundsHighlight(selectedSource, NodePath(path), snapshot.windowId)
            Toast.makeText(
                activity,
                "高亮请求已提交；服务将重新采集并校验目标窗口后显示 5 秒",
                Toast.LENGTH_SHORT
            ).show()
        }
        nodeMenuButton.setOnClickListener {
            selectedPath?.let { path -> currentIndex?.find(path) }?.let(showNodeContextMenu)
                ?: Toast.makeText(activity, "请先选择一个节点", Toast.LENGTH_SHORT).show()
        }
        screenPreviewButton.setOnClickListener {
            val snapshot = currentSnapshot
            if (snapshot == null) {
                Toast.makeText(activity, "请先采集并选择一个目标窗口", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            capturePort.requestScreenLayoutPreview(selectedSource, snapshot.windowId)
        }
        windowButton.setOnClickListener { showWindowPicker() }
        clearHighlightButton.setOnClickListener {
            capturePort.clearNodeBoundsHighlight()
            Toast.makeText(activity, "已提交清除高亮请求", Toast.LENGTH_SHORT).show()
        }
        childrenButton.setOnClickListener {
            val selected = selectedPath?.let { path -> currentIndex?.find(path) }
            val children = selected?.let { currentIndex?.childrenOf(it.path.childIndices) }.orEmpty()
            if (children.isEmpty()) {
                Toast.makeText(activity, "当前控件没有已采集到的直接子节点", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("选择子节点（${children.size}）")
                    .setItems(children.mapIndexed { childIndex, child ->
                        "${childIndex + 1}. ${child.className.substringAfterLast('.')} · ${child.viewId.ifBlank { "无 ViewId" }}"
                    }.toTypedArray()) { _, which -> selectNode(children[which]) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        autoButton.setOnClickListener {
            selectedSource = NodeCaptureSource.AUTOMATIC
            selectedPath = null
            selectedWindowId = null
            hiddenNodePaths.clear()
            locatorRequestSequence += 1
            render()
        }
        manualButton.setOnClickListener {
            selectedSource = NodeCaptureSource.MANUAL
            selectedPath = null
            selectedWindowId = null
            hiddenNodePaths.clear()
            locatorRequestSequence += 1
            render()
        }
        filterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderTreeRows()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(Button(activity).apply {
            text = "刷新快照"
            isAllCaps = false
            setOnClickListener { render() }
        }, LinearLayout.LayoutParams(-1, dp(46)))
        content.addView(actions)

        inspectorScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addView(content)
        }
        inspectorDialog = AlertDialog.Builder(activity)
            .setView(inspectorScroll)
            .setPositiveButton("关闭", null)
            .create()
        inspectorDialog.setOnDismissListener {
            locatorRequestSequence += 1
            filterRequestSequence += 1
            pendingFilterRunnable?.let(filterHandler::removeCallbacks)
            filterExecutor.shutdownNow()
            locatorExecutor.shutdownNow()
        }
        inspectorDialog.show()
        FreedomUi.styleAlertDialog(inspectorDialog, activity, dp, widthFraction = 0.94f)
        render()
    }

    private fun emptyLabel(activity: Activity, dp: (Int) -> Int, text: String) =
        TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF7D887E.toInt())
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }

    private fun selectedSourceLabel(source: NodeCaptureSource): String = when (source) {
        NodeCaptureSource.MANUAL -> "浮动按钮采集"
        NodeCaptureSource.AUTOMATIC -> "程序自主采集"
    }

}
