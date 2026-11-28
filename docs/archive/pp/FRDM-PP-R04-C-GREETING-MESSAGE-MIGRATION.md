# FRDM PP — R04 第三批招呼与消息读取辅助迁移

- PP 基线 `6f34c3809e12a6ce062701f7aab369ca640a5cdf`；[CI 46056229991](https://github.com/ttiaogou/FRDM/actions/runs/46056229991) 全部成功。
- 门面仍持有原状态、统一入口、运行/窗口校验、业务事务及 OCR 回调、悬浮窗租约、调度；复用现有 Inspector、Policy、Contract、Reader 实例，不创建新运行时组件。
- 招呼窗口筛选移入 MomoSayHiAccessibilityInspector，Gateway 获取快照仍在门面；格式及行目标诊断移入 MomoSayHiNavigationEvidenceInspector，脱敏指纹移入 MomoSayHiIdentityFingerprintPolicy。保留原过滤、顺序、时间取值点、哈希与诊断字段。
- 聊天区域前置模型及读取结果映射移入 MomoChatPageContract，rootAccess、窗口一致性检查与 obtain 仍在原处。结构读取异常批次构造及正文行计数移入 MomoChatMessageReader；OCR 主流程仅替换原正文行计数表达式为同义调用，其他代码逐字一致。
- 新增三个源码等价性测试，按上一版 Git blob 固定诊断、指纹、窗口筛选、模型映射、结构读取和完整 OCR 调用段。既有业务、R02/R04、M04 测试继续独立执行；源码比对不替代真机。
- S1 精确指纹更新如下，S0、历史清单、原测试断言、CI 不变。
- MomoSocialAdapter.kt: `2d6f690d5b3504fa13bb177a14737180c3bd8a73` → `ed23fe9f78461734f1a484ac72287ad2d6a18ad8`
- MomoSayHiAccessibilityInspector.kt: `5bf48e5b2befd09562ccce5e98ccbd10fb4d5888` → `c3560c93fc19bb5acec659a040a216ef491d3503`
- MomoSayHiNavigationEvidenceInspector.kt: `cc24b37e75f2e1dcaa584fff5af034cd68482e58` → `ee6ab55f92bb78fccad1b29a7e3e439151c4963c`
- MomoSayHiIdentityFingerprintPolicy.kt: `4f709f87c43bfe9ec1e2d586e2413d44f90754c1` → `4f9094a13631680ac5a37c631c0d28e1b37486af`
- MomoChatPageModels.kt: `3fb5dd10b74ba2d161759ebd33a20d5e24220a82` → `830a1a38d32b76c75d07977890501c8bed662eb2`
- MomoChatMessageReader.kt: `5d951b290b6c8eed9d425765ce9fabd9ae786f55` → `1cc8714d36bcf379ee108dccb108aa988dc33fa2`
- 门面 8715 → 8627 行。真机待验证：多窗口招呼筛选、头像与安全目标重检、聊天区域刷新、结构读取异常、OCR 迟到/失效及发送确认。无真机环境，完整 CI 与真机验收尚未完成。后续只处理未迁移的无状态职责，不提前进入 R05。
