"""R08-D: exact relocation of export presentation; transaction authority stays in Activity."""
import unittest
from pathlib import Path
from tools.architecture.test_pp_r00_freeze import git_blob

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = "app/src/main/java/com/freedom/MainActivity.kt"
DIALOG = "app/src/main/java/com/freedom/ui/logs/LogRecordsDialog.kt"
FORMATTER = "app/src/main/java/com/freedom/ui/logs/LogTextFormatter.kt"
PREVIOUS = {
  "app/src/main/java/com/freedom/MainActivity.kt": "7afbe0c4eb9f8f67f96f9833665093424948d9c2",
  "app/src/main/java/com/freedom/ui/logs/LogRecordsDialog.kt": "468cbf7f53b5cd88fed715847b09392d25fbabae",
  "app/src/main/java/com/freedom/ui/logs/LogTextFormatter.kt": "0e61d27a625a3b71bae1d29a3f78d5cc4c6660d0"
}
ACTIVITY_INVERSE = [
  [
    "    private fun buildLogExportContent(records: List<String>): String =\n        LogTextFormatter.buildLogExportContent(records)\n",
    "    private fun buildLogExportContent(records: List<String>): String = buildString {\n        append(\"Freedom 运行日志\\n\")\n        append(\"导出时间：\")\n        append(\n            java.text.SimpleDateFormat(\"yyyy-MM-dd HH:mm:ss\", java.util.Locale.getDefault())\n                .format(java.util.Date())\n        )\n        append(\"\\n记录数量：\").append(records.size)\n        append(\"\\n导出内容：包含完整技术详情\\n\")\n        append(\"=\".repeat(72)).append(\"\\n\\n\")\n        append(LogTextFormatter.formatLogLines(\n            records.map(com.freedom.core.logging.RunLogBudgetPolicy::redactSensitivePayload),\n            includeTechnicalDetails = true\n        ))\n        if (records.isNotEmpty()) append('\\n')\n    }\n"
  ],
  [
    "        CollectionExportDialog.confirmSessionSampleExport(this, ::dp, archives) {\n            beginSessionSampleExport(archives)\n        }\n",
    "        val contentLabels = archives.flatMap { it.contentLabels }.distinct()\n        val confirmationContent = LinearLayout(this).apply {\n            orientation = LinearLayout.VERTICAL\n            setPadding(dp(18), dp(8), dp(18), dp(12))\n            addView(TextView(this@MainActivity).apply {\n                text = buildString {\n                    append(\"采集任务数量：\").append(archives.size).append(\"\\n\")\n                    append(\"导出方式：合并为一个 ZIP；采集样本和关联的运行诊断按运行 ID 分目录保存\")\n                        .append(\"\\n\\n\")\n                    archives.take(12).forEachIndexed { index, archive ->\n                        append(index + 1).append(\". \").append(archive.runId).append(\"\\n\")\n                        append(\"   \").append(archive.durationLabel()).append(\"；\")\n                            .append(archive.sampleCountLabel()).append(\"\\n\")\n                    }\n                    if (archives.size > 12) append(\"……另有 \").append(archives.size - 12).append(\" 条采集任务\\n\")\n                }\n                textSize = 13f\n                setTextColor(FreedomUi.SECONDARY)\n                setPadding(0, dp(4), 0, dp(10))\n            })\n            addView(TextView(this@MainActivity).apply {\n                text = \"包含内容（按归档实际文件检测）\"\n                textSize = 14f\n                typeface = Typeface.DEFAULT_BOLD\n                setTextColor(FreedomUi.TEXT)\n                setPadding(0, dp(4), 0, dp(4))\n            })\n            if (contentLabels.isEmpty()) {\n                addView(TextView(this@MainActivity).apply {\n                    text = \"未识别到可展示的数据分类；导出仍会保留归档中的原始文件。\"\n                    textSize = 12f\n                    setTextColor(FreedomUi.MUTED)\n                })\n            } else {\n                contentLabels.forEach { label ->\n                    addView(CheckBox(this@MainActivity).apply {\n                        text = label\n                        isChecked = true\n                        isEnabled = false\n                        textSize = 13f\n                    })\n                }\n            }\n            addView(TextView(this@MainActivity).apply {\n                text = \"本次导出会尝试附带每条运行对应的 diagnostic_report.txt、诊断事件、计数器及清单；若诊断尚未封存，会在导出清单中标明。\"\n                textSize = 12f\n                setTextColor(FreedomUi.SECONDARY)\n                setPadding(0, dp(8), 0, 0)\n            })\n            addView(TextView(this@MainActivity).apply {\n                text = \"导出的内容可能包含聊天文本、截图及其他个人信息，请注意隐私与数据安全。\"\n                textSize = 12f\n                setTextColor(FreedomUi.MUTED)\n                setPadding(0, dp(12), 0, 0)\n            })\n        }\n        val dialog = AlertDialog.Builder(this)\n            .setTitle(if (archives.size == 1) \"导出采集任务\" else \"批量导出采集任务\")\n            .setView(ScrollView(this).apply {\n                isVerticalScrollBarEnabled = false\n                isHorizontalScrollBarEnabled = false\n                addView(confirmationContent)\n                isFillViewport = true\n            })\n            .setNegativeButton(\"取消\", null)\n            .setPositiveButton(\"导出 ZIP\") { _, _ -> beginSessionSampleExport(archives) }\n            .show()\n        FreedomUi.styleAlertDialog(dialog, this, ::dp)\n        CollectionExportDialog.stylePrimaryExportButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), ::dp)\n"
  ],
  [
    "import android.widget.LinearLayout\n",
    "import android.widget.CheckBox\nimport android.widget.LinearLayout\n"
  ]
]
DIALOG_BLOCK = "    /** Confirmation presentation only; the Activity retains the export transaction. */\n    fun confirmSessionSampleExport(\n        activity: Activity,\n        dp: (Int) -> Int,\n        archives: List<SessionSampleArchive>,\n        onConfirm: () -> Unit\n    ) {\n        val contentLabels = archives.flatMap { it.contentLabels }.distinct()\n        val confirmationContent = LinearLayout(activity).apply {\n            orientation = LinearLayout.VERTICAL\n            setPadding(dp(18), dp(8), dp(18), dp(12))\n            addView(TextView(activity).apply {\n                text = buildString {\n                    append(\"采集任务数量：\").append(archives.size).append(\"\\n\")\n                    append(\"导出方式：合并为一个 ZIP；采集样本和关联的运行诊断按运行 ID 分目录保存\")\n                        .append(\"\\n\\n\")\n                    archives.take(12).forEachIndexed { index, archive ->\n                        append(index + 1).append(\". \").append(archive.runId).append(\"\\n\")\n                        append(\"   \").append(archive.durationLabel()).append(\"；\")\n                            .append(archive.sampleCountLabel()).append(\"\\n\")\n                    }\n                    if (archives.size > 12) append(\"……另有 \").append(archives.size - 12).append(\" 条采集任务\\n\")\n                }\n                textSize = 13f\n                setTextColor(FreedomUi.SECONDARY)\n                setPadding(0, dp(4), 0, dp(10))\n            })\n            addView(TextView(activity).apply {\n                text = \"包含内容（按归档实际文件检测）\"\n                textSize = 14f\n                typeface = Typeface.DEFAULT_BOLD\n                setTextColor(FreedomUi.TEXT)\n                setPadding(0, dp(4), 0, dp(4))\n            })\n            if (contentLabels.isEmpty()) {\n                addView(TextView(activity).apply {\n                    text = \"未识别到可展示的数据分类；导出仍会保留归档中的原始文件。\"\n                    textSize = 12f\n                    setTextColor(FreedomUi.MUTED)\n                })\n            } else {\n                contentLabels.forEach { label ->\n                    addView(CheckBox(activity).apply {\n                        text = label\n                        isChecked = true\n                        isEnabled = false\n                        textSize = 13f\n                    })\n                }\n            }\n            addView(TextView(activity).apply {\n                text = \"本次导出会尝试附带每条运行对应的 diagnostic_report.txt、诊断事件、计数器及清单；若诊断尚未封存，会在导出清单中标明。\"\n                textSize = 12f\n                setTextColor(FreedomUi.SECONDARY)\n                setPadding(0, dp(8), 0, 0)\n            })\n            addView(TextView(activity).apply {\n                text = \"导出的内容可能包含聊天文本、截图及其他个人信息，请注意隐私与数据安全。\"\n                textSize = 12f\n                setTextColor(FreedomUi.MUTED)\n                setPadding(0, dp(12), 0, 0)\n            })\n        }\n        val dialog = AlertDialog.Builder(activity)\n            .setTitle(if (archives.size == 1) \"导出采集任务\" else \"批量导出采集任务\")\n            .setView(ScrollView(activity).apply {\n                isVerticalScrollBarEnabled = false\n                isHorizontalScrollBarEnabled = false\n                addView(confirmationContent)\n                isFillViewport = true\n            })\n            .setNegativeButton(\"取消\", null)\n            .setPositiveButton(\"导出 ZIP\") { _, _ -> onConfirm() }\n            .show()\n        FreedomUi.styleAlertDialog(dialog, activity, dp)\n        CollectionExportDialog.stylePrimaryExportButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), dp)\n    }\n\n"
DIALOG_IMPORT = "import android.widget.CheckBox\n"
FORMATTER_BLOCK = "    fun buildLogExportContent(records: List<String>): String = buildString {\n        append(\"Freedom 运行日志\\n\")\n        append(\"导出时间：\")\n        append(\n            java.text.SimpleDateFormat(\"yyyy-MM-dd HH:mm:ss\", java.util.Locale.getDefault())\n                .format(java.util.Date())\n        )\n        append(\"\\n记录数量：\").append(records.size)\n        append(\"\\n导出内容：包含完整技术详情\\n\")\n        append(\"=\".repeat(72)).append(\"\\n\\n\")\n        append(LogTextFormatter.formatLogLines(\n            records.map(com.freedom.core.logging.RunLogBudgetPolicy::redactSensitivePayload),\n            includeTechnicalDetails = true\n        ))\n        if (records.isNotEmpty()) append('\\n')\n    }\n\n"

def restore_sources(activity, dialog, formatter):
    def inverse(source, edits):
        for current, previous in edits:
            if source.count(current) != 1:
                raise AssertionError("R08-D inverse anchor changed: " + current[:100])
            source = source.replace(current, previous, 1)
        return source
    restored = {
        ACTIVITY: inverse(activity, ACTIVITY_INVERSE),
        DIALOG: inverse(dialog, [(DIALOG_BLOCK, ""), (DIALOG_IMPORT, "")]),
        FORMATTER: inverse(formatter, [(FORMATTER_BLOCK, "")]),
    }
    for path, source in restored.items():
        if git_blob(source.encode("utf-8")) != PREVIOUS[path]:
            raise AssertionError("R08-D prior source not reconstructed: " + path)
    return restored

class PpR08ActivityExportPresentationMigrationTest(unittest.TestCase):
    def setUp(self):
        self.activity = (ROOT / ACTIVITY).read_text(encoding="utf-8")
        self.dialog = (ROOT / DIALOG).read_text(encoding="utf-8")
        self.formatter = (ROOT / FORMATTER).read_text(encoding="utf-8")

    def test_exact_inverse_and_single_export_authority(self):
        restore_sources(self.activity, self.dialog, self.formatter)
        self.assertIn("CollectionExportDialog.confirmSessionSampleExport(this, ::dp, archives)", self.activity)
        self.assertIn("LogTextFormatter.buildLogExportContent(records)", self.activity)
        for token in ("pendingSessionSampleExport", "sessionSampleExportRunning",
                      "archiveSealWaitRunnable", "contentResolver", "clearExportedDisplayLogs(",
                      "cleanupAfterVerifiedExport(", "startActivityForResult("):
            self.assertIn(token, self.activity)
            self.assertNotIn(token, self.dialog)
            self.assertNotIn(token, self.formatter)
        self.assertIn("records.map(com.freedom.core.logging.RunLogBudgetPolicy::redactSensitivePayload)", self.formatter)
        self.assertIn("includeTechnicalDetails = true", self.formatter)
        save = self.activity.split("private fun exportSessionSampleArchive(", 1)[1].split("private fun buildLogExportContent(", 1)[0]
        positions = [save.index(token) for token in ("output.use", "contentResolver.openInputStream(destinationUri)",
                     "cleanupAfterVerifiedExport(", "clearExportedDisplayLogs(")]
        self.assertEqual(sorted(positions), positions)

    def test_mutations_reject_lost_redaction_or_confirmation_or_verified_cleanup(self):
        for old, new in (
            ("records.map(com.freedom.core.logging.RunLogBudgetPolicy::redactSensitivePayload)", "records"),
            ("includeTechnicalDetails = true", "includeTechnicalDetails = false"),
        ):
            self.assertIn(old, self.formatter)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.activity, self.dialog, self.formatter.replace(old, new, 1))
        for old, new in (
            ('setPositiveButton("导出 ZIP") { _, _ -> onConfirm() }', 'setPositiveButton("导出 ZIP", null)'),
            ("导出的内容可能包含聊天文本、截图及其他个人信息，请注意隐私与数据安全。", ""),
            ("isEnabled = false", "isEnabled = true"),
        ):
            self.assertIn(old, self.dialog)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.activity, self.dialog.replace(old, new, 1), self.formatter)
        for old, new in (
            ("contentResolver.openInputStream(destinationUri)", "null"),
            ("monitorRepository.clearExportedDisplayLogs(it)", "true"),
        ):
            self.assertIn(old, self.activity)
            with self.subTest(old=old), self.assertRaises(AssertionError):
                restore_sources(self.activity.replace(old, new, 1), self.dialog, self.formatter)
