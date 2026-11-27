package com.freedom.data

import com.freedom.core.logging.RunDiagnosticInput
import android.content.Context
import com.freedom.core.accessibility.overlay.FloatingButtonPosition
import com.freedom.core.logging.RunLogBudgetPolicy
import com.freedom.core.logging.RunLogCategory
import com.freedom.core.logging.RunLogLevel
import org.json.JSONArray

/** Local-only storage for monitor state, recent events and the latest node snapshot. */
object MonitorStore {

    const val DEFAULT_TARGET = "com.immomo.momo"
    const val MAX_NODES_PER_SCAN = 3000
    const val MAX_DISPLAY_NODES = 140

    private const val PREFS = "freedom_monitor_store"
    private const val KEY_TARGET = "target_package"
    private const val KEY_RUNNING = "monitor_running"
    private const val LEGACY_ACTIVE_RUN_MODE = "active_run_mode"
    private const val KEY_LOGS = "event_logs"
    private const val KEY_APP = "current_app"
    private const val KEY_PAGE = "current_page"
    private const val KEY_NODES = "latest_nodes"
    private const val KEY_NODE_COUNT = "latest_node_count"
    private const val KEY_NODE_TRUNCATED = "latest_node_truncated"
    private const val KEY_KEEP_ALIVE = "keep_alive_enabled"
    private const val KEY_BOOT_CHECK = "boot_check_enabled"
    private const val KEY_BOOT_RECOVERY_PENDING = "boot_recovery_pending"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    private const val KEY_FLOATING_LOG = "floating_log_enabled"
    private const val KEY_FLOATING_BUTTON_RIGHT_EDGE = "floating_button_right_edge"
    private const val KEY_FLOATING_BUTTON_Y_FRACTION = "floating_button_y_fraction"
    private const val KEY_OVERLAY_WIDTH_DP = "overlay_width_dp"
    private const val KEY_OVERLAY_HEIGHT_DP = "overlay_height_dp"
    private const val MAX_LOGS = 300
    private const val MAX_LOG_CHARS_PER_ENTRY = 5_000 // Legacy guard; UTF-8 byte budget below is authoritative.

    data class Snapshot(
        val app: String,
        val page: String,
        val nodes: List<String>,
        val visitedCount: Int,
        val truncated: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun targetPackage(context: Context): String =
        prefs(context).getString(KEY_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET

    @Synchronized
    fun setTargetPackage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TARGET, value.trim()).apply()
    }

    /** Upgrade-only cleanup; the persisted run authorization is never changed here. */
    @Synchronized
    internal fun removeObsoleteRunMode(context: Context) {
        val store = prefs(context)
        if (store.contains(LEGACY_ACTIVE_RUN_MODE)) {
            store.edit().remove(LEGACY_ACTIVE_RUN_MODE).commit()
        }
    }

    /** Persisted user intent: whether monitoring should resume after a system rebind. */
    @Synchronized
    fun isRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUNNING, false)

    @Synchronized
    fun setRunning(context: Context, running: Boolean) {
        val store = prefs(context)
        val wasRunning = store.getBoolean(KEY_RUNNING, false)
        // Persist only user authorization; a page route is never restored from preferences.
        store.edit().putBoolean(KEY_RUNNING, running).remove(LEGACY_ACTIVE_RUN_MODE).commit()
        if (running && !wasRunning) {
            RunDiagnosticsManager.get(context).beginRun(
                mode = "PAGE_OBSERVATION"
            )
        } else if (!running && wasRunning) {
            RunDiagnosticsManager.get(context).finishRun(
                result = "STOPPED",
                reason = "运行请求被清除；具体停止原因由任务结束前的关联日志补充"
            )
        }
    }

    @Synchronized
    fun isKeepAliveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_ALIVE, true)

    @Synchronized
    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
    }

    @Synchronized
    fun isBootCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOT_CHECK, true)

    @Synchronized
    fun setBootCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOT_CHECK, enabled).apply()
    }

    /** A boot receiver records this until the system-bound AccessibilityService confirms recovery. */
    @Synchronized
    fun isBootRecoveryPending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOT_RECOVERY_PENDING, false)

    @Synchronized
    fun setBootRecoveryPending(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOT_RECOVERY_PENDING, pending).commit()
    }

    @Synchronized
    fun isOverlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_ENABLED, false)

    @Synchronized
    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    @Synchronized
    fun isFloatingLogEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_LOG, false)

    @Synchronized
    fun setFloatingLogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_LOG, enabled).apply()
    }

    @Synchronized
    fun floatingButtonPosition(context: Context): FloatingButtonPosition {
        val preferences = prefs(context)
        return FloatingButtonPosition(
            rightEdge = preferences.getBoolean(KEY_FLOATING_BUTTON_RIGHT_EDGE, true),
            yFraction = preferences.getFloat(
                KEY_FLOATING_BUTTON_Y_FRACTION,
                FloatingButtonPosition.DEFAULT_Y_FRACTION
            )
        )
    }

    @Synchronized
    fun setFloatingButtonPosition(context: Context, position: FloatingButtonPosition) {
        prefs(context).edit()
            .putBoolean(KEY_FLOATING_BUTTON_RIGHT_EDGE, position.rightEdge)
            .putFloat(KEY_FLOATING_BUTTON_Y_FRACTION, position.normalizedYFraction)
            .apply()
    }

    @Synchronized
    fun overlayWidthDp(context: Context): Int =
        prefs(context).getInt(KEY_OVERLAY_WIDTH_DP, 0)

    @Synchronized
    fun setOverlayWidthDp(context: Context, widthDp: Int) {
        prefs(context).edit().putInt(KEY_OVERLAY_WIDTH_DP, widthDp.coerceAtLeast(220)).apply()
    }

    @Synchronized
    fun overlayHeightDp(context: Context): Int =
        prefs(context).getInt(KEY_OVERLAY_HEIGHT_DP, 0)

    @Synchronized
    fun setOverlayHeightDp(context: Context, heightDp: Int) {
        prefs(context).edit().putInt(KEY_OVERLAY_HEIGHT_DP, heightDp.coerceAtLeast(200)).apply()
    }

    @Synchronized
    fun addLog(context: Context, line: String, input: RunDiagnosticInput? = null) {
        val diagnostics = RunDiagnosticsManager.get(context)
        if (input == null) diagnostics.recordLog(line) else diagnostics.recordInput(input)
        val p = prefs(context)
        val current = runCatching {
            JSONArray(p.getString(KEY_LOGS, "[]"))
        }.getOrElse {
            JSONArray()
        }
        val boundedLine = RunLogBudgetPolicy.truncateUtf8(
            line.take(MAX_LOG_CHARS_PER_ENTRY),
            RunLogBudgetPolicy.MAX_MONITOR_ENTRY_BYTES
        )
        current.put(boundedLine)

        // Keep the newest records first under both a count limit and a real UTF-8 byte budget.
        val retainedNewestFirst = ArrayList<String>(MAX_LOGS)
        var retainedBytes = 0L
        var removedBytes = 0L
        var retentionWindowClosed = false
        for (index in current.length() - 1 downTo 0) {
            val candidate = RunLogBudgetPolicy.truncateUtf8(
                current.optString(index),
                RunLogBudgetPolicy.MAX_MONITOR_ENTRY_BYTES
            )
            val bytes = RunLogBudgetPolicy.utf8Size(candidate).toLong()
            if (retentionWindowClosed ||
                retainedNewestFirst.size >= MAX_LOGS ||
                retainedBytes + bytes > RunLogBudgetPolicy.MAX_MONITOR_TOTAL_BYTES
            ) {
                retentionWindowClosed = true
                removedBytes += bytes
                continue
            }
            retainedNewestFirst += candidate
            retainedBytes += bytes
        }
        val trimmed = JSONArray()
        retainedNewestFirst.asReversed().forEach(trimmed::put)
        val removedCount = (current.length() - retainedNewestFirst.size).coerceAtLeast(0)
        if (removedCount > 0) {
            diagnostics.recordStructured(
                eventType = "monitor_history_evicted",
                category = RunLogCategory.DATA_QUALITY,
                // This is intentional rolling UI history retention; the independent
                // run archive has its own drop/truncation/write-failure counters.
                level = RunLogLevel.INFO,
                component = "MonitorStore",
                message = "普通运行日志历史达到容量预算；已移除旧记录 $removedCount 条，估算字节=$removedBytes",
                result = "RETENTION_APPLIED"
            )
        }
        p.edit().putString(KEY_LOGS, trimmed.toString()).apply()
    }

    @Synchronized
    fun logs(context: Context): List<String> {
        val array = runCatching {
            JSONArray(prefs(context).getString(KEY_LOGS, "[]"))
        }.getOrElse {
            JSONArray()
        }
        return (0 until array.length()).mapNotNull {
            array.optString(it).takeIf(String::isNotBlank)
        }
    }

    @Synchronized
    fun clearLogs(context: Context) {
        RunDiagnosticsManager.get(context).recordStructured(
            eventType = "monitor_history_cleared",
            category = RunLogCategory.LIFECYCLE,
            component = "MonitorStore",
            message = "用户清空了普通运行日志展示历史；任务诊断事件与已生成报告不受影响",
            result = "CLEARED"
        )
        prefs(context).edit().remove(KEY_LOGS).apply()
    }

    @Synchronized
    fun updateAppPage(context: Context, app: String? = null, page: String? = null) {
        val p = prefs(context)
        val previousApp = p.getString(KEY_APP, "")
        val editor = p.edit()
        if (!app.isNullOrBlank()) {
            editor.putString(KEY_APP, app.take(100))
            if (!previousApp.isNullOrBlank() && previousApp != app) {
                editor.putString(KEY_NODES, "[]")
                    .putInt(KEY_NODE_COUNT, 0)
                    .putBoolean(KEY_NODE_TRUNCATED, false)
            }
        }
        if (!page.isNullOrBlank()) editor.putString(KEY_PAGE, page.take(160))
        editor.apply()
    }

    @Synchronized
    fun saveSnapshot(
        context: Context,
        app: String,
        page: String,
        nodes: List<String>,
        visitedCount: Int,
        truncated: Boolean
    ) {
        val array = JSONArray()
        nodes.take(MAX_DISPLAY_NODES).forEach { array.put(it) }
        prefs(context).edit()
            .putString(KEY_APP, app.take(100))
            .putString(KEY_PAGE, page.take(160))
            .putString(KEY_NODES, array.toString())
            .putInt(KEY_NODE_COUNT, visitedCount)
            .putBoolean(KEY_NODE_TRUNCATED, truncated)
            .apply()
    }

    @Synchronized
    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        val array = runCatching {
            JSONArray(p.getString(KEY_NODES, "[]"))
        }.getOrElse {
            JSONArray()
        }
        val nodes = (0 until array.length()).mapNotNull {
            array.optString(it).takeIf(String::isNotBlank)
        }
        return Snapshot(
            app = p.getString(KEY_APP, "尚未识别") ?: "尚未识别",
            page = p.getString(KEY_PAGE, "等待页面信息") ?: "等待页面信息",
            nodes = nodes,
            visitedCount = p.getInt(KEY_NODE_COUNT, 0),
            truncated = p.getBoolean(KEY_NODE_TRUNCATED, false)
        )
    }
}
