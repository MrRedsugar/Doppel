# 本机视觉动作段

> 历史设计范围：本文记录旧引擎的本机动作段协议与 2026-09-09 测量，不是当前 App 的接入指南。当前入口为 `DirectRuntime → SplitTaskEngine`；旧引擎的 15 个实现文件（共 17 个顶层类，包括 `LocalVisualMotor`）已迁至 `android/sdk/src/test/java/dev/doppel/sdk/legacy`，包名仍是 `dev.doppel.sdk`，仅用于 JVM 测试，不随生产 APK / AAR 提供。`execute_visual_plan` 不能作为现行运行时工具使用。共享截图、观察与执行组件仍留在生产，不代表本文的模板控制循环仍在运行。历史证据不代表当前版本，具体结果见对应版本验证记录。

当前接入见 [手机直连说明](phone-direct.md)。下文“宿主集成”、工具示例及像素检查要求均指历史实现；旧 `LearningLiveTest` / `PerceptionLiveTest` 位于 `labs/legacy-direct-engine/androidTest`，不再默认编译。

`VisualAnchorTracker`、`VisualAnchorPlan` 和 `LocalVisualMotor` 把短程连续控制与大模型规划分开。规划器先观察当前界面、确定目标及有条件的动作段；本机控制器逐步从新帧重新定位、返回动作候选。遇到来源变化、遮挡、歧义、超时或未知动作结果，结束这一段并把原因交回规划层。组件不会调用模型，也不会注入手势或颁发权限。

该能力适用于同一画面内有辨识度的稳定图标、文字或卡片。它不是目标检测模型、OCR、游戏世界模型或路径规划器；不能知道按钮的语义，也不能凭一张图推导下一页未知按钮。语义节点可靠时应直接使用新观察中的节点选择器；GUI模型负责第一次定位，本机模板负责后续短程核验。

## 历史宿主集成

`LocalVisualMotor.tool(frame)` 返回名为 `execute_visual_plan` 的函数工具。`prepare(proposal, capture, nowMs)` 返回 `Prepared(motor)` 或 `Rejected(reason)`，其中 `capture` 使用既有截图数据格式：

```json
{
  "image_base64": "当前PNG的Base64",
  "mime_type": "image/png",
  "visual_frame": { "...": "现有VisualFrame.json()的完整字段" }
}
```

图像摘要是 **PNG字节的SHA256**。适配器会实际校验摘要、PNG类型、解码宽高、图像大小和显示尺寸；BitmapFactory只在Android图像适配层使用。底层跟踪和计划逻辑使用 `VisualAnchorImage(width, height, argb: IntArray)`，不依赖Android。调用方必须保证传入像素数组在调用期间不变；模板保存自己的局部副本，原图不会作为模板缓存长期保留。

提案示例中的坐标均为 `visual_frame.image_width / image_height` 的**整数图像像素**，矩形右、下边界不包含在内，不是显示器窗口坐标或归一化坐标：

```json
{
  "capture_id": "当前可信来源截图ID",
  "coordinate_space": "image_pixels",
  "anchors": [
    { "id": "first", "bounds": [44, 40, 76, 72], "x": 60, "y": 56 },
    { "id": "second", "bounds": [144, 60, 176, 92], "x": 160, "y": 76 }
  ],
  "steps": [
    { "kind": "tap", "start_anchor_id": "first", "duration_ms": 80,
      "required_anchor_ids": ["second"], "label": "打开目标", "screen_context": "当前应用页面", "safety": "safe" },
    { "kind": "long_press", "start_anchor_id": "second", "duration_ms": 600,
      "label": "打开目标菜单", "screen_context": "当前应用页面", "safety": "safe" }
  ],
  "ttl_ms": 15000,
  "max_frame_age_ms": 1500
}
```

此示例只是协议说明，不表示这些坐标对应任何设备的真实操作。`safety` 是模型输入，仍须进入原宿主策略判断；它不表示批准。适配器输出不含 `visual_permit` 或 `approved`。不得绕过 `dispatchVisual`、实时敏感标签检查、授权流程、`VisualPixels` 动作前校验及取消机制。

宿主循环：

1. 规划后调用 `prepare`，再取得新截图；首次规划耗时不能靠重复使用旧截图绕过新帧检查。
2. `motor.evaluate(capture, elapsedRealtime)` 返回 `Ready(token, stepIndex, action)`、`Waiting(reason)`、`Stopped(reason)` 或 `Complete`。`action` 可交给现有 `dispatchVisual`；归一化坐标由适配器根据当前图像尺寸计算。
3. 宿主真正排队后用 `bindCommand(token, commandId)` 绑定实际命令。等待用户批准期间不得自行提交下一动作。
4. 仅在宿主得到**确认手势完成**的终态回执后调用 `acknowledge(commandId, true, completedAtMs)`。仅HTTP成功、排队接受或动作结果未知均不满足条件。明确失败/未知使用 `false`，然后重新规划。
5. 下一步要求截图捕获时刻严格晚于上一手势完成时刻、且capture ID不同。调用取消时同步调用 `motor.cancel()`；旧token不能重放。`Complete`只说明这一段完成，不是最终业务成功。

所有时刻使用Android单调时钟。来源证据最多保留45秒；默认执行TTL从 `prepare` 起算15秒，实际截止时间取执行TTL与原 `VisualFrame.expiresAt` 的较早者。因此规划耗时20秒后仍可以有15秒执行预算，同时不能把原截图无限续期。用于提出动作的新截图默认最多1500毫秒，允许配置上限5000毫秒。图像尺寸、显示尺寸、package或rotation变化立即停止。

## 跟踪、条件和动作边界

- 最多16个模板、8步动作。模板宽高12—128像素；目标点可在模板内，也可使用规划器明确给出的附近固定偏移（各轴距离最近边界不超过128像素）。模板与目标均必须位于图内，重定位后的目标越界立即拒绝。默认搜索半径24像素，上限96；只允许平移，不做尺度、旋转或透视补偿。
- 建立模板时检测纹理与同范围歧义。每个新帧进行局部RGB搜索、候选全像素核验、4×4分区遮挡检查和周围环带检查；最多32个候选，不选择“离旧坐标最近”的重复图标。
- 远处背景动画不影响局部模板。目标变色、消失、遮挡、邻域被替换、重复匹配或超出搜索范围均拒绝。诊断记录不包含模板原始像素。
- `required_anchor_ids` 表示这一动作必须同时可见的局部条件。`wait_visible` 使用该列表、不注入动作，时长为0；`wait_timeout_ms` 可在目标暂时缺失时有界等待。歧义、邻域替换等不是加载状态，直接重新规划。
- tap 40—200ms；long_press 500—2000ms；swipe 150—2000ms，并保留既有最小归一化滑动距离。摆放与朝向可表达为两步swipe，但两步之间仍须新截图、重新定位和独立授权。

空白格子和缺少纹理的朝向终点可以用附近独特、未被角色遮挡的稳定地标加固定偏移表达；该偏移由规划器根据源图给出，跟踪器不会猜空地或棋盘语义。重复棋盘、改变外观的角色、无稳定邻近地标的区域仍需语义定位或专用GUI/场景模型。模板成功也不说明格子仍为空、费用足够、技能搭配或战斗策略正确，这些条件须由规划器及可见守卫明确处理。局部条件不能覆盖整个屏幕所有意外弹窗，因此宿主的前台包、权限与动作前 `VisualPixels` 目标/路径检查不可删除。

## 2026-09-09离线测量

当时使用三个历史真实第三方App截图，在本机JVM 21.0.10运行当时的跟踪组件。图像最长边1440；每项预热15次，计时100次；只计模板定位，**不包含PNG解码、截图、ADB、手势注入、模型或业务验收**。

| 历史画面与目标 | 稳定帧中位数 | P95 | 受控平移7/-5像素 | 目标中心遮挡 |
|---|---:|---:|---|---|
| OpenCalc 数字1 | 0.995ms | 1.951ms | 定位到新坐标 | 拒绝 |
| WPS 关闭图标 | 0.660ms | 1.248ms | 定位到新坐标 | 拒绝 |
| 明日方舟编队页“开始行动”文字 | 0.771ms | 1.236ms | 定位到新坐标 | 拒绝 |

平移和遮挡由保存截图做受控像素变换，因此不是连续实录、Android设备性能或战斗通过证据。该实验没有设备操作、付费API调用或GPU推理。原始PNG摘要、模板框、Java ImageIO解码/基准脚本、输出JSON和标注图保存在私有 `.artifacts/perception-execution/20260909/visual-anchor/`，不复制到公开测试资源。真实游戏帧这里特指已有编队页，不是新增战斗测试。

单元测试覆盖定位移动、不同位置动画、低纹理、局部多匹配、换色、遮挡、邻域替换、尺寸变化，以及计划的来源/有效期、取消、结果绑定、新帧顺序、等待超时、两段手势约束与JSON/PNG防篡改。Android实际BitmapFactory和宿主接线仍以集成测试及真实App执行记录为准，独立JVM测试不替代APK构建。
