# TODO

## 已完成

- [x] **堵住 `phone_sh` 命令拦截绕过并修正 Root 提示词与规则校验**
  - 将 `phone_sh` (`cmd`) 纳入 `js/packages/plugin-guard/index.js` 的 `DEVICE_SHELL_TOOLS`，受内置 hardline 与自定义命令规则约束；同步补充 `js/test/guard-ask.test.mjs` 测试。
  - 根据 `DSH_ENGINE_IDENTITY === 'root'` 动态调整 `systemPromptSection` 的 Root 描述，去掉自相矛盾的 `"there is no root"` 与 `RootTools.kt` 的 `"No app allowlist."` 描述；同步补充 `js/test/prompt.test.mjs` 测试。
  - 将 `phone_sh` 加入 `RuleValidation.kt` 的 `ENGINE_ONLY_STEP_TOOLS`，禁止其作为固定步骤直接写入自动化规则；同步更新 `RuleValidationTest.kt`。
  - 在 WSL 中重编 `engine.zip` 并更新 `app/src/main/assets/{engine.zip,engine.sha256,engine-version.txt}`（SHA-256 与构建阶段测试均已验证）。

- [x] **修复 Root 与引擎服务生命周期缺陷**
  - `RootGrant.check()`：在未写入 `disabled_by_user` 时按 `enabled` 默认关闭处理，避免未启用 Root 的用户每次切回前台都静默触发 `su` 探测。
  - `EngineStateOwnership`：增加 `DELETE_SELF | MOVE_SELF` 监听并在目录删除/移动时注销释放 `FileObserver`。
  - `EngineService.start()`：以 `AtomicBoolean.compareAndSet` 替代包住整段 `runBlocking` 启动流程的 `synchronized(this)`，避免堵死 Binder 线程与同锁调用。
  - `NativeRoot.kt`：删除已弃用且无调用方的 FUSE 挂载死代码（`target`、`helper`、`mountedSource`、`restore`、`disconnect`）。

- [x] **加固配置与规则持久化容错**
  - `CommandPolicyRepository`：`DataStoreFactory.create` 增加 `ReplaceFileCorruptionHandler { CommandPolicy() }`，序列化器开启 `ignoreUnknownKeys = true`，并在 `CommandPolicyRepositoryTest` 新增损坏恢复与未知字段兼容用例。
  - `SessionStore`：对 `rules.get(ruleId)` 返回的 `specJson` 解码补齐 `runCatching { ... }.getOrNull()` 防护。

- [x] **补齐 CI、归拢依赖与清理告警/跨平台测试缺陷**
  - `.github/workflows/regression.yml`：启用 Node 24 并运行 10 套独立 JS 引擎单测；将 `:app:testDebugUnitTest` 从 7 个过滤项扩展为全量单测。
  - `gradle/libs.versions.toml` & `app/build.gradle.kts`：将 `libsu-service` 与 `okhttp` 纳入版本目录管理。
  - 移除 `AndroidManifest.xml` 中冗余的 `android:extractNativeLibs="true"`（已确认 Merged Manifest 仍由 `useLegacyPackaging = true` 正确注入）。
  - 清零 `:app:compileDebugKotlin` 与 `:app:processDebugMainManifest` 的全部编译/Lint 告警（含 Kotlin 2.5 `KTLC-288`、`rememberTransformableState` 4 参新重载、`isChecked` API 36 兼容及冗余空安全操作符）。
  - 修复 `ShellFiles.kt` 与 `ShellFilesTest.kt` 在 Windows 下的路径分隔符硬编码问题（防止 `move` 校验失效触发无限递归 `copyRecursively`），以及 `ExtensionsStoreTest.kt` 中 `SharedFlow` 订阅竞态死锁。
  - 清理本地残留的 `native-probe/` 目录与旧版 `jackwener` Hilt 增量构建缓存。

- [x] **完善 Root 按会话工作区与细节打磨**
  - Room 升级至 v6（`SessionEntity.workspace` + `M_5_6` 迁移），冻结 v5 schema 并在 `MigrationTest` 补齐 v5→v6 迁移验证。
  - 新增 `SessionWorkspaces` 及 `SessionWorkspaceTest`，在 `SessionStore`、`RootTools`（`phone_workspace`）与 `HomeScreen` 的 `LocalLinkBase` 全面接入按会话绑定工作目录，并修复取消默认项目后旧会话卡在旧目录的问题。
  - `Composer` 的 `+`/`/` 菜单在开启 Root 时新增「工作项目」切换弹窗（支持应用私有目录与已添加项目即时切换）。
  - `NativeRootActivity` 复用 `NativeRoot.projects`/`saveProjects` 与 `RootConnection` 执行 `canonical()` 避免重复冷起 `su`，修正过时文案并补充 `uid 0` 扩展安全提示。
  - 修复 `PrivilegedScreen` 副屏已销毁时 `getDisplay(display)` 的空指针风险，为 `AppDirectoryPicker` 添加 `LruCache` 应用图标缓存。
- [x] **细化 Root 模式提示词分工并重编引擎**
  - 细化 Root 模式下 `bash` 与 `phone_sh` 的系统提示词分工并重编 `engine.zip`（`0.2.2-rc.5-abc7fb242ea`）。

- [x] **确认精选源与清理废弃 FUSE 挂载产物**
  - 确认 `MarketIndex.CURATED_URL` 与 `js/packages/plugin-extensions/boot.js` 继续复用上游 `jackwener/DSH-Android` 精选插件市场索引。
  - 删除无生产调用方的废弃 FUSE 挂载产物 `app/src/main/jniLibs/arm64-v8a/libdshworkspace.so`、`tools/root-workspace.c`，并清理 `PhoneBackendTest` 中的 `mountOnly` 测试及文档过时引用。

- [x] **打磨设置与 Root 页 UI 顺序**
  - 「手机权限」页（`SettingsScreen.PhoneSection`）：将 `BackgroundScreenCard` 提至 `PhoneAccessCard` 之下，并过滤掉与顶部重复的 `accessibility` 系统权限行。
  - 设置一级菜单（`SettingsIndex`）：将 10 行大卡按账号与模型 / 助手配置 / 权限与安全 / 用量与系统拆分为 4 张逻辑卡片。
  - 「命令规则」页（`CommandRulesSection`）：将命令规则测试框 `RulePreview` 移到 14 条内置规则之前。
  - 「Root 与项目」页（`NativeRootActivity`）：在已开启 Root 授权时将「工作项目」区块提至「Root 操作」状态卡之前。

- [x] **完整版自动化任务升级与缺陷修复**
  - **Bug 修复**：修复 `RuleMonitorService` 无定位权限时不限 SSID Wi-Fi 连接不触发（新增 `wifiConnected` 状态追踪）；支持 `RuleMonitorService` 按当前启用规则所需广播掩码动态注册/注销；新增系统时间/时区变更广播监听（`TIME_SET` / `TIMEZONE_CHANGED`）重排闹钟；单次定时任务（`onceAtEpochMs`）触发或同步发现过期后自动关闭；删除规则时同步清理 `rule_runs` 与防抖状态；`editAllowedTools`/`editSpec` 后及时 `sync()`；Play Services 地理围栏改用顺序挂起避免竞态。
  - **执行与配置能力扩展**：`RuleSpec` 向后兼容新增 `cooldownSeconds`、`notifyOnSuccess`、`modelProvider`/`modelId`/`modelEffort`、`workspace`，`Trigger` 新增 `Trigger.Manual`（手动触发）；`SessionStore` 在自动会话创建、失败、降级、停止、完成时真实回写 `rule_runs.outcome` 并应用规则级模型/工作目录/静默配置；`RuleTools.rule_delete` 补齐 `unattended` 与存在性校验。
  - **UI 与测试补全**：`AutomationScreen` / `RuleDetailScreen` 新增 5 个快捷模板、缺失权限高亮标签与一键跳转、下次预计触发时间、手动一键运行、「立即运行」/「快速编辑」弹窗（改名称/时间/关键词/Prompt/冷却/成功通知/模型/工作项目）/「对话修改」及 5 种运行态映射；新建 `RuleMatcher.kt` 与 `RuleMatcherTest.kt` 并扩充 `RuleValidationTest.kt`。

- [x] **自动化支持多触发器（OR）与扩展状态条件（AND）**
  - `RuleSpec` 新增 `extraTriggers`（最多共 8 个触发器，任一命中即触发，独立管理多定时/多围栏 `triggerIndex` 槽位）；`Trigger.Notification` 新增 `excludeKeyword` 排除词。
  - `Condition` 新增 `wifi`、`screen`（`on`/`off`/`unlocked`/`locked`）、`bluetooth` 持续状态条件，接入 `TriggerHub.collectDeviceState()` 懒采集系统状态。
  - 列表/详情 UI 展示多触发标签、排除词与新条件，并同步更新 `RuleTools.RULE_DOC`、三语资源与单测。

## 待办 / 需确认项

- [ ] **R8 混淆与资源裁剪真机验证**（`app/build.gradle.kts` 当前保持 `isMinifyEnabled = false` / `isShrinkResources = false`；开启前需在真机上完整验证 JNI、Shizuku、RootService 反射与序列化流程）。
