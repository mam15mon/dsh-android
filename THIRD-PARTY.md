# 第三方组件声明 / Third-Party Notices

## 原生 Android 版

- Android 源码和部分 ARM64 二进制来自 [jackwener/DSH-Android v0.3.11](https://github.com/jackwener/DSH-Android/releases/tag/v0.3.11)。上游 APK 的 SHA-256 为 `3b0e0b085ecba828899734c3562da7435e4966c98603fc5cf26e633991d376e5`。对应源码和重建说明见 [native/](native/README.md)，上游 Apache 2.0 许可证随 APK 打包。
- Node.js、LLVM libc++、ripgrep、node-pty 及 Node 附带组件的许可证在 [app/src/main/assets/licenses](app/src/main/assets/licenses/)。Root 工作区助手来自本仓库的 [tools/root-workspace.c](tools/root-workspace.c)。
- DSH 引擎 `0.2.2-rc.5` 依据 [npm 锁文件](js/engine/package-lock.json) 构建，`engine.zip` 保留分发包中的许可文件。内置的 [Shizuku 13.6.0](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0) 是原版签名包，许可证在 [同目录](app/src/main/assets/shizuku/LICENSE)。
- 应用通过 Shizuku API 和 Provider 13.1.5 接入 Shizuku，两者采用 [Apache License 2.0](LICENSES/Apache-2.0.txt)。
- 附件图片处理使用 sharp 的 WebAssembly 构建（`@img/sharp-wasm32`），其中的 libvips 采用 [LGPL-3.0](LICENSES/LGPL-3.0.txt)，许可文件随 `engine.zip` 分发。

### OkHttp 与 Okio

豆包实时语音识别通过 OkHttp 4.12.0 建立 WebSocket 连接。OkHttp 及其依赖 Okio 由 Square 开发，采用 [Apache License 2.0](LICENSES/Apache-2.0.txt)。源码见 [OkHttp](https://github.com/square/okhttp) 和 [Okio](https://github.com/square/okio)。

## 商标声明 / Trademark

本项目是**非官方**的第三方封装，与 **DeepSeek 没有任何隶属或背书关系**。
"DeepSeek"、"DeepSeek Harness" 等名称与标识归其各自权利人所有，
本项目仅在**描述兼容对象**的意义上使用这些名称。
