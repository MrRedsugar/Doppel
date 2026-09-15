# 定时任务 SDK

调度属于宿主，不要求模型假装等待。Android 本机与 Python 网关提供相同的 `once`、`interval`、五段 `cron` 合同；每次到点创建普通 run，继续使用现有模型、设备权限和任务控制。

## Android 开发入口

开发应用侧栏与设置的「定时任务」进入 `ScheduleActivity`。支持新建、编辑、停用、重新启用、删除，显示下次时间、等待原因、最近状态及关联 run ID。每天保存为 cron。

先完成使用同意与连接，启用无障碍和悬浮权限。不要求提前手动开启执行会话：到点检查就绪后，创建任务并启动现有 `DeviceWorkerService`；「开启执行会话」仍可用于保持可见服务和前台检查。到点时不能有执行中、暂停或等待确认的其他任务。设备锁屏时，只有用户单独开启本机自动解锁且系统与权限就绪，才能先解锁再派发；未开启时仍等待用户亮屏解锁。系统拒绝后台启动服务时不会绕过限制，本机不开始操作，保留任务记录并尝试暂停已创建的任务。通知权限关闭时只能从列表查看状态。

请保留系统锁屏密码，不要求取消锁屏密码或关闭自动锁屏。本机自动解锁默认关闭，只有阅读风险、明确确认并通过系统设备凭据验证后才能保存开启；不是创建定时任务时默认附带的权限。

Android 使用非精确 `JobScheduler`。系统省电、强行停止或厂商限制可能延迟执行；不申请精确闹钟权限，不绕过后台前台服务限制。可见会话每十秒检查计划，系统任务按下次时间再次唤醒。重启、应用更新、时钟变化只重新注册唤醒，不直接操作手机。

### 自动任务预告与本机解锁

定时执行与控件触发共用本机准备流程。判定依据为系统亮屏和锁屏状态，并不能可靠判断持机者身份，或仅凭无人触摸就确认手机安全。

| 触发时的设备状态 | 行为 |
| --- | --- |
| 亮屏且已解锁，包括用户正在使用手机 | 先显示 15 秒预告，允许立即执行或跳过；定时任务另有推迟 10 分钟。倒计时结束后重新核对任务、连接、权限和设备状态，仍就绪才创建任务。 |
| 已锁屏或熄屏，自动解锁关闭 | 不输入密码。定时任务在原有迟到窗口内等待用户亮屏解锁；未满足条件的控件触发不会创建任务。 |
| 系统已锁定，自动解锁开启 | 显示触摸遮罩，代码唤醒真实系统锁屏，读取当前 System UI 无障碍控件并输入本机保存的密码；确认系统确实解除锁定后才创建任务。此分支不再显示亮屏使用中的 15 秒倒计时；仅熄屏但系统尚未锁定时不会创建密码保护会话。 |

控件触发仍要求无障碍实际观察到规则匹配的控件。自动解锁不意味着能透过锁屏持续识别被遮住的应用页面，也不会为了扫描所有应用而主动解锁。两个来源继续只创建任务记录，不创建聊天对话。

「定时任务」与「自动触发」页面均提供「自动解锁设置」按钮，进入同一个 `AutomaticUnlockSettingsActivity`，展示完整风险说明；两类规则不会各自保存一份锁屏密码。当前要求 Android 11 或以上及已设置的安全锁屏，支持 4–16 位数字 PIN，或 4–64 位英文、数字和符号密码；图案锁不支持。实际输入还依赖系统锁屏提供可辨识、可操作的无障碍节点：PIN 需要能够唯一识别完整数字键盘，文本密码需要可编辑的密码框。读取不到、存在歧义、已有部分密码或系统拒绝输入时停止，不猜坐标、不调用模型兜底。解锁输入失败或结果不确定后停用自动解锁，不在后续调度中反复尝试；更改系统密码后需重新设置。

设置时的 `DEVICE_CREDENTIAL` 验证仅证明用户有权修改设置，不能证明用户另外填入的密码与系统密码一致。页面明确提示「所填密码尚未经实际解锁验证」，保存成功不等于已完成设备兼容性或完整解锁验收。密码不会显示回填，不接受通过任务指令或 Intent 传入，不进入模型请求、任务记录、日志或截图。输入页面使用 `FLAG_SECURE`，禁止自动填充和剪贴板菜单；密文由独立 AndroidKeyStore AES-GCM 密钥保护，原子写入应用私有 no-backup 目录，关闭功能删除密文与密钥。

**必须显著告知风险：为了锁屏时能自动解锁，该密钥允许 Doppel 在锁定状态下读取密码。设置时使用系统认证不等于每次后台读取也要求用户认证。手机会被实际解锁，普通应用无法在这个期间继续提供系统锁屏级保护。** 全屏无障碍遮罩可拦截普通触摸，也可覆盖桌面，但不能视为不可绕过的设备锁；系统界面、强停、进程死亡、服务被撤销，以及执行手势临时让出触摸的窗口都可能让他人使用设备。这项功能不能承诺防盗保护，默认关闭且开启前必须明确确认；共享设备或无法接受被盗用风险时不应开启。

派发屏幕手势时，遮罩短暂设置为不接收触摸，等待主线程确认和窗口更新后执行，结束后恢复；在这一间隙真实手指也可能触及下面的应用，不能保证只允许程序注入。用户已经按住接管按钮时不再开始新的手势放行。截图优先使用 Android 14 的应用窗口接口；整屏截图兼容路径仅短暂隐藏遮罩像素，保留触摸窗口，截图结束后恢复显示。旧截图或手势结束回调不得打断已经开始的本机密码验证。

通过自动解锁启动的任务在成功、失败、取消、暂停或中断后，会冻结执行并请求系统重新锁屏，确认真实锁定后才移除遮罩。系统拒绝重新锁屏时保留遮罩并提示用户用电源键锁屏，这仍不构成系统级安全保证。进程恢复后可根据本机遗留标记尝试重新锁屏并停用未确认的解锁尝试，无法保护进程已死到恢复之间的空窗。

保护会话只属于真正从系统锁定状态、由本机代码输入保存密码后进入的自动任务。`AutomaticUnlockSession.prepare` 在入口与开始执行前检查系统仍然锁定，解锁器回报是否实际输入过凭据。若用户在等待期间自行解锁，清理尚未派发的保护会话，后续调度仍需走普通预告流程，不因自动任务或手动继续而重新强加密码遮罩。

该自动解锁会话中的「接管」「停止」必须连续长按 3 秒，界面显示进度条和累计秒数。按住期间只延后尚未下发的新手势，不截断已在执行的手势；未满 3 秒松手后继续同一个待执行动作，不生成失败回执或额外模型请求。满 3 秒后等正在执行的观察或动作收尾，再在原有无障碍遮罩内显示本机密码框，不重新锁屏，也不调用系统验证页面。等待手指的动作临时退出在途计数，避免动作等长按、验密又等动作的互相等待；验密未通过时仍恢复同一个动作许可。

本机界面分别显示「验证后接管／确认并接管」与「验证后结束任务／确认并结束」。PIN 使用遮罩内数字键盘，文本密码使用受保护的系统输入法；密码始终掩码，仅与本机加密保存的凭据比较，不发送给 AI，不出现在截图、无障碍节点或事件文本中。5 秒无操作或连续 3 次错误会关闭输入框，提示「已返回任务」并保留任务代次、进度和待执行动作。输入或触摸重置可见的空闲倒计时；单次验证最长 30 秒，退出后 10 秒内忽略重复长按请求。只有正确密码才暂停或结束原任务并移除遮罩，此次主动交接不重新锁屏。任务自身成功、失败、取消或中断的重新锁屏逻辑仍保留。

遮罩持续标注「自动任务」。受保护会话尚未验证时，任务冲突通知不能直接替换或结束当前任务，只提示先长按验证并保留通知；正确验证结束原任务后可再次从通知继续执行。原本已解锁的普通任务无需这个密码步骤。

```kotlin
val gateway = Gateway(context)
val plan = gateway.request("POST", "/schedules", JSONObject()
    .put("device_id", gateway.prefs.getString("device_id", ""))
    .put("goal", "打开系统设置，告诉我手机型号")
    .put("mode", "ask")
    .put("rule", JSONObject().put("kind", "once")
        .put("at_ms", System.currentTimeMillis() + 60000)
        .put("timezone", "Asia/Shanghai")))
gateway.request("PATCH", "/schedules/${plan.getString("id")}", JSONObject().put("enabled", false))
```

`ScheduleManager.get(context).request(method, path, body)` 提供 CRUD，修改后自动 `arm()`。Worker 循环调用非阻塞 `tick()`。SDK manifest 已包含 `ScheduleJobService`、`ScheduleBootReceiver`，以及持久化与网络约束所需的正常权限 `RECEIVE_BOOT_COMPLETED`、`ACCESS_NETWORK_STATE`；不需要运行时权限弹窗。定义存应用私有 no-backup `schedules-v1.json`，原子替换；最多 100 个定义，每个保留 100 次历史，总文件上限 4 MiB。损坏或超限不会静默清空。

Android CRUD 返回额外 `background_wakeup`：`status` 为 `unknown`、`scheduled`、`idle` 或 `waiting`，并附 `checked_at_ms`、`reason` 和 `status_persisted`。`scheduled` 只表示系统接受唤醒请求，不保证准点或任务成功。权限缺失、系统拒绝、系统服务异常分别为 `permission_missing`、`system_declined`、`system_unavailable`；原异常细节不会返回。状态独立保存在应用私有 `doppel_schedule_wakeup` 偏好中，诊断状态写盘失败也不会否定已经保存的计划。客户端收到创建 ID 后不要因后台等待而重复创建。开发页面会显示等待提示与重试按钮，前台 tick 仍遵守原来的就绪/授权/过期规则。

读取计划不重新设置系统计时。变更、启动/时间广播或显式 `arm()` 可重新申请唤醒并更新状态；`GET` 只返回最后一次状态。`ScheduleRuntimeTest` 的 `cleanup_schedule_id` 参数仅用于已知失败测试留下的精确 UUID，并核对固定 fixture 目标、设备与空历史后删除；不能用它清理普通用户计划。

手机的 `/schedules` 始终由手机管理，即使模型连接是远程网关。Python HTTP `/v1/schedules` 由服务器管理，定义存服务器。两者不自动同步，不应在两端为同一业务重复注册；最终 runtime 仍有单设备排他检查。

## Python 网关与嵌入

`doppel serve` 与产品服务已挂载 `/v1/schedules`，使用原有 Bearer 身份验证。定义、读取、修改、删除按 owner 隔离，不能为其他账户的 device 创建计划。公开 `create_router(runtime, owner_dependency)` 同时挂载 scheduler lifespan；应用关闭时先结束调度循环，再关闭 runtime。`app.state.scheduler` 可用于测试与诊断。

```python
from doppel.scheduler import ScheduleRule, Scheduler

daily = ScheduleRule(kind="cron", expression="30 9 * * *", timezone="Asia/Shanghai")
next_ms = daily.next_after(now_ms)  # 严格晚于 now_ms

# 不使用 create_router 的嵌入宿主显式管理生命周期。
scheduler = Scheduler(runtime)
plan = scheduler.create(owner, {
    "device_id": device_id, "goal": "检查测试应用今天的记录", "mode": "ask",
    "rule": daily.to_dict(),
})
scheduler.start()  # 必须在所属 asyncio event loop 内调用。
# 关闭时先 await scheduler.close()，再 await runtime.close()。
```

定义存原有 `runtime.sqlite3` 的独立 `schedules` 表。沿用每个 data directory 一个 runtime 进程的约束，不能多个服务器共享。每十五秒检查一次，默认要求设备最近九十秒内注册或轮询命令；这只证明连接，不代替 Android 解锁与权限检查。嵌入宿主可以传 `readiness(owner, device_id)` 回调，返回原因码表示等待，返回 `None` 才尝试创建 run；该回调不能批准动作。Windows 的 IANA 时区依赖已打包的 `tzdata`。

## HTTP 合同

| 方法与路径 | 行为 |
| --- | --- |
| `GET /v1/schedules` | 当前 owner 的 `{"items": [...]}` |
| `POST /v1/schedules` | 创建，返回 201 与定义 |
| `GET /v1/schedules/{id}` | 定义及有限运行历史 |
| `PATCH /v1/schedules/{id}` | 编辑，或 `{"enabled": false}` 停用 |
| `DELETE /v1/schedules/{id}` | 删除，返回 `{"deleted": true}` |

输入为 `device_id`、`goal`、`mode`、`allowed_packages`、`rule`、`enabled`。`mode` 为 `ask`、`assist`、`full`，默认 `ask`；默认启用。未知字段被拒绝，不能注入永久批准、支付同意 ID 或脚本。网关目标最多 12000 字、本机直连 8000 字。本机直连目前不支持 `allowed_packages`，非空时明确拒绝，不能默默丢掉范围；网关沿用普通任务应用范围。

| 规则 | JSON 示例 | 语义 |
| --- | --- | --- |
| 一次 | `{"kind":"once","at_ms":1788917400000,"timezone":"Asia/Shanghai"}` | UTC epoch 毫秒，创建时必须为未来时间 |
| 间隔 | `{"kind":"interval","every_ms":3600000,"anchor_ms":1788917400000,"timezone":"UTC"}` | 固定锚点，最短 60 秒、最长 365 天，不随执行耗时漂移 |
| Cron | `{"kind":"cron","expression":"30 9 * * *","timezone":"Asia/Shanghai"}` | 每日当地时间 09:30 |

建议显式传 IANA 时区，省略则统一 `UTC`，不使用服务器本地时区。interval 为固定 UTC 时长，时区仅用于展示。cron 五段为「分钟 小时 日 月 星期」，支持数字、逗号列表、范围、`/` 步长；星期 0 和 7 都表示星期日。不支持秒段、名称别名、`L/W/#` 或代码。

日和星期都不是 `*` 时按传统 OR：`0 9 15 * 1` 表示每月 15 日及每周一 09:00。夏令时不存在的分钟跳过，重复出现的分钟只执行第一次。未来八年内仍无下一次则拒绝规则。手机编辑单次时间同样拒绝不存在的本地时间。

输出增加 `id`、`created_at_ms`、`next_due_ms`、`waiting_reason`、`history`。每次历史有 `scheduled_at_ms`、`at_ms`、`status`、`run_id`，可有原因和结束时间。`started` 只表示实际 run 已创建，不能宣称目标完成；其后追踪 `completed`、`failed`、`cancelled`。Android 另保存不可逆连接摘要 `binding`，不含凭据。

## 迟到、取消与恢复

允许迟到五分钟，设备忙、锁屏且尚未完成解锁、或权限不足时在窗口内等待。过期记录 `missed`；循环计划直接计算未来一次，绝不密集补跑。一次计划派发或错过后停用，保留记录。在计划启用时修改时间规则，或从停用改为启用，才从当前时间重新计算下次时间；仅修改目标、权限模式或应用范围会保留原定时间。过期一次任务重新启用前必须选择新的未来时间。

停用和删除阻止后续派发，不会暗中取消已有 run。已有任务仍通过普通 pause、resume、cancel、answer 管理。保存计划不保存未来动作批准；每次重新进入现有权限策略、系统中断和支付检查。

创建 run 前先原子保存 `dispatching` claim，成功后先保存 run_id，再启动设备执行。用户在创建中接管会保留暂停状态。创建结果不确定、进程崩溃或创建后无法写盘时，计划为 `uncertain` 并停用，不自动付费重试；核对任务列表后才能重新安排。极小的「run 已创建但 ID 尚未落盘」窗口可能无法自动关联，不能假设没有执行。

切换账户/服务器不会静默移交旧计划，显式编辑才重新绑定。历史逐次保留连接摘要，避免把旧 run ID 发往新服务器查询。

## 验证与研究

`framework/tests/test_scheduler.py` 验证规则、DST、重启、错过、停用删除、实际 runtime 关联、HTTP 鉴权与生命周期、写盘失败；产品测试另验跨账户拒绝。Android `ScheduleTest` 是纯 JVM 合同测试，`ScheduleRuntimeTest` 使用实际私有文件与持久 JobScheduler 验证 once CRUD，并用隔离的提交 gate 阻止派发。后者不调用模型、不操作屏幕，不能代替端到端模型任务验收。

自动解锁另有以下设备测试，实际运行结果与证据由 `docs/verification/` 下本次报告记录，本节不把测试代码存在视为测试通过：

- `AutomaticTaskNoticeDeviceTest`：真实 15 秒悬浮预告、桌面可见、触摸不重置计时、跳过后不执行、熄屏取消，以及保留系统密码和默认关闭的设置文案。
- `AutomaticTaskDispatchDeviceTest`：定时与控件触发分别从已解锁、锁屏两种状态派发；经生产调度、启动器和 Worker 创建单个独立任务，读取完成状态后清理任务指针，锁屏启动的分支还需重新锁屏；另含派发期间计划推进与会话状态变化的并发回归。网关为本机隔离桩，不调用模型。
- `AutomaticUnlockDeviceTest`：真实 PIN 自动输入、已解锁来源拒绝、桌面遮罩、短按延后同一手势且松手后仅放行一次、满长按与排队动作无死锁、本机密码输入脱敏、可见空闲倒计时及输入重置、三次错误与冷却后继续、正确密码接管不重新锁屏、真实手势放行与恢复、旧截图结束回调不取消验密、派发失败重新锁屏、错误 PIN 停用且下一次不重试，以及遗留恢复标记的处理。恢复检查通过预置标记调用生产恢复入口，不等于实际杀进程后的完整恢复验收。
- `AutomaticUnlockSettingsDeviceTest`：不确认风险不能开启、取消系统验证不保存、真实系统验密后加密保存、重新打开不回填密码，以及关闭时删除密文与密钥。

纯 JVM `AutomaticUnlockCredentialsTest` 只验证支持格式和读取缓冲区擦除，编译成功或手动验证系统 PIN 也不能替代上述流程检查。这些测试不证明任意模型任务能成功，不代表其他品牌系统兼容；图案锁不在支持范围。本机接管不调用系统 keyguard；初次自动解锁仍依赖各系统的真实锁屏控件，其兼容性应按设备报告如实记录。

参考 OpenClaw 固定提交 `62fca0de772fbd5c3046109820d7468e1e863c2b` 的 [cron 文档](https://github.com/openclaw/openclaw/blob/62fca0de772fbd5c3046109820d7468e1e863c2b/docs/automation/cron-jobs.md) 与 [schedule.ts](https://github.com/openclaw/openclaw/blob/62fca0de772fbd5c3046109820d7468e1e863c2b/src/cron/schedule.ts)。独立实现，没有复制 OpenClaw/Croner；迟到和确认策略按手机操作另行定义。
