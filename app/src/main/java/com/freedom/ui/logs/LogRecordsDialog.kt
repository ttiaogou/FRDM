package com.freedom.ui.logs

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.freedom.ui.theme.FreedomUi

/** Activity-owned collection/export UI; no live-log or diagnostic-report window. */
internal object CollectionExportDialog {
    data class Action(
        val label: String,
        val description: String? = null,
        val primary: Boolean = false,
        val onClick: () -> Unit
    )

    /** Preserve the former TXT export button's gradient, type and rounded shape. */
    fun stylePrimaryExportButton(view: TextView, dp: (Int) -> Int) {
        view.textSize = 12f
        view.typeface = Typeface.DEFAULT_BOLD
        view.gravity = Gravity.CENTER
        view.setTextColor(Color.WHITE)
        view.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(FreedomUi.LIME_DARK, FreedomUi.FOREST)
        ).apply { cornerRadius = dp(11).toFloat() }
        view.minimumHeight = dp(44)
        view.setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    /** Confirmation presentation only; the Activity retains the export transaction. */
    fun confirmSessionSampleExport(
        activity: Activity,
        dp: (Int) -> Int,
        archives: List<SessionSampleArchive>,
        onConfirm: () -> Unit
    ) {
        val contentLabels = archives.flatMap { it.contentLabels }.distinct()
        val confirmationContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
            addView(TextView(activity).apply {
                text = buildString {
                    append("采集任务数量：").append(archives.size).append("\n")
                    append("导出方式：合并为一个 ZIP；采集样本和关联的运行诊断按运行 ID 分目录保存")
                        .append("\n\n")
                    archives.take(12).forEachIndexed { index, archive ->
                        append(index + 1).append(". ").append(archive.runId).append("\n")
                        append("   ").append(archive.durationLabel()).append("；")
                            .append(archive.sampleCountLabel()).append("\n")
                    }
                    if (archives.size > 12) append("……另有 ").append(archives.size - 12).append(" 条采集任务\n")
                }
                textSize = 13f
                setTextColor(FreedomUi.SECONDARY)
                setPadding(0, dp(4), 0, dp(10))
            })
            addView(TextView(activity).apply {
                text = "包含内容（按归档实际文件检测）"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(FreedomUi.TEXT)
                setPadding(0, dp(4), 0, dp(4))
            })
            if (contentLabels.isEmpty()) {
                addView(TextView(activity).apply {
                    text = "未识别到可展示的数据分类；导出仍会保留归档中的原始文件。"
                    textSize = 12f
                    setTextColor(FreedomUi.MUTED)
                })
            } else {
                contentLabels.forEach { label ->
                    addView(CheckBox(activity).apply {
                        text = label
                        isChecked = true
                        isEnabled = false
                        textSize = 13f
                    })
                }
            }
            addView(TextView(activity).apply {
                text = "本次导出会尝试附带每条运行对应的 diagnostic_report.txt、诊断事件、计数器及清单；若诊断尚未封存，会在导出清单中标明。"
                textSize = 12f
                setTextColor(FreedomUi.SECONDARY)
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(activity).apply {
                text = "导出的内容可能包含聊天文本、截图及其他个人信息，请注意隐私与数据安全。"
                textSize = 12f
                setTextColor(FreedomUi.MUTED)
                setPadding(0, dp(12), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (archives.size == 1) "导出采集任务" else "批量导出采集任务")
            .setView(ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                addView(confirmationContent)
                isFillViewport = true
            })
            .setNegativeButton("取消", null)
            .setPositiveButton("导出 ZIP") { _, _ -> onConfirm() }
            .show()
        FreedomUi.styleAlertDialog(dialog, activity, dp)
        CollectionExportDialog.stylePrimaryExportButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), dp)
    }

    fun show(
        activity: Activity,
        dp: (Int) -> Int,
        title: String,
        actions: List<Action>,
        message: String? = null
    ) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(14))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                addView(content)
            })
            .setNegativeButton("关闭", null)
            .create()
        message?.let { status ->
            content.addView(TextView(activity).apply {
                text = status
                textSize = 13f
                setTextColor(FreedomUi.MUTED)
                setPadding(0, dp(8), 0, dp(12))
            })
        }
        actions.forEach { action ->
            content.addView(TextView(activity).apply {
                text = action.label
                if (action.primary) {
                    stylePrimaryExportButton(this, dp)
                } else {
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(FreedomUi.TEXT)
                    background = FreedomUi.roundedSurface(dp(11), FreedomUi.SURFACE, FreedomUi.DIVIDER)
                    minimumHeight = dp(48)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action.onClick()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            action.description?.let { description ->
                content.addView(TextView(activity).apply {
                    text = description
                    textSize = 12f
                    setTextColor(FreedomUi.MUTED)
                    setPadding(dp(4), dp(4), dp(4), dp(6))
                })
            }
        }
        dialog.show()
        FreedomUi.styleAlertDialog(dialog, activity, dp, widthFraction = 0.92f)
    }
}
