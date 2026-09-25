---
status: accepted
---

# 独立的原生 Android TV 应用

现有 MoonTVPlus 仓库已有 `/tv` Web 页面及 WebView/GeckoView Android 壳，但用户决定构建全原生 Android TV 应用，以获得由 Android 端负责的电视界面与播放体验。当前工作目录将成为独立的应用 Git 仓库；MoonTVPlus 源仓库作为 `vendor/MoonTVPlus` 下的只读 Git submodule，固定已验证的服务端提交，供接口核对和参考。应用构建不依赖该 submodule，也不在源仓库中实现应用。现有 TV Web 页面和壳是参考实现，不是新应用的实现基础。
