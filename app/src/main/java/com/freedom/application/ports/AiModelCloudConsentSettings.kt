package com.freedom.application.ports

import com.freedom.features.aireply.ai.AiModelCloudConsent

/** Read/write application boundary for the explicit remote-model processing consent. */
interface AiModelCloudConsentSettings : AiModelCloudConsent {
    fun setGranted(granted: Boolean)
}
