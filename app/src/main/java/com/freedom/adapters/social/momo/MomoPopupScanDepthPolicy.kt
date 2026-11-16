package com.freedom.adapters.social.momo

/** Depth guard shared by the popup tree, text and close-target traversals. */
internal object MomoPopupScanDepthPolicy {
    const val DEFAULT_MAX_SCAN_DEPTH = 48

    fun mustStopAt(depth: Int, childCount: Int, maxScanDepth: Int = DEFAULT_MAX_SCAN_DEPTH): Boolean =
        depth >= maxScanDepth && childCount > 0
}
