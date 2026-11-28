# FRDM PP — R04 第二批只读节点职责迁移

- PP 基线 `15731d3762ff76bb48f939305558e29d44570881`；[CI 46055025405](https://github.com/ttiaogou/FRDM/actions/runs/46055025405) 全部通过。
- 复用现有 MomoChatListSessionExtractor 与 MomoPageInspector 实例，将辅助 RecyclerView 边界、mf_view 浮层证据、局部节点路径解析与可见子节点计数从门面移动。AiFinderOverlayInspection 结果模型随职责迁移。显式传入原 targetPackage；不持有节点、状态或新增调用链。
- 原有 rootAccess 当前根读取、窗口代次校验、节点 obtain/recycle、异常处理、fail-closed、安全区域决策、调用顺序不变。原 safeInt 仅计数使用，迁移后删除无调用的私有包装；原表达式 runCatching/getOrDefault(0) 保留。浮层 Rect 转换使用原四字段构造；extractor 既有 safeString 对本次非空字符串表达式结果相同。
- M04 冻结方法与媒体 observe 区域逐字相同；原 R02、R04 第一批测试保留。新增 Python 源码等价性检测：从迁移前提交固定七段源码的 Git blob，逆向规范化仅本轮调用限定名/参数及等价辅助表达式，对比方法和三个完整调用者；这不是 Android 节点运行测试。既有几何、窗口、行为回归继续由完整 CI 执行。无新增运行时组件、无测试删除、无工作流调整。
- R00 仅追加三个 S1 精确指纹替换（保留 R03 与 R04 第一批），R01 固定新版 R00 和新检测；原清单及 S0 不变。
- MomoSocialAdapter.kt: `0d35707b612ac3926a75c1933650d3083a898571` → `2d6f690d5b3504fa13bb177a14737180c3bd8a73`
- MomoChatListSessionExtractor.kt: `200836fd2c0353efa39e4ecca29a05f880046922` → `581a0a323522ed6e12b71fe2e1d85fb197adb18e`
- MomoPageInspector.kt: `9fbda0573ae4e8a10e36498aad605ea60c266bb4` → `dfc2877d7bc269871759a5a62e04dfeb3d21b7dc`
- 门面 8838 → 8715 行。下一批继续审计招呼只读节点和消息读取/OCR 辅助逻辑，尚未迁移事务或状态所有权。
- 真机待验证：浮层出现/消失及不完整证据、辅助横滑列表避让、会话行定位、动态列表可见数、页面变化及返回续接。无本地 Android 设备；CI/真机未完成不宣称最终通过。
