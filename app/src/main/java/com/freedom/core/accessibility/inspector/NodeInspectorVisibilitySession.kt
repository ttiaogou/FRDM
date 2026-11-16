package com.freedom.core.accessibility.inspector

import com.freedom.core.accessibility.api.WindowRef
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Session-local hidden-node state shared by every inspector presentation.
 *
 * Hiding a node affects only the inspector: it never changes the target app or the detached
 * accessibility snapshot. Identity includes the captured window and structural node path, so
 * identical paths in different windows do not hide one another.
 */
object NodeInspectorVisibilitySession {
    private val lock = Any()
    private val hidden = linkedSetOf<NodeInspectorIdentity>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Starts a fresh global-capture inspection session. */
    fun beginSession() = clearHiddenNodes()

    /** Ends the current inspection session. */
    fun endSession() = clearHiddenNodes()

    fun hide(identity: NodeInspectorIdentity): Boolean {
        val changed = synchronized(lock) { hidden.add(identity) }
        if (changed) notifyListeners()
        return changed
    }

    fun hideAll(identities: Collection<NodeInspectorIdentity>): Int {
        val added = synchronized(lock) {
            identities.count { hidden.add(it) }
        }
        if (added > 0) notifyListeners()
        return added
    }

    fun isHidden(identity: NodeInspectorIdentity): Boolean =
        synchronized(lock) { identity in hidden }

    fun hiddenIdentities(): Set<NodeInspectorIdentity> =
        synchronized(lock) { hidden.toSet() }

    fun hiddenPaths(window: WindowRef): Set<List<Int>> =
        hiddenIdentities().asSequence()
            .filter { it.window == window }
            .mapTo(linkedSetOf()) { it.path.childIndices.toList() }

    /** Registers a lightweight UI invalidation callback and returns its unsubscriber. */
    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun clearHiddenNodes() {
        val changed = synchronized(lock) {
            val hadHidden = hidden.isNotEmpty()
            hidden.clear()
            hadHidden
        }
        if (changed) notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching(listener) }
    }
}
