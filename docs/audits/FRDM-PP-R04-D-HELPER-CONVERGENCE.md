# FRDM PP — R04 第四批与阶段验收

- PP 基线 `c89727c3b379477bca9e3169cc0d2adb21c0a04a`，第三批 [CI 46057593561](https://github.com/ttiaogou/FRDM/actions/runs/46057593561) 完整成功。仅在既有 Momo 组件中收敛无状态辅助职责。
- MomoSayHiAvatarTargetPolicy 的 contains/isWithin 原表达式与门面重复谓词逐项等价；开放现有对象方法并复用，删除 isPathWithin 重复实现，containsBounds 保留薄委托（ArchitectureBoundaryTest 以该声明为源码定位边界）。不改校验顺序、坐标、阈值、失败原因或测试。
- 导航候选日志格式移入现有 MomoSayHiNavigationEvidenceInspector，门面保留原日志去重状态、采集调用和 8 项上限。删除 4 个仅返回 config.page 字段的私有包装，直接读取相同配置，无默认值变化。
- 新增 3 项源码等价性检测（8 段历史源码 Git blob），2 项 Kotlin 运行回归（几何边界/路径及日志精确字段与数量）。保留 R00/R01 原清单，精确登记本批 S1 替换及新增测试指纹。R02、M04、既有断言及 CI 不变。提交前比对 M04 全部门面区域、媒体 observe、OCR 主流程、私聊安全区域与动态 feed 调用段逐字一致。
- MomoSocialAdapter.kt: `ed23fe9f78461734f1a484ac72287ad2d6a18ad8` → `8bab51d20b1d24626041635fda0a767d33eb7ff8`
- MomoSayHiAvatarTargetPolicy.kt: `71aa0ca8a1bf27b9e3baa7a4d2e2c8784943b5f1` → `2571b4c6719caab7fa956a8a32fcd3614cab0b1b`
- MomoSayHiNavigationEvidenceInspector.kt: `ee6ab55f92bb78fccad1b29a7e3e439151c4963c` → `6f917cf6b61ce1b91dbda2237101972398180fbe`

## R04 阶段审计

四批门面行数 8909 → 8838 → 8715 → 8627 → 8603，累计净减 306 行。迁移并非追求行数；原有七个端口、统一入口、所有运行时状态与手势执行链保留。剩余方法审计分类：

| 剩余职责 | 处置 |
| --- | --- |
| 统一页面入口、页面/动态检查 | 现有入口及 Inspector 已承接核心读取，门面仍组合实时窗口校验、页面归属及记忆写入；保留 |
| 招呼遍历、身份、返回、重检、点击确认 | 依赖选中目标、事务状态、检查点、回调、租约；R05 审计 |
| 私聊身份锚点与缓存、返回和列表定位 | 与身份事务、检查点及实时窗口绑定；R05 审计 |
| 草稿节点文本读取与当前输入框定位 | 与 Android 节点生命周期、聚焦、输入/发送安全契约同处；随 R05 输入职责审计，避免将 Android 依赖引入现有纯 Field 控制器 |
| OCR staleBatch、异步有效性、租约、去重回调 | 原有闭环及架构检测依赖；随 R05 事务审计，保留 |
| 媒体与拦截 | M04 冻结，禁止为缩减行数改动 |
| safeString/safeNode/safeBoolean、Rect 转换 | 局部平台异常处理和适配器转换仍有调用，其他类同名辅助的 null/异常语义不完全一致；保留，不新建通用工具层 |
| 历史 GesturePolicyPort 注释占位/Gradle 排除 | 现有架构测试定位依赖，延后独立审计 |

本轮仅完成已识别低风险无状态辅助范围，不宣称所有大方法内的纯片段已经迁移。R04 工程验收须本提交全量 CI 成功，真机验收需实际设备覆盖招呼、私聊、动态、身份、OCR、发送、媒体、窗口交接、停止与失败恢复。无本地工作区/真机，不能声明实际业务等价性已完全证明。未获 CI 验收不进入 R05。
