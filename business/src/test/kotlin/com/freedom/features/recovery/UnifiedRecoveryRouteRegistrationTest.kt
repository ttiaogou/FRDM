package com.freedom.features.recovery

import com.freedom.core.runtime.PageFingerprint
import com.freedom.core.runtime.PageObservationCoordinator
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.accountbrowse.DynamicFeedRecoveryRouteAdapter
import com.freedom.features.aireply.ChatDetailRecoveryRouteAdapter
import com.freedom.features.aireply.MessageListRecoveryRouteAdapter
import com.freedom.features.aireply.ReceivedGreetingsRecoveryRouteAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P5: all existing business recovery routes use the same typed registration mechanism. */
class UnifiedRecoveryRouteRegistrationTest {
    @Test
    fun messagesDynamicFeedAndGreetingsRoutesStayModeScopedOnSameMainActivity() {
        val scheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask =
                ScheduledTask { }
        }
        val coordinator = PageObservationCoordinator(scheduler)
        val message = MessageListRecoveryRouteAdapter {}
        val greetings = ReceivedGreetingsRecoveryRouteAdapter {}
        val browse = DynamicFeedRecoveryRouteAdapter({ "com.immomo.momo" }) {}
        coordinator.registerRecoveryAdapter(message)
        coordinator.registerRecoveryAdapter(greetings)
        coordinator.registerRecoveryAdapter(browse)

        fun page(kind: String, fingerprint: String? = null, pkg: String = "com.immomo.momo") =
            PageFingerprint(pkg, "com.immomo.momo.maintab.MaintabActivity", kind, fingerprint)
        val aiTasks = setOf("AI_REPLY_PRIVATE_MESSAGES", "AI_REPLY_RECEIVED_GREETINGS")
        val browseTasks = setOf("ACCOUNT_BROWSE")

        assertEquals(message.routeId,
            coordinator.matchRecoveryAdapter(page("CHAT_LIST"), aiTasks)?.routeId)
        assertNull(coordinator.matchRecoveryAdapter(page("CHAT_LIST"), browseTasks))
        assertEquals(browse.routeId,
            coordinator.matchRecoveryAdapter(page("DYNAMIC_FEED", "verified-feed-window"), browseTasks)?.routeId)
        assertNull(coordinator.matchRecoveryAdapter(page("DYNAMIC_FEED", "verified-feed-window"), aiTasks))
        assertNull(coordinator.matchRecoveryAdapter(page("DYNAMIC_FEED"), browseTasks))
        assertNull(coordinator.matchRecoveryAdapter(
            page("DYNAMIC_FEED", "verified-feed-window", pkg = "foreign.app"), browseTasks))
        assertEquals(greetings.routeId,
            coordinator.matchRecoveryAdapter(page("NON_CHAT", "verified-greetings-window"), aiTasks)?.routeId)
        assertNull(coordinator.matchRecoveryAdapter(page("NON_CHAT", "verified-greetings-window"), browseTasks))

        // A visually similar MainActivity is not proof that a route or mode changed.
        assertNull(coordinator.matchRecoveryAdapter(page("NON_CHAT"), aiTasks))
        assertNull(coordinator.matchRecoveryAdapter(page("NON_CHAT"), browseTasks))
    }

    @Test
    fun allFourBusinessAdaptersUseTheSameRegistryAndDoNotSaveOnMatching() {
        val scheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask =
                object : ScheduledTask { override fun cancel() = Unit }
        }
        val coordinator = PageObservationCoordinator(scheduler)
        val writes = mutableListOf<String>()
        val message = MessageListRecoveryRouteAdapter { writes += "message" }
        val greetings = ReceivedGreetingsRecoveryRouteAdapter { writes += "greetings" }
        val detail = ChatDetailRecoveryRouteAdapter { writes += "detail" }
        val browse = DynamicFeedRecoveryRouteAdapter({ "com.immomo.momo" }) { writes += "browse" }
        listOf(message, greetings, detail, browse).forEach(coordinator::registerRecoveryAdapter)

        assertEquals(setOf(
            "AI_REPLY_PRIVATE_MESSAGES",
            "AI_REPLY_RECEIVED_GREETINGS",
            "AI_REPLY_CHAT_DETAIL",
            DynamicFeedRecoveryRouteAdapter.ROUTE_ID
        ), coordinator.registeredRecoveryAdapterIds())
        assertEquals(message.routeId,
            coordinator.primaryRecoveryAdapterForTask("AI_REPLY_PRIVATE_MESSAGES")?.routeId)
        assertEquals(greetings.routeId,
            coordinator.primaryRecoveryAdapterForTask("AI_REPLY_RECEIVED_GREETINGS")?.routeId)
        assertEquals(browse.routeId,
            coordinator.primaryRecoveryAdapterForTask("ACCOUNT_BROWSE")?.routeId)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES",
            coordinator.recoveryAdapterForRoute(detail.routeId)?.taskId)
        assertEquals("ACCOUNT_BROWSE",
            coordinator.recoveryAdapterForRoute(browse.routeId)?.taskId)

        fun page(kind: String, adapter: String? = null, pkg: String = "com.immomo.momo") =
            PageFingerprint(pkg, "com.immomo.momo.ui.MomoActivity", kind, adapter)
        assertEquals(message.routeId, coordinator.matchRecoveryAdapter(
            page("CHAT_LIST"), setOf("AI_REPLY_PRIVATE_MESSAGES", "AI_REPLY_RECEIVED_GREETINGS")
        )?.routeId)
        assertEquals(greetings.routeId, coordinator.matchRecoveryAdapter(
            page("NON_CHAT", "greetings-structural-fingerprint"), setOf("AI_REPLY_RECEIVED_GREETINGS")
        )?.routeId)
        assertEquals(detail.routeId, coordinator.matchRecoveryAdapter(
            page("CHAT_DETAIL_CANDIDATE"), setOf("AI_REPLY_PRIVATE_MESSAGES")
        )?.routeId)

        // Greetings has a historically broad matcher. Registration scopes that observation
        // to the owning business mode rather than rewriting the existing fingerprint rule.
        assertEquals(browse.routeId, coordinator.matchRecoveryAdapter(
            page("DYNAMIC_FEED", "feed-structural-fingerprint"), setOf("ACCOUNT_BROWSE")
        )?.routeId)
        assertNull(coordinator.matchRecoveryAdapter(
            page("DYNAMIC_FEED", "feed-structural-fingerprint"), setOf("AI_REPLY_PRIVATE_MESSAGES")
        ))
        assertNull(coordinator.matchRecoveryAdapter(
            page("DYNAMIC_FEED", "feed-structural-fingerprint", "foreign.app"), setOf("ACCOUNT_BROWSE")
        ))
        assertEquals(emptyList<String>(), writes)

        coordinator.recoveryAdapterForRoute(browse.routeId)?.saveCheckpoint()
        assertEquals(listOf("browse"), writes)
        // P7: only the typed recovery adapter outlet can unregister a business route;
        // raw observation-only route removal must not take ownership of this task.
        coordinator.unregisterRecoveryAdapter(browse.routeId)
        assertNull(coordinator.primaryRecoveryAdapterForTask("ACCOUNT_BROWSE"))
        assertNull(coordinator.matchRecoveryAdapter(
            page("DYNAMIC_FEED", "feed-structural-fingerprint"), setOf("ACCOUNT_BROWSE")
        ))
    }
}
