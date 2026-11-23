# DSH 安卓原生客户端

本文记录过去的原生版构建与设备验证。当前工程的构建入口见 [仓库 README](../README.md#构建)。

当前交付已转为正式客户端：`DSH 安卓` / `com.dsh.android` / `0.5.5`，统一元数据见 `client.json`。
使用 `python prepare-engine.py`、`python prepare-upstream.py --build` 构建；正式签名配置与依赖见根目录 README。
产物为 `build/client/DSH-Android-0.5.5-arm64.apk`。长按桌面图标的「开始对话」直接进入 QuickActivity。

以下保留实验阶段的架构及设备验证记录，其中旧实验包名和旧产物路径是历史记录。

## 0.5.1：思考中停止与工作目录保持（2026-11-23）

停止不再经由模型 SDK 的 shutdown 等待：新增独立的单向 Binder stop，普通引擎直接退出，Root 宿主转发到真正承载 Node 的 Root daemon，停止创建中的引擎时先取消启动等待。Root daemon 保留宿主 Binder 的强引用，避免运行期间死亡监听丢失。会话立即记录 app/stopped、清除运行和等待状态；停止引发的请求关闭不作为会话错误。

用户保存的项目目录继续持久化。SDK 首次打开和恢复已有会话后、发送下一条消息前，统一应用保存目录；引擎或 App 重启不清空选择。无 Root 时暂停访问原目录、改用 App home，重新授权后回到同一保存目录；不会偷偷改动项目文件。

测试使用本机 HTTP SSE 服务持续发送 reasoning_content，没有调用外部付费模型。正式包身份下实测两轮普通引擎和两轮 Root 引擎停止，分别耗时 63 / 61 / 71 / 67 ms；每次均可重新启动引擎并继续同一会话，未生成 app/error。工作目录回归通过：Root 下保留已选目录，普通身份暂停访问而不清除选择。测试配置和临时会话已恢复、清理。设备日志见本机 build/device-stop-formal-final.log（不提交日志）。

## 正式版交付验证（2026-11-23）

正式元数据统一在 `client.json`。使用原主线的角色图标；静态桌面快捷方式「开始对话」直接打开 QuickActivity，不依赖桌宠是否已经显示。快捷窗口 taskAffinity 使用正式包名，更新检查仓库切换为 mam15mon/dsh-android。

正式 APK 使用独立发布签名，密钥仅保存在本机用户目录及 GitHub Actions Secrets，没有入库。本机与云端共用签名；构建额外核对不可调试标志、名称、包名、版本、图标资源、快捷方式文字和目标 Activity、许可证及签名证书。

29 项模型解析和桌宠几何测试通过，11 个 ELF 和 ZIP 的 16 KB 对齐检查通过。手机实际安装成功，系统登记了静态快捷方式，MainActivity 和 QuickActivity 均可启动。包名改变后为独立应用，旧实验版及旧数据保留；本次未复制旧版的密钥或会话。

本机最终产物 `build/client/DSH-Android-0.5.0-arm64.apk`，108945447 字节，SHA-256：`40213de74a58f50c6fc3023959d0f4d3215ad73b1910e30fbe02828485b8425a`；启动页也统一使用角色图标。GitHub Actions 改为从固定 npm 依赖和上游 APK 重建、签名原生客户端，大包不通过本机 Git 推送。

独立测试应用 `com.dshmobile.nativeprobe`。使用 Android 原生界面和 ARM64 Node.js 24.20.0，尝试在应用进程直接运行 DSH 0.2.2-rc.5。依赖解包到测试应用的私有目录，启动探针不会读取正式版的配置或会话。

## 构建和测试

1. 安装 Android SDK 36、NDK 27.2.12479018、Node.js、npm 和 Python 3。
2. 在仓库根目录运行 `python native-probe/prepare.py`。脚本校验 Android Node.js 发布包的 SHA-256，并按锁文件准备 DSH 依赖。
3. 运行 `gradlew.bat -p native-probe assembleDebug`，然后安装 `native-probe/build/outputs/apk/debug/native-probe-debug.apk`。
4. 打开「DSH 原生实验」，点「启动并验证 DSH 引擎」。报告分别显示 Node.js 平台、DSH 启动日志和本机 HTTP 就绪时间。

测试 App 用于定位 Android 运行时和依赖兼容性。Node.js 启动和 DSH HTTP 就绪是两项独立结果。聊天、插件和性能对比需要后续分别验证。

## 上游原生实现

`upstream/` 是 jackwener/DSH-Android `v0.3.11`（提交 `69ebd40d1d1d1d9760eaf7e1ee8b94a1e1ac95a7`）的源码副本，上游已归档，因此直接放在仓库内，保留其 Apache-2.0 授权。上游应用包名是 `io.github.jackwener.dsh`。

### 直接复用上游的测试版本

当前去 Ubuntu 的实验采用上游完整 Kotlin/Compose 界面、Android Node 共享库和引擎服务，不再以本目录最初的 Java 启动探针作为产品底座。

先运行 `python -B native-probe/prepare-engine.py`，再运行 `python -B native-probe/prepare-upstream.py --build`。需要 Python 3.11+、Git、JDK 21、Android SDK 和网络；引擎重建在 Windows 上还需要本机 WSL Ubuntu-26.04 的 Node/npm、patch 和 Python。脚本从固定 Git 提交准备源码，从校验过 SHA-256 的上游 v0.3.11 APK 提取原生库和 Shizuku 资源，并单独从锁定的 npm 依赖重建新版 DSH 引擎。Gradle 构建完整的 Android 应用；构建目录和运行时不进入 Git。

- 应用名：`DSH 安卓·原生实验`。
- 包名：`com.dshmobile.nativeexperiment`，与已有 DSH 安卓共存，独立保存设置和会话。
- APK：`native-probe/build/jackwener/experiment/app/build/outputs/apk/debug/app-debug.apk`。
- 上游自动更新检查默认关闭，避免独立签名的实验包提示安装原版。手动检查仍为上游功能，不作为实验包更新渠道。
- 该脚本保留原始源码和许可证；实验能力通过 `customize-upstream.py` 和 `overlay/` 加到生成的构建副本中，上游子模块保持原样。

这条路线不打包 Ubuntu，也不通过 PRoot/chroot 启动引擎。Android 原生 Node 不等于完整 Linux 用户空间；Ubuntu 中的 apt、Python 和 Linux 二进制插件不会因此自动兼容。

### 2026-11-22 构建记录

`python native-probe/prepare-upstream.py --build` 编译成功。Debug APK 为 116707696 字节（111.30 MiB），SHA-256：`e13dc5762d3cba06eb5e2c589a0e4711b6dc94217ebb0a050a06255a507cae88`。APK 签名验证通过，包名为 `com.dshmobile.nativeexperiment`，仅包含 arm64-v8a 原生库。

已核对 ZIP 完整性及内置 engine.zip 的 SHA-256，包内没有 Ubuntu/rootfs/PRoot/tawcroot 载荷。本次按要求只完成编译和包检查，未安装或实测这个自行编译的 APK；此前安装的 `io.github.jackwener.dsh` 是上游发布版，不能作为该 APK 的运行验证。

### 第二轮：Root、直连工作目录和连续画面

以下为新增源码能力，运行结果待手机验证：

- 设置 → 手机权限 → **Root 与目录直连**：显式开启并验证 su，输入目录绝对路径后保存；可解除选择。没有应用白名单，也没有存档专用修改工具。
- 引擎启动时将选定目录通过原有 Android Root/FUSE helper 挂到 App 私有的 `phone-workspace`，引擎初始化 cwd 指向该目录。Root 进程持有源目录，普通引擎工具直接读写挂载点，操作会影响原文件。选择保存在本机，后续引擎启动恢复连接；失败直接报错，不改用复制目录。
- 换目录或解除直连后，结束当前任务，再到设置 → 关于 → 引擎重启。现有会话可能保存自己的目录，新会话使用初始化目录。没有目录浏览器，当前通过绝对路径输入选择。
- 新增 `phone_sh`、`phone_root_ui`、`phone_root_tap`、`phone_root_swipe`、`phone_root_key`、`phone_root_screenshot` 和只读状态工具 `phone_workspace`。这些是独立 Root 入口；上游原有的 Shizuku 文件服务、系统专用工具和后台副屏没有因此全部改成 Root 后端。普通 bash 仍以 App 身份运行。
- 新增 `phone_watch`：沿用上游主屏/会话副屏选择，在设备上采样、筛变化帧、合成带实际时间戳的分镜，直接作为图片交给模型。支持 1–60 秒、1–3 fps、最多 24 帧；系统截图限速可能使实际采样频率降低。Root 开启后，主屏连续观察显式使用 Root 截图；非 Root 主屏使用无障碍截图，副屏使用已有副屏画面。
- Root 截图和上游无障碍截图均隐藏 DSH 自身浮层。Root 操作与命令成功只说明执行完成，仍不能替代对界面效果的核对。
- 原有 `tools/root-workspace.c` 用 NDK 编成 `libdshworkspace.so`，作为 APK 原生可执行文件打包，不引入 Ubuntu。Root/FUSE、SELinux、挂载命名空间和具体手机 ROM 的兼容性尚未实测。

第二轮 APK 已编译通过并校验签名、ZIP 完整性、engine.zip 哈希、FUSE 可执行文件及新增 8 个工具的 DEX 打包内容。交付路径为 `native-probe/build/DSH-Android-native-root-debug.apk`，118722685 字节，SHA-256 为 `541409f33d93104b10bee9c6a4fa301033b3db9c1e85902aa4a438e3726711d8`。这只能证明构建和打包成功，不代表 Root/FUSE 和连续观察已在手机运行通过；本轮没有安装到手机。

### 第三轮：DSH 0.2.2-rc.5

DSH 从 `0.2.1-rc.5` 升级至查询时 npm `latest` 对应的 `0.2.2-rc.5`，内置引擎完整标识 `0.2.2-rc.5-a1dd7c84273`。升级输入、补丁说明见 [engine/README.md](engine/README.md)。没有使用 `0.2.3-alpha.2`。

APK 版本为 `0.3.11-native-dsh0.2.2-rc.5`、versionCode 30，包名保持 `com.dshmobile.nativeexperiment`。Root 工具、FUSE 目录直连和连续观察的源码接入保留。交付文件 `native-probe/build/DSH-Android-native-dsh-0.2.2-rc.5-debug.apk`，123128078 字节（117.42 MiB），SHA-256：`adee5354a4c8a7ab4dccc0e170b0a8d76e516014b6bc6af69311fec58e0fedf0`。

已完成干净依赖重建、Android 补丁检查、干净 APK 构建、签名及 ZIP/引擎完整性核验，且启动、模拟 DeepSeek 账号登录/注销、模拟 OpenAI 兼容接口的 45 项主机端到端检查全部通过。测试使用模拟服务，不消耗用户模型凭据；不是手机实测。本轮没有操作手机或安装升级包。

针对最终引擎包的 300 项主机回归测试全部通过（失败、取消、跳过均为 0），覆盖第三方接口协议/Responses、推理参数、请求头、会话恢复、会话模型切换、运行中插话、连续任务、工具权限与扩展等。构建和测试日志在忽略的 `native-probe/build/` 中。

### 第四轮：16 KB 原生库对齐

手机系统提示 ELF 对齐警告后，逐项检查实际 APK：目录直连辅助程序 `libdshworkspace.so` 的 LOAD 段确实仅为 4 KB；截图列为“未知错误”的其余库，LOAD 段均已达到 16 KB 或更高。为辅助程序的 NDK r27 链接命令增加 `-Wl,-z,max-page-size=16384`，没有关闭可调试标记或屏蔽系统警告。

构建现在运行 `verify-native-alignment.py`，核对 APK 中全部 arm64 ELF 的 LOAD 对齐、动态 ELF 的 RELRO，以及 SDK `zipalign -c -P 16 4`。旧包准确检出辅助程序不合格，新包 11 个 ELF 与 ZIP 对齐全部通过；静态 musl ripgrep 的 RELRO 由其自身启动代码处理，不按 Android 动态链接库检查。压缩原生库和安装时提取的方式保持不变。

修复包已覆盖安装到手机，关闭旧警告后重启应用，界面树显示正常聊天页，没有新的兼容性弹窗。该手机实际页大小为 4096，因此这不等同于在真正 16 KB 页设备完成运行测试。新包 123128082 字节，SHA-256：`e8a5accc682be57ded239f8fc913e37008351ea52d9fa594d650907a1f47676c`；签名验证通过，交付文件仍为 `native-probe/build/DSH-Android-native-dsh-0.2.2-rc.5-debug.apk`。

### 第五轮：统一手机操作服务

首次增强能力引导、设置 → 手机权限顶部现在展示“手机操作方式”。Root 用户进入“Root 操作”授权；Shizuku 安装说明需要主动展开。抓树（读取界面文字和控件）和操控（点击、滑动、输入）是能力，不是互斥的授权模式。

`PhoneShell` 根据已保存的 Root 开关，选择内置 libsu 6.0.0 Root Binder 服务或原有 Shizuku 传输，二者复用 `ShellUserService`/`IShellService`。Root 失败直接报错，不自动改走 Shizuku。Root 下不必安装 Shizuku；使用系统命令、文件和副屏的原有消费者均接入同一服务，Root 身份使用真实 uid 0 和 android 归属信息。libsu 来源：https://github.com/topjohnwu/libsu （Apache-2.0）。

`screen_read/tap/type/swipe/scroll/key/wait/capture` 共用特权进程内的 UiAutomation 后端，模型不再需要另选 `phone_root_*` 工具。Root 开启时使用该后端；Root 关闭后，无障碍已启用则保留原有无障碍操作，只有 Shizuku 可用时也能通过同一服务抓树和操控。节点编号按会话/屏幕保存，动作后读取实际界面，中文输入通过控件设置文本；不支持设置文本的特殊编辑器明确报错。特殊滚轮的完整设置算法仍只在原有无障碍后端实现。主屏截图通过文件描述符传输，避免 Binder 大图片限制；连续画面保留原有采样流程并使用统一截图入口。

设备验证：Root uid 0、统一工具路由、抓树、按控件点击、中文输入并读回、等待文字出现、按键、截图、临时文件读写、创建并释放副屏全部通过。曾有一次测试被用户打开的通知栏遮挡；保持测试页面前台后全部通过。纯文件服务的 6 项回归在 Android 上全部通过；Windows 上其中 4 项因原测试使用 Linux 路径分隔符失败，因此没有宣称 Windows 全通过，也没有为此修改生产路径逻辑。身份映射的 5 项主机测试通过。当前没有实测 Shizuku 传输的新抓树接口，也没有把所有特殊应用编辑器或滚轮声明为已支持。

最终 APK 124444867 字节，SHA-256：`d50bdac868b1ecc557af405fe7a3f0ed992d4beedf9879f89824d82ce145378a`。已覆盖安装，保留应用数据；测试 APK 已移除。签名、11 个 ELF 和 APK ZIP 对齐检查通过。交付文件路径同上一轮。

### 第六轮：Root 项目选择与后台屏授权

“Root 与项目”改用应用现有 Compose 主题，保留系统栏安全距离和可滚动布局。添加项目先浏览文件夹，再命名，保存多个项目并选择当前项目；移出列表只移除配置，不删除原文件。原有直连目录自动展示在项目列表。项目目前共用一个当前工作目录，切换后按界面提示结束任务、重启引擎并新建会话；不是按会话隔离的项目系统。

文件夹选择支持根目录、手机存储、所有应用数据，手动路径为次级入口。进入页面先验证真实 Root 服务身份；无 Root 或未获授权时不能添加或挂载项目。直连只使用 Root/FUSE，不以 Shizuku、复制目录或其他方式替代。

修复 Root uid 已为 0 但应用隔离挂载视图仍隐藏其他 App 的问题：libsu 主 shell 使用 `FLAG_MOUNT_MASTER`，独立 su 命令和 FUSE helper 显式使用全局挂载视图；helper 先打开源目录，再进入目标 App 的挂载命名空间。设备上全局 Root 与 Binder 文件服务均能枚举 504 个应用数据目录，独立临时挂载能读写另一测试 App 缓存中的原文件，测试未改变用户的 Pictures 项目配置或文件。

“后台屏操作”说明为在独立虚拟屏幕运行 App，默认关闭。界面和保存开启状态前均检查 Root 或 Shizuku 系统服务的真实身份；无授权时不能开启，已保存开启但授权失效时显示暂停，并允许关闭。无障碍本身不满足创建后台屏的前提。

新项目选择弹窗的抓树、点击、中文输入回读、截图、等待、按键及 Root 文件/副屏设备回归通过。最终包 124691507 字节，SHA-256：`f6c4e4f0c43007959141f56bb9aee14e898c85622946156e03114d97c38b1b2e`，已覆盖安装并移除测试 APK，签名和全部 11 个 ELF/ZIP 对齐验证通过。交付路径不变。

### 第七轮：原生引擎身份自动切换（2026-11-23）

启动及 App 从后台回到前台时，以新的 `su --mount-master -c 'id -u'` 检查授权，不能用已有 Root Binder 代替检查。未授权使用普通 App 引擎；已授权由独立 libsu daemon 承载 Node，实际 uid 0，直接进入选中项目原目录，不再调用 FUSE 工作区恢复。普通 Android `:engine` 服务继续承担前台服务和 SDK 通道宿主职责。Root 进程监听宿主 Binder 死亡，随宿主退出；遗留 daemon 在下次启动前被显式停止，并等待旧 Android 宿主真正退出，避免启动竞争。

授权身份发生变化，空闲时自动换引擎；有运行会话、账号操作或实际设备工具调用时延后。关闭 Root 不再要求取消项目、结束任务或手动重启，保存的项目继续保留，普通引擎使用 App home，受限项目暂停访问。用户主动关闭 Root 单独持久化，不能因 KernelSU 授权仍有效而自动重新开启；再次点击开启会重新验证实际 su 授权。旧会话可能仍保存旧 cwd，切换项目仍建议新建会话。

Root 引擎创建的应用内部配置/缓存由受限范围的文件观察器交回 App uid，退出时补做所有权恢复；不跟随符号链接，不修改项目树所有权。工作区工具说明改为原目录及实际身份，去掉必须使用 `phone-workspace` 的描述。

设备验证已重复通过：真实 SDK `account/state`（不调用模型）、Root→普通→Root 双向切换、真实 `phone_sh sleep 2` 执行期间延后切换、项目配置保留、用户主动禁用持久生效、Root 创建的 0600 内部状态文件交回 App uid、从后台返回时重新检查 su 并自动切换。后台测试通过在后台解除测试用的用户禁用标记模拟身份重新具备资格，随后确实调用 su 检查；没有修改手机 KernelSU 的应用授权策略，不能等同于已经人工实测 KernelSU 拒绝→允许的完整设置操作。此前核对真实 Root Node 进程 uid 0、cwd 为苏丹项目原路径。最终测试退出后未遗留 App 的 Root daemon。

最终 APK 124171955 字节，SHA-256：`55e6810b6bd02ddd6d2c018914ef5b3ac54d4431c05c6fc67fc6daa5e8342dec`，已覆盖安装，测试包已移除；签名与 11 个 ELF/ZIP 对齐检查通过。交付路径保持不变。

### 第八轮：悬浮球后端计划一（2026-11-23）

直接对接用户提交的 `BallPrefs.kt`、`BallSettingsRows.kt`、`PetBallView.kt`（前端提交 `54b5155`），没有另建配置仓库或更换公开接口。服务订阅 `BallPrefs.flow(context)`，使用 `BallConfig.skin/sizeDp/keepInApp/posXRatio/posYRatio`；松手调用 `setPosition` 保存相对坐标，使用 `PetBallView.setSkin/setVisualState` 驱动视图。设置入口挂在通用 → 快捷入口，保留用户提供的大小滑块和推荐值吸附逻辑。

`customize-ball.py` 构建时从 `branding/res` 读取并核对 8 张 `dsh_pet_*.webp`，写到生成工程 drawable-nodpi；不修改 upstream 子模块。已验证资源逐字节与 main 一致，`processDebugResources` 生成全部 8 个 R.drawable 字段，APK 中包含全部 8 张 WebP。

悬浮窗口取消强制吸边，可自由停留；相对位置基于扣除状态栏、导航栏、显示缺口和间距后的可移动范围。拖动、缩放和旋转均钳制安全边界；胶囊根据球的左右半区选择另一侧并限制宽度与坐标。配置流实时更新尺寸、皮肤和应用内显示，不重启服务。待机/运行/等待/完成/失败映射到前端五个 BallVisualState，停止映射待机。截图隐藏优先于 keepInApp，嵌套截图计数保持原有恢复语义。

修复桌宠双击仍强制要求无障碍的问题：`BallScreenCapture` 重新检查 Root 授权，复用统一 Root/Shizuku 截图后端；二者不可用时才使用已授权的无障碍路径。截图失败明确报错，不偷偷换后端；无障碍界面文字仅为可选元数据。截图先隐藏自身窗口，再把真实图片放入 ScreenContext 交给快捷对话附件，不自动调用模型。

验证：4 项几何单测全部通过；设备上的配置流、非边缘自由位置、真实触摸处理及位置保存、实时缩放/换肤、胶囊左右贴靠、五种姿势映射、嵌套隐藏/恢复、真实横屏安全区检查全部通过。无障碍实例暂置空时，实际双击截图后端仍通过 Root 捕获并交付图片；没有关闭用户系统无障碍权限。单独两轮进程重启测试确认五个配置字段从磁盘恢复，之后还原原配置。测试没有发送模型消息，临时测试配置和截图附件已清理。

最终交付 APK 已覆盖安装，125607914 字节，SHA-256：`2cd59ce77e0c1ae929c2f5cbdf0cc963035416370251ff2dcd38ab2be3847d76`。签名及全部 11 个 ELF/ZIP 对齐检查通过，测试 APK 已移除，源码工作区和 upstream 子模块干净。

### 第九轮：退出等待、Root 临时目录及模型列表超时（2026-11-23）

设备记录确认 01:02:40 的 SIGKILL 对应 Android `:engine` 宿主，01:02:45 主进程因 `Previous engine process did not exit` 未处理异常崩溃。停止引擎现在持有 Binder 强引用，并检查实际 Binder 存活状态，避免解绑后仅依赖死亡回调造成误判；退出过程不被身份刷新取消，公开停止入口把真实失败交给引擎状态显示。主动停止完成后写入已有的正常退出标记，不将主动终止重复计入异常记录。

Root 引擎改用 `cache/tmp/root-engine`，其临时树在运行期间保留 uid 0 和 0700 权限，不由实时状态观察器交回 App uid；停止后仍在原有受限应用缓存范围恢复所有权。普通 App 引擎继续使用自己的临时目录，不修改项目树。模型列表移除 1 ms 超时覆盖，使用 DSH 默认 5000 ms；rc.5 的该参数会取消整个请求，不是只限制前台等待。

真实设备回归 8 项通过：SDK account/state、Root 溢出目录权限、强制 GC 下两次停止重启、实际设备命令执行期间延后切换、Root→App→Root、后台返回重新检查、主动关闭 Root 持久化、内部状态所有权恢复。没有发送模型消息或修改项目文件。最终引擎日志无 unsafe-root 警告，DeepSeek 模型列表实际联网加载 2 项成功。测试 APK 已卸载，主界面冷启动正常；签名和全部 11 个 ELF/ZIP 对齐验证通过。

本轮交付 APK 150504354 字节，SHA-256：`878f5158559a83380a65c1540ec0a25c0c5fcb74156222fb2938dbe49c8aa5fb`，已覆盖安装到手机。
