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
    /** Task that owns this route. Do not infer it from the route ID or page fingerprint. */
    val taskId: String get() = routeId
    /** One ordinary checkpoint route per task; secondary routes (e.g. pending send) opt out. */
    val primaryForTask: Boolean get() = true
    fun matches(fingerprint: PageFingerprint): Boolean
    fun saveCheckpoint()

    fun asRoute(): PageFingerprintRoute = PageFingerprintRoute(routeId, ::matches)
}

/**
 * One page-recovery adapter registration mechanism shared by all business runtimes.
 * Ordered route matching remains in PageFingerprintRouter; this registry only binds
 * route identity, task ownership and checkpoint callback. It does not decide resume,
 * mutate UI, inspect pages or infer a phase from stale business checkpoint data.
 */
class PageRecoveryAdapterRegistry {
    private val adapters = LinkedHashMap<String, PageRecoveryRouteAdapter>()

    @Synchronized
    fun register(adapter: PageRecoveryRouteAdapter) {
        require(adapter.routeId.isNotBlank()) { "Recovery routeId must not be blank" }
        require(adapter.taskId.isNotBlank()) { "Recovery taskId must not be blank" }
        if (adapter.primaryForTask) {
            check(adapters.values.none {
                it.routeId != adapter.routeId && it.taskId == adapter.taskId && it.primaryForTask
            }) { "Primary recovery route already registered for task: ${adapter.taskId}" }
        }
        adapters[adapter.routeId] = adapter
    }

    @Synchronized
    fun unregister(routeId: String) {
        adapters.remove(routeId)
    }

    @Synchronized
    fun adapterForRoute(routeId: String): PageRecoveryRouteAdapter? = adapters[routeId]

    @Synchronized
    fun primaryForTask(taskId: String): PageRecoveryRouteAdapter? =
        adapters.values.firstOrNull { it.taskId == taskId && it.primaryForTask }

    /**
     * Read-only, task-scoped matching. One mode cannot hijack another mode's page route
     * even if its legacy fingerprint matcher is intentionally broader.
     * No checkpoint persistence or UI action is invoked.
     */
    @Synchronized
    fun match(fingerprint: PageFingerprint, allowedTaskIds: Set<String>): PageRecoveryRouteAdapter? {
        if (allowedTaskIds.isEmpty()) return null
        return adapters.values.firstOrNull {
            it.taskId in allowedTaskIds && it.matches(fingerprint)
        }
    }

    @Synchronized
    fun registeredRouteIds(): Set<String> = adapters.keys.toSet()
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
