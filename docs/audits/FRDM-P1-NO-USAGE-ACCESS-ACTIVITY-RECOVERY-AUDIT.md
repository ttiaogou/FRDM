# FRDM P1｜无 Usage Access 时崩溃重启后的 Activity 身份恢复专项审计

- **仓库与分支**：\`ttiaogou/FRDM\` / \`P1\`。
- **审计基线 HEAD**：\`63466b6e746690117be1b33a408882d309bd7602\`。
- **日期**：2026-11-22。
- **性质**：独立只读审计。**不改生产代码、测试、CI、权限声明、页面指纹、手势/节奏、安全断言或运行状态机。**
- **数据来源**：用户 2026-11-22 16:33:24 导出的 \`Free崩溃后重启.txt\`（滚动日志仅最近 300 条）；对照 16:30:46 的 \`服务重连.txt\`、既有脱敏真机回归样本与 P1 源码。日志不纳入仓库、不复制联系人/消息/节点树明细。
- **Android 官方依据**：[AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)、[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[AccessibilityWindowInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo)、[UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)、[ActivityManager](https://developer.android.com/reference/android/app/ActivityManager)。

## 1. 核心结论

1. **底部标签切换与 Activity 身份更新不是同一事件**。本轮真机日志中，消息/个人/动态/附近底部切换主要对应 \`TYPE_VIEW_CLICKED\`、\`TYPE_VIEW_SCROLLED\`、\`TYPE_WINDOW_CONTENT_CHANGED\`，Activity 一直为空/未知；不存在可以证明当前宿主 Activity 的新 \`TYPE_WINDOW_STATE_CHANGED\` 记录。
2. **进入聊天详情再返回后恢复，是收到新 Activity 证据的结果**。日志在 16:33:13.284 收到 \`com.immomo.momo.message.activity.ChatActivity\`，16:33:15.283 收到 \`com.immomo.momo.maintab.MaintabActivity\` 的 \`TYPE_WINDOW_STATE_CHANGED\`；随后 16:33:16.057 \`UNKNOWN->READY\`、16:33:16.794 获取任务租约、16:33:16.881 \`RECOVERY_EXECUTION_READY\`，并出现已完成的自动滑动。
3. **桌面返回需要谨慎表述**：用户实际操作称“从桌面回来也不恢复”，但导出日志只有最近 300 条，没有完整的崩溃、桌面前后台切换和权限状态事件时间线。因此不能证明 Android 在桌面返回时从未发出窗口事件；可以确认没有在可见时间线里出现足以恢复身份的可接受 Activity 证据。
4. **没有证据表明 Activity 事件被订阅规则或去重误丢弃**。AI 模式明确订阅 \`TYPE_WINDOW_STATE_CHANGED\`；从目标包收到并被归一化后，Activity 更新在可见日志去重前已执行。之前的真机恢复也证明新鲜 Activity 事件一旦出现，正常接收/更新/放行。
5. **当前实现下，无 Usage Access 且没有新的真实 Activity 身份证据，不能安全地保证自动继续**。这不是让状态机“恢复失败”的理由：应保留检查点、继续只读重检；禁止根据一个控件 ID、节点树相似度或旧 className 执行手势。

## 2. 真机证据（原始日志行号为用户导出文本行号）

| 时间与行 | 实际证据 | 说明 |
|---|---|---|
| 16:32:40.675，行 81–84 | 点击 \`maintab_layout_profile\`，\`Activity=未知\` | 底部标签变动不等于 Activity 类名 |
| 16:32:40.572、16:32:41.870，行 68、117 | \`ROUTE_WAIT\` | 检查点保留，仅只读重检 |
| 16:32:46.447，行 156–159 | 点击 \`maintab_layout_chat\`，\`Activity=未知\` | 切回底部消息标签仍未取证成功 |
| 16:32:47.010–16:32:58.182，行 166–202 | 多次 \`APP_NOT_READY\`，原因“当前 Activity 身份尚未确认” | Android READY 门禁正常拒绝 |
| 16:32:58.295，行 215–218 | 点击 \`maintab_layout_nearby\`，\`Activity=未知\` | 切换其他底部标签未补齐 |
| 16:33:13.284，行 389–395 | \`TYPE_WINDOW_STATE_CHANGED\` / \`ChatActivity\` | 首次可见的新鲜 Activity 类名事件 |
| 16:33:15.283，行 436–442 | \`TYPE_WINDOW_STATE_CHANGED\` / \`MaintabActivity\` | 返回消息列表对应 Activity 身份事件 |
| 16:33:16.057–16:33:16.881，行 449–471 | \`UNKNOWN->READY\` → \`OWNED\` → \`RECOVERY_EXECUTION_READY\` | 有证据后完成门禁，确实恢复业务 |
| 16:33:18 后，行 481–589 | 列表自动操作继续，Activity 为 \`MaintabActivity\` | 行为结果佐证恢复而非仅状态日志 |

**证据范围限制**：这份日志是 300 条滚动记录，不包含最初进程死亡、无障碍 Service 初次连接、完整 Usage Access 许可检查或桌面全部事件。不能据此推断这些过程不存在。

## 3. P1 生产调用链与数据状态

### 3.1 状态清空和新证据入口

- \`FreedomAccessibilityService.onServiceConnected()\`（\`app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt:350-401\`）：订阅运行模式 → 执行 Service 恢复 → 重新应用订阅 → \`activityRecoveryCoordinator.restart()\`。**连接不代表新 Activity 事件已经出现**。
- \`AndroidAccessibilityServiceRecoveryCoordinator.onServiceConnected()\`（\`.../AndroidAccessibilityServiceRecoveryCoordinator.kt:38-63\`）：AI 模式恢复时有意 \`clearActivityContext()\`，避免沿用可能已过期的宿主 Activity。
- \`AndroidWindowContextTracker\`（\`.../AndroidWindowContextTracker.kt:15-55,62-73\`）：只保存可接受的窗口状态 Activity 类名，或经新鲜窗口/代次绑定的只读 Usage 证据；不直接使用滚动或内容更新猜测 Activity。
- \`AndroidAccessibilitySubscriptionPolicy\`（\`.../AndroidAccessibilitySubscriptionPolicy.kt:25-64,71-85\`）：AI 模式订阅 \`TYPE_WINDOW_STATE_CHANGED\` 等，回调入口普通事件限制目标包。
- \`AndroidAccessibilityEventForwarder.accept()\`（\`.../AndroidAccessibilityEventForwarder.kt:22-69\`）→ \`AndroidAccessibilityEventPipeline.processNormalized()\`（\`.../AndroidAccessibilityEventPipeline.kt:117-175\`）：新 \`TYPE_WINDOW_STATE_CHANGED\` 携带匹配 Activity 类名可更新 tracker；窗口/普通内容事件不自动生成 Activity 类名；可见日志去重不是更新的前提。

### 3.2 无权限时的恢复探针

- \`AppCompositionRoot.kt:281-310\`：\`AndroidForegroundActivityRecoveryCoordinator\` 唯一配置的 \`readEvidence\` 来源是 \`AndroidUsageActivityEvidenceReader.probe()\`。
- \`AndroidUsageActivityEvidenceReader.kt:77-141\`：先读取目标活动窗口 ID，再检查 \`OPSTR_GET_USAGE_STATS\`；无授权返回 \`Pending("USAGE_ACCESS_NOT_GRANTED")\`；有授权时仍要求正确包名、目标 Activity RESUMED、同一活动窗口 ID 和 window generation。
- \`AndroidForegroundActivityRecoveryCoordinator.kt:25-104\`：无可靠证据只读退避；存在两个一致样本且 \`acceptEvidence\` 通过才恢复 Activity。
- \`AndroidAccessibilityGateway.kt:565-662\`：以当前活动窗口根、包名、\`activityForWindow\`、页面业务就绪等共同评估 \`READY\`；\`Activity\` 未确认会持续 \`APP_NOT_READY\`。**\`ROUTE_WAIT\` 和 \`APP_NOT_READY\` 是不同层的状态，不应混为租约故障。**

### 3.3 Android 平台含义

- 官方 \`TYPE_WINDOW_STATE_CHANGED\` 描述为“视觉上独立的 UI 区域状态发生变化”，**不保证每次 Fragment/ViewPager 或前后台变化都会触发，也不保证 \`getClassName()\` 一定是 Activity 类名**。
- \`AccessibilityService.getWindows()\`/\`getRootInActiveWindow()\` 提供活动窗口、根节点与包名等 UI 信息，但**不提供通用、稳定的第三方当前宿主 Activity 类名字段**；窗口标题、根节点 className 不等价于宿主 Activity。
- \`ActivityManager.getRunningTasks()\` 自 Android 5.0 起不再为普通第三方应用提供完整的跨应用任务信息；不能作为 Freedom 自动恢复的正式证据源。ADB \`dumpsys activity\` 仅适合开发测试诊断，不是普通 App 的运行时 API。

## 4. 替代取证途径可行性

| 候选路径 | 无 Usage Access | 可证明什么 | 能否独立满足现有 Activity 门禁 | 建议 |
|---|---|---|---|---|
| A. 新鲜目标 \`TYPE_WINDOW_STATE_CHANGED\` 且真实 Activity 类名 | 是 | 某次窗口状态事件的 Activity 线索 | **可以进入现有验证链路**（仍需当前包/窗口/页面/检查点/租约验证） | **现有可用路径** |
| B. \`UsageEvents.ACTIVITY_RESUMED\` + 活动窗口、代次双样本 | **否**，需用户授权 | 活动 Activity 生命周期证据 | 是，当前代码已实现 | 保留，明确为可选授权路径 |
| C. \`TYPE_WINDOWS_CHANGED\`、\`getWindows\`、\`getRootInActiveWindow\`、窗口标题 | 是 | 当前显示的窗口、包名、窗口 ID、结构等 | **不能独立证明宿主 Activity 类名** | 可提高窗口/页面诊断精度；禁止单独放行动作 |
| D. 底部标签 View ID、Fragment 类、OCR 截图、页面特征/旧指纹 | 是 | 当前看到的 UI 结构及业务路由线索 | **不能独立证明宿主 Activity** | 只作页面观察；不得伪造或代替 Activity 证据 |
| E. \`ActivityManager.getRunningTasks()\`、任务/包管理器、反射/隐藏 API | 一般无需 Usage Access，但受其他系统限制 | 通常无法可靠取得其他应用当前 Activity | 否 | 不采用为生产恢复依据 |
| F. 目标应用合作提供受身份校验的 Activity 状态 | 是（需目标应用合作） | 理论上可建立独立可信证据 | 需协议和安全设计；陌陌当前无此集成 | 只作为架构备选，不在本轮实现 |

**关于“可以不打开聊天页就自动恢复”**：除 A/B 等真实身份来源外，现有公共 Android API **没有经过本审计确认的、无 Usage Access 且可在所有设备上可靠直接查询其他应用当前 Activity 的替代途径**。若将“当前稳定窗口+双快照页面指纹”视为等价身份，这**改变了现有 Activity 门禁安全契约**；不能作为无需评审的最小修复。要另立安全设计、负例回归和设备矩阵验证。

## 5. 审计建议（无生产改动）

**P0：仅补观察诊断，无安全逻辑变更。**
- 对“Service 重连/进程重启、窗口回前台、目标 \`TYPE_WINDOW_STATE_CHANGED\`、目标 \`TYPE_WINDOWS_CHANGED\`、无 Activity 时的只读重检”建立同一运行/服务代次下的结构化事件序列号，记录 \`sourcePackage\`、\`eventType\`、\`eventClassSimpleName\`、\`windowId\`、\`pageGeneration\`、\`activityEvidenceSource\`、\`rejectReason\`；不输出聊天正文、联系人或节点树明细。
- 仅作被动日志，在事件**过滤前后**各放一个定量计数，以区分平台未回调、目标包过滤、普通控件窗口事件、类名不符以及 READY 门禁拒绝。全局事件不能越过现有目标包安全过滤。
- 明确 \`ACTIVITY_EVIDENCE_PENDING\` 与 \`ROUTE_WAIT\` 的不同责任；避免把后者误报为“运行代次不匹配”已确定的根因。

**P1：独立真机证据矩阵**（不开 Usage Access 与已授权两种配置分别执行，保留完整开始/结束时间线）：
1. ADB 崩溃重启 → 在消息列表静止不动：不得以缓存类名恢复。
2. ADB 崩溃重启 → 陌陌底部标签切换 → 返回消息：没有新的可信 Activity 证据时仍保持只读。
3. ADB 崩溃重启 → HOME → 从桌面图标返回 / 从最近任务返回：分别记录目标窗口状态事件有无、className 内容、运行身份及拒绝原因；**不能把结果预设为必有 Activity 事件**。
4. ADB 崩溃重启 → 聊天详情 → 返回消息列表：新鲜 Activity 事件有效，之后仍通过双快照、检查点和租约验证。
5. 以上测试在窗口悬浮、弹窗、前后台快速切换、用户主动停止时验证没有错误放行。

**P2：方案决策**：若产品要求无 Usage Access 且无 Activity 新事件也自动恢复，需明确评审是否允许设计新的可信身份来源。不能直接复用历史窗口对象、猜 Activity、改变页面指纹或删除 Activity 检查。

## 6. 现有测试与本轮交付

- 已有 \`AndroidAccessibilityEventPipelineTest\` 的 \`clearedActivityAfterReconnectRequiresANewTargetWindowStateEvent\`：重连清空身份后，仅内容/滚动/控件类事件或非目标包事件不回填 Activity；真实目标 Activity 事件可回填。
- 已有 \`AndroidForegroundActivityRecoveryCoordinatorTest\` 的 \`noUsageAccessNeverRestoresActivityAndUsesReadOnlyBackoff\` 与双样本、过期回调隔离测试。
- 已有 \`ActivityRecoveryDeviceFixtureTest\` 的 \`realDeviceProcessRestartTabsStayReadOnlyUntilFreshChatActivityAndListActivity\`：固化这次真机现象；无法替代完整平台事件跟踪。
- **本轮仅添加本审计报告**，没有修改生产代码、测试代码或构建配置；未取得“完全无权限恢复”的技术证明，不作保证。
