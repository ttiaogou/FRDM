package com.freedom.core.logging

/** Priority assigned by the caller after the current page has been identified. */
enum class NodeTreeCapturePriority {
    /** Received-greetings, profile, and chat-detail pages required to explain the workflow. */
    KEY_PAGE,
    /** General navigation and ordinary list snapshots. */
    STANDARD
}

enum class NodeTreeCaptureAdmission {
    ALLOWED,
    STANDARD_QUOTA_REACHED,
    TOTAL_LIMIT_REACHED
}

/** Keeps ordinary snapshots from consuming the capacity reserved for key workflow pages. */
object NodeTreeCaptureBudgetPolicy {
    fun evaluate(
        priority: NodeTreeCapturePriority,
        totalCaptured: Int,
        standardCaptured: Int
    ): NodeTreeCaptureAdmission {
        if (totalCaptured >= RunLogBudgetPolicy.MAX_SAMPLE_NODE_TREE_SAMPLES) {
            return NodeTreeCaptureAdmission.TOTAL_LIMIT_REACHED
        }
        if (priority == NodeTreeCapturePriority.STANDARD &&
            standardCaptured >= RunLogBudgetPolicy.MAX_SAMPLE_STANDARD_NODE_TREE_SAMPLES
        ) {
            return NodeTreeCaptureAdmission.STANDARD_QUOTA_REACHED
        }
        return NodeTreeCaptureAdmission.ALLOWED
    }
}
