# Android DSH 引擎升级输入

本文记录此前的引擎升级与回归。当前维护步骤见 [JS 引擎构建](../js/README.md#build)。

固定 DSH `0.2.2-rc.5`，对应 2026-11-22 查询 npm 官方 registry 时的 `latest`；`alpha` 标签当时为 `0.2.3-alpha.2`，本实验不使用该版本。完整依赖和 tarball integrity 固定在 `package-lock.json`。

`python -B native-probe/prepare-engine.py` 从固定的 jackwener 子模块准备构建副本，更新 DSH 及 Android 插件的版本约束，应用其 Android 补丁与本目录的替代补丁，生成 `native-probe/build/engine-latest/engine.zip`、哈希和版本标记。Windows 构建使用本机 WSL Ubuntu-26.04 的 Node/npm、patch 和 Python；这是构建工具，不打进 APK，也不恢复手机上的 Ubuntu 容器。

替代补丁的原因：

- `06`：新版将 ripgrep 的可执行文件解析移动到 `dsh-util-ripgrep`，Android 显式使用 APK 的 rg 路径。
- `07`：新版将内置 profile 模板移动到 `dsh-official-bundles`，在该处注册 android profile。
- `08`：新版已实现 SDK 审批与提问，不再重复移植旧版实现；仅补充手机审批需要的根会话 ID。
- `19`：新版工具注册新增 `options.when`，保留该语义并适配 Android 的插件归属追踪。
- `23`：使用新版 DeepSeek 账号服务的方法与 Agent.model 的持久模型选择接口，并显式禁用不使用的插件管理工具行。
- `24`：手机使用 SDK JSON-RPC，禁用 Web 网关服务，避免其 WebSocket 注册侵入仅用于登录的回调服务器。
- `25`：解包新版账号状态流的 `{type,state}` 更新，保留手机端登录进度通知，并为新的模型选择 API 安装模型切换提示。

中转适配器仍使用上游内置的 `@snnh/dsh-llm-service-lite` 及其兼容补丁；其 cosmokit 约束改为新版引擎使用的 `1.9.0`，通过提供方回归检查验证，未加入旧版依赖回退。

之后运行 `python -B native-probe/prepare-upstream.py --build` 打包 APK。打包脚本要求已构建的引擎版本匹配固定版本且哈希正确，不会在缺少新版引擎时静默装回旧引擎。

APK 交付构建先 clean 再 assembleDebug，避免旧的增量 DEX 片段残留到交付包中。

原始源码、补丁及第三方许可保留在 `native-probe/upstream`；本目录只保存升级的锁文件、替代补丁和说明。
