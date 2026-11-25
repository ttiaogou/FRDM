package com.freedom.features.accountbrowse

import com.freedom.features.accountbrowse.ports.ImageSequencePosition
import kotlin.random.Random

/** Physical finger direction. The Momo image pager's mapping must be verified on device. */
enum class ImageSwipeDirection {
    RIGHT, // Previous image: current index decreases.
    LEFT   // Next image: current index increases.
}

/** Initial bounded plan only; not authorization to issue any gesture. */
data class ImageBrowsePlan(
    val startPosition: ImageSequencePosition,
    val direction: ImageSwipeDirection,
    val swipeCount: Int
) {
    val maxSwipes: Int
        get() = when (direction) {
            ImageSwipeDirection.RIGHT -> startPosition.current - 1
            ImageSwipeDirection.LEFT -> startPosition.total - startPosition.current
        }

    init {
        require(swipeCount in 1..maxSwipes)
    }

    /** Predicted destination, not evidence that a swipe actually succeeded. */
    val expectedEndIndex: Int
        get() = when (direction) {
            ImageSwipeDirection.RIGHT -> startPosition.current - swipeCount
            ImageSwipeDirection.LEFT -> startPosition.current + swipeCount
        }
}

/** Select once from a validated position; no Android, geometry, scheduling or execution. */
object ImageBrowsePlanningPolicy {
    fun plan(position: ImageSequencePosition, random: Random = Random.Default): ImageBrowsePlan? {
        val right = position.current - 1
        val left = position.total - position.current
        if (right == 0 && left == 0) return null
        val direction = when {
            right > left -> ImageSwipeDirection.RIGHT
            left > right -> ImageSwipeDirection.LEFT
            random.nextBoolean() -> ImageSwipeDirection.RIGHT
            else -> ImageSwipeDirection.LEFT
        }
        val limit = if (direction == ImageSwipeDirection.RIGHT) right else left
        return ImageBrowsePlan(position, direction, random.nextInt(limit) + 1)
    }
}
