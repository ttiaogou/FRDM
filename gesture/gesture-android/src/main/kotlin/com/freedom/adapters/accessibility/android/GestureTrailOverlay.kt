package com.freedom.adapters.accessibility.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.gesture.GestureCommand
import com.freedom.core.accessibility.gesture.GestureKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/**
 * Non-interactive visualization for the exact coordinate path submitted to dispatchGesture().
 * The visual window is an ACCESSIBILITY_OVERLAY and does not require SYSTEM_ALERT_WINDOW.
 */
object GestureTrailOverlay {
    private const val SWIPE_HOLD_AFTER_COMPLETE_MS = 650L
    private const val CANCEL_HOLD_MS = 420L
    private const val MAIN_THREAD_WAIT_MS = 1_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var trailView: TrailView? = null
    private var attached = false
    private val removeRunnable = Runnable { removeOnMainThread() }

    fun showGesture(context: Context, command: GestureCommand): Boolean = runOnMainSync(false) {
        val renderedStrokes = commandStrokes(command) ?: return@runOnMainSync false
        val view = ensureAttached(context) ?: return@runOnMainSync false
        mainHandler.removeCallbacks(removeRunnable)
        view.showCommand(command, renderedStrokes)
        true
    }

    fun completeGesture(commandId: String) {
        runOnMain {
            val view = trailView ?: return@runOnMain
            if (!view.finishCommand(commandId)) return@runOnMain
            mainHandler.removeCallbacks(removeRunnable)
            mainHandler.postDelayed(removeRunnable, SWIPE_HOLD_AFTER_COMPLETE_MS)
        }
    }

    fun cancelGesture(commandId: String) {
        runOnMain {
            val view = trailView ?: return@runOnMain
            if (!view.cancelCommand(commandId)) return@runOnMain
            mainHandler.removeCallbacks(removeRunnable)
            mainHandler.postDelayed(removeRunnable, CANCEL_HOLD_MS)
        }
    }

    fun rejectGesture(commandId: String, reason: String) {
        showCommandResult(commandId, "未执行：${reason.take(36)}", CANCEL_HOLD_MS)
    }

    fun failGesture(commandId: String, reason: String) {
        showCommandResult(commandId, "执行失败：${reason.take(36)}", CANCEL_HOLD_MS)
    }

    private fun showCommandResult(commandId: String, label: String, holdMs: Long) {
        runOnMain {
            val view = trailView ?: return@runOnMain
            if (!view.showCommandResult(commandId, label)) return@runOnMain
            mainHandler.removeCallbacks(removeRunnable)
            mainHandler.postDelayed(removeRunnable, holdMs)
        }
    }

    fun clear() {
        runOnMain {
            mainHandler.removeCallbacks(removeRunnable)
            removeOnMainThread()
        }
    }

    private fun commandStrokes(command: GestureCommand): List<RenderedStroke>? {
        val specs = buildList {
            add(command.gesture.commands to (command.gesture.startDelayMs to command.gesture.durationMs))
            command.gesture.additionalStrokes.forEach { stroke ->
                add(stroke.commands to (stroke.startDelayMs to stroke.durationMs))
            }
        }
        val rendered = specs.map { (commands, timing) ->
            val path = AndroidGesturePathFactory.create(commands) ?: return null
            RenderedStroke(
                path = path,
                startDelayMs = timing.first,
                durationMs = timing.second,
                startPoint = commands.firstOrNull { it is GesturePathCommand.MoveTo }
                    ?.let { (it as GesturePathCommand.MoveTo).point },
                endPoint = commands.lastOrNull()?.let(::endPointOf)
            )
        }
        return rendered.takeIf { it.isNotEmpty() }
    }

    private fun endPointOf(command: GesturePathCommand): GesturePoint = when (command) {
        is GesturePathCommand.MoveTo -> command.point
        is GesturePathCommand.LineTo -> command.point
        is GesturePathCommand.CubicTo -> command.end
    }

    private fun ensureAttached(context: Context): TrailView? {
        if (attached && trailView?.isAttachedToWindow == true) return trailView
        if (attached) removeOnMainThread()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val view = TrailView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        return try {
            manager.addView(view, params)
            windowManager = manager
            trailView = view
            attached = true
            view
        } catch (_: Exception) {
            windowManager = null
            trailView = null
            attached = false
            null
        }
    }

    private fun removeOnMainThread() {
        val view = trailView
        val manager = windowManager
        val wasAttached = attached
        attached = false
        trailView = null
        windowManager = null
        view?.stopAnimation()
        if (wasAttached && view != null && manager != null) {
            try {
                manager.removeViewImmediate(view)
            } catch (_: IllegalArgumentException) {
                // The system already detached the view.
            } catch (_: WindowManager.BadTokenException) {
                // The overlay token is no longer valid.
            } catch (_: Exception) {
                // A transient WindowManager failure must not interrupt accessibility work.
            }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private fun <T> runOnMainSync(defaultValue: T, action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(action).getOrDefault(defaultValue)
        }
        val latch = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        var result = defaultValue
        val task = Runnable {
            try {
                if (!cancelled.get()) result = runCatching(action).getOrDefault(defaultValue)
            } finally {
                latch.countDown()
            }
        }
        if (!mainHandler.post(task)) return defaultValue
        val completed = runCatching {
            latch.await(MAIN_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!completed) {
            cancelled.set(true)
            mainHandler.removeCallbacks(task)
            return defaultValue
        }
        return result
    }

    private data class RenderedStroke(
        val path: Path,
        val startDelayMs: Long,
        val durationMs: Long,
        val startPoint: GesturePoint?,
        val endPoint: GesturePoint?
    )

    private class TrailView(context: Context) : View(context) {
        private enum class Mode { NONE, COMMAND }

        private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        private val fullPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x6652B8FF
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF32D6FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4.5f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFB74D.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        private val clickRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF7043.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        private val clickFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFF7043.toInt()
            style = Paint.Style.FILL
        }
        private val statusBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6212938.toInt()
            style = Paint.Style.FILL
        }
        private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * density
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        private val progressPath = Path()
        private var mode = Mode.NONE
        private var renderedStrokes: List<RenderedStroke> = emptyList()
        private var commandId: String? = null
        private var commandKind: GestureKind = GestureKind.CUSTOM
        private var commandElapsedMs = 0L
        private var commandTotalDurationMs = 1L
        private var commandAnimator: ValueAnimator? = null
        private var statusLabel = ""

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun showCommand(command: GestureCommand, strokes: List<RenderedStroke>) {
            stopAnimation()
            mode = Mode.COMMAND
            commandId = command.id
            commandKind = command.kind
            renderedStrokes = strokes
            commandElapsedMs = 0L
            commandTotalDurationMs = strokes.maxOf { it.startDelayMs + it.durationMs }.coerceAtLeast(1L)
            val first = strokes.first().startPoint
            val last = strokes.last().endPoint
            statusLabel = buildString {
                append(kindLabel(command.kind))
                if (first != null) {
                    append("  ").append(first.x.toInt()).append(',').append(first.y.toInt())
                }
                if (last != null && last != first) {
                    append(" → ").append(last.x.toInt()).append(',').append(last.y.toInt())
                }
                append("  执行中")
            }
            invalidate()
            commandAnimator = ValueAnimator.ofInt(0, commandTotalDurationMs.toInt()).apply {
                duration = commandTotalDurationMs
                interpolator = LinearInterpolator()
                addUpdateListener {
                    commandElapsedMs = (it.animatedValue as Int).toLong()
                    invalidate()
                }
                start()
            }
        }

        fun finishCommand(id: String): Boolean {
            if (commandId != id || mode != Mode.COMMAND) return false
            commandAnimator?.cancel()
            commandAnimator = null
            commandElapsedMs = commandTotalDurationMs
            statusLabel = statusLabel.replace("执行中", "已完成")
            invalidate()
            return true
        }

        fun cancelCommand(id: String): Boolean {
            if (commandId != id || mode != Mode.COMMAND) return false
            commandAnimator?.cancel()
            commandAnimator = null
            statusLabel = statusLabel.replace("执行中", "已中断")
            invalidate()
            return true
        }

        fun showCommandResult(id: String, label: String): Boolean {
            if (commandId != id || mode != Mode.COMMAND) return false
            commandAnimator?.cancel()
            commandAnimator = null
            statusLabel = label
            invalidate()
            return true
        }

        fun setStatus(label: String) {
            statusLabel = label
            invalidate()
        }

        fun stopAnimation() {
            commandAnimator?.cancel()
            commandAnimator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val screenLocation = IntArray(2)
            getLocationOnScreen(screenLocation)
            canvas.save()
            canvas.translate(-screenLocation[0].toFloat(), -screenLocation[1].toFloat())
            when (mode) {
                Mode.NONE -> Unit
                Mode.COMMAND -> drawCommand(canvas)
            }
            if (statusLabel.isNotBlank()) drawStatus(canvas, statusLabel)
            canvas.restore()
        }

        private fun drawCommand(canvas: Canvas) {
            renderedStrokes.forEach { stroke ->
                val elapsedFromStart = commandElapsedMs - stroke.startDelayMs
                val active = elapsedFromStart >= 0L && elapsedFromStart <= stroke.durationMs
                val localElapsed = elapsedFromStart.coerceIn(0L, stroke.durationMs)
                val fraction = (localElapsed.toFloat() / stroke.durationMs.toFloat()).coerceIn(0f, 1f)
                if (commandKind == GestureKind.TAP || commandKind == GestureKind.DOUBLE_TAP ||
                    commandKind == GestureKind.LONG_PRESS
                ) {
                    drawCommandTap(canvas, stroke, fraction, active)
                    return@forEach
                }
                canvas.drawPath(stroke.path, fullPathPaint)
                val measure = PathMeasure(stroke.path, false)
                val length = measure.length
                val start = FloatArray(2)
                val end = FloatArray(2)
                if (length > 0f && measure.getPosTan(0f, start, null) && measure.getPosTan(length, end, null)) {
                    progressPath.reset()
                    val stopDistance = length * fraction
                    if (stopDistance > 0f) {
                        measure.getSegment(0f, stopDistance, progressPath, true)
                        canvas.drawPath(progressPath, progressPaint)
                    }
                    markerPaint.color = 0xFF69F0AE.toInt()
                    canvas.drawCircle(start[0], start[1], 6f * density, markerPaint)
                    markerPaint.color = 0xFFFF8A65.toInt()
                    canvas.drawCircle(end[0], end[1], 6f * density, markerPaint)
                    if (active) {
                        val current = FloatArray(2)
                        val tangent = FloatArray(2)
                        if (measure.getPosTan(stopDistance, current, tangent)) {
                            markerPaint.color = Color.WHITE
                            canvas.drawCircle(current[0], current[1], 8f * density, markerPaint)
                            markerPaint.color = 0xFF00B8D4.toInt()
                            canvas.drawCircle(current[0], current[1], 5f * density, markerPaint)
                            if (stopDistance > length * 0.05f) drawDirectionArrow(canvas, current, tangent)
                        }
                    }
                } else {
                    val point = stroke.startPoint ?: return@forEach
                    markerPaint.color = 0xFFFF7043.toInt()
                    canvas.drawCircle(point.x, point.y, (7f + 8f * fraction) * density, markerPaint)
                    clickRingPaint.alpha = ((1f - fraction) * 230f).toInt().coerceIn(0, 230)
                    canvas.drawCircle(point.x, point.y, (14f + 20f * fraction) * density, clickRingPaint)
                }
            }
        }

        private fun drawCommandTap(
            canvas: Canvas,
            stroke: RenderedStroke,
            fraction: Float,
            active: Boolean
        ) {
            val point = stroke.startPoint ?: return
            val pulse = if (active) fraction else 1f
            clickRingPaint.alpha = if (active) ((1f - pulse) * 230f).toInt().coerceIn(0, 230) else 100
            canvas.drawCircle(point.x, point.y, (10f + pulse * 22f) * density, clickRingPaint)
            clickFillPaint.alpha = if (active) 225 else 150
            canvas.drawCircle(point.x, point.y, 5.5f * density, clickFillPaint)
            drawCrosshair(canvas, point.x, point.y)
            if (commandKind == GestureKind.LONG_PRESS && active) {
                val radius = (15f + fraction * 10f) * density
                canvas.drawCircle(point.x, point.y, radius, outlinePaint)
            }
        }

        private fun drawDirectionArrow(canvas: Canvas, point: FloatArray, tangent: FloatArray) {
            val magnitude = kotlin.math.sqrt(tangent[0] * tangent[0] + tangent[1] * tangent[1])
            if (magnitude <= 0.001f) return
            val ux = tangent[0] / magnitude
            val uy = tangent[1] / magnitude
            val size = 11f * density
            val backX = point[0] - ux * size
            val backY = point[1] - uy * size
            val wing = 5f * density
            val leftX = backX - uy * wing
            val leftY = backY + ux * wing
            val rightX = backX + uy * wing
            val rightY = backY - ux * wing
            canvas.drawLine(point[0], point[1], leftX, leftY, progressPaint)
            canvas.drawLine(point[0], point[1], rightX, rightY, progressPaint)
        }

        private fun drawCrosshair(canvas: Canvas, x: Float, y: Float) {
            val cross = 13f * density
            clickRingPaint.alpha = 255
            canvas.drawLine(x - cross, y, x - 6f * density, y, clickRingPaint)
            canvas.drawLine(x + 6f * density, y, x + cross, y, clickRingPaint)
            canvas.drawLine(x, y - cross, x, y - 6f * density, clickRingPaint)
            canvas.drawLine(x, y + 6f * density, x, y + cross, clickRingPaint)
        }

        private fun drawStatus(canvas: Canvas, label: String) {
            val paddingX = 12f * density
            val paddingY = 8f * density
            val maxWidth = (width - 24f * density).coerceAtLeast(80f * density)
            val text = ellipsize(label, maxWidth - paddingX * 2f)
            val textWidth = statusTextPaint.measureText(text)
            val boxWidth = (textWidth + paddingX * 2f).coerceAtMost(maxWidth)
            val boxHeight = statusTextPaint.textSize + paddingY * 2f
            val left = 12f * density
            val top = 28f * density
            val rect = RectF(left, top, left + boxWidth, top + boxHeight)
            canvas.drawRoundRect(rect, 9f * density, 9f * density, statusBackgroundPaint)
            canvas.drawText(text, left + paddingX, top + paddingY + statusTextPaint.textSize * 0.82f, statusTextPaint)
        }

        private fun ellipsize(text: String, maxWidth: Float): String {
            if (statusTextPaint.measureText(text) <= maxWidth) return text
            var result = text
            while (result.isNotEmpty() && statusTextPaint.measureText("$result…") > maxWidth) {
                result = result.dropLast(1)
            }
            return "$result…"
        }

        private fun kindLabel(kind: GestureKind): String = when (kind) {
            GestureKind.TAP -> "点击"
            GestureKind.DOUBLE_TAP -> "双击"
            GestureKind.LONG_PRESS -> "长按"
            GestureKind.SWIPE -> "滑动"
            GestureKind.DRAG -> "拖动"
            GestureKind.CUSTOM -> "手势"
        }
    }
}
