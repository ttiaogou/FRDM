# FRDM PP M04-B — 私信账本生命周期及事务检查点

基线：e0c7350255dfb25aa7bc74203998d16266e1ebf7（M04-A CI 通过）。本批不修改已冻结的点击方法、不接入媒体动作或更改招呼返回流程。

- 私信确认收据仅使用当前已验证平台 ID 对应的 opaque session key。昵称键、收到的招呼不计入；未验证身份仍沿用原文本业务，不创建跟随动作。
- 旧 `freedom_contact_message_ledger` 混合招呼与私信，且无法分辨弱身份键。按用户确认，初始化私信账本时仅删除该废弃混合 SharedPreferences，不读取、不迁移、不作为任何任务的账本；删除失败记录告警，下次初始化可再清理。新私信专用存储保留已确认收据；运行时 reset 不清除它。招呼原有发送日志、样本与遍历检查点不受影响。
- 账本原有 pendingRecoveryAction 是业务动作权威状态；checkpoint 增加 transactionId、step、revision、reconciliationRequired，不保存节点、坐标、窗口或租约。相同阈值仍先图片后拦截。
- 更新必须比对精确联系人和旧 checkpoint；失败不推进内存状态。图片完成才创建拦截 checkpoint。Android 存储使用同步 commit；失败不可当成成功。
- M04-C 调用方须在任何可能改变页面/业务的手势前持久化 reconciliationRequired，重启或失去租约后只读重新核验，不得根据 checkpoint 直接重放。确认方法须由独立正面证据触发，且调用方核验新鲜身份、运行代次和租约；本批不执行实际 UI 动作。
- 私信文本确认后账本写失败保留原发送日志，进入现有详情恢复；正常返回及遍历位置仍保持原路径，待 M04-D 接入完成门禁。此批未解决跨进程图片证据重建及实际恢复调度。
