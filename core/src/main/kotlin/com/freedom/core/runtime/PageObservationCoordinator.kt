package com.freedom.core.runtime

import com.freedom.core.accessibility.events.AccessibilityEventType

/**
 * Service-scoped coordinator for event-assisted page observations.
 *
 * Business areas register named channels with their own quiet periods and maximum burst waits.
 * The coordinator owns event coalescing only; each channel callback remains responsible for a
 * fresh, read-only snapshot and for handing its evidence to the owning business state machine.
 */
class PageObservationCoordinator(
    private val scheduler: TaskScheduler,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val channels = LinkedHashMap<String, PageObservationSubscription>()
    private val fingerprintRouter = PageFingerprintRouter()
    private val recoveryAdapterRegistry = PageRecoveryAdapterRegistry()

    /**
     * Single production registration for feature recovery adapters.
     * Raw fingerprint routes are observation-only and cannot duplicate or override
     * the task-scoped recovery registry.
     */
    @Synchronized
    fun registerRecoveryAdapter(adapter: PageRecoveryRouteAdapter) {
        check(adapter.routeId !in fingerprintRouter.registeredRouteIds()) {
            "Recovery adapter routeId conflicts with observation-only route: ${adapter.routeId}"
        }
        recoveryAdapterRegistry.register(adapter)
    }

    @Synchronized
    fun recoveryAdapterForRoute(routeId: String): PageRecoveryRouteAdapter? =
        recoveryAdapterRegistry.adapterForRoute(routeId)

    @Synchronized
    fun primaryRecoveryAdapterForTask(taskId: String): PageRecoveryRouteAdapter? =
        recoveryAdapterRegistry.primaryForTask(taskId)

    @Synchronized
    fun matchRecoveryAdapter(
        fingerprint: PageFingerprint,
        allowedTaskIds: Set<String>
    ): PageRecoveryRouteAdapter? = recoveryAdapterRegistry.match(fingerprint, allowedTaskIds)

    @Synchronized
    fun registeredRecoveryAdapterIds(): Set<String> = recoveryAdapterRegistry.registeredRouteIds()

    /** Observation-only routes cannot replace or unregister any business recovery adapter. */
    @Synchronized
    fun registerFingerprintRoute(route: PageFingerprintRoute) {
        check(recoveryAdapterRegistry.adapterForRoute(route.routeId) == null) {
            "Observation-only routeId conflicts with recovery adapter: ${route.routeId}"
        }
        fingerprintRouter.register(route)
    }

    @Synchronized
    fun unregisterFingerprintRoute(routeId: String) {
        fingerprintRouter.unregister(routeId)
    }

    /** Use this typed path to remove a feature-owned recovery adapter. */
    @Synchronized
    fun unregisterRecoveryAdapter(routeId: String) {
        recoveryAdapterRegistry.unregister(routeId)
    }

    /** Matches observation-only routes. Business recovery must call matchRecoveryAdapter. */
    fun matchFingerprint(fingerprint: PageFingerprint): String? = fingerprintRouter.match(fingerprint)

    @Synchronized
    fun registeredFingerprintRouteIds(): Set<String> = fingerprintRouter.registeredRouteIds()

    @Synchronized
    fun registerChannel(
        channelId: String,
        quietPeriodMs: Long,
        maxWaitMs: Long,
        onObservationRequested: () -> Unit,
        eventTypes: Set<AccessibilityEventType> = emptySet(),
        isEnabled: () -> Boolean = { true }
    ): PageObservationSubscription {
        require(channelId.isNotBlank()) { "channelId must not be blank" }
        check(channelId !in channels) { "Page observation channel already registered: $channelId" }
        val debouncer = EventAssistedDebouncer(
            scheduler = scheduler,
            quietPeriodMs = quietPeriodMs,
            maxWaitMs = maxWaitMs,
            nowMillis = nowMillis,
            task = onObservationRequested
        )
        lateinit var subscription: PageObservationSubscription
        subscription = PageObservationSubscription(channelId, eventTypes, isEnabled, debouncer) {
            unregister(channelId, subscription)
        }
        channels[channelId] = subscription
        return subscription
    }

    @Synchronized
    fun registeredChannelIds(): Set<String> = channels.keys.toSet()

    @Synchronized
    fun resetAll() {
        channels.values.toList().forEach(PageObservationSubscription::cancel)
    }

    /**
     * Schedules an explicit read-only page recheck (recovery, arrival stability or delayed
     * inspection) via the same service-level observation scheduler as event channels.
     * The owning business runtime still validates the returned evidence and makes decisions.
     */
    fun scheduleReadOnlyObservation(delayMillis: Long, onObservationRequested: () -> Unit): ScheduledTask {
        require(delayMillis >= 0L) { "delayMillis cannot be negative" }
        return scheduler.schedule(delayMillis, onObservationRequested)
    }

    /** Signals only enabled channels interested in this normalized event. */
    fun signal(eventType: AccessibilityEventType) {
        synchronized(this) {
            channels.values.toList()
        }.filter { it.accepts(eventType) }
            .forEach(PageObservationSubscription::requestSnapshot)
    }

    @Synchronized
    private fun unregister(channelId: String, subscription: PageObservationSubscription) {
        if (channels[channelId] === subscription) channels.remove(channelId)
    }

}

/** A single business area's registration in the shared page-observation coordinator. */
class PageObservationSubscription internal constructor(
    val channelId: String,
    private val eventTypes: Set<AccessibilityEventType>,
    private val isEnabled: () -> Boolean,
    private val debouncer: EventAssistedDebouncer,
    private val onClose: () -> Unit
) : AutoCloseable {
    @Volatile
    private var closed = false

    fun requestSnapshot() {
        if (!closed) debouncer.signal()
    }

    fun cancel() {
        if (!closed) debouncer.cancel()
    }

    fun isPending(): Boolean = !closed && debouncer.isPending()

    internal fun accepts(eventType: AccessibilityEventType): Boolean =
        !closed && isEnabled() && (eventTypes.isEmpty() || eventType in eventTypes)

    override fun close() {
        if (closed) return
        closed = true
        debouncer.cancel()
        onClose()
    }
}
