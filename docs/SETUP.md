# Installation, setup and API configuration

## Install

1. Build the debug APK (README → Build), or download the `gravity-debug-apk` artifact from GitHub Actions.
2. Copy it to the phone and open it. Allow "Install unknown apps" for your file manager when Android asks.
3. On first start, allow notifications. They are used for task progress, downloads and screen capture.

Requirements: Android 8.0 (API 26) or newer. 64-bit ARM is recommended for local models.

## Permissions and why

| Permission | Used for |
|---|---|
| Internet, network state | Cloud AI, web search, downloads, online/offline switching |
| Notifications, foreground service (data sync, media projection) | Long tasks, model downloads, screen capture |
| Request install / delete packages | Installing and uninstalling apps you build (the system confirms each one) |
| Query all packages | Choosing a test target in Device Manager / Testing Center |
| READ_LOGS (optional, adb only) | Reading other apps' logcat: `adb shell pm grant com.gravity.ide android.permission.READ_LOGS` |

## AI providers

Open **AI Providers → Add provider**, save, then use **⋮ → Test connection**. The test lists models through the API and lets you pick the default model.

### Google Gemini
- Type: Google Gemini. Base URL: `https://generativelanguage.googleapis.com`.
- Model: e.g. `gemini-2.5-flash` or `gemini-2.5-pro`. Use Test connection to see what your key can access.
- API key: create one in Google AI Studio. It is stored encrypted with an Android Keystore key.
- Gemini supports streaming, function calling, vision, thinking configuration and the **Google Search grounding** chat toggle.

### OpenAI-compatible
- Type: OpenAI-compatible. Base URL ends in `/v1`, for example:
  - OpenAI `https://api.openai.com/v1`
  - OpenRouter `https://openrouter.ai/api/v1`
  - Ollama on your PC `http://192.168.x.y:11434/v1` (no key needed)
  - llama.cpp server `http://host:8080/v1`
- Switch on *Send reasoning_effort* only for models that accept it.
- Switch off *Request streamed usage* if the server rejects `stream_options`.

### Shared keys for many users
Never put a shared API key inside an APK. Run your own server-side proxy that holds the key and authenticates your users, then add the proxy here as an OpenAI-compatible endpoint.

### Routing
**Settings → Hybrid Intelligence → Operating mode** sets the routing rules:
- **Hybrid / Auto**: cloud first; offline falls back to the local model.
- **Local only**: never uses the network for AI.
- **Cloud only**: never uses the local model.

Set provider *Priority*, and the next provider becomes the backup when the primary fails or hits its token limit.

## Web search
**Settings → Web Search**: choose *Brave Search API* and set your key. Agents then get the `web_search` tool. Gemini chats can use Google Search grounding without a separate key.

## Teams
**Hybrid AI Team → New team**: assign a role, provider and model to each member. A local member can name a specific downloaded model, which Gravity loads before that member's stage after unloading the previous one. Pick the team when you start an agent task.

## Termux companion (same-phone APK builds)
1. Install Termux from F-Droid or GitHub, then run `pkg update && pkg install openjdk-17 gradle aapt2`.
2. Allow external apps: `mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties && termux-reload-settings`.
3. Run `termux-setup-storage` and accept the prompt.
4. Install an arm64 Android SDK (platform + build-tools) inside Termux and write `export ANDROID_HOME=/path/to/sdk` to `~/.gravity-env`. Gravity sources that file and writes `local.properties`.
5. In Gravity: **Build Center → Termux companion → Grant permission → Choose folder** (e.g. `Download/GravityBuild`) → **Check Termux toolchain**.
6. Pick *Termux companion* as the backend (or set it in Project settings) and build. The first build needs internet for Gradle dependencies.

Agents can drive the same loop with the `build_project`, `install_apk` and `test_app` tools.

## Projects
- Templates: Android (Compose), Static web, HTML5 game, Kotlin console, Python, Shell, Empty.
- **Project settings**: build command, expected artifact glob (a build counts as successful only if a fresh match exists), test command (teams run it every round), and **Local-only** (files are never sent to cloud AI).
- `GRAVITY.md` in each project is the agents' project memory. Edit it freely.
