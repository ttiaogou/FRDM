package com.freedom.ui.logs

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
