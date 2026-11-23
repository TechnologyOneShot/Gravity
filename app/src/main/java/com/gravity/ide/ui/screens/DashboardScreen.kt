@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravity.ide.agent.TaskStatus
import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.SectionHeader
import com.gravity.ide.ui.components.StatusPill
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.components.statusColor
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.launch
import java.io.File

private data class QuickAction(val label: String, val icon: ImageVector, val route: String)

private val quickActions = listOf(
    QuickAction("New Chat", Icons.Filled.AddComment, Routes.NEW_CHAT),
    QuickAction("New Project", Icons.Filled.PostAdd, Routes.PROJECTS_NEW),
    QuickAction("New Folder", Icons.Filled.CreateNewFolder, "files"),
    QuickAction("New File", Icons.Filled.NoteAdd, "files"),
    QuickAction("Workspace", Icons.Filled.FolderOpen, Routes.WORKSPACES),
    QuickAction("Terminal", Icons.Filled.Terminal, "terminal"),
    QuickAction("Models", Icons.Filled.Memory, Routes.models(0)),
    QuickAction("Downloads", Icons.Filled.Download, Routes.models(1)),
    QuickAction("Build APK", Icons.Filled.Build, "build"),
    QuickAction("Device Test", Icons.Filled.PhoneAndroid, Routes.TESTING),
    QuickAction("Export", Icons.Filled.Upload, Routes.EXPORT),
    QuickAction("Settings", Icons.Filled.Settings, Routes.SETTINGS),
)

private val widgetNames = linkedMapOf(
    "status" to "System status",
    "quick" to "Quick actions",
    "tasks" to "Running tasks",
    "projects" to "Recent projects",
    "chats" to "Recent chats",
    "builds" to "Recent builds",
    "screenshots" to "Recent screenshots",
    "activity" to "Recent activity",
)

@Composable
fun DashboardScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    val chats by c.db.chats().observe(false).collectAsState(initial = emptyList())
    val tasks by c.db.tasks().observe().collectAsState(initial = emptyList())
    val builds by c.db.builds().observe().collectAsState(initial = emptyList())
    val shots by c.db.screenshots().observe().collectAsState(initial = emptyList())
    val events by c.db.events().observeRecent(12).collectAsState(initial = emptyList())
    val providers by c.db.providers().observe().collectAsState(initial = emptyList())
    val network by c.network.state.collectAsState()
    val local by c.localHost.state.collectAsState()
    val runningBuilds by c.builds.running.collectAsState()
    var customizing by remember { mutableStateOf(false) }
    val w = settings.dashboardWidgets
    val activeStatuses = setOf(TaskStatus.RUNNING.name, TaskStatus.PAUSED.name, TaskStatus.WAITING_APPROVAL.name, TaskStatus.WAITING_NETWORK.name, TaskStatus.INTERRUPTED.name, TaskStatus.PENDING.name)
    val activeTasks = tasks.filter { it.status in activeStatuses }

    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Welcome to Gravity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Describe what to build. Gravity plans, edits, runs and verifies.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { customizing = true }) { Icon(Icons.Filled.Tune, "Customize dashboard") }
            }
        }
        if ("status" in w) item {
            SectionHeader("Status")
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusRow("Network", if (network.online) "Online · ${network.transport}" else "Offline", if (network.online) "ONLINE" else "OFFLINE")
                    StatusRow("Mode", settings.operatingMode.label, "ACTIVE")
                    val localText = when (val st = local) {
                        is LocalModelHost.State.Ready -> "Loaded: ${st.loaded.name}"
                        is LocalModelHost.State.Loading -> "Loading ${st.name}…"
                        is LocalModelHost.State.Failed -> "Failed: ${st.message.take(60)}"
                        LocalModelHost.State.Idle -> if (c.localHost.runtimeAvailable) "No model loaded" else "Runtime not bundled"
                    }
                    StatusRow("Local AI", localText, if (local is LocalModelHost.State.Ready) "READY" else "IDLE")
                    val cloud = providers.filter { it.enabled && it.kind != "LOCAL" }
                    StatusRow("Cloud AI", if (cloud.isEmpty()) "No provider configured" else cloud.joinToString { it.name }, if (cloud.isEmpty()) "IDLE" else cloud.first().lastStatus?.let { if (it.startsWith("OK")) "OK" else "WARN" } ?: "IDLE")
                    StatusRow("Builds", if (runningBuilds.isEmpty()) (builds.firstOrNull()?.let { "Last: ${it.status.lowercase()} ${relativeTime(it.startedAt)}" } ?: "No builds yet") else "${runningBuilds.size} running", if (runningBuilds.isEmpty()) (builds.firstOrNull()?.status ?: "IDLE") else "RUNNING")
                    StatusRow("Device bridge", "Same-device install via PackageInstaller", "ACTIVE")
                    TextButton(onClick = { go(Routes.OFFLINE) }) { Text("Offline readiness →") }
                }
            }
        }
        if ("quick" in w) item {
            SectionHeader("Quick actions")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in quickActions.chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (a in row) {
                            Column(
                                Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                                    .clickable { go(a.route) }.padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(a.icon, null, tint = GravityColors.Cyan, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(a.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if ("tasks" in w) item {
            SectionHeader("Running tasks") { TextButton(onClick = { go(Routes.TASKS) }) { Text("All") } }
            if (activeTasks.isEmpty()) {
                Text("No active agent tasks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    activeTasks.take(5).forEach { t ->
                        Panel(onClick = { go(Routes.task(t.id)) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(t.stage ?: "Step ${t.stepCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                StatusPill(t.status)
                            }
                        }
                    }
                }
            }
        }
        if ("projects" in w) item {
            SectionHeader("Recent projects") { TextButton(onClick = { go(Routes.PROJECTS_LIST) }) { Text("All") } }
            if (projects.isEmpty()) {
                Panel(onClick = { go(Routes.PROJECTS_NEW) }) { Text("Create your first project →", color = GravityColors.Cyan) }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(projects.take(10), key = { it.id }) { p ->
                        Column(
                            Modifier.width(160.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                                .clickable { go(Routes.files(p.id)) }.padding(12.dp),
                        ) {
                            Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.type.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = GravityColors.Purple)
                            Text(relativeTime(maxOf(p.lastOpenedAt, p.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if ("chats" in w) item {
            SectionHeader("Recent chats") { TextButton(onClick = { go(Routes.CHATS) }) { Text("All") } }
            if (chats.isEmpty()) Text("No chats yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            chats.take(4).forEach { ch ->
                Row(Modifier.fillMaxWidth().clickable { go(Routes.chat(ch.id)) }.padding(vertical = 8.dp)) {
                    Text(ch.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeTime(ch.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if ("builds" in w && builds.isNotEmpty()) item {
            SectionHeader("Recent builds") { TextButton(onClick = { go("build") }) { Text("Build Center") } }
            builds.take(3).forEach { b ->
                val project = projects.firstOrNull { it.id == b.projectId }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(project?.name ?: "Project", maxLines = 1)
                        Text(b.command, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    StatusPill(b.status)
                }
            }
        }
        if ("screenshots" in w && shots.isNotEmpty()) item {
            SectionHeader("Recent screenshots") { TextButton(onClick = { go(Routes.SCREENSHOTS) }) { Text("Gallery") } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shots.take(8), key = { it.id }) { s ->
                    val bmp = remember(s.filePath) {
                        File(s.filePath).takeIf { it.exists() }?.let { f ->
                            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
                        }
                    }
                    Box(Modifier.width(90.dp).aspectRatio(0.5f).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).clickable { go(Routes.SCREENSHOTS) }) {
                        if (bmp != null) Image(bmp, s.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if ("activity" in w) item {
            SectionHeader("Recent activity") { TextButton(onClick = { go(Routes.ACTIVITY) }) { Text("All") } }
            if (events.isEmpty()) Text("No activity yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            events.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(statusColor(e.level), RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(e.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeTime(e.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (customizing) {
        var selected by remember { mutableStateOf(w) }
        AlertDialog(
            onDismissRequest = { customizing = false },
            title = { Text("Dashboard widgets") },
            text = {
                Column {
                    for ((key, label) in widgetNames) {
                        Row(Modifier.fillMaxWidth().clickable { selected = if (key in selected) selected - key else selected + key }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in selected, onCheckedChange = { selected = if (it) selected + key else selected - key })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { c.settings.update { it.copy(dashboardWidgets = selected) } }
                    customizing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { customizing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String, status: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(8.dp).background(statusColor(status), RoundedCornerShape(4.dp)))
    }
}
