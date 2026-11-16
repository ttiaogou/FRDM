package com.freedom.ui.logs

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.query.AccessibilityNodeInspectionIndex
import kotlin.math.abs
import kotlin.math.roundToInt

/** Screen-coordinate map backed only by the detached, bounded node snapshot. */
internal class NodeInspectorBoundsMap(
    activity: Activity,
    private val dp: (Int) -> Int
) : View(activity) {
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        color = 0xFFA5C331.toInt()
    }
    private val ancestorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        color = 0xFF314D3A.toInt()
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
        color = 0xFFD92D20.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9).toFloat()
        color = 0xFF56665B.toInt()
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11).toFloat()
        color = 0xFF7D887E.toInt()
    }
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private var index: AccessibilityNodeInspectionIndex? = null
    private var selectedPath: List<Int>? = null
    private var hiddenPaths: Set<List<Int>> = emptySet()
    private var onNodeSelected: ((AccessibilityNodeSnapshot) -> Unit)? = null
    private var minX = 0
    private var minY = 0
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var downX = 0f
    private var downY = 0f

    init {
        isClickable = true
        setWillNotDraw(false)
    }

    fun setOnNodeSelected(listener: (AccessibilityNodeSnapshot) -> Unit) {
        onNodeSelected = listener
    }

    fun setSelection(
        index: AccessibilityNodeInspectionIndex?,
        path: List<Int>?,
        hiddenPaths: Set<List<Int>> = emptySet()
    ) {
        this.index = index
        selectedPath = path
        this.hiddenPaths = hiddenPaths.toSet()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = index ?: run {
            canvas.drawText("等待节点快照", dp(12).toFloat(), dp(24).toFloat(), hintPaint)
            return
        }
        val nodes = current.visibleBoundedNodes.filterNot { it.path.childIndices in hiddenPaths }
        if (nodes.isEmpty()) {
            canvas.drawText("当前快照没有有效且可见的节点边界", dp(12).toFloat(), dp(24).toFloat(), hintPaint)
            return
        }

        minX = nodes.minOf { it.bounds.left }.coerceAtMost(0)
        minY = nodes.minOf { it.bounds.top }.coerceAtMost(0)
        val maxX = nodes.maxOf { it.bounds.right }
        val maxY = nodes.maxOf { it.bounds.bottom }
        val contentWidth = (maxX - minX).coerceAtLeast(1)
        val contentHeight = (maxY - minY).coerceAtLeast(1)
        val margin = dp(12).toFloat()
        val availableWidth = (width - margin * 2).coerceAtLeast(1f)
        val availableHeight = (height - margin * 2).coerceAtLeast(1f)
        scale = minOf(availableWidth / contentWidth, availableHeight / contentHeight)
        val drawnWidth = contentWidth * scale
        val drawnHeight = contentHeight * scale
        offsetX = (width - drawnWidth) / 2f
        offsetY = (height - drawnHeight) / 2f

        nodes.forEach { drawNode(canvas, it, normalPaint) }
        current.ancestorsOf(selectedPath.orEmpty())
            .filterNot { it.path.childIndices in hiddenPaths }
            .forEach { drawNode(canvas, it, ancestorPaint) }
        selectedPath?.takeIf { it !in hiddenPaths }
            ?.let(current::find)
            ?.let { drawNode(canvas, it, selectedPaint) }

        if (nodes.size <= 80) {
            nodes.forEachIndexed { nodeIndex, node ->
                val b = node.bounds
                val x = offsetX + (b.left - minX) * scale + dp(2)
                val y = offsetY + (b.top - minY) * scale + dp(10)
                canvas.drawText((nodeIndex + 1).toString(), x, y, labelPaint)
            }
        }
    }

    private fun drawNode(canvas: Canvas, node: AccessibilityNodeSnapshot, paint: Paint) {
        val b = node.bounds
        if (b.isEmpty) return
        canvas.drawRect(
            RectF(
                offsetX + (b.left - minX) * scale,
                offsetY + (b.top - minY) * scale,
                offsetX + (b.right - minX) * scale,
                offsetY + (b.bottom - minY) * scale
            ),
            paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop) {
                    val current = index
                    if (current != null && scale > 0f) {
                        val screenX = ((event.x - offsetX) / scale + minX).roundToInt()
                        val screenY = ((event.y - offsetY) / scale + minY).roundToInt()
                        current.visibleBoundedNodes
                            .asSequence()
                            .filterNot { it.path.childIndices in hiddenPaths }
                            .filter { node ->
                                screenX >= node.bounds.left && screenX < node.bounds.right &&
                                    screenY >= node.bounds.top && screenY < node.bounds.bottom
                            }
                            .minWithOrNull(
                                compareBy<AccessibilityNodeSnapshot> { it.bounds.width.toLong() * it.bounds.height.toLong() }
                                    .thenByDescending { it.path.childIndices.size }
                                    .thenByDescending { it.drawingOrder }
                            )?.let { node ->
                                onNodeSelected?.invoke(node)
                                performClick()
                            }
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
