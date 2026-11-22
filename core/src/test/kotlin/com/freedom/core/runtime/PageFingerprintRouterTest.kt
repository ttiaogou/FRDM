package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0 regression coverage for the read-only page-routing boundary. */
class PageFingerprintRouterTest {
    private fun page(
        semanticPage: String,
        packageName: String = "com.immomo.momo",
        adapterFingerprint: String? = null
    ) = PageFingerprint(
        packageName = packageName,
        activityClass = "com.immomo.momo.ui.ChatActivity",
        semanticPage = semanticPage,
        adapterFingerprint = adapterFingerprint
    )

    @Test
    fun unknownAndForeignPagesDoNotMatchARegisteredBusinessRoute() {
        val router = PageFingerprintRouter()
        router.register(PageFingerprintRoute("MESSAGE_LIST") {
            it.packageName == "com.immomo.momo" &&
                it.semanticPage == "CHAT_LIST" &&
                it.adapterFingerprint?.startsWith("list|") == true
        })

        assertNull(router.match(page("UNKNOWN", adapterFingerprint = "list|rows=3")))
        assertNull(router.match(page("CHAT_LIST", "another.application", "list|rows=3")))
        assertNull(router.match(page("CHAT_LIST")))
        assertEquals("MESSAGE_LIST", router.match(page("CHAT_LIST", adapterFingerprint = "list|rows=3")))
        assertEquals(setOf("MESSAGE_LIST"), router.registeredRouteIds())
    }

    @Test
    fun duplicateRegistrationReplacesMatcherWithoutDuplicatingOrReorderingRoute() {
        val router = PageFingerprintRouter()
        val candidate = page("CHAT_LIST")
        router.register(PageFingerprintRoute("FIRST") { true })
        router.register(PageFingerprintRoute("SECOND") { true })
        assertEquals("FIRST", router.match(candidate))

        router.register(PageFingerprintRoute("FIRST") { false })
        assertEquals(listOf("FIRST", "SECOND"), router.registeredRouteIds().toList())
        assertEquals("SECOND", router.match(candidate))

        router.register(PageFingerprintRoute("FIRST") { true })
        assertEquals(listOf("FIRST", "SECOND"), router.registeredRouteIds().toList())
        assertEquals("FIRST", router.match(candidate))
    }

    @Test
    fun unregisterAndLaterReregisterRestoreCorrectRoutePriority() {
        val router = PageFingerprintRouter()
        val candidate = page("CHAT_LIST")
        router.register(PageFingerprintRoute("FIRST") { true })
        router.register(PageFingerprintRoute("SECOND") { true })

        router.unregister("FIRST")
        assertEquals("SECOND", router.match(candidate))
        router.unregister("SECOND")
        assertNull(router.match(candidate))
        assertTrue(router.registeredRouteIds().isEmpty())

        router.register(PageFingerprintRoute("SECOND") { true })
        router.register(PageFingerprintRoute("FIRST") { true })
        assertEquals(listOf("SECOND", "FIRST"), router.registeredRouteIds().toList())
        assertEquals("SECOND", router.match(candidate))
    }

    @Test
    fun invalidRouteRegistrationDoesNotDisturbExistingRoutes() {
        val router = PageFingerprintRouter()
        router.register(PageFingerprintRoute("VALID") { true })
        val before = router.registeredRouteIds()

        assertThrows(IllegalArgumentException::class.java) {
            router.register(PageFingerprintRoute("   ") { false })
        }
        assertEquals(before, router.registeredRouteIds())
        assertEquals("VALID", router.match(page("CHAT_LIST")))
    }

    @Test
    fun matchingRouteAdapterDoesNotInvokeCheckpointSideEffects() {
        var checkpointWrites = 0
        var comparisons = 0
        val adapter = object : PageRecoveryRouteAdapter {
            override val routeId = "RECEIVED_GREETINGS"
            override fun matches(fingerprint: PageFingerprint): Boolean {
                comparisons++
                return fingerprint.semanticPage == "GREETINGS_LIST"
            }
            override fun saveCheckpoint() {
                checkpointWrites++
            }
        }
        val router = PageFingerprintRouter()
        router.register(adapter.asRoute())

        assertNull(router.match(page("UNKNOWN")))
        assertEquals("RECEIVED_GREETINGS", router.match(page("GREETINGS_LIST")))
        assertEquals(2, comparisons)
        assertEquals(0, checkpointWrites)
    }

    @Test
    fun unifiedRecoveryRegistryScopesOverlappingLegacyMatchersByTask() {
        val scheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask =
                object : ScheduledTask { override fun cancel() = Unit }
        }
        val coordinator = PageObservationCoordinator(scheduler)
        var checkpointWrites = 0
        val greetings = object : PageRecoveryRouteAdapter {
            override val routeId = "AI_REPLY_RECEIVED_GREETINGS"
            override val taskId = "AI_REPLY_RECEIVED_GREETINGS"
            override fun matches(fingerprint: PageFingerprint) =
                !fingerprint.adapterFingerprint.isNullOrBlank()
            override fun saveCheckpoint() { checkpointWrites++ }
        }
        val browse = object : PageRecoveryRouteAdapter {
            override val routeId = "account-browse.dynamic-feed"
            override val taskId = "ACCOUNT_BROWSE"
            override fun matches(fingerprint: PageFingerprint) =
                fingerprint.semanticPage == "DYNAMIC_FEED" &&
                    !fingerprint.adapterFingerprint.isNullOrBlank()
            override fun saveCheckpoint() { checkpointWrites++ }
        }
        coordinator.registerRecoveryAdapter(greetings)
        coordinator.registerRecoveryAdapter(browse)
        val fingerprint = page("DYNAMIC_FEED", adapterFingerprint = "fresh-feed")

        assertEquals("account-browse.dynamic-feed",
            coordinator.matchRecoveryAdapter(fingerprint, setOf("ACCOUNT_BROWSE"))?.routeId)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS",
            coordinator.matchRecoveryAdapter(fingerprint, setOf("AI_REPLY_RECEIVED_GREETINGS"))?.routeId)
        assertNull(coordinator.matchRecoveryAdapter(fingerprint, emptySet()))
        assertNull(coordinator.matchRecoveryAdapter(fingerprint, setOf("UNKNOWN_TASK")))
        assertEquals("ACCOUNT_BROWSE",
            coordinator.recoveryAdapterForRoute("account-browse.dynamic-feed")?.taskId)
        assertEquals("account-browse.dynamic-feed",
            coordinator.primaryRecoveryAdapterForTask("ACCOUNT_BROWSE")?.routeId)
        assertEquals(0, checkpointWrites)
    }

    @Test
    fun registeredRecoveryAdapterReplacementAndUnregisterHaveNoStaleCallback() {
        val router = PageRecoveryAdapterRegistry()
        var oldWrites = 0
        var newWrites = 0
        fun adapter(onWrite: () -> Unit) = object : PageRecoveryRouteAdapter {
            override val routeId = "AI_REPLY_PRIVATE_MESSAGES"
            override val taskId = "AI_REPLY_PRIVATE_MESSAGES"
            override fun matches(fingerprint: PageFingerprint) = fingerprint.semanticPage == "CHAT_LIST"
            override fun saveCheckpoint() = onWrite()
        }
        val old = adapter { oldWrites++ }
        val fresh = adapter { newWrites++ }
        router.register(old)
        router.register(fresh)
        assertEquals(setOf("AI_REPLY_PRIVATE_MESSAGES"), router.registeredRouteIds())
        assertEquals(fresh, router.primaryForTask("AI_REPLY_PRIVATE_MESSAGES"))
        assertEquals(fresh, router.adapterForRoute("AI_REPLY_PRIVATE_MESSAGES"))
        router.primaryForTask("AI_REPLY_PRIVATE_MESSAGES")?.saveCheckpoint()
        assertEquals(0, oldWrites)
        assertEquals(1, newWrites)
        router.unregister("AI_REPLY_PRIVATE_MESSAGES")
        assertNull(router.primaryForTask("AI_REPLY_PRIVATE_MESSAGES"))
        assertNull(router.adapterForRoute("AI_REPLY_PRIVATE_MESSAGES"))
    }

    @Test
    fun taskScopedRecoveryRoutesNeverLeakIntoLegacyUnscopedFingerprintRouter() {
        val scheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask =
                ScheduledTask { }
        }
        val observation = PageObservationCoordinator(scheduler)
        var checkpointWrites = 0
        val route = object : PageRecoveryRouteAdapter {
            override val routeId = "ACCOUNT_BROWSE"
            override val taskId = "ACCOUNT_BROWSE"
            override fun matches(fingerprint: PageFingerprint): Boolean = true
            override fun saveCheckpoint() { checkpointWrites++ }
        }
        observation.registerRecoveryAdapter(route)
        val candidate = page("DYNAMIC_FEED", adapterFingerprint = "fresh-feed")
        // The old unrestricted route table may no longer match a business recovery route.
        assertNull(observation.matchFingerprint(candidate))
        assertTrue(observation.registeredFingerprintRouteIds().isEmpty())
        assertEquals(route,
            observation.matchRecoveryAdapter(candidate, setOf("ACCOUNT_BROWSE")))
        assertNull(observation.matchRecoveryAdapter(candidate, setOf("AI_REPLY_PRIVATE_MESSAGES")))
        assertThrows(IllegalStateException::class.java) {
            observation.registerFingerprintRoute(PageFingerprintRoute("ACCOUNT_BROWSE") { true })
        }
        observation.unregisterFingerprintRoute("ACCOUNT_BROWSE")
        assertEquals(route, observation.recoveryAdapterForRoute("ACCOUNT_BROWSE"))
        assertEquals(0, checkpointWrites)
        observation.unregisterRecoveryAdapter("ACCOUNT_BROWSE")
        assertNull(observation.recoveryAdapterForRoute("ACCOUNT_BROWSE"))
    }

    @Test
    fun observationOnlyAndRecoveryRoutesCannotReuseTheSameRouteId() {
        val coordinator = PageObservationCoordinator(object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask =
                ScheduledTask { }
        })
        coordinator.registerFingerprintRoute(PageFingerprintRoute("SHARED") { true })
        val adapter = object : PageRecoveryRouteAdapter {
            override val routeId = "SHARED"
            override fun matches(fingerprint: PageFingerprint): Boolean = true
            override fun saveCheckpoint() = Unit
        }
        assertThrows(IllegalStateException::class.java) {
            coordinator.registerRecoveryAdapter(adapter)
        }
        assertNull(coordinator.recoveryAdapterForRoute("SHARED"))
    }

    @Test
    fun secondaryChatDetailRouteCannotReplaceMessageListCheckpointOwner() {
        val registry = PageRecoveryAdapterRegistry()
        fun route(id: String, primary: Boolean) = object : PageRecoveryRouteAdapter {
            override val routeId = id
            override val taskId = "AI_REPLY_PRIVATE_MESSAGES"
            override val primaryForTask = primary
            override fun matches(fingerprint: PageFingerprint) = true
            override fun saveCheckpoint() = Unit
        }
        val message = route("AI_REPLY_PRIVATE_MESSAGES", true)
        val chatDetail = route("AI_REPLY_CHAT_DETAIL", false)
        registry.register(message)
        registry.register(chatDetail)
        assertEquals(message, registry.primaryForTask("AI_REPLY_PRIVATE_MESSAGES"))
        assertEquals(chatDetail, registry.adapterForRoute("AI_REPLY_CHAT_DETAIL"))
        assertThrows(IllegalStateException::class.java) {
            registry.register(route("INVALID_SECOND_PRIMARY", true))
        }
        assertEquals(setOf("AI_REPLY_PRIVATE_MESSAGES", "AI_REPLY_CHAT_DETAIL"),
            registry.registeredRouteIds())
    }

    @Test
    fun sharedObservationCoordinatorRoutesWithoutStartingSnapshotWork() {
        var scheduledTasks = 0
        val scheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
                scheduledTasks++
                return object : ScheduledTask {
                    override fun cancel() = Unit
                }
            }
        }
        val observation = PageObservationCoordinator(scheduler)
        observation.registerFingerprintRoute(PageFingerprintRoute("MESSAGE_LIST") {
            it.semanticPage == "CHAT_LIST"
        })

        assertNull(observation.matchFingerprint(page("UNKNOWN")))
        assertEquals("MESSAGE_LIST", observation.matchFingerprint(page("CHAT_LIST")))
        assertEquals(setOf("MESSAGE_LIST"), observation.registeredFingerprintRouteIds())
        observation.unregisterFingerprintRoute("MESSAGE_LIST")
        assertNull(observation.matchFingerprint(page("CHAT_LIST")))
        assertTrue(observation.registeredFingerprintRouteIds().isEmpty())
        assertEquals(0, scheduledTasks)
    }
}
