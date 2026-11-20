package com.freedom.core.runtime

/** Provider-neutral page identity supplied by an adapter after a fresh read-only observation. */
data class PageFingerprint(
    val packageName: String,
    val activityClass: String,
    val semanticPage: String,
    val adapterFingerprint: String? = null
)

/** A named destination that can be matched without knowing the source business task. */
data class PageFingerprintRoute(
    val routeId: String,
    val matches: (PageFingerprint) -> Boolean
)

/** Feature-owned page route contract; core only invokes its provider-neutral callbacks. */
interface PageRecoveryRouteAdapter {
    val routeId: String
    fun matches(fingerprint: PageFingerprint): Boolean
    fun saveCheckpoint()

    fun asRoute(): PageFingerprintRoute = PageFingerprintRoute(routeId, ::matches)
}

/**
 * Ordered page-fingerprint registry owned by the shared observation coordinator.
 * Registration is replaceable by route ID so service/runtime recreation cannot duplicate routes.
 */
class PageFingerprintRouter {
    private val routes = LinkedHashMap<String, PageFingerprintRoute>()

    @Synchronized
    fun register(route: PageFingerprintRoute) {
        require(route.routeId.isNotBlank()) { "routeId must not be blank" }
        routes[route.routeId] = route
    }

    @Synchronized
    fun unregister(routeId: String) {
        routes.remove(routeId)
    }

    @Synchronized
    fun match(fingerprint: PageFingerprint): String? =
        routes.values.firstOrNull { it.matches(fingerprint) }?.routeId

    @Synchronized
    fun registeredRouteIds(): Set<String> = routes.keys.toSet()
}
