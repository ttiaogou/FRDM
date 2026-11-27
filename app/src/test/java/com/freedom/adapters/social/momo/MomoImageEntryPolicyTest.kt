package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MomoImageEntryPolicyTest {
    private val pkg = "com.immomo.momo"
    private val entry = MomoImageEntryEvidence(
        MomoImageEntryPolicy.ENTRY_ID, MomoImageEntryPolicy.PARENT_ID,
        "android.widget.ImageView", pkg, true, true, true, 10, 20, 40, 50
    )

    @Test fun acceptsUniqueValidEntry() {
        assertEquals(entry, MomoImageEntryPolicy.resolve(pkg, listOf(entry)))
    }

    @Test fun ownedChatEntryDoesNotRequireFunctionLayout() {
        val relocated = entry.copy(parentId = "")
        assertEquals(relocated, MomoImageEntryPolicy.resolveOwned(pkg, listOf(relocated)))
        assertNull(MomoImageEntryPolicy.resolveOwned(pkg, listOf(relocated, relocated)))
        assertNull(MomoImageEntryPolicy.resolveOwned(pkg, listOf(relocated.copy(clickable = false))))
        assertNull(MomoImageEntryPolicy.resolveOwned(pkg, listOf(relocated.copy(packageName = "other"))))
    }

    @Test fun rejectsMissingOrAmbiguousEntry() {
        assertNull(MomoImageEntryPolicy.resolve(pkg, emptyList()))
        assertNull(MomoImageEntryPolicy.resolve(pkg, listOf(entry, entry)))
    }

    @Test fun rejectsWrongHierarchyOrUnavailableEntry() {
        listOf(entry.copy(parentId = "other"), entry.copy(visible = false),
            entry.copy(enabled = false), entry.copy(clickable = false),
            entry.copy(right = entry.left), entry.copy(packageName = "other"),
            entry.copy(className = "android.widget.Button"), entry.copy(viewId = "other")
        ).forEach { assertNull(MomoImageEntryPolicy.resolve(pkg, listOf(it))) }
    }
}
