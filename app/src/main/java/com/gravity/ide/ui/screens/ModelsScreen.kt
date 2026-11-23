@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.gravity.ide.ai.LlmMessage
import com.gravity.ide.ai.LlmRequest
import com.gravity.ide.ai.Role
import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.ai.local.LlamaBridge
import com.gravity.ide.data.db.LocalModelEntity
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.models.HubFile
import com.gravity.ide.models.HubModel
import com.gravity.ide.models.ModelStatus
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.components.ConfirmDialog
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.KeyValue
import com.gravity.ide.ui.components.LoadingRow
import com.gravity.ide.ui.components.MenuAction
import com.gravity.ide.ui.components.MessageBanner
import com.gravity.ide.ui.components.OverflowMenu
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.SectionHeader
import com.gravity.ide.ui.components.StatusPill
import com.gravity.ide.ui.components.formatBytes
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(initialTab: Int) {
    var tab by remember { mutableStateOf(initialTab.coerceIn(0, 2)) }
    Column {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Installed") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Downloads") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Search Hub") })
        }
        when (tab) {
            0 -> InstalledModels()
            1 -> DownloadsTab()
            else -> HubSearch()
        }
    }
}

@Composable
private fun RuntimeBanner() {
    val c = LocalAppContainer.current
    if (!c.localHost.runtimeAvailable) {
        MessageBanner(
            "Local inference runtime not bundled: ${c.localHost.runtimeError}. Models can still be downloaded, verified and inspected; " +
                "loading requires an APK built with -Pgravity.llama=true (docs/OFFLINE_SETUP.md).",
        )
    }
}

@Composable
private fun InstalledModels() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val models by c.db.models().observe().collectAsState(initial = emptyList())
    val state by c.localHost.state.collectAsState()
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    var confirmLoad by remember { mutableStateOf<Pair<LocalModelEntity, String>?>(null) }
    var deleting by remember { mutableStateOf<LocalModelEntity?>(null) }
    var benchmarking by remember { mutableStateOf(false) }
    val ready = models.filter { it.status == ModelStatus.READY.name }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val m = c.models.importLocal(uri)
                toast(context, if (m.status == ModelStatus.READY.name) "Imported ${m.name}" else "Import failed: ${m.error}")
            } catch (e: Exception) {
                toast(context, "Import failed: ${e.message}")
            }
        }
    }

    fun doLoad(m: LocalModelEntity) = scope.launch {
        try {
            val info = c.device.info()
            c.localHost.load(m, com.gravity.ide.device.PerformanceManager.contextSize(settings, info), com.gravity.ide.device.PerformanceManager.threads(settings, info))
            toast(context, "Loaded ${m.name}")
        } catch (e: Exception) {
            toast(context, "Load failed: ${e.message}")
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { RuntimeBanner() }
        item {
            Panel {
                Column {
                    val loadedText = when (val s = state) {
                        is LocalModelHost.State.Ready -> "Loaded: ${s.loaded.name} (ctx ${s.loaded.contextSize})"
                        is LocalModelHost.State.Loading -> "Loading ${s.name}…"
                        is LocalModelHost.State.Failed -> "Last load failed: ${s.message}"
                        LocalModelHost.State.Idle -> "No model loaded"
                    }
                    Text(loadedText, style = MaterialTheme.typography.bodyMedium)
                    val dev = remember { c.device.info() }
                    Text(
                        "RAM ${formatBytes(dev.availRamBytes)} free of ${formatBytes(dev.totalRamBytes)} · storage ${formatBytes(c.models.freeBytes())} free · recommended max model ≈ ${formatBytes(c.device.recommendedMaxModelBytes())}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (c.localHost.runtimeAvailable) Text(LlamaBridge.systemInfo(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import GGUF") }
                        if (state is LocalModelHost.State.Ready) {
                            OutlinedButton(onClick = { scope.launch { c.localHost.unload() } }) { Text("Unload") }
                            OutlinedButton(enabled = !benchmarking, onClick = {
                                val loaded = (state as LocalModelHost.State.Ready).loaded
                                benchmarking = true
                                scope.launch {
                                    try {
                                        val tps = c.models.benchmark(loaded.modelId) {
                                            val start = System.currentTimeMillis()
                                            var tokens = 0
                                            c.providers.local.stream(LlmRequest(loaded.name, listOf(LlmMessage(Role.USER, "Write a short paragraph about gravity.")), maxOutputTokens = 96)) { ev ->
                                                if (ev is com.gravity.ide.ai.LlmEvent.Usage) tokens = ev.outputTokens
                                            }
                                            tokens to (System.currentTimeMillis() - start)
                                        }
                                        toast(context, "%.1f tokens/s".format(tps))
                                    } catch (e: Exception) {
                                        toast(context, "Benchmark failed: ${e.message}")
                                    } finally {
                                        benchmarking = false
                                    }
                                }
                            }) { Icon(Icons.Filled.Speed, null); Text(" Benchmark") }
                        }
                    }
                    if (benchmarking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
        if (ready.isEmpty()) item { EmptyState("No installed models", "Search the Hugging Face Hub for GGUF models or import a .gguf file you already have.") }
        items(ready, key = { it.id }) { m ->
            val loaded = (state as? LocalModelHost.State.Ready)?.loaded?.modelId == m.id
            Panel {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (m.isDefault) Icon(Icons.Filled.Star, null, tint = GravityColors.Amber, modifier = Modifier.padding(end = 4.dp))
                                Text(m.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                listOfNotNull(m.architecture, m.sizeLabel, m.quantization, formatBytes(m.sizeBytes), m.contextLength?.let { "ctx $it" }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (loaded) StatusPill("ACTIVE") else StatusPill(m.status)
                        OverflowMenu(
                            listOf(
                                MenuAction("Load", Icons.Filled.PlayArrow) {
                                    val warn = c.localHost.memoryWarning(m, settings.localContextSize)
                                    if (warn != null) confirmLoad = m to warn else doLoad(m)
                                },
                                MenuAction("Set as default", Icons.Filled.Star) { scope.launch { c.db.models().setDefault(m.id) } },
                                MenuAction("Delete", Icons.Filled.Delete, destructive = true) { deleting = m },
                            )
                        )
                    }
                    KeyValue("License", m.license ?: "unknown — check the model card")
                    KeyValue("SHA-256", (m.sha256Actual ?: "—").take(20) + if (m.sha256Expected != null) " (verified)" else "", mono = true)
                    KeyValue("Est. RAM", formatBytes(com.gravity.ide.core.GgufReader.estimateRamBytes(m.sizeBytes, settings.localContextSize)))
                    m.benchmarkTokensPerSec?.let { KeyValue("Benchmark", "%.1f tokens/s".format(it)) }
                    m.repoId?.let { KeyValue("Source", it) }
                    m.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = GravityColors.Amber) }
                }
            }
        }
    }

    confirmLoad?.let { (m, warn) ->
        ConfirmDialog("Memory warning", warn, "Load anyway", onConfirm = { doLoad(m) }, onDismiss = { confirmLoad = null })
    }
    deleting?.let { m ->
        ConfirmDialog("Delete ${m.name}?", "Frees ${formatBytes(m.sizeBytes)}. The file is deleted permanently.", "Delete", destructive = true, onConfirm = { scope.launch { c.models.delete(m.id) } }, onDismiss = { deleting = null })
    }
}

@Composable
private fun DownloadsTab() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val models by c.db.models().observe().collectAsState(initial = emptyList())
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    val downloads = models.filter { it.status != ModelStatus.READY.name || it.sourceUrl != null }.sortedByDescending { it.updatedAt }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionHeader("Download from URL")
            OutlinedTextField(url, { url = it }, label = { Text("HTTPS URL of a .gguf file") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sha, { sha = it }, label = { Text("Expected SHA-256 (recommended)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Button(enabled = url.startsWith("https://"), onClick = {
                scope.launch {
                    try {
                        c.models.enqueueDownload(url.trim(), url.substringAfterLast('/').substringBefore('?'), sha.trim().ifBlank { null })
                        url = ""; sha = ""
                    } catch (e: Exception) {
                        toast(context, e.message ?: "Failed")
                    }
                }
            }, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Filled.Download, null); Text(" Download") }
            Text("Without an expected hash, Gravity still computes and records the file's SHA-256 but cannot verify integrity against the publisher.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SectionHeader("Download history") }
        if (downloads.isEmpty()) item { Text("No downloads yet.", style = MaterialTheme.typography.bodySmall) }
        items(downloads, key = { it.id }) { m ->
            Panel {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusPill(m.status)
                    }
                    if (m.sizeBytes > 0) {
                        LinearProgressIndicator(progress = { (m.downloadedBytes.toFloat() / m.sizeBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    }
                    Text("${formatBytes(m.downloadedBytes)} / ${formatBytes(m.sizeBytes)} · ${relativeTime(m.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    m.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = GravityColors.Red) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (m.status) {
                            ModelStatus.DOWNLOADING.name, ModelStatus.QUEUED.name -> TextButton(onClick = { scope.launch { c.models.pause(m.id) } }) { Icon(Icons.Filled.Pause, null); Text(" Pause") }
                            ModelStatus.PAUSED.name, ModelStatus.FAILED.name -> TextButton(onClick = { scope.launch { c.models.resume(m.id) } }) { Icon(Icons.Filled.PlayArrow, null); Text(" Resume") }
                        }
                        if (m.status != ModelStatus.READY.name) TextButton(onClick = { scope.launch { c.models.delete(m.id) } }) { Text("Cancel & delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubSearch() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val network by c.network.state.collectAsState()
    var query by remember { mutableStateOf("qwen2.5 coder gguf") }
    var results by remember { mutableStateOf<List<HubModel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var repo by remember { mutableStateOf<HubModel?>(null) }
    var files by remember { mutableStateOf<List<HubFile>?>(null) }

    fun search() {
        loading = true
        error = null
        scope.launch {
            try {
                results = c.models.searchHub(query)
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            if (!network.online) MessageBanner("Offline — Hub search needs a connection. Installed models keep working.", isError = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text("Search Hugging Face (GGUF)") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { search() }, enabled = network.online && query.isNotBlank()) { Text("Search") }
            }
            Text(
                "Families such as Qwen and Gemma ship under different licenses per model; read each model card. Smaller quantizations (Q4_K_M, 0.5B–3B parameters) are realistic on phones.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loading) item { LoadingRow("Searching…") }
        error?.let { item { MessageBanner(it, isError = true) } }
        items(results, key = { it.repoId }) { m ->
            Panel(onClick = {
                repo = m
                files = null
                scope.launch {
                    files = try { c.models.listHubFiles(m.repoId) } catch (e: Exception) { error = e.message; emptyList() }
                }
            }) {
                Column {
                    Text(m.repoId, style = MaterialTheme.typography.titleSmall)
                    Text("${m.downloads} downloads · ${m.likes} likes · license ${m.license ?: "unspecified"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    repo?.let { m ->
        AlertDialog(
            onDismissRequest = { repo = null },
            title = { Text(m.repoId) },
            text = {
                val f = files
                if (f == null) LoadingRow("Listing files…") else if (f.isEmpty()) Text("No .gguf files found.") else LazyColumn {
                    items(f, key = { it.path }) { file ->
                        val tooBig = file.size + com.gravity.ide.models.ModelManager.SAFETY_MARGIN > c.models.freeBytes()
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(file.path, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${formatBytes(file.size)} · ${if (file.sha256 != null) "SHA-256 known" else "no checksum"}" + if (tooBig) " · not enough storage" else "",
                                style = MaterialTheme.typography.labelSmall, color = if (tooBig) GravityColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(enabled = !tooBig, onClick = {
                                scope.launch {
                                    try {
                                        c.models.enqueueHubDownload(m.repoId, file, m.license)
                                        toast(context, "Download queued — see Downloads")
                                        repo = null
                                    } catch (e: Exception) {
                                        toast(context, e.message ?: "Failed")
                                    }
                                }
                            }) { Text("Download") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { repo = null }) { Text("Close") } },
        )
    }
    LaunchedEffect(Unit) { if (results.isEmpty() && network.online) search() }
}
