package com.freedom.application.ports

/** Application boundary for user-owned photos in the dedicated Freedom media album. */
data class ImagePoolImportResult(
    val publishedUris: List<String>,
    val failedCount: Int,
    val unsupportedSystem: Boolean = false
)

interface ImagePoolResourcePort {
    /** Blocking media IO; caller must use a background worker. */
    fun importImages(sourceUris: List<String>): ImagePoolImportResult

    /** Returns resource URIs that could not be removed from the managed pool. */
    fun removeFromPool(uris: List<String>): List<String>
}
