package com.freedom.adapters.social.momo

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.adapters.social.momo.DetailPageIdentityInspection
import com.freedom.adapters.social.momo.DynamicTabInspection
import com.freedom.adapters.social.momo.DynamicTabRowEvidence
import com.freedom.adapters.social.momo.FeedReadinessInspection
import com.freedom.adapters.social.momo.HomeBottomNavEvidence
import com.freedom.adapters.social.momo.PageFingerprint
import com.freedom.adapters.social.momo.TabLabelEvidence
import java.util.ArrayDeque

/**
 * Reads Momo page structure and returns evidence only.
 *
 * This component does not click, scroll, navigate, schedule work, or mutate the
 * account-browse state machine. The service remains responsible for deciding
 * what action to take from the inspection results.
 */
internal class MomoPageInspector(
    private val context: Context,
    private val dp: (Int) -> Int,
    private val config: Config
) {
    internal data class Config(
        val targetPackage: String,
        val homeNavLabels: Set<String>,
        val homeTabLabel: String,
        val homeNavLowerBandRatio: Float,
        val dynamicTabLabels: Set<String>,
        val dynamicTabLabel: String,
        val nearbyTabLabel: String,
        val aiTabLabel: String,
        val dynamicTabUpperBandRatio: Float,
        val dynamicTabRowToleranceDp: Int,
        val dynamicTabContentGapDp: Int,
        val pageFingerprintMaxNodes: Int,
        val fingerprintSizeBucketPx: Int,
        val minListWidthRatio: Float,
        val minListHeightRatio: Float,
        val maxNodeScanDurationMs: Long = 350L,
        val maxNodeScanDepth: Int = 32
    ) {
        init {
            require(maxNodeScanDurationMs in 1L..2_000L)
            require(maxNodeScanDepth in 1..64)
        }
    }

    private fun scanDeadlineNanos(): Long = System.nanoTime() + config.maxNodeScanDurationMs * 1_000_000L

    private data class PageNodeFact(
        val path: List<Int>,
        val ancestors: List<String>,
        val id: String,
        val className: String,
        val text: String,
        val visible: Boolean,
        val selected: Boolean,
        val clickable: Boolean,
        val scrollable: Boolean,
        val bounds: Rect
    )

    private data class PageNodeScan(val nodes: List<PageNodeFact>, val truncated: Boolean)

    /** One bounded snapshot-local walk. Facts contain no live Android node handles. */
    private fun scanPageFacts(
        root: AccessibilityNodeInfo,
        pruneDynamicContentLists: Boolean = false,
        pruneDynamicDetailPayload: Boolean = false
    ): PageNodeScan {
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, List<Int>, List<String>>>()
        queue.add(Triple(AccessibilityNodeInfo.obtain(root), emptyList(), emptyList()))
        val facts = ArrayList<PageNodeFact>()
        val deadline = scanDeadlineNanos()
        var visited = 0
        var truncated = false
        try {
            while (queue.isNotEmpty() && visited < config.pageFingerprintMaxNodes &&
                System.nanoTime() < deadline
            ) {
                val (node, path, ancestors) = queue.removeFirst()
                visited++
                try {
                    val rawId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
                    val id = rawId.substringAfterLast('/').substringAfterLast(':')
                    val className = runCatching { node.className?.toString().orEmpty().substringAfterLast('.') }
                        .getOrDefault("")
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    facts += PageNodeFact(
                        path = path,
                        ancestors = ancestors,
                        id = id,
                        className = className,
                        text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault(""),
                        visible = runCatching { node.isVisibleToUser }.getOrDefault(false) &&
                            bounds.width() > 0 && bounds.height() > 0,
                        selected = runCatching { node.isSelected || node.isChecked }.getOrDefault(false),
                        clickable = runCatching { node.isClickable && node.isEnabled }.getOrDefault(false),
                        scrollable = runCatching { node.isScrollable }.getOrDefault(false),
                        bounds = Rect(bounds)
                    )
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    val isDynamicFeedList = pruneDynamicContentLists && className == "RecyclerView" &&
                        ancestors.contains("pagertabcontent") && bounds.width() >=
                        context.resources.displayMetrics.widthPixels * config.minListWidthRatio &&
                        bounds.height() >= context.resources.displayMetrics.heightPixels * config.minListHeightRatio
                    val isDynamicDetailList = pruneDynamicDetailPayload && className == "RecyclerView" &&
                        bounds.width() >= context.resources.displayMetrics.widthPixels * 0.88f &&
                        bounds.height() >= context.resources.displayMetrics.heightPixels * 0.55f &&
                        bounds.top < context.resources.displayMetrics.heightPixels * 0.22f
                    if (path.size >= config.maxNodeScanDepth && childCount > 0) truncated = true
                    else if (!isDynamicFeedList) {
                        // The detail's first RecyclerView child contains the post/action row in
                        // the observed layout. Later children are volatile comments and payload;
                        // do not traverse them for page identity.
                        val childRange = if (isDynamicDetailList) 0 until minOf(childCount, 1) else 0 until childCount
                        for (i in childRange) {
                            if (System.nanoTime() >= deadline || visited + queue.size >= config.pageFingerprintMaxNodes) {
                                truncated = true
                                break
                            }
                            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                            queue.add(Triple(child, path + i, ancestors + listOf(id).filter(String::isNotBlank)))
                        }
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            if (queue.isNotEmpty() || System.nanoTime() >= deadline || visited >= config.pageFingerprintMaxNodes) {
                truncated = true
            }
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }
        return PageNodeScan(facts, truncated)
    }

    private fun PageNodeFact.isInside(id: String): Boolean = ancestors.any { it == id }
    private fun List<Int>.isDescendantOf(ancestor: List<Int>): Boolean =
        size > ancestor.size && take(ancestor.size) == ancestor

    fun hasMomoHomePageNavigationFingerprint(root: AccessibilityNodeInfo): Boolean {
        val scan = scanPageFacts(root)
        if (scan.truncated) return false
        val nodes = scan.nodes.filter { it.visible }
        fun id(name: String) = nodes.filter { it.id == name }
        val home = id("home_page_layout").singleOrNull() ?: return false
        val appbar = id("appbar_id").singleOrNull { it.path.isDescendantOf(home.path) } ?: return false
        val tabs = id("tablayout_id").singleOrNull { it.path.isDescendantOf(appbar.path) } ?: return false
        val bottom = id("maintab_bottom_container").singleOrNull() ?: return false
        val widget = id("tabwidget").singleOrNull { it.path.isDescendantOf(bottom.path) } ?: return false
        val nearby = id("maintab_layout_nearby").singleOrNull {
            it.path.size == widget.path.size + 1 && it.path.isDescendantOf(widget.path) && it.selected
        }
        return tabs.path.isNotEmpty() && nearby != null
    }

    /**
     * Dynamic feed has independent, page-local identification routes. The top-tab route survives
     * when publish controls are transiently hidden; the publish/list/home-nav route survives when
     * a toast or new-message banner obscures the Dynamic tab label. A known different selected
     * tab is not overridden by the fallback route (that is a real navigation signal, not occlusion).
     */
    private fun dynamicFeedFingerprintPath(root: AccessibilityNodeInfo): String? {
        val scan = scanPageFacts(root, pruneDynamicContentLists = true)
        if (scan.truncated) return null
        val nodes = scan.nodes.filter { it.visible }
        fun id(name: String) = nodes.filter { it.id == name }
        val home = id("home_page_layout").singleOrNull() ?: return null
        val appbar = id("appbar_id").singleOrNull { it.path.isDescendantOf(home.path) } ?: return null
        val tabs = id("tablayout_id").singleOrNull { it.path.isDescendantOf(appbar.path) }
        val pager = id("pagertabcontent").singleOrNull { it.path.isDescendantOf(home.path) }
        val feedList = pager != null && nodes.any {
            it.className == "RecyclerView" && it.scrollable && it.path.isDescendantOf(pager.path) &&
                it.bounds.width() >= context.resources.displayMetrics.widthPixels * config.minListWidthRatio &&
                it.bounds.height() >= context.resources.displayMetrics.heightPixels * config.minListHeightRatio
        }

        val dynamicTab = tabs?.let { tabContainer ->
            nodes.singleOrNull {
                it.id == "tab_title" && it.className == "TextView" && it.text == config.dynamicTabLabel &&
                    it.path.isDescendantOf(tabContainer.path)
            }
        }
        val selectedOtherTab = tabs?.let { tabContainer ->
            nodes.any {
                it.id == "tab_title" && it.className == "TextView" && it.selected &&
                    it.text in setOf(config.nearbyTabLabel, config.aiTabLabel) &&
                    it.path.isDescendantOf(tabContainer.path)
            }
        } == true
        val topTabPath = dynamicTab?.selected == true

        val publish = id("btn_publish").singleOrNull {
            it.clickable && it.path.isDescendantOf(appbar.path)
        }
        val publishLabel = publish?.let { button ->
            nodes.any { it.id == "tv_publish" && it.className == "TextView" && it.path.isDescendantOf(button.path) }
        } == true
        val publishShell = publish != null && publishLabel &&
            publish.path.isDescendantOf(appbar.path) && appbar.path.isDescendantOf(home.path)
        val paths = buildList {
            // Primary: selected Dynamic tab plus the actual feed RecyclerView under the same Pager.
            add(MomoFingerprintPath("DYNAMIC_TAB_AND_FEED", listOf(tabs != null, topTabPath, feedList)))
            // Backup: a page-local publish control in Home's appbar. This path intentionally does
            // not also require the tab label, list visibility or bottom nav; those may be covered.
            // A positively observed Nearby/AI tab selection still vetoes this fallback.
            add(MomoFingerprintPath("HOME_APPBAR_PUBLISH_ENTRY", listOf(publishShell, !selectedOtherTab)))
        }
        return MomoAlternativeFingerprintPolicy.firstMatch(paths)
    }

    private data class DynamicDetailAnchors(
        val authorAvatarEntry: Boolean,
        val followAction: Boolean,
        val overflowAction: Boolean,
        val fixedBottomCommentAction: Boolean,
        val mainContentList: Boolean,
        val headerControlsShareShell: Boolean,
        val commentActionOutsideContentList: Boolean,
        val signature: String
    ) {
        val complete: Boolean
            get() = MomoDynamicDetailFingerprintPolicy.isConfirmed(
                MomoDynamicDetailFingerprintSignals(
                    packageMatches = true,
                    snapshotComplete = true,
                    authorAvatarEntry = authorAvatarEntry,
                    followAction = followAction,
                    overflowAction = overflowAction,
                    mainContentList = mainContentList,
                    fixedBottomCommentAction = fixedBottomCommentAction,
                    headerControlsShareShell = headerControlsShareShell,
                    commentActionOutsideContentList = commentActionOutsideContentList
                )
            )
    }

    /**
     * Dynamic-detail identity is the four stable controls shared by both empty-comment and
     * populated-comment screenshots: author/avatar entry, Follow, overflow, and the independent
     * fixed bottom comment launcher. The three header controls must share one compact header shell;
     * the bottom launcher must be outside the large dynamic-content RecyclerView. Post text,
     * image count, like/comment count, comment rows, and empty-comment placeholders are excluded.
     */
    private fun inspectDynamicDetailAnchors(root: AccessibilityNodeInfo): Pair<DynamicDetailAnchors?, Boolean> {
        val scan = scanPageFacts(root, pruneDynamicDetailPayload = true)
        if (scan.truncated) return null to true
        val nodes = scan.nodes.filter { it.visible }
        val width = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        fun PageNodeFact.inBand(xMin: Float, xMax: Float, yMin: Float, yMax: Float): Boolean {
            val cx = bounds.centerX().toFloat() / width
            val cy = bounds.centerY().toFloat() / height
            return cx in xMin..xMax && cy in yMin..yMax
        }
        fun PageNodeFact.isDescendantOf(ancestor: PageNodeFact): Boolean =
            path.size > ancestor.path.size && path.take(ancestor.path.size) == ancestor.path
        fun PageNodeFact.containsDescendant(
            predicate: (PageNodeFact) -> Boolean
        ): Boolean = nodes.any { it.isDescendantOf(this) && predicate(it) }

        // The author avatar is a clickable profile entry containing its image node; the image itself
        // may also be clickable on the observed Momo build, so its own click flag is not identity.
        val avatarEntries = nodes.filter { container ->
            container.className in setOf("FrameLayout", "ViewGroup") && container.clickable &&
                container.inBand(.09f, .32f, .02f, .15f) &&
                container.containsDescendant { it.className == "ImageView" &&
                    container.bounds.contains(it.bounds) && it.inBand(.10f, .30f, .025f, .14f) }
        }
        // The label may switch between 关注/已关注. Identify its stable clickable slot, not its text.
        val followActions = nodes.filter {
            it.className == "TextView" && it.clickable && it.inBand(.62f, .85f, .025f, .13f)
        }
        val overflowActions = nodes.filter {
            it.className == "ImageView" && it.clickable && it.inBand(.84f, 1f, .025f, .13f)
        }

        // One compact full-width FrameLayout must own all three top controls. A username label or
        // back button is intentionally not required because neither is part of the four-point set.
        val headerShellCandidates = nodes.filter { candidate ->
            candidate.className == "FrameLayout" &&
                candidate.bounds.width() >= width * .90f &&
                candidate.bounds.height() in (height * .045f).toInt()..(height * .12f).toInt() &&
                candidate.bounds.top <= height * .08f &&
                avatarEntries.any { it.isDescendantOf(candidate) } &&
                followActions.any { it.isDescendantOf(candidate) } &&
                overflowActions.any { it.isDescendantOf(candidate) }
        }
        val headerShell = headerShellCandidates.maxByOrNull { it.path.size }
        // Nested wrapper candidates around the same header are expected. Disjoint header shells
        // are not: they make the three controls ambiguous and therefore fail closed.
        val headerShellHierarchyUnambiguous = headerShell != null && headerShellCandidates.all { candidate ->
            candidate.path.take(headerShell.path.size) == headerShell.path ||
                headerShell.path.take(candidate.path.size) == candidate.path
        }
        val authorAvatarEntry = headerShell != null &&
            avatarEntries.count { it.isDescendantOf(headerShell) } == 1
        val followAction = headerShell != null &&
            followActions.count { it.isDescendantOf(headerShell) } == 1
        val overflowAction = headerShell != null &&
            overflowActions.count { it.isDescendantOf(headerShell) } == 1
        val headerControlsShareShell = headerShellHierarchyUnambiguous &&
            authorAvatarEntry && followAction && overflowAction

        // The main dynamic content must be one unambiguous, screen-sized RecyclerView.
        val mainContentCandidates = nodes.filter {
            it.className == "RecyclerView" && it.bounds.width() >= width * .88f &&
                it.bounds.height() >= height * .55f && it.bounds.top < height * .22f
        }
        val mainContent = mainContentCandidates.singleOrNull()
        val mainContentList = mainContent != null

        // The floating comment launcher is a fixed clickable FrameLayout, outside (not inside)
        // the dynamic-content RecyclerView, with an icon and the fixed 评论 launcher label.
        // This stable control label is not comment-state copy, count, or an individual comment.
        val floatingCommentCandidates = if (mainContent == null) emptyList() else nodes.filter { candidate ->
            candidate.className == "FrameLayout" && candidate.clickable &&
                !candidate.isDescendantOf(mainContent) &&
                candidate.bounds.width() in (width * .18f).toInt()..(width * .38f).toInt() &&
                candidate.bounds.height() in (height * .035f).toInt()..(height * .12f).toInt() &&
                candidate.bounds.top >= height * .84f && candidate.bounds.right >= width * .90f &&
                candidate.containsDescendant { it.className == "ImageView" } &&
                candidate.containsDescendant { it.className == "TextView" && it.text == "评论" }
        }
        val floatingCommentAction = floatingCommentCandidates.size == 1
        val commentActionOutsideContentList = floatingCommentAction && mainContent != null &&
            floatingCommentCandidates.single().path.take(mainContent.path.size) != mainContent.path

        val signals = MomoDynamicDetailFingerprintSignals(
            packageMatches = true,
            snapshotComplete = true,
            authorAvatarEntry = authorAvatarEntry,
            followAction = followAction,
            overflowAction = overflowAction,
            mainContentList = mainContentList,
            fixedBottomCommentAction = floatingCommentAction,
            headerControlsShareShell = headerControlsShareShell,
            commentActionOutsideContentList = commentActionOutsideContentList
        )
        val signature = if (MomoDynamicDetailFingerprintPolicy.isConfirmed(signals)) {
            "momo-dynamic-detail-v3"
        } else {
            "momo-dynamic-detail-incomplete"
        }
        return DynamicDetailAnchors(
            authorAvatarEntry = authorAvatarEntry,
            followAction = followAction,
            overflowAction = overflowAction,
            fixedBottomCommentAction = floatingCommentAction,
            mainContentList = mainContentList,
            headerControlsShareShell = headerControlsShareShell,
            commentActionOutsideContentList = commentActionOutsideContentList,
            signature = signature
        ) to false
    }

    fun inspectHomeBottomNav(root: AccessibilityNodeInfo): HomeBottomNavEvidence? {
        val lowerBandTop = (
            context.resources.displayMetrics.heightPixels * config.homeNavLowerBandRatio
        ).toInt()
        val rowTolerance = maxOf(
            dp(config.dynamicTabRowToleranceDp),
            (context.resources.displayMetrics.heightPixels * 0.04f).toInt()
        )
        val labels = ArrayList<TabLabelEvidence>()
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, Boolean, Int>>()
        queue.add(Triple(AccessibilityNodeInfo.obtain(root), false, 0))
        val deadlineNanos = scanDeadlineNanos()
        var visited = 0
        var scanTruncated = false
        try {
            while (queue.isNotEmpty() && visited < config.pageFingerprintMaxNodes && System.nanoTime() < deadlineNanos) {
                val (node, selectedInPath, depth) = queue.removeFirst()
                visited++
                try {
                    val selected = selectedInPath || node.isSelected || node.isChecked
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                    val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                        .getOrDefault("")
                    val label = when {
                        text in config.homeNavLabels -> text
                        description in config.homeNavLabels -> description
                        else -> ""
                    }
                    if (label.isNotEmpty() && node.isVisibleToUser && bounds.width() > 0 &&
                        bounds.height() > 0 && bounds.centerY() >= lowerBandTop
                    ) {
                        labels.add(TabLabelEvidence(label, Rect(bounds), selected))
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    if (depth >= config.maxNodeScanDepth && childCount > 0) scanTruncated = true
                    else for (index in 0 until childCount) {
                        if (System.nanoTime() >= deadlineNanos) { scanTruncated = true; break }
                        if (visited + queue.size >= config.pageFingerprintMaxNodes) { scanTruncated = true; break }
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(Triple(child, selected, depth + 1))
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            if (queue.isNotEmpty() || System.nanoTime() >= deadlineNanos) scanTruncated = true
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }
        if (scanTruncated) return null

        val homeLabels = labels.filter { it.label == config.homeTabLabel }
        for (home in homeLabels) {
            val peerLabels = labels.filter {
                it.label != config.homeTabLabel &&
                    kotlin.math.abs(it.bounds.centerY() - home.bounds.centerY()) <= rowTolerance
            }
            if (peerLabels.isEmpty()) continue
            val selectedLabels = (listOf(home) + peerLabels).filter { it.selected }.map { it.label }.distinct()
            val selectionKnown = selectedLabels.size == 1
            val homeSelected = selectionKnown && selectedLabels.single() == config.homeTabLabel
            return HomeBottomNavEvidence(
                homeBounds = Rect(home.bounds),
                homeSelected = homeSelected,
                selectionKnown = selectionKnown,
                diagnostic = if (selectionKnown) {
                    "底部导航选中项=${selectedLabels.single()}"
                } else {
                    "底部导航存在，但选中状态未能唯一确认"
                }
            )
        }
        return null
    }

    fun isMomoDynamicFeedPage(root: AccessibilityNodeInfo): Boolean =
        inspectMomoFeedReadiness(root).fingerprint != null

    /**
     * Momo's target feed is the top-level Home page's inner "动态" tab.
     * The bottom navigation's "首页" is a different navigation level and must not
     * be used as the feed-tab marker. We identify the top tab row by its visible
     * labels (动态 / 附近 / AI小陌), then validate a visible list below that row.
     */
    fun inspectMomoFeedReadiness(root: AccessibilityNodeInfo): FeedReadinessInspection {
        val deadlineNanos = scanDeadlineNanos()
        val packageName = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        if (packageName != config.targetPackage) {
            return FeedReadinessInspection(null, "当前窗口不是陌陌：$packageName")
        }

        val tabInspection = inspectDynamicTabRow(root, deadlineNanos)
        val tabEvidence = tabInspection.evidence
        if (tabEvidence?.selectionKnown == true && !tabEvidence.dynamicSelected) {
            return FeedReadinessInspection(null, "已确认其他首页标签处于选中状态；不使用动态页备用指纹覆盖真实导航")
        }
        val matchedPath = dynamicFeedFingerprintPath(root)
            ?: return FeedReadinessInspection(
                null,
                "动态页主指纹与同页面备用指纹均未匹配；主路径=${tabInspection.reason}"
            )

        // The primary path uses the tab row's contentTop. When that row is temporarily obscured,
        // use the pager-scoped list as the fallback anchor rather than failing on the missing label.
        val contentTop = tabEvidence?.contentTop ?: 0
        val listNode = findVisibleFeedContainer(
            root = root,
            contentTop = contentTop,
            deadlineNanos = deadlineNanos,
            requirePagerAncestor = true
        ) ?: return FeedReadinessInspection(null, "动态页指纹=$matchedPath，但 Pager 内的可见滚动列表尚未稳定")

        return try {
            val bounds = Rect()
            listNode.getBoundsInScreen(bounds)
            val metrics = context.resources.displayMetrics
            val minWidth = (metrics.widthPixels * config.minListWidthRatio).toInt()
            val minHeight = (metrics.heightPixels * config.minListHeightRatio).toInt()
            val visibleContentTop = maxOf(bounds.top, contentTop)
            val visibleContentHeight = bounds.bottom - visibleContentTop
            if (!listNode.isVisibleToUser || bounds.width() < minWidth || visibleContentHeight < minHeight) {
                return FeedReadinessInspection(null, "动态页指纹=$matchedPath，但列表可见区域尺寸不足")
            }

            val contentBounds = Rect(bounds).apply { top = visibleContentTop }
            var visibleChildren = 0
            val childCount = runCatching { listNode.childCount }.getOrDefault(0)
            for (index in 0 until minOf(childCount, 64)) {
                if (System.nanoTime() >= deadlineNanos) {
                    return FeedReadinessInspection(null, "动态页列表子节点扫描超过时间预算；稍后重新获取快照")
                }
                val child = runCatching { listNode.getChild(index) }.getOrNull() ?: continue
                try {
                    val childBounds = Rect()
                    child.getBoundsInScreen(childBounds)
                    if (child.isVisibleToUser && childBounds.width() > 0 && childBounds.height() > 0 &&
                        Rect.intersects(contentBounds, childBounds)
                    ) visibleChildren++
                } finally { runCatching { child.recycle() } }
            }
            val className = listNode.className?.toString().orEmpty().substringAfterLast('.')
            FeedReadinessInspection(
                PageFingerprint("momo-dynamic-feed-v2", visibleChildren),
                "动态列表通过备用路径=$matchedPath；列表=$className；当前可见项目=$visibleChildren"
            )
        } finally {
            runCatching { listNode.recycle() }
        }
    }

    /**
     * Locate the upper horizontal tab row. Resource IDs are deliberately not required:
     * Momo versions may expose different IDs or omit IDs from accessibility nodes.
     * If another tab is selected, its state and the Dynamic label bounds are both retained
     * so the caller can actively click Dynamic. If selection semantics are unavailable,
     * the same-row navigation labels provide a bounded structural fallback.
     */
    fun inspectDynamicTabRow(
        root: AccessibilityNodeInfo,
        deadlineNanos: Long = scanDeadlineNanos()
    ): DynamicTabInspection {
        val metrics = context.resources.displayMetrics
        val upperBandBottom = (metrics.heightPixels * config.dynamicTabUpperBandRatio).toInt()
        val rowTolerance = maxOf(
            dp(config.dynamicTabRowToleranceDp),
            (metrics.heightPixels * 0.04f).toInt()
        )
        val labels = ArrayList<TabLabelEvidence>()
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, Boolean, Int>>()
        queue.add(Triple(AccessibilityNodeInfo.obtain(root), false, 0))
        var visited = 0
        var scanTruncated = false

        try {
            while (queue.isNotEmpty() && visited < config.pageFingerprintMaxNodes && System.nanoTime() < deadlineNanos) {
                val (node, selectedInPath, depth) = queue.removeFirst()
                visited++
                try {
                    val selected = selectedInPath || node.isSelected || node.isChecked
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                    val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                        .getOrDefault("")
                    val label = when {
                        text in config.dynamicTabLabels -> text
                        description in config.dynamicTabLabels -> description
                        else -> ""
                    }
                    if (label.isNotEmpty() && node.isVisibleToUser && bounds.width() > 0 &&
                        bounds.height() > 0 && bounds.top >= 0 && bounds.centerY() <= upperBandBottom
                    ) {
                        labels.add(TabLabelEvidence(label, Rect(bounds), selected))
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    if (depth >= config.maxNodeScanDepth && childCount > 0) scanTruncated = true
                    else for (index in 0 until childCount) {
                        if (System.nanoTime() >= deadlineNanos) { scanTruncated = true; break }
                        if (visited + queue.size >= config.pageFingerprintMaxNodes) { scanTruncated = true; break }
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(Triple(child, selected, depth + 1))
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            if (queue.isNotEmpty() || System.nanoTime() >= deadlineNanos) scanTruncated = true
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }
        if (scanTruncated) return DynamicTabInspection(null, "节点检索达到时间、深度或节点预算，当前结果不作为有效定位证据")

        val dynamicLabels = labels.filter { it.label == config.dynamicTabLabel }
        val nearbyLabels = labels.filter { it.label == config.nearbyTabLabel }
        val aiLabels = labels.filter { it.label == config.aiTabLabel }
        if (dynamicLabels.isEmpty()) {
            return DynamicTabInspection(null, "屏幕顶部区域没有识别到‘动态’标签")
        }

        var bestRow: List<TabLabelEvidence>? = null
        var bestScore = -1
        for (dynamic in dynamicLabels) {
            val sameRowNearby = nearbyLabels.filter {
                kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) <= rowTolerance &&
                    it.bounds.centerX() > dynamic.bounds.centerX()
            }.minByOrNull { kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) }
            val sameRowAi = aiLabels.filter {
                kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) <= rowTolerance &&
                    it.bounds.centerX() > dynamic.bounds.centerX()
            }.minByOrNull { kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) }

            // The photo shows these three labels in one horizontal row. Requiring at least
            // one neighbor avoids matching unrelated "动态" text elsewhere in the page.
            if (sameRowNearby != null || sameRowAi != null) {
                val row = listOfNotNull(dynamic, sameRowNearby, sameRowAi)
                val score = (if (sameRowNearby != null) 1 else 0) + (if (sameRowAi != null) 1 else 0)
                if (score > bestScore) {
                    bestRow = row
                    bestScore = score
                }
            }
        }

        val row = bestRow ?: return DynamicTabInspection(
            null,
            "识别到顶部‘动态’，但未找到同一行的‘附近’或‘AI小陌’标签，无法确认是首页顶部导航"
        )
        val dynamic = row.first { it.label == config.dynamicTabLabel }
        val selectedLabels = row.filter { it.selected }.map { it.label }.distinct()
        val selectionKnown = selectedLabels.size == 1
        val dynamicSelected = selectionKnown && selectedLabels.single() == config.dynamicTabLabel

        val rowBottom = row.maxOf { it.bounds.bottom }
        val contentTop = rowBottom + dp(config.dynamicTabContentGapDp)
        val selectionDescription = when {
            dynamicSelected -> "顶部‘动态’标签已选中"
            selectionKnown -> "顶部导航当前选中项为‘${selectedLabels.single()}’；可以主动切换到‘动态’"
            selectedLabels.size > 1 -> "Tab选中状态存在祖先节点歧义，按顶部标签行结构识别"
            else -> "无障碍节点未暴露Tab选中状态，按‘动态/附近/AI小陌’同排结构识别"
        }
        val neighborDescription = if (bestScore == 2) "附近与AI小陌同排" else "附近或AI小陌同排"
        return DynamicTabInspection(
            DynamicTabRowEvidence(
                contentTop = contentTop,
                diagnostic = "$selectionDescription，$neighborDescription",
                dynamicBounds = Rect(dynamic.bounds),
                dynamicSelected = dynamicSelected,
                selectionKnown = selectionKnown
            ),
            ""
        )
    }

    /** Finds a visible list-like container below the confirmed top Dynamic-tab row. */
    fun findVisibleFeedContainer(
        root: AccessibilityNodeInfo,
        contentTop: Int,
        deadlineNanos: Long = scanDeadlineNanos(),
        requirePagerAncestor: Boolean = false
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, Int, List<String>>>()
        queue.add(Triple(AccessibilityNodeInfo.obtain(root), 0, emptyList()))
        var scanTruncated = false
        val metrics = context.resources.displayMetrics
        val minWidth = (metrics.widthPixels * config.minListWidthRatio).toInt()
        val minHeight = (metrics.heightPixels * config.minListHeightRatio).toInt()
        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = 0L
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < config.pageFingerprintMaxNodes && System.nanoTime() < deadlineNanos) {
                val (node, depth, ancestors) = queue.removeFirst()
                visited++
                try {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val visibleTop = maxOf(bounds.top, contentTop)
                    val visibleHeight = bounds.bottom - visibleTop
                    val className = node.className?.toString().orEmpty()
                    val isListLike = className.endsWith("RecyclerView") ||
                        className.endsWith("ListView") || className.endsWith("AbsListView") ||
                        className.endsWith("ScrollView") || className.endsWith("NestedScrollView") ||
                        className.endsWith("WebView")
                    val localId = runCatching { node.viewIdResourceName.orEmpty().substringAfterLast('/').substringAfterLast(':') }
                        .getOrDefault("")
                    val pagerScoped = !requirePagerAncestor || "pagertabcontent" in ancestors
                    if (node.isVisibleToUser && isListLike && pagerScoped && bounds.width() >= minWidth &&
                        visibleHeight >= minHeight
                    ) {
                        val area = bounds.width().toLong() * visibleHeight.toLong()
                        if (area > bestArea) {
                            runCatching { bestNode?.recycle() }
                            bestNode = AccessibilityNodeInfo.obtain(node)
                            bestArea = area
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    val isLargeVisibleList = isListLike && node.isVisibleToUser &&
                        bounds.width() >= minWidth && visibleHeight >= minHeight
                    if (depth >= config.maxNodeScanDepth && childCount > 0) scanTruncated = true
                    else if (!isLargeVisibleList) for (index in 0 until childCount) {
                        if (System.nanoTime() >= deadlineNanos) { scanTruncated = true; break }
                        if (visited + queue.size >= config.pageFingerprintMaxNodes) { scanTruncated = true; break }
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(Triple(child, depth + 1, ancestors + listOf(localId).filter(String::isNotBlank)))
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            if (queue.isNotEmpty() || System.nanoTime() >= deadlineNanos) scanTruncated = true
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }
        if (scanTruncated) {
            runCatching { bestNode?.recycle() }
            return null
        }
        return bestNode
    }

    fun countVisibleDirectChildren(container: AccessibilityNodeInfo): Int {
        var visible = 0
        val count = runCatching { container.childCount }.getOrDefault(0)
        for (index in 0 until count) {
            val child = runCatching { container.getChild(index) }.getOrNull() ?: continue
            try {
                if (runCatching { child.isVisibleToUser }.getOrDefault(false)) visible += 1
            } finally {
                runCatching { child.recycle() }
            }
        }
        return visible
    }

    fun pageFingerprint(root: AccessibilityNodeInfo): PageFingerprint {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to 0)
        val structure = ArrayList<String>()
        val deadlineNanos = scanDeadlineNanos()
        var visibleCount = 0
        var visited = 0
        var timeBudgetExceeded = false
        var depthBudgetExceeded = false
        var nodeBudgetExceeded = false
        try {
            while (queue.isNotEmpty() && visited < config.pageFingerprintMaxNodes && System.nanoTime() < deadlineNanos) {
                val (node, depth) = queue.removeFirst()
                visited++
                try {
                    if (node.isVisibleToUser) {
                        visibleCount++
                        val className = node.className?.toString().orEmpty().substringAfterLast('.')
                        val viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
                        val bounds = Rect()
                        runCatching { node.getBoundsInScreen(bounds) }
                        structure.add(
                            "$depth:$className:$viewId:${node.isClickable}:${node.isScrollable}:" +
                                "${bounds.width() / config.fingerprintSizeBucketPx}:${bounds.height() / config.fingerprintSizeBucketPx}"
                        )
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    if (depth >= config.maxNodeScanDepth && childCount > 0) depthBudgetExceeded = true
                    else for (index in 0 until childCount) {
                        if (System.nanoTime() >= deadlineNanos) { timeBudgetExceeded = true; break }
                        if (visited + queue.size >= config.pageFingerprintMaxNodes) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child to depth + 1)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            if (queue.isNotEmpty()) {
                if (System.nanoTime() >= deadlineNanos) timeBudgetExceeded = true
                else if (visited >= config.pageFingerprintMaxNodes) nodeBudgetExceeded = true
            }
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }
        return PageFingerprint(
            signature = "$visibleCount:${structure.joinToString("|").hashCode()}",
            nodeCount = visibleCount,
            truncated = nodeBudgetExceeded || timeBudgetExceeded || depthBudgetExceeded,
            timeBudgetExceeded = timeBudgetExceeded,
            depthBudgetExceeded = depthBudgetExceeded,
            elapsedBudgetMs = config.maxNodeScanDurationMs,
            nodeBudgetExceeded = nodeBudgetExceeded
        )
    }

    /** Structure-based dynamic detail identity. Activity name is diagnostic only. */
    fun inspectCurrentDynamicDetailIdentity(
        root: AccessibilityNodeInfo,
        currentActivity: String = ""
    ): DetailPageIdentityInspection {
        val packageName = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        val feedPage = isMomoDynamicFeedPage(root)
        val (anchors, truncated) = inspectDynamicDetailAnchors(root)
        val complete = anchors?.complete == true
        val signature = anchors?.signature ?: "dynamic-detail:incomplete"
        val fingerprint = PageFingerprint(
            signature = signature,
            nodeCount = anchors?.let { 4 } ?: 0,
            truncated = truncated
        )
        val confirmed = packageName == config.targetPackage && !feedPage && complete && !truncated
        val reason = when {
            packageName != config.targetPackage -> "当前窗口包名不是陌陌：$packageName"
            feedPage -> "当前页面已经识别为动态列表"
            truncated -> "动态详情结构指纹扫描不完整，需要重新获取完整快照"
            complete -> "动态详情结构指纹确认；Activity=${currentActivity.ifBlank { "未知" }}（仅辅助诊断）"
            else -> "动态详情结构指纹不完整：${anchors?.signature ?: "未获取到锚点"}"
        }
        return DetailPageIdentityInspection(feedPage, confirmed, fingerprint, reason)
    }

}
