@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import com.gravity.ide.ui.components.safeTabIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravity.ide.ai.LlmMessage
import com.gravity.ide.ai.LlmRequest
import com.gravity.ide.ai.ReasoningMode
import com.gravity.ide.ai.Role
import com.gravity.ide.ai.complete
import com.gravity.ide.core.CommandSafety
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.terminal.LineKind
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.components.MenuAction
import com.gravity.ide.ui.components.OverflowMenu
import com.gravity.ide.ui.components.TextInputDialog
import com.gravity.ide.ui.theme.GravityColors
import com.gravity.ide.ui.theme.LocalCodeColors
import com.gravity.ide.ui.theme.MonoFamily
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun TerminalScreen(cwd: String?) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val sessions by c.terminals.sessions.collectAsState()
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    var activeId by remember { mutableStateOf<Int?>(null) }
    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableStateOf(-1) }
    var showInfo by remember { mutableStateOf(false) }
    var askAi by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var envOpen by remember { mutableStateOf(false) }

    LaunchedEffect(cwd) {
        val dir = cwd?.let { File(it) }?.takeIf { it.isDirectory }
        if (dir != null) activeId = c.terminals.open(dir, dir.name).id
        else if (c.terminals.sessions.value.isEmpty()) activeId = c.terminals.open(c.terminals.homeDir, "Shell").id
    }

    val session = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()
    val colors = LocalCodeColors.current

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sessions.isNotEmpty()) {
                ScrollableTabRow(selectedTabIndex = sessions.indexOf(session).coerceAtLeast(0), indicator = safeTabIndicator(sessions.indexOf(session).coerceAtLeast(0)), edgePadding = 4.dp, modifier = Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background) {
                    sessions.forEach { s ->
                        Tab(selected = s == session, onClick = { activeId = s.id }, text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.title, maxLines = 1)
                                IconButton(onClick = { c.terminals.close(s.id) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, "Close", Modifier.size(14.dp)) }
                            }
                        })
                    }
                }
            } else {
                Text("No sessions", modifier = Modifier.weight(1f).padding(12.dp))
            }
            IconButton(onClick = { activeId = c.terminals.open(session?.cwd?.value?.let { File(it) } ?: c.terminals.homeDir).id }) { Icon(Icons.Filled.Add, "New terminal") }
        }
        if (session == null) return@Column

        val lines by session.lines.collectAsState()
        val running by session.running.collectAsState()
        val dir by session.cwd.collectAsState()
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(dir, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
            if (running != null) IconButton(onClick = { session.stop() }) { Icon(Icons.Filled.Stop, "Stop", tint = GravityColors.Red) }
            IconButton(onClick = { askAi = true }) { Icon(Icons.Filled.AutoAwesome, "Ask AI for a command", tint = GravityColors.Purple) }
            OverflowMenu(
                listOf(
                    MenuAction("Clear", Icons.Filled.ClearAll) { session.clear() },
                    MenuAction("Copy output", Icons.Filled.ContentCopy) { clipboard.setText(AnnotatedString(session.transcript())); toast(context, "Copied") },
                    MenuAction("Environment variables", Icons.Filled.Tune) { envOpen = true },
                    MenuAction("About this shell", Icons.Filled.Info) { showInfo = true },
                )
            )
        }
        if (running != null) LinearProgressIndicator(Modifier.fillMaxWidth())

        SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(colors.background)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(10.dp)) {
                items(lines) { l ->
                    Text(
                        l.text,
                        fontFamily = MonoFamily,
                        fontSize = settings.terminalFontSize.sp,
                        lineHeight = (settings.terminalFontSize * 1.4f).sp,
                        color = when (l.kind) {
                            LineKind.STDOUT -> colors.plain
                            LineKind.STDERR -> GravityColors.Red
                            LineKind.INPUT -> GravityColors.Cyan
                            LineKind.SYSTEM -> GravityColors.Amber
                        },
                    )
                }
            }
        }
        val assessment = remember(input) { CommandSafety.assess(input) }
        if (input.isNotBlank() && assessment.risk != CommandSafety.Risk.SAFE) {
            Text(
                "${assessment.risk.name.lowercase()}: ${assessment.reasons.joinToString()}",
                color = if (assessment.risk == CommandSafety.Risk.DANGEROUS) GravityColors.Red else GravityColors.Amber,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = {
                val h = session.history
                if (h.isNotEmpty()) {
                    historyIndex = if (historyIndex <= 0) h.size - 1 else historyIndex - 1
                    input = h[historyIndex]
                }
            }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous command") }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (running != null) "Input for running program" else "Command") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = {
                    session.submit(input)
                    input = ""
                    historyIndex = -1
                }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                session.submit(input)
                input = ""
                historyIndex = -1
            }) { Icon(Icons.AutoMirrored.Filled.Send, "Run", tint = GravityColors.Cyan) }
        }

        if (showInfo) AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Android shell") },
            text = {
                Text(
                    "This is the device's /system/bin/sh (toybox/mksh) running inside Yamini's app sandbox, not Windows CMD or PowerShell. " +
                        "Commands run with the app's permissions: no root, no adb privileges, and Android 10+ blocks executing binaries written to app storage. " +
                        "Exit codes and output are real. Stop restarts the shell (Android provides no TTY to send Ctrl-C).",
                )
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
        )

        if (envOpen) AlertDialog(
            onDismissRequest = { envOpen = false },
            title = { Text("Environment") },
            text = {
                Column {
                    session.env.forEach { (k, v) -> Text("$k=$v", fontFamily = MonoFamily, fontSize = 11.sp) }
                    Text("Use `export NAME=value` in the shell to change variables for this session.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { envOpen = false }) { Text("Close") } },
        )

        if (askAi) TextInputDialog("Ask AI for a command", "Describe what you want to do", confirmLabel = "Suggest", singleLine = false, onConfirm = { request ->
            aiBusy = true
            scope.launch {
                try {
                    val resolved = c.providers.resolve(null, null)
                    val tail = lines.takeLast(30).joinToString("\n") { it.text }
                    val prompt = "You suggest ONE shell command for Android's /system/bin/sh (toybox; no root, no apt). Working directory: $dir.\n" +
                        "Recent terminal output:\n$tail\n\nTask: $request\nReply with only the command on one line."
                    val r = resolved.provider.complete(LlmRequest(resolved.model, listOf(LlmMessage(Role.USER, prompt)), reasoning = ReasoningMode.FAST, maxOutputTokens = 200))
                    c.providers.recordUsage(resolved.entity, r.inputTokens, r.outputTokens)
                    input = r.text.trim().removePrefix("```sh").removePrefix("```").removeSuffix("```").trim().lineSequence().firstOrNull().orEmpty()
                    toast(context, "Suggested command placed in the input — review before running")
                } catch (e: Exception) {
                    toast(context, "AI suggestion failed: ${e.message}")
                } finally {
                    aiBusy = false
                }
            }
        }, onDismiss = { askAi = false })
        if (aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
