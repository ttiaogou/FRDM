package com.freedom.core.accessibility.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingButtonPositionTest {
    @Test
    fun yFractionIsClampedAndNonFiniteValuesUseTheDefault() {
        assertEquals(0f, FloatingButtonPosition(yFraction = -2f).normalizedYFraction, 0f)
        assertEquals(1f, FloatingButtonPosition(yFraction = 3f).normalizedYFraction, 0f)
        assertEquals(
            FloatingButtonPosition.DEFAULT_Y_FRACTION,
            FloatingButtonPosition(yFraction = Float.NaN).normalizedYFraction,
            0f
        )
    }
}
