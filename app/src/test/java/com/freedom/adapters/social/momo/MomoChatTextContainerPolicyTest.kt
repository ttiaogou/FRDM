package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.*
import com.freedom.core.runtime.*
import com.freedom.features.aireply.ai.*
import com.freedom.features.aireply.application.message.*
import com.freedom.features.aireply.domain.message.*
import com.freedom.features.aireply.ports.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class MomoChatTextContainerPolicyTest {
    private val listBounds = ScreenRect(0, 293, 720, 1359)
    private fun body(text: String, top: Int = 431, incoming: Boolean = true): MomoChatTextEvidence {
        val side = if (incoming) "message_layout_rightcontainer" else "message_layout_leftcontainer"
        return MomoChatTextEvidence(text, ScreenRect(140, top + 16, 480, top + 60), 3, 0,
            "view", "com.immomo.momo:id/message_tv_layouttextview", emptyList(),
            listOf("message_root", "message_content_layout", side, "message_tv_layouttextview"), listOf(1, 0, 0))
    }
    private fun direction(evidence: MomoChatTextEvidence, incoming: Boolean): MomoChatMessageDirectionResolution {
        val avatar = if (incoming) "message_iv_avatar_container" else "message_layout_avatarcontainer"
        return MomoChatMessageDirectionPolicy.resolveForBody(evidence.path, evidence.viewIdTrail, listOf(
            MomoChatMessageLayoutNodeEvidence("message_root", "relativelayout", emptyList(), listOf("message_root")),
            MomoChatMessageLayoutNodeEvidence(avatar, "relativelayout", listOf(0), listOf("message_root", avatar))
        ))
    }
    private fun candidates(evidence: List<MomoChatTextEvidence>, top: Int, incoming: Boolean): List<ChatMessageCandidate> {
        val selected = MomoChatTextContainerPolicy.selectBody(evidence, listBounds) ?: return emptyList()
        val resolved = direction(selected, incoming)
        return listOf(ChatMessageCandidate(0, ScreenRect(0, top, 720, top + 120), selected.bounds, listBounds,
            selected.value, ChatMessageType.TEXT, resolved.direction, resolved.evidence))
    }
    private fun unknownCard(index: Int, top: Int = 600) = body("未知卡片内容".repeat(20), top).copy(
        viewId = "component_$index", path = listOf(1, 0, 0),
        viewIdTrail = listOf("message_root", "message_content_layout", "unknown_widget", "component_$index"))

    @Test fun unknownCardsNeverBecomeBodyRegardlessOfTextOrNames() {
        for (index in 0..100) {
            assertNull(MomoChatTextContainerPolicy.selectBody(listOf(unknownCard(index)), listBounds))
        }
    }

    @Test fun onlyFormalContainerWinsEvenWhenCardTextIsLonger() {
        val formal = body("测试正文")
        val evidence = (0..50).map { unknownCard(it) } + formal
        assertEquals(formal, MomoChatTextContainerPolicy.selectBody(evidence, listBounds))
        assertFalse(MomoChatRowCandidatePolicy.isFormalTextContainer("message_tv_layouttextview_banner"))
    }

    @Test fun bodyOwnershipUsesNearestMessageRootAndContentLayout() {
        val formal = body("测试正文")
        assertTrue(MomoChatTextContainerPolicy.isBodyText(formal.viewId, formal.viewIdTrail))
        assertFalse(MomoChatTextContainerPolicy.isBodyText(formal.viewId, listOf("message_root", "message_tv_layouttextview")))
        assertFalse(MomoChatTextContainerPolicy.isBodyText("label", listOf("message_root", "message_content_layout",
            "message_tv_layouttextview", "message_root", "message_content_layout", "label")))
        assertTrue(MomoChatTextContainerPolicy.isBodyText("label", formal.viewIdTrail + "label"))
    }

    @Test fun originalAvatarHierarchyStillResolvesBothDirections() {
        assertEquals(ChatMessageDirection.INCOMING, direction(body("对方正文"), true).direction)
        assertEquals(ChatMessageDirection.OUTGOING, direction(body("自己正文", incoming = false), false).direction)
    }

    @Test fun ocrForFormalBodyIsNotReclassifiedByUnrelatedCardNodes() {
        val formal = body("")
        val resolved = direction(formal, true)
        val target = ChatMessageOcrTarget(0, ScreenRect(0, 431, 720, 551), formal.bounds, formal.bounds, null,
            explicitDirectionHint = resolved.direction, explicitDirectionEvidence = resolved.evidence,
            structureSignature = "root=message_root;nodes=message_tv_layouttextview,game_content,message_btn_game_together")
        val messages = MomoChatOcrCandidateMapper.parse(listOf(target), mapOf(0 to "识别到的正式正文"),
            listBounds, ChatMessageParser(), 10)
        assertEquals(ChatMessageType.TEXT, messages.single().type)
        assertEquals(ChatMessageDirection.INCOMING, messages.single().direction)
        assertEquals("识别到的正式正文", messages.single().content)
    }

    private class Flow {
        val scheduled = mutableListOf<() -> Unit>()
        var text = ""
        val logs = mutableListOf<String>()
        var modelCalls = 0
        var request: AiModelRequest? = null
        val window = WindowRef("pkg", "activity", 1, 1)
        val snapshot = ChatPageSnapshot("pkg", "activity", ChatPageKind.CHAT_DETAIL_CANDIDATE,
            10, 1, 1, 0, 1, false, NodeTarget(window, NodePath(listOf(1))),
            NodeTarget(window, NodePath(listOf(2))), chatTitleText = "与测试对话", windowGeneration = 1)
        val coordinator = AiReplyDraftCoordinator(
            configurationRepository = object : AiModelConfigurationRepository {
                override fun load() = AiModelConfiguration(apiBaseUrl = "https://example.com/v1", modelName = "test", apiKey = "test")
                override fun save(configuration: AiModelConfiguration) = Unit
                override fun clearApiKey() = Unit
            }, cloudConsent = AiModelCloudConsent { true },
            personalizationRepository = object : AiReplyPersonalizationRepository {
                override fun load() = AiReplyPersonalization()
                override fun save(personalization: AiReplyPersonalization) = Unit
            }, inputPort = MomoDraftInputController { operation -> operation(object : MomoDraftInputController.Field {
                override fun readText() = text
                override fun focus() = true
                override fun setText(text: String): Boolean { this@Flow.text = text; return true }
            }) }, scheduler = object : TaskScheduler {
                override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
                    scheduled += task; return ScheduledTask { scheduled.remove(task) }
                }
            }, isAiReplyActive = { true }, isManualReviewRequired = { false }, onLog = { logs.add(it) },
            createProvider = { object : AiModelProvider {
                override fun generate(request: AiModelRequest): AiModelResult {
                    modelCalls++; this@Flow.request = request
                    return AiModelResult("""{"action":"REPLY","reason":"OK","reasonDetail":"回复已确认的文字消息","draft":"测试回复内容"}""", "fake", "fake")
                }
            } }, backgroundExecutor = Executor { it.run() }, chooseRandomInt = { minimum, _ -> minimum }
        )
        fun batch(messages: List<ChatMessage>) = ChatMessageBatch(MessageReadStatus.SUCCESS,
            "pkg|1|1|conversation=${snapshot.conversationIdentityKey()}", messages, messages.size,
            messages.size, false, 1L)
        fun drain() { while (scheduled.isNotEmpty()) scheduled.removeAt(0).invoke() }
    }

    @Test fun genericUnknownCardsDoNotBlockTextToModelToDraftInput() {
        val flow = Flow()
        val parser = ChatMessageParser()
        val own = candidates(listOf(body("自己之前说的话", 293, false)), 293, false)
        val incoming = candidates(listOf(body("对方的问题", 431)), 431, true)
        val cards = (0..50).flatMap { candidates(listOf(unknownCard(it)), 600, true) }
        assertTrue(cards.isEmpty())
        val messages = parser.parse(own + incoming + cards)
        assertTrue(flow.coordinator.generateVisibleDraft(flow.batch(messages), flow.snapshot))
        flow.drain()
        assertEquals(1, flow.modelCalls)
        assertEquals(listOf("自己之前说的话", "对方的问题"), flow.request!!.conversation.map { it.content })
        assertEquals("测试回复内容", flow.text)
        assertTrue(flow.coordinator.isAwaitingUserSend)
    }

    @Test fun noFormalTextProducesNoInventedReply() {
        val flow = Flow()
        val cardRows = (0..50).flatMap { candidates(listOf(unknownCard(it)), 600, true) }
        assertFalse(flow.coordinator.generateVisibleDraft(flow.batch(ChatMessageParser().parse(cardRows)), flow.snapshot))
        assertEquals(0, flow.modelCalls)
        assertEquals("", flow.text)
    }

    @Test fun latestOutgoingFormalTextStillDoesNotTriggerAnotherReply() {
        val flow = Flow()
        val incoming = candidates(listOf(body("对方正文", 293)), 293, true)
        val own = candidates(listOf(body("自己最新正文", 431, false)), 431, false)
        val messages = ChatMessageParser().parse(incoming + own)
        assertFalse(flow.coordinator.generateVisibleDraft(flow.batch(messages), flow.snapshot))
        assertEquals(0, flow.modelCalls)
    }

    @Test fun ambiguousFormalTextDirectionIsNotGuessed() {
        val flow = Flow()
        val message = ChatMessage("unknown", ChatMessageDirection.UNKNOWN, ChatMessageType.TEXT,
            "无法确认归属的正文", ScreenRect(0, 431, 720, 551), DirectionEvidence.AMBIGUOUS)
        assertFalse(flow.coordinator.generateVisibleDraft(flow.batch(listOf(message)), flow.snapshot))
        assertEquals(0, flow.modelCalls)
    }
}
