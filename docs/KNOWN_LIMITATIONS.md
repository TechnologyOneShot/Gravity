# Known limitations

The specification asks Gravity never to fake a feature. This file lists everything that is partial or absent, by spec section. The app shows the same information where it applies.

## Verification

- **App Builder makes web apps.** Generated apps are HTML/CSS/JavaScript in a WebView (inside the APK). That covers music players, to-do lists, games, trackers and similar apps, but not features that need native Android APIs the WebView does not expose (background playback with the screen off, notifications, widgets, Bluetooth, contacts). How good an app is depends on the AI model: Gemini's current Flash model writes complete apps. On-device models are asked for the whole app as one HTML file in a single reply (they cannot follow the multi-step tool protocol); Gravity then opens the app off-screen, taps every button and gives the model one chance to fix the JavaScript errors it finds. Measured in CI (Qwen2.5-Coder, Q4_K_M, 2 runs each of a calculator, a music player and a to-do list): 1.5B made 3–4 of 6 working apps, 3B 4 of 6 (its calculators were wrong), 7B 6 of 6. Gemini (cloud) uses the same single-file path but could not be tested in CI (no key there). The test catches errors, not wrong logic (e.g. a calculator without a + button), and a 7B model needs a phone with about 12 GB of RAM. A run that writes no complete page is reported as failed, never as built, and Install refuses an app that still shows the starter page. The phone's context size limits how long that one file can be (Settings → Local AI).
- **Generated APKs are signed with a key kept in this phone's Android Keystore.** Rebuilt apps install as updates. If Gravity is uninstalled the key is lost, and apps it made must be uninstalled before a new build can be installed. Installing needs Android's "Install unknown apps" permission for Gravity; Android asks the first time.
- **App Builder's AI step is only tested with a scripted model in CI** (no API key is stored there). The scripted model exercises the real HTTP/SSE path, the agent and every step after it, but the quality of real model output is not tested automatically.
- **Wrong phone-model answers on one real phone are not explained yet.** On that phone the standard engine answered the two check questions wrongly in every load mode (file mapped, read into memory, 1 thread), so mmap and threads are ruled out. The same arm64 library answers correctly under Android's ARM translation (CI job `arm64-apk-translated`, the APK's own .so with a real Qwen model), under qemu, and on the x86_64 emulator. Changes made without being able to test on that phone: native heap pointer tagging is turned off (a real-ARM-only difference), and a second engine build (Armv8.2 dot product + fp16, different CPU kernels; checked under qemu) is tried automatically on CPUs that report those features. Phone Models → Check the phone AI now reports the phone, CPU features, engines, file checksums and every load attempt, so the next report shows where it fails. Not yet confirmed on the reporting phone.
- **Gemini's free tier has small daily limits** (e.g. 20 requests a day for the newest Flash model on some keys). Gravity switches to the key's other Gemini models when one is used up, and to the phone model when all are.
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

## Files Gravity makes, voice and PDFs (added 2026-11-25)

- **Pictures: three sources, each with limits** (Settings → AI → Pictures and videos). *Online* (Pollinations.ai, Flux): realistic, needs internet, the description is sent there, one picture per 45 s without an account, a small watermark, and the free service sometimes times out. *On this phone* (Stable Diffusion 1.5, Realistic Vision 5.1 + LCM, 4 steps): realistic and offline, but a 2.2 GB download, about 2.5 GB of memory while it works, and about 1.3 min (384 px) to 2.4 min (512 px) a picture on a 4-core Arm CPU in CI; slower phones take longer, and phones without Armv8.2 dot product cannot run it. *Code drawing*: the chat's AI draws simple SVG pictures (about 2 in 3 recognisable with Gemma 3 4B in tests).
- **Audio is the phone's text-to-speech voice reading the reply** (WAV). It is not music or a cloned voice. How clear it sounds depends on the voices installed on the phone (Android Settings → Text-to-speech); Gravity picks the clearest installed voice for Hindi or English and lets you choose another in live mode.
- **PDF reading:** text PDFs are read from their text layer; scanned PDFs and Hindi typed in old fonts (Kruti Dev etc.) are read with on-device OCR (ML Kit, Devanagari + Latin), up to 60 pages. OCR can misread small, faint or handwritten text. A phone model gets as much of the text as fits its memory (context) and is told when the rest was left out.
- **Live mode speech recognition** is the phone's own service (usually Google). It may need the internet unless the offline speech pack for the language is installed.

## Natural voice, speed and closes (added 2026-11-26)

- **Natural voice**: Piper voices are clear but not as expressive as cloud voices; English words inside a Hindi sentence are read by the Hindi voice. Hinglish words that are not in the 42,000-word list are read as English letters. Voice data licence: CC BY-NC-SA 4.0 (non-commercial). Only arm64 phones and the x86_64 emulator have the engine.
- **Speed**: a phone model writes as fast as the phone's memory allows (Qwen2.5 7B about 7 tokens/s, Gemma 3 4B about 11 on an Arm Neoverse-N2 with 4 threads; phones are similar or slower). The saved prompt helps the first reply after a start, not the writing speed. For the quickest replies use Gemma 3 4B (or smaller) or Gemini.
- **The i8mm engine** (Armv8.6, prompts ~30% faster in CI) is not built by default: after a saved prompt its next-word scores differed from reading everything (Gemma 3 4B, max difference 2.9; the standard and fast engines give 0). Build with `-Pgravity.i8mmEngine=true` to try it.
- **Closes**: a page's web engine dying no longer closes Yamini (preview, app test, browser). If Android still closes Yamini (for memory), the next start says so and keeps the report until OK is tapped. Why HyperOS/MIUI close an app is not always given by Android.

## Feelings in the voice, online voice, speed test (added 2026-11-26)

- **What a phone voice can and cannot do**: Android's text-to-speech lets an app set speed, pitch and loudness per sentence and add silence or a short sound between sentences. Moods are made of exactly that. Yamini cannot make the phone voice really cry, whisper or laugh: "sad" is slower, lower and softer; "whisper" is very soft and slow; a laugh is spoken "हा हा" where the reply laughs. The breath sound (Expressive mode) is a sound file added by the app between sentences, not a breath in the voice. Each mood's settings and limits are listed in Settings → Voice → "Hear each mood".
- **Mood detection** is a word and emoji list (Hindi, Hinglish, English) plus what the user said; it does not understand sarcasm or context the way a person does, and stays neutral when unsure. A mood can be fixed by hand.
- **Hindi and English in one sentence** ("अरे वाह, that's amazing!") are read by the Hindi voice; a whole English sentence is read by the English voice. How English words sound inside a Hindi sentence depends on the phone's Hindi voice.
- **Online voice (Gemini)**: needs the user's own Gemini key and internet, and sends the reply's text to Google while switched on. It was not tested against Google's servers in CI (no key there); the request and answer formats are unit tested, and any failure falls back to the phone voice. Gemini's audio is watermarked (SynthID) by Google. Live mode uses the phone voice.
- **Speed test**: each run is one measurement; a warm phone runs slower, so results can differ a little between runs. Only a setup at least 5% faster than the usual one is kept. The previous voice changes (phrase-by-phrase speed changes and the offline voice as default) were undone after users found them worse.

## Building an app from a design picture (added 2026-11-27)

- **Phone models cannot see pictures.** Yamini measures the picture on the phone instead (ML Kit reads the texts; DesignReader measures the background and its gradient, photos, cards and buttons, outlined fields, bars, icons, and each text's size, color and weight) and gives the phone model that description. It is a measurement, not sight: icon names are guesses from the shape ("looks like ⏸"), only common shapes are named (play, pause, next, previous, ⋯, ☰), a photo becomes a box of its colors (the app cannot copy the photo), and texts the OCR misses are missing. The result is close to the design, not exact.
- **Gemini sees the picture itself.** When a design picture would go to the phone model and a Gemini key is set, Yamini asks first; choosing Gemini sends the picture and the request to Google.
- **Browser and phone bars** in a screenshot (an address like music.apple.com, the clock and battery) are left out; words printed on a photo (an album cover) belong to the photo and are not written into the app.
- **Installed apps** keep their page below the status bar and above the navigation bar (Android 15 draws apps under them), and the bars take the page's background color; a page whose background is a picture gets the picture's first color or white.

