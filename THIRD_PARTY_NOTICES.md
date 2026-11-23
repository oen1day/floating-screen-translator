# Third-party notices

This project depends on or interoperates with the following third-party
software and models. The repository's MIT license applies only to original
project code.

## llama.cpp / ggml

- Project: <https://github.com/ggml-org/llama.cpp>
- Vendored as a Git submodule at `third_party/llama.cpp`
- License: MIT
- Copyright: llama.cpp and ggml contributors

The Android JNI wrapper in `app/src/main/cpp` is original project code and
links against this dependency.

## Google ML Kit

- OCR: ML Kit Text Recognition v2
- Translation: ML Kit On-Device Translation
- Project: <https://developers.google.com/ml-kit>
- Terms: Google APIs Terms of Service and ML Kit terms

ML Kit may download language/OCR models through Google Play services or bundled
model components. Those components are not relicensed by this project.

## MediaPipe and LiteRT-LM

- Project: <https://github.com/google-ai-edge/mediapipe>
- Artifact: `com.google.mediapipe:tasks-genai`
- Artifact: `com.google.ai.edge.litertlm:litertlm-android`
- License: Apache License 2.0 unless otherwise stated by the upstream artifact

## AndroidX / Android platform libraries

- Project: <https://developer.android.com/jetpack/androidx>
- License: Apache License 2.0 unless otherwise stated upstream

## Local model files

Model files are downloaded separately and are not part of this repository or
the source distribution.

### Qwen2.5 1.5B LiteRT

- Source:
  <https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct>
- License: see the upstream model card

### Qwen2.5 3B LiteRT

- Source:
  <https://huggingface.co/mlboydaisuke/Qwen2.5-3B-Instruct-LiteRT>
- License: see the upstream model card

### Sakura 7B Qwen2.5 v1.0

- Source:
  <https://huggingface.co/SakuraLLM/Sakura-7B-Qwen2.5-v1.0-GGUF>
- License: CC-BY-NC-SA-4.0
- Restriction: non-commercial use only
- Model authors: SakuraLLM and upstream model authors

The application does not redistribute the Sakura GGUF. Users download it
directly from the upstream publisher after reviewing the model license.

## Android NDK / libc++

- Project: <https://developer.android.com/ndk>
- libc++ is distributed under the Apache License 2.0 with LLVM exceptions.
