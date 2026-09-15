# 连续会话与视觉执行（alpha16）

## 当前入口与历史范围

当前 Android 任务使用 `DirectRuntime → SplitTaskEngine`，模型与视觉增强通过 [模型连接](model-connections.md) 配置。`Gateway.createConversationRun` / `startNewConversation` 继续承担用户对话关联；定时和控件触发任务显式关闭对话创建，不推进用户会话。当前聊天、任务复盘与普通 Skills 的实现，不依赖旧 `continuous_agent`、`feedback_operator` 或 MiMo 专用视觉循环。

本文以下保留 alpha16 / alpha20 的历史协议和测量。旧引擎的 15 个实现文件（共 17 个顶层类，包括 `DirectTaskEngine`、`VisualAgentLoop`）已移至 `android/sdk/src/test/java/dev/doppel/sdk/legacy`，包名仍为 `dev.doppel.sdk`，仅参与 JVM 测试，不随生产 APK / SDK AAR 提供。旧 `LearningLiveTest` / `PerceptionLiveTest` 位于 `labs/legacy-direct-engine/androidTest`，不再默认编译。共享观察采集仍有生产调用；`ModelScreenSummary` 保留为公开 API，当前 Split 未调用。它们不表示旧引擎或自动学习仍启用。历史证据不代表当前版本，具体结果见对应版本验证记录。

下文的实验开关、MiMo 分工、旧 `finish.basis`、像素核验以及具体历史上下文上限，不应直接套用到当前 Split 协议；当前运行方式见 [手机直连说明](phone-direct.md)。

## 历史 alpha20：运行内连续事务实验

此节记录 2026-09-09 的实验状态及其 alpha16 会话基础。

当时开发构建的 `doppel` SharedPreferences 布尔项 `continuous_agent` 默认为 false，需要在创建旧运行时之前设置，实验 instrumentation 使用 `-e continuous_agent true`。这是历史实验配置，不是当前 App 的启用步骤；修改该偏好或重启当前 App 不会恢复已经移出生产的引擎。

实验启用后，统一 MiMo V2.5 多模态操作器持有本轮任务的阶段计划与原始工具事务。`set_task_plan` 参数为 `revision`（旧版本+1）、`reason` 和 1..8 个 `{id, objective, exit_condition}` 阶段。原生/视觉动作工具额外要求：

```json
{"task_progress":{"stage_id":"open_editor","status":"continue","observation":"当前仍在文件列表","screen_id":"current-screen-id","evidence_id":"current-host-evidence-id"}}
```

`continue` 保持阶段，`achieved` 以本轮来源记录模型判断并推进一阶段，`revise` 要求修订剩余路线而不执行附带动作。所有阶段完成也只表示模型核验完毕；宿主的完成证据与授权检查仍独立存在。动作解析前移除元数据，回传的原始 assistant 工具参数不被重写。

`session_task_plan` 保存阶段及有界评估；`_session_trajectory` 是私有原始事务，最多8条完整配对事务、256KiB，历史观察文本最多12000字符/条，不保存图片。若供应商返回 `reasoning_content`，配对回传时完整保留；超限整条舍弃而非截断思考或拆开工具配对。当前图片仍随本轮观察发送。原始事务不能作为执行队列，进程恢复/暂停撤销未确认工作，重新观察后才可决定新动作。公共任务接口剥离 `_session_trajectory`；用户真实补充 `session_user_updates` 是有界的公开会话数据。

2026-09-09 同一构建通过824项SDK单测，但真实明日方舟导航和WPS新建输入保存均达到预设150秒上限，分别准备7和9次模型请求。前者仍停终端总览，后者仅进入空白工作簿。该实验补足状态与协议，**没有证明正确的GUI决策、任务成功或实时游戏能力**。GUI定位能力与GUI策略能力须分开评估。当时可关闭实验偏好切回原计划路径；当前清理不再提供这条生产回退入口。

用户交互单位是对话。完成一个请求后直接输入下一句，保留此前的查询结果；每一轮设备执行仍有独立的取消、计费、证据与授权生命周期。`completed` 结束本轮执行，不结束对话。只有显式“新对话”才断开当前关联。

## 历史会话存储与 SDK 协议

Run 新增可选 `parent_run_id`。父执行必须已经结束，并属于同一用户和设备。没有该字段的既有记录、定时任务和 SDK 调用继续作为独立会话根。

Android 用户入口统一调用 `Gateway.createConversationRun(body)`，正文与普通创建一致：`device_id`、`goal`、`mode`。该方法自动连接选中的末轮；`startNewConversation()` 开始空会话。文字、悬浮窗文字、长按语音共享该选择。会话选择保存在本机，按直连应用或服务器/设备/账号分隔。设置切换期间的旧请求不会覆盖新选择。

第三方也可显式调用：

```http
POST /v1/runs
Content-Type: application/json

{"device_id":"your-device","goal":"再和另一个账本比较","mode":"ask","parent_run_id":"previous-finished-run"}
```

`GET /v1/runs/{id}/conversation` 返回 `items`，按时间正序，字段为 `id, goal, message, status, created_at`。Android 直连的 Gateway 使用不带 `/v1` 的相同路径。权限与拥有者检查由运行时执行，插件和模型不能伪造跨用户关联。

历史每次从仍存在的源记录解析，不拷贝旧命令、审批、支付许可、坐标或临时屏幕缓存。当前自动上下文上限为12轮/24000字符，每轮目标最多3000字符、结果最多4000字符；模型调用保留此前失败和不确定状态。删除中间记录会截断更早的链。删除源记录不承诺抹去已经被后续回答引用的派生文本。

历史记录页还提供 `POST /v1/runs/{id}/review`（`{"question":"..."}`）。
它只读取有限的任务结果和事件，供用户追问失败原因、关键步骤和改进建议；任务必须已暂停或结束，接口不带工具、不接收截图，也不会恢复或派发设备动作。用户明确确认的纠错通过复盘记忆保存，并在后续规划中作为有界的语义提示使用。

Android 直连目前保留最近50个执行；这是有限本地记忆，不是无限上下文或长期语义检索。服务器接口使用相同轮次上限。当前记录列表仍以执行为单位展示；聊天页可展示关联轮次。

## 历史 MiMo 感知与视觉执行

无障碍提供可靠控件时，MiMo V2.5 Pro 使用短语义摘要、历史结果和操作回执规划。进入无标识画布、要求视觉操作或连续语义目标失效时，Android 直连切入 MiMo V2.5 视觉 Agent；Pro 在官方文档中属于文本模型。

视觉 Agent 的一次请求同时包含真实截图、图片尺寸、当前帧身份、当前语义证据、最近回执、会话历史及动态技能目录。模型直接选择点击、长按、滑动、系统导航、启动应用、读取技能/网页或返回结果。此路径没有“先询问截图描述，再交给另一个模型解释描述，再定位坐标”的必经环节。

模型用图片整数像素坐标提出动作。宿主完成尺寸转换、帧时效和目标像素核验、权限检查、一次性许可和派发。每次操作后重新取得画面再决定下一步；截图长边上限1920。旧目标在页面变化时失效，不自动重放。系统报告已执行手势只证明派发，不能单独证明业务成功。

工具格式错误返回结构化 `visual_tool_feedback`，无效动作不派发。每轮执行最多两次格式修正机会，并要求新截图；取消或新执行使正在返回的旧模型响应失效。此机制不放宽支付、验证码或其他风险校验，也不把未知字段静默转换为已知字段。

## 结果证据与助手自身界面

设备观察标记 `assistant_surface`。模型摘要屏蔽助手自身聊天节点，避免把“时钟已打开”这样的历史文本误读为目标 App 状态。主界面提交后退到后台，再启动执行。

`finish.basis` 为 `current_screen`（默认）或 `conversation`。前者完成时要求当前 `screen_id` 与 `evidence_id`，并拒绝把助手页面作为目标 App 的完成证据；后者仅适用于回答/比较已有记录，要求存在关联历史，不代表已执行新的设备操作。服务端/harness 的完成协议仍沿用原有验证，新增视觉 actor 和上述 basis 当前属于 Android 直连路径。

模型结论依然可能出错。当前实现能校验证据身份与来源，不能以通用规则证明任意自然语言结论，因此每项业务验收仍需独立截图或目标数据核对。

## 遮挡恢复

执行命令前，宿主可请求收起 SystemUI 顶部、可识别且提供 `ACTION_DISMISS` 的普通通知。每个节点最多2次、每轮最多4次；来电、闹钟、付款和验证提示排除。收起后重新观察，旧动作不派发。此策略仅覆盖系统公开的能力；其他浮窗、广告或 OEM 手势仍需要视觉处理或用户接管，不能宣称全机型已验证。

## 历史验证与迁移

本工作区的历史验收记录位于 `docs/verification/2026-09-08-alpha16.md`（含私人设备测试背景，不随公开源码导出）。当时父关联是持久化 JSON 的可选字段，旧二进制会忽略关联；当时的 `DirectTaskEngine` 构造参数 `visualControl` 可控制旧视觉协议。该构造方式现在只属于测试源码，不能用于切换当前生产直连运行时。
