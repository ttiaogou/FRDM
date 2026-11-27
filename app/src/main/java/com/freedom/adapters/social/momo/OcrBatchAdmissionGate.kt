package com.freedom.adapters.social.momo

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prevents a second OCR batch from entering the single-thread worker while an earlier batch is
 * still running, including the interval after its caller-facing timeout while Binder is blocked.
 */
internal class OcrBatchAdmissionGate {
    private val occupied = AtomicBoolean(false)

    fun tryAcquire(): Lease? {
        if (!occupied.compareAndSet(false, true)) return null
        return Lease {
            occupied.set(false)
        }
    }

    internal class Lease(private val releaseAction: () -> Unit) : Closeable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) releaseAction()
        }
    }
}

/** A once-invalidated read may never resume from the same captured frame. No new owner. */
internal fun retainOcrReadValidity(isCurrent: () -> Boolean): () -> Boolean {
    val invalidated = AtomicBoolean(false)
    return {
        if (!invalidated.get() && !runCatching(isCurrent).getOrDefault(false)) invalidated.set(true)
        !invalidated.get()
    }
}
