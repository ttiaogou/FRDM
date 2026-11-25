package com.freedom.adapters.accessibility.android

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAccessibilityServiceRecoveryCoordinatorTest {
    @Test
    fun idleConnectionConnectsRuntimeAndRefreshesWithoutAttemptingRecovery() {
        val host = FakeHost().apply { runRequested = false }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(listOf("clear-activity", "connect", "show-overlay", "refresh"), host.events)
        assertEquals(0, scheduler.pendingCount)
        assertFalse(host.keepAliveStarted)
    }

    @Test
    fun runAuthorizationRestoresPageObservationInsteadOfStoppingTheService() {
        val host = FakeHost().apply { runRequested = true }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(
            listOf("clear-activity", "connect", "log:服务重连：重新观察当前页面", "show-overlay", "refresh"),
            host.events
        )
        assertTrue(host.runRequested)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun pageRestoreConnectsAndSchedulesKeepAliveOnlyAfterOverlayIsShown() {
        val host = FakeHost().apply { runRequested = true }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(
            listOf("clear-activity", "connect", "log:服务重连：重新观察当前页面", "show-overlay", "refresh"),
            host.events
        )
        assertEquals(listOf(AndroidAccessibilityServiceRecoveryCoordinator.KEEP_ALIVE_START_DELAY_MS), scheduler.delays)
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()
        assertEquals(listOf("clear-activity", "connect", "log:服务重连：重新观察当前页面", "show-overlay", "refresh", "start-keep-alive"), host.events)
        assertTrue(host.keepAliveStarted)
    }

    @Test
    fun restoreClearsActivityEvidenceBeforeRuntimeConnectAndOverlayRecovery() {
        val host = FakeHost().apply { runRequested = true }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(
            listOf(
                "clear-activity",
                "connect",
                "log:服务重连：重新观察当前页面",
                "show-overlay",
                "refresh"
            ),
            host.events
        )
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun floatingLauncherRecoversWhenServiceReconnectsWithoutAnActiveRun() {
        val host = FakeHost().apply {
            runRequested = false
            floatingLogEnabled = true
            overlayEnabled = true
        }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(listOf("clear-activity", "connect", "show-overlay", "refresh"), host.events)
        assertEquals(0, scheduler.pendingCount)
        assertFalse(host.keepAliveStarted)
    }

    @Test
    fun disabledFloatingLauncherIsNotRestoredWhenServiceReconnects() {
        val host = FakeHost().apply {
            runRequested = false
            floatingLogEnabled = false
            overlayEnabled = false
        }

        coordinator(host, FakeScheduler()).onServiceConnected()

        assertEquals(listOf("clear-activity", "connect", "refresh"), host.events)
        assertFalse(host.events.contains("show-overlay"))
    }

    @Test
    fun revokedOverlayPermissionDoesNotStopRestoredRunOrBlockKeepAlive() {
        val host = FakeHost().apply {
            runRequested = true
            overlayPermission = false
        }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(listOf("clear-activity", "connect", "log:服务重连：重新观察当前页面", "refresh"), host.events)
        assertTrue(host.runRequested)
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()
        assertTrue(host.keepAliveStarted)
        assertFalse(host.events.contains("show-overlay"))
    }

    @Test
    fun disablingFloatingLogHidesOverlayButDoesNotDisableKeepAlive() {
        val host = FakeHost().apply {
            runRequested = true
            floatingLogEnabled = false
        }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(listOf("clear-activity", "connect", "log:服务重连：重新观察当前页面", "refresh"), host.events)
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()
        assertTrue(host.keepAliveStarted)
        assertFalse(host.events.contains("show-overlay"))
    }

    @Test
    fun disablingKeepAliveStillAllowsConfiguredOverlayButDoesNotScheduleService() {
        val host = FakeHost().apply {
            runRequested = true
            keepAliveEnabled = false
        }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(listOf("clear-activity", "connect", "log:服务重连：重新观察当前页面", "show-overlay", "refresh"), host.events)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun delayedKeepAliveDoesNotDependOnFloatingLogSetting() {
        val host = FakeHost().apply { runRequested = true }
        val scheduler = FakeScheduler()
        coordinator(host, scheduler).onServiceConnected()

        host.floatingLogEnabled = false
        scheduler.runNext()

        assertTrue(host.keepAliveStarted)
        assertTrue(host.events.contains("start-keep-alive"))
    }

    @Test
    fun delayedKeepAliveIsNotSkippedWhenOverlayPermissionIsRevokedAfterScheduling() {
        val host = FakeHost().apply { runRequested = true }
        val scheduler = FakeScheduler()
        coordinator(host, scheduler).onServiceConnected()

        host.overlayPermission = false
        scheduler.runNext()

        assertTrue(host.keepAliveStarted)
        assertTrue(host.events.contains("start-keep-alive"))
    }

    @Test
    fun delayedKeepAliveIsSkippedWhenKeepAliveSettingIsDisabledAfterScheduling() {
        val host = FakeHost().apply { runRequested = true }
        val scheduler = FakeScheduler()
        coordinator(host, scheduler).onServiceConnected()

        host.keepAliveEnabled = false
        scheduler.runNext()

        assertFalse(host.keepAliveStarted)
        assertFalse(host.events.contains("start-keep-alive"))
    }

    @Test
    fun delayedKeepAliveStartsOnlyWhenAllRuntimeAndPermissionConditionsStillHold() {
        val host = FakeHost().apply { runRequested = true }
        val scheduler = FakeScheduler()
        coordinator(host, scheduler).onServiceConnected()

        host.runRequested = false
        scheduler.runNext()

        assertFalse(host.keepAliveStarted)
        assertFalse(host.events.contains("start-keep-alive"))
    }

    @Test
    fun serviceDestructionCancelsPendingKeepAliveRecovery() {
        val host = FakeHost().apply { runRequested = true }
        val scheduler = FakeScheduler()
        val coordinator = coordinator(host, scheduler)
        coordinator.onServiceConnected()

        coordinator.onServiceDestroyed()
        scheduler.runAll()

        assertEquals(0, scheduler.pendingCount)
        assertFalse(host.keepAliveStarted)
    }

    @Test
    fun repeatedConnectionReplacesThePreviousDelayedStartInsteadOfDuplicatingIt() {
        val host = FakeHost().apply { runRequested = true }
        val scheduler = FakeScheduler()
        val coordinator = coordinator(host, scheduler)
        coordinator.onServiceConnected()
        coordinator.onServiceConnected()

        assertEquals(1, scheduler.pendingCount)
        scheduler.runAll()
        assertEquals(1, host.events.count { it == "start-keep-alive" })
    }

    @Test
    fun pendingBootRecoveryIsAcknowledgedOnlyAfterRuntimeReconciliation() {
        val host = FakeHost().apply {
            runRequested = true
            bootRecoveryPending = true
        }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertTrue(host.events.indexOf("connect") < host.events.indexOf("clear-boot-recovery"))
        assertTrue(host.events.any { it.contains("开机恢复[RECONCILED]") })
        assertFalse(host.bootRecoveryPending)
    }

    @Test
    fun bootSignalForAlreadyConnectedServiceDoesNotRestartRuntime() {
        val host = FakeHost().apply {
            runRequested = true
            bootRecoveryPending = true
        }
        val recovery = coordinator(host, FakeScheduler())

        recovery.onBootRecoverySignal()

        assertFalse(host.events.contains("connect"))
        assertTrue(host.events.any { it.contains("开机恢复[RECONCILED]") })
        assertFalse(host.bootRecoveryPending)
    }

    @Test
    fun pendingBootRecoveryIsDiscardedWhenUserAlreadyStoppedTheRun() {
        val host = FakeHost().apply {
            runRequested = false
            bootRecoveryPending = true
        }

        coordinator(host, FakeScheduler()).onServiceConnected()

        assertTrue(host.events.any { it.contains("开机恢复[SKIPPED]") })
        assertFalse(host.bootRecoveryPending)
    }

    private fun coordinator(host: FakeHost, scheduler: FakeScheduler) =
        AndroidAccessibilityServiceRecoveryCoordinator(host, scheduler)

    private class FakeHost : AndroidAccessibilityServiceRecoveryCoordinator.Host {
        val events = mutableListOf<String>()
        var runRequested = false
        var overlayPermission = true
        var overlayEnabled = true
        var floatingLogEnabled = true
        var keepAliveEnabled = true
        var keepAliveStarted = false
        var bootRecoveryPending = false

        override fun isRunRequested() = runRequested
        override fun isBootRecoveryPending() = bootRecoveryPending
        override fun clearBootRecoveryPending() {
            events += "clear-boot-recovery"
            bootRecoveryPending = false
        }
        override fun appendLog(message: String) { events += "log:$message" }
        override fun clearActivityContext() { events += "clear-activity" }
        override fun connectRuntime() { events += "connect" }
        override fun canDrawOverlays() = overlayPermission
        override fun isOverlayEnabled() = overlayEnabled
        override fun isFloatingLogEnabled() = floatingLogEnabled
        override fun showOverlay() { events += "show-overlay" }
        override fun isKeepAliveEnabled() = keepAliveEnabled
        override fun startKeepAlive() {
            events += "start-keep-alive"
            keepAliveStarted = true
        }
        override fun refresh() { events += "refresh" }
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()
        val delays: List<Long> get() = entries.map { it.delay }
        val pendingCount: Int get() = entries.count { !it.cancelled }

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun runNext() {
            val entry = entries.firstOrNull { !it.cancelled } ?: return
            entry.cancelled = true
            entry.task()
        }

        fun runAll() {
            while (pendingCount > 0) runNext()
        }
    }
}
