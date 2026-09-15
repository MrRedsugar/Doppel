# 可选视觉增强的手机执行链路

本页对应 Android `0.1.0-alpha.37-small-scroll`，运行器版本为 `split-v9-small-scroll`。本机任务由 `SplitTaskEngine` 驱动；A/B 是开发术语，产品设置为“默认模型”和可选“视觉增强”。开启增强使用 A/B；关闭后 A 直接输出动作与坐标，完全跳过 B。

本文只描述 Android Kotlin 本机链路。Python 网关仍使用原有 Harness 执行器；千问或自定义平台适配不表示 Python 已接入 `SplitTaskEngine`，Android 新内核测试结论不能直接用于网关。PC 游戏窗口可作为另一种执行环境，但截图回放、合同测试或 Android 实测都不构成 PC 闭环已通过的证明。平台配置见[模型连接](../developer/model-connections.md)。

## 执行流程

1. 宿主采集截图并保留内部图像、尺寸、旋转、窗口及坐标映射。
2. A 读取最新截图、明确标为历史的对比图、任务状态、动作回执和按需加载的 Skills，输出本轮决策。
3. 开启视觉增强且 A 要执行设备动作时，宿主在 A 返回后重新截图。B 只接收新图、动作目标、预期变化、必要的页面关系与动作合同；每轮独立，不接收任务历史、UI 树、包名、Activity、截图编号、时间或尺寸。
4. B 独立核对目标并输出 0–1000 动作坐标或简短拒绝。宿主严格校验输出结构、动作种类、坐标范围、点数、时长、方向和浏览幅度，再映射到实际屏幕。
5. 设备返回动作回执；动作结束后保留约 500ms 的观察延时，再截图。A 根据新图判断业务结果、等待或选择恢复路线。B 的执行前核对不代表动作已成功。

关闭视觉增强时，第 3、4 步由 A 在原截图上直接输出动作坐标替代，不另调 B、不生成 B assessment。direct 动作绑定 A 实际读取的截图；不能把旧坐标绑定到后补的新图。审批后重新观察并重新生成动作，不重放审批前坐标。

默认 A 为 `qwen3.8-flash`，B 为 `qwen3.8-max`。官方百炼及 workspace maas 路由对两者均发送 `enable_thinking=false`，不发送 `reasoning_effort`，也不把内部 `_doppel_role` 发到模型平台。两种角色均需通过图片能力探测。Python 网关的原有 low 主模型策略不在本次 Kotlin 修改范围内。

任务创建时保存 `execution_mode=ab/direct`，本任务中固定，重启后沿用；没有该字段的旧任务按 AB 恢复。SDK 通过 `enhancementEnabled` 回调指定新任务模式。任务进行中仍不能修改模型设置。

## 严格结构化输出

`response_format.type=json_object` 只能要求 JSON，不能固定字段与类型。当前请求使用 `json_schema`、`strict=true`，由 `SplitOutputSchema` 按 A 的执行模式和 B 的具体动作生成结构。各分支只包含该决策需要的字段：拒绝不要求坐标，点击不要求滑动方向，等待不包含动作坐标。

官方[结构化输出文档](https://help.aliyun.com/zh/model-studio/qwen-structured-output)明确列出 Qwen3.8-Flash/Max 支持 Schema。供应商层保留严格模式；官方未列入支持范围的 Qwen 模型明确报错。自定义兼容接口透传同一 Schema，服务端拒绝时不会去掉约束重试，也不声称未知接口已经支持。

本地还独立执行严格 JSON 语法检查、与本次请求 Schema 一致的字段验证和动作语义验证。截断、尾随文本、字段缺失、类型错误、未知字段或错误动作分支均不能成为设备命令。旧平面 JSON 仅存在于历史记录或不带新格式参数的底层协议调用；生产新请求只接受以下包装。

A 的根对象为 `decision` 和 `state`。A Schema v2 使用唯一的 `decision.kind` 区分具体动作或控制决策，例如 `tap/swipe/wait/finish`，不再同时要求 `kind=execute` 和 `action=swipe`。校验通过后，宿主将明确的设备动作映射为引擎内部 `kind=execute/action=具体动作`；这不是修复或猜测无效输出，未知 kind 仍拒绝。B 合同保持 v1。

`state=null` 表示不更新已有状态；非空对象含 `phase/facts/completed_steps/remaining_steps/failed_routes`。`facts/completed_steps/failed_routes` 按状态管理规则合并，`remaining_steps` 是本次明确替换的剩余计划，不用空数组充当“不更新”。

```json
{
  "decision": {
    "kind": "tap",
    "target": "右上角的保存图标",
    "expected": "出现文件保存提示",
    "screen_context": "文件编辑页面"
  },
  "state": null
}
```

direct 模式同一 `decision` 还须包含 `points:[[520,120]]` 和 `duration_ms:60`。A 执行支持 `tap/long_press/double_tap/swipe/swipe_sequence/type/back/home/recents/notifications/quick_settings/enter`。不是预先猜测未来页面坐标的多任务批处理。

`wait`、`finish`、`ask_user`、`search_web`、`read_web`、`load_skill`、`read_skill_resource` 由 A 直接决定，不调用 B。知识工具无权创建或修改定时任务、Skills 或模型设置。输入窗口只用于设备任务，不提供聊天助手功能。

## B 的紧凑动作合同

B 输入有 `action/target/expected` 和可选 `screen_context`；文字输入还含 A 指定的 `text`。`expected` 是本动作后可观察的变化，不只是远期目标。A 需提供足够的同名目标区分依据；B 不得自行换目标、扩展任务或替换输入文字。

```json
{
  "result": {
    "status": "located",
    "action": "tap",
    "points": [[520,640]],
    "duration_ms": 60,
    "assessment": {"alignment": "consistent"}
  }
}
```

新 B 成功输出仅含执行参数与紧凑核对字段，不输出 `observed/reason/correction_reason` 解释段落。成功分支的 `assessment.alignment` 固定为 `consistent`。拒绝为独立分支，例如：

```json
{"result":{"status":"ambiguous","reason":"两个同名入口缺少可见区分依据"}}
```

拒绝状态为 `not_found/ambiguous/unsupported/intent_mismatch`，只有简短原因，不携带可执行坐标或 assessment。拒绝交回 A 重新决策，不自动变成任务失败。

| 动作 | 公共 status/action/assessment 之外的执行字段 |
|---|---|
| tap / long_press | 一个点及 duration_ms；长按 500–3000ms |
| double_tap | 一个点表示原地双击，两个点表示依次点击两处；duration_ms、interval_ms |
| swipe | 至少两个路径点及 duration_ms；assessment 增加方向字段 |
| swipe_sequence | 1–8 个 strokes，各有路径和时长；interval_ms；assessment 含逐段方向合同 |
| type | 与 A 完全相同的 text |
| back / home / recents / notifications / quick_settings / enter | 不需要坐标 |

宿主补充 `phase=before_action`，通过 `grounding_result` 与 `last_receipt.grounding_assessment` 向 A 提供核对记录。没有第二次执行后 B 请求；实际结果由 A 看新截图确认。

## 滑动距离与方向修正

浏览动作明确携带 `swipe_extent=small|large` 和 `scroll_goal=inspect|boundary`。A 普通找目标必须 small，目标描述也写明小幅滑动；兼容解析遇到缺省幅度时补 small。Schema 中 `boundary_reason` 为字符串，普通浏览可为空，large 浏览必须明确 `scroll_goal=boundary` 且原因非空。

small 的轨迹沿移动轴通常为屏幕该轴的 5%–15%，接近目标可以更短；large 为 30%–60%，仅用于明确直达边界。新请求不再提供 medium；旧历史只读保留。宿主验证每段累计轴向位移不超过该档上限，不缩放或改写 B 坐标。提示词要求低速拖动，建议 600–1000ms，并依据实际结果继续缩短或减速。短手势仍可能因应用惯性移动很远，标签 small 不保证地图位移一定小。

A 的单段合同为 `gesture_semantics/target_relative_direction/intended_finger_direction`；连续滑动使用逐段 `gesture_contracts`。方向按八方向表示，坐标 x 向右、y 向下增加。`reveal_content` 表示要查看画面外内容；露出右侧内容需手指左滑，露出左侧需右滑，露出下方需上滑，露出上方需下滑。当前内容本身跟手指同向移动，不能混淆“向右移动内容”和“露出右侧内容”。

B 根据当前图独立返回 `target_relative_direction/required_finger_direction/direction_corrected`；序列逐段返回。若 A 把浏览方向写反而目标及操作区域可确定，B 可以保持同一目标，修正方位与手指方向，标记 `direction_corrected=true` 并输出修正后的轨迹。宿主按 B 的有效方向合同验证坐标，保留 A 原合同、有效合同与修正记录交给 A。目标身份或页面关系本身不明确仍应拒绝。

`physical_gesture` 指明确手指运动、游戏拖放或系统手势，不能套用内容浏览的反向规则，也不能擅自反转 A 的物理手势。物理动作不受默认浏览距离限制。执行器始终按校验后的模型轨迹执行，不在底层统一翻转方向。

## 连续滑动逐段观察

`swipe_sequence` 被宿主拆成逐段设备命令。每段完成后等待动作后的观察延时、截图，再派发下一段；两段之间没有 A/B 调用。截图耗时计入序列的 `interval_ms` 最小间隔。

整组结束后，A 按顺序同时查看各段结果；中间截图标明历史，最后一张明确为当前最新截图。目标在中间出现、最后消失时，A 可据此决定小幅回滑；不能直接点击历史目标坐标。

档案保留 `sequence_id/stroke_index/stroke_count`、原始定位 `grounding_capture_id` 与各段实际 `source_capture_id`。中断、失败、缺图或导航边界变化停止剩余段，不重放已执行段。同一游戏画布内部的场景变化未必产生原生窗口边界，不能据此保证剩余手势永远不会失去语义目标。

## 等待与恢复

等待是 A 的决策，不是“截图有动画就冻结”。每次等待须有当前帧依据 `evidence`、可观察的结束条件 `wait_condition`、简短 `reason` 与 100–30000ms 的 `duration_ms`。

```json
{
  "decision": {
    "kind": "wait",
    "duration_ms": 1000,
    "reason": "资料仍在同步",
    "evidence": "本帧同步进度为60%，操作入口尚不可用",
    "wait_condition": "进度完成且出现可用入口"
  },
  "state": null
}
```

宿主记录连续等待次数、连续等待开始时间、本次依据、结束条件和请求时长。下一轮给 A 最近一次等待前的历史图及当前最新图，并提供 `wait_observation.elapsed_ms`。连续等待两次后 `reassess=true`，提醒 A 检查条件是否已解除、入口是否已出现；这是重审提示，不按次数拒绝第三次等待。真实加载仍可继续等，已经出现 START、确认按钮、列表或键盘入口时应按当前图选择动作。正常视频、背景动效和静止菜单都不应自动视为“还在加载”。

A 选择非 wait 决策后清除本次等待观察；暂停、取消或恢复也不复用旧等待图。等待命令只做可中断计时，不等待无障碍根节点或像素静止，下一条独立截图命令负责采集新图。来电、用户接管等中断仍适用。

技术性采集失败会清除当前截图及待定位意图，向 A 给出 `current_capture.status=unavailable` 和原因。历史图片仍明确标为历史。没有有效当前图时不能 execute 或成功 finish，也不会用旧图请求 B；A 可等待重采、查资料、问用户或以 failed 说明限制。隐私、登录或其他 `human_takeover` 原因仍按接管路径处理中断，不新增绕过敏感界面检查的备用上传分支。

## 状态、取消与执行边界

持久状态包括目标、补充、事实、阶段、已完成步骤、剩余计划、失败路线和最近回执。跨任务延续语义结果，不继承旧坐标或审批。原地重复点击不被一概禁止，多次无进展后 A 应调整路线。

回执区分 `accepted/unconfirmed/not_dispatched`，并保留完成段数、未确认段数和耗时。Android 接受手势不能证明订单、表格或游戏成功。取消、暂停、进程恢复使旧工作与命令失效；迟到回执仅存入历史，不恢复执行。

保留每段 400 次模型调用或两小时预算，以及既有操作无进展计数提醒与 32 次暂停规则。该计数依赖模型记录的 completed_steps，wait 不计入，不能视为可靠的业务死循环判断。此次等待改进没有新增按连续等待次数硬暂停的规则。

执行器不使用额外截图的像素变化/对比度/背景动画检查来阻挡每次点击或滑动；回执中该核验仍标为未执行。原生应用/窗口、尺寸旋转、中断、锁屏与既有授权边界继续保留。付款仍走单独的用户手动开启与授权机制。

## 排查数据与验证范围

新任务记录 `runtime_version=split-v9-small-scroll`、`execution_mode`、模型调用数、分项耗时、输入/输出 token、语义动作、预期、方向修正、等待观察、设备回执和原始截图。A-direct 没有 grounding 模型调用；AB 保留 B 的执行前紧凑反馈。

`ModelApi` 从最终发送请求提取 `_doppel_request`，模型 trace 的 `wire` 保存 `model/response_format/schema_name/schema_strict/enable_thinking`（仅存在时）。不包含请求头、凭据、消息或图片。这证明客户端实际发送的配置；兼容服务是否遵守仍需本地结构校验和实际回归确认。

以动作后截图和业务状态评估结果，不能仅看模型 finish 或 Schema 通过。单元测试、图片回放、PC 实测、模拟器与真机是不同层次的证据；本文不宣称 PC 版或 1-7 二倍代理已通过。个人截图和轨迹保留于私有工作区，不随公共源码导出。

实现入口：[SplitTaskEngine](../../android/sdk/src/main/java/dev/doppel/sdk/SplitTaskEngine.kt)、[SplitAgentProtocol](../../android/sdk/src/main/java/dev/doppel/sdk/SplitAgentProtocol.kt)、[SplitOutputSchema](../../android/sdk/src/main/java/dev/doppel/sdk/SplitOutputSchema.kt)、[DirectionalGestureContract](../../android/sdk/src/main/java/dev/doppel/sdk/DirectionalGestureContract.kt)、[SwipeExtentPolicy](../../android/sdk/src/main/java/dev/doppel/sdk/SwipeExtentPolicy.kt)、[DoppelAccessibilityService](../../android/sdk/src/main/java/dev/doppel/sdk/DoppelAccessibilityService.kt)。
