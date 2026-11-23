@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.di.AppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.ConfirmDialog
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.MenuAction
import com.gravity.ide.ui.components.OverflowMenu
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.TextInputDialog
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.gravityViewModel
import com.gravity.ide.ui.theme.GravityColors
import com.gravity.ide.workspace.ProjectTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

class ProjectsViewModel(private val c: AppContainer) : ViewModel() {
    val projects = c.db.projects().observeAll()
    val message = MutableStateFlow<String?>(null)

    private fun launchOp(label: String, block: suspend () -> String?) {
        viewModelScope.launch {
            message.value = try {
                block()
            } catch (e: Exception) {
                "$label failed: ${e.message}"
            }
        }
    }

    fun create(name: String, template: ProjectTemplate, onCreated: (ProjectEntity) -> Unit) = launchOp("Create") {
        val p = c.workspace.createProject(name, template)
        onCreated(p)
        "Created ${p.name}"
    }

    fun update(p: ProjectEntity) = launchOp("Update") {
        c.db.projects().upsert(p.copy(updatedAt = System.currentTimeMillis()))
        null
    }

    fun rename(p: ProjectEntity, name: String) = update(p.copy(name = name.trim()))

    fun duplicate(p: ProjectEntity) = launchOp("Duplicate") {
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val src = File(p.rootPath)
            val dest = File(c.workspace.workspacesRoot, src.name.substringBeforeLast('-') + "-copy-" + id.take(8))
            src.copyRecursively(dest)
            c.db.projects().upsert(p.copy(id = id, name = p.name + " copy", rootPath = dest.absolutePath, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), pinned = false))
            "Duplicated as ${p.name} copy"
        }
    }

    fun delete(p: ProjectEntity) = launchOp("Delete") {
        c.workspace.delete(File(p.rootPath), p.id)
        c.db.projects().delete(p)
        "${p.name} moved to trash (restore it from Backup & Recovery)"
    }

    fun importZip(uri: Uri, name: String) = launchOp("Import") {
        val p = c.workspace.createProject(name, ProjectTemplate.EMPTY)
        val r = c.workspace.importZip(uri, File(p.rootPath))
        "Imported ${r.files} files into ${p.name}" + if (r.rejected.isNotEmpty()) " (${r.rejected.size} unsafe entries rejected)" else ""
    }

    fun importFolder(uri: Uri, name: String) = launchOp("Import") {
        val p = c.workspace.createProject(name, ProjectTemplate.EMPTY)
        val n = c.workspace.importTree(uri, File(p.rootPath))
        c.db.projects().upsert(p.copy(sourceTreeUri = uri.toString()))
        "Copied $n files into ${p.name}"
    }

    fun exportZip(p: ProjectEntity, uri: Uri) = launchOp("Export") {
        val n = c.workspace.exportZip(File(p.rootPath), uri)
        "Exported $n files"
    }
}

@Composable
fun ProjectsScreen(openNew: Boolean, go: (String) -> Unit) {
    val vm = gravityViewModel { ProjectsViewModel(it) }
    val context = LocalContext.current
    val projects by vm.projects.collectAsState(initial = emptyList())
    val message by vm.message.collectAsState()
    var showNew by remember { mutableStateOf(openNew) }
    var showArchived by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectEntity?>(null) }
    var deleting by remember { mutableStateOf<ProjectEntity?>(null) }
    var editing by remember { mutableStateOf<ProjectEntity?>(null) }
    var exporting by remember { mutableStateOf<ProjectEntity?>(null) }

    LaunchedEffect(message) { message?.let { toast(context, it); vm.message.value = null } }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importZip(uri, "Imported ${System.currentTimeMillis() % 100000}")
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.importFolder(uri, uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "Imported folder")
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val p = exporting
        if (uri != null && p != null) vm.exportZip(p, uri)
        exporting = null
    }

    val visible = projects.filter { it.archived == showArchived }.sortedWith(compareByDescending<ProjectEntity> { it.pinned }.thenByDescending { it.favorite }.thenByDescending { maxOf(it.lastOpenedAt, it.updatedAt) })

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showNew = true }) { Icon(Icons.Filled.Add, null); Text(" New") }
                OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Open folder") }
                OutlinedButton(onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("Import ZIP") }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showArchived, onClick = { showArchived = false }, label = { Text("Active") })
                FilterChip(selected = showArchived, onClick = { showArchived = true }, label = { Text("Archived") })
            }
        }
        if (visible.isEmpty()) item {
            EmptyState(if (showArchived) "No archived projects" else "No projects yet", "Create a project from a template, open a folder or import a ZIP.")
        }
        items(visible, key = { it.id }) { p ->
            Panel(onClick = { go(if (p.type == "APP") Routes.app(p.id) else Routes.files(p.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.pinned) Icon(Icons.Filled.PushPin, null, tint = GravityColors.Cyan, modifier = Modifier.padding(end = 4.dp))
                            if (p.favorite) Icon(Icons.Filled.Star, null, tint = GravityColors.Amber, modifier = Modifier.padding(end = 4.dp))
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            "${p.type.lowercase()} · opened ${relativeTime(p.lastOpenedAt)}" + if (p.localOnly) " · local-only" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OverflowMenu(
                        listOf(
                            MenuAction("Open files", Icons.Filled.FolderOpen) { go(Routes.files(p.id)) },
                            MenuAction("Agent task", Icons.Filled.SmartToy) { go(Routes.agentNew(p.id)) },
                            MenuAction("Terminal here", Icons.Filled.Terminal) { go(Routes.terminal(p.rootPath)) },
                            MenuAction("Build", Icons.Filled.Build) { go(Routes.build(p.id)) },
                            MenuAction("Rename", Icons.Filled.Edit) { renaming = p },
                            MenuAction(if (p.pinned) "Unpin" else "Pin", Icons.Filled.PushPin) { vm.update(p.copy(pinned = !p.pinned)) },
                            MenuAction(if (p.favorite) "Remove favorite" else "Favorite", if (p.favorite) Icons.Filled.StarBorder else Icons.Filled.Star) { vm.update(p.copy(favorite = !p.favorite)) },
                            MenuAction("Duplicate", Icons.Filled.ContentCopy) { vm.duplicate(p) },
                            MenuAction(if (p.archived) "Unarchive" else "Archive", if (p.archived) Icons.Filled.Unarchive else Icons.Filled.Archive) { vm.update(p.copy(archived = !p.archived)) },
                            MenuAction("Export ZIP", Icons.Filled.Upload) {
                                exporting = p
                                exportLauncher.launch("${p.name}.zip")
                            },
                            MenuAction("Project settings", Icons.Filled.Settings) { editing = p },
                            MenuAction("Delete", Icons.Filled.Delete, destructive = true) { deleting = p },
                        )
                    )
                }
            }
        }
    }

    if (showNew) NewProjectDialog(onDismiss = { showNew = false }) { name, template ->
        vm.create(name, template) { go(Routes.files(it.id)) }
    }
    renaming?.let { p -> TextInputDialog("Rename project", "Name", p.name, "Rename", onConfirm = { vm.rename(p, it) }, onDismiss = { renaming = null }) }
    deleting?.let { p ->
        ConfirmDialog("Delete ${p.name}?", "The project folder is moved to Gravity's trash and can be restored from Backup & Recovery.", "Delete", destructive = true, onConfirm = { vm.delete(p) }, onDismiss = { deleting = null })
    }
    editing?.let { p -> ProjectSettingsDialog(p, onDismiss = { editing = null }) { vm.update(it) } }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, ProjectTemplate) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(ProjectTemplate.ANDROID_COMPOSE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Selector("Template", ProjectTemplate.entries.toList(), template, { it.label }, { template = it }, Modifier.fillMaxWidth())
                Text(
                    when (template) {
                        ProjectTemplate.ANDROID_COMPOSE -> "Complete Gradle project. Building it needs an Android toolchain (see Build Center feasibility)."
                        ProjectTemplate.WEB_STATIC, ProjectTemplate.WEB_GAME -> "Builds and previews fully on-device."
                        else -> "Starter files plus GRAVITY.md for agent instructions."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onCreate(name.trim(), template)
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProjectSettingsDialog(p: ProjectEntity, onDismiss: () -> Unit, onSave: (ProjectEntity) -> Unit) {
    var build by remember { mutableStateOf(p.buildCommand.orEmpty()) }
    var artifact by remember { mutableStateOf(p.artifactPattern.orEmpty()) }
    var test by remember { mutableStateOf(p.testCommand.orEmpty()) }
    var localOnly by remember { mutableStateOf(p.localOnly) }
    var backend by remember { mutableStateOf(p.buildBackend) }
    val backends = listOf<Pair<String?, String>>(null to "Automatic") + com.gravity.ide.build.BuildKind.entries.map { it.name to it.label }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(build, { build = it }, label = { Text("Build command") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artifact, { artifact = it }, label = { Text("Expected artifact (glob)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(test, { test = it }, label = { Text("Test command") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Selector("Build backend", backends, backends.firstOrNull { it.first == backend }, { it.second }, { backend = it.first }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Local-only project")
                        Text("Never send this project's files to cloud AI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(localOnly, { localOnly = it })
                }
                Spacer(Modifier.height(2.dp))
                Text(p.rootPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(p.copy(buildCommand = build.ifBlank { null }, artifactPattern = artifact.ifBlank { null }, testCommand = test.ifBlank { null }, localOnly = localOnly, buildBackend = backend))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
