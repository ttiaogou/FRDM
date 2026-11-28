# FRDM P1 — P4 UI 操作权唯一授权出口

基线：`ttiaogou/FRDM` 的 `P1` 分支，SHA `8e2dcf6bdd5679bf9d199279fa3a4a7efb255a82`。

## 审计简报

- P3 已将 AI Reply 私聊/收到招呼与自动浏览统一到进程级 `TaskCoordinator`，并在运行代次推进时即时撤销旧任务及 UI 租约。之前 `AndroidAccessibilityGateway` 仍只使用 `isUiOperationOwned: () -> Boolean` 查询“是否存在任意任务”，无法区分浏览/私聊/招呼任务，更无法在平台写入时绑定精确租约。
- `AndroidAccessibilityGateway.performNodeAction` 在前置阶段检查布尔值，最终语义节点写入未持有精确任务租约；已验证导航返回及全局动作同样有“前置检查→平台提交”间隙。
- `performCurrentInputAction` 直接调用当前输入框 `FOCUS / SET_TEXT` 语义写入点，缺少统一租约校验。尽管上层 `MomoSocialAdapter` 在发送草稿时已有页面、标题和任务门禁，该平台入口仍应独立拒绝失效的异步调用。
- 手势已经通过 `GesturePolicyPipeline → GestureExecutionCoordinator` 把 `TaskLease` 绑定至每项请求，最终在同一任务锁内提交；Android 平台网关之前却只接受任意任务布尔判断。P4 将平台实际提交点也收敛到同一个授权出口，不修改轨迹算法或原手势安全验证。
- `LOCK_SCREEN` 由 Service 的人工审核安全响应触发，`INTERNAL_CALIBRATION` 为用户明确发起、限 Freedom 自身前台窗口的校准；它们不属于目标应用的自动化业务写入，维持各自的独立安全约束。

## P4 唯一授权路径

```
RunSessionCoordinator → RunGenerationClock
                         ↓
AppCompositionRoot → 唯一进程级 TaskCoordinator + UI Operation Lease
                         ↓
                  UiWriteAuthorization
        校验：当前运行模式 + task ID + runGeneration
        通过 TaskCoordinator.withCurrentLease 原子保护实际平台提交
                         ↓
AndroidAccessibilityGateway 的全部目标应用业务写出口
   ├─ BROWSE_NODE_ACTION：仅 ACCOUNT_BROWSE
   ├─ AI_COMPOSER：仅 AI_REPLY_PRIVATE_MESSAGES
   ├─ VERIFIED_AI_BACK：仅 AI 私聊/收到招呼
   ├─ GLOBAL_NAVIGATION：当前模式对应的唯一任务
   └─ AUTONOMOUS_GESTURE：当前模式对应的唯一任务
```

不允许“任何任务租约存在”代替精确业务权限。页面观察和业务规划只读阶段不调用权限出口；最终动作使用原有新鲜窗口、指纹、Activity/页面、就绪及防用户触摸检查，授权通过不等于页面验证完成。

`UiWriteAuthorization.permits()` 仅用于快速前置拒绝；实际写入必须使用 `withAuthorizedWrite()`。该调用由 `TaskCoordinator.withCurrentLease()` 保持任务租约锁，阻止其他线程在核验与提交之间释放/转移租约。拒绝许可不会自动重试、恢复 UNKNOWN 或改变业务阶段。

## 兼容范围及不改项

- 保留所有手势节奏、物理轨迹、延迟/反馈、图片/OCR、草稿分段与点击定位等既有算法。
- 不修改页面指纹、Activity 取证、安全断言、UNKNOWN 恢复事务、检查点或用户操作暂停规则。
- `performCurrentInputAction` 在最后的 FOCUS/SET_TEXT 点强制要求私聊租约，上层输入框标题/窗口二次读取仍保持。
- `LOCK_SCREEN` 只走原服务人工审核安全入口；校准只在 `GestureDispatchPurpose.INTERNAL_CALIBRATION` 且 Freedom 自有窗口为前台时允许，不将例外扩展至陌陌自动化。
- 架构检查锁定应用组装根中的共享 TaskCoordinator（手势、AI、Browse、RunSession、UI 授权共 5 个注入点）并拒绝平台入口重新使用宽泛布尔授权及绕过平台网关的直接节点/全局动作。

## 测试与上一轮遗留关系

- Core：无租约/无运行模式拒绝，Browse/AI 任务作用域隔离，私聊→招呼切换后不可修改私聊输入，代次推进/释放后拒绝晚到回调。
- tools/architecture：正向验证唯一入口；反向验证宽泛布尔门禁、输入和全局动作旁路、额外平台节点写出口及未注入共享授权器。
- 完整 CI：原 Core/Business/Gesture/App 单测、Freedom Debug APK、OCR Plugin Debug APK、CodeQL，均必须通过，不删测试、不降低断言。
- P3 已消除“代次推进但旧任务槽尚未清空”缺口，P4 在其上补上“平台写操作未绑定精确任务租约”缺口。后续恢复事务实例统一等问题只报告，不在本轮扩展。

## 未验证的环境行为

CI 无法代替真机上服务重连、崩溃重启、没有 Usage Access、窗口叠层、异步 OCR 回调和长手势中段取消等时序回归。Android 手势系统内部执行器在一次获准命令中的多段继续派发，仍由原有手势取消/回调/watchdog 机制管理；本阶段没有修改该受保护算法。
