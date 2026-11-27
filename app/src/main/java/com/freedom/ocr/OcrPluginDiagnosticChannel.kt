package com.freedom.ocr

import android.os.ParcelFileDescriptor
import com.freedom.core.logging.RunLogLevel
import com.freedom.data.RunDiagnosticsManager
import org.autojs.plugin.paddle.ocr.api.OcrPluginDiagnosticWire
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Reads plugin evidence while the synchronous OCR call is still in flight, including before process death. */
internal class OcrPluginDiagnosticChannel private constructor(
    private val readEnd: ParcelFileDescriptor,
    val writeEnd: ParcelFileDescriptor,
    private val record: (RunLogLevel, String) -> Unit
) : Closeable {
    private val received = AtomicInteger()
    private val reader = thread(isDaemon = true, name = "Freedom-OCR-DiagnosticPipe") {
        var rejected = 0
        var status = "EOF"
        var pluginEndSeen = false
        try {
            ParcelFileDescriptor.AutoCloseInputStream(readEnd).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val event = OcrPluginDiagnosticWire.decode(line)
                    if (event == null || received.get() >= OcrPluginDiagnosticWire.MAX_RECORDS + 1) {
                        rejected++
                    } else {
                        received.incrementAndGet()
                        if (event.message.startsWith("OCR_PLUGIN_DIAGNOSTIC_END dropped=")) pluginEndSeen = true
                        record(RunLogLevel.valueOf(event.level), "pluginElapsedMs=${event.elapsedMs};${event.message}")
                    }
                }
            }
        } catch (_: Exception) {
            status = "READ_INTERRUPTED"
        } finally {
            if (status == "EOF" && !pluginEndSeen) status = "EOF_WITHOUT_PLUGIN_END"
            record(if (status == "EOF" && received.get() > 0 && rejected == 0) RunLogLevel.INFO else RunLogLevel.WARN,
                "OCR_PLUGIN_DIAGNOSTIC_CHANNEL_END status=$status;received=${received.get()};rejected=$rejected;pluginEndSeen=$pluginEndSeen")
        }
    }

    override fun close() {
        // The remote descriptor is closed on normal exit or by the kernel on plugin process death.
        runCatching { writeEnd.close() }
        try {
            reader.join(150L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (reader.isAlive) {
                runCatching { readEnd.close() }
                record(RunLogLevel.WARN, "OCR_PLUGIN_DIAGNOSTIC_CHANNEL_DRAIN_INCOMPLETE received=${received.get()}")
            }
        }
    }

    companion object {
        fun open(
            diagnostics: RunDiagnosticsManager,
            runId: String,
            requestId: String,
            attempt: Int,
            supported: Boolean
        ): OcrPluginDiagnosticChannel? {
            val record = { level: RunLogLevel, message: String ->
                runCatching { diagnostics.recordOcrPluginDiagnostic(runId, requestId, attempt, level, message) }
                Unit
            }
            if (!supported) {
                record(RunLogLevel.INFO, "OCR_PLUGIN_DIAGNOSTIC_CHANNEL_UNSUPPORTED")
                return null
            }
            val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse {
                record(RunLogLevel.WARN, "OCR_PLUGIN_DIAGNOSTIC_CHANNEL_OPEN_FAILED type=${it.javaClass.simpleName}")
                return null
            }
            return try {
                OcrPluginDiagnosticChannel(pipe[0], pipe[1], record)
            } catch (failure: Exception) {
                pipe.forEach { runCatching { it.close() } }
                record(RunLogLevel.WARN, "OCR_PLUGIN_DIAGNOSTIC_CHANNEL_OPEN_FAILED type=${failure.javaClass.simpleName}")
                null
            }
        }
    }
}
