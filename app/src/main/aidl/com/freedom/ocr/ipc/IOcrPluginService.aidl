package com.freedom.ocr.ipc;

import android.os.ParcelFileDescriptor;

/** Stable IPC contract between Freedom and independently-installed OCR plugins. */
interface IOcrPluginService {
    String getPluginInfo();
    String getStatus();
    String ensureModelInstalled(String modelId);
    String selectModel(String modelId);
    String recognize(in ParcelFileDescriptor imageFileDescriptor);
    void releaseEngine();
}
