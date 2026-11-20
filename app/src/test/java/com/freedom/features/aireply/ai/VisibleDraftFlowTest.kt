package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.*
import com.freedom.core.runtime.*
import com.freedom.features.aireply.domain.message.*
import com.freedom.features.aireply.ports.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class VisibleDraftFlowTest {
    private class Fixture {
        val tasks = mutableListOf<() -> Unit>()
        var active = true
        var consent = true
        var text = ""
        val logs = mutableListOf<String>()
        var modelOutput = """{"action":"REPLY","reason":"OK","draft":"你好呀"}"""
        var calls = 0
        var fail = false
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
                    return AiModelResult(modelOutput, "fake", "fake", TokenUsage(10, 20), "stop")
                }
            } }, backgroundExecutor = Executor { it.run() }, nowMillis = { now }, chooseRandomInt = { min, _ -> min })
        fun drain() { while (tasks.isNotEmpty()) tasks.removeAt(0).invoke() }
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
        f.modelOutput = """{"action":"WAIT","reason":"SEXUAL_INNUENDO_AMBIGUOUS","reasonDetail":"缺少前文无法确认含义；停止自动发送。","draft":null}"""
        assertTrue(f.coordinator.generateVisibleDraft(f.batch, f.snapshot))
        f.drain()
        val log = f.logs.single { it.startsWith("AI 模型决策详情：") }
        assertTrue(log.contains("action=WAIT"))
        assertTrue(log.contains("reason=SEXUAL_INNUENDO_AMBIGUOUS"))
        assertTrue(log.contains("缺少前文无法确认含义"))
        assertTrue(log.contains("provider=fake"))
        assertTrue(log.contains("finishReason=stop"))
        assertTrue(log.contains("outputTokens=20"))
        assertFalse(log.contains("13800138000"))
        assertEquals("", f.text)
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
}
