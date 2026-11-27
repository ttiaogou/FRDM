package com.freedom.ui.config

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.freedom.application.TaskLaunchParameters
import com.freedom.features.aireply.ai.AiReplyPersonalization
import com.freedom.features.aireply.ai.EmotionExpression
import com.freedom.features.aireply.ai.HumorLevel
import com.freedom.features.aireply.ai.InteractionLevel
import com.freedom.features.aireply.ai.LanguageStyle
import com.freedom.features.aireply.ai.PersonalityStyle
import com.freedom.features.aireply.ai.ReplyLength
import com.freedom.ui.theme.FreedomUi

/** Provider-neutral chat style settings; task-level reply controls remain unchanged. */
internal class PersonalizationScreen(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val onBack: () -> Unit,
    private val taskParameters: () -> TaskLaunchParameters,
    private val onTaskParametersChanged: (TaskLaunchParameters) -> Unit,
    private val onImageResourceImportRequested: () -> Unit,
    private val onImageResourceClearRequested: () -> Unit,
    private val loadAiReplyPersonalization: () -> AiReplyPersonalization,
    private val onAiReplyPersonalizationChanged: (AiReplyPersonalization) -> Unit
) {
    private var parameters = taskParameters()
    private var personalization = loadAiReplyPersonalization()
    private var messageInterceptionThresholdValue: TextView? = null
    private var imageSendThresholdValue: TextView? = null
    private var customInstructionsInput: EditText? = null
    private var personalizationStatus: TextView? = null

    fun create(): View {
        parameters = taskParameters()
        personalization = loadAiReplyPersonalization()
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
        root.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(54)))

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setPadding(dp(20), dp(16), dp(20), dp(28))
            clipToPadding = false
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }

        content.addView(dropdownPreferenceRow(
            label = "性格风格",
            choices = enumValues<PersonalityStyle>(),
            selected = personalization.personalityStyle,
            displayName = { it.displayName },
            onSelected = { selected -> updatePersonalization { it.copy(personalityStyle = selected) } }
        ))
        content.addView(dropdownPreferenceRow(
            label = "语言风格",
            choices = enumValues<LanguageStyle>(),
            selected = personalization.languageStyle,
            displayName = { it.displayName },
            onSelected = { selected -> updatePersonalization { it.copy(languageStyle = selected) } }
        ))
        content.addView(dropdownPreferenceRow(
            label = "回复长度",
            choices = enumValues<ReplyLength>(),
            selected = personalization.replyLength,
            displayName = { it.displayName },
            onSelected = { selected -> updatePersonalization { it.copy(replyLength = selected) } }
        ))
        content.addView(dropdownPreferenceRow(
            label = "情绪表达程度",
            choices = enumValues<EmotionExpression>(),
            selected = personalization.emotionExpression,
            displayName = { it.displayName },
            onSelected = { selected -> updatePersonalization { it.copy(emotionExpression = selected) } }
        ))
        content.addView(dropdownPreferenceRow(
            label = "幽默程度",
            choices = enumValues<HumorLevel>(),
            selected = personalization.humorLevel,
            displayName = { it.displayName },
            onSelected = { selected -> updatePersonalization { it.copy(humorLevel = selected) } }
        ))
        content.addView(dropdownPreferenceRow(
            label = "主动互动程度",
            choices = enumValues<InteractionLevel>(),
            selected = personalization.interactionLevel,
            displayName = { it.displayName },
            onSelected = { selected -> updatePersonalization { it.copy(interactionLevel = selected) } }
        ))
        content.addView(sectionHeader("高级自定义"))
        content.addView(TextView(activity).apply {
            text = "还有什么特别的聊天习惯需要告诉 AI？"
            textSize = 13f
            setTextColor(FreedomUi.SECONDARY)
            setPadding(dp(2), dp(4), dp(2), dp(8))
        })
        val customInput = EditText(activity).apply {
            setText(personalization.customInstructions)
            hint = "例如：不要频繁使用表情符号。\n不要每次回答都反问我。\n不要刻意讨好我。\n可以偶尔开玩笑。\n不要使用太官方的语气。"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            maxLines = 7
            filters = arrayOf(InputFilter.LengthFilter(AiReplyPersonalization.MAX_CUSTOM_INSTRUCTION_LENGTH))
            textSize = 14f
            setTextColor(FreedomUi.TEXT)
            setHintTextColor(FreedomUi.MUTED)
            background = FreedomUi.roundedSurface(dp(10), Color.WHITE, FreedomUi.DIVIDER)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isSaveEnabled = false
        }
        customInstructionsInput = customInput
        content.addView(customInput, LinearLayout.LayoutParams(-1, dp(132)))
        val status = TextView(activity).apply {
            textSize = 11f
            setTextColor(FreedomUi.SECONDARY)
            setPadding(dp(2), dp(6), dp(2), dp(4))
        }
        personalizationStatus = status
        content.addView(status)
        val saveCustomButton = Button(activity).apply {
            text = "保存个性化设置"
            setOnClickListener {
                updatePersonalization { it.copy(customInstructions = customInput.text.toString()) }
            }
        }
        FreedomUi.stylePrimaryButton(saveCustomButton, dp)
        content.addView(saveCustomButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(6)
        })

        content.addView(sectionHeader("回复行为设置"), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })
        content.addView(messageInterceptionPreferenceRow(), LinearLayout.LayoutParams(-1, -2))
        content.addView(imagePreReplyPreferenceRow(), LinearLayout.LayoutParams(-1, -2))

        content.addView(sectionHeader("本地记录说明"), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })
        content.addView(historyRetentionPolicyRow(), LinearLayout.LayoutParams(-1, -2))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildHeader(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        setBackgroundColor(0xFFF8F7F0.toInt())
        addView(TextView(activity).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(FreedomUi.TEXT)
            contentDescription = "返回配置"
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(48), dp(54)))
        addView(TextView(activity).apply {
            text = "个性化"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(FreedomUi.TEXT)
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        addView(View(activity), LinearLayout.LayoutParams(dp(48), dp(54)))
    }

    private fun sectionHeader(title: String): View = TextView(activity).apply {
        text = title
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(FreedomUi.SECONDARY)
        setPadding(dp(2), dp(12), dp(2), dp(6))
    }

    private fun <T> dropdownPreferenceRow(
        label: String,
        choices: Array<T>,
        selected: T,
        displayName: (T) -> String,
        onSelected: (T) -> Unit
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(8))
        }
        row.addView(TextView(activity).apply {
            text = label
            textSize = 13f
            setTextColor(FreedomUi.TEXT)
            setPadding(dp(2), dp(2), dp(2), dp(4))
        })
        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            choices.map(displayName)
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val spinner = Spinner(activity, Spinner.MODE_DROPDOWN).apply {
            this.adapter = adapter
            setSelection(choices.indexOf(selected).coerceAtLeast(0))
            background = FreedomUi.roundedSurface(dp(10), Color.WHITE, FreedomUi.DIVIDER)
            contentDescription = "$label，下拉选择"
            minimumHeight = dp(48)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    choices.getOrNull(position)?.let(onSelected)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        row.addView(spinner, LinearLayout.LayoutParams(-1, dp(48)))
        row.addView(View(activity).apply {
            setBackgroundColor(FreedomUi.DIVIDER)
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8) })
        return row
    }

    private fun updatePersonalization(update: (AiReplyPersonalization) -> AiReplyPersonalization) {
        val updated = update(personalization).normalized()
        updated.validationError()?.let { message ->
            personalizationStatus?.apply {
                text = message
                setTextColor(FreedomUi.ERROR)
            }
            return
        }
        runCatching { onAiReplyPersonalizationChanged(updated) }
            .onSuccess {
                personalization = updated
                personalizationStatus?.apply {
                    text = "个性化设置已保存。"
                    setTextColor(FreedomUi.SECONDARY)
                }
            }
            .onFailure { failure ->
                personalizationStatus?.apply {
                    text = failure.message ?: "保存个性化设置失败。"
                    setTextColor(FreedomUi.ERROR)
                }
            }
    }

    private fun messageInterceptionPreferenceRow(): View {
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
        val input = FreedomUi.editorInput(activity, dp, parameters.messageInterceptionThreshold.toString(), InputType.TYPE_CLASS_NUMBER)
        val content = FreedomUi.editorContent(activity, dp)
        FreedomUi.addEditorInput(content, input, dp)
        FreedomUi.addEditorHint(content, "回复消息满足后执行/0不开启；留空按 0 保存", dp)
        val dialog = FreedomUi.createEditorDialog(activity, "消息拦截", content)
        FreedomUi.showEditorDialog(dialog, activity, dp, input) {
            val parsed = FreedomUi.parseEditorInteger(input.text.toString())
            if (parsed == null || parsed < 0) {
                input.error = "请输入大于或等于 0 的数字"
                return@showEditorDialog
            }
            if (parsed > 0 && parameters.imageSendThreshold > parsed) {
                input.error = "图片发送阈值大于消息拦截阈值；拦截会先阻断后续回复"
                return@showEditorDialog
            }
            parameters = parameters.copy(messageInterceptionThreshold = parsed)
            onTaskParametersChanged(parameters)
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
            minimumHeight = dp(68)
            setPadding(dp(2), dp(8), dp(4), dp(8))
            background = clickableBackground(8)
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
        val input = FreedomUi.editorInput(activity, dp, parameters.imageSendThreshold.toString(), InputType.TYPE_CLASS_NUMBER)
        val content = FreedomUi.editorContent(activity, dp)
        FreedomUi.addEditorInput(content, input, dp)
        FreedomUi.addEditorHint(content, "回复消息满足后发送图片 / 0 不开启；留空按 0 保存", dp)
        val dialog = FreedomUi.createEditorDialog(activity, "图片预回复", content)
        val save: () -> Unit = save@{
            val parsed = FreedomUi.parseEditorInteger(input.text.toString())
            if (parsed == null || parsed < 0) {
                input.error = "请输入大于或等于 0 的数字"
                return@save
            }
            val latest = taskParameters()
            val validationError = latest.imageSendThresholdValidationError(parsed)
            if (validationError != null) {
                input.error = validationError
                return@save
            }
            parameters = latest.copy(imageSendThreshold = parsed)
            onTaskParametersChanged(parameters)
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
                onImageResourceImportRequested()
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
                            onImageResourceClearRequested()
                        }
                        .show()
                }
            })
        }
        FreedomUi.showEditorDialog(dialog, activity, dp, input, save)
    }

    private fun historyRetentionPolicyRow(): View {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(4), dp(12))
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

    private fun clickableBackground(radius: Int) = android.graphics.drawable.RippleDrawable(
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
}
