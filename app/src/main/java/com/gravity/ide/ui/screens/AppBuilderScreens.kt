package com.gravity.ide.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import com.gravity.ide.agent.AppBuilder
import com.gravity.ide.ai.GeminiProvider
import com.gravity.ide.ai.ProviderKind
import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.data.db.ProviderEntity
import com.gravity.ide.device.AppInstaller
import com.gravity.ide.security.SecretStore
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.ConfirmDialog
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.LoadingRow
import com.gravity.ide.ui.components.MenuAction
import com.gravity.ide.ui.components.MessageBanner
import com.gravity.ide.ui.components.MonoBlock
import com.gravity.ide.ui.components.OverflowMenu
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.formatBytes
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private val ACTIVE = setOf("PENDING", "RUNNING", "WAITING_APPROVAL", "WAITING_NETWORK")

private val EXAMPLES = listOf(
    "Music player" to "A music player for the songs on my phone, with a playlist, shuffle, repeat and a big play/pause button",
    "To-do list" to "A to-do list with due dates, priorities and a done filter",
    "Calculator" to "A calculator with history and big buttons",
    "Notes" to "A notes app with search, pinned notes and colours",
    "Expense tracker" to "An expense tracker with categories, monthly totals and a simple chart",
    "Stopwatch & timer" to "A stopwatch with laps and a countdown timer with an alarm sound",
    "Quiz game" to "A general knowledge quiz game with 20 questions, a timer and a high score",
    "Unit converter" to "A unit converter for length, weight, temperature and currency (rates entered by me)",
)

/** Home: describe an app in plain words and Gravity builds it. */
@Composable
fun HomeScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by c.db.providers().observe().collectAsState(initial = emptyList())
    val local by c.localHost.state.collectAsState()
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    val apps = projects.filter { it.type == "APP" }.sortedByDescending { maxOf(it.updatedAt, it.lastOpenedAt) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var setupOpen by remember { mutableStateOf(false) }
    val cloud = providers.firstOrNull { it.enabled && it.kind != ProviderKind.LOCAL.name }
    val localReady = (local as? LocalModelHost.State.Ready)?.loaded
    val aiReady = cloud != null || localReady != null

    var localWarning by remember { mutableStateOf(false) }

    fun startBuild() {
        scope.launch {
            busy = true
            runCatching { c.appBuilder.create(prompt) }
                .onSuccess { (project, _) -> prompt = ""; go(Routes.app(project.id)) }
                .onFailure { toast(context, it.message ?: "Could not start the build") }
            busy = false
        }
    }

    fun build() {
        when {
            !aiReady -> setupOpen = true
            cloud == null -> localWarning = true // only the on-device model: say what to expect first
            else -> startBuild()
        }
    }

    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("What do you want to build?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Describe it in your own words, in any language. Gravity writes the whole app, runs it here, and can install it on your phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                prompt, { prompt = it }, modifier = Modifier.fillMaxWidth().testTag("build-prompt"), minLines = 3,
                placeholder = { Text("e.g. Music player bana do — with playlists and shuffle") },
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EXAMPLES.forEach { (label, text) -> AssistChip(onClick = { prompt = text }, label = { Text(label) }) }
            }
        }
        item {
            Button(onClick = ::build, enabled = prompt.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("build-button")) {
                Icon(Icons.Filled.AutoAwesome, null)
                Spacer(Modifier.size(8.dp))
                Text(if (busy) "Starting…" else "Build my app")
            }
        }
        item {
            Panel(onClick = { setupOpen = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            when {
                                cloud != null -> "${cloud.name} · ${cloud.defaultModel.ifBlank { "model chosen on first use" }}"
                                localReady != null -> "On-device: ${localReady.name}"
                                else -> "Not set up yet: tap to connect a free AI key (one minute)"
                            },
                            color = if (aiReady) MaterialTheme.colorScheme.onSurface else GravityColors.Amber,
                        )
                    }
                    TextButton(onClick = { setupOpen = true }) { Text(if (aiReady) "Change" else "Set up") }
                }
            }
        }
        if (apps.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("My apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (apps.size > 5) TextButton(onClick = { go(Routes.APPS) }) { Text("All ${apps.size}") }
                }
            }
            items(apps.take(5), key = { it.id }) { p -> AppRow(p) { go(Routes.app(p.id)) } }
        }
        item {
            Text(
                "Gravity has no subscription and no limits of its own. Cloud AI providers set their own quotas (Gemini's free tier has daily limits); the on-device model has none but is much weaker.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (setupOpen) AiSetupDialog(onDismiss = { setupOpen = false }, go = go)
    if (localWarning) {
        AlertDialog(
            onDismissRequest = { localWarning = false },
            title = { Text("Only the on-device model is set up") },
            text = {
                Text(
                    "${localReady?.name ?: "The phone model"} will write the app as one simple HTML file. Small phone models are much weaker than " +
                        "Gemini: expect a basic app, or a failed build. A free Gemini key gives far better apps.",
                )
            },
            confirmButton = { TextButton(onClick = { localWarning = false; startBuild() }) { Text("Build with the phone model") } },
            dismissButton = { TextButton(onClick = { localWarning = false; setupOpen = true }) { Text("Connect Gemini") } },
        )
    }
}

@Composable
private fun AppRow(p: ProjectEntity, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Apps, null, tint = GravityColors.Cyan)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                Text("Changed ${relativeTime(maxOf(p.updatedAt, p.lastOpenedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Open", color = GravityColors.Cyan)
        }
    }
}

/** One-step AI setup: paste a Gemini key (model chosen automatically) or use the on-device model. */
@Composable
fun AiSetupDialog(onDismiss: () -> Unit, go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Connect AI") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recommended: a free Google Gemini key.", fontWeight = FontWeight.SemiBold)
                Text("1. Open Google AI Studio and sign in with your Google account.\n2. Tap \"Create API key\" and copy it.\n3. Paste it here. Gravity checks it and picks the newest model it can use.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { toast(context, "No browser found") }
                }) { Text("Open Google AI Studio") }
                OutlinedTextField(
                    key, { key = it.trim() }, label = { Text("Gemini API key") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("gemini-key"),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text("The key is stored encrypted on this phone (Android Keystore) and only sent to Google.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { MessageBanner(it, isError = true) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("Offline alternative", fontWeight = FontWeight.SemiBold)
                Text("Download and load a model in Model Manager. It runs on the phone with no limits and no internet, but small phone models write much weaker apps.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onDismiss(); go(Routes.models(0)) }) { Text("Open Model Manager") }
            }
        },
        confirmButton = {
            TextButton(enabled = key.length >= 20 && !saving, onClick = {
                scope.launch {
                    saving = true
                    error = null
                    val entity = ProviderEntity(
                        id = UUID.randomUUID().toString(), name = "Google Gemini", kind = ProviderKind.GEMINI.name,
                        baseUrl = GeminiProvider.DEFAULT_BASE_URL, defaultModel = "", createdAt = System.currentTimeMillis(),
                    )
                    c.db.providers().upsert(entity)
                    c.secrets.put(SecretStore.providerKey(entity.id), key)
                    c.providers.autoSelectModel(entity)
                        .onSuccess { model ->
                            c.settings.update { it.copy(defaultProviderId = entity.id, defaultModel = model) }
                            toast(context, "AI ready: $model")
                            onDismiss()
                        }
                        .onFailure { e ->
                            c.db.providers().delete(entity.id)
                            c.secrets.remove(SecretStore.providerKey(entity.id))
                            error = "That key did not work: ${e.message}"
                        }
                    saving = false
                }
            }) { Text("Save and check") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Close") } },
    )
}

/** All App Builder apps. */
@Composable
fun MyAppsScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    val apps = projects.filter { it.type == "APP" }.sortedByDescending { maxOf(it.updatedAt, it.lastOpenedAt) }
    if (apps.isEmpty()) {
        EmptyState("No apps yet", "Describe an app on the Home screen and Gravity builds it.", action = { TextButton(onClick = { go(Routes.HOME) }) { Text("Create an app") } })
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(apps, key = { it.id }) { p -> AppRow(p) { go(Routes.app(p.id)) } }
    }
}

/** One app: live preview, build progress, change requests, undo, install and share. */
@Composable
fun AppScreen(projectId: String, go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project by c.db.projects().observe(projectId).collectAsState(initial = null)
    val tasks by c.db.tasks().observeForProject(projectId).collectAsState(initial = emptyList())
    val live by c.agent.live.collectAsState()
    val p = project
    if (p == null) {
        LoadingRow("Opening app…")
        return
    }
    val current = tasks.firstOrNull()
    val running = current != null && current.status in ACTIVE
    val eventsFlow = remember(current?.id) { current?.let { c.db.events().observeForTask(it.id) } ?: flowOf(emptyList()) }
    val events by eventsFlow.collectAsState(initial = emptyList())
    var reloadKey by remember { mutableIntStateOf(0) }
    val consoleErrors = remember { mutableStateListOf<String>() }
    var change by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var setupOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var lastApk by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(projectId) { c.workspace.touch(p) }
    // Reload the preview whenever a run finishes (or is undone).
    LaunchedEffect(current?.id, current?.status, current?.stage) { if (current == null || current.status !in ACTIVE) reloadKey++ }

    fun submitChange(text: String) {
        scope.launch {
            runCatching { c.appBuilder.change(p, text) }
                .onSuccess { change = "" }
                .onFailure { toast(context, it.message ?: "Could not start the change") }
        }
    }

    fun install() {
        scope.launch {
            working = "Packaging…"
            try {
                val out = c.packager.build(p)
                lastApk = out.apk
                working = "Android will ask you to confirm the install…"
                when (val r = c.installer.install(out.apk)) {
                    is AppInstaller.InstallOutcome.Success -> toast(context, "Installed. Find \"${out.label}\" in your app list.")
                    is AppInstaller.InstallOutcome.Failure -> toast(
                        context,
                        "Install failed: ${r.message}" + if (r.message.contains("conflict", true) || r.message.contains("signature", true)) ". Uninstall the old copy of this app first." else "",
                    )
                }
            } catch (e: Exception) {
                toast(context, e.message ?: "Packaging failed")
            } finally {
                working = null
            }
        }
    }

    fun share() {
        scope.launch {
            working = "Packaging…"
            try {
                val apk = c.packager.build(p).apk
                lastApk = apk
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                val send = Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "Share ${p.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                toast(context, e.message ?: "Could not share")
            } finally {
                working = null
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Status of the latest run.
        if (current != null) {
            val isBuild = tasks.size == 1
            when {
                running -> Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            when (current.status) {
                                "WAITING_APPROVAL" -> "Waiting for your approval"
                                "WAITING_NETWORK" -> "Waiting for internet…"
                                else -> if (isBuild) "Building your app…" else "Making your change…"
                            },
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).testTag("app-status"),
                        )
                        if (current.status == "WAITING_APPROVAL") TextButton(onClick = { go(Routes.task(current.id)) }) { Text("Review") }
                        TextButton(onClick = { c.agent.cancel(current.id) }) { Text("Stop") }
                    }
                    Text(current.stage ?: "Starting…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    events.takeLast(3).forEach { e -> Text("• ${e.title}", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    live[current.id]?.takeIf { it.isNotBlank() }?.let { MonoBlock(it.takeLast(240), maxLines = 3) }
                }
                current.status == "FAILED" -> Column(Modifier.padding(horizontal = 12.dp)) {
                    MessageBanner("The AI run failed: ${current.error ?: "unknown error"}", isError = true)
                    Row {
                        TextButton(onClick = {
                            scope.launch { runCatching { c.appBuilder.retry(p, current) }.onFailure { toast(context, it.message ?: "Could not retry") } }
                        }) { Text("Try again") }
                        if ((current.error ?: "").let { "provider" in it.lowercase() || "local model" in it.lowercase() || "api key" in it.lowercase() }) {
                            TextButton(onClick = { setupOpen = true }) { Text("Set up AI") }
                        }
                        TextButton(onClick = { go(Routes.task(current.id)) }) { Text("Details") }
                    }
                }
                current.status == "PAUSED" -> Column(Modifier.padding(horizontal = 12.dp)) {
                    MessageBanner("Paused: ${current.stage ?: current.error ?: ""}")
                    TextButton(onClick = { c.agent.start(current.id) }) { Text("Continue") }
                }
                current.status == "COMPLETED" && current.stage != AppBuilder.UNDONE && !current.summary.isNullOrBlank() ->
                    Text(
                        current.summary!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("app-summary"),
                    )
                else -> Unit
            }
        }
        working?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp)) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (File(p.rootPath, "index.html").isFile) AppPreview(p, reloadKey, consoleErrors, Modifier.fillMaxSize())
            else EmptyState("No app yet", "The app appears here as soon as the AI writes it.")
        }
        if (consoleErrors.isNotEmpty() && !running) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${consoleErrors.size} error(s) in the app", color = GravityColors.Red, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { submitChange("Fix these JavaScript errors shown when the app runs:\n" + consoleErrors.take(8).joinToString("\n")) }) { Text("Ask AI to fix") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                change, { change = it }, enabled = !running, maxLines = 4, modifier = Modifier.weight(1f).testTag("change-input"),
                placeholder = { Text("Describe a change… e.g. add a shuffle button") },
            )
            IconButton(onClick = { submitChange(change) }, enabled = change.isNotBlank() && !running) { Icon(Icons.AutoMirrored.Filled.Send, "Send change") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = ::install, enabled = !running && working == null, modifier = Modifier.testTag("install-app")) {
                Icon(Icons.Filled.InstallMobile, null)
                Spacer(Modifier.size(6.dp))
                Text("Install on phone")
            }
            OutlinedButton(onClick = ::share, enabled = !running && working == null) { Icon(Icons.Filled.Share, null); Spacer(Modifier.size(6.dp)); Text("Share APK") }
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { c.appBuilder.undoLast(p) }
                        .onSuccess { n -> toast(context, "Undone: $n file(s) restored"); reloadKey++ }
                        .onFailure { toast(context, it.message ?: "Nothing to undo") }
                }
            }, enabled = !running) { Icon(Icons.Filled.Undo, null); Spacer(Modifier.size(6.dp)); Text("Undo last change") }
            IconButton(onClick = { reloadKey++ }) { Icon(Icons.Filled.Refresh, "Reload preview") }
            OverflowMenu(
                listOf(
                    MenuAction("Files (advanced)", Icons.Filled.Description) { go(Routes.files(p.id)) },
                    MenuAction("Open in Browser Preview", Icons.Filled.Public) { go(Routes.browser("gravity-project:${p.id}")) },
                    MenuAction("AI run details", Icons.Filled.ListAlt) { current?.let { go(Routes.task(it.id)) } },
                    MenuAction("Remove from My Apps", Icons.Filled.Delete, destructive = true) { confirmRemove = true },
                )
            )
        }
        lastApk?.takeIf { it.isFile }?.let { Text("APK: ${it.name} · ${formatBytes(it.length())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)) }
    }
    if (setupOpen) AiSetupDialog(onDismiss = { setupOpen = false }, go = go)
    if (confirmRemove) {
        ConfirmDialog(
            "Remove ${p.name}?", "It is archived, not deleted: restore it from Projects → Archived.", "Remove", destructive = true,
            onConfirm = { scope.launch { c.db.projects().upsert(p.copy(archived = true)); go(Routes.HOME) } },
            onDismiss = { confirmRemove = false },
        )
    }
}

/**
 * The app running in a WebView, served from the project folder like a web server. File inputs
 * open the system picker; JavaScript errors are collected so the AI can be asked to fix them.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppPreview(project: ProjectEntity, reloadKey: Int, consoleErrors: SnapshotStateList<String>, modifier: Modifier = Modifier) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val loader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/project/", WebViewAssetLoader.InternalStoragePathHandler(context, c.workspace.workspacesRoot))
            .build()
    }
    val url = "https://appassets.androidplatform.net/project/${File(project.rootPath).name}/index.html"
    var pending by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && data != null) {
            data.clipData?.let { clip -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri } } ?: data.data?.let { arrayOf(it) }
        } else null
        pending?.onReceiveValue(uris)
        pending = null
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(reloadKey, webView) {
        val wv = webView ?: return@LaunchedEffect
        consoleErrors.clear()
        wv.clearCache(true)
        wv.loadUrl(url)
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.host == "appassets.androidplatform.net") return false
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            consoleErrors.add("${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                        }
                        return true
                    }

                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        pending?.onReceiveValue(null)
                        pending = callback
                        val intent = params.createIntent()
                        if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        return try {
                            picker.launch(intent)
                            true
                        } catch (e: Exception) {
                            pending = null
                            false
                        }
                    }
                }
                webView = this
            }
        },
        modifier = modifier.heightIn(min = 200.dp).testTag("app-preview"),
    )
}
