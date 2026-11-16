package com.freedom.features.aireply.ai

import java.net.URI

/**
 * User-owned configuration for the OpenAI-compatible remote chat-completions adapter.
 * The API key is deliberately excluded from toString() to prevent accidental log disclosure.
 */
data class AiModelConfiguration(
    val apiBaseUrl: String = "",
    val modelName: String = "",
    val apiKey: String = "",
    val temperature: Double = DEFAULT_TEMPERATURE,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS
) {
    init {
        require(temperature.isFinite() && temperature in 0.0..2.0) {
            "Temperature 必须在 0.0 到 2.0 之间"
        }
        require(maxOutputTokens in 1..8_192) {
            "最大输出 Token 数必须在 1 到 8192 之间"
        }
        require(requestTimeoutMillis in MIN_REQUEST_TIMEOUT_MILLIS..MAX_REQUEST_TIMEOUT_MILLIS) {
            "请求超时必须在 1 到 180 秒之间"
        }
        require(apiBaseUrl.length <= MAX_API_BASE_URL_LENGTH) { "API Base URL 过长" }
        require(modelName.length <= MAX_MODEL_NAME_LENGTH) { "模型名称过长" }
        require(apiKey.length <= MAX_API_KEY_LENGTH) { "API Key 过长" }
        require(!apiKey.contains('\n') && !apiKey.contains('\r')) { "API Key 不能包含换行符" }
        require(!modelName.contains('\n') && !modelName.contains('\r')) { "模型名称不能包含换行符" }
    }

    /** Empty endpoint/model/key values are allowed while the user is still configuring the form. */
    fun validationError(): String? {
        val endpoint = apiBaseUrl.trim()
        if (endpoint.isEmpty()) return null
        val uri = runCatching { URI(endpoint) }.getOrNull()
            ?: return "API Base URL 格式无效"
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return "API Base URL 必须是有效的 HTTPS 地址"
        }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return "API Base URL 不能包含账号密码、查询参数或片段"
        }
        if (uri.port !in -1..65_535 || uri.port == 0) return "API Base URL 端口无效"
        return null
    }

    val isReadyForCloudProvider: Boolean
        get() = apiBaseUrl.isNotBlank() && modelName.isNotBlank() && apiKey.isNotBlank() &&
            validationError() == null

    fun normalized(): AiModelConfiguration = copy(
        apiBaseUrl = apiBaseUrl.trim().trimEnd('/'),
        modelName = modelName.trim(),
        apiKey = apiKey.trim()
    )

    override fun toString(): String =
        "AiModelConfiguration(apiBaseUrl=${if (apiBaseUrl.isBlank()) "[EMPTY]" else "[CONFIGURED]"}, " +
            "modelName=$modelName, apiKey=[REDACTED], temperature=$temperature, " +
            "maxOutputTokens=$maxOutputTokens, " +
            "requestTimeoutMillis=$requestTimeoutMillis)"

    companion object {
        const val DEFAULT_TEMPERATURE = 0.3
        const val DEFAULT_MAX_OUTPUT_TOKENS = 512
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 30_000L
        const val MIN_REQUEST_TIMEOUT_MILLIS = 1_000L
        const val MAX_REQUEST_TIMEOUT_MILLIS = 180_000L
        const val MAX_API_BASE_URL_LENGTH = 2_048
        const val MAX_MODEL_NAME_LENGTH = 256
        const val MAX_API_KEY_LENGTH = 8_192
    }
}

/** Persistence boundary for the user-owned model configuration. */
interface AiModelConfigurationRepository {
    fun load(): AiModelConfiguration
    fun save(configuration: AiModelConfiguration)
    fun clearApiKey()
}
