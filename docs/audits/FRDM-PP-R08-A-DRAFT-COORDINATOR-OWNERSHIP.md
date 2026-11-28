# FRDM PP R08-A — AiReplyDraftCoordinator 职责、状态所有权及模块化迁移专项

## Baseline and boundary
- Repository ttiaogou/FRDM, PP, 109ec5868ce47d6ec2d7c7487de4e1c7bc95db6f; R07-I CI 46116422705 completed/success.
- Draft coordinator baseline blob 21d90c5e10b1f4edd858c0b9fc48a932e8dfe928, 2559 lines; after this scoped migration 2326 lines. Business module and public class/constructor/method contracts unchanged.
- AppCompositionRoot injects model configuration/consent, EngineBackedAiDecisionGateway, input/send ports, shared scheduler, current TaskCoordinator lease and reply/segment repositories. ObservationRuntime calls the draft port and receives pre-send model failure; that callback cannot itself authorize UI. Model provider performs model I/O only; policy registry determines business context scope. R02 draft input trace, VisibleDraftFlowTest and existing regression fixtures unchanged.

## State and responsibility audit
| Group | Owner / decision |
| --- | --- |
| Configuration, consent, personalization, model decision | Existing repositories, policy registry and gateway; no change. |
| Request admission, pending/awaitingUserSend atomics, generation, shared lock, active request/target/visit | DraftCoordinator; cross-thread request ownership and model/input/send admission coupled; preserve. |
| Processed keys, failed requests, verified empty-input retries | DraftCoordinator; coupled to durable tryClaim and retry admission, not diagnostic dedup. Preserve. |
| Pending send confirmation, next message segment, send-control retry ticket, last receipt, read-only recovered match | DraftCoordinator; actual port and journal transaction, single shared lock. Preserve. |
| Durable task/segment status | ReplyTaskRepository / ReplySegmentTaskRepository; existing transition objects remain authoritative. |
| Diagnostics only | Existing AiModelCallDiagnostics.kt now contains the unchanged AiReplyTraceContract and instance-local AiReplyRequestTrace implementation owning modelLifecycleTrace and emittedSendTraceKeys. No new architecture layer or global state; same original lock, no extra scheduler/lease/repository/UI access. |

## Implemented
- Extract fixed trace formatting contract byte-for-byte; move model lifecycle and send diagnostic de-duplication state and emission methods into existing diagnostics source file.
- Reset/begin/finish diagnostic methods execute at their original lock-held positions; generation checks, terminal-once, cancellation sequence, bounded dedup, callback exception isolation and actual emission locations unchanged. Pass existing caps rather than duplicate constants.
- Keep a thin raw send trace forwarding overload to preserve existing PendingSendConfirmation private-type mapping, all send journal ordering and call sites. No changes to model retries, send proof algorithm, wrap evidence, parameters, safety assertions, public API or durable record format.
- Exact inverse reconstructs both original source blobs. Add negative tests for stale/terminal/reset/dedup and lost callback isolation. R00 S1 reviewed hash pins only, R01 evidence pins; manifest/S0/R02 unchanged.

## Remaining candidates / limitations
- DraftCoordinator still coordinates actual send/unknown recovery, verified next-segment composer, retry admission and lock-held durable claims. Do not split these into competing transaction owners or relax synchronization merely for line count. Further extraction of pure send-evidence functions would require separate exact migration and dedicated regression audit; no algorithm change authorized here.
- Existing class comment says sending is outside this stage, while code contains active send and confirmation methods; stale comment recorded, not changed. Separate diagnostic-only hash vs durable send-evidence hashes; do not combine blindly. No safely deletable complete file established.
- API-only remote diff/branch review cannot prove local worktree status; full CI and real-device end-to-end verification distinct. No claim of device validation.
