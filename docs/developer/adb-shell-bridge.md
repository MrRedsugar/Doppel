# ADB 辅助执行后端（开发调试）

产品设置中的“ADB 辅助权限”入口及对应页面已删除。底层 ShellBridge、Android SDK API 和电脑端脚本保留用于开发调试；产品设置不再提供连接、启用或停用操作。Android SDK 提供 `ShellBridgeClient.get(context)`；辅助进程是同 APK 中的 `ShellBridgeMain`，由用户已经授权的 ADB shell 通过 `app_process` 启动，脱离电脑连接后继续在手机本机运行，重启失效。主执行引擎、结果账本、权限和完成证据仍由宿主负责。

## 开发调试中的激活与停用

在已安装包含该组件的开发 APK 后运行：

```powershell
./scripts/adb-shell-bridge.ps1 -Serial emulator-5554 -Package dev.doppel.developer
```

脚本只选择指定 transport，检查 shell UID 2000、安装包路径与 App UID，再启动本机进程；不重启 ADB、不改分辨率或调试设置、不启动任何业务 App，也不调用 App 端的激活 API 或写入启用开关。脚本启动本身不是 App 接通或启用证明。

开发者须在已授权的 App 端调试代码或测试准备代码中，于后台线程显式调用 `ShellBridgeClient.get(context).activate()`。该 API 完成连接、peer 身份核对、握手和 UID 2000 的 ping 检查后，才保存 `enabled=true`。以返回状态中的 `enabled`、`connected`、实际 `uid` 及能力列表确认启用；后续可调用 `status()` 检查。`status()` 仅在已有启用状态时尝试连接，不会把未启用的 App 激活。删除产品设置 UI 不会自动清除既有启用状态。

开发 APK 的 `PlannedControlSetupTest.configureOptionalLocalBackends` 提供显式 opt-in 准备路径：传入 `planned_setup=true`、`emulatorOnly=true` 和 `shell=true` 时调用 `activate()`，并断言 `enabled=true`、`connected=true`、`uid=2000`。该测试限 `LDY-ANO0` 或 `LDY_ANO0` 型号模拟器，要求 App 已启用 DirectMode 且没有未完成任务；须先安装对应测试 APK 并启动辅助进程。此开发测试不依赖已删除的产品设置页面。

停用由同一 App 端开发调试上下文调用 `deactivate()`：写入 `enabled=false`，有当前连接时尝试发送 `shutdown`，随后断开客户端。失联或 shutdown 失败时不能据此确认辅助进程已退出。辅助进程已退出或设备重启后，再次使用须先重新运行启动脚本，再调用 `activate()`；卸载重装导致 UID 或 APK 路径变化时也须重新启动并激活。

连接失败时可读取 `status(refresh = false).connection_error`，其中包含连接阶段、异常类及固定原因码，用于区分辅助进程未监听、系统拒绝、超时、身份不匹配和握手失败；不记录原始异常文本、请求内容或会话令牌。Android 9 上 `LocalSocket` 在 `connect` 时才创建文件描述符，因此客户端必须先连接，再设置读取超时。实机独立 probe 已复现旧顺序的 `IOException: socket not created`，新顺序取得真实 peer UID2000；App 激活仍须以安装后客户端实际返回状态验收。

Android 11+ 用户可在系统无线调试页面取配对端口与连接端口：

```powershell
./scripts/adb-wireless-shell-bridge.ps1 -PairEndpoint 192.168.1.20:37123 -ConnectEndpoint 192.168.1.20:39123
```

配对码由官方 `adb pair` 交互读取，不写入参数或文件。已经配对时省略 PairEndpoint。`-LegacyTcp` 仅用于用户已经设置好的旧 TCP transport；脚本不会调用 `adb tcpip` 或开放新端口。Android 9/API28不支持 Android 11 原生 TLS 配对，本轮不能据其测试声称无线 TLS 成功。App 内 TLS 配对客户端尚未实现；此脚本通过官方桌面 ADB 配对后启动同一个本机后端。

## 协议与授权边界

服务仅监听 Unix 域抽象 LocalSocket `doppel.shell.<appUid>`，无 TCP 端口。服务验证客户端真实 peer UID 等于目标 App UID，客户端验证服务 peer UID 必须是普通 shell 2000；不接受 root 代替。会话 256 位随机令牌只在已核对 peer 的 socket 握手与内存中存在，不放 argv、日志或 SharedPreferences。其他 UID 即使知道 socket 名也无法得到能力。

每条请求只能选固定操作，不能传任意 shell、可执行路径、任意 keycode 或文本。当前能力：截图、返回、主页、最近任务、菜单、点击、长按、滑动。导航只映射固定 Android keycode。输入坐标必须是当前设备整数像素，动作带 run/command ID、来源包名/屏幕ID/尺寸/旋转与单调时钟时间，5 秒请求截止、15 秒来源上限。手势另外绑定辅助进程刚取得的 capture ID；独立客户端默认再次取得原始 PNG 并要求完整 SHA 相同，动画可能导致保守拒绝。

App集成路径沿用既有 `VisualGesturePermits.consume`、原图/核验图 `VisualPixels.compare`（目标RGB、边缘、滑动路径与上下文）和尺寸/包名/旋转检查。全部通过后，UID认证宿主可发送 `pixel_verification=host_target_rgb_edges`，绑定刚核验的辅助进程 capture ID 与精确 captured_at。辅助进程要求它仍是自己最新截图且不超过1000毫秒，并再次核对请求时限和尺寸；这一专用证明避免另一次全图SHA将目标之外的时钟/动画误当目标变化。它不能由模型输出自授予，直接客户端不带证明则保留全图检查。截图压缩后的宿主VisualFrame.sha256独立计算，capture ID及设备尺寸保持辅助进程原值；不得把压缩图像素直接当设备坐标。

App 的 `executeAuthorized(commandId, runId, source, operation, args, currentSource, isCurrent)` 必须在原宿主 policy/permit 完成后调用，`currentSource` 必须重新读取并核对 package、screen ID、尺寸与旋转；`isCurrent` 检查原任务及取消代次。该 API 是宿主适配层，不作为模型工具直接暴露。辅助进程最多保留4096个已消耗命令ID，容量满后拒绝，不淘汰不确定记录。进程重启后的防重放仍由原 App 持久结果账本保证。任何 `unconfirmed` 必须暂停，不能换无障碍后端重试。

`captureAuthorized(isCurrent, screenshotAllowed)` 只能在原截图隐私、来电、遮挡与伴随窗隐藏屏障内部调用；返回前后都复查。返回 PNG、SHA、capture ID、实际尺寸与单调时间，宿主必须重新绑定自己的 VisualFrame 与观察来源。辅助进程本身没有权限解释用户意图或敏感屏幕，绕开宿主调用该接口不属于支持用法。

## 真正的编辑器动作

可选 `ShellBridgeImeService` 是 Android InputMethodService，需用户在系统设置中启用并选择。它保留真实当前 EditorInfo 和一次输入会话ID，仅当 `imeOptions` 实际宣告 DONE/NEXT、存在当前 InputConnection、不是密码输入且允许 editor action 时发布能力。`performAuthorized(packageName, editorId, action, isCurrent)` 在 IME 主线程再次核对当前会话、包名、动作和任务代次，然后调用 `performEditorAction`。不把普通 Enter 当作完成，不提供 SEND/GO，也不读取或上传输入文本。任务结束可用系统输入法选择器切回原键盘；未选择该输入法时能力不可用。

模型 schema 是否提供 IME 动作由宿主根据当前能力决定，并仍需当前 editable node、焦点和原有敏感政策匹配。`capability.input_available` 与 `available/action` 分别标识文字输入和编辑器完成能力；未宣告完成动作的普通文本字段仍可使用文字输入。`performTextAuthorized(packageName, editorId, text, replace, isCurrent)` 在真实 InputConnection 提交不超过8000字符；replace 先请求系统 Select All，再 commitText，commit 模式只替换当前选择。失败、超时或途中失效均不可换通道重放。输入内容不写协议、日志或能力状态。此组件不会自动启用系统输入法，不会自行操作业务页面。

开发调试接线仍须遵守宿主已有的授权及截图隐私屏障；让 Agent 自行启用辅助权限不是支持流程。

`DoppelAccessibilityService`当前接线：只有明确启用且实际连接UID2000时使用辅助截图、视觉手势和back/home/recents/menu；普通节点继续先走无障碍。SET_TEXT明确返回false且当前非敏感编辑器仍有焦点时，可用真实任务IME替换文本，不能对accepted/unconfirmed重复输入。`ime_action`接受target/editor_id/action，在当前节点与EditorInfo双重检查后完成DONE/NEXT；结果仍进入原DeviceWorker持久账本。helper失联或回执不明不静默换后端。

等待辅助进程结果期间客户端每50毫秒检查原任务取消代次；失效时发送同会话令牌绑定的 cancel 控制，关闭连接。辅助进程在截图/命令子进程运行中检查控制并销毁子进程。已提交给系统的输入不能撤回，因此取消后仍按 unconfirmed 对待，不回退、不重放。该取消机制不声明硬实时延迟。

## 验证状态

以下保留既有验证记录，不代表产品设置页面仍存在。

协议、IME策略和能力绑定独立JUnit覆盖固定命令、拒绝shell文本、整数坐标/截止时间/来源约束、输入键拒绝、单次ID、密码/OTP/未知动作、文本长度、错误包名/焦点/多编辑器和新鲜宿主像素证明；Java helper对Android35 API、全部ShellBridge及集成服务Kotlin有独立编译检查。两份激活脚本PowerShell AST检查无错误。最初 `-ProbeOnly` 只读探测证实 emulator-5554 是Android9/API28、UID2000、开发App UID10056；该探测本身未启动辅助进程。主集成启动辅助进程后，独立LocalSocket连接已取得真实peer UID2000，并用于验证上述初始化顺序修复。开发APK另有opt-in `ShellBridgeIntegrationDeviceTest`（`shell_bridge_live=true` 且 `emulatorOnly=true`），只读检查App实际连接/截图来源与错误来源动作拒绝；测试只读取客户端状态，不自动调用 `activate()`，须先按上述开发调试流程完成启用，并保持模拟器无未完成任务及运行中的 worker。其设备结果由主集成验收记录补充。真实App手势/中文输入/业务结果仍独立验收，不能把组件单测当作业务通过。原始证据留在 `.artifacts/perception-execution/20260909/`，不进入公开源码。
