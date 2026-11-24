# Acceptance tests (spec §42)

**Status (2026-11-23):** verified on an Android 14 emulator (Google APIs, x86_64) in GitHub Actions by the instrumented suite in `app/src/androidTest` (job *emulator-tests*, plus *llama-apk-and-emulator-tests* for the native runtime). Each result below cites the test that produced it; the CI log prints what the app did and showed, and the run's `emulator-evidence` artifact holds 32 screenshots, logcat and the test XML.

What an emulator run cannot prove: behaviour on real phone hardware, anything that needs an AI provider key (none is stored in CI), Termux, Shizuku, the system install dialog, or MediaProjection consent. Those rows say so.

Result key: **Pass** = verified with evidence · **Partial** = the verifiable part passed, the rest is named · **Not run** = needs something CI does not have.

How results get recorded on a phone:
- **Self-test**: Testing Center or Help → *Run self-test* (name, expected, actual, PASS/FAIL/SKIPPED, details). Export Center → *Test report* exports them.
- **Feasibility**: Build Center → probes, suite `feasibility`.
- **App test**: Testing Center → *Run test* (suite `device:<package>`, with screenshot and log evidence).

| # | Test | Evidence | Result |
|---|---|---|---|
| 1 | App launches | `UiNavigationTest.t01` | **Pass** |
| 2 | Dashboard displays | `UiNavigationTest.t01`: status panel (network, mode, local/cloud AI, builds), quick actions, recent items | **Pass** |
| 3 | Navigation works | `UiNavigationTest.t02`: all 28 drawer entries opened with the right title, no crash | **Pass** (28/28) |
| 4 | New Chat works | `UiNavigationTest.t03`: chat created, message saved, no provider → visible error and an empty assistant row (no invented reply) | **Partial**: a real AI reply needs a provider key (row 18) |
| 5 | Chat history persists | `UiNavigationTest.t03`: chat still listed after the activity is recreated | **Partial**: survives activity recreation; not tested across a force-stop |
| 6 | New Project | `DeviceServicesTest.t03`: all 7 templates create their files and a database row | **Pass** |
| 7 | New Folder | `DeviceServicesTest.t03` | **Pass** |
| 8 | New File | Self-test + `DeviceServicesTest.t03` | **Pass** |
| 9 | File rename | Self-test + `DeviceServicesTest.t03` | **Pass** |
| 10 | File delete with safeguards | Trash round-trip (self-test, `t03`); `../escape.txt` rejected | **Pass** (service level; confirm dialog not exercised) |
| 11 | Workspace reopens | Workspaces screen lists every project from the database after restore and activity recreation (`t02` screens) | **Partial**: process restart not tested |
| 12 | Terminal runs real commands | `DeviceServicesTest.t02`: `id`, `uname -a`, `pwd` return the device's real output | **Pass** |
| 13 | Terminal exit codes | Self-test (`exit 0` / `exit 3`), `cat /nonexistent-file` → 1 | **Pass** |
| 14 | Local model downloads | `DeviceServicesTest.t12`: `ggml-org/models/tinyllamas/stories260K.gguf` from the Hugging Face Hub through WorkManager | **Pass** |
| 15 | Checksum verified | `t12`: SHA-256 equals the Hub's LFS hash; file size matches | **Pass** |
| 16 | Local model loads | `DeviceServicesTest.t12b` (APK with llama.cpp, x86_64): the downloaded `stories260K.gguf` loads with a 512-token context | **Pass** |
| 17 | Local model generates | `t12b`: 32 tokens in about 2 s; `t13`: with airplane mode on, the request is routed to the local model and generates 16 tokens | **Pass** (tiny test model; speed on a phone depends on the model and hardware) |
| 18 | Gemini works with a valid key | Self-test "Cloud provider connection" | **Not run**: no API key in CI |
| 19 | Custom API configuration | Provider protocol unit tests (MockWebServer) only | **Not run** on a device: needs an endpoint |
| 20 | Online/offline switching | `t13`: airplane mode → app reports offline → back online; a task without a provider waits in WAITING_NETWORK (`t09`) | **Partial**: automatic resume of a cloud task needs a provider |
| 21 | Hybrid collaboration task | | **Not run**: needs a cloud provider |
| 22 | Shared project context | | **Not run**: needs a provider |
| 23 | File changes reviewable | Diff engine in self-test only | **Not run** end to end: needs an agent run with a provider |
| 24 | Rollback restores content | Self-test "Checkpoint rollback" | **Pass** (checkpoint level; per-task rollback needs an agent run) |
| 25 | Web search returns sourced results | | **Not run**: needs a Brave key or Gemini |
| 26 | Offline project search | `t13`: project search in airplane mode | **Pass** |
| 27 | Cached offline build | | **Not run**: needs Termux |
| 28 | Android build generates an APK | CI builds Gravity's APK on every push; shell and static-web projects build on the device (`t05`) | **Partial**: an on-phone APK build needs Termux |
| 29 | Exported artifact exists | `t06`: exported ZIP read back (3 files); static-web artifact ZIP contains `index.html` (`t05`) | **Pass** |
| 30 | Same-device install | | **Not run**: system install dialog needs a person |
| 31 | Target app launches | `t11`: App test launched Settings; `dumpsys` shows it as the resumed activity | **Pass** |
| 32 | Runtime logs collected | `t11`: logcat read; other apps' lines need READ_LOGS (`adb shell pm grant`) | **Partial** |
| 33 | Real screenshots | CI captured 32 screenshots with `screencap`, not with the app's MediaProjection capture | **Not run** in-app: needs the user's capture consent |
| 34 | Screenshot analysis | | **Not run**: needs a vision provider and a capture |
| 35 | Genuine pass/fail | Healthy app → PASS (`t11`); Android build → FAILED with exit 127 (`t05`); no-provider chat and agent never report success (`t03`, `t09`) | **Partial**: the crashing-app FAIL path was not run |
| 36 | Accurate build failures | `t05`: Android template built with the plain device shell → FAILED, exit 127, real log `./gradlew: inaccessible or not found` | **Pass** |
| 37 | Backup and restore | `t07`: encrypted backup, wrong password rejected, restore adds the projects back | **Pass** |
| 38 | Credentials not exposed | `t07`: key stored encrypted (not in the prefs file in plain text); none of 48 backup entries contains it | **Pass** |
| 39 | Survives process interruption | `t08`: a task left RUNNING is marked INTERRUPTED by startup recovery | **Partial**: a real force-stop was not performed |
| 40 | No placeholder core features | Git Manager and Plugins state in the app that they are not implemented | **Not met**: see KNOWN_LIMITATIONS.md |

Counts for the latest run (commit bddbb9b, 2026-11-24): standard APK 22 passed and 2 skipped (both need the llama.cpp engine); llama.cpp APK 24/24 passed; App Builder end to end (build, change, undo, APK, install, launch, and the Home → Build → App screen UI flow) passed in both; no FATAL EXCEPTION in logcat; 53/53 JVM unit tests pass.

## App Builder (added after the original spec)

| Check | Evidence | Result |
|---|---|---|
| "music player bana do" → project "Music Player" → complete app written | `AppBuilderDeviceTest.t01` with a scripted model over real HTTP/SSE | Verified by CI on each push |
| Change request edits the app; Undo restores it | `t01`: `replace_in_file` adds a shuffle button, undo removes it | Verified by CI on each push |
| APK packaged and signed on the device | `t01`: Android parses the APK (package, label), `pm install` succeeds, the app launches and loads its page | Verified by CI on each push |
| Home → Build → App screen in the UI | `AppBuilderDeviceTest.t02` | Verified by CI on each push |
| Manifest rewriting | `AxmlRewriterTest` against the real shell APK | JVM unit test |
| Builds on the phone model never fake success | `DeviceServicesTest.t12c`: real llama.cpp run of App Builder with the CI model (stories260K, 2048 context). It replied with story text (1,617 tokens), so the task was marked FAILED with the reason, index.html kept the starter page and packaging was refused | Verified by CI on each push (llama.cpp APK) |
| A run that writes no files, or leaves the starter page, is not "completed" | `AgentEngine.complete()` checks; HTML extraction rules in `AppBuilderLocalPathTest` | JVM unit tests + `t12c` |
| Chat controls: model pill and picker sheet, + menu (files, photos), mic | `UiNavigationTest.t03a` opens the menu and the sheet (Auto, Cloud AI, On this phone, options) | Verified by CI on each push |
| Real AI output quality | | **Not tested automatically** (needs an API key, or a model too large for CI) |

Bugs the emulator suite found and that are fixed: Navigation 2.9.0 lifecycle crash when leaving a screen ("State must be at least CREATED to be moved to DESTROYED"); navigation started from a coroutine could leave the new screen uninitialised (navigation now always runs on the main thread); closing tabs crashed Material's tab indicator; Android 14 throws inside `ZipInputStream` for `../` entries, which made ZIP import fail instead of rejecting the entry; big-endian GGUF files were reported as "Unsupported GGUF version 50331648".

## JVM unit tests included

`PathGuardTest`, `ZipSafetyTest`, `LineDiffTest`, `CommandSafetyTest`, `SecretScannerTest`, `StreamTextTest`, `GgufReaderTest`, `BackupCryptoTest`, `GlobTest`, `TextToolProtocolTest`, `SyntaxHighlighterTest`, `ProviderProtocolTest`, `TermuxScriptTest`. `TermuxScriptTest` runs the real Termux build script with bash against a fake Gradle project, covering both success and failure exit codes. The last one replays Gemini and OpenAI streaming wire formats against MockWebServer and checks the exact request bodies.

Checked outside the Android build:
- the diff algorithm (Python port vs a brute-force LCS, 3,000 random cases);
- the terminal exit-code marker protocol (against a real POSIX shell);
- the Termux build script (with bash, including failing builds and paths with spaces);
- every llama.cpp function and signature the JNI bridge calls, against `llama.h` at tag b6000.
