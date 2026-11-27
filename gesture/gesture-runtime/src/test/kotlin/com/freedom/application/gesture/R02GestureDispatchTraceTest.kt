package com.freedom.application.gesture

import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.api.GestureCompletion
import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.api.GestureResultListener
import com.freedom.core.accessibility.api.GestureSpec
import com.freedom.core.accessibility.api.GestureExecutionPort
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureCommand
import com.freedom.core.accessibility.gesture.GestureCoordinateRegion
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.core.accessibility.gesture.GestureKind
import com.freedom.core.accessibility.gesture.GestureGeometryPolicy
import kotlin.random.Random
import com.freedom.core.accessibility.gesture.GestureVisualizationPort
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.TaskLease
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.UiWriteAuthorization
import com.freedom.core.runtime.UiWriteScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


/** Records actual preview, platform request and completion, with a fixed safe tap fixture. */
class R02GestureDispatchTraceTest {
    @Test
    fun serialTapRequestsPreserveExactPayloadAndCompletionOrder() {
        val effects = mutableListOf<String>()
        val requests = mutableListOf<GestureSpec>()
        val callbacks = mutableListOf<GestureResultListener>()
        val shown = mutableListOf<GestureCommand>()
        val outcomes = mutableListOf<String>()
        val dispatcher = object : GestureExecutionPort {
            override fun dispatchGesture(spec: GestureSpec, listener: GestureResultListener): GestureSubmission {
                requests += spec
                callbacks += listener
                effects += "dispatch"
                return GestureSubmission.ACCEPTED
            }
        }
        val visualizer = object : GestureVisualizationPort {
            override fun show(command: GestureCommand): Boolean {
                shown += command
                effects += "show:${command.id}"
                return true
            }
            override fun onCompleted(command: GestureCommand) = Unit
            override fun onCancelled(command: GestureCommand) = Unit
            override fun onRejected(command: GestureCommand, reason: String) = Unit
            override fun onFailed(command: GestureCommand, reason: String) = Unit
            override fun clear() = Unit
        }
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher, visualizer = visualizer)
        fun tap(id: String, x: Float) = GestureCommand(
            id = id, kind = GestureKind.TAP,
            gesture = GestureSpec(commands = listOf(GesturePathCommand.MoveTo(GesturePoint(x, 1_800f))),
                durationMs = 120L, feedbackIntent = GestureFeedbackIntent.NAVIGATION_ENTRY),
            validationRegion = GestureCoordinateRegion(0f, 0f, 1_079f, 2_399f),
            executionContext = GestureExecutionContext(runGeneration = 0L,
                targetWindow = WindowRef("com.immomo.momo", "FeedActivity", 0L, 13),
                coordinateContext = DisplayMetricsSnapshot(1_080, 2_400, 3f).toGestureCoordinateContext())
        )
        val first = tap("first", 300f)
        val second = tap("second", 320f)
        assertEquals(GestureSubmission.ACCEPTED, coordinator.execute(ApprovedGestureCommand.issueAfterValidation(first)) {
            outcomes += "${it.commandId}:${it.status}"
        })
        assertEquals(GestureSubmission.ACCEPTED, coordinator.execute(ApprovedGestureCommand.issueAfterValidation(second)) {
            outcomes += "${it.commandId}:${it.status}"
        })
        assertEquals(listOf("show:first", "dispatch"), effects)
        assertEquals(listOf(first.gesture), requests)
        callbacks[0].onResult(GestureCompletion.COMPLETED)
        assertEquals(listOf("show:first", "dispatch", "show:second", "dispatch"), effects)
        assertEquals(listOf(first.gesture, second.gesture), requests)
        assertEquals(listOf(first, second), shown)
        callbacks[1].onResult(GestureCompletion.CANCELLED)
        assertEquals(listOf("first:COMPLETED", "second:CANCELLED"), outcomes)
    }
}
