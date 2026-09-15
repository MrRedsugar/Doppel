# ADB 辅助后端依赖与许可

本轮 ShellBridge 协议、LocalSocket 客户端、app_process helper、输入法与脚本为项目自行实现，没有新增第三方二进制依赖或复制上游实现。系统 `app_process`、`input`、`screencap` 和官方桌面 ADB 在用户设备/工具环境中执行，不作为本 SDK 内嵌副本分发。

核验过的内置无线候选：MuntashirAkon/libadb-android 3.1.1，Git tag SHA `c849886ebc6d48e7b46d967e78a6bb65c90c3b74`。主库声明 `GPL-3.0-or-later OR Apache-2.0`；该版 build.gradle 依赖 BouncyCastle 1.81、AndroidX annotation 1.9.1、spake2-android 2.2.1。项目 README 明确提示存在 LGPL 依赖，不能只按主库 Apache 宣称完整依赖均为宽松许可。本轮未选择或分发该库；App 内 TLS 配对仍需完整传递依赖/源代码与分发义务审查及 Android11+设备验证。

- https://github.com/MuntashirAkon/libadb-android/tree/3.1.1
- https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/build.gradle
- https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md

无线脚本调用用户已安装的官方 `adb pair` / `adb connect`，未重新实现 TLS/SPAKE2 或绕过系统配对界面。本机 API28 探测不能证明 TLS 配对已运行。
