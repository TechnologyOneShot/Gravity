@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gravity.ide.BuildConfig
import com.gravity.ide.agent.AgentRole
import com.gravity.ide.ai.ReasoningMode
import com.gravity.ide.ai.local.LlamaBridge
import com.gravity.ide.data.db.MessageEntity
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.data.settings.OperatingMode
import com.gravity.ide.data.settings.ThemeMode
import com.gravity.ide.security.SecretStore
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.KeyValue
import com.gravity.ide.ui.components.LoadingRow
import com.gravity.ide.ui.components.MessageBanner
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.SectionHeader
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.StatusPill
import com.gravity.ide.ui.components.TextInputDialog
import com.gravity.ide.ui.components.formatBytes
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.drawerItems
import com.gravity.ide.ui.theme.GravityColors
import com.gravity.ide.workspace.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Control {
    data class Toggle(val get: (AppSettings) -> Boolean, val set: (AppSettings, Boolean) -> AppSettings) : Control
    data class Choice(val options: List<Pair<String, String>>, val get: (AppSettings) -> String, val set: (AppSettings, String) -> AppSettings) : Control
    data class Num(val get: (AppSettings) -> Int, val set: (AppSettings, Int) -> AppSettings, val range: IntRange) : Control
    data class Secret(val key: String) : Control
    data class Link(val route: String) : Control
    data class Action(val label: String, val run: (Context) -> Unit) : Control
    data class Info(val text: (AppSettings) -> String) : Control
}

private data class SettingItem(val category: String, val title: String, val description: String, val control: Control)

private val settingItems: List<SettingItem> = listOf(
    SettingItem("General", "Notifications", "Task completion and download progress notifications", Control.Toggle({ it.notifications }, { s, v -> s.copy(notifications = v) })),
    SettingItem("Appearance", "Theme", "Dark developer theme, light theme or follow the system", Control.Choice(ThemeMode.entries.map { it.name to it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, { it.theme.name }, { s, v -> s.copy(theme = ThemeMode.valueOf(v)) })),
    SettingItem("Language", "App language", "Uses Android's per-app language setting", Control.Action("Open") { ctx ->
        if (Build.VERSION.SDK_INT >= 33) ctx.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else ctx.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }),
    SettingItem("AI Providers", "Manage providers", "Gemini, OpenAI-compatible and custom endpoints", Control.Link(Routes.PROVIDERS)),
    SettingItem("API Configuration", "Keys, base URLs, timeouts, limits", "Edited per provider; keys are Keystore-encrypted", Control.Link(Routes.PROVIDERS)),
    SettingItem("Model Selection", "Default model", "Set from AI Providers (★ Set as default) or a local model", Control.Info { s -> s.defaultProviderId?.let { "Provider ${it.take(8)} · ${s.defaultModel ?: "provider default"}" } ?: "Not set" }),
    SettingItem("Model Download", "Hugging Face token", "Optional; needed for gated models. Stored encrypted.", Control.Secret(SecretStore.HF_TOKEN)),
    SettingItem("Model Download", "Model Manager", "Search, download, verify and load GGUF models", Control.Link(Routes.models(0))),
    SettingItem("Hybrid Intelligence", "Operating mode", "Hybrid, Local only, Cloud only or Auto model", Control.Choice(OperatingMode.entries.map { it.name to it.label }, { it.operatingMode.name }, { s, v -> s.copy(operatingMode = OperatingMode.valueOf(v)) })),
    SettingItem("Hybrid Intelligence", "Fall back to local model when offline", "Cloud tasks switch to the loaded local model", Control.Toggle({ it.offlineFallbackToLocal }, { s, v -> s.copy(offlineFallbackToLocal = v) })),
    SettingItem("Hybrid Intelligence", "Retry cloud tasks when back online", "Tasks waiting for network resume automatically", Control.Toggle({ it.retryWhenOnline }, { s, v -> s.copy(retryWhenOnline = v) })),
    SettingItem("Hybrid Intelligence", "AI teams", "Architect / Developer / Reviewer collaboration", Control.Link(Routes.TEAMS)),
    SettingItem("Hybrid Intelligence", "Offline readiness", "Check what works without a network before going offline", Control.Link(Routes.OFFLINE)),
    SettingItem("Thinking Modes", "Default reasoning mode", "Maps to thinkingBudget / thinkingLevel / reasoning_effort / local token budget", Control.Choice(ReasoningMode.entries.map { it.name to it.label }, { it.reasoning.name }, { s, v -> s.copy(reasoning = ReasoningMode.valueOf(v)) })),
    SettingItem("Agent Behavior", "Max steps per run", "The task pauses at this limit (resume continues)", Control.Num({ it.agentMaxSteps }, { s, v -> s.copy(agentMaxSteps = v) }, 1..200)),
    SettingItem("Agent Behavior", "Approve every command", "Otherwise only risky commands need approval", Control.Toggle({ it.approveAllCommands }, { s, v -> s.copy(approveAllCommands = v) })),
    SettingItem("Agent Behavior", "Approve every file write", "Otherwise only sensitive files need approval", Control.Toggle({ it.approveFileWrites }, { s, v -> s.copy(approveFileWrites = v) })),
    SettingItem("Agent Behavior", "Parallel tasks", "Independent tasks that may run at once", Control.Num({ it.maxParallelTasks }, { s, v -> s.copy(maxParallelTasks = v) }, 1..8)),
    SettingItem("Workspace", "Storage location", "App-private storage (survives restarts, removed on uninstall — back up!)", Control.Info { "/data/data/${BuildConfig.APPLICATION_ID}/files/workspaces" }),
    SettingItem("File Explorer", "Show hidden files", "Dot-files and folders", Control.Toggle({ it.showHiddenFiles }, { s, v -> s.copy(showHiddenFiles = v) })),
    SettingItem("Code Editor", "Font size", "Editor text size (sp)", Control.Num({ it.editorFontSize }, { s, v -> s.copy(editorFontSize = v) }, 8..28)),
    SettingItem("Code Editor", "Auto-save", "Save 2.5 s after the last edit (a version is kept each time)", Control.Toggle({ it.autoSave }, { s, v -> s.copy(autoSave = v) })),
    SettingItem("Terminal", "Font size", "Terminal text size (sp)", Control.Num({ it.terminalFontSize }, { s, v -> s.copy(terminalFontSize = v) }, 8..28)),
    SettingItem("Web Search", "Search provider", "Brave Search API (Gemini chats can also use Google Search grounding)", Control.Choice(listOf("none" to "None", "brave" to "Brave Search API"), { it.webSearchProvider }, { s, v -> s.copy(webSearchProvider = v) })),
    SettingItem("Web Search", "Brave Search API key", "Stored encrypted", Control.Secret(SecretStore.BRAVE_KEY)),
    SettingItem("Build Environment", "Build Center", "Build backends and the on-device feasibility report", Control.Link(Routes.build())),
    SettingItem("SDK Manager", "Toolchains (JDK, SDK, Gradle, Node)", "Android blocks running them from Gravity's storage; install them in Termux and use the Termux companion backend", Control.Link(Routes.build())),
    SettingItem("ADB", "Developer options", "USB/Wireless debugging (for Shizuku or a computer)", Control.Action("Open") { ctx -> ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }),
    SettingItem("Device Testing", "Testing Center", "Screen capture, app tests, self-test results", Control.Link(Routes.TESTING)),
    SettingItem("Notifications", "System notification settings", "Channels for tasks, downloads and capture", Control.Action("Open") { ctx ->
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }),
    SettingItem("Performance", "Performance mode", "Thread count for local inference (thermal state still caps it)", Control.Choice(listOf("ADAPTIVE" to "Adaptive", "HIGH" to "High performance", "LOW" to "Battery saver"), { it.performanceMode }, { s, v -> s.copy(performanceMode = v) })),
    SettingItem("Performance", "Low memory mode", "2k context, 2 threads, one task at a time", Control.Toggle({ it.lowMemoryMode }, { s, v -> s.copy(lowMemoryMode = v) })),
    SettingItem("Resource Limits", "Local inference threads", "Used in Adaptive mode", Control.Num({ it.localThreads }, { s, v -> s.copy(localThreads = v) }, 1..16)),
    SettingItem("Resource Limits", "Local context size (tokens)", "Larger contexts need more RAM", Control.Num({ it.localContextSize }, { s, v -> s.copy(localContextSize = v) }, 512..32768)),
    SettingItem("Security", "Redact secrets sent to cloud models", "API keys, tokens and private keys are replaced before upload", Control.Toggle({ it.redactSecrets }, { s, v -> s.copy(redactSecrets = v) })),
    SettingItem("Privacy", "Local-only projects", "Mark a project local-only in Project settings: its files never go to cloud AI", Control.Link(Routes.PROJECTS_LIST)),
    SettingItem("Storage", "Storage usage", "Workspaces, models, checkpoints, screenshots, trash", Control.Info { "See below" }),
    SettingItem("Backup", "Backup & Recovery", "Encrypted backups, trash, versions", Control.Link(Routes.BACKUP)),
    SettingItem("Plugins", "Plugins", "Extension status", Control.Link(Routes.PLUGINS)),
    SettingItem("Experimental Features", "Experimental features", "None in this build", Control.Info { "No experimental features are included in this build." }),
    SettingItem("Diagnostics", "Self-test & diagnostics", "Run real checks on this device", Control.Link(Routes.HELP)),
    SettingItem("About Gravity", "About", "Version and build information", Control.Link(Routes.ABOUT)),
)

@Composable
fun SettingsScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    var query by remember { mutableStateOf("") }
    var secretEdit by remember { mutableStateOf<String?>(null) }
    var storage by remember { mutableStateOf<List<Pair<String, Long>>?>(null) }
    LaunchedEffect(Unit) {
        storage = withContext(Dispatchers.IO) {
            val files = c.appContext.filesDir
            listOf("workspaces", "models", "checkpoints", "screenshots", "trash", "builds").map { it to File(files, it).walkBottomUp().filter { f -> f.isFile }.sumOf { f -> f.length() } } +
                ("cache" to c.appContext.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() })
        }
    }
    val shown = settingItems.filter { query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true) || it.category.contains(query, true) }

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { OutlinedTextField(query, { query = it }, placeholder = { Text("Search settings") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        var last = ""
        for (si in shown) {
            if (si.category != last) {
                last = si.category
                item(key = "h-${si.category}") { SectionHeader(si.category) }
            }
            item(key = "s-${si.category}-${si.title}") {
                SettingRow(si, settings, update = { t ->
                    scope.launch {
                        val before = c.settings.current().operatingMode
                        c.settings.update(t)
                        // Spec §17: inspect readiness when the user switches to offline (Local only).
                        if (before != OperatingMode.LOCAL_ONLY && c.settings.current().operatingMode == OperatingMode.LOCAL_ONLY) go(Routes.OFFLINE)
                    }
                }, go = go, context = context, hasSecret = { c.secrets.has(it) }, editSecret = { secretEdit = it })
            }
        }
        if (query.isBlank()) item {
            SectionHeader("Storage")
            val s = storage
            if (s == null) LoadingRow("Measuring…") else Panel {
                Column {
                    s.forEach { (name, size) -> KeyValue(name, formatBytes(size)) }
                    KeyValue("free", formatBytes(c.models.freeBytes()))
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { c.appContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                            toast(context, "Cache cleared")
                        }
                    }) { Text("Clear cache") }
                }
            }
        }
    }
    secretEdit?.let { key ->
        TextInputDialog("Set secret", "Value (leave as-is to keep)", "", "Save", onConfirm = { v -> c.secrets.put(key, v); toast(context, "Saved (encrypted)") }, onDismiss = { secretEdit = null })
    }
}

@Composable
private fun SettingRow(
    item: SettingItem,
    s: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    go: (String) -> Unit,
    context: Context,
    hasSecret: (String) -> Boolean,
    editSecret: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            Text(item.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.control is Control.Info) Text(item.control.text(s), style = MaterialTheme.typography.labelSmall, color = GravityColors.Cyan)
        }
        when (val ctl = item.control) {
            is Control.Toggle -> Switch(ctl.get(s), { v -> update { ctl.set(it, v) } })
            is Control.Choice -> Selector("", ctl.options, ctl.options.firstOrNull { it.first == ctl.get(s) }, { it.second }, { o -> update { ctl.set(it, o.first) } }, Modifier.width(170.dp))
            is Control.Num -> {
                var text by remember(ctl.get(s)) { mutableStateOf(ctl.get(s).toString()) }
                OutlinedTextField(
                    text, { v ->
                        text = v.filter(Char::isDigit)
                        text.toIntOrNull()?.takeIf { it in ctl.range }?.let { n -> update { ctl.set(it, n) } }
                    },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp),
                )
            }
            is Control.Secret -> TextButton(onClick = { editSecret(ctl.key) }) { Text(if (hasSecret(ctl.key)) "Change" else "Set") }
            is Control.Link -> TextButton(onClick = { go(ctl.route) }) { Text("Open") }
            is Control.Action -> TextButton(onClick = { runCatching { ctl.run(context) }.onFailure { toast(context, "Not available on this device") } }) { Text(ctl.label) }
            is Control.Info -> Unit
        }
    }
}

@Composable
fun GitScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    var gitPath by remember { mutableStateOf<String?>(null) }
    val versions by c.db.fileVersions().observeRecent().collectAsState(initial = emptyList())
    LaunchedEffect(Unit) {
        gitPath = withContext(Dispatchers.IO) {
            try {
                val p = ProcessBuilder("/system/bin/sh", "-c", "command -v git || true").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
            } catch (e: Exception) {
                ""
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            MessageBanner(
                "Git integration is not implemented in this build. Android ships no git binary and Gravity does not yet bundle a Git library (JGit integration is planned — see docs/KNOWN_LIMITATIONS.md). " +
                    "Until then, Gravity's checkpoints record a version of every file before each save and agent edit, with diff, per-task rollback and restore.",
                isError = true,
            )
        }
        item {
            Panel {
                Column {
                    KeyValue("git on PATH", gitPath?.ifBlank { "not found" } ?: "checking…", mono = true)
                    KeyValue("Checkpoints", "${versions.size} recent versions")
                    if (!gitPath.isNullOrBlank()) Text("A git binary was found: you can run git commands from the Terminal.", style = MaterialTheme.typography.bodySmall, color = GravityColors.Green)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { go(Routes.BACKUP) }) { Text("Version history") }
                        TextButton(onClick = { go(Routes.terminal()) }) { Text("Terminal") }
                    }
                }
            }
        }
    }
}

@Composable
fun PluginsScreen() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            MessageBanner(
                "No third-party plugin runtime is included in this build, so nothing can be installed here yet. Planned design: plugins as separate APKs exposing tools over a bound service, " +
                    "with declared permissions the user approves and no access to credentials or other projects.",
                isError = true,
            )
        }
        item { SectionHeader("Built-in agent tools") }
        val tools = listOf(
            "list_directory, read_file, search_code" to "Read-only, project root only",
            "write_file, replace_in_file, create_directory, move_path" to "Project root only; checkpointed; approval for sensitive files",
            "delete_path" to "Always requires approval; moves to trash",
            "run_command" to "Device shell; approval for risky commands",
            "build_project" to "Runs the project's build backend (Gravity shell, Termux, static web); real exit code",
            "install_apk" to "Requires approval, then the system install dialog",
            "test_app" to "Launch + logcat crash check + screenshot; real PASS/FAIL",
            "web_search, fetch_url" to "Network; results treated as untrusted data",
            "finish" to "Ends the task with a summary",
        )
        items(tools) { (name, perm) ->
            Panel {
                Column {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(perm, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionHeader("Agent roles") }
        items(AgentRole.entries.toList()) { r -> Text("• ${r.label}: ${r.brief}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun HelpScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val results by c.db.testResults().observe().collectAsState(initial = emptyList())
    var running by remember { mutableStateOf(false) }
    val selfTests = results.filter { it.suite == "self-test" }.distinctBy { it.name }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionHeader("Self-test")
            Text("Runs real checks on this device (database, Keystore, files, trash, checkpoints, shell exit codes, ZIP safety, GGUF parsing, backup crypto, network, providers, local inference) and records evidence.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !running, onClick = {
                running = true
                scope.launch {
                    c.selfTest.runAll()
                    running = false
                }
            }, modifier = Modifier.padding(top = 6.dp)) { Text("Run self-test") }
            if (running) LoadingRow("Running…")
        }
        items(selfTests, key = { it.id }) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.bodySmall)
                    Text(r.actual, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(r.outcome)
            }
        }
        item {
            SectionHeader("Diagnostics")
            Panel {
                Column {
                    KeyValue("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
                    KeyValue("Local runtime", if (LlamaBridge.isAvailable) "llama.cpp bundled" else (LlamaBridge.loadError ?: "unavailable"))
                    KeyValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}")
                    KeyValue("Data dir", c.appContext.filesDir.absolutePath, mono = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { go(Routes.OFFLINE) }) { Text("Offline readiness") }
                TextButton(onClick = { go(Routes.build()) }) { Text("Feasibility report") }
                TextButton(onClick = { go(Routes.ACTIVITY) }) { Text("Activity logs") }
                TextButton(onClick = { go(Routes.EXPORT) }) { Text("Export logs") }
            }
        }
        item {
            SectionHeader("Getting started")
            Text(
                "1. AI Providers → add Gemini (API key from Google AI Studio) or an OpenAI-compatible endpoint, then Test connection.\n" +
                    "2. Projects → New → pick a template.\n3. AI Agent → describe the change → approve risky steps → review Changes → roll back if needed.\n" +
                    "4. Model Manager → download a small GGUF model for offline work (needs an APK with the llama.cpp runtime).\n" +
                    "5. Testing Center → start capture → install/launch your app → run a test → AI-analyse the screenshot.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun AboutScreen() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("GRAVITY", style = MaterialTheme.typography.headlineMedium, color = GravityColors.Cyan)
            Text("Hybrid autonomous AI development environment for Android", style = MaterialTheme.typography.bodyMedium)
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionHeader("Principles")
            Text(
                "No simulated output: terminal output, builds, tests, downloads, screenshots and AI responses are real or reported as unavailable. " +
                    "Platform limits are shown where they apply (Build Center feasibility, Git, Plugins, shell notes).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            SectionHeader("Open-source components")
            Text("Jetpack Compose, Room, DataStore, WorkManager, AndroidX WebKit & DocumentFile (Apache-2.0) · OkHttp (Apache-2.0) · kotlinx.coroutines (Apache-2.0) · llama.cpp (MIT, optional build).", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun GlobalSearchScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var fileHits by remember { mutableStateOf<List<Pair<String, SearchHit>>>(emptyList()) }
    var messageHits by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.length < 2) {
            fileHits = emptyList(); messageHits = emptyList(); return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        searching = true
        fileHits = withContext(Dispatchers.IO) { projects.flatMap { p -> c.workspace.search(File(p.rootPath), query, maxResults = 40).map { p.id to it } }.take(200) }
        messageHits = c.db.chats().searchMessages(query)
        searching = false
    }
    val navHits = drawerItems.filter { query.length >= 2 && it.label.contains(query, true) }
    val projectHits = projects.filter { query.length >= 2 && it.name.contains(query, true) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { OutlinedTextField(query, { query = it }, placeholder = { Text("Search files, chats, projects, screens") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (searching) item { LoadingRow("Searching local data…") }
        if (navHits.isNotEmpty()) item { SectionHeader("Screens") }
        items(navHits, key = { "n" + it.route }) { n -> Text(n.label, modifier = Modifier.fillMaxWidth().clickable { go(n.route) }.padding(vertical = 8.dp)) }
        if (projectHits.isNotEmpty()) item { SectionHeader("Projects") }
        items(projectHits, key = { "p" + it.id }) { p -> Text(p.name, modifier = Modifier.fillMaxWidth().clickable { go(Routes.files(p.id)) }.padding(vertical = 8.dp)) }
        if (fileHits.isNotEmpty()) item { SectionHeader("Files (${fileHits.size})") }
        items(fileHits) { (pid, h) ->
            Column(Modifier.fillMaxWidth().clickable { go(Routes.editor(pid, h.relPath)) }.padding(vertical = 6.dp)) {
                Text("${projects.firstOrNull { it.id == pid }?.name}/${h.relPath}:${h.line}", style = MaterialTheme.typography.labelMedium, color = GravityColors.Cyan)
                Text(h.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (messageHits.isNotEmpty()) item { SectionHeader("Chat messages") }
        items(messageHits, key = { "m" + it.id }) { m ->
            Column(Modifier.fillMaxWidth().clickable { go(Routes.chat(m.chatId)) }.padding(vertical = 6.dp)) {
                Text(m.content.take(160), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(relativeTime(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (query.length >= 2 && !searching && navHits.isEmpty() && projectHits.isEmpty() && fileHits.isEmpty() && messageHits.isEmpty()) item { EmptyState("No results", "Web search is available to agents and Gemini chats when online.") }
    }
}

@Composable
fun WorkspacesScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val projects by c.db.projects().observeAll().collectAsState(initial = emptyList())
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(projects.size) { sizes = withContext(Dispatchers.IO) { projects.associate { it.id to c.workspace.storageUsage(File(it.rootPath)) } } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Panel {
                Column {
                    KeyValue("Location", c.workspace.workspacesRoot.absolutePath, mono = true)
                    KeyValue("Projects", "${projects.size} (${projects.count { it.archived }} archived)")
                    KeyValue("Total size", formatBytes(sizes.values.sum()))
                    Text(
                        "Workspaces are restored automatically after restarts. Folders opened through Android's picker are copied in (so the terminal and agent get real paths) and can be copied back from the Export Center.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { go(Routes.PROJECTS_NEW) }) { Text("New project") }
                        TextButton(onClick = { go(Routes.PROJECTS_LIST) }) { Text("Open folder / import ZIP") }
                    }
                }
            }
        }
        if (projects.isEmpty()) item { EmptyState("No workspaces", "") }
        items(projects, key = { it.id }) { p ->
            Panel(onClick = { go(Routes.files(p.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name + if (p.archived) " (archived)" else "")
                        Text(File(p.rootPath).name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatBytes(sizes[p.id] ?: 0), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
