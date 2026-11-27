package com.freedom.ui.config

import android.app.Activity
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
        val input = FreedomUi.editorInput(
            activity, dp, repository.targetPackage(),
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI,
            "例如 com.immomo.momo"
        )
        val content = FreedomUi.editorContent(activity, dp)
        FreedomUi.addEditorInput(content, input, dp)
        FreedomUi.addEditorHint(content, "输入需要监控的 Android 应用包名", dp)
        val dialog = FreedomUi.createEditorDialog(activity, "监控目标", content, positiveLabel = "保存目标")
        FreedomUi.showEditorDialog(dialog, activity, dp, input) {
            val value = input.text.toString().trim()
            if (!packagePattern.matches(value)) {
                input.error = "包名格式不正确，例如 com.immomo.momo"
                return@showEditorDialog
            }
            repository.setTargetPackage(value)
            notifyTargetChanged()
            repository.updateAppPage(
                appLabel = "尚未识别",
                pageLabel = "等待页面信息"
            )
            repository.addLog(timestamp() + "  监控目标已设置为 $value")
            refreshScreen()
            dialog.dismiss()
        }
    }
}
