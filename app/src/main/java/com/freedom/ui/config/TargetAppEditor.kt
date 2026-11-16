package com.freedom.ui.config

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.freedom.application.ports.MonitorControlRepository
import com.freedom.ui.theme.FreedomUi

/** Edits the selected target package while preserving the existing validation and save flow. */
internal object TargetAppEditor {
    fun show(
        activity: Activity,
        repository: MonitorControlRepository,
        dp: (Int) -> Int,
        packagePattern: Regex,
        timestamp: () -> String,
        notifyTargetChanged: () -> Unit,
        refreshScreen: () -> Unit
    ) {
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = "例如 com.immomo.momo"
            setText(repository.targetPackage())
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(TextView(activity).apply {
                text = "输入需要监控的 Android 应用包名："
                textSize = 13f
                setTextColor(FreedomUi.SECONDARY)
            })
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("监控目标")
            .setView(container)
            .setPositiveButton("保存目标") { _, _ ->
                val value = input.text.toString().trim()
                if (!packagePattern.matches(value)) {
                    Toast.makeText(
                        activity,
                        "包名格式不正确，例如 com.immomo.momo",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    repository.setTargetPackage(value)
                    notifyTargetChanged()
                    repository.updateAppPage(
                        appLabel = "尚未识别",
                        pageLabel = "等待页面信息"
                    )
                    repository.addLog(timestamp() + "  监控目标已设置为 $value")
                    refreshScreen()
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        FreedomUi.styleAlertDialog(dialog, activity, dp, widthFraction = 0.88f)
    }
}
