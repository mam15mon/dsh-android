# AGENTS.md

DSH 安卓：原生 Android 客户端（Kotlin / Compose + Android 原生 Node.js + DSH 引擎）。包名 `com.dsh.android`，仓库 `mam15mon/dsh-android`，版本号在 `client.json`。

## 构建

- `./gradlew assembleRelease`，单元测试 `./gradlew :app:testDebugUnitTest`。详见 [README](README.md#构建)。
- 只改 Markdown、`docs/`、`LICENSE`、`LICENSES/` 的推送不会触发 CI。

## 发布

完整流程见 [docs/RELEASE.md](docs/RELEASE.md)。要点：

1. 改 `client.json` 的 `versionName`，并递增 `versionCode`，推到 main，等这次 push 的构建通过。
2. 用 workflow_dispatch 发布：`gh workflow run regression.yml --ref main -f release_notes="<更新说明>"`。更新说明要先给用户确认。
3. 不要在本地签名，也不要手动上传 APK 或创建 Release：签名密钥只在 GitHub Secrets 里，App 内更新会拒绝签名不同的安装包。
4. 发布后检查 `https://github.com/mam15mon/dsh-android/releases/latest/download/update.json` 的包名和版本号。

## 约定

- 这里只维护原生 Android 版；旧容器版已停止维护，不要恢复相关代码或文档。
- main 受「Protect main」规则保护，禁止强制推送和删除分支。
