# 浮屏译

[English](README.md) | 简体中文

Floating Screen Translator 是一套用于 Android 的屏幕 OCR 悬浮翻译工具，
适合翻译无法选择或复制的屏幕文字。默认在本机完成截图、OCR 和翻译；
只有用户主动配置并选择后，才会使用兼容 OpenAI Chat Completions 的云端接口。

本项目由 **oen1day** 与 **OpenAI Codex（GPT-5）** 共同完成，免费开源。

> 人人为我，我为人人。

## 主要功能

- 全屏或自定义矩形区域 OCR。
- 可拖动、可缩放、可调整透明度、可沉浸收起的悬浮翻译面板。
- 首次启动时选择界面语言：简体中文、繁體中文、English、Русский。
- 可配置 OCR 源语言和目标语言。
- 快速模式：设备端 ML Kit 翻译。
- 均衡模式：可选的 Qwen2.5 1.5B 本地精修。
- 精细模式：可选的 Qwen2.5 3B 本地精修。
- API 模式：可选的用户自有 OpenAI 兼容接口。
- 日→中专项：可选的 Sakura 7B 日文轻小说／视觉小说翻译模型。
- `JA→ZH` 专项强度只在支持的语言方向中出现。
- 7B 模型运行在独立进程中，支持取消、卸载、模型校验和 KV 前缀缓存。
- 不内置敏感词拦截、内容降级或翻译删减层。

## OCR 与翻译

应用将 Android 屏幕捕获、OCR 和可选的翻译后端串联起来。翻译强度与界面语言
互相独立。本地模式不会上传识别文本；API 模式只有在你主动配置并选择后才联网。

## 界面语言

界面语言与 OCR／翻译语言不同。首次启动时可以选择，之后也可以在首页修改。
目前 App 界面支持简体中文、繁體中文、英语和俄语；公开文档暂时只提供英文和
简体中文，不发布只有摘要的半成品俄语文档。

## 隐私

- 默认不上传屏幕图像。
- API 模式只发送用户明确选择并配置后的识别文本。
- API Key 使用 Android Keystore 加密保存。
- 无遥测、广告、账号系统或项目自建服务器。

详细说明见 [PRIVACY.md](PRIVACY.md) 和 [SECURITY.md](SECURITY.md)。这两份
政策文件目前为英文。

## 环境要求

- JDK 21
- Android SDK 34
- Android NDK `26.3.11579264`
- CMake `3.29.2`

## 构建

```bash
git clone --recurse-submodules \
  https://github.com/oen1day/floating-screen-translator.git
cd floating-screen-translator
./gradlew assembleDebug -PscreenTranslatorArmArch=
```

可选的 ARMv8.6 dot-product/i8mm 加速构建：

```bash
./gradlew assembleDebug \
  -PscreenTranslatorArmArch=armv8.6-a+dotprod+i8mm
```

未知设备优先使用通用 ARM64 构建；只有确认设备支持时，才使用专用加速构建。

## 模型

模型和编译后的 APK 不包含在仓库中，由用户自行下载。

- Qwen2.5 1.5B LiteRT
- Qwen2.5 3B LiteRT
- Sakura 7B Qwen2.5 v1.0 IQ4_XS

Sakura 模型使用 `CC-BY-NC-SA-4.0` 许可证，仅限非商业使用。详见
[docs/SAKURA_MODEL.md](docs/SAKURA_MODEL.md) 和
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 限制

- 屏幕捕获行为受 Android 和 OEM 策略影响。
- 受保护或 DRM 内容可能显示为黑屏。
- 本地 7B 翻译在常见手机上无法达到实时字幕速度。
- Sakura 约需 4.25 GB 存储空间和较多运行内存。
- 本地模型不能等同于专业人工翻译。

## 项目文档

- [架构](docs/ARCHITECTURE.md)
- [维护说明](docs/MAINTENANCE.md)
- [测试说明](docs/TESTING.md)
- [贡献者与署名](CREDITS.md)
- [贡献指南](CONTRIBUTING.md)
- [许可证](LICENSE)

## 许可证

项目原创代码使用 MIT License。第三方软件和可选模型文件继续使用各自许可证。
本项目是独立社区项目，与 Google、llama.cpp、SakuraLLM、Xiaomi 或任何视觉小说
发行商没有隶属关系。
