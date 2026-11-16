package com.freedom.data

import android.content.Context
import com.freedom.application.ports.MonitorPresentationSnapshot
import com.freedom.application.ports.MonitorSnapshotRepository

/** Keeps the existing MonitorStore preference keys and snapshot format behind its port. */
internal class MonitorStoreSnapshotRepository(
    private val context: Context
) : MonitorSnapshotRepository {
    override fun updateAppPage(appLabel: String?, pageLabel: String?) {
        MonitorStore.updateAppPage(context, app = appLabel, page = pageLabel)
    }

    override fun save(snapshot: MonitorPresentationSnapshot) {
        MonitorStore.saveSnapshot(
            context = context,
            app = snapshot.appLabel,
            page = snapshot.pageLabel,
            nodes = snapshot.nodePreview,
            visitedCount = snapshot.visitedNodeCount,
            truncated = snapshot.truncated
        )
    }

    override fun read(): MonitorPresentationSnapshot {
        val snapshot = MonitorStore.snapshot(context)
        return MonitorPresentationSnapshot(
            appLabel = snapshot.app,
            pageLabel = snapshot.page,
            nodePreview = snapshot.nodes,
            visitedNodeCount = snapshot.visitedCount,
            truncated = snapshot.truncated
        )
    }
}
