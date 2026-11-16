package com.freedom.adapters.social.momo

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.application.ports.GestureSettingsRepository
import com.freedom.adapters.social.momo.FeedGestureTarget
import com.freedom.adapters.social.momo.ScrollGestureProfile
import java.util.ArrayDeque

/** Shared defaults consumed by the Android settings adapter and the Runtime policy provider. */
internal object MomoFeedGesturePolicyDefaults {
    const val DEFAULT_GESTURE_DURATION_MS = 620L
    const val MIN_GESTURE_DURATION_MS = 350L
    const val MAX_GESTURE_DURATION_MS = 1_200L
    const val DEFAULT_CURVE_OFFSET_RATIO = 0.012f
    const val MAX_CURVE_OFFSET_RATIO = 0.04f
    const val ENABLE_MICRO_PERTURBATION = true
    const val MICRO_PERTURBATION_AMPLITUDE_PX = 1.2f
    const val GESTURE_PATH_STEPS = 32
    const val MICRO_PERTURBATION_PATH_STEPS = 100
}

/**
 * Owns feed-list target discovery, readable list snapshots and the current scroll viewport.
 *
 * This component only inspects nodes and resolves semantic bounds. It does not calculate touch
 * coordinates, create physical paths, dispatch gestures, schedule retries, change browse phases,
 * or handle gesture callbacks. Gesture Policy Pipeline owns physical gesture compilation.
 */
internal class MomoFeedGestureController(
    private val context: Context,
    private val pageInspector: MomoPageInspector,
    private val onLog: (String) -> Unit,
    private val config: Config,
    private val gestureSettingsRepository: GestureSettingsRepository
) {
    internal data class Config(
        val minListWidthRatio: Float,
        val minListHeightRatio: Float,
        val defaultGestureDurationMs: Long = MomoFeedGesturePolicyDefaults.DEFAULT_GESTURE_DURATION_MS,
        val minGestureDurationMs: Long = MomoFeedGesturePolicyDefaults.MIN_GESTURE_DURATION_MS,
        val maxGestureDurationMs: Long = MomoFeedGesturePolicyDefaults.MAX_GESTURE_DURATION_MS,
        val defaultCurveOffsetRatio: Float = MomoFeedGesturePolicyDefaults.DEFAULT_CURVE_OFFSET_RATIO,
        val minGestureWidthPx: Int = 220,
        val minGestureHeightPx: Int = 300,
        val minComposerWidthRatio: Float = 0.35f,
        val composerBottomRatio: Float = 0.62f,
        val snapshotMaxNodes: Int = 500,
        val snapshotMaxTextNodes: Int = 8,
        val snapshotTextLength: Int = 36,
        val snapshotMaxLogLength: Int = 520,
        val snapshotTopRatio: Float = 0.18f,
        val snapshotBottomRatio: Float = 0.92f,
        val maxNodesPerScan: Int = 3_000
    )

    fun resolveFeedGestureTarget(root: AccessibilityNodeInfo): FeedGestureTarget? {
        val tabInspection = pageInspector.inspectDynamicTabRow(root)
        val tabEvidence = tabInspection.evidence ?: run {
            onLog("无法取得首次滑动区域：${tabInspection.reason}")
            return null
        }
        val listNode = pageInspector.findVisibleFeedContainer(root, tabEvidence.contentTop) ?: run {
            onLog("无法取得首次滑动区域：动态标签下方没有可见列表容器")
            return null
        }
        return try {
            val rawBounds = Rect()
            listNode.getBoundsInScreen(rawBounds)
            val metrics = context.resources.displayMetrics
            val clipped = Rect(
                maxOf(rawBounds.left, 0),
                maxOf(rawBounds.top, tabEvidence.contentTop, 0),
                minOf(rawBounds.right, metrics.widthPixels),
                minOf(rawBounds.bottom, metrics.heightPixels)
            )
            val className = listNode.className?.toString()?.substringAfterLast('.') ?: "Unknown"
            if (clipped.width() < config.minGestureWidthPx ||
                clipped.height() < config.minGestureHeightPx
            ) {
                onLog(
                    "目标列表有效滑动区域过小：class=$className，" +
                        "原始边界=$rawBounds，有效边界=$clipped"
                )
                null
            } else {
                FeedGestureTarget(clipped, className)
            }
        } catch (error: Exception) {
            onLog("读取动态列表屏幕边界失败：${error.javaClass.simpleName}")
            null
        } finally {
            runCatching { listNode.recycle() }
        }
    }

    fun captureVisibleFeedNodeSnapshot(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        val lines = ArrayList<String>(config.snapshotMaxTextNodes)
        var visited = 0
        val screenHeight = context.resources.displayMetrics.heightPixels
        try {
            while (queue.isNotEmpty() && visited < config.snapshotMaxNodes &&
                lines.size < config.snapshotMaxTextNodes
            ) {
                val node = queue.removeFirst()
                visited++
                try {
                    if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                        val bounds = Rect()
                        runCatching { node.getBoundsInScreen(bounds) }
                        val text = runCatching { node.text?.toString().orEmpty().trim() }
                            .getOrDefault("")
                        val description = runCatching { node.contentDescription?.toString().orEmpty().trim() }
                            .getOrDefault("")
                        val content = sequenceOf(text, description)
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                        if (content.isNotBlank() && bounds.top >= (screenHeight * config.snapshotTopRatio).toInt() &&
                            bounds.top < (screenHeight * config.snapshotBottomRatio).toInt()
                        ) {
                            val nodeClass = node.className?.toString().orEmpty().substringAfterLast('.')
                            lines.add(
                                "$nodeClass:${content.take(config.snapshotTextLength)}@" +
                                    "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                            )
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= config.snapshotMaxNodes ||
                            lines.size >= config.snapshotMaxTextNodes
                        ) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                runCatching { queue.removeFirst().recycle() }
            }
        }
        return lines.joinToString(" | ").take(config.snapshotMaxLogLength)
    }

    fun loadScrollGestureProfile(): ScrollGestureProfile {
        val preferences = gestureSettingsRepository.readBrowseGesturePreferences(
            defaultDurationMs = config.defaultGestureDurationMs,
            defaultCurveOffsetRatio = config.defaultCurveOffsetRatio
        )
        return ScrollGestureProfile(
            durationMs = preferences.durationMs
                .coerceIn(config.minGestureDurationMs, config.maxGestureDurationMs)
        )
    }

    fun findScrollableFeedContainer(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val minimumWidth = (screenWidth * config.minListWidthRatio).toInt()
        val minimumHeight = (screenHeight * config.minListHeightRatio).toInt()
        val minimumComposerWidth = (screenWidth * config.minComposerWidthRatio).toInt()

        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = 0L
        var hasBottomTextEntry = false
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < config.maxNodesPerScan) {
                val node = queue.removeFirst()
                visited++

                try {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }

                    if (node.isVisibleToUser && node.isEditable &&
                        bounds.bottom >= (screenHeight * config.composerBottomRatio).toInt() &&
                        bounds.width() >= minimumComposerWidth
                    ) {
                        hasBottomTextEntry = true
                    }

                    val className = node.className?.toString().orEmpty()
                    val isKnownVerticalList =
                        className.endsWith("RecyclerView") ||
                        className.endsWith("ListView") ||
                        className.endsWith("AbsListView") ||
                        className.endsWith("ScrollView") ||
                        className.endsWith("NestedScrollView") ||
                        className.endsWith("WebView")

                    if (node.isVisibleToUser && node.isScrollable && isKnownVerticalList) {
                        val width = bounds.width()
                        val height = bounds.height()
                        if (width >= minimumWidth && height >= minimumHeight) {
                            val area = width.toLong() * height.toLong()
                            if (area > bestArea) {
                                runCatching { bestNode?.recycle() }
                                bestNode = AccessibilityNodeInfo.obtain(node)
                                bestArea = area
                            }
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= config.maxNodesPerScan) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                runCatching { queue.removeFirst().recycle() }
            }
        }

        if (hasBottomTextEntry) {
            runCatching { bestNode?.recycle() }
            return null
        }
        return bestNode
    }

}
