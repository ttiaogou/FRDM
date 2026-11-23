package com.freedom.core.accessibility.events

/**
 * Observes system-delivered touch-interaction boundaries without claiming who originated them.
 * Accessibility's global touch boundary events have no trustworthy actor/injection provenance.
 * The monitor therefore reports observability and pairing only; it never emits PHYSICAL_USER.
 */
class TouchInteractionMonitor(
    private val onDiagnostic: (String) -> Unit = {}
) {
    data class Snapshot(
        val startCount: Long,
        val endCount: Long,
        val orphanEndCount: Long,
        val duplicateStartCount: Long,
        val interactionActive: Boolean,
        val lastDurationMs: Long?
    )

    private val lock = Any()
    private var startCount = 0L
    private var endCount = 0L
    private var orphanEndCount = 0L
    private var duplicateStartCount = 0L
    private var activeStartEventTimeMs: Long? = null
    private var lastDurationMs: Long? = null
    private var sequence = 0L

    fun record(
        type: AccessibilityEventType,
        eventTimeMs: Long,
        callbackUptimeMs: Long,
        packageName: String?,
        windowId: Int
    ): Snapshot? {
        val line: String
        val result: Snapshot
        synchronized(lock) {
            sequence += 1L
            when (type) {
                AccessibilityEventType.TOUCH_INTERACTION_START -> {
                    startCount += 1L
                    val duplicateStart = activeStartEventTimeMs != null
                    if (duplicateStart) duplicateStartCount += 1L
                    activeStartEventTimeMs = eventTimeMs
                    lastDurationMs = null
                    line = "全局触摸监控[START]：seq=$sequence；eventTimeUptimeMs=$eventTimeMs；" +
                        "callbackUptimeMs=$callbackUptimeMs；package=${packageName.orEmpty().ifBlank { "SYSTEM/NULL" }}；" +
                        "windowId=$windowId；origin=UNKNOWN；actorProvenance=UNAVAILABLE；" +
                        "duplicateStart=$duplicateStart"
                }
                AccessibilityEventType.TOUCH_INTERACTION_END -> {
                    endCount += 1L
                    val startedAt = activeStartEventTimeMs
                    if (startedAt == null) {
                        orphanEndCount += 1L
                        lastDurationMs = null
                        line = "全局触摸监控[END_UNPAIRED]：seq=$sequence；eventTimeUptimeMs=$eventTimeMs；" +
                            "callbackUptimeMs=$callbackUptimeMs；package=${packageName.orEmpty().ifBlank { "SYSTEM/NULL" }}；" +
                            "windowId=$windowId；origin=UNKNOWN；actorProvenance=UNAVAILABLE"
                    } else {
                        lastDurationMs = (eventTimeMs - startedAt).coerceAtLeast(0L)
                        activeStartEventTimeMs = null
                        line = "全局触摸监控[END]：seq=$sequence；eventTimeUptimeMs=$eventTimeMs；" +
                            "callbackUptimeMs=$callbackUptimeMs；durationMs=$lastDurationMs；" +
                            "package=${packageName.orEmpty().ifBlank { "SYSTEM/NULL" }}；windowId=$windowId；" +
                            "origin=UNKNOWN；actorProvenance=UNAVAILABLE"
                    }
                }
                else -> return null
            }
            result = snapshotLocked()
        }
        runCatching { onDiagnostic(line) }
        return result
    }

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    fun appendSummary(reason: String) {
        val state = snapshot()
        runCatching {
            onDiagnostic(
                "全局触摸监控[SUMMARY]：reason=$reason；start=${state.startCount}；end=${state.endCount}；" +
                    "unpairedEnd=${state.orphanEndCount}；duplicateStart=${state.duplicateStartCount}；" +
                    "interactionActive=${state.interactionActive}；lastDurationMs=${state.lastDurationMs ?: "NONE"}；" +
                    "physicalUserConfirmed=0；externalInjectedConfirmed=0"
            )
        }
    }

    fun reset(reason: String) {
        val previous = snapshot()
        synchronized(lock) {
            startCount = 0L
            endCount = 0L
            orphanEndCount = 0L
            duplicateStartCount = 0L
            activeStartEventTimeMs = null
            lastDurationMs = null
            sequence = 0L
        }
        runCatching {
            onDiagnostic(
                "全局触摸监控[RESET]：reason=$reason；previousStart=${previous.startCount}；" +
                    "previousEnd=${previous.endCount}；previousActive=${previous.interactionActive}"
            )
        }
    }

    private fun snapshotLocked() = Snapshot(
        startCount = startCount,
        endCount = endCount,
        orphanEndCount = orphanEndCount,
        duplicateStartCount = duplicateStartCount,
        interactionActive = activeStartEventTimeMs != null,
        lastDurationMs = lastDurationMs
    )

}
