package com.freedom.core.runtime

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

    /** Creates the shared coordinator used by the AccessibilityService dependency graph. */
    /**
     * Compatibility constructor for the original two-channel API. New business areas should use
     * [registerChannel] so they can share one service-scoped instance without owning other channels.
     */
    constructor(
        scheduler: TaskScheduler,
        fullPageDebounceMs: Long,
        knownRegionDebounceMs: Long,
        maxEventBurstWaitMs: Long,
        runFullPageSnapshot: () -> Unit,
        runKnownRegionSnapshot: () -> Unit,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
    ) : this(scheduler, nowMillis) {
        legacyFullPage = registerChannel(
            channelId = LEGACY_FULL_PAGE_CHANNEL,
            quietPeriodMs = fullPageDebounceMs,
            maxWaitMs = maxEventBurstWaitMs,
            onObservationRequested = runFullPageSnapshot
        )
        legacyKnownRegion = registerChannel(
            channelId = LEGACY_KNOWN_REGION_CHANNEL,
            quietPeriodMs = knownRegionDebounceMs,
            maxWaitMs = maxEventBurstWaitMs,
            onObservationRequested = runKnownRegionSnapshot
        )
    }

    private var legacyFullPage: PageObservationSubscription? = null
    private var legacyKnownRegion: PageObservationSubscription? = null

    @Synchronized
    fun registerChannel(
        channelId: String,
        quietPeriodMs: Long,
        maxWaitMs: Long,
        onObservationRequested: () -> Unit
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
        subscription = PageObservationSubscription(channelId, debouncer) {
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

    /** Cancels all channels for compatibility with a coordinator owned by one runtime. */
    fun reset() = resetAll()

    fun requestFullPageSnapshot() = requireLegacyFullPage().requestSnapshot()

    fun requestKnownRegionSnapshot() = requireLegacyKnownRegion().requestSnapshot()

    fun cancelFullPageSnapshot() = requireLegacyFullPage().cancel()

    fun cancelKnownRegionSnapshot() = requireLegacyKnownRegion().cancel()

    fun isFullPageSnapshotPending(): Boolean = requireLegacyFullPage().isPending()

    fun isKnownRegionSnapshotPending(): Boolean = requireLegacyKnownRegion().isPending()

    private fun requireLegacyFullPage(): PageObservationSubscription =
        checkNotNull(legacyFullPage) { "Legacy full-page observation channel is not configured" }

    private fun requireLegacyKnownRegion(): PageObservationSubscription =
        checkNotNull(legacyKnownRegion) { "Legacy known-region observation channel is not configured" }

    @Synchronized
    private fun unregister(channelId: String, subscription: PageObservationSubscription) {
        if (channels[channelId] === subscription) channels.remove(channelId)
    }

    private companion object {
        const val LEGACY_FULL_PAGE_CHANNEL = "__legacy_full_page__"
        const val LEGACY_KNOWN_REGION_CHANNEL = "__legacy_known_region__"
    }
}

/** A single business area's registration in the shared page-observation coordinator. */
class PageObservationSubscription internal constructor(
    val channelId: String,
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

    override fun close() {
        if (closed) return
        closed = true
        debouncer.cancel()
        onClose()
    }
}
