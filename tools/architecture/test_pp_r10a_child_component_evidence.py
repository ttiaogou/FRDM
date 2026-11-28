"""R10-A authorized component-driven child proof and exact historical inverse."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADAPTER = "app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt"
CONTROLLER = "app/src/main/java/com/freedom/adapters/social/momo/MomoInterceptionTransactionController.kt"
POLICY = "app/src/main/java/com/freedom/adapters/social/momo/MomoInterceptionRecoveryPolicy.kt"
BEFORE = {"app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt":"8af64ca8d467e4d1eea9e8b2a7cdd411b2cee95f","app/src/main/java/com/freedom/adapters/social/momo/MomoInterceptionTransactionController.kt":"cdf761e0f4a221fc289d0621bbd309900e84cc9e","app/src/main/java/com/freedom/adapters/social/momo/MomoInterceptionRecoveryPolicy.kt":"1dea6b1c70463992922a6b76b3e9934bb12299e5","app/src/test/java/com/freedom/adapters/social/momo/MomoInterceptionRecoveryPolicyTest.kt":"cceade363e6d5f10a245154fccc3cbe09cdbc111"}
AFTER = {"app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt":"5341a89da104101fe1430c0f6ba5477d94c048c7","app/src/main/java/com/freedom/adapters/social/momo/MomoInterceptionTransactionController.kt":"5ff5a8e2e2dc52f9ed2d0572e6a9b01fdda152d4","app/src/main/java/com/freedom/adapters/social/momo/MomoInterceptionRecoveryPolicy.kt":"90bbedf6e20a77756a9eb40518c35744aa27c137","app/src/test/java/com/freedom/adapters/social/momo/MomoInterceptionRecoveryPolicyTest.kt":"8f5ce67a1ca6715f0364ce394bc22cbc49ce54f9"}
EDITS = {"app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt":[[5829,["                        \"interception-layout\" -> interceptionTransaction.interceptionSettingsPage(root, child) &&","                            interceptionTransaction.interceptionSettingsChecked(root, child) != true","                        \"interception-switch\" -> interceptionTransaction.interceptionChecked(root) == false","                        \"interception-dismiss\" -> interceptionTransaction.interceptionChecked(root) == true","                        \"interception-back\" -> interceptionTransaction.interceptionSettingsPage(root, child) &&","                            (interceptionTransaction.interceptionSettingsChecked(root, child)","                                ?: child.interceptionState.switchVerifiedOn)"],["                        \"interception-layout\" -> interceptionTransaction.interceptionSettingsChecked(root, child) == false","                        \"interception-switch\" -> interceptionTransaction.interceptionChecked(root) == false","                        \"interception-dismiss\" -> interceptionTransaction.interceptionChecked(root) == true","                        \"interception-back\" -> interceptionTransaction.interceptionSettingsChecked(root, child) == true"]],[5672,["                    (child.stage == PrivateFollowUpStage.WAIT_FREEDOM && freshMediaSnapshot(live)?.let {","                        MomoFreedomAlbumInspector.ownedSelectionTarget(it, targetPackage) != null","                    } == true) ||","                    (child.stage == PrivateFollowUpStage.WAIT_SELECTION && album != null &&","                        live.packageName == album.packageName && live.windowId == album.windowId &&","                        live.generation >= album.generation && freshMediaSnapshot(live)?.let {","                            MomoFreedomAlbumInspector.ownedSendTarget(it, targetPackage) != null","                        } == true) ||"],[]],[485,["                            // A child popup can coexist with the underlying chat structure.","                            // Reuse its exact active-transaction evidence before classifying a chat.","                            inspectPrivateFollowUpOwnedPage(root, window) ||","                                (chat.pageType == MomoChatPageType.CHAT_DETAIL_CANDIDATE &&","                                    originalPrivateFollowUpChat(privateFollowUp) &&","                                    chat.chatTitleText == privateFollowUp.target.expectedChatTitle)"],["                            if (chat.pageType == MomoChatPageType.CHAT_DETAIL_CANDIDATE) {","                                originalPrivateFollowUpChat(privateFollowUp) &&","                                    chat.chatTitleText == privateFollowUp.target.expectedChatTitle","                            } else inspectPrivateFollowUpOwnedPage(root, window)"]]],"app/src/main/java/com/freedom/adapters/social/momo/MomoInterceptionTransactionController.kt":[[131,["            MomoInterceptionRecoveryPolicy.Page.SETTINGS_OFF,","            MomoInterceptionRecoveryPolicy.Page.SETTINGS_STATE_UNKNOWN -> child.interceptionState.interceptionSettingsObserved = true"],["            MomoInterceptionRecoveryPolicy.Page.SETTINGS_OFF -> child.interceptionState.interceptionSettingsObserved = true"]],[72,["        if (!interceptionSettingsPage(root, child)) return null"],["        val title = child.target.expectedChatTitle ?: return null","        if (interceptionNodeBounds(root, \"tv_user_name\", \"android.widget.TextView\", text = title) == null) return null"]],[65,["    /** The admitted parent owns the contact; the child settings row is sufficient live page evidence. */","    fun interceptionSettingsPage(root: AccessibilityNodeInfo, child: PrivateFollowUpTransaction): Boolean =","        child.stage != PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS &&","            interceptionNodeBounds(root, \"setting_layout_harass\", \"android.widget.LinearLayout\", clickable = true) != null"],["    /** Settings-page identity does not depend on the switch/status text being readable yet. */","    fun interceptionSettingsPage(root: AccessibilityNodeInfo, child: PrivateFollowUpTransaction): Boolean {","        val title = child.target.expectedChatTitle ?: return false","        return interceptionNodeBounds(root, \"tv_user_name\", \"android.widget.TextView\", text = title) != null &&","            interceptionNodeBounds(root, \"setting_layout_harass\", \"android.widget.LinearLayout\", clickable = true) != null","    }"]]],"app/src/main/java/com/freedom/adapters/social/momo/MomoInterceptionRecoveryPolicy.kt":[[25,["        // A confirmed row can open the popup even without a status label. After a","        // positive switch read, a missing status label does not invalidate that proof.","        Page.SETTINGS_STATE_UNKNOWN -> Decision(if (verifiedOn) Action.BACK else Action.OPEN_CONTROL, verifiedOn)","        Page.SETTINGS_IDENTITY_UNCONFIRMED,"],["        Page.SETTINGS_IDENTITY_UNCONFIRMED, Page.SETTINGS_STATE_UNKNOWN,"]]],"app/src/test/java/com/freedom/adapters/social/momo/MomoInterceptionRecoveryPolicyTest.kt":[[77,["    @Test fun missingSettingsStatusOpensControlButNeverEnablesWithoutPopupProof() {","        val available = policy.reconcile(Page.SETTINGS_STATE_UNKNOWN, false)","        assertEquals(Action.OPEN_CONTROL, available.action)","        assertFalse(available.verifiedOn)","        assertEquals(Action.WAIT, policy.reconcile(Page.POPUP_SOURCE_UNCONFIRMED, available.verifiedOn).action)","        assertEquals(Action.WAIT, policy.reconcile(Page.POPUP_STATE_UNKNOWN, available.verifiedOn).action)","        val enabled = policy.reconcile(Page.POPUP_ON, available.verifiedOn)","        assertTrue(enabled.verifiedOn)","        assertEquals(Action.DISMISS, enabled.action)","        val back = policy.reconcile(Page.SETTINGS_STATE_UNKNOWN, enabled.verifiedOn)","        assertEquals(Action.BACK, back.action)","        assertTrue(back.verifiedOn)","        assertEquals(Action.COMPLETE, policy.reconcile(Page.ORIGINAL_CHAT, back.verifiedOn).action)","        assertFalse(policy.reconcile(Page.SETTINGS_OFF, back.verifiedOn).verifiedOn)","    }",""],[]],[58,["            Page.SETTINGS_STATE_UNKNOWN to Action.OPEN_CONTROL,"],["            Page.SETTINGS_STATE_UNKNOWN to Action.WAIT,"]],[43,["        for (page in listOf(Page.SETTINGS_IDENTITY_UNCONFIRMED,"],["        for (page in listOf(Page.SETTINGS_IDENTITY_UNCONFIRMED, Page.SETTINGS_STATE_UNKNOWN,"]]]}

def restore_r10a_source(path, source):
    from tools.architecture.test_pp_r00_freeze import git_blob
    lines = source.split("\n")
    for index, current, previous in EDITS[path]:
        if lines[index:index + len(current)] != current:
            raise AssertionError("R10-A inverse anchor changed: " + path)
        lines[index:index + len(current)] = previous
    restored = "\n".join(lines)
    if git_blob(restored.encode("utf-8")) != BEFORE[path]:
        raise AssertionError("R10-A original blob not reconstructed: " + path)
    return restored


class R10aChildComponentEvidenceTest(unittest.TestCase):
    def test_exact_authorized_diff_and_original_sources(self):
        from tools.architecture.test_pp_r00_freeze import git_blob
        for path in EDITS:
            source = (ROOT / path).read_text(encoding="utf-8")
            self.assertEqual(AFTER[path], git_blob(source.encode("utf-8")))
            self.assertEqual(BEFORE[path], git_blob(restore_r10a_source(path, source).encode("utf-8")))

    def test_live_child_proof_and_unchanged_final_authority(self):
        adapter = (ROOT / ADAPTER).read_text(encoding="utf-8")
        owner = (ROOT / CONTROLLER).read_text(encoding="utf-8")
        settings = owner.split("fun interceptionSettingsPage(", 1)[1].split("private fun observeInterceptionPage(", 1)[0]
        self.assertNotIn("tv_user_name", settings)
        self.assertNotIn("expectedChatTitle", settings)
        self.assertIn('interceptionNodeBounds(root, "setting_layout_harass", "android.widget.LinearLayout", clickable = true)', settings)
        self.assertIn("interceptionSettingsPage(root, child)", settings)
        self.assertIn("MomoInterceptionRecoveryPolicy.Page.SETTINGS_STATE_UNKNOWN -> child.interceptionState.interceptionSettingsObserved = true", owner)
        entry = adapter.split("fun inspectUnifiedEntryPage(", 1)[1].split("fingerprint to ownedNestedPage", 1)[0]
        self.assertIn("matches == 0 && currentTaskId != null", entry)
        self.assertIn("inspectPrivateFollowUpOwnedPage(root, window) ||", entry)
        self.assertIn("originalPrivateFollowUpChat(privateFollowUp)", entry)
        route = adapter.split("private fun inspectPrivateFollowUpOwnedPage(", 1)[1].split("override fun beginPrivateFollowUp(", 1)[0]
        for token in ("privateFollowUpController.followUpCurrent(child)", "accessibilityGateway.currentGestureWindow() == live",
                      "interceptionTransaction.interceptionSettingsPage(root, child)",
                      "MomoFreedomAlbumInspector.ownedSelectionTarget(it, targetPackage)",
                      "MomoFreedomAlbumInspector.ownedSendTarget(it, targetPackage)",
                      "live.windowId == album.windowId", "live.generation >= album.generation"):
            self.assertIn(token, route)
        permit = adapter.split("private fun <T : Any> submitVerifiedAlbumTap(", 1)[1].split("internal fun captureImageSendBaseline(", 1)[0]
        for token in ("isBoundAiReplyUiOperationOwned(lease)", "executionContext.taskLease != lease",
                      "resolve(freshRoot, freshWindow) == evidence", "permit.matchesCommand(commandId)",
                      "permit.confirmsCurrentTarget()"):
            self.assertIn(token, permit)

    def test_inverse_rejects_missing_live_switch_proof_and_component_route(self):
        for path, old, new in (
            (CONTROLLER, "currentInterceptionChecked() != true", "false"),
            (ADAPTER, "inspectPrivateFollowUpOwnedPage(root, window) ||", "true ||"),
            (ADAPTER, "MomoFreedomAlbumInspector.ownedSendTarget(it, targetPackage) != null", "true"),
            (POLICY, "Page.POPUP_OFF -> Decision(Action.ENABLE, false)", "Page.POPUP_OFF -> Decision(Action.COMPLETE, true)"),
        ):
            source = (ROOT / path).read_text(encoding="utf-8")
            self.assertIn(old, source)
            with self.subTest(path=path, token=old), self.assertRaises(AssertionError):
                restore_r10a_source(path, source.replace(old, new, 1))
