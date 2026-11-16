package com.freedom.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDiagnosticHistoryAnalyzerTest {
    @Test
    fun identifiesRecurringIssueWithoutClaimingSameRootCause() {
        val current = reportWithIssues("RUN_3", "INPUT_ORIGIN_UNRESOLVED")
        val enriched = RunDiagnosticHistoryAnalyzer.enrich(
            current,
            previousRunIssueCodes = listOf(
                setOf("INPUT_ORIGIN_UNRESOLVED"),
                setOf("INPUT_ORIGIN_UNRESOLVED", "EVENT_BURST_DEDUPLICATED"),
                setOf("RUNTIME_ERRORS_PRESENT")
            )
        )
        val pattern = enriched.historicalPatterns.first { it.code == "INPUT_ORIGIN_UNRESOLVED" }

        assertEquals(3, pattern.occurrenceCount)
        assertEquals(4, pattern.analyzedRunCount)
        assertTrue(pattern.presentInCurrentRun)
    }

    @Test
    fun reportsPastRecurringIssueAsNotPresentInCurrentRun() {
        val current = reportWithIssues("RUN_4", "RUNTIME_ERRORS_PRESENT")
        val enriched = RunDiagnosticHistoryAnalyzer.enrich(
            current,
            previousRunIssueCodes = listOf(
                setOf("EVENT_BURST_DEDUPLICATED"),
                setOf("EVENT_BURST_DEDUPLICATED")
            )
        )
        val pattern = enriched.historicalPatterns.first { it.code == "EVENT_BURST_DEDUPLICATED" }
        assertTrue(!pattern.presentInCurrentRun)
        assertEquals(2, pattern.occurrenceCount)
    }

    private fun reportWithIssues(runId: String, code: String) = RunDiagnosticReport(
        runId = runId,
        taskId = runId,
        result = "STOPPED",
        startedAtEpochMs = 0,
        finishedAtEpochMs = 1,
        durationMs = 1,
        counters = RunDiagnosticCounters(),
        issues = listOf(
            RunDiagnosticIssue(
                code = code,
                level = RunLogLevel.WARN,
                title = code,
                description = "test",
                suggestedAction = "review"
            )
        ),
        conclusion = "test"
    )
}
