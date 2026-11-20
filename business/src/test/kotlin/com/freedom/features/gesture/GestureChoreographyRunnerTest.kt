package com.freedom.features.gesture

import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureTimeDistributionKind
import com.freedom.core.accessibility.gesture.GestureTimeDistributionProfile
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.gesture.api.GestureApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GestureChoreographyRunnerTest {
    @Test
    fun stepsRunInOrderAndPauseIsScheduledBetweenCallbacks() {
        val api = FakeGestureApi()
        val scheduler = FakeScheduler()
        val results = mutableListOf<GestureChoreographyResult>()
        val runner = GestureChoreographyRunner(api, scheduler, random = Random(11))
        val handle = runner.execute("sequence-1", listOf(
            GestureChoreographyStep(request("first"), pauseAfterMs = 80L),
            GestureChoreographyStep(request("second"))
        )) { results += it }

        assertNotNull(handle)
        assertEquals(listOf("first"), api.submittedIds)
        api.complete("first")
        assertEquals(listOf("first"), api.submittedIds)
        assertEquals(1, scheduler.pendingCount)
        val sampledPause = scheduler.nextDelayMs ?: error("pause was not scheduled")
        assertTrue(sampledPause in 56L..104L)
        scheduler.advanceBy(sampledPause - 1L)
        assertEquals(listOf("first"), api.submittedIds)
        scheduler.advanceBy(1L)
        assertEquals(listOf("first", "second"), api.submittedIds)
        api.complete("second")
        assertEquals(1, results.size)
        assertEquals(GestureExecutionStatus.COMPLETED, results.single().status)
        assertEquals(2, results.single().completedSteps)
    }

    @Test
    fun cancellationDuringInterStepPausePreventsNextSubmission() {
        val api = FakeGestureApi()
        val scheduler = FakeScheduler()
        val results = mutableListOf<GestureChoreographyResult>()
        val handle = GestureChoreographyRunner(api, scheduler).execute("sequence-2", listOf(
            GestureChoreographyStep(request("first"), pauseAfterMs = 100L),
            GestureChoreographyStep(request("second"))
        )) { results += it }!!

        api.complete("first")
        assertEquals(1, scheduler.pendingCount)
        assertTrue(handle.cancel("user_stop"))
        scheduler.advanceBy(100L)
        assertEquals(listOf("first"), api.submittedIds)
        assertEquals(GestureExecutionStatus.CANCELLED, results.single().status)
        assertEquals(1, results.single().completedSteps)
    }

    @Test
    fun cancellationDuringActiveStepDelegatesToUnifiedGestureApi() {
        val api = FakeGestureApi()
        val results = mutableListOf<GestureChoreographyResult>()
        val handle = GestureChoreographyRunner(api, FakeScheduler()).execute("sequence-3", listOf(
            GestureChoreographyStep(request("active")),
            GestureChoreographyStep(request("never"))
        )) { results += it }!!

        assertTrue(handle.cancel("manual_cancel"))
        assertEquals(listOf("active"), api.cancelledIds)
        assertEquals(GestureExecutionStatus.CANCELLED, results.single().status)
        assertFalse(handle.cancel("second_cancel"))
    }

    @Test
    fun rejectedStepStopsTheRemainingChoreography() {
        val api = FakeGestureApi(rejectIds = setOf("first"))
        val results = mutableListOf<GestureChoreographyResult>()
        GestureChoreographyRunner(api, FakeScheduler()).execute("sequence-4", listOf(
            GestureChoreographyStep(request("first")),
            GestureChoreographyStep(request("second"))
        )) { results += it }

        assertEquals(listOf("first"), api.submittedIds)
        assertEquals(GestureExecutionStatus.REJECTED, results.single().status)
        assertEquals(0, results.single().completedSteps)
    }

    @Test
    fun eachStageRevalidatesContextAndRunsPreAndPostConditions() {
        val api = FakeGestureApi()
        val results = mutableListOf<GestureChoreographyResult>()
        var beforeCount = 0
        var afterCount = 0
        GestureChoreographyRunner(api, FakeScheduler()).execute("guarded", listOf(
            GestureChoreographyStep(
                request("first"),
                precondition = { beforeCount++; true },
                postcondition = { afterCount++; true }
            ),
            GestureChoreographyStep(
                request("second"),
                precondition = { beforeCount++; true }
            )
        )) { results += it }
        assertEquals(listOf("first"), api.submittedIds)
        api.complete("first")
        assertEquals(listOf("first", "second"), api.submittedIds)
        api.complete("second")
        assertEquals(2, beforeCount)
        assertEquals(1, afterCount)
        assertEquals(GestureExecutionStatus.COMPLETED, results.single().status)
    }

    @Test
    fun failedPostconditionStopsLaterStages() {
        val api = FakeGestureApi()
        val results = mutableListOf<GestureChoreographyResult>()
        GestureChoreographyRunner(api, FakeScheduler()).execute("postcondition", listOf(
            GestureChoreographyStep(request("first"), postcondition = { false }),
            GestureChoreographyStep(request("second"))
        )) { results += it }
        api.complete("first")
        assertEquals(listOf("first"), api.submittedIds)
        assertEquals(GestureExecutionStatus.DEFERRED, results.single().status)
        assertEquals("stage_0_postcondition_failed", results.single().reason)
    }

    @Test
    fun stageTimeoutCancelsTheRequestAndStopsTheChoreography() {
        val api = FakeGestureApi()
        val scheduler = FakeScheduler()
        val results = mutableListOf<GestureChoreographyResult>()
        GestureChoreographyRunner(api, scheduler).execute("timeout", listOf(
            GestureChoreographyStep(request("slow"), maximumStageDurationMs = 200L),
            GestureChoreographyStep(request("never"))
        )) { results += it }
        scheduler.advanceBy(200L)
        assertEquals(listOf("slow"), api.cancelledIds)
        assertEquals(listOf("slow"), api.submittedIds)
        assertEquals(GestureExecutionStatus.FAILED, results.single().status)
        assertEquals("stage_0_timeout", results.single().reason)
    }

    @Test
    fun staleContextStopsBeforeSubmittingTheStage() {
        val api = FakeGestureApi(staleContext = true)
        val results = mutableListOf<GestureChoreographyResult>()
        GestureChoreographyRunner(api, FakeScheduler()).execute("stale", listOf(
            GestureChoreographyStep(request("first"))
        )) { results += it }
        assertTrue(api.submittedIds.isEmpty())
        assertEquals(GestureExecutionStatus.DEFERRED, results.single().status)
        assertEquals("stage_0_context_stale", results.single().reason)
    }

    @Test
    fun invalidOrDuplicateRequestIdsAreRejectedBeforeSubmission() {
        val api = FakeGestureApi()
        val runner = GestureChoreographyRunner(api, FakeScheduler())
        val duplicate = runner.execute("sequence-5", listOf(
            GestureChoreographyStep(request("same")),
            GestureChoreographyStep(request("same"))
        )) { }
        val tooLongPause = runner.execute("sequence-6", listOf(
            GestureChoreographyStep(request("one"), pauseAfterMs = 6_000L)
        )) { }

        assertNull(duplicate)
        assertNull(tooLongPause)
        assertTrue(api.submittedIds.isEmpty())
    }


    @Test
    fun requestFactoryBuildsFreshRequestJustInTimeForEachStage() {
        val api = FakeGestureApi()
        val scheduler = FakeScheduler()
        val factoryCalls = mutableListOf<String>()
        val firstContext = context(generation = 1L, activity = "TestActivity")
        val secondContext = context(generation = 2L, activity = "DetailActivity")
        val results = mutableListOf<GestureChoreographyResult>()
        val handle = GestureChoreographyRunner(api, scheduler).execute("fresh-sequence", listOf(
            GestureChoreographyStep(
                requestFactory = {
                    factoryCalls += "first"
                    request("fresh-first", firstContext)
                },
                postcondition = { api.setCurrentContext(secondContext); true }
            ),
            GestureChoreographyStep(
                requestFactory = {
                    factoryCalls += "second"
                    request("fresh-second", secondContext)
                }
            )
        )) { results += it }

        assertNotNull(handle)
        assertEquals(listOf("first"), factoryCalls)
        assertEquals(listOf("fresh-first"), api.submittedIds)
        api.complete("fresh-first")
        assertEquals(listOf("first", "second"), factoryCalls)
        assertEquals(listOf("fresh-first", "fresh-second"), api.submittedIds)
        api.complete("fresh-second")
        assertEquals(GestureExecutionStatus.COMPLETED, results.single().status)
        assertEquals(2, results.single().completedSteps)
    }

    @Test
    fun stageTimeoutDuringExecuteIsReappliedAfterSubmissionReturns() {
        lateinit var scheduler: FakeScheduler
        val api = FakeGestureApi(onExecute = { scheduler.advanceBy(100L) })
        scheduler = FakeScheduler()
        val results = mutableListOf<GestureChoreographyResult>()
        GestureChoreographyRunner(api, scheduler).execute("submission-timeout", listOf(
            GestureChoreographyStep(request("during-submit"), maximumStageDurationMs = 100L)
        )) { results += it }

        assertEquals(listOf("during-submit"), api.submittedIds)
        assertTrue(api.cancelledIds.contains("during-submit"))
        assertEquals(GestureExecutionStatus.FAILED, results.single().status)
        assertEquals("stage_0_timeout", results.single().reason)
    }


    @Test
    fun synchronousCompletionDoesNotReenterNextGestureSubmission() {
        val scheduler = FakeScheduler()
        val api = FakeGestureApi(synchronousCompleteIds = setOf("sync-first", "sync-second"))
        val results = mutableListOf<GestureChoreographyResult>()
        GestureChoreographyRunner(api, scheduler).execute("sync-sequence", listOf(
            GestureChoreographyStep(request("sync-first")),
            GestureChoreographyStep(request("sync-second"))
        )) { results += it }

        assertEquals(listOf("sync-first"), api.submittedIds)
        assertEquals(1, scheduler.pendingCount)
        scheduler.advanceBy(0L)
        assertEquals(listOf("sync-first", "sync-second"), api.submittedIds)
        assertEquals(GestureExecutionStatus.COMPLETED, results.single().status)
        assertEquals(2, results.single().completedSteps)
    }

    private fun context(generation: Long, activity: String = "TestActivity") = GestureExecutionContext(
        runGeneration = generation,
        targetWindow = WindowRef("test.app", activity, generation),
        coordinateContext = GestureCoordinateContext(0, 1080, 1920, 1f, 0)
    )

    private fun request(
        id: String,
        executionContext: GestureExecutionContext = context(1L, "TestActivity")
    ): GestureIntentRequest = GestureIntentRequest(
        id = id,
        intent = GestureIntent.Tap(
            preferredBounds = ScreenRect(100, 100, 200, 200),
            targetBounds = ScreenRect(100, 100, 200, 200),
            durationMs = 80L
        ),
        description = "test $id",
        executionContext = executionContext
    )

    private class FakeGestureApi(
        private val rejectIds: Set<String> = emptySet(),
        private val staleContext: Boolean = false,
        private val onExecute: (() -> Unit)? = null,
        private val synchronousCompleteIds: Set<String> = emptySet()
    ) : GestureApi {
        private var currentContext = GestureExecutionContext(
            runGeneration = 1L,
            targetWindow = WindowRef("test.app", "TestActivity", 1L),
            coordinateContext = GestureCoordinateContext(0, 1080, 1920, 1f, 0)
        )
        private val callbacks = linkedMapOf<String, GestureExecutionListener>()
        val submittedIds = mutableListOf<String>()
        val cancelledIds = mutableListOf<String>()

        override fun captureContext(
            expectedWindow: WindowRef?,
            expectedCoordinates: GestureCoordinateContext?
        ): GestureExecutionContext? {
            if (staleContext) return null
            if (expectedWindow != null && expectedWindow != currentContext.targetWindow) return null
            if (expectedCoordinates != null && expectedCoordinates != currentContext.coordinateContext) return null
            return currentContext
        }

        fun setCurrentContext(context: GestureExecutionContext) {
            currentContext = context
        }

        override fun execute(
            request: GestureIntentRequest,
            listener: GestureExecutionListener
        ): GestureSubmission {
            submittedIds += request.id
            callbacks[request.id] = listener
            onExecute?.invoke()
            if (request.id in synchronousCompleteIds) {
                callbacks.remove(request.id)
                listener.onResult(GestureExecutionResult(request.id, GestureExecutionStatus.COMPLETED))
            }
            return if (request.id in rejectIds) GestureSubmission.REJECTED else GestureSubmission.ACCEPTED
        }

        override fun cancelActive(commandId: String, reason: String): Boolean {
            cancelledIds += commandId
            return true
        }

        fun complete(id: String, status: GestureExecutionStatus = GestureExecutionStatus.COMPLETED) {
            callbacks.remove(id)?.onResult(GestureExecutionResult(id, status))
        }
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(val dueAt: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()
        private var now = 0L
        val pendingCount: Int get() = entries.count { !it.cancelled }
        val nextDelayMs: Long? get() = entries.filterNot { it.cancelled }.minOfOrNull { it.dueAt - now }

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            require(delayMillis >= 0L)
            val entry = Entry(now + delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            now += deltaMs
            while (true) {
                val next = entries.filter { !it.cancelled && it.dueAt <= now }.minByOrNull { it.dueAt } ?: break
                entries.remove(next)
                if (!next.cancelled) next.action()
            }
        }
    }
}
