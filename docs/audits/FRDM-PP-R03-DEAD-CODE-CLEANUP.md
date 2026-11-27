# FRDM PP — R03 第一轮无效代码清理

- PP 基线 `6725482c36155d4a5fc3f9aafeb40c320f5eabe1`，R02 CI [46052816423](https://github.com/ttiaogou/FRDM/actions/runs/46052816423) 全部成功。
- MainActivity 的私有 card() 与 sectionTitle(String) 在源码中仅出现于各自定义，无调用或方法引用；不符合 Android XML onClick 公开 View 参数契约。已检查现有 architecture 源码断言与 shrink 规则，无针对该二方法的调用/保留契约；保留仍被使用的 roundedBackground、dp、全部业务和页面代码。未进行全仓反射扫描，风险由完整 CI 及后续真机 UI 回归确认。
- 删除四个已跟踪 Python 3.12 __pycache__/*.pyc（合计 58,539 字节），保留 .py 源码及测试；.gitignore 增加 __pycache__/、*.pyc 防止重新跟踪。
- R00 原始清单（469 路径）和 R01 原始清单（280 证据）保持原字节；仅在 R00 checkout 断言中登记 MainActivity 从 `0ce061194a011399a493a189fbda49a841eb7771` 到 `7afbe0c4eb9f8f67f96f9833665093424948d9c2` 的精确授权替换；R01 证据断言相应固定 R00 检测器的新 blob `107973b54c7411286ca724fd5f40f6e0aa12dc2f`。无路径忽略、无自动重算、无删除或降低有效行为断言；其他 468 个源码/配置与 279 个历史证据仍按原始 blob 校验。R02 11 个轨迹及 M04 不变。
- 暂保留 core 的 GesturePolicyPort.kt 注释占位和旧 Gradle 排除项：ArchitectureBoundaryTest 仍通过旧路径定位读取占位，并直接断言 core 排除项存在，删除需要独立审计并以不弱于原有的模块归属检查替代这些定位断言。不得顺手删有效测试或调整模块。
- 暂不删除恢复兼容分支、旧接口或其他未证实无调用的代码；不拆分大文件。不修改无障碍入口、手势、页面指纹、业务状态所有权、操作权、默认值、持久化或业务流程。
- 真机待验证：配置首页显示、运行按钮、权限/日志/图片导入等 Activity 路径；正常点击及业务仍以 R00/R01/R02/M04 与完整 CI 为门禁。未提供真机环境，不宣称真机验收。R03 完整 CI 通过后才准入下一阶段。
