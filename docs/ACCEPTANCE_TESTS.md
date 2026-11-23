# Acceptance tests (spec §42)

**Current status: none of these has been executed.** The code has not been compiled, because the authoring sandbox had no Android toolchain. Do not treat any row as passed until it has a recorded result.

How results get recorded:
- **JVM**: `./gradlew testDebugUnitTest`, run in CI. Reports are in `app/build/reports/tests/`.
- **Self-test**: Testing Center or Help → *Run self-test*. Records name, expected, actual, PASS/FAIL/SKIPPED and details on the device; Export Center → *Test report* exports them.
- **Feasibility**: Build Center → probes, recorded as suite `feasibility`.
- **App test**: Testing Center → *Run test* (suite `device:<package>`, with screenshot and log evidence).
- **Manual**: steps below. Note the result in the table, with a screenshot or log.

| # | Test | Verified by | Result |
|---|---|---|---|
| 1 | App launches | Manual: install, open | Not run |
| 2 | Dashboard displays | Manual | Not run |
| 3 | Navigation works | Manual: open all 28 drawer entries | Not run |
| 4 | New Chat works | Manual | Not run |
| 5 | Chat history persists | Manual: send, force-stop, reopen | Not run |
| 6 | New Project | Manual: each template | Not run |
| 7 | New Folder | Manual | Not run |
| 8 | New File | Self-test "File create / rename / delete / restore" + manual | Not run |
| 9 | File rename | Self-test (same) | Not run |
| 10 | File delete with safeguards | Self-test (trash round-trip) + manual confirm dialog | Not run |
| 11 | Workspace reopens | Manual: restart app, open project | Not run |
| 12 | Terminal runs real commands | Self-test "Terminal exit codes" | Not run |
| 13 | Terminal exit codes | Self-test (`exit 0` / `exit 3`) | Not run |
| 14 | Local model downloads | Manual: Hub search → Download | Not run |
| 15 | Checksum verified | Manual: Hub file with SHA-256; see "Downloaded and verified" event | Not run |
| 16 | Local model loads | Manual (APK with llama runtime) | Not run |
| 17 | Local model generates | Self-test "Local model inference" | Not run |
| 18 | Gemini works with a valid key | Self-test "Cloud provider connection" + a chat message | Not run |
| 19 | Custom API configuration | Manual: OpenAI-compatible provider → Test connection | Not run |
| 20 | Online/offline switching | Manual: airplane mode during a cloud task → WAITING_NETWORK → resumes | Not run |
| 21 | Hybrid collaboration task | Manual: team (cloud Architect + local Developer) task completes | Not run |
| 22 | Shared project context | Manual: plan visible in task Result; developer prompt includes it | Not run |
| 23 | File changes reviewable | Manual: task → Changes → diff | Not run |
| 24 | Rollback restores content | Self-test "Checkpoint rollback" + task Roll back | Not run |
| 25 | Web search returns sourced results | Manual: Brave key → agent `web_search`, or Gemini grounding sources | Not run |
| 26 | Offline project search | Manual: airplane mode → Files search / Global Search | Not run |
| 27 | Cached offline build | Manual: Termux backend, second build with `--offline` after one online build; Offline Readiness shows the Termux cache | Not run |
| 28 | Android build generates an APK | CI (`assembleDebug`) for Gravity itself; on the phone with the Termux backend (Android template project) | Not run |
| 29 | Exported artifact exists | Export Center reports the size it read back | Not run |
| 30 | Same-device install | Manual: Device Manager → Install | Not run |
| 31 | Target app launches | App test | Not run |
| 32 | Runtime logs collected | App test details / Logcat tab | Not run |
| 33 | Real screenshots | Testing Center capture → Gallery | Not run |
| 34 | Screenshot analysis | Gallery → AI analyse | Not run |
| 35 | Genuine pass/fail | App test with a crashing app records FAIL | Not run |
| 36 | Accurate build failures | Build an Android project on a stock phone: FAILED with the real `java: not found` log | Not run |
| 37 | Backup and restore | Self-test "Backup encryption" + manual backup → restore | Not run |
| 38 | Credentials not exposed | Self-test "Keystore credential encryption"; check that exports and backups contain no keys | Not run |
| 39 | Survives process interruption | Manual: start a task, force-stop, reopen → INTERRUPTED → Resume | Not run |
| 40 | No placeholder core features | Review against KNOWN_LIMITATIONS.md | Not run |

## JVM unit tests included

`PathGuardTest`, `ZipSafetyTest`, `LineDiffTest`, `CommandSafetyTest`, `SecretScannerTest`, `StreamTextTest`, `GgufReaderTest`, `BackupCryptoTest`, `GlobTest`, `TextToolProtocolTest`, `SyntaxHighlighterTest`, `ProviderProtocolTest`, `TermuxScriptTest`. `TermuxScriptTest` runs the real Termux build script with bash against a fake Gradle project, covering both success and failure exit codes. The last one replays Gemini and OpenAI streaming wire formats against MockWebServer and checks the exact request bodies.

Checked outside the Android build:
- the diff algorithm (Python port vs a brute-force LCS, 3,000 random cases);
- the terminal exit-code marker protocol (against a real POSIX shell);
- the Termux build script (with bash, including failing builds and paths with spaces);
- every llama.cpp function and signature the JNI bridge calls, against `llama.h` at tag b6000.
