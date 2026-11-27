package com.freedom.ui.theme

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shared visual primitives for Freedom's warm, restrained UI language. */
internal object FreedomUi {
    const val BACKGROUND = 0xFFF8F7F0.toInt()
    const val SURFACE = 0xFFFBFAF5.toInt()
    const val TEXT = 0xFF23372B.toInt()
    const val SECONDARY = 0xFF56665B.toInt()
    const val MUTED = 0xFF7D887E.toInt()
    const val DIVIDER = 0xFFDDE2D8.toInt()
    const val FOREST = 0xFF23372B.toInt()
    const val FOREST_DARK = 0xFF17281F.toInt()
    const val LIME = 0xFFD8EF68.toInt()
    const val LIME_DARK = 0xFFA5C331.toInt()
    const val LIME_WASH = 0xFFEAF2CF.toInt()
    const val ERROR = 0xFF9B5145.toInt()

    fun surface(radiusPx: Int, fill: Int = BACKGROUND, stroke: Int = DIVIDER): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusPx.toFloat()
            setStroke(1, stroke)
        }

    fun roundedSurface(
        radiusPx: Int,
        fill: Int = BACKGROUND,
        stroke: Int = DIVIDER
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusPx.toFloat()
        setStroke(1, stroke)
    }

    fun styleAlertDialog(
        dialog: AlertDialog,
        activity: Activity,
        dp: (Int) -> Int,
        widthFraction: Float = 0.90f
    ) {
        dialog.window?.apply {
            setBackgroundDrawable(roundedSurface(dp(18), BACKGROUND, DIVIDER))
            setDimAmount(0.28f)
            setLayout(
                (activity.resources.displayMetrics.widthPixels * widthFraction).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val alertTitleId = activity.resources.getIdentifier("alertTitle", "id", "android")
        if (alertTitleId != 0) {
            dialog.findViewById<TextView>(alertTitleId)?.apply {
                setTextColor(TEXT)
                textSize = 19f
            }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(SECONDARY)
            textSize = 14f
        }
        listOf(
            AlertDialog.BUTTON_POSITIVE,
            AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL
        ).forEach { which ->
            dialog.getButton(which)?.apply {
                setTextColor(LIME_DARK)
                isAllCaps = false
                textSize = 14f
                minHeight = dp(44)
            }
        }
        dialog.listView?.apply {
            divider = ColorDrawable(DIVIDER)
            dividerHeight = dp(1)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
    }

    /** Blank is zero only for integer settings; invalid/overflowing values stay invalid. */
    fun parseEditorInteger(raw: String): Int? = raw.trim().ifEmpty { "0" }.toIntOrNull()

    fun editorInput(
        activity: Activity,
        dp: (Int) -> Int,
        current: String,
        inputType: Int,
        hint: String = "",
        multiline: Boolean = false
    ): EditText = EditText(activity).apply {
        isSingleLine = !multiline
        this.inputType = inputType
        if (multiline) {
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            minimumHeight = dp(76)
        } else {
            minimumHeight = dp(52)
        }
        textSize = if ((inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER) 18f else 16f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        this.hint = hint
        background = roundedSurface(dp(10), Color.WHITE, DIVIDER)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setText(current)
        setSelection(text.length)
    }

    fun editorContent(activity: Activity, dp: (Int) -> Int): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }

    fun addEditorInput(content: LinearLayout, input: EditText, dp: (Int) -> Int) {
        content.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    fun addEditorHint(content: LinearLayout, text: String, dp: (Int) -> Int) {
        content.addView(TextView(content.context).apply {
            this.text = text
            textSize = 11f
            setTextColor(MUTED)
            setPadding(dp(2), dp(8), 0, 0)
        })
    }

    fun createEditorDialog(
        activity: Activity,
        title: String,
        content: LinearLayout,
        positiveLabel: String = "保存",
        message: String? = null,
        onCancel: (() -> Unit)? = null
    ): AlertDialog = AlertDialog.Builder(activity)
        .setTitle(title)
        .apply { if (message != null) setMessage(message) }
        .setView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addView(content)
        })
        .setNegativeButton("取消") { _, _ -> onCancel?.invoke() }
        .setPositiveButton(positiveLabel, null)
        .create()

    /** Positive clicks never auto-dismiss; field owners retain their validation and save semantics. */
    fun showEditorDialog(
        dialog: AlertDialog,
        activity: Activity,
        dp: (Int) -> Int,
        input: EditText,
        onSave: () -> Unit
    ) {
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching(onSave).onFailure { input.error = "保存失败，请检查输入后重试" }
            }
            input.requestFocus()
        }
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        styleAlertDialog(dialog, activity, dp)
    }

    fun stylePrimaryButton(button: Button, dp: (Int) -> Int) {
        button.isAllCaps = false
        button.textSize = 14f
        button.setTextColor(Color.WHITE)
        button.minHeight = dp(46)
        button.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(LIME_DARK, FOREST)
        ).apply {
            cornerRadius = dp(12).toFloat()
        }
        button.elevation = dp(1).toFloat()
    }

    fun styleSecondaryButton(button: Button, dp: (Int) -> Int) {
        button.isAllCaps = false
        button.textSize = 14f
        button.setTextColor(TEXT)
        button.minHeight = dp(44)
        button.background = GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), DIVIDER)
        }
    }

    fun hideScrollbars(view: View) {
        when (view) {
            is ScrollView -> {
                view.isVerticalScrollBarEnabled = false
                view.isHorizontalScrollBarEnabled = false
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) hideScrollbars(view.getChildAt(index))
        }
    }
}
