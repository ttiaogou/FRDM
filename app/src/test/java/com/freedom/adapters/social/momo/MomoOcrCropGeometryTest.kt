package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MomoOcrCropGeometryTest {
    @Test
    fun mapsScreenCoordinatesToScaledScreenshotAndClampsToBubble() {
        val mapped = MomoOcrCropGeometry.mapToBitmapRect(
            rootBounds = ScreenRect(0, 0, 720, 1_600),
            screenBounds = ScreenRect(140, 472, 455, 595),
            bitmapWidth = 1_440,
            bitmapHeight = 3_200,
            paddingPx = 8,
            clampTo = ScreenRect(140, 434, 455, 633)
        )

        assertEquals(ScreenRect(280, 928, 910, 1_206), mapped)
    }

    @Test
    fun screenCropBoundsMatchPaddedBubbleClampedOcrCrop() {
        val bounds = MomoOcrCropGeometry.screenCropBounds(
            rootBounds = ScreenRect(0, 0, 720, 1_600),
            screenBounds = ScreenRect(140, 1_277, 268, 1_320),
            paddingPx = 8,
            clampTo = ScreenRect(140, 1_239, 268, 1_358)
        )

        assertEquals(ScreenRect(140, 1_269, 268, 1_328), bounds)
    }

    @Test
    fun returnsNullWhenBodyIsOutsideVisibleRoot() {
        val mapped = MomoOcrCropGeometry.mapToBitmapRect(
            rootBounds = ScreenRect(0, 0, 720, 1_600),
            screenBounds = ScreenRect(100, 1_700, 300, 1_800),
            bitmapWidth = 720,
            bitmapHeight = 1_600
        )

        assertNull(mapped)
    }
}
