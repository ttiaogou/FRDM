package com.freedom.application.gesture

import com.freedom.gesture.api.GestureApi
import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.GestureExecutionPort
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureVisualizationPort
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.accessibility.api.GestureSubmission

/**
 * Narrow handle exposed to the app composition root. It intentionally does not expose the raw
 * command compiler, validator, coordinator, or executor types.
 */
interface GestureRuntimeHandle : GestureApi {
    fun onPageTransition(newPageGeneration: Long, reason: String = "page_or_window_transition")
}

/** Creates one shared Runtime instance for all Business/Adapter GestureApi consumers. */
object GestureRuntimeFactory {
    fun create(
        dispatcher: GestureExecutionPort,
        visualizer: GestureVisualizationPort,
        displayMetrics: () -> DisplayMetricsSnapshot,
        onDiagnostic: (String) -> Unit = {},
        scheduler: TaskScheduler? = null,
        currentPageGeneration: () -> Long = { 0L },
        currentRunGeneration: () -> Long = { 0L },
        isTargetWindowCurrent: (WindowRef) -> Boolean = { window ->
            window.generation == runCatching(currentPageGeneration).getOrDefault(0L)
        },
        currentGestureWindow: () -> WindowRef? = { null },
        isGestureWindowCurrent: (WindowRef) -> Boolean = { expected ->
            currentGestureWindow()?.let {
                it.packageName == expected.packageName && it.generation == expected.generation &&
                    (it.windowId < 0 || expected.windowId < 0 || it.windowId == expected.windowId) &&
                    (it.activityClass.isBlank() || expected.activityClass.isBlank() ||
                        it.activityClass == expected.activityClass)
            } == true
        },
        currentCoordinateContext: (() -> GestureCoordinateContext?)? = null,
        policySettingsProvider: GesturePolicySettingsProvider = DefaultGesturePolicySettingsProvider,
        uptimeClockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
    ): GestureRuntimeHandle {
        val liveCoordinates = currentCoordinateContext ?: {
            runCatching(displayMetrics).getOrNull()?.toGestureCoordinateContext()
        }
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = displayMetrics,
            dispatcher = dispatcher,
            visualizer = visualizer,
            onDiagnostic = onDiagnostic,
            scheduler = scheduler,
            currentPageGeneration = currentPageGeneration,
            currentRunGeneration = currentRunGeneration,
            isTargetWindowCurrent = isTargetWindowCurrent,
            currentCoordinateContext = liveCoordinates,
            uptimeClockMillis = uptimeClockMillis
        )
        val pipeline = GesturePolicyPipeline(
            executor = coordinator,
            displayMetrics = displayMetrics,
            onDiagnostic = onDiagnostic,
            currentRunGeneration = currentRunGeneration,
            currentGestureWindow = currentGestureWindow,
            isGestureWindowCurrent = isGestureWindowCurrent,
            currentCoordinateContext = liveCoordinates,
            policySettingsProvider = policySettingsProvider
        )
        return DefaultGestureRuntimeHandle(coordinator, pipeline)
    }
}

private class DefaultGestureRuntimeHandle(
    private val coordinator: GestureExecutionCoordinator,
    private val pipeline: GesturePolicyPipeline
) : GestureRuntimeHandle {
    override fun captureContext(
        expectedWindow: WindowRef?,
        expectedCoordinates: GestureCoordinateContext?
    ): GestureExecutionContext? = pipeline.captureContext(expectedWindow, expectedCoordinates)

    override fun execute(
        request: GestureIntentRequest,
        listener: GestureExecutionListener
    ): GestureSubmission = pipeline.execute(request, listener)

    override fun cancelActive(commandId: String, reason: String): Boolean =
        pipeline.cancelActive(commandId, reason)

    override fun onPageTransition(newPageGeneration: Long, reason: String) =
        coordinator.onPageTransition(newPageGeneration, reason)
}
