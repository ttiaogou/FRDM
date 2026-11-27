package com.freedom.ui.logs

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.freedom.ui.theme.FreedomUi

/** Read-only run history. Capture/export actions are owned by the floating-button menu. */
internal object LogRecordsDialog {
    fun show(
        activity: Activity,
        records: List<String>,
        sampleRuns: List<SessionSampleArchive>,
        dp: (Int) -> Int,
        onExportLogs: (List<String>) -> Unit,
        onShowDiagnosticReport: () -> String,
        onCleared: () -> Unit
    ) {
        var showTechnicalDetails = false
        var activeTab = 0

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            background = FreedomUi.roundedSurface(dp(18), FreedomUi.BACKGROUND, FreedomUi.DIVIDER)
            minimumHeight = dp(540)
        }
        lateinit var dialog: AlertDialog

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(activity).apply {
            text = "运行记录"
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(FreedomUi.TEXT)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(activity).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(FreedomUi.TEXT)
            contentDescription = "关闭运行记录"
            background = rippleBackground(dp, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)
        root.addView(divider(activity), LinearLayout.LayoutParams(-1, dp(1)))

        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        val logsTab = tabButton(activity, "运行日志", dp)
        val runsTab = tabButton(activity, "采集归档", dp)
        tabs.addView(logsTab, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            rightMargin = dp(5)
        })
        tabs.addView(runsTab, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(5)
        })
        root.addView(tabs)

        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))

        fun updateTabs() {
            styleTab(logsTab, activeTab == 0, dp)
            styleTab(runsTab, activeTab == 1, dp)
        }

        fun renderLogs() {
            panel.removeAllViews()
            panel.addView(sectionHeading(activity, "运行日志", "最近 ${records.size} 条", dp))
            val logText = TextView(activity).apply {
                textSize = 12f
                setTextColor(FreedomUi.SECONDARY)
                setTextIsSelectable(true)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                text = LogTextFormatter.formatLogLines(records, false).ifBlank { "暂无运行记录" }
                setLineSpacing(dp(2).toFloat(), 1f)
                background = surfaceBackground(dp, 12, FreedomUi.SURFACE)
            }
            val technicalToggle = TextView(activity).apply {
                text = "查看技术详情"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(FreedomUi.LIME_DARK)
                background = rippleBackground(dp, 10, FreedomUi.LIME_WASH)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    showTechnicalDetails = !showTechnicalDetails
                    text = if (showTechnicalDetails) "收起技术详情" else "查看技术详情"
                    logText.text = LogTextFormatter.formatLogLines(records, showTechnicalDetails)
                        .ifBlank { "暂无运行记录" }
                }
            }
            panel.addView(technicalToggle, LinearLayout.LayoutParams(-1, dp(42)).apply {
                bottomMargin = dp(8)
            })
            panel.addView(TextView(activity).apply {
                text = "运行诊断 ZIP 包含已封存日志、异常证据与最近展示记录；节点可视化与采集数据导出请从悬浮按钮进入。"
                textSize = 11f
                setTextColor(FreedomUi.MUTED)
                setPadding(dp(2), 0, dp(2), dp(8))
            })
            panel.addView(ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                addView(logText)
            }, LinearLayout.LayoutParams(-1, 0, 1f))

            val footer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            }
            footer.addView(actionButton(activity, "导出 ZIP", dp, primary = true) {
                dialog.dismiss()
                onExportLogs(records)
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
            footer.addView(
                actionButton(activity, "诊断报告", dp) {
                    val reportView = TextView(activity).apply {
                        text = onShowDiagnosticReport()
                        textSize = 12f
                        setTextColor(FreedomUi.SECONDARY)
                        setTextIsSelectable(true)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background = surfaceBackground(dp, 12, FreedomUi.SURFACE)
                    }
                    val reportDialog = AlertDialog.Builder(activity)
                        .setTitle("最近一次任务诊断")
                        .setView(ScrollView(activity).apply {
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            setPadding(dp(16), dp(4), dp(16), dp(8))
                            addView(reportView)
                        })
                        .setPositiveButton("关闭", null)
                        .create()
                    reportDialog.show()
                    FreedomUi.styleAlertDialog(reportDialog, activity, dp, 0.90f)
                },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    leftMargin = dp(5)
                    rightMargin = dp(5)
                }
            )
            footer.addView(actionButton(activity, "清空记录", dp) {
                dialog.dismiss()
                onCleared()
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
            panel.addView(footer)
        }

        fun renderRuns() {
            panel.removeAllViews()
            panel.addView(sectionHeading(activity, "采集归档", "${sampleRuns.size} 条", dp))
            if (sampleRuns.isEmpty()) {
                panel.addView(TextView(activity).apply {
                    text = "暂无已封存的采集任务\n运行过程中产生的数据会在任务结束后归档。"
                    textSize = 14f
                    setTextColor(FreedomUi.MUTED)
                    gravity = Gravity.CENTER
                    setLineSpacing(dp(5).toFloat(), 1f)
                    setPadding(dp(20), dp(36), dp(20), dp(36))
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                val list = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, dp(8))
                }
                sampleRuns.forEach { run ->
                    list.addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background = surfaceBackground(dp, 12, FreedomUi.SURFACE)
                        addView(TextView(activity).apply {
                            text = run.runId
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(FreedomUi.TEXT)
                        })
                        addView(TextView(activity).apply {
                            val integrity = run.archiveIntegrityStatus ?: "未检测"
                            val warnings = run.captureWarnings.joinToString("、").ifBlank { "无" }
                            val nodeTreeStatus = if (run.nodeTreeCaptureCount > 0) {
                                "节点树已采集 ${run.nodeTreeCaptureCount} 份"
                            } else {
                                "节点树未采集（当前任务未产生结构快照）"
                            }
                            text = "采集时长：${run.durationLabel()}\n${run.sampleCountLabel()}\n" +
                                "$nodeTreeStatus\n归档完整性：$integrity · 采集告警：$warnings\n" +
                                run.contentLabels.joinToString("、").ifBlank { "归档内容未识别" }
                            textSize = 11f
                            setTextColor(FreedomUi.MUTED)
                            setLineSpacing(dp(2).toFloat(), 1f)
                        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
                panel.addView(ScrollView(activity).apply {
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                    addView(list)
                    isFillViewport = true
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }

        fun renderTab() {
            updateTabs()
            if (activeTab == 0) renderLogs() else renderRuns()
        }
        logsTab.setOnClickListener { activeTab = 0; renderTab() }
        runsTab.setOnClickListener { activeTab = 1; renderTab() }

        dialog = AlertDialog.Builder(activity)
            .setView(root)
            .create()
        renderTab()
        dialog.show()
        FreedomUi.styleAlertDialog(dialog, activity, dp, 0.94f)
    }

    private fun sectionHeading(
        activity: Activity,
        title: String,
        trailing: String,
        dp: (Int) -> Int
    ) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(2), dp(2), dp(10))
        addView(TextView(activity).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(FreedomUi.TEXT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = trailing
            textSize = 12f
            setTextColor(FreedomUi.MUTED)
        })
    }

    private fun tabButton(activity: Activity, label: String, dp: (Int) -> Int) =
        TextView(activity).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            isClickable = true
            isFocusable = true
        }

    private fun styleTab(view: TextView, selected: Boolean, dp: (Int) -> Int) {
        view.setTextColor(if (selected) FreedomUi.FOREST else FreedomUi.SECONDARY)
        view.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.background = GradientDrawable().apply {
            setColor(if (selected) FreedomUi.LIME_WASH else 0xFFEFEEE7.toInt())
            cornerRadius = dp(11).toFloat()
            if (selected) setStroke(dp(1), 0xFFC9D99D.toInt())
        }
    }

    private fun actionButton(
        activity: Activity,
        label: String,
        dp: (Int) -> Int,
        primary: Boolean = false,
        action: () -> Unit
    ) = TextView(activity).apply {
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else FreedomUi.TEXT)
        background = if (primary) {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(FreedomUi.LIME_DARK, FreedomUi.FOREST)
            ).apply { cornerRadius = dp(11).toFloat() }
        } else {
            rippleBackground(dp, 11, FreedomUi.SURFACE)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun divider(activity: Activity) = View(activity).apply {
        setBackgroundColor(FreedomUi.DIVIDER)
    }

    private fun surfaceBackground(
        dp: (Int) -> Int,
        radius: Int,
        fill: Int
    ) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), FreedomUi.DIVIDER)
    }

    private fun rippleBackground(
        dp: (Int) -> Int,
        radius: Int,
        fill: Int = Color.TRANSPARENT
    ) = RippleDrawable(
        ColorStateList.valueOf(0x26718E22),
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
        },
        GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(radius).toFloat()
        }
    )
}
