# FRDM PP R08-C — PrivateMessagesTraversalTaskExecutor 职责、状态所有权及模块化迁移专项

## Baseline and scope
- Repository ttiaogou/FRDM, branch PP, 58048aa6801bebfbaab01765e4f3a19d2bba1fa5; R08-B CI 46118418004 completed/success.
- Traversal executor baseline 1d12a483673719255862b5c512c8db9fd9219fad, 2017 lines -> 1986 lines. Existing ChatSessionManagementPolicy baseline fb8d97a3a33e6c576cc636f7eb95b00f838d6167.
- Unified entry -> AiReplyObservationRuntime -> traversal executor (business state), PrivateMessagesTaskExecutor (fresh row/window and gesture admission), checkpoint repository and shared recovery manager. No new owner, Android dependency, scheduler or state machine.

## Actual ownership
| Group | Actual owner / decision |
| --- | --- |
| Unread boundary, phase, selected/attempted identities, pending scroll and viewport cursor | Traversal executor remains sole mutable owner. |
| Checkpoint serialization/restore, unfinished visits, same-round recovery and identity-return reconciliation | Traversal executor; durable checkpoint repository remains unchanged. |
| R07-C/F visit binding, identity capture timeout, return retry, confirmed receipt and follow-up child | Existing traversal executor, with Runtime coordinating external scheduling and ledger effects. No duplicate storage. |
| Fresh row and lease checks, action submission | PrivateMessagesTaskExecutor and existing GestureApi; not policy. |
| Pure viewport identity/geometry keys, collection position encode/decode and boundary/scroll evidence | Relocated to existing ChatSessionManagementPolicy, along with single collection prefix. No mutable state in relocated code. |

## Implementation and verification
- Six pure methods relocated: visibleSessionKeys, collectionPositionKey, collectionPositionFromKey, orderedIdentityKeys, scrollPositionKeys, boundaryConfirmationKeys. Five thin delegates retain existing executor API and private call sites; collectionPositionKey stays private to policy. Existing collectionRows delegates to validatedCollectionRows.
- Preserve exact key format, bottom-to-top order, complete-row filter, range validation and legacy fallback. No geometry reused as authorization. Stateful recordVerifiedViewport and checkpoint/restoration logic remain in executor. No changes to unread admission, actual traversal, return continuity or retry logic.
- Exact inverse reconstructs both pre-migration source blobs. R07-F first restores R08-B executor then applies original historical hash and inverse; no historical assertion removed. R00 and R01 exact pins only; S0 and original R02 traces unchanged. Direct JVM decoder/projection tests added. Existing Kotlin tests unchanged.
- Remaining: forwarding viewport methods are compatibility candidates; isRestoredViewportCompatible is explicitly legacy, callers/reflection not fully audited. Existing disposable recovery probe is not a second live task owner; keep. Repeated reset blocks intentionally not deduplicated without distinct-lifetime audit. No full file confirmed safe for deletion.
- Local worktree and real-device flow not verified via GitHub API. Full CI is separate from pre-commit verification.
