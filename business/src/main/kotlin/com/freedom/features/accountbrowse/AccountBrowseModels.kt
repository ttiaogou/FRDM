package com.freedom.features.accountbrowse

/** Stable business phases for the existing AccountBrowse state machine. */
enum class AccountBrowsePhase {
    IDLE,
    WAIT_FOR_DYNAMIC_TAB,
    VERIFY_NAVIGATION_ACTION,
    REFRESH_DYNAMIC_FEED,
    VERIFY_DYNAMIC_FEED_REFRESH,
    WAIT_FOR_FEED_STABLE,
    FIRST_SWIPE_COUNTDOWN,
    WAIT_FOR_LIST_SCROLL,
    WAIT_FOR_SCROLL_SETTLE,
    WAIT_FOR_CARD_OPEN,
    WAIT_FOR_SAFE_CARD,
    WAIT_FOR_DETAIL_LOAD,
    DETAIL_DWELL,
    WAIT_FOR_IMAGE_BROWSER_RETURN,
    WAIT_FOR_DETAIL_RESTABILIZE,
    WAIT_FOR_LIST_RETURN,
    PAUSED
}

data class ManualResumeContext(
    val phaseName: String,
    val activityClass: String,
    val sourceFingerprint: String,
    val detailFingerprint: String,
    val imageBrowserActive: Boolean,
    val lastProgrammaticAction: String,
    val capturedAt: Long
)
