package com.freedom.ocr.plugin.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.autojs.plugin.paddle.ocr.api.OcrPluginContract
import org.autojs.plugin.paddle.ocr.api.OcrPluginDiagnosticWire
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Mirrors only existing OCR diagnostic records; never waits for the host, even in a watchdog. */
internal object OcrPluginDiagnosticRelay {
    private val journals = ConcurrentHashMap<String, Journal>()
    private val binderRequest = ThreadLocal<String>()
    @Volatile private var engineRequest: String? = null
    private val requestPattern = Regex("(?:^|[; ])requestId=([^; ]+)")

    @Suppress("DEPRECATION")
    fun <T> withRequest(requestId: String, extras: Bundle?, block: () -> T): T {
        val descriptor = runCatching {
            extras?.getParcelable<ParcelFileDescriptor>(OcrPluginContract.EXTRA_DIAGNOSTIC_WRITE_FD)
        }.getOrNull() ?: return block()
        val journal = runCatching {
            val fd = descriptor.fileDescriptor
            Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            Journal(descriptor)
        }.getOrElse {
            runCatching { descriptor.close() }
            return block()
        }
        if (journals.putIfAbsent(requestId, journal) != null) {
            runCatching { descriptor.close() }
            return block()
        }
        binderRequest.set(requestId)
        try {
            return block()
        } finally {
            binderRequest.remove()
            journals.remove(requestId, journal)
            journal.finish()
        }
    }

    /** Called only while OcrPluginService owns engineLock; covers coroutine-based model/native logs. */
    fun <T> withEngineRequest(requestId: String, block: () -> T): T {
        engineRequest = requestId
        try {
            return block()
        } finally {
            engineRequest = null
        }
    }

    fun i(tag: String, message: String): Int {
        val result = Log.i(tag, message)
        mirror("INFO", tag, message)
        return result
    }

    fun w(tag: String, message: String): Int {
        val result = Log.w(tag, message)
        mirror("WARN", tag, message)
        return result
    }

    fun e(tag: String, message: String, error: Throwable? = null): Int {
        val result = if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
        mirror("ERROR", tag, message)
        return result
    }

    private fun mirror(level: String, tag: String, message: String) {
        // Explicit request IDs also bind watchdog threads; model management outside the OCR
        // request must never borrow an active engine's journal.
        runCatching {
            val request = requestPattern.find(message.substringBefore(";message="))?.groupValues?.get(1)
                ?: binderRequest.get()
                ?: engineRequest?.takeIf { tag == "FreedomOcrEngine" }
                ?: return
            journals[request]?.append(level, message)
        }
    }

    private class Journal(private val descriptor: ParcelFileDescriptor) {
        private val normal = AtomicInteger()
        private val total = AtomicInteger()
        private val dropped = AtomicInteger()

        fun append(level: String, message: String) {
            if ((level == "INFO" && normal.incrementAndGet() > OcrPluginDiagnosticWire.MAX_NORMAL_RECORDS) ||
                total.incrementAndGet() > OcrPluginDiagnosticWire.MAX_RECORDS ||
                !write(level, message)) dropped.incrementAndGet()
        }

        private fun write(level: String, message: String): Boolean = runCatching {
            val bytes = OcrPluginDiagnosticWire.encode(level, SystemClock.elapsedRealtime(), message) ?: return false
            // Nonblocking, atomic frame; a full pipe or closed reader only drops diagnostics.
            Os.write(descriptor.fileDescriptor, bytes, 0, bytes.size) == bytes.size
        }.getOrDefault(false)

        fun finish() {
            try {
                write(if (dropped.get() == 0) "INFO" else "WARN",
                    "OCR_PLUGIN_DIAGNOSTIC_END dropped=${dropped.get()}")
            } finally {
                runCatching { descriptor.close() }
            }
        }
    }
}
