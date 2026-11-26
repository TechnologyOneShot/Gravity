@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import com.gravity.ide.ui.components.safeTabIndicator
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gravity.ide.ai.LlmMessage
import com.gravity.ide.ai.LlmRequest
import com.gravity.ide.ai.Role
import com.gravity.ide.ai.complete
import androidx.compose.ui.platform.testTag
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
        TabRow(selectedTabIndex = tab, indicator = safeTabIndicator(tab), containerColor = MaterialTheme.colorScheme.background) {
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

/** Release page with the current debug build (includes the llama.cpp engine). */
private const val RELEASE_URL = "https://github.com/TechnologyOneShot/Gravity/releases/tag/debug-latest"

@Composable
private fun RuntimeBanner() {
    val c = LocalAppContainer.current
    // After a crash inside the engine: say what Gravity changed (never silently).
    c.localHost.crashNotice?.let { MessageBanner(it, isError = true) }
    if (!c.localHost.runtimeAvailable) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Column {
            MessageBanner(
                "This copy of Yamini has no on-device AI engine, so models can be downloaded and checked but not loaded. " +
                    "Install yamini-debug.apk from the latest release (it includes the engine), then load the model here.",
            )
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(RELEASE_URL))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) { Text("Get the version with on-device AI") }
        }
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
        item { RecommendedModels(models) }
        item { PhoneAiCheck() }
        item { PictureModelCard() }
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
                    val needRam = com.gravity.ide.core.GgufReader.estimateRamBytes(m.sizeBytes, settings.localContextSize)
                    KeyValue(
                        "Est. RAM",
                        formatBytes(needRam) + if (needRam > c.device.recommendedMaxModelBytes()) " — too big for this phone (slow or may be closed by Android)" else "",
                    )
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
            Text("Without an expected hash, Yamini still computes and records the file's SHA-256 but cannot verify integrity against the publisher.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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


/** A coder model Gravity suggests, with what CI measured for it (App Builder test apps that worked). */
private data class Suggested(val title: String, val repo: String, val file: String, val approxBytes: Long, val note: String)

private val suggestedModels = listOf(
    Suggested(
        "Gemma 3 4B (Hindi chat)", "bartowski/google_gemma-3-4b-it-GGUF", "google_gemma-3-4b-it-Q4_K_M.gguf", 2_490_000_000L,
        "Best phone model for Hindi chat: in Yamini's CI test it wrote the most correct, natural Hindi. To a Hinglish question it often answers in Hindi script (Devanagari) instead of Roman letters. About 6 GB RAM phones. Phone models can still get facts wrong.",
    ),
    Suggested(
        "Llama 3.2 3B (chat, fast)", "bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2_020_000_000L,
        "Small and quick, good for short replies and live voice. Best at keeping Hinglish in Roman letters (CI test: every Hinglish question), but some words are wrong more often than with Gemma 3 4B.",
    ),
    Suggested(
        "Qwen2.5 7B (chat)", "bartowski/Qwen2.5-7B-Instruct-GGUF", "Qwen2.5-7B-Instruct-Q4_K_M.gguf", 4_680_000_000L,
        "Better writing than the 3B chat model, but slower. The Coder models below are made for apps and code: they write weak lyrics or stories. About 12 GB RAM phones.",
    ),
    Suggested(
        "Qwen2.5-Coder 7B", "bartowski/Qwen2.5-Coder-7B-Instruct-GGUF", "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf", 4_680_000_000L,
        "Best phone model for apps (6 of 6 test apps worked). Needs a phone with about 12 GB RAM; slower.",
    ),
    Suggested(
        "Qwen2.5-Coder 3B", "bartowski/Qwen2.5-Coder-3B-Instruct-GGUF", "Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf", 1_930_000_000L,
        "For apps on 6–8 GB phones (5 of 6 test apps worked).",
    ),
    Suggested(
        "Qwen2.5-Coder 1.5B", "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF", "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", 1_120_000_000L,
        "Smallest and fastest; simple apps only (3 of 6 test apps worked).",
    ),
)

/** One-tap downloads of the models above, marked by whether they fit this phone. */
@Composable
private fun RecommendedModels(installed: List<LocalModelEntity>) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val maxBytes = remember { c.device.recommendedMaxModelBytes() }
    var busy by remember { mutableStateOf<String?>(null) }
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Recommended for this phone", style = MaterialTheme.typography.titleSmall)
            Text(
                "Q4_K_M builds from Hugging Face, checked with their published SHA-256. Yamini loads a downloaded model by itself when the AI is needed.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (m in suggestedModels) {
                // Only a copy from this repo counts (checked with its SHA-256); an imported file with the same name may differ.
                val have = installed.firstOrNull { it.repoId == m.repo && it.filePath.endsWith(m.file) }
                val fits = m.approxBytes <= maxBytes
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${m.title} · ≈${formatBytes(m.approxBytes)}", style = MaterialTheme.typography.bodyMedium)
                        Text(m.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (fits) "Fits this phone" else "Too big for this phone",
                            style = MaterialTheme.typography.labelSmall, color = if (fits) GravityColors.Green else GravityColors.Amber,
                        )
                    }
                    when {
                        have != null -> Text(if (have.status == ModelStatus.READY.name) "Downloaded" else have.status.lowercase(), style = MaterialTheme.typography.labelMedium, color = GravityColors.Green)
                        busy == m.repo -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else -> TextButton(onClick = {
                            busy = m.repo
                            scope.launch {
                                try {
                                    val file = c.models.listHubFiles(m.repo).firstOrNull { it.path.equals(m.file, ignoreCase = true) }
                                        ?: throw IllegalStateException("${m.file} is no longer in ${m.repo}")
                                    c.models.enqueueHubDownload(m.repo, file, null)
                                    toast(context, "Downloading ${m.title} (see Downloads)")
                                } catch (e: Exception) {
                                    toast(context, "Could not start the download: ${e.message}")
                                } finally {
                                    busy = null
                                }
                            }
                        }) { Text("Download") }
                    }
                }
            }
        }
    }
}


/**
 * Asks the phone model two easy questions and shows its answers with this phone's details, so a
 * broken setup (e.g. random text) is visible and can be reported. If the answers are wrong, it
 * tries again with 1 thread and offers to keep that if it helps.
 */
@Composable
private fun PhoneAiCheck() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var oneThreadHelps by remember { mutableStateOf(false) }

    suspend fun runCheck(): String {
        val sb = StringBuilder()
        val info = c.device.info()
        val threads = com.gravity.ide.device.PerformanceManager.threads(settings, info)
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}" else android.os.Build.HARDWARE
        sb.appendLine("Phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · $soc · ${info.cpuCores} cores · ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
        val cpuFeatures = runCatching { java.io.File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim() }.getOrNull()
        val parts = runCatching { java.io.File("/proc/cpuinfo").readLines().filter { it.startsWith("CPU part") }.map { it.substringAfter(':').trim() }.distinct() }.getOrNull()
        sb.appendLine("CPU features: ${cpuFeatures ?: "?"} · parts ${parts?.joinToString(",") ?: "?"}")
        sb.appendLine("Native heap tagging: ${runCatching { com.gravity.ide.ai.local.LlamaBridge.heapTagged() }.getOrNull() ?: "?"} · app ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
        val bridge = com.gravity.ide.ai.local.LlamaBridge
        sb.appendLine("Engines: ${bridge.engines.joinToString(", ") { it.label }.ifEmpty { "none" }}" +
            (bridge.fastUnavailableReason?.let { " (fast engine: $it)" } ?: "") + " · HWCAP 0x${java.lang.Long.toHexString(bridge.cpuHwcap())}")
        val ready = c.db.models().all().filter { it.status == "READY" }
        val lastId = c.localHost.lastModelId()
        ready.forEach { m ->
            val file = java.io.File(m.filePath)
            // Read the file back now (not the checksum saved at download): shows if the stored copy changed.
            val now = if (file.length() <= 6L * 1024 * 1024 * 1024 && (m.id == lastId || ready.size <= 2)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { sha256Of(file) }.getOrNull() }
            } else null
            val hash = when {
                !file.exists() -> "FILE IS MISSING"
                file.length() != m.sizeBytes -> "SIZE DIFFERS (${formatBytes(file.length())} on disk) — download it again"
                m.sha256Expected == null -> "no published checksum to compare" + (now?.let { " (sha256 ${it.take(12)}…)" } ?: "")
                now != null && !now.equals(m.sha256Expected, ignoreCase = true) -> "FILE ON DISK DIFFERS from Hugging Face — delete it and download again"
                now != null -> "file on disk re-checked: matches Hugging Face"
                m.sha256Expected.equals(m.sha256Actual, ignoreCase = true) -> "checksum matched Hugging Face at download"
                else -> "CHECKSUM DIFFERS from Hugging Face"
            }
            sb.appendLine("File: ${m.name} · ${formatBytes(m.sizeBytes)} · $hash")
        }
        val loaded = try {
            c.localHost.loadedOrNull() ?: c.localHost.autoLoad(c.db.models().all(), settings)
        } catch (e: Exception) {
            sb.appendLine("Loading failed: ${e.message}")
            c.localHost.lastAttempts.forEach { sb.appendLine("  · $it") }
            return sb.toString()
        } ?: run {
            sb.appendLine("No phone model to check. Download one above (Recommended), then run the check.")
            return sb.toString()
        }
        val model = c.db.models().get(loaded.modelId) ?: return "The loaded model is not in the list any more."
        c.localHost.lastAttempts.forEach { sb.appendLine("Load: $it") }
        sb.appendLine("Model: ${model.name} · ${model.quantization ?: "?"} · ${formatBytes(model.sizeBytes)} · ${java.io.File(model.filePath).name}")
        sb.appendLine("Context ${loaded.contextSize} · threads $threads · loaded: ${c.localHost.loadMode(model.id)} · ${bridge.systemInfo(bridge.engineOf(loaded.handle)).trim()}")
        val questions = listOf("What is the capital of France? Answer with one word." to "paris", "What is 12 + 30? Reply with the number only." to "42")

        suspend fun round(label: String, handleName: String): Boolean {
            var allOk = true
            sb.appendLine(label)
            for ((q, expect) in questions) {
                val started = System.currentTimeMillis()
                val r = c.providers.local.complete(
                    LlmRequest(
                        handleName,
                        listOf(LlmMessage(Role.SYSTEM, "You are a helpful assistant. Answer briefly."), LlmMessage(Role.USER, q)),
                        temperature = 0.1, maxOutputTokens = 24,
                    )
                )
                val answer = r.text.trim().replace(Regex("\\s+"), " ")
                val ok = answer.contains(expect, ignoreCase = true)
                if (!ok) allOk = false
                sb.appendLine("${if (ok) "✓" else "✗"} $q → \"${answer.take(120)}\" (${r.outputTokens} tokens, ${System.currentTimeMillis() - started} ms)")
            }
            return allOk
        }

        val ok = round("Answers with $threads thread(s):", loaded.name)
        if (ok) {
            sb.appendLine("Result: the phone model answers correctly.")
        } else if (threads > 1) {
            c.localHost.unload()
            val single = c.localHost.load(model, loaded.contextSize, 1)
            val okSingle = round("Answers with 1 thread:", single.name)
            if (okSingle) {
                oneThreadHelps = true
                sb.appendLine("Result: wrong with $threads threads, correct with 1 thread. Tap \"Use 1 thread\" below to keep that.")
            } else {
                c.localHost.unload()
                c.localHost.load(model, loaded.contextSize, threads)
                sb.appendLine("Result: the answers are wrong. Please send a screenshot of this box so the problem can be fixed.")
            }
        } else {
            sb.appendLine("Result: the answers are wrong. Please send a screenshot of this box so the problem can be fixed.")
        }
        return sb.toString()
    }

    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check the phone AI", style = MaterialTheme.typography.titleSmall)
            Text(
                "Asks the phone model two easy questions and shows its answers with this phone's details. Use it if replies look like random text.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    enabled = !running && c.localHost.runtimeAvailable,
                    onClick = {
                        running = true
                        oneThreadHelps = false
                        report = null
                        scope.launch {
                            report = try {
                                runCheck()
                            } catch (e: Exception) {
                                "The check could not run: ${e.message}"
                            }
                            running = false
                        }
                    },
                    modifier = Modifier.testTag("phone-ai-check"),
                ) { Text(if (running) "Checking…" else "Run check") }
                if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            report?.let {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.testTag("phone-ai-report"))
                }
            }
            if (oneThreadHelps) {
                TextButton(onClick = {
                    scope.launch {
                        c.settings.update { it.copy(localThreads = 1, performanceMode = "ADAPTIVE") }
                        toast(context, "The phone model now uses 1 thread")
                    }
                }) { Text("Use 1 thread") }
            }
        }
    }
}

private fun sha256Of(file: java.io.File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(1 shl 20).use { input ->
        val buf = ByteArray(1 shl 20)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            digest.update(buf, 0, r)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}


/**
 * The on-phone picture model (realistic pictures without internet): its state, Download and Delete.
 * Its files are kept apart from chat models (role "picture").
 */
@Composable
private fun PictureModelCard() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val files by c.db.models().observePictureFiles().collectAsState(initial = emptyList())
    var state by remember { mutableStateOf<com.gravity.ide.ai.image.PhonePictures.State?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(files) { state = c.phonePictures.state() }
    Panel {
        Column {
            Text("Picture model on this phone", style = MaterialTheme.typography.titleSmall)
            Text(
                "Realistic pictures made on this phone, without internet: Stable Diffusion 1.5 (Realistic Vision 5.1) with the LCM " +
                    "speed-up. ${com.gravity.ide.ai.image.PhonePictures.DOWNLOAD_LABEL} once. A picture takes about 1–3 minutes on a fast " +
                    "phone and about 2.5 GB of memory. Licences: CreativeML OpenRAIL-M (model), OpenRAIL++ (LCM), MIT (TAESD).",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val st = state) {
                null -> Unit
                is com.gravity.ide.ai.image.PhonePictures.State.Unavailable ->
                    Text("Not available: ${st.why}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                com.gravity.ide.ai.image.PhonePictures.State.NotDownloaded ->
                    Button(onClick = {
                        scope.launch {
                            try {
                                c.phonePictures.download()
                                toast(context, "Downloading the picture model")
                            } catch (e: Exception) {
                                toast(context, "Download failed: ${e.message}")
                            }
                        }
                    }, modifier = Modifier.padding(top = 6.dp)) { Text("Download (${com.gravity.ide.ai.image.PhonePictures.DOWNLOAD_LABEL})") }
                is com.gravity.ide.ai.image.PhonePictures.State.Downloading -> {
                    Text(
                        if (st.failed != null) "Download stopped: ${st.failed}" else "Downloading… ${st.percent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (st.failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (st.failed != null) OutlinedButton(onClick = {
                            scope.launch { runCatching { c.phonePictures.download() }.onFailure { toast(context, "Download failed: ${it.message}") } }
                        }) { Text("Try again") }
                        OutlinedButton(onClick = { confirmDelete = true }) { Text("Cancel and delete") }
                    }
                }
                com.gravity.ide.ai.image.PhonePictures.State.Ready -> {
                    Text("Ready. Choose \"On this phone (realistic)\" in Settings → AI → Pictures and videos.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Delete (frees ${com.gravity.ide.ai.image.PhonePictures.DOWNLOAD_LABEL})") }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete the picture model?") },
        text = { Text("Its files (${com.gravity.ide.ai.image.PhonePictures.DOWNLOAD_LABEL}) are removed from this phone. You can download them again later.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { c.phonePictures.delete(); state = c.phonePictures.state() }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
}
