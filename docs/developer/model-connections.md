# 模型连接与视觉增强

本页说明 2026-09-11 新增的 Android 本机模型连接，以及 Python 服务端的模型适配接口。源码、APK、安装和真实运行的验收状态以对应版本验证报告为准。

## Android 设置

从设置中的“模型设置”进入“模型连接”页面。默认预设是阿里云百炼中国内地，默认模型 `qwen3.8-flash`，独立视觉增强 `qwen3.8-max`。从 alpha.28 起，Android 百炼链路的默认和增强请求都关闭思考，均不发送 `reasoning_effort`。用户界面只展示默认模型和可选的“独立视觉增强”。

1. 在“我的平台”填写 API 地址、API Key，必要时填写 JSON 附加请求头。可添加多个平台账户；预设包含百炼中国内地、百炼国际、OpenAI、OpenRouter、硅基流动和 MiMo，也可添加兼容 Chat Completions 的自定义平台。
2. 选择默认模型。可以获取平台的 `/models` 列表并搜索，也可以手工填写模型 ID。列表存在不代表已证明支持图片。
3. 按需开启独立视觉增强，选择同一或另一平台的视觉模型。alpha.29起，关闭后只由默认模型直接操作手机，不发送增强请求。选择默认模型或更换平台会保留开关状态及原增强模型选择；只有显式开关/启用增强才改变执行模式。
4. 分别验证所选模型的视觉能力，再启用本机连接。

默认模型和有效增强模型都需要支持图片。截图和任务内容会发送到对应平台；两种请求都会产生图片 Token 费用，费用由配置的平台账户承担。验证只发送本地随机生成的六色色块 PNG，不读取手机截图或个人图片。只有实际图片答复与像素内容吻合才记为“已验证视觉”；明确表示不支持图片的 400/422 错误记为“不支持图片”。网络、认证、额度、格式或识别错误均为“视觉待验证”，不会被误判为不支持。

平台配置整体以 Android Keystore AES-GCM 加密，保存在应用 `noBackupFilesDir`。API Key 和附加请求头不会出现在模型请求日志、配置列表、导出或系统备份中。修改认证或接口后会失效此前的能力记录；更换域名或端口必须显式重新输入全部认证信息。请求禁止跟随重定向携带密钥。读取配置、测试图片、模型发现不依赖本机直连已经开启，因此首次配置不会形成先启用才能验证的死循环。

旧 `DirectCredentials` MiMo 密钥不会自动迁移到千问。在“导入旧 MiMo 连接”中明确选择导入后，密钥只复制到 `https://api.xiaomimimo.com/v1`，旧凭据仍保留。导入后仍需选择并验证支持图片的模型；历史的 `mimo-v2.5-pro` 名称本身不证明支持视觉。

## Android 调用约定

```kotlin
val payload = JSONObject()
    .put("_doppel_role", "primary") // 或 grounding
    .put("messages", messages)
val result = ModelApi(context).complete(payload) { connection ->
    // 运行器保留连接，取消时 disconnect，并在执行动作前丢弃过期响应。
}
```

`ModelProviders(context).isReady()` 校验当前有效模型均已配置认证并通过图片验证。`ModelApi.complete` 按角色解析平台和模型，返回 OpenAI 兼容 JSON；调用方读取 `choices[0].message.content`。发送前剥离全部 `_doppel_*` 字段并覆盖模型 ID，避免内部角色名或旧模型选择泄漏到平台。

`primary` 默认使用 Flash、思考关闭；启用增强时`grounding`默认使用 Max、思考关闭。关闭增强的任务仅调用primary；底层ModelRouting.select保留grounding到primary的兼容解析，但执行内核不再产生该请求。自定义兼容平台不会被强行加入千问专有参数。百炼参数仅对指定的百炼预设官方域名及 Qwen 模型生效。连接超时 15 秒，默认模型读取超时 90 秒，增强模型 60 秒；不自动重试计费请求。

新 Android 执行内核中，主模型持有任务历史并负责规划、判断和恢复。开启增强时，增强模型每次接收当前截图、动作语义、预期可观察变化，以及可选的页面关系说明，不包含历史、包名、Activity 或截图编号。增强模型返回执行前的页面/意图一致性，矛盾或不确定时不派发动作；这不等于实际执行成功，结果仍由主模型看动作后的截图判断。关闭增强时，主模型直接输出动作所需的0–1000坐标，通过相同执行器检查后操作。主模型的等待、完成、提问、知识读取不会调用增强模型。实际点击、连续手势、取消、坐标映射与回执由手机完成；异常恢复归主模型负责。

## Python 服务端配置

已有网关部署仍以 `xiaomi-mimo` 为默认供应商，避免遗留密钥被静默发送到新平台。千问使用显式 `qwen` 或 `qwen-intl`；两者默认均为 Flash 主模型和 Max 视觉模型。`custom` 需要明确模型名称与 HTTPS 地址，默认关闭独立视觉增强。

```powershell
python -m doppel serve --provider qwen --api-key-file C:/private/qwen-key.txt
```

不同平台的视觉增强需单独认证，不能继承默认平台密钥：

```powershell
python -m doppel serve --provider qwen --api-key-file C:/private/qwen-key.txt --vision-provider custom --vision-model vision-model-id --vision-endpoint https://vision.example.com/v1 --vision-api-key-file C:/private/vision-key.txt
```

关闭增强用 `--no-vision-enhancement`，此时默认模型必须能接受视觉输入。附加请求头保存在宿主私有 JSON 文件中，通过 `--provider-headers-file` 或 `--vision-headers-file` 配置；例如 `{"X-Tenant":"tenant-name"}`。服务端密钥由部署者的私有文件权限保护，Android Keystore 仅适用于 Android 本机凭据。不要将这些文件提交到仓库、打进 APK 或放入公共制品。

产品服务使用以下环境变量；PowerShell `scripts/start-server.ps1` 有同名语义参数：

| 环境变量 | 用途 |
| --- | --- |
| `DOPPEL_PROVIDER` / `DOPPEL_MODEL` | 默认供应商与模型 |
| `DOPPEL_API_KEY_FILE` | 默认供应商的私有密钥文件 |
| `DOPPEL_PROVIDER_ENDPOINT` | 自定义平台基础地址或 Chat Completions 地址 |
| `DOPPEL_PROVIDER_HEADERS_FILE` | 默认平台附加请求头私有 JSON 文件 |
| `DOPPEL_VISION_ENHANCEMENT` | `1` 开启独立增强，`0` 跟随默认模型 |
| `DOPPEL_VISION_PROVIDER` / `DOPPEL_VISION_MODEL` | 独立增强供应商与模型 |
| `DOPPEL_VISION_ENDPOINT` | 独立自定义增强平台地址 |
| `DOPPEL_VISION_API_KEY_FILE` | 独立增强平台密钥文件 |
| `DOPPEL_VISION_HEADERS_FILE` | 独立增强平台附加请求头文件 |

启动脚本未显式传入的认证文件和平台选项会清除继承环境值，防止切换供应商时误用旧认证。预设供应商不能被重写为第三方地址；第三方地址必须选择 `custom`。远程接口需要 HTTPS，回环地址可以使用 HTTP。平台、地址与密钥由可信宿主配置选择，请求正文不能更改目的地。

Python `call_model(..., auxiliary=True)` 使用增强连接并关闭千问思考；普通请求使用主连接并开启 low。服务端不会向辅助视觉请求自动注入历史会话。使用量记录对应实际调用供应商，图片与思考 Token 明细是总量的子集，不会重复加算。

Python 网关仍沿用现有 Harness 和 `describe_screen` 执行接口；本次模型适配不表示其执行器已替换成 Android `SplitTaskEngine`。自定义服务端模型名称也不等同于视觉验证结果，部署者应在启用真实任务前验证图片请求。Android 设置中的随机图片能力记录不跨主机自动复制。

## 问题定位

模型列表为空或 `/models` 不受支持时可手工输入模型 ID。认证错误应检查所选地区、平台 Key、账户权限和请求头；网络失败保持待验证。视觉探测返回不支持时改选视觉模型；识别结果不匹配时保留待验证，检查模型名称或重新执行探测。任务运行期间需先结束任务再修改连接，避免一个任务中途混用不同平台。

百炼返回 HTTP403 且明确代码为 `AllocationQuota.FreeTierOnly` 时，表示免费额度耗尽并限制为仅免费使用，应用显示额度说明；应在对应账户控制台检查余额和“仅免费使用”选项。不能仅因为403就反复更换Key或把模型标为不支持图片。应用不自动充值或切换计费模式。

本机设置相关实现为 `ModelProviders.kt`、`ModelApi.kt`、`ModelTransport.kt` 和 `ModelSettingsActivity.kt`；Python 适配为 `providers.py`、`model_proxy.py`。测试使用合成请求与本地 HTTP fixture，不含用户凭据或真实任务图片。
