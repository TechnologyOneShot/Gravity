# Offline setup and local models

## 1. Build an APK with the local runtime

The default APK does not include llama.cpp. Build one that does:

```bash
git clone --depth 1 --branch b6000 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
./gradlew assembleDebug -Pgravity.llama=true
```

CI does this on every push to `main` (job *llama-apk-and-emulator-tests*): it builds the runtime for arm64-v8a and x86_64, runs the emulator suite with it, and attaches the result to the `debug-latest` pre-release as `gravity-debug-llama.apk`. Pick that file for a phone.

Notes:
- The JNI bridge (`app/src/main/cpp/gravity_llama.cpp`) targets the 2025 llama.cpp C API: `llama_model_load_from_file`, `llama_init_from_model`, `llama_vocab`, sampler chains and `llama_chat_apply_template` without a model argument. If the tag you pick renamed a function, adjust the bridge or choose a nearby tag.
- The build targets arm64-v8a and x86_64, CPU only. It needs the NDK (27.x) and CMake 3.22.1 from the SDK manager.
- Without it, Model Manager still downloads, verifies and inspects models and shows "Local runtime not bundled".

## 2. Get a model

**Model Manager → Search Hub** searches GGUF repositories on the Hugging Face Hub. Opening a repository lists `.gguf` files with their real size and LFS SHA-256. **Download** queues a resumable download. Gravity checks free storage first, verifies the SHA-256 when the download finishes, and parses the GGUF header (architecture, quantization, context length, chat template) before marking the model READY.

Realistic phone choices are 0.5B–3B parameter models at Q4_K_M. Check each model card's license: models in the same family (Qwen, Gemma, …) can have different terms. Gated models need a Hugging Face token (Settings → Model Download).

You can also **Import GGUF** to copy a file you already have. Gravity hashes and parses it the same way.

## 3. Load and use

**Installed → ⋮ → Load.** If the estimated RAM exceeds free memory, Gravity asks for confirmation first. **Benchmark** measures real tokens/s. Then:
- set the operating mode to **Local only** (or Hybrid with offline fallback);
- choose *Local model* as the provider in a chat, an agent task or a team member.

Local models call tools through a text protocol (fenced ```tool JSON blocks). Smaller models follow it less reliably than cloud models.

## 4. What works offline

Local chat and agents, code generation, all workspace, file, editor and terminal features, project search, checkpoints, static web builds and previews, installing APKs, logcat, screenshots, app tests, local backups and exports.

Not available offline: cloud models, web search, Hub search and downloads, dependency downloads. Cloud tasks wait in **WAITING_NETWORK** and resume automatically when the connection returns (Settings → Hybrid Intelligence).
