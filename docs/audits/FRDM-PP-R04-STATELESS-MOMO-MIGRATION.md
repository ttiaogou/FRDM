# FRDM PP — R04 Momo 无状态职责迁移（第一批）

- PP 基线 `afd95d27f19b77f9f2d95eb3992d8ec03050fc7b`，R03 CI [46053823624](https://github.com/ttiaogou/FRDM/actions/runs/46053823624) 全部通过。
- MomoSocialAdapter 约 8909 行，已有 Browse/Chat/Message/Session/PageInspection 组件及 80 余同包文件。保留现有七个端口、所有状态所有者、无障碍入口、手势门禁和既有执行通道；不新增运行时架构。
- 方法审计：只迁移没有可变状态、没有 Android 节点访问、无系统时钟或异步依赖的函数。调用点以同名对象成员 import 保持原调用表达式，两个 snapshot 工厂显式传入原 targetPackage/activityClass（仍只在原调用处求值）。

| 原方法 | 归属 |
| --- | --- |
| receivedGreetingsEntrySearchViewportFingerprint | MomoChatListSessionPolicy |
| sameMomoWindowGeneration、sayHiWindowStabilityKey 两个重载 | MomoWindowPageFingerprint |
| normalizeIdentityDisplayName、extractChatPeerName、sha256IdentityAnchor | MomoSayHiIdentityFingerprintPolicy |
| receivedGreetingsListChatPagePlaceholder、unknownChatPageSnapshot | MomoChatPageContract（MomoChatPageModels.kt） |

原表达式、字段、匹配规则、哈希、顺序、返回值保留，不将旧函数与新函数并行维护。9 个函数，门面净减 71 行（含 import 及空行差异）。已有 M04 冻结区域及 follow-up observe 块逐字比对相同，未修改媒体执行和点击算法。R04MomoReadOnlyMigrationTest 新增 4 个固定输入回归：身份文本/哈希、窗口代次/tree windowId、placeholder 快照、视口证据及拒绝路径。

R00 保留原始清单与 R03 精确变更；仅登记下列 S1 路径的精确 blob 替换，R01 同步固定新 R00 检测器及新回归测试，不忽略路径、不放宽行为断言。R02/M04 及旧测试不变。

- MomoChatListSessionPolicy.kt: `52f6de77fb025c65c1f93666cf4bfa940e85f93d` → `491b4e888cc045b2c170b48b16b11924682a1663`
- MomoWindowPageFingerprint.kt: `842648b653524414c0559a3919c8c97e91d45e6c` → `b812edf273cf0c45af16e80ae1a3e41bdb8085e8`
- MomoSayHiIdentityFingerprintPolicy.kt: `7d5fc671a44945b0b3e3dce6ff2d300e18d1046a` → `4f709f87c43bfe9ec1e2d586e2413d44f90754c1`
- MomoChatPageModels.kt: `2fa1871c901fbe8cf30b1d47589b8ea9189d62e1` → `3fb5dd10b74ba2d161759ebd33a20d5e24220a82`
- MomoSocialAdapter.kt: `69811799d3ed1048c3fc2105c4a2e09af7f50f17` → `0d35707b612ac3926a75c1933650d3083a898571`

本批不迁移直接读取 Android 节点的动态/招呼/消息只读适配方法，也不迁移有状态事务、OCR 执行与回调、身份缓存、点击验证；这些需要单独补充等价性证据。R04 全部完成前不得进入 R05。真机待验收：招呼入口搜索、列表窗口变化、身份标题/返回确认、未知页面、私聊续接、动态导航和图片/拦截；无真机环境。首次新回归与全量 CI 通过前不宣称验收。
