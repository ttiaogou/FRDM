package com.freedom.core.accessibility.gesture

enum class HorizontalDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT
}

/** Independent horizontal geometry policy; all coordinates and speeds use physical pixels. */
data class HorizontalSwipeConfig(
    val direction: HorizontalDirection,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val baseY: Float,
    val verticalJitterRange: Float = 48f,
    val minSwipeDistance: Float = 260f,
    val minSpeedPxPerMs: Float = 0.65f,
    val maxSpeedPxPerMs: Float = 1.75f,
    val minCurveOffset: Float = 18f,
    val maxCurveOffset: Float = 75f,
    val segments: Int = 3,
    val microJitterPx: Float = 3.5f
) {
    fun isValid(): Boolean =
        listOf(
            minX, maxX, minY, maxY, baseY, verticalJitterRange,
            minSwipeDistance, minSpeedPxPerMs, maxSpeedPxPerMs,
            minCurveOffset, maxCurveOffset, microJitterPx
        ).all { it.isFinite() } &&
            minX >= 0f && minY >= 0f &&
            maxX > minX && maxY > minY &&
            baseY in minY..maxY &&
            minSwipeDistance > 0f && maxX - minX >= minSwipeDistance &&
            verticalJitterRange >= 0f &&
            minSpeedPxPerMs > 0f && maxSpeedPxPerMs >= minSpeedPxPerMs &&
            minCurveOffset >= 0f && maxCurveOffset >= minCurveOffset &&
            microJitterPx in 0f..(Float.MAX_VALUE / 4f) &&
            segments in 1..128
}
