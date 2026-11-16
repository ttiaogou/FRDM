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

/** Full-screen personalization UI; kept visually aligned with the launcher palette. */
internal class PersonalizationScreen(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val onBack: () -> Unit,
    private val taskParameters: () -> TaskLaunchParameters,
    private val onTaskParametersChanged: (TaskLaunchParameters) -> Unit,
    private val cloudConsentGranted: () -> Boolean,
    private val onCloudConsentChanged: (Boolean) -> Unit,
    private val onModelConfiguration: () -> Unit
) {
    private var parameters = taskParameters()
    private var replyLimitValue: TextView? = null

    fun create(): View {
        parameters = taskParameters()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF8F7F0.toInt())
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
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundColor(0xFFF8F7F0.toInt())
        }
        header.addView(TextView(activity).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(0xFF23372B.toInt())
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(48), dp(54)))
        header.addView(TextView(activity).apply {
            text = "个性化"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF23372B.toInt())
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        header.addView(View(activity), LinearLayout.LayoutParams(dp(48), dp(54)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(54)))

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setPadding(dp(20), dp(18), dp(20), dp(28))
            clipToPadding = false
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        content.addView(preferenceRow("基本风格和语调", "高效务实"), LinearLayout.LayoutParams(-1, -2))
        content.addView(preferenceRow("亲切程度", "默认"), LinearLayout.LayoutParams(-1, -2))
        content.addView(preferenceRow("热情程度", "默认"), LinearLayout.LayoutParams(-1, -2))
        content.addView(preferenceRow("表情符号", "默认"), LinearLayout.LayoutParams(-1, -2))
        content.addView(replyLimitPreferenceRow(), LinearLayout.LayoutParams(-1, -2))
        content.addView(imagePreReplyPreferenceRow(), LinearLayout.LayoutParams(-1, -2))
        content.addView(modelConfigurationPreferenceRow(), LinearLayout.LayoutParams(-1, -2))
        content.addView(cloudConsentPreferenceRow(), LinearLayout.LayoutParams(-1, -2))
        content.addView(historyRetentionPolicyRow(), LinearLayout.LayoutParams(-1, -2))

        val custom = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(18), dp(2), dp(16))
        }
        custom.addView(TextView(activity).apply {
            text = "自定义指令"
            textSize = 13f
            setTextColor(0xFF56665B.toInt())
        })
        custom.addView(TextView(activity).apply {
            text = "例如：回答简洁一些"
            textSize = 14f
            setTextColor(0xFF7D887E.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.WHITE, dp(10))
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        content.addView(custom, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun replyLimitPreferenceRow(): View {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(2), dp(8), dp(4), dp(8))
            background = clickableBackground(8)
            isClickable = true
            isFocusable = true
            setOnClickListener { showReplyLimitEditor() }
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "回复数量限制"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(FreedomUi.TEXT)
            })
            addView(TextView(activity).apply {
                text = "达到上限后触发 · 0为不限"
                textSize = 11f
                setTextColor(FreedomUi.MUTED)
                setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        replyLimitValue = TextView(activity).apply {
            text = parameters.replyLimit.toString()
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(FreedomUi.TEXT)
            background = FreedomUi.roundedSurface(dp(9), Color.WHITE, FreedomUi.DIVIDER)
            contentDescription = "回复数量限制 ${parameters.replyLimit} 条，点击编辑"
        }
        row.addView(replyLimitValue, LinearLayout.LayoutParams(dp(64), dp(40)))
        row.addView(TextView(activity).apply {
            text = "条"
            textSize = 12f
            setTextColor(FreedomUi.MUTED)
            setPadding(dp(6), 0, 0, 0)
        })
        wrapper.addView(row)
        wrapper.addView(View(activity).apply { setBackgroundColor(FreedomUi.DIVIDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrapper
    }

    private fun showReplyLimitEditor() {
        val input = EditText(activity).apply {
            setText(parameters.replyLimit.toString())
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            textSize = 18f
            setTextColor(FreedomUi.TEXT)
            background = FreedomUi.roundedSurface(dp(10), Color.WHITE, FreedomUi.DIVIDER)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
            addView(TextView(activity).apply {
                text = "输入 0 表示不限"
                textSize = 11f
                setTextColor(FreedomUi.MUTED)
                setPadding(dp(2), dp(8), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("回复数量限制")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = input.text.toString().trim().toIntOrNull()
                if (parsed == null || parsed < 0) {
                    input.error = "请输入大于或等于 0 的数字"
                    return@setOnClickListener
                }
                parameters = parameters.copy(replyLimit = parsed)
                onTaskParametersChanged(parameters)
                replyLimitValue?.apply {
                    text = parsed.toString()
                    contentDescription = "回复数量限制 $parsed 条，点击编辑"
                }
                dialog.dismiss()
            }
        }
        dialog.show()
        FreedomUi.styleAlertDialog(dialog, activity, dp)
    }

    private fun imagePreReplyPreferenceRow(): View {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(2), dp(8), dp(4), dp(8))
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "图片预回复"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(FreedomUi.TEXT)
            })
            addView(TextView(activity).apply {
                text = "保存偏好；图片识别与自动回复流程接入后生效"
                textSize = 11f
                setTextColor(FreedomUi.MUTED)
                setPadding(0, dp(4), 0, 0)
                maxLines = 2
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Switch(activity).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(FreedomUi.TEXT, Color.WHITE))
            trackTintList = ColorStateList(states, intArrayOf(FreedomUi.LIME, 0xFFD9DED5.toInt()))
            minWidth = dp(52)
            showText = false
            isChecked = parameters.imagePreReplyEnabled
            contentDescription = "图片预回复偏好；执行链路尚未接入"
            setOnCheckedChangeListener { _, checked ->
                parameters = parameters.copy(imagePreReplyEnabled = checked)
                onTaskParametersChanged(parameters)
            }
        })
        wrapper.addView(row)
        wrapper.addView(View(activity).apply { setBackgroundColor(FreedomUi.DIVIDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrapper
    }


    private fun modelConfigurationPreferenceRow(): View {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(2), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onModelConfiguration() }
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "AI 模型配置"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(FreedomUi.TEXT)
            })
            addView(TextView(activity).apply {
                text = "设置 API 地址、模型名称、生成参数和 API Key"
                textSize = 11f
                setTextColor(FreedomUi.MUTED)
                setPadding(0, dp(4), 0, 0)
                maxLines = 2
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(activity).apply {
            text = "›"
            textSize = 23f
            setTextColor(FreedomUi.MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(4), 0)
        })
        wrapper.addView(row)
        wrapper.addView(View(activity).apply {
            setBackgroundColor(FreedomUi.DIVIDER)
        }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrapper
    }

    private fun cloudConsentPreferenceRow(): View {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(2), dp(8), dp(4), dp(8))
        }
        val consentSwitch = Switch(activity).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(FreedomUi.TEXT, Color.WHITE))
            trackTintList = ColorStateList(states, intArrayOf(FreedomUi.LIME, 0xFFD9DED5.toInt()))
            minWidth = dp(52)
            showText = false
            isChecked = cloudConsentGranted()
            contentDescription = "云端模型处理授权"
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "云端模型处理授权"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(FreedomUi.TEXT)
            })
            addView(TextView(activity).apply {
                text = "仅在明确授权后，允许将本地脱敏后的聊天上下文发送给模型服务；可随时撤销"
                textSize = 11f
                setTextColor(FreedomUi.MUTED)
                setPadding(0, dp(4), 0, 0)
                maxLines = 3
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fun installConsentListener() {
            consentSwitch.setOnCheckedChangeListener { button, checked ->
                if (checked) {
                    button.setOnCheckedChangeListener(null)
                    button.isChecked = false
                    installConsentListener()
                    val dialog = AlertDialog.Builder(activity)
                        .setTitle("授权云端模型处理")
                        .setMessage(
                            "授权后，未来接入的云端模型可接收本地会话上下文。应用会先对常见手机号、邮箱和中国大陆居民身份证号进行脱敏，但自动识别不能保证覆盖所有敏感信息。授权保存在本机，可随时关闭。当前版本尚未连接实际 AI 云端接口，开启授权本身不会发送聊天数据。"
                        )
                        .setNegativeButton("暂不授权", null)
                        .setPositiveButton("同意并授权") { _, _ ->
                            onCloudConsentChanged(true)
                            consentSwitch.setOnCheckedChangeListener(null)
                            consentSwitch.isChecked = true
                            installConsentListener()
                        }
                        .create()
                    dialog.show()
                    FreedomUi.styleAlertDialog(dialog, activity, dp)
                } else {
                    onCloudConsentChanged(false)
                }
            }
        }
        installConsentListener()
        row.addView(consentSwitch)
        wrapper.addView(row)
        wrapper.addView(View(activity).apply {
            setBackgroundColor(FreedomUi.DIVIDER)
        }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrapper
    }

    private fun historyRetentionPolicyRow(): View {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(12), dp(4), dp(12))
            addView(TextView(activity).apply {
                text = "本地聊天历史保留"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(FreedomUi.TEXT)
            })
            addView(TextView(activity).apply {
                text = "会话记录在本机加密保存，自首次记录起保留 30 天，到期后在下次访问存储时自动清理；不影响陌陌内的原始聊天记录。"
                textSize = 11f
                setTextColor(FreedomUi.MUTED)
                setPadding(0, dp(4), 0, 0)
                maxLines = 4
            })
        })
        wrapper.addView(View(activity).apply {
            setBackgroundColor(FreedomUi.DIVIDER)
        }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrapper
    }

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

    private fun preferenceRow(label: String, value: String): View {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(2), dp(8), dp(4), dp(8))
        }
        row.addView(TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(0xFF23372B.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(activity).apply {
            text = value
            textSize = 15f
            setTextColor(0xFF7D887E.toInt())
        })
        row.addView(TextView(activity).apply {
            text = "›"
            textSize = 21f
            setTextColor(0xFF7D887E.toInt())
            setPadding(dp(8), 0, dp(4), 0)
        })
        wrapper.addView(row, LinearLayout.LayoutParams(-1, -2))
        wrapper.addView(View(activity).apply {
            setBackgroundColor(0xFFDDE2D8.toInt())
        }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrapper
    }

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }
}

/** Keeps drawable setup terse while avoiding a theme-dependent default ImageButton background. */
private object ColorDrawableCompat {
    fun transparent() = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
}
