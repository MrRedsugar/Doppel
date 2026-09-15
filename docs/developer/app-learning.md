# 应用学习：当前状态与 SDK 兼容

当前 App 已移除“应用学习”和“教它一次”的产品入口。正常任务使用 `DirectRuntime → SplitTaskEngine`，不会在任务完成后自动生成学习路线，也不会自动读取 `learned-skills-v1` 或 `manual-skills-v1`。旧的 `automatic_learning` 设置不会重新启用这条产品路径。

现行 Skills 来自内置资料和用户明确导入的普通包，使用方式见 [手机直连的按需 Skills](direct-skills.md)。`DirectSkills` 不合并旧学习库；其正文、资源和删除接口对 `learned-`、`manual-` 前缀保留 `application_learning_removed` 响应。删除学习入口不会删除用户已有资料。

## 保留的数据与接口

`AppLearning`、`DemonstrationSession`、`LearningActivity` 仅为旧 SDK 集成保留，并标记为弃用。`LearnedSkillStore`、`ManualSkillStore` 及其数据结构仍保留，旧宿主可以显式读取、检查、导出或管理自己已有的记录；这些接口存在不表示当前 App 会调用它们学习或执行任务。

| 历史数据 | 位置（应用私有 `noBackupFilesDir` 下） | 兼容访问 |
| --- | --- | --- |
| 自动学习的路线 | `learned-skills-v1` | `AppLearning(context).store` |
| 人工确认的示教资料 | `manual-skills-v1` | `AppLearning(context).manual` |
| 未完成的示教证据 | `pending-skill-demo.json` | `pendingEvidence()`、`clearPendingEvidence()` |

例如，旧 SDK 宿主可以在用户明确要求导出时读取已有条目：

```kotlin
@Suppress("DEPRECATION")
val learning = AppLearning(context)
val items = learning.store.list()
// name 来自用户选中的旧条目，不由模型构造。
val document = learning.store.read(name, inspect = true)
val zip: ByteArray = learning.store.export(name)
// 手动资料使用 learning.manual.read(name, inspect = true) / export(name)。
```

兼容导出包包含 `SKILL.md` 和 `references/evidence.json`。它保留旧条目名称及历史来源，不会自动迁移成现行 Skills，也不会恢复自动参考。尤其是保留 `learned-` / `manual-` 名称的包，仍受到 `DirectSkills` 的前缀限制。

历史资料仍是不可信的操作参考，不能证明当前应用版本、页面或业务结果。导出内容可能包含界面标签和操作来源，用户应在主动分享前检查；移除入口也不等于删除此前任务记录或已经发送给供应商的内容。

## 旧 SDK 显式示教的生命周期

仍集成 `DemonstrationSession` 的旧宿主必须显式启动会话，并保留取消入口。它不是现行 App 的后台学习服务。会话在当前进程内采集离散截图及宿主提供的事件，不能保证恢复完整触摸路径，也不能把缺失动作补成事实。

`finish()` 只保存待确认的证据，不代表任务成功或已创建可执行 Skill。旧宿主仍需让用户说明目标、检查内容并明确保存。

移除产品入口不能删除取消钩子：`FirstUseConsent.revoke()` 和无障碍服务断开时仍调用 `DemonstrationSession.cancel()`。旧 SDK 宿主自行启动示教后，也应在用户取消、宿主结束或失去必要权限时取消会话，停止采集并移除示教悬浮层；`cancel()` 支持转到主线程清理。

## 验证边界

`LearnedSkillStoreTest`、`LearningTraceTest`、`DirectLearningTest` 等历史兼容测试，只能证明被显式调用的旧数据或适配逻辑。旧 `LearningLiveTest` 的记录不能作为当前 `DirectRuntime → SplitTaskEngine` 自动学习的证明。现行产品验证应确认没有应用学习入口、普通 Skills 仍可使用，以及已有历史文件未被清理。
