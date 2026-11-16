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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureExecutionCoordinatorTest {
    @Test
    fun visualizationMustBeReadyBeforeDispatch() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events, canShow = false)
        val coordinator = coordinator(dispatcher, visualizer)
        val submission = coordinator.executeApproved(validSwipe(), {})

        assertEquals(GestureSubmission.REJECTED, submission)
        assertEquals(listOf("show"), events)
    }

    @Test
    fun exactCommandIsShownBeforeDispatchAndCompletionIsReported() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = coordinator(dispatcher, visualizer)
        var result: GestureExecutionResult? = null

        val submission = coordinator.executeApproved(validSwipe(), { result = it })

        assertEquals(GestureSubmission.ACCEPTED, submission)
        assertEquals(listOf("show", "dispatch"), events)
        dispatcher.callback?.onResult(GestureCompletion.COMPLETED)
        assertEquals(GestureExecutionStatus.COMPLETED, result?.status)
        assertEquals("completed", visualizer.lastTerminalState)
    }

    @Test
    fun synchronousReadinessDeferralEndsPreviewWithoutDuplicatingCallerResult() {
        val events = mutableListOf<String>()
        var dispatchCount = 0
        var callback: GestureResultListener? = null
        val dispatcher = object : GestureExecutionPort {
            override fun dispatchGesture(spec: GestureSpec, listener: GestureResultListener): GestureSubmission {
                dispatchCount++
                events += "dispatch$dispatchCount"
                return if (dispatchCount == 1) GestureSubmission.DEFERRED_NOT_READY else {
                    callback = listener
                    GestureSubmission.ACCEPTED
                }
            }
        }
        val visualizer = FakeVisualizer(events)
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer
        )
        var terminal: GestureExecutionResult? = null

        assertEquals(
            GestureSubmission.DEFERRED_NOT_READY,
            coordinator.executeApproved(validSwipe(id = "wait-for-ready")) { terminal = it }
        )
        assertEquals(null, terminal)
        assertEquals("rejected", visualizer.lastTerminalState)
        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "fresh-retry")) { terminal = it })
        callback?.onResult(GestureCompletion.COMPLETED)
        assertEquals(GestureExecutionStatus.COMPLETED, terminal?.status)
        assertTrue(events.contains("dispatch1") && events.contains("dispatch2"))
    }

    @Test
    fun delayedReadinessDeferralEndsPreviewAndNotifiesPreviouslyAcceptedCaller() {
        val events = mutableListOf<String>()
        val scheduler = FakeTaskScheduler()
        var dispatchCount = 0
        var retryCallback: GestureResultListener? = null
        val dispatcher = object : GestureExecutionPort {
            override fun dispatchGesture(spec: GestureSpec, listener: GestureResultListener): GestureSubmission {
                dispatchCount++
                events += "dispatch$dispatchCount"
                return if (dispatchCount == 1) GestureSubmission.DEFERRED_NOT_READY else {
                    retryCallback = listener
                    GestureSubmission.ACCEPTED
                }
            }
        }
        val visualizer = FakeVisualizer(events)
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            scheduler = scheduler
        )
        var terminal: GestureExecutionResult? = null

        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "delayed-wait-for-ready", preDispatchDelayMs = 350L)) {
                terminal = it
            }
        )
        assertEquals(null, terminal)
        assertEquals(null, visualizer.lastTerminalState)

        scheduler.runNext()

        assertEquals(GestureExecutionStatus.DEFERRED, terminal?.status)
        assertTrue(terminal?.reason.orEmpty().contains("refresh_context_and_retry"))
        assertEquals("rejected", visualizer.lastTerminalState)
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "delayed-fresh-retry")) {}
        )
        retryCallback?.onResult(GestureCompletion.COMPLETED)
        assertEquals("completed", visualizer.lastTerminalState)
    }

    @Test
    fun cancellationDuringDispatcherReadinessDeferralIsReportedAsCancellation() {
        val events = mutableListOf<String>()
        lateinit var coordinator: GestureExecutionCoordinator
        var dispatchCount = 0
        var activeCallback: GestureResultListener? = null
        val dispatcher = object : GestureExecutionPort {
            override fun dispatchGesture(spec: GestureSpec, listener: GestureResultListener): GestureSubmission {
                dispatchCount++
                events += "dispatch$dispatchCount"
                if (dispatchCount == 1) {
                    assertTrue(coordinator.cancelActive("deferred-cancel", "manual_touch"))
                    return GestureSubmission.DEFERRED_NOT_READY
                }
                activeCallback = listener
                return GestureSubmission.ACCEPTED
            }
        }
        val visualizer = FakeVisualizer(events)
        coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer
        )
        var terminal: GestureExecutionResult? = null

        assertEquals(
            GestureSubmission.DEFERRED_NOT_READY,
            coordinator.executeApproved(validSwipe(id = "deferred-cancel")) { terminal = it }
        )
        assertEquals(GestureExecutionStatus.CANCELLED, terminal?.status)
        assertEquals("cancelled", visualizer.lastTerminalState)
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "after-deferred-cancel")) {}
        )
        activeCallback?.onResult(GestureCompletion.COMPLETED)
        assertEquals(listOf("show", "dispatch1", "show", "dispatch2"), events)
    }

    @Test
    fun preDispatchDelayUsesTheCoordinatorSchedulerAndRetainsTheSerialSlot() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val scheduler = FakeTaskScheduler()
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = FakeVisualizer(events),
            scheduler = scheduler
        )
        var result: GestureExecutionResult? = null

        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "delayed-dispatch", preDispatchDelayMs = 350L)) { result = it }
        )
        assertEquals(listOf("show"), events)
        assertEquals(1, scheduler.pendingCount)
        assertEquals(GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "queued-behind-delay"), {}))
        assertEquals(listOf("show"), events)

        scheduler.runNext()
        assertEquals(listOf("show", "dispatch"), events)
        dispatcher.callbacks.first().onResult(GestureCompletion.COMPLETED)
        assertEquals(GestureExecutionStatus.COMPLETED, result?.status)
        assertEquals(2, dispatcher.callbacks.size)
    }

    @Test
    fun dispatchTimestampIsReportedAfterPreDispatchDelayAtThePlatformBoundary() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val scheduler = FakeTaskScheduler()
        var uptimeNow = 1_000L
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = FakeVisualizer(events),
            scheduler = scheduler,
            uptimeClockMillis = { uptimeNow }
        )
        val listener = object : com.freedom.core.accessibility.gesture.GestureExecutionListener {
            override fun onDispatched(commandId: String, dispatchedAtUptimeMs: Long) {
                events += "dispatched:$commandId:$dispatchedAtUptimeMs"
            }

            override fun onResult(result: GestureExecutionResult) = Unit
        }

        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(
                validSwipe(id = "dispatch-time-origin", preDispatchDelayMs = 479L), listener
            )
        )
        assertEquals(listOf("show"), events)
        uptimeNow = 1_479L
        scheduler.runNext()

        assertEquals(
            listOf("show", "dispatch", "dispatched:dispatch-time-origin:1479"),
            events
        )
    }

    @Test
    fun cancellationBeforeDelayedDispatchCancelsTheScheduledTask() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val scheduler = FakeTaskScheduler()
        val visualizer = FakeVisualizer(events)
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            scheduler = scheduler
        )
        var result: GestureExecutionResult? = null

        assertEquals(GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "cancel-delay", preDispatchDelayMs = 500L)) { result = it })
        assertTrue(coordinator.cancelActive("cancel-delay", "test_cancel"))
        scheduler.runAll()

        assertEquals(emptyList<GestureResultListener>(), dispatcher.callbacks)
        assertEquals(GestureExecutionStatus.CANCELLED, result?.status)
        assertEquals("cancelled", visualizer.lastTerminalState)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun delayedDispatchRevalidatesLiveContextBeforeSubmittingToAndroid() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val scheduler = FakeTaskScheduler()
        var runGeneration = 0L
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = FakeVisualizer(events),
            scheduler = scheduler,
            currentRunGeneration = { runGeneration }
        )
        var result: GestureExecutionResult? = null

        assertEquals(GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "stale-during-delay", preDispatchDelayMs = 250L)) { result = it })
        runGeneration++
        scheduler.runNext()

        assertEquals(listOf("show"), events)
        assertEquals(GestureExecutionStatus.REJECTED, result?.status)
        assertTrue(result?.reason.orEmpty().contains("pre_dispatch_revalidation_failed"))
    }

    @Test
    fun outOfDisplayCoordinatesAreRejectedBeforeVisualizationOrDispatch() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = coordinator(dispatcher, visualizer)
        val invalid = validSwipe(xEnd = 1_200f)
        val submission = coordinator.executeApproved(invalid, {})

        assertEquals(GestureSubmission.REJECTED, submission)
        assertTrue(events.isEmpty())
    }

    @Test
    fun linearSwipeIsRejectedBeforeVisualizationOrDispatch() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = coordinator(dispatcher, visualizer)
        val linear = GestureCommand(
            id = "linear-swipe",
            kind = GestureKind.SWIPE,
            gesture = GestureSpec(
                commands = listOf(
                    GesturePathCommand.MoveTo(GesturePoint(300f, 1_800f)),
                    GesturePathCommand.LineTo(GesturePoint(900f, 500f))
                ),
                durationMs = 420L
            ),
            validationRegion = GestureCoordinateRegion(0f, 0f, 1_079f, 2_399f),
            executionContext = gestureContext(),
            description = "linear swipe must be rejected"
        )

        assertEquals(GestureSubmission.REJECTED, coordinator.executeApproved(linear, {}))
        assertTrue(events.isEmpty())
    }

    @Test
    fun collinearCubicSwipeIsRejectedAsAdegenerateStraightLine() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = coordinator(dispatcher, visualizer)
        val degenerate = GestureCommand(
            id = "collinear-cubic-swipe",
            kind = GestureKind.SWIPE,
            gesture = GestureSpec(
                commands = listOf(
                    GesturePathCommand.MoveTo(GesturePoint(300f, 1_800f)),
                    GesturePathCommand.CubicTo(
                        control1 = GesturePoint(500f, 1_400f),
                        control2 = GesturePoint(700f, 1_000f),
                        end = GesturePoint(900f, 600f)
                    )
                ),
                durationMs = 420L
            ),
            validationRegion = GestureCoordinateRegion(0f, 0f, 1_079f, 2_399f),
            executionContext = gestureContext(),
            description = "collinear cubic must be rejected"
        )

        assertEquals(GestureSubmission.REJECTED, coordinator.executeApproved(degenerate, {}))
        assertTrue(events.isEmpty())
    }

    @Test
    fun dragWithoutBezierSegmentIsRejected() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = coordinator(dispatcher, visualizer)
        val drag = GestureCommand(
            id = "drag-without-bezier",
            kind = GestureKind.DRAG,
            gesture = GestureSpec(
                commands = listOf(GesturePathCommand.MoveTo(GesturePoint(300f, 1_800f))),
                durationMs = 420L
            ),
            validationRegion = GestureCoordinateRegion(0f, 0f, 1_079f, 2_399f),
            executionContext = gestureContext(),
            description = "drag must contain a Bezier segment"
        )

        assertEquals(GestureSubmission.REJECTED, coordinator.executeApproved(drag, {}))
        assertTrue(events.isEmpty())
    }

    @Test
    fun concurrentGesturesAreDispatchedInFifoOrder() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = coordinator(dispatcher, visualizer)
        val results = mutableMapOf<String, GestureExecutionResult>()

        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "first")) { results[it.commandId] = it })
        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "second")) { results[it.commandId] = it })
        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "third")) { results[it.commandId] = it })
        assertEquals(listOf("show", "dispatch"), events)
        assertEquals(listOf("first"), visualizer.shownCommandIds)

        dispatcher.callbacks[0].onResult(GestureCompletion.COMPLETED)
        assertEquals(listOf("first", "second"), visualizer.shownCommandIds)
        assertEquals(listOf("show", "dispatch", "show", "dispatch"), events)

        dispatcher.callbacks[1].onResult(GestureCompletion.COMPLETED)
        assertEquals(listOf("first", "second", "third"), visualizer.shownCommandIds)
        assertEquals(listOf("show", "dispatch", "show", "dispatch", "show", "dispatch"), events)

        dispatcher.callbacks[2].onResult(GestureCompletion.CANCELLED)
        assertEquals(GestureExecutionStatus.COMPLETED, results["first"]?.status)
        assertEquals(GestureExecutionStatus.COMPLETED, results["second"]?.status)
        assertEquals(GestureExecutionStatus.CANCELLED, results["third"]?.status)
    }

    @Test
    fun queuedGestureCanBeCancelledWithoutAffectingActiveGesture() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = coordinator(dispatcher, visualizer)
        var queuedResult: GestureExecutionResult? = null

        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "active"), {}))
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "queued-cancel")) { queuedResult = it }
        )
        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "queued-next"), {}))
        assertTrue(coordinator.cancelActive("queued-cancel", "user_cancelled_pending"))
        assertEquals(GestureExecutionStatus.CANCELLED, queuedResult?.status)
        assertTrue(queuedResult?.reason.orEmpty().contains("cancelled_while_queued"))
        assertEquals(1, dispatcher.callbacks.size)

        dispatcher.callbacks[0].onResult(GestureCompletion.COMPLETED)
        assertEquals(listOf("active", "queued-next"), visualizer.shownCommandIds)
        assertEquals(2, dispatcher.callbacks.size)
    }

    @Test
    fun pendingQueueHasABoundedCapacity() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            maxPendingCommands = 1
        )

        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "active"), {}))
        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "only-pending"), {}))
        assertEquals(GestureSubmission.REJECTED, coordinator.executeApproved(validSwipe(id = "overflow"), {}))
        assertEquals(listOf("show", "dispatch"), events)

        dispatcher.callbacks[0].onResult(GestureCompletion.COMPLETED)
        assertEquals(listOf("active", "only-pending"), visualizer.shownCommandIds)
    }

    @Test
    fun pageTransitionCancelsQueuedCommandsFromTheOldGeneration() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        var pageGeneration = 12L
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            currentPageGeneration = { pageGeneration }
        )
        var queuedResult: GestureExecutionResult? = null

        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "old-active", windowGeneration = 12L), {}))
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "old-queued", windowGeneration = 12L)) { queuedResult = it }
        )
        pageGeneration = 13L
        coordinator.onPageTransition(13L, "queue_page_changed")
        assertEquals(GestureExecutionStatus.CANCELLED, queuedResult?.status)
        assertTrue(queuedResult?.reason.orEmpty().contains("queued_command_invalidated"))
        assertEquals(1, dispatcher.callbacks.size)

        dispatcher.callbacks[0].onResult(GestureCompletion.COMPLETED)
        assertEquals(listOf("old-active"), visualizer.shownCommandIds)
        assertEquals(1, dispatcher.callbacks.size)
    }

    @Test
    fun queuedCommandIsRevalidatedWhenItReachesTheHead() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        var runGeneration = 0L
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            currentRunGeneration = { runGeneration }
        )
        var queuedResult: GestureExecutionResult? = null

        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "run-active"), {}))
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "run-stale")) { queuedResult = it }
        )
        runGeneration = 1L
        dispatcher.callbacks[0].onResult(GestureCompletion.COMPLETED)

        assertEquals(GestureExecutionStatus.REJECTED, queuedResult?.status)
        assertTrue(queuedResult?.reason.orEmpty().contains("run_generation_changed"))
        assertEquals(1, dispatcher.callbacks.size)
        assertEquals(listOf("run-active"), visualizer.shownCommandIds)
    }

    @Test
    fun queuedDispatcherDeferralIsReportedAndTheQueueContinues() {
        val events = mutableListOf<String>()
        val callbacks = mutableListOf<GestureResultListener>()
        var dispatchCount = 0
        val dispatcher = object : GestureExecutionPort {
            override fun dispatchGesture(spec: GestureSpec, listener: GestureResultListener): GestureSubmission {
                dispatchCount++
                events += "dispatch$dispatchCount"
                if (dispatchCount == 2) return GestureSubmission.DEFERRED_NOT_READY
                callbacks += listener
                return GestureSubmission.ACCEPTED
            }
        }
        val visualizer = FakeVisualizer(events)
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer
        )
        var deferredResult: GestureExecutionResult? = null

        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "defer-active"), {}))
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "deferred-queued")) { deferredResult = it }
        )
        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "after-deferred"), {}))

        callbacks[0].onResult(GestureCompletion.COMPLETED)
        assertEquals(GestureExecutionStatus.DEFERRED, deferredResult?.status)
        assertTrue(deferredResult?.reason.orEmpty().contains("refresh_context_and_retry"))
        assertEquals(listOf("defer-active", "deferred-queued", "after-deferred"), visualizer.shownCommandIds)
        assertEquals(2, callbacks.size)
        callbacks[1].onResult(GestureCompletion.COMPLETED)
    }


    @Test
    fun userPreemptionDefersBusinessCancellationUntilPlatformSettles() {
        val diagnostics = mutableListOf<String>()
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            onDiagnostic = diagnostics::add
        )
        var terminal: GestureExecutionResult? = null

        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "manual-preempt")) { terminal = it }
        )
        assertTrue(coordinator.cancelActive("manual-preempt", "manual_click"))
        assertEquals(null, terminal)
        assertEquals(null, visualizer.lastTerminalState)

        // Do not dispatch a new command while Android may still be running the old stroke.
        // The request is admitted to FIFO, but remains undispatched until the old callback drains.
        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "too-early"), {}))
        assertEquals(1, dispatcher.callbacks.size)
        dispatcher.callback?.onResult(GestureCompletion.CANCELLED)
        assertEquals(GestureExecutionStatus.CANCELLED, terminal?.status)
        assertEquals("cancelled", visualizer.lastTerminalState)
        assertEquals(2, dispatcher.callbacks.size)
        dispatcher.callback?.onResult(GestureCompletion.COMPLETED)
        assertTrue(diagnostics.any { it.contains("已登记取消请求") && it.contains("manual-preempt") })
        assertTrue(diagnostics.any {
            it.contains("platformResult=CANCELLED") && it.contains("businessResult=CANCELLED") &&
                it.contains("manual-preempt")
        })
    }


    @Test
    fun pageTransitionCancelsOldPageBusinessActionButWaitsForPlatformCallback() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        var pageGeneration = 12L
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            currentPageGeneration = { pageGeneration }
        )
        var result: GestureExecutionResult? = null

        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "old-page-gesture", windowGeneration = 12L)) { result = it }
        )
        val oldPlatformCallback = dispatcher.callback
        pageGeneration = 13L
        coordinator.onPageTransition(13L, "test_page_changed")

        assertEquals(null, result)
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "new-page-too-early", windowGeneration = 13L), {})
        )

        // Even if Android reports COMPLETED, a gesture submitted on the old page is a
        // cancelled business action. The callback drains the old platform slot, then the
        // first valid command for the new page is dispatched from FIFO.
        oldPlatformCallback?.onResult(GestureCompletion.COMPLETED)
        assertEquals(GestureExecutionStatus.CANCELLED, result?.status)
        assertTrue(result?.reason.orEmpty().contains("test_page_changed"))
        assertTrue(result?.reason.orEmpty().contains("platform_result=COMPLETED"))
        assertEquals(2, dispatcher.callbacks.size)
        dispatcher.callback?.onResult(GestureCompletion.COMPLETED)
    }

    @Test
    fun expectedNavigationPageTransitionPreservesPlatformCompletionSignal() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        var pageGeneration = 21L
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = FakeVisualizer(events),
            currentPageGeneration = { pageGeneration }
        )
        val navigationTap = GestureCommand(
            id = "navigation-entry-tap",
            kind = GestureKind.TAP,
            gesture = GestureSpec(
                commands = listOf(GesturePathCommand.MoveTo(GesturePoint(300f, 1_800f))),
                durationMs = 120L,
                feedbackIntent = GestureFeedbackIntent.NAVIGATION_ENTRY
            ),
            validationRegion = GestureCoordinateRegion(0f, 0f, 1_079f, 2_399f),
            executionContext = gestureContext(windowGeneration = 21L)
        )
        var result: GestureExecutionResult? = null

        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(navigationTap) { result = it })
        val platformCallback = dispatcher.callback
        pageGeneration = 22L
        coordinator.onPageTransition(22L, "expected_navigation_transition")
        assertEquals(null, result)
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "must-wait-for-navigation-callback", windowGeneration = 22L), {})
        )

        platformCallback?.onResult(GestureCompletion.COMPLETED)
        assertEquals(GestureExecutionStatus.COMPLETED, result?.status)
        assertEquals(2, dispatcher.callbacks.size)
        dispatcher.callback?.onResult(GestureCompletion.COMPLETED)
    }

    @Test
    fun pageTransitionDuringVisualizationPreventsDispatchOfStaleCommand() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        var pageGeneration = 4L
        lateinit var coordinator: GestureExecutionCoordinator
        val visualizer = object : GestureVisualizationPort {
            override fun show(command: GestureCommand): Boolean {
                events += "show"
                pageGeneration = 5L
                coordinator.onPageTransition(5L, "page_changed_during_visualization")
                return true
            }

            override fun onCompleted(command: GestureCommand) = Unit
            override fun onCancelled(command: GestureCommand) { events += "cancelled" }
            override fun onRejected(command: GestureCommand, reason: String) = Unit
            override fun onFailed(command: GestureCommand, reason: String) = Unit
            override fun clear() = Unit
        }
        coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            currentPageGeneration = { pageGeneration }
        )
        var result: GestureExecutionResult? = null

        assertEquals(
            GestureSubmission.REJECTED,
            coordinator.executeApproved(validSwipe(id = "stale-before-dispatch", windowGeneration = 4L)) { result = it }
        )
        assertEquals(listOf("show", "cancelled"), events)
        assertEquals(GestureExecutionStatus.CANCELLED, result?.status)
    }

    @Test
    fun listenerCanSubmitNextGestureAfterPlatformTerminalCallback() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = coordinator(dispatcher, visualizer)
        var nextSubmission: GestureSubmission? = null

        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "first")) {
                nextSubmission = coordinator.executeApproved(validSwipe(id = "submitted-from-listener"), {})
            }
        )
        dispatcher.callback?.onResult(GestureCompletion.COMPLETED)

        assertEquals(GestureSubmission.ACCEPTED, nextSubmission)
        assertTrue(events.takeLast(2).containsAll(listOf("show", "dispatch")))
    }

    @Test
    fun cancellationRequestWaitsForPlatformCompletedCallbackAndReportsBothResults() {
        val diagnostics = mutableListOf<String>()
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val visualizer = FakeVisualizer(events)
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            onDiagnostic = diagnostics::add
        )
        var terminal: GestureExecutionResult? = null

        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "platform-completes-after-takeover")) { terminal = it }
        )
        assertTrue(coordinator.cancelActive("platform-completes-after-takeover", "manual_touch"))
        assertEquals(null, terminal)
        assertEquals(null, visualizer.lastTerminalState)
        dispatcher.callback?.onResult(GestureCompletion.COMPLETED)

        assertEquals(GestureExecutionStatus.CANCELLED, terminal?.status)
        assertTrue(terminal?.reason.orEmpty().contains("platform_result=COMPLETED"))
        assertTrue(diagnostics.any {
            it.contains("platformResult=COMPLETED") && it.contains("businessResult=CANCELLED")
        })
    }

    @Test
    fun cancellationDuringVisualizationWaitsForShowToReturnBeforeStartingNextQueuedGesture() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        lateinit var coordinator: GestureExecutionCoordinator
        val visualizer = object : GestureVisualizationPort {
            override fun show(command: GestureCommand): Boolean {
                events += "show-start:${command.id}"
                if (command.id == "visual-first") {
                    assertEquals(
                        GestureSubmission.ACCEPTED,
                        coordinator.executeApproved(validSwipe(id = "visual-second")) {}
                    )
                    assertTrue(coordinator.cancelActive("visual-first", "manual_touch_during_visualization"))
                }
                events += "show-end:${command.id}"
                return true
            }

            override fun onCompleted(command: GestureCommand) { events += "completed:${command.id}" }
            override fun onCancelled(command: GestureCommand) { events += "cancelled:${command.id}" }
            override fun onRejected(command: GestureCommand, reason: String) { events += "rejected:${command.id}" }
            override fun onFailed(command: GestureCommand, reason: String) { events += "failed:${command.id}" }
            override fun clear() = Unit
        }
        coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer
        )
        var firstResult: GestureExecutionResult? = null

        assertEquals(
            GestureSubmission.REJECTED,
            coordinator.executeApproved(validSwipe(id = "visual-first")) { firstResult = it }
        )

        assertEquals(GestureExecutionStatus.CANCELLED, firstResult?.status)
        assertEquals(
            listOf(
                "show-start:visual-first",
                "show-end:visual-first",
                "cancelled:visual-first",
                "show-start:visual-second",
                "show-end:visual-second",
                "dispatch"
            ),
            events
        )
        assertEquals(1, dispatcher.callbacks.size)
        dispatcher.callback?.onResult(GestureCompletion.COMPLETED)
        assertEquals("completed:visual-second", events.last())
    }

    @Test
    fun preemptionDuringVisualizationPreventsPlatformDispatch() {
        val diagnostics = mutableListOf<String>()
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        lateinit var coordinator: GestureExecutionCoordinator
        val visualizer = object : GestureVisualizationPort {
            override fun show(command: GestureCommand): Boolean {
                events += "show"
                assertTrue(coordinator.cancelActive(command.id, "manual_touch_before_dispatch"))
                return true
            }

            override fun onCompleted(command: GestureCommand) = Unit
            override fun onCancelled(command: GestureCommand) { events += "cancelled" }
            override fun onRejected(command: GestureCommand, reason: String) = Unit
            override fun onFailed(command: GestureCommand, reason: String) = Unit
            override fun clear() = Unit
        }
        coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            onDiagnostic = diagnostics::add
        )
        var terminal: GestureExecutionResult? = null

        val submission = coordinator.executeApproved(validSwipe(id = "cancel-before-dispatch")) {
            terminal = it
        }

        assertEquals(GestureSubmission.REJECTED, submission)
        assertEquals(listOf("show", "cancelled"), events)
        assertEquals(GestureExecutionStatus.CANCELLED, terminal?.status)
        assertTrue(diagnostics.any { it.contains("等待当前可视化调用返回后再释放 FIFO 槽") })
    }

    @Test
    fun runGenerationChangeDuringVisualizationPreventsDispatchOfOldRunCommand() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        var runGeneration = 9L
        val visualizer = object : GestureVisualizationPort {
            override fun show(command: GestureCommand): Boolean {
                events += "show"
                runGeneration++
                return true
            }

            override fun onCompleted(command: GestureCommand) = Unit
            override fun onCancelled(command: GestureCommand) { events += "cancelled" }
            override fun onRejected(command: GestureCommand, reason: String) = Unit
            override fun onFailed(command: GestureCommand, reason: String) = Unit
            override fun clear() = Unit
        }
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = visualizer,
            currentRunGeneration = { runGeneration }
        )
        var result: GestureExecutionResult? = null

        assertEquals(
            GestureSubmission.REJECTED,
            coordinator.executeApproved(validSwipe(id = "stale-run", runGeneration = 9L)) { result = it }
        )
        assertEquals(listOf("show", "cancelled"), events)
        assertEquals(GestureExecutionStatus.CANCELLED, result?.status)
        assertTrue(result?.reason.orEmpty().contains("run_generation_changed"))
    }

    @Test
    fun drainWatchdogDoesNotReleaseSerialSlotUntilLatePlatformCallbackArrives() {
        val diagnostics = mutableListOf<String>()
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val scheduler = FakeTaskScheduler()
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = FakeVisualizer(events),
            onDiagnostic = diagnostics::add,
            scheduler = scheduler
        )
        var terminal: GestureExecutionResult? = null
        var terminalCount = 0

        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "drain-timeout")) {
                terminal = it
                terminalCount++
            }
        )
        val lateCallback = dispatcher.callback
        assertTrue(coordinator.cancelActive("drain-timeout", "manual_click"))
        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "blocked-before-timeout"), {}))

        scheduler.runNext()
        assertEquals(GestureExecutionStatus.CANCELLED, terminal?.status)
        assertEquals(1, terminalCount)
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "blocked-after-timeout"), {})
        )
        assertEquals(1, dispatcher.callbacks.size)
        assertTrue(diagnostics.any {
            it.contains("platformResult=UNKNOWN") && it.contains("串行槽保持隔离") && it.contains("drain-timeout")
        })

        // A late platform terminal is the only event that proves the old stroke has drained.
        lateCallback?.onResult(GestureCompletion.COMPLETED)
        assertEquals(1, terminalCount)
        assertTrue(diagnostics.any { it.contains("手势平台迟到终态已确认") && it.contains("drain-timeout") })
        assertEquals(2, dispatcher.callbacks.size)
        dispatcher.callbacks[1].onResult(GestureCompletion.COMPLETED)
        assertEquals(3, dispatcher.callbacks.size)
        dispatcher.callbacks[2].onResult(GestureCompletion.COMPLETED)
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "after-platform-drain"), {})
        )
    }

    @Test
    fun platformCallbackDuringTimeoutNotificationCannotReleaseSlotEarly() {
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val scheduler = FakeTaskScheduler()
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(1_080, 2_400, 3f) },
            dispatcher = dispatcher,
            visualizer = FakeVisualizer(events),
            scheduler = scheduler
        )
        var lateCallback: GestureResultListener? = null
        var submissionDuringTerminal: GestureSubmission? = null

        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "timeout-callback-race")) {
                // Simulate Android's callback arriving while the watchdog is still notifying the
                // business listener. The slot must remain held until this listener returns.
                lateCallback?.onResult(GestureCompletion.COMPLETED)
                submissionDuringTerminal = coordinator.executeApproved(validSwipe(id = "must-wait-for-notification"), {})
            }
        )
        lateCallback = dispatcher.callback
        assertTrue(coordinator.cancelActive("timeout-callback-race", "manual_touch"))

        scheduler.runNext()

        assertEquals(GestureSubmission.ACCEPTED, submissionDuringTerminal)
        assertEquals(2, dispatcher.callbacks.size)
        dispatcher.callbacks[1].onResult(GestureCompletion.COMPLETED)
        assertEquals(
            GestureSubmission.ACCEPTED,
            coordinator.executeApproved(validSwipe(id = "after-timeout-notification-and-platform-drain"), {})
        )
    }

    @Test
    fun cancellationIsReportedWithCommandIdentityAndTerminalStatus() {
        val diagnostics = mutableListOf<String>()
        val events = mutableListOf<String>()
        val dispatcher = FakeDispatcher(events)
        val coordinator = GestureExecutionCoordinator(
            displayMetrics = { DisplayMetricsSnapshot(widthPixels = 1_080, heightPixels = 2_400, density = 3f) },
            dispatcher = dispatcher,
            visualizer = FakeVisualizer(events),
            onDiagnostic = diagnostics::add
        )

        assertEquals(GestureSubmission.ACCEPTED, coordinator.executeApproved(validSwipe(id = "cancel-me"), {}))
        dispatcher.callback?.onResult(GestureCompletion.CANCELLED)

        assertTrue(diagnostics.any { it.contains("手势已预留") && it.contains("cancel-me") })
        assertTrue(diagnostics.any { it.contains("手势结束") && it.contains("cancel-me") && it.contains("CANCELLED") })
    }

    private fun GestureExecutionCoordinator.executeApproved(
        command: GestureCommand,
        listener: com.freedom.core.accessibility.gesture.GestureExecutionListener
    ): GestureSubmission = execute(ApprovedGestureCommand.issueAfterValidation(command), listener)

    private fun coordinator(
        dispatcher: FakeDispatcher,
        visualizer: FakeVisualizer
    ) = GestureExecutionCoordinator(
        displayMetrics = { DisplayMetricsSnapshot(widthPixels = 1_080, heightPixels = 2_400, density = 3f) },
        dispatcher = dispatcher,
        visualizer = visualizer
    )

    private fun gestureContext(
        runGeneration: Long = 0L,
        windowGeneration: Long = 0L
    ) = GestureExecutionContext(
        runGeneration = runGeneration,
        targetWindow = WindowRef("com.immomo.momo", "FeedActivity", windowGeneration, 13),
        coordinateContext = DisplayMetricsSnapshot(1_080, 2_400, 3f).toGestureCoordinateContext()
    )

    private fun validSwipe(
        id: String = "swipe-1",
        xEnd: Float = 900f,
        runGeneration: Long = 0L,
        windowGeneration: Long = 0L,
        preDispatchDelayMs: Long = 0L
    ): GestureCommand {
        val plan = checkNotNull(
            GestureGeometryPolicy.createVerticalSwipe(
                bounds = com.freedom.core.accessibility.api.ScreenRect(0, 0, 1_080, 2_400),
                direction = GestureGeometryPolicy.VerticalSwipeDirection.UP,
                random = Random(42),
                durationMs = 420L
            )
        )
        val strokes = plan.strokes.toMutableList()
        if (xEnd != 900f) {
            val finalStroke = strokes.last()
            val commands = finalStroke.commands.toMutableList()
            val lastIndex = commands.lastIndex
            val last = commands[lastIndex] as GesturePathCommand.CubicTo
            commands[lastIndex] = last.copy(end = last.end.copy(x = xEnd))
            strokes[strokes.lastIndex] = finalStroke.copy(commands = commands)
        }
        val primary = strokes.first()
        return GestureCommand(
            id = id,
            kind = GestureKind.SWIPE,
            gesture = GestureSpec(
                commands = primary.commands,
                durationMs = primary.durationMs,
                startDelayMs = primary.startDelayMs,
                willContinue = primary.willContinue,
                additionalStrokes = strokes.drop(1)
            ),
            validationRegion = checkNotNull(GestureGeometryPolicy.validationRegion(plan.safeBounds)),
            executionContext = gestureContext(runGeneration, windowGeneration),
            description = "test swipe",
            preDispatchDelayMs = preDispatchDelayMs
        )
    }

    private class FakeTaskScheduler : TaskScheduler {
        private val pending = mutableListOf<() -> Unit>()
        val pendingCount: Int get() = pending.size

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            pending += task
            return ScheduledTask { pending.remove(task) }
        }

        fun runNext() {
            check(pending.isNotEmpty()) { "No scheduled task" }
            pending.removeAt(0).invoke()
        }

        fun runAll() {
            while (pending.isNotEmpty()) runNext()
        }
    }

    private class FakeDispatcher(private val events: MutableList<String>) :
        com.freedom.core.accessibility.api.GestureExecutionPort {
        val callbacks = mutableListOf<GestureResultListener>()
        val callback: GestureResultListener? get() = callbacks.lastOrNull()

        override fun dispatchGesture(
            spec: GestureSpec,
            listener: GestureResultListener
        ): GestureSubmission {
            events += "dispatch"
            callbacks += listener
            return GestureSubmission.ACCEPTED
        }
    }

    private class FakeVisualizer(
        private val events: MutableList<String>,
        private val canShow: Boolean = true
    ) : GestureVisualizationPort {
        var lastTerminalState: String? = null
        var lastCommandId: String? = null
        val shownCommandIds = mutableListOf<String>()

        override fun show(command: GestureCommand): Boolean {
            events += "show"
            lastCommandId = command.id
            shownCommandIds += command.id
            return canShow
        }

        override fun onCompleted(command: GestureCommand) {
            lastTerminalState = "completed"
        }

        override fun onCancelled(command: GestureCommand) {
            lastTerminalState = "cancelled"
        }

        override fun onRejected(command: GestureCommand, reason: String) {
            lastTerminalState = "rejected"
        }

        override fun onFailed(command: GestureCommand, reason: String) {
            lastTerminalState = "failed"
        }

        override fun clear() = Unit
    }
}
