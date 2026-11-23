# Maintenance notes

This file records the important invariants and failure modes for future
maintenance.

## Build and toolchain

- Android compile/target SDK: 34.
- Minimum SDK: 26.
- Java source/target: 17.
- JDK for Gradle: 21 because current LiteRT-LM artifacts contain Java 21 bytecode.
- Android NDK: `26.3.11579264`.
- CMake: `3.29.2`.
- Gradle: 8.7.
- Native ABI: `arm64-v8a`.

Do not commit `local.properties`, absolute SDK/NDK paths, signing material,
model files, or generated APKs.

## Interface languages

- Supported UI languages: `zh-CN`, `zh-TW`, `en`, and `ru`.
- First launch is blocked by `LanguageSetupActivity` until a language is chosen.
- The home screen exposes a language-change button.
- Static labels live in `res/values*/strings.xml`.
- Dynamic status strings use `assets/ui_text.json`.
- The selected locale never changes the OCR/translation language catalog.
- Translation output must not be passed through the UI localization translator.

## Attribution

- Visible application attribution is in `@string/project_credit`.
- Human author: `oen1day`.
- AI collaborator: `OpenAI Codex (GPT-5)`.
- Hidden/package-readable attribution is in
  `res/raw/credits.json` and application `<meta-data>`.
- Keep `CREDITS.md`, README attribution, and the visible app line in sync.

## OCR invariants

- Crop strictly to the selected rectangle.
- Cache real accessibility window bounds, not empty rectangles.
- Keep capture coordinates normalized to the selected source window.
- Do not classify or remove text based on story content.
- Deduplicate only geometrically or textually near-identical OCR output.
- Japanese subtitle recovery may use upscaled and contrast passes.

## Translation invariants

- Never silently label a generic model result as Sakura output.
- Sakura is Japanese-to-Simplified-Chinese only.
- The `日→中` strength must disappear when the target is not Chinese or the
  explicit source is not Japanese.
- Switching away from an unavailable direction must downgrade to PRECISE and
  persist that state.
- Cancellation uses generation tokens. A stale callback must not update the
  panel or start another model.
- Local and heavy engines are mutually exclusive.

## Native inference invariants

- Release the previous model before loading a new one.
- Keep the model/context behind a mutex for load, generate, and unload.
- Cancellation and abort callbacks must remain lock-free.
- Reuse only the matching token prefix in the KV cache.
- On error or cancellation, clear the KV cache and invalidate the cached token
  list.
- Remove generated tokens after successful generation while preserving the
  prompt prefix.
- Validate prompt tokens against the configured context before decoding.

## HyperOS and vendor quirks

- A service process declared as `:heavy-llm` caused a package-parser install
  failure on a tested HyperOS build. Use the full package name.
- Accessibility screenshots and overlays can survive one rotation and fail on
  another depending on the OEM policy.
- The watchdog rebuilds a missing accessibility overlay but must not rebuild
  during normal rotation if the existing view remains attached.
- `uiautomator dump` temporarily registers its own automation service and can
  disturb accessibility service bindings during rotation tests.
- Do not interpret an automation-induced accessibility disconnect as a normal
  user-device failure.

## OCR duplicate rule

The current near-duplicate check applies only when merging full-frame and lower
band OCR:

- ignore normalized punctuation/whitespace;
- short lines require an edit distance of at most 1;
- lines of 20 or more code points allow at most 2 edits;
- length difference must be at most 2;
- lines shorter than 10 code points are not fuzzy-merged.

This fixed repeated final subtitles without adding semantic filtering.

## Performance baseline

Test with the same fixed Japanese text and record:

- load time;
- prompt token count;
- reused prompt tokens;
- prompt time;
- generated token count;
- generation time;
- process RSS.

Current tested configuration:

- six threads;
- 2048 context;
- batch 2048;
- ubatch 512;
- F16 KV;
- no weight repacking;
- KV prompt-prefix cache enabled.

Optimized ARMv8.6 builds use dot-product and int8 matrix instructions. Keep the
generic ARM64 build for devices that may not support those instructions.

## Versioning

Use semantic-ish prerelease versions while the UI and vendor compatibility are
still changing. Update `versionCode` and `versionName` together. Record
native-model format changes and model license changes in `CHANGELOG.md`.

## Before release

1. Build generic and optimized variants.
2. Run lint.
3. Install on at least one physical Android device.
4. Test fresh model download, checksum, load, unload, and low-memory failure.
5. Test the dynamic `日→中` option in both supported and unsupported directions.
6. Test OCR duplicate merging with overlapping full/band scans.
7. Verify no source file contains a local path, key, serial, or private screen
   sample.
