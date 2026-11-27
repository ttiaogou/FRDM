package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class R02PageRoutingTraceTest {
    @Test
    fun foreignAndUnknownPagesNeverAcquireRegisteredRoute() {
        val router = PageFingerprintRouter()
        router.register(PageFingerprintRoute("LIST") {
            it.packageName == "fixture" && it.semanticPage == "CHAT_LIST" && it.adapterFingerprint == "fresh"
        })
        router.register(PageFingerprintRoute("DETAIL") {
            it.packageName == "fixture" && it.semanticPage == "CHAT_DETAIL" && it.adapterFingerprint == "fresh"
        })
        fun route(pkg: String, page: String, evidence: String?) =
            router.match(PageFingerprint(packageName = pkg, activityClass = "activity",
                semanticPage = page, adapterFingerprint = evidence))
        val trace = listOf(route("fixture", "UNKNOWN", "fresh"), route("other", "CHAT_LIST", "fresh"),
            route("fixture", "CHAT_LIST", null), route("fixture", "CHAT_LIST", "fresh"),
            route("fixture", "CHAT_DETAIL", "fresh"), route("fixture", "UNKNOWN", null))
        assertEquals(listOf(null, null, null, "LIST", "DETAIL", null), trace)
    }
}
