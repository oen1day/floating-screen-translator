# Floating Screen Translator

[English](README.md) | [简体中文](README.zh-CN.md)

An Android overlay OCR translator for text that cannot be selected or copied.
OCR and translation run locally by default. Cloud translation is optional and
uses a user-provided OpenAI-compatible Chat Completions endpoint.

Created by **oen1day** with **OpenAI Codex (GPT-5)**. Free and open source.

> All for one, one for all.

## Features

- Full-screen or custom rectangular OCR capture.
- A movable, resizable, transparent, and collapsible floating translation panel.
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

## Transcription and translation

The app combines Android screen capture, OCR, and one of several translation
backends. The selected translation strength is independent of the interface
language. Local modes keep recognized text on the device; API mode sends the
recognized text only after the user configures and selects it.

## Interface language

The interface language is separate from the OCR/translation language pair.
Choose it on the first-run screen or change it later from the home screen.
Russian is available in the app interface, but this repository currently
provides documentation in English and Simplified Chinese only.

## Privacy

- Screen images are not uploaded by default.
- API mode sends recognized text only when explicitly selected and configured.
- API keys are encrypted with Android Keystore.
- No analytics, advertising, accounts, or project-operated server.

Read [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

## Requirements

- JDK 21
- Android SDK 34
- Android NDK `26.3.11579264`
- CMake `3.29.2`

## Build

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

The generic ARM64 build is safer for unknown devices. Use the accelerated
build only when the device is known to support it.

## Models

Models and compiled APKs are not included in this repository. Users download
models separately.

- Qwen2.5 1.5B LiteRT
- Qwen2.5 3B LiteRT
- Sakura 7B Qwen2.5 v1.0 IQ4_XS

The Sakura model is licensed under `CC-BY-NC-SA-4.0` and is restricted to
non-commercial use. See [docs/SAKURA_MODEL.md](docs/SAKURA_MODEL.md) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Limitations

- Capture behavior depends on Android and OEM policy.
- Protected or DRM content may appear black.
- Local 7B translation is not real-time on typical phones.
- The Sakura model requires about 4.25 GB of storage and substantial RAM.
- Local models are not equivalent to professional human translation.

## Project documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Maintenance](docs/MAINTENANCE.md)
- [Testing](docs/TESTING.md)
- [Credits](CREDITS.md)
- [Contributing](CONTRIBUTING.md)
- [License](LICENSE)

## License

Original project code is licensed under the MIT License. Third-party software
and optional model files retain their own licenses. This is an independent
community project and is not affiliated with Google, llama.cpp, SakuraLLM,
Xiaomi, or any visual-novel publisher.
