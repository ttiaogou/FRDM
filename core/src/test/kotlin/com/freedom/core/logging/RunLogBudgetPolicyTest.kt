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
    fun bothAiReplyBusinessesMapModelAndSendFieldsToStructuredEventMetadata() {
        for (task in listOf("AI_REPLY_PRIVATE_MESSAGES", "AI_REPLY_RECEIVED_GREETINGS")) {
            val requestId = "12345678-1234-1234-1234-123456789abc"
            val modelLog = "AI 模型生命周期：component=MODEL；requestId=$requestId；" +
                "taskId=$task；generation=12；eventType=MODEL_REQUEST_FINISHED；" +
                "retryState=SUCCEEDED；reasonCode=MODEL_DECISION_REPLY；finalState=SUCCEEDED"
            val sendLog = "AI 消息发送链路：component=SEND；requestId=$requestId；" +
                "taskId=$task；generation=12；eventType=MESSAGE_SEND_CONFIRMED；" +
                "segmentIndex=0；sendStatus=CONFIRMED"

            val model = RunLogBudgetPolicy.toEvent(modelLog, "RUN_01", 1, 100, 200)
            val send = RunLogBudgetPolicy.toEvent(sendLog, "RUN_01", 2, 101, 201)

            assertEquals("RUN_01", model.runId)
            assertEquals("RUN_01", send.runId)
            assertEquals(task, model.taskId)
            assertEquals(task, send.taskId)
            assertEquals(requestId, model.traceId)
            assertEquals(model.traceId, send.traceId)
            assertEquals("MODEL_REQUEST_FINISHED", model.eventType)
            assertEquals("MESSAGE_SEND_CONFIRMED", send.eventType)
            assertEquals("AI 模型生命周期", model.component)
            assertEquals("AI 消息发送链路", send.component)
            assertEquals(modelLog, model.message)
            assertEquals(sendLog, send.message)
        }
    }

    @Test
    fun aiTraceUnknownTaskKeepsRunIdentityButStillLinksByRequestId() {
        val log = "AI 消息发送链路：component=SEND；requestId=request-123；" +
            "taskId=UNKNOWN；eventType=MESSAGE_SEND_REHYDRATED；sendAttemptOrigin=RECOVERED_SYNTHETIC"
        val event = RunLogBudgetPolicy.toEvent(log, "RUN_RECOVERY", 3, 4, 5)
        assertEquals("RUN_RECOVERY", event.taskId)
        assertEquals("request-123", event.traceId)
        assertEquals("MESSAGE_SEND_REHYDRATED", event.eventType)
    }

    @Test
    fun legacyEventsKeepOriginalActionIdAndTextInferenceWithoutAiTracePrefix() {
        val log = "正常任务：eventType=NOT_A_TRACE；requestId=foreign-value；" +
            "taskId=AI_REPLY_PRIVATE_MESSAGES；actionId=gesture-123"
        val event = RunLogBudgetPolicy.toEvent(log, "RUN_LEGACY", 1, 1, 1)
        assertEquals("RUN_LEGACY", event.taskId)
        assertEquals("gesture-123", event.traceId)
        assertEquals("gesture-123", event.actionId)
        // Legacy substring matching of "Type=" inside "eventType=" is unchanged.
        assertEquals("NOT_A_TRACE", event.eventType)
        assertEquals(log, event.message)
    }

    @Test
    fun malformedOrConflictingAiTraceFieldsCannotOverwriteRunMetadata() {
        val prefix = "AI 模型生命周期：component=MODEL；taskId=AI_REPLY_PRIVATE_MESSAGES；"
        val duplicated = prefix + "requestId=first；requestId=second；eventType=MODEL_REQUEST_FINISHED"
        val invalid = prefix + "requestId=secret@example.com；eventType=MODEL_REQUEST_FINISHED"
        val wrongComponent = "AI 模型生命周期：component=SEND；requestId=forged；" +
            "taskId=AI_REPLY_PRIVATE_MESSAGES；eventType=MODEL_REQUEST_FINISHED"
        val wrongEvent = prefix + "requestId=valid-123；eventType=MESSAGE_SEND_COMPLETED"
        for (log in listOf(duplicated, invalid)) {
            val event = RunLogBudgetPolicy.toEvent(log, "RUN_GUARD", 1, 1, 1)
            assertEquals("RUN_GUARD", event.traceId)
            assertEquals("AI_REPLY_PRIVATE_MESSAGES", event.taskId)
            assertEquals("MODEL_REQUEST_FINISHED", event.eventType)
        }
        val spoofed = RunLogBudgetPolicy.toEvent(wrongComponent, "RUN_GUARD", 2, 2, 2)
        assertEquals("RUN_GUARD", spoofed.traceId)
        assertEquals("RUN_GUARD", spoofed.taskId)
        assertEquals("MODEL_REQUEST_FINISHED", spoofed.eventType)
        val crossed = RunLogBudgetPolicy.toEvent(wrongEvent, "RUN_GUARD", 3, 3, 3)
        assertEquals("valid-123", crossed.traceId)
        assertEquals("MESSAGE_SEND_COMPLETED", crossed.eventType)
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
    fun privateRowObservationWarningsDoNotHideRealFailures() {
        val retry = "AI 私聊异常会话[INCOMPLETE_UNREAD_RETRYABLE]：unresolved=1；cacheWrite=false；保持检查点"
        val blocked = "AI 私聊异常会话[ROW_SCOPED_BLOCK]：仅隔离待复核会话；禁止提前完成本轮"
        val relocation = "AI 私聊异常会话[RELOCATION_WAIT]：已确认顶部但原异常未解决；按现有退避等待下一次新鲜列表观察"
        for (message in listOf(retry, blocked, relocation)) {
            assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel(message))
            assertEquals(RunLogCategory.DATA_QUALITY, RunLogBudgetPolicy.inferCategory(message))
            assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel("$message；读取失败"))
            assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel("$message；IllegalStateException"))
            assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel("$message；写入失败"))
            assertEquals(RunLogLevel.FATAL, RunLogBudgetPolicy.inferLevel("$message；FATAL"))
        }
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel("AI 私聊异常会话[OTHER]：异常"))
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
    fun identityObserveWaitingWithNegatedFailureIsInfoWithoutHidingRealFailures() {
        val waiting = "AI 联系人身份采集[OBSERVE_WAITING]：事务仍在进行；不返回失败原因"
        val event = RunLogBudgetPolicy.toEvent(
            waiting, "RUN_IDENTITY_WAIT", 1, 1, 1
        )
        assertEquals(RunLogLevel.INFO, event.level)
        assertEquals(null, event.result)
        assertTrue(RunLogBudgetPolicy.isBenignIdentityObservationWait(waiting))
        // The negation must not suppress real failures or other uncertain observations.
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel(
            "$waiting；资料页读取失败：IllegalStateException"
        ))
        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel(
            "$waiting；状态=UNKNOWN"
        ))
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel(
            "其他模块：不返回失败原因"
        ))
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel(
            "AI 联系人身份采集[OBSERVE_WAITING]：资料页读取失败"
        ))
    }

    @Test
    fun routineReadOnlyReadinessWaitIsInfoButActualRejectionAndFailuresRemainVisible() {
        val waiting = "目标应用就绪状态[TRANSITION]：READY->STARTING；mode=AI_REPLY；" +
            "package=com.immomo.momo；activity=UNKNOWN；windowId=3083；pageGeneration=24；" +
            "reason=当前 Activity 身份尚未确认"
        assertEquals(RunLogLevel.INFO, RunLogBudgetPolicy.inferLevel(waiting))
        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel(
            "自主手势派发拒绝[APP_NOT_READY]：status=STARTING；mode=AI_REPLY；windowId=3083；reason=当前 Activity 身份尚未确认"
        ))
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel("$waiting；读取失败"))
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel("$waiting；读取异常：IllegalStateException"))
        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel("$waiting；TIMEOUT"))
        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel(
            waiting.replace("READY->STARTING", "READY->BLOCKED")
        ))
    }

    @Test
    fun inferLevelAndCategoryAreDeterministic() {
        assertEquals(RunLogLevel.ERROR, RunLogBudgetPolicy.inferLevel("OCR 解析失败：IllegalStateException"))
        assertEquals(RunLogCategory.ERROR, RunLogBudgetPolicy.inferCategory("节点检索失败"))
        assertEquals(RunLogLevel.WARN, RunLogBudgetPolicy.inferLevel("事件提纯[DEDUP]"))
    }
}
