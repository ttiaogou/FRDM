package com.freedom.ocr

import org.autojs.plugin.common.api.PluginInfo
import org.autojs.plugin.paddle.ocr.api.PaddleOcrPluginCapabilityKeys

/**
 * Port of AutoJs6's OCR plugin selection policy. Freedom keeps the same metadata-driven
 * selection shape but narrows automatic execution to engines it has an adapter for.
 */
internal object OcrPluginSelection {
    fun declaredModelProfile(info: PluginInfo?): String? =
        info?.capabilities?.getString(PaddleOcrPluginCapabilityKeys.MODEL_PROFILE)?.takeIf { it.isNotBlank() }

    fun isRecommendedDefault(info: PluginInfo?): Boolean =
        info?.capabilities?.getBoolean(PaddleOcrPluginCapabilityKeys.RECOMMENDED_FOR_MOBILE_DEFAULT, false) == true

    fun normalizeProfile(raw: String?): String? = raw?.trim()?.lowercase()
        ?.replace(Regex("[\\s_-]+"), "-")?.takeIf { it.isNotEmpty() }

    fun matches(info: PluginInfo, engineId: String?, engine: String?, variant: String?, profile: String?): Boolean {
        if (engineId != null && info.id != engineId) return false
        if (engine != null && info.engine != engine) return false
        if (variant != null && info.variant != variant) return false
        if (profile != null && normalizeProfile(declaredModelProfile(info)) != normalizeProfile(profile)) return false
        return true
    }

    /** Same default tie-breakers used by AutoJs6 when no explicit package priority is stored. */
    fun <T> selectDefault(
        candidates: List<T>,
        pluginInfoOf: (T) -> PluginInfo?,
        packageNameOf: (T) -> String,
        recommendedDefaultOf: (PluginInfo?) -> Boolean = ::isRecommendedDefault,
    ): T? = candidates.sortedWith(
        compareByDescending<T> { variantNumber(pluginInfoOf(it)?.variant) }
            .thenByDescending { recommendedDefaultOf(pluginInfoOf(it)) }
            .thenBy { packageNameOf(it) },
    ).firstOrNull()

    private fun variantNumber(value: String?): Int =
        Regex("(\\d+)").findAll(value.orEmpty()).lastOrNull()?.value?.toIntOrNull() ?: 0
}
