package com.freedom.application.runtime

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.RunStateRepository
import com.freedom.core.runtime.RuntimeLogSink
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.TaskLease
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import com.freedom.core.runtime.UiWriteAuthorization
import com.freedom.core.runtime.UiWriteScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the real session/lease/final-write gates together, without Android UI mutations. */
class R09cPageWriteAuthorityTest {
    private data class Route(val mode: RunMode, val task: String, val scopes: Set<UiWriteScope>)

    private val privateRoute = Route(RunMode.AI_REPLY, "AI_REPLY_PRIVATE_MESSAGES",
        setOf(UiWriteScope.AI_COMPOSER, UiWriteScope.VERIFIED_AI_BACK,
            UiWriteScope.GLOBAL_NAVIGATION, UiWriteScope.AUTONOMOUS_GESTURE))
    private val greetingRoute = Route(RunMode.AI_REPLY, "AI_REPLY_RECEIVED_GREETINGS",
        setOf(UiWriteScope.VERIFIED_GREETING_COMPOSER, UiWriteScope.VERIFIED_AI_BACK,
            UiWriteScope.GLOBAL_NAVIGATION, UiWriteScope.AUTONOMOUS_GESTURE))
    private val browseRoute = Route(RunMode.ACCOUNT_BROWSE, "ACCOUNT_BROWSE",
        setOf(UiWriteScope.BROWSE_NODE_ACTION, UiWriteScope.GLOBAL_NAVIGATION,
            UiWriteScope.AUTONOMOUS_GESTURE))

    private class State : RunStateRepository {
        private var requested = true
        override fun isRunRequested() = requested
        override fun setRunRequested(requested: Boolean) { this.requested = requested }
    }

    private class Fixture {
        val clock = RunGenerationClock()
        val ui = UiOperationOwnershipCoordinator()
        val tasks = TaskCoordinator(ui)
        var onSave: () -> Unit = {}
        var onPause: () -> Unit = {}
        private val state = State()
        private fun runtime(runtimeMode: RunMode) = object : ModeRuntimePort {
            override val mode = runtimeMode
            override fun onRunStarted(taskId: String, retry: Boolean) = true
            override fun onRunStopped(reason: String) = Unit
            override fun saveProgressBeforeExit(reason: String, serviceBoundary: Boolean) = onSave()
            override fun onRunPaused(reason: String) = onPause()
            override fun onAccessibilityServiceConnected() = Unit
            override fun onAccessibilityServiceDisconnected() = Unit
            override fun onTargetApplicationEvent(event: AccessibilityEventEnvelope) = Unit
        }
        val session = RunSessionCoordinator(state,
            mapOf(RunMode.ACCOUNT_BROWSE to runtime(RunMode.ACCOUNT_BROWSE),
                RunMode.AI_REPLY to runtime(RunMode.AI_REPLY)), RuntimeLogSink { },
            generationClock = clock, taskCoordinator = tasks)
        val writes = UiWriteAuthorization(tasks, clock::current,
            { session.currentWriteRoute()?.mode }, session::currentWriteRoute)
        init { session.synchronizeRunRequest() }

        fun select(route: Route): TaskLease {
            val page = requireNotNull(session.routeIndependentPageTask(route.mode, route.task))
            assertNull(session.currentWriteRoute())
            val lease = requireNotNull(tasks.tryAcquire(route.task, page.runGeneration))
            assertTrue(session.admitBusinessPageTaskOwnership(lease, false))
            assertEquals(route.task, session.currentWriteRoute()?.taskId)
            assertEquals(route.task, ui.currentOwnerId())
            return lease
        }
        fun verify(lease: TaskLease?, expected: Set<UiWriteScope>) {
            for (scope in UiWriteScope.values()) {
                var performed = 0
                val result = writes.withBoundAuthorizedWrite(scope, lease) {
                    performed++
                    "allowed"
                }
                assertEquals(scope.name, if (scope in expected) "allowed" else null, result)
                assertEquals(scope.name, if (scope in expected) 1 else 0, performed)
                assertEquals(scope.name, scope in expected, writes.permitsBound(scope, lease))
            }
        }
    }

    @Test
    fun sixIndependentSwitchesHoldWritesUntilFreshPageAndExactDestinationLease() {
        val routes = listOf(privateRoute, greetingRoute, browseRoute)
        for (source in routes) for (destination in routes.filter { it != source }) {
            val f = Fixture()
            val lease = f.select(source)
            f.verify(lease, source.scopes)
            assertNull(f.tasks.tryAcquire(destination.task, lease.runGeneration))
            f.session.reconcileUnclassifiedPage(lease.runGeneration, false)
            f.verify(lease, emptySet())
            var saved = false
            var paused = false
            f.onSave = {
                saved = true
                assertEquals(source.task, f.ui.currentOwnerId())
                f.verify(lease, emptySet())
            }
            f.onPause = {
                paused = true
                assertNull(f.tasks.currentLease())
                assertNull(f.ui.currentOwnerId())
                f.verify(lease, emptySet())
            }
            assertNull(f.session.routeIndependentPageTask(destination.mode, destination.task, lease.runGeneration))
            assertTrue(saved && paused)
            assertNull(f.session.routeIndependentPageTask(destination.mode, destination.task, lease.runGeneration))
            assertFalse(f.session.admitBusinessPageTaskOwnership(lease, true))
            f.verify(lease, emptySet())
            val fresh = f.select(destination)
            assertTrue(fresh.runGeneration > lease.runGeneration)
            f.verify(lease, emptySet())
            f.verify(fresh, destination.scopes)
            assertFalse(f.tasks.release(lease))
            assertEquals(destination.task, f.ui.currentOwnerId())
            f.session.stop("test stop")
            assertNull(f.ui.currentOwnerId())
            f.verify(fresh, emptySet())
        }
    }

    @Test
    fun verifiedSiblingHandoffAndSameGenerationReplacementCannotReviveOldCapability() {
        val f = Fixture()
        val first = f.select(privateRoute)
        val next = requireNotNull(f.tasks.transfer(first, greetingRoute.task))
        assertEquals(first.runGeneration, next.runGeneration)
        assertNull(f.session.currentWriteRoute())
        f.verify(first, emptySet())
        f.verify(next, emptySet())
        assertFalse(f.session.admitBusinessPageTaskOwnership(next, false))
        assertTrue(f.session.admitBusinessPageTaskOwnership(next, true))
        f.verify(next, greetingRoute.scopes)
        f.verify(first, emptySet())
        assertTrue(f.tasks.release(next))
        f.verify(next, emptySet())
        val replacement = requireNotNull(f.tasks.tryAcquire(greetingRoute.task, next.runGeneration))
        assertFalse(f.session.rollbackBusinessPageTaskOwnership(next))
        assertTrue(f.session.admitBusinessPageTaskOwnership(replacement, false))
        f.verify(next, emptySet())
        f.verify(replacement, greetingRoute.scopes)
        assertFalse(f.tasks.release(next))
        assertEquals(greetingRoute.task, f.ui.currentOwnerId())
    }
}
