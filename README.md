# Floating Screen Translator

An Android overlay OCR translator with selectable screen regions and optional
local translation engines powered by ML Kit, LiteRT, and llama.cpp.

The app is intended for accessibility-style reading workflows where text is
rendered on screen and cannot be selected or copied. OCR and translation run
locally by default. Cloud translation is opt-in and requires a user-provided
OpenAI-compatible API endpoint.

## Features

- Full-screen OCR or a user-defined rectangular capture region.
- Persistent floating translation panel with drag, resize, opacity, and
  compact modes.
- Configurable source and target languages.
- Fast mode using ML Kit on-device translation.
- Optional Qwen2.5 1.5B and 3B local translation engines.
- Optional Sakura 7B Japanese-to-Simplified-Chinese literary model.
- Direction-aware fifth strength option: the `日→中` option appears only for
  Japanese-to-Chinese or auto-to-Chinese.
- Heavy llama.cpp inference runs in an isolated Android process.
- KV prefix caching avoids recalculating unchanged system prompts.
- API mode for user-configured OpenAI-compatible endpoints.
- No built-in content filtering or translation downgrade layer.

## Supported environment

- Android 8.0 / API 26 or later.
- `arm64-v8a` devices for the local llama.cpp backend.
- Screen capture or the optional accessibility compatibility service.
- Android 14+ can capture only the target application window on supported
  devices.

The project has been manually tested on Android 16/HyperOS. Other OEM software
may impose additional restrictions on accessibility overlays, background
services, or screen capture.

## Privacy and permissions

- Screen images are processed locally by default.
- The accessibility service is optional and used for a compatibility capture
  path and screen interaction assistance.
- The floating panel requires the Android overlay permission.
- API mode sends recognized text only when the user explicitly selects API mode
  and configures a third-party endpoint.
- API keys are encrypted with Android Keystore.
- No analytics, telemetry, advertising SDK, or account system is included.

See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md) before installing.

## Build

### Requirements

- JDK 21
- Android SDK Platform 34
- Android NDK `26.3.11579264`
- CMake `3.29.2`
- Git with submodule support

Clone with the native dependency:

```bash
git clone --recurse-submodules https://github.com/oen1day/floating-screen-translator.git
cd floating-screen-translator
```

If the repository was cloned without submodules:

```bash
git submodule update --init --recursive
```

Build the portable ARM64 package:

```bash
./gradlew assembleDebug -PscreenTranslatorArmArch=
```

Build an optional package optimized for ARMv8.6 devices with dot-product and
int8 matrix instructions:

```bash
./gradlew assembleDebug \
  -PscreenTranslatorArmArch=armv8.6-a+dotprod+i8mm
```

The generic package is recommended for distribution unless the target devices
are known to support the optimized instruction set.

## Local models

Large models are not included in this repository or the APK. The app obtains
them through Android `DownloadManager` after an explicit user action.

- Qwen2.5 1.5B: LiteRT model used by the balanced mode.
- Qwen2.5 3B: LiteRT model used by the precise mode when installed.
- Sakura 7B: Japanese-to-Simplified-Chinese GGUF used by the optional `日→中`
  strength.

See [docs/SAKURA_MODEL.md](docs/SAKURA_MODEL.md) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Optional accessibility auto-recovery

Some HyperOS builds remove an accessibility service after force-stop. An
optional developer feature can rewrite only this application's own
accessibility component when `WRITE_SECURE_SETTINGS` has been granted manually
through ADB:

```bash
adb shell pm grant io.github.oen1day.floatingtranslator \
  android.permission.WRITE_SECURE_SETTINGS
```

The debug build uses the package suffix `.debug`. The feature never changes
other applications' accessibility settings and is disabled by default.

## Limitations

- Screen capture and OCR performance vary significantly by device.
- DRM-protected, secure, or hardware-overlay content may appear black.
- Local 7B translation is not real-time on typical phones.
- The optional Sakura model requires roughly 4.25 GB of storage and substantial
  runtime memory.
- Translation quality is not equivalent to professional human translation.
- Vendor-specific background and overlay restrictions remain a compatibility
  risk.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Maintenance notes](docs/MAINTENANCE.md)
- [Testing guide](docs/TESTING.md)
- [Sakura model details](docs/SAKURA_MODEL.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## License

Original source code is licensed under the MIT License. Third-party components
and optional models retain their own licenses. In particular, the Sakura 7B
model is distributed separately under `CC-BY-NC-SA-4.0` and is restricted to
non-commercial use.

This is an independent community project and is not affiliated with Google,
the llama.cpp project, SakuraLLM, Xiaomi, or any visual-novel publisher.
