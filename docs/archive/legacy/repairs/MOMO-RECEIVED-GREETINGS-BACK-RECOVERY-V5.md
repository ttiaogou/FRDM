# Momo received-greetings BACK recovery repair (v5)

## Symptom reproduced from the 2026-11-15 run

The first received-greeting conversation was opened and inspected successfully. BACK returned to the same received-greetings window (`windowId=2686`, generation 7), and fresh snapshots exposed the listview hierarchy. The archived node-tree captures nevertheless kept `activityClass=...ChatActivity` after return. The adapter subsequently requested the retained `listview` subtree and rejected it as `LIST_PAGE_IDENTITY_NOT_CONFIRMED`.

## Root cause

The v4 path required `activityClass == NewSayHiSessionListActivity` twice during targeted-subtree recovery:

- when the adapter set `receivedGreetingsListSubtreeConfirmed`;
- inside `hasReceivedGreetingsListSubtreeFingerprint`.

That duplicated a stale, event-derived Activity hint as a hard veto, despite the adapter having reacquired the exact retained listview target and the subtree carrying the expected list root and greeting-row structure.

## v5 changes

1. The adapter now passes subtree confirmation only when the received-list navigation state is already confirmed, the read came from the retained list target, and `confirmedTarget.window == tree.window` exactly. This preserves package, Activity metadata, window ID, and generation equality between the retained target and the fresh subtree.
2. The subtree fingerprint no longer treats Activity name as authoritative. It still requires a non-truncated Momo tree, a root `listview` RecyclerView that is visible/enabled/scrollable/non-empty, and visible greeting-row schema outside the nested `card_list` recommendation carousel.
3. Subtrees still cannot self-authorize: the inspector only classifies a subtree when the adapter supplies the prior-full-page-confirmation flag.
4. Added regression tests for stale `ChatActivity` metadata after BACK, no-self-authorization, and exclusion of recommendation-carousel-only rows.

No changes were made to OCR trust, message reply policy, message sending, or AI callback behavior.

## Validation performed

- Architecture verifier unit tests: 6 passed.
- Architecture boundary verification: passed.
- Compiled `MomoSayHiAccessibilityInspectorTest.kt` with the inspector and required pure Kotlin dependencies, then executed all 26 test methods through a lightweight local runner: 26 passed. The runner used minimal JUnit API shims and Android type stubs; it is not a Gradle test invocation.
- A focused Kotlin regression harness passed against the actual archived `page_019` tree (124 nodes; `windowId=2686`; generation 7; stale `ChatActivity` metadata). The subtree was accepted only with the confirmation flag; without it the result remained `UNKNOWN`.
- A second focused Kotlin fixture verified one greeting row is extracted and that recommendation-carousel descendants do not satisfy the greeting-row fingerprint.

## Validation not performed

The full Gradle test/build workflow (`:app:testDebugUnitTest`, `:app:assembleDebug`, and OCR plugin build) could not be run in this environment: the source archive has no Gradle wrapper, no system Gradle executable or Android SDK is installed, and the Gradle distribution host was unreachable from the container. No physical Android device was attached, so live Momo traversal after this repair has not been verified.
