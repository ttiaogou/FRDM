# 模型调用失败诊断补充

基线：上一轮交付的 Freedom-full-source-text-container-ci-fixed.zip。

## 当前问题与本轮范围

此前 AiReplyDecisionEngine 使用 getOrNull 丢弃模型调用异常。调用失败后只能看到本地 MODEL_CALL_FAILED 和默认说明，无法据此认定模型拒绝回复，也无法判断 HTTP、网络或响应解析错误。

本轮仅补充诊断传播及日志，不修订请求参数、模型提示词、文本容器识别、消息方向、上下文、输入、发送、重试次数、冷却和状态转换。失败仍返回 HUMAN_REVIEW / MODEL_CALL_FAILED，沿用原有停止输入及失败处理。

## 修改文件与职责

| 文件（相对 Freedom/） | 对应组件及实际改动 |
| --- | --- |
| app/src/main/java/com/freedom/features/aireply/ai/AiModelCallDiagnostics.kt | 新增 AiProvider 公共诊断契约：安全错误码、调用阶段、HTTP 状态、可重试标记、异常类型及最多八级原因类型；仅使用固定词表记录服务端错误码/类型/参数名。 |
| app/src/main/java/com/freedom/adapters/ai/OpenAiCompatibleAiModelTransport.kt | 网络适配器逐调用跟踪配置、地址、编码、连接、写请求、读状态、读响应、解析阶段；保留精确 HTTP 数值（包括各类 5xx），携带底层异常类型。 |
| app/src/main/java/com/freedom/adapters/ai/DeepSeekAiModelTransport.kt | 对 DeepSeek 同步增加同一诊断链；保留原 DeepSeek 请求格式和参数。 |
| app/src/main/java/com/freedom/features/aireply/ai/AiReplyDecisionEngine.kt | 不再丢弃异常；以可选字段附带技术诊断，不改变决策结果。 |
| app/src/main/java/com/freedom/features/aireply/ai/AiReplyDraftCoordinator.kt | 以 requestId 关联失败日志；输出配置的服务商/模型、耗时及技术字段；调用失败时明确默认说明来自本地异常处理。 |
| app/src/test/java/com/freedom/adapters/ai/AiModelFailureDiagnosticsTest.kt | 新增十项测试，覆盖两种传输、失败传播、日志关联、敏感信息保护及失败不输入。 |
| docs/fixes/model-call-diagnostics.md | 本交付说明。 |

领域只依赖公共诊断接口，不导入网络适配器、Android 节点或 HTTP 实现。运行与会话状态所有权未迁移；未新增发送通道或改变状态转换。旧有架构差距继续保留，本轮未做模块搬迁或无关重构。

## 新日志

可搜索“AI 模型调用失败”，按同一 requestId 对应“AI 模型决策详情”。字段包含 provider、model、elapsedMs、safeCode、failureStage、httpStatus、retryable、exceptionType、causeTypes、providerErrorCode、providerErrorType、providerErrorParam。

- 未取得状态时 httpStatus=UNKNOWN，不能把它当作服务端返回了 HTTP 错误。
- retryable 仅记录传输层原有分类，不控制或新增重试。
- 例如 HTTP 400 且服务端返回词表内 unsupported_parameter / response_format，可以定位到参数不支持；词表外字段记为 REDACTED，缺失字段记为 UNKNOWN。
- 不输出 API 密钥、请求正文、响应正文、错误 message、异常 message/堆栈、接口 URL 或聊天原文。已有模型决策 reasonDetail 日志仍沿用原有脱敏规则；本轮未新增模型正文转储。
- 无法从旧日志恢复当时已丢弃的错误详情。新增日志的实际值需要重新运行获取。

## 验证

使用 Kotlin 2.0.21 / JVM target 17 分别编译 core（52 文件）、gesture_api（3 文件）、business（64 文件）、app 纯 JVM 子集（13 文件，包含两个真实模型 HTTP 传输实现），并分别编译 business 与 app 测试模块。最终运行 66 个测试类，共 559 项 JUnit 测试全部通过。

新增测试覆盖 HTTP 400/401/403/408/429/500/503、超时、TLS、连接建立前 DNS 异常、读取超时、响应格式错误/缺少 choices、空输出、配置缺失、错误技术字段提取及隐藏、requestId/配置模型/耗时关联、调用失败不写入或安排输入。网络使用注入的假连接，没有访问真实模型服务。

运行项目原有 Python 架构测试：12 项全部通过。运行 verify_boundaries.py --root . --show-advisories：通过；存在六项既有大类职责提示，不属于本轮修改。

第一次新增集成测试因样本标题未采用项目既有“与…对话”格式，在 CHAT_PAGE_NOT_CONFIRMED 处停止；修正新增样本后全部通过，未修改生产页面判断，也未删除或放宽断言。所有基线测试文件逐字节保留。

未执行完整 Android Gradle 构建、远程 CI、真机输入或真实模型 API 调用，因此不能承诺本次日志补充已解决实际模型调用失败或已成功输入。

## 交付完整性

完整源码 ZIP 保留基线所有原文件；增量 ZIP 仅包含本轮实际修改/新增的七个文件，保留 Freedom/ 下完整相对路径，可覆盖同名文件。未删除任何原项目文件。
