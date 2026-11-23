@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.gravity.ide.agent.TaskStatus
import com.gravity.ide.ai.ReasoningMode
import com.gravity.ide.ai.local.LocalLlamaProvider
import com.gravity.ide.data.db.EventEntity
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.ConfirmDialog
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.KeyValue
import com.gravity.ide.ui.components.MonoBlock
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.SectionHeader
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.StatusPill
import com.gravity.ide.ui.components.formatTimeFull
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.components.statusColor
import com.gravity.ide.ui.theme.GravityColors
import com.gravity.ide.workspace.CheckpointManager
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AgentNewTaskScreen(projectId: String?, onCreated: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    val providers by c.db.providers().observe().collectAsState(initial = emptyList())
    val teams by c.db.teams().observe().collectAsState(initial = emptyList())
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    var selectedProject by remember { mutableStateOf(projectId) }
    var instruction by remember { mutableStateOf("") }
    var providerId by remember { mutableStateOf<String?>(null) }
    var model by remember { mutableStateOf("") }
    var reasoning by remember { mutableStateOf<ReasoningMode?>(null) }
    var teamId by remember { mutableStateOf<String?>(null) }

    val providerOptions = listOf<Pair<String?, String>>(null to "Default (${settings.operatingMode.label})", LocalLlamaProvider.ID to "Local model") + providers.filter { it.enabled }.map { it.id to it.name }
    val teamOptions = listOf<Pair<String?, String>>(null to "Single agent") + teams.map { it.id to it.name }
    val mode = reasoning ?: settings.reasoning

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Describe the work", style = MaterialTheme.typography.titleMedium)
            Text(
                "The agent reads your project, plans, edits files, runs commands (dangerous ones need your approval) and reports verified results. Every change is checkpointed and can be rolled back.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Selector("Project", projects, projects.firstOrNull { it.id == selectedProject }, { it.name }, { selectedProject = it.id }, Modifier.fillMaxWidth())
            if (projects.isEmpty()) Text("Create a project first (Projects → New).", color = GravityColors.Amber, style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(
                instruction, { instruction = it }, label = { Text("Instruction") },
                placeholder = { Text("e.g. Add a settings screen with a dark-mode toggle and persist it with DataStore. Run the unit tests.") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Selector("Provider", providerOptions, providerOptions.firstOrNull { it.first == providerId }, { it.second }, { providerId = it.first }, Modifier.weight(1f))
                Selector("Reasoning", ReasoningMode.entries.toList(), mode, { it.label }, { reasoning = it }, Modifier.weight(1f))
            }
            if (providerId != null && providerId != LocalLlamaProvider.ID) {
                OutlinedTextField(model, { model = it }, label = { Text("Model ID (blank = provider default)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Text(mode.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Selector("Team", teamOptions, teamOptions.firstOrNull { it.first == teamId }, { it.second }, { teamId = it.first }, Modifier.fillMaxWidth())
            Text(
                if (teamId == null) "One developer agent with tools." else "Architect plans → Developer implements → tests run → reviewers check the diff (in parallel) → fixes until approved.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(
                enabled = selectedProject != null && instruction.isNotBlank(),
                onClick = {
                    val pid = selectedProject ?: return@Button
                    scope.launch {
                        try {
                            val id = c.agent.create(pid, instruction.trim(), providerId, model.ifBlank { null }, mode, teamId)
                            c.agent.start(id)
                            onCreated(id)
                        } catch (e: Exception) {
                            toast(context, "Could not start: ${e.message}")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Icon(Icons.Filled.PlayArrow, null); Text(" Start task") }
        }
    }
}

@Composable
fun TasksScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val tasks by c.db.tasks().observe().collectAsState(initial = emptyList())
    val projects by c.db.projects().observeAll().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("active") }
    val active = setOf(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.PAUSED, TaskStatus.WAITING_APPROVAL, TaskStatus.WAITING_NETWORK, TaskStatus.INTERRUPTED).map { it.name }
    val shown = tasks.filter { if (filter == "active") it.status in active else it.status !in active }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = filter == "active", onClick = { filter = "active" }, label = { Text("Active") })
                FilterChip(selected = filter == "history", onClick = { filter = "history" }, label = { Text("History") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { go(Routes.agentNew()) }) { Text("New task") }
            }
        }
        if (shown.isEmpty()) item { EmptyState(if (filter == "active") "No active tasks" else "No finished tasks", "Start a task from AI Agent.") }
        items(shown, key = { it.id }) { t ->
            Panel(onClick = { go(Routes.task(t.id)) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusPill(t.status)
                    }
                    Text(
                        "${projects.firstOrNull { it.id == t.projectId }?.name ?: "?"} · ${t.stepCount} steps · ${t.inputTokens + t.outputTokens} tokens · ${relativeTime(t.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    t.stage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = GravityColors.Cyan, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
fun TaskDetailScreen(taskId: String, go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val task by c.db.tasks().observeTask(taskId).collectAsState(initial = null)
    val events by c.db.events().observeForTask(taskId).collectAsState(initial = emptyList())
    val versions by c.db.fileVersions().observeForTask(taskId).collectAsState(initial = emptyList())
    val live by c.agent.live.collectAsState()
    val approvals by c.approvals.pending.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var changes by remember { mutableStateOf<List<CheckpointManager.Change>>(emptyList()) }
    var diff by remember { mutableStateOf<CheckpointManager.Change?>(null) }
    var confirmRollback by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    val t = task ?: run {
        EmptyState("Task not found", "")
        return
    }
    val project by c.db.projects().observe(t.projectId).collectAsState(initial = null)
    LaunchedEffect(versions.size, tab, project) {
        val p = project
        if (tab == 2 && p != null) changes = c.checkpoints.changesForTask(taskId, File(p.rootPath))
    }
    val status = TaskStatus.valueOf(t.status)
    val myApprovals = approvals.filter { it.taskId == taskId }

    Column {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                StatusPill(t.status)
            }
            Text(
                "${project?.name ?: ""} · ${t.model ?: "default model"} · ${ReasoningMode.fromName(t.reasoning).label} · ${t.stepCount} steps · ${t.inputTokens}/${t.outputTokens} tokens",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            t.stage?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = GravityColors.Cyan) }
            if (status == TaskStatus.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (status) {
                    TaskStatus.RUNNING, TaskStatus.WAITING_APPROVAL -> {
                        OutlinedButton(onClick = { c.agent.pause(taskId) }) { Icon(Icons.Filled.Pause, null); Text(" Pause") }
                        OutlinedButton(onClick = { c.agent.cancel(taskId) }) { Icon(Icons.Filled.Cancel, null); Text(" Cancel") }
                    }
                    TaskStatus.PAUSED, TaskStatus.INTERRUPTED, TaskStatus.WAITING_NETWORK, TaskStatus.PENDING -> {
                        Button(onClick = { c.agent.start(taskId) }) { Icon(Icons.Filled.PlayArrow, null); Text(" Resume") }
                        OutlinedButton(onClick = { c.agent.cancel(taskId) }) { Text("Cancel") }
                    }
                    TaskStatus.FAILED, TaskStatus.CANCELLED -> Button(onClick = { c.agent.start(taskId) }) { Icon(Icons.Filled.Replay, null); Text(" Retry") }
                    TaskStatus.COMPLETED -> Unit
                }
                if (versions.isNotEmpty() && status != TaskStatus.RUNNING) {
                    OutlinedButton(onClick = { confirmRollback = true }) { Icon(Icons.Filled.Restore, null); Text(" Roll back") }
                }
                if (status != TaskStatus.RUNNING) TextButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, null) }
            }
        }
        for (a in myApprovals) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(GravityColors.Amber.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(12.dp)) {
                Text("Approval required · ${a.risk.lowercase()}", style = MaterialTheme.typography.labelMedium, color = statusColor(if (a.risk == "DANGEROUS") "FAILED" else "WARN"))
                Text(a.title, style = MaterialTheme.typography.bodyMedium)
                if (a.detail.isNotBlank()) Text(a.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { c.approvals.respond(a.id, true) }) { Text("Approve") }
                    OutlinedButton(onClick = { c.approvals.respond(a.id, false) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = GravityColors.Red)) { Text("Reject") }
                }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Activity") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Result") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Changes (${versions.map { it.relPath }.distinct().size})") })
        }
        when (tab) {
            0 -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                live[taskId]?.takeIf { it.isNotBlank() }?.let { text ->
                    item {
                        Panel { Column { Text("Model output (streaming)", style = MaterialTheme.typography.labelSmall, color = GravityColors.Purple); Text(text.takeLast(1500), style = MaterialTheme.typography.bodySmall) } }
                    }
                }
                if (events.isEmpty()) item { Text("No activity yet.", style = MaterialTheme.typography.bodySmall) }
                items(events.asReversed(), key = { it.id }) { e -> EventRow(e, expanded == e.id) { expanded = if (expanded == e.id) null else e.id } }
            }
            1 -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item {
                    SectionHeader("Instruction")
                    Text(t.instruction, style = MaterialTheme.typography.bodyMedium)
                    t.plan?.let {
                        SectionHeader("Plan")
                        RichText(it)
                    }
                    SectionHeader("Summary")
                    if (t.summary != null) RichText(t.summary) else Text(if (status == TaskStatus.COMPLETED) "No summary." else "Available when the task completes.", style = MaterialTheme.typography.bodySmall)
                    t.error?.let {
                        SectionHeader("Error")
                        Text(it, color = GravityColors.Red, style = MaterialTheme.typography.bodySmall)
                    }
                    SectionHeader("Details")
                    KeyValue("Created", formatTimeFull(t.createdAt))
                    KeyValue("Updated", formatTimeFull(t.updatedAt))
                    KeyValue("Task ID", t.id, mono = true)
                    project?.let { p -> TextButton(onClick = { go(Routes.files(p.id)) }) { Text("Open project files") } }
                }
            }
            else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (changes.isEmpty()) item { Text("No file changes recorded for this task.", style = MaterialTheme.typography.bodySmall) }
                items(changes, key = { it.version.relPath }) { ch ->
                    val s = com.gravity.ide.core.LineDiff.stats(ch.diff)
                    Panel(onClick = { diff = ch }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(ch.version.relPath, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        ch.before == null -> "created"
                                        ch.after == null -> "deleted"
                                        else -> "modified"
                                    } + " by ${ch.version.agentRole ?: ch.version.source}${ch.version.model?.let { " · $it" } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("+${s.added} −${s.removed}", style = MaterialTheme.typography.labelMedium, color = GravityColors.Cyan)
                        }
                    }
                }
            }
        }
    }

    diff?.let { ch ->
        DiffDialog(ch.version.relPath, ch.before ?: "", ch.after ?: "", onDismiss = { diff = null }, extraAction = {
            TextButton(onClick = {
                val p = project ?: return@TextButton
                scope.launch {
                    c.checkpoints.restore(ch.version.id, File(p.rootPath), p.id)
                    changes = c.checkpoints.changesForTask(taskId, File(p.rootPath))
                    toast(context, "Restored ${ch.version.relPath}")
                }
                diff = null
            }) { Text("Restore original") }
        })
    }
    if (confirmRollback) ConfirmDialog(
        "Roll back all changes?", "Every file this task touched is restored to its state before the task. The current versions are kept in history.", "Roll back", destructive = true,
        onConfirm = {
            val p = project ?: return@ConfirmDialog
            scope.launch {
                val n = c.checkpoints.rollbackTask(taskId, File(p.rootPath), p.id)
                toast(context, "Restored $n file(s)")
            }
        }, onDismiss = { confirmRollback = false },
    )
    if (confirmDelete) ConfirmDialog("Delete task?", "The task and its conversation are removed. File changes stay as they are.", "Delete", destructive = true, onConfirm = {
        scope.launch {
            c.agent.delete(taskId)
            go(Routes.TASKS)
        }
    }, onDismiss = { confirmDelete = false })
}

@Composable
fun EventRow(e: EventEntity, expanded: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(statusColor(e.level), RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(8.dp))
            Text(e.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = if (expanded) 10 else 1, overflow = TextOverflow.Ellipsis)
            Text(relativeTime(e.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val meta = listOfNotNull(e.category.lowercase(), e.agentRole, e.model, e.tool).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
        if (expanded && e.detail.isNotBlank()) MonoBlock(e.detail.take(8000), Modifier.padding(start = 16.dp, top = 4.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
