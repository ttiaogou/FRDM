# FRDM PP — R02 行为回归基线

## 准入与边界

PP `86a173c40e43dfad6fb70d19de375d6ed98e8f20`，R01 CI [46051534869](https://github.com/ttiaogou/FRDM/actions/runs/46051534869) 完整通过。R00 469 个源码/配置文件、R01 280 个原回归/门禁/规则文件及 M04 点击冻结均保留。本批仅新增测试与审计，不变更现有业务算法、操作权、状态所有权、调度、持久化、测试或 CI 工作流。

## 可执行行为基线

固定输入、时间/调度、人工控制平台反馈，直接调用现有生产类；在测试代码中记录实际返回、状态、调用及效果，比较固定有序预期。首次 CI 运行在与 R01 完全相同的生产源码上；后续迁移复用同一测试输入和固定预期，不能因迁移后结果不同重新录制预期。测试夹具沿用现有私有测试夹具的形状，仅存在于测试源码，不复制生产实现，不新增运行时框架。

| 契约 | 新增轨迹测试 | 主要固定证据 |
| --- | --- | --- |
| entry | R02PageRoutingTraceTest | UNKNOWN、外包、证据缺失、列表/详情路由 |
| gesture | R02GestureDispatchTraceTest | 固定安全点击输入，预览/派发顺序、实际 GestureSpec、队列完成及取消结果 |
| ownership | R02OwnershipTraceTest | 唯一租约、交接、旧租约/旧代次拒绝、释放 |
| private | R02PrivateReturnTraceTest | 检查点恢复、原遍历方向、已处理行、候选、确认收据 |
| greetings | R02GreetingsTraceTest | 筛选与持久事实隔离、回到原游标、重试边界 |
| browse | R02ImagePagingTraceTest | 缺证据等待、跨页异常、逐张确认、计数、截止时间 |
| identity | R02IdentityTraceTest | 昵称临时/重复、平台身份分离、跨快照复用、持久化数量 |
| ocr | R02MessageReadTraceTest | 读取超时、迟到与重复回调、调度器交付、最终状态 |
| reply | R02DraftInputTraceTest | 写入/核验顺序、固定等待、人工编辑不覆盖 |
| media/storage | R02MediaLedgerTraceTest | 确认事件去重、同阈值先图后拦截、错误联系人拒绝、跨重建持久状态 |
| recovery | R02RecoveryTraceTest | 实际阶梯观察调度、长期等待、旧代次停止拒绝、有效用户停止 |

新测试均在现有模块 test source set 中，使用 JUnit4，由原 Freedom CI 的 core:test、business:test、gesture-runtime:test、app:testDebugUnitTest 自动执行。新增 `test_pp_r02_trace_evidence.py` 复用 R00 git_blob，固定 11 个测试文件及 12 类 R01 契约映射，保护测试输入与预期不被悄悄修改。原 R01 映射的 45 个回归文件、全部原测试及 M04 专项检测继续独立执行，不替代已有断言。

## 证据边界与后续准入

这是固定预期的组件级行为回归，不是两个完整 APK 的差分运行，不是 Android 真机重放。首次完整 CI 通过后才建立经过执行验证的 golden trace；不能仅根据源码审查宣称测试已通过。现有测试和本次新增轨迹未穷尽所有业务调用顺序、真实节点/窗口、回调线程、平台时序、完整手势参数分布、数据库实际写入或业务端到端。针对每个后续迁移批次，先检查其实际受影响的方法、边界和测试覆盖，补足缺口再允许移除对应 S1 冻结；S0、M04 和未迁移文件继续保护。不得为 CI 通过降低断言或重新录制预期。

真机待验收：私聊正常返回及原位置续接、招呼筛选/返回、动态浏览/翻页、头像点击/ID、OCR 页变化及迟到、AI 文本输入及发送确认、图片实际送达及拦截开关和返回、页面交接、手势失败、服务重连、用户停止。无真机执行环境，不宣称通过。

本阶段不清理历史代码、不改生产源码、不修改旧测试或 CI、不提前实施 R03。R02 完整 CI 通过是后续准入的必要条件，不是对未覆盖行为的迁移授权。
