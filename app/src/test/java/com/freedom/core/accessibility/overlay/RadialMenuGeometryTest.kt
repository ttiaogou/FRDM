package com.freedom.core.accessibility.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialMenuGeometryTest {
    private val threeAngles = listOf(-90f, 0f, 90f)

    @Test
    fun rightEdgeLauncherPlacesThreeActionsOnTheLeftFacingSemicircle() {
        val positions = RadialMenuGeometry.fanPositions(
            menuWidth = 260, menuHeight = 260, itemSize = 60, radius = 92,
            anchorOnRight = true, anchorCenterY = 130f, anglesDegrees = threeAngles
        )

        assertEquals(3, positions.size)
        assertTrue(positions.all { it.left in 0..200 && it.top in 0..200 })
        assertEquals(positions[0].left, positions[2].left)
        assertEquals(positions[0].top + positions[2].top, 260 - 60)
        assertTrue(positions[0].top < positions[1].top)
        assertTrue(positions[1].top < positions[2].top)
        assertTrue(positions[1].left < positions[0].left)
    }

    @Test
    fun leftEdgeLauncherMirrorsTheSemicircleToItsRight() {
        val rightFan = RadialMenuGeometry.fanPositions(
            menuWidth = 260, menuHeight = 260, itemSize = 60, radius = 92,
            anchorOnRight = true, anchorCenterY = 130f, anglesDegrees = threeAngles
        )
        val leftFan = RadialMenuGeometry.fanPositions(
            menuWidth = 260, menuHeight = 260, itemSize = 60, radius = 92,
            anchorOnRight = false, anchorCenterY = 130f, anglesDegrees = threeAngles
        )

        assertEquals(3, leftFan.size)
        for (index in leftFan.indices) {
            assertEquals(260 - 60 - rightFan[index].left, leftFan[index].left)
            assertEquals(rightFan[index].top, leftFan[index].top)
        }
    }

    @Test
    fun anchorNearDisplayEdgeKeepsEveryActionInsideMenuWindow() {
        val positions = RadialMenuGeometry.fanPositions(
            menuWidth = 260, menuHeight = 260, itemSize = 60, radius = 92,
            anchorOnRight = true, anchorCenterY = 9f, anglesDegrees = threeAngles
        )
        assertEquals(3, positions.size)
        assertTrue(positions.all { it.left >= 0 && it.top >= 0 })
        assertTrue(positions.all { it.left + 60 <= 260 && it.top + 60 <= 260 })
    }
}
