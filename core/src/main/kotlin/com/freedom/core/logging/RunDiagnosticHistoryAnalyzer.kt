package com.freedom.core.logging

/**
 * Deterministic recurrence analysis over completed runs. It reports frequency only; repeated
 * symptoms are not treated as proof that the underlying cause is identical.
 */
object RunDiagnosticHistoryAnalyzer {
    fun enrich(
        current: RunDiagnosticReport,
        previousRunIssueCodes: List<Set<String>>
    ): RunDiagnosticReport {
        val currentCodes = current.issues.map(RunDiagnosticIssue::code)
            .filterNot { it == "NO_RULE_BASED_ISSUE_FOUND" }
            .toSet()
        val allRuns = previousRunIssueCodes.take(19) + listOf(currentCodes)
        val patterns = allRuns.flatMap(Set<String>::toList)
            .groupingBy { it }
            .eachCount()
            .filter { (code, count) -> count >= 2 && code != "NO_RULE_BASED_ISSUE_FOUND" }
            .map { (code, count) ->
                RunDiagnosticHistoryPattern(
                    code = code,
                    occurrenceCount = count,
                    analyzedRunCount = allRuns.size,
                    presentInCurrentRun = code in currentCodes
                )
            }
            .sortedWith(compareByDescending<RunDiagnosticHistoryPattern> { it.presentInCurrentRun }
                .thenByDescending { it.occurrenceCount }
                .thenBy { it.code })
            .take(20)
        return current.copy(historicalPatterns = patterns)
    }
}
