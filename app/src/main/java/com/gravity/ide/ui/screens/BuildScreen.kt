@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gravity.ide.build.BuildKind
import com.gravity.ide.build.TermuxBridge
import com.gravity.ide.data.db.BuildRecordEntity
import com.gravity.ide.data.db.TestResultEntity
import com.gravity.ide.device.Capability
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.KeyValue
import com.gravity.ide.ui.components.LoadingRow
import com.gravity.ide.ui.components.MessageBanner
import com.gravity.ide.ui.components.MonoBlock
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.SectionHeader
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.StatusPill
import com.gravity.ide.ui.components.formatTimeFull
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun BuildScreen(projectId: String?, go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    val builds by c.db.builds().observe().collectAsState(initial = emptyList())
    val running by c.builds.running.collectAsState()
    var selected by remember { mutableStateOf(projectId) }
    var caps by remember { mutableStateOf<List<Capability>?>(null) }
    var probing by remember { mutableStateOf(false) }
    var openLog by remember { mutableStateOf<BuildRecordEntity?>(null) }
    var termuxOk by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val project = projects.firstOrNull { it.id == selected }
    var backendOverride by remember(project?.id) { mutableStateOf<BuildKind?>(null) }
    LaunchedEffect(refresh) { termuxOk = c.builds.termuxConfigured() }

    fun probe() {
        probing = true
        scope.launch {
            val result = c.device.probeCapabilities()
            caps = result
            probing = false
            // Record the feasibility assessment as real test evidence.
            result.forEach { cap ->
                c.db.testResults().insert(
                    TestResultEntity(suite = "feasibility", name = cap.name, expected = "Available", actual = cap.evidence, outcome = if (cap.available) "PASS" else "FAIL", details = cap.impact, createdAt = System.currentTimeMillis())
                )
            }
        }
    }
    LaunchedEffect(Unit) { if (caps == null) probe() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Selector("Project", projects, project, { it.name }, { selected = it.id }, Modifier.fillMaxWidth())
        }
        if (project != null) item {
            val kind = backendOverride ?: c.builds.defaultKind(project, termuxOk)
            val isRunning = running.containsKey(project.id)
            val kinds = BuildKind.entries.filter { it != BuildKind.STATIC_WEB || project.type == "WEB" || project.type == "GAME" || project.type == "APP" }
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Selector("Backend", kinds, kind, { it.label + if (it == BuildKind.TERMUX && !termuxOk) " (set up below)" else "" }, { backendOverride = it }, Modifier.fillMaxWidth())
                    KeyValue("Command", if (kind == BuildKind.STATIC_WEB) "assemble dist/ + zip" else project.buildCommand ?: "— (set in Project settings)", mono = true)
                    KeyValue("Artifact", project.artifactPattern ?: "—", mono = true)
                    if (isRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isRunning) {
                            OutlinedButton(onClick = { c.builds.cancel(project.id) }) { Icon(Icons.Filled.Stop, null); Text(" Cancel") }
                        } else {
                            Button(onClick = {
                                scope.launch {
                                    try {
                                        val r = c.builds.build(project, kind)
                                        toast(context, "Build ${r.status.lowercase()}")
                                    } catch (e: Exception) {
                                        toast(context, e.message ?: "Build failed")
                                    }
                                }
                            }) { Icon(Icons.Filled.Build, null); Text(" Build") }
                            OutlinedButton(onClick = { scope.launch { runCatching { c.builds.build(project, kind, clean = true) }.onFailure { toast(context, it.message ?: "") } } }) {
                                Icon(Icons.Filled.CleaningServices, null); Text(" Clean build")
                            }
                        }
                    }
                    if (project.type == "ANDROID" && kind == BuildKind.COMMAND) {
                        MessageBanner(
                            "The Yamini shell can only run toolchains that are actually executable inside this app (see feasibility below); on stock Android that excludes JDK/Gradle. " +
                                "Use the Termux companion backend for same-phone APK builds, or build on a computer/CI and install the APK here.",
                        )
                    }
                }
            }
        }
        item { TermuxPanel(onChanged = { refresh++ }) }
        item { SectionHeader("Build history") }
        val shown = builds.filter { selected == null || it.projectId == selected }
        if (shown.isEmpty()) item { Text("No builds yet.", style = MaterialTheme.typography.bodySmall) }
        items(shown.take(30), key = { it.id }) { b ->
            Panel(onClick = { openLog = b }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(projects.firstOrNull { it.id == b.projectId }?.name ?: "Project", modifier = Modifier.weight(1f))
                        StatusPill(b.status)
                    }
                    Text("${b.command} · exit ${b.exitCode ?: "—"} · ${relativeTime(b.startedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    b.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = GravityColors.Red, maxLines = 3) }
                    b.artifactPath?.let { path ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(File(path).name + if (File(path).exists()) "" else " (missing)", style = MaterialTheme.typography.labelSmall, color = GravityColors.Green, modifier = Modifier.weight(1f))
                            if (path.endsWith(".apk")) TextButton(onClick = { go(Routes.devices(path)) }) { Icon(Icons.Filled.InstallMobile, null); Text(" Install") }
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("On-device toolchain feasibility") { TextButton(onClick = { probe() }, enabled = !probing) { Text("Re-run") } }
            Text("Real probes on this device. Results are also saved to the Testing Center as evidence.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (probing) item { LoadingRow("Probing device capabilities…") }
        caps?.let { list ->
            items(list, key = { it.id }) { cap ->
                Panel {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(cap.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            StatusPill(if (cap.available) "PASS" else "FAIL")
                        }
                        Text(cap.evidence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(cap.impact, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                val canGradle = list.firstOrNull { it.id == "jdk" }?.available == true && list.firstOrNull { it.id == "exec_app_storage" }?.available == true
                MessageBanner(
                    if (canGradle) "A JDK is on PATH and app storage is executable: Gradle builds may work here. Verify with a clean build of the Android template."
                    else "Verdict: this device cannot run desktop Gradle/AGP inside Yamini's own sandbox. For APKs built on this phone use the Termux companion backend above " +
                        "(Termux runs the JDK, Gradle and aapt2). Without it: static web/game builds, any tool that is present, and installing/testing APKs built elsewhere.",
                    isError = !canGradle,
                )
            }
        }
    }

    openLog?.let { b ->
        AlertDialog(
            onDismissRequest = { openLog = null },
            title = { Text("Build #${b.id} · ${b.status.lowercase()}") },
            text = {
                LazyColumn {
                    item {
                        KeyValue("Started", formatTimeFull(b.startedAt))
                        KeyValue("Finished", b.finishedAt?.let { formatTimeFull(it) } ?: "—")
                        KeyValue("Exit code", b.exitCode?.toString() ?: "—")
                        b.artifactPath?.let { KeyValue("Artifact", it, mono = true) }
                        b.error?.let { Text(it, color = GravityColors.Red, style = MaterialTheme.typography.bodySmall) }
                        MonoBlock(b.log.takeLast(20_000).ifBlank { "(no output)" }, Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { openLog = null }) { Text("Close") } },
        )
    }
}

private const val TERMUX_STEPS = """1. Install Termux from F-Droid or GitHub (the Play Store build is outdated).
2. In Termux: pkg update && pkg install openjdk-17 gradle aapt2
3. Let Gravity send commands: mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties && termux-reload-settings
4. Shared storage access: termux-setup-storage (accept the prompt).
5. Android SDK: Termux has no official SDK. Install an arm64 SDK (platform + build-tools) and put export ANDROID_HOME=/path/to/sdk in ~/.gravity-env — Gravity sources that file and writes local.properties for you. Termux's aapt2 is used automatically.
6. Here: grant the permission and choose a shared folder (e.g. Download/GravityBuild).
7. Run the first build online so Gradle can cache dependencies; afterwards builds can use --offline."""

@Composable
private fun TermuxPanel(onChanged: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsState(initial = com.gravity.ide.data.settings.AppSettings())
    var status by remember { mutableStateOf(c.termux.status()) }
    var toolchain by remember { mutableStateOf<Map<String, String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var showSteps by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = c.termux.status()
        onChanged()
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            if (c.termux.sharePathFor(uri) == null) {
                toast(context, "Pick a folder on internal storage (for example Download/GravityBuild)")
            } else {
                scope.launch {
                    c.settings.update { it.copy(termuxShareUri = uri.toString()) }
                    onChanged()
                }
            }
        }
    }
    val sharePath = settings.termuxShareUri?.let { c.termux.sharePathFor(Uri.parse(it)) }
    SectionHeader("Termux companion")
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Same-phone APK builds: Yamini copies the project to a shared folder, Termux runs Gradle with its own JDK and aapt2, and Yamini imports and verifies the resulting APK. Exit code and output are Termux's real results.",
                style = MaterialTheme.typography.bodySmall,
            )
            KeyValue("Termux", if (status.installed) "installed (${status.version})" else "not installed")
            KeyValue("Permission", if (status.permissionGranted) "granted" else "not granted")
            KeyValue("Shared folder", sharePath ?: "not chosen", mono = sharePath != null)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!status.installed) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Get Termux") }
                } else if (!status.permissionGranted) {
                    TextButton(onClick = { permLauncher.launch(TermuxBridge.PERMISSION) }) { Text("Grant permission") }
                }
                TextButton(onClick = { folderLauncher.launch(null) }) { Text(if (sharePath == null) "Choose folder" else "Change folder") }
                TextButton(onClick = { status = c.termux.status(); onChanged() }) { Text("Refresh") }
            }
            Button(enabled = status.installed && status.permissionGranted && !checking, onClick = {
                checking = true
                scope.launch {
                    toolchain = try { c.termux.toolchain(sharePath) } catch (e: Exception) { mapOf("error" to (e.message ?: "failed")) }
                    checking = false
                }
            }) { Text("Check Termux toolchain") }
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            toolchain?.let { t ->
                for ((k, v) in t) KeyValue(k, v, mono = true)
                val missing = listOf("java", "gradle", "aapt2", "sdk").filter { t[it] == null }
                if (missing.isNotEmpty()) Text("Missing: ${missing.joinToString()}", color = GravityColors.Amber, style = MaterialTheme.typography.labelSmall)
                if (t["storage"] == "missing") Text("Run termux-setup-storage in Termux.", color = GravityColors.Amber, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Hide setup steps" else "Setup steps") }
            if (showSteps) Text(TERMUX_STEPS, style = MaterialTheme.typography.bodySmall, fontFamily = com.gravity.ide.ui.theme.MonoFamily)
        }
    }
}
