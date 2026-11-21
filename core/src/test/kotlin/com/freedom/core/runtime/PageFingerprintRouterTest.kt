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
