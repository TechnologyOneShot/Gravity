@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.data.settings.OperatingMode
import com.gravity.ide.device.OfflineReadinessChecker
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.LoadingRow
import com.gravity.ide.ui.components.MessageBanner
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.SectionHeader
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.StatusPill
import com.gravity.ide.ui.components.formatTimeFull
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.launch

/** Offline Readiness Checker: measures what will work before the network goes away. */
@Composable
fun OfflineReadinessScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    val network by c.network.state.collectAsState()
    var projectId by remember { mutableStateOf<String?>(null) }
    var includeTermux by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<OfflineReadinessChecker.Report?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run() {
        running = true
        error = null
        scope.launch {
            try {
                val termux = if (includeTermux) {
                    val share = settings.termuxShareUri?.let { c.termux.sharePathFor(android.net.Uri.parse(it)) }
                    try { c.termux.toolchain(share) } catch (e: Exception) { error = "Termux check failed: ${e.message}"; null }
                } else null
                report = c.offlineReadiness.check(projects.firstOrNull { it.id == projectId }, termux)
            } catch (e: Exception) {
                error = e.message
            } finally {
                running = false
            }
        }
    }
    LaunchedEffect(Unit) { run() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Checks what will keep working without a network: downloaded models, the local runtime, memory, storage, toolchains and the project's dependency caches. Results are saved as test evidence (suite offline-readiness).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(if (network.online) "Currently online (${network.transport})" else "Currently offline", color = if (network.online) GravityColors.Green else GravityColors.Amber, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            val options = listOf<Pair<String?, String>>(null to "No specific project") + projects.map { it.id to it.name }
            Selector("Project", options, options.firstOrNull { it.first == projectId }, { it.second }, { projectId = it.first }, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Include Termux toolchain check", Modifier.weight(1f))
                Switch(includeTermux, { includeTermux = it }, enabled = c.termux.status().permissionGranted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !running, onClick = { run() }) { Text("Run check") }
                if (settings.operatingMode != OperatingMode.LOCAL_ONLY && report?.readyForLocalAi == true) {
                    TextButton(onClick = { scope.launch { c.settings.update { it.copy(operatingMode = OperatingMode.LOCAL_ONLY) } } }) { Text("Switch to Local only") }
                }
            }
        }
        if (running) item { LoadingRow("Checking this device…") }
        error?.let { item { MessageBanner(it, isError = true) } }
        report?.let { r ->
            item { SectionHeader("Operations offline · ${formatTimeFull(r.createdAt)}") }
            items(r.operations, key = { "op-" + it.name }) { op ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(op.name, style = MaterialTheme.typography.bodyMedium)
                        Text(op.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(if (op.offline) "READY" else "OFFLINE")
                }
            }
            item { SectionHeader("Checks (${r.checks.count { it.ready }}/${r.checks.size} ready)") }
            items(r.checks, key = { "c-" + it.name }) { ch ->
                Panel {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ch.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            StatusPill(if (ch.ready) "PASS" else "FAIL")
                        }
                        Text(ch.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ch.fix?.let { Text("Fix: $it", style = MaterialTheme.typography.labelSmall, color = GravityColors.Amber) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { go(Routes.models(2)) }) { Text("Get a model") }
                    TextButton(onClick = { go(Routes.build()) }) { Text("Build Center") }
                }
            }
        }
    }
}
