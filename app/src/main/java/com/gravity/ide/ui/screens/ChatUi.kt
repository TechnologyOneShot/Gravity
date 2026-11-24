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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.material.icons.filled.Image
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

private val BrandGradient = Brush.linearGradient(listOf(GravityColors.Cyan, GravityColors.Purple))
private val UserGradient = Brush.linearGradient(listOf(Color(0xFF0E7490), Color(0xFF4338CA)))
private val CodeBg = Color(0xFF070A10)
private val CodeHeaderBg = Color(0xFF0F1520)

/** Gravity's round gradient avatar. */
@Composable
internal fun GravityAvatar(size: Dp = 30.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(BrandGradient), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/** What the chat will use, as shown on the model pill. */
internal data class ModelLabel(val text: String, val isLocal: Boolean, val needsAttention: Boolean)

internal fun modelLabelFor(chat: ChatEntity, providers: List<ProviderEntity>, loadedLocal: LocalModelHost.Loaded?): ModelLabel {
    val chosen = providers.firstOrNull { it.id == chat.providerId }
    return when {
        chat.providerId == LocalLlamaProvider.ID ->
            if (loadedLocal != null) ModelLabel("On phone · ${loadedLocal.name}", true, false) else ModelLabel("Phone model · not loaded", true, true)
        chosen != null -> ModelLabel("${chosen.name} · ${chat.model ?: chosen.defaultModel.ifBlank { "auto" }}", false, false)
        providers.any { it.enabled && it.kind != ProviderKind.LOCAL.name } -> ModelLabel("Auto · best available", false, false)
        loadedLocal != null -> ModelLabel("Auto · ${loadedLocal.name} (phone)", true, false)
        else -> ModelLabel("No AI model · tap to set up", false, true)
    }
}

/** The tappable "which AI answers" pill at the top of a chat. */
@Composable
internal fun ModelPill(label: ModelLabel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = GravityColors.SurfaceHigh,
        border = BorderStroke(1.dp, if (label.needsAttention) GravityColors.Amber else GravityColors.Cyan.copy(alpha = 0.45f)),
        modifier = modifier.testTag("model-pill"),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (label.isLocal) Icons.Filled.PhoneAndroid else Icons.Filled.Cloud, null,
                tint = if (label.needsAttention) GravityColors.Amber else GravityColors.Cyan, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(label.text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ExpandMore, "Change AI model", modifier = Modifier.size(18.dp), tint = GravityColors.Muted)
        }
    }
}

/** Empty-chat welcome: what the chat can do, which model answers, and starter prompts. */
@Composable
internal fun ChatWelcome(label: ModelLabel, onPickModel: () -> Unit, onSuggestion: (String) -> Unit, onBuildApp: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GravityAvatar(64.dp)
        Spacer(Modifier.height(14.dp))
        Text("How can I help?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Ask about code, attach files or photos, or just speak.",
            style = MaterialTheme.typography.bodyMedium, color = GravityColors.Muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(14.dp))
        Surface(
            onClick = onPickModel, shape = RoundedCornerShape(16.dp), color = GravityColors.SurfaceHigh,
            border = BorderStroke(1.dp, if (label.needsAttention) GravityColors.Amber else GravityColors.Outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (label.isLocal) Icons.Filled.PhoneAndroid else Icons.Filled.Cloud, if (label.needsAttention) GravityColors.Amber else GravityColors.Cyan)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (label.needsAttention) "Choose an AI to chat with" else "AI model", style = MaterialTheme.typography.labelMedium, color = GravityColors.Muted)
                    Text(label.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Change", color = GravityColors.Cyan, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(10.dp))
        val suggestions = listOf(
            Triple(Icons.Filled.Code, "Explain code", "Explain what this code does, step by step:\n"),
            Triple(Icons.Filled.BugReport, "Fix a bug", "Help me find and fix the bug in this code:\n"),
            Triple(Icons.Filled.Lightbulb, "Plan an idea", "Help me plan an app that "),
            Triple(Icons.Filled.Description, "Write a function", "Write a Kotlin function that "),
        )
        for (row in suggestions.chunked(2)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((icon, title, prompt) in row) {
                    Surface(
                        onClick = { onSuggestion(prompt) }, shape = RoundedCornerShape(16.dp), color = GravityColors.Surface,
                        border = BorderStroke(1.dp, GravityColors.Outline), modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Icon(icon, null, tint = GravityColors.Purple, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(title, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Surface(onClick = onBuildApp, shape = RoundedCornerShape(16.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.background(Brush.linearGradient(listOf(GravityColors.Cyan.copy(alpha = 0.18f), GravityColors.Purple.copy(alpha = 0.18f))), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PhoneIphone, null, tint = GravityColors.Cyan)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Want a whole app instead?", style = MaterialTheme.typography.titleSmall)
                    Text("Describe it on the Create screen and Gravity builds it.", style = MaterialTheme.typography.labelMedium, color = GravityColors.Muted)
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

/** One message. User messages are gradient bubbles; Gravity's replies have an avatar and actions. */
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
) {
    val atts = remember(m.attachments) {
        runCatching { JSONArray(m.attachments) }.getOrNull()?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    }
    if (m.role == "user") {
        var menu by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
            for (a in atts) {
                AttachmentTag(a.optString("name"), a.optString("kind"), a.optLong("size"))
                Spacer(Modifier.height(4.dp))
            }
            if (m.content.isNotBlank()) {
                Box {
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(UserGradient)
                            .clickable { menu = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(m.content, color = Color.White, style = MaterialTheme.typography.bodyLarge)
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        GravityAvatar()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Gravity" + (m.model?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium, color = GravityColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            if (m.content.isNotBlank()) {
                Surface(shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp), color = GravityColors.SurfaceHigh, border = BorderStroke(1.dp, GravityColors.Outline)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
            if (m.content.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallAction(Icons.Filled.ContentCopy, "Copy reply", onCopy)
                    if (isLast && !busy) SmallAction(Icons.Filled.Refresh, "Regenerate reply", onRetry)
                    SmallAction(Icons.Filled.Share, "Share reply", onShare)
                    SmallAction(Icons.Filled.Delete, "Delete reply", onDelete)
                    Spacer(Modifier.weight(1f))
                    if (m.model != null) {
                        Text(
                            "${m.inputTokens + m.outputTokens} tok · ${relativeTime(m.createdAt)}",
                            style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted,
                        )
                    }
                }
            }
        }
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        GravityAvatar()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Gravity", style = MaterialTheme.typography.labelMedium, color = GravityColors.Muted)
            Spacer(Modifier.height(4.dp))
            Surface(shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp), color = GravityColors.SurfaceHigh, border = BorderStroke(1.dp, GravityColors.Outline)) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (text.isEmpty()) TypingDots() else RichText("$text ▍")
                }
            }
        }
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

/** Pill-shaped message box with an attach menu, voice input and a round send/stop button. */
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
) {
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp)) {
        if (attachments.isNotEmpty()) {
            LazyRow(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(attachments) { a ->
                    Surface(shape = RoundedCornerShape(14.dp), color = GravityColors.SurfaceHigh, border = BorderStroke(1.dp, GravityColors.Outline)) {
                        Row(Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(attachmentIcon(a.kind), null, tint = GravityColors.Cyan, modifier = Modifier.size(18.dp))
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
        Surface(shape = RoundedCornerShape(28.dp), color = GravityColors.SurfaceHigh, border = BorderStroke(1.dp, GravityColors.Outline)) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("attach-button")) {
                        Icon(Icons.Filled.Add, "Attach files or photos", tint = GravityColors.Cyan)
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
                TextField(
                    value = value, onValueChange = onValueChange,
                    placeholder = { Text("Message Gravity…") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp).testTag("chat-input"),
                    maxLines = 8,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    ),
                )
                IconButton(onClick = onMic, modifier = Modifier.testTag("mic-button")) {
                    Icon(Icons.Filled.Mic, "Voice input", tint = GravityColors.Muted)
                }
                val canSend = value.isNotBlank() || attachments.isNotEmpty()
                val brush = when {
                    busy -> SolidColor(GravityColors.Red)
                    canSend -> BrandGradient
                    else -> SolidColor(GravityColors.Outline)
                }
                IconButton(
                    onClick = { if (busy) onStop() else onSend() },
                    modifier = Modifier.padding(2.dp).size(44.dp).clip(CircleShape).background(brush, CircleShape),
                ) {
                    if (busy) Icon(Icons.Filled.Stop, "Stop", tint = Color.White) else Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
                }
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

    fun useLocal(m: LocalModelEntity) {
        scope.launch {
            loadingId = m.id
            try {
                val info = c.device.info()
                c.localHost.load(m, PerformanceManager.contextSize(settings, info), PerformanceManager.threads(settings, info))
                onUpdate(chat.copy(providerId = LocalLlamaProvider.ID, model = null))
                toast(context, "Using ${m.name} on this phone")
                onDismiss()
            } catch (e: Exception) {
                toast(context, "Could not load ${m.name}: ${e.message}")
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
                CodeBlock(if (language == null && firstLine.isNotEmpty()) part.trimEnd() else body.trimEnd(), language)
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
    Surface(shape = RoundedCornerShape(12.dp), color = CodeBg, border = BorderStroke(1.dp, GravityColors.Outline), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().background(CodeHeaderBg).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language ?: "code", style = MaterialTheme.typography.labelMedium, color = GravityColors.Muted, modifier = Modifier.weight(1f))
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
