# AUDIT_REGISTER — PP 持续审计台账

初始核查 SHA：`7000ed094517d7ae1236d836ecae0f34e2c1fdd2`。本台账不制定规则、不授予施工或删除权限。[AGENTS.md](../../AGENTS.md) 为唯一施工入口。来源 [R09-G](FRDM-PP-R09-G-FINAL-ENGINEERING-ACCEPTANCE.md) §6、[G01-A](FRDM-PP-G01-A-DOCUMENT-INTEGRITY-AUDIT.md)、[G01-B](FRDM-PP-G01-B-CURRENT-DOCUMENTATION.md)、[G01-C](FRDM-PP-G01-C-HISTORICAL-DOCUMENT-ARCHIVE.md)。R09G-G01～G09 保留原报告编号并加前缀防混淆。

## 状态与维护口径

CANDIDATE 待证实；OPEN 已确认待处理；RETAINED 已分类保留；LIMITATION 已知限制；DEFERRED 延期未执行；IN_PROGRESS 已授权处理中；VERIFY_PENDING 实施待验；CLOSED 有证据已解决；REOPENED 复发。等级为治理排序建议，不是新增安全断言。每行初始最近证据 SHA 为上述基线；后续追加实际文件/方法、调用证据、影响、关联 OWN、状态、证据 SHA、验证 run/关闭证据。问题重复出现时复用原编号，历史保留项不计为确认冗余。禁止虚构验证或以绿 CI 证明所有语义无重复。无新证据不无意义改写。

## 遗留及文档事项

| ID | 分类/等级 | 证据与影响 | 状态 | 后续处置及关闭/复核依据 |
| --- | --- | --- | --- | --- |
| R09G-G01 | 可达性/中 | showNodeMoreMenu 单引用；R09-B1 清理触发两项悬浮菜单契约失败，已回滚 | RETAINED | 另行授权真实可达性及 UI 契约审计；验证旧测试，不凭单引用删除 |
| R09G-G02 | 历史占位/低 | GesturePolicyPort.kt 无声明占位及六个 main、两个 test 失效排除路径；非活动实现 | RETAINED | 保留冻结配置/原测试；精确证明后独立授权处理，不宣称节省 APK |
| R09G-G03 | 相同方法体/低 | 七组 recycle、clear、getOrCreateKey、sha256、reset、bool/int、intersect；不同生命周期、存储、密钥域、APK、lane、类型或 S0 | RETAINED | 保留原分类，若有新证据才复审语义；不强行合并 |
| R09G-G04 | 接口/中 | ChatPagePort 42 方法、fixture fallback、跨 owner 薄委托及诊断归档；尚未证明无效 | CANDIDATE | 逐实现/调用/默认契约、锁与副作用审计；独立授权，行为回归 |
| R09G-G05 | 结构风险/中 | 11 个大文件 advisory；详见 R09-G §6 | CANDIDATE | 按职责、状态与链路评估，不按行数拆分；不是硬错误 |
| R09G-G06 | 缓存与异步/高 | 同联系人跨 visit 新鲜度、已离开定时令牌门禁的回调；跨联系人问题已修，仍依赖现行代次/路由/租约 | CANDIDATE | 依据真实故障证据做并发与新鲜度专项审计，不撤旧门禁 |
| R09G-G07 | APK 体积/中 | R09-G 普通 5595612 / 精简 5593806 bytes，差 1806 bytes；DEX 变化不等于 APK 变化 | OPEN | 独立打包分析，记录实际 APK 内各项大小；不得用外层 ZIP 比较 |
| R09G-G08 | 签名与隐私/高 | debug 签名只保证同 run 配套；debug 日志可能含聊天正文 | LIMITATION | 使用授权会话、脱敏日志；正式签名/隐私专项另行授权，勿视 debug 为正式发布 |
| R09G-G09 | 真机/高 | F01–F12 均 DEFERRED/NOT_RUN；用户独立验收 | DEFERRED | 按 R09-F 原场景记录设备、版本、配套 APK、行为和脱敏证据；通过后才关闭 |
| DOC-001 | 文档/高 | 架构说明落后于 R09-G；G01-B 更新、C 移出历史正文 | CLOSED | G01-C 架构 gate success；现行 ARCHITECTURE.md、G01-B/C 报告 |
| DOC-002 | 文档/高 | 旧单模块/共享源码目标误导；G01-B/C 区分现行与历史 | CLOSED | G01-C gate 通过；ARCHITECTURE-CONTRACTS-3.0.md、模块边界、归档索引 |
| DOC-003 | 文档/高 | 旧草稿及 OCR 准入描述冲突；G01-C 归档旧修复 | CLOSED | 文档冲突隔离，非宣称 AI 行为已全部验证；G01-C gate |
| DOC-004 | 文档/中 | 历史报告混杂现行目录；53 份历史资料已归档 | CLOSED | G01-C gate，原 blob/历史链接及索引核查 |
| DOC-005 | 文档/中 | OCR 说明重复、旧下载描述与异常文件名；G01-B 整合、C 移除重复入口 | CLOSED | G01-C gate；现行 OCR 说明与原文归档；设备离线仍待验收 |
| DOC-006 | 文档/中 | README 缺少导航；G01-B/C 补全 | CLOSED | G01-C gate，现行 README 链接 |
| DOC-007 | 治理/高 | 三份治理文档已建、AGENTS 接入维护流程；G01-F 复核 32 个职责、18 个台账编号、检查点及链接 | CLOSED | G01-E 完整 CI 46137618668 success，216 Python tests；G01-F 对 4ecfc4cab2968b8a34b18bb92873c9c7b26c745d 实际复审通过；仅关闭治理基础设施建设，不代表语义覆盖或 AI 合规已穷尽 |
| DOC-008 | 冻结保护/高 | G01-E 仅追加经授权的 AGENTS 导航；旧全文及精确新 blob 验证接入原 R01 | CLOSED | G01-E 架构 job 140956354191 三项新增测试及原检测全部通过；G01-F 复核十份正文、原清单与精确迁移，证据 SHA 4ecfc4cab2968b8a34b18bb92873c9c7b26c745d |
| GOV-001 | 职责覆盖/中 | OWN-13/21/31/32 部分建档；全仓方法、状态字段及语义重复未穷尽 | CANDIDATE | 后续按职责、调用链、读写者与生命周期补证，禁止把未核查标已验证 |

DOC-001～006 的关闭仅针对文档问题，证据为 G01-C HEAD 的 [架构与资源 gate](https://github.com/ttiaogou/FRDM/actions/runs/46136302512/job/140952260570)：213 Python tests、0 链接异常候选、硬检测通过。G01-C 完整 CI 查询时仍在运行，不作为关闭依据。DOC-007/008 关闭依据为 [G01-E 实际完整 CI](https://github.com/ttiaogou/FRDM/actions/runs/46137618668) 及 G01-F 对该 SHA 的累计差异、原冻结、归档、结构及链接复核；结项记录提交的 CI 仍须实际检查。R09G-G05 的 11 个具体文件及 G03 分类详见 R09-G；不另复制完整历史报告。

## D01 专项事项

证据基线：285c3d123d533e26aa68f3d2fab5d5b968329504（G01-F，完整 CI 46139876328 success）。D01-B 仅完成独立节点 ZIP 退役；原 S0 清单、Service、采集、监控、归档和现有可视化代码不变。D01-B 首次 CI 46140766984：架构、APK、OCR、CodeQL 通过，App 单测 961 项中 1 项失败；旧 FloatingWindowLifecycleBoundaryTest 仍要求已退役入口存在。修复只迁移该过期断言及截取边界，R01 保留原证据及新增精确逆向，修复提交 CI 待验证；不关闭待验事项。R09G-G01/G04 保留原编号。

| ID | 分类/等级 | 证据与影响 | 状态 | 后续处置及关闭/复核依据 |
| --- | --- | --- | --- | --- |
| D01-001 | 导出/高 | OWN-31/32：MainActivity 节点 ZIP 入口与专属事务、LatestAccessibilityNodeDump.createArchive 已精确移除 | VERIFY_PENDING | 本提交 CI、真机入口检查 |
| D01-002 | 缓存/高 | OWN-32：LatestAccessibilityNodeDump 与 GlobalNodeLayoutVisualizationUseCase 分别保存节点；前者仍有 S0 Service 高亮接收器读取 | OPEN | 不改 S0；先完成引用审计、冻结范围决议 |
| D01-003 | 缓存/高 | MomoSocialAdapter.onNodeTreeSnapshot 经 AppCompositionRoot 写入旧自动缓存；仍有 S0 读取者 | OPEN | 与 D01-002 同步收敛，保留正式归档 |
| D01-004 | 入口/中 | 旧 ACTION_MANUAL_NODE_CAPTURE 接收器仍注册，现行 UI 未发现发送者 | CANDIDATE | 全仓引用及动态入口核验；S0 独立决议 |
| D01-005 | 接口/中 | NodeInspectorDialog、NodeCapturePresentationPort 等旧检查器关联组件 | CANDIDATE | 全仓引用、测试保护及实际入口核查后处理 |
| D01-006 | 导出/中 | 独立节点 ZIP SAF 请求码、临时文件及保存回调已精确移除 | VERIFY_PENDING | D01-B CI 及真机回归 |
| D01-007 | 调度/中 | AndroidMonitorPreviewScheduler 350ms 防抖与监控摘要仍有生产用途 | CANDIDATE | 保留有效调度；后续核查全部摘要消费者 |
| D01-008 | 导出/高 | 缺少默认综合导出；现有 createExportBundle 已支持组合数据范围 | OPEN | D01-C 复用原聚合、保存和清理事务 |
| D01-009 | 兼容/高 | includeAllDiagnostics 改变关联诊断目录封存判断，存在旧归档兼容差异 | OPEN | D01-C 验证兼容且不放宽封存保护 |
| D01-010 | 遗留/中 | SessionSampleArchiveManager.createBatchArchive 仍有测试引用，未证明生产不可达 | CANDIDATE | D01-D 全量引用核验 |
| D01-011 | 交互/中 | 导出成功可能安全清理源数据，入口提示不充分 | OPEN | D01-C 明确范围及清理提示 |
| D01-012 | 导出/中 | 原节点 ZIP 默认优先手动缓存而非时间最新，相关方法已移除 | VERIFY_PENDING | 随 D01-001 验收 |

## 持续扫描范围

代码相似、语义职责、正反调用链、状态/缓存、监听器/回调生命周期、不可达及依赖/资源。当前现有架构检测与 G01-A 文档扫描继续使用；AI 发现的问题必须区分候选与确认。删除生产代码需独立任务授权及行为保护，不因本台账自动执行。

[职责归属表](RESPONSIBILITY_MAP.md) · [当前检查点](AUDIT_CHECKPOINT.md) · [R09-F 真机清单](FRDM-PP-R09-F-APK-DEVICE-REGRESSION.md) · [历史归档](../archive/README.md)。
