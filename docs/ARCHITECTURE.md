# Architecture

## Runtime components

### `FloatingTranslateService`

Owns the floating panel, capture loop, OCR scheduling, source/target language
state, translation backend selection, request cancellation, and rotation
recovery.

### `TextEngine`

Wraps ML Kit OCR. Japanese recognition may combine:

- a normal-scale pass;
- a 2x upscaled pass;
- a bottom-band contrast pass for small subtitles.

Near-duplicate OCR lines are merged with a Unicode code-point edit distance.
This addresses duplicate lines produced by overlapping full-frame and
lower-band scans. It does not filter text by meaning.

### `TranslationEngine`

Provides the immediate ML Kit translation draft. Local and cloud modes operate
as optional refinement backends.

### Local small engines

- `LocalLlmEngine`: Qwen2.5 1.5B LiteRT path.
- `LiteRtLlmEngine`: Qwen2.5 3B LiteRT path.

### `HeavyTranslationService`

Runs llama.cpp in a separate Android process. The service owns the 7B model,
context, KV cache, sampler, and cancellation state. The floating process
communicates through `Messenger`.

The process name uses the full package form rather than a leading-colon alias.
Some HyperOS package parsers reject the leading-colon form when this service is
declared.

### Native bridge

`llama_jni.cpp`:

- loads and unloads a GGUF model;
- creates a 2048-token context;
- supports configurable thread/batch settings;
- uses F16 KV cache by default;
- chunks prompt processing by batch capacity;
- caches the longest matching KV prompt prefix;
- rolls back generated tokens while preserving the prompt prefix;
- samples with top-k, top-p, temperature, and a fixed seed;
- checks an atomic cancellation flag during decoding.

## Translation routing

- `FAST`: ML Kit immediately.
- `BALANCED`: ML Kit draft plus Qwen2.5 1.5B refinement.
- `PRECISE`: ML Kit draft plus Qwen2.5 3B when available.
- `API`: user-configured OpenAI-compatible endpoint.
- `ULTRA`: direction-specific Sakura option, currently Japanese-to-Simplified-
  Chinese only.

The `日→中` strength is dynamically appended to the four base strengths only
when the selected source is Japanese or auto and the target is Chinese.

## Capture modes

### MediaProjection

Captures the display and crops the selected normalized region. Overlay overlap
is excluded from the OCR input.

### Accessibility compatibility mode

Uses `AccessibilityService.takeScreenshotOfWindow()` on Android 14+ when the
active target window can be resolved. This can exclude the accessibility
overlay from the captured image. Older or unsupported devices fall back to a
full screenshot path with overlay-region exclusion.

## Model isolation

Entering the Sakura mode:

1. invalidates queued local refinements;
2. releases 1.5B and 3B engines;
3. binds the heavy process;
4. loads Sakura;
5. applies one final result only if its generation token is still current.

Leaving the mode cancels inference, unloads the native model, and unbinds the
heavy service.
