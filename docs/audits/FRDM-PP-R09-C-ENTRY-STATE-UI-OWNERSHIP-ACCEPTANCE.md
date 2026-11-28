# R09-C — 统一入口、状态唯一性及操作权专项验收

## 基线及范围
- Repository ttiaogou/FRDM, PP, 3671b85bed46e2e7e571cabb5c890b3dfa18fa9d; CI 46127938010 all five jobs success, 204 Python architecture tests. User waived local worktree check; remote compare and HEAD verification required. Read AGENTS and active 01/02/03/04/10 rules. R09-B candidate showNodeMoreMenu remains explicitly deferred because protected UI contract failed on deletion; no B-scope change.
- Audit against actual selected source, R09-A/P0-P7 and R07 state evidence. No new production coordinator, state owner, page recognition or operation grant. No proven runtime defect in the examined routes warrants production changes.

## Authority and event paths
| Boundary | Actual owner and evidence |
| --- | --- |
| Raw Android callback | FreedomAccessibilityService.onAccessibilityEvent -> AndroidAccessibilityEventForwarder.accept; filtering and touch/window evidence before pipeline, no business dispatch in callback |
| Normalized target/global touch/activity | AndroidAccessibilityEventPipeline -> UnifiedAccessibilityEntry -> AppCompositionRoot -> RunSessionCoordinator -> selected ModeRuntimePort; navigation attribution through same gateway, not a second task grant |
| Read-only observation | PageObservationCoordinator and independent entry page snapshot; foreign window metadata wake-up not business mutation |
| Run authorization and page route | RunStateRepository persisted user intent; RunSessionCoordinator single pageRuntime; STARTING/UNCLASSIFIED/STOPPING are not READY write permission |
| Process generation and exclusive task | AppCompositionRoot synchronized lazy RunGenerationClock, TaskCoordinator and UiOperationOwnershipCoordinator; exact lease/revision bound together |
| Feature capability | AiReplyTaskOwnership delegates TaskExecutionController; AccountBrowseModeRuntimeAdapter uses same process coordinator; local fixture-only fallback is not production grant |
| State | existing R07-I 63 historical declarations (62 live, one retired), private traversal/visit, greeting flow, OCR read, paired quota, distinct recovery proof; R08 browse runtime owns domain state; no duplicate authoritative grant found in scoped audit |
| Final UI mutations | UiWriteAuthorization validates scope, current route, exact lease and generation under task monitor; production root also excludes greeting/private recovery and private follow-up blocks; gesture runtime validates execution context and submits task-bound gestures under withCurrentLease; user calibration/safety actions remain separately gated |
- Independent page switches: STOPPING published before source checkpoint save, generation advanced and old task/UI revoked before pause, fresh observation required before target start; task reservation does not grant write while route unverified. Same-generation verified AI sibling handoff needs explicit admission. Old release/rollback cannot revoke newer exact lease.
- Existing manual interaction evidence and observation pathways are not independent write authority. Do not change manual-handling policy, target fingerprints, gesture or timing. R09-D will audit recovery, callbacks, checkpoint and delayed proof/generation lifecycle in depth.

## Strengthened verification
- New Python whole-production Kotlin source checks use existing scanner/masking: exact named session event dispatch call/declaration counts, no external callable reference bypass, exact task/UI capability constructors and named authoritative slots, exact entry SAM construction/declaration, five shared production injections, route publication boundaries and required recovery/write gate wiring. Add negative mutations for extra entry/lease/slot and removed live gate. This is lexical guard evidence, not a compiler-level call graph or proof of arbitrary renamed semantic duplication.
- New Kotlin test uses actual RunSessionCoordinator, TaskCoordinator, UiOperationOwnershipCoordinator and UiWriteAuthorization (fake mode runtimes only). Covers all six independent transitions across private/greetings/browse; current scope matrix, UNKNOWN hold, write denial during checkpoint/pause, fresh generation, exact destination lease, stale source denial and user stop. Also same-generation verified sibling handoff, rejected unverified handoff, replacement capability and stale rollback/release. No physical Android write or real-device test; root recovery-specific exclusions are checked by source evidence, not simulated by this fixture.
- All previous gates/tests remain unchanged. R01 records exact hashes of the two new tests only; no S0/S1 changes, no manifest changes. Full latest CI/APK verification pending until actual result. R09-F real-device evidence remains required.
