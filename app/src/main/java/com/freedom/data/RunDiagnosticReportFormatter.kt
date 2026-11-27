package com.freedom.data

import com.freedom.core.logging.RunDiagnosticReport
import org.json.JSONArray
import org.json.JSONObject

/** Serializes sealed reports without owning files, retention, or active-run lifecycle. */
internal object RunDiagnosticReportFormatter {
    fun toJson(report: RunDiagnosticReport): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("runId", report.runId)
        .put("taskId", report.taskId)
        .put("result", report.result)
        .put("startedAtEpochMs", report.startedAtEpochMs)
        .put("finishedAtEpochMs", report.finishedAtEpochMs)
        .put("durationMs", report.durationMs)
        .put("conclusion", report.conclusion)
        .put("counters", RunDiagnosticEventJsonCodec.counters(report.counters))
        .put("historicalPatterns", JSONArray().apply {
            report.historicalPatterns.forEach { pattern ->
                put(JSONObject()
                    .put("code", pattern.code)
                    .put("occurrenceCount", pattern.occurrenceCount)
                    .put("analyzedRunCount", pattern.analyzedRunCount)
                    .put("presentInCurrentRun", pattern.presentInCurrentRun))
            }
        })
        .put("issues", JSONArray().apply {
            report.issues.forEach { issue ->
                put(JSONObject()
                    .put("code", issue.code)
                    .put("level", issue.level.name)
                    .put("title", issue.title)
                    .put("description", issue.description)
                    .put("evidenceSequences", JSONArray().apply { issue.evidenceSequences.forEach(::put) })
                    .put("suggestedAction", issue.suggestedAction))
            }
        })

    fun toText(report: RunDiagnosticReport): String = buildString {
        append("Freedom 自主运行诊断报告\n")
        append("=".repeat(54)).append('\n')
        append("Run ID：").append(report.runId).append('\n')
        append("Task ID：").append(report.taskId).append('\n')
        append("运行结果：").append(report.result).append('\n')
        append("开始时间：").append(report.startedAtEpochMs).append('\n')
        append("结束时间：").append(report.finishedAtEpochMs).append('\n')
        append("运行时长：").append(report.durationMs).append(" ms\n\n")
        append("容量与完整性统计\n")
        append("已接收事件：").append(report.counters.acceptedEvents).append('\n')
        append("成功写入事件：").append(report.counters.writtenEvents).append('\n')
        append("当前保留事件：").append(report.counters.retainedEvents).append('\n')
        append("正常滚动淘汰：").append(report.counters.normalEvictedEvents).append(" 条 / ")
            .append(report.counters.normalEvictedBytes).append(" 字节（非采集失败）\n")
        append("队列丢弃：").append(report.counters.queueDroppedEvents)
            .append("；预算拒绝：").append(report.counters.budgetDroppedEvents).append('\n')
        append("异常专项取证：").append(report.counters.incidentCaptured)
            .append("；拒绝=").append(report.counters.incidentRejected)
            .append("；写入失败=").append(report.counters.incidentWriteFailures)
            .append("；后置窗口受限=").append(report.counters.incidentPostLimited).append('\n')
        append("异常事务：重复合并=").append(report.counters.incidentMerged)
            .append("；已恢复=").append(report.counters.incidentResolved)
            .append("；未解决=").append(report.counters.incidentUnresolved)
            .append("；中断=").append(report.counters.incidentInterrupted).append('\n')
        append("事件日志字节：").append(report.counters.eventBytes).append('\n')
        append("错误：").append(report.counters.errorEvents).append("；警告：").append(report.counters.warningEvents).append('\n')
        append("去重合并：").append(report.counters.deduplicatedEvents).append('\n')
        append("丢弃：").append(report.counters.droppedEvents).append(" 条 / ").append(report.counters.droppedBytes).append(" 字节\n")
        append("截断：").append(report.counters.truncatedRecords).append(" 条 / ").append(report.counters.truncatedBytes).append(" 字节\n")
        append("写入失败：").append(report.counters.writeFailures).append(" 次\n")
        append("异常详细信息：").append(report.counters.errorDetailBytes).append(" 字节；超出预算丢弃 ")
            .append(report.counters.errorDetailDroppedCount).append(" 条 / ")
            .append(report.counters.errorDetailDroppedBytes).append(" 字节\n\n")
        append("诊断结论\n").append(report.conclusion).append("\n\n")
        append("问题与建议\n")
        report.issues.forEachIndexed { index, issue ->
            append(index + 1).append(". [").append(issue.level).append("] ").append(issue.title).append('\n')
            append("   编码：").append(issue.code).append('\n')
            append("   说明：").append(issue.description).append('\n')
            if (issue.evidenceSequences.isNotEmpty()) {
                append("   证据序号：").append(issue.evidenceSequences.joinToString(", ")).append('\n')
            }
            append("   建议：").append(issue.suggestedAction).append("\n\n")
        }
        append("历史问题趋势（最多比较最近 20 次已封存运行）\n")
        if (report.historicalPatterns.isEmpty()) {
            append("未发现重复出现的诊断编码。\n")
        } else {
            report.historicalPatterns.forEach { pattern ->
                append("- ").append(pattern.code).append("：")
                    .append(pattern.occurrenceCount).append(" / ")
                    .append(pattern.analyzedRunCount).append(" 次运行出现；")
                    .append(if (pattern.presentInCurrentRun) "本次仍出现" else "本次未出现（历史重复项）")
                    .append('\n')
            }
        }
        append("说明：重复诊断编码表示症状重复，不证明根因相同。报告由确定性规则生成；‘疑似’项不代表已证实因果；未触发规则不等于业务结果绝对正确。\n")
    }

}
