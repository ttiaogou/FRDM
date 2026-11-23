package com.freedom.core.accessibility.gesture

import com.freedom.core.accessibility.api.GesturePoint

/** Immutable coordinate envelope shared by the geometry planner and runtime validator. */
data class GestureCoordinateRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(right >= left && bottom >= top)
    }

    fun contains(point: GesturePoint): Boolean =
        point.x.isFinite() && point.y.isFinite() &&
            point.x >= left && point.x <= right && point.y >= top && point.y <= bottom
}
