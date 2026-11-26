package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.*
import com.freedom.core.runtime.*
import com.freedom.features.aireply.domain.message.*
import com.freedom.features.aireply.ports.*
import com.freedom.features.aireply.policy.AiReplyPolicyRegistry
import com.freedom.features.aireply.policy.PolicyRegistry
import com.freedom.features.conversation.domain.message.StoredConversationMessage
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class VisibleDraftFlowTest {
    private class Fixture(
        private val sendPort: ChatDraftSendPort? = null,
        private val segmentRepository: ReplySegmentTaskRepository? = null,
        private val taskRepository: ReplyTaskRepository? = null,
        private val policyRegistry: PolicyRegistry = AiReplyPolicyRegistry
    ) {
        val tasks = mutableListOf<() -> Unit>()
        val scheduledDelays = mutableListOf<Long>()
        val requests = mutableListOf<AiModelRequest>()
        val decisionRequests = mutableListOf<ModelDecisionRequest>()
        var configuredProvider = AiModelServiceProvider.DEEPSEEK
        var active = true
        var consent = true
        var text = ""
        val inputWriteTargets = mutableListOf<ChatDraftInputTarget>()
        val logs = mutableListOf<String>()
        val preSendFailures = mutableListOf<AiReplyPreSendModelFailure>()
        var completionObservationRequests = 0
        var configuredModelName = "test"
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
                override fun load() = AiModelConfiguration(apiBaseUrl = "https://example.com/v1", modelName = configuredModelName,
                    apiKey = "test", provider = configuredProvider)
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
                    scheduledDelays.add(delayMillis)
                    tasks.add(task)
                    return ScheduledTask { tasks.remove(task) }
                }
            },
            isAiReplyActive = { active }, onLog = { logs.add(it) },
            createGateway = { EngineBackedAiDecisionGateway(object : AiModelProvider {
                override fun generate(request: AiModelRequest): AiModelResult {
                    calls++
                    requests.add(request)
                    if (fail) error("simulated transport failure")
                    if (emptyFailuresRemaining > 0) {
                        emptyFailuresRemaining--
                        throw AiModelEmptyOutputException("BLANK", emptyFinishReason, 10, 0)
                    }
                    return AiModelResult(modelOutput, "fake", "fake", TokenUsage(10, 20), "stop")
                }
            }, AiModelCloudConsent { consent }).let { gateway ->
                object : AiDecisionGateway {
                    override fun decide(request: ModelDecisionRequest): ModelDecisionOutcome {
                        decisionRequests.add(request)
                        return gateway.decide(request)
                    }
                }
            } }, backgroundExecutor = Executor { it.run() }, nowMillis = { now }, chooseRandomInt = { min, _ -> min },
            sendPort = sendPort,
            replySegmentTaskRepository = segmentRepository,
            replyTaskRepository = taskRepository,
            onPreSendModelFailure = { preSendFailures.add(it) },
            policyRegistry = policyRegistry,
            onSendConfirmationObservationRequested = { completionObservationRequests++ })
        fun drain() { while (tasks.isNotEmpty()) tasks.removeAt(0).invoke() }
        fun step() { assertTrue("Expected a scheduled task", tasks.isNotEmpty()); tasks.removeAt(0).invoke() }
    }

    private class SegmentLedger : ReplySegmentTaskRepository {
        val records = linkedMapOf<String, ReplySegmentTaskRecord>()
        var failUnknownUpdate = false
        override fun find(taskId: String) = records[taskId]
        override fun findByRequest(requestId: String) = records.values.filter { it.requestId == requestId }
        override fun findByConversation(conversationKey: String) = records.values.filter { it.conversationKey == conversationKey }
        override fun save(record: ReplySegmentTaskRecord) { records[record.taskId] = record }
        override fun update(record: ReplySegmentTaskRecord): Boolean {
            if (failUnknownUpdate && record.status == ReplySegmentTaskStatus.UNKNOWN) error("journal temporarily unavailable")
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

    @Test fun policyRegistryRejectsUnknownAndMismatchedTaskBeforeModelOrInput() {
        val unknown = Fixture()
        assertFalse(unknown.coordinator.generateVisibleDraft(
            unknown.batch, unknown.snapshot, taskId = "UNREGISTERED_TASK"
        ))
        assertEquals(0, unknown.calls)
        assertEquals("", unknown.text)
        assertTrue(unknown.decisionRequests.isEmpty())

        val misregistered = Fixture(policyRegistry = object : PolicyRegistry {
            override fun resolve(taskId: String) = com.freedom.features.aireply.policy.ReceivedGreetingsPolicy
        })
        assertFalse(misregistered.coordinator.generateVisibleDraft(
            misregistered.batch, misregistered.snapshot, taskId = AiDecisionTaskIds.PRIVATE_MESSAGES
        ))
        assertEquals(0, misregistered.calls)
        assertEquals("", misregistered.text)
        assertTrue(misregistered.decisionRequests.isEmpty())
    }

    @Test fun registryForbidsGreetingHistoryAndPreservesVerifiedPrivateHistory() {
        val history = VerifiedDraftHistory(
            accountKey = "",
            contactId = "contact-verified",
            conversationId = "momo-direct-contact-verified",
            lookupSource = "LOCAL_PLATFORM_CONTACT",
            messages = listOf(
                StoredConversationMessage(
                    recordId = "historical-row",
                    contactId = "contact-verified",
                    conversationId = "momo-direct-contact-verified",
                    messageFingerprint = "historical-logical-id",
                    direction = "INCOMING",
                    type = "TEXT",
                    content = "昨日的历史消息",
                    contentQuality = "VERIFIED",
                    directionEvidence = "LAYOUT_ID_AND_HIERARCHY",
                    bounds = ScreenRect(0, 20, 100, 40),
                    identityConfidence = "PLATFORM_ID_VERIFIED",
                    firstObservedAtMillis = 0L,
                    lastObservedAtMillis = 0L,
                    observationCount = 1
                )
            )
        )
        val greeting = Fixture()
        assertTrue(greeting.coordinator.generateVisibleDraft(
            greeting.batch, greeting.snapshot, history = history,
            taskId = AiDecisionTaskIds.RECEIVED_GREETINGS
        ))
        assertEquals(listOf("你好"), greeting.requests.single().conversation.map { it.content })
        assertEquals(ModelContextScope.CURRENT_WINDOW_ONLY,
            greeting.decisionRequests.single().constraints.contextScope)

        val privateMessage = Fixture()
        assertTrue(privateMessage.coordinator.generateVisibleDraft(
            privateMessage.batch, privateMessage.snapshot, history = history,
            taskId = AiDecisionTaskIds.PRIVATE_MESSAGES
        ))
        assertEquals(listOf("昨日的历史消息", "你好"),
            privateMessage.requests.single().conversation.map { it.content })
        assertEquals(ModelContextScope.VERIFIED_HISTORY_WITH_CURRENT_WINDOW,
            privateMessage.decisionRequests.single().constraints.contextScope)
        assertEquals(greeting.decisionRequests.single().outputContract,
            privateMessage.decisionRequests.single().outputContract)
    }

    private fun traceField(log: String, field: String): String =
        log.substringAfter("$field=").substringBefore("；")

    private fun traceKeys(log: String): Set<String> =
        log.substringAfter("：").split("；").map { it.substringBefore("=") }.toSet()

    @Test fun bothBusinessesShareTraceContractAndCorrelateModelToConfirmedSend() {
        for (taskId in listOf(AiDecisionTaskIds.PRIVATE_MESSAGES, AiDecisionTaskIds.RECEIVED_GREETINGS)) {
            var sent = 0
            val f = Fixture(object : ChatDraftSendPort {
                override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                    sent++
                    return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
                }
            })
            assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot, taskId = taskId))
            f.drain()
            val model = f.logs.single { it.startsWith("AI 模型生命周期：") &&
                traceField(it, "eventType") == "MODEL_REQUEST_FINISHED" }
            val submitted = f.logs.single { it.startsWith("AI 消息发送链路：") &&
                traceField(it, "eventType") == "MESSAGE_SEND_SUBMITTED" }
            assertEquals(traceKeys(model), traceKeys(submitted))
            assertEquals(taskId, traceField(model, "taskId"))
            assertEquals(taskId, traceField(submitted, "taskId"))
            assertEquals(traceField(model, "requestId"), traceField(submitted, "requestId"))
            assertEquals(traceField(model, "generation"), traceField(submitted, "generation"))
            assertEquals("SUCCEEDED", traceField(model, "finalState"))
            assertEquals("0", traceField(submitted, "segmentIndex"))
            assertEquals("1", traceField(submitted, "segmentCount"))
            assertEquals("SENT_UNCONFIRMED", traceField(submitted, "sendStatus"))
            assertEquals("ORIGINAL", traceField(submitted, "sendAttemptOrigin"))
            assertTrue(traceField(submitted, "sendAttemptId") != "UNKNOWN")
            assertEquals(1, sent)
            assertFalse(f.logs.any { it.contains("eventType=MESSAGE_SEND_COMPLETED") })
            val bubble = ChatMessage("confirmed-outgoing", ChatMessageDirection.OUTGOING,
                ChatMessageType.TEXT, "你好呀", ScreenRect(0, 10, 100, 30),
                DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
            val after = f.batch.copy(messages = listOf(bubble), capturedAtMillis = 2L)
            assertEquals(SendConfirmationResult.CONFIRMED,
                f.coordinator.confirmSendResult(after, f.snapshot))
            val sendEvents = f.logs.filter { it.startsWith("AI 消息发送链路：") }
            assertEquals(listOf("MESSAGE_SEND_SUBMITTED", "MESSAGE_SEND_CONFIRMED",
                "MESSAGE_SEND_COMPLETED"), sendEvents.map { traceField(it, "eventType") })
            assertTrue(sendEvents.all { traceField(it, "taskId") == taskId })
            assertTrue(sendEvents.all { traceField(it, "requestId") == traceField(model, "requestId") })
            assertEquals(traceField(submitted, "sendAttemptId"),
                traceField(sendEvents[1], "sendAttemptId"))
            assertEquals(traceField(sendEvents[1], "sendConfirmationId"),
                traceField(sendEvents[2], "sendConfirmationId"))
            assertTrue(traceField(sendEvents[2], "sendConfirmationId") != "UNKNOWN")
            assertEquals(SendConfirmationResult.NOT_PENDING,
                f.coordinator.confirmSendResult(after.copy(capturedAtMillis = 3L), f.snapshot))
            assertEquals(3, f.logs.count { it.startsWith("AI 消息发送链路：") })
        }
    }

    @Test fun multiMessageTraceCompletesOnlyAfterBothSegmentsAreConfirmed() {
        val segments = SegmentLedger()
        lateinit var f: Fixture
        f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                f.text = ""
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, TaskLedger())
        f.modelOutput = """{"action":"REPLY","reason":"OK","draft":"第一\n第二"}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        val first = ChatMessage("first-out", ChatMessageDirection.OUTGOING, ChatMessageType.TEXT,
            "第一", ScreenRect(0, 10, 100, 30), DirectionEvidence.LAYOUT_ID_AND_HIERARCHY,
            MessageContentQuality.VERIFIED)
        assertEquals(SendConfirmationResult.WAITING,
            f.coordinator.confirmSendResult(f.batch.copy(messages = listOf(first), capturedAtMillis = 2L), f.snapshot))
        assertEquals(0, f.logs.count { it.contains("eventType=MESSAGE_SEND_COMPLETED") })
        assertEquals(SendConfirmationResult.WAITING, f.coordinator.confirmSendResult(
            f.batch.copy(messages = listOf(first), capturedAtMillis = 3L), f.snapshot))
        f.drain()
        val second = first.copy(id = "second-out", content = "第二")
        assertEquals(SendConfirmationResult.CONFIRMED, f.coordinator.confirmSendResult(
            f.batch.copy(messages = listOf(first, second), capturedAtMillis = 4L), f.snapshot))
        val events = f.logs.filter { it.startsWith("AI 消息发送链路：") }
        val submitted = events.filter { traceField(it, "eventType") == "MESSAGE_SEND_SUBMITTED" }
        val confirmed = events.filter { traceField(it, "eventType") == "MESSAGE_SEND_CONFIRMED" }
        assertEquals(listOf("0", "1"), submitted.map { traceField(it, "segmentIndex") })
        assertEquals(listOf("0", "1"), confirmed.map { traceField(it, "segmentIndex") })
        assertEquals(1, events.count { traceField(it, "eventType") == "MESSAGE_SEND_COMPLETED" })
        assertEquals(1, events.map { traceField(it, "requestId") }.distinct().size)
        assertTrue(events.all { traceField(it, "segmentCount") == "2" })
        assertEquals(listOf(ReplySegmentTaskStatus.SENT, ReplySegmentTaskStatus.SENT),
            segments.records.values.map { it.status })
    }

    @Test fun unknownSendTraceNeverReportsOverallCompletionOrStartsNextSegment() {
        val sent = mutableListOf<String>()
        lateinit var f: Fixture
        f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sent += expectedText
                f.text = ""
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, SegmentLedger(), TaskLedger())
        f.modelOutput = """{"action":"REPLY","reason":"OK","draft":"先发\n再发"}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        val noBubble = f.batch.copy(capturedAtMillis = 2L)
        assertEquals(SendConfirmationResult.WAITING, f.coordinator.confirmSendResult(noBubble, f.snapshot))
        assertEquals(SendConfirmationResult.UNKNOWN, f.coordinator.confirmSendResult(noBubble, f.snapshot))
        // Rechecking UNKNOWN must not invent a second send receipt or silently advance.
        assertEquals(SendConfirmationResult.UNKNOWN, f.coordinator.confirmSendResult(noBubble, f.snapshot))
        val events = f.logs.filter { it.startsWith("AI 消息发送链路：") }
        assertEquals(1, events.count { traceField(it, "eventType") == "MESSAGE_SEND_UNCERTAIN" })
        assertEquals(0, events.count { traceField(it, "eventType") == "MESSAGE_SEND_COMPLETED" })
        assertEquals(0, events.count { traceField(it, "eventType") == "MESSAGE_SEND_CONFIRMED" })
        assertEquals("UNKNOWN", traceField(events.last(), "sendStatus"))
        assertTrue(f.logs.any { it.contains("eventType=MODEL_REQUEST_FINISHED") &&
            it.contains("finalState=SUCCEEDED") })
        assertEquals(listOf("先发"), sent)
    }

    @Test fun modelWaitHasNoSendTraceEvents() {
        val f = Fixture()
        f.modelOutput = """{"action":"WAIT","reason":"SAFE_SKIP","draft":null}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertTrue(f.logs.any { it.contains("eventType=MODEL_REQUEST_FINISHED") })
        assertFalse(f.logs.any { it.startsWith("AI 消息发送链路：") })
        assertEquals("", f.text)
    }

    @Test fun p3LogsNeverExposeFreeModelReasonModelNameOrConversationIdentity() {
        for (taskId in listOf(AiDecisionTaskIds.PRIVATE_MESSAGES, AiDecisionTaskIds.RECEIVED_GREETINGS)) {
            // Segment-creation diagnostics are emitted only when a segment repository is configured.
            val f = Fixture(segmentRepository = SegmentLedger())
            val modelSecret = "CUSTOM_MODEL_PRIVATE_KEY_987654321"
            val privateReason = "13800138000"
            val privateDetail = "user.private@example.org sk-private-secret"
            f.configuredModelName = modelSecret
            f.modelOutput = """{"action":"REPLY","reason":"$privateReason","reasonDetail":"$privateDetail","draft":"普通回复"}"""
            assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot, taskId = taskId))
            f.drain()
            val logs = f.logs.joinToString("\n")
            assertFalse(logs.contains(modelSecret))
            assertFalse(logs.contains(privateReason))
            assertFalse(logs.contains(privateDetail))
            assertFalse(logs.contains("sk-private-secret"))
            assertFalse(logs.contains(f.snapshot.conversationIdentityKey().orEmpty()))
            assertTrue(f.logs.any { it.startsWith("AI 模型调用开始：") && it.contains("modelHash=") })
            assertTrue(f.logs.any { it.startsWith("AI 模型调用完成：") && it.contains("reasonHash=") })
            assertTrue(f.logs.any { it.startsWith("AI 消息段任务已建立：") &&
                it.contains("conversationKeyHash=") })
            assertEquals("普通回复", f.text)
            assertEquals(1, f.calls)
        }
    }

    @Test fun p3InvalidModelProtocolNeverLeaksDraftOrStartsSend() {
        val f = Fixture()
        val secret = "raw_private_draft_payload"
        f.modelOutput = """{"action":"REPLY","reason":"OK","draft":"$secret","extra":"private"}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertFalse(f.logs.joinToString("\n").contains(secret))
        assertTrue(f.logs.any { it.contains("outcomeCategory=MODEL_OUTPUT_INVALID") &&
            it.contains("safeCode=JSON_SCHEMA_MISMATCH") })
        assertFalse(f.logs.any { it.startsWith("AI 消息发送链路：") })
        assertEquals("", f.text)
        assertEquals(1, f.calls)
    }

    @Test fun p3StructuredTraceRejectsMultilineIdentifiersAndKeepsBoundedSize() {
        val secret = "private@example.org\nAPI_KEY=sk-example-secret"
        val log = AiReplyTraceContract.format(
            component = "SEND", requestId = secret,
            taskId = AiDecisionTaskIds.PRIVATE_MESSAGES, generation = 11,
            eventType = "MESSAGE_SEND_UNCERTAIN", reasonCode = secret,
            sendAttemptId = secret, sendConfirmationId = secret, sendStatus = "UNKNOWN"
        )
        assertFalse(log.contains(secret))
        assertFalse(log.contains("sk-example-secret"))
        assertFalse(log.contains("\n"))
        assertEquals("UNKNOWN", traceField(log, "requestId"))
        assertEquals("UNKNOWN", traceField(log, "reasonCode"))
        assertTrue(log.length < 1024)
    }

    @Test fun registeredTasksOnlyReleaseSendGateAfterConfirmedBubbleWithoutDuplicateReceipt() {
        for (taskId in listOf(AiDecisionTaskIds.RECEIVED_GREETINGS, AiDecisionTaskIds.PRIVATE_MESSAGES)) {
            var sent = 0
            val f = Fixture(object : ChatDraftSendPort {
                override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                    sent++
                    return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
                }
            })
            assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot, taskId = taskId))
            f.drain()
            assertEquals(1, f.calls)
            assertEquals(1, sent)
            assertTrue(f.coordinator.isConversationReturnBlocked)
            val bubble = ChatMessage(
                "confirmed-outgoing", ChatMessageDirection.OUTGOING, ChatMessageType.TEXT,
                "你好呀", ScreenRect(0, 10, 100, 30),
                DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED
            )
            val afterSend = f.batch.copy(messages = listOf(bubble), capturedAtMillis = 2L)
            assertEquals(SendConfirmationResult.CONFIRMED,
                f.coordinator.confirmSendResult(afterSend, f.snapshot))
            assertFalse(f.coordinator.isConversationReturnBlocked)
            assertEquals(SendConfirmationResult.NOT_PENDING,
                f.coordinator.confirmSendResult(afterSend.copy(capturedAtMillis = 3L), f.snapshot))
            assertEquals(1, sent)
        }
    }

    @Test fun registryPreservesOriginalTaskAcrossEmptyModelOutputRecovery() {
        for (taskId in listOf(AiDecisionTaskIds.RECEIVED_GREETINGS, AiDecisionTaskIds.PRIVATE_MESSAGES)) {
            val f = Fixture()
            f.emptyFailuresRemaining = 3
            assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot, taskId = taskId))
            f.drain()
            assertEquals(3, f.calls)
            assertEquals(listOf(taskId, taskId, taskId), f.decisionRequests.map { it.taskId })
            assertEquals(listOf(ModelResponseMode.STRICT_JSON, ModelResponseMode.SIMPLE_JSON,
                ModelResponseMode.PLAIN_TEXT), f.decisionRequests.map { it.responseMode })
            assertEquals("", f.text)
        }
    }

    @Test fun greetingAndPrivateUseTheSameGatewayWithDistinctTaskIdentities() {
        val greeting = Fixture()
        assertTrue(greeting.coordinator.generateVisibleDraft(
            greeting.batch, greeting.snapshot, taskId = AiDecisionTaskIds.RECEIVED_GREETINGS
        ))
        assertEquals(1, greeting.decisionRequests.size)
        assertEquals(AiDecisionTaskIds.RECEIVED_GREETINGS, greeting.decisionRequests.single().taskId)
        assertEquals(ModelContextScope.CURRENT_WINDOW_ONLY,
            greeting.decisionRequests.single().constraints.contextScope)

        val privateReply = Fixture()
        assertTrue(privateReply.coordinator.generateVisibleDraft(
            privateReply.batch, privateReply.snapshot, taskId = AiDecisionTaskIds.PRIVATE_MESSAGES
        ))
        assertEquals(1, privateReply.decisionRequests.size)
        assertEquals(AiDecisionTaskIds.PRIVATE_MESSAGES, privateReply.decisionRequests.single().taskId)
        assertEquals(ModelContextScope.CURRENT_WINDOW_ONLY,
            privateReply.decisionRequests.single().constraints.contextScope)
        assertEquals(greeting.decisionRequests.single().outputContract,
            privateReply.decisionRequests.single().outputContract)
    }

    @Test fun deepSeekEmptyOutputRetryKeepsTheOriginalTaskIdentityAndStrictParsing() {
        val greeting = Fixture()
        greeting.emptyFailuresRemaining = 3
        assertTrue(greeting.coordinator.generateVisibleDraft(
            greeting.batch, greeting.snapshot, taskId = AiDecisionTaskIds.RECEIVED_GREETINGS
        ))
        greeting.drain()
        assertEquals(3, greeting.calls)
        assertEquals(3, greeting.decisionRequests.size)
        assertTrue(greeting.decisionRequests.all { it.taskId == AiDecisionTaskIds.RECEIVED_GREETINGS })
        assertEquals(
            listOf(ModelResponseMode.STRICT_JSON, ModelResponseMode.SIMPLE_JSON,
                ModelResponseMode.PLAIN_TEXT),
            greeting.decisionRequests.map { it.responseMode }
        )
        assertTrue(greeting.decisionRequests.all { it.outputContract == AiReplyModelContracts.currentOutput() })
        assertEquals("", greeting.text)
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
        assertTrue(log.contains("reasonHash="))
        assertFalse(log.contains("reason=FINANCIAL_TRANSACTION_REVIEW"))
        assertTrue(log.contains("reasonDetailPresent=true"))
        assertFalse(log.contains("涉及转账等高后果事项"))
        assertTrue(log.contains("provider=fake"))
        assertTrue(log.contains("finishReason=stop"))
        assertTrue(log.contains("outputTokens=20"))
        assertFalse(log.contains("13800138000"))
        assertEquals("", f.text)
    }

    @Test fun modelOutputCategoriesCoverValidAndInvalidDecisionsWithoutRawExplanation() {
        for ((output, category, action) in listOf(
            Triple("""{"action":"REPLY","reason":"OK","reasonDetail":"私人解释13800138000","draft":"你好呀"}""",
                "VALID_DECISION", "REPLY"),
            Triple("""{"action":"WAIT","reason":"OK","reasonDetail":"私人解释13800138000","draft":null}""",
                "VALID_DECISION", "WAIT"),
            Triple("""{"action":"DO_NOT_REPLY","reason":"OK","reasonDetail":"私人解释13800138000","draft":null}""",
                "VALID_DECISION", "DO_NOT_REPLY"),
            Triple("""{"action":"WAIT","reason":"OK","draft":"bad"}""",
                "MODEL_OUTPUT_INVALID", "")
        )) {
            val f = Fixture()
            f.modelOutput = output
            assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
            f.drain()
            val classification = f.logs.single { it.startsWith("AI 模型结果分类：") }
            assertTrue(classification.contains("outcomeCategory=$category"))
            assertTrue(classification.contains("taskId=${AiDecisionTaskIds.PRIVATE_MESSAGES}"))
            assertTrue(classification.contains("requestId="))
            if (action.isNotEmpty()) {
                assertTrue(classification.contains("action=$action"))
                assertTrue(f.logs.single { it.startsWith("AI 模型决策详情：") }.contains("action=$action"))
            } else assertTrue(classification.contains("safeCode=JSON_DRAFT_INVALID"))
            assertFalse(f.logs.joinToString("\n").contains("私人解释"))
            assertFalse(f.logs.joinToString("\n").contains("13800138000"))
        }
    }

    @Test fun emptyTransportOutputIsClassifiedWithoutChangingRetry() {
        val f = Fixture()
        f.emptyFailuresRemaining = 1
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        val categories = f.logs.filter { it.startsWith("AI 模型结果分类：") }
        assertEquals(2, categories.size)
        assertTrue(categories.first().contains("outcomeCategory=EMPTY_MODEL_OUTPUT"))
        assertTrue(categories.last().contains("outcomeCategory=VALID_DECISION"))
        assertEquals(2, f.calls)
    }

    private fun lifecycleEvents(f: Fixture): List<String> =
        f.logs.filter { it.startsWith("AI 模型生命周期：") }

    private fun lifecycleField(log: String, field: String): String =
        log.substringAfter("$field=").substringBefore("；")

    @Test fun modelLifecycleFirstTrySuccessHasOneTerminalAndNoRetry() {
        val f = Fixture()
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        val logs = lifecycleEvents(f)
        assertEquals(listOf("MODEL_ATTEMPT_STARTED", "MODEL_ATTEMPT_FINISHED", "MODEL_REQUEST_FINISHED"),
            logs.map { lifecycleField(it, "eventType") })
        assertTrue(logs.all { lifecycleField(it, "attemptIndex") == "0" })
        assertEquals("SUCCEEDED", lifecycleField(logs.last(), "finalState"))
        assertEquals("SUCCEEDED", lifecycleField(logs.last(), "retryState"))
        assertEquals(1, f.calls)
    }

    @Test fun modelLifecycleEmptyRecoveryExhaustionHasThreeAttemptsAndOneTerminal() {
        val f = Fixture()
        f.emptyFailuresRemaining = 3
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        val logs = lifecycleEvents(f)
        assertEquals(listOf(
            "MODEL_ATTEMPT_STARTED", "MODEL_ATTEMPT_FINISHED", "MODEL_RETRY_SCHEDULED",
            "MODEL_RETRY_EXECUTING", "MODEL_ATTEMPT_STARTED", "MODEL_ATTEMPT_FINISHED",
            "MODEL_RETRY_SCHEDULED", "MODEL_RETRY_EXECUTING", "MODEL_ATTEMPT_STARTED",
            "MODEL_ATTEMPT_FINISHED", "MODEL_REQUEST_FINISHED"
        ), logs.map { lifecycleField(it, "eventType") })
        assertEquals(listOf("0", "1", "2"), logs.filter {
            lifecycleField(it, "eventType") == "MODEL_ATTEMPT_STARTED"
        }.map { lifecycleField(it, "attemptIndex") })
        assertEquals(listOf("2500", "6000"), logs.filter {
            lifecycleField(it, "eventType") == "MODEL_RETRY_SCHEDULED"
        }.map { lifecycleField(it, "delayMs") })
        assertEquals("EXHAUSTED", lifecycleField(logs.last(), "retryState"))
        assertEquals("FAILED", lifecycleField(logs.last(), "finalState"))
        assertEquals(3, f.calls)
    }

    @Test fun modelLifecycleRecoverySuccessReusesRequestIdAndHasSingleSuccessTerminal() {
        val f = Fixture()
        f.emptyFailuresRemaining = 1
        assertTrue(f.coordinator.generateVisibleDraft(
            f.batch, f.snapshot, taskId = AiDecisionTaskIds.RECEIVED_GREETINGS))
        f.drain()
        val logs = lifecycleEvents(f)
        assertEquals(1, logs.count { lifecycleField(it, "eventType") == "MODEL_REQUEST_FINISHED" })
        assertEquals("SUCCEEDED", lifecycleField(logs.last(), "finalState"))
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", lifecycleField(logs.last(), "taskId"))
        assertEquals(1, logs.map { lifecycleField(it, "requestId") }.distinct().size)
        assertEquals(2, f.calls)
        assertEquals("你好呀", f.text)
    }

    @Test fun modelLifecycleComposerChangeCancelsWithoutAdditionalModelCall() {
        val f = Fixture()
        f.emptyFailuresRemaining = 1
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.text = "人工输入"
        f.drain()
        val logs = lifecycleEvents(f)
        assertEquals(1, logs.count { lifecycleField(it, "eventType") == "MODEL_RETRY_CANCELLED" })
        assertEquals("CANCELLED_CONTEXT_CHANGED", lifecycleField(logs.last(), "retryState"))
        assertEquals("CANCELLED", lifecycleField(logs.last(), "finalState"))
        assertEquals(1, f.calls)
        assertEquals("人工输入", f.text)
    }

    @Test fun modelLifecycleRuntimeResetCancelsPendingRetryOnce() {
        val f = Fixture()
        f.emptyFailuresRemaining = 2
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.step() // first retry ran, second retry is scheduled
        f.coordinator.resetForRuntimeReset()
        f.drain() // stale retry must not run
        val logs = lifecycleEvents(f)
        assertEquals(1, logs.count { lifecycleField(it, "eventType") == "MODEL_RETRY_CANCELLED" })
        assertEquals(1, logs.count { lifecycleField(it, "eventType") == "MODEL_REQUEST_FINISHED" })
        assertEquals("CANCELLED_GENERATION_CHANGED", lifecycleField(logs.last(), "retryState"))
        assertEquals("CANCELLED", lifecycleField(logs.last(), "finalState"))
        assertEquals(2, f.calls)
    }

    @Test fun modelLifecycleInactiveRuntimeIsCancelledWithoutRetryOrNewInput() {
        val f = Fixture()
        f.emptyFailuresRemaining = 1
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.active = false
        f.drain()
        val logs = lifecycleEvents(f)
        assertEquals("CANCELLED_RUNTIME_INACTIVE", lifecycleField(logs.last(), "retryState"))
        assertEquals("CANCELLED", lifecycleField(logs.last(), "finalState"))
        assertEquals(1, f.calls)
        assertEquals("", f.text)
    }

    @Test fun modelLifecycleNonRetryableEmptyAndNetworkFailureDoNotScheduleRetries() {
        for (transportFailure in listOf(false, true)) {
            val f = Fixture()
            if (transportFailure) f.fail = true else {
                f.emptyFailuresRemaining = 1
                f.emptyFinishReason = "length"
            }
            assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
            f.drain()
            val logs = lifecycleEvents(f)
            assertEquals(1, logs.count { lifecycleField(it, "eventType") == "MODEL_ATTEMPT_STARTED" })
            assertEquals(0, logs.count { lifecycleField(it, "eventType") == "MODEL_RETRY_SCHEDULED" })
            assertEquals(1, logs.count { lifecycleField(it, "eventType") == "MODEL_REQUEST_FINISHED" })
            assertEquals("FAILED", lifecycleField(logs.last(), "finalState"))
            assertEquals("NONE", lifecycleField(logs.last(), "retryState"))
            assertEquals(1, f.calls)
        }
    }

    @Test fun modelWaitWithTechnicalLookingReasonNeverSchedulesTechnicalRetry() {
        val f = Fixture()
        f.modelOutput = """{"action":"WAIT","reason":"MODEL_CALL_FAILED","reasonDetail":"涉及转账，不自动回复。","draft":null}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(1, f.calls)
        assertEquals("", f.text)
        assertTrue(f.logs.any { it.contains("action=WAIT") && it.contains("reasonHash=") })
        assertFalse(f.logs.any { it.contains("reason=MODEL_CALL_FAILED") })
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
        assertTrue(f.logs.any { it.contains("delayMs=2500") })
        assertTrue(f.requests.single().responseFormat is AiModelResponseFormat.JsonSchema)
        f.drain()
        assertEquals(2, f.calls)
        assertEquals("你好呀", f.text)
        assertTrue(f.coordinator.isConversationReturnBlocked)
    }

    @Test fun repeatedEmptyOutputStopsAfterTwoScheduledRetriesWithoutSending() {
        val f = Fixture()
        f.emptyFailuresRemaining = 3
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(3, f.calls)
        assertEquals("", f.text)
        assertFalse(f.coordinator.isPending)
        assertFalse(f.coordinator.isConversationReturnBlocked)
        assertEquals(2, f.logs.count { it.contains("AI 模型空输出有限恢复") })
        assertTrue(f.scheduledDelays.containsAll(listOf(2_500L, 6_000L)))
        assertTrue(f.requests.take(2).all { it.responseFormat is AiModelResponseFormat.JsonSchema })
        assertEquals(AiModelResponseFormat.PlainText, f.requests.last().responseFormat)
    }

    @Test fun exhaustedEmptyOutputReleasesGateAndReclaimsPersistedModelTask() {
        val ledger = TaskLedger()
        val f = Fixture(taskRepository = ledger)
        f.emptyFailuresRemaining = 3
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(3, f.calls)
        assertEquals("", f.text)
        assertFalse(f.coordinator.isPending)
        assertFalse(f.coordinator.isConversationReturnBlocked)
        assertFalse(f.coordinator.isSendConfirmationPending)
        assertEquals(ReplyTaskStatus.FAILED_RETRYABLE, ledger.records.values.single().status)
        f.now = 30_000L
        assertTrue(f.coordinator.generateVisibleDraft(f.batch.copy(capturedAtMillis = 2L), f.snapshot))
        f.drain()
        assertEquals(4, f.calls)
        assertEquals("你好呀", f.text)
    }

    @Test fun plainTextResponseModeIsUsedOnlyOnFinalDeepSeekRetry() {
        val f = Fixture()
        f.emptyFailuresRemaining = 2
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(3, f.calls)
        assertEquals(listOf(2_500L, 6_000L), f.scheduledDelays.filter { it > 0L }.take(2))
        assertTrue(f.requests.take(2).all { it.responseFormat is AiModelResponseFormat.JsonSchema })
        assertEquals(AiModelResponseFormat.PlainText, f.requests[2].responseFormat)
        assertFalse(f.requests[2].systemInstruction.contains("JSON Schema"))
        assertEquals("你好呀", f.text)
    }

    @Test fun plainTextResponseModeAcceptsNonJsonWithoutSendingJsonEnvelope() {
        val f = Fixture()
        f.emptyFailuresRemaining = 2
        f.modelOutput = "not-json"
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(3, f.calls)
        assertEquals(AiModelResponseFormat.PlainText, f.requests.last().responseFormat)
        assertEquals("not-json", f.text)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertFalse(f.logs.any { it.contains("MODEL_OUTPUT_SCHEMA_INVALID") })
    }

    @Test fun otherProvidersKeepStrictResponseModeOnEveryRetry() {
        val f = Fixture()
        f.configuredProvider = AiModelServiceProvider.OPENAI
        f.emptyFailuresRemaining = 2
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(3, f.calls)
        assertTrue(f.requests.all { it.responseFormat is AiModelResponseFormat.JsonSchema })
        assertEquals("你好呀", f.text)
    }

    @Test fun secondEmptyRetryCancelsIfUserEditsComposer() {
        val f = Fixture()
        f.emptyFailuresRemaining = 2
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.step()
        assertEquals(2, f.calls)
        f.text = "用户输入"
        f.drain()
        assertEquals(2, f.calls)
        assertEquals("用户输入", f.text)
        assertFalse(f.coordinator.isPending)
        assertFalse(f.coordinator.isConversationReturnBlocked)
    }

    @Test fun nonRetryableFinishReasonsNeverScheduleRecoveryOrHoldReturnGate() {
        for (finishReason in listOf("length", "content_filter", "tool_calls")) {
            val ledger = TaskLedger()
            val f = Fixture(taskRepository = ledger)
            f.emptyFailuresRemaining = 1
            f.emptyFinishReason = finishReason
            assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
            f.drain()
            assertEquals(1, f.calls)
            assertEquals("", f.text)
            assertTrue(f.scheduledDelays.isEmpty())
            assertFalse(f.coordinator.isConversationReturnBlocked)
            assertEquals(ReplyTaskStatus.FAILED_RETRYABLE, ledger.records.values.single().status)
            assertFalse(f.logs.any { it.contains("AI 模型空输出有限恢复") })
        }
    }

    @Test fun runtimeResetAfterFirstRetryCancelsSecondRetryWithoutAStaleDraft() {
        val f = Fixture()
        f.emptyFailuresRemaining = 2
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.step()
        assertEquals(2, f.calls)
        assertEquals("", f.text)
        f.coordinator.resetForRuntimeReset()
        f.drain()
        assertEquals(2, f.calls)
        assertEquals("", f.text)
        assertFalse(f.coordinator.isPending)
        assertFalse(f.coordinator.isConversationReturnBlocked)
        assertTrue(f.inputWriteTargets.isEmpty())
    }

    @Test fun modelWaitAfterDeepSeekFallbackCannotStartAFourthRequest() {
        val ledger = TaskLedger()
        val f = Fixture(taskRepository = ledger)
        f.emptyFailuresRemaining = 2
        f.modelOutput = """{"action":"WAIT","reason":"FINANCIAL_TRANSACTION_REVIEW","draft":null}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(3, f.calls)
        assertEquals(AiModelResponseFormat.PlainText, f.requests.last().responseFormat)
        assertEquals("", f.text)
        assertTrue(f.inputWriteTargets.isEmpty())
        assertFalse(f.coordinator.isConversationReturnBlocked)
        assertEquals(ReplyTaskStatus.MODEL_COMPLETED, ledger.records.values.single().status)
        assertFalse(f.logs.any { it.contains("attempt=3/2") })
    }

    @Test fun uncertainSendAfterDeepSeekFallbackStaysUnknownWithoutRegeneration() {
        val tasks = TaskLedger()
        val segments = SegmentLedger()
        var sends = 0
        lateinit var f: Fixture
        f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sends++
                f.text = ""
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        f.emptyFailuresRemaining = 2
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(3, f.calls)
        assertEquals(1, sends)
        assertTrue(f.coordinator.isSendConfirmationPending)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        val noOutgoing = f.batch.copy(capturedAtMillis = 2L)
        assertEquals(SendConfirmationResult.WAITING, f.coordinator.confirmSendResult(noOutgoing, f.snapshot))
        assertEquals(SendConfirmationResult.UNKNOWN, f.coordinator.confirmSendResult(noOutgoing, f.snapshot))
        assertEquals(ReplyTaskStatus.UNKNOWN, tasks.records.values.single().status)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertFalse(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(1, sends)
        assertEquals(3, f.calls)
    }

    @Test fun recoveredUnconfirmedOrUnknownSendNeverRewritesAfterRuntimeRecreation() {
        for (expectUnknown in listOf(false, true)) {
            val segments = SegmentLedger()
            val tasks = TaskLedger()
            var initialSends = 0
            val first = Fixture(object : ChatDraftSendPort {
                override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                    initialSends++
                    return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
                }
            }, segments, tasks)
            first.emptyFailuresRemaining = 2
            assertTrue(first.coordinator.generateVisibleDraft(first.batch, first.snapshot))
            first.drain()
            assertEquals(3, first.calls)
            assertEquals(1, initialSends)
            if (expectUnknown) {
                val noOutgoing = first.batch.copy(capturedAtMillis = 2L)
                assertEquals(SendConfirmationResult.WAITING, first.coordinator.confirmSendResult(noOutgoing, first.snapshot))
                assertEquals(SendConfirmationResult.UNKNOWN, first.coordinator.confirmSendResult(noOutgoing, first.snapshot))
            }
            assertEquals(if (expectUnknown) ReplySegmentTaskStatus.UNKNOWN else ReplySegmentTaskStatus.SENT_UNCONFIRMED,
                segments.records.values.single().status)

            var repeatedSends = 0
            val restored = Fixture(object : ChatDraftSendPort {
                override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                    repeatedSends++
                    return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
                }
            }, segments, tasks)
            assertFalse(restored.coordinator.generateVisibleDraft(restored.batch, restored.snapshot))
            restored.drain()
            assertEquals(0, restored.calls)
            assertEquals(0, repeatedSends)
            assertEquals(1, initialSends)
            assertTrue(restored.logs.any { it.contains("RECOVERY_UNCERTAIN_SEND_REQUIRES_READ_ONLY_CONFIRMATION") })
        }
    }

    @Test fun invalidModelOutputRemainsReclaimableWithoutAFalseSendGate() {
        val ledger = TaskLedger()
        val f = Fixture(taskRepository = ledger)
        f.modelOutput = "not-json"
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals("", f.text)
        assertFalse(f.coordinator.isConversationReturnBlocked)
        assertEquals(ReplyTaskStatus.FAILED_RETRYABLE, ledger.records.values.single().status)
    }

    @Test fun modelWaitHasCompletedDecisionButDoesNotHoldSendGate() {
        val ledger = TaskLedger()
        val f = Fixture(taskRepository = ledger)
        f.modelOutput = """{"action":"WAIT","reason":"FINANCIAL_TRANSACTION_REVIEW","draft":null}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals("", f.text)
        assertFalse(f.coordinator.isConversationReturnBlocked)
        assertEquals(ReplyTaskStatus.MODEL_COMPLETED, ledger.records.values.single().status)
        assertTrue(f.coordinator.isPrivateNoReplyCompleted(f.batch.windowSessionKey!!, f.batch.windowSessionKey!!))
        assertFalse(f.coordinator.isPrivateNoReplyCompleted("different-visit", f.batch.windowSessionKey!!))
        f.coordinator.resetForRuntimeReset()
        assertFalse(f.coordinator.isPrivateNoReplyCompleted(f.batch.windowSessionKey!!, f.batch.windowSessionKey!!))
    }

    @Test fun r03PartialSendWithoutUncertainSegmentCannotStartNewReplyOrCountAsCompleted() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val f = Fixture(segmentRepository = segments, taskRepository = tasks)
        val conversation = f.snapshot.conversationIdentityKey()!!
        val records = ReplySegmentTaskFactory.create("interrupted", conversation, listOf("first", "second"), 100L)
        segments.save(records[0].copy(status = ReplySegmentTaskStatus.SENT, confirmationId = "first-receipt"))
        segments.save(records[1])
        val outcome = f.coordinator.confirmSendTransaction(f.batch, f.snapshot, 50L)
        assertEquals(SendConfirmationResult.UNKNOWN, outcome.status)
        assertNull(outcome.finalSendConfirmationId)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertEquals(listOf(ReplySegmentTaskStatus.SENT, ReplySegmentTaskStatus.CREATED), segments.records.values.map { it.status })
        assertEquals(0, f.calls)
        assertTrue(f.inputWriteTargets.isEmpty())
    }

    @Test fun r03UnprovableHistoricalSendRetainsUnknownInsteadOfArchivingSuccess() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String) =
                ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
        }, segments, tasks)
        f.now = 100L
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertTrue(f.coordinator.deferPrivateUnconfirmedSendForManualReturn())
        val record = segments.records.values.single()
        segments.save(record.copy(sendAttemptStartedAt = null))
        val result = f.coordinator.confirmSendTransaction(f.batch, f.snapshot, 50L)
        assertEquals(SendConfirmationResult.UNKNOWN, result.status)
        assertNull(result.finalSendConfirmationId)
        assertEquals(record.status, segments.records.values.single().status)
        assertEquals(1, f.calls)
    }

    @Test fun r03CompleteDurableSendReceiptCanCloseInterruptedVisitWithoutNewModelOrSend() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val f = Fixture(segmentRepository = segments, taskRepository = tasks)
        val conversation = f.snapshot.conversationIdentityKey()!!
        val record = ReplySegmentTaskFactory.create("original-request", conversation, listOf("sent"), 100L).single()
        segments.save(record.copy(status = ReplySegmentTaskStatus.SENT, confirmationId = "actual-final-receipt", sendRequestKey = "owner-key"))
        tasks.save(ReplyTaskRecord("owner-key", conversation, "original-logical-message", "revision",
            ReplyTaskStatus.SENT, "original-request", 110L))
        val result = f.coordinator.confirmSendTransaction(f.batch, f.snapshot, 50L)
        assertEquals(SendConfirmationResult.CONFIRMED, result.status)
        assertEquals("actual-final-receipt", result.finalSendConfirmationId)
        assertEquals(0, f.calls)
        assertTrue(f.inputWriteTargets.isEmpty())
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

    @Test fun modelTransportFailureClosesLedgerAsRetryableAndAllowsFreshClaim() {
        val ledger = TaskLedger()
        val f = Fixture(taskRepository = ledger)
        f.fail = true
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        assertEquals(ReplyTaskStatus.FAILED_RETRYABLE, ledger.records.values.single().status)
        assertFalse(f.coordinator.isConversationReturnBlocked)
        assertEquals("", f.text)
        f.now = 30_000L
        f.fail = false
        assertTrue(f.coordinator.generateVisibleDraft(f.batch.copy(capturedAtMillis = 2L), f.snapshot))
        f.drain()
        assertEquals(2, f.calls)
        assertEquals("你好呀", f.text)
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

    @Test fun multiMessageTransactionHandoffYieldsReceiptOnlyAfterFinalConfirmedSegment() {
        val segments = SegmentLedger()
        val sentTexts = mutableListOf<String>()
        lateinit var f: Fixture
        f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sentTexts += expectedText
                f.text = ""
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, TaskLedger())
        f.modelOutput = """{"action":"REPLY","reason":"OK","draft":"第一\n第二"}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(listOf("第一"), sentTexts)
        val first = ChatMessage("first-message", ChatMessageDirection.OUTGOING, ChatMessageType.TEXT,
            "第一", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val afterFirst = f.batch.copy(messages = listOf(first), capturedAtMillis = 2L)
        assertNull(f.coordinator.nextSegmentConfirmedObservationId)
        val partial = f.coordinator.confirmSendTransaction(afterFirst, f.snapshot)
        assertEquals(afterFirst.effectiveObservationBatchId, f.coordinator.nextSegmentConfirmedObservationId)
        assertEquals(SendConfirmationResult.WAITING, partial.status)
        assertNull(partial.finalSendConfirmationId)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertEquals(listOf(ReplySegmentTaskStatus.SENT, ReplySegmentTaskStatus.CREATED),
            segments.records.values.map { it.status })
        assertNull(f.coordinator.consumeConfirmedSendId())

        val sameSnapshot = f.coordinator.confirmSendTransaction(afterFirst, f.snapshot)
        assertEquals(SendConfirmationResult.WAITING, sameSnapshot.status)
        assertNull(sameSnapshot.finalSendConfirmationId)
        assertEquals(listOf("第一"), sentTexts)
        val fresh = afterFirst.copy(capturedAtMillis = 3L)
        val advancing = f.coordinator.confirmSendTransaction(fresh, f.snapshot)
        assertNull(f.coordinator.nextSegmentConfirmedObservationId)
        assertEquals(SendConfirmationResult.WAITING, advancing.status)
        assertNull(advancing.finalSendConfirmationId)
        f.drain()
        assertEquals(listOf("第一", "第二"), sentTexts)
        val second = first.copy(id = "second-message", content = "第二")
        val finalBatch = f.batch.copy(messages = listOf(first, second), capturedAtMillis = 4L)
        val completed = f.coordinator.confirmSendTransaction(finalBatch, f.snapshot)
        assertNull(f.coordinator.nextSegmentConfirmedObservationId)
        assertEquals(SendConfirmationResult.CONFIRMED, completed.status)
        assertFalse(completed.finalSendConfirmationId.isNullOrBlank())
        assertEquals(listOf(ReplySegmentTaskStatus.SENT, ReplySegmentTaskStatus.SENT),
            segments.records.values.map { it.status })
        assertFalse(f.coordinator.isConversationReturnBlocked)
        assertNull("Final receipt must not be consumed twice", f.coordinator.consumeConfirmedSendId())
        val duplicate = f.coordinator.confirmSendTransaction(finalBatch, f.snapshot)
        assertEquals(SendConfirmationResult.NOT_PENDING, duplicate.status)
        assertNull(duplicate.finalSendConfirmationId)
    }

    @Test fun partialOrUnknownSendTransactionCannotCarryFinalReceipt() {
        assertTrue(runCatching {
            ReplySendTransactionOutcome(SendConfirmationResult.WAITING, "must-not-be-final")
        }.isFailure)
        assertTrue(runCatching {
            ReplySendTransactionOutcome(SendConfirmationResult.UNKNOWN, "must-not-be-final")
        }.isFailure)
        assertTrue(runCatching {
            ReplySendTransactionOutcome(SendConfirmationResult.NOT_PENDING, "must-not-be-final")
        }.isFailure)
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

    @Test fun returnGateReleasesAfterModelFailureAndResetsOnRuntimeReset() {
        val f = Fixture()
        f.fail = true
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        assertFalse(f.coordinator.isPending)
        assertFalse(f.coordinator.isConversationReturnBlocked)
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

    @Test fun platformCompletionRequestsOneReadOnlyObservationWithoutConfirmingOrResending() {
        var sends = 0
        lateinit var completed: () -> Unit
        val ledger = SegmentLedger()
        val f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult =
                error("Completion-capable send port must be used")
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String,
                onGestureCompleted: () -> Unit): ChatDraftInputResult {
                sends++
                completed = onGestureCompleted
                // Exercise a platform callback delivered before the submission returns.
                onGestureCompleted()
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, ledger, TaskLedger())
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(1, sends)
        assertEquals(1, f.completionObservationRequests)
        assertTrue(f.coordinator.isSendConfirmationPending)
        assertTrue(f.coordinator.isConversationReturnBlocked)
        assertEquals(ReplySegmentTaskStatus.SENT_UNCONFIRMED, ledger.records.values.single().status)
        completed()
        f.drain()
        assertEquals(1, f.completionObservationRequests)
        val outgoing = ChatMessage("new-outgoing", ChatMessageDirection.OUTGOING,
            ChatMessageType.TEXT, "你好呀", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        assertEquals(SendConfirmationResult.CONFIRMED, f.coordinator.confirmSendResult(
            f.batch.copy(messages = listOf(outgoing), capturedAtMillis = f.now + 1L), f.snapshot))
        assertEquals(1, sends)
        assertEquals(ReplySegmentTaskStatus.SENT, ledger.records.values.single().status)
    }

    @Test fun platformCompletionAfterRuntimeResetCannotRequestObservation() {
        lateinit var completed: () -> Unit
        val f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult =
                error("Completion-capable send port must be used")
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String,
                onGestureCompleted: () -> Unit): ChatDraftInputResult {
                completed = onGestureCompleted
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, SegmentLedger(), TaskLedger())
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertTrue(f.coordinator.isSendConfirmationPending)
        assertEquals(0, f.completionObservationRequests)
        f.coordinator.resetForRuntimeReset()
        completed()
        f.drain()
        assertEquals(0, f.completionObservationRequests)
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


    @Test fun taskIsolationStillRevokesPendingCallbacksWhenUnknownJournalUpdateThrows() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        var submitted = 0
        lateinit var completed: () -> Unit
        val first = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult =
                error("completion capable send port required")
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String,
                onGestureCompleted: () -> Unit): ChatDraftInputResult {
                submitted++
                completed = onGestureCompleted
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        assertTrue(first.coordinator.generateVisibleDraft(first.batch, first.snapshot))
        first.drain()
        assertEquals(1, submitted)
        assertTrue(first.coordinator.isSendConfirmationPending)
        val durable = segments.records.values.single()
        assertEquals(ReplySegmentTaskStatus.SENT_UNCONFIRMED, durable.status)
        segments.failUnknownUpdate = true
        first.coordinator.resetForRuntimeReset()
        completed()
        first.drain()
        assertFalse(first.coordinator.isSendConfirmationPending)
        assertEquals(0, first.completionObservationRequests)
        assertEquals(durable, segments.records.values.single())
        assertEquals(1, submitted)
        assertTrue(first.logs.any { it.contains("UNKNOWN_JOURNAL_UPDATE_UNCONFIRMED") })
        // The original conversation still has its non-replayable send proof, even
        // though the in-memory pending flag no longer globally blocks another task.
        first.text = ""
        first.coordinator.generateVisibleDraft(first.batch, first.snapshot)
        first.drain()
        assertEquals(1, submitted)
    }

    @Test fun restoredUnknownSendRequiresTwoFreshMatchingObservationsAndNeverResends() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        var submitted = 0
        val first = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                submitted++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        assertTrue(first.coordinator.generateVisibleDraft(first.batch, first.snapshot))
        first.drain()
        assertEquals(1, submitted)
        assertTrue(segments.records.values.single().sendRequestKey?.isNotBlank() == true)
        assertTrue(segments.records.values.single().sendWindowSessionKey?.isNotBlank() == true)
        first.coordinator.resetForRuntimeReset()
        assertEquals(ReplySegmentTaskStatus.UNKNOWN, segments.records.values.single().status)

        var replayed = 0
        val restored = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                replayed++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        val outgoing = ChatMessage("new-outgoing", ChatMessageDirection.OUTGOING,
            ChatMessageType.TEXT, "你好呀", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val afterSend = restored.batch.copy(messages = listOf(outgoing), capturedAtMillis = 2L)
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendTransaction(afterSend, restored.snapshot).status)
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendTransaction(
            afterSend.copy(capturedAtMillis = 3L), restored.snapshot).status)
        val terminal = restored.coordinator.confirmSendTransaction(
            afterSend.copy(capturedAtMillis = 4L), restored.snapshot)
        assertEquals(SendConfirmationResult.CONFIRMED, terminal.status)
        assertTrue(terminal.finalSendConfirmationId?.isNotBlank() == true)
        assertEquals(ReplySegmentTaskStatus.SENT, segments.records.values.single().status)
        assertEquals(ReplyTaskStatus.SENT, tasks.records.values.single().status)
        assertEquals(1, submitted)
        assertEquals(0, replayed)
        assertEquals(0, restored.calls)
        val recoveryEvents = restored.logs.filter { it.startsWith("AI 消息发送链路：") }
        val recovered = recoveryEvents.single { traceField(it, "eventType") == "MESSAGE_SEND_REHYDRATED" }
        assertEquals("UNKNOWN", traceField(recovered, "taskId"))
        assertEquals("RECOVERED_SYNTHETIC", traceField(recovered, "sendAttemptOrigin"))
        assertEquals("READ_ONLY_RECOVERY", traceField(recovered, "reasonCode"))
        assertEquals(1, recoveryEvents.count { traceField(it, "eventType") == "MESSAGE_SEND_COMPLETED" })
        assertTrue(recoveryEvents.all { traceField(it, "requestId") == segments.records.values.single().requestId })
    }

    @Test fun recoveredUnknownCannotConfirmIdenticalOutgoingPresentBeforeOriginalSend() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        // The reader orders by vertical position, not insertion order. Keep the
        // historical outgoing bubble above the latest incoming row.
        val old = ChatMessage("historical", ChatMessageDirection.OUTGOING,
            ChatMessageType.TEXT, "你好呀", ScreenRect(0, 0, 100, 9),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val first = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String) =
                ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
        }, segments, tasks)
        val initial = first.batch.copy(messages = first.batch.messages + listOf(old))
        assertTrue(first.coordinator.generateVisibleDraft(initial, first.snapshot))
        first.drain()
        assertTrue(segments.records.values.single().preSendOutgoingKeyHashes.isNotEmpty())
        first.coordinator.resetForRuntimeReset()

        var repeated = 0
        val restored = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                repeated++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        val current = restored.batch.copy(messages = listOf(old), capturedAtMillis = 2L)
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendTransaction(current, restored.snapshot).status)
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendTransaction(
            current.copy(capturedAtMillis = 3L), restored.snapshot).status)
        assertEquals(SendConfirmationResult.UNKNOWN, restored.coordinator.confirmSendTransaction(
            current.copy(capturedAtMillis = 4L), restored.snapshot).status)
        assertEquals(ReplySegmentTaskStatus.UNKNOWN, segments.records.values.single().status)
        assertEquals(0, repeated)
        assertFalse(restored.coordinator.generateVisibleDraft(restored.batch, restored.snapshot))
    }

    @Test fun legacyUnknownWithoutDurableSendProofCannotBeConfirmedByMatchingText() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val first = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String) =
                ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
        }, segments, tasks)
        assertTrue(first.coordinator.generateVisibleDraft(first.batch, first.snapshot))
        first.drain()
        first.coordinator.resetForRuntimeReset()
        val old = segments.records.values.single()
        segments.records[old.taskId] = old.copy(
            sendAttemptStartedAt = null, sendWindowSessionKey = null,
            sendChatTitleHash = null, sendRequestKey = null
        )
        val restored = Fixture(segmentRepository = segments, taskRepository = tasks)
        val outgoing = ChatMessage("new-outgoing", ChatMessageDirection.OUTGOING,
            ChatMessageType.TEXT, "你好呀", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        assertEquals(SendConfirmationResult.UNKNOWN, restored.coordinator.confirmSendTransaction(
            restored.batch.copy(messages = listOf(outgoing), capturedAtMillis = 2L),
            restored.snapshot).status)
        assertEquals(ReplySegmentTaskStatus.UNKNOWN, segments.records.values.single().status)
        assertTrue(restored.logs.any { it.contains("UNRESOLVED_EVIDENCE") })
    }

    @Test fun recoveredFirstMessageConfirmsThenResumesNextSegmentWithFreshComposer() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val sends = mutableListOf<String>()
        val first = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sends.add(expectedText)
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        first.modelOutput = """{"action":"REPLY","reason":"OK","draft":"第一\n第二"}"""
        assertTrue(first.coordinator.generateVisibleDraft(first.batch, first.snapshot))
        first.drain()
        assertEquals(listOf("第一"), sends)
        first.coordinator.resetForRuntimeReset()

        val restored = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sends.add(expectedText)
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        val firstOutgoing = ChatMessage("first-out", ChatMessageDirection.OUTGOING,
            ChatMessageType.TEXT, "第一", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val firstBatch = restored.batch.copy(messages = listOf(firstOutgoing), capturedAtMillis = 2L)
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendResult(firstBatch, restored.snapshot))
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendResult(
            firstBatch.copy(capturedAtMillis = 3L), restored.snapshot))
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendResult(
            firstBatch.copy(capturedAtMillis = 4L), restored.snapshot))
        assertEquals(listOf("第一"), sends)
        assertEquals(listOf(ReplySegmentTaskStatus.SENT, ReplySegmentTaskStatus.CREATED),
            segments.records.values.map { it.status })
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendResult(
            firstBatch.copy(capturedAtMillis = 5L), restored.snapshot))
        restored.drain()
        assertEquals(listOf("第一", "第二"), sends)
        val secondOutgoing = ChatMessage("second-out", ChatMessageDirection.OUTGOING,
            ChatMessageType.TEXT, "第二", ScreenRect(0, 30, 100, 50),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val last = restored.coordinator.confirmSendTransaction(
            firstBatch.copy(messages = listOf(firstOutgoing, secondOutgoing), capturedAtMillis = 6L),
            restored.snapshot)
        assertEquals(SendConfirmationResult.CONFIRMED, last.status)
        assertTrue(last.finalSendConfirmationId?.isNotBlank() == true)
        assertEquals(listOf(ReplySegmentTaskStatus.SENT, ReplySegmentTaskStatus.SENT),
            segments.records.values.map { it.status })
    }

    @Test fun failedDurablePreSendEvidenceNeverDispatchesSendGesture() {
        val stored = SegmentLedger()
        val rejectEvidence = object : ReplySegmentTaskRepository by stored {
            override fun update(record: ReplySegmentTaskRecord): Boolean {
                if (record.status == ReplySegmentTaskStatus.SENDING &&
                    record.sendAttemptStartedAt != null) return false
                return stored.update(record)
            }
        }
        var sends = 0
        val f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                sends++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, rejectEvidence, TaskLedger())
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(0, sends)
        assertEquals(ReplySegmentTaskStatus.PENDING_SEND, stored.records.values.single().status)
        assertTrue(f.logs.any { it.contains("SEND_PRECHECKPOINT_NOT_DURABLE") || it.contains("NOT_DURABLE") })
    }

    @Test fun technicalModelFailureEmitsOnlyUnsentFailureAndDefersAfterTwoAttempts() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val f = Fixture(segmentRepository = segments, taskRepository = tasks)
        f.fail = true
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot, visitToken = "greeting-visit"))
        assertEquals(1, f.preSendFailures.size)
        val first = f.preSendFailures.single()
        assertEquals("greeting-visit", first.visitToken)
        assertEquals(1, first.attempts)
        assertFalse(first.readyToDefer)
        assertEquals(30_000L, first.retryDelayMs)
        assertEquals(ReplyTaskStatus.FAILED_RETRYABLE, tasks.records.values.single().status)
        assertTrue(segments.records.isEmpty())
        assertTrue(f.coordinator.verifyGreetingPreSendFailure(first, f.snapshot))
        assertFalse(f.coordinator.isConversationReturnBlocked)

        f.now = 30_001L
        assertTrue(f.coordinator.generateVisibleDraft(f.batch.copy(capturedAtMillis = 2L),
            f.snapshot, visitToken = "greeting-visit"))
        assertEquals(2, f.preSendFailures.size)
        val second = f.preSendFailures.last()
        assertEquals(2, second.attempts)
        assertTrue(second.readyToDefer)
        assertEquals(ReplyTaskStatus.FAILED_RETRYABLE, tasks.records.values.single().status)
        assertTrue(f.coordinator.verifyGreetingPreSendFailure(second, f.snapshot))
        f.text = "用户正在输入"
        assertFalse(f.coordinator.verifyGreetingPreSendFailure(second, f.snapshot))
        f.text = ""
        assertFalse(f.coordinator.generateVisibleDraft(f.batch.copy(capturedAtMillis = 3L),
            f.snapshot, visitToken = "greeting-visit"))
        f.coordinator.releaseDeferredGreetingModelFailure(second)
        assertTrue(f.coordinator.generateVisibleDraft(f.batch.copy(capturedAtMillis = 4L),
            f.snapshot, visitToken = "greeting-visit"))
        assertEquals(1, f.preSendFailures.last().attempts)
    }

    @Test fun modelWaitAndSubmittedSendNeverProducePreSendFailureEvent() {
        val f = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult =
                ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
        }, SegmentLedger(), TaskLedger())
        f.modelOutput = """{"action":"WAIT","reason":"SAFETY","draft":null}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        assertEquals(0, f.preSendFailures.size)
        // A distinct request is used for the sending example.
        val sending = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult =
                ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
        }, SegmentLedger(), TaskLedger())
        assertTrue(sending.coordinator.generateVisibleDraft(sending.batch, sending.snapshot))
        sending.drain()
        assertTrue(sending.coordinator.isSendConfirmationPending)
        assertEquals(0, sending.preSendFailures.size)
        sending.coordinator.confirmSendResult(sending.batch.copy(capturedAtMillis = 2L), sending.snapshot)
        sending.coordinator.confirmSendResult(sending.batch.copy(capturedAtMillis = 3L), sending.snapshot)
        assertEquals(0, sending.preSendFailures.size)
    }

    @Test fun restoredUnknownNeedsConsecutiveFreshProofAfterOcrMissAndWrongWindow() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        var originalSends = 0
        val first = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                originalSends++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        assertTrue(first.coordinator.generateVisibleDraft(first.batch, first.snapshot))
        first.drain()
        assertEquals(1, originalSends)
        first.coordinator.resetForRuntimeReset()
        assertEquals(ReplySegmentTaskStatus.UNKNOWN, segments.records.values.single().status)

        var replayed = 0
        val restored = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                replayed++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        val outgoing = ChatMessage("new-outgoing", ChatMessageDirection.OUTGOING,
            ChatMessageType.TEXT, "你好呀", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val after = restored.batch.copy(messages = listOf(outgoing), capturedAtMillis = 2L)
        fun confirm(batch: ChatMessageBatch): SendConfirmationResult =
            restored.coordinator.confirmSendTransaction(batch, restored.snapshot).status

        assertEquals(SendConfirmationResult.WAITING, confirm(after)) // Rehydrate only.
        assertEquals(SendConfirmationResult.WAITING, confirm(after.copy(capturedAtMillis = 3L)))
        // OCR missed the outgoing bubble. Its absence is not proof of a failed send.
        assertEquals(SendConfirmationResult.WAITING, confirm(
            after.copy(messages = emptyList(), capturedAtMillis = 4L)))
        assertEquals(SendConfirmationResult.WAITING, confirm(after.copy(capturedAtMillis = 5L)))
        assertEquals(ReplySegmentTaskStatus.UNKNOWN, segments.records.values.single().status)
        assertEquals(0, replayed)
        assertEquals(SendConfirmationResult.CONFIRMED, confirm(after.copy(capturedAtMillis = 6L)))
        assertEquals(ReplySegmentTaskStatus.SENT, segments.records.values.single().status)

        // A receipt is single-use; an old OCR callback cannot report completion twice.
        val duplicate = restored.coordinator.confirmSendTransaction(
            after.copy(capturedAtMillis = 7L), restored.snapshot)
        assertEquals(SendConfirmationResult.NOT_PENDING, duplicate.status)
        assertNull(duplicate.finalSendConfirmationId)
        assertEquals(1, originalSends)
        assertEquals(0, replayed)
    }

    @Test fun recoveredUnknownWindowMismatchBreaksConsecutiveProofWithoutReplaying() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val first = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String) =
                ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
        }, segments, tasks)
        assertTrue(first.coordinator.generateVisibleDraft(first.batch, first.snapshot))
        first.drain()
        first.coordinator.resetForRuntimeReset()
        var resent = 0
        val restored = Fixture(object : ChatDraftSendPort {
            override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
                resent++
                return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
            }
        }, segments, tasks)
        val bubble = ChatMessage("bubble", ChatMessageDirection.OUTGOING,
            ChatMessageType.TEXT, "你好呀", ScreenRect(0, 10, 100, 30),
            DirectionEvidence.LAYOUT_ID_AND_HIERARCHY, MessageContentQuality.VERIFIED)
        val valid = restored.batch.copy(messages = listOf(bubble), capturedAtMillis = 2L)
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendResult(valid, restored.snapshot))
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendResult(
            valid.copy(capturedAtMillis = 3L), restored.snapshot))
        // Unrelated page generation must invalidate the pending proof, not complete the send.
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendResult(
            valid.copy(windowSessionKey = "pkg|99|99|conversation=unrelated", capturedAtMillis = 4L),
            restored.snapshot))
        assertEquals(SendConfirmationResult.WAITING, restored.coordinator.confirmSendResult(
            valid.copy(capturedAtMillis = 5L), restored.snapshot))
        assertEquals(SendConfirmationResult.CONFIRMED, restored.coordinator.confirmSendResult(
            valid.copy(capturedAtMillis = 6L), restored.snapshot))
        assertEquals(0, resent)
    }

    @Test fun modelFailureDeferralProofRejectsNewOwnerOrAnyUnconfirmedSegment() {
        val segments = SegmentLedger()
        val tasks = TaskLedger()
        val f = Fixture(segmentRepository = segments, taskRepository = tasks)
        f.fail = true
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot, visitToken = "greeting-visit"))
        val failure = f.preSendFailures.single()
        assertTrue(f.coordinator.verifyGreetingPreSendFailure(failure, f.snapshot))
        assertEquals(ReplyTaskStatus.FAILED_RETRYABLE, tasks.records.values.single().status)
        val row = requireNotNull(tasks.records[failure.requestKey])
        tasks.records[failure.requestKey] = row.copy(requestId = "new-request")
        assertFalse(f.coordinator.verifyGreetingPreSendFailure(failure, f.snapshot))
        tasks.records[failure.requestKey] = row

        // A concurrent send attempt, even unconfirmed, defeats "no send" proof.
        val segment = ReplySegmentTaskRecord(
            taskId = "${failure.requestId}:segment:0",
            requestId = failure.requestId, conversationKey = failure.conversationKey,
            segmentIndex = 0, segmentCount = 1,
            segmentContentHash = "hash", segmentContent = "你好",
            status = ReplySegmentTaskStatus.UNKNOWN, createdAt = 1L
        )
        segments.records[segment.taskId] = segment
        assertFalse(f.coordinator.verifyGreetingPreSendFailure(failure, f.snapshot))
        segments.records.clear()
        assertTrue(f.coordinator.verifyGreetingPreSendFailure(failure, f.snapshot))
        assertFalse(f.coordinator.verifyGreetingPreSendFailure(
            failure, f.snapshot.copy(chatTitleText = "其他聊天")))
    }


    @Test fun partialOcrProjectionReachesModelWithoutChangingRawMessageOrDedupeIdentity() {
        val f = Fixture()
        val marker = OcrUncertaintyMarkers.UNCERTAIN_SYMBOL_ONLY
        val raw = "今晚有空吗\n${marker} 在吗a"
        val first = OcrContentRect(0, 0, 100, 23)
        val second = OcrContentRect(0, 30, 100, 53)
        val tokens = listOf(
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.RECOGNIZED, "今晚有空吗",
                OcrTokenSource.OCR_LINE, 0, first),
            OcrContentToken(OcrContentKind.PLACEHOLDER, OcrTokenStatus.UNVERIFIED, marker,
                OcrTokenSource.VISUAL_GUARD, 1, second),
            OcrContentToken(OcrContentKind.TEXT, OcrTokenStatus.AMBIGUOUS, " 在吗a",
                OcrTokenSource.OCR_LINE, 1, second)
        )
        val projection = OcrTextProjection("今晚有空吗", OcrTextProjectionStatus.PARTIAL,
            listOf(0), listOf(1))
        val original = f.batch.messages.single()
        val current = original.copy(
            content = raw, contentQuality = MessageContentQuality.AMBIGUOUS,
            observationRowIndex = 0
        )
        val batch = f.batch.copy(
            messages = listOf(current),
            ocrTokensByRowIndex = mapOf(0 to tokens),
            ocrTextProjectionsByRowIndex = mapOf(0 to projection)
        )
        assertTrue(f.coordinator.generateVisibleDraft(batch, f.snapshot))
        assertEquals(1, f.calls)
        assertEquals(listOf("今晚有空吗"), f.requests.single().conversation.map { it.content })
        assertEquals(raw, batch.messages.single().content)
        assertEquals(original.id, batch.messages.single().id)
    }


}
