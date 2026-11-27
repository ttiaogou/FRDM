package com.freedom.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunIncidentHistoryStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun run(root: File, id: String, state: String): File {
        val directory = File(root, id)
        val incident = File(directory, "incidents/incident-1")
        check(incident.mkdirs())
        File(incident, "transaction.json").writeText("""{"state":"$state","occurrences":2}""")
        File(incident, "timeline.jsonl").writeText("frozen")
        File(directory, "events.jsonl").writeText("ordinary")
        File(directory, "diagnostic_report.json").writeText("report")
        return directory
    }

    private fun manifest(directory: File) {
        File(directory, "diagnostic_manifest.json").writeText("test-manifest")
    }

    @Test fun normalPruneCanPreserveIncidentCopyButNotOrdinaryRunFiles() {
        val runs = temp.newFolder("runs")
        val history = temp.newFolder("history")
        val source = run(runs, "run-1", "UNRESOLVED")
        val store = RunIncidentHistoryStore(history)
        assertTrue(store.preserve(source, ::manifest))
        assertTrue(store.preserve(source, ::manifest))
        assertTrue(source.deleteRecursively())
        assertEquals("frozen", File(history, "run-1/incidents/incident-1/timeline.jsonl").readText())
        assertFalse(File(history, "run-1/events.jsonl").exists())
        assertTrue(File(history, "run-1/incident_retention.json").isFile)
    }

    @Test fun failedManifestDoesNotPublishOrDeleteSourceAndMismatchRefusesReuse() {
        val source = run(temp.newFolder("runs"), "run-1", "UNRESOLVED")
        val history = temp.newFolder("history")
        val store = RunIncidentHistoryStore(history)
        assertFalse(store.preserve(source) { error("simulated disk error") })
        assertTrue(File(source, "incidents/incident-1/timeline.jsonl").isFile)
        assertFalse(File(history, "run-1").exists())
        assertTrue(store.preserve(source, ::manifest))
        File(source, "incidents/incident-1/timeline.jsonl").writeText("changed")
        assertFalse(store.preserve(source, ::manifest))
    }

    @Test fun independentLimitEvictsResolvedBeforeUnresolvedEvenWhenResolvedIsNewer() {
        val runs = temp.newFolder("runs")
        val history = temp.newFolder("history")
        val store = RunIncidentHistoryStore(history, maxRuns = 1, maxBytes = 1024 * 1024L)
        assertTrue(store.preserve(run(runs, "older", "UNRESOLVED"), ::manifest))
        assertTrue(store.preserve(run(runs, "newer", "RESOLVED"), ::manifest))
        assertEquals(1, store.prune())
        assertTrue(File(history, "older").isDirectory)
        assertFalse(File(history, "newer").exists())
    }
}
