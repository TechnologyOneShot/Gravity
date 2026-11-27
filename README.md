# YAMINI

*(Formerly Gravity. The package name `com.gravity.ide` and this repository keep the old name, so new builds install over earlier ones.)*

**Yamini** is an autonomous AI development environment for Android, written in Kotlin and Jetpack Compose.

**Start here: App Builder.** The home screen asks "What do you want to build?". Type something like *"music player bana do"* and Yamini creates a project, has the AI write a complete mobile web app, and runs it on the same screen. Ask for changes in plain words ("add a shuffle button"), undo the last change, and tap **Install on phone** to get a real APK, packaged and signed on the phone itself (no PC, Java or Termux). Apps are HTML/CSS/JavaScript running in an Android WebView; native Kotlin apps still go through the Build Center and Termux. Yamini has no subscription and no limits of its own; cloud AI providers keep their own quotas (Gemini's free tier has daily limits).

Under the hood it combines a project workspace, a code editor, a real device shell, local (llama.cpp) and cloud (Gemini / OpenAI-compatible) models, a tool-using coding agent, multi-agent teams, builds, same-device install and testing, screenshots, backups and exports.

> **Status: builds and passes on an Android 14 emulator; first phone reports have been fixed.** Every push to `main` runs the JVM unit tests, then installs the APK on an emulator and drives the real app: App Builder end to end (with a scripted model: build, change, undo, APK packaging, install and launch of the generated app), every drawer screen, chat, self-test, projects, builds, backup/restore, Keystore, a real Hugging Face model download with SHA-256 check, and (in the llama.cpp APK) local generation online and in airplane mode. Features that need an AI provider key, Termux or user consent dialogs are not covered by CI. Details: [docs/ACCEPTANCE_TESTS.md](docs/ACCEPTANCE_TESTS.md).

## Build

| Route | Command |
|---|---|
| Android Studio (Otter or newer) | Open the folder → Run `app` (debug) |
| Command line (JDK 17+, Android SDK 36) | `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| GitHub Actions | Every push to `main` builds and tests the APK and attaches it to the **debug-latest** pre-release: `yamini-phone.apk` for phones (arm64 only, about half the size; also under the build's own name, e.g. `yamini-0.1.96-phone.apk`, which never changes while it downloads) and `yamini-debug.apk`/`gravity-debug.apk` (includes the on-device AI engine; `gravity-debug-llama.apk` is the same file under its old name). A build without the engine is kept as a run artifact. |
| With the on-device LLM runtime | Clone llama.cpp into `app/src/main/cpp/llama.cpp`, then `./gradlew assembleDebug -Pgravity.llama=true` (see [docs/OFFLINE_SETUP.md](docs/OFFLINE_SETUP.md)) |

Unit tests: `./gradlew testDebugUnitTest`. These are real tests of the core logic: path sandboxing, ZIP-slip protection, Myers diff, command risk classification, secret redaction, SSE and UTF-8 streaming, GGUF parsing, backup encryption, the syntax lexer, and the Gemini/OpenAI request and stream parsers, tested against a local HTTP server.

## What's in the box

| Area | State in this build |
|---|---|
| Dashboard, 28-entry navigation drawer, context menus | Implemented. Every entry opens a real screen. |
| Projects / workspaces / file explorer (create, rename, copy, move, delete to trash, ZIP import/export with zip-slip and zip-bomb limits, SAF folder import/export, search, live FileObserver sync) | Implemented |
| Code editor (tabs, highlighting for 17 languages, line numbers, find/replace, go-to-line, undo/redo, auto-indent, bracket matching, word completion, AI completion, JSON formatter, brace re-indent, JSON/XML/bracket diagnostics, diff, version history, auto-save) | Implemented. No language server. |
| Terminal (multiple tabs, real `/system/bin/sh`, real exit codes, cwd tracking, history, stdin to running programs, stop, risk warnings, AI command suggestion) | Implemented within Android's app sandbox |
| Cloud AI: Gemini REST (streaming, function calling, thought signatures, thinking budget/level, Google Search grounding, vision) and OpenAI-compatible (streaming, tools, `reasoning_effort`, vision) | Implemented |
| Provider manager (Keystore-encrypted keys, test connection, model discovery, priority/backup, usage, token limits, approximate cost) | Implemented |
| Local AI: model search on the Hugging Face Hub, resumable downloads with storage checks, SHA-256 verification, GGUF header parsing, import, load/unload, benchmark, RAM warnings | Implemented. Inference needs the optional llama.cpp build. |
| Autonomous agent (plan, then tool loop with 14 tools including `build_project`, `install_apk`, `test_app`; approvals, checkpoints, pause/resume/cancel/retry, persistence across process death, backup provider fallback, offline wait and auto-resume) | Implemented |
| Hybrid teams (Architect → Developer → tests → parallel Reviewer + Security reviewer → fixes; sequential local-model loading; per-change role and model attribution) | Implemented |
| Reasoning modes (Fast … Think Plus) mapped to real provider parameters | Implemented |
| **Feelings in the voice** (Settings → Voice, on by default): each reply's mood is found on the phone from its words, emoji and what the user said (22 moods: happy, sad, serious, caring, laughing, sleepy…; neutral by default; a mood can also be fixed by hand) and sets the voice's speed, pitch, loudness and pauses between whole sentences (sentences are never cut up). Three modes: Normal (everyday), Expressive (stronger, with a soft breath sound before some longer sentences) and Professional (calm and clear). Hindi and English sentences each get their own voice in one reply; laughter in a reply is spoken ("हा हा"); emoji are not read out. Live mode says a short "हम्म…" when an answer takes a moment. | Implemented |
| **Yamini's voice: female or male** (Settings → Voice): picks matching phone voices and the AI's Hindi grammar for itself, in chat and live mode ("मैं कर सकती हूँ" / "मैं कर सकता हूँ"). | Implemented |
| **Online expressive voice (Gemini, opt-in)**: reads replies with Gemini text-to-speech (gemini-3.8-flash-lite-tts, then 3.8-flash-tts, then 3.1-flash-tts-preview) using the user's own Gemini key, with the mood as a style and real laugh tags; the first part plays while the rest is made. No key, no internet or an error: the phone's voice reads it (automatic fallback). Live mode keeps the phone's voice (it starts at once). Not tested against Google in CI (no key there): the request and answer formats are unit tested. | Implemented, untested with a real key |
| **Speed test for this phone** (Phone Models → Check the phone AI): measures reading and writing speed of the loaded model with the usual thread count and with more cores, keeps the fastest (only if at least 5% faster) and uses it at every load. Reading the prompt and writing now have separate thread counts. | Implemented |
| **Says what it is doing, live**: before a reply's first words the chat shows the step with a running time ("Loading the phone model…", "Reading your message and the chat so far · Qwen2.5 7B… 12 s", "Asking gemini-3-flash…", "Searching the web…"). While an app is built or changed, its card shows the file and part being written, read from the AI's own text ("Creating index.html · 142 lines · writing the styles (CSS)", "Modifying index.html · change 2 · writing the new lines") with the last lines of code, and every step as it happens (Reading / Created / Modified / Running …). | Implemented |
| **Live talk like a person**: Yamini reacts first ("arre wah!", "oh no"), asks a short question back now and then, uses what you said earlier, may share a small opinion or a light joke, and says honestly that it is an AI when asked. "💬 Kuch baat karo" (or saying "bore ho raha hun", "kuch baat karo") makes it bring up a topic itself: 346 topics in 26 areas (food, cricket, movies, family, school, travel, festivals, science, feelings, games, stories…), each started in one of 12 ways, never the same topic twice in a talk. It remembers what you say about yourself (name, place, likes, dislikes, studies/work) on the phone, shown and deletable in Settings → Voice. The AI itself can talk about anything; the list only gives it fresh starting points. | Implemented |
| **Stage directions are said, not written**: when a model writes "(A long pause…)", "(My voice is barely a whisper)", "*smiles*" or "(हँसते हुए)", the chat does not show it and the voice says the following words in that manner (sad, whispering, laughing, calm, after a pause). The prompt also asks for no stage directions and no claims of human feelings. Settings → Voice has a one-tap "Movie AI-butler voice" (butler style, male voice, British English, calm and formal); an actor's own voice is not copied. | Implemented |
| **Design pictures for the phone model**: a screenshot or mockup attached in App mode is measured on the phone (texts by ML Kit; background and gradient, photos, cards, buttons, fields, bars, icon shapes, text sizes, colors and weight by DesignReader) and the phone model builds from that description; browser/status bars and words on photos are left out. With a Gemini key, Yamini asks whether Gemini (sees the picture) should build it instead. Installed apps stay below the status bar. | Implemented |
| **Smoother screens**: replies on screen refresh about 20 times a second instead of for every word; terminal output arrives in 30 ms batches; pictures in chat, files, gallery and dashboard are decoded in the background at the size shown; folder sizes are counted in the background; reply text is parsed once per change. | Implemented |
| **Offline voice pack (optional, off by default)** (Settings → Voice): the phone's own voice (Google's) is the default because it sounds more natural; Piper neural voices (Hindi female/male, English female/male, ~67 MB each, SHA-256 checked) run on the phone by sherpa-onnx through Yamini's own Android voice engine: no internet, no account, no limits. Measured on an Arm CPU: the first sentence is ready in about 0.1–0.2 s. Hinglish replies are read in Devanagari by the Hindi voice (a 42,000-word Hinglish → Devanagari list), so "Main aapke liye" is said as Hindi, not as English letters. Voice data is CC BY-NC-SA 4.0 (free, non-commercial). | Implemented |
| Updates keep your data: About → **Update Yamini** downloads the latest build, checks its SHA-256, that it is newer and signed with the same key, and installs it over the installed app with Yamini's own installer (Android says exactly why if it refuses). Uninstalling asks whether to keep app data (`hasFragileUserData`), so downloaded models can survive a reinstall. | Implemented |
| Faster phone-model replies: what a model's warm-up read is saved and loaded at the next start. Measured in CI on an Arm Neoverse-N2 (4 threads): Qwen2.5 7B read the system prompt in 40 s, and with the saved prompt the first word of the first reply came 1.2 s after loading (Gemma 3 4B: 21–36 s → 0.6–1.1 s), with the same next-word scores as reading everything. Ordinary chat is answered without a thinking pass (Auto). An i8mm engine build (prompts ~30% faster) exists but is off: its scores did not match. Writing speed is limited by phone memory: about 7 tokens/s for a 7B model and 11 for Gemma 3 4B on that CPU, so Gemma 3 4B answers about 1.6× sooner. | Implemented |
| Chats (history, search, pin/archive/duplicate, per-chat model/reasoning/project/grounding, streaming, stop/regenerate/continue, text/image/PDF/ZIP attachments, voice input) | Implemented |
| Build Center: static web/game builds; project build commands with "exit 0 **and** fresh artifact" success rule; **Termux companion backend** for same-phone APK builds; build history and logs; on-device toolchain feasibility probes | Implemented. APK builds on the phone need Termux with a JDK, Gradle, aapt2 and an SDK; see [docs/FEASIBILITY.md](docs/FEASIBILITY.md). |
| Same-device testing: PackageInstaller install, launch, uninstall, app list, logcat with crash detection, MediaProjection screenshots, app test runs with recorded evidence, AI visual analysis, pixel-diff comparison | Implemented. No adb, instrumentation or input injection. |
| Browser preview (WebViewAssetLoader-served project previews, console capture) | Implemented |
| Activity log, notifications, export center, backup/restore (optional encryption), trash, version restore, self-test, Offline Readiness Checker | Implemented |
| Settings (searchable, 30 categories) | Implemented |
| Git, plugin runtime, Shizuku/Wireless-Debugging bridge, Gradle inside Yamini's own sandbox | **Not in this build.** Shown in-app with the reason. See [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md). |

## Documentation

- [Architecture](docs/ARCHITECTURE.md): layers, module map, dependency map, data model, key flows
- [Setup and API configuration](docs/SETUP.md)
- [Offline setup and local models](docs/OFFLINE_SETUP.md)
- [On-device build and same-device testing feasibility](docs/FEASIBILITY.md)
- [Known limitations](docs/KNOWN_LIMITATIONS.md)
- [Acceptance tests and verification status](docs/ACCEPTANCE_TESTS.md)

## Layout

```
app/src/main/java/com/gravity/ide/
  core/        pure Kotlin: PathGuard, ZipSafety, LineDiff, SyntaxHighlighter, GgufReader, BackupCrypto, ...
  data/        Room entities and DAOs, DataStore settings, event log
  security/    Android Keystore secret store
  workspace/   projects, templates, file operations, checkpoints
  terminal/    shell sessions
  ai/          provider interface, Gemini, OpenAI-compatible, routing, reasoning; ai/local = llama.cpp JNI
  agent/       agent engine, tools, approvals, teams, foreground service
  models/      Hub search, download worker, model manager
  build/       build engine
  device/      device inspector, feasibility probes, installer, logcat, performance limits
  capture/     MediaProjection capture, screenshot store, image diff
  testing/     self-test and app test runners
  backup/      backup and restore
  search/      web search and page fetch
  network/     connectivity monitor
  ui/          theme, components, navigation, screens
app/src/main/cpp/  optional llama.cpp bridge (gravity_llama.cpp, CMakeLists.txt)
app/src/test/      JVM unit tests
```
