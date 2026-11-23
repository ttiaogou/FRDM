package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.*
import com.freedom.core.runtime.*
import com.freedom.features.aireply.domain.message.*
import com.freedom.features.aireply.ports.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class VisibleDraftFlowTest {
    private class Fixture(
        private val sendPort: ChatDraftSendPort? = null,
        private val segmentRepository: ReplySegmentTaskRepository? = null,
        private val taskRepository: ReplyTaskRepository? = null
    ) {
        val tasks = mutableListOf<() -> Unit>()
        var active = true
        var consent = true
        var text = ""
        val inputWriteTargets = mutableListOf<ChatDraftInputTarget>()
        val logs = mutableListOf<String>()
        var modelOutput = """{"action":"REPLY","reason":"OK","draft":"你好呀"}"""
        var calls = 0
        var fail = false
        var focusRejectionsRemaining = 0
        var emptyFailuresRemaining = 0
        var emptyFinishReason: String? = "stop"
        var now = 0L
        val window = WindowRef("pkg", "activity", 7, 21)
        val snapshot = ChatPageSnapshot("pkg", "activity", ChatPageKind.CHAT_DETAIL_CANDIDATE,
            10, 1, 1, 1, 1, false, NodeTarget(window, NodePath(listOf(1))),
            NodeTarget(window, NodePath(listOf(2))), chatTitleText = "与测试对话", windowGeneration = 7)
        val batch = ChatMessageBatch(MessageReadStatus.SUCCESS,
            "pkg|21|7|conversation=${snapshot.conversationIdentityKey()}",
            listOf(ChatMessage("id", ChatMessageDirection.INCOMING, ChatMessageType.TEXT, "你好",
                ScreenRect(0, 10, 100, 30), DirectionEvidence.LAYOUT_ID_AND_HIERARCHY,
                MessageContentQuality.VERIFIED)), 1, 1, false, 1L,
            completeTailRowBounds = ScreenRect(0, 10, 100, 30))
        val coordinator = AiReplyDraftCoordinator(
            configurationRepository = object : AiModelConfigurationRepository {
                override fun load() = AiModelConfiguration(apiBaseUrl = "https://example.com/v1", modelName = "test", apiKey = "test")
                override fun save(configuration: AiModelConfiguration) = Unit
                override fun clearApiKey() = Unit
            },
            cloudConsent = AiModelCloudConsent { consent },
            personalizationRepository = object : AiReplyPersonalizationRepository {
                override fun load() = AiReplyPersonalization()
                override fun save(personalization: AiReplyPersonalization) = Unit
            },
            inputPort = object : ChatDraftInputPort {
                override fun writeDraft(target: ChatDraftInputTarget, expectedText: String, text: String): ChatDraftInputResult {
                    if (this@Fixture.text != expectedText) return ChatDraftInputResult(ChatDraftInputStatus.INPUT_NOT_EMPTY)
                    if (focusRejectionsRemaining > 0) {
                        focusRejectionsRemaining--
                        return ChatDraftInputResult(ChatDraftInputStatus.ACTION_REJECTED, "INPUT_FOCUS_REJECTED")
                    }
                    this@Fixture.inputWriteTargets.add(target)
                    this@Fixture.text = text
                    return ChatDraftInputResult(ChatDraftInputStatus.WRITTEN)
                }
                override fun verifyDraft(target: ChatDraftInputTarget, text: String) = ChatDraftInputResult(
                    if (this@Fixture.text == text) ChatDraftInputStatus.VERIFIED else ChatDraftInputStatus.TEXT_MISMATCH)
            },
            scheduler = object : TaskScheduler {
                override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
                    tasks.add(task)
                    return ScheduledTask { tasks.remove(task) }
                }
            },
            isAiReplyActive = { active }, onLog = { logs.add(it) },
            createProvider = { object : AiModelProvider {
                override fun generate(request: AiModelRequest): AiModelResult {
                    calls++
                    if (fail) error("simulated transport failure")
                    if (emptyFailuresRemaining > 0) {
                        emptyFailuresRemaining--
                        throw AiModelEmptyOutputException("BLANK", emptyFinishReason, 10, 0)
                    }
                    return AiModelResult(modelOutput, "fake", "fake", TokenUsage(10, 20), "stop")
                }
            } }, backgroundExecutor = Executor { it.run() }, nowMillis = { now }, chooseRandomInt = { min, _ -> min },
            sendPort = sendPort,
            replySegmentTaskRepository = segmentRepository,
            replyTaskRepository = taskRepository)
        fun drain() { while (tasks.isNotEmpty()) tasks.removeAt(0).invoke() }
        fun step() { assertTrue("Expected a scheduled task", tasks.isNotEmpty()); tasks.removeAt(0).invoke() }
    }

    private class SegmentLedger : ReplySegmentTaskRepository {
        val records = linkedMapOf<String, ReplySegmentTaskRecord>()
        override fun find(taskId: String) = records[taskId]
        override fun findByRequest(requestId: String) = records.values.filter { it.requestId == requestId }
        override fun findByConversation(conversationKey: String) = records.values.filter { it.conversationKey == conversationKey }
        override fun save(record: ReplySegmentTaskRecord) { records[record.taskId] = record }
        override fun update(record: ReplySegmentTaskRecord): Boolean {
            val before = records[record.taskId] ?: return false
            if (!ReplySegmentTaskTransitions.isAllowed(before.status, record.status)) return false
            records[record.taskId] = record
            return true
        }
    }

    private class TaskLedger : ReplyTaskRepository {
        val records = linkedMapOf<String, ReplyTaskRecord>()
        override fun find(requestKey: String) = records[requestKey]
        override fun save(record: ReplyTaskRecord) { records[record.requestKey] = record }
        override fun tryClaim(record: ReplyTaskRecord, requestTimeoutMillis: Long): Boolean {
            val previous = records[record.requestKey]
            if (previous != null && previous.status != ReplyTaskStatus.FAILED_RETRYABLE) return false
            records[record.requestKey] = record
            return true
        }
        override fun updateStatus(requestKey: String, requestId: String, status: ReplyTaskStatus, updatedAtMillis: Long): Boolean {
            val before = records[requestKey] ?: return false
            if (before.requestId != requestId || !ReplyTaskStatusTransitions.isAllowed(before.status, status)) return false
            records[requestKey] = before.copy(status = status, updatedAtMillis = updatedAtMillis)
            return true
        }
    }

    @Test fun ocrRecognitionToInputWithoutPlatformIdentityOrPreapproval() {
        val f = Fixture()
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        assertFalse(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(1, f.calls)
        assertEquals("你好呀", f.text)
        assertFalse(f.coordinator.isPending)
        assertFalse(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
    }

    @Test fun nonReplyDecisionLogsExplanationWithoutWritingOrLeakingPhoneNumber() {
        val f = Fixture()
        f.modelOutput = """{"action":"WAIT","reason":"FINANCIAL_TRANSACTION_REVIEW","reasonDetail":"涉及转账等高后果事项；停止自动发送。","draft":null}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        val log = f.logs.single { it.startsWith("AI 模型决策详情：") }
        assertTrue(log.contains("action=WAIT"))
        assertTrue(log.contains("reason=FINANCIAL_TRANSACTION_REVIEW"))
        assertTrue(log.contains("涉及转账等高后果事项"))
        assertTrue(log.contains("provider=fake"))
        assertTrue(log.contains("finishReason=stop"))
        assertTrue(log.contains("outputTokens=20"))
        assertFalse(log.contains("13800138000"))
        assertEquals("", f.text)
    }

    @Test fun modelWaitWithTechnicalLookingReasonNeverSchedulesTechnicalRetry() {
        val f = Fixture()
        f.modelOutput = """{"action":"WAIT","reason":"MODEL_CALL_FAILED","reasonDetail":"涉及转账，不自动回复。","draft":null}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(1, f.calls)
        assertEquals("", f.text)
        assertTrue(f.logs.any { it.contains("action=WAIT") && it.contains("reason=MODEL_CALL_FAILED") })
        assertFalse(f.logs.any { it.contains("AI 模型空输出有限恢复") })
        assertFalse(f.logs.any { it.startsWith("AI 程序执行状态：") })
    }

    @Test fun emptyModelOutputRetriesExactlyOnceAfterValidatedDelayAndThenWritesDraft() {
        val f = Fixture()
        f.emptyFailuresRemaining = 1
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        assertEquals(1, f.calls)
        assertTrue(f.coordinator.isPending)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertEquals("", f.text)
        assertTrue(f.logs.any { it.contains("delayMs=30000") })
        f.drain()
        assertEquals(2, f.calls)
        assertEquals("你好呀", f.text)
        assertTrue(f.coordinator.isConversationReturnBlocked)
    }

    @Test fun repeatedEmptyOutputStopsAfterOneScheduledRetryWithoutSending() {
        val f = Fixture()
        f.emptyFailuresRemaining = 2
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(2, f.calls)
        assertEquals("", f.text)
        assertFalse(f.coordinator.isPending)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertEquals(1, f.logs.count { it.contains("AI 模型空输出有限恢复") })
    }

    @Test fun emptyOutputWithLengthFinishReasonDoesNotRetry() {
        val f = Fixture()
        f.emptyFailuresRemaining = 1
        f.emptyFinishReason = "length"
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(1, f.calls)
        assertFalse(f.coordinator.isPending)
        assertEquals("", f.text)
    }

    @Test fun emptyOutputRetryDoesNotCallModelAfterComposerChangedOrReset() {
        for (reset in listOf(false, true)) {
            val f = Fixture()
            f.emptyFailuresRemaining = 1
            assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
            if (reset) f.coordinator.resetForRuntimeReset() else f.text = "用户输入"
            f.drain()
            assertEquals(1, f.calls)
            assertEquals(if (reset) "" else "用户输入", f.text)
        }
    }

    @Test fun transientModelFailureRetriesOnceAfterCooldown() {
        val f = Fixture(); f.fail = true
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        assertFalse(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.now = 30_000L
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.now = 60_000L
        assertFalse(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        assertEquals(2, f.calls)
        assertEquals("", f.text)
        assertTrue(f.logs.any { it.startsWith("AI 程序执行状态：") && it.contains("executionState=MODEL_CALL_FAILED") })
        assertFalse(f.logs.any { it.contains("action=WAIT") })
    }


    @Test fun verifiedFirstFocusFailureRetriesOnlyAfterFreshObservationAndSendsOnce() {
        var sends = 0
        val tasks = TaskLedger()
        val f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sends++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, taskRepository = tasks)
        f.focusRejectionsRemaining = 1
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals("", f.text)
        assertEquals(0, sends)
        assertEquals(ReplyTaskStatus.FAILED_RETRYABLE, tasks.records.values.single().status)
        assertTrue(f.logs.any { it.contains("DRAFT_INPUT_CONFIRMED_EMPTY_RETRYABLE") })

        f.now = 30_001L
        assertFalse(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        assertEquals(1, f.calls)
        val refreshed = f.batch.copy(capturedAtMillis = 2L)
        assertTrue(f.coordinator.generateVisibleDraft(refreshed, f.snapshot))
        f.drain()
        assertEquals(2, f.calls)
        assertEquals("你好呀", f.text)
        assertEquals(1, sends)
        // Never turn the same confirmed request into a second send.
        assertFalse(f.coordinator.generateVisibleDraft(refreshed.copy(capturedAtMillis = 3L), f.snapshot))
        assertEquals(1, sends)
    }

    @Test fun firstFocusRetryRejectsUserEditsAndNewOutgoingMessages() {
        val f = Fixture(taskRepository = TaskLedger())
        f.focusRejectionsRemaining = 1
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        f.now = 30_001L
        f.text = "人工输入"
        assertFalse(f.coordinator.generateVisibleDraft(f.batch.copy(capturedAtMillis = 2L), f.snapshot))
        assertEquals("人工输入", f.text)
        f.text = ""
        val outgoing = ChatMessage("new-outgoing", ChatMessageDirection.OUTGOING, ChatMessageType.TEXT,
            "已发送", ScreenRect(0, 0, 100, 9), DirectionEvidence.LAYOUT_ID_AND_HIERARCHY,
            MessageContentQuality.VERIFIED)
        assertFalse(f.coordinator.generateVisibleDraft(f.batch.copy(
            messages = f.batch.messages + outgoing, capturedAtMillis = 3L
        ), f.snapshot))
        assertEquals(1, f.calls)
        assertTrue(f.logs.any { it.contains("DRAFT_INPUT_RETRY_REQUIRES_FRESH_EMPTY_UNCHANGED_CHAT") })
    }

    @Test fun repeatedVerifiedFocusRejectionNeverLoopsOrResends() {
        var sends = 0
        val f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sends++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, taskRepository = TaskLedger())
        f.focusRejectionsRemaining = 3
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        f.now = 30_001L
        assertTrue(f.coordinator.generateVisibleDraft(f.batch.copy(capturedAtMillis = 2L), f.snapshot))
        f.drain()
        f.now = 60_002L
        assertFalse(f.coordinator.generateVisibleDraft(f.batch.copy(capturedAtMillis = 3L), f.snapshot))
        assertEquals(2, f.calls)
        assertEquals(0, sends)
        assertEquals("", f.text)
    }

    @Test fun staleModelCallbackAfterRunResetNeverWritesOrCreatesRetryProof() {
        val f = Fixture(taskRepository = TaskLedger())
        f.focusRejectionsRemaining = 1
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.coordinator.resetForRuntimeReset()
        f.drain()
        assertEquals("", f.text)
        assertFalse(f.logs.any { it.contains("DRAFT_INPUT_CONFIRMED_EMPTY_RETRYABLE") })
    }

    @Test fun existingInputIsPreserved() {
        val f = Fixture(); f.text = "人工输入"
        f.coordinator.generateVisibleDraft(f.batch, f.snapshot); f.drain()
        assertEquals("人工输入", f.text)
    }

    @Test fun stoppedOrResetRequestsCannotFillInput() {
        for (reset in listOf(false, true)) {
            val f = Fixture()
            f.coordinator.generateVisibleDraft(f.batch, f.snapshot)
            if (reset) f.coordinator.resetForRuntimeReset() else f.active = false
            f.drain()
            assertEquals("", f.text)
        }
    }

    @Test fun staleWindowAndMissingCloudConfigurationConsentDoNotCallModel() {
        val f = Fixture()
        assertFalse(f.coordinator.generateVisibleDraft(f.batch.copy(windowSessionKey = "old"), f.snapshot))
        f.consent = false
        assertFalse(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        assertEquals(0, f.calls)
    }

    @Test fun buttonNotReadyKeepsOriginalMessageAndRecoversWithoutAnotherModelCall() {
        var attempts = 0
        var buttonReady = false
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                attempts++
                return if (!buttonReady) ChatDraftInputResult(ChatDraftInputStatus.SEND_CONTROL_NOT_FOUND, "SEND_CONTROL_NOT_READY")
                else ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        repeat(8) { if (attempts == 0) f.step() }
        assertEquals(1, attempts)
        assertEquals(1, f.calls)
        assertEquals("你好呀", f.text)
        assertTrue(f.coordinator.isSendControlRecoveryPending)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertTrue(f.coordinator.isPending)
        assertEquals(ReplySegmentTaskStatus.PENDING_SEND, segments.records.values.single().status)
        assertEquals(ReplyTaskStatus.PENDING_SEND, tasks.records.values.single().status)

        // A new verified observation rebinds the pending segment, without regenerating the model.
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        assertEquals(1, f.calls)
        buttonReady = true
        f.step()
        assertEquals(2, attempts)
        assertFalse(f.coordinator.isSendControlRecoveryPending)
        assertFalse(f.coordinator.isPending)
        assertEquals(ReplySegmentTaskStatus.SENT_UNCONFIRMED, segments.records.values.single().status)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertEquals("你好呀", f.text)
    }

    @Test fun changedComposerIsNeverOverwrittenOrSentDuringPendingRecovery() {
        var attempts = 0
        val f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                attempts++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_CONTROL_NOT_FOUND, "SEND_CONTROL_NOT_READY")
            }
        })
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        repeat(8) { if (attempts == 0) f.step() }
        assertEquals(1, attempts)
        f.text = "人工修改"
        f.step()
        assertEquals(1, attempts)
        assertEquals("人工修改", f.text)
        assertTrue(f.coordinator.isSendControlRecoveryPending)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        f.coordinator.resetForRuntimeReset()
        f.step()
        assertEquals(1, attempts)
        assertFalse(f.coordinator.isConversationReturnBlocked)
    }

    @Test fun persistentPendingSendRestoresExactDraftWithoutModelRegeneration() {
        var firstAttempts = 0
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val first = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                firstAttempts++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_CONTROL_NOT_FOUND, "SEND_CONTROL_NOT_READY")
            }
        }, segments, tasks)
        assertTrue(first.coordinator.generateVisibleDraft(first.batch, first.snapshot))
        repeat(8) { if (firstAttempts == 0) first.step() }
        assertEquals(1, firstAttempts)
        assertEquals(ReplySegmentTaskStatus.PENDING_SEND, segments.records.values.single().status)
        first.coordinator.resetForRuntimeReset()

        var resumedAttempts = 0
        val second = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                resumedAttempts++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        second.text = "你好呀"
        assertTrue(second.coordinator.generateVisibleDraft(second.batch, second.snapshot))
        assertEquals(0, second.calls)
        assertTrue(second.coordinator.isSendControlRecoveryPending)
        second.step()
        assertEquals(1, resumedAttempts)
        assertEquals(ReplySegmentTaskStatus.SENT_UNCONFIRMED, segments.records.values.single().status)
        assertTrue(second.coordinator.isConversationReturnBlocked)
    }

    @Test fun secondMessageButtonPendingNeverReplaysTheFirstConfirmedMessage() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val sentTexts = mutableListOf<String>()
        var secondReady = false
        lateinit var f: Fixture
        f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                if (expectedText == "第二" && !secondReady) {
                    return ChatDraftInputResult(ChatDraftInputStatus.SEND_CONTROL_NOT_FOUND, "SEND_CONTROL_NOT_READY")
                }
                sentTexts += expectedText
                f.text = ""
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        f.modelOutput = """{"action":"REPLY","reason":"OK","draft":"第一\n第二"}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        repeat(8) { if (sentTexts.isEmpty()) f.step() }
        assertEquals(listOf("第一"), sentTexts)
        val firstOutgoing = ChatMessage("own-first", ChatMessageDirection.OUTGOING, ChatMessageType.TEXT,
            "第一", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val afterFirst = f.batch.copy(messages = listOf(firstOutgoing), capturedAtMillis = f.now + 1L)
        assertEquals(SendConfirmationResult.WAITING,
            f.coordinator.confirmSendResult(afterFirst, f.snapshot))
        assertEquals(listOf("第一"), sentTexts)
        assertTrue(f.coordinator.isSendConfirmationPending)
        assertEquals(ReplySegmentTaskStatus.CREATED, segments.records.values.last().status)
        // The original confirmation batch must never authorize another input.
        assertEquals(SendConfirmationResult.WAITING,
            f.coordinator.confirmSendResult(afterFirst, f.snapshot))
        assertTrue(f.tasks.isEmpty())
        val fresh = f.snapshot.copy(inputFieldTarget = NodeTarget(f.window, NodePath(listOf(88))))
        assertEquals(SendConfirmationResult.WAITING,
            f.coordinator.confirmSendResult(afterFirst.copy(capturedAtMillis = f.now + 2L), fresh))
        repeat(8) { if (!f.coordinator.isSendControlRecoveryPending) f.step() }
        assertTrue(f.coordinator.isSendControlRecoveryPending)
        assertEquals(listOf("第一"), sentTexts)
        assertEquals(ReplySegmentTaskStatus.SENT, segments.records.values.first().status)
        assertEquals(ReplySegmentTaskStatus.PENDING_SEND, segments.records.values.last().status)
        assertEquals(fresh.inputFieldTarget, f.inputWriteTargets.last().nodeTarget)
        secondReady = true
        f.step()
        assertEquals(listOf("第一", "第二"), sentTexts)
    }

    @Test fun identicalMessageSegmentsNeedTwoDistinctConfirmedOutgoingBubbles() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val sentTexts = mutableListOf<String>()
        lateinit var f: Fixture
        f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sentTexts += expectedText
                f.text = ""
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        f.modelOutput = """{"action":"REPLY","reason":"OK","draft":"一样\n一样"}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(listOf("一样"), sentTexts)
        val first = ChatMessage("own-one", ChatMessageDirection.OUTGOING, ChatMessageType.TEXT,
            "一样", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val afterFirst = f.batch.copy(messages = listOf(first), capturedAtMillis = 2L)
        assertEquals(SendConfirmationResult.WAITING, f.coordinator.confirmSendResult(afterFirst, f.snapshot))
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertEquals(ReplySegmentTaskStatus.CREATED, segments.records.values.last().status)
        assertEquals(SendConfirmationResult.WAITING,
            f.coordinator.confirmSendResult(afterFirst.copy(capturedAtMillis = 3L), f.snapshot))
        f.drain()
        assertEquals(listOf("一样", "一样"), sentTexts)
        assertEquals(ReplySegmentTaskStatus.SENT_UNCONFIRMED, segments.records.values.last().status)
        // The first bubble is already in the sent-message baseline, even with identical text.
        assertEquals(SendConfirmationResult.WAITING,
            f.coordinator.confirmSendResult(afterFirst.copy(capturedAtMillis = 4L), f.snapshot))
        assertTrue(f.coordinator.isConversationReturnBlocked)
        val second = first.copy(id = "own-two")
        assertEquals(SendConfirmationResult.CONFIRMED,
            f.coordinator.confirmSendResult(afterFirst.copy(messages = listOf(first, second), capturedAtMillis = 5L), f.snapshot))
        assertEquals(listOf(ReplySegmentTaskStatus.SENT, ReplySegmentTaskStatus.SENT),
            segments.records.values.map { it.status })
        assertFalse(f.coordinator.isConversationReturnBlocked)
        assertEquals(1, f.calls)
    }

    @Test fun nextMessageWaitsForVerifiedEmptyComposerAndCorrectChatAndResetCancelsIt() {
        val segments = SegmentLedger()
        lateinit var f: Fixture
        val sentTexts = mutableListOf<String>()
        f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sentTexts += expectedText
                f.text = ""
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, TaskLedger())
        f.modelOutput = """{"action":"REPLY","reason":"OK","draft":"一号\n二号"}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        val first = ChatMessage("first", ChatMessageDirection.OUTGOING, ChatMessageType.TEXT,
            "一号", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val afterFirst = f.batch.copy(messages = listOf(first), capturedAtMillis = 2L)
        assertEquals(SendConfirmationResult.WAITING, f.coordinator.confirmSendResult(afterFirst, f.snapshot))
        f.text = "人工草稿"
        assertEquals(SendConfirmationResult.WAITING,
            f.coordinator.confirmSendResult(afterFirst.copy(capturedAtMillis = 3L), f.snapshot))
        assertEquals(listOf("一号"), sentTexts)
        f.text = ""
        assertEquals(SendConfirmationResult.WAITING,
            f.coordinator.confirmSendResult(afterFirst.copy(capturedAtMillis = 4L),
                f.snapshot.copy(chatTitleText = "另一个聊天")))
        assertEquals(listOf("一号"), sentTexts)
        assertEquals(SendConfirmationResult.WAITING,
            f.coordinator.confirmSendResult(afterFirst.copy(capturedAtMillis = 5L), f.snapshot))
        f.coordinator.resetForRuntimeReset()
        f.drain()
        assertEquals(listOf("一号"), sentTexts)
        assertEquals(ReplySegmentTaskStatus.CREATED, segments.records.values.last().status)
    }

    @Test fun unknownFirstMessageCannotStartLaterSegments() {
        val sentTexts = mutableListOf<String>()
        lateinit var f: Fixture
        f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sentTexts += expectedText
                f.text = ""
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, SegmentLedger(), TaskLedger())
        f.modelOutput = """{"action":"REPLY","reason":"OK","draft":"先发\n再发"}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        val after = f.batch.copy(capturedAtMillis = 2L)
        assertEquals(SendConfirmationResult.WAITING, f.coordinator.confirmSendResult(after, f.snapshot))
        assertEquals(SendConfirmationResult.UNKNOWN, f.coordinator.confirmSendResult(after, f.snapshot))
        assertTrue(f.coordinator.isSendConfirmationPending)
        assertEquals(listOf("先发"), sentTexts)
        assertEquals(1, f.calls)
    }

    @Test fun rejectedSegmentPersistenceNeverAdvancesNextMessage() {
        val ledger = object : ReplySegmentTaskRepository {
            private val records = linkedMapOf<String, ReplySegmentTaskRecord>()
            override fun find(taskId: String) = records[taskId]
            override fun findByRequest(requestId: String) = records.values.filter { it.requestId == requestId }
            override fun findByConversation(conversationKey: String) = records.values.filter { it.conversationKey == conversationKey }
            override fun save(record: ReplySegmentTaskRecord) { records[record.taskId] = record }
            override fun update(record: ReplySegmentTaskRecord): Boolean {
                if (record.status == ReplySegmentTaskStatus.SENT) return false
                val current = records[record.taskId] ?: return false
                if (!ReplySegmentTaskTransitions.isAllowed(current.status, record.status)) return false
                records[record.taskId] = record
                return true
            }
        }
        val sentTexts = mutableListOf<String>()
        lateinit var f: Fixture
        f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sentTexts += expectedText
                f.text = ""
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, ledger, TaskLedger())
        f.modelOutput = """{"action":"REPLY","reason":"OK","draft":"第一\n第二"}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        val outgoing = ChatMessage("own", ChatMessageDirection.OUTGOING, ChatMessageType.TEXT,
            "第一", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        assertEquals(SendConfirmationResult.UNKNOWN,
            f.coordinator.confirmSendResult(f.batch.copy(messages = listOf(outgoing), capturedAtMillis = 2L), f.snapshot))
        f.drain()
        assertEquals(listOf("第一"), sentTexts)
    }

    @Test fun unknownSendOutcomeIsNeverResubmittedByPendingRecovery() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        var firstAttempts = 0
        val first = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                firstAttempts++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_CONTROL_NOT_FOUND, "SEND_CONTROL_NOT_READY")
            }
        }, segments, tasks)
        assertTrue(first.coordinator.generateVisibleDraft(first.batch, first.snapshot))
        repeat(8) { if (firstAttempts == 0) first.step() }
        val record = segments.records.values.single()
        assertTrue(segments.update(record.copy(status = ReplySegmentTaskStatus.SENDING)))
        first.coordinator.resetForRuntimeReset()

        var attempted = false
        val second = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                attempted = true
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        second.text = "你好呀"
        second.coordinator.generateVisibleDraft(second.batch, second.snapshot)
        assertFalse(second.coordinator.isSendControlRecoveryPending)
        assertFalse(attempted)
    }

    @Test fun returnGateStaysClosedAfterModelFailureAndResetsOnRuntimeReset() {
        val f = Fixture()
        f.fail = true
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        assertFalse(f.coordinator.isPending)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        f.coordinator.resetForRuntimeReset()
        assertFalse(f.coordinator.isConversationReturnBlocked)
    }

    @Test fun returnGateStaysClosedAfterDraftInputButSendIsUnavailable() {
        val f = Fixture()
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertFalse(f.coordinator.isPending)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertTrue(f.logs.any { it.contains("DRAFT_SEND_SEND_PORT_UNAVAILABLE") })
    }

    @Test fun returnGateOpensOnlyAfterNewOutgoingBubbleConfirmsSend() {
        val f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String) =
                ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
        })
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertFalse(f.coordinator.isPending)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        val outgoing = ChatMessage("new-outgoing", ChatMessageDirection.OUTGOING,
            ChatMessageType.TEXT, "你好呀", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val afterSend = f.batch.copy(messages = listOf(outgoing), capturedAtMillis = f.now + 1L)
        assertEquals(SendConfirmationResult.CONFIRMED, f.coordinator.confirmSendResult(afterSend, f.snapshot))
        assertFalse(f.coordinator.isConversationReturnBlocked)
    }


}
