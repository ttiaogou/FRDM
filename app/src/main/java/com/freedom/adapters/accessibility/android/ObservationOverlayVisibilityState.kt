package com.freedom.adapters.accessibility.android

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Idempotent handle for one monitor-overlay observation hold. */
class ObservationVisibilityLease(
    private val releaseAction: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    override fun close() {
        // Serialize close with teardown draining. A teardown caller that observes an
        // already-closed lease must also know its releaseAction has fully completed.
        synchronized(this) {
            if (closed.compareAndSet(false, true)) releaseAction()
        }
    }
}


/**
 * Platform-neutral visibility state for the monitor overlay.
 * Observation hides are reference-counted so overlapping screenshot/OCR tasks cannot
 * restore the panel while another task still needs an unobstructed display.
 */
internal class ObservationOverlayVisibilityState {
    private val lock = Any()
    private var holdCount: Int = 0

    val activeHolds: Int
        get() = synchronized(lock) { holdCount }

    fun acquire(): Boolean = synchronized(lock) {
        val isFirstHold = holdCount == 0
        check(holdCount < Int.MAX_VALUE) { "Too many active overlay observation holds" }
        holdCount += 1
        isFirstHold
    }

    /** Returns true when the final observation hold has been released. */
    fun release(): Boolean = synchronized(lock) {
        if (holdCount == 0) return@synchronized false
        holdCount -= 1
        holdCount == 0
    }

    fun shouldBeVisible(
        floatingLogEnabled: Boolean,
        touchPassthrough: Boolean
    ): Boolean = synchronized(lock) {
        floatingLogEnabled && !touchPassthrough && holdCount == 0
    }

    fun isObservationHidden(): Boolean = synchronized(lock) { holdCount > 0 }
}
