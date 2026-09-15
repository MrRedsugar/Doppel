# 屏幕摘要与只读视觉复用

文档范围：当前 App 直连入口是 `DirectRuntime → SplitTaskEngine`。`ModelScreenSummary` 仍是生产 SDK 中的共享公开 API；本页后半部分的动作收窄、只读缓存及 `perception_metrics` 属于旧 `DirectTaskEngine`，不代表当前 Split 运行链具备这些行为。

旧引擎的 15 个实现文件（共 17 个顶层类）已迁至 `android/sdk/src/test/java/dev/doppel/sdk/legacy/`，保持 `dev.doppel.sdk` 包名，仅用于 JVM 历史测试。`PerceptionLiveTest` 已归档至 `labs/legacy-direct-engine/androidTest/`，不再默认编译为设备测试。历史证据不能替代当前版本验收，具体结果见对应版本验证记录。

Android SDK 提供 `ModelScreenSummary`，使集成方能将当前观察转换为紧凑模型输入；真实动作仍需交给宿主对原始观察、当前节点和权限进行校验。

```kotlin
val summary = ModelScreenSummary.render(observation, evidenceId)
val modelText = summary.text
val selectableNodes = summary.targetIds
// selectableNodes 仅是当前摘要中展示的可操作引用，不是执行许可。
```

默认最多展示220个有意义节点、22000字符，扫描最多300个节点。可选 `maxNodes` 范围1–300，`maxChars` 范围1024–64000。返回 `totalNodes`、`meaningfulNodes`、`shownNodes` 和 `truncatedNodes`；超量会在文字中明确提示未展示内容。

摘要移除无文字、无状态、无能力的容器；同一节点的重复文字/描述只展示一次。小型可点击/长按容器在内部只有一个静态标签、没有独立交互节点或密码节点时，可关联该标签；这不改写原始节点或宿主权限。不同目标即使同名也不合并。同名/无标签的可操作目标保留位置，用于消歧；唯一有文字的目标使用ID。需要完整空间布局的集成方仍可读取原始观察，或请求新的视觉理解。

保留 enabled、checked、selected、state_description 和可滚动方向。缺少勾选状态时显示 `checked=unknown`；未确认方向不添加滚动能力。密码节点仅显示保护说明，禁用/隐藏/未展示节点不会进入 `targetIds`。这只是信息表示，不能证明点击已经成功。

旧 `DirectTaskEngine` 还根据摘要中实际展示的可操作目标收窄 `action.kind`：页面没有可滚动目标就不提供 `scroll`，没有输入框就不提供输入类动作，没有长按能力就不提供 `long_press`。不同目标的能力仍以各自摘要为准，宿主继续逐项核验动作与目标的匹配，模型忽略工具定义也不能执行无效操作。这是旧引擎的接线行为，不是调用摘要 API 自动获得的能力。

## 旧直连引擎的复用条件（历史）

`DirectTaskEngine` 默认对普通 `inspect_screen` 解读启用单项内存缓存。每次仍获取新截图；复用必须满足以下条件全部一致：任务与执行代、问题、用户目标、完整语义观察（仅排除采集时间）、当前图像内容、包名、屏幕与图像尺寸、方向、已加载资料、设备环境以及已接受动作历史。

缓存仅保留一次成功解读的摘要键和最多4000字符的文本，有效期30秒，不落盘。原有视觉解读仍按既有任务历史规则保存。不同实际图像即使携带相同画布节点ID或报告摘要也不会命中；缓存键包含传入图像内容。新的语义状态、资料、动作、暂停/恢复和进程重建会使旧解读不可复用。数据来源不完整或尺寸不匹配时也不复用。

视觉动作定位、动作后的结果检查、手势坐标、一次性许可、批准和完成证据均不在缓存范围。复用的解读仍是模型解释，绑定本次新设备来源，并标记 `reused_read`，不能作为历史动作重放或自动成功的授权。

上述设计未增加跨任务路线记忆或自动回放。该只读缓存随旧引擎保留在 JVM 历史测试中，不是当前 `SplitTaskEngine` 的运行时缓存；Python 网关的精简观察不在本页迁移范围内。

## 旧引擎诊断（历史）

旧 `DirectTaskEngine` 任务返回可选 `perception_metrics`：

| 字段 | 含义 |
| --- | --- |
| `planner_calls` | 该历史版本记录的规划请求次数 |
| `vision_calls` | 普通视觉解读及操作后核对的请求次数 |
| `grounding_calls` | 视觉目标定位请求次数 |
| `read_cache_hits` | 满足全部条件并复用普通只读解读的次数 |
| `screen_source_chars` / `screen_summary_chars` | 最近一次原始观察JSON和模型摘要的字符数 |
| `screen_nodes` / `screen_shown_nodes` / `screen_truncated_nodes` | 最近一次输入、展示及截断节点数 |

这些分阶段计数从启用本版本时开始，旧任务已有总calls不会被猜测分配到各阶段。实际token仍由供应商usage记录；字符减少或一次复用不能换算成固定费用或整个任务的统一加速比。
