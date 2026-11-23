package com.freedom.features.aireply.ai

/** 向远端模型服务商发送对话上下文前，必须通过用户明确授权检查。 */
fun interface AiModelCloudConsent {
    fun isGranted(): Boolean

    companion object {
        val DENY_ALL = AiModelCloudConsent { false }
    }
}

/** 对话文本离开设备前，脱敏常见的高风险个人身份标识。 */
object AiModelDataRedactor {
    private val emailPattern = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val mainlandMobilePattern = Regex("(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9]{9}(?![0-9])")
    private val mainlandIdPattern = Regex(
        "(?<![0-9])[1-9][0-9]{5}(?:18|19|20)[0-9]{2}" +
            "(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9])"
    )

    fun redact(text: String): String = text
        .replace(mainlandIdPattern, "[身份证号已脱敏]")
        .replace(mainlandMobilePattern, "[手机号已脱敏]")
        .replace(emailPattern, "[邮箱已脱敏]")

    fun redactMessages(messages: List<AiModelMessage>): List<AiModelMessage> =
        messages.map { message -> message.copy(content = redact(message.content)) }
}
