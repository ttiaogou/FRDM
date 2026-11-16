package com.freedom.adapters.social.momo

import android.graphics.Bitmap
import com.freedom.core.accessibility.api.ScreenRect
import kotlin.math.ceil
import kotlin.math.floor

/** Pure coordinate conversion kept separate for deterministic tests. */
internal object MomoOcrCropGeometry {
    fun crop(
        bitmap: Bitmap,
        rootBounds: ScreenRect,
        bodyBounds: ScreenRect,
        bubbleBounds: ScreenRect?
    ): Bitmap? {
        val mapped = mapToBitmapRect(
            rootBounds = rootBounds,
            screenBounds = bodyBounds,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            paddingPx = DEFAULT_PADDING_PX,
            clampTo = bubbleBounds
        ) ?: return null
        if (mapped.width < MIN_CROP_EDGE_PX || mapped.height < MIN_CROP_EDGE_PX) return null
        return runCatching {
            Bitmap.createBitmap(bitmap, mapped.left, mapped.top, mapped.width, mapped.height)
        }.getOrNull()
    }

    fun screenCropBounds(
        rootBounds: ScreenRect,
        screenBounds: ScreenRect,
        paddingPx: Int = DEFAULT_PADDING_PX,
        clampTo: ScreenRect? = null
    ): ScreenRect? {
        if (rootBounds.isEmpty || screenBounds.isEmpty) return null
        val screenClip = if (clampTo != null && !clampTo.isEmpty) {
            ScreenRect(
                maxOf(rootBounds.left, clampTo.left, screenBounds.left - paddingPx),
                maxOf(rootBounds.top, clampTo.top, screenBounds.top - paddingPx),
                minOf(rootBounds.right, clampTo.right, screenBounds.right + paddingPx),
                minOf(rootBounds.bottom, clampTo.bottom, screenBounds.bottom + paddingPx)
            )
        } else {
            ScreenRect(
                maxOf(rootBounds.left, screenBounds.left - paddingPx),
                maxOf(rootBounds.top, screenBounds.top - paddingPx),
                minOf(rootBounds.right, screenBounds.right + paddingPx),
                minOf(rootBounds.bottom, screenBounds.bottom + paddingPx)
            )
        }
        return screenClip.takeUnless { it.isEmpty }
    }

    fun mapToBitmapRect(
        rootBounds: ScreenRect,
        screenBounds: ScreenRect,
        bitmapWidth: Int,
        bitmapHeight: Int,
        paddingPx: Int = 0,
        clampTo: ScreenRect? = null
    ): ScreenRect? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val screenClip = screenCropBounds(rootBounds, screenBounds, paddingPx, clampTo) ?: return null
        val scaleX = bitmapWidth.toDouble() / rootBounds.width.toDouble()
        val scaleY = bitmapHeight.toDouble() / rootBounds.height.toDouble()
        val left = floor((screenClip.left - rootBounds.left) * scaleX).toInt().coerceIn(0, bitmapWidth)
        val top = floor((screenClip.top - rootBounds.top) * scaleY).toInt().coerceIn(0, bitmapHeight)
        val right = ceil((screenClip.right - rootBounds.left) * scaleX).toInt().coerceIn(0, bitmapWidth)
        val bottom = ceil((screenClip.bottom - rootBounds.top) * scaleY).toInt().coerceIn(0, bitmapHeight)
        if (right <= left || bottom <= top) return null
        return ScreenRect(left, top, right, bottom)
    }

    private const val DEFAULT_PADDING_PX = 8
    private const val MIN_CROP_EDGE_PX = 8
}
