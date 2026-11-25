package com.freedom.adapters.accessibility.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.freedom.core.accessibility.api.GestureCompletion
import com.freedom.core.accessibility.api.GestureDispatchPurpose
import com.freedom.core.accessibility.api.GestureExecutionPort
import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GestureResultListener
import com.freedom.core.accessibility.api.GestureSpec
import com.freedom.core.accessibility.api.GestureStrokeSpec
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.events.InputOriginClassifier
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TargetAppReadinessController
import com.freedom.core.runtime.TargetAppReadinessStatus
import java.util.concurrent.atomic.AtomicLong

/**
 * The single Android GestureExecutionPort implementation. It owns Path-to-platform dispatch,
 * completion/cancellation callbacks, action-ledger registration, and continued-stroke chaining.
 * Readiness and window identity are supplied by the Accessibility Gateway as narrow callbacks.
 */
class AndroidGestureDispatcher(
    private val service: AccessibilityService,
    private val targetPackage: () -> String,
    private val activityClass: () -> String,
    private val inputOriginClassifier: InputOriginClassifier,
    private val targetAppReadinessController: TargetAppReadinessController,
    private val onReadinessDiagnostic: (String) -> Unit = {},
    private val currentWindowGeneration: () -> Long,
    private val activeWindowId: () -> Int,
    private val currentActiveWindowPackage: () -> String,
    private val inspectAutonomousDispatchReadiness: () -> String?
) : GestureExecutionPort {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dispatchActionSequence = AtomicLong(0L)
    private val windowGeneration: Long
        get() = currentWindowGeneration()

    override fun dispatchGesture(spec: GestureSpec, listener: GestureResultListener): GestureSubmission =
        dispatchGesture(spec, listener) {}

    override fun dispatchGesture(
        spec: GestureSpec,
        listener: GestureResultListener,
        onPlatformDispatch: () -> Unit
    ): GestureSubmission = dispatchGesture(spec, listener, { true }, onPlatformDispatch)

    override fun dispatchGesture(
        spec: GestureSpec,
        listener: GestureResultListener,
        canDispatch: () -> Boolean,
        onPlatformDispatch: () -> Unit
    ): GestureSubmission {
        var dispatchTimestampReported = false
        fun reportPlatformDispatch() {
            if (!dispatchTimestampReported) {
                dispatchTimestampReported = true
                runCatching(onPlatformDispatch)
            }
        }

        val strokes = buildList {
            add(
                GestureStrokeSpec(
                    commands = spec.commands,
                    durationMs = spec.durationMs,
                    startDelayMs = spec.startDelayMs,
                    continuationOfPrevious = false,
                    willContinue = spec.willContinue
                )
            )
            addAll(spec.additionalStrokes)
        }
        if (strokes.isEmpty()) return GestureSubmission.REJECTED

        // Convert every path before beginning platform dispatch. A malformed later segment must
        // never leave a finger-down prefix with no valid continuation or release geometry.
        val paths = strokes.map { stroke ->
            AndroidGesturePathFactory.create(stroke.commands) ?: return GestureSubmission.REJECTED
        }
        val hasContinuation = strokes.drop(1).any { it.continuationOfPrevious }
        if (hasContinuation && (!strokes.first().willContinue ||
                strokes.drop(1).any { !it.continuationOfPrevious } || strokes.last().willContinue)
        ) return GestureSubmission.REJECTED

        if (spec.dispatchPurpose == GestureDispatchPurpose.INTERNAL_CALIBRATION) {
            val activePackage = currentActiveWindowPackage()
            if (activePackage != service.packageName) {
                targetAppReadinessController.invalidate(
                    "内部校准派发被拒绝：Freedom 校准页面不在最前台",
                    SystemClock.uptimeMillis()
                )
                return GestureSubmission.REJECTED
            }
        } else {
            val readinessFailure = inspectAutonomousDispatchReadiness()
            if (readinessFailure != null) {
                val readiness = targetAppReadinessController.current()
                if (readiness.status == TargetAppReadinessStatus.STARTING) {
                    runCatching {
                        onReadinessDiagnostic(
                            "自主手势派发暂缓[PAGE_NOT_READY]：保留上层待执行任务；" +
                                "status=${readiness.status}；reason=${readiness.reason}"
                        )
                    }
                    return GestureSubmission.DEFERRED_NOT_READY
                }
                return GestureSubmission.REJECTED
            }
        }

        val actionId = "freedom-dispatch-${SystemClock.uptimeMillis()}-${dispatchActionSequence.incrementAndGet()}"
        inputOriginClassifier.registerDispatchGesture(
            actionId = actionId,
            packageName = targetPackage(),
            activityClass = activityClass(),
            pageGeneration = windowGeneration,
            windowId = activeWindowId(),
            gesture = spec,
            submittedAtUptimeMs = SystemClock.uptimeMillis()
        )

        var terminalDelivered = false
        fun finish(completed: Boolean) {
            if (terminalDelivered) return
            terminalDelivered = true
            inputOriginClassifier.settleDispatchGesture(
                actionId,
                completed = completed,
                settledAtUptimeMs = SystemClock.uptimeMillis()
            )
            listener.onResult(if (completed) GestureCompletion.COMPLETED else GestureCompletion.CANCELLED)
        }

        if (!hasContinuation) {
            val builder = GestureDescription.Builder()
            for ((index, stroke) in strokes.withIndex()) {
                val androidStroke = runCatching {
                    GestureDescription.StrokeDescription(
                        paths[index],
                        stroke.startDelayMs,
                        stroke.durationMs,
                        stroke.willContinue
                    )
                }.getOrNull() ?: run {
                    inputOriginClassifier.rejectDispatchGesture(actionId, "invalid_android_stroke_$index")
                    return GestureSubmission.REJECTED
                }
                if (!runCatching { builder.addStroke(androidStroke) }.isSuccess) {
                    inputOriginClassifier.rejectDispatchGesture(actionId, "android_builder_rejected_stroke_$index")
                    return GestureSubmission.REJECTED
                }
            }
            val description = runCatching { builder.build() }.getOrNull() ?: run {
                inputOriginClassifier.rejectDispatchGesture(actionId, "android_gesture_build_failed")
                return GestureSubmission.REJECTED
            }
            if (!runCatching(canDispatch).getOrDefault(false)) {
                inputOriginClassifier.rejectDispatchGesture(actionId, "dispatch_target_unverified")
                return GestureSubmission.REJECTED
            }
            reportPlatformDispatch()
            val accepted = runCatching {
                service.dispatchGesture(description, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
                    override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
                }, mainHandler)
            }.getOrDefault(false)
            if (!accepted) inputOriginClassifier.rejectDispatchGesture(actionId, "dispatchGesture_not_accepted")
            return if (accepted) GestureSubmission.ACCEPTED else GestureSubmission.REJECTED
        }

        // StrokeDescription.continueStroke() is designed to continue a still-down pointer in
        // the *next* GestureDescription. Each segment is therefore submitted from the previous
        // segment's onCompleted callback. This remains one logical coordinator command, one
        // registered input-origin action and one final listener result; every platform dispatch
        // still passes through this sole Gateway method.
        fun releaseContinuedPointer(
            previousStroke: GestureDescription.StrokeDescription,
            previousStrokeIndex: Int
        ) {
            val endpoint = strokes.getOrNull(previousStrokeIndex)?.commands?.lastOrNull()?.let { command ->
                when (command) {
                    is GesturePathCommand.MoveTo -> command.point
                    is GesturePathCommand.LineTo -> command.point
                    is GesturePathCommand.CubicTo -> command.end
                }
            }
            // A zero-length continuation is a documented stationary touch; with willContinue=false
            // it closes the retained pointer without introducing another spatial movement.
            val releasePath = endpoint?.let {
                AndroidGesturePathFactory.create(listOf(GesturePathCommand.MoveTo(it)))
            }
            if (releasePath == null || !previousStroke.willContinue()) {
                finish(false)
                return
            }
            val releaseAccepted = runCatching {
                val releaseStroke = previousStroke.continueStroke(releasePath, 0L, 1L, false)
                val releaseDescription = GestureDescription.Builder().addStroke(releaseStroke).build()
                service.dispatchGesture(releaseDescription, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = finish(false)
                    override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
                }, mainHandler)
            }.getOrDefault(false)
            if (!releaseAccepted) finish(false)
        }

        fun dispatchSegment(index: Int, previousStroke: GestureDescription.StrokeDescription?): Boolean {
            val stroke = strokes[index]
            val androidStroke = runCatching {
                if (index == 0) {
                    GestureDescription.StrokeDescription(
                        paths[index], stroke.startDelayMs, stroke.durationMs, stroke.willContinue
                    )
                } else {
                    val previous = previousStroke
                        ?: throw IllegalArgumentException("orphan_continuation_stroke_$index")
                    if (!previous.willContinue()) {
                        throw IllegalArgumentException("previous_stroke_did_not_allow_continuation_$index")
                    }
                    // startTime is relative to this new GestureDescription, so zero chains the
                    // next segment immediately at the previous segment's exact endpoint.
                    previous.continueStroke(paths[index], 0L, stroke.durationMs, stroke.willContinue)
                }
            }.getOrNull() ?: run {
                if (previousStroke != null) releaseContinuedPointer(previousStroke, index - 1)
                return false
            }
            val description = runCatching {
                GestureDescription.Builder().addStroke(androidStroke).build()
            }.getOrNull() ?: run {
                if (previousStroke != null) releaseContinuedPointer(previousStroke, index - 1)
                return false
            }
            if (index == 0) {
                if (!runCatching(canDispatch).getOrDefault(false)) return false
                reportPlatformDispatch()
            }
            val accepted = runCatching {
                service.dispatchGesture(description, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (index == strokes.lastIndex) {
                            finish(true)
                        } else {
                            dispatchSegment(index + 1, androidStroke)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
                }, mainHandler)
            }.getOrDefault(false)
            if (!accepted && previousStroke != null) {
                releaseContinuedPointer(previousStroke, index - 1)
            }
            return accepted
        }

        val firstAccepted = dispatchSegment(0, null)
        if (!firstAccepted) {
            inputOriginClassifier.rejectDispatchGesture(actionId, "first_continuous_segment_not_accepted")
            return GestureSubmission.REJECTED
        }
        return GestureSubmission.ACCEPTED
    }
}
