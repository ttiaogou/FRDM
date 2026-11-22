package com.freedom.features.accountbrowse

import com.freedom.core.runtime.PageFingerprint
import com.freedom.core.runtime.PageRecoveryRouteAdapter

/**
 * Provider-neutral route for the automatic-browse dynamic feed.
 * It only identifies the destination and delegates checkpoint persistence to the runtime.
 */
class DynamicFeedRecoveryRouteAdapter(
    private val targetPackage: () -> String,
    private val save: () -> Unit
) : PageRecoveryRouteAdapter {
    override val routeId: String = ROUTE_ID
    override val taskId: String = "ACCOUNT_BROWSE"

    override fun matches(fingerprint: PageFingerprint): Boolean =
        fingerprint.packageName == targetPackage() &&
        fingerprint.semanticPage == "DYNAMIC_FEED" &&
            !fingerprint.adapterFingerprint.isNullOrBlank()

    override fun saveCheckpoint() = save()

    companion object {
        const val ROUTE_ID = "account-browse.dynamic-feed"
    }
}
