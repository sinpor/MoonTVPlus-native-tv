# MoonTVPlus Native TV

独立的原生 Android TV 播放端。应用连接用户自行部署的 MoonTVPlus 服务，不提供管理功能。最低支持 Android 6（API 23）。

当前为开发预览。模拟器已验证连接、扫码登录、详情和播放错误处理；真实服务、媒体格式与实体遥控器仍需按 [实施方案](docs/implementation-plan.md) 验收。

## 仓库结构

- `app/`：Kotlin + Compose for TV + Media3 应用。
- `vendor/MoonTVPlus/`：固定提交的服务端源码 submodule，供核对接口；应用构建不依赖它。
- `docs/`：已确认的产品范围、架构决定和实施方案。

## 本地构建

安装 JDK 17、Android SDK API 35 后运行：

```bash
./gradlew :app:assembleDebug
```

debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。正式版通过 GitHub Actions 在 `v*` 标签上构建；需先配置 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四项仓库 Secrets。签名密钥原件应在两个独立位置加密备份，并验证能够恢复。密钥与密码不得提交到仓库。

如果发布构建在 `packageRelease` 报 keystore 读取错误，先在本机运行 `keytool -list -keystore /path/to/release.jks -alias <alias>`，按提示输入仓库密码，确认原始文件和 alias 可读取。然后重新从该文件生成完整的 Base64 内容，更新 `ANDROID_KEYSTORE_BASE64`；同时核对仓库密码、alias 和密钥密码对应的三个 Secret。不要把密钥、密码或 Base64 内容贴到日志或 issue 中。修正 Secrets 后可以重跑失败的工作流；如需使用新增的工作流校验步骤，则从包含该步骤的提交创建新标签。

## 首次使用

在电视上输入 MoonTVPlus 服务地址，或扫描电视二维码用同一局域网的手机输入。优先使用 HTTPS；HTTP 需要电视端明确确认。随后手机扫描登录二维码，在 MoonTVPlus 网页确认电视登录。

完整首版目标与验收条件见 [实施方案](docs/implementation-plan.md)。
