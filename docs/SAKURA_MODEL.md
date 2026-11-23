# Sakura model details

## Model

- Name: Sakura 7B Qwen2.5 v1.0
- Quantization: IQ4_XS GGUF
- File: `sakura-7b-qwen2.5-v1.0-iq4xs.gguf`
- Size: 4,250,298,208 bytes
- SHA-256:
  `30e82ca13bc1e321f4f7b77459d533d483d46200522dc5c31eef9d2f2a6335d6`
- Source:
  <https://huggingface.co/SakuraLLM/Sakura-7B-Qwen2.5-v1.0-GGUF>
- License: CC-BY-NC-SA-4.0

The model is not redistributed by this repository or the APK.

## Supported direction

This checkpoint is trained for Japanese literary text to Simplified Chinese.
It is not a bidirectional Chinese/Japanese model.

The app therefore adds the `日→中` strength only when:

- source is Japanese, or source is auto detection; and
- target is Simplified Chinese.

## Memory and isolation

- Separate process: `io.github.oen1day.floatingtranslator.heavy`
- Native backend: llama.cpp
- ABI: ARM64
- Default threads: 6
- Context: 2048 tokens
- F16 KV cache
- Model weights are memory-mapped
- KV prefix cache is retained while the model process remains loaded

## Speed observations

On a tested ARM64 Android device:

- first request with a short system prompt: roughly 14–20 seconds;
- repeated identical prompt with KV reuse: roughly 5 seconds;
- changed subtitle with a reused system prefix: roughly 6–12 seconds.

These figures are hardware- and thermal-dependent, not guarantees.

## Quality boundaries

- Better literary phrasing than the bundled small models for Japanese.
- Still a quantized local model, not equivalent to human translation.
- Names may be transliterated inconsistently.
- Tight boundary conditions and specialized vocabulary still cause errors.
