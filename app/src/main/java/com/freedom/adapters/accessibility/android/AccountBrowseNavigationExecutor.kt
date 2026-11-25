package com.freedom.adapters.accessibility.android

import com.freedom.gesture.api.GestureApi
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext

internal data class CoordinateTapPlan(val request: GestureIntentRequest)

/** Builds only a window-bound coordinate-tap command; navigation state stays Feature-owned. */
internal class AccountBrowseNavigationExecutor(
    private val gesturePolicy: GestureApi,
    private val displayMetrics: () -> com.freedom.core.accessibility.api.DisplayMetricsSnapshot
) {
    fun createCoordinateTap(
        preferredBounds: ScreenRect?,
        clickableBounds: ScreenRect?,
        description: String,
        expectedWindow: WindowRef?,
        feedbackIntent: GestureFeedbackIntent = GestureFeedbackIntent.STANDARD
    ): CoordinateTapPlan? {
        val preferred = preferredBounds?.takeUnless { it.isEmpty } ?: return null
        val clickable = clickableBounds?.takeUnless { it.isEmpty } ?: return null
        val metrics = displayMetrics()
        if (metrics.widthPixels <= 1 || metrics.heightPixels <= 1) return null
        val displayBounds = ScreenRect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if (intersectScreenRects(preferred, clickable, displayBounds) == null) return null
        val sourceWindow = expectedWindow ?: return null
        val executionContext = gesturePolicy.captureContext(
            expectedWindow = sourceWindow,
            expectedCoordinates = metrics.toGestureCoordinateContext()
        ) ?: return null
        return CoordinateTapPlan(
            request = GestureIntentRequest(
                id = "accountbrowse-tap-${System.nanoTime()}",
                intent = GestureIntent.Tap(
                    preferredBounds = preferred,
                    targetBounds = clickable,
                    durationMs = 90L,
                    displayBounds = displayBounds,
                    feedbackIntent = feedbackIntent
                ),
                description = description,
                executionContext = executionContext
            )
        )
    }

    private fun intersectScreenRects(first: ScreenRect, second: ScreenRect, third: ScreenRect): ScreenRect? {
        val intersection = ScreenRect(
            left = maxOf(first.left, second.left, third.left),
            top = maxOf(first.top, second.top, third.top),
            right = minOf(first.right, second.right, third.right),
            bottom = minOf(first.bottom, second.bottom, third.bottom)
        )
        return intersection.takeUnless { it.isEmpty }
    }
}
