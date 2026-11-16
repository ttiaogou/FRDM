package com.freedom.ui.config

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.freedom.features.aireply.ai.AiModelConfiguration
import com.freedom.features.aireply.ai.AiModelOption
import com.freedom.features.aireply.ai.AiModelServiceProvider
import com.freedom.ui.theme.FreedomUi

/** User-facing editor with provider/model choices; only the API key is required as free text. */
internal class AiModelConfigurationScreen(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    initialConfiguration: AiModelConfiguration,
    private val onBack: () -> Unit,
    private val onSave: (AiModelConfiguration) -> Result<Unit>,
    private val onClearApiKey: () -> Result<Unit>,
    private val cloudConsentGranted: () -> Boolean,
    private val onCloudConsentChanged: (Boolean) -> Unit
) {
    private var savedConfiguration = initialConfiguration.normalized()
    private var configuration = savedConfiguration
    private var selectedProvider = configuration.provider
    private var selectedModelName = configuration.modelName
    private var currentModelOptions: List<AiModelOption> = emptyList()
    private var apiKeyStatus: TextView? = null
    private var statusMessage: TextView? = null
    private var apiKeyInput: EditText? = null
    private var clearApiKeyButton: Button? = null

    fun create(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FreedomUi.BACKGROUND)
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
        root.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(58)))

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sectionTitle("模型服务"))

        val providerOptions = listOf(
            AiModelServiceProvider.OPENAI,
            AiModelServiceProvider.DEEPSEEK,
            AiModelServiceProvider.CUSTOM_OPENAI_COMPAT
        )
        val providerSpinner = addDropdown(
            content,
            label = "选择模型服务商",
            labels = providerOptions.map { it.displayName },
            selectedIndex = providerOptions.indexOf(selectedProvider).coerceAtLeast(0)
        )

        val modelSpinner = addDropdown(
            content,
            label = "选择模型",
            labels = emptyList(),
            selectedIndex = 0
        )
        val modelSpinnerLabel = content.getChildAt(content.childCount - 2)

        val customFields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (selectedProvider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) View.VISIBLE else View.GONE
        }
        val customEndpointInput = addField(
            customFields,
            label = "自定义 API 地址（HTTPS）",
            value = configuration.apiBaseUrl,
            hint = "例如 https://api.example.com/v1",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val customModelInput = addField(
            customFields,
            label = "自定义模型名称",
            value = configuration.modelName,
            hint = "填写接口服务商提供的模型 ID",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )
        content.addView(customFields)

        fun refreshModelOptions(provider: AiModelServiceProvider, preferredModel: String? = null) {
            if (provider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) {
                modelSpinner.visibility = View.GONE
                modelSpinnerLabel.visibility = View.GONE
                currentModelOptions = emptyList()
                return
            }
            modelSpinner.visibility = View.VISIBLE
            modelSpinnerLabel.visibility = View.VISIBLE
            val options = provider.modelOptions.toMutableList()
            val desiredModel = preferredModel ?: configuration.modelName
            if (desiredModel.isNotBlank() && options.none { it.id == desiredModel }) {
                options += AiModelOption(desiredModel, "已保存模型（旧配置） · $desiredModel")
            }
            currentModelOptions = options
            val adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                options.map { it.displayName }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            modelSpinner.adapter = adapter
            val selectedIndex = options.indexOfFirst { it.id == desiredModel }
                .takeIf { it >= 0 }
                ?: options.indexOfFirst { it.id == provider.defaultModelName }.coerceAtLeast(0)
            selectedModelName = options.getOrNull(selectedIndex)?.id ?: provider.defaultModelName
            modelSpinner.setSelection(selectedIndex, false)
        }

        refreshModelOptions(selectedProvider, selectedModelName)
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val nextProvider = providerOptions.getOrNull(position) ?: return
                if (nextProvider == selectedProvider) return
                val previousProvider = selectedProvider
                selectedProvider = nextProvider
                // A key belongs to the selected provider. Never reuse an OpenAI key for DeepSeek
                // (or vice versa); the user must enter the new provider's key explicitly.
                configuration = configuration.copy(
                    apiKey = savedConfiguration.apiKey
                        .takeIf { savedConfiguration.provider == nextProvider }
                        .orEmpty()
                )
                apiKeyInput?.text?.clear()
                apiKeyInput?.hint = if (configuration.apiKey.isBlank()) {
                    "粘贴服务商提供的 API Key"
                } else {
                    "留空保持已保存的 Key 不变"
                }
                apiKeyStatus?.text = apiKeyStatusText()
                clearApiKeyButton?.isEnabled = configuration.apiKey.isNotBlank()
                if (nextProvider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) {
                    if (previousProvider != AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) {
                        val hasSavedCustomConfiguration =
                            savedConfiguration.provider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT
                        customEndpointInput.setText(
                            if (hasSavedCustomConfiguration) savedConfiguration.apiBaseUrl else ""
                        )
                        customModelInput.setText(
                            if (hasSavedCustomConfiguration) savedConfiguration.modelName else ""
                        )
                    }
                    customFields.visibility = View.VISIBLE
                } else {
                    customFields.visibility = View.GONE
                    customEndpointInput.setText(nextProvider.defaultBaseUrl)
                    selectedModelName = nextProvider.defaultModelName
                    refreshModelOptions(nextProvider, nextProvider.defaultModelName)
                }
            }
        }
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedModelName = currentModelOptions.getOrNull(position)?.id ?: selectedModelName
            }
        }

        content.addView(TextView(activity).apply {
            text = "服务商和常用模型已预设，API 地址会自动配置。通常只需选择服务商、选择模型并填写 API Key。自定义兼容接口仅供已有自定义服务地址的用户使用。"
            textSize = 12f
            setTextColor(FreedomUi.MUTED)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(2), dp(10), dp(2), dp(8))
        })

        content.addView(sectionTitle("API Key"), topMargin(12))
        val apiKeyStatusView = TextView(activity).apply {
            text = apiKeyStatusText()
            textSize = 12f
            setTextColor(FreedomUi.MUTED)
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        apiKeyStatus = apiKeyStatusView
        content.addView(apiKeyStatusView)
        apiKeyInput = addField(
            content,
            label = "API Key（需要手动填写）",
            value = "",
            hint = if (configuration.apiKey.isBlank()) "粘贴服务商提供的 API Key" else "留空保持已保存的 Key 不变",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        apiKeyInput?.apply {
            setSingleLine(true)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val keyActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val clearKeyButton = Button(activity).apply {
            text = "删除已保存的 API Key"
            isEnabled = configuration.apiKey.isNotBlank()
            setOnClickListener { confirmClearApiKey() }
        }
        FreedomUi.styleSecondaryButton(clearKeyButton, dp)
        clearApiKeyButton = clearKeyButton
        keyActions.addView(clearKeyButton, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(keyActions, topMargin(4))

        content.addView(TextView(activity).apply {
            text = "聊天风格偏好通过通用自然语言指令传递，不依赖特定模型厂商。不同模型对指令的遵循效果可能有所差异。API Key 使用 Android Keystore + AES-GCM 加密保存在应用私有存储中；仅在开启云端模型处理授权后才会发送脱敏后的聊天上下文。生成内容只会填入聊天输入框，不会自动发送。"
            textSize = 12f
            setTextColor(FreedomUi.MUTED)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(2), dp(14), dp(2), dp(8))
        })
        content.addView(cloudConsentPreferenceRow(), topMargin(12))
        val statusMessageView = TextView(activity).apply {
            textSize = 12f
            setTextColor(FreedomUi.SECONDARY)
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        statusMessage = statusMessageView
        content.addView(statusMessageView)
        val saveButton = Button(activity).apply {
            text = "保存模型配置"
            setOnClickListener {
                val endpoint = if (selectedProvider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) {
                    customEndpointInput.text.toString().trim()
                } else {
                    selectedProvider.defaultBaseUrl
                }
                val model = if (selectedProvider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) {
                    customModelInput.text.toString().trim()
                } else {
                    selectedModelName
                }
                val next = runCatching {
                    configuration.copy(
                        provider = selectedProvider,
                        apiBaseUrl = endpoint,
                        modelName = model,
                        apiKey = apiKeyInput?.text?.toString()?.trim()
                            ?.takeIf { it.isNotEmpty() } ?: configuration.apiKey
                    ).normalized()
                }.getOrElse { failure ->
                    showStatus(failure.message ?: "模型配置无效。", error = true)
                    return@setOnClickListener
                }
                if (selectedProvider != AiModelServiceProvider.CUSTOM_OPENAI_COMPAT &&
                    currentModelOptions.none { it.id == next.modelName }
                ) {
                    showStatus("请选择列表中的模型。", error = true)
                    return@setOnClickListener
                }
                next.validationError()?.let { message ->
                    showStatus(message, error = true)
                    if (selectedProvider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) {
                        customEndpointInput.error = message
                    }
                    return@setOnClickListener
                }
                if (next.apiKey.isBlank()) {
                    showStatus("请填写 API Key 后再保存。", error = true)
                    apiKeyInput?.error = "API Key 不能为空"
                    return@setOnClickListener
                }
                if (next.modelName.isBlank() || next.apiBaseUrl.isBlank()) {
                    showStatus("模型配置不完整，请检查服务商和模型选择。", error = true)
                    return@setOnClickListener
                }
                onSave(next).fold(
                    onSuccess = {
                        configuration = next
                        savedConfiguration = next
                        apiKeyInput?.text?.clear()
                        apiKeyInput?.hint = "留空保持已保存的 Key 不变"
                        apiKeyStatus?.text = apiKeyStatusText()
                        clearKeyButton.isEnabled = configuration.apiKey.isNotBlank()
                        showStatus("模型配置已加密保存。", error = false)
                    },
                    onFailure = { failure ->
                        showStatus(failure.message ?: "保存失败，请检查安全存储状态。", error = true)
                    }
                )
            }
        }
        FreedomUi.stylePrimaryButton(saveButton, dp)
        content.addView(saveButton, topMargin(8))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun addDropdown(
        parent: LinearLayout,
        label: String,
        labels: List<String>,
        selectedIndex: Int
    ): Spinner {
        parent.addView(TextView(activity).apply {
            text = label
            textSize = 12f
            setTextColor(FreedomUi.SECONDARY)
            setPadding(dp(2), dp(8), dp(2), dp(5))
        })
        return Spinner(activity).apply {
            val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            this.adapter = adapter
            background = FreedomUi.roundedSurface(dp(10), Color.WHITE, FreedomUi.DIVIDER)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setSelection(selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0)), false)
            parent.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun addField(
        parent: LinearLayout,
        label: String,
        value: String,
        hint: String,
        inputType: Int
    ): EditText {
        parent.addView(TextView(activity).apply {
            text = label
            textSize = 12f
            setTextColor(FreedomUi.SECONDARY)
            setPadding(dp(2), dp(8), dp(2), dp(5))
        })
        val input = EditText(activity).apply {
            setText(value)
            this.hint = hint
            this.inputType = inputType
            textSize = 14f
            setTextColor(FreedomUi.TEXT)
            setHintTextColor(FreedomUi.MUTED)
            setSingleLine(true)
            background = FreedomUi.roundedSurface(dp(10), Color.WHITE, FreedomUi.DIVIDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        parent.addView(input, LinearLayout.LayoutParams(-1, dp(48)))
        return input
    }

    private fun buildHeader(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        addView(TextView(activity).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(FreedomUi.TEXT)
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(48), dp(54)))
        addView(TextView(activity).apply {
            text = "AI 模型配置"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(FreedomUi.TEXT)
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        addView(View(activity), LinearLayout.LayoutParams(dp(48), dp(54)))
    }

    private fun sectionTitle(label: String) = TextView(activity).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(FreedomUi.TEXT)
        setPadding(dp(2), dp(8), dp(2), dp(8))
    }

    private fun cloudConsentPreferenceRow(): View {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(sectionTitle("云端模型处理授权"))
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(2), dp(6), dp(4), dp(8))
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
        val consentStatusTitle = TextView(activity).apply {
            text = if (cloudConsentGranted()) "已授权云端模型处理" else "尚未授权云端模型处理"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(FreedomUi.TEXT)
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(consentStatusTitle)
            addView(TextView(activity).apply {
                text = "开启后，允许将本地脱敏后的聊天上下文发送给当前配置的云端模型；可随时撤销。"
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
                            "授权后，应用可以将本地脱敏后的聊天上下文发送给当前配置的云端模型服务。系统会尝试对常见手机号、邮箱和中国大陆居民身份证号进行脱敏，但无法保证识别所有敏感信息。授权保存在本机，可随时关闭。生成内容仍受现有回复校验流程约束，不会因授权而自动发送消息。"
                        )
                        .setNegativeButton("暂不授权", null)
                        .setPositiveButton("同意并授权") { _, _ ->
                            runCatching { onCloudConsentChanged(true) }
                                .onSuccess {
                                    consentStatusTitle.text = "已授权云端模型处理"
                                    consentSwitch.setOnCheckedChangeListener(null)
                                    consentSwitch.isChecked = true
                                    installConsentListener()
                                }
                                .onFailure { failure ->
                                    showStatus(failure.message ?: "保存云端授权状态失败。", error = true)
                                }
                        }
                        .create()
                    dialog.show()
                    FreedomUi.styleAlertDialog(dialog, activity, dp)
                } else {
                    runCatching { onCloudConsentChanged(false) }
                        .onSuccess { consentStatusTitle.text = "尚未授权云端模型处理" }
                        .onFailure { failure ->
                            showStatus(failure.message ?: "撤销云端授权失败。", error = true)
                            consentSwitch.setOnCheckedChangeListener(null)
                            consentSwitch.isChecked = true
                            installConsentListener()
                        }
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

    private fun confirmClearApiKey() {
        if (configuration.apiKey.isBlank()) return
        val dialog = AlertDialog.Builder(activity)
            .setTitle("删除 API Key")
            .setMessage("将从本机加密配置中删除已保存的 API Key。其他模型参数不会改变。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                onClearApiKey().fold(
                    onSuccess = {
                        configuration = configuration.copy(apiKey = "")
                        savedConfiguration = savedConfiguration.copy(apiKey = "")
                        apiKeyStatus?.text = apiKeyStatusText()
                        clearApiKeyButton?.isEnabled = false
                        apiKeyInput?.hint = "粘贴 API Key"
                        apiKeyInput?.text?.clear()
                        showStatus("已删除本机保存的 API Key。", error = false)
                    },
                    onFailure = { failure ->
                        showStatus(failure.message ?: "删除 API Key 失败。", error = true)
                    }
                )
            }
            .create()
        dialog.show()
        FreedomUi.styleAlertDialog(dialog, activity, dp)
    }

    private fun apiKeyStatusText(): String =
        if (configuration.apiKey.isBlank()) "当前状态：未保存 API Key。"
        else "当前状态：API Key 已在本机安全保存；页面不会回显密钥。"

    private fun showStatus(message: String, error: Boolean) {
        statusMessage?.apply {
            text = message
            setTextColor(if (error) FreedomUi.ERROR else FreedomUi.SECONDARY)
        }
    }

    private fun topMargin(value: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(value)
    }
}
