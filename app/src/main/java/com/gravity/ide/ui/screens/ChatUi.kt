@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ArrowUpward
import com.gravity.ide.ui.theme.DisplayFamily
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravity.ide.ai.ProviderKind
import com.gravity.ide.ai.ReasoningMode
import com.gravity.ide.ai.local.LocalLlamaProvider
import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.core.ModelPicker
import com.gravity.ide.data.db.ChatEntity
import com.gravity.ide.data.db.LocalModelEntity
import com.gravity.ide.data.db.MessageEntity
import com.gravity.ide.data.db.ProviderEntity
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.device.PerformanceManager
import com.gravity.ide.models.ModelStatus
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.formatBytes
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.launch
import org.json.JSONArray

private val CodeBg = Color(0xFF181716)
private val CodeHeaderBg = Color(0xFF242322)

/** Gravity's round spark avatar. */
@Composable
internal fun GravityAvatar(size: Dp = 30.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(GravityColors.Accent), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.AutoAwesome, null, tint = GravityColors.OnAccent, modifier = Modifier.size(size * 0.55f))
    }
}

/** What the chat will use, as shown on the model button. */
internal data class ModelLabel(val text: String, val isLocal: Boolean, val needsAttention: Boolean)

internal fun modelLabelFor(chat: ChatEntity, providers: List<ProviderEntity>, loadedLocal: LocalModelHost.Loaded?): ModelLabel {
    val chosen = providers.firstOrNull { it.id == chat.providerId }
    val hasCloud = providers.any { it.enabled && it.kind != ProviderKind.LOCAL.name }
    return when {
        chat.providerId == LocalLlamaProvider.ID -> ModelLabel(loadedLocal?.name?.let(::shortModelName) ?: "Phone model", true, false)
        chosen != null -> ModelLabel(shortModelName(chat.model ?: chosen.defaultModel.ifBlank { chosen.name }), false, false)
        hasCloud -> ModelLabel("Auto", false, false)
        loadedLocal != null -> ModelLabel(shortModelName(loadedLocal.name), true, false)
        else -> ModelLabel("Choose AI", false, true)
    }
}

/** "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf" → "qwen2.5-coder-1.5b"; "gemini-3.5-flash" stays. */
internal fun shortModelName(name: String): String =
    name.removeSuffix(".gguf").replace(Regex("(?i)[-_.](instruct|chat|it)?[-_.]?(q\\d[_a-z0-9]*|f16|bf16)$"), "").replace(Regex("(?i)-instruct$"), "").take(28)

/** The model button in the message box: which AI answers; tap to change. */
@Composable
internal fun ModelPill(label: ModelLabel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color.Transparent, modifier = modifier.testTag("model-pill")) {
        Row(Modifier.padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (label.isLocal) Icon(Icons.Filled.PhoneAndroid, null, tint = GravityColors.Muted, modifier = Modifier.size(14.dp).padding(end = 2.dp))
            Text(
                label.text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (label.needsAttention) GravityColors.Amber else GravityColors.Muted, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ExpandMore, "Change AI model", modifier = Modifier.size(18.dp), tint = GravityColors.Muted)
        }
    }
}

/** Empty-chat welcome: a calm greeting, AI setup if needed, and starter ideas. */
@Composable
internal fun ChatWelcome(
    needsSetup: Boolean,
    onSetup: () -> Unit,
    onSuggestion: (text: String, app: Boolean) -> Unit,
    appMode: Boolean = false,
    onModeChange: (Boolean) -> Unit = {},
) {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Chat or App is chosen once, on a new chat; after the first message the chat keeps its mode.
        ModeSwitch(appMode, onModeChange)
        Spacer(Modifier.height(28.dp))
        GravityAvatar(44.dp)
        Spacer(Modifier.height(18.dp))
        Text(greeting, fontFamily = DisplayFamily, fontSize = 32.sp, textAlign = TextAlign.Center)
        Text(
            "How can I help you today?", fontFamily = DisplayFamily, fontSize = 20.sp, color = GravityColors.Muted,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
        if (needsSetup) {
            Surface(
                onClick = onSetup, shape = RoundedCornerShape(18.dp), color = GravityColors.Accent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, GravityColors.Accent.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth().testTag("setup-ai"),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.AutoAwesome, GravityColors.Accent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Set up AI (1 minute)", style = MaterialTheme.typography.titleSmall)
                        Text("A free Gemini key gives the best answers and apps. Phone models work offline.", style = MaterialTheme.typography.labelMedium, color = GravityColors.Muted)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        val ideas = listOf(
            Triple("Calculator app", "Build a calculator app", true),
            Triple("Music player", "Build a music player for the songs on my phone", true),
            Triple("To-do list", "Build a to-do list app", true),
            Triple("Explain code", "Explain what this code does, step by step:\n", false),
            Triple("Write a message", "Help me write a message that ", false),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((title, text, app) in ideas) {
                Surface(
                    onClick = { onSuggestion(text, app) }, shape = RoundedCornerShape(50), color = Color.Transparent,
                    border = BorderStroke(1.dp, GravityColors.Outline),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (app) Icons.Filled.PhoneIphone else Icons.Filled.Lightbulb, null, tint = GravityColors.Muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(title, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** One message: the user's in a soft bubble; Gravity's as plain text with actions; app runs as cards. */
@Composable
internal fun ChatBubble(
    m: MessageEntity,
    isLast: Boolean,
    busy: Boolean,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onChangeModel: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenApp: (String) -> Unit = {},
    onRetryApp: (projectId: String, taskId: String) -> Unit = { _, _ -> },
    onMake: (com.gravity.ide.core.MakeFiles.Kind) -> Unit = {},
) {
    val atts = remember(m.attachments) {
        runCatching { JSONArray(m.attachments) }.getOrNull()?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    }
    val app = atts.firstOrNull { it.optString("kind") == "app" }
    if (app != null) {
        AppTaskCard(app.optString("projectId"), app.optString("taskId"), app.optBoolean("change"), onOpenApp, onRetryApp)
        return
    }
    if (m.role == "user") {
        var menu by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(start = 56.dp), horizontalAlignment = Alignment.End) {
            for (a in atts) {
                AttachmentTag(a.optString("name"), a.optString("kind"), a.optLong("size"))
                Spacer(Modifier.height(4.dp))
            }
            if (m.content.isNotBlank()) {
                Box {
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(GravityColors.SurfaceHigh)
                            .clickable { menu = true }.padding(horizontal = 16.dp, vertical = 11.dp),
                    ) {
                        Text(m.content, style = MaterialTheme.typography.bodyLarge)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menu = false; onCopy() })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        if (m.content.isNotBlank()) {
            SelectionContainer { RichText(m.content) }
            val sources = remember(m.sources) { runCatching { JSONArray(m.sources) }.getOrNull() }
            if (sources != null && sources.length() > 0) {
                Text("Sources", style = MaterialTheme.typography.labelMedium, color = GravityColors.Muted, modifier = Modifier.padding(top = 8.dp))
                for (i in 0 until sources.length()) {
                    val s = sources.getJSONObject(i)
                    Text(
                        "${i + 1}. ${s.optString("title")}", color = GravityColors.Blue, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { onOpenUrl(s.optString("url")) }.padding(vertical = 2.dp),
                    )
                }
            }
        }
        m.error?.let { err ->
            if (m.content.isNotBlank()) Spacer(Modifier.height(6.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = GravityColors.Red.copy(alpha = 0.10f), border = BorderStroke(1.dp, GravityColors.Red.copy(alpha = 0.5f))) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ErrorOutline, null, tint = GravityColors.Red, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("No reply", style = MaterialTheme.typography.titleSmall, color = GravityColors.Red)
                    }
                    Text(err, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    Row {
                        if (isLast && !busy) TextButton(onClick = onRetry) { Text("Try again") }
                        TextButton(onClick = onChangeModel) { Text("Choose model") }
                    }
                }
            }
        }
        // Files made from this reply (PDF, ZIP, PNG, WAV).
        atts.filter { it.optString("kind") == "file" }.forEach { f ->
            Spacer(Modifier.height(8.dp))
            MadeFileCard(f.optString("name"), f.optString("path"), f.optString("mime"), f.optLong("size"))
        }
        if (m.content.isNotBlank()) {
            var makeMenu by remember { mutableStateOf(false) }
            val canZip = remember(m.content) { com.gravity.ide.core.MakeFiles.codeFiles(m.content).isNotEmpty() }
            val canImage = remember(m.content) { com.gravity.ide.core.MakeFiles.svg(m.content) != null || com.gravity.ide.core.MakeFiles.picturePrompt(m.content) != null }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAction(Icons.Filled.ContentCopy, "Copy reply", onCopy)
                Box {
                    SmallAction(Icons.Filled.Download, "Save as a file", { makeMenu = true })
                    DropdownMenu(expanded = makeMenu, onDismissRequest = { makeMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("PDF") }, leadingIcon = { Icon(Icons.Filled.PictureAsPdf, null) },
                            onClick = { makeMenu = false; onMake(com.gravity.ide.core.MakeFiles.Kind.PDF) },
                        )
                        DropdownMenuItem(
                            text = { Text("Audio (read aloud)") }, leadingIcon = { Icon(Icons.Filled.GraphicEq, null) },
                            onClick = { makeMenu = false; onMake(com.gravity.ide.core.MakeFiles.Kind.AUDIO) },
                        )
                        if (canZip) DropdownMenuItem(
                            text = { Text("ZIP of the code") }, leadingIcon = { Icon(Icons.Filled.FolderZip, null) },
                            onClick = { makeMenu = false; onMake(com.gravity.ide.core.MakeFiles.Kind.ZIP) },
                        )
                        if (canImage) DropdownMenuItem(
                            text = { Text("Picture (PNG)") }, leadingIcon = { Icon(Icons.Filled.Image, null) },
                            onClick = { makeMenu = false; onMake(com.gravity.ide.core.MakeFiles.Kind.IMAGE) },
                        )
                        if (canImage) DropdownMenuItem(
                            text = { Text("Video (MP4, a scene per picture)") }, leadingIcon = { Icon(Icons.Filled.Movie, null) },
                            onClick = { makeMenu = false; onMake(com.gravity.ide.core.MakeFiles.Kind.VIDEO) },
                        )
                    }
                }
                if (isLast && !busy) SmallAction(Icons.Filled.Refresh, "Regenerate reply", onRetry)
                SmallAction(Icons.Filled.Share, "Share reply", onShare)
                // Read the reply aloud (tap again to stop).
                val speakingId by com.gravity.ide.files.ReplySpeaker.speakingId.collectAsState()
                val speakContext = LocalContext.current
                SmallAction(
                    if (speakingId == m.id) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                    if (speakingId == m.id) "Stop reading aloud" else "Read aloud",
                    { com.gravity.ide.files.ReplySpeaker.toggle(speakContext, m.id, m.content) },
                )
                SmallAction(Icons.Filled.Delete, "Delete reply", onDelete)
                Spacer(Modifier.weight(1f))
                m.model?.let {
                    Text("${shortModelName(it)} · ${relativeTime(m.createdAt)}", style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted, maxLines = 1)
                }
            }
        }
    }
}

private val ACTIVE_TASK = setOf("PENDING", "RUNNING", "WAITING_APPROVAL", "WAITING_NETWORK")

/** A build or change of an app, shown in the chat with live progress and the result. */
@Composable
private fun AppTaskCard(projectId: String, taskId: String, isChange: Boolean, onOpenApp: (String) -> Unit, onRetryApp: (String, String) -> Unit) {
    val c = LocalAppContainer.current
    val task by remember(taskId) { c.db.tasks().observeTask(taskId) }.collectAsState(initial = null)
    val project by remember(projectId) { c.db.projects().observe(projectId) }.collectAsState(initial = null)
    val live by c.agent.live.collectAsState()
    val t = task
    val status = t?.status
    val running = status == null || status in ACTIVE_TASK
    // What the builder did, step by step, from its real task log (BuildNarration).
    val log by remember(taskId) { c.db.events().observeForTask(taskId) }.collectAsState(initial = emptyList())
    val steps = remember(log) { com.gravity.ide.core.BuildNarration.lines(log.map { com.gravity.ide.core.BuildNarration.Entry(it.category, it.title, it.detail, it.level) }) }
    var showSteps by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(18.dp), color = GravityColors.Surface, border = BorderStroke(1.dp, GravityColors.Outline),
        modifier = Modifier.fillMaxWidth().testTag("app-card"),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(GravityColors.Accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PhoneAndroid, null, tint = GravityColors.Accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(project?.name ?: "App", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            running && status == "WAITING_NETWORK" -> "Waiting for internet…"
                            running && status == "WAITING_APPROVAL" -> "Waiting for your approval"
                            running -> if (isChange) "Making your change…" else "Building your app…"
                            status == "COMPLETED" -> if (isChange) "Change applied" else "Ready"
                            status == "FAILED" -> "Didn't work"
                            else -> status?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty()
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when (status) {
                            "COMPLETED" -> GravityColors.Green
                            "FAILED" -> GravityColors.Red
                            else -> GravityColors.Muted
                        },
                        modifier = Modifier.testTag("app-card-status"),
                    )
                }
                if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (running) {
                // Live: each step as it happens; the last one is in progress.
                val shown = steps.takeLast(6)
                if (shown.isEmpty()) {
                    t?.stage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted, maxLines = 2, modifier = Modifier.padding(top = 8.dp)) }
                }
                Column(Modifier.padding(top = 8.dp).testTag("app-card-steps"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.forEachIndexed { i, line -> BuildStep(line, current = i == shown.lastIndex) }
                }
                live[taskId]?.takeIf { it.isNotBlank() }?.let {
                    Text("Written so far: ${"%,d".format(it.length)} characters", style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted, modifier = Modifier.padding(top = 4.dp))
                }
            } else if (steps.isNotEmpty()) {
                TextButton(onClick = { showSteps = !showSteps }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp), modifier = Modifier.testTag("app-card-show-steps")) {
                    Text((if (showSteps) "Hide what I did" else "What I did (${steps.size} steps)") + if (showSteps) " ▴" else " ▾", style = MaterialTheme.typography.labelMedium)
                }
                if (showSteps) {
                    Column(Modifier.testTag("app-card-steps"), verticalArrangement = Arrangement.spacedBy(3.dp)) { steps.forEach { BuildStep(it, current = false) } }
                }
            }
            if (status == "COMPLETED") t?.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            if (status == "FAILED") t?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GravityColors.Red, modifier = Modifier.padding(top = 8.dp)) }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (status == "COMPLETED") {
                    Surface(onClick = { preview = !preview }, shape = RoundedCornerShape(50), color = GravityColors.Accent, modifier = Modifier.testTag("preview-app")) {
                        Text(if (preview) "Hide preview" else "▶ Preview", color = GravityColors.OnAccent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                    TextButton(onClick = { onOpenApp(projectId) }, modifier = Modifier.testTag("open-app")) { Text("Open app") }
                } else {
                    TextButton(onClick = { onOpenApp(projectId) }, modifier = Modifier.testTag("open-app")) { Text("Open app") }
                }
                if (status == "FAILED" || status == "CANCELLED" || status == "INTERRUPTED") {
                    TextButton(onClick = { onRetryApp(projectId, taskId) }) { Text("Try again") }
                }
            }
            // The app itself, running right here in the chat (the same live preview as the App screen).
            val p = project
            if (preview && status == "COMPLETED" && p != null) {
                val errors = remember { androidx.compose.runtime.mutableStateListOf<String>() }
                var reload by remember { mutableStateOf(0) }
                Surface(
                    shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, GravityColors.Outline),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(480.dp),
                ) {
                    if (java.io.File(p.rootPath, "index.html").isFile) AppPreview(p, reload, errors, Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This app has no index.html yet.", color = GravityColors.Muted) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { reload++ }) { Text("Reload") }
                    if (errors.isNotEmpty()) Text("${errors.size} JavaScript error(s): ${errors.last().take(80)}", style = MaterialTheme.typography.labelSmall, color = GravityColors.Red, maxLines = 2)
                }
            }
        }
    }
}

/** One step of an app build in the chat: a dot, or a small spinner for the step in progress. */
@Composable
private fun BuildStep(line: String, current: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 4.dp).size(12.dp), contentAlignment = Alignment.Center) {
            if (current) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
            else Box(Modifier.size(5.dp).clip(CircleShape).background(GravityColors.Muted))
        }
        Spacer(Modifier.width(8.dp))
        Text(line, style = MaterialTheme.typography.bodySmall, color = if (current) MaterialTheme.colorScheme.onSurface else GravityColors.Muted)
    }
}

@Composable
private fun SmallAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, description, tint = GravityColors.Muted, modifier = Modifier.size(17.dp))
    }
}

/** The reply while it is being generated: animated dots until the first words arrive. */
@Composable
internal fun StreamingBubble(text: String) {
    Column(Modifier.fillMaxWidth()) {
        if (text.isEmpty()) TypingDots() else RichText("$text ▍")
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(animation = tween(520), repeatMode = RepeatMode.Reverse, initialStartOffset = StartOffset(i * 170)),
                label = "dot$i",
            )
            Box(Modifier.padding(end = 5.dp).size(8.dp).alpha(a).clip(CircleShape).background(GravityColors.Cyan))
        }
        Spacer(Modifier.width(4.dp))
        Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = GravityColors.Muted)
    }
}

private fun attachmentIcon(kind: String): ImageVector = when (kind) {
    "image" -> Icons.Filled.Image
    "pdf" -> Icons.Filled.PictureAsPdf
    "zip" -> Icons.Filled.FolderZip
    else -> Icons.Filled.Description
}

@Composable
private fun AttachmentTag(name: String, kind: String, size: Long) {
    Surface(shape = RoundedCornerShape(12.dp), color = GravityColors.SurfaceHigh, border = BorderStroke(1.dp, GravityColors.Outline)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(attachmentIcon(kind), null, tint = GravityColors.Cyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            if (size > 0) Text(" · ${formatBytes(size)}", style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted)
        }
    }
}

/**
 * Claude-style message box: the text on top; below it the attach menu, the Chat / App switch,
 * the model button, voice input and a round send/stop button.
 */
@Composable
internal fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    attachments: List<Attachment>,
    onRemoveAttachment: (Attachment) -> Unit,
    onAttachFiles: () -> Unit,
    onAttachImages: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    appMode: Boolean = false,
    onModeChange: (Boolean) -> Unit = {},
    /** The Chat / App switch in the box (the chat screen shows it at the top of a new chat instead). */
    showModeSwitch: Boolean = true,
    /** The chat already has messages (App mode then changes the app it built). */
    started: Boolean = false,
    modelLabel: ModelLabel? = null,
    onPickModel: () -> Unit = {},
    /** Live voice mode; when set, the round button starts it while the message box is empty. */
    onLive: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(24.dp), color = GravityColors.SurfaceHigh, border = BorderStroke(1.dp, GravityColors.Outline),
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 10.dp),
    ) {
        Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)) {
            // A Coder model chosen for normal chat: say so once, with a way to switch.
            var hideCoderHint by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            if (!appMode && !hideCoderHint && modelLabel != null && modelLabel.isLocal && modelLabel.text.contains("coder", ignoreCase = true)) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp).testTag("coder-hint"), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Coder models are made for code and apps; their Hindi chat is weak. For Hindi / Hinglish talk, tap here and pick Gemma 3 4B (Phone Models → Recommended) or Gemini.",
                        style = MaterialTheme.typography.labelSmall, color = GravityColors.Amber,
                        modifier = Modifier.weight(1f).clickable(onClick = onPickModel),
                    )
                    IconButton(onClick = { hideCoderHint = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "Hide", modifier = Modifier.size(14.dp), tint = GravityColors.Muted)
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                LazyRow(Modifier.padding(start = 6.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(attachments) { a ->
                        Surface(shape = RoundedCornerShape(14.dp), color = GravityColors.Surface, border = BorderStroke(1.dp, GravityColors.Outline)) {
                            Row(Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(attachmentIcon(a.kind), null, tint = GravityColors.Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.widthIn(max = 150.dp)) {
                                    Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatBytes(a.size), style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted)
                                }
                                IconButton(onClick = { onRemoveAttachment(a) }, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Filled.Close, "Remove ${a.name}", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            TextField(
                value = value, onValueChange = onValueChange,
                placeholder = {
                    Text(
                        when {
                            appMode && started -> "Describe a change to this app…"
                            appMode -> "Describe the app (📎 add a design picture to copy it)…"
                            else -> "Message Gravity…"
                        },
                        color = GravityColors.Muted,
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 200.dp).testTag("chat-input"),
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp).testTag("attach-button")) {
                        Icon(Icons.Filled.Add, "Attach files or photos", tint = GravityColors.Muted)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Files (code, text, PDF, ZIP)") }, leadingIcon = { Icon(Icons.Filled.AttachFile, null) },
                            onClick = { menu = false; onAttachFiles() },
                        )
                        DropdownMenuItem(
                            text = { Text("Photos and images") }, leadingIcon = { Icon(Icons.Filled.Image, null) },
                            onClick = { menu = false; onAttachImages() },
                        )
                    }
                }
                if (showModeSwitch) ModeSwitch(appMode, onModeChange)
                Spacer(Modifier.weight(1f))
                if (modelLabel != null) ModelPill(modelLabel, onPickModel, Modifier.widthIn(max = 140.dp))
                IconButton(onClick = onMic, modifier = Modifier.size(40.dp).testTag("mic-button")) {
                    Icon(Icons.Filled.Mic, "Voice input", tint = GravityColors.Muted)
                }
                val canSend = value.isNotBlank() || attachments.isNotEmpty()
                // Empty box: the round button starts live voice mode (like a call); with text it sends.
                val liveInstead = !busy && !canSend && onLive != null
                val brush = when {
                    busy -> SolidColor(GravityColors.Red)
                    canSend || liveInstead -> SolidColor(GravityColors.Accent)
                    else -> SolidColor(GravityColors.Outline)
                }
                // A little smaller than a standard icon button (40dp circle), like Claude's send button.
                Box(
                    Modifier.padding(start = 4.dp).size(40.dp).clip(CircleShape).background(brush, CircleShape)
                        .clickable(onClickLabel = if (busy) "Stop" else if (liveInstead) "Live voice mode" else "Send") {
                            if (busy) onStop() else if (liveInstead) onLive?.invoke() else onSend()
                        }
                        .testTag(if (liveInstead) "live-button" else "send-button"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) Icon(Icons.Filled.Stop, "Stop", tint = Color.White, modifier = Modifier.size(18.dp))
                    else if (liveInstead) Icon(Icons.Filled.GraphicEq, "Live voice mode", tint = GravityColors.OnAccent, modifier = Modifier.size(20.dp))
                    else Icon(
                        Icons.Filled.ArrowUpward, "Send",
                        tint = if (canSend) GravityColors.OnAccent else if (GravityColors.isDark) Color.White else GravityColors.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Chat or App: what a message does (answer in the chat, or build / change an app). */
@Composable
internal fun ModeSwitch(appMode: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(GravityColors.Surface).padding(3.dp)) {
        for ((app, label) in listOf(false to "Chat", true to "App")) {
            val on = appMode == app
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) GravityColors.Accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onChange(app) }.padding(horizontal = 12.dp, vertical = 5.dp)
                    .testTag(if (app) "mode-app" else "mode-chat"),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) GravityColors.Accent else GravityColors.Muted)
            }
        }
    }
}

/**
 * Bottom sheet for choosing who answers: Auto, any cloud model the user's key can use (listed
 * live from the provider), or a downloaded on-device model. Also holds reasoning, web search and
 * project options.
 */
@Composable
internal fun ModelPickerSheet(
    chat: ChatEntity,
    providers: List<ProviderEntity>,
    projects: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onUpdate: (ChatEntity) -> Unit,
    onAddAi: () -> Unit,
    go: (String) -> Unit,
) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localModels by c.db.models().observe().collectAsState(initial = emptyList())
    val localState by c.localHost.state.collectAsState()
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmLocal by remember { mutableStateOf<Pair<LocalModelEntity, String>?>(null) }
    var loadingId by remember { mutableStateOf<String?>(null) }
    val loaded = (localState as? LocalModelHost.State.Ready)?.loaded
    var loadFailure by remember { mutableStateOf<String?>(null) }

    fun useLocal(m: LocalModelEntity) {
        scope.launch {
            loadingId = m.id
            try {
                val info = c.device.info()
                c.localHost.load(m, PerformanceManager.contextSize(settings, info), PerformanceManager.threads(settings, info))
                onUpdate(chat.copy(providerId = LocalLlamaProvider.ID, model = null))
                toast(context, "Using ${m.name} on this phone")
                onDismiss()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The whole reason, not a cut-off toast: what failed, every way Gravity tried, and the phone.
                loadFailure = buildString {
                    appendLine(e.message ?: e.toString())
                    val attempts = c.localHost.lastAttempts
                    if (attempts.isNotEmpty()) {
                        appendLine()
                        appendLine("What Gravity tried:")
                        attempts.forEach { appendLine("• $it") }
                    }
                    appendLine()
                    append("Phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
                    if (android.os.Build.VERSION.SDK_INT >= 31) append(" · ${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}")
                    append(" · app ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
                }
            } finally {
                loadingId = null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = GravityColors.Surface) {
        LazyColumn(
            Modifier.fillMaxWidth().testTag("model-sheet"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column {
                    Text("Choose AI model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cloud models give the best answers. Phone models work offline and keep everything on this device.",
                        style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted,
                    )
                }
            }
            item {
                PickerRow(
                    Icons.Filled.AutoAwesome, "Auto (best available)", "Your default AI from Settings; the phone model when offline",
                    selected = chat.providerId == null,
                ) { onUpdate(chat.copy(providerId = null, model = null)); onDismiss() }
            }
            item { SectionLabel("Cloud AI") }
            val cloud = providers.filter { it.enabled && it.kind != ProviderKind.LOCAL.name }
            if (cloud.isEmpty()) {
                item { PickerRow(Icons.Filled.Add, "Add a free Gemini key", "Best quality. No subscription.", false, tint = GravityColors.Green) { onAddAi() } }
            }
            items(cloud, key = { it.id }) { p ->
                CloudModels(p, chat) { model -> onUpdate(chat.copy(providerId = p.id, model = model.ifBlank { null })); onDismiss() }
            }
            item { SectionLabel("On this phone (offline)") }
            if (!c.localHost.runtimeAvailable) {
                item { Text(c.localHost.runtimeError ?: "This build has no on-device AI runtime.", style = MaterialTheme.typography.bodySmall, color = GravityColors.Amber) }
            }
            val ready = localModels.filter { it.status == ModelStatus.READY.name }
            if (ready.isEmpty()) {
                item {
                    PickerRow(Icons.Filled.Download, "Download a phone model", "Model Manager → Search Hub", false, tint = GravityColors.Purple) {
                        onDismiss(); go(Routes.models(2))
                    }
                }
            }
            items(ready, key = { it.id }) { m ->
                val isLoaded = loaded?.modelId == m.id
                val detail = buildString {
                    append(formatBytes(m.sizeBytes))
                    m.quantization?.let { append(" · ").append(it) }
                    append(if (isLoaded) " · loaded" else " · tap to load")
                }
                PickerRow(
                    Icons.Filled.PhoneAndroid, m.name, detail,
                    selected = chat.providerId == LocalLlamaProvider.ID && isLoaded, loading = loadingId == m.id, tint = GravityColors.Purple,
                ) {
                    if (loadingId != null) return@PickerRow
                    val warn = if (isLoaded) null else c.localHost.memoryWarning(m, settings.localContextSize)
                    if (warn != null) confirmLocal = m to warn else useLocal(m)
                }
            }
            item { SectionLabel("Options") }
            item {
                Column {
                    Text("Thinking", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val current = ReasoningMode.fromName(chat.reasoning)
                        for (mode in ReasoningMode.entries) {
                            FilterChip(selected = mode == current, onClick = { onUpdate(chat.copy(reasoning = mode.name)) }, label = { Text(mode.label) })
                        }
                    }
                    Text(ReasoningMode.fromName(chat.reasoning).description, style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Web search", style = MaterialTheme.typography.labelLarge)
                        Text("Google Search grounding (Gemini only)", style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted)
                    }
                    Switch(checked = chat.webGrounding, onCheckedChange = { onUpdate(chat.copy(webGrounding = it)) })
                }
            }
            item {
                val options = listOf<Pair<String?, String>>(null to "No project") + projects.map { it.first to it.second }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, tint = GravityColors.Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Selector(
                        "Project context (its file list is sent to the model)", options, options.firstOrNull { it.first == chat.projectId },
                        { it.second }, { onUpdate(chat.copy(projectId = it.first)) }, modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onDismiss(); go(Routes.PROVIDERS) }) {
                        Icon(Icons.Filled.Settings, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("AI providers")
                    }
                    TextButton(onClick = { onDismiss(); go(Routes.models()) }) {
                        Icon(Icons.Filled.Tune, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Model Manager")
                    }
                }
            }
        }
    }

    loadFailure?.let { report ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { loadFailure = null },
            modifier = Modifier.testTag("load-failure"),
            title = { Text("The phone model did not load") },
            text = {
                SelectionContainer {
                    Text(
                        report, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)); toast(context, "Copied. Paste it in your message.") }) { Text("Copy") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { loadFailure = null; onDismiss(); go(Routes.models(0)) }) { Text("Full check") }
                    TextButton(onClick = { loadFailure = null }) { Text("Close") }
                }
            },
        )
    }

    confirmLocal?.let { (m, warn) ->
        AlertDialog(
            onDismissRequest = { confirmLocal = null },
            title = { Text("Load ${m.name}?") },
            text = { Text(warn) },
            confirmButton = { TextButton(onClick = { confirmLocal = null; useLocal(m) }) { Text("Load anyway") } },
            dismissButton = { TextButton(onClick = { confirmLocal = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = GravityColors.Cyan, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun PickerRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    loading: Boolean = false,
    tint: Color = GravityColors.Cyan,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp),
        color = if (selected) GravityColors.Cyan.copy(alpha = 0.10f) else GravityColors.SurfaceHigh,
        border = BorderStroke(1.dp, if (selected) GravityColors.Cyan else GravityColors.Outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted)
            }
            when {
                loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                selected -> Icon(Icons.Filled.CheckCircle, "Selected", tint = GravityColors.Cyan)
            }
        }
    }
}

/** A cloud provider's models, listed live with the user's key; the recommended one first. */
@Composable
private fun CloudModels(p: ProviderEntity, chat: ChatEntity, onPick: (String) -> Unit) {
    val c = LocalAppContainer.current
    var models by remember(p.id) { mutableStateOf<List<String>?>(null) }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var showAll by remember(p.id) { mutableStateOf(false) }
    LaunchedEffect(p.id) {
        try {
            models = ModelPicker.rankForChat(c.providers.build(p).listModels())
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    val current = if (chat.providerId == p.id) chat.model ?: p.defaultModel else null
    val isGemini = p.kind == ProviderKind.GEMINI.name
    Surface(shape = RoundedCornerShape(16.dp), color = GravityColors.SurfaceHigh, border = BorderStroke(1.dp, if (current != null) GravityColors.Cyan else GravityColors.Outline)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Cloud, GravityColors.Blue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text(runCatching { ProviderKind.valueOf(p.kind).label }.getOrDefault(p.kind), style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted)
                }
            }
            Spacer(Modifier.height(6.dp))
            val list = models
            when {
                error != null -> {
                    Text("Could not load the model list: $error", style = MaterialTheme.typography.bodySmall, color = GravityColors.Red)
                    ModelOption(p.defaultModel.ifBlank { "Provider default" }, recommended = false, selected = current != null) { onPick(p.defaultModel) }
                }
                list == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading models…", style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted)
                }
                list.isEmpty() -> Text("This key has no models available.", style = MaterialTheme.typography.bodySmall, color = GravityColors.Amber)
                else -> {
                    val recommended = if (isGemini) list.first() else p.defaultModel.takeIf { it in list } ?: list.first()
                    val general = if (isGemini) list.filter { ModelPicker.isGeneral(it) } else list
                    var shown = if (showAll) list else general.take(5)
                    if (current != null && current !in shown && current in list) shown = shown + current
                    for (id in shown) ModelOption(id, recommended = id == recommended, selected = id == current) { onPick(id) }
                    if (list.size > shown.size) TextButton(onClick = { showAll = true }) { Text("Show all ${list.size} models") }
                }
            }
        }
    }
}

@Composable
private fun ModelOption(id: String, recommended: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) GravityColors.Cyan.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null,
            tint = if (selected) GravityColors.Cyan else GravityColors.Muted, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(id, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (recommended) {
            Text(
                "Recommended", style = MaterialTheme.typography.labelSmall, color = GravityColors.Green,
                modifier = Modifier.background(GravityColors.Green.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Light Markdown: fenced code blocks (with a Copy button), headings, bullets, **bold** and `code`. */
@Composable
fun RichText(text: String) {
    val parts = remember(text) { text.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val firstLine = part.substringBefore('\n', "").trim()
                val language = firstLine.takeIf { it.isNotEmpty() && it.length <= 20 && it.none { ch -> ch.isWhitespace() } }
                val body = if (part.contains('\n')) part.substringAfter('\n') else part
                val content = if (language == null && firstLine.isNotEmpty()) part.trimEnd() else body.trimEnd()
                if (com.gravity.ide.core.ChatFormat.isProse(language, content)) {
                    // Lyrics, poems or letters that a model put in a code fence read as normal text.
                    MarkdownText(content.trim('\n'))
                } else if (language.equals("svg", ignoreCase = true) || (language == null && content.trimStart().startsWith("<svg"))) {
                    // A drawing the AI made for a picture or video: the file is what matters; the code is folded away.
                    var open by remember { mutableStateOf(false) }
                    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                        Text(if (open) "Hide the drawing's code ▴" else "🎨 Drawing (SVG code, ${content.length} characters) ▾", style = MaterialTheme.typography.labelMedium)
                    }
                    if (open) CodeBlock(content, "svg")
                } else {
                    CodeBlock(content, language)
                }
            } else if (part.isNotBlank()) {
                MarkdownText(part.trim('\n'))
            }
        }
    }
}

@Composable
private fun MarkdownText(md: String) {
    val lines = remember(md) { md.lines() }
    val bullet = remember { Regex("^(\\s*)[-*•]\\s+(.*)$") }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (raw in lines) {
            val line = raw.trimEnd()
            val b = bullet.find(line)
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.startsWith("### ") -> Text(inlineMarkdown(line.removePrefix("### ")), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                line.startsWith("## ") -> Text(inlineMarkdown(line.removePrefix("## ")), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                line.startsWith("# ") -> Text(inlineMarkdown(line.removePrefix("# ")), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                b != null -> Row(Modifier.padding(start = (b.groupValues[1].length * 6).dp)) {
                    Text("•", color = GravityColors.Cyan, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp))
                    Text(inlineMarkdown(b.groupValues[2]), style = MaterialTheme.typography.bodyLarge)
                }
                else -> Text(inlineMarkdown(line), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private val inlinePattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

private fun inlineMarkdown(s: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlinePattern.findAll(s)) {
        append(s.substring(last, m.range.first))
        val bold = m.groups[1]?.value
        if (bold != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CodeBg, color = GravityColors.Cyan)) { append(m.groupValues[2]) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

@Composable
fun CodeBlock(code: String, language: String?) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // Code stays dark in day mode too (light code text on a dark block reads well in both).
    Surface(shape = RoundedCornerShape(12.dp), color = CodeBg, border = BorderStroke(1.dp, Color(0xFF3D3D3A)), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().background(CodeHeaderBg).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language ?: "code", style = MaterialTheme.typography.labelMedium, color = Color(0xFFA3A097), modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)); toast(context, "Code copied") }) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFFE2E8F0), softWrap = false)
            }
        }
    }
}

/** A file Gravity made from a reply: preview for pictures, Open and Share. */
@Composable
private fun MadeFileCard(name: String, path: String, mime: String, size: Long) {
    val context = LocalContext.current
    val file = remember(path) { java.io.File(path) }
    val thumb = remember(path) {
        if (mime.startsWith("image/") && file.isFile) runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            android.graphics.BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
        }.getOrNull() else null
    }
    fun uri() = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    Surface(
        shape = RoundedCornerShape(16.dp), color = GravityColors.SurfaceHigh, border = BorderStroke(1.dp, GravityColors.Outline),
        modifier = Modifier.fillMaxWidth().testTag("made-file"),
    ) {
        Column(Modifier.padding(12.dp)) {
            thumb?.let {
                androidx.compose.foundation.Image(
                    it, name, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        mime == "application/pdf" -> Icons.Filled.PictureAsPdf
                        mime == "application/zip" -> Icons.Filled.FolderZip
                        mime.startsWith("image/") -> Icons.Filled.Image
                        mime.startsWith("audio/") -> Icons.Filled.GraphicEq
                        mime.startsWith("video/") -> Icons.Filled.Movie
                        else -> Icons.Filled.Description
                    },
                    null, tint = GravityColors.Accent, modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (file.isFile) formatBytes(size) else "File was removed", style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted)
                }
                if (file.isFile) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri(), mime)
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { toast(context, "No app on this phone opens ${file.extension.uppercase()} files; use Share") }
                    }) { Text("Open") }
                    TextButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime)
                            .putExtra(android.content.Intent.EXTRA_STREAM, uri()).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(android.content.Intent.createChooser(send, "Share $name").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Share") }
                }
            }
        }
    }
}
