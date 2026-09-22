# Skills 功能退役

2026-09-21 起，产品不再提供 Skills 导入、目录、读取、生成及自动注入。任务复盘和纠错使用聊天与可编辑的长期记忆；网页搜索、网页读取和文档工具继续提供。

Android 任务协议已移除 `load_skill`、`read_skill_resource`。Python 网关不再注册 `/skills` 路由及 `list_skills`、`read_skill`、`read_skill_resource` 模型工具；Artemis 适配器也不再提供 Skills 工具。旧工具调用按未注册工具处理，不提供兼容执行或自动恢复。

升级不会删除用户已保存的 Skills、导入文件或旧学习数据目录，也不会把这些资料自动迁移或注入长期记忆。历史任务和历史文档中的 Skills 记录只说明当时的版本行为。
