package com.freedom.core.accessibility.gesture

import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizontalSwipePolicyTest {
    private fun config(direction: HorizontalDirection) = HorizontalSwipeConfig(
        direction = direction, minX = 20f, maxX = 700f,
        minY = 100f, maxY = 500f, baseY = 300f
    )

    @Test
    fun bothDirectionsProduceBoundedContinuousCubicGeometry() {
        for (direction in HorizontalDirection.values()) {
            val config = config(direction)
            repeat(32) { seed ->
                val stroke = requireNotNull(HorizontalSwipePolicy.createSwipe(config, Random(seed)))
                val start = (stroke.commands.first() as GesturePathCommand.MoveTo).point
                val segments = stroke.commands.drop(1).map { it as GesturePathCommand.CubicTo }
                val end = segments.last().end
                val distance = if (direction == HorizontalDirection.LEFT_TO_RIGHT) {
                    end.x - start.x
                } else start.x - end.x
                assertTrue(distance >= config.minSwipeDistance)
                assertEquals(config.segments, segments.size)
                val points = listOf(start) + segments.flatMap { listOf(it.control1, it.control2, it.end) }
                assertTrue(points.all { point: GesturePoint ->
                    point.x.isFinite() && point.y.isFinite() &&
                        point.x in config.minX..config.maxX && point.y in config.minY..config.maxY
                })
                assertTrue(stroke.durationMs in 120L..1200L)
                assertEquals(0L, stroke.startDelayMs)
                assertFalse(stroke.continuationOfPrevious)
                assertFalse(stroke.willContinue)
            }
        }
    }

    @Test
    fun invalidConfigurationIsRejectedAndSeededGeometryIsRepeatable() {
        val config = config(HorizontalDirection.LEFT_TO_RIGHT)
        assertNull(HorizontalSwipePolicy.createSwipe(config.copy(maxX = 200f), Random(1)))
        assertNull(HorizontalSwipePolicy.createSwipe(config.copy(baseY = Float.NaN), Random(1)))
        assertNull(HorizontalSwipePolicy.createSwipe(config.copy(segments = 0), Random(1)))
        assertNull(HorizontalSwipePolicy.createSwipe(config.copy(minSpeedPxPerMs = 0f), Random(1)))
        val first = requireNotNull(HorizontalSwipePolicy.createSwipe(config, Random(42)))
        assertEquals(first, HorizontalSwipePolicy.createSwipe(config, Random(42)))
        assertTrue(first != HorizontalSwipePolicy.createSwipe(config, Random(43)))
    }
}
