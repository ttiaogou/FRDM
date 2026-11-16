package com.freedom.adapters.accessibility.android

import com.freedom.core.runtime.RunMode
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

        assertEquals(listOf("connect", "show-overlay", "refresh"), host.events)
        assertEquals(0, scheduler.pendingCount)
        assertFalse(host.keepAliveStarted)
    }

    @Test
    fun missingPersistedModeRestoresManualObservationInsteadOfStoppingTheService() {
        val host = FakeHost().apply { runRequested = true; restoredMode = null }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(
            listOf("resolve-mode", "log:已恢复人工采集待命：未选择 AI 回复或自动浏览；手动节点采集仍可使用", "connect", "show-overlay", "refresh"),
            host.events
        )
        assertTrue(host.runRequested)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun accountBrowseRestoreConnectsAndSchedulesKeepAliveOnlyAfterOverlayIsShown() {
        val host = FakeHost().apply { runRequested = true; restoredMode = RunMode.ACCOUNT_BROWSE }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(
            listOf("resolve-mode", "connect", "show-overlay", "refresh"),
            host.events
        )
        assertEquals(listOf(AndroidAccessibilityServiceRecoveryCoordinator.KEEP_ALIVE_START_DELAY_MS), scheduler.delays)
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()
        assertEquals(listOf("resolve-mode", "connect", "show-overlay", "refresh", "start-keep-alive"), host.events)
        assertTrue(host.keepAliveStarted)
    }

    @Test
    fun aiRestoreClearsBrowseStateBeforeRuntimeConnectAndOverlayRecovery() {
        val host = FakeHost().apply { runRequested = true; restoredMode = RunMode.AI_REPLY }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(
            listOf(
                "resolve-mode",
                "reset:ACCOUNT_BROWSE:AI 模式恢复",
                "clear-activity",
                "log:已恢复 AI 回复模式运行状态；聊天页面观察独立运行，尚未接入消息解析与回复执行器",
                "connect",
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

        assertEquals(listOf("connect", "show-overlay", "refresh"), host.events)
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

        assertEquals(listOf("connect", "refresh"), host.events)
        assertFalse(host.events.contains("show-overlay"))
    }

    @Test
    fun revokedOverlayPermissionDoesNotStopRestoredRunOrBlockKeepAlive() {
        val host = FakeHost().apply {
            runRequested = true
            restoredMode = RunMode.ACCOUNT_BROWSE
            overlayPermission = false
        }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(listOf("resolve-mode", "connect", "refresh"), host.events)
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
            restoredMode = RunMode.ACCOUNT_BROWSE
            floatingLogEnabled = false
        }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(listOf("resolve-mode", "connect", "refresh"), host.events)
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()
        assertTrue(host.keepAliveStarted)
        assertFalse(host.events.contains("show-overlay"))
    }

    @Test
    fun disablingKeepAliveStillAllowsConfiguredOverlayButDoesNotScheduleService() {
        val host = FakeHost().apply {
            runRequested = true
            restoredMode = RunMode.ACCOUNT_BROWSE
            keepAliveEnabled = false
        }
        val scheduler = FakeScheduler()

        coordinator(host, scheduler).onServiceConnected()

        assertEquals(listOf("resolve-mode", "connect", "show-overlay", "refresh"), host.events)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun delayedKeepAliveDoesNotDependOnFloatingLogSetting() {
        val host = FakeHost().apply { runRequested = true; restoredMode = RunMode.ACCOUNT_BROWSE }
        val scheduler = FakeScheduler()
        coordinator(host, scheduler).onServiceConnected()

        host.floatingLogEnabled = false
        scheduler.runNext()

        assertTrue(host.keepAliveStarted)
        assertTrue(host.events.contains("start-keep-alive"))
    }

    @Test
    fun delayedKeepAliveIsNotSkippedWhenOverlayPermissionIsRevokedAfterScheduling() {
        val host = FakeHost().apply { runRequested = true; restoredMode = RunMode.ACCOUNT_BROWSE }
        val scheduler = FakeScheduler()
        coordinator(host, scheduler).onServiceConnected()

        host.overlayPermission = false
        scheduler.runNext()

        assertTrue(host.keepAliveStarted)
        assertTrue(host.events.contains("start-keep-alive"))
    }

    @Test
    fun delayedKeepAliveIsSkippedWhenKeepAliveSettingIsDisabledAfterScheduling() {
        val host = FakeHost().apply { runRequested = true; restoredMode = RunMode.ACCOUNT_BROWSE }
        val scheduler = FakeScheduler()
        coordinator(host, scheduler).onServiceConnected()

        host.keepAliveEnabled = false
        scheduler.runNext()

        assertFalse(host.keepAliveStarted)
        assertFalse(host.events.contains("start-keep-alive"))
    }

    @Test
    fun delayedKeepAliveStartsOnlyWhenAllRuntimeAndPermissionConditionsStillHold() {
        val host = FakeHost().apply { runRequested = true; restoredMode = RunMode.ACCOUNT_BROWSE }
        val scheduler = FakeScheduler()
        coordinator(host, scheduler).onServiceConnected()

        host.runRequested = false
        scheduler.runNext()

        assertFalse(host.keepAliveStarted)
        assertFalse(host.events.contains("start-keep-alive"))
    }

    @Test
    fun serviceDestructionCancelsPendingKeepAliveRecovery() {
        val host = FakeHost().apply { runRequested = true; restoredMode = RunMode.ACCOUNT_BROWSE }
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
        val host = FakeHost().apply { runRequested = true; restoredMode = RunMode.ACCOUNT_BROWSE }
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
            restoredMode = RunMode.AI_REPLY
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
            restoredMode = RunMode.ACCOUNT_BROWSE
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
        var restoredMode: RunMode? = RunMode.ACCOUNT_BROWSE
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
        override fun resolveActiveModeForRestore(): RunMode? {
            events += "resolve-mode"
            return restoredMode
        }
        override fun stopRun(reason: String) {
            events += "stop:$reason"
            runRequested = false
        }
        override fun stopKeepAlive() { events += "stop-keep-alive" }
        override fun appendLog(message: String) { events += "log:$message" }
        override fun resetRuntime(mode: RunMode, reason: String) { events += "reset:$mode:$reason" }
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
