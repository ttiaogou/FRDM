package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.*
import com.freedom.core.runtime.*
import com.freedom.features.aireply.ports.*
import org.junit.Assert.*
import org.junit.Test


class R02DraftInputTraceTest {
    @Test
    fun orderedWritesReadsAndFixedDwellRemainUnchanged() {
        val input = Input()
        val scheduler = Scheduler()
        val coordinator = coordinator(input, scheduler)
        val trace = mutableListOf<String>()
        fun sample() {
            trace += "writes:${input.writes.joinToString("|") { "${it.first}>${it.second}" }}"
            trace += "reads:${input.reads.joinToString("|")}"
            trace += "delays:${scheduler.delays.joinToString("|")}"
        }
        coordinator.writeNext(target, "abcdef", listOf("abc", "def"), 0, "", 0)
        // Existing flow guarantees the first write and final exact verification order.
        assertEquals(listOf("" to "abc"), input.writes)
        scheduler.next()
        scheduler.next()
        sample()
        assertEquals(listOf("writes:>abc|abc>abcdef", "reads:abc|abcdef|abcdef", "delays:500|500"), trace)
        val edited = Input()
        val other = Scheduler()
        coordinator(edited, other).writeNext(target, "abcdef", listOf("abc", "def"), 0, "", 0)
        edited.text = "user edit"
        other.next()
        assertEquals(listOf("" to "abc"), edited.writes)
        assertEquals("user edit", edited.text)
        assertTrue(other.tasks.isEmpty())
    }

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
    private class Send : ChatDraftSendPort {
        var calls = 0
        override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult {
            calls++
            return ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
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
        }, input, scheduler, { true }, {}, { error("unused") }, chooseRandomInt = { _, _ -> 500 }
    )
}
