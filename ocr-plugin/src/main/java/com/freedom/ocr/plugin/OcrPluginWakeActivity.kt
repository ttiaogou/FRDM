package com.freedom.ocr.plugin

import android.app.Activity
import android.os.Bundle

/**
 * No-display wake entry adapted from AutoJs6's plugin WakeActivity pattern. It only moves the
 * stopped plugin package into an active process so the host can bind its exported OCR service.
 */
class OcrPluginWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
