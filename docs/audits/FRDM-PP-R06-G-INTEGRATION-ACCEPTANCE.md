# FRDM PP R06-G — AiReplyObservationRuntime 无状态职责迁移集成验收

## 基线与范围

- Repository: ttiaogou/FRDM; branch: PP; reviewed HEAD: `d0f385536a1387979b66c3dbbd8292f0f5c0a9ef`.
- R06-A source baseline: `560025b8cb856baa957987f3f759265158210c73`; Runtime blob `1c0c3bc1669f0cdc56e3abbb36c2f8c0be064f8a`.
- R06-F fix CI [46106430687](https://github.com/ttiaogou/FRDM/actions/runs/46106430687) completed/success: 155 architecture tests, resource gate, module unit tests, CodeQL, Freedom debug and shrink-only APK, OCR plugin APK.
- R06-A..F: 7 commits, 16 changed files, no removed files; Runtime 9,849 → 9,474 lines (net -375, not a total-project reduction).
- This stage changes only integration regression evidence and this report, not production sources.

## Stateless ownership and integration

| Step | Single owner | Methods |
| --- | --- | ---: |
| B | AiReplyPageEvidence.kt | 8 |
| C | PrivateMessageCandidateEvidence.kt | 4 |
| D | ReceivedGreetingsChatEvidence.kt | 4 |
| E | ChatMessageReadEvidence.kt | 7 |
| F | AiReplyPageDiagnostics.kt | 4 |

All 27 methods have no duplicate declaration in Runtime. R06-G adds an explicit integrated F→E→D→C→B inverse reconstruction to the frozen R06-A blob, cross-owner declaration checks, forbidden dependency checks, 19 retained Runtime state declaration checks and existing entry/read/log wiring predicates. Negative mutations cover duplicate owners, leaked state, direct gesture/log dependency and disconnected event/read gates. Existing per-stage inverse, mutation, R00/R01/R02, R05-I and M04 gates remain active; R01 pins the new integration test exactly.

The existing Service → event forwarder → AndroidAccessibilityEventPipeline → UnifiedAccessibilityEntry → RunSessionCoordinator path remains unchanged. Page reads remain coordinated by AiReplyPageReadCoordinator; asynchronous message/OCR requests by ChatMessageReadCoordinator; current binding, stale batch checks, task leases, recovery and logging callbacks retain their existing owners. Helpers return evidence, not operation permission. The R06-B verifier extraction changed only the method-region end anchor from relocated pageIdentityChanged to ownsTask; existing hard checks remain.

## Remaining responsibilities and limitations

- Runtime still owns orchestration, current session/greeting bindings, page observation scheduling/caches, recovery routes/checkpoints, follow-up admission and diagnostics deduplication. Existing private traversal executor, greeting controller and message coordinator remain authoritative for their respective state.
- stabilizeIncomingPageSnapshot and isSemanticallyUnconfirmed remain in Runtime because of cache mutation/default argument dependencies. logStructureEvidence and recordPrivateUnreadDiagnostic retain output side effects. No authorization to migrate R07 state here.
- No new duplicate production owner or file requiring deletion identified within R06 scope. Existing diagnostic chunk threshold is 3,500 characters; historical comment budget differs and is not changed.
- Source and CI evidence are not fresh device evidence. Real UI transitions, OCR callbacks, manual handoff and system recovery still require device regression. API-only work cannot inspect a local git working tree.
- This report records the verified R06-F baseline. Final R06-G acceptance depends on the actual CI for the R06-G commit.
