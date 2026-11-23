# Known limitations

The specification asks Gravity never to fake a feature. This file lists everything that is partial or absent, by spec section. The app shows the same information where it applies.

## Verification

- **Verified on an emulator, not yet on a phone.** Every push runs the JVM unit tests and an instrumented suite on an Android 14 emulator (both the standard APK and the llama.cpp APK). See ACCEPTANCE_TESTS.md for each row's evidence. Real-device behaviour (performance, OEM restrictions, ARM-only issues) has not been checked yet.
- Not verifiable in CI and therefore unverified: anything that needs an AI provider key (cloud chat, agent runs, teams, web search, screenshot analysis), Termux builds, the system install dialog and MediaProjection capture.
- 15 Kotlin deprecation warnings (non-AutoMirrored icons, `LocalClipboardManager`) remain. They do not affect the build.
- Acceptance tests 1–40 have **not** been executed on a device (see ACCEPTANCE_TESTS.md).

## By area

| Spec § | Item | Status |
|---|---|---|
| 5, 7 | Agent / hybrid teams | Implemented. Parallelism is task-level, plus parallel reviewers; a single developer edits at a time within one task. |
| 6 | Reasoning modes | Mapped to real parameters. Older Gemini models get no thinking config, and OpenAI-compatible endpoints get `reasoning_effort` only when enabled per provider. |
| 8 | Provider "discover models" | Uses the `/models` endpoints. Approximate cost uses prices you enter, because no pricing table is built in. |
| 9 | Local inference | Requires an APK built with `-Pgravity.llama=true` and a llama.cpp checkout at the tag the bridge was written for (`b6000`, or adjust). The default APK can download, verify and inspect models but cannot run them. No GPU offload (`n_gpu_layers = 0`). |
| 9 | Model catalogue | No hard-coded catalogue: models come from a live Hugging Face Hub search. The license comes from Hub tags or GGUF metadata, and you must still read the model card. |
| 11 | PDF attachments | Android has no text extractor, so the first 3 pages are sent as images (vision models only). |
| 11, 30 | Voice | Uses the system speech recognizer, which may use the network. There is no bundled offline speech model. |
| 12 | SAF folders | Copied into app storage (the terminal and agent need real paths), with no live two-way sync. "Copy to folder" exports back. |
| 14 | Editor | Regex lexer highlighting, not a parser. No language server; diagnostics are JSON/XML parse errors and bracket balance only. Formatting is JSON pretty-print and brace re-indent. Highlighting turns off above 200 k characters. |
| 15 | Terminal | Android `sh` (toybox/mksh) with app permissions, so no TTY, Ctrl-C or job control. **Stop restarts the shell.** Not CMD or PowerShell. Prompts without a trailing newline appear once a newline arrives. |
| 16 | Web search | Brave Search API (your key) or Gemini Google Search grounding. No other providers yet. |
| 17–19 | Offline readiness | Implemented: the Offline Readiness screen (Dashboard, Settings, Help) checks models, runtime, memory, storage, toolchains (optionally inside Termux) and project caches, and opens automatically when you switch to Local only. It cannot see Gradle caches outside Gravity and Termux. |
| 20 | SDK / dependency manager | **Not implemented.** Toolchains cannot run from app storage (see FEASIBILITY.md). Settings → SDK Manager explains this. |
| 21 | Android APK/AAB builds on-device | Possible through the **Termux companion** backend once Termux has a JDK, Gradle, aapt2 and an arm64 Android SDK (user-provisioned). Gravity's own shell cannot run them and reports the real failure. A `bundleDebug` command works through Termux too; the Export Center only exports APK/web artifacts. Static web builds work everywhere. |
| 22, 23 | ADB, instrumentation, UI Automator, input injection, force-stop | **Not implemented**: these need adb/Shizuku privileges. Install, launch, uninstall (system UI), logcat, crash detection and screenshots work. |
| 24 | Visual inspection | AI analysis of real screenshots plus pixel diff. No automatic element-level layout detection. |
| 26 | Responsive preview | A "Wide" (desktop viewport) toggle only. |
| 27 | Git | **Not implemented** (no git binary, and JGit is not bundled). Checkpoints provide per-file history, diff and rollback in the meantime. |
| 28 | Project memory | `GRAVITY.md` is read by every agent; chats and task history persist. There is no semantic or symbol index. |
| 31 | Performance | Thread and context limits from the performance mode, thermal state and low-memory mode. No benchmarking claims, except the measured tokens/s of a loaded model. |
| 32 | Prompt-injection protection | System-prompt rules plus treating tool and web output as data, which reduces but cannot eliminate the risk. Approvals gate destructive actions. |
| 32 | Dependency security checks | Not implemented. |
| 33 | Plugin system | **Not implemented.** The screen describes the planned bound-service design and lists the built-in tools and their permissions. |
| 34 | Backups | Exclude API keys (Keystore-bound) and model files (size). |
| 37 | Landscape layouts | Screens reflow but have no dedicated two-pane landscape layout. |
| 38 | Migrations | Schema v1. Future versions must add migrations. |
| — | Cleartext HTTP | Allowed app-wide for LAN model servers. The provider screen warns about public `http://` hosts. |
