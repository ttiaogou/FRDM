package com.freedom.ui.config

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.freedom.application.ports.GestureCalibrationPresentationPort
import com.freedom.application.ports.MonitorControlRepository

/** Configuration drawer. Permission-backed switches always reflect Android's real permission state. */
internal class ConfigPanel(
    private val activity: Activity,
    private val repository: MonitorControlRepository,
    private val gestureCalibration: GestureCalibrationPresentationPort,
    private val callbacks: Callbacks
) {
    data class Callbacks(
        val isAccessibilityEnabled: () -> Boolean,
        val dp: (Int) -> Int,
        val roundedBackground: (Int, Float) -> GradientDrawable,
        val timestamp: () -> String,
        val isIgnoringBatteryOptimizations: () -> Boolean,
        val openBatteryOptimizationSettings: () -> Unit,
        val requestAccessibilityPermission: () -> Unit,
        val requestOverlayPermission: () -> Unit,
        val isNotificationEnabled: () -> Boolean,
        val requestNotificationPermission: () -> Unit,
        val onOcrManagement: () -> Unit,
        val onAiModelConfiguration: () -> Unit,
        val showGestureCalibrationDialog: () -> Unit,
        val hideOverlay: () -> Unit
    )

    fun show() {
        val dialog = Dialog(activity)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                setStroke(dp(1), DIVIDER)
                setCornerRadii(floatArrayOf(
                    0f, 0f, dp(22).toFloat(), dp(22).toFloat(),
                    dp(22).toFloat(), dp(22).toFloat(), 0f, 0f
                ))
            }
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(22), dp(18), dp(18))
        }
        header.addView(View(activity).apply {
            setBackgroundColor(ACCENT)
        }, LinearLayout.LayoutParams(dp(3), dp(30)).apply { rightMargin = dp(12) })
        header.addView(TextView(activity).apply {
            text = "配置菜单"
            textSize = 21f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(TEXT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(activity).apply {
            text = "×"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            contentDescription = "关闭配置菜单"
            background = clickableBackground(21)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        panel.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply {
            leftMargin = dp(22)
            rightMargin = dp(22)
        })

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(28))
        }

        addSectionTitle(body, "配置")
        val floatingEnabled = repository.isFloatingLogEnabled()
        addSwitchRow(body, "悬浮按钮", floatingEnabled, contentDescription = "浮动按钮") { enabled ->
            if (enabled && !Settings.canDrawOverlays(activity)) {
                dialog.dismiss()
                callbacks.requestOverlayPermission()
                false
            } else {
                runCatching {
                    repository.setFloatingLogEnabled(enabled)
                    repository.setOverlayEnabled(enabled)
                    callbacks.hideOverlay()
                    if (enabled) {
                        Toast.makeText(activity, "悬浮按钮已开启；切换到目标应用后显示", Toast.LENGTH_SHORT).show()
                    }
                    true
                }.onFailure { error ->
                    repository.addLog(
                        callbacks.timestamp() + "  切换悬浮按钮失败：${error.javaClass.simpleName}: " +
                            (error.message ?: "无详细信息")
                    )
                    Toast.makeText(activity, "切换悬浮按钮失败，请查看运行记录", Toast.LENGTH_LONG).show()
                }.getOrDefault(false)
            }
        }
        addDivider(body)
        addActionRow(
            body,
            "AI 模型配置",
            "管理模型 API、密钥与云端处理授权",
            "管理"
        ) {
            dialog.dismiss()
            callbacks.onAiModelConfiguration()
        }
        addDivider(body)
        addActionRow(
            body,
            "自动采样路径偏移频率",
            gestureCalibration.configurationSubtitle(),
            if (gestureCalibration.isCalibrated()) "查看" else "采样"
        ) {
            dialog.dismiss()
            callbacks.showGestureCalibrationDialog()
        }
        addDivider(body)
        addActionRow(
            body,
            "OCR插件",
            "管理已安装及可获取的 OCR 插件",
            "管理"
        ) {
            dialog.dismiss()
            callbacks.onOcrManagement()
        }

        addSectionTitle(body, "权限", top = 24)
        addStatusRow(body, "Root授权", "暂未接入", enabled = false)
        addDivider(body)
        addPermissionSwitchRow(
            body,
            "无障碍服务",
            checked = callbacks.isAccessibilityEnabled()
        ) {
            dialog.dismiss()
            callbacks.requestAccessibilityPermission()
        }
        addDivider(body)
        val batteryOptimizationIgnored = callbacks.isIgnoringBatteryOptimizations()
        addPermissionSwitchRow(
            body,
            "忽略电池优化",
            checked = batteryOptimizationIgnored,
            contentDescription = if (batteryOptimizationIgnored) {
                "忽略电池优化已开启"
            } else {
                "忽略电池优化未开启，点击打开系统授权"
            }
        ) {
            if (batteryOptimizationIgnored) {
                Toast.makeText(activity, "已忽略电池优化", Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                callbacks.openBatteryOptimizationSettings()
            }
        }
        addDivider(body)
        val notificationEnabled = callbacks.isNotificationEnabled()
        addPermissionSwitchRow(
            body,
            "通知发布",
            checked = notificationEnabled,
            contentDescription = if (notificationEnabled) {
                "通知发布已开启"
            } else {
                "通知发布未开启，点击申请系统通知权限"
            }
        ) {
            dialog.dismiss()
            callbacks.requestNotificationPermission()
        }

        scroll.addView(body)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.gravity = Gravity.START or Gravity.TOP
            params.width = (activity.resources.displayMetrics.widthPixels * 0.86f).toInt()
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.dimAmount = 0.24f
            attributes = params
            setLayout(params.width, params.height)
        }
        panel.post {
            panel.translationX = -panel.width.toFloat()
            panel.animate().translationX(0f).setDuration(220L).start()
        }
    }

    private fun addSectionTitle(parent: LinearLayout, title: String, top: Int = 0) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(8))
        }
        row.addView(View(activity).apply {
            setBackgroundColor(ACCENT)
        }, LinearLayout.LayoutParams(dp(3), dp(21)).apply { rightMargin = dp(10) })
        row.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT_DARK)
        })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    private fun addSwitchRow(parent: LinearLayout, title: String, checked: Boolean, contentDescription: String? = null, onChanged: (Boolean) -> Boolean) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
        }
        row.addView(label(title), LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = styledSwitch().apply {
            isChecked = checked
            this.contentDescription = contentDescription ?: title
        }
        val listener = object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(button: CompoundButton, value: Boolean) {
                if (!onChanged(value)) {
                    button.setOnCheckedChangeListener(null)
                    button.isChecked = !value
                    button.setOnCheckedChangeListener(this)
                }
            }
        }
        toggle.setOnCheckedChangeListener(listener)
        row.addView(toggle)
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    /** A read-only Switch whose value mirrors the system authorization; the whole row opens Settings. */
    private fun addPermissionSwitchRow(
        parent: LinearLayout,
        title: String,
        checked: Boolean,
        contentDescription: String = if (checked) "$title 已授权" else "$title 未授权，点击打开系统设置",
        onClick: () -> Unit
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            background = clickableBackground(8)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(label(title), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(styledSwitch().apply {
            isChecked = checked
            isClickable = false
            isFocusable = false
            this.contentDescription = contentDescription
        })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addStatusRow(parent: LinearLayout, title: String, status: String, enabled: Boolean) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        row.addView(label(title), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(activity).apply {
            text = status
            textSize = 12f
            setTextColor(if (enabled) ACCENT_DARK else MUTED)
            gravity = Gravity.CENTER
        })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addActionRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        actionText: String,
        action: () -> Unit
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = if (subtitle == title) dp(58) else dp(66)
            background = clickableBackground(8)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title))
            if (subtitle != title) {
                addView(detail(subtitle), LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(4)
                })
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = actionText
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ACCENT_DARK)
                setPadding(dp(8), 0, dp(2), 0)
            })
            addView(TextView(activity).apply {
                text = "›"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(MUTED)
                setPadding(dp(6), 0, dp(2), 0)
            })
        })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun divider() = View(activity).apply { setBackgroundColor(DIVIDER) }

    private fun clickableBackground(radius: Int) = RippleDrawable(
        ColorStateList.valueOf(0x26718E22),
        GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(radius).toFloat()
        },
        GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(radius).toFloat()
        }
    )

    private fun label(value: String) = TextView(activity).apply {
        text = value
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(TEXT)
    }

    private fun detail(value: String) = TextView(activity).apply {
        text = value
        textSize = 11f
        setTextColor(MUTED)
        maxLines = 2
    }

    private fun styledSwitch() = Switch(activity).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(TEXT, Color.WHITE))
        trackTintList = ColorStateList(states, intArrayOf(LIME, 0xFFD9DED5.toInt()))
        minWidth = dp(52)
        showText = false
    }

    private fun dp(value: Int) = callbacks.dp(value)

    companion object {
        private const val BACKGROUND = 0xFFF8F7F0.toInt()
        private const val TEXT = 0xFF23372B.toInt()
        private const val MUTED = 0xFF7D887E.toInt()
        private const val DIVIDER = 0xFFDDE2D8.toInt()
        private const val ACCENT = 0xFFA5C331.toInt()
        private const val ACCENT_DARK = 0xFF718E22.toInt()
        private const val LIME = 0xFFD8EF68.toInt()
    }
}
