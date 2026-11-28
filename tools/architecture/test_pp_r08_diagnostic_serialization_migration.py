"""R08-E: exact diagnostic serialization relocation; live state and file authority remain."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
MANAGER = "app/src/main/java/com/freedom/data/RunDiagnosticsManager.kt"
CODEC = "app/src/main/java/com/freedom/data/RunDiagnosticEventJsonCodec.kt"
FORMATTER = "app/src/main/java/com/freedom/data/RunDiagnosticReportFormatter.kt"
PREVIOUS = {
  "app/src/main/java/com/freedom/data/RunDiagnosticsManager.kt": "d44a787d7f8b9195a8e226086167573b31129b3b",
  "app/src/main/java/com/freedom/data/RunDiagnosticEventJsonCodec.kt": "4e6a6e034886e073c33e9ade293138dc5c7d52ff",
  "app/src/main/java/com/freedom/data/RunDiagnosticReportFormatter.kt": "1cc1720451ebb4523a8d6d3eae1cf76e8e4b5733"
}
MANAGER_INVERSE = [
  [
    "        val summary = RunDiagnosticReportFormatter.toSummaryText(report)\n",
    "        val summary = buildString {\n            append(\"Freedom 任务诊断摘要\\n\")\n            append(\"Run ID：\").append(report.runId).append('\\n')\n            append(\"任务结果：\").append(report.result).append('\\n')\n            append(\"问题数量：\").append(report.issues.size).append('\\n')\n            append(\"结论：\").append(report.conclusion)\n        }\n"
  ],
  [
    "        val boundedJson = RunDiagnosticReportFormatter.toBoundedJson(report)\n",
    "        val json = RunDiagnosticReportFormatter.toJson(report).toString(2)\n        val boundedJson = if (RunLogBudgetPolicy.utf8Size(json) <= RunLogBudgetPolicy.MAX_DIAGNOSTIC_REPORT_BYTES) {\n            json\n        } else {\n            JSONObject()\n                .put(\"schemaVersion\", 1)\n                .put(\"runId\", report.runId)\n                .put(\"result\", report.result)\n                .put(\"conclusion\", \"诊断 JSON 达到容量上限；请查看同目录的精简 TXT 报告。\")\n                .put(\"issuesOmitted\", report.issues.size)\n                .toString(2)\n        }\n"
  ],
  [
    "    private fun errorDetailLine(event: RunDiagnosticEvent, detail: String): String =\n        RunDiagnosticEventJsonCodec.errorDetailLine(event, detail)\n",
    "    private fun errorDetailLine(event: RunDiagnosticEvent, detail: String): String = JSONObject()\n        .put(\"sequence\", event.sequence)\n        .put(\"eventId\", event.eventId)\n        .put(\"capturedAtEpochMs\", event.timestampEpochMs)\n        .put(\"errorType\", event.errorType ?: \"UNKNOWN\")\n        .put(\"detail\", detail)\n        .toString()\n"
  ],
  [
    "    private fun boundEventFields(event: RunDiagnosticEvent): RunDiagnosticEvent =\n        RunDiagnosticEventJsonCodec.boundEventFields(event)\n",
    "    private fun boundEventFields(event: RunDiagnosticEvent): RunDiagnosticEvent {\n        var bounded = event.copy(\n            runId = event.runId.take(96),\n            taskId = event.taskId.take(96),\n            traceId = event.traceId.take(128),\n            component = event.component.take(80),\n            eventType = event.eventType.take(100),\n            message = RunLogBudgetPolicy.truncateUtf8(\n                event.message,\n                RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES - 5 * 1024\n            ),\n            actionId = event.actionId?.take(128),\n            packageName = event.packageName?.take(160),\n            activityClass = event.activityClass?.take(180),\n            inputOrigin = event.inputOrigin?.take(40),\n            inputOriginEvidence = event.inputOriginEvidence?.take(80),\n            viewId = event.viewId?.take(240),\n            previousState = event.previousState?.take(100),\n            currentState = event.currentState?.take(100),\n            result = event.result?.take(100),\n            reason = event.reason?.let { RunLogBudgetPolicy.truncateUtf8(it, 512) },\n            errorType = event.errorType?.take(160)\n        )\n        var messageBudget = RunLogBudgetPolicy.utf8Size(bounded.message)\n        while (RunLogBudgetPolicy.utf8Size(RunDiagnosticEventJsonCodec.encode(bounded)) > RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES &&\n            messageBudget > 0\n        ) {\n            messageBudget = (messageBudget * 3 / 4).coerceAtLeast(0)\n            bounded = bounded.copy(message = RunLogBudgetPolicy.truncateUtf8(bounded.message, messageBudget))\n        }\n        if (RunLogBudgetPolicy.utf8Size(RunDiagnosticEventJsonCodec.encode(bounded)) > RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES) {\n            bounded = bounded.copy(\n                component = bounded.component.take(32),\n                eventType = bounded.eventType.take(40),\n                actionId = bounded.actionId?.take(48),\n                packageName = bounded.packageName?.take(64),\n                activityClass = bounded.activityClass?.take(64),\n                inputOriginEvidence = bounded.inputOriginEvidence?.take(32),\n                viewId = bounded.viewId?.take(64),\n                reason = bounded.reason?.let { RunLogBudgetPolicy.truncateUtf8(it, 96) },\n                errorType = bounded.errorType?.take(64)\n            )\n        }\n        return bounded\n    }\n"
  ]
]
CODEC_BLOCK = "    fun errorDetailLine(event: RunDiagnosticEvent, detail: String): String = JSONObject()\n        .put(\"sequence\", event.sequence)\n        .put(\"eventId\", event.eventId)\n        .put(\"capturedAtEpochMs\", event.timestampEpochMs)\n        .put(\"errorType\", event.errorType ?: \"UNKNOWN\")\n        .put(\"detail\", detail)\n        .toString()\n\n    fun boundEventFields(event: RunDiagnosticEvent): RunDiagnosticEvent {\n        var bounded = event.copy(\n            runId = event.runId.take(96),\n            taskId = event.taskId.take(96),\n            traceId = event.traceId.take(128),\n            component = event.component.take(80),\n            eventType = event.eventType.take(100),\n            message = RunLogBudgetPolicy.truncateUtf8(\n                event.message,\n                RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES - 5 * 1024\n            ),\n            actionId = event.actionId?.take(128),\n            packageName = event.packageName?.take(160),\n            activityClass = event.activityClass?.take(180),\n            inputOrigin = event.inputOrigin?.take(40),\n            inputOriginEvidence = event.inputOriginEvidence?.take(80),\n            viewId = event.viewId?.take(240),\n            previousState = event.previousState?.take(100),\n            currentState = event.currentState?.take(100),\n            result = event.result?.take(100),\n            reason = event.reason?.let { RunLogBudgetPolicy.truncateUtf8(it, 512) },\n            errorType = event.errorType?.take(160)\n        )\n        var messageBudget = RunLogBudgetPolicy.utf8Size(bounded.message)\n        while (RunLogBudgetPolicy.utf8Size(RunDiagnosticEventJsonCodec.encode(bounded)) > RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES &&\n            messageBudget > 0\n        ) {\n            messageBudget = (messageBudget * 3 / 4).coerceAtLeast(0)\n            bounded = bounded.copy(message = RunLogBudgetPolicy.truncateUtf8(bounded.message, messageBudget))\n        }\n        if (RunLogBudgetPolicy.utf8Size(RunDiagnosticEventJsonCodec.encode(bounded)) > RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES) {\n            bounded = bounded.copy(\n                component = bounded.component.take(32),\n                eventType = bounded.eventType.take(40),\n                actionId = bounded.actionId?.take(48),\n                packageName = bounded.packageName?.take(64),\n                activityClass = bounded.activityClass?.take(64),\n                inputOriginEvidence = bounded.inputOriginEvidence?.take(32),\n                viewId = bounded.viewId?.take(64),\n                reason = bounded.reason?.let { RunLogBudgetPolicy.truncateUtf8(it, 96) },\n                errorType = bounded.errorType?.take(64)\n            )\n        }\n        return bounded\n    }\n\n"
FORMATTER_BLOCK = "    /** The existing JSON size ceiling and compatibility fallback, without file ownership. */\n    fun toBoundedJson(report: RunDiagnosticReport): String {\n        val json = toJson(report).toString(2)\n        return if (RunLogBudgetPolicy.utf8Size(json) <= RunLogBudgetPolicy.MAX_DIAGNOSTIC_REPORT_BYTES) {\n            json\n        } else {\n            JSONObject()\n                .put(\"schemaVersion\", 1)\n                .put(\"runId\", report.runId)\n                .put(\"result\", report.result)\n                .put(\"conclusion\", \"诊断 JSON 达到容量上限；请查看同目录的精简 TXT 报告。\")\n                .put(\"issuesOmitted\", report.issues.size)\n                .toString(2)\n        }\n    }\n\n    fun toSummaryText(report: RunDiagnosticReport): String = buildString {\n        append(\"Freedom 任务诊断摘要\\n\")\n        append(\"Run ID：\").append(report.runId).append('\\n')\n        append(\"任务结果：\").append(report.result).append('\\n')\n        append(\"问题数量：\").append(report.issues.size).append('\\n')\n        append(\"结论：\").append(report.conclusion)\n    }\n\n"
POLICY_IMPORT = "import com.freedom.core.logging.RunLogBudgetPolicy\n"

def restore_sources(manager, codec, formatter):
    def inverse(source, edits):
        for current, previous in edits:
            if source.count(current) != 1:
                raise AssertionError("R08-E inverse anchor changed: " + current[:100])
            source = source.replace(current, previous, 1)
        return source
    restored = {
        MANAGER: inverse(manager, MANAGER_INVERSE),
        CODEC: inverse(codec, [(CODEC_BLOCK, ""), (POLICY_IMPORT, "")]),
        FORMATTER: inverse(formatter, [(FORMATTER_BLOCK, ""), (POLICY_IMPORT, "")]),
    }
    for path, source in restored.items():
        if git_blob(source.encode("utf-8")) != PREVIOUS[path]:
            raise AssertionError("R08-E prior source not reconstructed: " + path)
    return restored

class PpR08DiagnosticSerializationMigrationTest(unittest.TestCase):
    def setUp(self):
        self.manager = (ROOT / MANAGER).read_text(encoding="utf-8")
        self.codec = (ROOT / CODEC).read_text(encoding="utf-8")
        self.formatter = (ROOT / FORMATTER).read_text(encoding="utf-8")

    def test_exact_inverse_and_serialization_without_state_authority(self):
        restore_sources(self.manager, self.codec, self.formatter)
        for token in ("RunDiagnosticEventJsonCodec.boundEventFields(event)",
                      "RunDiagnosticEventJsonCodec.errorDetailLine(event, detail)",
                      "RunDiagnosticReportFormatter.toBoundedJson(report)",
                      "RunDiagnosticReportFormatter.toSummaryText(report)",
                      "RunLogBudgetPolicy.redactSensitivePayload(rawDetail)",
                      "state.reservedErrorDetailBytes += detailBytesToReserve",
                      "state.finishLatch.countDown()", "synchronized(historyLock)",
                      "writeDiagnosticManifest(state.directory, state.runId)"):
            self.assertIn(token, self.manager)
        for token in ("ActiveRun", "synchronized(", "ThreadPoolExecutor", "File(",
                      "finishLatch", "writer.execute"):
            self.assertNotIn(token, CODEC_BLOCK)
            self.assertNotIn(token, FORMATTER_BLOCK)

    def test_mutations_reject_changed_limits_schema_and_sealing_order(self):
        for old, new in (
            ("RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES - 5 * 1024",
             "RunLogBudgetPolicy.MAX_RUN_EVENT_RECORD_BYTES"),
            ("messageBudget = (messageBudget * 3 / 4).coerceAtLeast(0)",
             "messageBudget = 0"),
            ('.put("errorType", event.errorType ?: "UNKNOWN")', '.put("errorType", "")'),
        ):
            self.assertIn(old, self.codec)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.manager, self.codec.replace(old, new, 1), self.formatter)
        for old, new in (
            ("RunLogBudgetPolicy.MAX_DIAGNOSTIC_REPORT_BYTES", "Int.MAX_VALUE"),
            ('.put("issuesOmitted", report.issues.size)', '.put("issuesOmitted", 0)'),
            ('append("问题数量：").append(report.issues.size)', 'append("问题数量：0")'),
        ):
            self.assertIn(old, self.formatter)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.manager, self.codec, self.formatter.replace(old, new, 1))
        for old, new in (
            ("RunLogBudgetPolicy.redactSensitivePayload(rawDetail)", "rawDetail"),
            ("state.reservedErrorDetailBytes += detailBytesToReserve", "Unit"),
            ("sealEvents(state.directory)", "Unit"),
            ("state.finishLatch.countDown()", "Unit"),
        ):
            self.assertIn(old, self.manager)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.manager.replace(old, new, 1), self.codec, self.formatter)
