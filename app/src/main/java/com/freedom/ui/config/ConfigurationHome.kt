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
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
        val onImageResourceImportRequested: () -> Unit,
        val onImageResourceClearRequested: () -> Unit,
        val onCollectionExport: () -> Unit,
        val onOpenDrawer: () -> Unit,
        val onRunClick: () -> Unit
    )

    private var runButton: ImageButton? = null
    private var targetValue: TextView? = null
    private var distanceValue: TextView? = null
    private var minAgeValue: TextView? = null
    private var maxAgeValue: TextView? = null
    private var activeTimeValue: TextView? = null
    private var messageInterceptionThresholdValue: TextView? = null
    private var imageSendThresholdValue: TextView? = null
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
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(20), dp(10), dp(20), dp(8))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(buildTargetSection())
        content.addView(buildPersonalizationRow(), spaced(18))
        content.addView(messageInterceptionPreferenceRow(), spaced(4))
        content.addView(imagePreReplyPreferenceRow())
        // Only surplus viewport height is allocated here. The target and personalization
        // positions stay fixed; small screens scroll, tall screens keep filters near Run.
        content.addView(View(activity), LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(buildFilterPanel(), spaced(12))
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

        val exportEntry = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_floating_export)
            imageTintList = ColorStateList.valueOf(TEXT)
            background = clickableBackground(24)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "采集与导出"
            isFocusable = true
            setOnClickListener { callbacks.onCollectionExport() }
        }
        header.addView(configEntry, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(exportEntry, LinearLayout.LayoutParams(dp(48), dp(48)))
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

        val activeTimeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(7), dp(6), dp(9))
            background = clickableBackground(8)
            isClickable = true
            isFocusable = true
            contentDescription = "活跃时间 ${parameters.maxLastActiveMinutes} 分钟，点击编辑"
            setOnClickListener {
                showNumberEditor(
                    title = "活跃时间",
                    value = parameters.maxLastActiveMinutes,
                    suffix = "分钟",
                    validate = { value -> if (value < 0) "活跃时间不能小于 0" else null },
                    onSave = { value -> saveParameters(parameters.copy(maxLastActiveMinutes = value)) }
                )
            }
        }
        activeTimeRow.addView(TextView(activity).apply {
            text = "活跃时间"
            textSize = 13f
            setTextColor(PANEL_MUTED)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val activeTimeColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val activeTimeNumber = TextView(activity).apply {
            text = parameters.maxLastActiveMinutes.toString()
            contentDescription = "活跃时间 ${parameters.maxLastActiveMinutes} 分钟，点击编辑"
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(LIME)
            gravity = Gravity.END
        }
        activeTimeValue = activeTimeNumber
        activeTimeColumn.addView(activeTimeNumber)
        activeTimeColumn.addView(TextView(activity).apply {
            text = "分钟（0为不限）"
            textSize = 10f
            setTextColor(PANEL_MUTED)
        })
        activeTimeRow.addView(activeTimeColumn)
        panel.addView(activeTimeRow, LinearLayout.LayoutParams(-1, -2))
        panel.addView(View(activity).apply { setBackgroundColor(0xFF465149.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))

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

    private fun messageInterceptionPreferenceRow(): View {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(2), dp(6), dp(4), dp(6))
            background = replyPreferenceBackground(8)
            isClickable = true
            isFocusable = true
            setOnClickListener { showReplyLimitEditor() }
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "消息拦截"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(FreedomUi.TEXT)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        messageInterceptionThresholdValue = TextView(activity).apply {
            text = parameters.messageInterceptionThreshold.toString()
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(FreedomUi.TEXT)
            background = FreedomUi.roundedSurface(dp(9), Color.WHITE, FreedomUi.DIVIDER)
            contentDescription = "消息拦截 ${parameters.messageInterceptionThreshold}，点击编辑"
        }
        row.addView(messageInterceptionThresholdValue, LinearLayout.LayoutParams(dp(64), dp(40)))
        wrapper.addView(row)
        wrapper.addView(View(activity).apply {
            setBackgroundColor(FreedomUi.DIVIDER)
        }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrapper
    }

    private fun showReplyLimitEditor() {
        refreshTaskParameters()
        val input = FreedomUi.editorInput(activity, ::dp, parameters.messageInterceptionThreshold.toString(), InputType.TYPE_CLASS_NUMBER)
        val content = FreedomUi.editorContent(activity, ::dp)
        FreedomUi.addEditorInput(content, input, ::dp)
        FreedomUi.addEditorHint(content, "回复消息满足后执行/0不开启；留空按 0 保存", ::dp)
        val dialog = FreedomUi.createEditorDialog(activity, "消息拦截", content)
        FreedomUi.showEditorDialog(dialog, activity, ::dp, input) {
            val parsed = FreedomUi.parseEditorInteger(input.text.toString())
            if (parsed == null || parsed < 0) {
                input.error = "请输入大于或等于 0 的数字"
                return@showEditorDialog
            }
            parameters = callbacks.taskParameters()
            if (parsed > 0 && parameters.imageSendThreshold > parsed) {
                input.error = "图片发送阈值大于消息拦截阈值；拦截会先阻断后续回复"
                return@showEditorDialog
            }
            parameters = parameters.copy(messageInterceptionThreshold = parsed)
            callbacks.onTaskParametersChanged(parameters)
            messageInterceptionThresholdValue?.apply {
                text = parsed.toString()
                contentDescription = "消息拦截 $parsed，点击编辑"
            }
            dialog.dismiss()
        }
    }

    private fun imagePreReplyPreferenceRow(): View {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(2), dp(6), dp(4), dp(6))
            background = replyPreferenceBackground(8)
            isClickable = true
            isFocusable = true
            setOnClickListener { showImagePreReplyEditor() }
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "图片预回复"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(FreedomUi.TEXT)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        imageSendThresholdValue = TextView(activity).apply {
            text = parameters.imageSendThreshold.toString()
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(FreedomUi.TEXT)
            background = FreedomUi.roundedSurface(dp(9), Color.WHITE, FreedomUi.DIVIDER)
            contentDescription = "图片预回复 ${parameters.imageSendThreshold}，点击编辑"
        }
        row.addView(imageSendThresholdValue, LinearLayout.LayoutParams(dp(64), dp(40)))
        wrapper.addView(row)
        wrapper.addView(View(activity).apply {
            setBackgroundColor(FreedomUi.DIVIDER)
        }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrapper
    }

    private fun showImagePreReplyEditor() {
        refreshTaskParameters()
        val input = FreedomUi.editorInput(activity, ::dp, parameters.imageSendThreshold.toString(), InputType.TYPE_CLASS_NUMBER)
        val content = FreedomUi.editorContent(activity, ::dp)
        FreedomUi.addEditorInput(content, input, ::dp)
        FreedomUi.addEditorHint(content, "回复消息满足后发送图片 / 0 不开启；留空按 0 保存", ::dp)
        val dialog = FreedomUi.createEditorDialog(activity, "图片预回复", content)
        val save: () -> Unit = save@{
            val parsed = FreedomUi.parseEditorInteger(input.text.toString())
            if (parsed == null || parsed < 0) {
                input.error = "请输入大于或等于 0 的数字"
                return@save
            }
            val latest = callbacks.taskParameters()
            val validationError = latest.imageSendThresholdValidationError(parsed)
            if (validationError != null) {
                input.error = validationError
                return@save
            }
            parameters = latest.copy(imageSendThreshold = parsed)
            callbacks.onTaskParametersChanged(parameters)
            imageSendThresholdValue?.apply {
                text = parsed.toString()
                contentDescription = "图片预回复 $parsed，点击编辑"
            }
            dialog.dismiss()
        }
        content.addView(TextView(activity).apply {
            text = "发送图片"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(FreedomUi.TEXT)
            setPadding(dp(2), dp(18), 0, dp(6))
        })
        content.addView(Button(activity).apply {
            text = "导入图片（已导入 ${parameters.imageResourceUris.size} 张）"
            setOnClickListener {
                dialog.dismiss()
                callbacks.onImageResourceImportRequested()
            }
        })
        if (parameters.imageResourceUris.isNotEmpty()) {
            content.addView(Button(activity).apply {
                text = "清空图片池"
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("清空图片池")
                        .setMessage("将移除 Freedom 专用相册中的已导入副本，不删除原始图片。确定继续？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清空") { _, _ ->
                            dialog.dismiss()
                            callbacks.onImageResourceClearRequested()
                        }
                        .show()
                }
            })
        }
        FreedomUi.showEditorDialog(dialog, activity, ::dp, input, save)
    }

    private fun replyPreferenceBackground(radius: Int) = android.graphics.drawable.RippleDrawable(
        ColorStateList.valueOf(0x26718E22),
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(radius).toFloat()
        },
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(radius).toFloat()
        }
    )

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
        renderParameters(updated)
    }

    fun refreshTaskParameters() {
        parameters = callbacks.taskParameters()
        renderParameters(parameters)
    }

    private fun renderParameters(updated: TaskLaunchParameters) {
        messageInterceptionThresholdValue?.apply {
            text = updated.messageInterceptionThreshold.toString()
            contentDescription = "消息拦截 ${updated.messageInterceptionThreshold}，点击编辑"
        }
        imageSendThresholdValue?.apply {
            text = updated.imageSendThreshold.toString()
            contentDescription = "图片预回复 ${updated.imageSendThreshold}，点击编辑"
        }
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
        activeTimeValue?.apply {
            text = updated.maxLastActiveMinutes.toString()
            contentDescription = "活跃时间 ${updated.maxLastActiveMinutes} 分钟，点击编辑"
        }
    }

    private fun showNumberEditor(
        title: String,
        value: Int,
        suffix: String,
        validate: (Int) -> String?,
        onSave: (Int) -> Unit
    ) {
        val input = FreedomUi.editorInput(activity, ::dp, value.toString(), InputType.TYPE_CLASS_NUMBER, "输入$suffix")
        val content = FreedomUi.editorContent(activity, ::dp)
        FreedomUi.addEditorInput(content, input, ::dp)
        FreedomUi.addEditorHint(content, "输入 0 表示不限（如该项目适用）；留空按 0 保存", ::dp)
        val dialog = FreedomUi.createEditorDialog(activity, title, content)
        FreedomUi.showEditorDialog(dialog, activity, ::dp, input) {
            val parsed = FreedomUi.parseEditorInteger(input.text.toString())
            if (parsed == null) {
                input.error = "请输入有效数字"
                return@showEditorDialog
            }
            val validationError = validate(parsed)
            if (validationError != null) {
                input.error = validationError
                return@showEditorDialog
            }
            onSave(parsed)
            dialog.dismiss()
        }
    }

    private fun sectionLabel(value: String): View = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(MUTED)
        setPadding(dp(2), dp(13), 0, dp(3))
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
