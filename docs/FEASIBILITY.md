# On-device build and same-device testing: feasibility

This covers specification sections 20–23 and 40. Gravity measures this **on the device**: Build Center → *On-device toolchain feasibility* runs the probes below and saves each result to the Testing Center as evidence. This document explains what the probes check and what follows from them.

## What the probes check

| Probe | How | Why it matters |
|---|---|---|
| Shell execution | `sh -c "echo; id"` | Terminal and agent `run_command` |
| Exec from app storage | Writes a script to `filesDir`, marks it executable, then runs it directly | Android 10+ (targetSdk ≥ 29) blocks `execve` on files in app-writable storage (W^X), so a downloaded JDK, Gradle daemon, aapt2 or node binary cannot run from there |
| Native library dir | `applicationInfo.nativeLibraryDir` | Binaries packaged in the APK as `lib*.so` **can** run from here |
| JDK on PATH | `command -v java` | Gradle and AGP need a full JDK. ART (`dalvikvm`) is not one. |
| Termux / Shizuku installed | Package lookup | Companion paths (below) |
| USB / Wireless debugging | `Settings.Global` | Needed for Shizuku pairing |
| Install permission | `canRequestPackageInstalls()` | Same-device installs |
| READ_LOGS | Permission check | Other apps' logcat |
| Storage ≥ 8 GB, RAM ≥ 6 GB, arm64 | StatFs, ActivityManager, ABIs | Rough needs of SDK + Gradle cache + daemon |

## Conclusions for a stock Android 10+ device

1. **APK builds with Gradle/AGP cannot run inside Gravity's own sandbox.** The JDK, Gradle and build-tools are desktop binaries. Android does not let an app execute binaries it downloaded, and Google does not ship arm64 Android builds of most build-tools. Gravity therefore never shows a successful Android build unless the command really exited 0 and produced the APK.
2. **What does build on-device:** static web and HTML5 game projects (Build Center → static web build) and any command-line tool already present on the device.
3. **Same-device install, launch, logs and screenshots need no adb.** They work through PackageInstaller (the system confirms each install), launch intents, `logcat` (Gravity's own process only, unless READ_LOGS is granted once over adb), and MediaProjection (one consent per session).
4. **Not possible without adb-level privileges:** `am instrument` (instrumentation/UI Automator tests), `input` injection, force-stop, `pm grant`. An ordinary app cannot do these.

## Legitimate routes to a real APK from the phone

| Route | Status in this build | Notes |
|---|---|---|
| **Termux companion**: `pkg install openjdk-17 gradle aapt2`, AGP with `android.aapt2FromMavenOverride` pointing at Termux's aapt2, project exported to shared storage, `RUN_COMMAND` intent | **Implemented** (Build Center → backend *Termux companion*) | Gravity copies the sources to a shared folder you choose, Termux syncs them to `~/gravity-builds/<project>`, injects the aapt2/SDK settings, runs the build command, and returns its real stdout/stderr/exit code. Gravity then imports the APK and checks that Android can parse it. You must provide the arm64 Android SDK in Termux yourself. The build still happens on the same phone, inside a companion app. |
| **CI / remote build** (e.g. the GitHub Actions workflow in this repo) | Works today | Never labelled as an offline same-phone build. Install the resulting APK through Device Manager. |
| **Bundled toolchain as `lib*.so`** | Not attempted | Technically allowed (the native lib dir is executable), but it means a 300+ MB APK and maintaining arm64 JDK/aapt2 ports. |
| **Shizuku** (`newProcess` with shell UID after the user authorises it) | Detected, not bridged | Would add `am instrument`, `input` and force-stop. Requires adding the Shizuku API dependency and a consent flow. |
| **Wireless Debugging + built-in adb client** | Detected, not bridged | Requires implementing ADB pairing (SPAKE2) and the TLS transport. |

## Persisting state before self-reinstall

Gravity persists every agent task after each step, plus all builds, test results and settings, in Room and DataStore. If Gravity installs a new version of itself, in-flight tasks are marked INTERRUPTED on the next start and can be resumed.
