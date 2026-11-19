package com.freedom.core.accessibility.api

/** Narrows which asynchronous Accessibility feedback may be causally linked to a dispatch. */
enum class GestureFeedbackIntent {
    /** Default: feedback must match the gesture's own geometry and shape. */
    STANDARD,
    /** A bottom-navigation tap; page changes are verified by snapshots, never scroll ownership. */
    NAVIGATION_TAB,
    /** A tap explicitly intended to open a different Activity/window. */
    NAVIGATION_ENTRY
}
