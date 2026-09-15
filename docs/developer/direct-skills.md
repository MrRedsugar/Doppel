# 手机直连的按需 Skills

当前直连任务使用 `DirectRuntime → SplitTaskEngine`，通过 `DirectSkills` 读取内置资料和用户导入的普通包。App 已移除应用学习入口，不会自动积累或读取 `learned-skills-v1` / `manual-skills-v1`；旧文件与 SDK 兼容访问边界见 [应用学习：当前状态与 SDK 兼容](app-learning.md)。

开发应用在直连模式下已有本机 Skills 目录、Markdown/ZIP 导入、查看资料和删除入口。打开「扩展」会默认显示 Skills 标签；MCP 服务标签继续使用 Python 网关，并明确提示直连尚不支持，不会把桌面程序误报为手机可运行工具。

通过 Gradle 工程依赖 SDK 会自动解析运行库；直接接入独立 AAR 时，需要声明 `org.yaml:snakeyaml:2.4`，以及联网研究的 `com.squareup.okhttp3:okhttp:4.12.0`、`org.jsoup:jsoup:1.18.3` 等依赖。完整宿主配置见 [Android SDK](android-public.md#build-and-connect)，AAR 不内嵌这些传递依赖。

`DirectSkills` 是知识存储，不能执行脚本或授予权限。当前运行时的 `list_skills`、`load_skill`、`read_skill_resource` 工具允许模型按任务需要检索目录并分段读取正文或资源；`SplitTaskEngine` 的目录和参考回调连接 `DirectSkills.list` / `relevant`。宿主可用 `relevant` 按当前应用和文本相关性选择小份参考；自动选入仅是 `untrusted_knowledge`，不是 verified use，也不应将整份目录/攻略放进系统提示词。

## API 与返回形状

```kotlin
val skills = DirectSkills(context)
val catalogue = skills.list()
val page = skills.list(query = "表格", offset = 0, limit = 20)
val knowledge = skills.read("arknights")
val revision = knowledge.optString("revision")
val source = skills.resource("arknights", "references/sources.md", revision)
val next = skills.read("arknights", revision, offset = 5000, maxChars = 5000)
val references = skills.relevant(goal, currentPackage, currentScreenDescription)
// User-selected data only; requires current direct mode and accepted notices.
val imported = skills.importPackage(bytes, "markdown") // or "zip"
skills.delete(imported.getString("name"))
```

清单形状：

```json
{"items":[{"name":"arknights","description":"简短使用场景","source":"bundled","revision":"sha256-hex","included_source_version":"2026.09.08.4","platforms":["android"],"dependencies":{"tools":["inspect_screen"],"runtimes":[]},"runtime_status":"unverified","trusted":false}],"errors":[]}
```

列表不含正文或资源内容。普通目录最多 50 项（包括内置），不合并旧学习或示教资料；名称最多 50 字符、描述最多 200 字符。无参 `list()` 返回已启用的完整目录；`enabledState()` / `setEnabled()` 管理独立于包内容的启用开关。`list(query, offset, limit)` 检索名称、说明、别名、包名，返回 `total/offset/limit/next_offset/truncated`。空 query 可逐页遍历；offset 从 0 开始，limit 为 1–50，query 最多 1000 字符，末页 `next_offset:null`。检索先作用于全目录再分页，超过首 20 项仍可找到。

正文返回 `found:true`、`instructions`、`instruction_chars`、可读 `resources` 相对路径；资源返回 `found:true`、`content`、`name/source/path/revision`。二者均为 `trusted:false`，带 `offset/total_chars/next_offset/truncated`。offset/maxChars 为追加参数，旧调用保持首段读取。

正文和每次资源最多 12000 字符，maxChars 允许 1–12000；offset 非负，超出末尾返回空文本及末尾 offset。字符偏移使用 Kotlin UTF-16 索引。还有后文时 `truncated:true`，用同一 revision 和 next_offset 继续读取；不能声称已经阅读其余内容。缺少、格式损坏、危险路径返回 `found:false` 与稳定 `error`；非法范围返回 `invalid_skill_range`。传入旧 revision 返回 `skill_changed_refresh_catalogue`，应重新列目录后选择，不能把失败当加载成功。

SDK、对应模型工具及 Gateway GET 正文/资源请求都支持 revision 匹配检查。Gateway 范围参数使用 `offset/max_chars`，目录使用 `query/offset/limit`；重复参数和非整数范围拒绝解析。示例：`GET /skills/arknights?revision=SHA&offset=5000&max_chars=5000`。

Android Gateway 在直连模式委托 `DirectSkills.request(method,path)`：

| 路径 | 行为 |
| --- | --- |
| `GET /skills` | 完整清单与格式错误项；带 query/offset/limit 时返回检索分页 |
| `GET /skills/{name}` | 按需正文，支持 revision/offset/max_chars |
| `GET /skills/{name}/resources?path=references%2Fsources.md` | UTF-8 资料，支持 revision/offset/max_chars |
| `DELETE /skills/{name}` | 只删除本机导入项，返回 `{"ok":true}` |

内置资料不可删除或覆盖。模型没有导入/删除管理工具；用户通过文件选择器明确选择 `content://` URI，`SkillImportClient` 在本机校验导入，无网关上传。网关模式继续使用原来的 HTTP Skills 管理接口和账户隔离，不自动同步手机导入内容。

`learned-` 与 `manual-` 是已移除应用学习路径的名称前缀，正文和资源读取不会回退到旧库，删除也不会改动旧文件，而是返回 `application_learning_removed`。停用项的读取先返回 `skill_disabled`。兼容存储接口仍可显式检查和导出历史条目，但这些条目不会因此进入当前运行时；旧导出包保留上述前缀时也不能直接作为可读的普通 Skill 使用。

## 包格式与边界

每个 Skill 包有带 YAML frontmatter 的 `SKILL.md`，必填 `name` 和 `description`。支持常用 YAML 多行字符串和列表，使用 SnakeYAML 2.4 `SafeConstructor`；拒绝对象标签、YAML 别名引用、重复键、超过 32 层的结构。`platforms`、可选 `packages`、可选 `aliases/app_aliases` 是字符串数组，`dependencies` 只接受 `tools` 与 `runtimes` 数组，最多 32 项、每项最多 256 字符。packages 仅接受明确 Android 包名，不支持通配符；省略或空列表表示通用资料。aliases 是主题检索词，例如“表格/保存”，不会用于跨应用身份匹配；app_aliases 是包作者声明的应用正式名称/别名（每项去除首尾空白后至少 2 字符），例如“明日方舟/WPS”。两者和 YAML 引用语法不同，也不能授予权限。可选字符串 `version` 作为 `included_source_version` 展示。

支持单独 SKILL.md，或根目录/单层包目录内含 SKILL.md 的 ZIP。每个文本文件最多 64 KiB；压缩文件和解压总量各最多 2 MiB；最多 128 个 ZIP 条目/100 个文件/8 层路径。拒绝绝对路径、`..`、反斜杠、Windows 保留名、重复/大小写冲突路径、加密 ZIP 和链接类型。通过全部校验后以单次目录重命名发布，不覆盖同名资料。

数据保存在应用私有 no-backup `direct-skills-v1`，只读内置资料来自 SDK `assets/skills`。路径逐级检查包含关系和文件系统链接。目录由宿主管理，这不是针对同 UID 或 root 恶意进程的操作系统沙箱。

包里的 scripts 文件不会执行；资源读取仅允许 Markdown、txt、JSON、YAML、CSV，并拒绝 scripts 目录。资源、frontmatter 和第三方文本始终 `trusted:false`；宿主继续独立验证设备动作、可用运行环境与外部工具权限。`runtime_status:unverified` 不等于依赖已安装。

`revision` 是规范排序后的包路径、长度与全部文件字节计算的 SHA-256，修改资料会改变它。不会把本机路径、密钥或运行时授权写入清单。通过目录级校验后，单个包的正文解析或资源读取失败会进入清单的 errors，其他可用包仍可列出。目录结构非法、目录项为链接或普通文件、名称冲突或总量超限会使整个清单请求失败，不能按“单项损坏已隔离”处理。

## 自动相关参考与内置资料

`relevant(goal, packageName, screen)` 先限制 Android 平台，然后区分任务应用与前台应用：当 goal 明确包含有 packages 的普通资料的 app_aliases 或完整 skill name，候选可跨前台包成为 `task_app`。只在 goal 中做身份匹配，屏幕文字和主题 aliases 不构成跨应用身份；英文身份名称检查标识符边界，避免把 `WPS` 匹配进 `swpshelper`。旧资料没有 app_aliases 时仍可通过完整 name 匹配。资料只表示目标应用的知识适用范围，不代表应用已安装、当前页面属于该应用或已获准启动。

单个明确任务应用优先返回该应用与必要的 generic；多个明确任务应用按首次提及顺序最多选择两份，去除重复包与重叠别名命中，两个目标占满时不再加入 generic。最多尝试四个明确目标候选。不明确应用的普通目标仍按当前包和文本相关性选 current_app，再选 generic；当前/通用范围各最多读取两份候选。无匹配不会硬塞应用资料。这条选择链只读取当前目录，不会从历史学习库补充资料。

选取保留入口范围说明，并从正文/最多四个 references 的首 12000 字符中按相关性提取有位置的短段；references 按当前目标和入口中对它的说明排序。这是有界词项检索，不是全文语义索引；未检索到的长段仍可由显式工具分页读取。返回最多两项，instructions 总计最多 6500 字符，包含 name/revision/source/packages/app_aliases、`reference_kind:untrusted_knowledge`、`scope:task_app|current_app|generic`、`excerpts:[{path,offset,chars}]` 与 `coverage:selected_excerpts_only`。task_app 额外有 `match:{source:goal,field:app_aliases|name,alias,offset}`；current_app 的 match.source 为 foreground_package，generic 为 goal_and_screen_terms。`foreground_matches` 表示包是否匹配当前前台；`grants_execution_authority:false` 明确资料匹配不授予动作权限。

读取中版本失配丢弃候选，不能混合新旧版本。app_aliases 仍是不可信的包声明，可能含歧义；否定句中提到应用也可能选中它的知识，但不会据此授权打开。该 SDK 方法只解释调用方传入的 goal，调用方负责提供当前任务描述；方法没有设备已安装应用清单，不能以匹配结果证明安装状态。自动参考选择不构成事实核验、模型实际采用或业务成功，使用证据由运行时另外记录。

当前内置包为 `mobile-ui`、`wps`、`arknights` 和 `arknights-deployment`，分别提供通用手机操作、表格操作、游戏导航及部署参考。具体版本、适用包名、操作说明与来源以各包 `SKILL.md` 和 references 为准。内置知识、成功读取或模型引用都不等于已通过相应应用的真实任务验收；历史场景结果应查对应验证报告。

开发测试为 `DirectSkillsTest` 和 `SkillKnowledgeResolverTest`，覆盖目录摘要、超过 20 项检索分页、跨 5000 字符续读、版本变化、应用/平台过滤、摘录预算、YAML 拒绝、越界/重复/超量 ZIP、资源只读、内置保护、UTF-8 与截断。Android 运行与模型选用由独立 instrumentation/端到端验证负责，不能以导入成功代替游戏任务成功。

`KnowledgeLiveTest` 是显式 `-e knowledge_live true` 才运行的 Android 公开网络验收：复用当前已同意条款的直连设置，经 Gateway 验证内置知识版本/来源，并请求一次固定公开搜索词及公开文章。它不调用模型、不修改设置或控制屏幕；原有任务须已暂停或结束，并验证任务记录字节保持不变。结果写入目标应用 `files/knowledge-live.json`，仅包含公开来源、有限摘录、SHA-256 与校验状态。网页随时间变动，网络或解析失败必须单独报告；默认测试集合会跳过此项。
