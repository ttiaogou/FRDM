# FRDM PP R09-E — 全量架构硬检测、行为等价性回归及单元测试验收

## 范围及基线

- 仓库 ttiaogou/FRDM，仅 PP；验收生产基线 71c90c9e8ff9e33b078c79661d251227dc473f65；Git tree ee5275829ae217e9d4db1bd1f8140fca4057f3f2。
- [CI 46130126855](https://github.com/ttiaogou/FRDM/actions/runs/46130126855) 已完成且五项 job 全部 success，head_sha 与基线一致。复核实际 job 步骤、日志及 Gradle XML 工件；原施工计划 R09-E、AGENTS、现行强制约束及工程验证规则已读取。用户已免除本地工作区检查，使用远端 tree/diff/HEAD；不声称本地工作区已检查。
- 本阶段只验收及记录证据，无明确需修复的 CI 缺陷，不新增生产架构，不修改源码、构建配置、既有测试、冻结清单、门禁或施工规范。报告提交不改变已验收生产树，报告提交自己的 CI 仍须核验。

## R00/R01/R02 独立 Git tree 比对

原始基线 435406f593bc1dd512edac0af28f8f7c48feb0c2；递归 tree 未截断。当前与原始清单直接比较，并以实际 CI 中 R00/R01/R02 精确指纹检测复核授权变更。

| 对象 | 原始数量 | 原始 blob 一致 | 变更 | 缺失 |
|---|---:|---:|---:|---:|
| S0 永久冻结 | 76 | 76 | 0 | 0 |
| S1 原始文件 | 393 | 350 | 43 | 0 |
| R01 原测试及夹具 | 254 | 248 | 6 | 0 |
| R01 原检测/CI 文件 | 15 | 11 | 4 | 0 |
| R01 规则 | 11 | 11 | 0 | 0 |
| R02 固定轨迹文件 | 11 | 11 | 0 | 0 |

- 新增受控生产文件 12 个；原 469 个源码/配置文件无缺失，当前受控范围合计 481 个。现有 R00 精确迁移 pin 检测通过；S0 零改动。R00 清单 blob 4510f804078288427e8f8b61f2ec69e8a210bab7；R01 清单 blob 033bb92c5bc369ba7d7e315ac11fd3bfc9b513a7；R02 轨迹检测文件 blob 659ebbdf34c97d4aa8af77f4d366ff5b53c637d3，原轨迹预期未重录。
- R01 12 类契约的 57 个唯一源码路径、45 个唯一旧回归路径均存在。六个历史测试文件与四个历史检测文件有已登记变更，不能表述为“所有旧测试文件字节不变”。R01 精确证据及历史迁移逆向检测通过；原施工规则与 CI workflow 未改。四个历史检测文件为 test_m04_media_click_freeze.py、test_pp_r00_freeze.py、test_verify_boundaries.py、verify_boundaries.py。
- M04 七项点击冻结/缺失/重复/顺序/选择器等测试全部通过；保留 R05-E/F 动作归属迁移后的相同保护，不宣称本轮重新完成 M04 真机点击验收。

## 完整自动化验收证据

| 验收 | 证据 |
|---|---|
| Python 架构检测 | [job 140933221865](https://github.com/ttiaogou/FRDM/actions/runs/46130126855/job/140933221865)；209 tests，OK；R00/R01/R02/R03–R08 历史迁移及 R09-A/B/C/D 专项通过 |
| 架构边界 | ARCHITECTURE CHECK PASSED；9 模块依赖方向有效；11 项非阻断大文件职责 advisory，非 11 个硬错误 |
| Android XML 资源 | 验证步骤 success |
| JVM/Android 单元测试 | [job 140933650408](https://github.com/ttiaogou/FRDM/actions/runs/46130126855/job/140933650408)；实际执行 business、core、gesture-core、gesture-runtime、app 测试任务；报告工件 15256494841，Gradle-test-reports-46130126855-1 |
| OCR 插件 | [job 140933650361](https://github.com/ttiaogou/FRDM/actions/runs/46130126855/job/140933650361)；:ocr-plugin:testDebugUnitTest 与 :ocr-plugin:assembleDebug 成功；插件测试数量不在下表主报告中 |
| 主程序 APK | [job 140933650382](https://github.com/ttiaogou/FRDM/actions/runs/46130126855/job/140933650382)；普通与 shrink-only APK 均构建并上传 |
| CodeQL | [job 140933650400](https://github.com/ttiaogou/FRDM/actions/runs/46130126855/job/140933650400)；Java/Kotlin 分析成功；未以 job 成功推断零告警或零漏洞 |

已下载 Gradle 报告 ZIP，在实际 TEST-*.xml 中汇总 tests/failures/errors/skipped（非估算）：

| 模块 | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| app | 127 | 961 | 0 | 0 | 0 |
| business | 91 | 977 | 0 | 0 | 0 |
| core | 42 | 286 | 0 | 0 | 0 |
| gesture-core | 3 | 38 | 0 | 0 | 0 |
| gesture-runtime | 5 | 77 | 0 | 0 | 0 |
| Total | 268 | 2339 | 0 | 0 | 0 |

11 份 R02 固定轨迹各 1 个测试全部实际通过：PageRouting、GestureDispatch、Ownership、PrivateReturn、Greetings、ImagePaging、Identity、MessageRead、DraftInput、MediaLedger（media/storage 共用）、Recovery。R09-C 2 个操作权组合测试、R09-D 3 个历史缓存隔离测试和 1 个恢复隔离测试、R09-B3 3 个 HTTP helper 回归全部实际通过。固定组件级输入/预期回归不等同于双 APK 全链路差分或真机回放。

## 遗留与下一阶段准入

- R09-B 最新扫描：436 个物理生产 Kotlin/Java 文件、3779 个简单方法、100 个接口、1 个私有候选、0 个失效接口候选、7 组重复方法体。受原功能契约保护的 showNodeMoreMenu 及历史占位、Core 排除配置保留；七组既有分类保留，不自动删除。
- 11 项大文件 advisory（MainActivity、AndroidAccessibilityGateway、AndroidAccountBrowseWorkflow、MomoChatListSessionExtractor、MomoChatMessageReader、MomoSocialAdapter、AppCompositionRoot、RunDiagnosticsManager、AiReplyObservationRuntime、PrivateMessagesTraversalTaskExecutor、AiReplyDraftCoordinator）；按职责而非行数判断。不得据此重复扩大迁移范围。
- R09-D 同联系人跨访问缓存新鲜度、已经离开定时器令牌检查区的回调依赖上层门禁、复杂并发与真机导航仍未穷尽；R09-F 以实际设备/日志验收私聊、招呼、动态续接、身份采集、OCR、AI 发送、图片与拦截、操作权交接、重连和用户停止。APK 工件已存在（普通 15258000142、shrink-only 15257432724、OCR 15256509823）；并不等于真机通过。
- 本阶段基线的全部自动化验收通过。只提交本报告，报告提交对应 CI 未完成前不宣布新 HEAD CI 通过；不提前宣布 R09-F/G 完成。
