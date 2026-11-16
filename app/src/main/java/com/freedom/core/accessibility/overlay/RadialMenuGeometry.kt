package com.freedom.core.accessibility.overlay

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Platform-neutral radial geometry; actions fan out from the edge-mounted launcher. */
object RadialMenuGeometry {
    data class ItemPosition(val left: Int, val top: Int)

    fun fanPositions(
        menuWidth: Int,
        menuHeight: Int,
        itemSize: Int,
        radius: Int,
        anchorOnRight: Boolean,
        anchorCenterY: Float,
        anglesDegrees: List<Float>
    ): List<ItemPosition> {
        if (menuWidth <= 0 || menuHeight <= 0 || itemSize <= 0 || radius < 0 || anglesDegrees.isEmpty()) {
            return emptyList()
        }
        val anchorX = if (anchorOnRight) menuWidth.toFloat() else 0f
        val safeAnchorY = anchorCenterY.coerceIn(itemSize / 2f, menuHeight - itemSize / 2f)
        val maxLeft = (menuWidth - itemSize).coerceAtLeast(0)
        val maxTop = (menuHeight - itemSize).coerceAtLeast(0)
        return anglesDegrees.map { angleDegrees ->
            val radians = Math.toRadians(angleDegrees.toDouble())
            val horizontal = radius * cos(radians).toFloat()
            val vertical = radius * sin(radians).toFloat()
            val centerX = if (anchorOnRight) anchorX - horizontal else anchorX + horizontal
            ItemPosition(
                left = (centerX - itemSize / 2f).roundToInt().coerceIn(0, maxLeft),
                top = (safeAnchorY + vertical - itemSize / 2f).roundToInt().coerceIn(0, maxTop)
            )
        }
    }
}
