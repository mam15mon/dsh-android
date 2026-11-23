# DSH 安卓

在 Android 本机运行 DeepSeek Harness 的原生客户端，使用 Kotlin / Compose 界面、Android 原生 Node.js 和 DSH 引擎。旧容器版已停止维护。

- 应用名：**DSH 安卓**
- 包名：`com.dsh.android`
- 当前版本：`0.5.5`，内置 DSH `0.2.2-rc.5`
- 支持 Android 10 及以上、ARM64 手机。
- 长按桌面应用图标，选择 **开始对话**，直接打开桌宠使用的快捷对话窗口。
- 手机操作支持无障碍和可选的 Root / Shizuku；Root 引擎可直接访问选中的原始项目目录。

模型服务正常联网。Android 原生运行时不提供 Ubuntu 的 apt 或完整 Linux 用户空间，Linux 专用二进制插件需要单独适配。

## 下载

从 [GitHub Actions](https://github.com/mam15mon/dsh-android/actions/workflows/regression.yml) 的成功构建中下载 `DSH-Android-arm64`，其中包含签名 APK 和 SHA-256 校验文件。

新包名与原生实验版、旧容器版独立安装、独立存储数据，旧版本不会被自动卸载。

## 构建

在 Android Studio 打开仓库根目录，选择 JDK 21 和 Android SDK 37.2。应用源码、资源和测试位于 `app/src/`，版本配置位于 `client.json`。Gradle 直接使用仓库内的 ARM64 原生库、DSH 引擎和 Shizuku APK。

```bash
./gradlew assembleRelease
```

Windows 用 `.\gradlew.bat assembleRelease`。APK 产物位于 `app/build/outputs/apk/release/`。正常构建需要访问 Gradle 和 Maven 依赖仓库，运行时文件随源码检出。

正式签名时，设置 `DSH_ANDROID_KEYSTORE`（原密钥库绝对路径）和 `DSH_ANDROID_STORE_PASSWORD`（密钥库口令）。签名别名为 `dsh_android`，密钥与密钥库使用同一口令。两项均未设置时 Gradle 产出未签名的 APK。GitHub Actions 使用 `DSH_ANDROID_KEYSTORE_B64` 和 `DSH_ANDROID_STORE_PASSWORD`，并验证签名及 16 KB 对齐。

维护运行时时，可按 [JS 引擎构建](js/README.md) 和 [原生库构建](native/README.md) 更新仓库内的预制文件。

## 源码与授权

这是第三方非官方客户端，与 DeepSeek 没有隶属或背书关系。原生客户端基于 [jackwener/DSH-Android](https://github.com/jackwener/DSH-Android)，保留 Apache-2.0 授权，原始许可证同时随 APK 分发；本仓库原有自有代码的 MIT 许可证保留在 [LICENSE](LICENSE)。DSH 和其他依赖遵守各自许可证。

[原生版实现与验证记录](docs/native-probe-history.md)
