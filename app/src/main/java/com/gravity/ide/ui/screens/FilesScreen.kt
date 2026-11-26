@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.gravity.ide.ui.screens

import android.os.Build
import android.os.FileObserver
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gravity.ide.core.PathGuard
import com.gravity.ide.core.ZipSafety
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.di.AppContainer
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.ConfirmDialog
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.KeyValue
import com.gravity.ide.ui.components.MenuAction
import com.gravity.ide.ui.components.OverflowMenu
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.TextInputDialog
import com.gravity.ide.ui.components.formatBytes
import com.gravity.ide.ui.components.formatTime
import com.gravity.ide.ui.components.formatTimeFull
import com.gravity.ide.ui.gravityViewModel
import com.gravity.ide.ui.theme.GravityColors
import com.gravity.ide.workspace.FileNode
import com.gravity.ide.workspace.SearchHit
import com.gravity.ide.workspace.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FilesViewModel(private val c: AppContainer, val projectId: String) : ViewModel() {
    val project = MutableStateFlow<ProjectEntity?>(null)
    val dir = MutableStateFlow<File?>(null)
    val nodes = MutableStateFlow<List<FileNode>>(emptyList())
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val clipboard = MutableStateFlow<Pair<List<File>, Boolean>?>(null) // files, isCut
    val sort = MutableStateFlow(SortMode.NAME)
    val hits = MutableStateFlow<List<SearchHit>?>(null)
    val message = MutableStateFlow<String?>(null)
    private var showHidden = false

    init {
        viewModelScope.launch {
            val p = c.db.projects().get(projectId)
            project.value = p
            if (p != null) {
                c.workspace.touch(p)
                dir.value = File(p.rootPath)
                showHidden = c.settings.current().showHiddenFiles
                refresh()
            }
        }
    }

    val root: File? get() = project.value?.let { File(it.rootPath) }

    fun refresh() {
        val d = dir.value ?: return
        val r = root ?: return
        viewModelScope.launch {
            nodes.value = withContext(Dispatchers.IO) { c.workspace.list(d, r, showHidden, sort.value) }
        }
    }

    fun toggleHidden() {
        showHidden = !showHidden
        refresh()
    }

    fun open(d: File) {
        val r = root ?: return
        if (!PathGuard.isInside(r, d)) return
        dir.value = d
        selection.value = emptySet()
        refresh()
    }

    fun up() {
        val d = dir.value ?: return
        val r = root ?: return
        if (d.canonicalPath != r.canonicalPath) open(d.parentFile ?: r)
    }

    private fun op(block: suspend () -> String?) {
        viewModelScope.launch {
            message.value = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            refresh()
        }
    }

    fun newFile(name: String) = op { c.workspace.createFile(dir.value!!, name); null }
    fun newFolder(name: String) = op { c.workspace.createDirectory(dir.value!!, name); null }
    fun rename(f: File, name: String) = op {
        val p = project.value!!
        if (f.isFile) c.checkpoints.snapshot(p.id, root!!, f, "user", note = "Before rename")
        c.workspace.rename(f, name); null
    }

    fun delete(files: List<File>) = op {
        val p = project.value!!
        files.forEach { c.workspace.delete(it, p.id) }
        selection.value = emptySet()
        "${files.size} item(s) moved to trash"
    }

    fun copy(files: List<File>, cut: Boolean) {
        clipboard.value = files to cut
        selection.value = emptySet()
        message.value = "${files.size} item(s) ${if (cut) "cut" else "copied"} — open a folder and paste"
    }

    fun paste() = op {
        val (files, cut) = clipboard.value ?: return@op null
        val target = dir.value!!
        files.forEach { if (cut) c.workspace.move(it, target) else c.workspace.copy(it, target) }
        if (cut) clipboard.value = null
        "Pasted ${files.size} item(s)"
    }

    fun extract(zip: File) = op {
        val dest = File(zip.parentFile, zip.nameWithoutExtension)
        val r = zip.inputStream().use { ZipSafety.extract(it, dest) }
        "Extracted ${r.files} files" + if (r.rejected.isNotEmpty()) "; rejected unsafe: ${r.rejected.joinToString()}" else ""
    }

    fun compress(f: File) = op {
        val dest = File(f.parentFile, f.name + ".zip")
        dest.outputStream().use { out ->
            if (f.isDirectory) ZipSafety.zipDirectory(f, out, prefix = f.name + "/") else {
                java.util.zip.ZipOutputStream(out).use { z ->
                    z.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
        }
        "Created ${dest.name}"
    }

    fun search(q: String) {
        val r = root ?: return
        if (q.isBlank()) {
            hits.value = null
            return
        }
        viewModelScope.launch { hits.value = withContext(Dispatchers.IO) { c.workspace.search(r, q) } }
    }

    fun toggleSelect(f: File) {
        val key = f.absolutePath
        selection.value = if (key in selection.value) selection.value - key else selection.value + key
    }
}

@Composable
fun FilesScreen(projectId: String?, go: (String) -> Unit) {
    if (projectId == null) {
        ProjectPicker(title = "Choose a project to browse", go = { go(Routes.files(it.id)) })
        return
    }
    val vm = gravityViewModel(key = "files-$projectId") { FilesViewModel(it, projectId) }
    val context = LocalContext.current
    val project by vm.project.collectAsState()
    val dir by vm.dir.collectAsState()
    val nodes by vm.nodes.collectAsState()
    val selection by vm.selection.collectAsState()
    val clipboard by vm.clipboard.collectAsState()
    val hits by vm.hits.collectAsState()
    val message by vm.message.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<List<File>?>(null) }
    var info by remember { mutableStateOf<FileNode?>(null) }
    var query by remember { mutableStateOf("") }
    var sortOpen by remember { mutableStateOf(false) }
    val c = LocalAppContainer.current

    LaunchedEffect(message) { message?.let { toast(context, it); vm.message.value = null } }

    // Keep the listing in sync with real filesystem changes (agent edits, terminal, imports).
    val currentDir = dir
    DisposableEffect(currentDir) {
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
        val observer = currentDir?.let { d ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(d, mask) { override fun onEvent(event: Int, path: String?) = vm.refresh() }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(d.absolutePath, mask) { override fun onEvent(event: Int, path: String?) = vm.refresh() }
            }
        }
        observer?.startWatching()
        onDispose { observer?.stopWatching() }
    }

    val p = project ?: run {
        EmptyState("Project not found", "It may have been deleted.")
        return
    }
    val root = File(p.rootPath)

    Column(Modifier.fillMaxSize()) {
        // Breadcrumb + actions
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                val rel = dir?.let { PathGuard.relativize(root, it) } ?: "."
                TextButton(onClick = { vm.open(root) }) { Text(p.name, maxLines = 1) }
                if (rel != ".") {
                    var acc = root
                    for (seg in rel.split(File.separatorChar)) {
                        acc = File(acc, seg)
                        val target = acc
                        Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                        TextButton(onClick = { vm.open(target) }) { Text(seg, maxLines = 1) }
                    }
                }
            }
            IconButton(onClick = { dialog = "file" }) { Icon(Icons.Filled.NoteAdd, "New file") }
            IconButton(onClick = { dialog = "folder" }) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
            OverflowMenu(
                listOf(
                    MenuAction("Refresh", Icons.Filled.Refresh) { vm.refresh() },
                    MenuAction("Sort…", Icons.Filled.Sort) { sortOpen = true },
                    MenuAction("Show/hide hidden files", Icons.Filled.Visibility) { vm.toggleHidden() },
                    MenuAction("Terminal here", Icons.Filled.Terminal) { go(Routes.terminal(dir?.absolutePath)) },
                    MenuAction("Agent task on project", Icons.Filled.SmartToy) { go(Routes.agentNew(p.id)) },
                    MenuAction("Preview in browser", Icons.Filled.Public) { go(Routes.browser("gravity-project:${p.id}")) },
                )
            )
        }
        OutlinedTextField(
            query, { query = it; vm.search(it) }, placeholder = { Text("Search in project files") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; vm.search("") }) { Icon(Icons.Filled.Close, "Clear") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        if (selection.isNotEmpty() || clipboard != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selection.isNotEmpty()) {
                    Text("${selection.size} selected", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    val files = selection.map { File(it) }
                    IconButton(onClick = { vm.copy(files, false) }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                    IconButton(onClick = { vm.copy(files, true) }) { Icon(Icons.Filled.ContentCut, "Cut") }
                    IconButton(onClick = { deleting = files }) { Icon(Icons.Filled.Delete, "Delete", tint = GravityColors.Red) }
                    IconButton(onClick = { vm.selection.value = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") }
                } else {
                    Text("${clipboard!!.first.size} item(s) on clipboard", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    AssistChip(onClick = { vm.paste() }, label = { Text("Paste here") }, leadingIcon = { Icon(Icons.Filled.ContentPaste, null) })
                    IconButton(onClick = { vm.clipboard.value = null }) { Icon(Icons.Filled.Close, "Clear clipboard") }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))

        val searchHits = hits
        if (searchHits != null) {
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                item { Text("${searchHits.size} matches", style = MaterialTheme.typography.labelMedium) }
                items(searchHits) { h ->
                    Column(Modifier.fillMaxWidth().combinedClickable(onClick = { go(Routes.editor(p.id, h.relPath)) }).padding(vertical = 6.dp)) {
                        Text("${h.relPath}:${h.line}", style = MaterialTheme.typography.labelMedium, color = GravityColors.Cyan)
                        Text(h.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            if (dir?.canonicalPath != root.canonicalPath) item {
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.up() }).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  ..", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (nodes.isEmpty()) item { EmptyState("Empty folder", "Create a file or folder, or ask the agent to scaffold code here.") }
            items(nodes, key = { it.file.absolutePath }) { n ->
                val selected = n.file.absolutePath in selection
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) GravityColors.Cyan.copy(alpha = 0.10f) else MaterialTheme.colorScheme.background)
                        .combinedClickable(
                            onClick = {
                                when {
                                    selection.isNotEmpty() -> vm.toggleSelect(n.file)
                                    n.isDirectory -> vm.open(n.file)
                                    n.name.endsWith(".apk", true) -> go(Routes.devices(n.file.absolutePath))
                                    isImage(n.name) -> info = n
                                    else -> go(Routes.editor(p.id, n.relPath))
                                }
                            },
                            onLongClick = { vm.toggleSelect(n.file) },
                        )
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (n.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null,
                        tint = if (n.isDirectory) GravityColors.Blue else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(n.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (n.isDirectory) "${n.childCount} items · ${formatTime(n.lastModified)}" else "${formatBytes(n.size)} · ${formatTime(n.lastModified)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val actions = mutableListOf(
                        MenuAction("Rename", Icons.Filled.Edit) { renaming = n.file },
                        MenuAction("Copy", Icons.Filled.ContentCopy) { vm.copy(listOf(n.file), false) },
                        MenuAction("Cut / Move", Icons.Filled.ContentCut) { vm.copy(listOf(n.file), true) },
                        MenuAction("Compress to ZIP", Icons.Filled.FolderZip) { vm.compress(n.file) },
                        MenuAction("Info", Icons.Filled.Info) { info = n },
                        MenuAction("Delete", Icons.Filled.Delete, destructive = true) { deleting = listOf(n.file) },
                    )
                    if (n.name.endsWith(".zip", true)) actions.add(0, MenuAction("Extract here", Icons.Filled.Unarchive) { vm.extract(n.file) })
                    if (n.name.endsWith(".apk", true)) actions.add(0, MenuAction("Install on this device", Icons.Filled.InstallMobile) { go(Routes.devices(n.file.absolutePath)) })
                    if (n.name.endsWith(".html", true)) actions.add(0, MenuAction("Preview", Icons.Filled.Public) { go(Routes.browser("gravity-project:${p.id}/${n.relPath}")) })
                    OverflowMenu(actions)
                }
            }
        }
    }

    when (dialog) {
        "file" -> TextInputDialog("New file", "File name (e.g. Main.kt)", confirmLabel = "Create", onConfirm = { vm.newFile(it) }, onDismiss = { dialog = null })
        "folder" -> TextInputDialog("New folder", "Folder name", confirmLabel = "Create", onConfirm = { vm.newFolder(it) }, onDismiss = { dialog = null })
    }
    renaming?.let { f -> TextInputDialog("Rename", "New name", f.name, "Rename", onConfirm = { vm.rename(f, it) }, onDismiss = { renaming = null }) }
    deleting?.let { files ->
        ConfirmDialog("Delete ${files.size} item(s)?", "Items are moved to Yamini's trash and can be restored.", "Delete", destructive = true, onConfirm = { vm.delete(files) }, onDismiss = { deleting = null })
    }
    info?.let { n ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(n.name) },
            text = {
                Column {
                    if (isImage(n.name)) {
                        val bmp = remember(n.file) { android.graphics.BitmapFactory.decodeFile(n.file.absolutePath)?.asImageBitmap() }
                        if (bmp != null) androidx.compose.foundation.Image(bmp, n.name, modifier = Modifier.fillMaxWidth())
                    }
                    KeyValue("Path", n.relPath, mono = true)
                    KeyValue("Type", if (n.isDirectory) "Folder" else n.name.substringAfterLast('.', "file").uppercase())
                    KeyValue("Size", if (n.isDirectory) formatBytes(c.workspace.storageUsage(n.file)) else formatBytes(n.size))
                    KeyValue("Modified", formatTimeFull(n.lastModified))
                    if (n.isDirectory) KeyValue("Items", n.childCount.toString())
                }
            },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Close") } },
        )
    }
    if (sortOpen) {
        AlertDialog(
            onDismissRequest = { sortOpen = false },
            title = { Text("Sort by") },
            text = {
                Column {
                    SortMode.entries.forEach { m ->
                        TextButton(onClick = { vm.sort.value = m; vm.refresh(); sortOpen = false }) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

@Composable
fun ProjectPicker(title: String, go: (ProjectEntity) -> Unit) {
    val c = LocalAppContainer.current
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(title, style = MaterialTheme.typography.titleMedium) }
        if (projects.isEmpty()) item { EmptyState("No projects", "Create a project first (Projects → New).") }
        items(projects, key = { it.id }) { p ->
            Panel(onClick = { go(p) }) {
                Column {
                    Text(p.name)
                    Text(p.type.lowercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
