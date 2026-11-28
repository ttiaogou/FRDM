package com.freedom.features.aireply

import com.freedom.core.runtime.PageObservationCoordinator
import com.freedom.core.runtime.PageObservationSubscription
import com.freedom.core.runtime.ScheduledTask
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus

/**
 * AI Reply registrations in the service-scoped page-observation coordinator plus its greetings
 * arrival scheduler. The generic coalescing mechanism is shared with AccountBrowse; page
 * recognition and stability confirmation are supplied by the unified AI Reply observation path.
 */
internal class MomoPageObservationCoordinator(
    sharedCoordinator: PageObservationCoordinator,
    fullInspectionDebounceMs: Long,
    regionRefreshDebounceMs: Long,
    maxEventBurstInspectionWaitMs: Long,
    runFullInspection: () -> Unit,
    runRegionRefresh: () -> Unit,
    onFallbackCheckRequested: () -> Unit,
    onSnapshotRequested: () -> Unit = {},
    retryDelayMs: Long = 350L,
    maxStabilityRetries: Int = 8,
    fallbackDelayMs: () -> Long,
    isObservationActive: () -> Boolean = { true }
) {
    private val observationCoordinator = sharedCoordinator
    private var pageInspectionTask: ScheduledTask? = null
    private var fullInspectionCallbackToken = 0L
    /** Purpose carried with the next scheduled full observation. */
    var pendingPageReadPurpose = AiReplyPageReadPurpose.FULL_INSPECTION
    /** Startup navigation is page-structure verified and remains bounded. */
    var lastPageInspection: ChatPageSnapshot? = null
    /** Last positively confirmed inbox anchor; survives only same-window transient UNKNOWN samples. */
    var lastConfirmedChatListInspection: ChatPageSnapshot? = null
    var lastRegionFingerprint = ""
    private val fullPageChannel: PageObservationSubscription = observationCoordinator.registerChannel(
        channelId = FULL_INSPECTION_CHANNEL,
        quietPeriodMs = fullInspectionDebounceMs,
        maxWaitMs = maxEventBurstInspectionWaitMs,
        onObservationRequested = runFullInspection,
        isEnabled = isObservationActive
    )
    private val regionRefreshChannel: PageObservationSubscription = observationCoordinator.registerChannel(
        channelId = REGION_REFRESH_CHANNEL,
        quietPeriodMs = regionRefreshDebounceMs,
        maxWaitMs = maxEventBurstInspectionWaitMs,
        onObservationRequested = runRegionRefresh,
        isEnabled = isObservationActive
    )
    private val receivedGreetingsWatcher = ReceivedGreetingsPageArrivalWatcher(
        scheduleReadOnlyObservation = observationCoordinator::scheduleReadOnlyObservation,
        onFallbackCheckRequested = onFallbackCheckRequested,
        onSnapshotRequested = onSnapshotRequested,
        retryDelayMs = retryDelayMs,
        maxStabilityRetries = maxStabilityRetries,
        fallbackDelayMs = fallbackDelayMs
    )

    fun hasExplicitInspection(): Boolean = pageInspectionTask != null

    // Keep cancel and clear separate: Runtime reset has ordered cross-owner cancellation.
    fun cancelExplicitInspectionHandle() { pageInspectionTask?.cancel() }

    fun clearExplicitInspectionHandle() { pageInspectionTask = null }



    fun consumeFullInspectionPurpose(): AiReplyPageReadPurpose {
        // An event/watcher can satisfy an ordinary pending full observation before
        // its explicit timer fires. Retire that timer rather than losing its handle.
        // Protected send/scroll reads are still shielded at their existing entry gates.
        ++fullInspectionCallbackToken
        pageInspectionTask?.cancel()
        pageInspectionTask = null
        cancelFullInspection()
        val purpose = pendingPageReadPurpose
        pendingPageReadPurpose = AiReplyPageReadPurpose.FULL_INSPECTION
        return purpose
    }

    fun scheduleExplicitFullInspection(
        delayMs: Long,
        purpose: AiReplyPageReadPurpose,
        onObservationRequested: () -> Unit
    ) {
        cancelFullInspection()
        cancelRegionRefresh()
        val token = ++fullInspectionCallbackToken
        pageInspectionTask?.cancel()
        pageInspectionTask = null
        pendingPageReadPurpose = purpose
        pageInspectionTask = observationCoordinator.scheduleReadOnlyObservation(delayMs) observation@{
            // A cancelled callback must not retire a replacement observation or advance a
            // new child. Reset/route recovery can also clear the pending slot without reposting.
            if (token != fullInspectionCallbackToken || pageInspectionTask == null) return@observation
            onObservationRequested()
        }
    }

    /**
     * Keeps the last positively confirmed inbox identity as comparison-only evidence across
     * transient UNKNOWN samples. It never supplies coordinates or node paths to an action.
     */
    fun stabilizeIncomingPageSnapshot(
        incoming: ChatPageSnapshot,
        isSemanticallyUnconfirmed: (ChatPageSnapshot, ChatPageSnapshot?) -> Boolean,
        onLog: (String) -> Unit
    ): ChatPageSnapshot {
        val confirmed = lastConfirmedChatListInspection
            ?: lastPageInspection?.takeIf { it.pageKind == ChatPageKind.CHAT_LIST }
        val stable = stabilizeConfirmedChatList(confirmed, incoming)
        when {
            stable.pageKind == ChatPageKind.CHAT_LIST -> lastConfirmedChatListInspection = stable
            isSemanticallyUnconfirmed(stable, confirmed) && confirmed != null &&
                confirmed.packageName == stable.packageName &&
                confirmed.hasSameWindowIdentity(stable) ->
                lastConfirmedChatListInspection = confirmed
            else -> lastConfirmedChatListInspection = null
        }
        lastPageInspection = stable
        if (stable !== incoming) {
            onLog("AI 页面稳定性[TRANSIENT_UNKNOWN_NORMALIZED]：新快照的窗口代次、消息Tab锚点和可见主列表视口一致；使用新快照数据，不复用旧节点路径或坐标")
        }
        return stable
    }

    fun signalFullInspection() = fullPageChannel.requestSnapshot()

    fun signalRegionRefresh() = regionRefreshChannel.requestSnapshot()

    fun cancelFullInspection() = fullPageChannel.cancel()

    fun cancelRegionRefresh() = regionRefreshChannel.cancel()

    fun isFullInspectionPending(): Boolean = fullPageChannel.isPending()

    fun onPageChangeEvent() = receivedGreetingsWatcher.onPageChangeEvent()

    fun onPageSnapshotAvailable(status: ReceivedGreetingsPageArrivalStatus): ReceivedGreetingsPageArrivalStatus =
        receivedGreetingsWatcher.onPageSnapshotAvailable(status)

    fun resetReceivedGreetingsWatch() = receivedGreetingsWatcher.reset()

    fun reset() {
        fullPageChannel.cancel()
        regionRefreshChannel.cancel()
        receivedGreetingsWatcher.reset()
    }

    private companion object {
        const val FULL_INSPECTION_CHANNEL = "AI_REPLY_FULL_INSPECTION"
        const val REGION_REFRESH_CHANNEL = "AI_REPLY_KNOWN_REGION_REFRESH"
    }
}
