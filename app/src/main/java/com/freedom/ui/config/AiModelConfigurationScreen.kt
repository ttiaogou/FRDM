package com.freedom.ui.config

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.freedom.features.aireply.ai.AiModelConfiguration
import com.freedom.ui.theme.FreedomUi

/** User-facing editor for model connection parameters and the locally encrypted API key. */
internal class AiModelConfigurationScreen(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    initialConfiguration: AiModelConfiguration,
    private val onBack: () -> Unit,
    private val onSave: (AiModelConfiguration) -> Result<Unit>,
    private val onClearApiKey: () -> Result<Unit>
) {
    private var configuration = initialConfiguration
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
        val endpointInput = addField(
            content,
            label = "API Base URL",
            value = configuration.apiBaseUrl,
            hint = "例如 https://api.example.com/v1",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val modelInput = addField(
            content,
            label = "模型名称 / Model ID",
            value = configuration.modelName,
            hint = "填写服务端支持的模型 ID",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )
        content.addView(sectionTitle("API Key"), topMargin(18))
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
            label = "API Key",
            value = "",
            hint = if (configuration.apiKey.isBlank()) "粘贴 API Key" else "留空保持已保存的 Key 不变",
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

        content.addView(sectionTitle("生成参数"), topMargin(18))
        val temperatureInput = addField(
            content,
            label = "Temperature（0.0 – 2.0）",
            value = configuration.temperature.toString(),
            hint = "默认 0.3",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val maxTokensInput = addField(
            content,
            label = "Max Output Tokens（1 – 8192）",
            value = configuration.maxOutputTokens.toString(),
            hint = "默认 512",
            inputType = InputType.TYPE_CLASS_NUMBER
        )
        val timeoutInput = addField(
            content,
            label = "请求超时（秒，1 – 180）",
            value = (configuration.requestTimeoutMillis / 1_000L).toString(),
            hint = "默认 30 秒",
            inputType = InputType.TYPE_CLASS_NUMBER
        )

        content.addView(TextView(activity).apply {
            text = "API Key 与其他模型配置使用独立 Android Keystore 密钥，通过 AES-GCM 加密后保存在应用私有存储中。API 地址仅接受 HTTPS。当前阶段只保存配置，尚未发送模型请求；云端处理仍受“云端模型处理授权”开关控制。"
            textSize = 12f
            setTextColor(FreedomUi.MUTED)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(2), dp(18), dp(2), dp(10))
        }, topMargin(10))
        val statusMessageView = TextView(activity).apply {
            textSize = 12f
            setTextColor(FreedomUi.SECONDARY)
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        statusMessage = statusMessageView
        content.addView(statusMessageView)
        val saveButton = Button(activity).apply {
            text = "保存模型配置"
            setOnClickListener {
                val temperature = temperatureInput.text.toString().trim().toDoubleOrNull()
                if (temperature == null) {
                    showStatus("Temperature 必须是数字。", error = true)
                    temperatureInput.error = "请输入 0.0 到 2.0 之间的数字"
                    return@setOnClickListener
                }
                val maxTokens = maxTokensInput.text.toString().trim().toIntOrNull()
                if (maxTokens == null) {
                    showStatus("Max Output Tokens 必须是整数。", error = true)
                    maxTokensInput.error = "请输入 1 到 8192 之间的整数"
                    return@setOnClickListener
                }
                val timeoutSeconds = timeoutInput.text.toString().trim().toLongOrNull()
                if (timeoutSeconds == null || timeoutSeconds !in 1L..180L) {
                    showStatus("请求超时必须是 1 到 180 秒之间的整数。", error = true)
                    timeoutInput.error = "请输入 1 到 180 之间的整数"
                    return@setOnClickListener
                }
                val next = runCatching {
                    configuration.copy(
                        apiBaseUrl = endpointInput.text.toString().trim(),
                        modelName = modelInput.text.toString().trim(),
                        apiKey = apiKeyInput?.text?.toString()?.trim()
                            ?.takeIf { it.isNotEmpty() } ?: configuration.apiKey,
                        temperature = temperature,
                        maxOutputTokens = maxTokens,
                        requestTimeoutMillis = timeoutSeconds * 1_000L
                    ).normalized()
                }.getOrElse { failure ->
                    showStatus(failure.message ?: "模型参数无效。", error = true)
                    return@setOnClickListener
                }
                next.validationError()?.let { message ->
                    showStatus(message, error = true)
                    endpointInput.error = message
                    return@setOnClickListener
                }
                onSave(next).fold(
                    onSuccess = {
                        configuration = next
                        apiKeyInput?.text?.clear()
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
