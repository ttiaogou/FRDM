package com.freedom.ui.config

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.freedom.features.aireply.ai.AiModelConfiguration
import com.freedom.features.aireply.ai.AiModelOption
import com.freedom.features.aireply.ai.AiModelServiceProvider
import com.freedom.ui.theme.FreedomUi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader

/** 面向用户的模型配置界面，提供服务商与模型选项，并支持 API Key 文本输入。 */
internal class AiModelConfigurationScreen(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    initialConfiguration: AiModelConfiguration,
    private val onBack: () -> Unit,
    private val onSave: (AiModelConfiguration) -> Result<Unit>,
    private val onCloudConsentChanged: (Boolean) -> Unit
) {
    private var savedConfiguration = initialConfiguration.normalized()
    private var configuration = savedConfiguration
    private var selectedProvider = configuration.provider
    private var selectedModelName = configuration.modelName
    private var currentModelOptions: List<AiModelOption> = emptyList()
    private var apiKeyStatus: TextView? = null
    private var statusMessage: TextView? = null
    private var apiKeyValue: TextView? = null
    private var apiAddressValue: TextView? = null
    private var modelNameValue: TextView? = null
    private var customEndpointInput: EditText? = null
    private var customModelInput: EditText? = null

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
        modelSpinner.visibility = View.GONE
        modelSpinnerLabel.visibility = View.GONE

        fun clickableValue(label: String, value: String, description: String, action: () -> Unit): TextView {
            content.addView(TextView(activity).apply {
                text = label
                textSize = 12f
                setTextColor(FreedomUi.SECONDARY)
                setPadding(dp(2), dp(10), dp(2), dp(5))
            })
            return TextView(activity).apply {
                text = value.ifBlank { "未设置" }
                textSize = 14f
                setTextColor(FreedomUi.TEXT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = FreedomUi.roundedSurface(dp(10), Color.WHITE, FreedomUi.DIVIDER)
                isClickable = true
                isFocusable = true
                contentDescription = description
                setOnClickListener { action() }
                content.addView(this, LinearLayout.LayoutParams(-1, dp(52)))
            }
        }

        apiAddressValue = clickableValue("API 地址", configuration.apiBaseUrl, "API 地址，点击编辑") {
            showApiAddressEditor()
        }
        modelNameValue = clickableValue("模型名称", configuration.modelName, "模型名称，点击编辑") {
            showModelEditor()
        }

        val customFields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        customEndpointInput = addField(
            customFields,
            label = "自定义 API 地址（HTTPS）",
            value = configuration.apiBaseUrl,
            hint = "例如 https://api.example.com/v1",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        customModelInput = addField(
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
                // API Key 归属于当前选中的服务商，不得将 OpenAI 密钥用于 DeepSeek，
                // 反之亦然；用户必须为新的服务商明确输入相应密钥。
                configuration = configuration.copy(
                    apiKey = savedConfiguration.apiKey
                        .takeIf { savedConfiguration.provider == nextProvider }
                        .orEmpty()
                )
                apiKeyValue?.text = if (configuration.apiKey.isBlank()) "未设置" else "已保存 · 点击编辑"
                apiKeyStatus?.text = apiKeyStatusText()
                if (nextProvider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) {
                    if (previousProvider != AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) {
                        val hasSavedCustomConfiguration =
                            savedConfiguration.provider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT
                        customEndpointInput?.setText(
                            if (hasSavedCustomConfiguration) savedConfiguration.apiBaseUrl else ""
                        )
                        customModelInput?.setText(
                            if (hasSavedCustomConfiguration) savedConfiguration.modelName else ""
                        )
                    }
                    customFields.visibility = View.GONE
                } else {
                    customEndpointInput?.setText(nextProvider.defaultBaseUrl)
                    configuration = configuration.copy(
                        apiBaseUrl = nextProvider.defaultBaseUrl,
                        modelName = nextProvider.defaultModelName
                    )
                    apiAddressValue?.text = nextProvider.defaultBaseUrl
                    modelNameValue?.text = nextProvider.defaultModelName
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

        content.addView(sectionTitle("API Key"), topMargin(12))
        val apiKeyStatusView = TextView(activity).apply {
            text = apiKeyStatusText()
            textSize = 12f
            setTextColor(FreedomUi.MUTED)
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        apiKeyStatus = apiKeyStatusView
        content.addView(apiKeyStatusView)
        val apiKeyField = TextView(activity).apply {
            text = if (configuration.apiKey.isBlank()) "未设置" else "已保存 · 点击编辑"
            textSize = 14f
            setTextColor(FreedomUi.TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = FreedomUi.roundedSurface(dp(10), Color.WHITE, FreedomUi.DIVIDER)
            isClickable = true
            isFocusable = true
            contentDescription = "API Key，点击编辑"
        }
        apiKeyValue = apiKeyField
        apiKeyField.setOnClickListener { showApiKeyEditor() }
        content.addView(apiKeyField, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(TextView(activity).apply {
            text = "点击编辑 API Key"
            textSize = 11f
            setTextColor(FreedomUi.MUTED)
            setPadding(dp(2), dp(6), dp(2), dp(0))
        })

        val statusMessageView = TextView(activity).apply {
            textSize = 12f
            setTextColor(FreedomUi.SECONDARY)
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        statusMessage = statusMessageView
        content.addView(statusMessageView)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (configuration.apiKey.isNotBlank()) {
            loadRemoteModels(configuration.apiBaseUrl, configuration.apiKey)
        }
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

    private fun showApiAddressEditor() {
        showTextEditor(
            title = "API 地址",
            current = configuration.apiBaseUrl,
            hint = "例如 https://api.example.com/v1",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        ) { value ->
            val next = configuration.copy(apiBaseUrl = value).normalized()
            next.validationError()?.let { throw IllegalArgumentException(it) }
            configuration = next
            apiAddressValue?.text = next.apiBaseUrl.ifBlank { "未设置" }
            customEndpointInput?.setText(next.apiBaseUrl)
            if (next.apiKey.isNotBlank()) loadRemoteModels(next.apiBaseUrl, next.apiKey)
            onSave(next).getOrThrow()
        }
    }

    private fun showModelEditor() {
        val input = FreedomUi.editorInput(activity, dp, selectedModelName,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, "输入模型 ID")
        val content = FreedomUi.editorContent(activity, dp).apply {
            if (currentModelOptions.isNotEmpty()) {
                addView(TextView(activity).apply {
                    text = "已加载模型"
                    textSize = 11f
                    setTextColor(FreedomUi.MUTED)
                    setPadding(dp(2), 0, 0, dp(6))
                })
                val picker = Spinner(activity).apply {
                    adapter = ArrayAdapter(
                        activity,
                        android.R.layout.simple_spinner_item,
                        currentModelOptions.map { it.displayName }
                    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    setSelection(currentModelOptions.indexOfFirst { it.id == selectedModelName }.coerceAtLeast(0))
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            input.setText(currentModelOptions.getOrNull(position)?.id.orEmpty())
                            input.setSelection(input.text.length)
                        }
                    }
                }
                addView(picker, LinearLayout.LayoutParams(-1, dp(48)))
            }
            FreedomUi.addEditorInput(this, input, dp)
            addView(TextView(activity).apply {
                text = "可选择已加载模型，也可手动填写模型 ID"
                textSize = 11f
                setTextColor(FreedomUi.MUTED)
                setPadding(dp(2), dp(8), 0, 0)
            })
        }
        val dialog = FreedomUi.createEditorDialog(activity, "模型名称", content)
        FreedomUi.showEditorDialog(dialog, activity, dp, input) {
            val value = input.text.toString().trim()
            if (value.isBlank()) {
                input.error = "模型名称不能为空"
                return@showEditorDialog
            }
            val next = configuration.copy(modelName = value).normalized()
            configuration = next
            selectedModelName = value
            modelNameValue?.text = value
            customModelInput?.setText(value)
            onSave(next).getOrThrow()
            dialog.dismiss()
        }
    }

    private fun showTextEditor(
        title: String,
        current: String,
        hint: String,
        inputType: Int,
        onSaveValue: (String) -> Unit
    ) {
        val input = FreedomUi.editorInput(activity, dp, current, inputType, hint)
        val content = FreedomUi.editorContent(activity, dp)
        FreedomUi.addEditorInput(content, input, dp)
        val dialog = FreedomUi.createEditorDialog(activity, title, content)
        FreedomUi.showEditorDialog(dialog, activity, dp, input) {
            runCatching { onSaveValue(input.text.toString().trim()) }
                .onSuccess { dialog.dismiss() }
                .onFailure { input.error = it.message ?: "输入内容无效" }
        }
    }

    private fun loadRemoteModels(baseUrl: String, apiKey: String) {
        if (baseUrl.isBlank() || apiKey.isBlank()) return
        showStatus("正在加载模型列表…", error = false)
        Thread {
            val result = runCatching {
                val endpoint = baseUrl.trimEnd('/') + "/models"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "application/json")
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else {
                    connection.errorStream ?: connection.inputStream
                }
                val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                connection.disconnect()
                if (status !in 200..299) error("模型列表加载失败（HTTP $status）")
                val data = JSONObject(body).optJSONArray("data") ?: error("模型列表响应格式无效")
                (0 until data.length()).mapNotNull { index ->
                    data.optJSONObject(index)?.optString("id")?.trim()?.takeIf { it.isNotBlank() }
                }.distinct().map { AiModelOption(it, it) }
            }
            activity.runOnUiThread {
                result.onSuccess { options ->
                    currentModelOptions = options
                    if (options.any { it.id == selectedModelName }) {
                        modelNameValue?.text = selectedModelName
                    }
                    showStatus("已加载 ${options.size} 个模型。", error = false)
                }.onFailure { failure ->
                    showStatus(failure.message ?: "模型列表加载失败，可手动填写模型 ID。", error = true)
                }
            }
        }.start()
    }

    private fun showApiKeyEditor() {
        val input = FreedomUi.editorInput(activity, dp, "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "输入 API Key").apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val content = FreedomUi.editorContent(activity, dp)
        FreedomUi.addEditorInput(content, input, dp)
        FreedomUi.addEditorHint(content, "留空并保存清空已保存的 Key", dp)
        val dialog = FreedomUi.createEditorDialog(activity, "API Key", content)
        FreedomUi.showEditorDialog(dialog, activity, dp, input) {
            val endpoint = if (selectedProvider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) {
                customEndpointInput?.text?.toString()?.trim().orEmpty()
            } else {
                configuration.apiBaseUrl.ifBlank { selectedProvider.defaultBaseUrl }
            }
            val model = if (selectedProvider == AiModelServiceProvider.CUSTOM_OPENAI_COMPAT) {
                customModelInput?.text?.toString()?.trim().orEmpty()
            } else {
                selectedModelName.ifBlank { configuration.modelName }
            }
            val next = runCatching {
                configuration.copy(
                    provider = selectedProvider,
                    apiBaseUrl = endpoint,
                    modelName = model,
                    apiKey = input.text.toString().trim()
                ).normalized()
            }.getOrElse { failure ->
                input.error = failure.message ?: "API Key 无效"
                return@showEditorDialog
            }
            if (selectedProvider != AiModelServiceProvider.CUSTOM_OPENAI_COMPAT &&
                currentModelOptions.none { it.id == next.modelName }
            ) {
                input.error = "请选择列表中的模型"
                return@showEditorDialog
            }
            next.validationError()?.let { message ->
                input.error = message
                return@showEditorDialog
            }
            if (next.modelName.isBlank() || next.apiBaseUrl.isBlank()) {
                input.error = "模型配置不完整，请检查服务商和模型选择"
                return@showEditorDialog
            }
            onSave(next).fold(
                onSuccess = {
                    configuration = next
                    savedConfiguration = next
                    runCatching { onCloudConsentChanged(next.apiKey.isNotBlank()) }
                        .onFailure { failure ->
                            showStatus(failure.message ?: "保存云端授权状态失败。", error = true)
                        }
                    apiKeyValue?.text = if (next.apiKey.isBlank()) "未设置" else "已保存 · 点击编辑"
                    apiKeyStatus?.text = apiKeyStatusText()
                    if (next.apiKey.isNotBlank()) loadRemoteModels(next.apiBaseUrl, next.apiKey)
                    showStatus("模型配置已保存。", error = false)
                    dialog.dismiss()
                },
                onFailure = { failure ->
                    input.error = failure.message ?: "保存失败，请检查安全存储状态"
                }
            )
        }
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
