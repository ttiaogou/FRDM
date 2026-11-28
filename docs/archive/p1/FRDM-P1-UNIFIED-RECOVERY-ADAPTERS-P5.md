# FRDM P1 — P5 恢复适配器接口与注册统一

- 仓库：`ttiaogou/FRDM`；目标分支：`P1`。
- 基线提交：`f2d1f9b5deb26458458ed014bc08667c77815ab4`；上一阶段 P4 的 Push CI 为 Success。
- 范围：恢复**适配器接口/注册**，不收敛 P6 的恢复事务实例及调度器所有权。

## 审计简报

现有系统有两种相互补充的适配器，不应混淆：
1. `PageRecoveryRouteAdapter`：无障碍页面观察的**只读路由识别**与显式业务检查点保存钩子。消息列表、收到招呼、聊天详情、动态浏览共 4 个实现；都通过 `PageObservationCoordinator.registerFingerprintRoute(adapter.asRoute())` 注册为普通指纹路由，但协调器只存了匹配函数，丢失了任务归属、主/次恢复路由、检查点入口等适配器信息。AI Reply 另有本地适配器列表回退以及任务/路由的正反向 `when` 表，存在第二套查找权威；浏览业务独立注册。
2. `RecoveryCheckpointAdapter<T>`：消息列表、收到招呼、动态浏览已使用**相同的泛型映射接口**，把本业务仓库的原始检查点映射为 `RecoveryCheckpoint`；仍由三个业务各自提供已确认页面结构证据、原阶段/游标和检查点存储。所有检查点恢复必须经过既有 `RecoveryCheckpointVerificationCoordinator` 与两次独立新鲜快照。这一层不存在第二套接口，不在本阶段重构或合并存储，以免改变安全断言。

审计还发现不同业务路由有可能覆盖相同的页面观察：收到招呼的旧匹配规则仅要求非空 adapter 指纹，能够在通用全局匹配表里先于动态页路由命中。这是**跨业务路由抢占风险**，并非要改写页面指纹或业务匹配器。P5 改为按调用方所属任务集合匹配同一个注册表，既保留原匹配逻辑，也避免其他运行模式的路由夺取当前页面证据。

## 统一接口与注册机制

```
AppCompositionRoot (P2 同一服务级 PageObservationCoordinator)
   └── PageObservationCoordinator.registerRecoveryAdapter(PageRecoveryRouteAdapter)
       ├── MessageListRecoveryRouteAdapter          taskId=AI_REPLY_PRIVATE_MESSAGES, 主路由
       ├── ReceivedGreetingsRecoveryRouteAdapter    taskId=AI_REPLY_RECEIVED_GREETINGS, 主路由
       ├── ChatDetailRecoveryRouteAdapter           taskId=AI_REPLY_PRIVATE_MESSAGES, 次级路由
       └── DynamicFeedRecoveryRouteAdapter          taskId=ACCOUNT_BROWSE, 主路由
           ↓
       PageRecoveryAdapterRegistry
         - adapterForRoute(routeId)
         - primaryForTask(taskId)
         - match(freshFingerprint, allowedTaskIds)
         - unregister(routeId) / 同路由 ID 的原子替换
           ↓
       PageFingerprintRouter (保留原只读通用匹配能力)
```

- `PageRecoveryRouteAdapter` 在原契约上增添 `taskId` 与 `primaryForTask`，默认值保留旧实现/测试的源码兼容。所有真实业务适配器**显式声明任务归属**，聊天详情禁止覆盖消息列表的主检查点路由。
- 注册表仅维护**接口、身份和回调**，绝不保存 Android 节点、旧坐标、手势、页面截图或业务阶段，不自行触发 `saveCheckpoint()`，不启动任务、不执行 UI 写入、不授权恢复。
- `PageObservationCoordinator` 负责维护注册表和既有 `PageFingerprintRouter` 的一致性；同名路由替换不重复注册，注销同时移除指纹与适配器。主任务路由冲突时拒绝注册，避免第二个主恢复入口篡夺任务。
- AI Reply 通过注册表解析任务→主路由、路由→任务，删除本地 `when` 查找表及本地适配器匹配回退。自动浏览通过同一注册入口注册，使用 `ACCOUNT_BROWSE` 任务作用域识别动态页。路由匹配的**业务指纹函数内容保持不变**。
- 动态页的只读匹配路由 ID 仍是 `account-browse.dynamic-feed`；动态页恢复事务/检查点内原有 `account-browse` ID 不变。不可将两者合并，否则会破坏持久化记录兼容性。
- P5 只统一路由适配器注册；P6 才审计已知各业务仍构建自己的 `RecoveryEventDispatcher`、`RecoveryScheduler` 和双快照 verifier 实例是否可共享，**本阶段保留原数量**。

## 不改范围、安全不变量与兼容

- 原页面指纹匹配函数、Activity、窗口、身份绑定、双快照稳定性验证、原业务检查点结构/存储、UNKNOWN 重试时序全部不变。
- 不更改 P3 的运行代次/租约机制与 P4 的唯一 UI 写操作授权出口；路由注册和匹配绝不授予操作权。
- 不改手势算法、轨迹、点击坐标、输入分段、等待节奏、原测试/架构安全断言、不删除文件。
- `RecoveryCheckpointAdapter<T>` 和三条检查点→统一 checkpoint 的映射调用继续保留，由已有架构门禁与测试保护。

## 验证与交付

- Core：跨业务宽泛匹配被任务作用域隔离；主/次路由归属与冲突拒绝；重复注册替换后不持有旧回调；取消注册清理身份；只读匹配不写检查点。
- Business：注册真实四个路由的互操作与读取顺序；检查点写入仅由任务明确调用；原恢复检查点映射单测原样保留。
- Python 架构门禁新增 P5：禁止业务绕过统一注册入口、AI 恢复本地列表/映射、自动浏览无任务作用域匹配、双主路由/缺少任务归属、在其它模块另造注册表实例。
- 全量 CI：Python 架构测试与严格边界检查、Core/Business/Gesture/App 单测、Freedom Debug 和 OCR Plugin Debug APK、CodeQL。

## 与 P4 的关系和后续遗留

P4 完成“UI 写操作必须持有正确任务租约”闭环；P5 统一“恢复**观察和检查点保存入口如何注册、匹配、查找归属**”，属于上游只读取证与任务定位机制。新的注册表不替代 P4 操作许可，也不把任意匹配页直接视作恢复成功。已存在的分支独立恢复调度实例及长期恢复/进程重启的复杂时序属于 P6 审计。

## 未验证事项

CI 无法代替真机服务重连/崩溃重启、没有 Usage Access 时缺失 Activity 证据、跨窗口切换和异步 OCR 回调交错测试。仅经 GitHub 远端 API 提交；不能声明已检查本地 `git status`，须以 P1 远端 SHA、compare diff 和 CI 为准。
