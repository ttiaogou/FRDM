package org.autojs.plugin.paddle.ocr.api

/** Optional diagnostic-only side channel; each frame fits within the Linux atomic pipe-write limit. */
object OcrPluginDiagnosticWire {
    const val MAX_LINE_BYTES = 2048
    const val MAX_RECORDS = 256
    const val MAX_NORMAL_RECORDS = 192

    data class Record(val level: String, val elapsedMs: Long, val message: String)

    fun encode(level: String, elapsedMs: Long, message: String): ByteArray? {
        // Never transport exception messages, native paths or stack traces from the plugin.
        val safe = message.substringBefore(";message=").substringBefore(";nativeLibraryDir=")
            .replace('\r', ' ').replace('\n', ' ')
        val line = "$level|$elapsedMs|$safe"
        if (decode(line) == null) return null
        return (line + "\n").toByteArray(Charsets.UTF_8)
    }

    fun decode(line: String): Record? {
        if (line.toByteArray(Charsets.UTF_8).size >= MAX_LINE_BYTES || '\r' in line || '\n' in line) return null
        val fields = line.split('|', limit = 3)
        if (fields.size != 3 || fields[0] !in setOf("INFO", "WARN", "ERROR")) return null
        val elapsed = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val message = fields[2]
        if (!message.startsWith("OCR_") || ";message=" in message || ";nativeLibraryDir=" in message) return null
        return Record(fields[0], elapsed, message)
    }
}
