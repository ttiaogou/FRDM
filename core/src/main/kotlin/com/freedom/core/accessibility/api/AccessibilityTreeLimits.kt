package com.freedom.core.accessibility.api

/** Shared bounded-inspection defaults; platform adapters may apply a stricter request limit. */
object AccessibilityTreeLimits {
    const val MAX_NODES_PER_SCAN = 3000
    const val MAX_DISPLAY_NODES = 140
}
