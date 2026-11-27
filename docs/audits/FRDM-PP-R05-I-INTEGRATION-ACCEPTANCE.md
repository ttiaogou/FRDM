# FRDM PP R05-I — MomoSocialAdapter 有状态职责迁移集成验收

## 基线与范围

- 分支 PP；验收基线 `cc2fba0612766ae0e0469c45cf9c82381d83edbb`（R05-H）；R05 起始基线 `1cc96df4ccf855e20e0a6436249db4f529abc282`。
- 两基线间 8 个提交，0 behind；28 个变更文件（含测试和审计文档），没有删除文件。
- R05-H CI [46068559000](https://github.com/ttiaogou/FRDM/actions/runs/46068559000) 完成且成功：架构/资源、CodeQL、单元测试、Freedom APK、OCR 插件 APK。
- APK artifacts: Freedom-debug-apk 5,507,917 bytes; Freedom-slim-debug-apk 2,791,237 bytes; Freedom-OCR-plugin-debug-apk 113,386,380 bytes.
- Adapter 净减少 1,143 行（8,602 → 7,459）。本阶段不修改生产代码。

## 职责及证据

| 阶段 | 验收对象 | 基线证据 |
| --- | --- | --- |
| A | MomoInboxIdentityCaptureState | R05-A 逆向测试及身份状态单测 |
| B | MomoSayHiTraversalState / MomoSayHiNavigationFlowController / MomoReceivedGreetingsEntrySearchController | R05-B 逆向测试及招呼状态单测 |
| C | MomoInboxIdentityCaptureState 内头像及身份状态操作 | R05-C 逆向测试及头像事务单测 |
| D | MomoDraftTransactionController | R05-D 逆向测试 |
| E | MomoImageSendTransactionController | R05-E 逆向测试、M04 图片动作冻结 |
| F | MomoInterceptionTransactionController | R05-F 逆向测试、M04 拦截动作冻结 |
| G | MomoPrivateFollowUpController | R05-G 逆向测试、L07 硬检测 |
| H | Adapter 私有冗余包装 | R05-H 精确逆向测试 |

R00/R01 历史冻结、R02 证据索引、R04、R05-A..H 检测继续保留。R05-I 新增跨控制器实际接线和唯一状态所有者检查，包含反向变异检测；无新的操作权或恢复循环。TaskCoordinator 及 UiWriteAuthorization 保持原有任务租约与写入授权职责。

## 遗留及验收限制

- Adapter 仍持有招呼临时页面状态、选择状态、手势回执/返回及诊断状态；还保留原有冻结定位、点击执行、只读页面确认。这不是完全无状态 Adapter，不能将阶段集成验收等同于所有有状态代码迁移完毕。
- 现有头像资料页实际观察/滚动/返回执行仍在 Adapter；图片、拦截底层手势执行及短时 permit 仍在 Adapter。此轮不扩大范围或改变算法。
- CI 及源码等价性是自动化验收证据，不是真机操作证据。真实页面变动、OCR、异步回调、用户手动切换及系统网络异常的端到端行为仍需真机复测。
- 本文记录的是已验证的 R05-H 基线；R05-I 自身最终结论以本提交的实际 CI 为准。
