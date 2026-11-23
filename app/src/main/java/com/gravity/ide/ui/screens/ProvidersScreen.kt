@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.gravity.ide.ai.GeminiProvider
import com.gravity.ide.ai.ProviderKind
import com.gravity.ide.data.db.ProviderEntity
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.security.SecretStore
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.components.ConfirmDialog
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.KeyValue
import com.gravity.ide.ui.components.MenuAction
import com.gravity.ide.ui.components.MessageBanner
import com.gravity.ide.ui.components.OverflowMenu
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.StatusPill
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ProvidersScreen() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val providers by c.db.providers().observe().collectAsState(initial = emptyList())
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    var editing by remember { mutableStateOf<ProviderEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProviderEntity?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    var discovered by remember { mutableStateOf<Pair<ProviderEntity, List<String>>?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Button(onClick = { creating = true }) { Icon(Icons.Filled.Add, null); Text(" Add provider") }
            Text(
                "API keys are encrypted with a hardware-backed Android Keystore key and never leave this device (not in logs, exports or backups). " +
                    "Shipping a shared key inside an APK is unsafe: for multi-user apps put keys behind your own server-side proxy and add it here as an OpenAI-compatible endpoint.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (providers.isEmpty()) item { EmptyState("No providers", "Add Google Gemini or any OpenAI-compatible API (OpenAI, OpenRouter, Groq, a llama.cpp server or Ollama on your network).") }
        items(providers, key = { it.id }) { p ->
            val cost = p.totalInputTokens / 1_000_000.0 * p.inputPricePerMTok + p.totalOutputTokens / 1_000_000.0 * p.outputPricePerMTok
            Panel {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (settings.defaultProviderId == p.id) Icon(Icons.Filled.Star, "Default", tint = GravityColors.Amber, modifier = Modifier.padding(end = 4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text("${ProviderKind.valueOf(p.kind).label} · ${p.defaultModel}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(if (!p.enabled) "DISABLED" else if (p.lastStatus?.startsWith("OK") == true) "OK" else if (p.lastStatus == null) "UNTESTED" else "ERROR")
                        Switch(p.enabled, { en -> scope.launch { c.db.providers().upsert(p.copy(enabled = en)) } })
                        OverflowMenu(
                            listOf(
                                MenuAction("Test connection", Icons.Filled.NetworkCheck) {
                                    testing = p.id
                                    scope.launch {
                                        val r = c.providers.test(p)
                                        testing = null
                                        r.onSuccess { discovered = p to it }.onFailure { toast(context, "Failed: ${it.message}") }
                                    }
                                },
                                MenuAction("Set as default", Icons.Filled.Star) { scope.launch { c.settings.update { it.copy(defaultProviderId = p.id, defaultModel = p.defaultModel) } } },
                                MenuAction("Edit", Icons.Filled.Edit) { editing = p },
                                MenuAction("Delete", Icons.Filled.Delete, destructive = true) { deleting = p },
                            )
                        )
                    }
                    if (testing == p.id) Text("Testing…", style = MaterialTheme.typography.labelSmall, color = GravityColors.Cyan)
                    p.lastStatus?.let { Text("$it · ${relativeTime(p.lastCheckedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    KeyValue("Usage", "${p.totalInputTokens} in / ${p.totalOutputTokens} out tokens" + if (p.inputPricePerMTok + p.outputPricePerMTok > 0) " · ≈ $%.4f".format(cost) else "")
                    if (p.usageLimitTokens > 0) KeyValue("Limit", "${p.usageLimitTokens} tokens")
                    if (p.failureCount > 0) Text("${p.failureCount} consecutive failure(s)", style = MaterialTheme.typography.labelSmall, color = GravityColors.Red)
                    if (p.baseUrl.startsWith("http://") && !isPrivateHost(p.baseUrl)) {
                        Text("Warning: unencrypted HTTP to a public host — your key and code are sent in clear text.", style = MaterialTheme.typography.labelSmall, color = GravityColors.Red)
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        ProviderDialog(editing, onDismiss = { creating = false; editing = null }) { entity, key ->
            scope.launch {
                c.db.providers().upsert(entity)
                if (key != null) c.secrets.put(SecretStore.providerKey(entity.id), key)
                if (settings.defaultProviderId == null) c.settings.update { it.copy(defaultProviderId = entity.id, defaultModel = entity.defaultModel) }
                toast(context, "Saved ${entity.name} — use Test connection to verify")
            }
        }
    }
    deleting?.let { p ->
        ConfirmDialog("Delete ${p.name}?", "Its stored API key is erased.", "Delete", destructive = true, onConfirm = {
            scope.launch {
                c.db.providers().delete(p.id)
                c.secrets.remove(SecretStore.providerKey(p.id))
                if (settings.defaultProviderId == p.id) c.settings.update { it.copy(defaultProviderId = null, defaultModel = null) }
            }
        }, onDismiss = { deleting = null })
    }
    discovered?.let { (p, models) ->
        AlertDialog(
            onDismissRequest = { discovered = null },
            title = { Text("${p.name}: ${models.size} models") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(models) { m ->
                        TextButton(onClick = {
                            scope.launch { c.db.providers().upsert(p.copy(defaultModel = m)) }
                            discovered = null
                        }) { Text(m + if (m == p.defaultModel) "  (current)" else "") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { discovered = null }) { Text("Close") } },
        )
    }
}

fun isPrivateHost(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
    return host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host) || host.endsWith(".local")
}

@Composable
private fun ProviderDialog(existing: ProviderEntity?, onDismiss: () -> Unit, onSave: (ProviderEntity, String?) -> Unit) {
    val c = LocalAppContainer.current
    var kind by remember { mutableStateOf(existing?.kind?.let { ProviderKind.valueOf(it) } ?: ProviderKind.GEMINI) }
    var name by remember { mutableStateOf(existing?.name ?: "Google Gemini") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: GeminiProvider.DEFAULT_BASE_URL) }
    var model by remember { mutableStateOf(existing?.defaultModel ?: "gemini-2.5-flash") }
    var key by remember { mutableStateOf("") }
    var timeout by remember { mutableStateOf((existing?.timeoutSec ?: 120).toString()) }
    var priority by remember { mutableStateOf((existing?.priority ?: 0).toString()) }
    var effort by remember { mutableStateOf(existing?.supportsReasoningEffort ?: false) }
    var streamUsage by remember { mutableStateOf(existing?.streamUsage ?: true) }
    var inPrice by remember { mutableStateOf((existing?.inputPricePerMTok ?: 0.0).toString()) }
    var outPrice by remember { mutableStateOf((existing?.outputPricePerMTok ?: 0.0).toString()) }
    var limit by remember { mutableStateOf((existing?.usageLimitTokens ?: 0L).toString()) }
    val hasKey = existing?.let { c.secrets.has(SecretStore.providerKey(it.id)) } ?: false
    val kinds = listOf(ProviderKind.GEMINI, ProviderKind.OPENAI_COMPAT)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add provider" else "Edit provider") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Selector("Type", kinds, kind, { it.label }, { k ->
                    kind = k
                    if (existing == null) {
                        if (k == ProviderKind.GEMINI) { name = "Google Gemini"; baseUrl = GeminiProvider.DEFAULT_BASE_URL; model = "gemini-2.5-flash" }
                        else { name = "OpenAI-compatible"; baseUrl = "https://api.openai.com/v1"; model = "" }
                    }
                }, Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("Default model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    key, { key = it }, label = { Text(if (hasKey) "API key (stored — leave blank to keep)" else "API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit) }, label = { Text("Timeout s") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(priority, { priority = it.filter { ch -> ch.isDigit() || ch == '-' } }, label = { Text("Priority") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(inPrice, { inPrice = it }, label = { Text("$ / 1M in") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(outPrice, { outPrice = it }, label = { Text("$ / 1M out") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit) }, label = { Text("Usage limit (tokens, 0 = none)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (kind == ProviderKind.OPENAI_COMPAT) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Send reasoning_effort", Modifier.weight(1f)); Switch(effort, { effort = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Request streamed usage", Modifier.weight(1f)); Switch(streamUsage, { streamUsage = it }) }
                }
                if (baseUrl.startsWith("http://") && !isPrivateHost(baseUrl)) MessageBanner("HTTP to a public host is not encrypted.", isError = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank() && (hasKey || key.isNotBlank() || kind == ProviderKind.OPENAI_COMPAT), onClick = {
                val entity = (existing ?: ProviderEntity(id = UUID.randomUUID().toString(), name = name, kind = kind.name, baseUrl = baseUrl, defaultModel = model, createdAt = System.currentTimeMillis())).copy(
                    name = name.trim(), kind = kind.name, baseUrl = baseUrl.trim(), defaultModel = model.trim(),
                    timeoutSec = timeout.toIntOrNull() ?: 120, priority = priority.toIntOrNull() ?: 0,
                    supportsReasoningEffort = effort, streamUsage = streamUsage,
                    inputPricePerMTok = inPrice.toDoubleOrNull() ?: 0.0, outputPricePerMTok = outPrice.toDoubleOrNull() ?: 0.0,
                    usageLimitTokens = limit.toLongOrNull() ?: 0,
                )
                onSave(entity, key.trim().ifBlank { null })
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
