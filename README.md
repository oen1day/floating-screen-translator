# 浮屏译 / Floating Screen Translator / Плавающий переводчик экрана

**本项目由 oen1day 与 OpenAI Codex（GPT-5）共同完成，免费开源。**
**Created by oen1day with OpenAI Codex (GPT-5). Free and open source.**
**Создано oen1day совместно с OpenAI Codex (GPT-5). Бесплатно и с открытым кодом.**

> 人人为我，我为人人。
> All for one, one for all.
> Один за всех, все за одного.

[中文](#中文) · [English](#english) · [Русский](#русский) ·
[Privacy](PRIVACY.md) · [Security](SECURITY.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

---

## 中文

一个用于 Android 的屏幕 OCR 悬浮翻译工具。默认在本机完成截图、OCR 和翻译，
也可以由用户主动配置兼容 OpenAI Chat Completions 的云端接口。

### 主要功能

- 全屏或用户自定义矩形区域 OCR。
- 可拖动、可缩放、可调整透明度、可完全沉浸收起的悬浮翻译面板。
- 首次启动时选择界面语言：简体中文、繁體中文、English、Русский。
- 可配置源语言和目标语言。
- 快速：ML Kit 本地翻译。
- 均衡：Qwen2.5 1.5B 本地精修。
- 精细：Qwen2.5 3B 本地精修。
- API：用户自行配置的 OpenAI 兼容接口。
- 日→中：Sakura 7B 日文轻小说/Galgame 专项模型。
- 第五档“日→中”只在日语或自动识别到中文方向时出现。
- 7B 模型运行在独立进程，支持取消、卸载、模型校验和 KV 前缀缓存。
- 不内置敏感词拦截、内容降级或翻译删减。

### 首个界面语言

首次打开 App 时会显示四个选项：

- 🇨🇳 中文
- 🇭🇰 繁體中文
- 🇺🇸 English
- 🇷🇺 Русский

该选项修改的是 App 界面语言，不会缩减 OCR/翻译语言列表。
后续可在首页的“界面语言”按钮中再次修改。

### 隐私

- 默认不上传屏幕图像。
- API 模式只在用户主动选择并配置后联网。
- API Key 使用 Android Keystore 加密保存。
- 无遥测、广告、账号系统或项目自建服务器。

详细说明见 [PRIVACY.md](PRIVACY.md) 和 [SECURITY.md](SECURITY.md)。

### 构建

需要 JDK 21、Android SDK 34、NDK `26.3.11579264` 和 CMake `3.29.2`。

```bash
git clone --recurse-submodules \
  https://github.com/oen1day/floating-screen-translator.git
cd floating-screen-translator
./gradlew assembleDebug -PscreenTranslatorArmArch=
```

MT6989 / ARMv8.6 + dot-product + i8mm 加速构建：

```bash
./gradlew assembleDebug \
  -PscreenTranslatorArmArch=armv8.6-a+dotprod+i8mm
```

通用 ARM64 包适合未知设备；不要在不确定兼容性的旧设备上使用专用包。

### 模型说明

模型不包含在仓库或 APK 中，由用户主动下载。

- Qwen2.5 1.5B LiteRT
- Qwen2.5 3B LiteRT
- Sakura 7B Qwen2.5 v1.0 IQ4_XS

Sakura 许可证为 `CC-BY-NC-SA-4.0`，仅限非商业使用。详见
[docs/SAKURA_MODEL.md](docs/SAKURA_MODEL.md)。

### 限制

- 屏幕捕获和设备兼容性受 Android/OEM 策略影响。
- 受保护页面可能显示黑屏。
- 本地 7B 仍不是实时字幕；不同设备速度差异较大。
- Sakura 约需 4.25GB 存储和较多运行内存。
- 本地模型不等于人工或超大云端模型。

更多资料：[架构](docs/ARCHITECTURE.md)、[维护说明](docs/MAINTENANCE.md)、
[测试](docs/TESTING.md)。

---

## English

An Android overlay OCR translator for text that cannot be selected or copied.
OCR and translation run locally by default. Cloud translation is opt-in and
uses a user-provided OpenAI-compatible Chat Completions endpoint.

### Features

- Full-screen or custom rectangular OCR capture.
- A movable, resizable, transparent, collapsible floating translation panel.
- First-run interface language selection: Simplified Chinese, Traditional
  Chinese, English, and Russian.
- Configurable OCR source and translation target languages.
- Fast mode with on-device ML Kit translation.
- Optional Qwen2.5 1.5B balanced mode.
- Optional Qwen2.5 3B precise mode.
- Optional user-configured cloud API mode.
- Optional Sakura 7B Japanese-to-Simplified-Chinese literary model.
- The `JA→ZH` strength appears only for supported language directions.
- Heavy llama.cpp inference runs in an isolated process with model
  verification, cancellation, unloading, and KV prefix caching.
- No built-in content filtering or translation downgrade layer.

### Interface language

The interface language is separate from the OCR/translation language pair.
Change it from the first-run screen or the home screen's Interface Language
button.

### Privacy

- Screen images are not uploaded by default.
- API mode sends recognized text only when explicitly selected and configured.
- API keys are encrypted with Android Keystore.
- No analytics, advertising, accounts, or project-operated server.

Read [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

### Build

Requirements: JDK 21, Android SDK 34, NDK `26.3.11579264`, CMake `3.29.2`.

```bash
git clone --recurse-submodules \
  https://github.com/oen1day/floating-screen-translator.git
cd floating-screen-translator
./gradlew assembleDebug -PscreenTranslatorArmArch=
```

Optional ARMv8.6 dot-product/i8mm build:

```bash
./gradlew assembleDebug \
  -PscreenTranslatorArmArch=armv8.6-a+dotprod+i8mm
```

Models are downloaded separately. The Sakura model is licensed
`CC-BY-NC-SA-4.0` and restricted to non-commercial use. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

### Limitations

- Capture behavior depends on Android and OEM policy.
- Protected or DRM content may appear black.
- Local 7B translation is not real-time on typical phones.
- The Sakura model requires about 4.25GB of storage and substantial RAM.
- Local models are not equivalent to professional human translation.

See [Architecture](docs/ARCHITECTURE.md), [Maintenance](docs/MAINTENANCE.md),
and [Testing](docs/TESTING.md).

---

## Русский

Android-приложение для OCR-перевода текста поверх других приложений. OCR и
перевод по умолчанию выполняются локально; облачный API включается только
пользователем.

Поддерживаются четыре языка интерфейса: упрощённый китайский, традиционный
китайский, английский и русский. Модели Qwen и Sakura загружаются отдельно.
Sakura 7B распространяется по лицензии `CC-BY-NC-SA-4.0` и разрешена только для
некоммерческого использования.

Создано oen1day совместно с OpenAI Codex (GPT-5). Проект бесплатный и открытый.

---

## License

Original project code is licensed under the MIT License. Third-party software
and optional model files retain their own licenses. This is an independent
community project and is not affiliated with Google, llama.cpp, SakuraLLM,
Xiaomi, or any visual-novel publisher.
