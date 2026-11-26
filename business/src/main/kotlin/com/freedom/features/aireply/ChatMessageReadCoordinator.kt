package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.aireply.ports.ChatMessagePort
import com.freedom.features.aireply.ports.ChatPageSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the asynchronous lifecycle of one bounded visible-message read.
 *
 * This coordinator handles callback arbitration, timeout invalidation, latest-snapshot coalescing,
 * callback-thread handoff and active-page result validation. It does not interpret message content,
 * persist samples, update task counters or decide whether a result belongs to a private-message or
 * received-greetings workflow; the owning runtime supplies those decisions through callbacks.
 */
internal class ChatMessageReadCoordinator(
    private val scheduler: TaskScheduler,
    private val messagePort: ChatMessagePort,
    private val isReadRequestAllowed: () -> Boolean,
    private val inspectActivePage: () -> ChatPageSnapshot?,
    private val isResultCurrent: (
        requestedSnapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        activeSnapshot: ChatPageSnapshot
    ) -> Boolean,
    private val shouldDrainPendingSnapshot: () -> Boolean,
    private val onValidatedResult: (
        requestedSnapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        activeSnapshot: ChatPageSnapshot
    ) -> Unit,
    private val onTimeout: (timeoutMs: Long) -> Unit,
    private val onAdapterFailure: () -> Unit,
    private val timeoutMs: Long = DEFAULT_CALLBACK_TIMEOUT_MS,
    private val messageLimit: Int = DEFAULT_MESSAGE_LIMIT
) {
    companion object {
        const val DEFAULT_CALLBACK_TIMEOUT_MS = 65_000L
        const val DEFAULT_MESSAGE_LIMIT = 20
    }

    // The adapter worker may only read this generation, not scheduler-owned runtime state.
    @Volatile private var requestGeneration = 0L
    private var requestInFlight = false
    private var ocrInFlight = false
    private var timeoutTask: ScheduledTask? = null
    private var pendingSnapshot: ChatPageSnapshot? = null

    init {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        require(messageLimit > 0) { "messageLimit must be positive" }
    }

    val isInFlight: Boolean
        get() = requestInFlight

    /**
     * Coalesces structural reads while structure is in flight. During OCR, page events remain
     * observations, but no queued snapshot is replayed into a second OCR transaction.
     */
    fun request(snapshot: ChatPageSnapshot) {
        if (!isReadRequestAllowed()) return
        if (requestInFlight) {
            // The OCR stage owns the whole read until its result is delivered.
            // Never replay a queued structural snapshot after OCR; it can contain
            // transient overlay/layout changes and stale message geometry.
            if (!ocrInFlight) pendingSnapshot = snapshot
            return
        }
        pendingSnapshot = null
        startRead(
            read = { _, callback -> messagePort.readRecentMessagesStructureAsync(snapshot, messageLimit, callback) },
            forceScheduledDelivery = false,
            timedOut = onTimeout,
            failed = onAdapterFailure
        ) { batch, revalidate, generation ->
            completeRequest(snapshot, batch, generation, revalidate)
        }
    }

    /** Continuation after identity or send-proof admission. Shares the structural single-flight. */
    fun requestOcr(
        snapshot: ChatPageSnapshot,
        limit: Int,
        forceScheduledDelivery: Boolean = false,
        onTimeout: (Long) -> Unit,
        onFailure: () -> Unit,
        sendConfirmation: Boolean = false,
        onResult: (ChatMessageBatch, Boolean) -> Unit
    ): Boolean {
        if (!isReadRequestAllowed() || requestInFlight) return false
        pendingSnapshot = null
        ocrInFlight = true
        startRead(
            read = { isCurrent, callback ->
                if (sendConfirmation) messagePort.readRecentMessagesForSendConfirmationAsync(snapshot, limit, isCurrent, callback)
                else messagePort.readRecentMessagesAsync(snapshot, limit, isCurrent, callback)
            },
            forceScheduledDelivery = forceScheduledDelivery,
            timedOut = onTimeout,
            failed = onFailure
        ) { batch, revalidate, generation ->
            if (generation == requestGeneration && requestInFlight) {
                closeRead()
                // The owning runtime applies the existing draft/send-specific page,
                // identity and lease checks before accepting the batch.
                onResult(batch, revalidate || batch.status == MessageReadStatus.WINDOW_STALE)
            }
        }
        return true
    }

    private fun closeRead() {
        timeoutTask?.cancel()
        timeoutTask = null
        requestInFlight = false
        ocrInFlight = false
    }

    private fun startRead(
        read: (() -> Boolean, (ChatMessageBatch) -> Unit) -> Unit,
        forceScheduledDelivery: Boolean,
        timedOut: (Long) -> Unit,
        failed: () -> Unit,
        deliverResult: (ChatMessageBatch, Boolean, Long) -> Unit
    ) {
        requestInFlight = true
        val expectedGeneration = ++requestGeneration
        timeoutTask?.cancel()

        // The adapter callback may run on a worker thread. The gate arbitrates callback, timeout
        // and adapter exception. The timeout handle is local so callback receipt can cancel it
        // before scheduler delivery is queued.
        val callbackGate = Any()
        var callbackReceived = false
        var requestClosedWithoutCallback = false
        val timeoutTaskRef = AtomicReference<ScheduledTask?>()
        val scheduledTimeout = scheduler.schedule(timeoutMs) {
            val shouldHandleTimeout = synchronized(callbackGate) {
                if (callbackReceived || requestClosedWithoutCallback) {
                    false
                } else {
                    requestClosedWithoutCallback = true
                    true
                }
            }
            if (!shouldHandleTimeout || expectedGeneration != requestGeneration || !requestInFlight) {
                return@schedule
            }

            // Invalidate before reopening the in-flight gate; any late adapter callback becomes stale.
            requestGeneration += 1L
            closeRead()
            pendingSnapshot = null
            timedOut(timeoutMs)
        }
        timeoutTask = scheduledTimeout
        timeoutTaskRef.set(scheduledTimeout)

        val callThread = Thread.currentThread()
        val callReturned = AtomicBoolean(false)
        try {
            read({ expectedGeneration == requestGeneration }) { batch ->
                val shouldDeliver = synchronized(callbackGate) {
                    if (callbackReceived) {
                        false
                    } else {
                        callbackReceived = true
                        if (!requestClosedWithoutCallback) timeoutTaskRef.get()?.cancel()
                        true
                    }
                }
                if (shouldDeliver) {
                    // Keep the original runtime contract: an inline callback on the invoking
                    // thread reuses the requested snapshot, while asynchronous or cross-thread
                    // callbacks must re-inspect the active page before accepting the result.
                    val shouldRevalidateActivePage =
                        forceScheduledDelivery || callReturned.get() || Thread.currentThread() != callThread
                    val deliver = {
                        deliverResult(batch, shouldRevalidateActivePage, expectedGeneration)
                    }
                    if (!shouldRevalidateActivePage) {
                        // Preserve synchronous adapter semantics. OCR/screenshot callbacks that
                        // cross a thread boundary are delivered through the injected scheduler.
                        deliver()
                    } else {
                        scheduler.schedule(0L, deliver)
                    }
                }
            }
        } catch (_: Exception) {
            val shouldHandleException = synchronized(callbackGate) {
                if (callbackReceived || requestClosedWithoutCallback) {
                    false
                } else {
                    requestClosedWithoutCallback = true
                    true
                }
            }
            if (shouldHandleException && expectedGeneration == requestGeneration) {
                requestGeneration += 1L
                timeoutTaskRef.get()?.cancel()
                closeRead()
                pendingSnapshot = null
                failed()
            }
        } finally {
            callReturned.set(true)
        }
    }

    /** Invalidates the active callback and drops any queued snapshot at a task/runtime boundary. */
    fun invalidatePending() {
        requestGeneration += 1L
        closeRead()
        pendingSnapshot = null
    }

    private fun completeRequest(
        requestedSnapshot: ChatPageSnapshot,
        batch: ChatMessageBatch,
        expectedGeneration: Long,
        revalidateActivePage: Boolean
    ) {
        if (expectedGeneration != requestGeneration || !requestInFlight) return

        closeRead()
        val mayDrainPending = shouldDrainPendingSnapshot()

        if (isReadRequestAllowed()) {
            val activeSnapshot = if (revalidateActivePage) {
                runCatching { inspectActivePage() }.getOrNull()
            } else {
                requestedSnapshot
            }
            if (activeSnapshot != null && isResultCurrent(requestedSnapshot, batch, activeSnapshot)) {
                onValidatedResult(requestedSnapshot, batch, activeSnapshot)
            }
        }

        // The validated structural callback may synchronously start OCR or a
        // replacement structure read. Do not drain stale snapshots into that transaction.
        if (expectedGeneration != requestGeneration || requestInFlight) return
        val pending = pendingSnapshot
        pendingSnapshot = null
        if (mayDrainPending && pending != null && pending.stableKey() != requestedSnapshot.stableKey()) {
            request(pending)
        }
    }
}
