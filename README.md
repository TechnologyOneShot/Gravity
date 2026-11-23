# GRAVITY

**Gravity** is an autonomous AI development environment for Android, written in Kotlin and Jetpack Compose. It combines a project workspace, a code editor, a real device shell, local (llama.cpp) and cloud (Gemini / OpenAI-compatible) models, a tool-using coding agent, multi-agent teams, builds, same-device install and testing, screenshots, backups and exports.

> **Status: source complete, not yet compiled.** The project was written in a sandbox that could not reach Google Maven, Maven Central or the Android SDK, so **it has not been compiled, and no APK has been produced or run on a device yet.** The included GitHub Actions workflow builds the debug APK and runs the JVM unit tests. Expect a first round of compile fixes. See [docs/ACCEPTANCE_TESTS.md](docs/ACCEPTANCE_TESTS.md) for what has and hasn't been verified.

## Build

| Route | Command |
|---|---|
| Android Studio (Otter or newer) | Open the folder → Run `app` (debug) |
| Command line (JDK 17+, Android SDK 36) | `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| GitHub Actions | Push to GitHub → Actions → *Android CI* → artifact `gravity-debug-apk` |
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
| Chats (history, search, pin/archive/duplicate, per-chat model/reasoning/project/grounding, streaming, stop/regenerate/continue, text/image/PDF/ZIP attachments, voice input) | Implemented |
| Build Center: static web/game builds; project build commands with "exit 0 **and** fresh artifact" success rule; **Termux companion backend** for same-phone APK builds; build history and logs; on-device toolchain feasibility probes | Implemented. APK builds on the phone need Termux with a JDK, Gradle, aapt2 and an SDK; see [docs/FEASIBILITY.md](docs/FEASIBILITY.md). |
| Same-device testing: PackageInstaller install, launch, uninstall, app list, logcat with crash detection, MediaProjection screenshots, app test runs with recorded evidence, AI visual analysis, pixel-diff comparison | Implemented. No adb, instrumentation or input injection. |
| Browser preview (WebViewAssetLoader-served project previews, console capture) | Implemented |
| Activity log, notifications, export center, backup/restore (optional encryption), trash, version restore, self-test, Offline Readiness Checker | Implemented |
| Settings (searchable, 30 categories) | Implemented |
| Git, plugin runtime, Shizuku/Wireless-Debugging bridge, Gradle inside Gravity's own sandbox | **Not in this build.** Shown in-app with the reason. See [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md). |

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
