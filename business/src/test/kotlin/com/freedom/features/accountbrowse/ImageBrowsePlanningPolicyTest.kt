package com.freedom.features.accountbrowse

import com.freedom.features.accountbrowse.ports.ImageSequencePosition
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBrowsePlanningPolicyTest {
    @Test
    fun prefersDirectionWithMoreAvailableImages() {
        val examples = listOf(
            Triple(ImageSequencePosition(8, 9), ImageSwipeDirection.RIGHT, 7),
            Triple(ImageSequencePosition(2, 9), ImageSwipeDirection.LEFT, 7),
            Triple(ImageSequencePosition(1, 9), ImageSwipeDirection.LEFT, 8),
            Triple(ImageSequencePosition(9, 9), ImageSwipeDirection.RIGHT, 8),
            Triple(ImageSequencePosition(1, 2), ImageSwipeDirection.LEFT, 1),
            Triple(ImageSequencePosition(2, 2), ImageSwipeDirection.RIGHT, 1)
        )
        examples.forEach { (position, direction, limit) ->
            repeat(64) { seed ->
                val plan = requireNotNull(ImageBrowsePlanningPolicy.plan(position, Random(seed)))
                assertEquals(position, plan.startPosition)
                assertEquals(direction, plan.direction)
                assertEquals(limit, plan.maxSwipes)
                assertTrue(plan.swipeCount in 1..limit)
                assertTrue(plan.expectedEndIndex in 1..position.total)
            }
        }
    }

    @Test
    fun equalCapacityRandomizesDirectionAndCount() {
        val position = ImageSequencePosition(3, 5)
        val plans = (0 until 256).map { seed ->
            requireNotNull(ImageBrowsePlanningPolicy.plan(position, Random(seed)))
        }
        assertEquals(setOf(ImageSwipeDirection.RIGHT, ImageSwipeDirection.LEFT), plans.map { it.direction }.toSet())
        assertEquals(setOf(1, 2), plans.map { it.swipeCount }.toSet())
        assertTrue(plans.all { it.maxSwipes == 2 && it.expectedEndIndex in 1..5 })
        val positionEight = ImageSequencePosition(8, 9)
        val counts = (0 until 256).map { seed ->
            requireNotNull(ImageBrowsePlanningPolicy.plan(positionEight, Random(seed))).swipeCount
        }.toSet()
        assertEquals((1..7).toSet(), counts)
    }

    @Test
    fun singleImageDoesNotProduceAPlan() {
        assertNull(ImageBrowsePlanningPolicy.plan(ImageSequencePosition(1, 1), Random(42)))
    }

    @Test
    fun allPositionsStayWithinSequenceAndSeededPlansAreRepeatable() {
        for (total in 2..24) {
            for (current in 1..total) {
                val position = ImageSequencePosition(current, total)
                repeat(16) { seed ->
                    val plan = requireNotNull(ImageBrowsePlanningPolicy.plan(position, Random(seed)))
                    assertEquals(plan, ImageBrowsePlanningPolicy.plan(position, Random(seed)))
                    assertEquals(maxOf(current - 1, total - current), plan.maxSwipes)
                    assertTrue(plan.swipeCount in 1..plan.maxSwipes)
                    assertTrue(plan.expectedEndIndex in 1..total)
                    val expected = if (plan.direction == ImageSwipeDirection.RIGHT) {
                        current - plan.swipeCount
                    } else current + plan.swipeCount
                    assertEquals(expected, plan.expectedEndIndex)
                }
            }
        }
    }

    @Test
    fun maximumRepresentableSequenceDoesNotOverflow() {
        for (current in listOf(1, Int.MAX_VALUE, 1_073_741_824)) {
            val position = ImageSequencePosition(current, Int.MAX_VALUE)
            repeat(64) { seed ->
                val plan = requireNotNull(ImageBrowsePlanningPolicy.plan(position, Random(seed)))
                assertTrue(plan.swipeCount in 1..plan.maxSwipes)
                assertTrue(plan.expectedEndIndex in 1..Int.MAX_VALUE)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun directPlanConstructionCannotCrossImageBoundary() {
        ImageBrowsePlan(ImageSequencePosition(8, 9), ImageSwipeDirection.RIGHT, 8)
    }
}
