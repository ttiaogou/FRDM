package com.freedom.core.accessibility.overlay

/** Persistable edge placement for the floating launcher, independent of screen pixel size. */
data class FloatingButtonPosition(
    val rightEdge: Boolean = true,
    val yFraction: Float = DEFAULT_Y_FRACTION
) {
    val normalizedYFraction: Float
        get() = yFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: DEFAULT_Y_FRACTION

    companion object {
        const val DEFAULT_Y_FRACTION = 0.42f
    }
}
