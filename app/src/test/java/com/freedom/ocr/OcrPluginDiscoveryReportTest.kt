package com.freedom.ocr

import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPluginDiscoveryReportTest {
    @Test
    fun emptyServiceQueryDoesNotClaimThatPluginApkIsNotInstalled() {
        val report = OcrPluginDiscoveryReport(
            plugins = emptyList(),
            attempts = 3,
            serviceCandidateCount = 0,
            eligibleServiceCount = 0,
            queryCompleted = true,
            failures = emptyList(),
        )

        assertTrue(report.diagnosticMessage().contains("不等同于确认 APK 未安装"))
    }

    @Test
    fun failedBinderValidationIsReportedSeparatelyFromMissingService() {
        val report = OcrPluginDiscoveryReport(
            plugins = emptyList(),
            attempts = 2,
            serviceCandidateCount = 1,
            eligibleServiceCount = 1,
            queryCompleted = true,
            failures = listOf("com.example.ocr：等待 OCR 插件连接超时"),
        )

        assertTrue(report.diagnosticMessage().contains("连接或元数据验证未成功"))
        assertTrue(report.diagnosticMessage().contains("连接超时"))
    }
}
