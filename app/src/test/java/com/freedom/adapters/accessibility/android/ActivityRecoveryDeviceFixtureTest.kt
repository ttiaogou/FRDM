package com.freedom.adapters.accessibility.android

import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRecoveryDeviceFixtureTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("recovery/" + name)) {
            "Missing real-device recovery fixture: " + name
        }.bufferedReader().use { it.readText() }

    @Test
    fun processRestartShowsBusinessProofWithMissingActivityAndSafeRejection() {
        val log = fixture("device-process-restart-033243.txt")
        assertTrue(log.contains("PROCESS_RESTART_CHECKPOINT"))
        assertTrue(log.contains("SERVICE_RECONNECT_CHECKPOINT"))
        assertTrue(log.contains("RECOVERY_VERIFIED"))
        assertTrue(log.contains("activity=UNKNOWN"))
        assertTrue(log.contains("APP_NOT_READY"))
    }

    @Test
    fun reconnectEvidenceIncludesLegitimateQuietPeriodAndLaterGestureCancellation() {
        val log = fixture("device-service-reconnect-031120.txt")
        assertTrue(log.contains("STARTING->READY"))
        assertTrue(log.contains("列表滚动被系统取消"))
    }

    /** Sanitized real-device milestones: their order distinguishes observation from resumed work. */
    private fun assertMilestonesInOrder(log: String, vararg milestones: String) {
        var offset = 0
        for (milestone in milestones) {
            val next = log.indexOf(milestone, offset)
            assertTrue("Missing or out-of-order real-device milestone: $milestone", next >= 0)
            offset = next + milestone.length
        }
    }

    @Test
    fun realDeviceServiceReconnectionReacquiresLeaseAndContinuesAutonomousWork() {
        val log = fixture("device-service-reconnect-success-163046.txt")
        assertMilestonesInOrder(
            log,
            "事件=DISCONNECTED；runGeneration=2",
            "任务协调[RELEASED]：task=AI_REPLY_PRIVATE_MESSAGES",
            "事件=DESTROYED；runGeneration=3",
            "事件=RESET；mode=ACCOUNT_BROWSE；reason=AI 模式恢复；runGeneration=4",
            "任务协调[OWNED]：task=AI_REPLY_PRIVATE_MESSAGES；reason=启动私人消息任务；runGeneration=5",
            "TYPE_WINDOW_STATE_CHANGED；事件类型值=32；来源应用=com.immomo.momo；原始控件类名=com.immomo.momo.maintab.MaintabActivity",
            "STARTING->READY",
            "outcome=VerificationSample",
            "outcome=ResumeRequested",
            "任务协调[OWNED]：task=AI_REPLY_PRIVATE_MESSAGES；reason=统一页面路由双快照验证完成；runGeneration=5",
            "消息列表检查点恢复[CONTINUE]",
            "RECOVERY_EXECUTION_READY",
            "手势结束：id=momo-session-scroll-1795336231650；类型=SWIPE；状态=COMPLETED"
        )
        assertTrue("No stale lease rejection is expected in this verified reconnect", !log.contains("任务协调[REJECTED]"))
    }

    @Test
    fun realDeviceProcessRestartTabsStayReadOnlyUntilFreshChatActivityAndListActivity() {
        val log = fixture("device-process-restart-activity-navigation-163324.txt")
        assertMilestonesInOrder(
            log,
            "当前 Activity=未知",
            "maintab_layout_profile",
            "消息列表统一检查点[ROUTE_WAIT]",
            "maintab_layout_chat",
            "APP_NOT_READY",
            "maintab_layout_nearby",
            "消息列表统一检查点[ROUTE_WAIT]",
            "TYPE_WINDOW_STATE_CHANGED；事件类型值=32；来源应用=com.immomo.momo；原始控件类名=com.immomo.momo.message.activity.ChatActivity",
            "TYPE_WINDOW_STATE_CHANGED；事件类型值=32；来源应用=com.immomo.momo；原始控件类名=com.immomo.momo.maintab.MaintabActivity",
            "UNKNOWN->READY",
            "outcome=ResumeRequested",
            "任务协调[OWNED]：task=AI_REPLY_PRIVATE_MESSAGES",
            "消息列表检查点恢复[REANCHOR]",
            "RECOVERY_EXECUTION_READY",
            "手势结束：id=momo-session-scroll-1795336397007；类型=SWIPE；状态=COMPLETED"
        )
        val beforeActivityReturn = log.substringBefore(
            "TYPE_WINDOW_STATE_CHANGED；事件类型值=32；来源应用=com.immomo.momo；原始控件类名=com.immomo.momo.maintab.MaintabActivity"
        )
        assertTrue("Tabs and content changes cannot authorize recovery", !beforeActivityReturn.contains("RECOVERY_EXECUTION_READY"))
    }

    @Test
    fun adbInducedCrashesArePreservedAsExperimentalStimulusNotBusinessFailures() {
        val log = fixture("device-adb-crashes-031122.txt")
        assertTrue(log.contains("CrashedByAdbException"))
        assertTrue(log.contains("shell-induced crash"))
    }
}
