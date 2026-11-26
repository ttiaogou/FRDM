package com.freedom.core.accessibility.gesture

import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.api.GestureStrokeSpec
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Independent horizontal geometry generator. No Android APIs, dispatch, runtime policy
 * selection, or business state. The caller must supply a current safe coordinate region.
 */
object HorizontalSwipePolicy {
    fun createSwipe(
        config: HorizontalSwipeConfig,
        random: Random = Random.Default
    ): GestureStrokeSpec? {
        if (!config.isValid()) return null

        fun safeX(x: Float) = x.coerceIn(config.minX, config.maxX)
        fun safeY(y: Float) = y.coerceIn(config.minY, config.maxY)

        val startY = safeY(
            config.baseY + random.range(-config.verticalJitterRange, config.verticalJitterRange)
        )
        val endY = safeY(
            startY + random.range(-config.verticalJitterRange * 0.5f, config.verticalJitterRange * 0.5f)
        )
        val (rawStartX, rawEndX) = when (config.direction) {
            HorizontalDirection.LEFT_TO_RIGHT -> {
                val sx = random.range(config.minX, config.maxX - config.minSwipeDistance)
                sx to random.range(sx + config.minSwipeDistance, config.maxX)
            }
            HorizontalDirection.RIGHT_TO_LEFT -> {
                val sx = random.range(config.minX + config.minSwipeDistance, config.maxX)
                sx to random.range(config.minX, sx - config.minSwipeDistance)
            }
        }
        val startX = safeX(rawStartX)
        val endX = safeX(rawEndX)
        val horizontalDistance = when (config.direction) {
            HorizontalDirection.LEFT_TO_RIGHT -> endX - startX
            HorizontalDirection.RIGHT_TO_LEFT -> startX - endX
        }
        if (!horizontalDistance.isFinite() || horizontalDistance < config.minSwipeDistance) return null

        val anchors = ArrayList<GesturePoint>(config.segments + 1)
        anchors += GesturePoint(startX, startY)
        for (i in 1 until config.segments) {
            val t = i.toFloat() / config.segments
            anchors += GesturePoint(
                safeX(lerp(startX, endX, t) + random.range(-config.microJitterPx, config.microJitterPx)),
                safeY(lerp(startY, endY, t) + random.range(-config.microJitterPx * 2f, config.microJitterPx * 2f))
            )
        }
        anchors += GesturePoint(endX, endY)

        val commands = ArrayList<GesturePathCommand>(config.segments + 1)
        commands += GesturePathCommand.MoveTo(anchors.first())
        val baseSign = if (random.nextBoolean()) 1f else -1f
        var totalLength = 0.0

        for (i in 0 until config.segments) {
            val start = anchors[i]
            val end = anchors[i + 1]
            val midX = lerp(start.x, end.x, 0.5f)
            val midY = lerp(start.y, end.y, 0.5f)
            val sign = if (random.nextFloat() < 0.75f) baseSign else -baseSign
            val curveMagnitude = random.range(config.minCurveOffset, config.maxCurveOffset)
            val control = GesturePoint(
                safeX(midX + random.range(-config.microJitterPx * 3f, config.microJitterPx * 3f)),
                safeY(midY + sign * curveMagnitude)
            )
            // Exact quadratic-to-cubic degree elevation, up to floating point rounding.
            // No new random samples and no change to the original quadratic curve family.
            val c1 = elevate(start, control)
            val c2 = elevate(end, control)
            if (listOf(start, control, c1, c2, end).any {
                    !it.x.isFinite() || !it.y.isFinite() ||
                        it.x !in config.minX..config.maxX || it.y !in config.minY..config.maxY
                }) return null
            commands += GesturePathCommand.CubicTo(c1, c2, end)
            totalLength += quadraticLength(start.asDouble(), control.asDouble(), end.asDouble())
        }
        if (!totalLength.isFinite() || totalLength <= 0.0) return null

        val speed = random.range(config.minSpeedPxPerMs, config.maxSpeedPxPerMs)
        if (!speed.isFinite() || speed <= 0f) return null
        val durationMs = (totalLength / speed.toDouble()).toLong().coerceIn(120L, 1200L)

        // One continuous contour, one pointer, no independent Android dispatch.
        return GestureStrokeSpec(
            commands = commands.toList(),
            durationMs = durationMs
        )
    }

    private fun lerp(start: Float, end: Float, t: Float): Float =
        start + (end - start) * t

    private fun Random.range(min: Float, max: Float): Float =
        if (max <= min) min
        else (min.toDouble() + nextFloat().toDouble() * (max.toDouble() - min.toDouble())).toFloat()

    private fun elevate(endpoint: GesturePoint, control: GesturePoint): GesturePoint =
        GesturePoint(
            (endpoint.x.toDouble() + (control.x.toDouble() - endpoint.x.toDouble()) * (2.0 / 3.0)).toFloat(),
            (endpoint.y.toDouble() + (control.y.toDouble() - endpoint.y.toDouble()) * (2.0 / 3.0)).toFloat()
        )

    private data class PointD(val x: Double, val y: Double) {
        fun midpoint(other: PointD) = PointD((x + other.x) * 0.5, (y + other.y) * 0.5)
        fun distance(other: PointD) = hypot(other.x - x, other.y - y)
    }

    private fun GesturePoint.asDouble() = PointD(x.toDouble(), y.toDouble())

    // Platform-neutral arc-length approximation with bounded adaptive subdivision.
    // The chord is a lower bound and the control polygon an upper bound.
    private fun quadraticLength(
        start: PointD,
        control: PointD,
        end: PointD,
        tolerance: Double = 0.01,
        depth: Int = 0
    ): Double {
        val chord = start.distance(end)
        val polygon = start.distance(control) + control.distance(end)
        if (polygon - chord <= tolerance || depth >= 16) return (polygon + chord) * 0.5
        val left = start.midpoint(control)
        val right = control.midpoint(end)
        val middle = left.midpoint(right)
        return quadraticLength(start, left, middle, tolerance * 0.5, depth + 1) +
            quadraticLength(middle, right, end, tolerance * 0.5, depth + 1)
    }
}
