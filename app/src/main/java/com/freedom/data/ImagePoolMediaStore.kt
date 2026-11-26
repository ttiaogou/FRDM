package com.freedom.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/** Results describe only media durably published to the public Freedom album. */
internal data class ImagePoolImportResult(
    val publishedUris: List<String>,
    val failedCount: Int,
    val unsupportedSystem: Boolean = false
)

/**
 * Android-only media adapter. Never touches AccessibilityService or chat controls.
 * Import is blocking IO and must be called from a background worker.
 *
 * The source SAF grant is requested persistently where supported. The published MediaStore
 * item is owned by this app and remains usable without the source document provider.
 */
internal class ImagePoolMediaStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val cacheDir = context.applicationContext.cacheDir

    fun importImages(sources: List<Uri>): ImagePoolImportResult {
        val unique = sources.distinct()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Public shared-album writes on API 26-28 require a separate runtime storage grant.
            // Never silently count a source URI as an exported picture.
            return ImagePoolImportResult(emptyList(), unique.size, unsupportedSystem = true)
        }
        val published = ArrayList<String>(unique.size)
        var failures = 0
        unique.forEach { source ->
            runCatching { publish(source).toString() }
                .onSuccess { published.add(it) }
                .onFailure { failures++ }
        }
        return ImagePoolImportResult(published, failures)
    }

    private fun publish(source: Uri): Uri {
        require(source.scheme == ContentResolver.SCHEME_CONTENT) { "A document URI is required" }
        // A provider may expose only a transient grant. A successful MediaStore copy is durable
        // even in that case, so a failed takePersistableUriPermission is not itself import failure.
        runCatching {
            resolver.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val mime = resolver.getType(source)?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            ?: error("No image MIME type")
        val extension = EXTENSIONS[mime] ?: error("Unsupported image MIME type")
        val temporary = File.createTempFile("freedom_import_", ".part", cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = resolver.openInputStream(source)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        total += read
                    }
                    total
                }
            } ?: error("Unable to read selected document")
            require(bytes > 0) { "Empty image" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val name = "FRDM_$hash.$extension"
            findPublished(name, hash)?.let { return it }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = imagesCollection()
            val destination = resolver.insert(collection, values) ?: error("MediaStore insertion failed")
            try {
                resolver.openOutputStream(destination, "w")?.use { output ->
                    FileInputStream(temporary).use { input -> input.copyTo(output) }
                } ?: error("Cannot open MediaStore output")
                val finalized = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(destination, finalized, null, null) == 1) {
                    "Cannot finalize published image"
                }
                return destination
            } catch (failure: Exception) {
                runCatching { resolver.delete(destination, null, null) }
                throw failure
            }
        } finally {
            temporary.delete()
        }
    }

    /** Reimporting the same bytes resolves to the same album image, not another copy. */
    private fun findPublished(name: String, expectedHash: String): Uri? {
        val collection = imagesCollection()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, ALBUM_PATH),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = ContentUris.withAppendedId(collection, cursor.getLong(0))
                val actual = runCatching {
                    val digest = MessageDigest.getInstance("SHA-256")
                    resolver.openInputStream(candidate)?.use { stream ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count > 0) digest.update(buffer, 0, count)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                }.getOrNull()
                if (actual == expectedHash) return candidate
            }
        }
        return null
    }

    /**
     * Only delete verified FRDM-managed album items. Legacy source document URIs are detached
     * from the pool without deleting the user's original files.
     */
    fun removeFromPool(uris: List<String>): List<String> {
        val failed = ArrayList<String>()
        uris.forEach { value ->
            val uri = runCatching { Uri.parse(value) }.getOrNull()
            if (uri == null) { failed.add(value); return@forEach }
            if (uri.authority != "media") {
                runCatching {
                    resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                return@forEach
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                !runCatching { isManagedMedia(uri) }.getOrDefault(false)) {
                failed.add(value)
            } else if (runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false).not()) {
                failed.add(value)
            }
        }
        return failed
    }

    private fun isManagedMedia(uri: Uri): Boolean =
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
            null, null, null
        )?.use { cursor ->
            cursor.moveToFirst() &&
                cursor.getString(0)?.startsWith("FRDM_") == true &&
                cursor.getString(1) == ALBUM_PATH
        } == true

    private fun imagesCollection(): Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    companion object {
        const val ALBUM_PATH = "Pictures/Freedom/"
        private val EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/gif" to "gif",
            "image/avif" to "avif",
            "image/bmp" to "bmp",
            "image/x-ms-bmp" to "bmp"
        )
    }
}
