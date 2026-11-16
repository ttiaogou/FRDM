package com.freedom.application.ports

/** Read/write boundary for target selection and the existing monitor presentation snapshot. */
interface MonitorSettingsRepository {
    fun targetPackage(): String
    fun setTargetPackage(packageName: String)
}

data class MonitorPresentationSnapshot(
    val appLabel: String,
    val pageLabel: String,
    val nodePreview: List<String>,
    val visitedNodeCount: Int,
    val truncated: Boolean
)

interface MonitorSnapshotRepository {
    fun updateAppPage(appLabel: String?, pageLabel: String?)
    fun save(snapshot: MonitorPresentationSnapshot)
    fun read(): MonitorPresentationSnapshot
}
