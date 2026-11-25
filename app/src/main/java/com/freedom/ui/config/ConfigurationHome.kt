package com.freedom.ui.config

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.freedom.R
import com.freedom.application.TaskLaunchParameters
import com.freedom.ui.theme.FreedomUi

/** Task launcher UI. Business state and actions are supplied through callbacks. */
internal class ConfigurationHome(
    private val activity: Activity,
    private val callbacks: Callbacks
) {
    data class Callbacks(
        val dp: (Int) -> Int,
        val targetLabel: () -> String,
        val taskParameters: () -> TaskLaunchParameters,
        val onTaskParametersChanged: (TaskLaunchParameters) -> Unit,
        val onTargetClick: () -> Unit,
        val onPersonalization: () -> Unit,
        val onRecordsTab: () -> Unit,
        val onOpenDrawer: () -> Unit,
        val onRunClick: () -> Unit
    )

    private var runButton: ImageButton? = null
    private var targetValue: TextView? = null
    private var distanceValue: TextView? = null
    private var minAgeValue: TextView? = null
    private var maxAgeValue: TextView? = null
    private var allStatusChip: TextView? = null
    private var onlineOnlyChip: TextView? = null
    private var parameters = callbacks.taskParameters()

    fun refresh(running: Boolean) {
        runButton?.apply {
            setImageResource(if (running) R.drawable.ic_run_stop else R.drawable.ic_run_start)
            contentDescription = if (running) "停止运行" else "开始运行"
        }
        targetValue?.text = callbacks.targetLabel()
    }

    fun create(): View {
        parameters = callbacks.taskParameters()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setOnApplyWindowInsetsListener { view, insets ->
                val top: Int
                val bottom: Int
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                    )
                    top = bars.top
                    bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    val t = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    val b = insets.systemWindowInsetBottom
                    top = t
                    bottom = b
                }
                view.setPadding(view.paddingLeft, top, view.paddingRight, bottom)
                insets
            }
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(68)))

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            clipToPadding = false
            setPadding(dp(20), dp(10), dp(20), dp(22))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(buildTargetSection())
        content.addView(buildPersonalizationRow(), spaced(18))
        content.addView(buildFilterPanel(), spaced(20))
        scroll.addView(content)
        installLeftEdgeGesture(scroll)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildRunFooter(), LinearLayout.LayoutParams(-1, dp(88)))
        installLeftEdgeGesture(root)
        return root
    }

    private fun installLeftEdgeGesture(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            override fun onTouch(view: View, event: android.view.MotionEvent): Boolean {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = event.x - downX
                        val dy = kotlin.math.abs(event.y - downY)
                        if (downX <= dp(26) && dx >= dp(72) && dy < dp(72)) {
                            callbacks.onOpenDrawer()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    private fun buildHeader(): View {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(6), dp(18), dp(4))
            setBackgroundColor(BACKGROUND)
        }
        val configEntry = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = clickableBackground(10)
            isClickable = true
            isFocusable = true
            setOnClickListener { callbacks.onOpenDrawer() }
        }
        configEntry.addView(View(activity).apply {
            setBackgroundColor(LIME_DARK)
        }, LinearLayout.LayoutParams(dp(3), dp(26)).apply { rightMargin = dp(7) })
        configEntry.addView(TextView(activity).apply {
            text = "配置"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
        })

        val recordsEntry = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_run_history)
            imageTintList = ColorStateList.valueOf(TEXT)
            background = clickableBackground(24)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "运行记录"
            isFocusable = true
            setOnClickListener { callbacks.onRecordsTab() }
        }
        header.addView(configEntry, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(recordsEntry, LinearLayout.LayoutParams(dp(48), dp(48)))
        return header
    }

    private fun buildRunFooter(): View {
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(8), dp(20), dp(8))
            background = clickableBackground(18)
            isClickable = true
            isFocusable = true
            setOnClickListener { callbacks.onRunClick() }
        }
        footer.addView(View(activity).apply {
            setBackgroundColor(DIVIDER)
        }, LinearLayout.LayoutParams(0, dp(1), 1f))
        val runEntry = ImageButton(activity).apply {
            background = clickableBackground(34)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "开始运行"
            setPadding(0, 0, 0, 0)
            setImageResource(R.drawable.ic_run_start)
            setOnClickListener { callbacks.onRunClick() }
        }
        runButton = runEntry
        footer.addView(runEntry, LinearLayout.LayoutParams(dp(68), dp(68)).apply {
            leftMargin = dp(8)
        })
        return footer
    }

    private fun buildTargetSection(): View {
        val section = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        section.addView(sectionLabel("选择目标应用"))
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(8))
            background = clickableBackground(10)
            isClickable = true
            isFocusable = true
            setOnClickListener { callbacks.onTargetClick() }
        }
        val name = TextView(activity).apply {
            text = callbacks.targetLabel()
            textSize = 26f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(TEXT)
        }
        targetValue = name
        row.addView(name, LinearLayout.LayoutParams(0, dp(54), 1f))
        row.addView(TextView(activity).apply {
            text = "⌄"
            textSize = 25f
            setTextColor(SECONDARY)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(2), 0)
        })
        section.addView(row)
        section.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        return section
    }

    private fun buildPersonalizationRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
            background = clickableBackground(10)
            isClickable = true
            isFocusable = true
            setOnClickListener { callbacks.onPersonalization() }
        }
        row.addView(View(activity).apply {
            setBackgroundColor(LIME_DARK)
        }, LinearLayout.LayoutParams(dp(3), dp(34)).apply { rightMargin = dp(12) })
        row.addView(TextView(activity).apply {
            text = "个性化"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(activity).apply {
            text = "›"
            textSize = 27f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
        })
        return row
    }

    private fun buildFilterPanel(): View {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(16))
            background = rounded(DARK_PANEL, DARK_PANEL, 14)
        }
        panel.addView(View(activity).apply {
            setBackgroundColor(LIME)
        }, LinearLayout.LayoutParams(dp(66), dp(3)).apply {
            bottomMargin = dp(12)
        })
        panel.addView(TextView(activity).apply {
            text = "筛选条件"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })

        val onlineStatusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(10))
        }
        onlineStatusRow.addView(TextView(activity).apply {
            text = "在线状态"
            textSize = 13f
            setTextColor(PANEL_MUTED)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val statusChoices = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val allChip = filterChip("不限", selected = !parameters.onlineOnly) {
            saveParameters(parameters.copy(onlineOnly = false))
        }
        val onlyOnlineChip = filterChip("仅在线", selected = parameters.onlineOnly) {
            saveParameters(parameters.copy(onlineOnly = true))
        }
        allStatusChip = allChip
        onlineOnlyChip = onlyOnlineChip
        statusChoices.addView(allChip, LinearLayout.LayoutParams(dp(62), dp(34)))
        statusChoices.addView(onlyOnlineChip, LinearLayout.LayoutParams(dp(76), dp(34)).apply {
            leftMargin = dp(8)
        })
        onlineStatusRow.addView(statusChoices)
        panel.addView(onlineStatusRow)
        panel.addView(View(activity).apply {
            setBackgroundColor(0xFF465149.toInt())
        }, LinearLayout.LayoutParams(-1, dp(1)))

        val distanceRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(7), dp(6), dp(9))
            background = clickableBackground(8)
            isClickable = true
            isFocusable = true
            contentDescription = "最大距离 ${parameters.maxDistanceKm} 公里，点击编辑"
            setOnClickListener {
                showNumberEditor(
                    title = "最大距离",
                    value = parameters.maxDistanceKm,
                    suffix = "km",
                    validate = { value -> if (value < 0) "距离不能小于 0" else null },
                    onSave = { value -> saveParameters(parameters.copy(maxDistanceKm = value)) }
                )
            }
        }
        distanceRow.addView(TextView(activity).apply {
            text = "最大距离"
            textSize = 13f
            setTextColor(PANEL_MUTED)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val distanceColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val distanceNumber = TextView(activity).apply {
            text = parameters.maxDistanceKm.toString()
            contentDescription = "最大距离 ${parameters.maxDistanceKm} 公里，点击编辑"
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(LIME)
            gravity = Gravity.END
        }
        distanceValue = distanceNumber
        distanceColumn.addView(distanceNumber)
        distanceColumn.addView(TextView(activity).apply {
            text = "km（0为不限）"
            textSize = 10f
            setTextColor(PANEL_MUTED)
        })
        distanceRow.addView(distanceColumn)
        panel.addView(distanceRow, LinearLayout.LayoutParams(-1, -2))
        panel.addView(View(activity).apply { setBackgroundColor(0xFF465149.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))

        val ageRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(6))
        }
        ageRow.addView(TextView(activity).apply {
            text = "年龄限制"
            textSize = 13f
            setTextColor(PANEL_MUTED)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val minValue = panelValue(parameters.minAge.toString()) {
            showNumberEditor(
                title = "最低年龄",
                value = parameters.minAge,
                suffix = "岁",
                validate = { value ->
                    when {
                        value !in 0..120 -> "年龄范围应为 0–120"
                        parameters.maxAge > 0 && value > parameters.maxAge -> "最低年龄不能大于最高年龄"
                        else -> null
                    }
                },
                onSave = { value -> saveParameters(parameters.copy(minAge = value)) }
            )
        }
        minAgeValue = minValue
        ageRow.addView(minValue, LinearLayout.LayoutParams(dp(52), dp(34)))
        ageRow.addView(TextView(activity).apply {
            text = "—"
            textSize = 14f
            setTextColor(PANEL_MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(30), dp(34)))
        val maxValue = panelValue(parameters.maxAge.toString()) {
            showNumberEditor(
                title = "最高年龄",
                value = parameters.maxAge,
                suffix = "岁",
                validate = { value ->
                    when {
                        value !in 0..120 -> "年龄范围应为 0–120"
                        value > 0 && parameters.minAge > 0 && value < parameters.minAge -> "最高年龄不能小于最低年龄"
                        else -> null
                    }
                },
                onSave = { value -> saveParameters(parameters.copy(maxAge = value)) }
            )
        }
        maxAgeValue = maxValue
        ageRow.addView(maxValue, LinearLayout.LayoutParams(dp(52), dp(34)))
        ageRow.addView(TextView(activity).apply {
            text = "岁"
            textSize = 13f
            setTextColor(PANEL_MUTED)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
        })
        panel.addView(ageRow)
        panel.addView(TextView(activity).apply {
            text = "0为不限 · 点击数值编辑"
            textSize = 10f
            setTextColor(PANEL_MUTED)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        return panel
    }

    private fun buildMessageBlockingRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0.55f
            setPadding(dp(2), dp(8), dp(2), dp(4))
        }
        row.addView(TextView(activity).apply {
            text = "开启消息拦截"
            textSize = 14f
            setTextColor(TEXT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(styledSwitch().apply {
            isEnabled = false
            isChecked = false
            contentDescription = "开启消息拦截，暂未接入"
        })
        return row
    }

    private fun filterChip(
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(activity).apply {
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        updateFilterChipStyle(this, selected)
        setOnClickListener { onClick() }
    }

    private fun updateFilterChipStyle(chip: TextView, selected: Boolean) {
        chip.setTextColor(if (selected) TEXT else PANEL_MUTED)
        chip.background = GradientDrawable().apply {
            setColor(if (selected) LIME else 0xFF2D3931.toInt())
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), if (selected) LIME else 0xFF68736A.toInt())
        }
        chip.contentDescription = if (selected) "${chip.text}，已选中" else "${chip.text}，未选中"
    }

    private fun panelValue(value: String, onClick: () -> Unit) = TextView(activity).apply {
        text = value
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = clickableBackground(8, 0xFF2D3931.toInt(), 0xFF68736A.toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        contentDescription = "当前值 $value，点击编辑"
    }

    private fun saveParameters(updated: TaskLaunchParameters) {
        parameters = updated
        callbacks.onTaskParametersChanged(updated)
        distanceValue?.apply {
            text = updated.maxDistanceKm.toString()
            contentDescription = "最大距离 ${updated.maxDistanceKm} 公里，点击编辑"
        }
        minAgeValue?.apply {
            text = updated.minAge.toString()
            contentDescription = "当前值 ${updated.minAge}，点击编辑"
        }
        maxAgeValue?.apply {
            text = updated.maxAge.toString()
            contentDescription = "当前值 ${updated.maxAge}，点击编辑"
        }
        allStatusChip?.let { updateFilterChipStyle(it, selected = !updated.onlineOnly) }
        onlineOnlyChip?.let { updateFilterChipStyle(it, selected = updated.onlineOnly) }
    }

    private fun showNumberEditor(
        title: String,
        value: Int,
        suffix: String,
        validate: (Int) -> String?,
        onSave: (Int) -> Unit
    ) {
        val input = EditText(activity).apply {
            setText(value.toString())
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 18f
            isSingleLine = true
            setTextColor(TEXT)
            hint = "输入$suffix"
            background = rounded(Color.WHITE, BORDER, 10)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
            addView(TextView(activity).apply {
                text = "输入 0 表示不限（如该项目适用）"
                textSize = 11f
                setTextColor(MUTED)
                setPadding(dp(2), dp(8), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = input.text.toString().trim().toIntOrNull()
                if (parsed == null) {
                    input.error = "请输入有效数字"
                    return@setOnClickListener
                }
                val validationError = validate(parsed)
                if (validationError != null) {
                    input.error = validationError
                    return@setOnClickListener
                }
                onSave(parsed)
                dialog.dismiss()
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
        FreedomUi.styleAlertDialog(dialog, activity, ::dp, widthFraction = 0.86f)
    }

    private fun sectionLabel(value: String): View = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(MUTED)
        setPadding(dp(2), dp(13), 0, dp(3))
    }

    private fun styledSwitch() = Switch(activity).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(TEXT, Color.WHITE))
        trackTintList = ColorStateList(states, intArrayOf(LIME, 0xFFD9DED5.toInt()))
        minWidth = dp(52)
        showText = false
    }

    private fun divider() = View(activity).apply { setBackgroundColor(DIVIDER) }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun clickableBackground(radius: Int, fill: Int = Color.TRANSPARENT, stroke: Int? = null) =
        RippleDrawable(
            ColorStateList.valueOf(0x33718E22),
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(radius).toFloat()
                if (stroke != null) setStroke(dp(1), stroke)
            },
            GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(radius).toFloat()
            }
        )

    private fun spaced(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = callbacks.dp(value)

    companion object {
        private const val BACKGROUND = 0xFFF8F7F0.toInt()
        private const val TEXT = 0xFF23372B.toInt()
        private const val SECONDARY = 0xFF56665B.toInt()
        private const val MUTED = 0xFF7D887E.toInt()
        private const val DIVIDER = 0xFFDDE2D8.toInt()
        private const val LIME = 0xFFD8EF68.toInt()
        private const val LIME_DARK = 0xFFA5C331.toInt()
        private const val DARK_PANEL = 0xFF202A24.toInt()
        private const val PANEL_MUTED = 0xFFB7C0B5.toInt()
        private const val BORDER = 0xFFD8DED2.toInt()
    }
}
