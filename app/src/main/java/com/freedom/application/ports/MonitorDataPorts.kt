package com.freedom.application.ports

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
