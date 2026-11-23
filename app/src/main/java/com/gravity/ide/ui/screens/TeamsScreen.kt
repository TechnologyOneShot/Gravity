@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.gravity.ide.agent.AgentRole
import com.gravity.ide.agent.TeamCodec
import com.gravity.ide.agent.TeamMember
import com.gravity.ide.ai.local.LocalLlamaProvider
import com.gravity.ide.data.db.LocalModelEntity
import com.gravity.ide.data.db.ProviderEntity
import com.gravity.ide.data.db.TeamEntity
import com.gravity.ide.models.ModelStatus
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.components.ConfirmDialog
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.MenuAction
import com.gravity.ide.ui.components.OverflowMenu
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun TeamsScreen() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val teams by c.db.teams().observe().collectAsState(initial = emptyList())
    val providers by c.db.providers().observe().collectAsState(initial = emptyList())
    val models by c.db.models().observe().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<TeamEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TeamEntity?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Button(onClick = { creating = true }) { Icon(Icons.Filled.Add, null); Text(" New team") }
            Text(
                "A team runs as: Architect plan → Developer (tools) → tests → Reviewer and Security reviewer in parallel → fixes, up to the review-round limit. " +
                    "Local and cloud members can be mixed; local models are loaded one at a time (the previous one is unloaded first) to protect memory. " +
                    "Every file change records which role and model made it.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (teams.isEmpty()) item { EmptyState("No teams", "Create e.g. “Gravity Ultimate Coding Team”: Gemini as Architect, a local Qwen as Developer, Gemma as Reviewer.") }
        items(teams, key = { it.id }) { t ->
            val members = TeamCodec.decode(t.members)
            Panel {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        OverflowMenu(listOf(MenuAction("Edit", Icons.Filled.Edit) { editing = t }, MenuAction("Delete", Icons.Filled.Delete, destructive = true) { deleting = t }))
                    }
                    members.forEach { m ->
                        Text("• ${m.role.label}: ${describe(m, providers, models)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Review rounds ${t.reviewRounds} · tests ${if (t.runTests) "on" else "off"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (creating || editing != null) {
        TeamDialog(editing, providers, models.filter { it.status == ModelStatus.READY.name }, onDismiss = { creating = false; editing = null }) { team ->
            scope.launch { c.db.teams().upsert(team) }
        }
    }
    deleting?.let { t -> ConfirmDialog("Delete ${t.name}?", "Tasks that used it keep their history.", "Delete", destructive = true, onConfirm = { scope.launch { c.db.teams().delete(t.id) } }, onDismiss = { deleting = null }) }
}

private fun describe(m: TeamMember, providers: List<ProviderEntity>, models: List<LocalModelEntity>): String = when (m.providerId) {
    null -> "default provider"
    LocalLlamaProvider.ID -> "local · " + (models.firstOrNull { it.id == m.model }?.name ?: "currently loaded model")
    else -> (providers.firstOrNull { it.id == m.providerId }?.name ?: "missing provider") + (m.model?.let { " · $it" } ?: "")
}

@Composable
private fun TeamDialog(existing: TeamEntity?, providers: List<ProviderEntity>, models: List<LocalModelEntity>, onDismiss: () -> Unit, onSave: (TeamEntity) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "Gravity Ultimate Coding Team") }
    var members by remember {
        mutableStateOf(
            existing?.let { TeamCodec.decode(it.members) } ?: listOf(
                TeamMember(AgentRole.ARCHITECT, null, null), TeamMember(AgentRole.DEVELOPER, null, null), TeamMember(AgentRole.REVIEWER, null, null),
            )
        )
    }
    var rounds by remember { mutableStateOf((existing?.reviewRounds ?: 1).toString()) }
    var runTests by remember { mutableStateOf(existing?.runTests ?: true) }
    val providerOptions = listOf<Pair<String?, String>>(null to "Default provider", LocalLlamaProvider.ID to "Local model") + providers.map { it.id to it.name }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New team" else "Edit team") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Team name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                members.forEachIndexed { i, m ->
                    Panel {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Selector("Role", AgentRole.entries.toList(), m.role, { it.label }, { r -> members = members.toMutableList().also { l -> l[i] = m.copy(role = r) } }, Modifier.weight(1f))
                                IconButton(onClick = { members = members.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Filled.Close, "Remove", tint = GravityColors.Red) }
                            }
                            Selector("Provider", providerOptions, providerOptions.firstOrNull { it.first == m.providerId }, { it.second }, { p ->
                                members = members.toMutableList().also { l -> l[i] = m.copy(providerId = p.first, model = null) }
                            }, Modifier.fillMaxWidth())
                            if (m.providerId == LocalLlamaProvider.ID) {
                                val opts = listOf<LocalModelEntity?>(null) + models
                                Selector("Local model", opts, opts.firstOrNull { it?.id == m.model }, { it?.name ?: "Whatever is loaded" }, { lm ->
                                    members = members.toMutableList().also { l -> l[i] = m.copy(model = lm?.id) }
                                }, Modifier.fillMaxWidth())
                            } else if (m.providerId != null) {
                                OutlinedTextField(m.model.orEmpty(), { v -> members = members.toMutableList().also { l -> l[i] = m.copy(model = v.ifBlank { null }) } }, label = { Text("Model ID (blank = provider default)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                TextButton(onClick = { members = members + TeamMember(AgentRole.SECURITY, null, null) }) { Icon(Icons.Filled.Add, null); Text(" Add member") }
                OutlinedTextField(rounds, { rounds = it.filter(Char::isDigit) }, label = { Text("Max review rounds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Run project tests each round", Modifier.weight(1f)); Switch(runTests, { runTests = it }) }
                if (members.none { it.role == AgentRole.DEVELOPER }) Text("A team needs a Developer; the task's own provider is used if none is set.", color = GravityColors.Amber, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(
                    TeamEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(), name = name.trim(), members = TeamCodec.encode(members),
                        reviewRounds = (rounds.toIntOrNull() ?: 1).coerceIn(0, 5), runTests = runTests, createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    )
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
