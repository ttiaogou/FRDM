package com.freedom.core.runtime

import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAppReadinessTest {
    @Test
    fun startupAndUnrecognizedPagesNeverBecomeReady() {
        val controller = TargetAppReadinessController()
        controller.markStarting(RunMode.AI_REPLY, "run requested", 100L)
        assertEquals(TargetAppReadinessStatus.STARTING, controller.current().status)

        val snapshot = controller.evaluate(
            TargetAppReadinessEvidence(
                targetPackage = TARGET_PACKAGE,
                foregroundPackage = TARGET_PACKAGE,
                activityClass = "com.immomo.momo.splash.LaunchActivity",
                runMode = RunMode.AI_REPLY,
                windowId = 4,
                pageGeneration = 1L,
                activeWindowAvailable = true,
                businessPageReady = false,
                businessPageReason = "广告/启动页尚未通过页面准入"
            ),
            120L
        )

        assertEquals(TargetAppReadinessStatus.STARTING, snapshot.status)
        assertTrue(snapshot.reason.contains("广告/启动页"))
    }

    @Test
    fun incompleteRetryableSafetyInspectionRemainsStartingUntilFreshEvidenceCompletes() {
        val controller = TargetAppReadinessController(foregroundQuietPeriodMs = 0L)
        val incomplete = readyEvidence().copy(
            businessPageReady = false,
            businessPageReason = "页面安全检查尚未完整；等待新鲜快照重试",
            blockingReason = null
        )

        val deferred = controller.evaluate(incomplete, 200L)
        assertEquals(TargetAppReadinessStatus.STARTING, deferred.status)
        assertTrue(deferred.reason.contains("等待新鲜快照重试"))

        val confirmed = controller.evaluate(readyEvidence(), 300L)
        assertEquals(TargetAppReadinessStatus.READY, confirmed.status)
    }

    @Test
    fun adOverlayAndForeignForegroundAreBlocked() {
        val controller = TargetAppReadinessController()
        val ad = readyEvidence().copy(blockingReason = "检测到开屏广告")
        assertEquals(TargetAppReadinessStatus.BLOCKED, controller.evaluate(ad, 200L).status)

        val foreign = readyEvidence().copy(foregroundPackage = "com.android.systemui")
        assertEquals(TargetAppReadinessStatus.BLOCKED, controller.evaluate(foreign, 220L).status)
    }

    @Test
    fun onlyFreshCompleteEvidenceCanSetReadyAndStopInvalidatesIt() {
        val transitions = mutableListOf<Pair<TargetAppReadinessStatus, TargetAppReadinessStatus>>()
        val controller = TargetAppReadinessController { previous, current ->
            transitions += previous.status to current.status
        }
        val duringQuiet = controller.evaluate(readyEvidence(), 300L)
        assertEquals(TargetAppReadinessStatus.STARTING, duringQuiet.status)
        assertTrue(duringQuiet.reason.contains("静默观察中"))
        assertEquals(TargetAppReadinessStatus.STARTING, controller.evaluate(readyEvidence(), 5_299L).status)
        val ready = controller.evaluate(readyEvidence(), 5_300L)
        assertEquals(TargetAppReadinessStatus.READY, ready.status)
        assertEquals(6, ready.windowId)
        assertEquals(9L, ready.pageGeneration)

        controller.invalidate("run stopped", 5_310L)
        assertEquals(TargetAppReadinessStatus.UNKNOWN, controller.current().status)
        assertTrue(transitions.contains(TargetAppReadinessStatus.READY to TargetAppReadinessStatus.UNKNOWN))
    }

    @Test
    fun greetingsReturnRequiresFreshReadyForExactRecoveredWindowBeforeActionPreparation() {
        val controller = TargetAppReadinessController(foregroundQuietPeriodMs = 0L)
        val list = readyEvidence().copy(
            activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
            windowId = 3083,
            pageGeneration = 24L
        )
        val window = WindowRef(list.targetPackage, list.activityClass, list.pageGeneration, list.windowId)
        val missing = controller.evaluate(list.copy(activityClass = ""), 100L)
        repeat(6) {
            assertFalse(missing.isReadyForWindow(window, RunMode.AI_REPLY))
        }
        val ready = controller.evaluate(list, 200L)
        assertTrue(ready.isReadyForWindow(window, RunMode.AI_REPLY))
        assertFalse(ready.isReadyForWindow(window.copy(activityClass = ""), RunMode.AI_REPLY))
        assertFalse(ready.isReadyForWindow(window.copy(windowId = 3097), RunMode.AI_REPLY))
        assertFalse(ready.isReadyForWindow(window.copy(generation = 25L), RunMode.AI_REPLY))
        assertFalse(ready.isReadyForWindow(window.copy(packageName = "other"), RunMode.AI_REPLY))
        assertFalse(ready.isReadyForWindow(window, RunMode.ACCOUNT_BROWSE))
        assertFalse(controller.evaluate(list.copy(blockingReason = "overlay"), 300L)
            .isReadyForWindow(window, RunMode.AI_REPLY))
        controller.invalidate("stop", 400L)
        assertFalse(controller.current().isReadyForWindow(window, RunMode.AI_REPLY))
    }

    @Test
    fun foregroundQuietHintUsesExistingClockAndDoesNotAuthorizeOrAdvanceReadiness() {
        val controller = TargetAppReadinessController()
        assertEquals(null, controller.foregroundQuietRemainingMs(100L))
        controller.observeForegroundPackage(TARGET_PACKAGE, TARGET_PACKAGE, 100L)
        assertEquals(5_000L, controller.foregroundQuietRemainingMs(100L))
        assertEquals(1L, controller.foregroundQuietRemainingMs(5_099L))
        assertEquals(0L, controller.foregroundQuietRemainingMs(5_100L))
        assertEquals(TargetAppReadinessStatus.UNKNOWN, controller.current().status)
        controller.observeForegroundPackage(TARGET_PACKAGE, "com.android.systemui", 5_200L)
        assertEquals(null, controller.foregroundQuietRemainingMs(5_200L))
    }

    @Test
    fun accountBrowseIsNotDelayedByAiReplyForegroundQuietProtection() {
        val controller = TargetAppReadinessController()
        val accountBrowse = readyEvidence().copy(runMode = RunMode.ACCOUNT_BROWSE)
        assertEquals(TargetAppReadinessStatus.READY, controller.evaluate(accountBrowse, 100L).status)
    }

    @Test
    fun leavingTargetForegroundResetsTheFiveSecondQuietWindow() {
        val controller = TargetAppReadinessController()
        assertEquals(TargetAppReadinessStatus.STARTING, controller.evaluate(readyEvidence(), 1_000L).status)
        controller.observeForegroundPackage(TARGET_PACKAGE, "com.android.systemui", 2_000L)
        assertEquals(TargetAppReadinessStatus.STARTING, controller.evaluate(readyEvidence(), 2_100L).status)
        assertEquals(TargetAppReadinessStatus.STARTING, controller.evaluate(readyEvidence(), 6_999L).status)
        assertEquals(TargetAppReadinessStatus.READY, controller.evaluate(readyEvidence(), 7_100L).status)
    }

    private fun readyEvidence() = TargetAppReadinessEvidence(
        targetPackage = TARGET_PACKAGE,
        foregroundPackage = TARGET_PACKAGE,
        activityClass = "com.immomo.momo.maintab.MaintabActivity",
        runMode = RunMode.AI_REPLY,
        windowId = 6,
        pageGeneration = 9L,
        activeWindowAvailable = true,
        businessPageReady = true,
        businessPageReason = "消息列表可操作"
    )

    private companion object {
        const val TARGET_PACKAGE = "com.immomo.momo"
    }
}
