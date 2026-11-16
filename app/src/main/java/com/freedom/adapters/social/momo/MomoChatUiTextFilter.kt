package com.freedom.adapters.social.momo

/** Momo-specific UI notice filtering; deliberately kept inside the platform adapter. */
internal object MomoChatUiTextFilter {
    private val NON_MESSAGE_UI_TEXT = setOf(
        "是否关注对方", "关注后可以订阅对方动态", "通过随机匹配向你打招呼", "通过敲敲向你打招呼",
        "来自在线推荐", "关注", "已关注", "取消关注", "发送", "语音", "图片",
        "更多", "表情", "拍摄", "按住说话"
    )

    fun isNonMessageUiText(value: String): Boolean {
        val normalized = value.trim().lowercase()
            .replace(Regex("[\\s\\p{Punct}，。！？、：；（）【】《》“”‘’·…]+"), "")
        return normalized.isNotEmpty() && normalized in NON_MESSAGE_UI_TEXT
    }
}
