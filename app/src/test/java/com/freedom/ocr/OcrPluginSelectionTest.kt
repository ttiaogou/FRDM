package com.freedom.ocr

import org.autojs.plugin.common.api.PluginInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPluginSelectionTest {
    @Test
    fun matchesEngineIdEngineVariantAndProfile() {
        val info = PluginInfo().apply {
            id = "freedom-paddle-ocr-pp-ocrv6-small"
            engine = "paddle-ocr"
            variant = "v6-small"
        }
        assertTrue(OcrPluginSelection.matches(info, info.id, "paddle-ocr", "v6-small", null))
        assertFalse(OcrPluginSelection.matches(info, null, "rapid-ocr", null, null))
        assertFalse(OcrPluginSelection.matches(info, null, null, "v5", null))
        assertFalse(OcrPluginSelection.matches(info, "another-plugin", null, null, null))
    }

    @Test
    fun defaultSelectionPrefersNewerVariantThenStablePackageName() {
        data class Candidate(val packageName: String, val info: PluginInfo)
        val candidates = listOf(
            Candidate("com.example.ocr.v5", PluginInfo().apply { variant = "v5" }),
            Candidate("com.example.ocr.v6", PluginInfo().apply { variant = "v6-small" }),
            Candidate("com.example.ocr.v6-alt", PluginInfo().apply { variant = "v6" }),
        )
        assertEquals(
            "com.example.ocr.v6",
            OcrPluginSelection.selectDefault(candidates, { it.info }, { it.packageName })?.packageName,
        )
    }

    @Test
    fun defaultSelectionUsesUpstreamVariantBeforeRecommendedFlag() {
        data class Candidate(val packageName: String, val info: PluginInfo)
        val candidates = listOf(
            Candidate("com.example.ocr.v5-recommended", PluginInfo().apply {
                id = "recommended"
                variant = "v5"
            }),
            Candidate("com.example.ocr.v6", PluginInfo().apply { variant = "v6" }),
        )
        assertEquals(
            "com.example.ocr.v6",
            OcrPluginSelection.selectDefault(
                candidates,
                { it.info },
                { it.packageName },
                recommendedDefaultOf = { it?.id == "recommended" },
            )?.packageName,
        )
    }

    @Test
    fun recommendedFlagBreaksEqualVariantTieBeforePackageName() {
        data class Candidate(val packageName: String, val info: PluginInfo)
        val candidates = listOf(
            Candidate("com.example.ocr.a", PluginInfo().apply { variant = "v6" }),
            Candidate("com.example.ocr.z", PluginInfo().apply {
                id = "recommended"
                variant = "v6"
            }),
        )
        assertEquals(
            "com.example.ocr.z",
            OcrPluginSelection.selectDefault(
                candidates,
                { it.info },
                { it.packageName },
                recommendedDefaultOf = { it?.id == "recommended" },
            )?.packageName,
        )
    }

    @Test
    fun profileNormalizationMatchesAutoJs6Conventions() {
        assertEquals("pp-ocr-v6-mobile", OcrPluginSelection.normalizeProfile(" PP_OCR V6 Mobile "))
    }
}
