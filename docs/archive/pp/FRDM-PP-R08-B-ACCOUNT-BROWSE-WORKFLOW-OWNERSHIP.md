# FRDM PP R08-B — AndroidAccountBrowseWorkflow 职责、状态所有权及模块化迁移专项

## Baseline
- Repository ttiaogou/FRDM; branch PP; e9965610a29cc5936589e16b6ffff3cbe43af41b; R08-A CI 46117197647 completed/success.
- Workflow baseline blob 2c35e3d96bbb595e0bf08d3c1c1257f9ef7b0da6, 3072 lines -> 3036 lines. Runtime baseline blob d0fa5ac24dcd1d06981853c058b3b7bd3500d8de. No new production files or architecture layer.
- Unified service entry -> normalized pipeline -> RunSessionCoordinator -> AccountBrowseModeRuntimeAdapter -> AndroidAccountBrowseWorkflow; shared PageObservationCoordinator and recovery route, existing TaskCoordinator lease and GestureApi continue unchanged.

## Ownership audit
| Responsibility | Actual owner / disposition |
| --- | --- |
| Phase, session fields, checkpoints and business scheduling | AccountBrowseRuntime / AccountBrowseStateMachine / AccountBrowseSessionStateOwner / AccountBrowseScheduler; immutable snapshot with command port; no second storage. |
| Recovery transaction and durable observation | DynamicPageRecovery and shared recovery manager, existing checkpoint adapters/repositories. No new recovery mechanism. |
| Semantic page evidence | SocialBrowsePort; no Android nodes passed into business. |
| Four adapter-local lifetimes | gestureDispatchSyncTask, overlayPassthroughWatchdogTask, browseCallbackEpoch, activeBrowseGestureRequestId remain platform execution state in workflow; not business phase/session truth. |
| Navigation / scroll / popup / recovery executors | Existing stateless platform helpers retained; no new task owner. |
| Image visit initialization, feed-return sample update and decision, interaction context projection | Moved into existing AccountBrowseRuntime, the real session owner. |

## Changes and behavior protection
- Image initialization retains clear-before-read, image-only guard, confirmed index requirement, plan computation and exact deadline, single-image finish, and original log callback. Adapter supplies same read function; no gesture executes here.
- Feed return retains original inspected feed/position matcher in adapter, exact consecutive-sample update then deadline decision in Feature owner. Phase transition/log/scheduling and existing BACK avoidance remain at original call sites.
- Context projection reads phase and existing session fields in original order and invokes the same semantic activity predicates. No new snapshot storage.
- All callback epoch/request ID and overlay restoration assertions preserved. Existing boundary test follows relocated code, checks actual delegations and retains original safety assertions; exact inverse reconstructs prior workflow, Runtime and test. R00 reviewed S1 pins and R01 evidence pins only; S0, manifest, R02 trace oracle unchanged.

## Remaining candidates and limitations
- phase/accountBrowsePhase and session/accountBrowseSession are forwarding accessors; phase setter also stamps dynamic-tab wait start before transition. Do not delete as one mechanical operation.
- Cross-phase callback and watchdog state stays adapter-local; moving to Feature owner would conflate platform lifetime with durable business state. The remaining image advancement/gesture callback and refresh/navigation orchestration require a separate cohesive audit rather than unapproved algorithm changes.
- No complete file confirmed safe to delete. No new timer, gesture route, fingerprint, delay, parameter or durable schema. API-only remote diff cannot establish local worktree state. Full CI and real-device behavior are separate acceptance boundaries.
