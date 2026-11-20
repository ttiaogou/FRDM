package com.freedom.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogBudgetPolicyTest {
    @Test
    fun truncateUtf8NeverExceedsLimitForChineseText() {
        val value = "自由运行日志诊断".repeat(100)
        val result = RunLogBudgetPolicy.truncateUtf8(value, 97)

        assertTrue(RunLogBudgetPolicy.utf8Size(result) <= 97)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.code in 0x4E00..0x9FFF })
    }

    @Test
    fun truncateUtf8DoesNotSplitSurrogatePair() {
        val value = "A😀B"
        val result = RunLogBudgetPolicy.truncateUtf8(value, 5)

        assertEquals("A😀", result)
        assertTrue(RunLogBudgetPolicy.utf8Size(result) <= 5)
    }

    @Test
    fun redactSensitivePayloadKeepsFieldNameButHidesBody() {
        val value = "消息正文=这是用户的私密消息；result=FAILED"
        val result = RunLogBudgetPolicy.redactSensitivePayload(value)

        assertTrue(result.contains("消息正文=<REDACTED>"))
        assertTrue(!result.contains("这是用户的私密消息"))
        assertTrue(result.contains("result=FAILED"))
    }



    @Test
    fun structuredNodeEvidenceLongerThanLegacyFourKbLimitIsPreserved() {
        val value = "AI 模式节点树明细：" + "node-evidence/0/1/2;class=TextView;flags=visible || ".repeat(90)
        val event = RunLogBudgetPolicy.toEvent(
            message = value,
            runId = "RUN_NODE_TREE",
            sequence = 1,
            timestampEpochMs = 1,
            elapsedRealtimeMs = 1
        )

        assertEquals(value, event.message)
        assertTrue(RunLogBudgetPolicy.utf8Size(event.message) > 4 * 1024)
        assertTrue(RunLogBudgetPolicy.utf8Size(event.message) < RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES)
    }

    @Test
    fun sampleArchiveBudgetsAllowMoreRuntimeLogsAndNodeTrees() {
        assertTrue(RunLogBudgetPolicy.MAX_SAMPLE_RUNTIME_LOG_RECORD_BYTES >= 32 * 1024)
        assertTrue(RunLogBudgetPolicy.MAX_SAMPLE_RUNTIME_LOG_BYTES >= 8 * 1024 * 1024)
        assertTrue(RunLogBudgetPolicy.MAX_SAMPLE_RUNTIME_LOG_ENTRIES >= 2_500)
        assertTrue(RunLogBudgetPolicy.MAX_SAMPLE_NODE_TREE_SAMPLES >= 64)
        assertEquals(16, RunLogBudgetPolicy.MAX_SAMPLE_STANDARD_NODE_TREE_SAMPLES)
    }

    @Test
    fun successfulReadOnlyOcrSummaryWithZeroFailuresRemainsInfo() {
        val message = "收到招呼只读 OCR 采集：样本=1；读取状态=SUCCESS；消息行=1；" +
            "OCR目标=1；已识别=1；失败=0；截断=false；唯一身份=已验证；未调用 AI 消息回调，不输入、不发送"
        assertEquals(RunLogLevel.INFO, RunLogBudgetPolicy.inferLevel(message))
        assertEquals(RunLogLevel.INFO, RunLogBudgetPolicy.inferLevel("OCR采集汇总：OCR失败区域=0"))
        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel("诊断记录：截断=true"))
    }

    @Test
    fun nonZeroFailureCounterStillProducesError() {
        val message = "收到招呼只读 OCR 采集：读取状态=PARTIAL；已识别=1；失败=1；截断=false"
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel(message))
    }

    @Test
    fun asynchronousReceivedGreetingsNavigationWaitIsInfoNotFalseError() {
        val message = "收到的招呼导航：入口点击已提交，等待异步进入 NewSayHiCardActivity；" +
            "当前过渡 Activity=NewSayHiSessionListActivity；elapsed=1370ms；不执行主聊天列表身份失败判定"
        assertEquals(RunLogLevel.INFO, RunLogBudgetPolicy.inferLevel(message))
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel(
            "$message；页面切换失败：IllegalStateException"
        ))
    }

    @Test
    fun exhaustedSnapshotRecoveryIsWarningWhileIntermediateRetriesRemainInfo() {
        assertEquals(RunLogLevel.INFO, RunLogBudgetPolicy.inferLevel(
            "AI 页面观察[SNAPSHOT_RECOVERY]：页面快照为空；retry=1/3；350ms 后重新获取"
        ))
        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel(
            "AI 页面观察[SNAPSHOT_RECOVERY_EXHAUSTED]：连续 3 次快照恢复仍为空"
        ))
    }

    @Test
    fun completedGestureWithCallbackDrainTimeoutFalseRemainsInfo() {
        assertEquals(
            RunLogLevel.INFO,
            RunLogBudgetPolicy.inferLevel(
                "手势平台终态已确认；platformResult=COMPLETED；businessResult=COMPLETED；callbackAfterDrainTimeout=false"
            )
        )
        assertEquals(
            RunLogLevel.WARN,
            RunLogBudgetPolicy.inferLevel("手势回调等待；callbackAfterDrainTimeout=true")
        )
    }


    @Test
    fun observationQualityStatesAreWarningsRatherThanRuntimeErrors() {
        val directionUnknown = "AI 模式消息读取：状态=DIRECTION_UNKNOWN；方向不明=7；" +
            "OCR失败区域=0；最新对方文字消息=无/不确定"
        val partialOcr = "AI 模式消息读取：状态=OCR_PARTIAL；方向不明=6；" +
            "OCR已识别区域=6；OCR失败区域=1；OCR状态=部分 OCR 目标未产生有效文字或文本框"

        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel(directionUnknown))
        assertEquals(RunLogCategory.DATA_QUALITY, RunLogBudgetPolicy.inferCategory(directionUnknown))
        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel(partialOcr))
        assertEquals(RunLogCategory.DATA_QUALITY, RunLogBudgetPolicy.inferCategory(partialOcr))
    }

    @Test
    fun receivedGreetingsNavigationPauseAfterTimeoutIsWarning() {
        val message = "收到的招呼导航暂停：异步页面切换等待失败或超时；不把中间页面当作消息列表"

        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel(message))
        assertEquals(RunLogCategory.NAVIGATION, RunLogBudgetPolicy.inferCategory(message))
    }

    @Test
    fun concreteExceptionStillOverridesObservationQualityWarning() {
        assertEquals(
            RunLogLevel.ERROR,
            RunLogBudgetPolicy.inferLevel("AI 模式消息读取：状态=OCR_PARTIAL；OCR解析异常：IllegalStateException")
        )
    }

    @Test
    fun explicitErrorSeverityIsNotDowngradedWithObservationQualityText() {
        val message = "[ERROR] AI 模式消息读取：状态=DIRECTION_UNKNOWN；OCR失败区域=0"
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel(message))
    }

    @Test
    fun inferLevelAndCategoryAreDeterministic() {
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel("OCR 解析失败：IllegalStateException"))
        assertEquals(RunLogCategory.ERROR, RunLogBudgetPolicy.inferCategory("节点检索失败"))
        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel("事件提纯[DEDUP]"))
    }
}
