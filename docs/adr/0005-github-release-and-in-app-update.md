---
status: accepted
---

# 通过 GitHub Releases 发布并在应用内发起更新

首版通过 `sinpor/MoonTVPlus-native-tv` 的 GitHub Releases 交付签名 APK，应用检查新版本后可自行下载 APK 并调起 Android 系统安装界面。用户仍须在系统界面确认安装；应用不能静默更新。后续版本必须沿用相同的应用 ID 和签名密钥才能覆盖安装。为避免密钥丢失，发布版使用新建的独立签名密钥，开发者保留两份独立位置的加密备份，并验证可从备份恢复；GitHub Actions 通过加密 Secrets 使用签名材料的副本。相比仅提示用户前往发布页，这一选择减少电视上的手动下载步骤，但增加了更新下载、文件校验和安装流程的实现与维护成本。
