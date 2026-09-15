# 独立设备实验 CLI

`scripts/device-lab.py` 用于操作员准备、观察和收尾。它与 Doppel App 的模型执行链分开，不会提交任务，也不能作为业务完成证据。用户授权范围和 worklog123 仍适用：只操作根任务选择的模拟器、真实第三方 App 的新测试内容；不支付、不提交考勤、不发送社交消息、不操作真实账户。

只依赖 Python 标准库和本机已有 ADB。必须显式提供 `--adb`、`--serial`、`--output`，没有默认设备。新目录首次使用，后续同设备通过 `--append` 追加；不覆盖截图、状态或录像。首次创建记录 `.device-lab.json`，每次 CLI 用 `.operator.lock` 防止同一证据目录并发操作。若进程被强杀，先核对锁中的 PID 确实已退出，再人工清理该锁；工具不会窃取旧锁。

## 截图及坐标输入

PowerShell 示例中的 ADB 路径和序列号须替换为当前明确选择的设备。下方输入示例是用法说明，实际验证范围见文末；本轮未操作物理手机。

```powershell
$labArgs = @('--adb', 'C:\Program Files\platform-tools\adb.exe', '--serial', 'emulator-5554', '--output', '.artifacts\adaptive-control\20260909\operator-demo')
$capture = python -X utf8 scripts/device-lab.py @labArgs screenshot | ConvertFrom-Json
$framePath = $capture.result
```

`result` 是新的 `.frame.json` 路径，同目录 PNG 由其中的 `png` 字段指向。帧记录 PNG 字节 SHA256、原始宽高、设备序列号、截图请求 UTC 时间/Unix 时间和接收时间。不会改变设备分辨率或密度。示例下一个输入应在截图后 30 秒以内，且先在 PNG 中确认对应位置：

```powershell
python -X utf8 scripts/device-lab.py @labArgs --append tap --frame $framePath --operator --x 0.25 --y 0.5
```

坐标必须为 `[0, 1)` 内有限归一化值，按 `floor(x × 原图宽)`、`floor(y × 原图高)` 转到该帧的原始像素，拒绝 1、负数、NaN 和无穷大。截图被 UI 缩放显示时应根据原 PNG 的比例计算，不把查看器窗口像素当设备像素。

每次 tap/swipe/key 均校验：显式 `--operator`，帧来自相同序列号，保存 PNG 的 SHA/尺寸一致，时间不在未来且 age ≤ 30 秒，操作前重新截图的 SHA/尺寸与保存帧完全一致；慢截图之后再检查旧帧年龄。变化/过期就停止，并保留本次预检截图，重新观察后再决定操作。严格字节匹配可能因动画、时钟或不同 PNG 编码而保守拒绝，不能关闭此检查来重复点击。检查到输入仍存在很短的设备状态变化窗口，本工具不声称提供原子 UI 事务。

滑动两个端点同样基于已保存帧，时长 50–2000ms：

```powershell
python -X utf8 scripts/device-lab.py @labArgs --append swipe --frame $framePath --operator --x1 0.5 --y1 0.8 --x2 0.5 --y2 0.2 --duration 400
```

按键只有 `back`、`home`、`enter`，仍需保存帧和 `--operator`：

```powershell
python -X utf8 scripts/device-lab.py @labArgs --append key --frame $framePath --operator --key back
```

`enter` 只用于操作员明确核对过的本地编辑场景。它可能触发表单提交，不用作未知焦点、消息发送、支付或认证的通用完成键。本 CLI 不提供文本输入，避免把密码/验证码写进 argv 或证据日志，也没有任意 ADB shell 子命令。

## 状态、录像与预览

```powershell
python -X utf8 scripts/device-lab.py @labArgs --append status
python -X utf8 scripts/device-lab.py @labArgs --append record --seconds 10
python -X utf8 scripts/device-lab.py @labArgs --append preview --scrcpy 'D:\Tools\scrcpy\scrcpy.exe'
```

`status` 只读取 `dev.doppel.developer` 的 `no_backup/direct-runs-v1.json`（对应 `DirectRuntime` 的 `context.noBackupFilesDir`），校验 JSON 后保存原始私有快照和 SHA256；不读取偏好设置、连接凭据或其他 package。status 文件和截图仍可能含用户任务内容，按项目私有证据目录保留，不上传公开仓库。

`record` 使用 Android 自带 `screenrecord`，时长整数 1–60 秒。远端使用随机 `/sdcard/doppel-lab-<uuid>.mp4`，拉取本机新文件后只清理该次生成的远端临时文件，记录大小和 SHA。若录屏/拉取中断，临时文件可能留在设备；不做通配清理。录像帧率不等于模型决策频率。

`preview` 只启动已指定的本机 scrcpy，固定 `--serial`、`--no-control`，通过进程环境绑定所选 ADB；只读预览，不允许在窗口里绕过帧检查输入，不自动下载。可从 [scrcpy 官方 Releases](https://github.com/Genymobile/scrcpy/releases)自行准备并核验发行包；CLI 只检查所给路径存在，不声称验证其发布者。关闭 scrcpy 窗口即可结束预览。预览只记录启动 PID，不把成功启动进程当作画面已显示。

所有 ADB 操作用 argv 列表和 `shell=False`。日志不保存 stdout/stderr；失败只记录退出码/错误类别。`events.jsonl` 保存命令、截图、状态与录像证据；`operator-actions.jsonl` 单独记录人工输入及来源帧，固定 `source=operator`、`agent_success=false`。被 ADB 接受也不等于语义命中、业务完成或 Agent 成功。工具不自动重复失败动作，不提供 HTTP 控制端点，不自动启动/安装第三方 App。

## 本地契约测试

```powershell
python -X utf8 -m unittest discover -s tests -p test_device_lab.py
python -X utf8 scripts/device-lab.py --help
```

测试使用有效合成 PNG 和 fake subprocess，覆盖原图坐标映射、时效双重检查、换设备/旋转/变图/篡改帧拒绝、越界/非有限值、按键允许列表、显式追加与锁、状态固定路径、限时录屏及 scrcpy 无控制参数。测试不运行 ADB、真实截图、输入或录像；不能替代根任务的真实第三方 App 验收。

## 已验证范围

2026-09-09已在根任务明确选择的雷电实例 `emulator-5554` 完成一次截图smoke。`screencap -p` 返回0，取得1440×3200 PNG，并保存帧元数据及 `events.jsonl`；本机重新计算的PNG SHA256与帧记录一致。私有证据位于 `.artifacts/adaptive-control/20260909/lab-smoke/`，其中 `frame-7b384b5a13ce469487486135592c67a6.frame.json` 关联原PNG。

这次smoke验证截图、设备来源和证据落盘；没有验证CLI的真实点击、滑动、按键、录像或scrcpy画面，也不证明Doppel完成了第三方App任务。它与上面的fake subprocess契约测试、Doppel自身模型执行链的真实App验收分别记录。
