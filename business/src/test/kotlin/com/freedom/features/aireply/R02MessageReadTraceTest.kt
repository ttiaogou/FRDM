package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.aireply.ports.ChatMessagePort
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/** Same scheduler/port fixture semantics as the existing read-coordinator tests. */
class R02MessageReadTraceTest {
    @Test
    fun timedOutReadLateCallbackAndDuplicateResultDoNotReachNewBusinessRead() {
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        val page = snapshot("fixture")
        val trace = mutableListOf<String>()
        val reader = coordinator(scheduler, port, { page },
            onResult = { _, batch, _ -> trace += "result:${batch.capturedAtMillis}" },
            onTimeout = { delay -> trace += "timeout:$delay" })
        reader.request(page)
        val old = requireNotNull(port.pendingCallback)
        trace += "first:${reader.isInFlight}"
        scheduler.runNext(ChatMessageReadCoordinator.DEFAULT_CALLBACK_TIMEOUT_MS)
        trace += "after-timeout:${reader.isInFlight}"
        old(batch(1L))
        scheduler.runNext(0L)
        trace += "after-late:${reader.isInFlight}"
        reader.request(page)
        val fresh = requireNotNull(port.pendingCallback)
        fresh(batch(2L))
        fresh(batch(3L))
        trace += "before-delivery:${reader.isInFlight}"
        scheduler.runNext(0L)
        trace += "done:${reader.isInFlight}"
        assertEquals(listOf("first:true", "timeout:65000", "after-timeout:false",
            "after-late:false", "before-delivery:true", "result:2", "done:false"), trace)
    }

    private fun coordinator(
        scheduler: FakeScheduler = FakeScheduler(),
        port: TestMessagePort,
        inspect: () -> ChatPageSnapshot?,
        allowed: () -> Boolean = { true },
        current: (ChatPageSnapshot, ChatMessageBatch, ChatPageSnapshot) -> Boolean = { _, _, _ -> true },
        shouldDrain: () -> Boolean = { true },
        onResult: (ChatPageSnapshot, ChatMessageBatch, ChatPageSnapshot) -> Unit = { _, _, _ -> },
        onTimeout: (Long) -> Unit = {},
        onFailure: () -> Unit = {},
        onRejected: () -> Boolean = { false }
    ) = ChatMessageReadCoordinator(
        scheduler = scheduler,
        messagePort = port,
        isReadRequestAllowed = allowed,
        inspectActivePage = inspect,
        isResultCurrent = current,
        shouldDrainPendingSnapshot = shouldDrain,
        onValidatedResult = onResult,
        onTimeout = onTimeout,
        onAdapterFailure = onFailure,
        onRejectedResult = onRejected
    )

    private fun snapshot(activity: String) = ChatPageSnapshot(
        packageName = "com.immomo.momo",
        activityClass = activity,
        pageKind = ChatPageKind.UNKNOWN,
        scannedNodeCount = 0,
        editableInputCount = 0,
        messageListCount = 0,
        sendActionCount = 0,
        messageRelatedViewIdCount = 0,
        hasChatTab = false,
        messageListTarget = null,
        inputFieldTarget = null
    )

    private fun batch(capturedAt: Long = 10L) = ChatMessageBatch(
        status = MessageReadStatus.EMPTY_CONFIRMED,
        windowSessionKey = null,
        messages = emptyList(),
        scannedRowCount = 0,
        candidateRowCount = 0,
        truncated = false,
        capturedAtMillis = capturedAt
    )

    private class TestMessagePort : ChatMessagePort {
        var ocrReuseInvalidations = 0
        override fun invalidateOcrRecognitionReuse() {
            ocrReuseInvalidations++
        }

        var sendProofReads = 0
        var workerAdmission: (() -> Boolean)? = null
        var confirmedBaselineObservationId: String? = null

        override fun readRecentMessagesForSendConfirmationAsync(
            page: ChatPageSnapshot, limit: Int, isRequestCurrent: () -> Boolean,
            confirmedBaselineObservationId: String?, onResult: (ChatMessageBatch) -> Unit
        ) {
            this.confirmedBaselineObservationId = confirmedBaselineObservationId
            readRecentMessagesForSendConfirmationAsync(page, limit, isRequestCurrent, onResult)
        }

        override fun readRecentMessagesAsync(
            page: ChatPageSnapshot, limit: Int, isRequestCurrent: () -> Boolean,
            onResult: (ChatMessageBatch) -> Unit
        ) {
            workerAdmission = isRequestCurrent
            readRecentMessagesAsync(page, limit, onResult)
        }

        override fun readRecentMessagesForSendConfirmationAsync(
            page: ChatPageSnapshot, limit: Int, isRequestCurrent: () -> Boolean,
            onResult: (ChatMessageBatch) -> Unit
        ) {
            workerAdmission = isRequestCurrent
            readRecentMessagesForSendConfirmationAsync(page, limit, onResult)
        }

        override fun readRecentMessagesForSendConfirmationAsync(
            page: ChatPageSnapshot, limit: Int, onResult: (ChatMessageBatch) -> Unit
        ) {
            sendProofReads++
            readRecentMessagesAsync(page, limit, onResult)
        }

        var synchronousResult: ChatMessageBatch? = null
        var throwOnRead = false
        var pendingCallback: ((ChatMessageBatch) -> Unit)? = null
        val requestedSnapshots = mutableListOf<ChatPageSnapshot>()

        override fun readRecentMessages(page: ChatPageSnapshot, limit: Int): ChatMessageBatch =
            synchronousResult ?: ChatMessageBatch(
                status = MessageReadStatus.EMPTY_CONFIRMED,
                windowSessionKey = null,
                messages = emptyList(),
                scannedRowCount = 0,
                candidateRowCount = 0,
                truncated = false,
                capturedAtMillis = 0L
            )

        override fun readRecentMessagesAsync(
            page: ChatPageSnapshot,
            limit: Int,
            onResult: (ChatMessageBatch) -> Unit
        ) {
            requestedSnapshots += page
            if (throwOnRead) throw IllegalStateException("test adapter failure")
            val immediate = synchronousResult
            if (immediate != null) onResult(immediate) else pendingCallback = onResult
        }
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(
            val delayMillis: Long,
            val callback: () -> Unit,
            var cancelled: Boolean = false
        )

        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun pendingDelays(): List<Long> = entries.filterNot { it.cancelled }.map { it.delayMillis }

        fun runNext(delayMillis: Long) {
            val entry = entries.firstOrNull { !it.cancelled && it.delayMillis == delayMillis }
                ?: error("No pending scheduled task with delay=$delayMillis; pending=${pendingDelays()}")
            entry.cancelled = true
            entry.callback()
        }
    }
}
