package com.freedom.features.aireply.ai

import java.net.URI

data class AiModelOption(
    val id: String,
    val displayName: String
)

/** 应用当前支持的模型服务商；CUSTOM_OPENAI_COMPAT 保留已有的自定义接口地址。 */
enum class AiModelServiceProvider(
    val storageKey: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModelName: String,
    val modelOptions: List<AiModelOption>
) {
    OPENAI(
        storageKey = "openai",
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModelName = "gpt-6.1-sol",
        modelOptions = listOf(
            AiModelOption("gpt-6.1-sol", "GPT-6.1 Sol · 综合推荐"),
            AiModelOption("gpt-6.1-terra", "GPT-6.1 Terra · 快速助手"),
            AiModelOption("gpt-6-luna", "GPT-6 Luna · 轻量快速"),
            AiModelOption("gpt-6-astra", "GPT-6 Astra · 高能力")
        )
    ),
    DEEPSEEK(
        storageKey = "deepseek",
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com",
        defaultModelName = "deepseek-flash",
        modelOptions = listOf(
            AiModelOption("deepseek-flash", "DeepSeek Flash · 日常推荐"),
            AiModelOption("deepseek-v4-pro", "DeepSeek V4 Pro · 高能力")
        )
    ),
    CUSTOM_OPENAI_COMPAT(
        storageKey = "custom_openai_compatible",
        displayName = "自定义兼容接口（高级）",
        defaultBaseUrl = "",
        defaultModelName = "",
        modelOptions = emptyList()
    );

    companion object {
        fun fromStorageKey(value: String): AiModelServiceProvider =
            entries.firstOrNull { it.storageKey == value || it.name == value }
                ?: throw IllegalArgumentException("AI 模型服务商不受支持")

        fun fromLegacyBaseUrl(baseUrl: String): AiModelServiceProvider {
            val host = runCatching { URI(baseUrl).host?.lowercase() }.getOrNull()
            return when (host) {
                "api.openai.com" -> OPENAI
                "api.deepseek.com" -> DEEPSEEK
                else -> if (baseUrl.isBlank()) OPENAI else CUSTOM_OPENAI_COMPAT
            }
        }
    }
}

/**
 * 用户为所选云端模型服务商配置的参数。
 * toString() 不包含 API Key、原始模型名称或接口地址，避免日志意外泄露。
 */
data class AiModelConfiguration(
    val apiBaseUrl: String = AiModelServiceProvider.DEEPSEEK.defaultBaseUrl,
    val modelName: String = AiModelServiceProvider.DEEPSEEK.defaultModelName,
    val apiKey: String = "",
    val temperature: Double = DEFAULT_TEMPERATURE,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val provider: AiModelServiceProvider = AiModelServiceProvider.DEEPSEEK
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

    /** 用户尚在填写配置时，允许接口地址、模型名称和密钥为空。 */
    fun validationError(): String? {
        val endpoint = apiBaseUrl.trim()
        if (endpoint.isEmpty()) return null
        val uri = runCatching { URI(endpoint) }.getOrNull()
            ?: return "API 地址格式无效"
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return "API 地址必须是有效的 HTTPS 地址"
        }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return "API 地址不能包含账号密码、查询参数或片段"
        }
        if (uri.port !in -1..65_535 || uri.port == 0) return "API 地址端口无效"
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
        "AiModelConfiguration(provider=${provider.storageKey}, " +
            "apiBaseUrl=${if (apiBaseUrl.isBlank()) "[EMPTY]" else "[CONFIGURED]"}, " +
            "modelName=${if (modelName.isBlank()) "[EMPTY]" else "[CONFIGURED]"}, " +
            "apiKey=[REDACTED], temperature=$temperature, " +
            "maxOutputTokens=$maxOutputTokens, requestTimeoutMillis=$requestTimeoutMillis)"

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

/** 用户模型配置的持久化接口边界。 */
interface AiModelConfigurationRepository {
    fun load(): AiModelConfiguration
    fun save(configuration: AiModelConfiguration)
    fun clearApiKey()
}
