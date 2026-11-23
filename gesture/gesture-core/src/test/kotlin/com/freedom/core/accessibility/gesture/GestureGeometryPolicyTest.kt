package com.freedom.core.accessibility.gesture

import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.api.ScreenRect
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureGeometryPolicyTest {
    @Test
    fun safeDisplayBoundsReserveConservativeSystemEdgeBands() {
        val safe = GestureGeometryPolicy.safeDisplayBounds(
            displayBounds = ScreenRect(0, 0, 1080, 2400),
            density = 3f
        )

        assertEquals(ScreenRect(72, 72, 1008, 2328), safe)
        assertNull(GestureGeometryPolicy.safeDisplayBounds(ScreenRect(0, 0, 80, 80), 3f))
    }

    @Test
    fun truncatedGaussianOffsetIsCenteredBoundedAndHasNormalDispersion() {
        val random = Random(2_026_111)
        val offsets = List(20_000) {
            GestureGeometryPolicy.sampleTruncatedGaussianOffset(
                sigmaPx = 7f,
                maxAbsOffsetPx = 21f,
                random = random
            ) ?: error("Gaussian sampler unexpectedly exhausted its rejection budget")
        }
        val mean = offsets.average()
        val variance = offsets.map { (it - mean) * (it - mean) }.average()
        val standardDeviation = kotlin.math.sqrt(variance)

        assertTrue(kotlin.math.abs(mean) < 0.25)
        assertTrue(standardDeviation in 6.7..7.1)
        assertTrue(offsets.all { kotlin.math.abs(it) <= 21f })
        assertTrue(offsets.any { it > 14f } && offsets.any { it < -14f })
    }
    @Test
    fun tapPointIsSampledInsideTheSafeIntersectionRatherThanAtTheCenter() {
        val preferred = ScreenRect(100, 200, 300, 280)
        val target = ScreenRect(80, 180, 320, 300)
        val display = ScreenRect(0, 0, 720, 1600)

        val point = GestureGeometryPolicy.sampleTapPoint(
            preferredBounds = preferred,
            targetBounds = target,
            displayBounds = display,
            random = Random(1234)
        )

        assertNotNull(point)
        point!!
        assertTrue(point.x >= 130f && point.x < 270f)
        assertTrue(point.y >= 212f && point.y < 268f)
        assertFalse(point.x == 200f && point.y == 240f)
    }

    @Test
    fun tapPointIsRejectedWhenSafeIntersectionIsEmpty() {
        assertNull(
            GestureGeometryPolicy.sampleTapPoint(
                preferredBounds = ScreenRect(0, 0, 20, 20),
                targetBounds = ScreenRect(30, 30, 40, 40),
                displayBounds = ScreenRect(0, 0, 100, 100),
                random = Random(1)
            )
        )
    }

    @Test
    fun tapStrokeRemainsInsideTheSafeTarget() {
        val bounds = ScreenRect(100, 200, 300, 280)
        val point = GestureGeometryPolicy.sampleTapPoint(bounds, bounds, bounds, Random(42))
        assertNotNull(point)

        val commands = GestureGeometryPolicy.createTapCommands(point!!, bounds, Random(42))
        assertNotNull(commands)
        assertTrue(commands!!.first() is GesturePathCommand.MoveTo)
        assertTrue(commands.last() is GesturePathCommand.LineTo)
        val lineEnd = (commands.last() as GesturePathCommand.LineTo).point
        assertTrue(lineEnd.x >= bounds.left && lineEnd.x < bounds.right)
        assertTrue(lineEnd.y >= bounds.top && lineEnd.y < bounds.bottom)
    }

    @Test
    fun bottomAlignedTapSamplingAlwaysProducesAStrokeInsideTheSameSafeRegion() {
        val bounds = ScreenRect(288, 1486, 432, 1600)
        repeat(1_000) { seed ->
            val point = GestureGeometryPolicy.sampleTapPoint(
                preferredBounds = bounds,
                targetBounds = bounds,
                displayBounds = ScreenRect(0, 0, 720, 1600),
                random = Random(seed)
            )
            assertNotNull("sample seed=$seed", point)
            assertNotNull(
                "stroke builder rejected a point sampled from its own safe region; seed=$seed",
                GestureGeometryPolicy.createTapCommands(point!!, bounds, Random(seed))
            )
        }
    }

    @Test
    fun tapStrokeRejectsPointsOutsideTheInwardSafeRegion() {
        val bounds = ScreenRect(100, 200, 300, 280)
        val nearEdge = GesturePoint(101f, 201f)

        assertNull(GestureGeometryPolicy.createTapCommands(nearEdge, bounds, Random(9)))
    }

    @Test
    fun verticalSwipeIsBoundedAndUsesCubicBezierSegments() {
        val bounds = ScreenRect(20, 100, 700, 1500)
        val plan = GestureGeometryPolicy.createVerticalSwipe(
            bounds,
            GestureGeometryPolicy.VerticalSwipeDirection.UP,
            Random(123)
        )

        assertNotNull(plan)
        plan!!
        assertTrue(plan.commands.size > 2)
        assertTrue(plan.commands[0] is GesturePathCommand.MoveTo)
        assertTrue(plan.commands.drop(1).all { it is GesturePathCommand.CubicTo })
        assertTrue(plan.start.y > plan.end.y)
        assertTrue(plan.start.isWithin(plan.safeBounds))
        assertTrue(plan.end.isWithin(plan.safeBounds))
        assertTrue(kotlin.math.abs(plan.control1.x - plan.start.x) >= 2f)
        assertTrue(kotlin.math.abs(plan.control2.x - plan.end.x) >= 2f)
        listOf(plan.start, plan.control1, plan.control2, plan.end).forEach {
            assertTrue(it.isWithin(plan.safeBounds))
        }
        val commandPoints = plan.commands.flatMap { path ->
            when (path) {
                is GesturePathCommand.MoveTo -> listOf(path.point)
                is GesturePathCommand.LineTo -> listOf(path.point)
                is GesturePathCommand.CubicTo -> listOf(path.control1, path.control2, path.end)
            }
        }
        assertTrue(commandPoints.all { it.isWithin(plan.safeBounds) })
    }

    @Test
    fun verticalSwipeUsesOneContinuousFivePhasePointerWithAccelerationAndDeceleration() {
        val plan = GestureGeometryPolicy.createVerticalSwipe(
            bounds = ScreenRect(0, 0, 1080, 2400),
            direction = GestureGeometryPolicy.VerticalSwipeDirection.UP,
            random = Random(778),
            durationMs = 600L
        ) ?: error("swipe plan was not generated")

        assertEquals(5, plan.strokes.size)
        assertEquals(listOf(90L, 120L, 180L, 120L, 90L), plan.strokes.map { it.durationMs })
        assertEquals(listOf(0L, 90L, 210L, 390L, 510L), plan.strokes.map { it.startDelayMs })
        assertTrue(plan.strokes.first().willContinue)
        assertTrue(plan.strokes.drop(1).all { it.continuationOfPrevious })
        assertTrue(plan.strokes.dropLast(1).all { it.willContinue })
        assertFalse(plan.strokes.last().willContinue)

        for (index in 1 until plan.strokes.size) {
            val previousEnd = (plan.strokes[index - 1].commands.last() as GesturePathCommand.CubicTo).end
            val currentStart = (plan.strokes[index].commands.first() as GesturePathCommand.MoveTo).point
            assertEquals(previousEnd, currentStart)
        }
        assertEquals(600L, plan.strokes.sumOf { it.durationMs })
        val distances = plan.strokes.map { stroke ->
            val start = (stroke.commands.first() as GesturePathCommand.MoveTo).point
            val end = (stroke.commands.last() as GesturePathCommand.CubicTo).end
            kotlin.math.hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble())
        }
        val speeds = distances.indices.map { distances[it] / plan.strokes[it].durationMs }
        assertTrue(speeds[0] < speeds[1] && speeds[1] < speeds[2])
        assertTrue(speeds[2] > speeds[3] && speeds[3] > speeds[4])
    }

    @Test
    fun verticalSwipeDistanceUsesBoundedSevenPointFivePercentVariation() {
        val bounds = ScreenRect(0, 0, 720, 1600)
        val scales = (0 until 500).map { seed ->
            val plan = GestureGeometryPolicy.createVerticalSwipe(
                bounds,
                GestureGeometryPolicy.VerticalSwipeDirection.UP,
                Random(seed)
            ) ?: error("swipe generation failed for seed=$seed")
            val baseDistance = (plan.safeBounds.height - 1) * 0.48f
            kotlin.math.abs(plan.start.y - plan.end.y) / baseDistance
        }

        assertTrue(scales.all { it in 0.925f..1.075f })
        assertTrue(scales.distinct().size > 450)
    }

    @Test
    fun allSwipeStartsUseBoundedGaussianPerturbationAroundViewportBaselines() {
        val bounds = ScreenRect(0, 0, 1080, 2400)
        val starts = (0 until 100).map { seed ->
            GestureGeometryPolicy.createVerticalSwipe(
                bounds,
                GestureGeometryPolicy.VerticalSwipeDirection.UP,
                Random(seed)
            )?.start ?: error("swipe start sampling failed for seed=$seed")
        }

        assertTrue(starts.distinct().size > 95)
        assertTrue(starts.all { it.x in 200f..880f && it.y in 1_600f..2_050f })
    }

    @Test
    fun swipeStartAndEndVaryAcrossPlans() {
        val bounds = ScreenRect(0, 0, 720, 1600)
        val first = GestureGeometryPolicy.createVerticalSwipe(
            bounds,
            GestureGeometryPolicy.VerticalSwipeDirection.UP,
            Random(1)
        )
        val second = GestureGeometryPolicy.createVerticalSwipe(
            bounds,
            GestureGeometryPolicy.VerticalSwipeDirection.UP,
            Random(2)
        )

        assertNotNull(first)
        assertNotNull(second)
        assertFalse(first!!.start == second!!.start)
        assertFalse(first.end == second.end)
    }

    @Test
    fun sampledFeedCurveIsConvertedToCubicSegments() {
        val bounds = ScreenRect(0, 0, 500, 1000)
        val points = listOf(
            GesturePoint(120f, 800f),
            GesturePoint(130f, 650f),
            GesturePoint(145f, 500f),
            GesturePoint(150f, 350f)
        )

        val commands = GestureGeometryPolicy.sampledCurveToBezierCommands(points, bounds)

        assertNotNull(commands)
        assertEquals(points.size, commands!!.size)
        assertTrue(commands.first() is GesturePathCommand.MoveTo)
        assertTrue(commands.drop(1).all { it is GesturePathCommand.CubicTo })
    }
    private fun GesturePoint.isWithin(bounds: ScreenRect): Boolean =
        x >= bounds.left && x <= bounds.right - 1 &&
            y >= bounds.top && y <= bounds.bottom - 1
}
