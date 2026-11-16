package com.freedom.adapters.accessibility.android

/**
 * Tracks outstanding observation leases owned by the monitor overlay.
 * Teardown closes a snapshot; later screenshot/OCR callbacks may safely close the same
 * idempotent handles again.
 */
internal class ObservationVisibilityLeaseRegistry {
    private val lock = Any()
    private val leases = LinkedHashSet<ObservationVisibilityLease>()

    fun register(lease: ObservationVisibilityLease) {
        synchronized(lock) { leases.add(lease) }
    }

    fun remove(lease: ObservationVisibilityLease) {
        synchronized(lock) { leases.remove(lease) }
    }

    fun releaseAll(): Int {
        // Detach the current generation atomically before invoking callbacks. This keeps
        // the registry empty even when a lease callback is delayed or cannot unregister
        // itself during teardown. A lease registered concurrently belongs to the next run.
        val snapshot = synchronized(lock) {
            leases.toList().also { leases.clear() }
        }
        snapshot.forEach { lease -> runCatching { lease.close() } }
        return snapshot.size
    }

    internal fun activeLeaseCount(): Int = synchronized(lock) { leases.size }
}
