@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gravity.ide.agent.TaskStatus
import com.gravity.ide.core.ZipSafety
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.data.db.TrashEntity
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.ConfirmDialog
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.MessageBanner
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.SectionHeader
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.formatBytes
import com.gravity.ide.ui.components.formatTimeFull
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val events by c.db.events().observeRecent(1000).collectAsState(initial = emptyList())
    var category by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val categories = listOf("ALL") + events.map { it.category }.distinct().sorted()
    val shown = events.filter { (category == "ALL" || it.category == category) && (query.isBlank() || it.title.contains(query, true) || it.detail.contains(query, true)) }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search activity") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.forEach { cat -> FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat.lowercase()) }) }
            }
            Text("Every entry is written by the component that performed the operation (tool calls, commands, builds, downloads, network changes).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown.isEmpty()) item { EmptyState("No activity", "") }
        items(shown, key = { it.id }) { e ->
            EventRow(e, expanded == e.id) { expanded = if (expanded == e.id) null else e.id }
            if (expanded == e.id && e.taskId != null) TextButton(onClick = { go(Routes.task(e.taskId)) }) { Text("Open task") }
        }
    }
}

@Composable
fun NotificationsScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val notes by c.db.events().observeNotifications().collectAsState(initial = emptyList())
    var expanded by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { c.db.events().markAllRead() }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item { Row { Text("Warnings, errors and completed tasks", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f)); TextButton(onClick = { scope.launch { c.db.events().markAllRead() } }) { Text("Mark read") } } }
        if (notes.isEmpty()) item { EmptyState("All clear", "No notifications.") }
        items(notes, key = { it.id }) { e ->
            EventRow(e, expanded == e.id) { expanded = if (expanded == e.id) null else e.id }
            if (e.taskId != null) TextButton(onClick = { go(Routes.task(e.taskId)) }) { Text("Open task") }
        }
    }
}

/** Writes through SAF, then reads the destination size back to verify the export exists. */
private suspend fun exportTo(context: Context, uri: Uri, write: suspend (OutputStream) -> Unit): String = withContext(Dispatchers.IO) {
    val out = context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot open destination")
    out.use { write(it) }
    var name = uri.lastPathSegment ?: uri.toString()
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) {
            name = cur.getString(0) ?: name
            if (!cur.isNull(1)) size = cur.getLong(1)
        }
    }
    if (size == 0L) throw IllegalStateException("Export produced an empty file")
    "Saved $name (${if (size > 0) formatBytes(size) else "size unknown"})\n${uri}"
}

@Composable
fun ExportScreen() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    val chats by c.db.chats().observe(false).collectAsState(initial = emptyList())
    val builds by c.db.builds().observe().collectAsState(initial = emptyList())
    var project by remember { mutableStateOf<ProjectEntity?>(null) }
    var chatId by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }
    val latestApk = builds.firstOrNull { it.artifactPath?.endsWith(".apk") == true && File(it.artifactPath).exists() }
    val latestWeb = builds.firstOrNull { it.kind == "STATIC_WEB" && it.artifactPath != null && File(it.artifactPath).exists() }

    fun run(uri: Uri?, write: suspend (OutputStream) -> Unit) {
        if (uri == null) return
        busy = true
        scope.launch {
            result = try { exportTo(context, uri, write) } catch (e: Exception) { "Export failed: ${e.message}" }
            busy = false
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        when (pending) {
            "project" -> project?.let { p -> run(uri) { ZipSafety.zipDirectory(File(p.rootPath), it, exclude = ZipSafety.DEFAULT_EXCLUDES) } }
            "web" -> latestWeb?.artifactPath?.let { path -> run(uri) { o -> File(path).inputStream().use { it.copyTo(o) } } }
            "screens" -> run(uri) { ZipSafety.zipDirectory(c.screenshots.dir, it) }
        }
    }
    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        latestApk?.artifactPath?.let { path -> run(uri) { o -> File(path).inputStream().use { it.copyTo(o) } } }
    }
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        when (pending) {
            "chat" -> chatId?.let { id ->
                run(uri) { o ->
                    val ch = c.db.chats().get(id)
                    val md = buildString {
                        append("# ").append(ch?.title ?: "Chat").append("\n\n")
                        c.db.chats().messages(id).forEach { m ->
                            append("## ").append(m.role).append(" · ").append(formatTimeFull(m.createdAt)).append(m.model?.let { " · $it" } ?: "").append("\n\n")
                            append(m.content).append("\n\n")
                        }
                    }
                    o.write(md.toByteArray())
                }
            }
            "logs" -> run(uri) { o ->
                val text = c.db.events().recent(5000).joinToString("\n") { "${formatTimeFull(it.createdAt)} [${it.level}] ${it.category} ${it.title}${if (it.detail.isNotBlank()) "\n    " + it.detail.replace("\n", "\n    ") else ""}" }
                o.write(text.toByteArray())
            }
            "builds" -> run(uri) { o ->
                val text = builds.joinToString("\n\n") { b ->
                    "## Build #${b.id} — ${b.status}\n- Project: ${projects.firstOrNull { it.id == b.projectId }?.name}\n- Command: `${b.command}`\n- Exit: ${b.exitCode}\n- Started: ${formatTimeFull(b.startedAt)}\n- Artifact: ${b.artifactPath ?: "—"}\n${b.error?.let { "- Error: $it\n" } ?: ""}\n```\n${b.log.takeLast(4000)}\n```"
                }
                o.write(("# Gravity build report\n\n$text").toByteArray())
            }
            "tests" -> run(uri) { o ->
                val rows = c.db.testResults().recent(1000)
                val text = buildString {
                    append("# Gravity test report\n\nGenerated ${formatTimeFull(System.currentTimeMillis())}\n\n| Suite | Test | Expected | Actual | Result |\n|---|---|---|---|---|\n")
                    rows.forEach { r -> append("| ${r.suite} | ${r.name} | ${r.expected.replace("|", "/")} | ${r.actual.replace("|", "/").take(200)} | ${r.outcome} |\n") }
                }
                o.write(text.toByteArray())
            }
        }
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val p = project
        if (uri != null && p != null) {
            busy = true
            scope.launch {
                result = try { "Copied ${c.workspace.exportTree(File(p.rootPath), uri)} files to the selected folder\n$uri" } catch (e: Exception) { "Export failed: ${e.message}" }
                busy = false
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        result?.let { item { MessageBanner(it, isError = it.startsWith("Export failed")) { result = null } } }
        item {
            SectionHeader("Project")
            Selector("Project", projects, project, { it.name }, { project = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(enabled = project != null, onClick = { pending = "project"; zipLauncher.launch("${project!!.name}-$stamp.zip") }) { Text("Source ZIP") }
                OutlinedButton(enabled = project != null, onClick = { treeLauncher.launch(null) }) { Text("Copy to folder") }
            }
        }
        item {
            SectionHeader("Build artifacts")
            Text(latestApk?.let { "Latest APK: ${File(it.artifactPath!!).name}" } ?: "No APK artifact yet (build one or install one built elsewhere).", style = MaterialTheme.typography.bodySmall)
            Button(enabled = latestApk != null, onClick = { apkLauncher.launch(File(latestApk!!.artifactPath!!).name) }) { Text("Export APK") }
            Text(latestWeb?.let { "Latest web build: ${File(it.artifactPath!!).name}" } ?: "No web build yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            Button(enabled = latestWeb != null, onClick = { pending = "web"; zipLauncher.launch(File(latestWeb!!.artifactPath!!).name) }) { Text("Export web build") }
            Text("AAB export: a bundle needs an Android toolchain (Termux companion or CI). Gravity's own device shell cannot build one (see Build Center).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            SectionHeader("Conversations & reports")
            Selector("Chat", chats, chats.firstOrNull { it.id == chatId }, { it.title }, { chatId = it.id }, Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = chatId != null, onClick = { pending = "chat"; textLauncher.launch("chat-$stamp.md") }) { Text("Chat") }
                OutlinedButton(onClick = { pending = "logs"; textLauncher.launch("gravity-logs-$stamp.md") }) { Text("Logs") }
                OutlinedButton(onClick = { pending = "builds"; textLauncher.launch("build-report-$stamp.md") }) { Text("Build report") }
                OutlinedButton(onClick = { pending = "tests"; textLauncher.launch("test-report-$stamp.md") }) { Text("Test report") }
                OutlinedButton(onClick = { pending = "screens"; zipLauncher.launch("screenshots-$stamp.zip") }) { Text("Screenshots") }
            }
        }
        item { Text("Full backups (projects + chats + tasks, optionally encrypted) are in Backup & Recovery.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun BackupScreen() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val trash by c.db.trash().observe().collectAsState(initial = emptyList())
    val versions by c.db.fileVersions().observeRecent().collectAsState(initial = emptyList())
    val tasks by c.db.tasks().observe().collectAsState(initial = emptyList())
    val projects by c.db.projects().observeAll().collectAsState(initial = emptyList())
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var purge by remember { mutableStateOf<TrashEntity?>(null) }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                result = try {
                    val s = c.backups.createBackup(uri, password.toCharArray().takeIf { it.isNotEmpty() })
                    "Backup saved: ${s.projects} projects, ${s.chats} chats, ${s.messages} messages, ${s.tasks} tasks (${formatBytes(s.bytes)})${if (s.encrypted) ", encrypted" else ""}\n$uri"
                } catch (e: Exception) { "Backup failed: ${e.message}" }
                busy = false
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                result = try {
                    val s = c.backups.restore(uri, password.toCharArray().takeIf { it.isNotEmpty() })
                    "Restored ${s.projects} projects, ${s.chats} chats, ${s.messages} messages, ${s.tasks} tasks"
                } catch (e: Exception) { "Restore failed: ${e.message}" }
                busy = false
            }
        }
    }
    val interrupted = tasks.filter { it.status == TaskStatus.INTERRUPTED.name }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        result?.let { item { MessageBanner(it, isError = it.contains("failed")) { result = null } } }
        item {
            SectionHeader("Backup")
            OutlinedTextField(
                password, { password = it }, label = { Text("Password (optional, encrypts the backup)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { createLauncher.launch("gravity-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.gvb") }) { Text("Create backup") }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) { Text("Restore…") }
            }
            Text(
                "Includes project files, chats, teams and task history. Excludes API keys (bound to this device's Keystore) and model files (re-download them; names and checksums are listed in the backup).",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (interrupted.isNotEmpty()) {
            item { SectionHeader("Interrupted tasks") }
            items(interrupted, key = { it.id }) { t ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Stopped at step ${t.stepCount} · ${relativeTime(t.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { c.agent.start(t.id) }) { Text("Resume") }
                    }
                }
            }
        }
        item { SectionHeader("Trash (${trash.size})") }
        if (trash.isEmpty()) item { Text("Trash is empty.", style = MaterialTheme.typography.bodySmall) }
        items(trash, key = { it.id }) { t ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(File(t.originalPath).name, maxLines = 1)
                        Text("${if (t.isDirectory) "folder" else "file"} · deleted ${relativeTime(t.deletedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                val restored = c.workspace.restoreFromTrash(t.id)
                                // A deleted project folder is re-registered as a project.
                                val pid = t.projectId
                                if (pid != null && t.isDirectory && c.db.projects().get(pid) == null && t.originalPath.startsWith(c.workspace.workspacesRoot.absolutePath) && File(t.originalPath).parentFile == c.workspace.workspacesRoot) {
                                    c.db.projects().upsert(
                                        ProjectEntity(id = pid, name = restored.name.substringBeforeLast('-'), type = "GENERIC", rootPath = restored.absolutePath, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
                                    )
                                }
                                toast(context, "Restored ${restored.name}")
                            } catch (e: Exception) {
                                toast(context, "Restore failed: ${e.message}")
                            }
                        }
                    }) { Text("Restore") }
                    TextButton(onClick = { purge = t }) { Text("Delete", color = GravityColors.Red) }
                }
            }
        }
        item { SectionHeader("Recent file versions") }
        if (versions.isEmpty()) item { Text("No versions yet. A version is saved before every editor save and agent edit.", style = MaterialTheme.typography.bodySmall) }
        items(versions.take(60), key = { "v" + it.id }) { v ->
            val p = projects.firstOrNull { it.id == v.projectId }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.relPath, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text("${p?.name ?: "?"} · ${v.source}${v.agentRole?.let { " · $it" } ?: ""} · ${relativeTime(v.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p != null) TextButton(onClick = {
                    scope.launch {
                        try {
                            c.checkpoints.restore(v.id, File(p.rootPath), p.id)
                            toast(context, "Restored ${v.relPath}")
                        } catch (e: Exception) {
                            toast(context, "Restore failed: ${e.message}")
                        }
                    }
                }) { Text("Restore") }
            }
        }
    }
    purge?.let { t ->
        ConfirmDialog("Delete permanently?", "${File(t.originalPath).name} cannot be recovered afterwards.", "Delete", destructive = true, onConfirm = { scope.launch { c.workspace.purgeTrash(t.id) } }, onDismiss = { purge = null })
    }
}
