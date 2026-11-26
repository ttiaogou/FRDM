package com.freedom.adapters.social.momo

/** Read-only snapshot for diagnosing why the existing received-greetings admission guard rejected. */
internal data class MomoReceivedGreetingsAdmissionDiagnostic(
    val listConfirmed: Boolean,
    val listStructureVisible: Boolean,
    val windowTargetPresent: Boolean,
    val windowCurrent: Boolean,
    val sayHiGestureInFlight: Boolean,
    val readOnlyStage: String,
    val entrySearchGestureInFlight: Boolean
) {
    /** One read-only gate shared by recovery preflight and the final start admission. */
    fun isReadyForCurrentList(): Boolean = rejectedGates().isEmpty()

    fun rejectedGates(): List<String> = buildList {
        if (!listConfirmed) add("LIST_NOT_CONFIRMED")
        if (!listStructureVisible) add("LIST_STRUCTURE_NOT_VISIBLE")
        if (!windowTargetPresent) add("WINDOW_TARGET_MISSING")
        if (!windowCurrent) add("WINDOW_NOT_CURRENT")
        if (sayHiGestureInFlight) add("SAYHI_GESTURE_IN_FLIGHT")
        if (readOnlyStage != "IDLE") add("READ_ONLY_STAGE_NOT_IDLE")
        if (entrySearchGestureInFlight) add("ENTRY_SEARCH_GESTURE_IN_FLIGHT")
    }

    fun toLogFields(): String =
        "sample=POST_REJECTION;" +
            "rejectReason=${rejectedGates().firstOrNull() ?: "STATE_CHANGED_AFTER_REJECTION"};" +
            "failedGates=${rejectedGates().joinToString(",").ifEmpty { "NONE" }};" +
            "listConfirmed=$listConfirmed;" +
            "listStructureVisible=$listStructureVisible;" +
            "windowTargetPresent=$windowTargetPresent;" +
            "windowCurrent=$windowCurrent;" +
            "sayHiGestureInFlight=$sayHiGestureInFlight;" +
            "readOnlyStage=$readOnlyStage;" +
            "entrySearchGestureInFlight=$entrySearchGestureInFlight"
}
