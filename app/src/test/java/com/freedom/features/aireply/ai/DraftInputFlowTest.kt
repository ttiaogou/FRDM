package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.*
import com.freedom.core.runtime.*
import com.freedom.features.aireply.ports.*
import org.junit.Assert.*
import org.junit.Test

class DraftInputFlowTest {
    private class Scheduler : TaskScheduler {
        val tasks = mutableListOf<() -> Unit>()
        val delays = mutableListOf<Long>()
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            delays.add(delayMillis); tasks.add(task)
            return ScheduledTask { tasks.remove(task) }
        }
        fun next() = tasks.removeAt(0).invoke()
    }
    private class Input : ChatDraftInputPort {
        var text = ""
        var failure: ChatDraftInputStatus? = null
        val writes = mutableListOf<Pair<String, String>>()
        val reads = mutableListOf<String>()
        override fun writeDraft(target: ChatDraftInputTarget, expectedText: String, text: String): ChatDraftInputResult {
            if (failure != null) return ChatDraftInputResult(failure!!)
            if (this.text != expectedText) return ChatDraftInputResult(ChatDraftInputStatus.INPUT_NOT_EMPTY)
            writes.add(expectedText to text); this.text = text
            return ChatDraftInputResult(ChatDraftInputStatus.WRITTEN)
        }
        override fun verifyDraft(target: ChatDraftInputTarget, text: String): ChatDraftInputResult {
            reads.add(text)
            return ChatDraftInputResult(failure ?: if (this.text == text) ChatDraftInputStatus.VERIFIED else ChatDraftInputStatus.TEXT_MISMATCH)
        }
    }
    private val window = WindowRef("pkg", "activity", 1, 1)
    private val target = ChatDraftInputTarget("pkg", "activity", "w", "c", "s", NodeTarget(window, NodePath(emptyList())))
    private fun coordinator(input: Input, scheduler: Scheduler) = AiReplyDraftCoordinator(
        object : AiModelConfigurationRepository {
            override fun load(): AiModelConfiguration = error("unused")
            override fun save(configuration: AiModelConfiguration) = Unit
            override fun clearApiKey() = Unit
        }, AiModelCloudConsent { true }, object : AiReplyPersonalizationRepository {
            override fun load(): AiReplyPersonalization = error("unused")
            override fun save(personalization: AiReplyPersonalization) = Unit
        }, input, scheduler, { true }, { false }, {}, { error("unused") }, chooseRandomInt = { _, _ -> 500 }
    )

    @Test fun writesAndVerifiesEachPrefixThenRequiresUser() {
        val input = Input(); val scheduler = Scheduler(); val coordinator = coordinator(input, scheduler)
        coordinator.writeNext(target, "abcdef", listOf("abc", "def"), 0, "", 0)
        assertEquals(listOf("" to "abc"), input.writes)
        scheduler.next(); scheduler.next()
        assertEquals(listOf("" to "abc", "abc" to "abcdef"), input.writes)
        assertEquals(listOf("abc", "abcdef", "abcdef"), input.reads)
        assertEquals(listOf(500L, 500L), scheduler.delays)
        assertTrue(coordinator.isAwaitingUserSend)
    }

    @Test fun editsAndFailuresStopWithoutOverwriting() {
        for (status in listOf(null, ChatDraftInputStatus.STALE_CHAT, ChatDraftInputStatus.INPUT_NOT_FOUND, ChatDraftInputStatus.TEXT_MISMATCH)) {
            val input = Input(); val scheduler = Scheduler(); val coordinator = coordinator(input, scheduler)
            coordinator.writeNext(target, "abcdef", listOf("abc", "def"), 0, "", 0)
            if (status == null) input.text = "user edit" else input.failure = status
            scheduler.next()
            assertEquals(1, input.writes.size)
            assertTrue(scheduler.tasks.isEmpty())
            assertFalse(coordinator.isAwaitingUserSend)
            if (status == null) assertEquals("user edit", input.text)
        }
    }

    @Test fun resetInvalidatesScheduledCallback() {
        val input = Input(); val scheduler = Scheduler(); val coordinator = coordinator(input, scheduler)
        coordinator.writeNext(target, "abcdef", listOf("abc", "def"), 0, "", 0)
        coordinator.resetForRuntimeReset(); scheduler.next()
        assertEquals(1, input.writes.size)
        assertTrue(input.reads.isEmpty())
        assertFalse(coordinator.isAwaitingUserSend)
    }
}
