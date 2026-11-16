package com.freedom.adapters.accessibility.android

import com.freedom.features.accountbrowse.ports.BrowsePageKind
import com.freedom.features.accountbrowse.ports.BrowsePageEvidence

/** Stateless recovery identity policy. It never owns retry counters or Feature session state. */
internal class AccountBrowseRecoveryExecutor {
    fun isRetainedObstructionEvidence(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("页面遮挡") ||
            normalized.contains("仅保留页面上下文") ||
            normalized.contains("dialogwindowid") ||
            normalized.contains("modal overlay") ||
            normalized.contains("遮挡消失")
    }

    fun isConfirmedDetailPage(page: BrowsePageEvidence): Boolean =
        page.pageKind == BrowsePageKind.DETAIL &&
            page.confidence >= 80 && !isRetainedObstructionEvidence(page.reason)
}
