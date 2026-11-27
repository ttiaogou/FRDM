package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R04HelperConvergenceTest {
    @Test fun sharedGeometryKeepsInclusiveEdgesAndRejectsEmptyBounds() {
        val policy = MomoSayHiAvatarTargetPolicy
        val outer = ScreenRect(10, 20, 100, 200)
        assertTrue(policy.contains(outer, outer))
        assertTrue(policy.contains(outer, ScreenRect(10, 20, 20, 30)))
        assertFalse(policy.contains(outer, ScreenRect(9, 20, 20, 30)))
        assertFalse(policy.contains(outer, ScreenRect(10, 20, 10, 30)))
        assertFalse(policy.contains(ScreenRect(0, 0, 0, 0), outer))
        assertTrue(policy.isWithin(NodePath(listOf(0, 2)), NodePath(listOf(0))))
        assertTrue(policy.isWithin(NodePath(listOf(0)), NodePath(listOf(0))))
        assertTrue(policy.isWithin(NodePath(listOf(0)), NodePath(emptyList())))
        assertFalse(policy.isWithin(NodePath(listOf(0)), NodePath(listOf(0, 2))))
        assertFalse(policy.isWithin(NodePath(listOf(0, 2)), NodePath(listOf(0, 3))))
    }

    @Test fun diagnosticFormattingKeepsExactFieldsOrderAndLimit() {
        val inspector = MomoSayHiNavigationEvidenceInspector()
        val evidence = MomoNavigationTargetEvidence(
            path = listOf(0, 2), className = "android.widget.TextView", viewId = "fixture:id/title",
            bounds = ScreenRect(10, 20, 30, 40), visible = true, enabled = true,
            clickable = false, checkable = false, checked = false)
        val expected = "path=0/2;class=TextView;id=fixture:id/title;bounds=10,20,30,40;" +
            "clickable=false;enabled=true;checkable=false;checked=false;clickAncestor=NONE;" +
            "clickAncestorId=;clickAncestorClass=NONE;listLabel=false;interceptRelated=false"
        assertEquals("NONE", inspector.describeNavigationCandidates(emptyList(), 8))
        assertEquals(expected, inspector.describeNavigationCandidates(List(9) { evidence }, 1))
        assertEquals(List(8) { expected }.joinToString(";"), inspector.describeNavigationCandidates(List(9) { evidence }, 8))
    }
}
