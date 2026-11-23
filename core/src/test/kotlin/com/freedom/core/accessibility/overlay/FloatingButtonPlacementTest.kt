package com.freedom.core.accessibility.overlay

import com.freedom.core.accessibility.overlay.FloatingButtonPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingButtonPlacementTest {
    @Test
    fun initialPositionKeepsAQuarterOfTheButtonOutsideTheSelectedEdge() {
        val screen = FloatingButtonPlacement.Size(1080, 2400)
        val right = FloatingButtonPlacement.initial(
            screen, 200, FloatingButtonPosition(rightEdge = true, yFraction = 0.5f), 80, 100
        )
        val left = FloatingButtonPlacement.initial(
            screen, 200, FloatingButtonPosition(rightEdge = false, yFraction = 0.5f), 80, 100
        )

        assertEquals(1080 - 200 + 50, right.x)
        assertEquals(-50, left.x)
        assertEquals(right.y, left.y)
    }

    @Test
    fun snapSelectsTheNearestEdgeAndClampsVerticalPosition() {
        val screen = FloatingButtonPlacement.Size(1000, 2000)
        val right = FloatingButtonPlacement.snap(screen, 160, 800, -50, 70, 90)
        val left = FloatingButtonPlacement.snap(screen, 160, 50, 5000, 70, 90)

        assertEquals(1000 - 160 + 40, right.x)
        assertEquals(70, right.y)
        assertEquals(-40, left.x)
        assertEquals(2000 - 160 - 90, left.y)
    }

    @Test
    fun persistedYFractionIsIndependentOfScreenHeight() {
        val position = FloatingButtonPlacement.persistedPosition(
            screen = FloatingButtonPlacement.Size(1000, 2000),
            buttonSize = 160,
            point = FloatingButtonPlacement.Point(800, 900),
            topInset = 70,
            bottomInset = 90
        )
        val restored = FloatingButtonPlacement.initial(
            screen = FloatingButtonPlacement.Size(1200, 2400),
            buttonSize = 180,
            position = position,
            topInset = 80,
            bottomInset = 100
        )

        assertTrue(position.rightEdge)
        assertEquals(0.5f, position.normalizedYFraction, 0.01f)
        assertTrue(restored.y in 1050..1150)
    }

    @Test
    fun menuAlwaysOpensTowardTheScreenInteriorAndRemainsOnScreen() {
        val screen = FloatingButtonPlacement.Size(1080, 2400)
        val menu = FloatingButtonPlacement.Size(344, 344)
        val fromRight = FloatingButtonPlacement.menuOrigin(
            screen, FloatingButtonPlacement.Point(900, 900), 100, menu, 80, 90
        )
        val fromLeft = FloatingButtonPlacement.menuOrigin(
            screen, FloatingButtonPlacement.Point(-25, 900), 100, menu, 80, 90
        )

        assertTrue(fromRight.x < 900)
        assertEquals(25, fromLeft.x)
        assertTrue(fromRight.x >= 0 && fromRight.x + menu.width <= screen.width)
        assertTrue(fromRight.y >= 80 && fromRight.y + menu.height <= screen.height - 90)
    }
}
