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

class ChatMessageReadCoordinatorTest {
    @Test
    fun ordinaryReadRetryCleanupDoesNotErasePendingSendProofBackoff() {
        val coordinator = coordinator(FakeScheduler(), TestMessagePort(), inspect = { snapshot("chat") })
        coordinator.deferredSendProofRetryAttempt = 3
        coordinator.renewVisit()
        coordinator.invalidateMessageReadTransaction()
        coordinator.cancelMessageReadRetry()
        assertEquals(3, coordinator.deferredSendProofRetryAttempt)
    }

    @Test
    fun newVisitAndReadInvalidationRejectPreviousWorkerEvenInSameWindow() {
        val scheduler = FakeScheduler()
        val page = snapshot("same-chat")
        val port = TestMessagePort()
        val coordinator = coordinator(scheduler, port, inspect = { page })
        val originalVisit = coordinator.draftVisitToken
        coordinator.deferredPrivateReadReturnVisit = originalVisit
        var oldDeliveries = 0
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {}) { _, _ ->
            oldDeliveries++
        })
        val oldAdmission = requireNotNull(port.workerAdmission)
        val oldCallback = requireNotNull(port.pendingCallback)
        var admissionCancelled = false
        val admissionGeneration = coordinator.ocrAdmissionRetryGeneration
        coordinator.ocrAdmissionRetryTask = ScheduledTask { admissionCancelled = true }
        coordinator.ocrAdmissionRetryAttempt = 2

        coordinator.renewVisit()
        coordinator.invalidateMessageReadTransaction()
        assertTrue(coordinator.draftVisitToken != originalVisit)
        assertEquals(null, coordinator.deferredPrivateReadReturnVisit)
        assertTrue(admissionCancelled)
        assertEquals(admissionGeneration + 1L, coordinator.ocrAdmissionRetryGeneration)
        assertEquals(0, coordinator.ocrAdmissionRetryAttempt)
        assertEquals(null, coordinator.ocrAdmissionRetryTask)
        assertFalse(oldAdmission())
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {}) { _, _ -> })
        val replacement = requireNotNull(port.workerAdmission)
        oldCallback(batch())
        scheduler.runNext(0L)
        assertEquals(0, oldDeliveries)
        assertTrue(replacement())
        assertTrue(coordinator.isInFlight)
    }

    @Test
    fun retryCleanupCancelsOriginalHandlesAndResetsOnlyTheirBudgets() {
        val page = snapshot("same-chat")
        val coordinator = coordinator(FakeScheduler(), TestMessagePort(), inspect = { page })
        val failure = com.freedom.features.aireply.ai.AiReplyPreSendModelFailure(
            requestId = "failed-request",
            requestKey = "failed-key",
            conversationKey = "conversation",
            windowSessionKey = "window",
            visitToken = coordinator.draftVisitToken,
            taskLease = null,
            reasonCode = "MODEL_DECISION_MODEL_CALL_FAILED",
            attempts = 1,
            retryDelayMs = 30_000L,
            readyToDefer = false
        )
        val cancelled = mutableListOf<String>()
        coordinator.greetingModelFailureRecovery = failure
        coordinator.greetingModelFailureRecoveryTask = ScheduledTask { cancelled += "greeting" }
        coordinator.privateModelFailureRecovery = failure
        coordinator.privateModelFailureRecoveryTask = ScheduledTask { cancelled += "private" }
        coordinator.draftRetryTask = ScheduledTask { cancelled += "draft" }
        coordinator.draftReadRetries = 2
        coordinator.privateOcrRecoveryAttempt = 3
        coordinator.privateModelRetryAwaitingDraft = true
        coordinator.messageReadRetryTask = ScheduledTask { cancelled += "list" }
        coordinator.messageReadRetryCount = 2

        coordinator.clearGreetingModelFailureRetry()
        assertEquals(listOf("greeting"), cancelled)
        assertEquals(null, coordinator.greetingModelFailureRecovery)
        assertEquals(null, coordinator.greetingModelFailureRecoveryTask)
        assertTrue(coordinator.privateModelFailureRecovery === failure)
        coordinator.clearPrivateReadAndModelRetry()
        assertEquals(listOf("greeting", "draft", "private"), cancelled)
        assertEquals(null, coordinator.privateModelFailureRecovery)
        assertEquals(null, coordinator.privateModelFailureRecoveryTask)
        assertEquals(null, coordinator.draftRetryTask)
        assertEquals(0, coordinator.draftReadRetries)
        assertEquals(0, coordinator.privateOcrRecoveryAttempt)
        assertFalse(coordinator.privateModelRetryAwaitingDraft)
        assertEquals(2, coordinator.messageReadRetryCount)
        coordinator.cancelMessageReadRetry()
        assertEquals(listOf("greeting", "draft", "private", "list"), cancelled)
        assertEquals(null, coordinator.messageReadRetryTask)
        assertEquals(0, coordinator.messageReadRetryCount)
    }

    @Test
    fun invalidationRevokesWorkerAdmissionForBothOcrPurposesAndNeverRevivesOldRead() {
        val scheduler = FakeScheduler()
        val page = snapshot("same-chat")
        val port = TestMessagePort()
        val coordinator = coordinator(scheduler, port, inspect = { page })
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {}) { _, _ ->
            error("invalidated read must not reach business")
        })
        val old = requireNotNull(port.workerAdmission)
        val oldCallback = requireNotNull(port.pendingCallback)
        assertTrue(old())
        coordinator.invalidatePending()
        assertEquals(1, port.ocrReuseInvalidations)
        assertFalse(old())
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {},
            sendConfirmation = true) { _, _ -> })
        val replacement = requireNotNull(port.workerAdmission)
        assertTrue(replacement())
        assertFalse(old())
        oldCallback(batch())
        scheduler.runNext(0L)
        assertTrue(replacement())
        scheduler.runNext(ChatMessageReadCoordinator.DEFAULT_CALLBACK_TIMEOUT_MS)
        assertEquals(2, port.ocrReuseInvalidations)
        assertFalse(replacement())
        assertFalse(old())
    }

    @Test
    fun onlySendConfirmationUsesTheDedicatedReadPurpose() {
        val scheduler = FakeScheduler()
        val page = snapshot("send-proof")
        val port = TestMessagePort()
        val coordinator = coordinator(scheduler, port, inspect = { page })
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {}) { _, _ -> })
        assertEquals(0, port.sendProofReads)
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {},
            sendConfirmation = true) { _, _ -> })
        assertEquals(1, port.sendProofReads)
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)
        assertFalse(coordinator.isInFlight)
    }

    @Test
    fun confirmedBaselineObservationIsForwardedOnlyForExplicitSegmentPreparation() {
        val scheduler = FakeScheduler()
        val page = snapshot("send-proof")
        val port = TestMessagePort()
        val coordinator = coordinator(scheduler, port, inspect = { page })
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {},
            sendConfirmation = true, confirmedBaselineObservationId = "confirmed-batch") { _, _ -> })
        assertEquals("confirmed-batch", port.confirmedBaselineObservationId)
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {},
            sendConfirmation = true) { _, _ -> })
        assertEquals(null, port.confirmedBaselineObservationId)
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)
        assertFalse(coordinator.isInFlight)
    }

    @Test
    fun structuralContinuationOwnsOcrUntilSchedulerDeliveryWithoutReplayingPendingSnapshots() {
        val page = snapshot("same-chat")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        var delivered = 0
        lateinit var coordinator: ChatMessageReadCoordinator
        coordinator = coordinator(scheduler, port, inspect = { page }, onResult = { requested, _, _ ->
            assertTrue(coordinator.requestOcr(requested, 10, onTimeout = {}, onFailure = {}) { _, fresh ->
                assertTrue(fresh)
                delivered++
            })
        })
        coordinator.request(page)
        coordinator.request(snapshot("queued-layout-change"))
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)
        assertEquals(2, port.requestedSnapshots.size)
        assertTrue(coordinator.isInFlight)
        repeat(300) { coordinator.request(page) }
        assertFalse(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {}) { _, _ ->
            error("duplicate OCR must not be started")
        })
        val callback = requireNotNull(port.pendingCallback)
        callback(batch())
        callback(batch())
        repeat(300) { coordinator.request(page) }
        assertTrue(coordinator.isInFlight)
        assertEquals(2, port.requestedSnapshots.size)
        scheduler.runNext(0L)
        assertEquals(1, delivered)
        assertFalse(coordinator.isInFlight)
        assertEquals(2, port.requestedSnapshots.size)
    }

    @Test
    fun ocrTimeoutAndLateCallbackCannotReleaseNewRead() {
        val page = snapshot("same-chat")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        var timeouts = 0
        var delivered = 0
        val coordinator = coordinator(scheduler, port, inspect = { page })
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = { timeouts++ }, onFailure = {}) { _, _ ->
            error("timed out OCR must not reach business")
        })
        val old = requireNotNull(port.pendingCallback)
        scheduler.runNext(ChatMessageReadCoordinator.DEFAULT_CALLBACK_TIMEOUT_MS)
        assertEquals(1, timeouts)
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {}) { _, _ -> delivered++ })
        old(batch())
        scheduler.runNext(0L)
        assertTrue(coordinator.isInFlight)
        assertEquals(0, delivered)
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)
        assertEquals(1, delivered)
        assertFalse(coordinator.isInFlight)
    }

    @Test
    fun invalidatingOcrDropsAlreadyQueuedResultWithoutClosingReplacementRead() {
        val page = snapshot("same-chat")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        var delivered = 0
        val coordinator = coordinator(scheduler, port, inspect = { page })
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {}) { _, _ ->
            error("old generation")
        })
        requireNotNull(port.pendingCallback)(batch())
        coordinator.invalidatePending()
        assertTrue(coordinator.requestOcr(page, 10, onTimeout = {}, onFailure = {}) { _, _ -> delivered++ })
        scheduler.runNext(0L)
        assertTrue(coordinator.isInFlight)
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)
        assertEquals(1, delivered)
    }

    @Test
    fun synchronousAdapterCallbackRemainsSynchronous() {
        val snapshot = snapshot("sync")
        val batch = batch()
        val port = TestMessagePort().apply { synchronousResult = batch }
        var delivered = 0
        var activePageInspections = 0
        val coordinator = coordinator(
            port = port,
            inspect = { activePageInspections++; snapshot },
            onResult = { requested, result, active ->
                assertEquals(snapshot, requested)
                assertEquals(batch, result)
                assertEquals(snapshot, active)
                delivered++
            }
        )

        coordinator.request(snapshot)

        assertEquals(1, delivered)
        assertEquals(0, activePageInspections)
        assertFalse(coordinator.isInFlight)
    }

    @Test
    fun asynchronousCallbackIsHandedToSchedulerAndCancelsTimeoutImmediately() {
        val snapshot = snapshot("async")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        var delivered = 0
        val coordinator = coordinator(
            scheduler = scheduler,
            port = port,
            inspect = { snapshot },
            onResult = { _, _, _ -> delivered++ }
        )

        coordinator.request(snapshot)
        val callback = requireNotNull(port.pendingCallback)
        assertTrue(coordinator.isInFlight)
        assertEquals(listOf(ChatMessageReadCoordinator.DEFAULT_CALLBACK_TIMEOUT_MS), scheduler.pendingDelays())

        callback(batch())

        assertEquals(0, delivered)
        assertEquals(listOf(0L), scheduler.pendingDelays())
        scheduler.runNext(0L)
        assertEquals(1, delivered)
        assertFalse(coordinator.isInFlight)
    }

    @Test
    fun duplicateAdapterCallbacksDeliverOnlyTheFirstBatch() {
        val snapshot = snapshot("duplicate")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        var deliveredIds = mutableListOf<Long>()
        val coordinator = coordinator(
            scheduler = scheduler,
            port = port,
            inspect = { snapshot },
            onResult = { _, result, _ -> deliveredIds += result.capturedAtMillis }
        )

        coordinator.request(snapshot)
        val callback = requireNotNull(port.pendingCallback)
        callback(batch(capturedAt = 1L))
        callback(batch(capturedAt = 2L))
        scheduler.runNext(0L)

        assertEquals(listOf(1L), deliveredIds)
    }

    @Test
    fun timeoutInvalidatesLateCallbackAndRunsTimeoutHandlerOnce() {
        val snapshot = snapshot("timeout")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        var delivered = 0
        var timeouts = 0
        val coordinator = coordinator(
            scheduler = scheduler,
            port = port,
            inspect = { snapshot },
            onResult = { _, _, _ -> delivered++ },
            onTimeout = { timeout ->
                assertEquals(ChatMessageReadCoordinator.DEFAULT_CALLBACK_TIMEOUT_MS, timeout)
                timeouts++
            }
        )

        coordinator.request(snapshot)
        scheduler.runNext(ChatMessageReadCoordinator.DEFAULT_CALLBACK_TIMEOUT_MS)
        assertEquals(1, timeouts)
        assertFalse(coordinator.isInFlight)

        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)
        assertEquals(0, delivered)
        assertEquals(1, timeouts)
    }

    @Test
    fun adapterExceptionClosesRequestAndCancelsWatchdog() {
        val snapshot = snapshot("exception")
        val scheduler = FakeScheduler()
        val port = TestMessagePort().apply { throwOnRead = true }
        var failures = 0
        val coordinator = coordinator(
            scheduler = scheduler,
            port = port,
            inspect = { snapshot },
            onFailure = { failures++ }
        )

        coordinator.request(snapshot)

        assertEquals(1, failures)
        assertFalse(coordinator.isInFlight)
        assertTrue(scheduler.pendingDelays().isEmpty())
    }

    @Test
    fun onlyNewestPendingSnapshotIsReadAfterCurrentRequestCompletes() {
        val first = snapshot("first")
        val stalePending = snapshot("stale-pending")
        val newestPending = snapshot("newest-pending")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        val coordinator = coordinator(
            scheduler = scheduler,
            port = port,
            inspect = { null },
            current = { _, _, _ -> true }
        )

        coordinator.request(first)
        coordinator.request(stalePending)
        coordinator.request(newestPending)
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)

        assertEquals(2, port.requestedSnapshots.size)
        assertEquals(first, port.requestedSnapshots[0])
        assertEquals(newestPending, port.requestedSnapshots[1])
        assertTrue(coordinator.isInFlight)
    }

    @Test
    fun invalidationRejectsCallbackFromPreviousTaskGeneration() {
        val snapshot = snapshot("invalidated")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        var delivered = 0
        val coordinator = coordinator(
            scheduler = scheduler,
            port = port,
            inspect = { snapshot },
            onResult = { _, _, _ -> delivered++ }
        )

        coordinator.request(snapshot)
        coordinator.invalidatePending()
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)

        assertEquals(0, delivered)
        assertFalse(coordinator.isInFlight)
        assertTrue(scheduler.pendingDelays().isEmpty())
    }

    @Test
    fun staleActivePageCannotReachBusinessResultCallback() {
        val requested = snapshot("requested")
        val active = snapshot("different-active-page")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        var delivered = 0
        val coordinator = coordinator(
            scheduler = scheduler,
            port = port,
            inspect = { active },
            current = { _, _, _ -> false },
            onResult = { _, _, _ -> delivered++ }
        )

        coordinator.request(requested)
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)

        assertEquals(0, delivered)
    }

    @Test
    fun rejectedStructureReadRetiresQueuedSnapshotWhenOwnerRequestsFreshProof() {
        val first = snapshot("send-proof")
        val queued = snapshot("old-queued-page")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        var rejected = 0
        var delivered = 0
        val coordinator = coordinator(
            scheduler = scheduler,
            port = port,
            inspect = { first },
            current = { _, _, _ -> false },
            onResult = { _, _, _ -> delivered++ },
            onRejected = { rejected++; true }
        )
        coordinator.request(first)
        coordinator.request(queued)
        val callback = requireNotNull(port.pendingCallback)
        callback(batch())
        scheduler.runNext(0L)
        assertEquals(1, rejected)
        assertEquals(0, delivered)
        assertEquals(listOf(first), port.requestedSnapshots)
        assertFalse(coordinator.isInFlight)
        callback(batch())
        assertEquals(1, rejected)
        assertTrue(scheduler.pendingDelays().isEmpty())
    }

    @Test
    fun receivedGreetingsReadOnlyResultDoesNotDrainQueuedPrivateSnapshotWhenOwnerSaysHold() {
        val first = snapshot("greetings-detail")
        val pending = snapshot("private-detail")
        val scheduler = FakeScheduler()
        val port = TestMessagePort()
        val coordinator = coordinator(
            scheduler = scheduler,
            port = port,
            inspect = { first },
            shouldDrain = { false }
        )

        coordinator.request(first)
        coordinator.request(pending)
        requireNotNull(port.pendingCallback)(batch())
        scheduler.runNext(0L)

        assertEquals(1, port.requestedSnapshots.size)
        assertFalse(coordinator.isInFlight)
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
